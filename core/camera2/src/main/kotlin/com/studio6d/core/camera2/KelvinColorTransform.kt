package com.studio6d.core.camera2

import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.ln
import kotlin.math.pow

/**
 * Converts Kelvin color temperature (2500K..10000K) into Camera2 RGGB channel gains
 * when manual White Balance (AWB OFF) is active.
 */
object KelvinColorTransform {

    fun toRggbGains(kelvin: Int, tint: Float = 0f): RggbChannelVector {
        val temp = kelvin.coerceIn(2000, 11000) / 100f

        val red = if (temp <= 66f) {
            255f
        } else {
            (329.698727446f * (temp - 60f).pow(-0.1332047592f)).coerceIn(0f, 255f)
        }

        val green = if (temp <= 66f) {
            (99.4708025861f * ln(temp) - 161.1195681661f).coerceIn(0f, 255f)
        } else {
            (288.1221695283f * (temp - 60f).pow(-0.0755148492f)).coerceIn(0f, 255f)
        }

        val blue = when {
            temp >= 66f -> 255f
            temp <= 19f -> 0f
            else -> (138.5177312231f * ln(temp - 10f) - 305.0447927307f).coerceIn(0f, 255f)
        }

        // Convert RGB target into inverse camera sensor channel gains normalized around green = 1.0
        val gNorm = (green / 255f).coerceAtLeast(0.1f)
        val rGain = ((red / 255f) / gNorm).coerceIn(0.6f, 3.5f)
        val bGain = ((blue / 255f) / gNorm).coerceIn(0.6f, 3.5f)
        val greenGain = (1.0f - tint * 0.01f).coerceIn(0.7f, 1.4f)

        return RggbChannelVector(rGain, greenGain, greenGain, bGain)
    }
}
