package com.soulfriend.pet

/**
 * A name said in Telugu found among names saved in English letters, and the other way round: "రోజా" is "Roja",
 * "శ్రీనివాస్ రెడ్డి" is "Srinivas Reddy", "కిషోర్" is "Kishore". Both are turned into how they sound and compared.
 */
object Names {
    private val VOWELS = mapOf('అ' to "a", 'ఆ' to "a", 'ఇ' to "i", 'ఈ' to "i", 'ఉ' to "u", 'ఊ' to "u", 'ఋ' to "ru", 'ఎ' to "e", 'ఏ' to "e",
        'ఐ' to "ai", 'ఒ' to "o", 'ఓ' to "o", 'ఔ' to "au")
    private val SIGNS = mapOf('ా' to "a", 'ి' to "i", 'ీ' to "i", 'ు' to "u", 'ూ' to "u", 'ృ' to "ru", 'ె' to "e", 'ే' to "e", 'ై' to "ai",
        'ొ' to "o", 'ో' to "o", 'ౌ' to "au")
    private val CONS = mapOf('క' to "k", 'ఖ' to "kh", 'గ' to "g", 'ఘ' to "gh", 'ఙ' to "n", 'చ' to "ch", 'ఛ' to "ch", 'జ' to "j", 'ఝ' to "jh", 'ఞ' to "n",
        'ట' to "t", 'ఠ' to "th", 'డ' to "d", 'ఢ' to "dh", 'ణ' to "n", 'త' to "t", 'థ' to "th", 'ద' to "d", 'ధ' to "dh", 'న' to "n",
        'ప' to "p", 'ఫ' to "ph", 'బ' to "b", 'భ' to "bh", 'మ' to "m", 'య' to "y", 'ర' to "r", 'ఱ' to "r", 'ల' to "l", 'ళ' to "l", 'వ' to "v",
        'శ' to "sh", 'ష' to "sh", 'స' to "s", 'హ' to "h")
    private const val VIRAMA = '్'
    private const val LIPS = "పఫబభమ"

    /** Telugu letters as English letters, the way names are usually spelled ("రోజా" → "roja"); other letters stay. */
    fun latin(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val k = CONS[c]
            when {
                k != null -> {
                    out.append(k)
                    val n = s.getOrNull(i + 1)
                    when {
                        n == VIRAMA -> i++                               // no vowel after it
                        n != null && SIGNS.containsKey(n) -> { out.append(SIGNS[n]); i++ }
                        else -> out.append('a')                          // its own "a"
                    }
                }
                VOWELS.containsKey(c) -> out.append(VOWELS[c])
                SIGNS.containsKey(c) -> out.append(SIGNS[c])
                c == 'ం' -> { val n = s.getOrNull(i + 1); out.append(if (n == null || n == ' ' || LIPS.indexOf(n) >= 0) 'm' else 'n') }
                c == 'ః' -> out.append('h')
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private val SAME = listOf("aa" to "a", "ee" to "i", "oo" to "u", "ou" to "u", "sh" to "s", "th" to "t", "dh" to "d", "kh" to "k", "gh" to "g",
        "bh" to "b", "ph" to "p", "ch" to "c", "ck" to "k", "w" to "v", "z" to "j", "f" to "p", "y" to "i", "q" to "k")

    /** How a name sounds, so that spellings agree: "Roja", "Rojaa", "రోజా" → "roja"; "Reddy", "రెడ్డి" → "redi". */
    fun key(name: String): String {
        var s = latin(name).lowercase().filter { it in 'a'..'z' }
        for ((a, b) in SAME) s = s.replace(a, b)
        val o = StringBuilder()
        for (ch in s) if (o.isEmpty() || o.last() != ch) o.append(ch)       // doubled letters once
        return o.toString()
    }

    private fun far(a: String, b: String): Int {                            // how many letters differ (edit distance)
        if (Math.abs(a.length - b.length) > 1) return 9
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1))
            prev = cur
        }
        return prev[b.length]
    }

    // English letters as they are said in Telugu: "వీఆర్ఓ" is VRO, "ఎంఆర్ఓ" MRO, "ఆర్ఐ" RI, "డీటీ" DT
    private val LETTERS = listOf("డబ్ల్యూ" to "w", "డబ్లు" to "w", "ఎక్స్" to "x", "హెచ్" to "h", "ఎయిచ్" to "h", "క్యూ" to "q", "జెడ్" to "z",
        "ఎఫ్" to "f", "ఎల్" to "l", "ఎం" to "m", "ఎమ్" to "m", "ఎన్" to "n", "ఆర్" to "r", "ఎస్" to "s", "వై" to "y", "ఐ" to "i", "ఏ" to "a",
        "ఎ" to "a", "బీ" to "b", "బి" to "b", "సీ" to "c", "సి" to "c", "డీ" to "d", "డి" to "d", "ఈ" to "e", "జీ" to "g", "జి" to "g",
        "జే" to "j", "కే" to "k", "ఓ" to "o", "పీ" to "p", "పి" to "p", "టీ" to "t", "టి" to "t", "యూ" to "u", "యు" to "u", "వీ" to "v", "వి" to "v")
    /** "వీఆర్ఓ" → "vro" (a word made only of English letter names, 2 or more); null for any other word. */
    fun initials(word: String): String? {
        val w = word.replace("\u200c", "").replace(".", "")
        val out = StringBuilder(); var i = 0
        while (i < w.length) {
            val hit = LETTERS.filter { w.startsWith(it.first, i) }.maxByOrNull { it.first.length } ?: return null
            out.append(hit.second); i += hit.first.length
        }
        return out.toString().takeIf { it.length >= 2 }
    }

    /**
     * How well the saved name answers to the said one: 2 = the same name (the whole of it, or one of its words),
     * 1 = nearly (one letter apart, as "Kishore" and "కిషోర్"), 0 = no. English letters said in Telugu count as letters.
     */
    fun match(said: String, saved: String): Int {
        val m = match1(said, saved)
        if (m == 2) return m
        val alt = said.trim().split(Regex("\\s+")).joinToString(" ") { initials(it) ?: it }
        return if (alt != said.trim()) maxOf(m, match1(alt, saved)) else m
    }
    private fun match1(said: String, saved: String): Int {
        val q = key(said)
        if (q.length < 2) return 0
        val full = key(saved)
        if (full == q || (q.length >= 4 && full.contains(q))) return 2
        val words = saved.split(' ', '.', ',', '-', '_', '(', ')').map { key(it) }.filter { it.isNotEmpty() }
        if (words.any { it == q }) return 2
        val qs = said.trim().split(Regex("\\s+")).map { key(it) }.filter { it.length >= 2 }
        if (qs.size >= 2 && qs.all { w -> words.any { it == w || (w.length >= 4 && it.startsWith(w)) } }) return 2
        if (q.length >= 5 && (far(q, full) <= 1 || words.any { it.length >= 5 && far(q, it) <= 1 })) return 1
        return 0
    }
}
