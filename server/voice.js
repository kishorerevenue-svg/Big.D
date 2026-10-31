// BigD AI natural voice: turns the pet's reply text into an MP3 with Google Cloud Text-to-Speech
// (Chirp 3 HD voices, Telugu and English). Off until GOOGLE_TTS_KEY is set in .env.
// The phone then raises the pitch a little for each pet, so the voice stays clear but cute.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

module.exports = function makeVoice({ DATA_DIR }) {
  const KEY = process.env.GOOGLE_TTS_KEY || "";
  const DIR = path.join(DATA_DIR, "tts");
  fs.mkdirSync(DIR, { recursive: true });

  // Chirp 3 HD voice for each pet (names from Google's list) and its speaking speed.
  const PET_VOICE = {
    puppy: ["Puck", 1.05], kitten: ["Leda", 1.05], bunny: ["Aoede", 1.08], panda: ["Kore", 0.95], bear: ["Orus", 0.92],
    fox: ["Zephyr", 1.05], robot: ["Charon", 1.0], parrot: ["Laomedeia", 1.1], mochi: ["Leda", 1.0], kitty: ["Aoede", 1.05]
  };
  // Bill guard: stop asking Google after this many characters in a month (1 million are free).
  // Then the pet simply uses the phone's voice until the 1st of next month.
  const MONTH_CAP = Number(process.env.TTS_MONTHLY_CHARS || 900000);
  const USAGE_FILE = path.join(DATA_DIR, "tts-usage.json");
  const thisMonth = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 7); // India month
  let usage = { month: thisMonth(), chars: 0 };
  try { usage = JSON.parse(fs.readFileSync(USAGE_FILE, "utf8")); } catch (e) { }
  function used() { if (usage.month !== thisMonth()) usage = { month: thisMonth(), chars: 0 }; return usage.chars; }
  function addUsed(n) { used(); usage.chars += n; try { fs.writeFileSync(USAGE_FILE, JSON.stringify(usage)); } catch (e) { } }

  const MOOD_SPEED = { excited: 1.06, laughing: 1.06, love: 1.04, proud: 1.04, surprised: 1.08, scared: 1.08, sad: 0.9, crying: 0.9, sleepy: 0.88, yawning: 0.88 };

  function clean(text) {
    return String(text || "")
      .replace(/\p{Extended_Pictographic}|️|‍/gu, "")
      .replace(/[*_#`~>|]/g, "")
      .replace(/https?:\/\/\S+/g, "")
      .replace(/\s+/g, " ").trim().slice(0, 700);
  }

  /** Returns { file } (a cached or new MP3), or throws. */
  async function speak(text, pet, mood) {
    const t = clean(text);
    if (!t) throw Object.assign(new Error("empty"), { user: true });
    const locale = /[ఀ-౿]/.test(t) ? "te-IN" : "en-IN";
    const [voice, base] = PET_VOICE[pet] || PET_VOICE.puppy;
    const override = locale === "te-IN" ? process.env.TTS_VOICE_TE : process.env.TTS_VOICE_EN;
    const name = override || `${locale}-Chirp3-HD-${voice}`;
    const rate = Math.round(Math.min(1.3, Math.max(0.75, base * (MOOD_SPEED[mood] || 1))) * 100) / 100;
    const id = crypto.createHash("sha1").update([name, rate, t].join("|")).digest("hex");
    const file = path.join(DIR, id + ".mp3");
    if (fs.existsSync(file)) { fs.utimesSync(file, new Date(), new Date()); return { file }; }
    if (used() + t.length > MONTH_CAP) throw Object.assign(new Error("monthly voice limit"), { cap: true });

    const ctl = new AbortController(); const timer = setTimeout(() => ctl.abort(), 15000);
    let r;
    try {
      r = await fetch((process.env.TTS_BASE || "https://texttospeech.googleapis.com") + "/v1/text:synthesize?key=" + encodeURIComponent(KEY), {
        method: "POST", signal: ctl.signal, headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ input: { text: t }, voice: { languageCode: locale, name }, audioConfig: { audioEncoding: "MP3", speakingRate: rate } })
      });
    } finally { clearTimeout(timer); }
    const data = await r.json().catch(() => ({}));
    if (!r.ok || !data.audioContent) throw new Error((data.error && data.error.message) || ("tts_error " + r.status));
    addUsed(t.length);
    fs.writeFileSync(file, Buffer.from(data.audioContent, "base64"));
    return { file };
  }

  // keep the cache small: delete voice files not used for 7 days
  setInterval(() => {
    const now = Date.now();
    for (const f of fs.readdirSync(DIR)) { const p = path.join(DIR, f); try { if (now - fs.statSync(p).mtimeMs > 7 * 86400e3) fs.unlinkSync(p); } catch (e) { } }
  }, 6 * 3600e3).unref();

  return { enabled: !!KEY, speak, usage: () => ({ month: thisMonth(), chars: used(), cap: MONTH_CAP }) };
};
