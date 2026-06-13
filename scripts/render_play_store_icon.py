#!/usr/bin/env python3
"""Render the Play Store listing icon (512x512, opaque) from the app's
adaptive-icon vector drawables.

The Play Console "App icon" is a separate 512x512 PNG you upload by hand --
it is NOT extracted from the APK's mipmaps. This script flattens the same
adaptive-icon sources the launcher ships (white background + foreground glyph)
into that upload asset so the store icon stays in sync with the app icon and,
crucially, has a *solid white* background instead of transparency (transparent
store icons look broken on the dark-theme store listing).

Output: play/icon-512.png  (RGB, no alpha -> no transparency)

Rerun (or use `make play-icon`) after editing the icon XML.
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
BG_SRC = REPO_ROOT / "app/src/main/res/drawable/ic_launcher_background.xml"
FG_SRC = REPO_ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
DST = REPO_ROOT / "play/icon-512.png"

SIZE = 512          # Play Store requires exactly 512x512.
SUPERSAMPLE = 4     # Render at 4x then downsample for clean anti-aliased edges.

# Adaptive icons are authored on a 108x108dp canvas, but launchers mask away the
# outer 18dp on every side and only ever show the central 72x72dp "safe zone".
# Cropping to that zone makes the flattened store icon match the on-device size
# of the glyph rather than looking small with a wide white border.
VIEWPORT = 108.0
SAFE_INSET = 18.0
SAFE_SIZE = VIEWPORT - 2 * SAFE_INSET  # 72.0

BLACK = (0, 0, 0, 255)
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def attr(el, name: str, default=None):
    """Read an android:<name> attribute. ElementTree expands the prefix to the
    full namespace URI, so it must be looked up by URI, not by `android:name`."""
    return el.get(f"{{{ANDROID_NS}}}{name}", default)


def path_points(d: str):
    """Walk the absolute M/L/H/V/Z commands used by these drawables and return
    the polygon vertices. (The glyph paths are not all rectangles -- the "T" is
    an 8-vertex polygon -- so taking a bounding box would fill it as a block.)"""
    tokens = re.findall(r"[MLHVZ]|-?\d+(?:\.\d+)?", d, re.IGNORECASE)
    pts = []
    cx = cy = 0.0
    cmd = None
    i = 0
    while i < len(tokens):
        tk = tokens[i]
        if tk.upper() in "MLHVZ":
            cmd = tk.upper()
            i += 1
            continue
        if cmd in ("M", "L"):
            cx, cy = float(tokens[i]), float(tokens[i + 1])
            i += 2
        elif cmd == "H":
            cx = float(tokens[i])
            i += 1
        elif cmd == "V":
            cy = float(tokens[i])
            i += 1
        else:
            i += 1
            continue
        pts.append((cx, cy))
    return pts


def background_color(src: Path):
    """The background drawable is a single solid-fill rect; return its color."""
    tree = ET.parse(src)
    for path in tree.iter("path"):
        fill = attr(path, "fillColor")
        if fill and fill != "#00000000":
            # #AARRGGBB or #RRGGBB -> opaque RGBA tuple.
            h = fill.lstrip("#")
            if len(h) == 8:
                h = h[2:]  # drop alpha; the store icon is forced opaque anyway
            r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
            return (r, g, b, 255)
    return (255, 255, 255, 255)


def render() -> None:
    hi = SIZE * SUPERSAMPLE
    scale = hi / SAFE_SIZE  # dp -> hi-res px, within the safe zone

    def sx(v: float) -> float:
        return (v - SAFE_INSET) * scale

    bg = background_color(BG_SRC)
    img = Image.new("RGBA", (hi, hi), bg)
    draw = ImageDraw.Draw(img)

    tree = ET.parse(FG_SRC)
    for path in tree.iter("path"):
        data = attr(path, "pathData", "")
        fill = attr(path, "fillColor", "#00000000")
        stroke = attr(path, "strokeColor")
        stroke_w = float(attr(path, "strokeWidth", "0"))

        fill_rgba = None if fill == "#00000000" else BLACK
        stroke_rgba = BLACK if stroke and stroke != "#00000000" else None
        sw = max(1, round(stroke_w * scale)) if stroke_rgba else 0

        pts = [(sx(x), sx(y)) for x, y in path_points(data)]
        if not pts:
            print(f"  skipping path (can't parse): {data[:60]}", file=sys.stderr)
            continue

        if fill_rgba and len(pts) >= 3:
            draw.polygon(pts, fill=fill_rgba)
        if stroke_rgba and len(pts) >= 2:
            draw.line(pts + [pts[0]], fill=stroke_rgba, width=sw, joint="curve")

    # Downsample for anti-aliasing, then drop alpha so the upload is fully
    # opaque (no transparency anywhere -> no dark-mode show-through).
    out = img.resize((SIZE, SIZE), Image.LANCZOS).convert("RGB")
    DST.parent.mkdir(parents=True, exist_ok=True)
    out.save(DST)
    print(f"Wrote {DST.relative_to(REPO_ROOT)}  ({SIZE}x{SIZE}px, opaque RGB)")


if __name__ == "__main__":
    render()
