"""Generate Mao app icons from branding/mao_logo_source.png.

The source is the rounded-square yellow logo on a black (or white) background.
Outputs:
  app/src/main/res/drawable-nodpi/mao_logo.png            in-app logo, transparent corners
  app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png  adaptive-icon foreground
  app/src/main/res/drawable/ic_launcher_background.xml     matching yellow gradient

Run from the project root:  python branding/make_icons.py
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "branding" / "mao_logo_source.png"
RES = ROOT / "app" / "src" / "main" / "res"

im = Image.open(SRC).convert("RGBA")
rgb = im.convert("RGB")
w, h = im.size
MARK = (255, 0, 255)
# Flood-fill the flat background from the edges, then make it transparent.
for seed in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1), (w // 2, 0), (w // 2, h - 1), (0, h // 2), (w - 1, h // 2)]:
    ImageDraw.floodfill(rgb, seed, MARK, thresh=60)
px, a = rgb.load(), im.load()
for y in range(h):
    for x in range(w):
        if px[x, y] == MARK:
            a[x, y] = (0, 0, 0, 0)
im = im.crop(im.getbbox())
side = max(im.size)
square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
square.paste(im, ((side - im.width) // 2, (side - im.height) // 2))

(RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
square.resize((512, 512), Image.LANCZOS).save(RES / "drawable-nodpi" / "mao_logo.png", optimize=True)

# Adaptive icon: 108dp canvas, only the centre 72dp is visible. Scaling the rounded
# square to 85% puts its own corners outside the mask and keeps the figure in view.
canvas = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
inner = int(432 * 0.85)
logo = square.resize((inner, inner), Image.LANCZOS)
canvas.paste(logo, ((432 - inner) // 2, (432 - inner) // 2), logo)
(RES / "mipmap-xxxhdpi").mkdir(parents=True, exist_ok=True)
canvas.save(RES / "mipmap-xxxhdpi" / "ic_launcher_foreground.png", optimize=True)

(RES / "drawable" / "ic_launcher_background.xml").write_text(
    """<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="315" android:startColor="#FFFFD23F" android:endColor="#FFF5A300" />
</shape>
""",
    encoding="utf-8",
)
print("icons written from", SRC)
