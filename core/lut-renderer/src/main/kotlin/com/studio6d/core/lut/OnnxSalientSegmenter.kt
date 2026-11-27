package com.studio6d.core.lut

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.exp

/**
 * High-Precision Offline Salient Object Silhouette Segmenter backed by BRIA RMBG-1.4 (Quantized ONNX).
 * Produces crisp sub-pixel alpha mattes for fine details (ears, hair strands, acrylic rims)
 * on arbitrary objects and figures without relying on cloud or GMS dependencies.
 */
class OnnxSalientSegmenter private constructor(private val appContext: Context) {

    private val inferLock = Any()
    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OnnxSalientWarmup").apply { isDaemon = true }
    }

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var inputName: String = "input"

    @Volatile
    private var inputW: Int = DEFAULT_INPUT_W

    @Volatile
    private var inputH: Int = DEFAULT_INPUT_H

    @Volatile
    private var loadFailed: Boolean = false

    private var cachedPixels = IntArray(0)
    private var cachedInputBuffer = ByteBuffer.allocateDirect(3 * DEFAULT_INPUT_W * DEFAULT_INPUT_H * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun warmUpAsync() {
        if (session != null || loadFailed) return
        warmupExecutor.execute {
            runCatching { ensureSession() }
        }
    }

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (loadFailed) return null
        synchronized(inferLock) {
            session?.let { return it }
            if (loadFailed) return null
            return try {
                val t0 = System.currentTimeMillis()
                val modelFile = ensureModelFileOnDisk()
                val ortEnv = OrtEnvironment.getEnvironment()
                val ortSession = OrtSession.SessionOptions().use { opts ->
                    opts.setIntraOpNumThreads(4)
                    opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    opts.setMemoryPatternOptimization(false)
                    opts.setCPUArenaAllocator(false)
                    ortEnv.createSession(modelFile.absolutePath, opts)
                }
                val firstInput = ortSession.inputNames.firstOrNull() ?: "input"
                val tensorInfo = ortSession.inputInfo[firstInput]?.info as? TensorInfo
                val shape = tensorInfo?.shape
                if (shape != null && shape.size == 4 && shape[2] > 0L && shape[3] > 0L) {
                    inputH = shape[2].toInt()
                    inputW = shape[3].toInt()
                } else {
                    inputW = DEFAULT_INPUT_W
                    inputH = DEFAULT_INPUT_H
                }
                inputName = firstInput
                env = ortEnv
                session = ortSession
                Log.i(
                    TAG,
                    "Loaded RMBG-1.4 ONNX (${inputW}x${inputH}) in ${System.currentTimeMillis() - t0}ms"
                )
                ortSession
            } catch (t: Throwable) {
                loadFailed = true
                Log.e(TAG, "Failed to initialize RMBG-1.4 ONNX session: ${t.message}", t)
                null
            }
        }
    }

    private fun ensureModelFileOnDisk(): File {
        val legacyFile = File(appContext.cacheDir, "rmbg_1_4_q8.onnx")
        if (legacyFile.exists()) {
            runCatching { legacyFile.delete() }
        }
        val outFile = File(appContext.cacheDir, CACHE_FILE_NAME)
        val assetLen = runCatching {
            appContext.assets.openFd(ASSET_MODEL_PATH).use { it.length }
        }.getOrDefault(-1L)

        if (outFile.exists() && (assetLen <= 0L || outFile.length() == assetLen) && outFile.length() > 1_000_000L) {
            return outFile
        }
        val tmp = File(appContext.cacheDir, "$CACHE_FILE_NAME.tmp")
        appContext.assets.open(ASSET_MODEL_PATH).use { input ->
            tmp.outputStream().use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
        if (outFile.exists()) outFile.delete()
        if (!tmp.renameTo(outFile)) {
            tmp.copyTo(outFile, overwrite = true)
            tmp.delete()
        }
        return outFile
    }

    /**
     * Runs RMBG-1.4 on [uprightBitmap] and returns normalized foreground alpha [0..1]
     * with its (width, height). Returns null if inference fails or produces a flat mask.
     */
    fun segmentForegroundMatte(uprightBitmap: Bitmap): Pair<FloatArray, Pair<Int, Int>>? {
        val ortSession = ensureSession() ?: return null
        val ortEnv = env ?: return null

        synchronized(inferLock) {
            return runCatching {
                runMatteInference(ortSession, ortEnv, uprightBitmap, inputW, inputH)
            }.getOrElse { err ->
                if (inputW != 1024 || inputH != 1024) {
                    Log.w(TAG, "RMBG-1.4 inference failed at ${inputW}x${inputH} (${err.message}); retrying at 1024x1024")
                    inputW = 1024
                    inputH = 1024
                    runCatching {
                        runMatteInference(ortSession, ortEnv, uprightBitmap, 1024, 1024)
                    }.getOrElse { fallbackErr ->
                        Log.e(TAG, "Salient matte fallback inference failed: ${fallbackErr.message}", fallbackErr)
                        null
                    }
                } else {
                    Log.e(TAG, "Salient matte inference failed: ${err.message}", err)
                    null
                }
            }
        }
    }

    private fun runMatteInference(
        ortSession: OrtSession,
        ortEnv: OrtEnvironment,
        uprightBitmap: Bitmap,
        w: Int,
        h: Int
    ): Pair<FloatArray, Pair<Int, Int>>? {
        val t0 = System.currentTimeMillis()
        val planeSize = w * h
        val neededFloats = 3 * planeSize

        val scaled = if (uprightBitmap.width == w && uprightBitmap.height == h) {
            uprightBitmap
        } else {
            Bitmap.createScaledBitmap(uprightBitmap, w, h, true)
        }

        try {
            if (cachedPixels.size != planeSize) {
                cachedPixels = IntArray(planeSize)
            }
            scaled.getPixels(cachedPixels, 0, w, 0, 0, w, h)

            if (cachedInputBuffer.capacity() < neededFloats) {
                cachedInputBuffer = ByteBuffer.allocateDirect(neededFloats * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
            }
            val buf = cachedInputBuffer
            buf.clear()
            val inv255 = 1f / 255f
            // RMBG-1.4 preprocess: mean=[0.5, 0.5, 0.5], std=[1.0, 1.0, 1.0] in NCHW layout
            for (i in 0 until planeSize) {
                val px = cachedPixels[i]
                val r = ((px ushr 16) and 0xFF) * inv255
                val g = ((px ushr 8) and 0xFF) * inv255
                val b = (px and 0xFF) * inv255
                buf.put(i, r - 0.5f)
                buf.put(planeSize + i, g - 0.5f)
                buf.put(planeSize * 2 + i, b - 0.5f)
            }
            buf.position(0)
            buf.limit(neededFloats)

            val shape = longArrayOf(1L, 3L, h.toLong(), w.toLong())
            val rawMask: FloatArray
            val outW: Int
            val outH: Int

            OnnxTensor.createTensor(ortEnv, buf, shape).use { inputTensor ->
                ortSession.run(mapOf(inputName to inputTensor)).use { result ->
                    val outTensor = result[0] as? OnnxTensor ?: return null
                    val outShape = outTensor.info.shape
                    if (outShape.size < 2) return null
                    outH = outShape[outShape.size - 2].toInt().coerceAtLeast(1)
                    outW = outShape[outShape.size - 1].toInt().coerceAtLeast(1)
                    val outFb = outTensor.floatBuffer
                    val totalOut = outW * outH
                    if (outFb.remaining() < totalOut) return null
                    rawMask = FloatArray(totalOut)
                    outFb.get(rawMask)
                }
            }

            var minV = Float.POSITIVE_INFINITY
            var maxV = Float.NEGATIVE_INFINITY
            for (v in rawMask) {
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }

            if (minV < -0.05f || maxV > 1.05f) {
                minV = Float.POSITIVE_INFINITY
                maxV = Float.NEGATIVE_INFINITY
                for (i in rawMask.indices) {
                    val s = 1f / (1f + exp(-rawMask[i]))
                    rawMask[i] = s
                    if (s < minV) minV = s
                    if (s > maxV) maxV = s
                }
            }

            val span = maxV - minV
            if (span < 0.12f) return null

            val invSpan = 1f / span
            for (i in rawMask.indices) {
                rawMask[i] = ((rawMask[i] - minV) * invSpan).coerceIn(0f, 1f)
            }

            val dt = System.currentTimeMillis() - t0
            Log.i(TAG, "RMBG-1.4 ${w}x${h} -> ${outW}x${outH} in ${dt}ms")
            return rawMask to (outW to outH)
        } finally {
            if (scaled !== uprightBitmap) scaled.recycle()
        }
    }

    companion object {
        private const val TAG = "OnnxSalientSegmenter"
        private const val ASSET_MODEL_PATH = "models/rmbg_1_4_q8.onnx"
        private const val CACHE_FILE_NAME = "rmbg_1_4_q8_dyn.onnx"
        // Multiples of 64 matching 3:2 DSLR aspect ratio
        private const val DEFAULT_INPUT_W = 576
        private const val DEFAULT_INPUT_H = 384

        @Volatile
        private var instance: OnnxSalientSegmenter? = null

        fun getInstance(context: Context): OnnxSalientSegmenter {
            return instance ?: synchronized(this) {
                instance ?: OnnxSalientSegmenter(context.applicationContext).also { instance = it }
            }
        }
    }
}
