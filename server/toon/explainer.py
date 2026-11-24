"""Habit Studio: explainer videos (whiteboard and motion-graphics styles), made fully on our server, no GPU.

A plan (JSON, from studio.js) has scenes: a title card, points, numbered steps, a warning, a big number (a helpline),
and the end card. Pillow draws the moving pictures (paper or a dark glowing background, cards, icons, highlighter
marks, the Habit puppy presenting in the corner with its mouth moving with the voice); the Telugu and English words
are drawn by the video's subtitles engine (libass, with "complex shaping", so Telugu letters join correctly) and come
in with small animations. The narration (our free Telugu voice, one file per scene) and the music are mixed in.

    python3 explainer.py plan.json   ->  writes plan["out"] (MP4, 720x1280) and plan["poster"] (JPG); prints {"dur": s}
"""
import json, math, os, random, subprocess, sys, wave, struct
from PIL import Image, ImageDraw, ImageFilter

W, H, FPS = 720, 1280, 24
HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(HERE, "..", "assets", "cartoon")

STYLES = {
    # paper whiteboard: dark ink, marker colours
    "board": {"bg": (250, 248, 242), "ink": (34, 40, 52), "sub": (90, 98, 112), "card": (255, 255, 255), "line": (222, 218, 206),
              "accent": [(37, 99, 235), (234, 88, 12), (22, 163, 74), (190, 24, 93)], "warn": (220, 38, 38), "hl": (253, 224, 71)},
    # motion graphics: deep navy with glowing colours
    "neon": {"bg": (13, 18, 38), "ink": (240, 244, 255), "sub": (170, 182, 210), "card": (26, 34, 66), "line": (48, 60, 104),
             "accent": [(56, 189, 248), (250, 204, 21), (74, 222, 128), (244, 114, 182)], "warn": (248, 113, 113), "hl": (250, 204, 21)},
}


def ass_color(rgb, alpha=0):
    r, g, b = rgb
    return "&H%02X%02X%02X%02X" % (alpha, b, g, r)


def ass_time(t):
    t = max(0, t)
    h = int(t // 3600); m = int(t % 3600 // 60); s = t % 60
    return "%d:%02d:%05.2f" % (h, m, s)


def esc(s):
    return str(s).replace("\\", "／").replace("{", "(").replace("}", ")").replace("\n", "\\N")


def wrap(text, n):
    """Breaks text into lines of about n letters (Telugu letters count as letters too), at spaces."""
    words, lines, cur = str(text).split(), [], ""
    for w in words:
        if cur and len(cur) + 1 + len(w) > n:
            lines.append(cur); cur = w
        else:
            cur = (cur + " " + w).strip()
    if cur:
        lines.append(cur)
    return "\\N".join(esc(l) for l in lines[:6])


# ---------------- the voice ----------------
def read_wav(path):
    """(samples as a list of ints, rate) of a mono 16-bit WAV."""
    with wave.open(path, "rb") as w:
        n, rate, ch, sw = w.getnframes(), w.getframerate(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(n)
    if sw != 2:
        return [], rate
    s = struct.unpack("<%dh" % (len(raw) // 2), raw)
    if ch == 2:
        s = s[::2]
    return list(s), rate


def loudness(samples, rate, fps, frames):
    """How loud the voice is at each video frame (0..1), for the puppy's mouth."""
    out, step = [], rate / fps
    for i in range(frames):
        a, b = int(i * step), int((i + 1) * step)
        chunk = samples[a:b]
        if not chunk:
            out.append(0.0); continue
        out.append(min(1.0, (sum(abs(x) for x in chunk[::8]) / max(1, len(chunk[::8]))) / 5000.0))
    return out


# ---------------- pictures ----------------
def load_puppy():
    faces = {}
    for mouth in ("closed", "half", "open"):
        for eyes in ("open", "blink"):
            f = os.path.join(ART, "puppy_%s_%s.png" % (mouth, eyes))
            if os.path.exists(f):
                im = Image.open(f).convert("RGBA")
                k = 210 / im.height
                faces[(mouth, eyes)] = im.resize((int(im.width * k), 210), Image.LANCZOS)
    return faces


def background(st, style, seed):
    img = Image.new("RGB", (W, H), st["bg"])
    d = ImageDraw.Draw(img)
    rnd = random.Random(seed)
    if style == "board":
        for y in range(0, H, 40):            # faint ruled paper
            d.line([(0, y), (W, y)], fill=(242, 239, 230), width=1)
        d.rectangle([0, 0, W, 14], fill=st["accent"][0])
    else:
        glow = Image.new("RGB", (W, H), (0, 0, 0))
        g = ImageDraw.Draw(glow)
        for _ in range(5):
            c = rnd.choice(st["accent"])
            x, y, r = rnd.randint(-100, W + 100), rnd.randint(-100, H + 100), rnd.randint(180, 320)
            g.ellipse([x - r, y - r, x + r, y + r], fill=tuple(int(v * 0.35) for v in c))
        glow = glow.filter(ImageFilter.GaussianBlur(90))
        img = Image.blend(img, glow, 0.55)
        d = ImageDraw.Draw(img)
        for x in range(0, W, 48):            # a soft grid
            d.line([(x, 0), (x, H)], fill=(22, 30, 58), width=1)
        for y in range(0, H, 48):
            d.line([(0, y), (W, y)], fill=(22, 30, 58), width=1)
    return img


def ease(t):
    t = max(0.0, min(1.0, t))
    return 1 - (1 - t) ** 3


def pop(t):
    """0 -> overshoot -> 1, for things that pop in."""
    t = max(0.0, min(1.0, t))
    return 1 + 0.12 * math.sin(t * math.pi) if t < 1 else 1.0


def icon(d, kind, cx, cy, r, col, ink, k=1.0):
    r = max(1, int(r * k))
    if kind == "check":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        d.line([(cx - r * 0.45, cy), (cx - r * 0.1, cy + r * 0.38), (cx + r * 0.5, cy - r * 0.35)], fill=(255, 255, 255), width=max(3, r // 5))
    elif kind == "warn":
        d.polygon([(cx, cy - r), (cx + r * 1.05, cy + r * 0.85), (cx - r * 1.05, cy + r * 0.85)], fill=col)
        d.rectangle([cx - r * 0.09, cy - r * 0.45, cx + r * 0.09, cy + r * 0.3], fill=(255, 255, 255))
        d.ellipse([cx - r * 0.11, cy + r * 0.45, cx + r * 0.11, cy + r * 0.67], fill=(255, 255, 255))
    elif kind == "phone":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        d.rounded_rectangle([cx - r * 0.32, cy - r * 0.55, cx + r * 0.32, cy + r * 0.55], radius=int(r * 0.12), outline=(255, 255, 255), width=max(3, r // 8))
        d.ellipse([cx - r * 0.06, cy + r * 0.36, cx + r * 0.06, cy + r * 0.48], fill=(255, 255, 255))
    elif kind == "bulb":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        d.ellipse([cx - r * 0.42, cy - r * 0.6, cx + r * 0.42, cy + r * 0.2], outline=(255, 255, 255), width=max(3, r // 8))
        d.rectangle([cx - r * 0.2, cy + r * 0.22, cx + r * 0.2, cy + r * 0.48], fill=(255, 255, 255))
    elif kind == "rupee":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        w = max(3, r // 7)
        d.line([(cx - r * 0.35, cy - r * 0.45), (cx + r * 0.35, cy - r * 0.45)], fill=(255, 255, 255), width=w)
        d.line([(cx - r * 0.35, cy - r * 0.2), (cx + r * 0.35, cy - r * 0.2)], fill=(255, 255, 255), width=w)
        d.arc([cx - r * 0.5, cy - r * 0.45, cx + r * 0.25, cy + r * 0.05], -90, 90, fill=(255, 255, 255), width=w)
        d.line([(cx - r * 0.3, cy + r * 0.05), (cx + r * 0.3, cy + r * 0.55)], fill=(255, 255, 255), width=w)
    elif kind == "heart":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        d.ellipse([cx - r * 0.5, cy - r * 0.45, cx, cy + r * 0.05], fill=(255, 255, 255))
        d.ellipse([cx, cy - r * 0.45, cx + r * 0.5, cy + r * 0.05], fill=(255, 255, 255))
        d.polygon([(cx - r * 0.48, cy - r * 0.12), (cx + r * 0.48, cy - r * 0.12), (cx, cy + r * 0.5)], fill=(255, 255, 255))
    elif kind == "leaf":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        d.chord([cx - r * 0.5, cy - r * 0.55, cx + r * 0.5, cy + r * 0.55], 300, 120, fill=(255, 255, 255))
        d.line([(cx - r * 0.35, cy + r * 0.4), (cx + r * 0.3, cy - r * 0.35)], fill=col, width=max(2, r // 10))
    elif kind == "doc":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        d.rectangle([cx - r * 0.35, cy - r * 0.5, cx + r * 0.35, cy + r * 0.5], fill=(255, 255, 255))
        for i in range(3):
            y = cy - r * 0.25 + i * r * 0.22
            d.line([(cx - r * 0.22, y), (cx + r * 0.22, y)], fill=col, width=max(2, r // 12))
    else:  # star
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
        pts = []
        for i in range(10):
            a = -math.pi / 2 + i * math.pi / 5
            rr = r * (0.55 if i % 2 == 0 else 0.24)
            pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
        d.polygon(pts, fill=(255, 255, 255))


# ---------------- the plan → timeline ----------------
def layout(plan):
    """Each scene's start, length and the things that appear in it (pictures drawn by Pillow, words by libass)."""
    scenes, t = [], 0.0
    for i, s in enumerate(plan["scenes"]):
        kind = s.get("kind", "point")
        voice = s.get("voice")
        vs, rate, vlen = [], 24000, 0.0
        if voice and os.path.exists(voice):
            try:
                vs, rate = read_wav(voice)
                vlen = len(vs) / float(rate)
            except Exception:
                vs, vlen = [], 0.0
        words = len(" ".join([s.get("text", "")] + list(s.get("items") or [])))
        need = max(vlen + 0.7, 2.6 + words * 0.045, 3.0 if kind in ("title", "end") else 3.5)
        scenes.append({"i": i, "kind": kind, "start": t, "dur": need, "s": s, "voice": vs, "rate": rate, "vlen": vlen})
        t += need
    return scenes, t


def build_ass(plan, scenes, st, path, fontname):
    ink, sub = ass_color(st["ink"]), ass_color(st["sub"])
    lines = [
        "[Script Info]", "ScriptType: v4.00+", "PlayResX: %d" % W, "PlayResY: %d" % H, "WrapStyle: 2", "ScaledBorderAndShadow: yes", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        "Style: Big,%s,72,%s,%s,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,5,40,40,40,1" % (fontname, ink, ink),
        "Style: Mid,%s,50,%s,%s,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,7,40,40,40,1" % (fontname, ink, ink),
        "Style: Small,%s,32,%s,%s,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,5,40,40,40,1" % (fontname, sub, sub),
        "Style: Num,%s,150,%s,%s,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,0,0,5,40,40,40,1" % (fontname, ass_color(st["warn"]), ink),
        "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]

    def ev(a, b, style, text, extra=""):
        lines.append("Dialogue: 0,%s,%s,%s,,0,0,0,,{%s}%s" % (ass_time(a), ass_time(b), style, extra, text))

    for sc in scenes:
        s, a, b = sc["s"], sc["start"], sc["start"] + sc["dur"]
        k = sc["kind"]
        fade = "\\fad(250,200)"
        if k == "title":
            ev(a + 0.2, b, "Big", wrap(s.get("text", plan.get("title", "")), 22), "\\an5\\pos(360,680)" + fade + "\\t(0,400,\\fscx100\\fscy100)\\fscx70\\fscy70")
            if s.get("sub"):
                ev(a + 0.7, b, "Small", wrap(s["sub"], 30), "\\an5\\pos(360,900)" + fade)
        elif k == "point":
            ev(a + 0.35, b, "Mid", wrap(s.get("text", ""), 24), "\\an7\\pos(80,560)" + fade + "\\move(120,560,80,560,0,350)")
        elif k == "steps":
            items = list(s.get("items") or [])[:4]
            if s.get("text"):
                ev(a + 0.2, b, "Mid", wrap(s["text"], 18), "\\an7\\pos(60,200)" + fade)
            gap = max(0.6, (sc["dur"] - 1.2) / max(1, len(items)))
            for j, it in enumerate(items):
                ev(a + 0.6 + j * gap, b, "Mid", wrap(it, 24), "\\an4\\pos(190,%d)\\fs40" % (480 + j * 200) + fade + "\\move(230,%d,190,%d,0,300)" % (480 + j * 200, 480 + j * 200))
                ev(a + 0.75 + j * gap, b, "Mid", str(j + 1), "\\an5\\pos(110,%d)\\fs46\\1c&H00FFFFFF&" % (480 + j * 200) + fade)
        elif k == "warn":
            ev(a + 0.4, b, "Mid", wrap(s.get("text", ""), 24), "\\an8\\pos(360,700)" + fade)
        elif k == "number":
            ev(a + 0.2, b, "Num", esc(s.get("big", "")), "\\an5\\pos(360,570)\\fscx40\\fscy40\\t(0,350,\\fscx108\\fscy108)\\t(350,500,\\fscx100\\fscy100)" + fade)
            ev(a + 0.8, b, "Mid", wrap(s.get("text", ""), 24), "\\an8\\pos(360,730)" + fade)
        else:  # end
            ev(a + 0.2, b, "Big", wrap(s.get("text", "హ్యాబిట్ AI లో అడగండి"), 22), "\\an5\\pos(360,660)" + fade)
            ev(a + 0.6, b, "Small", esc(s.get("sub", "🤖 AI-made · AI తో తయారైన వీడియో")), "\\an5\\pos(360,840)" + fade)
        # the narration as small captions at the bottom (for people watching without sound)
        say = s.get("say", "")
        if say and k not in ("title", "end"):
            ev(a + 0.2, b - 0.1, "Small", wrap(say, 44), "\\an2\\pos(330,1250)\\bord0" + fade)
    # the series name in the corner, all through
    total = scenes[-1]["start"] + scenes[-1]["dur"] if scenes else 1
    ev(0, total, "Small", esc(plan.get("brand", "Habit Studio · హ్యాబిట్")), "\\an7\\pos(30,36)\\fs24")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def frame(base, st, style, sc, t, faces, mouth_level, blink, pal):
    """One picture at time t (seconds since the scene started)."""
    img = base.copy()
    d = ImageDraw.Draw(img)
    k, s = sc["kind"], sc["s"]
    acc = pal[sc["i"] % len(pal)]
    p = ease(t / 0.5)
    if k == "title":
        r = 120 * ease(t / 0.45) * pop(t / 0.6)
        icon(d, s.get("icon", "star"), W // 2, 420, r, acc, st["ink"])
        w = int(420 * ease((t - 0.5) / 0.6))
        if w > 0:
            d.rounded_rectangle([W // 2 - w // 2, 810, W // 2 + w // 2, 824], radius=7, fill=st["hl"])
    elif k == "point":
        icon(d, s.get("icon", "bulb"), 130, 420, 72 * pop(t / 0.5), acc, st["ink"], p)
        x1 = int(80 + 540 * ease((t - 0.7) / 0.8))
        if x1 > 82:   # the highlighter mark under the words
            d.rounded_rectangle([80, 880, x1, 904], radius=10, fill=st["hl"])
        d.rounded_rectangle([50, 520, W - 50, 940], radius=26, outline=st["line"], width=3)
    elif k == "steps":
        items = list(s.get("items") or [])[:4]
        gap = max(0.6, (sc["dur"] - 1.2) / max(1, len(items)))
        for j in range(len(items)):
            tj = t - (0.6 + j * gap)
            if tj < 0:
                continue
            cy = 480 + j * 200
            rr = 48 * pop(tj / 0.45) * ease(tj / 0.3)
            c = pal[j % len(pal)]
            d.rounded_rectangle([60, cy - 80, W - 50, cy + 80], radius=22, fill=st["card"], outline=st["line"], width=2)
            d.ellipse([110 - rr, cy - rr, 110 + rr, cy + rr], fill=c)
            if j + 1 < len(items):
                d.line([(110, cy + 52), (110, cy + 138)], fill=st["line"], width=4)
    elif k == "warn":
        shake = math.sin(t * 40) * 6 * max(0.0, 1 - t / 0.6)
        icon(d, "warn", W // 2 + shake, 480, 110 * pop(t / 0.5), st["warn"], st["ink"], p)
        d.rounded_rectangle([50, 650, W - 50, 1060], radius=28, outline=st["warn"], width=5)
    elif k == "number":
        icon(d, s.get("icon", "phone"), W // 2, 340, 80 * pop(t / 0.5), st["warn"], st["ink"], p)
        d.rounded_rectangle([90, 460, W - 90, 680], radius=30, outline=st["warn"], width=5)
    else:  # end
        icon(d, "heart", W // 2, 430, 100 * pop(t / 0.6), acc, st["ink"], p)
    # the presenter: the Habit puppy in the corner, mouth with the voice, blinking now and then
    if faces:
        mouth = "open" if mouth_level > 0.45 else "half" if mouth_level > 0.15 else "closed"
        f = faces.get((mouth, "blink" if blink else "open")) or faces.get(("closed", "open"))
        bob = int(math.sin(t * 3.2) * 6)
        img.paste(f, (W - f.width - 14, 46 + bob), f)       # top right, out of the way of the words
    return img


def main():
    plan = json.load(open(sys.argv[1], encoding="utf-8"))
    style = plan.get("style", "board") if plan.get("style") in STYLES else "board"
    st = STYLES[style]
    pal = st["accent"]
    tmp = plan["tmp"]
    os.makedirs(tmp, exist_ok=True)
    scenes, total = layout(plan)
    faces = load_puppy()
    base = background(st, style, plan.get("seed", 1))

    # the whole narration as one track (each scene's voice at its start)
    rate = 24000
    track = [0] * int(total * rate + rate)
    for sc in scenes:
        if sc["voice"]:
            src = sc["voice"] if sc["rate"] == rate else [sc["voice"][int(i * sc["rate"] / rate)] for i in range(int(len(sc["voice"]) * rate / sc["rate"]))]
            at = int((sc["start"] + 0.25) * rate)
            for i, v in enumerate(src):
                if at + i < len(track):
                    track[at + i] = v
    voice_wav = os.path.join(tmp, "voice.wav")
    with wave.open(voice_wav, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes(struct.pack("<%dh" % len(track), *track))
    nframes = int(total * FPS)
    level = loudness(track, rate, FPS, nframes)

    ass = os.path.join(tmp, "words.ass")
    build_ass(plan, scenes, st, ass, plan.get("font", "Hind Guntur"))

    music = plan.get("music")
    fonts = plan.get("fonts", "")
    ff = plan.get("ffmpeg", "ffmpeg")
    vf = "ass=%s:fontsdir=%s" % (ass.replace(":", "\\:"), fonts.replace(":", "\\:"))
    cmd = [ff, "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "%dx%d" % (W, H), "-r", str(FPS), "-i", "-",
           "-i", voice_wav]
    if music and os.path.exists(music):
        cmd += ["-stream_loop", "-1", "-i", music,
                "-filter_complex", "[1:a]volume=1.0[v];[2:a]volume=0.16[m];[v][m]amix=inputs=2:duration=first:dropout_transition=0[a]",
                "-map", "0:v", "-map", "[a]"]
    else:
        cmd += ["-map", "0:v", "-map", "1:a"]
    cmd += ["-vf", vf, "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", "-shortest", plan["out"]]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    rnd = random.Random(plan.get("seed", 1))
    blink_until = -1
    poster_frame = None
    try:
        for fi in range(nframes):
            t = fi / FPS
            sc = scenes[-1]
            for x in scenes:
                if x["start"] <= t < x["start"] + x["dur"]:
                    sc = x; break
            if fi > blink_until + 60 and rnd.random() < 0.02:
                blink_until = fi + 3
            img = frame(base, st, style, sc, t - sc["start"], faces, level[fi] if fi < len(level) else 0, fi <= blink_until, pal)
            if sc["kind"] == "title" and t - sc["start"] > 1.2 and poster_frame is None:
                poster_frame = fi
            p.stdin.write(img.tobytes())
    finally:
        p.stdin.close()
        p.wait()
    if p.returncode != 0 or not os.path.exists(plan["out"]):
        raise SystemExit("ffmpeg failed")
    # the poster: a frame of the title card, with its words
    at = (poster_frame or int(FPS * 1.5)) / FPS
    subprocess.run([ff, "-y", "-v", "error", "-ss", "%.2f" % at, "-i", plan["out"], "-frames:v", "1", "-q:v", "4", plan["poster"]], check=False)
    print(json.dumps({"dur": round(total, 1)}))


if __name__ == "__main__":
    main()
