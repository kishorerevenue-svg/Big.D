// Habit studies by itself — no Claude, no cost. A general question it couldn't answer well (even asked once, by one
// person) is looked up a few minutes later in free, public places:
//   📚 the public library (GOs, circulars, notices people added), 📖 Wikipedia (Telugu and English),
//   🦆 DuckDuckGo's instant answers;
// then written as a short answer by our own brain (the home PC's bigger model, or the server's when it can write),
// or — with no brain that can write — the best few sentences from those places, as they are.
// The answer is kept (answers.js, "self") with its sources, so the next time the pet answers at once. The owner sees
// and can delete each one on the Teach page. Nothing about the person who asked is used or kept.
module.exports = function makeSelfStudy({ learn, ownBrain, fetchImpl }) {
  const get = (u, ms = 8000) => (fetchImpl || fetch)(u, { headers: { "user-agent": "Habit-AI/1.0 (self study; https://bigdai.tech)" }, signal: AbortSignal.timeout(ms) });
  const json = async (u, ms) => { const r = await get(u, ms); if (!r.ok) throw new Error("http " + r.status); return r.json(); };
  const isTe = s => /[ఀ-౿]/.test(String(s || ""));
  const ASK_WORDS = /\b(what|whats|what's|who|whom|which|where|when|why|how|is|are|was|were|do|does|did|can|could|should|tell|me|about|explain|the|a|an|of|for|to|in|on|meaning|define|please|bro|sir|give|details|information|info)\b/gi;
  const TE_ASK = /ఏమిటి|ఏంటి|ఎవరు|ఎక్కడ|ఎప్పుడు|ఎందుకు|ఎలా|ఎంత|అంటే|గురించి|చెప్పు|చెప్పండి|వివరించు|వివరాలు|సమాచారం|ఏమిటో|ఎవరో|దయచేసి|నాకు/g;
  const termOf = q => String(q).replace(ASK_WORDS, " ").replace(TE_ASK, " ").replace(/[?.!,;:"'()]/g, " ").replace(/\s+/g, " ").trim();
  const tokens = s => (String(s).toLowerCase().match(/[\p{L}\p{M}\p{N}]+/gu) || []).filter(t => t.length > 1);
  const clean = s => String(s || "").replace(/\s+/g, " ").replace(/\[\d+\]/g, "").trim();

  async function wikipedia(term, host) {
    const s = await json(`https://${host}.wikipedia.org/w/api.php?action=query&list=search&srsearch=${encodeURIComponent(term)}&srlimit=2&format=json`);
    const hits = (((s || {}).query || {}).search || []).map(x => x.title);
    const out = [];
    const want = tokens(term);
    for (const title of hits) {
      if (!tokens(title).some(w => want.some(x => x.startsWith(w.slice(0, 4)) || w.startsWith(x.slice(0, 4))))) continue;   // not about it
      try {
        const sum = await json(`https://${host}.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`);
        if (sum && sum.extract && sum.type !== "disambiguation")
          out.push({ title: "Wikipedia: " + title, url: ((sum.content_urls || {}).desktop || {}).page || `https://${host}.wikipedia.org/wiki/${encodeURIComponent(title)}`, text: clean(sum.extract) });
      } catch (e) { }
    }
    return out;
  }
  async function duck(term) {
    const j = await json(`https://api.duckduckgo.com/?q=${encodeURIComponent(term)}&format=json&no_html=1&skip_disambig=1`, 7000);
    return j && j.AbstractText ? [{ title: (j.AbstractSource || "DuckDuckGo") + ": " + (j.Heading || term), url: j.AbstractURL || "", text: clean(j.AbstractText) }] : [];
  }

  /** Free, public facts about a question: [{ title, url, text }]. */
  async function gather(q, te) {
    const term = termOf(q);
    if (term.length < 3) return [];
    const jobs = [];
    // the public library first (what people of this place added)
    const lib = (learn.searchKnowledge ? learn.searchKnowledge(q, 2, false, "", 1.0) : []).filter(h => h.of >= 0.4)
      .map(h => ({ title: "Habit library: " + h.title, url: "", text: clean(h.text).slice(0, 1500) }));
    if (te || isTe(q)) jobs.push(wikipedia(term, "te").catch(() => []));
    if (!isTe(term)) { jobs.push(wikipedia(term, "en").catch(() => [])); jobs.push(duck(term).catch(() => [])); }
    const got = (await Promise.all(jobs)).flat();
    const seen = new Set();
    return lib.concat(got).filter(s => s.text && s.text.length > 40 && !seen.has(s.text.slice(0, 80)) && seen.add(s.text.slice(0, 80))).slice(0, 4);
  }

  /** The best few sentences for the question, as they are (when no brain can write). */
  function extract(q, sources, max = 4) {
    const want = new Set(tokens(termOf(q)));
    const sents = [];
    // sentences (short pieces like "G.O. Ms. No." are joined to the next one, so abbreviations don't break a sentence)
    const split = text => { const out = []; let buf = ""; for (const p of String(text).split(/(?<=[.!?।])\s+/)) { buf = buf ? buf + " " + p : p; if (buf.length >= 40 && !/\b(?:No|Ms|Mr|Dr|Sri|Smt|Rs|Govt|St|vs|i\.e|e\.g|[A-Z])\.$/.test(buf)) { out.push(buf); buf = ""; } } if (buf) out.push(buf); return out; };
    sources.forEach((s, si) => split(s.text).forEach((x, k) => {
      const t = x.trim(); if (t.length < 25 || t.length > 400) return;
      const toks = tokens(t); let hit = 0; for (const w of toks) if (want.has(w)) hit++;
      sents.push({ t, score: hit * 2 + (k === 0 ? 2 : 0) - si * 0.5 - k * 0.15, si, k });
    }));
    const best = sents.sort((a, b) => b.score - a.score).slice(0, max).sort((a, b) => a.si - b.si || a.k - b.k);
    return best.map(x => x.t).join(" ");
  }

  const SCHEMA = { type: "object", properties: { answer: { type: "string" }, skip: { type: "boolean" } }, required: ["answer", "skip"] };
  /** Studies one question: { answer, sources, how } or null (nothing found). */
  async function study(q, lang) {
    const te = lang === "te" || isTe(q);
    const sources = await gather(q, te);
    if (!sources.length) return null;
    let answer = "", how = "extract";
    try {
      if (ownBrain && ownBrain.canWrite && ownBrain.canWrite() && await ownBrain.ready()) {
        const out = await ownBrain.writeJson({
          system: "You write short, correct answers for Habit AI, a friendly phone helper for people in Andhra Pradesh and Telangana, India. " +
            "Use ONLY the facts given below; if they don't answer the question, reply with skip true. " +
            `Answer in ${te ? "simple spoken Telugu, in Telugu script" : "simple, clear English"}, in 3 to 5 short sentences that will be read aloud. ` +
            "Reply only with JSON: {\"answer\": \"...\", \"skip\": false}",
          prompt: "Question: " + q + "\n\nFacts:\n" + sources.map(s => `--- ${s.title} ---\n${s.text.slice(0, 1400)}`).join("\n\n"),
          schema: SCHEMA, maxTokens: 500, timeoutMs: 240000 });
        if (out && !out.skip && String(out.answer || "").trim().length > 20) { answer = String(out.answer).trim(); how = "brain"; }
        else if (out && out.skip) return null;
      }
    } catch (e) { }
    if (!answer) answer = extract(q, sources);
    if (answer.length < 30) return null;
    return { answer: answer.slice(0, 1500), sources: sources.filter(s => s.url).slice(0, 3).map(s => ({ title: s.title.slice(0, 90), url: s.url })), how };
  }

  return { study, gather, extract, termOf };
};
