// YouTube in Media: the newest videos of trusted Telugu channels in each section (news, movies, devotional, kids,
// farming…), live TV news and live darshan, Shorts, and what's trending today.
//  - Works without any key: YouTube's own channel feeds (long videos and Shorts separately).
//  - With YOUTUBE_API_KEY (free, from Google Cloud) it also finds channels by their @handle more surely, and gets the
//    most popular Telugu videos of the day in India (without it: our channels' most-watched videos).
// Live: every 30 minutes each live channel's own "live" page is checked.
// Every 2 hours the most-watched new videos become Media posts (a few a day per section, never clickbait: our own
// brain checks each title). Videos play inside the app with YouTube's own player, so the channels get their views.
const fs = require("fs");
const path = require("path");

module.exports = function makeYouTube({ DATA_DIR, media, ownBrain, fetchImpl, h }) {
  const get = fetchImpl || ((...a) => fetch(...a));
  const KEY = () => process.env.YOUTUBE_API_KEY || "";
  const API = process.env.YOUTUBE_API_BASE || "https://www.googleapis.com/youtube/v3";
  const YT = process.env.YOUTUBE_BASE || "https://www.youtube.com";
  const ON = String(process.env.MEDIA_YOUTUBE || "true") !== "false" && String(process.env.MEDIA_AUTO || "true") !== "false";
  const FILE = path.join(DATA_DIR, "youtube.json");
  let db = { added: {}, removed: {}, ids: {}, ch: {}, posted: { day: "" }, trending: { at: 0, list: [] }, runs: {} };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  let saveTimer = null;
  const save = () => { clearTimeout(saveTimer); saveTimer = setTimeout(() => { try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("YouTube save error:", e.message); } }, 300); };
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);

  // ---------- trusted channels (the owner can add or remove channels on the Teach page) ----------
  const c = (id, name, live) => ({ id, name, live: !!live });
  const hd = (handle, name, live) => ({ handle, name, live: !!live });     // found by its @handle (the server looks up its id once)
  const DEFAULTS = {
    news: [c("UCPXTXMecYqnRKNdqdVOGSFg", "TV9 Telugu", 1), c("UCumtYpCY26F6Jr3satUgMvA", "NTV Telugu", 1), c("UC_2irx_BQR7RsBKmUV9fePQ", "ABN Telugu", 1),
      c("UCAR3h_9fLV82N2FH4cE4RKw", "TV5 News", 1), c("UCDCMjD1XIAsCZsYHNMGVcog", "V6 News Telugu", 1), c("UCNZOrs1QBt8cJnv9ud96qRA", "hmtv", 1), c("UCu6edg8_eu3-A8ylgaWereA", "T News", 1),
      hd("@Sakshi_TV_Live", "Sakshi TV", 1), c("UCg6JyAGrskayg14qJP3598g", "TV9 Telugu Digital"), c("UCE8KNq9oibK4AdPw_iWrS1Q", "Samayam Telugu"), hd("@BBCNewsTelugu", "BBC News Telugu")],
    devotional: [c("UCS2Y83GD-fc7qqgNW5uj41g", "SVBC TTD", 1), c("UCZQ3VR0OxbRVVy5uzfGomUg", "Aditya Bhakthi"), c("UCjA7qIB2IetzeolMd_Hwa0w", "T-Series Bhakti Sagar Telugu")],
    // trailers, songs and film news: the production houses and music labels themselves
    movies: [c("UCNApqoVYJbYSrni4YsbXzyQ", "Aditya Music"), c("UCnSqxrSfo1sK4WZ7nBpYW1Q", "Lahari Music"), c("UCWqyzn3cDkRDh3kRGWrIQwA", "Mango Music"), c("UCnJjcn5FrgrOEp5_N45ZLEQ", "T-Series Telugu"),
      c("UCn4rEMqKtwBQ6-oEwbd4PcA", "Sony Music South"), hd("@saregamasouth", "Saregama Telugu"), c("UCKZSn5C-RzrLjuWJF8wWiDw", "Mythri Movie Makers"), c("UCiJfiEg1FImWsVuEu0L8X6Q", "Geetha Arts"),
      hd("@sitharaentertainments", "Sithara Entertainments"), c("UCU0PnZqMDQ0uvwacGzCX3NA", "Suresh Productions"), c("UC70pKToywlxOGdgIvz8gYqA", "Vyjayanthi Movies"),
      hd("@UV_Creations", "UV Creations"), hd("@telugufilmnagar", "Telugu FilmNagar"), c("UC3PYGKK29qJ0zu-eEthHK4g", "Zee Telugu"), c("UCSs9H1cyB3OHdy8wkit8ZKg", "ETV Telugu")],
    kids: [c("UCHvoq_rGmOKJKGId1nD1V4Q", "Infobells Telugu"), c("UCX56XWkzt1NHIoSIrreA5xw", "Mango Telugu Rhymes"), hd("@ChuChuTVTelugu", "ChuChu TV Telugu"),
      c("UCVQExT4EjUs4Zc-h0NxLOFw", "Koo Koo TV Telugu"), hd("@sesameworkshopindiatelugu", "Sesame Workshop India Telugu")],
    farming: [c("UCO3uQWHfi0YlcypvilKfKlA", "AgriTech Telugu"), hd("@rythunestham", "Rythu Nestham")],
    health: [], jobs: [], realestate: [], crypto: [], facts: []
  };
  // new videos posted in Media per day (YOUTUBE_MORE=2 doubles them); trailers and breaking news come first
  const MORE = () => Math.max(0.5, Math.min(4, Number(process.env.YOUTUBE_MORE || 1)));
  const BASE_DAILY = { news: 30, movies: 30, devotional: 10, kids: 10, farming: 6, health: 4, jobs: 4, realestate: 2, crypto: 2, facts: 6 };
  // and each day a few of the most-watched videos of all time ("old favourites"): many in movies, 1-2 of the most beautiful in devotional
  const CLASSICS = { movies: 8, devotional: 2, kids: 3, farming: 1, facts: 2 };
  const DAILY = new Proxy({}, { get: (t, k) => BASE_DAILY[k] === undefined ? undefined : Math.round(BASE_DAILY[k] * MORE()), ownKeys: () => Reflect.ownKeys(BASE_DAILY), getOwnPropertyDescriptor: (t, k) => ({ enumerable: true, configurable: true, value: BASE_DAILY[k] }) });
  const PER_RUN = { news: 2, movies: 3 };
  const TRAILER = /trailer|teaser|glimpse|first look|ట్రైలర్|టీజర్|గ్లింప్స్|ఫస్ట్ లుక్/i;
  const BREAKING = /breaking|బ్రేకింగ్|alert|అలర్ట్|cyclone|తుఫాను|heavy rain|భారీ వర్ష|flood|వరద|earthquake|భూకంప|accident|ప్రమాద|red alert|holiday|సెలవు|exam|పరీక్ష|results?\b|ఫలితాలు/i;
  const keyOf = x => x.id || x.handle;
  function channels(cat) {
    const removed = db.removed[cat] || [];
    return [...(DEFAULTS[cat] || []).filter(x => !removed.includes(x.id)), ...(db.added[cat] || [])];
  }
  const allChannels = () => Object.keys(media.CATS).flatMap(cat => channels(cat).map(x => ({ ...x, cat })));

  // ---------- reading YouTube's feeds ----------
  const decode = s => String(s || "").replace(/&amp;/g, "&").replace(/&quot;/g, "\"").replace(/&#0?39;|&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&#(\d+);/g, (m, n) => { try { return String.fromCodePoint(Number(n)); } catch (e) { return ""; } });
  const tag = (x, t) => { const m = x.match(new RegExp(`<${t}\\b[^>]*>([\\s\\S]*?)<\\/${t}>`, "i")); return m ? decode(m[1]).trim() : ""; };
  function parseFeed(xml) {
    const out = [];
    for (const m of String(xml).matchAll(/<entry\b[\s\S]*?<\/entry>/gi)) {
      const x = m[0];
      const id = tag(x, "yt:videoId");
      if (!/^[\w-]{11}$/.test(id)) continue;
      const href = (x.match(/<link\b[^>]*rel="alternate"[^>]*href="([^"]+)"/i) || [])[1] || "";
      const author = (x.match(/<author>[\s\S]*?<\/author>/i) || [""])[0];
      out.push({ id, title: tag(x, "title").slice(0, 200), ch: tag(author, "name").slice(0, 80),
        chId: tag(x, "yt:channelId"), at: Date.parse(tag(x, "published")) || 0, desc: tag(x, "media:description").slice(0, 1500),
        views: Number((x.match(/<media:statistics\b[^>]*views="(\d+)"/i) || [])[1] || 0), short: /\/shorts\//.test(href) });
    }
    return out;
  }
  async function feed(q) {
    const r = await get(`${YT}/feeds/videos.xml?${q}`, { headers: { "user-agent": "Mozilla/5.0 (compatible; BigDAI-reader/1.0; +https://bigdai.tech)" }, signal: AbortSignal.timeout(12000) });
    if (!r.ok) throw new Error("YouTube said " + r.status);
    return parseFeed(await r.text());
  }
  async function api(pathq) {
    const r = await get(`${API}/${pathq}&key=${encodeURIComponent(KEY())}`, { signal: AbortSignal.timeout(12000) });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error("YouTube API: " + ((j.error && j.error.message) || r.status));
    return j;
  }

  /** A channel's id (UC…), from its @handle when needed (with the key, or from the channel's page). */
  async function resolve(x) {
    if (x.id) return x.id;
    if (db.ids[x.handle]) return db.ids[x.handle];
    let id = "";
    if (KEY()) { try { const j = await api(`channels?part=id&forHandle=${encodeURIComponent(x.handle)}`); id = ((j.items || [])[0] || {}).id || ""; } catch (e) { } }
    if (!id) {
      try {
        const r = await get(`${YT}/${x.handle}`, { headers: { "user-agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36", "accept-language": "en", cookie: "SOCS=CAI; CONSENT=YES+1" }, signal: AbortSignal.timeout(12000) });
        const html = await r.text();
        id = (html.match(/<link rel="canonical" href="https:\/\/www\.youtube\.com\/channel\/(UC[\w-]{22})"/) || html.match(/"externalId":"(UC[\w-]{22})"/) || html.match(/itemprop="identifier" content="(UC[\w-]{22})"/) || [])[1] || "";
      } catch (e) { }
    }
    if (id) { db.ids[x.handle] = id; save(); }
    return id;
  }

  /** The newest long videos and Shorts of one channel. */
  async function refresh(x) {
    const st = db.ch[keyOf(x)] = db.ch[keyOf(x)] || { fails: 0 };
    try {
      const id = await resolve(x);
      if (!id) throw new Error("couldn't find this channel");
      const tail = id.slice(2);
      let long = [];
      try { long = await feed(`playlist_id=UULF${tail}`); } catch (e) { long = (await feed(`channel_id=${id}`)).filter(v => !v.short); }
      let shorts = [];
      try { shorts = await feed(`playlist_id=UUSH${tail}`); } catch (e) { }
      if (!st.popAt || Date.now() - st.popAt > 20 * 3600e3) {
        try { st.popular = (await feed(`playlist_id=UULP${tail}`)).sort((a, b) => b.views - a.views).slice(0, 15).map(v => ({ ...v, desc: v.desc.slice(0, 400) })); } catch (e) { }
        try { st.popShorts = (await feed(`playlist_id=UUPS${tail}`)).slice(0, 15).map(v => ({ ...v, desc: "" })); } catch (e) { }      // the channel's most-watched Shorts
        st.popAt = Date.now();
      }
      Object.assign(st, { id, name: x.name || (long[0] || shorts[0] || {}).ch || st.name || "", at: Date.now(), err: "", fails: 0,
        videos: long.slice(0, 15).map(v => ({ ...v, desc: v.desc.slice(0, 600) })), shorts: shorts.slice(0, 15).map(v => ({ ...v, desc: "" })) });
    } catch (e) {
      Object.assign(st, { at: Date.now(), err: String(e.message || e).slice(0, 80), fails: (st.fails || 0) + 1 });
    }
    save();
  }

  // ---------- live now (TV news channels, live darshan) ----------
  // (a channel's "live" page shows its live stream now, also a 24/7 one that started weeks ago)
  async function checkLive() {
    for (const x of allChannels().filter(x => x.live)) {
      const st = db.ch[keyOf(x)] = db.ch[keyOf(x)] || {};
      try {
        const id = await resolve(x); if (!id) continue;
        const r = await get(`${YT}/channel/${id}/live`, { headers: { "user-agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36", "accept-language": "en", cookie: "SOCS=CAI; CONSENT=YES+1" }, signal: AbortSignal.timeout(15000) });
        if (!r.ok) throw new Error("YouTube said " + r.status);
        const html = (await r.text()).slice(0, 3_000_000);
        const vid = (html.match(/<link rel="canonical" href="https:\/\/www\.youtube\.com\/watch\?v=([\w-]{11})"/) || [])[1];
        const isLive = /"isLiveNow":true/.test(html);
        const title = decode((html.match(/<meta name="title" content="([^"]*)"/) || [])[1] || "");
        st.live = vid && isLive ? { id: vid, title: str(title, 200), since: (st.live && st.live.id === vid && st.live.since) || Date.now() } : null;
        st.liveErr = "";
      } catch (e) { st.liveErr = String(e.message || e).slice(0, 80); /* keep what we knew */ }
      st.liveAt = Date.now();
    }
    save();
    updateLivePosts();
  }
  /** One "🔴 LIVE" post per live channel, updated when its stream changes and hidden when it ends. */
  function updateLivePosts() {
    for (const x of allChannels().filter(x => x.live)) {
      const st = db.ch[keyOf(x)] || {};
      const key = "live:" + keyOf(x);
      const item = media.allItems().find(i => i.key === key);
      if (st.live) {
        const title = `🔴 LIVE · ${x.name || st.name}`.slice(0, 140);
        const body = (cleanTitle(st.live.title) || (x.cat === "devotional" ? "ప్రత్యక్ష ప్రసారం" : "ప్రత్యక్ష వార్తలు")) + (x.cat === "devotional" ? " · ఇప్పుడు ప్రత్యక్ష ప్రసారం" : " · ఇప్పుడు లైవ్‌లో");
        const yt = { id: st.live.id, title: str(st.live.title, 150), ch: x.name || st.name || "" };
        if (!item) media.addItem({ cat: x.cat, title, body, emoji: "🔴", yt, url: `https://www.youtube.com/watch?v=${st.live.id}`, img: "", by: "auto", writer: "youtube",
          kind: "live", key, mood: x.cat === "devotional" ? "devotional" : "serious", issue: x.cat === "devotional" ? "festivals" : "other" });
        else if (item.hidden || !item.yt || item.yt.id !== st.live.id) {
          Object.assign(item, { hidden: false, title, body, yt, url: `https://www.youtube.com/watch?v=${st.live.id}`, at: item.yt && item.yt.id === st.live.id ? item.at : Date.now() });
          media.changed();
        }
      } else if (item && !item.hidden) { item.hidden = true; media.changed(); }
    }
  }

  // ---------- trending today (Telugu) ----------
  const telugu = s => /[ఀ-౿]/.test(s);
  /** Not clickbait: not skipped by our own brain, and no "shocking", "viral", gossip or crime words in the title. */
  const fine = v => !(db.skipped || []).includes(v.id) && !BAD.test(v.title || "");
  async function trending() {
    let list = [];
    if (KEY()) {
      try {
        const j = await api("videos?part=snippet,statistics&chart=mostPopular&regionCode=IN&maxResults=50");
        const ours = new Set(Object.values(db.ch).map(s => s.id).filter(Boolean));
        list = (j.items || []).filter(v => v.snippet && (telugu(v.snippet.title) || /^te/.test(v.snippet.defaultAudioLanguage || v.snippet.defaultLanguage || "") || ours.has(v.snippet.channelId)))
          .map(v => ({ id: v.id, title: str(v.snippet.title, 200), ch: str(v.snippet.channelTitle, 80), at: Date.parse(v.snippet.publishedAt) || 0, views: Number((v.statistics || {}).viewCount || 0),
            desc: str(v.snippet.description, 600), cat: ({ "25": "news", "17": "news", "1": "movies", "10": "movies", "23": "movies", "24": "movies", "27": "jobs" })[v.snippet.categoryId] || "" }));
      } catch (e) { console.error("YouTube trending:", e.message); }
    }
    if (list.length < 5) {
      // without a key (or few Telugu videos in India's list): our channels' most-watched videos of the last 2 days
      const since = Date.now() - 2 * 86400e3, seen = new Set(list.map(v => v.id));
      list = list.concat(Object.values(db.ch).flatMap(s => s.videos || []).filter(v => v.at > since && !seen.has(v.id)).sort((a, b) => b.views - a.views)
        .slice(0, 20 - list.length).map(v => ({ id: v.id, title: v.title, ch: v.ch, at: v.at, views: v.views })));
    }
    db.trending = { at: Date.now(), list: list.filter(fine).slice(0, 20) }; save();
  }

  // ---------- new videos become Media posts ----------
  function cleanTitle(t) {
    let x = String(t || "").replace(/#[^\s#]+/g, " ");
    for (let k = 0; k < 3; k++) x = x.replace(/\s*[|｜]\s*[^|｜]*(?:tv|news|music|telugu|live|channel|official)[^|｜]*$/i, "");   // "| Telugu News | TV9 Telugu"
    return x.replace(/\b(?:LIVE|Live|live)\s*[:|-]?\s*/g, "").replace(/\s{2,}/g, " ").replace(/^[\s|:-]+|[\s|:-]+$/g, "").trim().slice(0, 140);
  }
  const BAD = /shock|షాకింగ్|వైరల్|viral|prank|scandal|leaked|హత్య|murder|rape|అత్యాచార|bigg ?boss|బిగ్ ?బాస్|ఎఫైర్|affair|రొమాన్స్|romance|hot\b|sexy|గొడవ|fight\b|బూతులు|abuse/i;
  function cleanDesc(d) {
    const lines = String(d || "").split(/\n+/).map(l => l.replace(/https?:\/\/\S+/g, "").replace(/#[^\s#]+/g, "").trim())
      .filter(l => l.length > 15 && !/subscribe|follow us|facebook|instagram|twitter|whatsapp|download|copyright|all rights|℗|©|lyrics|credits|music\s*:|singer|director\s*:|producer|watch (?:the )?full|click (?:here|the link)|stream (?:it )?now|available on|presenting|watch now/i.test(l));
    return lines.slice(0, 2).join(" ").slice(0, 300);
  }
  const DEFAULT_ISSUE = { news: "other", movies: "cinema", devotional: "festivals", kids: "children", farming: "farming", health: "health", jobs: "jobs", realestate: "money", crypto: "money" };
  const DEFAULT_MOOD = { news: "serious", movies: "happy", devotional: "devotional", kids: "happy", farming: "useful", health: "useful", jobs: "useful", realestate: "useful", crypto: "useful" };
  const SCHEMA = () => ({ type: "object", required: ["skip", "title", "mood", "issue"], properties: { skip: { type: "boolean" }, title: { type: "string" },
    mood: { type: "string", enum: media.MOODS }, issue: { type: "string", enum: media.ISSUES } } });
  const SYSTEM = cat => `You choose YouTube videos for the "Media" page of BigD AI, a Telugu app for families in Andhra Pradesh and Telangana (elders and children use it too). Section: ${media.CATS[cat].en}.
You get one video's title and description from a known channel. Set "skip" to true if it is clickbait, gossip about someone's private life, crime or violence details, vulgar or adult, fear-mongering, a political attack, or not useful for this section; also if it is clearly not for Telugu people (a Tamil, Malayalam, Kannada or Hindi film or song that is not released in Telugu).
"title": a clean Telugu title for it, at most 12 words, only from the given title (no hashtags, no channel name, no "LIVE", no made-up facts).
Reply only with JSON: {"skip": false, "title": "...", "mood": one of ${media.MOODS.join(", ")}, "issue": one of ${media.ISSUES.join(", ")}}`;
  async function judge(cat, v) {
    if (ownBrain) {
      try {
        const j = await ownBrain.writeJson({ system: SYSTEM(cat), prompt: `Channel: ${v.ch}\nTitle: ${v.title}\nDescription: ${cleanDesc(v.desc) || "(none)"}`, schema: SCHEMA(), maxTokens: 160, timeoutMs: 120000 });
        return { skip: !!j.skip, title: cleanTitle(j.title) || cleanTitle(v.title), mood: j.mood, issue: j.issue, by: "own" };
      } catch (e) { if (e.reason === "busy") throw e; }
    }
    const other = /\b(tamil|malayalam|kannada|hindi)\b/i.test(v.title) && !/telugu|తెలుగు/i.test(v.title);
    return { skip: BAD.test(v.title) || other, title: cleanTitle(v.title), mood: DEFAULT_MOOD[cat], issue: DEFAULT_ISSUE[cat], by: "rules" };
  }
  /** Which new video first: the ones people are watching fastest; trailers and breaking news much sooner. */
  function score(cat, v) {
    const hours = Math.max(0.25, (Date.now() - v.at) / 3600e3);
    let s = ((v.views || 0) + 200) / Math.pow(hours + 1, 0.8);
    if (cat === "movies" && TRAILER.test(v.title)) s *= 6;
    if (cat === "news" && BREAKING.test(v.title) && hours < 4) s *= 6;
    return s;
  }
  async function postNew(only) {
    if (db.posted.day !== today()) db.posted = { day: today() };
    const since = Date.now() - 36 * 3600e3;
    for (const cat of Object.keys(BASE_DAILY)) {
      if (only && !only.includes(cat)) continue;
      const recent = media.allItems().filter(i => i.cat === cat && i.kind === "video" && Date.now() - i.at < 24 * 3600e3).length;
      const catchUp = recent < DAILY[cat] / 2 ? Math.min(10, Math.ceil(DAILY[cat] / 2) - recent) : 0;
      let room = Math.min(Math.max(PER_RUN[cat] || 1, catchUp), DAILY[cat] - (db.posted[cat] || 0));
      if (room <= 0) continue;
      const trend = (db.trending.list || []).filter(v => v.cat === cat);
      const pool = [...channels(cat).flatMap(x => (db.ch[keyOf(x)] || {}).videos || []), ...trend]
        .filter((v, i, all) => v.at > since && all.findIndex(o => o.id === v.id) === i && !media.hasUrl(`https://www.youtube.com/watch?v=${v.id}`) && !(db.skipped || []).includes(v.id))
        .sort((a, b) => score(cat, b) - score(cat, a));
      for (const v of pool.slice(0, room + 4)) {
        if (room <= 0) break;
        let j;
        try { j = await judge(cat, v); } catch (e) { return; }      // our own brain is answering people now: later
        if (j.skip || j.title.length < 4) { db.skipped = [...(db.skipped || []), v.id].slice(-800); save(); continue; }
        const it = media.addItem({ cat, title: j.title, body: cleanDesc(v.desc) || `${v.ch} నుంచి కొత్త వీడియో. చూడటానికి ▶ నొక్కండి.`, emoji: cat === "kids" ? "🧒" : cat === "devotional" ? "🙏" : "▶️",
          yt: { id: v.id, title: str(v.title, 150), ch: v.ch }, url: `https://www.youtube.com/watch?v=${v.id}`, source: "YouTube · " + v.ch, img: "", by: "auto", writer: "youtube",
          kind: "video", mood: j.mood, issue: j.issue });
        if (it) { db.posted[cat] = (db.posted[cat] || 0) + 1; room--; save(); }     // a few per run, so they come through the day
      }
    }
  }

  /** A few old favourites a day (most watched of all time), spread from 7 AM to 10 PM; none again for 4 months. */
  async function postClassics() {
    const hour = new Date(Date.now() + 5.5 * 3600e3).getUTCHours();
    if (hour < 7 || hour > 22) return;
    if (db.posted.day !== today()) db.posted = { day: today() };
    db.classics = db.classics || {};
    for (const [id, at] of Object.entries(db.classics)) if (Date.now() - at > 120 * 86400e3) delete db.classics[id];
    for (const [cat, n] of Object.entries(CLASSICS)) {
      const done = db.posted["c_" + cat] || 0;
      if (done >= Math.round(n * MORE()) || done >= Math.ceil(n * (hour - 6) / 16)) continue;     // spread through the day
      const pool = channels(cat).flatMap(x => (db.ch[keyOf(x)] || {}).popular || [])
        .filter(v => !db.classics[v.id] && fine(v) && !media.hasUrl(`https://www.youtube.com/watch?v=${v.id}`)).sort((a, b) => b.views - a.views).slice(0, 12);
      for (let tries = 0; tries < 3 && pool.length; tries++) {
        const v = pool.splice(Math.floor(Math.random() * pool.length), 1)[0];
        db.classics[v.id] = Date.now();
        let j;
        try { j = await judge(cat, v); } catch (e) { return; }
        if (j.skip || j.title.length < 4) { save(); continue; }
        const it = media.addItem({ cat, title: j.title, body: "⭐ ఎక్కువ మంది చూసిన వీడియో · One of the most-watched\n" + (cleanDesc(v.desc) || v.ch), emoji: "⭐",
          yt: { id: v.id, title: str(v.title, 150), ch: v.ch }, url: `https://www.youtube.com/watch?v=${v.id}`, source: "YouTube · " + v.ch, img: "", by: "auto", writer: "youtube",
          kind: "video", mood: j.mood, issue: j.issue, meta: { classic: true } });
        save();
        if (it) { db.posted["c_" + cat] = done + 1; save(); break; }
      }
    }
  }

  // ---------- the schedule: news videos every 30 minutes, the others every 2 hours, live every 30 minutes, trending every 3 hours ----------
  let busy = false;
  async function tick(force) {
    if (!ON || busy) return;
    busy = true;
    try {
      const now = Date.now();
      if (force || now - (db.runs.videos || 0) > 2 * 3600e3) {
        db.runs.videos = now; db.runs.news = now; save();
        for (const x of allChannels()) await refresh(x);
        await postNew();
        await postClassics();
      } else if (now - (db.runs.news || 0) > 30 * 60e3) {
        // news (and breaking news) every 30 minutes
        db.runs.news = now; save();
        for (const x of allChannels().filter(x => x.cat === "news")) await refresh(x);
        await postNew(["news"]);
      }
      if (force || now - (db.runs.live || 0) > 30 * 60e3) { db.runs.live = now; save(); await checkLive(); }
      if (force || now - (db.runs.trending || 0) > 3 * 3600e3) { db.runs.trending = now; save(); await trending(); }
    } catch (e) { console.error("YouTube error:", e.message); }
    busy = false;
  }
  if (ON) { setTimeout(() => tick(), 3 * 60_000).unref(); setInterval(() => tick(), 5 * 60_000).unref(); }

  // ---------- for the app: GET /api/videos?cat=news ----------
  function videos(cat) {
    const list = cat && media.CATS[cat] ? channels(cat).map(x => ({ ...x, cat })) : allChannels();
    const view = v => ({ id: v.id, title: v.title, ch: v.ch, at: v.at, views: v.views || 0 });
    const live = list.map(x => { const st = db.ch[keyOf(x)] || {}; return st.live ? { id: st.live.id, title: st.live.title, ch: x.name || st.name, cat: x.cat, since: st.live.since, live: true } : null; }).filter(Boolean);
    const latest = list.flatMap(x => ((db.ch[keyOf(x)] || {}).videos || []).filter(fine).slice(0, 8).map(v => ({ ...view(v), cat: x.cat }))).sort((a, b) => b.at - a.at).slice(0, 40);
    // Shorts: new ones and every channel's most-watched ones, in a new random order each time (today's first), up to 300
    const seen = new Set(), mix = a => { for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; } return a; };
    const pool = list.flatMap(x => { const st = db.ch[keyOf(x)] || {}; return [...(st.shorts || []), ...(st.popShorts || [])].filter(fine).map(v => ({ ...view(v), cat: x.cat })); })
      .filter(v => !seen.has(v.id) && seen.add(v.id));
    const day = Date.now() - 24 * 3600e3;
    const shorts = [...mix(pool.filter(v => v.at > day)), ...mix(pool.filter(v => v.at <= day))].slice(0, 300);
    const popular = list.flatMap(x => ((db.ch[keyOf(x)] || {}).popular || []).filter(fine).map(v => ({ ...view(v), cat: x.cat }))).sort((a, b) => b.views - a.views).slice(0, 40);
    return { live, latest, shorts, popular, trending: cat && cat !== "all" ? [] : db.trending.list, key: !!KEY() };
  }
  const reads = new Map();
  async function handle(req, res, url, origin) {
    if (url.pathname !== "/api/videos" || req.method !== "GET") return false;
    if (h && !h.allowed("v:" + h.clientIp(req), reads, 300)) { h.send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
    h.send(res, 200, videos(str(url.searchParams.get("cat"), 20)), origin);
    return true;
  }

  // ---------- the Teach page ----------
  function list() {
    return Object.keys(media.CATS).map(cat => ({ cat, te: media.CATS[cat].te, en: media.CATS[cat].en, daily: DAILY[cat] || 0, postedToday: db.posted.day === today() ? db.posted[cat] || 0 : 0,
      channels: channels(cat).map(x => { const st = db.ch[keyOf(x)] || {}; return { key: keyOf(x), name: x.name || st.name || x.handle || x.id, live: !!x.live, liveNow: !!st.live, added: !!(db.added[cat] || []).find(a => keyOf(a) === keyOf(x)),
        videos: (st.videos || []).length, shorts: (st.shorts || []).length, problem: st.err || "", checked: st.at || 0 }; }) }));
  }
  /** Adds a channel from its link, @handle or UC… id. */
  function addChannel(cat, input, live) {
    if (!media.CATS[cat]) return false;
    const s = String(input || "").trim();
    const id = (s.match(/(?:^|\/channel\/)(UC[\w-]{22})(?:[/?#]|$)/) || [])[1];
    const handle = id ? "" : ((s.match(/(?:youtube\.com\/)?(@[\w.\-ఀ-౿]{3,60})/) || [])[1] || "");
    if (!id && !handle) return false;
    const x = id ? { id, name: "", live: !!live } : { handle, name: handle, live: !!live };
    db.removed[cat] = (db.removed[cat] || []).filter(k => k !== keyOf(x));
    if (!channels(cat).some(y => keyOf(y) === keyOf(x))) db.added[cat] = [...(db.added[cat] || []), x].slice(-30);
    save();
    refresh({ ...x, cat }).catch(() => {});
    return true;
  }
  function removeChannel(cat, key) {
    if (!media.CATS[cat]) return false;
    if ((db.added[cat] || []).some(x => keyOf(x) === key)) db.added[cat] = db.added[cat].filter(x => keyOf(x) !== key);
    else db.removed[cat] = [...new Set([...(db.removed[cat] || []), key])];
    const item = media.allItems().find(i => i.key === "live:" + key);
    if (item && !item.hidden) { item.hidden = true; media.changed(); }
    save(); return true;
  }
  const stats = () => ({ on: ON, key: !!KEY(), channels: allChannels().length, liveNow: Object.values(db.ch).filter(s => s.live).length, postedToday: db.posted.day === today() ? db.posted : {} });

  return { handle, videos, list, addChannel, removeChannel, stats, tick, parseFeed, cleanTitle, _db: () => db };
};
