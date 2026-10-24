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

    /** [photo] = Pair(mediaType, base64) to ask about a picture. */
    fun ask(text: String, onReply: (JSONObject) -> Unit, onError: (String) -> Unit, photo: Pair<String, String>? = null) {
        history.add(JSONObject().put("role", "user").put("content", text))
        while (history.size > 12) history.removeAt(0)
        val messages = JSONArray(); history.forEach { messages.put(it) }
        val todos = JSONArray(); store.todos().forEach { todos.put(JSONObject().put("text", it.text).put("done", it.done)) }
        val notes = JSONArray(); store.notes().takeLast(10).forEach { notes.put(it) }
        val body = JSONObject()
            .put("messages", messages).put("lang", store.lang)
            .put("now", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("tz", ZoneId.systemDefault().id)
            .put("todos", todos).put("notes", notes)
            .put("memories", JSONArray().also { a -> store.memories().forEach { a.put(it) } })
        if (photo != null) body.put("image", JSONObject().put("mediaType", photo.first).put("data", photo.second))
        val server = store.server
        if (server.contains("YOUR-DOMAIN") || !server.startsWith("https://")) {
            history.removeAt(history.lastIndex)
            main.post { onError("I don't have a brain yet! Set up your server, then type its address in the BigD AI app (Server address).") }
            return
        }
        pool.execute {
            try {
                val (code, out) = post("$server/api/pet", body.toString())
                val json = try { JSONObject(out) } catch (e: Exception) { JSONObject() }
                if (code in 200..299 && !json.has("reply")) main.post {
                    history.removeAt(history.lastIndex)
                    onError("That server address is not a BigD AI server. Check the address in the BigD AI app.")
                } else if (code in 200..299) {
                    val reply = json.optString("reply", "Woof!")
                    main.post {
                        if (photo != null) history[history.lastIndex] = JSONObject().put("role", "user").put("content", "[I showed you a photo] $text")
                        history.add(JSONObject().put("role", "assistant").put("content", reply))
                        onReply(json)
                    }
                } else main.post {
                    history.removeAt(history.lastIndex)
                    onError(json.optString("error", "Something went wrong (error $code). Please try again."))
                }
            } catch (e: Exception) {
                main.post {
                    if (history.isNotEmpty()) history.removeAt(history.lastIndex)
                    onError(if (server.contains("YOUR-DOMAIN")) "Set your server address in the BigD AI app first."
                            else "No connection. Check your internet and try again.")
                }
            }
        }
    }

    fun report(reason: String, reply: String, question: String) {
        val body = JSONObject().put("reason", reason).put("reply", reply).put("question", question).toString()
        val server = store.server
        pool.execute { try { post("$server/api/report", body) } catch (e: Exception) { } }
    }

    private fun post(url: String, body: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 60_000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val out = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, out)
    }
}
