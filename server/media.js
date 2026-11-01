// BigD AI "Media" page: short Telugu posts in 9 sections (news, movies, kids, crypto, real estate, jobs,
// devotional, farming, health). The AI searches the internet and writes each post in its own words with a
// link to the real source, and adds official YouTube videos when it finds them (each link is checked).
// It fills each section once (MEDIA_SEED posts) and then adds a few new posts every morning.
// The BigD team (team password) can also write, remove and refresh posts.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

module.exports = function makeMedia({ DATA_DIR, API_BASE, API_KEY, MODEL, adminPassword, h }) {
  const { send, readBody, allowed, clientIp } = h;
  const reads = new Map(); // reading posts has its own, bigger limit (it costs nothing)
  setInterval(() => { const now = Date.now(); for (const [k, l] of reads) if (!l.some(t => now - t < 600000)) reads.delete(k); }, 600000).unref();
  const FILE = path.join(DATA_DIR, "media.json");
  const AUTO = String(process.env.MEDIA_AUTO || "true") !== "false";
  const SEED = Number(process.env.MEDIA_SEED || 12);
  const MAX_CALLS_DAY = Number(process.env.MEDIA_MAX_CALLS_DAY || 40);
  const MEDIA_MODEL = process.env.MEDIA_MODEL || MODEL;
  const YT_OEMBED = process.env.YT_OEMBED_BASE || "https://www.youtube.com";
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
  const istNow = () => new Date(Date.now() + 5.5 * 3600e3);
  const today = () => istNow().toISOString().slice(0, 10);

  // section -> Telugu name, English name, how many searches per call, posts per morning, what to write
  const CATS = {
    news: { te: "వార్తలు", en: "News", search: 3, daily: 3, keepDays: 30,
      brief: "Today's important news for Telugu people: Andhra Pradesh and Telangana first, then India and the world. Only real news from the last two days. Neutral and factual; no political attacks." },
    movies: { te: "సినిమా", en: "Movie reviews", search: 3, daily: 1, keepDays: 120,
      brief: "Telugu movies first (and big Indian films): new releases, what critics say about them (a short summary of published reviews and the overall verdict, naming the publications), box office and official trailers. Never write your own made-up review or opinion." },
    kids: { te: "పిల్లలు", en: "Kids", search: 0, daily: 1, keepDays: 365,
      brief: "Short moral stories, simple rhymes, riddles with answers and fun facts for children aged 4 to 10, for parents to read aloud. Original, gentle and happy; nothing scary." },
    crypto: { te: "క్రిప్టో", en: "Crypto", search: 2, daily: 1, keepDays: 60,
      brief: "Crypto news (Bitcoin, Ethereum, Indian rules and tax on crypto) and simple explanations of how things work. Warn about crypto scams. Never tell people to buy or sell, and no price predictions." },
    realestate: { te: "రియల్ ఎస్టేట్", en: "Real estate", search: 2, daily: 1, keepDays: 90,
      brief: "Real estate news for Andhra Pradesh and Telangana (Amaravati, Visakhapatnam, Vijayawada, Tirupati, Hyderabad…), RERA rules, home loans, buying tips and land/plot fraud warnings. No investment advice." },
    jobs: { te: "ఉద్యోగాలు", en: "Jobs", search: 3, daily: 2, keepDays: 45,
      brief: "Real, recent job notifications useful for Telugu people: APPSC, TGPSC, SSC, Railways (RRB), banks (IBPS, SBI), police, DSC/TET, central government and big private hiring drives. Say who can apply, the last date and the official website. Only notifications you found." },
    devotional: { te: "భక్తి", en: "Devotional", search: 1, daily: 1, keepDays: 365,
      brief: "Upcoming festivals and their meaning, famous temples of Andhra Pradesh and Telangana (Tirumala, Srisailam, Simhachalam, Yadadri, Bhadrachalam…), simple devotional stories and good thoughts. Respectful to every faith." },
    farming: { te: "వ్యవసాయం", en: "Farming", search: 2, daily: 1, keepDays: 120,
      brief: "Useful farming tips for this season in Andhra Pradesh and Telangana, crop and market price news, government schemes for farmers (PM-KISAN, state schemes, crop insurance) and weather advice for farmers." },
    health: { te: "ఆరోగ్యం", en: "Health", search: 0, daily: 1, keepDays: 365,
      brief: "Simple, trusted health tips for families (water, food, exercise, sleep, BP, sugar, children's and elders' health, seasonal illnesses). Always add to see a doctor for real problems. No miracle cures, no medicine doses." }
  };

  // ---------- storage ----------
  let db = { next: 1, items: [], calls: { day: "", n: 0 }, lastDaily: "", seedTries2: { day: "" } };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  let saveTimer = null;
  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => {
      try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("Media save error:", e.message); }
    }, 300);
  }
  const SALT = (() => {
    const f = path.join(DATA_DIR, "media-salt.txt");
    try { return fs.readFileSync(f, "utf8").trim(); } catch (e) { const s = crypto.randomBytes(16).toString("hex"); try { fs.writeFileSync(f, s); } catch (e2) { } return s; }
  })();
  const me = req => crypto.createHash("sha256").update(SALT + "|" + (String(req.headers["x-device"] || "").replace(/[^\w-]/g, "").slice(0, 64) || clientIp(req))).digest("hex").slice(0, 24);
  function isAdmin(req) {
    const given = String(req.headers["x-admin-key"] || ""), pw = adminPassword();
    if (!pw || !given) return false;
    return crypto.timingSafeEqual(crypto.createHash("sha256").update(given).digest(), crypto.createHash("sha256").update(pw).digest());
  }

  function prune() {
    const now = Date.now();
    for (const [key, c] of Object.entries(CATS)) {
      const mine = db.items.filter(i => i.cat === key && i.by === "auto").sort((a, b) => b.at - a.at);
      const drop = new Set(mine.filter((i, n) => n >= 150 || now - i.at > c.keepDays * 86400e3).map(i => i.id));
      if (drop.size) db.items = db.items.filter(i => !drop.has(i.id));
    }
  }

  // ---------- checking links ----------
  const ytId = u => { const m = String(u || "").match(/(?:youtube\.com\/(?:watch\?(?:.*&)?v=|shorts\/|embed\/)|youtu\.be\/)([\w-]{11})/); return m ? m[1] : ""; };
  async function checkVideo(id) {
    try {
      const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 8000);
      const r = await fetch(`${YT_OEMBED}/oembed?format=json&url=${encodeURIComponent("https://www.youtube.com/watch?v=" + id)}`, { signal: ctl.signal });
      clearTimeout(t);
      if (!r.ok) return null;
      const j = await r.json();
      return { id, title: str(j.title, 150), ch: str(j.author_name, 80) };
    } catch (e) { return null; }
  }
  const host = u => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch (e) { return ""; } };
  /** Opens a link the way a phone browser would. Returns the HTTP status (0 = it didn't open). */
  async function pageStatus(u) {
    try {
      const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 9000);
      const r = await fetch(u, { redirect: "follow", signal: ctl.signal, headers: {
        "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36",
        "Accept": "text/html,application/xhtml+xml", "Accept-Language": "te-IN,te;q=0.9,en-IN;q=0.8" } });
      clearTimeout(t);
      try { if (r.body) await r.body.cancel(); } catch (e) { }
      return r.status;
    } catch (e) { return 0; }
  }
  /** The same page, ignoring "#…", tracking codes and a last "/". */
  const norm = u => { try { const x = new URL(u); x.hash = ""; [...x.searchParams.keys()].filter(k => /^utm_|^fbclid$|^gclid$/.test(k)).forEach(k => x.searchParams.delete(k)); return x.toString().replace(/\/$/, ""); } catch (e) { return ""; } };

  // ---------- asking Claude (with web search) ----------
  async function claude(system, user, searches) {
    const body = { model: MEDIA_MODEL, max_tokens: 9000, system, messages: [{ role: "user", content: user }] };
    if (searches > 0) body.tools = [{ type: "web_search_20250305", name: "web_search", max_uses: searches, user_location: { type: "approximate", country: "IN", timezone: "Asia/Kolkata" } }];
    const urls = new Set(); let data = null;
    for (let round = 0; round < 3; round++) {
      const r = await fetch(API_BASE + "/v1/messages", { method: "POST",
        headers: { "x-api-key": API_KEY, "anthropic-version": "2023-06-01", "content-type": "application/json" }, body: JSON.stringify(body) });
      data = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error((data.error && data.error.message) || "api_error " + r.status);
      for (const c of data.content || []) {
        if (c.type === "web_search_tool_result" && Array.isArray(c.content)) c.content.forEach(x => x && x.url && urls.add(x.url));
        for (const cit of c.citations || []) if (cit.url) urls.add(cit.url);
      }
      if (data.stop_reason !== "pause_turn") break;
      body.messages = body.messages.concat([{ role: "assistant", content: data.content }]);
    }
    const content = data.content || [];
    let last = -1; content.forEach((c, i) => { if (c.type === "web_search_tool_result") last = i; });
    let text = content.slice(last + 1).filter(c => c.type === "text").map(c => c.text).join("").trim();
    if (!text.includes("{")) text = content.filter(c => c.type === "text").map(c => c.text).join("").trim();
    text = text.replace(/```(?:json)?/gi, "");
    let obj = null;
    try { obj = JSON.parse(text); } catch (e) { const a = text.indexOf("{"), b = text.lastIndexOf("}"); if (a >= 0 && b > a) { try { obj = JSON.parse(text.slice(a, b + 1)); } catch (e2) { } } }
    return { obj, urls };
  }

  function prompt(key, n, recent) {
    const c = CATS[key];
    const date = istNow().toISOString().slice(0, 10);
    return `You write posts for the "Media" page of BigD AI, a Telugu app used by ordinary people in Andhra Pradesh and Telangana, including elders.
Section: ${c.en} (${c.te}). ${c.brief}
Today's date: ${date}.
Write ${n} different posts in simple, everyday Telugu (Telugu script; English only for names, numbers and words people normally say in English).
${c.search ? `Search the internet for real, recent information first. Every post must be based on a page you found, written completely in your own words (never copy sentences). Put that page's URL in "url" and the website's name in "source". If you find an official YouTube video that really belongs with a post (an official trailer, or a news channel's own video), put its full https://www.youtube.com/watch?v=... link in "youtube"; only links you actually saw, otherwise leave it empty.`
  : `Write original content, not copied from anywhere. Leave "url", "source" and "youtube" empty.`}
Be accurate: never invent facts, dates, numbers, names, quotes or links. Nothing hateful, political attacks, adult or unsafe content.
Do not repeat these recent posts: ${recent.length ? recent.join(" | ") : "(none yet)"}
Reply with only a JSON object:
{"posts":[{"title":"<Telugu title, at most 12 words>","body":"<Telugu text, 50 to 110 words>","emoji":"<one emoji that fits>","source":"<website name or empty>","url":"<https link or empty>","youtube":"<YouTube link or empty>"}]}`;
  }

  async function generate(key, n) {
    const c = CATS[key];
    if (!c || !API_KEY) return 0;
    if (db.calls.day !== today()) db.calls = { day: today(), n: 0 };
    if (db.calls.n >= MAX_CALLS_DAY) return 0;
    db.calls.n++; save();
    const recent = db.items.filter(i => i.cat === key).sort((a, b) => b.at - a.at).slice(0, 25).map(i => i.title);
    const { obj, urls } = await claude(prompt(key, n, recent), `Write ${n} new ${c.en} posts now.`, c.search);
    const key2 = u => { const n0 = norm(u); try { return decodeURIComponent(n0).toLowerCase(); } catch (e) { return n0.toLowerCase(); } };
    const found = new Set([...urls].map(key2));
    const foundHosts = new Set([...urls].map(host));
    const why = { link: 0, repeat: 0, short: 0 }; let example = "";
    const seenTitles = new Set(db.items.filter(i => i.cat === key).map(i => i.title.replace(/\s+/g, "")));
    const seenUrls = new Set(db.items.filter(i => i.url).map(i => i.url));
    let added = 0;
    const posts = obj && Array.isArray(obj.posts) ? obj.posts : [];
    for (const p of posts.slice(0, n + 2)) {
      const title = str(p && p.title, 140), body = str(p && p.body, 1400);
      if (title.length < 4 || body.length < 30) { why.short++; continue; }
      if (seenTitles.has(title.replace(/\s+/g, ""))) { why.repeat++; continue; }
      let url = str(p.url, 500); let source = str(p.source, 60);
      if (c.search) {
        // keep only real pages: one the search found, or one that really opens (no made-up links)
        let ok = /^https?:\/\//.test(url) && found.has(key2(url));
        if (!ok && /^https?:\/\//.test(url)) {
          const st = await pageStatus(url);
          ok = (st >= 200 && st < 400) || (foundHosts.has(host(url)) && [401, 403, 429].includes(st));
        }
        if (!ok) { why.link++; if (!example) example = url || "(no link)"; continue; }
        url = norm(url) || url;   // without tracking codes
        if (seenUrls.has(url)) { why.repeat++; continue; }
        if (!source) source = host(url);
      } else { url = ""; source = ""; }
      let yt = null;
      const vid = ytId(p.youtube);
      if (vid && c.search) yt = await checkVideo(vid);
      const item = { id: db.next++, cat: key, at: Date.now() - added * 1000, title, body, emoji: str(p.emoji, 8), source, url, yt, by: "auto", likes: [] };
      db.items.push(item); seenTitles.add(title.replace(/\s+/g, "")); if (url) seenUrls.add(url);
      added++;
      if (added >= n) break;
    }
    prune(); save();
    console.log(`Media: added ${added} ${key} post(s)` + (c.search ? ` · AI wrote ${posts.length}, searched ${urls.size} pages` : ` · AI wrote ${posts.length}`) +
      (why.link || why.repeat || why.short ? ` · skipped: ${why.link} bad link, ${why.repeat} repeat, ${why.short} too short` : "") + (example ? ` · e.g. ${example.slice(0, 120)}` : ""));
    return added;
  }

  // ---------- a simple queue, one AI call at a time ----------
  const queue = [];
  let running = false;
  function enqueue(key, n) { if (CATS[key] && !queue.some(q => q.key === key)) queue.push({ key, n }); pump(); }
  async function pump() {
    if (running) return;
    running = true;
    while (queue.length) {
      const job = queue.shift();
      try { await generate(job.key, job.n); } catch (e) { console.error("Media generate error:", job.key, e.message); }
      await new Promise(r => setTimeout(r, 15000));
    }
    running = false;
  }

  /** Fill empty sections, and add the morning posts once a day after 6 AM (India time). */
  function tick() {
    if (!AUTO || !API_KEY) return;
    if (!db.seedTries2 || db.seedTries2.day !== today()) db.seedTries2 = { day: today() };
    const tries = db.seedTries2;
    for (const [key, c] of Object.entries(CATS)) {
      const have = db.items.filter(i => i.cat === key).length;
      // fill a section that has few posts (at most 3 tries a day, so a hard topic can't use up the credit)
      if (have < SEED && (tries[key] || 0) < 3 && !queue.some(q => q.key === key)) {
        tries[key] = (tries[key] || 0) + 1; save();
        enqueue(key, Math.min(c.search ? 5 : 6, SEED - have));
      }
    }
    if (db.lastDaily !== today() && istNow().getUTCHours() >= 6) {
      db.lastDaily = today(); save();
      for (const [key, c] of Object.entries(CATS)) enqueue(key, c.daily);
    }
  }
  setTimeout(tick, 60_000);
  setInterval(tick, 10 * 60_000).unref();

  // ---------- what the app sees ----------
  function view(i, dev) {
    return { id: i.id, cat: i.cat, at: i.at, title: i.title, body: i.body, emoji: i.emoji || "", source: i.source || "", url: i.url || "",
      yt: i.yt || null, by: i.by, likes: (i.likes || []).length, liked: (i.likes || []).includes(dev) };
  }

  function list(url, req) {
    const cat = url.searchParams.get("cat") || "all";
    const before = Number(url.searchParams.get("before")) || Infinity;
    const limit = Math.min(40, Number(url.searchParams.get("limit")) || 20);
    const pool = db.items.filter(i => (cat === "all" || i.cat === cat) && i.id < before).sort((a, b) => b.id - a.id);
    const dev = me(req);
    const cats = Object.entries(CATS).map(([key, c]) => ({ key, te: c.te, en: c.en, count: db.items.filter(i => i.cat === key).length }));
    return { items: pool.slice(0, limit).map(i => view(i, dev)), more: pool.length > limit, cats, admin: isAdmin(req) };
  }

  function stats() { return { posts: db.items.length, aiCallsToday: db.calls.day === today() ? db.calls.n : 0, waiting: queue.length }; }

  // ---------- router ----------
  async function handle(req, res, url, origin) {
    const p = url.pathname;
    if (!p.startsWith("/api/media")) return false;
    if (req.method === "GET" && p === "/api/media") {
      if (!allowed("m:" + clientIp(req), reads, 400)) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
      send(res, 200, list(url, req), origin); return true;
    }
    if (!allowed(clientIp(req))) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
    if (req.method !== "POST") { send(res, 404, { error: "Not found" }, origin); return true; }
    let body;
    try { body = await readBody(req, 64 * 1024); } catch (e) { send(res, 400, { error: "Bad request." }, origin); return true; }
    const item = db.items.find(i => i.id === Number(body.id));
    if (p === "/api/media/like") {
      if (!item) { send(res, 404, { error: "That post is not there any more." }, origin); return true; }
      const d = me(req); item.likes = item.likes || [];
      const k = item.likes.indexOf(d); if (k >= 0) item.likes.splice(k, 1); else item.likes.push(d);
      save(); send(res, 200, { likes: item.likes.length, liked: k < 0 }, origin); return true;
    }
    if (!isAdmin(req)) { send(res, 401, { error: "Only the BigD team can do this (team password)." }, origin); return true; }
    if (p === "/api/media/post") {
      const cat = CATS[body.cat] ? body.cat : "news";
      const title = str(body.title, 140), text = str(body.body, 3000);
      if (title.length < 3 || text.length < 10) { send(res, 400, { error: "Write a title and some text." }, origin); return true; }
      const link = /^https?:\/\//.test(str(body.url, 500)) ? str(body.url, 500) : "";
      const vid = ytId(body.youtube);
      const it = { id: db.next++, cat, at: Date.now(), title, body: text, emoji: str(body.emoji, 8), source: link ? host(link) : "", url: link,
        yt: vid ? (await checkVideo(vid)) || { id: vid, title: "", ch: "" } : null, by: "team", likes: [] };
      db.items.push(it); save();
      send(res, 200, { item: view(it, me(req)) }, origin); return true;
    }
    if (p === "/api/media/delete") {
      if (!item) { send(res, 404, { error: "Not found" }, origin); return true; }
      db.items = db.items.filter(i => i !== item); save(); send(res, 200, { ok: true }, origin); return true;
    }
    if (p === "/api/media/generate") {
      if (!API_KEY) { send(res, 500, { error: "Server has no API key yet." }, origin); return true; }
      const keys = body.cat && CATS[body.cat] ? [body.cat] : Object.keys(CATS);
      keys.forEach(k => enqueue(k, Math.max(1, Math.min(6, Number(body.count) || CATS[k].daily + 1))));
      send(res, 200, { queued: keys.length, ...stats() }, origin); return true;
    }
    send(res, 404, { error: "Not found" }, origin); return true;
  }

  return { handle, stats, generate, CATS };
};
