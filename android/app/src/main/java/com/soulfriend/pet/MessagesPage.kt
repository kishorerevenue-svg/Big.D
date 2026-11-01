package com.soulfriend.pet

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Messages page: check any message for fraud, see new messages with 🟢 🟡 🔴 labels, and reply settings. */
class MessagesPage(private val m: MainActivity) {
    private var input = ""
    private var checking = false
    private var verdict: ScamCheck.Verdict? = null
    private val ui get() = m.ui
    private val store get() = m.store
    private fun LinearLayout.put(v: View, top: Int = 10) = addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = m.dp(top) })

    /** Text shared to BigD AI from another app: check it right away. */
    fun checkShared(text: String) { input = text.take(3000); verdict = null; check() }

    fun build() {
        m.pageHeader("Messages · మెసేజ్‌లు", "Is a message safe or a fraud? Check it here. Dangerous messages get a red warning.")

        m.add(ui.section("🛡️ Is this message safe?", "Paste a message you got, or share it to BigD AI. I'll tell you if it's safe or a fraud.").apply {
            val box = ui.input("Paste or type the message here…", input, multi = true)
            box.onText { input = it }
            put(box, 10)
            put(ui.row(ui.button("📋 Paste") {
                val clip = try { m.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(m)?.toString() } catch (e: Exception) { null }
                if (clip.isNullOrBlank()) m.toast("Nothing copied yet. Long-press a message › Copy, then come back.") else { input = clip; box.setText(clip) }
            }, ui.button(if (checking) "Checking…" else "🛡️ Check it", "primary") { if (!checking) check() }), 8)
            put(ui.text("Tip: in WhatsApp or SMS, long-press the message › Copy, then tap 📋 Paste. Or tap Share › BigD AI.", 12.5f, Ui.MUTED), 8)
        }, 12)
        verdict?.let { m.add(result(it, input), 10) }

        if (BuildConfig.MESSAGE_REPLIES) {
            val access = MessageHub.accessGranted(m)
            m.add(ui.section("Automatic check · ఆటోమేటిక్ చెక్", "Every new message gets a 🟢 🟡 🔴 label here. A dangerous one shows a red warning at once.").apply {
                if (!access || !store.msgWatch) {
                    put(ui.text("○ Off. Turn it on to check every new WhatsApp and SMS message.", 14f, Ui.INK), 10)
                    put(ui.button("Turn on", "primary") { m.showMessageDisclosure() }, 8)
                    if (!access) put(ui.text("If Android says \"Restricted setting\": open Settings › Apps › BigD AI › ⋮ › Allow restricted settings, then tap Turn on again.", 12f, Ui.MUTED), 6)
                } else {
                    put(ui.text("✅ On for " + MessageHub.APPS.filter { it.first in store.msgApps }.joinToString(", ") { it.second }, 14f, Ui.GOOD, bold = true), 10)
                    put(ui.check("🛡️ Red warning for dangerous messages", store.scamAlerts) { store.scamAlerts = it }, 6)
                }
            }, 12)
        } else {
            m.add(ui.card(Ui.SUN_SOFT).apply {
                addView(ui.text("📲 Automatic checking", 15f, Ui.INK, bold = true))
                put(ui.text("Checking every new WhatsApp and SMS message by itself comes with the Play Store version of BigD AI " +
                    "(Android lets only Play Store apps read message notifications). For now, copy or share a message and check it above.", 13.5f, Ui.MUTED), 4)
            }, 12)
        }

        val list = store.msgLog().reversed()
        m.add(ui.label("Recent messages"), 20)
        if (list.isEmpty()) m.add(ui.text(if (BuildConfig.MESSAGE_REPLIES) "New messages will appear here with a 🟢 🟡 🔴 safety label." else "Messages you check will appear here.", 14f, Ui.MUTED), 8)
        list.take(40).forEach { m.add(row(it), 8) }
        if (list.isNotEmpty()) m.add(ui.button("Clear the list", "ghost") {
            AlertDialog.Builder(m).setMessage("Remove all messages from this list?").setPositiveButton("Clear") { _, _ -> store.clearMsgs(); m.render() }.setNegativeButton("Cancel", null).show()
        }, 10)

        if (BuildConfig.MESSAGE_REPLIES) m.add(m.messagesCard(), 18)
    }

    private fun check() {
        val t = input.trim()
        if (t.length < 5) { m.toast("Paste or type a message first."); return }
        checking = true; verdict = null; m.render()
        m.io.execute {
            val v = ScamCheck.checkBlocking(store, t, "", "")
            ScamCheck.log(store, "manual_" + System.currentTimeMillis(), "Checked by you", "You", t, v, "manual")
            m.runOnUiThread {
                checking = false; verdict = v; m.render()
                if (v.level == "danger") dangerDialog(v, t)
            }
        }
    }

    /** The result as a coloured card. */
    private fun result(v: ScamCheck.Verdict, text: String): View = LinearLayout(m).apply {
        orientation = LinearLayout.VERTICAL; setPadding(m.dp(16), m.dp(14), m.dp(16), m.dp(14))
        background = ui.round(ScamCheck.soft(v.level), 20, ScamCheck.color(v.level))
        addView(ui.text(ScamCheck.label(v.level), 18f, ScamCheck.color(v.level), bold = true))
        if (v.title.isNotBlank()) put(ui.text(v.title, 16f, Ui.INK, bold = true), 6)
        if (v.reasons.isNotEmpty()) put(ui.text("Why · ఎందుకు\n" + v.reasons.joinToString("\n") { "• $it" }, 14f, Ui.INK), 8)
        if (v.advice.isNotEmpty()) put(ui.text("What to do · ఏం చేయాలి\n" + v.advice.joinToString("\n") { "✓ $it" }, 14f, Ui.INK), 8)
        if (v.level == "danger") put(helpButtons(), 12)
        if (v.offline) put(ui.text("Checked on your phone only. Connect to the internet for a full check.", 12f, Ui.MUTED), 8)
        else put(ui.text("🚩 Wrong result? Tell the BigD team", 12.5f, Ui.MUTED).apply {
            setPadding(0, m.dp(4), 0, m.dp(4))
            setOnClickListener { m.reportAi("message check", listOf(ScamCheck.label(v.level), v.title, v.reasons.joinToString("; "), v.advice.joinToString("; ")).filter { it.isNotBlank() }.joinToString("\n"), "") }
        }, 10)
    }

    private fun helpButtons() = ui.row(
        ui.button("📞 Call 1930", "danger") { m.leavingForSystemScreen = true; try { m.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))) } catch (e: Exception) { } },
        ui.button("🚩 Report") { m.leavingForSystemScreen = true; try { m.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://sancharsaathi.gov.in/sfc/"))) } catch (e: Exception) { } })

    /** A big red window for dangerous messages. */
    fun dangerDialog(v: ScamCheck.Verdict, text: String) {
        val red = ScamCheck.color("danger")
        val box = LinearLayout(m).apply {
            orientation = LinearLayout.VERTICAL; setPadding(m.dp(20), m.dp(16), m.dp(20), m.dp(8))
            addView(ui.text("⚠️  DANGER · ప్రమాదం", 20f, Color.WHITE, bold = true).apply {
                setPadding(m.dp(12), m.dp(8), m.dp(12), m.dp(8)); background = ui.round(red, 12)
            })
            put(ui.text(v.title.ifBlank { "This looks like a fraud message" }, 17f, red, bold = true), 12)
            put(ui.text("“" + text.take(200) + "”", 14f, Ui.INK).apply { setPadding(m.dp(10), m.dp(8), m.dp(10), m.dp(8)); background = ui.round(ScamCheck.soft("danger"), 12) }, 8)
            if (v.advice.isNotEmpty()) put(ui.text(v.advice.joinToString("\n") { "✓ $it" }, 14.5f, Ui.INK, bold = true), 10)
            put(helpButtons(), 12)
        }
        AlertDialog.Builder(m).setView(box).setPositiveButton("OK, I understand", null).show()
    }

    private fun row(o: JSONObject): View {
        val level = o.optString("level")
        val v = o.optJSONObject("v")?.let { ScamCheck.Verdict.from(it) }
        val at = Instant.ofEpochMilli(o.optLong("at")).atZone(ZoneId.systemDefault())
        return LinearLayout(m).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.round(Color.WHITE, 16, Ui.LINE)
            addView(View(m).apply { background = ui.round(ScamCheck.color(level), 3) }, LinearLayout.LayoutParams(m.dp(6), -1).apply { setMargins(m.dp(8), m.dp(10), 0, m.dp(10)) })
            addView(LinearLayout(m).apply {
                orientation = LinearLayout.VERTICAL; setPadding(m.dp(12), m.dp(10), m.dp(14), m.dp(10))
                addView(ui.line(ui.text(o.optString("sender").ifBlank { "Unknown" } + " · " + o.optString("app"), 14.5f, Ui.INK, bold = true),
                    ui.text(m.fmt.format(at), 12f, Ui.MUTED)))
                addView(ui.text(ScamCheck.label(level) + (if (v != null && level != "safe" && v.title.isNotBlank()) " · " + v.title else ""), 13f, ScamCheck.color(level), bold = true))
                addView(ui.text(o.optString("text"), 14f, Ui.INK).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { details(o) }
        }
    }

    private fun details(o: JSONObject) {
        val id = o.optString("id")
        val v = o.optJSONObject("v")?.let { ScamCheck.Verdict.from(it) }
        val msg = StringBuilder()
        msg.append(o.optString("text")).append("\n\n")
        if (v != null) {
            msg.append(ScamCheck.label(v.level)).append("\n")
            if (v.title.isNotBlank()) msg.append(v.title).append("\n")
            v.reasons.forEach { msg.append("• ").append(it).append("\n") }
            v.advice.forEach { msg.append("✓ ").append(it).append("\n") }
        } else msg.append("Not checked yet.")
        val live = MessageHub.recent(id)
        val b = AlertDialog.Builder(m).setTitle(o.optString("sender").ifBlank { "Message" } + " · " + o.optString("app")).setMessage(msg.toString().trim())
            .setNeutralButton("Remove") { _, _ -> store.removeMsg(id); m.render() }
        if (live?.action != null && PetService.running && v?.level != "danger") b.setPositiveButton("✍️ Reply") { _, _ ->
            m.startService(Intent(m, PetService::class.java).setAction(PetService.ACTION_REPLY).putExtra("id", id)); m.moveTaskToBack(true)
        } else b.setPositiveButton("Check again") { _, _ ->
            m.io.execute {
                val nv = ScamCheck.checkBlocking(store, o.optString("text"), o.optString("sender"), o.optString("app"))
                ScamCheck.log(store, id, o.optString("app"), o.optString("sender"), o.optString("text"), nv, o.optString("src", "manual"))
                m.runOnUiThread { m.render(); if (nv.level == "danger") dangerDialog(nv, o.optString("text")) }
            }
        }
        b.setNegativeButton("Close", null).show()
    }
}
