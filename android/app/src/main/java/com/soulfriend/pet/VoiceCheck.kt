package com.soulfriend.pet

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

/**
 * To know WHO is talking, Habit has to hear the voice itself, while the phone's voice typing turns it into words. Since
 * Android 13 an app may open the microphone and hand the sound to the voice typing (EXTRA_AUDIO_SOURCE); not every voice
 * typing app accepts it. [VoiceTap] does that; [VoiceCheck] tries it once (the "🎙 Voice check" button) and remembers the
 * answer. Nothing is sent anywhere and nothing is kept: the sound is only measured here.
 */
@android.annotation.TargetApi(33)
class VoiceTap {
    private var rec: AudioRecord? = null
    private var pipe: Array<ParcelFileDescriptor>? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var running = false
    private var thread: Thread? = null
    /** The loudest sound Habit itself heard (0 = silence: the voice typing took the microphone). */
    @Volatile var peak = 0; private set

    /** Opens the microphone and puts its sound into [intent] for the voice typing. False if the microphone didn't open. */
    @SuppressLint("MissingPermission")
    fun start(intent: Intent): Boolean {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, rate))
        } catch (e: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        val p = try { ParcelFileDescriptor.createPipe() } catch (e: IOException) { r.release(); return false }
        rec = r; pipe = p
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
        try { r.startRecording() } catch (e: Exception) { stop(); return false }
        running = true
        thread = Thread {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(p[1])
            val buf = ByteArray(3200)                                   // 100 ms of sound
            try {
                while (running) {
                    val n = r.read(buf, 0, buf.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    var i = 0
                    while (i + 1 < n) { val s = abs((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)); if (s > peak) peak = s; i += 2 }
                    if (pcm.size() < rate * 2 * 20) pcm.write(buf, 0, n)  // at most 20 seconds, only in memory
                    try { out.write(buf, 0, n) } catch (e: IOException) { break }   // the voice typing has finished
                }
            } finally { try { out.close() } catch (e: Exception) { } }
        }.apply { isDaemon = true; start() }
        return true
    }

    /** After startListening(): the voice typing has its own copy of the reading end. */
    fun handedOver() { try { pipe?.get(0)?.close() } catch (e: Exception) { } }

    /** Closes the microphone; returns what Habit heard (16 kHz, 16-bit, mono). */
    fun stop(): ByteArray {
        running = false
        try { rec?.stop() } catch (e: Exception) { }
        try { rec?.release() } catch (e: Exception) { }
        rec = null
        try { thread?.join(600) } catch (e: Exception) { }
        pipe?.forEach { try { it.close() } catch (e: Exception) { } }
        pipe = null
        return pcm.toByteArray()
    }
}

object VoiceCheck {
    class Result(val ok: Boolean, val heard: String, val peak: Int, val error: Int, val which: String, val service: String)

    /** The phone's voice typing app (for the check's small print), e.g. "com.google.android.tts". */
    fun service(ctx: Context): String = try {
        (Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service") ?: "").substringBefore('/')
    } catch (e: Exception) { "" }

    /** "ok" when this phone passed the check, "no" when it didn't, "" when it wasn't checked. */
    fun state(ctx: Context): String = try { ChatDb.get(ctx).kv("voice:tap") } catch (e: Exception) { "" }

    /**
     * One check: they say a sentence; it passes when the voice typing gives the words AND Habit heard the sound itself.
     * The phone's usual voice typing first, then (if the phone has one) its on-device voice typing. Call on the main thread.
     */
    @android.annotation.TargetApi(33)
    fun run(ctx: Context, lang: String, onStatus: (String) -> Unit, onDone: (Result) -> Unit) {
        val te = lang == "te"
        if (Build.VERSION.SDK_INT < 33) { onDone(Result(false, "", 0, -1, "", service(ctx))); return }
        val main = Handler(Looper.getMainLooper())
        val tries = mutableListOf("usual")
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) tries.add("on-device")
        fun attempt(k: Int, last: Result?) {
            if (k >= tries.size) { save(ctx, last!!); onDone(last); return }
            val which = tries[k]
            val sr = try { if (which == "usual") SpeechRecognizer.createSpeechRecognizer(ctx) else SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }
                     catch (e: Exception) { attempt(k + 1, last ?: Result(false, "", 0, -2, which, service(ctx))); return }
            val tap = VoiceTap()
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (te) "te-IN" else "en-IN")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            var finished = false
            fun finish(heard: String, error: Int) {
                if (finished) return
                finished = true
                main.removeCallbacksAndMessages(sr)
                tap.stop()
                try { sr.destroy() } catch (e: Exception) { }
                val r = Result(heard.isNotBlank() && tap.peak > 1200, heard, tap.peak, error, which, service(ctx))
                if (r.ok) { save(ctx, r); onDone(r) } else main.postDelayed({ attempt(k + 1, r) }, 400)
            }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    onStatus(if (te) "🎙 ఇప్పుడు చెప్పండి: \"హలో హ్యాబిట్, ఈరోజు ఎలా ఉన్నావు?\"" else "🎙 Now say: \"Hello Habit, how are you today?\"")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { onStatus(if (te) "⏳ చూస్తున్నాను…" else "⏳ Checking…") }
                override fun onError(error: Int) { finish("", error) }
                override fun onResults(results: Bundle?) { finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(), 0) }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            if (!tap.start(i)) { finish("", -3); return }
            try { sr.startListening(i); tap.handedOver() } catch (e: Exception) { finish("", -4); return }
            main.postAtTime({ try { sr.stopListening() } catch (e: Exception) { }; main.postDelayed({ finish("", -5) }, 4000) }, sr, android.os.SystemClock.uptimeMillis() + 12_000)
        }
        attempt(0, null)
    }

    private fun save(ctx: Context, r: Result) {
        try {
            val db = ChatDb.get(ctx)
            db.setKv("voice:tap", if (r.ok) "ok" else "no")
            db.setKv("voice:tapWhich", if (r.ok) r.which else "")
        } catch (e: Exception) { }
    }
}
