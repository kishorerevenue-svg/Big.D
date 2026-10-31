package com.soulfriend.pet

import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Community page (like a small Twitter for BigD AI): everyone can post ideas, problems and thanks, and read
 * everyone's posts. Likes, reports, block, delete your own posts, and replies from the BigD team.
 * Posts are checked for bad words and personal details before they appear.
 */
class CommunityPage(private val m: MainActivity) {
    private class Post(val id: Int, val at: Long, val name: String, val pet: String, val kind: String, val text: String,
                       var likes: Int, var liked: Boolean, val mine: Boolean, val user: String, val pinned: Boolean, val team: String,
                       val hidden: Boolean, val removed: Boolean, val reports: Int)

    private val posts = mutableListOf<Post>()
    private var loaded = false
    private var loading = false
    private var more = false
    private var error = ""
    private var admin = false
    private var kind = "idea"
    private var text = ""
    private var privateMsg = false
    private var rating = 0
    private var sending = false
    private var status = ""
    private var editingName = false
    private var listening = false

    private val ui get() = m.ui
    private val store get() = m.store
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(top) })
    private fun headers() = if (store.teamKey.isNotBlank()) mapOf("X-Admin-Key" to store.teamKey) else emptyMap()

    private val kinds = listOf("idea" to "💡 Idea", "problem" to "🐞 Problem", "thanks" to "❤️ Thanks", "other" to "💬 Other")
    private fun kindLabel(k: String) = kinds.firstOrNull { it.first == k }?.second ?: "💬 Other"
    private fun petIcon(p: String) = mapOf("puppy" to "🐶", "kitten" to "🐱", "bunny" to "🐰", "panda" to "🐼", "bear" to "🧸", "fox" to "🦊",
        "robot" to "🤖", "parrot" to "🦜", "mochi" to "🍡", "kitty" to "😺", "chameleon" to "🦎")[p] ?: "🐾"

    fun build() {
        m.pageHeader("Community · కమ్యూనిటీ", "Share ideas, problems and thanks. Everyone can read the posts, and the BigD team replies.")
        composer()
        m.add(ui.line(ui.label(if (admin) "Latest posts · team view" else "Latest posts"), ui.button(if (loading) "…" else "↻ Refresh", "ghost") { load(true) }), 20)
        if (error.isNotBlank()) m.add(ui.text("⚠️ $error", 13.5f, Ui.DANGER), 8)
        val blocked = store.blockedUsers
        val shown = posts.filter { it.user !in blocked }
        if (shown.isEmpty()) m.add(ui.text(if (loading || !loaded) "Loading posts…" else "No posts yet. Be the first to share an idea!", 14f, Ui.MUTED), 8)
        shown.forEach { m.add(card(it), 10) }
        if (more) m.add(ui.button(if (loading) "Loading…" else "Show more posts", "ghost") { if (!loading) load(false) }, 12)
        m.shareAndAbout()
        if (!loaded && !loading) load(true)
    }

    // ---------------- writing a post ----------------
    private fun composer() {
        m.add(ui.section("✍️ Write a post").apply {
            if (store.feedName.isBlank() || editingName) {
                val nameBox = ui.input("Your name (shown with your posts)", store.feedName)
                put(nameBox, 10)
                put(ui.row(ui.button("Save name") {
                    val n = nameBox.text.toString().trim()
                    if (n.length < 2) m.toast("Write a name with at least 2 letters.") else { store.feedName = n; editingName = false; m.render() }
                }), 6)
            } else put(ui.line(ui.text("Posting as ${petIcon(store.petKind)} ${store.feedName}", 14f, Ui.MUTED),
                ui.button("✎ Change", "ghost") { editingName = true; m.render() }), 8)

            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                kinds.take(2).forEachIndexed { i, (k, l) -> addView(ui.chip(l, kind == k) { kind = k; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) }) }
            }, 12)
            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                kinds.drop(2).forEachIndexed { i, (k, l) -> addView(ui.chip(l, kind == k) { kind = k; m.render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = m.dp(6) }) }
            }, 6)

            val box = ui.input(when (kind) { "idea" -> "Your idea for BigD AI…"; "problem" -> "What went wrong? Which page?"; "thanks" -> "What did you like?"; else -> "Write here, in Telugu or English…" }, text, multi = true)
            box.onText { text = it }
            put(box, 10)
            put(ui.row(ui.button(if (listening) "● Listening… tap to stop" else "🎤 Speak", if (listening) "danger" else "soft") {
                if (listening) { m.stopListening(); listening = false; m.render(); return@button }
                val before = box.text.toString().trim()
                listening = true; m.render()
                m.listen(onPartial = { p -> box.setText(if (before.isEmpty()) p else "$before $p") }, onDone = { heard ->
                    listening = false
                    if (!heard.isNullOrBlank()) text = if (before.isEmpty()) heard else "$before $heard"
                    m.render()
                })
            }), 8)

            put(ui.check("🔒 Only for the BigD team (not shown to others)", privateMsg) { privateMsg = it; m.render() }, 10)
            if (privateMsg) put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.text("Rate the app: ", 14f, Ui.MUTED))
                for (i in 1..5) addView(ui.text(if (i <= rating) "★" else "☆", 30f, if (i <= rating) Ui.SUN else Ui.LINE).apply {
                    setPadding(m.dp(3), 0, m.dp(3), 0); setOnClickListener { rating = i; m.render() }
                })
                gravity = Gravity.CENTER_VERTICAL
            }, 6)
            put(ui.button(if (sending) "Sending…" else if (privateMsg) "🔒 Send to the team" else "📢 Post for everyone", "primary") { if (!sending) submit() }, 12)
            if (status.isNotBlank()) put(ui.text(status, 13.5f, if (status.startsWith("⚠️")) Ui.DANGER else Ui.GOOD), 8)
        }, 14)
    }

    private fun submit() {
        val t = text.trim()
        if (privateMsg) {
            if (t.isEmpty() && rating == 0) { m.toast("Write something or tap the stars."); return }
            sending = true; status = ""; m.render()
            m.sendFeedback(rating, kind, t) { ok -> sending = false; status = if (ok) "✅ Thank you! The BigD team got your message." else "⚠️ Couldn't send. Check your internet."; if (ok) { text = ""; rating = 0 }; m.render() }
            return
        }
        if (t.length < 3) { m.toast("Write a little more first."); return }
        if (store.feedName.isBlank()) { m.toast("First write your name above and tap Save name."); editingName = true; m.render(); return }
        if (!store.feedAgreed) { rules { store.feedAgreed = true; submit() }; return }
        sending = true; status = ""; m.render()
        val body = JSONObject().put("name", store.feedName).put("pet", store.petKind).put("kind", kind).put("text", t)
        m.io.execute {
            val r = Net.post(store, "/api/feed/post", body, headers(), 30_000)
            m.runOnUiThread {
                sending = false
                if (r.ok) { text = ""; status = "✅ Posted! Everyone can see it now."; r.json.optJSONObject("post")?.let { posts.add(0, parse(it)) } }
                else status = "⚠️ " + r.error("Couldn't post. Please try again.")
                m.render()
            }
        }
    }

    private fun rules(onAgree: () -> Unit) {
        AlertDialog.Builder(m).setTitle("Community rules · నియమాలు")
            .setMessage("Posts here are public: everyone using BigD AI can read them, with your name.\n\n" +
                "• Be kind and respectful.\n• No bad words, hate, adult or violent content.\n• Don't share phone numbers, addresses or ID numbers.\n" +
                "• No ads, links or fake news.\n\nPosts that break these rules are removed, and people who keep breaking them are blocked. " +
                "You can delete your own posts at any time.")
            .setPositiveButton("I agree") { _, _ -> onAgree() }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------- the feed ----------------
    private fun parse(o: JSONObject): Post {
        val team = o.optJSONObject("team")?.optString("text") ?: ""
        return Post(o.optInt("id"), o.optLong("at"), o.optString("name"), o.optString("pet"), o.optString("kind"), o.optString("text"),
            o.optInt("likes"), o.optBoolean("liked"), o.optBoolean("mine"), o.optString("user"), o.optBoolean("pinned"), team,
            o.optBoolean("hidden"), o.optBoolean("removed"), o.optInt("reports"))
    }

    fun load(refresh: Boolean) {
        if (loading) return
        loading = true; error = ""
        val before = if (refresh) "" else posts.filter { !it.pinned }.minOfOrNull { it.id }?.let { "&before=$it" } ?: ""
        val adminQ = if (store.teamKey.isNotBlank()) "&admin=1" else ""
        m.io.execute {
            val r = Net.get(store, "/api/feed?limit=25$before$adminQ", headers())
            m.runOnUiThread {
                loading = false; loaded = true
                if (!r.ok) { error = r.error("Couldn't load posts."); m.render(); return@runOnUiThread }
                val a = r.json.optJSONArray("posts")
                val list = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> parse(o) } }
                if (refresh) posts.clear()
                list.forEach { p -> if (posts.none { it.id == p.id }) posts.add(p) }
                more = r.json.optBoolean("more")
                val wasAdmin = admin
                admin = r.json.optBoolean("admin")
                if (store.teamKey.isNotBlank() && !admin && !wasAdmin && refresh) m.toast("The team password is wrong. Posts are shown as for everyone.")
                if (m.tab == "feedback") m.render()
            }
        }
    }

    private fun ago(at: Long): String {
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; s < 7 * 86400 -> "${s / 86400} d ago"
            else -> DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        }
    }

    private fun card(p: Post): View = ui.card(if (p.hidden || p.removed) Color.parseColor("#F7EDED") else Ui.SURFACE).apply {
        val avatar = TextView(m).apply {
            text = petIcon(p.pet); textSize = 22f; gravity = Gravity.CENTER
            background = ui.round(Ui.TEAL_SOFT, 22)
        }
        addView(LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(avatar, LinearLayout.LayoutParams(m.dp(44), m.dp(44)))
            addView(ui.stack(ui.text(p.name + (if (p.mine) " · you" else ""), 15f, Ui.INK, bold = true),
                ui.text(kindLabel(p.kind) + " · " + ago(p.at), 12.5f, Ui.MUTED)), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = m.dp(10) })
            if (p.pinned) addView(ui.text("📌", 18f))
        })
        if (admin && (p.hidden || p.removed || p.reports > 0))
            put(ui.text((if (p.removed) "Removed · " else if (p.hidden) "Hidden after reports · " else "") + "${p.reports} report(s)", 12.5f, Ui.DANGER, bold = true), 8)
        put(ui.text(p.text, 15.5f, Ui.INK).apply { setTextIsSelectable(true) }, 10)
        if (p.team.isNotBlank()) put(ui.card(Ui.TEAL_SOFT).apply {
            addView(ui.text("🐾 BigD team", 13.5f, Ui.TEAL_DARK, bold = true))
            put(ui.text(p.team, 14.5f, Ui.INK), 4)
        }, 10)
        put(ui.row(
            ui.button((if (p.liked) "❤️ " else "🤍 ") + p.likes, if (p.liked) "soft" else "ghost") { like(p) },
            ui.button("⋯", "ghost") { menu(p) }
        ), 10)
    }

    private fun like(p: Post) {
        p.liked = !p.liked; p.likes += if (p.liked) 1 else -1; m.render()
        m.io.execute {
            val r = Net.post(store, "/api/feed/like", JSONObject().put("id", p.id), headers(), 15_000)
            m.runOnUiThread { if (r.ok) { p.likes = r.json.optInt("likes", p.likes); p.liked = r.json.optBoolean("liked", p.liked) }; m.render() }
        }
    }

    private fun menu(p: Post) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (p.mine) items.add("🗑 Delete my post" to { call("/api/feed/delete", JSONObject().put("id", p.id), "Deleted.") { posts.remove(p) } })
        else {
            items.add("🚩 Report this post" to { report(p) })
            items.add("🚫 Hide posts from ${p.name}" to { store.blockedUsers = store.blockedUsers + p.user; m.toast("You won't see posts from ${p.name}."); m.render() })
        }
        if (admin) {
            items.add("🐾 Reply as BigD team" to { teamReply(p) })
            items.add((if (p.pinned) "📌 Unpin" else "📌 Pin to top") to { adminDo(p, if (p.pinned) "unpin" else "pin") })
            items.add((if (p.removed || p.hidden) "♻️ Restore" else "🗑 Remove (team)") to { adminDo(p, if (p.removed || p.hidden) "restore" else "remove") })
        }
        AlertDialog.Builder(m).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun report(p: Post) {
        val reasons = arrayOf("Bad words or insults", "Spam or ads", "Fake or wrong information", "Personal details", "Something else")
        AlertDialog.Builder(m).setTitle("Why are you reporting it?").setItems(reasons) { _, i ->
            call("/api/feed/report", JSONObject().put("id", p.id).put("reason", reasons[i]), "Thank you. The BigD team will check it.") { posts.remove(p) }
        }.show()
    }

    private fun teamReply(p: Post) {
        val box = ui.input("Reply as the BigD team", p.team, multi = true)
        AlertDialog.Builder(m).setTitle("Team reply").setView(LinearLayout(m).apply { setPadding(m.dp(20), m.dp(8), m.dp(20), 0); addView(box, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("Save") { _, _ -> adminDo(p, "reply", box.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun adminDo(p: Post, action: String, text: String = "") =
        call("/api/feed/admin", JSONObject().put("id", p.id).put("action", action).put("text", text), "Done.") { }

    private fun call(path: String, body: JSONObject, okMsg: String, onOk: () -> Unit) {
        m.io.execute {
            val r = Net.post(store, path, body, headers(), 20_000)
            m.runOnUiThread {
                if (r.ok) { onOk(); m.toast(okMsg); if (path.endsWith("admin")) load(true) else m.render() }
                else m.toast(r.error("Couldn't do that. Try again."))
            }
        }
    }
}
