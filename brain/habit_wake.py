#!/usr/bin/env python3
"""habit-wake: teaches Habit AI's ear its own name, like a smart speaker's wake word. Free, on Kaggle.

In a Kaggle notebook (Settings: Internet on; a GPU is not needed; secret HF_TOKEN, the same one as for habit-gemma), one cell:

    !curl -fsSL https://raw.githubusercontent.com/kishorerevenue-svg/Big.D/main/brain/habit_wake.py -o habit_wake.py && python habit_wake.py --full

(--test: a quick try, about 20 to 30 minutes. --full: the real one, about 1 to 2 hours. Choose GPU T4 x2 if you have it: faster.)

What it does:
  1. Free voices (Piper: English with ~1,000 speakers, Telugu and Hindi) say "Habit", "హ్యాబిట్", "हैबिट" thousands of times,
     fast and slow, and also words that only sound close ("rabbit", "have it", "హ్యాపీ", "అబ్బా"…), so the ear learns the difference;
  2. real people talking (LibriSpeech English, CC BY 4.0; OpenSLR 66 Telugu, CC BY-SA 4.0) teach it what is NOT its name;
  3. everything is mixed with room echo, fans, people talking around, music and TV-like sound;
  4. a small brain (two layers, ~100k numbers) learns "is this its name?" on top of Google's free speech features
     (openWakeWord's melspectrogram + speech_embedding, Apache 2.0); the phone runs the same features;
  5. an exam: how often it hears its name (recall) and how often it wakes by mistake per hour of real talk;
  6. keeps the ear in your public Hugging Face repo <you>/habit-wake (habit_wake.json). The phone takes it from there.

No one's own voice is used, and nothing from the app or the office.
"""
import argparse
import base64
import io
import json
import math
import os
import random
import re
import shutil
import subprocess
import sys
import tarfile
import time
import zipfile

T0 = time.time()


def say(msg):
    m = int((time.time() - T0) // 60)
    print(f"[{m:3d} min] {msg}", flush=True)


def stop(msg):
    print("\n❌ " + msg + "\n", flush=True)
    sys.exit(1)


def pip(*pkgs):
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", *pkgs], check=False)


# ---------------------------------------------------------------- settings
MODES = {
    # voices, clips of the name, clips of near words, mixing rounds, hours of real talk (English, Telugu), learning steps
    "test": dict(voices=["en_US-libritts_r-medium", "te_IN-maya-medium", "hi_IN-pratham-medium"], pos=1500, adv=1500, rounds=1,
                 libri_train=["dev-clean"], libri_val=["test-clean"], libri_hours=5.0, te=["te_in_female"], te_hours=2.0,
                 steps=3000, hidden=64),
    "full": dict(voices=["en_US-libritts_r-medium", "en_GB-vctk-medium", "te_IN-maya-medium", "te_IN-padmavathi-medium",
                         "te_IN-venkatesh-medium", "hi_IN-pratham-medium", "hi_IN-priyamvada-medium", "hi_IN-rohan-medium"],
                 pos=6000, adv=6000, rounds=2, libri_train=["dev-clean", "test-clean", "train-clean-100"], libri_val=["dev-other"],
                 libri_hours=25.0, te=["te_in_female", "te_in_male"], te_hours=6.0, steps=12000, hidden=64),
}
VOICE_PATH = {
    "en_US-libritts_r-medium": "en/en_US/libritts_r/medium", "en_GB-vctk-medium": "en/en_GB/vctk/medium",
    "te_IN-maya-medium": "te/te_IN/maya/medium", "te_IN-padmavathi-medium": "te/te_IN/padmavathi/medium",
    "te_IN-venkatesh-medium": "te/te_IN/venkatesh/medium", "hi_IN-pratham-medium": "hi/hi_IN/pratham/medium",
    "hi_IN-priyamvada-medium": "hi/hi_IN/priyamvada/medium", "hi_IN-rohan-medium": "hi/hi_IN/rohan/medium",
}
LANG_SHARE = {"en": 0.5, "te": 0.35, "hi": 0.15}

NAME = {
    "en": ["habit", "Habit!", "habit?", "Habit.", "hey habit", "hey, Habit", "hyabit", "habbit", "Habit, Habit"],
    "te": ["హ్యాబిట్", "హ్యాబిట్!", "హ్యాబిట్?", "హాబిట్", "హేయ్ హ్యాబిట్", "ఓయ్ హ్యాబిట్", "హ్యాబిట్ హ్యాబిట్"],
    "hi": ["हैबिट", "हैबिट!", "हेबिट", "अरे हैबिट"],
}
NEAR = {
    "en": ["rabbit", "have it", "habits", "hobbit", "happy", "had it", "cabinet", "a bit", "hand it", "Abbott", "hobby", "orbit",
           "inhabit", "habitat", "hey", "hi", "hello", "bit", "hat", "happen", "Habib", "rabbit hole", "have a bit", "Hamid", "Abid",
           "had a bit", "Robert", "rapid", "hey Robert", "I'll have it", "grab it", "Hobart", "hybrid", "abbey", "Avatar", "Bob it",
           "half", "habitual", "Hey Siri", "Alexa", "OK Google", "what is it", "Abhi", "hug it", "add it", "edit", "credit", "visit",
           "submit", "tablet", "rabbits", "rabbit habit", "habit of reading", "bad habit", "a good habit"],
    "te": ["అబ్బా", "హాయ్", "బాబు", "హబీబ్", "అబిడ్స్", "హ్యాపీ", "హ్యాండ్", "అలవాటు", "చెప్పు", "ఏంటి", "ఎవరు", "బిట్", "రాబిట్",
           "హాస్పిటల్", "అబద్ధం", "హాబీ", "హలో", "ఏమండీ", "బాబాయ్", "అబ్బాయి", "హాయిగా", "హైదరాబాద్", "ఆబిడ్", "హేబిటాట్",
           "ఓ బాబు", "అవును", "ఇదిగో", "హాఫ్", "హ్యాట్", "వెబ్‌సైట్", "క్రెడిట్", "విజిట్", "టాబ్లెట్"],
    "hi": ["हैलो", "हैप्पी", "आदत", "हबीब", "अब्बा", "अभी", "हाबी", "बाबू", "हाय", "रैबिट", "हैदराबाद", "अरे भाई"],
}
TALK = {  # ordinary sentences in the voices too (they are never the name)
    "en": ["what time is it now", "please call my brother", "the meeting is at ten", "send the file to the office",
           "it is going to rain today", "I will come back in the evening", "turn on the light", "where is my phone",
           "have you eaten", "the bus is late again", "let us go to the market", "the report is ready"],
    "te": ["ఈ రోజు వాతావరణం ఎలా ఉంది", "అమ్మకి ఫోన్ చెయ్యి", "మీటింగ్ పది గంటలకి", "ఆఫీసుకి ఫైల్ పంపించు", "భోజనం చేశారా",
           "బస్సు ఇంకా రాలేదు", "సాయంత్రం వస్తాను", "లైట్ వేయి", "నా ఫోన్ ఎక్కడ", "రేపు సెలవు", "పని అయిపోయింది", "మార్కెట్‌కి వెళ్దాం"],
    "hi": ["अभी क्या समय है", "माँ को फोन करो", "मीटिंग दस बजे है", "आज बारिश होगी", "खाना खाया", "बस अभी तक नहीं आई"],
}

SR = 16000
CLIP = 32000             # 2 s windows -> 16 speech features (each 80 ms)
WIN = 16
FEAT_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/"
VOICE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/"
LIBRI = {"dev-clean": 12, "test-clean": 12, "dev-other": 12, "test-other": 12, "train-clean-100": 12}
SLR = ["https://www.openslr.org/resources/", "https://us.openslr.org/resources/", "https://openslr.elda.org/resources/",
       "https://openslr.trmal.net/resources/"]


def setup():
    say("getting the tools (a minute)…")
    pip("piper-tts>=1.3", "soundfile", "scipy", "numpy", "requests", "huggingface_hub")
    # with a GPU the speech features are much faster; without, the normal one
    gpu = shutil.which("nvidia-smi") is not None and subprocess.run(["nvidia-smi"], capture_output=True).returncode == 0
    pip("onnxruntime-gpu" if gpu else "onnxruntime")


# ---------------------------------------------------------------- downloads
def fetch(urls, dest, tries=3):
    import requests
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    if isinstance(urls, str):
        urls = [urls]
    last = None
    for u in urls:
        for t in range(tries):
            try:
                with requests.get(u, stream=True, timeout=60) as r:
                    r.raise_for_status()
                    tmp = dest + ".part"
                    with open(tmp, "wb") as f:
                        for c in r.iter_content(1 << 20):
                            f.write(c)
                os.replace(tmp, dest)
                return dest
            except Exception as e:
                last = e
                time.sleep(3 + 5 * t)
    raise RuntimeError(f"couldn't download {os.path.basename(dest)}: {last}")


def stream_tar(urls):
    """Yields (name, bytes) of the files in a .tar.gz while it downloads (nothing big is kept on disk)."""
    import requests
    for u in urls if isinstance(urls, list) else [urls]:
        try:
            r = requests.get(u, stream=True, timeout=60)
            r.raise_for_status()
            r.raw.decode_content = True
            with tarfile.open(fileobj=r.raw, mode="r|gz") as tf:
                for m in tf:
                    if m.isfile():
                        f = tf.extractfile(m)
                        if f is not None:
                            yield m.name, f.read()
            return
        except Exception as e:
            say(f"  (that place didn't work: {str(e)[:80]}; trying another)")
    raise RuntimeError("couldn't download " + str(urls[0]))


# ---------------------------------------------------------------- speech features (the same steps the phone does)
class Features:
    """openWakeWord's features: melspectrogram (x/10+2) -> windows of 76 frames every 8 -> Google's speech embedding (96)."""

    def __init__(self, models):
        import onnxruntime as ort
        prov = [p for p in ("CUDAExecutionProvider", "CPUExecutionProvider") if p in ort.get_available_providers()]
        o = ort.SessionOptions()
        o.intra_op_num_threads = max(1, (os.cpu_count() or 2))
        try:
            self.mel = ort.InferenceSession(os.path.join(models, "melspectrogram.onnx"), sess_options=o, providers=prov)
            self.emb = ort.InferenceSession(os.path.join(models, "embedding_model.onnx"), sess_options=o, providers=prov)
        except Exception:
            prov = ["CPUExecutionProvider"]
            self.mel = ort.InferenceSession(os.path.join(models, "melspectrogram.onnx"), sess_options=o, providers=prov)
            self.emb = ort.InferenceSession(os.path.join(models, "embedding_model.onnx"), sess_options=o, providers=prov)
        say(f"  speech features on {'the GPU' if 'CUDA' in self.emb.get_providers()[0] else 'the CPU'}")

    def melspec(self, audio):            # audio: [B, N] int16 values as float32 -> [B, frames, 32]
        import numpy as np
        out = self.mel.run(None, {"input": audio.astype(np.float32)})[0]
        return out[:, 0] / 10.0 + 2.0

    def embed(self, mels):                # [B, frames, 32] -> [B, n, 96]
        import numpy as np
        B, F, _ = mels.shape
        idx = [i for i in range(0, F, 8) if i + 76 <= F]
        wins = np.stack([mels[:, i:i + 76] for i in idx], 1).reshape(-1, 76, 32, 1).astype(np.float32)
        outs = []
        for s in range(0, len(wins), 1024):
            outs.append(self.emb.run(None, {"input_1": wins[s:s + 1024]})[0].reshape(-1, 96))
        return np.concatenate(outs).reshape(B, len(idx), 96)

    def clips(self, audio):               # [B, 32000] -> [B, 16, 96]
        import numpy as np
        out = []
        for s in range(0, len(audio), 64):
            out.append(self.embed(self.melspec(audio[s:s + 64]))[:, -WIN:])
        return np.concatenate(out).astype(np.float16)

    def stream(self, audio):              # one long recording -> [T, 96] (one every 80 ms)
        import numpy as np
        if len(audio) < CLIP:
            return np.zeros((0, 96), np.float16)
        return self.embed(self.melspec(audio[None, :]))[0].astype(np.float16)


# ---------------------------------------------------------------- the voices
_VOICES = {}


def _load_voices(paths):
    from piper import PiperVoice
    for name, p in paths.items():
        try:
            _VOICES[name] = PiperVoice.load(p)
        except Exception as e:
            print("voice failed", name, e, flush=True)


def _speak(job):
    """One clip: (voice, text, seed) -> int16 at 16 kHz, silence trimmed (or None)."""
    import numpy as np
    from scipy.signal import resample_poly
    from piper import SynthesisConfig
    name, text, seed, held = job
    v = _VOICES.get(name)
    if v is None:
        return None
    r = random.Random(seed)
    n = int(getattr(v.config, "num_speakers", 1) or 1)
    spk = r.randrange(n) if n > 1 else None
    if n > 7:                                   # the exam's speakers (every 7th) never appear in training
        while (spk % 7 == 0) != held:
            spk = r.randrange(n)
    cfg = SynthesisConfig(speaker_id=spk, length_scale=r.uniform(0.75, 1.35),
                          noise_scale=r.uniform(0.45, 0.9), noise_w_scale=r.uniform(0.5, 1.0), normalize_audio=True)
    try:
        a = np.concatenate([c.audio_int16_array for c in v.synthesize(text, syn_config=cfg)]).astype(np.float32)
    except Exception:
        return None
    sr = int(getattr(v.config, "sample_rate", 22050))
    if sr != SR:
        g = math.gcd(SR, sr)
        a = resample_poly(a, SR // g, sr // g)
    e = np.abs(a)
    if e.max() < 50:
        return None
    k = np.convolve(e, np.ones(160) / 160, mode="same")
    on = np.where(k > 0.04 * k.max())[0]
    a = a[max(0, on[0] - 800): on[-1] + 800]
    if len(a) > CLIP - 1600 or len(a) < 2400:
        return None
    return a.astype(np.int16)


def make_voices(cfg, work):
    paths = {}
    for name in cfg["voices"]:
        base = VOICE_URL + VOICE_PATH[name] + "/" + name
        d = os.path.join(work, "voices")
        os.makedirs(d, exist_ok=True)
        try:
            fetch(base + ".onnx.json", os.path.join(d, name + ".onnx.json"))
            paths[name] = fetch(base + ".onnx", os.path.join(d, name + ".onnx"))
        except Exception as e:
            say(f"  voice {name} not available ({str(e)[:60]}); going on without it")
    if not any(n.startswith("en_") for n in paths):
        stop("the English voices couldn't be downloaded. Check that Internet is on (notebook Settings › Internet) and run again.")
    return paths


def speak_many(paths, texts_by_lang, count, seed, held_out=False):
    """count clips spread over the voices; held_out: different speakers than training (for the exam)."""
    from multiprocessing import Pool
    r = random.Random(seed)
    langs = {}
    for name in paths:
        langs.setdefault(name.split("_")[0], []).append(name)
    jobs = []
    for i in range(count * 13 // 10):          # a few more: some come out too long or empty
        lang = r.choices([l for l in LANG_SHARE if l in langs], [LANG_SHARE[l] for l in LANG_SHARE if l in langs])[0]
        name = r.choice(langs[lang])
        jobs.append((name, r.choice(texts_by_lang[lang]), r.randrange(1 << 30), held_out))
    out = []
    with Pool(max(1, (os.cpu_count() or 2)), initializer=_load_voices, initargs=(paths,)) as p:
        for a in p.imap_unordered(_speak, jobs, chunksize=8):
            if a is not None:
                out.append(a)
            if len(out) >= count:
                break
    return out


# ---------------------------------------------------------------- real talk (what is NOT the name)
def decode(data, name):
    import numpy as np
    import soundfile as sf
    from scipy.signal import resample_poly
    try:
        a, sr = sf.read(io.BytesIO(data), dtype="float32", always_2d=False)
    except Exception:
        return None
    if a.ndim > 1:
        a = a.mean(1)
    if sr != SR:
        g = math.gcd(SR, sr)
        a = resample_poly(a, SR // g, sr // g)
    return (np.clip(a, -1, 1) * 32767).astype(np.int16)


def real_talk(feat, cfg, work, which, hours, te, te_hours, keep_audio, val=False):
    """Feature streams of real people talking, and some of their sound kept for the background mixes."""
    import numpy as np
    streams, got = [], 0.0
    for part in which:
        if got >= hours:
            break
        say(f"  LibriSpeech {part} (English, people reading)…")
        urls = [m + f"{LIBRI[part]}/{part}.tar.gz" for m in SLR]
        for name, data in stream_tar(urls):
            if not name.endswith(".flac"):
                continue
            a = decode(data, name)
            if a is None or len(a) < CLIP:
                continue
            streams.append(feat.stream(a))
            got += len(a) / SR / 3600
            if keep_audio is not None and random.random() < 0.08:
                keep_audio.append(a)
            if got >= hours:
                break
    say(f"  {got:.1f} hours of English talk")
    tg = 0.0
    for z in te:
        if tg >= te_hours:
            break
        say(f"  OpenSLR 66 {z} (Telugu, people reading)…")
        try:
            zp = fetch([m + f"66/{z}.zip" for m in SLR], os.path.join(work, z + ".zip"))
        except Exception as e:
            say(f"  Telugu talk not available ({str(e)[:60]}); going on without it")
            continue
        with zipfile.ZipFile(zp) as zf:
            names = [n for n in zf.namelist() if n.endswith(".wav")]
            random.Random(66).shuffle(names)
            cut = int(len(names) * 0.85)
            for n in (names[cut:] if val else names[:cut]):
                a = decode(zf.read(n), n)
                if a is None or len(a) < CLIP:
                    continue
                streams.append(feat.stream(a))
                tg += len(a) / SR / 3600
                if keep_audio is not None and random.random() < 0.15:
                    keep_audio.append(a)
                if tg >= te_hours:
                    break
        if val:                                   # the training part was read before: the zip isn't needed any more
            try:
                os.remove(zp)
            except Exception:
                pass
    say(f"  {tg:.1f} hours of Telugu talk")
    return streams, got + tg


def windows(streams, step):
    import numpy as np
    out = []
    for s in streams:
        for i in range(0, len(s) - WIN + 1, step):
            out.append(s[i:i + WIN])
    return np.stack(out).astype(np.float16) if out else np.zeros((0, WIN, 96), np.float16)


# ---------------------------------------------------------------- the room: echo, fans, people around, music, TV
class Room:
    def __init__(self, talk_audio, seed=1):
        import numpy as np
        self.r = np.random.default_rng(seed)
        self.talk = [a.astype(np.float32) for a in talk_audio if len(a) >= CLIP] or [np.zeros(CLIP, np.float32)]

    def noise(self, kind):
        import numpy as np
        r = self.r
        n = CLIP
        if kind == "colour":                      # white / pink / brown noise
            w = r.standard_normal(n)
            f = np.fft.rfft(w)
            k = np.arange(1, len(f) + 1)
            f = f / (k ** r.uniform(0, 1.0))
            x = np.fft.irfft(f, n)
        elif kind == "fan":                       # a fan, an AC, a fridge (mains hum, 50 Hz in India)
            t = np.arange(n) / SR
            x = sum(r.uniform(0.2, 1) * np.sin(2 * np.pi * 50 * h * t + r.uniform(0, 6)) for h in (1, 2, 3, 4))
            b = np.convolve(r.standard_normal(n), np.ones(40) / 40, mode="same")
            x = x * r.uniform(0.1, 0.6) + b * 3
        elif kind == "babble":                    # people talking around (an office, a bus)
            x = np.zeros(n, np.float32)
            for _ in range(r.integers(2, 7)):
                a = self.talk[r.integers(len(self.talk))]
                s = r.integers(0, len(a) - n + 1)
                x += a[s:s + n] * r.uniform(0.3, 1)
        elif kind == "talk":                      # one other person, close
            a = self.talk[r.integers(len(self.talk))]
            s = r.integers(0, len(a) - n + 1)
            x = a[s:s + n].copy()
        else:                                     # "music" / TV: chords, beats, sometimes a voice on top
            t = np.arange(n) / SR
            x = np.zeros(n)
            beat = r.uniform(0.3, 0.7)
            for k in range(int(2 / beat) + 1):
                s = int(k * beat * SR)
                f0 = 110 * 2 ** (r.integers(0, 24) / 12)
                e = np.exp(-np.maximum(t - k * beat, 0) * r.uniform(2, 8)) * (t >= k * beat)
                for h, g in ((1, 1), (1.5, 0.5), (2, 0.4), (3, 0.2)):
                    x += g * e * np.sin(2 * np.pi * f0 * h * t)
                if r.random() < 0.5 and s + 800 <= n:
                    x[s:s + 800] += r.standard_normal(800) * 0.8
            if kind == "tv":
                a = self.talk[r.integers(len(self.talk))]
                s = r.integers(0, len(a) - n + 1)
                x = x / (np.abs(x).max() + 1e-6) * 3000 + a[s:s + n]
        x = np.asarray(x, np.float32)
        return x / (np.sqrt(np.mean(x ** 2)) + 1e-6)

    def echo(self, x):
        import numpy as np
        from scipy.signal import fftconvolve
        rt = self.r.uniform(0.15, 0.9)
        n = int(rt * SR)
        t = np.arange(n) / SR
        h = self.r.standard_normal(n) * np.exp(-6.9 * t / rt)
        h[0] = self.r.uniform(1, 4)
        h /= np.sqrt(np.sum(h ** 2))
        return fftconvolve(x, h)[:len(x)]

    def colour(self, x):
        import numpy as np
        from scipy.signal import butter, sosfilt
        k = self.r.integers(3)
        if k == 0:
            sos = butter(2, self.r.uniform(2500, 7000), "low", fs=SR, output="sos")
        elif k == 1:
            sos = butter(2, self.r.uniform(80, 400), "high", fs=SR, output="sos")
        else:
            lo = self.r.uniform(200, 800)
            sos = butter(2, [lo, self.r.uniform(lo * 4, 7500)], "band", fs=SR, output="sos")
        return sosfilt(sos, x)

    def place(self, clip, end=True):
        """Puts a spoken clip in a 2 s window: ending 0-200 ms before the end (when the ear decides), or anywhere."""
        import numpy as np
        out = np.zeros(CLIP, np.float32)
        a = clip.astype(np.float32)
        if end:
            s = CLIP - len(a) - int(self.r.uniform(0, 0.2) * SR)
        else:
            s = int(self.r.integers(0, CLIP - len(a) + 1))
        s = max(0, s)
        out[s:s + len(a)] = a[:CLIP - s]
        return out

    def mix(self, clip, end=True, snr=(-3, 22), p_bg=0.8):
        import numpy as np
        r = self.r
        x = self.place(clip, end) if clip is not None else np.zeros(CLIP, np.float32)
        if clip is not None:
            x = x / (np.abs(x).max() + 1e-6) * r.uniform(0.15, 0.95) * 32767
            if r.random() < 0.5:
                x = self.echo(x)
            if r.random() < 0.3:
                x = self.colour(x)
        speech = np.sqrt(np.mean(x[x != 0] ** 2)) if np.any(x != 0) else r.uniform(200, 3000)
        if clip is None or r.random() < p_bg:
            kind = r.choice(["babble", "talk", "music", "tv", "fan", "colour"], p=[0.25, 0.15, 0.15, 0.15, 0.15, 0.15])
            b = self.noise(kind)
            db = r.uniform(*snr)
            x = x + b * speech / (10 ** (db / 20))
        if r.random() < 0.3:
            x = x + r.standard_normal(CLIP) * speech / (10 ** (r.uniform(15, 35) / 20))
        return np.clip(x, -32768, 32767).astype(np.float32)


def mixed_features(feat, room, clips, rounds, **kw):
    import numpy as np
    out = []
    batch = []
    for _ in range(rounds):
        for c in clips:
            batch.append(room.mix(c, **kw))
            if len(batch) == 256:
                out.append(feat.clips(np.stack(batch)))
                batch = []
    if batch:
        out.append(feat.clips(np.stack(batch)))
    return np.concatenate(out) if out else np.zeros((0, WIN, 96), np.float16)


# ---------------------------------------------------------------- the small brain (numpy: x -> 64 -> 64 -> 1)
class Ear:
    def __init__(self, hidden, seed=0):
        import numpy as np
        r = np.random.default_rng(seed)
        d = WIN * 96
        self.p = {"w1": r.standard_normal((d, hidden)).astype(np.float32) * math.sqrt(2 / d), "b1": np.zeros(hidden, np.float32),
                  "w2": r.standard_normal((hidden, hidden)).astype(np.float32) * math.sqrt(2 / hidden), "b2": np.zeros(hidden, np.float32),
                  "w3": r.standard_normal((hidden, 1)).astype(np.float32) * math.sqrt(1 / hidden), "b3": np.zeros(1, np.float32)}
        self.m = {k: np.zeros_like(v) for k, v in self.p.items()}
        self.v = {k: np.zeros_like(v) for k, v in self.p.items()}
        self.t = 0

    def forward(self, x):
        import numpy as np
        p = self.p
        x = x.reshape(len(x), -1).astype(np.float32)
        a1 = x @ p["w1"] + p["b1"]; h1 = np.maximum(a1, 0)
        a2 = h1 @ p["w2"] + p["b2"]; h2 = np.maximum(a2, 0)
        z = (h2 @ p["w3"] + p["b3"])[:, 0]
        return z, (x, a1, h1, a2, h2)

    def score(self, x):
        import numpy as np
        out = []
        for s in range(0, len(x), 8192):
            z, _ = self.forward(x[s:s + 8192])
            out.append(1 / (1 + np.exp(-np.clip(z, -30, 30))))
        return np.concatenate(out) if out else np.zeros(0)

    def step(self, x, y, w, lr, l2=1e-5, drop=0.1):
        import numpy as np
        p = self.p
        rng = np.random.default_rng(self.t)
        x = x.reshape(len(x), -1).astype(np.float32)
        if drop:
            x = x * (rng.random(x.shape) > drop) / (1 - drop)
        a1 = x @ p["w1"] + p["b1"]; h1 = np.maximum(a1, 0)
        a2 = h1 @ p["w2"] + p["b2"]; h2 = np.maximum(a2, 0)
        z = (h2 @ p["w3"] + p["b3"])[:, 0]
        q = 1 / (1 + np.exp(-np.clip(z, -30, 30)))
        loss = float(np.sum(w * (np.logaddexp(0, z) - y * z)) / np.sum(w))
        dz = (w * (q - y) / np.sum(w))[:, None].astype(np.float32)
        g = {"w3": h2.T @ dz, "b3": dz.sum(0)}
        dh2 = dz @ p["w3"].T * (a2 > 0)
        g["w2"] = h1.T @ dh2; g["b2"] = dh2.sum(0)
        dh1 = dh2 @ p["w2"].T * (a1 > 0)
        g["w1"] = x.T @ dh1; g["b1"] = dh1.sum(0)
        self.t += 1
        b1, b2 = 0.9, 0.999
        for k in p:
            gk = g[k] + (l2 * p[k] if k.startswith("w") else 0)
            self.m[k] = b1 * self.m[k] + (1 - b1) * gk
            self.v[k] = b2 * self.v[k] + (1 - b2) * gk * gk
            mh = self.m[k] / (1 - b1 ** self.t); vh = self.v[k] / (1 - b2 ** self.t)
            p[k] -= (lr * mh / (np.sqrt(vh) + 1e-8)).astype(np.float32)
        return loss

    def export(self):
        out = {}
        for k, v in self.p.items():
            out[k] = {"shape": list(v.shape), "data": base64.b64encode(v.astype("<f4").tobytes()).decode()}
        return out


def train(ear, pos, adv, real, quiet, steps, log_every=500):
    import numpy as np
    r = np.random.default_rng(7)
    hard = np.zeros((0,), np.int64)
    n = {"pos": 192, "adv": 192, "real": 512, "quiet": 64, "hard": 128}
    for s in range(1, steps + 1):
        lr = 1e-4 + 0.5 * (2e-3 - 1e-4) * (1 + math.cos(math.pi * s / steps))
        negw = 1.0 + 9.0 * min(1.0, s / (0.6 * steps))      # mistakes on other talk cost more and more
        ip = r.integers(len(pos), size=n["pos"]); ia = r.integers(len(adv), size=n["adv"])
        ir = r.integers(len(real), size=n["real"]); iq = r.integers(len(quiet), size=n["quiet"])
        parts = [pos[ip], adv[ia], real[ir], quiet[iq]]
        ys = [np.ones(len(ip)), np.zeros(len(ia)), np.zeros(len(ir)), np.zeros(len(iq))]
        ws = [np.ones(len(ip)), np.full(len(ia), negw), np.full(len(ir), negw), np.full(len(iq), negw)]
        if len(hard):
            ih = hard[r.integers(len(hard), size=n["hard"])]
            parts.append(real[ih]); ys.append(np.zeros(len(ih))); ws.append(np.full(len(ih), negw))
        x = np.concatenate(parts); y = np.concatenate(ys).astype(np.float32); w = np.concatenate(ws).astype(np.float32)
        # a little extra variety: features a bit louder / softer
        x = x.astype(np.float32) * r.uniform(0.9, 1.1, size=(len(x), 1, 1)).astype(np.float32)
        loss = ear.step(x, y, w, lr)
        if s % 1000 == 0 and s >= steps // 4:        # the other talk it still mistakes for its name: practise on those
            pick = r.integers(len(real), size=min(len(real), 60000))
            sc = ear.score(real[pick])
            hard = pick[np.argsort(-sc)[:4000]]
        if s % log_every == 0 or s == steps:
            say(f"  learning {s}/{steps}  (mistake score {loss:.3f})")


def fph(ear, streams, hours, thr, gap=25):
    """Wakes by mistake per hour on real talk (after waking it waits 2 s, like the phone)."""
    import numpy as np
    wakes = 0
    for s in streams:
        if len(s) < WIN:
            continue
        x = np.stack([s[i:i + WIN] for i in range(0, len(s) - WIN + 1)])
        sc = ear.score(x)
        last = -10 ** 9
        for i in np.where(sc >= thr)[0]:
            if i - last >= gap:
                wakes += 1
                last = i
    return wakes / max(hours, 1e-6)


# ---------------------------------------------------------------- Hugging Face
def secret(name):
    v = os.environ.get(name, "")
    if v:
        return v.strip()
    try:
        from kaggle_secrets import UserSecretsClient
        return (UserSecretsClient().get_secret(name) or "").strip()
    except Exception:
        return ""


def main():
    ap = argparse.ArgumentParser()
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--test", action="store_true")
    g.add_argument("--full", action="store_true")
    ap.add_argument("--work", default="")
    ap.add_argument("--no-upload", action="store_true")
    ap.add_argument("--no-setup", action="store_true")
    a = ap.parse_args()
    mode = "full" if a.full else "test"
    cfg = MODES[mode]
    out_dir = "/kaggle/working" if os.path.isdir("/kaggle/working") else os.path.abspath("habit_wake_out")
    work = a.work or ("/tmp/habit_wake" if os.path.isdir("/kaggle") else os.path.abspath("habit_wake_work"))
    os.makedirs(work, exist_ok=True); os.makedirs(out_dir, exist_ok=True)
    print("=" * 70 + f"\n habit-wake ({mode}): teaching Habit's ear its name\n" + "=" * 70, flush=True)
    if not a.no_setup:
        setup()
    import numpy as np

    hf, user = "", ""
    if mode == "full" and not a.no_upload:
        hf = secret("HF_TOKEN")
        if not hf:
            stop("add your Hugging Face token: notebook Add-ons › Secrets › HF_TOKEN (Write), and tick its box. (The same as for habit-gemma.)")
        try:
            from huggingface_hub import HfApi
            user = HfApi(token=hf).whoami()["name"]
        except Exception as e:
            stop("Hugging Face didn't accept HF_TOKEN (" + str(e)[:120] + "). Make a new token with Write access.")
        say(f"Hugging Face: {user} ✓")

    say("1/6 the speech features (Google's speech embedding, via openWakeWord)…")
    md = os.path.join(work, "features")
    os.makedirs(md, exist_ok=True)
    for f in ("melspectrogram.onnx", "embedding_model.onnx"):
        fetch(FEAT_URL + f, os.path.join(md, f))
    feat = Features(md)

    say("2/6 real people talking (what is NOT the name)…")
    talk_audio = []
    real_s, real_h = real_talk(feat, cfg, work, cfg["libri_train"], cfg["libri_hours"], cfg["te"], cfg["te_hours"], talk_audio)
    val_s, val_h = real_talk(feat, cfg, work, cfg["libri_val"], 3.0 if mode == "test" else 5.0, cfg["te"],
                             0.5 if mode == "test" else 1.0, None, val=True)
    real = windows(real_s, 3)
    del real_s
    say(f"  {len(real):,} pieces of other talk to learn from; {val_h:.1f} hours kept aside for the exam")

    say("3/6 the voices say its name (and near words)…")
    paths = make_voices(cfg, work)
    say(f"  voices: {', '.join(paths)}")
    near = {l: NEAR[l] + TALK[l] for l in NEAR}
    pos_tr = speak_many(paths, NAME, cfg["pos"], 11)
    adv_tr = speak_many(paths, near, cfg["adv"], 12)
    pos_te = speak_many(paths, NAME, max(300, cfg["pos"] // 6), 13, held_out=True)
    adv_te = speak_many(paths, near, max(300, cfg["adv"] // 6), 14, held_out=True)
    say(f"  {len(pos_tr):,} + {len(pos_te):,} clips of the name, {len(adv_tr):,} + {len(adv_te):,} of near words")
    if len(pos_tr) < 200:
        stop("the voices made too few clips. Run again (it may be a short internet problem).")

    say("4/6 into rooms: echo, fans, people around, music, TV…")
    room = Room(talk_audio)
    pos = mixed_features(feat, room, pos_tr, cfg["rounds"])
    adv = np.concatenate([mixed_features(feat, room, adv_tr, cfg["rounds"]),
                          mixed_features(feat, room, adv_tr[: len(adv_tr) // 2], 1, end=False)])
    quiet = mixed_features(feat, room, [None] * (1500 if mode == "test" else 5000), 1)
    room_t = Room(talk_audio, seed=99)
    pos_x = mixed_features(feat, room_t, pos_te, 1, snr=(0, 20))
    adv_x = mixed_features(feat, room_t, adv_te, 1)
    del talk_audio

    say(f"5/6 learning ({cfg['steps']:,} steps)…")
    ear = Ear(cfg["hidden"])
    train(ear, pos, adv, real, quiet, cfg["steps"])

    say("6/6 the exam…")
    sp, sa = ear.score(pos_x), ear.score(adv_x)
    rows = []
    for thr in (0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95, 0.97):
        rows.append((thr, float(np.mean(sp >= thr)), float(np.mean(sa >= thr)), fph(ear, val_s, val_h, thr)))
    print("\n  threshold   hears its name   near words taken as name   wakes by mistake / hour of talk")
    for thr, rc, fa, fp in rows:
        print(f"    {thr:4.2f}        {rc * 100:5.1f} %              {fa * 100:5.1f} %                    {fp:5.2f}")
    target = 0.5 if mode == "full" else 1.0
    ok = [r for r in rows if r[3] <= target and r[0] >= 0.5]
    best = max(ok, key=lambda r: (r[1], r[0])) if ok else max(rows, key=lambda r: r[0])
    thr, rc, fa, fp = best
    print(f"\n  ➜ chosen: threshold {thr}: hears its name {rc * 100:.0f} % of the time (in noisy rooms, new voices), "
          f"wakes by mistake {fp:.2f} times per hour of other people talking.\n", flush=True)

    made = time.strftime("%Y-%m-%d %H:%M")
    model = {"kind": "habit-wake", "format": 1, "name": "habit", "words": ["Habit", "హ్యాబిట్", "हैबिट"], "window": WIN,
             "layers": ear.export(), "threshold": thr, "recall": round(rc, 3), "near_words": round(fa, 3), "wakes_per_hour": round(fp, 2),
             "mode": mode, "made": made, "version": int(time.time()),
             "features": "openWakeWord v0.5.1 melspectrogram + embedding_model (x/10+2, 76x32 every 8 frames)",
             "trained_on": {"voices": list(paths), "real_talk_hours": round(real_h, 1), "name_clips": len(pos_tr), "near_clips": len(adv_tr)}}
    path = os.path.join(out_dir, "habit_wake.json")
    with open(path, "w") as f:
        json.dump(model, f)
    say(f"saved {path} ({os.path.getsize(path) // 1024} KB)")

    if mode == "test" and not a.no_upload:
        print("This was the quick try: it is NOT sent to the phone. For the real ear run:  python habit_wake.py --full", flush=True)
        return
    if a.no_upload:
        return
    if fp > 2.0 or rc < 0.5:
        stop(f"the ear isn't good enough yet (hears its name {rc * 100:.0f} %, wakes by mistake {fp:.1f}/hour), so it's not sent "
             "to the phone. Send this output to Claude.")
    from huggingface_hub import HfApi
    api = HfApi(token=hf)
    repo = f"{user}/habit-wake"
    api.create_repo(repo, repo_type="model", private=False, exist_ok=True)
    readme = f"""---
license: cc-by-sa-4.0
tags: [wake-word, openwakeword, telugu]
---
# habit-wake

Habit AI's ear for its own name ("Habit" / "హ్యాబిట్" / "हैबिट"), made {made}.
A small network (two layers) on top of openWakeWord's speech features (melspectrogram + Google's speech_embedding, Apache-2.0).

Exam (new voices, noisy rooms; real talk kept aside): hears its name {rc * 100:.0f} %, near words taken as the name
{fa * 100:.0f} %, wakes by mistake {fp:.2f} times per hour of other people talking (threshold {thr}).

Made only from synthetic voices (Piper voices: {", ".join(paths)}; see each voice's model card) and public speech:
LibriSpeech (CC BY 4.0), OpenSLR 66 Telugu (CC BY-SA 4.0). No one's own recordings.
"""
    api.upload_file(path_or_fileobj=path, path_in_repo="habit_wake.json", repo_id=repo)
    api.upload_file(path_or_fileobj=readme.encode(), path_in_repo="README.md", repo_id=repo)
    url = f"https://huggingface.co/{repo}/resolve/main/habit_wake.json"
    print("\n" + "=" * 70)
    print(f" ✅ Done. Habit's ear is in {repo}.")
    print(" The phone takes it by itself (within a day, or at once: Settings › Voice › Call me by name › 🎧 Get the new ear).")
    print(" If the server has no Hugging Face token, type this once in the server terminal:")
    print(f"   bash /root/Big.D/server/set-key.sh WAKE_MODEL_URL {url}")
    print("=" * 70, flush=True)


if __name__ == "__main__":
    main()
