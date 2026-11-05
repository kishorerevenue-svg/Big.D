package com.soulfriend.pet

import android.content.Context
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A small brain inside the phone, for when the AI can't be reached (no internet, the server is down, or the AI is busy).
 * It understands common requests in English, Telugu and Telugu typed in English letters, and answers in the same shape
 * as the AI ({reply, emotion, actions}), so the pet still sets reminders, calls family, opens apps and so on.
 * Nothing is sent anywhere.
 */
object LocalBrain {
    @Volatile private var lastNote = 0L

    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val TELUGU = Regex("[\\u0C00-\\u0C7F]")
    private const val NB = "(?<![\\p{L}\\p{M}\\p{N}])"   // a word starts here (works for Telugu too)
    private const val NE = "(?![\\p{L}\\p{M}\\p{N}])"    // a word ends here

    /** What the small brain may look at: all of it stays on the phone. */
    internal class Facts(val lang: String, val family: () -> List<FamilyContact>, val todos: () -> List<Todo>,
                         val reminders: () -> List<Reminder>, val battery: () -> Pair<Int, Boolean>?)

    /** The answer, or null when this small brain doesn't understand. */
    fun answer(ctx: Context, store: Store, input: String, now: LocalDateTime = LocalDateTime.now()): JSONObject? =
        answerWith(facts(ctx, store), input, now)

    private fun facts(ctx: Context, store: Store) = Facts(store.lang, { store.family() }, { store.todos() }, { store.reminders() }, {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }?.let { it to bm.isCharging }
    })

    internal fun answerWith(f: Facts, input: String, now: LocalDateTime, chatToo: Boolean = true): JSONObject? {
        val raw = input.trim()
        if (raw.isEmpty() || raw.length > 600) return null
        val te = TELUGU.containsMatchIn(raw) || f.lang == "te"
        val t = clean(raw)
        if (t.isEmpty()) return null
        val out = understand(f, t, te, now, chatToo) ?: return null
        return if (!chatToo) out else out.put("reply", out.optString("reply") + noteOnce(te)).put("offline", true)
    }

    /** Only the things the phone itself does (reminders, alarms, timers, calls, apps, lists, time, battery, sums, fraud check):
     *  used when the server's own brain answered, which can talk but can't set an alarm. null for everything else. */
    fun device(ctx: Context, store: Store, input: String, now: LocalDateTime = LocalDateTime.now()): JSONObject? =
        answerWith(facts(ctx, store), input, now, chatToo = false)

    /** Now and then (at most every 10 minutes), say why the answers are simpler than usual. */
    fun noteOnce(te: Boolean): String {
        val ms = System.currentTimeMillis()
        if (ms - lastNote < 10 * 60_000L) return ""
        lastNote = ms
        return "\n\n" + say(te, "📴 My AI brain can't be reached right now, so my small built-in helper is answering.",
            "📴 నా AI మెదడు ఇప్పుడు అందుబాటులో లేదు, అందుకే ఫోన్‌లోని చిన్న సహాయకుడు జవాబిస్తున్నాడు.")
    }

    /** What the small brain can do (shown when it doesn't understand). */
    fun hint(store: Store) = if (store.lang == "te")
        "AI లేకపోయినా నేను ఇవి చేయగలను: రిమైండర్, అలారం, టైమర్, కుటుంబానికి కాల్, యాప్ ఓపెన్, లిస్ట్, సమయం, తేదీ, లెక్కలు, మెసేజ్ మోసమా అని చెక్."
    else "Even without the AI I can: set reminders, alarms and timers, call your family, open apps, add to your list, tell the time and date, do sums and check a message for fraud."

    // ---------------------------------------------------------------------------------------------
    private fun understand(f: Facts, t: String, te: Boolean, now: LocalDateTime, chatToo: Boolean = true): JSONObject? =
        emergency(t, te) ?: timeOrDate(t, te, now) ?: battery(f, t, te) ?: timer(t, te) ?: alarm(t, te, now)
            ?: reminder(t, te, now) ?: lists(f, t, te) ?: noteOrRemember(t, te) ?: call(f, t, te)
            ?: openApp(t, te) ?: sums(t, te) ?: fraud(t, te) ?: (if (chatToo) chat(t, te) else null)

    private fun clean(s: String): String {
        // the case is kept (so "Call Ravi" stays "Ravi"); every pattern below ignores case
        var x = s.replace("\u200C", "").replace("\u200D", "").replace(Regex("\\s+"), " ").trim()
        x = x.replace(re("^(hey |hi |ok |okay )?(big ?d( ai)?|puppy|buddy)[,!.:\\s]+"), "")
        x = x.replace(re("$NB(please|pls|plz|kindly|bro|ra|దయచేసి|కొంచెం|ప్లీజ్)$NE"), " ")
        return x.replace(Regex("\\s+"), " ").trim().trim('.', '!', '?', '।', ',', ' ')
    }

    private fun say(te: Boolean, en: String, tel: String) = if (te) tel else en
    private fun act(type: String, vararg kv: Pair<String, Any>) = JSONObject().put("type", type).also { o -> kv.forEach { (k, v) -> o.put(k, v) } }
    private fun reply(text: String, emotion: String = "happy", vararg actions: JSONObject) =
        JSONObject().put("reply", text).put("emotion", emotion).put("actions", JSONArray().also { a -> actions.forEach { a.put(it) } })

    // ---------------- emergency ----------------
    private fun emergency(t: String, te: Boolean): JSONObject? {
        val numbers = say(te,
            "Emergency numbers: 112 all emergencies · 108 ambulance · 100 police · 101 fire · 1930 cyber fraud · 181 women · 1098 children · 14567 elders",
            "అత్యవసర నంబర్లు: 112 అన్నిటికీ · 108 అంబులెన్స్ · 100 పోలీస్ · 101 ఫైర్ · 1930 సైబర్ మోసం · 181 మహిళలు · 1098 పిల్లలు · 14567 వృద్ధులు")
        if (re("emergency numbers?|helpline|అత్యవసర నంబర్లు|హెల్ప్‌?లైన్").containsMatchIn(t)) return reply("☎️ $numbers")
        val (num, en, tel) = when {
            re("^(call |dial )?(an |the )?ambulance$|^ambulance (ki |ku )?(call|phone)|అంబులెన్స్").containsMatchIn(t) ->
                Triple("108", "🚑 Ambulance: 108.", "🚑 అంబులెన్స్: 108.")
            re("^(call |dial )?(the )?police$|^police (ki |ku )?(call|phone)|పోలీస్.{0,6}(ఫోన్|కాల్)").containsMatchIn(t) ->
                Triple("100", "🚓 Police: 100 (or 112).", "🚓 పోలీస్: 100 (లేదా 112).")
            re("^(call |dial )?(the )?fire ?(brigade|engine|station)$|ఫైర్ ఇంజన్|అగ్నిమాపక").containsMatchIn(t) ->
                Triple("101", "🚒 Fire: 101.", "🚒 ఫైర్: 101.")
            re("${NB}sos$NE|${NB}emergency$NE|^help me$|^save me|ఎమర్జెన్సీ|కాపాడండి|కాపాడు|ఆపదలో|ఆదుకోండి").containsMatchIn(t) ->
                Triple("112", "🆘 112 is for all emergencies. I'm also opening Family & SOS.", "🆘 112 అన్ని అత్యవసరాలకి. Family & SOS కూడా తెరుస్తున్నాను.")
            else -> return null
        }
        val acts = mutableListOf(act("call", "number" to num, "contact" to num))
        if (num == "112") acts.add(act("open_page", "page" to "family"))
        return reply(say(te, en, tel) + "\n\n" + numbers, "surprised", *acts.toTypedArray())
    }

    // ---------------- time, date, battery ----------------
    private val TE_DAYS = listOf("సోమవారం", "మంగళవారం", "బుధవారం", "గురువారం", "శుక్రవారం", "శనివారం", "ఆదివారం")
    private val TE_MONTHS = listOf("జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్")

    private fun clock(te: Boolean, time: LocalTime): String {
        val h12 = if (time.hour % 12 == 0) 12 else time.hour % 12
        val hm = "%d:%02d".format(h12, time.minute)
        return if (!te) hm + (if (time.hour < 12) " AM" else " PM")
        else (when (time.hour) { in 4..11 -> "ఉదయం"; in 12..15 -> "మధ్యాహ్నం"; in 16..19 -> "సాయంత్రం"; else -> "రాత్రి" }) + " " + hm
    }

    private fun day(te: Boolean, d: LocalDate) = if (te) "${d.dayOfMonth} ${TE_MONTHS[d.monthValue - 1]} ${d.year}, ${TE_DAYS[d.dayOfWeek.value - 1]}"
        else DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH).format(d)

    private fun timeOrDate(t: String, te: Boolean, now: LocalDateTime): JSONObject? {
        val time = re("^(what('?s| is) the )?(current )?time( now| please| is it)?$|^what time is it( now)?$|^(ippudu )?time (enta|entha|ento|cheppu)$|" +
            "(టైం|టైమ్|సమయం)\\s*(ఎంత|ఎంతైంది|ఎంత అయింది|చెప్పు)|ఎన్ని గంటలు|ఇప్పుడు (టైం|టైమ్|సమయం)").containsMatchIn(t)
        val date = re("^(what('?s| is) )?(the )?(today'?s )?(date|day)( today| is it)?$|^what day is (it|today)$|^which day is today$|^(today )?date (enta|entha|cheppu)$|" +
            "(ఈ ?రోజు|ఇవాళ).{0,12}(తేదీ|తారీఖు|తారీకు|ఏ రోజు|ఏ వారం|ఏం వారం)|తేదీ ఎంత|ఏ వారం").containsMatchIn(t)
        if (!time && !date) return null
        val n = now.toLocalTime(); val d = now.toLocalDate()
        return reply(when {
            time && date -> say(te, "🕐 It's ${clock(false, n)}, ${day(false, d)}.", "🕐 ఇప్పుడు ${clock(true, n)}, ${day(true, d)}.")
            time -> say(te, "🕐 It's ${clock(false, n)}.", "🕐 ఇప్పుడు సమయం ${clock(true, n)}.")
            else -> say(te, "📅 Today is ${day(false, d)}.", "📅 ఈ రోజు ${day(true, d)}.")
        })
    }

    private fun battery(f: Facts, t: String, te: Boolean): JSONObject? {
        if (!re("battery|బ్యాటరీ|బ్యాటరి|^(charge|charging) (enta|entha|how much)|(ఛార్జ్|చార్జ్|ఛార్జింగ్|చార్జింగ్) ఎంత").containsMatchIn(t)) return null
        if (re("saver|save|సేవర్|buy|కొను").containsMatchIn(t)) return null
        val (pct, charging) = f.battery() ?: return null
        val low = pct <= 20 && !charging
        return reply(say(te,
            "🔋 Battery is at $pct%" + (if (charging) ", charging." else ".") + (if (low) " Please charge your phone soon." else ""),
            "🔋 బ్యాటరీ $pct% ఉంది" + (if (charging) ", ఛార్జ్ అవుతోంది." else ".") + (if (low) " త్వరగా ఛార్జ్ పెట్టండి." else "")),
            if (low) "sleepy" else "happy")
    }

    // ---------------- timer, alarm, reminder ----------------
    private val DURATION = re("(\\d+(?:\\.\\d+)?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|సెకన్లు|సెకన్ల|సెకను|సెకండ్లు|నిమిషాలు|నిమిషాల|నిమిషం|నిముషాలు|నిముషాల|గంటలు|గంటల|గంట|nimishalu|nimishala|nimisham|gantalu|gantala|ganta)$NE")

    private fun seconds(m: MatchResult): Long {
        val n = m.groupValues[1].toDouble(); val u = m.groupValues[2].lowercase()
        val k = when { u.startsWith("s") || u.startsWith("సెక") -> 1; u.startsWith("h") || u.startsWith("గంట") || u.startsWith("gant") -> 3600; else -> 60 }
        return (n * k).roundToLong()
    }

    private fun timer(t: String, te: Boolean): JSONObject? {
        if (!re("timer|టైమర్").containsMatchIn(t)) return null
        val found = DURATION.findAll(t).toList()
        val half = re("half an hour|అర ?గంట|ara ?ganta").containsMatchIn(t)
        val secs = found.sumOf { seconds(it) } + (if (half) 1800 else 0)
        if (secs <= 0) return reply(say(te, "⏱️ For how long? Say for example \"timer 10 minutes\".", "⏱️ ఎంత సేపు? ఉదాహరణకి \"10 నిమిషాల టైమర్\" అని చెప్పండి."), "thinking")
        if (secs > 24 * 3600) return null
        return reply(say(te, "⏱️ Okay!", "⏱️ సరే!"), "happy", act("timer", "seconds" to secs, "label" to say(te, "Timer", "టైమర్"))).put("sound", "ding")
    }

    private class When(val at: LocalDateTime, val parts: List<String>, val repeat: String = "")

    // a clock time: "at 6", "6 pm", "6:30", "18:30", "6 గంటలకు", "6 ki"
    private val CLOCK = re("(\\bat\\s+|@\\s*)?(?<![\\d:.])(\\d{1,2})(?:[:.](\\d{2}))?(?!\\d)\\s*(a\\.?\\s?m\\b\\.?|p\\.?\\s?m\\b\\.?|o'?\\s?clock\\b|గంటలకు|గంటలకి|గంటకు|గం\\.|\\bki\\b|\\bku\\b)?")

    /** When something should happen. [loose]: a bare number is a clock time too (alarms). */
    private fun parseWhen(t: String, now: LocalDateTime, loose: Boolean, usualPart: String = ""): When? {
        val parts = mutableListOf<String>()
        var repeat = ""
        re("every ?day|daily|ప్రతి ?రోజూ?|రోజూ|prathi ?roju").find(t)?.let { repeat = "daily"; parts += it.value }
            ?: re("every ?week|weekly|ప్రతి ?వారం").find(t)?.let { repeat = "weekly"; parts += it.value }
            ?: re("every ?month|monthly|ప్రతి ?నెల").find(t)?.let { repeat = "monthly"; parts += it.value }
            ?: re("week ?days|on weekdays").find(t)?.let { repeat = "weekdays"; parts += it.value }
        // "in 10 minutes", "after 2 hours", "10 నిమిషాల తర్వాత", "half an hour later"
        for (m in DURATION.findAll(t)) {
            val pre = re("(?:^|\\s)(in|after)\\s*$").find(t.substring(0, m.range.first))
            val post = re("^\\s*(later|tarvata|taruvata|తర్వాత|తరువాత|లో$NE)").find(t.substring(m.range.last + 1))
            if (pre != null || post != null) {
                return When(now.plusSeconds(seconds(m)), listOfNotNull(pre?.value?.trim(), m.value, post?.value?.trim()) + parts, repeat)
            }
        }
        re("(in |after )?(half an hour|అర ?గంట|ara ?ganta)( later| తర్వాత| లో)?").find(t)?.let { return When(now.plusMinutes(30), listOf(it.value) + parts, repeat) }
        // the day
        var date = now.toLocalDate(); var dayGiven = false
        re("day after tomorrow|ఎల్లుండి|ellundi").find(t)?.let { date = date.plusDays(2); dayGiven = true; parts += it.value }
        if (!dayGiven) re("tomorrow|రేపు|\\brepu\\b").find(t)?.let { date = date.plusDays(1); dayGiven = true; parts += it.value }
        if (!dayGiven) re("\\btoday\\b|ఈ ?రోజు|ఇవాళ|\\beeroju\\b|\\bivala\\b").find(t)?.let { parts += it.value }
        // the part of the day
        val pw = re("morning|ఉదయాన్నే|ఉదయం|పొద్దున్నే|పొద్దున్న|పొద్దున|poddun+e?|afternoon|మధ్యాహ్నం|madhyahnam|madhyanam|evening|సాయంత్రం|sayantram|tonight|night|రాత్రికి|రాత్రి|ratri").find(t)
        val part = pw?.value?.let { w -> when {
            re("morn|ఉదయ|పొద్దున|poddun").containsMatchIn(w) -> "am"
            re("after|మధ్యాహ్న|madhya").containsMatchIn(w) -> "noon"
            re("even|సాయంత్ర|sayant").containsMatchIn(w) -> "pm"
            else -> "night"
        } } ?: ""
        pw?.let { parts += it.value }
        // the clock time
        val all = CLOCK.findAll(t).filter {
            val h = it.groupValues[2].toInt(); val mi = it.groupValues[3].ifEmpty { "0" }.toInt()
            // a bare number followed by "minutes", "rupees" or "tablets" is not a clock time ("at 6", "6 pm", "6 గంటలకు" always are)
            h in 0..23 && mi in 0..59 && (it.groupValues[1].isNotBlank() || it.groupValues[4].isNotBlank() || it.groupValues[3].isNotEmpty() ||
                !re("^\\s*(min|sec|hour|hr|నిమి|నిము|సెక|గంటలు|గంటల$NE|rupees|rs|₹|tablet|pill|మాత్ర|రూపాయ)").containsMatchIn(t.substring(it.range.last + 1)))
        }.toList()
        val strong = all.firstOrNull { it.groupValues[1].isNotBlank() || it.groupValues[4].isNotBlank() || it.groupValues[3].isNotEmpty() }
        val m = strong ?: if (all.size == 1 && (loose || part.isNotEmpty() || dayGiven)) all[0] else null
        if (m == null) {
            if (part.isEmpty()) return null              // no time at all: ask
            val h = when (part) { "am" -> 8; "noon" -> 14; "pm" -> 18; else -> 21 }   // "tomorrow morning": 8 AM
            var at = LocalDateTime.of(date, LocalTime.of(h, 0))
            if (!at.isAfter(now)) at = at.plusDays(1)
            return When(at, parts, repeat)
        }
        val base = m.groupValues[2].toInt(); val mi = m.groupValues[3].ifEmpty { "0" }.toInt()
        val suf = m.groupValues[4].lowercase().replace(Regex("[.\\s]"), "")
        val p = when { suf.startsWith("a") -> "am"; suf.startsWith("p") -> "pm"; else -> part.ifEmpty { usualPart } }
        val hours: List<Int> = when {
            base > 12 -> listOf(base)
            p == "am" -> listOf(if (base == 12) 0 else base)
            p == "pm" -> listOf(if (base == 12) 12 else base + 12)
            p == "noon" -> listOf(if (base == 12) 12 else base + 12)
            p == "night" -> listOf(when { base == 12 -> 0; base in 1..4 -> base; else -> base + 12 })
            base == 0 -> listOf(0)
            base == 12 -> listOf(12, 0)
            else -> listOf(base, base + 12)                // no AM/PM: whichever comes next
        }
        val sensible = if (hours.size == 1) hours[0] else when (base) { in 5..11 -> base; in 1..4 -> base + 12; else -> 12 }
        parts += m.value.trim()
        val at = if (dayGiven) LocalDateTime.of(date, LocalTime.of(sensible, mi))
            else hours.map { LocalDateTime.of(date, LocalTime.of(it, mi)) }.filter { it.isAfter(now) }.minOrNull()
                ?: LocalDateTime.of(date.plusDays(1), LocalTime.of(sensible, mi))
        return When(at, parts, repeat)
    }

    private fun alarm(t: String, te: Boolean, now: LocalDateTime): JSONObject? {
        if (!re("alarm|wake me|అలారం|అలారమ్|లేపు|నిద్ర లేపు|\\blepu\\b").containsMatchIn(t)) return null
        if (re("remind|గుర్తు").containsMatchIn(t)) return null
        val wake = re("wake|లేపు|\\blepu\\b").containsMatchIn(t)          // "wake me at 6": the morning
        val w = parseWhen(t, now, loose = true, usualPart = if (wake) "am" else "")
            ?: return reply(say(te, "⏰ What time? Say for example \"alarm at 5:30 am\".", "⏰ ఎన్ని గంటలకు? ఉదాహరణకి \"ఉదయం 5:30 కి అలారం\" అని చెప్పండి."), "thinking")
        return reply(say(te, "⏰ Okay!", "⏰ సరే!"), "happy", act("alarm", "hour" to w.at.hour, "minute" to w.at.minute, "label" to "BigD AI")).put("sound", "ding")
    }

    private fun reminder(t: String, te: Boolean, now: LocalDateTime): JSONObject? {
        if (re("^(show|read|tell me|what are|list)? ?(my |the )?reminders$|రిమైండర్లు|రిమైండర్స్").containsMatchIn(t)) return null   // the list: see lists()
        if (!re("remind|రిమైండర్|గుర్తు ?(చేయి|చెయ్యి|చేయండి|చెయ్|చేయ్|చేస్తావా|చెయ్యవా|చెయ్యి)|gu?rthu ?(cheyyi|chey|cheyi|cheyyandi)|gurtu ?(cheyyi|chey|cheyi)").containsMatchIn(t)) return null
        val w = parseWhen(t, now, loose = false)
            ?: return reply(say(te, "⏰ When should I remind you? Say for example \"remind me at 6 pm to take tablets\".",
                "⏰ ఎప్పుడు గుర్తు చేయాలి? ఉదాహరణకి \"సాయంత్రం 6 గంటలకు మందులు వేసుకోమని గుర్తు చేయి\" అని చెప్పండి."), "thinking")
        var x = t
        for (p in w.parts.filter { it.isNotBlank() }.sortedByDescending { it.length }) x = x.replace(Regex(NB + Regex.escape(p) + NE, RegexOption.IGNORE_CASE), " ")
        x = x.replace(re("set (a |an )?reminder( for| to)?|remind(er)?( me| us)?|రిమైండర్ ?(పెట్టు|సెట్ చేయి|సెట్ చెయ్యి|పెట్టండి)?|గుర్తు ?(చేయి|చెయ్యి|చేయండి|చెయ్|చేయ్|చేస్తావా|చెయ్యవా)|gu?rthu ?(cheyyi|chey|cheyi|cheyyandi)|gurtu ?(cheyyi|chey|cheyi)"), " ")
            .replace(Regex("\\s+"), " ").trim()
        repeat(3) {
            x = x.replace(re("^(to|that|about|of|for|on|at|me|and|ani|అని|కి|కు|,|-)\\s+"), "").replace(re("\\s+(to|that|at|on|for|ani|అని|,|-)$"), "").trim()
        }
        val text = x.trim(' ', ',', '.', '-').replaceFirstChar { it.titlecase(Locale.ROOT) }.ifBlank { say(te, "Reminder", "రిమైండర్") }
        return reply(say(te, "⏰ Okay! I'll remind you: “$text”", "⏰ సరే! గుర్తు చేస్తాను: “$text”"), "happy",
            act("reminder", "at" to w.at.withSecond(0).withNano(0).toString(), "text" to text, "repeat" to w.repeat)).put("sound", "ding")
    }

    // ---------------- list, notes, memories ----------------
    private fun lists(f: Facts, t: String, te: Boolean): JSONObject? {
        val add = re("^add (.+?) (?:to|in|into|on) (?:my |the )?(?:shopping |todo |to-?do |grocery |groceries )?list$").find(t)
            ?: re("^(?:put|write) (.+?) (?:in|on) (?:my |the )?list$").find(t)
            ?: re("^(.+?)\\s*(?:ని|ను)?\\s*(?:నా\\s*)?(?:లిస్ట్|లిస్టు|జాబితా)\\s*(?:లో|కి)?\\s*(?:పెట్టు|చేర్చు|రాయి|యాడ్ చేయి|యాడ్ చెయ్యి|యాడ్ చేయ్|add)$").find(t)
            ?: re("^(.+?) list lo (?:add|pettu|rayi|cheyyi|add cheyyi)$").find(t)
        if (add != null) {
            val item = add.groupValues[1].trim().trim(',', '.').replaceFirstChar { it.titlecase(Locale.ROOT) }
            if (item.isNotEmpty() && item.length <= 120) return reply(say(te, "📝 Done!", "📝 సరే!"), "happy", act("todo_add", "text" to item))
        }
        if (re("^(?:show|read|tell me|what'?s (?:on|in)) (?:my |the )?(?:shopping |todo |to-?do )?list$|^my (?:shopping |todo |to-?do )?list$|^list$|" +
                "(?:నా )?(?:లిస్ట్|లిస్టు|జాబితా)\\s*(?:చూపించు|చెప్పు|చదువు|ఏంటి|ఏమిటి)|^(?:na )?list (?:chupinchu|cheppu|chaduvu)$").containsMatchIn(t)) {
            val open = f.todos().filter { !it.done }
            return reply(if (open.isEmpty()) say(te, "📝 Your list is empty.", "📝 మీ లిస్ట్ ఖాళీగా ఉంది.")
                else say(te, "📝 Your list:\n", "📝 మీ లిస్ట్:\n") + open.take(15).joinToString("\n") { "• " + it.text })
        }
        if (re("^(?:show|read|tell me|what are) (?:my |the )?reminders$|^my reminders$|^reminders$|రిమైండర్లు|రిమైండర్స్").containsMatchIn(t)) {
            val now = System.currentTimeMillis()
            val list = f.reminders().filter { it.at > now || it.repeat.isNotEmpty() }.sortedBy { it.at }
            val fmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)
            return reply(if (list.isEmpty()) say(te, "⏰ You have no reminders.", "⏰ మీకు రిమైండర్లు ఏమీ లేవు.")
                else say(te, "⏰ Your reminders:\n", "⏰ మీ రిమైండర్లు:\n") + list.take(10).joinToString("\n") {
                    "• " + fmt.format(java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault())) + " · " + it.text +
                        (if (it.repeat.isNotEmpty()) " (" + Reminders.repeatLabel(it.repeat) + ")" else "")
                })
        }
        return null
    }

    private fun noteOrRemember(t: String, te: Boolean): JSONObject? {
        val note = re("^(?:take (?:a )?note|note down|write (?:a )?note|save (?:a )?note|note)[:\\s-]+(.+)$").find(t)
            ?: re("^(.+?)\\s*(?:అని)?\\s*(?:నోట్|నోటు)\\s*(?:చేయి|చెయ్యి|రాయి|పెట్టు|చేసుకో)$").find(t)
        if (note != null) return reply(say(te, "🗒️ Saved.", "🗒️ సేవ్ చేశాను."), "happy", act("note_save", "text" to note.groupValues[1].trim()))
        val mem = re("^remember (?:that )?(.+)$").find(t) ?: re("^(.+?)\\s*(?:అని)?\\s*గుర్తుంచుకో(?:ండి|వా)?$").find(t)
        if (mem != null) return reply(say(te, "🧠 Okay!", "🧠 సరే!"), "happy", act("remember", "text" to mem.groupValues[1].trim()))
        return null
    }

    // ---------------- calls ----------------
    private val RELATIONS = listOf(
        listOf("amma", "mother", "mom", "mummy", "mommy", "అమ్మ", "తల్లి"),
        listOf("nanna", "father", "dad", "daddy", "nannagaru", "నాన్న", "నాన్నగారు", "తండ్రి"),
        listOf("akka", "elder sister", "sister", "అక్క"), listOf("chelli", "younger sister", "చెల్లి", "చెల్లెలు"),
        listOf("anna", "elder brother", "brother", "అన్న", "అన్నయ్య"), listOf("tammudu", "younger brother", "తమ్ముడు", "తమ్ముడి"),
        listOf("wife", "bharya", "భార్య", "ఆవిడ"), listOf("husband", "bhartha", "భర్త", "ఆయన", "వారు"),
        listOf("son", "koduku", "కొడుకు", "అబ్బాయి", "babu"), listOf("daughter", "kuthuru", "కూతురు", "కూతురి", "అమ్మాయి", "papa"),
        listOf("grandmother", "grandma", "ammamma", "nanamma", "అమ్మమ్మ", "నానమ్మ", "బామ్మ"),
        listOf("grandfather", "grandpa", "thatha", "tatha", "తాత", "తాతయ్య"),
        listOf("uncle", "mama", "babai", "peddanna", "మామ", "మామయ్య", "బాబాయ్", "బాబాయి", "పెదనాన్న"),
        listOf("aunt", "aunty", "atta", "pinni", "peddamma", "అత్త", "అత్తయ్య", "పిన్ని", "పెద్దమ్మ"),
        listOf("doctor", "డాక్టర్", "వైద్యుడు"), listOf("friend", "స్నేహితుడు", "స్నేహితుడి", "ఫ్రెండ్")
    )

    private fun family(f: Facts, who: String): FamilyContact? {
        val q = who.trim().lowercase().removePrefix("my ").removePrefix("మా ").removePrefix("నా ").trim()
        if (q.isEmpty()) return null
        val fam = f.family()
        fam.firstOrNull { it.name.lowercase() == q }?.let { return it }
        val group = RELATIONS.firstOrNull { g -> g.any { it == q } }
        if (group != null) fam.firstOrNull { c ->
            val r = c.relation.lowercase().trim(); val n = c.name.lowercase().trim(); val words = r.split(Regex("[\\s,/()-]+"))
            group.any { it == r || it in words || it == n }
        }?.let { return it }
        return fam.firstOrNull { q.length >= 3 && (it.name.lowercase().startsWith(q) || it.name.lowercase().split(" ").any { p -> p == q }) }
    }

    private fun call(f: Facts, t: String, te: Boolean): JSONObject? {
        re("^(?:call|dial|phone)\\s+([+\\d][\\d\\s-]{2,})$").find(t)?.let { m ->
            val n = m.groupValues[1].filter { it.isDigit() || it == '+' }
            return reply(say(te, "📞 Okay!", "📞 సరే!"), "happy", act("call", "number" to n, "contact" to n))
        }
        val m = re("^(?:call|phone|dial|ring)\\s+(?:to\\s+)?(.+?)(?:\\s+(?:now|once))?$").find(t)
            ?: re("^(.+?)\\s*(?:కి|కు)?\\s*(?:ఫోన్|కాల్)\\s*(?:చెయ్యి|చేయి|చెయ్|చేయండి|కొట్టు|చేయ్)$").find(t)
            ?: re("^(.+?)\\s*(?:ki|ku)?\\s+(?:phone|call)\\s*(?:cheyyi|chey|cheyi|cheyyu|kottu)$").find(t)
            ?: return null
        var who = m.groupValues[1].trim()
        if (who.isEmpty() || who.length > 40) return null
        val c = family(f, who)
        if (c != null) who = c.name
        val shown = who.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return reply(say(te, "📞 Calling $shown…", "📞 $shown కి కాల్ చేస్తున్నాను…"), "happy",
            act("call", "number" to (c?.phone ?: ""), "contact" to shown))
    }

    // ---------------- apps and pages ----------------
    private val TE_APPS = mapOf("వాట్సాప్" to "whatsapp", "వాట్సప్" to "whatsapp", "యూట్యూబ్" to "youtube", "కెమెరా" to "camera", "ఫోన్ పే" to "phonepe",
        "ఫోన్‌పే" to "phonepe", "గూగుల్ పే" to "google pay", "జీపే" to "google pay", "పేటీఎం" to "paytm", "ఫేస్‌బుక్" to "facebook", "ఫేస్ బుక్" to "facebook",
        "ఇన్‌స్టాగ్రామ్" to "instagram", "ఇన్స్టాగ్రామ్" to "instagram", "గ్యాలరీ" to "gallery", "ఫోటోలు" to "photos", "మెసేజెస్" to "messages",
        "సెట్టింగ్స్" to "settings", "క్యాలిక్యులేటర్" to "calculator", "క్లాక్" to "clock", "గడియారం" to "clock", "మ్యాప్స్" to "maps", "క్రోమ్" to "chrome",
        "ఫోన్" to "phone", "కాంటాక్ట్స్" to "contacts", "క్యాలెండర్" to "calendar", "ప్లే స్టోర్" to "play store")
    private val PAGES = listOf("tasks" to "list|lists|todo|to-?do|tasks|reminders|లిస్ట్|లిస్టు|రిమైండర్లు", "vault" to "vault|వాల్ట్",
        "family" to "sos|family|family & sos|ఫ్యామిలీ|కుటుంబం", "translate" to "translate|translator|అనువాదం|ట్రాన్స్‌?లేట్",
        "files" to "letter|letters|files|లెటర్|లేఖ", "feedback" to "media|మీడియా", "govt" to "govt|government|government services|ప్రభుత్వ సేవలు")

    private fun openApp(t: String, te: Boolean): JSONObject? {
        val m = re("^(?:open|start|launch)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app)?$").find(t)
            ?: re("^(.+?)\\s*(?:ని|ను|యాప్)?\\s*(?:ఓపెన్|ఓపన్|తెరువు|తెరవు)\\s*(?:చెయ్యి|చేయి|చెయ్|చేయండి|చేయ్)?$").find(t)
            ?: re("^(.+?)\\s+open\\s*(?:cheyyi|chey|cheyi|cheyyu)?$").find(t)
            ?: return null
        var name = m.groupValues[1].trim().removeSuffix("యాప్").removeSuffix(" app").trim()
        if (name.isEmpty() || name.length > 40) return null
        PAGES.firstOrNull { (_, p) -> re("^($p)( page)?$").matches(name) }?.let { (page, _) ->
            return reply(say(te, "📂 Opening it.", "📂 తెరుస్తున్నాను."), "happy", act("open_page", "page" to page))
        }
        TE_APPS.entries.firstOrNull { name.contains(it.key) }?.let { name = it.value }
        return reply(say(te, "📲 Okay!", "📲 సరే!"), "happy", act("open_app", "name" to name))
    }

    // ---------------- sums ----------------
    private fun sums(t: String, te: Boolean): JSONObject? {
        var e = t.replace(re("^(what('?s| is)|calculate|how much is|tell me|compute)\\s+"), "").replace(re("\\s*(ఎంత|ఎంతో|enta|entha|=|\\?)\\s*$"), "").trim()
        re("^(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|శాతం)\\s*(?:of|లో)\\s*(\\d+(?:\\.\\d+)?)$").find(e)?.let { m ->
            val v = m.groupValues[1].toDouble() / 100 * m.groupValues[2].toDouble()
            return reply("🧮 ${m.groupValues[1]}% of ${m.groupValues[2]} = ${num(v)}")
        }
        e = e.replace(re("\\s*(plus|ప్లస్)\\s*"), "+").replace(re("\\s*(minus|మైనస్)\\s*"), "-")
            .replace(re("\\s*(multiplied by|times|into|ఇంటు|x|×)\\s*"), "*").replace(re("\\s*(divided by|by|÷|భాగించు)\\s*"), "/")
        if (!Regex("^[\\d\\s.+\\-*/()]+$").matches(e) || !Regex("\\d\\s*[+\\-*/]\\s*[\\d(]").containsMatchIn(e)) return null
        if (Regex("^\\+?\\d[\\d\\s-]{8,}$").matches(e)) return null        // a phone number, not a sum
        val v = try { Calc(e.replace(" ", "")).run() } catch (ex: Exception) { return null }
        if (v.isNaN() || v.isInfinite()) return reply(say(te, "🧮 That can't be worked out (dividing by zero?).", "🧮 దీన్ని లెక్కించలేను (సున్నాతో భాగించారా?)."), "confused")
        return reply("🧮 ${e.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ").replace(Regex("(?<=\\d)-"), " - ")} = ${num(v)}")
    }

    private fun num(v: Double): String = if (abs(v - v.roundToLong()) < 1e-9 && abs(v) < 1e15) v.roundToLong().toString()
        else "%.4f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')

    /** + - * / and brackets, the usual order. */
    private class Calc(val s: String) {
        var i = 0
        fun run(): Double { val v = sum(); if (i != s.length) throw IllegalArgumentException(); return v }
        fun sum(): Double { var v = product(); while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = product(); v = if (op == '+') v + r else v - r }; return v }
        fun product(): Double { var v = unary(); while (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i++]; val r = unary(); v = if (op == '*') v * r else v / r }; return v }
        fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else atom()
        fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = sum(); if (i >= s.length || s[i] != ')') throw IllegalArgumentException(); i++; return v }
            val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException()
            return s.substring(st, i).toDouble()
        }
    }

    // ---------------- is this message a fraud? ----------------
    private fun fraud(t: String, te: Boolean): JSONObject? {
        if (t.length < 40 || !re("scam|fraud|fake|cheat|is (this|it) (safe|real|true)|మోసం|ఫ్రాడ్|నకిలీ|ఫేక్|నిజమా|సేఫ్ ?(ఆ|నా)").containsMatchIn(t)) return null
        val (score, hints) = ScamCheck.quick(t)
        if (score == 0) return reply(say(te,
            "🛡️ I found no warning signs on the phone, but I can't do the full check without my AI brain. Until you're sure: don't click links, don't share OTP or PIN, don't pay.",
            "🛡️ ఫోన్‌లో ప్రమాద సూచనలు ఏమీ కనిపించలేదు, కానీ AI లేకుండా పూర్తిగా చెక్ చేయలేను. ఖచ్చితంగా తెలిసే వరకు: లింక్ నొక్కవద్దు, OTP/PIN చెప్పవద్దు, డబ్బు పంపవద్దు."), "thinking")
        val v = ScamCheck.offlineVerdict(te, score, hints)
        return reply(ScamCheck.label(v.level) + "\n" + v.title + "\n" + (v.reasons + v.advice).joinToString("\n") { "• $it" } +
            say(te, "\nIf you lost money, call 1930 at once.", "\nడబ్బు పోయిందా? వెంటనే 1930కి కాల్ చేయండి."), if (v.level == "danger") "scared" else "thinking")
    }

    // ---------------- a little chat ----------------
    private fun chat(t: String, te: Boolean): JSONObject? = when {
        re("^(good ?night|gn)$|శుభ ?రాత్రి|గుడ్ ?నైట్").containsMatchIn(t) -> reply(say(te, "🌙 Good night! Sleep well.", "🌙 శుభ రాత్రి! హాయిగా నిద్రపోండి."), "sleepy")
        re("^(hi+|hello+|hey|hai|namaste|namaskaram|good (morning|afternoon|evening))$NE|^(నమస్తే|నమస్కారం|హాయ్|హలో|శుభోదయం)").containsMatchIn(t) ->
            reply(say(te, "🐾 Hello! I'm here. Ask me to set a reminder, call family, open an app or tell the time.",
                "🐾 నమస్తే! నేను ఇక్కడే ఉన్నాను. రిమైండర్, కాల్, యాప్ ఓపెన్, సమయం — ఏదైనా అడగండి."), "excited")
        re("thank|thanks|^tq$|^ty$|ధన్యవాదాలు|థాంక్స్|థ్యాంక్స్|థాంక్యూ").containsMatchIn(t) -> reply(say(te, "😊 You're welcome!", "😊 పర్వాలేదు!"), "love")
        re("how are (you|u)|ela unnav|bagunnava|ఎలా ఉన్నావ|బాగున్నావా").containsMatchIn(t) -> reply(say(te, "🐾 I'm fine, thank you! How are you?", "🐾 నేను బాగున్నాను! మీరు ఎలా ఉన్నారు?"), "happy")
        re("who are you|what('?s| is) your name|నువ్వు ఎవరు|నీ పేరు").containsMatchIn(t) -> reply(say(te, "🐾 I'm your BigD AI pet, your helper on this phone.", "🐾 నేను మీ BigD AI పెట్‌ని, ఈ ఫోన్‌లో మీ సహాయకుడిని."), "proud")
        re("i love you|love you|ఐ లవ్ యూ|నువ్వంటే ఇష్టం").containsMatchIn(t) -> reply("❤️ " + say(te, "Love you too!", "నాకూ మీరంటే చాలా ఇష్టం!"), "love")
        re("^(help|what can you do|what all can you do)$|ఏం చేయగలవు|ఏమి చేయగలవు|^సహాయం$").containsMatchIn(t) ->
            reply(say(te, "🐾 Right now I can: set reminders, alarms and timers, call your family, open apps, add to and read your list, tell the time and date, do sums, check a message for fraud and show emergency numbers.",
                "🐾 ఇప్పుడు నేను ఇవి చేయగలను: రిమైండర్, అలారం, టైమర్, కుటుంబానికి కాల్, యాప్ ఓపెన్, లిస్ట్‌లో రాయడం/చదవడం, సమయం, తేదీ, లెక్కలు, మెసేజ్ మోసమా అని చెక్, అత్యవసర నంబర్లు."), "happy")
        else -> null
    }
}
