# Draws Habit's own cartoon puppy (an original character) and the cartoon backgrounds, as PNG files.
#   python3 draw.py            (needs Pillow; the PNGs are already in this folder)
# Puppy: 6 pictures = mouth (closed, half, open) x eyes (open, blink). Backgrounds: 720x1280.
from PIL import Image, ImageDraw, ImageFilter
import math, os, random

HERE = os.path.dirname(os.path.abspath(__file__))
S = 2                      # drawn twice as big, then made smaller (smooth edges)
FUR, FUR_D, CREAM, NOSE, INK = "#F2B45A", "#B9722E", "#FFF1D6", "#3B2A20", "#2B1B12"
MOUTH, TONGUE, COLLAR, TAG = "#7A1F2B", "#FF6F8E", "#E8336D", "#FFC83D"

def E(d, cx, cy, rx, ry, fill, outline=None, w=0):
    d.ellipse([(cx - rx) * S, (cy - ry) * S, (cx + rx) * S, (cy + ry) * S], fill=fill, outline=outline, width=w * S)

def rotated_ellipse(img, cx, cy, rx, ry, ang, fill):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([(cx - rx) * S, (cy - ry) * S, (cx + rx) * S, (cy + ry) * S], fill=fill)
    layer = layer.rotate(ang, center=(cx * S, cy * S), resample=Image.BICUBIC)
    img.alpha_composite(layer)

def puppy(mouth, eyes):
    W, H = 640, 720
    img = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # tail
    d.arc([440 * S, 430 * S, 560 * S, 560 * S], 200, 330, fill=FUR_D, width=26 * S)
    # body and belly
    E(d, 320, 545, 165, 150, FUR)
    E(d, 320, 575, 105, 115, CREAM)
    # paws
    for x in (255, 385):
        E(d, x, 680, 52, 30, FUR)
        for k in (-18, 0, 18): d.line([(x + k) * S, 668 * S, (x + k) * S, 690 * S], fill=FUR_D, width=3 * S)
    # collar with the D tag
    d.rounded_rectangle([200 * S, 392 * S, 440 * S, 425 * S], radius=16 * S, fill=COLLAR)
    E(d, 320, 450, 26, 26, TAG, outline="#E0A21E", w=3)
    # ears (behind the head)
    rotated_ellipse(img, 165, 220, 62, 120, -18, FUR_D)
    rotated_ellipse(img, 475, 220, 62, 120, 18, FUR_D)
    d = ImageDraw.Draw(img)
    # head
    E(d, 320, 230, 175, 160, FUR)
    E(d, 320, 330, 105, 75, CREAM)            # muzzle
    E(d, 235, 120, 40, 22, "#F7C77C")          # a light patch on the head
    # eyes
    for x in (255, 385):
        if eyes == "open":
            E(d, x, 235, 34, 40, "white", outline=INK, w=3)
            E(d, x + 4, 242, 20, 24, INK)
            E(d, x + 11, 232, 7, 8, "white")
            E(d, x - 4, 252, 3, 3, "white")
        else:
            d.arc([(x - 30) * S, 220 * S, (x + 30) * S, 262 * S], 200, 340, fill=INK, width=7 * S)
        # eyebrows
        d.arc([(x - 30) * S, 168 * S, (x + 30) * S, 196 * S], 200, 340, fill=FUR_D, width=7 * S)
    # cheeks
    for x in (205, 435): E(d, x, 305, 26, 16, "#F79A8A")
    # nose
    d.rounded_rectangle([290 * S, 280 * S, 350 * S, 318 * S], radius=18 * S, fill=NOSE)
    E(d, 307, 290, 9, 5, "#7A5A48")
    # mouth
    if mouth == "closed":
        d.arc([272 * S, 300 * S, 322 * S, 352 * S], 20, 160, fill=INK, width=6 * S)
        d.arc([318 * S, 300 * S, 368 * S, 352 * S], 20, 160, fill=INK, width=6 * S)
    else:
        h = 34 if mouth == "half" else 58
        d.rounded_rectangle([282 * S, 328 * S, 358 * S, (328 + h) * S], radius=26 * S, fill=MOUTH, outline=INK, width=5 * S)
        E(d, 320, 328 + h - 13, 26, 15 if mouth == "half" else 19, TONGUE)
    return img.resize((W, H), Image.LANCZOS)

for m in ("closed", "half", "open"):
    for e in ("open", "blink"):
        puppy(m, e).save(os.path.join(HERE, f"puppy_{m}_{e}.png"), optimize=True)

# ---------- backgrounds ----------
def grad(top, bottom, w=720, h=1280):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    t, b = [int(top[i:i + 2], 16) for i in (1, 3, 5)], [int(bottom[i:i + 2], 16) for i in (1, 3, 5)]
    for y in range(h):
        k = y / (h - 1)
        d.line([0, y, w, y], fill=tuple(int(t[i] + (b[i] - t[i]) * k) for i in range(3)))
    return img

def cloud(d, x, y, s):
    for dx, dy, r in ((0, 0, 40), (45, -18, 48), (95, 0, 40), (45, 12, 44)):
        d.ellipse([x + dx * s - r * s, y + dy * s - r * s, x + dx * s + r * s, y + dy * s + r * s], fill="white")

random.seed(7)
# park: sky, sun, clouds, hills, a tree, flowers
img = grad("#7EC8F5", "#D8F1FF"); d = ImageDraw.Draw(img)
d.ellipse([520, 120, 660, 260], fill="#FFD95A")
cloud(d, 80, 220, 1.0); cloud(d, 380, 330, 0.8)
d.ellipse([-300, 780, 600, 1300], fill="#7CC36B"); d.ellipse([200, 820, 1100, 1350], fill="#69B35A")
d.rectangle([0, 1000, 720, 1280], fill="#5FA750")
d.rectangle([90, 640, 120, 900], fill="#8A5A3B"); d.ellipse([10, 520, 200, 720], fill="#4E9A45")
for i in range(26):
    x, y = random.randint(10, 710), random.randint(1010, 1270)
    d.ellipse([x - 7, y - 7, x + 7, y + 7], fill=random.choice(["#FF7AA2", "#FFE066", "#FFFFFF", "#B48CFF"]))
img.save(os.path.join(HERE, "bg_park.png"), optimize=True)
# room: wall, window, floor, a plant and a lamp
img = grad("#FFE9C9", "#FFD9A8"); d = ImageDraw.Draw(img)
d.rectangle([0, 980, 720, 1280], fill="#C98B55")
for x in range(0, 720, 90): d.line([x, 980, x, 1280], fill="#B97C49", width=3)
d.rounded_rectangle([430, 230, 660, 520], radius=10, fill="#9ED8FF", outline="white", width=14)
d.line([545, 230, 545, 520], fill="white", width=10); d.line([430, 375, 660, 375], fill="white", width=10)
d.rectangle([60, 860, 150, 980], fill="#E07A5F"); d.ellipse([20, 700, 190, 890], fill="#4E9A45")
d.rectangle([600, 760, 612, 980], fill="#6B4F3A"); d.polygon([(560, 760), (652, 760), (630, 690), (582, 690)], fill="#FFD166")
img.save(os.path.join(HERE, "bg_room.png"), optimize=True)
# night: dark sky, moon, stars
img = grad("#0B1D51", "#3A2E7A"); d = ImageDraw.Draw(img)
d.ellipse([480, 140, 640, 300], fill="#FFF4C2"); d.ellipse([520, 120, 680, 280], fill="#0E2158")
for i in range(70):
    x, y, r = random.randint(0, 720), random.randint(0, 900), random.choice([2, 2, 3, 4])
    d.ellipse([x - r, y - r, x + r, y + r], fill="#FFFFFF")
d.ellipse([-200, 950, 920, 1500], fill="#22305F")
img.save(os.path.join(HERE, "bg_night.png"), optimize=True)
# school: green board, a desk line
img = grad("#FCEFD8", "#F5DDB8"); d = ImageDraw.Draw(img)
d.rounded_rectangle([60, 200, 660, 600], radius=12, fill="#2F6B4F", outline="#8A5A3B", width=18)
d.line([110, 300, 380, 300], fill="#E9F5EC", width=5); d.line([110, 360, 300, 360], fill="#E9F5EC", width=5)
d.text((470, 280), "A B C", fill="#E9F5EC"); d.text((470, 340), "1 2 3", fill="#E9F5EC")
d.rectangle([0, 1000, 720, 1280], fill="#B98A5E")
img.save(os.path.join(HERE, "bg_school.png"), optimize=True)
# stage: curtains and a spotlight (for jokes)
img = grad("#3B0A2A", "#1B0614"); d = ImageDraw.Draw(img)
spot = Image.new("L", (720, 1280), 0); ds = ImageDraw.Draw(spot)
ds.polygon([(300, 0), (420, 0), (640, 1280), (80, 1280)], fill=110)
spot = spot.filter(ImageFilter.GaussianBlur(40))
img = Image.composite(Image.new("RGB", (720, 1280), "#FFE7A3"), img, spot); d = ImageDraw.Draw(img)
for x0, x1 in ((0, 150), (570, 720)):
    d.rectangle([x0, 0, x1, 1280], fill="#B0123B")
    for x in range(x0 + 15, x1, 30): d.line([x, 0, x, 1280], fill="#8E0E30", width=6)
d.rectangle([0, 0, 720, 90], fill="#8E0E30")
d.rectangle([0, 1080, 720, 1280], fill="#5A2E1A")
img.save(os.path.join(HERE, "bg_stage.png"), optimize=True)
print("drawn")
