// Habit AI icon: a gold biscuit (minted gold bar) with a bold stamped "H", on deep navy.
// Everything is drawn on the 108 x 108 adaptive-icon canvas (the middle 72 x 72 is what launchers show).
const fs = require("fs");
const path = require("path");
const G = JSON.parse(fs.readFileSync(path.join(__dirname, "glyphs.json"), "utf8"));

const THEMES = {
  app:   { bg: ["#2F3680", "#1D2254", "#11142E"], glow: "#F6C453" },
  brain: { bg: ["#4A2C86", "#2A1A55", "#150C2C"], glow: "#F6C453" },
};

function bar(shape) {
  if (shape === "landscape") return { w: 56, h: 42, r: 6, b: 4, thick: 2.6, oy: -1.3 };
  if (shape === "square") return { w: 48, h: 50, r: 6, b: 4 };
  return { w: 44, h: 54, r: 6, b: 4.2 };      // portrait: a minted bar standing up
}

// The H glyph scaled into a box (centre cx, cy; cap height capH; squeeze sx)
function hPath(font, cx, cy, capH, sx = 1) {
  const g = G[font]; const [x0, y0, x1, y1] = g.box;
  const s = capH / (y1 - y0);
  const w = (x1 - x0) * s * sx;
  const tx = cx - w / 2 - x0 * s * sx, ty = cy + capH / 2 + y0 * s;
  return { d: g.d, transform: `translate(${tx.toFixed(3)} ${ty.toFixed(3)}) scale(${(s * sx).toFixed(5)} ${(-s).toFixed(5)})`, w };
}

function star(cx, cy, R, k = 0.18) {
  const q = R * k;
  return `M${cx} ${cy - R} C${cx + q} ${cy - q} ${cx + q} ${cy - q} ${cx + R} ${cy} C${cx + q} ${cy + q} ${cx + q} ${cy + q} ${cx} ${cy + R} ` +
         `C${cx - q} ${cy + q} ${cx - q} ${cy + q} ${cx - R} ${cy} C${cx - q} ${cy - q} ${cx - q} ${cy - q} ${cx} ${cy - R}Z`;
}

function defs(theme, id) {
  const t = THEMES[theme];
  return `<defs>
  <radialGradient id="${id}bg" cx="50%" cy="40%" r="75%"><stop offset="0" stop-color="${t.bg[0]}"/><stop offset=".55" stop-color="${t.bg[1]}"/><stop offset="1" stop-color="${t.bg[2]}"/></radialGradient>
  <radialGradient id="${id}glow" cx="50%" cy="50%" r="50%"><stop offset="0" stop-color="${t.glow}" stop-opacity=".42"/><stop offset=".55" stop-color="${t.glow}" stop-opacity=".12"/><stop offset="1" stop-color="${t.glow}" stop-opacity="0"/></radialGradient>
  <linearGradient id="${id}rim" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFF8DC"/><stop offset=".22" stop-color="#FBDD8A"/><stop offset=".5" stop-color="#E9B444"/><stop offset=".78" stop-color="#B57A1A"/><stop offset="1" stop-color="#7A4E0C"/></linearGradient>
  <linearGradient id="${id}rimEdge" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7A4E0C"/><stop offset=".5" stop-color="#C08422"/><stop offset="1" stop-color="#FFF1B8"/></linearGradient>
  <linearGradient id="${id}face" x1=".1" y1="0" x2=".55" y2="1"><stop offset="0" stop-color="#FDE9A8"/><stop offset=".3" stop-color="#F7CF62"/><stop offset=".62" stop-color="#EDB743"/><stop offset="1" stop-color="#D99B2C"/></linearGradient>
  <linearGradient id="${id}side" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#8C5B10"/><stop offset=".45" stop-color="#B07818"/><stop offset="1" stop-color="#6A420A"/></linearGradient>
  <linearGradient id="${id}h" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#B47A1B"/><stop offset=".5" stop-color="#9E6914"/><stop offset="1" stop-color="#7E520E"/></linearGradient>
  <linearGradient id="${id}sheen" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset=".5" stop-color="#fff" stop-opacity=".55"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient>
  <filter id="${id}inset" x="-10%" y="-10%" width="120%" height="120%">
    <feOffset in="SourceAlpha" dx=".55" dy=".75" result="o"/><feGaussianBlur in="o" stdDeviation=".35" result="b"/>
    <feComposite in="SourceAlpha" in2="b" operator="out" result="edge"/>
    <feFlood flood-color="#3E2604" flood-opacity=".85"/><feComposite in2="edge" operator="in" result="sh"/>
    <feMerge><feMergeNode in="SourceGraphic"/><feMergeNode in="sh"/></feMerge>
  </filter>
  <filter id="${id}soft" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="1.5"/></filter>
  <filter id="${id}spark" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation=".8" result="g"/><feMerge><feMergeNode in="g"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
</defs>`;
}

function background(id) {
  return `<rect x="0" y="0" width="108" height="108" fill="url(#${id}bg)"/>
  <circle cx="54" cy="54" r="40" fill="url(#${id}glow)"/>`;
}

function foreground(o, id) {
  const B = bar(o.shape), oy = B.oy || 0, th = B.thick || 0;
  const x = 54 - B.w / 2, y = 54 - B.h / 2 + oy;
  const fx = x + B.b, fy = y + B.b, fw = B.w - 2 * B.b, fh = B.h - 2 * B.b, fr = Math.max(1.5, B.r - B.b * 0.6);
  const capH = o.capH || (o.shape === "landscape" ? 23.5 : 27);
  const H = hPath(o.font || "cinzel", 54, 54 + oy + (o.hDy || 0), capH, o.sx || (o.shape === "portrait" ? 0.9 : 1));
  const clip = `<clipPath id="${id}clipFace"><rect x="${fx}" y="${fy}" width="${fw}" height="${fh}" rx="${fr}"/></clipPath>`;
  const sheen = `<g clip-path="url(#${id}clipFace)" opacity=".42">
      <polygon points="${fx - 6},${fy + fh} ${fx + fw * 0.30},${fy + fh} ${fx + fw * 0.80},${fy} ${fx + fw * 0.44 - 6},${fy}" fill="url(#${id}sheen)"/>
      <polygon points="${fx + fw * 0.40},${fy + fh} ${fx + fw * 0.50},${fy + fh} ${fx + fw * 1.0},${fy} ${fx + fw * 0.9},${fy}" fill="url(#${id}sheen)" opacity=".7"/>
    </g>`;
  const sx = x + B.w - 3, sy = y + 3;   // sparkle on the top-right corner
  const badge = o.variant === "brain"
    ? `<g><circle cx="${x + B.w - 6}" cy="${y + B.h - 5.5}" r="8" fill="#1B1240" stroke="#F7D36B" stroke-width="1.1"/>
        <text x="${x + B.w - 6}" y="${y + B.h - 5.5 + 3.6}" font-size="9.6" text-anchor="middle" font-family="Noto Color Emoji">🧠</text></g>` : "";
  return `<defs>${clip}</defs>
  <ellipse cx="54" cy="${y + B.h + th + 1.4}" rx="${B.w * 0.46}" ry="2.6" fill="#000" opacity=".55" filter="url(#${id}soft)"/>
  ${th ? `<rect x="${x}" y="${y + th}" width="${B.w}" height="${B.h}" rx="${B.r}" fill="url(#${id}side)"/>` : ""}
  <rect x="${x}" y="${y}" width="${B.w}" height="${B.h}" rx="${B.r}" fill="url(#${id}rim)"/>
  <rect x="${x + 0.35}" y="${y + 0.35}" width="${B.w - 0.7}" height="${B.h - 0.7}" rx="${B.r - 0.3}" fill="none" stroke="#FFF5CC" stroke-opacity=".55" stroke-width=".5"/>
  <rect x="${fx}" y="${fy}" width="${fw}" height="${fh}" rx="${fr}" fill="url(#${id}face)"/>
  ${sheen}
  <rect x="${fx}" y="${fy}" width="${fw}" height="${fh}" rx="${fr}" fill="none" stroke="url(#${id}rimEdge)" stroke-width=".7"/>
  <path d="${H.d}" transform="translate(.55 .7) ${H.transform}" fill="#FFF3C4" opacity=".85"/>
  <path d="${H.d}" transform="${H.transform}" fill="url(#${id}h)" filter="url(#${id}inset)"/>
  <path d="${star(sx, sy, 5.2)}" fill="#FFFDF2" filter="url(#${id}spark)"/>
  <path d="${star(sx, sy, 2.2, .3)}" fill="#fff"/>
  ${badge}`;
}

// White silhouette for Android 13 themed icons: the bar with the H and the rim line cut out.
function mono(o, id) {
  const B = bar(o.shape), oy = B.oy || 0, th = B.thick || 0;
  const x = 54 - B.w / 2, y = 54 - B.h / 2 + oy;
  const fx = x + B.b, fy = y + B.b, fw = B.w - 2 * B.b, fh = B.h - 2 * B.b, fr = Math.max(1.5, B.r - B.b * 0.6);
  const capH = o.capH || (o.shape === "landscape" ? 23.5 : 27);
  const H = hPath(o.font || "cinzel", 54, 54 + oy + (o.hDy || 0), capH, o.sx || (o.shape === "portrait" ? 0.9 : 1));
  return `<defs><mask id="${id}m"><rect x="0" y="0" width="108" height="108" fill="#fff"/>
    <rect x="${fx}" y="${fy}" width="${fw}" height="${fh}" rx="${fr}" fill="none" stroke="#000" stroke-width="1.3"/>
    <path d="${H.d}" transform="${H.transform}" fill="#000"/></mask></defs>
  <rect x="${x}" y="${y}" width="${B.w}" height="${B.h}" rx="${B.r}" fill="#fff" mask="url(#${id}m)"/>`;
}

/** mode: bg | fg | mono | full | legacy | round. view: the part of the 108 canvas to show. */
function iconSVG(o) {
  const id = "i" + Math.random().toString(36).slice(2, 7);
  const size = o.size || 512;
  const view = o.view || (["full", "legacy", "round"].includes(o.mode) ? [18, 18, 72, 72] : [0, 0, 108, 108]);
  let body = "";
  if (o.mode === "bg") body = background(id);
  else if (o.mode === "fg") body = foreground(o, id);
  else if (o.mode === "mono") body = mono(o, id);
  else body = background(id) + foreground(o, id);
  let clip = "";
  if (o.mode === "legacy" || o.mode === "round") {
    const [vx, vy, vw] = view;
    const shape = o.mode === "round" ? `<circle cx="${vx + vw / 2}" cy="${vy + vw / 2}" r="${vw / 2}"/>` : `<rect x="${vx}" y="${vy}" width="${vw}" height="${vw}" rx="${vw * 0.2}"/>`;
    clip = `<clipPath id="${id}shape">${shape}</clipPath>`;
    body = `<g clip-path="url(#${id}shape)">${body}</g>`;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="${view.join(" ")}">${defs(o.variant || "app", id)}<defs>${clip}</defs>${body}</svg>`;
}

module.exports = { iconSVG, foreground, defs, background, bar, hPath, star, THEMES };
