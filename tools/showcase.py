"""README art: frames the raw demo screenshots (tools/raw, made by tools/capture.ps1) as real windows with a soft shadow
on the brand gradient, and builds the hero banner, the theme collage and the phone pair.
usage (from the repo root): python -I tools/showcase.py   → docs/images/*.png"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RAW, OUT = "tools/raw", "docs/images"
FONTS = os.path.join(os.environ.get("WINDIR", "C:/Windows"), "Fonts")


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size)


def gradient(w, h):
    """Deep violet → navy diagonal with a blue and a purple glow (the logo's two colors)."""
    top, bottom = (24, 16, 54), (10, 14, 34)
    bg = Image.new("RGB", (w, h))
    px = bg.load()
    for y in range(h):
        for x in range(0, w):
            t = (x / w * 0.35 + y / h * 0.65)
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom))
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse((-w * 0.15, -h * 0.35, w * 0.45, h * 0.45), fill=(33, 150, 243, 120))
    d.ellipse((w * 0.55, h * 0.45, w * 1.2, h * 1.35), fill=(138, 43, 226, 130))
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) // 5))
    return Image.alpha_composite(bg.convert("RGBA"), glow)


def window(raw_name, scale=1.0):
    """Client area of a demo shot + a drawn Windows title bar in the theme's backdrop color, rounded, with a hairline."""
    im = Image.open(os.path.join(RAW, raw_name)).convert("RGB")
    w, h = im.size
    client = im.crop((8, 31, w - 8, h - 8))
    cw, ch = client.size
    back = client.getpixel((3, 3))
    dark = sum(back) / 3 < 128
    fg = (230, 230, 235) if dark else (30, 30, 35)
    bar = 32
    win = Image.new("RGB", (cw, ch + bar), back)
    win.paste(client, (0, bar))
    d = ImageDraw.Draw(win)
    icon = Image.open("src/jvmMain/resources/icon-24.png").convert("RGBA").resize((18, 18), Image.LANCZOS)
    win.paste(icon, (10, 7), icon)
    d.text((36, 16), "Donat TeleDrive", font=font("segoeui.ttf", 12), fill=fg, anchor="lm")
    glyphs = font("segmdl2.ttf", 10)
    for i, g in enumerate(["\uE8BB", "\uE922", "\uE921"]):  # close, maximize, minimize (right to left)
        d.text((cw - 23 - i * 46, 16), g, font=glyphs, fill=fg, anchor="mm")
    if scale != 1.0:
        win = win.resize((round(win.width * scale), round(win.height * scale)), Image.LANCZOS)
    r = round(10 * max(scale, 0.6))
    mask = Image.new("L", win.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, win.width - 1, win.height - 1), r, fill=255)
    out = win.convert("RGBA")
    out.putalpha(mask)
    ImageDraw.Draw(out).rounded_rectangle((0, 0, win.width - 1, win.height - 1), r, outline=(255, 255, 255, 40) if dark else (0, 0, 0, 45))
    return out


def place(canvas, win, x, y, blur=26, drop=18, opacity=150):
    """Window with a soft drop shadow."""
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    m = Image.new("L", win.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, win.width - 1, win.height - 1), 14, fill=opacity)
    shadow.paste((0, 0, 0, 255), (x, y + drop), m)
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(shadow)
    canvas.alpha_composite(win, (x, y))


def framed(raw_name, out_name, width=1500, pad=56):
    win = window(raw_name)
    s = (width - 2 * pad) / win.width
    win = window(raw_name, s)
    canvas = gradient(width, win.height + 2 * pad + 10)
    place(canvas, win, pad, pad - 6)
    save(canvas, out_name)


def save(img, name):
    img.convert("RGB").save(os.path.join(OUT, name), optimize=True)
    print(name, img.size, os.path.getsize(os.path.join(OUT, name)) // 1024, "KB")


def hero():
    W, H = 1600, 1220
    c = gradient(W, H)
    d = ImageDraw.Draw(c)
    logo = Image.open("docs/images/logo.png").convert("RGBA")
    logo = logo.crop(logo.getchannel("A").getbbox())
    logo = logo.resize((150, round(150 * logo.height / logo.width)), Image.LANCZOS)
    title, tag = font("segoeuib.ttf", 64), font("segoeuil.ttf", 27)
    tw = d.textlength("Donat TeleDrive", font=title)
    x0 = (W - (logo.width + 26 + tw)) // 2
    c.alpha_composite(logo, (round(x0), 70))
    d.text((x0 + logo.width + 26, 70 + logo.height // 2), "Donat TeleDrive", font=title, fill=(255, 255, 255), anchor="lm")
    d.text((W // 2, 200), "Your own private cloud drive — powered by your Telegram account.", font=tag, fill=(214, 214, 236), anchor="mm")
    chips = ["Unlimited storage you own", "Recovery-first", "SHA-256 verified", "11 themes"]
    cf = font("seguisb.ttf", 17)
    widths = [d.textlength(t, font=cf) + 40 for t in chips]
    x = (W - (sum(widths) + 14 * (len(chips) - 1))) // 2
    glass = Image.new("RGBA", c.size, (0, 0, 0, 0))  # translucent chips: drawn on their own layer, then blended
    g = ImageDraw.Draw(glass)
    for w in widths:
        g.rounded_rectangle((x, 238, x + w, 274), 18, fill=(255, 255, 255, 30), outline=(255, 255, 255, 80))
        x += w + 14
    c.alpha_composite(glass)
    x = (W - (sum(widths) + 14 * (len(chips) - 1))) // 2
    for t, w in zip(chips, widths):
        d.text((x + w / 2, 256), t, font=cf, fill=(240, 240, 255), anchor="mm")
        x += w + 14
    desk = window("home.png", 1300 / 1424)
    place(c, desk, 150, 320, blur=34, drop=26, opacity=190)
    phone = window("phone-dark.png", 0.62)
    place(c, phone, W - phone.width - 70, H - phone.height - 40, blur=30, drop=22, opacity=200)
    save(c, "hero.png")


def collage(names, labels, out_name, cols=2, cell_w=760, gap=40, pad=60):
    wins = [window(n, cell_w / window(n).width) for n in names]
    rows = (len(wins) + cols - 1) // cols
    cell_h = max(w.height for w in wins) + 50
    W = pad * 2 + cols * cell_w + (cols - 1) * gap
    H = pad * 2 + rows * cell_h + (rows - 1) * (gap - 20)
    c = gradient(W, H)
    d = ImageDraw.Draw(c)
    lf = font("seguisb.ttf", 22)
    for i, (w, label) in enumerate(zip(wins, labels)):
        x = pad + (i % cols) * (cell_w + gap)
        y = pad + (i // cols) * (cell_h + gap - 20)
        place(c, w, x + (cell_w - w.width) // 2, y)
        d.text((x + cell_w / 2, y + w.height + 30), label, font=lf, fill=(235, 235, 250), anchor="mm")
    save(c, out_name)


def social():
    """1280×640 card (GitHub profile / social preview), same layout as Donat Downloader's: name left, app window right."""
    W, H = 1280, 640
    c = gradient(W, H)
    d = ImageDraw.Draw(c)
    logo = Image.open("docs/images/logo.png").convert("RGBA")
    logo = logo.crop(logo.getchannel("A").getbbox())
    logo = logo.resize((190, round(190 * logo.height / logo.width)), Image.LANCZOS)
    c.alpha_composite(logo, (70, 165))
    title = font("segoeuib.ttf", 66)
    d.text((72, 300), "Donat", font=title, fill=(255, 255, 255))
    d.text((72, 372), "TeleDrive", font=title, fill=(255, 255, 255))
    d.text((74, 470), "Your private cloud on Telegram", font=font("segoeuil.ttf", 26), fill=(214, 214, 236))
    d.text((74, 508), "installer  ·  zip  ·  portable  ·  open source", font=font("segoeuil.ttf", 21), fill=(160, 160, 190))
    win = window("home.png", 0.62)
    place(c, win, 590, 88, blur=24, drop=16, opacity=170)  # runs off the right edge, like the Downloader card
    save(c, "social-preview.png")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    social()
    hero()
    for raw, out in [("gallery.png", "gallery.png"), ("preview.png", "preview.png"), ("transfers.png", "transfers.png"),
                     ("insights.png", "insights.png"), ("files.png", "files-details.png"), ("settings.png", "settings.png"),
                     ("login.png", "login.png"), ("lock.png", "lock.png")]:
        framed(raw, out, width=1500 if raw not in ("login.png", "lock.png") else 1100)
    collage([f"theme-{t}.png" for t in ("DAYLIGHT", "OCEAN", "SAKURA", "NEON")], ["Daylight", "Ocean", "Sakura", "Neon"], "themes.png")
    collage(["phone-light.png", "phone-dark.png"], ["Daylight", "Forest"], "phones.png", cell_w=430)
