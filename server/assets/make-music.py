# Original background music for BigD AI's videos, one short loop per mood. Made only from sine waves and a little
# noise (no recording or existing song is used), so it is free to use anywhere.
#   python3 make-music.py <mood> out.wav      moods: serious, happy, inspiring, devotional, kids
import math, random, struct, wave, sys

SR = 22050
mood = sys.argv[1]
random.seed({"serious": 11, "happy": 12, "inspiring": 13, "devotional": 14, "kids": 15}.get(mood, 1))
hz = lambda m: 440 * 2 ** ((m - 69) / 12)
P = {
    # bpm, bars, chords (MIDI), arpeggio pattern, melody notes (scale), drone, shaker, pluck brightness
    "serious":    dict(bpm=96,  bars=8, chords=[(57, 60, 64), (53, 57, 60), (48, 52, 55), (55, 59, 62)], arp=[0, 2, 1, 2, 0, 2, 1, 2], amp_arp=0.10, shaker=True, drone=None, bell=False, bass_pulse=True),
    "happy":      dict(bpm=112, bars=8, chords=[(60, 64, 67), (55, 59, 62), (57, 60, 64), (53, 57, 60)], arp=[0, 1, 2, 3, 2, 1, 0, 2], amp_arp=0.12, shaker=True, drone=None, bell=False, bass_pulse=False, clap=True),
    "inspiring":  dict(bpm=100, bars=8, chords=[(60, 64, 67), (53, 57, 60), (57, 60, 64), (55, 59, 62)], arp=[0, 1, 2, 3, 0, 1, 2, 3], amp_arp=0.11, shaker=False, drone=None, bell=True, bass_pulse=True, rise=True),
    "devotional": dict(bpm=66,  bars=6, chords=[(60, 64, 67), (60, 65, 69), (60, 64, 67), (55, 59, 62)], arp=None, amp_arp=0.0, shaker=False, drone=(48, 55), bell=True, bass_pulse=False,
                       melody=[72, 74, 76, 79, 81, 79, 76, 74]),          # Mohanam (Sa Ri Ga Pa Dha), slow and gentle
    "kids":       dict(bpm=124, bars=8, chords=[(60, 64, 67), (65, 69, 72), (67, 71, 74), (60, 64, 67)], arp=[0, 2, 1, 2, 3, 2, 1, 2], amp_arp=0.13, shaker=True, drone=None, bell=False, bass_pulse=False, marimba=True),
}[mood]
beat = 60 / P["bpm"]; total = P["bars"] * 4 * beat
n = int(total * SR); out = [0.0] * n

def note(f, start, dur, amp, partials=((1, 1.0), (2, 0.28), (3, 0.08)), decay=3.5, attack=0.006, vib=0.0):
    s0 = int(start * SR); m = int(dur * SR)
    for i in range(m):
        k = s0 + i
        if k >= n: break
        t = i / SR
        e = min(1.0, t / attack) * math.exp(-decay * t) * min(1.0, (dur - t) / 0.05)
        ff = f * (1 + vib * math.sin(2 * math.pi * 5.2 * t))
        out[k] += amp * e * sum(a * math.sin(2 * math.pi * ff * p * t) for p, a in partials)

def pad(freqs, start, dur, amp):
    s0 = int(start * SR); m = int(dur * SR)
    for i in range(m):
        k = s0 + i
        if k >= n: break
        t = i / SR
        e = min(1.0, t / 0.5) * min(1.0, (dur - t) / 0.6)
        out[k] += amp * e * sum(math.sin(2 * math.pi * f * (1 + 0.002 * math.sin(2 * math.pi * 0.3 * t)) * t) for f in freqs) / len(freqs)

def noise_hit(start, length, amp, tau):
    s0 = int(start * SR); prev = 0.0
    for i in range(int(length * SR)):
        x = random.uniform(-1, 1); y = x - prev; prev = x
        if s0 + i < n: out[s0 + i] += amp * y * math.exp(-i / (SR * tau))

for bar in range(P["bars"]):
    c = P["chords"][bar % len(P["chords"])]; t0 = bar * 4 * beat
    pad([hz(x - 12) for x in c], t0, 4 * beat, 0.15 if mood != "devotional" else 0.10)
    # bass
    if P.get("bass_pulse"):
        for j in range(8): note(hz(c[0] - 24), t0 + j * beat / 2, beat * 0.45, 0.20, partials=((1, 1.0), (2, 0.2)), decay=5.0)
    elif mood != "devotional":
        note(hz(c[0] - 24), t0, 1.8 * beat, 0.28, partials=((1, 1.0), (2, 0.15)), decay=2.0)
        note(hz(c[0] - 24), t0 + 2 * beat, 1.8 * beat, 0.22, partials=((1, 1.0), (2, 0.15)), decay=2.0)
    # arpeggio / marimba
    if P["arp"]:
        tones = [c[0], c[1], c[2], c[0] + 12]
        for j, k in enumerate(P["arp"]):
            m = tones[k] + 12 + (12 if P.get("rise") and bar >= P["bars"] // 2 else 0)
            if P.get("marimba"):
                note(hz(m), t0 + j * beat / 2, beat * 0.4, P["amp_arp"], partials=((1, 1.0), (4, 0.25), (10, 0.05)), decay=9.0)
            else:
                note(hz(m), t0 + j * beat / 2, beat * 0.9, P["amp_arp"] if j % 2 == 0 else P["amp_arp"] * 0.8)
    # bells (inspiring: on the first beat; devotional: a slow temple-bell melody)
    if P.get("bell"):
        if mood == "devotional":
            mel = P["melody"]
            for j in range(4):
                note(hz(mel[(bar * 4 + j) % len(mel)]), t0 + j * beat, beat * 1.6, 0.09, partials=((1, 1.0), (2.76, 0.35), (5.4, 0.12)), decay=1.6, vib=0.003)
        else:
            note(hz(c[2] + 24), t0, beat * 2.5, 0.07, partials=((1, 1.0), (2.76, 0.3), (5.4, 0.1)), decay=1.4)
    # shaker on the off-beats, claps on 2 and 4
    if P["shaker"]:
        for j in range(8):
            if j % 2 == 1: noise_hit(t0 + j * beat / 2, 0.06, 0.03, 0.015)
    if P.get("clap"):
        for j in (1, 3): noise_hit(t0 + j * beat, 0.12, 0.06, 0.03)

# a tanpura-like drone for the devotional loop (Sa and Pa, always there)
if P["drone"]:
    for f0 in P["drone"]:
        f = hz(f0)
        for i in range(n):
            t = i / SR
            e = min(1.0, t / 1.5) * min(1.0, (total - t) / 1.5)
            out[i] += 0.07 * e * (math.sin(2 * math.pi * f * t) + 0.4 * math.sin(2 * math.pi * 2 * f * t + 0.3 * math.sin(2 * math.pi * 0.2 * t)) + 0.2 * math.sin(2 * math.pi * 3 * f * t))

m = max(abs(v) for v in out); g = 0.6 / m
fade = int(0.02 * SR)
for i in range(fade): out[i] *= i / fade; out[n - 1 - i] *= i / fade
with wave.open(sys.argv[2], "w") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(b"".join(struct.pack("<h", int(v * g * 32767)) for v in out))
print(mood, round(total, 1), "s")
