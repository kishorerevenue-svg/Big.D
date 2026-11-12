package com.soulfriend.pet

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * TEST BUILDS ONLY (src/debug): fills Habit Chat with made-up sample chats (no real people, nothing sent anywhere)
 * and opens one screen in one theme, for the CI's screenshots:
 *   adb shell am start -n <app>/com.soulfriend.pet.DemoActivity --es theme dark|light|auto --es screen list|chat|group|settings|info
 *   (also --es colour teal|purple|orange|green|blue|maroon for the app's colour, and the app's pages: home, tasks, files, vault,
 *   pet, translate, family, govt, feed (the Media tab))
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val db = ChatDb.get(this)
        if (db.kv("demo") != "1") seed(store, db)
        intent.getStringExtra("theme")?.let { db.setKv("set:theme", it) }
        intent.getStringExtra("lang")?.let { store.lang = it }
        intent.getStringExtra("colour")?.let { db.setKv("set:appColour", it) }
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        val go = when (intent.getStringExtra("screen") ?: "list") {
            "chat" -> Intent(this, ChatActivity::class.java).putExtra("cid", "dm-ravi")
            "group" -> Intent(this, ChatActivity::class.java).putExtra("cid", "g-office")
            "ai" -> Intent(this, ChatActivity::class.java).putExtra("cid", ChatDb.AI)
            "settings" -> Intent(this, ChatSettingsActivity::class.java)
            "info" -> Intent(this, ChatInfoActivity::class.java).putExtra("cid", "g-office").putExtra("mode", "info")
            "contact" -> Intent(this, ChatInfoActivity::class.java).putExtra("cid", "dm-ravi").putExtra("mode", "info")
            "media" -> Intent(this, ChatInfoActivity::class.java).putExtra("cid", "dm-ravi").putExtra("mode", "media")
            "starred" -> Intent(this, ChatInfoActivity::class.java).putExtra("cid", "").putExtra("mode", "starred")
            "devices" -> Intent(this, LinkedDevicesActivity::class.java)
            "home", "tasks", "vault", "pet", "translate", "family", "govt" -> Intent(this, MainActivity::class.java).putExtra("tab", intent.getStringExtra("screen"))
            "files" -> { store.letterMode = "public"; Intent(this, MainActivity::class.java).putExtra("tab", "files") }
            "office" -> { store.letterMode = "office"; Intent(this, MainActivity::class.java).putExtra("tab", "files") }
            "feed" -> Intent(this, MainActivity::class.java).putExtra("tab", "feedback")
            "login" -> Intent(this, LoginActivity::class.java)
            else -> Intent(this, MainActivity::class.java).putExtra("tab", "messages")
        }
        startActivity(go.addFlags(flags))
        finish()
    }

    private fun seed(store: Store, db: ChatDb) {
        store.loggedIn = true; store.onboarded = true; store.lang = "en"
        store.server = "http://127.0.0.1:9"          // nothing goes anywhere
        // a few to-dos and reminders for Home
        store.addTodo("Send the survey report to the RDO office"); store.addTodo("Check the pension list"); store.addTodo("Call the VRO about Survey No. 214")
        store.addReminder(System.currentTimeMillis() + 2 * 3600_000L, "Call the RDO office")
        store.addReminder(System.currentTimeMillis() + 26 * 3600_000L, "Take BP medicine", "daily")
        val me = "me1"
        db.setKv("me", JSONObject().put("uid", me).put("name", "Kishore Kumar").put("handle", "kishore").put("emoji", "🐾").toString())
        db.setKv("ready", "1")
        db.ensureSelf("My notes"); db.ensureAi()
        val now = System.currentTimeMillis()
        val min = 60_000L; val hour = 60 * min; val day = 24 * hour
        fun person(uid: String, name: String) = JSONObject().put("uid", uid).put("name", name).put("emoji", "🐾").put("handle", name.lowercase().replace(" ", "").take(10))
        fun conv(cid: String, kind: String, name: String, members: List<JSONObject>, st: JSONObject = JSONObject(), admins: List<String> = emptyList(), emoji: String = "🐾") =
            db.putConv(JSONObject().put("cid", cid).put("kind", kind).put("name", name).put("emoji", emoji).put("accepted", true).put("member", true)
                .put("members", JSONArray(members)).put("admins", JSONArray(admins)).put("st", st).put("at", now - 30 * day).put("last", now - 30 * day))
        var n = 0
        fun msg(cid: String, sender: String, at: Long, kind: String, body: JSONObject, seq: Long = (++n).toLong(), state: Int = 1): String {
            val id = "m$n"
            db.putMsg(ChatMsg(cid, id, seq, sender, at, kind, body.put("t", kind), state))
            return id
        }
        val ravi = person("ravi", "Ravi Teja"); val sita = person("sita", "Sita Devi"); val suresh = person("suresh", "Suresh VRO")
        val amma = person("amma", "Amma"); val lakshmi = person("lakshmi", "Lakshmi")
        val mine = person(me, "Kishore Kumar")

        // a picture for the photo message (a little map, drawn here)
        val pic = File(filesDir, "demo_map.jpg")
        run {
            val b = Bitmap.createBitmap(800, 520, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawColor(Color.parseColor("#173F48"))
            p.style = Paint.Style.STROKE; p.strokeWidth = 34f; p.color = Color.parseColor("#3A88A6")
            c.drawPath(Path().apply { moveTo(0f, 360f); cubicTo(180f, 300f, 300f, 430f, 470f, 370f); cubicTo(620f, 320f, 720f, 330f, 800f, 340f) }, p)
            p.strokeWidth = 14f; p.color = Color.parseColor("#38FFFFFF"); c.drawLine(70f, 0f, 230f, 520f, p); c.drawLine(0f, 150f, 800f, 190f, p)
            p.style = Paint.Style.FILL; p.color = Color.parseColor("#2EF5C451")
            c.drawPath(Path().apply { moveTo(330f, 210f); lineTo(500f, 225f); lineTo(485f, 320f); lineTo(315f, 300f); close() }, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 8f; p.color = Color.parseColor("#F5C451")
            c.drawPath(Path().apply { moveTo(330f, 210f); lineTo(500f, 225f); lineTo(485f, 320f); lineTo(315f, 300f); close() }, p)
            pic.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        }
        val voice = File(filesDir, "demo_voice.m4a"); voice.writeBytes(ByteArray(64))

        // ---- Ravi Teja: a one-to-one chat ----
        conv("dm-ravi", "dm", "Ravi Teja", listOf(mine, ravi), JSONObject().put("ravi", JSONObject().put("d", 9).put("r", 7)))
        msg("dm-ravi", "ravi", now - day - 2 * hour, "text", JSONObject().put("text", "Sir, the survey started in Pendurthi village this morning."))
        msg("dm-ravi", me, now - day - 2 * hour + 5 * min, "text", JSONObject().put("text", "Very good. Send me the report when it's ready 👍"))
        val doc = msg("dm-ravi", "ravi", now - 3 * hour, "doc", JSONObject().put("name", "Survey_Report_Pendurthi.pdf").put("size", 2_400_000).put("mime", "application/pdf"))
        msg("dm-ravi", me, now - 3 * hour + 8 * min, "text", JSONObject().put("text", "Good. Please also add the map.").put("re", JSONObject().put("id", doc).put("who", "ravi").put("p", "📄 Survey_Report_Pendurthi.pdf")))
        msg("dm-ravi", "ravi", now - 2 * hour, "photo", JSONObject().put("w", 800).put("h", 520).put("local", pic.absolutePath).put("text", "Map added, sir")
            .put("_rx", JSONObject().put(me, JSONObject().put("e", "❤️"))))
        msg("dm-ravi", me, now - 2 * hour + 4 * min, "voice", JSONObject().put("dur", 18).put("local", voice.absolutePath))
        msg("dm-ravi", "ravi", now - 40 * min, "text", JSONObject().put("text", "Noted sir. I'll finish the remaining part by evening and send it to the RDO office.").put("_star", true))
        msg("dm-ravi", me, now - 25 * min, "text", JSONObject().put("text", "Thank you, Ravi 🙏"))
        db.touch("dm-ravi", now - 25 * min, "Thank you, Ravi 🙏", false)
        db.setKv("pinmsg:dm-ravi", JSONObject().put("id", doc).put("until", 0).toString())
        db.putTask("dm-ravi", JSONObject().put("tid", "t-r1").put("title", "Submit survey report to RDO").put("to", me).put("by", "ravi").put("due", now + 3 * hour).put("state", "open").put("at", now - 3 * hour))
        db.putTask("dm-ravi", JSONObject().put("tid", "t-r2").put("title", "Collect Form 8 signatures").put("to", "ravi").put("by", me).put("state", "open").put("at", now - 2 * hour))

        // ---- Mandal Office: a group ----
        conv("g-office", "group", "Mandal Office", listOf(mine, sita, suresh, ravi), JSONObject().put("sita", JSONObject().put("d", 99).put("r", 99)).put("suresh", JSONObject().put("d", 99).put("r", 50)).put("ravi", JSONObject().put("d", 99).put("r", 99)), listOf(me), "👥")
        msg("g-office", "sita", now - 5 * hour, "text", JSONObject().put("text", "Good morning all. Meeting at 4 PM in the hall today."))
        msg("g-office", "sita", now - 4 * hour, "task", JSONObject().put("tid", "t1").put("action", "new").put("title", "Submit survey report to RDO"))
        db.putTask("g-office", JSONObject().put("tid", "t1").put("title", "Submit survey report to RDO").put("note", "With the map and the village list").put("to", me).put("by", "sita").put("due", now + 3 * hour).put("state", "open").put("at", now - 4 * hour))
        msg("g-office", "suresh", now - 3 * hour, "poll", JSONObject().put("q", "Which day for the grama sabha?").put("o", JSONArray(listOf("Monday", "Wednesday", "Friday"))).put("multi", false)
            .put("_votes", JSONObject().put(me, JSONObject().put("ch", JSONArray(listOf(1)))).put("sita", JSONObject().put("ch", JSONArray(listOf(1)))).put("suresh", JSONObject().put("ch", JSONArray(listOf(0))))))
        msg("g-office", me, now - 2 * hour, "event", JSONObject().put("title", "Grama sabha meeting").put("at", now + day).put("place", "Panchayat office").put("note", "Bring the voter list")
            .put("_rsvp", JSONObject().put("sita", JSONObject().put("a", "y")).put(me, JSONObject().put("a", "y"))))
        msg("g-office", "suresh", now - 90 * min, "loc", JSONObject().put("lat", 17.8156).put("lng", 83.2097).put("name", "Pendurthi Mandal Office").put("acc", 12))
        val ask = msg("g-office", me, now - 60 * min, "text", JSONObject().put("text", "@Sita_Devi please share the voter list before the meeting.").put("mn", JSONArray(listOf("sita"))))
        msg("g-office", "sita", now - 20 * min, "text", JSONObject().put("text", "Sure sir, sending it now.").put("re", JSONObject().put("id", ask).put("who", me).put("p", "@Sita_Devi please share the voter list before the meeting.")))
        db.touch("g-office", now - 20 * min, "Sure sir, sending it now.", true); db.touch("g-office", now - 20 * min, "Sure sir, sending it now.", true); db.touch("g-office", now - 20 * min, "Sure sir, sending it now.", true)
        db.setKv("pin:g-office", (now - 1000).toString())

        // ---- more chats for the list ----
        conv("dm-amma", "dm", "Amma", listOf(mine, amma))
        msg("dm-amma", "amma", now - 4 * hour - 10 * min, "voice", JSONObject().put("dur", 42))
        db.touch("dm-amma", now - 4 * hour - 10 * min, "🎤 Voice note", true)
        conv("g-field", "group", "Field Team", listOf(mine, suresh, ravi), JSONObject(), listOf(me), "👥")
        msg("g-field", "suresh", now - day - 3 * hour, "photo", JSONObject().put("w", 800).put("h", 520).put("text", "Survey No. 214"))
        db.touch("g-field", now - day - 3 * hour, "📷 Survey No. 214", false)
        conv("dm-suresh", "dm", "Suresh (VRO)", listOf(mine, suresh))
        msg("dm-suresh", "suresh", now - day - 5 * hour, "text", JSONObject().put("text", "Report reached the collectorate, sir"))
        db.touch("dm-suresh", now - day - 5 * hour, "Report reached the collectorate, sir", false)
        conv("dm-lakshmi", "dm", "Lakshmi", listOf(mine, lakshmi))
        msg("dm-lakshmi", "lakshmi", now - 3 * day, "loc", JSONObject().put("lat", 17.7).put("lng", 83.3).put("name", "Home"))
        db.touch("dm-lakshmi", now - 3 * day, "📍 Home", false)
        db.putMsg(ChatMsg(ChatDb.AI, "ai1", 0, me, now - 6 * hour, "text", JSONObject().put("t", "text").put("text", "Remind me at 9 PM every day to update the diary"), 1))
        db.putMsg(ChatMsg(ChatDb.AI, "ai2", 0, "ai", now - 6 * hour + min, "text", JSONObject().put("t", "text").put("text", "Done! Reminder set for 9 PM every day. ⏰"), 1))
        db.touch(ChatDb.AI, now - 6 * hour + min, "Done! Reminder set for 9 PM every day. ⏰", true)
        db.setKv("demo", "1")
    }
}
