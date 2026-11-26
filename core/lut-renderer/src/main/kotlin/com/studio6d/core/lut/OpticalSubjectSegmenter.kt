package com.studio6d.core.lut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.studio6d.core.model.AfPoints6D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * Unified 3-Tier Object-Silhouette Segmenter & Canon 11-Point Saliency Detector:
 * - Tier 1: Google ML Kit Subject Segmentation (Multi-subject union in Auto AF mode, or point-locked subject in Single-Point AF)
 * - Tier 2: Offline bundled ML Kit Selfie Segmentation (STREAM_MODE for Live View, SINGLE_IMAGE_MODE for Stills)
 * - Tier 3: EdgeGuidedFocusSegmenter Dual-Geodesic Barrier + Real Optical Defocus Gate (close-ups only, never flat screens)
 * Followed by Joint Bilateral RGB edge-snapping so the mask strictly follows real subject contours.
 */
class OpticalSubjectSegmenter private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val subjectInFlight = AtomicBoolean(false)
    private val liveSelfieInFlight = AtomicBoolean(false)
    private val stillSelfieInFlight = AtomicBoolean(false)

    @Volatile
    private var subjectModuleAvailable = false

    @Volatile
    private var lastModuleInstallAttemptMs = 0L

    @Volatile
    private var cachedLiveSubjectAlphas: FloatArray? = null

    @Volatile
    private var cachedLiveW = 0

    @Volatile
    private var cachedLiveH = 0

    @Volatile
    private var cachedLiveTimestampMs = 0L

    @Volatile
    private var lastLiveSubjectAttemptMs = 0L

    private val subjectSegmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder()
                    .enableConfidenceMask()
                    .build()
            )
            .build()
    )

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

    init {
        checkAndRequestSubjectModuleThrottled()
    }

    private fun checkAndRequestSubjectModuleThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastModuleInstallAttemptMs < 15_000L) return
        lastModuleInstallAttemptMs = now
        runCatching {
            val client = ModuleInstall.getClient(appContext)
            client.areModulesAvailable(subjectSegmenter)
                .addOnSuccessListener { avail ->
                    if (avail.areModulesAvailable()) {
                        subjectModuleAvailable = true
                    } else {
                        val req = ModuleInstallRequest.newBuilder()
                            .addApi(subjectSegmenter)
                            .build()
                        client.installModules(req)
                            .addOnSuccessListener { resp ->
                                if (resp.areModulesAlreadyInstalled()) {
                                    subjectModuleAvailable = true
                                } else {
                                    client.areModulesAvailable(subjectSegmenter)
                                        .addOnSuccessListener { recheck ->
                                            subjectModuleAvailable = recheck.areModulesAvailable()
                                        }
                                }
                            }
                    }
                }
        }
    }

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

        // NOTE: Never call Bitmap.recycle() manually on bitmaps handed to ML Kit InputImage;
        // NativeAllocationRegistry releases pixels safely once async ML Kit native workers complete.
        val bmp = rgbaToBitmap(uprightRgba, width, height)

        // Tier 2 (Fast Bundled STREAM_MODE Selfie Segmenter ~15ms for Live View)
        val selfieAlphas = withTimeoutOrNull(85L) {
            runSelfieSegmentation(bmp, focusNormX, focusNormY, isAutoMultiPointAf, isLiveStream = true)
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

        // Tier 1 (Async throttled GMS Subject Segmentation refresh without stalling 60fps Live View)
        val now = System.currentTimeMillis()
        if (subjectModuleAvailable && !subjectInFlight.get() && now - lastLiveSubjectAttemptMs >= 900L) {
            lastLiveSubjectAttemptMs = now
            bgScope.launch {
                val subj = runSubjectSegmentation(bmp, focusNormX, focusNormY, isAutoMultiPointAf)
                if (subj != null) {
                    cachedLiveSubjectAlphas = subj
                    cachedLiveW = width
                    cachedLiveH = height
                    cachedLiveTimestampMs = System.currentTimeMillis()
                }
            }
        } else if (!subjectModuleAvailable) {
            checkAndRequestSubjectModuleThrottled()
        }

        val cached = cachedLiveSubjectAlphas
        if (cached != null && cachedLiveW == width && cachedLiveH == height &&
            cached.size == total && (now - cachedLiveTimestampMs) < 1800L
        ) {
            val polished = EdgeGuidedFocusSegmenter.refineMaskJointBilateral(
                alphaIn = cached,
                rgbaBytes = uprightRgba,
                width = width,
                height = height,
                radius = 2,
                passes = 1
            )
            return SubjectMaskData(polished, width, height, hasSubject = true)
        }

        // Tier 3 (Real-time Edge-Guided + Optical Defocus Gate fallback)
        return EdgeGuidedFocusSegmenter.computeSubjectMask(
            rgbaBytes = uprightRgba,
            width = width,
            height = height,
            focusNormX = focusNormX,
            focusNormY = focusNormY,
            lensDiopters = lensDiopters
        )
    }

    suspend fun segmentStillCapture(
        sourceBitmap: Bitmap,
        exifDegrees: Int,
        focusNormX: Float,
        focusNormY: Float,
        lensDiopters: Float,
        isAutoMultiPointAf: Boolean = true
    ): SubjectMaskData {
        // NOTE: Never call thumb.recycle() manually; ML Kit's async native workers may outlive coroutine timeouts.
        val thumb = createUprightThreeTwoThumb(sourceBitmap, exifDegrees, STILL_MASK_W, STILL_MASK_H)
        val w = thumb.width
        val h = thumb.height
        val rgbaBuf = ByteBuffer.allocate(w * h * 4)
        thumb.copyPixelsToBuffer(rgbaBuf)
        val uprightRgba = rgbaBuf.array()

        if (subjectModuleAvailable) {
            val subjAlphas = withTimeoutOrNull(1100L) {
                runSubjectSegmentation(thumb, focusNormX, focusNormY, isAutoMultiPointAf)
            }
            if (subjAlphas != null) {
                val polished = EdgeGuidedFocusSegmenter.refineMaskJointBilateral(
                    alphaIn = subjAlphas,
                    rgbaBytes = uprightRgba,
                    width = w,
                    height = h,
                    radius = 2,
                    passes = 2
                )
                return SubjectMaskData(polished, w, height = h, hasSubject = true)
            }
        } else {
            checkAndRequestSubjectModuleThrottled()
        }

        val selfieAlphas = withTimeoutOrNull(400L) {
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
            val avgMask = if (hasMask) maskSum / count else 0f
            if (avgContrast > maxContrast) maxContrast = avgContrast

            val centerBias = if (pt.isCrossType) 1.15f else 1.0f
            val baseScore = if (hasMask) {
                avgMask * 1.85f + avgContrast * 2.2f + avgPop * 0.45f
            } else {
                avgContrast * 3.2f + avgPop * 0.65f
            }
            val score = baseScore * centerBias
            if (score > maxScore) maxScore = score
            scored.add(pt.index to score)
        }

        if (maxScore <= 0.001f || (!hasMask && maxContrast < 0.018f)) {
            return listOf(5)
        }

        val threshold = maxScore * 0.58f
        val selected = scored
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .take(4)
            .map { it.first }

        return selected.ifEmpty { listOf(5) }
    }

    private suspend fun runSubjectSegmentation(
        bitmap: Bitmap,
        focusNormX: Float,
        focusNormY: Float,
        isAutoMultiPointAf: Boolean
    ): FloatArray? {
        if (!subjectInFlight.compareAndSet(false, true)) return null
        return suspendCancellableCoroutine { cont ->
            val input = InputImage.fromBitmap(bitmap, 0)
            try {
                subjectSegmenter.process(input)
                    .addOnCompleteListener { task ->
                        @Suppress("UNUSED_VARIABLE")
                        val keepAlive = bitmap
                        try {
                            if (task.isSuccessful) {
                                val alphas = extractFocusedSubjectAlphas(
                                    result = task.result,
                                    w = bitmap.width,
                                    h = bitmap.height,
                                    focusNormX = focusNormX,
                                    focusNormY = focusNormY,
                                    isAutoMultiPointAf = isAutoMultiPointAf
                                )
                                if (cont.isActive) cont.resume(alphas)
                            } else {
                                subjectModuleAvailable = false
                                checkAndRequestSubjectModuleThrottled()
                                if (cont.isActive) cont.resume(null)
                            }
                        } catch (_: Throwable) {
                            if (cont.isActive) cont.resume(null)
                        } finally {
                            subjectInFlight.set(false)
                        }
                    }
            } catch (_: Throwable) {
                subjectInFlight.set(false)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun extractFocusedSubjectAlphas(
        result: SubjectSegmentationResult,
        w: Int,
        h: Int,
        focusNormX: Float,
        focusNormY: Float,
        isAutoMultiPointAf: Boolean
    ): FloatArray? {
        val total = w * h

        if (isAutoMultiPointAf) {
            // Multi-subject union keeps all detected subjects (e.g., multiple people) sharp
            val fgBuf = result.foregroundConfidenceMask
            if (fgBuf != null) {
                fgBuf.rewind()
                if (fgBuf.remaining() >= total) {
                    val fgOut = FloatArray(total)
                    for (i in 0 until total) {
                        fgOut[i] = fgBuf.get().coerceIn(0f, 1f)
                    }
                    if (isValidOverallForeground(fgOut)) {
                        return fgOut
                    }
                }
            }
            if (result.subjects.isNotEmpty()) {
                val union = FloatArray(total)
                for (subj in result.subjects) {
                    accumulateSubjectMask(subj, union, w, h)
                }
                if (isValidOverallForeground(union)) {
                    return union
                }
            }
            return null
        }

        // Single-point AF mode: prioritize subjects overlapping the tapped AF coordinate
        val fx = (focusNormX.coerceIn(0.02f, 0.98f) * w).roundToInt().coerceIn(0, w - 1)
        val fy = (focusNormY.coerceIn(0.02f, 0.98f) * h).roundToInt().coerceIn(0, h - 1)
        val rad = max(3, min(w, h) / 14)

        val focusedSubjects = ArrayList<com.google.mlkit.vision.segmentation.subject.Subject>()
        for (subj in result.subjects) {
            val mask = subj.confidenceMask ?: continue
            val sx = subj.startX
            val sy = subj.startY
            val sw = subj.width
            val sh = subj.height
            if (sw <= 0 || sh <= 0) continue

            val yLo = max(sy, fy - rad)
            val yHi = min(sy + sh - 1, fy + rad)
            val xLo = max(sx, fx - rad)
            val xHi = min(sx + sw - 1, fx + rad)
            if (xLo > xHi || yLo > yHi) continue

            mask.rewind()
            var maxConf = 0f
            for (y in yLo..yHi) {
                val localY = y - sy
                for (x in xLo..xHi) {
                    val localX = x - sx
                    val idx = localY * sw + localX
                    if (idx in 0 until mask.limit()) {
                        val c = mask.get(idx).coerceIn(0f, 1f)
                        if (c > maxConf) maxConf = c
                    }
                }
            }
            if (maxConf >= 0.18f) {
                focusedSubjects.add(subj)
            }
        }

        if (focusedSubjects.isNotEmpty()) {
            val out = FloatArray(total)
            for (subj in focusedSubjects) {
                accumulateSubjectMask(subj, out, w, h)
            }
            if (isValidNeuralMaskNearPoint(out, w, h, fx, fy, rad, minConf = 0.18f)) {
                return out
            }
        }

        val fgBuf = result.foregroundConfidenceMask ?: return null
        fgBuf.rewind()
        if (fgBuf.remaining() < total) return null
        val fgOut = FloatArray(total)
        for (i in 0 until total) {
            fgOut[i] = fgBuf.get().coerceIn(0f, 1f)
        }
        return if (isValidNeuralMaskNearPoint(fgOut, w, h, fx, fy, rad, minConf = 0.20f)) fgOut else null
    }

    private fun accumulateSubjectMask(
        subj: com.google.mlkit.vision.segmentation.subject.Subject,
        out: FloatArray,
        w: Int,
        h: Int
    ) {
        val mask = subj.confidenceMask ?: return
        val sx = subj.startX
        val sy = subj.startY
        val sw = subj.width
        val sh = subj.height
        mask.rewind()
        for (ly in 0 until sh) {
            val gy = sy + ly
            if (gy !in 0 until h) continue
            val outRow = gy * w
            val srcRow = ly * sw
            for (lx in 0 until sw) {
                val gx = sx + lx
                if (gx !in 0 until w) continue
                val srcIdx = srcRow + lx
                if (srcIdx < mask.limit()) {
                    val c = mask.get(srcIdx).coerceIn(0f, 1f)
                    val outIdx = outRow + gx
                    if (c > out[outIdx]) out[outIdx] = c
                }
            }
        }
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
