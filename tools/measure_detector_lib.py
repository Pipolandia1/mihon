"""Shared pieces of measure-detector-on-device.py."""
from __future__ import annotations
import numpy as np

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


