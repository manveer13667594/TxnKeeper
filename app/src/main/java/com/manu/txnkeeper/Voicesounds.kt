package com.manu.txnkeeper

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The two little sounds of voice entry, in the style of a chat app's voice-message sounds: soft, short
 * "pop" blips whose pitch glides, and that fade away quickly. A higher blip pair when listening starts,
 * one falling pop when it stops. They are made from sine waves here, so there is no sound file to ship.
 * The start sound is played BEFORE the microphone opens (see [START_MS]), so it is never recorded.
 *
 * To change them: [VOLUME] is the loudness (0.0 to 1.0); each [Blip] is "glide from one pitch to another
 * (Hz) over some milliseconds"; [decay] is how fast it fades (higher = a shorter, poppier blip).
 */
object VoiceSounds {
    private const val RATE = 22_050

    /** Loudness, 0.0 (silent) to 1.0 (as loud as the phone's sound volume allows). */
    private const val VOLUME = 0.20

    /** How long the start sound lasts; recording begins after this. Keep it a little longer than the sound. */
    const val START_MS = 230L

    /** One blip: glides from [fromHz] to [toHz] over [ms]. [fromHz] = 0 is a silent gap. */
    private class Blip(val fromHz: Int, val toHz: Int, val ms: Int, val decay: Double = 22.0)

    // Start: two quick rising blips ("bloop-blip")
    private val startBlips = listOf(Blip(520, 760, 80), Blip(0, 0, 18), Blip(760, 1080, 100, decay = 18.0))

    // Stop: one falling pop
    private val stopBlips = listOf(Blip(980, 560, 120, decay = 20.0))

    fun start() = play(startBlips)
    fun stop() = play(stopBlips)

    private fun play(blips: List<Blip>) {
        runCatching {
            val samples = ShortArray(blips.sumOf { RATE * it.ms / 1000 })
            var at = 0
            for (b in blips) {
                val n = RATE * b.ms / 1000
                if (b.fromHz > 0) {
                    var phase = 0.0
                    for (i in 0 until n) {
                        val t = i.toDouble() / n                       // 0..1 through the blip
                        val hz = b.fromHz + (b.toHz - b.fromHz) * t     // the glide
                        phase += 2 * PI * hz / RATE
                        // quick fade in (no click), then a fast fade out: that is what makes it a "pop"
                        val attack = minOf(1.0, i / 90.0)
                        val envelope = attack * exp(-b.decay * t * b.ms / 1000.0 * 4)
                        samples[at + i] = (sin(phase) * envelope * VOLUME * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                at += n
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.play()
            val totalMs = blips.sumOf { it.ms } + 150L
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, totalMs)
        }
    }
}