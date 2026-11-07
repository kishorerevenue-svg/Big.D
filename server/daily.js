// Daily useful posts for Media, made by our own server (no AI needed, so they are free and always on time):
//  - 📿 today's panchangam (devotional), every morning
//  - 🌧️ weather warnings for farmers (farming): heavy rain, heat, strong wind or cold, by town, from Open-Meteo
//  - 🌾 mandi crop prices (farming), from the government's open data (data.gov.in), when DATA_GOV_KEY is set
const panchang = require("./panchang.js");

module.exports = function makeDaily({ getJson }) {
  const get = getJson || (async (u, ms = 12000) => { const r = await fetch(u, { headers: { "user-agent": "BigD-AI/1.0" }, signal: AbortSignal.timeout(ms) }); if (!r.ok) throw new Error(u.split("?")[0] + " said " + r.status); return r.json(); });
  const istDate = (plusDays = 0) => new Date(Date.now() + 5.5 * 3600e3 + plusDays * 86400e3).toISOString().slice(0, 10);
  const MONTH = ["జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"];
  const nice = d => { const [, m, dd] = d.split("-").map(Number); return `${dd} ${MONTH[m - 1]}`; };

  // ---------- panchangam ----------
  function panchangPost(date = istDate()) {
    const p = panchang.post(date);
    return { cat: "devotional", title: p.title, body: p.body, emoji: "📿", mood: "devotional", issue: "festivals", kind: "panchang", key: "panchang:" + date, scene: "temple sunrise" };
  }

  // ---------- weather warnings for farmers ----------
  const TOWNS = [
    ["శ్రీకాకుళం", 18.30, 83.90], ["విజయనగరం", 18.11, 83.40], ["విశాఖపట్నం", 17.69, 83.22], ["కాకినాడ", 16.99, 82.25], ["రాజమహేంద్రవరం", 17.00, 81.80],
    ["ఏలూరు", 16.71, 81.10], ["మచిలీపట్నం", 16.19, 81.14], ["విజయవాడ", 16.51, 80.65], ["గుంటూరు", 16.31, 80.44], ["ఒంగోలు", 15.51, 80.05],
    ["నెల్లూరు", 14.44, 79.99], ["తిరుపతి", 13.63, 79.42], ["కడప", 14.47, 78.82], ["కర్నూలు", 15.83, 78.04], ["అనంతపురం", 14.68, 77.60],
    ["హైదరాబాద్", 17.39, 78.49], ["వరంగల్", 17.97, 79.59], ["కరీంనగర్", 18.44, 79.13], ["నిజామాబాద్", 18.67, 78.09], ["ఖమ్మం", 17.25, 80.15],
    ["నల్గొండ", 17.05, 79.27], ["మహబూబ్‌నగర్", 16.74, 78.00], ["ఆదిలాబాద్", 19.66, 78.53], ["సంగారెడ్డి", 17.62, 78.08]
  ];
  const ADVICE = {
    rain: "కోసిన పంటను, ధాన్యాన్ని కప్పి ఉంచండి లేదా సురక్షిత ప్రదేశానికి తరలించండి. ఈ రోజుల్లో పురుగుమందులు, ఎరువులు చల్లకండి. పొలంలో నీరు నిలవకుండా కాలువలు శుభ్రం చేయండి. పిడుగులు పడే అవకాశం ఉంటే చెట్ల కింద నిలబడకండి.",
    heat: "నీటిని సాయంత్రం లేదా తెల్లవారుజామున పెట్టండి. మధ్యాహ్నం 12 నుంచి 3 వరకు పొలం పనులు తగ్గించండి. పశువులకు నీడ, తగినంత నీరు ఇవ్వండి. మీరూ ఎక్కువ నీళ్లు తాగండి.",
    wind: "అరటి, బొప్పాయి వంటి మొక్కలకు ఊతం కట్టండి. పశువుల పాకలు, షెడ్ల పైకప్పులు గట్టిగా కట్టండి. విద్యుత్ తీగల దగ్గరికి వెళ్లకండి.",
    cold: "నారుమళ్లను రాత్రిపూట కప్పండి. పశువులను, ముఖ్యంగా దూడలను చలి నుంచి కాపాడండి. పెద్దవాళ్లు, పిల్లలు వెచ్చగా ఉండేలా చూసుకోండి."
  };

  /** Warnings for one day (0 = today, 1 = tomorrow). null when nothing needs a warning. */
  async function weatherPost(dayAhead = 1) {
    const lat = TOWNS.map(t => t[1]).join(","), lon = TOWNS.map(t => t[2]).join(",");
    const data = await get(`https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&daily=precipitation_sum,precipitation_probability_max,temperature_2m_max,temperature_2m_min,wind_gusts_10m_max&timezone=Asia%2FKolkata&forecast_days=3`);
    const list = Array.isArray(data) ? data : [data];
    const found = { rain: [], heavy: [], heat: [], wind: [], cold: [] };
    list.forEach((w, i) => {
      const d = w.daily || {}, k = dayAhead;
      const rain = (d.precipitation_sum || [])[k], prob = (d.precipitation_probability_max || [])[k];
      const tmax = (d.temperature_2m_max || [])[k], tmin = (d.temperature_2m_min || [])[k], gust = (d.wind_gusts_10m_max || [])[k];
      const name = TOWNS[i][0];
      if (rain >= 64.5 && prob >= 50) found.heavy.push(`${name} (${Math.round(rain)} మి.మీ.)`);
      else if (rain >= 35 && prob >= 60) found.rain.push(`${name} (${Math.round(rain)} మి.మీ.)`);
      if (tmax >= 41) found.heat.push(`${name} (${Math.round(tmax)}°C)`);
      if (gust >= 55) found.wind.push(`${name} (${Math.round(gust)} కి.మీ./గం.)`);
      if (tmin <= 12) found.cold.push(`${name} (${Math.round(tmin)}°C)`);
    });
    const day = istDate(dayAhead), word = dayAhead === 1 ? "రేపు" : "ఈరోజు";
    const parts = [];
    if (found.heavy.length) parts.push(`🌧️ భారీ వర్షాలు: ${found.heavy.join(", ")}.`);
    if (found.rain.length) parts.push(`🌦️ బలమైన వర్షాలు: ${found.rain.join(", ")}.`);
    if (found.heat.length) parts.push(`🔥 ఎండ తీవ్రత: ${found.heat.join(", ")}.`);
    if (found.wind.length) parts.push(`💨 బలమైన గాలులు: ${found.wind.join(", ")}.`);
    if (found.cold.length) parts.push(`🥶 చలి: ${found.cold.join(", ")}.`);
    if (!parts.length) return null;
    const tips = [];
    if (found.heavy.length || found.rain.length) tips.push("• " + ADVICE.rain);
    if (found.heat.length) tips.push("• " + ADVICE.heat);
    if (found.wind.length) tips.push("• " + ADVICE.wind);
    if (found.cold.length) tips.push("• " + ADVICE.cold);
    const what = found.heavy.length ? "భారీ వర్ష" : found.rain.length ? "వర్ష" : found.heat.length ? "ఎండ" : found.wind.length ? "గాలుల" : "చలి";
    // which warnings for which towns (without the numbers): the same warnings again in the morning are not posted twice
    const sig = Object.entries(found).filter(([, l]) => l.length).map(([k, l]) => k + "=" + l.map(t => t.split(" (")[0]).sort().join("+")).join(";");
    return {
      cat: "farming", kind: "weather", key: `weather:${day}:${dayAhead}`, day, sig, emoji: found.heavy.length || found.rain.length ? "🌧️" : found.heat.length ? "🔥" : found.wind.length ? "💨" : "🥶",
      mood: "useful", issue: "weather", scene: found.heavy.length || found.rain.length ? "heavy rain" : found.heat.length ? "hot sun" : found.wind.length ? "strong wind trees" : "foggy morning",
      title: `రైతులకు ${what} హెచ్చరిక · ${word}, ${nice(day)}`,
      body: `${word} ఈ ఊళ్లలో వాతావరణం జాగ్రత్తగా ఉండాలి:\n${parts.join("\n")}\n\nఏం చేయాలి:\n${tips.join("\n")}\n\n(Open-Meteo వాతావరణ అంచనా. అధికారిక హెచ్చరికల కోసం IMD, APSDMA, తెలంగాణ విపత్తు నిర్వహణ శాఖ సూచనలు పాటించండి.)`,
      source: "open-meteo.com", url: "https://open-meteo.com/"
    };
  }

  // ---------- mandi prices (needs a free key from data.gov.in) ----------
  const CROPS = { "Paddy(Dhan)(Common)": "వరి", "Paddy(Dhan)(Basmati)": "బాస్మతి వరి", "Cotton": "పత్తి", "Dry Chillies": "ఎండు మిర్చి", "Green Chilli": "పచ్చి మిర్చి",
    "Groundnut": "వేరుశనగ", "Maize": "మొక్కజొన్న", "Turmeric": "పసుపు", "Bengal Gram(Gram)(Whole)": "శనగలు", "Black Gram (Urd Beans)(Whole)": "మినుములు",
    "Green Gram (Moong)(Whole)": "పెసలు", "Red Gram": "కందులు", "Jowar(Sorghum)": "జొన్నలు", "Tomato": "టమాటా", "Onion": "ఉల్లిపాయలు", "Potato": "బంగాళాదుంప",
    "Banana": "అరటి", "Coconut": "కొబ్బరి", "Sunflower": "పొద్దుతిరుగుడు", "Castor Seed": "ఆముదాలు", "Brinjal": "వంకాయ", "Bhindi(Ladies Finger)": "బెండకాయ" };
  async function mandiPost() {
    const key = process.env.DATA_GOV_KEY;
    if (!key) return null;
    const rows = [];
    for (const state of ["Andhra Pradesh", "Telangana"]) {
      const j = await get(`https://api.data.gov.in/resource/9ef84268-d588-465a-a308-a864a43d0070?api-key=${encodeURIComponent(key)}&format=json&limit=1000&filters%5Bstate%5D=${encodeURIComponent(state)}`, 20000);
      for (const r of (j.records || [])) rows.push({ ...r, st: state });
    }
    const by = {};
    for (const r of rows) {
      const te = CROPS[r.commodity]; const price = Number(r.modal_price);
      if (!te || !(price > 0)) continue;
      (by[te] = by[te] || []).push({ price, market: r.market, st: r.st });
    }
    const names = Object.keys(by).sort((a, b) => by[b].length - by[a].length).slice(0, 10);
    if (names.length < 2) return null;
    const lines = names.map(n => {
      const l = by[n].sort((a, b) => a.price - b.price), mid = l[Math.floor(l.length / 2)].price, hi = l[l.length - 1];
      return `• ${n}: సగటు ₹${Math.round(mid).toLocaleString("en-IN")} / క్వింటాల్ (ఎక్కువ: ${hi.market}లో ₹${Math.round(hi.price).toLocaleString("en-IN")})`;
    });
    const day = istDate();
    return { cat: "farming", kind: "mandi", key: `mandi:${day}`, emoji: "🌾", mood: "useful", issue: "prices", scene: "vegetable market",
      title: `ఈరోజు మార్కెట్ ధరలు · ${nice(day)}`,
      body: `ఆంధ్రప్రదేశ్, తెలంగాణ మార్కెట్ యార్డుల్లో ఈరోజు ధరలు (మోడల్ ధర):\n${lines.join("\n")}\n\n(ప్రభుత్వ Agmarknet సమాచారం, data.gov.in. అమ్మే ముందు మీ దగ్గరి మార్కెట్ యార్డులో ధర తెలుసుకోండి.)`,
      source: "data.gov.in", url: "https://agmarknet.gov.in/" };
  }

  return { panchangPost, weatherPost, mandiPost, TOWNS };
};
