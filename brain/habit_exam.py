"""Habit's exam for its own AI model (the same rules as check() in server/gemma.js: change both together).

    python3 habit_exam.py exam.json answers.json      # answers.json: {"question id": "the model's raw answer", ...}

Each answer is checked with fixed rules: the right language, not too long, words a right answer uses (for example
"don't share" for an OTP question, 1930 for cyber fraud), and words it must never use. The questions are only for
testing a model: they are never lessons.
"""
import json
import re
import sys

NEVER = ["as an ai language model", "as a language model", "large language model", "openai", "chatgpt"]


def check(item, raw, emotions):
    text = ("" if raw is None else str(raw)).strip()
    obj, parsed, direct = None, False, False
    try:
        obj = json.loads(text)
        parsed = direct = True
    except Exception:
        a, b = text.find("{"), text.rfind("}")
        if a >= 0 and b > a:
            try:
                obj = json.loads(text[a:b + 1])
                parsed = True
            except Exception:
                obj = None
    is_obj = parsed and isinstance(obj, dict)
    ok = is_obj and isinstance(obj.get("reply"), str) and obj["reply"].strip() != ""
    reply = obj["reply"].strip() if ok else ("" if is_obj else text)
    fmt = bool(direct and ok and obj.get("emotion") in (emotions or []))
    w = item.get("want") or {}
    fails = []
    low = reply.lower()

    def has(p):
        if p.startswith("re:"):
            return re.search(p[3:], reply, re.I) is not None
        return p.lower() in low

    def label(p):   # "re:(?<![0-9])108(?![0-9])" → "108"
        return re.sub(r"\(\?<?[!=][^)]*\)", "", re.sub(r"^re:", "", p))

    if not reply:
        fails.append("no answer")
    te = len(re.findall(r"[ఀ-౿]", reply))
    en = len(re.findall(r"[A-Za-z]", reply))
    share = te / (te + en) if te + en else 0
    if w.get("script") == "te" and share < 0.5:
        fails.append("not in Telugu")
    if w.get("script") == "en" and share > 0.1:
        fails.append("not in English")
    words = len(reply.split())
    if w.get("maxWords") and words > w["maxWords"]:
        fails.append("too long (%d words)" % words)
    if w.get("minWords") and words < w["minWords"]:
        fails.append("too short (%d words)" % words)
    for group in w.get("all") or []:
        if not any(has(p) for p in group):
            fails.append("missing: " + label(group[0]))
    if w.get("any") and not any(has(p) for p in w["any"]):
        fails.append("missing: " + label(w["any"][0]))
    for p in list(w.get("none") or []) + NEVER:
        if has(p):
            fails.append("should not say: " + label(p))
    return {"ok": len(fails) == 0, "format": fmt, "fails": fails, "reply": reply}


def score(exam, answers):
    """answers: {id: raw answer}. Returns the summary and one result per question."""
    emotions = exam["schema"]["properties"]["emotion"]["enum"]
    rows, by = [], {}
    for it in exam["items"]:
        if it["id"] not in answers:
            continue
        c = check(it, answers[it["id"]], emotions)
        rows.append(dict(id=it["id"], cat=it["cat"], q=it["q"], **c))
        b = by.setdefault(it["cat"], [0, 0])
        b[1] += 1
        b[0] += 1 if c["ok"] else 0
    n = len(rows)
    pct = lambda k: int(sum(r[k] for r in rows) * 100 / n + 0.5) if n else 0   # rounded like JavaScript's Math.round
    return {"n": n, "score": pct("ok"), "format": pct("format"), "byCat": by}, rows


if __name__ == "__main__":
    ex = json.load(open(sys.argv[1], encoding="utf-8"))
    ans = json.load(open(sys.argv[2], encoding="utf-8"))
    summary, rows = score(ex, ans)
    print(json.dumps(summary, ensure_ascii=False, indent=1))
