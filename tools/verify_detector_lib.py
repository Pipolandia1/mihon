"""Shared pieces of verify-detector.py: the letterbox, the decoder and the IoU."""
from __future__ import annotations
import json
from pathlib import Path
import numpy as np
from PIL import Image

PAGES = Path(__file__).parent / "test-pages"
TRUTH = json.loads((PAGES / "ground-truth.json").read_text(encoding="utf-8"))

# What the reader will use, and what the model card recommends.
INPUT_SIZES = (640, 1280)
SCORE_FLOOR = 0.25
MATCH_IOU = 0.5
PAD_VALUE = 114  # ultralytics letterbox fill


def describe(path: Path) -> None:
    """The plan's own inspection script, verbatim in spirit."""
    m = onnx.load(str(path))
    dims = lambda t: [d.dim_value or d.dim_param for d in t.type.tensor_type.shape.dim]
    print(f"--- {path.name} ---")
    print("  inputs :", [(i.name, dims(i)) for i in m.graph.input])
    print("  outputs:", [(o.name, dims(o)) for o in m.graph.output])
    print("  meta   :", {p.key: p.value[:80] for p in m.metadata_props})
    print("  opset  :", [(o.domain or "ai.onnx", o.version) for o in m.opset_import])


def letterbox(img: Image.Image, size: int) -> tuple[np.ndarray, float, float, float]:
    """ultralytics-style: scale to fit, pad to square, image centred. Returns (chw, scale, padx, pady)."""
    w, h = img.size
    scale = size / max(w, h)
    nw, nh = round(w * scale), round(h * scale)
    resized = img.resize((nw, nh), Image.BILINEAR)
    canvas = Image.new("RGB", (size, size), (PAD_VALUE, PAD_VALUE, PAD_VALUE))
    padx, pady = (size - nw) // 2, (size - nh) // 2
    canvas.paste(resized, (padx, pady))
    arr = np.asarray(canvas, dtype=np.float32) / 255.0  # HWC RGB 0..1
    return arr.transpose(2, 0, 1)[None], scale, float(padx), float(pady)


def iou(a, b) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    iw = max(0.0, min(ax2, bx2) - max(ax1, bx1))
    ih = max(0.0, min(ay2, by2) - max(ay1, by1))
    inter = iw * ih
    union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
    return inter / union if union > 0 else 0.0


def decode_xyxy_pixels(rows, scale, padx, pady, w, h):
    """The assumed layout: [x1, y1, x2, y2, score, cls] in letterboxed-input pixels."""
    out = []
    for x1, y1, x2, y2, score, cls in rows:
        if score < SCORE_FLOOR:
            continue
        nx1 = (x1 - padx) / scale / w
        ny1 = (y1 - pady) / scale / h
        nx2 = (x2 - padx) / scale / w
        ny2 = (y2 - pady) / scale / h
        out.append((nx1, ny1, nx2, ny2, float(score), int(cls)))
    return out


