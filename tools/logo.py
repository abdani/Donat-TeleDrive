"""Pre-render the logo from docs/images/logo.png at exact pixel sizes (premultiplied Lanczos, optimized PNG).
usage (from the repo root): python -I tools/logo.py [docs/images/logo.png] [src/jvmMain/resources]"""
import sys, os
from PIL import Image

src = sys.argv[1] if len(sys.argv) > 1 else "docs/images/logo.png"
out = sys.argv[2] if len(sys.argv) > 2 else "src/jvmMain/resources"
im = Image.open(src).convert("RGBA")
im = im.crop(im.getchannel("A").getbbox())  # tight to the artwork


def fit(w, h, margin):
    """Artwork centered on a transparent w x h canvas, `margin` = fraction of the shorter side kept free."""
    m = round(min(w, h) * margin)
    s = min((w - 2 * m) / im.width, (h - 2 * m) / im.height)
    size = (max(1, round(im.width * s)), max(1, round(im.height * s)))
    art = im.convert("RGBa").resize(size, Image.LANCZOS).convert("RGBA")  # premultiplied: no dark fringes
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    canvas.alpha_composite(art, ((w - size[0]) // 2, (h - size[1]) // 2))
    return canvas


for f in os.listdir(out):
    if f.startswith("logo-") and f.endswith(".png"):
        os.remove(os.path.join(out, f))
# Header: 144 x 96 dp at 100/125/150/175/200 % display scale, named by pixel width.
for w in (144, 180, 216, 252, 288):
    fit(w, w * 2 // 3, 0.03).save(os.path.join(out, f"logo-{w}.png"), optimize=True)
# Window / taskbar / tray icons (square).
for s in (16, 20, 24, 32, 40, 48, 64, 256):
    fit(s, s, 0.01).save(os.path.join(out, f"icon-{s}.png"), optimize=True)
# Exe / installer / shortcut icon: one .ico holding every size (Windows picks the exact one).
os.makedirs("packaging", exist_ok=True)
sizes = (16, 20, 24, 32, 40, 48, 64, 256)
fit(256, 256, 0.01).save("packaging/icon.ico", sizes=[(s, s) for s in sizes], append_images=[fit(s, s, 0.01) for s in sizes[:-1]])
total = sum(os.path.getsize(os.path.join(out, f)) for f in os.listdir(out) if f.endswith(".png"))
print("written; total", total, "bytes")
