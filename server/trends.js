// What's happening today, for Habit's cartoons and its self brain. It learns from the internet every day:
//  - Google Trends: what people in India are searching for right now (a free RSS feed, no key). Only kind, everyday
//    THEMES are taken from it (a big cricket match day, exam results, a rainy day, a holiday, a festival…), never the
//    searches themselves: no names of real people, politics, crimes, films or brands reach a cartoon. TRENDS=false turns
//    it off.
//  - The festival calendar, worked out on our own server from our panchangam (panchang.js): Sankranti, Ugadi, Sri Rama
//    Navami, Vinayaka Chavithi, Bathukamma, Dasara, Deepavali, Karthika Pournami, Christmas, Easter, Ramzan, Bakrid and
//    more, and kind days like Teachers' Day and Children's Day. (Ramzan and Bakrid follow the moon sighting, so their
//    day can be one day off.)
const P = require("./panchang.js");

module.exports = function makeTrends({ fetchImpl } = {}) {
  const get = fetchImpl || fetch;
  const ON = () => String(process.env.TRENDS || "true") !== "false";
  const URL_ = () => process.env.TRENDS_URL || "https://trends.google.com/trending/rss?geo=IN";
  const istDate = (ms = Date.now()) => new Date(ms + 5.5 * 3600e3).toISOString().slice(0, 10);
  const addDays = (date, n) => new Date(Date.parse(date + "T00:00:00Z") + n * 86400e3).toISOString().slice(0, 10);

  // ---------- the festival calendar ----------
  const PL = P.PLACES.vijayawada;
  const cache = new Map();
  /** The panchangam facts the festivals depend on, for one IST date. */
  function day(date) {
    if (cache.has(date)) return cache.get(date);
    const [y, mo, d] = date.split("-").map(Number);
    const rise = P.sunEvent(y, mo, d, PL.lat, PL.lon, true), set = P.sunEvent(y, mo, d, PL.lat, PL.lon, false);
    const t = ms => Math.floor(P.elongation(P.jdOf(ms)) / 12);
    const m = P.masaAt(rise), jd = P.jdOf(rise);
    const o = { date, y, mo, d, weekday: new Date(Date.UTC(y, mo - 1, d)).getUTCDay(), rise, set,
      rt: t(rise), mt: t((rise + set) / 2), st: t(set), nt: t((set + rise + 86400e3) / 2),   // tithi at sunrise, midday, sunset, midnight
      masa: m.adhika ? -1 : m.index, sun: P.sidereal(P.sunLon(jd), jd) };
    cache.set(date, o);
    if (cache.size > 1200) cache.delete(cache.keys().next().value);
    return o;
  }
  // Makara Sankranti: the day the Sun enters Makara (sidereal 270°); after sunset, the next day
  const sankCache = {};
  function sankranti(y) {
    if (sankCache[y]) return sankCache[y];
    const at = P.crossing(j => P.sidereal(P.sunLon(j), j), 270, Date.UTC(y, 0, 9), 12);
    let date = at ? istDate(at) : `${y}-01-14`;
    if (at && at > day(date).set) date = addDays(date, 1);
    return (sankCache[y] = date);
  }
  // Ramzan and Bakrid: the tabular Islamic calendar (the real day follows the moon sighting: it can be a day off)
  const ISL = 1948439.5;
  const islToJd = (y, m, d) => d + Math.ceil(29.5 * (m - 1)) + (y - 1) * 354 + Math.floor((3 + 11 * y) / 30) + ISL - 1;
  function islamic(date) {
    const jd = Date.parse(date + "T00:00:00Z") / 86400e3 + 2440587.5;
    const y = Math.floor((30 * (jd - ISL) + 10646) / 10631);
    const m = Math.min(12, Math.ceil((jd - (29 + islToJd(y, 1, 1))) / 29.5) + 1);
    return { y, m, d: Math.round(jd - islToJd(y, m, 1)) + 1 };
  }
  function easter(y) {
    const a = y % 19, b = Math.floor(y / 100), c = y % 100, d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25), g = Math.floor((b - f + 1) / 3);
    const h = (19 * a + b - d - g + 15) % 30, i = Math.floor(c / 4), k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = Math.floor((a + 11 * h + 22 * l) / 451);
    const n = h + l - 7 * m + 114;
    return `${y}-${String(Math.floor(n / 31)).padStart(2, "0")}-${String(n % 31 + 1).padStart(2, "0")}`;
  }
  const nthSunday = (o, month, n) => o.mo === month && o.weekday === 0 && Math.ceil(o.d / 7) === n;
  const md = (o, m, d) => o.mo === m && o.d === d;
  const prev = o => day(addDays(o.date, -1)), after = o => day(addDays(o.date, 1));
  const first = (o, test) => test(o) && !test(prev(o));        // a tithi seen on two days: the first one
  // the tithi at sunrise; a tithi that touches no sunrise at all (kshaya) belongs to the day it begins
  const tday = (o, t) => o.rt === t || (o.rt === (t + 29) % 30 && after(o).rt === (t + 1) % 30);
  const sun = (o, m, t) => first(o, x => x.masa === m && tday(x, t));
  // Holika Dahan: Purnima at sunset, once its first half (Bhadra) is over before midnight; otherwise the next evening
  function holika(o) {
    const p = prev(o);
    if (p.masa === 11 && p.st === 14 && !bhadraOk(p) && !(prev(p).masa === 11 && prev(p).st === 14)) return true;
    return o.masa === 11 && o.st === 14 && bhadraOk(o) && !(p.masa === 11 && p.st === 14 && bhadraOk(p));
  }
  function bhadraOk(o) {
    const end = P.crossing(P.elongation, 174, o.rise - 86400e3, 3);
    return !!end && end <= (o.set + after(o).rise) / 2;
  }

  // [key, Telugu, English, faith, is it today?, what the cartoon could show]
  const FESTIVALS = [
    ["bhogi", "భోగి", "Bhogi", "hindu", o => o.date === addDays(sankranti(o.y), -1), "the Bhogi bonfire at dawn (kept safe), Bhogi pallu for the children, rangoli"],
    ["sankranti", "మకర సంక్రాంతి", "Makara Sankranti", "hindu", o => o.date === sankranti(o.y), "kites, colourful muggulu (rangoli), the Haridasu singing, gobbemmalu, sweets like ariselu, a decorated Gangireddu bull"],
    ["kanuma", "కనుమ", "Kanuma", "hindu", o => o.date === addDays(sankranti(o.y), 1), "thanking and decorating the cows and bulls, a family feast"],
    ["vasant_panchami", "వసంత పంచమి", "Vasant Panchami", "hindu", o => first(o, x => x.masa === 10 && x.mt === 4), "little children starting to learn letters (aksharabhyasam), books and Saraswati"],
    ["ratha_saptami", "రథసప్తమి", "Ratha Saptami", "hindu", o => sun(o, 10, 6), "greeting the rising sun, a rangoli of a chariot"],
    ["shivaratri", "మహాశివరాత్రి", "Maha Shivaratri", "hindu", o => first(o, x => x.masa === 10 && x.nt === 28), "staying awake all night at the temple, devotional songs"],
    ["holika", "కాముని పున్నమి", "Holika Dahan", "hindu", holika, "a safe evening bonfire with the family"],
    ["holi", "హోలీ", "Holi", "hindu", o => holika(prev(o)), "playing with safe, natural colours, friends laughing"],
    ["ugadi", "ఉగాది", "Ugadi, the Telugu New Year", "hindu", o => (o.masa === 0 && o.rt === 0) || (o.rt === 29 && after(o).rt === 1 && after(o).masa === 0), "Ugadi pachadi with six tastes, mango leaves at the door, new year wishes, the panchanga sravanam"],
    ["rama_navami", "శ్రీరామ నవమి", "Sri Rama Navami", "hindu", o => first(o, x => x.masa === 0 && x.mt === 8), "panakam and vadapappu, the Sita Rama kalyanam"],
    ["mahavir", "మహావీర్ జయంతి", "Mahavir Jayanti", "jain", o => sun(o, 0, 12), "kindness to every living being"],
    ["buddha", "బుద్ధ పూర్ణిమ", "Buddha Purnima", "buddhist", o => sun(o, 1, 14), "peace, kindness and lamps"],
    ["hanuman", "హనుమాన్ జయంతి", "Hanuman Jayanti", "hindu", o => sun(o, 1, 24), "strength and courage, the children dressed up"],
    ["tholi_ekadashi", "తొలి ఏకాదశి", "Tholi Ekadashi", "hindu", o => sun(o, 3, 10), "the first big Ekadashi, a temple visit"],
    ["guru_purnima", "గురు పౌర్ణమి", "Guru Purnima", "hindu", o => sun(o, 3, 14), "thanking our teachers and elders"],
    ["varalakshmi", "వరలక్ష్మీ వ్రతం", "Varalakshmi Vratam", "hindu", o => o.masa === 4 && o.weekday === 5 && o.rt >= 8 && o.rt <= 14, "the family puja, flowers, sweets and visiting neighbours"],
    ["rakhi", "రాఖీ పౌర్ణమి", "Raksha Bandhan", "hindu", o => sun(o, 4, 14), "a sister tying a rakhi and a brother's sweet gift"],
    ["krishnashtami", "శ్రీకృష్ణ జన్మాష్టమి", "Krishnashtami", "hindu", o => sun(o, 4, 22), "little children dressed as Krishna, a pot of butter, uttiyadi"],
    ["vinayaka", "వినాయక చవితి", "Vinayaka Chavithi", "hindu", o => first(o, x => x.masa === 5 && x.mt === 3), "a cute clay Ganesha, undrallu and kudumulu sweets, a green (eco-friendly) festival"],
    ["bathukamma", "ఎంగిలిపూల బతుకమ్మ", "Bathukamma begins", "hindu", o => sun(o, 5, 29), "colourful flower stacks (Bathukamma), songs and dancing in a circle"],
    ["saddula_bathukamma", "సద్దుల బతుకమ్మ", "Saddula Bathukamma", "hindu", o => sun(o, 6, 7), "the biggest Bathukamma of flowers, songs, a family feast"],
    ["dasara", "విజయదశమి (దసరా)", "Dasara (Vijayadashami)", "hindu", o => first(o, x => x.masa === 6 && Math.floor(P.elongation(P.jdOf(x.rise + 0.7 * (x.set - x.rise))) / 12) === 9), "bommala koluvu (a doll display), new clothes, visiting family, sharing sweets"],
    ["naraka", "నరక చతుర్దశి", "Naraka Chaturdashi", "hindu", o => sun(o, 6, 28), "an early oil bath and lamps"],
    ["deepavali", "దీపావళి", "Deepavali", "hindu", o => first(o, x => x.masa === 6 && x.st === 29), "rows of diyas, sweets, rangoli, safe crackers with elders around (and kind to the animals)"],
    ["nagula_chavithi", "నాగుల చవితి", "Nagula Chavithi", "hindu", o => sun(o, 7, 3), "the family visiting the anthill with milk and flowers (never hurting snakes)"],
    ["karthika_pournami", "కార్తీక పౌర్ణమి", "Karthika Pournami", "hindu", o => sun(o, 7, 14), "lighting many lamps, little lamps floating on the water"],
    ["guru_nanak", "గురునానక్ జయంతి", "Guru Nanak Jayanti", "sikh", o => sun(o, 7, 14), "langar: everyone sharing food together"],
    ["mukkoti", "ముక్కోటి ఏకాదశి", "Mukkoti Ekadashi", "hindu", o => first(o, x => tday(x, 10) && x.sun >= 240 && x.sun < 270), "the Vaikuntha dwaram darshan at the temple"],
    ["easter", "ఈస్టర్", "Easter", "christian", o => o.date === easter(o.y), "the family going to church and sharing sweets"],
    ["christmas", "క్రిస్మస్", "Christmas", "christian", o => md(o, 12, 25), "a star, a cake, carols and gifts for everyone"],
    ["ramzan", "రంజాన్ (ఈద్)", "Ramzan (Eid ul-Fitr)", "muslim", o => { const i = islamic(o.date); return i.m === 10 && i.d === 1; }, "sheer khurma, new clothes, Eid wishes and sharing with neighbours"],
    ["bakrid", "బక్రీద్", "Bakrid (Eid ul-Adha)", "muslim", o => { const i = islamic(o.date); return i.m === 12 && i.d === 10; }, "Eid wishes and sharing food with neighbours and the poor"],
    ["new_year", "నూతన సంవత్సరం", "New Year's Day", "all", o => md(o, 1, 1), "new year wishes and funny new year promises"],
    ["republic", "గణతంత్ర దినోత్సవం", "Republic Day", "all", o => md(o, 1, 26), "the flag hoisting at school, sweets and a patriotic song"],
    ["womens_day", "మహిళా దినోత్సవం", "Women's Day", "all", o => md(o, 3, 8), "thanking and respecting the women in our lives"],
    ["mothers_day", "మాతృ దినోత్సవం", "Mother's Day", "all", o => nthSunday(o, 5, 2), "children surprising their mother (or Ammamma) with a gift"],
    ["fathers_day", "పితృ దినోత్సవం", "Father's Day", "all", o => nthSunday(o, 6, 3), "children thanking their father (or Tatayya)"],
    ["friendship", "స్నేహితుల దినోత్సవం", "Friendship Day", "all", o => nthSunday(o, 8, 1), "friendship bands and helping a friend"],
    ["independence", "స్వాతంత్ర్య దినోత్సవం", "Independence Day", "all", o => md(o, 8, 15), "the flag hoisting at school, sweets and a patriotic song"],
    ["telugu_day", "తెలుగు భాషా దినోత్సవం", "Telugu Language Day", "all", o => md(o, 8, 29), "loving our Telugu: children learning sweet Telugu words and sametalu"],
    ["teachers_day", "ఉపాధ్యాయ దినోత్సవం", "Teachers' Day", "all", o => md(o, 9, 5), "the students surprising their teacher"],
    ["childrens_day", "బాలల దినోత్సవం", "Children's Day", "all", o => md(o, 11, 14), "children having fun, games and sweets at school"]
  ].map(([key, te, en, faith, test, show]) => ({ key, te, en, faith, test, show, approx: faith === "muslim" }));

  /** The festivals on an IST date ("YYYY-MM-DD"): [{ key, te, en, faith, show, approx }]. */
  function festivalsOn(date) {
    let o;
    try { o = day(date); } catch (e) { return []; }
    const out = [];
    for (const f of FESTIVALS) { try { if (f.test(o)) out.push({ key: f.key, te: f.te, en: f.en, faith: f.faith, show: f.show, approx: f.approx, date }); } catch (e) { } }
    // Dasara navaratri: the nine nights before Vijayadashami
    if (o.masa === 6 && o.rt <= 8 && !out.some(f => f.key === "dasara")) out.push({ key: "navaratri", te: "దసరా నవరాత్రులు", en: "the Dasara Navaratri days", faith: "hindu", show: "bommala koluvu, Bathukamma flowers, family visits", season: true, date });
    return out;
  }
  /** The festivals in the next [days] days (today first): [{ ..., date, inDays }]. */
  function upcoming(days = 45, from = istDate()) {
    const out = [];
    for (let i = 0; i <= days; i++) { const d = addDays(from, i); for (const f of festivalsOn(d)) if (!f.season) out.push(Object.assign(f, { inDays: i })); }
    return out;
  }
  /** When a festival comes next (by key), within about a year: { ...festival, date, inDays } or null. */
  function next(key, from = istDate()) {
    for (let i = 0; i <= 400; i++) { const d = addDays(from, i); const f = festivalsOn(d).find(x => x.key === key); if (f) return Object.assign(f, { inDays: i }); }
    return null;
  }
  const keys = () => FESTIVALS.map(f => ({ key: f.key, te: f.te, en: f.en }));

  /** Today's festival (or tomorrow's) for the day's first cartoon: { f, when: "today" | "tomorrow" } or null. */
  function festivalNow(date = istDate()) {
    const t = festivalsOn(date).filter(f => !f.season);
    if (t.length) return { f: t[0], when: "today" };
    const m = festivalsOn(addDays(date, 1)).filter(f => !f.season);
    return m.length ? { f: m[0], when: "tomorrow" } : null;
  }

  // ---------- Google Trends: what India is searching for, as kind themes ----------
  // A search only becomes a theme when it matches one of these (anything else, and anything unkind, is left out).
  const NEVER = /\b(death|dies|died|dead|killed|murder|rape|arrest|accident|crash|blast|attack|war|riot|scam|fraud|election|vote|poll|party|minister|cm\b|pm\b|bjp|congress|tdp|ysrcp|brs|jsp|aap|court|case|police|terror|cyclone|flood|earthquake|tsunami|landslide|stampede|lottery|bet|betting|casino|gold price|petrol|stock|share price|sensex|nifty|bitcoin|crypto)\b/i;
  const THEMES = [
    ["cricket", /cricket|\bipl\b|t20|odi\b|test match|world cup|\bwpl\b|\bind\s*(vs|v)\b|\bvs\b/i, "క్రికెట్ మ్యాచ్ రోజు", "a big cricket match day: everyone is watching (never real players)"],
    ["sports", /kabaddi|football|fifa|\bisl\b|hockey|badminton|olympic|chess/i, "ఆటల రోజు", "a sports day with friends"],
    ["exams", /exam|result|results|admit card|hall ticket|answer key|\bssc\b|\binter\b|eamcet|eapcet|\bneet\b|\bjee\b|cbse|\btet\b|\bdsc\b|board/i, "పరీక్షలు, ఫలితాల రోజు", "exams and results: children studying, waiting for results"],
    ["rain", /\brain|rains|monsoon|thunder|drizzle|weather|imd\b/i, "వర్షం పడే రోజు", "a rainy day: umbrellas, paper boats, puddles (staying safe)"],
    ["heat", /heat ?wave|hot weather|temperature|summer/i, "చాలా ఎండ రోజు", "a very hot day: drinking water, buttermilk and staying in the shade"],
    ["cold", /cold wave|winter|fog/i, "చలి రోజు", "a cold winter morning: sweaters and hot tea"],
    ["holiday", /holiday|school leave|bank holiday|vacation/i, "సెలవు రోజు", "a holiday: the whole family at home"],
    ["festival", /sankranti|pongal|ugadi|navami|vinayaka|ganesh|chaturthi|bathukamma|dasara|dussehra|navratri|diwali|deepavali|karthika|christmas|easter|eid|ramzan|ramadan|bakrid|holi|rakhi|raksha|janmashtami|krishnashtami|shivaratri|onam|guru purnima/i, "పండుగ సందడి", "a festival everyone is talking about"],
    ["kind_day", /teachers'? day|children'?s day|mother'?s day|father'?s day|friendship day|women'?s day|new year/i, "ప్రత్యేక రోజు", "a special day to thank someone"],
    ["space", /isro|chandrayaan|gaganyaan|launch|rocket|eclipse|meteor|moon|supermoon|comet/i, "అంతరిక్షం గురించి", "the sky and space: rockets, the moon, an eclipse"],
    ["food", /recipe|biryani|mango|food festival/i, "రుచికరమైన వంటలు", "tasty food at home"]
  ];
  let state = { at: 0, ok: false, error: "", searches: [], themes: [] };
  function themesOf(titles) {
    const n = {};
    for (const t of titles) {
      if (!t || NEVER.test(t)) continue;
      for (const [key, re] of THEMES) if (re.test(t)) { n[key] = (n[key] || 0) + 1; break; }
    }
    return THEMES.filter(([k]) => n[k]).sort((a, b) => n[b[0]] - n[a[0]]).slice(0, 4).map(([key, , te, en]) => ({ key, te, en, count: n[key] }));
  }
  const decode = s => String(s).replace(/<!\[CDATA\[|\]\]>/g, "").replace(/&amp;/g, "&").replace(/&quot;/g, "\"").replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").trim();
  /** The searches in the feed: each title, and its news headlines too (they say what the search is about). */
  function parse(xml) {
    const out = [];
    for (const m of String(xml).matchAll(/<item>([\s\S]*?)<\/item>/g)) {
      const title = decode((m[1].match(/<title>([\s\S]*?)<\/title>/) || [])[1] || "");
      const news = [...m[1].matchAll(/<ht:news_item_title>([\s\S]*?)<\/ht:news_item_title>/g)].map(x => decode(x[1])).slice(0, 2);
      if (title) out.push({ title: title.slice(0, 100), news });
    }
    return out.slice(0, 40);
  }
  async function refresh() {
    if (!ON()) return state;
    try {
      const r = await get(URL_(), { headers: { "user-agent": "Mozilla/5.0 (compatible; HabitAI/1.0; +https://bigdai.tech)" }, signal: AbortSignal.timeout(12000) });
      if (!r.ok) throw new Error("Google Trends answered " + r.status);
      const items = parse(await r.text());
      if (!items.length) throw new Error("no searches in the feed");
      // a search counts only when it AND its news are kind (a cricket search about an accident is left out)
      const kind = items.filter(i => !NEVER.test(i.title) && !i.news.some(n => NEVER.test(n)));
      state = { at: Date.now(), ok: true, error: "", searches: items.map(i => i.title), themes: themesOf(kind.map(i => [i.title, ...i.news].join(" · "))) };
    } catch (e) {
      state = Object.assign({}, state, { at: Date.now(), ok: false, error: String(e.message || e).slice(0, 160) });
    }
    return state;
  }
  if (ON() && !fetchImpl) { setTimeout(() => refresh().catch(() => {}), 60e3).unref(); setInterval(() => refresh().catch(() => {}), 3 * 3600e3).unref(); }
  /** Today's kind themes from Google Trends (fresh for 8 hours). */
  const themes = () => (state.ok && Date.now() - state.at < 8 * 3600e3 ? state.themes : []);
  function status() {
    let soon = [];
    try { soon = upcoming(30).slice(0, 6).map(f => ({ date: f.date, te: f.te, en: f.en, approx: f.approx })); } catch (e) { }
    return { on: ON(), ok: state.ok, checked: state.at, error: state.error, themes: themes(), searches: state.searches.slice(0, 20), festivalsSoon: soon };
  }

  return { festivalsOn, upcoming, next, keys, festivalNow, themes, refresh, status, _test: { parse, themesOf, day, sankranti, islamic, easter } };
};
