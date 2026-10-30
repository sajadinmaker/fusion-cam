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
#include <vector>

namespace {

using u16 = uint16_t;

constexpr int kTile = 16;           // tile size, level-pixel units
constexpr int kSearch = 4;          // search radius per pyramid level
constexpr int kLevels = 4;          // level0 = half-res gray
constexpr float kTau = 256.0f;      // noise floor for the Wiener weight
constexpr float kGhostDiscard = 8192.0f;
constexpr u16 kInvalid = 0xFFFF;    // clipped sentinel in gray pyramids
constexpr int16_t kNoMatch = INT16_MAX;

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
                // NOTE: border tiles whose true shift points off-image cannot
                // evaluate their true offset here (clamped search would be the
                // paper's exact treatment). Merge stays ghost-free for them via
                // per-pixel weights. TODO Phase 3: clamped sub-pixel search.
                for (int sy = ody - kSearch; sy <= ody + kSearch; sy++) {
                    for (int sx = odx - kSearch; sx <= odx + kSearch; sx++) {
                        const int ax = ox + sx, ay = oy + sy;
                        if (ax < 0 || ay < 0 || ax + kTile > lw || ay + kTile > lh) continue;
                        double cost = 0.0; int valid = 0;
                        for (int cy = 0; cy < kTile; cy++) {
                            const size_t rbase = static_cast<size_t>(oy + cy) * lw;
                            for (int cx = 0; cx < kTile; cx++) {
                                const u16 rv = rl[rbase + ox + cx];
                                if (rv == kInvalid) continue;
                                const u16 av = sampleClamped(al.data(), lw, lh, ax + cx, ay + cy);
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
void merge(const u16* ref, const std::vector<u16*>& alts,
           const std::vector<AlignOffsets>& offsets, int W, int H,
           int clipThresh, u16* merged) {
    const int tw = offsets.empty() ? 0 : offsets[0].tw;
    const int th = offsets.empty() ? 0 : offsets[0].th;
    for (int ty = 0; ty < th; ty++) {
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
                    const size_t rbase = static_cast<size_t>(oy + cy) * W;
                    const size_t abase = static_cast<size_t>(oyA + cy) * W;
                    for (int cx = 0; cx < kTile * 2; cx++) {
                        const u16 rv = ref[rbase + ox + cx];
                        if (rv >= clipThresh) continue;
                        const u16 av = alts[a][abase + oxA + cx];
                        if (av >= clipThresh) continue;
                        const int d = static_cast<int>(rv) - static_cast<int>(av);
                        diff += static_cast<double>(d) * d;
                        valid++;
                    }
                }
                if (valid == 0) { discard[a] = true; continue; }
                discard[a] = static_cast<float>(diff / valid) > kGhostDiscard;
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
                        const u16 av = alts[a][static_cast<size_t>(oy + cy + pdy * 2) * W +
                                               ox + cx + pdx * 2];
                        if (av >= clipThresh) continue;
                        const int d = static_cast<int>(rv) - static_cast<int>(av);
                        const float w = kTau / (kTau + static_cast<float>(d) * d);
                        num += w * av;
                        den += w;
                    }
                    merged[ridx] = static_cast<u16>(std::min(num / den, static_cast<double>(clipThresh - 1)));
                }
            }
        }
    }
}

// Finish: parity-aware bilinear demosaic + gamma, one pass, no RGB planes.
// G is native at every green position; R/B fill from parity-derived neighbors.
void finish(const u16* merged, int W, int H, int cfa, int clipRange,
            jint* outPixels) {
    int par[4][2];
    channelParities(cfa, par);
    const float norm = 1.0f / (clipRange > 0 ? clipRange : 1);
    std::vector<jint> gammaLut(65536);
    for (int v = 0; v < 65536; v++) {
        const float x = std::min(v * norm, 1.0f);
        gammaLut[v] = static_cast<jint>(std::pow(x, 1.0f / 2.2f) * 255.0f + 0.5f);
    }
    auto gamma8 = [&gammaLut](u16 v) -> jint { return gammaLut[v]; };

    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            const u16 v = merged[static_cast<size_t>(y) * W + x];
            const int px = x & 1, py = y & 1;
            // Identify which channel is native here.
            int nativeCh;
            if (px == par[0][0] && py == par[0][1]) nativeCh = 0;
            else if (px == par[3][0] && py == par[3][1]) nativeCh = 3;
            else nativeCh = 1; // green (both green parities)

            jint rr, gg, bb;
            const jint g8 = gamma8(v);
            if (nativeCh == 1) {
                gg = g8;
                jint fillSum[2] = {0, 0}; // index 0 -> R, 1 -> B
                int fillCount[2] = {0, 0};
                for (int chIdx = 0; chIdx < 2; chIdx++) {
                    const int ch = (chIdx == 0) ? 0 : 3;
                    const int chx = static_cast<int>(par[ch][0]), chy = static_cast<int>(par[ch][1]);
                    // Parity relation decides neighbor offsets.
                    const bool differX = (chx != px), differY = (chy != py);
                    if (differX && differY) {
                        fillSum[chIdx] += gamma8(sampleClamped(merged, W, H, x - 1, y - 1)) +
                                          gamma8(sampleClamped(merged, W, H, x + 1, y - 1)) +
                                          gamma8(sampleClamped(merged, W, H, x - 1, y + 1)) +
                                          gamma8(sampleClamped(merged, W, H, x + 1, y + 1));
                        fillCount[chIdx] = 4;
                    } else if (differX) {
                        fillSum[chIdx] += gamma8(sampleClamped(merged, W, H, x - 1, y)) +
                                          gamma8(sampleClamped(merged, W, H, x + 1, y));
                        fillCount[chIdx] = 2;
                    } else if (differY) {
                        fillSum[chIdx] += gamma8(sampleClamped(merged, W, H, x, y - 1)) +
                                          gamma8(sampleClamped(merged, W, H, x, y + 1));
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
                gg = (gamma8(sampleClamped(merged, W, H, x - 1, y)) +
                      gamma8(sampleClamped(merged, W, H, x + 1, y)) +
                      gamma8(sampleClamped(merged, W, H, x, y - 1)) +
                      gamma8(sampleClamped(merged, W, H, x, y + 1))) / 4;
                const int chx = static_cast<int>(par[3][0]), chy = static_cast<int>(par[3][1]);
                const bool dx = (chx != px), dy2 = (chy != py);
                bb = (dx && dy2)
                    ? (gamma8(sampleClamped(merged, W, H, x - 1, y - 1)) +
                       gamma8(sampleClamped(merged, W, H, x + 1, y - 1)) +
                       gamma8(sampleClamped(merged, W, H, x - 1, y + 1)) +
                       gamma8(sampleClamped(merged, W, H, x + 1, y + 1))) / 4
                    : (dx ? (gamma8(sampleClamped(merged, W, H, x - 1, y)) +
                             gamma8(sampleClamped(merged, W, H, x + 1, y))) / 2
                          : (gamma8(sampleClamped(merged, W, H, x, y - 1)) +
                             gamma8(sampleClamped(merged, W, H, x, y + 1))) / 2);
            } else {
                bb = g8;
                gg = (gamma8(sampleClamped(merged, W, H, x - 1, y)) +
                      gamma8(sampleClamped(merged, W, H, x + 1, y)) +
                      gamma8(sampleClamped(merged, W, H, x, y - 1)) +
                      gamma8(sampleClamped(merged, W, H, x, y + 1))) / 4;
                const int chx = static_cast<int>(par[0][0]), chy = static_cast<int>(par[0][1]);
                const bool dx = (chx != px), dy2 = (chy != py);
                rr = (dx && dy2)
                    ? (gamma8(sampleClamped(merged, W, H, x - 1, y - 1)) +
                       gamma8(sampleClamped(merged, W, H, x + 1, y - 1)) +
                       gamma8(sampleClamped(merged, W, H, x - 1, y + 1)) +
                       gamma8(sampleClamped(merged, W, H, x + 1, y + 1))) / 4
                    : (dx ? (gamma8(sampleClamped(merged, W, H, x - 1, y)) +
                             gamma8(sampleClamped(merged, W, H, x + 1, y))) / 2
                          : (gamma8(sampleClamped(merged, W, H, x, y - 1)) +
                             gamma8(sampleClamped(merged, W, H, x, y + 1))) / 2);
            }
            outPixels[static_cast<size_t>(y) * W + x] =
                (0xFF << 24) | (bb << 16) | (gg << 8) | rr;
        }
    }
}

} // namespace

// ---------------------------------------------------------------- JNI entry

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_fusioncam_scanner_fusion_FusionNative_mergeBurst(
        JNIEnv* env, jclass /*klass*/, jobjectArray frames, jint refIndex,
        jint width, jint height, jint cfa, jint blackLevel, jint whiteLevel) {

    const int W = width, H = height;
    const size_t pixels = static_cast<size_t>(W) * H;
    const jint n = env->GetArrayLength(frames);
    if (n < 2 || refIndex < 0 || refIndex >= n) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "need >=2 frames and a valid refIndex");
        return nullptr;
    }
    const size_t bytesPerFrame =
        static_cast<size_t>(W) * H * 2;

    // Load: little-endian bytes -> u16, black level subtracted at load.
    std::vector<std::vector<u16>> raws(static_cast<size_t>(n));
    for (jint i = 0; i < n; i++) {
        jbyteArray jbytes = static_cast<jbyteArray>(env->GetObjectArrayElement(frames, i));
        if (env->GetArrayLength(jbytes) < static_cast<jsize>(bytesPerFrame)) {
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                          "frame buffer too small");
            return nullptr;
        }
        jbyte* src = env->GetByteArrayElements(jbytes, nullptr);
        raws[i].resize(pixels);
        for (size_t p = 0; p < pixels; p++) {
            const int lo = static_cast<unsigned char>(src[2 * p]);
            const int hi = static_cast<unsigned char>(src[2 * p + 1]);
            int v = (lo | (hi << 8)) - blackLevel;
            raws[i][p] = static_cast<u16>(std::max(v, 0));
        }
        env->ReleaseByteArrayElements(jbytes, src, JNI_ABORT);
        env->DeleteLocalRef(jbytes);
    }
    const int clipRange = std::max(whiteLevel - blackLevel, 1);
    const int clipThresh = clipRange * 98 / 100;

    // Align every alt onto the reference (ref keep aside).
    const u16* refPtr = raws[refIndex].data();
    GrayPyramid refGray = buildGray(refPtr, W, H, clipThresh);
    std::vector<std::vector<u16>> altRaws(static_cast<size_t>(n - 1));
    std::vector<AlignOffsets> offsets(static_cast<size_t>(n - 1));
    std::vector<u16*> altPtrs(static_cast<size_t>(n - 1));
    size_t k = 0;
    for (jint i = 0; i < n; i++) {
        if (i == refIndex) continue;
        altRaws[k] = std::move(raws[i]);
        altPtrs[k] = altRaws[k].data();
        GrayPyramid altGray = buildGray(altPtrs[k], W, H, clipThresh);
        offsets[k] = align(refGray, altGray);
        k++;
    }

    // Merge (merged pre-filled from ref; fusion overwrites tile by tile).
    std::vector<u16> merged(raws[refIndex]); // copy of ref
    merge(refPtr, altPtrs, offsets, W, H, clipThresh, merged.data());

    // Finish to sRGB pixels.
    jintArray out = env->NewIntArray(static_cast<jsize>(pixels));
    std::vector<jint> pixelsBuf(pixels);
    finish(merged.data(), W, H, cfa, clipRange, pixelsBuf.data());
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(pixels), pixelsBuf.data());
    return out;
}
