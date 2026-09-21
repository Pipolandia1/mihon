#!/usr/bin/env python3
"""Score a whole walk's screenshots against the ground truth, without knowing the choreography.

Counting taps to know which page a screenshot shows turned out to be fragile: Mihon has a
chapter-transition page before page 1, entering a page backwards focuses its last bubble,
and one miscount shifts every measurement after it. So this does not count. For every
screenshot in the walk it decides whether the page is at fit scale (letterboxed top and
bottom), and for those it identifies the page by correlating the on-screen page with each
synthetic test page, magenta pixels masked out. Only then does it measure.

    python tools/measure-walk.py tools/shots p9-walk-
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from measure_detector_lib import MATCH_IOU, components, iou, magenta_mask, page_extent  # noqa: E402

PAGES = Path(__file__).parent / "test-pages"
TRUTH = json.loads((PAGES / "ground-truth.json").read_text(encoding="utf-8"))
THUMB = (120, 180)


def thumb(gray: np.ndarray) -> np.ndarray:
    im = Image.fromarray(gray.astype(np.uint8)).resize(THUMB, Image.BILINEAR)
    a = np.asarray(im, dtype=np.float64)
    a -= a.mean()
    return a / (np.linalg.norm(a) + 1e-9)


REFERENCE = {
    p["file"]: thumb(np.asarray(Image.open(PAGES / p["file"]).convert("L")))
    for p in TRUTH["pages"] if p["file"].endswith(".png")
}


def identify(frame: np.ndarray, extent) -> tuple[str, float]:
    x1, y1, x2, y2 = extent
    region = frame[y1:y2 + 1, x1:x2 + 1].astype(float)
    gray = region.mean(axis=2)
    # Paint the overlay's magenta out with white so it does not vote.
    gray[magenta_mask(frame)[y1:y2 + 1, x1:x2 + 1]] = 255
    t = thumb(gray)
    scores = {name: float((t * ref).sum()) for name, ref in REFERENCE.items()}
    best = max(scores, key=scores.get)
    return best, scores[best]


def at_fit(extent, frame_h: int) -> bool:
    """Letterboxed above and below by a similar amount: the page is at fit scale."""
    _, y1, _, y2 = extent
    top, bottom = y1, frame_h - 1 - y2
    return top > 100 and bottom > 100 and abs(top - bottom) < 60


def measure(frame: np.ndarray, extent, page_file: str) -> dict:
    page = next(p for p in TRUTH["pages"] if p["file"] == page_file)
    gts = [(b["x"], b["y"], b["x"] + b["w"], b["y"] + b["h"]) for b in page.get("bubbles", [])]
    px1, py1, px2, py2 = extent
    pw, ph = px2 - px1 + 1, py2 - py1 + 1
    dets = [((x1 - px1) / pw, (y1 - py1) / ph, (x2 - px1) / pw, (y2 - py1) / ph)
            for (x1, y1, x2, y2) in components(magenta_mask(frame))]
    ug, ud = set(range(len(gts))), set(range(len(dets)))
    matches = []
    for s, gi, di in sorted(((iou(g, d), gi, di) for gi, g in enumerate(gts) for di, d in enumerate(dets)), reverse=True):
        if s < MATCH_IOU or gi not in ug or di not in ud:
            continue
        matches.append((gi, di, s)); ug.discard(gi); ud.discard(di)
    inside = sum(1 for gi, di, _ in matches
                 if dets[di][0] >= gts[gi][0] - 0.01 and dets[di][1] >= gts[gi][1] - 0.01
                 and dets[di][2] <= gts[gi][2] + 0.01 and dets[di][3] <= gts[gi][3] + 0.01)
    return {"exist": len(gts), "detected": len(dets), "matched": len(matches), "fp": len(ud), "fn": len(ug),
            "inside": inside, "ious": [round(s, 3) for _, _, s in matches]}


def main(folder: str, prefix: str) -> int:
    shots = sorted(Path(folder).glob(f"{prefix}*.png"))
    seen: dict[str, dict] = {}
    for shot in shots:
        frame = np.asarray(Image.open(shot).convert("RGB")).astype(int)
        try:
            extent = page_extent(frame)
        except SystemExit:
            print(f"{shot.name:22} black (transition / not a page)")
            continue
        if not at_fit(extent, frame.shape[0]):
            print(f"{shot.name:22} not at fit (zoomed or card) - skipped")
            continue
        name, conf = identify(frame, extent)
        if conf < 0.5:
            print(f"{shot.name:22} at fit but matches no test page (best {name} {conf:.2f})")
            continue
        if name in seen:
            print(f"{shot.name:22} {name} again - already measured")
            continue
        r = measure(frame, extent, name)
        seen[name] = r
        print(f"{shot.name:22} {name:22} exist={r['exist']} detected={r['detected']} matched={r['matched']} "
              f"fp={r['fp']} fn={r['fn']} inside={r['inside']}/{r['matched']} iou={r['ious']}  (id conf {conf:.2f})")

    print("\n===== SUMMARY over pages seen at fit =====")
    tot = {k: sum(r[k] for r in seen.values()) for k in ("exist", "detected", "matched", "fp", "fn", "inside")}
    ious = [s for r in seen.values() for s in r["ious"]]
    print(f"pages={len(seen)}  bubbles exist={tot['exist']} detected={tot['detected']} matched={tot['matched']} "
          f"fp={tot['fp']} fn={tot['fn']}  inside-balloon={tot['inside']}/{tot['matched']}  "
          f"meanIoU={sum(ious) / len(ious) if ious else 0:.3f}")
    missing = [p["file"] for p in TRUTH["pages"] if p["file"].endswith(".png") and p["file"] not in seen]
    if missing:
        print("never seen at fit:", ", ".join(missing))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1], sys.argv[2]))
