// Habit Chat: chats between people who use Habit AI, like WhatsApp: one-to-one and groups, photos, voice notes, ✓✓
// ticks, reminders and group tasks.
//  - Locked end to end: every message is locked on the phone with the keys of the people in the chat (P-256 ECDH +
//    AES-256-GCM, see ChatCrypto.kt), so this server only passes on locked messages it can't read. A message is
//    deleted here as soon as everyone in the chat has it (or after 30 days); photos and voice notes after 30 days.
//  - People find each other by their Habit @name, a QR code or an invite link (/c/<name>).
//  - A first message from someone new waits in Requests until it is accepted (at most 5 messages until then);
//    being added to a group by someone new is a request too. Block and report are always there.
//  - Groups have admins: they add and remove people, rename the group, make other admins, and close tasks. A task (a
//    reminder, or work given to a member) stays pinned at the top until an admin closes it; the member adds remarks
//    and marks it done. In a one-to-one chat both people can close tasks.
//  - New messages reach the phone by a long wait: the phone asks, and the server answers as soon as something comes
//    (within 25 s). Phones confirm what they got with their next ask, and only then is it marked delivered (✓✓).
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

module.exports = function makeChat({ DATA_DIR, social, h }) {
  const FILE = path.join(DATA_DIR, "chat.json");
  const FDIR = path.join(DATA_DIR, "chat-files");
  fs.mkdirSync(FDIR, { recursive: true });
  const MAX_GROUP = 100, KEEP = 30 * 86400e3, BEFORE_ACCEPT = 5, WAIT_MS = Number(process.env.CHAT_WAIT_MS || 25000);
  const FILE_MAX = 16 * 1024 * 1024, FILES_MAX_BYTES = Number(process.env.CHAT_FILES_MAX_MB || 5000) * 1024 * 1024;
  let db = { keys: {}, convs: {}, msgs: {}, inbox: {}, blocks: {}, tasks: {}, reports: [], files: {}, day: "", sent: {} };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  let timer = null;
  const save = () => { clearTimeout(timer); timer = setTimeout(() => { try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("chat save:", e.message); } }, 300); };
  const str = (v, n) => String(v === undefined || v === null ? "" : v).slice(0, n);
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);
  const blocked = (a, b) => (db.blocks[a] || []).includes(b);        // a blocked b
  const person = uid => social.person(uid) || { uid, name: "Habit friend", handle: "", emoji: "🐾", avatar: "", gone: true };

  // ---------- each person's events (what their phone hasn't confirmed yet) ----------
  const waiters = new Map();   // uid -> Set of functions that wake a waiting sync
  function box(uid) { return db.inbox[uid] || (db.inbox[uid] = { n: 0, ev: [] }); }
  function push(uid, ev) {
    const b = box(uid);
    ev.n = ++b.n; ev.at = Date.now();
    // one "conversation changed" or "ticks changed" event per chat is enough
    if (ev.k === "c" || ev.k === "r") b.ev = b.ev.filter(e => !(e.k === ev.k && e.cid === ev.cid));
    b.ev.push(ev);
    if (b.ev.length > 5000) b.ev = b.ev.slice(-5000);
    const w = waiters.get(uid);
    if (w) for (const f of [...w]) f();
  }
  const pushAll = (conv, ev, except) => { for (const u of conv.members) if (u !== except) push(u, Object.assign({}, ev)); };

  // ---------- chats ----------
  const dmId = (a, b) => "d" + crypto.createHash("sha256").update([a, b].sort().join("|")).digest("hex").slice(0, 16);
  /** Do these two people already know each other (an accepted chat, or a group both accepted)? */
  function knows(a, b) {
    for (const c of Object.values(db.convs)) if (c.members.includes(a) && c.members.includes(b) && c.st[a] && c.st[a].ok && c.st[b] && c.st[b].ok) return true;
    return false;
  }
  function view(c, me) {
    const other = c.kind === "dm" ? c.members.find(u => u !== me) || c.gone : "";
    const p = other ? person(other) : null;
    return { cid: c.id, kind: c.kind, name: c.kind === "dm" ? p.name : c.name, handle: p ? p.handle : "", emoji: c.kind === "dm" ? p.emoji : "👥", avatar: p ? p.avatar : "",
      members: c.members.map(u => Object.assign(person(u), { admin: c.admins.includes(u), key: !!db.keys[u] })), admins: c.admins.slice(), by: c.by, at: c.at, last: c.last,
      accepted: !!(c.st[me] && c.st[me].ok), seq: c.seq, st: ticks(c), blocked: !!other && blocked(me, other), member: c.members.includes(me) };
  }
  const ticks = c => { const o = {}; for (const u of c.members) o[u] = { d: (c.st[u] || {}).d || 0, r: (c.st[u] || {}).r || 0 }; return o; };
  function sysMsg(c, sys) {
    const m = { seq: ++c.seq, id: "s" + c.seq, from: sys.by || "", at: Date.now(), type: "sys", sys };
    (db.msgs[c.id] = db.msgs[c.id] || []).push(m);
    c.last = m.at;
    for (const u of c.members) push(u, { k: "m", cid: c.id, seq: m.seq });
    return m;
  }
  const msgView = (m, me, cid) => ({ cid, seq: m.seq, id: m.id, from: m.from, at: m.at, type: m.type, task: m.task || null, sys: m.sys || null,
    env: m.env ? { v: m.env.v, epk: m.env.epk, iv: m.env.iv, ct: m.env.ct, key: (m.env.keys || {})[me] || "" } : null });

  /** Marks what this phone confirmed (events up to [upTo]) as delivered, and forgets messages everyone has. */
  function ack(me, upTo) {
    const b = box(me);
    if (!upTo || !b.ev.length) return;
    const done = b.ev.filter(e => e.n <= upTo);
    if (!done.length) return;
    b.ev = b.ev.filter(e => e.n > upTo);
    const touched = new Set();
    for (const e of done) {
      if (e.k !== "m") continue;
      const c = db.convs[e.cid];
      if (!c || !c.st[me]) continue;
      if (e.seq > (c.st[me].d || 0)) { c.st[me].d = e.seq; touched.add(c.id); }
    }
    for (const cid of touched) { const c = db.convs[cid]; pushAll(c, { k: "r", cid }, me); purge(c); }
    save();
  }
  /** A message is deleted from the server once every other member has it. */
  function purge(c) {
    const list = db.msgs[c.id];
    if (!list || !list.length) return;
    db.msgs[c.id] = list.filter(m => c.members.some(u => u !== m.from && ((c.st[u] || {}).d || 0) < m.seq));
  }

  // ---------- requests ----------
  const sentToday = me => { if (db.day !== today()) { db.day = today(); db.sent = {}; } return db.sent[me] || 0; };
  const minute = new Map();
  function rate(me) {
    const now = Date.now(), l = (minute.get(me) || []).filter(t => now - t < 60e3);
    if (l.length >= 60) return false;
    l.push(now); minute.set(me, l); return true;
  }

  function keyOk(pub) {
    try {
      const k = crypto.createPublicKey({ key: Buffer.from(pub, "base64"), format: "der", type: "spki" });
      return k.asymmetricKeyType === "ec" && (k.asymmetricKeyDetails || {}).namedCurve === "prime256v1";
    } catch (e) { return false; }
  }
  const kidOf = pub => crypto.createHash("sha256").update(pub).digest("hex").slice(0, 16);
  const keysOf = uids => { const o = {}; for (const u of uids) if (db.keys[u]) o[u] = { pub: db.keys[u].pub, kid: db.keys[u].kid }; return o; };

  async function readRaw(req, max) {
    return await new Promise((resolve, reject) => {
      const parts = []; let n = 0;
      req.on("data", d => { n += d.length; if (n > max) { reject(Object.assign(new Error("too big"), { status: 413 })); req.destroy(); } else parts.push(d); });
      req.on("end", () => resolve(Buffer.concat(parts)));
      req.on("error", reject);
    });
  }

  // ---------- the API ----------
  async function handle(req, res, url, origin) {
    const p = url.pathname;
    if (p.startsWith("/c/") && req.method === "GET") return invitePage(req, res, decodeURIComponent(p.slice(3)));
    if (!p.startsWith("/api/chat/")) return false;
    const send = (st, o) => { h.send(res, st, o, origin); return true; };
    const me = social.who(req);
    // (by person, not by internet address: many phones share one address on mobile data)
    if (!h.allowed(me ? "chat:" + me : "chatip:" + h.clientIp(req), undefined, me ? 900 : 300)) return send(429, { error: "Too many requests. Please wait a few minutes." });
    const mine = me && social.person(me);
    if (!mine) return send(403, { error: "Make your Habit profile first: write your name.", needProfile: true });
    let body = {};
    if (req.method === "POST" && p !== "/api/chat/file") { try { body = await h.readBody(req, 1024 * 1024); } catch (e) { return send(400, { error: "Bad request." }); } }

    // this phone's lock: its public key (the private key never leaves the phone)
    if (p === "/api/chat/hello" && req.method === "POST") {
      const pub = str(body.pub, 400);
      if (!keyOk(pub)) return send(400, { error: "Bad key." });
      const kid = kidOf(pub), old = db.keys[me];
      if (!old || old.kid !== kid) {
        db.keys[me] = { pub, kid, at: Date.now() };
        db.inbox[me] = { n: box(me).n, ev: [] };        // a new phone: old locked messages were for the old key
        for (const c of Object.values(db.convs)) if (c.members.includes(me)) pushAll(c, { k: "c", cid: c.id }, me);
        save();
      }
      return send(200, { me: Object.assign({}, mine, { kid }), cursor: box(me).n });
    }
    if (!db.keys[me]) return send(409, { error: "Open Habit Chat in the app first.", needKey: true });

    if (p === "/api/chat/convs" && req.method === "GET") {
      const list = Object.values(db.convs).filter(c => c.members.includes(me)).map(c => view(c, me));
      return send(200, { convs: list, cursor: box(me).n, me: mine });
    }
    if (p === "/api/chat/find" && req.method === "GET") {
      return send(200, { people: social.findPeople(url.searchParams.get("q"), me, 15, u => !!db.keys[u] && !blocked(u, me)) });
    }
    if (p === "/api/chat/user" && req.method === "GET") {
      const u = social.byHandle(url.searchParams.get("h"));
      if (!u || !db.keys[u.uid]) return send(404, { error: u ? "This person hasn't opened Habit Chat yet." : "No one has that @name." });
      return send(200, { person: u });
    }
    if (p === "/api/chat/keys" && req.method === "GET") {
      const c = db.convs[str(url.searchParams.get("cid"), 40)];
      if (!c || !c.members.includes(me)) return send(404, { error: "Not found" });
      return send(200, { keys: keysOf(c.members) });
    }

    // the long wait for new messages
    if (p === "/api/chat/sync" && req.method === "GET") {
      const c0 = Math.max(0, Number(url.searchParams.get("c")) || 0);
      ack(me, c0);
      const pending = () => box(me).ev.filter(e => e.n > c0);
      let ev = pending();
      if (!ev.length && url.searchParams.get("wait") === "1") {
        await new Promise(resolve => {
          let set = waiters.get(me); if (!set) waiters.set(me, set = new Set());
          let t = null;
          const wake = () => { clearTimeout(t); set.delete(wake); if (!set.size) waiters.delete(me); resolve(); };
          set.add(wake);
          t = setTimeout(wake, WAIT_MS);
          res.on("close", wake);
        });
        if (res.writableEnded || res.destroyed) return true;
        ev = pending();
      }
      ev = ev.slice(0, 300);
      const out = { cursor: ev.length ? ev[ev.length - 1].n : c0, convs: [], msgs: [], ticks: [], gone: [], more: pending().length > ev.length };
      const seen = new Set();
      for (const e of ev) {
        const c = db.convs[e.cid];
        if (e.k === "x" || (c && !c.members.includes(me))) { if (!seen.has("x" + e.cid)) { seen.add("x" + e.cid); out.gone.push(e.cid); } continue; }
        if (!c) continue;
        if (e.k === "c" && !seen.has("c" + e.cid)) { seen.add("c" + e.cid); out.convs.push(view(c, me)); }
        else if (e.k === "r" && !seen.has("r" + e.cid)) { seen.add("r" + e.cid); out.ticks.push({ cid: c.id, st: ticks(c) }); }
        else if (e.k === "m") { const m = (db.msgs[c.id] || []).find(x => x.seq === e.seq); if (m) out.msgs.push(msgView(m, me, c.id)); }
      }
      return send(200, out);
    }

    // photos and voice notes (locked on the phone before they come here)
    if (p === "/api/chat/file" && req.method === "POST") {
      if (!rate(me)) return send(429, { error: "Too many. Please wait a little." });
      let buf;
      try { buf = await readRaw(req, FILE_MAX); } catch (e) { return send(e.status || 400, { error: e.status === 413 ? "That file is too big (16 MB at most)." : "Upload failed." }); }
      if (buf.length < 16) return send(400, { error: "Empty file." });
      const id = crypto.randomBytes(16).toString("hex");
      fs.writeFileSync(path.join(FDIR, id), buf);
      db.files[id] = { by: me, at: Date.now(), size: buf.length };
      tidyFiles(); save();
      return send(200, { id });
    }
    if (p === "/api/chat/file" && req.method === "GET") {
      const id = str(url.searchParams.get("id"), 40);
      if (!/^[0-9a-f]{32}$/.test(id) || !db.files[id]) return send(404, { error: "That photo or voice note is no longer on the server." });
      try { const buf = fs.readFileSync(path.join(FDIR, id)); res.writeHead(200, { "Content-Type": "application/octet-stream", "Content-Length": buf.length, "Cache-Control": "private, max-age=86400" }); res.end(buf); }
      catch (e) { send(404, { error: "Not found" }); }
      return true;
    }
    if (req.method === "POST") {
      // open a one-to-one chat (it reaches them as a request with the first message, unless they know you)
      if (p === "/api/chat/open") {
        const other = str(body.uid, 40) || ((social.byHandle(body.handle) || {}).uid || "");
        if (!other || other === me || !social.person(other)) return send(404, { error: "No one has that @name." });
        if (!db.keys[other]) return send(409, { error: "This person hasn't opened Habit Chat yet. Send them your invite link." });
        const cid = dmId(me, other);
        let c = db.convs[cid];
        if (!c) {
          c = db.convs[cid] = { id: cid, kind: "dm", members: [me, other], admins: [me, other], name: "", by: me, at: Date.now(), last: Date.now(), seq: 0,
            st: { [me]: { ok: true, d: 0, r: 0 }, [other]: { ok: knows(other, me), d: 0, r: 0 } }, told: { [me]: true } };
          save();
        }
        if (!c.st[me].ok) { c.st[me].ok = true; save(); }
        return send(200, { conv: view(c, me) });
      }
      // a new group: the one who makes it is its first admin
      if (p === "/api/chat/group") {
        const name = str(body.name, 40).replace(/[\r\n]/g, " ").trim();
        if (name.length < 2) return send(400, { error: "Give the group a name." });
        const want = [...new Set((Array.isArray(body.members) ? body.members : []).map(x => str(x, 40)))].filter(u => u !== me && social.person(u) && db.keys[u] && !blocked(u, me));
        if (want.length > MAX_GROUP - 1) return send(400, { error: `A group can have up to ${MAX_GROUP} people.` });
        const cid = "g" + crypto.randomBytes(8).toString("hex");
        const c = db.convs[cid] = { id: cid, kind: "group", members: [me, ...want], admins: [me], name, by: me, at: Date.now(), last: Date.now(), seq: 0, st: { [me]: { ok: true, d: 0, r: 0 } }, told: null };
        for (const u of want) c.st[u] = { ok: knows(u, me), d: 0, r: 0 };
        sysMsg(c, { what: "created", by: me, name, who: want });
        pushAll(c, { k: "c", cid });
        save();
        return send(200, { conv: view(c, me) });
      }
      const c = db.convs[str(body.cid, 40)];
      if (!c || !c.members.includes(me)) return send(404, { error: "This chat isn't there any more." });
      const admin = c.kind === "dm" || c.admins.includes(me);

      if (p === "/api/chat/group/edit") {
        if (c.kind !== "group") return send(400, { error: "Only groups can be changed." });
        if (!admin) return send(403, { error: "Only a group admin can do that." });
        const list = k => [...new Set((Array.isArray(body[k]) ? body[k] : []).map(x => str(x, 40)))];
        const add = list("add").filter(u => !c.members.includes(u) && social.person(u) && db.keys[u] && !blocked(u, me));
        if (c.members.length + add.length > MAX_GROUP) return send(400, { error: `A group can have up to ${MAX_GROUP} people.` });
        for (const u of add) { c.members.push(u); c.st[u] = { ok: knows(u, me), d: c.seq, r: c.seq }; }
        if (add.length) sysMsg(c, { what: "added", by: me, who: add });
        const remove = list("remove").filter(u => c.members.includes(u) && u !== me);
        for (const u of remove) { c.members = c.members.filter(x => x !== u); c.admins = c.admins.filter(x => x !== u); delete c.st[u]; push(u, { k: "x", cid: c.id }); }
        if (remove.length) sysMsg(c, { what: "removed", by: me, who: remove });
        const up = list("admin").filter(u => c.members.includes(u) && !c.admins.includes(u));
        for (const u of up) c.admins.push(u);
        const down = list("unadmin").filter(u => c.admins.includes(u) && u !== me);
        c.admins = c.admins.filter(u => !down.includes(u));
        if (up.length || down.length) sysMsg(c, { what: "admins", by: me, who: up, not: down });
        const name = str(body.name, 40).replace(/[\r\n]/g, " ").trim();
        if (name && name.length >= 2 && name !== c.name) { c.name = name; sysMsg(c, { what: "name", by: me, name }); }
        pushAll(c, { k: "c", cid: c.id });
        purge(c); save();
        return send(200, { conv: view(c, me) });
      }
      if (p === "/api/chat/leave") {
        if (c.kind === "dm") return send(200, { ok: true });      // a one-to-one chat is only cleared on the phone
        c.members = c.members.filter(u => u !== me); c.admins = c.admins.filter(u => u !== me); delete c.st[me];
        push(me, { k: "x", cid: c.id });
        if (c.kind === "group" && c.members.length) {
          if (!c.admins.length) c.admins.push(c.members[0]);          // a group always has an admin
          sysMsg(c, { what: "left", by: me });
          pushAll(c, { k: "c", cid: c.id });
        }
        if (!c.members.length) { delete db.convs[c.id]; delete db.msgs[c.id]; delete db.tasks[c.id]; }
        save();
        return send(200, { ok: true });
      }
      if (p === "/api/chat/accept") { c.st[me].ok = true; push(me, { k: "c", cid: c.id }); save(); return send(200, { conv: view(c, me) }); }
      if (p === "/api/chat/read") {
        const seq = Math.min(c.seq, Math.max(0, Number(body.seq) || 0));
        const s = c.st[me];
        if (seq > (s.r || 0)) { s.r = seq; s.d = Math.max(s.d || 0, seq); pushAll(c, { k: "r", cid: c.id }, me); purge(c); save(); }
        return send(200, { ok: true });
      }
      if (p === "/api/chat/report") {
        const who = str(body.uid, 40);
        const msgs = (Array.isArray(body.msgs) ? body.msgs : []).slice(-10).map(m => ({ from: str(m && m.from, 40), text: str(m && m.text, 1000), at: Number(m && m.at) || 0 }));
        db.reports.push({ at: Date.now(), by: me, who: c.members.includes(who) ? who : "", cid: c.id, kind: c.kind, name: c.kind === "group" ? c.name : "", reason: str(body.reason, 200), msgs });
        db.reports = db.reports.slice(-500);
        save();
        return send(200, { ok: true });
      }
      if (p === "/api/chat/send") {
        const type = body.type === "task" ? "task" : "msg";
        const env = body.env || {};
        if (env.v !== 1 || typeof env.ct !== "string" || typeof env.iv !== "string" || typeof env.epk !== "string" || !env.keys || typeof env.keys !== "object" || env.ct.length > 200000)
          return send(400, { error: "Bad message." });
        if (!rate(me) || sentToday(me) >= 3000) return send(429, { error: "Too many messages. Please wait a little." });
        const id = str(body.id, 40);
        const list = db.msgs[c.id] || (db.msgs[c.id] = []);
        const dup = id && list.find(m => m.from === me && m.id === id);
        if (dup) return send(200, { seq: dup.seq, at: dup.at });
        // the lock must be for everyone in the chat with their current keys (someone new, or a new phone: lock again)
        const kids = env.kid || {};
        const need = c.members.filter(u => db.keys[u]);
        if (need.some(u => !env.keys[u] || kids[u] !== db.keys[u].kid)) return send(409, { error: "keys", keys: keysOf(c.members) });
        const other = c.kind === "dm" ? c.members.find(u => u !== me) : "";
        if (other && (blocked(other, me) || blocked(me, other))) {
          if (blocked(me, other)) return send(403, { error: "You blocked this person. Unblock them to send a message." });
          return send(200, { seq: 0, at: Date.now() });          // they blocked you: it stays with one tick
        }
        if (other && !c.st[other].ok && (c.st[me].sent || 0) >= BEFORE_ACCEPT)
          return send(429, { error: "Please wait until they accept your chat request." });
        if (!c.st[me].ok) c.st[me].ok = true;                      // replying to a request accepts it
        // tasks: anyone can add one (a reminder, or work for someone); the assigned person adds remarks and marks it
        // done; only an admin closes or reopens it (in a one-to-one chat, either of you)
        let task = null;
        if (type === "task") {
          const t = body.task || {}, tid = str(t.id, 40), act = ["new", "remark", "done", "close", "reopen"].includes(t.action) ? t.action : "";
          if (!tid || !act) return send(400, { error: "Bad task." });
          const T = db.tasks[c.id] || (db.tasks[c.id] = {});
          if (act === "new") {
            if (T[tid]) return send(409, { error: "That task is already there." });
            T[tid] = { by: me, to: c.members.includes(str(t.to, 40)) ? str(t.to, 40) : "", state: "open", at: Date.now(), due: Number(t.due) || 0 };
          } else {
            const x = T[tid];
            if (!x) return send(404, { error: "That task isn't there any more." });
            if ((act === "close" || act === "reopen") && !admin) return send(403, { error: "Only a group admin can close or reopen a task." });
            if (act === "done" && !(me === x.to || me === x.by || admin)) return send(403, { error: "Only the person it was given to can mark it done." });
            if (act === "close") x.state = "closed"; else if (act === "reopen") x.state = "open"; else if (act === "done") x.state = "done";
            x.last = Date.now();
          }
          task = { id: tid, action: act, to: T[tid].to, due: T[tid].due, by: T[tid].by, state: T[tid].state };
        }
        const m = { seq: ++c.seq, id: id || "m" + c.seq, from: me, at: Date.now(), type, env: { v: 1, epk: str(env.epk, 400), iv: str(env.iv, 40), ct: env.ct, keys: {} } };
        for (const u of c.members) if (env.keys[u]) m.env.keys[u] = str(env.keys[u], 400);
        if (task) m.task = task;
        list.push(m);
        c.last = m.at; c.st[me].d = c.st[me].r = m.seq; c.st[me].sent = (c.st[me].sent || 0) + 1;
        db.sent[me] = sentToday(me) + 1;
        for (const u of c.members) {
          if (u === me) continue;
          if (c.told && !c.told[u]) { c.told[u] = true; push(u, { k: "c", cid: c.id }); }   // a new chat reaches them with its first message
          push(u, { k: "m", cid: c.id, seq: m.seq });
        }
        save();
        return send(200, { seq: m.seq, at: m.at, task });
      }
      if (p === "/api/chat/block") {
        const who = str(body.uid, 40);
        if (!who || who === me) return send(400, { error: "Bad request." });
        const l = db.blocks[me] || (db.blocks[me] = []);
        db.blocks[me] = body.on === false ? l.filter(u => u !== who) : [...new Set([...l, who])];
        push(me, { k: "c", cid: c.id });
        save();
        return send(200, { conv: view(c, me) });
      }
    }
    return send(404, { error: "Not found" });
  }

  // ---------- the invite link: bigdai.tech/c/<@name> ----------
  const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;" })[c]);
  function invitePage(req, res, handleName) {
    const p = social.byHandle(handleName);
    const name = p ? p.name : "A friend", hd = p ? p.handle : "";
    const app = `intent://chat/${encodeURIComponent(hd)}#Intent;scheme=bigd;S.browser_fallback_url=${encodeURIComponent(h.baseUrl(req) + "/download")};end`;
    const html = `<!doctype html><html lang="te"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Chat with ${esc(name)} on Habit AI</title><link rel="icon" type="image/png" sizes="32x32" href="/favicon-32.png"><link rel="icon" type="image/png" sizes="192x192" href="/icon-192.png"><link rel="apple-touch-icon" href="/apple-touch-icon.png"><meta property="og:image" content="${esc(h.baseUrl(req))}/icon-512.png"><meta property="og:title" content="${esc(name)} invites you to Habit Chat">
<meta property="og:description" content="Free chat, locked end to end, in Telugu and English.">
<style>body{margin:0;font-family:system-ui,sans-serif;background:#EEF3F2;color:#132A2C;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{background:#fff;border-radius:20px;padding:28px 22px;max-width:360px;margin:16px;text-align:center;box-shadow:0 2px 12px #0001}
.e{font-size:56px}h1{font-size:22px;margin:8px 0}p{color:#5B7073;line-height:1.5}a{display:block;margin-top:12px;padding:14px;border-radius:14px;text-decoration:none;font-weight:700}
.a{background:#0E6B6B;color:#fff}.b{background:#D7EDEA;color:#0A4F50}</style></head><body><main>
<div class="e">${esc(p ? p.emoji : "🐾")}</div><h1>${esc(name)}</h1><p>${hd ? "@" + esc(hd) + " · " : ""}wants to chat with you on Habit AI.<br>Habit తో చాట్ చేయడానికి మిమ్మల్ని పిలుస్తున్నారు.</p>
${hd ? `<a class="a" href="${esc(app)}">💬 Open in Habit AI</a>` : ""}<a class="b" href="/download"><img src="/icon-192.png" alt="" style="width:24px;height:24px;vertical-align:-6px;margin-right:8px;border-radius:6px">Get Habit AI (free)</a>
<p style="font-size:13px">Then open Messages › 💬 Habit Chat and search ${hd ? "@" + esc(hd) : "their @name"}.</p></main></body></html>`;
    res.writeHead(p ? 200 : 404, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    res.end(html);
    return true;
  }

  // ---------- tidy up: messages and files older than 30 days ----------
  function tidyFiles() {
    const now = Date.now();
    let total = 0;
    const list = Object.entries(db.files).sort((a, b) => b[1].at - a[1].at);
    for (const [id, f] of list) {
      total += f.size;
      if (now - f.at > KEEP || total > FILES_MAX_BYTES) { try { fs.rmSync(path.join(FDIR, id), { force: true }); } catch (e) { } delete db.files[id]; }
    }
  }
  function tidy() {
    const now = Date.now();
    for (const [cid, list] of Object.entries(db.msgs)) { db.msgs[cid] = list.filter(m => now - m.at < KEEP); if (!db.msgs[cid].length) delete db.msgs[cid]; }
    for (const b of Object.values(db.inbox)) b.ev = b.ev.filter(e => now - e.at < KEEP);
    for (const T of Object.values(db.tasks)) for (const [id, t] of Object.entries(T)) if (t.state === "closed" && now - (t.last || t.at) > 60 * 86400e3) delete T[id];
    tidyFiles(); save();
  }
  setInterval(tidy, 6 * 3600e3).unref();

  function stats() {
    const convs = Object.values(db.convs);
    return { people: Object.keys(db.keys).length, chats: convs.filter(c => c.kind === "dm").length, groups: convs.filter(c => c.kind === "group").length,
      waiting: Object.values(db.msgs).reduce((a, l) => a + l.length, 0), files: Object.keys(db.files).length, reports: db.reports.length };
  }
  function reports() { return db.reports.slice(-100).reverse().map(r => Object.assign({}, r, { byName: person(r.by).name, whoName: r.who ? person(r.who).name : "", whoHandle: r.who ? person(r.who).handle : "" })); }

  return { handle, stats, reports, _test: { db: () => db, ack, purge, waiters } };
};
