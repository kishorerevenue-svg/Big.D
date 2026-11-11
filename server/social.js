// Habit AI social ("Media" tab, like a small X/Twitter):
//  - official accounts for each Media section (✓), and a profile for every user (photo, name, @handle, tagline)
//  - posts with text, up to 4 photos (each checked by AI before anyone sees it) and a link from
//    YouTube / Instagram / Facebook / X / any site (shown as a preview)
//  - comments, likes, views, follow, report, and team tools (team password)
// Section posts written by the AI (media.js) appear in the same feed under their official accounts.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { preview } = require("./preview.js");
const video = require("./video.js");

module.exports = function makeSocial({ DATA_DIR, API_BASE, API_KEY, MODEL, adminPassword, media, reels, forgetOldFeed, h, ai }) {
  const { send, readBody, allowed, clientIp } = h;
  const FILE = path.join(DATA_DIR, "social.json");
  const UP = path.join(DATA_DIR, "uploads");
  fs.mkdirSync(UP, { recursive: true });
  const MAX_UPLOADS = Number(process.env.SOCIAL_MAX_UPLOADS || 20000);
  const MAX_VIDEO_MB = Number(process.env.SOCIAL_MAX_VIDEO_MB || 100);        // biggest video file a phone may send
  const MAX_VIDEO_SEC = Number(process.env.SOCIAL_MAX_VIDEO_SEC || 60);       // longest video, in seconds
  const MAX_VIDEO_GB = Number(process.env.SOCIAL_MAX_VIDEO_GB || 15);         // all videos together, on the server's disk
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);

  // ---------- storage ----------
  let db = { nextPost: 1, nextComment: 1, users: {}, devices: {}, uploads: {}, posts: [], comments: [], follows: {}, stats: {}, imported: false, bannedDevices: [] };
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
  // ---------- optional log in with a phone number or email + a password (to use the profile on a new phone) ----------
  // The number or email is never shown to anyone; the password is kept only as a scrambled scrypt hash.
  const EASY = new Set(["123456", "1234567", "12345678", "123456789", "1234567890", "111111", "000000", "password", "password1", "qwerty",
    "abc123", "654321", "112233", "123123", "666666", "888888", "999999", "121212", "iloveyou"]);
  /** "98480 12345", "+91 98480-12345", "Kishore@Gmail.com" → { kind, id }, or null when it isn't a mobile number or email. */
  function loginId(raw) {
    const s = String(raw || "").trim().toLowerCase().slice(0, 100);
    if (s.includes("@")) return /^[^\s@]+@[^\s@]+\.[a-z]{2,}$/.test(s) ? { kind: "email", id: s } : null;
    let d = s.replace(/\D/g, "");
    if (s.startsWith("+") && !d.startsWith("91")) return d.length >= 8 && d.length <= 15 ? { kind: "phone", id: "+" + d } : null;
    if (d.length === 12 && d.startsWith("91")) d = d.slice(2);
    if (d.length === 11 && d.startsWith("0")) d = d.slice(1);
    return /^[6-9]\d{9}$/.test(d) ? { kind: "phone", id: d } : null;
  }
  const scrypt = (pw, salt) => new Promise((ok, bad) => crypto.scrypt(String(pw), salt, 32, (e, k) => e ? bad(e) : ok(k.toString("hex"))));
  async function passwordOk(u, pw) {
    if (!u || !u.login || !u.login.hash) return false;
    const a = Buffer.from(await scrypt(pw, u.login.salt), "hex"), b = Buffer.from(u.login.hash, "hex");
    return a.length === b.length && crypto.timingSafeEqual(a, b);
  }
  // wrong tries: 5 per number/email and 10 per phone in 15 minutes
  const loginFails = new Map();
  setInterval(() => { const now = Date.now(); for (const [k, f] of loginFails) if (now > f.until) loginFails.delete(k); }, 30 * 60 * 1000).unref();
  const lockedOut = keys => keys.some(k => { const f = loginFails.get(k); return !!f && f.n >= (k.startsWith("dev|") ? 10 : 5) && Date.now() < f.until; });
  const failedLogin = keys => keys.forEach(k => { const f = loginFails.get(k) || { n: 0, until: 0 }; f.n++; f.until = Date.now() + 15 * 60 * 1000; loginFails.set(k, f); });
  const userByLogin = id => Object.values(db.users).find(x => x.login && x.login.id === id);
  /** What the app shows: "••••••2345" or "ki•••@gmail.com". */
  const loginShown = l => !l ? null : { kind: l.kind, shown: l.kind === "email" ? l.id.replace(/^(.{1,2})[^@]*(@.*)$/, "$1•••$2") : "••••••" + l.id.slice(-4) };
  /** Checks a number/email + password from the web or a new phone. Returns the profile, or a { status, error } to send. */
  async function checkLogin(req, body) {
    const L = loginId(body.id);
    const keys = ["dev|" + devHash(req)].concat(L ? ["id|" + L.id] : []);
    if (lockedOut(keys)) return { status: 429, error: "Too many wrong tries. Please wait 15 minutes and try again." };
    const u = L ? userByLogin(L.id) : null;
    if (!u || !(await passwordOk(u, String(body.password || "").slice(0, 64)))) { failedLogin(keys); return { status: 401, error: "Wrong phone number / email or password." }; }
    loginFails.delete("id|" + L.id);
    return { user: u };
  }

  function isAdmin(req) {
    const given = String(req.headers["x-admin-key"] || ""), pw = adminPassword();
    if (!pw || !given) return false;
    return crypto.timingSafeEqual(crypto.createHash("sha256").update(given).digest(), crypto.createHash("sha256").update(pw).digest());
  }

  // ---------- official accounts: one per Media section ----------
  const ICON = { news: "📰", movies: "🎬", kids: "🧒", crypto: "🪙", realestate: "🏠", jobs: "💼", devotional: "🛕", farming: "🌾", health: "🩺", facts: "💡", fun: "😄" };
  const BIO = {
    facts: "మీకు తెలుసా? ఆసక్తికరమైన నిజాలు · Interesting true facts",
    fun: "నవ్వులు, పొడుపు కథలు, మంచి మాటలు, హ్యాబిట్ కార్టూన్లు · Jokes, riddles and puppy cartoons",
    news: "రోజూ ముఖ్యమైన వార్తలు · Daily news for Telugu people", movies: "కొత్త సినిమాలు, విమర్శకుల అభిప్రాయాలు, ట్రైలర్లు · Movies, reviews, trailers",
    kids: "పిల్లలకు కథలు, పాటలు, పొడుపు కథలు · Stories and rhymes for kids", crypto: "క్రిప్టో వార్తలు, జాగ్రత్తలు · Crypto news (not financial advice)",
    realestate: "AP, తెలంగాణ రియల్ ఎస్టేట్ వార్తలు · Real estate news (not advice)", jobs: "కొత్త ఉద్యోగ నోటిఫికేషన్లు · New job notifications",
    devotional: "పండుగలు, ఆలయాలు, మంచి మాటలు · Festivals, temples, good thoughts", farming: "రైతులకు చిట్కాలు, పథకాలు, ధరలు · Tips and schemes for farmers",
    health: "ఆరోగ్య చిట్కాలు · Simple health tips (not medical advice)"
  };
  const OFFICIAL = {};
  for (const [k, c] of Object.entries(media.CATS)) OFFICIAL["s_" + k] = { uid: "s_" + k, name: "Habit " + c.te, handle: "habit_" + k, bio: BIO[k] || c.en, emoji: ICON[k] || "📺", verified: true, official: true, cat: k };
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
  const videoView = id => { const u = db.uploads[id]; return u && u.kind === "video" ? { url: `/u/${id}.mp4`, poster: `/u/${id}-p.jpg`, dur: Math.round(u.dur || 0), w: u.w || 0, h: u.h || 0 } : null; };
  // The app was called BigD before: profiles made then with the default name show the new one.
  const newName = n => n === "BigD friend" ? "Habit friend" : n;
  // @names that look like the app's own accounts (habit_news, habitai, habit_team…) are kept for the team.
  const reservedHandle = hd => /^(habit|bigd)(ai)?(_.*|app|team|official|support|admin)?$/.test(String(hd || ""));

  function userView(uid, me, counts) {
    const u = userOf(uid);
    if (!u) return { uid, name: "Habit friend", handle: "", bio: "", avatar: "", emoji: "🐾", verified: false };
    counts = counts || followerCounts();
    return { uid, name: newName(u.name) || "Habit friend", handle: u.handle || "", bio: u.bio || "", avatar: fileUrl(u.avatar), emoji: u.emoji || "🐾",
      verified: !!u.verified, official: !!u.official, followers: counts[uid] || 0, following: (db.follows[uid] || []).length,
      posts: postCount(uid), followed: !!me && (db.follows[me] || []).includes(uid), me: uid === me, banned: !!u.banned };
  }

  // ---------- posts (user posts "p12" and AI section posts "m34" together) ----------
  const stat = key => db.stats[key] || (db.stats[key] = { likes: [], views: 0, comments: 0 });
  function mediaToPost(i) {
    return { key: "m" + i.id, uid: "s_" + i.cat, at: i.at, title: i.title, text: i.body, photos: [], yt: i.yt || null,
      link: i.url && i.img ? { url: i.url, site: i.source || "", title: "", image: i.img, yt: "" } : null,   // the source page's own picture, like a link card on X
      source: i.url ? { name: i.source, url: i.url } : null, cat: i.cat, emoji: i.emoji || "",
      mood: i.mood || "", issue: i.issue || "", kind: i.kind || "", writer: i.writer || "", video: i.video || null,
      reel: i.reel && reels && reels.has(i.id) ? "/media/reel/" + i.id + ".mp4" : "" };   // a short video of this news, to share
  }
  function userPostToPost(p) { return { key: "p" + p.id, uid: p.uid, at: p.at, title: "", text: p.text, photos: (p.photos || []).map(fileUrl), video: p.cartoon || (p.video ? videoView(p.video) : null), yt: p.link && p.link.yt ? { id: p.link.yt, ch: p.link.site || "" } : null, link: p.link || null, source: null, cat: OFFICIAL[p.uid] ? OFFICIAL[p.uid].cat : "", emoji: "" }; }
  function postView(x, me, counts) {
    const s = x.key[0] === "p" ? db.posts.find(p => "p" + p.id === x.key) : null;
    const st = s || stat(x.key);
    return Object.assign({}, x, { author: userView(x.uid, me, counts), likes: (st.likes || []).length, liked: !!me && (st.likes || []).includes(me),
      views: st.views || 0, comments: st.comments || 0, mine: !!me && x.uid === me });
  }
  function allPosts() {
    return db.posts.filter(p => !p.removed && !p.hidden && !(db.users[p.uid] && db.users[p.uid].banned)).map(userPostToPost)
      .concat(media.allItems().filter(i => !i.hidden).map(mediaToPost));
  }
  /** "Popular now": what people liked, commented on and opened most in the last 3 days (newer counts more). */
  function popularity(x, now) {
    const st = x.key[0] === "p" ? db.posts.find(p => "p" + p.id === x.key) || {} : db.stats[x.key] || {};
    const score = (st.likes || []).length * 3 + (st.comments || 0) * 4 + (st.views || 0) * 0.2;
    return score / Math.pow((now - x.at) / 3600e3 + 2, 1.2);
  }
  function feed(tab, before, limit, me, filter = {}) {
    let list = allPosts();
    if (tab === "following") { const f = new Set(db.follows[me] || []); list = list.filter(x => f.has(x.uid)); }
    else if (tab === "people") list = list.filter(x => !OFFICIAL[x.uid]);
    else if (tab && tab.startsWith("user:")) list = list.filter(x => x.uid === tab.slice(5));
    else if (tab && media.CATS[tab]) list = list.filter(x => x.uid === "s_" + tab);
    // by mood (happy, devotional…) and by issue (farming, jobs, prices…)
    if (filter.mood) list = list.filter(x => x.mood === filter.mood);
    if (filter.issue) list = list.filter(x => x.issue === filter.issue);
    const counts = followerCounts();
    if (tab === "popular") {
      const now = Date.now();
      const top = list.filter(x => now - x.at < 3 * 86400e3).map(x => [x, popularity(x, now)]).filter(([, sc]) => sc > 0).sort((a, b) => b[1] - a[1]).map(([x]) => x);
      return { posts: top.slice(0, 40).map(x => postView(x, me, counts)), more: false };
    }
    list = list.filter(x => x.at < before).sort((a, b) => b.at - a.at);
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
    const data = await ai({ model: MODEL, max_tokens: maxTokens, system, messages: [{ role: "user", content }] }, { tries: 1, backup: true });
    const text = (data.content || []).filter(c => c.type === "text").map(c => c.text).join("");
    try { return JSON.parse(text); } catch (e) { const a = text.indexOf("{"), b = text.lastIndexOf("}"); if (a >= 0 && b > a) { try { return JSON.parse(text.slice(a, b + 1)); } catch (e2) { } } }
    return {};
  }
  const TEXT_RULES = `You moderate the public pages of Habit AI, a family-friendly app used by people of all ages in India (Telugu and English). Decide if this text can be shown to everyone.
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
  const PHOTO_RULES = `You check photos before they are shown publicly in Habit AI, a family-friendly app used by people of all ages in India.
Reject the photo if it has: nudity or sexual content; anyone who may be under 18 shown in a revealing, sexual or unsafe way; graphic violence, blood or dead bodies; hate symbols; weapons used to threaten; drugs; ID cards, bank cards, cheques or documents with personal numbers; screenshots that show phone numbers or private chats; ads, QR codes for payments or spam.
Allow ordinary photos: people, family, festivals, food, places, nature, animals, events, memes that are not hateful.
Reply with only a JSON object: {"ok":true|false,"reason":"<if rejected: one short, kind sentence>"}`;
  async function checkPhoto(type, b64) {
    if (!API_KEY) return { ok: false, reason: "Photos can't be checked right now. Please try later." };
    const o = await claude(PHOTO_RULES, [{ type: "image", source: { type: "base64", media_type: type, data: b64 } }, { type: "text", text: "Check this photo." }]);
    return { ok: o.ok === true, reason: str(o.reason, 200) || "This photo can't be shown." };
  }

  // ---------- deleting a profile and everything linked to it ----------
  /** Deletes an uploaded photo or video (with its poster) from the disk and the list. */
  function unlinkUpload(id) {
    for (const f of [id + ".jpg", id + ".mp4", id + "-p.jpg"]) { try { fs.unlinkSync(path.join(UP, f)); } catch (e) { } }
    delete db.uploads[id];
  }
  /** Deletes the profile [uid]: its posts and photos, comments, likes, follows, reports, uploads and the phones linked to it. */
  function deleteUser(uid) {
    if (!uid || OFFICIAL[uid]) return { posts: 0, comments: 0 };
    const u = db.users[uid];
    if (u && u.banned) for (const [d, x] of Object.entries(db.devices)) if (x === uid && !db.bannedDevices.includes(d)) db.bannedDevices.push(d);  // a stopped person can't simply start again
    const mine = db.posts.filter(p => p.uid === uid);
    const gone = new Set(mine.map(p => "p" + p.id));
    for (const p of mine) { for (const ph of p.photos || []) unlinkUpload(ph); if (p.video) unlinkUpload(p.video); }
    db.posts = db.posts.filter(p => p.uid !== uid);
    for (const k of gone) delete db.stats[k];
    // comments they wrote (other posts lose one from their count), and all comments on their deleted posts
    let comments = 0;
    for (const c of db.comments) {
      if (c.uid !== uid || gone.has(c.key)) continue;
      comments++;
      if (c.removed) continue;
      const up = c.key[0] === "p" ? db.posts.find(q => "p" + q.id === c.key) : null;
      if (up) up.comments = Math.max(0, (up.comments || 1) - 1); else if (db.stats[c.key]) db.stats[c.key].comments = Math.max(0, (db.stats[c.key].comments || 1) - 1);
    }
    db.comments = db.comments.filter(c => c.uid !== uid && !gone.has(c.key));
    // their likes and reports on other people's posts and comments
    const drop = list => Array.isArray(list) ? list.filter(x => x !== uid) : list;
    for (const p of db.posts) { p.likes = drop(p.likes); p.reports = drop(p.reports); }
    for (const st of Object.values(db.stats)) st.likes = drop(st.likes);
    for (const c of db.comments) { c.likes = drop(c.likes); c.reports = drop(c.reports); }
    for (const i of media.allItems()) if (Array.isArray(i.reports)) i.reports = drop(i.reports);
    // who they follow, and who follows them
    delete db.follows[uid];
    for (const list of Object.values(db.follows)) { const i = list.indexOf(uid); if (i >= 0) list.splice(i, 1); }
    // their photos (profile photo, unused photos) and the phones linked to the profile
    for (const [id, x] of Object.entries(db.uploads)) if (x.uid === uid) unlinkUpload(id);
    for (const [d, x] of Object.entries(db.devices)) if (x === uid) delete db.devices[d];
    for (const k of viewSeen) if (k.startsWith(uid + "|")) viewSeen.delete(k);
    delete db.users[uid];
    save(); if (media.changed) media.changed();
    console.log(`Social: deleted profile ${uid} (${mine.length} posts, ${comments} comments)`);
    return { posts: mine.length, comments };
  }

  // ---------- import the old community posts once ----------
  if (!db.imported) {
    try {
      const old = JSON.parse(fs.readFileSync(path.join(DATA_DIR, "feed.json"), "utf8"));
      for (const p of (old.posts || []).filter(p => !p.removed && !p.hidden)) {
        const uid = "l_" + crypto.createHash("sha256").update("old|" + p.dev).digest("hex").slice(0, 10);
        if (!db.users[uid]) db.users[uid] = { uid, name: p.name || "Habit friend", handle: "", bio: "", avatar: "", emoji: "🐾", at: p.at };
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
    if (base.length < 3 || reservedHandle(base)) base = "friend";
    let h2 = base, n = 0;
    const taken = hd => reservedHandle(hd) || Object.values(db.users).some(u => u.handle === hd) || Object.values(OFFICIAL).some(u => u.handle === hd);
    while (taken(h2)) h2 = base + (++n > 5 ? Math.floor(Math.random() * 9000 + 1000) : n);
    return h2;
  }

  // ---------- videos ----------
  /** Sends an uploaded file; videos support Range requests, so the player can start and jump anywhere. */
  function serveFile(req, res, name, type) {
    const file = path.join(UP, name);
    let st; try { st = fs.statSync(file); } catch (e) { res.writeHead(404); res.end(); return; }
    const size = st.size, head = { "Content-Type": type, "Cache-Control": "public, max-age=2592000", "Accept-Ranges": "bytes" };
    const m = String(req.headers.range || "").match(/^bytes=(\d*)-(\d*)$/);
    if (m && (m[1] || m[2])) {
      let start = m[1] ? Number(m[1]) : Math.max(0, size - Number(m[2]));
      let end = m[1] && m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
      if (start >= size || start > end) { res.writeHead(416, { "Content-Range": `bytes */${size}` }); res.end(); return; }
      res.writeHead(206, Object.assign(head, { "Content-Range": `bytes ${start}-${end}/${size}`, "Content-Length": end - start + 1 }));
      if (req.method === "HEAD") { res.end(); return; }
      fs.createReadStream(file, { start, end }).pipe(res); return;
    }
    res.writeHead(200, Object.assign(head, { "Content-Length": size }));
    if (req.method === "HEAD") { res.end(); return; }
    fs.createReadStream(file).pipe(res);
  }

  const vjobs = new Map();   // id -> { uid, status: "uploading" | "waiting" | "processing" | "failed", reason, at }
  const vqueue = [];
  let vbusy = false;
  function videoBytes() {
    let n = 0;
    for (const [id, u] of Object.entries(db.uploads)) if (u.kind === "video") n += u.bytes || 0;
    return n;
  }
  async function checkFrames(files) {
    if (!API_KEY) return { ok: false, reason: "Videos can't be checked right now. Please try later." };
    const content = files.map(f => ({ type: "image", source: { type: "base64", media_type: "image/jpeg", data: fs.readFileSync(f).toString("base64") } }));
    content.push({ type: "text", text: "These are 4 frames from one short video that someone wants to post. Check the video using the frames." });
    const o = await claude(PHOTO_RULES, content);
    return { ok: o.ok === true, reason: str(o.reason, 200) || "This video can't be shown." };
  }
  async function videoWorker() {
    if (vbusy) return;
    vbusy = true;
    while (vqueue.length) {
      const id = vqueue.shift(), job = vjobs.get(id);
      if (!job) continue;
      job.status = "processing";
      const raw = path.join(UP, id + ".upload"), mp4 = path.join(UP, id + ".mp4"), poster = path.join(UP, id + "-p.jpg"), fp = path.join(UP, id + "-f");
      let frames = [];
      try {
        const first = await video.probe(raw).catch(() => ({ video: false, dur: 0 }));
        if (!first.video) throw Object.assign(new Error("bad"), { user: "That file isn't a video we can play. Please choose another video." });
        if (first.dur > MAX_VIDEO_SEC + 1.5) throw Object.assign(new Error("long"), { user: `Please choose a video up to ${MAX_VIDEO_SEC} seconds long (trim it in your Gallery first).` });
        const out = await video.convert(raw, mp4, poster, fp, MAX_VIDEO_SEC);
        frames = out.frames;
        const chk = await checkFrames(frames);
        if (!chk.ok) throw Object.assign(new Error("unsafe"), { user: chk.reason });
        db.uploads[id] = { uid: job.uid, at: Date.now(), kind: "video", dur: out.dur, w: out.w, h: out.h, bytes: fs.statSync(mp4).size };
        save(); vjobs.delete(id);
        console.log(`Social: video ${id} ready (${out.dur.toFixed(1)} s, ${out.w}x${out.h}, ${(db.uploads[id].bytes / 1048576).toFixed(1)} MB)`);
      } catch (e) {
        for (const x of [mp4, poster]) { try { fs.unlinkSync(x); } catch (e2) { } }
        job.status = "failed"; job.reason = e.user || "Couldn't prepare that video. Please try another one.";
        if (!e.user) console.error("Social video error:", id, e.message, e.stderr || "");
      }
      for (const x of frames.concat([raw])) { try { fs.unlinkSync(x); } catch (e) { } }
    }
    vbusy = false;
  }
  /** POST /api/social/upload-video: the phone sends the video file itself (not JSON); it is prepared in the background. */
  async function uploadVideo(req, res, origin) {
    const uid = uidOf(req, true), user = db.users[uid];
    if ((user && user.banned) || db.bannedDevices.includes(devHash(req))) { send(res, 403, { error: "You can't post." }, origin); req.resume(); return; }
    if (!(await video.available())) { send(res, 503, { error: "Videos can't be added yet. Please try again tomorrow." }, origin); req.resume(); return; }
    const size = Number(req.headers["content-length"] || 0);   // 0 = sent in chunks, size unknown: the counter below still stops big files
    if (size > MAX_VIDEO_MB * 1048576) { send(res, 413, { error: `Please choose a video smaller than ${MAX_VIDEO_MB} MB (up to ${MAX_VIDEO_SEC} seconds).` }, origin); req.resume(); return; }
    if (videoBytes() > MAX_VIDEO_GB * 1073741824) { send(res, 507, { error: "Video space is full right now. Please try later." }, origin); req.resume(); return; }
    if ([...vjobs.values()].filter(j => j.status !== "failed").length >= 6) { send(res, 503, { error: "Many videos are being prepared. Please try again in a few minutes." }, origin); req.resume(); return; }
    const lim = limit(req, "video", 10, 20000, false); if (lim) { send(res, 429, { error: lim }, origin); req.resume(); return; }
    const id = crypto.randomBytes(10).toString("hex"), raw = path.join(UP, id + ".upload");
    vjobs.set(id, { uid, status: "uploading", at: Date.now() });
    const out = fs.createWriteStream(raw);
    let got = 0, failed = false;
    const fail = (code, msg) => {
      if (failed) return; failed = true; vjobs.delete(id);
      try { out.destroy(); } catch (e) { } try { fs.unlinkSync(raw); } catch (e) { }
      try { send(res, code, { error: msg }, origin); } catch (e) { }
    };
    req.on("data", c => { got += c.length; if (got > MAX_VIDEO_MB * 1048576) { fail(413, `Please choose a video smaller than ${MAX_VIDEO_MB} MB.`); req.destroy(); } });
    req.on("close", () => { if (!req.complete) fail(400, "The upload stopped. Please try again."); });
    req.on("error", () => fail(400, "The upload stopped. Please try again."));
    out.on("error", () => fail(500, "Couldn't save the video. Please try again."));
    out.on("finish", () => {
      if (failed) return;
      if (got < 10000) { fail(400, "That video is empty. Please choose another one."); return; }
      limit(req, "video", 10, 20000);
      const job = vjobs.get(id); job.status = "waiting";
      vqueue.push(id); videoWorker();
      send(res, 200, { id, status: "processing" }, origin);
    });
    req.pipe(out);
  }
  // forget old failures, and delete photos and videos that were uploaded but never used in a post or profile
  setInterval(() => {
    const now = Date.now();
    for (const [id, j] of vjobs) if (now - j.at > 3600e3) { vjobs.delete(id); try { fs.unlinkSync(path.join(UP, id + ".upload")); } catch (e) { } }
    const used = new Set();
    for (const q of db.posts) { if (q.removed) continue; (q.photos || []).forEach(x => used.add(x)); if (q.video) used.add(q.video); }
    for (const u of Object.values(db.users)) if (u.avatar) used.add(u.avatar);
    let n = 0;
    for (const [id, u] of Object.entries(db.uploads)) if (!used.has(id) && now - (u.at || 0) > 48 * 3600e3) { unlinkUpload(id); n++; }
    if (n) { save(); console.log(`Social: removed ${n} unused upload(s)`); }
  }, 3600e3).unref();

  // ---------- router ----------
  async function handle(req, res, url, origin) {
    const p = url.pathname;
    // photos people posted
    const f = p.match(/^\/u\/([a-f0-9]{20})(-p)?\.(jpg|mp4)$/);
    if ((req.method === "GET" || req.method === "HEAD") && f) { serveFile(req, res, f[1] + (f[2] || "") + "." + f[3], f[3] === "mp4" ? "video/mp4" : "image/jpeg"); return true; }
    if (!p.startsWith("/api/social/")) return false;
    const me = uidOf(req, false);
    const admin = isAdmin(req);

    if (req.method === "GET") {
      if (!allowed("s:" + clientIp(req), reads, 600)) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
      if (p === "/api/social/feed") {
        const tab = str(url.searchParams.get("tab"), 40) || "foryou";
        const before = Number(url.searchParams.get("before")) || Date.now() + 1;
        const lim = Math.min(30, Number(url.searchParams.get("limit")) || 20);
        const filter = { mood: str(url.searchParams.get("mood"), 20), issue: str(url.searchParams.get("issue"), 20) };
        send(res, 200, Object.assign(feed(tab, before, lim, me, filter), { admin }), origin); return true;
      }
      if (p === "/api/social/profile") {
        const uid = str(url.searchParams.get("uid"), 40);
        if (!userOf(uid)) { send(res, 404, { error: "This profile is not there." }, origin); return true; }
        send(res, 200, { user: userView(uid, me), admin }, origin); return true;
      }
      if (p === "/api/social/me") {
        const u = me && db.users[me];
        send(res, 200, { uid: me, profile: u && u.name ? userView(me, me) : null, code: u ? u.code || "" : "", login: u ? loginShown(u.login) : null, admin }, origin); return true;
      }
      if (p === "/api/social/suggest") {
        const counts = followerCounts(), mine = new Set(db.follows[me] || []);
        const people = Object.values(db.users).filter(u => u.name && !u.banned && u.uid !== me && !mine.has(u.uid))
          .sort((a, b) => (counts[b.uid] || 0) - (counts[a.uid] || 0) || (b.at || 0) - (a.at || 0)).slice(0, 8).map(u => u.uid);
        const off = Object.keys(OFFICIAL).filter(u => !mine.has(u));
        send(res, 200, { users: off.concat(people).slice(0, 14).map(u => userView(u, me, counts)) }, origin); return true;
      }
      if (p === "/api/social/upload-status") {
        const id = str(url.searchParams.get("id"), 20), j = vjobs.get(id), u = db.uploads[id];
        if (u && u.kind === "video" && u.uid === me) { send(res, 200, Object.assign({ status: "ready", id }, videoView(id)), origin); return true; }
        if (j && j.uid === me) { send(res, 200, { status: j.status === "failed" ? "failed" : "processing", reason: j.reason || "", ahead: Math.max(0, vqueue.indexOf(id)) }, origin); return true; }
        send(res, 404, { status: "failed", reason: "That video is not there any more. Please add it again." }, origin); return true;
      }
      if (p === "/api/social/reports") {
        // the team's list of reported posts and comments, newest report first
        if (!admin) { send(res, 401, { error: "Wrong team password." }, origin); return true; }
        const counts = followerCounts();
        const extra = x => ({ reports: (x.reports || []).length, reasons: x.reasons || [], hidden: !!x.hidden, reportedAt: x.reportedAt || 0 });
        const posts = db.posts.filter(q => !q.removed && (q.reports || []).length).map(q => Object.assign(postView(userPostToPost(q), me, counts), extra(q)))
          .concat(media.allItems().filter(i => (i.reports || []).length).map(i => Object.assign(postView(mediaToPost(i), me, counts), extra(i))))
          .sort((a, b) => b.reportedAt - a.reportedAt).slice(0, 100);
        const comments = db.comments.filter(c => !c.removed && (c.reports || []).length)
          .map(c => Object.assign({ id: c.id, key: c.key, at: c.at, text: c.text, author: userView(c.uid, me, counts) }, extra(c)))
          .sort((a, b) => b.reportedAt - a.reportedAt).slice(0, 100);
        send(res, 200, { posts, comments }, origin); return true;
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
    if (!allowed(clientIp(req))) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); req.resume(); return true; }
    if (p === "/api/social/upload-video") { try { await uploadVideo(req, res, origin); } catch (e) { console.error("Social video upload error:", e.message); try { send(res, 500, { error: "Something went wrong. Please try again." }, origin); } catch (e2) { } } return true; }
    let body;
    try { body = await readBody(req, p === "/api/social/upload" ? 4 * 1024 * 1024 : 64 * 1024); } catch (e) { send(res, 400, { error: e.message === "too_large" ? "That photo is too big." : "Bad request." }, origin); return true; }
    if (p === "/api/social/delete-by-code") {
      // from the web page bigdai.tech/delete-account: delete a profile with its backup code
      const lim = limit(req, "delcode", 10, 3000); if (lim) { send(res, 429, { error: lim }, origin); return true; }
      let u = null;
      if (body.id) {
        // or with the phone number / email and password
        const c = await checkLogin(req, body);
        if (!c.user) { send(res, c.status, { error: c.error }, origin); return true; }
        u = c.user;
      } else {
        const code = str(body.code, 20).toUpperCase().replace(/[^A-Z0-9]/g, "");
        u = code.length >= 8 && Object.values(db.users).find(x => x.code === code);
        if (!u) { send(res, 404, { error: "That backup code is not right." }, origin); return true; }
      }
      const name = u.name || "";
      try { const r = deleteUser(u.uid); send(res, 200, { ok: true, name, deleted: r }, origin); }
      catch (e) { console.error("Social delete error:", e.message); send(res, 502, { error: "Something went wrong. Please try again." }, origin); }
      return true;
    }
    const uid = uidOf(req, true);
    const user = db.users[uid];
    const banned = (user && user.banned) || db.bannedDevices.includes(devHash(req));

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
        if (banned) { send(res, 403, { error: "You can't change your profile." }, origin); return true; }
        const name = str(body.name, 30).replace(/[\r\n]/g, " "), bio = str(body.bio, 160).replace(/\n{3,}/g, "\n\n");
        if (name.length < 2) { send(res, 400, { error: "Write your name (at least 2 letters)." }, origin); return true; }
        const chk = await checkText(name + "\n" + bio);
        if (!chk.ok) { send(res, 400, { error: chk.reason || "Please change your name or tagline." }, origin); return true; }
        const u = db.users[uid] || (db.users[uid] = { uid, at: Date.now(), code: crypto.randomBytes(5).toString("hex").toUpperCase() });
        if (!u.code) u.code = crypto.randomBytes(5).toString("hex").toUpperCase();
        u.name = name; u.bio = bio; u.emoji = str(body.emoji, 8) || u.emoji || "🐾";
        // forgiving @name: "Kishore.Kumar" or "kishore kumar" becomes "kishore_kumar"
        let hd = str(body.handle, 40).toLowerCase().replace(/^@/, "").replace(/[\s.\-]+/g, "_").replace(/[^a-z0-9_]/g, "")
          .replace(/_+/g, "_").replace(/^_+|_+$/g, "").slice(0, 20);
        if (hd && !/^[a-z0-9_]{3,20}$/.test(hd)) { send(res, 400, { error: "@name: use 3 to 20 small English letters, numbers or _." }, origin); return true; }
        if (hd && hd !== u.handle && reservedHandle(hd)) { send(res, 400, { error: "That @name is kept for the Habit AI team. Try another." }, origin); return true; }
        if (hd && hd !== u.handle && (Object.values(db.users).some(x => x.handle === hd) || Object.values(OFFICIAL).some(x => x.handle === hd))) { send(res, 400, { error: "That @name is taken. Try another." }, origin); return true; }
        u.handle = hd || u.handle || handleFree(name);
        if (body.avatar !== undefined) {
          const a = str(body.avatar, 20);
          if (a && !(db.uploads[a] && db.uploads[a].uid === uid && db.uploads[a].kind !== "video")) { send(res, 400, { error: "Upload the photo again." }, origin); return true; }
          u.avatar = a;
        }
        save(); send(res, 200, { profile: userView(uid, uid), code: u.code, login: loginShown(u.login) }, origin); return true;
      }

      if (p === "/api/social/account") {
        // the app's first screen: is there a Habit account with this mobile number (or email)?
        const lim = limit(req, "account", 60, 400); if (lim) { send(res, 429, { error: lim }, origin); return true; }
        const L = loginId(body.id);
        if (!L) { send(res, 400, { error: "Type your 10-digit mobile number." }, origin); return true; }
        const other = userByLogin(L.id);
        send(res, 200, { kind: L.kind, exists: !!other, mine: !!other && other.uid === uid, name: user && user.name ? user.name : "", hasLogin: !!(user && user.login) }, origin); return true;
      }
      if (p === "/api/social/signup") {
        // a new Habit account: mobile number (or email), password and name. A profile already on this phone keeps
        // everything it has (posts, chats): the login is added to it.
        if (banned) { send(res, 403, { error: "You can't make an account." }, origin); return true; }
        const lim = limit(req, "signup", 10, 800); if (lim) { send(res, 429, { error: lim }, origin); return true; }
        const L = loginId(body.id);
        if (!L) { send(res, 400, { error: "Type your 10-digit mobile number." }, origin); return true; }
        const pw = String(body.password || "");
        if (pw.length < 6 || pw.length > 64) { send(res, 400, { error: "The password needs 6 or more letters or numbers." }, origin); return true; }
        if (EASY.has(pw.toLowerCase())) { send(res, 400, { error: "That password is too easy to guess. Please choose another one." }, origin); return true; }
        const other = userByLogin(L.id);
        if (other && other.uid !== uid) { send(res, 409, { error: L.kind === "email" ? "This email already has a Habit account. Enter its password." : "This number already has a Habit account. Enter its password.", exists: true }, origin); return true; }
        const name = str(body.name, 30).replace(/[\r\n]/g, " ").trim() || (user && user.name) || "";
        if (name.length < 2) { send(res, 400, { error: "Write your name (at least 2 letters)." }, origin); return true; }
        if (!(user && user.name === name)) { const chk = await checkText(name); if (!chk.ok) { send(res, 400, { error: chk.reason || "Please change your name." }, origin); return true; } }
        const u = db.users[uid] || (db.users[uid] = { uid, at: Date.now(), code: crypto.randomBytes(5).toString("hex").toUpperCase() });
        if (!u.code) u.code = crypto.randomBytes(5).toString("hex").toUpperCase();
        u.name = name; u.emoji = u.emoji || "🐾"; u.bio = u.bio || ""; u.handle = u.handle || handleFree(name);
        const salt = crypto.randomBytes(16).toString("hex");
        u.login = { kind: L.kind, id: L.id, salt, hash: await scrypt(pw, salt), at: Date.now() };
        save(); send(res, 200, { profile: userView(uid, uid), code: u.code, login: loginShown(u.login) }, origin); return true;
      }

      if (p === "/api/social/login-set") {
        // add (or change) "log in with phone number or email + password" for this phone's profile
        if (!(user && user.name)) { send(res, 400, { error: "First make your profile." }, origin); return true; }
        const lim = limit(req, "loginset", 10, 2000); if (lim) { send(res, 429, { error: lim }, origin); return true; }
        const L = loginId(body.id);
        if (!L) { send(res, 400, { error: "Type a 10-digit mobile number or an email address." }, origin); return true; }
        const pw = String(body.password || "");
        if (pw.length < 6 || pw.length > 64) { send(res, 400, { error: "The password needs 6 or more letters or numbers." }, origin); return true; }
        if (EASY.has(pw.toLowerCase())) { send(res, 400, { error: "That password is too easy to guess. Please choose another one." }, origin); return true; }
        const other = userByLogin(L.id);
        if (other && other.uid !== uid) { send(res, 400, { error: L.kind === "email" ? "This email is already used by another profile." : "This phone number is already used by another profile." }, origin); return true; }
        const salt = crypto.randomBytes(16).toString("hex");
        user.login = { kind: L.kind, id: L.id, salt, hash: await scrypt(pw, salt), at: Date.now() };
        save(); send(res, 200, { ok: true, login: loginShown(user.login) }, origin); return true;
      }

      if (p === "/api/social/login-remove") {
        if (user && user.login) { delete user.login; save(); }
        send(res, 200, { ok: true }, origin); return true;
      }

      if (p === "/api/social/login") {
        // on a new phone: log in with the phone number or email and the password; this phone is then linked to the profile
        const c = await checkLogin(req, body);
        if (!c.user) { send(res, c.status, { error: c.error }, origin); return true; }
        db.devices[devHash(req)] = c.user.uid; save();
        send(res, 200, { profile: userView(c.user.uid, c.user.uid), code: c.user.code, login: loginShown(c.user.login) }, origin); return true;
      }

      if (p === "/api/social/restore") {
        const code = str(body.code, 20).toUpperCase().replace(/[^A-Z0-9]/g, "");
        const lim = limit(req, "restore", 10, 3000); if (lim) { send(res, 429, { error: lim }, origin); return true; }
        const u = code.length >= 8 && Object.values(db.users).find(x => x.code === code);
        if (!u) { send(res, 404, { error: "That backup code is not right." }, origin); return true; }
        db.devices[devHash(req)] = u.uid; save();
        send(res, 200, { profile: userView(u.uid, u.uid), code: u.code, login: loginShown(u.login) }, origin); return true;
      }

      if (p === "/api/social/post") {
        if (banned) { send(res, 403, { error: "You can't post." }, origin); return true; }
        const as = admin && OFFICIAL[str(body.as, 30)] ? str(body.as, 30) : uid;   // the team can post as an official account
        if (as === uid && !(user && user.name)) { send(res, 400, { error: "First make your profile (name and photo)." }, origin); return true; }
        const lim = admin ? "" : limit(req, "post", 10, 10000, false); if (lim) { send(res, 429, { error: lim }, origin); return true; }
        const text = str(body.text, 1000).replace(/\n{3,}/g, "\n\n");
        const vid = str(body.video, 20);
        const videoId = vid && db.uploads[vid] && db.uploads[vid].uid === uid && db.uploads[vid].kind === "video" ? vid : "";
        if (vid && !videoId) { send(res, 400, { error: "The video isn't ready yet. Please wait a moment, or add it again." }, origin); return true; }
        const photos = videoId ? [] : (Array.isArray(body.photos) ? body.photos : []).slice(0, 4).map(x => str(x, 20)).filter(x => db.uploads[x] && db.uploads[x].uid === uid && db.uploads[x].kind !== "video");
        const linkUrl = str(body.link, 600);
        if (!text && !photos.length && !videoId && !linkUrl) { send(res, 400, { error: "Write something, or add a photo, video or link." }, origin); return true; }
        if (text) { const chk = await checkText(text); if (!chk.ok) { send(res, 400, { error: chk.reason || "This post can't be shown. Please keep it kind and safe." }, origin); return true; } }
        const link = linkUrl ? await preview(linkUrl) : null;
        if (linkUrl && !link) { send(res, 400, { error: "That link doesn't look right. Copy it again." }, origin); return true; }
        if (!admin) limit(req, "post", 10, 10000);
        const post = { id: db.nextPost++, uid: as, at: Date.now(), text, photos, video: videoId || undefined, link, likes: [], views: 0, comments: 0, reports: [] };
        db.posts.push(post); save();
        send(res, 200, { post: postView(userPostToPost(post), uid) }, origin); return true;
      }

      if (p === "/api/social/delete-me") {
        // from the app: delete this phone's profile and everything linked to it
        if (str(body.confirm, 10) !== "DELETE") { send(res, 400, { error: "Please confirm." }, origin); return true; }
        const r = user ? deleteUser(uid) : { posts: 0, comments: 0 };
        if (!user) delete db.devices[devHash(req)];
        // also anything this phone did on the old Community page and old likes
        let old = 0;
        try { if (media.forgetDevice) media.forgetDevice(req); } catch (e) { }
        try {
          const oldDev = forgetOldFeed ? forgetOldFeed(req) : "";
          if (oldDev) { const lid = "l_" + crypto.createHash("sha256").update("old|" + oldDev).digest("hex").slice(0, 10); if (db.users[lid]) old = deleteUser(lid).posts; }
        } catch (e) { }
        save();
        send(res, 200, { ok: true, deleted: { posts: r.posts + old, comments: r.comments } }, origin); return true;
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
        const reason = str(body.reason, 80) || "Reported";
        // reports are kept (who and why) until the team checks them; 3 reports hide the post until then
        const mark = x => {
          x.reports = Array.isArray(x.reports) ? x.reports : [];
          if (x.reports.includes(uid)) return;
          x.reports.push(uid); x.reasons = (x.reasons || []).concat(reason).slice(-10); x.reportedAt = Date.now();
          if (x.reports.length >= 3) x.hidden = true;
        };
        if (body.comment) {
          const c = db.comments.find(x => x.id === Number(body.comment));
          if (c && c.uid !== uid) mark(c);
        } else {
          const found = findPost(key);
          if (found && found.user && found.user.uid !== uid) mark(found.user);
          if (found && found.media) { mark(found.media); if (media.changed) media.changed(); }   // AI-written posts can be reported too
          console.log("Social report:", key, reason);
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
        if (found.media) { if (!admin) { send(res, 403, { error: "Only the Habit team can remove this." }, origin); return true; } media.removeItem(found.media.id); send(res, 200, { ok: true }, origin); return true; }
        if (found.user.uid !== uid && !admin && !(OFFICIAL[found.user.uid] && admin)) { send(res, 403, { error: "You can delete only your own posts." }, origin); return true; }
        found.user.removed = true;
        for (const ph of found.user.photos || []) unlinkUpload(ph);
        if (found.user.video) unlinkUpload(found.user.video);
        save(); send(res, 200, { ok: true }, origin); return true;
      }

      if (p === "/api/social/admin") {
        if (!admin) { send(res, 401, { error: "Wrong team password." }, origin); return true; }
        const a = str(body.action, 20), target = str(body.uid, 40);
        if (a === "ban" || a === "unban" || a === "verify" || a === "unverify") {
          const u = db.users[target]; if (!u) { send(res, 404, { error: "No such user." }, origin); return true; }
          if (a === "ban") u.banned = true; if (a === "unban") u.banned = false; if (a === "verify") u.verified = true; if (a === "unverify") u.verified = false;
        } else if (a === "unhide") {
          // the team checked it and it's fine: show it again and clear its reports
          const ok = x => { x.hidden = false; x.reports = []; x.reasons = []; delete x.reportedAt; };
          if (body.comment) { const c = db.comments.find(x => x.id === Number(body.comment)); if (c) ok(c); }
          else { const fp = findPost(key); if (fp && fp.user) ok(fp.user); if (fp && fp.media) { ok(fp.media); if (media.changed) media.changed(); } }
        }
        else { send(res, 400, { error: "Unknown action." }, origin); return true; }
        save(); send(res, 200, { ok: true }, origin); return true;
      }
      send(res, 404, { error: "Not found" }, origin); return true;
    } catch (e) {
      console.error("Social error:", p, e.message);
      send(res, 502, { error: "Something went wrong. Please try again." }, origin); return true;
    }
  }

  function stats() {
    const reported = db.posts.filter(x => !x.removed && (x.reports || []).length).length + db.comments.filter(c => !c.removed && (c.reports || []).length).length +
      media.allItems().filter(i => (i.reports || []).length).length;
    const vids = Object.values(db.uploads).filter(u => u.kind === "video");
    return { users: Object.values(db.users).filter(u => u.name).length, posts: db.posts.filter(x => !x.removed).length, photos: Object.keys(db.uploads).length - vids.length,
      videos: vids.length, videoMB: Math.round(vids.reduce((n, u) => n + (u.bytes || 0), 0) / 1048576), reported };
  }
  /** Likes, comments and views of a Media post (the cartoon maker learns from them). */
  function engagement(key) { const s = db.stats[key] || {}; return { likes: (s.likes || []).length, comments: s.comments || 0, views: s.views || 0 }; }
  /** A puppy cartoon someone made, posted on their own profile. */
  function postCartoon(req, text, video) {
    const uid = uidOf(req, false);
    if (!uid || !db.users[uid]) return { error: "Make your profile first (Media › 👤), then post your cartoon." };
    db.posts.push({ id: db.nextPost++, uid, at: Date.now(), text: String(text).slice(0, 900), photos: [], cartoon: video, link: null, likes: [], views: 0, comments: 0, reports: [] });
    save();
    return { ok: true };
  }
  // ---------- for Habit Chat (chat.js): who is this phone, and finding people by name or @name ----------
  /** This phone's profile id, or "" (no profile yet). */
  const who = req => uidOf(req, false);
  /** A person as chat shows them, or null (no profile, or banned). */
  function person(uid) {
    const u = db.users[uid];
    if (!u || !u.name || u.banned) return null;
    return { uid, name: newName(u.name), handle: u.handle || "", emoji: u.emoji || "🐾", avatar: fileUrl(u.avatar) };
  }
  const byHandle = h => { const x = String(h || "").toLowerCase().replace(/^@/, ""); const u = x && Object.values(db.users).find(v => v.handle === x); return u ? person(u.uid) : null; };
  /** People whose @name or name starts with / contains [q] (at most [n]); exact @name first. */
  function findPeople(q, me, n = 15, can = () => true) {
    const x = String(q || "").toLowerCase().replace(/^@/, "").trim();
    if (x.length < 2) return [];
    const out = [];
    for (const u of Object.values(db.users)) {
      if (u.uid === me || !u.name || u.banned || !can(u.uid)) continue;
      const h = u.handle || "", nm = u.name.toLowerCase();
      const s = h === x ? 3 : h.startsWith(x) ? 2 : nm.startsWith(x) || nm.includes(" " + x) ? 1.5 : h.includes(x) || nm.includes(x) ? 1 : 0;
      if (s) out.push([s, u.uid]);
    }
    return out.sort((a, b) => b[0] - a[0]).slice(0, n).map(([, uid]) => person(uid)).filter(Boolean);
  }

  // ---------- linked computers (Habit Chat on the web): one more device for the same person ----------
  /** A device's key: the salted hash of its device id (the same one used to know who a phone is). */
  const devKey = req => devHash(req);
  function linkDevice(dk, uid) { db.devices[dk] = uid; save(); }
  function unlinkDevice(dk) { if (db.devices[dk]) { delete db.devices[dk]; save(); } }

  return { handle, stats, engagement, postCartoon, who, person, byHandle, findPeople, devKey, linkDevice, unlinkDevice };
};
