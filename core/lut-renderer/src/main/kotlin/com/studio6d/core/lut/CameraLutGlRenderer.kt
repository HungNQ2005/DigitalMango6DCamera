package com.studio6d.core.lut

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import com.studio6d.core.model.PictureStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * OpenGL ES 3.0 Live View renderer applying:
 * - Dedicated GPU VBOs (`stride = 0`) + matched `highp` GLSL precision
 * - Asynchronous 3-tier Neural Object-Silhouette Segmentation (`OpticalSubjectSegmenter`)
 *   + Real-time Canon 11-point AF saliency detection (`onAutoAfPointsUpdated`)
 * - 2-Pass Separable Foreground-Excluded Optical Bokeh (smooth Gaussian DoF, zero grainy stipple)
 * - Highlight roll-off, 3D LUT (.cube), Picture Style fine-tuning, subtle vignette, optional monochrome grain,
 *   Highlight Alert blinkies & MF Focus Peaking
 */
class CameraLutGlRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val requestRenderCallback: () -> Unit,
    private val onHistogramUpdated: ((FloatArray) -> Unit)? = null,
    private val onAutoAfPointsUpdated: ((List<Int>) -> Unit)? = null
) : GLSurfaceView.Renderer {

    @Volatile var lutIntensity: Float = 0.48f
    @Volatile var highlightRolloff: Float = 0.14f
    @Volatile var vignetteStrength: Float = 0.04f
    @Volatile var grainStrength: Float = 0.00f
    @Volatile var bokehStrength: Float = 0.52f
    @Volatile var focusCenterX: Float = 0.50f
    @Volatile var focusCenterY: Float = 0.50f
    @Volatile var isSubjectFocused: Boolean = false
    @Volatile var isAutoAfSelection: Boolean = true
    @Volatile var lensFocusDiopters: Float = 0f
    @Volatile var contrastOffset: Int = 0
    @Volatile var saturationOffset: Int = 0
    @Volatile var colorToneOffset: Int = 0
    @Volatile var highlightAlertEnabled: Boolean = false
    @Volatile var focusPeakingEnabled: Boolean = false
    @Volatile var displayRotation: Int = Surface.ROTATION_90
    @Volatile var sensorAspect: Float = 4f / 3f
    @Volatile var zoomFactor: Float = 1f
    @Volatile var zoomCenterX: Float = 0.5f
    @Volatile var zoomCenterY: Float = 0.5f

    private var currentStyle: PictureStyle = PictureStyle.STANDARD
    private val lutDirty = AtomicBoolean(true)

    private var programId = 0
    private var liveSepHProgramId = 0
    private var liveSepVProgramId = 0

    private var posVboId = 0
    private var uvVboId = 0
    private var oesTextureId = 0
    private var lutTextureId = 0
    private var subjectMaskTexId = 0
    private var surfaceTexture: SurfaceTexture? = null

    private var blurFboA = 0
    private var blurTexA = 0
    private var blurFboB = 0
    private var blurTexB = 0
    private var blurW = 0
    private var blurH = 0

    private var histFboId = 0
    private var histTexId = 0
    private var histFrameCounter = 0
    private val histPixelBuffer = ByteBuffer.allocateDirect(MASK_W * MASK_H * 4).order(ByteOrder.nativeOrder())
    private val uprightRgba = ByteArray(MASK_W * MASK_H * 4)
    private val maskUploadBuffer = ByteBuffer.allocateDirect(MASK_W * MASK_H).order(ByteOrder.nativeOrder())
    private val smoothMask = FloatArray(MASK_W * MASK_H) { 0f }

    private val segScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val segBusy = AtomicBoolean(false)
    @Volatile private var pendingSubjectMask: SubjectMaskData? = null
    @Volatile private var pendingMaskFocusX = 0.5f
    @Volatile private var pendingMaskFocusY = 0.5f
    @Volatile private var lastAfReportMs = 0L
    private var hasValidLiveSubjectMask = false
    private var lastMaskFocusX = -1f
    private var lastMaskFocusY = -1f
    private var prevFocusedState = false

    private val stMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val frameAvailable = AtomicBoolean(false)
    private var startTimeNs = System.nanoTime()

    private var uStMatrixLoc = -1
    private var uCameraTexLoc = -1
    private var uLutTexLoc = -1
    private var uSubjectMaskTexLoc = -1
    private var uBlurBgTexLoc = -1
    private var uLutSizeLoc = -1
    private var uLutIntensityLoc = -1
    private var uHighlightRolloffLoc = -1
    private var uVignetteLoc = -1
    private var uGrainLoc = -1
    private var uTimeLoc = -1
    private var uFocusPeakingLoc = -1
    private var uTexelSizeLoc = -1
    private var uRotationModeLoc = -1
    private var uCropScaleLoc = -1
    private var uZoomFactorLoc = -1
    private var uZoomCenterLoc = -1
    private var uBokehStrengthLoc = -1
    private var uHasSubjectLoc = -1
    private var uContrastLoc = -1
    private var uSaturationLoc = -1
    private var uColorToneLoc = -1
    private var uHighlightAlertLoc = -1

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var currentLutSize = 17f

    fun setPictureStyle(style: PictureStyle) {
        if (currentStyle != style) {
            currentStyle = style
            lutDirty.set(true)
            requestRenderCallback()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        liveSepHProgramId = createProgram(VERTEX_SHADER, LIVE_SEP_H_FRAGMENT_SHADER)
        liveSepVProgramId = createProgram(VERTEX_SHADER, LIVE_SEP_V_FRAGMENT_SHADER)

        uStMatrixLoc = GLES30.glGetUniformLocation(programId, "uStMatrix")
        uCameraTexLoc = GLES30.glGetUniformLocation(programId, "uCameraTex")
        uLutTexLoc = GLES30.glGetUniformLocation(programId, "uLutTex")
        uSubjectMaskTexLoc = GLES30.glGetUniformLocation(programId, "uSubjectMaskTex")
        uBlurBgTexLoc = GLES30.glGetUniformLocation(programId, "uBlurBgTex")
        uLutSizeLoc = GLES30.glGetUniformLocation(programId, "uLutSize")
        uLutIntensityLoc = GLES30.glGetUniformLocation(programId, "uLutIntensity")
        uHighlightRolloffLoc = GLES30.glGetUniformLocation(programId, "uHighlightRolloff")
        uVignetteLoc = GLES30.glGetUniformLocation(programId, "uVignette")
        uGrainLoc = GLES30.glGetUniformLocation(programId, "uGrain")
        uTimeLoc = GLES30.glGetUniformLocation(programId, "uTime")
        uFocusPeakingLoc = GLES30.glGetUniformLocation(programId, "uFocusPeaking")
        uTexelSizeLoc = GLES30.glGetUniformLocation(programId, "uTexelSize")
        uRotationModeLoc = GLES30.glGetUniformLocation(programId, "uRotationMode")
        uCropScaleLoc = GLES30.glGetUniformLocation(programId, "uCropScale")
        uZoomFactorLoc = GLES30.glGetUniformLocation(programId, "uZoomFactor")
        uZoomCenterLoc = GLES30.glGetUniformLocation(programId, "uZoomCenter")
        uBokehStrengthLoc = GLES30.glGetUniformLocation(programId, "uBokehStrength")
        uHasSubjectLoc = GLES30.glGetUniformLocation(programId, "uHasSubject")
        uContrastLoc = GLES30.glGetUniformLocation(programId, "uContrast")
        uSaturationLoc = GLES30.glGetUniformLocation(programId, "uSaturation")
        uColorToneLoc = GLES30.glGetUniformLocation(programId, "uColorTone")
        uHighlightAlertLoc = GLES30.glGetUniformLocation(programId, "uHighlightAlert")

        createQuadVbos()
        oesTextureId = createOesTexture()
        lutTextureId = create3dLutTexture()
        subjectMaskTexId = createSubjectMaskTexture()
        createHistogramFbo()
        createLiveBlurFbos()
        lutDirty.set(true)

        surfaceTexture?.release()
        val st = SurfaceTexture(oesTextureId).apply {
            setOnFrameAvailableListener {
                frameAvailable.set(true)
                requestRenderCallback()
            }
        }
        surfaceTexture = st
        onSurfaceTextureReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        val hadNewFrame = frameAvailable.compareAndSet(true, false)
        if (hadNewFrame) {
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
        }

        if (lutDirty.compareAndSet(true, false)) {
            uploadLutToGpu(currentStyle)
        }

        val freshMask = pendingSubjectMask
        if (freshMask != null) {
            pendingSubjectMask = null
            consumeSubjectMaskOnGlThread(freshMask, pendingMaskFocusX, pendingMaskFocusY)
        }
        if (bokehStrength <= 0.01f) {
            hasValidLiveSubjectMask = false
        }

        val rotMode = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 1
            Surface.ROTATION_180 -> 2
            Surface.ROTATION_270 -> 3
            else -> 1
        }

        val vpAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val sAspect = sensorAspect.coerceIn(0.5f, 2.5f)
        val cropScaleX = if (vpAspect < sAspect) vpAspect / sAspect else 1f
        val cropScaleY = if (vpAspect > sAspect) sAspect / vpAspect else 1f

        val z = zoomFactor.coerceIn(1f, 10f)
        val halfSpan = 0.5f / z
        val zCenterX = zoomCenterX.coerceIn(halfSpan, 1f - halfSpan)
        val zCenterY = (1f - zoomCenterY).coerceIn(halfSpan, 1f - halfSpan)

        val canBokeh = isSubjectFocused && bokehStrength > 0.01f && hasValidLiveSubjectMask

        if (canBokeh && liveSepHProgramId != 0 && liveSepVProgramId != 0 && blurFboA != 0 && blurFboB != 0) {
            renderSeparableLiveBlur(
                rotMode = rotMode,
                cropScaleX = cropScaleX,
                cropScaleY = cropScaleY,
                z = z,
                zCenterX = zCenterX,
                zCenterY = zCenterY,
                aspect = vpAspect
            )
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        drawQuad(
            rotMode = rotMode,
            cropScaleX = cropScaleX,
            cropScaleY = cropScaleY,
            z = z,
            zCenterX = zCenterX,
            zCenterY = zCenterY,
            enablePeaking = focusPeakingEnabled,
            enableAlert = highlightAlertEnabled,
            enableBokeh = if (canBokeh) bokehStrength else 0f,
            hasSubject = canBokeh,
            texelW = 1f / viewportWidth,
            texelH = 1f / viewportHeight
        )

        val newlyFocused = isSubjectFocused && !prevFocusedState
        prevFocusedState = isSubjectFocused

        val callback = onHistogramUpdated
        if (hadNewFrame && histFboId != 0) {
            histFrameCounter = (histFrameCounter + 1) % 6
            if (histFrameCounter == 0 || newlyFocused) {
                sampleHistogramAndSubjectMask(
                    rotMode = rotMode,
                    cropScaleX = cropScaleX,
                    cropScaleY = cropScaleY,
                    z = z,
                    zCenterX = zCenterX,
                    zCenterY = zCenterY,
                    callback = if (histFrameCounter == 0) callback else null
                )
            }
        }
    }

    private fun renderSeparableLiveBlur(
        rotMode: Int,
        cropScaleX: Float,
        cropScaleY: Float,
        z: Float,
        zCenterX: Float,
        zCenterY: Float,
        aspect: Float
    ) {
        val targetW = (viewportWidth / 2).coerceAtLeast(160)
        val targetH = (viewportHeight / 2).coerceAtLeast(106)
        if (blurW != targetW || blurH != targetH) {
            blurW = targetW
            blurH = targetH
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexA)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, blurW, blurH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexB)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, blurW, blurH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        }

        val stepX = (0.0028f * bokehStrength) / aspect.coerceAtLeast(0.5f)
        val stepY = 0.0028f * bokehStrength

        // Pass H: OES -> blurFboA
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboA)
        GLES30.glViewport(0, 0, blurW, blurH)
        GLES30.glUseProgram(liveSepHProgramId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(liveSepHProgramId, "uCameraTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, subjectMaskTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(liveSepHProgramId, "uSubjectMaskTex"), 2)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(liveSepHProgramId, "uStMatrix"), 1, false, stMatrix, 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(liveSepHProgramId, "uRotationMode"), rotMode)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(liveSepHProgramId, "uCropScale"), cropScaleX, cropScaleY)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(liveSepHProgramId, "uZoomFactor"), z)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(liveSepHProgramId, "uZoomCenter"), zCenterX, zCenterY)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(liveSepHProgramId, "uStepX"), stepX)
        drawQuadGeometry()

        // Pass V: blurTexA -> blurFboB
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFboB)
        GLES30.glViewport(0, 0, blurW, blurH)
        GLES30.glUseProgram(liveSepVProgramId)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexA)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(liveSepVProgramId, "uInputTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, subjectMaskTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(liveSepVProgramId, "uSubjectMaskTex"), 2)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(liveSepVProgramId, "uStepY"), stepY)
        drawQuadGeometry()
    }

    private fun consumeSubjectMaskOnGlThread(
        maskData: SubjectMaskData,
        fx: Float,
        fy: Float
    ) {
        val wasValid = hasValidLiveSubjectMask
        val valid = maskData.hasSubject && maskData.width == MASK_W && maskData.height == MASK_H &&
            maskData.maskBytes.size >= MASK_W * MASK_H
        hasValidLiveSubjectMask = valid
        if (!valid) return

        val focusMoved = abs(fx - lastMaskFocusX) > 0.06f || abs(fy - lastMaskFocusY) > 0.06f
        val snapDirect = !wasValid || focusMoved
        lastMaskFocusX = fx
        lastMaskFocusY = fy

        maskUploadBuffer.position(0)
        for (i in smoothMask.indices) {
            val target = (maskData.maskBytes[i].toInt() and 0xFF).toFloat()
            val blended = if (snapDirect) target else (smoothMask[i] * 0.35f + target * 0.65f)
            smoothMask[i] = blended
            maskUploadBuffer.put(blended.toInt().coerceIn(0, 255).toByte())
        }
        maskUploadBuffer.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, subjectMaskTexId)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, 0, 0, MASK_W, MASK_H,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskUploadBuffer
        )
    }

    private fun drawQuad(
        rotMode: Int,
        cropScaleX: Float,
        cropScaleY: Float,
        z: Float,
        zCenterX: Float,
        zCenterY: Float,
        enablePeaking: Boolean,
        enableAlert: Boolean,
        enableBokeh: Float,
        hasSubject: Boolean,
        texelW: Float,
        texelH: Float
    ) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (programId == 0) return
        GLES30.glUseProgram(programId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES30.glUniform1i(uCameraTexLoc, 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glUniform1i(uLutTexLoc, 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, subjectMaskTexId)
        GLES30.glUniform1i(uSubjectMaskTexLoc, 2)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexB)
        GLES30.glUniform1i(uBlurBgTexLoc, 3)

        GLES30.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)
        GLES30.glUniform1f(uLutSizeLoc, currentLutSize)
        GLES30.glUniform1f(uLutIntensityLoc, lutIntensity)
        GLES30.glUniform1f(uHighlightRolloffLoc, highlightRolloff)
        GLES30.glUniform1f(uVignetteLoc, vignetteStrength)
        GLES30.glUniform1f(uGrainLoc, grainStrength)
        GLES30.glUniform1f(uTimeLoc, ((System.nanoTime() - startTimeNs) / 1_000_000_000.0).toFloat())
        GLES30.glUniform1f(uFocusPeakingLoc, if (enablePeaking) 1f else 0f)
        GLES30.glUniform2f(uTexelSizeLoc, texelW, texelH)
        GLES30.glUniform1i(uRotationModeLoc, rotMode)
        GLES30.glUniform2f(uCropScaleLoc, cropScaleX, cropScaleY)
        GLES30.glUniform1f(uZoomFactorLoc, z)
        GLES30.glUniform2f(uZoomCenterLoc, zCenterX, zCenterY)
        GLES30.glUniform1f(uBokehStrengthLoc, enableBokeh)
        GLES30.glUniform1f(uHasSubjectLoc, if (hasSubject) 1f else 0f)
        GLES30.glUniform1f(uContrastLoc, contrastOffset.toFloat())
        GLES30.glUniform1f(uSaturationLoc, saturationOffset.toFloat())
        GLES30.glUniform1f(uColorToneLoc, colorToneOffset.toFloat())
        GLES30.glUniform1f(uHighlightAlertLoc, if (enableAlert) 1f else 0f)

        drawQuadGeometry()
    }

    private fun drawQuadGeometry() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, posVboId)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVboId)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun createQuadVbos() {
        val vbos = IntArray(2)
        GLES30.glGenBuffers(2, vbos, 0)
        posVboId = vbos[0]
        uvVboId = vbos[1]

        val posBuf = ByteBuffer.allocateDirect(QUAD_POS.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        posBuf.put(QUAD_POS).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, posVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD_POS.size * 4, posBuf, GLES30.GL_STATIC_DRAW)

        val uvBuf = ByteBuffer.allocateDirect(QUAD_UV.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        uvBuf.put(QUAD_UV).position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVboId)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD_UV.size * 4, uvBuf, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun sampleHistogramAndSubjectMask(
        rotMode: Int,
        cropScaleX: Float,
        cropScaleY: Float,
        z: Float,
        zCenterX: Float,
        zCenterY: Float,
        callback: ((FloatArray) -> Unit)?
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFboId)
        GLES30.glViewport(0, 0, MASK_W, MASK_H)
        drawQuad(
            rotMode = rotMode,
            cropScaleX = cropScaleX,
            cropScaleY = cropScaleY,
            z = z,
            zCenterX = zCenterX,
            zCenterY = zCenterY,
            enablePeaking = false,
            enableAlert = false,
            enableBokeh = 0f,
            hasSubject = false,
            texelW = 1f / MASK_W,
            texelH = 1f / MASK_H
        )
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        histPixelBuffer.position(0)
        GLES30.glReadPixels(0, 0, MASK_W, MASK_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, histPixelBuffer)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)

        // Update 32-bin histogram
        if (callback != null) {
            val bins = FloatArray(32)
            histPixelBuffer.position(0)
            val total = MASK_W * MASK_H
            for (i in 0 until total step 2) {
                val base = i * 4
                val r = histPixelBuffer.get(base).toInt() and 0xFF
                val g = histPixelBuffer.get(base + 1).toInt() and 0xFF
                val b = histPixelBuffer.get(base + 2).toInt() and 0xFF
                val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                val bin = (lum * 31f).toInt().coerceIn(0, 31)
                bins[bin] += 1f
            }
            val maxVal = bins.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            for (i in bins.indices) {
                bins[i] = (bins[i] / maxVal).coerceIn(0f, 1f)
            }
            callback(bins)
        }

        // Dispatch background segmentation + Canon 11-point AF saliency detection
        val needSeg = bokehStrength > 0.01f
        val needAfDetect = isAutoAfSelection && onAutoAfPointsUpdated != null
        if ((needSeg || needAfDetect) && segBusy.compareAndSet(false, true)) {
            val rowBytes = MASK_W * 4
            for (y in 0 until MASK_H) {
                val srcY = MASK_H - 1 - y
                histPixelBuffer.position(srcY * rowBytes)
                histPixelBuffer.get(uprightRgba, y * rowBytes, rowBytes)
            }
            val frameRgba = uprightRgba.copyOf()
            val fx = focusCenterX
            val fy = focusCenterY
            val diopters = lensFocusDiopters
            val autoAf = isAutoAfSelection
            val runSeg = needSeg
            val reportAf = needAfDetect
            segScope.launch {
                try {
                    val segmenter = OpticalSubjectSegmenter.getInstance(context)
                    val segResult = if (runSeg) {
                        segmenter.segmentLiveFrame(
                            uprightRgba = frameRgba,
                            width = MASK_W,
                            height = MASK_H,
                            focusNormX = fx,
                            focusNormY = fy,
                            lensDiopters = diopters,
                            isAutoMultiPointAf = autoAf
                        )
                    } else {
                        null
                    }
                    if (segResult != null && bokehStrength > 0.01f) {
                        pendingMaskFocusX = fx
                        pendingMaskFocusY = fy
                        pendingSubjectMask = segResult
                        requestRenderCallback()
                    }
                    if (reportAf) {
                        val now = System.currentTimeMillis()
                        if (now - lastAfReportMs >= 280L) {
                            lastAfReportMs = now
                            val points = segmenter.detectSalientAfPoints(frameRgba, MASK_W, MASK_H, segResult)
                            onAutoAfPointsUpdated?.invoke(points)
                        }
                    }
                } finally {
                    segBusy.set(false)
                }
            }
        }
    }

    private fun createLiveBlurFbos() {
        val fbos = IntArray(2)
        val texs = IntArray(2)
        GLES30.glGenFramebuffers(2, fbos, 0)
        GLES30.glGenTextures(2, texs, 0)
        blurFboA = fbos[0]
        blurFboB = fbos[1]
        blurTexA = texs[0]
        blurTexB = texs[1]

        blurW = 240
        blurH = 160

        for (i in 0..1) {
            val tex = texs[i]
            val fbo = fbos[i]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, blurW, blurH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun createSubjectMaskTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        maskUploadBuffer.position(0)
        repeat(MASK_W * MASK_H) { maskUploadBuffer.put(0xFF.toByte()) }
        maskUploadBuffer.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
            MASK_W, MASK_H, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskUploadBuffer
        )
        return ids[0]
    }

    private fun createHistogramFbo() {
        val fboIds = IntArray(1)
        val texIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        GLES30.glGenTextures(1, texIds, 0)
        histFboId = fboIds[0]
        histTexId = texIds[0]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, histTexId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
            MASK_W, MASK_H, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, histFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, histTexId, 0
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun uploadLutToGpu(style: PictureStyle) {
        val lut = CubeLutParser.loadFromAssets(context, style.cubeAssetPath)
        currentLutSize = lut.size.toFloat()
        val buffer = lut.toRgbaByteBuffer()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTextureId)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D,
            0,
            GLES30.GL_RGBA8,
            lut.size,
            lut.size,
            lut.size,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            buffer
        )
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun create3dLutTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed ($type): ${GLES30.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    companion object {
        private const val TAG = "CameraLutGlRenderer"
        private const val MASK_W = 192
        private const val MASK_H = 128

        private val QUAD_POS = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )

        private val QUAD_UV = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        private const val VERTEX_SHADER = """#version 300 es
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            uniform mat4 uStMatrix;
            uniform int uRotationMode;
            uniform vec2 uCropScale;
            uniform float uZoomFactor;
            uniform vec2 uZoomCenter;
            out vec2 vTexCoord;
            out vec2 vScreenUv;

            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vScreenUv = aTexCoord;

                vec2 zoomed = (aTexCoord - uZoomCenter) / uZoomFactor + uZoomCenter;
                vec2 cropped = (zoomed - 0.5) * uCropScale + 0.5;

                vec2 portraitUv;
                if (uRotationMode == 1) {
                    portraitUv = vec2(cropped.y, 1.0 - cropped.x);
                } else if (uRotationMode == 3) {
                    portraitUv = vec2(1.0 - cropped.y, cropped.x);
                } else if (uRotationMode == 2) {
                    portraitUv = vec2(1.0 - cropped.x, 1.0 - cropped.y);
                } else {
                    portraitUv = cropped;
                }

                vTexCoord = (uStMatrix * vec4(portraitUv, 0.0, 1.0)).xy;
            }
        """

        private const val LIVE_SEP_H_FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;

            in vec2 vTexCoord;
            in vec2 vScreenUv;

            uniform samplerExternalOES uCameraTex;
            uniform sampler2D uSubjectMaskTex;
            uniform mat4 uStMatrix;
            uniform int uRotationMode;
            uniform vec2 uCropScale;
            uniform float uZoomFactor;
            uniform vec2 uZoomCenter;
            uniform float uStepX;

            out vec4 fragColor;

            vec2 screenToTexUv(vec2 suv) {
                vec2 zoomed = (suv - uZoomCenter) / uZoomFactor + uZoomCenter;
                vec2 cropped = (zoomed - 0.5) * uCropScale + 0.5;
                vec2 portraitUv;
                if (uRotationMode == 1) {
                    portraitUv = vec2(cropped.y, 1.0 - cropped.x);
                } else if (uRotationMode == 3) {
                    portraitUv = vec2(1.0 - cropped.y, cropped.x);
                } else if (uRotationMode == 2) {
                    portraitUv = vec2(1.0 - cropped.x, 1.0 - cropped.y);
                } else {
                    portraitUv = cropped;
                }
                return (uStMatrix * vec4(portraitUv, 0.0, 1.0)).xy;
            }

            float sampleMask(vec2 suv) {
                return texture(uSubjectMaskTex, vec2(suv.x, 1.0 - suv.y)).r;
            }

            void main() {
                vec3 acc = vec3(0.0);
                float wSum = 0.0;
                for (int k = -5; k <= 5; k++) {
                    float fk = float(k);
                    float gW = exp(-0.5 * (fk * fk) / (2.4 * 2.4));
                    vec2 tapUv = clamp(vScreenUv + vec2(fk * uStepX, 0.0), 0.002, 0.998);
                    float subjW = 1.0 - sampleMask(tapUv) * 0.95;
                    float w = gW * max(subjW, 0.05);
                    vec2 texUv = clamp(screenToTexUv(tapUv), 0.002, 0.998);
                    acc += texture(uCameraTex, texUv).rgb * w;
                    wSum += w;
                }
                fragColor = vec4((wSum > 0.001) ? (acc / wSum) : texture(uCameraTex, clamp(vTexCoord, 0.002, 0.998)).rgb, 1.0);
            }
        """

        private const val LIVE_SEP_V_FRAGMENT_SHADER = """#version 300 es
            precision highp float;

            in vec2 vScreenUv;

            uniform sampler2D uInputTex;
            uniform sampler2D uSubjectMaskTex;
            uniform float uStepY;

            out vec4 fragColor;

            float sampleMask(vec2 suv) {
                return texture(uSubjectMaskTex, vec2(suv.x, 1.0 - suv.y)).r;
            }

            void main() {
                vec3 acc = vec3(0.0);
                float wSum = 0.0;
                for (int k = -5; k <= 5; k++) {
                    float fk = float(k);
                    float gW = exp(-0.5 * (fk * fk) / (2.4 * 2.4));
                    vec2 tapUv = clamp(vScreenUv + vec2(0.0, fk * uStepY), 0.002, 0.998);
                    float subjW = 1.0 - sampleMask(tapUv) * 0.95;
                    float w = gW * max(subjW, 0.05);
                    acc += texture(uInputTex, tapUv).rgb * w;
                    wSum += w;
                }
                fragColor = vec4((wSum > 0.001) ? (acc / wSum) : texture(uInputTex, vScreenUv).rgb, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            precision mediump sampler3D;

            in vec2 vTexCoord;
            in vec2 vScreenUv;

            uniform samplerExternalOES uCameraTex;
            uniform sampler3D uLutTex;
            uniform sampler2D uSubjectMaskTex;
            uniform sampler2D uBlurBgTex;
            uniform float uLutSize;
            uniform float uLutIntensity;
            uniform float uHighlightRolloff;
            uniform float uVignette;
            uniform float uGrain;
            uniform float uTime;
            uniform float uFocusPeaking;
            uniform vec2 uTexelSize;
            uniform float uBokehStrength;
            uniform float uHasSubject;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uColorTone;
            uniform float uHighlightAlert;

            out vec4 fragColor;

            float sampleMask(vec2 suv) {
                return texture(uSubjectMaskTex, vec2(suv.x, 1.0 - suv.y)).r;
            }

            float luminance(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            float rand(vec2 co) {
                return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);
            }

            vec3 applyHighlightRolloff(vec3 col, float strength) {
                float lum = luminance(col);
                float shoulder = smoothstep(0.86, 1.0, lum);
                vec3 compressed = col / (1.0 + col * (0.15 * strength));
                return mix(col, compressed, shoulder * strength);
            }

            vec3 sampleLut(vec3 rgb) {
                float scale = (uLutSize - 1.0) / uLutSize;
                float offset = 0.5 / uLutSize;
                vec3 coord = clamp(rgb, 0.0, 1.0) * scale + offset;
                return texture(uLutTex, coord).rgb;
            }

            void main() {
                vec3 centerRaw = texture(uCameraTex, clamp(vTexCoord, 0.002, 0.998)).rgb;
                vec3 raw;
                if (uHasSubject > 0.5 && uBokehStrength > 0.01) {
                    float subj = sampleMask(vScreenUv);
                    float blurMask = smoothstep(0.14, 0.82, 1.0 - subj);
                    vec3 blurBg = texture(uBlurBgTex, vScreenUv).rgb;
                    raw = mix(centerRaw, blurBg, blurMask);
                } else {
                    raw = centerRaw;
                }

                vec3 rolled = applyHighlightRolloff(raw, uHighlightRolloff);
                vec3 graded = mix(rolled, sampleLut(rolled), uLutIntensity);

                if (abs(uContrast) > 0.05) {
                    float cFactor = 1.0 + uContrast * 0.08;
                    graded = clamp((graded - 0.5) * cFactor + 0.5, 0.0, 1.0);
                }
                if (abs(uSaturation) > 0.05) {
                    float l = luminance(graded);
                    float sFactor = max(0.0, 1.0 + uSaturation * 0.14);
                    graded = clamp(mix(vec3(l), graded, sFactor), 0.0, 1.0);
                }
                if (abs(uColorTone) > 0.05) {
                    graded.r = clamp(graded.r + uColorTone * 0.014, 0.0, 1.0);
                    graded.b = clamp(graded.b - uColorTone * 0.010, 0.0, 1.0);
                }

                vec2 centered = vScreenUv - 0.5;
                float dist = dot(centered, centered) * 2.2;
                graded *= clamp(1.0 - dist * uVignette, 0.2, 1.0);

                if (uGrain > 0.001) {
                    float noise = (rand(vScreenUv * 800.0 + fract(uTime)) - 0.5) * uGrain;
                    graded = clamp(graded + noise, 0.0, 1.0);
                }

                if (uHighlightAlert > 0.5 && luminance(centerRaw) > 0.96) {
                    float stripe = step(0.5, fract((vScreenUv.x + vScreenUv.y) * 42.0 + uTime * 2.5));
                    if (stripe > 0.5) {
                        graded = vec3(0.08, 0.08, 0.10);
                    }
                }

                if (uFocusPeaking > 0.5) {
                    float stepPx = min(uTexelSize.x, uTexelSize.y) * 1.4;
                    float lL = luminance(texture(uCameraTex, vTexCoord - vec2(stepPx, 0.0)).rgb);
                    float lR = luminance(texture(uCameraTex, vTexCoord + vec2(stepPx, 0.0)).rgb);
                    float lU = luminance(texture(uCameraTex, vTexCoord - vec2(0.0, stepPx)).rgb);
                    float lD = luminance(texture(uCameraTex, vTexCoord + vec2(0.0, stepPx)).rgb);
                    float edge = abs(lR - lL) + abs(lD - lU);
                    if (edge > 0.20) {
                        graded = mix(graded, vec3(1.0, 0.18, 0.10), 0.85);
                    }
                }

                fragColor = vec4(graded, 1.0);
            }
        """
    }
}
