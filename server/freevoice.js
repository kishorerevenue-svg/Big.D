// Our own free voice for the cartoons: Piper (an open-source voice program, MIT) with its Telugu voice, running on
// our own server. No key, no payment. It downloads itself the first time (about 90 MB, into data/piper):
//   - the Piper program from GitHub (rhasspy/piper)
//   - the Telugu voice "maya" (te_IN-maya-medium) from Hugging Face (rhasspy/piper-voices)
// Each character gets its own voice from the one Telugu voice: higher for the kids, the puppy, the cat and the
// parrot; lower for Ramu and the farmer; slower for Ammamma.
// TOON_VOICE=false turns it off (the cartoons then use talk sounds). The natural Google voice, when the server has
// GOOGLE_TTS_KEY, is used first.
const fs = require("fs");
const path = require("path");
const { execFile, spawn } = require("child_process");

module.exports = function makeFreeVoice({ DATA_DIR, FFMPEG = "ffmpeg" }) {
  const DIR = path.join(DATA_DIR, "piper");
  const BIN = path.join(DIR, "piper", "piper");
  const NAME = process.env.TOON_VOICE_NAME || "te_IN-maya-medium";
  const MODEL = path.join(DIR, NAME + ".onnx");
  const TRIED = path.join(DIR, ".tried");
  const ARCH = process.arch === "arm64" ? "aarch64" : "x86_64";
  const PIPER_URL = process.env.PIPER_URL || `https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_${ARCH}.tar.gz`;
  const VOICE_URL = process.env.TOON_VOICE_URL || "https://huggingface.co/rhasspy/piper-voices/resolve/main/te/te_IN/maya/medium/" + NAME + ".onnx";
  const ON = String(process.env.TOON_VOICE || "true") !== "false";
  // [speed (bigger = slower), pitch]
  const STYLE = { bigd: [0.95, 1.22], chaiwala: [1.0, 0.76], ammamma: [1.12, 0.9], chinnu: [0.95, 1.12], chitti: [0.95, 1.2],
    raithu: [1.05, 0.72], teacher: [1.0, 1.0], pilli: [0.95, 1.32], chilaka: [0.9, 1.45],
    chai: [1.0, 0.8], idli: [0.95, 1.12], mango: [0.95, 1.22], tatayya: [1.1, 0.72] };
  let state = ON ? "not ready yet" : "off (TOON_VOICE=false)";
  let ok = false, busy = null, failedAt = 0;

  const run = (cmd, args, opt = {}) => new Promise((res, rej) => execFile(cmd, args, { timeout: 120000, maxBuffer: 32e6, ...opt }, (e, out, err) => e ? rej(Object.assign(e, { stderr: err })) : res(out)));
  const have = () => fs.existsSync(BIN) && fs.existsSync(MODEL) && fs.existsSync(MODEL + ".json");

  async function download(url, file) {
    const r = await fetch(url, { redirect: "follow", signal: AbortSignal.timeout(15 * 60e3) });
    if (!r.ok) throw new Error(`download failed (${r.status}) ${url}`);
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length < 1000) throw new Error("download too small: " + url);
    fs.writeFileSync(file + ".part", buf);
    fs.renameSync(file + ".part", file);
  }

  /** Makes one line of speech: Telugu text -> WAV file (24 kHz, mono) in the character's voice. */
  async function speak(text, who, out) {
    if (!ok) throw new Error("the free voice isn't ready");
    const t = String(text || "").replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}"“”*_#{}<>[\]\\]/gu, " ")
      .replace(/BigD/gi, "బిగ్‌డీ").replace(/\s+/g, " ").trim().slice(0, 300);
    if (!t) throw new Error("nothing to say");
    const [speed, pitch] = STYLE[who] || [1, 1];
    const raw = out + ".raw.wav";
    await new Promise((res, rej) => {
      const p = spawn(BIN, ["--model", MODEL, "--output_file", raw, "--length_scale", String(speed)],
        { env: { ...process.env, LD_LIBRARY_PATH: path.dirname(BIN) }, stdio: ["pipe", "ignore", "pipe"] });
      let err = "";
      const timer = setTimeout(() => { p.kill("SIGKILL"); rej(new Error("the voice took too long")); }, 90000);
      p.stderr.on("data", d => { err += d; if (err.length > 4000) err = err.slice(-4000); });
      p.on("error", e => { clearTimeout(timer); rej(e); });
      p.on("close", code => { clearTimeout(timer); code === 0 && fs.existsSync(raw) ? res() : rej(new Error("the voice failed: " + err.trim().split("\n").pop())); });
      p.stdin.end(t + "\n");
    });
    try {
      const rate = JSON.parse(fs.readFileSync(MODEL + ".json", "utf8")).audio.sample_rate || 22050;
      // a different pitch for each character, keeping the same speed
      const f = pitch === 1 ? "aresample=24000" : `asetrate=${Math.round(rate * pitch)},aresample=24000,atempo=${(1 / pitch).toFixed(4)}`;
      await run(FFMPEG, ["-y", "-v", "error", "-i", raw, "-af", f + ",volume=1.3", "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le", out]);
    } finally { fs.rmSync(raw, { force: true }); }
    return out;
  }

  /** Gets the voice ready: downloads it the first time, then checks that it speaks. */
  function ready() {
    if (busy) return busy;
    busy = (async () => {
      if (!ON) return false;
      if (failedAt && Date.now() - failedAt < 6 * 3600e3) return false;     // tries again later
      try {
        fs.mkdirSync(DIR, { recursive: true });
        if (!have()) {
          try { if (Date.now() - fs.statSync(TRIED).mtimeMs < 6 * 3600e3) { state = "the download failed a little while ago; it tries again later"; return false; } } catch (e) { }
          fs.writeFileSync(TRIED, String(Date.now()));
          state = "downloading the free Telugu voice (about 90 MB)…";
          if (!fs.existsSync(BIN)) {
            const tgz = path.join(DIR, "piper.tar.gz");
            await download(PIPER_URL, tgz);
            await run("tar", ["xzf", tgz, "-C", DIR]);
            fs.rmSync(tgz, { force: true });
          }
          if (!fs.existsSync(MODEL + ".json")) await download(VOICE_URL + ".json", MODEL + ".json");
          if (!fs.existsSync(MODEL)) await download(VOICE_URL, MODEL);
        }
        ok = true;
        const test = path.join(DIR, "test.wav");
        await speak("నమస్కారం", "teacher", test);
        const size = fs.statSync(test).size;
        fs.rmSync(test, { force: true });
        if (size < 8000) throw new Error("the voice made no sound");
        state = "ready";
        console.log("Cartoons: our free Telugu voice is ready");
        return true;
      } catch (e) {
        ok = false;
        failedAt = Date.now();
        state = "not working: " + String(e.message || e).slice(0, 200);
        console.error("Cartoons: free voice:", state);
        return false;
      } finally { busy = null; }
    })();
    return busy;
  }

  return { ready, speak, ok: () => ok, state: () => state };
};
