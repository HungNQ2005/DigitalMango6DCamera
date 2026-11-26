package com.studio6d.core.lut

/**
 * O(N) 3-Channel Color Fast Guided Image Filter (He et al.).
 * Snaps coarse depth/disparity maps tightly onto fine RGB color & luminance edges
 * without blurring sharp object silhouettes or colored acrylic/hair boundaries.
 */
object FastGuidedFilter {

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

        val rCh = FloatArray(total)
        val gCh = FloatArray(total)
        val bCh = FloatArray(total)
        var ptr = 0
        for (i in 0 until total) {
            rCh[i] = (guideRgba[ptr].toInt() and 0xFF) / 255f
            gCh[i] = (guideRgba[ptr + 1].toInt() and 0xFF) / 255f
            bCh[i] = (guideRgba[ptr + 2].toInt() and 0xFF) / 255f
            ptr += 4
        }

        val scratch = FloatArray(total)
        val meanR = boxMean(rCh, width, height, radius, scratch)
        val meanG = boxMean(gCh, width, height, radius, scratch)
        val meanB = boxMean(bCh, width, height, radius, scratch)
        val meanP = boxMean(src, width, height, radius, scratch)

        val prod = FloatArray(total)
        for (i in 0 until total) prod[i] = rCh[i] * rCh[i]
        val meanRR = boxMean(prod, width, height, radius, scratch)
        for (i in 0 until total) prod[i] = gCh[i] * gCh[i]
        val meanGG = boxMean(prod, width, height, radius, scratch)
        for (i in 0 until total) prod[i] = bCh[i] * bCh[i]
        val meanBB = boxMean(prod, width, height, radius, scratch)

        for (i in 0 until total) prod[i] = rCh[i] * src[i]
        val meanRP = boxMean(prod, width, height, radius, scratch)
        for (i in 0 until total) prod[i] = gCh[i] * src[i]
        val meanGP = boxMean(prod, width, height, radius, scratch)
        for (i in 0 until total) prod[i] = bCh[i] * src[i]
        val meanBP = boxMean(prod, width, height, radius, scratch)

        val aR = FloatArray(total)
        val aG = FloatArray(total)
        val aB = FloatArray(total)
        val bCoeff = FloatArray(total)

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

        val meanAR = boxMean(aR, width, height, radius, scratch)
        val meanAG = boxMean(aG, width, height, radius, scratch)
        val meanAB = boxMean(aB, width, height, radius, scratch)
        val meanBCoeff = boxMean(bCoeff, width, height, radius, scratch)

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

    private fun boxMean(src: FloatArray, w: Int, h: Int, r: Int, horizScratch: FloatArray): FloatArray {
        val out = FloatArray(w * h)
        // Horizontal sliding sum -> horizScratch
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            var count = 0
            val initEnd = r.coerceAtMost(w - 1)
            for (x in 0..initEnd) {
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
        // Vertical sliding sum -> out
        for (x in 0 until w) {
            var sum = 0f
            var count = 0
            val initEnd = r.coerceAtMost(h - 1)
            for (y in 0..initEnd) {
                sum += horizScratch[y * w + x]
                count++
            }
            for (y in 0 until h) {
                out[y * w + x] = sum / count
                val leave = y - r
                if (leave >= 0) {
                    sum -= horizScratch[leave * w + x]
                    count--
                }
                val enter = y + r + 1
                if (enter < h) {
                    sum += horizScratch[enter * w + x]
                    count++
                }
            }
        }
        return out
    }
}
