"""BigD cartoons in the cute "doodle" style (the default look).

BigD's own characters (all original): Chai (a glass of tea for a head), Idli, Mango, the BigD puppy, Ammamma,
Tatayya, the teacher, Pilli the cat and Chilaka the parrot, on soft pastel backgrounds. Their elbows and knees bend;
hands go on hips, point, clap, wave and cover the face; heads nod, tilt and turn; faces wink, side-eye, laugh and
cry; lips follow the voice. The camera cuts between wide shots and close-ups.
Used by toon.py (plan["style"] = "doodle", the default).
"""
import math, os, random, subprocess
from collections import OrderedDict
from PIL import Image, ImageDraw
import toon as T

W, H, FPS = T.W, T.H, T.FPS
K = 2                       # the picture is drawn twice as big, then made smaller (smooth edges)
INK = (40, 30, 36)
OW = 3.0                    # outline
FLOOR = 1110
WHITE = (255, 255, 255)

CAST = {
    "chai": dict(name="చాయ్", head="glass", h=1.0, top=(128, 110, 236), legs=(186, 176, 246), skin=(246, 214, 190), sleeve=0.45, glove=True, sole=(150, 130, 240)),
    "idli": dict(name="ఇడ్లీ", head="idli", h=0.8, top=(255, 206, 60), legs=(246, 214, 190), shorts=(70, 120, 210), skin=(246, 214, 190), sleeve=0.4, glove=True, sole=(70, 120, 210)),
    "mango": dict(name="మామిడి", head="mango", h=0.8, top=(255, 143, 184), legs=(246, 214, 190), dress=(255, 143, 184), skin=(246, 214, 190), sleeve=0.22, puff=True, glove=True, sole=(255, 100, 150)),
    "bigd": dict(name="బిగ్‌డీ", head="puppy", h=0.92, top=(143, 227, 176), legs=(143, 227, 176), stripe=True, skin=(255, 255, 255), sleeve=1.0, glove=True, sole=(90, 190, 130)),
    "ammamma": dict(name="అమ్మమ్మ", head="ammamma", h=0.92, belly=1.15, top=(160, 40, 86), legs=(240, 196, 160), saree=(196, 56, 108), border=(242, 179, 61), skin=(240, 196, 160), sleeve=0.32, shoe=(150, 96, 60), sole=(110, 70, 45)),
    "tatayya": dict(name="తాతయ్య", head="tatayya", h=0.98, top=(255, 255, 255), legs=(214, 160, 112), dhoti=(255, 252, 240), towel=(46, 158, 91), skin=(214, 160, 112), sleeve=0.0, shoe=(150, 96, 60), sole=(110, 70, 45)),
    "teacher": dict(name="టీచర్", head="teacher", h=0.96, top=(60, 123, 214), legs=(244, 238, 228), kurta=(60, 123, 214), dupatta=(242, 179, 61), skin=(240, 196, 160), sleeve=0.6, shoe=(90, 60, 45), sole=(60, 40, 30)),
    "pilli": dict(name="పిల్లి", head="cat", h=0.8, top=(255, 160, 70), legs=(150, 150, 168), skin=(242, 166, 90), sleeve=1.0, glove=True, hood=True, sole=(255, 140, 60)),
    "chilaka": dict(name="చిలుక", head="parrot", h=0.7, bird=True),
}
ALIAS = {"chaiwala": "chai", "chinnu": "idli", "chitti": "mango", "raithu": "tatayya"}
CHARS = list(CAST)
# the head pictures: neck at (160, 300); (top of the head, middle of the face, size)
HEADS = {"glass": (114, 214, 74), "idli": (172, 236, 72), "mango": (110, 214, 72), "puppy": (138, 214, 76), "cat": (126, 220, 72),
         "ammamma": (110, 220, 72), "tatayya": (144, 220, 74), "teacher": (140, 220, 72), "parrot": (120, 220, 50)}
PLACE_COL = {"stage": (249, 176, 208), "teashop": (255, 214, 172), "market": (255, 236, 156), "village": (192, 236, 206),
             "farm": (214, 240, 172), "school": (192, 224, 255), "home": (228, 216, 255), "park": (198, 236, 202),
             "beach": (176, 222, 255), "night": (44, 50, 110)}


def shade(c, f):
    return tuple(min(255, int(v * f)) for v in c[:3])


class Pen:
    """Draws in 1x units onto a bigger picture (scale), with (ox, oy) as the origin."""

    def __init__(self, img, scale=K, ox=0.0, oy=0.0):
        self.d, self.s, self.ox, self.oy = ImageDraw.Draw(img), scale, ox, oy

    def p(self, x, y):
        return ((x + self.ox) * self.s, (y + self.oy) * self.s)

    def box(self, cx, cy, rx, ry):
        a, b = self.p(cx - abs(rx), cy - abs(ry))
        c, d = self.p(cx + abs(rx), cy + abs(ry))
        return [a, b, c, d]

    def line(self, pts, col, w):
        self.d.line([self.p(*q) for q in pts], fill=col, width=max(1, int(round(w * self.s))), joint="curve")

    def dot(self, x, y, r, col):
        self.d.ellipse(self.box(x, y, r, r), fill=col)

    def stick(self, pts, w, col):
        self.line(pts, col, w)
        for x, y in pts:
            self.dot(x, y, w / 2, col)

    def limb(self, pts, w, col, ow=OW):
        if ow:
            self.stick(pts, w + 2 * ow, INK)
        self.stick(pts, w, col)

    def ell(self, cx, cy, rx, ry, col, ow=OW):
        if ow:
            self.d.ellipse(self.box(cx, cy, rx + ow, ry + ow), fill=INK)
        self.d.ellipse(self.box(cx, cy, rx, ry), fill=col)

    def rect(self, x0, y0, x1, y1, col, r=0, ow=OW):
        if ow:
            a, b = self.p(min(x0, x1) - ow, min(y0, y1) - ow)
            c, d = self.p(max(x0, x1) + ow, max(y0, y1) + ow)
            self.d.rounded_rectangle([a, b, c, d], radius=int((r + ow) * self.s), fill=INK)
        a, b = self.p(min(x0, x1), min(y0, y1))
        c, d = self.p(max(x0, x1), max(y0, y1))
        self.d.rounded_rectangle([a, b, c, d], radius=int(r * self.s), fill=col)

    def poly(self, pts, col, ow=OW):
        q = [self.p(*v) for v in pts]
        self.d.polygon(q, fill=col)
        if ow:
            self.d.line(q + [q[0], q[1]], fill=INK, width=max(1, int(round(ow * self.s))), joint="curve")

    def arc(self, cx, cy, rx, ry, a0, a1, col, w):
        self.d.arc(self.box(cx, cy, rx, ry), a0, a1, fill=col, width=max(1, int(round(w * self.s))))

    def chord(self, cx, cy, rx, ry, a0, a1, col):
        self.d.chord(self.box(cx, cy, rx, ry), a0, a1, fill=col)


# ================================================================ faces and heads
def face(p, cx, ey, my, ex, s, eyes, mouth, look, lid, blush=(255, 150, 160)):
    lx = look * 3.4 * s
    for side in (-1, 1):
        x = cx + side * ex
        kind = eyes
        if eyes == "wink":
            kind = "open" if side < 0 else "happy"
        if kind in ("open", "angry", "sad", "side"):
            p.ell(x + lx, ey, 7.2 * s, 9.6 * s, INK, ow=0)
            p.ell(x + lx + 2.4 * s, ey - 3.4 * s, 2.5 * s, 2.5 * s, WHITE, ow=0)
            if kind == "side":
                p.rect(x - 10 * s, ey - 13 * s, x + 10 * s, ey - 1.5 * s, lid, ow=0)
                p.line([(x - 9 * s, ey - 1.5 * s), (x + 9 * s, ey - 1.5 * s)], INK, 2.6 * s)
            elif kind == "angry":
                p.line([(x + side * 10 * s, ey - 19 * s), (x - side * 7 * s, ey - 11 * s)], INK, 3.2 * s)
            elif kind == "sad":
                p.line([(x + side * 10 * s, ey - 12 * s), (x - side * 6 * s, ey - 19 * s)], INK, 3.0 * s)
        elif kind == "wide":
            p.ell(x, ey, 10.5 * s, 11.5 * s, WHITE, ow=2.4 * s)
            p.ell(x + lx * 0.6, ey + 1 * s, 4.4 * s, 4.4 * s, INK, ow=0)
        elif kind in ("blink", "sleep"):
            p.arc(x, ey + (1 if kind == "blink" else 3) * s, 8 * s, 4 * s, 15, 165, INK, 2.8 * s)
        elif kind == "happy":
            p.arc(x, ey + 5 * s, 8 * s, 7 * s, 200, 340, INK, 3.0 * s)
        elif kind == "dizzy":
            p.line([(x - 6 * s, ey - 6 * s), (x + 6 * s, ey + 6 * s)], INK, 2.6 * s)
            p.line([(x + 6 * s, ey - 6 * s), (x - 6 * s, ey + 6 * s)], INK, 2.6 * s)
    for side in (-1, 1):
        p.ell(cx + side * (ex + 11 * s), my - 7 * s, 7 * s, 4 * s, blush, ow=0)
    dark, tongue = (150, 30, 50), (255, 110, 140)
    if mouth == "closed":
        p.arc(cx, my - 3 * s, 8 * s, 6 * s, 20, 160, INK, 2.8 * s)
    elif mouth == "half":
        p.ell(cx, my, 5.5 * s, 4.2 * s, dark, ow=2 * s)
    elif mouth == "open":
        p.ell(cx, my + 1 * s, 8.5 * s, 9 * s, dark, ow=2.4 * s)
        p.ell(cx, my + 5.5 * s, 4.6 * s, 2.8 * s, tongue, ow=0)
    elif mouth in ("smile", "grin", "tongue"):
        p.chord(cx, my - 4 * s, (14 if mouth == "grin" else 11) * s, 11 * s, 0, 180, INK)
        p.chord(cx, my - 4 * s, (12 if mouth == "grin" else 9) * s, 9 * s, 0, 180, dark)
        if mouth == "grin":
            p.rect(cx - 10 * s, my - 4 * s, cx + 10 * s, my - 0.5 * s, WHITE, ow=0)
        p.ell(cx, my + 1.5 * s, 4.6 * s, 2.6 * s, tongue, ow=0)
        if mouth == "tongue":
            p.ell(cx + 3 * s, my + 7 * s, 5 * s, 6 * s, tongue, ow=1.6 * s)
    elif mouth == "o":
        p.ell(cx, my + 1 * s, 4.6 * s, 6 * s, dark, ow=2.2 * s)
    elif mouth == "sad":
        p.arc(cx, my + 6 * s, 8 * s, 6 * s, 200, 340, INK, 2.8 * s)
    elif mouth == "flat":
        p.line([(cx - 7 * s, my), (cx + 7 * s, my)], INK, 2.8 * s)


def draw_head(kind, eyes, mouth, look, turn, hs):
    size = 320
    img = Image.new("RGBA", (int(size * hs * K), int(size * hs * K)), (0, 0, 0, 0))
    p = Pen(img, hs * K)
    t = turn * 14
    if kind == "glass":
        tea = (176, 98, 44)
        p.poly([(98, 124), (222, 124), (208, 296), (112, 296)], (232, 240, 244))
        p.poly([(104, 176), (216, 176), (207, 290), (113, 290)], tea, ow=0)
        p.ell(160, 176, 54, 10, (245, 228, 196), ow=0)
        for bx in (130, 156, 184): p.ell(bx, 174, 5, 3.5, (255, 246, 228), ow=0)
        p.ell(160, 124, 62, 9, (244, 248, 250))
        p.line([(214, 140), (205, 280)], WHITE, 5)
        face(p, 160 + t, 222, 252, 19, 1.0, eyes, mouth, look, tea, (236, 120, 110))
    elif kind == "idli":
        c = (252, 252, 246)
        p.ell(160, 236, 80, 62, c)
        p.chord(160, 236, 79, 61, 25, 155, (232, 232, 222))
        for bx, by in ((126, 204), (196, 208), (160, 192)): p.ell(bx, by, 3, 3, (220, 220, 208), ow=0)
        face(p, 160 + t, 232, 258, 25, 1.05, eyes, mouth, look, c)
    elif kind == "mango":
        c = (255, 186, 50)
        p.poly([(160, 132), (200, 106), (216, 120), (176, 140)], (80, 170, 80), ow=2.4)
        p.line([(160, 144), (156, 124)], (110, 80, 40), 5)
        p.ell(160, 214, 64, 80, c)
        p.ell(136, 182, 14, 24, (255, 222, 120), ow=0)
        rb = (255, 79, 139)
        p.poly([(122, 150), (100, 136), (100, 164)], rb, ow=2.2)
        p.poly([(122, 150), (144, 136), (144, 164)], rb, ow=2.2)
        p.ell(122, 150, 6, 6, shade(rb, 0.8), ow=2)
        face(p, 160 + t, 214, 246, 22, 1.0, eyes, mouth, look, c)
    elif kind == "puppy":
        p.ell(88, 214, 26, 52, (201, 138, 90))
        p.ell(232, 214, 26, 52, (201, 138, 90))
        p.ell(160, 214, 80, 74, WHITE)
        face(p, 160 + t, 204, 248, 27, 1.05, eyes, mouth, look, WHITE)
        p.ell(160 + t, 228, 11, 7.5, INK, ow=0)
        p.ell(156 + t, 226, 3, 2, (120, 120, 120), ow=0)
    elif kind == "cat":
        fur = (242, 166, 90)
        for sd in (-1, 1):
            p.poly([(160 + sd * 66, 200), (160 + sd * 46, 126), (160 + sd * 12, 160)], fur)
            p.poly([(160 + sd * 54, 186), (160 + sd * 44, 142), (160 + sd * 24, 164)], (255, 170, 190), ow=0)
        p.ell(160, 220, 74, 66, fur)
        for dx in (-14, 0, 14): p.line([(160 + dx, 156), (160 + dx * 0.8, 172)], (207, 122, 52), 4)
        face(p, 160 + t, 214, 246, 25, 1.0, eyes, mouth, look, fur)
        p.poly([(152 + t, 228), (168 + t, 228), (160 + t, 236)], (255, 120, 150), ow=0)
        for sd in (-1, 1):
            for k in (-1, 1):
                p.line([(160 + t + sd * 30, 238 + k * 4), (160 + t + sd * 70, 232 + k * 12)], INK, 2)
    elif kind in ("ammamma", "tatayya", "teacher"):
        skin = (240, 196, 160) if kind != "tatayya" else (214, 160, 112)
        if kind == "ammamma":
            grey = (233, 230, 226)
            p.ell(160, 136, 26, 22, grey)
            p.ell(160, 220, 70, 72, skin)
            p.chord(160, 214, 72, 66, 180, 360, grey)
            p.arc(160, 214, 72, 66, 180, 360, INK, 3)
            p.line([(160, 150), (160, 176)], (200, 196, 190), 3)
            face(p, 160 + t, 224, 256, 24, 1.0, eyes, mouth, look, skin)
            fr = (120, 76, 40)
            for sd in (-1, 1):
                p.d.ellipse(p.box(160 + t + sd * 24, 224, 15, 14), outline=fr, width=int(3 * p.s))
            p.line([(160 + t - 9, 224), (160 + t + 9, 224)], fr, 3)
            for sd in (-1, 1): p.ell(160 + sd * 70, 240, 5, 5, (242, 179, 61), ow=1.6)
        elif kind == "tatayya":
            white = (244, 244, 244)
            p.ell(160, 220, 72, 74, skin)
            for sd in (-1, 1): p.ell(160 + sd * 66, 206, 14, 24, white)
            p.arc(160, 168, 22, 10, 200, 340, shade(skin, 0.85), 2.4)
            face(p, 160 + t, 212, 262, 25, 1.0, eyes, mouth, look, skin)
            for sd in (-1, 1): p.ell(160 + t + sd * 16, 244, 18, 7, white, ow=2.4)
        else:
            black = (34, 24, 22)
            p.ell(160, 226, 80, 84, black)
            p.ell(160, 220, 68, 72, skin)
            p.chord(160, 212, 70, 64, 180, 360, black)
            p.arc(160, 212, 70, 64, 180, 360, INK, 3)
            p.chord(130, 168, 34, 22, 0, 180, black)
            face(p, 160 + t, 224, 256, 24, 1.0, eyes, mouth, look, skin)
            for sd in (-1, 1): p.ell(160 + sd * 68, 240, 4, 4, (242, 179, 61), ow=1.4)
    bb = img.getbbox()
    nx, ny = 160 * hs * K, 300 * hs * K
    if bb:
        img = img.crop(bb)
        nx, ny = nx - bb[0], ny - bb[1]
    return img, nx, ny


_HEADS = OrderedDict()


def head_pic(name, eyes, mouth, look, turn):
    sp = CAST[name]
    key = (name, eyes, mouth, look, round(turn * 2) / 2)
    if key in _HEADS:
        _HEADS.move_to_end(key)
        return _HEADS[key]
    pic = draw_head(sp["head"], eyes, mouth, look, key[4], sp["hs"])
    _HEADS[key] = pic
    if len(_HEADS) > 90:
        _HEADS.popitem(last=False)
    return pic


# ================================================================ places (soft pastel)
def place_pic(name, night_tint=False):
    name = name if name in PLACE_COL else "stage"
    bg = PLACE_COL[name]
    img = Image.new("RGB", (W * K, H * K), bg)
    p = Pen(img)
    floor = shade(bg, 0.93)
    if name != "stage":
        p.rect(-10, 1030, W + 10, H + 10, floor, ow=0)
    if name == "stage":
        rnd = random.Random(4)
        for _ in range(9):
            x, y, r = rnd.uniform(40, 680), rnd.uniform(120, 900), rnd.uniform(6, 11)
            p.poly([(x, y - r * 2), (x + r * 0.5, y - r * 0.5), (x + r * 2, y), (x + r * 0.5, y + r * 0.5), (x, y + r * 2),
                    (x - r * 0.5, y + r * 0.5), (x - r * 2, y), (x - r * 0.5, y - r * 0.5)], shade(bg, 1.08), ow=0)
    elif name == "teashop":
        for i in range(8):
            p.rect(i * 92 - 10, -10, i * 92 + 82, 96, (232, 96, 60) if i % 2 == 0 else WHITE, ow=0)
        for i in range(8): p.ell(i * 92 + 36, 96, 46, 16, (232, 96, 60) if i % 2 == 0 else WHITE, ow=0)
        p.rect(30, 820, 400, 1030, (196, 128, 74), r=8)
        p.rect(30, 800, 400, 840, (214, 150, 92), r=8)
        p.ell(330, 760, 46, 36, (200, 206, 214))
        p.poly([(290, 760), (250, 730), (246, 738), (286, 772)], (190, 196, 204), ow=2.4)
        p.ell(330, 726, 16, 6, (180, 186, 194))
        for gx in (80, 130, 180):
            p.rect(gx - 12, 760, gx + 12, 800, (226, 238, 244), r=3, ow=2.4)
            p.rect(gx - 9, 772, gx + 9, 797, (176, 98, 44), r=2, ow=0)
        for i, sx in enumerate((316, 340)):
            p.arc(sx, 690 - i * 8, 10, 22, 200, 340, WHITE, 4)
    elif name == "market":
        for i in range(8):
            p.rect(i * 92 - 10, -10, i * 92 + 82, 110, (226, 62, 52) if i % 2 == 0 else WHITE, ow=0)
        for i in range(8): p.ell(i * 92 + 36, 110, 46, 16, (226, 62, 52) if i % 2 == 0 else WHITE, ow=0)
        for cx, cols in ((520, ((245, 166, 35), (232, 75, 60))), (640, ((123, 192, 67), (255, 214, 64)))):
            p.rect(cx - 56, 930, cx + 56, 1030, (196, 140, 86), r=6)
            for j in range(3):
                for i in range(4 - j):
                    p.ell(cx - 36 + i * 24 + j * 12, 918 - j * 20, 12, 12, cols[(i + j) % 2], ow=2.2)
    elif name == "village":
        p.rect(40, 800, 300, 1030, (230, 180, 120), r=4)
        p.poly([(10, 810), (330, 810), (170, 660)], (184, 132, 62))
        p.rect(140, 900, 200, 1030, (110, 70, 44), r=6)
        tx, ty = 600, 1030
        for i in range(10):
            p.stick([(tx + i * 3, ty - i * 46), (tx + (i + 1) * 3, ty - (i + 1) * 46)], 20 - i, (150, 106, 64))
        for ang in (-160, -120, -80, -40, -10):
            a = math.radians(ang)
            p.line([(tx + 30, ty - 460), (tx + 30 + math.cos(a) * 70, ty - 460 + math.sin(a) * 50 - 6), (tx + 30 + math.cos(a) * 130, ty - 460 + math.sin(a) * 30 + 40)], (70, 160, 80), 14)
        p.ell(110, 170, 50, 50, (255, 214, 64))
    elif name == "farm":
        p.ell(600, 170, 54, 54, (255, 206, 60))
        for row, y in enumerate((960, 1000)):
            for x in range(20 + row * 18, W, 46):
                p.line([(x - 8, y - 22), (x, y), (x + 8, y - 22)], (70, 160, 80), 5)
                p.line([(x, y), (x, y - 30)], (70, 160, 80), 5)
    elif name == "school":
        p.rect(110, 290, 610, 560, (60, 110, 80), r=10)
        d = ImageDraw.Draw(img)
        d.text((170 * K, 330 * K), "A  B  C", font=T.font(40 * K), fill=WHITE)
        d.text((170 * K, 420 * K), "1 + 1 = 2", font=T.font(40 * K), fill=(255, 236, 160))
        p.ell(530, 360, 26, 26, (255, 236, 160), ow=0)
        p.ell(620, 150, 34, 34, WHITE)
        p.line([(620, 150), (620, 128)], INK, 3)
        p.line([(620, 150), (636, 158)], INK, 3)
    elif name == "home":
        p.rect(460, 300, 650, 520, (176, 222, 255), r=6)
        p.line([(555, 300), (555, 520)], INK, 3)
        p.rect(440, 290, 486, 540, (255, 160, 190), r=10)
        p.rect(624, 290, 670, 540, (255, 160, 190), r=10)
        p.rect(60, 940, 140, 1030, (196, 120, 80), r=8)
        for dx, dy in ((-24, -30), (0, -52), (24, -30)): p.ell(100 + dx, 940 + dy, 22, 30, (80, 170, 90))
        p.ell(150, 220, 34, 34, WHITE)
        p.line([(150, 220), (150, 198)], INK, 3)
        p.line([(150, 220), (166, 228)], INK, 3)
    elif name == "park":
        p.rect(80, 700, 116, 1030, (150, 106, 64), r=8)
        for dx, dy, r in ((-60, -10, 70), (60, -10, 74), (0, -70, 84), (0, 20, 70)): p.ell(98 + dx, 640 + dy, r, r * 0.9, (90, 180, 100))
        p.rect(470, 940, 680, 960, (196, 128, 74), r=6)
        p.rect(490, 960, 504, 1030, (160, 100, 60), r=4)
        p.rect(646, 960, 660, 1030, (160, 100, 60), r=4)
        for x in (300, 360, 420):
            p.line([(x, 1030), (x, 990)], (70, 160, 80), 4)
            p.ell(x, 984, 10, 10, (255, 140, 180), ow=2)
    elif name == "beach":
        p.rect(-10, 760, W + 10, 1030, (90, 180, 240), ow=0)
        for y in (800, 860, 920):
            for x in range(-20, W, 90): p.arc(x + 45, y, 45, 9, 200, 340, WHITE, 4)
        p.rect(-10, 1030, W + 10, H + 10, (246, 222, 170), ow=0)
        p.line([(620, 780), (620, 1030)], (150, 110, 70), 6)
        for i in range(5):
            p.d.pieslice(p.box(620, 800, 120, 74), 180 + i * 36, 216 + i * 36, fill=(232, 72, 60) if i % 2 == 0 else WHITE)
        p.ell(600, 140, 50, 50, (255, 214, 64))
    elif name == "night":
        rnd = random.Random(8)
        for _ in range(36):
            x, y, r = rnd.uniform(0, W), rnd.uniform(0, 760), rnd.choice((1.6, 2.2, 2.8))
            p.ell(x, y, r, r, (255, 250, 220), ow=0)
        p.ell(560, 180, 50, 50, (255, 246, 200), ow=0)
        p.ell(584, 166, 46, 46, PLACE_COL["night"], ow=0)
        p.rect(80, 600, 92, 1030, (90, 96, 130), r=4, ow=0)
        p.ell(86, 590, 26, 18, (255, 236, 150), ow=0)
    return img


# ================================================================ the body, drawn from the pose
def rig_for(name):
    sp = CAST[name]
    h = sp["h"]
    hs = h ** 0.6 if h < 0.95 else 1.0
    sp["hs"] = hs
    top, mid, size = HEADS[sp["head"]]
    return dict(kind="bird" if sp.get("bird") else "body", name=name, s=h, h=h, hs=hs, hipw=16 * h, thigh=82 * h, shin=80 * h,
                foot=13 * h, leg=30 * h, torso=148 * h, shw=38 * h, waist=34 * h * sp.get("belly", 1.0), upper=70 * h, fore=64 * h,
                arm=24 * h, handr=13 * h, n2t=(300 - top) * hs, n2c=(300 - mid) * hs, hr=size * hs)


def skeleton(a, P):
    D = a.rig
    h = D["h"]
    lean = math.radians(P["lean"])
    cl, sl = math.cos(lean), math.sin(lean)
    rot = math.radians(P["rot"])
    cr, sr = math.cos(rot), math.sin(rot)
    up = lambda x, y: (x * cl + y * sl, -x * sl + y * cl)
    R = lambda q: (q[0] * cr + q[1] * sr, -q[0] * sr + q[1] * cr)
    L = {}
    if D["kind"] == "bird":
        L["hc"] = (0.0, -50 * h)
        L["ht"] = (0.0, -110 * h)
        L["hand", 1] = L["hand", -1] = (0.0, 40 * h)
        X, Y = P["x"], FLOOR - 340 - P["lift"]
        Wd = {k: (X + v[0], Y + v[1]) for k, v in L.items()}
        Wd["T"] = lambda x, y, upper=True: (X + x, Y + y)
        Wd["head_ang"] = P["tilt"]
        return Wd
    for side in (-1, 1):
        t, s = (math.radians(v) for v in P["legs"][side])
        hx = side * D["hipw"]
        kx, ky = hx + D["thigh"] * math.sin(t), D["thigh"] * math.cos(t)
        L["hip", side] = (hx, 0.0)
        L["knee", side] = (kx, ky)
        L["ankle", side] = (kx + D["shin"] * math.sin(s), ky + D["shin"] * math.cos(s))
    L["neck"] = up(0, -D["torso"])
    for side in (-1, 1):
        sx, sy = up(side * D["shw"], -D["torso"] + 12 * h)
        u, f = (math.radians(v + P["lean"]) for v in P["arms"][side])
        ex, ey = sx + D["upper"] * math.sin(u), sy + D["upper"] * math.cos(u)
        L["sh", side] = (sx, sy)
        L["elb", side] = (ex, ey)
        L["hand", side] = (ex + D["fore"] * math.sin(f), ey + D["fore"] * math.cos(f))
    hang = math.radians(P["tilt"] + P["lean"])
    nx, ny = L["neck"]
    L["hc"] = (nx - D["n2c"] * math.sin(hang), ny - D["n2c"] * math.cos(hang))
    L["ht"] = (nx - D["n2t"] * math.sin(hang), ny - D["n2t"] * math.cos(hang))
    turned = abs(P["rot"]) > 0.01
    if turned:
        for k in L:
            L[k] = R(L[k])
    low = max(L["ankle", s][1] + D["foot"] for s in (-1, 1))
    low = max(low, max(L["knee", s][1] + D["leg"] * 0.5 for s in (-1, 1)), L["hc"][1] + D["hr"] * 0.95)
    if abs(P["rot"]) > 30:
        low = max(low, max(L["sh", s][1] + D["arm"] * 0.6 for s in (-1, 1)), max(L["hand", s][1] + D["handr"] * 0.5 for s in (-1, 1)))
    X, Y = P["x"], FLOOR - P["lift"] - low
    Wd = {k: (X + v[0], Y + v[1]) for k, v in L.items()}

    def tf(x, y, upper=True):
        q = up(x, y) if upper else (x, y)
        if turned:
            q = R(q)
        return (X + q[0], Y + q[1])
    Wd["T"] = tf
    Wd["head_ang"] = P["tilt"] + P["lean"] + P["rot"]
    return Wd


def mid(a, b, k):
    return (a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k)


def draw_char(img, pen, a, P):
    sp = CAST[a.name]
    D = a.rig
    h = D["h"]
    sk = skeleton(a, P)
    tf = sk["T"]
    if D["kind"] == "bird":
        draw_parrot(pen, sk, P, h)
        return sk
    skin = sp["skin"]
    legc = sp["legs"]
    long_dress = sp.get("saree") or sp.get("dhoti")
    for side in (-1, 1):
        hip, knee, ank = sk["hip", side], sk["knee", side], sk["ankle", side]
        pen.limb([hip, knee, ank], D["leg"] * (0.85 if long_dress else 1), legc)
        if sp.get("stripe"):
            pen.line([hip, knee, ank], WHITE, 3 * h)
        if sp.get("shorts"):
            pen.limb([hip, mid(hip, knee, 0.55)], D["leg"] + 6 * h, sp["shorts"])
        fdir = P.get("fdir") or side
        cx, cy = ank[0] + fdir * 9 * h, ank[1] + 6 * h
        pen.ell(cx, cy, 24 * h, 12 * h, sp.get("shoe", WHITE))
        pen.line([(cx - 20 * h, cy + 7 * h), (cx + 20 * h, cy + 7 * h)], sp.get("sole", (150, 150, 150)), 4 * h)
    wst = D["waist"]
    if sp.get("shorts"):
        pen.poly([tf(-wst, -6 * h), tf(wst, -6 * h), tf(wst + 4 * h, 24 * h, False), tf(-wst - 4 * h, 24 * h, False)], sp["shorts"])
    elif sp.get("dress"):
        pen.poly([tf(-wst, -20 * h), tf(wst, -20 * h), tf(wst + 26 * h, D["thigh"] * 0.8, False), tf(-wst - 26 * h, D["thigh"] * 0.8, False)], sp["dress"])
        pen.line([tf(-wst - 22 * h, D["thigh"] * 0.72, False), tf(wst + 22 * h, D["thigh"] * 0.72, False)], WHITE, 6 * h)
    elif sp.get("saree"):
        hem = D["thigh"] + D["shin"] - 4 * h
        pen.poly([tf(-wst, -10 * h), tf(wst, -10 * h), tf(wst + 18 * h, hem, False), tf(-wst - 18 * h, hem, False)], sp["saree"])
        pen.line([tf(-wst - 16 * h, hem - 10 * h, False), tf(wst + 16 * h, hem - 10 * h, False)], sp["border"], 8 * h)
        for k in (-1, 0, 1):
            pen.line([tf(k * 8 * h, 10 * h, False), tf(k * 14 * h, hem - 18 * h, False)], shade(sp["saree"], 0.8), 2.4 * h)
    elif sp.get("dhoti"):
        hem = D["thigh"] + D["shin"] * 0.62
        pen.poly([tf(-wst, -8 * h), tf(wst, -8 * h), tf(wst + 14 * h, hem, False), tf(-wst - 14 * h, hem, False)], sp["dhoti"])
        for k in (-1, 1):
            pen.line([tf(k * 10 * h, 0, False), tf(k * 18 * h, hem - 8 * h, False)], shade(sp["dhoti"], 0.85), 2.2 * h)
    elif sp.get("kurta"):
        pen.poly([tf(-wst, -10 * h), tf(wst, -10 * h), tf(wst + 10 * h, D["thigh"] * 0.85, False), tf(-wst - 10 * h, D["thigh"] * 0.85, False)], sp["kurta"])
    else:
        pen.poly([tf(-wst, -6 * h), tf(wst, -6 * h), tf(wst + 2 * h, 16 * h, False), tf(-wst - 2 * h, 16 * h, False)], legc)
    to = D["torso"]
    shw = D["shw"]
    body = [tf(-shw - 6 * h, -to + 2 * h), tf(shw + 6 * h, -to + 2 * h), tf(wst + 7 * h, -to * 0.45), tf(wst + 2 * h, 8 * h),
            tf(-wst - 2 * h, 8 * h), tf(-wst - 7 * h, -to * 0.45)]
    if sp.get("hood"):
        pen.ell(*tf(0, -to - 4 * h), 40 * h, 18 * h, shade(sp["top"], 0.9))
    pen.poly(body, sp["top"])
    if sp.get("stripe"):
        pen.line([tf(0, -to + 6 * h), tf(0, 4 * h)], WHITE, 3 * h)
    if sp.get("hood"):
        for k in (-1, 1): pen.line([tf(k * 9 * h, -to + 6 * h), tf(k * 9 * h, -to + 40 * h)], WHITE, 2.6 * h)
    if sp.get("towel"):
        pen.limb([tf(shw - 4 * h, -to - 4 * h), tf(shw - 12 * h, -to * 0.35)], 22 * h, sp["towel"], ow=2.4)
        pen.ell(*tf(0, -to + 6 * h), 16 * h, 14 * h, skin, ow=0)
    if a.name == "ammamma":
        pc = shade(sp["saree"], 1.1)
        pen.poly([tf(-shw - 4 * h, -to - 2 * h), tf(-shw + 34 * h, -to - 2 * h), tf(wst + 8 * h, -to * 0.2), tf(wst - 26 * h, 0)], pc, ow=2.4)
        pen.line([tf(-shw + 34 * h, -to - 2 * h), tf(wst + 8 * h, -to * 0.2)], sp["border"], 5 * h)
        pen.line([tf(-shw - 4 * h, -to - 2 * h), tf(wst - 26 * h, 0)], sp["border"], 5 * h)
    if sp.get("dupatta"):
        for k in (-1, 1):
            pen.limb([tf(k * (shw - 8 * h), -to - 2 * h), tf(k * (shw - 12 * h), -to * 0.25)], 16 * h, sp["dupatta"], ow=2.2)
    if a.name == "teacher":
        for i in range(6):
            pen.ell(*tf(26 * h + i * 1.2 * h, -to - 20 * h + i * 20 * h), 10 * h, 12 * h, (34, 24, 22), ow=1.8)
        pen.ell(*tf(34 * h, -to + 104 * h), 8 * h, 6 * h, (255, 79, 139), ow=1.6)

    def arms():
        for side in (-1, 1):
            sh, el, hd = sk["sh", side], sk["elb", side], sk["hand", side]
            pen.limb([sh, el, hd], D["arm"], skin)
            sl = sp.get("sleeve", 0.4)
            if sl > 0:
                endp = mid(sh, el, min(1.0, sl * 2)) if sl <= 0.5 else mid(el, hd, (sl - 0.5) * 2)
                pts = [sh, endp] if sl <= 0.5 else [sh, el, endp]
                pen.limb(pts, D["arm"] + 5 * h, sp["top"])
            if sp.get("puff"):
                pen.ell(*mid(sh, el, 0.25), 18 * h, 14 * h, sp["top"])
            pen.ell(hd[0], hd[1], D["handr"], D["handr"] * 0.92, WHITE if sp.get("glove") else skin)
    if not P["arms_front"]:
        arms()
    pic, nx, ny = head_pic(a.name, P["eyes"], P["mouth"], P["look"], P["turn"])
    im, qx, qy = T.rot_about(pic, sk["head_ang"], nx, ny, Image.BILINEAR)
    neck = sk["neck"]
    img.paste(im, (int(round(neck[0] * K - qx)), int(round(neck[1] * K - qy))), im)
    if P["arms_front"]:
        arms()
    return sk


def draw_parrot(pen, sk, P, h):
    x, y = sk["T"](0, 0)
    lk = -1 if P["look"] < 0 else 1
    g, gd = (61, 190, 90), (42, 154, 69)
    pen.poly([(x - 10, y + 40), (x + 10, y + 40), (x + 4, y + 110), (x - 4, y + 110)], (60, 140, 230))
    for side in (-1, 1):
        a = math.radians(side * (20 + P["flap"]))
        bx, by = x + side * 24, y - 6
        tip = (bx + math.sin(a) * 30 * side + side * 26, by + math.cos(a) * 46)
        pen.poly([(bx, by - 10), (bx + side * 12, by), tip, (bx - side * 2, by + 34)], gd)
    pen.ell(x, y + 10, 34, 48, g)
    pen.ell(x, y + 20, 20, 30, (166, 227, 126), ow=0)
    hx, hy = x, y - 48
    pen.ell(hx, hy, 30, 29, g)
    pen.ell(hx - lk * 12, hy + 6, 9, 6, (255, 214, 64), ow=0)
    if P["eyes"] in ("blink", "happy", "sleep"):
        pen.arc(hx + lk * 8, hy - 6, 7, 5, 200 if P["eyes"] == "happy" else 20, 340 if P["eyes"] == "happy" else 160, INK, 2.6)
    else:
        r = 7.5 if P["eyes"] == "wide" else 5.5
        pen.ell(hx + lk * 8, hy - 6, r, r * 1.25, INK, ow=0)
        pen.ell(hx + lk * 10, hy - 9, 1.8, 1.8, WHITE, ow=0)
    opened = P["mouth"] in ("half", "open", "o", "smile", "grin", "tongue")
    gap = 6 if opened else 0
    if opened:
        pen.poly([(hx + lk * 8, hy + 4), (hx + lk * 24, hy + 6), (hx + lk * 12, hy + 4 + gap * 2)], (150, 30, 50), ow=0)
    pen.poly([(hx + lk * 6, hy + 8 + gap), (hx + lk * 20, hy + 8 + gap), (hx + lk * 10, hy + 16 + gap)], (190, 80, 30), ow=2)
    pen.poly([(hx + lk * 4, hy - 2), (hx + lk * 26, hy + 2), (hx + lk * 12, hy + 12)], (240, 122, 42), ow=2.2)
    for side in (-1, 1): pen.ell(x + side * 10, y + 58, 6, 4, (240, 140, 60), ow=1.6)


# ================================================================ acting
def arm(P, side, u, f):
    P["arms"][side] = (side * u, side * f)


def reach(a, P, side, tx, ty):
    """Puts a hand at (tx, ty) (hips at 0, 0), bending the elbow outwards."""
    D = a.rig
    sx, sy = side * D["shw"], -D["torso"] + 12 * D["h"]
    dx, dy = tx - sx, ty - sy
    d = clampf(math.hypot(dx, dy), 1.0, D["upper"] + D["fore"] - 0.5)
    base = math.atan2(dx, dy)
    al = math.acos(clampf((D["upper"] ** 2 + d * d - D["fore"] ** 2) / (2 * D["upper"] * d), -1, 1))
    u = base + side * al
    ex, ey = sx + D["upper"] * math.sin(u), sy + D["upper"] * math.cos(u)
    P["arms"][side] = (math.degrees(u), math.degrees(math.atan2(tx - ex, ty - ey)))


def spot(a, where, side=1):
    """Where things are on the body (hips at 0, 0): the hips, chin, eyes, mouth, chest."""
    D = a.rig
    hy = -(D["torso"] + D["n2c"])
    return {"hips": (side * (D["waist"] + 4 * D["h"]), -30 * D["h"]), "chin": (side * 16 * D["h"], hy + D["hr"] * 0.72),
            "eyes": (side * 20 * D["h"], hy + 6 * D["h"]), "mouth": (side * 6 * D["h"], hy + D["hr"] * 0.45),
            "chest": (side * 6 * D["h"], -D["torso"] * 0.55), "belly": (side * 12 * D["h"], -D["torso"] * 0.25),
            "cross": (-side * 28 * D["h"], -D["torso"] * 0.6)}[where]


def legs(P, side, t, s):
    P["legs"][side] = (side * t, side * s)


class Actor(T.Actor):
    def __init__(self, name, home, idx):
        self.name, self.home, self.idx = name, home, idx
        self.rig = rig_for(name)
        self.phase = random.random() * 6
        self.blink_every = 2.6 + random.random() * 1.8
        self.pitch = T.PITCH.get(name, 260)
        self.off = -190 if home <= 360 else W + 190


class Story(T.Story):
    def __init__(self, plan):
        self.plan, self.ground = plan, FLOOR
        self.funny = plan.get("mood", "funny") in ("funny", "happy")
        names = []
        for c in plan.get("cast") or []:
            c = ALIAS.get(c, c)
            if c in CAST and c not in names:
                names.append(c)
        cast = names[:3] or ["bigd"]
        for b in plan.get("beats") or []:
            b["who"] = ALIAS.get(b.get("who"), b.get("who"))
        slots = {1: [360], 2: [220, 500], 3: [140, 360, 580]}[len(cast)]
        self.actors = [Actor(n, slots[i], i) for i, n in enumerate(cast)]
        self.by = {a.name: a for a in self.actors}
        self.beats, self.fx, self.sfx = [], [], []
        self._pc, self._sk, self._cams = {}, {}, {}
        self.build()
        self.plan_shots()

    # ---------------------------------------------------------- extra effects for the new actions
    def plan_effects(self):
        super().plan_effects()
        for b in self.beats:
            a = self.by[b["who"]]
            d, t0 = b["do"], b["t0"]
            if d == "clap" or d == "cheer":
                for k in range(int((b["tb"] - t0) / 0.3)):
                    self.sfx.append((t0 + 0.15 + k * 0.3, "clap", 0))
                if d == "cheer":
                    self.sfx.append((t0, "ding", 0))
                    self.fx.append(dict(k="sparkle", a=a.name, t0=t0, t1=b["tb"]))
            elif d == "angry":
                self.sfx.append((t0 + 0.05, "hmm", a.pitch * 0.8))
                self.fx.append(dict(k="steam", a=a.name, t0=t0, t1=b["tb"]))
                for k in range(int((b["tb"] - t0) / 0.3)):
                    self.sfx.append((t0 + k * 0.3, "step", 0))
            elif d == "shy":
                self.fx.append(dict(k="hearts", a=a.name, t0=t0 + 0.3, t1=t0 + 1.6))
            elif d in ("facepalm", "shrug"):
                self.sfx.append((t0 + 0.2, "hmm", a.pitch))
            elif d == "point":
                self.sfx.append((t0 + 0.1, "pop", 0))
            elif d == "kneel":
                self.sfx.append((t0 + 0.5, "land", 0))

    # ---------------------------------------------------------- the camera: wide shots and close-ups
    def plan_shots(self):
        shots = []
        wide_acts = ("enter", "exit", "run", "slip", "fall", "give", "dance", "jump", "kneel", "cheer")
        close_acts = ("surprised", "cry", "facepalm", "shy", "angry", "think", "eat", "crossarms", "shrug")
        n = 0
        for i, b in enumerate(self.beats):
            d = b["do"]
            close = i > 0 and b["say"] and d not in wide_acts and (d in close_acts or n % 2 == 1) and self.by[b["who"]].rig["kind"] != "bird"
            n += 1
            shots.append((b["t0"], b["t1"], "close" if close else "wide", b["who"]))
            if d in ("slip", "fall"):
                imp = b["t0"] + (T.SLIP_WALK + T.SLIP_SLIDE if d == "slip" else 0.45)
                others = [o for o in self.actors if o.name != b["who"] and b["before"][o.name]["on"] and o.rig["kind"] != "bird"]
                if others and b["tl"] - imp > 0.9:
                    shots.append((imp + 0.3, b["tl"] - 0.08, "close", others[0].name))
        self.shots = shots

    def camera(self, t):
        shot = None
        for s in self.shots:
            if s[0] <= t < s[1]:
                shot = s
        if not shot or shot[2] == "wide" or t >= self.fin0:
            return (0.0, 0.0, 1.0)
        if shot in self._cams:
            return self._cams[shot]
        a = self.by[shot[3]]
        sk = self.skel(a, shot[0] + 0.3)
        z = 1.5
        vw, vh = W / z, H / z
        hx, ht = sk["ht"]
        cam = (clampf(hx - vw / 2, 0, W - vw), clampf(ht - 600 / z, 0, H - vh), z)
        self._cams[shot] = cam
        return cam

    def skel(self, a, t):
        key = (a.name, round(t, 4))
        if key not in self._sk:
            if len(self._sk) > 3000:
                self._sk.clear()
            self._sk[key] = skeleton(a, self.pose(a, t))
        return self._sk[key]

    def head_top(self, a, P):
        return skeleton(a, P)["ht"]

    # ---------------------------------------------------------- poses
    def _pose(self, a, t):
        b = self.beat_at(t)
        if b is not None:
            st = b["before"][a.name]
        elif self.beats and t < self.beats[0]["t0"]:
            st = self.beats[0]["before"][a.name]
        else:
            st = self.after[a.name]
        ph = a.phase
        P = dict(x=st["x"], on=st["on"], lift=0.0, lean=0.0, rot=0.0, legs={-1: (-3.0, -2.0), 1: (3.0, 2.0)},
                 arms={-1: (-8.0, -4.0), 1: (8.0, 4.0)}, tilt=1.5 * math.sin(2 * math.pi * 0.35 * t + ph), turn=0.0, look=0,
                 eyes="open", mouth="closed", held=st["held"], head=st["head"], hand=1, arms_front=False, pscale=1.0,
                 pdx=0.0, pdy=0.0, headwob=0.0, flap=8 * math.sin(2 * math.pi * 2.2 * t + ph), fdir=0, sx=1.0, sy=1.0)
        for side in (-1, 1):
            u, f = P["arms"][side]
            P["arms"][side] = (u + side * 2 * math.sin(2 * math.pi * 0.4 * t + ph + side), f + side * 3 * math.sin(2 * math.pi * 0.4 * t + ph + side))
        if (t + ph * 3) % a.blink_every < 0.13:
            P["eyes"] = "blink"
        if a.rig["kind"] == "bird":
            P["lift"] = 14 * math.sin(2 * math.pi * 0.8 * t + ph)
            P["flap"] = 30 * abs(math.sin(2 * math.pi * 3.0 * t + ph))
        others = [o for o in self.actors if o is not a]
        if b is not None and b["who"] != a.name:
            sx = b["before"][b["who"]]["x"]
            P["look"] = 0 if abs(sx - P["x"]) < 5 else (1 if sx > P["x"] else -1)
        elif others:
            near = min(others, key=lambda o: abs(o.home - P["x"]))
            P["look"] = 1 if near.home > P["x"] else -1
        P["turn"] = 0.5 * P["look"]
        if b is None:
            if self.beats and self.fin0 <= t < self.fin0 + T.FIN and P["on"]:
                if self.funny:
                    self.laugh_pose(a, P, t - self.fin0)
                else:
                    P["eyes"], P["mouth"] = "happy", "smile"
                    arm(P, 1, 140 + 18 * math.sin(2 * math.pi * 2.4 * (t - self.fin0)), 172)
            elif t >= self.fin0 + T.FIN and P["on"]:
                P["mouth"] = "smile" if P["eyes"] != "blink" else "closed"
        elif b["who"] == a.name:
            self.act(a, b, t, P)
        else:
            self.react(a, b, t, P)
        if st["asleep"] and not (b is not None and b["who"] == a.name):
            P["eyes"], P["tilt"], P["mouth"] = "sleep", 12.0, "closed"
        return P

    def walk_pose(self, a, P, u, dirn, speed=1.0):
        run = speed >= 1.5
        f = 1.9 * speed
        for side in (-1, 1):
            psi = 2 * math.pi * f * u + (0 if side < 0 else math.pi)
            A, B = (40, 85) if run else (24, 52)
            th = dirn * A * math.sin(psi)
            bend = B * max(0.0, math.cos(psi))
            P["legs"][side] = (th, th - dirn * bend)
            ua = -dirn * (34 if run else 18) * math.sin(psi) + side * 6
            P["arms"][side] = (ua, ua + dirn * (75 if run else 12))
        P["lean"] = -dirn * (9 if run else 3)
        P["look"], P["turn"], P["fdir"] = dirn, 0.8 * dirn, dirn
        if a.rig["kind"] == "bird":
            P["flap"] = 40 * abs(math.sin(2 * math.pi * 6 * u))

    def laugh_pose(self, a, P, u):
        P["eyes"] = "happy"
        P["mouth"] = "grin" if int(u * 7) % 2 == 0 else "open"
        P["lean"] = 6 * math.sin(2 * math.pi * 3.5 * u)
        P["lift"] += 5 * abs(math.sin(2 * math.pi * 3.5 * u))
        if a.rig["kind"] != "bird":
            reach(a, P, -1, *spot(a, "belly", -1))
            reach(a, P, 1, *spot(a, "belly", 1))
        P["flap"] = 40 * abs(math.sin(2 * math.pi * 5 * u))

    def set_arm(self, a, P, ang, side=1):
        arm(P, side or 1, ang, ang + 30)

    def act(self, a, b, t, P):
        d, u = b["do"], t - b["t0"]
        dirn = b["dirn"]
        side = (1 if dirn > 0 else -1)
        talk = self.talking(b, t)
        if b["prop"] and d in ("talk", "enter", "exit", "run", "jump", "laugh", "surprised", "think", "dance", "wave", "cry",
                               "sleep", "point", "clap", "shrug", "crossarms", "cheer", "kneel", "angry", "shy"):
            if P["head"] != b["prop"]:
                P["held"] = b["prop"]
        if d == "enter":
            walk = b["lead"] - 0.2
            P["on"] = True
            if u < walk:
                P["x"] = T.lerp(b["x0"], b["x1"], u / max(0.01, walk))
                self.walk_pose(a, P, u, b["dirn"])
            else:
                P["x"] = b["x1"]
        elif d == "exit":
            ts_ = b["tb"] + 0.15
            if t >= ts_:
                k = (t - ts_) / max(0.01, b["post"] - 0.1)
                P["x"] = T.lerp(b["x0"], b["x1"], clampf(k, 0, 1))
                self.walk_pose(a, P, t - ts_, b["dirn"])
        elif d == "run":
            if u < b["lead"]:
                self.walk_pose(a, P, u, b["dirn"], speed=1.9)
                P["x"] = b["before"][a.name]["x"] + b["dirn"] * 70 * math.sin(math.pi * u / b["lead"])
            else:
                P["mouth"] = "open" if int(u * 4) % 2 == 0 else "half"
        elif d == "jump":
            if u < 0.2:
                k = u / 0.2
                for s_ in (-1, 1): legs(P, s_, 10 + 30 * k, -10 - 40 * k)
                arm(P, -1, 20, 10)
                arm(P, 1, 20, 10)
            elif u < 0.8:
                k = (u - 0.2) / 0.6
                P["lift"] += 170 * math.sin(math.pi * k)
                for s_ in (-1, 1): legs(P, s_, 18, -30)
                arm(P, -1, 155, 170)
                arm(P, 1, 155, 170)
                P["eyes"], P["mouth"] = "happy", "grin"
                P["flap"] = 45 * abs(math.sin(2 * math.pi * 6 * u))
            elif u < 0.95:
                k = (u - 0.8) / 0.15
                for s_ in (-1, 1): legs(P, s_, 30 * (1 - k), -40 * (1 - k))
        elif d in ("slip", "fall"):
            self.fall_pose(a, b, t, P)
        elif d == "laugh":
            if t < b["tb"] + 0.2:
                self.laugh_pose(a, P, u)
        elif d == "surprised":
            if u < 0.45:
                P["lift"] += 50 * math.sin(math.pi * u / 0.45)
            if t < b["tb"]:
                arm(P, -1, 50, 95)
                arm(P, 1, 50, 95)
                P["eyes"], P["mouth"] = "wide", "o"
        elif d == "think":
            if t < b["tb"]:
                P["arms_front"] = True
                fh = -1 if P["held"] and (P.get("hand") or 1) == 1 else 1      # the free hand goes to the chin
                reach(a, P, fh, *spot(a, "chin", fh))
                reach(a, P, -fh, *spot(a, "cross", -fh))
                P["tilt"], P["look"], P["turn"] = -8, 0, 0
                P["eyes"] = "side" if int(u * 1.5) % 2 == 0 else "open"
        elif d == "dance":
            if t < b["tb"]:
                w = 2 * math.pi * 1.6 * u
                arm(P, -1, 90 + 60 * math.sin(w), 120 + 50 * math.sin(w))
                arm(P, 1, 90 - 60 * math.sin(w), 120 - 50 * math.sin(w))
                for s_ in (-1, 1): legs(P, s_, 6 + 10 * max(0, math.sin(w + (0 if s_ < 0 else math.pi))), -22 * max(0, math.sin(w + (0 if s_ < 0 else math.pi))))
                P["lean"] = 8 * math.sin(w / 2)
                P["lift"] += 8 * abs(math.sin(w))
                P["eyes"], P["mouth"] = "happy", "smile"
        elif d == "sleep":
            if t >= b["tt"] + 0.1:
                P["eyes"], P["tilt"] = "sleep", 14.0
        elif d == "cry":
            if t < b["tb"]:
                P["eyes"], P["mouth"] = "sleep", "sad"
                P["lift"] += 2 * math.sin(2 * math.pi * 5 * u)
                P["arms_front"] = True
                reach(a, P, -1, *spot(a, "eyes", -1))
                reach(a, P, 1, *spot(a, "eyes", 1))
        elif d == "wave":
            if t < b["tb"]:
                ws = -1 if b["before"][a.name]["x"] > 360 else 1
                arm(P, ws, 135, 170 + 22 * math.sin(2 * math.pi * 2.6 * u))
                P["eyes"] = "happy" if int(u * 2) % 3 == 0 else P["eyes"]
                P["mouth"] = "smile"
        elif d == "give":
            walk = b["walk"]
            hand_t = b["t0"] + walk + 0.42
            hs = 1 if b["dirn"] > 0 else -1
            P["hand"] = hs
            P["held"] = b["gift"] if t < hand_t else None
            if P["head"] == b["gift"]:
                P["head"] = None
            if u < walk:
                P["x"] = T.lerp(b["x0"], b["x1"], T.ease_io(u / max(0.01, walk)))
                self.walk_pose(a, P, u, b["dirn"])
            elif t < b["tb"] + T.GAP * 0.4:
                P["x"] = b["x1"]
                if u < walk + 0.6:
                    arm(P, hs, 78, 86)
                P["eyes"] = "happy" if t > hand_t else P["eyes"]
                P["mouth"] = "smile"
            else:
                k = (t - (b["tb"] + T.GAP * 0.4)) / max(0.01, b["post"] + T.GAP * 0.6)
                P["x"] = T.lerp(b["x1"], b["x0"], T.ease_io(k))
                self.walk_pose(a, P, t - b["tb"], -b["dirn"])
        elif d == "eat":
            p = b["prop"]
            if u < 1.2:
                P["held"], P["hand"] = p, 1
                P["arms_front"] = True
                k = clampf(u / 0.3, 0, 1)
                mx, my = spot(a, "mouth", 1)
                cx, cy = spot(a, "chest", 1)
                reach(a, P, 1, T.lerp(cx + 30 * a.rig["h"], mx, k), T.lerp(cy, my, k))
                P["pscale"] = 1 - 0.75 * clampf((u - 0.3) / 0.9, 0, 1)
                P["mouth"] = "open" if int(u * 5) % 2 == 0 else "closed"
            else:
                P["held"] = None
                P["eyes"], P["mouth"] = "happy", "smile"
        elif d == "balance":
            P["head"] = b["prop"]
            if P["held"] == b["prop"]:
                P["held"] = None
            w = 2 * math.pi * u
            arm(P, -1, 82 + 9 * math.sin(1.3 * w + 1), 90 + 12 * math.sin(1.3 * w))
            arm(P, 1, 82 + 9 * math.sin(1.3 * w), 90 + 12 * math.sin(1.3 * w + 1))
            P["lean"] = 4 * math.sin(0.9 * w)
            P["headwob"] = 8 * math.sin(1.1 * w)
            P["eyes"] = "wide" if u < 0.6 else P["eyes"]
        elif d == "point":
            if t < b["tb"]:
                ps = P["look"] or 1
                arm(P, ps, 80, 90)
                P["eyes"] = "side" if not talk and u > 0.4 else P["eyes"]
        elif d == "clap":
            if t < b["tb"]:
                c = max(0.0, math.sin(2 * math.pi * 3.3 * u))
                P["arms_front"] = True
                for s_ in (-1, 1):
                    x, y = spot(a, "chest", s_)
                    reach(a, P, s_, x + s_ * 26 * a.rig["h"] * (1 - c), y)
                P["eyes"], P["mouth"] = "happy", "smile" if not talk else P["mouth"]
                P["lift"] += 4 * abs(c)
        elif d == "shrug":
            if t < b["tb"]:
                k = clampf(u / 0.3, 0, 1)
                arm(P, -1, 30 * k + 8, 110 * k)
                arm(P, 1, 30 * k + 8, 110 * k)
                P["tilt"], P["eyes"] = 9 * k, "side"
                P["mouth"] = "flat"
        elif d == "facepalm":
            if t < b["tb"]:
                P["arms_front"] = True
                fh = -1 if P["held"] and (P.get("hand") or 1) == 1 else 1
                reach(a, P, fh, *spot(a, "eyes", fh))
                P["eyes"], P["mouth"], P["tilt"] = "blink", "flat", -10 * fh
        elif d == "crossarms":
            if t < b["tb"]:
                P["arms_front"] = True
                reach(a, P, -1, *spot(a, "cross", -1))
                reach(a, P, 1, *spot(a, "cross", 1))
                P["eyes"] = "side" if not talk else "angry"
                P["mouth"] = "flat"
                P["turn"] = -0.5 * (P["look"] or 1)
        elif d == "cheer":
            if t < b["tb"]:
                w = 2 * math.pi * 2.2 * u
                arm(P, -1, 150 + 10 * math.sin(w), 168)
                arm(P, 1, 150 - 10 * math.sin(w), 168)
                P["lift"] += 14 * abs(math.sin(w))
                P["eyes"], P["mouth"] = "happy", "grin"
        elif d == "kneel":
            k = clampf(u / 0.5, 0, 1)
            ld = P["look"] or 1
            for s_ in (-1, 1):
                P["legs"][s_] = (s_ * 4 + ld * 6 * k, -ld * 92 * k + s_ * 2 * (1 - k))
            arm(P, -1, 18 + 10 * k, -10 * k)
            arm(P, 1, 18 + 10 * k, -10 * k)
            P["tilt"] = 6 * k * ld
        elif d == "angry":
            if t < b["tb"]:
                arm(P, -1, 12, 6)
                arm(P, 1, 12, 6)
                st_ = int(u * 3.3) % 2
                legs(P, -1 if st_ else 1, 14, -24)
                P["eyes"], P["mouth"] = "angry", "grin" if not talk else P["mouth"]
                P["lean"] = 2 * math.sin(2 * math.pi * 6 * u)
        elif d == "shy":
            if t < b["tb"]:
                P["arms_front"] = True
                for s_ in (-1, 1):
                    x, y = spot(a, "belly", s_)
                    reach(a, P, s_, x * 0.4, y)
                P["tilt"] = 12 * (P["look"] or 1)
                P["turn"] = -0.5 * (P["look"] or 1)
                P["eyes"] = "happy" if not talk else "open"
                P["lean"] = 3 * math.sin(2 * math.pi * 0.8 * u)
        if talk and not (d == "cry" and int(u * 6) % 2):
            m = self.flap_mouth(b, t)
            if P["eyes"] == "happy" and m == "closed":
                m = "smile"
            P["mouth"] = m
            if d in ("talk", "enter", "give", "exit", "surprised", "sleep", "wave", "run") and a.rig["kind"] != "bird":
                look = P["look"] or 1
                g = math.sin(2 * math.pi * 0.8 * (t - b["tl"]))
                if abs(P["arms"][look][0]) < 40:
                    arm(P, look, 28 + 22 * max(0.0, g), 70 + 40 * max(0.0, g))
                P["tilt"] += 4 * math.sin(2 * math.pi * 1.1 * (t - b["tl"]))
                P["turn"] = 0.6 * look
        elif d == "talk" and t >= b["tt"]:
            P["mouth"] = "closed"

    def fall_pose(self, a, b, t, P):
        u = t - b["t0"]
        dirn = b["dirn"]
        if b["do"] == "slip":
            walk, imp = T.SLIP_WALK, T.SLIP_WALK + T.SLIP_SLIDE
            x0, px = b["x0"], b["px"]
            if u < walk:
                P["x"] = T.lerp(x0, px - dirn * 25, u / walk)
                self.walk_pose(a, P, u, dirn)
                return
            if u < imp:
                k = (u - walk) / T.SLIP_SLIDE
                P["x"] = px - dirn * 25 + dirn * 60 * k
                P["rot"] = 88 * dirn * T.ease_in(k)
                P["lift"] += 40 * math.sin(math.pi * k)
                for s_ in (-1, 1): P["legs"][s_] = (dirn * 40, dirn * 30)
                arm(P, -1, 140, 160)
                arm(P, 1, 140, 160)
                P["eyes"], P["mouth"] = "wide", "o"
                return
            lx = b["lx"]
        else:
            imp = 0.45
            lx = b["x0"]
            if u < imp:
                k = u / imp
                P["rot"] = 88 * dirn * T.ease_in(k)
                arm(P, -1, 120, 140)
                arm(P, 1, 120, 140)
                P["eyes"], P["mouth"] = "wide", "o"
                return
        P["x"] = lx
        tup = b["tb"] + 0.15
        if t < tup:
            P["rot"] = 88 * dirn + 2 * math.sin(2 * math.pi * 3 * u)
            arm(P, -dirn, 4, 2)                         # the arm on the floor side lies along the body
            arm(P, dirn, 60 + 10 * math.sin(2 * math.pi * 1.5 * u), 80)
            for s_ in (-1, 1): P["legs"][s_] = (dirn * 12, dirn * 4)
            P["eyes"] = "dizzy" if u < imp + 0.6 else ("wide" if not self.talking(b, t) else "open")
            P["mouth"] = "o" if not self.talking(b, t) else P["mouth"]
        else:
            k = (t - tup) / 0.5
            if k <= 1:
                P["rot"] = 88 * dirn * (1 - T.ease_out(k))
                P["lift"] += 10 * math.sin(math.pi * clampf(k, 0, 1))
            elif b.get("back", 0) > 0.05:
                kk = clampf((t - tup - 0.5) / b["back"], 0, 1)
                P["x"] = T.lerp(lx, b["x0"], T.ease_io(kk))
                if kk < 1:
                    self.walk_pose(a, P, t - tup, -dirn)
        fl = b.get("fly")
        if fl:
            l0, l1 = self.fly_times(b)
            if l0 <= t < l1 + (0.12 * 7 if fl == "tray" else 0):
                if P["held"] == fl:
                    P["held"] = None
                if P["head"] == fl:
                    P["head"] = None
            elif t >= l1:
                P["head"] = fl
                if P["held"] == fl:
                    P["held"] = None

    def react(self, a, b, t, P):
        d, u = b["do"], t - b["t0"]
        if d in ("slip", "fall"):
            imp = b["t0"] + (T.SLIP_WALK + T.SLIP_SLIDE if d == "slip" else 0.45)
            if imp <= t < imp + 0.75:
                P["eyes"], P["mouth"] = "wide", "o"
                P["lift"] += 10 * math.sin(math.pi * (t - imp) / 0.75)
                arm(P, -1, 45, 100)
                arm(P, 1, 45, 100)
            elif t >= imp + 0.75 and self.funny:
                self.laugh_pose(a, P, t - imp)
        elif d == "laugh" and self.funny and t < b["tb"]:
            P["eyes"], P["mouth"] = "happy", "smile"
            P["lean"] = 3 * math.sin(2 * math.pi * 5 * u)
        elif d == "cry" and t < b["tb"]:
            P["eyes"], P["mouth"] = "sad", "sad"
        elif d in ("dance", "cheer") and t < b["tb"]:
            P["eyes"], P["mouth"] = "happy", "smile"
            c = max(0.0, math.sin(2 * math.pi * 2.2 * u))
            P["arms_front"] = True
            for s_ in (-1, 1):
                x, y = spot(a, "chest", s_)
                reach(a, P, s_, x + s_ * 24 * a.rig["h"] * (1 - c), y)
        elif d == "surprised" and u < 0.8:
            P["eyes"] = "wide"
        elif d in ("angry", "crossarms") and t < b["tb"]:
            P["eyes"] = "wide" if u < 0.8 else "side"
        elif d in ("facepalm", "shrug") and t < b["tb"]:
            P["eyes"] = "side"
        elif d == "jump" and 0.8 <= u < 1.4:
            P["eyes"], P["mouth"] = "happy", "smile"
        elif d == "give" and a.name == b.get("target"):
            hand_t = b["t0"] + b["walk"] + 0.42
            hs = 1 if b["x1"] > P["x"] else -1
            P["hand"] = hs
            if t >= b["t0"] + b["walk"]:
                if t < hand_t + 0.5:
                    arm(P, hs, 72, 82)
                if t >= hand_t:
                    P["held"] = b["gift"]
                    P["eyes"], P["mouth"] = "happy", "smile"
        elif d == "balance" and t < b["tb"]:
            P["eyes"] = "wide" if u < 1.2 else P["eyes"]
        elif d == "point" and t < b["tb"]:
            P["eyes"] = "wide" if u < 0.6 else P["eyes"]

    # ---------------------------------------------------------- drawing
    def draw(self, frameK, t, R):
        pen = Pen(frameK)
        for f in self.fx:
            if f["k"] == "ground" and f["t0"] <= t < f["t1"]:
                self.draw_ground_prop(frameK, f, t, R)
        poses = {a.name: self.pose(a, t) for a in self.actors}
        shc = shade(PLACE_COL.get(self.plan.get("place"), PLACE_COL["stage"]), 0.86)
        for a in self.actors:
            P = poses[a.name]
            if P["on"] and a.rig["kind"] != "bird":
                rr = 70 * a.rig["h"] * (1 - min(0.5, P["lift"] / 400))
                pen.ell(P["x"], FLOOR + 6, rr, 12, shc, ow=0)
        order = sorted(self.actors, key=lambda a: (a.rig["kind"] == "bird", a.idx))
        for a in order:
            P = poses[a.name]
            if P["on"]:
                draw_char(frameK, pen, a, P)
                self.draw_props(frameK, a, P, R)
        for f in self.fx:
            if f["t0"] <= t < f["t1"] and f["k"] != "ground":
                self.draw_fx(frameK, f, t, R, poses)

    def draw_props(self, frameK, a, P, R):
        props = R["props"]
        sk = skeleton(a, P)
        rot = P["rot"] + P["lean"]
        if P["head"] and P["head"] in props:
            pr = props[P["head"]]
            tx, ty = sk["ht"]
            h = pr["hhalf"]
            im, qx, qy = T.rot_about(pr["head"], sk["head_ang"] + P["headwob"], h, h)
            frameK.paste(im, (int(tx * K - qx), int(ty * K - qy)), im)
        if P["held"] and P["held"] in props:
            pr = props[P["held"]]
            hx, hy = sk["hand", P.get("hand", 1) or 1]
            hx, hy = hx + P["pdx"], hy + P["pdy"]
            im, h = pr["c"], pr["half"]
            if P["pscale"] < 0.99:
                n = max(2, int(im.width * P["pscale"]))
                im = im.resize((n, n), Image.BILINEAR)
                h = n / 2
            im, qx, qy = T.rot_about(im, rot, h, h)
            frameK.paste(im, (int(hx * K - qx), int(hy * K - qy)), im)

    def draw_ground_prop(self, frameK, f, t, R):
        pr = R["props"][f["prop"]]
        x = f["x"]
        ang = 0.0
        if t >= f["imp"] - T.SLIP_SLIDE * 0.5:
            k = clampf((t - f["imp"] + T.SLIP_SLIDE * 0.5) / 0.6, 0, 1)
            x += f["dirn"] * 260 * T.ease_out(k)
            ang = -f["dirn"] * 400 * T.ease_out(k)
        im = pr["img"] if not ang else pr["img"].rotate(ang, expand=True, resample=Image.BICUBIC)
        frameK.paste(im, (int(x * K - im.width / 2), int(FLOOR * K - im.height + 16)), im)

    def draw_fx(self, frameK, f, t, R, poses):
        k = f["k"]
        u = t - f["t0"]
        F = R["fx"]
        pen = Pen(frameK)
        if k == "dust":
            kk = u / max(0.01, f["t1"] - f["t0"])
            for i in range(6):
                ang = math.pi * (0.1 + 0.8 * i / 5)
                rr = (8 + 26 * kk) * (1 - kk * 0.6)
                pen.ell(f["x"] - math.cos(ang) * (30 + 70 * kk), FLOOR - 10 - math.sin(ang) * (8 + 30 * kk), rr, rr, (236, 232, 236), ow=0)
            return
        a = self.by.get(f.get("a"))
        if not a:
            return
        P = poses[a.name]
        if not P["on"] and k != "fly":
            return
        sk = skeleton(a, P)
        hx, hy = sk["ht"]
        put = lambda im, x, y: frameK.paste(im, (int(x * K - im.width / 2), int(y * K - im.height / 2)), im)
        if k == "stars":
            for i in range(3):
                ang = 2 * math.pi * (1.4 * u + i / 3)
                put(F["star"], hx + math.cos(ang) * 46, hy - 10 + math.sin(ang) * 14)
        elif k in ("!", "?"):
            im = F[k]
            sc = min(1.0, 0.4 + u / 0.15 * 0.6)
            if sc < 1:
                im = im.resize((max(1, int(im.width * sc)), max(1, int(im.height * sc))), Image.BILINEAR)
            put(im, hx + 50 + (4 * math.sin(2 * math.pi * 2 * u) if k == "?" else 0), hy - 50)
        elif k == "zzz":
            for i in range(3):
                uu = (u + i * 0.55) % 1.65
                im = F["Z"]
                sc = 0.6 + 0.5 * uu / 1.65
                im = im.resize((max(1, int(im.width * sc)), max(1, int(im.height * sc))), Image.BILINEAR)
                put(im, hx + 40 + uu * 40, hy - uu * 70)
        elif k in ("hearts", "sparkle"):
            for i in range(3):
                uu = u - i * 0.3
                if uu < 0:
                    continue
                put(F["heart" if k == "hearts" else "star"], hx - 40 + i * 40 + 10 * math.sin(2 * math.pi * 1.5 * uu), hy - 10 - (uu % 1.4) * 70)
        elif k == "notes":
            for i in range(2):
                uu = (u + i * 0.6) % 1.2
                put(F["note"], hx + (-60 if i else 60) + 8 * math.sin(2 * math.pi * 2 * uu), hy + 30 - uu * 80)
        elif k == "sweat":
            uu = u % 1.2
            r = a.rig["hr"]
            cx, cy = sk["hc"]
            put(F["drop"], cx + r * 0.9, cy - r * 0.2 + uu * 16)
        elif k == "tears":
            r = a.rig["hr"]
            cx, cy = sk["hc"]
            for sx in (-1, 1):
                uu = (u + (0.3 if sx > 0 else 0)) % 0.7
                put(F["drop"], cx + sx * r * 0.42, cy + r * 0.15 + uu * 60)
        elif k == "steam":
            for i in range(3):
                uu = (u + i * 0.4) % 1.2
                rr = 10 + 14 * uu
                pen.ell(hx + (i - 1) * 28 + 6 * math.sin(4 * uu), hy - 10 - uu * 60, rr, rr * 0.8, (240, 240, 244), ow=0)
        elif k == "speed":
            im = F["speed"]
            cx, cy = sk["hc"]
            for i in range(4):
                jit = (i * 37 + int(t * 24) * 13) % 30
                put(im, cx - f["dirn"] * (100 + jit), cy + 40 + i * 50)
        elif k == "fly":
            self.draw_fly(frameK, f, t, a, P, R)

    def draw_fly(self, frameK, f, t, a, P, R):
        l0, l1 = f["l0"], f["l1"]
        Pl0 = self.pose(a, l0 - 0.001)
        sk0 = skeleton(a, Pl0)
        sx0, sy0 = sk0["hand", Pl0.get("hand", 1) or 1] if Pl0["held"] == f["prop"] else sk0["ht"]
        ex, ey = skeleton(a, self.pose(a, l1 + 0.001))["ht"]
        props = R["props"]
        put = lambda im, x, y: frameK.paste(im, (int(x * K - im.width / 2), int(y * K - im.height / 2)), im)
        if f["prop"] != "tray":
            if t >= l1:
                return
            k = (t - l0) / (l1 - l0)
            im = props[f["prop"]]["c"].rotate(-600 * k, resample=Image.BICUBIC)
            put(im, T.lerp(sx0, ex, k), T.lerp(sy0, ey, k) - 280 * math.sin(math.pi * k))
            return
        tray_c, th = props["_tray"]
        glass_c, gh = props["_glass"]
        if t < l1:
            k = (t - l0) / (l1 - l0)
            put(tray_c.rotate(-500 * k, resample=Image.BICUBIC), T.lerp(sx0, ex, k), T.lerp(sy0, ey, k) - 260 * math.sin(math.pi * k))
        else:
            skn = skeleton(a, self.pose(a, t))
            im, qx, qy = T.rot_about(tray_c, skn["head_ang"], th, th)
            frameK.paste(im, (int(skn["ht"][0] * K - qx), int(skn["ht"][1] * K - qy)), im)
        for i, (gx, gy) in enumerate(props["_slots"]):
            g1 = l1 + 0.12 * (i + 1)
            skn = skeleton(a, self.pose(a, min(t, g1)))
            tx, ty = skn["ht"]
            rr = math.radians(skn["head_ang"])
            wx, wy = tx + gx * math.cos(rr) + gy * math.sin(rr), ty - gx * math.sin(rr) + gy * math.cos(rr)
            if t < g1:
                k = (t - l0) / (g1 - l0)
                x = T.lerp(sx0 + gx, wx, k)
                y = T.lerp(sy0 + gy, wy, k) - (300 + 40 * i) * math.sin(math.pi * k)
                im = glass_c.rotate(-360 * k * (1 + i % 2), resample=Image.BICUBIC)
                frameK.paste(im, (int(x * K - gh), int(y * K - gh)), im)
            else:
                im, qx, qy = T.rot_about(glass_c, skn["head_ang"], gh, gh)
                frameK.paste(im, (int(wx * K - qx), int(wy * K - qy)), im)

    def screen(self, cam, x, y):
        left, top, z = cam
        return (x - left) * z, (y - top) * z

    def draw_tail(self, frame, b, t, poses):
        a = self.by[b["who"]]
        P = poses[a.name]
        if not P["on"] or not b.get("bub"):
            return
        bx, by = b["bub"]
        cam = self.camera(t)
        hx, hy = self.screen(cam, *skeleton(a, P)["ht"])
        if hy < by + 30 or not (-20 < hx < W + 20) or hy > H:
            return
        d = ImageDraw.Draw(frame)
        tipx, tipy = T.lerp(bx, hx, 0.85), hy - 10
        if b["do"] == "think":
            for i, rr in enumerate((13, 9, 6)):
                k = 0.3 + i * 0.25
                cx, cy = T.lerp(bx, tipx, k), T.lerp(by + 18, tipy, k)
                d.ellipse([cx - rr - 3, cy - rr - 3, cx + rr + 3, cy + rr + 3], fill=(48, 40, 32))
                d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=WHITE)
            return
        d.polygon([(bx - 21, by + 8), (bx + 21, by + 8), (tipx, tipy + 4)], fill=(48, 40, 32))
        d.polygon([(bx - 18, by + 8), (bx + 18, by + 8), (tipx, tipy)], fill=WHITE)

    def poster_time(self):
        for b in self.beats:
            if b["do"] in ("slip", "fall"):
                return b["t0"] + (T.SLIP_WALK + T.SLIP_SLIDE if b["do"] == "slip" else 0.45) + 0.2
        for b in self.beats:
            if b["do"] in ("jump", "dance", "laugh", "balance", "surprised", "cheer", "point"):
                return b["t0"] + 0.5
        return min(self.total * 0.35, 4.0)


def clampf(v, a, b):
    return a if v < a else b if v > b else v


# ================================================================ words, sound and the video
def make_ass(story, title, path):
    total, end0 = story.total, story.end0
    ev = ["Dialogue: 4,%s,%s,Label,,0,0,0,,BigD cartoon" % (T.ts(0), T.ts(end0)),
          "Dialogue: 4,%s,%s,Title,,0,0,0,,{\\fad(200,300)}%s" % (T.ts(0.15), T.ts(2.9), T.ass_text(title))]
    for b in story.beats:
        if not b["say"]:
            continue
        a = story.by[b["who"]]
        tt = b["tl"] + 0.05
        cam = story.camera(tt)
        hx, hy = story.screen(cam, *skeleton(a, story.pose(a, tt))["ht"])
        if not (0 <= hx <= W) or hy > H:
            hx, hy = W / 2, 700
        bx, by = clampf(hx, 262, 458), clampf(hy - 46, 380, 720)
        b["bub"] = (bx, by)
        txt = T.ass_text(b["say"])
        t0, t1 = T.ts(b["tl"] - 0.05), T.ts(b["tt"] + 0.25)
        ev.append("Dialogue: 1,%s,%s,BubbleEdge,,0,0,0,,{\\an2\\pos(%d,%d)}%s" % (t0, t1, bx, by, txt))
        ev.append("Dialogue: 2,%s,%s,Bubble,,0,0,0,,{\\an2\\pos(%d,%d)}%s" % (t0, t1, bx, by, txt))
    ev.append("Dialogue: 5,%s,%s,End,,0,0,0,,{\\fad(300,0)\\pos(360,560)}BigD AI" % (T.ts(end0), T.ts(total)))
    ev.append("Dialogue: 5,%s,%s,EndSub,,0,0,0,,{\\fad(300,0)\\pos(360,640)}కార్టూన్లు · వార్తలు · సహాయం · bigdai.tech" % (T.ts(end0), T.ts(total)))
    T.write_ass(path, ev)


def render(plan):
    random.seed(plan.get("seed", 7))
    tmp = plan.get("tmp") or os.path.dirname(plan["out"])
    os.makedirs(tmp, exist_ok=True)
    place = plan.get("place", "stage")
    if place not in PLACE_COL:
        place = "stage"
    plan["place"] = place
    bg = place_pic(place)
    story = Story(plan)
    R = dict(props=T.build_props(), fx=T.build_fx())
    big = lambda im: im.resize((im.width * K, im.height * K), Image.BICUBIC)
    for k, v in list(R["props"].items()):
        if k == "_slots":
            continue
        elif k in ("_tray", "_glass"):
            R["props"][k] = (big(v[0]), v[1] * K)
        else:
            R["props"][k] = dict(img=big(v["img"]), c=big(v["c"]), half=v["half"] * K, head=big(v["head"]), hhalf=v["hhalf"] * K)
    R["fx"] = {k: big(v) for k, v in R["fx"].items()}
    ass = os.path.join(tmp, "words.ass")
    snd = os.path.join(tmp, "sound.wav")
    make_ass(story, plan.get("title", ""), ass)
    T.make_sound(story, snd)
    total = story.total
    ff = T.start_ffmpeg(plan, ass, snd, total)
    n = int(total * FPS)
    poster_t = plan.get("poster_t") or story.poster_time()
    if poster_t >= story.end0:
        poster_t = story.end0 * 0.5
    dark = Image.new("RGB", (W, H), (0, 0, 0))
    try:
        for i in range(n):
            t = i / FPS
            fk = bg.copy()
            story.draw(fk, t, R)
            left, top, z = story.camera(t)
            if z == 1.0:
                frame = fk.reduce(K)
            else:
                box = (int(left * K), int(top * K), int((left + W / z) * K), int((top + H / z) * K))
                frame = fk.crop(box).resize((W, H), Image.BILINEAR)
            poses = {a.name: story.pose(a, t) for a in story.actors}
            for b in story.beats:
                if b["say"] and b["tl"] - 0.05 <= t < b["tt"] + 0.25:
                    story.draw_tail(frame, b, t, poses)
            if t >= story.end0:
                frame = Image.blend(frame, dark, 0.5 * clampf((t - story.end0) / 0.3, 0, 1))
            if plan.get("poster") and abs(t - poster_t) < 0.5 / FPS:
                frame.save(plan["poster"], "JPEG", quality=84)
            ff.stdin.write(frame.tobytes())
        ff.stdin.close()
    except BrokenPipeError:
        pass
    T.finish_ffmpeg(ff, plan["out"])
    return total
