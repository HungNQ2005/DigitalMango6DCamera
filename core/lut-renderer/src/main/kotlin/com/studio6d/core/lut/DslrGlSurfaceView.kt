package com.studio6d.core.lut

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import com.studio6d.core.model.PictureStyle

/**
 * GLSurfaceView wrapper for 3:2 Live View preview with real-time 3D LUT, separable optical bokeh,
 * and automatic 11-point AF saliency tracking.
 */
class DslrGlSurfaceView(
    context: Context,
    onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    onHistogramUpdated: ((FloatArray) -> Unit)? = null,
    onAutoAfPointsUpdated: ((List<Int>) -> Unit)? = null
) : GLSurfaceView(context) {

    val lutRenderer: CameraLutGlRenderer

    init {
        setEGLContextClientVersion(3)
        lutRenderer = CameraLutGlRenderer(
            context = context,
            onSurfaceTextureReady = onSurfaceTextureReady,
            requestRenderCallback = { requestRender() },
            onHistogramUpdated = onHistogramUpdated,
            onAutoAfPointsUpdated = onAutoAfPointsUpdated
        )
        setRenderer(lutRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun applyStyleAndParams(
        style: PictureStyle,
        lutIntensity: Float,
        highlightRolloff: Float,
        vignetteStrength: Float,
        grainStrength: Float,
        bokehStrength: Float,
        focusCenterX: Float,
        focusCenterY: Float,
        isSubjectFocused: Boolean,
        isAutoAfSelection: Boolean,
        lensFocusDiopters: Float,
        contrastOffset: Int,
        saturationOffset: Int,
        colorToneOffset: Int,
        highlightAlertEnabled: Boolean,
        focusPeaking: Boolean,
        displayRotation: Int = lutRenderer.displayRotation,
        sensorAspect: Float = lutRenderer.sensorAspect,
        zoomFactor: Float = 1f,
        zoomCenterX: Float = 0.5f,
        zoomCenterY: Float = 0.5f
    ) {
        lutRenderer.setPictureStyle(style)
        lutRenderer.lutIntensity = lutIntensity
        lutRenderer.highlightRolloff = highlightRolloff
        lutRenderer.vignetteStrength = vignetteStrength
        lutRenderer.grainStrength = grainStrength
        lutRenderer.bokehStrength = bokehStrength
        lutRenderer.focusCenterX = focusCenterX
        lutRenderer.focusCenterY = focusCenterY
        lutRenderer.isSubjectFocused = isSubjectFocused
        lutRenderer.isAutoAfSelection = isAutoAfSelection
        lutRenderer.lensFocusDiopters = lensFocusDiopters
        lutRenderer.contrastOffset = contrastOffset
        lutRenderer.saturationOffset = saturationOffset
        lutRenderer.colorToneOffset = colorToneOffset
        lutRenderer.highlightAlertEnabled = highlightAlertEnabled
        lutRenderer.focusPeakingEnabled = focusPeaking
        lutRenderer.displayRotation = displayRotation
        lutRenderer.sensorAspect = sensorAspect
        lutRenderer.zoomFactor = zoomFactor
        lutRenderer.zoomCenterX = zoomCenterX
        lutRenderer.zoomCenterY = zoomCenterY
        requestRender()
    }
}
