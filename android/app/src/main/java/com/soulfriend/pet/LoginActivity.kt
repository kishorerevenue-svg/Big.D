package com.soulfriend.pet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * The first screen, like WhatsApp: everyone logs in with their mobile number (or email) and a password, so their Habit
 * account (profile, posts, chats) stays theirs on a new phone too.
 *  - A number already on Habit: enter its password (forgot it? the backup code from Media › 👤 works too).
 *  - A new number: your name and a new password. A profile already on this phone keeps everything; the login is added.
 *  - Someone who already set a login (Media › 👤) goes straight in.
 * The number is never shown to anyone, and the password is kept only scrambled (scrypt) on the server.
 * (An SMS code comes with the Firebase setup.)
 */
class LoginActivity : Activity() {
    private lateinit var store: Store
    private lateinit var ui: Ui
    private lateinit var box: LinearLayout
    private var step = "check"         // check, phone, password, create
    private var account = ""            // the number or email typed
    private var email = false
    private var deviceName = ""
    private var busy = false
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = ui.dp(v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this)
        if (store.lang != "te" && store.lang != "en") store.lang = if (Locale.getDefault().language == "te") "te" else "en"
        window.statusBarColor = Ui.BG
        if (Build.VERSION.SDK_INT >= 23) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(28), dp(24), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); isFillViewport = true; addView(box) })
        render()
        // already logged in on this phone (from Media › 👤)? Then straight in.
        Thread {
            val r = ChatSync.api(store, "GET", "/api/social/me", timeoutMs = 15_000)
            runOnUiThread {
                if (r.ok && r.json.optJSONObject("login") != null) { done(r.json); return@runOnUiThread }
                deviceName = r.json.optJSONObject("profile")?.optString("name") ?: ""
                step = "phone"; render()
                if (!r.ok && r.code == 0) say(t("No internet. Connect to the internet to start Habit.", "ఇంటర్నెట్ లేదు. Habit మొదలుపెట్టడానికి ఇంటర్నెట్ ఆన్ చేయండి."), false)
            }
        }.start()
    }

    private var msg: TextView? = null
    private fun say(s: String, good: Boolean) { msg?.apply { text = s; setTextColor(if (good) Ui.GOOD else Ui.DANGER); visibility = View.VISIBLE } }

    private fun render() {
        box.removeAllViews()
        box.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.habit_logo); contentDescription = "Habit AI" },
            LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })
        box.addView(ui.text("Habit AI", 30f, Ui.TEAL_DARK, bold = true).apply { gravity = Gravity.CENTER })
        box.addView(ui.text(t("Your friend in Telugu and English", "తెలుగు, ఇంగ్లీష్‌లో మీ స్నేహితుడు"), 15f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(14)) })
        // the language
        val langs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((code, label) in listOf("te" to "తెలుగు", "en" to "English")) langs.addView(TextView(this).apply {
            text = label; textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(18), dp(8), dp(18), dp(8))
            val on = store.lang == code
            setTextColor(if (on) Color.WHITE else Ui.TEAL_DARK); background = ui.round(if (on) Ui.TEAL else Ui.TEAL_SOFT, 18)
            setOnClickListener { store.lang = code; render() }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4); marginEnd = dp(4) })
        box.addView(langs)
        val card = ui.card().apply { orientation = LinearLayout.VERTICAL }
        box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        msg = ui.text("", 14f, Ui.DANGER).apply { visibility = View.GONE; setPadding(0, dp(10), 0, 0) }
        when (step) {
            "check" -> card.addView(ui.text(t("One moment…", "ఒక్క క్షణం…"), 16f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20)) })
            "phone" -> phoneStep(card)
            "password" -> passwordStep(card)
            else -> createStep(card)
        }
        card.addView(msg)
        box.addView(ui.text(t("🔒 Your number is never shown to anyone. Your password is kept scrambled; Habit can't read it.",
            "🔒 మీ నంబర్ ఎవరికీ కనిపించదు. మీ పాస్‌వర్డ్ గుప్తంగా ఉంటుంది; Habit కూడా చదవలేదు."), 12.5f, Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(16), dp(8), 0) })
    }

    private fun field(hint: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint; textSize = 18f; inputType = type; setSingleLine(true); setTextColor(Ui.INK)
        setPadding(dp(14), dp(12), dp(14), dp(12)); background = ui.round(Color.WHITE, 12, Ui.LINE)
    }
    private fun bigButton(label: String, onClick: () -> Unit) = ui.button(label, "primary") { if (!busy) onClick() }

    // ---------- 1. the mobile number ----------
    private fun phoneStep(card: LinearLayout) {
        card.addView(ui.text(if (email) t("Your email", "మీ ఈమెయిల్") else t("📱 Your mobile number", "📱 మీ మొబైల్ నంబర్"), 18f, Ui.INK, bold = true))
        card.addView(ui.text(t("To keep your Habit account yours, on any phone.", "మీ Habit ఖాతా ఏ ఫోన్‌లోనైనా మీదే ఉండాలని."), 13.5f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(10)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!email) row.addView(ui.text("🇮🇳 +91", 18f, Ui.INK, bold = true).apply { setPadding(dp(4), 0, dp(10), 0) })
        val input = field(if (email) "name@gmail.com" else "98480 12345", if (email) InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_PHONE)
        input.setText(account)
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        card.addView(bigButton(t("Next", "తర్వాత")) { check(input.text.toString()) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        card.addView(ui.text(if (email) t("Use my mobile number instead", "బదులుగా నా మొబైల్ నంబర్") else t("No mobile number? Use email", "మొబైల్ నంబర్ లేదా? ఈమెయిల్ వాడండి"), 14f, Ui.TEAL, bold = true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0); setOnClickListener { email = !email; account = ""; render() } })
        input.requestFocus()
    }
    private fun check(raw: String) {
        val v = raw.trim()
        if (!email && v.filter { it.isDigit() }.let { it.length != 10 && !(it.length == 12 && it.startsWith("91")) }) { say(t("Type your 10-digit mobile number.", "మీ 10 అంకెల మొబైల్ నంబర్ టైప్ చేయండి."), false); return }
        if (email && !v.contains("@")) { say(t("Type your email address.", "మీ ఈమెయిల్ టైప్ చేయండి."), false); return }
        account = v; busy = true; say(t("Checking…", "చూస్తున్నాం…"), true)
        Thread {
            val r = ChatSync.api(store, "POST", "/api/social/account", JSONObject().put("id", v).toString())
            runOnUiThread {
                busy = false
                if (!r.ok) { say(r.error("Couldn't check. Please try again."), false); return@runOnUiThread }
                deviceName = r.json.optString("name").ifBlank { deviceName }
                step = if (r.json.optBoolean("exists")) "password" else "create"
                render()
            }
        }.start()
    }

    // ---------- 2a. a number already on Habit: its password ----------
    private fun passwordStep(card: LinearLayout) {
        card.addView(ui.text(t("Welcome back! 👋", "మళ్ళీ స్వాగతం! 👋"), 20f, Ui.INK, bold = true))
        card.addView(ui.text(t("$account already has a Habit account. Enter its password.", "$account కి ఇప్పటికే Habit ఖాతా ఉంది. దాని పాస్‌వర్డ్ ఇవ్వండి."), 14f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(10)) })
        val pw = field(t("Password", "పాస్‌వర్డ్"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        card.addView(pw)
        card.addView(showToggle(pw))
        card.addView(bigButton(t("Log in", "లాగిన్")) {
            busy = true; say(t("Logging in…", "లాగిన్ అవుతోంది…"), true)
            Thread {
                val r = ChatSync.api(store, "POST", "/api/social/login", JSONObject().put("id", account).put("password", pw.text.toString()).toString())
                runOnUiThread { busy = false; if (r.ok) done(r.json) else say(r.error("Couldn't log in."), false) }
            }.start()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        card.addView(link(t("Forgot the password?", "పాస్‌వర్డ్ మర్చిపోయారా?")) { forgot() })
        card.addView(link(t("← A different number", "← వేరే నంబర్")) { step = "phone"; render() })
        pw.requestFocus()
    }
    private fun forgot() {
        val code = field(t("Backup code, e.g. 3F9A1C27B0", "బ్యాకప్ కోడ్, ఉదా: 3F9A1C27B0"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        wrap.addView(ui.text(t("Your backup code is in Habit on your old phone: Media › 👤 your profile. (Getting a code by SMS is coming soon.)",
            "మీ బ్యాకప్ కోడ్ పాత ఫోన్‌లోని Habit లో ఉంటుంది: మీడియా › 👤 మీ ప్రొఫైల్. (SMS కోడ్ త్వరలో వస్తుంది.)"), 13.5f, Ui.MUTED))
        wrap.addView(code, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        AlertDialog.Builder(this).setTitle(t("Use your backup code", "మీ బ్యాకప్ కోడ్ వాడండి")).setView(wrap).setPositiveButton(t("Continue", "కొనసాగించు")) { _, _ ->
            busy = true; say(t("Checking…", "చూస్తున్నాం…"), true)
            Thread {
                val r = ChatSync.api(store, "POST", "/api/social/restore", JSONObject().put("code", code.text.toString()).toString())
                runOnUiThread {
                    busy = false
                    if (!r.ok) { say(r.error("That backup code is not right."), false); return@runOnUiThread }
                    // in with the code: now a new password, so next time the number and password work
                    newPassword(r.json)
                }
            }.start()
        }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun newPassword(me: JSONObject) {
        val pw = field(t("New password (6 or more)", "కొత్త పాస్‌వర్డ్ (6 లేదా ఎక్కువ)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0); addView(pw) }
        AlertDialog.Builder(this).setTitle(t("Set a new password", "కొత్త పాస్‌వర్డ్ పెట్టండి")).setView(wrap).setCancelable(false).setPositiveButton(t("Save", "సేవ్")) { _, _ ->
            Thread {
                val r = ChatSync.api(store, "POST", "/api/social/login-set", JSONObject().put("id", account).put("password", pw.text.toString()).toString())
                runOnUiThread { if (r.ok) done(me.put("login", r.json.optJSONObject("login"))) else { say(r.error("Couldn't save the password."), false); newPassword(me) } }
            }.start()
        }.show()
    }

    // ---------- 2b. a new number: name and password ----------
    private fun createStep(card: LinearLayout) {
        card.addView(ui.text(t("Make your Habit account", "మీ Habit ఖాతా తయారుచేయండి"), 20f, Ui.INK, bold = true))
        card.addView(ui.text(account, 14f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(10)) })
        val name = field(t("Your name", "మీ పేరు"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).apply { setText(deviceName) }
        val pw = field(t("Make a password (6 or more)", "పాస్‌వర్డ్ పెట్టండి (6 లేదా ఎక్కువ)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val pw2 = field(t("The password again", "పాస్‌వర్డ్ మళ్ళీ"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        card.addView(name)
        card.addView(pw, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(pw2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(showToggle(pw, pw2))
        card.addView(ui.text(t("Remember this password: you need it to use your account on a new phone.", "ఈ పాస్‌వర్డ్ గుర్తుంచుకోండి: కొత్త ఫోన్‌లో మీ ఖాతా వాడడానికి కావాలి."), 13f, Ui.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        card.addView(bigButton(t("Make my account", "నా ఖాతా తయారుచేయండి")) {
            if (name.text.toString().trim().length < 2) { say(t("Write your name.", "మీ పేరు రాయండి."), false); return@bigButton }
            if (pw.text.toString().length < 6) { say(t("The password needs 6 or more letters or numbers.", "పాస్‌వర్డ్‌కి 6 లేదా ఎక్కువ అక్షరాలు కావాలి."), false); return@bigButton }
            if (pw.text.toString() != pw2.text.toString()) { say(t("The two passwords are not the same.", "రెండు పాస్‌వర్డ్‌లు ఒకటి కాదు."), false); return@bigButton }
            busy = true; say(t("Making your account…", "మీ ఖాతా తయారవుతోంది…"), true)
            Thread {
                val r = ChatSync.api(store, "POST", "/api/social/signup", JSONObject().put("id", account).put("password", pw.text.toString()).put("name", name.text.toString().trim()).toString())
                runOnUiThread {
                    busy = false
                    if (r.ok) done(r.json)
                    else if (r.json.optBoolean("exists")) { step = "password"; render(); say(r.error(""), false) }
                    else say(r.error("Couldn't make the account."), false)
                }
            }.start()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        card.addView(link(t("← A different number", "← వేరే నంబర్")) { step = "phone"; render() })
    }

    private fun showToggle(vararg fields: EditText) = TextView(this).apply {
        text = t("👁 Show password", "👁 పాస్‌వర్డ్ చూపించు"); textSize = 13.5f; setTextColor(Ui.TEAL); setPadding(0, dp(8), 0, 0)
        var shown = false
        setOnClickListener {
            shown = !shown
            for (f in fields) { f.inputType = InputType.TYPE_CLASS_TEXT or (if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD); f.setSelection(f.text.length) }
            text = if (shown) t("🙈 Hide password", "🙈 పాస్‌వర్డ్ దాచు") else t("👁 Show password", "👁 పాస్‌వర్డ్ చూపించు")
        }
    }
    private fun link(label: String, onClick: () -> Unit) = ui.text(label, 14f, Ui.TEAL, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0); setOnClickListener { if (!busy) onClick() } }

    /** Logged in: remember it, and into the app. */
    private fun done(me: JSONObject) {
        store.loggedIn = true
        store.loginShown = me.optJSONObject("login")?.optString("shown") ?: ""
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
