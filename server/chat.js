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
//  - Voice and video calls: the ring / answer / hang-up notes travel as locked messages like any other; the sound and
//    video go straight between the phones (WebRTC). /api/chat/turn gives short-lived passwords for the call relay
//    (coturn on this server, see turn-setup.sh) for phones that can't reach each other directly.
//  - Like WhatsApp also: reactions, poll votes, edits and pins are small locked "control" notes (type "ctl"); groups can
//    be "only admins send messages" and have a description and an invite link (/g/<code>); "typing…", online and last
//    seen (hidden for people who hide theirs); status updates for 24 hours (locked for the people you chat with, the
//    server only keeps who has seen them); communities (an announcements group plus the groups linked to it).
//  - GIFs: the phone searches KLIPY or GIPHY itself (their rules), with the key kept in .env (GIF_KEY).
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const zlib = require("zlib");

module.exports = function makeChat({ DATA_DIR, social, h, push: fcm = null }) {
  const FILE = path.join(DATA_DIR, "chat.json");
  const FDIR = path.join(DATA_DIR, "chat-files");
  fs.mkdirSync(FDIR, { recursive: true });
  const MAX_GROUP = 100, KEEP = 30 * 86400e3, BEFORE_ACCEPT = 5, WAIT_MS = Number(process.env.CHAT_WAIT_MS || 25000);
  const FILE_MAX = Number(process.env.CHAT_FILE_MAX_MB || 32) * 1024 * 1024 + 64 * 1024, FILES_MAX_BYTES = Number(process.env.CHAT_FILES_MAX_MB || 5000) * 1024 * 1024;
  let db = { keys: {}, convs: {}, msgs: {}, inbox: {}, blocks: {}, tasks: {}, reports: [], files: {}, day: "", sent: {}, status: {}, comms: {}, links: {}, priv: {}, seenAt: {} };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  const DAY = 86400e3;
  /** When each person last had Habit open (online = within the last 40 s). Kept in memory, saved with the hourly tidy. */
  const lastSeen = new Map(Object.entries(db.seenAt || {}));
  const hidesSeen = u => (db.priv[u] || {}).seen === false;
  const hidesPhone = u => (db.priv[u] || {}).phone === false;       // turned off "People who have my number can find me"
  const matchQuota = new Map();                                      // person → { day, n }: contact numbers checked today
  let timer = null;
  const save = () => { clearTimeout(timer); timer = setTimeout(() => { try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("chat save:", e.message); } }, 300); };
  const str = (v, n) => String(v === undefined || v === null ? "" : v).slice(0, n);
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);
  const blocked = (a, b) => (db.blocks[a] || []).includes(b);        // a blocked b
  const person = uid => social.person(uid) || { uid, name: "Habit friend", handle: "", emoji: "🐾", avatar: "", gone: true };

  // ---------- linked computers (Habit Chat on the web, like WhatsApp Web) ----------
  // A computer shows a QR code with its own one-time public key; the phone scans it and sends the chat key locked for
  // that one-time key (this server only passes it on, and can't open it). The computer then has its own inbox: every
  // event goes to the phone's inbox ("<uid>") and to each linked computer's ("<uid>#<id>"), and a message stays here
  // until every device has it.
  db.web = db.web || {};                 // device key -> { uid, id, name, at, seen }
  const webIdx = new Map();              // uid -> [their linked computers]
  function reindex() { webIdx.clear(); for (const w of Object.values(db.web)) { if (!webIdx.has(w.uid)) webIdx.set(w.uid, []); webIdx.get(w.uid).push(w); } }
  reindex();
  const devicesOf = uid => webIdx.get(uid) || [];
  const boxIds = uid => [uid].concat(devicesOf(uid).map(w => uid + "#" + w.id));
  /** A newly added member's computers don't wait for the messages from before they joined. */
  function joinDevices(c, u) { for (const w of devicesOf(u)) { c.dd = c.dd || {}; c.dd[u + "#" + w.id] = c.seq; } }

  // ---------- each device's events (what it hasn't confirmed yet) ----------
  const waiters = new Map();   // inbox id -> Set of functions that wake a waiting sync
  function box(bid) { return db.inbox[bid] || (db.inbox[bid] = { n: 0, ev: [] }); }
  function pushBox(bid, ev) {
    const b = box(bid);
    ev.n = ++b.n; ev.at = Date.now();
    // one "conversation changed" or "ticks changed" event per chat is enough (one "typing" per person and chat, and one
    // "status updates changed" / "communities changed")
    if (ev.k === "c" || ev.k === "r" || ev.k === "q") b.ev = b.ev.filter(e => !(e.k === ev.k && e.cid === ev.cid));
    else if (ev.k === "t") b.ev = b.ev.filter(e => !(e.k === "t" && e.cid === ev.cid && e.u === ev.u));
    else if (ev.k === "s" || ev.k === "v" || ev.k === "k") b.ev = b.ev.filter(e => e.k !== ev.k);
    b.ev.push(ev);
    if (b.ev.length > 5000) b.ev = b.ev.slice(-5000);
    const w = waiters.get(bid);
    if (w) for (const f of [...w]) f();
  }
  /** An event for a person: to their phone and each of their linked computers ([except]: the device that did it). */
  function push(uid, ev, except) { for (const bid of boxIds(uid)) if (bid !== except) pushBox(bid, Object.assign({}, ev)); }

  // ---------- waking phones where Habit is closed (Firebase push, see push.js) ----------
  // Each phone gives its push address (POST /api/chat/push). A call note wakes the phone at once (it rings); a new
  // message wakes it gently (a notification), only when Habit isn't open and waiting there. The push has no words.
  db.push = db.push || {};                 // uid -> [{ t: push address, pkg, at }]
  function wake(u, what, cid, high) {
    if (!fcm || !fcm.ready()) return;
    const list = db.push[u];
    if (!list || !list.length) return;
    if (!high && waiters.has(u)) return;   // Habit is open there: the message reaches it at once anyway
    for (const x of list.slice()) fcm.send(x.t, { t: what, cid }, high).then(r => {
      if (r !== "gone") return;
      db.push[u] = (db.push[u] || []).filter(y => y.t !== x.t);
      if (!db.push[u].length) delete db.push[u];
      save();
    }).catch(() => {});
  }
  const pushPhones = () => Object.keys(db.push || {}).length;
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
    const k = c.comm && db.comms[c.comm];
    return { cid: c.id, kind: c.kind, name: c.kind === "dm" ? p.name : c.name, handle: p ? p.handle : "", emoji: c.kind === "dm" ? p.emoji : (c.emoji || (c.ann ? "📢" : "👥")), avatar: p ? p.avatar : "",
      other: other || "", members: c.members.map(u => Object.assign(person(u), { admin: c.admins.includes(u), key: !!db.keys[u] })), admins: c.admins.slice(), by: c.by, at: c.at, last: c.last,
      accepted: !!(c.st[me] && c.st[me].ok), seq: c.seq, st: ticks(c), blocked: !!other && blocked(me, other), member: c.members.includes(me),
      desc: c.desc || "", onlyAdmins: !!c.onlyAdmins, adminsEdit: c.adminsEdit !== false, ann: !!c.ann,
      comm: k ? { id: k.id, name: k.name, emoji: k.emoji || "🏘️" } : null, link: c.kind === "group" && c.code && c.admins.includes(me) ? c.code : "" };
  }
  /** Someone leaves a group (a group with nobody left is deleted). */
  const dropDevices = (c, u) => { if (c.dd) for (const k of Object.keys(c.dd)) if (k.startsWith(u + "#")) delete c.dd[k]; };
  function leaveConv(c, u) {
    c.members = c.members.filter(x => x !== u); c.admins = c.admins.filter(x => x !== u); delete c.st[u]; dropDevices(c, u);
    push(u, { k: "x", cid: c.id });
    if (c.kind === "group" && c.members.length) {
      if (!c.admins.length) c.admins.push(c.members[0]);          // a group always has an admin
      sysMsg(c, { what: "left", by: u });
      pushAll(c, { k: "c", cid: c.id });
    }
    if (!c.members.length) { if (c.code) delete db.links[c.code]; delete db.convs[c.id]; delete db.msgs[c.id]; delete db.tasks[c.id]; }
  }

  // ---------- communities: an announcements group (only admins write) plus groups linked to it ----------
  const commAdmin = (k, u) => { const ann = db.convs[k.ann]; return !!ann && ann.admins.includes(u); };
  const inComm = (k, u) => { const ann = db.convs[k.ann]; return (!!ann && ann.members.includes(u)) || k.groups.some(g => db.convs[g] && db.convs[g].members.includes(u)); };
  function commView(k, me) {
    const ann = db.convs[k.ann];
    return { id: k.id, name: k.name, desc: k.desc || "", emoji: k.emoji || "🏘️", admin: commAdmin(k, me), by: k.by, at: k.at, ann: ann ? k.ann : "", people: ann ? ann.members.length : 0,
      groups: k.groups.map(g => db.convs[g]).filter(Boolean).map(g => ({ cid: g.id, name: g.name, emoji: g.emoji || "👥", n: g.members.length, joined: g.members.includes(me) })) };
  }
  /** Everyone in a community's groups is also in its announcements group. */
  function syncComm(k) {
    const ann = db.convs[k.ann]; if (!ann) return;
    const add = [];
    for (const gid of k.groups) { const g = db.convs[gid]; if (g) for (const u of g.members) if (!ann.members.includes(u) && !add.includes(u)) add.push(u); }
    if (!add.length) return;
    for (const u of add) { ann.members.push(u); ann.st[u] = { ok: true, d: ann.seq, r: ann.seq }; joinDevices(ann, u); }
    sysMsg(ann, { what: "joined", who: add });
    pushAll(ann, { k: "c", cid: ann.id });
  }
  function pushComm(k) {
    const all = new Set((db.convs[k.ann] || { members: [] }).members);
    for (const g of k.groups) (db.convs[g] || { members: [] }).members.forEach(u => all.add(u));
    for (const u of all) push(u, { k: "k" });
  }
  /** Links the groups [list] (this admin's own, not in another community) to community [k]. */
  function linkGroups(k, me, list) {
    for (const cid of (Array.isArray(list) ? list : []).map(x => str(x, 40))) {
      const g = db.convs[cid];
      if (!g || g.kind !== "group" || g.ann || g.comm || !g.admins.includes(me) || k.groups.length >= 50) continue;
      k.groups.push(cid); g.comm = k.id;
      sysMsg(g, { what: "linked", by: me, name: k.name });
      pushAll(g, { k: "c", cid });
    }
  }
  function unlinkGroup(k, cid, me) {
    const g = db.convs[cid];
    k.groups = k.groups.filter(x => x !== cid);
    if (g) { delete g.comm; sysMsg(g, { what: "unlinked", by: me, name: k.name }); pushAll(g, { k: "c", cid }); }
  }
  const commOfConv = c => c.comm && db.comms[c.comm];
  const newCode = () => { let code; do { code = crypto.randomBytes(9).toString("base64").replace(/[+/=]/g, "").slice(0, 12); } while (db.links[code] || code.length < 10); return code; };

  // ---------- status updates (24 hours) ----------
  const liveStatus = uid => (db.status[uid] || []).filter(s => Date.now() - s.at < DAY);
  function dropStatus(s) { for (const f of s.files || []) { try { fs.rmSync(path.join(FDIR, f), { force: true }); } catch (e) { } delete db.files[f]; } }
  const typingMinute = new Map();
  function typingOk(me) {
    const now = Date.now(), l = (typingMinute.get(me) || []).filter(t => now - t < 60e3);
    if (l.length >= 40) return false;
    l.push(now); typingMinute.set(me, l); return true;
  }
  /** How far a person has the chat: their phone ([st].d), any of their linked computers ([dd]), or read. */
  const gotUpTo = (c, u) => Math.max((c.st[u] || {}).d || 0, (c.st[u] || {}).r || 0, ...devicesOf(u).map(w => (c.dd || {})[u + "#" + w.id] || 0));
  const ticks = c => { const o = {}; for (const u of c.members) o[u] = { d: gotUpTo(c, u), r: (c.st[u] || {}).r || 0 }; return o; };
  function sysMsg(c, sys) {
    const m = { seq: ++c.seq, id: "s" + c.seq, from: sys.by || "", dev: "*", at: Date.now(), type: "sys", sys };
    (db.msgs[c.id] = db.msgs[c.id] || []).push(m);
    c.last = m.at;
    for (const u of c.members) push(u, { k: "m", cid: c.id, seq: m.seq });
    return m;
  }
  const msgView = (m, me, cid) => ({ cid, seq: m.seq, id: m.id, from: m.from, at: m.at, type: m.type, task: m.task || null, sys: m.sys || null,
    env: m.env ? { v: m.env.v, epk: m.env.epk, iv: m.env.iv, ct: m.env.ct, key: (m.env.keys || {})[me] || "" } : null });

  /** Marks what this device ([bid]: "<uid>" or "<uid>#<computer>") confirmed (events up to [upTo]) as delivered, and
   *  forgets messages every device has. */
  function ack(bid, me, upTo) {
    const b = box(bid);
    if (!upTo || !b.ev.length) return;
    const done = b.ev.filter(e => e.n <= upTo);
    if (!done.length) return;
    b.ev = b.ev.filter(e => e.n > upTo);
    const ticked = new Set(), got = new Set();
    for (const e of done) {
      if (e.k !== "m") continue;
      const c = db.convs[e.cid];
      if (!c || !c.st[me]) continue;
      got.add(c.id);
      const before = gotUpTo(c, me);
      if (bid !== me) { c.dd = c.dd || {}; if (e.seq > (c.dd[bid] || 0)) c.dd[bid] = e.seq; }
      else if (e.seq > (c.st[me].d || 0)) c.st[me].d = e.seq;          // (only what the phone itself got)
      if (gotUpTo(c, me) > before) ticked.add(c.id);
    }
    for (const cid of ticked) pushAll(db.convs[cid], { k: "r", cid }, me);
    for (const cid of got) purge(db.convs[cid]);
    save();
  }
  /** A message is deleted from the server once every device that should get it has it: each member's phone and linked
   *  computers (the sender's own other devices get a copy too), except the device that sent it ([m].dev; older
   *  messages: the sender's phone; a note like "X joined": every device). */
  function purge(c) {
    const list = db.msgs[c.id];
    if (!list || !list.length) return;
    const dd = c.dd || {};
    db.msgs[c.id] = list.filter(m => {
      const src = m.dev !== undefined ? m.dev : m.from;
      return c.members.some(u => (u !== src && ((c.st[u] || {}).d || 0) < m.seq) ||
        devicesOf(u).some(w => { const b = u + "#" + w.id; return b !== src && (dd[b] || 0) < m.seq; }));
    });
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
    if (p.startsWith("/g/") && req.method === "GET") return groupPage(req, res, decodeURIComponent(p.slice(3)));
    if ((p === "/web" || p === "/web/") && req.method === "GET") return webPage(req, res);
    if (p.startsWith("/api/web/")) return webApi(req, res, url, origin);
    if (!p.startsWith("/api/chat/")) return false;
    const send = (st, o) => { h.send(res, st, o, origin); return true; };
    const me = social.who(req);
    // (by person, not by internet address: many phones share one address on mobile data)
    if (!h.allowed(me ? "chat:" + me : "chatip:" + h.clientIp(req), undefined, me ? 900 + 600 * devicesOf(me).length : 300)) return send(429, { error: "Too many requests. Please wait a few minutes." });
    const mine = me && social.person(me);
    if (!mine) return send(403, { error: "Make your Habit profile first: write your name.", needProfile: true });
    // which device: the phone, or one of the person's linked computers (it has its own inbox)
    const w0 = db.web[social.devKey(req)];
    const web = w0 && w0.uid === me ? w0 : null;
    const bid = web ? me + "#" + web.id : me;
    let body = {};
    if (req.method === "POST" && p !== "/api/chat/file") { try { body = await h.readBody(req, 1024 * 1024); } catch (e) { return send(400, { error: "Bad request." }); } }

    // this phone's lock: its public key (the private key never leaves the phone, except to a computer it links)
    if (p === "/api/chat/hello" && req.method === "POST") {
      if (web) return send(403, { error: "This computer uses your phone's chat lock." });
      const pub = str(body.pub, 400);
      if (!keyOk(pub)) return send(400, { error: "Bad key." });
      const kid = kidOf(pub), old = db.keys[me];
      if (!old || old.kid !== kid) {
        db.keys[me] = { pub, kid, at: Date.now() };
        db.inbox[me] = { n: box(me).n, ev: [] };        // a new phone: old locked messages were for the old key
        for (const x of devicesOf(me).slice()) unlinkWeb(x);   // and computers linked to the old phone can't open new ones
        for (const c of Object.values(db.convs)) if (c.members.includes(me)) pushAll(c, { k: "c", cid: c.id }, me);
        save();
      }
      return send(200, { me: Object.assign({}, mine, { kid }), cursor: box(me).n });
    }
    if (!db.keys[me]) return send(409, { error: "Open Habit Chat in the app first.", needKey: true });

    // this phone's push address (Firebase): its Habit calls ring and new messages show while Habit is closed (push.js)
    if (p === "/api/chat/push" && req.method === "POST") {
      if (web) return send(200, { ok: true });
      const tok = str(body.token, 4096).trim(), pkg = /^[\w.]{3,120}$/.test(String(body.pkg || "")) ? String(body.pkg) : "";
      if (tok) for (const u of Object.keys(db.push)) if (u !== me) {          // one phone, one person (it changed accounts)
        db.push[u] = db.push[u].filter(x => x.t !== tok);
        if (!db.push[u].length) delete db.push[u];
      }
      const list = (db.push[me] || []).filter(x => x.t !== tok);
      if (tok) list.push({ t: tok, pkg, at: Date.now() });
      if (list.length) db.push[me] = list.slice(-3); else delete db.push[me];   // the newest three phones / installs
      save();
      return send(200, { ok: true, push: !!(fcm && fcm.ready()) });
    }

    if (p === "/api/chat/convs" && req.method === "GET") {
      const list = Object.values(db.convs).filter(c => c.members.includes(me)).map(c => view(c, me));
      return send(200, { convs: list, cursor: box(bid).n, me: mine, keys: { uid: me, pub: db.keys[me].pub, kid: db.keys[me].kid } });
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
    // the people I chat with one-to-one (both accepted, nobody blocked), with their keys: for status updates, new calls
    // and broadcast lists
    if (p === "/api/chat/contacts" && req.method === "GET") {
      const people = [];
      for (const c of Object.values(db.convs)) {
        if (c.kind !== "dm" || !c.members.includes(me)) continue;
        const u = c.members.find(x => x !== me);
        if (!u || !(c.st[me] && c.st[me].ok) || !(c.st[u] && c.st[u].ok) || blocked(me, u) || blocked(u, me) || !db.keys[u]) continue;
        const pp = social.person(u);
        if (pp) people.push(Object.assign({}, pp, { cid: c.id, pub: db.keys[u].pub, kid: db.keys[u].kid }));
      }
      return send(200, { people, me: { uid: me, pub: db.keys[me].pub, kid: db.keys[me].kid } });
    }
    // online / last seen of someone I chat with (nothing for people who hide theirs, and nothing if I hide mine)
    if (p === "/api/chat/presence" && req.method === "GET") {
      const u = str(url.searchParams.get("uid"), 40);
      const shared = !!u && u !== me && Object.values(db.convs).some(c => c.kind === "dm" && c.members.includes(me) && c.members.includes(u) && c.st[me] && c.st[me].ok && c.st[u] && c.st[u].ok);
      if (!shared || blocked(u, me) || blocked(me, u)) return send(200, { on: false, at: 0 });
      if (hidesSeen(u) || hidesSeen(me)) return send(200, { on: false, at: 0, hidden: true });
      const at = Number(lastSeen.get(u)) || 0;
      return send(200, { on: Date.now() - at < 40e3, at });
    }
    if (p === "/api/chat/privacy" && req.method === "GET") return send(200, { seen: !hidesSeen(me), phone: !hidesPhone(me), hasPhone: social.hasPhone(me) });
    // status updates I can see (locked; only the key for me is given), and mine with who has seen them
    if (p === "/api/chat/status" && req.method === "GET") {
      const updates = [];
      for (const [owner, list] of Object.entries(db.status)) {
        if (owner === me || blocked(me, owner) || blocked(owner, me)) continue;
        const items = list.filter(s => Date.now() - s.at < DAY && s.env.keys[me]).map(s => ({ id: s.id, at: s.at, kind: s.kind || "text", seen: !!s.views[me],
          env: { v: 1, epk: s.env.epk, iv: s.env.iv, ct: s.env.ct, key: s.env.keys[me] } }));
        const pp = items.length ? social.person(owner) : null;
        if (pp) updates.push({ owner, person: pp, items });
      }
      const mine = liveStatus(me).map(s => ({ id: s.id, at: s.at, kind: s.kind || "text", env: { v: 1, epk: s.env.epk, iv: s.env.iv, ct: s.env.ct, key: s.env.keys[me] || "" },
        views: Object.entries(s.views).map(([u, at]) => Object.assign({ at }, person(u))).sort((a, b) => b.at - a.at) }));
      return send(200, { updates, mine });
    }
    if (p === "/api/chat/communities" && req.method === "GET") {
      return send(200, { communities: Object.values(db.comms).filter(k => inComm(k, me)).map(k => commView(k, me)) });
    }
    // a group invite link: what the group is, before joining
    if (p === "/api/chat/group/peek" && req.method === "GET") {
      const c = db.convs[db.links[str(url.searchParams.get("code"), 20)] || ""];
      if (!c || c.kind !== "group") return send(404, { error: "This invite link doesn't work any more. Ask for a new one." });
      return send(200, { name: c.name, emoji: c.emoji || "👥", n: c.members.length, desc: c.desc || "", member: c.members.includes(me), cid: c.id });
    }
    // GIFs: the phone searches KLIPY or GIPHY itself (their rules allow only the app to ask), with the key from .env
    if (p === "/api/chat/gifkey" && req.method === "GET") {
      const key = process.env.GIF_KEY || process.env.KLIPY_KEY || process.env.GIPHY_KEY || "";
      if (!key) return send(200, { key: "" });
      const giphyOnly = process.env.GIPHY_KEY && !process.env.GIF_KEY && !process.env.KLIPY_KEY;
      const base = String(process.env.GIF_API || (giphyOnly ? "https://api.giphy.com/v2" : "https://api.klipy.com/v2")).replace(/\/+$/, "");
      return send(200, { key, base, name: /giphy/i.test(base) ? "GIPHY" : "KLIPY" });
    }

    // calls: where the phones find each other (STUN), and the relay for when they can't (TURN, short-lived password)
    if (p === "/api/chat/turn" && req.method === "GET") {
      const ice = [];
      const secret = process.env.TURN_SECRET || "";
      let host = process.env.TURN_HOST || "";
      if (!host) { try { host = new URL(h.baseUrl(req)).hostname; } catch (e) { host = ""; } }
      if (secret && host && !/^(localhost|127\.|\[::1\])/.test(host)) {
        const username = Math.floor(Date.now() / 1000 + 8 * 3600) + ":" + me;
        const credential = crypto.createHmac("sha1", secret).update(username).digest("base64");
        ice.push({ urls: [`stun:${host}:3478`, `turn:${host}:3478?transport=udp`, `turn:${host}:3478?transport=tcp`], username, credential });
      }
      ice.push({ urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] });
      return send(200, { ice, relay: ice.length > 1 });
    }

    // the long wait for new messages
    if (p === "/api/chat/sync" && req.method === "GET") {
      const c0 = Math.max(0, Number(url.searchParams.get("c")) || 0);
      const fg = url.searchParams.get("fg") === "1";        // the app is open on the screen (not only the floating pet)
      if (fg) lastSeen.set(me, Date.now());
      if (web) web.seen = Date.now();
      ack(bid, me, c0);
      const pending = () => box(bid).ev.filter(e => e.n > c0);
      let ev = pending();
      if (!ev.length && url.searchParams.get("wait") === "1") {
        await new Promise(resolve => {
          let set = waiters.get(bid); if (!set) waiters.set(bid, set = new Set());
          let t = null;
          const wake = () => { clearTimeout(t); set.delete(wake); if (!set.size) waiters.delete(bid); resolve(); };
          set.add(wake);
          t = setTimeout(wake, WAIT_MS);
          res.on("close", wake);
        });
        if (fg) lastSeen.set(me, Date.now());
        if (res.writableEnded || res.destroyed) return true;
        ev = pending();
      }
      ev = ev.slice(0, 300);
      const out = { cursor: ev.length ? ev[ev.length - 1].n : c0, convs: [], msgs: [], ticks: [], gone: [], typing: [], read: [], more: pending().length > ev.length, now: Date.now() };
      const seen = new Set();
      for (const e of ev) {
        if (e.k === "s" || e.k === "v") { out.status = true; continue; }       // status updates changed: the phone asks for them
        if (e.k === "k") { out.comms = true; continue; }                      // communities changed
        const c = db.convs[e.cid];
        if (e.k === "t") { if (c && c.members.includes(me)) out.typing.push({ cid: c.id, u: e.u, on: e.on !== false, what: e.what || "", at: e.at }); continue; }
        if (e.k === "x" || (c && !c.members.includes(me))) { if (!seen.has("x" + e.cid)) { seen.add("x" + e.cid); out.gone.push(e.cid); } continue; }
        if (!c) continue;
        if (e.k === "c" && !seen.has("c" + e.cid)) { seen.add("c" + e.cid); out.convs.push(view(c, me)); }
        else if (e.k === "r" && !seen.has("r" + e.cid)) { seen.add("r" + e.cid); out.ticks.push({ cid: c.id, st: ticks(c) }); }
        else if (e.k === "q") out.read.push({ cid: c.id, seq: e.seq });      // read on my other device
        else if (e.k === "m") { const m = (db.msgs[c.id] || []).find(x => x.seq === e.seq); if (m) out.msgs.push(msgView(m, me, c.id)); }
      }
      return send(200, out);
    }

    // photos and voice notes (locked on the phone before they come here)
    if (p === "/api/chat/file" && req.method === "POST") {
      if (!rate(me)) return send(429, { error: "Too many. Please wait a little." });
      let buf;
      try { buf = await readRaw(req, FILE_MAX); } catch (e) { return send(e.status || 400, { error: e.status === 413 ? "That file is too big (32 MB at most)." : "Upload failed." }); }
      if (buf.length < 16) return send(400, { error: "Empty file." });
      const id = crypto.randomBytes(16).toString("hex");
      fs.writeFileSync(path.join(FDIR, id), buf);
      db.files[id] = { by: me, at: Date.now(), size: buf.length };
      tidyFiles(); save();
      return send(200, { id });
    }
    if (p === "/api/chat/file" && req.method === "GET") {
      const id = str(url.searchParams.get("id"), 40);
      if (!/^[0-9a-f]{32}$/.test(id) || !db.files[id]) return send(404, { error: "That file is no longer on the server." });
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
          push(me, { k: "c", cid }, bid);       // my other devices (phone / computers) see the new chat too
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
      // last seen and online: on or off (off: others don't see mine, and I don't see theirs)
      if (p === "/api/chat/privacy") {
        const pr = db.priv[me] || (db.priv[me] = {});
        if (body.seen !== undefined) pr.seen = !!body.seen;
        if (body.phone !== undefined) pr.phone = !!body.phone;      // "People who have my number can find me"
        save();
        return send(200, { seen: !hidesSeen(me), phone: !hidesPhone(me), hasPhone: social.hasPhone(me) });
      }
      // "Contacts on Habit": which of my phone contacts use Habit Chat. The phone sends its contacts' numbers as scrambled
      // codes (only after its owner said yes); they are compared here and never kept. Found: people who log in with that
      // number, opened Habit Chat (where they can turn this off) and didn't turn off "People who have my number can find
      // me"; nobody blocked. At most 6,000 numbers a day each.
      if (p === "/api/chat/match") {
        const codes = [...new Set((Array.isArray(body.h) ? body.h : []).map(x => String(x || "").toLowerCase()).filter(x => /^[0-9a-f]{32}$/.test(x)))].slice(0, 1000);
        const day = new Date().toISOString().slice(0, 10);
        const q = matchQuota.get(me), used = q && q.day === day ? q.n : 0;
        if (used + codes.length > 6000) return send(429, { error: "Checked enough contacts for today. Please try again tomorrow." });
        if (matchQuota.size > 20000) for (const [k, v] of matchQuota) if (v.day !== day) matchQuota.delete(k);
        matchQuota.set(me, { day, n: used + codes.length });
        const found = [];
        for (const [code, u] of social.byPhoneCodes(codes)) {
          if (u === me || !db.keys[u] || hidesPhone(u) || blocked(u, me) || blocked(me, u)) continue;
          const pp = social.person(u);
          if (pp) found.push({ h: code, person: { uid: u, name: pp.name, handle: pp.handle, emoji: pp.emoji, avatar: pp.avatar, chat: !!db.keys[u] } });
        }
        return send(200, { found });
      }
      // a status update: locked on the phone for the people chosen (only people who know me can get a key)
      if (p === "/api/chat/status") {
        const env = body.env || {};
        if (env.v !== 1 || typeof env.ct !== "string" || typeof env.iv !== "string" || typeof env.epk !== "string" || !env.keys || typeof env.keys !== "object" || env.ct.length > 200000)
          return send(400, { error: "Bad update." });
        if (!rate(me)) return send(429, { error: "Too many. Please wait a little." });
        const id = str(body.id, 40) || "s" + crypto.randomBytes(6).toString("hex");
        const list = db.status[me] = liveStatus(me);
        const dup = list.find(s => s.id === id);
        if (dup) return send(200, { id, at: dup.at });
        if (list.length >= 30) return send(429, { error: "You can share up to 30 status updates in a day." });
        const keys = {};
        for (const [u, k] of Object.entries(env.keys)) if (u === me || (db.keys[u] && !blocked(u, me) && !blocked(me, u) && knows(me, u))) keys[u] = str(k, 400);
        if (!keys[me]) return send(400, { error: "Bad update." });
        const files = (Array.isArray(body.files) ? body.files : []).map(x => str(x, 40)).filter(f => db.files[f] && db.files[f].by === me).slice(0, 2);
        const s = { id, at: Date.now(), kind: ["text", "photo", "video"].includes(body.kind) ? body.kind : "text", env: { v: 1, epk: str(env.epk, 400), iv: str(env.iv, 40), ct: env.ct, keys }, files, views: {} };
        list.push(s);
        for (const u of Object.keys(keys)) if (u !== me) push(u, { k: "s" });
        save();
        return send(200, { id, at: s.at, to: Object.keys(keys).length - 1 });
      }
      if (p === "/api/chat/status/seen") {
        const owner = str(body.owner, 40), s = liveStatus(owner).find(x => x.id === str(body.id, 40));
        if (s && owner !== me && s.env.keys[me] && !s.views[me]) { s.views[me] = Date.now(); push(owner, { k: "v" }); save(); }
        return send(200, { ok: true });
      }
      if (p === "/api/chat/status/delete") {
        const id = str(body.id, 40), list = db.status[me] || [], s = list.find(x => x.id === id);
        if (s) { dropStatus(s); db.status[me] = list.filter(x => x !== s); for (const u of Object.keys(s.env.keys)) if (u !== me) push(u, { k: "s" }); save(); }
        return send(200, { ok: true });
      }
      // communities
      if (p === "/api/chat/community") {
        const name = str(body.name, 40).replace(/[\r\n]/g, " ").trim();
        if (name.length < 2) return send(400, { error: "Give the community a name." });
        if (Object.values(db.comms).filter(k => k.by === me).length >= 20) return send(429, { error: "You've made many communities already." });
        const id = "k" + crypto.randomBytes(8).toString("hex"), gid = "g" + crypto.randomBytes(8).toString("hex");
        const desc = str(body.desc, 500).trim(), emoji = str(body.emoji, 16).trim() || "🏘️";
        const ann = db.convs[gid] = { id: gid, kind: "group", members: [me], admins: [me], name, by: me, at: Date.now(), last: Date.now(), seq: 0, st: { [me]: { ok: true, d: 0, r: 0 } }, told: null,
          onlyAdmins: true, ann: true, comm: id, emoji: "📢", desc };
        const k = db.comms[id] = { id, name, desc, emoji, by: me, at: Date.now(), groups: [], ann: gid };
        sysMsg(ann, { what: "community", by: me, name });
        linkGroups(k, me, body.groups);
        syncComm(k);
        pushAll(ann, { k: "c", cid: gid }); pushComm(k);
        save();
        return send(200, { community: commView(k, me), conv: view(ann, me) });
      }
      if (p.startsWith("/api/chat/community/")) {
        const k = db.comms[str(body.id, 40)];
        if (!k || !inComm(k, me)) return send(404, { error: "This community isn't there any more." });
        const ann = db.convs[k.ann];
        if (p === "/api/chat/community/edit") {
          if (!commAdmin(k, me)) return send(403, { error: "Only a community admin can do that." });
          const name = str(body.name, 40).replace(/[\r\n]/g, " ").trim();
          if (name.length >= 2 && name !== k.name) { k.name = name; if (ann) { ann.name = name; sysMsg(ann, { what: "name", by: me, name }); } }
          if (body.desc !== undefined) { k.desc = str(body.desc, 500).trim(); if (ann) ann.desc = k.desc; }
          if (body.emoji !== undefined && str(body.emoji, 16).trim()) k.emoji = str(body.emoji, 16).trim();
          linkGroups(k, me, body.add);
          for (const cid of (Array.isArray(body.remove) ? body.remove : []).map(x => str(x, 40))) if (k.groups.includes(cid)) unlinkGroup(k, cid, me);
          syncComm(k);
          if (ann) pushAll(ann, { k: "c", cid: ann.id });
          pushComm(k); save();
          return send(200, { community: commView(k, me) });
        }
        if (p === "/api/chat/community/join") {
          const g = db.convs[str(body.cid, 40)];
          if (!g || !k.groups.includes(g.id)) return send(404, { error: "That group isn't in this community any more." });
          if (!g.members.includes(me)) {
            if (g.members.length >= MAX_GROUP) return send(400, { error: `That group is full (${MAX_GROUP} people).` });
            g.members.push(me); g.st[me] = { ok: true, d: g.seq, r: g.seq }; joinDevices(g, me);
            sysMsg(g, { what: "joined", who: [me] });
            pushAll(g, { k: "c", cid: g.id }); syncComm(k); push(me, { k: "k" }); save();
          }
          return send(200, { conv: view(g, me) });
        }
        if (p === "/api/chat/community/leave") {
          for (const cid of [k.ann, ...k.groups]) { const g = db.convs[cid]; if (g && g.members.includes(me)) leaveConv(g, me); }
          push(me, { k: "k" }); save();
          return send(200, { ok: true });
        }
        if (p === "/api/chat/community/delete") {
          if (!commAdmin(k, me)) return send(403, { error: "Only a community admin can do that." });
          pushComm(k);
          for (const cid of k.groups.slice()) unlinkGroup(k, cid, me);
          if (ann) { for (const u of ann.members) push(u, { k: "x", cid: ann.id }); delete db.convs[ann.id]; delete db.msgs[ann.id]; delete db.tasks[ann.id]; }
          delete db.comms[k.id];
          save();
          return send(200, { ok: true });
        }
        return send(404, { error: "Not found" });
      }
      // joining a group with its invite link
      if (p === "/api/chat/join") {
        const c = db.convs[db.links[str(body.code, 20)] || ""];
        if (!c || c.kind !== "group") return send(404, { error: "This invite link doesn't work any more. Ask for a new one." });
        if (!c.members.includes(me)) {
          if (c.members.length >= MAX_GROUP) return send(400, { error: `This group is full (${MAX_GROUP} people).` });
          c.members.push(me); c.st[me] = { ok: true, d: c.seq, r: c.seq }; joinDevices(c, me);
          sysMsg(c, { what: "joined", who: [me], link: true });
          pushAll(c, { k: "c", cid: c.id });
          const k = commOfConv(c); if (k) { syncComm(k); pushComm(k); }
          save();
        }
        return send(200, { conv: view(c, me) });
      }
      const c = db.convs[str(body.cid, 40)];
      if (!c || !c.members.includes(me)) return send(404, { error: "This chat isn't there any more." });
      const admin = c.kind === "dm" || c.admins.includes(me);

      if (p === "/api/chat/group/edit") {
        if (c.kind !== "group") return send(400, { error: "Only groups can be changed." });
        const list = k => [...new Set((Array.isArray(body[k]) ? body[k] : []).map(x => str(x, 40)))];
        // people and settings: admins only; the name, picture and description: admins, or everyone if the group allows it
        const people = ["add", "remove", "admin", "unadmin"].some(k => list(k).length) || body.onlyAdmins !== undefined || body.adminsEdit !== undefined;
        const info = body.name !== undefined || body.desc !== undefined || body.emoji !== undefined;
        if (people && !admin) return send(403, { error: "Only a group admin can do that." });
        if (info && !admin && c.adminsEdit !== false) return send(403, { error: "Only admins can change this group's name, picture and description." });
        const add = list("add").filter(u => !c.members.includes(u) && social.person(u) && db.keys[u] && !blocked(u, me));
        if (c.members.length + add.length > MAX_GROUP) return send(400, { error: `A group can have up to ${MAX_GROUP} people.` });
        for (const u of add) { c.members.push(u); c.st[u] = { ok: knows(u, me), d: c.seq, r: c.seq }; joinDevices(c, u); }
        if (add.length) sysMsg(c, { what: "added", by: me, who: add });
        const remove = list("remove").filter(u => c.members.includes(u) && u !== me);
        for (const u of remove) { c.members = c.members.filter(x => x !== u); c.admins = c.admins.filter(x => x !== u); delete c.st[u]; dropDevices(c, u); push(u, { k: "x", cid: c.id }); }
        if (remove.length) sysMsg(c, { what: "removed", by: me, who: remove });
        const up = list("admin").filter(u => c.members.includes(u) && !c.admins.includes(u));
        for (const u of up) c.admins.push(u);
        const down = list("unadmin").filter(u => c.admins.includes(u) && u !== me);
        c.admins = c.admins.filter(u => !down.includes(u));
        if (up.length || down.length) sysMsg(c, { what: "admins", by: me, who: up, not: down });
        const name = str(body.name, 40).replace(/[\r\n]/g, " ").trim();
        if (name && name.length >= 2 && name !== c.name) { c.name = name; sysMsg(c, { what: "name", by: me, name }); }
        if (body.desc !== undefined) { const d = str(body.desc, 500).trim(); if (d !== (c.desc || "")) { c.desc = d; sysMsg(c, { what: "desc", by: me }); } }
        if (body.emoji !== undefined) { const e = str(body.emoji, 16).trim(); if (e && e !== c.emoji) { c.emoji = e; sysMsg(c, { what: "emoji", by: me, emoji: e }); } }
        if (body.onlyAdmins !== undefined && !!body.onlyAdmins !== !!c.onlyAdmins) { c.onlyAdmins = !!body.onlyAdmins; sysMsg(c, { what: "only", by: me, on: c.onlyAdmins }); }
        if (body.adminsEdit !== undefined && !!body.adminsEdit !== (c.adminsEdit !== false)) { c.adminsEdit = !!body.adminsEdit; sysMsg(c, { what: "infoedit", by: me, on: c.adminsEdit }); }
        const k = commOfConv(c);
        if (k) { if (c.ann) { k.name = c.name; k.desc = c.desc || ""; } syncComm(k); pushComm(k); }
        pushAll(c, { k: "c", cid: c.id });
        purge(c); save();
        return send(200, { conv: view(c, me) });
      }
      // the group's invite link (admins): make one, make a new one (the old one stops working), or turn it off
      if (p === "/api/chat/group/link") {
        if (c.kind !== "group" || c.ann) return send(400, { error: "Only groups have invite links." });
        if (!admin) return send(403, { error: "Only a group admin can do that." });
        if (body.off) { if (c.code) delete db.links[c.code]; c.code = ""; }
        else if (!c.code || body.reset) { if (c.code) delete db.links[c.code]; c.code = newCode(); db.links[c.code] = c.id; }
        pushAll(c, { k: "c", cid: c.id }); save();
        return send(200, { code: c.code || "", url: c.code ? h.baseUrl(req) + "/g/" + c.code : "", conv: view(c, me) });
      }
      // "typing…" / "recording audio…" (not kept: a few seconds old is already stale)
      if (p === "/api/chat/typing") {
        if (!typingOk(me)) return send(200, { ok: true });
        const other = c.kind === "dm" ? c.members.find(u => u !== me) : "";
        if (!(c.st[me] && c.st[me].ok) || (other && (blocked(other, me) || blocked(me, other)))) return send(200, { ok: true });
        for (const u of c.members) if (u !== me && c.st[u] && c.st[u].ok) push(u, { k: "t", cid: c.id, u: me, on: body.on !== false, what: body.what === "rec" ? "rec" : "" });
        return send(200, { ok: true });
      }
      if (p === "/api/chat/leave") {
        if (c.kind === "dm") return send(200, { ok: true });      // a one-to-one chat is only cleared on the phone
        leaveConv(c, me);
        save();
        return send(200, { ok: true });
      }
      if (p === "/api/chat/accept") { c.st[me].ok = true; push(me, { k: "c", cid: c.id }); save(); return send(200, { conv: view(c, me) }); }
      if (p === "/api/chat/read") {
        const seq = Math.min(c.seq, Math.max(0, Number(body.seq) || 0));
        const s = c.st[me];
        // my other devices learn it was read here (also with read receipts off: then only they know)
        if (seq > 0 && seq > (s.q || 0)) { s.q = seq; push(me, { k: "q", cid: c.id, seq }, bid); }
        if (!body.quiet && seq > (s.r || 0)) { s.r = seq; pushAll(c, { k: "r", cid: c.id }, me); push(me, { k: "r", cid: c.id }, bid); }
        save();
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
        // "ctl": a small control note (a reaction, a poll vote, an edit, a pin…): allowed in "only admins" groups too;
        // the phones check what it really is after opening it
        const type = body.type === "task" ? "task" : body.type === "ctl" ? "ctl" : "msg";
        const env = body.env || {};
        if (env.v !== 1 || typeof env.ct !== "string" || typeof env.iv !== "string" || typeof env.epk !== "string" || !env.keys || typeof env.keys !== "object" || env.ct.length > 200000)
          return send(400, { error: "Bad message." });
        if (!rate(me) || sentToday(me) >= 3000) return send(429, { error: "Too many messages. Please wait a little." });
        if (c.kind === "group" && c.onlyAdmins && !admin && (type === "msg" || (type === "task" && (body.task || {}).action === "new")))
          return send(403, { error: "Only admins can send messages in this group." });
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
        const m = { seq: ++c.seq, id: id || "m" + c.seq, from: me, dev: bid, at: Date.now(), type, env: { v: 1, epk: str(env.epk, 400), iv: str(env.iv, 40), ct: env.ct, keys: {} } };
        for (const u of c.members) if (env.keys[u]) m.env.keys[u] = str(env.keys[u], 400);
        if (task) m.task = task;
        list.push(m);
        c.last = m.at; c.st[me].r = Math.max(c.st[me].r || 0, m.seq); c.st[me].sent = (c.st[me].sent || 0) + 1;
        db.sent[me] = sentToday(me) + 1;
        for (const u of c.members) {
          if (u === me) continue;
          if (c.told && !c.told[u]) { c.told[u] = true; push(u, { k: "c", cid: c.id }); }   // a new chat reaches them with its first message
          push(u, { k: "m", cid: c.id, seq: m.seq });
        }
        push(me, { k: "m", cid: c.id, seq: m.seq }, bid);       // a copy for my other devices (sent from the computer: the phone shows it too)
        // phones where Habit is closed: a call note wakes them at once (the call rings, or stops ringing); a message or task
        // shows as a notification. Other small notes (reactions, edits…) wait until Habit opens.
        const ring = type === "ctl" && body.ring === true;
        if (type !== "ctl" || ring) for (const u of c.members) if (u !== me) wake(u, ring ? "call" : "msg", c.id, ring);
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
  // ---------- linking a computer: the QR code on bigdai.tech/web, scanned by the phone ----------
  const LINK_MS = 3 * 60e3;
  const pendingLinks = new Map();      // code -> { dk, name, at, payload, wake }
  setInterval(() => { const now = Date.now(); for (const [k, x] of pendingLinks) if (now - x.at > LINK_MS + 60e3) pendingLinks.delete(k); }, 60e3).unref();
  function unlinkWeb(w) {
    const dk = Object.keys(db.web).find(k => db.web[k] === w);
    if (!dk) return;
    delete db.web[dk]; reindex();
    social.unlinkDevice(dk);
    const wb = w.uid + "#" + w.id;
    delete db.inbox[wb];
    const ws = waiters.get(wb); if (ws) for (const f of [...ws]) f();
    for (const c of Object.values(db.convs)) if (c.dd && c.dd[wb] !== undefined) { delete c.dd[wb]; purge(c); }
  }
  async function webApi(req, res, url, origin) {
    const p = url.pathname;
    const send = (st, o) => { h.send(res, st, o, origin); return true; };
    const who0 = social.who(req);
    if (!h.allowed(who0 ? "webu:" + who0 : "web:" + h.clientIp(req), undefined, 300)) return send(429, { error: "Too many tries. Please wait a few minutes." });
    const dk = social.devKey(req);
    let body = {};
    if (req.method === "POST") { try { body = await h.readBody(req, 512 * 1024); } catch (e) { return send(400, { error: "Bad request." }); } }
    // the computer asks for a link code (for its QR code, with its own one-time key)
    if (p === "/api/web/start" && req.method === "POST") {
      if (!/^web-[0-9a-f]{32}$/.test(String(req.headers["x-device"] || ""))) return send(400, { error: "Bad device." });
      if (db.web[dk]) return send(200, { linked: true });
      for (const [k, x] of pendingLinks) if (x.dk === dk) pendingLinks.delete(k);
      let code;
      do { code = crypto.randomBytes(12).toString("base64").replace(/[^A-Za-z0-9]/g, "").slice(0, 12); } while (code.length < 12 || pendingLinks.has(code));
      pendingLinks.set(code, { dk, name: str(body.name, 60).replace(/[\r\n<>"]/g, " ").trim() || "Computer", at: Date.now(), payload: null, wake: new Set() });
      return send(200, { code, ms: LINK_MS });
    }
    // the computer waits (up to 25 s at a time) for the phone to link it
    if (p === "/api/web/wait" && req.method === "GET") {
      const code = str(url.searchParams.get("code"), 20), x = pendingLinks.get(code);
      if (!x || x.dk !== dk) return send(410, { error: "expired" });
      if (!x.payload && Date.now() - x.at < LINK_MS && url.searchParams.get("w") !== "0") {
        await new Promise(resolve => {
          let t = null;
          const wake = () => { clearTimeout(t); x.wake.delete(wake); resolve(); };
          x.wake.add(wake); t = setTimeout(wake, WAIT_MS); res.on("close", wake);
        });
        if (res.writableEnded || res.destroyed) return true;
      }
      if (x.payload) { pendingLinks.delete(code); return send(200, Object.assign({ linked: true }, x.payload)); }
      if (Date.now() - x.at >= LINK_MS) { pendingLinks.delete(code); return send(410, { error: "expired" }); }
      return send(200, { waiting: true });
    }
    // the rest: a person with Habit Chat (their phone, or a computer already linked)
    const me = social.who(req);
    if (!me || !social.person(me) || !db.keys[me]) return send(403, { error: "Open Habit Chat on your phone first.", needProfile: true });
    const mineWeb = db.web[dk] && db.web[dk].uid === me ? db.web[dk] : null;
    // the phone links the computer: the chat key and recent chats go to it locked for its one-time key
    if (p === "/api/web/link" && req.method === "POST") {
      if (mineWeb) return send(403, { error: "Link a computer from your phone." });
      const x = pendingLinks.get(str(body.code, 20));
      if (!x || x.payload || Date.now() - x.at >= LINK_MS) return send(410, { error: "This QR code has expired. Open bigdai.tech/web on the computer again for a new one." });
      const s = body.sealed || {};
      if (typeof s.epk !== "string" || typeof s.iv !== "string" || typeof s.ct !== "string" || typeof s.key !== "string" || s.ct.length > 400000) return send(400, { error: "Bad link." });
      if (devicesOf(me).length >= 4) return send(400, { error: "You can link up to 4 computers. Log one out first (⋮ › Linked devices)." });
      if (db.web[x.dk]) unlinkWeb(db.web[x.dk]);
      const id = crypto.randomBytes(4).toString("hex");
      const w = db.web[x.dk] = { uid: me, id, name: x.name, at: Date.now(), seen: Date.now() };
      reindex();
      social.linkDevice(x.dk, me);
      const wb = me + "#" + id;
      db.inbox[wb] = { n: 0, ev: [] };
      // older messages come from the phone (locked for the computer); what reached here after the phone's copy is sent too
      const upto = body.upto && typeof body.upto === "object" ? body.upto : {};
      for (const c of Object.values(db.convs)) {
        if (!c.members.includes(me)) continue;
        c.dd = c.dd || {};
        const u0 = Number(upto[c.id]);
        const from = Object.prototype.hasOwnProperty.call(upto, c.id) && u0 >= 0 ? Math.min(c.seq, u0) : c.seq;
        c.dd[wb] = from;
        for (const m of (db.msgs[c.id] || []).filter(m => m.seq > from).slice(-300)) pushBox(wb, { k: "m", cid: c.id, seq: m.seq });
      }
      x.payload = { sealed: { epk: str(s.epk, 400), iv: str(s.iv, 40), ct: s.ct, key: str(s.key, 400) }, uid: me, me: social.person(me) };
      for (const f of [...x.wake]) f();
      save();
      return send(200, { ok: true, device: { id, name: w.name, at: w.at } });
    }
    if (p === "/api/web/devices" && req.method === "GET") {
      return send(200, { devices: devicesOf(me).map(w => ({ id: w.id, name: w.name, at: w.at, seen: w.seen || w.at, me: !!mineWeb && mineWeb.id === w.id })) });
    }
    // log out a computer (from the phone, or the computer itself)
    if (p === "/api/web/logout" && req.method === "POST") {
      const id = str(body.id, 20);
      const w = id ? devicesOf(me).find(x => x.id === id) : mineWeb;
      if (w) { unlinkWeb(w); save(); }
      return send(200, { ok: true });
    }
    return send(404, { error: "Not found" });
  }
  /** bigdai.tech/web: Habit Chat on a computer. One page; it may only talk to this server (a strict content policy). */
  let webParts = null;
  function webPage(req, res) {
    if (webParts === null) { try { webParts = fs.readFileSync(path.join(__dirname, "webchat.html"), "utf8").split("__NONCE__"); } catch (e) { webParts = []; } }
    if (!webParts.length) { res.writeHead(404, { "Content-Type": "text/plain" }); res.end("Not found"); return true; }
    const nonce = crypto.randomBytes(16).toString("base64");
    let body = Buffer.from(webParts.join(nonce), "utf8");
    const gz = /\bgzip\b/.test(String(req.headers["accept-encoding"] || ""));
    if (gz) body = zlib.gzipSync(body, { level: 6 });
    res.writeHead(200, Object.assign({
      "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
      "X-Frame-Options": "DENY", "Cross-Origin-Opener-Policy": "same-origin", "Permissions-Policy": "camera=(), geolocation=(self), microphone=(self), payment=(), usb=()",
      "Content-Security-Policy": `default-src 'none'; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'; img-src 'self' blob: data:; media-src 'self' blob:; connect-src 'self'; ` +
        "font-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
      "Content-Length": body.length, "Vary": "Accept-Encoding"
    }, gz ? { "Content-Encoding": "gzip" } : {}));
    res.end(body);
    return true;
  }

  // ---------- a group's invite link: bigdai.tech/g/<code> ----------
  function groupPage(req, res, code) {
    const c = db.convs[db.links[String(code || "").slice(0, 20)] || ""];
    const ok = !!c && c.kind === "group";
    const name = ok ? c.name : "This invite link doesn't work any more";
    const app = `intent://group/${encodeURIComponent(code)}#Intent;scheme=bigd;S.browser_fallback_url=${encodeURIComponent(h.baseUrl(req) + "/download")};end`;
    const html = `<!doctype html><html lang="te"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(name)} · Habit Chat group</title><link rel="icon" type="image/png" sizes="192x192" href="/icon-192.png"><meta property="og:image" content="${esc(h.baseUrl(req))}/icon-512.png">
<meta property="og:title" content="${ok ? "Join \"" + esc(c.name) + "\" on Habit Chat" : "Habit Chat"}"><meta property="og:description" content="${ok ? c.members.length + " people · locked end to end, Telugu and English." : "Ask for a new invite link."}">
<style>body{margin:0;font-family:system-ui,sans-serif;background:#0F1330;color:#ECEFF7;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{background:#1E2550;border-radius:20px;padding:28px 22px;max-width:360px;margin:16px;text-align:center}
.e{font-size:56px}h1{font-size:22px;margin:8px 0}p{color:#9AA3C7;line-height:1.5}a{display:block;margin-top:12px;padding:14px;border-radius:14px;text-decoration:none;font-weight:700}
.a{background:#F6C453;color:#0F1330}.b{background:#2A3266;color:#ECEFF7}</style></head><body><main>
<div class="e">${esc(ok ? c.emoji || "👥" : "🔗")}</div><h1>${esc(name)}</h1>
<p>${ok ? c.members.length + " people · గ్రూప్‌లో చేరడానికి ఆహ్వానం<br>" + esc(c.desc || "") : "Ask the group admin for a new link."}</p>
${ok ? `<a class="a" href="${esc(app)}">👥 Join in Habit AI</a>` : ""}<a class="b" href="/download">Get Habit AI (free)</a></main></body></html>`;
    res.writeHead(ok ? 200 : 404, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
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
    // old "typing…" notes go after a minute
    for (const b of Object.values(db.inbox)) b.ev = b.ev.filter(e => now - e.at < (e.k === "t" ? 60e3 : KEEP));
    for (const T of Object.values(db.tasks)) for (const [id, t] of Object.entries(T)) if (t.state === "closed" && now - (t.last || t.at) > 60 * 86400e3) delete T[id];
    // status updates go after 24 hours (with their photos and videos)
    for (const [u, list] of Object.entries(db.status)) {
      const keep = [];
      for (const s of list) { if (now - s.at < DAY) keep.push(s); else dropStatus(s); }
      if (keep.length) db.status[u] = keep; else delete db.status[u];
    }
    // communities whose announcements group is gone; invite links of groups that are gone
    for (const [id, k] of Object.entries(db.comms)) if (!db.convs[k.ann]) { for (const g of k.groups) if (db.convs[g]) delete db.convs[g].comm; delete db.comms[id]; }
    for (const [code, cid] of Object.entries(db.links)) if (!db.convs[cid] || db.convs[cid].code !== code) delete db.links[code];
    db.seenAt = Object.fromEntries(lastSeen);
    // computers not used for 30 days are logged out (like WhatsApp)
    for (const w of Object.values(db.web)) if (now - (w.seen || w.at) > 30 * DAY) unlinkWeb(w);
    tidyFiles(); save();
  }
  setInterval(tidy, 3600e3).unref();

  function stats() {
    const convs = Object.values(db.convs);
    return { people: Object.keys(db.keys).length, chats: convs.filter(c => c.kind === "dm").length, groups: convs.filter(c => c.kind === "group").length,
      waiting: Object.values(db.msgs).reduce((a, l) => a + l.length, 0), files: Object.keys(db.files).length, reports: db.reports.length,
      statuses: Object.values(db.status).reduce((a, l) => a + l.filter(s => Date.now() - s.at < DAY).length, 0), communities: Object.keys(db.comms).length,
      computers: Object.keys(db.web).length };
  }
  function reports() { return db.reports.slice(-100).reverse().map(r => Object.assign({}, r, { byName: person(r.by).name, whoName: r.who ? person(r.who).name : "", whoHandle: r.who ? person(r.who).handle : "" })); }

  return { handle, stats, reports, pushPhones, _test: { db: () => db, ack, purge, waiters } };
};
