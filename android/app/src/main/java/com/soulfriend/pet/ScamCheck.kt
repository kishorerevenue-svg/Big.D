package com.soulfriend.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * Message safety check: 🟢 safe, 🟡 careful, 🔴 danger (fraud, cybercrime, threats).
 * First the phone looks for warning signs by itself (free, instant). Only messages with warning signs are
 * sent to the server for a careful AI check. If the server can't be reached, the phone's own result is used.
 */
object ScamCheck {
    data class Verdict(val level: String, val title: String, val reasons: List<String>, val advice: List<String>, val type: String = "", val offline: Boolean = false) {
        fun toJson(): JSONObject = JSONObject().put("level", level).put("title", title)
            .put("reasons", JSONArray(reasons)).put("advice", JSONArray(advice)).put("type", type).put("offline", offline)
        companion object {
            fun from(o: JSONObject) = Verdict(o.optString("level", "careful"), o.optString("title"), list(o.optJSONArray("reasons")), list(o.optJSONArray("advice")), o.optString("type"), o.optBoolean("offline"))
            private fun list(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        }
    }

    private class Rule(val score: Int, val hint: String, pattern: String) { val re = Regex(pattern, RegexOption.IGNORE_CASE) }

    private val RULES = listOf(
        Rule(3, "app file (.apk) link", "\\.apk\\b"),
        Rule(3, "remote-control app", "anydesk|teamviewer|quick ?support|rustdesk|screen ?shar"),
        Rule(3, "police, court or customs threat", "digital arrest|arrest warrant|\\bcbi\\b|narcotics|customs|cyber ?cell|money laundering|fedex|court notice|police (case|complaint|station)|courier.{0,30}(seized|drugs|illegal)|పోలీస్|అరెస్ట్"),
        Rule(3, "power cut threat", "(disconnect|cut ?off|కట్).{0,60}(tonight|today|9[.:]30|immediately|ఈ రోజు|ఈ రాత్రి)"),
        Rule(3, "blackmail", "video call.{0,30}(record|leak|viral)|nude|sextortion|private video"),
        Rule(2, "account block or KYC", "\\bkyc\\b|pan.{0,15}(update|link|expire)|(account|a/c|card|sim|wallet).{0,25}(block|suspend|freez|deactivat|closed|expire)|కేవైసీ"),
        Rule(2, "prize or lottery", "lottery|lucky draw|jackpot|you (have )?won|winner|\\bprize\\b|\\bkbc\\b|gift (card|voucher)|congratulations.{0,40}(won|selected)|లాటరీ|బహుమతి|గెలుచుకున్నారు"),
        Rule(2, "easy money or job offer", "work from home|part[ -]?time (job|work)|earn (rs\\.? ?|₹ ?)?\\d|daily (income|earning|salary)|per day.{0,15}(rs|₹)|(like|subscribe|review|rating).{0,25}(earn|paid|salary|income)|telegram task|double (your|the) money|crypto|bitcoin|guaranteed (return|profit)|trading (tips|group)|ఇంటి నుంచే"),
        Rule(2, "instant loan offer", "loan.{0,20}(approved|sanction|pre-?approved|instant)|instant loan"),
        Rule(2, "new number or emergency story", "(this is|it's|it is) my new number|new number.{0,20}(save|use)|(accident|hospital).{0,40}(money|send|urgent)|కొత్త నంబర్"),
        Rule(1, "asks for money", "send (me )?money|transfer (rs|₹|money)|pay (rs|₹|inr)|processing fee|registration fee|advance (fee|payment)|refund|cashback|upi pin|collect request|డబ్బు పంప|డబ్బులు పంప"),
        Rule(1, "pressure to act fast", "urgent|immediately|within \\d+ ?(hours?|hrs?|minutes?|mins?)|last (chance|warning|date)|today only|act now|expires? today|అత్యవసరం|వెంటనే"),
        Rule(1, "link", "https?://|www\\.|bit\\.ly|tinyurl|cutt\\.ly|rb\\.gy|is\\.gd|t\\.me/|wa\\.me/|goo\\.gl")
    )
    private val otpWords = Regex("\\botp\\b|\\bpin\\b|password|cvv|ఓటీపీ|ఓటిపి|పిన్", RegexOption.IGNORE_CASE)
    private val askWords = Regex("share|send|tell|forward|give|చెప్ప|పంప|షేర్", RegexOption.IGNORE_CASE)
    private val safeOtp = Regex("do ?n.?t share|never share|not share|ఎవరికీ|షేర్ చేయవద్దు|చెప్పవద్దు", RegexOption.IGNORE_CASE)

    /** Warning signs found on the phone: (score, hints). 0 = nothing suspicious. */
    fun quick(text: String, sender: String = ""): Pair<Int, List<String>> {
        if (text.trim().length < 12) return 0 to emptyList()
        var score = 0; val hints = mutableListOf<String>()
        for (r in RULES) if (r.re.containsMatchIn(text)) { score += r.score; hints.add(r.hint) }
        if (otpWords.containsMatchIn(text) && askWords.containsMatchIn(text) && !safeOtp.containsMatchIn(text)) { score += 3; hints.add("asks for OTP, PIN or password") }
        val s = sender.trim()
        if (Regex("^\\+(?!91)\\d[\\d\\s-]{6,}$").matches(s)) { score += 2; hints.add("foreign number") }
        else if (score > 0 && Regex("^[+\\d\\s-]{8,}$").matches(s)) { score += 1; hints.add("unknown number") }
        return score to hints
    }

    private fun te(store: Store) = store.lang == "te"

    fun safeVerdict(store: Store) = Verdict("safe",
        if (te(store)) "ప్రమాదం ఏమీ కనిపించలేదు" else "No warning signs found",
        emptyList(), listOf(if (te(store)) "అయినా తెలియని వాళ్ళకి డబ్బు, OTP ఇవ్వకండి." else "Still, never give money or OTPs to people you don't know."))

    /** The phone's own result, used when the server can't be reached. */
    fun offlineVerdict(store: Store, score: Int, hints: List<String>): Verdict {
        val danger = score >= 4
        val t = te(store)
        val advice = if (t) listOf("లింక్‌లు నొక్కవద్దు, ఫైల్స్ ఓపెన్ చేయవద్దు", "OTP, PIN, పాస్‌వర్డ్ ఎవరికీ చెప్పవద్దు", "డబ్బులు పంపవద్దు", "డబ్బు పోయిందా? వెంటనే 1930కి కాల్ చేయండి")
            else listOf("Don't click links or open files", "Never share OTP, PIN or passwords", "Don't pay or send money", "Lost money? Call 1930 right away")
        return Verdict(if (danger) "danger" else "careful",
            if (danger) (if (t) "ఇది మోసపూరిత మెసేజ్‌లా ఉంది" else "This looks like a fraud message") else (if (t) "ఈ మెసేజ్‌తో జాగ్రత్త" else "Be careful with this message"),
            hints.take(3).map { (if (t) "హెచ్చరిక: " else "Warning sign: ") + it }, if (danger) advice else advice.take(3), offline = true)
    }

    /** Full check. Blocking: call off the main thread. */
    fun checkBlocking(store: Store, text: String, sender: String, app: String): Verdict {
        val (score, hints) = quick(text, sender)
        if (score == 0) return safeVerdict(store)
        val body = JSONObject().put("text", Privacy.maybe(store, text).take(3000)).put("sender", sender.take(100)).put("app", app.take(40))
            .put("lang", store.lang).put("hints", JSONArray(hints))
        val r = Net.post(store, "/api/scan", body, timeoutMs = 30_000)
        return if (r.ok && r.json.has("level")) Verdict.from(r.json) else offlineVerdict(store, score, hints)
    }

    // ---- how it looks ----
    fun color(level: String) = when (level) { "danger" -> Color.parseColor("#C62828"); "careful" -> Color.parseColor("#B7791F"); "safe" -> Color.parseColor("#1D8A5B"); else -> Color.parseColor("#7A8A8C") }
    fun soft(level: String) = when (level) { "danger" -> Color.parseColor("#FDE2E1"); "careful" -> Color.parseColor("#FFF3D6"); "safe" -> Color.parseColor("#DFF3E8"); else -> Color.parseColor("#EEF2F2") }
    fun label(level: String) = when (level) { "danger" -> "🔴 Danger · ప్రమాదం"; "careful" -> "🟡 Be careful · జాగ్రత్త"; "safe" -> "🟢 Safe · సురక్షితం"; else -> "⚪ Checking…" }

    // ---- the list of checked messages (only on this phone) ----
    fun log(store: Store, id: String, app: String, sender: String, text: String, v: Verdict?, src: String) {
        val o = JSONObject().put("id", id).put("app", app).put("sender", sender).put("text", text.take(2000))
            .put("at", System.currentTimeMillis()).put("src", src).put("level", v?.level ?: "checking")
        if (v != null) o.put("v", v.toJson())
        store.putMsg(o)
    }

    /** Red alert: in the floating pet's window if it's on screen, otherwise as an urgent notification. */
    fun alert(ctx: Context, item: JSONObject) {
        if (PetService.running) {
            try { ctx.startService(Intent(ctx, PetService::class.java).setAction(PetService.ACTION_DANGER).putExtra("item", item.toString())); return } catch (e: Exception) { }
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("danger") == null)
            nm.createNotificationChannel(NotificationChannel("danger", "Danger alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Warnings about fraud and dangerous messages" })
        val v = Verdict.from(item.optJSONObject("v") ?: JSONObject())
        val open = PendingIntent.getActivity(ctx, 77, Intent(ctx, MainActivity::class.java).putExtra("tab", "messages").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, "danger").setSmallIcon(R.drawable.ic_paw)
            .setContentTitle("⚠️ Danger: message from ${item.optString("sender")}")
            .setContentText(v.title.ifBlank { "This looks like a fraud message. Don't click links or share OTP." })
            .setStyle(Notification.BigTextStyle().bigText(v.title + "\n" + v.advice.joinToString("\n") { "• $it" }))
            .setColor(color("danger")).setContentIntent(open).setAutoCancel(true).build()
        try { nm.notify(item.optString("id").hashCode(), n) } catch (e: SecurityException) { }
    }
}
