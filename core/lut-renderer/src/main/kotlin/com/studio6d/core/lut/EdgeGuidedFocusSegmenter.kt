package com.studio6d.core.lut

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Alpha matte aligned with upright 3:2 viewport [0..1]x[0..1] (255 = sharp foreground subject, 0 = background).
 * Row 0 is the TOP edge of the image/viewport.
 */
class SubjectMaskData(
    val maskBytes: ByteArray,
    val width: Int,
    val height: Int,
    val hasSubject: Boolean,
    val depthBytes: ByteArray? = null,
    val focusDepth: Float = 0.5f,
    val dofHalfWidth: Float = 0.085f,
    val dofFalloff: Float = 0.28f
) {
    companion object {
        val NONE = SubjectMaskData(ByteArray(0), 0, 0, false)
    }
}

/**
 * Dual-Geodesic Minimum-Barrier Distance (MBD) + Multi-Prototype GrabCut Appearance +
 * Joint Bilateral Edge-Snap segmenter.
 * Strictly follows true image color/edge boundaries so focused objects of arbitrary shape
 * stay sharp while backgrounds separate cleanly without circular radial falloff.
 */
object EdgeGuidedFocusSegmenter {

    fun computeSubjectMask(
        rgbaBuffer: ByteBuffer,
        width: Int,
        height: Int,
        focusNormX: Float,
        focusNormY: Float,
        lensDiopters: Float = 0f
    ): SubjectMaskData {
        val total = width * height
        if (total <= 0 || rgbaBuffer.capacity() < total * 4) return SubjectMaskData.NONE
        val bytes = ByteArray(total * 4)
        rgbaBuffer.position(0)
        rgbaBuffer.get(bytes)
        return computeSubjectMask(bytes, width, height, focusNormX, focusNormY, lensDiopters)
    }

    fun computeSubjectMask(
        rgbaBytes: ByteArray,
        width: Int,
        height: Int,
        focusNormX: Float,
        focusNormY: Float,
        lensDiopters: Float = 0f
    ): SubjectMaskData {
        val total = width * height
        if (width < 16 || height < 16 || rgbaBytes.size < total * 4) return SubjectMaskData.NONE
        // Optical defocus gate: skip distant infinity scenes (> ~2.6m) on calibrated sensors, while
        // permitting close/tabletop 3D objects (<= 2.6m) and uncalibrated sensors reporting <= 0.01f.
        if (lensDiopters in 0.01f..0.38f) return SubjectMaskData.NONE

        val rArr = FloatArray(total)
        val gArr = FloatArray(total)
        val bArr = FloatArray(total)
        val lArr = FloatArray(total)
        val c1Arr = FloatArray(total)
        val c2Arr = FloatArray(total)

        var p = 0
        for (i in 0 until total) {
            val r = (rgbaBytes[p].toInt() and 0xFF) / 255f
            val g = (rgbaBytes[p + 1].toInt() and 0xFF) / 255f
            val b = (rgbaBytes[p + 2].toInt() and 0xFF) / 255f
            p += 4
            rArr[i] = r
            gArr[i] = g
            bArr[i] = b
        }

        // 3x3 light spatial smoothing into perceptual opponent color (L, R-G, (R+G)/2-B)
        for (y in 0 until height) {
            val y0 = (y - 1).coerceAtLeast(0)
            val y1 = (y + 1).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x0 = (x - 1).coerceAtLeast(0)
                val x1 = (x + 1).coerceAtMost(width - 1)
                var rs = 0f
                var gs = 0f
                var bs = 0f
                for (yy in y0..y1) {
                    val row = yy * width
                    for (xx in x0..x1) {
                        val idx = row + xx
                        rs += rArr[idx]
                        gs += gArr[idx]
                        bs += bArr[idx]
                    }
                }
                val inv = 1f / ((y1 - y0 + 1) * (x1 - x0 + 1))
                val r = rs * inv
                val g = gs * inv
                val b = bs * inv
                val idx = y * width + x
                lArr[idx] = 0.2126f * r + 0.7152f * g + 0.0722f * b
                c1Arr[idx] = r - g
                c2Arr[idx] = 0.5f * (r + g) - b
            }
        }

        // Sobel edge gradient magnitude on opponent channels
        val grad = FloatArray(total)
        for (y in 1 until height - 1) {
            val up = (y - 1) * width
            val mid = y * width
            val dn = (y + 1) * width
            for (x in 1 until width - 1) {
                val gxL = (lArr[up + x + 1] + 2f * lArr[mid + x + 1] + lArr[dn + x + 1]) -
                    (lArr[up + x - 1] + 2f * lArr[mid + x - 1] + lArr[dn + x - 1])
                val gyL = (lArr[dn + x - 1] + 2f * lArr[dn + x] + lArr[dn + x + 1]) -
                    (lArr[up + x - 1] + 2f * lArr[up + x] + lArr[up + x + 1])
                val gxC1 = c1Arr[mid + x + 1] - c1Arr[mid + x - 1]
                val gyC1 = c1Arr[dn + x] - c1Arr[up + x]
                val gxC2 = c2Arr[mid + x + 1] - c2Arr[mid + x - 1]
                val gyC2 = c2Arr[dn + x] - c2Arr[up + x]
                grad[mid + x] = 0.25f * (hypot(gxL, gyL) + 0.85f * hypot(gxC1, gyC1) + 0.75f * hypot(gxC2, gyC2))
            }
        }

        val fx = (focusNormX.coerceIn(0.05f, 0.95f) * width).roundToInt().coerceIn(2, width - 3)
        val fy = (focusNormY.coerceIn(0.05f, 0.95f) * height).roundToInt().coerceIn(2, height - 3)
        val minDim = min(width, height)
        val maxDim = max(width, height)
        val rCore = max(2, minDim / 22)

        val focusIdx = fy * width + fx
        val centerL = lArr[focusIdx]
        val centerC1 = c1Arr[focusIdx]
        val centerC2 = c2Arr[focusIdx]

        // 1. Sample diverse foreground prototypes (up to 4) within low-barrier region around (fx, fy)
        val sampleR = max(rCore + 3, minDim / 5)
        val fgCandidates = ArrayList<Int>(160)
        for (y in (fy - sampleR).coerceAtLeast(1)..(fy + sampleR).coerceAtMost(height - 2)) {
            for (x in (fx - sampleR).coerceAtLeast(1)..(fx + sampleR).coerceAtMost(width - 2)) {
                val dx = x - fx
                val dy = y - fy
                val insideCore = abs(dx) <= rCore && abs(dy) <= rCore
                val idx = y * width + x
                val cDiff = colorDistOpp(lArr[idx], c1Arr[idx], c2Arr[idx], centerL, centerC1, centerC2)
                if (insideCore || (cDiff < 0.22f && grad[idx] < 0.18f)) {
                    fgCandidates.add(idx)
                }
            }
        }
        if (fgCandidates.isEmpty()) fgCandidates.add(focusIdx)
        val fgProtos = selectFarthestPrototypes(fgCandidates, 4, lArr, c1Arr, c2Arr, focusIdx)

        // 2. Sample background prototypes (up to 6) from frame borders outside focus exclusion zone
        val borderMargin = max(2, minDim / 16)
        val exclRadius = 0.20f * maxDim
        val bgCandidates = ArrayList<Int>(256)
        for (y in 0 until height) {
            val isTopOrBottom = y < borderMargin || y >= height - borderMargin
            val row = y * width
            for (x in 0 until width) {
                if (!isTopOrBottom && x >= borderMargin && x < width - borderMargin) continue
                val dFocus = hypot((x - fx).toFloat(), (y - fy).toFloat())
                if (dFocus < exclRadius) continue
                bgCandidates.add(row + x)
            }
        }
        val bgProtos = selectBackgroundPrototypes(bgCandidates, fgProtos, 6, lArr, c1Arr, c2Arr)
        if (bgProtos.isEmpty()) return SubjectMaskData.NONE

        // 3. Dual-Geodesic Minimum-Barrier Distance sweeps (dFg from focus core + connected homogeneous object fill, dBg from borders)
        val dFg = FloatArray(total) { 1e4f }
        val dBg = FloatArray(total) { 1e4f }

        for (y in (fy - rCore).coerceAtLeast(0)..(fy + rCore).coerceAtMost(height - 1)) {
            val row = y * width
            for (x in (fx - rCore).coerceAtLeast(0)..(fx + rCore).coerceAtMost(width - 1)) {
                dFg[row + x] = 0f
            }
        }

        // Expand foreground seeds along smooth uniform bodies (e.g., tall cylindrical bottle) within barrier gate
        val maxFillRadiusSq = (maxDim * 0.36f) * (maxDim * 0.36f)
        val seedQueue = IntArray(total)
        val seedVisited = BooleanArray(total)
        var sHead = 0
        var sTail = 0
        seedVisited[focusIdx] = true
        seedQueue[sTail++] = focusIdx
        while (sHead < sTail) {
            val cur = seedQueue[sHead++]
            dFg[cur] = 0f
            val cx = cur % width
            val cy = cur / width
            for (dy in -1..1) {
                val ny = cy + dy
                if (ny <= 1 || ny >= height - 2) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    if (nx <= 1 || nx >= width - 2) continue
                    val nIdx = ny * width + nx
                    if (seedVisited[nIdx]) continue
                    val rx = (nx - fx).toFloat()
                    val ry = (ny - fy).toFloat()
                    if (rx * rx + ry * ry > maxFillRadiusSq) continue
                    val cDiff = colorDistOpp(lArr[nIdx], c1Arr[nIdx], c2Arr[nIdx], centerL, centerC1, centerC2)
                    if (cDiff < 0.14f && grad[nIdx] < 0.14f) {
                        seedVisited[nIdx] = true
                        seedQueue[sTail++] = nIdx
                    }
                }
            }
        }

        for (idx in bgCandidates) {
            val minF = minProtoDist(lArr[idx], c1Arr[idx], c2Arr[idx], fgProtos)
            val minB = minProtoDist(lArr[idx], c1Arr[idx], c2Arr[idx], bgProtos)
            if (minB <= minF * 1.15f) {
                dBg[idx] = 0f
            }
        }

        val eps = 0.05f / maxDim.toFloat()
        runGeodesicSweeps(dFg, width, height, lArr, c1Arr, c2Arr, grad, eps)
        runGeodesicSweeps(dBg, width, height, lArr, c1Arr, c2Arr, grad, eps)

        // 4. Combine Geodesic probability + Multi-prototype Appearance probability
        val rawAlpha = FloatArray(total)
        for (i in 0 until total) {
            val geoProb = dBg[i] / (dFg[i] + dBg[i] + 0.025f)
            val minF = minProtoDist(lArr[i], c1Arr[i], c2Arr[i], fgProtos)
            val minB = minProtoDist(lArr[i], c1Arr[i], c2Arr[i], bgProtos)
            val appProb = minB / (minF + minB + 0.035f)
            val combined = 0.72f * geoProb + 0.28f * appProb
            rawAlpha[i] = (1f / (1f + exp(-(combined - 0.50f) * 11f))).coerceIn(0f, 1f)
        }

        // 5. Morphological cleanup: keep focus-connected foreground & fill interior holes (<12%)
        keepFocusConnectedComponent(rawAlpha, width, height, fx, fy)
        fillSmallInteriorHoles(rawAlpha, width, height, maxHoleRatio = 0.12f)

        // 6. Contour Boundary Gradient + Closed-Object Border Gate (works for smooth objects & rejects flat screens)
        var fgCount = 0
        var fgAlphaSum = 0f
        var bgAlphaSum = 0f
        var bgCount = 0
        var boundaryGradSum = 0f
        var boundaryCount = 0
        var borderLeakCount = 0
        var borderTotalCount = 0

        for (y in 0 until height) {
            val row = y * width
            val isOuterBorder = y < 2 || y >= height - 2
            for (x in 0 until width) {
                val idx = row + x
                val a = rawAlpha[idx]
                if (a >= 0.5f) {
                    fgCount++
                    fgAlphaSum += a
                } else {
                    bgCount++
                    bgAlphaSum += a
                }
                if (isOuterBorder || x < 2 || x >= width - 2) {
                    borderTotalCount++
                    if (a >= 0.55f) borderLeakCount++
                }
                if (x in 1 until width - 1 && y in 1 until height - 1) {
                    val isEdgePixel = a in 0.25f..0.75f || (
                        a >= 0.45f && (
                            rawAlpha[idx - 1] < 0.45f ||
                                rawAlpha[idx + 1] < 0.45f ||
                                rawAlpha[idx - width] < 0.45f ||
                                rawAlpha[idx + width] < 0.45f
                            )
                        )
                    if (isEdgePixel) {
                        boundaryGradSum += grad[idx]
                        boundaryCount++
                    }
                }
            }
        }
        val fgRatio = fgCount.toFloat() / total
        val fgMean = if (fgCount > 0) fgAlphaSum / fgCount else 0f
        val bgMean = if (bgCount > 0) bgAlphaSum / bgCount else 1f
        val boundaryGradAvg = if (boundaryCount > 0) boundaryGradSum / boundaryCount else 0f
        val borderLeakRatio = if (borderTotalCount > 0) borderLeakCount.toFloat() / borderTotalCount else 1f

        val closeOrUncalibrated = lensDiopters >= 0.38f || lensDiopters <= 0.01f
        val minBoundaryGrad = if (lensDiopters >= 0.38f) 0.020f else 0.030f
        val hasSubject = closeOrUncalibrated &&
            fgRatio in 0.02f..0.78f &&
            (fgMean - bgMean) > 0.26f &&
            boundaryGradAvg >= minBoundaryGrad &&
            borderLeakRatio < 0.44f
        if (!hasSubject) return SubjectMaskData.NONE

        val refinedBytes = refineMaskJointBilateral(rawAlpha, rgbaBytes, width, height, radius = 2, passes = 2)
        return SubjectMaskData(maskBytes = refinedBytes, width = width, height = height, hasSubject = true)
    }

    /**
     * Joint Bilateral edge-snap filter guided by original RGB pixel colors so alpha transitions
     * lock tightly onto true object contours without stair-stepping or radial smudge.
     */
    fun refineMaskJointBilateral(
        alphaIn: FloatArray,
        rgbaBytes: ByteArray,
        width: Int,
        height: Int,
        radius: Int = 2,
        passes: Int = 2
    ): ByteArray {
        val total = width * height
        if (total <= 0 || alphaIn.size < total || rgbaBytes.size < total * 4) return ByteArray(0)

        var src = alphaIn.copyOf(total)
        var dst = FloatArray(total)

        val inv2SigmaSpatial2 = 1f / (2f * 2.0f * 2.0f)
        val inv2SigmaColor2 = 1f / (2f * 0.10f * 0.10f)

        repeat(passes.coerceAtLeast(1)) {
            for (y in 0 until height) {
                val yMin = (y - radius).coerceAtLeast(0)
                val yMax = (y + radius).coerceAtMost(height - 1)
                val row = y * width
                for (x in 0 until width) {
                    val cIdx = row + x
                    val cOff = cIdx * 4
                    val cr = (rgbaBytes[cOff].toInt() and 0xFF) / 255f
                    val cg = (rgbaBytes[cOff + 1].toInt() and 0xFF) / 255f
                    val cb = (rgbaBytes[cOff + 2].toInt() and 0xFF) / 255f

                    var wSum = 0f
                    var valSum = 0f
                    val xMin = (x - radius).coerceAtLeast(0)
                    val xMax = (x + radius).coerceAtMost(width - 1)

                    for (yy in yMin..yMax) {
                        val dy = (yy - y).toFloat()
                        val dy2 = dy * dy
                        val nRow = yy * width
                        for (xx in xMin..xMax) {
                            val dx = (xx - x).toFloat()
                            val nIdx = nRow + xx
                            val nOff = nIdx * 4
                            val dr = ((rgbaBytes[nOff].toInt() and 0xFF) / 255f) - cr
                            val dg = ((rgbaBytes[nOff + 1].toInt() and 0xFF) / 255f) - cg
                            val db = ((rgbaBytes[nOff + 2].toInt() and 0xFF) / 255f) - cb
                            val colorDist2 = dr * dr + dg * dg + db * db
                            val spaceDist2 = dx * dx + dy2
                            val w = exp(-(spaceDist2 * inv2SigmaSpatial2 + colorDist2 * inv2SigmaColor2))
                            valSum += src[nIdx] * w
                            wSum += w
                        }
                    }
                    dst[cIdx] = if (wSum > 1e-5f) (valSum / wSum).coerceIn(0f, 1f) else src[cIdx]
                }
            }
            val tmp = src
            src = dst
            dst = tmp
        }

        val out = ByteArray(total)
        for (i in 0 until total) {
            out[i] = (src[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    private fun runGeodesicSweeps(
        dist: FloatArray,
        width: Int,
        height: Int,
        lArr: FloatArray,
        c1Arr: FloatArray,
        c2Arr: FloatArray,
        grad: FloatArray,
        eps: Float
    ) {
        val diagEps = eps * 1.414f
        repeat(3) {
            // Forward sweep
            for (y in 1 until height - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val idx = row + x
                    var best = dist[idx]
                    best = min(best, barrierCost(idx, idx - 1, eps, lArr, c1Arr, c2Arr, grad, dist))
                    best = min(best, barrierCost(idx, idx - width, eps, lArr, c1Arr, c2Arr, grad, dist))
                    best = min(best, barrierCost(idx, idx - width - 1, diagEps, lArr, c1Arr, c2Arr, grad, dist))
                    best = min(best, barrierCost(idx, idx - width + 1, diagEps, lArr, c1Arr, c2Arr, grad, dist))
                    dist[idx] = best
                }
            }
            // Backward sweep
            for (y in height - 2 downTo 1) {
                val row = y * width
                for (x in width - 2 downTo 1) {
                    val idx = row + x
                    var best = dist[idx]
                    best = min(best, barrierCost(idx, idx + 1, eps, lArr, c1Arr, c2Arr, grad, dist))
                    best = min(best, barrierCost(idx, idx + width, eps, lArr, c1Arr, c2Arr, grad, dist))
                    best = min(best, barrierCost(idx, idx + width + 1, diagEps, lArr, c1Arr, c2Arr, grad, dist))
                    best = min(best, barrierCost(idx, idx + width - 1, diagEps, lArr, c1Arr, c2Arr, grad, dist))
                    dist[idx] = best
                }
            }
        }
    }

    private fun barrierCost(
        cur: Int,
        nbr: Int,
        stepEps: Float,
        lArr: FloatArray,
        c1Arr: FloatArray,
        c2Arr: FloatArray,
        grad: FloatArray,
        dist: FloatArray
    ): Float {
        val cDiff = colorDistOpp(lArr[cur], c1Arr[cur], c2Arr[cur], lArr[nbr], c1Arr[nbr], c2Arr[nbr])
        val edge = max(grad[cur], grad[nbr])
        return dist[nbr] + cDiff + 1.6f * edge + stepEps
    }

    private fun keepFocusConnectedComponent(alpha: FloatArray, width: Int, height: Int, fx: Int, fy: Int) {
        val total = width * height
        val visited = BooleanArray(total)
        val queue = IntArray(total)
        var head = 0
        var tail = 0

        val rSeed = max(2, min(width, height) / 28)
        for (y in (fy - rSeed).coerceAtLeast(0)..(fy + rSeed).coerceAtMost(height - 1)) {
            val row = y * width
            for (x in (fx - rSeed).coerceAtLeast(0)..(fx + rSeed).coerceAtMost(width - 1)) {
                val idx = row + x
                if (alpha[idx] >= 0.35f && !visited[idx]) {
                    visited[idx] = true
                    queue[tail++] = idx
                }
            }
        }
        if (tail == 0) {
            val center = fy * width + fx
            visited[center] = true
            queue[tail++] = center
        }

        while (head < tail) {
            val cur = queue[head++]
            val cx = cur % width
            val cy = cur / width
            for (dy in -1..1) {
                val ny = cy + dy
                if (ny < 0 || ny >= height) continue
                val nRow = ny * width
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx
                    if (nx < 0 || nx >= width) continue
                    val nIdx = nRow + nx
                    if (!visited[nIdx] && alpha[nIdx] >= 0.35f) {
                        visited[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
            }
        }

        for (i in 0 until total) {
            if (!visited[i] && alpha[i] >= 0.35f) {
                alpha[i] *= 0.15f
            }
        }
    }

    private fun fillSmallInteriorHoles(alpha: FloatArray, width: Int, height: Int, maxHoleRatio: Float) {
        val total = width * height
        val maxHolePixels = (total * maxHoleRatio).toInt().coerceAtLeast(1)
        val visited = BooleanArray(total)
        val queue = IntArray(total)

        for (i in 0 until total) {
            if (visited[i] || alpha[i] >= 0.45f) continue
            var head = 0
            var tail = 0
            var touchesBorder = false
            visited[i] = true
            queue[tail++] = i

            while (head < tail) {
                val cur = queue[head++]
                val cx = cur % width
                val cy = cur / width
                if (cx == 0 || cx == width - 1 || cy == 0 || cy == height - 1) {
                    touchesBorder = true
                }
                if (cx > 0) {
                    val n = cur - 1
                    if (!visited[n] && alpha[n] < 0.45f) { visited[n] = true; queue[tail++] = n }
                }
                if (cx + 1 < width) {
                    val n = cur + 1
                    if (!visited[n] && alpha[n] < 0.45f) { visited[n] = true; queue[tail++] = n }
                }
                if (cy > 0) {
                    val n = cur - width
                    if (!visited[n] && alpha[n] < 0.45f) { visited[n] = true; queue[tail++] = n }
                }
                if (cy + 1 < height) {
                    val n = cur + width
                    if (!visited[n] && alpha[n] < 0.45f) { visited[n] = true; queue[tail++] = n }
                }
            }

            if (!touchesBorder && tail <= maxHolePixels) {
                for (k in 0 until tail) {
                    alpha[queue[k]] = 0.92f
                }
            }
        }
    }

    private data class OppColor(val l: Float, val c1: Float, val c2: Float)

    private fun selectFarthestPrototypes(
        candidates: List<Int>,
        maxCount: Int,
        lArr: FloatArray,
        c1Arr: FloatArray,
        c2Arr: FloatArray,
        anchorIdx: Int
    ): List<OppColor> {
        val protos = ArrayList<OppColor>(maxCount)
        protos.add(OppColor(lArr[anchorIdx], c1Arr[anchorIdx], c2Arr[anchorIdx]))
        val stride = max(1, candidates.size / 120)
        while (protos.size < maxCount) {
            var bestIdx = -1
            var bestDist = 0.04f
            var i = 0
            while (i < candidates.size) {
                val c = candidates[i]
                val d = minProtoDist(lArr[c], c1Arr[c], c2Arr[c], protos)
                if (d > bestDist) {
                    bestDist = d
                    bestIdx = c
                }
                i += stride
            }
            if (bestIdx < 0) break
            protos.add(OppColor(lArr[bestIdx], c1Arr[bestIdx], c2Arr[bestIdx]))
        }
        return protos
    }

    private fun selectBackgroundPrototypes(
        candidates: List<Int>,
        fgProtos: List<OppColor>,
        maxCount: Int,
        lArr: FloatArray,
        c1Arr: FloatArray,
        c2Arr: FloatArray
    ): List<OppColor> {
        if (candidates.isEmpty()) return emptyList()
        val stride = max(1, candidates.size / 180)
        // Pick first border pixel furthest from foreground prototypes
        var firstIdx = -1
        var bestFgDist = -1f
        var i = 0
        while (i < candidates.size) {
            val c = candidates[i]
            val d = minProtoDist(lArr[c], c1Arr[c], c2Arr[c], fgProtos)
            if (d > bestFgDist) {
                bestFgDist = d
                firstIdx = c
            }
            i += stride
        }
        if (firstIdx < 0) return emptyList()
        val protos = ArrayList<OppColor>(maxCount)
        protos.add(OppColor(lArr[firstIdx], c1Arr[firstIdx], c2Arr[firstIdx]))

        while (protos.size < maxCount) {
            var bestIdx = -1
            var bestScore = 0.05f
            i = 0
            while (i < candidates.size) {
                val c = candidates[i]
                val dBg = minProtoDist(lArr[c], c1Arr[c], c2Arr[c], protos)
                val dFg = minProtoDist(lArr[c], c1Arr[c], c2Arr[c], fgProtos)
                val score = min(dBg, dFg * 1.2f)
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = c
                }
                i += stride
            }
            if (bestIdx < 0) break
            protos.add(OppColor(lArr[bestIdx], c1Arr[bestIdx], c2Arr[bestIdx]))
        }
        return protos
    }

    private fun minProtoDist(l: Float, c1: Float, c2: Float, protos: List<OppColor>): Float {
        var best = Float.MAX_VALUE
        for (k in protos.indices) {
            val p = protos[k]
            val d = colorDistOpp(l, c1, c2, p.l, p.c1, p.c2)
            if (d < best) best = d
        }
        return best
    }

    private fun colorDistOpp(l1: Float, a1: Float, b1: Float, l2: Float, a2: Float, b2: Float): Float {
        val dl = (l1 - l2) * 0.85f
        val da = (a1 - a2) * 1.10f
        val db = (b1 - b2) * 0.95f
        return sqrt(dl * dl + da * da + db * db)
    }
}
