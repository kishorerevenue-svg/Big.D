// The "board": a 1024 x 500 banner (Google Play feature graphic size) with the gold biscuit and the name.
const { foreground, defs } = require("./design.js");
function boardHTML() {
  const id = "bd";
  const logo = `<svg xmlns="http://www.w3.org/2000/svg" width="470" height="470" viewBox="0 0 108 108">${defs("app", id)}${foreground({ shape: "landscape", font: "cinzel" }, id)}</svg>`;
  let rays = "";
  for (let i = 0; i < 14; i++) {
    const a = (i / 14) * Math.PI * 2 + 0.12, w = 0.07;
    const x1 = 255 + Math.cos(a - w) * 900, y1 = 250 + Math.sin(a - w) * 900, x2 = 255 + Math.cos(a + w) * 900, y2 = 250 + Math.sin(a + w) * 900;
    rays += `<polygon points="255,250 ${x1.toFixed(0)},${y1.toFixed(0)} ${x2.toFixed(0)},${y2.toFixed(0)}" fill="#F6C453" opacity="${i % 2 ? 0.035 : 0.06}"/>`;
  }
  const sparks = [[560, 70, 9], [960, 430, 7], [610, 440, 5], [980, 70, 6], [70, 420, 6], [90, 70, 4]].map(([x, y, r]) =>
    `<path d="M${x} ${y - r}C${x + r * .18} ${y - r * .18} ${x + r * .18} ${y - r * .18} ${x + r} ${y}C${x + r * .18} ${y + r * .18} ${x + r * .18} ${y + r * .18} ${x} ${y + r}C${x - r * .18} ${y + r * .18} ${x - r * .18} ${y + r * .18} ${x - r} ${y}C${x - r * .18} ${y - r * .18} ${x - r * .18} ${y - r * .18} ${x} ${y - r}Z" fill="#FFF3C4" opacity=".8"/>`).join("");
  return `<!doctype html><html><head><meta charset="utf-8"><style>
@font-face{font-family:Cinzel;src:url(cinzel-900.woff) format("woff");font-weight:900}
@font-face{font-family:Cinzel;src:url(cinzel-700.woff) format("woff");font-weight:700}
@font-face{font-family:Hind;src:url(../../server/fonts/HindGuntur-Bold.ttf) format("truetype");font-weight:700}
@font-face{font-family:Hind;src:url(../../server/fonts/HindGuntur-Regular.ttf) format("truetype");font-weight:400}
html,body{margin:0;background:transparent}
.board{width:1024px;height:500px;position:relative;overflow:hidden;
  background:radial-gradient(circle at 255px 250px,#323A86 0,#1D2254 46%,#10132B 100%)}
.rays{position:absolute;inset:0}
.logo{position:absolute;left:20px;top:15px}
.text{position:absolute;left:500px;right:36px;top:0;bottom:0;display:flex;flex-direction:column;justify-content:center;align-items:flex-start}
.name{font:900 92px/1 Cinzel;letter-spacing:2px;white-space:nowrap;
  background:linear-gradient(180deg,#FFF6D6 0%,#FBDD8A 30%,#EDB743 58%,#C38A24 82%,#9A6414 100%);
  -webkit-background-clip:text;background-clip:text;color:transparent;
  filter:drop-shadow(0 2px 0 #6B430B) drop-shadow(0 10px 18px rgba(0,0,0,.5))}
.rule{display:flex;align-items:center;gap:10px;margin:16px 0 18px;width:440px}
.rule span{flex:1;height:2px;background:linear-gradient(90deg,rgba(246,196,83,0),#F6C453,rgba(246,196,83,0))}
.rule i{width:10px;height:10px;transform:rotate(45deg);background:#F6C453;box-shadow:0 0 10px #F6C453}
.tag{font:700 34px/1.25 Cinzel;color:#FCE7A6;letter-spacing:.5px}
.te{font:700 36px/1.5 Hind;color:#FFF3D0;letter-spacing:0;margin-top:2px}
.chips{margin-top:20px;display:flex;gap:8px;flex-wrap:nowrap}
.chips b{font:400 17px/1 Hind;color:#FFF3D0;padding:8px 11px 6px;white-space:nowrap;border-radius:999px;background:rgba(246,196,83,.13);border:1px solid rgba(246,196,83,.45)}
</style></head><body><div class="board" id="board">
<svg class="rays" width="1024" height="500" viewBox="0 0 1024 500">${rays}${sparks}</svg>
<div class="logo">${logo}</div>
<div class="text"><div class="name" id="name">HABIT AI</div><div class="rule"><span></span><i></i><span></span></div>
<div class="tag">Good habits are gold</div><div class="tag te">మంచి అలవాట్లే బంగారం</div>
<div class="chips"><b>🐾 Pet friend</b><b>💬 Chat</b><b>⏰ Reminders</b><b>🛡️ Safety</b></div></div>
</div></body></html>`;
}
module.exports = { boardHTML };
