// Habit's self brain: it answers by itself, at once, without any AI model, from what Habit already knows and learns
// every day. It answers (in Telugu or English) when Claude can't: this month's limit is used up, there's no credit,
// Claude is down, or the person's free messages for today are used up.
//  - a little kind chat (hello, how are you, thanks, who are you, what can you do…), and care for someone who is sad
//  - today's panchangam and the festivals (panchang.js, trends.js)
//  - Habit's Media posts, which come from the internet every day: news, movies, jobs, farming and mandi prices,
//    devotional, health tips, kids stories, facts, jokes and riddles
//  - the answers it learned from Claude (answers.js) and the owner's knowledge library (the Teach page)
//  - the weather (Open-Meteo) and Wikipedia
//  - a good-morning briefing (panchangam, festival, weather, the person's reminders and to-dos)
// Reminders, alarms, calls and apps are done by the phone's own small brain (LocalBrain.kt), as always.
const P = require("./panchang.js");

module.exports = function makeSelfBrain({ media, answers, learn, trends, PETS = {}, EMOTIONS = [], fetchImpl }) {
  const get = (u, ms = 6000) => (fetchImpl || fetch)(u, { headers: { "user-agent": "Habit-AI/1.0 (self brain; https://bigdai.tech)" }, signal: AbortSignal.timeout(ms) });
  const json = async (u, ms) => { const r = await get(u, ms); if (!r.ok) throw new Error("http " + r.status); return r.json(); };
  const stats = { answered: 0, weak: 0, skills: {} };
  const istDate = (ms = Date.now()) => new Date(ms + 5.5 * 3600e3).toISOString().slice(0, 10);
  const addDays = (date, n) => new Date(Date.parse(date + "T00:00:00Z") + n * 86400e3).toISOString().slice(0, 10);
  const pick = a => a[Math.floor(Math.random() * a.length)];
  const emo = e => (EMOTIONS.length && !EMOTIONS.includes(e) ? "happy" : e);

  // ---------- language ----------
  const isTe = s => /[ఀ-౿]/.test(s);
  const ROMAN_TE = /\b(cheppu|chepu|cheppandi|chepandi|cheppava|enti|emiti|emaina|ela|elaa|unnav|unnava|unnaru|bagunnava|bagunnara|baagunnava|nenu|naku|naaku|meeru|miru|kavali|kaavali|ivvu|cheyi|cheyyi|ledu|undi|kadha|katha|kathalu|varthalu|vartalu|vaartalu|vaarthalu|panchangam|em chestunnav|emchestunnav|tinnava|ammamma|tatayya|thatha|nuvvu|nee peru|peru enti|podupu|udyogalu|vaatavaranam|vatavaranam|pandaga|panduga|selavu|samacharam|navvinchu|bore kodutundi|badha|andi|garu|namaskaram|ra babu)\b/i;
  const useTe = (text, lang) => lang === "te" || (lang !== "en" && (isTe(text) || ROMAN_TE.test(text)));
  const say = (te, tel, en) => (te ? tel : en);

  // ---------- words (a light Telugu stemmer, so రైతులకు finds రైతు) ----------
  const STOP = new Set(("the a an is are was were be been am of for to in on at and or about tell me please pls plz kindly how why when where who whom which what whats can could " +
    "you your do does did i my mine we our us it its this that these those with from by give show say know get any some all more today now latest new bro sir madam bigd " +
    "big d hey hi hello ok okay so just like want need will would should there their them they he she his her him one also than then into out up very much many " +
    "make made take get got use used way ways best good does done thing things ఏమిటి ఏంటి ఎలా ఎందుకు ఎప్పుడు ఎక్కడ ఎవరు ఎంత ఏది అంటే గురించి చెప్పు చెప్పండి చెప్పవా నాకు మాకు మీరు మీకు నేను మేము ఈ ఆ ఒక మరియు కూడా ఉంది ఉన్నాయి అని " +
    "అయితే కాని కానీ చాలా ఇది అది వారు ఏమి ఏం దయచేసి బ్రో ఇప్పుడు ఈరోజు రోజు ఏదైనా ఇంకా మళ్ళీ కావాలి ఇవ్వు ఇవ్వండి చూపించు").split(/\s+/));
  const TE_SUFFIX = ["లకు", "లను", "లలో", "లతో", "లకి", "లది", "లు", "కు", "కి", "ను", "ని", "లో", "తో", "గా", "నే", "లే", "కే", "డు", "ము", "వు", "ది", "ల"];
  function stem(w) {
    if (!isTe(w)) return w.length > 4 ? w.replace(/(ies|es|s|ing|ed)$/, "") : w;
    let s = w;
    for (let k = 0; k < 2; k++) { const x = TE_SUFFIX.find(f => s.length - f.length >= 2 && s.endsWith(f)); if (!x) break; s = s.slice(0, -x.length); }
    return s.replace(/[ంాిీుూెేైొోౌ్]+$/, "") || s;
  }
  const words = s => [...new Set((String(s).toLowerCase().match(/[\p{L}\p{M}\p{N}]+/gu) || []).filter(t => t.length > 1 && !STOP.has(t)).map(stem).filter(t => t.length > 1))];
  const same = (a, b) => a === b || (Math.min(a.length, b.length) >= (isTe(a) ? 2 : 4) && (a.startsWith(b) || b.startsWith(a)) && Math.abs(a.length - b.length) <= 4);

  /** Cuts a text at a sentence end before [n] characters. */
  function clip(t, n) {
    const s = String(t || "").replace(/\s+\n/g, "\n").trim();
    if (s.length <= n) return s;
    const cut = s.slice(0, n);
    const at = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("।"), cut.lastIndexOf("! "), cut.lastIndexOf("? "), cut.lastIndexOf("\n"));
    return (at > n * 0.5 ? cut.slice(0, at + 1) : cut.replace(/\s+\S*$/, "") + "…").trim();
  }

  // ---------- Habit's Media posts ----------
  const NOT_TEXT = new Set(["cartoon", "aivideo", "reel", "youtube", "video"]);
  function posts(cat, { days = 120, kind, notKind } = {}) {
    const since = Date.now() - days * 86400e3;
    let all = [];
    try { all = media && media.allItems ? media.allItems() : []; } catch (e) { all = []; }
    return all.filter(i => i && i.cat === cat && i.at >= since && !i.video && !i.yt && !NOT_TEXT.has(i.kind) && (!kind || i.kind === kind) && (!notKind || i.kind !== notKind)
      && String(i.body || "").length >= 25).sort((a, b) => b.at - a.at);
  }
  const served = new Map();   // phone -> the posts it was told lately (so a story or a joke isn't told twice in a row)
  function fresh(list, dev) {
    const seen = served.get(dev) || [];
    const out = list.filter(i => !seen.includes(i.id));
    if (out.length) return out;
    // all told already: the one told longest ago
    return [list.slice().sort((a, b) => seen.indexOf(a.id) - seen.indexOf(b.id))[0]];
  }
  function told(dev, it) {
    if (!it) return;
    const s = (served.get(dev) || []).concat(it.id).slice(-40);
    served.delete(dev); served.set(dev, s);
    if (served.size > 5000) served.delete(served.keys().next().value);
  }
  const srcOf = it => (it && it.url ? [{ title: (it.source || "Habit Media").slice(0, 80), url: it.url }] : []);
  const tokCache = new Map();
  function tokensOf(it) {
    let t = tokCache.get(it.id);
    if (!t) { t = { title: words(it.title), body: words(String(it.body).slice(0, 1500)) }; tokCache.set(it.id, t); if (tokCache.size > 8000) tokCache.delete(tokCache.keys().next().value); }
    return t;
  }
  /** The Media post that best answers the question (word matching), with how much of the question it covers. */
  function searchPosts(q, minCover) {
    const qw = words(q);
    if (!qw.length) return null;
    let all = [];
    try { all = media && media.allItems ? media.allItems() : []; } catch (e) { }
    let best = null;
    for (const it of all) {
      if (!it || it.video || it.yt || NOT_TEXT.has(it.kind) || it.kind === "panchang" || String(it.body || "").length < 40) continue;
      const t = tokensOf(it);
      let s = 0, n = 0;
      for (const w of qw) { if (t.title.some(x => same(x, w))) { s += 2; n++; } else if (t.body.some(x => same(x, w))) { s += 1; n++; } }
      const cover = n / qw.length;
      if (n >= Math.min(2, qw.length) && s > n && cover >= minCover && (!best || s > best.s || (s === best.s && it.at > best.it.at))) best = { it, s, cover };   // (at least one word in the title)
    }
    return best;
  }

  // ---------- replies ----------
  function R(skill, reply, emotion = "happy", extra = {}) {
    stats.answered++; stats.skills[skill] = (stats.skills[skill] || 0) + 1;
    if (extra.weak) stats.weak++;
    return Object.assign({ emotion: emo(emotion), reply: String(reply).trim().slice(0, 4000), actions: [], sources: [], brain: "self", own: true, self: true, skill }, extra);
  }
  const petOf = ctx => PETS[(ctx && ctx.pet) || "puppy"] || PETS.puppy || { name: "puppy", te: "కుక్కపిల్ల" };
  const SOUND = { puppy: "Woof! ", kitten: "Meow! ", robot: "Beep boop! ", kitty: "Nyaa! " };
  const hourOf = ctx => { const m = String((ctx && ctx.now) || "").match(/T(\d{2})/); return m ? Number(m[1]) : new Date(Date.now() + 5.5 * 3600e3).getUTCHours(); };
  const MONTH_EN = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
  const DAY_EN = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
  function dateSay(date, te) {
    const [y, m, d] = date.split("-").map(Number), w = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
    const yr = y !== Number(istDate().slice(0, 4)) ? " " + y : "";
    return te ? `${P.VARA[w]}, ${d} ${P.MONTH[m - 1]}${yr}` : `${DAY_EN[w]}, ${d} ${MONTH_EN[m - 1]}${yr}`;
  }
  const time12 = ms => { const t = new Date(ms + 5.5 * 3600e3); const h = t.getUTCHours(); return `${h % 12 || 12}:${String(t.getUTCMinutes()).padStart(2, "0")} ${h < 12 ? "AM" : "PM"}`; };

  /** Why the big AI isn't answering, in a few kind words (and the real reason for the owner). */
  function whyLine(info = {}, te, admin) {
    const until = info.until ? dateSay(istDate(info.until), te) : "";
    const lines = {
      resting: until ? say(te, `నా పెద్ద AI మెదడు ${until} వరకు విశ్రాంతి తీసుకుంటోంది.`, `My big AI brain is resting until ${until}.`)
        : say(te, "నా పెద్ద AI మెదడు కొంతసేపు విశ్రాంతి తీసుకుంటోంది.", "My big AI brain is resting for a while."),
      down: say(te, "నా పెద్ద AI మెదడు ఇప్పుడు బిజీగా ఉంది, కొంచెం తర్వాత మళ్ళీ అడగండి.", "My big AI brain is busy right now; please ask again in a little while."),
      your_day: say(te, "ఈ రోజు మీ ఉచిత AI మెసేజ్‌లు అయిపోయాయి, రేపు మళ్ళీ వస్తాయి.", "You've used today's free AI messages; they come back tomorrow."),
      busy_day: say(te, "ఈ రోజు హ్యాబిట్‌కి చాలా రద్దీగా ఉంది, రేపు పెద్ద AI మళ్ళీ జవాబిస్తుంది.", "Habit is very busy today; the big AI answers again tomorrow."),
      no_key: say(te, "నా పెద్ద AI మెదడు ఇంకా సిద్ధం కాలేదు.", "My big AI brain isn't set up yet.")
    };
    let s = lines[info.why] || lines.down;
    if (admin && info.why === "resting") s += ` (Owner: Claude can't answer: ${info.reason === "limit" ? "this month's usage limit is used up" : info.reason === "credit" ? "the credit is used up" : info.reason === "key" ? "the API key is wrong or expired" : "an account problem"}${info.until ? ", back on " + istDate(info.until) : ""}. To bring it back now, raise the monthly limit or add credit at console.anthropic.com.)`;
    return s;
  }
  const CAN = te => say(te, "వార్తలు, పంచాంగం, పండుగలు, కథలు, జోకులు, పొడుపు కథలు, మీకు తెలుసా విశేషాలు, ఉద్యోగాలు, రైతు ధరలు, వాతావరణం చెప్పగలను; రిమైండర్లు, అలారాలు, కాల్స్ కూడా చేస్తాను.",
    "I can tell you the news, today's panchangam, festivals, stories, jokes, riddles, fun facts, jobs, crop prices and the weather, and set reminders, alarms and calls.");

  // ---------- skills ----------
  const re = (s) => new RegExp(s, "i");
  const GREET = re("^(hi+|hello+|hey+|hai+|hii+|namaste|namaskaram|namaskar|vanakkam|good ?(morning|afternoon|evening)|gm|bro|bigd|big d|habit(?=\\s*[,!.]|\\s*$))\\b(\\s+(there|everyone|all|friend|buddy|dear|ra|raa|andi|bigd|habit(?=\\s*[,!.]|\\s*$)))?[\\s,!.]*|^(నమస్తే|నమస్కారం|హాయ్|హలో|శుభోదయం|గుడ్ ?మార్నింగ్)[\\s,!.]*");
  const CRISIS = re("suicid|kill myself|end my life|want to die|wanna die|don'?t want to live|self[- ]?harm|cut myself|ఆత్మహత్య|చనిపోవాలని|చనిపోతాను|చావాలని|చచ్చిపోవాలని|బతకాలని లేదు|బ్రతకాలని లేదు|chanipovalani|chachipovalani|chavalani|bathakalani ledu");
  const SYMPTOM = re("\\b(fever|pain|ache|vomit\\w*|loose motions?|diarrh\\w*|bleed\\w*|breath\\w*|medicine|tablet|dose|dosage|pregnan\\w*|injury|burn|bite|bitten|poison\\w*)\\b|జ్వరం|నొప్పి|వాంతి|విరేచనాలు|రక్తం|ఆయాసం|మందు|మాత్ర|టాబ్లెట్|గర్భ|కాటు|విషం|jwaram|noppi");

  function chat(q, te, ctx, info) {
    const pet = petOf(ctx), snd = te ? "" : (SOUND[(ctx && ctx.pet) || "puppy"] || "");
    if (re("^(how are (you|u)|how r u|hw r u|how('?s| is) it going|ela unnav\\w*|ela unnaru|bagunnava|bagunnara|baagunnava)|ఎలా ఉన్నావ|ఎలా ఉన్నారు|బాగున్నావా|బాగున్నారా").test(q))
      return R("chat", say(te, "నేను బాగున్నాను, థాంక్యూ! 🐾 మీరు ఎలా ఉన్నారు?", snd + "I'm doing great, thank you! 🐾 How are you?"), "happy");
    if (re("^(i('?m| am) )?(fine|good|great|doing (good|well|fine)|all good)\\b.{0,12}$|^బాగున్నాను|^బాగానే ఉన్నాను|^(nenu )?ba+gunnanu").test(q))
      return R("chat", say(te, "సూపర్! విని చాలా సంతోషంగా ఉంది. 😊", "Super! I'm so happy to hear that. 😊"), "excited");
    if (re("\\b(thanks|thank you|thank u|thanku|thx|tq|ty)\\b|ధన్యవాదాలు|థాంక్స్|థ్యాంక్స్|థాంక్యూ").test(q))
      return R("chat", say(te, "పర్వాలేదు! మీకు సహాయం చేయడం నాకు చాలా ఇష్టం. 😊", "You're welcome! I love helping you. 😊"), "love");
    if (re("who are (you|u)|what('?s| is) (your|ur) name|ur name|nuvvu evaru|nee peru|నువ్వు ఎవరు|నీ పేరు|మీరు ఎవరు|నువ్వెవరు").test(q))
      return R("chat", say(te, `నేను హ్యాబిట్, మీ ఫోన్‌లో ఉండే మీ స్నేహితుడైన ${pet.te}ని! 🐾 మీతో మాట్లాడతాను, రిమైండర్లు పెడతాను, వార్తలు, కథలు, పంచాంగం చెబుతాను.`,
        `${snd}I'm Habit, your friendly ${pet.name} who lives on your phone! 🐾 I chat with you, set reminders, and tell you the news, stories and the panchangam.`), "proud");
    if (re("^(help|menu)$|what (all )?can (you|u) do|what do you do|em chestav|em cheyagalav|ఏం చేయగలవు|ఏమి చేయగలవు|ఏం చేస్తావు|^సహాయం$").test(q))
      return R("chat", say(te, "ఇప్పుడు నేను " + CAN(true) + " ఏది కావాలో చెప్పండి! 🐾", "Right now " + CAN(false) + " Just tell me which! 🐾"), "excited");
    if (re("\\b(i )?(love|luv) (you|u)\\b|ఐ లవ్ యూ|నువ్వంటే ఇష్టం|నువ్వు అంటే ఇష్టం").test(q)) return R("chat", say(te, "❤️ నాకూ మీరంటే చాలా ఇష్టం!", "❤️ I love you too!"), "love");
    if (re("good ?night|^gn$|శుభ ?రాత్రి|గుడ్ ?నైట్").test(q)) return R("chat", say(te, "🌙 శుభ రాత్రి! హాయిగా నిద్రపోండి, మంచి కలలు రావాలి.", "🌙 Good night! Sleep well and sweet dreams."), "sleepy");
    if (re("^(bye|bye bye|see (you|u)|tata|good ?bye|ok bye|cya)\\b|^బై|వెళ్తాను|వెళ్ళొస్తా").test(q)) return R("chat", say(te, "👋 సరే, మళ్ళీ కలుద్దాం! ఎప్పుడు కావాలన్నా పిలవండి.", "👋 Okay, see you soon! Call me anytime."), "wink");
    if (re("^(sorry|sry|my bad)\\b|క్షమించు|సారీ").test(q)) return R("chat", say(te, "అయ్యో, పర్వాలేదు! 😊", "Oh, no problem at all! 😊"), "shy");
    if (re("^(ok|okay|k|kk|hmm+|haa+|ha|achha|acha|sare|sari|fine)$|^(సరే|ఓకే|హ్మ్)$").test(q)) return R("chat", say(te, "😊 సరే!", "😊 Okay!"), "happy");
    if (re("are (you|u) there|you there|unnava|ఉన్నావా|why (no|not|didn'?t you) (reply|answer)|reply (me|ivvu)|జవాబు ఎందుకు|మాట్లాడవా").test(q))
      return R("chat", say(te, "నేను ఇక్కడే ఉన్నాను! 🐾 ", "I'm right here! 🐾 ") + whyLine(info, te, ctx && ctx.admin) + " " + say(te, "ఈలోగా " + CAN(true), "Meanwhile " + CAN(false)), "happy");
    if (re("\\b(i('?m| am)|feeling|feel) (so |very )?(sad|lonely|alone|upset|low|depressed|hurt|crying)\\b|బాధగా ఉంది|ఒంటరిగా ఉన్నా|ఏడుపు వస్తోంది|మనసు బాలేదు|badha ?ga undi|onteri").test(q))
      return R("care", say(te, "అయ్యో, మీరు బాధగా ఉన్నారంటే నాకూ బాధగా ఉంది. 🤗 నేను మీతోనే ఉన్నాను. ఏం జరిగిందో చెప్పాలనిపిస్తే చెప్పండి, లేదా మీకు నమ్మకమైన వాళ్ళతో ఒకసారి మాట్లాడండి. కావాలంటే ఒక చిన్న కథ గానీ జోక్ గానీ చెప్పమంటారా?",
        "Oh, I'm sorry you're feeling this way. 🤗 I'm right here with you. Tell me what happened if you like, or talk to someone you trust. Shall I tell you a little story or a joke?"), "sad");
    if (re("\\b(i('?m| am) )?(so )?bored\\b|boring|bore (ga undi|kodutundi|kodtundi)|బోర్ (గా|కొడుతోంది|కొడుతుంది)|విసుగ్గా").test(q))
      return R("chat", say(te, "బోర్ కొడుతోందా? 😄 ఒక జోక్, పొడుపు కథ, లేదా కథ చెప్పమంటారా? ఏది కావాలో చెప్పండి!", "Bored? 😄 Shall I tell you a joke, a riddle or a story? Just say which!"), "wink");
    return null;
  }

  function panchangam(q, te) {
    if (!re("panchang|tithi|thithi|nakshatra|nakshatram|rahu ?kal|rahukal|yamagand|durmuhur|muhurt|sunrise|sunset|పంచాంగం|తిథి|నక్షత్రం|రాహుకాలం|రాహు కాలం|యమగండం|ముహూర్తం|వర్జ్యం|సూర్యోదయం").test(q)) return null;
    const date = re("tomorrow|repu|రేపు|రేపటి").test(q) ? addDays(istDate(), 1) : istDate();
    const p = P.post(date);
    if (te) return R("panchangam", p.body, "happy");
    const d = p.data, TITHI = ["Pratipada", "Dvitiya", "Tritiya", "Chaturthi", "Panchami", "Shashthi", "Saptami", "Ashtami", "Navami", "Dashami", "Ekadashi", "Dvadashi", "Trayodashi", "Chaturdashi"];
    const tn = i => (i === 14 ? "Purnima" : i === 29 ? "Amavasya" : TITHI[i % 15]);
    const MASA = ["Chaitra", "Vaishakha", "Jyeshtha", "Ashadha", "Shravana", "Bhadrapada", "Ashvayuja", "Karthika", "Margashira", "Pushya", "Magha", "Phalguna"];
    const NAK = ["Ashwini", "Bharani", "Krittika", "Rohini", "Mrigashira", "Ardra", "Punarvasu", "Pushya", "Ashlesha", "Magha", "Purva Phalguni", "Uttara Phalguni", "Hasta", "Chitra", "Swati",
      "Vishakha", "Anuradha", "Jyeshtha", "Mula", "Purva Ashadha", "Uttara Ashadha", "Shravana", "Dhanishta", "Shatabhisha", "Purva Bhadrapada", "Uttara Bhadrapada", "Revati"];
    return R("panchangam", `📿 ${date === istDate() ? "Today's" : "Tomorrow's"} panchangam (${d.place === "విజయవాడ" ? "Vijayawada" : "Andhra Pradesh"} times): ${d.adhika ? "Adhika " : ""}${MASA[d.masaIndex]} month, ${d.tithiIndex < 15 ? "Shukla" : "Krishna"} paksha. ` +
      `Tithi ${tn(d.tithiIndex)}${d.tithiEnd ? " until " + time12(d.tithiEnd) : ""}, then ${tn((d.tithiIndex + 1) % 30)}. Nakshatram ${NAK[d.nakIndex] || d.nakshatra}${d.nakEnd ? " until " + time12(d.nakEnd) : ""}. ` +
      `Sunrise ${time12(d.sunrise)}, sunset ${time12(d.sunset)}. Rahu kalam ${time12(d.rahu[0])} to ${time12(d.rahu[1])}, Yamagandam ${time12(d.yama[0])} to ${time12(d.yama[1])}.`, "happy");
  }

  const FEST_NAMES = [["deepavali", "deepavali|diwali|deepawali|దీపావళి"], ["naraka", "naraka|నరక చతుర్దశి"], ["sankranti", "sankranti|sankranthi|pongal|సంక్రాంతి"], ["bhogi", "bhogi|భోగి"], ["kanuma", "kanuma|కనుమ"],
    ["ugadi", "ugadi|ఉగాది"], ["rama_navami", "rama ?navami|sri ?rama ?navami|రామ ?నవమి"], ["vinayaka", "vinayaka|ganesh|ganesha|chavithi|chaturthi|వినాయక|గణేష్"],
    ["dasara", "dasara|dussehra|dasera|vijaya ?dashami|దసరా|విజయదశమి"], ["saddula_bathukamma", "saddula"], ["bathukamma", "bathukamma|బతుకమ్మ"], ["christmas", "christmas|xmas|క్రిస్మస్"],
    ["easter", "easter|ఈస్టర్"], ["ramzan", "ramzan|ramadan|ramzaan|eid ?ul ?fitr|\\beid\\b|రంజాన్"], ["bakrid", "bakrid|bakri ?eid|eid ?ul ?adha|బక్రీద్"], ["holika", "holika|kamuni|కాముని"],
    ["holi", "\\bholi\\b|హోలీ"], ["shivaratri", "shivaratri|sivaratri|శివరాత్రి"], ["rakhi", "rakhi|raksha ?bandhan|రాఖీ"], ["krishnashtami", "krishnashtami|janmashtami|krishna ?ashtami|కృష్ణాష్టమి|జన్మాష్టమి"],
    ["karthika_pournami", "karthika ?pournami|kartika ?purnima|karthika ?purnima|కార్తీక పౌర్ణమి"], ["nagula_chavithi", "nagula|నాగుల"], ["varalakshmi", "varalakshmi|వరలక్ష్మి"],
    ["guru_purnima", "guru ?purnima|guru ?pournami|గురు పౌర్ణమి"], ["mukkoti", "mukkoti|vaikunta|vaikuntha|ముక్కోటి|వైకుంఠ"], ["tholi_ekadashi", "tholi ?ekadashi|తొలి ఏకాదశి"],
    ["new_year", "new ?year|నూతన సంవత్సరం"], ["republic", "republic ?day|గణతంత్ర"], ["independence", "independence ?day|స్వాతంత్ర్య"], ["teachers_day", "teachers'? ?day|ఉపాధ్యాయ దినోత్సవం"],
    ["childrens_day", "children'?s ?day|బాలల దినోత్సవం"], ["mothers_day", "mother'?s ?day|మాతృ దినోత్సవం"], ["fathers_day", "father'?s ?day|పితృ దినోత్సవం"], ["friendship", "friendship ?day|స్నేహితుల దినోత్సవం"],
    ["womens_day", "women'?s ?day|మహిళా దినోత్సవం"], ["telugu_day", "telugu ?(language|bhasha) ?day|తెలుగు భాషా"], ["buddha", "buddha ?purnima|బుద్ధ"], ["mahavir", "mahavir|మహావీర్"],
    ["guru_nanak", "guru ?nanak|గురునానక్"], ["hanuman", "hanuman ?jayanti|హనుమాన్ జయంతి"], ["vasant_panchami", "vasant ?panchami|vasantha ?panchami|వసంత పంచమి"], ["ratha_saptami", "ratha ?saptami|రథసప్తమి"]];
  function festivals(q, te) {
    if (!trends) return null;
    const named = FEST_NAMES.find(([, r]) => re(r).test(q));
    const asks = re("festival|pandaga|panduga|పండుగ|పండగ|holiday|selavu|సెలవు").test(q);
    if (!named && !asks) return null;
    const approxNote = f => (f.approx ? say(te, " (చంద్ర దర్శనాన్ని బట్టి ఒక రోజు తేడా ఉండవచ్చు.)", " (It depends on the moon sighting, so it may be a day off.)") : "");
    if (named) {
      const f = trends.next(named[0]);
      if (!f) return null;
      const name = te ? f.te : f.en;
      const when = f.inDays === 0 ? say(te, `🎉 ఈరోజే ${name}! మీకు, మీ కుటుంబానికి శుభాకాంక్షలు!`, `🎉 Today is ${name}! Happy ${name} to you and your family!`)
        : f.inDays === 1 ? say(te, `రేపే ${name}! (${dateSay(f.date, true)})`, `${name} is tomorrow (${dateSay(f.date, false)})!`)
          : say(te, `${name} ${dateSay(f.date, true)}న వస్తుంది (ఇంకా ${f.inDays} రోజులు).`, `${name} is on ${dateSay(f.date, false)} (in ${f.inDays} days).`);
      return R("festival", "🪔 " + when + approxNote(f), f.inDays <= 1 ? "excited" : "happy");
    }
    const list = trends.upcoming(40);
    const today = list.filter(f => f.inDays === 0), soon = list.filter(f => f.inDays > 0).slice(0, 3);
    const soonTxt = soon.map(f => te ? `${f.te} – ${dateSay(f.date, true)} (${f.inDays} రోజుల్లో)` : `${f.en} on ${dateSay(f.date, false)} (in ${f.inDays} days)`).join("; ");
    if (today.length) {
      const n = today.map(f => (te ? f.te : f.en)).join(say(te, ", ", " and "));
      return R("festival", say(te, `🎉 ఈరోజు ${n}! మీకు, మీ కుటుంబానికి శుభాకాంక్షలు!`, `🎉 Today is ${n}! Happy ${n} to you and your family!`) + (soon.length ? say(te, ` తర్వాత: ${soonTxt}.`, ` Next: ${soonTxt}.`) : ""), "excited");
    }
    return R("festival", say(te, `ఈరోజు పెద్ద పండుగ ఏమీ లేదు. ${soon.length ? "రాబోయే పండుగలు: " + soonTxt + "." : ""}`, `No big festival today. ${soon.length ? "Coming up: " + soonTxt + "." : ""}`), "happy");
  }

  /** Fresh headlines straight from Google News (when Habit's own Media news is old, e.g. while Claude rests). */
  async function googleNews(topic, te) {
    const hl = te ? "hl=te&gl=IN&ceid=IN:te" : "hl=en-IN&gl=IN&ceid=IN:en";
    const u = topic ? `https://news.google.com/rss/search?q=${encodeURIComponent(topic + " when:2d")}&${hl}` : `https://news.google.com/rss?${hl}`;
    const r = await get(u, 7000);
    if (!r.ok) return [];
    const xml = await r.text();
    const dec = x => String(x).replace(/<!\[CDATA\[|\]\]>/g, "").replace(/&amp;/g, "&").replace(/&quot;/g, "\"").replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").trim();
    return [...xml.matchAll(/<item>[\s\S]*?<title>([\s\S]*?)<\/title>[\s\S]*?<link>([\s\S]*?)<\/link>/g)].slice(0, 3).map(m => ({ title: dec(m[1]), url: dec(m[2]) }));
  }
  function listTitles(list, n) { return list.slice(0, n).map((i, k) => `${k + 1}. ${String(i.title).replace(/^[^\p{L}\p{N}]+/u, "")}`).join("\n"); }
  async function sections(q, te, ctx) {
    const dev = (ctx && ctx.device) || "?";
    const one = (skill, list, emoji, emotion, max = 700, extra = {}) => {
      if (!list.length) return null;
      const it = pick(fresh(list.slice(0, 60), dev));
      told(dev, it);
      return R(skill, `${emoji} ${String(it.title).replace(/^[^\p{L}\p{N}]+/u, "")}\n${clip(it.body, max)}`, emotion, Object.assign({ sources: srcOf(it) }, extra));
    };
    const latest = (skill, list, head, foot, emotion = "thinking") => list.length ? R(skill, `${head}\n${listTitles(list, 3)}${foot ? "\n" + foot : ""}`, emotion, { sources: srcOf(list[0]) }) : null;
    // news (on a topic, if they named one)
    if (re("\\bnews\\b|headline|varthalu|vartalu|vaartalu|vaarthalu|వార్త|న్యూస్|ముఖ్యాంశాలు").test(q)) {
      const all = posts("news", { days: 3 });
      const topic = q.replace(new RegExp("\\b(latest|today'?s?|news|headlines?|tell me|the|about|on|what'?s|what is|in|of|any|top|please|varthalu|vartalu|vaartalu|cheppu)\\b|వార్తలు|వార్త|న్యూస్|ఈరోజు|ఈ రోజు|తాజా|చెప్పండి|చెప్పు|గురించి|సమాచారం|ముఖ్య|ఏమిటి|ఏంటి", "gi"), " ").replace(/[?.!,]/g, " ").replace(/\s+/g, " ").trim();
      const tw = words(topic);
      if (tw.length) {
        const hit = all.concat(posts("news", { days: 14 })).filter((i, k, a) => a.indexOf(i) === k).filter(i => { const t = tokensOf(i); return tw.every(w => t.title.some(x => same(x, w)) || t.body.some(x => same(x, w))); });
        if (hit.length) return R("news", say(te, `📰 ${topic} గురించి తాజా వార్తలు:\n`, `📰 The latest news about ${topic}:\n`) + listTitles(hit, 3) + "\n" + clip(hit[0].body, 300), "thinking", { sources: srcOf(hit[0]) });
      }
      // nothing new in Habit's Media (or not on that topic): the headlines straight from Google News
      if (!all.length || tw.length) {
        try {
          const g = await googleNews(tw.length ? topic : "", te);
          if (g.length) return R("news", say(te, tw.length ? `📰 ${topic} గురించి ఇప్పటి వార్తలు (Google News):\n` : "📰 ఇప్పటి ముఖ్య వార్తలు (Google News):\n", tw.length ? `📰 News about ${topic} right now (Google News):\n` : "📰 Top news right now (Google News):\n") +
            g.map((x, k) => `${k + 1}. ${x.title}`).join("\n"), "thinking", { sources: g.slice(0, 3).map(x => ({ title: x.title.slice(0, 80), url: x.url })) });
        } catch (e) { }
      }
      if (!all.length) return R("news", say(te, "📰 ఈరోజు ఇంకా కొత్త వార్తలు రాలేదు. కొంచెం తర్వాత మళ్ళీ అడగండి.", "📰 No new news has come in yet today. Please ask again a little later."), "thinking");
      return R("news", say(te, "📰 ఈరోజు ముఖ్య వార్తలు:\n", "📰 Today's top news (in Telugu, from Habit Media):\n") + listTitles(all, 3) + "\n" + clip(all[0].body, 260) + say(te, "\nమరిన్ని వివరాలు మీడియా పేజీలో.", "\nThere's more on the Media page."), "thinking", { sources: srcOf(all[0]) });
    }
    if (re("\\b(jobs?|job notifications?|recruitment|vacanc\\w*|naukri|udyogam|udyogalu)\\b|ఉద్యోగ|ఉద్యోగాల నోటిఫికేషన్|రిక్రూట్‌మెంట్|ఖాళీలు").test(q))
      return latest("jobs", posts("jobs", { days: 30 }), say(te, "💼 కొత్త ఉద్యోగ ప్రకటనలు:", "💼 New job notifications:"),
        say(te, "అర్హత, చివరి తేదీ, అధికారిక వెబ్‌సైట్ వివరాలు మీడియా పేజీలోని ఉద్యోగాలు విభాగంలో ఉన్నాయి.", "Who can apply, the last date and the official website are in the Jobs section of the Media page."), "proud");
    if (re("\\b(mandi|crop prices?|market prices?|market rates?|farmers?|farming|agricultur\\w*|vyavasayam|raithu|rythu|paddy|cotton|chilli)\\b|రైతు|వ్యవసాయ|పంట|మండీ|మార్కెట్ ధర|మార్కెట్ యార్డు|వరి|పత్తి|మిర్చి").test(q)) {
      const f = posts("farming", { days: 20 });
      if (re("price|rate|mandi|ధర|రేటు|మండీ|మార్కెట్").test(q)) { const m = f.filter(i => i.kind === "mandi"); if (m.length) return one("farming", m, "🌾", "thinking", 900); }
      if (re("rain|weather|వర్షం|వాన|వాతావరణ").test(q)) { const w = f.filter(i => i.kind === "weather"); if (w.length) return one("farming", w, "🌧️", "thinking", 900); }
      if (f.length) return R("farming", say(te, "🌾 రైతుల కోసం తాజా సమాచారం:\n", "🌾 The latest for farmers:\n") + listTitles(f, 3) + "\n" + clip(f[0].body, 400), "thinking", { sources: srcOf(f[0]) });
    }
    if (re("\\b(movies?|cinema|films?|reviews?|box ?office|trailers?|ott)\\b|సినిమా|చిత్రం|రివ్యూ|ట్రైలర్|బాక్స్ ?ఆఫీస్|ఓటీటీ").test(q))
      return latest("movies", posts("movies", { days: 14 }), say(te, "🎬 సినిమా కబుర్లు:", "🎬 Movie news:"), say(te, "పూర్తి వివరాలు మీడియా పేజీలో.", "The details are on the Media page."), "excited");
    if (re("\\b(riddles?|podupu)\\b|పొడుపు").test(q)) {
      const f = posts("fun", { days: 365 }); const r = f.filter(i => /పొడుపు|జవాబు|riddle/i.test(i.title + " " + i.body));
      return one("riddle", r.length ? r : f, "🤔", "thinking", 700, { sound: "drumroll" });
    }
    if (re("\\b(jokes?|funny|comedy|laugh|navvinchu|navvu|haasyam|hasyam)\\b|జోక్|హాస్య|నవ్వించు|నవ్వు|నవ్వుకో").test(q)) {
      const f = posts("fun", { days: 365 }), j = f.filter(i => !/పొడుపు|జవాబు|riddle/i.test(i.title + " " + i.body));
      return one("joke", j.length ? j : f, "😄", "laughing", 700, { sound: "laugh" });
    }
    if (re("\\b(story|stories|kadha|katha|kathalu|rhymes?|bedtime)\\b|కథ|కధ|కతలు|పాట|బాలల|పిల్లల కోసం|చిన్న కథ").test(q))
      return one("story", posts("kids", { days: 365 }), "📖", "happy", 1200);
    if (re("\\b(facts?|did you know|gk|general knowledge|science|vignanam)\\b|మీకు తెలుసా|విజ్ఞానం|సైన్స్|విశేషాలు").test(q))
      return one("fact", posts("facts", { days: 365 }), "💡", "surprised", 800, { sound: "wow" });
    if (re("\\b(devotional|bhakti|bhakthi|temples?|tirumala|tirupati|ttd|srisailam|darshan)\\b|దేవుడు|దేవాలయం|ఆలయం|భక్తి|తిరుమల|శ్రీశైలం|దర్శనం|దైవ").test(q))
      return one("devotional", posts("devotional", { days: 60, notKind: "panchang" }), "🙏", "happy", 800);
    if (re("\\b(health|healthy|health tips?|diet|exercise|yoga|arogyam|bp|sugar|diabetes)\\b|ఆరోగ్య|వ్యాయామం|యోగా|షుగర్|బీపీ").test(q) && !SYMPTOM.test(q))
      return one("health", posts("health", { days: 365 }), "🩺", "happy", 800);
    if (re("\\b(crypto|bitcoin|ethereum)\\b|క్రిప్టో|బిట్‌కాయిన్").test(q))
      return latest("crypto", posts("crypto", { days: 14 }), say(te, "🪙 క్రిప్టో వార్తలు (కొనమని, అమ్మమని నేను ఎప్పుడూ చెప్పను):", "🪙 Crypto news (I never tell anyone to buy or sell):"), "", "thinking");
    if (re("real ?estate|\\b(plots?|land prices?|house prices?|rera)\\b|రియల్ ?ఎస్టేట్|ప్లాట్|స్థలం|భూమి ధర").test(q))
      return latest("realestate", posts("realestate", { days: 30 }), say(te, "🏠 రియల్ ఎస్టేట్ కబుర్లు:", "🏠 Real estate news:"), "", "thinking");
    return null;
  }

  // ---------- the weather (Open-Meteo, free) ----------
  const TE_CITY = { "విజయవాడ": "Vijayawada", "హైదరాబాద్": "Hyderabad", "హైదరాబాదు": "Hyderabad", "విశాఖపట్నం": "Visakhapatnam", "వైజాగ్": "Visakhapatnam", "విశాఖ": "Visakhapatnam",
    "తిరుపతి": "Tirupati", "గుంటూరు": "Guntur", "నెల్లూరు": "Nellore", "కర్నూలు": "Kurnool", "కాకినాడ": "Kakinada", "రాజమండ్రి": "Rajahmundry", "రాజమహేంద్రవరం": "Rajahmundry",
    "వరంగల్": "Warangal", "కరీంనగర్": "Karimnagar", "నిజామాబాద్": "Nizamabad", "ఖమ్మం": "Khammam", "అనంతపురం": "Anantapur", "కడప": "Kadapa", "ఒంగోలు": "Ongole", "ఏలూరు": "Eluru",
    "శ్రీకాకుళం": "Srikakulam", "విజయనగరం": "Vizianagaram", "చిత్తూరు": "Chittoor", "అమరావతి": "Amaravati", "భీమవరం": "Bhimavaram", "మచిలీపట్నం": "Machilipatnam",
    "నల్గొండ": "Nalgonda", "మహబూబ్‌నగర్": "Mahabubnagar", "ఆదిలాబాద్": "Adilabad", "సిద్దిపేట": "Siddipet", "తెనాలి": "Tenali", "ప్రొద్దుటూరు": "Proddatur", "హిందూపురం": "Hindupur" };
  const SKY = { 0: ["clear sky", "ఆకాశం నిర్మలంగా ఉంది"], 1: ["mostly clear", "ఎక్కువగా నిర్మలంగా"], 2: ["partly cloudy", "కొద్దిగా మేఘాలు"], 3: ["cloudy", "మేఘావృతం"], 45: ["fog", "పొగమంచు"], 48: ["fog", "పొగమంచు"],
    51: ["light drizzle", "చిరుజల్లులు"], 53: ["drizzle", "చిరుజల్లులు"], 55: ["drizzle", "చిరుజల్లులు"], 61: ["light rain", "తేలికపాటి వర్షం"], 63: ["rain", "వర్షం"], 65: ["heavy rain", "భారీ వర్షం"],
    80: ["rain showers", "జల్లులు"], 81: ["rain showers", "జల్లులు"], 82: ["heavy showers", "భారీ జల్లులు"], 95: ["thunderstorm", "ఉరుములతో వర్షం"], 96: ["thunderstorm with hail", "వడగళ్ళ వాన"], 99: ["thunderstorm with hail", "వడగళ్ళ వాన"] };
  const WEATHER = re("weather|temperature|forecast|\\brain(s|ing|y)?\\b|vaatavaranam|vatavaranam|varsham|vaana|endalu|వాతావరణం|వర్షం|వాన|ఎండ|వేడి|చలి|ఉష్ణోగ్రత");
  const NOT_PLACE = /^(what'?s?|whats|how'?s?|is|it|the|today|tomorrow|will|going|to|there|any|like|what is|how is|tell me|current|now|here|in|my|our)$/i;
  /** The town in the question: { q: the name to look up, te: the Telugu name they used (or "") }, or null. */
  function placeIn(q) {
    for (const [te, en] of Object.entries(TE_CITY)) if (q.includes(te)) return { q: en, te };
    const m = q.match(/\b(?:in|at|for|of)\s+([A-Za-z][A-Za-z .'-]{2,40}?)(?:\s+(?:today|tomorrow|now|right now|this week|weather|please))?[?.!]*$/i)
      || q.match(/^([A-Za-z][A-Za-z .'-]{2,30}?)\s+(?:weather|temperature|forecast)\b/i) || q.match(/([A-Za-z]{3,30})\s+lo\s+(?:vaatavaranam|vatavaranam|varsham|vaana)/i);
    const place = m ? m[1].replace(/\b(the|today|tomorrow|now|like|please)\b/gi, "").replace(/\s+/g, " ").trim() : "";
    if (!place || WEATHER.test(place) || place.split(" ").every(w => NOT_PLACE.test(w)) || /^(here|there|my (area|place|town|city|village)|our (area|town|city|village)|india|the morning|the evening|this week)$/i.test(place)) return null;
    return { q: place, te: "" };
  }
  async function weatherFor(place, te, teName = "") {
    let g = await json(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(place)}&count=1&language=en&countryCode=IN`).catch(() => null);
    if (!g || !(g.results || []).length) g = await json(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(place)}&count=1&language=en`).catch(() => null);
    const p0 = ((g && g.results) || [])[0];
    if (!p0) return null;
    const p = Object.assign({}, p0, { name: te && teName ? teName : p0.name });
    const w = await json(`https://api.open-meteo.com/v1/forecast?latitude=${p.latitude}&longitude=${p.longitude}&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&timezone=auto&forecast_days=2`);
    const c = w.current || {}, d = w.daily || {};
    const sky = code => (SKY[code] || ["", ""])[te ? 1 : 0];
    const n = i => [Math.round((d.temperature_2m_min || [])[i]), Math.round((d.temperature_2m_max || [])[i]), (d.precipitation_probability_max || [])[i] ?? "?", sky((d.weather_code || [])[i])];
    const [a, b, r, s] = n(0), [a1, b1, , s1] = n(1);
    return { place: p.name, text: say(te, `🌤️ ${p.name}లో ఇప్పుడు ${Math.round(c.temperature_2m)}°C, ${sky(c.weather_code)}. ఈరోజు ${a}–${b}°C, వర్షం అవకాశం ${r}%. రేపు ${s1 || ""} ${a1}–${b1}°C.`,
      `🌤️ In ${p.name} right now: ${Math.round(c.temperature_2m)}°C, ${sky(c.weather_code)}. Today ${a}–${b}°C, ${r}% chance of rain${s ? " (" + s + ")" : ""}. Tomorrow: ${s1 ? s1 + ", " : ""}${a1}–${b1}°C.`) };
  }
  async function weather(q, te) {
    if (!WEATHER.test(q)) return null;
    const place = placeIn(q);
    const ask = R("weather", say(te, "🌤️ ఏ ఊరి వాతావరణం కావాలి? ఉదాహరణకి \"విజయవాడ వాతావరణం\" అని అడగండి.", "🌤️ Which town's weather? For example, ask \"weather in Vijayawada\"."), "thinking", { weak: true });
    if (!place) return ask;     // (weak: the phone then tries with its own place)
    try { const w = await weatherFor(place.q, te, place.te); if (w) return R("weather", w.text, "cool", { sources: [{ title: "Open-Meteo", url: "https://open-meteo.com/" }] }); } catch (e) { }
    return ask;
  }

  // ---------- Wikipedia ----------
  const DEFINE = re("^(what|who|whom)('?s| is| are| was| were)\\b|\\b(meaning of|define|explain|tell me about|history of)\\b|అంటే ఏమిటి|అంటే ఏంటి|ఎవరు\\??$|గురించి చెప్పు|గురించి చెప్పండి|ఏమిటి\\??$|ante enti|evaru");
  async function wiki(q, te) {
    const term = q.replace(new RegExp("\\b(who|what|whom|is|are|was|were|tell me|about|explain|the|a|an|meaning of|define|history of|please|bro)\\b", "gi"), " ")
      .replace(/ఎవరు|ఏమిటి|ఏంటి|అంటే|గురించి|చెప్పు|చెప్పండి|వివరించు|ఎవరో|ఏమిటో/g, " ").replace(/[?.!,]/g, " ").replace(/\s+/g, " ").trim();
    if (term.length < 3) return null;
    const tw = words(term);
    for (const host of isTe(term) ? ["te"] : te ? ["te", "en"] : ["en"]) {
      try {
        const s = await json(`https://${host}.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(term)}&srlimit=1&format=json`);
        const title = ((((s || {}).query || {}).search || [])[0] || {}).title;
        if (!title || !words(title).some(w => tw.some(x => same(w, x)))) continue;        // not about what they asked
        const sum = await json(`https://${host}.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`);
        if (sum && sum.extract && sum.type !== "disambiguation") {
          const url = ((sum.content_urls || {}).desktop || {}).page || `https://${host}.wikipedia.org/wiki/${encodeURIComponent(title)}`;
          return R("wikipedia", say(te && host === "te", "📖 వికీపీడియా ప్రకారం: ", te ? "📖 వికీపీడియా (ఇంగ్లీష్) ప్రకారం: " : "📖 Wikipedia says: ") + clip(sum.extract, 480), "thinking", { sources: [{ title: "Wikipedia: " + title, url }] });
        }
      } catch (e) { }
    }
    return null;
  }

  // ---------- the answer ----------
  /** The self brain's answer, or null when it has nothing good ([strong]); with strong false it always answers
   *  (weak: true when all it can do is say what it can do). [info] = why the big AI isn't answering. */
  async function answer(text, lang, ctx = {}, { strong = true, info = {} } = {}) {
    const raw = String(text || "").replace(/^\[Your eyes:[^\]]*\]\s*(\(looking again\)\s*)?/, "").trim().slice(0, 600);
    const te = useTe(raw, lang);
    if (!raw) return strong ? null : fallback(te, ctx, info);
    if (CRISIS.test(raw)) return R("care", say(te,
      "మీరు ఇంత బాధలో ఉన్నారంటే నాకు చాలా బాధగా ఉంది. మీరు ఒంటరి కాదు. దయచేసి ఇప్పుడే Tele-MANAS కి 14416 కాల్ చేయండి (ఉచితం, 24 గంటలూ, తెలుగులో కూడా మాట్లాడతారు). ప్రాణాపాయం ఉంటే వెంటనే 112 కి కాల్ చేయండి. మీకు నమ్మకమైన ఒకరితో ఇప్పుడే మాట్లాడండి. నేను ఇక్కడే ఉన్నాను. 🤗",
      "I'm really sorry you're going through so much pain. You are not alone. Please call Tele-MANAS on 14416 right now (free, 24 hours, they speak Telugu too). If you are in danger, call 112 now. Please talk to someone you trust right away. I'm here with you. 🤗"), "sad");
    let q = raw;
    for (let k = 0; k < 3; k++) { const m = q.match(GREET); if (!m || !m[0]) break; q = q.slice(m[0].length).trim(); }
    if (!q) {
      const h = hourOf(ctx), snd = te ? "" : (SOUND[ctx.pet || "puppy"] || "");
      const g = h >= 4 && h < 12 ? say(te, "శుభోదయం", "Good morning") : h >= 12 && h < 17 ? say(te, "శుభ మధ్యాహ్నం", "Good afternoon") : h >= 17 && h < 22 ? say(te, "శుభ సాయంత్రం", "Good evening") : say(te, "నమస్తే", "Hello");
      return R("chat", say(te, `${g}! 🐾 నేను ఇక్కడే ఉన్నాను. వార్తలు, పంచాంగం, ఒక కథ, ఒక జోక్, లేదా రిమైండర్ — ఏం కావాలో చెప్పండి.`, `${snd}${g}! 🐾 I'm here. Ask me for the news, today's panchangam, a story, a joke or a reminder.`), "excited");
    }
    try {
      const quick = chat(q, te, ctx, info) || panchangam(q, te) || festivals(q, te);
      if (quick) return quick;
      if (SYMPTOM.test(q) && re("\\b(i|me|my|mine|i'?ve|i'?m|have|having|got)\\b|నాకు|నా |మా |ఉంది|వచ్చింది|naku").test(q))
        return R("health", say(te, "ఆరోగ్యం విషయంలో నా సొంత మెదడు సలహా ఇవ్వలేదు. 🙏 ఎక్కువగా ఉంటే దగ్గర్లోని డాక్టర్‌ని కలవండి; అత్యవసరమైతే వెంటనే 108 కి కాల్ చేయండి.",
          "I can't give health advice on my own. 🙏 If it's bad, please see a doctor nearby; in an emergency, call 108 right away."), "sad");
      const sec = await sections(q, te, ctx);
      if (sec) return sec;
      const w = await weather(q, te);
      if (w) return w;
      // what it learned from Claude, and the owner's library
      const learnedHit = answers && answers.lookup ? answers.lookup(q, { min: strong ? 0.75 : 0.6 }) : null;
      if (learnedHit) { if (answers.used) answers.used(learnedHit.id); return R("learned", learnedHit.answer, "happy", { sources: learnedHit.sources || [], learned: true }); }
      const k = learn && learn.searchKnowledge ? learn.searchKnowledge(q, 1)[0] : null;
      if (k && k.of >= (strong ? 0.6 : 0.5) && k.score >= 2.5) return R("library", say(te, `📚 "${k.title}" ప్రకారం: `, `📚 From "${k.title}": `) + clip(k.text, 600), "thinking");
      const m = searchPosts(q, strong ? 0.66 : 0.5);
      if (m) return R("media", say(te, "📰 హ్యాబిట్ మీడియాలో దీని గురించి: ", "📰 Here's what Habit Media has on this: ") + `${String(m.it.title).replace(/^[^\p{L}\p{N}]+/u, "")}\n${clip(m.it.body, 650)}`, "thinking", { sources: srcOf(m.it) });
      if (!strong || DEFINE.test(q)) { const wk = await wiki(q, te); if (wk) return wk; }
    } catch (e) { console.error("Self brain:", e.message); }
    return strong ? null : fallback(te, ctx, info);
  }
  function fallback(te, ctx, info) {
    return R("fallback", say(te, "క్షమించండి, దీనికి నా సొంత మెదడు దగ్గర ఇంకా జవాబు లేదు. ", "Sorry, my own brain doesn't have an answer for this yet. ") + whyLine(info, te, ctx && ctx.admin) + " " +
      say(te, "ఈలోగా " + CAN(true) + " 🐾", "Meanwhile " + CAN(false) + " 🐾"), "shy", { weak: true });
  }
  /** A photo, and no AI that can see right now. */
  function noEyes(lang, ctx = {}, info = {}, text = "") {
    const te = useTe(String(text || ""), lang) || (lang === "auto" && !String(text || "").trim());
    return R("photo", say(te, "అయ్యో! ఇప్పుడు నేను ఫోటోలు చూడలేను. ", "Oh no! I can't look at photos right now. ") + whyLine(info, te, ctx.admin) + say(te, " కొంచెం తర్వాత మళ్ళీ చూపించండి. 🙏", " Please show me again later. 🙏"), "sad");
  }

  // ---------- a good-morning briefing, without AI ----------
  const GOOD = [["ఈరోజు మీకు మంచి రోజు కావాలి! 😊", "Have a lovely day! 😊"], ["చిన్న చిరునవ్వుతో రోజు మొదలుపెట్టండి! 🌞", "Start the day with a little smile! 🌞"],
    ["నీళ్ళు బాగా తాగండి, సంతోషంగా ఉండండి! 💧", "Drink plenty of water and stay happy! 💧"], ["మీరు అనుకున్నది ఈరోజు సాధిస్తారు! 💪", "You'll do what you set out to do today! 💪"]];
  async function briefing(ctx = {}, lang = "auto") {
    const te = lang !== "en";
    const date = istDate(), p = P.panchang(date);
    const parts = [say(te, `శుభోదయం! ఈరోజు ${dateSay(date, true)}.`, `Good morning! Today is ${dateSay(date, false)}.`)];
    try { const f = trends ? trends.festivalsOn(date).filter(x => !x.season) : []; if (f.length) parts.push(say(te, `ఈరోజు ${f.map(x => x.te).join(", ")}! శుభాకాంక్షలు! 🎉`, `Today is ${f.map(x => x.en).join(" and ")}! Happy festival! 🎉`)); } catch (e) { }
    parts.push(say(te, `తిథి ${p.tithi}, నక్షత్రం ${p.nakshatra}. రాహుకాలం ${time12(p.rahu[0])} నుంచి ${time12(p.rahu[1])} వరకు.`, `Rahu kalam is from ${time12(p.rahu[0])} to ${time12(p.rahu[1])}.`));
    if (ctx.city) { try { const w = await weatherFor(TE_CITY[ctx.city] || ctx.city, te, isTe(ctx.city) ? ctx.city : ""); if (w) parts.push(w.text.replace(/^🌤️\s*/, "")); } catch (e) { } }
    const rem = (ctx.reminders || []).slice(0, 4), todo = (ctx.todos || []).slice(0, 4);
    if (rem.length) parts.push(say(te, `ఈరోజు మీ రిమైండర్లు: ${rem.join("; ")}.`, `Your reminders today: ${rem.join("; ")}.`));
    if (todo.length) parts.push(say(te, `చేయాల్సిన పనులు: ${todo.join("; ")}.`, `Still to do: ${todo.join("; ")}.`));
    const g = GOOD[Number(date.slice(8)) % GOOD.length];
    parts.push(te ? g[0] : g[1]);
    stats.answered++; stats.skills.briefing = (stats.skills.briefing || 0) + 1;
    return { text: parts.join(" "), emotion: "happy", sources: [], self: true };
  }

  return { answer, noEyes, briefing, stats: () => ({ ...stats, skills: { ...stats.skills } }), _test: { words, stem, same, useTe, placeIn, clip, searchPosts } };
};
