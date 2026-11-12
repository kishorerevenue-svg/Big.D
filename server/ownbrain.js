// Habit AI's own brain: a free, open AI model (Google's Gemma, Apache 2.0) that runs on THIS server with Ollama.
// It answers when Claude (and Gemini) can't: Claude is down, there is no Claude key, or today's budget is used up.
// So the app never depends on one company. Before answering it reads the internet (news, Wikipedia, weather),
// the owner's knowledge library and the answers learned from common questions. Nothing leaves this server
// except those look-ups. Installed by brain-setup.sh (from the Teach page, or by hand).
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

module.exports = function makeOwnBrain({ DATA_DIR, SERVER_DIR, learn, answers }) {
  const BASE = process.env.OWN_BRAIN_URL || "http://127.0.0.1:11434";
  const CONF = path.join(DATA_DIR, "ownbrain.json");          // { model, vision, installed } written by brain-setup.sh
  const LOG = path.join(DATA_DIR, "ownbrain-setup.log");
  const off = () => /^(off|no|false|0)$/i.test(process.env.OWN_BRAIN || "");
  const conf = () => { try { return JSON.parse(fs.readFileSync(CONF, "utf8")); } catch (e) { return null; } };
  const model = () => process.env.OWN_BRAIN_MODEL || (conf() || {}).model || "";
  const stats = { answered: 0, failed: 0, skippedClaude: 0, lastProblem: "", lastMs: 0 };

  // ---------- is it there? ----------
  let seen = { at: 0, ok: false, vision: false };
  async function ready() {
    if (off() || !model()) return false;
    if (Date.now() - seen.at < 30000) return seen.ok;
    let ok = false, vision = false;
    try {
      const r = await fetch(BASE + "/api/tags", { signal: AbortSignal.timeout(2000) });
      const j = await r.json();
      const names = (j.models || []).map(m => String(m.name || m.model || ""));
      ok = names.includes(model()) || names.includes(model() + ":latest");
      if (ok) {
        const s = await fetch(BASE + "/api/show", { method: "POST", body: JSON.stringify({ model: model() }), signal: AbortSignal.timeout(3000) }).then(x => x.json()).catch(() => ({}));
        vision = Array.isArray(s.capabilities) ? s.capabilities.includes("vision") : !!(conf() || {}).vision;
      }
    } catch (e) { ok = false; }
    seen = { at: Date.now(), ok, vision };
    return ok;
  }

  // ---------- Claude down? then don't make people wait for it every time ----------
  // When Claude (with all its retries and backups) couldn't answer, the own brain answers straight away for a minute;
  // then Claude is tried again (if it is still down: 2, 4… up to 10 minutes), and is used again as soon as it answers.
  // Only real outages count (busy, no connection, a wrong or expired key, no credit), not one bad request.
  const claude = { fails: 0, skipUntil: 0 };
  const outage = e => !e || !e.status || !!e.resting || [401, 403, 408, 409, 425, 429, 500, 502, 503, 504, 529].includes(e.status) ||
    (e.status === 400 && /credit|billing|balance|quota|disabled|usage limit|spend limit|regain access/i.test(e.message || ""));
  function claudeFailed(e) {
    if (!outage(e)) return;
    claude.fails++;
    claude.skipUntil = Date.now() + Math.min(60000 * Math.pow(2, claude.fails - 1), 600000);
    warm();
  }
  function claudeWorked() { claude.fails = 0; claude.skipUntil = 0; }
  /** When someone last asked the own brain (the exam waits while people are using it). */
  let lastAsked = 0;
  const busyNow = () => Date.now() - lastAsked < 45000 || Date.now() < claude.skipUntil;
  function skipClaude() { const s = Date.now() < claude.skipUntil; if (s) stats.skippedClaude++; return s; }

  // load the model into memory before it is needed (the first answer is then quicker)
  let warming = 0;
  function warm() {
    if (Date.now() - warming < 120000) return;
    warming = Date.now();
    ready().then(ok => { if (ok) fetch(BASE + "/api/generate", { method: "POST", body: JSON.stringify({ model: model(), prompt: "", keep_alive: "30m" }), signal: AbortSignal.timeout(120000) }).catch(() => {}); });
  }

  // ---------- reading the internet (free, no keys) ----------
  const get = (u, ms = 6000) => fetch(u, { headers: { "user-agent": "Habit-AI/1.0 (own brain)" }, signal: AbortSignal.timeout(ms) });
  const isTe = s => /[ఀ-౿]/.test(s);
  const decode = s => String(s).replace(/<!\[CDATA\[|\]\]>/g, "").replace(/&amp;/g, "&").replace(/&quot;/g, "\"").replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").trim();

  async function news(q, te) {
    const topic = q.replace(/\b(latest|today'?s?|news|headlines|tell me|the|about|on|what'?s|what is|in)\b/gi, " ").replace(/వార్తలు|వార్త|న్యూస్|ఈరోజు|ఈ రోజు|తాజా|చెప్పు|గురించి/g, " ").replace(/[?.!]/g, " ").trim();
    const hl = te ? "hl=te&gl=IN&ceid=IN:te" : "hl=en-IN&gl=IN&ceid=IN:en";
    const u = topic.length > 2 ? `https://news.google.com/rss/search?q=${encodeURIComponent(topic)}&${hl}` : `https://news.google.com/rss?${hl}`;
    const xml = await get(u).then(r => r.text());
    const titles = [...xml.matchAll(/<item>[\s\S]*?<title>([\s\S]*?)<\/title>/g)].slice(0, 6).map(m => decode(m[1]));
    return titles.length ? `News headlines from Google News (${topic.length > 2 ? "about " + topic : "top stories"}), just now:\n` + titles.map((t, i) => `${i + 1}. ${t}`).join("\n") : "";
  }

  const WMO = { 0: "clear sky", 1: "mostly clear", 2: "partly cloudy", 3: "cloudy", 45: "fog", 48: "fog", 51: "light drizzle", 53: "drizzle", 55: "heavy drizzle",
    61: "light rain", 63: "rain", 65: "heavy rain", 80: "rain showers", 81: "rain showers", 82: "heavy rain showers", 95: "thunderstorm", 96: "thunderstorm with hail", 99: "thunderstorm with hail" };
  async function weather(q) {
    const m = q.match(/\b(?:in|at|for)\s+([A-Za-z][A-Za-z .'-]{2,40})/i) || q.match(/([ఀ-౿]{2,20})\s*(?:లో|లొ)\s/);
    if (!m) return "";
    const place = m[1].replace(/\b(today|tomorrow|now|right now|this week)\b.*$/i, "").trim();
    const g = await get(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(place)}&count=1&language=en`).then(r => r.json());
    const p = (g.results || [])[0];
    if (!p) return "";
    const w = await get(`https://api.open-meteo.com/v1/forecast?latitude=${p.latitude}&longitude=${p.longitude}&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=2`).then(r => r.json());
    const c = w.current || {}, d = w.daily || {};
    const day = i => `${(WMO[(d.weather_code || [])[i]] || "")}, ${Math.round((d.temperature_2m_min || [])[i])}–${Math.round((d.temperature_2m_max || [])[i])}°C, rain chance ${(d.precipitation_probability_max || [])[i] ?? "?"}%`;
    return `Weather in ${p.name}${p.admin1 ? ", " + p.admin1 : ""} (Open-Meteo, now): ${Math.round(c.temperature_2m)}°C, ${WMO[c.weather_code] || ""}. Today: ${day(0)}. Tomorrow: ${day(1)}.`;
  }

  async function wiki(q, te) {
    const term = q.replace(/\b(who|what|is|are|was|were|tell me|about|explain|the|a|an|meaning of|define|history of)\b/gi, " ")
      .replace(/ఎవరు|ఏమిటి|ఏంటి|అంటే|గురించి|చెప్పు|చెప్పండి|వివరించు|ఎవరో|ఏమిటో/g, " ").replace(/[?.!,]/g, " ").replace(/\s+/g, " ").trim();
    if (term.length < 3) return "";
    for (const host of te ? ["te", "en"] : ["en"]) {
      try {
        const s = await get(`https://${host}.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(term)}&srlimit=1&format=json`).then(r => r.json());
        const title = (((s.query || {}).search || [])[0] || {}).title;
        if (!title) continue;
        const sum = await get(`https://${host}.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`).then(r => r.json());
        if (sum && sum.extract) return `From Wikipedia ("${title}"): ${String(sum.extract).slice(0, 700)}`;
      } catch (e) { }
    }
    return "";
  }

  /** Facts for the question: what was learned, the owner's library, and the internet. Each look-up gives up after a few seconds.
   *  [used] (optional) gets claude: true when a fact came from Claude (an answer learned from Claude, or a document Claude read):
   *  then this answer is never used to teach Habit's own model. */
  async function facts(q, lang, used) {
    const te = lang === "te" || isTe(q);
    const out = [];
    const learned = answers ? answers.lookup(q) : null;
    if (learned) out.push(`A prepared answer to this common question (checked earlier with the internet):\n${learned.answer.slice(0, 1000)}`);
    const info = {};
    const k = learn.knowledgeBlock(q, info);
    if (k) out.push(k.trim().slice(0, 900));
    if (used && (learned || (k && info.claude))) used.claude = true;
    const jobs = [];
    if (/news|headline|వార్త|న్యూస్|samachar|varthalu/i.test(q)) jobs.push(news(q, te));
    if (/weather|rain|temperature|forecast|వాతావరణం|వర్షం|ఎండ|వేడి|చలి|ఉష్ణోగ్రత/i.test(q)) jobs.push(weather(q));
    if (/\b(who|what|which|where|when|why|how|tell me about|explain|meaning|define|history)\b|ఎవరు|ఏమిటి|ఏంటి|అంటే|గురించి|ఎక్కడ|ఎప్పుడు|ఎందుకు|ఎలా/i.test(q) && !/news|weather|వార్త|వాతావరణం/i.test(q)) jobs.push(wiki(q, te));
    const got = await Promise.all(jobs.map(j => j.catch(() => "")));
    for (const g of got) if (g) out.push(g);
    // a small server reads slowly: keep all facts together short
    let total = 0;
    return out.filter(f => (total += f.length) <= 2200 || f === out[0]);
  }

  // ---------- answering as the pet ----------
  const LANG = {
    auto: "Reply in the language the person uses: Telugu script if they write Telugu (or Telugu in English letters), English if they write English.",
    te: "Always reply in simple spoken Telugu, in Telugu script.",
    en: "Always reply in simple, clear English."
  };
  const SCHEMA = {
    type: "object",
    properties: {
      emotion: { type: "string", enum: ["happy", "excited", "thinking", "sad", "surprised", "love", "confused", "laughing", "calm", "wink", "proud", "shy"] },
      reply: { type: "string" },
      sound: { type: "string", enum: ["", "tada", "laugh", "wow", "oops", "sad", "applause", "drumroll", "boing", "ding", "whoosh"] }
    },
    required: ["emotion", "reply"]
  };

  /** The words the own brain is told before each answer (also the exam's, brain/exam.json, with no facts). */
  function prompt(lang, ctx, pet, found, photo) {
    const memories = (ctx.memories || []).slice(-8).filter(Boolean).map(m => String(m).slice(0, 160));
    return `You are ${pet.name}, ${pet.like}, a kind friend who lives on the person's phone in the Habit AI app. Right now you are Habit's own brain on Habit's own server (the big AI is not reachable), so answer by yourself.
${LANG[lang] || LANG.auto}
Your reply is read aloud: talk like a warm human friend. Chat: 1 to 3 short sentences. Questions: a clear answer in up to 6 short sentences.
Use the FACTS when they help and say where they came from ("the news says…", "Wikipedia says…"). If the facts don't cover it and you are not sure, say honestly that you are not sure and they can ask again in a little while. Never make up names, numbers, prices, dates or news.
Health, money and legal questions: general, safe guidance only, and suggest a doctor, the bank or an officer for decisions. Never ask for passwords, OTPs, PINs or card numbers. Refuse anything harmful, kindly.
Answer only with JSON: {"emotion": "...", "reply": "...", "sound": ""}. Leave "sound" empty unless a fun sound really fits.
${photo ? "A photo is attached (from their camera or gallery): look at it carefully and answer what they ask. Read any writing exactly as written, then explain or translate it if needed. If it is blurry, dark or not in view, say so and ask them to hold the phone steady and closer and say \"look again\".\n" : ""}Now: ${ctx.now || new Date().toISOString()} ${ctx.tz || ""}.
${memories.length ? "Things they told you before (use them kindly when they fit):\n- " + memories.join("\n- ") + "\n" : ""}FACTS:
${found.length ? found.join("\n\n") : "(none)"}`;
  }

  /** The answer's text (JSON: {emotion, reply, sound}) and clean: false when Claude's words were among its facts. Throws if it can't. */
  async function pet(messages, lang, ctx, petInfo) {
    if (!(await ready())) throw Object.assign(new Error("Our own brain isn't installed or running yet."), { status: 503, user: true });
    const t0 = Date.now();
    lastAsked = t0;
    const last = messages[messages.length - 1];
    const photo = Array.isArray(last.content) ? last.content.find(c => c.type === "image") : null;
    const lastText = Array.isArray(last.content) ? String((last.content.find(c => c.type === "text") || {}).text || "") : String(last.content || "");
    const question = lastText.replace(/^\[Your eyes:[^\]]*\]\s*(\(looking again\)\s*)?/, "");
    const used = {};
    const found = photo ? [] : await facts(question, lang, used);
    const msgs = messages.slice(-4).map((m, i, all) => {
      const text = Array.isArray(m.content) ? String((m.content.find(c => c.type === "text") || {}).text || "") : String(m.content || "");
      const o = { role: m.role === "assistant" ? "assistant" : "user", content: text.slice(0, i === all.length - 1 ? 1200 : 400) };
      if (i === all.length - 1 && photo && seen.vision && photo.source && photo.source.data) o.images = [photo.source.data];
      return o;
    });
    const body = {
      model: model(), stream: false, keep_alive: "30m", format: SCHEMA,
      messages: [{ role: "system", content: prompt(lang, ctx, petInfo, found, !!(photo && seen.vision)) }, ...msgs],
      options: { num_ctx: 4096, num_predict: 260, temperature: 0.6, num_thread: Math.max(1, os.cpus().length - 1) }
    };
    try {
      const r = await fetch(BASE + "/api/chat", { method: "POST", body: JSON.stringify(body), signal: AbortSignal.timeout(80000) });
      const j = await r.json();
      if (!r.ok) throw Object.assign(new Error(j.error || "own brain error " + r.status), { status: 502 });
      const text = String((j.message || {}).content || "").trim();
      if (!text) throw Object.assign(new Error("own brain gave no answer"), { status: 502 });
      stats.answered++; stats.lastMs = Date.now() - t0; lastAsked = Date.now();
      return { text, sources: [], clean: !used.claude };
    } catch (e) {
      stats.failed++; stats.lastProblem = new Date().toISOString() + " " + String(e.message || e).slice(0, 120);
      seen.at = 0;   // check again next time
      throw Object.assign(e, { status: e.status || 502 });
    }
  }

  // ---------- writing in the background (Media posts): no one is waiting, so it may take its time ----------
  /** True when the installed model is big enough to write good Telugu posts (the 1B model answers chats, but writes poorly). */
  const canWrite = () => !/(?:^|[:\-_])(?:270m|0\.5b|1b|1\.5b)\b/i.test(model()) || /^(1|true|yes)$/i.test(process.env.OWN_BRAIN_WRITES || "");
  /** Writes JSON that fits [schema]. Throws (status 503, with a reason) if the own brain isn't there or can't write well
   *  ("not_ready", "too_small"), or if Claude is down ("busy": then people need the own brain for chat). */
  async function writeJson({ system, prompt, schema, maxTokens = 700, timeoutMs = 240000 }) {
    if (!(await ready())) throw Object.assign(new Error("own brain not ready"), { status: 503, reason: "not_ready" });
    if (!canWrite()) throw Object.assign(new Error("own brain model is too small to write posts"), { status: 503, reason: "too_small" });
    if (Date.now() < claude.skipUntil) throw Object.assign(new Error("own brain is busy answering people"), { status: 503, reason: "busy" });
    const r = await fetch(BASE + "/api/chat", { method: "POST", signal: AbortSignal.timeout(timeoutMs), body: JSON.stringify({
      model: model(), stream: false, keep_alive: "10m", format: schema,
      messages: [{ role: "system", content: system }, { role: "user", content: prompt }],
      options: { num_ctx: 4096, num_predict: maxTokens, temperature: 0.5, num_thread: Math.max(1, os.cpus().length - 1) } }) });
    const j = await r.json();
    if (!r.ok) throw Object.assign(new Error(j.error || "own brain error " + r.status), { status: 502 });
    const text = String((j.message || {}).content || "");
    try { stats.wrote = (stats.wrote || 0) + 1; return JSON.parse(text); } catch (e) { throw Object.assign(new Error("own brain wrote something that isn't JSON"), { status: 502 }); }
  }

  // ---------- installing (Teach page button, or brain-setup.sh by hand) ----------
  // the installer runs on its own (gently, so the app stays quick), and keeps going if the server restarts
  const RUNNING = path.join(DATA_DIR, "ownbrain-setup.pid");
  function installing() {
    try { const pid = Number(fs.readFileSync(RUNNING, "utf8")); if (pid) { process.kill(pid, 0); return true; } } catch (e) { }
    return false;
  }
  function install() {
    if (installing()) return { started: false, running: true };
    const script = path.join(SERVER_DIR, "brain-setup.sh");
    fs.writeFileSync(LOG, "== " + new Date().toISOString() + " ==\n");
    const q = s => "'" + String(s).replace(/'/g, "'\\''") + "'";
    spawn("bash", ["-c", `nohup setsid nice -n 15 bash ${q(script)} >> ${q(LOG)} 2>&1 < /dev/null & echo $! > ${q(RUNNING)}`], { cwd: SERVER_DIR, stdio: "ignore" }).unref();
    seen.at = 0;
    return { started: true, running: true };
  }
  // After an update the server installs its own brain by itself, once (it tries again the next day if it couldn't).
  const TRIED = path.join(DATA_DIR, "ownbrain-tried");
  function autoInstall() {
    if (off() || model() || /^(off|no|false|0)$/i.test(process.env.OWN_BRAIN_AUTO || "")) return;
    try { if (Date.now() - fs.statSync(TRIED).mtimeMs < 20 * 3600000) return; } catch (e) { }
    try { fs.writeFileSync(TRIED, new Date().toISOString()); } catch (e) { }
    console.log("Own brain: not installed yet, installing it in the background (see the Teach page)");
    install();
  }
  setTimeout(autoInstall, 5 * 60000).unref();

  async function status() {
    const ok = await ready();
    let log = "";
    try { log = fs.readFileSync(LOG, "utf8").split("\n").slice(-12).join("\n"); } catch (e) { }
    return { on: !off(), installed: !!model(), model: model(), ready: ok, vision: ok && seen.vision, installing: installing(), writes: ok && canWrite(),
      answered: stats.answered, wrote: stats.wrote || 0, failed: stats.failed, skippedClaude: stats.skippedClaude, lastSeconds: Math.round(stats.lastMs / 1000), lastProblem: stats.lastProblem,
      claudeResting: Date.now() < claude.skipUntil, log };
  }

  /** For /health: what we know right now, without waiting (it checks again in the background). */
  function quick() { ready().catch(() => {}); return { installed: !!model(), model: model() || "none", ready: seen.ok, vision: seen.ok && seen.vision, answered: stats.answered }; }

  return { ready, pet, writeJson, canWrite, facts, status, quick, install, warm, claudeFailed, claudeWorked, skipClaude, stats, prompt, SCHEMA, busyNow, BASE, model };
};
