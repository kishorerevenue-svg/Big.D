// Habit channels (app: Chats › Updates › Channels) and 🔥 Trending (app: Tasks › 🔥 Trending, like Twitter).
//
// Channels are made by our side only (the Habit team): nobody else can post in them.
//  - 🛡 Fake message alerts: the people's fake messages list (fakes.js); the app opens that list.
//  - 📰 Habit News, 🏛 Govt schemes, 🌾 Farmers' corner, 💡 Daily tips: one short post a day each, written by Claude
//    (news and schemes look on the web first, so they are about today), always marked 🤖 AI-made.
//    The team can also post by hand from the Teach page, and remove any post.
//  - 🎬 Habit Videos: our own Habit Studio videos, once the owner approved them.
//
// 🔥 Trending: today's kind Google Trends searches (India), each explained in one or two plain lines from its news
// headlines, from @HabitTrending. Under them anyone with a Habit profile can post a short line (280 letters) about
// what's happening; each post is checked before it shows (bad words, numbers, links, hate, fake news), and people
// can ❤️ like and 🚩 report (3 reports hide a post).
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

module.exports = function makeChannels({ DATA_DIR, learn, MODEL, social, trends, media, fakes, adminPassword, h }) {
  const { send, readBody, allowed, clientIp } = h;
  const FILE = path.join(DATA_DIR, "channels.json");
  const CHANNELS = [
    { id: "fake", emoji: "🛡", en: "Fake message alerts", te: "మోసం మెసేజ్ హెచ్చరికలు", about: { en: "Fraud messages people got, so you can spot them", te: "జనాలకు వచ్చిన మోసం మెసేజ్‌లు — మీరు గుర్తుపట్టడానికి" } },
    { id: "news", emoji: "📰", en: "Habit News", te: "Habit వార్తలు", hour: 8, search: true,
      about: { en: "Today's important news in a few simple lines", te: "ఈరోజు ముఖ్యమైన వార్తలు, కొన్ని సులువైన లైన్లలో" },
      ask: "the 3 or 4 most useful news items for Andhra Pradesh and Telangana families today (weather warnings, prices, exams and jobs, schemes, sports, science). Neutral, no political opinions, no crime or accident details" },
    { id: "govt", emoji: "🏛", en: "Govt schemes", te: "ప్రభుత్వ పథకాలు", hour: 10, search: true,
      about: { en: "Schemes, last dates and how to apply", te: "పథకాలు, చివరి తేదీలు, ఎలా అప్లై చేయాలి" },
      ask: "one government scheme or service (central, Andhra Pradesh or Telangana) that is open or in the news now: who can get it, what they get, the documents, how and where to apply, and the last date if there is one. Only facts you found on official or trusted pages" },
    { id: "farm", emoji: "🌾", en: "Farmers' corner", te: "రైతు మిత్ర", hour: 7, search: false,
      about: { en: "Crop care for this season, in simple Telugu", te: "ఈ కాలం పంటల జాగ్రత్తలు, సులువైన తెలుగులో" },
      ask: "one practical tip for farmers in Andhra Pradesh and Telangana for this month's season (paddy, cotton, chilli, groundnut, vegetables, cattle): what to watch for and what to do. Safe, standard advice from agriculture departments; tell them to ask the Rythu Bharosa Kendram / agriculture officer before using any pesticide" },
    { id: "tips", emoji: "💡", en: "Daily tips", te: "రోజువారీ చిట్కాలు", hour: 9, search: false,
      about: { en: "Health, money, phone safety and home tips", te: "ఆరోగ్యం, డబ్బు, ఫోన్ భద్రత, ఇంటి చిట్కాలు" },
      ask: "one useful everyday tip for a family (rotate between: health habits, saving money, phone and UPI safety, kitchen and home, children's study, elders' care). Simple and safe; for health, general advice only and 'see a doctor' when needed" },
    { id: "studio", emoji: "🎬", en: "Habit Videos", te: "Habit వీడియోలు", about: { en: "Our own short Telugu videos", te: "మా సొంత చిన్న తెలుగు వీడియోలు" } },
  ];
  const WRITERS = CHANNELS.filter(c => c.ask);
  const KEEP_POSTS = 60, KEEP_TWEETS = 400, POST_LEN = 280, POSTS_PER_DAY = 10, HIDE_AFTER = 3;

  let db = { posts: {}, topics: [], tweets: [], tried: {} };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  let timer = null;
  const save = () => { clearTimeout(timer); timer = setTimeout(() => { try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("Channels save error:", e.message); } }, 300); };
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
  const ist = () => new Date(Date.now() + 5.5 * 3600e3);
  const today = () => ist().toISOString().slice(0, 10);
  const id = () => crypto.randomBytes(5).toString("hex");
  const reads = new Map(), counts = new Map();
  setInterval(() => { const now = Date.now(); for (const [k, l] of reads) if (!l.some(t => now - t < 600000)) reads.delete(k); }, 600000).unref();
  function isAdmin(req) {
    const given = String(req.headers["x-admin-key"] || req.headers["x-teach-key"] || ""), pw = adminPassword();
    if (!pw || !given) return false;
    return crypto.timingSafeEqual(crypto.createHash("sha256").update(given).digest(), crypto.createHash("sha256").update(pw).digest());
  }
  function parse(t) {
    t = String(t || ""); const a = t.search(/[[{]/), b = Math.max(t.lastIndexOf("}"), t.lastIndexOf("]"));
    if (a < 0 || b <= a) return null;
    try { return JSON.parse(t.slice(a, b + 1)); } catch (e) { return null; }
  }
  const claudeUp = () => learn && !(learn.claudeOut && learn.claudeOut());

  // ---------- the channel posts ----------
  function addPost(ch, p) {
    const list = db.posts[ch] = db.posts[ch] || [];
    const post = { id: id(), at: Date.now(), te: str(p.te, 1500), en: str(p.en, 1500), by: p.by || "team", ai: !!p.ai };
    if (!post.te && !post.en) return null;
    list.push(post);
    if (list.length > KEEP_POSTS) db.posts[ch] = list.slice(-KEEP_POSTS);
    save();
    return post;
  }
  const WRITE = `You write one short post for a channel in Habit AI, a Telugu family app (all ages, many are not used to phones).
Write simple spoken Telugu (Telugu script; common English words like UPI, OTP, online are fine), warm and clear. 4 to 7 short lines, a fitting emoji or two, no hashtags, no links, no phone numbers.
Never make up facts, dates, amounts or names: if you are not sure, leave it out. No political opinions. No fear-mongering.
Reply with only JSON: {"te":"<the post in Telugu>","en":"<the same post in simple English>"}`;
  async function write(c) {
    const recent = (db.posts[c.id] || []).slice(-6).map(p => "- " + (p.en || "").slice(0, 120)).join("\n");
    const prompt = `Today is ${ist().toDateString()} (India). Channel: ${c.emoji} ${c.en}.\nWrite about ${c.ask}.\n` +
      (recent ? `Recent posts (don't repeat these):\n${recent}\n` : "");
    const out = await learn.callClaude({ system: WRITE, messages: [{ role: "user", content: prompt }], maxTokens: 2500, search: !!c.search, model: MODEL });
    const j = parse(out.text);
    if (!j || !(j.te || j.en)) throw new Error("the post came out incomplete");
    const clean = s => str(s, 1500).replace(/https?:\/\/\S+/g, "").replace(/\[\d+\]/g, "").trim();
    return addPost(c.id, { te: clean(j.te), en: clean(j.en), by: "claude", ai: true });
  }

  // ---------- 🔥 Trending ----------
  const EXPLAIN = `You explain today's trending Google searches in India for a Telugu family app's 🔥 Trending feed (like Twitter).
For each search, write what it is about in 1 or 2 short plain sentences, ONLY from the news headlines given (never add facts that are not in them). Neutral: no opinions, no politics sides, no gossip, no crime details.
Skip a search if its headlines are about death, crime, violence, accidents, or politics fights: leave it out of the list.
Reply with only JSON: [{"title":"<the search>","te":"<simple Telugu, 1-2 sentences>","en":"<simple English, 1-2 sentences>","emoji":"<one emoji>"}]`;
  async function refreshTopics() {
    const now = trends && trends.kindNow ? trends.kindNow() : [];
    const have = new Set(db.topics.filter(t => Date.now() - t.at < 24 * 3600e3).map(t => t.title.toLowerCase()));
    const fresh = now.filter(i => !have.has(i.title.toLowerCase())).slice(0, 8);
    if (!fresh.length) return 0;
    const prompt = fresh.map(i => `Search: ${i.title}\nHeadlines: ${(i.news || []).join(" | ") || "(none)"}`).join("\n\n");
    const out = await learn.callClaude({ system: EXPLAIN, messages: [{ role: "user", content: prompt }], maxTokens: 3000, search: false, model: MODEL });
    const list = parse(out.text);
    if (!Array.isArray(list)) throw new Error("the trending notes came out incomplete");
    let n = 0;
    for (const x of list) {
      const title = str(x.title, 100);
      if (!title || !fresh.some(f => f.title.toLowerCase() === title.toLowerCase())) continue;
      db.topics.push({ id: id(), at: Date.now(), title, te: str(x.te, 400), en: str(x.en, 400), emoji: str(x.emoji, 8) || "🔥", likes: [] });
      n++;
    }
    db.topics = db.topics.filter(t => Date.now() - t.at < 3 * 86400e3).slice(-60);
    save();
    return n;
  }

  // ---------- the clock: one post a day per channel, trending every 3 hours ----------
  let busy = false;
  async function tick() {
    if (busy || !claudeUp()) return;
    busy = true;
    try {
      const h = ist().getUTCHours(), d = today();
      for (const c of WRITERS) {
        if (h < c.hour || h >= 21) continue;
        const last = (db.posts[c.id] || []).slice(-1)[0];
        if (last && new Date(last.at + 5.5 * 3600e3).toISOString().slice(0, 10) === d) continue;
        const k = c.id + "|" + d;
        if ((db.tried[k] || 0) >= 2) continue;               // at most 2 tries a day each
        db.tried[k] = (db.tried[k] || 0) + 1; save();
        try { await write(c); } catch (e) { console.error("Channel " + c.id + ":", String(e.message || e).slice(0, 160)); }
      }
      for (const k of Object.keys(db.tried)) if (!k.endsWith(d)) delete db.tried[k];
      if (h >= 7 && h < 22 && Date.now() - (db.topicsAt || 0) > 3 * 3600e3) {
        db.topicsAt = Date.now(); save();
        try { await refreshTopics(); } catch (e) { console.error("Trending:", String(e.message || e).slice(0, 160)); }
      }
    } finally { busy = false; }
  }
  setTimeout(() => tick().catch(() => { }), 90e3).unref();
  setInterval(() => tick().catch(() => { }), 20 * 60e3).unref();

  // ---------- what the app sees ----------
  function studioPosts() {
    try {
      return media.allItems().filter(i => i.kind === "cartoon" && i.video && i.video.url).sort((a, b) => b.at - a.at).slice(0, 30)
        .map(i => ({ id: "m" + i.id, at: i.at, te: i.title, en: i.title, ai: true, video: i.video.url, poster: i.video.poster || "", mediaId: i.id }));
    } catch (e) { return []; }
  }
  function postsOf(ch) {
    if (ch === "studio") return studioPosts();
    if (ch === "fake") return [];
    return (db.posts[ch] || []).slice().reverse();
  }
  function list() {
    return CHANNELS.map(c => {
      const last = c.id === "fake" ? null : postsOf(c.id)[0] || null;
      return { id: c.id, emoji: c.emoji, en: c.en, te: c.te, about: c.about, last: last ? { at: last.at, te: last.te.slice(0, 120), en: last.en.slice(0, 120) } : null };
    });
  }
  const tweetView = (t, uid, admin) => ({ id: t.id, at: t.at, text: t.text, name: t.name, emoji: t.emoji, handle: t.handle || "", likes: (t.likes || []).length,
    liked: !!uid && (t.likes || []).includes(uid), mine: !!uid && t.uid === uid, hidden: admin ? !!t.hidden : undefined });
  const topicView = (t, uid) => ({ id: t.id, at: t.at, title: t.title, te: t.te, en: t.en, emoji: t.emoji, likes: (t.likes || []).length, liked: !!uid && (t.likes || []).includes(uid) });
  function within(uid) {
    const c = counts.get(uid), d = today();
    if (c && c.day === d && c.n >= POSTS_PER_DAY) return "That's enough posts for today. Come back tomorrow!";
    if (c && Date.now() - c.last < 30_000) return "Please wait a few seconds.";
    counts.set(uid, { day: d, n: (c && c.day === d ? c.n : 0) + 1, last: Date.now() });
    return "";
  }

  // ---------- router ----------
  const handle = async function handle(req, res, url, origin) {
    const p = url.pathname;
    if (!p.startsWith("/api/channels") && !p.startsWith("/api/trending")) return false;
    const uid = social.who(req) || "";
    const admin = isAdmin(req);
    try {
      if (req.method === "GET") {
        if (!allowed("ch:" + clientIp(req), reads, 300)) { send(res, 429, { error: "Too many requests." }, origin); return true; }
        if (p === "/api/channels") { send(res, 200, { channels: list(), admin }, origin); return true; }
        if (p === "/api/channels/posts") {
          const c = CHANNELS.find(x => x.id === url.searchParams.get("ch"));
          if (!c) { send(res, 404, { error: "No such channel." }, origin); return true; }
          send(res, 200, { channel: { id: c.id, emoji: c.emoji, en: c.en, te: c.te, about: c.about }, posts: postsOf(c.id), admin }, origin); return true;
        }
        if (p === "/api/trending") {
          const topics = db.topics.filter(t => Date.now() - t.at < 36 * 3600e3).slice().reverse().slice(0, 12).map(t => topicView(t, uid));
          const from = Math.max(0, Number(url.searchParams.get("from")) || 0);
          const all = db.tweets.filter(t => !t.removed && (admin || !t.hidden)).slice().reverse();
          send(res, 200, { topics, posts: all.slice(from, from + 30).map(t => tweetView(t, uid, admin)), more: all.length > from + 30, admin, canPost: !!(uid && social.person(uid)) }, origin);
          return true;
        }
        send(res, 404, { error: "Not found" }, origin); return true;
      }
      if (req.method !== "POST") { send(res, 404, { error: "Not found" }, origin); return true; }
      if (!allowed(clientIp(req))) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
      let body;
      try { body = await readBody(req, 32 * 1024); } catch (e) { send(res, 400, { error: "Bad request." }, origin); return true; }

      // the team: post by hand, remove a post, write a channel's post now
      if (p === "/api/channels/post" || p === "/api/channels/remove" || p === "/api/channels/write") {
        if (!admin) { send(res, 403, { error: "Only the Habit team can post in channels." }, origin); return true; }
        const c = CHANNELS.find(x => x.id === body.ch && x.id !== "fake" && x.id !== "studio");
        if (!c) { send(res, 400, { error: "That channel takes no posts from here." }, origin); return true; }
        if (p === "/api/channels/remove") { db.posts[c.id] = (db.posts[c.id] || []).filter(x => x.id !== body.id); save(); send(res, 200, { ok: true }, origin); return true; }
        if (p === "/api/channels/write") {
          if (!claudeUp() || !c.ask) { send(res, 503, { error: "Claude is resting now. Try later." }, origin); return true; }
          const post = await write(c); send(res, 200, { post }, origin); return true;
        }
        const post = addPost(c.id, { te: body.te, en: body.en || body.te, by: "team" });
        if (!post) { send(res, 400, { error: "Write the post first." }, origin); return true; }
        send(res, 200, { post }, origin); return true;
      }

      if (!uid && !admin) { send(res, 403, { error: "Make your Habit profile first: Chats › 💬 Habit Chat.", needProfile: true }, origin); return true; }
      if (p === "/api/trending/post") {
        const me = social.person(uid);
        if (!me) { send(res, 403, { error: "Make your Habit profile first: Chats › 💬 Habit Chat.", needProfile: true }, origin); return true; }
        const text = str(body.text, POST_LEN).replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n");
        const lim = within(uid);
        if (lim) { send(res, 429, { error: lim }, origin); return true; }
        const bad = fakes && fakes.checkPost ? await fakes.checkPost(text) : (text.length < 2 ? "Write a little more." : "");
        if (bad) { send(res, 400, { error: bad }, origin); return true; }
        const t = { id: id(), uid, name: me.name, emoji: me.emoji, handle: me.handle, text, at: Date.now(), likes: [], reports: [] };
        db.tweets.push(t);
        if (db.tweets.length > KEEP_TWEETS) db.tweets = db.tweets.slice(-KEEP_TWEETS);
        save();
        send(res, 200, { post: tweetView(t, uid, admin) }, origin); return true;
      }
      const t = db.tweets.find(x => x.id === body.id && !x.removed) || db.topics.find(x => x.id === body.id);
      if (!t) { send(res, 404, { error: "That post is not there any more." }, origin); return true; }
      if (p === "/api/trending/like") {
        t.likes = t.likes || [];
        const k = t.likes.indexOf(uid || "team");
        if (k >= 0) t.likes.splice(k, 1); else t.likes.push(uid || "team");
        save(); send(res, 200, { likes: t.likes.length, liked: k < 0 }, origin); return true;
      }
      if (p === "/api/trending/report") {
        t.reports = t.reports || [];
        if (uid && !t.reports.includes(uid)) t.reports.push(uid);
        if (t.reports.length >= HIDE_AFTER || admin) t.hidden = true;
        save(); send(res, 200, { ok: true }, origin); return true;
      }
      if (p === "/api/trending/delete") {
        if (!(admin || (t.uid && t.uid === uid))) { send(res, 403, { error: "Only your own posts." }, origin); return true; }
        if (db.tweets.includes(t)) t.removed = true; else db.topics = db.topics.filter(x => x !== t);
        save(); send(res, 200, { ok: true }, origin); return true;
      }
      send(res, 404, { error: "Not found" }, origin); return true;
    } catch (e) {
      console.error("Channels error:", e.message);
      send(res, 500, { error: "Something went wrong. Please try again." }, origin); return true;
    }
  };

  function status() {
    return { channels: list().map(c => ({ id: c.id, en: c.en, posts: c.id === "studio" ? studioPosts().length : (db.posts[c.id] || []).length, last: c.last && c.last.at })),
      topics: db.topics.length, posts: db.tweets.filter(t => !t.removed).length, hidden: db.tweets.filter(t => t.hidden && !t.removed).length };
  }
  return { handle, tick, status, write: chId => write(CHANNELS.find(c => c.id === chId)), refreshTopics, CHANNELS };
};
