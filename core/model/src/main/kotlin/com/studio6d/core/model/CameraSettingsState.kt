package com.studio6d.core.model

/**
 * Which parameter the Quick Control Dial (or Main Dial) is currently adjusting.
 */
enum class ActiveDialTarget(val label: String) {
    SHUTTER("TV / Shutter"),
    APERTURE("AV / Aperture"),
    ISO("ISO Speed"),
    EXPOSURE_COMP("Exposure Comp."),
    LENS_ZOOM("Optical / Sensor Zoom"),
    WHITE_BALANCE("White Balance"),
    PICTURE_STYLE("Picture Style"),
    AF_MODE("AF Operation"),
    DRIVE_MODE("Drive Mode"),
    METERING_MODE("Metering Mode"),
    MANUAL_FOCUS("Manual Focus")
}

/**
 * Immutable state representing the full Digital Mango 6D camera configuration and live telemetry.
 */
data class CameraSettingsState(
    val isCameraPoweredOn: Boolean = true,
    val exposureMode: ExposureMode = ExposureMode.APERTURE_PRIORITY,
    val shutterSpeedNs: Long = 4_000_000L, // 1/250s
    val simulatedApertureF: Float = 2.0f, // Wide prime aperture for natural creamy lens bokeh
    val opticalBokehEnabled: Boolean = true,
    val iso: Int = 0, // 0 = AUTO
    val evCompensation: Float = 0f,
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 8.0f,
    val externalLens: ExternalLensProfile = ExternalLensProfile.STOCK_24MM,
    val isAeLocked: Boolean = false,
    val flashMode: FlashMode = FlashMode.OFF,
    val aebEnabled: Boolean = false,
    val highlightAlertEnabled: Boolean = false,
    val gridMode: GridMode = GridMode.THIRDS_3X3,
    val wbPreset: WhiteBalancePreset = WhiteBalancePreset.AWB,
    val customKelvin: Int = 5200,
    val afMode: AfMode = AfMode.ONE_SHOT,
    val manualFocusDiopter: Float = 0f, // 0.0 (infinity) .. minFocusDistance
    val isAutoAfSelection: Boolean = true,
    val activeAfPointIndices: List<Int> = listOf(5),
    val selectedAfPointIndex: Int = 5, // Center cross-type AF point
    val customFocusNormX: Float = 0.50f,
    val customFocusNormY: Float = 0.50f,
    val afFocusPulseSeq: Int = 0,
    val afFocusFailedSeq: Int = 0,
    val liveViewZoomFactor: Float = 1f, // 1f, 5f, 10f MF check loupe
    val driveMode: DriveMode = DriveMode.SINGLE,
    val meteringMode: MeteringMode = MeteringMode.EVALUATIVE,
    val pictureStyle: PictureStyle = PictureStyle.STANDARD,
    val contrastOffset: Int = 0,     // -4..+4
    val saturationOffset: Int = 0,   // -4..+4
    val colorToneOffset: Int = 0,    // -4..+4
    val lutIntensity: Float = 0.48f,
    val highlightRolloff: Float = 0.14f,
    val saveRawDng: Boolean = false,
    val topLcdAmberBacklight: Boolean = false,
    val infoDisplayMode: InfoDisplayMode = InfoDisplayMode.LIVE_VIEW_OVERLAYS,
    val isQMenuOpen: Boolean = false,
    val isMenuOpen: Boolean = false,
    val isPlaybackOpen: Boolean = false,
    val isMultiControlLocked: Boolean = false,
    val soundEnabled: Boolean = true,
    val activeDialTarget: ActiveDialTarget = ActiveDialTarget.APERTURE,
    val isHalfPressedAfLocked: Boolean = false,
    val isAfHunting: Boolean = false,
    val isSubjectFocused: Boolean = false,
    val lensFocusDiopters: Float = 0f,
    val isCapturing: Boolean = false,
    val pendingSaveCount: Int = 0,
    val shutterReflexSeq: Int = 0,
    val remainingShotsEstimate: Int = 428,
    val lastCapturedPath: String? = null,
    val statusBanner: String? = null,
    val measuredEvOffset: Float = 0f,
    val liveMeteredShutterNs: Long = 4_000_000L,
    val liveMeteredIso: Int = 400,
    val histogramBins: FloatArray = FloatArray(32)
) {
    val effectiveKelvin: Int?
        get() = if (wbPreset == WhiteBalancePreset.KELVIN) customKelvin else wbPreset.kelvin

    /**
     * Optical disc-bokeh intensity derived from physical aperture stop (f/1.2..f/16).
     */
    val opticalBokehStrength: Float
        get() {
            if (!opticalBokehEnabled || simulatedApertureF >= 8.0f) return 0f
            val base = when {
                simulatedApertureF <= 1.25f -> 0.80f
                simulatedApertureF <= 1.5f -> 0.68f
                simulatedApertureF <= 2.1f -> 0.52f
                simulatedApertureF <= 2.9f -> 0.36f
                simulatedApertureF <= 4.1f -> 0.20f
                simulatedApertureF <= 5.7f -> 0.09f
                else -> 0f
            }
            return (base * externalLens.bokehAssistScale).coerceIn(0f, 0.85f)
        }

    /**
     * Peripheral Illumination Corrected Full-Frame lens optical vignette.
     */
    val simulatedVignetteStrength: Float
        get() = when {
            simulatedApertureF <= 1.4f -> 0.06f
            simulatedApertureF <= 2.0f -> 0.04f
            simulatedApertureF <= 2.8f -> 0.02f
            else -> 0.0f
        }

    val simulatedGrainStrength: Float
        get() = if (pictureStyle == PictureStyle.MONOCHROME) 0.014f else 0.0f
}
