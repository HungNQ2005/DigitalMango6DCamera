package com.studio6d.core.lut

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.studio6d.core.model.PictureStyle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Persistent single-thread OpenGL ES 3.0 offscreen 4-pass still-image processor:
 * - Pass 1A (`sepProgramId`, horizontal 17-tap): foreground-excluded Gaussian horizontal blur at 1/2 res
 * - Pass 1B (`sepProgramId`, vertical 17-tap): foreground-excluded Gaussian vertical blur at 1/2 res
 * - Pass 1C (`discProgramId`, 25-tap Golden-Angle disc): creamy optical bokeh + specular highlight bloom at 1/3 res
 * - Pass 2 (`programId`, full resolution): prime-lens micro-clarity on subject + RGB-guided bilateral edge-lock +
 *   2-stage optical DoF blend + gentle highlight roll-off + 3D .cube LUT + Picture Style tuning + Vignette + Monochrome grain.
 */
object GpuStillLutProcessor {

    private const val TAG = "GpuStillLutProcessor"

    private val glExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GpuStillLutThread").apply { isDaemon = true }
    }

    private var initialized = false
    private var maxTexSize = 4096
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var sepProgramId = 0
    private var discProgramId = 0
    private var programId = 0

    private var posVboId = 0
    private var uvVboId = 0
    private var srcTexId = 0
    private var maskTexId = 0
    private var depthTexId = 0

    private var bgFboA = 0
    private var bgTexA = 0
    private var bgFboB = 0
    private var bgTexB = 0
    private var halfW = 0
    private var halfH = 0

    private var bgFboDeep = 0
    private var bgTexDeep = 0
    private var deepW = 0
    private var deepH = 0

    private var fboId = 0
    private var fboTexId = 0
    private var fboW = 0
    private var fboH = 0
    private var cachedReadbackBuffer: ByteBuffer? = null

    private val lutCache = HashMap<PictureStyle, Pair<Int, Float>>()

    fun process(
        context: Context,
        source: Bitmap,
        exifDegrees: Int,
        style: PictureStyle,
        lutIntensity: Float,
        highlightRolloff: Float,
        vignetteStrength: Float,
        grainStrength: Float,
        bokehStrength: Float = 0f,
        subjectMask: SubjectMaskData = SubjectMaskData.NONE,
        lensDiopters: Float = 1.0f,
        contrastOffset: Int = 0,
        saturationOffset: Int = 0,
        colorToneOffset: Int = 0
    ): Bitmap {
        val rotNorm = ((exifDegrees % 360) + 360) % 360
        val swapDims = rotNorm == 90 || rotNorm == 270
        val upW = if (swapDims) source.height else source.width
        val upH = if (swapDims) source.width else source.height
        val upAspect = upW.toFloat() / upH.toFloat().coerceAtLeast(1f)
        val targetAspect = 1.5f

        val rawOutW: Int
        val rawOutH: Int
        val cropScaleX: Float
        val cropScaleY: Float
        if (abs(upAspect - targetAspect) < 0.015f) {
            rawOutW = upW
            rawOutH = upH
            cropScaleX = 1f
            cropScaleY = 1f
        } else if (upAspect > targetAspect) {
            rawOutH = upH
            rawOutW = (upH * targetAspect).roundToInt().coerceIn(2, upW)
            cropScaleX = targetAspect / upAspect
            cropScaleY = 1f
        } else {
            rawOutW = upW
            rawOutH = (upW / targetAspect).roundToInt().coerceIn(2, upH)
            cropScaleX = 1f
            cropScaleY = upAspect / targetAspect
        }

        val rotMode = when (rotNorm) {
            90 -> 1
            180 -> 2
            270 -> 3
            else -> 0
        }

        return runCatching {
            glExecutor.submit(Callable {
                renderOnPersistentGl(
                    context = context.applicationContext,
                    source = source,
                    requestedOutW = rawOutW,
                    requestedOutH = rawOutH,
                    rotMode = rotMode,
                    cropScaleX = cropScaleX,
                    cropScaleY = cropScaleY,
                    style = style,
                    lutIntensity = lutIntensity,
                    highlightRolloff = highlightRolloff,
                    vignetteStrength = vignetteStrength,
                    grainStrength = grainStrength,
                    bokehStrength = bokehStrength,
                    subjectMask = subjectMask,
                    lensDiopters = lensDiopters,
                    contrastOffset = contrastOffset,
                    saturationOffset = saturationOffset,
                    colorToneOffset = colorToneOffset
                )
            }).get()
        }.getOrElse { err ->
            Log.e(TAG, "GPU still render failed, using CPU fallback: ${err.message}", err)
            val cpuInput = if (source.width.toLong() * source.height > 4_500_000L) {
                val scale = kotlin.math.sqrt(4_500_000.0 / (source.width.toDouble() * source.height))
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).roundToInt().coerceAtLeast(2),
                    (source.height * scale).roundToInt().coerceAtLeast(2),
                    true
                )
            } else {
                source
            }
            try {
                StillLutProcessor.applyStyle(
                    context = context,
                    source = cpuInput,
                    exifDegrees = exifDegrees,
                    style = style,
                    lutIntensity = lutIntensity,
                    highlightRolloff = highlightRolloff,
                    vignetteStrength = vignetteStrength,
                    grainStrength = grainStrength,
                    bokehStrength = bokehStrength,
                    subjectMask = subjectMask,
                    contrastOffset = contrastOffset,
                    saturationOffset = saturationOffset,
                    colorToneOffset = colorToneOffset
                )
            } finally {
                if (cpuInput !== source) cpuInput.recycle()
            }
        }
    }

    private fun ensureGlInitialized() {
        if (initialized) {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw IllegalStateException("eglMakeCurrent failed")
            }
            return
        }

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x0040,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        require(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            "eglChooseConfig failed"
        }
        val config = configs[0]!!

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        require(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfAttribs, 0)
        require(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        require(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }

        val maxTexArr = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexArr, 0)
        maxTexSize = maxTexArr[0].coerceAtLeast(2048)

        sepProgramId = createProgram(VERTEX_SHADER, SEP_BLUR_FRAGMENT_SHADER, "StillSepBlurProgram")
        discProgramId = createProgram(VERTEX_SHADER, DISC_BLOOM_FRAGMENT_SHADER, "StillDiscProgram")
        programId = createProgram(VERTEX_SHADER, FINAL_FRAGMENT_SHADER, "StillFinalProgram")

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

        val texs = IntArray(7)
        GLES30.glGenTextures(7, texs, 0)
        srcTexId = texs[0]
        bgTexA = texs[1]
        bgTexB = texs[2]
        bgTexDeep = texs[3]
        fboTexId = texs[4]
        maskTexId = texs[5]
        depthTexId = texs[6]

        for (id in listOf(srcTexId, bgTexA, bgTexB, bgTexDeep, fboTexId)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        val initMask = ByteBuffer.allocateDirect(4).apply {
            repeat(4) { put(0xFF.toByte()) }
            position(0)
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        for (id in listOf(maskTexId, depthTexId)) {
            initMask.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 2, 2, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, initMask)
        }

        val fbos = IntArray(4)
        GLES30.glGenFramebuffers(4, fbos, 0)
        bgFboA = fbos[0]
        bgFboB = fbos[1]
        bgFboDeep = fbos[2]
        fboId = fbos[3]

        initialized = true
    }

    private fun ensureFboSize(fbo: Int, tex: Int, curW: Int, curH: Int, reqW: Int, reqH: Int, name: String): Pair<Int, Int> {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        if (curW != reqW || curH != reqH) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                reqW, reqH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex, 0
            )
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            require(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "$name FBO incomplete: $status" }
        }
        return reqW to reqH
    }

    private fun getOrUploadLutTexture(context: Context, style: PictureStyle): Pair<Int, Float> {
        lutCache[style]?.let { return it }
        val lut = CubeLutParser.loadFromAssets(context, style.cubeAssetPath)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val texId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, texId)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8,
            lut.size, lut.size, lut.size, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, lut.toRgbaByteBuffer()
        )
        val entry = texId to lut.size.toFloat()
        lutCache[style] = entry
        return entry
    }

    private fun renderOnPersistentGl(
        context: Context,
        source: Bitmap,
        requestedOutW: Int,
        requestedOutH: Int,
        rotMode: Int,
        cropScaleX: Float,
        cropScaleY: Float,
        style: PictureStyle,
        lutIntensity: Float,
        highlightRolloff: Float,
        vignetteStrength: Float,
        grainStrength: Float,
        bokehStrength: Float,
        subjectMask: SubjectMaskData,
        lensDiopters: Float,
        contrastOffset: Int,
        saturationOffset: Int,
        colorToneOffset: Int
    ): Bitmap {
        ensureGlInitialized()
        val tRenderStart = System.currentTimeMillis()
        val maxRenderDim = min(maxTexSize, 3600)

        var outW = requestedOutW
        var outH = requestedOutH
        if (outW > maxRenderDim || outH > maxRenderDim) {
            val scale = min(maxRenderDim.toFloat() / outW, maxRenderDim.toFloat() / outH)
            outW = (outW * scale).roundToInt().coerceAtLeast(2)
            outH = (outH * scale).roundToInt().coerceAtLeast(2)
        }

        val uploadBitmap = if (source.width > maxRenderDim || source.height > maxRenderDim) {
            val scale = min(maxRenderDim.toFloat() / source.width, maxRenderDim.toFloat() / source.height)
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(2),
                (source.height * scale).roundToInt().coerceAtLeast(2),
                true
            )
        } else {
            source
        }

        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexId)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, uploadBitmap, 0)
        } finally {
            if (uploadBitmap !== source) uploadBitmap.recycle()
        }

        val hasValidMask = bokehStrength > 0.01f &&
            subjectMask.hasSubject &&
            subjectMask.width > 0 &&
            subjectMask.height > 0 &&
            subjectMask.maskBytes.size >= subjectMask.width * subjectMask.height
        val depthBytes = subjectMask.depthBytes
        val hasValidDepth = hasValidMask &&
            depthBytes != null &&
            depthBytes.size >= subjectMask.width * subjectMask.height
        val hasMatte = hasValidMask && subjectMask.hasSilhouetteMatte

        val distanceBoost = (0.80f + 0.45f * (lensDiopters.coerceIn(0.35f, 3.2f) / 2.0f)).coerceIn(0.80f, 1.55f)
        val effectiveBokeh = if (hasValidMask) (bokehStrength * distanceBoost).coerceIn(0f, 1.25f) else 0f

        if (hasValidMask) {
            val maskBuf = ByteBuffer.allocateDirect(subjectMask.maskBytes.size)
                .order(ByteOrder.nativeOrder())
                .put(subjectMask.maskBytes)
                .apply { position(0) }
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexId)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
                subjectMask.width, subjectMask.height, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, maskBuf
            )
            if (hasValidDepth && depthBytes != null) {
                val depthBuf = ByteBuffer.allocateDirect(depthBytes.size)
                    .order(ByteOrder.nativeOrder())
                    .put(depthBytes)
                    .apply { position(0) }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexId)
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
                    subjectMask.width, subjectMask.height, 0,
                    GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, depthBuf
                )
            }

            // PASS 1A & 1B: Subject-Excluded Clean Anti-Aliasing Prefilter at 1/4 Res (bgFboA / bgFboB)
            val reqHalfW = (outW / 4).coerceAtLeast(240)
            val reqHalfH = (outH / 4).coerceAtLeast(160)
            val dimsA = ensureFboSize(bgFboA, bgTexA, halfW, halfH, reqHalfW, reqHalfH, "bgFboA")
            val dimsB = ensureFboSize(bgFboB, bgTexB, halfW, halfH, reqHalfW, reqHalfH, "bgFboB")
            halfW = dimsA.first
            halfH = dimsB.second

            val stepY = 0.0022f * effectiveBokeh
            val stepX = stepY / 1.5f

            // Pass 1A: Horizontal prefilter uSrcTex -> bgFboA
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bgFboA)
            GLES30.glViewport(0, 0, halfW, halfH)
            GLES30.glUseProgram(sepProgramId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(sepProgramId, "uInputTex"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(sepProgramId, "uSubjectMaskTex"), 2)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(sepProgramId, "uDepthTex"), 5)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(sepProgramId, "uRotationMode"), rotMode)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(sepProgramId, "uCropScale"), cropScaleX, cropScaleY)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(sepProgramId, "uBlurStep"), stepX, 0f)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(sepProgramId, "uIsSrcPass"), 1)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(sepProgramId, "uHasSubject"), 1f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(sepProgramId, "uHasDepth"), if (hasValidDepth) 1f else 0f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(sepProgramId, "uHasMatte"), if (hasMatte) 1f else 0f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(sepProgramId, "uFocusDepth"), subjectMask.focusDepth)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(sepProgramId, "uDofHalfWidth"), subjectMask.dofHalfWidth)
            drawBoundQuad()

            // Pass 1B: Vertical prefilter bgTexA -> bgFboB
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bgFboB)
            GLES30.glViewport(0, 0, halfW, halfH)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTexA)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(sepProgramId, "uInputTex"), 0)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(sepProgramId, "uBlurStep"), 0f, stepY)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(sepProgramId, "uIsSrcPass"), 0)
            drawBoundQuad()

            // PASS 1C: Per-Pixel Variable-Radius 48-Tap Linear HDR Optical Aperture Disc at 1/2 Res -> bgFboDeep
            val reqDeepW = (outW / 2).coerceAtLeast(480)
            val reqDeepH = (outH / 2).coerceAtLeast(320)
            val dimsDeep = ensureFboSize(bgFboDeep, bgTexDeep, deepW, deepH, reqDeepW, reqDeepH, "bgFboDeep")
            deepW = dimsDeep.first
            deepH = dimsDeep.second

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bgFboDeep)
            GLES30.glViewport(0, 0, deepW, deepH)
            GLES30.glUseProgram(discProgramId)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(discProgramId, "uSrcTex"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(discProgramId, "uSubjectMaskTex"), 2)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTexB)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(discProgramId, "uBgTexB"), 3)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(discProgramId, "uDepthTex"), 5)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(discProgramId, "uRotationMode"), rotMode)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(discProgramId, "uCropScale"), cropScaleX, cropScaleY)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(discProgramId, "uBokehStrength"), effectiveBokeh)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(discProgramId, "uHasDepth"), if (hasValidDepth) 1f else 0f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(discProgramId, "uHasMatte"), if (hasMatte) 1f else 0f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(discProgramId, "uFocusDepth"), subjectMask.focusDepth)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(discProgramId, "uDofHalfWidth"), subjectMask.dofHalfWidth)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(discProgramId, "uDofFalloff"), subjectMask.dofFalloff)
            drawBoundQuad()
        }

        // PASS 2: Full-resolution sub-pixel alpha matte composite + foreground defocus + 3D LUT
        val (lutTexId, lutSize) = getOrUploadLutTexture(context, style)
        val dimsOut = ensureFboSize(fboId, fboTexId, fboW, fboH, outW, outH, "finalFbo")
        fboW = dimsOut.first
        fboH = dimsOut.second

        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glUseProgram(programId)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSrcTex"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uLutTex"), 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uSubjectMaskTex"), 2)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasValidMask) bgTexB else srcTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBgTexB"), 3)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (hasValidMask) bgTexDeep else srcTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uBgTexDeep"), 4)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uDepthTex"), 5)

        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLutSize"), lutSize)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uLutIntensity"), lutIntensity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uHighlightRolloff"), highlightRolloff)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uVignette"), vignetteStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uGrain"), grainStrength)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSeed"), (System.nanoTime() and 0xFFFFL).toFloat() / 65536f)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uRotationMode"), rotMode)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uCropScale"), cropScaleX, cropScaleY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(programId, "uOutTexelSize"), 1f / outW, 1f / outH)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uBokehStrength"), effectiveBokeh)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uHasSubject"), if (hasValidMask) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uHasDepth"), if (hasValidDepth) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uHasMatte"), if (hasMatte) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uFocusDepth"), subjectMask.focusDepth)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDofHalfWidth"), subjectMask.dofHalfWidth)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uDofFalloff"), subjectMask.dofFalloff)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uContrast"), contrastOffset.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSaturation"), saturationOffset.toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uColorTone"), colorToneOffset.toFloat())

        drawBoundQuad()

        val neededBytes = outW * outH * 4
        var outBuffer = cachedReadbackBuffer
        if (outBuffer == null || outBuffer.capacity() < neededBytes) {
            outBuffer = ByteBuffer.allocateDirect(neededBytes).order(ByteOrder.nativeOrder())
            cachedReadbackBuffer = outBuffer
        }
        outBuffer!!.clear()
        outBuffer.limit(neededBytes)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
        GLES30.glReadPixels(0, 0, outW, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, outBuffer)
        outBuffer.rewind()

        val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        outBitmap.copyPixelsFromBuffer(outBuffer)
        Log.i(TAG, "GPU still render ${outW}x${outH} in ${System.currentTimeMillis() - tRenderStart}ms (bokeh=${effectiveBokeh}, matte=$hasMatte, depth=$hasValidDepth)")
        return outBitmap
    }

    private fun drawBoundQuad() {
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

    private fun createProgram(vsSrc: String, fsSrc: String, debugName: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc, "$debugName-VS")
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc, "$debugName-FS")
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            throw IllegalStateException("$debugName link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String, debugName: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("$debugName compile failed: $log")
        }
        return shader
    }

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
        precision highp float;
        precision highp int;
        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        uniform int uRotationMode;
        uniform vec2 uCropScale;
        out vec2 vTexCoord;
        out vec2 vScreenUv;

        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vScreenUv = aTexCoord;
            vec2 cropped = (aTexCoord - 0.5) * uCropScale + 0.5;
            if (uRotationMode == 1) {
                vTexCoord = vec2(cropped.y, 1.0 - cropped.x);
            } else if (uRotationMode == 2) {
                vTexCoord = vec2(1.0 - cropped.x, 1.0 - cropped.y);
            } else if (uRotationMode == 3) {
                vTexCoord = vec2(1.0 - cropped.y, cropped.x);
            } else {
                vTexCoord = cropped;
            }
        }
    """

    private const val SEP_BLUR_FRAGMENT_SHADER = """#version 300 es
        precision highp float;
        precision highp int;

        in vec2 vTexCoord;
        in vec2 vScreenUv;

        uniform sampler2D uInputTex;
        uniform sampler2D uSubjectMaskTex;
        uniform sampler2D uDepthTex;
        uniform int uRotationMode;
        uniform vec2 uCropScale;
        uniform vec2 uBlurStep;
        uniform int uIsSrcPass;
        uniform float uHasSubject;
        uniform float uHasDepth;
        uniform float uHasMatte;
        uniform float uFocusDepth;
        uniform float uDofHalfWidth;

        out vec4 fragColor;

        vec2 screenToTexUv(vec2 suv) {
            vec2 cropped = (suv - 0.5) * uCropScale + 0.5;
            if (uRotationMode == 1) {
                return vec2(cropped.y, 1.0 - cropped.x);
            } else if (uRotationMode == 2) {
                return vec2(1.0 - cropped.x, 1.0 - cropped.y);
            } else if (uRotationMode == 3) {
                return vec2(1.0 - cropped.y, cropped.x);
            }
            return cropped;
        }

        void main() {
            vec3 acc = vec3(0.0);
            float wSum = 0.0;
            for (int k = -8; k <= 8; k++) {
                float fk = float(k);
                float gWeight = exp(-0.5 * (fk * fk) / (3.2 * 3.2));
                vec2 tapUv = clamp(vScreenUv + uBlurStep * fk, 0.001, 0.999);
                float bgWeight = 1.0;
                if (uHasMatte > 0.5) {
                    bgWeight *= smoothstep(0.65, 0.08, texture(uSubjectMaskTex, tapUv).r);
                }
                if (uHasDepth > 0.5) {
                    float tapD = texture(uDepthTex, tapUv).r;
                    bgWeight *= smoothstep(uFocusDepth - uDofHalfWidth * 0.25, uFocusDepth - uDofHalfWidth - 0.07, tapD);
                }
                if (uHasMatte <= 0.5 && uHasDepth <= 0.5 && uHasSubject > 0.5) {
                    bgWeight = 1.0 - texture(uSubjectMaskTex, tapUv).r * 0.96;
                }
                float w = gWeight * max(bgWeight, 0.002);
                vec2 sampleUv = (uIsSrcPass == 1) ? clamp(screenToTexUv(tapUv), 0.001, 0.999) : tapUv;
                acc += texture(uInputTex, sampleUv).rgb * w;
                wSum += w;
            }
            if (wSum > 0.0001) {
                fragColor = vec4(acc / wSum, 1.0);
            } else {
                vec2 fbUv = (uIsSrcPass == 1) ? clamp(vTexCoord, 0.001, 0.999) : vScreenUv;
                fragColor = vec4(texture(uInputTex, fbUv).rgb, 1.0);
            }
        }
    """

    private const val DISC_BLOOM_FRAGMENT_SHADER = """#version 300 es
        precision highp float;
        precision highp int;

        in vec2 vTexCoord;
        in vec2 vScreenUv;

        uniform sampler2D uSrcTex;
        uniform sampler2D uSubjectMaskTex;
        uniform sampler2D uBgTexB;
        uniform sampler2D uDepthTex;
        uniform int uRotationMode;
        uniform vec2 uCropScale;
        uniform float uBokehStrength;
        uniform float uHasDepth;
        uniform float uHasMatte;
        uniform float uFocusDepth;
        uniform float uDofHalfWidth;
        uniform float uDofFalloff;

        out vec4 fragColor;

        vec2 screenToTexUv(vec2 suv) {
            vec2 cropped = (suv - 0.5) * uCropScale + 0.5;
            if (uRotationMode == 1) {
                return vec2(cropped.y, 1.0 - cropped.x);
            } else if (uRotationMode == 2) {
                return vec2(1.0 - cropped.x, 1.0 - cropped.y);
            } else if (uRotationMode == 3) {
                return vec2(1.0 - cropped.y, cropped.x);
            }
            return cropped;
        }

        float luminance(vec3 c) {
            return dot(c, vec3(0.2126, 0.7152, 0.0722));
        }

        void main() {
            vec3 srcCenter = texture(uSrcTex, clamp(vTexCoord, 0.001, 0.999)).rgb;
            float centerMatte = (uHasMatte > 0.5) ? texture(uSubjectMaskTex, vScreenUv).r : 0.0;
            if (centerMatte > 0.985) {
                fragColor = vec4(srcCenter, 1.0);
                return;
            }

            float bgCoC = 1.0;
            if (uHasDepth > 0.5) {
                float centerD = texture(uDepthTex, vScreenUv).r;
                float bgD = centerD;
                if (centerMatte > 0.03) {
                    float offX = 0.008 / 1.5;
                    float offY = 0.008;
                    bgD = min(bgD, texture(uDepthTex, clamp(vScreenUv + vec2(offX, 0.0), 0.002, 0.998)).r);
                    bgD = min(bgD, texture(uDepthTex, clamp(vScreenUv - vec2(offX, 0.0), 0.002, 0.998)).r);
                    bgD = min(bgD, texture(uDepthTex, clamp(vScreenUv + vec2(0.0, offY), 0.002, 0.998)).r);
                    bgD = min(bgD, texture(uDepthTex, clamp(vScreenUv - vec2(0.0, offY), 0.002, 0.998)).r);
                }
                float behind = max(0.0, uFocusDepth - bgD);
                float depthCoC = smoothstep(uDofHalfWidth * 0.55, uDofHalfWidth * 0.55 + uDofFalloff, behind);
                if (uHasMatte > 0.5) {
                    float isCloserFg = smoothstep(uDofHalfWidth * 0.45, uDofHalfWidth * 0.95, bgD - uFocusDepth);
                    float groundContact = smoothstep(0.68, 0.96, vScreenUv.y) * (1.0 - smoothstep(0.0, uDofHalfWidth * 0.85, abs(bgD - uFocusDepth)));
                    float matteFloor = 0.44 * (1.0 - centerMatte) * (1.0 - isCloserFg) * (1.0 - 0.65 * groundContact);
                    bgCoC = clamp(max(depthCoC, matteFloor), 0.0, 1.0);
                } else {
                    bgCoC = smoothstep(uDofHalfWidth, uDofHalfWidth + uDofFalloff, behind);
                }
            }

            float radius = 0.028 * uBokehStrength * bgCoC;
            if (radius <= 0.00035) {
                fragColor = vec4(srcCenter, 1.0);
                return;
            }

            float usePrefilter = smoothstep(0.004, 0.012, radius);
            vec3 accLin = vec3(0.0);
            float wSum = 0.0;
            const float goldenAngle = 2.39996323;
            const float invAspect = 1.0 / 1.5;

            for (int i = 0; i < 48; i++) {
                float fi = float(i);
                float rNorm = sqrt((fi + 0.5) / 48.0);
                float r = rNorm * radius;
                float a = fi * goldenAngle;
                vec2 tapUv = clamp(vScreenUv + vec2(cos(a) * r * invAspect, sin(a) * r), 0.002, 0.998);

                float occ = 1.0;
                if (uHasMatte > 0.5) {
                    occ *= smoothstep(0.62, 0.06, texture(uSubjectMaskTex, tapUv).r);
                    if (uHasDepth > 0.5) {
                        float tapD = texture(uDepthTex, tapUv).r;
                        occ *= smoothstep(uFocusDepth + uDofHalfWidth * 0.80, uFocusDepth + uDofHalfWidth * 0.20, tapD);
                    }
                } else if (uHasDepth > 0.5) {
                    float tapD = texture(uDepthTex, tapUv).r;
                    occ *= smoothstep(uFocusDepth - uDofHalfWidth * 0.20, uFocusDepth - uDofHalfWidth - 0.07, tapD);
                }

                vec3 srcS = texture(uSrcTex, clamp(screenToTexUv(tapUv), 0.001, 0.999)).rgb;
                vec3 preS = texture(uBgTexB, tapUv).rgb;
                vec3 s = mix(srcS, preS, usePrefilter);

                float rimBoost = 0.88 + 0.22 * smoothstep(0.65, 0.98, rNorm);
                float lum = luminance(s);
                float hdrEnergy = 1.0 + smoothstep(0.48, 0.99, lum) * (4.2 * uBokehStrength) * bgCoC;
                float w = max(occ, 0.004) * rimBoost * hdrEnergy;

                vec3 lin = pow(max(s, vec3(0.0)), vec3(2.2));
                accLin += lin * w;
                wSum += w;
            }

            vec3 avgLin = accLin / max(wSum, 0.0001);
            vec3 toneMapped = avgLin / (1.0 + max(vec3(0.0), avgLin - 0.68) * 0.36);
            fragColor = vec4(pow(clamp(toneMapped, 0.0, 1.0), vec3(1.0 / 2.2)), 1.0);
        }
    """

    private const val FINAL_FRAGMENT_SHADER = """#version 300 es
        precision highp float;
        precision highp int;
        precision mediump sampler3D;

        in vec2 vTexCoord;
        in vec2 vScreenUv;

        uniform sampler2D uSrcTex;
        uniform sampler3D uLutTex;
        uniform sampler2D uSubjectMaskTex;
        uniform sampler2D uBgTexB;
        uniform sampler2D uBgTexDeep;
        uniform sampler2D uDepthTex;
        uniform int uRotationMode;
        uniform vec2 uCropScale;
        uniform vec2 uOutTexelSize;
        uniform float uLutSize;
        uniform float uLutIntensity;
        uniform float uHighlightRolloff;
        uniform float uVignette;
        uniform float uGrain;
        uniform float uSeed;
        uniform float uBokehStrength;
        uniform float uHasSubject;
        uniform float uHasDepth;
        uniform float uHasMatte;
        uniform float uFocusDepth;
        uniform float uDofHalfWidth;
        uniform float uDofFalloff;
        uniform float uContrast;
        uniform float uSaturation;
        uniform float uColorTone;

        out vec4 fragColor;

        vec2 screenToTexUv(vec2 suv) {
            vec2 cropped = (suv - 0.5) * uCropScale + 0.5;
            if (uRotationMode == 1) {
                return vec2(cropped.y, 1.0 - cropped.x);
            } else if (uRotationMode == 2) {
                return vec2(1.0 - cropped.x, 1.0 - cropped.y);
            } else if (uRotationMode == 3) {
                return vec2(1.0 - cropped.y, cropped.x);
            }
            return cropped;
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

        vec3 applyCanonAloClarity(vec3 col) {
            float lum = luminance(col);
            float shadowToe = smoothstep(0.0, 0.032, lum);
            float aloMask = shadowToe * (1.0 - smoothstep(0.50, 0.92, lum));
            vec3 lifted = pow(max(col, vec3(0.0)), vec3(0.84));
            vec3 c = mix(col, lifted, 0.44 * aloMask);
            c = clamp((c - 0.42) * 1.065 + 0.435, 0.0, 1.0);
            c.r = clamp(c.r * 1.014, 0.0, 1.0);
            c.b = clamp(c.b * 1.006, 0.0, 1.0);
            float l2 = luminance(c);
            float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
            float vib = (1.0 - smoothstep(0.32, 0.82, sat)) * 0.16;
            return clamp(mix(vec3(l2), c, 1.0 + vib), 0.0, 1.0);
        }

        float sampleRefinedSubjectMask(vec3 centerCol) {
            float mCenter = texture(uSubjectMaskTex, vScreenUv).r;
            float wSum = 1.0;
            float mSum = mCenter;
            float dx = 0.0018 / 1.5;
            float dy = 0.0018;
            for (int i = 0; i < 4; i++) {
                vec2 off = (i == 0) ? vec2(dx, 0.0) :
                           (i == 1) ? vec2(-dx, 0.0) :
                           (i == 2) ? vec2(0.0, dy) : vec2(0.0, -dy);
                vec2 nUv = clamp(vScreenUv + off, 0.001, 0.999);
                vec3 nCol = texture(uSrcTex, clamp(screenToTexUv(nUv), 0.001, 0.999)).rgb;
                vec3 diff = nCol - centerCol;
                float w = exp(-dot(diff, diff) * 75.0) * 0.75;
                mSum += texture(uSubjectMaskTex, nUv).r * w;
                wSum += w;
            }
            return mSum / wSum;
        }

        float sampleRefinedDepth(vec3 centerCol) {
            float dCenter = texture(uDepthTex, vScreenUv).r;
            float wSum = 1.0;
            float dSum = dCenter;
            float dx = 0.0022 / 1.5;
            float dy = 0.0022;
            for (int i = 0; i < 4; i++) {
                vec2 off = (i == 0) ? vec2(dx, 0.0) :
                           (i == 1) ? vec2(-dx, 0.0) :
                           (i == 2) ? vec2(0.0, dy) : vec2(0.0, -dy);
                vec2 nUv = clamp(vScreenUv + off, 0.001, 0.999);
                vec3 nCol = texture(uSrcTex, clamp(screenToTexUv(nUv), 0.001, 0.999)).rgb;
                vec3 diff = nCol - centerCol;
                float w = exp(-dot(diff, diff) * 70.0) * 0.75;
                dSum += texture(uDepthTex, nUv).r * w;
                wSum += w;
            }
            return dSum / wSum;
        }

        vec3 sampleForegroundDisc(vec2 suv, float fgCoC, float bokehStr) {
            vec3 center = texture(uSrcTex, clamp(screenToTexUv(suv), 0.001, 0.999)).rgb;
            float radius = 0.016 * bokehStr * fgCoC;
            if (radius <= 0.0003) return center;
            vec3 accLin = pow(max(center, vec3(0.0)), vec3(2.2)) * 0.12;
            float wSum = 0.12;
            const float goldenAngle = 2.39996323;
            const float invAspect = 1.0 / 1.5;
            for (int i = 0; i < 16; i++) {
                float fi = float(i);
                float r = sqrt((fi + 0.5) / 16.0) * radius;
                float a = fi * goldenAngle;
                vec2 tapUv = clamp(suv + vec2(cos(a) * r * invAspect, sin(a) * r), 0.001, 0.999);
                float tapD = texture(uDepthTex, tapUv).r;
                float fgOnly = smoothstep(uFocusDepth + uDofHalfWidth * 0.30, uFocusDepth + uDofHalfWidth + 0.06, tapD) * 0.95 + 0.05;
                vec3 s = texture(uSrcTex, clamp(screenToTexUv(tapUv), 0.001, 0.999)).rgb;
                accLin += pow(max(s, vec3(0.0)), vec3(2.2)) * fgOnly;
                wSum += fgOnly;
            }
            return pow(clamp(accLin / max(wSum, 0.001), 0.0, 1.0), vec3(1.0 / 2.2));
        }

        vec3 applyPrimeMicroClarity(vec3 centerCol, float clarityWeight) {
            vec3 nL = texture(uSrcTex, clamp(screenToTexUv(vScreenUv - vec2(uOutTexelSize.x, 0.0)), 0.001, 0.999)).rgb;
            vec3 nR = texture(uSrcTex, clamp(screenToTexUv(vScreenUv + vec2(uOutTexelSize.x, 0.0)), 0.001, 0.999)).rgb;
            vec3 nU = texture(uSrcTex, clamp(screenToTexUv(vScreenUv - vec2(0.0, uOutTexelSize.y)), 0.001, 0.999)).rgb;
            vec3 nD = texture(uSrcTex, clamp(screenToTexUv(vScreenUv + vec2(0.0, uOutTexelSize.y)), 0.001, 0.999)).rgb;
            vec3 avg4 = (nL + nR + nU + nD) * 0.25;
            vec3 detail = centerCol - avg4;
            return clamp(centerCol + detail * (0.22 * clarityWeight), 0.0, 1.0);
        }

        void main() {
            vec3 centerRaw = texture(uSrcTex, clamp(vTexCoord, 0.001, 0.999)).rgb;
            vec3 raw;

            if (uHasSubject > 0.5 && uBokehStrength > 0.01) {
                vec3 crispSubj = applyPrimeMicroClarity(centerRaw, 1.0);
                vec3 bokehBg = texture(uBgTexDeep, vScreenUv).rgb;

                if (uHasMatte > 0.5) {
                    float matte = sampleRefinedSubjectMask(centerRaw);
                    if (uHasDepth > 0.5) {
                        float d = sampleRefinedDepth(centerRaw);
                        float fgCoC = smoothstep(uDofHalfWidth * 0.72, uDofHalfWidth * 0.72 + uDofFalloff, d - uFocusDepth);
                        if (matte < 0.20 && fgCoC > 0.02) {
                            vec3 fgBlur = sampleForegroundDisc(vScreenUv, fgCoC, uBokehStrength);
                            vec3 bgOrFg = mix(bokehBg, fgBlur, smoothstep(0.02, 0.25, fgCoC));
                            raw = mix(bgOrFg, crispSubj, matte);
                        } else if (matte >= 0.99) {
                            raw = crispSubj;
                        } else if (matte > 0.01) {
                            raw = mix(bokehBg, crispSubj, matte);
                        } else {
                            float depthCoC = smoothstep(uDofHalfWidth * 0.55, uDofHalfWidth * 0.55 + uDofFalloff, uFocusDepth - d);
                            float isCloserFg = smoothstep(uDofHalfWidth * 0.45, uDofHalfWidth * 0.95, d - uFocusDepth);
                            float groundContact = smoothstep(0.68, 0.96, vScreenUv.y) * (1.0 - smoothstep(0.0, uDofHalfWidth * 0.85, abs(d - uFocusDepth)));
                            float matteFloor = 0.44 * (1.0 - isCloserFg) * (1.0 - 0.65 * groundContact);
                            float bgCoC = clamp(max(depthCoC, matteFloor), 0.0, 1.0);
                            raw = (bgCoC < 0.002) ? crispSubj : mix(crispSubj, bokehBg, smoothstep(0.0, 0.12, bgCoC));
                        }
                    } else {
                        raw = mix(bokehBg, crispSubj, matte);
                    }
                } else if (uHasDepth > 0.5) {
                    float d = sampleRefinedDepth(centerRaw);
                    float behind = uFocusDepth - d;
                    float inFront = d - uFocusDepth;
                    float bgCoC = smoothstep(uDofHalfWidth, uDofHalfWidth + uDofFalloff, behind);
                    float fgCoC = smoothstep(uDofHalfWidth * 1.10, uDofHalfWidth * 1.10 + uDofFalloff * 1.15, inFront);

                    if (bgCoC < 0.002 && fgCoC < 0.002) {
                        raw = crispSubj;
                    } else if (fgCoC >= bgCoC) {
                        vec3 sharp = applyPrimeMicroClarity(centerRaw, 1.0 - fgCoC);
                        vec3 fgBlur = sampleForegroundDisc(vScreenUv, fgCoC, uBokehStrength);
                        raw = mix(sharp, fgBlur, smoothstep(0.0, 1.0, fgCoC));
                    } else {
                        vec3 sharp = applyPrimeMicroClarity(centerRaw, 1.0 - bgCoC);
                        raw = mix(sharp, bokehBg, smoothstep(0.0, 0.08, bgCoC));
                    }
                } else {
                    float subj = sampleRefinedSubjectMask(centerRaw);
                    raw = mix(bokehBg, applyPrimeMicroClarity(centerRaw, subj), subj);
                }
            } else {
                raw = applyPrimeMicroClarity(centerRaw, 0.70);
            }

            vec3 rolled = applyHighlightRolloff(raw, uHighlightRolloff);
            vec3 graded = applyCanonAloClarity(mix(rolled, sampleLut(rolled), uLutIntensity));

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
                float noise = (rand(vScreenUv * 1200.0 + uSeed) - 0.5) * uGrain;
                graded = clamp(graded + noise, 0.0, 1.0);
            }

            fragColor = vec4(graded, 1.0);
        }
    """
}
