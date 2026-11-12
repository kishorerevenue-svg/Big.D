package com.soulfriend.pet

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

/** Talks to your server, which talks to Claude. The API key stays on the server. */
class Brain(private val store: Store) {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val history = mutableListOf<JSONObject>()

    fun clear() = history.clear()

    /** [photo] = Pair(mediaType, base64) to ask about a picture.
     *  [local]: when the AI can't be reached, let the phone's own small brain ([LocalBrain]) answer simple requests. */
    fun ask(text: String, onReply: (JSONObject) -> Unit, onError: (String) -> Unit, photo: Pair<String, String>? = null, local: Boolean = true) {
        history.add(JSONObject().put("role", "user").put("content", Privacy.maybe(store, text)))
        while (history.size > 12) history.removeAt(0)
        val messages = JSONArray(); history.forEach { messages.put(it) }
        val todos = JSONArray(); store.todos().forEach { todos.put(JSONObject().put("text", it.text).put("done", it.done)) }
        val notes = JSONArray(); store.notes().takeLast(10).forEach { notes.put(it) }
        val body = JSONObject()
            .put("messages", messages).put("lang", store.lang).put("pet", store.petKind)
            .put("now", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("tz", ZoneId.systemDefault().id)
            .put("todos", todos).put("notes", notes)
            .put("memories", JSONArray().also { a -> store.memories().forEach { a.put(it) } })
            .put("learn", store.learnShare)
        if (photo != null) body.put("image", JSONObject().put("mediaType", photo.first).put("data", photo.second))
        val server = store.server
        if (server.contains("YOUR-DOMAIN") || !server.startsWith("https://")) {
            history.removeAt(history.lastIndex)
            main.post { onError("I don't have a brain yet! Set up your server, then type its address in the Habit AI app (Server address).") }
            return
        }
        pool.execute {
            try {
                val (code, out) = post("$server/api/pet", body.toString())
                val json = try { JSONObject(out) } catch (e: Exception) { JSONObject() }
                if (code in 200..299 && !json.has("reply")) main.post {
                    history.removeAt(history.lastIndex)
                    onError("That server address is not a Habit AI server. Check the address in the Habit AI app.")
                } else if (code in 200..299) {
                    main.post {
                        // the server's own brain answered (the big AI is away): reminders, alarms, calls and apps are still
                        // done by the phone's small brain, which knows how; everything else is the own brain's answer
                        val dev = if (json.optBoolean("own") && photo == null) (try { LocalBrain.device(store.ctx, store, text) } catch (e: Exception) { null }) else null
                        if (dev == null && json.optBoolean("weak") && photo == null && local) {
                            // the server had no real answer this time: the phone tries too (the weather here, news,
                            // Wikipedia, Habit Brain), and only then the server's kind "here's what I can do"
                            history.removeAt(history.lastIndex)
                            offline(text, onReply) { keep(text, json); onReply(json) }
                            return@post
                        }
                        val j = dev ?: json
                        if (photo != null) history[history.lastIndex] = JSONObject().put("role", "user").put("content", "[I showed you a photo] $text")
                        history.add(JSONObject().put("role", "assistant").put("content", j.optString("reply", "Woof!")))
                        onReply(j)
                    }
                } else main.post {
                    history.removeAt(history.lastIndex)
                    val err = json.optString("error", "Something went wrong (error $code). Please try again.")
                    if (local && photo == null && (code == 429 || code >= 500)) offline(text, onReply) { onError(err + more(code >= 500)) } else onError(err)
                }
            } catch (e: Exception) {
                main.post {
                    if (history.isNotEmpty()) history.removeAt(history.lastIndex)
                    val err = "No connection. Check your internet and try again."
                    if (local && photo == null) offline(text, onReply) { onError(err + more(true)) } else onError(err)
                }
            }
        }
    }

    /** The AI couldn't answer: the phone's own small brain tries (reminders, alarms, calls, apps, time, sums…),
     *  then the phone reads the internet by itself (weather, news, Wikipedia). [fail] runs when neither can help. */
    private fun offline(text: String, onReply: (JSONObject) -> Unit, fail: () -> Unit) {
        val give = { j: JSONObject -> keep(text, j); onReply(j) }
        // reminders, alarms, calls, apps, time, sums, fraud check: the phone's small brain does these itself
        val dev = try { LocalBrain.device(store.ctx, store, text) } catch (e: Exception) { null }
        if (dev != null) { give(dev); return }
        pool.execute {
            // the internet may still be there (only the server is away): weather, news, Wikipedia
            val w = if (online()) (try { LocalWeb.answer(store.ctx, store, text) } catch (e: Exception) { null }) else null
            main.post {
                val small = { (try { LocalBrain.answer(store.ctx, store, text) } catch (e: Exception) { null })?.let(give) ?: fail() }
                when {
                    w != null -> give(w)
                    // no internet at all: the tiny brain on the phone talks (Habit Brain app, if it's there)
                    TinyBrain.installed(store.ctx) -> TinyBrain.ask(store.ctx, TinyBrain.system(store), text) { t ->
                        if (t == null) small() else give(JSONObject().put("reply", t).put("emotion", "happy").put("actions", JSONArray()).put("tiny", true))
                    }
                    else -> small()
                }
            }
        }
    }

    private fun online(): Boolean = try {
        val cm = store.ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (e: Exception) { true }

    private fun keep(text: String, j: JSONObject) {
        history.add(JSONObject().put("role", "user").put("content", Privacy.maybe(store, text)))
        history.add(JSONObject().put("role", "assistant").put("content", j.optString("reply")))
        while (history.size > 12) history.removeAt(0)
    }

    private fun more(show: Boolean) = if (show) "\n\n" + LocalBrain.hint(store) else ""

    /** Asks the server to write a reply to a chat message. [onDraft] gets (reply, warning). */
    fun draftReply(m: Incoming, instruction: String, auto: Boolean, onDraft: (String, String) -> Unit, onError: (String) -> Unit) {
        val server = store.server
        if (server.contains("YOUR-DOMAIN") || !server.startsWith("https://")) { main.post { onError("Set your server address in the Habit AI app first.") }; return }
        val body = JSONObject()
            .put("app", m.app).put("chat", m.chat).put("sender", m.sender).put("group", m.group)
            .put("text", Privacy.maybe(store, m.text)).put("instruction", Privacy.maybe(store, instruction)).put("auto", auto)
        if (!auto) body.put("memories", JSONArray().also { a -> store.memories().forEach { a.put(it) } })
        pool.execute {
            try {
                val (code, out) = post("$server/api/reply", body.toString())
                val json = try { JSONObject(out) } catch (e: Exception) { JSONObject() }
                val reply = json.optString("reply", "")
                if (code in 200..299 && reply.isNotBlank()) main.post { onDraft(reply, json.optString("warning", "")) }
                else main.post { onError(json.optString("error", "Couldn't write a reply (error $code).")) }
            } catch (e: Exception) {
                main.post { onError("No connection. Check your internet and try again.") }
            }
        }
    }

    /** Good-morning briefing text (panchangam, weather, today's reminders and to-dos). */
    fun briefing(reminders: List<String>, onOk: (String) -> Unit, onError: (String) -> Unit) {
        val server = store.server
        if (!server.startsWith("https://")) { main.post { onError("No server address.") }; return }
        val todos = JSONArray(); store.todos().filter { !it.done }.take(10).forEach { todos.put(it.text) }
        val body = JSONObject()
            .put("now", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("tz", ZoneId.systemDefault().id).put("lang", store.lang).put("city", store.city)
            .put("pet", store.petKind).put("todos", todos)
            .put("reminders", JSONArray().also { a -> reminders.forEach { a.put(it) } })
        pool.execute {
            try {
                val (code, out) = post("$server/api/briefing", body.toString())
                val json = try { JSONObject(out) } catch (e: Exception) { JSONObject() }
                val text = json.optString("text", "")
                if (code in 200..299 && text.isNotBlank()) main.post { onOk(text) }
                else main.post { onError(json.optString("error", "error $code")) }
            } catch (e: Exception) { main.post { onError("No connection.") } }
        }
    }

    /**
     * Natural voice: asks the server for an MP3 of [text]. Returns the file, or null.
     * [notSetUp] is called when the server says natural voice isn't set up (then stop asking).
     * Must be called off the main thread.
     */
    fun ttsBlocking(text: String, pet: String, mood: String, dir: java.io.File, notSetUp: () -> Unit): java.io.File? {
        val server = store.server
        if (!server.startsWith("https://")) return null
        return try {
            val c = URL("$server/api/tts").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 8_000; c.readTimeout = 20_000; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("X-Device", store.deviceId)
            val body = JSONObject().put("text", Privacy.maybe(store, text)).put("pet", pet).put("mood", mood).toString()
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code == 501) { c.disconnect(); notSetUp(); return null }
            if (code !in 200..299) { c.disconnect(); return null }
            val f = java.io.File(dir, "voice_" + System.nanoTime() + ".mp3")
            c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            c.disconnect()
            if (f.length() < 200) { f.delete(); null } else f
        } catch (e: Exception) { null }
    }

    /** 👍 / 👎 on an answer from Habit's own brain ([rid] came with it): it teaches Habit's own AI model. Only the question,
     *  the answer and the right answer they wrote go (the server takes out phone numbers, e-mails and long numbers, and the
     *  owner checks them first). [done] gets null, or what went wrong. */
    fun rate(rid: String, question: String, reply: String, good: Boolean, fix: String, done: (String?) -> Unit) {
        val body = JSONObject().put("rid", rid).put("q", Privacy.maybe(store, question).take(600)).put("reply", reply).put("good", good)
            .put("fix", Privacy.maybe(store, fix).take(1500)).toString()
        val server = store.server
        pool.execute {
            val err = try {
                val (code, out) = post("$server/api/brain/rate", body)
                if (code in 200..299) null else (try { JSONObject(out).optString("error") } catch (e: Exception) { "" }).ifBlank { "Couldn't send it now. Please try again." }
            } catch (e: Exception) { "No connection. Please try again." }
            main.post { done(err) }
        }
    }

    fun report(reason: String, reply: String, question: String) {
        val body = JSONObject().put("reason", reason).put("reply", reply).put("question", question).toString()
        val server = store.server
        pool.execute { try { post("$server/api/report", body) } catch (e: Exception) { } }
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 95_000; c.doOutput = true   // the server's own brain can take a while
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("X-Device", store.deviceId)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val out = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, out)
    }
}
