package com.soulfriend.pet

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Small helpers to talk to the Habit server. Blocking: call them off the main thread. */
object Net {
    class Reply(val code: Int, val json: JSONObject) {
        val ok get() = code in 200..299
        fun error(fallback: String) = json.optString("error", "").ifBlank { fallback }
    }

    fun post(store: Store, path: String, body: JSONObject, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 90_000): Reply =
        call(store, "POST", path, body.toString(), headers, timeoutMs)

    fun get(store: Store, path: String, headers: Map<String, String> = emptyMap()): Reply =
        call(store, "GET", path, null, headers, 20_000)

    private fun call(store: Store, method: String, path: String, body: String?, headers: Map<String, String>, timeoutMs: Int): Reply {
        val server = store.server
        if (!server.startsWith("https://")) return Reply(0, JSONObject().put("error", "Set your server address in the Habit AI app first."))
        return try {
            val c = URL(server + path).openConnection() as HttpURLConnection
            c.requestMethod = method; c.connectTimeout = 15_000; c.readTimeout = timeoutMs
            c.setRequestProperty("X-Device", store.deviceId)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            c.disconnect()
            Reply(code, try { JSONObject(text) } catch (e: Exception) { JSONObject() })
        } catch (e: Exception) {
            Reply(0, JSONObject().put("error", "No connection. Check your internet and try again."))
        }
    }
}
