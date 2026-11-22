#!/usr/bin/env python3
"""habit-cartoons: makes cute pictures and short cartoon clips of Habit's OWN characters, free, on Kaggle's GPU.

In a Kaggle notebook (Settings: Accelerator GPU T4 x2; Internet on; secret HF_TOKEN with Write, the same as before), one cell:

    !curl -fsSL https://raw.githubusercontent.com/kishorerevenue-svg/Big.D/main/brain/habit_cartoons.py -o habit_cartoons.py && python habit_cartoons.py

(about 1 to 1.5 hours: about 20 pictures and 6 clips. --test: 4 pictures and 1 clip, about 20 minutes, not sent.)

What it does:
  1. picks scenes: Habit's own pets (the puppy, Rangu the chameleon, the bunny, the panda…) doing everyday Telugu things —
     riding a scooter with flowers, flying a kite at Sankranti, lighting diyas, tea at a village stall, rain, school,
     the Vizag beach, good morning and good night;
  2. draws each as a soft, cute 3D-style picture (Stable Diffusion XL, CreativeML Open RAIL++-M licence, free to use);
  3. makes short moving clips (Wan 2.1 1.3B, Apache 2.0; if that can't run here, a gentle zoom of the picture);
  4. writes a short Telugu + English caption for each, marked 🤖 AI-made;
  5. keeps them in your public Hugging Face dataset <you>/habit-cartoons. The server brings a few into Media every day.

Only our own characters: never famous cartoon characters, brands, logos or real people.
"""
import argparse
import datetime
import json
import os
import random
import shutil
import subprocess
import sys
import time

T0 = time.time()


def say(msg):
    print(f"[{int((time.time() - T0) // 60):3d} min] {msg}", flush=True)


def stop(msg):
    print("\n❌ " + msg + "\n", flush=True)
    sys.exit(1)


def pip(*pkgs):
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", *pkgs], check=False)


# ---------------------------------------------------------------- Habit's own characters and their days
CHARACTERS = {
    "puppy": ("a fluffy cream-coloured puppy with floppy ears and a red collar", "కుక్కపిల్ల", "puppy"),
    "rangu": ("a cute round green chameleon with big googly eyes and a curly tail", "రంగు", "Rangu the chameleon"),
    "bunny": ("a small white bunny with long pink ears", "కుందేలు", "bunny"),
    "panda": ("a chubby baby panda", "పాండా", "panda"),
    "kitten": ("a ginger kitten with big green eyes", "పిల్లి పిల్ల", "kitten"),
    "parrot": ("a bright green parrot with a red beak", "చిలుక", "parrot"),
    "fox": ("a cheerful little orange fox with a fluffy tail", "నక్క పిల్ల", "little fox"),
    "teddy": ("a soft brown teddy bear", "టెడ్డీ", "teddy"),
    "robot": ("a small friendly round white robot with blue glowing eyes", "చిట్టి రోబో", "little robot"),
}
# (scene for the picture, Telugu caption, English caption, kind)  — {te}/{en} = the character's name
SCENES = [
    ("riding a mint-green scooter with a basket full of colourful flowers, on a sunny street", "{te} పూల బుట్టతో స్కూటర్ మీద షికారు! 🛵🌸", "{en} off on a flower ride! 🛵🌸", "day"),
    ("flying a colourful kite on a rooftop on a bright Sankranti morning, rangoli on the floor", "సంక్రాంతి గాలిపటం ఎగరేస్తున్న {te}! 🪁", "{en} flying a Sankranti kite! 🪁", "festival"),
    ("lighting small clay diyas on Deepavali night, warm golden glow, rangoli", "{te} దీపావళి దీపాలు వెలిగిస్తోంది! 🪔", "{en} lighting Deepavali diyas! 🪔", "festival"),
    ("holding a small glass of tea at a cosy village tea stall in the morning", "{te}తో ఒక కప్పు టీ! ☕", "Morning tea with {en}! ☕", "morning"),
    ("splashing happily in rain puddles holding a small yellow umbrella", "వర్షంలో {te} ఆటలు! ☔", "{en} loves the rain! ☔", "day"),
    ("wearing a tiny school bag, walking to school with a big smile", "బడికి బయలుదేరిన {te}! 🎒", "{en} is off to school! 🎒", "morning"),
    ("building a sandcastle on a beach with gentle waves and hills behind, sunset sky", "సముద్రం ఒడ్డున {te} ఇసుక కోట! 🏖️", "{en}'s sandcastle at the beach! 🏖️", "day"),
    ("stretching and waving good morning with a rising sun and flowers", "శుభోదయం! {te} మీకు మంచి రోజు కోరుకుంటోంది 🌞", "Good morning from {en}! 🌞", "morning"),
    ("sleeping cosily under a soft blanket, crescent moon and stars outside the window", "శుభ రాత్రి! {te} నిద్రపోతోంది 🌙", "Good night from {en}! 🌙", "night"),
    ("watering small plants in clay pots in a little garden", "{te} మొక్కలకి నీళ్ళు పోస్తోంది! 🌱", "{en} watering the plants! 🌱", "day"),
    ("reading a big picture book under a mango tree", "మామిడి చెట్టు కింద {te} చదువు! 📖", "{en} reading under the mango tree! 📖", "day"),
    ("riding a small red bicycle in a green village lane with paddy fields", "పల్లెలో సైకిల్ మీద {te}! 🚲", "{en} cycling in the village! 🚲", "day"),
    ("happily eating soft idlis with chutney on a banana leaf", "{te}కి ఇడ్లీ అంటే ఎంతో ఇష్టం! 😋", "{en} loves idli! 😋", "day"),
    ("blowing candles on a small birthday cake with balloons", "పుట్టినరోజు శుభాకాంక్షలు! 🎂 — {te}", "Happy birthday from {en}! 🎂", "day"),
    ("playing cricket with a small bat in a park", "{te} సిక్సర్ కొట్టింది! 🏏", "{en} hits a six! 🏏", "day"),
    ("hugging a big heart-shaped balloon, soft pink background", "మీకు ప్రేమతో — {te} 💖", "With love, from {en} 💖", "day"),
]
STYLE = ("adorable chibi 3D cartoon character, big shiny expressive eyes, soft rounded shapes, smooth clay-like render, "
         "soft studio lighting, gentle shadows, pastel colours, kawaii, cheerful, full body, centered, high detail, clean simple background")
NEGATIVE = ("text, letters, words, caption, watermark, logo, signature, brand, trademark, famous character, real person, human, "
            "scary, horror, ugly, deformed, extra limbs, extra eyes, bad anatomy, blurry, low quality, jpeg artifacts, photo, realistic, dark")


def scenes(n, seed):
    r = random.Random(seed)
    chars = list(CHARACTERS)
    out, used = [], set()
    while len(out) < n:
        c, s = r.choice(chars), r.randrange(len(SCENES))
        if (c, s) in used and len(used) < len(chars) * len(SCENES):
            continue
        used.add((c, s))
        look, te, en = CHARACTERS[c]
        scene, cte, cen, kind = SCENES[s]
        out.append({"char": c, "prompt": f"{look}, {scene}. {STYLE}", "motion": f"{look}, {scene}, the character smiles, moves gently and waves, smooth cute 3D cartoon animation, soft lighting",
                    "te": cte.format(te=te, en=en), "en": (lambda c: c[0].upper() + c[1:])(cen.format(te=te, en=en)), "kind": kind})
    return out


# ---------------------------------------------------------------- pictures (SDXL)
def pictures(items, out_dir, steps):
    import torch
    from diffusers import StableDiffusionXLPipeline, AutoencoderKL
    say("  loading the picture maker (Stable Diffusion XL, a few minutes the first time)…")
    vae = AutoencoderKL.from_pretrained("madebyollin/sdxl-vae-fp16-fix", torch_dtype=torch.float16)
    pipe = StableDiffusionXLPipeline.from_pretrained("stabilityai/stable-diffusion-xl-base-1.0", vae=vae, torch_dtype=torch.float16,
                                                     variant="fp16", use_safetensors=True).to("cuda")
    pipe.enable_vae_slicing()
    pipe.set_progress_bar_config(disable=True)
    import numpy as np
    made = []
    for k, it in enumerate(items):
        g = torch.Generator("cuda").manual_seed(1000 + k)
        try:
            img = pipe(it["prompt"], negative_prompt=NEGATIVE, width=768, height=1344, num_inference_steps=steps, guidance_scale=6.0, generator=g).images[0]
        except Exception as e:
            say(f"  picture {k + 1} failed: {str(e)[:100]}")
            continue
        a = np.asarray(img).astype("float32")
        if not np.isfinite(a).all() or a.mean() < 12 or a.std() < 8:          # black or empty: try once more
            say(f"  picture {k + 1} came out empty; skipped")
            continue
        it["file"] = f"{it['id']}.jpg"
        img.convert("RGB").save(os.path.join(out_dir, it["file"]), quality=90)
        made.append(it)
        say(f"  picture {k + 1}/{len(items)} ✓ ({it['en']})")
    del pipe
    torch.cuda.empty_cache()
    return made


# ---------------------------------------------------------------- clips (Wan 2.1, or a gentle zoom of the picture)
def ffmpeg():
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    pip("imageio-ffmpeg")
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def zoom_clip(img_path, out_path, secs=6):
    """A gentle zoom and float over the picture (when the AI video maker can't run): 720x1280, H.264."""
    fps = 25
    n = secs * fps
    vf = (f"scale=1440:2520,zoompan=z='min(zoom+0.0009,1.12)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)+sin(on/{fps}*2)*12':d={n}:s=720x1260:fps={fps},"
          "pad=720:1280:0:10:color=white,format=yuv420p")
    subprocess.run([ffmpeg(), "-y", "-loglevel", "error", "-loop", "1", "-i", img_path, "-vf", vf, "-t", str(secs), "-c:v", "libx264",
                    "-preset", "veryfast", "-crf", "23", "-movflags", "+faststart", out_path], check=True)


def wan_clips(items, out_dir, frames):
    import torch
    from diffusers import AutoencoderKLWan, WanPipeline
    from diffusers.utils import export_to_video
    say("  loading the clip maker (Wan 2.1, 1.3B)…")
    mid = "Wan-AI/Wan2.1-T2V-1.3B-Diffusers"
    vae = AutoencoderKLWan.from_pretrained(mid, subfolder="vae", torch_dtype=torch.float32)
    pipe = WanPipeline.from_pretrained(mid, vae=vae, torch_dtype=torch.float16)
    pipe.enable_model_cpu_offload()
    pipe.set_progress_bar_config(disable=True)
    import numpy as np
    made = []
    for k, it in enumerate(items):
        t = time.time()
        try:
            out = pipe(prompt=it["motion"], negative_prompt=NEGATIVE, height=832, width=480, num_frames=frames, guidance_scale=5.0,
                       num_inference_steps=30, generator=torch.Generator("cpu").manual_seed(2000 + k)).frames[0]
        except Exception as e:
            say(f"  clip {k + 1} failed: {str(e)[:120]}")
            if k == 0:
                raise
            continue
        arr = np.asarray(out, dtype="float32")
        if not np.isfinite(arr).all() or arr.mean() < 0.05 or arr.std() < 0.02:
            if k == 0:
                raise RuntimeError("the clip maker gives empty frames on this GPU")
            continue
        raw = os.path.join(out_dir, f"{it['id']}_raw.mp4")
        export_to_video(out, raw, fps=16)
        it["video"] = f"{it['id']}.mp4"
        subprocess.run([ffmpeg(), "-y", "-loglevel", "error", "-i", raw, "-vf", "scale=720:1248:flags=lanczos,pad=720:1280:0:16:color=white,format=yuv420p",
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "22", "-movflags", "+faststart", os.path.join(out_dir, it["video"])], check=True)
        os.remove(raw)
        it["maker"] = "wan2.1"
        made.append(it)
        say(f"  clip {k + 1}/{len(items)} ✓ ({int(time.time() - t)} s)")
    del pipe
    torch.cuda.empty_cache()
    return made


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
    ap.add_argument("--test", action="store_true")
    ap.add_argument("--pictures", type=int, default=0)
    ap.add_argument("--clips", type=int, default=-1)
    ap.add_argument("--no-wan", action="store_true", help="clips as a gentle zoom of the picture only")
    a = ap.parse_args()
    n_pic = a.pictures or (4 if a.test else 20)
    n_clip = a.clips if a.clips >= 0 else (1 if a.test else 6)
    out_dir = "/kaggle/working/habit_cartoons" if os.path.isdir("/kaggle/working") else os.path.abspath("habit_cartoons_out")
    os.makedirs(out_dir, exist_ok=True)
    print("=" * 70 + "\n habit-cartoons: cute pictures and clips of Habit's own characters\n" + "=" * 70, flush=True)

    import shutil as _s
    if not _s.which("nvidia-smi") or subprocess.run(["nvidia-smi"], capture_output=True).returncode != 0:
        stop("this needs a GPU: notebook Settings › Accelerator › GPU T4 x2, then run again.")
    hf, user = "", ""
    if not a.test:
        hf = secret("HF_TOKEN")
        if not hf:
            stop("add your Hugging Face token: notebook Add-ons › Secrets › HF_TOKEN (Write), and tick its box.")
    say("getting the tools (a few minutes)…")
    pip("diffusers>=0.33", "transformers", "accelerate", "safetensors", "huggingface_hub", "ftfy", "imageio", "imageio-ffmpeg", "sentencepiece")
    if hf:
        from huggingface_hub import HfApi
        try:
            user = HfApi(token=hf).whoami()["name"]
        except Exception as e:
            stop("Hugging Face didn't accept HF_TOKEN (" + str(e)[:120] + "). Make a new token with Write access.")
        say(f"Hugging Face: {user} ✓")

    day = datetime.date.today().strftime("%Y%m%d")
    items = scenes(n_pic, int(day))
    for k, it in enumerate(items):
        it["id"] = f"{day}_{k:02d}_{it['char']}"

    say(f"1/3 drawing {len(items)} pictures…")
    made = pictures(items, out_dir, 24 if a.test else 30)
    if not made:
        stop("no pictures came out. Send the lines above to Claude.")

    say(f"2/3 making {n_clip} short clips…")
    clips = []
    pick = [it for it in made if it["kind"] != "night"][:n_clip] or made[:n_clip]
    if n_clip and not a.no_wan:
        try:
            clips = wan_clips(pick, out_dir, 49 if not a.test else 33)
        except Exception as e:
            say(f"  the AI clip maker can't run here ({str(e)[:100]}); making gentle zoom clips of the pictures instead")
            clips = []
    for it in pick:
        if it in clips or len(clips) >= n_clip:
            continue
        try:
            it["video"] = f"{it['id']}.mp4"
            zoom_clip(os.path.join(out_dir, it["file"]), os.path.join(out_dir, it["video"]))
            it["maker"] = "zoom"
            clips.append(it)
        except Exception as e:
            it.pop("video", None)
            say(f"  zoom clip failed: {str(e)[:100]}")
    say(f"  {len(clips)} clips ✓")

    say("3/3 keeping them…")
    entries = []
    for it in made:
        e = {"id": it["id"], "char": it["char"], "kind": it["kind"], "te": it["te"], "en": it["en"], "image": f"{day}/{it['file']}",
             "made": day, "label": "🤖 AI-made", "picture_maker": "stable-diffusion-xl-base-1.0"}
        if it.get("video"):
            e["video"] = f"{day}/{it['video']}"
            e["video_maker"] = it.get("maker", "")
        entries.append(e)
    with open(os.path.join(out_dir, "batch.json"), "w") as f:
        json.dump({"day": day, "items": entries}, f, ensure_ascii=False, indent=1)
    if a.test:
        print(f"\nThis was the quick try: {len(made)} pictures and {len(clips)} clips are in {out_dir} (Output on the right). Not sent anywhere.")
        print("For the real batch run:  python habit_cartoons.py", flush=True)
        return

    from huggingface_hub import HfApi, hf_hub_download
    api = HfApi(token=hf)
    repo = f"{user}/habit-cartoons"
    api.create_repo(repo, repo_type="dataset", private=False, exist_ok=True)
    old = []
    try:
        with open(hf_hub_download(repo, "manifest.json", repo_type="dataset", token=hf)) as f:
            old = json.load(f).get("items", [])
    except Exception:
        pass
    have = {e["id"] for e in old}
    manifest = {"updated": datetime.datetime.utcnow().isoformat() + "Z", "items": (old + [e for e in entries if e["id"] not in have])[-400:]}
    up = os.path.join(out_dir, "_upload")
    if os.path.isdir(up):
        shutil.rmtree(up)
    os.makedirs(os.path.join(up, day))
    for it in made:
        shutil.copy(os.path.join(out_dir, it["file"]), os.path.join(up, day, it["file"]))
        if it.get("video"):
            shutil.copy(os.path.join(out_dir, it["video"]), os.path.join(up, day, it["video"]))
    with open(os.path.join(up, "manifest.json"), "w") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    with open(os.path.join(up, "README.md"), "w") as f:
        f.write("---\nlicense: cc-by-4.0\ntags: [cartoon, ai-generated, telugu]\n---\n# habit-cartoons\n\nCute pictures and short clips of Habit AI's own "
                "characters, AI-made (Stable Diffusion XL; clips: Wan 2.1 or a gentle zoom). Every item is labelled 🤖 AI-made.\n")
    api.upload_folder(folder_path=up, repo_id=repo, repo_type="dataset", commit_message=f"batch {day}")
    print("\n" + "=" * 70)
    print(f" ✅ Done: {len(made)} pictures and {len(clips)} clips are in {repo}.")
    print(" The server brings a few into Media every day by itself (Fun). Run this again any week for a new batch.")
    print("=" * 70, flush=True)


if __name__ == "__main__":
    main()
