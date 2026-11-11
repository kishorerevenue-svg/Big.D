// Link previews: the title and main picture of a web page (like WhatsApp or X show for a link).
// Safety: only public internet addresses (never the server's own network), at most 4 redirects,
// at most 400 KB of the page is read (1.5 MB when the story's text is wanted), and every request has a time limit.
const dns = require("dns").promises;
const net = require("net");

const YT_OEMBED = process.env.YT_OEMBED_BASE || "https://www.youtube.com";
const str = (v, n) => String(v == null ? "" : v).trim().slice(0, n);
const ytId = u => { const m = String(u || "").match(/(?:youtube\.com\/(?:watch\?(?:.*&)?v=|shorts\/|embed\/|live\/)|youtu\.be\/)([\w-]{11})/); return m ? m[1] : ""; };
const hostOf = u => { try { return new URL(u).hostname.replace(/^www\.|^m\./, ""); } catch (e) { return ""; } };

function privateIp(a) {
  let x = String(a).toLowerCase();
  const mapped = x.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/); if (mapped) x = mapped[1];
  if (net.isIPv4(x)) return /^(0\.|10\.|127\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.|2(2[4-9]|[3-5]\d)\.)/.test(x);
  return x === "::" || x === "::1" || /^(fc|fd|fe[89ab]|ff|::ffff:)/.test(x);
}
async function publicHost(u) {
  try {
    const url = new URL(u); if (!/^https?:$/.test(url.protocol)) return false;
    if (process.env.PREVIEW_ALLOW_LOCAL === "1") return true;   // tests only
    const ips = await dns.lookup(url.hostname, { all: true });
    return ips.length > 0 && ips.every(({ address }) => !privateIp(address));
  } catch (e) { return false; }
}
const ent = s => String(s || "").replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#0?39;|&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
  .replace(/&#(\d+);/g, (m, d) => { try { return String.fromCodePoint(Number(d)); } catch (e) { return ""; } })
  .replace(/&#x([0-9a-f]+);/gi, (m, x) => { try { return String.fromCodePoint(parseInt(x, 16)); } catch (e) { return ""; } });
const SITE = { "youtube.com": "YouTube", "youtu.be": "YouTube", "instagram.com": "Instagram", "facebook.com": "Facebook", "fb.watch": "Facebook", "x.com": "X", "twitter.com": "X", "sharechat.com": "ShareChat", "moj.video": "Moj", "threads.net": "Threads" };

/** { url, site, title, image, yt, description, final } for a link, or null if it isn't a web link. Never throws.
 *  With { text: true } it also gives "text": the first paragraphs of the page (used to write news posts from the facts). */
async function preview(u, opts = {}) {
  const url = str(u, 600); if (!/^https?:\/\/\S+$/.test(url)) return null;
  const host = hostOf(url); if (!host) return null;
  const out = { url, site: SITE[host] || host, title: "", image: "", yt: "", description: "", final: url };
  const vid = ytId(url);
  if (vid) {
    out.yt = vid; out.image = `https://i.ytimg.com/vi/${vid}/hqdefault.jpg`;
    try {
      const r = await fetch(`${YT_OEMBED}/oembed?format=json&url=${encodeURIComponent("https://www.youtube.com/watch?v=" + vid)}`, { signal: AbortSignal.timeout(8000) });
      if (r.ok) { const j = await r.json(); out.title = str(j.title, 200); out.site = "YouTube · " + str(j.author_name, 60); }
    } catch (e) { }
    return out;
  }
  try {
    let at = url, r = null;
    for (let hop = 0; hop < 5; hop++) {
      if (!(await publicHost(at))) return out;
      r = await fetch(at, { redirect: "manual", signal: AbortSignal.timeout(8000), headers: { "User-Agent": "Mozilla/5.0 (compatible; HabitAI-preview/1.0; +https://bigdai.tech)", "Accept": "text/html" } });
      const loc = r.status >= 300 && r.status < 400 && r.headers.get("location");
      if (!loc) break;
      try { await r.body?.cancel(); } catch (e) { }
      at = new URL(loc, at).toString(); r = null;
    }
    if (!r || !r.ok || !r.body) return out;
    const reader = r.body.getReader(); const parts = []; let size = 0;
    while (size < (opts.text ? 1_500_000 : 400_000)) { const { done, value } = await reader.read(); if (done) break; parts.push(Buffer.from(value)); size += value.length; }
    try { await reader.cancel(); } catch (e) { }
    const html = Buffer.concat(parts).toString("utf8");
    const meta = {};
    for (const tag of html.match(/<meta\b[^>]*>/gi) || []) {
      const k = (tag.match(/\b(?:property|name)\s*=\s*["']([^"']+)["']/i) || [])[1];
      const v = (tag.match(/\bcontent\s*=\s*["']([^"']*)["']/i) || [])[1];
      if (k && v != null && !(k.toLowerCase() in meta)) meta[k.toLowerCase()] = ent(v);
    }
    out.title = str(meta["og:title"] || meta["twitter:title"] || ent((html.match(/<title[^>]*>([^<]*)<\/title>/i) || [])[1]), 200);
    let img = meta["og:image"] || meta["og:image:url"] || meta["og:image:secure_url"] || meta["twitter:image"] || meta["twitter:image:src"] || "";
    try { if (img) img = new URL(img, at).toString(); } catch (e) { img = ""; }
    if (/^https:\/\//.test(img)) out.image = str(img, 600);
    if (meta["og:site_name"] && !SITE[host]) out.site = str(meta["og:site_name"], 60);
    out.description = str(meta["og:description"] || meta["description"] || meta["twitter:description"], 500);
    out.final = at;
    if (opts.text) {
      // the story itself: the page's paragraphs (or the article text that news sites put in their JSON-LD)
      const paras = [];
      const ld = html.match(/"articleBody"\s*:\s*"((?:[^"\\]|\\.){80,})"/);
      if (ld) { try { paras.push(JSON.parse('"' + ld[1] + '"')); } catch (e) { } }
      if (!paras.length) for (const m of html.matchAll(/<p\b[^>]*>([\s\S]*?)<\/p>/gi)) {
        const t = ent(m[1].replace(/<[^>]+>/g, " ")).replace(/\s+/g, " ").trim();
        if (t.length >= 60 && !/cookie|subscribe|newsletter|all rights reserved|copyright|download (?:the )?app/i.test(t)) paras.push(t);
        if (paras.join(" ").length > 1800) break;
      }
      out.text = str(paras.join("\n").replace(/[ \t]+/g, " "), 1800);
    }
  } catch (e) { }
  return out;
}

module.exports = { preview, ytId, hostOf, privateIp };
