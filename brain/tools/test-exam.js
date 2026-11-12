// Checks that the exam's rules give the same result in JavaScript (server/gemma.js) and Python (brain/habit_exam.py),
// for many kinds of answers to every question.   node brain/tools/test-exam.js
const { execFileSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { check } = require("../../server/gemma.js");
const exam = require("../exam.json");
const EMO = exam.schema.properties.emotion.enum;

const word = p => p.startsWith("re:") ? (p.match(/[0-9]{2,}/) || ["7"])[0] : p;
const kinds = [];
for (const it of exam.items) {
  const w = it.want, te = w.script === "te";
  const base = te ? "నేను మీకు సహాయం చేస్తాను, జాగ్రత్తగా ఉండండి." : "I will help you, please be careful.";
  const fits = p => !/^[a-z]/i.test(word(p)) === te || /^[0-9]/.test(word(p));   // a word in the answer's own script
  const pick = list => word(list.find(fits) || list[0]);
  const right = [...(w.any ? [pick(w.any)] : []), ...(w.all || []).map(pick), ...Array(w.minWords || 0).fill(te ? "సరే" : "fine")].join(" ");
  const answers = [
    JSON.stringify({ emotion: "happy", reply: base + " " + right }),
    JSON.stringify({ emotion: "angry", reply: base + " " + right }),
    JSON.stringify({ emotion: "calm", reply: "" }),
    JSON.stringify({ emotion: "calm", reply: te ? "Sure, I can help with that today." : "సరే, సహాయం చేస్తాను." }),
    "Here you go: " + JSON.stringify({ emotion: "happy", reply: base }) + " hope it helps",
    base + " " + right,
    "", "[]", "null", "123", "\"hello\"", "{broken", "{\"reply\": 5}",
    JSON.stringify({ emotion: "happy", reply: (base + " ").repeat(40) }),
    JSON.stringify({ emotion: "happy", reply: base + " As an AI language model I " + right }),
    JSON.stringify({ emotion: "wink", reply: base + " " + (w.none || ["openai"]).map(word).join(" ") + " " + right }),
    JSON.stringify({ emotion: "happy", reply: "ABCDE1234F " + right + " 1930 108 112 " + base })
  ];
  for (const a of answers) kinds.push({ id: it.id, raw: a });
}
const js = kinds.map(k => check(exam.items.find(i => i.id === k.id), k.raw, EMO));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "exam-"));
fs.writeFileSync(path.join(tmp, "cases.json"), JSON.stringify(kinds));
const py = JSON.parse(execFileSync("python3", ["-I", "-c", `
import json, sys
sys.path.insert(0, ${JSON.stringify(path.join(__dirname, ".."))})
from habit_exam import check
ex = json.load(open(${JSON.stringify(path.join(__dirname, "..", "exam.json"))}, encoding="utf-8"))
items = {i["id"]: i for i in ex["items"]}
emo = ex["schema"]["properties"]["emotion"]["enum"]
cases = json.load(open(sys.argv[1], encoding="utf-8"))
print(json.dumps([check(items[c["id"]], c["raw"], emo) for c in cases], ensure_ascii=False))
`, path.join(tmp, "cases.json")], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 }));
let bad = 0, oks = 0;
for (let i = 0; i < kinds.length; i++) {
  const a = js[i], b = py[i];
  if (a.ok) oks++;
  if (a.ok !== b.ok || a.format !== b.format || a.reply !== b.reply || JSON.stringify(a.fails) !== JSON.stringify(b.fails)) {
    if (bad++ < 5) console.log("DIFFERENT for", kinds[i].id, JSON.stringify(kinds[i].raw).slice(0, 120), "\n JS:", JSON.stringify(a).slice(0, 300), "\n PY:", JSON.stringify(b).slice(0, 300));
  }
}
// the first kind of answer (the right words, in the right language, as JSON) passes every question
const firstFail = exam.items.filter((it, n) => !js[n * 17].ok).map(it => it.id + ": " + js[exam.items.indexOf(it) * 17].fails.join(", "));
if (firstFail.length) { console.log("A right answer failed:", firstFail.slice(0, 8)); bad++; }
console.log(bad ? `FAILED: ${bad} differences` : `OK: ${kinds.length} answers checked the same in JavaScript and Python (${oks} passed)`);
process.exit(bad ? 1 : 0);
