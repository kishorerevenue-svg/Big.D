// Habit's library, in two boxes (and the office space, offices.js, is the third):
//  📚 Public library — like Wikipedia: anyone who has logged in adds GOs, circulars, scheme details, notices, rules…;
//     everyone can find and read them, and the pet answers from all of them (learn.js "public").
//     A file with ID, phone or bank numbers waits for the owner (held) instead of going public.
//  📁 My files — each person's own small files: only for them (and their pet answers from them). Kept on their phone
//     and here, so they also open on the web (bigdai.tech/library) and on a new phone.
// Only the TEXT of a file is kept, never the file itself. The owner sees everything on the Teach page: the public
// library in full, and the titles of people's own files (to remove misuse), not what they say.
const crypto = require("crypto");

module.exports = function makeLibrary({ learn, formats, social, h }) {
  const { send, readBody, allowed, clientIp } = h;
  const MAX_FILE = 15 * 1024 * 1024;
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);

  // ID / phone / bank numbers: such a file is not put in the public library until the owner has looked at it
  const ID_RE = [
    /\b[2-9]\d{3}[ -]?\d{4}[ -]?\d{4}\b/,                          // Aadhaar
    /\b[A-Z]{5}\d{4}[A-Z]\b/,                                      // PAN
    /(?:^|[^\d])(?:\+91[ -]?)?[6-9]\d{9}(?!\d)/,                   // mobile number
    /(?:a\/c|acc(?:oun)?t|ఖాతా|బ్యాంక్)[^\n\d]{0,25}\d{9,18}/i,      // bank account
  ];
  const personal = t => ID_RE.some(r => r.test(t));

  // no real limit for people adding files; only a guard against a runaway script flooding the server
  const per = new Map();
  setInterval(() => per.clear(), 24 * 3600e3).unref();
  const tooMany = (k, n) => { const c = (per.get(k) || 0) + 1; per.set(k, c); return c > n; };

  async function textOf(name, type, data) {
    if (require("./formats.js").OWN_FORMAT.test(name || "") || type.includes("wordprocessingml") || type.includes("spreadsheetml")) return { text: await formats.textOf(name, type, data), via: "file" };
    return learn.fileToTextVia(name || "file", type, data);
  }
  const hashOf = t => crypto.createHash("sha1").update(String(t).replace(/\s+/g, " ").trim()).digest("hex").slice(0, 16);
  const view = (it, full = false) => ({ id: it.id, title: it.title, added: it.added, chars: it.chars, by: it.byName || "",
    ...(full ? { scope: learn.scopeOf(it), held: !!it.held, byUid: it.by || "", via: learn.viaOf(it) } : {}) });
  const isPublic = it => it && learn.scopeOf(it) === "public";
  const isMine = (it, uid) => it && uid && it.scope === "my:" + uid;
  const snippet = (text, q) => {
    const words = String(q || "").toLowerCase().split(/\s+/).filter(w => w.length > 1);
    const t = String(text || "");
    let at = -1; for (const w of words) { at = t.toLowerCase().indexOf(w); if (at >= 0) break; }
    const s = Math.max(0, at - 80);
    return (s > 0 ? "…" : "") + t.slice(s, s + 260).replace(/\s+/g, " ").trim() + "…";
  };

  /** Adds a file or text to the public library or to the person's own files. */
  async function add(req, body, who, where) {
    const name = str(body.name, 200) || "file";
    const type = str(body.mediaType, 100);
    let text = str(body.text, 500_000), via = "typed";
    if (!text) {
      const data = String(body.data || "");
      if (!data) return { status: 400, error: "Choose a file, or write the text." };
      if (data.length * 0.75 > MAX_FILE) return { status: 413, error: "That file is too big (15 MB at most)." };
      try { const r = await textOf(name, type, data); text = String(r.text || "").trim(); via = r.via; }
      catch (e) { return { status: e.user ? 400 : 502, error: e.user ? e.message : "Couldn't read that file now. Try again later." }; }
    }
    if (text.replace(/\s+/g, "").length < 30) return { status: 400, error: "There were almost no words in it to keep." };
    const title = str(body.title, 200) || name.replace(/\.[a-z0-9]+$/i, "");
    const h = hashOf(text);
    if (where === "public") {
      const same = learn.listKnowledge().find(k => k.hash === h && learn.scopeOf(k) !== "owner" && !String(k.scope || "").startsWith("my:"));
      if (same) return { status: 200, item: view(same), same: true };
      const held = personal(text);
      const it = learn.addKnowledge(title, "library:" + name, text.slice(0, 500_000), via, { scope: "public", by: who.uid, byName: who.name, hash: h, ...(held ? { held: true } : {}) });
      return { status: 200, item: view(it), held };
    }
    const scope = "my:" + who.uid;
    const same = learn.listKnowledge().find(k => k.hash === h && k.scope === scope);
    if (same) return { status: 200, item: view(same), same: true };
    const it = learn.addKnowledge(title, "my:" + name, text.slice(0, 500_000), via, { scope, by: who.uid, byName: who.name, hash: h });
    return { status: 200, item: view(it), text: text.slice(0, 500_000) };
  }

  function search(q, uid, where) {
    const out = [];
    const want = where === "mine" ? (c => c.scope === "my:" + uid) : (c => c.scope === "public");
    for (const hit of learn.searchKnowledge(q, 60, false, where === "mine" ? uid : "", 0.05)) {
      if (!want(hit) || out.find(o => o.id === hit.id)) continue;
      const it = learn.itemOf(hit.id); if (!it) continue;
      out.push(Object.assign(view(it), { snippet: snippet(hit.text, q) }));
      if (out.length >= 20) break;
    }
    // also by the title
    const ql = String(q).toLowerCase();
    for (const it of learn.listKnowledge()) {
      if (out.length >= 20) break;
      if (out.find(o => o.id === it.id)) continue;
      if ((where === "mine" ? isMine(it, uid) : isPublic(it)) && it.title.toLowerCase().includes(ql)) out.push(Object.assign(view(it), { snippet: "" }));
    }
    return out;
  }

  // ---------- routes ----------
  async function handle(req, res, url, origin) {
    const p = url.pathname;
    if (!p.startsWith("/api/library/")) return false;
    if (req.method !== "POST") { send(res, 404, { error: "Not found" }, origin); return true; }
    if (!allowed("lib:" + clientIp(req), undefined, 1200)) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); req.resume(); return true; }
    let body;
    try { body = await readBody(req, /\/(add|my-add)$/.test(p) ? 21 * 1024 * 1024 : 64 * 1024); }
    catch (e) { send(res, e.message === "too_large" ? 413 : 400, { error: e.message === "too_large" ? "That file is too big (15 MB at most)." : "Bad request." }, origin); return true; }
    const ok = (o) => send(res, 200, o, origin);
    const no = (s, e) => send(res, s, { error: e }, origin);

    // the public library: anyone may look
    if (p === "/api/library/search") {
      const q = str(body.q, 200);
      if (q.length < 2) {                    // nothing typed: the newest
        const items = learn.listKnowledge().filter(isPublic).slice(0, 30).map(it => view(it));
        return ok({ found: items, total: learn.listKnowledge().filter(isPublic).length }), true;
      }
      return ok({ found: search(q, "", "public") }), true;
    }
    if (p === "/api/library/read") {
      const it = learn.itemOf(str(body.id, 40));
      if (isPublic(it)) return ok({ item: view(it), text: learn.readKnowledge(it.id) }), true;
      // their own file (from the phone, or the web with a login)
      const who = await social.account(req, body);
      if (who.uid && isMine(it, who.uid)) return ok({ item: view(it), text: learn.readKnowledge(it.id) }), true;
      return no(404, "That file is not there (or it isn't yours)."), true;
    }

    // adding and their own files: they must be logged in (from the phone, or the web with the mobile number + password)
    const who = await social.account(req, body);
    if (who.status) return no(who.status, who.error), true;
    if (!who.uid || !who.login) return no(401, "Log in first (your mobile number and password), then add files."), true;
    if (who.banned) return no(403, "This account can't add files."), true;

    if (p === "/api/library/add") {
      if (tooMany("pub:" + who.uid, 3000)) return no(429, "That's a lot for one day. Add more tomorrow."), true;
      const r = await add(req, body, who, "public");
      if (r.error) return no(r.status, r.error), true;
      return ok({ item: r.item, same: !!r.same, held: !!r.held }), true;
    }
    if (p === "/api/library/my-add") {
      if (tooMany("my:" + who.uid, 3000)) return no(429, "That's a lot for one day. Add more tomorrow."), true;
      const r = await add(req, body, who, "mine");
      if (r.error) return no(r.status, r.error), true;
      return ok({ item: r.item, same: !!r.same, text: r.text || "" }), true;
    }
    if (p === "/api/library/my-list") {
      const items = learn.listKnowledge().filter(it => isMine(it, who.uid)).map(it => view(it));
      return ok({ items, name: who.name }), true;
    }
    if (p === "/api/library/my-search") return ok({ found: search(str(body.q, 200), who.uid, "mine") }), true;
    if (p === "/api/library/delete") {
      // their own file, or a public one they added themselves
      const it = learn.itemOf(str(body.id, 40));
      if (!it || !(isMine(it, who.uid) || (it.by === who.uid && learn.scopeOf(it) !== "owner"))) return no(404, "That file is not yours."), true;
      return ok({ ok: learn.removeKnowledge(it.id) }), true;
    }
    if (p === "/api/library/mine-public") {
      const items = learn.listKnowledge().filter(it => it.by === who.uid && !String(it.scope || "").startsWith("my:")).map(it => view(it, true));
      return ok({ items }), true;
    }
    no(404, "Not found");
    return true;
  }

  /** For the Teach page (the owner): the whole library with who added what. */
  function overview() {
    const all = learn.listKnowledge();
    const people = {};
    for (const it of all) if (String(it.scope || "").startsWith("my:")) {
      const k = it.scope.slice(3); const pp = people[k] || (people[k] = { uid: k, name: it.byName || "", files: [] });
      pp.files.push({ id: it.id, title: it.title, added: it.added, chars: it.chars });
    }
    return {
      held: all.filter(it => it.held).map(it => view(it, true)),
      public: all.filter(it => !it.held && learn.scopeOf(it) === "public" && it.by).map(it => view(it, true)),
      people: Object.values(people).sort((a, b) => b.files.length - a.files.length),
    };
  }
  /** The owner lets a held file into the public library. */
  const approve = id => { const it = learn.itemOf(id); return it && it.held ? !!learn.updateKnowledge(id, { held: undefined }) : false; };

  return { handle, overview, approve };
};
