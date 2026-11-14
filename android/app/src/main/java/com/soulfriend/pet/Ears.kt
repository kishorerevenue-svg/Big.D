package com.soulfriend.pet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Which voice typing the pet listens with. Some phones' usual voice typing gives up a second after it starts (before
 * people begin to talk) or doesn't know Telugu; then the pet tries the Google app's voice typing (when the phone has
 * it) and remembers the one that worked. Nothing is recorded or kept: this only chooses the phone's voice typing.
 */
object Ears {
    private const val GSA = "com.google.android.googlequicksearchbox"

    /** The phone's usual voice typing (Settings), when the phone says which. */
    fun usual(ctx: Context): ComponentName? = try {
        Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")?.takeIf { it.isNotBlank() }?.let { ComponentName.unflattenFromString(it) }
    } catch (e: Exception) { null }

    /** Every voice typing on this phone. */
    fun all(ctx: Context): List<ComponentName> = try {
        ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { r -> r.serviceInfo?.let { ComponentName(it.packageName, it.name) } }.distinct()
    } catch (e: Exception) { emptyList() }

    /** The one that worked last time (if it's still on the phone), else the usual one. */
    fun start(ctx: Context): ComponentName? {
        val saved = try { Store(ctx).ears } catch (e: Exception) { "" }
        val c = saved.takeIf { it.isNotBlank() }?.let { ComponentName.unflattenFromString(it) }
        return if (c != null && c in all(ctx)) c else usual(ctx)
    }

    /**
     * Another voice typing to try when [now] keeps giving up: the Google app's (it knows Telugu well); with [anyKind]
     * (this one can't work at all: busy, broken, doesn't know the language) any other one too. Null: nothing else.
     */
    fun other(ctx: Context, now: ComponentName?, anyKind: Boolean): ComponentName? {
        val cur = now ?: usual(ctx)
        val list = all(ctx).filter { it != cur }
        return list.firstOrNull { it.packageName == GSA } ?: (if (anyKind) list.firstOrNull() else null)
    }

    /** It heard words with [c]: start with it next time (nothing to keep when it's the usual one). */
    fun worked(ctx: Context, c: ComponentName?) {
        try {
            val s = Store(ctx)
            val v = if (c == null || c == usual(ctx)) "" else c.flattenToString()
            if (s.ears != v) s.ears = v
        } catch (e: Exception) { }
    }

    /** Whether this voice typing ([key]) can take the pet's own sound ([QuietEar.attach]); not known yet: yes, try it. */
    fun feedOk(ctx: Context, key: String): Boolean = try { key !in Store(ctx).earsNoFeed.split(',') } catch (e: Exception) { true }

    /** What the last try showed: this voice typing takes the pet's own sound ([ok]), or opens the microphone itself. */
    fun fed(ctx: Context, key: String, ok: Boolean) {
        try {
            val s = Store(ctx)
            val set = s.earsNoFeed.split(',').filter { it.isNotBlank() }.toMutableSet()
            if (if (ok) set.remove(key) else set.add(key)) s.earsNoFeed = set.joinToString(",")
        } catch (e: Exception) { }
    }

    fun create(ctx: Context, c: ComponentName?): SpeechRecognizer = try {
        if (c == null) SpeechRecognizer.createSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx, c)
    } catch (e: Exception) { SpeechRecognizer.createSpeechRecognizer(ctx) }
}

/**
 * Waits for someone to start talking with the microphone alone: no voice typing runs meanwhile, so there are no beeps
 * and nothing gives up after a second of quiet. When talking starts it says so ([onTalk]). On Android 13+ it can then
 * hand its sound to the voice typing ([attach]), from a moment before the talking, so the first word isn't cut. The
 * sound is only passed on, never kept. [onDone]: nobody talked in time (true: the microphone gave only silence, so it
 * can't be used for waiting here). [key]: which voice typing it hands over to.
 */
class QuietEar(val key: String, private val onTalk: () -> Unit, private val onDone: (Boolean) -> Unit) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var feed: android.os.ParcelFileDescriptor? = null      // where the voice typing reads (after the hand-over)
    private var readEnd: android.os.ParcelFileDescriptor? = null
    private var thread: Thread? = null
    @Volatile private var sent = 0L
    @Volatile private var stuck = false
    @Volatile private var cap = 65536                                         // how much the pipe holds
    @Volatile private var attached = false

    @android.annotation.SuppressLint("MissingPermission")
    fun start(maxMs: Long): Boolean {
        val rate = 16000
        val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            android.media.AudioRecord(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
        } catch (e: Exception) { return false }
        if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        try { rec.startRecording() } catch (e: Exception) { rec.release(); return false }
        if (rec.recordingState != android.media.AudioRecord.RECORDSTATE_RECORDING) { try { rec.stop() } catch (e: Exception) { }; rec.release(); return false }
        running = true
        thread = Thread {
            val frame = ByteArray(640)                                        // 20 ms
            val ring = java.util.ArrayDeque<ByteArray>()                      // the last moment of sound; after the hand-over: what is still to pass on
            val end = android.os.SystemClock.elapsedRealtime() + maxMs
            var floor = -1.0; var loud = 0; var frames = 0; var zeros = 0; var talked = false; var stuckSince = 0L
            try {
                while (running) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n < 0) break
                    if (n == 0) continue
                    ring.addLast(frame.copyOf(n))
                    val fd = feed
                    if (fd != null) {                                         // handed over: the moment before the talking, then the talking
                        var full = false
                        while (ring.isNotEmpty()) {
                            val f = ring.peekFirst() ?: break
                            try { android.system.Os.write(fd.fileDescriptor, f, 0, f.size); ring.removeFirst(); sent += f.size }
                            catch (e: android.system.ErrnoException) { if (e.errno == android.system.OsConstants.EAGAIN) { full = true; break } else throw e }
                        }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!full) stuckSince = 0L
                        else if (stuckSince == 0L) stuckSince = now
                        else if (now - stuckSince > 1_500L) { stuck = true; break }   // the voice typing isn't reading (any more)
                        continue
                    }
                    while (ring.size > 60) ring.removeFirst()                 // 1.2 s
                    if (talked) continue                                      // waiting to be handed over
                    var sum = 0.0; var i = 0
                    while (i + 1 < n) { val s = ((frame[i].toInt() and 0xFF) or (frame[i + 1].toInt() shl 8)).toShort().toDouble(); sum += s * s; i += 2 }
                    val rms = Math.sqrt(sum / maxOf(1, n / 2))
                    frames++
                    if (rms == 0.0) zeros++
                    if (frames == 75 && zeros >= 74) { main.post { if (running) { running = false; onDone(true) } }; break }   // 1.5 s of only silence: not ours here
                    if (floor < 0 || (frames <= 10 && rms < floor)) floor = rms   // starts from the quietest of the first moments
                    if (frames > 5 && rms > maxOf(floor * 3.0, 250.0)) { if (++loud >= 3) { talked = true; main.post { if (running) onTalk() } } }
                    else { loud = 0; floor = floor * 0.95 + rms * 0.05 }       // learns how quiet the room is
                    if (android.os.SystemClock.elapsedRealtime() > end) { main.post { if (running) { running = false; onDone(false) } }; break }
                }
            } catch (e: Exception) { }
            finally {
                try { rec.stop() } catch (e: Exception) { }
                rec.release()
                try { feed?.close() } catch (e: Exception) { }
                feed = null
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    /** Android 13+: the voice typing reads this sound instead of opening the microphone. False: it opens the microphone. */
    fun attach(i: android.content.Intent): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33 || !running) return false
        val p = try { android.os.ParcelFileDescriptor.createPipe() } catch (e: Exception) { return false }
        try { android.system.Os.fcntlInt(p[1].fileDescriptor, android.system.OsConstants.F_SETFL, android.system.OsConstants.O_NONBLOCK) }
        catch (e: Exception) { try { p[0].close(); p[1].close() } catch (x: Exception) { }; return false }
        // a small pipe (F_SETPIPE_SZ), so it shows whether the voice typing really reads it
        cap = try { android.system.Os.fcntlInt(p[1].fileDescriptor, 1031, 4096).takeIf { it in 4096..65536 } ?: 65536 } catch (e: Exception) { 65536 }
        i.putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
            .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
        readEnd = p[0]                                                        // kept until the end (the voice typing may read it a little later)
        attached = true
        feed = p[1]
        return true
    }

    /** After the voice typing finished by itself: whether it read the sound (true), didn't (false: it opened the microphone
     *  itself), or it can't be told (null). */
    fun taken(): Boolean? = when { !attached -> null; sent > cap + 1280 -> true; stuck -> false; cap <= 8192 -> false; else -> null }

    fun stop() {
        running = false
        try { thread?.join(300) } catch (e: Exception) { }
        thread = null
        try { readEnd?.close() } catch (e: Exception) { }
        readEnd = null
    }
}
