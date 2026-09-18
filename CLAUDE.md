# CLAUDE.md — Mihon fork with Bubble Zoom

## What this repository is

A fork of Mihon (Android manga reader, Kotlin) that adds a **Bubble Zoom** feature:
the reader detects dialogue regions on a page and lets the user step through them one
by one with a smooth animated zoom, similar to Google Play Books' Bubble Zoom.

Read `docs/BUBBLE_ZOOM_SPEC.md` before doing anything. It is the frozen scope.
Read `docs/BUBBLE_ZOOM_STATUS.md` to learn what is already done.

## Non-negotiable rules

1. **One phase per session.** Implement only the phase the user names. Never
   implement a later phase "while you're in there", even if it looks trivial.
2. **Bubble Zoom OFF must be byte-for-byte vanilla Mihon behavior.** Any regression
   in normal reading is a critical bug, not a trade-off.
3. **The page renders before detection finishes.** Detection is an enhancement that
   arrives late. Never block page display, never block the main thread.
4. **All Bubble Zoom code lives in
   `app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubblezoom/`.**
   Target: 90%+ of the feature outside upstream Mihon files. If you find yourself
   adding more than ~15 lines to an upstream class, stop and move the logic into the
   `bubblezoom` package behind a single call.
5. **Persisted and in-memory bubble coordinates are normalized floats, 0.0–1.0**,
   relative to the original image. Pixels only exist at the moment of drawing.
6. **The reader never references a specific ML runtime.** It talks to the
   `BubbleDetector` interface. No ONNX / LiteRT / TFLite / YOLO type ever crosses into
   reader code.
7. **No OCR, no translation, no TTS, no webtoon, no segmentation** in the MVP.

## Environment (Windows + emulator)

| Thing | Value |
|---|---|
| OS | Windows, Claude Code native, Bash tool backed by Git Bash |
| Repo | `D:/Pipo/OneDrive/Documentos/Apps/Mihon Bubble Zoom/mihon` |
| Android SDK | `D:/Android/Sdk` (`ANDROID_HOME`) |
| JDK | `D:/Java/jdk-21.0.12.1+1` (`JAVA_HOME`) — Temurin **21**, per `.github/.java-version` |
| Build output | `D:/mihon-build` — redirected out of OneDrive by `~/.gradle/init.d/mihon-build-dir.init.gradle.kts` |
| Emulator AVD | `mihon_test` |
| Screenshots | `tools/shots/` (gitignored) |
| Test manga | pushed to the emulator's Mihon local source, see `tools/push-test-manga.ps1` |

**Never start the emulator yourself.** Background processes you launch are killed when the
session ends or the context compacts. The emulator runs in a separate terminal the user
opens with `tools/start-emulator.ps1`. Your job is only to check it:

```bash
adb devices          # expect "emulator-5554   device"
```

If no device is attached, stop and tell the user to run `tools/start-emulator.ps1` in a
second terminal. Do not try to work around it.

Prefer `./gradlew :app:compileDebugKotlin` for a fast check. Only run a full
`assembleDebug` when you are about to install and test on the emulator.

There is **no `standard` product flavor** in current Mihon — `app/src` has only `debug`,
`main` and `test`. Telemetry and the updater are opt-in Gradle properties
(`-Pinclude-telemetry`, `-Penable-updater`), off by default, so a plain debug build needs
neither `google-services.json` nor `keystore.properties`.

## How you verify your own work

Every phase that changes visible behavior ends with you driving the emulator and
**looking at the result**, not with you asserting that it should work.

```bash
./gradlew :app:assembleDebug
# the build splits by ABI; x86_64 is the emulator, arm64-v8a a real phone
adb install -r D:/mihon-build/app/outputs/apk/debug/app-x86_64-debug.apk
adb shell am force-stop app.mihon.dev
adb shell am start -n app.mihon.dev/eu.kanade.tachiyomi.ui.main.MainActivity

# drive the UI
adb shell input tap <x> <y>
adb shell input swipe <x1> <y1> <x2> <y2> 200
adb shell input keyevent KEYCODE_VOLUME_DOWN
adb shell settings put system user_rotation 1   # 0=portrait, 1=landscape

# capture and LOOK
adb exec-out screencap -p > tools/shots/<phase>-<step>.png
```

Then **Read the PNG file** and describe what you actually see. Compare it against what the
phase said should happen. If the screenshot does not show what you expected, the phase is
not done — say so plainly instead of reporting success.

Rules for this loop:

- Name screenshots `<phase>-<step>.png` so a sequence can be compared.
- Use the synthetic test page with known bubble coordinates for anything involving
  coordinate accuracy: you know exactly where the drawn bubbles are, so misalignment is
  measurable, not a matter of opinion.
- After a crash, always pull the reason: `adb logcat -d -b crash | tail -50`.
- For timing claims, use logcat timestamps, never a guess.
- Screenshots over ~5 MB may fail to read — downscale with `adb exec-out screencap -p |
  magick - -resize 50% file.png` if needed.
- Show the user the screenshot paths in your summary so they can look too.

## Workflow you must follow, every phase

**Before editing:**
1. List the exact files you intend to create or modify.
2. For each upstream Mihon file in that list, justify in one sentence why the change
   cannot live in the `bubblezoom` package.
3. Wait for approval if the list touches an upstream file that was not touched before.

**After editing:**
1. Run `./gradlew :app:compileStandardDebugKotlin` (fast) — not a full assemble.
2. Run any unit tests you added.
3. Build, install and **verify on the emulator with screenshots**, as described above.
   Report what you saw, including anything that looked wrong.
4. List every file changed.
5. Update `docs/BUBBLE_ZOOM_STATUS.md`.
6. Tell the user which screenshots to look at, and what specifically to judge that a
   screenshot cannot answer (animation quality, feel, timing).
7. Stop. Do not start the next phase.

**Never commit unless the user asks.** The user tests on a physical device first.

## Commit message convention

```
bubble zoom: <short imperative description>
```

One commit per phase. No mega-commits.

## Code conventions

- Kotlin, following the surrounding Mihon style. Match the formatting of the file you
  are editing.
- Coroutines: every detection job is tied to a lifecycle scope and **must be cancelled**
  when the page is recycled or the reader closes. Leaked jobs are a reportable bug.
- No magic numbers. Thresholds, paddings and durations go in `BubbleDetectionConfig` /
  `BubbleZoomConfig`.
- Bitmaps: recycle downscaled copies immediately after inference. Never hold a second
  full-resolution copy of a page.
- Pure logic (ordering, coordinate math, filtering) goes in classes with **no Android
  dependencies**, so it can be unit tested on the JVM. Write those tests.

## Things that are easy to get wrong here — check them every time

- Coordinate systems: image space vs. view space vs. screen space. The reader uses a
  subsampling image view with its own source/view coordinate conversion. Use the
  library's conversion methods; never reimplement the math.
- Page recycling: the pager reuses page holders. State keyed by position instead of by
  page identity will show bubbles from the wrong page.
- Rotation and configuration changes: normalized coordinates must survive them.
- Animated images (GIF/WebP animated) and the non-subsampling image path: Bubble Zoom
  must simply stay off for those, not crash.
- Double-page spreads: a spread is one image; treat it as one page.

## Driving the emulator — facts learned the hard way in P0

- **The default reading mode is right-to-left.** `ReaderPreferences.defaultReadingMode`
  defaults to `ReadingMode.RIGHT_TO_LEFT`, so in the reader the **left** tap zone advances
  and the **right** one goes back. RTL is the main path for bubble ordering, not an edge
  case. Verify direction with the page counter before trusting a navigation screenshot.
- **Git Bash mangles absolute paths passed to Windows programs.** `/storage/emulated/0/...`
  becomes `C:/Program Files/Git/storage/...`, and `--sdk_root=D:\Android\Sdk` becomes
  `D:AndroidSdk`, which Windows reads as *drive-relative*. Export `MSYS_NO_PATHCONV=1` for
  `adb shell` commands, and use forward slashes for Windows paths.
- **Double tap and pinch cannot be driven by `adb shell input tap`.** Two separate
  injections are always too far apart to register as one gesture; they land as two single
  taps and just toggle the reader menu. Zoom gestures are a manual check for the user —
  say so instead of claiming they passed.
- **The local source needs a storage folder picked through SAF.** `StorageManager` writes
  `local/`, `downloads/` and `autobackup/` under the folder chosen in onboarding; on this
  emulator that is `/storage/emulated/0/Mihon`. A chapter is a plain directory of images:
  `local/<Manga>/<Chapter>/<page>.png`. `tools/push-test-manga.ps1` does this.

## Definition of done for the MVP

1. A page loads normally and immediately.
2. The detector finds dialogue regions in the background.
3. Those regions are ordered correctly for LTR and RTL.
4. Tap / volume keys step forward and backward through them.
5. Each region is zoomed into with a smooth animation.
6. After the last bubble, normal Mihon page navigation resumes seamlessly.
7. With the preference off, the app is indistinguishable from upstream Mihon.
