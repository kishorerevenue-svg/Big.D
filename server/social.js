// BigD AI social ("Media" tab, like a small X/Twitter):
//  - official accounts for each Media section (✓), and a profile for every user (photo, name, @handle, tagline)
//  - posts with text, up to 4 photos (each checked by AI before anyone sees it) and a link from
//    YouTube / Instagram / Facebook / X / any site (shown as a preview)
//  - comments, likes, views, follow, report, and team tools (team password)
// Section posts written by the AI (media.js) appear in the same feed under their official accounts.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const dns = require("dns").promises;
const net = require("net");

module.exports = function makeSocial({ DATA_DIR, API_BASE, API_KEY, MODEL, adminPassword, media, h }) {
  const { send, readBody, allowed, clientIp } = h;
  const FILE = path.join(DATA_DIR, "social.json");
  const UP = path.join(DATA_DIR, "uploads");
  fs.mkdirSync(UP, { recursive: true });
  const MAX_UPLOADS = Number(process.env.SOCIAL_MAX_UPLOADS || 20000);
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);

  // ---------- storage ----------
  let db = { nextPost: 1, nextComment: 1, users: {}, devices: {}, uploads: {}, posts: [], comments: [], follows: {}, stats: {}, imported: false };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  let saveTimer = null;
  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("Social save error:", e.message); }
    }, 300);
  }
  const SALT = (() => {
    const f = path.join(DATA_DIR, "social-salt.txt");
    try { return fs.readFileSync(f, "utf8").trim(); } catch (e) { const s = crypto.randomBytes(16).toString("hex"); try { fs.writeFileSync(f, s); } catch (e2) { } return s; }
  })();
  const devHash = req => crypto.createHash("sha256").update(SALT + "|" + (String(req.headers["x-device"] || "").replace(/[^\w-]/g, "").slice(0, 64) || clientIp(req))).digest("hex").slice(0, 24);
  /** This phone's user id (made the first time it is needed). */
  function uidOf(req, create = true) {
    const d = devHash(req);
    let uid = db.devices[d];
    if (!uid && create) { uid = "u" + crypto.randomBytes(5).toString("hex"); db.devices[d] = uid; save(); }
    return uid || "";
  }
  function isAdmin(req) {
    const given = String(req.headers["x-admin-key"] || ""), pw = adminPassword();
    if (!pw || !given) return false;
    return crypto.timingSafeEqual(crypto.createHash("sha256").update(given).digest(), crypto.createHash("sha256").update(pw).digest());
  }

  // ---------- official accounts: one per Media section ----------
  const ICON = { news: "📰", movies: "🎬", kids: "🧒", crypto: "🪙", realestate: "🏠", jobs: "💼", devotional: "🛕", farming: "🌾", health: "🩺" };
  const BIO = {
    news: "రోజూ ముఖ్యమైన వార్తలు · Daily news for Telugu people", movies: "కొత్త సినిమాలు, విమర్శకుల అభిప్రాయాలు, ట్రైలర్లు · Movies, reviews, trailers",
    kids: "పిల్లలకు కథలు, పాటలు, పొడుపు కథలు · Stories and rhymes for kids", crypto: "క్రిప్టో వార్తలు, జాగ్రత్తలు · Crypto news (not financial advice)",
    realestate: "AP, తెలంగాణ రియల్ ఎస్టేట్ వార్తలు · Real estate news (not advice)", jobs: "కొత్త ఉద్యోగ నోటిఫికేషన్లు · New job notifications",
    devotional: "పండుగలు, ఆలయాలు, మంచి మాటలు · Festivals, temples, good thoughts", farming: "రైతులకు చిట్కాలు, పథకాలు, ధరలు · Tips and schemes for farmers",
    health: "ఆరోగ్య చిట్కాలు · Simple health tips (not medical advice)"
  };
  const OFFICIAL = {};
  for (const [k, c] of Object.entries(media.CATS)) OFFICIAL["s_" + k] = { uid: "s_" + k, name: "BigD " + c.te, handle: "bigd_" + k, bio: BIO[k] || c.en, emoji: ICON[k] || "📺", verified: true, official: true, cat: k };
  const userOf = uid => OFFICIAL[uid] || db.users[uid] || null;

  function followerCounts() {
    const n = {};
    for (const list of Object.values(db.follows)) for (const t of list) n[t] = (n[t] || 0) + 1;
    return n;
  }
  function postCount(uid) {
    if (OFFICIAL[uid]) return media.allItems().filter(i => i.cat === OFFICIAL[uid].cat).length + db.posts.filter(p => p.uid === uid && !p.removed).length;
    return db.posts.filter(p => p.uid === uid && !p.removed && !p.hidden).length;
  }
  const fileUrl = id => id ? `/u/${id}.jpg` : "";
  function userView(uid, me, counts) {
    const u = userOf(uid);
    if (!u) return { uid, name: "BigD friend", handle: "", bio: "", avatar: "", emoji: "🐾", verified: false };
    counts = counts || followerCounts();
    return { uid, name: u.name || "BigD friend", handle: u.handle || "", bio: u.bio || "", avatar: fileUrl(u.avatar), emoji: u.emoji || "🐾",
      verified: !!u.verified, official: !!u.official, followers: counts[uid] || 0, following: (db.follows[uid] || []).length,
      posts: postCount(uid), followed: !!me && (db.follows[me] || []).includes(uid), me: uid === me, banned: !!u.banned };
  }

  // ---------- posts (user posts "p12" and AI section posts "m34" together) ----------
  const stat = key => db.stats[key] || (db.stats[key] = { likes: [], views: 0, comments: 0 });
  function mediaToPost(i) { return { key: "m" + i.id, uid: "s_" + i.cat, at: i.at, title: i.title, text: i.body, photos: [], yt: i.yt || null, link: null, source: i.url ? { name: i.source, url: i.url } : null, cat: i.cat, emoji: i.emoji || "" }; }
  function userPostToPost(p) { return { key: "p" + p.id, uid: p.uid, at: p.at, title: "", text: p.text, photos: (p.photos || []).map(fileUrl), yt: p.link && p.link.yt ? { id: p.link.yt, ch: p.link.site || "" } : null, link: p.link || null, source: null, cat: OFFICIAL[p.uid] ? OFFICIAL[p.uid].cat : "", emoji: "" }; }
  function postView(x, me, counts) {
    const s = x.key[0] === "p" ? db.posts.find(p => "p" + p.id === x.key) : null;
    const st = s || stat(x.key);
    return Object.assign({}, x, { author: userView(x.uid, me, counts), likes: (st.likes || []).length, liked: !!me && (st.likes || []).includes(me),
      views: st.views || 0, comments: st.comments || 0, mine: !!me && x.uid === me });
  }
  function allPosts() {
    return db.posts.filter(p => !p.removed && !p.hidden && !(db.users[p.uid] && db.users[p.uid].banned)).map(userPostToPost)
      .concat(media.allItems().map(mediaToPost));
  }
  function feed(tab, before, limit, me) {
    let list = allPosts();
    if (tab === "following") { const f = new Set(db.follows[me] || []); list = list.filter(x => f.has(x.uid)); }
    else if (tab === "people") list = list.filter(x => !OFFICIAL[x.uid]);
    else if (tab && tab.startsWith("user:")) list = list.filter(x => x.uid === tab.slice(5));
    else if (tab && media.CATS[tab]) list = list.filter(x => x.uid === "s_" + tab);
    list = list.filter(x => x.at < before).sort((a, b) => b.at - a.at);
    const counts = followerCounts();
    return { posts: list.slice(0, limit).map(x => postView(x, me, counts)), more: list.length > limit };
  }
  function findPost(key) {
    if (/^p\d+$/.test(key)) { const p = db.posts.find(q => "p" + q.id === key && !q.removed); return p ? { user: p } : null; }
    if (/^m\d+$/.test(key)) { const i = media.allItems().find(q => "m" + q.id === key); return i ? { media: i } : null; }
    return null;
  }

  // ---------- checking words and photos ----------
  function localProblem(text) {
    if (/(?:\+?91[\s-]?)?[6-9](?:[\s-]?\d){9}\b/.test(text)) return "Please don't post phone numbers. Keep personal details private.";
    if (/\b\d{4}\s?\d{4}\s?\d{4}\b|\b[A-Z]{5}\d{4}[A-Z]\b/i.test(text)) return "Please don't post ID numbers (Aadhaar, PAN).";
    if (/@[a-z0-9-]+\.[a-z]/i.test(text)) return "Please don't post email addresses.";
    return "";
  }
  async function claude(system, content, maxTokens = 200) {
    const r = await fetch(API_BASE + "/v1/messages", { method: "POST",
      headers: { "x-api-key": API_KEY, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model: MODEL, max_tokens: maxTokens, system, messages: [{ role: "user", content }] }) });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error((data.error && data.error.message) || "api_error " + r.status);
    const text = (data.content || []).filter(c => c.type === "text").map(c => c.text).join("");
    try { return JSON.parse(text); } catch (e) { const a = text.indexOf("{"), b = text.lastIndexOf("}"); if (a >= 0 && b > a) { try { return JSON.parse(text.slice(a, b + 1)); } catch (e2) { } } }
    return {};
  }
  const TEXT_RULES = `You moderate the public pages of BigD AI, a family-friendly app used by people of all ages in India (Telugu and English). Decide if this text can be shown to everyone.
Reject: insults, abuse, bad words, hate against any group, sexual content, violence or threats, harassment or bullying of a person, personal details (phone numbers, addresses, ID numbers), spam or advertising, scams, fake news, political or religious attacks.
Allow: everyday thoughts, news, wishes, feedback, ideas, problems, thanks, polite criticism, and links to normal videos.
If the writer may be in danger or thinking of self-harm, reject it and in "reason" kindly ask them to talk to someone they trust or call Tele-MANAS 14416.
Reply with only a JSON object: {"ok":true|false,"reason":"<if rejected: one short, kind sentence in the text's language>"}`;
  async function checkText(text) {
    const local = localProblem(text); if (local) return { ok: false, reason: local };
    if (!API_KEY || !text.trim()) return { ok: true };
    try { const o = await claude(TEXT_RULES, `<<<\n${text}\n>>>`); return { ok: o.ok !== false, reason: str(o.reason, 200) }; }
    catch (e) { return { ok: true }; } // if the AI is busy, reports from users still protect the pages
  }
  const PHOTO_RULES = `You check photos before they are shown publicly in BigD AI, a family-friendly app used by people of all ages in India.
Reject the photo if it has: nudity or sexual content; anyone who may be under 18 shown in a revealing, sexual or unsafe way; graphic violence, blood or dead bodies; hate symbols; weapons used to threaten; drugs; ID cards, bank cards, cheques or documents with personal numbers; screenshots that show phone numbers or private chats; ads, QR codes for payments or spam.
Allow ordinary photos: people, family, festivals, food, places, nature, animals, events, memes that are not hateful.
Reply with only a JSON object: {"ok":true|false,"reason":"<if rejected: one short, kind sentence>"}`;
  async function checkPhoto(type, b64) {
    if (!API_KEY) return { ok: false, reason: "Photos can't be checked right now. Please try later." };
    const o = await claude(PHOTO_RULES, [{ type: "image", source: { type: "base64", media_type: type, data: b64 } }, { type: "text", text: "Check this photo." }]);
    return { ok: o.ok === true, reason: str(o.reason, 200) || "This photo can't be shown." };
  }

  // ---------- link previews (YouTube, Instagram, Facebook, X, any site) ----------
  const ytId = u => { const m = String(u || "").match(/(?:youtube\.com\/(?:watch\?(?:.*&)?v=|shorts\/|embed\/|live\/)|youtu\.be\/)([\w-]{11})/); return m ? m[1] : ""; };
  const hostOf = u => { try { return new URL(u).hostname.replace(/^www\.|^m\./, ""); } catch (e) { return ""; } };
  async function publicHost(u) {
    try {
      const url = new URL(u); if (!/^https?:$/.test(url.protocol)) return false;
      const ips = await dns.lookup(url.hostname, { all: true });
      return ips.every(({ address: a }) => !(net.isIPv4(a) ? /^(127\.|10\.|192\.168\.|169\.254\.|0\.|172\.(1[6-9]|2\d|3[01])\.)/.test(a) : /^(::1$|fc|fd|fe80)/i.test(a)));
    } catch (e) { return false; }
  }
  const ent = s => String(s || "").replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#0?39;|&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&#(\d+);/g, (m, d) => String.fromCodePoint(Number(d))).replace(/&#x([0-9a-f]+);/gi, (m, x) => String.fromCodePoint(parseInt(x, 16)));
  const SITE = { "youtube.com": "YouTube", "youtu.be": "YouTube", "instagram.com": "Instagram", "facebook.com": "Facebook", "fb.watch": "Facebook", "x.com": "X", "twitter.com": "X", "sharechat.com": "ShareChat", "moj.video": "Moj", "threads.net": "Threads" };
  async function preview(u) {
    const url = str(u, 600); if (!/^https?:\/\//.test(url)) return null;
    const host = hostOf(url); const site = SITE[host] || host;
    const out = { url, site, title: "", image: "", yt: "" };
    const vid = ytId(url);
    if (vid) {
      out.yt = vid; out.image = `https://i.ytimg.com/vi/${vid}/hqdefault.jpg`;
      try { const r = await fetch(`https://www.youtube.com/oembed?format=json&url=${encodeURIComponent("https://www.youtube.com/watch?v=" + vid)}`, { signal: AbortSignal.timeout(8000) });
        if (r.ok) { const j = await r.json(); out.title = str(j.title, 200); out.site = "YouTube · " + str(j.author_name, 60); } } catch (e) { }
      return out;
    }
    try {
      // follow at most 4 redirects, and never to the server's own network
      let at = url, r = null;
      for (let hop = 0; hop < 5; hop++) {
        if (!(await publicHost(at))) return out;
        r = await fetch(at, { redirect: "manual", signal: AbortSignal.timeout(8000), headers: { "User-Agent": "Mozilla/5.0 (compatible; BigDAI-preview/1.0; +https://bigdai.tech)", "Accept": "text/html" } });
        const loc = r.status >= 300 && r.status < 400 && r.headers.get("location");
        if (!loc) break;
        at = new URL(loc, at).toString(); r = null;
      }
      if (!r || !r.body) return out;
      // read at most 400 KB of the page
      const reader = r.body.getReader(); const parts = []; let size = 0;
      while (size < 400_000) { const { done, value } = await reader.read(); if (done) break; parts.push(Buffer.from(value)); size += value.length; }
      try { await reader.cancel(); } catch (e) { }
      const html = Buffer.concat(parts).toString("utf8");
      const meta = {};
      for (const tag of html.match(/<meta\b[^>]*>/gi) || []) {
        const k = (tag.match(/\b(?:property|name)\s*=\s*["']([^"']+)["']/i) || [])[1];
        const v = (tag.match(/\bcontent\s*=\s*["']([^"']*)["']/i) || [])[1];
        if (k && v != null && !(k.toLowerCase() in meta)) meta[k.toLowerCase()] = ent(v);
      }
      out.title = str(meta["og:title"] || meta["twitter:title"] || ent((html.match(/<title[^>]*>([^<]*)<\/title>/i) || [])[1]), 200);
      const img = meta["og:image"] || meta["twitter:image"] || "";
      if (/^https:\/\//.test(img)) out.image = str(img, 600);
      if (meta["og:site_name"] && !SITE[host]) out.site = str(meta["og:site_name"], 60);
    } catch (e) { }
    return out;
  }

  // ---------- import the old community posts once ----------
  if (!db.imported) {
    try {
      const old = JSON.parse(fs.readFileSync(path.join(DATA_DIR, "feed.json"), "utf8"));
      for (const p of (old.posts || []).filter(p => !p.removed && !p.hidden)) {
        const uid = "l_" + crypto.createHash("sha256").update("old|" + p.dev).digest("hex").slice(0, 10);
        if (!db.users[uid]) db.users[uid] = { uid, name: p.name || "BigD friend", handle: "", bio: "", avatar: "", emoji: "🐾", at: p.at };
        db.posts.push({ id: db.nextPost++, uid, at: p.at, text: p.text, photos: [], link: null, likes: [], views: 0, comments: 0, reports: [] });
      }
    } catch (e) { }
    db.imported = true; save();
  }

  // ---------- limits ----------
  const counters = new Map();
  /** Daily and "not too fast" limits. Call with commit=false to check, then count it once it worked. */
  function limit(req, kind, perDay, gapMs, commit = true) {
    const k = devHash(req) + "|" + kind, now = Date.now();
    let c = counters.get(k); if (!c || c.day !== today()) c = { day: today(), n: 0, last: 0 };
    if (c.n >= perDay) return "You've reached today's limit. Please try again tomorrow.";
    if (gapMs && now - c.last < gapMs) return "Please wait a few seconds and try again.";
    if (commit) { c.n++; c.last = now; }
    counters.set(k, c); return "";
  }
  const reads = new Map();
  setInterval(() => { const now = Date.now(); for (const [k, l] of reads) if (!l.some(t => now - t < 600000)) reads.delete(k); }, 600000).unref();
  const viewSeen = new Set();

  function handleFree(name) {
    let base = String(name || "").toLowerCase().replace(/[^a-z0-9_]/g, "").slice(0, 14);
    if (base.length < 3) base = "friend";
    let h2 = base, n = 0;
    const taken = hd => Object.values(db.users).some(u => u.handle === hd) || Object.values(OFFICIAL).some(u => u.handle === hd);
    while (taken(h2)) h2 = base + (++n > 5 ? Math.floor(Math.random() * 9000 + 1000) : n);
    return h2;
  }

  // ---------- router ----------
  async function handle(req, res, url, origin) {
    const p = url.pathname;
    // photos people posted
    const f = p.match(/^\/u\/([a-f0-9]{20})\.jpg$/);
    if (req.method === "GET" && f) {
      const file = path.join(UP, f[1] + ".jpg");
      if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return true; }
      res.writeHead(200, { "Content-Type": "image/jpeg", "Cache-Control": "public, max-age=2592000" });
      fs.createReadStream(file).pipe(res); return true;
    }
    if (!p.startsWith("/api/social/")) return false;
    const me = uidOf(req, false);
    const admin = isAdmin(req);

    if (req.method === "GET") {
      if (!allowed("s:" + clientIp(req), reads, 600)) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
      if (p === "/api/social/feed") {
        const tab = str(url.searchParams.get("tab"), 40) || "foryou";
        const before = Number(url.searchParams.get("before")) || Date.now() + 1;
        const lim = Math.min(30, Number(url.searchParams.get("limit")) || 20);
        send(res, 200, Object.assign(feed(tab, before, lim, me), { admin }), origin); return true;
      }
      if (p === "/api/social/profile") {
        const uid = str(url.searchParams.get("uid"), 40);
        if (!userOf(uid)) { send(res, 404, { error: "This profile is not there." }, origin); return true; }
        send(res, 200, { user: userView(uid, me), admin }, origin); return true;
      }
      if (p === "/api/social/me") {
        const u = me && db.users[me];
        send(res, 200, { uid: me, profile: u && u.name ? userView(me, me) : null, code: u ? u.code || "" : "", admin }, origin); return true;
      }
      if (p === "/api/social/suggest") {
        const counts = followerCounts(), mine = new Set(db.follows[me] || []);
        const people = Object.values(db.users).filter(u => u.name && !u.banned && u.uid !== me && !mine.has(u.uid))
          .sort((a, b) => (counts[b.uid] || 0) - (counts[a.uid] || 0) || (b.at || 0) - (a.at || 0)).slice(0, 8).map(u => u.uid);
        const off = Object.keys(OFFICIAL).filter(u => !mine.has(u));
        send(res, 200, { users: off.concat(people).slice(0, 14).map(u => userView(u, me, counts)) }, origin); return true;
      }
      if (p === "/api/social/comments") {
        const key = str(url.searchParams.get("key"), 20);
        const counts = followerCounts();
        const list = db.comments.filter(c => c.key === key && !c.removed && (admin || !c.hidden)).slice(-200)
          .map(c => ({ id: c.id, at: c.at, text: c.text, author: userView(c.uid, me, counts), mine: c.uid === me, likes: (c.likes || []).length, hidden: !!c.hidden }));
        send(res, 200, { comments: list }, origin); return true;
      }
      send(res, 404, { error: "Not found" }, origin); return true;
    }

    if (req.method !== "POST") { send(res, 404, { error: "Not found" }, origin); return true; }
    if (!allowed(clientIp(req))) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
    let body;
    try { body = await readBody(req, p === "/api/social/upload" ? 4 * 1024 * 1024 : 64 * 1024); } catch (e) { send(res, 400, { error: e.message === "too_large" ? "That photo is too big." : "Bad request." }, origin); return true; }
    const uid = uidOf(req, true);
    const user = db.users[uid];
    const banned = user && user.banned;

    try {
      if (p === "/api/social/upload") {
        if (banned) { send(res, 403, { error: "You can't post." }, origin); return true; }
        const lim = limit(req, "upload", 40, 1000); if (lim) { send(res, 429, { error: lim }, origin); return true; }
        if (Object.keys(db.uploads).length >= MAX_UPLOADS) { send(res, 507, { error: "Photo space is full right now. Please try later." }, origin); return true; }
        const b64 = String(body.data || "");
        const buf = Buffer.from(b64, "base64");
        const jpeg = buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff;
        if (!jpeg || buf.length < 2000 || buf.length > 2.5 * 1024 * 1024) { send(res, 400, { error: "Please choose a normal photo (JPG)." }, origin); return true; }
        const chk = await checkPhoto("image/jpeg", b64);
        if (!chk.ok) { send(res, 400, { error: chk.reason }, origin); return true; }
        const id = crypto.randomBytes(10).toString("hex");
        fs.writeFileSync(path.join(UP, id + ".jpg"), buf);
        db.uploads[id] = { uid, at: Date.now() }; save();
        send(res, 200, { id, url: fileUrl(id) }, origin); return true;
      }

      if (p === "/api/social/me") {
        const name = str(body.name, 30).replace(/[\r\n]/g, " "), bio = str(body.bio, 160).replace(/\n{3,}/g, "\n\n");
        if (name.length < 2) { send(res, 400, { error: "Write your name (at least 2 letters)." }, origin); return true; }
        const chk = await checkText(name + "\n" + bio);
        if (!chk.ok) { send(res, 400, { error: chk.reason || "Please change your name or tagline." }, origin); return true; }
        const u = db.users[uid] || (db.users[uid] = { uid, at: Date.now(), code: crypto.randomBytes(5).toString("hex").toUpperCase() });
        if (!u.code) u.code = crypto.randomBytes(5).toString("hex").toUpperCase();
        u.name = name; u.bio = bio; u.emoji = str(body.emoji, 8) || u.emoji || "🐾";
        let hd = str(body.handle, 20).toLowerCase().replace(/^@/, "");
        if (hd && !/^[a-z0-9_]{3,20}$/.test(hd)) { send(res, 400, { error: "@name: use 3 to 20 small English letters, numbers or _." }, origin); return true; }
        if (hd && hd !== u.handle && (Object.values(db.users).some(x => x.handle === hd) || Object.values(OFFICIAL).some(x => x.handle === hd))) { send(res, 400, { error: "That @name is taken. Try another." }, origin); return true; }
        u.handle = hd || u.handle || handleFree(name);
        if (body.avatar !== undefined) {
          const a = str(body.avatar, 20);
          if (a && !(db.uploads[a] && db.uploads[a].uid === uid)) { send(res, 400, { error: "Upload the photo again." }, origin); return true; }
          u.avatar = a;
        }
        save(); send(res, 200, { profile: userView(uid, uid), code: u.code }, origin); return true;
      }

      if (p === "/api/social/restore") {
        const code = str(body.code, 20).toUpperCase().replace(/[^A-Z0-9]/g, "");
        const lim = limit(req, "restore", 10, 3000); if (lim) { send(res, 429, { error: lim }, origin); return true; }
        const u = code.length >= 8 && Object.values(db.users).find(x => x.code === code);
        if (!u) { send(res, 404, { error: "That backup code is not right." }, origin); return true; }
        db.devices[devHash(req)] = u.uid; save();
        send(res, 200, { profile: userView(u.uid, u.uid), code: u.code }, origin); return true;
      }

      if (p === "/api/social/post") {
        if (banned) { send(res, 403, { error: "You can't post." }, origin); return true; }
        const as = admin && OFFICIAL[str(body.as, 30)] ? str(body.as, 30) : uid;   // the team can post as an official account
        if (as === uid && !(user && user.name)) { send(res, 400, { error: "First make your profile (name and photo)." }, origin); return true; }
        const lim = admin ? "" : limit(req, "post", 10, 10000, false); if (lim) { send(res, 429, { error: lim }, origin); return true; }
        const text = str(body.text, 1000).replace(/\n{3,}/g, "\n\n");
        const photos = (Array.isArray(body.photos) ? body.photos : []).slice(0, 4).map(x => str(x, 20)).filter(x => db.uploads[x] && db.uploads[x].uid === uid);
        const linkUrl = str(body.link, 600);
        if (!text && !photos.length && !linkUrl) { send(res, 400, { error: "Write something, or add a photo or link." }, origin); return true; }
        if (text) { const chk = await checkText(text); if (!chk.ok) { send(res, 400, { error: chk.reason || "This post can't be shown. Please keep it kind and safe." }, origin); return true; } }
        const link = linkUrl ? await preview(linkUrl) : null;
        if (linkUrl && !link) { send(res, 400, { error: "That link doesn't look right. Copy it again." }, origin); return true; }
        if (!admin) limit(req, "post", 10, 10000);
        const post = { id: db.nextPost++, uid: as, at: Date.now(), text, photos, link, likes: [], views: 0, comments: 0, reports: [] };
        db.posts.push(post); save();
        send(res, 200, { post: postView(userPostToPost(post), uid) }, origin); return true;
      }

      if (p === "/api/social/preview") { send(res, 200, { link: await preview(body.url) }, origin); return true; }

      if (p === "/api/social/follow") {
        const t = str(body.uid, 40);
        if (!userOf(t) || t === uid) { send(res, 400, { error: "Can't follow that." }, origin); return true; }
        const list = db.follows[uid] || (db.follows[uid] = []);
        const i = list.indexOf(t); if (i >= 0) list.splice(i, 1); else list.push(t);
        save(); send(res, 200, { followed: i < 0, followers: followerCounts()[t] || 0 }, origin); return true;
      }

      if (p === "/api/social/views") {
        for (const key of (Array.isArray(body.keys) ? body.keys : []).slice(0, 60).map(k => str(k, 20))) {
          const k2 = uid + "|" + key + "|" + today();
          if (viewSeen.has(k2) || !findPost(key)) continue;
          viewSeen.add(k2); if (viewSeen.size > 200000) viewSeen.clear();
          const up = key[0] === "p" ? db.posts.find(q => "p" + q.id === key) : null;
          if (up) up.views = (up.views || 0) + 1; else stat(key).views++;
        }
        save(); send(res, 200, { ok: true }, origin); return true;
      }

      const key = str(body.key, 20);
      if (p === "/api/social/comment") {
        if (banned) { send(res, 403, { error: "You can't comment." }, origin); return true; }
        if (!(user && user.name)) { send(res, 400, { error: "First make your profile (just your name)." }, origin); return true; }
        if (!findPost(key)) { send(res, 404, { error: "That post is not there any more." }, origin); return true; }
        const lim = limit(req, "comment", 60, 3000, false); if (lim) { send(res, 429, { error: lim }, origin); return true; }
        const text = str(body.text, 500);
        if (text.length < 1) { send(res, 400, { error: "Write a comment first." }, origin); return true; }
        const chk = await checkText(text); if (!chk.ok) { send(res, 400, { error: chk.reason || "Please keep comments kind." }, origin); return true; }
        limit(req, "comment", 60, 3000);
        const c = { id: db.nextComment++, key, uid, at: Date.now(), text, likes: [], reports: [] };
        db.comments.push(c);
        const up = key[0] === "p" ? db.posts.find(q => "p" + q.id === key) : null;
        if (up) up.comments = (up.comments || 0) + 1; else stat(key).comments++;
        save(); send(res, 200, { comment: { id: c.id, at: c.at, text, author: userView(uid, uid), mine: true, likes: 0 } }, origin); return true;
      }

      if (p === "/api/social/like") {
        const found = findPost(key);
        if (!found) { send(res, 404, { error: "That post is not there any more." }, origin); return true; }
        const st = found.user || stat(key); st.likes = st.likes || [];
        const i = st.likes.indexOf(uid); if (i >= 0) st.likes.splice(i, 1); else st.likes.push(uid);
        save(); send(res, 200, { likes: st.likes.length, liked: i < 0 }, origin); return true;
      }

      if (p === "/api/social/report") {
        if (body.comment) {
          const c = db.comments.find(x => x.id === Number(body.comment));
          if (c && c.uid !== uid && !c.reports.includes(uid)) { c.reports.push(uid); if (c.reports.length >= 3) c.hidden = true; }
        } else {
          const found = findPost(key);
          if (found && found.user && found.user.uid !== uid && !found.user.reports.includes(uid)) {
            found.user.reports.push(uid); if (found.user.reports.length >= 3) found.user.hidden = true;
          }
          console.log("Social report:", key, str(body.reason, 80));
        }
        save(); send(res, 200, { ok: true }, origin); return true;
      }

      if (p === "/api/social/delete") {
        if (body.comment) {
          const c = db.comments.find(x => x.id === Number(body.comment));
          if (!c || (c.uid !== uid && !admin)) { send(res, 403, { error: "You can delete only your own comments." }, origin); return true; }
          c.removed = true;
          const up = c.key[0] === "p" ? db.posts.find(q => "p" + q.id === c.key) : null;
          if (up) up.comments = Math.max(0, (up.comments || 1) - 1); else stat(c.key).comments = Math.max(0, stat(c.key).comments - 1);
          save(); send(res, 200, { ok: true }, origin); return true;
        }
        const found = findPost(key);
        if (!found) { send(res, 404, { error: "Not found" }, origin); return true; }
        if (found.media) { if (!admin) { send(res, 403, { error: "Only the BigD team can remove this." }, origin); return true; } media.removeItem(found.media.id); send(res, 200, { ok: true }, origin); return true; }
        if (found.user.uid !== uid && !admin && !(OFFICIAL[found.user.uid] && admin)) { send(res, 403, { error: "You can delete only your own posts." }, origin); return true; }
        found.user.removed = true;
        for (const ph of found.user.photos || []) { try { fs.unlinkSync(path.join(UP, ph + ".jpg")); } catch (e) { } delete db.uploads[ph]; }
        save(); send(res, 200, { ok: true }, origin); return true;
      }

      if (p === "/api/social/admin") {
        if (!admin) { send(res, 401, { error: "Wrong team password." }, origin); return true; }
        const a = str(body.action, 20), target = str(body.uid, 40);
        if (a === "ban" || a === "unban" || a === "verify" || a === "unverify") {
          const u = db.users[target]; if (!u) { send(res, 404, { error: "No such user." }, origin); return true; }
          if (a === "ban") u.banned = true; if (a === "unban") u.banned = false; if (a === "verify") u.verified = true; if (a === "unverify") u.verified = false;
        } else if (a === "unhide") { const fp = findPost(key); if (fp && fp.user) { fp.user.hidden = false; fp.user.reports = []; } }
        else { send(res, 400, { error: "Unknown action." }, origin); return true; }
        save(); send(res, 200, { ok: true }, origin); return true;
      }
      send(res, 404, { error: "Not found" }, origin); return true;
    } catch (e) {
      console.error("Social error:", p, e.message);
      send(res, 502, { error: "Something went wrong. Please try again." }, origin); return true;
    }
  }

  function stats() { return { users: Object.values(db.users).filter(u => u.name).length, posts: db.posts.filter(x => !x.removed).length, photos: Object.keys(db.uploads).length }; }
  return { handle, stats };
};
