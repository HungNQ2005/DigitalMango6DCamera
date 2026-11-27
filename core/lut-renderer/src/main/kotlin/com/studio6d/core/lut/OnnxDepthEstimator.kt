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
import kotlin.math.roundToInt

/**
 * Offline Monocular Depth Estimator backed by Depth Anything V2 Small (Quantized ONNX).
 * Memory-maps the ONNX weights from app cache to keep Dalvik heap low and runs purely on-device.
 */
class OnnxDepthEstimator private constructor(private val appContext: Context) {

    private val inferLock = Any()
    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OnnxDepthWarmup").apply { isDaemon = true }
    }

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var inputName: String = "pixel_values"

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
                val firstInput = ortSession.inputNames.firstOrNull() ?: "pixel_values"
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
                    "Loaded Depth Anything V2 ONNX (${inputW}x${inputH}) in ${System.currentTimeMillis() - t0}ms"
                )
                ortSession
            } catch (t: Throwable) {
                loadFailed = true
                Log.e(TAG, "Failed to initialize Depth Anything V2 ONNX session: ${t.message}", t)
                null
            }
        }
    }

    private fun ensureModelFileOnDisk(): File {
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
     * Runs Depth Anything V2 on [uprightBitmap] and returns normalized inverse depth [0..1]
     * (1.0 = closest foreground, 0.0 = farthest background) with its (width, height).
     */
    fun estimateNormalizedDisparity(uprightBitmap: Bitmap): Pair<FloatArray, Pair<Int, Int>>? {
        val ortSession = ensureSession() ?: return null
        val ortEnv = env ?: return null

        synchronized(inferLock) {
            val t0 = System.currentTimeMillis()
            val w = inputW
            val h = inputH
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
                val invStdR = 1f / 0.229f
                val invStdG = 1f / 0.224f
                val invStdB = 1f / 0.225f
                // NCHW layout: Channel 0 (R), Channel 1 (G), Channel 2 (B)
                for (i in 0 until planeSize) {
                    val px = cachedPixels[i]
                    val r = ((px ushr 16) and 0xFF) * inv255
                    buf.put(i, (r - 0.485f) * invStdR)
                    val g = ((px ushr 8) and 0xFF) * inv255
                    buf.put(planeSize + i, (g - 0.456f) * invStdG)
                    val b = (px and 0xFF) * inv255
                    buf.put(planeSize * 2 + i, (b - 0.406f) * invStdB)
                }
                buf.position(0)
                buf.limit(neededFloats)

                val shape = longArrayOf(1L, 3L, h.toLong(), w.toLong())
                val rawDepth: FloatArray
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
                        rawDepth = FloatArray(totalOut)
                        outFb.get(rawDepth)
                    }
                }

                // Robust 2%..98% percentile normalization to [0..1] disparity
                val sorted = rawDepth.copyOf()
                sorted.sort()
                val loIdx = ((sorted.size - 1) * 0.02f).roundToInt().coerceIn(0, sorted.lastIndex)
                val hiIdx = ((sorted.size - 1) * 0.98f).roundToInt().coerceIn(0, sorted.lastIndex)
                val lo = sorted[loIdx]
                val hi = sorted[hiIdx]
                val span = hi - lo
                if (span < 1e-4f) return null

                val invSpan = 1f / span
                for (i in rawDepth.indices) {
                    rawDepth[i] = ((rawDepth[i] - lo) * invSpan).coerceIn(0f, 1f)
                }

                val dt = System.currentTimeMillis() - t0
                Log.i(TAG, "Depth Anything V2 ${w}x${h} -> ${outW}x${outH} in ${dt}ms")
                return rawDepth to (outW to outH)
            } catch (t: Throwable) {
                Log.e(TAG, "Depth inference failed: ${t.message}", t)
                return null
            } finally {
                if (scaled !== uprightBitmap) scaled.recycle()
            }
        }
    }

    companion object {
        private const val TAG = "OnnxDepthEstimator"
        private const val ASSET_MODEL_PATH = "models/depth_anything_v2_small_q8.onnx"
        private const val CACHE_FILE_NAME = "depth_anything_v2_small_q8.onnx"
        // Multiples of 14 (ViT patch size: 24x16 patches) matching 3:2 DSLR aspect ratio
        private const val DEFAULT_INPUT_W = 336
        private const val DEFAULT_INPUT_H = 224

        @Volatile
        private var instance: OnnxDepthEstimator? = null

        fun getInstance(context: Context): OnnxDepthEstimator {
            return instance ?: synchronized(this) {
                instance ?: OnnxDepthEstimator(context.applicationContext).also { instance = it }
            }
        }
    }
}
