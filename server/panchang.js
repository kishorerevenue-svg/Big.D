// Today's Telugu panchangam, worked out on our own server (no website, no AI, no library):
// the Sun and Moon positions (Jean Meeus, "Astronomical Algorithms", ch. 25 and 47, accurate to about a minute
// of arc, so the times are within a few minutes), Lahiri ayanamsa, sunrise and sunset, and from those:
// samvatsaram, ayanam, ruthuvu, masam (amanta, with adhika masam), paksham, tithi, nakshatram, yogam, karanam,
// Rahu kalam, Yamagandam and Gulika kalam, with the time each tithi/nakshatram ends.
const D2R = Math.PI / 180;
const sin = d => Math.sin(d * D2R), cos = d => Math.cos(d * D2R);
const norm = d => ((d % 360) + 360) % 360;
const jdOf = ms => ms / 86400000 + 2440587.5;
const msOf = jd => (jd - 2440587.5) * 86400000;

/** The Sun's apparent ecliptic longitude (degrees). */
function sunLon(jd) {
  const T = (jd - 2451545) / 36525;
  const L0 = 280.46646 + 36000.76983 * T + 0.0003032 * T * T;
  const M = 357.52911 + 35999.05029 * T - 0.0001537 * T * T;
  const C = (1.914602 - 0.004817 * T - 0.000014 * T * T) * sin(M) + (0.019993 - 0.000101 * T) * sin(2 * M) + 0.000289 * sin(3 * M);
  const om = 125.04 - 1934.136 * T;
  return norm(L0 + C - 0.00569 - 0.00478 * sin(om));
}

// Meeus table 47.A: multiples of D, M, M', F and the sine coefficient (millionths of a degree)
const MOON = [
  [0, 0, 1, 0, 6288774], [2, 0, -1, 0, 1274027], [2, 0, 0, 0, 658314], [0, 0, 2, 0, 213618], [0, 1, 0, 0, -185116],
  [0, 0, 0, 2, -114332], [2, 0, -2, 0, 58793], [2, -1, -1, 0, 57066], [2, 0, 1, 0, 53322], [2, -1, 0, 0, 45758],
  [0, 1, -1, 0, -40923], [1, 0, 0, 0, -34720], [0, 1, 1, 0, -30383], [2, 0, 0, -2, 15327], [0, 0, 1, 2, -12528],
  [0, 0, 1, -2, 10980], [4, 0, -1, 0, 10675], [0, 0, 3, 0, 10034], [4, 0, -2, 0, 8548], [2, 1, -1, 0, -7888],
  [2, 1, 0, 0, -6766], [1, 0, -1, 0, -5163], [1, 1, 0, 0, 4987], [2, -1, 1, 0, 4036], [2, 0, 2, 0, 3994],
  [4, 0, 0, 0, 3861], [2, 0, -3, 0, 3665], [0, 1, -2, 0, -2689], [2, 0, -1, 2, -2602], [2, -1, -2, 0, 2390],
  [1, 0, 1, 0, -2348], [2, -2, 0, 0, 2236], [0, 1, 2, 0, -2120], [0, 2, 0, 0, -2069], [2, -2, -1, 0, 2048],
  [2, 0, 1, -2, -1773], [2, 0, 0, 2, -1595], [4, -1, -1, 0, 1215], [0, 0, 2, 2, -1110], [3, 0, -1, 0, -892],
  [2, 1, 1, 0, -810], [4, -1, -2, 0, 759], [0, 2, -1, 0, -713], [2, 2, -1, 0, -700], [2, 1, -2, 0, 691],
  [2, -1, 0, -2, 596], [4, 0, 1, 0, 549], [0, 0, 4, 0, 537], [4, -1, 0, 0, 520], [1, 0, -2, 0, -487],
  [2, 1, 0, -2, -399], [0, 0, 2, -2, -381], [1, 1, 1, 0, 351], [3, 0, -2, 0, -340], [4, 0, -3, 0, 330],
  [2, -1, 2, 0, 327], [0, 2, 1, 0, -323], [1, 1, -1, 0, 299], [2, 0, 3, 0, 294]
];

/** The Moon's apparent ecliptic longitude (degrees). */
function moonLon(jd) {
  const T = (jd - 2451545) / 36525, T2 = T * T, T3 = T2 * T, T4 = T3 * T;
  const Lp = 218.3164477 + 481267.88123421 * T - 0.0015786 * T2 + T3 / 538841 - T4 / 65194000;
  const D = 297.8501921 + 445267.1114034 * T - 0.0018819 * T2 + T3 / 545868 - T4 / 113065000;
  const M = 357.5291092 + 35999.0502909 * T - 0.0001536 * T2 + T3 / 24490000;
  const Mp = 134.9633964 + 477198.8675055 * T + 0.0087414 * T2 + T3 / 69699 - T4 / 14712000;
  const F = 93.2720950 + 483202.0175233 * T - 0.0036539 * T2 - T3 / 3526000 + T4 / 863310000;
  const E = 1 - 0.002516 * T - 0.0000074 * T2;
  const A1 = 119.75 + 131.849 * T, A2 = 53.09 + 479264.29 * T;
  let s = 0;
  for (const [d, m, mp, f, c] of MOON) s += c * (m === 0 ? 1 : Math.abs(m) === 1 ? E : E * E) * sin(d * D + m * M + mp * Mp + f * F);
  s += 3958 * sin(A1) + 1962 * sin(Lp - F) + 318 * sin(A2);
  const om = 125.04452 - 1934.136261 * T;
  const nut = -0.004778 * sin(om) - 0.000367 * sin(2 * (280.4665 + 36000.7698 * T)) - 0.0000639 * sin(2 * Lp) + 0.0000583 * sin(2 * om);
  return norm(Lp + s / 1e6 + nut);
}

/** Lahiri (Chitrapaksha) ayanamsa: 23°51′11″ in 2000, moving about 50.29″ a year. */
const ayanamsa = jd => 23.8531 + 1.39689 * (jd - 2451545) / 36525;
const sidereal = (lon, jd) => norm(lon - ayanamsa(jd));
const elongation = jd => norm(moonLon(jd) - sunLon(jd));

/** The moment (ms) after [fromMs] when f(jd) (an angle that keeps growing) reaches [target], within [maxDays]. */
function crossing(f, target, fromMs, maxDays = 2) {
  const at = ms => norm(f(jdOf(ms)) - target + 180) - 180;   // < 0 before, > 0 after
  let a = fromMs, step = 3600000, end = fromMs + maxDays * 86400000;
  while (a < end && at(a + step) < 0) a += step;
  if (a >= end) return null;
  let b = a + step;
  for (let i = 0; i < 40 && b - a > 20000; i++) { const m = (a + b) / 2; if (at(m) < 0) a = m; else b = m; }
  return (a + b) / 2;
}

/** The new moon just before [ms], or the next one after it (dir +1). */
function newMoon(ms, dir = -1) {
  const e = elongation(jdOf(ms));                 // the Moon gains about 12.19° a day on the Sun
  const guess = dir < 0 ? ms - e / 12.19 * 86400000 : ms + (360 - e) / 12.19 * 86400000;
  return crossing(elongation, 0, guess - 1.5 * 86400000, 3);
}

/** Sunrise or sunset (ms) at a place on the given IST date (NOAA method, with refraction). */
function sunEvent(y, mo, d, lat, lon, rise) {
  const noonUtc = Date.UTC(y, mo - 1, d, 12) - lon / 15 * 3600000;
  let t = noonUtc;
  for (let k = 0; k < 3; k++) {
    const jd = jdOf(t), T = (jd - 2451545) / 36525;
    const L = sunLon(jd), eps = 23.439291 - 0.0130042 * T;
    const dec = Math.asin(sin(eps) * sin(L)) / D2R;
    const ra = norm(Math.atan2(cos(eps) * sin(L), cos(L)) / D2R);
    const L0 = norm(280.46646 + 36000.76983 * T);
    const eqt = norm(L0 - 0.0057183 - ra + 180) - 180;            // equation of time (degrees)
    const transit = Date.UTC(y, mo - 1, d, 12) - (lon + eqt) / 15 * 3600000;
    const cosH = (sin(-0.8333) - sin(lat) * sin(dec)) / (cos(lat) * cos(dec));
    const H = Math.acos(Math.max(-1, Math.min(1, cosH))) / D2R;
    t = transit + (rise ? -1 : 1) * H / 15 * 3600000;
  }
  return t;
}

const TITHI = ["పాడ్యమి", "విదియ", "తదియ", "చవితి", "పంచమి", "షష్ఠి", "సప్తమి", "అష్టమి", "నవమి", "దశమి", "ఏకాదశి", "ద్వాదశి", "త్రయోదశి", "చతుర్దశి"];
const tithiName = i => i === 14 ? "పౌర్ణమి" : i === 29 ? "అమావాస్య" : TITHI[i % 15];
const NAKSHATRA = ["అశ్విని", "భరణి", "కృత్తిక", "రోహిణి", "మృగశిర", "ఆర్ద్ర", "పునర్వసు", "పుష్యమి", "ఆశ్లేష", "మఖ", "పుబ్బ", "ఉత్తర", "హస్త", "చిత్త",
  "స్వాతి", "విశాఖ", "అనూరాధ", "జ్యేష్ఠ", "మూల", "పూర్వాషాఢ", "ఉత్తరాషాఢ", "శ్రవణం", "ధనిష్ఠ", "శతభిషం", "పూర్వాభాద్ర", "ఉత్తరాభాద్ర", "రేవతి"];
const YOGA = ["విష్కంభం", "ప్రీతి", "ఆయుష్మాన్", "సౌభాగ్యం", "శోభనం", "అతిగండం", "సుకర్మ", "ధృతి", "శూలం", "గండం", "వృద్ధి", "ధ్రువం", "వ్యాఘాతం", "హర్షణం",
  "వజ్రం", "సిద్ధి", "వ్యతీపాతం", "వరీయాన్", "పరిఘం", "శివం", "సిద్ధం", "సాధ్యం", "శుభం", "శుక్లం", "బ్రహ్మం", "ఐంద్రం", "వైధృతి"];
const KARANA_MOVING = ["బవ", "బాలవ", "కౌలవ", "తైతుల", "గరజి", "వణిజ", "విష్టి"];
const karanaName = k => k === 0 ? "కింస్తుఘ్న" : k === 57 ? "శకుని" : k === 58 ? "చతుష్పాద" : k === 59 ? "నాగవ" : KARANA_MOVING[(k - 1) % 7];
const MASA = ["చైత్ర", "వైశాఖ", "జ్యేష్ఠ", "ఆషాఢ", "శ్రావణ", "భాద్రపద", "ఆశ్వయుజ", "కార్తీక", "మార్గశిర", "పుష్య", "మాఘ", "ఫాల్గుణ"];
const RUTHU = ["వసంత", "గ్రీష్మ", "వర్ష", "శరత్", "హేమంత", "శిశిర"];
const VARA = ["ఆదివారం", "సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం", "శుక్రవారం", "శనివారం"];
const SAMVAT = ["ప్రభవ", "విభవ", "శుక్ల", "ప్రమోదూత", "ప్రజోత్పత్తి", "ఆంగీరస", "శ్రీముఖ", "భావ", "యువ", "ధాత", "ఈశ్వర", "బహుధాన్య", "ప్రమాది", "విక్రమ",
  "వృష", "చిత్రభాను", "స్వభాను", "తారణ", "పార్థివ", "వ్యయ", "సర్వజిత్", "సర్వధారి", "విరోధి", "వికృతి", "ఖర", "నందన", "విజయ", "జయ", "మన్మథ", "దుర్ముఖి",
  "హేవిళంబి", "విళంబి", "వికారి", "శార్వరి", "ప్లవ", "శుభకృత్", "శోభకృత్", "క్రోధి", "విశ్వావసు", "పరాభవ", "ప్లవంగ", "కీలక", "సౌమ్య", "సాధారణ",
  "విరోధికృత్", "పరీధావి", "ప్రమాదీచ", "ఆనంద", "రాక్షస", "నల", "పింగళ", "కాళయుక్తి", "సిద్ధార్థి", "రౌద్రి", "దుర్మతి", "దుందుభి", "రుధిరోద్గారి",
  "రక్తాక్షి", "క్రోధన", "అక్షయ"];
const MONTH = ["జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"];
// which eighth of the day (from sunrise) for Sun..Sat
const RAHU = [8, 2, 7, 5, 6, 4, 3], YAMA = [5, 4, 3, 2, 1, 7, 6], GULIKA = [7, 6, 5, 4, 3, 2, 1];

/** The amanta month that a new moon starts: named from the Sun's sidereal sign then; adhika if the Sun stays in that sign all month. */
function masaAt(ms) {
  const nm = newMoon(ms, -1), next = newMoon(ms, +1);
  const r1 = Math.floor(sidereal(sunLon(jdOf(nm)), jdOf(nm)) / 30), r2 = Math.floor(sidereal(sunLon(jdOf(next)), jdOf(next)) / 30);
  return { index: (r1 + 1) % 12, adhika: r1 === r2, start: nm };
}

/** The Telugu year (samvatsaram): changes at Ugadi (the start of Chaitra). */
function samvatsara(ms) {
  let t = ms;
  for (let i = 0; i < 14; i++) {
    const m = masaAt(t);
    if (m.index === 0 && !m.adhika) {
      const y = new Date(m.start + 5.5 * 3600000).getUTCFullYear();
      return SAMVAT[(((y - 1987) % 60) + 60) % 60];
    }
    t = m.start - 3 * 86400000;
  }
  return "";
}

const PLACES = { vijayawada: { name: "విజయవాడ", lat: 16.5062, lon: 80.6480 }, hyderabad: { name: "హైదరాబాద్", lat: 17.3850, lon: 78.4867 }, visakhapatnam: { name: "విశాఖపట్నం", lat: 17.6868, lon: 83.2185 } };

/** Everything for one day (an IST date "YYYY-MM-DD") at a place. */
function panchang(dateIst, place = "vijayawada") {
  const p = PLACES[place] || PLACES.vijayawada;
  const [y, mo, d] = dateIst.split("-").map(Number);
  const rise = sunEvent(y, mo, d, p.lat, p.lon, true), set = sunEvent(y, mo, d, p.lat, p.lon, false);
  const jd = jdOf(rise);
  const e = elongation(jd), ti = Math.floor(e / 12);
  const tithiEnd = crossing(elongation, ((ti + 1) * 12) % 360, rise);
  const moonS = sidereal(moonLon(jd), jd), sunS = sidereal(sunLon(jd), jd);
  const ni = Math.floor(moonS / (360 / 27));
  const nakEnd = crossing(j => sidereal(moonLon(j), j), ((ni + 1) * 360 / 27) % 360, rise);
  const yi = Math.floor(norm(moonS + sunS) / (360 / 27));
  const yogaEnd = crossing(j => norm(sidereal(moonLon(j), j) + sidereal(sunLon(j), j)), ((yi + 1) * 360 / 27) % 360, rise);
  const ki = Math.floor(e / 6);
  const masa = masaAt(rise);
  const weekday = new Date(Date.UTC(y, mo - 1, d)).getUTCDay();
  const part = (set - rise) / 8;
  const span = n => [rise + (n - 1) * part, rise + n * part];
  return {
    date: dateIst, place: p.name, weekday, sunrise: rise, sunset: set,
    samvatsara: samvatsara(rise), ayanam: sunS >= 90 && sunS < 270 ? "దక్షిణాయనం" : "ఉత్తరాయణం",
    ruthu: RUTHU[Math.floor(masa.index / 2)], masa: MASA[masa.index], adhika: masa.adhika, masaIndex: masa.index,
    paksha: ti < 15 ? "శుక్ల పక్షం" : "బహుళ పక్షం", tithiIndex: ti,
    tithi: tithiName(ti), tithiEnd, nextTithi: tithiName((ti + 1) % 30),
    nakshatra: NAKSHATRA[ni], nakIndex: ni, nakEnd, nextNakshatra: NAKSHATRA[(ni + 1) % 27],
    yoga: YOGA[yi], yogaEnd, karana: karanaName(ki),
    rahu: span(RAHU[weekday]), yama: span(YAMA[weekday]), gulika: span(GULIKA[weekday])
  };
}

/** "8:59" on a 12-hour clock (India time). */
const hm = ms => { const t = new Date(ms + 5.5 * 3600000); const h = t.getUTCHours() % 12 || 12; return `${h}:${String(t.getUTCMinutes()).padStart(2, "0")}`; };
const partOf = h => h < 4 ? "తెల్లవారుజామున" : h < 12 ? "ఉదయం" : h < 16 ? "మధ్యాహ్నం" : h < 19 ? "సాయంత్రం" : "రాత్రి";
const short = { "తెల్లవారుజామున": "తె.", "ఉదయం": "ఉ.", "మధ్యాహ్నం": "మ.", "సాయంత్రం": "సా.", "రాత్రి": "రా." };
/** "రాత్రి 11:05" (or "రేపు ఉదయం 5:53") for an end time. */
function when(ms, dateIst) {
  if (!ms) return "";
  const t = new Date(ms + 5.5 * 3600000);
  return (t.toISOString().slice(0, 10) !== dateIst ? "రేపు " : "") + partOf(t.getUTCHours()) + " " + hm(ms);
}
/** "ఉ. 8:59–10:25" for a time span. */
const span2 = ([a, b]) => `${short[partOf(new Date(a + 5.5 * 3600000).getUTCHours())]} ${hm(a)}–${hm(b)}`;

/** The Media post: a title and the text, in Telugu. */
function post(dateIst, place = "vijayawada") {
  const p = panchang(dateIst, place);
  const [y, mo, d] = dateIst.split("-").map(Number);
  const special = [];
  if (p.tithiIndex === 10 || p.tithiIndex === 25) special.push("ఏకాదశి");
  if (p.tithiIndex === 14) special.push("పౌర్ణమి");
  if (p.tithiIndex === 29) special.push("అమావాస్య");
  if (p.masaIndex === 7 && p.weekday === 1 && !p.adhika) special.push("కార్తీక సోమవారం");
  const lines = [
    `శ్రీ ${p.samvatsara} నామ సంవత్సరం, ${p.ayanam}, ${p.ruthu} ఋతువు, ${p.adhika ? "అధిక " : ""}${p.masa} మాసం, ${p.paksha}.`,
    `🌙 తిథి: ${p.tithi} ${when(p.tithiEnd, dateIst)} వరకు, తర్వాత ${p.nextTithi}.`,
    `⭐ నక్షత్రం: ${p.nakshatra} ${when(p.nakEnd, dateIst)} వరకు, తర్వాత ${p.nextNakshatra}.`,
    `యోగం: ${p.yoga} · కరణం: ${p.karana}.`,
    `🌅 సూర్యోదయం ఉ. ${hm(p.sunrise)} · 🌇 సూర్యాస్తమయం సా. ${hm(p.sunset)}.`,
    `⛔ రాహుకాలం ${span2(p.rahu)} · యమగండం ${span2(p.yama)} · గుళిక ${span2(p.gulika)}.`,
    special.length ? `✨ ఈరోజు: ${special.join(", ")}.` : "",
    `(${p.place} సమయాలు. మీ ఊరిలో కొన్ని నిమిషాలు తేడా ఉండవచ్చు. ముఖ్యమైన కార్యక్రమాలకు మీ పురోహితులను సంప్రదించండి.)`
  ].filter(Boolean);
  return { title: `నేటి పంచాంగం · ${VARA[p.weekday]}, ${d} ${MONTH[mo - 1]} ${y}`, body: lines.join("\n"), emoji: "📿", data: p };
}

module.exports = { panchang, post, sunLon, moonLon, elongation, newMoon, crossing, jdOf, sidereal, masaAt, samvatsara, sunEvent, PLACES, VARA, MONTH };
