package com.soulfriend.pet

import android.app.AlertDialog
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tasks › 🔥 Trending, like Twitter: what India is searching today (from @HabitTrending, explained in one or two
 * plain lines, 🤖 AI-made), and under it short posts from people with a Habit profile ("What's happening?", 280 letters),
 * each checked before it shows. ❤️ like, 🚩 report (3 reports hide a post), 🗑 delete your own.
 * Everything sits in its own holder, so taps and new data redraw only this part (not the box you may be typing in).
 */
class Trending(private val m: MainActivity) {
    private val store get() = m.store
    private val ui get() = m.ui
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = m.dp(v)
    private fun headers() = if (store.teamKey.isNotBlank()) mapOf("X-Admin-Key" to store.teamKey) else emptyMap()

    private var topics: JSONArray? = null
    private var posts: JSONArray? = null
    private var more = false
    private var canPost = false
    private var loading = false
    private var loadedAt = 0L
    private var error = ""
    private var draft = ""
    private var sending = false
    private var holder: LinearLayout? = null

    fun build() {
        m.add(composer(), 14)
        val h = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL }
        holder = h
        m.add(h, 4)
        fill(h)
        if (!loading && (topics == null || System.currentTimeMillis() - loadedAt > 120_000)) load(false)
    }
    private fun redraw() { holder?.takeIf { it.isAttachedToWindow && m.tab == "tasks" }?.let { fill(it) } }

    private fun composer(): View = ui.card().apply {
        addView(ui.text("🔥 " + t("What's happening?", "ఏం జరుగుతోంది?"), 16f, Ui.INK, bold = true))
        val count = ui.text("${draft.length}/280", 12f, Ui.MUTED)
        val box = ui.input(t("Say something about today's news, a match, a film, the weather…", "ఈరోజు వార్త, మ్యాచ్, సినిమా, వాతావరణం గురించి ఏదైనా చెప్పండి…"), draft, multi = true).apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(280))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { draft = s?.toString() ?: ""; count.text = "${draft.length}/280" }
            })
        }
        addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
            addView(ui.text(t("Kind words only · checked before it shows", "మంచి మాటలే · చూపించే ముందు చెక్ చేస్తాం"), 11.5f, Ui.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            addView(count, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
            addView(ui.button(if (sending) t("Posting…", "పోస్ట్ అవుతోంది…") else t("Post", "పోస్ట్"), "primary") { if (!sending) post(box) })
        })
    }

    private fun post(box: TextView) {
        val text = draft.trim()
        if (text.length < 2) { m.toast(t("Write a little first.", "ముందు కొంచెం రాయండి.")); return }
        if (!ChatSync.isSetUp(m)) { m.toast(t("Make your Habit profile first: Chats › Start Habit Chat.", "ముందు Habit ప్రొఫైల్ చేయండి: చాట్‌లు › Habit చాట్ మొదలుపెట్టండి.")); return }
        sending = true; m.render()
        m.io.execute {
            val r = Net.post(store, "/api/trending/post", JSONObject().put("text", text), headers(), 45_000)
            m.runOnUiThread {
                sending = false
                if (r.ok) {
                    draft = ""
                    val list = JSONArray().put(r.json.optJSONObject("post"))
                    posts?.let { for (i in 0 until it.length()) list.put(it.get(i)) }
                    posts = list
                    m.toast(t("Posted ✓", "పోస్ట్ అయింది ✓"))
                } else m.toast(r.error(t("Couldn't post. Try again.", "పోస్ట్ కాలేదు. మళ్ళీ ప్రయత్నించండి.")))
                if (m.tab == "tasks") m.render()
            }
        }
    }

    private fun load(append: Boolean) {
        if (loading) return
        loading = true; error = ""
        val from = if (append) posts?.length() ?: 0 else 0
        m.io.execute {
            val r = Net.get(store, "/api/trending?from=$from", headers())
            m.runOnUiThread {
                loading = false; loadedAt = System.currentTimeMillis()
                if (r.ok) {
                    topics = r.json.optJSONArray("topics") ?: JSONArray()
                    val got = r.json.optJSONArray("posts") ?: JSONArray()
                    val list = if (append) posts ?: JSONArray() else JSONArray()
                    for (i in 0 until got.length()) list.put(got.get(i))
                    posts = list; more = r.json.optBoolean("more"); canPost = r.json.optBoolean("canPost")
                } else error = r.error(t("Couldn't load. Check your internet.", "లోడ్ కాలేదు. ఇంటర్నెట్ చూడండి."))
                redraw()
            }
        }
    }

    private fun fill(h: LinearLayout) {
        h.removeAllViews()
        fun put(v: View, top: Int = 10) = h.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        if (error.isNotBlank() && topics == null) {
            put(ui.text(error, 14f, Ui.MUTED), 14)
            put(ui.button(t("Try again", "మళ్ళీ ప్రయత్నించు"), "soft") { load(false) }, 8); return
        }
        val tp = topics
        if (tp == null) { put(ui.text(t("Loading…", "లోడ్ అవుతోంది…"), 14f, Ui.MUTED), 16); return }
        put(ui.label(t("Trending in India now", "ఇప్పుడు ఇండియాలో ట్రెండింగ్")), 18)
        if (tp.length() == 0) put(ui.text(t("Today's trends come in a little while.", "ఈరోజు ట్రెండ్స్ కాసేపట్లో వస్తాయి."), 14f, Ui.MUTED), 8)
        for (i in 0 until tp.length()) put(topicCard(tp.getJSONObject(i), i + 1), 8)
        val ps = posts ?: JSONArray()
        put(ui.label(t("What people say", "జనాలు ఏమంటున్నారు")), 22)
        if (ps.length() == 0) put(ui.text(t("No posts yet. Be the first!", "ఇంకా పోస్ట్‌లు లేవు. మీరే మొదటివాళ్ళు అవ్వండి!"), 14f, Ui.MUTED), 8)
        for (i in 0 until ps.length()) put(postCard(ps.getJSONObject(i)), 8)
        if (more) put(ui.button(if (loading) t("Loading…", "లోడ్ అవుతోంది…") else t("Show more", "ఇంకా చూపించు"), "ghost") { load(true) }, 10)
    }

    private fun topicCard(o: JSONObject, n: Int): View = ui.card().apply {
        addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ui.text("🔥", 18f, Ui.INK).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(34), -2))
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text("Habit Trending  ✔", 14f, Ui.INK, bold = true))
                addView(ui.text("@HabitTrending · " + ChatStatus.ago(o.optLong("at"), te) + " · #$n", 12f, Ui.MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(ui.text(o.optString("emoji", "🔥") + " " + o.optString("title"), 16f, Ui.INK).apply { typeface = Fonts.heavy(context); setPadding(0, dp(8), 0, 0) })
        addView(ui.text(if (te) o.optString("te") else o.optString("en"), 14.5f, Ui.INK).apply { setLineSpacing(0f, 1.12f); setPadding(0, dp(4), 0, 0) })
        addView(actions(o, own = false, ai = true))
    }

    private fun postCard(o: JSONObject): View = ui.card().apply {
        addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(o.optString("emoji", "🐾"), 18f, Ui.INK).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(34), -2))
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text(o.optString("name"), 14f, Ui.INK, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(ui.text((o.optString("handle").let { if (it.isNotBlank()) "@$it · " else "" }) + ChatStatus.ago(o.optLong("at"), te) +
                    (if (o.optBoolean("hidden")) " · 🙈 hidden" else ""), 12f, Ui.MUTED))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(ui.text(o.optString("text"), 15f, Ui.INK).apply { setLineSpacing(0f, 1.12f); setTextIsSelectable(true); setPadding(0, dp(8), 0, 0) })
        addView(actions(o, own = o.optBoolean("mine"), ai = false))
    }

    private fun actions(o: JSONObject, own: Boolean, ai: Boolean): View = LinearLayout(m).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
        val liked = o.optBoolean("liked")
        addView(ui.text((if (liked) "❤️ " else "🤍 ") + o.optInt("likes"), 13.5f, Ui.INK, bold = liked).apply {
            setPadding(dp(2), dp(6), dp(14), dp(6)); setOnClickListener { like(o) }
        })
        if (ai) addView(ui.text("🤖 AI-made", 12f, Ui.MUTED))
        addView(View(m), LinearLayout.LayoutParams(0, 1, 1f))
        if (own) addView(ui.text("🗑 " + t("Delete", "తొలగించు"), 13f, Ui.MUTED).apply { setPadding(dp(10), dp(6), dp(2), dp(6)); setOnClickListener { delete(o) } })
        else if (!ai) addView(ui.text("🚩", 15f, Ui.MUTED).apply { setPadding(dp(10), dp(6), dp(2), dp(6)); contentDescription = t("Report", "రిపోర్ట్"); setOnClickListener { report(o) } })
    }

    private fun like(o: JSONObject) {
        if (!ChatSync.isSetUp(m)) { m.toast(t("Make your Habit profile first: Chats › Start Habit Chat.", "ముందు Habit ప్రొఫైల్ చేయండి: చాట్‌లు › Habit చాట్ మొదలుపెట్టండి.")); return }
        val was = o.optBoolean("liked")
        o.put("liked", !was).put("likes", (o.optInt("likes") + if (was) -1 else 1).coerceAtLeast(0)); redraw()
        m.io.execute {
            val r = Net.post(store, "/api/trending/like", JSONObject().put("id", o.optString("id")), headers(), 20_000)
            m.runOnUiThread { if (r.ok) { o.put("likes", r.json.optInt("likes")).put("liked", r.json.optBoolean("liked")); redraw() } }
        }
    }
    private fun report(o: JSONObject) {
        AlertDialog.Builder(m).setTitle(t("Report this post?", "ఈ పోస్ట్ రిపోర్ట్ చేయాలా?"))
            .setMessage(t("If it is rude, false or unsafe. After 3 reports it is hidden.", "అసభ్యంగా, తప్పుగా, ప్రమాదంగా ఉంటే. 3 రిపోర్ట్‌ల తర్వాత దాచేస్తాం."))
            .setPositiveButton(t("Report", "రిపోర్ట్")) { _, _ ->
                m.io.execute {
                    val r = Net.post(store, "/api/trending/report", JSONObject().put("id", o.optString("id")), headers(), 20_000)
                    m.runOnUiThread { m.toast(if (r.ok) t("Thanks, reported.", "ధన్యవాదాలు, రిపోర్ట్ అయింది.") else r.error(t("Couldn't report.", "రిపోర్ట్ కాలేదు."))) }
                }
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
    private fun delete(o: JSONObject) {
        AlertDialog.Builder(m).setTitle(t("Delete your post?", "మీ పోస్ట్ తొలగించాలా?"))
            .setPositiveButton(t("Delete", "తొలగించు")) { _, _ ->
                m.io.execute {
                    val r = Net.post(store, "/api/trending/delete", JSONObject().put("id", o.optString("id")), headers(), 20_000)
                    m.runOnUiThread {
                        if (r.ok) { posts = posts?.let { a -> JSONArray().also { n -> for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") != o.optString("id")) n.put(a.get(i)) } }; redraw() }
                        else m.toast(r.error(t("Couldn't delete.", "తొలగించలేకపోయాను.")))
                    }
                }
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
}
