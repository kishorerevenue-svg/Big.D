// News videos to share (WhatsApp status and so on): a short vertical MP4 for new Media posts.
// The post's picture slowly zooms (or a moving colour background), the headline and the news come as text
// (drawn with the subtitles engine and "complex shaping", so Telugu letters join correctly), gentle music
// made for BigD AI, and the news read aloud when the natural voice (GOOGLE_TTS_KEY) is set up.
// One video at a time in the background, at most REELS_DAILY a day; videos are deleted after 10 days.
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
  const DAILY = Number(process.env.REELS_DAILY || 12);
  const CATS = String(process.env.REELS_CATS || "news,jobs,farming,health,devotional,movies,kids,realestate").split(",").map(s => s.trim());
  const KEEP_MS = 10 * 86400e3;
  const W = 720, H = 1280, FPS = 30;
  const COLOR = { news: "#3B82F6", movies: "#F97316", jobs: "#6366F1", devotional: "#FB923C", kids: "#F472B6", health: "#14B8A6",
    farming: "#65A30D", realestate: "#10B981", crypto: "#F59E0B" };
  const STATE = path.join(DIR, "state.json");
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);
  let st = { day: "", made: 0, tries: {} };
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
      try { r = await fetch(url, { redirect: "manual", signal: AbortSignal.timeout(15000), headers: { "User-Agent": "Mozilla/5.0 (compatible; BigD-AI/1.0; +https://bigdai.tech)" } }); }
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

  /** Builds the subtitle file; returns the video length in seconds. */
  function writeAss(item, assFile, hasPic, voiceSec) {
    const te = /[ఀ-౿]/.test(item.title + item.body);
    const cat = (media.CATS[item.cat] || {});
    const label = "BigD AI · " + (te ? (cat.te || "వార్తలు") : (cat.en || "News"));
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
    ev.push(`Dialogue: 1,${ts(0.2)},${ts(endAt)},Foot,,0,0,0,,${te ? "BigD AI యాప్‌లో మరిన్ని · bigdai.tech" : "More on the BigD AI app · bigdai.tech"}`);
    ev.push(`Dialogue: 1,${ts(endAt)},${ts(total)},Body,,0,0,0,,{\\fad(300,0)\\an7\\pos(0,0)\\bord0\\shad0\\1c&H2A1F0A&\\1a&H30&\\p1}m 0 0 l ${W} 0 ${W} ${H} 0 ${H}{\\p0}`);
    ev.push(`Dialogue: 2,${ts(endAt)},${ts(total)},End,,0,0,0,,{\\fad(300,0)\\pos(${W / 2},${H / 2 - 60})}BigD AI`);
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
    let voiceFile = "", voiceSec = 0;
    if (voice && voice.enabled) {
      try { voiceFile = (await voice.speak(item.title + ". " + item.body, "panda", "calm")).file; voiceSec = await seconds(voiceFile); } catch (e) { voiceFile = ""; }
    }
    const go = async withPic => {
      const dur = writeAss(item, ass, withPic, voiceSec);
      const frames = Math.round(dur * FPS);
      const assArg = `ass=${ass.replace(/[:\\']/g, m => "\\" + m)}:fontsdir=${FONTS.replace(/[:\\']/g, m => "\\" + m)}:shaping=complex`;
      const args = ["-y", "-v", "error"];
      if (withPic) args.push("-loop", "1", "-framerate", String(FPS), "-t", dur.toFixed(2), "-i", pic, "-i", pic);
      else args.push("-f", "lavfi", "-i", `gradients=s=${W}x${H}:c0=0x0A4F50:c1=0x${String(COLOR[item.cat] || "#19A39A").slice(1)}:x0=0:y0=0:x1=${W}:y1=${H}:d=${dur.toFixed(2)}:speed=0.01:r=${FPS}`);
      args.push("-stream_loop", "-1", "-i", MUSIC);
      if (voiceFile) args.push("-i", voiceFile);
      const music = withPic ? 2 : 1, vo = music + 1;
      let fc = withPic
        ? `[0:v]scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},boxblur=22:2,eq=brightness=-0.2:saturation=1.15,format=yuv420p[bg];` +
          `[1:v]scale=1080:-2,zoompan=z='min(zoom+0.0005,1.15)':d=${frames}:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=${W}x405:fps=${FPS},format=yuv420p[fg];` +
          `[bg][fg]overlay=0:150:shortest=1[v1];[v1]${assArg},format=yuv420p[v];`
        : `[0:v]format=yuv420p,${assArg},format=yuv420p[v];`;
      fc += voiceFile
        ? `[${music}:a]atrim=0:${dur.toFixed(2)},volume=0.22,afade=t=in:d=1,afade=t=out:st=${(dur - 2).toFixed(2)}:d=2[m];[${vo}:a]adelay=delays=900:all=1,volume=1.8[vo];[m][vo]amix=inputs=2:duration=first:dropout_transition=0[a]`
        : `[${music}:a]atrim=0:${dur.toFixed(2)},volume=0.7,afade=t=in:d=1,afade=t=out:st=${(dur - 2).toFixed(2)}:d=2[a]`;
      args.push("-filter_complex", fc, "-map", "[v]", "-map", "[a]", "-t", dur.toFixed(2), "-r", String(FPS),
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "25", "-pix_fmt", "yuv420p", "-profile:v", "main",
        "-c:a", "aac", "-b:a", "96k", "-ac", "2", "-movflags", "+faststart", part);
      await run("nice", ["-n", "10", FFMPEG, ...args], 6 * 60e3);
    };
    try {
      try { await go(hasPic); }
      catch (e) { if (!hasPic) throw e; hasPic = false; await go(false); }   // a picture ffmpeg can't read: the colour background instead
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
    const next = media.allItems().filter(i => !i.hidden && !i.yt && CATS.includes(i.cat) && i.at > since && (i.img !== undefined || !i.url) && !i.reel && (st.tries[i.id] || 0) < 2 && !has(i.id))
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
  }, 6 * 3600e3).unref();

  /** GET /media/reel/<id>.mp4 (with Range support, for players and downloads). */
  function handle(req, res, url) {
    const m = url.pathname.match(/^\/media\/reel\/(\d+)\.mp4$/);
    if (!m || (req.method !== "GET" && req.method !== "HEAD")) return false;
    const f = file(m[1]);
    let s; try { s = fs.statSync(f); } catch (e) { res.writeHead(404); res.end(); return true; }
    const size = s.size, head = { "Content-Type": "video/mp4", "Cache-Control": "public, max-age=86400", "Accept-Ranges": "bytes",
      "Content-Disposition": `inline; filename="BigD-news-${m[1]}.mp4"` };
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

  function stats() { return { on: ok === true, today: st.day === today() ? st.made : 0, daily: DAILY, voice: !!(voice && voice.enabled), lastError }; }

  return { handle, stats, has, make, tick };
};
