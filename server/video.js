// Short videos people post in Media (up to 1 minute):
//  - made smaller: at most 1280 px on the long side, H.264 + AAC, 30 fps, "fast start" so it plays while loading
//  - hidden details (like the place it was filmed) are removed
//  - a poster picture, and 4 small frames that the AI checks for safety before anyone sees the video
// Uses ffmpeg/ffprobe installed on the server (update.sh installs them once).
const { execFile } = require("child_process");

const FFMPEG = process.env.FFMPEG_PATH || "ffmpeg";
const FFPROBE = process.env.FFPROBE_PATH || "ffprobe";

function run(bin, args, timeoutMs) {
  return new Promise((resolve, reject) => {
    execFile(bin, args, { timeout: timeoutMs, maxBuffer: 16 * 1024 * 1024 }, (err, stdout, stderr) => {
      if (err) { err.stderr = String(stderr || "").slice(-600); reject(err); } else resolve({ stdout: String(stdout), stderr: String(stderr) });
    });
  });
}

let ok = null;
/** True when ffmpeg and ffprobe can run on this server. */
async function available() {
  if (ok !== null) return ok;
  try { await run(FFMPEG, ["-version"], 15000); await run(FFPROBE, ["-version"], 15000); ok = true; } catch (e) { ok = false; }
  setTimeout(() => { ok = null; }, 10 * 60 * 1000).unref();   // look again later (it may be installed meanwhile)
  return ok;
}

/** Length in seconds, size in pixels, and whether there is a picture track at all. */
async function probe(file) {
  const { stdout } = await run(FFPROBE, ["-v", "error", "-show_entries", "format=duration:stream=codec_type,width,height", "-of", "json", file], 60000);
  const j = JSON.parse(stdout || "{}");
  const v = (j.streams || []).find(s => s.codec_type === "video");
  return { dur: Number(j.format && j.format.duration) || 0, w: v ? Number(v.width) || 0 : 0, h: v ? Number(v.height) || 0 : 0, video: !!v };
}

/** Makes the small, safe copy of [input]: [mp4], a [poster] JPEG and 4 check frames. Returns { dur, w, h, frames }. */
async function convert(input, mp4, poster, framePrefix, maxSec) {
  const scale = "scale='if(gte(iw,ih),trunc(min(1280,iw)/2)*2,-2)':'if(gte(iw,ih),-2,trunc(min(1280,ih)/2)*2)'";
  await run(FFMPEG, ["-y", "-v", "error", "-i", input, "-t", String(maxSec + 1), "-map", "0:v:0", "-map", "0:a:0?", "-vf", scale, "-r", "30",
    "-c:v", "libx264", "-preset", "veryfast", "-crf", "28", "-maxrate", "2M", "-bufsize", "4M", "-pix_fmt", "yuv420p", "-profile:v", "main",
    "-c:a", "aac", "-b:a", "96k", "-ac", "2", "-map_metadata", "-1", "-movflags", "+faststart", mp4], 6 * 60 * 1000);
  const info = await probe(mp4);
  if (!info.video || info.dur <= 0) throw new Error("no_video");
  const at = t => String(Math.max(0, Math.min(info.dur - 0.1, t)).toFixed(2));
  await run(FFMPEG, ["-y", "-v", "error", "-ss", at(Math.min(1, info.dur * 0.3)), "-i", mp4, "-frames:v", "1", "-vf", "scale='min(720,iw)':-2", "-q:v", "4", poster], 60000);
  const frames = [];
  for (const [i, k] of [0.1, 0.35, 0.6, 0.85].entries()) {
    const f = `${framePrefix}-${i}.jpg`;
    await run(FFMPEG, ["-y", "-v", "error", "-ss", at(info.dur * k), "-i", mp4, "-frames:v", "1", "-vf", "scale='min(512,iw)':-2", "-q:v", "5", f], 60000);
    frames.push(f);
  }
  return { dur: info.dur, w: info.w, h: info.h, frames };
}

module.exports = { available, probe, convert };
