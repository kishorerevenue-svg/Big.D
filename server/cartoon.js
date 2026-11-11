// Habit cartoons: our own cartoon videos, made fully on our server (no paid video AI, no GPU).
//  - OUR OWN BRAIN writes each one as a tiny screenplay (a joke, riddle, fact, kids story or good thought) in simple
//    Telugu; Claude only when our own brain can't. Habit's own characters act it out: Ramu the tea seller, Ammamma,
//    Chinnu, Chitti, Ramayya the farmer, the teacher, the Habit puppy, a cat and a parrot, in a market, village, farm,
//    tea shop, beach, park, home, school or on stage. They walk, talk, slip, jump, laugh, dance... (toon/toon.py).
//  - Speech bubbles, sound effects, talk sounds (or the natural voice, GOOGLE_TTS_KEY) and music by mood.
//  - It LEARNS BY ITSELF: after each cartoon has been out a while, its likes, comments and views are counted; the
//    kinds people like get made more often, and the brain is shown the favourites (and the ideas people asked for).
//  - It knows what's happening (trends.js): on a festival day (or the day before) the day's first cartoon is about
//    the festival, and the writer sees today's kind themes from Google Trends India (a cricket match day, exam
//    results, a rainy day…; never names, politics or news).
//  - Anyone using the app can ask for one with their own idea (a few a day each); they can watch it, share it and
//    post it on their profile. Unkind or unsafe ideas are refused.
//  - Needs python3 with Pillow on the server (installed by itself the first time); until then the old puppy cartoon.
// CARTOONS_DAILY made by itself a day (default 4, the Teach page can change it), posted in Media (😄 Fun, 🧒 Kids, 💡 Facts).
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { execFile } = require("child_process");

module.exports = function makeCartoon({ DATA_DIR, media, ai, MODEL, API_KEY, ownBrain, voice, h, engagement, aiPeople, isAdmin, freeVoice, trends, claudeOut }) {
  const FFMPEG = process.env.FFMPEG || "ffmpeg";
  const ART = path.join(__dirname, "assets", "cartoon");
  const FONTS = path.join(__dirname, "fonts");
  const DIR = path.join(DATA_DIR, "cartoons");
  fs.mkdirSync(DIR, { recursive: true });
  const FILE = path.join(DATA_DIR, "cartoons.json");
  // cartoons made by itself for Media: the owner sets how many a day on the Teach page (default 4)
  const DAILY = () => { const v = db && db.settings && db.settings.daily; return Math.max(0, Math.min(24, Number(v !== undefined && v !== null ? v : (process.env.CARTOONS_DAILY ?? 4)))); };
  const EACH = () => Math.max(0, Number(process.env.CARTOON_PUBLIC_EACH ?? 3));        // a day, for each person
  const PUBLIC_DAY = () => Math.max(0, Number(process.env.CARTOON_PUBLIC_DAILY ?? 60)); // a day, everyone together
  const ON = String(process.env.CARTOONS || "true") !== "false";
  let db = { next: 1, made: [], day: "", auto: 0, pub: 0, perDevice: {}, asks: [], jobs: {} };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  let saveTimer = null;
  const save = () => { clearTimeout(saveTimer); saveTimer = setTimeout(() => { try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("Cartoon save error:", e.message); } }, 300); };
  const ist = () => new Date(Date.now() + 5.5 * 3600e3);
  const today = () => ist().toISOString().slice(0, 10);
  const roll = () => { if (db.day !== today()) { db.day = today(); db.auto = 0; db.pub = 0; db.perDevice = {}; save(); } };
  const run = (cmd, args, opt = {}) => new Promise((res, rej) => execFile(cmd, args, { timeout: 5 * 60e3, maxBuffer: 64e6, ...opt }, (e, out, err) => e ? rej(Object.assign(e, { stderr: err })) : res(out)));

  // ---------- our own cartoon maker (toon/toon.py, needs python3 with Pillow) ----------
  const PY = process.env.PYTHON || "python3";
  const TOON = path.join(__dirname, "toon", "toon.py");
  let toonOk = false, toonNote = "checking…";
  const checkToon = () => new Promise(res => execFile(PY, ["-c", "import PIL"], { timeout: 30000 }, e => res(!e)));
  const toonReady = (async () => {
    if (String(process.env.TOON || "true") === "false") { toonNote = "off (TOON=false): the old puppy cartoon"; return; }
    toonOk = await checkToon();
    if (toonOk) { toonNote = "ready"; return; }
    if (process.getuid && process.getuid() === 0 && String(process.env.TOON_AUTOINSTALL || "true") !== "false") {
      toonNote = "installing Pillow for the cartoon maker (a few minutes)…";
      const sh = "export DEBIAN_FRONTEND=noninteractive; apt-get install -y -q python3-pil >/dev/null 2>&1 || (apt-get update -y -q >/dev/null 2>&1 && apt-get install -y -q python3-pil >/dev/null 2>&1)";
      execFile("bash", ["-c", sh], { timeout: 20 * 60e3 }, async () => {
        toonOk = await checkToon();
        toonNote = toonOk ? "ready" : "couldn't install python3-pil; on the server run: apt-get install -y python3-pil";
        console.log("Cartoons:", toonNote);
      });
    } else toonNote = "needs python3-pil; on the server run: apt-get install -y python3-pil";
  })();
  // the characters of the cute (doodle) look; the first look's names still work (chaiwala = chai, chinnu = idli, ...)
  const CHARS = ["chai", "idli", "mango", "bigd", "ammamma", "tatayya", "teacher", "pilli", "chilaka"];
  const ALIAS = { chaiwala: "chai", chinnu: "idli", chitti: "mango", raithu: "tatayya" };
  const NAMES = { chai: "చాయ్", idli: "ఇడ్లీ", mango: "మామిడి", bigd: "హ్యాబిట్", ammamma: "అమ్మమ్మ", tatayya: "తాతయ్య", teacher: "టీచర్", pilli: "పిల్లి", chilaka: "చిలుక" };
  const VOICE_OF = { bigd: "puppy", chai: "bear", tatayya: "robot", ammamma: "panda", teacher: "kitten", idli: "chameleon", mango: "bunny", pilli: "kitty", chilaka: "parrot" };
  const STYLE = process.env.TOON_STYLE || "doodle";
  const PLACES = ["market", "village", "farm", "teashop", "beach", "park", "home", "school", "stage", "night"];
  const ACTIONS = ["talk", "enter", "exit", "run", "jump", "slip", "fall", "laugh", "surprised", "think", "dance", "sleep", "cry", "wave", "give", "eat", "balance",
    "point", "clap", "shrug", "facepalm", "crossarms", "cheer", "kneel", "angry", "shy"];
  const PROPS = ["tea", "tray", "kettle", "banana", "ball", "book", "mango", "umbrella", "phone", "kite", "laddu", "flower", "bag", "icecream", "letter"];
  const OLD_SCENE = { home: "room", school: "school", night: "night", stage: "stage" };

  // ---------- kinds of cartoons ----------
  const TYPES = {
    joke: { te: "హాస్యం", en: "Joke", cat: "fun", scene: "stage", place: "market", mood: "funny", music: "music-kids.m4a" },
    riddle: { te: "పొడుపు కథ", en: "Riddle", cat: "fun", scene: "school", place: "school", mood: "happy", music: "music-happy.m4a" },
    fact: { te: "మీకు తెలుసా", en: "Fact", cat: "facts", scene: "school", place: "school", mood: "useful", music: "music-inspiring.m4a" },
    story: { te: "కథ", en: "Story", cat: "kids", scene: "park", place: "village", mood: "happy", music: "music-happy.m4a" },
    thought: { te: "మంచి మాట", en: "Good thought", cat: "fun", scene: "park", place: "park", mood: "inspiring", music: "music-inspiring.m4a" }
  };
  const SCENES = ["park", "room", "night", "school", "stage"];

  // ---------- learning by itself: which kinds people like ----------
  const score = m => { const e = (engagement && m.itemId && engagement(m.itemId)) || {}; return (e.likes || 0) * 3 + (e.comments || 0) * 4 + (e.views || 0) * 0.2; };
  function learned() {
    const now = Date.now();
    const out = {};
    for (const t of Object.keys(TYPES)) {
      const done = db.made.filter(m => m.type === t && !m.pub && m.itemId && now - m.at > 12 * 3600e3 && now - m.at < 30 * 86400e3);
      out[t] = { made: done.length, avg: done.length ? Math.round(done.reduce((a, m) => a + score(m), 0) / done.length * 10) / 10 : null };
    }
    return out;
  }
  function pickType() {
    const L = learned(), keys = Object.keys(TYPES);
    if (Math.random() < 0.25) return keys[Math.floor(Math.random() * keys.length)];       // sometimes try any kind
    const w = keys.map(k => (L[k].avg === null ? 5 : L[k].avg) + 1);
    let r = Math.random() * w.reduce((a, b) => a + b, 0);
    for (let i = 0; i < keys.length; i++) { r -= w[i]; if (r <= 0) return keys[i]; }
    return keys[0];
  }
  const favourites = () => db.made.filter(m => !m.pub && m.itemId && Date.now() - m.at > 12 * 3600e3 && Date.now() - m.at < 45 * 86400e3)
    .map(m => [m, score(m)]).filter(([, s]) => s > 0).sort((a, b) => b[1] - a[1]).slice(0, 5).map(([m]) => m.title);

  // ---------- the screenplay, by our own brain ----------
  const SYSTEM = (type) => `You write a very short cartoon for Habit AI, a Telugu app used by families in Andhra Pradesh and Telangana, with children watching too.
Kind: ${TYPES[type].en} (${TYPES[type].te}).${type === "riddle" ? ' A riddle: one character asks it, another thinks, and the last line gives the answer, starting with "జవాబు:".' : ""}${type === "fact" ? " Only true, well-known facts; nothing doubtful. The teacher or Ammamma tells it to a child." : ""}${type === "story" ? " A tiny story with a happy ending and a clear moral in the last line." : ""}${type === "joke" ? " A clean, gentle joke from everyday Telugu life, with a funny action (a slip, a surprise, a dance...) when it fits." : ""}${type === "thought" ? " A good thought for the day, said kindly." : ""}
The characters (use 1 to 3; write their key). They are cute cartoon characters:
- chai: Chai (చాయ్), a jolly fellow with a glass of tea for a head
- idli: Idli (ఇడ్లీ), a naughty little boy with an idli for a head
- mango: Mango (మామిడి), a clever little girl with a mango for a head
- bigd: Habit the puppy (హ్యాబిట్), the app's cute, kind puppy
- ammamma: Ammamma (అమ్మమ్మ), a wise, funny grandma
- tatayya: Tatayya (తాతయ్య), a sweet old grandpa, a farmer
- teacher: the teacher (టీచర్)
- pilli: a talking cat (పిల్లి)
- chilaka: a talking parrot (చిలుక) that copies words
"place" is one of: ${PLACES.join(", ")}.
Each beat is one character doing one thing and (usually) saying one short line. "do" is one of: talk, enter (walks in), exit (walks out), run, jump, slip (slips on a banana peel and falls; what they hold flies up and lands on their head), fall, laugh, surprised, think, dance, sleep, cry, wave, give (gives their prop to another character), eat (eats their prop), balance (balances the prop on their head), point (points at someone), clap, shrug, facepalm (hand on the face), crossarms (arms crossed), cheer (both arms up), kneel, angry (stamps the feet), shy.
"prop" (or "none") is one of: ${PROPS.join(", ")} (tray = a tray of tea glasses).
Like the cartoons trending on Reels: the first line is a hook that grabs attention at once, then a quick story, then a funny or sweet twist at the end (the punchline or the moral). 3 to 5 beats, 8 to 20 seconds. Every line in simple, everyday Telugu (Telugu script), at most 10 words.
Be kind: never make fun of women or men, any caste, religion, region, colour, body or disability, or any real person; no politics, no violence, nothing scary or adult. Slapstick stays gentle: nobody gets hurt.
If the idea you are given is unkind, unsafe, about real or famous people, or not something to show children, set "skip" to true.
Reply only with JSON: {"title": "<Telugu, at most 6 words>", "place": "...", "cast": ["..."], "beats": [{"who": "...", "do": "...", "say": "...", "prop": "..."}], "mood": one of funny, happy, inspiring, calm, useful, "skip": false}`;
  const SCHEMA = { type: "object", required: ["title", "place", "cast", "beats", "mood", "skip"], properties: { title: { type: "string" },
    place: { type: "string", enum: PLACES }, cast: { type: "array", items: { type: "string", enum: CHARS } },
    beats: { type: "array", items: { type: "object", required: ["who", "do", "say"], properties: { who: { type: "string", enum: CHARS },
      do: { type: "string", enum: ACTIONS }, say: { type: "string" }, prop: { type: "string", enum: [...PROPS, "none"] } } } },
    mood: { type: "string", enum: ["happy", "funny", "inspiring", "calm", "useful"] }, skip: { type: "boolean" } } };
  const clean = t => String(t || "").replace(/[{}\\]/g, "").replace(/\s+/g, " ").trim();
  /** It learns from the internet every day: light topics from today's Media posts (festivals, weather, farming, facts,
   *  kids) give the writer fresh ideas. (Never the news or politics.) */
  function todayTopics() {
    const out = [];
    for (const c of ["devotional", "farming", "facts", "kids", "fun"]) {
      try { out.push(...(media && media.recentTitles ? media.recentTitles(c, 2) : [])); } catch (e) { }
    }
    return out.map(t => String(t || "").replace(/^[^\p{L}\p{N}]+/u, "").slice(0, 80)).filter(t => t.length > 3).slice(0, 8);
  }
  /** Festivals coming up (from our own panchangam) and what India is searching for today (Google Trends, as kind themes). */
  function todayLines() {
    const out = [];
    try {
      const soon = trends ? trends.upcoming(3) : [];
      if (soon.length) out.push("Festivals: " + soon.map(f => `${f.en} (${f.te}) ${f.inDays === 0 ? "is today" : f.inDays === 1 ? "is tomorrow" : "is in " + f.inDays + " days"}${f.approx ? " (about)" : ""}: ${f.show}`).join("; "));
      const th = trends ? trends.themes() : [];
      if (th.length) out.push("What people in India are searching for today, as kind themes (use one only if it fits; never real people, teams or brands): " + th.map(t => t.en).join("; "));
    } catch (e) { }
    return out;
  }
  /** The day's festival theme for the first cartoon of a festival day (or the day before). */
  function festivalTheme() {
    try {
      const n = trends && trends.festivalNow();
      if (!n) return "";
      const f = n.f, when = n.when === "today" ? (f.approx ? "around today" : "today") : (f.approx ? "around tomorrow" : "tomorrow");
      return `${f.en} (${f.te}) is ${when}. Make the cartoon about this festival, warmly and respectfully, the way families in Andhra Pradesh and Telangana enjoy it: ${f.show}. ` +
        (n.when === "tomorrow" ? "Show the happy preparations for it. " : "") + "Never make fun of any faith or its customs, and keep it safe for children.";
    } catch (e) { return ""; }
  }
  /** Is our cartoon writer resting? (Claude's monthly limit is used up and our own brain is too small to write.) */
  const writerResting = () => { const o = claudeOut && claudeOut(); return !!o && !(ownBrain && ownBrain.quick && ownBrain.quick().ready && ownBrain.canWrite && ownBrain.canWrite()); };
  function restingError() {
    const o = claudeOut && claudeOut();
    const d = o && o.why === "limit" && o.until ? new Date(o.until + 5.5 * 3600e3) : null;
    const MONTH = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
    const MONTH_TE = ["జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"];
    return d ? `మా కార్టూన్ రచయిత ${d.getUTCDate()} ${MONTH_TE[d.getUTCMonth()]} వరకు విశ్రాంతిలో ఉన్నారు. · Our cartoon writer is resting until ${d.getUTCDate()} ${MONTH[d.getUTCMonth()]}. Please try again then.`
      : "మా కార్టూన్ రచయిత కొంతసేపు విశ్రాంతిలో ఉన్నారు. · Our cartoon writer is resting for a while. Please try again later.";
  }
  /** Reads the screenplay JSON, even when it was cut off at the end (then the beats that are complete). */
  function parseLoose(text) {
    const a = text.indexOf("{"), b = text.lastIndexOf("}");
    if (a >= 0 && b > a) { try { return JSON.parse(text.slice(a, b + 1)); } catch (e) { } }
    const bi = text.indexOf('"beats"');
    if (bi < 0) return null;
    const str = k => { const m = text.match(new RegExp('"' + k + '"\\s*:\\s*"((?:[^"\\\\]|\\\\.)*)"')); try { return m ? JSON.parse('"' + m[1] + '"') : ""; } catch (e) { return ""; } };
    const beats = [];
    for (const m of text.slice(bi).matchAll(/\{[^{}]*\}/g)) { try { beats.push(JSON.parse(m[0])); } catch (e) { } }
    const castM = text.match(/"cast"\s*:\s*\[([^\]]*)\]/);
    const skipM = text.match(/"skip"\s*:\s*(true|false)/);
    return { title: str("title"), place: str("place"), mood: str("mood"), cast: castM ? [...castM[1].matchAll(/"([a-z]+)"/g)].map(x => x[1]) : [],
      beats, skip: !!skipM && skipM[1] === "true" };
  }
  /** Checks and tidies a screenplay; throws when it isn't good enough to make. */
  function tidy(j, type) {
    const key = c => ALIAS[String(c || "")] || String(c || "");
    const cast = [...new Set((Array.isArray(j.cast) ? j.cast : []).map(key).filter(c => CHARS.includes(c)))].slice(0, 3);
    let beats = (Array.isArray(j.beats) ? j.beats : []).map(b => ({ who: key(b && b.who), do: ACTIONS.includes(b && b.do) ? b.do : "talk",
      say: clean(b && b.say).slice(0, 110), prop: PROPS.includes(b && b.prop) ? b.prop : "" }));
    for (const b of beats) if (CHARS.includes(b.who) && !cast.includes(b.who) && cast.length < 3) cast.push(b.who);
    if (!cast.length) cast.push("bigd");
    beats = beats.filter(b => cast.includes(b.who)).slice(0, 6);
    const says = beats.filter(b => b.say.length > 1);
    const tel = x => (x.match(/[ఀ-౿]/g) || []).length;
    if (says.length < 2 || tel(says.map(b => b.say).join("")) < 15) throw new Error("couldn't write it well, please try again");
    const place = PLACES.includes(j.place) ? j.place : (TYPES[type].place || "park");
    const mood = ["happy", "funny", "inspiring", "calm", "useful"].includes(j.mood) ? j.mood : TYPES[type].mood;
    return { title: clean(j.title || TYPES[type].te).slice(0, 80), place, cast, beats, mood,
      says: says.map(b => b.say), lines: says.map(b => `${NAMES[b.who]}: ${b.say}`), scene: OLD_SCENE[place] || TYPES[type].scene };
  }
  async function script(type, idea, theme) {
    const fav = favourites(), asked = db.asks.slice(-6).map(a => a.prompt);
    const prompt = idea ? `The person's idea: «${idea}»\nTurn it into this cartoon with the characters, place and props above (pick the closest ones).` :
      [theme ? `TODAY'S THEME (make the cartoon about it): ${theme}` : `Make a new ${TYPES[type].en.toLowerCase()} for today.`,
        fav.length ? `People liked these recently (make something new in the same spirit, not a copy): ${fav.join(" | ")}` : "",
        asked.length && !theme ? `Ideas people asked for recently (for inspiration): ${asked.join(" | ")}` : "",
        todayTopics().length && !theme ? `What people are reading about today (only if one fits and stays kind; never real people or politics): ${todayTopics().join(" | ")}` : "",
        ...(theme ? [] : todayLines()),
        `Seed: ${crypto.randomBytes(3).toString("hex")}`].filter(Boolean).join("\n");
    let j = null, by = "own";
    // (Telugu takes many tokens: room for a whole screenplay, and what was cut off at the end is left out)
    try { j = await ownBrain.writeJson({ system: SYSTEM(type), prompt, schema: SCHEMA, maxTokens: 2500, timeoutMs: 240000 }); }
    catch (e) {
      if (!API_KEY) throw new Error(e.reason === "busy" ? "our brain is busy, try again in a little while" : "our brain isn't ready yet");
      by = "claude";
      for (let k = 0; k < 2 && !j; k++) {     // once more (shorter) if the first answer can't be read
        const ask = k ? prompt + "\n(Reply with the JSON only: 4 beats, short lines.)" : prompt;
        let data;
        try { data = await ai({ model: MODEL, max_tokens: 4000, system: SYSTEM(type), messages: [{ role: "user", content: ask }] }, { tries: 1, backup: true }); }
        catch (x) { if (x.resting || x.why || (claudeOut && claudeOut())) throw new Error(restingError()); throw x; }
        const text = (data.content || []).filter(c => c.type === "text").map(c => c.text).join("");
        j = parseLoose(text);
        if (j && !j.skip && !(Array.isArray(j.beats) && j.beats.length >= 2)) j = null;
        if (!j) console.error("Cartoon: the screenplay couldn't be read:", text.slice(0, 300).replace(/\s+/g, " "));
      }
    }
    if (!j) throw new Error("couldn't write it, please try again");
    if (j.skip) throw Object.assign(new Error("Sorry, we can't make that one. Please try a kind, happy idea."), { refused: true });
    return Object.assign(tidy(j, type), { by });
  }

  // ---------- making the video ----------
  const RATE = 24000;
  async function pcm(file) { return await run(FFMPEG, ["-v", "error", "-i", file, "-f", "s16le", "-ac", "1", "-ar", String(RATE), "-"], { encoding: "buffer" }); }
  function wav(buf) {
    const hd = Buffer.alloc(44);
    hd.write("RIFF", 0); hd.writeUInt32LE(36 + buf.length, 4); hd.write("WAVE", 8); hd.write("fmt ", 12); hd.writeUInt32LE(16, 16); hd.writeUInt16LE(1, 20); hd.writeUInt16LE(1, 22);
    hd.writeUInt32LE(RATE, 24); hd.writeUInt32LE(RATE * 2, 28); hd.writeUInt16LE(2, 32); hd.writeUInt16LE(16, 34); hd.write("data", 36); hd.writeUInt32LE(buf.length, 40);
    return Buffer.concat([hd, buf]);
  }
  // (no emoji in the video: the video font has none, they would show as empty boxes)
  const esc = t => String(t).replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}]/gu, "").replace(/\\/g, "＼").replace(/[{}]/g, "").replace(/\n/g, "\\N").trim();
  const ts = s => { const cs = Math.max(0, Math.round(s * 100)); return `${Math.floor(cs / 360000)}:${String(Math.floor(cs / 6000) % 60).padStart(2, "0")}:${String(Math.floor(cs / 100) % 60).padStart(2, "0")}.${String(cs % 100).padStart(2, "0")}`; };

  async function render(id, s, type) {
    const tmp = path.join(DIR, id + "-tmp");
    fs.mkdirSync(tmp, { recursive: true });
    try {
      // 1. the voice, line by line (so the words on screen match it); without the voice, a reading time for each line
      const STEP = 0.1, parts = [];
      let t = 0.8;
      const said = s.says || s.lines;
      for (let i = 0; i < said.length; i++) {
        if (type === "riddle" && i === said.length - 1) t += 1.4;     // a little wait before the answer
        let samples = null;
        if (voice && voice.enabled) { try { samples = await pcm((await voice.speak(said[i], "puppy", s.mood === "calm" ? "calm" : "happy")).file); } catch (e) { samples = null; } }
        const dur = samples ? samples.length / 2 / RATE : Math.max(2.2, said[i].length * 0.075);
        parts.push({ at: t, dur, samples });
        t += dur + 0.45;
      }
      const total = Math.min(60, t + 2.4);
      const n = Math.ceil(total / STEP);
      // 2. the voice track, and how open the mouth is every 0.1 s
      const voiceBuf = Buffer.alloc(Math.ceil(total * RATE) * 2);
      const level = new Array(n).fill(0);
      for (const p of parts) {
        const start = Math.floor(p.at * RATE) * 2;
        if (p.samples) {
          p.samples.copy(voiceBuf, start, 0, Math.min(p.samples.length, voiceBuf.length - start));
          for (let k = 0; k < Math.ceil(p.dur / STEP); k++) {
            let sum = 0, cnt = 0;
            for (let o = Math.floor(k * STEP * RATE) * 2; o < Math.min(p.samples.length - 1, Math.floor((k + 1) * STEP * RATE) * 2); o += 8) { const v = p.samples.readInt16LE(o); sum += v * v; cnt++; }
            const rms = cnt ? Math.sqrt(sum / cnt) : 0;
            const idx = Math.floor(p.at / STEP) + k;
            if (idx < n) level[idx] = rms > 4500 ? 2 : rms > 1300 ? 1 : 0;
          }
        } else {
          for (let k = 0; k < Math.ceil(p.dur / STEP); k++) { const idx = Math.floor(p.at / STEP) + k; if (idx < n) level[idx] = [1, 2, 1, 0][k % 4]; }
        }
      }
      // 3. the puppy, frame by frame (mouth from the voice, a blink every few seconds)
      const MOUTH = ["closed", "half", "open"];
      let list = "ffconcat version 1.0\n", last = "", run1 = 0, nextBlink = 2.5 + Math.random() * 2;
      const flush = () => { if (last) list += `file '${path.join(ART, last)}'\nduration ${(run1 * STEP).toFixed(2)}\n`; };
      for (let k = 0; k < n; k++) {
        const tt = k * STEP;
        let eyes = "open";
        if (tt >= nextBlink) { eyes = "blink"; if (tt >= nextBlink + 0.15) nextBlink = tt + 2.6 + Math.random() * 2.4; }
        const f = `puppy_${MOUTH[level[k]]}_${eyes}.png`;
        if (f === last) run1++; else { flush(); last = f; run1 = 1; }
      }
      flush();
      list += `file '${path.join(ART, last)}'\n`;
      fs.writeFileSync(path.join(tmp, "puppy.ffconcat"), list);
      fs.writeFileSync(path.join(tmp, "voice.wav"), wav(voiceBuf));
      // 4. the words: the title on top, each line in a speech bubble while the puppy says it
      const end = total - 2.2;
      const ass = `[Script Info]
ScriptType: v4.00+
PlayResX: 720
PlayResY: 1280
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Title,Hind Guntur,50,&H00FFFFFF,&H00FFFFFF,&H00402010,&H90000000,1,0,0,0,100,100,0,0,1,4,2,8,40,40,120,1
Style: Bubble,Hind Guntur,44,&H00262626,&H00262626,&H00FFFFFF,&H50000000,1,0,0,0,100,100,0,0,3,16,3,8,70,70,330,1
Style: Label,Hind Guntur,26,&H00FFFFFF,&H00FFFFFF,&H60000000,&H70000000,1,0,0,0,100,100,0,0,3,1,0,9,24,24,40,1
Style: End,Hind Guntur,64,&H00FFFFFF,&H00FFFFFF,&H00402010,&H90000000,1,0,0,0,100,100,0,0,1,4,2,5,40,40,0,1
Style: EndSub,Hind Guntur,34,&H00FFFFFF,&H00FFFFFF,&H00402010,&H90000000,1,0,0,0,100,100,0,0,1,3,1,5,40,40,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 2,${ts(0)},${ts(end)},Label,,0,0,0,,Habit cartoon
Dialogue: 1,${ts(0.2)},${ts(end)},Title,,0,0,0,,{\\fad(400,200)}${esc(s.title)}
${parts.map((p, i) => `Dialogue: 1,${ts(p.at - 0.1)},${ts(Math.min(end, p.at + p.dur + 0.35))},Bubble,,0,0,0,,{\\fad(150,100)}${esc(said[i])}`).join("\n")}
Dialogue: 3,${ts(end)},${ts(total)},End,,0,0,0,,{\\fad(300,0)\\pos(360,560)}Habit AI
Dialogue: 3,${ts(end)},${ts(total)},EndSub,,0,0,0,,{\\fad(300,0)\\pos(360,640)}కార్టూన్లు · వార్తలు · సహాయం · bigdai.tech
`;
      fs.writeFileSync(path.join(tmp, "words.ass"), ass);
      // 5. all together
      const q = p => p.replace(/[:\\']/g, m => "\\" + m);
      const music = fs.existsSync(path.join(__dirname, "assets", TYPES[type].music)) ? path.join(__dirname, "assets", TYPES[type].music) : path.join(__dirname, "assets", "reel-music.m4a");
      const out = path.join(DIR, id + ".mp4");
      const fc = `[0:v]scale=720:1280,setsar=1,fps=30[bg];[1:v]fps=30,scale=520:-1,format=rgba[p];` +
        `[bg][p]overlay=x=(W-w)/2:y=H-h-70+9*sin(2*PI*t*1.1):eval=frame:shortest=0,trim=duration=${total.toFixed(2)},` +
        `drawbox=x=0:y=0:w=iw:h=ih:color=black@0.45:t=fill:enable='gte(t,${end.toFixed(2)})',` +
        `ass=${q(path.join(tmp, "words.ass"))}:fontsdir=${q(FONTS)}:shaping=complex,format=yuv420p[v];` +
        `[3:a]volume=0.22,afade=t=out:st=${(total - 1.5).toFixed(2)}:d=1.5[m];[2:a]volume=1.0[vo];[vo][m]amix=inputs=2:duration=first:dropout_transition=0,volume=2[a]`;
      await run("nice", ["-n", "10", FFMPEG, "-y", "-v", "error", "-loop", "1", "-framerate", "30", "-t", total.toFixed(2), "-i", path.join(ART, `bg_${s.scene}.png`),
        "-f", "concat", "-safe", "0", "-i", path.join(tmp, "puppy.ffconcat"), "-i", path.join(tmp, "voice.wav"), "-stream_loop", "-1", "-i", music,
        "-filter_complex", fc, "-map", "[v]", "-map", "[a]", "-t", total.toFixed(2), "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", out + ".part.mp4"]);
      fs.renameSync(out + ".part.mp4", out);
      await run(FFMPEG, ["-y", "-v", "error", "-ss", String(Math.min(3, total / 3).toFixed(1)), "-i", out, "-frames:v", "1", "-q:v", "4", path.join(DIR, id + ".jpg")]).catch(() => {});
      return Math.round(total);
    } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
  }

  /** Our own cartoon maker: Habit's characters act out the screenplay (toon/toon.py). Returns the length in seconds. */
  async function renderToon(id, s, type) {
    const tmp = path.join(DIR, id + "-tmp");
    fs.mkdirSync(tmp, { recursive: true });
    try {
      const beats = [];
      // the voices: the natural Google voice when the server has it, else our own free Telugu voice, else talk sounds
      const google = voice && voice.enabled;
      const free = !google && freeVoice && (freeVoice.ok() || await freeVoice.ready());
      for (const [i, b] of s.beats.entries()) {
        const nb = { who: b.who, do: b.do, say: b.say, prop: b.prop || "" };
        const f = path.join(tmp, `v${i}.wav`);
        if (b.say && google) {
          try {
            const samples = await pcm((await voice.speak(b.say, VOICE_OF[b.who] || "puppy", s.mood === "calm" ? "calm" : "happy")).file);
            fs.writeFileSync(f, wav(samples));
            nb.voice = f;
          } catch (e) { }
        } else if (b.say && free) {
          try {
            await freeVoice.speak(b.say, b.who, f);
            const secs = (fs.statSync(f).size - 44) / 48000, letters = (b.say.match(/[ఀ-౿a-z0-9]/gi) || []).length;
            if (secs > 0.3 && secs < Math.max(8, letters * 0.25)) nb.voice = f;     // (a voice gone wrong: talk sounds instead)
          } catch (e) { console.error("Cartoon voice:", e.message); }
        }
        beats.push(nb);
      }
      const music = path.join(__dirname, "assets", TYPES[type].music);
      const out = path.join(DIR, id + ".mp4"), poster = path.join(DIR, id + ".jpg");
      const plan = { style: STYLE, title: s.title, place: s.place, cast: s.cast, beats, mood: s.mood, type, seed: crypto.randomBytes(2).readUInt16LE(0),
        out, poster, tmp, cache: path.join(DIR, "cache"), fonts: FONTS, ffmpeg: FFMPEG, music: fs.existsSync(music) ? music : path.join(__dirname, "assets", "reel-music.m4a") };
      fs.writeFileSync(path.join(tmp, "plan.json"), JSON.stringify(plan));
      const res = await run("nice", ["-n", "10", PY, TOON, path.join(tmp, "plan.json")], { timeout: 12 * 60e3 });
      let dur = 0;
      try { dur = JSON.parse(String(res).trim().split("\n").pop()).dur; } catch (e) { }
      if (!fs.existsSync(out)) throw new Error("the cartoon maker made no video");
      if (!fs.existsSync(poster)) await run(FFMPEG, ["-y", "-v", "error", "-ss", "3", "-i", out, "-frames:v", "1", "-q:v", "4", poster]).catch(() => {});
      return Math.round(dur || 20);
    } catch (e) {
      throw new Error("the cartoon maker failed: " + String((e.stderr && String(e.stderr).trim().split("\n").pop()) || e.message).slice(0, 200));
    } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
  }

  // ---------- one at a time (our own brain and the video maker are shared) ----------
  const queue = [];
  let busy = false;
  async function pump() {
    if (busy) return;
    busy = true;
    while (queue.length) {
      const job = queue.shift();
      job.status = "making"; save();
      // a person's idea while today's AI cartoons last: the trend-quality AI cartoon (free for now)
      if (job.ai) {
        try {
          const r = await aiPeople.make(job.prompt, job.type);
          fs.renameSync(r.file, path.join(DIR, job.id + ".mp4"));
          try { fs.renameSync(r.poster, path.join(DIR, job.id + ".jpg")); } catch (e) { }
          Object.assign(job, { title: r.title, lines: r.lines, dur: r.dur, status: "done", by: "gemini", doneAt: Date.now() });
          db.made.push({ id: job.id, type: job.type, title: r.title, at: Date.now(), pub: true, itemId: 0, by: "gemini" }); db.made = db.made.slice(-400);
          console.log(`Cartoon: made AI cartoon ${job.id} for someone`);
          save(); continue;
        } catch (e) {
          aiPeople.refund(job.device, job.admin);
          job.status = "failed"; job.doneAt = Date.now();
          // not swapped for the puppy (a different picture from what the person asked for): their free try is given back
          job.error = e.refused ? String(e.message).slice(0, 160)
            : "Sorry, the AI cartoon couldn't be made just now. Your free try is given back, so please try again in a little while." + (job.admin ? ` (Owner: ${String(e.message || e).slice(0, 200)})` : "");
          console.error("AI cartoon failed:", e.message);
          save(); continue;
        }
      }
      try {
        const s = await script(job.type, job.prompt, job.theme);
        job.title = s.title; job.lines = s.lines;
        await toonReady;
        job.dur = toonOk ? await renderToon(job.id, s, job.type) : await render(job.id, s, job.type);
        job.maker = toonOk ? "toon" : "puppy";
        job.status = "done"; job.by = s.by;
        db.made.push({ id: job.id, type: job.type, title: s.title, at: Date.now(), pub: !!job.device, itemId: 0, by: s.by });
        if (!job.device) {
          const it = media.addItem({ cat: TYPES[job.type].cat, title: "🎬 " + s.title, body: s.lines.join("\n"), emoji: "🎬", mood: s.mood, issue: job.type === "story" ? "children" : "other",
            kind: "cartoon", writer: s.by, by: "auto", img: "", key: "cartoon:" + job.id, video: { url: `/media/cartoon/${job.id}.mp4`, poster: `/media/cartoon/${job.id}.jpg`, dur: job.dur, w: 720, h: 1280 } });
          if (it) db.made[db.made.length - 1].itemId = it.id;
        }
        db.made = db.made.slice(-400);
        console.log(`Cartoon: made ${job.id} (${job.type}${job.device ? ", asked by someone" : ""}, written by ${s.by})`);
      } catch (e) {
        job.status = "failed"; job.error = String(e.message || e).slice(0, 160);
        if (job.device && !job.admin && !e.refused) { const k = job.device; db.perDevice[k] = Math.max(0, (db.perDevice[k] || 1) - 1); db.pub = Math.max(0, db.pub - 1); }   // a failure doesn't count
        console.error("Cartoon failed:", job.error);
      }
      job.doneAt = Date.now();
      save();
    }
    busy = false;
  }
  function add(type, prompt, device, aiCartoon, admin, theme) {
    const id = (device ? "p" : "a") + (db.next++) + (device ? "_" + crypto.randomBytes(4).toString("hex") : "");
    const job = { id, type, prompt: prompt || "", device: device || "", status: "waiting", at: Date.now(), ai: !!aiCartoon, admin: !!admin, theme: theme || "" };
    db.jobs[id] = job;
    for (const [k, j] of Object.entries(db.jobs)) if (Date.now() - j.at > 3 * 86400e3) delete db.jobs[k];
    queue.push(job); save(); pump();
    return job;
  }

  // ---------- by itself, through the day ----------
  const SLOTS = [7, 9, 11, 13, 15, 17, 19, 20, 21, 8, 10, 12, 14, 16, 18, 22];
  function tick() {
    if (!ON || String(process.env.MEDIA_AUTO || "true") === "false" || busy || queue.length) return;
    if (!toonOk && !(db.settings && db.settings.daily !== undefined)) return;     // not the old puppy by itself
    roll();
    if (db.auto >= DAILY() || ist().getUTCHours() < SLOTS[Math.min(db.auto, SLOTS.length - 1)] || ist().getUTCHours() > 22) return;
    if (writerResting()) return;      // no writer today (Claude's monthly limit): nothing to make, nothing to fail
    db.auto++;
    // a festival today (or tomorrow): the day's first cartoon is about it
    const theme = db.festDay !== today() ? festivalTheme() : "";
    if (theme) db.festDay = today();
    save();
    add(theme ? "story" : pickType(), "", "", false, false, theme);
  }
  if (ON) { setTimeout(tick, 4 * 60_000).unref(); setInterval(tick, 10 * 60_000).unref(); }
  // videos are kept 60 days (the ones people made: 7 days, unless they posted them)
  setInterval(() => {
    for (const f of fs.readdirSync(DIR)) {
      const p = path.join(DIR, f);
      try { const age = Date.now() - fs.statSync(p).mtimeMs; if (age > 60 * 86400e3 || (/^p/.test(f) && age > 7 * 86400e3 && !(db.jobs[f.split(".")[0]] || {}).posted)) fs.rmSync(p, { recursive: true, force: true }); } catch (e) { }
    }
  }, 12 * 3600e3).unref();

  // ---------- for the app ----------
  const devOf = req => crypto.createHash("sha256").update("cartoon|" + (String(req.headers["x-device"] || "").replace(/[^\w-]/g, "").slice(0, 64) || (h ? h.clientIp(req) : ""))).digest("hex").slice(0, 20);
  const WHY = {
    no_key: "AI cartoons from your idea are coming soon!",
    off: "AI cartoons are resting right now. Please try again later.",
    budget: "This month's free AI cartoons are all made. They start again next month!",
    day: "Today's free AI cartoons are all made. Please come back tomorrow! 🌅",
    you: "You made your free AI cartoon today. Please come back tomorrow for another one! 🌅"
  };
  const BAD = /\b(sex|nude|naked|porn|kill|murder|rape|suicide|bomb|drugs?)\b|బూతు|చంపు|హత్య/i;
  const view = j => ({ id: j.id, status: j.status, title: j.title || "", lines: j.lines || [], error: j.error || "", position: j.status === "waiting" ? queue.indexOf(j) + 1 : 0,
    video: j.status === "done" ? { url: `/media/cartoon/${j.id}.mp4`, poster: `/media/cartoon/${j.id}.jpg`, dur: j.dur || 0, w: 720, h: 1280 } : null, posted: !!j.posted,
    ai: !!j.ai, note: j.note || "" });
  async function handle(req, res, url, origin, social) {
    const p = url.pathname;
    const m = p.match(/^\/media\/cartoon\/([a-z0-9_]{2,40})\.(mp4|jpg)$/);
    if (m && req.method === "GET") {
      const f = path.join(DIR, `${m[1]}.${m[2]}`);
      if (!fs.existsSync(f)) { res.writeHead(404); res.end(); return true; }
      const size = fs.statSync(f).size, head = { "Content-Type": m[2] === "mp4" ? "video/mp4" : "image/jpeg", "Cache-Control": "public, max-age=86400", "Accept-Ranges": "bytes" };
      const r = String(req.headers.range || "").match(/^bytes=(\d*)-(\d*)$/);
      if (r && m[2] === "mp4") {
        const start = r[1] ? Number(r[1]) : size - Number(r[2]); const end = Math.min(r[1] && r[2] ? Number(r[2]) : size - 1, size - 1);
        if (start >= size || start > end || start < 0) { res.writeHead(416, { "Content-Range": `bytes */${size}` }); res.end(); return true; }
        res.writeHead(206, { ...head, "Content-Range": `bytes ${start}-${end}/${size}`, "Content-Length": end - start + 1 });
        fs.createReadStream(f, { start, end }).pipe(res);
      } else { res.writeHead(200, { ...head, "Content-Length": size }); fs.createReadStream(f).pipe(res); }
      return true;
    }
    if (!p.startsWith("/api/cartoon/")) return false;
    const send = (st, o) => h.send(res, st, o, origin);
    const admin = !!(isAdmin && isAdmin(req));
    const aiState = dev => aiPeople ? aiPeople.state(dev, admin) : { on: false, reason: "no_key", all: 0, you: 0 };
    if (p === "/api/cartoon/make" && req.method === "POST") {
      const body = await h.readBody(req).catch(() => ({}));
      roll();
      const dev = devOf(req);
      const type = TYPES[body.type] ? body.type : "joke";
      const idea = String(body.prompt || "").replace(/\s+/g, " ").trim().slice(0, 1000);
      // maker: "ai" (the AI cartoon of their idea), "puppy" (our simple puppy cartoon, chosen by the person), or none (older apps: AI if there is one, else the puppy)
      const maker = body.maker === "free" ? "puppy" : ["ai", "puppy"].includes(body.maker) ? body.maker : "";
      if (!ON) return send(503, { error: "Cartoons are off right now." }), true;
      if (BAD.test(idea)) return send(400, { error: "Sorry, we can't make that one. Please try a kind, happy idea." }), true;
      if (queue.length >= 15) return send(429, { error: "So many people are making cartoons now! Please try again in a little while." }), true;
      const ai = maker === "puppy" ? { on: false } : aiState(dev);
      if (maker === "ai" && !ai.on) return send(409, { error: WHY[ai.reason] || WHY.no_key, reason: ai.reason }), true;
      if (!ai.on && writerResting()) return send(409, { error: restingError(), reason: "resting" }), true;     // tell them now, not after a wait
      if (!ai.on && !admin) {   // the free puppy: a few a day for each person
        if ((db.perDevice[dev] || 0) >= EACH()) return send(429, { error: `You can make ${EACH()} free cartoons a day. Please come back tomorrow! 🎬` }), true;
        if (db.pub >= PUBLIC_DAY()) return send(429, { error: "So many people are making cartoons today! Please try again tomorrow." }), true;
        db.perDevice[dev] = (db.perDevice[dev] || 0) + 1; db.pub++;
      }
      if (idea) db.asks = [...db.asks, { prompt: idea.slice(0, 120), type, at: Date.now(), ai: !!ai.on }].slice(-200);
      if (ai.on) aiPeople.take(dev, admin);
      const job = add(type, idea, dev, ai.on, admin);
      return send(200, view(job)), true;
    }
    if (p === "/api/cartoon/info" && req.method === "GET") {
      roll();
      const dev = devOf(req), ai = aiState(dev);
      return send(200, { ai: { on: ai.on, reason: ai.reason, why: ai.on ? "" : (WHY[ai.reason] || "") }, admin,
        aiLeftToday: ai.on ? ai.all : 0, aiLeftForYou: ai.on ? (admin ? Math.max(1, ai.you) : ai.you) : 0,
        leftForYou: admin ? 99 : Math.max(0, EACH() - (db.perDevice[dev] || 0)), each: EACH() }), true;
    }
    if (p === "/api/cartoon/status" && req.method === "GET") {
      const job = db.jobs[String(url.searchParams.get("id") || "")];
      return send(job ? 200 : 404, job ? view(job) : { error: "Not found" }), true;
    }
    if (p === "/api/cartoon/post" && req.method === "POST") {
      const body = await h.readBody(req).catch(() => ({}));
      const job = db.jobs[String(body.id || "")];
      if (!job || job.status !== "done" || job.device !== devOf(req)) return send(404, { error: "Not found" }), true;
      if (job.posted) return send(200, view(job)), true;
      const text = job.ai ? `🤖 ${job.title}\n${(job.lines || []).join("\n")}\n\n🤖 AI-made video · AI తో తయారైన వీడియో` : `🎬 ${job.title}\n${(job.lines || []).join("\n")}`;
      const r = social && social.postCartoon(req, text.slice(0, 900), view(job).video);
      if (!r || r.error) return send(400, { error: (r && r.error) || "Couldn't post it." }), true;
      job.posted = true; save();
      return send(200, view(job)), true;
    }
    return false;
  }

  function status() {
    roll();
    return { on: ON, maker: toonOk ? "ours" : "puppy", makerNote: toonNote,
      voice: voice && voice.enabled ? "the natural Google voice" : freeVoice ? "our free Telugu voice: " + freeVoice.state() : "talk sounds", daily: DAILY(), madeToday: db.auto, publicToday: db.pub, waiting: queue.length, busy, learned: learned(), favourites: favourites(),
      recent: db.made.slice(-12).reverse().map(m => ({ id: m.id, type: m.type, title: m.title, at: m.at, pub: m.pub, by: m.by, score: m.itemId ? score(m) : null })),
      asks: db.asks.slice(-10).reverse(), writerResting: writerResting(), trends: trends ? trends.status() : null };
  }
  /** The owner sets how many puppy cartoons are made by itself each day (0 = none). */
  function settings(o) {
    db.settings = db.settings || {};
    if (o && o.daily !== undefined && o.daily !== "" && isFinite(Number(o.daily))) db.settings.daily = Math.max(0, Math.min(24, Math.round(Number(o.daily))));
    save(); return status();
  }
  return { handle, status, settings, add, tick, TYPES, _test: { parseLoose, tidy, script, render, learned, pickType, db: () => db } };
};
