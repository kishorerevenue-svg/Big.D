#!/usr/bin/env node
// A short report on the office files kept on this server: only COUNTS (no file names, no words from the files), so the
// owner can screenshot it safely. Run on the server:  node /root/Big.D/server/office-report.js
const fs = require("fs");
const path = require("path");

const DIR = path.join(__dirname, "data", "offices");
let offices = {};
try { offices = JSON.parse(fs.readFileSync(path.join(DIR, "index.json"), "utf8")); } catch (e) { console.log("No offices on this server yet."); process.exit(0); }

const TE = /[ఀ-౿]/g, LAT = /[A-Za-z]/g, ODD = /[ -ÿ‘-„†-‰]/g;
const n = (t, re) => (t.match(re) || []).length;
function looks(text) {
  const te = n(text, TE), lat = n(text, LAT), odd = n(text, ODD), all = Math.max(1, te + lat);
  if (text.length < 200) return "little";                       // a few words only (a blurry scan, a cover page)
  if (te / all > 0.3) return "telugu";
  // Anu / Shree-Lipi: Telugu typed in an old font reads as Latin letters full of accents and odd marks
  if (odd / Math.max(1, lat) > 0.04 || /[À-ÿ]{3,}/.test(text)) return "oldfont";
  return "english";
}
const pct = (a, b) => (b ? Math.round(a * 100 / b) : 0) + "%";

for (const o of Object.values(offices)) {
  const dir = path.join(DIR, o.id);
  let files = [];
  try { for (const f of fs.readdirSync(dir)) if (f.endsWith(".json")) try { files.push(JSON.parse(fs.readFileSync(path.join(dir, f), "utf8"))); } catch (e) { } } catch (e) { }
  const c = { telugu: 0, english: 0, oldfont: 0, little: 0 }, kinds = {}, label = { brain: 0, staff: 0, tried: 0, none: 0 }, years = {};
  let chars = 0, made = 0;
  for (const f of files) {
    const t = String(f.text || "");
    c[looks(t)]++; chars += t.length;
    kinds[f.kind || "Letter"] = (kinds[f.kind || "Letter"] || 0) + 1;
    label[f.aboutBy === "brain" ? "brain" : f.aboutBy === "staff" ? "staff" : f.aboutBy === "tried" ? "tried" : "none"]++;
    if (f.by === "made in Habit") made++;
    const ys = (t.match(/\b(19[89]\d|20[0-3]\d)\b/g) || []);
    const y = ys.length ? Math.max(...ys.map(Number)) : 0;
    const band = y ? (y < 2000 ? "before 2000" : y < 2010 ? "2000-2009" : y < 2020 ? "2010-2019" : "2020 and later") : "no year";
    years[band] = (years[band] || 0) + 1;
  }
  const total = files.length;
  console.log("==============================================");
  console.log(`🏢 ${o.name}${o.place ? " · " + o.place : ""}`);
  console.log(`Files kept: ${total}   (letters made in Habit: ${made})   Members: ${(o.members || []).length}`);
  console.log(`Text kept: ${(chars / 1048576).toFixed(1)} MB   (about ${total ? Math.round(chars / total) : 0} letters a file)`);
  console.log("--- Language ---");
  console.log(`  Telugu (reads well):        ${c.telugu}  (${pct(c.telugu, total)})`);
  console.log(`  English:                    ${c.english}  (${pct(c.english, total)})`);
  console.log(`  OLD FONT (Anu/Shree-Lipi):  ${c.oldfont}  (${pct(c.oldfont, total)})   <- needs the font converter`);
  console.log(`  Very little text:           ${c.little}  (${pct(c.little, total)})   <- blurry scans / covers`);
  console.log("--- Kind ---");
  for (const [k, v] of Object.entries(kinds).sort((a, b) => b[1] - a[1]).slice(0, 8)) console.log(`  ${k}: ${v}`);
  console.log("--- Year written (newest year inside) ---");
  for (const k of ["before 2000", "2000-2009", "2010-2019", "2020 and later", "no year"]) if (years[k]) console.log(`  ${k}: ${years[k]}`);
  console.log("--- 'What it is about' labels ---");
  console.log(`  by the home brain: ${label.brain}   by staff: ${label.staff}   tried, failed: ${label.tried}   waiting: ${label.none}`);
}
console.log("==============================================");
console.log("(Counts only: no file names or words are shown.)");
