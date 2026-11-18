// Example letters ("formats"): sample letters, reports and proceedings the owner adds on the Teach page (from a computer:
// Word, PDF, photo or text). When a letter of that kind is written, the closest example is given to the AI as the
// FORMAT to follow (layout, headings, salutation, numbering, tone): never its facts. Kept as text in data/formats/.
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const crypto = require("crypto");

/** The text of a Word (.docx) file: its word/document.xml, paragraphs on their own lines. */
function docxText(buf) {
  // the zip's central directory: find word/document.xml, then read and unpack it
  const eocd = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocd < 0) throw Object.assign(new Error("That Word file couldn't be opened. Save it as PDF and try again."), { user: true });
  const count = buf.readUInt16LE(eocd + 10);
  let at = buf.readUInt32LE(eocd + 16);
  for (let i = 0; i < count && at + 46 <= buf.length; i++) {
    const method = buf.readUInt16LE(at + 10), size = buf.readUInt32LE(at + 20);
    const nameLen = buf.readUInt16LE(at + 28), extraLen = buf.readUInt16LE(at + 30), noteLen = buf.readUInt16LE(at + 32);
    const local = buf.readUInt32LE(at + 42);
    const name = buf.toString("utf8", at + 46, at + 46 + nameLen);
    if (name === "word/document.xml") {
      const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
      const raw = buf.subarray(start, start + size);
      const xml = (method === 8 ? zlib.inflateRawSync(raw) : raw).toString("utf8");
      return xml.replace(/<w:tab\/>/g, "\t").replace(/<w:br\/>/g, "\n").replace(/<\/w:p>/g, "\n").replace(/<[^>]+>/g, "")
        .replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, "\"").replace(/&apos;/g, "'")
        .replace(/[ \t]+\n/g, "\n").replace(/\n{3,}/g, "\n\n").trim();
    }
    at += 46 + nameLen + extraLen + noteLen;
  }
  throw Object.assign(new Error("No text was found in that Word file."), { user: true });
}

module.exports = function makeFormats({ DATA_DIR, learn, MODEL }) {
  const DIR = path.join(DATA_DIR, "formats");
  try { fs.mkdirSync(DIR, { recursive: true }); } catch (e) { }
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
  const words = s => String(s || "").toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(w => w.length >= 3);

  let cache = null;
  function list() {
    if (cache) return cache;
    const out = [];
    try { for (const f of fs.readdirSync(DIR)) if (f.endsWith(".json")) try { out.push(JSON.parse(fs.readFileSync(path.join(DIR, f), "utf8"))); } catch (e) { } } catch (e) { }
    cache = out.sort((a, b) => b.at - a.at);
    return cache;
  }
  const hashOf = t => crypto.createHash("sha1").update(String(t).replace(/\s+/g, " ").trim()).digest("hex").slice(0, 16);
  /** The text of an uploaded file: Word and text files are read here (free); PDF and photos by the AI. */
  async function textOf(name, type, data, opt) {
    if (/\.docx$/i.test(name) || type.includes("wordprocessingml")) return docxText(Buffer.from(data, "base64"));
    return learn.fileToText(name || "file", type, data, opt);
  }
  // ---------- many files at once: the same file is never read twice, and the AI reads only so many a day ----------
  const SEEN = path.join(DIR, "seen.json");
  let seen = {};
  try { seen = JSON.parse(fs.readFileSync(SEEN, "utf8")); } catch (e) { }
  let seenTimer = null;
  const saveSeen = () => { if (!seenTimer) seenTimer = setTimeout(() => { seenTimer = null; try { fs.writeFileSync(SEEN, JSON.stringify(seen)); } catch (e) { } }, 2000); };
  const rawHash = data => crypto.createHash("sha1").update(String(data || "")).digest("hex").slice(0, 20);
  const AI_DAILY = Math.max(0, Number(process.env.BULK_AI_DAILY || 300));
  let aiDay = "", aiUsed = 0;
  /** A file's text for the bulk upload: { text } or { same } (this very file came before) or { wait } (the AI's daily count is used up). */
  async function bulkText(name, type, data) {
    const h = rawHash(data);
    if (seen[h]) return { same: true };
    const free = /\.(docx|txt|md|csv)$/i.test(name) || type.includes("wordprocessingml") || /^text\//.test(type);
    if (!free) {
      const day = new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);
      if (day !== aiDay) { aiDay = day; aiUsed = 0; }
      if (aiUsed >= AI_DAILY) return { wait: true };
      aiUsed++;
    }
    // (the regular AI, not the bigger one: many files, and plain text is enough)
    const text = await textOf(name, type, data, { model: MODEL || undefined, maxTokens: 8000 });
    seen[h] = Date.now(); saveSeen();
    return { text };
  }
  /** What kind of document a file is, from its name and folder ("Reports/RDO survey.docx" → Report). */
  function kindOf(p) {
    const s = String(p || "").toLowerCase();
    for (const [k, re] of [["Proceedings", /proceed|procd|ప్రొసీడింగ్/], ["Report", /report|నివేదిక|రిపోర్ట్/], ["Memo", /memo|మెమో/], ["Notice", /notice|నోటీసు/],
      ["Endorsement", /endorse|ఎండార్స్/], ["Certificate", /certif|సర్టిఫికెట్|ధృవ/], ["Application", /applic|petition|దరఖాస్తు/], ["Letter", /letter|లేఖ|lr/]]) if (re.test(s)) return k;
    return "Letter";
  }
  const view = x => ({ id: x.id, title: x.title, kind: x.kind, chars: x.text.length, at: x.at, start: x.text.slice(0, 160) });

  /** Adds an example: a file ({ name, mediaType, data } in base64) or pasted { text }. */
  async function add(body) {
    let text = str(body.text, 30000);
    const name = str(body.name, 120), type = str(body.mediaType, 120), data = String(body.data || "");
    if (!text && data) text = await textOf(name, type, data);
    text = String(text || "").replace(/\r/g, "").trim().slice(0, 30000);
    if (text.length < 40) throw Object.assign(new Error("Too little text was found in that example."), { user: true });
    const h = hashOf(text), same = list().find(x => x.hash === h);
    if (same) return Object.assign(view(same), { same: true });
    if (list().length >= 3000) throw Object.assign(new Error("3000 examples are kept at most: delete old ones first."), { user: true });
    const x = { id: crypto.randomBytes(6).toString("hex"), title: str(body.title, 100) || name.replace(/^.*[\\/]/, "").replace(/\.[a-z0-9]+$/i, "") || "Example",
      kind: str(body.kind, 60) || kindOf(str(body.path, 300) + " " + name), text, at: Date.now(), hash: h, from: str(body.path, 300) };
    fs.writeFileSync(path.join(DIR, x.id + ".json"), JSON.stringify(x));
    list().unshift(x);
    return view(x);
  }
  function remove(id) {
    if (!/^[a-f0-9]{12}$/.test(String(id || ""))) return false;
    if (cache) cache = cache.filter(x => x.id !== id);
    try { fs.unlinkSync(path.join(DIR, id + ".json")); return true; } catch (e) { return false; }
  }

  /** The example closest to this letter (its kind first, then the words of the matter), or null. */
  function pick(docType, request) {
    const all = list();
    if (!all.length) return null;
    const kw = words(docType), rw = new Set(words(request));
    let best = null, bs = 0;
    for (const x of all) {
      const tw = words(x.title + " " + x.kind);
      let s = kw.filter(w => tw.includes(w)).length * 5;
      s += words(x.title + " " + x.kind + " " + x.text.slice(0, 1500)).filter(w => rw.has(w)).length * 0.2;
      if (s > bs) { bs = s; best = x; }
    }
    return bs >= 1 ? best : null;
  }

  /** The words for the AI: an example to follow for its FORMAT only. */
  function block(x) {
    if (!x) return "";
    return `\n\nEXAMPLE FORMAT ("${x.title}"${x.kind ? ", a " + x.kind : ""}). Follow its format closely: its layout, the order of its parts, its headings, the way it addresses and signs off, its reference/date lines, numbering and tone. Do NOT copy its facts, names, numbers, dates or places: those come only from the matter and the papers.\n<<<\n${x.text.slice(0, 8000)}\n>>>`;
  }

  return { list: () => list().map(view), add, remove, pick, block, textOf, kindOf, hashOf, bulkText, aiLeft: () => Math.max(0, AI_DAILY - aiUsed) };
};
module.exports.docxText = docxText;
