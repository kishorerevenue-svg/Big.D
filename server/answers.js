// Learning from common questions. When many different people ask BigD the same general question
// ("what is PM Kisan?", "how do I link Aadhaar with PAN?"), the server prepares a short, checked answer at night
// (with Claude and the internet) and keeps it. Then:
//  - our own brains (selfbrain.js, ownbrain.js) use it when Claude can't answer, and
//  - the same question asked again as a first message is answered at once from it (fast, and no AI bill).
// BigD also learns from Claude as it talks: when Claude answers a general question and says the answer is right for
// anyone (nothing about the person: Claude marks it, and the server checks it has none of the person's words), the
// answer is kept here too, so BigD can answer it by itself while Claude can't (its monthly limit is used up…).
// Those are kept 90 days. LEARN_FROM_CLAUDE=false turns this off.
// Only general questions are counted: nothing about the person (I, my, నా, నేను…), no long numbers, no links,
// no greetings or commands, nothing about today/news/prices. Who asked is never kept: only a salted, one-way
// mark so the same phone isn't counted twice. Questions nobody else asks are forgotten after 30 days.
// The owner sees everything on the Teach page and can delete any answer.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

module.exports = function makeAnswers({ DATA_DIR, learn }) {
  const QF = path.join(DATA_DIR, "questions.json");
  const AF = path.join(DATA_DIR, "learned.json");
  const SF = path.join(DATA_DIR, "learn-salt");
  const MIN = () => Math.max(2, Number(process.env.LEARN_MIN_ASKERS || 3));     // different people before an answer is prepared
  const off = () => /^(off|no|false|0)$/i.test(process.env.LEARN_QUESTIONS || "");
  const load = (f, d) => { try { return JSON.parse(fs.readFileSync(f, "utf8")); } catch (e) { return d; } };
  let qs = load(QF, {});          // key -> { q, lang, n, askers: [mark], first, last }
  let learned = load(AF, []);     // [{ id, key, q, lang, answer, sources, at, uses }]
  let dirty = false;
  const save = () => { if (!dirty) return; dirty = false; try { fs.writeFileSync(QF, JSON.stringify(qs)); fs.writeFileSync(AF, JSON.stringify(learned, null, 1)); } catch (e) { console.error("learning save:", e.message); } };
  setInterval(save, 30000).unref();

  // a new salt every month: last month's marks can't be matched to anything
  function salt() {
    const month = new Date().toISOString().slice(0, 7);
    let s = load(SF, null);
    if (!s || s.month !== month) { s = { month, salt: crypto.randomBytes(16).toString("hex") }; try { fs.writeFileSync(SF, JSON.stringify(s)); } catch (e) { } }
    return s.salt;
  }
  const mark = who => crypto.createHash("sha256").update(salt() + "|" + who).digest("hex").slice(0, 10);

  // ---------- which questions count ----------
  const te = s => /[ఀ-౿]/.test(s);
  const PERSONAL = /\b(i|me|my|mine|myself|i'm|im|i've|i'd|we|our|us|ours)\b|నేను|నాకు|నన్ను|నాది|నా\s|మాకు|మేము|మమ్మల్ని|మనం|మా\s/i;
  const COMMAND = /^(please\s+)?(remind|set|call|open|play|send|add|delete|remove|save|note|wake|turn|start|stop|show|take|look|read|translate|write|make|create|draft|reply|search|find|book|order)\b/i;
  const TIMELY = /\b(today|tonight|tomorrow|yesterday|now|current|currently|latest|this (week|month|year)|news|weather|rain|temperature|price|rate|cost|score|match|live|result|results|stock|share)\b|ఈరోజు|ఈ రోజు|రేపు|నిన్న|ఇప్పుడు|వార్త|వాతావరణం|వర్షం|ధర|రేటు|స్కోర్|ఫలితం|మ్యాచ్/i;
  const CHAT = /^(hi|hello|hey|hai|how are you|good (morning|night|evening|afternoon)|thanks|thank you|ok|okay|bye|who are you|what('s| is) your name|నమస్తే|నమస్కారం|హాయ్|హలో|బాగున్నావా|థాంక్స్|ధన్యవాదాలు|నువ్వు ఎవరు)\b/i;
  const CONTEXT = /\b(it|this|that|they|them|those|these|he|she|his|her|him|there|again|more)\b|అది|ఇది|వాళ్ళు|ఆయన|ఆమె|ఇంకా|మళ్ళీ/i;   // needs the conversation
  const ASKS = /\?|\b(what|how|why|when|where|who|which|can|is|are|does|do|explain|meaning|define|difference|benefits?|process|eligib\w*|documents?)\b|ఏమిటి|ఏంటి|ఎలా|ఎందుకు|ఎప్పుడు|ఎక్కడ|ఎవరు|ఎంత|ఏది|అంటే|ఎలాగ|అర్హత|పత్రాలు/i;

  // polite words around a question ("please tell me…", "bro", "చెప్పండి") don't make it personal or different
  const FILL = /\b(can|could|will|would) you (please )?(tell|explain|say)( to)? me\b|\b(tell|explain|say|show)( to)? me\b|\bdo you know\b|\b(please|pls|plz|bro|sir|madam|kindly|ok|so|hey|bigd|big d)\b|నాకు చెప్పు|నాకు చెప్పండి|చెప్పు|చెప్పండి|బ్రో|దయచేసి/gi;
  /** A general question anyone could ask (not about the person, not about today, not a command or chat). */
  function general(q) {
    const raw = String(q || "").trim();
    if (raw.length < 8 || raw.length > 200 || raw.startsWith("[")) return false;
    if (/\d{5,}|@|https?:\/\/|www\./i.test(raw)) return false;
    const t = raw.replace(FILL, " ").replace(/\s+/g, " ").trim();
    return t.length >= 6 && !PERSONAL.test(t) && !COMMAND.test(t) && !TIMELY.test(t) && !CHAT.test(t) && !CONTEXT.test(t) && ASKS.test(t);
  }
  const key = q => String(q).toLowerCase().replace(FILL, " ").replace(/[^\p{L}\p{M}\p{N}\s]/gu, " ").replace(/\s+/g, " ").trim();
  // small words that don't change what is asked ("what is the PM Kisan scheme" = "what is PM Kisan scheme")
  const STOP = new Set(["the", "a", "an", "is", "are", "was", "were", "of", "for", "to", "in", "on", "at", "and", "about", "do", "does", "did", "be", "can", "will", "should", "i"]);
  const words = k => new Set(k.split(" ").filter(w => w.length > 1 && !STOP.has(w)));
  const alike = (a, b) => { const x = words(a), y = words(b); if (!x.size || !y.size) return 0; let n = 0; for (const w of x) if (y.has(w)) n++; return n / (x.size + y.size - n); };

  /** Counts a question (if it is a general one). [who] = the phone's id or IP; only a one-way mark of it is kept. */
  function note(q, who) {
    if (off() || !general(q)) return;
    let k = key(q);
    if (!k) return;
    if (!qs[k]) {   // the same question in slightly different words counts as the same
      let best = "", score = 0;
      for (const other of Object.keys(qs)) { if (te(other) !== te(k)) continue; const s = alike(k, other); if (s > score) { score = s; best = other; } }
      if (score >= 0.8) k = best;
    }
    const now = new Date().toISOString();
    const e = qs[k] || (qs[k] = { q: String(q).trim().slice(0, 200), lang: te(q) ? "te" : "en", n: 0, askers: [], first: now, last: now });
    e.n++; e.last = now;
    const m = mark(who || "?");
    if (!e.askers.includes(m) && e.askers.length < 50) e.askers.push(m);
    dirty = true;
    const keys = Object.keys(qs);
    if (keys.length > 6000) { keys.sort((a, b) => (qs[a].last < qs[b].last ? -1 : 1)).slice(0, 1000).forEach(x => delete qs[x]); }
  }

  /** The learned answer for a question, if one fits: { answer, score, id, from }. */
  function lookup(q, { min = 0.75 } = {}) {
    if (off()) return null;
    const k = key(q);
    if (!k) return null;
    let best = null, score = 0;
    for (const a of learned) { if (te(a.key) !== te(k)) continue; const s = a.key === k ? 1 : alike(k, a.key); if (s > score) { score = s; best = a; } }
    if (!best || score < min) return null;
    return { answer: best.answer, score, id: best.id, sources: best.sources || [], from: best.from || "night", at: best.at };
  }
  /** Counts one more use of a learned answer. */
  function used(id) { const a = learned.find(x => x.id === id); if (a) { a.uses = (a.uses || 0) + 1; dirty = true; } }

  // ---------- learning from Claude's own answers ----------
  const CHAT_DAYS = 90, MAX = () => Math.max(100, Number(process.env.LEARNED_MAX || 3000));
  /** Keeps Claude's answer to a general question (true when kept). [personal]: the person's own words (names, numbers
   *  from their memories and notes); an answer that contains any of them is not kept. */
  function keep(q, answer, { sources = [], personal = [] } = {}) {
    if (off() || /^(off|no|false|0)$/i.test(process.env.LEARN_FROM_CLAUDE || "") || !general(q)) return false;
    const a = String(answer || "").trim();
    if (a.length < 25 || a.length > 1500 || /[\w.+-]+@[\w-]+\.\w/.test(a)) return false;
    const low = a.toLowerCase();
    if (personal.some(w => w && low.includes(String(w).toLowerCase()))) return false;
    const k = key(q);
    if (!k) return false;
    const old = learned.find(x => x.key === k);
    if (old && (old.from || "night") !== "chat") return false;                  // an answer prepared (and checked) at night stays
    if (old && Date.now() - Date.parse(old.at) < 7 * 86400e3) return false;      // learned this week already
    learned = learned.filter(x => x.key !== k);
    learned.push({ id: crypto.randomBytes(5).toString("hex"), key: k, q: String(q).trim().slice(0, 200), lang: te(q) ? "te" : "en", answer: a,
      sources: (sources || []).slice(0, 3), at: new Date().toISOString(), uses: old ? old.uses || 0 : 0, from: "chat" });
    tidyUp();
    dirty = true;
    return true;
  }
  /** Chat answers are forgotten after 90 days; at most LEARNED_MAX answers (the least used chat ones go first). */
  function tidyUp() {
    const now = Date.now();
    learned = learned.filter(x => (x.from || "night") !== "chat" || now - Date.parse(x.at) < CHAT_DAYS * 86400e3);
    if (learned.length > MAX()) {
      const chat = learned.filter(x => x.from === "chat").sort((a, b) => (a.uses || 0) - (b.uses || 0) || Date.parse(a.at) - Date.parse(b.at));
      const drop = new Set(chat.slice(0, learned.length - MAX()).map(x => x.id));
      learned = learned.filter(x => !drop.has(x.id));
    }
  }
  setInterval(() => { const n = learned.length; tidyUp(); if (learned.length !== n) { dirty = true; save(); } }, 12 * 3600e3).unref();

  /** A first question that is (almost) word for word a learned one: answer at once. (An answer learned from a chat
   *  is used this way for 30 days; after that Claude answers it fresh, and the old one is only for when it can't.) */
  function direct(q) {
    if (/^(off|no|false|0)$/i.test(process.env.LEARNED_DIRECT || "") || !general(q)) return null;
    const hit = lookup(q);
    if (!hit || hit.score < 0.9) return null;
    if (hit.from === "chat" && Date.now() - Date.parse(hit.at) > 30 * 86400e3) return null;
    const a = learned.find(x => x.id === hit.id);
    if (a) { a.uses = (a.uses || 0) + 1; dirty = true; }
    return hit;
  }

  // ---------- preparing answers (once a night, with Claude and the internet) ----------
  const PREP = lang => `You prepare short, correct answers to common questions that many people ask BigD AI, a friendly phone helper used by the public in Andhra Pradesh and Telangana, India.
Search the internet when the facts can change (schemes, rules, fees, steps, official websites).
Answer in ${lang === "te" ? "simple spoken Telugu, in Telugu script" : "simple, clear English"}, in 3 to 6 short sentences that will be read aloud, general for everyone. Give the official website or helpline when it helps.
If the question depends on the person's own details, on today's date, news or prices, needs a doctor or lawyer for that person, or is unsafe, reply with skip true.
Reply only with JSON: {"answer": "...", "skip": false}`;

  let preparing = false;
  async function prepare(max = Number(process.env.LEARN_PER_NIGHT || 25)) {
    if (off() || preparing) return { made: 0 };
    preparing = true;
    let made = 0;
    try {
      const month = 30 * 86400000, now = Date.now();
      const todo = Object.entries(qs)
        .filter(([k, e]) => e.askers.length >= MIN() && !learned.some(a => a.key === k && now - Date.parse(a.at) < month))
        .sort((a, b) => b[1].askers.length - a[1].askers.length).slice(0, max);
      for (const [k, e] of todo) {
        try {
          const out = await learn.callClaude({ system: PREP(e.lang), messages: [{ role: "user", content: e.q }], maxTokens: 900, search: true, maxSources: 2 });
          let obj = null;
          try { const t = out.text; obj = JSON.parse(t.slice(t.indexOf("{"), t.lastIndexOf("}") + 1)); } catch (x) { }
          if (!obj || obj.skip || !String(obj.answer || "").trim()) { e.skip = true; continue; }
          learned = learned.filter(a => a.key !== k);
          learned.push({ id: crypto.randomBytes(5).toString("hex"), key: k, q: e.q, lang: e.lang, answer: String(obj.answer).trim().slice(0, 1500), sources: out.sources || [], at: new Date().toISOString(), uses: 0 });
          made++; dirty = true;
        } catch (x) { console.error("learning: couldn't prepare an answer:", x.status || "", x.message); break; }   // AI down: try tomorrow
      }
      // forget questions nobody else asked in 30 days
      for (const [k, e] of Object.entries(qs)) if (now - Date.parse(e.last) > month && e.askers.length < MIN()) { delete qs[k]; dirty = true; }
    } finally { preparing = false; save(); }
    if (made) console.log("learning: prepared " + made + " answers to common questions");
    return { made };
  }
  // once a night, around 1 AM server time
  let lastNight = "";
  setInterval(() => { const d = new Date(); const day = d.toISOString().slice(0, 10); if (d.getHours() === 1 && lastNight !== day) { lastNight = day; prepare().catch(() => {}); } }, 600000).unref();

  // ---------- for the Teach page ----------
  function list() {
    const top = Object.entries(qs).map(([k, e]) => ({ key: k, q: e.q, lang: e.lang, people: e.askers.length, times: e.n, last: e.last, skipped: !!e.skip }))
      .sort((a, b) => b.people - a.people || b.times - a.times).slice(0, 60);
    const sorted = learned.slice().sort((a, b) => (b.uses || 0) - (a.uses || 0) || (Date.parse(b.at) - Date.parse(a.at)));
    return { on: !off(), fromClaude: !/^(off|no|false|0)$/i.test(process.env.LEARN_FROM_CLAUDE || ""), minPeople: MIN(), questions: top,
      learned: sorted.slice(0, 300).map(a => Object.assign({}, a, { from: a.from || "night" })), total: learned.length, fromChat: learned.filter(a => a.from === "chat").length };
  }
  function remove(id) { const n = learned.length; learned = learned.filter(a => a.id !== id); dirty = true; save(); return learned.length < n; }
  function forget(k) { const had = !!qs[k]; delete qs[k]; learned = learned.filter(a => a.key !== k); dirty = true; save(); return had; }
  function stats() { return { on: !off(), questions: Object.keys(qs).length, learned: learned.length, fromChat: learned.filter(a => a.from === "chat").length, used: learned.reduce((s, a) => s + (a.uses || 0), 0) }; }

  return { note, lookup, used, keep, direct, general, prepare, list, remove, forget, stats, _key: key };
};
