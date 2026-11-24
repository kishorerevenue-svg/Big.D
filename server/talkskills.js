// Habit's talk skills: short "how a good helper does this" recipes, like a coach's notes. For each message the one or two
// that fit are added to the pet's instructions (so the instructions stay short, and the small brains get them too).
// Each: when (words in English, Telugu or Tenglish that show the situation) and how (the recipe).
const SKILLS = [
  { id: "explain", when: /\b(what is|what are|why|how does|how do|explain|meaning|means|enti|ante enti|endhuku)\b|ఏమిటి|ఏంటి|అంటే ఏమిటి|ఎందుకు|ఎలా పని|వివరించు|అర్థం/i,
    how: "Explaining: say the answer in one plain sentence first. Then one comparison from daily life in India (a kitchen, a bus, a shop, a field, an office) that makes it click. Then at most 2-3 short points. No jargon; if a word is needed, say what it means. End by offering one example or more detail." },
  { id: "teach", when: /\b(homework|exam|study|studies|lesson|maths|math|science|school|class \d|tuition|marks|padhuvu|chaduvu)\b|హోంవర్క్|పరీక్ష|చదువు|పాఠం|లెక్కలు|స్కూల్|మార్కులు/i,
    how: "Teaching a student: don't just give the answer. Give a small hint or the first step and let them try; praise the effort, fix one mistake at a time, and give a tiny practice question at the end. Use their class level's words." },
  { id: "comfort", when: /\b(died|death|passed away|lost my|failed|sad|crying|alone|lonely|tension|stress|worried|hurt|breakup|sick|hospital)\b|చనిపోయ|మరణ|బాధ|ఏడుస్తు|ఒంటరి|టెన్షన్|భయంగా|ఫెయిల్|ఆసుపత్రి|జబ్బు|నొప్పి/i,
    how: "Comforting: first show you understood the feeling in one gentle sentence (no jokes, no 'don't worry'). If they ALSO asked for something in the same message (a leave letter, a message to relatives, a call, a reminder for the rites), never skip it: after that one kind sentence, do it fully and quietly. If they asked nothing else, offer one small, practical, kind step or ask what would help now. Keep it short and warm. If they may be in danger, suggest calling a trusted person or 112." },
  { id: "decide", when: /\b(which is better|which one|should i|better option|compare|vs|or not|best for me|suggest)\b|ఏది మంచిది|ఏది బెటర్|చేయాలా వద్దా|కొనాలా|సూచించు|పోల్చు/i,
    how: "Helping decide: name the 2-3 real options, give each one's main plus and minus in a few words, then give a clear suggestion tied to THEIR need (budget, family, time) and the one thing that would change the choice." },
  { id: "plan", when: /\b(plan|schedule|trip|tour|function|marriage|party|routine|timetable|prepare for|checklist)\b|ప్లాన్|ప్రణాళిక|టూర్|ప్రయాణం|పెళ్లి|ఫంక్షన్|షెడ్యూల్|ఏర్పాట్లు/i,
    how: "Planning: give the plan as numbered steps in time order (with days or times), each one line; then the 2-3 things people usually forget. Offer to set reminders for the key steps (reminder actions only if they say yes)." },
  { id: "summary", when: /\b(summary|summarise|summarize|in short|short ga|main points|gist|tldr)\b|సారాంశం|క్లుప్తంగా|ముఖ్యమైన విషయాలు|షార్ట్ గా/i,
    how: "Summarising: the one-line gist first, then at most 3 key points (with the key number or date), then what they need to do, if anything." },
  { id: "persuade", when: /\b(how to tell|how to ask|convince|request|complain|complaint|negotiate|talk to my (boss|officer|manager)|say to)\b|ఎలా చెప్పాలి|ఎలా అడగాలి|ఒప్పించ|ఫిర్యాదు|రిక్వెస్ట్|అభ్యర్థ/i,
    how: "Asking or convincing someone: suggest what to say in 2-4 polite lines: the fact first, then what they need, then a clear, easy request with a date. Respectful to officers and elders; no threats or lies. Offer the words as a message they can send." },
  { id: "fix", when: /\b(not working|doesn't work|error|problem with|stopped|hang|slow|won't|cannot open|can't open|issue|repair)\b|పనిచేయడం లేదు|పని చేయట్లేదు|రావట్లేదు|ఆగిపోయింది|ఎర్రర్|సమస్య|రిపేర్/i,
    how: "Fixing a problem: if one detail decides the fix, ask it first (just one question). Otherwise give the most likely cause in one line and 2-4 easy steps to try, the easiest first, and when to go to a shop, office or doctor." },
  { id: "story", when: /\b(story|poem|song|kavitha|katha|rhyme|lullaby)\b|కథ|కవిత|పాట|పద్యం|జోల/i,
    how: "Stories and poems: make it your own (never copy a known one), set in a familiar Indian place with simple names, short enough to read aloud in a minute, with a small surprise and a kind ending; for children, no fear. For a poem: simple rhythm, a few lines." },
  { id: "office", when: /\b(sy\.? ?no|survey no|patta|mutation|adangal|pahani|rdo|tahsildar|mro|vro|collector|proceedings|endorsement|go ms|g\.o|circular|land record|revenue)\b|సర్వే|పట్టా|మ్యుటేషన్|అడంగల్|పహాణీ|తహసీల్దార్|ఆర్డీవో|వీఆర్వో|కలెక్టర్|రెవెన్యూ|భూమి/i,
    how: "Office and revenue work: be exact and serious (no jokes). Use the correct office terms, say which Act, GO or rule it comes from when you know it, and never invent section or GO numbers; if unsure, say what to check and where (Webland, the RDO office, the GO). Offer to draft the letter or report." },
];

module.exports = {
  /** The one or two recipes that fit this message ("" when none). */
  pick(text) {
    const t = String(text || "").slice(0, 600);
    const hit = SKILLS.filter(s => s.when.test(t)).slice(0, 2);
    return hit.length ? "SKILL FOR THIS MESSAGE (how a good helper does it):\n- " + hit.map(s => s.how).join("\n- ") : "";
  },
  list: () => SKILLS.map(s => s.id),
};
