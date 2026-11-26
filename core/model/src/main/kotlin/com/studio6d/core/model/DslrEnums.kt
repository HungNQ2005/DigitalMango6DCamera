package com.studio6d.core.model

/**
 * Exposure modes modeled after the Canon EOS 6D lockable Mode Dial.
 */
enum class ExposureMode(val code: String, val displayName: String) {
    MANUAL("M", "Manual Exposure"),
    APERTURE_PRIORITY("Av", "Aperture-Priority AE"),
    SHUTTER_PRIORITY("Tv", "Shutter-Priority AE"),
    PROGRAM("P", "Program AE"),
    SCENE_AUTO("A+", "Scene Intelligent Auto"),
    CREATIVE_AUTO("CA", "Creative Auto"),
    CUSTOM_1("C1", "Custom Shooting Mode 1"),
    CUSTOM_2("C2", "Custom Shooting Mode 2");

    val allowsManualShutter: Boolean
        get() = this == MANUAL || this == SHUTTER_PRIORITY || this == CUSTOM_1 || this == CUSTOM_2

    val allowsManualAperture: Boolean
        get() = this == MANUAL || this == APERTURE_PRIORITY || this == CUSTOM_1 || this == CUSTOM_2

    val allowsExposureCompensation: Boolean
        get() = this == APERTURE_PRIORITY || this == SHUTTER_PRIORITY || this == PROGRAM
}

enum class AfMode(val lcdLabel: String, val fullLabel: String) {
    ONE_SHOT("ONE SHOT", "One-Shot AF"),
    AI_FOCUS("AI FOCUS", "AI Focus AF"),
    AI_SERVO("AI SERVO", "AI Servo AF"),
    MANUAL("MF", "Manual Focus")
}

enum class DriveMode(val badge: String, val label: String, val isSilent: Boolean = false) {
    SINGLE("[ ]", "Single Shooting"),
    CONTINUOUS("[H]", "Continuous Shooting (4.5 fps)"),
    SILENT_SINGLE("[S]", "Silent Single Shooting", isSilent = true),
    SILENT_CONTINUOUS("[Sc]", "Silent Continuous (3 fps)", isSilent = true),
    TIMER_10S("[10s]", "Self-timer: 10 sec / Remote"),
    TIMER_2S("[2s]", "Self-timer: 2 sec")
}

enum class MeteringMode(val symbol: String, val label: String) {
    EVALUATIVE("(O)", "Evaluative Metering"),
    PARTIAL("( )", "Partial Metering (8%)"),
    SPOT("(.)", "Spot Metering (3.5%)"),
    CENTER_WEIGHTED("[]", "Center-Weighted Average")
}

enum class WhiteBalancePreset(val code: String, val label: String, val kelvin: Int?, val tint: Float = 0f) {
    AWB("AWB", "Auto White Balance (Ambience)", null),
    DAYLIGHT("DAY", "Daylight (~5200K)", 5200),
    SHADE("SHD", "Shade (~7000K)", 7000),
    CLOUDY("CLD", "Cloudy (~6000K)", 6000),
    TUNGSTEN("TNG", "Tungsten Light (~3200K)", 3200),
    FLUORESCENT("FLU", "White Fluorescent (~4000K)", 4000, tint = 4f),
    KELVIN("K", "Color Temperature (2500K-10000K)", 5500)
}

enum class FlashMode(val badge: String, val label: String) {
    OFF("⚡OFF", "Flash Off"),
    AUTO("⚡A", "Auto Flash"),
    ON("⚡ON", "Fill Flash On"),
    TORCH("⚡LED", "Modeling Torch")
}

enum class GridMode(val label: String) {
    OFF("Off"),
    THIRDS_3X3("3x3 Grid"),
    GOLDEN_RATIO("Phi Grid"),
    CROSSHAIR("Crosshair")
}

enum class InfoDisplayMode {
    /** Clean 3:2 Live View with minimal bottom exposure bar */
    LIVE_VIEW_CLEAN,
    /** 3:2 Live View + Grid + Real-time Luminance Histogram + Electronic Level */
    LIVE_VIEW_OVERLAYS,
    /** Classic DSLR rear Shooting Info dashboard */
    SHOOTING_INFO_SCREEN;

    fun next(): InfoDisplayMode = entries[(ordinal + 1) % entries.size]
}
