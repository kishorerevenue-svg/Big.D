#!/usr/bin/env python3
"""habit-gemma: makes Habit AI's own AI model, for free, on a Kaggle GPU.

In a Kaggle notebook (GPU T4 x2, internet on, secrets HF_TOKEN and HABIT_LINK), one cell:

    !curl -fsSL https://raw.githubusercontent.com/kishorerevenue-svg/Big.D/main/brain/habit_gemma.py -o habit_gemma.py && python habit_gemma.py --test

(--test: a quick try, about 1 hour. --full: the real one, 4 to 8 hours.) See brain/README.md.

What it does:
  1. Big Gemma (Google Gemma 4 26B, Apache 2.0) writes lessons in Telugu and English about everyday life, money safety,
     health, government services, office writing, farming and more (brain/topics.json), and checks each one;
  2. adds the owner's own lessons and library from the Teach page (the HABIT_LINK secret), and the Aya dataset's Telugu
     and English (written by people, Apache 2.0);
  3. teaches small Gemma 4 (E2B or E4B) with all of it (Unsloth, LoRA);
  4. makes it a file Ollama runs (GGUF, Q4_K_M) and gives the same exam as the Teach page (brain/exam.json) to it and to
     the plain Gemma;
  5. keeps the lessons and the new model in your private Hugging Face repos (<you>/habit-lessons, <you>/habit-gemma).
     Then the server takes it with server/brain-model.sh.

Claude's words are never used: Anthropic's terms don't allow training other AI models on Claude's answers. The teacher
is Gemma itself, and nothing Claude wrote or read is in the owner's training file. The exam's questions are never
lessons (lessons too close to them are left out).
"""
import argparse
import gc
import glob
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

RAW = os.environ.get("HABIT_RAW", "https://raw.githubusercontent.com/kishorerevenue-svg/Big.D/main/brain/")
WORK = os.environ.get("HABIT_WORK") or ("/kaggle/working" if os.path.isdir("/kaggle/working") else os.path.abspath("habit-work"))
BIG = os.environ.get("HABIT_BIG", "/tmp/habit")          # big files that are not kept
OLLAMA = os.environ.get("HABIT_OLLAMA_URL", "http://127.0.0.1:11434")
STUDENT = {"E2B": "unsloth/gemma-4-E2B-it", "E4B": "unsloth/gemma-4-E4B-it"}
PLAIN = {"E2B": "gemma4:e2b", "E4B": "gemma4:e4b"}       # the plain Gemma in Ollama, for the exam
LANGS = {"te": "Telugu, in Telugu script (తెలుగు లిపి)", "tl": "Telugu written in English letters, the way people type on phones (like \"naaku help kavali\")",
         "en": "simple English"}
T0 = time.time()


def say(*a):
    print(time.strftime("%H:%M"), *a, flush=True)


def step(title):
    print("\n" + "=" * 72 + "\n" + time.strftime("%H:%M") + "  " + title + "\n" + "=" * 72, flush=True)


def stop(msg):
    print("\n❌ STOPPED: " + msg + "\n", flush=True)
    sys.exit(1)


def sh(cmd, check=True, quiet=False, env=None):
    r = subprocess.run(cmd, shell=True, text=True, capture_output=quiet, env=env)
    if check and r.returncode != 0:
        if quiet:
            print((r.stdout or "")[-2000:], (r.stderr or "")[-2000:])
        stop("this command didn't work: " + cmd[:160])
    return r


def hours():
    return (time.time() - T0) / 3600


# ------------------------------------------------------------------ settings
def settings():
    p = argparse.ArgumentParser(description="Makes habit-gemma, Habit AI's own model.")
    p.add_argument("--test", action="store_true", help="a quick try (about 1 hour), into <you>/habit-gemma-test")
    p.add_argument("--full", action="store_true", help="the real one (4 to 8 hours), into <you>/habit-gemma")
    p.add_argument("--size", default="E2B", choices=list(STUDENT), help="E2B: for a server with 6 GB memory. E4B: smarter, 10 GB")
    p.add_argument("--teacher", default="gemma4:26b", help="Big Gemma in Ollama (gemma4:26b, or gemma4:31b: a little smarter, slower)")
    p.add_argument("--new-lessons", default="auto", choices=["auto", "yes", "no"],
                   help="auto: Big Gemma writes lessons again after 30 days (and finishes ones it didn't finish); yes; no")
    p.add_argument("--hours", type=float, default=6.0, help="at most this many hours for writing lessons (Kaggle stops at 12)")
    p.add_argument("--no-aya", action="store_true", help="don't add the Aya dataset")
    p.add_argument("--stage", default="", help=argparse.SUPPRESS)
    a = p.parse_args()
    if not a.test and not a.full and not a.stage:
        a.test = True
        say("No --test or --full: doing a quick test (--test).")
    a.mode = "test" if a.test else "full"
    return a


def secret(name):
    v = os.environ.get(name, "").strip()
    if v:
        return v
    try:
        from kaggle_secrets import UserSecretsClient
        return (UserSecretsClient().get_secret(name) or "").strip()
    except Exception:
        return ""


# ------------------------------------------------------------------ the machine
def gpus(cfg):
    """The GPUs (nvidia-smi only: nothing is imported before the libraries are installed)."""
    step("1. Checking this machine")
    r = sh("nvidia-smi --query-gpu=name,memory.total --format=csv,noheader,nounits", check=False, quiet=True)
    gpus = [l.split(",") for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not gpus and not os.environ.get("HABIT_NO_GPU_OK"):
        stop("there is no GPU. In the notebook: Settings › Accelerator › GPU T4 x2, then run again.")
    vram = sum(int(float(g[1])) for g in gpus) if gpus else 0
    say("GPUs:", ", ".join(f"{g[0].strip()} ({int(float(g[1])) // 1024} GB)" for g in gpus) or "none (test machine)")
    if gpus and vram < 28000 and cfg.teacher in ("gemma4:26b", "gemma4:31b"):
        cfg.teacher = "gemma4:12b"
        say("Less than 28 GB of GPU memory: the teacher is gemma4:12b (for gemma4:26b choose GPU T4 x2).")
    cfg.gpus = len(gpus)
    import urllib.request
    try:
        urllib.request.urlopen("https://huggingface.co", timeout=20)
    except Exception:
        if not os.environ.get("HABIT_NO_GPU_OK"):
            stop("no internet. In the notebook: Settings › Internet on (Kaggle asks you to verify your phone number once).")
    free = shutil.disk_usage("/tmp").free // 2 ** 30
    say(f"Free disk: {free} GB")
    if free < 45 and not os.environ.get("HABIT_NO_GPU_OK"):
        say("⚠️ Less than 45 GB free disk: it may run out. Kaggle usually has about 70 GB.")


def machine(cfg):
    """The internet, the Hugging Face token and the training link (after the libraries are installed)."""
    import requests
    try:
        requests.get("https://huggingface.co/api/models/unsloth/gemma-4-E2B-it", timeout=20)
    except Exception:
        stop("no internet. In the notebook: Settings › Internet on (Kaggle asks you to verify your phone number once).")
    cfg.hf = secret("HF_TOKEN")
    cfg.link = secret("HABIT_LINK")
    if not cfg.hf:
        stop("add your Hugging Face token: in the notebook Add-ons › Secrets › Add secret, name HF_TOKEN (type Write), and tick its box. See the guide.")
    from huggingface_hub import HfApi
    try:
        cfg.user = HfApi(token=cfg.hf).whoami()["name"]
    except Exception as e:
        stop("Hugging Face didn't accept HF_TOKEN (" + str(e)[:120] + "). Make a new token with Write access.")
    say(f"Hugging Face: {cfg.user} ✓   Owner's lessons link: {'✓' if cfg.link else 'not added (only Big Gemma and Aya will teach)'}")


def files(cfg):
    """exam.json, habit_exam.py and topics.json, from GitHub (always the newest)."""
    import requests
    os.makedirs(BIG, exist_ok=True)
    here = os.path.dirname(os.path.abspath(__file__))
    for name in ["exam.json", "habit_exam.py", "topics.json"]:
        local = os.path.join(os.environ.get("HABIT_FILES_DIR", ""), name) if os.environ.get("HABIT_FILES_DIR") else ""
        dst = os.path.join(BIG, name)
        if local and os.path.exists(local):
            shutil.copy(local, dst)
            continue
        try:
            r = requests.get(RAW + name, timeout=60)
            r.raise_for_status()
            open(dst, "wb").write(r.content)
        except Exception:
            if os.path.exists(os.path.join(here, name)):
                shutil.copy(os.path.join(here, name), dst)
            else:
                stop("couldn't download " + name + " from GitHub.")
    sys.path.insert(0, BIG)
    import habit_exam
    cfg.exam = json.load(open(os.path.join(BIG, "exam.json"), encoding="utf-8"))
    cfg.topics = json.load(open(os.path.join(BIG, "topics.json"), encoding="utf-8"))
    cfg.check = habit_exam
    say(f"Exam: {len(cfg.exam['items'])} questions. Topics: {sum(len(g['topics']) for g in cfg.topics['groups'].values())}.")


# ------------------------------------------------------------------ Ollama
ollama_proc = None


def ollama_install():
    if shutil.which("ollama"):
        return
    say("Installing Ollama (it runs the Gemma models)...")
    sh("(apt-get -qq update && apt-get -qq install -y zstd pciutils lshw) >/dev/null 2>&1", check=False)
    sh("curl -fsSL https://ollama.com/install.sh | sh >/tmp/ollama-install.log 2>&1", check=False)
    if not shutil.which("ollama"):
        for f, x in [("ollama-linux-amd64.tar.zst", "--zstd -xf"), ("ollama-linux-amd64.tgz", "-xzf")]:
            sh(f"curl -fsSL -o /tmp/{f} https://github.com/ollama/ollama/releases/latest/download/{f} && tar -C /usr {x} /tmp/{f}", check=False, quiet=True)
            if shutil.which("ollama"):
                break
    if not shutil.which("ollama"):
        stop("couldn't install Ollama.")


def ollama_up(gpus=None, parallel=4):
    """Starts Ollama (again) on these GPUs ("0,1"; None: all)."""
    global ollama_proc
    import requests
    ollama_down()
    env = dict(os.environ, OLLAMA_HOST=OLLAMA.split("//")[1], OLLAMA_NUM_PARALLEL=str(parallel), OLLAMA_MAX_LOADED_MODELS="1",
               OLLAMA_KEEP_ALIVE="30m")
    if gpus is not None:
        env["CUDA_VISIBLE_DEVICES"] = gpus
    os.makedirs(WORK, exist_ok=True)
    ollama_proc = subprocess.Popen(["ollama", "serve"], env=env, stdout=open(os.path.join(BIG, "ollama.log"), "a"), stderr=subprocess.STDOUT)
    for _ in range(90):
        try:
            requests.get(OLLAMA + "/api/tags", timeout=2)
            return
        except Exception:
            time.sleep(1)
    stop("Ollama didn't start (see /tmp/habit/ollama.log).")


def ollama_down():
    global ollama_proc
    if ollama_proc:
        ollama_proc.terminate()
        try:
            ollama_proc.wait(20)
        except Exception:
            ollama_proc.kill()
        ollama_proc = None
        time.sleep(2)


def ollama(args, check=True):
    return sh("ollama " + args, check=check, quiet=True)


def pull(tag):
    say(f"Downloading {tag} ...")
    for _ in range(3):
        if ollama(f"pull {tag}", check=False).returncode == 0:
            say(f"{tag} ✓")
            return
        time.sleep(10)
    stop(f"couldn't download {tag} from Ollama.")


thinkers = {}


def chat(model, system, user, schema=None, temperature=0.7, num_predict=700, options=None, timeout=900, think=None):
    import requests
    if model not in thinkers:
        try:
            caps = requests.post(OLLAMA + "/api/show", json={"model": model}, timeout=30).json().get("capabilities") or []
        except Exception:
            caps = []
        thinkers[model] = "thinking" in caps
    body = {"model": model, "stream": False, "keep_alive": "30m",
            "messages": ([{"role": "system", "content": system}] if system else []) + [{"role": "user", "content": user}],
            "options": options or {"temperature": temperature, "num_predict": num_predict, "num_ctx": 4096}}
    if schema:
        body["format"] = schema
    if think is not None and thinkers[model]:
        body["think"] = think
    r = requests.post(OLLAMA + "/api/chat", json=body, timeout=timeout)
    r.raise_for_status()
    return (r.json().get("message") or {}).get("content", "")


def jchat(*a, **k):
    """The teacher's JSON answer (it doesn't think aloud first: quicker), or None."""
    k.setdefault("think", False)
    try:
        return json.loads(chat(*a, **k))
    except Exception:
        return None


teacher_ready = []


def need_teacher(cfg):
    if not teacher_ready:
        pull(cfg.teacher)
        teacher_ready.append(cfg.teacher)


# ------------------------------------------------------------------ Big Gemma writes lessons
QS = {"type": "object", "properties": {"questions": {"type": "array", "items": {"type": "string"}}}, "required": ["questions"]}
ANS = {"type": "object", "properties": {"reply": {"type": "string"}}, "required": ["reply"]}
JUDGE = {"type": "object", "properties": {"score": {"type": "integer"}, "why": {"type": "string"}}, "required": ["score"]}
PAIRS = {"type": "object", "properties": {"pairs": {"type": "array", "items": {"type": "object", "properties": {
    "q": {"type": "string"}, "a": {"type": "string"}}, "required": ["q", "a"]}}}, "required": ["pairs"]}

ASKER = ("You write realistic questions that ordinary people in Andhra Pradesh and Telangana type or say to Habit, "
         "a friendly helper app on their phone. People of every kind: farmers, students, mothers, office workers, elders.")
HABIT = """You are Habit, a kind, cheerful cartoon puppy who lives in a phone app called Habit AI, used by ordinary families in Andhra Pradesh and Telangana. You answer like a warm, sensible friend.
Rules:
- Language: if the question is in Telugu, or in Telugu written with English letters, answer in simple spoken Telugu in Telugu script. If it is in English, answer in simple English.
- Be short, your answer is read aloud: chat in 1 to 3 sentences; questions in up to 6 short sentences, or a few short steps.
- Be correct. Never make up numbers, fees, dates, phone numbers, websites, names or rules. When you are not sure, say so and say where to check (the Village or Ward Secretariat, MeeSeva, the official website, a doctor, the bank).
- Money safety: never ask for OTPs, PINs, CVVs or passwords, and warn kindly about scams. Money lost to a fraud: call 1930 at once or report at cybercrime.gov.in.
- Health: general, safe advice only. For anything serious say to see a doctor, or call 108 in an emergency.
- Office writing: when asked to write a letter or a note, write a short, proper one (to whom, subject, the matter, a polite closing).
- Say no kindly to anything harmful or against the law, and suggest something good instead.
- You are a cartoon puppy in the app, not a real dog and not a person. Never name any AI company or AI model.
Answer with JSON: {"reply": "..."}"""
CHECKER = ("You are a strict checker of answers written for Habit, a helper app used by families in Andhra Pradesh. "
           "Be strict about wrong facts, made-up numbers, websites or rules, unsafe advice, and the wrong language.")


def lang_ok(cfg, text, lang):
    r = cfg.check.check({"want": {"script": "en" if lang == "en" else "te", "maxWords": 170}}, json.dumps({"emotion": "happy", "reply": text}), ["happy"])
    return r["ok"]


def clean_answer(cfg, a, lang):
    a = (a or "").strip()
    if len(a) < 2 or len(a) > 1600 or not lang_ok(cfg, a, lang):
        return ""
    if re.search(r"\d{8,}", a.replace("-", "")) or any(w in a.lower() for w in cfg.check.NEVER + ["claude", "anthropic", "gemini", "gemma"]):
        return ""
    return a


def teach_one(cfg, q, lang, topic, group):
    """Big Gemma answers, then checks its answer (kept if 4 or 5 out of 5)."""
    j = jchat(cfg.teacher, HABIT, q, schema=ANS, temperature=0.6, num_predict=600)
    a = clean_answer(cfg, (j or {}).get("reply", ""), lang)
    if not a:
        return None
    want = "Telugu in Telugu script" if lang in ("te", "tl") else "English"
    v = jchat(cfg.teacher, CHECKER, f"Question: {q}\n\nAnswer: {a}\n\nCheck: 1) correct and safe, with nothing made up? 2) written in {want}? "
              "3) short, clear, kind and useful? Give a score from 1 to 5 (5 very good, 4 good, 3 or less has a problem). "
              'Return JSON: {"score": n, "why": "a few words"}', schema=JUDGE, temperature=0.1, num_predict=80)
    s = (v or {}).get("score", 0)
    if not isinstance(s, int) or s < 4:
        return None
    return {"q": q.strip(), "a": a, "lang": "te" if lang in ("te", "tl") else "en", "asked": lang, "topic": topic, "group": group, "from": "teacher", "score": s}


def ask_questions(cfg, group, g, topic, lang, n):
    en, te = topic
    j = jchat(cfg.teacher, ASKER, f"Write {n} different questions about: {en} ({te}). Area: {g['en']}.\nWrite them in {LANGS[lang]}.\n"
              "Mix them: short everyday ones, some with a small personal situation (no real names, phone numbers or ID numbers), and one that asks for steps. "
              "Each question 4 to 30 words. Don't number them.\nReturn JSON: {\"questions\": [\"...\"]}", schema=QS, temperature=0.9, num_predict=900)
    out = []
    for q in (j or {}).get("questions", [])[:n]:
        q = re.sub(r"^\s*(\d+[.)]\s*|[-•]\s*)", "", str(q)).strip()
        if 8 <= len(q) <= 300 and (lang == "te") == bool(re.search(r"[\u0C00-\u0C7F]", q)):
            out.append(q)
    return out


def words_of(s):
    return set(re.findall(r"[\w\u0C00-\u0C7F]+", s.lower()))


def too_close(cfg, q):
    """True when a lesson's question is nearly an exam question (those are only for testing)."""
    if not hasattr(cfg, "exam_words"):
        cfg.exam_words = [words_of(i["q"]) for i in cfg.exam["items"]]
    w = words_of(q)
    if not w:
        return True
    return any(len(w & e) / len(w | e) >= 0.6 for e in cfg.exam_words if e)


def hf_api(cfg):
    from huggingface_hub import HfApi
    return HfApi(token=cfg.hf)


def lessons_repo(cfg):
    return f"{cfg.user}/habit-lessons"


def saved_lessons(cfg):
    """The lessons Big Gemma wrote before (kept in <you>/habit-lessons): (lessons, info) or ([], None)."""
    from huggingface_hub import hf_hub_download
    try:
        info = json.load(open(hf_hub_download(lessons_repo(cfg), "lessons-info.json", repo_type="dataset", token=cfg.hf, cache_dir=BIG + "/hf")))
        path = hf_hub_download(lessons_repo(cfg), "lessons.jsonl", repo_type="dataset", token=cfg.hf, cache_dir=BIG + "/hf")
        return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()], info
    except Exception:
        return [], None


def keep_lessons(cfg, lessons, info):
    """Saves Big Gemma's lessons in <you>/habit-lessons (private), so a later run can use them again."""
    os.makedirs(WORK, exist_ok=True)
    path = os.path.join(WORK, "lessons.jsonl")
    with open(path, "w", encoding="utf-8") as f:
        for l in lessons:
            f.write(json.dumps(l, ensure_ascii=False) + "\n")
    json.dump(info, open(os.path.join(WORK, "lessons-info.json"), "w"), indent=1)
    if cfg.mode == "test":
        return
    try:
        api = hf_api(cfg)
        api.create_repo(lessons_repo(cfg), repo_type="dataset", private=True, exist_ok=True)
        api.upload_file(path_or_fileobj=path, path_in_repo="lessons.jsonl", repo_id=lessons_repo(cfg), repo_type="dataset")
        api.upload_file(path_or_fileobj=os.path.join(WORK, "lessons-info.json"), path_in_repo="lessons-info.json", repo_id=lessons_repo(cfg), repo_type="dataset")
    except Exception as e:
        say("⚠️ couldn't save the lessons in Hugging Face now: " + str(e)[:160])


def plan(cfg):
    """(group, topic, language, how many questions) for every lesson job."""
    groups = cfg.topics["groups"]
    per = {"te": 6, "tl": 3, "en": 4}
    jobs = []
    for gk, g in groups.items():
        for t in g["topics"]:
            for lang, n in per.items():
                jobs.append((gk, tuple(t), lang, n))
    if cfg.mode == "test":
        pick = []
        for gk in ["safety", "govt", "daily"]:
            t = tuple(groups[gk]["topics"][0])
            pick += [(gk, t, "te", 2), (gk, t, "en", 2)]
        return pick
    random.Random(7).shuffle(jobs)
    return jobs


def teacher_lessons(cfg):
    step("2. Big Gemma writes lessons")
    old, info = ([], None) if cfg.mode == "test" else saved_lessons(cfg)
    if cfg.new_lessons == "no":
        if old:
            say(f"Using the {len(old)} lessons Big Gemma wrote before (--new-lessons no).")
            return old
        stop("there are no saved lessons yet: run with --new-lessons auto.")
    # auto: keep the saved lessons (less than 30 days old, or not finished yet) and write only the topics they don't have
    recent = bool(info) and time.time() - info.get("at", 0) < 30 * 86400
    done = set(info.get("done", [])) if (info and cfg.new_lessons == "auto" and (recent or not info.get("complete"))) else set()
    lessons = list(old) if done else []
    jobs = [j for j in plan(cfg) if f"{j[0]}|{j[1][0]}|{j[2]}" not in done]
    if not jobs:
        say(f"Using the {len(lessons)} lessons Big Gemma wrote on {time.strftime('%d %b', time.localtime(info.get('at', 0)))} (--new-lessons yes to write new ones).")
        return lessons
    if done:
        say(f"{len(lessons)} lessons were written before; {len(jobs)} topics are left (or new).")
    need_teacher(cfg)
    budget = 0.15 if cfg.mode == "test" else cfg.hours
    lock, last_save, t_start = threading.Lock(), time.time(), time.time()
    asked = kept = 0
    info = {"teacher": cfg.teacher, "at": time.time(), "complete": False, "done": sorted(done)}

    def job(j):
        gk, t, lang, n = j
        out = []
        for q in ask_questions(cfg, gk, cfg.topics["groups"][gk], t, lang, n):
            if too_close(cfg, q):
                continue
            try:
                l = teach_one(cfg, q, lang, t[0], gk)
            except Exception:
                l = None
            out.append(l)
        return j, out

    with ThreadPoolExecutor(4) as pool:
        futs = [pool.submit(job, j) for j in jobs]
        for f in as_completed(futs):
            try:
                j, out = f.result()
            except Exception as e:
                say("a topic failed:", str(e)[:120])
                continue
            with lock:
                asked += len(out)
                good = [l for l in out if l]
                kept += len(good)
                lessons.extend(good)
                info["done"].append(f"{j[0]}|{j[1][0]}|{j[2]}")
                n_done = len(info["done"]) - len(done)
                if n_done % 20 == 0 or n_done == len(jobs):
                    left = (time.time() - t_start) / max(1, n_done) * (len(jobs) - n_done) / 3600
                    say(f"{n_done}/{len(jobs)} topics · {len(lessons)} lessons ({kept} of {asked} answers passed the check) · about {left:.1f} h left")
                if time.time() - last_save > 1200:
                    last_save = time.time()
                    keep_lessons(cfg, lessons, info)
            if (time.time() - t_start) / 3600 > budget:
                say(f"⏱ {budget} hours used for lessons: the rest are written next time (--new-lessons auto).")
                for x in futs:
                    x.cancel()
                break
    have = set(info["done"])
    info["complete"] = cfg.mode != "test" and all(f"{j[0]}|{j[1][0]}|{j[2]}" in have for j in plan(cfg))
    info["at"] = time.time()
    keep_lessons(cfg, lessons, info)
    say(f"Big Gemma's lessons: {len(lessons)} ✓")
    return lessons


def owner(cfg):
    """The owner's lessons and library (from the Teach page's training link)."""
    import requests
    if not cfg.link:
        return {"lessons": [], "docs": []}
    try:
        r = requests.get(cfg.link, timeout=90)
        if r.status_code == 403:
            stop("the training link (HABIT_LINK) has expired or is wrong: make a new one on the Teach page and save it again in Secrets.")
        r.raise_for_status()
        d = r.json()
        if d.get("app") != "Habit AI":
            stop("HABIT_LINK is not a Habit AI training link.")
        say(f"Owner's lessons: {len(d.get('lessons', []))}, library documents: {len(d.get('docs', []))}"
            + (f" ({d['counts'].get('docsSkipped')} left out: Claude read them)" if d.get("counts", {}).get("docsSkipped") else ""))
        return d
    except SystemExit:
        raise
    except Exception as e:
        say("⚠️ couldn't get the owner's lessons now (" + str(e)[:120] + "): going on without them.")
        return {"lessons": [], "docs": []}


def owner_lessons(cfg, data):
    """The owner's lessons, and other ways people may ask the same questions (Big Gemma writes the questions only)."""
    out, extra = [], []
    for l in data.get("lessons", []):
        q, a = str(l.get("q", "")).strip(), str(l.get("a", "")).strip()
        if q and a:
            out.append({"q": q, "a": a, "lang": l.get("lang", "en"), "topic": l.get("topic", ""), "from": "owner"})
    todo = out if cfg.mode == "full" else out[:3]
    if todo:
        need_teacher(cfg)
    for l in todo:
        for lang, n in ([("te", 3), ("tl", 2)] if l["lang"] == "te" else [("en", 3)]):
            j = jchat(cfg.teacher, ASKER, f"Write {n} other ways people may ask this same question, in {LANGS[lang]}. Keep the meaning exactly the same.\n"
                      f"Question: {l['q']}\nReturn JSON: {{\"questions\": [\"...\"]}}", schema=QS, temperature=0.8, num_predict=500)
            for q in (j or {}).get("questions", [])[:n]:
                q = str(q).strip()
                if 5 <= len(q) <= 300 and q != l["q"]:
                    extra.append({"q": q, "a": l["a"], "lang": l["lang"], "topic": l["topic"], "from": "owner-asked"})
    return out, extra


def library_lessons(cfg, data):
    """Lessons from the owner's library (typed notes, text files, web pages): only facts that are in them."""
    pieces = []
    for d in data.get("docs", []):
        paras, buf = re.split(r"\n\s*\n", d.get("text", "")), ""
        for p in paras:
            if len(buf) + len(p) > 2200 and buf:
                pieces.append((d["title"], buf))
                buf = ""
            buf += ("\n\n" if buf else "") + p[:2200]
        if buf.strip():
            pieces.append((d["title"], buf))
    pieces = [p for p in pieces if len(p[1]) > 300][: (3 if cfg.mode == "test" else 160)]
    out = []
    if pieces:
        need_teacher(cfg)
    for title, text in pieces:
        te = len(re.findall(r"[\u0C00-\u0C7F]", text)) > 0.3 * max(1, len(re.findall(r"[A-Za-z\u0C00-\u0C7F]", text)))
        lang = "te" if te else "en"
        j = jchat(cfg.teacher, "You make question-and-answer lessons from a passage, for a helper app used by families in Andhra Pradesh.",
                  f"Passage (from \"{title}\"):\n{text}\n\nWrite 3 questions that ordinary people may ask which this passage answers, and the answer to each, "
                  f"using ONLY facts from the passage. Write them in {LANGS[lang]}. Each answer up to 5 short sentences.\n"
                  'Return JSON: {"pairs": [{"q": "...", "a": "..."}]}', schema=PAIRS, temperature=0.4, num_predict=1200)
        for p in (j or {}).get("pairs", [])[:3]:
            q, a = str(p.get("q", "")).strip(), clean_answer(cfg, p.get("a", ""), lang)
            if q and a and not too_close(cfg, q):
                out.append({"q": q, "a": a, "lang": lang, "topic": title[:60], "from": "library"})
    if pieces:
        say(f"Lessons from the owner's library: {len(out)}")
    return out


def aya(cfg):
    """The Aya dataset's Telugu and English questions and answers written by people (Apache 2.0)."""
    if cfg.no_aya:
        return []
    want = {"tel": 40, "eng": 10} if cfg.mode == "test" else {"tel": 1500, "eng": 400}
    try:
        from datasets import load_dataset
        ds = None
        for name in ["CohereLabs/aya_dataset", "CohereForAI/aya_dataset"]:
            try:
                ds = load_dataset(name, split="train", token=cfg.hf)
                break
            except Exception:
                continue
        if ds is None:
            raise RuntimeError("not found")
        rows = {k: [] for k in want}
        for r in ds:
            c = r.get("language_code")
            if c in rows and r.get("annotation_type") == "original-annotations" and 10 <= len(r["targets"]) <= 900 and len(r["inputs"]) <= 600:
                rows[c].append({"q": r["inputs"].strip(), "a": r["targets"].strip(), "lang": "te" if c == "tel" else "en", "topic": "", "from": "aya"})
        out = []
        for c, n in want.items():
            random.Random(3).shuffle(rows[c])
            out += [x for x in rows[c] if not too_close(cfg, x["q"])][:n]
        say(f"Aya dataset (written by people): {len(out)} lessons")
        return out
    except Exception as e:
        say("⚠️ the Aya dataset couldn't be read (" + str(e)[:100] + "): going on without it.")
        return []


def mix(cfg, teacher, own, asked, lib, people):
    """All lessons in one file for training: the owner's own lessons count three times."""
    rows, seen = [], set()
    for l in own * 3 + asked + lib + teacher + people:
        key = (re.sub(r"\W+", " ", l["q"].lower()).strip(), l["a"][:80])
        if key in seen and l["from"] != "owner":
            continue
        seen.add(key)
        rows.append({"messages": [{"role": "user", "content": l["q"]}, {"role": "assistant", "content": l["a"]}], "from": l["from"]})
    random.Random(11).shuffle(rows)
    os.makedirs(BIG, exist_ok=True)
    with open(os.path.join(BIG, "train.jsonl"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    counts = {k: sum(1 for r in rows if r["from"] == k) for k in ["teacher", "owner", "owner-asked", "library", "aya"]}
    counts["owner"] = len(own)   # the owner's lessons (each one is in the file three times)
    counts["total"] = len(rows)
    say("Lessons for training: " + ", ".join(f"{k} {v}" for k, v in counts.items()))
    return counts


# ------------------------------------------------------------------ teaching small Gemma (in its own process)
def unsloth_install():
    """Unsloth and the libraries it needs, the way Unsloth's own Kaggle notebook installs them (read from it when possible)."""
    import importlib.util
    import urllib.request
    if importlib.util.find_spec("unsloth") and importlib.util.find_spec("unsloth_zoo"):
        return   # already there (looked up without importing it: importing it here would hold GPU memory)
    try:
        import numpy
        import PIL
        np_, pil_ = f"numpy=={numpy.__version__}", f"pillow=={PIL.__version__}"
    except Exception:
        np_, pil_ = "numpy", "pillow"
    line = '"torch>=2.8.0" "triton>=3.4.0" {_numpy} {_pil} torchvision bitsandbytes unsloth "unsloth_zoo>=2026.4.5" transformers==5.5.0 torchcodec timm'
    try:
        u = "https://raw.githubusercontent.com/unslothai/notebooks/main/kaggle/Gemma4_(E4B)-Text.ipynb".replace("(", "%28").replace(")", "%29")
        nb = json.loads(urllib.request.urlopen(u, timeout=30).read().decode("utf-8"))
        for c in nb["cells"]:
            src = "".join(c.get("source", []))
            m = re.search(r"uv pip install -q+ \\?\n?((?:.|\n)*?)(?:\n\S|\Z)", src)
            if c.get("cell_type") == "code" and m:
                pk = " ".join(m.group(1).replace("\\\n", " ").split())
                if pk and re.fullmatch(r"[\w\s\"'.,<>=!~\[\]{}-]+", pk):
                    line = pk
                break
    except Exception:
        pass
    line = line.replace("{_numpy}", np_).replace("{_pil}", pil_)
    line += " huggingface_hub datasets requests"
    say("Installing Unsloth and the other libraries (5 to 10 minutes)...")
    sh("pip install -q uv >/dev/null 2>&1", check=False)
    for cmd in [f"uv pip install -qqq {line}", f"uv pip install -qqq --system {line}", f"pip install -q {line}"]:
        if sh(cmd, check=False, quiet=True).returncode == 0:
            return
    stop("couldn't install Unsloth.")


def stage_train(cfg):
    """Runs as its own process (python habit_gemma.py --stage train ...): its GPU memory is free again when it ends."""
    unsloth_install()
    import torch
    from unsloth import FastModel
    from unsloth.chat_templates import get_chat_template, train_on_responses_only
    from datasets import Dataset
    from trl import SFTTrainer, SFTConfig
    rows = [json.loads(l) for l in open(os.path.join(BIG, "train.jsonl"), encoding="utf-8")]
    say(f"Teaching {STUDENT[cfg.size]} with {len(rows)} lessons...")
    model, tok = FastModel.from_pretrained(model_name=STUDENT[cfg.size], dtype=None, max_seq_length=1024, load_in_4bit=True, full_finetuning=False, token=cfg.hf)
    model = FastModel.get_peft_model(model, finetune_vision_layers=False, finetune_language_layers=True, finetune_attention_modules=True,
                                     finetune_mlp_modules=True, r=16, lora_alpha=16, lora_dropout=0, bias="none", random_state=3407)
    tok = get_chat_template(tok, chat_template="gemma-4")
    texts = [tok.apply_chat_template(r["messages"], tokenize=False, add_generation_prompt=False) for r in rows]
    ds = Dataset.from_dict({"text": texts})
    args = dict(dataset_text_field="text", per_device_train_batch_size=2, gradient_accumulation_steps=4, warmup_steps=10, learning_rate=1e-4,
                logging_steps=10, optim="adamw_8bit", weight_decay=0.001, lr_scheduler_type="linear", seed=3407, report_to="none",
                output_dir=os.path.join(BIG, "train"), save_strategy="no")
    if cfg.mode == "test":
        args["max_steps"] = 12
    else:
        args["num_train_epochs"] = 1
    trainer = SFTTrainer(model=model, tokenizer=tok, train_dataset=ds, eval_dataset=None, args=SFTConfig(**args))
    try:
        trainer = train_on_responses_only(trainer)
    except Exception:
        trainer = train_on_responses_only(trainer, instruction_part="<|turn>user\n", response_part="<|turn>model\n")
    try:   # one lesson as the model sees it: it learns only the answer part
        lab = trainer.train_dataset[0]["labels"]
        say("It learns this part of a lesson:", tok.decode([x for x in lab if x != -100])[:200].replace("\n", " "))
    except Exception:
        pass
    t_saved = time.time()
    st = trainer.train()
    say(f"Taught in {st.metrics.get('train_runtime', 0) / 60:.0f} minutes (loss {st.metrics.get('train_loss', 0):.2f}).")
    out = os.path.join(BIG, "gguf")
    shutil.rmtree(out, ignore_errors=True)
    say("Saving it as a GGUF file (10 to 20 minutes)...")
    model.save_pretrained_gguf(out, tok, quantization_method="f16")
    # where Unsloth put it (the folder, or next to it): the newest big .gguf that isn't the picture part (mmproj)
    found = set()
    for d in [out, BIG, os.getcwd(), WORK]:
        found |= set(glob.glob(os.path.join(d, "**", "*.gguf"), recursive=True))
    ggufs = [g for g in found if "mmproj" not in os.path.basename(g).lower() and os.path.getmtime(g) > t_saved]
    if not ggufs:
        stop("no GGUF file was made.")
    g = max(ggufs, key=os.path.getsize)
    keep_at = os.path.join(BIG, "habit-gemma-f16.gguf")
    shutil.move(g, keep_at)
    shutil.rmtree(out, ignore_errors=True)   # the merged model Unsloth made on the way (many GB)
    shutil.rmtree(os.path.expanduser("~/.cache/huggingface/hub/models--" + STUDENT[cfg.size].replace("/", "--")), ignore_errors=True)
    json.dump({"gguf": keep_at, "loss": st.metrics.get("train_loss"), "minutes": round(st.metrics.get("train_runtime", 0) / 60)}, open(os.path.join(BIG, "trained.json"), "w"))
    say(f"GGUF ✓ ({os.path.getsize(keep_at) // 2 ** 20} MB)")


def train(cfg):
    step("4. Teaching small Gemma " + cfg.size)
    ollama_down()   # all GPU memory for teaching
    args = [sys.executable, os.path.abspath(__file__), "--stage", "train", "--size", cfg.size, "--test" if cfg.mode == "test" else "--full"]
    env = dict(os.environ, HF_TOKEN=cfg.hf, CUDA_VISIBLE_DEVICES="0")
    if subprocess.run(args, env=env, cwd=BIG).returncode != 0:
        stop("teaching didn't finish (see the messages above).")
    return json.load(open(os.path.join(BIG, "trained.json")))


# ------------------------------------------------------------------ into Ollama
def modelfile_blocks(text):
    """The parts of a Modelfile (TEMPLATE, PARAMETER, RENDERER, PARSER…) without FROM, ADAPTER and LICENSE."""
    out, cur, open_ = [], [], False
    for line in text.splitlines():
        if not open_:
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            cur = [line]
            open_ = line.count('"""') == 1
        else:
            cur.append(line)
            if '"""' in line:
                open_ = False
        if not open_ and cur:
            word = cur[0].split(None, 1)[0].upper()
            if word not in ("FROM", "ADAPTER", "LICENSE"):
                out.append("\n".join(cur))
            cur = []
    return out


def llama_quantize():
    """llama.cpp's llama-quantize: the one Unsloth built, or built here (a few minutes). "" if it can't be had."""
    for g in glob.glob(os.path.join(BIG, "**", "llama-quantize"), recursive=True) + [shutil.which("llama-quantize") or ""]:
        if g and os.access(g, os.X_OK):
            return g
    src = os.path.join(BIG, "llama.cpp")
    if not os.path.isdir(src):
        sh(f"git clone --depth 1 https://github.com/ggml-org/llama.cpp {src}", check=False, quiet=True)
    sh(f"cmake -S {src} -B {src}/build -DGGML_CUDA=OFF -DLLAMA_CURL=OFF >/dev/null && cmake --build {src}/build --target llama-quantize -j 4 >/dev/null", check=False, quiet=True)
    g = os.path.join(src, "build", "bin", "llama-quantize")
    return g if os.access(g, os.X_OK) else ""


def ollama_model(cfg, trained):
    """habit-gemma in Ollama (Q4_K_M), with the plain Gemma's template; returns (its GGUF file, the Modelfile lines)."""
    step("5. habit-gemma into Ollama")
    ollama_up(None, parallel=4)
    plain = PLAIN[cfg.size]
    pull(plain)
    blocks = modelfile_blocks(ollama(f"show --modelfile {plain}").stdout)
    d = os.path.join(BIG, "model")
    os.makedirs(d, exist_ok=True)
    f16 = os.path.join(d, "habit-gemma-f16.gguf")
    if not os.path.exists(f16):
        shutil.move(trained["gguf"], f16)

    def make(src, quant, bl):
        """ollama create habit-gemma from [src] (a file in d), quantized to [quant] ("" = as it is); True if it answers."""
        open(os.path.join(d, "Modelfile"), "w").write(f"FROM ./{src}\n" + "\n".join(bl) + "\n")
        ollama("rm habit-gemma", check=False)
        r = sh(f"cd {d} && ollama create habit-gemma {'-q ' + quant if quant else ''} -f Modelfile", check=False, quiet=True)
        if r.returncode != 0:
            say(f"⚠️ Ollama: {((r.stderr or '') + (r.stdout or '')).strip()[-300:]}")
            return False
        try:
            return bool(chat("habit-gemma", "", "Say hello in one short sentence.", num_predict=40, timeout=300).strip())
        except Exception as e:
            say(f"⚠️ habit-gemma didn't answer: {str(e)[:200]}")
            return False

    # 1. Ollama makes it Q4_K_M itself (with the plain Gemma's template, or with the template inside the file)
    quant, used = "Q4_K_M", None
    for bl in (blocks, []):
        if make("habit-gemma-f16.gguf", "q4_K_M", bl):
            used = bl
            break
    # 2. llama.cpp makes it Q4_K_M, then Ollama takes it as it is
    if used is None:
        say("Making Q4_K_M with llama.cpp instead...")
        q = llama_quantize()
        q4 = os.path.join(d, "habit-gemma-q4.gguf")
        if q and sh(f"{q} {f16} {q4} Q4_K_M", check=False, quiet=True).returncode == 0:
            for bl in (blocks, []):
                if make("habit-gemma-q4.gguf", "", bl):
                    used = bl
                    break
    # 3. as it is (big: 16-bit; for a big server only)
    if used is None:
        say("⚠️ Couldn't make it smaller: keeping it in 16-bit (about 3 times bigger).")
        quant = "F16"
        for bl in (blocks, []):
            if make("habit-gemma-f16.gguf", "", bl):
                used = bl
                break
    if used is None:
        stop("Ollama couldn't make habit-gemma from the GGUF file.")
    # the file Ollama made (its blob) is what the server downloads
    show = ollama("show --modelfile habit-gemma").stdout
    m = re.search(r"^FROM (\S+)", show, re.M)
    blob = m.group(1) if m else ""
    if not os.path.exists(blob):
        stop("couldn't find habit-gemma's file in Ollama.")
    for f in glob.glob(os.path.join(d, "*.gguf")):
        os.remove(f)
    say(f"habit-gemma ✓ ({os.path.getsize(blob) // 2 ** 20} MB, {quant})")
    return blob, used, quant


# ------------------------------------------------------------------ the exam
def exam(cfg, model):
    ex = cfg.exam
    items = [i for i in ex["items"] if i.get("quick")] if cfg.mode == "test" else ex["items"]
    answers = {}

    def one(it):
        try:
            return it["id"], chat(model, ex["system"], it["q"], schema=ex["schema"], options=ex["options"], timeout=600)
        except Exception as e:
            return it["id"], ""

    with ThreadPoolExecutor(4) as pool:
        for i, a in pool.map(one, items):
            answers[i] = a
    summary, rows = cfg.check.score(ex, answers)
    json.dump({"summary": summary, "rows": rows}, open(os.path.join(WORK, f"exam-{model.replace(':', '-')}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return summary


# ------------------------------------------------------------------ keeping it
CARD = """---
license: apache-2.0
base_model: google/gemma-4-{size}-it
language: [te, en]
tags: [gguf, ollama, habit-ai]
---
# habit-gemma ({size})

Habit AI's own model: Google's Gemma 4 {size} (Apache 2.0), taught with LoRA (Unsloth) to answer like Habit, the
friendly puppy in the Habit AI app, in Telugu and English.

Taught with lessons written by Gemma 4 ({teacher}, Apache 2.0) and checked by it, the app owner's own lessons and library
notes, and the Aya dataset (CohereLabs/aya_dataset, Apache 2.0, written by people). No answers from Claude or any other
company's AI service were used. Exam (brain/exam.json, {n} questions): habit-gemma {hs}%, plain Gemma {bs}%.

Licence: Apache 2.0, like Gemma 4. Made {made}.
"""


def keep(cfg, blob, blocks, quant, report):
    step("7. Keeping habit-gemma in your Hugging Face")
    api = hf_api(cfg)
    repo = f"{cfg.user}/habit-gemma" + ("-test" if cfg.mode == "test" else "")
    name = f"habit-gemma-{cfg.size.lower()}-{quant.lower()}.gguf"
    report["gguf"] = {"file": name, "bytes": os.path.getsize(blob), "quant": quant}
    report["repo"] = repo
    d = os.path.join(BIG, "up")
    os.makedirs(d, exist_ok=True)
    open(os.path.join(d, "Modelfile"), "w").write("FROM ./habit-gemma.gguf\n" + "\n".join(blocks) + "\n")
    json.dump(report, open(os.path.join(d, "report.json"), "w"), indent=1)
    open(os.path.join(d, "README.md"), "w").write(CARD.format(size=cfg.size, teacher=cfg.teacher, n=report["exam"]["questions"], made=report["made"][:10],
                                                              hs=report["exam"]["habit"]["score"], bs=report["exam"]["base"]["score"]))
    api.create_repo(repo, private=True, exist_ok=True)
    say(f"Uploading {os.path.getsize(blob) // 2 ** 20} MB to {repo} (private)...")
    api.upload_file(path_or_fileobj=blob, path_in_repo=name, repo_id=repo)
    for f in ["Modelfile", "README.md", "report.json"]:   # report.json last: the server looks for it
        api.upload_file(path_or_fileobj=os.path.join(d, f), path_in_repo=f, repo_id=repo)
    try:
        for f in api.list_repo_files(repo):
            if f.endswith(".gguf") and f != name:
                api.delete_file(f, repo_id=repo)
        api.super_squash_history(repo_id=repo)   # older versions take no space
    except Exception:
        pass
    say(f"✓ https://huggingface.co/{repo}")
    return repo


def main():
    cfg = settings()
    if cfg.stage == "train":
        cfg.hf = secret("HF_TOKEN")
        stage_train(cfg)
        return
    print(f"habit-gemma · {cfg.mode} run · Gemma 4 {cfg.size} · teacher {cfg.teacher}", flush=True)
    gpus(cfg)
    unsloth_install()    # first, so every later step uses the same versions of the libraries
    ollama_install()
    machine(cfg)
    files(cfg)
    ollama_up(None, parallel=4)
    data = owner(cfg)
    teacher = teacher_lessons(cfg)
    step("3. The owner's lessons, the library and Aya")
    own, asked = owner_lessons(cfg, data)
    lib = library_lessons(cfg, data)
    people = aya(cfg)
    counts = mix(cfg, teacher, own, asked, lib, people)
    ollama("rm " + cfg.teacher, check=False)   # room on the disk
    trained = train(cfg)
    blob, blocks, quant = ollama_model(cfg, trained)
    step("6. The exam")
    habit = exam(cfg, "habit-gemma")
    say(f"habit-gemma: {habit['score']}% right, {habit['format']}% in the right shape")
    base = exam(cfg, PLAIN[cfg.size])
    say(f"plain Gemma {cfg.size}: {base['score']}% right, {base['format']}% in the right shape")
    for c, (ok, n) in habit["byCat"].items():
        b = base["byCat"].get(c, [0, n])
        print(f"   {c:<10} habit-gemma {ok}/{n}   plain {b[0]}/{b[1]}", flush=True)
    report = {"made": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "mode": cfg.mode, "size": cfg.size, "student": STUDENT[cfg.size],
              "teacher": cfg.teacher, "lessons": counts, "trained": {k: trained.get(k) for k in ("loss", "minutes")},
              "exam": {"questions": habit["n"], "habit": habit, "base": base}, "hours": round(hours(), 1),
              "rule": "No answers from Claude or any other company's AI service were used to teach this model."}
    json.dump(report, open(os.path.join(WORK, "report.json"), "w"), indent=1)
    if cfg.mode == "full" and habit["score"] + 3 < base["score"]:
        print(f"\n⚠️ habit-gemma did worse than the plain Gemma ({habit['score']}% against {base['score']}%), so it was NOT kept in Hugging Face.\n"
              "Add more lessons on the Teach page (or check the exam answers in exam-habit-gemma.json), then run again.", flush=True)
        return
    repo = keep(cfg, blob, blocks, quant, report)
    print("\n" + "=" * 72, flush=True)
    if cfg.mode == "test":
        print(f"✅ THE TEST WORKED ({hours():.1f} hours). Now the real one: change --test to --full in the cell, then Save Version › Save & Run All.", flush=True)
    else:
        print(f"✅ habit-gemma IS READY ({hours():.1f} hours): exam {habit['score']}% (plain Gemma {base['score']}%).\n"
              "Now on the Teach page: '🎓 Teach Gemma' › 'Put habit-gemma on this server'.", flush=True)
    print("=" * 72, flush=True)


if __name__ == "__main__":
    main()
