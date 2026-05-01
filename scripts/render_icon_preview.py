#!/usr/bin/env python3
"""Render ic_launcher_foreground.xml as a PNG preview.

Parses the vector drawable path data manually rather than going through
the Android toolchain. Rerun (or use `make previews`) after editing the XML.

Output: app/src/main/res/drawable/ic_launcher_foreground_preview.png
"""

import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("Pillow is required: pip install pillow")

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC = REPO_ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
DST = SRC.with_name("ic_launcher_foreground_preview.png")

PREVIEW_SIZE = 216  # pixels; viewport is 108x108, so 2× scale
SCALE = PREVIEW_SIZE / 108
BG = (255, 255, 255, 255)
FG = (0, 0, 0, 255)


def s(v: float) -> float:
    return v * SCALE


def bbox_from_path(d: str):
    """Return (x1,y1,x2,y2) for simple M…H…V…Z rect paths."""
    nums = [float(t) for t in re.findall(r"[-\d.]+", d)]
    xs = nums[0::2]
    ys = nums[1::2]
    return min(xs), min(ys), max(xs), max(ys)


def render(src: Path, dst: Path, size: int) -> None:
    tree = ET.parse(src)
    vw = float(tree.getroot().get("android:viewportWidth", "108"))
    vh = float(tree.getroot().get("android:viewportHeight", "108"))
    scale = size / max(vw, vh)

    img = Image.new("RGBA", (size, size), BG)
    draw = ImageDraw.Draw(img)

    ns = {"android": "http://schemas.android.com/apk/res/android"}

    for path in tree.iter("path"):
        data = path.get("android:pathData", "")
        fill = path.get("android:fillColor", "#00000000")
        stroke = path.get("android:strokeColor", None)
        stroke_w = float(path.get("android:strokeWidth", "0"))

        fill_rgba = None if fill == "#00000000" else FG
        stroke_rgba = FG if stroke and stroke != "#00000000" else None
        sw = max(1, round(stroke_w * scale)) if stroke_rgba else 0

        try:
            x1, y1, x2, y2 = bbox_from_path(data)
        except Exception:
            print(f"  skipping path (can't parse): {data[:60]}", file=sys.stderr)
            continue

        box = [x1 * scale, y1 * scale, x2 * scale, y2 * scale]
        draw.rectangle(box, fill=fill_rgba, outline=stroke_rgba, width=sw)

    img.save(dst)
    print(f"Wrote {dst.relative_to(REPO_ROOT)}  ({size}×{size}px, {scale}× scale)")


if __name__ == "__main__":
    render(SRC, DST, PREVIEW_SIZE)
