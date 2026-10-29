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
3. Phase 2 — HDR+ core: align-and-merge in C++/Halide over RAW bursts
4. Phase 3 — GPU (Vulkan compute/Halide GPU target), < 1.5 s/photo
5. Phase 4 — night mode (motion-masked long-merge)
6. Phase 5 — TFLite semantics: portrait/sky/hair masks → selective NR & sharpening
7. Phase 6 — color science harness: A/B against iPhone on identical scenes
8. Phase 7 — ProRAW-style linear DNG export, Ultra HDR, launch
