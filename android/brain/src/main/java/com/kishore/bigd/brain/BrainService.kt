package com.kishore.bigd.brain

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * BigD AI asks the tiny brain here (only apps with BigD's stamp can, see the manifest).
 * Message 1 = {system, prompt}  ->  reply 2 = {text} or {error}. One question at a time; the model stays loaded
 * while BigD AI is connected (it disconnects a few minutes after the last question, which frees the memory).
 */
class BrainService : Service() {
    private val worker = HandlerThread("tiny-brain").apply { start() }
    private val bg = Handler(worker.looper)
    @Volatile private var engine: Engine? = null

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        val to = msg.replyTo
        when (msg.what) {
            1 -> { val system = msg.data.getString("system") ?: ""; val prompt = msg.data.getString("prompt") ?: ""; bg.post { answer(system, prompt, to) } }
            3 -> send(to, Bundle().apply { putBoolean("ready", Model.ready(this@BrainService)) })
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun send(to: Messenger?, b: Bundle) { try { to?.send(Message.obtain(null, 2).apply { data = b }) } catch (e: Exception) { } }

    private fun answer(system: String, prompt: String, to: Messenger?) {
        if (!Model.ready(this)) { send(to, Bundle().apply { putString("error", "not_ready") }); return }
        val out = Bundle()
        try {
            val e = load()
            e.createConversation(ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
            )).use { c -> out.putString("text", c.sendMessage(prompt).toString().trim()) }
        } catch (t: Throwable) {
            out.putString("error", t.javaClass.simpleName + ": " + (t.message ?: ""))
            closeEngine()
        }
        send(to, out)
    }

    /** The phone's graphics chip is faster; if this phone can't use it, the main chip. Takes a few seconds once. */
    private fun load(): Engine {
        engine?.let { return it }
        val path = Model.file(this).absolutePath
        val e = try {
            Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), cacheDir = cacheDir.path)).also { it.initialize() }
        } catch (t: Throwable) {
            Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = cacheDir.path)).also { it.initialize() }
        }
        engine = e
        return e
    }

    private fun closeEngine() { try { engine?.close() } catch (t: Throwable) { }; engine = null }

    override fun onDestroy() {
        bg.post { closeEngine(); worker.quitSafely() }
        super.onDestroy()
    }
}
