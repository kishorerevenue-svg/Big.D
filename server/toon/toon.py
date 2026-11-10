#!/usr/bin/env python3
"""BigD cartoons: our own cartoon maker (no paid video AI, no GPU needed).

Draws BigD's own characters (all original) in a place, acts out a short screenplay (walk in, talk, slip, jump,
laugh, dance, sleep, give, eat, balance ...), adds speech bubbles (the Telugu words are drawn by the video's
subtitle engine), sound effects, talk sounds (or the natural voice, when the server has it) and music.

  python3 toon.py plan.json        writes plan["out"] (mp4) and plan["poster"] (jpg), prints {"dur": seconds}
  python3 toon.py --demo DIR       a test cartoon in DIR
  python3 toon.py --sheet FILE     all characters and places in one picture (to check the drawings)
"""
import array, json, math, os, random, subprocess, sys, wave
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 720, 1280, 24
SS = 2                      # parts are drawn twice as big, then made smaller (smooth edges)
SR = 24000                  # sound samples a second
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ART = os.path.join(ROOT, "assets", "cartoon")
FONTS = os.path.join(ROOT, "fonts")
FONT = os.path.join(FONTS, "HindGuntur-Bold.ttf")
VERSION = "1"
INK = (56, 38, 30, 255)
WHITE = (255, 255, 255, 255)
INTRO, GAP, FIN, END = 0.7, 0.35, 1.6, 2.0

CHARS = ["bigd", "chaiwala", "ammamma", "chinnu", "chitti", "raithu", "teacher", "pilli", "chilaka"]
PLACES = ["market", "village", "farm", "teashop", "beach", "park", "home", "school", "stage", "night"]
ACTIONS = ["talk", "enter", "exit", "run", "jump", "slip", "fall", "laugh", "surprised", "think", "dance", "sleep",
           "cry", "wave", "give", "eat", "balance", "point", "clap", "shrug", "facepalm", "crossarms", "cheer", "kneel",
           "angry", "shy"]
PROPS = ["tea", "tray", "kettle", "banana", "ball", "book", "mango", "umbrella", "phone", "kite", "laddu", "flower",
         "bag", "icecream", "letter"]
GROUND = ("banana", "ball")
PITCH = {"chaiwala": 150, "raithu": 135, "ammamma": 235, "teacher": 255, "chinnu": 330, "chitti": 385, "bigd": 300,
         "pilli": 520, "chilaka": 640, "chai": 165, "idli": 340, "mango": 390, "tatayya": 140}


# ================================================================ drawing helpers
def rgb(c, a=None):
    if c is None:
        return None
    if isinstance(c, tuple):
        if len(c) == 4:
            return c if a is None else c[:3] + (a,)
        return tuple(c) + (255 if a is None else a,)
    c = c.lstrip("#")
    return (int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), 255 if a is None else a)


def mix(c1, c2, t):
    a, b = rgb(c1), rgb(c2)
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(4))


def shade(c, f):
    a = rgb(c)
    return (min(255, int(a[0] * f)), min(255, int(a[1] * f)), min(255, int(a[2] * f)), a[3])


def new(w, h):
    return Image.new("RGBA", (max(1, int(round(w * SS))), max(1, int(round(h * SS)))), (0, 0, 0, 0))


def clamp(v, a, b):
    return a if v < a else b if v > b else v


def lerp(a, b, k):
    return a + (b - a) * k


def ease_in(k):
    k = clamp(k, 0, 1)
    return k * k


def ease_out(k):
    k = clamp(k, 0, 1)
    return 1 - (1 - k) * (1 - k)


def ease_io(k):
    k = clamp(k, 0, 1)
    return k * k * (3 - 2 * k)


class Pen:
    """Draws in 1x units onto a 2x picture; (ox, oy) is where (0, 0) is."""

    def __init__(self, img, ox=0.0, oy=0.0):
        self.img, self.d, self.ox, self.oy = img, ImageDraw.Draw(img), ox, oy

    def p(self, x, y):
        return ((x + self.ox) * SS, (y + self.oy) * SS)

    def box(self, cx, cy, rx, ry):
        rx, ry = abs(rx), abs(ry)
        return [(cx - rx + self.ox) * SS, (cy - ry + self.oy) * SS, (cx + rx + self.ox) * SS, (cy + ry + self.oy) * SS]

    def ell(self, cx, cy, rx, ry, fill, line=None, w=0):
        self.d.ellipse(self.box(cx, cy, rx, ry), fill=rgb(fill), outline=rgb(line), width=max(0, int(w * SS)))

    def rect(self, x0, y0, x1, y1, fill, r=0, line=None, w=0):
        a, b = self.p(min(x0, x1), min(y0, y1))
        c, d = self.p(max(x0, x1), max(y0, y1))
        r = min(r * SS, (c - a) / 2, (d - b) / 2)
        self.d.rounded_rectangle([a, b, c, d], radius=max(0, int(r)), fill=rgb(fill), outline=rgb(line), width=max(0, int(w * SS)))

    def poly(self, pts, fill):
        self.d.polygon([self.p(x, y) for x, y in pts], fill=rgb(fill))

    def line(self, pts, fill, w):
        self.d.line([self.p(x, y) for x, y in pts], fill=rgb(fill), width=max(1, int(w * SS)), joint="curve")

    def arc(self, cx, cy, rx, ry, a0, a1, fill, w):
        self.d.arc(self.box(cx, cy, rx, ry), a0, a1, fill=rgb(fill), width=max(1, int(w * SS)))

    def chord(self, cx, cy, rx, ry, a0, a1, fill):
        self.d.chord(self.box(cx, cy, rx, ry), a0, a1, fill=rgb(fill))

    def pie(self, cx, cy, rx, ry, a0, a1, fill):
        self.d.pieslice(self.box(cx, cy, rx, ry), a0, a1, fill=rgb(fill))

    def stick(self, x0, y0, x1, y1, w, fill):
        self.line([(x0, y0), (x1, y1)], fill, w)
        self.ell(x0, y0, w / 2, w / 2, fill)
        self.ell(x1, y1, w / 2, w / 2, fill)


def grow(img, px, color):
    a = img.getchannel("A")
    g = a.filter(ImageFilter.GaussianBlur(max(0.6, px / 2.2))).point(lambda v: 255 if v > 4 else 0)
    base = Image.new("RGBA", img.size, rgb(color))
    base.putalpha(g)
    base.alpha_composite(img)
    return base


def finish(img, edge=3.0, color=INK):
    """The dark cartoon outline around a drawing, then made 1x (smooth edges)."""
    if edge > 0:
        img = grow(img, edge * SS, color)
    return img.resize((max(1, img.width // SS), max(1, img.height // SS)), Image.LANCZOS)


def part(img, cx, cy):
    """A finished 1x picture whose (0, 0) is at (cx, cy) -> (cropped picture, left, top) relative to (0, 0)."""
    bb = img.getbbox()
    if not bb:
        return None
    return (img.crop(bb), bb[0] - cx, bb[1] - cy)


def comp(dst, im, x, y):
    """alpha_composite that also works when the picture sticks out on the left or top."""
    x, y = int(round(x)), int(round(y))
    sx = sy = 0
    if x < 0:
        sx, x = -x, 0
    if y < 0:
        sy, y = -y, 0
    if sx >= im.width or sy >= im.height or x >= dst.width or y >= dst.height:
        return
    if sx or sy:
        im = im.crop((sx, sy, im.width, im.height))
    dst.alpha_composite(im, (x, y))


def rot_about(img, ang, px, py, resample=Image.BICUBIC):
    """Turns a picture by ang degrees (counter-clockwise on screen) around (px, py) -> (picture, new px, new py)."""
    if abs(ang) < 0.3:
        return img, px, py
    w, h = img.size
    r = math.radians(ang)
    c, s = math.cos(r), math.sin(r)
    pts = [(-px, -py), (w - px, -py), (-px, h - py), (w - px, h - py)]
    us = [x * c + y * s for x, y in pts]
    vs = [-x * s + y * c for x, y in pts]
    ox, oy = min(us) - 1, min(vs) - 1
    ow, oh = int(math.ceil(max(us) - ox)) + 2, int(math.ceil(max(vs) - oy)) + 2
    data = (c, -s, ox * c - oy * s + px, s, c, ox * s + oy * c + py)
    return img.transform((ow, oh), Image.AFFINE, data, resample), -ox, -oy


def tight(img, px, py):
    """Crops a limb picture to what is drawn, keeping where its turning point is."""
    bb = img.getbbox()
    if not bb:
        return img, px, py
    return img.crop(bb), px - bb[0], py - bb[1]


def centered(img, gx, gy):
    """Pads a picture so (gx, gy) is in the middle (so it can turn around that point)."""
    half = int(math.ceil(max(gx, img.width - gx, gy, img.height - gy))) + 2
    out = Image.new("RGBA", (2 * half, 2 * half), (0, 0, 0, 0))
    out.alpha_composite(img, (int(round(half - gx)), int(round(half - gy))))
    return out, half


def font(size):
    try:
        return ImageFont.truetype(FONT, size)
    except Exception:
        return ImageFont.load_default()


def text_img(txt, size, fill, stroke=WHITE, sw=4):
    f = font(size)
    bb = f.getbbox(txt, stroke_width=sw)
    img = Image.new("RGBA", (bb[2] - bb[0] + 6, bb[3] - bb[1] + 6), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((3 - bb[0], 3 - bb[1]), txt, font=f, fill=rgb(fill), stroke_width=sw, stroke_fill=rgb(stroke))
    return img


def paste(frame, im, x, y):
    frame.paste(im, (int(round(x)), int(round(y))), im)


# ================================================================ places (720x1280 backgrounds)
def sky(p, top, bottom, y0, y1, n=40):
    for i in range(n):
        p.rect(0, y0 + (y1 - y0) * i / n, W, y0 + (y1 - y0) * (i + 1) / n + 1.5, mix(top, bottom, i / max(1, n - 1)))


def cloud(p, x, y, k=1.0):
    for dx, dy, rr in ((-50, 10, 34), (-14, -12, 44), (30, 0, 38), (64, 12, 28), (4, 16, 42)):
        p.ell(x + dx * k, y + dy * k, rr * k, rr * k * 0.82, WHITE)


def sun(p, x, y, r):
    p.ell(x, y, r * 1.5, r * 1.5, (255, 240, 170, 255))
    p.ell(x, y, r, r, (255, 205, 60, 255))


def tree(p, x, g, h, k=1.0):
    p.rect(x - 13 * k, g - h, x + 13 * k, g, (125, 84, 52, 255), r=6 * k)
    for dx, dy, rr, c in ((0, -h - 40 * k, 72 * k, (70, 150, 75)), (-52 * k, -h + 6 * k, 54 * k, (70, 150, 75)),
                          (54 * k, -h + 4 * k, 56 * k, (70, 150, 75)), (0, -h + 20 * k, 62 * k, (78, 160, 82)),
                          (-22 * k, -h - 62 * k, 30 * k, (112, 190, 104))):
        p.ell(x + dx, g + dy, rr, rr * 0.9, c)


def palm(p, x, g, h, lean=1, k=1.0):
    pts = [(x + lean * 46 * k * (i / 12) ** 2, g - h * i / 12) for i in range(13)]
    for i in range(12):
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        p.stick(x0, y0, x1, y1, (26 - 9 * i / 12) * k, (146, 104, 64))
        p.line([(x0 - 10 * k, y0), (x0 + 10 * k, y0)], (118, 82, 50), 2 * k)
    tx, ty = pts[-1]
    for ang in (-170, -140, -108, -75, -45, -12, 18):
        a = math.radians(ang)
        L = 130 * k
        mid = (tx + math.cos(a) * L * 0.5, ty + math.sin(a) * L * 0.5 - 10 * k)
        tip = (tx + math.cos(a) * L, ty + math.sin(a) * L * 0.6 + 46 * k)
        p.line([(tx, ty), mid, tip], (58, 150, 72), 15 * k)
        p.line([(tx, ty), mid], (92, 178, 92), 5 * k)
    for dx, dy in ((-12, 12), (10, 12), (0, 22)):
        p.ell(tx + dx * k, ty + dy * k, 11 * k, 11 * k, (112, 82, 44))


def hut(p, x0, x1, g, wall_h=200, roof_h=160, wall=(214, 158, 100), roof=(184, 132, 62)):
    mx = (x0 + x1) / 2
    p.rect(x0, g - wall_h, x1, g, wall)
    p.rect(x0, g - 26, x1, g, shade(wall, 0.85))
    p.rect(mx - 32, g - 130, mx + 32, g, (104, 64, 38), r=8)
    p.rect(x0 + 22, g - wall_h + 40, x0 + 66, g - wall_h + 84, (96, 62, 40), r=4)
    p.poly([(x0 - 34, g - wall_h + 10), (x1 + 34, g - wall_h + 10), (mx, g - wall_h - roof_h)], roof)
    for i in range(9):
        p.line([(mx, g - wall_h - roof_h + 12), (x0 - 34 + (x1 - x0 + 68) * i / 8, g - wall_h + 8)], shade(roof, 0.8), 2)


def shop(p, x0, x1, top, g, wall, aw1, aw2, goods):
    p.rect(x0, top, x1, g, wall)
    p.rect(x0 + 16, top + 14, x1 - 16, top + 64, (255, 247, 226), r=8, line=shade(wall, 0.7), w=3)
    n, ay0, ay1 = 6, top + 78, top + 150
    span = x1 - x0 + 16
    for i in range(n):
        c = aw1 if i % 2 == 0 else aw2
        xa, xb = x0 - 8 + span * i / n, x0 - 8 + span * (i + 1) / n
        p.rect(xa, ay0, xb, ay1, c)
        p.ell((xa + xb) / 2, ay1, (xb - xa) / 2, 14, c)
    p.rect(x0 + 30, ay1 + 26, x1 - 30, g, (86, 58, 42))
    p.rect(x0 + 8, g - 150, x1 - 8, g, (176, 122, 69), r=6)
    p.rect(x0 + 8, g - 150, x1 - 8, g - 132, (196, 142, 86), r=6)
    cw = (x1 - x0 - 40) / len(goods)
    for i, c in enumerate(goods):
        cx = x0 + 20 + cw * (i + 0.5)
        for dy, cnt in ((0, 4), (-20, 3), (-40, 2)):
            for j in range(cnt):
                p.ell(cx + (j - (cnt - 1) / 2) * 19, g - 162 + dy, 10, 10, c)


def bunting(p, y, sag, colors):
    f = lambda x: y + sag * (1 - ((x - 360) / 380) ** 2)
    p.line([(x, f(x)) for x in range(-10, 731, 10)], (90, 70, 60), 2)
    for i, x in enumerate(range(26, 720, 52)):
        yy = f(x)
        p.poly([(x - 17, yy), (x + 17, yy), (x, yy + 36)], colors[i % len(colors)])


def speckle(p, y0, y1, color, n=60, seed=3, r=3):
    rnd = random.Random(seed)
    for _ in range(n):
        p.ell(rnd.uniform(0, W), rnd.uniform(y0, y1), r, r * 0.7, color)


def glass_on(p, x, y, k=1.0):
    p.rect(x - 13 * k, y - 44 * k, x + 13 * k, y, (226, 238, 244), r=4 * k, line=INK, w=2 * k)
    p.rect(x - 10 * k, y - 34 * k, x + 10 * k, y - 3 * k, (160, 92, 44), r=3 * k)
    p.rect(x - 10 * k, y - 37 * k, x + 10 * k, y - 31 * k, (238, 214, 180), r=2 * k)


def place_market():
    img = new(W, H)
    p = Pen(img)
    sky(p, "#8ED2FF", "#E4F6FF", 0, 780)
    cloud(p, 150, 150)
    cloud(p, 560, 230, 0.75)
    for x0, x1, top, c in ((-20, 160, 330, "#F6D7A6"), (150, 330, 270, "#CDE2F6"), (320, 500, 340, "#F2C1C9"), (490, 740, 290, "#D6EFC6")):
        p.rect(x0, top, x1, 780, c)
        wc = mix(c, "#FFFFFF", 0.55)
        for yy in range(top + 34, 720, 74):
            for xx in range(int(x0) + 26, int(x1) - 34, 54):
                p.rect(xx, yy, xx + 26, yy + 36, wc, r=3)
    bunting(p, 300, 70, [(232, 72, 60), (255, 196, 60), (64, 170, 120), (70, 130, 220), (240, 120, 180)])
    shop(p, -14, 252, 520, 1010, (246, 194, 108), (226, 62, 52), (255, 255, 255), [(245, 166, 35), (232, 75, 60), (123, 192, 67)])
    shop(p, 236, 490, 550, 1010, (143, 209, 143), (40, 140, 90), (255, 255, 255), [(255, 214, 64), (245, 166, 35), (160, 100, 200)])
    shop(p, 474, 734, 515, 1010, (244, 154, 193), (60, 110, 200), (255, 255, 255), [(232, 75, 60), (123, 192, 67), (255, 214, 64)])
    p.rect(0, 1000, W, 1052, (204, 196, 186))
    p.rect(0, 1048, W, 1058, (168, 160, 150))
    p.rect(0, 1058, W, H, (184, 176, 166))
    speckle(p, 1070, H, (165, 157, 148))
    return finish(img, 0).convert("RGB"), 1188


def place_village():
    img = new(W, H)
    p = Pen(img)
    sky(p, "#7CC6F4", "#EAF8FF", 0, 900)
    sun(p, 600, 170, 56)
    cloud(p, 160, 190)
    cloud(p, 420, 120, 0.7)
    p.ell(170, 905, 430, 170, (124, 194, 110))
    p.ell(610, 925, 380, 160, (106, 180, 94))
    palm(p, 62, 960, 640, 1, 1.0)
    palm(p, 672, 950, 560, -1, 0.9)
    hut(p, 136, 372, 965, 190, 150)
    hut(p, 430, 604, 955, 150, 120, wall=(226, 176, 118), roof=(170, 120, 56))
    p.rect(0, 955, W, H, (217, 178, 122))
    p.ell(360, 1120, 430, 105, (229, 198, 150))
    rnd = random.Random(5)
    for _ in range(30):
        x, y = rnd.uniform(0, W), rnd.uniform(975, H)
        if abs(y - 1120) < 95 and abs(x - 360) < 360:
            continue
        for dx in (-6, 0, 6):
            p.poly([(x + dx - 4, y), (x + dx + 4, y), (x + dx * 1.6, y - 16)], (96, 170, 80))
    return finish(img, 0).convert("RGB"), 1190


def place_farm():
    img = new(W, H)
    p = Pen(img)
    sky(p, "#8BCFFF", "#F2FBFF", 0, 770)
    sun(p, 130, 160, 52)
    cloud(p, 470, 170, 0.9)
    for i in range(14):
        tree(p, -20 + i * 56, 772, 26 + (i % 3) * 12, 0.42)
    palm(p, 596, 778, 250, -1, 0.5)
    palm(p, 650, 780, 220, 1, 0.45)
    for y0, y1, c in ((770, 830, (124, 200, 110)), (830, 900, (106, 184, 94)), (900, 975, (124, 200, 110)), (975, 1052, (100, 176, 88))):
        p.rect(0, y0, W, y1, c)
        step = 30 + (y0 - 770) // 12
        for x in range(8, W, step):
            hh = 10 + (y0 - 770) / 22
            p.line([(x - 4, y1 - 6 - hh), (x, y1 - 6), (x + 4, y1 - 6 - hh)], shade(c, 0.75), 2)
    sx, sy = 530, 905
    p.stick(sx, sy - 120, sx, sy, 8, (130, 90, 50))
    p.stick(sx - 52, sy - 92, sx + 52, sy - 92, 7, (130, 90, 50))
    p.rect(sx - 26, sy - 102, sx + 26, sy - 50, (220, 80, 60), r=6)
    p.ell(sx, sy - 128, 18, 18, (240, 210, 150))
    p.poly([(sx - 36, sy - 136), (sx + 36, sy - 136), (sx, sy - 172)], (230, 190, 90))
    p.rect(0, 1046, W, H, (190, 142, 88))
    p.rect(0, 1040, W, 1062, (110, 176, 90))
    speckle(p, 1080, H, (168, 122, 74), seed=7)
    return finish(img, 0).convert("RGB"), 1188


def place_teashop():
    img = new(W, H)
    p = Pen(img)
    p.rect(0, 0, W, 995, (88, 172, 172))
    p.rect(0, 0, W, 60, (70, 150, 150))
    p.rect(110, 92, 610, 212, (226, 62, 52), r=14, line=(255, 236, 200), w=5)
    glass_on(p, 300, 190, 1.4)
    glass_on(p, 360, 190, 1.4)
    glass_on(p, 420, 190, 1.4)
    p.rect(50, 420, 600, 438, (150, 96, 54))
    for i, c in enumerate([(245, 166, 35), (232, 75, 60), (255, 214, 64), (123, 192, 67)]):
        cx = 115 + i * 125
        p.rect(cx - 42, 300, cx + 42, 420, (214, 236, 242), r=14, line=INK, w=2)
        p.rect(cx - 32, 280, cx + 32, 302, (230, 80, 70), r=6)
        for j in range(5):
            p.ell(cx - 22 + (j % 3) * 22, 352 + (j // 3) * 32, 14, 14, c)
    p.line([(655, 0), (655, 250)], (90, 70, 50), 3)
    for i in range(8):
        p.ell(655 + (i % 2) * 20 - 10, 262 + i * 20, 12, 34, (250, 214, 60), line=(190, 150, 30), w=2)
    p.rect(0, 655, W, 995, (168, 108, 60))
    p.rect(0, 640, W, 678, (196, 140, 84))
    for x in range(0, W, 120):
        p.rect(x + 14, 700, x + 106, 960, (152, 96, 52), r=8)
    p.rect(470, 592, 640, 642, (70, 70, 74), r=6)
    p.ell(555, 548, 64, 50, (196, 202, 210), line=INK, w=2)
    p.poly([(500, 548), (448, 512), (440, 520), (494, 566)], (180, 186, 194))
    p.ell(555, 500, 28, 10, (170, 176, 184))
    p.ell(555, 492, 9, 7, INK)
    for i, sx in enumerate((520, 560, 600)):
        p.arc(sx, 440 - i * 6, 14, 26, 200, 340, (235, 245, 245), 4)
        p.arc(sx + 12, 400 - i * 6, 14, 22, 20, 160, (235, 245, 245), 4)
    for i in range(4):
        glass_on(p, 90 + i * 60, 640)
    p.rect(0, 995, W, H, (200, 194, 182))
    for y in range(1060, H, 70):
        p.line([(0, y), (W, y)], (184, 178, 166), 2)
    return finish(img, 0).convert("RGB"), 1190


def place_beach():
    img = new(W, H)
    p = Pen(img)
    sky(p, "#7ACDFF", "#E8F8FF", 0, 720)
    sun(p, 580, 150, 50)
    cloud(p, 170, 170, 0.8)
    for gx, gy in ((300, 260), (350, 300), (420, 240)):
        p.arc(gx - 12, gy, 12, 8, 200, 340, INK, 3)
        p.arc(gx + 12, gy, 12, 8, 200, 340, INK, 3)
    p.rect(0, 700, W, 900, (46, 155, 214))
    p.rect(0, 770, W, 900, (66, 175, 228))
    for y in (735, 800, 860):
        for x in range(-20, W, 92):
            p.arc(x + 46, y, 46, 10, 200, 340, (220, 244, 255), 4)
    p.rect(0, 890, W, H, (242, 212, 155))
    p.ell(360, 893, 440, 26, (250, 234, 196))
    p.stick(632, 770, 632, 1000, 8, (150, 110, 70))
    for i in range(6):
        p.pie(632, 790, 130, 80, 180 + i * 30, 210 + i * 30, (232, 72, 60) if i % 2 == 0 else (255, 255, 255))
    for x, y in ((120, 1160), (520, 1230), (300, 1250)):
        p.pie(x, y, 18, 18, 180, 360, (250, 160, 170))
    sx, sy = 80, 1060
    pts = []
    for i in range(10):
        a = math.radians(-90 + i * 36)
        rr = 26 if i % 2 == 0 else 11
        pts.append((sx + math.cos(a) * rr, sy + math.sin(a) * rr))
    p.poly(pts, (245, 140, 60))
    speckle(p, 920, H, (226, 194, 136), seed=9)
    return finish(img, 0).convert("RGB"), 1195


OLD_PLACES = {"park": ("bg_park.png", 1190), "home": ("bg_room.png", 1195), "school": ("bg_school.png", 1195),
              "night": ("bg_night.png", 1190), "stage": ("bg_stage.png", 1185)}
NEW_PLACES = {"market": place_market, "village": place_village, "farm": place_farm, "teashop": place_teashop, "beach": place_beach}


def nightify(img):
    out = Image.blend(img, Image.new("RGB", img.size, (18, 24, 66)), 0.55)
    d = ImageDraw.Draw(out)
    rnd = random.Random(11)
    for _ in range(40):
        x, y = rnd.uniform(0, W), rnd.uniform(0, 560)
        r = rnd.choice((1.5, 2, 2.5))
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 250, 220))
    d.ellipse([520, 110, 610, 200], fill=(255, 246, 200))
    d.ellipse([548, 98, 638, 188], fill=(40, 46, 96))
    return out


def place_image(name, night, cache):
    name = name if name in PLACES else "park"
    ground = GROUNDS[name]
    key = os.path.join(cache, "place-%s-%s-v%s.png" % (name, "n" if night else "d", VERSION)) if cache else None
    if key and os.path.exists(key):
        try:
            return Image.open(key).convert("RGB"), ground
        except Exception:
            pass
    if name in OLD_PLACES:
        img = Image.open(os.path.join(ART, OLD_PLACES[name][0])).convert("RGB").resize((W, H))
    else:
        img = NEW_PLACES[name]()[0]
    if night and name != "night":
        img = nightify(img)
    if key:
        try:
            os.makedirs(cache, exist_ok=True)
            img.save(key)
        except Exception:
            pass
    return img, ground


GROUNDS = {"market": 1188, "village": 1190, "farm": 1188, "teashop": 1190, "beach": 1195, "park": 1190, "home": 1195,
           "school": 1195, "night": 1190, "stage": 1185}


# ================================================================ characters
HUMANS = {
    "chaiwala": dict(s=1.0, belly=1.3, skin="#B5763F", hair="short", hair_c="#2A1E1A", moust=True, top="#F3EEDC",
                     style="kurta", sleeve=0.34, low="pants", low_c="#55407E", shoe="#5B3A25", towel="#E2453A"),
    "raithu": dict(s=1.0, belly=1.05, skin="#8E5A33", hair="turban", hair_c="#2A1E1A", turban="#FFFFFF",
                   turban2="#2E9E5B", moust=True, top="#FFFFFF", style="banian", sleeve=0, low="dhoti",
                   low_c="#FFFBEF", shoe="#7A4A2A", towel="#2E9E5B"),
    "ammamma": dict(s=0.93, belly=1.14, skin="#D39B68", hair="bun", hair_c="#E7E3DD", glasses=True, wrinkles=True,
                    earrings=True, top="#8E1F3F", style="blouse", sleeve=0.3, low="saree", low_c="#A3284B",
                    border="#F2B33D", shoe="#8A5A3B"),
    "teacher": dict(s=0.96, belly=0.95, skin="#DFA06E", hair="braid", hair_c="#1E1614", earrings=True, top="#2F6FC0",
                    style="kurta_d", sleeve=0.62, low="kurta", low_c="#2F6FC0", pants_c="#F1ECE2", dupatta="#F2B33D",
                    shoe="#5A3A2A"),
    "chinnu": dict(s=0.74, hs=0.95, belly=0.95, skin="#C98A55", hair="spiky", hair_c="#231A16", top="#FFFFFF",
                   style="shirt", sleeve=0.36, low="shorts", low_c="#24407A", shoe="#2B2B2B", bag="#E2453A",
                   tie="#D9302C"),
    "chitti": dict(s=0.72, hs=0.95, belly=0.92, skin="#D9A06B", hair="pigtails", hair_c="#231A16", ribbon="#FF4F8B",
                   top="#FF8FB8", style="frock", sleeve=0.3, puff=True, low="frock", low_c="#FF8FB8", shoe="#C2185B"),
}
EYE_KINDS = ("open", "wide", "blink", "happy", "sleep", "sad")
MOUTHS = ("closed", "half", "open", "smile", "o", "sad")


def draw_eyes(p, kind, look, ex, ey, rr, scale=1.0, iris=(43, 27, 18), lid=INK):
    """Both eyes at (+-ex, ey); rr is the head size."""
    for sx in (-1, 1):
        x = sx * ex
        rx, ry = 0.16 * rr * scale, 0.2 * rr * scale
        if kind in ("open", "wide", "sad"):
            if kind == "wide":
                rx, ry = rx * 1.15, ry * 1.2
            p.ell(x, ey, rx, ry, WHITE, line=lid, w=0.028 * rr)
            pr = (0.105 if kind != "wide" else 0.075) * rr * scale
            px = x + look * 0.055 * rr
            p.ell(px, ey + 0.02 * rr, pr, pr * 1.1, iris)
            p.ell(px + pr * 0.38, ey - pr * 0.3, pr * 0.32, pr * 0.32, WHITE)
        elif kind == "blink":
            p.arc(x, ey + 0.02 * rr, rx, ry * 0.45, 15, 165, lid, 0.038 * rr)
        elif kind == "happy":
            p.arc(x, ey + 0.09 * rr, rx, ry * 0.7, 200, 340, lid, 0.045 * rr)
        elif kind == "sleep":
            p.arc(x, ey - 0.02 * rr, rx, ry * 0.4, 15, 165, lid, 0.038 * rr)
        # eyebrows
        by = ey - 0.29 * rr * scale - (0.06 * rr if kind == "wide" else 0)
        if kind == "sad":
            p.line([(x - sx * 0.13 * rr, by + 0.02 * rr), (x + sx * 0.12 * rr, by + 0.08 * rr)], lid, 0.04 * rr)
        elif kind not in ("sleep",):
            p.arc(x, by + 0.07 * rr, 0.13 * rr, 0.07 * rr, 200, 340, lid, 0.04 * rr)


def draw_mouth(p, kind, my, rr, dark=(122, 31, 43), tongue=(255, 111, 142)):
    if kind == "closed":
        p.arc(0, my - 0.06 * rr, 0.15 * rr, 0.09 * rr, 20, 160, INK, 0.04 * rr)
    elif kind == "half":
        p.ell(0, my, 0.11 * rr, 0.07 * rr, dark, line=INK, w=0.025 * rr)
        p.ell(0, my + 0.035 * rr, 0.06 * rr, 0.025 * rr, tongue)
    elif kind == "open":
        p.ell(0, my + 0.02 * rr, 0.14 * rr, 0.13 * rr, dark, line=INK, w=0.025 * rr)
        p.ell(0, my + 0.08 * rr, 0.08 * rr, 0.045 * rr, tongue)
        p.rect(-0.08 * rr, my - 0.1 * rr, 0.08 * rr, my - 0.06 * rr, WHITE, r=0.02 * rr)
    elif kind == "smile":
        p.chord(0, my - 0.06 * rr, 0.21 * rr, 0.17 * rr, 0, 180, dark)
        p.arc(0, my - 0.06 * rr, 0.21 * rr, 0.17 * rr, 0, 180, INK, 0.03 * rr)
        p.line([(-0.21 * rr, my - 0.06 * rr), (0.21 * rr, my - 0.06 * rr)], INK, 0.03 * rr)
        p.rect(-0.15 * rr, my - 0.055 * rr, 0.15 * rr, my - 0.005 * rr, WHITE, r=0.02 * rr)
        p.ell(0, my + 0.06 * rr, 0.09 * rr, 0.04 * rr, tongue)
    elif kind == "o":
        p.ell(0, my + 0.02 * rr, 0.075 * rr, 0.095 * rr, dark, line=INK, w=0.025 * rr)
    elif kind == "sad":
        p.arc(0, my + 0.07 * rr, 0.14 * rr, 0.09 * rr, 200, 340, INK, 0.04 * rr)


def build_human(name):
    sp = HUMANS[name]
    s = sp["s"]
    r = 80 * (s ** 0.55) * sp.get("hs", 1.0)
    HIP, TOR, NECK = 150 * s, 175 * s, 8 * s
    SHW, BW = 60 * s, 64 * s * sp.get("belly", 1.0)
    LW, LL = 34 * s, 128 * s
    AW, AL = 26 * s, 138 * s
    skin = rgb(sp["skin"])
    skin_d = shade(skin, 0.8)
    hair = rgb(sp.get("hair_c", "#2A1E1A"))
    top = rgb(sp["top"])
    low = sp["low"]
    hy = -(TOR + NECK + r * 0.9)
    big = sp["s"] < 0.8

    # legs (left and right), turning around the hip
    def leg(side):
        half = LL + LW + 14 * s
        img = new(2 * half, 2 * half)
        p = Pen(img, half, half)
        p.stick(0, 0, 0, LL, LW, skin)
        if low in ("pants", "kurta"):
            p.stick(0, 0, 0, LL - 4 * s, LW + 2 * s, rgb(sp.get("pants_c", sp.get("low_c"))) if low == "kurta" else rgb(sp["low_c"]))
        if low == "shorts":
            p.stick(0, 0, 0, LL * 0.42, LW + 5 * s, rgb(sp["low_c"]))
        p.ell(side * 9 * s, LL + 6 * s, LW * 0.88, LW * 0.42, rgb(sp.get("shoe", "#5A3A2A")))
        return tight(finish(img), half, half)

    def arm():
        half = AL + AW + 14 * s
        img = new(2 * half, 2 * half)
        p = Pen(img, half, half)
        p.stick(0, 0, 0, AL, AW, skin)
        sl = sp.get("sleeve", 0.32)
        if sl > 0:
            p.stick(0, 0, 0, AL * sl, AW + 6 * s, top)
        if sp.get("puff"):
            p.ell(0, AL * 0.1, AW * 1.0, AW * 0.8, top)
        p.ell(0, AL + 2 * s, AW * 0.64, AW * 0.64, skin)
        return tight(finish(img), half, half)

    def torso():
        hw = 150 * s
        oy = TOR + 30 * s
        img = new(2 * hw, oy + 30 * s)
        p = Pen(img, hw, oy)
        st = sp["style"]
        if st == "kurta_d" or sp.get("hair") == "braid":
            pass
        p.rect(-12 * s, -TOR - 16 * s, 12 * s, -TOR + 14 * s, skin, r=4 * s)
        p.rect(-SHW, -TOR, SHW, -TOR * 0.3, top, r=30 * s)
        p.ell(0, -TOR * 0.4, BW, TOR * 0.5, top)
        p.rect(-BW * 0.9, -TOR * 0.45, BW * 0.9, 8 * s, top, r=18 * s)
        if st == "shirt":
            p.poly([(-15 * s, -TOR - 2 * s), (0, -TOR + 16 * s), (-2 * s, -TOR - 4 * s)], WHITE)
            p.line([(-15 * s, -TOR - 2 * s), (0, -TOR + 16 * s), (15 * s, -TOR - 2 * s)], INK, 2.2 * s)
            tie = rgb(sp.get("tie", "#D9302C"))
            p.poly([(0, -TOR + 10 * s), (-9 * s, -TOR + 32 * s), (0, -TOR * 0.32), (9 * s, -TOR + 32 * s)], tie)
            p.ell(0, -TOR + 12 * s, 6 * s, 5 * s, tie)
            bag = rgb(sp.get("bag", "#E2453A"))
            for sx in (-1, 1):
                p.line([(sx * (SHW - 14 * s), -TOR + 2 * s), (sx * (SHW - 18 * s), -TOR * 0.28)], bag, 12 * s)
        elif st == "kurta":
            p.line([(0, -TOR + 8 * s), (0, -TOR * 0.4)], shade(top, 0.75), 2.4 * s)
            for k in range(3):
                p.ell(0, -TOR + 22 * s + k * 22 * s, 3 * s, 3 * s, shade(top, 0.6))
            tw = rgb(sp.get("towel", "#E2453A"))
            p.line([(-SHW + 6 * s, -TOR - 6 * s), (-SHW + 22 * s, -TOR * 0.22)], tw, 28 * s)
            for k in range(4):
                yy = -TOR + 20 * s + k * 26 * s
                p.line([(-SHW - 6 * s + k * 1.5 * s, yy), (-SHW + 26 * s + k * 1.5 * s, yy + 4 * s)], WHITE, 3 * s)
        elif st == "banian":
            p.ell(0, -TOR + 2 * s, 24 * s, 30 * s, skin)
            tw = rgb(sp.get("towel", "#2E9E5B"))
            p.line([(SHW - 6 * s, -TOR - 6 * s), (SHW - 26 * s, -TOR * 0.22)], tw, 26 * s)
            p.line([(SHW + 4 * s, -TOR + 30 * s), (SHW - 30 * s, -TOR + 36 * s)], shade(tw, 0.7), 3 * s)
        elif st == "blouse":
            bd = rgb(sp.get("border", "#F2B33D"))
            pc = shade(top, 1.12)
            p.poly([(-SHW - 4 * s, -TOR + 2 * s), (-SHW + 40 * s, -TOR - 6 * s), (BW + 6 * s, -TOR * 0.25), (BW - 34 * s, -TOR * 0.08)], pc)
            p.line([(-SHW + 40 * s, -TOR - 6 * s), (BW + 6 * s, -TOR * 0.25)], bd, 6 * s)
            p.line([(-SHW - 4 * s, -TOR + 2 * s), (BW - 34 * s, -TOR * 0.08)], bd, 6 * s)
        elif st == "kurta_d":
            du = rgb(sp.get("dupatta", "#F2B33D"))
            for sx in (-1, 1):
                p.line([(sx * (SHW - 10 * s), -TOR - 4 * s), (sx * (SHW - 4 * s), -TOR * 0.25)], du, 22 * s)
            p.arc(0, -TOR + 2 * s, SHW - 10 * s, 26 * s, 0, 180, du, 18 * s)
        elif st == "frock":
            for sx in (-1, 1):
                p.ell(sx * 13 * s, -TOR + 6 * s, 16 * s, 11 * s, WHITE, line=INK, w=1.6 * s)
            rb = rgb(sp.get("ribbon", "#FF4F8B"))
            p.poly([(0, -TOR + 26 * s), (-14 * s, -TOR + 18 * s), (-14 * s, -TOR + 34 * s)], rb)
            p.poly([(0, -TOR + 26 * s), (14 * s, -TOR + 18 * s), (14 * s, -TOR + 34 * s)], rb)
        if sp.get("hair") == "braid":
            for k in range(7):
                p.ell(r * 0.5 + k * 1.5 * s, hy + r * 0.75 + k * 21 * s, 13 * s, 15 * s, hair, line=shade(hair, 1.6), w=1.4 * s)
            p.ell(r * 0.5 + 10 * s, hy + r * 0.75 + 152 * s, 9 * s, 8 * s, rgb("#FF4F8B"))
        return part(finish(img), hw, oy)

    def lower():
        c = rgb(sp.get("low_c", sp["top"]))
        hw = 160 * s
        oy = 40 * s
        img = new(2 * hw, oy + HIP + 20 * s)
        p = Pen(img, hw, oy)
        if low in ("pants", "shorts"):
            p.rect(-BW * 0.78, -14 * s, BW * 0.78, 24 * s, c, r=12 * s)
        elif low == "dhoti":
            p.poly([(-BW * 0.85, -12 * s), (BW * 0.85, -12 * s), (BW + 10 * s, HIP * 0.74), (-BW - 10 * s, HIP * 0.74)], c)
            for k in (-1, 0, 1):
                p.line([(k * 14 * s, -6 * s), (k * 22 * s, HIP * 0.7)], shade(c, 0.85), 2.2 * s)
        elif low == "saree":
            bd = rgb(sp.get("border", "#F2B33D"))
            p.poly([(-BW * 0.92, -14 * s), (BW * 0.92, -14 * s), (BW + 20 * s, HIP - 12 * s), (-BW - 20 * s, HIP - 12 * s)], c)
            p.rect(-BW - 20 * s, HIP - 30 * s, BW + 20 * s, HIP - 12 * s, bd)
            for k in range(4):
                p.line([(-8 * s + k * 9 * s, 10 * s), (-16 * s + k * 13 * s, HIP - 32 * s)], shade(c, 0.8), 2 * s)
        elif low == "kurta":
            p.poly([(-BW * 0.94, -16 * s), (BW * 0.94, -16 * s), (BW + 8 * s, HIP * 0.56), (-BW - 8 * s, HIP * 0.56)], c)
            for sx in (-1, 1):
                p.line([(sx * (BW + 2 * s), HIP * 0.3), (sx * (BW + 7 * s), HIP * 0.55)], shade(c, 0.7), 2.4 * s)
        elif low == "frock":
            p.poly([(-BW * 0.85, -18 * s), (BW * 0.85, -18 * s), (BW + 32 * s, HIP * 0.5), (-BW - 32 * s, HIP * 0.5)], c)
            p.rect(-BW - 32 * s, HIP * 0.5 - 12 * s, BW + 32 * s, HIP * 0.5, WHITE)
            for k in range(5):
                p.ell(-BW + k * BW * 0.5, HIP * 0.22 + (k % 2) * 14 * s, 4 * s, 4 * s, WHITE)
        return part(finish(img), hw, oy)

    def back():
        if not sp.get("bag"):
            return None
        hw = 120 * s
        oy = TOR + 40 * s
        img = new(2 * hw, oy + 20 * s)
        p = Pen(img, hw, oy)
        bag = rgb(sp["bag"])
        p.rect(-SHW - 12 * s, -TOR - 22 * s, SHW + 12 * s, -TOR * 0.25, bag, r=26 * s)
        p.rect(-SHW + 10 * s, -TOR - 30 * s, SHW - 10 * s, -TOR - 10 * s, shade(bag, 0.8), r=10 * s)
        return part(finish(img), hw, oy)

    # head: back (hair behind, ears, face, hair on top), eyes, mouth, front (moustache, glasses, turban, ribbons)
    side = int(3.6 * r)
    c = side / 2.0
    style = sp["hair"]
    img = new(side, side)
    p = Pen(img, c, c)
    if style == "bun":
        p.ell(0, -r * 1.0, r * 0.38, r * 0.3, hair)
    if style == "pigtails":
        for sx in (-1, 1):
            p.ell(sx * r * 1.12, r * 0.32, r * 0.24, r * 0.46, hair)
    if style == "braid":
        p.ell(0, -r * 0.06, r * 1.05, r * 1.1, hair)
    for sx in (-1, 1):
        p.ell(sx * r * 0.95, r * 0.08, r * 0.17, r * 0.21, skin)
        p.ell(sx * r * 0.95, r * 0.08, r * 0.08, r * 0.11, skin_d)
    p.ell(0, 0, r * 0.96, r, skin)
    if style in ("short", "spiky"):
        p.chord(0, -r * 0.24, r * 1.0, r * 0.84, 180, 360, hair)
        if style == "spiky":
            for k in range(7):
                a = math.radians(200 + k * 23)
                bx, by = math.cos(a) * r * 0.86, -r * 0.24 + math.sin(a) * r * 0.74
                tx, ty = math.cos(a) * r * 1.22, -r * 0.24 + math.sin(a) * r * 1.08
                p.poly([(bx - 0.16 * r, by), (bx + 0.16 * r, by), (tx, ty)], hair)
            p.poly([(-0.5 * r, -0.36 * r), (-0.1 * r, -0.36 * r), (-0.32 * r, -0.12 * r)], hair)
        else:
            p.pie(-0.34 * r, -0.28 * r, 0.42 * r, 0.22 * r, 0, 180, hair)
    elif style in ("bun", "braid", "pigtails"):
        p.chord(0, -r * 0.2, r * 1.0, r * 0.86, 180, 360, hair)
        p.line([(0, -r * 1.04), (0, -r * 0.5)], shade(hair, 0.75), r * 0.035)
    p.arc(0, r * 0.2, r * 0.085, r * 0.06, 20, 160, skin_d, r * 0.045)
    for sx in (-1, 1):
        p.ell(sx * r * 0.56, r * 0.33, r * 0.13, r * 0.08, mix(skin, "#FF6E82", 0.38))
    back_img = finish(img, 3.0 if s >= 0.8 else 2.6)
    eye_scale = 1.12 if big else 1.0
    eyes = {}
    for k in EYE_KINDS:
        for lk in ((-1, 0, 1) if k in ("open", "wide", "sad") else (0,)):
            im = new(side, side)
            draw_eyes(Pen(im, c, c), k, lk, 0.36 * r, 0.02 * r, r, eye_scale)
            eyes[(k, lk)] = finish(im, 0)
    my = r * (0.52 if sp.get("moust") else 0.48)
    mouths = {}
    for k in MOUTHS:
        im = new(side, side)
        draw_mouth(Pen(im, c, c), k, my, r)
        mouths[k] = finish(im, 0)
    fr = new(side, side)
    p = Pen(fr, c, c)
    has_front = False
    if sp.get("moust"):
        has_front = True
        for sx in (-1, 1):
            p.ell(sx * 0.17 * r, 0.36 * r, 0.21 * r, 0.085 * r, hair)
            p.arc(sx * 0.38 * r, 0.3 * r, 0.08 * r, 0.08 * r, 0 if sx > 0 else 90, 90 if sx > 0 else 180, hair, 0.05 * r)
    if style == "turban":
        has_front = True
        tc, tc2 = rgb(sp.get("turban", "#FFFFFF")), rgb(sp.get("turban2", "#2E9E5B"))
        p.ell(0, -0.78 * r, 1.08 * r, 0.56 * r, tc)
        p.chord(0, -0.52 * r, 1.02 * r, 0.36 * r, 0, 180, tc)
        p.line([(-0.95 * r, -0.62 * r), (0.85 * r, -1.08 * r)], tc2, 0.12 * r)
        for k in range(3):
            p.arc(0, -0.66 * r + k * 0.12 * r, 0.9 * r, 0.28 * r, 200, 340, shade(tc, 0.85), 0.035 * r)
        p.ell(0.78 * r, -1.0 * r, 0.16 * r, 0.12 * r, tc)
    if style == "pigtails":
        has_front = True
        rb = rgb(sp.get("ribbon", "#FF4F8B"))
        for sx in (-1, 1):
            x, y = sx * 0.98 * r, -0.2 * r
            p.poly([(x, y), (x - 0.22 * r, y - 0.14 * r), (x - 0.22 * r, y + 0.14 * r)], rb)
            p.poly([(x, y), (x + 0.22 * r, y - 0.14 * r), (x + 0.22 * r, y + 0.14 * r)], rb)
            p.ell(x, y, 0.06 * r, 0.06 * r, shade(rb, 0.8))
    if sp.get("earrings"):
        has_front = True
        for sx in (-1, 1):
            p.ell(sx * 0.97 * r, 0.3 * r, 0.06 * r, 0.06 * r, (242, 179, 61))
    front_img = finish(fr, 2.2) if has_front else None
    if sp.get("glasses") or sp.get("wrinkles"):
        g2 = new(side, side)
        p = Pen(g2, c, c)
        if sp.get("glasses"):
            for sx in (-1, 1):
                p.ell(sx * 0.36 * r, 0.02 * r, 0.25 * r, 0.23 * r, None, line=(110, 70, 40), w=0.045 * r)
            p.line([(-0.12 * r, 0.0), (0.12 * r, 0.0)], (110, 70, 40), 0.04 * r)
        if sp.get("wrinkles"):
            for sx in (-1, 1):
                p.line([(sx * 0.62 * r, -0.04 * r), (sx * 0.7 * r, -0.08 * r)], skin_d, 0.025 * r)
                p.line([(sx * 0.62 * r, 0.04 * r), (sx * 0.7 * r, 0.06 * r)], skin_d, 0.025 * r)
        g2 = finish(g2, 0)
        front_img = g2 if front_img is None else Image.alpha_composite(g2, front_img)
    hair_top = r * (1.05 if style not in ("turban", "spiky", "bun") else 1.3)
    up = TOR + NECK + r * 0.9 + hair_top
    C = int(2 * max(up + 30 * s, HIP + 30 * s, SHW + AL + 40 * s)) + 8
    return dict(kind="human", name=name, s=s, r=r, hip=HIP, rest=44 * s, hipx=26 * s, sh=(SHW - 6 * s, -TOR + 18 * s),
                AL=AL + 2 * s, head=(0.0, hy), top=up, C=C, leg={-1: leg(-1), 1: leg(1)}, arm=arm(), torso=torso(),
                lower=lower(), back=back(), hp=dict(side=side, back=back_img, eyes=eyes, mouth=mouths, front=front_img),
                mouth=(0.0, hy + my), hand_side=1)


def outline1x(img, edge=2.5, color=INK):
    return grow(img, edge, color)


def build_puppy():
    k = 0.42
    sprites = {}
    for m in ("closed", "half", "open"):
        for e in ("open", "blink"):
            im = Image.open(os.path.join(ART, "puppy_%s_%s.png" % (m, e))).convert("RGBA")
            im = im.resize((int(im.width * k), int(im.height * k)), Image.LANCZOS)
            pad = Image.new("RGBA", (im.width + 10, im.height + 10), (0, 0, 0, 0))
            pad.alpha_composite(im, (5, 5))
            sprites[(m, e)] = outline1x(pad, 2.4)
    w, h = sprites[("closed", "open")].size
    oy = 0.62 * h
    C = int(2 * max(oy, h - oy) + 40)
    return dict(kind="puppy", name="bigd", sprites=sprites, sw=w, shh=h, soy=oy, hip=h * 0.96 - oy, rest=0.3 * h,
                head=(0.0, 0.37 * h - oy), r=0.2 * h, top=oy - 0.12 * h, C=C, mouth=(0.0, 0.5 * h - oy), hand_side=0)


def critter_head(r, kind, base_fn, front_fn, mouth_y, eye_x, eye_y, eye_scale, iris):
    side = int(3.4 * r)
    c = side / 2.0
    img = new(side, side)
    base_fn(Pen(img, c, c))
    back_img = finish(img, 2.6)
    eyes = {}
    for k in EYE_KINDS:
        for lk in ((-1, 0, 1) if k in ("open", "wide", "sad") else (0,)):
            im = new(side, side)
            draw_eyes(Pen(im, c, c), k, lk, eye_x, eye_y, r, eye_scale, iris=iris)
            eyes[(k, lk)] = finish(im, 0)
    mouths = {}
    for k in MOUTHS:
        im = new(side, side)
        p = Pen(im, c, c)
        if kind == "parrot":
            draw_beak(p, k, r)
        else:
            draw_mouth(p, k, mouth_y, r * 0.8)
        mouths[k] = finish(im, 0 if kind != "parrot" else 2.0)
    front = None
    if front_fn:
        im = new(side, side)
        front_fn(Pen(im, c, c))
        front = finish(im, 0)
    return dict(side=side, back=back_img, eyes=eyes, mouth=mouths, front=front)


def draw_beak(p, kind, r):
    up, low = (240, 122, 42), (180, 70, 30)
    opened = kind in ("half", "open", "o", "smile")
    gap = {"half": 0.1, "open": 0.2, "o": 0.16, "smile": 0.14}.get(kind, 0) * r
    if opened:
        p.ell(0, 0.42 * r + gap * 0.5, 0.16 * r, 0.1 * r + gap * 0.5, (122, 31, 43))
    p.poly([(-0.2 * r, 0.42 * r + gap), (0.2 * r, 0.42 * r + gap), (0, 0.62 * r + gap)], low)
    p.poly([(-0.26 * r, 0.24 * r), (0.26 * r, 0.24 * r), (0.04 * r, 0.62 * r), (-0.06 * r, 0.5 * r)], up)


def build_cat():
    fur, fur_d, cream, pink = rgb("#F2A65A"), rgb("#CF7A34"), rgb("#FFE9CC"), rgb("#FF9AAE")
    side = 320
    img = new(side, side + 40)
    p = Pen(img, side / 2, side / 2)
    p.ell(0, 10, 70, 84, fur)
    p.ell(0, 30, 44, 60, cream)
    for sx in (-1, 1):
        for k in range(3):
            p.arc(sx * 50, -10 + k * 26, 18, 8, 160 if sx < 0 else 200, 200 if sx < 0 else 340, fur_d, 4)
        p.ell(sx * 30, 90, 26, 15, fur)
        for t in (-8, 0, 8):
            p.line([(sx * 30 + t, 82), (sx * 30 + t, 96)], fur_d, 2)
    body = part(finish(img), side / 2, side / 2)
    timg = new(240, 240)
    tp = Pen(timg, 120, 120)
    tp.line([(0, 0), (34, -6), (62, -40), (64, -92)], fur, 20)
    tp.ell(64, -94, 11, 11, fur_d)
    tail = tight(finish(timg), 120, 120)
    R = 62

    def base(pp):
        for sx in (-1, 1):
            pp.poly([(sx * 0.95 * R, -0.25 * R), (sx * 0.25 * R, -0.85 * R), (sx * 0.85 * R, -1.32 * R)], fur)
            pp.poly([(sx * 0.78 * R, -0.42 * R), (sx * 0.42 * R, -0.82 * R), (sx * 0.78 * R, -1.1 * R)], pink)
        pp.ell(0, 0, 1.0 * R, 0.9 * R, fur)
        for k in (-1, 0, 1):
            pp.line([(k * 0.18 * R, -0.85 * R), (k * 0.14 * R, -0.6 * R)], fur_d, 0.07 * R)
        for sx in (-1, 1):
            pp.ell(sx * 0.2 * R, 0.36 * R, 0.24 * R, 0.18 * R, cream)
        pp.poly([(-0.09 * R, 0.2 * R), (0.09 * R, 0.2 * R), (0, 0.3 * R)], pink)

    def front(pp):
        for sx in (-1, 1):
            for k in (-1, 0, 1):
                pp.line([(sx * 0.38 * R, 0.34 * R + k * 0.06 * R), (sx * 1.15 * R, 0.26 * R + k * 0.16 * R)], INK, 0.025 * R)

    hp = critter_head(R, "cat", base, front, 0.48 * R, 0.4 * R, -0.08 * R, 1.25, (60, 120, 50))
    hy = -118
    return dict(kind="cat", name="pilli", body=body, tail=tail, tailpos=(52, 50), hp=hp, head=(0.0, hy), r=R,
                hip=100, rest=52, top=-hy + 1.35 * R, C=520, mouth=(0.0, hy + 0.45 * R), hand_side=0)


def build_parrot():
    g, gd, belly = rgb("#3DBE5A"), rgb("#2A9A45"), rgb("#A6E37E")
    side = 260
    img = new(side, side + 120)
    p = Pen(img, side / 2, side / 2)
    for k, c in ((-1, (40, 120, 220)), (0, (40, 160, 80)), (1, (40, 120, 220))):
        p.poly([(k * 12 - 10, 50), (k * 12 + 10, 50), (k * 16, 170)], c)
    p.ell(0, 0, 48, 72, g)
    p.ell(0, 14, 28, 48, belly)
    for sx in (-1, 1):
        p.ell(sx * 15, 76, 11, 7, (240, 140, 60))
    body = part(finish(img), side / 2, side / 2)
    wings = {}
    for sx in (-1, 1):
        wi = new(220, 220)
        wp = Pen(wi, 110, 110)
        wp.ell(sx * 4, 50, 26, 54, gd)
        wp.ell(sx * 6, 86, 16, 22, (40, 120, 220))
        for k in range(3):
            wp.line([(sx * (-8 + k * 8), 40), (sx * (-6 + k * 9), 92)], shade(gd, 0.75), 2)
        wings[sx] = tight(finish(wi), 110, 110)
    R = 46

    def base(pp):
        pp.ell(0, 0, R, R * 0.98, g)
        pp.poly([(-0.2 * R, -0.85 * R), (0.1 * R, -1.35 * R), (0.25 * R, -0.85 * R)], g)
        for sx in (-1, 1):
            pp.ell(sx * 0.62 * R, 0.25 * R, 0.16 * R, 0.1 * R, (255, 214, 64))

    hp = critter_head(R, "parrot", base, None, 0.5 * R, 0.42 * R, -0.08 * R, 1.25, (30, 30, 30))
    hy = -98
    return dict(kind="parrot", name="chilaka", body=body, wings=wings, wingpos=(36, -38), hp=hp, head=(0.0, hy), r=R,
                hip=82, rest=40, top=-hy + 1.4 * R, C=420, mouth=(0.0, hy + 0.5 * R), hand_side=0, fly=150)


_RIGS = {}


def rig_for(name):
    if name not in _RIGS:
        if name in HUMANS:
            _RIGS[name] = build_human(name)
        elif name == "pilli":
            _RIGS[name] = build_cat()
        elif name == "chilaka":
            _RIGS[name] = build_parrot()
        else:
            _RIGS[name] = build_puppy()
    return _RIGS[name]


# ================================================================ props and small effects
def build_props():
    out = {}

    def mk(w, h, fn, grip=None, edge=2.4):
        img = new(w, h)
        fn(Pen(img, w / 2, h / 2))
        im = finish(img, edge)
        gx, gy = (w / 2, h / 2) if grip is None else (w / 2 + grip[0], h / 2 + grip[1])
        return im, gx, gy

    def tea(p):
        p.rect(-13, -22, 13, 22, (226, 238, 244), r=4)
        p.rect(-10, -12, 10, 19, (160, 92, 44), r=3)
        p.rect(-10, -15, 10, -9, (238, 214, 180), r=2)
    out["tea"] = mk(44, 64, tea)

    def tray_empty(p):
        p.ell(0, 0, 88, 15, (196, 202, 212))
        p.ell(0, -3, 76, 10, (226, 230, 236))
    tray, gx, gy = mk(200, 60, tray_empty, (0, 14))
    glass = mk(44, 64, tea)[0]
    slots = [(-40, -5), (40, -5), (-60, 3), (-20, 4), (20, 4), (60, 3)]
    full = tray.copy()
    big = Image.new("RGBA", (tray.width, tray.height + 70), (0, 0, 0, 0))
    big.alpha_composite(tray, (0, 70))
    for sx, sy in slots:
        comp(big, glass, tray.width / 2 + sx - glass.width / 2, 70 + tray.height / 2 + sy - glass.height + 6)
    out["tray"] = (big, big.width / 2, 70 + gy)
    tray_c = centered(tray, gx, gy)
    glass_c = centered(glass, glass.width / 2, 55)
    slots = [(sx, sy - 18) for sx, sy in slots]

    def kettle(p):
        p.ell(0, 8, 34, 28, (196, 202, 210))
        p.poly([(-28, 4), (-62, -22), (-56, -28), (-22, -6)], (180, 186, 194))
        p.ell(0, -18, 18, 6, (170, 176, 184))
        p.arc(0, -10, 26, 30, 200, 340, (90, 90, 96), 6)
    out["kettle"] = mk(150, 110, kettle, (0, -38))

    def banana(p):
        for ang, dx in ((-35, -16), (0, 0), (35, 16)):
            pass
        p.ell(-16, 4, 22, 8, (250, 214, 60))
        p.ell(16, 4, 22, 8, (250, 214, 60))
        p.ell(0, -2, 10, 18, (250, 214, 60))
        p.ell(0, -18, 5, 5, (120, 90, 40))
    out["banana"] = mk(90, 60, banana)

    def ball(p):
        p.ell(0, 0, 24, 24, (232, 72, 60))
        p.chord(0, 0, 24, 24, 200, 340, WHITE)
        p.line([(-22, 6), (22, 6)], (40, 100, 200), 5)
    out["ball"] = mk(60, 60, ball)

    def book(p):
        p.rect(-30, -22, 30, 22, (47, 111, 192), r=4)
        p.rect(-26, 16, 30, 22, WHITE, r=2)
        p.rect(-14, -10, 18, 2, (255, 214, 64), r=3)
    out["book"] = mk(76, 60, book)

    def mango(p):
        p.ell(0, 4, 22, 28, (255, 176, 40))
        p.ell(-6, -4, 10, 14, (255, 210, 90))
        p.ell(8, -26, 12, 5, (60, 160, 70))
    out["mango"] = mk(60, 76, mango)

    def umbrella(p):
        for i in range(6):
            p.pie(0, -70, 100, 70, 180 + i * 30, 210 + i * 30, (60, 110, 220) if i % 2 == 0 else (255, 196, 60))
        p.line([(0, -70), (0, 92)], (80, 60, 40), 5)
        p.arc(-10, 92, 10, 10, 0, 180, (80, 60, 40), 5)
    out["umbrella"] = mk(220, 220, umbrella, (0, 88))

    def phone(p):
        p.rect(-16, -30, 16, 30, (40, 40, 48), r=6)
        p.rect(-12, -24, 12, 22, (120, 200, 255), r=3)
    out["phone"] = mk(44, 72, phone)

    def kite(p):
        p.poly([(0, -150), (34, -110), (0, -60), (-34, -110)], (232, 72, 60))
        p.poly([(0, -150), (34, -110), (0, -110)], (255, 214, 64))
        p.poly([(0, -60), (-34, -110), (0, -110)], (255, 214, 64))
        p.line([(0, -60), (10, -30), (-6, 0), (8, 30), (0, 60)], (90, 70, 60), 2)
        for y in (-40, -10, 20):
            p.poly([(-8, y), (8, y), (0, y + 10)], (64, 170, 120))
    out["kite"] = mk(100, 330, kite, (0, 60), edge=1.6)

    def laddu(p):
        p.ell(0, 0, 18, 18, (245, 166, 35))
        for dx, dy in ((-6, -6), (6, -2), (-2, 7), (8, 8), (-9, 4)):
            p.ell(dx, dy, 2.4, 2.4, (214, 120, 20))
    out["laddu"] = mk(46, 46, laddu)

    def flower(p):
        p.line([(0, 0), (0, 40)], (60, 150, 70), 4)
        for k in range(10):
            a = math.radians(k * 36)
            p.ell(math.cos(a) * 12, -6 + math.sin(a) * 12, 8, 8, (255, 150, 30))
        p.ell(0, -6, 8, 8, (220, 90, 20))
    out["flower"] = mk(56, 100, flower, (0, 36))

    def bag(p):
        p.arc(0, -22, 16, 18, 180, 360, (140, 90, 50), 5)
        p.rect(-26, -22, 26, 26, (230, 190, 120), r=6)
        p.rect(-14, -6, 14, 8, (232, 72, 60), r=3)
    out["bag"] = mk(70, 90, bag, (0, -34))

    def icecream(p):
        p.poly([(-16, -6), (16, -6), (0, 38)], (222, 170, 100))
        p.line([(-8, 4), (8, 16)], (190, 140, 80), 2)
        p.ell(0, -16, 18, 16, (255, 150, 190))
        p.ell(4, -32, 5, 5, (220, 40, 60))
    out["icecream"] = mk(50, 90, icecream, (0, 26))

    def letter(p):
        p.rect(-28, -18, 28, 18, WHITE, r=3)
        p.line([(-28, -18), (0, 4), (28, -18)], (150, 150, 160), 2)
        p.rect(14, -14, 24, -4, (232, 72, 60), r=1)
    out["letter"] = mk(70, 50, letter)
    res = {}
    for k, (img, gx, gy) in out.items():
        ci, half = centered(img, gx, gy)
        hi, hhalf = centered(img, img.width / 2, gy if k == "tray" else img.height - 2)
        res[k] = dict(img=img, c=ci, half=half, head=hi, hhalf=hhalf)
    res["_tray"] = tray_c
    res["_glass"] = glass_c
    res["_slots"] = slots
    return res


def build_fx():
    fx = {}

    def mk(w, h, fn, edge=2.0):
        img = new(w, h)
        fn(Pen(img, w / 2, h / 2))
        return finish(img, edge)

    def star(p):
        pts = []
        for i in range(10):
            a = math.radians(-90 + i * 36)
            rr = 15 if i % 2 == 0 else 6.5
            pts.append((math.cos(a) * rr, math.sin(a) * rr))
        p.poly(pts, (255, 214, 64))
    fx["star"] = mk(40, 40, star)

    def heart(p):
        p.ell(-7, -4, 9, 9, (255, 80, 130))
        p.ell(7, -4, 9, 9, (255, 80, 130))
        p.poly([(-15, 0), (15, 0), (0, 16)], (255, 80, 130))
    fx["heart"] = mk(40, 40, heart)

    def drop(p):
        p.poly([(0, -14), (-8, 2), (8, 2)], (110, 198, 255))
        p.ell(0, 4, 8, 8, (110, 198, 255))
        p.ell(-2, 3, 2.5, 2.5, WHITE)
    fx["drop"] = mk(26, 36, drop, 1.6)

    def note(p):
        p.ell(-6, 12, 8, 6, (110, 60, 180))
        p.line([(1, 12), (1, -16)], (110, 60, 180), 3.5)
        p.line([(1, -16), (12, -10), (12, -2)], (110, 60, 180), 3.5)
    fx["note"] = mk(36, 46, note, 1.6)
    fx["Z"] = text_img("Z", 34, (80, 120, 220), WHITE, 3)
    fx["!"] = text_img("!", 72, (232, 60, 50), WHITE, 5)
    fx["?"] = text_img("?", 64, (240, 160, 30), WHITE, 5)

    def speed(p):
        p.rect(-48, -4, 48, 4, (255, 255, 255, 255), r=4)
    fx["speed"] = mk(110, 16, speed, 0)
    return fx


# ================================================================ sounds (made here, no files needed)
class Track:
    def __init__(self, secs):
        self.a = array.array("f", bytes(4 * (int(secs * SR) + SR)))

    def add(self, t, samples, vol=1.0):
        a = self.a
        i0 = int(t * SR)
        n = min(len(samples), len(a) - i0)
        for k in range(max(0, -i0), n):
            a[i0 + k] += samples[k] * vol

    def write(self, path):
        peak = max(0.05, max(abs(min(self.a)), abs(max(self.a))))
        g = 0.85 / peak if peak > 0.85 else 1.0
        data = array.array("h", (int(clamp(v * g, -1, 1) * 32000) for v in self.a))
        if sys.byteorder == "big":
            data.byteswap()
        with wave.open(path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes(data.tobytes())


def tone(dur, f0, f1=None, kind="sine", attack=0.005, release=0.05, vib=0.0, vibf=0.0, decay=0.0):
    n = int(dur * SR)
    out = [0.0] * n
    ph = 0.0
    f1 = f0 if f1 is None else f1
    for i in range(n):
        t = i / SR
        f = f0 + (f1 - f0) * (i / n)
        if vib:
            f += vib * math.sin(2 * math.pi * vibf * t) * (math.exp(-decay * t) if decay else 1)
        ph += 2 * math.pi * f / SR
        v = math.sin(ph)
        if kind == "sq":
            v = v + 0.33 * math.sin(3 * ph) + 0.18 * math.sin(5 * ph)
        env = min(1.0, t / attack) * min(1.0, (dur - t) / release)
        if decay and not vib:
            env *= math.exp(-decay * t)
        out[i] = v * env
    return out


def noise(dur, lp=0.15, attack=0.05, release=0.2, seed=1):
    rnd = random.Random(seed)
    n = int(dur * SR)
    out = [0.0] * n
    y = 0.0
    for i in range(n):
        t = i / SR
        y += lp * (rnd.uniform(-1, 1) - y)
        out[i] = y * min(1.0, t / attack) * min(1.0, (dur - t) / release) * 3
    return out


def add_sfx(tr, t, kind, pitch=300):
    if kind == "step":
        tr.add(t, tone(0.05, 150, 90, attack=0.002, release=0.04), 0.12)
    elif kind == "whoosh":
        tr.add(t, noise(0.36, 0.12, 0.12, 0.2, seed=int(t * 100)), 0.5)
    elif kind == "boing":
        tr.add(t, tone(0.5, 260, 300, vib=130, vibf=11, decay=5, release=0.2), 0.45)
    elif kind == "land":
        tr.add(t, tone(0.14, 110, 60, attack=0.002, release=0.1), 0.45)
    elif kind == "slide":
        tr.add(t, tone(0.5, 1400, 330, vib=25, vibf=14, release=0.1), 0.32)
    elif kind == "thud":
        tr.add(t, tone(0.3, 95, 42, attack=0.002, release=0.25), 0.9)
        tr.add(t, noise(0.16, 0.06, 0.004, 0.12, seed=7), 0.5)
    elif kind == "ting":
        tr.add(t, tone(0.55, 2093, attack=0.002, release=0.5, decay=6), 0.22)
        tr.add(t, tone(0.45, 3136, attack=0.002, release=0.4, decay=8), 0.1)
    elif kind == "pop":
        tr.add(t, tone(0.08, 600, 1200, attack=0.002, release=0.05), 0.4)
    elif kind == "ding":
        for k, f in enumerate((1047, 1319, 1568)):
            tr.add(t + k * 0.09, tone(0.5, f, attack=0.003, release=0.45, decay=5), 0.2)
    elif kind == "tada":
        for k, f in enumerate((784, 988, 1175, 1568)):
            tr.add(t + k * 0.11, tone(0.6 if k == 3 else 0.2, f, kind="sq", attack=0.004, release=0.15), 0.13)
    elif kind == "laugh":
        for k in range(6):
            f = pitch * (1.25 if k % 2 == 0 else 1.1) * (1 - k * 0.025)
            tr.add(t + k * 0.13, tone(0.09, f, f * 0.9, kind="sq", attack=0.004, release=0.05), 0.2)
            tr.add(t + k * 0.13, noise(0.07, 0.4, 0.003, 0.05, seed=k), 0.05)
    elif kind == "snore":
        tr.add(t, noise(0.9, 0.03, 0.35, 0.4, seed=int(t * 10)), 0.6)
        tr.add(t, tone(0.9, 75, 70, attack=0.3, release=0.4), 0.15)
    elif kind == "sob":
        for k in range(4):
            tr.add(t + k * 0.22, tone(0.16, pitch * 1.3, pitch * 0.9, kind="sq", attack=0.01, release=0.1), 0.13)
    elif kind == "chomp":
        tr.add(t, noise(0.06, 0.5, 0.002, 0.04, seed=3), 0.35)
    elif kind == "tweet":
        for k in range(6):
            tr.add(t + k * 0.16, tone(0.08, 2600, 3200, attack=0.003, release=0.04), 0.07)
    elif kind == "hmm":
        tr.add(t, tone(0.5, pitch * 0.7, pitch * 0.75, kind="sq", attack=0.05, release=0.2, vib=4, vibf=5), 0.12)
    elif kind == "clap":
        tr.add(t, noise(0.04, 0.7, 0.001, 0.03, seed=int(t * 50)), 0.35)


def add_blips(tr, t0, t1, pitch, seed):
    rnd = random.Random(seed)
    t = t0
    while t < t1:
        f = pitch * rnd.uniform(0.85, 1.25)
        tr.add(t, tone(0.055, f, f * rnd.uniform(0.9, 1.1), kind="sq", attack=0.004, release=0.03), 0.13)
        t += rnd.uniform(0.085, 0.13)


def read_wav(path):
    try:
        with wave.open(path, "rb") as w:
            if w.getsampwidth() != 2:
                return None
            n = w.getnframes()
            raw = w.readframes(n)
            ch = w.getnchannels()
            rate = w.getframerate()
        a = array.array("h")
        a.frombytes(raw)
        if sys.byteorder == "big":
            a.byteswap()
        if ch > 1:
            a = a[::ch]
        if rate != SR:
            step = rate / SR
            a = array.array("h", (a[int(i * step)] for i in range(int(len(a) / step))))
        return [v / 32768.0 for v in a]
    except Exception:
        return None


# ================================================================ the story, frame by frame
def read_secs(text):
    n = 0.0
    for ch in text:
        if "అ" <= ch <= "హ" or "ౘ" <= ch <= "ౡ":
            n += 1
        elif ch.isalnum():
            n += 0.35
    return clamp(0.8 + n * 0.11, 1.5, 6.0)


LEAD = {"talk": 0.2, "exit": 0.2, "run": 1.3, "jump": 0.95, "slip": 2.3, "fall": 1.6, "laugh": 0.15, "surprised": 0.55,
        "think": 0.9, "dance": 1.3, "sleep": 0.25, "cry": 0.3, "wave": 0.3, "eat": 1.25, "balance": 1.0, "enter": 0.0,
        "give": 0.0, "point": 0.2, "clap": 0.3, "shrug": 0.35, "facepalm": 0.45, "crossarms": 0.3, "cheer": 0.25,
        "kneel": 0.7, "angry": 0.3, "shy": 0.3}
MINB = {"laugh": 1.5, "dance": 2.0, "cry": 1.5, "wave": 1.3, "think": 1.2, "sleep": 0.4, "balance": 1.4, "jump": 0.3,
        "clap": 1.3, "cheer": 1.3, "angry": 1.3, "shy": 1.2, "facepalm": 1.0, "kneel": 0.6}
SLIP_WALK, SLIP_SLIDE = 0.75, 0.3


class Actor:
    def __init__(self, name, home, idx):
        self.name, self.home, self.idx = name, home, idx
        self.rig = rig_for(name)
        self.phase = random.random() * 6
        self.blink_every = 2.6 + random.random() * 1.8
        self.pitch = PITCH.get(name, 260)
        self.off = -190 if home <= 360 else W + 190


class Story:
    def __init__(self, plan, ground):
        self.plan, self.ground = plan, ground
        self.funny = plan.get("mood", "funny") in ("funny", "happy")
        cast = [c for c in plan["cast"] if c in CHARS][:3] or ["bigd"]
        slots = {1: [360], 2: [210, 510], 3: [130, 360, 590]}[len(cast)]
        self.actors = [Actor(n, slots[i], i) for i, n in enumerate(cast)]
        self.by = {a.name: a for a in self.actors}
        self.beats, self.fx, self.sfx = [], [], []
        self._pc = {}
        self.build()

    # ---------------------------------------------------------- planning the beats
    def build(self):
        st = {a.name: dict(x=a.home, on=True, held=None, head=None, asleep=False) for a in self.actors}
        first = {}
        for b in self.plan["beats"]:
            first.setdefault(b.get("who"), b.get("do"))
        for a in self.actors:
            if first.get(a.name) == "enter":
                st[a.name].update(x=a.off, on=False)
        t = INTRO
        raw = [b for b in self.plan["beats"] if b.get("who") in self.by][:8]
        for i, rb in enumerate(raw):
            a = self.by[rb["who"]]
            s = st[a.name]
            do = rb.get("do") if rb.get("do") in ACTIONS else "talk"
            say = str(rb.get("say") or "").strip()
            prop = rb.get("prop") if rb.get("prop") in PROPS else None
            if not s["on"] and do != "enter":
                do = "enter"
            elif s["on"] and do == "enter":
                do = "talk"
            if do in ("give",) and not [o for o in self.actors if o.name != a.name and st[o.name]["on"]]:
                do = "talk"
            if self.plan.get("type") == "riddle" and i == len(raw) - 1 and i > 0:
                t += 0.9
            b = dict(i=i, who=a.name, do=do, say=say, prop=prop, t0=t, before={k: dict(v) for k, v in st.items()})
            voice = read_wav(rb["voice"]) if rb.get("voice") else None
            b["voice"] = voice
            talk = (len(voice) / SR + 0.25) if voice else (read_secs(say) if say else 0.0)
            dirn = 1 if s["x"] <= 360 else -1
            lead, post = LEAD[do], 0.0
            if do == "enter":
                b["x0"], b["x1"] = s["x"], a.home
                lead = abs(a.home - s["x"]) / 380 + 0.2
                dirn = 1 if a.home > s["x"] else -1
                s["x"], s["on"] = a.home, True
            elif do == "exit":
                off = -190 if s["x"] <= 360 else W + 190
                b["x0"], b["x1"] = s["x"], off
                post = abs(off - s["x"]) / 380 + 0.1
                dirn = 1 if off > s["x"] else -1
            elif do == "give":
                others = [o for o in self.actors if o.name != a.name and st[o.name]["on"]]
                nxt = raw[i + 1].get("who") if i + 1 < len(raw) else None      # usually the one who speaks next gets it
                tg = next((o for o in others if o.name == nxt), None) or min(others, key=lambda o: abs(st[o.name]["x"] - s["x"]))
                tx = st[tg.name]["x"]
                dirn = 1 if tx > s["x"] else -1
                stop = tx - dirn * (110 + 60 * max(a.rig.get("s", 0.7), tg.rig.get("s", 0.7)))
                if (stop - s["x"]) * dirn < 0:
                    stop = s["x"]
                walk = abs(stop - s["x"]) / 300
                b.update(target=tg.name, x0=s["x"], x1=stop, walk=walk)
                gift = prop or s["held"] or s["head"] or "laddu"
                b["gift"] = gift
                lead = walk + 0.6
                post = walk
            elif do == "slip":
                dirn = -1 if s["x"] < 360 else 1          # walks outwards, so the head falls towards the middle
                b["peel"] = prop if prop in GROUND else "banana"
                b["fly"] = s["head"] or s["held"] or (prop if prop and prop not in GROUND else None)
                b["px"] = s["x"] + dirn * 75
                b["lx"] = b["px"] + dirn * 35
                b["x0"] = b["x1"] = s["x"]                # gets up and walks back
                b["back"] = abs(b["lx"] - s["x"]) / 300
                post = 0.75 + b["back"]
            elif do == "fall":
                dirn = -1 if s["x"] < 360 else 1
                b["fly"] = s["head"] or s["held"] or (prop if prop and prop not in GROUND else None)
                b["x0"] = b["x1"] = b["lx"] = s["x"]
                post = 0.75
            elif do == "balance":
                b["prop"] = prop or (s["head"] if s["head"] else s["held"]) or ("tray" if a.name in ("chaiwala", "chai") else "book")
            elif do == "eat":
                b["prop"] = prop if prop in ("laddu", "mango", "icecream", "banana", "tea") else (
                    s["held"] if s["held"] in ("laddu", "mango", "icecream", "banana", "tea") else "laddu")
            elif do == "sleep":
                post = 1.8
            body = max(talk, MINB.get(do, 0.0))
            b.update(dirn=dirn, lead=lead, talk=talk, tl=t + lead, tt=t + lead + talk, tb=t + lead + body, post=post)
            b["t1"] = t + lead + body + GAP + post
            # what stays after this beat
            if do in ("slip", "fall"):
                if b.get("fly"):
                    if s["held"] == b["fly"]:
                        s["held"] = None
                    s["head"] = b["fly"]
                s["x"] = b["x1"]
            elif do == "exit":
                s["x"], s["on"] = b["x1"], False
            elif do == "give":
                st[b["target"]]["held"] = b["gift"]
                if s["held"] == b["gift"]:
                    s["held"] = None
                if s["head"] == b["gift"]:
                    s["head"] = None
            elif do == "balance":
                if s["held"] == b["prop"]:
                    s["held"] = None
                s["head"] = b["prop"]
            elif do == "eat":
                if s["held"] == b["prop"]:
                    s["held"] = None
            elif prop and prop != s["head"]:
                s["held"] = prop
            s["asleep"] = do == "sleep"
            if do != "sleep":
                for o in self.actors:
                    if o.name != a.name and st[o.name]["asleep"] and do in ("jump", "slip", "fall", "laugh", "surprised", "dance"):
                        st[o.name]["asleep"] = False
            self.beats.append(b)
            t = b["t1"]
        self.after = st
        self.fin0 = t
        self.total = min(75.0, t + FIN + END)
        self.end0 = self.total - END
        self.plan_effects()

    def beat_at(self, t):
        for b in self.beats:
            if b["t0"] <= t < b["t1"]:
                return b
        return None

    # ---------------------------------------------------------- poses
    def pose(self, a, t):
        key = (a.name, round(t, 4))
        if key in self._pc:
            return self._pc[key]
        P = self._pose(a, t)
        if len(self._pc) > 4000:
            self._pc.clear()
        self._pc[key] = P
        return P

    def _pose(self, a, t):
        b = self.beat_at(t)
        if b is not None:
            st = b["before"][a.name]
        elif self.beats and t < self.beats[0]["t0"]:
            st = self.beats[0]["before"][a.name]
        else:
            st = self.after[a.name]
        P = dict(x=st["x"], on=st["on"], lift=0.0, rot=0.0, sx=1.0, sy=1.0, legL=0.0, legR=0.0, armL=9.0, armR=9.0,
                 tilt=0.0, eyes="open", mouth="closed", look=0, held=st["held"], head=st["head"], arms_front=False,
                 flap=0.0, tail=0.0, pdx=0.0, pdy=0.0, pscale=1.0, hand=1, headwob=0.0)
        P["lift"] = 2.0 * math.sin(2 * math.pi * 0.45 * t + a.phase)
        P["armL"] += 2 * math.sin(2 * math.pi * 0.45 * t + a.phase + 1)
        P["armR"] += 2 * math.sin(2 * math.pi * 0.45 * t + a.phase + 2)
        P["tail"] = 12 * math.sin(2 * math.pi * 1.1 * t + a.phase)
        P["flap"] = 6 * math.sin(2 * math.pi * 2.0 * t + a.phase)
        if (t + a.phase * 3) % a.blink_every < 0.13:
            P["eyes"] = "blink"
        if a.rig["kind"] == "parrot":
            P["lift"] += a.rig["fly"] + 14 * math.sin(2 * math.pi * 0.8 * t + a.phase)
            P["flap"] = 28 * abs(math.sin(2 * math.pi * 3.2 * t + a.phase))
        others = [o for o in self.actors if o is not a]
        if b is not None and b["who"] != a.name:
            sx = b["before"][b["who"]]["x"]
            P["look"] = 0 if abs(sx - P["x"]) < 5 else (1 if sx > P["x"] else -1)
        elif others:
            near = min(others, key=lambda o: abs(o.home - P["x"]))
            P["look"] = 1 if near.home > P["x"] else -1
        if b is None:
            if self.beats and t >= self.fin0 and t < self.fin0 + FIN and P["on"]:
                if self.funny:
                    self.laugh_pose(a, P, t - self.fin0)
                else:
                    P["eyes"], P["mouth"] = "happy", "smile"
                    self.set_arm(a, P, 140 + 20 * math.sin(2 * math.pi * 2.6 * (t - self.fin0)), side=1)
            elif t >= self.fin0 + FIN and P["on"]:
                P["mouth"] = "smile" if P["eyes"] != "blink" else "closed"
        elif b["who"] == a.name:
            self.act(a, b, t, P)
        else:
            self.react(a, b, t, P)
        if st["asleep"] and not (b is not None and b["who"] == a.name):
            P["eyes"], P["tilt"], P["mouth"] = "sleep", 12.0, "closed"
        return P

    def set_arm(self, a, P, ang, side=1):
        if side > 0:
            P["armR"] = ang
        else:
            P["armL"] = ang

    def walk_pose(self, a, P, u, dirn, speed=1.0):
        f = 2.3 * speed
        ph = 2 * math.pi * f * u
        if a.rig["kind"] == "human":
            sw = (20 if speed < 1.5 else 34) * math.sin(ph)
            P["legL"] = P["legR"] = sw
            P["armL"] = P["armR"] = 8 - sw * 0.85
            P["lift"] = (5 if speed < 1.5 else 9) * abs(math.sin(ph))
        else:
            P["lift"] += 18 * abs(math.sin(ph))
            P["tilt"] = 7 * math.sin(ph)
            P["flap"] = 35 * abs(math.sin(2 * ph))
        P["look"] = dirn

    def flap_mouth(self, b, t):
        v = b.get("voice")
        if v:
            i0 = int((t - b["tl"]) * SR)
            seg = v[max(0, i0): max(0, i0) + SR // FPS]
            if not seg:
                return "closed"
            rms = math.sqrt(sum(x * x for x in seg[::4]) / max(1, len(seg[::4])))
            return "open" if rms > 0.14 else "half" if rms > 0.04 else "closed"
        k = int((t - b["tl"]) / 0.09)
        pat = ("half", "open", "half", "closed", "open", "half", "closed", "open")
        return pat[(k * 5 + b["i"] * 3) % 8]

    def talking(self, b, t):
        if not b["say"]:
            return False
        end = b["tl"] + (b["talk"] - 0.25 if b.get("voice") else b["talk"] * 0.8)
        return b["tl"] <= t < end

    def laugh_pose(self, a, P, u):
        P["eyes"] = "happy"
        P["mouth"] = "smile" if int(u * 7) % 2 == 0 else "open"
        P["rot"] = 3.5 * math.sin(2 * math.pi * 7 * u)
        P["lift"] += 4 * abs(math.sin(2 * math.pi * 3.5 * u))
        P["armL"] = P["armR"] = -12

    def act(self, a, b, t, P):
        d, u = b["do"], t - b["t0"]
        human = a.rig["kind"] == "human"
        side = 1 if b["dirn"] > 0 else -1
        if b["prop"] and d in ("talk", "enter", "exit", "run", "jump", "laugh", "surprised", "think", "dance", "wave", "cry", "sleep"):
            if P["head"] != b["prop"]:
                P["held"] = b["prop"]
        if d == "enter":
            walk = b["lead"] - 0.2
            if u < walk:
                P["x"] = lerp(b["x0"], b["x1"], u / max(0.01, walk))
                P["on"] = True
                self.walk_pose(a, P, u, b["dirn"])
            else:
                P["x"], P["on"] = b["x1"], True
        elif d == "exit":
            ts = b["tb"] + 0.15
            if t >= ts:
                k = (t - ts) / max(0.01, b["post"] - 0.1)
                P["x"] = lerp(b["x0"], b["x1"], clamp(k, 0, 1))
                self.walk_pose(a, P, t - ts, b["dirn"])
                P["look"] = b["dirn"]
        elif d == "run":
            if u < b["lead"]:
                self.walk_pose(a, P, u, b["dirn"], speed=1.9)
                P["x"] = b["before"][a.name]["x"] + b["dirn"] * 70 * math.sin(math.pi * u / b["lead"])
                P["rot"] = -9 * b["dirn"] if human else -5 * b["dirn"]
            else:
                P["mouth"] = "open" if int(u * 4) % 2 == 0 else "half"
        elif d == "jump":
            if u < 0.15:
                k = u / 0.15
                P["sy"], P["sx"] = 1 - 0.1 * k, 1 + 0.06 * k
                P["armL"] = P["armR"] = 8 - 12 * k
            elif u < 0.75:
                k = (u - 0.15) / 0.6
                P["lift"] += 160 * math.sin(math.pi * k)
                P["armL"] = P["armR"] = 150
                P["legL"] = P["legR"] = 14
                P["eyes"], P["mouth"] = "happy", "smile"
                P["flap"] = 40 * abs(math.sin(2 * math.pi * 6 * u))
            elif u < 0.9:
                k = (u - 0.75) / 0.15
                P["sy"], P["sx"] = 1 - 0.08 * (1 - k), 1 + 0.05 * (1 - k)
        elif d in ("slip", "fall"):
            self.fall_pose(a, b, t, P)
        elif d == "laugh":
            if t < b["tb"] + 0.2:
                self.laugh_pose(a, P, u)
        elif d == "surprised":
            if u < 0.45:
                P["lift"] += 45 * math.sin(math.pi * u / 0.45)
                P["armL"] = P["armR"] = 55
            if t < b["tb"]:
                P["eyes"] = "wide"
                P["mouth"] = "o"
        elif d == "think":
            if t < b["tb"]:
                P["arms_front"] = True
                self.set_arm(a, P, 150, 1)
                P["tilt"] = 8
                P["look"] = 0
        elif d == "dance":
            if t < b["tb"]:
                w = 2 * math.pi * 1.5 * u
                P["armL"], P["armR"] = 75 + 60 * math.sin(w), 75 - 60 * math.sin(w)
                P["legL"] = P["legR"] = 14 * math.sin(w)
                P["rot"] = 7 * math.sin(w / 2)
                P["lift"] += 10 * abs(math.sin(w))
                P["eyes"], P["mouth"] = "happy", "smile"
                P["tail"] = 30 * math.sin(2 * w)
                P["flap"] = 40 * abs(math.sin(w))
        elif d == "sleep":
            if t >= b["tt"] + 0.1:
                P["eyes"], P["tilt"] = "sleep", 14.0
                P["armL"] = P["armR"] = 5
        elif d == "cry":
            if t < b["tb"]:
                P["eyes"], P["mouth"] = "sleep", "sad"
                P["lift"] += 2 * math.sin(2 * math.pi * 5 * u)
                P["arms_front"] = True
                P["armL"] = P["armR"] = 158
        elif d == "wave":
            if t < b["tb"]:
                self.set_arm(a, P, 140 + 22 * math.sin(2 * math.pi * 2.6 * u), -side if b["before"][a.name]["x"] > 360 else 1)
                P["eyes"] = "happy" if int(u * 2) % 3 == 0 else P["eyes"]
                P["mouth"] = "smile"
                P["tail"] = 35 * math.sin(2 * math.pi * 4 * u)
        elif d == "give":
            walk = b["walk"]
            hand_t = b["t0"] + walk + 0.42
            hs = 1 if b["dirn"] > 0 else -1
            P["hand"] = hs
            P["held"] = b["gift"] if t < hand_t else None
            if P["head"] == b["gift"]:
                P["head"] = None
            if u < walk:
                P["x"] = lerp(b["x0"], b["x1"], ease_io(u / max(0.01, walk)))
                self.walk_pose(a, P, u, b["dirn"])
            elif t < b["tb"] + GAP * 0.4:
                P["x"] = b["x1"]
                if u < walk + 0.6:
                    self.set_arm(a, P, 78, hs)
                P["eyes"] = "happy" if t > hand_t else P["eyes"]
                P["mouth"] = "smile"
            else:
                k = (t - (b["tb"] + GAP * 0.4)) / max(0.01, b["post"] + GAP * 0.6)
                P["x"] = lerp(b["x1"], b["x0"], ease_io(k))
                self.walk_pose(a, P, t - b["tb"], -b["dirn"])
        elif d == "eat":
            p = b["prop"]
            if u < 1.2:
                P["held"] = p
                P["hand"] = 1
                if human:
                    P["arms_front"] = True
                    P["armR"] = 150
                    hx, hy = hand_local(a.rig, P)
                    mx, my = a.rig["mouth"]
                    k = clamp(u / 0.3, 0, 1)
                    P["pdx"], P["pdy"] = (mx - hx) * k, (my - hy) * k
                P["pscale"] = 1 - 0.75 * clamp((u - 0.3) / 0.9, 0, 1)
                P["mouth"] = "open" if int(u * 5) % 2 == 0 else "closed"
            else:
                P["held"] = None
                P["eyes"] = "happy"
                P["mouth"] = "smile"
        elif d == "balance":
            P["head"] = b["prop"]
            if P["held"] == b["prop"]:
                P["held"] = None
            w = 2 * math.pi * u
            P["armL"] = 80 + 9 * math.sin(1.3 * w + 1)
            P["armR"] = 80 + 9 * math.sin(1.3 * w)
            P["rot"] = 3.5 * math.sin(0.9 * w)
            P["headwob"] = 8 * math.sin(1.1 * w)
            P["eyes"] = "wide" if u < 0.6 else P["eyes"]
        # talking
        if self.talking(b, t) and d not in ("cry",) or (d == "cry" and self.talking(b, t) and int(u * 6) % 2 == 0):
            m = self.flap_mouth(b, t)
            if P["eyes"] == "happy" and m == "closed":
                m = "smile"
            P["mouth"] = m
            if human and d in ("talk", "enter", "give", "exit", "surprised", "sleep", "wave") and P["armR"] < 60 and P["armL"] < 60:
                g = 18 + 26 * max(0.0, math.sin(2 * math.pi * 0.9 * (t - b["tl"])))
                self.set_arm(a, P, g, P["look"] or 1)
                P["tilt"] += 3 * math.sin(2 * math.pi * 1.1 * (t - b["tl"]))
        elif d == "talk" and t >= b["tt"]:
            P["mouth"] = "closed"

    def fall_pose(self, a, b, t, P):
        u = t - b["t0"]
        dirn = b["dirn"]
        if b["do"] == "slip":
            walk, imp = SLIP_WALK, SLIP_WALK + SLIP_SLIDE
            x0, px = b["x0"], b["px"]
            if u < walk:
                P["x"] = lerp(x0, px - dirn * 25, u / walk)
                self.walk_pose(a, P, u, dirn)
                return
            if u < imp:
                k = (u - walk) / SLIP_SLIDE
                P["x"] = px - dirn * 25 + dirn * 60 * k
                P["rot"] = 88 * dirn * ease_in(k)
                P["lift"] += 40 * math.sin(math.pi * k)
                P["legL"] = P["legR"] = 22
                P["armL"] = P["armR"] = 140
                P["eyes"], P["mouth"] = "wide", "o"
                return
            lx = b["lx"]
        else:
            imp = 0.45
            lx = b["x0"]
            if u < imp:
                k = u / imp
                P["rot"] = 88 * dirn * ease_in(k)
                P["lift"] += 25 * math.sin(math.pi * k)
                P["armL"] = P["armR"] = 120
                P["eyes"], P["mouth"] = "wide", "o"
                return
        P["x"] = lx
        tup = b["tb"] + 0.15
        if t < tup:
            P["rot"] = 88 * dirn + 2 * math.sin(2 * math.pi * 3 * u)
            P["armL"] = P["armR"] = 60
            P["legL"] = P["legR"] = 10
            P["eyes"] = "sleep" if u < imp + 0.5 else ("wide" if not self.talking(b, t) else "open")
            P["mouth"] = "o" if not self.talking(b, t) else P["mouth"]
        else:
            k = (t - tup) / 0.5
            if k <= 1:
                P["rot"] = 88 * dirn * (1 - ease_out(k))
                P["lift"] += 10 * math.sin(math.pi * clamp(k, 0, 1))
            elif b.get("back", 0) > 0.05:
                kk = clamp((t - tup - 0.5) / b["back"], 0, 1)
                P["x"] = lerp(lx, b["x0"], ease_io(kk))
                if kk < 1:
                    self.walk_pose(a, P, t - tup, -dirn)
        fl = b.get("fly")
        if fl:
            l0, l1 = self.fly_times(b)
            if t < l0:
                pass
            elif t < l1 + (0.12 * 7 if fl == "tray" else 0):
                if P["held"] == fl:
                    P["held"] = None
                if P["head"] == fl:
                    P["head"] = None
            else:
                P["head"] = fl
                if P["held"] == fl:
                    P["held"] = None

    def fly_times(self, b):
        if b["do"] == "slip":
            return b["t0"] + SLIP_WALK + SLIP_SLIDE * 0.3, b["t0"] + SLIP_WALK + SLIP_SLIDE + 0.55
        return b["t0"] + 0.15, b["t0"] + 0.45 + 0.55

    def react(self, a, b, t, P):
        d, u = b["do"], t - b["t0"]
        if d in ("slip", "fall"):
            imp = b["t0"] + (SLIP_WALK + SLIP_SLIDE if d == "slip" else 0.45)
            if imp <= t < imp + 0.75:
                P["eyes"], P["mouth"] = "wide", "o"
                P["lift"] += 10 * math.sin(math.pi * (t - imp) / 0.75)
                P["armL"] = P["armR"] = 40
            elif t >= imp + 0.75 and self.funny:
                self.laugh_pose(a, P, t - imp)
        elif d == "laugh" and self.funny and t < b["tb"]:
            P["eyes"], P["mouth"] = "happy", "smile"
            P["rot"] = 2 * math.sin(2 * math.pi * 6 * u)
        elif d == "cry" and t < b["tb"]:
            P["eyes"], P["mouth"] = "sad", "sad"
        elif d == "dance" and t < b["tb"]:
            P["eyes"], P["mouth"] = "happy", "smile"
            c = math.sin(2 * math.pi * 2.0 * u)
            P["armL"] = P["armR"] = -20 + 22 * c
            P["arms_front"] = True
            P["lift"] += 4 * abs(c)
        elif d == "surprised" and u < 0.8:
            P["eyes"] = "wide"
        elif d == "jump" and 0.75 <= u < 1.4:
            P["eyes"], P["mouth"] = "happy", "smile"
        elif d == "give" and a.name == b.get("target"):
            hand_t = b["t0"] + b["walk"] + 0.42
            giver_x = b["x1"]
            hs = 1 if giver_x > P["x"] else -1
            P["hand"] = hs
            if t >= b["t0"] + b["walk"]:
                if t < hand_t + 0.5:
                    self.set_arm(a, P, 70, hs)
                P["held"] = b["gift"] if t >= hand_t else P["held"]
                if t >= hand_t:
                    P["eyes"], P["mouth"] = "happy", "smile"
        elif d == "balance" and t < b["tb"]:
            P["eyes"] = "wide" if u < 1.2 else P["eyes"]

    # ---------------------------------------------------------- effects and sounds
    def plan_effects(self):
        fx, sx = self.fx, self.sfx
        sx.append((0.15, "pop", 0))
        for bi, b in enumerate(self.beats):
            a = self.by[b["who"]]
            d, t0 = b["do"], b["t0"]
            prev0 = self.beats[bi - 1]["t0"] if bi > 0 else 0.3
            if b["say"] and not b.get("voice"):
                talk_end = b["tl"] + b["talk"] * 0.78
                sx.append((b["tl"], "blips", (talk_end, a.pitch, b["i"])))
            if d in ("enter",):
                for k in range(int((b["lead"] - 0.2) / 0.22)):
                    sx.append((t0 + k * 0.22, "step", 0))
            if d == "exit":
                ts = b["tb"] + 0.15
                for k in range(int(b["post"] / 0.22)):
                    sx.append((ts + k * 0.22, "step", 0))
            if d == "run":
                sx.append((t0, "whoosh", 0))
                fx.append(dict(k="speed", a=a.name, t0=t0, t1=t0 + b["lead"], dirn=b["dirn"]))
                fx.append(dict(k="sweat", a=a.name, t0=t0 + b["lead"], t1=b["tb"]))
                for k in range(int(b["lead"] / 0.12)):
                    sx.append((t0 + k * 0.12, "step", 0))
            if d == "jump":
                sx.append((t0 + 0.15, "boing", 0))
                sx.append((t0 + 0.75, "land", 0))
                fx.append(dict(k="dust", x=b["before"][a.name]["x"], t0=t0 + 0.75, t1=t0 + 1.25))
            if d in ("slip", "fall"):
                imp = t0 + (SLIP_WALK + SLIP_SLIDE if d == "slip" else 0.45)
                if d == "slip":
                    for k in range(3):
                        sx.append((t0 + k * 0.24, "step", 0))
                    sx.append((t0 + SLIP_WALK, "slide", 0))
                    fx.append(dict(k="ground", prop=b["peel"], x=b["px"], t0=prev0, t1=b["t1"], imp=imp, dirn=b["dirn"]))
                sx.append((imp, "thud", 0))
                sx.append((imp + 0.25, "tweet", 0))
                fx.append(dict(k="dust", x=b["lx"] + b["dirn"] * 20, t0=imp, t1=imp + 0.55))
                fx.append(dict(k="stars", a=a.name, t0=imp + 0.05, t1=b["tb"] + 0.15))
                if b.get("fly"):
                    l0, l1 = self.fly_times(b)
                    fx.append(dict(k="fly", a=a.name, prop=b["fly"], t0=l0, t1=l1 + (0.12 * 7 if b["fly"] == "tray" else 0) + 0.02, l0=l0, l1=l1))
                    sx.append((l1, "ting" if b["fly"] == "tray" else "pop", 0))
                    if b["fly"] == "tray":
                        for k in range(6):
                            sx.append((l1 + 0.12 * (k + 1), "ting", 0))
                if self.funny:
                    for o in self.actors:
                        if o is not a and b["before"][o.name]["on"]:
                            sx.append((imp + 0.8 + 0.1 * o.idx, "laugh", o.pitch))
            if d == "laugh":
                sx.append((t0 + 0.05, "laugh", a.pitch))
                if b["tb"] - t0 > 1.4:
                    sx.append((t0 + 0.95, "laugh", a.pitch))
                if self.funny:
                    for o in self.actors:
                        if o is not a and b["before"][o.name]["on"]:
                            sx.append((t0 + 0.35 + 0.1 * o.idx, "laugh", o.pitch))
            if d == "surprised":
                sx.append((t0 + 0.05, "pop", 0))
                fx.append(dict(k="!", a=a.name, t0=t0 + 0.05, t1=t0 + 1.3))
            if d == "think":
                sx.append((t0 + 0.1, "hmm", a.pitch))
                fx.append(dict(k="?", a=a.name, t0=t0 + 0.2, t1=b["tb"]))
            if d == "dance":
                sx.append((t0, "ding", 0))
                fx.append(dict(k="notes", a=a.name, t0=t0, t1=b["tb"]))
                for o in self.actors:
                    if o is not a and b["before"][o.name]["on"]:
                        for k in range(int((b["tb"] - t0) / 0.5)):
                            sx.append((t0 + 0.25 + k * 0.5, "clap", 0))
            if d == "sleep":
                zs = b["tt"] + 0.1
                fx.append(dict(k="zzz", a=a.name, t0=zs, t1=b["t1"]))
                for k in range(int(max(0, b["t1"] - zs) / 1.3) + 1):
                    sx.append((zs + 0.2 + k * 1.3, "snore", 0))
            if d == "cry":
                fx.append(dict(k="tears", a=a.name, t0=t0, t1=b["tb"]))
                sx.append((t0 + 0.1, "sob", a.pitch))
            if d == "eat":
                for k in range(4):
                    sx.append((t0 + 0.35 + k * 0.22, "chomp", 0))
                sx.append((t0 + 1.25, "ding", 0))
                fx.append(dict(k="hearts", a=a.name, t0=t0 + 1.2, t1=t0 + 2.6))
            if d == "give":
                hand_t = t0 + b["walk"] + 0.42
                sx.append((hand_t, "pop", 0))
                fx.append(dict(k="hearts", a=b["target"], t0=hand_t + 0.1, t1=hand_t + 1.6))
                for k in range(int(b["walk"] / 0.22)):
                    sx.append((t0 + k * 0.22, "step", 0))
            if d == "balance":
                fx.append(dict(k="sweat", a=a.name, t0=t0 + 0.4, t1=b["tb"]))
                sx.append((t0 + 0.1, "hmm", a.pitch * 1.3))
            if d == "enter" and b["prop"]:
                pass
        # sleeping actors keep their Zzz in later beats
        for b in self.beats:
            for name, st in b["before"].items():
                if st["asleep"] and name != b["who"]:
                    fx.append(dict(k="zzz", a=name, t0=b["t0"], t1=b["t1"]))
        if self.funny:
            for o in self.actors:
                if self.after[o.name]["on"]:
                    sx.append((self.fin0 + 0.1 + 0.12 * o.idx, "laugh", o.pitch))
        sx.append((self.end0 + 0.1, "tada", 0))

    # ---------------------------------------------------------- drawing one frame
    def draw(self, frame, t, R):
        ground = self.ground
        for f in self.fx:
            if f["k"] == "ground" and f["t0"] <= t < f["t1"]:
                self.draw_ground_prop(frame, f, t, R)
        order = sorted(self.actors, key=lambda a: (a.rig["kind"] == "parrot", a.idx))
        poses = {a.name: self.pose(a, t) for a in self.actors}
        for a in order:
            P = poses[a.name]
            if P["on"]:
                draw_actor(frame, a, P, ground)
                draw_props(frame, a, P, ground, R)
        for f in self.fx:
            if f["t0"] <= t < f["t1"] and f["k"] != "ground":
                self.draw_fx(frame, f, t, R, poses)
        for b in self.beats:
            if b["say"] and b.get("bub") and b["tl"] - 0.05 <= t < b["tt"] + 0.25:
                self.draw_tail(frame, b, t, poses)

    def draw_ground_prop(self, frame, f, t, R):
        pr = R["props"][f["prop"]]
        x = f["x"]
        ang = 0.0
        if t >= f["imp"] - SLIP_SLIDE * 0.5:
            k = clamp((t - f["imp"] + SLIP_SLIDE * 0.5) / 0.6, 0, 1)
            x += f["dirn"] * 260 * ease_out(k)
            ang = -f["dirn"] * 400 * ease_out(k)
        im = pr["img"] if not ang else pr["img"].rotate(ang, expand=True, resample=Image.BICUBIC)
        paste(frame, im, x - im.width / 2, self.ground - im.height + 8)

    def head_top(self, a, P):
        hx, hy = head_top_local(a.rig)
        return to_world(a.rig, P, self.ground, hx, hy)

    def draw_fx(self, frame, f, t, R, poses):
        k = f["k"]
        u = t - f["t0"]
        F = R["fx"]
        if k == "dust":
            d = ImageDraw.Draw(frame)
            kk = u / (f["t1"] - f["t0"])
            for i in range(6):
                ang = math.pi * (0.1 + 0.8 * i / 5)
                rr = 8 + 26 * kk
                cx = f["x"] - math.cos(ang) * (30 + 70 * kk)
                cy = self.ground - 10 - math.sin(ang) * (8 + 30 * kk)
                c = int(lerp(200, 240, kk))
                d.ellipse([cx - rr * (1 - kk * 0.6), cy - rr * (1 - kk * 0.6), cx + rr * (1 - kk * 0.6), cy + rr * (1 - kk * 0.6)], fill=(c, c - 6, c - 14))
            return
        a = self.by.get(f.get("a"))
        if not a:
            return
        P = poses[a.name]
        if not P["on"] and k != "fly":
            return
        hx, hy = self.head_top(a, P)
        if k == "stars":
            for i in range(3):
                ang = 2 * math.pi * (1.4 * u + i / 3)
                im = F["star"]
                paste(frame, im, hx + math.cos(ang) * 46 - im.width / 2, hy - 18 + math.sin(ang) * 14 - im.height / 2)
        elif k in ("!", "?"):
            im = F[k]
            sc = min(1.0, 0.4 + u / 0.15 * 0.6)
            if sc < 1:
                im = im.resize((max(1, int(im.width * sc)), max(1, int(im.height * sc))), Image.BILINEAR)
            wob = 4 * math.sin(2 * math.pi * 2 * u) if k == "?" else 0
            paste(frame, im, hx + 40 - im.width / 2 + wob, hy - 40 - im.height)
        elif k == "zzz":
            for i in range(3):
                uu = (u + i * 0.55) % 1.65
                im = F["Z"]
                sc = 0.6 + 0.5 * uu / 1.65
                im = im.resize((max(1, int(im.width * sc)), max(1, int(im.height * sc))), Image.BILINEAR)
                paste(frame, im, hx + 30 + uu * 40, hy - 10 - uu * 70)
        elif k == "hearts":
            for i in range(3):
                uu = u - i * 0.3
                if uu < 0:
                    continue
                im = F["heart"]
                paste(frame, im, hx - 40 + i * 40 + 10 * math.sin(2 * math.pi * 1.5 * uu) - 20, hy - 20 - uu * 60)
        elif k == "notes":
            for i in range(2):
                uu = (u + i * 0.6) % 1.2
                im = F["note"]
                paste(frame, im, hx + (-60 if i else 50) + 8 * math.sin(2 * math.pi * 2 * uu), hy + 20 - uu * 80)
        elif k == "sweat":
            im = F["drop"]
            uu = u % 1.2
            r = a.rig.get("r", 60)
            paste(frame, im, hx + r * 0.85, hy + r * 0.5 + uu * 16)
        elif k == "tears":
            im = F["drop"]
            r = a.rig.get("r", 60)
            hx2, hy2 = to_world(a.rig, P, self.ground, a.rig["head"][0], a.rig["head"][1])
            for sx in (-1, 1):
                uu = (u + (0.3 if sx > 0 else 0)) % 0.7
                paste(frame, im, hx2 + sx * r * 0.45 - im.width / 2, hy2 + r * 0.1 + uu * 60)
        elif k == "speed":
            im = F["speed"]
            ox, oy = to_world(a.rig, P, self.ground, 0, -a.rig["hip"] * 0.2)
            for i in range(4):
                jit = (i * 37 + int(t * 24) * 13) % 30
                paste(frame, im, ox - f["dirn"] * (90 + jit) - im.width / 2, oy - 120 + i * 40)
        elif k == "fly":
            self.draw_fly(frame, f, t, a, P, R)

    def draw_fly(self, frame, f, t, a, P, R):
        l0, l1 = f["l0"], f["l1"]
        Pl0 = self.pose(a, l0 - 0.001)
        sx0, sy0 = (hand_world(a.rig, Pl0, self.ground) if Pl0["held"] == f["prop"] else self.head_top(a, Pl0))
        Pl1 = self.pose(a, l1 + 0.001)
        ex, ey = self.head_top(a, Pl1)
        props = R["props"]
        if f["prop"] != "tray":
            if t >= l1:
                return
            k = (t - l0) / (l1 - l0)
            x = lerp(sx0, ex, k)
            y = lerp(sy0, ey, k) - 280 * math.sin(math.pi * k)
            im = props[f["prop"]]["c"].rotate(-600 * k, resample=Image.BICUBIC)
            paste(frame, im, x - im.width / 2, y - im.height / 2)
            return
        tray_c, th = props["_tray"]
        glass_c, gh = props["_glass"]
        if t < l1:
            k = (t - l0) / (l1 - l0)
            x = lerp(sx0, ex, k)
            y = lerp(sy0, ey, k) - 260 * math.sin(math.pi * k)
            paste(frame, tray_c.rotate(-500 * k, resample=Image.BICUBIC), x - th, y - th)
        else:
            Pn = self.pose(a, t)
            tx, ty = self.head_top(a, Pn)
            im = tray_c.rotate(Pn["rot"], resample=Image.BICUBIC) if abs(Pn["rot"]) > 0.5 else tray_c
            paste(frame, im, tx - th, ty - th)
        for i, (gx, gy) in enumerate(props["_slots"]):
            g1 = l1 + 0.12 * (i + 1)
            Pn = self.pose(a, min(t, g1))
            tx, ty = self.head_top(a, Pn)
            rr = math.radians(Pn["rot"])
            wx = tx + gx * math.cos(rr) + gy * math.sin(rr)
            wy = ty - gx * math.sin(rr) + gy * math.cos(rr)
            if t < g1:
                k = (t - l0) / (g1 - l0)
                x = lerp(sx0 + gx, wx, k)
                y = lerp(sy0 + gy, wy, k) - (300 + 40 * i) * math.sin(math.pi * k)
                im = glass_c.rotate(-360 * k * (1 + i % 2), resample=Image.BICUBIC)
            else:
                x, y = wx, wy
                im = glass_c.rotate(Pn["rot"], resample=Image.BICUBIC) if abs(Pn["rot"]) > 0.5 else glass_c
            paste(frame, im, x - gh, y - gh)

    def draw_tail(self, frame, b, t, poses):
        a = self.by[b["who"]]
        P = poses[a.name]
        if not P["on"]:
            return
        bx, by = b["bub"]
        hx, hy = self.head_top(a, P)
        if hy < by + 30:
            return
        d = ImageDraw.Draw(frame)
        tipx, tipy = lerp(bx, hx, 0.85), hy - 10
        if b["do"] == "think":
            for i, rr in enumerate((13, 9, 6)):
                k = 0.3 + i * 0.25
                cx, cy = lerp(bx, tipx, k), lerp(by + 18, tipy, k)
                d.ellipse([cx - rr - 3, cy - rr - 3, cx + rr + 3, cy + rr + 3], fill=(48, 40, 32))
                d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=(255, 255, 255))
            return
        pts = [(bx - 18, by + 8), (bx + 18, by + 8), (tipx, tipy)]
        d.polygon([(pts[0][0] - 3, pts[0][1]), (pts[1][0] + 3, pts[1][1]), (tipx, tipy + 4)], fill=(48, 40, 32))
        d.polygon(pts, fill=(255, 255, 255))


# ================================================================ putting a character together
def hand_local(rig, P):
    side = P.get("hand", 1) or 1
    if rig["kind"] != "human":
        return rig["mouth"][0], rig["mouth"][1] + rig.get("r", 40) * 0.25
    shx, shy = rig["sh"]
    AL = rig["AL"]
    if side > 0:
        a = math.radians(P["armR"])
        return shx + AL * math.sin(a), shy + AL * math.cos(a)
    a = math.radians(P["armL"])
    return -shx - AL * math.sin(a), shy + AL * math.cos(a)


def head_top_local(rig):
    return rig["head"][0], -rig["top"] + (8 if rig["kind"] == "human" else 4)


def origin_world(rig, P, ground):
    ang = math.radians(abs(P["rot"]))
    h = rig["hip"] * P["sy"] * math.cos(ang) + rig["rest"] * math.sin(ang)
    return P["x"], ground - h - P["lift"]


def to_world(rig, P, ground, lx, ly):
    ox, oy = origin_world(rig, P, ground)
    lx, ly = lx * P["sx"], ly * P["sy"]
    r = math.radians(P["rot"])
    c, s = math.cos(r), math.sin(r)
    return ox + lx * c + ly * s, oy - lx * s + ly * c


def hand_world(rig, P, ground):
    hx, hy = hand_local(rig, P)
    return to_world(rig, P, ground, hx + P["pdx"], hy + P["pdy"])


def head_img(hp, P, tilt):
    key = (P["eyes"], P["look"] if P["eyes"] in ("open", "wide", "sad") else 0)
    img = hp["back"].copy()
    img.alpha_composite(hp["eyes"].get(key) or hp["eyes"][("open", 0)])
    img.alpha_composite(hp["mouth"].get(P["mouth"]) or hp["mouth"]["closed"])
    if hp["front"] is not None:
        img.alpha_composite(hp["front"])
    if abs(tilt) > 0.5:
        img = img.rotate(tilt, resample=Image.BICUBIC)
    return img


def put_limb(cv, limb, ang, x, y, resample=Image.BICUBIC):
    img, px, py = limb
    im, qx, qy = rot_about(img, ang, px, py, resample)
    comp(cv, im, x - qx, y - qy)


def put_head(cv, hp, P, tilt, x, y):
    head = head_img(hp, P, 0)
    c = hp["side"] / 2
    im, qx, qy = rot_about(head, tilt, c, c, Image.BILINEAR)
    comp(cv, im, x - qx, y - qy)


def draw_actor(frame, a, P, ground):
    rig = a.rig
    C = rig["C"]
    o = C // 2
    cv = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    kind = rig["kind"]
    tilt = P["tilt"] * (P["look"] or 1) if kind == "human" else P["tilt"]
    if kind == "human":
        def put(pt):
            if pt:
                comp(cv, pt[0], o + pt[1], o + pt[2])
        put(rig["back"])
        for side in (-1, 1):
            put_limb(cv, rig["leg"][side], P["legR"] if side > 0 else -P["legL"], o + side * rig["hipx"], o)
        put(rig["lower"])
        put(rig["torso"])

        def arms():
            shx, shy = rig["sh"]
            for side in (-1, 1):
                put_limb(cv, rig["arm"], P["armR"] if side > 0 else -P["armL"], o + side * shx, o + shy)
        if not P["arms_front"]:
            arms()
        hx, hy = rig["head"]
        put_head(cv, rig["hp"], P, tilt, o + hx, o + hy)
        if P["arms_front"]:
            arms()
    elif kind == "puppy":
        e = "blink" if P["eyes"] in ("blink", "happy", "sleep") else "open"
        m = {"half": "half", "open": "open", "o": "open"}.get(P["mouth"], "closed")
        sp = rig["sprites"][(m, e)]
        im, qx, qy = rot_about(sp, tilt, sp.width / 2, rig["soy"], Image.BILINEAR)
        comp(cv, im, o - qx, o - qy)
    elif kind == "cat":
        tx, ty = rig["tailpos"]
        put_limb(cv, rig["tail"], P["tail"], o + tx, o + ty)
        b = rig["body"]
        comp(cv, b[0], o + b[1], o + b[2])
        hx, hy = rig["head"]
        put_head(cv, rig["hp"], P, tilt, o + hx, o + hy)
    elif kind == "parrot":
        b = rig["body"]
        comp(cv, b[0], o + b[1], o + b[2])
        wx, wy = rig["wingpos"]
        for side in (-1, 1):
            put_limb(cv, rig["wings"][side], side * P["flap"], o + side * wx, o + wy)
        hx, hy = rig["head"]
        put_head(cv, rig["hp"], P, tilt, o + hx, o + hy)
    bb = cv.getbbox()
    if not bb:
        return
    cv = cv.crop(bb)
    cx, cy = o - bb[0], o - bb[1]
    if P["sx"] != 1 or P["sy"] != 1:
        cv = cv.resize((max(1, int(cv.width * P["sx"])), max(1, int(cv.height * P["sy"]))), Image.BILINEAR)
        cx, cy = cx * P["sx"], cy * P["sy"]
    cv, cx, cy = rot_about(cv, P["rot"], cx, cy, Image.BILINEAR)
    ox, oy = origin_world(rig, P, ground)
    frame.paste(cv, (int(round(ox - cx)), int(round(oy - cy))), cv)


def draw_props(frame, a, P, ground, R):
    rig = a.rig
    props = R["props"]
    if P["head"] and P["head"] in props:
        pr = props[P["head"]]
        tx, ty = to_world(rig, P, ground, *head_top_local(rig))
        h = pr["hhalf"]
        im, qx, qy = rot_about(pr["head"], P["rot"] + P["headwob"], h, h)
        paste(frame, im, tx - qx, ty - qy)
    if P["held"] and P["held"] in props:
        pr = props[P["held"]]
        wx, wy = hand_world(rig, P, ground)
        im, h = pr["c"], pr["half"]
        if P["pscale"] < 0.99:
            nsz = max(2, int(im.width * P["pscale"]))
            im = im.resize((nsz, nsz), Image.BILINEAR)
            h = nsz / 2
        im, qx, qy = rot_about(im, P["rot"], h, h)
        paste(frame, im, wx - qx, wy - qy)


# ================================================================ words (subtitles) and the whole video
def ass_text(s):
    import re
    s = re.sub(r"[\U0001F000-\U0001FAFF☀-➿⬀-⯿️]", "", str(s))
    return s.replace("\\", "＼").replace("{", "").replace("}", "").replace("\n", " ").strip()


def ts(sec):
    cs = max(0, int(round(sec * 100)))
    return "%d:%02d:%02d.%02d" % (cs // 360000, cs // 6000 % 60, cs // 100 % 60, cs % 100)


def make_ass(story, title, path):
    total, end0 = story.total, story.end0
    ev = ["Dialogue: 4,%s,%s,Label,,0,0,0,,BigD cartoon" % (ts(0), ts(end0)),
          "Dialogue: 4,%s,%s,Title,,0,0,0,,{\\fad(200,300)}%s" % (ts(0.15), ts(2.9), ass_text(title))]
    for b in story.beats:
        if not b["say"]:
            continue
        a = story.by[b["who"]]
        P = story.pose(a, b["tl"] + 0.05)
        hx, hy = story.head_top(a, P)
        bx = clamp(hx, 262, 458)
        by = clamp(hy - 46, 360, 720)
        b["bub"] = (bx, by)
        txt = ass_text(b["say"])
        t0, t1 = ts(b["tl"] - 0.05), ts(b["tt"] + 0.25)
        ev.append("Dialogue: 1,%s,%s,BubbleEdge,,0,0,0,,{\\an2\\pos(%d,%d)}%s" % (t0, t1, bx, by, txt))
        ev.append("Dialogue: 2,%s,%s,Bubble,,0,0,0,,{\\an2\\pos(%d,%d)}%s" % (t0, t1, bx, by, txt))
    ev.append("Dialogue: 5,%s,%s,End,,0,0,0,,{\\fad(300,0)\\pos(360,560)}BigD AI" % (ts(end0), ts(total)))
    ev.append("Dialogue: 5,%s,%s,EndSub,,0,0,0,,{\\fad(300,0)\\pos(360,640)}కార్టూన్లు · వార్తలు · సహాయం · bigdai.tech" % (ts(end0), ts(total)))
    write_ass(path, ev)


ASS_HEAD = """[Script Info]
ScriptType: v4.00+
PlayResX: 720
PlayResY: 1280
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Title,Hind Guntur,46,&H00FFFFFF,&H00FFFFFF,&H00205AE0,&H50000000,1,0,0,0,100,100,0,0,3,14,0,8,60,60,64,1
Style: BubbleEdge,Hind Guntur,40,&H00302830,&H00302830,&H00302830,&H00000000,1,0,0,0,100,100,0,0,3,19,0,2,112,112,0,1
Style: Bubble,Hind Guntur,40,&H00262626,&H00262626,&H00FFFFFF,&H64000000,1,0,0,0,100,100,0,0,3,16,4,2,112,112,0,1
Style: Label,Hind Guntur,24,&H00FFFFFF,&H00FFFFFF,&H60000000,&H70000000,1,0,0,0,100,100,0,0,3,1,0,9,22,22,36,1
Style: End,Hind Guntur,64,&H00FFFFFF,&H00FFFFFF,&H00402010,&H90000000,1,0,0,0,100,100,0,0,1,4,2,5,40,40,0,1
Style: EndSub,Hind Guntur,34,&H00FFFFFF,&H00FFFFFF,&H00402010,&H90000000,1,0,0,0,100,100,0,0,1,3,1,5,40,40,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""


def write_ass(path, events):
    with open(path, "w", encoding="utf-8") as f:
        f.write(ASS_HEAD + "\n".join(events) + "\n")


def make_sound(story, path):
    tr = Track(story.total)
    for t, kind, arg in story.sfx:
        if kind == "blips":
            t1, pitch, seed = arg
            add_blips(tr, t, t1, pitch, seed)
        else:
            add_sfx(tr, t, kind, arg or 300)
    for b in story.beats:
        if b.get("voice"):
            tr.add(b["tl"], b["voice"], 1.6)
    tr.write(path)


def render(plan):
    random.seed(plan.get("seed", 7))
    tmp = plan.get("tmp") or os.path.dirname(plan["out"])
    os.makedirs(tmp, exist_ok=True)
    bg, ground = place_image(plan.get("place", "park"), plan.get("night"), plan.get("cache"))
    story = Story(plan, ground)
    R = dict(props=build_props(), fx=build_fx())
    ass = os.path.join(tmp, "words.ass")
    snd = os.path.join(tmp, "sound.wav")
    make_ass(story, plan.get("title", ""), ass)
    make_sound(story, snd)
    total = story.total
    ff = start_ffmpeg(plan, ass, snd, total)
    n = int(total * FPS)
    poster_t = plan.get("poster_t") or story.poster_time()
    if poster_t >= story.end0:
        poster_t = story.end0 * 0.5
    dark = Image.new("RGB", (W, H), (0, 0, 0))
    try:
        for i in range(n):
            t = i / FPS
            frame = bg.copy()
            story.draw(frame, t, R)
            if t >= story.end0:
                frame = Image.blend(frame, dark, 0.5 * clamp((t - story.end0) / 0.3, 0, 1))
            if plan.get("poster") and abs(t - poster_t) < 0.5 / FPS:
                frame.save(plan["poster"], "JPEG", quality=84)
            ff.stdin.write(frame.tobytes())
        ff.stdin.close()
    except BrokenPipeError:
        pass
    finish_ffmpeg(ff, plan["out"])
    return total


def start_ffmpeg(plan, ass, snd, total):
    """ffmpeg reads the pictures from us, adds the words, the sounds and the music, and writes plan["out"]."""
    ffmpeg = plan.get("ffmpeg") or "ffmpeg"
    music = plan.get("music")
    q = lambda p: p.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
    cmd = [ffmpeg, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (W, H), "-r", str(FPS), "-i", "-",
           "-i", snd]
    if music and os.path.exists(music):
        cmd += ["-stream_loop", "-1", "-i", music]
    else:
        cmd += ["-f", "lavfi", "-t", "%.2f" % total, "-i", "anullsrc=r=24000:cl=mono"]
    fc = ("[0:v]ass=%s:fontsdir=%s:shaping=complex,format=yuv420p[v];" % (q(ass), q(plan.get("fonts") or FONTS)) +
          "[2:a]volume=%.2f,afade=t=out:st=%.2f:d=1.5[m];[1:a]volume=1.0[s];[s][m]amix=inputs=2:duration=first:dropout_transition=0,volume=2[a]"
          % (float(plan.get("music_volume", 0.2)), max(0.0, total - 1.6)))
    cmd += ["-filter_complex", fc, "-map", "[v]", "-map", "[a]", "-t", "%.2f" % total, "-c:v", "libx264", "-preset", "veryfast",
            "-crf", "23", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-ar", "44100", "-movflags", "+faststart", plan["out"] + ".part.mp4"]
    return subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.PIPE)


def finish_ffmpeg(ff, out):
    err = ff.stderr.read().decode("utf-8", "replace")
    if ff.wait() != 0:
        raise RuntimeError("ffmpeg: " + err.strip()[-400:])
    os.replace(out + ".part.mp4", out)


def _poster_time(self):
    for b in self.beats:
        if b["do"] in ("slip", "fall"):
            return b["t0"] + (SLIP_WALK + SLIP_SLIDE if b["do"] == "slip" else 0.45) + 0.6
    for b in self.beats:
        if b["do"] in ("jump", "dance", "laugh", "balance", "surprised"):
            return b["t0"] + 0.5
    return min(self.total * 0.35, 4.0)


Story.poster_time = _poster_time


# ================================================================ checking by hand
DEMO = {
    "title": "చాయ్ వాలా జారుడు", "place": "teashop", "mood": "funny", "type": "joke", "seed": 3,
    "cast": ["chai", "idli"],
    "beats": [
        {"who": "chai", "do": "balance", "prop": "tray", "say": "ఆరు గ్లాసుల టీ! ఒక్క చుక్క కూడా కింద పడదు!"},
        {"who": "idli", "do": "point", "say": "అన్నా, జాగ్రత్త! కింద అరటి తొక్క!"},
        {"who": "chai", "do": "slip", "prop": "banana", "say": "అయ్యో... నా టీ!"},
        {"who": "idli", "do": "laugh", "say": "హహ్హా! గ్లాసులన్నీ మీ తల మీదే!"},
        {"who": "chai", "do": "shrug", "say": "చూశావా? ఒక్క చుక్క కూడా పడలేదు!"},
    ],
}


def sheet(path):
    names = CHARS
    cell_w, cell_h = 360, 640
    img = Image.new("RGB", (cell_w * 5, cell_h * 2 + 360), (235, 235, 235))
    for i, n in enumerate(names):
        a = Actor(n, 180, 0)
        st = Story({"cast": [n], "beats": []}, 600)
        P = st.pose(a, 0.0)
        P = dict(P, x=180, lift=0 if a.rig["kind"] != "parrot" else 120, eyes=["open", "happy", "wide"][i % 3], mouth=["smile", "open", "o"][i % 3],
                 held=["tea", "book", None, "ball", "flower", None, "laddu", None, None][i])
        cell = Image.new("RGB", (cell_w, cell_h), (250, 246, 238))
        draw_actor(cell, a, P, 600)
        draw_props(cell, a, P, 600, dict(props=build_props(), fx=build_fx()))
        ImageDraw.Draw(cell).text((10, 10), n, fill=(0, 0, 0), font=font(28))
        img.paste(cell, ((i % 5) * cell_w, (i // 5) * cell_h))
    x = 0
    for n in PLACES:
        bg, g = place_image(n, False, None)
        th = bg.resize((180, 320))
        ImageDraw.Draw(th).line([(0, g / 4), (180, g / 4)], fill=(255, 0, 0), width=2)
        img.paste(th, (x, cell_h * 2 + 20))
        x += 180
    img.save(path)


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "--sheet":
        sheet(sys.argv[2])
        return
    if len(sys.argv) >= 3 and sys.argv[1] == "--demo":
        d = sys.argv[2]
        os.makedirs(d, exist_ok=True)
        plan = dict(DEMO, out=os.path.join(d, "demo.mp4"), poster=os.path.join(d, "demo.jpg"), tmp=os.path.join(d, "tmp"),
                    cache=os.path.join(d, "cache"), music=os.path.join(ROOT, "assets", "music-kids.m4a"))
        if len(sys.argv) >= 4:
            with open(sys.argv[3], encoding="utf-8") as f:
                plan.update(json.load(f))
    else:
        with open(sys.argv[1], encoding="utf-8") as f:
            plan = json.load(f)
    if plan.get("style", "doodle") == "doodle":
        sys.path.insert(0, HERE)
        import doodle
        dur = doodle.render(plan)
    else:
        dur = render(plan)
    print(json.dumps({"dur": round(dur, 2)}))


if __name__ == "__main__":
    main()
