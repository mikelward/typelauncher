#!/usr/bin/env python3
"""Render launcher icon PNG previews.

Parses the vector drawable path data manually rather than going through
the Android toolchain. Rerun (or use `make previews`) after editing the XML.

Outputs:
- app/src/main/res/drawable/ic_launcher_foreground_preview.png
- build/icon-previews/local_badge_contact_sheet.png
"""

import math
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Pillow is required: pip install pillow")

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC = REPO_ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml"
DST = SRC.with_name("ic_launcher_foreground_preview.png")
CONTACT_SHEET = REPO_ROOT / "build/icon-previews/local_badge_contact_sheet.png"

PREVIEW_SIZE = 216  # pixels; viewport is 108x108, so 2× scale
SCALE = PREVIEW_SIZE / 108
BG = (255, 255, 255, 255)
FG = (0, 0, 0, 255)
LOCAL_BADGE = (255, 193, 7, 255)
LOCAL_BADGE_ALT = (33, 150, 243, 255)
GRID_BG = (245, 245, 245, 255)
LABEL = (38, 38, 38, 255)
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

BADGE_VARIANTS = [
    ("dev", "DEV", LOCAL_BADGE),
    ("construction", "wrench", LOCAL_BADGE),
    ("code", "</>", LOCAL_BADGE),
    ("build", "hammer", LOCAL_BADGE),
    ("factor", "x2", LOCAL_BADGE),
    ("local", "LOCAL", LOCAL_BADGE_ALT),
    ("dirty", "*", (244, 67, 54, 255)),
    ("lab", "LAB", (76, 175, 80, 255)),
]


def s(v: float) -> float:
    return v * SCALE


def android_attr(node, name: str, default=None):
    return node.get(f"{ANDROID_NS}{name}", node.get(f"android:{name}", default))


def points_from_path(d: str):
    """Return polygon points for simple absolute M/H/V/L/Z vector paths."""
    tokens = re.findall(r"[A-Za-z]|[-\d.]+", d.replace(",", " "))
    points = []
    x = y = 0.0
    command = None
    index = 0
    while index < len(tokens):
        token = tokens[index]
        if re.match(r"[A-Za-z]", token):
            command = token
            index += 1
            if command.upper() == "Z":
                break
            continue
        if command == "M" or command == "L":
            x = float(token)
            y = float(tokens[index + 1])
            index += 2
        elif command == "H":
            x = float(token)
            index += 1
        elif command == "V":
            y = float(token)
            index += 1
        else:
            raise ValueError(f"unsupported path command {command}")
        points.append((x, y))
    if len(points) < 2:
        raise ValueError("path has too few points")
    return points


def render_icon(src: Path, size: int) -> Image.Image:
    tree = ET.parse(src)
    vw = float(android_attr(tree.getroot(), "viewportWidth", "108"))
    vh = float(android_attr(tree.getroot(), "viewportHeight", "108"))
    scale = size / max(vw, vh)

    img = Image.new("RGBA", (size, size), BG)
    draw = ImageDraw.Draw(img)

    for path in tree.iter("path"):
        data = android_attr(path, "pathData", "")
        fill = android_attr(path, "fillColor", "#00000000")
        stroke = android_attr(path, "strokeColor", None)
        stroke_w = float(android_attr(path, "strokeWidth", "0"))

        fill_rgba = None if fill == "#00000000" else FG
        stroke_rgba = FG if stroke and stroke != "#00000000" else None
        sw = max(1, round(stroke_w * scale)) if stroke_rgba else 0

        try:
            points = [(x * scale, y * scale) for x, y in points_from_path(data)]
        except Exception:
            print(f"  skipping path (can't parse): {data[:60]}", file=sys.stderr)
            continue

        if fill_rgba:
            draw.polygon(points, fill=fill_rgba)
        if stroke_rgba:
            draw.line(points + [points[0]], fill=stroke_rgba, width=sw, joint="curve")

    return img


def render(src: Path, dst: Path, size: int) -> None:
    img = render_icon(src, size)
    img.save(dst)
    scale = size / 108
    print(f"Wrote {dst.relative_to(REPO_ROOT)}  ({size}x{size}px, {scale}x scale)")


def render_base_icon(size: int) -> Image.Image:
    return render_icon(SRC, size)


def font(size: int, bold: bool = False) -> ImageFont.ImageFont:
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def draw_centered_text(draw: ImageDraw.ImageDraw, box, text: str, fill) -> None:
    text_font = font(22 if len(text) <= 3 else 14, bold=True)
    text_box = draw.textbbox((0, 0), text, font=text_font)
    width = text_box[2] - text_box[0]
    height = text_box[3] - text_box[1]
    x = box[0] + (box[2] - box[0] - width) / 2
    y = box[1] + (box[3] - box[1] - height) / 2 - text_box[1]
    draw.text((x, y), text, fill=fill, font=text_font)


def draw_badge_symbol(draw: ImageDraw.ImageDraw, symbol: str, center, radius: int) -> None:
    cx, cy = center
    if symbol == "wrench":
        draw.line((cx - 9, cy + 8, cx + 7, cy - 8), fill=FG, width=5)
        draw.ellipse((cx + 2, cy - 14, cx + 14, cy - 2), outline=FG, width=4)
        draw.ellipse((cx - 14, cy + 2, cx - 4, cy + 12), fill=FG)
    elif symbol == "hammer":
        draw.line((cx - 8, cy + 10, cx + 6, cy - 4), fill=FG, width=5)
        draw.rounded_rectangle((cx - 4, cy - 14, cx + 14, cy - 5), radius=2, fill=FG)
        draw.rectangle((cx - 13, cy - 10, cx - 2, cy - 5), fill=FG)
    else:
        draw_centered_text(draw, (cx - radius, cy - radius, cx + radius, cy + radius), symbol, FG)


def draw_badged_icon(symbol: str, badge_color) -> Image.Image:
    img = render_base_icon(PREVIEW_SIZE)
    draw = ImageDraw.Draw(img)
    radius = 18
    cx = PREVIEW_SIZE - 54
    cy = PREVIEW_SIZE - 36
    draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=badge_color)
    draw_badge_symbol(draw, symbol, (cx, cy), radius)
    return img


def render_contact_sheet(dst: Path) -> None:
    cell_w = PREVIEW_SIZE
    cell_h = PREVIEW_SIZE + 34
    cols = 4
    rows = math.ceil(len(BADGE_VARIANTS) / cols)
    sheet = Image.new("RGBA", (cols * cell_w, rows * cell_h), GRID_BG)
    for index, (label, symbol, badge_color) in enumerate(BADGE_VARIANTS):
        row, col = divmod(index, cols)
        x = col * cell_w
        y = row * cell_h
        sheet.alpha_composite(draw_badged_icon(symbol, badge_color), (x, y))
        draw = ImageDraw.Draw(sheet)
        label_font = font(18)
        text_box = draw.textbbox((0, 0), label, font=label_font)
        width = text_box[2] - text_box[0]
        draw.text((x + (cell_w - width) / 2, y + PREVIEW_SIZE + 5), label, fill=LABEL, font=label_font)
    dst.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(dst)
    print(f"Wrote {dst.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    render(SRC, DST, PREVIEW_SIZE)
    render_contact_sheet(CONTACT_SHEET)
