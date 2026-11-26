package com.studio6d.core.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Standard 1/3-stop tables and LCD formatters matching Canon EOS 6D conventions.
 */
object DslrStops {

    /** Canon 6D ISO range: L(50), 100-25600 native, H1(51200), H2(102400); 0 represents AUTO ISO. */
    val ISO_STOPS: List<Int> = listOf(
        0, // AUTO
        50, // L (Expanded Low)
        100, 125, 160,
        200, 250, 320,
        400, 500, 640,
        800, 1000, 1250,
        1600, 2000, 2500,
        3200, 4000, 5000,
        6400, 8000, 10000,
        12800, 16000, 20000,
        25600,
        51200, // H1
        102400 // H2
    )

    /** Canon 6D shutter range: 30s to 1/4000s (in nanoseconds), including 1/180s X-Sync. */
    val SHUTTER_STOPS_NS: List<Long> = listOf(
        250_000L,         // 1/4000
        312_500L,         // 1/3200
        400_000L,         // 1/2500
        500_000L,         // 1/2000
        625_000L,         // 1/1600
        800_000L,         // 1/1250
        1_000_000L,       // 1/1000
        1_250_000L,       // 1/800
        1_562_500L,       // 1/640
        2_000_000L,       // 1/500
        2_500_000L,       // 1/400
        3_125_000L,       // 1/320
        4_000_000L,       // 1/250
        5_000_000L,       // 1/200
        5_555_556L,       // 1/180 (X-Sync)
        6_250_000L,       // 1/160
        8_000_000L,       // 1/125
        10_000_000L,      // 1/100
        12_500_000L,      // 1/80
        16_666_667L,      // 1/60
        20_000_000L,      // 1/50
        25_000_000L,      // 1/40
        33_333_333L,      // 1/30
        40_000_000L,      // 1/25
        50_000_000L,      // 1/20
        66_666_667L,      // 1/15
        76_923_077L,      // 1/13
        100_000_000L,     // 1/10
        125_000_000L,     // 1/8
        166_666_667L,     // 1/6
        200_000_000L,     // 1/5
        250_000_000L,     // 1/4
        300_000_000L,     // 0"3
        400_000_000L,     // 0"4
        500_000_000L,     // 0"5
        600_000_000L,     // 0"6
        800_000_000L,     // 0"8
        1_000_000_000L,   // 1"
        1_300_000_000L,   // 1"3
        1_600_000_000L,   // 1"6
        2_000_000_000L,   // 2"
        2_500_000_000L,   // 2"5
        3_200_000_000L,   // 3"2
        4_000_000_000L,   // 4"
        5_000_000_000L,   // 5"
        6_000_000_000L,   // 6"
        8_000_000_000L,   // 8"
        10_000_000_000L,  // 10"
        13_000_000_000L,  // 13"
        15_000_000_000L,  // 15"
        20_000_000_000L,  // 20"
        25_000_000_000L,  // 25"
        30_000_000_000L   // 30"
    )

    /** EF L-series full-frame lens aperture stops (f/1.2 prime to f/16). */
    val APERTURE_STOPS: List<Float> = listOf(
        1.2f, 1.4f, 1.8f, 2.0f, 2.2f, 2.5f, 2.8f,
        3.2f, 3.5f, 4.0f, 4.5f, 5.0f, 5.6f,
        6.3f, 7.1f, 8.0f, 9.0f, 10f, 11f, 13f, 16f
    )

    /** Exposure compensation in 1/3 EV increments (-3.0 to +3.0). */
    val EV_STOPS: List<Float> = (-9..9).map { it / 3f }

    /**
     * Formats shutter speed in authentic Canon Top-LCD vs Q-Menu notation:
     * - Top LCD shows `250` for 1/250s, `0"5` for 0.5s, `1"` for 1s.
     * - Q-Menu shows `1/250` for 1/250s.
     */
    fun formatShutter(ns: Long, compactTopLcd: Boolean = false): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 1.0) {
            val tenths = ((seconds - seconds.toInt()) * 10).roundToInt()
            if (tenths == 0) "${seconds.toInt()}\"" else "${seconds.toInt()}\"$tenths"
        } else if (seconds >= 0.3) {
            val tenths = (seconds * 10).roundToInt()
            "0\"$tenths"
        } else {
            val denom = (1.0 / seconds).roundToInt()
            if (compactTopLcd) "$denom" else "1/$denom"
        }
    }

    fun formatAperture(fStop: Float, includePrefix: Boolean = true): String {
        val value = if (fStop >= 10f) "${fStop.roundToInt()}" else "%.1f".format(fStop)
        return if (includePrefix) "F$value" else value
    }

    fun formatIso(iso: Int, compact: Boolean = false): String = when {
        iso <= 0 -> "AUTO"
        iso == 50 -> if (compact) "L" else "L(50)"
        iso == 51200 -> if (compact) "H1" else "H1(51200)"
        iso == 102400 -> if (compact) "H2" else "H2(102400)"
        else -> "$iso"
    }

    fun nearestShutterStop(ns: Long): Long {
        val targetLog = kotlin.math.ln(ns.coerceAtLeast(100_000L).toDouble())
        return SHUTTER_STOPS_NS.minByOrNull { stop ->
            abs(kotlin.math.ln(stop.toDouble()) - targetLog)
        } ?: ns
    }

    fun nearestIsoStop(iso: Int): Int {
        val clamped = iso.coerceIn(100, 25600)
        val targetLog = kotlin.math.ln(clamped.toDouble())
        return ISO_STOPS.asSequence()
            .filter { it in 100..25600 }
            .minByOrNull { stop -> abs(kotlin.math.ln(stop.toDouble()) - targetLog) }
            ?: clamped
    }

    fun formatFocalLength35mm(zoomRatio: Float, baseFocal35mm: Float = 24f): String =
        "${(baseFocal35mm * zoomRatio).roundToInt()}mm"

    fun formatZoom(zoomRatio: Float): String =
        if (zoomRatio >= 9.95f) "${zoomRatio.roundToInt()}x" else "%.1fx".format(zoomRatio)

    fun formatEv(ev: Float): String {
        if (abs(ev) < 0.05f) return "0"
        val sign = if (ev > 0) "+" else "-"
        val absEv = abs(ev)
        val whole = absEv.toInt()
        val third = ((absEv - whole) * 3f).roundToInt()
        return when {
            whole == 0 && third == 1 -> "${sign}1/3"
            whole == 0 && third == 2 -> "${sign}2/3"
            third == 0 -> "$sign$whole"
            else -> "$sign$whole $third/3"
        }
    }

    fun <T> stepList(list: List<T>, current: T, deltaSteps: Int): T {
        if (list.isEmpty()) return current
        val idx = list.indexOf(current).let { if (it >= 0) it else 0 }
        val nextIdx = (idx + deltaSteps).coerceIn(0, list.lastIndex)
        return list[nextIdx]
    }
}
