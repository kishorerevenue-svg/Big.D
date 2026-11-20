package com.soulfriend.pet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * The tiny brain on the phone (the separate Habit Brain app, Habit AI Lite): it answers when there is no internet at all.
 * Only apps with Habit's stamp can ask it. The connection stays open a few minutes after the last question,
 * so the model stays loaded; then it closes, which frees the phone's memory.
 */
object TinyBrain {
    const val PKG = "com.kishore.bigd.brain"
    private val main = Handler(Looper.getMainLooper())
    private var messenger: Messenger? = null
    private var conn: ServiceConnection? = null
    private var appCtx: Context? = null
    private val waiting = ArrayList<(Messenger?) -> Unit>()
    private val close = Runnable { unbind() }

    fun installed(ctx: Context) = try { ctx.packageManager.getPackageInfo(PKG, 0); true } catch (e: Exception) { false }

    /** Opens Habit Brain (to download the brain or try it). */
    fun open(ctx: Context, store: Store) {
        try {
            ctx.startActivity(Intent().setClassName(PKG, "$PKG.BrainActivity").putExtra("server", store.server).putExtra("lang", store.lang)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
    }

    /** When it last couldn't answer (not downloaded, too slow): then the server is asked as before, for a while. */
    @Volatile var failedAt = 0L
    fun usable(ctx: Context) = installed(ctx) && System.currentTimeMillis() - failedAt > 10 * 60_000L

    /** The question with the talk so far and a few things it knows about them (all of it stays on this phone). */
    fun prompt(store: Store, history: List<org.json.JSONObject>, text: String): String {
        val mem = store.memories().takeLast(12)
        val talk = history.takeLast(6).joinToString("\n") { h -> (if (h.optString("role") == "user") "Them: " else "You: ") + h.optString("content").take(400) }
        return (if (mem.isNotEmpty()) "What you know about them:\n" + mem.joinToString("\n") { "- " + it.take(200) } + "\n\n" else "") +
            (if (talk.isNotBlank()) "The talk so far:\n$talk\n\n" else "") +
            "They say now: $text"
    }

    /** What the tiny brain is told about itself. */
    fun system(store: Store, online: Boolean = false) = "You are Habit, a kind cartoon pet friend who lives on the person's phone. " +
        (if (online) "You are answering with your own brain on this phone (it is free and private: nothing leaves the phone). "
         else "The phone has no internet right now, so you are answering with your small offline brain. ") +
        (when (store.lang) { "te" -> "Always reply in simple spoken Telugu, in Telugu script. "; "en" -> "Reply in simple English. "
            else -> "Reply in the language the person uses (Telugu script for Telugu). " }) +
        "Your reply is read aloud: 1 to 4 short, warm sentences. Their words come from voice typing: if they correct themselves midway, go by the last correction, and ignore stray words from a TV or other people. If you are not sure about a fact, say so honestly; never make up numbers, names, prices or news. " +
        "For health or money, give only safe, general advice and suggest asking a doctor or the bank."

    /** Asks it (main thread). [done] gets the answer, or null when it can't answer (not installed, brain not downloaded, too slow). */
    fun ask(ctx: Context, system: String, prompt: String, done: (String?) -> Unit) {
        var finished = false
        val once = { s: String? -> if (!finished) { finished = true; done(s) } }
        main.postDelayed({ once(null) }, 90_000)
        connect(ctx) { m ->
            if (m == null) { once(null); return@connect }
            val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
                if (msg.what == 2) once(msg.data.getString("text")?.takeIf { it.isNotBlank() })
                true
            })
            try {
                m.send(Message.obtain(null, 1).apply {
                    data = Bundle().apply { putString("system", system); putString("prompt", prompt) }
                    replyTo = reply
                })
            } catch (e: Exception) { once(null) }
        }
        main.removeCallbacks(close); main.postDelayed(close, 5 * 60_000L)
    }

    private fun connect(ctx: Context, then: (Messenger?) -> Unit) {
        messenger?.let { then(it); return }
        waiting.add(then)
        if (conn != null) return
        val c = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName, b: IBinder) {
                val m = Messenger(b); messenger = m
                val list = ArrayList(waiting); waiting.clear(); list.forEach { it(m) }
            }
            override fun onServiceDisconnected(n: ComponentName) { messenger = null }
            override fun onBindingDied(n: ComponentName) { unbind() }
            override fun onNullBinding(n: ComponentName) { unbind() }
        }
        val app = ctx.applicationContext
        val ok = try { app.bindService(Intent().setClassName(PKG, "$PKG.BrainService"), c, Context.BIND_AUTO_CREATE) } catch (e: Exception) { false }
        if (!ok) { val list = ArrayList(waiting); waiting.clear(); list.forEach { it(null) }; return }
        conn = c; appCtx = app
    }

    private fun unbind() {
        val c = conn ?: return
        try { appCtx?.unbindService(c) } catch (e: Exception) { }
        conn = null; messenger = null; appCtx = null
        val list = ArrayList(waiting); waiting.clear(); list.forEach { it(null) }
    }
}
