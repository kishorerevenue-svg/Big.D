// Brings Habit's own cute pictures and clips (made on Kaggle by brain/habit_cartoons.py, kept in the owner's public
// Hugging Face dataset <user>/habit-cartoons) into Media › Fun: a few a day (CARTOONS_PER_DAY, 4), spread over the day,
// each marked 🤖 AI-made. Clips are copied here (Media plays them from this server); pictures are shown from Hugging Face.
// Found with the server's HF_TOKEN, or CARTOONS_REPO=<user>/habit-cartoons.
const fs = require("fs");
const path = require("path");

module.exports = function makeHfCartoons({ DATA_DIR, media }) {
  const DIR = path.join(DATA_DIR, "cartoons");           // (the same folder as the cartoon maker: served at /media/cartoon/)
  const FILE = path.join(DATA_DIR, "hf-cartoons.json");
  fs.mkdirSync(DIR, { recursive: true });
  let db = { done: [], day: "", today: 0, lastRun: 0, error: "" };
  try { db = Object.assign(db, JSON.parse(fs.readFileSync(FILE, "utf8"))); } catch (e) { }
  const save = () => { try { fs.writeFileSync(FILE, JSON.stringify(db)); } catch (e) { } };
  const perDay = () => Math.max(0, Number(process.env.CARTOONS_PER_DAY || 4));

  let repo = "";
  async function repoName() {
    if (repo) return repo;
    if (process.env.CARTOONS_REPO) return (repo = String(process.env.CARTOONS_REPO).trim());
    if (!process.env.HF_TOKEN) return "";
    try {
      const j = await fetch("https://huggingface.co/api/whoami-v2", { headers: { Authorization: "Bearer " + process.env.HF_TOKEN }, signal: AbortSignal.timeout(8000) }).then(r => r.json());
      if (j && j.name) repo = `${j.name}/habit-cartoons`;
    } catch (e) { }
    return repo;
  }
  const fileUrl = (r, f) => `https://huggingface.co/datasets/${r}/resolve/main/${f.split("/").map(encodeURIComponent).join("/")}`;
  const safeId = id => "hf_" + String(id).toLowerCase().replace(/[^a-z0-9_]/g, "_").slice(0, 60);

  async function download(url, to) {
    const r = await fetch(url, { signal: AbortSignal.timeout(120000) });
    if (!r.ok) throw new Error("http " + r.status);
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length > 60 * 1024 * 1024) throw new Error("too big");
    fs.writeFileSync(to + ".part", buf); fs.renameSync(to + ".part", to);
  }

  /** Posts the next one (or few) that isn't in Media yet. */
  async function run(force = false) {
    const r = await repoName();
    if (!r) return { posted: 0, why: "no repo (HF_TOKEN or CARTOONS_REPO)" };
    const day = new Date().toISOString().slice(0, 10);
    if (db.day !== day) { db.day = day; db.today = 0; }
    if (!force && db.today >= perDay()) return { posted: 0, why: "enough for today" };
    let man;
    try {
      const res = await fetch(fileUrl(r, "manifest.json") + "?t=" + Date.now(), { signal: AbortSignal.timeout(20000) });
      if (res.status === 404) { db.error = "not made yet"; save(); return { posted: 0, why: "not made yet" }; }
      man = await res.json();
    } catch (e) { db.error = String(e.message || e).slice(0, 100); save(); return { posted: 0, why: db.error }; }
    const done = new Set(db.done);
    // clips first, then pictures; newest batch first
    const todo = (man.items || []).filter(it => it && it.id && !done.has(it.id)).sort((a, b) => (b.made || "").localeCompare(a.made || "") || (b.video ? 1 : 0) - (a.video ? 1 : 0));
    let posted = 0;
    for (const it of todo.slice(0, force ? 1 : 1)) {
      const title = String(it.te || it.en || "Habit cartoon").slice(0, 120);
      const body = `${it.te || ""}\n${it.en || ""}\n\n${it.label || "🤖 AI-made"} · Habit's own characters`;
      try {
        let item;
        if (it.video) {
          const id = safeId(it.id);
          await download(fileUrl(r, it.video), path.join(DIR, id + ".mp4"));
          if (it.image) { try { await download(fileUrl(r, it.image), path.join(DIR, id + ".jpg")); } catch (e) { } }
          item = media.addItem({ cat: "fun", title: "🎬 " + title, body, emoji: "🎬", mood: "happy", issue: "other", kind: "cartoon", writer: "habit-cartoons", by: "auto",
            img: "", key: "hfc:" + it.id, video: { url: `/media/cartoon/${id}.mp4`, poster: `/media/cartoon/${id}.jpg`, dur: 6, w: 720, h: 1280 } });
        } else {
          item = media.addItem({ cat: "fun", title: "🎨 " + title, body, emoji: "🎨", mood: "happy", issue: "other", kind: "picture", writer: "habit-cartoons", by: "auto",
            img: fileUrl(r, it.image), key: "hfc:" + it.id });
        }
        db.done.push(it.id); db.done = db.done.slice(-2000);
        if (item) { posted++; db.today++; }
      } catch (e) { db.error = String(e.message || e).slice(0, 100); }
    }
    db.lastRun = Date.now(); if (posted) db.error = ""; save();
    if (posted) console.log(`Habit cartoons: posted ${posted} from ${r}`);
    return { posted, left: Math.max(0, todo.length - posted) };
  }
  // one at a time, about every 3 hours in the day (8 AM to 9 PM India time), at most CARTOONS_PER_DAY
  setTimeout(() => run().catch(() => {}), 5 * 60000).unref();
  setInterval(() => {
    const h = (new Date().getUTCHours() + 5.5) % 24;
    if (h >= 8 && h < 21 && Date.now() - db.lastRun > 3 * 3600e3 - 60000) run().catch(() => {});
  }, 15 * 60000).unref();

  const status = async () => ({ repo: await repoName(), today: db.today, perDay: perDay(), posted: db.done.length, lastRun: db.lastRun, error: db.error });
  return { run, status };
};
