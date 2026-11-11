package com.kishore.bigd.brain

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Habit Brain: download the tiny brain, see whether it is ready, and try it.
 * Habit AI then uses it by itself when the phone has no internet. Nothing asked here leaves the phone.
 */
class BrainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var col: LinearLayout
    private val ink = Color.parseColor("#1C2140"); private val muted = Color.parseColor("#5F6688"); private val teal = Color.parseColor("#0F766E")
    private var te = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("server")?.takeIf { it.startsWith("https://") }?.let { Model.prefs(this).edit().putString("server", it).apply() }
        te = intent.getStringExtra("lang") == "te"
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#EEF1FA")); addView(col) })
        window.statusBarColor = Color.parseColor("#EEF1FA")
    }

    override fun onResume() { super.onResume(); Model.finish(this); show() }
    override fun onPause() { super.onPause(); h.removeCallbacksAndMessages(null) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(0f, 1.15f)
    }
    private fun button(s: String, primary: Boolean = true, click: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 15f; setTextColor(if (primary) Color.WHITE else ink)
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (primary) teal else Color.WHITE); if (!primary) setStroke(dp(1), Color.parseColor("#D8DDF0")) }
        setPadding(dp(18), dp(10), dp(18), dp(10)); setOnClickListener { click() }
    }
    private fun add(v: View, top: Int = 12) = col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#D8DDF0")) }
    }

    private fun show() {
        h.removeCallbacksAndMessages(null)
        col.removeAllViews()
        add(text("🧠 Habit Brain", 26f, bold = true), 0)
        add(text(if (te) "ఇంటర్నెట్ అస్సలు లేనప్పుడు కూడా మీ Habit పెట్ మాట్లాడేలా, ఈ ఫోన్‌లోనే పనిచేసే చిన్న మెదడు. మీరు అడిగేది ఫోన్ దాటి బయటికి వెళ్ళదు."
                  else "A tiny brain that works on this phone, so your Habit pet can still talk when there is no internet at all. Nothing you ask it leaves the phone.", 15f, muted), 6)

        val ram = Model.ramGb(this)
        add(text(if (ram >= 6) "📱 This phone: $ram GB memory ✓" else "⚠️ This phone has $ram GB memory. The tiny brain needs about 6 GB; it may be slow or stop.", 14f, if (ram >= 6) teal else Color.parseColor("#B45309")), 14)

        val box = card()
        when {
            Model.ready(this) -> {
                box.addView(text(if (te) "✓ చిన్న మెదడు సిద్ధంగా ఉంది. ఇంటర్నెట్ లేనప్పుడు Habit AI దీన్ని తానే వాడుతుంది." else "✓ The tiny brain is ready. Habit AI uses it by itself when there is no internet.", 16f, teal, bold = true))
                val q = EditText(this).apply { hint = if (te) "ఏదైనా అడగండి…" else "Ask it something…"; textSize = 15f }
                box.addView(q, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                val answer = text("", 15f)
                val ask = button(if (te) "అడుగు" else "Ask") {
                    val s = q.text.toString().trim(); if (s.isEmpty()) return@button
                    answer.text = if (te) "ఆలోచిస్తున్నాను… (మొదటిసారి కొన్ని సెకన్లు పడుతుంది)" else "Thinking… (the first time takes a few seconds)"
                    test(s) { r -> answer.text = r }
                }
                box.addView(ask, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                box.addView(answer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                box.addView(button(if (te) "🗑 మెదడును తొలగించు (2.6 GB ఖాళీ అవుతుంది)" else "🗑 Delete the brain (frees 2.6 GB)", false) { Model.delete(this); show() },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            }
            Model.downloading(this) -> {
                val p = Model.progress(this)
                val done = (p?.first ?: 0L) / 1_000_000; val all = (p?.second ?: 0L).takeIf { it > 0 }?.div(1_000_000) ?: Model.SIZE_MB.toLong()
                box.addView(text("⬇ " + (if (te) "డౌన్‌లోడ్ అవుతోంది" else "Downloading") + "… ${"%.1f".format(done / 1000.0)} GB / ${"%.1f".format(all / 1000.0)} GB", 16f, bold = true))
                box.addView(text(if (te) "మీరు ఈ యాప్ మూసేసినా డౌన్‌లోడ్ కొనసాగుతుంది." else "It keeps going if you close this app. It may wait for Wi-Fi.", 13.5f, muted))
                box.addView(button(if (te) "ఆపు" else "Cancel", false) { Model.cancel(this); show() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
                h.postDelayed({ Model.finish(this); show() }, 1500)
            }
            else -> {
                Model.prefs(this).getString("problem", "")?.takeIf { it.isNotBlank() }?.let { box.addView(text("⚠️ $it", 14f, Color.parseColor("#B45309"))) }
                box.addView(text(if (te) "మెదడును డౌన్‌లోడ్ చేయండి (సుమారు 2.6 GB, ఒక్కసారే). దీనికి ఫోన్‌లో 3 GB ఖాళీ కావాలి." else "Download the brain (about 2.6 GB, only once). It needs 3 GB of free space on the phone.", 15f))
                val mobile = CheckBox(this).apply { text = if (te) "మొబైల్ డేటాతో కూడా (Wi-Fi లేకపోతే)" else "Use mobile data too (not only Wi-Fi)"; textSize = 14f }
                box.addView(mobile, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                box.addView(button(if (te) "⬇ మెదడును డౌన్‌లోడ్ చెయ్యి" else "⬇ Download the brain") {
                    val free = getExternalFilesDir(null)?.usableSpace ?: 0L
                    if (free < 3_000_000_000L) { box.addView(text(if (te) "ఫోన్‌లో ఖాళీ సరిపోదు. కొన్ని ఫోటోలు/వీడియోలు తొలగించండి." else "Not enough free space. Free up some photos or videos first.", 14f, Color.parseColor("#B45309"))); return@button }
                    Thread { val url = Model.url(this); h.post { Model.start(this, url, mobile.isChecked); show() } }.start()
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            }
        }
        add(box, 16)
        add(text("Google's Gemma 4 (E2B) model, Apache 2.0 licence, running with Google's LiteRT-LM. " +
            "Answers from a small brain are simpler than Habit's big AI; check important things.", 12.5f, muted), 18)
    }

    /** Tries the brain through Habit's own door (the same way Habit AI asks it). */
    private fun test(q: String, done: (String) -> Unit) {
        val reply = Messenger(Handler(Looper.getMainLooper()) { m ->
            if (m.what == 2) done(m.data.getString("text") ?: ("😕 " + (m.data.getString("error") ?: "No answer.")))
            true
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) {
                try {
                    Messenger(b).send(Message.obtain(null, 1).apply {
                        data = Bundle().apply { putString("system", "You are Habit, a kind cartoon pet friend. Answer in the language of the question, in 1 to 4 short sentences."); putString("prompt", q) }
                        replyTo = reply
                    })
                } catch (e: Exception) { done("😕 " + e.message) }
                h.postDelayed({ try { unbindService(this) } catch (e: Exception) { } }, 120_000)
            }
            override fun onServiceDisconnected(n: ComponentName) {}
        }
        bindService(Intent(this, BrainService::class.java), conn, Context.BIND_AUTO_CREATE)
    }
}
