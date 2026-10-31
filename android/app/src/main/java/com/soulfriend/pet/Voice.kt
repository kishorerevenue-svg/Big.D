package com.soulfriend.pet

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import java.io.File

/** How each pet sounds: phone-voice pitch and speed, changed a little by mood. */
object PetVoice {
    private val BASE = mapOf(
        PuppyView.Kind.PUPPY to (1.35f to 1.0f), PuppyView.Kind.KITTEN to (1.5f to 1.05f), PuppyView.Kind.BUNNY to (1.55f to 1.05f),
        PuppyView.Kind.PANDA to (1.1f to 0.95f), PuppyView.Kind.BEAR to (0.9f to 0.92f), PuppyView.Kind.FOX to (1.3f to 1.05f),
        PuppyView.Kind.ROBOT to (1.05f to 1.0f), PuppyView.Kind.PARROT to (1.55f to 1.1f), PuppyView.Kind.MOCHI to (1.6f to 1.0f),
        PuppyView.Kind.KITTY to (1.5f to 1.05f)
    )

    /** (pitch, speed) for the phone's own voice. */
    fun phone(kind: PuppyView.Kind, mood: PuppyView.Mood): Pair<Float, Float> {
        val (p, r) = BASE[kind] ?: (1.35f to 1.0f)
        return when (mood) {
            PuppyView.Mood.EXCITED, PuppyView.Mood.LAUGHING, PuppyView.Mood.LOVE, PuppyView.Mood.PROUD -> p * 1.06f to r * 1.08f
            PuppyView.Mood.SAD, PuppyView.Mood.CRYING, PuppyView.Mood.SLEEPY, PuppyView.Mood.YAWNING -> p * 0.94f to r * 0.9f
            PuppyView.Mood.SCARED, PuppyView.Mood.SURPRISED -> p * 1.04f to r * 1.1f
            else -> p to r
        }
    }

    /** Pitch for the natural (human) voice: only a gentle shift, so it stays clear. */
    fun naturalPitch(kind: PuppyView.Kind, mood: PuppyView.Mood): Float = 1f + (phone(kind, mood).first - 1f) * 0.35f
}

/** Plays the natural voice made by the server (an MP3), with the pet's pitch. */
class NaturalVoicePlayer {
    private var player: MediaPlayer? = null

    fun play(file: File, pitch: Float, onStart: () -> Unit, onDone: () -> Unit, onFail: () -> Unit) {
        stop()
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                try { if (Build.VERSION.SDK_INT >= 23) it.playbackParams = it.playbackParams.setPitch(pitch).setSpeed(1f) } catch (e: Exception) { }
                if (!it.isPlaying) it.start()
                onStart()
            }
            mp.setOnCompletionListener { release(); file.delete(); onDone() }
            mp.setOnErrorListener { _, _, _ -> release(); file.delete(); onFail(); true }
            mp.prepareAsync()
        } catch (e: Exception) { release(); file.delete(); onFail() }
    }

    fun stop() { try { player?.stop() } catch (e: Exception) { }; release() }
    private fun release() { try { player?.release() } catch (e: Exception) { }; player = null }
}
