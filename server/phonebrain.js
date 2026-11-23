// Habit Brain's small brain (about 0.6 GB): Google's Gemma 3 1B for phones (litert-community/Gemma3-1B-IT, int4,
// LiteRT-LM). Hugging Face asks for a login and the Gemma licence for it, so this server fetches it once with the
// owner's HF_TOKEN (after the owner accepts the licence on that page) and gives it to the phones from here, with
// resume (Range) so a broken download continues. Gemma is provided under and subject to the Gemma Terms of Use
// (https://ai.google.dev/gemma/terms); the app shows that notice. PHONE_BRAIN_SMALL_URL can name another place.
const fs = require("fs");
const path = require("path");
const { Readable } = require("stream");
const { pipeline } = require("stream/promises");

module.exports = function makePhoneBrain({ DATA_DIR }) {
  const DIR = path.join(DATA_DIR, "phonebrain");
  fs.mkdirSync(DIR, { recursive: true });
  const REPO = "litert-community/Gemma3-1B-IT";
  const NAME = "gemma3-1b-it-int4.litertlm";
  const FILE = path.join(DIR, NAME);
  const MIN = 300 * 1024 * 1024;
  let fetching = null, why = "", got = 0, total = 0;

  const ready = () => { try { return fs.statSync(FILE).size > MIN; } catch (e) { return false; } };
  const auth = () => process.env.HF_TOKEN ? { Authorization: "Bearer " + process.env.HF_TOKEN } : {};

  /** Which file in the repo: the phone (not web, not one chip only) int4/q4 .litertlm, the smallest such. */
  async function pick() {
    try {
      const j = await fetch(`https://huggingface.co/api/models/${REPO}/tree/main`, { headers: auth(), signal: AbortSignal.timeout(15000) }).then(r => r.ok ? r.json() : []);
      const all = (Array.isArray(j) ? j : []).filter(f => f && f.type === "file" && /\.litertlm$/i.test(f.path) && /(int4|q4)/i.test(f.path) && !/(web|mediatek|qualcomm|mt\d|sm\d)/i.test(f.path));
      all.sort((a, b) => (a.size || 9e12) - (b.size || 9e12));
      if (all[0]) return all[0].path;
    } catch (e) { }
    return NAME;
  }

  /** Fetches it once (in the background). */
  function ensure() {
    if (ready() || fetching || process.env.PHONE_BRAIN_SMALL_URL) return;
    if (!process.env.HF_TOKEN) { why = "The server has no HF_TOKEN."; return; }
    fetching = (async () => {
      const part = FILE + ".part";
      try {
        why = ""; got = 0; total = 0;
        const f = await pick();
        const r = await fetch(`https://huggingface.co/${REPO}/resolve/main/${encodeURIComponent(f)}`, { headers: auth(), redirect: "follow", signal: AbortSignal.timeout(3600_000) });
        if (r.status === 401 || r.status === 403) throw new Error(`Hugging Face says no (${r.status}): the owner must open huggingface.co/${REPO} while logged in and accept the Gemma licence once.`);
        if (!r.ok) throw new Error("Hugging Face http " + r.status);
        total = Number(r.headers.get("content-length") || 0);
        const body = Readable.fromWeb(r.body);
        body.on("data", c => { got += c.length; });
        await pipeline(body, fs.createWriteStream(part));
        if (fs.statSync(part).size < MIN) throw new Error("the file that came was too small");
        fs.renameSync(part, FILE);
      } catch (e) {
        why = String(e.message || e).slice(0, 300);
        try { fs.unlinkSync(part); } catch (x) { }
      } finally { fetching = null; }
    })();
  }

  /** For /brain/phone: where the phone gets the small brain, and whether it is ready. */
  function info(base) {
    const own = String(process.env.PHONE_BRAIN_SMALL_URL || "").trim();
    if (own) return { url: own, name: NAME, sizeMb: 600, ready: true, licence: "Gemma Terms of Use" };
    if (!ready()) ensure();
    const r = ready();
    return { url: base + "/brain/phone/small", name: NAME, sizeMb: r ? Math.round(fs.statSync(FILE).size / 1e6) : 600, ready: r,
      preparing: !!fetching, progress: fetching && total ? Math.round(got * 100 / total) : undefined, why: r ? undefined : (why || undefined),
      licence: "Gemma Terms of Use" };
  }

  /** Sends the file, with resume (Range). */
  function serve(req, res) {
    if (!ready()) {
      ensure();
      res.writeHead(503, { "content-type": "application/json", "retry-after": "600" });
      return res.end(JSON.stringify({ error: "The small brain is being prepared on the server. Try again in 10 minutes.", why: why || undefined }));
    }
    const size = fs.statSync(FILE).size;
    const m = /^bytes=(\d*)-(\d*)$/.exec(String(req.headers.range || ""));
    let start = 0, end = size - 1, code = 200;
    if (m && (m[1] || m[2])) {
      if (m[1]) { start = Number(m[1]); if (m[2]) end = Math.min(Number(m[2]), size - 1); }
      else { start = Math.max(0, size - Number(m[2])); }
      if (start > end || start >= size) { res.writeHead(416, { "content-range": `bytes */${size}` }); return res.end(); }
      code = 206;
    }
    const h = { "content-type": "application/octet-stream", "content-length": end - start + 1, "accept-ranges": "bytes",
      "content-disposition": `attachment; filename="${NAME}"`, "cache-control": "public, max-age=86400" };
    if (code === 206) h["content-range"] = `bytes ${start}-${end}/${size}`;
    res.writeHead(code, h);
    if (req.method === "HEAD") return res.end();
    fs.createReadStream(FILE, { start, end }).pipe(res);
  }

  // get it ready a little after the server starts, so the first phone doesn't wait
  setTimeout(ensure, 90_000).unref?.();
  return { info, serve, ensure, status: () => ({ ready: ready(), preparing: !!fetching, why, got, total }) };
};
