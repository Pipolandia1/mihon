#!/usr/bin/env python3
"""Why does the detector agree with the ground truth on COUNT but not on IoU?

verify-detector.py found det == gt on every synthetic page and yet only a third of the
boxes match at IoU >= 0.5. Two explanations look the same in that summary and mean very
different things:

    GEOMETRY   the un-letterbox math is off, so every box is shifted or scaled -> fix the code
    CONTENT    the model boxes the TEXT inside a balloon, not the balloon's outline, so its
               box sits inside ours and is simply smaller -> a padding decision, not a bug

They are told apart per detection: a content box is CONTAINED in the truth box with its
centre near the truth's centre; a geometry error is partly OUTSIDE it. This prints both,
draws truth (green) and detection (red) on each page so a person can look, and dumps page
01's raw rows so the Kotlin decoder can be tested against the model's real numbers.

Run:  python tools/diagnose-detector.py tools/models/yolo26n.onnx 640
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
from verify_detector_lib import PAGES, TRUTH, decode_xyxy_pixels, iou, letterbox  # noqa: E402

OUT = Path(__file__).parent / "shots"


def main(model: str, size: int) -> int:
    session = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    inp, out = session.get_inputs()[0].name, session.get_outputs()[0].name
    OUT.mkdir(exist_ok=True)

    contained = shifted = 0
    fixture = None

    for page in TRUTH["pages"]:
        gts = [(b["x"], b["y"], b["x"] + b["w"], b["y"] + b["h"]) for b in page.get("bubbles", [])]
        img = Image.open(PAGES / page["file"]).convert("RGB")
        w, h = img.size
        tensor, scale, padx, pady = letterbox(img, size)
        raw = session.run([out], {inp: tensor})[0][0]
        dets = decode_xyxy_pixels(raw, scale, padx, pady, w, h)

        if page["file"].startswith("01"):
            fixture = {
                "model": Path(model).name, "inputSize": size,
                "sourceWidth": w, "sourceHeight": h,
                "scale": scale, "padX": padx, "padY": pady,
                "rows": [[float(v) for v in r] for r in raw if r[4] >= 0.25],
                "expectedNormalized": [[float(v) for v in d[:4]] for d in dets],
            }

        print(f"\n{page['file']}  gt={len(gts)} det={len(dets)}")
        draw = ImageDraw.Draw(img)
        for g in gts:
            draw.rectangle([g[0] * w, g[1] * h, g[2] * w, g[3] * h], outline=(0, 200, 0), width=6)
        for d in dets:
            x1, y1, x2, y2, score, _ = d
            draw.rectangle([x1 * w, y1 * h, x2 * w, y2 * h], outline=(220, 0, 0), width=6)
            if not gts:
                print(f"   det score={score:.2f}  (page has no truth: FALSE POSITIVE)")
                continue
            gi = max(range(len(gts)), key=lambda i: iou(gts[i], d[:4]))
            g = gts[gi]
            inside = x1 >= g[0] - 0.005 and y1 >= g[1] - 0.005 and x2 <= g[2] + 0.005 and y2 <= g[3] + 0.005
            cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
            gcx, gcy = (g[0] + g[2]) / 2, (g[1] + g[3]) / 2
            centre_in = g[0] <= cx <= g[2] and g[1] <= cy <= g[3]
            area_ratio = ((x2 - x1) * (y2 - y1)) / ((g[2] - g[0]) * (g[3] - g[1]))
            contained += inside
            shifted += not inside
            print(f"   det score={score:.2f} iou={iou(g, d[:4]):.3f} "
                  f"{'INSIDE truth' if inside else 'partly OUTSIDE'} "
                  f"centreInTruth={centre_in} area={area_ratio:.2f}x "
                  f"centreOffset=({cx-gcx:+.3f},{cy-gcy:+.3f})")
        img.save(OUT / f"p9-diag-{page['file']}")

    print(f"\ncontained in truth: {contained}   partly outside: {shifted}")
    print("-> CONTENT (text region) if nearly all are contained; GEOMETRY if many are outside.")

    fx = OUT / "p9-page01-fixture.json"
    fx.write_text(json.dumps(fixture, indent=2), encoding="utf-8", newline="\n")
    print(f"page-01 raw rows for the Kotlin decoder test: {fx}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 640))
