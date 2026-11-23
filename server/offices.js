// Offices: any office (a Tahsildar office, an MRO, a school, a hospital…) makes its own private space in Habit. The one
// who makes it is its head and gets a code for the staff; staff join with the code. Each office keeps its old letters
// and files ONLY as text (the files themselves are not kept), and only its own members can search and use them. Other
// offices get only the way of writing (names and numbers hidden), and only when the office allows it ("share").
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

module.exports = function makeOffices({ DATA_DIR, formats, h }) {
  const DIR = path.join(DATA_DIR, "offices");
  fs.mkdirSync(DIR, { recursive: true });
  const INDEX = path.join(DIR, "index.json");
  let offices = {};
  try { offices = JSON.parse(fs.readFileSync(INDEX, "utf8")); } catch (e) { }
  const saveIndex = () => fs.writeFileSync(INDEX, JSON.stringify(offices));
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
  const hash = s => crypto.createHash("sha256").update("office|" + String(s).trim().toUpperCase()).digest("hex");
  const userError = m => Object.assign(new Error(m), { user: true });
  const newCode = name => {
    const p = (str(name, 40).replace(/[^A-Za-z]/g, "").toUpperCase() + "OFC").slice(0, 3);
    const abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let r = ""; for (const b of crypto.randomBytes(6)) r += abc[b % abc.length];
    return p + "-" + r;
  };
  const dev = s => crypto.createHash("sha256").update("dev|" + String(s || "")).digest("hex").slice(0, 16);
  const filesDir = id => { const d = path.join(DIR, id); fs.mkdirSync(d, { recursive: true }); return d; };

  /** The office this request belongs to (its code in X-Office-Code), and whether it is its head (X-Office-Head). */
  function who(req) {
    const code = String(req.headers["x-office-code"] || "").trim();
    if (!code) return null;
    const hc = hash(code);
    const o = Object.values(offices).find(x => x.codeHash === hc);
    if (!o) return null;
    const head = String(req.headers["x-office-head"] || "");
    const isHead = !!head && crypto.timingSafeEqual(Buffer.from(hash(head)), Buffer.from(o.headHash));
    const d = dev(req.headers["x-device"] || "");
    if (!isHead && o.removed && o.removed.includes(d)) return null;
    return { office: o, head: isHead, device: d };
  }
  const view = (o, head) => ({ id: o.id, name: o.name, place: o.place, share: o.share !== false, files: count(o.id), at: o.at,
    ...(head ? { members: (o.members || []).filter(m => !(o.removed || []).includes(m.d)).map(m => ({ d: m.d, name: m.name, at: m.at })) } : {}) });

  // ---------- the office's files (text only) ----------
  const cache = new Map();   // office id → [ { id, title, kind, text, at } ]
  function files(id) {
    if (cache.has(id)) return cache.get(id);
    const out = [];
    try { for (const f of fs.readdirSync(filesDir(id))) if (f.endsWith(".json")) try { out.push(JSON.parse(fs.readFileSync(path.join(filesDir(id), f), "utf8"))); } catch (e) { } } catch (e) { }
    cache.set(id, out);
    return out;
  }
  const count = id => files(id).length;
  async function addFile(o, body) {
    const name = str(body.name, 200), type = str(body.mediaType, 120);
    if (count(o.id) >= 5000) throw userError("This office already keeps 5000 files.");
    const got = await formats.bulkText(name, type, String(body.data || ""), "o" + o.id);
    if (got.same) return { same: true };
    if (got.wait) throw Object.assign(userError("Today's AI reading for PDFs and photos is used up. Word and text files still go in; the rest tomorrow."), { wait: true });
    const text = String(got.text || "").replace(/\r/g, "").trim().slice(0, 60000);
    if (text.length < 40) throw userError("No text could be read.");
    const hh = formats.hashOf(text);
    if (files(o.id).some(f => f.hash === hh)) return { same: true };
    const where = str(body.path, 300) || name;
    const f = { id: crypto.randomBytes(6).toString("hex"), title: name.replace(/^.*[\\/]/, "").replace(/\.[a-z0-9]+$/i, "") || "File",
      kind: formats.kindOf(where + " " + name), text, at: Date.now(), hash: hh, by: str(body.by, 40) };
    fs.writeFileSync(path.join(filesDir(o.id), f.id + ".json"), JSON.stringify(f));
    files(o.id).push(f);
    // typed in an old Telugu font (Anu, Shree-Lipi…): the Telugu in it reads as English letters; say so
    let oldFont = "";
    if (/\.docx$/i.test(name)) try { oldFont = require("./formats.js").oldFontOf(Buffer.from(String(body.data || ""), "base64")); } catch (e) { }
    return { added: true, title: f.title, kind: f.kind, ...(oldFont ? { oldFont } : {}) };
  }

  /** A finished letter of this office: kept as an example and as history (learning by itself). */
  function addText(o, title, text, kind) {
    text = String(text || "").trim().slice(0, 60000);
    if (text.length < 80) return;
    const hh = formats.hashOf(text);
    if (files(o.id).some(f => f.hash === hh)) return;
    const f = { id: crypto.randomBytes(6).toString("hex"), title: str(title, 100) || "Letter", kind: str(kind, 60) || formats.kindOf(title), text, at: Date.now(), hash: hh, by: "made in Habit" };
    try { fs.writeFileSync(path.join(filesDir(o.id), f.id + ".json"), JSON.stringify(f)); files(o.id).push(f); } catch (e) { }
  }

  // ---------- search: by Sy.No., village or name (no AI) ----------
  function search(o, q) {
    q = str(q, 120);
    const nums = (q.match(/\d+(?:\s*[\/-]\s*\w+)*/g) || []).map(n => n.replace(/\s+/g, ""));
    const words = q.replace(/\d+(?:\s*[\/-]\s*\w+)*/g, " ").split(/[^\p{L}\p{M}]+/u).map(w => w.toLowerCase())
      .filter(w => w.length >= 3 && !/^(sy|syno|survey|no|number|of|the|and|village|గ్రామం|సర్వే|నెంబర్|నంబర్|వివరాలు|details)$/.test(w));
    if (!nums.length && !words.length) return [];
    const esc = s => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const numRe = nums.map(n => new RegExp("(?<![\\d/])" + esc(n) + "(?![\\d])", "i"));
    const res = [];
    for (const f of files(o.id)) {
      const low = f.text.toLowerCase();
      if (!numRe.every(r => r.test(f.text)) || !words.every(w => low.includes(w))) continue;
      const lines = f.text.split(/\n+/).map(l => l.trim()).filter(Boolean);
      const hits = lines.filter(l => numRe.some(r => r.test(l)) || words.some(w => l.toLowerCase().includes(w))).slice(0, 3).map(l => l.slice(0, 220));
      const date = (f.text.match(/\b(\d{1,2})[.\/-](\d{1,2})[.\/-](\d{4})\b/) || [])[0] || "";
      const rc = ((f.text.match(/\b(?:\(M\))?\s*(?:R\.?\s?C\.?|Lr|D\.Dis)\.?\s*No\.?[^\n]{0,40}/i) || [])[0] || "").trim().slice(0, 60);
      const sort = date ? date.replace(/^(\d{1,2})[.\/-](\d{1,2})[.\/-](\d{4})$/, (m, d, mo, y) => y + mo.padStart(2, "0") + d.padStart(2, "0")) : "0";
      res.push({ id: "o" + f.id, title: f.title, date, rc, hits, sort });
    }
    return res.sort((a, b) => a.sort.localeCompare(b.sort)).slice(0, 40).map(({ sort, ...r }) => r);
  }

  // ---------- for letters: the office's old information, and examples of the format ----------
  const tokens = s => [...new Set(String(s || "").toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(w => w.length >= 3))];
  /** The office's old files that belong to this matter (shared words, the same Sy.No.): for its own members only. */
  function related(officeId, request, max = 3) {
    const q = tokens(request);
    if (!q.length) return "";
    const nums = (String(request).match(/\d+(?:\/\w+)?/g) || []);
    const scored = files(officeId).map(f => {
      const t = new Set(tokens(f.title + " " + f.text.slice(0, 20000)));
      let s = q.filter(w => t.has(w)).length;
      for (const n of nums) if (new RegExp("(?<![\\d/])" + n.replace(/[/]/g, "\\/") + "(?![\\d])").test(f.text)) s += 4;
      return { f, s };
    }).filter(x => x.s >= 4).sort((a, b) => b.s - a.s).slice(0, max);
    if (!scored.length) return "";
    return "\n\nYOUR OFFICE'S EARLIER FILES ABOUT THIS (history of the matter; use only what really belongs to it):\n" +
      scored.map(({ f }) => `--- ${f.title} ---\n${f.text.slice(0, 3500)}`).join("\n\n");
  }
  /** The closest earlier letter of this kind: the member's own office as it is; else another office that shares, blanked. */
  function example(officeId, docType, request) {
    const kw = tokens(docType), rw = new Set(tokens(request));
    const score = f => tokens(f.title + " " + f.kind).filter(w => kw.includes(w)).length * 5 + tokens(f.title + " " + f.text.slice(0, 1500)).filter(w => rw.has(w)).length * 0.2;
    const best = list => { let b = null, s = 0; for (const f of list) { const x = score(f); if (x > s) { s = x; b = f; } } return s >= 1 ? b : null; };
    if (officeId) { const own = best(files(officeId)); if (own) return { text: own.text, own: true, kind: own.kind }; }
    const shared = Object.values(offices).filter(o => o.share !== false && o.id !== officeId).flatMap(o => files(o.id));
    const b = best(shared);
    return b ? { text: formats.blank(b.text), own: false, kind: b.kind } : null;
  }

  // ---------- routes ----------
  const addHits = new Map();
  async function handle(req, res, url, origin) {
    const p = url.pathname;
    if (!p.startsWith("/api/office/") || p === "/api/office/search" || p === "/api/office/read") return false;
    const send = (c, b) => h.send(res, c, b, origin);
    if (req.method !== "POST") { send(404, { error: "Not found" }); return true; }
    // (a folder of old files is many requests: adding files has its own, bigger allowance)
    if (p === "/api/office/add" ? !h.allowed(h.clientIp(req), addHits, 600) : !h.allowed(h.clientIp(req))) { send(429, { error: "Too many requests. Please wait a few minutes." }); return true; }
    let body;
    try { body = await h.readBody(req, p === "/api/office/add" ? 22 * 1024 * 1024 : 64 * 1024); } catch (e) { send(400, { error: "Bad request." }); return true; }
    try {
      if (p === "/api/office/create") {
        const name = str(body.name, 100);
        if (name.length < 3) throw userError("Write the office's name.");
        if (Object.keys(offices).length >= 20000) throw userError("No more offices can be made now.");
        const code = newCode(name), headKey = crypto.randomBytes(9).toString("base64url");
        const o = { id: crypto.randomBytes(6).toString("hex"), name, place: str(body.place, 120), codeHash: hash(code), headHash: hash(headKey),
          share: body.share !== false, at: Date.now(), members: [], removed: [] };
        offices[o.id] = o; saveIndex();
        send(200, { office: view(o, true), code, headKey }); return true;
      }
      if (p === "/api/office/join") {
        const hc = hash(str(body.code, 40));
        const o = Object.values(offices).find(x => x.codeHash === hc);
        if (!o) throw userError("No office has this code. Check it with your office head.");
        const d = dev(req.headers["x-device"] || body.device || "");
        if ((o.removed || []).includes(d)) throw userError("The office head has removed this phone.");
        o.members = (o.members || []).filter(m => m.d !== d).concat([{ d, name: str(body.name, 40) || "Staff", at: Date.now() }]).slice(-500);
        saveIndex();
        send(200, { office: view(o, false) }); return true;
      }
      const w = who(req);
      if (!w) { send(403, { error: "Join your office first (Office letters › My office)." }); return true; }
      const o = w.office;
      if (p === "/api/office/me") { send(200, { office: view(o, w.head), head: w.head }); return true; }
      if (p === "/api/office/add") {
        const out = await addFile(o, { ...body, by: w.head ? "head" : w.device });
        send(200, out); return true;
      }
      if (!w.head) { send(403, { error: "Only the office head can do this." }); return true; }
      if (p === "/api/office/new-code") {
        const code = newCode(o.name); o.codeHash = hash(code); saveIndex();
        send(200, { code }); return true;
      }
      if (p === "/api/office/remove") {
        const d = str(body.d, 16); o.removed = [...new Set([...(o.removed || []), d])].slice(-1000); saveIndex();
        send(200, { office: view(o, true) }); return true;
      }
      if (p === "/api/office/settings") {
        if (typeof body.share === "boolean") o.share = body.share;
        if (str(body.name, 100).length >= 3) o.name = str(body.name, 100);
        saveIndex(); send(200, { office: view(o, true) }); return true;
      }
      if (p === "/api/office/delete-file") {
        const id = str(body.id, 20).replace(/^o/, "");
        try { fs.unlinkSync(path.join(filesDir(o.id), path.basename(id) + ".json")); } catch (e) { }
        cache.delete(o.id); send(200, { ok: true }); return true;
      }
      send(404, { error: "Not found" }); return true;
    } catch (e) {
      if (e.user) { send(e.wait ? 429 : 400, { error: e.message }); return true; }
      console.error("Office:", e.message);
      send(500, { error: "Something went wrong. Please try again." }); return true;
    }
  }

  /** For the app owner (Teach page): every office, and deleting one (its files go too). */
  const listAll = () => Object.values(offices).map(o => ({ id: o.id, name: o.name, place: o.place, share: o.share !== false, files: count(o.id),
    members: (o.members || []).filter(m => !(o.removed || []).includes(m.d)).length, at: o.at })).sort((a, b) => b.at - a.at);
  function removeOffice(id) {
    if (!offices[id]) return false;
    delete offices[id]; saveIndex(); cache.delete(id);
    try { fs.rmSync(path.join(DIR, path.basename(id)), { recursive: true, force: true }); } catch (e) { }
    return true;
  }

  return { handle, who, search, related, example, addText, listAll, removeOffice, read: (o, id) => { const f = files(o.id).find(x => "o" + x.id === id); return f ? f.text : ""; }, count };
};
