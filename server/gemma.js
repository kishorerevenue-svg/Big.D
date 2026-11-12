// Habit's own model, "habit-gemma": Google's open Gemma 4 model (Apache 2.0), taught to talk and help like Habit.
//
// Claude's words never teach it. Anthropic's terms don't allow using Claude's answers to train another AI model, so
// nothing Claude wrote or read goes into its lessons: not Claude's chat answers, not the answers learned from Claude,
// not the files or templates Claude made, not the PDFs or photos Claude read for the library.
// Its teachers:
//   1. Big Gemma (Gemma 4 26B, also Apache 2.0) writes lessons on a free Kaggle GPU (brain/habit-gemma.ipynb);
//   2. the owner: lessons written on the Teach page, and the library's typed notes, text files and web pages;
//   3. people: 👍 / 👎 on answers from Habit's own brains. The owner turns corrections and good answers into lessons.
// The exam (brain/exam.json) checks a model with fixed rules, here and on Kaggle. Its questions are never lessons.
// The trained model comes back from the owner's private Hugging Face repo (brain-model.sh).
const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { spawn } = require("child_process");

/** The exam's rules for one answer. The same rules are in brain/habit_exam.py (Kaggle): change both together. */
const NEVER = ["as an ai language model", "as a language model", "large language model", "openai", "chatgpt"];
function check(item, raw, emotions) {
  const text = String(raw == null ? "" : raw).trim();
  let obj = null, parsed = false, direct = false;
  try { obj = JSON.parse(text); parsed = direct = true; } catch (e) {
    const a = text.indexOf("{"), b = text.lastIndexOf("}");
    if (a >= 0 && b > a) { try { obj = JSON.parse(text.slice(a, b + 1)); parsed = true; } catch (e2) { obj = null; } }
  }
  const isObj = parsed && obj !== null && typeof obj === "object" && !Array.isArray(obj);
  const ok = isObj && typeof obj.reply === "string" && obj.reply.trim() !== "";
  const reply = ok ? obj.reply.trim() : isObj ? "" : text;
  const format = direct && ok && (emotions || []).includes(obj.emotion);
  const w = item.want || {}, fails = [];
  const low = reply.toLowerCase();
  const has = p => p.startsWith("re:") ? new RegExp(p.slice(3), "iu").test(reply) : low.includes(p.toLowerCase());
  if (!reply) fails.push("no answer");
  const te = (reply.match(/[ఀ-౿]/g) || []).length, en = (reply.match(/[A-Za-z]/g) || []).length;
  const share = te + en ? te / (te + en) : 0;
  if (w.script === "te" && share < 0.5) fails.push("not in Telugu");
  if (w.script === "en" && share > 0.1) fails.push("not in English");
  const words = reply.split(/\s+/).filter(Boolean).length;
  if (w.maxWords && words > w.maxWords) fails.push(`too long (${words} words)`);
  if (w.minWords && words < w.minWords) fails.push(`too short (${words} words)`);
  for (const group of w.all || []) if (!group.some(has)) fails.push("missing: " + group[0]);
  if (w.any && w.any.length && !w.any.some(has)) fails.push("missing: " + w.any[0]);
  for (const p of [...(w.none || []), ...NEVER]) if (has(p)) fails.push("should not say: " + p.replace(/^re:/, ""));
  return { ok: fails.length === 0, format, fails, reply };
}

module.exports = function makeGemma({ DATA_DIR, SERVER_DIR, ownBrain, learn, HF_BASE }) {
  const HF = HF_BASE || process.env.HF_BASE || "https://huggingface.co";
  const file = n => path.join(DATA_DIR, n);
  const load = (n, d) => { try { return JSON.parse(fs.readFileSync(file(n), "utf8")); } catch (e) { return d; } };
  const save = (n, v) => { try { const t = file(n) + ".tmp"; fs.writeFileSync(t, JSON.stringify(v)); fs.renameSync(t, file(n)); } catch (e) { console.error("Gemma: couldn't save " + n + ": " + e.message); } };
  const newId = () => Date.now().toString(36) + crypto.randomBytes(3).toString("hex");
  const day = () => new Date(Date.now() + 5.5 * 3600000).toISOString().slice(0, 10);
  const teOf = s => { const t = (String(s).match(/[ఀ-౿]/g) || []).length, e = (String(s).match(/[A-Za-z]/g) || []).length; return t && t >= e * 0.3 ? "te" : "en"; };
  /** Takes out what could be personal: e-mail addresses, phone, Aadhaar, card and account numbers (6 or more digits), PAN. */
  const scrub = s => String(s == null ? "" : s)
    .replace(/[\w.+-]+@[\w-]+\.[\w.-]+/g, "[email]")
    .replace(/\+?\d[\d -]{6,}\d/g, m => (m.replace(/\D/g, "").length >= 9 ? "[number]" : m))
    .replace(/\d{6,}/g, "[number]")
    .replace(/[A-Z]{5}\d{4}[A-Z]/g, "[PAN]")
    .trim();

  // ---------- which answers came from Habit's own brains ----------
  // Each answer from the own brain (Gemma on this server) or the self brain gets an id ("rid", from its words), so the
  // phone can show 👍 / 👎 under it, and a rating can only be for words this server really answered.
  // "clean": Gemma wrote it, with no answer learned from Claude and no document Claude read among its facts, and every
  // earlier answer in the chat was Gemma's too. Only clean answers may become lessons as they are.
  const SEEN_MAX = 6000, SEEN_DAYS = 7;
  let seen = load("gemma-seen.json", {});        // rid -> [brain "own"|"self", clean 0|1, model, time]
  let seenDirty = false;
  const rid = text => crypto.createHash("sha256").update(String(text || "").trim()).digest("hex").slice(0, 16);
  function flushSeen() {
    if (!seenDirty) return;
    seenDirty = false;
    const old = Date.now() - SEEN_DAYS * 86400000;
    const keep = Object.entries(seen).filter(([, r]) => r[3] > old).sort((a, b) => b[1][3] - a[1][3]).slice(0, SEEN_MAX);
    seen = Object.fromEntries(keep);
    save("gemma-seen.json", seen);
  }
  setInterval(flushSeen, 60000).unref();
  const textOf = c => typeof c === "string" ? c : Array.isArray(c) ? String((c.find(x => x && x.type === "text") || {}).text || "") : "";

  /** An answer from the pet route: an answer from Habit's own brains (not about a photo) gets its rid. */
  function mark(out, messages) {
    if (!out || !out.own || !out.reply || !Array.isArray(messages) || !messages.length) { if (out) delete out.clean; return out; }
    const last = messages[messages.length - 1];
    if (Array.isArray(last.content)) { delete out.clean; return out; }
    const brain = out.self ? "self" : "own";
    const before = messages.slice(0, -1).filter(m => m.role === "assistant");
    const gemmaBefore = before.every(m => { const r = seen[rid(textOf(m.content))]; return !!r && r[0] === "own" && r[1] === 1; });
    const clean = brain === "own" && out.clean !== false && gemmaBefore ? 1 : 0;
    const id = rid(out.reply);
    // the same words again (Gemma said exactly this before): they are Gemma's own words if they were once
    const was = seen[id];
    seen[id] = [brain, clean || (was && was[0] === brain && was[1] === 1) ? 1 : 0, brain === "own" ? String(ownBrain.model() || "gemma") : "self", Date.now()];
    seenDirty = true;
    out.rid = id;
    delete out.clean;
    return out;
  }

  // ---------- 👍 / 👎 from people ----------
  const RATE_MAX = 3000, PER_PHONE = 40, PER_DAY = 600;
  let ratings = load("gemma-ratings.json", []);
  const counts = new Map();   // "day|phone" -> how many ratings today
  let today = { day: "", n: 0 };
  function rate(body, device) {
    const id = String((body && body.rid) || "");
    const reply = String((body && body.reply) || "").trim();
    if (!/^[0-9a-f]{16}$/.test(id) || !reply) return { status: 400, error: "Nothing to rate." };
    const rec = seen[id];
    if (!rec || rid(reply) !== id) return { status: 410, error: "That answer is too old to rate now." };
    const d = day(), k = d + "|" + device;
    if (today.day !== d) { today = { day: d, n: 0 }; counts.clear(); }
    if ((counts.get(k) || 0) >= PER_PHONE || today.n >= PER_DAY) return { status: 429, error: "Thank you! That's enough ratings for today." };
    counts.set(k, (counts.get(k) || 0) + 1); today.n++;
    const good = body.good === true;
    const q = scrub(body.q).slice(0, 600);
    const r = { id: newId(), at: Date.now(), rid: id, brain: rec[0], clean: rec[1] === 1, model: rec[2], good, q, reply: scrub(reply).slice(0, 2000),
      fix: good ? "" : scrub(body.fix).slice(0, 1500), lang: teOf(q + " " + reply), dev: String(device || "").slice(0, 16), status: "new" };
    // the same phone rating the same answer again: the new rating replaces the old one
    ratings = ratings.filter(x => !(x.rid === id && x.dev === r.dev && x.status === "new"));
    ratings.push(r);
    if (ratings.length > RATE_MAX) {
      const done = ratings.filter(x => x.status !== "new");
      const drop = new Set((done.length >= ratings.length - RATE_MAX ? done : ratings).slice(0, ratings.length - RATE_MAX).map(x => x.id));
      ratings = ratings.filter(x => !drop.has(x.id));
    }
    save("gemma-ratings.json", ratings);
    return { ok: true };
  }

  // ---------- the owner's lessons ----------
  const LESSONS_MAX = 5000;
  let lessons = load("gemma-lessons.json", []);   // { id, at, q, a, lang, topic, from: "owner" | "fix" | "gemma" }
  function addLesson(b, from = "owner") {
    const q = String((b && b.q) || "").trim().slice(0, 600), a = String((b && b.a) || "").trim().slice(0, 3000);
    if (q.length < 3 || a.length < 2) return { status: 400, error: "Write the question and the answer." };
    const old = b.id ? lessons.find(l => l.id === String(b.id)) : null;
    const l = Object.assign(old || { id: newId(), at: Date.now(), from }, { q, a, lang: teOf(q + " " + a), topic: String(b.topic || (old && old.topic) || "").trim().slice(0, 60), edited: old ? Date.now() : undefined });
    if (!old) { lessons.push(l); if (lessons.length > LESSONS_MAX) lessons.splice(0, lessons.length - LESSONS_MAX); }
    save("gemma-lessons.json", lessons);
    return { ok: true, lesson: l };
  }
  function removeLesson(id) { const n = lessons.length; lessons = lessons.filter(l => l.id !== String(id)); save("gemma-lessons.json", lessons); return { ok: lessons.length < n }; }
  /** A rating becomes a lesson. The answer can be the person's correction (their words), Gemma's own clean answer, or the
   *  owner's own words; an answer that may hold Claude's words (or the self brain's ready-made text) can't be used as it is. */
  function ratingToLesson(b) {
    const r = ratings.find(x => x.id === String((b && b.id) || ""));
    if (!r) return { status: 404, error: "That rating is gone." };
    const a = String(b.a || "").trim();
    if (a === r.reply.trim() && !(r.clean && r.brain === "own"))
      return { status: 400, error: r.brain === "self" ? "This is the self brain's ready-made answer: write the answer in your own words." : "This answer may hold Claude's words (Claude answered earlier in that chat, or its facts came from Claude), so it can't teach Gemma as it is: write the answer in your own words." };
    const from = a === r.reply.trim() ? "gemma" : r.fix && a === r.fix.trim() ? "fix" : "owner";
    const out = addLesson({ q: b.q || r.q, a, topic: b.topic }, from);
    if (out.ok) { r.status = "lesson"; r.lesson = out.lesson.id; save("gemma-ratings.json", ratings); }
    return out;
  }
  function skipRating(id) { const r = ratings.find(x => x.id === String(id)); if (r) { r.status = "skip"; save("gemma-ratings.json", ratings); } return { ok: !!r }; }

  // ---------- the training file (for the Kaggle notebook) ----------
  // A private link with a long random key, for 14 days. Only lessons the owner made or approved, and the library's typed
  // notes, text files and web pages: never anything Claude wrote or read.
  let link = load("gemma-link.json", null);
  function makeLink(base) {
    link = { key: crypto.randomBytes(24).toString("base64url"), until: Date.now() + 14 * 86400000 };
    save("gemma-link.json", link);
    return linkInfo(base);
  }
  function stopLink() { link = null; try { fs.unlinkSync(file("gemma-link.json")); } catch (e) { } return { ok: true }; }
  const linkInfo = base => link && link.until > Date.now() ? { url: `${base}/api/gemma/export?key=${link.key}`, until: link.until } : null;
  function keyOk(k) {
    if (!link || link.until < Date.now() || !k) return false;
    const a = crypto.createHash("sha256").update(String(k)).digest(), b = crypto.createHash("sha256").update(link.key).digest();
    return crypto.timingSafeEqual(a, b);
  }
  function docs() {
    const out = [], skipped = [];
    let total = 0;
    for (const it of learn.listKnowledge()) {
      const via = learn.viaOf(it);
      if (via === "claude") { skipped.push(it.title); continue; }
      const text = learn.readKnowledge(it.id).slice(0, 60000);
      if (text.length < 200 || total + text.length > 400000) continue;
      total += text.length;
      out.push({ title: it.title, via, text });
    }
    return { docs: out, skipped };
  }
  function exportData() {
    const d = docs();
    return {
      app: "Habit AI", made: new Date().toISOString(), version: 1,
      rule: "Owner-made lessons only. Nothing written or read by Claude is in this file.",
      lessons: lessons.map(l => ({ q: l.q, a: l.a, lang: l.lang, topic: l.topic || "", from: l.from })),
      docs: d.docs, counts: { lessons: lessons.length, docs: d.docs.length, docsSkipped: d.skipped.length }
    };
  }

  // ---------- the exam ----------
  const EXAM_FILE = path.join(SERVER_DIR, "..", "brain", "exam.json");
  let examCache = null;
  function exam() {
    try { const st = fs.statSync(EXAM_FILE); if (!examCache || examCache.mtime !== st.mtimeMs) examCache = { mtime: st.mtimeMs, data: JSON.parse(fs.readFileSync(EXAM_FILE, "utf8")) }; }
    catch (e) { return null; }
    return examCache.data;
  }
  let results = load("gemma-exams.json", { runs: [], last: null });
  let job = null;   // { model, quick, done, total, ok, stop, items: [] }
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  async function ask(model, ex, q) {
    const r = await fetch(ownBrain.BASE + "/api/chat", { method: "POST", signal: AbortSignal.timeout(150000), body: JSON.stringify({
      model, stream: false, keep_alive: "10m", format: ex.schema,
      messages: [{ role: "system", content: ex.system }, { role: "user", content: q }],
      options: Object.assign({ num_thread: Math.max(1, os.cpus().length - 1) }, ex.options || {}) }) });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.error || "error " + r.status);
    return String((j.message || {}).content || "");
  }
  /** Runs the exam on [model] in the background (quick: about 40 questions, else all). Waits while people use the own brain. */
  function startExam({ model, quick }) {
    const ex = exam();
    if (!ex) return { status: 500, error: "The exam file (brain/exam.json) is missing. Update the server." };
    if (job && !job.finished) return { status: 409, error: "An exam is already running." };
    const m = String(model || ownBrain.model() || "").trim();
    if (!m) return { status: 400, error: "Our own brain isn't installed yet." };
    const items = ex.items.filter(i => !quick || i.quick);
    job = { model: m, quick: !!quick, total: items.length, done: 0, ok: 0, format: 0, stop: false, finished: false, started: Date.now(), items: [] };
    const me = job;
    (async () => {
      for (const it of items) {
        if (me.stop) break;
        for (let w = 0; ownBrain.busyNow() && w < 40 && !me.stop; w++) await sleep(15000);   // people first (up to 10 minutes)
        if (me.stop) break;
        let raw = "", err = "";
        try { raw = await ask(m, ex, it.q); } catch (e) { err = String(e.message || e).slice(0, 120); }
        const c = err ? { ok: false, format: false, fails: ["couldn't answer: " + err], reply: "" } : check(it, raw, ex.schema.properties.emotion.enum);
        me.done++; if (c.ok) me.ok++; if (c.format) me.format++;
        me.items.push({ id: it.id, cat: it.cat, q: it.q, ok: c.ok, format: c.format, fails: c.fails, reply: c.reply.slice(0, 600) });
        if (err && /not found|no such model|connection|ECONNREFUSED|fetch failed/i.test(err) && me.done >= 3 && me.ok === 0) { me.problem = err; break; }
      }
      me.finished = true;
      const by = {};
      for (const x of me.items) { const b = by[x.cat] || (by[x.cat] = [0, 0]); b[1]++; if (x.ok) b[0]++; }
      const run = { at: Date.now(), model: m, quick: me.quick, n: me.done, total: me.total, score: me.done ? Math.round(me.ok * 100 / me.done) : 0,
        format: me.done ? Math.round(me.format * 100 / me.done) : 0, byCat: by, minutes: Math.round((Date.now() - me.started) / 60000), stopped: me.stop || !!me.problem };
      if (me.done) { results.runs.unshift(run); results.runs = results.runs.slice(0, 20); results.last = { ...run, items: me.items }; save("gemma-exams.json", results); }
    })().catch(e => { me.finished = true; me.problem = e.message; });
    return { ok: true, total: items.length };
  }
  function stopExam() { if (job && !job.finished) job.stop = true; return { ok: true }; }
  const examState = () => job && !job.finished ? { running: true, model: job.model, quick: job.quick, done: job.done, total: job.total, ok: job.ok } : { running: false, problem: job && job.problem };

  // ---------- habit-gemma from the owner's Hugging Face ----------
  const LOG = file("gemma-model.log"), PID = file("gemma-model.pid");
  let started = 0;   // the script's number is written a moment after it starts: until then it counts as running
  function busyGetting() {
    if (Date.now() - started < 8000) return true;
    try { const pid = Number(fs.readFileSync(PID, "utf8")); if (pid) { process.kill(pid, 0); return true; } } catch (e) { }
    return false;
  }
  function runScript(args) {
    if (busyGetting()) return { status: 409, error: "It is already working on it." };
    const script = path.join(SERVER_DIR, "brain-model.sh");
    started = Date.now();
    try { fs.unlinkSync(PID); } catch (e) { }
    fs.writeFileSync(LOG, "== " + new Date().toISOString() + " ==\n");
    const q = s => "'" + String(s).replace(/'/g, "'\\''") + "'";
    spawn("bash", ["-c", `nohup setsid nice -n 15 bash ${q(script)} ${args.map(q).join(" ")} >> ${q(LOG)} 2>&1 < /dev/null & echo $! > ${q(PID)}`], { cwd: SERVER_DIR, stdio: "ignore" }).unref();
    hfCache = null;
    return { ok: true };
  }
  let hfCache = null;
  async function hf(fresh) {
    const token = process.env.HF_TOKEN || "";
    if (!token) return { set: false };
    if (!fresh && hfCache && Date.now() - hfCache.at < (hfCache.v.report ? 600000 : 60000)) return hfCache.v;
    const H = { authorization: "Bearer " + token };
    let v;
    try {
      const w = await fetch(HF + "/api/whoami-v2", { headers: H, signal: AbortSignal.timeout(8000) });
      if (!w.ok) throw new Error(w.status === 401 ? "The Hugging Face token was not accepted. Make a new one (Write) and save it again." : "Hugging Face answered " + w.status);
      const who = await w.json();
      const repo = process.env.HABIT_MODEL_REPO || `${who.name}/habit-gemma`;
      const r = await fetch(`${HF}/${repo}/resolve/main/report.json`, { headers: H, signal: AbortSignal.timeout(10000) });
      v = { set: true, user: who.name, repo, report: r.ok ? await r.json().catch(() => null) : null, none: r.status === 404 || r.status === 401 };
    } catch (e) { v = { set: true, problem: String(e.message || e).slice(0, 200) }; }
    hfCache = { at: Date.now(), v };
    return v;
  }
  async function installed() {
    try { const j = await fetch(ownBrain.BASE + "/api/tags", { signal: AbortSignal.timeout(3000) }).then(r => r.json()); return (j.models || []).map(m => String(m.name || m.model || "").replace(/:latest$/, "")).filter(Boolean); }
    catch (e) { return []; }
  }
  function brainConf() { try { return JSON.parse(fs.readFileSync(file("ownbrain.json"), "utf8")); } catch (e) { return {}; } }

  // ---------- the Teach page ----------
  async function info(base) {
    let log = "";
    try { log = fs.readFileSync(LOG, "utf8").split("\n").slice(-14).join("\n"); } catch (e) { }
    const ex = exam();
    const by = f => lessons.filter(l => l.from === f).length;
    const d = docs();
    return {
      counts: { lessons: lessons.length, owner: by("owner"), fixes: by("fix"), gemma: by("gemma"), docs: d.docs.length, docsSkipped: d.skipped.length,
        ratingsNew: ratings.filter(r => r.status === "new").length, up: ratings.filter(r => r.good).length, down: ratings.filter(r => !r.good).length },
      lessons: lessons.slice(-80).reverse(),
      ratings: ratings.filter(r => r.status === "new").slice(-60).reverse().map(({ dev, ...r }) => r),
      link: linkInfo(base),
      exam: { questions: ex ? ex.items.length : 0, quick: ex ? ex.items.filter(i => i.quick).length : 0, state: examState(), runs: results.runs.slice(0, 8), last: results.last },
      model: { now: ownBrain.model() || "", base: brainConf().base || "", installed: await installed(), getting: busyGetting(), log },
      hf: await hf(false)
    };
  }
  /** The owner's Teach page actions (/api/teach/gemma/…). Returns null for other paths. */
  async function teach(sub, body, base) {
    switch (sub) {
      case "lesson": return addLesson(body || {}, "owner");
      case "lesson/delete": return removeLesson((body || {}).id);
      case "rating/lesson": return ratingToLesson(body || {});
      case "rating/skip": return skipRating((body || {}).id);
      case "link": return { ok: true, link: makeLink(base) };
      case "link/stop": return stopLink();
      case "exam": return startExam(body || {});
      case "exam/stop": return stopExam();
      case "get": if (!process.env.HF_TOKEN) return { status: 400, error: "Save your Hugging Face token on the server first (set-key.sh HF_TOKEN …)." }; return runScript([]);
      case "back": return runScript(["--back"]);
      case "hf": return { hf: await hf(true) };
      default: return null;
    }
  }
  /** Public routes: POST /api/brain/rate (the app), GET /api/gemma/export?key=… (the Kaggle notebook). */
  const rateHits = new Map();   // ratings have their own count (many phones can share one address)
  async function handle(req, res, url, origin, h) {
    if (req.method === "POST" && url.pathname === "/api/brain/rate") {
      if (!h.allowed(h.clientIp(req), rateHits, 150)) { h.send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
      let body;
      try { body = await h.readBody(req, 32 * 1024); } catch (e) { h.send(res, 400, { error: "Bad request." }, origin); return true; }
      const device = crypto.createHash("sha256").update("rate|" + (String(req.headers["x-device"] || "") || h.clientIp(req))).digest("hex").slice(0, 16);
      const out = rate(body, device);
      h.send(res, out.status || 200, out.status ? { error: out.error } : out, origin);
      return true;
    }
    if (req.method === "GET" && url.pathname === "/api/gemma/export") {
      if (!h.allowed("gemma:" + h.clientIp(req))) { h.send(res, 429, { error: "Too many tries. Wait 10 minutes." }, origin); return true; }
      if (!keyOk(url.searchParams.get("key"))) { h.send(res, 403, { error: "This training link is wrong or has expired. Make a new one on the Teach page." }, origin); return true; }
      h.send(res, 200, exportData(), origin);
      return true;
    }
    return false;
  }

  return { mark, rate, handle, teach, info, check, exportData, addLesson, ratingToLesson, startExam, stopExam, examState, flushSeen, rid, scrub, _ratings: () => ratings, _seen: () => seen };
};
module.exports.check = check;
module.exports.NEVER = NEVER;
