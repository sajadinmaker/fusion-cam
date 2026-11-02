//
// Fusion Cam · Phase 2: HDR+-style burst merge over RAW Bayer frames.
//
// Algorithm follows "Burst photography for high dynamic range and low-light
// imaging on mobile cameras" (Hasinoff et al., SIGGRAPH Asia 2016):
//   1. per-frame grayscale (2x2 Bayer block mean, clipped pixels excluded)
//   2. 4-level coarse-to-fine pyramid tile alignment (16px tiles, ±4 search)
//   3. per-tile merge with Wiener-like attenuation toward the noise floor,
//      hard ghost-discard
//   4. "finish": parity-aware bilinear demosaic + gamma -> sRGB pixels
//
// Also implements the universal-device fallback (mergeBurstYuv): the same
// align+merge machinery on the luma plane of YUV_420_888 frames for cameras
// without RAW_SENSOR (LEGACY/LIMITED HALs, i.e. most Android 8+ phones);
// chroma comes from the reference frame, BT.601 limited-range to sRGB.
//
// Phase 2 simplifications (revisit in Phase 3):
//   * constant noise-floor estimate (paper: per-frame noise model)
//   * even-only full-resolution offsets (keeps Bayer parity; costs 1px
//     alignment refinement)
//   * single thread
//
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

namespace {

using u16 = uint16_t;
using u8 = uint8_t;

constexpr int kTile = 16;           // tile size, level-pixel units
constexpr int kSearch = 4;          // search radius per pyramid level
constexpr int kLevels = 4;          // level0 = half-res gray
constexpr float kTau = 256.0f;      // noise floor for the Wiener weight
// Tile ghost discard sits a fixed multiple above the noise floor so a
// high-ISO merge (scaled tau) never trips it on noise alone.
constexpr float kGhostTauMultiple = 32.0f;
constexpr float kGhostDiscard = kGhostTauMultiple * kTau;
constexpr float kTauYuv = 16.0f;    // 8-bit luma noise floor (variance units)
constexpr int kClipYuv = 250;       // near-white Y sentinel threshold
constexpr u16 kInvalid = 0xFFFF;    // clipped sentinel in gray pyramids
constexpr int16_t kNoMatch = INT16_MAX;
constexpr int kBayerChannels = 4;   // R, G-even, G-odd, B

void channelParities(int cfa, int out[4][2]) {
    switch (cfa) {
        case 1: // GRBG
            out[0][0] = 1; out[0][1] = 0; out[1][0] = 0; out[1][1] = 0;
            out[2][0] = 1; out[2][1] = 1; out[3][0] = 0; out[3][1] = 1;
            break;
        case 2: // GBRG
            out[0][0] = 0; out[0][1] = 1; out[1][0] = 0; out[1][1] = 0;
            out[2][0] = 1; out[2][1] = 1; out[3][0] = 1; out[3][1] = 0;
            break;
        case 3: // BGGR
            out[0][0] = 1; out[0][1] = 1; out[1][0] = 1; out[1][1] = 0;
            out[2][0] = 0; out[2][1] = 0; out[3][0] = 0; out[3][1] = 1;
            break;
        default: // 0 RGGB, 4 RGB approximated as RGGB
            out[0][0] = 0; out[0][1] = 0; out[1][0] = 1; out[1][1] = 0;
            out[2][0] = 0; out[2][1] = 1; out[3][0] = 1; out[3][1] = 1;
            break;
    }
}

inline u16 sampleClamped(const u16* img, int W, int H, int x, int y) {
    x = std::min(std::max(x, 0), W - 1);
    y = std::min(std::max(y, 0), H - 1);
    return img[static_cast<size_t>(y) * W + x];
}

struct GrayPyramid {
    int w[kLevels] = {0, 0, 0, 0};
    int h[kLevels] = {0, 0, 0, 0};
    std::vector<u16> level[kLevels];
};

// level0: mean of each 2x2 Bayer block, kInvalid if any member clipped.
// level i>0: 2x2 box mean of level i-1, kInvalid if any member invalid.
GrayPyramid buildGray(const u16* postBlackRaw, int W, int H, int clipThresh) {
    GrayPyramid p;
    const int gw = W / 2, gh = H / 2;
    for (int l = 0; l < kLevels; l++) {
        p.w[l] = gw >> l;
        p.h[l] = gh >> l;
    }
    p.level[0].assign(static_cast<size_t>(gw) * gh, 0);
    for (int y = 0; y < gh; y++) {
        for (int x = 0; x < gw; x++) {
            const u16 v0 = postBlackRaw[static_cast<size_t>(2 * y) * W + 2 * x];
            const u16 v1 = postBlackRaw[static_cast<size_t>(2 * y) * W + 2 * x + 1];
            const u16 v2 = postBlackRaw[static_cast<size_t>(2 * y + 1) * W + 2 * x];
            const u16 v3 = postBlackRaw[static_cast<size_t>(2 * y + 1) * W + 2 * x + 1];
            const u16 hi = std::max(std::max(v0, v1), std::max(v2, v3));
            const u16 sum = static_cast<u16>((v0 + v1 + v2 + v3) / 4);
            p.level[0][static_cast<size_t>(y) * gw + x] = (hi >= clipThresh) ? kInvalid : sum;
        }
    }
    for (int l = 1; l < kLevels; l++) {
        const int pw = p.w[l - 1], ph = p.h[l - 1], cw = p.w[l], chh = p.h[l];
        p.level[l].assign(static_cast<size_t>(cw) * chh, 0);
        const auto& prev = p.level[l - 1];
        for (int y = 0; y < chh; y++) {
            for (int x = 0; x < cw; x++) {
                const u16 a = prev[static_cast<size_t>(2 * y) * pw + 2 * x];
                const u16 b = prev[static_cast<size_t>(2 * y) * pw + 2 * x + 1];
                const u16 c = prev[static_cast<size_t>(2 * y + 1) * pw + 2 * x];
                const u16 d = prev[static_cast<size_t>(2 * y + 1) * pw + 2 * x + 1];
                const u16 lo = std::min(std::min(a, b), std::min(c, d));
                const u16 sum = static_cast<u16>((a + b + c + d) / 4);
                p.level[l][static_cast<size_t>(y) * cw + x] = (lo == kInvalid) ? kInvalid : sum;
            }
        }
    }
    return p;
}

struct AlignOffsets {
    // level-0 tile grid only (what merge consumes); offsets in level-0 px.
    int tw = 0, th = 0;
    std::vector<int16_t> dy, dx;
};

// Coarse-to-fine tile alignment of `alt` onto `ref`.
AlignOffsets align(const GrayPyramid& ref, const GrayPyramid& alt) {
    AlignOffsets out;
    out.tw = ref.w[0] / kTile;
    out.th = ref.h[0] / kTile;
    out.dy.assign(static_cast<size_t>(out.tw) * out.th, 0);
    out.dx.assign(static_cast<size_t>(out.tw) * out.th, 0);

    // Per-level offset grids; coarser levels feed finer initial guesses.
    int gridsTw[kLevels], gridsTh[kLevels];
    std::vector<int16_t> gDy[kLevels], gDx[kLevels];
    for (int l = 0; l < kLevels; l++) {
        gridsTw[l] = ref.w[l] / kTile;
        gridsTh[l] = ref.h[l] / kTile;
        gDy[l].assign(static_cast<size_t>(gridsTw[l]) * gridsTh[l], 0);
        gDx[l].assign(static_cast<size_t>(gridsTw[l]) * gridsTh[l], 0);
    }

    for (int l = kLevels - 1; l >= 0; l--) {
        const int lw = ref.w[l], lh = ref.h[l];
        const int tw = gridsTw[l], th = gridsTh[l];
        const auto& rl = ref.level[l];
        const auto& al = alt.level[l];
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                const int ox = tx * kTile, oy = ty * kTile;
                int ody = 0, odx = 0;
                if (l < kLevels - 1 && gridsTw[l + 1] > 0 && gridsTh[l + 1] > 0) {
                    const int pty = std::min(ty / 2, gridsTh[l + 1] - 1);
                    const int ptx = std::min(tx / 2, gridsTw[l + 1] - 1);
                    const int16_t pdy = gDy[l + 1][static_cast<size_t>(pty * gridsTw[l + 1] + ptx)];
                    const int16_t pdx = gDx[l + 1][static_cast<size_t>(pty * gridsTw[l + 1] + ptx)];
                    if (pdy != kNoMatch && pdx != kNoMatch) {
                        ody = pdy * 2; odx = pdx * 2;
                    }
                }
                int bestDy = ody, bestDx = odx;
                double bestCost = 1e18; int bestValid = 0;
                // Border tiles: candidates whose alt tile extends past the
                // frame are evaluated on their overlap with the image only.
                // Out-of-bounds alt samples are skipped (never clamped), so
                // duplicated-edge content cannot fake a match; the existing
                // enough-valid guard rejects degenerate candidates.
                for (int sy = ody - kSearch; sy <= ody + kSearch; sy++) {
                    for (int sx = odx - kSearch; sx <= odx + kSearch; sx++) {
                        const int ax = ox + sx, ay = oy + sy;
                        double cost = 0.0; int valid = 0;
                        for (int cy = 0; cy < kTile; cy++) {
                            const int ayRow = ay + cy;
                            if (ayRow < 0 || ayRow >= lh) continue;
                            const size_t rbase = static_cast<size_t>(oy + cy) * lw;
                            const size_t abase = static_cast<size_t>(ayRow) * lw;
                            for (int cx = 0; cx < kTile; cx++) {
                                const int axCol = ax + cx;
                                if (axCol < 0 || axCol >= lw) continue;
                                const u16 rv = rl[rbase + ox + cx];
                                if (rv == kInvalid) continue;
                                const u16 av = al[abase + axCol];
                                if (av == kInvalid) continue;
                                const int d = static_cast<int>(rv) - static_cast<int>(av);
                                cost += static_cast<double>(d) * d;
                                valid++;
                            }
                        }
                        if (valid > 0 && cost / valid < bestCost) {
                            bestCost = cost / valid;
                            bestDy = sy; bestDx = sx; bestValid = valid;
                        }
                    }
                }
                const bool enoughValid = bestValid >= kTile * kTile / 4;
                const size_t lidx = static_cast<size_t>(ty) * tw + tx;
#ifdef ALIGN_DEBUG
                printf("  align l=%d tile(%d,%d) grid=%dx%d o=(%d,%d) best=(%d,%d) cost=%.0f valid=%d thr=%d\n",
                       l, tx, ty, tw, th, ody, odx, bestDy, bestDx, bestCost, bestValid, kTile * kTile / 4);
#endif
                if (enoughValid) {
                    gDy[l][lidx] = static_cast<int16_t>(bestDy);
                    gDx[l][lidx] = static_cast<int16_t>(bestDx);
                } else {
                    gDy[l][lidx] = kNoMatch;
                    gDx[l][lidx] = kNoMatch;
                }
            }
        }
    }
    out.dy = gDy[0];
    out.dx = gDx[0];
    return out;
}

// Per-pixel weighted merge into `merged` (pre-initialized from ref) using the
// level-0 alignments. Full-res tiles are 32x32 (level-0 tile * 2) so offsets
// stay even and Bayer parity is preserved.
//
// Weight model (paper's per-pixel Wiener attenuation, Phase 2 simplification):
//   w(pixel) = kTau / (kTau + d^2), d = ref - aligned alt at that pixel
// Flat/noisy pixels (|d| ~ noise) merge strongly; structural outliers
// (misalignment, object motion, boundary crossings) merge ~never.
// A tile whose mean diff^2 explodes is additionally hard-discardged.
//
// Border tiles may carry offsets that push the alt read out of the frame;
// alt pixels outside [0,H)x[0,W) are skipped (never clamped), consistent
// with the overlap-only alignment. `tau` is the per-pixel Wiener noise
// floor in variance units — callers scale it for ISO/noise conditions, and
// `ghostDiscard` should ride along (kGhostTauMultiple × tau) so high-ISO
// noise never discards tiles on its own.
void merge(const u16* ref, const std::vector<u16*>& alts,
           const std::vector<AlignOffsets>& offsets, int W, int H,
           int clipThresh, float tau, float ghostDiscard,
           u16* merged, int tyBegin, int tyEnd) {
    const int tw = offsets.empty() ? 0 : offsets[0].tw;
    const int th = offsets.empty() ? 0 : offsets[0].th;
    for (int ty = tyBegin; ty < tyEnd && ty < th; ty++) {
        for (int tx = 0; tx < tw; tx++) {
            const int ox = tx * kTile * 2, oy = ty * kTile * 2;
            if (ox + kTile * 2 > W || oy + kTile * 2 > H) continue;
            // Tile-level ghost discard first: mean diff^2 per alt frame.
            size_t nAlt = alts.size() < 16 ? alts.size() : 16;
            bool discard[16] = {};
            for (size_t a = 0; a < nAlt; a++) {
                const int16_t pdy = offsets[a].dy[static_cast<size_t>(ty) * tw + tx];
                const int16_t pdx = offsets[a].dx[static_cast<size_t>(ty) * tw + tx];
                if (pdy == kNoMatch || pdx == kNoMatch) { discard[a] = true; continue; }
                const int oyA = oy + pdy * 2, oxA = ox + pdx * 2;
                double diff = 0.0; int valid = 0;
                for (int cy = 0; cy < kTile * 2; cy++) {
                    const int ay = oyA + cy;
                    if (ay < 0 || ay >= H) continue;
                    const size_t rbase = static_cast<size_t>(oy + cy) * W;
                    const size_t abase = static_cast<size_t>(ay) * W;
                    for (int cx = 0; cx < kTile * 2; cx++) {
                        const int axc = oxA + cx;
                        if (axc < 0 || axc >= W) continue;
                        const u16 rv = ref[rbase + ox + cx];
                        if (rv >= clipThresh) continue;
                        const u16 av = alts[a][abase + axc];
                        if (av >= clipThresh) continue;
                        const int d = static_cast<int>(rv) - static_cast<int>(av);
                        diff += static_cast<double>(d) * d;
                        valid++;
                    }
                }
                if (valid == 0) { discard[a] = true; continue; }
                discard[a] = static_cast<float>(diff / valid) > ghostDiscard;
            }
            // Weighted pixel merge inside the tile, per-pixel weights.
            for (int cy = 0; cy < kTile * 2; cy++) {
                const size_t rbase = static_cast<size_t>(oy + cy) * W;
                for (int cx = 0; cx < kTile * 2; cx++) {
                    const size_t ridx = rbase + ox + cx;
                    const u16 rv = ref[ridx];
                    if (rv >= clipThresh) { merged[ridx] = rv; continue; }
                    double num = rv; double den = 1.0;
                    for (size_t a = 0; a < nAlt; a++) {
                        if (discard[a]) continue;
                        const int16_t pdy = offsets[a].dy[static_cast<size_t>(ty) * tw + tx];
                        const int16_t pdx = offsets[a].dx[static_cast<size_t>(ty) * tw + tx];
                        const int ay = oy + cy + pdy * 2;
                        const int axc = ox + cx + pdx * 2;
                        if (ay < 0 || ay >= H || axc < 0 || axc >= W) continue;
                        const u16 av = alts[a][static_cast<size_t>(ay) * W + axc];
                        if (av >= clipThresh) continue;
                        const int d = static_cast<int>(rv) - static_cast<int>(av);
                        const float w = tau / (tau + static_cast<float>(d) * d);
                        num += w * av;
                        den += w;
                    }
                    merged[ridx] = static_cast<u16>(std::min(num / den, static_cast<double>(clipThresh - 1)));
                }
            }
        }
    }
}

// Threaded driver: stripes the merge over tile rows, one stripe per worker.
// Sharpness of the weight model is unchanged; workers write disjoint tile-row
// bands of `merged`, so no synchronization is needed beyond the joins.
void mergeThreaded(const u16* ref, const std::vector<u16*>& alts,
                   const std::vector<AlignOffsets>& offsets, int W, int H,
                   int clipThresh, float tau, float ghostDiscard, u16* merged) {
    const int th = offsets.empty() ? 0 : offsets[0].th;
    if (th <= 0) return;
    const unsigned hw = std::thread::hardware_concurrency();
    const int workers = static_cast<int>(
        std::min<size_t>(std::max(hw, 1u), static_cast<size_t>(th)));
    std::vector<std::thread> pool;
    for (int t = 0; t < workers; t++) {
        pool.emplace_back([&, t]() {
            for (int ty = t; ty < th; ty += workers) {
                merge(ref, alts, offsets, W, H, clipThresh, tau, ghostDiscard,
                      merged, ty, ty + 1);
            }
        });
    }
    for (auto& thr : pool) thr.join();
}

// Finish: parity-aware bilinear demosaic + WB gains + gamma, no RGB planes.
// G is native at every green position; R/B fill from parity-derived neighbors.
// wb[] holds per-channel gains (R, G, B); 1/1/1 = neutral. Applied pre-gamma.
void finish(const u16* merged, int W, int H, int cfa, int clipRange,
            const float wb[3], jint* outPixels) {
    int par[4][2];
    channelParities(cfa, par);
    const float norm = 1.0f / (clipRange > 0 ? clipRange : 1);
    // One gamma LUT per output channel; entry v = gamma(clamp(v * wb[ch])).
    std::vector<jint> gammaLut[3];
    for (int ch = 0; ch < 3; ch++) {
        gammaLut[ch].resize(65536);
        for (int v = 0; v < 65536; v++) {
            const float x = std::min(std::max(v * norm * wb[ch], 0.0f), 1.0f);
            gammaLut[ch][v] = static_cast<jint>(std::pow(x, 1.0f / 2.2f) * 255.0f + 0.5f);
        }
    }
    // g: 0=R, 1=G, 2=B (wb index and LUT index coincide).
    auto gamma8 = [&gammaLut](int g, u16 v) -> jint { return gammaLut[g][v]; };

    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            const u16 v = merged[static_cast<size_t>(y) * W + x];
            const int px = x & 1, py = y & 1;
            // Identify which channel is native here.
            int nativeCh;
            if (px == par[0][0] && py == par[0][1]) nativeCh = 0;
            else if (px == par[3][0] && py == par[3][1]) nativeCh = 3;
            else nativeCh = 1; // green (both green parities)
            const int nGain = (nativeCh == 0) ? 0 : (nativeCh == 3) ? 2 : 1;

            jint rr, gg, bb;
            const jint g8 = gamma8(nGain, v);
            if (nativeCh == 1) {
                gg = g8;
                jint fillSum[2] = {0, 0}; // index 0 -> R, 1 -> B
                int fillCount[2] = {0, 0};
                for (int chIdx = 0; chIdx < 2; chIdx++) {
                    const int ch = (chIdx == 0) ? 0 : 3;
                    const int chx = static_cast<int>(par[ch][0]), chy = static_cast<int>(par[ch][1]);
                    const int gain = (chIdx == 0) ? 0 : 2;
                    // Parity relation decides neighbor offsets.
                    const bool differX = (chx != px), differY = (chy != py);
                    if (differX && differY) {
                        fillSum[chIdx] += gamma8(gain, sampleClamped(merged, W, H, x - 1, y - 1)) +
                                          gamma8(gain, sampleClamped(merged, W, H, x + 1, y - 1)) +
                                          gamma8(gain, sampleClamped(merged, W, H, x - 1, y + 1)) +
                                          gamma8(gain, sampleClamped(merged, W, H, x + 1, y + 1));
                        fillCount[chIdx] = 4;
                    } else if (differX) {
                        fillSum[chIdx] += gamma8(gain, sampleClamped(merged, W, H, x - 1, y)) +
                                          gamma8(gain, sampleClamped(merged, W, H, x + 1, y));
                        fillCount[chIdx] = 2;
                    } else if (differY) {
                        fillSum[chIdx] += gamma8(gain, sampleClamped(merged, W, H, x, y - 1)) +
                                          gamma8(gain, sampleClamped(merged, W, H, x, y + 1));
                        fillCount[chIdx] = 2;
                    } else {
                        // Same parity as a non-green channel: we mis-typed the
                        // pixel; fall back to the native value.
                        fillSum[chIdx] = g8; fillCount[chIdx] = 1;
                    }
                }
                rr = fillCount[0] > 0 ? fillSum[0] / fillCount[0] : 0;
                bb = fillCount[1] > 0 ? fillSum[1] / fillCount[1] : 0;
            } else if (nativeCh == 0) {
                rr = g8;
                gg = (gamma8(1, sampleClamped(merged, W, H, x - 1, y)) +
                      gamma8(1, sampleClamped(merged, W, H, x + 1, y)) +
                      gamma8(1, sampleClamped(merged, W, H, x, y - 1)) +
                      gamma8(1, sampleClamped(merged, W, H, x, y + 1))) / 4;
                const int chx = static_cast<int>(par[3][0]), chy = static_cast<int>(par[3][1]);
                const bool dx = (chx != px), dy2 = (chy != py);
                bb = (dx && dy2)
                    ? (gamma8(2, sampleClamped(merged, W, H, x - 1, y - 1)) +
                       gamma8(2, sampleClamped(merged, W, H, x + 1, y - 1)) +
                       gamma8(2, sampleClamped(merged, W, H, x - 1, y + 1)) +
                       gamma8(2, sampleClamped(merged, W, H, x + 1, y + 1))) / 4
                    : (dx ? (gamma8(2, sampleClamped(merged, W, H, x - 1, y)) +
                             gamma8(2, sampleClamped(merged, W, H, x + 1, y))) / 2
                          : (gamma8(2, sampleClamped(merged, W, H, x, y - 1)) +
                             gamma8(2, sampleClamped(merged, W, H, x, y + 1))) / 2);
            } else {
                bb = g8;
                gg = (gamma8(1, sampleClamped(merged, W, H, x - 1, y)) +
                      gamma8(1, sampleClamped(merged, W, H, x + 1, y)) +
                      gamma8(1, sampleClamped(merged, W, H, x, y - 1)) +
                      gamma8(1, sampleClamped(merged, W, H, x, y + 1))) / 4;
                const int chx = static_cast<int>(par[0][0]), chy = static_cast<int>(par[0][1]);
                const bool dx = (chx != px), dy2 = (chy != py);
                rr = (dx && dy2)
                    ? (gamma8(0, sampleClamped(merged, W, H, x - 1, y - 1)) +
                       gamma8(0, sampleClamped(merged, W, H, x + 1, y - 1)) +
                       gamma8(0, sampleClamped(merged, W, H, x - 1, y + 1)) +
                       gamma8(0, sampleClamped(merged, W, H, x + 1, y + 1))) / 4
                    : (dx ? (gamma8(0, sampleClamped(merged, W, H, x - 1, y)) +
                             gamma8(0, sampleClamped(merged, W, H, x + 1, y))) / 2
                          : (gamma8(0, sampleClamped(merged, W, H, x, y - 1)) +
                             gamma8(0, sampleClamped(merged, W, H, x, y + 1))) / 2);
            }
            outPixels[static_cast<size_t>(y) * W + x] =
                (0xFF << 24) | (bb << 16) | (gg << 8) | rr;
        }
    }
}

// BT.601 limited-range YUV -> sRGB (the range camera YUV pipelines emit).
// Luma is the merged plane; chroma is the reference frame's — fusing
// misaligned chroma ghosts, so the fallback keeps chroma unfused.
void yuvToRgb(const u16* y, const u8* u, const u8* v, int W, int H, jint* outPixels) {
    const int cw = W / 2, chh = H / 2;
    for (int py = 0; py < H; py++) {
        const int cy = std::min(py / 2, chh - 1);
        const size_t ubase = static_cast<size_t>(cy) * cw;
        for (int px = 0; px < W; px++) {
            const int cx = std::min(px / 2, cw - 1);
            int yv = static_cast<int>(y[static_cast<size_t>(py) * W + px]) - 16;
            if (yv < 0) yv = 0;
            const int uc = static_cast<int>(u[ubase + cx]) - 128;
            const int vc = static_cast<int>(v[ubase + cx]) - 128;
            int r = (1192 * yv + 1634 * vc) >> 10;
            int g = (1192 * yv - 400 * uc - 833 * vc) >> 10;
            int b = (1192 * yv + 2066 * uc) >> 10;
            r = std::min(std::max(r, 0), 255);
            g = std::min(std::max(g, 0), 255);
            b = std::min(std::max(b, 0), 255);
            outPixels[static_cast<size_t>(py) * W + px] =
                (0xFF << 24) | (b << 16) | (g << 8) | r;
        }
    }
}

} // namespace

// ---------------------------------------------------------------- JNI entry

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_fusioncam_scanner_fusion_FusionNative_mergeBurst(
        JNIEnv* env, jclass /*klass*/, jobjectArray frames, jint refIndex,
        jint width, jint height, jint cfa,
        jintArray jBlackLevels, jint whiteLevel, jfloatArray jWbGains,
        jfloat tauScale) {

    const int W = width, H = height;
    const size_t pixels = static_cast<size_t>(W) * H;
    const jint n = env->GetArrayLength(frames);
    if (n < 2 || refIndex < 0 || refIndex >= n) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "need >=2 frames and a valid refIndex");
        return nullptr;
    }
    const size_t bytesPerFrame = static_cast<size_t>(W) * H * 2;

    // Per-Bayer-position black levels (order R, G-even, G-odd, B);
    // fall back to the scalar mean if the caller passed nothing.
    jint blScalar[4] = {0, 0, 0, 0};
    if (jBlackLevels != nullptr) {
        if (env->GetArrayLength(jBlackLevels) != kBayerChannels) {
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                          "blackLevels must have 4 entries");
            return nullptr;
        }
        env->GetIntArrayRegion(jBlackLevels, 0, kBayerChannels, blScalar);
    }
    // White balance gains (R, G, B) applied in finish; 1/1/1 = none.
    float wb[3] = {1.0f, 1.0f, 1.0f};
    if (jWbGains != nullptr && env->GetArrayLength(jWbGains) == 3) {
        jfloat* g = env->GetFloatArrayElements(jWbGains, nullptr);
        for (int i = 0; i < 3; i++) wb[i] = g[i];
        env->ReleaseFloatArrayElements(jWbGains, g, JNI_ABORT);
    }
    // Per-frame Wiener noise floor: tau scales with ISO (variance roughly
    // grows with gain). tauScale = 1 restores the Phase 2 constant model.
    const float tau = kTau * std::max(tauScale, 1.0f);

    // Load: little-endian bytes -> u16, black level subtracted at load.
    std::vector<std::vector<u16>> raws(static_cast<size_t>(n));
    // Channel-this-pixel-belongs-to lookup, for per-position black levels.
    int bl[4] = {static_cast<int>(blScalar[0]), static_cast<int>(blScalar[1]),
                 static_cast<int>(blScalar[2]), static_cast<int>(blScalar[3])};
    int par[4][2];
    channelParities(cfa, par);
    // Map parity -> {R, G-even, G-odd, B} black-level index.
    int blIndexOfParity[2][2];
    for (int ch = 0; ch < kBayerChannels; ch++) {
        blIndexOfParity[par[ch][0]][par[ch][1]] = ch;
    }
    for (jint i = 0; i < n; i++) {
        jbyteArray jbytes = static_cast<jbyteArray>(env->GetObjectArrayElement(frames, i));
        if (env->GetArrayLength(jbytes) < static_cast<jsize>(bytesPerFrame)) {
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                          "frame buffer too small");
            return nullptr;
        }
        jbyte* src = env->GetByteArrayElements(jbytes, nullptr);
        raws[i].resize(pixels);
        for (int y = 0; y < H; y++) {
            const size_t rowBase = static_cast<size_t>(y) * W;
            for (int x = 0; x < W; x++) {
                const size_t p = rowBase + x;
                const int lo = static_cast<unsigned char>(src[2 * p]);
                const int hi = static_cast<unsigned char>(src[2 * p + 1]);
                const int ch = blIndexOfParity[x & 1][y & 1];
                const int v = (lo | (hi << 8)) - bl[ch];
                raws[i][p] = static_cast<u16>(std::max(v, 0));
            }
        }
        env->ReleaseByteArrayElements(jbytes, src, JNI_ABORT);
        env->DeleteLocalRef(jbytes);
    }
    int minBl = bl[0];
    int maxBl = bl[0];
    for (int ch = 1; ch < kBayerChannels; ch++) {
        minBl = std::min(minBl, bl[ch]);
        maxBl = std::max(maxBl, bl[ch]);
    }
    const int clipRange = std::max(whiteLevel - minBl, 1);
    const int clipThresh = clipRange * 98 / 100;

    // Align every alt onto the reference, one alt per worker thread.
    const u16* refPtr = raws[refIndex].data();
    GrayPyramid refGray = buildGray(refPtr, W, H, clipThresh);
    const jint nAlt = n - 1;
    std::vector<std::vector<u16>> altRaws(static_cast<size_t>(nAlt));
    std::vector<AlignOffsets> offsets(static_cast<size_t>(nAlt));
    std::vector<u16*> altPtrs(static_cast<size_t>(nAlt));
    for (jint i = 0, k = 0; i < n; i++) {
        if (i == refIndex) continue;
        altRaws[k] = std::move(raws[i]);
        altPtrs[k] = altRaws[k].data();
        k++;
    }
    {
        const unsigned hw = std::thread::hardware_concurrency();
        const int workers = static_cast<int>(std::min<size_t>(
            std::max(hw, 1u), static_cast<size_t>(nAlt)));
        std::vector<std::thread> pool;
        for (int t = 0; t < workers; t++) {
            pool.emplace_back([&, t, nAlt]() {
                for (int a = t; a < nAlt; a += workers) {
                    GrayPyramid altGray = buildGray(altPtrs[a], W, H, clipThresh);
                    offsets[a] = align(refGray, altGray);
                }
            });
        }
        for (auto& thr : pool) thr.join();
    }

    // Merge (merged pre-filled from ref; fusion overwrites tile by tile),
    // striped over tile rows per worker.
    std::vector<u16> merged(raws[refIndex]); // copy of ref
    mergeThreaded(refPtr, altPtrs, offsets, W, H, clipThresh, tau, kGhostDiscard, merged.data());

    // Finish to sRGB pixels.
    jintArray out = env->NewIntArray(static_cast<jsize>(pixels));
    std::vector<jint> pixelsBuf(pixels);
    finish(merged.data(), W, H, cfa, clipRange, wb, pixelsBuf.data());
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(pixels), pixelsBuf.data());
    return out;
}

// ------------------------------------------------------------- YUV fallback
//
// Universal-device path: cameras without RAW_SENSOR (LEGACY/LIMITED HALs) can
// still shoot merged bursts. frames[i] is one packed [Y, U, V] buffer from
// FusionNative.yuvBytes (stride compaction done on the Java side). Luma runs
// the same pyramid align + Wiener merge as RAW — the Y plane is just a
// single-channel image, so buildGray/align/merge work unchanged. Chroma is
// taken from the reference frame. A single frame (n == 1) skips fusion and
// only converts: the plain-photo path on RAW-less devices.
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_fusioncam_scanner_fusion_FusionNative_mergeBurstYuv(
        JNIEnv* env, jclass /*klass*/, jobjectArray frames, jint refIndex,
        jint width, jint height, jfloat tauScale) {

    const int W = width, H = height;
    const size_t pixels = static_cast<size_t>(W) * H;
    const int cw = W / 2, chh = H / 2;
    const jint n = env->GetArrayLength(frames);
    if (n < 1 || refIndex < 0 || refIndex >= n) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                      "need >=1 frame and a valid refIndex");
        return nullptr;
    }
    const size_t yBytes = pixels;
    const size_t cBytes = static_cast<size_t>(cw) * chh;
    const size_t bytesPerFrame = yBytes + 2 * cBytes;

    std::vector<std::vector<u16>> luma(static_cast<size_t>(n));
    std::vector<u8> refU, refV;
    for (jint i = 0; i < n; i++) {
        jbyteArray jbytes = static_cast<jbyteArray>(env->GetObjectArrayElement(frames, i));
        if (env->GetArrayLength(jbytes) < static_cast<jsize>(bytesPerFrame)) {
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                          "frame buffer too small");
            return nullptr;
        }
        jbyte* src = env->GetByteArrayElements(jbytes, nullptr);
        luma[i].resize(pixels);
        for (size_t p = 0; p < pixels; p++) luma[i][p] = static_cast<u8>(src[p]);
        if (i == refIndex) {
            refU.assign(src + yBytes, src + yBytes + cBytes);
            refV.assign(src + yBytes + cBytes, src + yBytes + 2 * cBytes);
        }
        env->ReleaseByteArrayElements(jbytes, src, JNI_ABORT);
        env->DeleteLocalRef(jbytes);
    }

    std::vector<u16> merged(luma[refIndex]); // pre-filled from ref
    const jint nAlt = n - 1;
    if (nAlt > 0) {
        const u16* refPtr = luma[refIndex].data();
        GrayPyramid refGray = buildGray(refPtr, W, H, kClipYuv);
        std::vector<std::vector<u16>> altRaws(static_cast<size_t>(nAlt));
        std::vector<AlignOffsets> offsets(static_cast<size_t>(nAlt));
        std::vector<u16*> altPtrs(static_cast<size_t>(nAlt));
        for (jint i = 0, k = 0; i < n; i++) {
            if (i == refIndex) continue;
            altRaws[k] = std::move(luma[i]);
            altPtrs[k] = altRaws[k].data();
            k++;
        }
        const unsigned hw = std::thread::hardware_concurrency();
        const int workers = static_cast<int>(std::min<size_t>(
            std::max(hw, 1u), static_cast<size_t>(nAlt)));
        std::vector<std::thread> pool;
        for (int t = 0; t < workers; t++) {
            pool.emplace_back([&, t, nAlt]() {
                for (int a = t; a < nAlt; a += workers) {
                    GrayPyramid altGray = buildGray(altPtrs[a], W, H, kClipYuv);
                    offsets[a] = align(refGray, altGray);
                }
            });
        }
        for (auto& thr : pool) thr.join();

        // τ rides with ISO exactly like the RAW path; ghost discard rides with τ.
        const float tau = kTauYuv * std::max(tauScale, 1.0f);
        mergeThreaded(refPtr, altPtrs, offsets, W, H, kClipYuv, tau,
                      kGhostTauMultiple * tau, merged.data());
    }

    jintArray out = env->NewIntArray(static_cast<jsize>(pixels));
    std::vector<jint> pixelsBuf(pixels);
    yuvToRgb(merged.data(), refU.data(), refV.data(), W, H, pixelsBuf.data());
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(pixels), pixelsBuf.data());
    return out;
}
