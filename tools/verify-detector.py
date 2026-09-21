#!/usr/bin/env python3
"""Confirm an ONNX bubble detector's tensor layout against pages whose answers are known.

The phase P9 brief forbids guessing the output layout. This is how it is not guessed:
the synthetic test pages carry every bubble's exact normalized box in ground-truth.json,
so a decoding that is right lands its boxes on those bubbles with high IoU, and a decoding
that is wrong — xywh read as xyxy, normalized read as pixels, a transposed axis — lands
them nowhere. The pages are the oracle; the reading of the tensor is the hypothesis.

It also answers, per model and per input size, the questions the brief asks for the
device run: how many bubbles exist, how many were found, the IoU of each match, false
positives and negatives, and inference time (CPU, this PC — a proxy for ordering only).

Run:  python tools/verify-detector.py tools/models/yolo26n.onnx [more.onnx ...]
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
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


def evaluate(session: ort.InferenceSession, size: int, bgr: bool) -> dict:
    inp = session.get_inputs()[0]
    out_name = session.get_outputs()[0].name
    totals = {"gt": 0, "det": 0, "tp": 0, "fp": 0, "fn": 0, "ious": [], "ms": []}
    per_page = []

    for page in TRUTH["pages"]:
        gts = [(b["x"], b["y"], b["x"] + b["w"], b["y"] + b["h"]) for b in page.get("bubbles", [])]
        img = Image.open(PAGES / page["file"]).convert("RGB")
        w, h = img.size
        tensor, scale, padx, pady = letterbox(img, size)
        if bgr:
            tensor = tensor[:, ::-1, :, :].copy()

        t0 = time.perf_counter()
        raw = session.run([out_name], {inp.name: tensor})[0]
        ms = (time.perf_counter() - t0) * 1000

        rows = raw[0] if raw.ndim == 3 else raw
        dets = decode_xyxy_pixels(rows, scale, padx, pady, w, h)

        # Greedy matching, best IoU first.
        unmatched_gt = set(range(len(gts)))
        unmatched_det = set(range(len(dets)))
        pairs = sorted(
            ((iou(g, d[:4]), gi, di) for gi, g in enumerate(gts) for di, d in enumerate(dets)),
            reverse=True,
        )
        matches = []
        for score, gi, di in pairs:
            if score < MATCH_IOU or gi not in unmatched_gt or di not in unmatched_det:
                continue
            matches.append((gi, di, score))
            unmatched_gt.discard(gi)
            unmatched_det.discard(di)

        per_page.append({
            "file": page["file"], "gt": len(gts), "det": len(dets), "tp": len(matches),
            "fp": len(unmatched_det), "fn": len(unmatched_gt),
            "ious": [round(s, 3) for _, _, s in matches], "ms": round(ms, 1),
            "raw_head": [[round(float(v), 1) for v in r] for r in rows[:3]],
        })
        totals["gt"] += len(gts); totals["det"] += len(dets); totals["tp"] += len(matches)
        totals["fp"] += len(unmatched_det); totals["fn"] += len(unmatched_gt)
        totals["ious"] += [s for _, _, s in matches]; totals["ms"].append(ms)

    return {"size": size, "bgr": bgr, "pages": per_page, "totals": totals}


def main(paths: list[str]) -> int:
    for p in map(Path, paths):
        describe(p)
        session = ort.InferenceSession(str(p), providers=["CPUExecutionProvider"])
        shape = session.get_inputs()[0].shape
        print(f"  session input shape: {shape}  (a fixed int means only that size will run)")
        for size in INPUT_SIZES:
            for bgr in (False, True):
                try:
                    r = evaluate(session, size, bgr)
                except Exception as e:  # noqa: BLE001 - a fixed-shape model simply refuses other sizes
                    print(f"\n  size={size} bgr={bgr}: did not run - {type(e).__name__}: {str(e)[:120]}")
                    continue
                t = r["totals"]
                mean_iou = sum(t["ious"]) / len(t["ious"]) if t["ious"] else 0.0
                print(f"\n  size={size} {'BGR' if bgr else 'RGB'}: gt={t['gt']} det={t['det']} "
                      f"tp={t['tp']} fp={t['fp']} fn={t['fn']} meanIoU={mean_iou:.3f} "
                      f"ms/page={sum(t['ms'])/len(t['ms']):.0f}")
                for pg in r["pages"]:
                    print(f"     {pg['file']:24} gt={pg['gt']} det={pg['det']} tp={pg['tp']} "
                          f"fp={pg['fp']} fn={pg['fn']} iou={pg['ious']} {pg['ms']}ms")
                if not bgr and size == INPUT_SIZES[0]:
                    print("     first raw rows (are these 0..S pixels? xyxy with x2>x1? score in col 4?):")
                    for row in r["pages"][0]["raw_head"]:
                        print("       ", row)
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1:]))
