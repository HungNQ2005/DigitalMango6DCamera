package com.studio6d.core.camera2

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.media.ToneGenerator
import kotlin.random.Random

/**
 * Plays physical-modeled Canon 6D-style mechanical shutter, AF lens hunting chatter,
 * and piezo AF confirmation sounds in `assets/sounds/`.
 */
class DslrSoundEngine(private val context: Context) {

    @Volatile
    var enabled: Boolean = true

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val toneGenerator = runCatching {
        ToneGenerator(AudioManager.STREAM_SYSTEM, 35)
    }.getOrNull()

    private var shutterId: Int = loadAssetSound("sounds/6d_shutter.wav")
    private var silentShutterId: Int = loadAssetSound("sounds/6d_shutter_silent.wav")
    private var afBeepId: Int = loadAssetSound("sounds/6d_af_beep.wav")
    private var dialTickId: Int = loadAssetSound("sounds/6d_dial_tick.wav")
    private var afHuntId: Int = loadAssetSound("sounds/6d_af_hunt.wav")

    @Volatile
    private var activeHuntStreamId: Int = 0

    private fun loadAssetSound(assetPath: String): Int {
        return runCatching {
            val afd = context.assets.openFd(assetPath)
            soundPool.load(afd, 1)
        }.getOrDefault(0)
    }

    @Synchronized
    fun startAfHunt() {
        if (!enabled || afHuntId == 0) return
        if (activeHuntStreamId != 0) return
        activeHuntStreamId = soundPool.play(afHuntId, 0.30f, 0.30f, 0, -1, 1.0f)
    }

    @Synchronized
    fun stopAfHunt() {
        val stream = activeHuntStreamId
        if (stream != 0) {
            runCatching { soundPool.stop(stream) }
            activeHuntStreamId = 0
        }
    }

    fun playShutter(silentMode: Boolean) {
        stopAfHunt()
        if (!enabled) return
        val id = if (silentMode) silentShutterId else shutterId
        if (id != 0) {
            val rate = 0.985f + Random.nextFloat() * 0.030f
            val gain = 0.94f + Random.nextFloat() * 0.06f
            soundPool.play(id, gain, gain, 1, 0, rate)
        } else {
            val tone = if (silentMode) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP2
            toneGenerator?.startTone(tone, 45)
        }
    }

    fun playAfBeep() {
        stopAfHunt()
        if (!enabled) return
        if (afBeepId != 0) {
            soundPool.play(afBeepId, 0.48f, 0.48f, 1, 0, 1f)
        } else {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 55)
        }
    }

    fun playDialTick() {
        if (!enabled) return
        if (dialTickId != 0) {
            val rate = 0.97f + Random.nextFloat() * 0.06f
            soundPool.play(dialTickId, 0.42f, 0.42f, 0, 0, rate)
        }
    }

    fun release() {
        stopAfHunt()
        soundPool.release()
        toneGenerator?.release()
    }
}
