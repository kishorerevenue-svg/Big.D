// BigD AI files: turns the AI's "make_file" content into real Word (.docx) and Excel (.xlsx) files.
// Files are kept for 24 hours under data/files and then deleted.
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const docx = require("docx");
const ExcelJS = require("exceljs");

module.exports = function makeFiles({ DATA_DIR }) {
  const DIR = path.join(DATA_DIR, "files");
  fs.mkdirSync(DIR, { recursive: true });
  const KEEP_MS = 24 * 3600 * 1000;

  // Telugu needs a complex-script font; Word and phone viewers fall back to their own Telugu font if missing.
  const FONT = { ascii: "Arial", hAnsi: "Arial", cs: "Nirmala UI", eastAsia: "Arial" };
  const str = (v, n = 4000) => String(v == null ? "" : v).slice(0, n);

  function safeName(title, ext) {
    const base = str(title, 60).replace(/[^\p{L}\p{M}\p{N} _-]/gu, "").trim().replace(/\s+/g, "-") || "BigD-file";
    return `${base}.${ext}`;
  }

  /** Paragraph runs; "**bold**" parts become bold. Line breaks are kept. */
  function runs(text, opts = {}) {
    const out = [];
    str(text).split(/(\*\*[^*]+\*\*)/).forEach(part => {
      if (!part) return;
      const bold = /^\*\*.*\*\*$/.test(part) || !!opts.bold;
      const t = part.replace(/^\*\*|\*\*$/g, "");
      t.split("\n").forEach((line, i) => out.push(new docx.TextRun({ text: line, bold, break: i > 0 ? 1 : 0, font: FONT, size: opts.size || 24, sizeComplexScript: opts.size || 24 })));
    });
    return out;
  }

  async function buildDocx(doc) {
    const { Paragraph, Table, TableRow, TableCell, WidthType, AlignmentType, HeadingLevel, BorderStyle } = docx;
    const children = [];
    const blocks = Array.isArray(doc.blocks) ? doc.blocks.slice(0, 200) : [];
    for (const b of blocks) {
      if (!b || typeof b !== "object") continue;
      if (b.h) children.push(new Paragraph({ children: runs(b.h, { bold: true, size: 28 }), heading: HeadingLevel.HEADING_2, alignment: b.align === "left" ? AlignmentType.LEFT : AlignmentType.CENTER, spacing: { before: 120, after: 160 } }));
      else if (b.right) children.push(new Paragraph({ children: runs(b.right), alignment: AlignmentType.RIGHT, spacing: { after: 80 } }));
      else if (b.center) children.push(new Paragraph({ children: runs(b.center), alignment: AlignmentType.CENTER, spacing: { after: 80 } }));
      else if (b.sign) children.push(new Paragraph({ children: runs(b.sign, { bold: true }), alignment: AlignmentType.RIGHT, spacing: { before: 480 } }));
      else if (Array.isArray(b.list)) b.list.slice(0, 100).forEach((item, i) => children.push(new Paragraph({ children: runs(`${b.numbered ? (i + 1) + "." : "•"} ${str(item)}`), indent: { left: 360 }, spacing: { after: 60 } })));
      else if (Array.isArray(b.table) && b.table.length) {
        const rows = b.table.slice(0, 200).filter(Array.isArray);
        const cols = Math.max(...rows.map(r => r.length)) || 1;
        children.push(new Table({
          width: { size: 100, type: WidthType.PERCENTAGE },
          rows: rows.map((r, ri) => new TableRow({
            tableHeader: ri === 0,
            children: Array.from({ length: cols }, (_, ci) => new TableCell({
              children: [new Paragraph({ children: runs(r[ci], { bold: ri === 0, size: 22 }) })],
              shading: ri === 0 ? { fill: "E3E7F8" } : undefined
            }))
          }))
        }));
        children.push(new Paragraph({ children: [] }));
      }
      else if (b.p != null) children.push(new Paragraph({ children: runs(b.p, { bold: !!b.bold }), alignment: b.justify === false ? AlignmentType.LEFT : AlignmentType.JUSTIFIED, spacing: { after: 160, line: 300 } }));
    }
    if (!children.length) children.push(new Paragraph({ children: runs("(empty)") }));
    const d = new docx.Document({
      creator: "BigD AI", title: str(doc.title, 200),
      styles: { default: { document: { run: { font: FONT, size: 24 } } } },
      sections: [{ properties: { page: { margin: { top: 1134, bottom: 1134, left: 1134, right: 1134 } } }, children }]
    });
    return docx.Packer.toBuffer(d);
  }

  async function buildXlsx(book) {
    const wb = new ExcelJS.Workbook();
    wb.creator = "BigD AI";
    const sheets = Array.isArray(book.sheets) && book.sheets.length ? book.sheets.slice(0, 10) : [{ name: book.title || "Sheet1", rows: book.rows || [] }];
    const used = new Set();
    for (const sh of sheets) {
      let name = str(sh.name || "Sheet", 31).replace(/[\\/?*[\]:]/g, " ").trim() || "Sheet";
      while (used.has(name)) name = name.slice(0, 28) + "_" + used.size;
      used.add(name);
      const ws = wb.addWorksheet(name);
      const header = Array.isArray(sh.columns) ? sh.columns.map(c => str(c, 200)) : null;
      const rows = (Array.isArray(sh.rows) ? sh.rows : []).slice(0, 5000).filter(Array.isArray);
      if (sh.title) { ws.addRow([str(sh.title, 200)]).font = { name: "Arial", bold: true, size: 14 }; ws.addRow([]); }
      if (header) {
        const hr = ws.addRow(header);
        hr.eachCell(c => { c.font = { name: "Arial", bold: true, color: { argb: "FFFFFFFF" } }; c.fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FF1C2140" } }; c.alignment = { vertical: "middle", wrapText: true }; });
        ws.views = [{ state: "frozen", ySplit: hr.number }];
      }
      for (const r of rows) {
        const row = ws.addRow(r.slice(0, 50).map(v => {
          if (typeof v === "number") return v;
          const s = str(v, 2000);
          if (/^=[A-Za-z(]/.test(s)) return { formula: s.slice(1) };
          if (/^-?\d+(\.\d+)?$/.test(s) && s.length < 12 && !/^0\d/.test(s)) return Number(s);
          return s;
        }));
        row.eachCell(c => { c.font = { name: "Arial" }; c.alignment = { vertical: "top", wrapText: true }; });
      }
      const n = Math.max(header ? header.length : 0, ...rows.map(r => r.length), 1);
      for (let i = 1; i <= n; i++) {
        let w = 8;
        ws.getColumn(i).eachCell({ includeEmpty: false }, c => { const v = c.value && c.value.formula ? 10 : String(c.value == null ? "" : c.value).length; w = Math.max(w, Math.min(v + 2, 50)); });
        ws.getColumn(i).width = w;
        ws.getColumn(i).eachCell(c => { c.border = { top: { style: "thin", color: { argb: "FFBFC5D6" } }, bottom: { style: "thin", color: { argb: "FFBFC5D6" } }, left: { style: "thin", color: { argb: "FFBFC5D6" } }, right: { style: "thin", color: { argb: "FFBFC5D6" } } }; });
      }
    }
    return wb.xlsx.writeBuffer();
  }

  /** Builds the file and returns { url path, name }. */
  async function create(action) {
    const format = String(action.format || "").toLowerCase() === "xlsx" ? "xlsx" : "docx";
    const content = action.content && typeof action.content === "object" ? action.content : action;
    const buf = format === "xlsx" ? await buildXlsx({ title: action.title, ...content }) : await buildDocx({ title: action.title, ...content });
    const id = crypto.randomBytes(12).toString("hex");
    const name = safeName(action.title, format);
    fs.mkdirSync(path.join(DIR, id), { recursive: true });
    fs.writeFileSync(path.join(DIR, id, name), Buffer.from(buf));
    return { path: `/files/${id}/${encodeURIComponent(name)}`, name, format };
  }

  /** Serves /files/<id>/<name>. Returns true if handled. */
  function serve(url, res) {
    const m = url.pathname.match(/^\/files\/([a-f0-9]{24})\/([^/]+)$/);
    if (!m) return false;
    const file = path.join(DIR, m[1], path.basename(decodeURIComponent(m[2])));
    if (!file.startsWith(DIR) || !fs.existsSync(file)) { res.writeHead(404, { "Content-Type": "text/plain" }); res.end("This file has expired. Ask BigD to make it again."); return true; }
    const type = file.endsWith(".xlsx") ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    res.writeHead(200, { "Content-Type": type, "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(path.basename(file))}`, "Cache-Control": "private, max-age=3600" });
    fs.createReadStream(file).pipe(res);
    return true;
  }

  function cleanup() {
    const now = Date.now();
    for (const id of fs.readdirSync(DIR)) {
      const p = path.join(DIR, id);
      try { if (now - fs.statSync(p).mtimeMs > KEEP_MS) fs.rmSync(p, { recursive: true, force: true }); } catch (e) { }
    }
  }
  setInterval(cleanup, 3600 * 1000).unref();

  return { create, serve };
};
