#!/usr/bin/env python3
"""Read the detector's boxes off the screen and score them against the ground truth.

With "Show detected regions" on, the debug overlay draws every box the controller holds
for the page - since P9, the real detections - as a magenta outline. So a screenshot IS the
measurement: find the magenta rectangles, map them from screen pixels back to page-normalized
coordinates using the page's own extent on screen, and compare with ground-truth.json.

Reports, per page, what the P9 brief asks for: bubbles that exist, bubbles detected, IoU of
each match, false positives, false negatives. Inference time comes from logcat separately.

    python tools/measure-detector-on-device.py tools/shots/p9-dev-02.png 02-panel-grid.png
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

TRUTH = json.loads((Path(__file__).parent / "test-pages" / "ground-truth.json").read_text(encoding="utf-8"))

# BubbleZoomDebugOverlay.STROKE_COLOR is argb(0xFF, 0xFF, 0x00, 0xAA); allow blending.
MAGENTA = ((170, 255), (0, 110), (120, 255))
MATCH_IOU = 0.3  # the model boxes text, not balloons, so IoU against the balloon is modest
MIN_COMPONENT_PX = 200


def magenta_mask(frame: np.ndarray) -> np.ndarray:
    (rlo, rhi), (glo, ghi), (blo, bhi) = MAGENTA
    return (
        (frame[:, :, 0] >= rlo) & (frame[:, :, 0] <= rhi)
        & (frame[:, :, 1] >= glo) & (frame[:, :, 1] <= ghi)
        & (frame[:, :, 2] >= blo) & (frame[:, :, 2] <= bhi)
    )


def page_extent(frame: np.ndarray) -> tuple[int, int, int, int]:
    """The page's rectangle on screen: the non-black region, ignoring the status/nav bars."""
    lum = frame.mean(axis=2)
    rows = np.nonzero((lum > 40).mean(axis=1) > 0.5)[0]
    cols = np.nonzero((lum > 40).mean(axis=0) > 0.5)[0]
    if len(rows) == 0 or len(cols) == 0:
        raise SystemExit("no page on screen: the screenshot is black (transition, menu, or not the reader)")
    return int(cols.min()), int(rows.min()), int(cols.max()), int(rows.max())


def components(mask: np.ndarray) -> list[tuple[int, int, int, int]]:
    """Bounding boxes of 8-connected magenta blobs. Each drawn rectangle outline is one blob."""
    h, w = mask.shape
    seen = np.zeros_like(mask, dtype=bool)
    boxes = []
    ys, xs = np.nonzero(mask)
    for sy, sx in zip(ys, xs):
        if seen[sy, sx]:
            continue
        stack = [(sy, sx)]
        seen[sy, sx] = True
        x1 = x2 = sx
        y1 = y2 = sy
        n = 0
        while stack:
            y, x = stack.pop()
            n += 1
            x1, x2, y1, y2 = min(x1, x), max(x2, x), min(y1, y), max(y2, y)
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    ny, nx = y + dy, x + dx
                    if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        stack.append((ny, nx))
        if n >= MIN_COMPONENT_PX:
            boxes.append((x1, y1, x2, y2))
    return boxes


def iou(a, b) -> float:
    iw = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    ih = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = iw * ih
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def main(shot: str, page_file: str) -> int:
    page = next(p for p in TRUTH["pages"] if p["file"] == page_file)
    gts = [(b["x"], b["y"], b["x"] + b["w"], b["y"] + b["h"]) for b in page.get("bubbles", [])]

    frame = np.asarray(Image.open(shot).convert("RGB")).astype(int)
    px1, py1, px2, py2 = page_extent(frame)
    pw, ph = px2 - px1 + 1, py2 - py1 + 1
    dets = [
        ((x1 - px1) / pw, (y1 - py1) / ph, (x2 - px1) / pw, (y2 - py1) / ph)
        for (x1, y1, x2, y2) in components(magenta_mask(frame))
    ]

    unmatched_gt, unmatched_det = set(range(len(gts))), set(range(len(dets)))
    pairs = sorted(((iou(g, d), gi, di) for gi, g in enumerate(gts) for di, d in enumerate(dets)), reverse=True)
    matches = []
    for s, gi, di in pairs:
        if s < MATCH_IOU or gi not in unmatched_gt or di not in unmatched_det:
            continue
        matches.append((gi, di, s))
        unmatched_gt.discard(gi)
        unmatched_det.discard(di)

    contained = sum(
        1 for gi, di, _ in matches
        if dets[di][0] >= gts[gi][0] - 0.01 and dets[di][1] >= gts[gi][1] - 0.01
        and dets[di][2] <= gts[gi][2] + 0.01 and dets[di][3] <= gts[gi][3] + 0.01
    )
    print(f"{page_file}: page on screen x{px1}..{px2} y{py1}..{py2}")
    print(f"  bubbles exist={len(gts)} detected={len(dets)} matched={len(matches)} "
          f"fp={len(unmatched_det)} fn={len(unmatched_gt)}  inside-balloon={contained}/{len(matches)}")
    print(f"  IoU per match: {[round(s, 3) for _, _, s in matches]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1], sys.argv[2]))
