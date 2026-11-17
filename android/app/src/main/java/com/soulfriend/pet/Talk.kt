package com.soulfriend.pet

/** Words that steer a spoken conversation with the pet: "bye / enough / చాలు" (stop), "stay with me / ఉండు" (keep
 *  listening), and yes / no. Only short, whole sayings count ("stop the alarm" is a question for the brain, not "stop").
 *  Plain text rules, so they are tested without a phone (TalkTest). */
object Talk {
    // marks a web answer can carry: Claude's citation marks ((cite index="10-1">…</cite>, also <cite index=\"10-1\">
    // and <\/cite>) and a few HTML tags; never shown in the bubble or read aloud (the server takes them out too)
    private val MARKS = Regex("<\\\\?/?\\s*(cite|b|i|u|em|strong|span|sup|sub|small|p|a)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val BREAKS = Regex("<\\s*br\\s*/?\\s*>", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("[ \\t]{2,}")
    /** The words only, without citation marks, HTML tags or **bold** stars. */
    fun plain(s: String): String {
        var t = s
        if ('<' in t) t = SPACES.replace(MARKS.replace(BREAKS.replace(t, "\n"), ""), " ")
        if ("**" in t) t = t.replace("**", "")
        return t.trim()
    }

    private val LINKS = Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val MARKDOWN = Regex("__|`+|^[ \\t]*[#*]+[ \\t]*", RegexOption.MULTILINE)
    /** What the voice reads: no marks, no web addresses read letter by letter ("the link" instead), no list stars. */
    fun speakable(s: String): String {
        val te = s.any { it in 'ఀ'..'౿' }
        return SPACES.replace(MARKDOWN.replace(LINKS.replace(plain(s), if (te) "లింక్" else "the link"), ""), " ").trim()
    }

    private fun clean(s: String): String = s.lowercase()
        .replace(Regex("['’`]"), "")
        .replace(Regex("[\\p{P}\\p{S}]"), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun words(s: String) = clean(s).split(" ").filter { it.isNotEmpty() }

    // "ok", "సరే": alone they may mean "yes" (to "Shall I tell you more?"); twice ("ok ok", "సరే సరే") they mean "enough"
    private val OKS = setOf("ok", "okay", "okk", "okey", "k", "sare", "sari", "సరే", "సరి", "ఓకే")
    private val STOP_WORDS = setOf(
        "stop", "enough", "bye", "byebye", "goodbye", "tata", "bas", "chalu", "chaalu", "aapu", "apu", "aapey", "aapandi", "quiet", "later",
        "చాలు", "ఆపు", "ఆపేయ్", "ఆపెయ్", "ఆపండి", "ఆపేయండి", "బై", "టాటా", "తర్వాత", "తరువాత", "వద్దు", "సరిపోతుంది", "సరిపోయింది", "మానేయ్")
    private val STOP_FILLER = OKS + setOf("please", "pls", "thanks", "thank", "you", "thanku", "thankyou", "thats", "that", "is", "it", "now",
        "talking", "for", "today", "ika", "inka", "ఇక", "ఇంక", "ఇంకా", "ఇప్పుడు", "ధన్యవాదాలు", "థాంక్స్", "థాంక్యూ", "bro", "బ్రో", "ra", "రా",
        "andi", "అండి", "good", "see", "u", "bye")
    private val STOP_PHRASES = setOf("no more", "thats all", "that is all", "nothing", "nothing else", "be quiet", "shut up", "no thanks",
        "no thank you", "ఏమీ లేదు", "ఇంకేం లేదు", "అంతే", "అంతే చాలు", "see you", "see you later", "talk later", "తర్వాత మాట్లాడదాం")

    /** True when all they said is "stop", "enough", "bye", "ok ok", "చాలు", "సరే సరే", "ఇక చాలు", "ok thanks bye"… */
    fun isStop(text: String): Boolean {
        val c = clean(text)
        if (c.isEmpty()) return false
        if (c in STOP_PHRASES) return true
        val w = words(text)
        if (w.size > 6) return false
        if (w.size >= 2 && w.all { it in OKS }) return true
        return w.any { it in STOP_WORDS } && w.all { it in STOP_WORDS || it in STOP_FILLER }
    }

    private val STAY_WORDS = setOf("stay", "undu", "ఉండు", "ఉండండి", "వెళ్ళకు", "వెళ్లకు", "vellaku", "listening")
    private val STAY_FILLER = OKS + setOf("please", "pls", "with", "me", "here", "keep", "on", "ikkade", "ఇక్కడే", "ఇక్కడ", "నాతో", "నాతోనే",
        "naatho", "natho", "naathone", "bro", "బ్రో", "ra", "రా", "andi", "అండి", "kasepu", "కాసేపు", "konchem", "కొంచెం", "వింటూ", "vintu")
    private val STAY_PHRASES = setOf("dont go", "do not go", "keep listening", "stay with me", "listen to me", "వెళ్ళిపోకు", "వెళ్లిపోకు")

    /** True when they ask the pet to stay and keep listening: "stay", "stay with me", "ఇక్కడే ఉండు", "నాతో ఉండు", "don't go". */
    fun isStay(text: String): Boolean {
        val c = clean(text)
        if (c.isEmpty()) return false
        if (c in STAY_PHRASES) return true
        val w = words(text)
        if (w.size > 5) return false
        return w.any { it in STAY_WORDS } && w.all { it in STAY_WORDS || it in STAY_FILLER }
    }

    // "Call me by name" switched by voice: "listen for your name" / "పేరు విను" (on), "stop listening for your name" /
    // "పేరు వినడం ఆపు" (off). Null: it isn't about that.
    private val WAKE_ON = Regex("(listen for (your|ur) name|call( you)? by (your )?name on|hands ?free on|start listening for (your|ur) name|" +
        "పేరు (విను|వింటూ ఉండు|వినడం మొదలు|వినడం ఆన్|ఆన్)|పేరుతో పిలిస్తే (విను|పలుకు)|peru vinu)")
    private val WAKE_OFF = Regex("(stop listening for (your|ur) name|dont listen for (your|ur) name|do not listen for (your|ur) name|hands ?free off|" +
        "call( you)? by (your )?name off|పేరు (వినడం ఆపు|వినకు|వినడం ఆఫ్|ఆఫ్)|పేరు కోసం వినకు|peru vinaku|peru vinadam aapu)")
    fun wakeSwitch(text: String): Boolean? {
        val c = clean(text)
        if (c.isEmpty() || words(text).size > 10) return null
        return when { WAKE_OFF.containsMatchIn(c) -> false; WAKE_ON.containsMatchIn(c) -> true; else -> null }
    }

    // "let's play", "play quiz", "games", "ఆట ఆడదాం", "క్విజ్ ఆడుకుందాం": a request to play (not "what game is on TV?")
    private val PLAY_EN = Regex("^(lets|let us|shall we|can we|i want to|i wanna|wanna|come on|come)? ?play( a| some| the)?( game| games| quiz| riddles?| tic tac toe| tictactoe| x o| memory( game)?| maths?( game)?| number game)?( with me| now| please)*$")
    private val PLAY_ONLY = Regex("^(games?|quiz|riddles?|tic tac toe|tictactoe|memory game|maths? game|ఆటలు|ఆట|గేమ్|గేమ్స్|క్విజ్|పొడుపు కథలు|పొడుపు కథ)( please| ప్లీజ్)?$")
    private val PLAY_TE = Regex("ఆడదాం|ఆడుదాం|ఆడుకుందాం|ఆడదామా|ఆడుకుందామా|ఆట ఆడ|aadadam|aadudam|aadukundam|adadam|adukundam|aadukundama")

    /** The game they asked for ("quiz", "riddles", "memory", "tictactoe", "maths", or "any"), or null when it isn't a request to play. */
    fun game(text: String): String? {
        val c = clean(text)
        if (c.isEmpty() || words(text).size > 8) return null
        if (!(PLAY_EN.matches(c) || PLAY_ONLY.matches(c) || PLAY_TE.containsMatchIn(c))) return null
        return when {
            Regex("quiz|క్విజ్").containsMatchIn(c) -> "quiz"
            Regex("riddle|పొడుపు").containsMatchIn(c) -> "riddles"
            Regex("tic ?tac ?toe|tictactoe|\\bx o\\b|ఎక్స్ ఓ").containsMatchIn(c) -> "tictactoe"
            Regex("memory|జ్ఞాపక").containsMatchIn(c) -> "memory"
            Regex("\\bmaths?\\b|number game|లెక్కల").containsMatchIn(c) -> "maths"
            else -> "any"
        }
    }

    private val YES = setOf("yes", "yeah", "yep", "yup", "sure", "haan", "ha", "avunu", "avnu", "అవును", "అవ్ను", "kavali", "kaavali", "కావాలి",
        "please", "ఉండు", "undu", "do", "it", "ok", "okay", "sare", "సరే", "ఓకే")
    private val NO = setOf("no", "nope", "nah", "vaddu", "వద్దు", "ledu", "లేదు", "dont", "not", "never", "వద్దులే")

    /** "yes" (true), "no" (false), or neither (null) for the pet's yes/no question. A "no" word wins ("no, not now"). */
    fun yesNo(text: String): Boolean? {
        val w = words(text)
        if (w.isEmpty() || w.size > 6) return null
        if (w.any { it in NO }) return false
        if (w.any { it in YES }) return true
        return null
    }

    // "చిరంజీవి పాటలు పెట్టు", "play Rathinamo song", "భక్తి పాటలు వినిపించు": what to play (and whether shorts). Done on the
    // phone at once, so a song never depends on the brain remembering to really start it.
    private val PLAY_WHAT = Regex("పాటలు|పాటల|పాట|సాంగ్స్|సాంగ్|songs?|వీడియోలు|వీడియో|videos?|షార్ట్స్|రీల్స్|shorts|reels|సినిమా|కామెడీ|comedy|భజన|కీర్తన", RegexOption.IGNORE_CASE)
    private val PLAY_DO = Regex("పెట్ట[^\\s]*|ప్లే\\s*చెయ్[^\\s]*|ప్లే\\s*చేయ[^\\s]*|ప్లే|వెయ్[^\\s]*|వేయ్[^\\s]*|వేయి|వినిపించ[^\\s]*|చూపించ[^\\s]*|\\bplay\\b|\\bput on\\b", RegexOption.IGNORE_CASE)
    private val PLAY_NOT = Regex("కాల్|call|అలారం|alarm|రిమైండర్|remind|స్టేటస్|status|పోస్ట్|ఎవరు|ఎందుకు|ఎప్పుడు|who|why|when|\\?", RegexOption.IGNORE_CASE)
    private val PLAY_DROP = Regex("చిలకమ్మా|చిలకమ్మ|దయచేసి|please|ఒకసారి|కొంచెం|నాకు|మాకు|\\bfor me\\b|\\bme\\b|\\ba\\b|\\bsome\\b|ఇప్పుడే|ఇప్పుడు|\\bnow\\b", RegexOption.IGNORE_CASE)
    fun playQuery(text: String, petName: String = ""): Pair<String, Boolean>? {
        val t = text.trim().trimEnd('.', '!', '।')
        if (t.length > 90 || !PLAY_WHAT.containsMatchIn(t) || !PLAY_DO.containsMatchIn(t) || PLAY_NOT.containsMatchIn(t)) return null
        var q = PLAY_DROP.replace(PLAY_DO.replace(t, " "), " ")
        if (petName.isNotBlank()) q = q.replace(petName, " ", ignoreCase = true)
        q = q.replace(Regex("హ్యాబిట్|హాబిట్|\\bhabit\\b", RegexOption.IGNORE_CASE), " ").replace(Regex("[,،]"), " ").replace(Regex("\\s+"), " ").trim()
        val short = Regex("షార్ట్స్|రీల్స్|shorts|reels", RegexOption.IGNORE_CASE).containsMatchIn(t)
        if (PLAY_WHAT.replace(q, "").isBlank()) q = if (Regex("[\\u0C00-\\u0C7F]").containsMatchIn(t)) "తెలుగు $q".trim() else "Telugu $q".trim()
        return q.take(100) to short
    }

    // A call through Habit: "హ్యాబిట్ ద్వారా రోజాకి కాల్ చేయి", "Roja ki Habit call", "call Roja on Habit", "రోజాకి వీడియో కాల్".
    // (Just "హ్యాబిట్, రోజాకి కాల్ చెయ్" is talking to the pet: that is a normal call.) Returns (who, video), or null.
    private const val HB = "(?:హ్యాబిట్|హాబిట్|హబిట్|హేబిట్|హెబిట్|హ్యాబీట్|హాబీట్|హేబీట్|హ్యాబిత్|habit)"
    private val HABIT_WAY = Regex("$HB\\s*(?:ద్వారా|ద్వార|లో|లోనే|తో|కాల్|call|వీడియో|video|యాప్|app)|(?:on|via|through|in|using)\\s+$HB|వీడియో\\s*కాల్|video\\s*call", RegexOption.IGNORE_CASE)
    private val CALL_WORD = Regex("కాల్|call|ఫోన్\\s*చే|ఫోన్\\s*చె", RegexOption.IGNORE_CASE)
    private val CALL_FILL = Regex("^(?:$HB|ద్వారా|ద్వార|లో|లోనే|తో|కాల్|కాల్స్|call|calls|వీడియో|video|ఫోన్|యాప్|app|చెయ్.*|చేయ్.*|చేయి|చేయండి|చేసి|చేస్తావా|కలుపు|కలిపి|ఒక్క|ఒక|ఒకసారి|ఒకటి|నువ్వు|నేను|చెప్పింది|చెప్పాను|ఇంకా|వినలేదు|ప్లీజ్|please|make|a|to|on|via|through|using|in|now|ఇప్పుడు|ఇప్పుడే|త్వరగా|కొంచెం|సరే|అమ్మా|రా|బాబు|మరి|కదా|అని|the|my|me|for)$", RegexOption.IGNORE_CASE)
    fun habitCall(text: String, petName: String = ""): Pair<String, Boolean>? {
        val t = text.trim().trimEnd('.', '!', '।', '?')
        if (t.length > 120 || !HABIT_WAY.containsMatchIn(t) || !CALL_WORD.containsMatchIn(t)) return null
        val video = Regex("వీడియో|video", RegexOption.IGNORE_CASE).containsMatchIn(t)
        val pn = petName.lowercase().split(Regex("[,\\s]+")).filter { it.length >= 2 }
        val words = t.replace(Regex("[,،!?।\"']"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            .filter { w -> !CALL_FILL.matches(w) && w.lowercase() !in pn }
            .map { w -> w.replace(Regex("(?:గారికి|గారికీ|కి|కీ|కు|కూ|తో)$"), "").replace(Regex("(?:ki|ku|ko)$", RegexOption.IGNORE_CASE), "") }
            .filter { it.length >= 2 }
        if (words.isEmpty() || words.size > 3) return null
        return words.joinToString(" ") to video
    }
}
