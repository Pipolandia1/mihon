#!/usr/bin/env python3
"""Generate synthetic manga pages with known bubble coordinates.

Every later Bubble Zoom phase is verified against these pages: because the exact
normalized position of every bubble is known up front, a misaligned overlay or a
wrong reading order is measurable rather than a matter of opinion.

Writes:
    tools/test-pages/<name>.png     the pages themselves
    tools/test-pages/ground-truth.json   every bubble's normalized box plus the
                                         correct LTR and RTL reading order

Run:  python tools/make-test-pages.py
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# --- page geometry -----------------------------------------------------------

PAGE_W = 1600
PAGE_H = 2400

BACKGROUND = "white"
INK = "black"

PANEL_BORDER_WIDTH = 6
BUBBLE_OUTLINE_WIDTH = 5
BUBBLE_TEXT_PADDING = 18

# Bubbles whose centres are within this fraction of the page height belong to the
# same horizontal band, and are read left-to-right (LTR) or right-to-left (RTL)
# within it. This mirrors the ordering rule in docs/BUBBLE_ZOOM_SPEC.md section 8.
BAND_TOLERANCE = 0.05

LOREM = (
    "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod "
    "tempor incididunt ut labore et dolore magna aliqua ut enim ad minim veniam "
    "quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo"
).split()


# --- model -------------------------------------------------------------------


@dataclass(frozen=True)
class Bubble:
    """A speech bubble in normalized page coordinates (0..1)."""

    x: float
    y: float
    w: float
    h: float
    shape: str = "round"  # "round" or "ellipse"
    words: int = 8

    @property
    def cx(self) -> float:
        return self.x + self.w / 2

    @property
    def cy(self) -> float:
        return self.y + self.h / 2

    def pixels(self) -> tuple[int, int, int, int]:
        left = round(self.x * PAGE_W)
        top = round(self.y * PAGE_H)
        right = round((self.x + self.w) * PAGE_W)
        bottom = round((self.y + self.h) * PAGE_H)
        return left, top, right, bottom


@dataclass(frozen=True)
class Panel:
    """A panel border in normalized page coordinates (0..1)."""

    x: float
    y: float
    w: float
    h: float

    @property
    def cx(self) -> float:
        return self.x + self.w / 2

    @property
    def cy(self) -> float:
        return self.y + self.h / 2

    def pixels(self) -> tuple[int, int, int, int]:
        left = round(self.x * PAGE_W)
        top = round(self.y * PAGE_H)
        right = round((self.x + self.w) * PAGE_W)
        bottom = round((self.y + self.h) * PAGE_H)
        return left, top, right, bottom


@dataclass
class Page:
    name: str
    purpose: str
    panels: list[Panel] = field(default_factory=list)
    bubbles: list[Bubble] = field(default_factory=list)


# --- reading order -----------------------------------------------------------


def band_order(items: list, indices: list[int], rtl: bool) -> list[int]:
    """Order indices by horizontal band, then left-to-right or right-to-left.

    Items are grouped into bands by their centre y, bands run top to bottom, and
    within a band items run in reading direction. This is the rule in section 8
    of docs/BUBBLE_ZOOM_SPEC.md, applied both to panels and to bubbles.
    """
    if not indices:
        return []

    by_y = sorted(indices, key=lambda i: items[i].cy)

    bands: list[list[int]] = []
    for i in by_y:
        if bands and abs(items[i].cy - items[bands[-1][0]].cy) <= BAND_TOLERANCE:
            bands[-1].append(i)
        else:
            bands.append([i])

    order: list[int] = []
    for band in bands:
        order.extend(sorted(band, key=lambda i: items[i].cx, reverse=rtl))
    return order


def reading_order_flat(bubbles: list[Bubble], rtl: bool) -> list[int]:
    """The no-panel fallback: the band rule applied straight to the bubbles."""
    return band_order(bubbles, list(range(len(bubbles))), rtl)


def _overlap_area(b: Bubble, p: Panel) -> float:
    dx = max(0.0, min(b.x + b.w, p.x + p.w) - max(b.x, p.x))
    dy = max(0.0, min(b.y + b.h, p.y + p.h) - max(b.y, p.y))
    return dx * dy


def reading_order_panels(bubbles: list[Bubble], panels: list[Panel], rtl: bool) -> list[int]:
    """Panel-aware order: panels in reading order, bubbles within each panel.

    Each bubble goes to the panel it overlaps most; ties and non-overlapping
    bubbles go to the nearest panel by centre distance. Bubbles that match no
    panel at all are appended at the end in the same band order.
    """
    if not bubbles:
        return []
    if not panels:
        return reading_order_flat(bubbles, rtl)

    assigned: dict[int, list[int]] = {p: [] for p in range(len(panels))}
    orphans: list[int] = []

    for i, bubble in enumerate(bubbles):
        areas = [_overlap_area(bubble, p) for p in panels]
        best = max(range(len(panels)), key=lambda p: areas[p])
        if areas[best] > 0:
            assigned[best].append(i)
        else:
            nearest = min(
                range(len(panels)),
                key=lambda p: (bubble.cx - panels[p].cx) ** 2 + (bubble.cy - panels[p].cy) ** 2,
            )
            # A bubble that touches no panel is only pulled into the nearest one
            # when its centre actually falls inside it; otherwise it is an orphan.
            p = panels[nearest]
            if p.x <= bubble.cx <= p.x + p.w and p.y <= bubble.cy <= p.y + p.h:
                assigned[nearest].append(i)
            else:
                orphans.append(i)

    order: list[int] = []
    for p in band_order(panels, list(range(len(panels))), rtl):
        order.extend(band_order(bubbles, assigned[p], rtl))
    order.extend(band_order(bubbles, orphans, rtl))
    return order


# --- drawing -----------------------------------------------------------------


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for candidate in ("arial.ttf", "DejaVuSans.ttf", "segoeui.ttf"):
        try:
            return ImageFont.truetype(candidate, size)
        except OSError:
            continue
    return ImageFont.load_default()


def wrap_words(draw: ImageDraw.ImageDraw, words: list[str], font, max_width: int) -> list[str]:
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if draw.textlength(candidate, font=font) <= max_width or not current:
            current = candidate
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def draw_bubble(draw: ImageDraw.ImageDraw, bubble: Bubble) -> None:
    left, top, right, bottom = bubble.pixels()

    if bubble.shape == "ellipse":
        draw.ellipse(
            (left, top, right, bottom),
            fill=BACKGROUND,
            outline=INK,
            width=BUBBLE_OUTLINE_WIDTH,
        )
    else:
        radius = max(12, min(right - left, bottom - top) // 5)
        draw.rounded_rectangle(
            (left, top, right, bottom),
            radius=radius,
            fill=BACKGROUND,
            outline=INK,
            width=BUBBLE_OUTLINE_WIDTH,
        )

    # An ellipse's usable area is narrower than its bounding box, so inset the
    # text further or it spills past the outline.
    pad_x = BUBBLE_TEXT_PADDING
    pad_y = BUBBLE_TEXT_PADDING
    if bubble.shape == "ellipse":
        pad_x += round((right - left) * 0.15)
        pad_y += round((bottom - top) * 0.10)

    inner_w = (right - left) - 2 * pad_x
    inner_h = (bottom - top) - 2 * pad_y
    if inner_w <= 0 or inner_h <= 0:
        return

    words = [LOREM[i % len(LOREM)] for i in range(bubble.words)]

    # Shrink the type until the wrapped text fits inside the bubble.
    for size in range(44, 13, -2):
        font = load_font(size)
        lines = wrap_words(draw, words, font, inner_w)
        line_h = size + 6
        if len(lines) * line_h <= inner_h:
            break
    else:
        return

    text_h = len(lines) * line_h
    y = top + (bottom - top - text_h) // 2
    for line in lines:
        line_w = draw.textlength(line, font=font)
        x = left + ((right - left) - line_w) // 2
        draw.text((x, y), line, fill=INK, font=font)
        y += line_h


def render(page: Page, out_dir: Path) -> Path:
    image = Image.new("RGB", (PAGE_W, PAGE_H), BACKGROUND)
    draw = ImageDraw.Draw(image)

    for panel in page.panels:
        draw.rectangle(panel.pixels(), outline=INK, width=PANEL_BORDER_WIDTH)

    for bubble in page.bubbles:
        draw_bubble(draw, bubble)

    path = out_dir / f"{page.name}.png"
    image.save(path)
    return path


# --- the pages ---------------------------------------------------------------

FULL_PANEL = Panel(0.03, 0.02, 0.94, 0.96)


def build_pages() -> list[Page]:
    pages: list[Page] = []

    # 1. The three boxes phase P3 hardcodes. These exact numbers are the contract
    #    between this generator and that phase's debug overlay.
    pages.append(
        Page(
            name="01-three-bubbles",
            purpose=(
                "The three boxes hardcoded by phase P3. Overlay alignment is measured "
                "against these exact coordinates."
            ),
            panels=[FULL_PANEL],
            bubbles=[
                Bubble(0.62, 0.08, 0.23, 0.14, words=9),
                Bubble(0.15, 0.31, 0.28, 0.16, shape="ellipse", words=12),
                Bubble(0.58, 0.67, 0.26, 0.15, words=10),
            ],
        )
    )

    # 2. A 2x2 panel grid, two bubbles per panel: exercises panel-aware ordering.
    grid_bubbles: list[Bubble] = []
    for row in range(2):
        for col in range(2):
            px = 0.04 + col * 0.48
            py = 0.03 + row * 0.48
            grid_bubbles.append(Bubble(px + 0.03, py + 0.05, 0.18, 0.11, words=7))
            grid_bubbles.append(
                Bubble(px + 0.23, py + 0.26, 0.18, 0.11, shape="ellipse", words=8)
            )
    pages.append(
        Page(
            name="02-panel-grid",
            purpose="2x2 panels with two bubbles each, for panel-aware reading order.",
            panels=[
                Panel(0.04 + col * 0.48, 0.03 + row * 0.48, 0.44, 0.44)
                for row in range(2)
                for col in range(2)
            ],
            bubbles=grid_bubbles,
        )
    )

    # 3. Two bubbles in one band plus one below. The first two swap between LTR
    #    and RTL; the third is last in both. This is the ordering smoke test.
    pages.append(
        Page(
            name="03-side-by-side",
            purpose=(
                "Two side-by-side bubbles in one band plus one below. Bubbles 0 and 1 "
                "swap between LTR and RTL; bubble 2 is last in both."
            ),
            panels=[Panel(0.04, 0.03, 0.92, 0.50), Panel(0.04, 0.55, 0.92, 0.42)],
            bubbles=[
                Bubble(0.09, 0.12, 0.34, 0.20, words=14),  # left of the top band
                Bubble(0.57, 0.12, 0.34, 0.20, words=14),  # right of the top band
                Bubble(0.31, 0.64, 0.38, 0.22, shape="ellipse", words=16),
            ],
        )
    )

    # 4. No bubbles at all: Bubble Zoom must fall through to normal page nav.
    pages.append(
        Page(
            name="04-no-bubbles",
            purpose="A page with no bubbles. Navigation must fall through to normal paging.",
            panels=[
                Panel(0.04, 0.03, 0.92, 0.30),
                Panel(0.04, 0.35, 0.92, 0.30),
                Panel(0.04, 0.67, 0.92, 0.30),
            ],
            bubbles=[],
        )
    )

    # 5. A bubble in each corner, flush with the page edge: catches clamping bugs
    #    in the zoom target and off-by-one errors in coordinate conversion.
    pages.append(
        Page(
            name="05-corners",
            purpose=(
                "One bubble flush against each page corner. Catches clamping bugs in the "
                "zoom target and coordinate-conversion off-by-ones."
            ),
            panels=[FULL_PANEL],
            bubbles=[
                Bubble(0.00, 0.00, 0.22, 0.12, words=6),
                Bubble(0.78, 0.00, 0.22, 0.12, words=6),
                Bubble(0.00, 0.88, 0.22, 0.12, words=6),
                Bubble(0.78, 0.88, 0.22, 0.12, words=6),
            ],
        )
    )

    return pages


# --- entry point -------------------------------------------------------------


def main() -> None:
    out_dir = Path(__file__).resolve().parent / "test-pages"
    out_dir.mkdir(parents=True, exist_ok=True)

    pages = build_pages()
    truth = {
        "pageWidth": PAGE_W,
        "pageHeight": PAGE_H,
        "bandTolerance": BAND_TOLERANCE,
        "pages": [],
    }

    for index, page in enumerate(pages):
        path = render(page, out_dir)
        truth["pages"].append(
            {
                "index": index,
                "file": path.name,
                "purpose": page.purpose,
                "panels": [
                    {"x": p.x, "y": p.y, "w": p.w, "h": p.h} for p in page.panels
                ],
                "bubbles": [
                    {"x": b.x, "y": b.y, "w": b.w, "h": b.h, "shape": b.shape}
                    for b in page.bubbles
                ],
                # Panel-aware order: what BubbleOrderResolver must produce when the
                # detector reports panels (spec section 8).
                "ltrOrder": reading_order_panels(page.bubbles, page.panels, rtl=False),
                "rtlOrder": reading_order_panels(page.bubbles, page.panels, rtl=True),
                # The no-panel fallback: band rule applied straight to the bubbles.
                "ltrOrderNoPanels": reading_order_flat(page.bubbles, rtl=False),
                "rtlOrderNoPanels": reading_order_flat(page.bubbles, rtl=True),
            }
        )
        print(f"wrote {path.name}  ({len(page.bubbles)} bubbles)")

    truth_path = out_dir / "ground-truth.json"
    truth_path.write_text(json.dumps(truth, indent=2), encoding="utf-8")
    print(f"wrote {truth_path.name}")


if __name__ == "__main__":
    main()
