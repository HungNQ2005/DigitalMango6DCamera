package com.studio6d.core.lut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.studio6d.core.model.AfPoints6D
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Unified 2-Tier Object-Silhouette Segmenter & Canon 11-Point Saliency Detector:
 * - Tier 1: Offline bundled ML Kit Selfie Segmentation (STREAM_MODE for Live View, SINGLE_IMAGE_MODE for Stills)
 * - Tier 2: EdgeGuidedFocusSegmenter Dual-Geodesic Barrier + Real Optical Defocus Gate (never flat screens)
 * Followed by Joint Bilateral RGB edge-snapping so the mask strictly follows real subject contours.
 */
class OpticalSubjectSegmenter private constructor(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val depthEstimator = OnnxDepthEstimator.getInstance(context.applicationContext).also {
        it.warmUpAsync()
    }
    private val liveSelfieInFlight = AtomicBoolean(false)
    private val stillSelfieInFlight = AtomicBoolean(false)
    private var livePixels = IntArray(0)
    private var liveBitmap: Bitmap? = null

    private val liveSelfieSegmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build()
    )

    private val stillSelfieSegmenter = Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
    )

    suspend fun segmentLiveFrame(
        uprightRgba: ByteArray,
        width: Int,
        height: Int,
        focusNormX: Float,
        focusNormY: Float,
        lensDiopters: Float,
        isAutoMultiPointAf: Boolean = true
    ): SubjectMaskData {
        val total = width * height
        if (width < 16 || height < 16 || uprightRgba.size < total * 4) return SubjectMaskData.NONE

        // Tier 1: Fast Bundled STREAM_MODE Selfie Segmenter (~15ms for Live View) with reusable Bitmap/IntArray
        val selfieAlphas = if (!liveSelfieInFlight.get()) {
            val bmp = obtainLiveBitmap(uprightRgba, width, height)
            withTimeoutOrNull(85L) {
                runSelfieSegmentation(bmp, focusNormX, focusNormY, isAutoMultiPointAf, isLiveStream = true)
            }
        } else {
            null
        }
        if (selfieAlphas != null) {
            val polished = EdgeGuidedFocusSegmenter.refineMaskJointBilateral(
                alphaIn = selfieAlphas,
                rgbaBytes = uprightRgba,
                width = width,
                height = height,
                radius = 2,
                passes = 1
            )
            return SubjectMaskData(polished, width, height, hasSubject = true)
        }

        // Tier 2: Real-time Edge-Guided + Optical Defocus Gate fallback for 3D objects
        return EdgeGuidedFocusSegmenter.computeSubjectMask(
            rgbaBytes = uprightRgba,
            width = width,
            height = height,
            focusNormX = focusNormX,
            focusNormY = focusNormY,
            lensDiopters = lensDiopters
        )
    }

    private fun obtainLiveBitmap(rgba: ByteArray, w: Int, h: Int): Bitmap {
        val total = w * h
        if (livePixels.size != total) {
            livePixels = IntArray(total)
        }
        var p = 0
        for (i in 0 until total) {
            val r = rgba[p].toInt() and 0xFF
            val g = rgba[p + 1].toInt() and 0xFF
            val b = rgba[p + 2].toInt() and 0xFF
            val a = rgba[p + 3].toInt() and 0xFF
            p += 4
            livePixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val existing = liveBitmap
        val bmp = if (existing != null && !existing.isRecycled && existing.width == w && existing.height == h) {
            existing
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { liveBitmap = it }
        }
        bmp.setPixels(livePixels, 0, w, 0, 0, w, h)
        return bmp
    }

    suspend fun segmentStillCapture(
        sourceBitmap: Bitmap,
        exifDegrees: Int,
        focusNormX: Float,
        focusNormY: Float,
        lensDiopters: Float,
        isAutoMultiPointAf: Boolean = true,
        apertureF: Float = 2.0f,
        dofWidthScale: Float = 1.0f
    ): SubjectMaskData {
        // NOTE: Never call thumb.recycle() manually; ML Kit's async native workers may outlive coroutine timeouts.
        val guideW = 600
        val guideH = 400
        val thumb = createUprightThreeTwoThumb(sourceBitmap, exifDegrees, guideW, guideH)
        val w = thumb.width
        val h = thumb.height
        val total = w * h
        val rgbaBuf = ByteBuffer.allocate(total * 4)
        thumb.copyPixelsToBuffer(rgbaBuf)
        val uprightRgba = rgbaBuf.array()

        // Tier 1 (Primary): Offline Depth Anything V2 Small + 3-Channel Fast Guided Filter
        val depthResult = depthEstimator.estimateNormalizedDisparity(thumb)
        if (depthResult != null) {
            val (lowDisp, dims) = depthResult
            val upsampled = resampleBilinear(lowDisp, dims.first, dims.second, w, h)
            val guidedDepth = FastGuidedFilter.filter(
                guideRgba = uprightRgba,
                src = upsampled,
                width = w,
                height = h,
                radius = 8,
                eps = 0.0045f
            )

            val fx = (focusNormX.coerceIn(0.02f, 0.98f) * w).roundToInt().coerceIn(0, w - 1)
            val fy = (focusNormY.coerceIn(0.02f, 0.98f) * h).roundToInt().coerceIn(0, h - 1)
            val radX = (w * 0.040f).roundToInt().coerceAtLeast(4)
            val radY = (h * 0.040f).roundToInt().coerceAtLeast(4)
            val y0 = (fy - radY).coerceAtLeast(0)
            val y1 = (fy + radY).coerceAtMost(h - 1)
            val x0 = (fx - radX).coerceAtLeast(0)
            val x1 = (fx + radX).coerceAtMost(w - 1)
            val windowSamples = FloatArray((y1 - y0 + 1) * (x1 - x0 + 1))
            var sCount = 0
            for (yy in y0..y1) {
                val row = yy * w
                for (xx in x0..x1) {
                    windowSamples[sCount++] = guidedDepth[row + xx]
                }
            }
            windowSamples.sort(0, sCount)
            val focusDepth = if (sCount > 0) {
                windowSamples[(sCount * 0.56f).roundToInt().coerceIn(0, sCount - 1)]
            } else {
                guidedDepth[fy * w + fx]
            }

            // Optional human-portrait enhancement: stabilize portrait foreground plane while preserving 3D DoF
            val selfieAlphas = withTimeoutOrNull(250L) {
                runSelfieSegmentation(thumb, focusNormX, focusNormY, isAutoMultiPointAf, isLiveStream = false)
            }
            if (selfieAlphas != null && selfieAlphas.size >= total) {
                for (i in 0 until total) {
                    val sa = selfieAlphas[i]
                    if (sa > 0.72f) {
                        guidedDepth[i] = (guidedDepth[i] * 0.60f + focusDepth * 0.40f).coerceIn(0f, 1f)
                    }
                }
            }

            val baseHalfWidth = when {
                apertureF <= 1.25f -> 0.095f
                apertureF <= 1.50f -> 0.105f
                apertureF <= 2.10f -> 0.128f
                apertureF <= 2.90f -> 0.158f
                apertureF <= 4.10f -> 0.205f
                apertureF <= 5.70f -> 0.260f
                else -> 0.320f
            }
            val dofHalfWidth = (baseHalfWidth * dofWidthScale).coerceIn(0.07f, 0.38f)
            val dofFalloff = 0.29f

            var minD = 1f
            var maxD = 0f
            var inFocusCount = 0
            var separatedCount = 0
            for (i in 0 until total) {
                val d = guidedDepth[i]
                if (d < minD) minD = d
                if (d > maxD) maxD = d
                val diff = abs(d - focusDepth)
                if (diff <= dofHalfWidth * 1.05f) inFocusCount++
                if (diff > dofHalfWidth * 1.15f) separatedCount++
            }

            if (inFocusCount < (total * 0.015f).toInt() ||
                separatedCount < (total * 0.04f).toInt() ||
                (maxD - minD) <= dofHalfWidth * 1.30f
            ) {
                return SubjectMaskData.NONE
            }

            val depthBytes = ByteArray(total)
            val maskBytes = ByteArray(total)
            for (i in 0 until total) {
                val d = guidedDepth[i].coerceIn(0f, 1f)
                depthBytes[i] = (d * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                val diff = abs(d - focusDepth)
                val t = ((diff - dofHalfWidth) / dofFalloff).coerceIn(0f, 1f)
                val smooth = t * t * (3f - 2f * t)
                val sharp = (1f - smooth).coerceIn(0f, 1f)
                maskBytes[i] = (sharp * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            }

            return SubjectMaskData(
                maskBytes = maskBytes,
                width = w,
                height = h,
                hasSubject = true,
                depthBytes = depthBytes,
                focusDepth = focusDepth,
                dofHalfWidth = dofHalfWidth,
                dofFalloff = dofFalloff
            )
        }

        // Tier 2 Fallback: Bundled Selfie Segmentation -> Edge-Guided Dual-Geodesic Segmenter
        val selfieAlphas = withTimeoutOrNull(450L) {
            runSelfieSegmentation(thumb, focusNormX, focusNormY, isAutoMultiPointAf, isLiveStream = false)
        }
        if (selfieAlphas != null) {
            val polished = EdgeGuidedFocusSegmenter.refineMaskJointBilateral(
                alphaIn = selfieAlphas,
                rgbaBytes = uprightRgba,
                width = w,
                height = h,
                radius = 2,
                passes = 2
            )
            return SubjectMaskData(polished, w, height = h, hasSubject = true)
        }

        return EdgeGuidedFocusSegmenter.computeSubjectMask(
            rgbaBytes = uprightRgba,
            width = w,
            height = h,
            focusNormX = focusNormX,
            focusNormY = focusNormY,
            lensDiopters = lensDiopters
        )
    }

    /**
     * Scores all 11 Canon 6D AF diamond points using segmentation confidence (if active),
     * local high-frequency detail contrast, border color distinctiveness, and center priority.
     * Returns 1..4 salient AF indices sorted primary-first.
     */
    fun detectSalientAfPoints(
        uprightRgba: ByteArray,
        width: Int,
        height: Int,
        mask: SubjectMaskData?
    ): List<Int> {
        val total = width * height
        if (width < 16 || height < 16 || uprightRgba.size < total * 4) return listOf(5)

        val luma = FloatArray(total)
        val rArr = FloatArray(total)
        val gArr = FloatArray(total)
        val bArr = FloatArray(total)
        var p = 0
        for (i in 0 until total) {
            val r = (uprightRgba[p].toInt() and 0xFF) / 255f
            val g = (uprightRgba[p + 1].toInt() and 0xFF) / 255f
            val b = (uprightRgba[p + 2].toInt() and 0xFF) / 255f
            p += 4
            rArr[i] = r
            gArr[i] = g
            bArr[i] = b
            luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        // Sample average border color for figure-ground pop measurement
        var borderR = 0f
        var borderG = 0f
        var borderB = 0f
        var borderCount = 0
        val margin = max(2, min(width, height) / 14)
        for (y in 0 until height step 2) {
            val topOrBot = y < margin || y >= height - margin
            val row = y * width
            for (x in 0 until width step 2) {
                if (!topOrBot && x >= margin && x < width - margin) continue
                val idx = row + x
                borderR += rArr[idx]
                borderG += gArr[idx]
                borderB += bArr[idx]
                borderCount++
            }
        }
        if (borderCount > 0) {
            borderR /= borderCount
            borderG /= borderCount
            borderB /= borderCount
        }

        val hasMask = mask != null && mask.hasSubject && mask.width > 0 && mask.height > 0 &&
            mask.maskBytes.size >= mask.width * mask.height
        val radX = max(3, (width * 0.055f).roundToInt())
        val radY = max(3, (height * 0.07f).roundToInt())

        val scored = ArrayList<Pair<Int, Float>>(11)
        var maxScore = 0f
        var maxContrast = 0f

        for (pt in AfPoints6D.points) {
            val cx = (pt.normX * width).roundToInt().coerceIn(2, width - 3)
            val cy = (pt.normY * height).roundToInt().coerceIn(2, height - 3)

            var lapSum = 0f
            var colPopSum = 0f
            var maskSum = 0f
            var lumaSum = 0f
            var maxLocalGrad = 0f
            var count = 0

            val y0 = (cy - radY).coerceAtLeast(1)
            val y1 = (cy + radY).coerceAtMost(height - 2)
            val x0 = (cx - radX).coerceAtLeast(1)
            val x1 = (cx + radX).coerceAtMost(width - 2)

            for (y in y0..y1) {
                val row = y * width
                for (x in x0..x1) {
                    val idx = row + x
                    val lap = abs(4f * luma[idx] - luma[idx - 1] - luma[idx + 1] - luma[idx - width] - luma[idx + width])
                    lapSum += lap
                    val gLoc = (abs(luma[idx + 1] - luma[idx - 1]) + abs(luma[idx + width] - luma[idx - width])) * 0.5f
                    if (gLoc > maxLocalGrad) maxLocalGrad = gLoc
                    lumaSum += luma[idx]
                    val dr = rArr[idx] - borderR
                    val dg = gArr[idx] - borderG
                    val db = bArr[idx] - borderB
                    colPopSum += sqrt(dr * dr + dg * dg + db * db)

                    if (mask != null && hasMask) {
                        val mx = (x.toFloat() / width * (mask.width - 1)).roundToInt().coerceIn(0, mask.width - 1)
                        val my = (y.toFloat() / height * (mask.height - 1)).roundToInt().coerceIn(0, mask.height - 1)
                        maskSum += (mask.maskBytes[my * mask.width + mx].toInt() and 0xFF) / 255f
                    }
                    count++
                }
            }
            if (count == 0) continue

            val avgContrast = lapSum / count
            val avgPop = colPopSum / count
            val avgLuma = lumaSum / count
            val avgMask = if (hasMask) maskSum / count else 0f
            if (avgContrast > maxContrast) maxContrast = avgContrast

            val glarePenalty = if (avgLuma > 0.86f) 0.22f else if (avgLuma < 0.04f) 0.50f else 1.0f
            val centerBias = when {
                pt.index == 5 -> 1.65f
                pt.index in listOf(2, 4, 6, 8) -> 1.25f
                pt.index in listOf(1, 3, 7, 9) -> 1.12f
                else -> 0.92f
            }
            val baseScore = if (hasMask) {
                avgMask * 1.95f + avgContrast * 1.4f + maxLocalGrad * 0.85f + avgPop * 0.45f
            } else {
                avgContrast * 1.8f + maxLocalGrad * 1.6f + avgPop * 0.75f
            }
            val score = baseScore * centerBias * glarePenalty
            if (score > maxScore) maxScore = score
            scored.add(pt.index to score)
        }

        if (maxScore <= 0.001f || (!hasMask && maxContrast < 0.015f)) {
            return listOf(5)
        }

        val threshold = maxScore * 0.68f
        val selected = scored
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }

        return selected.ifEmpty { listOf(5) }
    }

    private suspend fun runSelfieSegmentation(
        bitmap: Bitmap,
        focusNormX: Float,
        focusNormY: Float,
        isAutoMultiPointAf: Boolean,
        isLiveStream: Boolean
    ): FloatArray? {
        val flag = if (isLiveStream) liveSelfieInFlight else stillSelfieInFlight
        if (!flag.compareAndSet(false, true)) return null
        val segmenter = if (isLiveStream) liveSelfieSegmenter else stillSelfieSegmenter
        return suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            try {
                segmenter.process(input)
                    .addOnCompleteListener { task ->
                        @Suppress("UNUSED_VARIABLE")
                        val keepAlive = bitmap
                        try {
                            if (task.isSuccessful) {
                                val mask = task.result
                                val w = bitmap.width
                                val h = bitmap.height
                                val mw = mask.width
                                val mh = mask.height
                                val buf = mask.buffer
                                buf.rewind()
                                buf.order(ByteOrder.nativeOrder())
                                val mTotal = mw * mh
                                if (mw <= 0 || mh <= 0 || buf.remaining() < mTotal * 4) {
                                    if (cont.isActive) cont.resume(null)
                                    return@addOnCompleteListener
                                }

                                val mFloats = FloatArray(mTotal)
                                for (i in 0 until mTotal) {
                                    mFloats[i] = buf.getFloat().coerceIn(0f, 1f)
                                }

                                val out = resampleBilinear(mFloats, mw, mh, w, h)
                                val valid = if (isAutoMultiPointAf) {
                                    isValidOverallForeground(out)
                                } else {
                                    val fx = (focusNormX.coerceIn(0.02f, 0.98f) * w).roundToInt().coerceIn(0, w - 1)
                                    val fy = (focusNormY.coerceIn(0.02f, 0.98f) * h).roundToInt().coerceIn(0, h - 1)
                                    val rad = max(3, min(w, h) / 14)
                                    isValidNeuralMaskNearPoint(out, w, h, fx, fy, rad, minConf = 0.22f)
                                }
                                if (cont.isActive) cont.resume(if (valid) out else null)
                            } else {
                                if (cont.isActive) cont.resume(null)
                            }
                        } catch (_: Throwable) {
                            if (cont.isActive) cont.resume(null)
                        } finally {
                            flag.set(false)
                        }
                    }
            } catch (_: Throwable) {
                flag.set(false)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun isValidOverallForeground(alphas: FloatArray): Boolean {
        var fgCount = 0
        var maxConf = 0f
        for (i in alphas.indices) {
            val a = alphas[i]
            if (a > maxConf) maxConf = a
            if (a >= 0.45f) fgCount++
        }
        val fgRatio = fgCount.toFloat() / alphas.size.coerceAtLeast(1)
        return maxConf >= 0.45f && fgRatio in 0.015f..0.88f
    }

    private fun isValidNeuralMaskNearPoint(
        alphas: FloatArray,
        w: Int,
        h: Int,
        fx: Int,
        fy: Int,
        rad: Int,
        minConf: Float
    ): Boolean {
        var maxNearFocus = 0f
        for (y in (fy - rad).coerceAtLeast(0)..(fy + rad).coerceAtMost(h - 1)) {
            val row = y * w
            for (x in (fx - rad).coerceAtLeast(0)..(fx + rad).coerceAtMost(w - 1)) {
                val c = alphas[row + x]
                if (c > maxNearFocus) maxNearFocus = c
            }
        }
        if (maxNearFocus < minConf) return false

        var fgCount = 0
        for (i in alphas.indices) {
            if (alphas[i] >= 0.45f) fgCount++
        }
        val fgRatio = fgCount.toFloat() / alphas.size.coerceAtLeast(1)
        return fgRatio in 0.012f..0.90f
    }

    private fun resampleBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh) return src
        val out = FloatArray(dw * dh)
        val scaleX = if (dw > 1) (sw - 1).toFloat() / (dw - 1) else 0f
        val scaleY = if (dh > 1) (sh - 1).toFloat() / (dh - 1) else 0f
        for (y in 0 until dh) {
            val fy = y * scaleY
            val y0 = fy.toInt().coerceIn(0, sh - 1)
            val y1 = min(y0 + 1, sh - 1)
            val ty = fy - y0
            val outRow = y * dw
            val row0 = y0 * sw
            val row1 = y1 * sw
            for (x in 0 until dw) {
                val fx = x * scaleX
                val x0 = fx.toInt().coerceIn(0, sw - 1)
                val x1 = min(x0 + 1, sw - 1)
                val tx = fx - x0
                val top = src[row0 + x0] + (src[row0 + x1] - src[row0 + x0]) * tx
                val bot = src[row1 + x0] + (src[row1 + x1] - src[row1 + x0]) * tx
                out[outRow + x] = (top + (bot - top) * ty).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun rgbaToBitmap(rgba: ByteArray, w: Int, h: Int): Bitmap {
        val total = w * h
        val pixels = IntArray(total)
        var p = 0
        for (i in 0 until total) {
            val r = rgba[p].toInt() and 0xFF
            val g = rgba[p + 1].toInt() and 0xFF
            val b = rgba[p + 2].toInt() and 0xFF
            val a = rgba[p + 3].toInt() and 0xFF
            p += 4
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun createUprightThreeTwoThumb(
        source: Bitmap,
        exifDegrees: Int,
        outW: Int,
        outH: Int
    ): Bitmap {
        val rotNorm = ((exifDegrees % 360) + 360) % 360
        val swap = rotNorm == 90 || rotNorm == 270
        val upW = if (swap) source.height else source.width
        val upH = if (swap) source.width else source.height
        val upAspect = upW.toFloat() / upH.toFloat().coerceAtLeast(1f)
        val targetAspect = outW.toFloat() / outH.toFloat()

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = Matrix()
        matrix.postTranslate(-source.width / 2f, -source.height / 2f)
        if (rotNorm != 0) {
            matrix.postRotate(rotNorm.toFloat())
        }
        val scale = if (abs(upAspect - targetAspect) < 0.01f || upAspect > targetAspect) {
            outH.toFloat() / upH.toFloat()
        } else {
            outW.toFloat() / upW.toFloat()
        }
        matrix.postScale(scale, scale)
        matrix.postTranslate(outW / 2f, outH / 2f)
        canvas.drawBitmap(source, matrix, null)
        return out
    }

    companion object {
        private const val STILL_MASK_W = 384
        private const val STILL_MASK_H = 256

        @Volatile
        private var instance: OpticalSubjectSegmenter? = null

        fun getInstance(context: Context): OpticalSubjectSegmenter {
            return instance ?: synchronized(this) {
                instance ?: OpticalSubjectSegmenter(context).also { instance = it }
            }
        }
    }
}
