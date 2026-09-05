"""Generate the unified NiumaStatusBar brand icon set from a single design.

Outputs (under src-tauri/icons/):
  - icon.png                  1024x1024 master
  - 32x32.png, 64x64.png
  - 128x128.png, 128x128@2x.png (256x256)
  - Square*Logo.png + StoreLogo.png  (Windows store / Tauri bundle)
  - icon.ico                  multi-resolution ICO (16/24/32/48/64/128/256)
  - icon.icns                 multi-resolution ICNS (macOS)
  - ios/AppIcon-*.png         iOS bundle
  - tray.png                  32x32 tray icon (white version on transparent)

Also outputs Android adaptive-icon PNGs (foreground + background per density)
under src-tauri/gen/android/app/src/main/res/.

Run with:  python scripts/generate_icons.py
"""

from __future__ import annotations

import os
import struct
import zlib
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ICONS_DIR = ROOT / "src-tauri" / "icons"
ANDROID_DIR = ROOT / "src-tauri" / "gen" / "android" / "app" / "src" / "main" / "res"

# Brand palette: cyan -> violet -> pink (matches cyberpunk theme)
GRAD_STOPS = [
    (0.00, (0x22, 0xd3, 0xee)),  # #22d3ee
    (0.55, (0x8b, 0x5c, 0xf6)),  # #8b5cf6
    (1.00, (0xec, 0x48, 0x99)),  # #ec4899
]


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def sample_gradient(t: float) -> tuple[int, int, int]:
    for i in range(len(GRAD_STOPS) - 1):
        t0, c0 = GRAD_STOPS[i]
        t1, c1 = GRAD_STOPS[i + 1]
        if t0 <= t <= t1:
            local = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(int(lerp(c0[k], c1[k], local)) for k in range(3))  # type: ignore[return-value]
    return GRAD_STOPS[-1][1]


def draw_rounded_rect(im: Image.Image, radius: int, fill) -> None:
    ImageDraw.Draw(im).rounded_rectangle(
        ((0, 0), (im.width - 1, im.height - 1)),
        radius=radius,
        fill=fill,
    )


def make_master(size: int = 1024) -> Image.Image:
    """Render the master brand icon (gradient square + pulse line)."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()

    radius = round(size * 224 / 1024)
    # Gradient fill across diagonal (top-left cyan -> bottom-right pink)
    for y in range(size):
        for x in range(size):
            # Skip pixels outside the rounded square
            cx = min(x, size - 1 - x)
            cy = min(y, size - 1 - y)
            if cx < radius and cy < radius:
                dx, dy = radius - cx, radius - cy
                if dx * dx + dy * dy > radius * radius:
                    continue
            t = (x + y) / (2 * (size - 1))  # 0..1 along the diagonal
            r, g, b = sample_gradient(max(0.0, min(1.0, t)))
            px[x, y] = (r, g, b, 255)

    draw = ImageDraw.Draw(img)
    # Pulse line geometry (master 1024 coords). Scaled below.
    line_color = (255, 255, 255, 245)
    pts = [
        (140, 560), (380, 560), (470, 560), (520, 720),
        (580, 280), (640, 720), (690, 560), (780, 560), (884, 560),
    ]
    scaled = [(x * size / 1024, y * size / 1024) for x, y in pts]
    stroke = round(size * 72 / 1024)
    dot_r = round(size * 36 / 1024)
    draw.line(scaled, fill=line_color, width=stroke, joint="curve")
    for x, y in (scaled[0], scaled[-1]):
        draw.ellipse(
            (x - dot_r, y - dot_r, x + dot_r, y + dot_r),
            fill=line_color,
        )
    return img


def make_android_foreground(size: int = 432) -> Image.Image:
    """Foreground for Android adaptive icon (108dp safe area = 432px @xxxhdpi).

    The pulse line and dots only; background is set by ic_launcher_background.
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    # Center the pulse at size/2
    cx = size / 2
    cy = size / 2
    s = size / 1024  # scale factor from master coordinates
    base_pts = [
        (140, 560), (380, 560), (470, 560), (520, 720),
        (580, 280), (640, 720), (690, 560), (780, 560), (884, 560),
    ]
    pts = [((x - 512) * s + cx, (y - 512) * s + cy) for x, y in base_pts]
    stroke = round(size * 72 / 1024)
    dot_r = round(size * 36 / 1024)
    draw.line(pts, fill=(255, 255, 255, 250), width=stroke, joint="curve")
    for x, y in (pts[0], pts[-1]):
        draw.ellipse(
            (x - dot_r, y - dot_r, x + dot_r, y + dot_r),
            fill=(255, 255, 255, 250),
        )
    return img


def make_android_background(size: int = 432) -> Image.Image:
    """Solid gradient background (no logo) for adaptive icon."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            r, g, b = sample_gradient(max(0.0, min(1.0, t)))
            px[x, y] = (r, g, b, 255)
    return img


def make_round_icon(size: int) -> Image.Image:
    """Build a round-clipped composite (legacy Android <26 round launcher icon)."""
    master = make_master(size)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    master.putalpha(mask)
    return master


def write_png(im: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if im.mode != "RGBA":
        im = im.convert("RGBA")
    im.save(path, "PNG", optimize=True)
    print(f"  -> {path.relative_to(ROOT)}  ({im.width}x{im.height})")


def write_ico(images: list[Image.Image], path: Path) -> None:
    """Write a multi-resolution ICO file."""
    sizes = [(im.width, im.height) for im in images]
    header = struct.pack("<HHH", 0, 1, len(images))
    offset = 6 + 16 * len(images)
    entries = b""
    blobs = b""
    for im, (w, h) in zip(images, sizes):
        png_bytes = im.convert("RGBA").tobytes("raw", "BGRA")  # placeholder
        # Re-encode via PIL to PNG bytes for embedding
        from io import BytesIO
        buf = BytesIO()
        im.save(buf, "PNG", optimize=True)
        data = buf.getvalue()
        # ICO header entry: width, height, ncolors, reserved, planes, bitcount, size, offset
        entries += struct.pack(
            "<BBBBHHII",
            0 if w >= 256 else w,
            0 if h >= 256 else h,
            0, 0, 1, 32, len(data), offset,
        )
        blobs += data
        offset += len(data)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(header + entries + blobs)
    print(f"  -> {path.relative_to(ROOT)}  ({len(images)} sizes)")


def write_icns(images: list[Image.Image], path: Path) -> None:
    """Pack PNG-encoded ICNS (ic09=512, ic08=256, ic07=128, etc.)."""
    size_to_code = {
        16: b"icp4", 32: b"icp5", 64: b"icp6",
        128: b"ic07", 256: b"ic08", 512: b"ic09", 1024: b"ic10",
    }
    from io import BytesIO
    chunks = b""
    for im in images:
        if im.width not in size_to_code:
            continue
        buf = BytesIO()
        im.save(buf, "PNG", optimize=True)
        data = buf.getvalue()
        code = size_to_code[im.width]
        chunks += code + struct.pack(">I", 8 + len(data)) + data
    total = 8 + len(chunks)
    out = b"icns" + struct.pack(">I", total) + chunks
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(out)
    print(f"  -> {path.relative_to(ROOT)}  ({len(images)} sizes)")


def main() -> None:
    print("[1/3] Rendering master icon (1024x1024)...")
    master = make_master(1024)

    print("[2/3] Writing desktop icon variants...")
    write_png(master, ICONS_DIR / "icon.png")
    write_png(master.resize((32, 32), Image.LANCZOS), ICONS_DIR / "32x32.png")
    write_png(master.resize((64, 64), Image.LANCZOS), ICONS_DIR / "64x64.png")
    write_png(master.resize((128, 128), Image.LANCZOS), ICONS_DIR / "128x128.png")
    write_png(master.resize((256, 256), Image.LANCZOS), ICONS_DIR / "128x128@2x.png")
    write_png(master.resize((256, 256), Image.LANCZOS), ICONS_DIR / "Square30x30Logo.png")
    write_png(master.resize((44, 44), Image.LANCZOS), ICONS_DIR / "Square44x44Logo.png")
    write_png(master.resize((71, 71), Image.LANCZOS), ICONS_DIR / "Square71x71Logo.png")
    write_png(master.resize((89, 89), Image.LANCZOS), ICONS_DIR / "Square89x89Logo.png")
    write_png(master.resize((107, 107), Image.LANCZOS), ICONS_DIR / "Square107x107Logo.png")
    write_png(master.resize((142, 142), Image.LANCZOS), ICONS_DIR / "Square142x142Logo.png")
    write_png(master.resize((150, 150), Image.LANCZOS), ICONS_DIR / "Square150x150Logo.png")
    write_png(master.resize((284, 284), Image.LANCZOS), ICONS_DIR / "Square284x284Logo.png")
    write_png(master.resize((310, 310), Image.LANCZOS), ICONS_DIR / "Square310x310Logo.png")
    write_png(master.resize((50, 50), Image.LANCZOS), ICONS_DIR / "StoreLogo.png")

    ios_dir = ICONS_DIR / "ios"
    ios_specs = [
        ("AppIcon-20x20@1x.png", 20),
        ("AppIcon-20x20@2x.png", 40),
        ("AppIcon-20x20@2x-1.png", 40),
        ("AppIcon-20x20@3x.png", 60),
        ("AppIcon-29x29@1x.png", 29),
        ("AppIcon-29x29@2x.png", 58),
        ("AppIcon-29x29@2x-1.png", 58),
        ("AppIcon-29x29@3x.png", 87),
        ("AppIcon-40x40@1x.png", 40),
        ("AppIcon-40x40@2x.png", 80),
        ("AppIcon-40x40@2x-1.png", 80),
        ("AppIcon-40x40@3x.png", 120),
        ("AppIcon-60x60@2x.png", 120),
        ("AppIcon-60x60@3x.png", 180),
        ("AppIcon-76x76@1x.png", 76),
        ("AppIcon-76x76@2x.png", 152),
        ("AppIcon-83.5x83.5@2x.png", 167),
        ("AppIcon-512@2x.png", 1024),
    ]
    for name, sz in ios_specs:
        write_png(master.resize((sz, sz), Image.LANCZOS), ios_dir / name)

    print("  Building multi-resolution icon.ico...")
    ico_sizes = [16, 24, 32, 48, 64, 128, 256]
    write_ico([master.resize((s, s), Image.LANCZOS) for s in ico_sizes], ICONS_DIR / "icon.ico")

    print("  Building multi-resolution icon.icns...")
    icns_sizes = [16, 32, 64, 128, 256, 512, 1024]
    write_icns([master.resize((s, s), Image.LANCZOS) for s in icns_sizes], ICONS_DIR / "icon.icns")

    print("[3/3] Writing Android adaptive-icon PNGs...")
    # Adaptive icon foreground: 432px (xxxhdpi). Inner 66dp safe zone = 264px @xxxhdpi
    # We render the pulse line within the safe zone.
    densities = {
        "mdpi": 108,
        "hdpi": 162,
        "xhdpi": 216,
        "xxhdpi": 324,
        "xxxhdpi": 432,
    }
    bg = make_android_background(432)
    fg = make_android_foreground(432)
    for density, sz in densities.items():
        folder = ANDROID_DIR / f"mipmap-{density}"
        # Foreground (adaptive icon layer): pulse line on transparent
        write_png(fg.resize((sz, sz), Image.LANCZOS), folder / "ic_launcher_foreground.png")
        # Legacy square launcher icon: full square composite (gradient + pulse)
        composite = Image.alpha_composite(bg.resize((sz, sz), Image.LANCZOS),
                                          fg.resize((sz, sz), Image.LANCZOS))
        write_png(composite, folder / "ic_launcher.png")
        # Legacy round launcher icon: full circle composite
        write_png(make_round_icon(sz), folder / "ic_launcher_round.png")

    print("Done.")


if __name__ == "__main__":
    main()