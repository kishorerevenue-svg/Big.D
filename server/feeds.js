// More Media posts, all day, from trusted sources: news feeds (newspapers' and TV channels' own feeds, Google News
// searches for each section, and any feed the owner adds on the Teach page). For each new story the server opens the
// real page, reads its first paragraphs, and OUR OWN BRAIN (the open model on this server) writes a short post in
// simple Telugu from those facts only, with a link to the real story. If the own brain isn't installed (or is too
// small to write well), Claude writes them instead, up to FEED_CLAUDE_DAILY short posts a day.
// Stories that many papers are reporting come first. Every post gets a mood and an issue, so Media can show
// "by mood", "by issue" and "popular now".
const fs = require("fs");
const path = require("path");
const { preview } = require("./preview.js");
const panchang = require("./panchang.js");

module.exports = function makeFeeds({ DATA_DIR, ownBrain, ai, MODEL, API_KEY, media, fetchImpl, decodeGoogle = true }) {
  const get = fetchImpl || fetch;
  const FILE = path.join(DATA_DIR, "feeds.json");
  let db = { added: {}, removed: {}, seen: {}, health: {}, claude: { day: "", n: 0 }, wrote: { own: 0, claude: 0 }, today: { day: "" } };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  let saveTimer = null;
  const save = () => { clearTimeout(saveTimer); saveTimer = setTimeout(() => { try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("Feeds save error:", e.message); } }, 300); };
  const ist = () => new Date(Date.now() + 5.5 * 3600e3);
  const today = () => ist().toISOString().slice(0, 10);
  const CLAUDE_DAILY = () => Number(process.env.FEED_CLAUDE_DAILY || 40);
  const UA = "Mozilla/5.0 (compatible; HabitAI-reader/1.0; +https://bigdai.tech)";
  const count = (what) => { if (db.today.day !== today()) db.today = { day: today() }; db.today[what] = (db.today[what] || 0) + 1; };

  // ---------- sources ----------
  const gn = (q, te) => ({ url: `https://news.google.com/rss/search?q=${encodeURIComponent(q + " when:1d")}&${te ? "hl=te&gl=IN&ceid=IN:te" : "hl=en-IN&gl=IN&ceid=IN:en"}`, name: "Google News · " + q });
  const site = (url, name) => ({ url, name });
  const hindu = (p, name) => site(`https://www.thehindu.com/${p}/feeder/default.rss`, "The Hindu · " + name);
  function defaults() {
    let masa = "";
    try { masa = panchang.panchang(today()).masa; } catch (e) { }
    return {
      news: [site("https://news.google.com/rss?hl=te&gl=IN&ceid=IN:te", "Google News · తెలుగు ముఖ్య వార్తలు"), gn("Andhra Pradesh"), gn("Telangana"),
        gn("ఆంధ్రప్రదేశ్", true), gn("తెలంగాణ", true), gn("India"), hindu("news/national/andhra-pradesh", "Andhra Pradesh"), hindu("news/national/telangana", "Telangana"),
        site("https://feeds.bbci.co.uk/telugu/rss.xml", "BBC News తెలుగు"), site("https://ntvtelugu.com/feed", "NTV Telugu"), site("https://10tv.in/latest/feed", "10TV")],
      movies: [gn("Telugu movie"), gn("Tollywood box office"), gn("టాలీవుడ్", true), gn("Telugu OTT release"), gn("Telugu movie trailer"),
        site("https://telugu.gulte.com/feed", "Gulte తెలుగు"), site("https://www.123telugu.com/feed", "123telugu")],
      farming: [gn("farmers Andhra Pradesh"), gn("Telangana farmers"), gn("రైతులు", true), gn("paddy procurement OR crop insurance OR PM-KISAN"), hindu("sci-tech/agriculture", "Agriculture")],
      health: [gn("health Andhra Pradesh OR Telangana"), gn("ఆరోగ్యం", true), gn("Aarogyasri OR NTR Vaidya Seva"), hindu("sci-tech/health", "Health")],
      devotional: [gn("Tirumala TTD"), gn("తిరుమల", true), gn("Srisailam OR Simhachalam OR Yadadri OR Bhadrachalam OR Kanaka Durga temple"), gn("ఆలయం", true),
        ...(masa ? [gn(masa + " మాసం", true)] : [])],
      jobs: [gn("APPSC OR TGPSC notification"), gn("recruitment notification apply online"), gn("ఉద్యోగ నోటిఫికేషన్", true), gn("SSC OR RRB OR IBPS OR SBI recruitment"),
        gn("DSC OR TET Andhra Pradesh OR Telangana")],
      realestate: [gn("Amaravati real estate"), gn("Hyderabad real estate"), gn("Visakhapatnam OR Vijayawada OR Tirupati real estate"), gn("RERA Andhra Pradesh OR Telangana"), gn("రియల్ ఎస్టేట్", true)],
      crypto: [gn("crypto India"), gn("Bitcoin"), gn("crypto scam India")],
      facts: [site(`https://api.wikimedia.org/feed/v1/wikipedia/en/featured/${today().replace(/-/g, "/")}`, "Wikipedia · today's article and on this day"), gn("ISRO"), gn("scientists discover")]
    };
  }
  /** The feeds of a section: the defaults the owner didn't remove, then the ones the owner added. */
  function sources(cat) {
    const removed = db.removed[cat] || [];
    return [...(defaults()[cat] || []).filter(s => !removed.includes(s.url)), ...(db.added[cat] || []).map(u => ({ url: u, name: hostOf(u), added: true }))];
  }
  const hostOf = u => { try { return new URL(u).hostname.replace(/^www\./, ""); } catch (e) { return ""; } };

  // ---------- reading feeds (RSS and Atom) ----------
  const decode = s => String(s || "").replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1").replace(/&amp;/g, "&").replace(/&quot;/g, "\"").replace(/&#0?39;|&apos;/g, "'")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&nbsp;/g, " ").replace(/&#(\d+);/g, (m, n) => { try { return String.fromCodePoint(Number(n)); } catch (e) { return ""; } })
    .replace(/&#x([0-9a-f]+);/gi, (m, x) => { try { return String.fromCodePoint(parseInt(x, 16)); } catch (e) { return ""; } });
  const strip = s => decode(decode(s).replace(/<[^>]+>/g, " ")).replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
  const tag = (x, t) => { const m = x.match(new RegExp(`<${t}\\b[^>]*>([\\s\\S]*?)<\\/${t}>`, "i")); return m ? m[1] : ""; };
  const attr = (x, t, a) => { const m = x.match(new RegExp(`<${t}\\b[^>]*?\\b${a}\\s*=\\s*["']([^"']+)["']`, "i")); return m ? decode(m[1]) : ""; };
  function parse(xml) {
    const out = [];
    for (const m of String(xml).matchAll(/<(item|entry)\b[\s\S]*?<\/\1>/gi)) {
      const x = m[0];
      const title = strip(tag(x, "title"));
      const link = (decode(tag(x, "link")).trim() || attr(x, "link", "href") || decode(tag(x, "guid")).trim()).replace(/\s+/g, "");
      const when = Date.parse(strip(tag(x, "pubDate") || tag(x, "published") || tag(x, "updated") || tag(x, "dc:date"))) || 0;
      let source = strip(tag(x, "source")), head = title;
      const dash = title.lastIndexOf(" - ");
      if (/news\.google\./.test(link) && dash > 0) { if (!source) source = title.slice(dash + 3); head = title.slice(0, dash); }   // "Headline - Publisher"
      let summary = strip(tag(x, "description") || tag(x, "summary") || tag(x, "content:encoded") || tag(x, "content") || tag(x, "media:description"));
      if (/news\.google\./.test(link)) summary = "";      // Google News puts only the headline and other papers' headlines here
      summary = summary.replace(title, "").replace(head, "").replace(/(?:The post|appeared first on)[\s\S]*$/i, "").trim();
      const img = attr(x, "media:content", "url") || attr(x, "media:thumbnail", "url") || attr(x, "enclosure", "url") ||
        ((tag(x, "description") + tag(x, "content:encoded")).match(/<img\b[^>]*\bsrc=["'](https:[^"']+)["']/i) || [])[1] || "";
      if (head && /^https?:\/\//.test(link)) out.push({ title: head.slice(0, 220), link, when, source: source.slice(0, 80), summary: summary.slice(0, 900),
        img: /^https:\/\/\S+$/.test(decode(img)) && !/\.(?:gif|svg)(?:\?|$)/i.test(img) ? decode(img) : "" });
    }
    return out;
  }
  /** Wikipedia's page of the day: its featured article and "on this day" (true facts with a link, free). */
  function wiki(j) {
    const out = [], page = p => (p && p.content_urls && p.content_urls.desktop && p.content_urls.desktop.page) || "";
    const img = p => (p && p.thumbnail && /^https:/.test(p.thumbnail.source || "") ? p.thumbnail.source : "");
    if (j.tfa && page(j.tfa)) out.push({ title: (j.tfa.titles && j.tfa.titles.normalized) || j.tfa.title || "", link: page(j.tfa), when: Date.now(), source: "Wikipedia", summary: String(j.tfa.extract || "").slice(0, 900), img: img(j.tfa) });
    for (const e of (j.onthisday || []).slice(0, 20)) {
      const p = (e.pages || []).find(x => page(x) && x.extract);
      if (p) out.push({ title: `On this day in ${e.year}: ${e.text}`.slice(0, 220), link: page(p), when: Date.now(), source: "Wikipedia", summary: String(p.extract).slice(0, 900), img: img(p) });
    }
    return out.filter(s => s.title);
  }
  async function read(src) {
    const h = db.health[src.url] = db.health[src.url] || { fails: 0 };
    // a feed that keeps failing is tried again only twice a day
    if (h.fails >= 5 && Date.now() - (h.at || 0) < 12 * 3600e3) return [];
    try {
      const r = await get(src.url, { headers: { "user-agent": UA, accept: "application/rss+xml, application/atom+xml, application/xml, text/xml, */*" }, signal: AbortSignal.timeout(12000) });
      if (!r.ok) throw new Error("it said " + r.status);
      const list = /api\.wikimedia\.org\/feed/.test(src.url) ? wiki(await r.json()) : parse((await r.text()).slice(0, 3_000_000));
      if (!list.length) throw new Error("no stories in it");
      Object.assign(h, { at: Date.now(), n: list.length, err: "", fails: 0 });
      return list.map(s => ({ ...s, feed: src.url, source: s.source || src.name.replace(/^Google News · .*/, "") || hostOf(s.link) }));
    } catch (e) {
      Object.assign(h, { at: Date.now(), n: 0, err: String(e.message || e).slice(0, 80), fails: (h.fails || 0) + 1 });
      return [];
    } finally { save(); }
  }

  // ---------- Google News links: find the newspaper's own page (for its picture and its text) ----------
  async function realLink(u) {
    const m = String(u).match(/^https:\/\/news\.google\.com\/(?:rss\/)?articles\/([\w-]+)/);
    if (!m || !decodeGoogle) return u;
    try {
      const page = await get(`https://news.google.com/articles/${m[1]}`, { headers: { "user-agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36",
        accept: "text/html", "accept-language": "en-US,en;q=0.9", cookie: "CONSENT=PENDING+987" }, signal: AbortSignal.timeout(10000) });
      const html = await page.text();
      const sg = (html.match(/data-n-a-sg="([^"]+)"/) || [])[1], ts = (html.match(/data-n-a-ts="([^"]+)"/) || [])[1];
      if (!sg || !ts) return u;
      const inner = JSON.stringify(["garturlreq", [["X", "X", ["X", "X"], null, null, 1, 1, "US:en", null, 1, null, null, null, null, null, 0, 1], "X", "X", 1, [1, 1, 1], 1, 1, null, 0, 0, null, 0], m[1], Number(ts), sg]);
      const r = await get("https://news.google.com/_/DotsSplashUi/data/batchexecute", { method: "POST", signal: AbortSignal.timeout(10000),
        headers: { "content-type": "application/x-www-form-urlencoded;charset=UTF-8", origin: "https://news.google.com", referer: "https://news.google.com/", "x-same-domain": "1",
          "user-agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36" },
        body: "f.req=" + encodeURIComponent(JSON.stringify([[["Fbv4je", inner]]])) });
      const text = await r.text();
      const j = JSON.parse(text.split("\n\n")[1]);
      const url = JSON.parse(j[0][2])[1];
      return typeof url === "string" && /^https?:\/\/(?!news\.google\.)/.test(url) ? url : u;
    } catch (e) { return u; }
  }

  // ---------- which stories ----------
  const STOP = new Set(["the", "and", "for", "with", "from", "that", "this", "after", "over", "into", "about", "says", "said", "will", "news", "telugu", "today", "india", "అని", "ఈ", "ఆ", "లో"]);
  const words = s => new Set(String(s).toLowerCase().replace(/[^\p{L}\p{M}\p{N}\s]/gu, " ").split(/\s+/).filter(w => w.length > 2 && !STOP.has(w)));
  const overlap = (x, y) => { if (!x.size || !y.size) return 0; let n = 0; for (const w of x) if (y.has(w)) n++; return n / Math.min(x.size, y.size); };
  const alike = (a, b) => overlap(words(a), words(b));

  /** New stories for a section: the ones many papers report first, then the newest; never one already used. */
  async function fresh(cat) {
    const pool = [];
    for (const s of await Promise.all(sources(cat).map(read))) pool.push(...s);
    const recent = media.recentTitles(cat, 120).map(words);
    const seen = new Set(db.seen[cat] || []);
    const now = Date.now();
    const cands = pool.filter(s => !(s.when && now - s.when > 2 * 86400e3) && !seen.has(s.link) && !media.hasUrl(s.link));
    for (const s of pool) s.w = words(s.title);
    for (const s of cands) {
      s.cover = new Set(pool.filter(o => o !== s && overlap(s.w, o.w) >= 0.5).map(o => o.source || o.feed)).size;
      const hours = s.when ? (now - s.when) / 3600e3 : 6;
      s.score = Math.min(s.cover, 6) * 2 - hours * 0.5;
    }
    const picked = [], perSource = {};
    for (const s of cands.sort((a, b) => b.score - a.score)) {
      if (recent.some(r => overlap(r, s.w) >= 0.6) || picked.some(p => overlap(p.w, s.w) >= 0.5)) continue;
      const src = (s.source || hostOf(s.link)).toLowerCase();
      if ((perSource[src] || 0) >= 2) continue;
      perSource[src] = (perSource[src] || 0) + 1;
      picked.push(s);
      if (picked.length >= 12) break;
    }
    return picked;
  }

  // ---------- writing a post ----------
  const SCHEMA = () => ({ type: "object", required: ["title", "body", "emoji", "mood", "issue", "skip"], properties: {
    title: { type: "string" }, body: { type: "string" }, emoji: { type: "string" }, mood: { type: "string", enum: media.MOODS }, issue: { type: "string", enum: media.ISSUES },
    scene: { type: "string" }, skip: { type: "boolean" } } });
  const SYSTEM = (cat) => `You write short posts for the "Media" page of Habit AI, a Telugu app used by ordinary people in Andhra Pradesh and Telangana, including elders.
Section: ${media.CATS[cat].en} (${media.CATS[cat].te}). ${media.CATS[cat].brief}
Write in simple, everyday Telugu (Telugu script; English only for names, numbers and words people normally say in English).
Use ONLY the facts you are given (headline, summary and the start of the story). Never add facts, numbers, names, dates or quotes that are not there. If there are few facts, write less. Write it fully in your own words; do not copy sentences.
Neutral and respectful: no political attacks, no rumours, nothing hateful or adult. Set "skip" to true if the story is not useful for this section, is only an advertisement, is gossip about someone's private life, or is not suitable for families.
"mood" is how the post feels to read; "issue" is what it is mainly about. "scene" is 2 to 4 English words to find a stock video that fits the story, with no names of people or places (for example "paddy field rain", "hospital doctor", "train station").
Reply only with JSON: {"title": "<Telugu title, at most 12 words>", "body": "<Telugu, 30 to 90 words>", "emoji": "<one emoji>", "mood": one of ${media.MOODS.join(", ")}, "issue": one of ${media.ISSUES.join(", ")}, "scene": "<2-4 English words>", "skip": false}`;
  const facts = (s, page) => [`Headline: ${s.title}`, `Source: ${s.source || hostOf(s.link) || "(unknown)"}`,
    `Published: ${s.when ? new Date(s.when + 5.5 * 3600e3).toISOString().slice(0, 16).replace("T", " ") + " India time" : "recently"}`,
    s.summary ? `Summary: ${s.summary}` : "", page && page.description && page.description !== s.summary ? `Page description: ${page.description}` : "",
    page && page.text ? `Start of the story:\n${page.text}` : ""].filter(Boolean).join("\n");

  /** { title, body, emoji, mood, issue, skip, by } or null; throws { reason: "busy" } when the own brain must answer people now. */
  async function write(cat, s, page) {
    try {
      const j = await ownBrain.writeJson({ system: SYSTEM(cat), prompt: facts(s, page), schema: SCHEMA(), maxTokens: 700 });
      return { ...j, by: "own" };
    } catch (e) {
      if (e.reason === "busy") throw e;
      if (e.status !== 503) { console.error("Feeds: own brain couldn't write:", e.message); return null; }
    }
    // no own brain (or one too small to write well): Claude writes it, a limited number a day
    if (!API_KEY) return null;
    if (db.claude.day !== today()) db.claude = { day: today(), n: 0 };
    if (db.claude.n >= CLAUDE_DAILY()) return null;
    db.claude.n++; save();
    const data = await ai({ model: MODEL, max_tokens: 900, system: SYSTEM(cat), messages: [{ role: "user", content: facts(s, page) }] }, { tries: 1, backup: true });
    const text = (data.content || []).filter(c => c.type === "text").map(c => c.text).join("");
    const a = text.indexOf("{"), b = text.lastIndexOf("}");
    try { return { ...JSON.parse(text.slice(a, b + 1)), by: "claude" }; } catch (e) { return null; }
  }
  const telugu = s => (String(s).match(/[ఀ-౿]/g) || []).length;

  /** Writes up to [n] new posts for a section. Returns how many were added. */
  async function run(cat, n) {
    if (!media.CATS[cat] || cat === "kids") return 0;
    let added = 0, tried = 0;
    const list = await fresh(cat);
    for (const s of list) {
      if (added >= n || tried >= n + 4) break;
      tried++;
      const mark = () => { db.seen[cat] = [...(db.seen[cat] || []), s.link].slice(-600); save(); };
      let link = s.link;
      if (/^https:\/\/news\.google\./.test(link)) {
        link = await realLink(link);
        if (link !== s.link && media.hasUrl(link)) { mark(); continue; }
      }
      const page = /^https:\/\/news\.google\./.test(link) ? null : await preview(link, { text: true }).catch(() => null);
      let p = null;
      try { p = await write(cat, s, page); }
      catch (e) {
        if (e.reason === "busy") { console.log("Feeds: paused, the own brain is answering people while Claude rests"); break; }
        console.error("Feeds: couldn't write a post:", e.message); continue;
      }
      mark();
      if (!p || p.skip) { count("skipped"); continue; }
      const title = String(p.title || "").trim().slice(0, 140), body = String(p.body || "").trim().slice(0, 1400);
      if (title.length < 4 || body.length < 30 || telugu(title + body) < 20) { count("skipped"); continue; }
      if (media.recentTitles(cat, 60).some(t => alike(t, title) >= 0.6)) { count("skipped"); continue; }   // the same story from another paper
      media.addItem({ cat, title, body, emoji: String(p.emoji || "").slice(0, 8),
        mood: media.MOODS.includes(p.mood) ? p.mood : "useful", issue: media.ISSUES.includes(p.issue) ? p.issue : "other", scene: p.scene,
        url: link, source: (page && page.site && !/news\.google/.test(page.site) ? page.site : "") || s.source || hostOf(link),
        img: s.img || (page ? page.image || "" : ""), by: "auto", writer: p.by, kind: "feed" });
      db.wrote[p.by] = (db.wrote[p.by] || 0) + 1; count(p.by); save();
      added++;
    }
    console.log(`Feeds: added ${added} ${cat} post(s) from ${list.length} new stories`);
    return added;
  }

  // ---------- one section at a time, in the background ----------
  const queue = [];
  let running = "";
  function want(cat, n) { if (media.CATS[cat] && running !== cat && !queue.some(q => q.cat === cat)) queue.push({ cat, n }); pump(); }
  async function pump() {
    if (running) return;
    while (queue.length) {
      const job = queue.shift();
      running = job.cat;
      try { await run(job.cat, job.n); } catch (e) { console.error("Feeds error:", job.cat, e.message); }
      running = "";
    }
  }
  /** Can posts be written at all right now (own brain, or Claude with credit left today)? */
  async function canWrite() {
    if (await ownBrain.ready() && ownBrain.canWrite()) return true;
    return !!API_KEY && !(db.claude.day === today() && db.claude.n >= CLAUDE_DAILY());
  }

  // ---------- the Teach page ----------
  function list() {
    return Object.keys(media.CATS).filter(c => c !== "kids").map(c => ({ cat: c, te: media.CATS[c].te, en: media.CATS[c].en,
      feeds: sources(c).map(s => { const h = db.health[s.url] || {}; return { url: s.url, name: s.name, added: !!s.added, stories: h.n || 0, problem: h.err || "", checked: h.at || 0 }; }) }));
  }
  function addFeed(cat, url) {
    url = String(url || "").trim();
    if (!media.CATS[cat] || cat === "kids" || !/^https:\/\/[^\s"'<>]+$/.test(url) || url.length > 500) return false;
    db.removed[cat] = (db.removed[cat] || []).filter(u => u !== url);
    if (!sources(cat).some(s => s.url === url)) db.added[cat] = [...(db.added[cat] || []), url].slice(-30);
    delete db.health[url];
    save(); return true;
  }
  function removeFeed(cat, url) {
    if (!media.CATS[cat]) return false;
    if ((db.added[cat] || []).includes(url)) db.added[cat] = db.added[cat].filter(u => u !== url);
    else db.removed[cat] = [...new Set([...(db.removed[cat] || []), url])];
    save(); return true;
  }
  const stats = () => ({ wrote: db.wrote, today: db.today.day === today() ? db.today : { day: today() }, claudeToday: db.claude.day === today() ? db.claude.n : 0,
    claudeDaily: CLAUDE_DAILY(), running, waiting: queue.map(q => q.cat) });

  return { run, want, canWrite, list, addFeed, removeFeed, stats, parse, realLink, fresh };
};
