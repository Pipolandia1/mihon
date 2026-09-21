#!/usr/bin/env python3
"""Prove that Bubble Zoom OFF behaves like stock Mihon, and that ON does not.

Phase P7's whole claim is "off is vanilla". That is not something to assert; it is
three measurements, and this makes all three twice — once with the feature off and
once with it on — so the two columns can be compared.

    1. VIEW TREE.    Off, none of the feature's views may exist under the page
                     holder. `dumpsys activity top` prints the real hierarchy, so a
                     card or a debug overlay attached anyway cannot hide.
    2. NAVIGATION.   Off, every tap in the forward zone turns a page, so the run
                     visits one page per tap. On, three of every four taps are spent
                     on that page's bubbles, so the same taps reach far fewer pages.
                     A page is recognised by how much ink it carries at fit scale;
                     the off run is what collects those fingerprints, since every
                     screen it produces is by definition a page at fit.
    3. PIXELS.       Off, no magenta anywhere: no debug boxes on any page.

The preference is flipped by hand between the two runs, in the app's own settings —
the reader's gear icon, or Settings > Reader > Pager. Never by editing shared_prefs
from outside: that invalidates the file, the app rewrites it, and this project has
already lost its storage grant that way once.

Run, with the emulator up, the app installed and the test manga pushed:

    python tools/verify-bubble-zoom-preference.py off      # feature off, on page 1
    # turn Bubble Zoom on in the app, go back to page 1
    python tools/verify-bubble-zoom-preference.py on
    python tools/verify-bubble-zoom-preference.py compare
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

ADB = "D:/Android/Sdk/platform-tools/adb.exe"
SHOTS = Path(__file__).parent / "shots"

# The reader's tap zones in a 1080x2400 portrait viewport. Reading mode is RTL by
# default, so the LEFT third is "forward".
FORWARD = (180, 1200)

# The debug overlay's stroke, from BubbleZoomDebugOverlay.STROKE_COLOR.
MAGENTA = ((180, 255), (0, 100), (180, 255))

TAPS = 8


def adb(*args: str, binary: bool = False):
    result = subprocess.run([ADB, *args], capture_output=True)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


def tap(x: int, y: int, settle: float = 1.4) -> None:
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(settle)


def screenshot(name: str) -> np.ndarray:
    data = adb("exec-out", "screencap", "-p", binary=True)
    SHOTS.mkdir(exist_ok=True)
    path = SHOTS / f"{name}.png"
    path.write_bytes(data)
    return np.asarray(Image.open(path).convert("RGB")).astype(int)


def magenta_pixels(frame: np.ndarray) -> int:
    (rlo, rhi), (glo, ghi), (blo, bhi) = MAGENTA
    return int(
        (
            (frame[:, :, 0] >= rlo) & (frame[:, :, 0] <= rhi)
            & (frame[:, :, 1] >= glo) & (frame[:, :, 1] <= ghi)
            & (frame[:, :, 2] >= blo) & (frame[:, :, 2] <= bhi)
        ).sum()
    )


def bubble_zoom_views() -> list[str]:
    """Every one of the feature's views currently in the reader's hierarchy."""
    dump = adb("shell", "dumpsys", "activity", "top")
    return sorted({name for name in re.findall(r"BubbleZoom\w+", dump)})


def ink(frame: np.ndarray) -> int:
    """How much ink the screen carries.

    A page fingerprint, not a page number: no OCR and none wanted. Each synthetic
    test page carries a different and stable amount, so a screen can be matched back
    to the page it shows. Only screens at fit scale are comparable — a lifted card
    magnifies the text and changes the count — which is why the off run, where every
    screen is a page at fit, is the one that supplies the fingerprints.
    """
    return int((frame.mean(axis=2) < 128).sum())


def measure(label: str) -> dict:
    frame = screenshot(f"p7-{label}-00-start")
    inks = [ink(frame)]
    magenta = [magenta_pixels(frame)]
    views = set(bubble_zoom_views())

    for i in range(TAPS):
        tap(*FORWARD)
        frame = screenshot(f"p7-{label}-{i + 1:02d}-tap")
        inks.append(ink(frame))
        magenta.append(magenta_pixels(frame))
        views |= set(bubble_zoom_views())

    return {
        "label": label,
        "views": sorted(views),
        "magentaMax": max(magenta),
        "inks": inks,
    }


# A page redraws identically between runs apart from the clock in the status bar, a
# few hundred pixels at most.
INK_TOLERANCE = 3000


def pages_reached(inks: list[int], fingerprints: list[int]) -> int:
    """How many of the known pages these screens include."""
    hit = set()
    for value in inks:
        for index, fingerprint in enumerate(fingerprints):
            if abs(value - fingerprint) <= INK_TOLERANCE:
                hit.add(index)
                break
    return len(hit)


def fingerprints_of(inks: list[int]) -> list[int]:
    """Collapses a run's screens into one value per distinct page."""
    pages: list[int] = []
    for value in inks:
        if all(abs(value - seen) > INK_TOLERANCE for seen in pages):
            pages.append(value)
    return pages


def compare() -> int:
    try:
        off = json.loads((SHOTS / "p7-off.json").read_text(encoding="utf-8"))
        on = json.loads((SHOTS / "p7-on.json").read_text(encoding="utf-8"))
    except FileNotFoundError as missing:
        print(f"run both columns first — {missing.filename} is not there")
        return 2

    # Every screen the off run produced is a page at fit, so it is the reference.
    fingerprints = fingerprints_of(off["inks"])
    off_pages = len(fingerprints)
    on_pages = pages_reached(on["inks"], fingerprints)

    print(f"{'':34}{'OFF':>24}{'ON':>24}")
    for title, key in (
        ("Bubble Zoom views in the tree", "views"),
        ("magenta pixels (debug boxes)", "magentaMax"),
    ):
        fmt = (lambda v: ", ".join(v) or "none") if key == "views" else str
        print(f"{title:34}{fmt(off[key]):>24}{fmt(on[key]):>24}")
    print(f"{f'pages reached in {TAPS} taps':34}{off_pages:>24}{on_pages:>24}")

    failures = []
    if off["views"]:
        failures.append(f"OFF still has the feature's views attached: {off['views']}")
    if off["magentaMax"]:
        failures.append(f"OFF drew debug boxes ({off['magentaMax']} magenta pixels)")
    if off_pages <= on_pages:
        failures.append(
            f"OFF reached {off_pages} pages and ON reached {on_pages} for the same "
            f"{TAPS} taps: with the feature off, taps are still being swallowed"
        )
    if not on["views"]:
        failures.append("ON attached no views at all, so the comparison proves nothing")

    print()
    for failure in failures:
        print("FAIL:", failure)
    if not failures:
        print("PASS: off is indistinguishable from stock, and on is not.")
    return 1 if failures else 0


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "compare":
        raise SystemExit(compare())
    if mode not in ("off", "on"):
        print(__doc__)
        raise SystemExit(2)

    data = measure(mode)
    out = SHOTS / f"p7-{mode}.json"
    out.write_text(json.dumps(data, indent=2), encoding="utf-8", newline="\n")
    print(f"{mode.upper()}:")
    for key, value in data.items():
        print(f"  {key}: {value}")
    print(f"\nwritten to {out}")
