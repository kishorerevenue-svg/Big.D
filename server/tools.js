// BigD AI tool pages:
//  1. Translate: Telugu, English and Hindi, from typed or spoken text or from a photo (signboards, letters, notices).
//  2. Govt services: a list of official websites for common services (AP, Telangana, all India),
//     and answers to questions using ONLY official government websites (gov.in, nic.in), with links.
// BigD AI is not a government app; every answer says to check the official site.
module.exports = function makeTools({ learn, API_KEY, MODEL, h }) {
  const { send, readBody, allowed, clientIp } = h;
  const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
  const hits = new Map();   // its own 10-minute counter, so translating never uses up chat messages
  setInterval(() => { const now = Date.now(); for (const [k, l] of hits) if (!l.some(t => now - t < 600000)) hits.delete(k); }, 600000).unref();
  const today = () => new Date(Date.now() + 5.5 * 3600e3).toISOString().slice(0, 10);
  const device = req => String(req.headers["x-device"] || "").replace(/[^\w-]/g, "").slice(0, 64) || "ip:" + clientIp(req);
  function counter(perDevice, total) {
    let c = { day: "", dev: new Map(), total: 0 };
    return req => {
      if (c.day !== today()) c = { day: today(), dev: new Map(), total: 0 };
      const d = device(req), n = c.dev.get(d) || 0;
      if (n >= perDevice || c.total >= total) return false;
      c.dev.set(d, n + 1); c.total++; return true;
    };
  }
  function parseJson(text) {
    try { return JSON.parse(text); } catch (e) { }
    const a = String(text).indexOf("{"), b = String(text).lastIndexOf("}");
    if (a >= 0 && b > a) { try { return JSON.parse(text.slice(a, b + 1)); } catch (e) { } }
    return null;
  }

  // ============ 1. translate ============
  const LANGS = { te: "Telugu (in Telugu script)", en: "English", hi: "Hindi (in Devanagari script)" };
  const translateOk = counter(Number(process.env.TRANSLATE_PER_DEVICE_DAILY || 150), Number(process.env.TRANSLATE_DAILY_TOTAL || 6000));
  const TRANSLATE = to => `You translate for ordinary people in India. Translate into ${LANGS[to]}.
- Keep the meaning, tone and politeness. Use simple, natural words that people really say.
- Keep names, numbers, dates, money amounts, phone numbers and links exactly as they are.
- If a photo is given: first read ALL the text in it (in any language), then translate that text. If the photo has no text, say so in one short sentence in ${LANGS[to]}.
- Never add explanations or notes.
Reply with only a JSON object: {"from":"<the original language in English, e.g. Telugu>","original":"<only for photos: the text you read>","text":"<the translation>"}`;

  async function translate(body) {
    const to = LANGS[body.to] ? body.to : "en";
    const text = str(body.text, 4000);
    const img = body.image && typeof body.image === "object" ? body.image : null;
    let content;
    if (img) {
      const type = ["image/jpeg", "image/png", "image/webp"].includes(img.mediaType) ? img.mediaType : "image/jpeg";
      const data = String(img.data || "");
      if (data.length < 1000 || data.length > 4 * 1024 * 1024) return { status: 400, error: "Please choose a clear photo." };
      content = [{ type: "image", source: { type: "base64", media_type: type, data } }, { type: "text", text: text ? `Translate the text in this photo. Note from the person: ${text}` : "Translate the text in this photo." }];
    } else {
      if (!text) return { status: 400, error: "Type or say something to translate first." };
      content = `<<<\n${text}\n>>>`;
    }
    const out = await learn.callClaude({ system: TRANSLATE(to), messages: [{ role: "user", content }], maxTokens: 3000, search: false, model: MODEL });
    const o = parseJson(out.text) || {};
    const result = str(o.text || (o.from ? "" : out.text), 8000);
    if (!result) return { status: 502, error: "Couldn't translate that. Please try again." };
    return { status: 200, from: str(o.from, 40), original: str(o.original, 6000), text: result };
  }

  // ============ 2. govt services ============
  // Official websites only (checked November 2026). "letter" = a request for the BigD letter helper.
  const SERVICES = [
    // Andhra Pradesh
    { st: "ap", cat: "cert", te: "ఆదాయ, కుల, నివాస సర్టిఫికెట్లు", en: "Income, caste and residence certificates", where: "Your Grama / Ward Sachivalayam, or online on AP Seva", url: "https://vswsonline.ap.gov.in", letter: "Application to the Tahsildar for an income certificate" },
    { st: "ap", cat: "cert", te: "మీసేవ సేవలు", en: "MeeSeva services", where: "A MeeSeva centre, or online", url: "https://ap.meeseva.gov.in" },
    { st: "ap", cat: "land", te: "అడంగల్, 1-B భూమి రికార్డులు", en: "Adangal and 1-B land records", where: "See and download online on Meebhoomi", url: "https://meebhoomi.ap.gov.in" },
    { st: "ap", cat: "land", te: "EC, రిజిస్ట్రేషన్, మార్కెట్ విలువ", en: "Encumbrance certificate (EC), registration and market value", where: "Online on IGRS AP, or the Sub-Registrar office", url: "https://registration.ap.gov.in" },
    { st: "ap", cat: "welfare", te: "NTR భరోసా పెన్షన్ స్థితి", en: "NTR Bharosa pension status", where: "Apply at your Sachivalayam; check the status online", url: "https://sspensions.ap.gov.in" },
    { st: "ap", cat: "welfare", te: "రైస్ కార్డ్ (రేషన్) సేవలు", en: "Rice card (ration) services", where: "Your Grama / Ward Sachivalayam, or online on AP Seva", url: "https://vswsonline.ap.gov.in" },
    { st: "ap", cat: "complaint", te: "ఫిర్యాదు: PGRS / మీకోసం", en: "Public grievance: PGRS / Meekosam", where: "Online, or the Monday grievance meeting at your Collectorate / Mandal office", url: "https://meekosam.ap.gov.in", letter: "Complaint letter to the District Collector about a pending problem" },
    // Telangana
    { st: "ts", cat: "cert", te: "మీసేవ సర్టిఫికెట్లు (ఆదాయ, కుల, నివాస)", en: "MeeSeva certificates (income, caste, residence)", where: "A MeeSeva centre, or online", url: "https://ts.meeseva.telangana.gov.in", letter: "Application to the Tahsildar for an income certificate" },
    { st: "ts", cat: "welfare", te: "ఆసరా పెన్షన్", en: "Aasara pension", where: "Your Gram Panchayat / municipal office; check online", url: "http://www.aasara.telangana.gov.in" },
    { st: "ts", cat: "health", te: "ఆరోగ్యశ్రీ", en: "Aarogyasri health scheme", where: "Network hospitals; card details online", url: "https://aarogyasri.telangana.gov.in" },
    // all of India
    { st: "in", cat: "id", te: "ఆధార్ అప్‌డేట్, డౌన్‌లోడ్", en: "Aadhaar update and download", where: "Online, or an Aadhaar centre. Never share your Aadhaar OTP with anyone", url: "https://myaadhaar.uidai.gov.in" },
    { st: "in", cat: "id", te: "పాన్ కార్డ్ (ఉచిత e-PAN)", en: "PAN card (free instant e-PAN)", where: "Online on the Income Tax website", url: "https://www.incometax.gov.in" },
    { st: "in", cat: "id", te: "ఓటర్ ఐడి", en: "Voter ID (new, change, download)", where: "Online on the Voters' Service Portal", url: "https://voters.eci.gov.in" },
    { st: "in", cat: "id", te: "పాస్‌పోర్ట్", en: "Passport", where: "Apply online, then visit a Passport Seva Kendra", url: "https://www.passportindia.gov.in" },
    { st: "in", cat: "id", te: "డ్రైవింగ్ లైసెన్స్", en: "Driving licence", where: "Apply online on Sarathi, then the RTO office", url: "https://sarathi.parivahan.gov.in" },
    { st: "in", cat: "id", te: "డిజిలాకర్ (మీ పత్రాలు ఆన్‌లైన్‌లో)", en: "DigiLocker (your documents online)", where: "Online, with your Aadhaar", url: "https://www.digilocker.gov.in" },
    { st: "in", cat: "cert", te: "జనన, మరణ ధృవపత్రాలు", en: "Birth and death certificates", where: "Your Sachivalayam, panchayat or municipality; some places online", url: "https://crsorgi.gov.in", letter: "Application for a birth certificate" },
    { st: "in", cat: "farm", te: "పీఎం కిసాన్", en: "PM-KISAN", where: "Online status and e-KYC", url: "https://pmkisan.gov.in" },
    { st: "in", cat: "farm", te: "పంట బీమా (PMFBY)", en: "Crop insurance (PMFBY)", where: "Bank, CSC or online", url: "https://pmfby.gov.in" },
    { st: "in", cat: "health", te: "ఆయుష్మాన్ భారత్ (PM-JAY)", en: "Ayushman Bharat (PM-JAY)", where: "Online, CSC or a network hospital", url: "https://pmjay.gov.in" },
    { st: "in", cat: "welfare", te: "ఈ-శ్రమ్ కార్డు (కార్మికులు)", en: "e-Shram card (workers)", where: "Online, or a CSC", url: "https://eshram.gov.in" },
    { st: "in", cat: "welfare", te: "స్కాలర్‌షిప్స్ (NSP)", en: "Scholarships (National Scholarship Portal)", where: "Online", url: "https://scholarships.gov.in" },
    { st: "in", cat: "complaint", te: "కేంద్ర ప్రభుత్వ ఫిర్యాదులు (CPGRAMS)", en: "Central government grievances (CPGRAMS)", where: "Online", url: "https://pgportal.gov.in", letter: "Complaint letter about a central government service" },
    { st: "in", cat: "complaint", te: "వినియోగదారుల ఫిర్యాదు (1915)", en: "Consumer complaint (helpline 1915)", where: "Online or call 1915", url: "https://consumerhelpline.gov.in" },
    { st: "in", cat: "complaint", te: "సైబర్ మోసం ఫిర్యాదు (1930)", en: "Cyber fraud complaint (helpline 1930)", where: "Online or call 1930 at once", url: "https://cybercrime.gov.in" }
  ];
  const CATS = [
    { key: "cert", te: "సర్టిఫికెట్లు", en: "Certificates", icon: "📜" }, { key: "land", te: "భూమి, ఆస్తి", en: "Land and property", icon: "🏡" },
    { key: "id", te: "గుర్తింపు కార్డులు", en: "ID cards", icon: "🪪" }, { key: "welfare", te: "పెన్షన్లు, సంక్షేమం", en: "Pensions and welfare", icon: "🤝" },
    { key: "farm", te: "రైతులు", en: "Farmers", icon: "🌾" }, { key: "health", te: "ఆరోగ్యం", en: "Health", icon: "🩺" },
    { key: "complaint", te: "ఫిర్యాదులు", en: "Complaints", icon: "📣" }
  ];

  const OFFICIAL = ["ap.gov.in", "telangana.gov.in", "india.gov.in", "uidai.gov.in", "incometax.gov.in", "eci.gov.in", "passportindia.gov.in",
    "parivahan.gov.in", "digilocker.gov.in", "pmkisan.gov.in", "pmfby.gov.in", "pmjay.gov.in", "nha.gov.in", "eshram.gov.in", "scholarships.gov.in",
    "pgportal.gov.in", "consumerhelpline.gov.in", "cybercrime.gov.in", "crsorgi.gov.in", "meeseva.gov.in", "ap.nic.in", "telangana.nic.in", "epfindia.gov.in"];
  const askOk = counter(Number(process.env.GOVT_PER_DEVICE_DAILY || 20), Number(process.env.GOVT_DAILY_TOTAL || 800));
  const ASK = (state, lang) => `You help ordinary people in India, many of them elderly or new to smartphones, with government services. The person lives in ${state === "ts" ? "Telangana" : state === "ap" ? "Andhra Pradesh" : "India"}.
Search ONLY official government websites and answer from what you find there.
Answer in ${lang === "te" ? "simple Telugu script" : lang === "en" ? "simple English" : "the language the person used (Telugu script if they wrote Telugu, otherwise simple English)"}, short and practical:
1. What it is, in one line. 2. Who can apply. 3. Where to apply (the official website, or the office: Grama/Ward Sachivalayam, MeeSeva, Tahsildar office, etc.). 4. Documents usually asked for. 5. Fee and time, ONLY if the official site says so. 6. The official link.
Rules: never make up fees, dates, document lists or links; if the official sites don't say, tell them to ask at the office or the helpline. Never ask for Aadhaar numbers, OTPs or bank details. Warn them not to pay middlemen. End with one short line saying BigD AI is not a government service and they should check the official website.`;

  async function ask(body) {
    const q = str(body.question, 600);
    if (q.length < 4) return { status: 400, error: "Type or say your question first." };
    const state = ["ap", "ts"].includes(body.state) ? body.state : "";
    const call = domains => learn.callClaude({ system: ASK(state, str(body.lang, 4)), messages: [{ role: "user", content: q }], maxTokens: 1500, search: true,
      domains, maxUses: 3, maxSources: 4, model: MODEL });
    let out;
    try { out = await call(["gov.in", "nic.in"]); }
    catch (e) {
      // if the search service doesn't accept "gov.in" as a whole, use the main official sites by name
      if (e.status !== 400) throw e;
      out = await call(OFFICIAL);
    }
    if (!out.text) return { status: 502, error: "Couldn't find an answer right now. Please try again." };
    return { status: 200, text: str(out.text, 6000), sources: out.sources.filter(s => /^https:\/\/[^/]*\.(gov|nic)\.in(\/|$)/.test(s.url)) };
  }

  // ============ router ============
  return async function handle(req, res, url, origin) {
    const p = url.pathname;
    if (p !== "/api/translate" && !p.startsWith("/api/govt/")) return false;
    if (req.method === "GET" && p === "/api/govt/services") { send(res, 200, { cats: CATS, services: SERVICES }, origin); return true; }
    if (req.method !== "POST") { send(res, 404, { error: "Not found" }, origin); return true; }
    if (!allowed(clientIp(req), hits, 60)) { send(res, 429, { error: "Too many requests. Please wait a few minutes." }, origin); return true; }
    let body;
    try { body = await readBody(req, 6 * 1024 * 1024); } catch (e) { send(res, 400, { error: e.message === "too_large" ? "That photo is too big." : "Bad request." }, origin); return true; }
    if (!API_KEY) { send(res, 500, { error: "Server has no API key yet." }, origin); return true; }
    try {
      if (p === "/api/translate") {
        if (!translateOk(req)) { send(res, 429, { error: "You've reached today's translation limit. Please try again tomorrow." }, origin); return true; }
        const r = await translate(body);
        send(res, r.status, r.status === 200 ? { from: r.from, original: r.original, text: r.text } : { error: r.error }, origin); return true;
      }
      if (p === "/api/govt/ask") {
        if (!askOk(req)) { send(res, 429, { error: "You've reached today's limit for questions. Please try again tomorrow." }, origin); return true; }
        const r = await ask(body);
        send(res, r.status, r.status === 200 ? { text: r.text, sources: r.sources } : { error: r.error }, origin); return true;
      }
      send(res, 404, { error: "Not found" }, origin); return true;
    } catch (e) {
      console.error("Tools error:", p, e.status || "", e.message);
      send(res, 502, { error: "The AI is busy right now. Please try again." }, origin); return true;
    }
  };
};
