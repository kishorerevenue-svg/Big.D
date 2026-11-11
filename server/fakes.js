// Habit AI: the fake messages list in the Messages tab.
// People add a fake (fraud) message they got, so others recognise it early. Before anything is shown,
// private details are hidden on the server (phone and account numbers, emails, UPI ids, the paths of links).
// Under every message anyone can tap 🚨 Fake · 📩 I got this too · ✅ Looks safe, and write a short comment
// (checked before it shows). The same message added twice becomes one, with one more "I got this too".
// Claude checks new messages and comments when it can; when it rests, the rules below still keep the list clean,
// and 3 reports hide anything until the team looks at it.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

module.exports = function makeFakes({ DATA_DIR, social, learn, MODEL, API_KEY, adminPassword, h }) {
  const { send, readBody, allowed, clientIp } = h;
  const FILE = path.join(DATA_DIR, "fakes.json");
  const KEEP_DAYS = 120, MAX_ITEMS = 2000, MAX_COMMENTS = 200;
  const ADD_PER_DAY = 5, COMMENTS_PER_DAY = 25, HIDE_AFTER = 3;
  const WHERE = ["whatsapp", "sms", "call", "email", "social", "other"];

  let db = { next: 1, items: [] };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  let timer = null;
  const save = () => { clearTimeout(timer); timer = setTimeout(() => { try { fs.writeFileSync(FILE + ".tmp", JSON.stringify(db)); fs.renameSync(FILE + ".tmp", FILE); } catch (e) { console.error("Fakes save error:", e.message); } }, 300); };
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);
  const reads = new Map(), counts = new Map();
  setInterval(() => { const now = Date.now(); for (const [k, l] of reads) if (!l.some(t => now - t < 600000)) reads.delete(k); }, 600000).unref();
  /** A per-person daily counter: true while [uid] may still do [what] today. */
  function within(what, uid, max, gapMs) {
    const k = what + "|" + uid, c = counts.get(k), now = Date.now();
    if (c && c.day === today() && c.n >= max) return "limit";
    if (c && gapMs && now - c.last < gapMs) return "wait";
    counts.set(k, { day: today(), n: (c && c.day === today() ? c.n : 0) + 1, last: now });
    return "";
  }
  function isAdmin(req) {
    const given = String(req.headers["x-admin-key"] || ""), pw = adminPassword();
    if (!pw || !given) return false;
    return crypto.timingSafeEqual(crypto.createHash("sha256").update(given).digest(), crypto.createHash("sha256").update(pw).digest());
  }

  // ---------- 1. hiding private details (always, before anyone or any AI sees the text) ----------
  const dots = n => "•".repeat(Math.max(2, n));
  function hidePrivate(raw) {
    let t = String(raw || "").replace(/\r/g, "").replace(/\u0000/g, "").replace(/[ \t]+/g, " ").replace(/\n{3,}/g, "\n\n").trim();
    const links = [], keep = [];
    const hold = s => { keep.push(s); return `\u0000${keep.length - 1}\u0000`; };   // finished pieces the next steps must not touch
    // emails and UPI ids: only the part after @ stays
    t = t.replace(/[\w.+-]{2,}@([a-z][\w-]*(?:\.[a-z]{2,})*)\b/gi, (m, d) => hold(dots(4) + "@" + d.toLowerCase()));
    // links: only the site name stays (that is what people should recognise), never the path (it can carry their details)
    t = t.replace(/(?:https?:\/\/|www\.)[^\s<>"'\u0000]+|\b(?:[a-z0-9-]+\.)+(?:com|in|net|org|xyz|link|ly|me|app|apk|info|top|site|online|club|co|io|live|shop|vip|cc|tk|ml|ga|cf|gq|ink|icu|buzz|work|click)\b(?:\/[^\s<>"'\u0000]*)?/gi, m => {
      const bare = m.replace(/^https?:\/\//i, "").replace(/^www\./i, "");
      const host = bare.split(/[/?#]/)[0].toLowerCase().replace(/[.,;:!)]+$/, "");
      if (!host.includes(".")) return m;
      links.push(host);
      return hold("🔗" + host + (bare.length > host.length + 1 ? "/…" : ""));
    });
    // money amounts and dates stay as they are (they show the trick: "you won ₹25,00,000 · last date 15-11-2026")
    t = t.replace(/(?:₹|\brs\.?|\binr\b|\brupees?\b)\s?\d[\d,]*(?:\.\d+)?|\b\d[\d,]*(?:\.\d+)?\s?(?:rupees?\b|రూపాయ\S*|\/-)/gi, hold);
    t = t.replace(/\b\d{1,2}[-/.]\d{1,2}[-/.](?:\d{4}|\d{2})\b/g, hold);
    // phone numbers, account / card / Aadhaar numbers, OTPs: only the last 2 digits stay
    t = t.replace(/(?:\+?91[\s-]?)?(?:\d[\s-]?){5,17}\d/g, m => { const d = m.replace(/\D/g, ""); return dots(d.length - 2) + d.slice(-2) + (/\s$/.test(m) ? " " : ""); });
    t = t.replace(/\b[A-Z]{5}\d{4}[A-Z]\b/g, "•••••••••");   // PAN
    for (let i = 0; i < 3 && t.includes("\u0000"); i++) t = t.replace(/\u0000(\d+)\u0000/g, (m, n) => keep[Number(n)]);
    return { text: t.slice(0, 1200), links: [...new Set(links)].slice(0, 5) };
  }

  // ---------- 2. what kind of fraud is it (rules; Claude's own words when it can) ----------
  const KINDS = [
    ["kyc", "🏦", "Bank KYC / account block fraud", "బ్యాంక్ KYC / ఖాతా బ్లాక్ మోసం", /\bkyc\b|account (?:is |will be )?(?:block|suspend|freez|deactiv)|pan (?:card )?(?:link|update)|కేవైసీ|ఖాతా.{0,12}(?:బ్లాక్|నిలిపి)|అకౌంట్.{0,12}బ్లాక్/i],
    ["power", "⚡", "Electricity bill / power cut fraud", "కరెంట్ బిల్లు / కరెంట్ కట్ మోసం", /electricity|power (?:will be )?(?:cut|disconnect)|current bill|bijli|కరెంట్|విద్యుత్/i],
    ["arrest", "👮", "Fake police / digital arrest", "నకిలీ పోలీస్ / డిజిటల్ అరెస్ట్", /digital arrest|\bcbi\b|\bed\b officer|narcotics|police (?:case|complaint|station)|arrest warrant|money laundering|అరెస్ట్|పోలీస్|వారెంట్/i],
    ["parcel", "📦", "Courier / parcel / customs fraud", "కొరియర్ / పార్సెల్ మోసం", /courier|parcel|fedex|dhl|blue ?dart|customs|india post|delivery (?:failed|pending)|కొరియర్|పార్సెల్/i],
    ["lottery", "🎁", "Lottery / prize / gift fraud", "లాటరీ / బహుమతి మోసం", /lottery|you (?:have )?won|winner|prize|lucky draw|gift card|reward points?|cash ?back of|kbc|లాటరీ|బహుమతి|గెలుచుకున్నారు|ప్రైజ్/i],
    ["job", "💼", "Job / part-time task fraud", "ఉద్యోగం / పార్ట్ టైమ్ టాస్క్ మోసం", /part[ -]?time|work from home|daily (?:income|earn|salary)|earn ₹?\s?\d|like (?:videos|youtube)|rating task|hotel review|job offer|hiring|telegram task|ఉద్యోగం|ఇంటి నుంచే పని|రోజుకు.{0,10}సంపాద/i],
    ["loan", "💸", "Instant loan fraud", "ఇన్‌స్టంట్ లోన్ మోసం", /loan (?:is )?(?:approved|sanction|offer)|instant loan|pre-?approved|without documents|లోన్|రుణం/i],
    ["invest", "📈", "Investment / trading / crypto fraud", "పెట్టుబడి / ట్రేడింగ్ మోసం", /invest|trading|stock tips|crypto|bitcoin|double your money|guaranteed returns?|ipo allot|పెట్టుబడి|ట్రేడింగ్|రెట్టింపు/i],
    ["otp", "🔢", "OTP / code sharing fraud", "OTP చెప్పమనే మోసం", /\botp\b|one time password|verification code|share the code|ఓటీపీ|కోడ్ చెప్ప/i],
    ["upi", "💳", "UPI / payment fraud", "UPI / పేమెంట్ మోసం", /\bupi\b|collect request|scan (?:this|the) qr|paid by mistake|sent by mistake|refund (?:of|is)|payment (?:failed|pending)|పొరపాటున.{0,12}(?:పంపా|వేశా)|రీఫండ్/i],
    ["apk", "📲", "Fake app (APK) fraud", "నకిలీ యాప్ (APK) మోసం", /\.apk\b|download (?:this|the) app|install (?:this|the) app|apk ఫైల్|యాప్ డౌన్‌లోడ్/i],
    ["govt", "🏛️", "Fake government scheme", "నకిలీ ప్రభుత్వ పథకం", /pm[ -]?kisan|scheme|yojana|subsidy|ration card|aadhaar (?:update|link|block)|govt\.? (?:benefit|grant)|పథకం|రేషన్|సబ్సిడీ|ఆధార్/i],
    ["family", "🆘", "Fake relative / friend asking money", "బంధువు / స్నేహితుడి పేరుతో డబ్బు అడిగే మోసం", /new number|this is my new|(?:urgent|emergency).{0,30}(?:money|send|transfer)|accident.{0,30}(?:money|send)|కొత్త నంబర్|అత్యవసరం.{0,20}డబ్బు/i],
  ];
  const URGENT = /urgent|immediately|today itself|within 24 hours|last (?:date|chance|warning)|final notice|tonight|click (?:here|the link|below)|verify now|update now|ఈ రోజే|వెంటనే|చివరి అవకాశం|క్లిక్ చేయండి/i;
  function kindOf(text, links) {
    let best = null, score = 0;
    for (const k of KINDS) if (k[4].test(text)) { score++; if (!best) best = k; }
    if (links.length) score++;
    if (URGENT.test(text)) score++;
    return { kind: best ? best[0] : "other", emoji: best ? best[1] : "⚠️", en: best ? best[2] : "Suspicious message", te: best ? best[3] : "అనుమానాస్పద మెసేజ్", score };
  }

  // ---------- 3. what may not be shown ----------
  const RUDE = /\b(?:f+u+c+k+|sh[i1]t|bitch|bastard|asshole|madarchod|bhenchod|chutiya|randi|lanja|dengu|dengey|puku|modda|gudda|sulli|nakodaka)\w*/i;
  const RUDE_TE = /లంజ|దెంగ|పూకు|మొడ్డ|గుద్ద|నా కొడకా|ముండ/;
  function rude(t) { return RUDE.test(t) || RUDE_TE.test(t); }
  function commentProblem(text) {
    if (text.length < 2) return "Write a little more.";
    if (rude(text)) return "Please keep it kind. Comments with bad words can't be shown.";
    if (/https?:\/\/|www\.|\b[a-z0-9-]+\.(?:com|in|net|org|xyz|link|ly|me|app|apk)\b/i.test(text)) return "Links can't go in comments.";
    if (/(?:\+?91[\s-]?)?[6-9](?:[\s-]?\d){9}\b|\b\d{4}\s?\d{4}\s?\d{4}\b|\d{6,}/.test(text)) return "Please don't write phone, account or ID numbers in comments.";
    if (/@[a-z0-9-]+(?:\.[a-z]+)?/i.test(text)) return "Please don't write emails or UPI ids in comments.";
    return "";
  }
  const ADD_PROMPT = `People add fake (fraud) messages they got to a public list in a Telugu family app, so others recognise them. Private details were already hidden with •••.
Decide if this text is a scam / fraud / fake message (SMS, WhatsApp, email, or the words of a fake phone call), and describe it.
Not allowed in the list: things that are not a message someone got (opinions, chats, news, ads for this app), naming or accusing a private person, abuse, bad words, sexual content, hate.
Reply with only JSON: {"ok":true|false,"reason":"<if not ok: one short kind sentence in the text's language>","fake":true|false,"level":"danger"|"careful","emoji":"<one emoji>","en":"<3 to 6 words: the kind of fraud>","te":"<the same in simple Telugu script>","text":"<the same text, with names of private persons replaced by [name] and house addresses by [address]; change nothing else>"}`;
  const COMMENT_PROMPT = `You check a short public comment under a fake (fraud) message in a family app used by all ages in India (Telugu and English).
Allow: "I got this too", where or how it came, warnings, advice, questions, polite talk. Reject: abuse, bad words, hate, sexual content, threats, personal details, links, spam or ads, naming or accusing a private person.
Reply with only JSON: {"ok":true|false,"reason":"<if rejected: one short kind sentence in the comment's language>"}`;
  async function askClaude(system, content, maxTokens) {
    if (!API_KEY || !learn || (learn.claudeOut && learn.claudeOut())) return null;
    try {
      const out = await learn.callClaude({ system, messages: [{ role: "user", content }], maxTokens, search: false, model: MODEL });
      const t = String(out.text || ""), a = t.indexOf("{"), b = t.lastIndexOf("}");
      return a >= 0 && b > a ? JSON.parse(t.slice(a, b + 1)) : null;
    } catch (e) { return null; } // resting or busy: the rules and the people's reports still protect the list
  }

  // ---------- 4. the same message added again becomes one ----------
  const words = t => new Set(String(t).toLowerCase().replace(/[•🔗]/g, " ").split(/[^\p{L}\p{N}]+/u).filter(w => w.length > 2));
  function sameAs(text) {
    const a = words(text);
    if (a.size < 3) return null;
    let best = null, bestS = 0;
    for (const it of db.items) {
      if (it.removed) continue;
      const b = it.w ? new Set(it.w) : words(it.text);
      let both = 0; for (const x of a) if (b.has(x)) both++;
      const s = both / (a.size + b.size - both);
      if (s > bestS) { bestS = s; best = it; }
    }
    return bestS >= 0.72 ? best : null;
  }

  // ---------- views ----------
  const tally = it => { const c = { fake: 0, got: 0, safe: 0 }; for (const v of Object.values(it.votes || {})) if (c[v] != null) c[v]++; return c; };
  function commentView(c, uid, admin) {
    const p = social.person(c.uid);
    return { id: c.id, name: p ? p.name : "Habit friend", emoji: p ? p.emoji : "🐾", text: c.text, at: c.at, mine: !!uid && c.uid === uid, hidden: admin ? !!c.hidden : undefined };
  }
  function view(it, uid, admin, lang) {
    const c = tally(it), shown = (it.comments || []).filter(x => !x.removed && (admin || !x.hidden));
    return {
      id: it.id, emoji: it.emoji, title: lang === "en" ? it.en : it.te, titleEn: it.en, titleTe: it.te, level: it.level, kind: it.kind,
      text: it.text, where: it.where, at: it.at, seen: it.seen || it.at, links: it.links || [],
      fake: c.fake, got: c.got, safe: c.safe, my: (uid && it.votes && it.votes[uid]) || "",
      comments: shown.length, last: shown.slice(-2).map(x => commentView(x, uid, admin)),
      mine: !!uid && it.uid === uid, hidden: admin ? !!it.hidden : undefined, checked: !!it.ai,
    };
  }
  const visible = (it, admin) => !it.removed && (admin || !it.hidden);
  const hot = it => { const c = tally(it); return c.got * 2 + c.fake - c.safe * 2 + (it.comments || []).length * 0.5; };

  function tidy() {
    const cut = Date.now() - KEEP_DAYS * 86400e3;
    const before = db.items.length;
    db.items = db.items.filter(it => !it.removed && ((it.seen || it.at) > cut || hot(it) >= 20));
    if (db.items.length > MAX_ITEMS) db.items = db.items.sort((a, b) => (b.seen || b.at) - (a.seen || a.at)).slice(0, MAX_ITEMS);
    if (db.items.length !== before) save();
  }
  tidy();
  setInterval(tidy, 6 * 3600e3).unref();

  // ---------- adding ----------
  async function add(body, uid) {
    const where = WHERE.includes(body.where) ? body.where : "other";
    const raw = str(body.text, 3000);
    const { text, links } = hidePrivate(raw);
    if (text.replace(/[•🔗\s]/g, "").length < 12 || text.split(/\s+/).length < 3) return { status: 400, error: "Please paste the whole message (a few words at least)." };
    if (rude(text)) return { status: 400, error: "This can't be shown: it has bad words." };
    const k = kindOf(text, links);
    const same = sameAs(text);
    if (same) {
      same.votes = same.votes || {};
      if (!same.votes[uid]) same.votes[uid] = "got";
      same.seen = Date.now(); save();
      return { status: 200, merged: true, item: same };
    }
    const aiCan = !!API_KEY && !!learn && !(learn.claudeOut && learn.claudeOut());
    const notFake = "This doesn't look like a fake message. Check it above with 🛡️ Check it, or add the whole message as you got it.";
    if (!aiCan && k.score < 1) return { status: 400, error: notFake };
    const lim = within("add", uid, ADD_PER_DAY, 20_000);
    if (lim) return { status: 429, error: lim === "wait" ? "Please wait a little before adding another one." : `You can add ${ADD_PER_DAY} messages a day. Thank you for warning others!` };
    const ai = aiCan ? await askClaude(ADD_PROMPT, `Where it came: ${where}\nText:\n<<<\n${text}\n>>>`, 700) : null;
    if (ai && ai.ok === false) return { status: 400, error: str(ai.reason, 200) || "This can't go in the fake messages list." };
    if (ai && ai.fake === false) return { status: 400, error: notFake };
    if (!ai && k.score < 1) return { status: 400, error: notFake };
    let finalText = text;
    if (ai && typeof ai.text === "string" && ai.text.trim().length >= text.length * 0.6) finalText = hidePrivate(ai.text).text; // names and addresses hidden by Claude
    const it = {
      id: db.next++, uid, at: Date.now(), seen: Date.now(), where, kind: k.kind,
      emoji: (ai && str(ai.emoji, 4)) || k.emoji, en: (ai && str(ai.en, 60)) || k.en, te: (ai && str(ai.te, 80)) || k.te,
      level: ai ? (ai.level === "careful" ? "careful" : "danger") : (k.score >= 2 ? "danger" : "careful"),
      text: finalText, links, w: [...words(finalText)].slice(0, 80), votes: { [uid]: "fake" }, comments: [], reports: [], ai: !!ai,
    };
    db.items.push(it); save();
    return { status: 200, merged: false, item: it };
  }

  // ---------- router ----------
  const handle = async function handle(req, res, url, origin) {
    const p = url.pathname;
    if (!p.startsWith("/api/fakes")) return false;
    const uid = social.who(req) || "";
    const admin = isAdmin(req);
    const lang = url.searchParams.get("lang") === "en" ? "en" : "te";
    try {
      if (req.method === "GET" && p === "/api/fakes") {
        if (!allowed("fk:" + clientIp(req), reads, 300)) { send(res, 429, { error: "Too many requests." }, origin); return true; }
        const sort = url.searchParams.get("sort") === "top" ? "top" : "latest";
        const from = Math.max(0, Number(url.searchParams.get("from")) || 0), n = Math.min(30, Number(url.searchParams.get("n")) || 10);
        let list = db.items.filter(it => visible(it, admin));
        if (sort === "top") { const cut = Date.now() - 60 * 86400e3; list = list.filter(it => (it.seen || it.at) > cut).sort((a, b) => hot(b) - hot(a) || b.at - a.at); }
        else list = list.sort((a, b) => (b.seen || b.at) - (a.seen || a.at));
        send(res, 200, { items: list.slice(from, from + n).map(it => view(it, uid, admin, lang)), more: list.length > from + n, total: list.length, admin }, origin);
        return true;
      }
      if (req.method === "GET" && p === "/api/fakes/comments") {
        if (!allowed("fk:" + clientIp(req), reads, 300)) { send(res, 429, { error: "Too many requests." }, origin); return true; }
        const it = db.items.find(x => x.id === Number(url.searchParams.get("id")) && visible(x, admin));
        if (!it) { send(res, 404, { error: "That message is not in the list any more." }, origin); return true; }
        send(res, 200, { comments: (it.comments || []).filter(x => !x.removed && (admin || !x.hidden)).map(x => commentView(x, uid, admin)) }, origin);
        return true;
      }
      if (req.method !== "POST") { send(res, 404, { error: "Not found" }, origin); return true; }
      if (!allowed(clientIp(req))) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
      let body;
      try { body = await readBody(req, 64 * 1024); } catch (e) { send(res, 400, { error: "Bad request." }, origin); return true; }
      if (!uid && !admin) { send(res, 403, { error: "Make your Habit profile first: Messages › 💬 Habit Chat.", needProfile: true }, origin); return true; }

      if (p === "/api/fakes/add") {
        const r = await add(body, uid || "team");
        if (r.status !== 200) { send(res, r.status, { error: r.error }, origin); return true; }
        send(res, 200, { merged: r.merged, item: view(r.item, uid, admin, lang) }, origin); return true;
      }
      const it = db.items.find(x => x.id === Number(body.id) && visible(x, admin));
      if (!it) { send(res, 404, { error: "That message is not in the list any more." }, origin); return true; }

      if (p === "/api/fakes/vote") {
        const v = ["fake", "got", "safe"].includes(body.v) ? body.v : "";
        it.votes = it.votes || {};
        if (!v || it.votes[uid] === v) delete it.votes[uid]; else it.votes[uid] = v;
        if (v === "got") it.seen = Date.now();   // came again today: it moves up in "Latest"
        save();
        const c = tally(it);
        send(res, 200, { fake: c.fake, got: c.got, safe: c.safe, my: it.votes[uid] || "" }, origin); return true;
      }
      if (p === "/api/fakes/comment") {
        const text = str(body.text, 200).replace(/\s+/g, " ");
        const bad = commentProblem(text);
        if (bad) { send(res, 400, { error: bad }, origin); return true; }
        if (!social.person(uid)) { send(res, 403, { error: "Make your Habit profile first: Messages › 💬 Habit Chat.", needProfile: true }, origin); return true; }
        const lim = within("comment", uid, COMMENTS_PER_DAY, 10_000);
        if (lim) { send(res, 429, { error: lim === "wait" ? "Please wait a few seconds." : "That's enough comments for today. Thank you!" }, origin); return true; }
        const ai = await askClaude(COMMENT_PROMPT, `Fake message: ${it.en}\nComment:\n<<<\n${text}\n>>>`, 150);
        if (ai && ai.ok === false) { send(res, 400, { error: str(ai.reason, 200) || "This comment can't be shown. Please keep it kind and safe." }, origin); return true; }
        it.comments = it.comments || [];
        const c = { id: crypto.randomBytes(5).toString("hex"), uid, text, at: Date.now(), reports: [] };
        it.comments.push(c);
        if (it.comments.length > MAX_COMMENTS) it.comments = it.comments.slice(-MAX_COMMENTS);
        save();
        send(res, 200, { comment: commentView(c, uid, admin), comments: it.comments.filter(x => !x.removed && !x.hidden).length }, origin); return true;
      }
      if (p === "/api/fakes/report") {
        const target = body.cid ? (it.comments || []).find(x => x.id === String(body.cid)) : it;
        if (!target) { send(res, 404, { error: "Not found" }, origin); return true; }
        target.reports = target.reports || [];
        if (target.uid !== uid && !target.reports.includes(uid)) target.reports.push(uid);
        if (target.reports.length >= HIDE_AFTER) target.hidden = true;   // hidden until the team looks at it
        save(); send(res, 200, { ok: true }, origin); return true;
      }
      if (p === "/api/fakes/delete") {
        const target = body.cid ? (it.comments || []).find(x => x.id === String(body.cid)) : it;
        if (!target) { send(res, 404, { error: "Not found" }, origin); return true; }
        if (target.uid !== uid && !admin) { send(res, 403, { error: "You can delete only what you added." }, origin); return true; }
        target.removed = true; save(); send(res, 200, { ok: true }, origin); return true;
      }
      if (p === "/api/fakes/restore") {
        if (!admin) { send(res, 401, { error: "Wrong team password." }, origin); return true; }
        const target = body.cid ? (it.comments || []).find(x => x.id === String(body.cid)) : it;
        if (target) { target.hidden = false; target.reports = []; save(); }
        send(res, 200, { ok: true }, origin); return true;
      }
      send(res, 404, { error: "Not found" }, origin); return true;
    } catch (e) {
      console.error("Fakes error:", p, e.message);
      send(res, 500, { error: "Something went wrong. Please try again." }, origin); return true;
    }
  };

  function stats() {
    const live = db.items.filter(it => !it.removed);
    return { items: live.length, hidden: live.filter(it => it.hidden).length, comments: live.reduce((n, it) => n + (it.comments || []).filter(c => !c.removed).length, 0),
      votes: live.reduce((n, it) => n + Object.keys(it.votes || {}).length, 0), today: live.filter(it => it.at > Date.now() - 86400e3).length };
  }
  return { handle, stats, _test: { hidePrivate, kindOf, sameAs, commentProblem } };
};
