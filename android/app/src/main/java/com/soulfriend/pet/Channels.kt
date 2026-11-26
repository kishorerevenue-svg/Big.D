package com.soulfriend.pet

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Habit channels (Chats › Updates › Channels), like WhatsApp channels, made by the Habit team only:
 * 🛡 Fake message alerts (opens the fake messages page), 📰 Habit News, 🏛 Govt schemes, 🌾 Farmers' corner,
 * 💡 Daily tips and 🎬 Habit Videos. Posts written by AI carry "🤖 AI-made".
 * [list] is kept here so the Updates page can draw at once; [page] is one channel's posts (tab "channel").
 */
class Channels(private val m: MainActivity) {
    private val store get() = m.store
    private val ui get() = m.ui
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = m.dp(v)

    /** The channels (from the server), or null before the first load. */
    var list: JSONArray? = null
        private set
    private var listAt = 0L
    private var loadingList = false
    /** The open channel (tab "channel"). */
    var openId = ""
    private var posts: JSONArray? = null
    private var postsOf = ""
    private var error = ""

    /** Fetches the channel list (at most once a minute); [done] runs on the main thread when it changed. */
    fun loadList(done: () -> Unit) {
        if (loadingList || System.currentTimeMillis() - listAt < 60_000) return
        loadingList = true
        m.io.execute {
            val r = Net.get(store, "/api/channels")
            m.runOnUiThread {
                loadingList = false; listAt = System.currentTimeMillis()
                if (r.ok) { list = r.json.optJSONArray("channels"); done() }
            }
        }
    }

    fun name(c: JSONObject) = if (te) c.optString("te") else c.optString("en")
    fun about(c: JSONObject) = c.optJSONObject("about")?.optString(if (te) "te" else "en").orEmpty()
    fun lastLine(c: JSONObject): String {
        val l = c.optJSONObject("last") ?: return about(c)
        return (if (te) l.optString("te") else l.optString("en")).replace('\n', ' ').ifBlank { about(c) }
    }
    /** True when this channel has a post the person hasn't opened yet. */
    fun unseen(c: JSONObject): Boolean {
        val at = c.optJSONObject("last")?.optLong("at") ?: return false
        return at > (ChatDb.get(m).kv("chSeen:" + c.optString("id")).toLongOrNull() ?: 0L)
    }

    /** Opens a channel: the fake messages page for 🛡, else its posts. */
    fun open(id: String) {
        if (id == "fake") { m.go("safety"); return }
        openId = id
        if (postsOf != id) { posts = null; error = "" }
        m.go("channel")
        load()
    }

    private fun load() {
        val id = openId
        m.io.execute {
            val r = Net.get(store, "/api/channels/posts?ch=$id")
            m.runOnUiThread {
                if (id != openId) return@runOnUiThread
                if (r.ok) { posts = r.json.optJSONArray("posts") ?: JSONArray(); postsOf = id; error = "" } else error = r.error(t("Couldn't load. Check your internet.", "లోడ్ కాలేదు. ఇంటర్నెట్ చూడండి."))
                val newest = posts?.optJSONObject(0)?.optLong("at") ?: 0L
                if (newest > 0) ChatDb.get(m).setKv("chSeen:$id", newest.toString())
                if (m.tab == "channel") m.render()
            }
        }
    }

    /** One channel's page. */
    fun page() {
        val c = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }.firstOrNull { it.optString("id") == openId } ?: JSONObject().put("id", openId)
        m.add(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Ico.view(m, R.drawable.ic_back, Ui.INK, 22, 42, t("Back to Updates", "అప్‌డేట్స్‌కి"), ui.round(Ui.SURFACE, 21, Ui.LINE)) { m.backToUpdates() },
                LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(ui.text(c.optString("emoji", "📢"), 26f, Ui.INK).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(48), -2))
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text(name(c).ifBlank { t("Channel", "ఛానెల్") }, 19f, Ui.INK).apply { typeface = Fonts.heavy(context); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(ui.text("✔ " + t("Habit team channel", "Habit టీమ్ ఛానెల్"), 12f, Ui.TEAL_DARK, bold = true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }, 4)
        if (about(c).isNotBlank()) m.add(ui.text(about(c), 14f, Ui.MUTED), 6)
        val p = posts
        when {
            error.isNotBlank() && p == null -> m.add(ui.card().apply {
                addView(ui.text(error, 14f, Ui.MUTED))
                addView(ui.button(t("Try again", "మళ్ళీ ప్రయత్నించు"), "soft") { error = ""; m.render(); load() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            }, 14)
            p == null || postsOf != openId -> m.add(ui.text(t("Loading…", "లోడ్ అవుతోంది…"), 14f, Ui.MUTED), 18)
            p.length() == 0 -> m.add(ui.card().apply {
                addView(ui.text(t("No posts yet. The first post comes today — check back soon.", "ఇంకా పోస్ట్‌లు లేవు. మొదటి పోస్ట్ ఈరోజే వస్తుంది — కాసేపట్లో చూడండి."), 14.5f, Ui.MUTED))
            }, 14)
            else -> for (i in 0 until p.length()) m.add(postCard(p.getJSONObject(i)), if (i == 0) 14 else 10)
        }
    }

    private fun postCard(o: JSONObject): View = ui.card().apply {
        val words = (if (te) o.optString("te") else o.optString("en")).ifBlank { o.optString("te") }
        val video = o.optString("video")
        if (video.isNotBlank()) {
            val abs = { u: String -> if (u.startsWith("http")) u else store.server.trimEnd('/') + u }
            addView(FrameLayout(m).apply {
                background = ui.round(Color.BLACK, 14)
                addView(ImageView(m).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; Images.load(abs(o.optString("poster")), this, 720) }, FrameLayout.LayoutParams(-1, -1))
                addView(TextView(m).apply { text = "▶"; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#99000000")) } }, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
                setOnClickListener {
                    MediaViewer.items = listOf(MediaViewer.Item("file", abs(video), "", "🎬 Habit Videos · 🤖 AI-made", words, 0, false, abs(o.optString("poster"))))
                    MediaViewer.start = 0; MediaViewer.server = store.server; MediaViewer.onLike = null; MediaViewer.onComments = null
                    m.leavingForSystemScreen = true; m.startActivity(Intent(m, MediaViewer::class.java))
                }
            }, LinearLayout.LayoutParams(-1, dp(380)))
        }
        addView(ui.text(words, 15.5f, Ui.INK).apply { setLineSpacing(0f, 1.15f); setTextIsSelectable(true); if (video.isNotBlank()) setPadding(0, dp(10), 0, 0) })
        val whenTxt = ChatStatus.ago(o.optLong("at"), te)
        addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0)
            addView(ui.text(whenTxt + if (o.optBoolean("ai")) " · 🤖 AI-made" else "", 12f, Ui.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            if (words.isNotBlank()) addView(ui.text("📤 " + t("Share", "షేర్"), 13f, Ui.TEAL_DARK, bold = true).apply {
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setOnClickListener {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, words + "\n\n— Habit AI · " + store.server.removePrefix("https://"))
                    m.leavingForSystemScreen = true
                    try { m.startActivity(Intent.createChooser(send, t("Share", "షేర్"))) } catch (e: Exception) { m.leavingForSystemScreen = false }
                }
            })
        })
    }
}
