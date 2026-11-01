# Fusion Cam

Computational-photography camera app for Android — the "iPhone pipeline" ported:
ZSL burst capture of RAW frames → align-and-merge fusion → scene-aware (semantic)
processing → Apple-style tone mapping and color science. See the research doc at
`~/iphone-camera-to-android-research.md` for the full analysis this app implements.

## Phase 0 (this module) — Device capability scanner

`app` deploys as `dev.fusioncam.scanner`. Tap **Scan this device** and it reports,
per camera:

- Hardware level (LEVEL_3 / FULL / LIMITED / LEGACY), the hard prerequisite for
  Camera2 ZSL RAW burst control
- RAW_SENSOR support, largest RAW size, max RAW burst fps (from min frame duration)
- MANUAL_SENSOR / BURST_CAPTURE capabilities, per-request exposure scripting
- OIS, max analog ISO, sensor geometry (active/pixel array MP, mm², CFA)
- Device RAM → per-photo burst memory budget, thermal status, SoC

Each camera gets a score (0–100) and a tier:

| Tier | Meaning |
|------|---------|
| A (≥85) | Full HDR+-style pipeline target |
| B (≥70) | Pipeline supported, needs per-device tuning |
| C (≥50) | Degraded pipeline (fewer frames / sYCC-only merge) |
| D (<50) | Not supported (no RAW / LEGACY HAL) |

A full JSON report lands in
`Android/data/dev.fusioncam.scanner/files/reports/capabilities-<ts>.json`
for the tuning database.

## On-device smoke test ✅ (Android 16 emulator, torch-era LEVEL_3 camera)

First end-to-end device run of Phases 0–3, driven over adb:
- **Phase 0**: scan completed on-device; camera #1 (Back) scored **tier B/73**:
  HW LEVEL_3, RAW_SENSOR 1856×1392 @30 fps, RGGB, max analog ISO 1600, no OIS.
- **Session resilience found + fixed**: the emulator kills the combined
  preview+RAW session synchronously ("Surface was abandoned") when a layout
  pass replaces the SurfaceView's native surface between `surfaceCreated`
  and session creation. Previously this path never reached the RAW-only
  retry, leaving the engine permanently sessionless. Fixes: synchronous
  session-creation failure now also falls back to RAW-only
  (`CaptureEngine.configureSession`), and `surfaceChanged` restarts the
  engine with the fresh surface. Verified: engine recovers into RAW-only
  streaming automatically.
- **Phase 2/3 merge on-device**: latch 4 ZSL frames (ISO 100, 4.36 ms) →
  per-channel black levels [64,64,64,64], white 4000, RGGB → full
  align+merge+demosaic+WB path → valid 1856×1392 `merged.jpg` in **722 ms**
  (2.6 MP ×4 frames, single pool). Emulator image is the synthetic test
  pattern; render is artifact-free (no ghosting/dislocation).
- App now drives the camera screen directly under debug builds to avoid
  fighting UI scroll (`app/src/debug/AndroidManifest.xml` exports
  `CaptureActivity` for debug only).

## Phase 3.5 — border alignment + noise-scaled merge ✅ (host-tested)

- **Border-tile alignment fixed**: the search previously discarded any
  candidate tile extending past the frame edge, so border tiles whose true
  shift pointed off-image could not lock. Candidates are now evaluated on
  their overlap with the frame, with out-of-bounds alt samples *skipped,
  never clamped* — duplicated edge content can't fake a match. The host test
  now recovers alignment truth on **all 24/24 tiles**, borders included
  (was 20/24); merged variance improved to 29.3 → 11.0 as a side effect.
- **Merge honors frame bounds**: alt reads outside [0,H)×[0,W) are skipped in
  both the tile-diff pass and the pixel merge (no OOB reads, no edge-dup
  ghosting).
- **Per-frame noise floor**: the Wiener τ now scales with the reference
  frame's ISO (`SENSOR_SENSITIVITY`, floored at ISO 100) instead of being a
  constant. Verified at σ=24 (ISO-1600-like): the 3-frame merge reaches
  ref/2.95 variance vs the theoretical 3-frame floor of ref/3 — the scaled
  τ feeds through instead of freezing up.
- Documented (not fixed, by design): near-flat block tiles at extreme noise
  can lose a cost tie on their single dot-sized signal; a ±3 px miss on a
  flat region is dot-sized and harmless — per-pixel Wiener weights absorb it.
  Host test asserts ≥ 19/24 tiles lock at σ=24.

## Phase 3 (CPU part) — color fidelity + multithreading ✅ (host-tested)

- **Per-Bayer-position black levels**: `SENSOR_BLACK_LEVEL_PATTERN` (R, G-even,
  G-odd, B) subtracted per pixel during load, instead of the scalar mean.
  Clip range computed from min(black levels).
- **White-balance gains**: R/G/B floats from the reference frame's
  `CaptureResult.COLOR_CORRECTION_GAINS` (`RggbChannelVector` → mean of the two
  greens), applied pre-gamma in `finish()` via per-channel gamma LUTs — one
  LUT build, no per-pixel float math. Neutral 1/1/1 still accepted.
- **Multithreaded**: align runs one alt frame per worker (`std::thread` pool,
  hardware_concurrency-capped); merge stripes tile rows across workers
  (disjoint writes, no locks). finish remains per-pixel sequential with LUT —
  the cheap stage.
- Host test extended: threaded merge keeps the 29.3 → 12.2 variance cut and
  interior-tile alignment truth; new assertions prove WB gains land (R×2/B×0.5
  warm render: R 228→255, B 237→173, G bit-identical).

## Phase 2 — HDR+ burst merge core ✅ (host-tested)

`src/main/cpp/FusionNative.cpp` + `fusion/FusionNative.kt` implement the
Hasinoff et al. HDR+ algorithm (Phase 2 scope), compiled through CMake for
both ABIs:

1. **Load** — RAW16 Bayer planes → u16, black level subtracted, per-frame
2. **Grayscale pyramid** — 2×2 Bayer block mean per frame, clipped pixels
   excluded (sentinel), 4 coarse-to-fine levels
3. **Align** — 16px-tile pyramid alignment per alt frame, L2 on valid gray
   pixels, ±4 search per level, low-validity tiles → `kNoMatch`
4. **Merge** — full-res 32px tiles (offsets ×2 stay even → Bayer parity safe),
   **per-pixel Wiener weight** `τ/(τ+d²)` (structural outliers ≈0, flat pixels
   merge hard), tile-level ghost discard above `d̄² > 8192`
5. **Finish** — parity-aware bilinear demosaic (no RGB planes needed), gamma
   LUT → `0xAARRGGBB` pixels → Bitmap → JPEG + on-screen preview

`Activity` has a **"Phase 2: HDR+ merge burst"** button: latch → extract each
frame's plane → `FusionNative.mergeBurst` → thumbnail + `merged.jpg`.

**Verified on host** (`cpp/test/run_host_tests.sh`, AddressSanitizer+UBSan):
- alignment recovers ±1 level-0-px truth on all interior tiles; border tiles
  correctly cannot exceed image bounds (documented, ghost-free in merge)
- 3-frame merge cuts flat-region noise variance 29.3 → 12.2 (≥2.4× better than
  the naive per-tile weight version, which also ghosted — SSR found by this test)
- demosaic output channels sane

Phase 2 simplifications kept as TODOs: constant noise floor τ (paper: per-frame
noise model), even-only offsets, single thread, per-channel black levels.

## Phase 1 — capture skeleton ✅

`ui.CaptureActivity` + `capture.CaptureEngine` prove the ZSL capture loop:

- Repeating (preview + RAW_SENSOR) session; the `ImageReader` **is** the rolling
  buffer — frames queue up without copies and drain in one shot on latch
- Queue depth adapts to sensor size (50MP sensors → depth 2, ≤12MP → 4)
- Latch flow: `stopRepeating()` → settle 250 ms → drain all buffered frames —
  the "photo taken just before the tap" rank-by-sharpness trick
- Sharpness proxy: variance of Bayer-**green** Laplacian (±2 pixel steps preserve
  Bayer parity so neighbors are always same-channel)
- Every drained frame pairs with its `TotalCaptureResult` by `SENSOR_TIMESTAMP`;
  metadata dumped to `frames.json`: exposure/ISO, AE/AF state, noise profile,
  dynamic black level, neutral color point, 3×3 color transform, focus distance
- Sharpest frame saved as `best.dng` via `DngCreator` (characteristics + result),
  the same shape an eventual ProRAW-style linear export needs

Known skeleton limitations (Phase 2 targets): RAW fps is measured but not
scripted (no per-frame exposure bracketing yet); no semantic per-region
processing; RAW-only preview fallback is untested on LEGACY devices.

## Building

```
./gradlew assembleDebug
```

Install on a phone: `adb install app/build/outputs/apk/debug/app-debug.apk`.
No AndroidX/Compose dependencies on purpose — keep the scanner lean until
Phase 1 introduces CameraX.

## Scoring weights (add to 100)

- HW level: 30 (LEVEL_3) / 25 (FULL) / 12 (LIMITED)
- RAW output stream: 20
- BURST_CAPTURE: 8, MANUAL_SENSOR: 5
- RAW burst bandwidth: 10 (≥15 fps) / 7 (≥5 fps) / 3
- OIS: 6 · sensor area: 10 (≥40mm²) / 8 (≥30) / 5 (≥20) · 48MP+ pixel array: 4
- RAM: 5 (≥8GB) / 4 (≥6GB) / 2 (≥4GB)
- Hard gate: no per-request `SENSOR_SENSITIVITY`/`SENSOR_EXPOSURE_TIME` → cap at 20

## Roadmap

1. Phase 0 — capability matrix + device scoring ✅
2. Phase 1 — capture skeleton: ZSL preview + rolling RAW buffer, sharpest-frame
   latch → `best.dng` + `frames.json` ✅ (needs on-device smoke test)
3. Phase 2 — HDR+ core: align-and-merge in C++/NDK over RAW bursts ✅ (host-
   tested via cpp/test/run_host_tests.sh; needs on-device smoke test)
3. Phase 2 — HDR+ core: align-and-merge in C++/Halide over RAW bursts
4. Phase 3 — GPU (Vulkan compute/Halide GPU target), < 1.5 s/photo
5. Phase 4 — night mode (motion-masked long-merge)
6. Phase 5 — TFLite semantics: portrait/sky/hair masks → selective NR & sharpening
7. Phase 6 — color science harness: A/B against iPhone on identical scenes
8. Phase 7 — ProRAW-style linear DNG export, Ultra HDR, launch
