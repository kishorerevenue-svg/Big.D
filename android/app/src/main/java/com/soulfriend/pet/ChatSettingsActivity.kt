package com.soulfriend.pet

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/**
 * Habit Chat settings, like WhatsApp's (kept on this phone): wallpaper, notifications, read receipts (blue ticks),
 * automatic downloads, hidden chats lock, tasks bar, blocked people, clear all chats, and what "locked end to end" means.
 */
class ChatSettingsActivity : Activity() {
    private lateinit var store: Store
    private lateinit var ui: Ui
    private lateinit var box: LinearLayout
    private val C = ChatTheme
    private val db get() = ChatDb.get(this)
    private val te get() = store.lang == "te"
    private fun t(en: String, tel: String) = if (te) tel else en
    private fun dp(v: Int) = ui.dp(v)

    override fun onCreate(savedInstanceState: Bundle?) {
        ChatTheme.apply(this)
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.background = ChatTheme.Backdrop(root, C.BG, C.BG)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), dp(12), dp(6)); minimumHeight = dp(60)
            addView(Ico.view(this@ChatSettingsActivity, R.drawable.ic_back, C.TXT, 22, 48, t("Back", "వెనక్కి")) { finish() })
            addView(ui.text(t("Chat settings", "చాట్ సెట్టింగ్స్"), 22f, C.TXT).apply { typeface = Fonts.heavy(context); setPadding(dp(4), 0, 0, 0) })
        })
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(32)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ChatTheme.bars(this, C.BG, C.BG)
        draw()
    }

    private fun draw() {
        box.removeAllViews(); card = null
        val me = ChatSync.me(this)
        // who I am
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
            background = C.round(C.CARD, dp(24).toFloat(), C.EDGE, dp(1))
            addView(ChatDraw.avatar(this@ChatSettingsActivity, store, me.optString("emoji", "🐾"), me.optString("avatar"), dp(58), null, me.optString("name"), key = me.optString("uid")), LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(LinearLayout(this@ChatSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(ui.text(me.optString("name", "Habit friend"), 18f, C.TXT).apply { typeface = Fonts.heavy(context) })
                if (me.optString("handle").isNotEmpty()) addView(ui.text("@" + me.optString("handle") + " · " + t("change it in Media › your profile", "మీడియా › మీ ప్రొఫైల్‌లో మార్చండి"), 13f, C.SUB))
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        head(t("Look", "రూపం"))
        item(R.drawable.ic_sparkle, 0, t("Theme", "థీమ్"), themeName(ChatTheme.mode(this))) { pickTheme() }
        val wall = ChatTheme.wall(db.kv("set:wall").toIntOrNull() ?: ChatTheme.DEFAULT_WALL)
        item(R.drawable.ic_image, 4, t("Chat wallpaper", "చాట్ వాల్‌పేపర్"), if (te) wall.te else wall.en) { pickWall() }
        item(R.drawable.ic_edit, 2, t("Font size", "అక్షరాల సైజు"), when (db.kv("set:font")) { "s" -> t("Small", "చిన్నది"); "l" -> t("Large", "పెద్దది"); else -> t("Medium", "మధ్యస్థం") }) { pickFont() }
        head(t("Chats", "చాట్‌లు"))
        toggle(R.drawable.ic_send, 1, t("Enter is send", "ఎంటర్ నొక్కితే పంపు"), t("The keyboard's Enter key sends the message", "కీబోర్డ్ ఎంటర్ నొక్కితే మెసేజ్ వెళ్తుంది"), ChatTheme.enterSends(this)) { db.setKv("set:enter", if (it) "1" else "0") }
        toggle(R.drawable.ic_link, 4, t("Link previews", "లింక్ ప్రివ్యూలు"), t("A link you send shows its title and picture (made on your phone)", "మీరు పంపే లింక్‌కి టైటిల్, బొమ్మ (మీ ఫోన్‌లోనే తయారవుతుంది)"), ChatTheme.linkPreviews(this)) { db.setKv("set:previews", if (it) "1" else "0") }
        toggle(R.drawable.ic_tasks, 0, t("Show open tasks as a bar at the top", "తెరిచిన పనులు పైన బార్‌గా చూపించు"),
            t("Off: a small \"tasks\" pill next to the chat's name shows them", "ఆఫ్: చాట్ పేరు పక్కన చిన్న \"పనులు\" గుర్తు చూపిస్తుంది"), ChatTheme.tasksBar(this)) { db.setKv("set:tasksbar", if (it) "1" else "0") }
        item(R.drawable.ic_star, 0, t("Starred messages", "స్టార్ చేసిన మెసేజ్‌లు"), t("Messages you starred, in all chats", "అన్ని చాట్‌లలో మీరు స్టార్ చేసినవి")) { ChatInfoActivity.open(this, "", "starred") }
        item(R.drawable.ic_media, 2, t("Linked devices", "లింక్ చేసిన పరికరాలు"), t("Habit Chat on your computer: bigdai.tech/web", "మీ కంప్యూటర్‌లో Habit చాట్: bigdai.tech/web")) { LinkedDevicesActivity.open(this) }
        head(t("Notifications", "నోటిఫికేషన్లు"))
        toggle(R.drawable.ic_bell, 0, t("Message notifications", "మెసేజ్ నోటిఫికేషన్లు"), t("New messages show a notification", "కొత్త మెసేజ్‌లకు నోటిఫికేషన్"), ChatTheme.notifyOn(this)) { db.setKv("set:notify", if (it) "1" else "0") }
        toggle(R.drawable.ic_eye, 4, t("Show the message in the notification", "నోటిఫికేషన్‌లో మెసేజ్ చూపించు"), t("Off: only \"New message\"", "ఆఫ్: \"కొత్త మెసేజ్\" మాత్రమే"), ChatTheme.previewOn(this)) { db.setKv("set:preview", if (it) "1" else "0") }
        toggle(R.drawable.ic_heart, 3, t("Reaction notifications", "స్పందనల నోటిఫికేషన్లు"), t("When someone reacts to your message", "మీ మెసేజ్‌కి ఎవరైనా స్పందిస్తే"), ChatTheme.reactNotify(this)) { db.setKv("set:reactnotify", if (it) "1" else "0") }
        item(R.drawable.ic_phone, 5, t("Ringtone for calls", "కాల్స్ రింగ్‌టోన్"), t("Phone settings › Habit calls (ringing)", "ఫోన్ సెట్టింగ్స్ › Habit calls (ringing)")) {
            CallManager.channels(this)
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CallManager.RING)) } catch (e: Exception) { }
        }
        head(t("Privacy", "గోప్యత"))
        toggle(R.drawable.ic_clock, 5, t("Last seen and online", "చివరిగా చూసింది, ఆన్‌లైన్"), t("Off: nobody sees when you were online, and you don't see theirs", "ఆఫ్: మీరు ఎప్పుడు ఆన్‌లైన్‌లో ఉన్నారో ఎవరికీ తెలియదు, మీకూ వాళ్ళది కనిపించదు"), db.kv("set:seen") != "0") { on ->
            db.setKv("set:seen", if (on) "1" else "0")
            Thread { ChatSync.api(store, "POST", "/api/chat/privacy", JSONObject().put("seen", on).toString()) }.start()
        }
        toggle(R.drawable.ic_phone, 1, t("Find my contacts on Habit", "Habit లో నా కాంటాక్ట్‌లను చూపించు"),
            t("Shows which of your contacts use Habit. Their numbers are checked with Habit's server in a scrambled form and aren't kept there.",
                "మీ కాంటాక్ట్‌లలో ఎవరు Habit వాడుతున్నారో చూపిస్తుంది. వాళ్ళ నంబర్లు గుప్త రూపంలో Habit సర్వర్‌తో సరిచూస్తాం, అక్కడ దాచం."),
            ChatContacts.matchState(this) == "on") { on ->
            ChatContacts.setMatch(this, on)
            if (on) {
                if (ChatContacts.allowed(this)) ChatContacts.matchNow(this, store, force = true)
                else requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            }
        }
        toggle(R.drawable.ic_search, 4, t("People who have my number can find me", "నా నంబర్ ఉన్నవాళ్ళు నన్ను కనుగొనవచ్చు"),
            t("Your number is never shown. People who already have it in their contacts see that you're on Habit and can chat with you.",
                "మీ నంబర్ ఎవరికీ కనిపించదు. అది ఇప్పటికే తమ కాంటాక్ట్స్‌లో ఉన్నవాళ్ళకు మీరు Habit లో ఉన్నారని తెలుస్తుంది, మీతో చాట్ చేయగలరు."),
            db.kv("set:findme") != "0") { on ->
            db.setKv("set:findme", if (on) "1" else "0")
            Thread { ChatSync.api(store, "POST", "/api/chat/privacy", JSONObject().put("phone", on).toString()) }.start()
        }
        toggle(R.drawable.ic_ticks, 4, t("Read receipts (blue ticks)", "చదివిన గుర్తు (నీలి టిక్కులు)"), t("Off: people don't see when you read their messages", "ఆఫ్: మీరు చదివారని ఇతరులకు తెలియదు"), ChatTheme.receiptsOn(this)) { db.setKv("set:receipts", if (it) "1" else "0") }
        item(R.drawable.ic_status, 1, t("Status privacy", "స్టేటస్ గోప్యత"), when (ChatStatus.privacy(this).first) { "except" -> t("Everyone I chat with, except some", "అందరూ, కొందరు తప్ప"); "only" -> t("Only the people I chose", "నేను ఎంచుకున్నవాళ్ళు మాత్రమే"); else -> t("Everyone I chat with", "నేను చాట్ చేసే అందరూ") }) { ChatStatus.privacyDialog(this, store) }
        item(R.drawable.ic_timer, 2, t("Default message timer", "కొత్త చాట్‌లకు టైమర్"), t("New chats you start: ", "మీరు మొదలుపెట్టే కొత్త చాట్‌లు: ") + ChatTheme.timerText(ChatTheme.defaultTimer(this), te)) { pickTimer() }
        toggle(R.drawable.ic_lock, 0, t("Lock the locked chats", "లాక్ చేసిన చాట్‌లకు లాక్"), t("Open Locked chats with your fingerprint or phone lock", "లాక్ చేసిన చాట్‌లను వేలిముద్ర లేదా ఫోన్ లాక్‌తో తెరవండి"), ChatTheme.lockHidden(this)) { db.setKv("set:lock", if (it) "1" else "0") }
        val blocked = db.convs().filter { it.blocked && !it.isGroup }
        item(R.drawable.ic_close, 3, t("Blocked people", "బ్లాక్ చేసినవాళ్ళు"), if (blocked.isEmpty()) t("None", "ఎవరూ లేరు") else blocked.joinToString(", ") { it.name }) { blockedList() }
        head(t("Storage and data", "స్టోరేజ్, డేటా"))
        val dl = ChatTheme.autoDl(this)
        item(R.drawable.ic_download, 4, t("Download videos and documents", "వీడియోలు, ఫైల్స్ డౌన్‌లోడ్"), when (dl) { "always" -> t("Always (also on mobile data)", "ఎప్పుడూ (మొబైల్ డేటాలో కూడా)"); "never" -> t("Only when I tap", "నేను నొక్కినప్పుడు మాత్రమే"); else -> t("On Wi-Fi (small ones always)", "Wi-Fi లో (చిన్నవి ఎప్పుడూ)") }) { pickDl() }
        val used = try { ChatSync.dir(this).listFiles()?.sumOf { it.length() } ?: 0L } catch (e: Exception) { 0L }
        item(R.drawable.ic_archive, 7, t("Manage storage", "స్టోరేజ్ నిర్వహణ"), t("Photos, videos and files take %.1f MB. Tap to free up space (the messages stay).", "ఫోటోలు, వీడియోలు, ఫైల్స్ %.1f MB వాడుతున్నాయి. ఖాళీ చేయడానికి నొక్కండి (మెసేజ్‌లు ఉంటాయి).").format(java.util.Locale.ENGLISH, used / 1_000_000.0)) { freeSpace() }
        item(R.drawable.ic_trash, 3, t("Clear all chats on this phone", "ఈ ఫోన్‌లో అన్ని చాట్‌లు క్లియర్"), t("Messages, photos and files here; chats stay in the list", "ఇక్కడి మెసేజ్‌లు, ఫోటోలు, ఫైల్స్")) { clearAll() }
        head(t("About", "గురించి"))
        rows().addView(ui.text(t("🔒 Habit Chat is locked end to end: messages, photos, files and calls are locked on your phone and opened only on the phones of the people in the chat. Habit's server only passes them on and deletes them once delivered. Calls go straight between the phones (or through Habit's relay, still locked).",
            "🔒 Habit చాట్ ఎండ్ టు ఎండ్ లాక్: మెసేజ్‌లు, ఫోటోలు, ఫైల్స్, కాల్స్ మీ ఫోన్‌లోనే లాక్ అవుతాయి, చాట్‌లో ఉన్నవాళ్ళ ఫోన్‌లలో మాత్రమే తెరుచుకుంటాయి. Habit సర్వర్ వాటిని అందించి, అందాక తొలగిస్తుంది."), 13.5f, C.SUB).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
    }

    private val REQ_CONTACTS = 41
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONTACTS && ChatContacts.allowed(this)) ChatContacts.matchNow(this, store, force = true)
    }

    /** The card the next rows go into (each heading starts a new one). */
    private var card: LinearLayout? = null
    private fun head(s: String) {
        box.addView(ui.text(s.uppercase(java.util.Locale.getDefault()), 12f, C.GOLD_TXT).apply { typeface = Fonts.heavy(context); letterSpacing = 0.07f; setPadding(dp(6), dp(22), dp(6), dp(8)) })
        card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = C.round(C.CARD, dp(22).toFloat(), C.EDGE, dp(1)); setPadding(0, dp(4), 0, dp(4)); clipToOutline = true }
        box.addView(card, LinearLayout.LayoutParams(-1, -2))
    }
    private fun rows() = card ?: box
    /** A small rounded tile with a coloured icon (tint: 0 gold · 1 teal · 2 lilac · 3 coral · 4 sky · 5 green · 6 pink · 7 orange). */
    private fun tile(res: Int, tint: Int): View {
        val (bg, fg) = C.TINTS[tint]
        return Ico.view(this, res, fg, 19, 38, "", C.round(bg, dp(12).toFloat()))
    }

    private fun item(res: Int, tint: Int, title: String, sub: String, onClick: () -> Unit) {
        rows().addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(12), dp(11)); background = Ico.rowRipple(context)
            addView(tile(res, tint), LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(this@ChatSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(6), 0)
                addView(ui.text(title, 15.5f, C.TXT).apply { typeface = Fonts.semi(context) })
                addView(ui.text(sub, 13f, C.SUB).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Ico.view(this@ChatSettingsActivity, R.drawable.ic_chevron_right, C.SUB, 18, 24, ""), LinearLayout.LayoutParams(dp(24), dp(24)))
            setOnClickListener { onClick() }
        })
    }

    private fun toggle(res: Int, tint: Int, title: String, sub: String, on: Boolean, change: (Boolean) -> Unit) {
        rows().addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(10)); background = Ico.rowRipple(context)
            addView(tile(res, tint), LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(this@ChatSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0)
                addView(ui.text(title, 15.5f, C.TXT).apply { typeface = Fonts.semi(context) })
                addView(ui.text(sub, 13f, C.SUB))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this@ChatSettingsActivity).apply {
                isChecked = on
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(C.PRIMARY, if (C.dark) Color.parseColor("#C9CEE6") else Color.WHITE))
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf((C.PRIMARY and 0x00FFFFFF) or (0x80 shl 24), if (C.dark) Color.parseColor("#4A5288") else Color.parseColor("#C9CEE0")))
                setOnCheckedChangeListener { _, c -> change(c) }
            }
            addView(sw)
            setOnClickListener { sw.isChecked = !sw.isChecked }
        })
    }

    private fun themeName(k: String) = when (k) { "dark" -> t("Navy and gold (dark)", "నేవీ, బంగారు (డార్క్)"); "light" -> t("Clean light", "తెలుపు (లైట్)"); else -> t("Automatic: follows the phone's dark mode", "ఆటోమేటిక్: ఫోన్ డార్క్ మోడ్‌ని బట్టి") }
    /** Theme: automatic (the phone's dark mode), navy and gold, or clean light. The open screens change at once. */
    private fun pickTheme() {
        val keys = listOf("auto", "dark", "light")
        AlertDialog.Builder(this).setTitle(t("Theme", "థీమ్"))
            .setSingleChoiceItems(keys.map { themeName(it) }.toTypedArray(), keys.indexOf(ChatTheme.mode(this)).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                if (keys[i] != ChatTheme.mode(this)) { db.setKv("set:theme", keys[i]); ChatSync.changed(); recreate() }
            }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }

    private fun pickWall() {
        val walls = ChatTheme.WALLS
        val cur = db.kv("set:wall").toIntOrNull() ?: ChatTheme.DEFAULT_WALL
        AlertDialog.Builder(this).setTitle(t("🎨 Wallpaper for all chats", "🎨 అన్ని చాట్‌లకు వాల్‌పేపర్"))
            .setSingleChoiceItems(walls.map { if (te) it.te else it.en }.toTypedArray(), walls.indexOfFirst { it.id == cur }) { d, i ->
                db.setKv("set:wall", walls[i].id.toString()); d.dismiss(); draw()
            }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }

    private fun pickDl() {
        val keys = listOf("wifi", "always", "never")
        val labels = arrayOf(t("On Wi-Fi (small ones always)", "Wi-Fi లో (చిన్నవి ఎప్పుడూ)"), t("Always (also on mobile data)", "ఎప్పుడూ (మొబైల్ డేటాలో కూడా)"), t("Only when I tap", "నేను నొక్కినప్పుడు మాత్రమే"))
        AlertDialog.Builder(this).setTitle(t("⬇ Videos and documents", "⬇ వీడియోలు, ఫైల్స్"))
            .setSingleChoiceItems(labels, keys.indexOf(ChatTheme.autoDl(this)).coerceAtLeast(0)) { d, i -> db.setKv("set:autodl", keys[i]); d.dismiss(); draw() }
            .setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }

    private fun blockedList() {
        val blocked = db.convs().filter { it.blocked && !it.isGroup }
        if (blocked.isEmpty()) { android.widget.Toast.makeText(this, t("You haven't blocked anyone.", "మీరు ఎవరినీ బ్లాక్ చేయలేదు."), android.widget.Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle(t("🚫 Blocked · tap to unblock", "🚫 బ్లాక్ · అన్‌బ్లాక్ కోసం నొక్కండి"))
            .setItems(blocked.map { it.emoji + "  " + it.name }.toTypedArray()) { _, i ->
                val c = blocked[i]
                val other = c.members().map { it.optString("uid") }.firstOrNull { it != ChatSync.myUid(this) } ?: return@setItems
                Thread {
                    val r = ChatSync.api(store, "POST", "/api/chat/block", JSONObject().put("uid", other).put("on", false).toString())
                    runOnUiThread { if (r.ok) { db.patchConv(c.cid) { it.put("blocked", false) }; draw() } }
                }.start()
            }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }

    private fun pickFont() {
        val keys = listOf("s", "m", "l")
        val labels = arrayOf(t("Small", "చిన్నది"), t("Medium", "మధ్యస్థం"), t("Large", "పెద్దది"))
        AlertDialog.Builder(this).setTitle(t("🔤 Font size", "🔤 అక్షరాల సైజు"))
            .setSingleChoiceItems(labels, keys.indexOf(db.kv("set:font").ifEmpty { "m" }).coerceAtLeast(0)) { d, i -> db.setKv("set:font", keys[i]); d.dismiss(); draw() }
            .setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }
    private fun pickTimer() {
        val cur = ChatTheme.defaultTimer(this)
        AlertDialog.Builder(this).setTitle(t("⏱ Default message timer", "⏱ కొత్త చాట్‌లకు టైమర్"))
            .setSingleChoiceItems(ChatTheme.TIMERS.map { ChatTheme.timerText(it, te) }.toTypedArray(), ChatTheme.TIMERS.indexOfFirst { it == cur }.coerceAtLeast(0)) { d, i ->
                db.setKv("set:timer", ChatTheme.TIMERS[i].toString()); d.dismiss(); draw()
            }.setNegativeButton(t("Close", "మూసివేయి"), null).show()
    }
    /** Deletes downloaded photos, videos and files (they come again from the chat while the server still has them). */
    private fun freeSpace() {
        val opts = arrayOf(t("Videos and documents", "వీడియోలు, ఫైల్స్"), t("Everything (photos, voice notes and stickers too)", "అన్నీ (ఫోటోలు, వాయిస్, స్టిక్కర్లు కూడా)"))
        AlertDialog.Builder(this).setTitle(t("💾 Free up space", "💾 ఖాళీ చేయండి")).setItems(opts) { _, i ->
            var n = 0
            for (c in db.allConvs()) for (m in db.msgs(c.cid, 100_000)) {
                if (!ChatSync.hasFile(m)) continue
                if (i == 0 && m.kind != "video" && m.kind != "doc") continue
                val f = m.body.optString("local").takeIf { it.isNotEmpty() }?.let { java.io.File(it) } ?: continue
                if (f.exists() && f.delete()) n++
                m.body.remove("local"); db.setBody(m.cid, m.id, m.body)
            }
            android.widget.Toast.makeText(this, t("Freed $n files.", "$n ఫైల్స్ తొలగించాం."), android.widget.Toast.LENGTH_LONG).show()
            ChatSync.changed(); draw()
        }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }

    private fun clearAll() {
        AlertDialog.Builder(this).setMessage(t("Clear the messages, photos and files of all chats on this phone? This can't be undone.", "ఈ ఫోన్‌లో అన్ని చాట్‌ల మెసేజ్‌లు, ఫోటోలు, ఫైల్స్ క్లియర్ చేయాలా? మళ్ళీ రావు."))
            .setPositiveButton(t("Clear all", "అన్నీ క్లియర్")) { _, _ ->
                for (c in db.convs()) db.clear(c.cid, false)
                try { ChatSync.dir(this).listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
                ChatSync.changed(); draw()
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
}
