// News videos to share (WhatsApp status and so on): a short vertical MP4 for new Media posts.
// Scenes: the post's own picture slowly zooming, then (with PEXELS_API_KEY, free) 2 or 3 moving video clips that fit
// the story from Pexels, softly fading into each other, with credit to the people who filmed them; without the key,
// the picture alone (or a moving colour background). The headline and the news come as text (drawn with the
// subtitles engine and "complex shaping", so Telugu letters join correctly), music made for Habit AI that fits the
// post's mood (serious, happy, inspiring, devotional, kids), and the news read aloud when the natural voice
// (GOOGLE_TTS_KEY) is set up. One video at a time in the background, at most REELS_DAILY a day; videos are deleted
// after 10 days.
const fs = require("fs");
const path = require("path");
const dns = require("dns").promises;
const { execFile } = require("child_process");
const { privateIp } = require("./preview.js");

module.exports = function makeReels({ DATA_DIR, media, voice }) {
  const DIR = path.join(DATA_DIR, "reels");
  const TMP = path.join(DIR, "tmp");
  fs.mkdirSync(TMP, { recursive: true });
  const FONTS = path.join(__dirname, "fonts");
  const MUSIC = path.join(__dirname, "assets", "reel-music.m4a");
  const FFMPEG = process.env.FFMPEG_PATH || "ffmpeg";
  const FFPROBE = process.env.FFPROBE_PATH || "ffprobe";
  const DAILY = Number(process.env.REELS_DAILY || 16);
  const PEXELS = () => process.env.PEXELS_API_KEY || "";
  const PEXELS_API = process.env.PEXELS_API_BASE || "https://api.pexels.com";
  const CLIPS = path.join(DIR, "clips");
  fs.mkdirSync(CLIPS, { recursive: true });
  const XF = 0.6;     // seconds of soft fade between two scenes
  const CATS = String(process.env.REELS_CATS || "news,jobs,farming,health,devotional,movies,kids,realestate,facts").split(",").map(s => s.trim());
  const KEEP_MS = 10 * 86400e3;
  const W = 720, H = 1280, FPS = 30;
  const COLOR = { news: "#3B82F6", movies: "#F97316", jobs: "#6366F1", devotional: "#FB923C", kids: "#F472B6", health: "#14B8A6",
    farming: "#65A30D", realestate: "#10B981", crypto: "#F59E0B" };
  const STATE = path.join(DIR, "state.json");
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);
  let st = { day: "", made: 0, tries: {}, px: {}, used: [] };
  try { st = Object.assign(st, JSON.parse(fs.readFileSync(STATE, "utf8"))); } catch (e) { }
  const saveSt = () => { try { fs.writeFileSync(STATE, JSON.stringify(st)); } catch (e) { } };
  let ok = null, busy = false, lastError = "";

  function run(bin, args, ms) {
    return new Promise((resolve, reject) => execFile(bin, args, { timeout: ms, maxBuffer: 8 << 20 }, (err, so, se) =>
      err ? reject(Object.assign(err, { stderr: String(se || "").slice(-800) })) : resolve(String(so))));
  }

  /** True when this server's ffmpeg can draw the text (libass) and zoom pictures. */
  async function available() {
    if (ok !== null) return ok;
    try {
      const f = await run(FFMPEG, ["-hide_banner", "-filters"], 15000);
      ok = /\sass\s/.test(f) && /\szoompan\s/.test(f) && /\sgradients\s/.test(f) && fs.existsSync(path.join(FONTS, "HindGuntur-Regular.ttf")) && fs.existsSync(MUSIC);
    } catch (e) { ok = false; }
    setTimeout(() => { ok = null; }, 30 * 60e3).unref();
    return ok;
  }

  // ---------- music that fits the post ----------
  const MOOD_MUSIC = { serious: "music-serious.m4a", happy: "music-happy.m4a", inspiring: "music-inspiring.m4a", devotional: "music-devotional.m4a",
    calm: "music-devotional.m4a", sad: "music-devotional.m4a", funny: "music-kids.m4a", useful: "reel-music.m4a" };
  function musicFor(item) {
    const f = item.cat === "kids" ? "music-kids.m4a" : MOOD_MUSIC[item.mood] || (item.cat === "devotional" ? "music-devotional.m4a" : item.cat === "news" ? "music-serious.m4a" : "reel-music.m4a");
    const p = path.join(__dirname, "assets", f);
    return fs.existsSync(p) ? p : MUSIC;
  }

  // ---------- moving scenes from Pexels (free stock videos; their licence allows this, and we credit the makers) ----------
  const SCENES = {
    issue: { farming: ["farmer field", "paddy field", "tractor farm"], water: ["river water", "water tap"], prices: ["vegetable market", "market stall"], jobs: ["office work", "students exam"],
      education: ["students classroom", "books study"], health: ["healthy food", "doctor hospital", "yoga"], women: ["women working", "women smiling"], children: ["children playing", "school children"],
      safety: ["police lights", "traffic road"], roads: ["highway traffic", "road construction"], power: ["power lines", "electricity"], schemes: ["village india", "government building"],
      weather: ["rain clouds", "storm clouds", "sunny sky"], festivals: ["diya lamp", "temple", "festival lights"], cinema: ["cinema theatre", "film camera", "movie set"],
      technology: ["smartphone", "technology"], sports: ["cricket", "stadium"], money: ["money coins", "bank"], environment: ["forest", "nature"], politics: ["parliament building", "city skyline"], other: ["india city", "people walking"] },
    cat: { news: ["india city", "newspaper"], movies: ["cinema theatre", "film camera"], kids: ["children playing", "colorful balloons"], crypto: ["bitcoin", "stock chart"],
      realestate: ["apartment buildings", "house construction"], jobs: ["office work", "students exam"], devotional: ["temple", "diya lamp", "prayer"], farming: ["farmer field", "paddy field"], health: ["healthy food", "doctor"] }
  };
  const queries = item => [...new Set([item.scene, ...(SCENES.issue[item.issue] || []), ...(SCENES.cat[item.cat] || [])].filter(Boolean).map(x => String(x).toLowerCase().trim().slice(0, 60)))];
  /** A landscape MP4 of about 1280x720 (not 4K: smaller to download, and the band is 720 wide). */
  function pickFile(files) {
    const mp4 = (files || []).filter(f => /mp4/.test(f.file_type || "") && f.width && f.height && f.width >= f.height && f.width <= 1920 && /^https?:\/\//.test(f.link || ""));
    return mp4.sort((a, b) => Math.abs(a.width - 1280) - Math.abs(b.width - 1280))[0] || null;
  }
  async function pexels(q) {
    const c = st.px[q];
    if (c && Date.now() - c.at < 3 * 86400e3) return c.list;
    const r = await fetch(`${PEXELS_API}/videos/search?query=${encodeURIComponent(q)}&orientation=landscape&size=medium&per_page=15`,
      { headers: { Authorization: PEXELS() }, signal: AbortSignal.timeout(15000) });
    if (!r.ok) throw new Error("Pexels said " + r.status);
    const j = await r.json();
    const list = (j.videos || []).filter(v => (v.duration || 0) >= 4).map(v => { const f = pickFile(v.video_files); return f && { id: v.id, user: String((v.user || {}).name || "").slice(0, 40), link: f.link }; }).filter(Boolean);
    st.px[q] = { at: Date.now(), list };
    for (const k of Object.keys(st.px)) if (Date.now() - st.px[k].at > 7 * 86400e3) delete st.px[k];
    saveSt();
    return list;
  }
  /** Downloads a clip once (kept 10 days for other videos). */
  async function clip(v) {
    const f = path.join(CLIPS, `${Number(v.id)}.mp4`);
    if (fs.existsSync(f)) return f;
    let url = v.link;
    for (let hop = 0; hop < 4 && url; hop++) {
      if (!(await publicUrl(url))) throw new Error("not a public address");
      const r = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(60000) });
      if (r.status >= 300 && r.status < 400 && r.headers.get("location")) { url = new URL(r.headers.get("location"), url).toString(); continue; }
      if (!r.ok) throw new Error("clip download said " + r.status);
      if (Number(r.headers.get("content-length") || 0) > 45e6) throw new Error("clip too big");
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length < 20000 || buf.length > 45e6) throw new Error("clip size");
      fs.writeFileSync(f + ".part", buf); fs.renameSync(f + ".part", f);
      return f;
    }
    throw new Error("too many redirects");
  }
  /** Up to [want] clips that fit the post, not the same ones as the last videos. */
  async function scenes(item, want) {
    if (!PEXELS() || want < 1) return [];
    const out = [], seen = [];
    for (const q of queries(item).slice(0, 3)) {
      let list = [];
      try { list = await pexels(q); } catch (e) { lastError = "Pexels: " + e.message; break; }
      for (const v of list) {
        if (!seen.some(x => x.id === v.id)) seen.push(v);
        if (out.length >= want) break;
        if (st.used.includes(v.id) || out.some(o => o.id === v.id)) continue;
        try { out.push({ ...v, file: await clip(v) }); } catch (e) { }
      }
      if (out.length >= want) break;
    }
    // not enough new ones: the ones used longest ago are fine again
    for (const v of seen.filter(x => !out.some(o => o.id === x.id)).sort((a, b) => st.used.indexOf(a.id) - st.used.indexOf(b.id))) {
      if (out.length >= want) break;
      try { out.push({ ...v, file: await clip(v) }); } catch (e) { }
    }
    if (out.length) { st.used = [...st.used, ...out.map(o => o.id)].slice(-80); saveSt(); }
    return out;
  }

  const file = id => path.join(DIR, `${Number(id)}.mp4`);
  const has = id => fs.existsSync(file(id));

  // ---------- the picture (only from public internet addresses, at most 8 MB) ----------
  async function publicUrl(u) {
    try {
      const url = new URL(u); if (!/^https?:$/.test(url.protocol)) return false;
      if (process.env.PREVIEW_ALLOW_LOCAL === "1") return true;   // tests only
      const ips = await dns.lookup(url.hostname, { all: true });
      return ips.length > 0 && ips.every(a => !privateIp(a.address));
    } catch (e) { return false; }
  }
  async function picture(u, out) {
    let url = u;
    for (let hop = 0; hop < 4 && url; hop++) {
      if (!(await publicUrl(url))) return false;
      let r;
      try { r = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(15000), headers: { "User-Agent": "Mozilla/5.0 (compatible; Habit-AI/1.0; +https://bigdai.tech)" } }); }
      catch (e) { return false; }
      if (r.status >= 300 && r.status < 400 && r.headers.get("location")) { url = new URL(r.headers.get("location"), url).toString(); continue; }
      if (!r.ok || !/^image\//i.test(r.headers.get("content-type") || "")) return false;
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length < 3000 || buf.length > 8 * 1024 * 1024) return false;
      fs.writeFileSync(out, buf); return true;
    }
    return false;
  }

  // ---------- the text, as an ASS subtitle file ----------
  const esc = s => String(s || "").replace(/\\/g, "/").replace(/\{/g, "(").replace(/\}/g, ")").replace(/\s+/g, " ").trim();
  const ts = s => { s = Math.max(0, s); const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, x = (s % 60).toFixed(2).padStart(5, "0"); return `${h}:${String(m).padStart(2, "0")}:${x}`; };
  const bgr = hex => { const h = String(hex || "#19A39A").replace("#", ""); return "&H00" + h.slice(4, 6) + h.slice(2, 4) + h.slice(0, 2); };

  /** Sentences grouped into pieces of at most [max] letters. */
  function pieces(text, max) {
    const parts = String(text || "").replace(/\s+/g, " ").trim().split(/(?<=[.!?।])\s+/).filter(Boolean);
    const out = []; let cur = "";
    for (const p of parts) {
      if (p.length > max) { if (cur) { out.push(cur); cur = ""; } for (let i = 0; i < p.length; i += max) out.push(p.slice(i, i + max)); continue; }
      if (cur && cur.length + 1 + p.length > max) { out.push(cur); cur = p; } else cur = cur ? cur + " " + p : p;
    }
    if (cur) out.push(cur);
    return out;
  }

  /** Builds the subtitle file; returns the video length in seconds. [credits]: one per scene ("" for none). */
  function writeAss(item, assFile, hasPic, voiceSec, credits = []) {
    const te = /[ఀ-౿]/.test(item.title + item.body);
    const cat = (media.CATS[item.cat] || {});
    const label = "Habit AI · " + (te ? (cat.te || "వార్తలు") : (cat.en || "News"));
    const t0 = esc(item.title);
    const title = t0.length > 110 ? t0.slice(0, 108).replace(/\s+\S*$/, "") + "…" : t0;
    const segs = pieces(esc(item.body).slice(0, 900), 90).slice(0, 8);
    // how long each piece stays: by its length (about 13 letters a second), or spread over the voice
    let lens = segs.map(s => Math.max(3.5, s.length / 12));
    const start = 1.6;
    if (voiceSec > 0) { const sum = lens.reduce((a, b) => a + b, 0) || 1; lens = lens.map(l => l / sum * Math.max(3.5 * segs.length, voiceSec - 0.8)); }
    let total = start + lens.reduce((a, b) => a + b, 0) + 3;
    if (voiceSec > 0) total = Math.max(total, voiceSec + 2.5);
    total = Math.min(Math.max(total, 10), 50);
    const titleLines = Math.min(4, Math.ceil(title.length / (te ? 18 : 22)) || 1);
    const titleTop = hasPic ? 600 : 300;
    const bodyTop = titleTop + titleLines * 84 + 40;
    const head = `[Script Info]
ScriptType: v4.00+
PlayResX: ${W}
PlayResY: ${H}
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Badge,Hind Guntur,40,&H00FFFFFF,&H00FFFFFF,${bgr(COLOR[item.cat])},${bgr(COLOR[item.cat])},-1,0,0,0,100,100,0,0,3,12,0,8,40,40,62,1
Style: Title,Hind Guntur,60,&H00FFFFFF,&H00FFFFFF,&H40000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,2,8,44,44,${titleTop},1
Style: Body,Hind Guntur,48,&H00F4F7F6,&H00FFFFFF,&H50000000,&H80000000,0,0,0,0,100,100,0,0,1,2.4,1.2,8,50,50,${bodyTop},1
Style: Source,Hind Guntur,32,&H20E6EFEE,&H00FFFFFF,&H50000000,&H80000000,0,0,0,0,100,100,0,0,1,1.6,0,2,40,40,150,1
Style: Foot,Hind Guntur,36,&H00FFFFFF,&H00FFFFFF,${bgr("#E8336D")},${bgr("#E8336D")},-1,0,0,0,100,100,0,0,3,11,0,2,40,40,64,1
Style: End,Hind Guntur,96,&H00FFFFFF,&H00FFFFFF,&H40000000,&H80000000,-1,0,0,0,100,100,0,0,1,4,3,5,40,40,0,1
Style: EndSub,Hind Guntur,44,&H00F4F7F6,&H00FFFFFF,&H40000000,&H80000000,0,0,0,0,100,100,0,0,1,2.4,1.2,5,40,40,0,1
Style: Credit,Hind Guntur,24,&H30FFFFFF,&H00FFFFFF,&H60000000,&H80000000,0,0,0,0,100,100,0,0,1,1.4,0,3,20,20,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
`;
    const ev = [];
    const endAt = total - 2.8;
    ev.push(`Dialogue: 1,${ts(0.2)},${ts(endAt)},Badge,,0,0,0,,{\\fad(300,200)}${esc(label)}`);
    ev.push(`Dialogue: 1,${ts(0.4)},${ts(endAt)},Title,,0,0,0,,{\\fad(400,200)}${title}`);
    let t = start;
    segs.forEach((s, i) => {
      const a = t, b = Math.min(endAt, t + lens[i]);
      if (b > a + 0.5) ev.push(`Dialogue: 1,${ts(a)},${ts(b)},Body,,0,0,0,,{\\fad(300,250)}${s}`);
      t += lens[i];
    });
    if (item.source) ev.push(`Dialogue: 1,${ts(0.4)},${ts(endAt)},Source,,0,0,0,,${esc((te ? "మూలం · Source: " : "Source: ") + item.source).slice(0, 80)}`);
    // who filmed each scene (bottom right of the picture band)
    const seg = total / Math.max(1, credits.length);
    credits.forEach((c, i) => { if (c) ev.push(`Dialogue: 1,${ts(i * seg + 0.3)},${ts(Math.min(endAt, (i + 1) * seg))},Credit,,0,0,0,,{\\pos(${W - 20},${150 + 404 - 10})}${esc(c).slice(0, 60)}`); });
    ev.push(`Dialogue: 1,${ts(0.2)},${ts(endAt)},Foot,,0,0,0,,${te ? "Habit AI యాప్‌లో మరిన్ని · bigdai.tech" : "More on the Habit AI app · bigdai.tech"}`);
    ev.push(`Dialogue: 1,${ts(endAt)},${ts(total)},Body,,0,0,0,,{\\fad(300,0)\\an7\\pos(0,0)\\bord0\\shad0\\1c&H2A1F0A&\\1a&H30&\\p1}m 0 0 l ${W} 0 ${W} ${H} 0 ${H}{\\p0}`);
    ev.push(`Dialogue: 2,${ts(endAt)},${ts(total)},End,,0,0,0,,{\\fad(300,0)\\pos(${W / 2},${H / 2 - 60})}Habit AI`);
    ev.push(`Dialogue: 2,${ts(endAt + 0.2)},${ts(total)},EndSub,,0,0,0,,{\\fad(300,0)\\pos(${W / 2},${H / 2 + 50})}${te ? "వార్తలు · వీడియోలు · సహాయం" : "News · videos · help"}`);
    ev.push(`Dialogue: 2,${ts(endAt + 0.2)},${ts(total)},EndSub,,0,0,0,,{\\fad(300,0)\\pos(${W / 2},${H / 2 + 120})}bigdai.tech`);
    fs.writeFileSync(assFile, head + ev.join("\n") + "\n");
    return total;
  }

  async function seconds(f) {
    try { return Number((await run(FFPROBE, ["-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", f], 20000)).trim()) || 0; } catch (e) { return 0; }
  }

  /** Makes the video for one Media post. */
  async function make(item) {
    const base = path.join(TMP, String(item.id));
    const pic = base + ".img", ass = base + ".ass", out = file(item.id), part = out + ".part.mp4";
    let hasPic = !!item.img && await picture(item.img, pic);
    const clips = await scenes(item, hasPic ? 2 : 3);
    const MUSIC_FILE = musicFor(item);
    let voiceFile = "", voiceSec = 0;
    if (voice && voice.enabled) {
      try { voiceFile = (await voice.speak(item.title + ". " + item.body, "panda", "calm")).file; voiceSec = await seconds(voiceFile); } catch (e) { voiceFile = ""; }
    }
    // scenes: [the picture] + clips; with none, a moving colour background
    const go = async (withPic, withClips) => {
      const sc = [...(withPic ? [{ kind: "pic", file: pic, credit: "" }] : []), ...withClips.map(c => ({ kind: "clip", file: c.file, credit: c.user ? `🎥 ${c.user} · Pexels` : "🎥 Pexels" }))];
      const dur = writeAss(item, ass, sc.length > 0, voiceSec, sc.map(x => x.credit));
      const assArg = `ass=${ass.replace(/[:\\']/g, m => "\\" + m)}:fontsdir=${FONTS.replace(/[:\\']/g, m => "\\" + m)}:shaping=complex`;
      const args = ["-y", "-v", "error"];
      const seg = dur / Math.max(1, sc.length);
      const lens = sc.map((x, i) => seg + (i < sc.length - 1 ? XF : 0));
      sc.forEach((x, i) => {
        if (x.kind === "pic") args.push("-framerate", String(FPS), "-i", x.file);
        else args.push("-stream_loop", "-1", "-t", lens[i].toFixed(2), "-i", x.file);
      });
      if (!sc.length) args.push("-f", "lavfi", "-i", `gradients=s=${W}x${H}:c0=0x0A4F50:c1=0x${String(COLOR[item.cat] || "#19A39A").slice(1)}:x0=0:y0=0:x1=${W}:y1=${H}:d=${dur.toFixed(2)}:speed=0.01:r=${FPS}`);
      args.push("-stream_loop", "-1", "-i", MUSIC_FILE);
      if (voiceFile) args.push("-i", voiceFile);
      const music = Math.max(1, sc.length), vo = music + 1;
      let fc = "";
      if (sc.length) {
        // each scene as a 16:9 band (the picture slowly zooms; clips play), softly fading into the next
        sc.forEach((x, i) => {
          fc += x.kind === "pic"
            ? `[${i}:v]scale=1280:720:force_original_aspect_ratio=increase,crop=1280:720,zoompan=z='min(zoom+0.0006,1.15)':d=${Math.ceil(lens[i] * FPS)}:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=${W}x404:fps=${FPS},setsar=1,format=yuv420p,settb=AVTB,setpts=PTS-STARTPTS[s${i}];`
            : `[${i}:v]scale=${W}:404:force_original_aspect_ratio=increase,crop=${W}:404,fps=${FPS},setsar=1,format=yuv420p,trim=duration=${lens[i].toFixed(2)},settb=AVTB,setpts=PTS-STARTPTS[s${i}];`;
        });
        let last = "s0";
        for (let i = 1; i < sc.length; i++) { fc += `[${last}][s${i}]xfade=transition=fade:duration=${XF}:offset=${(i * seg).toFixed(2)}[x${i}];`; last = "x" + i; }
        // the same scenes, blurred, fill the screen behind the band
        fc += `[${last}]split[b1][b2];[b1]scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},boxblur=22:2,eq=brightness=-0.2:saturation=1.15,format=yuv420p[bg];` +
          `[bg][b2]overlay=0:150:shortest=1[v1];[v1]${assArg},format=yuv420p[v];`;
      } else fc = `[0:v]format=yuv420p,${assArg},format=yuv420p[v];`;
      fc += voiceFile
        ? `[${music}:a]atrim=0:${dur.toFixed(2)},volume=0.22,afade=t=in:d=1,afade=t=out:st=${(dur - 2).toFixed(2)}:d=2[m];[${vo}:a]adelay=delays=900:all=1,volume=1.8[vo];[m][vo]amix=inputs=2:duration=first:dropout_transition=0[a]`
        : `[${music}:a]atrim=0:${dur.toFixed(2)},volume=0.7,afade=t=in:d=1,afade=t=out:st=${(dur - 2).toFixed(2)}:d=2[a]`;
      args.push("-filter_complex", fc, "-map", "[v]", "-map", "[a]", "-t", dur.toFixed(2), "-r", String(FPS),
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "25", "-pix_fmt", "yuv420p", "-profile:v", "main",
        "-c:a", "aac", "-b:a", "96k", "-ac", "2", "-movflags", "+faststart", part);
      if (process.env.REELS_DEBUG) console.log("ffmpeg " + args.map(a => /[ ;\[\]()=,:]/.test(a) ? JSON.stringify(a) : a).join(" "));
      await run("nice", ["-n", "10", FFMPEG, ...args], 6 * 60e3);
    };
    try {
      try { await go(hasPic, clips); }
      catch (e) {
        if (!hasPic && !clips.length) throw e;
        console.error("Reels: scenes didn't work, trying simpler:", String(e.stderr || e.message).slice(-200));
        try { await go(hasPic, []); }                    // without the clips
        catch (e2) { if (!hasPic) throw e2; hasPic = false; await go(false, []); }   // a picture ffmpeg can't read: the colour background instead
      }
      if (await seconds(part) < 5) throw new Error("video too short");
      fs.renameSync(part, out);
      return true;
    } finally {
      for (const f of [pic, ass, part]) { try { fs.unlinkSync(f); } catch (e) { } }
    }
  }

  // ---------- making them in the background ----------
  async function tick() {
    if (busy) return;
    if (st.day !== today()) { st.day = today(); st.made = 0; st.tries = {}; saveSt(); }
    if (st.made >= DAILY || !(await available())) return;
    const since = Date.now() - 2 * 86400e3;
    // (a post with a source page waits until its picture was looked for)
    // (YouTube posts are videos already)
    const next = media.allItems().filter(i => !i.hidden && !i.yt && !i.video && CATS.includes(i.cat) && i.at > since && (i.img !== undefined || !i.url) && !i.reel && (st.tries[i.id] || 0) < 2 && !has(i.id))
      .sort((a, b) => b.at - a.at)[0];
    if (!next) return;
    busy = true; st.tries[next.id] = (st.tries[next.id] || 0) + 1; saveSt();
    const t0 = Date.now();
    try {
      await make(next);
      next.reel = true; media.changed(); st.made++; saveSt(); lastError = "";
      console.log(`Reels: video for post ${next.id} made in ${Math.round((Date.now() - t0) / 1000)} s`);
    } catch (e) { lastError = String(e.stderr || e.message || e).slice(-300); console.error("Reels error:", lastError); }
    busy = false;
  }
  available().catch(() => { });
  setTimeout(tick, 90_000).unref();
  setInterval(tick, 4 * 60_000).unref();

  // old videos and the ones whose post was removed are deleted
  setInterval(() => {
    const live = new Set(media.allItems().filter(i => !i.hidden).map(i => String(i.id)));
    for (const f of fs.readdirSync(DIR)) {
      if (!/^\d+\.mp4$/.test(f)) continue;
      const p = path.join(DIR, f);
      try { if (!live.has(f.replace(".mp4", "")) || Date.now() - fs.statSync(p).mtimeMs > KEEP_MS) fs.unlinkSync(p); } catch (e) { }
    }
    for (const i of media.allItems()) if (i.reel && !has(i.id)) { i.reel = false; media.changed(); }
    for (const f of fs.readdirSync(CLIPS)) { const p = path.join(CLIPS, f); try { if (Date.now() - fs.statSync(p).mtimeMs > KEEP_MS) fs.unlinkSync(p); } catch (e) { } }
  }, 6 * 3600e3).unref();

  /** GET /media/reel/<id>.mp4 (with Range support, for players and downloads). */
  function handle(req, res, url) {
    const m = url.pathname.match(/^\/media\/reel\/(\d+)\.mp4$/);
    if (!m || (req.method !== "GET" && req.method !== "HEAD")) return false;
    const f = file(m[1]);
    let s; try { s = fs.statSync(f); } catch (e) { res.writeHead(404); res.end(); return true; }
    const size = s.size, head = { "Content-Type": "video/mp4", "Cache-Control": "public, max-age=86400", "Accept-Ranges": "bytes",
      "Content-Disposition": `inline; filename="Habit-news-${m[1]}.mp4"` };
    const r = String(req.headers.range || "").match(/^bytes=(\d*)-(\d*)$/);
    if (r && (r[1] || r[2])) {
      const start = r[1] ? Number(r[1]) : Math.max(0, size - Number(r[2]));
      const end = r[1] && r[2] ? Math.min(Number(r[2]), size - 1) : size - 1;
      if (start >= size || start > end) { res.writeHead(416, { "Content-Range": `bytes */${size}` }); res.end(); return true; }
      res.writeHead(206, Object.assign(head, { "Content-Range": `bytes ${start}-${end}/${size}`, "Content-Length": end - start + 1 }));
      if (req.method === "HEAD") { res.end(); return true; }
      fs.createReadStream(f, { start, end }).pipe(res); return true;
    }
    res.writeHead(200, Object.assign(head, { "Content-Length": size }));
    if (req.method === "HEAD") { res.end(); return true; }
    fs.createReadStream(f).pipe(res); return true;
  }

  function stats() { return { on: ok === true, today: st.day === today() ? st.made : 0, daily: DAILY, voice: !!(voice && voice.enabled), scenes: PEXELS() ? "pexels" : "picture", lastError }; }

  return { handle, stats, has, make, tick };
};
