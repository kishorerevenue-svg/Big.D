package com.soulfriend.pet

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Everything the puppy remembers, saved on the phone. */
object Config {
    /** Put your server address here, or type it in the app's setup screen. No slash at the end. */
    const val DEFAULT_SERVER = "https://YOUR-DOMAIN.com"
}

data class Todo(val text: String, val done: Boolean)
data class Reminder(val id: Int, val at: Long, val text: String)

class Store(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("soul_friend", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", Config.DEFAULT_SERVER) ?: Config.DEFAULT_SERVER
        set(v) { p.edit().putString("server", v.trim().trimEnd('/')).apply() }

    /** "auto", "te" or "en" */
    var lang: String
        get() = p.getString("lang", "auto") ?: "auto"
        set(v) { p.edit().putString("lang", v).apply() }

    var voiceOn: Boolean
        get() = p.getBoolean("voice", true)
        set(v) { p.edit().putBoolean("voice", v).apply() }

    /** puppy, kitten, bunny, panda, bear or fox */
    var petKind: String
        get() = p.getString("petKind", "puppy") ?: "puppy"
        set(v) { p.edit().putString("petKind", v).apply() }

    var petX: Int
        get() = p.getInt("petX", -1)
        set(v) { p.edit().putInt("petX", v).apply() }
    var petY: Int
        get() = p.getInt("petY", -1)
        set(v) { p.edit().putInt("petY", v).apply() }

    // ---- to-do list ----
    fun todos(): List<Todo> {
        val a = JSONArray(p.getString("todos", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Todo(o.optString("text"), o.optBoolean("done")) }
    }
    private fun saveTodos(list: List<Todo>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("text", it.text).put("done", it.done)) }
        p.edit().putString("todos", a.toString()).apply()
    }
    fun addTodo(text: String) { if (text.isNotBlank()) saveTodos(todos() + Todo(text.trim(), false)) }
    fun setTodoDone(index: Int, done: Boolean) {
        val l = todos().toMutableList(); if (index in l.indices) { l[index] = l[index].copy(done = done); saveTodos(l) }
    }
    fun removeTodo(index: Int) { val l = todos().toMutableList(); if (index in l.indices) { l.removeAt(index); saveTodos(l) } }
    /** Finds the item that best matches what the AI said. Returns its index or -1. */
    fun findTodo(text: String): Int {
        val q = text.trim().lowercase(); val l = todos()
        l.indexOfFirst { it.text.lowercase() == q }.let { if (it >= 0) return it }
        return l.indexOfFirst { it.text.lowercase().contains(q) || q.contains(it.text.lowercase()) }
    }

    // ---- notes ----
    fun notes(): List<String> {
        val a = JSONArray(p.getString("notes", "[]")); return (0 until a.length()).map { a.getString(it) }
    }
    fun addNote(text: String) {
        if (text.isBlank()) return
        val a = JSONArray(); (notes() + text.trim()).takeLast(100).forEach { a.put(it) }
        p.edit().putString("notes", a.toString()).apply()
    }
    fun removeNote(index: Int) {
        val l = notes().toMutableList(); if (index in l.indices) l.removeAt(index)
        val a = JSONArray(); l.forEach { a.put(it) }; p.edit().putString("notes", a.toString()).apply()
    }

    // ---- things the puppy remembers about you ----
    fun memories(): List<String> {
        val a = JSONArray(p.getString("memories", "[]")); return (0 until a.length()).map { a.getString(it) }
    }
    private fun saveMemories(l: List<String>) { val a = JSONArray(); l.takeLast(60).forEach { a.put(it) }; p.edit().putString("memories", a.toString()).apply() }
    fun remember(text: String) { val t = text.trim(); if (t.isNotEmpty() && memories().none { it.equals(t, true) }) saveMemories(memories() + t) }
    fun forget(text: String): Boolean {
        val q = text.trim().lowercase(); val l = memories()
        val keep = l.filterNot { it.lowercase() == q || it.lowercase().contains(q) || (q.length > 3 && q.contains(it.lowercase())) }
        saveMemories(keep); return keep.size < l.size
    }
    fun removeMemory(index: Int) { val l = memories().toMutableList(); if (index in l.indices) { l.removeAt(index); saveMemories(l) } }

    // ---- reminders ----
    fun reminders(): List<Reminder> {
        val a = JSONArray(p.getString("reminders", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Reminder(o.getInt("id"), o.getLong("at"), o.optString("text")) }
    }
    private fun saveReminders(list: List<Reminder>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)) }
        p.edit().putString("reminders", a.toString()).apply()
    }
    fun addReminder(at: Long, text: String): Reminder {
        val id = p.getInt("nextId", 1); p.edit().putInt("nextId", id + 1).apply()
        val r = Reminder(id, at, text); saveReminders(reminders() + r); return r
    }
    fun removeReminder(id: Int) { saveReminders(reminders().filter { it.id != id }) }
}
