// Habit Studio: our own explainer videos (whiteboard and motion-graphics styles), like an AI video team, fully free:
//  1. Researcher: picks the day's topic from a list of useful things people ask (schemes, fraud safety, health, farming,
//     government services, money, phone safety, children's learning) and the festival of the day (trends.js).
//  2. Writer: Claude (or Habit's own brain when Claude rests) writes a 30-45 second Telugu explainer as scenes:
//     a title, points, numbered steps, a warning, a helpline number, the end card. Careful: no amounts, dates or
//     rules that change, unless sure; it ends with where to check.
//  3. Voice: our free Telugu voice (freevoice.js, Piper) reads each scene.
//  4. Animator + editor: toon/explainer.py draws it (the Habit puppy presents), the words come in with small
//     animations, the music goes under the voice.
//  5. Approval: each new video WAITS on the Teach page. The owner taps ✓ Post or ✕ Drop. Nothing goes out by itself.
//  6. Upload: a posted video goes to Media (its topic's section) with the "🤖 AI-made" label.
// STUDIO_DAILY (default 2) made by itself a day, between 7 am and 8 pm; the owner can also ask for any topic now.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { execFile } = require("child_process");

module.exports = function makeStudio({ DATA_DIR, learn, MODEL, ownBrain, freeVoice, voice, media, trends, youtube }) {
  const PY = process.env.PYTHON || "python3";
  const SCRIPT = path.join(__dirname, "toon", "explainer.py");
  const OUT = path.join(DATA_DIR, "cartoons");                 // served as /media/cartoon/<id>.mp4 (cartoon.js)
  fs.mkdirSync(OUT, { recursive: true });
  const FILE = path.join(DATA_DIR, "studio.json");
  const DAILY = () => Math.max(0, Math.min(10, Number(process.env.STUDIO_DAILY ?? 2)));
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);
  const hourIST = () => new Date(Date.now() + 5.5 * 3600e3).getUTCHours();
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
  let db = { next: 1, items: [], day: "", made: 0, used: [] };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  const save = () => { try { fs.writeFileSync(FILE, JSON.stringify(db)); } catch (e) { } };
  let busy = false, lastError = "";

  // the researcher's list: useful, lasting topics (the festival of the day comes first)
  const TOPICS = [
    ["OTP, KYC మోసాల నుంచి ఎలా కాపాడుకోవాలి", "facts", "warn"],
    ["డబ్బు పోతే 1930 సైబర్ హెల్ప్‌లైన్: ఏం చేయాలి", "facts", "phone"],
    ["ఆధార్‌కు మొబైల్ నంబర్ లింక్: ఎందుకు, ఎలా", "facts", "doc"],
    ["రేషన్ కార్డు కోసం దరఖాస్తు: కావాల్సిన పత్రాలు", "facts", "doc"],
    ["పెన్షన్ దరఖాస్తు: సచివాలయంలో ఏం తీసుకెళ్లాలి", "facts", "doc"],
    ["కుల, ఆదాయ ధృవీకరణ పత్రాలు: ఎక్కడ, ఎలా", "facts", "doc"],
    ["భూమి రికార్డులు ఆన్‌లైన్‌లో చూడటం: మీకోసం సులభ దారి", "facts", "doc"],
    ["డెంగ్యూ జ్వరం: జాగ్రత్తలు, డాక్టర్ దగ్గరకు ఎప్పుడు", "health", "heart"],
    ["వడదెబ్బ నుంచి కాపాడుకోవడం", "health", "heart"],
    ["షుగర్ ఉన్నవాళ్ళకు రోజువారీ అలవాట్లు", "health", "heart"],
    ["పిల్లలకు టీకాలు: ఎందుకు ముఖ్యం", "health", "heart"],
    ["108, 112, 100, 101: ఏ నంబర్ ఎప్పుడు", "facts", "phone"],
    ["వరి పంటలో పురుగుల నివారణ: మొదటి జాగ్రత్తలు", "farming", "leaf"],
    ["మట్టి పరీక్ష: రైతుకు ఎందుకు లాభం", "farming", "leaf"],
    ["రైతు భరోసా కేంద్రంలో దొరికే సేవలు", "farming", "leaf"],
    ["ఫోన్ పోతే వెంటనే చేయాల్సిన 4 పనులు", "facts", "phone"],
    ["UPI పేమెంట్స్: మోసపోకుండా 4 జాగ్రత్తలు", "facts", "rupee"],
    ["ఇంటి బడ్జెట్: నెలకు కొంచెం పొదుపు ఎలా", "facts", "rupee"],
    ["పిల్లల చదువు: రోజూ 15 నిమిషాల అలవాటు", "kids", "star"],
    ["పిల్లలకు మొబైల్ సేఫ్టీ: తల్లిదండ్రులకు టిప్స్", "kids", "star"],
    ["నీటిని పొదుపుగా వాడటం: ఇంట్లో 4 చిన్న మార్పులు", "facts", "leaf"],
    ["రోడ్డు భద్రత: హెల్మెట్, సీట్ బెల్ట్ ఎందుకు", "facts", "warn"],
  ];
  /** What people liked: per topic, the likes its posted videos got (from Media). */
  function liked() {
    const by = {};
    for (const it of db.items) if (it.status === "posted" && it.itemId && media.likesOf) {
      const n = media.likesOf(it.itemId);
      if (n < 0) continue;
      const k = it.topic;
      by[k] = by[k] || { likes: 0, n: 0, cat: it.cat };
      by[k].likes += n; by[k].n++;
    }
    return by;
  }
  function pickTopic() {
    const fest = trends && trends.festivalNow && trends.festivalNow();
    if (fest && !db.used.includes("fest:" + fest.f.key + ":" + today())) return { topic: `${fest.f.te}: పండుగ విశేషాలు, ఆచారాలు`, cat: "devotional", icon: "star", key: "fest:" + fest.f.key + ":" + today() };
    const fresh = TOPICS.filter(t => !db.used.slice(-TOPICS.length + 2).includes(t[0]));
    const pool = fresh.length ? fresh : TOPICS;
    // learning from people: a section whose videos got more likes is picked more often (never less than a fair chance)
    const by = liked(), catLikes = {};
    for (const v of Object.values(by)) { catLikes[v.cat] = catLikes[v.cat] || [0, 0]; catLikes[v.cat][0] += v.likes; catLikes[v.cat][1] += v.n; }
    const weight = t => { const c = catLikes[t[1]]; return 1 + (c ? Math.min(4, c[0] / Math.max(1, c[1])) : 0.5); };
    const total = pool.reduce((a, t) => a + weight(t), 0);
    let x = Math.random() * total, t = pool[0];
    for (const p of pool) { x -= weight(p); if (x <= 0) { t = p; break; } }
    return { topic: t[0], cat: t[1], icon: t[2], key: t[0] };
  }

  // ---------- the writer ----------
  const SYSTEM = `You write short explainer videos for "Habit Studio", the video channel of Habit AI, a Telugu helper app for ordinary people in Andhra Pradesh and Telangana. The video is 30 to 45 seconds, vertical, with Habit's cartoon puppy presenting.
Write in simple, natural spoken Telugu (English words that people really use, like OTP, UPI, Aadhaar, may stay). Be correct and careful: only well-known, lasting facts and safe, general guidance. Do NOT give money amounts, last dates, eligibility limits or rule numbers that change, unless you are completely sure; instead say where to check (the village/ward Sachivalayam, the official website, the doctor, the agriculture officer). No politics, no party or leader names, no brand promotion, nothing scary for children, no medical doses.
Scenes (6 to 8), in this order: one "title", then 4 to 6 of "point" / "steps" / "warn" / "number" / "quiz" / "react", then one "end".
MAKE IT GRAB PEOPLE, like the best short videos: the title is a HOOK that stops the scroll in 2 seconds (a surprising question, "90% మందికి ఇది తెలీదు", a common mistake); short, fast scenes; at least one react moment and, when it fits, a quiz; the voice talks like a friendly young anchor, with feeling, not like a news reader. A trend from today may be used as the hook only when it truly connects to the topic (no celebrity names, nothing copied).
- title: text = the question or topic (at most 8 words); sub = a short promise ("2 నిమిషాల్లో తెలుసుకోండి"); icon.
- point: text = one key idea (at most 12 words); say = what the puppy says for it (one or two short sentences); icon.
- steps: text = a short heading (at most 5 words); items = 2 to 4 steps (each at most 7 words); say.
- warn: text = the warning (at most 12 words); say.
- number: big = a real, well-known helpline or number (like 1930, 108, 112, 100, 101, 181, 1100, 14567) ONLY if it truly fits; text = what it is for; say.
- react (one or two, the fun part, like a meme moment): the puppy's big reaction to a surprising fact, a fraud trick or a common mistake: text = 1 to 3 words ("అయ్యో!", "నిజమా?!", "అస్సలు కాదు!", "వావ్!"); sub = one short funny line about it (kind, never mocking anyone); say = that line read aloud.
- quiz (optional, at most one, people love it): text = a short question (at most 10 words); items = 3 short options; answer = the number of the right option (1, 2 or 3); say = the question read aloud and then the answer with one line why.
- end: text = "హ్యాబిట్ AI లో అడగండి" or a short call to action; sub = "🤖 AI-made · AI తో తయారైన వీడియో".
Icons: warn, phone, doc, bulb, heart, leaf, rupee, star, check.
Every "say" is read aloud by a Telugu voice: plain words, no symbols, numbers written so they read well.
Reply with only JSON: {"title":"<the video title in Telugu, at most 10 words>","style":"board" or "neon","lines":["<3 to 5 short lines for the post's text>"],"scenes":[{"kind":"title","text":"...","sub":"...","icon":"..."}, ...]}`;
  const SCHEMA = { type: "object", properties: { title: { type: "string" }, style: { type: "string" }, lines: { type: "array", items: { type: "string" } },
    scenes: { type: "array", items: { type: "object", properties: { kind: { type: "string" }, text: { type: "string" }, sub: { type: "string" }, say: { type: "string" },
      items: { type: "array", items: { type: "string" } }, big: { type: "string" }, icon: { type: "string" } }, required: ["kind"] } } }, required: ["title", "scenes"] };

  function parse(t) {
    try { return JSON.parse(t); } catch (e) { }
    const a = String(t).indexOf("{"), b = String(t).lastIndexOf("}");
    if (a >= 0 && b > a) try { return JSON.parse(String(t).slice(a, b + 1)); } catch (e) { }
    return null;
  }
  function clean(j, icon) {
    const KINDS = ["title", "point", "steps", "warn", "number", "quiz", "react", "end"], ICONS = ["warn", "phone", "doc", "bulb", "heart", "leaf", "rupee", "star", "check"];
    const scenes = (Array.isArray(j && j.scenes) ? j.scenes : []).filter(s => s && KINDS.includes(s.kind)).slice(0, 8).map(s => ({
      kind: s.kind, text: str(s.text, 120), sub: str(s.sub, 80), say: str(s.say, 260), big: str(s.big, 8).replace(/[^\d+]/g, ""),
      items: (Array.isArray(s.items) ? s.items : []).slice(0, 4).map(x => str(x, 60)).filter(Boolean), icon: ICONS.includes(s.icon) ? s.icon : "",
      answer: Math.max(1, Math.min(3, Number(s.answer) || 1)) }))
      .filter(s => s.kind !== "number" || /^\d{3,5}$/.test(s.big))
      .filter(s => s.kind !== "quiz" || s.items.length === 3);
    if (!scenes.length || scenes[0].kind !== "title") scenes.unshift({ kind: "title", text: str(j && j.title, 80), sub: "2 నిమిషాల్లో తెలుసుకోండి", icon });
    if (scenes[0].kind === "title" && !scenes[0].icon) scenes[0].icon = icon;
    if (scenes[scenes.length - 1].kind !== "end") scenes.push({ kind: "end", text: "హ్యాబిట్ AI లో అడగండి", sub: "🤖 AI-made · AI తో తయారైన వీడియో" });
    scenes[scenes.length - 1].sub = "🤖 AI-made · AI తో తయారైన వీడియో";
    if (scenes.length < 4) return null;
    for (const x of scenes) if (x.kind === "react") x.text = x.text.split(/\s+/).slice(0, 3).join(" ");
    return { title: str(j.title, 90) || scenes[0].text, style: j.style === "neon" ? "neon" : "board", lines: (Array.isArray(j.lines) ? j.lines : []).slice(0, 5).map(x => str(x, 160)).filter(Boolean), scenes };
  }
  /** What is trending today (YouTube in India, Telugu first; Google searches), for a hook. */
  function trendingNow() {
    const out = [];
    try { const yt = typeof youtube === "function" ? youtube() : youtube; for (const v of ((yt && yt._db().trending || {}).list || []).filter(v => v.te).slice(0, 8)) out.push(v.title); } catch (e) { }
    try { for (const t of (trends && trends.themes ? trends.themes() : []).slice(0, 4)) out.push(t.te || t.en); } catch (e) { }
    return out.filter(Boolean).slice(0, 10);
  }
  async function write(topic, icon) {
    const tr = trendingNow();
    const prompt = `Topic: ${topic}\n${tr.length ? "Trending today (for a hook only if it truly connects): " + tr.join(" | ") + "\n" : ""}Make the explainer video.`;
    if (learn && !learn.claudeOut()) {
      try {
        const out = await learn.callClaude({ system: SYSTEM, messages: [{ role: "user", content: prompt }], maxTokens: 2000, search: false, model: MODEL });
        const j = clean(parse(out.text), icon);
        if (j) return Object.assign(j, { by: "claude" });
      } catch (e) { lastError = "writer: " + String(e.message || e).slice(0, 120); }
    }
    if (ownBrain && ownBrain.writeJson) {
      const j = clean(await ownBrain.writeJson({ system: SYSTEM, prompt, schema: SCHEMA, maxTokens: 1800, timeoutMs: 280000 }), icon);
      if (j) return Object.assign(j, { by: "own" });
    }
    throw new Error("no brain could write the video now");
  }

  // ---------- voice + animation ----------
  const run = (cmd, args, timeout) => new Promise((ok, bad) => execFile(cmd, args, { timeout, maxBuffer: 4 * 1024 * 1024 }, (e, out, err) => e ? bad(Object.assign(e, { stderr: err })) : ok(out)));
  async function render(id, s) {
    const tmp = path.join(OUT, id + "-tmp");
    fs.mkdirSync(tmp, { recursive: true });
    try {
      const google = !!(voice && voice.enabled);                  // Google's natural Telugu voice when its key is set
      const voiced = google || (freeVoice && (freeVoice.ok() || await freeVoice.ready().catch(() => false)));
      const scenes = [];
      for (const [i, sc] of s.scenes.entries()) {
        const n = Object.assign({}, sc);
        const words = sc.say || (sc.kind === "title" ? sc.text : sc.kind === "end" ? sc.text : "");
        if (voiced && words) {
          const f = path.join(tmp, `v${i}.wav`);
          if (google) {
            try {
              const mp3 = (await voice.speak(words, "puppy", sc.kind === "react" ? "surprised" : sc.kind === "warn" ? "calm" : "excited")).file;
              await run(process.env.FFMPEG || "ffmpeg", ["-y", "-v", "error", "-i", mp3, "-ac", "1", "-ar", "24000", "-sample_fmt", "s16", f], 60e3);
              n.voice = f;
            } catch (e) { }
          }
          if (!n.voice && freeVoice) { try { await freeVoice.speak(words, "bigd", f); n.voice = f; } catch (e) { } }
        }
        scenes.push(n);
      }
      const music = path.join(__dirname, "assets", s.cat === "devotional" ? "music-devotional.m4a" : s.cat === "kids" ? "music-kids.m4a" : s.scenes.some(x => x.kind === "warn") ? "music-serious.m4a" : "music-inspiring.m4a");
      const plan = { style: s.style, title: s.title, brand: "Habit Studio · హ్యాబిట్", scenes, out: path.join(OUT, id + ".mp4"), poster: path.join(OUT, id + ".jpg"), tmp,
        fonts: path.join(__dirname, "fonts"), font: "Hind Guntur", ffmpeg: process.env.FFMPEG || "ffmpeg", music: fs.existsSync(music) ? music : "", seed: crypto.randomInt(1, 60000) };
      fs.writeFileSync(path.join(tmp, "plan.json"), JSON.stringify(plan));
      const out = await run("nice", ["-n", "10", PY, SCRIPT, path.join(tmp, "plan.json")], 10 * 60e3);
      let dur = 30;
      try { dur = JSON.parse(String(out).trim().split("\n").pop()).dur; } catch (e) { }
      if (!fs.existsSync(plan.out)) throw new Error("no video came out");
      return { dur: Math.round(dur), voiced: !!voiced, voiceKind: google ? "google" : voiced ? "free" : "" };
    } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
  }

  /** Makes one video (a topic from the owner, or the researcher's pick). It then waits for the owner's ✓. */
  async function make(topicIn) {
    if (busy) throw new Error("Studio is busy making a video. Try again in a few minutes.");
    busy = true;
    const pick = topicIn ? { topic: str(topicIn, 120), cat: "facts", icon: "bulb", key: "ask:" + str(topicIn, 60) } : pickTopic();
    const id = "s" + (db.next++) + "_" + crypto.randomBytes(3).toString("hex");
    const item = { id, topic: pick.topic, cat: pick.cat, status: "making", at: Date.now() };
    db.items.push(item); db.items = db.items.slice(-200); save();
    try {
      const s = await write(pick.topic, pick.icon);
      s.cat = pick.cat;
      const r = await render(id, s);
      Object.assign(item, { title: s.title, lines: s.lines, scenes: s.scenes.length, by: s.by, style: s.style, dur: r.dur, voiced: r.voiced, voiceKind: r.voiceKind, status: "waiting", doneAt: Date.now() });
      if (!topicIn) { db.used.push(pick.key); db.used = db.used.slice(-60); }
      lastError = "";
      console.log(`Studio: made ${id} "${s.title}" (${s.by}), waiting for the owner`);
    } catch (e) {
      item.status = "failed"; item.error = String((e.stderr && String(e.stderr).trim().split("\n").pop()) || e.message || e).slice(0, 200);
      lastError = item.error;
      console.error("Studio failed:", item.error);
    } finally { busy = false; save(); }
    return item;
  }

  /** The owner's ✓: posted in Media with the AI-made label. */
  function approve(id) {
    const it = db.items.find(x => x.id === id);
    if (!it || it.status !== "waiting") throw new Error("That video isn't waiting.");
    const body = (it.lines && it.lines.length ? it.lines.join("\n") : it.topic) + "\n\n🤖 AI-made video · AI తో తయారైన వీడియో · Habit Studio";
    const m = media.addItem({ cat: it.cat || "facts", title: "🎬 " + (it.title || it.topic), body, emoji: "🎬", mood: "useful", issue: "other",
      kind: "cartoon", writer: it.by || "claude", by: "auto", img: "", key: "studio:" + it.id,
      video: { url: `/media/cartoon/${it.id}.mp4`, poster: `/media/cartoon/${it.id}.jpg`, dur: it.dur || 30, w: 720, h: 1280 } });
    if (!m) throw new Error("Media didn't take it (maybe already posted).");
    it.status = "posted"; it.itemId = m.id; it.postedAt = Date.now(); save();
    return it;
  }
  function drop(id) {
    const it = db.items.find(x => x.id === id);
    if (!it || !["waiting", "failed"].includes(it.status)) throw new Error("That video can't be dropped.");
    it.status = "dropped";
    for (const ext of ["mp4", "jpg"]) fs.rmSync(path.join(OUT, `${it.id}.${ext}`), { force: true });
    save();
    return it;
  }

  // by itself: a few a day, in the daytime, only when nothing is waiting too long (the owner sees them first)
  async function tick() {
    if (db.day !== today()) { db.day = today(); db.made = 0; save(); }
    const h = hourIST();
    if (busy || h < 7 || h >= 20 || db.made >= DAILY()) return;
    if (db.items.filter(x => x.status === "waiting").length >= 6) return;       // the owner hasn't looked yet: don't pile up
    db.made++; save();
    await make().catch(() => { });
  }
  setTimeout(() => { tick().catch(() => { }); setInterval(() => tick().catch(() => { }), 40 * 60e3).unref?.(); }, 5 * 60e3).unref?.();

  function list() {
    return { daily: DAILY(), madeToday: db.made, busy, lastError, voice: voice && voice.enabled ? "Google natural Telugu voice" : freeVoice ? "free voice: " + freeVoice.state() : "none",
      items: db.items.slice(-30).reverse().map(x => ({ id: x.id, topic: x.topic, title: x.title || "", status: x.status, cat: x.cat, by: x.by || "", dur: x.dur || 0,
        voiced: !!x.voiced, error: x.error || "", at: x.at, likes: x.status === "posted" && x.itemId && media.likesOf ? Math.max(0, media.likesOf(x.itemId)) : 0, video: ["waiting", "posted"].includes(x.status) ? `/media/cartoon/${x.id}.mp4` : "", poster: ["waiting", "posted"].includes(x.status) ? `/media/cartoon/${x.id}.jpg` : "" })) };
  }
  return { make, approve, drop, list, tick };
};
