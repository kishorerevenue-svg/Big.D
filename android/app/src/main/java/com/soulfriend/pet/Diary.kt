package com.soulfriend.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The pet's diary: every talk with the pet is kept on this phone (the newest 300). After a conversation (and at least
 * once a day) the new talks go to the Habit server once, and Claude rewrites the pet's memory list from them: each
 * person and friend, habits, daily routines, schedule, likes, important dates; small talk, news and one-time questions
 * are dropped, and no passwords or ID numbers are kept. The server keeps nothing; the memory and the diary stay here.
 */
object Diary {
    private const val MAX = 300
    @Volatile private var busy = false
    private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** One talk: what was said ([who]: the person's name when they said it, else the owner) and what the pet answered. */
    fun add(store: Store, who: String, said: String, answer: String) {
        if (said.isBlank()) return
        val a = try { JSONArray(store.diary) } catch (e: Exception) { JSONArray() }
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("at", LocalDateTime.now().format(WHEN))
            .put("who", who.take(40)).put("q", said.take(500)).put("a", Talk.plain(answer).take(500)))
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - MAX) until a.length()) keep.put(a.get(i))
        store.diary = keep.toString()
    }

    /** The talks that aren't in the memory yet. */
    private fun fresh(store: Store): List<JSONObject> {
        val a = try { JSONArray(store.diary) } catch (e: Exception) { return emptyList() }
        val since = store.diaryTidyAt
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optLong("t") > since }
    }

    /**
     * Puts the new talks into the pet's memory (off the main thread). [afterTalk]: a conversation just ended (then a
     * couple of new talks are enough); otherwise once the newest talk is over a day old or there are many.
     */
    fun tidy(ctx: Context, afterTalk: Boolean = false) {
        val store = Store(ctx.applicationContext)
        if (busy || !store.server.startsWith("https://")) return
        val items = fresh(store)
        if (items.isEmpty()) return
        val day = System.currentTimeMillis() - store.diaryTidyAt > 24 * 3600_000L
        if (!(items.size >= 20 || (afterTalk && items.size >= 2) || day)) return
        busy = true
        Thread {
            try {
                val talk = JSONArray(); items.takeLast(80).forEach { talk.put(JSONObject().put("at", it.optString("at")).put("who", it.optString("who")).put("q", it.optString("q")).put("a", it.optString("a"))) }
                val body = JSONObject().put("lang", store.lang).put("memories", JSONArray(store.memories())).put("talk", talk)
                val c = URL(store.server + "/api/pet/memory").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 90_000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("X-Device", store.deviceId)
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                c.disconnect()
                if (code in 200..299) {
                    val j = JSONObject(text)
                    val m = j.optJSONArray("memories")
                    if (m != null && j.optBoolean("changed")) store.setMemories((0 until m.length()).map { m.optString(it) })
                    store.diaryTidyAt = items.last().optLong("t")       // (later talks wait for the next time)
                }
            } catch (e: Exception) {
            } finally { busy = false }
        }.start()
    }

    /** How many talks the diary keeps now (for the pet page). */
    fun size(store: Store): Int = try { JSONArray(store.diary).length() } catch (e: Exception) { 0 }
}
