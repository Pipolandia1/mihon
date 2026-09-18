# Bubble Zoom — Prompts prontos, fase por fase

Como usar:

1. **Janela 1:** `C:\dev\mihon\tools\start-emulator.ps1` (depois do P0 esse script existe).
2. **Janela 2:** `cd C:\dev\mihon` → `claude` → `/clear`.
3. Cole o prompt da fase.
4. Ele lista os arquivos que vai mexer — **você aprova**.
5. Ele implementa, compila, instala no emulador, tira screenshots e olha os screenshots.
6. Você olha os screenshots também.
7. Cole o **Prompt de Revisão**, depois o de **Correção** se aparecer algo.
8. Commit, `/clear`, próxima fase.

Regra de ouro: **um prompt = uma mudança verificável.** Nunca "implemente o Bubble Zoom".

O `CLAUDE.md` já obriga o agente a verificar no emulador com screenshot ao fim de cada
fase — por isso os prompts abaixo não repetem isso toda hora.

---

## P0 — Setup completo do ambiente

Cole isto na primeira sessão, dentro de `C:\dev`, com o Claude Code recém-instalado.
**Troque `SEU-USUARIO`.** Esta fase é longa (~1h, quase tudo download) e vai pedir sua
aprovação algumas vezes.

```
You are setting up an Android development environment from scratch on Windows, then
building a fork of Mihon and preparing an emulator-based test loop. I have Git for
Windows and Claude Code installed, virtualization enabled, and long paths enabled.
Nothing else. Do all of this yourself; ask me only when you genuinely cannot proceed.

Work in C:/dev. Report progress as you go. If a step fails, diagnose and retry before
asking me.

=== STEP 1: JDK ===
Install a JDK with winget (Microsoft OpenJDK or Eclipse Temurin). Which major version:
first fetch https://raw.githubusercontent.com/mihonapp/mihon/main/.github/workflows/build_push.yml
and use the same java-version the project's CI uses. If you cannot read it, use 17.
Set JAVA_HOME as a user environment variable and verify with `java -version`.

=== STEP 2: Android SDK, command line only ===
Do NOT install Android Studio. Install the SDK at C:/Android/Sdk (short path, no spaces):
- download the Windows commandline-tools zip from the official Android developer site
- extract to C:/Android/Sdk/cmdline-tools/latest  (the "latest" folder name matters)
- set ANDROID_HOME=C:/Android/Sdk as a user env var, and add to PATH:
  cmdline-tools/latest/bin, platform-tools, emulator
- accept licenses: `sdkmanager --licenses`
- install: platform-tools, emulator, the platform and build-tools matching the compileSdk
  in the Mihon repo (read app/build.gradle.kts after step 3 if needed; otherwise use the
  newest stable), and a system image: system-images;android-35;google_apis;x86_64
Verify `adb --version` and `sdkmanager --list_installed`.

=== STEP 3: Clone the fork ===
git clone https://github.com/SEU-USUARIO/mihon.git C:/dev/mihon
cd C:/dev/mihon
git remote add upstream https://github.com/mihonapp/mihon.git
git fetch upstream
git checkout -b feature/bubble-zoom

=== STEP 4: Baseline build ===
Read CONTRIBUTING.md and app/build.gradle.kts first, and tell me what you learn about
build variants and any required files (e.g. google-services.json) BEFORE building.
Then list the assemble tasks and build the debug variant.
If the build fails, read the actual error and fix the environment — do not modify the
project's source code in this phase. The baseline must be unmodified upstream Mihon.

=== STEP 5: Emulator, launched OUTSIDE your session ===
Create an AVD named mihon_test from the system image above, Pixel-class device profile,
with a reasonable RAM/heap and a 2GB+ userdata partition.

Create C:/dev/mihon/tools/start-emulator.ps1 that:
- starts `emulator -avd mihon_test -gpu host -no-snapshot-load`
- prints a clear message that this window must stay open
Then STOP and ask me to run it in a second terminal. Do not start the emulator yourself —
background processes you own get killed when this session ends.
Once I confirm, wait for `adb devices` to show the device and for
`adb shell getprop sys.boot_completed` to return 1.

=== STEP 6: Install and smoke-test ===
Install the APK, launch the app, take a screenshot to tools/shots/p0-launch.png, read that
screenshot, and tell me what you actually see.

=== STEP 7: Synthetic test manga (this is what makes every later phase verifiable) ===
Mihon has a local source that reads manga from device storage. Find out from the
repository source code exactly which directory it uses on this Android version and how a
chapter must be laid out. Do not guess — read the code.

Then write C:/dev/mihon/tools/make-test-pages.py that generates synthetic manga pages as
PNGs (use Pillow; install it if needed):
- page size 1600x2400, white background, black panel borders
- speech-bubble-like shapes (rounded rectangles and ellipses with black outlines) filled
  white, containing lorem-ipsum-style text
- IMPORTANT: it must write a sidecar file tools/test-pages/ground-truth.json listing, for
  each page, every bubble's exact normalized coordinates (x, y, w, h in 0..1) and the
  correct LTR and RTL reading order
- generate at least these pages:
  1. three bubbles at exactly (0.62,0.08,0.23,0.14), (0.15,0.31,0.28,0.16),
     (0.58,0.67,0.26,0.15) — the same boxes phase P3 hardcodes
  2. a 2x2 panel grid with two bubbles per panel (for reading-order tests)
  3. two side-by-side bubbles plus one below (the LTR/RTL example from the spec)
  4. a page with no bubbles at all
  5. a bubble at each extreme corner, touching the page edge

Also write tools/push-test-manga.ps1 that packages those pages into the local-source
layout you discovered and pushes them to the emulator with adb, then triggers a library
refresh if one is needed.

Run both. Open the test manga in the reader on the emulator, screenshot it to
tools/shots/p0-reader.png, read the screenshot, and confirm the pages display correctly.

=== STEP 8: Baseline regression checklist ===
Drive the emulator with adb input commands and screenshots to verify, on the test manga:
page forward, page back, pinch/double-tap zoom, rotation to landscape and back, and RTL
mode. Save a screenshot for each and read them. Report anything that looks wrong.
These screenshots are the baseline I will compare every later phase against — keep them
in tools/shots/baseline/.

=== STEP 9: Wire up the project ===
Add to .gitignore: tools/shots/, tools/test-pages/, bubble-test-pages/, *.onnx, *.tflite
Then write docs/BUBBLE_ZOOM_STATUS.md marking P0 complete, listing the exact paths,
versions and AVD name you ended up with, plus any environment quirk I should know about.

=== FINALLY ===
Give me: the exact commands to rebuild + reinstall + screenshot in one line (I will reuse
it constantly), the list of everything you installed and where, and the git commit command
for the baseline. Do not commit.
```

> Depois do P0, copie os arquivos deste kit (`CLAUDE.md`, `.claude/settings.json`,
> `docs/`) para dentro de `C:\dev\mihon` — ou peça ao próprio Claude Code para fazer isso,
> já que a essa altura ele tem acesso à pasta.

---

## P1 — Mapa técnico do reader

> Use plan mode aqui (`Shift+Tab` duas vezes). Esta fase não escreve código de app.

```
We are going to implement a Bubble Zoom feature (like Google Play Books) in this Mihon fork.

DO NOT MODIFY ANY APPLICATION CODE. This is a read-only audit.

Read docs/BUBBLE_ZOOM_SPEC.md first, then study the repository and produce a precise
technical map of the reader. Answer with real file paths, class names and method
signatures from THIS repository — never from memory or from upstream Tachiyomi.

Identify:
1. Where a ReaderPage's decoded image becomes available, and the exact callback/lifecycle point.
2. Which view actually renders the page image, and how it differs for large images vs
   small/animated ones (subsampling path vs plain ImageView path).
3. The exact API available for converting between image (source) coordinates and view
   coordinates, and for animating scale + center.
4. What minScale / maxScale mean in this codebase and where they are computed.
5. Where tap navigation (next/previous) is handled, and the class that owns moveToNext/moveToPrevious.
6. Where volume-key navigation is handled.
7. Where double-tap zoom is handled.
8. Where reader preferences are defined and where the reader settings UI is built.
9. How pages are recycled/reused in the pager, and how neighboring pages are preloaded.
10. How the reader knows it is in LTR vs RTL mode.
11. How double-page spreads are represented.
12. The minimal set of upstream files that would need modification, with a one-line
    justification each.

Then propose a minimal integration architecture consistent with docs/BUBBLE_ZOOM_SPEC.md,
and flag anything in that spec that does not match the reality of this codebase.

Write the result to docs/BUBBLE_ZOOM_ARCHITECTURE.md. That is the only file you may create.
```

**Antes de seguir:** leia o documento gerado e corrija à mão o que estiver errado. Todas
as fases seguintes dependem dele.

---

## P2 — Modelos de dados

```
Implement ONLY phase P2 from docs/BUBBLE_ZOOM_SPEC.md.

Create the data models in
app/src/main/java/eu/kanade/tachiyomi/ui/reader/bubblezoom/ :

- BubbleBox: x, y, width, height as normalized Floats (0.0–1.0, relative to the original
  image). Include: centerX, centerY, area, a padded(fraction) method that expands the box
  while clamping to 0..1, a contains(nx, ny) method, and intersectionOverUnion(other).
- BubbleType: enum TEXT, PANEL, UNKNOWN.
- BubbleDetection: box, confidence, type.
- BubblePageResult: ordered List<BubbleDetection>, modelVersion, detectionTimeMs.

Rules:
- Pure Kotlin, zero Android imports.
- require() validation in BubbleBox's init for the 0..1 range.
- No reader code is touched in this phase.

Also add JVM unit tests covering padded() clamping at the page edges, contains(), and IoU.

Do not create the detector, the controller, or any view. Do not modify any upstream file.
```

---

## P3 — Debug overlay com boxes hardcoded

> **A fase mais importante do projeto.** Se as caixas ficarem grudadas na imagem durante
> zoom, pan e rotação, o problema mais difícil está resolvido.

```
Implement ONLY phase P3 from docs/BUBBLE_ZOOM_SPEC.md.

Goal: draw three hardcoded BubbleBoxes on top of the current page image, perfectly
aligned with the image content, and keep them aligned during zoom, pan, rotation and
window resize.

Use exactly these boxes:
  BubbleBox(0.62f, 0.08f, 0.23f, 0.14f)
  BubbleBox(0.15f, 0.31f, 0.28f, 0.16f)
  BubbleBox(0.58f, 0.67f, 0.26f, 0.15f)

Requirements:
- New file: bubblezoom/BubbleZoomDebugOverlay.kt — a View that draws the rectangles.
- It must convert normalized coords -> original image pixel coords -> view coords using
  the coordinate-conversion API documented in docs/BUBBLE_ZOOM_ARCHITECTURE.md.
  Do NOT reimplement the transform math yourself.
- It must redraw when the image view's scale or center changes. Use the callback
  mechanism the image view already provides.
- Only enable it on the subsampling-image path. On the plain/animated ImageView path it
  must be a no-op, never a crash.
- For now, gate it behind a hardcoded `const val BUBBLE_ZOOM_DEBUG = true` so I can flip
  it off. The real preference comes in P7.
- Keep the change in the upstream page-view class to the absolute minimum: adding the
  overlay child and forwarding the coordinate/scale callbacks.

Do not add: detection, navigation, controller, animation, preferences.

Before editing, list the files you will touch and justify each upstream modification.

VERIFICATION — this is the phase where alignment must be proven, not assumed:
Use test page 1 of the synthetic test manga, whose bubbles are drawn at EXACTLY the three
coordinates above (see tools/test-pages/ground-truth.json). Build, install, open that page
and capture screenshots for each of these states, reading each one:
  p3-fit.png        page at default fit
  p3-zoomed.png     after a pinch/double-tap zoom in
  p3-panned.png     after panning to a corner
  p3-landscape.png  after rotating to landscape
  p3-back.png       after rotating back to portrait
In every single one, the drawn overlay rectangles must sit exactly on top of the drawn
bubbles in the artwork. Any visible offset is a failure — report it as such, do not
rationalize it. Tell me the screenshot paths so I can check with my own eyes.
```

**Teste manual obrigatório:** abra uma página, dê zoom, arraste, gire a tela, volte e
avance páginas. As caixas devem permanecer coladas no mesmo ponto do desenho, sempre.

---

## P4 — Foco animado

```
Implement ONLY phase P4 from docs/BUBBLE_ZOOM_SPEC.md.

Add the ability to animate the reader's camera onto a BubbleBox.

- Add a method on the page image view (or an extension/helper in the bubblezoom package
  if that keeps upstream cleaner): focusOnBubble(box: BubbleBox).
- Behavior: expand the box by BubbleZoomConfig.focusPadding (default 0.20), convert to
  original-image coordinates, compute the center, compute a target scale such that the
  padded box occupies ~60% of the usable screen width, clamp the scale between the view's
  minScale and maxScale, and animate scale + center together.
- Animation duration from BubbleZoomConfig (default 200 ms), with a decelerate/ease-out
  interpolator.
- Add resetBubbleZoom(): animate back to the view's default fit for the page.
- Create BubbleZoomConfig.kt for these constants. No magic numbers anywhere else.
- Cancel any in-flight focus animation before starting a new one.
- Handle the degenerate cases: box larger than the viewport, box at the very edge of the
  page, extremely tall or wide box.

For testing only, wire the three hardcoded P3 boxes to a temporary debug trigger (e.g. a
long-press cycles through box 0, 1, 2, then reset). Mark that trigger clearly with
// TEMP P4 so it is easy to remove in P6.

Do not touch navigation, detection or preferences.
```

---

## P5 — Controller

```
Implement ONLY phase P5 from docs/BUBBLE_ZOOM_SPEC.md.

Create bubblezoom/BubbleZoomController.kt and bubblezoom/BubbleZoomState.kt.

State: Disabled, Idle, Detecting, Ready(bubbles, currentIndex), Error.
currentIndex starts at -1 meaning "page shown normally, no bubble focused".

Operations:
  activatePage(page, bubbles: List<BubbleDetection>)   // bubbles injected for now
  tryMoveNext(): Boolean
  tryMovePrevious(): Boolean
  selectBubble(index: Int)
  reset()
  disable()

Semantics:
- tryMoveNext() advances currentIndex and returns true while there is a next bubble;
  when already on the last bubble it resets zoom, returns false, and leaves the state
  ready for the next page.
- tryMovePrevious() mirrors that: from index -1 or 0 it returns false so the reader can
  go back a page; when it does go back a page, the previous page must start on its LAST
  bubble, not the first. Expose that intent in the API.
- The controller exposes state as a StateFlow. It knows nothing about views, bitmaps,
  ML, or Android UI. It must be unit-testable on the JVM.
- Feed it the three hardcoded boxes for now.

Add JVM unit tests for the full forward/backward walk, including both boundaries.

Do not wire navigation yet. Do not add detection.
```

---

## P6 — Integração com a navegação

> Fim do protótipo. Depois desta fase a experiência inteira já funciona, com balões falsos.

```
Implement ONLY phase P6 from docs/BUBBLE_ZOOM_SPEC.md.

Route the reader's existing navigation through BubbleZoomController.

In the class that owns moveToNext()/moveToPrevious():
    fun moveToNext() {
        if (bubbleZoomController.tryMoveNext()) return
        // ...existing Mihon logic, untouched...
    }
and the mirror for moveToPrevious().

Requirements:
- Do NOT create a parallel navigation system. This is the only place navigation branches.
- When the reader moves to a new page, call activatePage() and reset the bubble index.
  Moving backwards into a page must start at its last bubble.
- When the page holder is recycled, the controller state for that page must be released
  and any pending work cancelled.
- Remove the // TEMP P4 debug trigger.
- Bubble state must be keyed by page identity, never by adapter position.

Expected behavior on device (LTR):
  page 7 -> tap -> bubble 1 -> tap -> bubble 2 -> tap -> bubble 3 -> tap -> page 8 -> bubble 1
and exactly the reverse going back.

VERIFICATION: drive the emulator with adb input taps and capture the whole sequence —
page N fit, bubble 1, bubble 2, bubble 3, page N+1 fit, bubble 1 — then the same walk
backwards. Read every screenshot and confirm the sequence is exactly right, including
that going back into a page lands on its LAST bubble. Also test: fast repeated taps,
swiping pages by gesture mid-walk, and rotating mid-walk. Report anything off.
```

---

## P7 — Preferência

```
Implement ONLY phase P7 from docs/BUBBLE_ZOOM_SPEC.md.

Add a single reader preference: "Bubble Zoom" (boolean, default false), plus a second
one "Bubble Zoom debug overlay" (boolean, default false) in the same section.

- Follow exactly the pattern used by the existing reader preferences and the existing
  reader settings UI in this repo. Do not invent a new mechanism.
- OFF must mean: BubbleZoomController is Disabled, no overlay is attached, no detection
  scheduled, no coordinate callbacks registered, navigation goes straight to the original
  Mihon code path. Byte-for-byte vanilla behavior.
- Toggling the preference while the reader is open must take effect without restarting
  the reader.
- Replace the hardcoded BUBBLE_ZOOM_DEBUG constant from P3 with the debug preference.

After editing, tell me exactly where the toggle appears in the settings UI, and give me a
regression test script proving that OFF behaves like stock Mihon.
```

---

## P8 — Abstração do detector

```
Implement ONLY phase P8 from docs/BUBBLE_ZOOM_SPEC.md.

Introduce the detector abstraction and wire the whole pipeline with a fake implementation.

- bubblezoom/detector/BubbleDetector.kt:
      interface BubbleDetector {
          val modelVersion: Int
          suspend fun detect(image: BubbleDetectionImage): List<BubbleDetection>
      }
  BubbleDetectionImage carries the bitmap plus its original width/height. Design it so a
  future implementation can accept a file path instead of a bitmap without changing callers.
- bubblezoom/detector/FakeBubbleDetector.kt: returns the three known boxes after a
  configurable artificial delay (default 300 ms), to simulate latency.
- The controller now asks the detector instead of receiving a hardcoded list. It goes
  Idle -> Detecting -> Ready/Error.
- Detection runs on Dispatchers.Default in a scope tied to the page holder / reader
  lifecycle, and is cancelled on recycle.
- The page must render immediately; bubbles arrive later. If the user taps while state is
  Detecting, the tap does normal page navigation — never a freeze, never a queued jump.

After this phase, nothing in reader code may know where the boxes came from.

Do not add any real ML dependency yet.
```

---

## P9 — Detector real

> A partir daqui você precisa do arquivo de modelo. Veja a seção "Modelo" no fim.

```
Implement ONLY phase P9 from docs/BUBBLE_ZOOM_SPEC.md.

Add a real on-device detector using ONNX Runtime Mobile.

- Add the dependency com.microsoft.onnxruntime:onnxruntime-android to the app module,
  following this repo's version catalog conventions (gradle/libs.versions.toml).
- bubblezoom/detector/OnnxBubbleDetector.kt implementing BubbleDetector.
- Model file at app/src/main/assets/bubble_detector.onnx (I will supply it; the file is
  gitignored). If the asset is missing, the detector must report Error cleanly and the
  reader must behave exactly as if Bubble Zoom were off. No crash, no ANR.
- Image pipeline, exactly as specified in the spec:
  original bitmap -> letterboxed downscale to BubbleDetectionConfig.inputSize (640)
  -> float tensor -> inference -> NMS -> undo letterbox -> normalize to 0..1
  -> recycle the scaled bitmap immediately.
- Inference on Dispatchers.Default. Never on the main thread. Never more than one
  inference running at a time (guard with a Mutex or a single-threaded dispatcher).
- The ORT session is created once and closed with the reader. No session per page.
- Log the inference time into BubblePageResult.detectionTimeMs.
- Keep FakeBubbleDetector selectable, ideally via the debug preference, so I can A/B them.

Before editing, tell me the output tensor layout you are assuming and how you will verify
it against the actual model, rather than guessing.

VERIFICATION: run the detector on the synthetic test pages and compare the boxes it
returns against tools/test-pages/ground-truth.json. Report, per page: how many bubbles
exist, how many were detected, IoU of each match, false positives, false negatives, and
inference time from logcat. Then do the same visually on a real manga page with the debug
overlay on, and read the screenshot. Be blunt about how good or bad the detector actually
is — bad numbers here are information, not failure.
```

---

## P10 — Filtro de confiança

```
Implement ONLY phase P10 from docs/BUBBLE_ZOOM_SPEC.md.

Centralize all detection tuning in bubblezoom/BubbleDetectionConfig.kt:
confidenceThreshold (0.35), nmsIouThreshold (0.45), boxPadding (0.20), inputSize (640),
maxDetections (40), minBoxAreaFraction (0.0015), maxBoxAreaFraction (0.5).

- Apply: confidence filter, NMS, area filters, cap on maxDetections (keep highest confidence).
- Remove every magic number related to detection from the rest of the codebase.
- Boxes that overlap heavily after NMS should be merged rather than both kept, when they
  are the same class. Explain the merge rule you chose.

No UI for these values yet — they are constants. Do not touch the reader.
```

---

## P11 — Cache e prefetch

```
Implement ONLY phase P11 from docs/BUBBLE_ZOOM_SPEC.md.

Add bubblezoom/BubbleZoomCache.kt with an in-memory LruCache (40 entries) keyed by
manga id + chapter id + page index + modelVersion.

Add prefetch: when the user lands on page N, schedule detection for N, N+1, N+2 only.
- Exactly one inference at a time, with the visible page always jumping the queue.
- Cancel queued/running jobs for pages that leave the window.
- A cache hit must be instant, with no Detecting state flash.

Verify and tell me how to observe: going back one page must NOT re-run inference.

Do not add disk persistence in this phase.
```

---

## P12 — Painéis e ordem de leitura

> Use plan mode. É a fase com mais decisões de algoritmo.

```
Implement ONLY phase P12 from docs/BUBBLE_ZOOM_SPEC.md.

1. Make the detector also emit PANEL detections (keep TEXT and PANEL separate in
   BubbleType). If the model has no panel class, say so explicitly and implement only the
   fallback below — do not fake panels.
2. Create bubblezoom/BubbleOrderResolver.kt as a pure Kotlin class with no Android imports:
       fun order(texts: List<BubbleDetection>, panels: List<BubbleDetection>, rtl: Boolean): List<BubbleDetection>
   Algorithm from the spec: group panels into horizontal bands (vertical tolerance 5% of
   page height), order bands top->bottom, order within a band left->right (LTR) or
   right->left (RTL); assign each text box to the panel with the highest IoU, or the
   nearest panel center if none overlaps; order texts inside each panel with the same
   band rule; orphan texts go last, same rule.
   Fallback with no panels: apply the band rule directly to the text boxes.
3. The reader must get the ordered list and nothing else. RTL mode comes from the reader's
   existing reading-direction setting — find it, do not add a new one.

Add JVM unit tests for: simple vertical stack, two side-by-side bubbles plus one below
(the spec's example, both LTR and RTL), a bubble straddling two panels, zero panels
detected, zero bubbles detected, and a single bubble.

These tests must pass before you report success.

VERIFICATION: tools/test-pages/ground-truth.json contains the correct LTR and RTL order
for the synthetic pages. Run the resolver against those pages and report the exact
ordering it produced versus the expected one. Then verify visually on the emulator with
the debug overlay showing reading-order numbers, in both LTR and RTL mode.
```

---

## P13 — Cache em disco

```
Implement ONLY phase P13 from docs/BUBBLE_ZOOM_SPEC.md.

Persist BubblePageResult to disk.

- Location: the app's cacheDir, in a dedicated subfolder. Never filesDir. It must NOT be
  included in Mihon's backup/restore.
- One small JSON per page, using the serialization library already used in this repo.
- Key: manga id + chapter id + page index + a short hash of the image bytes + modelVersion.
  Explain how you compute the image hash cheaply without reading the whole file twice.
- On modelVersion mismatch, ignore and overwrite the entry.
- Add a size cap with LRU eviction (start at 20 MB) and a "clear Bubble Zoom cache"
  action wherever this repo already offers cache-clearing actions in settings.
- All disk I/O on Dispatchers.IO. A corrupt or unreadable entry must be treated as a miss,
  never as an error.

Verify: kill the app, reopen the same chapter, and detection must not run again.
```

---

## P14 — Teclas de volume

```
Implement ONLY phase P14 from docs/BUBBLE_ZOOM_SPEC.md.

When Bubble Zoom is active, volume-down = next bubble, volume-up = previous bubble,
falling through to normal page navigation when the bubbles run out.

Reuse the exact same tryMoveNext()/tryMovePrevious() path used by tap navigation. Do not
duplicate one line of navigation logic. Respect the user's existing "volume keys" and
"invert volume keys" preferences.
```

---

## P15 — Double tap

```
Implement ONLY phase P15 from docs/BUBBLE_ZOOM_SPEC.md.

On double tap: convert the touch point from view coordinates to normalized image
coordinates, find the smallest BubbleBox containing that point, and if one exists,
selectBubble(itsIndex). If none exists, fall through to Mihon's normal double-tap zoom,
completely unchanged.

If state is not Ready, always fall through.

Verify on device that double-tapping empty art still does the stock zoom.
```

---

## P16 — Overlay visual

```
Implement ONLY phase P16 from docs/BUBBLE_ZOOM_SPEC.md.

Create bubblezoom/BubbleZoomOverlayView.kt: instead of only moving the reader's camera,
visually lift the focused bubble.

- Draw the focused region (box + BubbleZoomConfig.focusPadding) as a card above the page:
  scale-in animation, fade, subtle elevation/shadow, 180–220 ms, ease-out.
- Source pixels come from the already-decoded page; do not re-decode the image and do not
  keep a second full-resolution copy.
- The card must stay inside the screen bounds, shifting rather than clipping when the
  bubble is near an edge.
- Transition directly between consecutive bubbles without a flash of the page in between.
- Dismiss on tap outside, on back press, and when Bubble Zoom navigation ends.
- It must degrade gracefully: if anything fails, fall back to the P4 camera-zoom behavior.

Theme: respect the app's dark/light theme for the card background and scrim.
```

---

## P17 — Debug HUD

```
Implement ONLY phase P17 from docs/BUBBLE_ZOOM_SPEC.md.

Extend the debug overlay (behind the existing debug preference) to show:
- every detected box with its index and confidence, colored by BubbleType
- panel boxes in a distinct color, with their computed reading order number
- a corner HUD: "Detection: 143 ms | Panels: 6 | Texts: 11 | Accepted: 9 | Rejected: 2 |
  Cache: HIT/MISS | Model: v1"

This is the instrument panel that tells me whether a bad result came from the detector,
the threshold, the coordinate transform, the reading order, or the rendering. Make it
readable over both dark and light artwork.
```

---

## P18 — Dataset e tuning

```
Implement ONLY phase P18 from docs/BUBBLE_ZOOM_SPEC.md.

I have placed ~30 test pages in bubble-test-pages/ (gitignored) with the categories
listed in section 13 of the spec.

Build a small JVM/instrumented harness that runs the detector over that folder and
produces a report: per page — detections, confidences, inference time; and a summary with
mean/median/p95 inference time.

Then tell me, based on the numbers only:
1. which confidenceThreshold value maximizes recall without obvious false positives;
2. whether inference time meets the spec targets (<500 ms, ideally <200 ms) on my device;
3. which page categories fail, and whether the failure is detection or ordering.

Do not change tuning constants until I approve the numbers.
```

---

# Prompt de revisão (use depois de CADA fase, antes do commit)

```
Review the uncommitted diff for the Bubble Zoom phase we just finished.

DO NOT MODIFY CODE. Report only.

Check specifically for:
- regressions in normal Mihon reader behavior, especially with Bubble Zoom disabled
- lifecycle leaks and coroutine jobs not cancelled on page recycle or reader close
- anything doing real work on the main thread
- unnecessary or oversized modifications to upstream Mihon files (the target is 90%+ of
  the feature inside the bubblezoom package)
- duplicated navigation logic instead of going through the controller
- coordinate-system errors (image vs view vs screen; normalized vs pixel)
- unsafe bitmap usage: second full-resolution copies, bitmaps not recycled
- coupling between the ML implementation and reader UI
- state keyed by adapter position instead of page identity
- cache invalidation problems
- magic numbers that should live in a config object
- missing handling for: rotation, double-page spreads, animated images, pages with zero
  detections

For each problem report: severity (critical/major/minor), file, class/method, why it is a
problem, and the minimal fix. Concrete problems only — no style opinions, no praise.
```

# Prompt de correção

```
Fix ONLY the following issues from your review: [cole os números/itens].

Nothing else. No refactors, no improvements, no renames. After fixing, show me the diff
of the fixes alone and re-run the compile check.
```

# Prompt de fim de fase

```
Update docs/BUBBLE_ZOOM_STATUS.md to reflect the phase we just completed: current phase,
working checklist, files modified, new files, known issues, next phase. Keep it short.
Then give me the exact git commit command with the right message, following the
convention in CLAUDE.md. Do not run it.
```

---

# Sobre o modelo (necessário a partir de P9)

Pegue um modelo YOLO de detecção de balões já em ONNX. Candidatos na tabela do
`BUBBLE_ZOOM_SPEC.md`. O arquivo vai em `app/src/main/assets/bubble_detector.onnx` e
**não** entra no Git.

Antes de embarcar, descubra o formato real de saída dele (número de classes, layout do
tensor). Um jeito rápido, no PC:

```bash
pip install onnx onnxruntime
python -c "
import onnx
m = onnx.load('bubble_detector.onnx')
print([(i.name, [d.dim_value or d.dim_param for d in i.type.tensor_type.shape.dim]) for i in m.graph.input])
print([(o.name, [d.dim_value or d.dim_param for d in o.type.tensor_type.shape.dim]) for o in m.graph.output])
print(m.metadata_props)
"
```

Cole essa saída no prompt P9. Sem ela, o agente vai chutar o parsing do tensor — e é aí
que essas integrações costumam quebrar.

**Licença:** se o modelo for GPL-3.0/AGPL-3.0 e você algum dia quiser distribuir o APK,
resolva isso antes de investir em tuning. Para uso pessoal, tudo bem.
