#!/usr/bin/env python3
"""Render ic_launcher_foreground.xml as a PNG preview.

Parses the vector drawable path data manually rather than going through
the Android toolchain. Rerun (or use `make previews`) after editing the XML.

Output: app/src/main/res/drawable/ic_launcher_foreground_preview.png
"""

import sys
from pathlib import Path
from xml.etree import ElementTree as ET

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("Pillow is required: pip install pillow")

# Shared, correct path parsing lives in the store-icon generator: it reads
# attributes by their full namespace URI (ElementTree expands the `android:`
# prefix) and walks the M/H/V/Z commands so non-rectangular glyphs like the "T"
# fill as their outline rather than their bounding box.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from render_play_store_icon import attr, path_points  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC = REPO_ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
DST = SRC.with_name("ic_launcher_foreground_preview.png")

PREVIEW_SIZE = 216  # pixels; viewport is 108x108, so 2× scale
SUPERSAMPLE = 4     # render large then downsample for clean anti-aliased edges
BG = (255, 255, 255, 255)
FG = (0, 0, 0, 255)


def render(src: Path, dst: Path, size: int) -> None:
    tree = ET.parse(src)
    root = tree.getroot()
    vw = float(attr(root, "viewportWidth", "108"))
    vh = float(attr(root, "viewportHeight", "108"))

    hi = size * SUPERSAMPLE
    scale = hi / max(vw, vh)

    img = Image.new("RGBA", (hi, hi), BG)
    draw = ImageDraw.Draw(img)

    for path in tree.iter("path"):
        data = attr(path, "pathData", "")
        fill = attr(path, "fillColor", "#00000000")
        stroke = attr(path, "strokeColor")
        stroke_w = float(attr(path, "strokeWidth", "0"))

        fill_rgba = None if fill == "#00000000" else FG
        stroke_rgba = FG if stroke and stroke != "#00000000" else None
        sw = max(1, round(stroke_w * scale)) if stroke_rgba else 0

        pts = [(x * scale, y * scale) for x, y in path_points(data)]
        if not pts:
            print(f"  skipping path (can't parse): {data[:60]}", file=sys.stderr)
            continue

        if fill_rgba and len(pts) >= 3:
            draw.polygon(pts, fill=fill_rgba)
        if stroke_rgba and len(pts) >= 2:
            draw.line(pts + [pts[0]], fill=stroke_rgba, width=sw, joint="curve")

    out = img.resize((size, size), Image.LANCZOS)
    out.save(dst)
    print(f"Wrote {dst.relative_to(REPO_ROOT)}  ({size}×{size}px)")


if __name__ == "__main__":
    render(SRC, DST, PREVIEW_SIZE)
