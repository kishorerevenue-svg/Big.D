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
    /** The words only, without citation marks or HTML tags. */
    fun plain(s: String): String = if ('<' !in s) s else SPACES.replace(MARKS.replace(BREAKS.replace(s, "\n"), ""), " ").trim()

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
}
