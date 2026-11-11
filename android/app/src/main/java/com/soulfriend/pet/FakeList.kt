package com.soulfriend.pet

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * The fake messages list in Tasks › 🛡️ Fake messages, right under the message check.
 * People add a fake message they got; the server hides numbers, links and names first. Under each one anyone can
 * tap 🚨 Fake · 📩 I got it too · ✅ Safe and write a short comment (checked before it shows).
 * A small switch on top, like Files' Public / Office: 🆕 Latest | 🔥 Most reported.
 * The card sits in its own holder, so taps and new data redraw only this card (not the page you may be typing in).
 */
class FakeList(private val m: MainActivity) {
    private val store get() = m.store
    private val ui get() = m.ui
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = m.dp(v)
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    private fun headers() = if (store.teamKey.isNotBlank()) mapOf("X-Admin-Key" to store.teamKey) else emptyMap()

    private var sort = "latest"
    private var items: JSONArray? = null
    private var more = false
    private var loading = false
    private var loadedAt = 0L
    private var error = ""
    private var holder: LinearLayout? = null
    private val open = HashSet<Int>()                 // messages with all comments open
    private val longText = HashSet<Int>()             // messages shown in full
    private val comments = HashMap<Int, JSONArray>()
    private val drafts = HashMap<Int, String>()
    private val busy = HashSet<String>()

    /** Adds the card to the page (called by MessagesPage.build). */
    fun build() {
        val h = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL }
        holder = h
        fill(h)
        m.add(h, 14)
    }

    private fun redraw() { holder?.takeIf { it.isAttachedToWindow && m.onSafety() }?.let { fill(it) } }

    private fun fill(h: LinearLayout) {
        h.removeAllViews()
        if ((items == null || System.currentTimeMillis() - loadedAt > 3 * 60_000L) && !loading && System.currentTimeMillis() - loadedAt > 15_000L) load(false)
        h.addView(ui.section(t("📋 Fake messages people got", "📋 ప్రజలకు వచ్చిన మోసపు మెసేజ్‌లు"),
            t("Got a fake message? Add it here to warn everyone. Numbers, links and names are hidden first.",
                "మోసపు మెసేజ్ వచ్చిందా? ఇక్కడ చేర్చి అందరినీ హెచ్చరించండి. నంబర్లు, లింకులు, పేర్లు ముందే దాచబడతాయి.")).apply {
            // the small switch, like Files' Public / Office
            put(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(sortTab(t("🆕 Latest", "🆕 కొత్తవి"), t("Just added", "ఇప్పుడే వచ్చినవి"), sort == "latest") { setSort("latest") }, LinearLayout.LayoutParams(0, -1, 1f))
                addView(sortTab(t("🔥 Most reported", "🔥 ఎక్కువ మందికి"), t("Many people got these", "చాలా మందికి వచ్చినవి"), sort == "top") { setSort("top") },
                    LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
            }, 12)
            put(ui.button(t("➕ Add a fake message you got", "➕ మీకు వచ్చిన మోసపు మెసేజ్ చేర్చండి"), "primary") { addDialog("") }, 10)
            val list = items
            if (list == null) {
                put(ui.text(if (loading) t("⏳ Loading the list…", "⏳ లిస్ట్ వస్తోంది…") else error.ifBlank { t("Couldn't load the list.", "లిస్ట్ రాలేదు.") }, 14f, Ui.MUTED), 10)
                if (!loading) put(ui.button(t("Try again", "మళ్ళీ ప్రయత్నించండి"), "ghost") { load(false) }, 8)
                return@apply
            }
            if (list.length() == 0) put(ui.text(t("No fake messages here yet. Got one? Tap ➕ Add and warn others first.",
                "ఇంకా ఏమీ లేవు. మీకు వచ్చిందా? ➕ నొక్కి అందరినీ ముందే హెచ్చరించండి."), 14f, Ui.MUTED), 10)
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { put(item(it), 10) }
            if (more) put(ui.button(if (loading) t("Loading…", "వస్తోంది…") else t("Show more", "ఇంకా చూపించు"), "ghost") { if (!loading) load(true) }, 10)
            put(ui.text(t("Added by Habit users and written as they got it. Never click their links or share an OTP or PIN. Lost money? Call 1930.",
                "Habit వాడేవాళ్ళు చేర్చినవి. వాటిలోని లింకులు నొక్కకండి, OTP లేదా PIN ఎవరికీ చెప్పకండి. డబ్బు పోయిందా? వెంటనే 1930 కి కాల్ చేయండి."), 12f, Ui.MUTED), 10)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun sortTab(title: String, sub: String, on: Boolean, onClick: () -> Unit) = LinearLayout(m).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(9), dp(12), dp(9))
        background = if (on) ui.round(Ui.TEAL, 16) else ui.round(Ui.SURFACE, 16, Ui.LINE)
        addView(ui.text(title, 14.5f, if (on) Color.WHITE else Ui.INK, bold = true))
        addView(ui.text(sub, 12f, if (on) Color.parseColor("#DDFFFFFF") else Ui.MUTED))
        contentDescription = title
        setOnClickListener { onClick() }
    }

    private fun setSort(s: String) {
        if (sort == s) return
        sort = s; items = null; more = false; loadedAt = 0L; load(false)
    }

    // ---------- one fake message ----------
    private fun item(o: JSONObject): View {
        val id = o.optInt("id")
        val danger = o.optString("level") != "careful"
        val col = if (danger) Ui.DANGER else AppLook.WARN
        return LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(10))
            background = ui.round(if (danger) AppLook.lt(Color.parseColor("#FFF7F6"), AppLook.DANGER_SOFT) else AppLook.lt(Color.parseColor("#FFFAEF"), AppLook.WARN_SOFT), 16, Ui.LINE)
            // title + ⋮
            addView(ui.line(ui.text(o.optString("emoji", "⚠️") + " " + o.optString("title"), 15.5f, col, bold = true),
                TextView(m).apply { text = "⋮"; textSize = 20f; setTextColor(Ui.MUTED); setPadding(dp(10), 0, dp(4), 0); contentDescription = t("More", "ఇంకా"); setOnClickListener { menu(o) } }))
            val got = o.optInt("got")
            addView(ui.text(listOf(where(o.optString("where")), ago(o.optLong("seen", o.optLong("at"))),
                if (got > 0) t("📩 $got got it too", "📩 $got మందికి వచ్చింది") else "").filter { it.isNotBlank() }.joinToString(" · ") +
                (if (o.optBoolean("hidden")) "  · 🙈 " + t("hidden by reports", "రిపోర్టులతో దాచబడింది") else ""), 12.5f, Ui.MUTED))
            // the message itself
            val full = id in longText
            addView(ui.text("“" + o.optString("text") + "”", 14.5f, Ui.INK).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8)); background = ui.round(Ui.SURFACE, 12)
                if (!full) { maxLines = 6; ellipsize = TextUtils.TruncateAt.END }
                setOnClickListener { if (full) longText.remove(id) else longText.add(id); redraw() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            // 🚨 Fake · 📩 I got it too · ✅ Safe
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL
                val my = o.optString("my")
                listOf(Triple("fake", "🚨", t("Fake", "మోసం")), Triple("got", "📩", t("Got it too", "నాకూ వచ్చింది")), Triple("safe", "✅", t("Safe", "సేఫ్"))).forEachIndexed { i, (k, e, label) ->
                    addView(votePill(e, o.optInt(k), label, my == k, k == "fake" || k == "got") { vote(o, k) },
                        LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            // comments
            addView(commentsView(o), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun votePill(emoji: String, n: Int, label: String, on: Boolean, warn: Boolean, onClick: () -> Unit) = LinearLayout(m).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(6), dp(4), dp(6))
        val fill = when { !on -> Ui.SURFACE; warn -> Ui.DANGER; else -> Ui.GOOD }
        background = ui.round(fill, 14, if (on) null else Ui.LINE)
        addView(ui.text("$emoji $n", 15f, if (on) Color.WHITE else Ui.INK, bold = true).apply { gravity = Gravity.CENTER })
        addView(ui.text(label, 11.5f, if (on) Color.WHITE else Ui.MUTED).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        contentDescription = "$label $n"
        setOnClickListener { onClick() }
    }

    private fun commentsView(o: JSONObject): View = LinearLayout(m).apply {
        orientation = LinearLayout.VERTICAL
        val id = o.optInt("id")
        val n = o.optInt("comments")
        val isOpen = id in open
        val shown = if (isOpen) comments[id] else o.optJSONArray("last")
        if (shown != null) for (i in 0 until shown.length()) shown.optJSONObject(i)?.let { c ->
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3))
                addView(ui.text("${c.optString("emoji", "🐾")} ${c.optString("name")}: " + c.optString("text"), 13.5f, Ui.INK), LinearLayout.LayoutParams(0, -2, 1f))
                setOnLongClickListener { commentMenu(o, c); true }
            })
        }
        val link = when {
            !isOpen && n > (shown?.length() ?: 0) -> t("💬 See all $n comments", "💬 అన్ని $n కామెంట్లు చూడండి")
            !isOpen -> if (n == 0) t("💬 Write a comment", "💬 కామెంట్ రాయండి") else t("💬 Write a comment", "💬 కామెంట్ రాయండి")
            else -> t("Hide comments", "కామెంట్లు మూసివేయి")
        }
        addView(ui.text(link, 13.5f, Ui.TEAL_DARK, bold = true).apply {
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { if (isOpen) { open.remove(id); redraw() } else openComments(id) }
        })
        if (isOpen) {
            val box = ui.input(t("Write a short comment…", "చిన్న కామెంట్ రాయండి…"), drafts[id] ?: "")
            box.onText { drafts[id] = it }
            addView(ui.line(box, ui.button(if ("c$id" in busy) "…" else t("Send", "పంపు"), "primary") { comment(o, box.text.toString()) }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(ui.text(t("Be kind. No phone numbers, links or names. Comments are checked before they show.",
                "మర్యాదగా రాయండి. నంబర్లు, లింకులు, పేర్లు వద్దు. కామెంట్లు చూసే ముందు చెక్ చేయబడతాయి."), 11.5f, Ui.MUTED))
        }
    }

    // ---------- talking to the server ----------
    private fun load(append: Boolean) {
        if (loading) return
        loading = true; error = ""
        val from = if (append) items?.length() ?: 0 else 0
        val s = sort
        if (!append) redraw()
        m.io.execute {
            val r = Net.get(store, "/api/fakes?sort=$s&lang=${if (te) "te" else "en"}&from=$from&n=10", headers())
            m.runOnUiThread {
                loading = false; loadedAt = System.currentTimeMillis()
                if (s != sort) return@runOnUiThread
                if (r.ok) {
                    val got = r.json.optJSONArray("items") ?: JSONArray()
                    val list = if (append) items ?: JSONArray() else JSONArray()
                    for (i in 0 until got.length()) list.put(got.get(i))
                    items = list; more = r.json.optBoolean("more")
                } else error = if (r.code == 404) t("The list is getting ready on the Habit server. Please look again in a little while.", "ఈ లిస్ట్ Habit సర్వర్‌లో సిద్ధమవుతోంది. కాసేపట్లో మళ్ళీ చూడండి.")
                    else r.error(t("Couldn't load the list. Check the internet.", "లిస్ట్ రాలేదు. ఇంటర్నెట్ చూడండి."))
                redraw()
            }
        }
    }

    private fun replace(o: JSONObject, patch: (JSONObject) -> Unit) {
        val list = items ?: return
        for (i in 0 until list.length()) { val x = list.optJSONObject(i) ?: continue; if (x.optInt("id") == o.optInt("id")) { patch(x); break } }
    }

    private fun vote(o: JSONObject, k: String) {
        val key = "v" + o.optInt("id")
        if (key in busy) return
        busy.add(key)
        val v = if (o.optString("my") == k) "" else k
        m.io.execute {
            val r = Net.post(store, "/api/fakes/vote", JSONObject().put("id", o.optInt("id")).put("v", v), headers(), 20_000)
            m.runOnUiThread {
                busy.remove(key)
                if (r.ok) replace(o) { x -> for (f in listOf("fake", "got", "safe", "my")) x.put(f, r.json.opt(f)) }
                else needProfile(r) ?: m.toast(r.error(t("Couldn't save. Check the internet.", "సేవ్ కాలేదు. ఇంటర్నెట్ చూడండి.")))
                redraw()
            }
        }
    }

    private fun openComments(id: Int) {
        open.add(id); redraw()
        m.io.execute {
            val r = Net.get(store, "/api/fakes/comments?id=$id", headers())
            m.runOnUiThread {
                if (r.ok) comments[id] = r.json.optJSONArray("comments") ?: JSONArray()
                else { open.remove(id); m.toast(r.error(t("Couldn't load the comments.", "కామెంట్లు రాలేదు."))) }
                redraw()
            }
        }
    }

    private fun comment(o: JSONObject, text: String) {
        val id = o.optInt("id")
        val s = text.trim()
        if (s.length < 2) { m.toast(t("Write a little more.", "ఇంకొంచెం రాయండి.")); return }
        if ("c$id" in busy) return
        busy.add("c$id"); redraw()
        m.io.execute {
            val r = Net.post(store, "/api/fakes/comment", JSONObject().put("id", id).put("text", s), headers(), 30_000)
            m.runOnUiThread {
                busy.remove("c$id")
                if (r.ok) {
                    drafts.remove(id)
                    val c = r.json.optJSONObject("comment")
                    if (c != null) { (comments[id] ?: JSONArray().also { comments[id] = it }).put(c) }
                    replace(o) { x ->
                        x.put("comments", r.json.optInt("comments", x.optInt("comments") + 1))
                        val last = JSONArray(); val all = comments[id]
                        if (all != null) for (i in maxOf(0, all.length() - 2) until all.length()) last.put(all.get(i))
                        x.put("last", last)
                    }
                } else needProfile(r) ?: m.toast(r.error(t("Couldn't send. Check the internet.", "పంపలేకపోయాం. ఇంటర్నెట్ చూడండి.")))
                redraw()
            }
        }
    }

    /** Opens the add window; [prefill] is the message just checked above (if any). */
    fun addDialog(prefill: String) {
        val box = LinearLayout(m).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val input = ui.input(t("Paste the whole message here…", "పూర్తి మెసేజ్ ఇక్కడ పేస్ట్ చేయండి…"), prefill, multi = true)
        box.addView(input)
        var where = "whatsapp"
        val chips = LinearLayout(m).apply { orientation = LinearLayout.HORIZONTAL }
        val opts = listOf("whatsapp" to "WhatsApp", "sms" to "SMS", "call" to t("📞 Call", "📞 కాల్"), "email" to "Email", "other" to t("Other", "ఇతర"))
        fun drawChips() {
            chips.removeAllViews()
            opts.forEach { (k, label) -> chips.addView(ui.chip(label, where == k) { where = k; drawChips() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
        }
        drawChips()
        box.addView(ui.text(t("Where did it come?", "ఎక్కడ వచ్చింది?"), 13.5f, Ui.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        box.addView(android.widget.HorizontalScrollView(m).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        box.addView(ui.text(t("For a fake call, write what they said. Phone numbers, links, emails and names are hidden before anyone sees it.",
            "ఫేక్ కాల్ అయితే వాళ్ళు ఏం చెప్పారో రాయండి. నంబర్లు, లింకులు, ఈమెయిల్స్, పేర్లు ఎవరూ చూడకముందే దాచబడతాయి."), 12f, Ui.MUTED),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val dlg = AlertDialog.Builder(m).setTitle(t("➕ Add a fake message", "➕ మోసపు మెసేజ్ చేర్చండి"))
            .setView(android.widget.ScrollView(m).apply { addView(box) })
            .setPositiveButton(t("Add", "చేర్చు"), null).setNegativeButton(t("Cancel", "వద్దు"), null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (text.length < 12) { m.toast(t("Please paste the whole message.", "పూర్తి మెసేజ్ పేస్ట్ చేయండి.")); return@setOnClickListener }
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                m.io.execute {
                    val r = Net.post(store, "/api/fakes/add", JSONObject().put("text", text.take(3000)).put("where", where), headers(), 45_000)
                    m.runOnUiThread {
                        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                        if (!r.ok) { needProfile(r)?.also { dlg.dismiss() } ?: m.toast(r.error(t("Couldn't add it. Check the internet.", "చేర్చలేకపోయాం. ఇంటర్నెట్ చూడండి."))); return@runOnUiThread }
                        dlg.dismiss()
                        m.toast(if (r.json.optBoolean("merged")) t("Others already added this one. We counted you in \"Got it too\". 🙏", "ఇది ఇప్పటికే ఉంది. మిమ్మల్ని \"నాకూ వచ్చింది\"లో లెక్కించాం. 🙏")
                            else t("Added. Thank you for warning everyone! 🙏", "చేర్చాం. అందరినీ హెచ్చరించినందుకు ధన్యవాదాలు! 🙏"))
                        sort = "latest"; items = null; loadedAt = 0L; load(false)
                        holder?.let { h -> h.post { m.scrollToView(h) } }
                    }
                }
            }
        }
        dlg.show()
    }

    /** No Habit profile yet: says how to make one (returns Unit), or null when that wasn't the problem. */
    private fun needProfile(r: Net.Reply): Unit? =
        if (r.code == 403 && r.json.optBoolean("needProfile")) m.toast(t("Make your Habit profile first: tap 💬 Start Habit Chat at the top of Messages.",
            "ముందు మీ Habit ప్రొఫైల్ చేయండి: మెసేజ్‌ల పైన 💬 Habit చాట్ మొదలుపెట్టండి నొక్కండి.")) else null

    // ---------- ⋮ menus ----------
    private fun menu(o: JSONObject) {
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add(t("📤 Share with family", "📤 కుటుంబంతో షేర్ చేయండి") to { share(o) })
        if (o.optBoolean("mine") || store.teamKey.isNotBlank()) acts.add(t("🗑️ Delete", "🗑️ తొలగించు") to { post("/api/fakes/delete", JSONObject().put("id", o.optInt("id")), t("Deleted", "తొలగించాం")) })
        else acts.add(t("⚠️ Report (not a fake message, or rude)", "⚠️ రిపోర్ట్ (మోసపు మెసేజ్ కాదు / అసభ్యం)") to { post("/api/fakes/report", JSONObject().put("id", o.optInt("id")), t("Thank you. The team will look at it.", "ధన్యవాదాలు. టీమ్ చూస్తుంది.")) })
        if (store.teamKey.isNotBlank() && o.optBoolean("hidden")) acts.add("✅ Team: show it again" to { post("/api/fakes/restore", JSONObject().put("id", o.optInt("id")), "Shown again") })
        AlertDialog.Builder(m).setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
    }

    private fun commentMenu(o: JSONObject, c: JSONObject) {
        val body = JSONObject().put("id", o.optInt("id")).put("cid", c.optString("id"))
        val acts = ArrayList<Pair<String, () -> Unit>>()
        if (c.optBoolean("mine") || store.teamKey.isNotBlank()) acts.add(t("🗑️ Delete comment", "🗑️ కామెంట్ తొలగించు") to { post("/api/fakes/delete", body, t("Deleted", "తొలగించాం")) })
        if (!c.optBoolean("mine")) acts.add(t("⚠️ Report comment", "⚠️ కామెంట్ రిపోర్ట్") to { post("/api/fakes/report", body, t("Thank you. The team will look at it.", "ధన్యవాదాలు. టీమ్ చూస్తుంది.")) })
        if (store.teamKey.isNotBlank() && c.optBoolean("hidden")) acts.add("✅ Team: show it again" to { post("/api/fakes/restore", body, "Shown again") })
        if (acts.isNotEmpty()) AlertDialog.Builder(m).setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
    }

    private fun post(path: String, body: JSONObject, done: String) {
        m.io.execute {
            val r = Net.post(store, path, body, headers(), 20_000)
            m.runOnUiThread {
                if (r.ok) { m.toast(done); comments.remove(body.optInt("id")); open.remove(body.optInt("id")); loadedAt = 0L; load(false) }
                else needProfile(r) ?: m.toast(r.error(t("Couldn't do it. Check the internet.", "కాలేదు. ఇంటర్నెట్ చూడండి.")))
            }
        }
    }

    private fun share(o: JSONObject) {
        val text = t("⚠️ Fake message going around: ", "⚠️ ఈ మోసపు మెసేజ్ తిరుగుతోంది: ") + o.optString("title") + "\n\n“" + o.optString("text") + "”\n\n" +
            t("Don't click its links, don't share OTP or PIN. Lost money? Call 1930.\n— from Habit AI",
                "దాని లింకులు నొక్కకండి, OTP / PIN చెప్పకండి. డబ్బు పోయిందా? 1930 కి కాల్ చేయండి.\n— Habit AI నుంచి")
        m.leavingForSystemScreen = true
        try { m.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), t("Share", "షేర్"))) } catch (e: Exception) { }
    }

    // ---------- small helpers ----------
    private fun where(w: String) = when (w) {
        "whatsapp" -> "WhatsApp"; "sms" -> "SMS"; "call" -> t("📞 Phone call", "📞 ఫోన్ కాల్"); "email" -> "Email"; "social" -> "Facebook / Instagram"
        else -> t("Other", "ఇతర")
    }

    private fun ago(at: Long): String {
        if (at <= 0) return ""
        val min = (System.currentTimeMillis() - at) / 60_000L
        return when {
            min < 2 -> t("just now", "ఇప్పుడే")
            min < 60 -> t("$min min ago", "$min నిమిషాల క్రితం")
            min < 24 * 60 -> t("${min / 60} h ago", "${min / 60} గంటల క్రితం")
            min < 48 * 60 -> t("yesterday", "నిన్న")
            else -> t("${min / (24 * 60)} days ago", "${min / (24 * 60)} రోజుల క్రితం")
        }
    }
}
