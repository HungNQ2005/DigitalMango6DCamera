package com.studio6d.core.lut

/**
 * O(N) 3-Channel Color Fast Guided Image Filter (He et al.).
 * Snaps coarse depth/disparity maps tightly onto fine RGB color & luminance edges
 * without blurring sharp object silhouettes or colored acrylic/hair boundaries.
 */
object FastGuidedFilter {

    private class Workspace(var capacity: Int = 0) {
        var rCh = FloatArray(0)
        var gCh = FloatArray(0)
        var bCh = FloatArray(0)
        var scratch = FloatArray(0)
        var prod = FloatArray(0)
        var rowSum = FloatArray(0)
        var meanR = FloatArray(0)
        var meanG = FloatArray(0)
        var meanB = FloatArray(0)
        var meanP = FloatArray(0)
        var meanRR = FloatArray(0)
        var meanGG = FloatArray(0)
        var meanBB = FloatArray(0)
        var meanRP = FloatArray(0)
        var meanGP = FloatArray(0)
        var meanBP = FloatArray(0)
        var aR = FloatArray(0)
        var aG = FloatArray(0)
        var aB = FloatArray(0)
        var bCoeff = FloatArray(0)
        var meanAR = FloatArray(0)
        var meanAG = FloatArray(0)
        var meanAB = FloatArray(0)
        var meanBCoeff = FloatArray(0)

        fun ensure(total: Int) {
            if (capacity >= total) return
            capacity = total
            rCh = FloatArray(total)
            gCh = FloatArray(total)
            bCh = FloatArray(total)
            scratch = FloatArray(total)
            prod = FloatArray(total)
            rowSum = FloatArray(total)
            meanR = FloatArray(total)
            meanG = FloatArray(total)
            meanB = FloatArray(total)
            meanP = FloatArray(total)
            meanRR = FloatArray(total)
            meanGG = FloatArray(total)
            meanBB = FloatArray(total)
            meanRP = FloatArray(total)
            meanGP = FloatArray(total)
            meanBP = FloatArray(total)
            aR = FloatArray(total)
            aG = FloatArray(total)
            aB = FloatArray(total)
            bCoeff = FloatArray(total)
            meanAR = FloatArray(total)
            meanAG = FloatArray(total)
            meanAB = FloatArray(total)
            meanBCoeff = FloatArray(total)
        }
    }

    private val lock = Any()
    private val ws = Workspace()

    fun filter(
        guideRgba: ByteArray,
        src: FloatArray,
        width: Int,
        height: Int,
        radius: Int = 6,
        eps: Float = 0.0022f
    ): FloatArray {
        val total = width * height
        if (width < 4 || height < 4 || src.size < total || guideRgba.size < total * 4) {
            return src.copyOf()
        }

        synchronized(lock) {
            ws.ensure(total)
            val rCh = ws.rCh
            val gCh = ws.gCh
            val bCh = ws.bCh
            var ptr = 0
            val inv255 = 1f / 255f
            for (i in 0 until total) {
                rCh[i] = (guideRgba[ptr].toInt() and 0xFF) * inv255
                gCh[i] = (guideRgba[ptr + 1].toInt() and 0xFF) * inv255
                bCh[i] = (guideRgba[ptr + 2].toInt() and 0xFF) * inv255
                ptr += 4
            }

            val scratch = ws.scratch
            boxMeanInto(rCh, ws.meanR, width, height, radius, scratch)
            boxMeanInto(gCh, ws.meanG, width, height, radius, scratch)
            boxMeanInto(bCh, ws.meanB, width, height, radius, scratch)
            boxMeanInto(src, ws.meanP, width, height, radius, scratch)

            val prod = ws.prod
            for (i in 0 until total) prod[i] = rCh[i] * rCh[i]
            boxMeanInto(prod, ws.meanRR, width, height, radius, scratch)
            for (i in 0 until total) prod[i] = gCh[i] * gCh[i]
            boxMeanInto(prod, ws.meanGG, width, height, radius, scratch)
            for (i in 0 until total) prod[i] = bCh[i] * bCh[i]
            boxMeanInto(prod, ws.meanBB, width, height, radius, scratch)

            for (i in 0 until total) prod[i] = rCh[i] * src[i]
            boxMeanInto(prod, ws.meanRP, width, height, radius, scratch)
            for (i in 0 until total) prod[i] = gCh[i] * src[i]
            boxMeanInto(prod, ws.meanGP, width, height, radius, scratch)
            for (i in 0 until total) prod[i] = bCh[i] * src[i]
            boxMeanInto(prod, ws.meanBP, width, height, radius, scratch)

            val meanR = ws.meanR
            val meanG = ws.meanG
            val meanB = ws.meanB
            val meanP = ws.meanP
            val meanRR = ws.meanRR
            val meanGG = ws.meanGG
            val meanBB = ws.meanBB
            val meanRP = ws.meanRP
            val meanGP = ws.meanGP
            val meanBP = ws.meanBP
            val aR = ws.aR
            val aG = ws.aG
            val aB = ws.aB
            val bCoeff = ws.bCoeff

            for (i in 0 until total) {
                val varR = (meanRR[i] - meanR[i] * meanR[i]).coerceAtLeast(0f)
                val varG = (meanGG[i] - meanG[i] * meanG[i]).coerceAtLeast(0f)
                val varB = (meanBB[i] - meanB[i] * meanB[i]).coerceAtLeast(0f)
                val varSum = varR + varG + varB + eps * 3f

                val covRP = meanRP[i] - meanR[i] * meanP[i]
                val covGP = meanGP[i] - meanG[i] * meanP[i]
                val covBP = meanBP[i] - meanB[i] * meanP[i]

                val ar = covRP / varSum
                val ag = covGP / varSum
                val ab = covBP / varSum
                aR[i] = ar
                aG[i] = ag
                aB[i] = ab
                bCoeff[i] = meanP[i] - ar * meanR[i] - ag * meanG[i] - ab * meanB[i]
            }

            boxMeanInto(aR, ws.meanAR, width, height, radius, scratch)
            boxMeanInto(aG, ws.meanAG, width, height, radius, scratch)
            boxMeanInto(aB, ws.meanAB, width, height, radius, scratch)
            boxMeanInto(bCoeff, ws.meanBCoeff, width, height, radius, scratch)

            val meanAR = ws.meanAR
            val meanAG = ws.meanAG
            val meanAB = ws.meanAB
            val meanBCoeff = ws.meanBCoeff
            val out = FloatArray(total)
            for (i in 0 until total) {
                out[i] = (
                    meanAR[i] * rCh[i] +
                        meanAG[i] * gCh[i] +
                        meanAB[i] * bCh[i] +
                        meanBCoeff[i]
                    ).coerceIn(0f, 1f)
            }
            return out
        }
    }

    private fun boxMeanInto(
        src: FloatArray,
        out: FloatArray,
        w: Int,
        h: Int,
        r: Int,
        horizScratch: FloatArray
    ) {
        // Horizontal sliding sum (row-major) -> horizScratch
        val rClampedX = r.coerceAtMost(w - 1)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            var count = 0
            for (x in 0..rClampedX) {
                sum += src[row + x]
                count++
            }
            for (x in 0 until w) {
                horizScratch[row + x] = sum / count
                val leave = x - r
                if (leave >= 0) {
                    sum -= src[row + leave]
                    count--
                }
                val enter = x + r + 1
                if (enter < w) {
                    sum += src[row + enter]
                    count++
                }
            }
        }
        // Vertical sliding sum (row-major -> linear L1 cache access!) -> out
        val rClampedY = r.coerceAtMost(h - 1)
        val rowSum = ws.rowSum
        java.util.Arrays.fill(rowSum, 0, w, 0f)
        var count = 0
        for (iy in 0..rClampedY) {
            val rowOff = iy * w
            for (x in 0 until w) {
                rowSum[x] += horizScratch[rowOff + x]
            }
            count++
        }
        for (y in 0 until h) {
            val invCount = 1f / count
            val outOff = y * w
            for (x in 0 until w) {
                out[outOff + x] = rowSum[x] * invCount
            }
            val leave = y - r
            if (leave >= 0) {
                val leaveOff = leave * w
                for (x in 0 until w) {
                    rowSum[x] -= horizScratch[leaveOff + x]
                }
                count--
            }
            val enter = y + r + 1
            if (enter < h) {
                val enterOff = enter * w
                for (x in 0 until w) {
                    rowSum[x] += horizScratch[enterOff + x]
                }
                count++
            }
        }
    }
}
