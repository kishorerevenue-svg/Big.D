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
        super.onCreate(savedInstanceState)
        store = Store(this); ui = Ui(this)
        window.statusBarColor = C.NAVY
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.NAVY); fitsSystemWindows = true }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(12), dp(8)); setBackgroundColor(C.BAR)
            addView(TextView(this@ChatSettingsActivity).apply { text = "←"; textSize = 22f; setTextColor(C.TXT); gravity = Gravity.CENTER; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(ui.text(t("Chat settings", "చాట్ సెట్టింగ్స్"), 19f, C.TXT, bold = true))
        })
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(32)) }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        draw()
    }

    private fun draw() {
        box.removeAllViews()
        val me = ChatSync.me(this)
        // who I am
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(14), dp(4), dp(14))
            addView(TextView(this@ChatSettingsActivity).apply { text = me.optString("emoji", "🐾"); textSize = 26f; gravity = Gravity.CENTER; background = ChatTheme.circle(C.CARD) }, LinearLayout.LayoutParams(dp(58), dp(58)))
            addView(LinearLayout(this@ChatSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0)
                addView(ui.text(me.optString("name", "Habit friend"), 18f, C.TXT, bold = true))
                if (me.optString("handle").isNotEmpty()) addView(ui.text("@" + me.optString("handle") + " · " + t("change in Media › 👤", "మీడియా › 👤 లో మార్చండి"), 13f, C.SUB))
            })
        })
        line()
        head(t("Look", "రూపం"))
        val wall = ChatTheme.wall(db.kv("set:wall").toIntOrNull() ?: 0)
        item("🎨", t("Chat wallpaper", "చాట్ వాల్‌పేపర్"), if (te) wall.te else wall.en) { pickWall() }
        toggle("📋", t("Show open tasks as a bar at the top", "తెరిచిన పనులు పైన బార్‌గా చూపించు"),
            t("Off: a small 📋 next to the chat's name shows them", "ఆఫ్: చాట్ పేరు పక్కన చిన్న 📋 చూపిస్తుంది"), ChatTheme.tasksBar(this)) { db.setKv("set:tasksbar", if (it) "1" else "0") }
        line()
        head(t("Notifications", "నోటిఫికేషన్లు"))
        toggle("🔔", t("Message notifications", "మెసేజ్ నోటిఫికేషన్లు"), t("New messages show a notification", "కొత్త మెసేజ్‌లకు నోటిఫికేషన్"), ChatTheme.notifyOn(this)) { db.setKv("set:notify", if (it) "1" else "0") }
        toggle("👁", t("Show the message in the notification", "నోటిఫికేషన్‌లో మెసేజ్ చూపించు"), t("Off: only \"New message\"", "ఆఫ్: \"కొత్త మెసేజ్\" మాత్రమే"), ChatTheme.previewOn(this)) { db.setKv("set:preview", if (it) "1" else "0") }
        item("🎵", t("Ringtone for calls", "కాల్స్ రింగ్‌టోన్"), t("Phone settings › Habit calls (ringing)", "ఫోన్ సెట్టింగ్స్ › Habit calls (ringing)")) {
            CallManager.channels(this)
            try { startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CallManager.RING)) } catch (e: Exception) { }
        }
        line()
        head(t("Privacy", "గోప్యత"))
        toggle("✓✓", t("Read receipts (blue ticks)", "చదివిన గుర్తు (నీలి టిక్కులు)"), t("Off: people don't see when you read their messages", "ఆఫ్: మీరు చదివారని ఇతరులకు తెలియదు"), ChatTheme.receiptsOn(this)) { db.setKv("set:receipts", if (it) "1" else "0") }
        toggle("🔒", t("Lock hidden chats", "దాచిన చాట్‌లకు లాక్"), t("Open them with your fingerprint or phone lock", "వేలిముద్ర లేదా ఫోన్ లాక్‌తో తెరవండి"), ChatTheme.lockHidden(this)) { db.setKv("set:lock", if (it) "1" else "0") }
        val blocked = db.convs().filter { it.blocked && !it.isGroup }
        item("🚫", t("Blocked people", "బ్లాక్ చేసినవాళ్ళు"), if (blocked.isEmpty()) t("None", "ఎవరూ లేరు") else blocked.joinToString(", ") { it.name }) { blockedList() }
        line()
        head(t("Storage and data", "స్టోరేజ్, డేటా"))
        val dl = ChatTheme.autoDl(this)
        item("⬇", t("Download videos and documents", "వీడియోలు, ఫైల్స్ డౌన్‌లోడ్"), when (dl) { "always" -> t("Always (also on mobile data)", "ఎప్పుడూ (మొబైల్ డేటాలో కూడా)"); "never" -> t("Only when I tap", "నేను నొక్కినప్పుడు మాత్రమే"); else -> t("On Wi-Fi (small ones always)", "Wi-Fi లో (చిన్నవి ఎప్పుడూ)") }) { pickDl() }
        item("🧹", t("Clear all chats on this phone", "ఈ ఫోన్‌లో అన్ని చాట్‌లు క్లియర్"), t("Messages, photos and files here; chats stay in the list", "ఇక్కడి మెసేజ్‌లు, ఫోటోలు, ఫైల్స్")) { clearAll() }
        line()
        head(t("About", "గురించి"))
        box.addView(ui.text(t("🔒 Habit Chat is locked end to end: messages, photos, files and calls are locked on your phone and opened only on the phones of the people in the chat. Habit's server only passes them on and deletes them once delivered. Calls go straight between the phones (or through Habit's relay, still locked).",
            "🔒 Habit చాట్ ఎండ్ టు ఎండ్ లాక్: మెసేజ్‌లు, ఫోటోలు, ఫైల్స్, కాల్స్ మీ ఫోన్‌లోనే లాక్ అవుతాయి, చాట్‌లో ఉన్నవాళ్ళ ఫోన్‌లలో మాత్రమే తెరుచుకుంటాయి. Habit సర్వర్ వాటిని అందించి, అందాక తొలగిస్తుంది."), 13.5f, C.SUB).apply { setPadding(dp(4), dp(6), dp(4), dp(6)) })
    }

    private fun head(s: String) = box.addView(ui.text(s, 13.5f, C.GOLD, bold = true).apply { setPadding(dp(4), dp(16), dp(4), dp(4)) })
    private fun line() = box.addView(View(this).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(6) })

    private fun item(icon: String, title: String, sub: String, onClick: () -> Unit) {
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), dp(4), dp(12))
            addView(ui.text(icon, 19f, C.TXT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), -2))
            addView(LinearLayout(this@ChatSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                addView(ui.text(title, 16f, C.TXT))
                addView(ui.text(sub, 13f, C.SUB).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { onClick() }
        })
    }

    private fun toggle(icon: String, title: String, sub: String, on: Boolean, change: (Boolean) -> Unit) {
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(10))
            addView(ui.text(icon, 17f, C.TXT).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(dp(40), -2))
            addView(LinearLayout(this@ChatSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0)
                addView(ui.text(title, 16f, C.TXT))
                addView(ui.text(sub, 13f, C.SUB))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val sw = Switch(this@ChatSettingsActivity).apply {
                isChecked = on
                thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(C.GOLD, Color.parseColor("#C9CEE6")))
                trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(C.GOLD_DARK, Color.parseColor("#4A5288")))
                setOnCheckedChangeListener { _, c -> change(c) }
            }
            addView(sw)
            setOnClickListener { sw.isChecked = !sw.isChecked }
        })
    }

    private fun pickWall() {
        val walls = ChatTheme.WALLS
        val cur = db.kv("set:wall").toIntOrNull() ?: 0
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

    private fun clearAll() {
        AlertDialog.Builder(this).setMessage(t("Clear the messages, photos and files of all chats on this phone? This can't be undone.", "ఈ ఫోన్‌లో అన్ని చాట్‌ల మెసేజ్‌లు, ఫోటోలు, ఫైల్స్ క్లియర్ చేయాలా? మళ్ళీ రావు."))
            .setPositiveButton(t("Clear all", "అన్నీ క్లియర్")) { _, _ ->
                for (c in db.convs()) db.clear(c.cid, false)
                try { ChatSync.dir(this).listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
                ChatSync.changed(); draw()
            }.setNegativeButton(t("Cancel", "వద్దు"), null).show()
    }
}
