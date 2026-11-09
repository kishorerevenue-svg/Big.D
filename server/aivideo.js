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
  const DAILY = () => Math.max(0, Math.min(6, Number(process.env.AI_VIDEOS_DAILY ?? 2)));
  const CAP = () => Math.max(0, Number(process.env.AI_VIDEO_MONTHLY_USD ?? 35));
  const SECONDS = () => Math.min(15, Math.max(4, Math.round(Number(process.env.AI_VIDEO_SECONDS || 8))));
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
    if (db.day !== today()) { db.day = today(); db.made = 0; db.fails = 0; }
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
  const SLOTS = [5, 16, 11, 20, 8, 13];       // India time: the first video at 5 AM, the second at 4 PM, …
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
  const SYSTEM = (cat) => `You plan one short video for the "Media" page of BigD AI, a Telugu app for families in Andhra Pradesh and Telangana. An AI video model makes it: ${SECONDS()} seconds, vertical (9:16). It is clearly labelled "AI-made".
Section: ${media.CATS[cat].en}.
Rules: no real people, no famous people, no politicians; no faces of gods or goddesses and no idols up close (show temples, lamps, flowers, bells, or devotees from behind or far away); no text, letters or logos in the video; nothing scary or sad for children. For health and farming, show only simple, safe, well-known good habits (no medicines, no chemicals, no doses, no cures).
"prompt": an English description for the video model: one continuous shot, what we see, slow camera movement, light and colours, the style (realistic; for kids: a cute, colourful 3D animation), and the sound: gentle music and natural sounds only, no speech, no singing.
"title": a Telugu title, at most 10 words. "lines": 2 or 3 short Telugu lines, at most 12 words each, shown on the video: for kids, the story in short and its moral; for health or farming, the simple tip; for devotional, a gentle morning blessing.
Reply only with JSON: {"prompt": "...", "title": "...", "lines": ["...", "..."], "mood": one of ${media.MOODS.join(", ")}, "issue": one of ${media.ISSUES.join(", ")}}`;
  async function script(cat, theme) {
    let masa = ""; try { masa = panchang.panchang(today()).masa; } catch (e) { }
    const ask = `Idea: ${theme}.${cat === "devotional" && masa ? ` This month is ${masa} masam in the Telugu calendar.` : ""}`;
    let j = null;
    if (API_KEY) {
      try {
        const data = await ai({ model: MODEL, max_tokens: 900, system: SYSTEM(cat), messages: [{ role: "user", content: ask }] }, { tries: 1, backup: true });
        const text = (data.content || []).filter(c => c.type === "text").map(c => c.text).join("");
        j = JSON.parse(text.slice(text.indexOf("{"), text.lastIndexOf("}") + 1));
      } catch (e) { j = null; }
    }
    if (!j && ownBrain) {
      j = await ownBrain.writeJson({ system: SYSTEM(cat), prompt: ask, maxTokens: 600, schema: { type: "object", required: ["prompt", "title", "lines", "mood", "issue"], properties: {
        prompt: { type: "string" }, title: { type: "string" }, lines: { type: "array", items: { type: "string" } }, mood: { type: "string", enum: media.MOODS }, issue: { type: "string", enum: media.ISSUES } } } });
    }
    if (!j || String(j.prompt || "").length < 20 || !j.title) throw new Error("couldn't plan the video");
    return { prompt: String(j.prompt).slice(0, 1500), title: String(j.title).trim().slice(0, 100), lines: (Array.isArray(j.lines) ? j.lines : []).map(l => String(l).trim().slice(0, 90)).filter(Boolean).slice(0, 3),
      mood: media.MOODS.includes(j.mood) ? j.mood : (cat === "devotional" ? "devotional" : cat === "kids" ? "happy" : "useful"),
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
  async function generate(prompt) {
    const fmt = { type: "video", aspect_ratio: "9:16", resolution: "720p", duration: SECONDS() + "s", delivery: "uri" };
    let r = await call("POST", "/v1beta/interactions", { model: VMODEL(), input: prompt, response_format: fmt });
    if (!r.ok && r.status === 400 && /duration/i.test(r.text)) { delete fmt.duration; r = await call("POST", "/v1beta/interactions", { model: VMODEL(), input: prompt, response_format: fmt }); }
    if (!r.ok) throw Object.assign(new Error("Gemini: " + ((r.json.error && r.json.error.message) || r.status)), { status: r.status });
    db.spent = Math.round((db.spent + SECONDS() * RATE) * 100) / 100; save();     // counted as soon as Google takes the job
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
  async function makeOne(cat) {
    roll();
    if (busy) throw new Error("already making one");
    if (!KEY()) throw new Error("GEMINI_API_KEY is not set");
    if (db.spent + SECONDS() * RATE > CAP()) throw new Error(`this month's limit is reached ($${db.spent} of $${CAP()})`);
    busy = true;
    const id = db.next++; save();
    const raw = path.join(DIR, `${id}.raw.mp4`), out = path.join(DIR, `${id}.mp4`), poster = path.join(DIR, `${id}.jpg`);
    try {
      const theme = themeFor(cat);
      const s = await script(cat, theme);
      fs.writeFileSync(raw, await generate(s.prompt));
      const dur = await finish(raw, out, poster, s);
      try { fs.unlinkSync(raw); } catch (e) { }
      const item = media.addItem({ cat, title: "🤖 " + s.title, body: s.lines.join("\n") + "\n\n🤖 AI-made video · AI తో తయారైన వీడియో", emoji: "🤖", mood: s.mood, issue: s.issue,
        kind: "aivideo", writer: "gemini", by: "auto", img: "", key: "aivideo:" + id,
        video: { url: `/media/ai/${id}.mp4`, poster: fs.existsSync(poster) ? `/media/ai/${id}.jpg` : "", dur: Math.round(dur), w: 720, h: 1280 } });
      db.list = [{ id, at: Date.now(), cat, title: s.title, theme, item: item ? item.id : 0 }, ...db.list].slice(0, 60);
      db.made++; db.lastError = ""; save();
      console.log(`AI video: made #${id} (${cat}) · this month about $${db.spent}`);
      return { id, title: s.title };
    } catch (e) {
      for (const f of [raw, out, poster]) try { fs.unlinkSync(f); } catch (x) { }
      db.lastError = String(e.message || e).slice(0, 200); db.fails++; db.waitUntil = Date.now() + 60 * 60e3; save();
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
      costEachUsd: Math.round(SECONDS() * RATE * 100) / 100, busy, lastError: db.lastError, recent: db.list.slice(0, 12) };
  }
  function remove(id) {
    const x = db.list.find(v => v.id === Number(id));
    if (!x) return false;
    if (x.item) media.removeItem(x.item);
    for (const ext of ["mp4", "jpg"]) try { fs.unlinkSync(path.join(DIR, `${x.id}.${ext}`)); } catch (e) { }
    db.list = db.list.filter(v => v !== x); save();
    return true;
  }
  return { handle, status, makeOne, remove, tick, _test: { generate, findVideo, finish, script, db: () => db } };
};
