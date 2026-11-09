// Daily AI videos for Media: short vertical videos made by Google's Gemini Omni video model (GEMINI_API_KEY, with
// billing on), only on safe, timeless topics: devotional scenes (temples, lamps, flowers), kids' fables (cute
// animation), simple health tips and farming tips. Never news, never real people, never gods' faces up close.
// Each video gets Telugu words on screen (and the natural voice when GOOGLE_TTS_KEY is set) and a clear
// "🤖 AI-made" label, in the video and in the post.
// AI_VIDEOS_DAILY a day (default 2: a devotional one at 5 AM, a kids/health/farming one at 4 PM, India time), and
// never more than AI_VIDEO_MONTHLY_USD (default 35) in a month: the server counts what it spends and stops.
const fs = require("fs");
const path = require("path");
const { execFile } = require("child_process");
const panchang = require("./panchang.js");

module.exports = function makeAiVideo({ DATA_DIR, media, ai, MODEL, API_KEY, ownBrain, voice, h }) {
  const KEY = () => process.env.GEMINI_API_KEY || "";
  const BASE = () => process.env.GEMINI_BASE || "https://generativelanguage.googleapis.com";
  const VMODEL = () => process.env.GEMINI_VIDEO_MODEL || "gemini-omni-1.2-flash";
  // limits: the owner can change them on the Teach page (those win over .env); the server never spends more than CAP a month
  const set = (k, env, d) => { const v = db && db.settings && db.settings[k]; return v !== undefined && v !== null && v !== "" ? Number(v) : (process.env[env] !== undefined ? Number(process.env[env]) : d); };
  const DAILY = () => Math.max(0, Math.min(6, set("daily", "AI_VIDEOS_DAILY", 0)));
  const CAP = () => Math.max(0, set("monthlyUsd", "AI_VIDEO_MONTHLY_USD", 60));
  const SECONDS = () => Math.min(15, Math.max(4, Math.round(set("seconds", "AI_VIDEO_SECONDS", 8))));
  const CARTOONS = () => Math.max(0, Math.min(10, set("cartoons", "AI_CARTOONS_DAILY", 1)));               // funny AI cartoons a day, by itself
  const CSECONDS = () => Math.min(15, Math.max(4, Math.round(set("cartoonSeconds", "AI_CARTOON_SECONDS", 10))));
  const PUBLIC_DAY = () => Math.max(0, Math.min(200, set("publicDay", "AI_CARTOONS_PUBLIC_DAILY", 2)));     // people's own ideas, all together, a day
  const PUBLIC_EACH = () => Math.max(0, Math.min(5, set("publicEach", "AI_CARTOONS_PUBLIC_EACH", 1)));      // each person, a day
  const RATE = 0.075;         // US$ a second at 720p (a little above Google's price, to stay safely under the limit)
  const ON = () => !!KEY() && String(process.env.AI_VIDEOS || "true") !== "false" && String(process.env.MEDIA_AUTO || "true") !== "false";
  const FFMPEG = process.env.FFMPEG || "ffmpeg";
  const FONTS = path.join(__dirname, "fonts");
  const DIR = path.join(DATA_DIR, "aivideos");
  fs.mkdirSync(DIR, { recursive: true });
  const FILE = path.join(DATA_DIR, "aivideos.json");
  let db = { next: 1, month: "", spent: 0, day: "", made: 0, fails: 0, waitUntil: 0, used: {}, lastError: "", list: [] };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  const save = () => { try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("AI video save error:", e.message); } };
  const ist = () => new Date(Date.now() + 5.5 * 3600e3);
  const today = () => ist().toISOString().slice(0, 10);
  const month = () => today().slice(0, 7);
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  function roll() {
    if (db.month !== month()) { db.month = month(); db.spent = 0; }
    if (db.day !== today()) { db.day = today(); db.made = 0; db.fails = 0; db.cartoons = 0; db.pubDay = 0; db.pubEach = {}; }
  }

  // ---------- what to make ----------
  const THEMES = {
    devotional: ["sunrise over a South Indian temple tower (gopuram), bells ringing, birds flying", "rows of small oil lamps (diyas) glowing on temple steps at dusk",
      "lotus flowers opening in a temple pond with morning mist", "a colourful rangoli (muggu) at a house entrance at dawn with a brass lamp beside it",
      "oil lamps floating on a calm river at night", "marigold and jasmine garlands swaying at a temple entrance", "a camphor flame (harati) and a brass bell in a small home prayer room, soft light"],
    kids: ["the thirsty crow dropping pebbles into a pot until the water rises", "the lion and the little mouse who frees him from a hunter's net",
      "the slow tortoise winning the race while the hare sleeps", "the ant and the dove who help each other near a stream", "the monkey and the crocodile by a river under a jamun tree",
      "the clever rabbit showing the lion his own reflection in a well", "four friends (crow, mouse, tortoise and deer) helping each other in a forest"],
    health: ["water poured from a copper jug into a glass at sunrise", "an elderly couple seen from behind walking in a green park at sunrise",
      "a plate of millet roti, vegetables, dal and curd on a banana leaf", "hands washed with soap and water, slow motion", "a person seen from behind doing gentle yoga stretches on a terrace at sunrise",
      "fresh papaya, guava and banana cut on a wooden board", "a quiet bedroom at night with the phone kept away, a person sleeping peacefully (seen from far)"],
    farming: ["drip irrigation watering rows of young vegetable plants", "hands spreading straw mulch around plants to keep the soil wet", "rain water flowing into a small farm pond beside green paddy fields",
      "young paddy seedlings transplanted into a wet field (hands only)", "neem leaves ground into a natural pest spray in a clay pot", "bees visiting sunflower and mustard flowers",
      "compost turned with a spade, earthworms in rich dark soil"]
  };
  THEMES.cartoon = ["a grandfather trying his first video call, the phone camera showing only his ear", "a monkey snatching a boy's mango, and the boy getting it back with a clever trick",
    "a family stuck in a traffic jam while a cow calmly walks past every vehicle", "a puppy and a cat both wanting the same soft cushion", "a boy pressing snooze on five alarm clocks before school",
    "a farmer's scarecrow that frightens the farmer himself in the evening", "a first-time cook flipping a dosa that sticks to the ceiling", "a parrot that repeats everything the family says at the wrong moment",
    "a schoolgirl's homework blown away by the wind and chased all over the village", "a grandmother beating the grandchildren at a mobile game", "an auto rickshaw driver who sings so well the passengers forget to get down",
    "a little boy trying to carry a watermelon bigger than himself"];
  const SLOTS = [5, 16, 11, 20, 8, 13];       // India time: the first video at 5 AM, the second at 4 PM, …
  const CSLOTS = [12, 19, 15, 21, 10, 17, 9, 14, 18, 20];    // funny cartoons
  const OTHERS = ["kids", "health", "farming"];
  function catFor(n) {
    if (n === 0) return "devotional";
    const d = Math.floor(Date.parse(today()) / 86400e3);
    return OTHERS[(d + n) % OTHERS.length];
  }
  function themeFor(cat) {
    const used = db.used[cat] || [];
    const list = THEMES[cat].filter(t => !used.includes(t));
    const t = (list.length ? list : THEMES[cat])[Math.floor(Math.random() * (list.length || THEMES[cat].length))];
    db.used[cat] = [...used, t].slice(-(THEMES[cat].length - 1));
    return t;
  }

  // ---------- the script: what the video shows, and the Telugu words on it ----------
  const SYSTEM = (cat, person) => cat === "cartoon" ? CARTOON_SYSTEM(person) : `You plan one short video for the "Media" page of BigD AI, a Telugu app for families in Andhra Pradesh and Telangana. An AI video model makes it: ${SECONDS()} seconds, vertical (9:16). It is clearly labelled "AI-made".
Section: ${media.CATS[cat].en}.
Rules: no real people, no famous people, no politicians; no faces of gods or goddesses and no idols up close (show temples, lamps, flowers, bells, or devotees from behind or far away); no text, letters or logos in the video; nothing scary or sad for children. For health and farming, show only simple, safe, well-known good habits (no medicines, no chemicals, no doses, no cures).
"prompt": an English description for the video model: one continuous shot, what we see, slow camera movement, light and colours, the style (realistic; for kids: a cute, colourful 3D animation), and the sound: gentle music and natural sounds only, no speech, no singing.
"title": a Telugu title, at most 10 words. "lines": 2 or 3 short Telugu lines, at most 12 words each, shown on the video: for kids, the story in short and its moral; for health or farming, the simple tip; for devotional, a gentle morning blessing.
Reply only with JSON: {"prompt": "...", "title": "...", "lines": ["...", "..."], "mood": one of ${media.MOODS.join(", ")}, "issue": one of ${media.ISSUES.join(", ")}}`;
  const CARTOON_SYSTEM = (person) => `You plan one funny cartoon short for the "Media" page of BigD AI, a Telugu app for families in Andhra Pradesh and Telangana (children watch too). An AI video model makes it: ${CSECONDS()} seconds, vertical (9:16), clearly labelled "AI-made".
Style: a bright, colourful 2D cartoon like the popular funny cartoon shorts: expressive faces, simple backgrounds from Telugu everyday life (village, town, home, school), 3 to 5 quick shots that tell a tiny story with a funny, kind ending. Sound: cartoon sound effects and light music, no speech, no singing. No text, letters or logos in the video.
Be kind: never make fun of women or men, any caste, religion, region, colour, body or disability, or any real or famous person; no politics, no violence, nothing scary or adult.${person ? `
The idea comes from a person using the app. If it is unkind, unsafe, about real or famous people, or not for children, set "skip" to true.` : ""}
"prompt": the English description for the video model, shot by shot, ending with the funny moment. "title": a Telugu title shown on top, at most 9 words, that makes people smile. "lines": 1 short Telugu line (at most 12 words) shown at the end, the punchline or a kind smile.
Reply only with JSON: {"prompt": "...", "title": "...", "lines": ["..."], "mood": "funny", "issue": one of ${media.ISSUES.join(", ")}, "skip": false}`;
  async function script(cat, theme, person) {
    let masa = ""; try { masa = panchang.panchang(today()).masa; } catch (e) { }
    const ask = person ? `The person's idea: «${theme}»` : `Idea: ${theme}.${cat === "devotional" && masa ? ` This month is ${masa} masam in the Telugu calendar.` : ""}`;
    let j = null;
    if (API_KEY) {
      try {
        const data = await ai({ model: MODEL, max_tokens: 900, system: SYSTEM(cat, person), messages: [{ role: "user", content: ask }] }, { tries: 1, backup: true });
        const text = (data.content || []).filter(c => c.type === "text").map(c => c.text).join("");
        j = JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1));
      } catch (e) { j = null; }
    }
    if (!j && ownBrain) {
      j = await ownBrain.writeJson({ system: SYSTEM(cat, person), prompt: ask, maxTokens: 600, schema: { type: "object", required: ["prompt", "title", "lines", "mood", "issue"], properties: { skip: { type: "boolean" },
        prompt: { type: "string" }, title: { type: "string" }, lines: { type: "array", items: { type: "string" } }, mood: { type: "string", enum: media.MOODS }, issue: { type: "string", enum: media.ISSUES } } } });
    }
    if (j && j.skip) throw Object.assign(new Error("Sorry, the puppy can't make that one. Please try a kind, happy idea."), { refused: true });
    if (!j || String(j.prompt || "").length < 20 || !j.title) throw new Error("couldn't plan the video");
    return { prompt: String(j.prompt).slice(0, 1500), title: String(j.title).trim().slice(0, 100), lines: (Array.isArray(j.lines) ? j.lines : []).map(l => String(l).trim().slice(0, 90)).filter(Boolean).slice(0, 3),
      mood: media.MOODS.includes(j.mood) ? j.mood : (cat === "devotional" ? "devotional" : cat === "kids" ? "happy" : cat === "cartoon" ? "funny" : "useful"),
      issue: media.ISSUES.includes(j.issue) ? j.issue : ({ devotional: "festivals", kids: "children", health: "health", farming: "farming" })[cat] };
  }

  // ---------- Gemini Omni: make the video ----------
  async function call(method, p, body) {
    const r = await fetch(BASE() + p, { method, headers: { "x-goog-api-key": KEY(), "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(200000) });
    const text = await r.text();
    let json = {}; try { json = JSON.parse(text); } catch (e) { }
    return { ok: r.ok, status: r.status, json, text };
  }
  function findVideo(o, depth = 0) {
    if (!o || typeof o !== "object" || depth > 10) return null;
    if ((o.type === "video" || /^video\//.test(o.mime_type || o.mimeType || "")) && (o.data || o.uri)) return { data: o.data, uri: o.uri };
    for (const v of Array.isArray(o) ? o : Object.values(o)) { const f = findVideo(v, depth + 1); if (f) return f; }
    return null;
  }
  const failed = j => /fail|cancel|error|block/i.test(String(j.status || j.state || ""));
  async function generate(prompt, secs = SECONDS()) {
    const fmt = { type: "video", aspect_ratio: "9:16", resolution: "720p", duration: secs + "s", delivery: "uri" };
    let r = await call("POST", "/v1beta/interactions", { model: VMODEL(), input: prompt, response_format: fmt });
    if (!r.ok && r.status === 400 && /duration/i.test(r.text)) { delete fmt.duration; r = await call("POST", "/v1beta/interactions", { model: VMODEL(), input: prompt, response_format: fmt }); }
    if (!r.ok) throw Object.assign(new Error("Gemini: " + ((r.json.error && r.json.error.message) || r.status)), { status: r.status });
    db.spent = Math.round((db.spent + secs * RATE) * 100) / 100; save();     // counted as soon as Google takes the job
    let j = r.json;
    const id = j.id || (j.name ? String(j.name).split("/").pop() : "");
    for (let i = 0; i < 60 && !findVideo(j) && !failed(j); i++) {        // up to 10 minutes
      await sleep(Number(process.env.AI_VIDEO_POLL_MS || 10000));
      if (!id) break;
      const g = await call("GET", `/v1beta/interactions/${encodeURIComponent(id)}`);
      if (g.ok) j = g.json;
    }
    if (failed(j)) throw new Error("Gemini couldn't make it: " + String((j.error && j.error.message) || j.status || "").slice(0, 120));
    const v = findVideo(j);
    if (!v) throw new Error("Gemini: no video came back");
    if (v.data) return Buffer.from(v.data, "base64");
    const m = String(v.uri).match(/files\/([\w-]+)/);
    let url = v.uri;
    if (m && (/^files\//.test(v.uri) || /generativelanguage\.googleapis\.com/.test(v.uri))) {
      for (let i = 0; i < 30; i++) {
        const f = await call("GET", `/v1beta/files/${m[1]}`);
        const state = String((f.json && f.json.state) || "ACTIVE");
        if (state === "ACTIVE") break;
        if (state === "FAILED") throw new Error("Gemini: the video file failed");
        await sleep(Number(process.env.AI_VIDEO_POLL_MS || 5000));
      }
      url = `${BASE()}/v1beta/files/${m[1]}:download?alt=media`;
    }
    const d = await fetch(url, { headers: /generativelanguage|^http:\/\/127\./.test(url) ? { "x-goog-api-key": KEY() } : {}, signal: AbortSignal.timeout(180000) });
    if (!d.ok) throw new Error("video download said " + d.status);
    const buf = Buffer.from(await d.arrayBuffer());
    if (buf.length < 20000) throw new Error("the video came back empty");
    return buf;
  }

  // ---------- our finishing: Telugu words, "AI-made" label, the voice ----------
  const run = (cmd, args, ms = 5 * 60e3) => new Promise((res, rej) => execFile(cmd, args, { timeout: ms, maxBuffer: 4e6 }, (e, out, err) => e ? rej(Object.assign(e, { stderr: err })) : res(out)));
  const seconds = async f => Number(await run(process.env.FFPROBE || "ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f], 30000)) || 0;
  const esc = t => String(t).replace(/\\/g, "＼").replace(/[{}]/g, "").replace(/\n/g, "\\N");
  const ts = s => { const cs = Math.max(0, Math.round(s * 100)); return `${Math.floor(cs / 360000)}:${String(Math.floor(cs / 6000) % 60).padStart(2, "0")}:${String(Math.floor(cs / 100) % 60).padStart(2, "0")}.${String(cs % 100).padStart(2, "0")}`; };
  async function finish(raw, out, poster, s) {
    let vo = "", voSec = 0;
    if (voice && voice.enabled) { try { vo = (await voice.speak([s.title, ...s.lines].join(". "), "panda", "calm")).file; voSec = await seconds(vo); } catch (e) { vo = ""; } }
    const vSec = (await seconds(raw)) || SECONDS();
    const total = Math.min(30, Math.max(vSec, voSec ? voSec + 0.6 : 0));
    const ass = out + ".ass";
    const per = total / Math.max(1, s.lines.length);
    fs.writeFileSync(ass, `[Script Info]
ScriptType: v4.00+
PlayResX: 720
PlayResY: 1280
WrapStyle: 0

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Title,Hind Guntur,50,&H00FFFFFF,&H00FFFFFF,&H00000000,&H90000000,1,0,0,0,100,100,0,0,1,3.2,2,8,40,40,110,1
Style: Line,Hind Guntur,42,&H00FFFFFF,&H00FFFFFF,&H00000000,&H90000000,1,0,0,0,100,100,0,0,3,2,0,2,50,50,150,1
Style: Label,Hind Guntur,26,&H00FFFFFF,&H00FFFFFF,&H50000000,&H70000000,1,0,0,0,100,100,0,0,3,1,0,9,24,24,36,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 2,${ts(0)},${ts(total)},Label,,0,0,0,,🤖 AI-made · BigD AI
Dialogue: 1,${ts(0.3)},${ts(total)},Title,,0,0,0,,{\\fad(400,300)}${esc(s.title)}
${s.lines.map((l, i) => `Dialogue: 1,${ts(i * per + 0.4)},${ts(Math.min(total, (i + 1) * per))},Line,,0,0,0,,{\\fad(300,200)}${esc(l)}`).join("\n")}
`);
    const q = p => p.replace(/[:\\']/g, m => "\\" + m);
    const args = ["-y", "-v", "error", "-i", raw];
    if (vo) args.push("-i", vo);
    const pad = total > vSec + 0.05 ? `,tpad=stop_mode=clone:stop_duration=${(total - vSec).toFixed(2)}` : "";
    let fc = `[0:v]scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280,fps=30,setsar=1${pad},ass=${q(ass)}:fontsdir=${q(FONTS)}:shaping=complex,format=yuv420p[v]`;
    // the video's own music and sounds, softer under the voice
    fc += vo ? `;[0:a]volume=0.35,apad[m];[1:a]volume=1.0,apad[s];[m][s]amix=inputs=2:duration=longest:dropout_transition=0,atrim=0:${total.toFixed(2)}[a]` : `;[0:a]apad,atrim=0:${total.toFixed(2)}[a]`;
    const go = async (withAudio) => run(FFMPEG, [...args, "-filter_complex", withAudio ? fc : fc.split(";")[0], "-map", "[v]", ...(withAudio ? ["-map", "[a]", "-c:a", "aac", "-b:a", "96k"] : []),
      "-t", total.toFixed(2), "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out + ".part.mp4"]);
    try { await go(true); } catch (e) { await go(false); }      // (a video that came without sound)
    fs.renameSync(out + ".part.mp4", out);
    await run(FFMPEG, ["-y", "-v", "error", "-ss", "1", "-i", out, "-frames:v", "1", "-q:v", "4", poster]).catch(() => {});
    try { fs.unlinkSync(ass); } catch (e) { }
    return total;
  }

  // ---------- one video, from idea to Media post ----------
  let busy = false;
  /** One AI video. opts.idea: a person's own idea (then it is not posted in Media; the caller gets the files). */
  async function makeOne(cat, opts = {}) {
    roll();
    for (let i = 0; busy && i < 150; i++) await sleep(4000);       // one at a time
    if (busy) throw new Error("already making one");
    if (!KEY()) throw new Error("GEMINI_API_KEY is not set");
    const secs = cat === "cartoon" ? CSECONDS() : SECONDS();
    if (db.spent + secs * RATE > CAP()) throw new Error(`this month's limit is reached ($${db.spent} of $${CAP()})`);
    busy = true;
    const id = db.next++; save();
    const raw = path.join(DIR, `${id}.raw.mp4`), out = path.join(DIR, `${id}.mp4`), poster = path.join(DIR, `${id}.jpg`);
    try {
      const theme = opts.idea || themeFor(cat);
      const s = await script(cat, theme, !!opts.idea);
      fs.writeFileSync(raw, await generate(s.prompt, secs));
      const dur = await finish(raw, out, poster, s);
      try { fs.unlinkSync(raw); } catch (e) { }
      if (opts.idea) { db.lastError = ""; save(); return { file: out, poster, title: s.title, lines: s.lines, dur: Math.round(dur) }; }
      const section = cat === "cartoon" ? "fun" : cat;
      const item = media.addItem({ cat: section, title: "🤖 " + s.title, body: s.lines.join("\n") + "\n\n🤖 AI-made video · AI తో తయారైన వీడియో", emoji: "🤖", mood: s.mood, issue: s.issue,
        kind: "aivideo", writer: "gemini", by: "auto", img: "", key: "aivideo:" + id,
        video: { url: `/media/ai/${id}.mp4`, poster: fs.existsSync(poster) ? `/media/ai/${id}.jpg` : "", dur: Math.round(dur), w: 720, h: 1280 } });
      db.list = [{ id, at: Date.now(), cat, title: s.title, theme, item: item ? item.id : 0 }, ...db.list].slice(0, 60);
      if (cat === "cartoon") db.cartoons = (db.cartoons || 0) + 1; else db.made++;
      db.lastError = ""; save();
      console.log(`AI video: made #${id} (${cat}) · this month about $${db.spent}`);
      return { id, title: s.title };
    } catch (e) {
      for (const f of [raw, out, poster]) try { fs.unlinkSync(f); } catch (x) { }
      db.lastError = String(e.message || e).slice(0, 200);
      if (!opts.idea) { db.fails++; db.waitUntil = Date.now() + 60 * 60e3; }
      save();
      console.error("AI video failed:", db.lastError);
      throw e;
    } finally { busy = false; }
  }

  // ---------- every day, by itself ----------
  async function tick() {
    if (!ON() || busy) return;
    roll();
    const n = db.made;
    if (n >= DAILY() || db.fails >= 3 || Date.now() < db.waitUntil) return;
    if (ist().getUTCHours() < SLOTS[n]) return;
    if (db.spent + SECONDS() * RATE > CAP()) { if (db.lastError.indexOf("limit") < 0) { db.lastError = `This month's limit is reached ($${db.spent} of $${CAP()}). Videos start again next month.`; save(); } return; }
    await makeOne(catFor(n)).catch(() => {});
  }
  async function cartoonTick() {
    if (!ON() || busy) return;
    roll();
    const n = db.cartoons || 0;
    if (n >= CARTOONS() || db.fails >= 3 || Date.now() < db.waitUntil || ist().getUTCHours() < CSLOTS[Math.min(n, CSLOTS.length - 1)]) return;
    if (db.spent + CSECONDS() * RATE > CAP()) return;
    await makeOne("cartoon").catch(() => {});
  }
  setTimeout(() => cartoonTick(), 7 * 60_000).unref();
  setInterval(() => cartoonTick(), 10 * 60_000).unref();

  // ---------- people's own ideas (the cartoon maker in the app): free for now, with daily limits ----------
  const forPeople = {
    /** How many AI cartoons this person (and everyone) can still make today. */
    left(dev) {
      roll();
      // (people's own ideas don't depend on MEDIA_AUTO, which is only about posting by itself)
      if (!KEY() || String(process.env.AI_VIDEOS || "true") === "false" || db.spent + CSECONDS() * RATE > CAP()) return { all: 0, you: 0 };
      return { all: Math.max(0, PUBLIC_DAY() - (db.pubDay || 0)), you: Math.max(0, PUBLIC_EACH() - ((db.pubEach || {})[dev] || 0)) };
    },
    take(dev) { roll(); db.pubDay = (db.pubDay || 0) + 1; db.pubEach = db.pubEach || {}; db.pubEach[dev] = (db.pubEach[dev] || 0) + 1; save(); },
    refund(dev) { db.pubDay = Math.max(0, (db.pubDay || 1) - 1); if (db.pubEach && db.pubEach[dev]) db.pubEach[dev]--; save(); },
    make: (idea, type) => makeOne("cartoon", { idea: `${idea || "a funny surprise"}${type && type !== "joke" ? ` (kind: ${type})` : ""}` })
  };
  setTimeout(() => tick(), 5 * 60_000).unref();
  setInterval(() => tick(), 10 * 60_000).unref();
  // videos are kept 45 days
  setInterval(() => {
    for (const f of fs.readdirSync(DIR)) { const p = path.join(DIR, f); try { if (Date.now() - fs.statSync(p).mtimeMs > 45 * 86400e3) fs.unlinkSync(p); } catch (e) { } }
  }, 12 * 3600e3).unref();

  // ---------- GET /media/ai/<id>.mp4 | .jpg (with Range, for players) ----------
  function handle(req, res, url) {
    const m = url.pathname.match(/^\/media\/ai\/(\d+)\.(mp4|jpg)$/);
    if (!m || req.method !== "GET") return false;
    const f = path.join(DIR, `${Number(m[1])}.${m[2]}`);
    if (!fs.existsSync(f)) { res.writeHead(404); res.end(); return true; }
    const size = fs.statSync(f).size, type = m[2] === "mp4" ? "video/mp4" : "image/jpeg";
    const head = { "Content-Type": type, "Cache-Control": "public, max-age=86400", "Accept-Ranges": "bytes" };
    const r = String(req.headers.range || "").match(/^bytes=(\d*)-(\d*)$/);
    if (r && m[2] === "mp4") {
      let start = r[1] ? Number(r[1]) : size - Number(r[2]), end = r[1] && r[2] ? Number(r[2]) : size - 1;
      end = Math.min(end, size - 1);
      if (start >= size || start > end || start < 0) { res.writeHead(416, { "Content-Range": `bytes */${size}` }); res.end(); return true; }
      res.writeHead(206, Object.assign(head, { "Content-Range": `bytes ${start}-${end}/${size}`, "Content-Length": end - start + 1 }));
      fs.createReadStream(f, { start, end }).pipe(res);
    } else { res.writeHead(200, Object.assign(head, { "Content-Length": size })); fs.createReadStream(f).pipe(res); }
    return true;
  }

  function status() {
    roll();
    return { on: ON(), key: !!KEY(), model: VMODEL(), daily: DAILY(), madeToday: db.made, monthSpentUsd: db.spent, monthLimitUsd: CAP(), seconds: SECONDS(),
      costEachUsd: Math.round(SECONDS() * RATE * 100) / 100, busy, lastError: db.lastError, recent: db.list.slice(0, 12),
      cartoons: CARTOONS(), cartoonsToday: db.cartoons || 0, cartoonSeconds: CSECONDS(), cartoonCostUsd: Math.round(CSECONDS() * RATE * 100) / 100,
      publicDay: PUBLIC_DAY(), publicEach: PUBLIC_EACH(), publicToday: db.pubDay || 0 };
  }
  function remove(id) {
    const x = db.list.find(v => v.id === Number(id));
    if (!x) return false;
    if (x.item) media.removeItem(x.item);
    for (const ext of ["mp4", "jpg"]) try { fs.unlinkSync(path.join(DIR, `${x.id}.${ext}`)); } catch (e) { }
    db.list = db.list.filter(v => v !== x); save();
    return true;
  }
  /** The owner changes the limits on the Teach page. */
  function settings(o) {
    db.settings = db.settings || {};
    for (const k of ["monthlyUsd", "daily", "cartoons", "publicDay", "publicEach", "seconds", "cartoonSeconds"]) if (o[k] !== undefined && o[k] !== "" && isFinite(Number(o[k]))) db.settings[k] = Number(o[k]);
    save(); return status();
  }
  return { handle, status, settings, makeOne, remove, tick, forPeople, _test: { generate, findVideo, finish, script, db: () => db } };
};
