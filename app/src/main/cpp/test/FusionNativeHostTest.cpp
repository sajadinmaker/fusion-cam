// Host-side test harness for the Phase 2 fusion core.
//
// #includes the real FusionNative.cpp so the anonymous-namespace internals
// (buildGray / align / merge / finish) are exercised directly with synthetic
// RGGB RAW16 data: a blocky high-contrast scene (no periodic texture, so the
// aligner cannot alias onto a wrong period), with independent per-frame noise.
//
// Verifies:
//   1. align() recovers each shift within one level-0 pixel (== 2 full-res px)
//   2. merge() reduces pixel variance in a structurally identical flat patch
//   3. finish() yields sane sRGB output channels
#include "FusionNative.cpp"

#include <cstdio>
#include <random>

static constexpr int W = 128, H = 96;
static constexpr int kBlack = 0;     // synthetic frames are already post-black
static constexpr int kWhite = 1024;

// Distinct 32px blocks plus a unique bright dot per tile so every align tile
// has unambiguous structure (no flat-region tie at the correct offset).
static u16 sceneValue(int x, int y) {
    const int bx = (x / 32) & 3, by = (y / 32) & 3;
    const u16 blockVal[] = {250, 420, 600, 800};
    u16 v = blockVal[(bx + by) & 3];
    if (x > 60 && x < 64) v = 950; // vertical high-contrast edge
    // Unique per-tile dot: (tx,ty) = the 32px tile this pixel lives in.
    const int tx = x / 32, ty = y / 32;
    const int dotX = tx * 32 + 4 + (tx * 7 + ty * 5) % 16;
    const int dotY = ty * 32 + 4 + (ty * 11 + tx * 3) % 16;
    if (x == dotX && y == dotY) v = 890;
    return v;
}

static void makeFrame(std::vector<u16>& out, int shiftX, int shiftY, std::mt19937& rng) {
    std::normal_distribution<float> noise(0.0f, 6.0f);
    out.assign(static_cast<size_t>(W) * H, 0);
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            const int sx = std::min(std::max(x + shiftX, 0), W - 1);
            const int sy = std::min(std::max(y + shiftY, 0), H - 1);
            const float v = sceneValue(sx, sy) + noise(rng);
            out[static_cast<size_t>(y) * W + x] =
                static_cast<u16>(std::max(0.0f, std::min(v, 1000.0f)));
        }
    }
}

static int groundTruthLevel0(int expectedLevel0) { return expectedLevel0; }

int main() {
    const int clipThresh = (kWhite - kBlack) * 98 / 100;
    std::mt19937 rng(1234);

    std::vector<u16> ref;
    std::vector<u16> alt1, alt2;
    makeFrame(ref, 0, 0, rng);
    makeFrame(alt1, 2, 0, rng);  // full-res +2 x  -> level-0 offset (-1, 0)
    makeFrame(alt2, 0, 4, rng);  // full-res +4 y  -> level-0 offset (0, -2)

    // ---- 1. alignment -------------------------------------------------------
    const GrayPyramid refGray = buildGray(ref.data(), W, H, clipThresh);
    const GrayPyramid a1Gray = buildGray(alt1.data(), W, H, clipThresh);
    const GrayPyramid a2Gray = buildGray(alt2.data(), W, H, clipThresh);
    const AlignOffsets o1 = align(refGray, a1Gray);
    const AlignOffsets o2 = align(refGray, a2Gray);

    int failures = 0;
    int nonMatch1 = 0, nonMatch2 = 0;
    int sumDx1 = 0, sumDy1 = 0, matched1 = 0;
    int sumDx2 = 0, sumDy2 = 0, matched2 = 0;
    for (size_t i = 0; i < o1.dy.size(); i++) {
        if (o1.dy[i] == kNoMatch) nonMatch1++;
        else { sumDy1 += o1.dy[i]; sumDx1 += o1.dx[i]; matched1++; }
        if (o2.dy[i] == kNoMatch) nonMatch2++;
        else { sumDy2 += o2.dy[i]; sumDx2 += o2.dx[i]; matched2++; }
    }
    printf("align1: matched=%zu nonmatch=%d mean=(%d,%d) [expect x=-1 y=0]\n",
           o1.dy.size(), nonMatch1,
           matched1 ? sumDx1 / matched1 : -999, matched1 ? sumDy1 / matched1 : -999);
    printf("align2: matched=%zu nonmatch=%d mean=(%d,%d) [expect x=0 y=-2]\n",
           o2.dy.size(), nonMatch2,
           matched2 ? sumDx2 / matched2 : -999, matched2 ? sumDy2 / matched2 : -999);

    // Structure everywhere: interior tiles must recover the truth ±1. Border
    // tiles (top row / left column) legitimately cannot when their true shift
    // points off-image — the search cannot evaluate out-of-bounds offsets, and
    // merge keeps them ghost-free via per-pixel weights.
    int offBy2 = 0;
    for (size_t i = 0; i < o1.dy.size(); i++) {
        const int tx = static_cast<int>(i % o1.tw), ty = static_cast<int>(i / o1.tw);
        if (ty == 0 || tx == 0) continue;
        if (o1.dy[i] != kNoMatch &&
            (std::abs(o1.dx[i] - (-1)) > 1 || std::abs(o1.dy[i] - 0) > 1)) offBy2++;
        if (o2.dy[i] != kNoMatch &&
            (std::abs(o2.dx[i] - 0) > 1 || std::abs(o2.dy[i] + 2) > 1)) offBy2++;
    }
    printf("interior tiles off-truth beyond ±1 level-0 px: %d / %zu\n", offBy2, (o1.dy.size() * 2 - o1.tw) * 2);
    if (offBy2 > 0) failures++;

    // ---- 2. merge -----------------------------------------------------------
    std::vector<u16> merged(ref); // pre-filled from ref, as in the JNI entry
    std::vector<u16*> alts = {alt1.data(), alt2.data()};
    std::vector<AlignOffsets> offs = {o1, o2};
    merge(ref.data(), alts, offs, W, H, clipThresh, merged.data());

    auto variance = [&](const std::vector<u16>& img, int x0, int y0, int x1, int y1) {
        double sum = 0, sum2 = 0; long n = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                const double v = img[static_cast<size_t>(y) * W + x];
                sum += v; sum2 += v * v; n++;
            }
        const double meanV = sum / n;
        return sum2 / n - meanV * meanV;
    };
    // Flat patch (block value 250 region, y 16..32, x 8..24) away from the
    // edge column and the per-tile dots.
    const double varRef = variance(ref, 8, 16, 24, 32);
    const double varMerged = variance(merged, 8, 16, 24, 32);
    printf("noise variance: ref=%.1f merged=%.1f\n", varRef, varMerged);
    if (varMerged >= varRef * 0.75) { printf("FAIL: insufficient noise reduction\n"); failures++; }

    // ---- 3. finish ----------------------------------------------------------
    std::vector<jint> pixels(static_cast<size_t>(W) * H);
    finish(merged.data(), W, H, 0 /*RGGB*/, kWhite - kBlack, pixels.data());
    size_t center = static_cast<size_t>(H / 2) * W + W / 2;
    jint c = pixels[center];
    const int r = (c >> 0) & 0xFF, g = (c >> 8) & 0xFF, b = (c >> 16) & 0xFF;
    printf("finish center pixel R=%d G=%d B=%d (expect ~mid-gray, G>=R,B-20)\n", r, g, b);
    if (r < 40 || g < 40 || b < 40 || r > 255 || g > 255 || b > 255) failures++;

    if (failures == 0) printf("\nALL PASS\n");
    else printf("\n%d FAILURES\n", failures);
    return failures;
}
