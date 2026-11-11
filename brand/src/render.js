const { chromium } = require("playwright");
const { iconSVG } = require("./design.js");
const { boardHTML } = require("./board.js");
const fs = require("fs"), path = require("path");
const OUT = path.join(__dirname, "out");
const DENS = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
(async () => {
  fs.rmSync(OUT, { recursive: true, force: true });
  const b = await chromium.launch();
  const page = await b.newPage({ deviceScaleFactor: 1 });
  fs.writeFileSync(path.join(__dirname, "blank.html"), `<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0;background:transparent}svg{display:block}</style></head><body></body></html>`);
  await page.goto("file://" + path.join(__dirname, "blank.html"));
  async function shot(svg, file) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await page.evaluate(s => { document.body.innerHTML = s; }, svg);
    await page.evaluate(() => document.fonts.ready);
    await page.locator("svg").first().screenshot({ path: file, omitBackground: true });
  }
  const base = { shape: "landscape", font: "cinzel" };
  for (const variant of ["app", "brain"]) {
    for (const [d, k] of Object.entries(DENS)) {
      const dir = path.join(OUT, variant, "mipmap-" + d);
      await shot(iconSVG({ ...base, variant, mode: "fg", size: Math.round(108 * k) }), path.join(dir, "ic_launcher_foreground.png"));
      await shot(iconSVG({ ...base, variant, mode: "bg", size: Math.round(108 * k) }), path.join(dir, "ic_launcher_background.png"));
      await shot(iconSVG({ ...base, variant, mode: "mono", size: Math.round(108 * k) }), path.join(dir, "ic_launcher_monochrome.png"));
      await shot(iconSVG({ ...base, variant, mode: "legacy", size: Math.round(48 * k) }), path.join(dir, "ic_launcher.png"));
      await shot(iconSVG({ ...base, variant, mode: "round", size: Math.round(48 * k) }), path.join(dir, "ic_launcher_round.png"));
    }
    await shot(iconSVG({ ...base, variant, mode: "full", size: 512 }), path.join(OUT, variant, "playstore-icon-512.png"));
  }
  // the logo inside the app (login screen)
  await shot(iconSVG({ ...base, mode: "legacy", size: 288 }), path.join(OUT, "app", "drawable-nodpi", "habit_logo.png"));
  // web: favicon and home-screen icons for the server pages
  await shot(iconSVG({ ...base, mode: "legacy", size: 32 }), path.join(OUT, "web", "favicon-32.png"));
  await shot(iconSVG({ ...base, mode: "full", size: 180 }), path.join(OUT, "web", "apple-touch-icon.png"));
  await shot(iconSVG({ ...base, mode: "legacy", size: 192 }), path.join(OUT, "web", "icon-192.png"));
  await shot(iconSVG({ ...base, mode: "legacy", size: 512 }), path.join(OUT, "web", "icon-512.png"));
  // the board (banner / Play Store feature graphic)
  fs.writeFileSync(path.join(__dirname, "board.html"), boardHTML());
  const bp = await b.newPage({ viewport: { width: 1024, height: 500 }, deviceScaleFactor: 1 });
  await bp.goto("file://" + path.join(__dirname, "board.html"));
  await bp.evaluate(() => document.fonts.ready);
  await bp.waitForTimeout(200);
  const nameW = await bp.evaluate(() => document.getElementById("name").getBoundingClientRect().right);
  console.log("name right edge:", nameW);
  await bp.locator("#board").screenshot({ path: path.join(OUT, "habit-ai-board-1024x500.png") });
  const bp2 = await b.newPage({ viewport: { width: 1024, height: 500 }, deviceScaleFactor: 2 });
  await bp2.goto("file://" + path.join(__dirname, "board.html"));
  await bp2.evaluate(() => document.fonts.ready); await bp2.waitForTimeout(200);
  await bp2.locator("#board").screenshot({ path: path.join(OUT, "habit-ai-board-2048x1000.png") });
  await b.close();
})();
