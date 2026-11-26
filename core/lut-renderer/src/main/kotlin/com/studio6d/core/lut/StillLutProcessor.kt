package com.studio6d.core.lut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import com.studio6d.core.model.PictureStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CPU fallback for upright EXIF rotation, 3:2 framing, subject-masked disc bokeh,
 * 3D LUT, Picture Style fine-tuning, highlight roll-off, vignette & grain.
 */
object StillLutProcessor {

    fun applyStyle(
        context: Context,
        source: Bitmap,
        exifDegrees: Int = 0,
        style: PictureStyle,
        lutIntensity: Float,
        highlightRolloff: Float,
        vignetteStrength: Float,
        grainStrength: Float,
        bokehStrength: Float = 0f,
        subjectMask: SubjectMaskData = SubjectMaskData.NONE,
        contrastOffset: Int = 0,
        saturationOffset: Int = 0,
        colorToneOffset: Int = 0
    ): Bitmap {
        val upright = rotateBitmapIfNeeded(source, exifDegrees)
        val cropped = cropToThreeTwo(upright)
        if (upright !== source && upright !== cropped) {
            upright.recycle()
        }

        val lut = CubeLutParser.loadFromAssets(context, style.cubeAssetPath)
        val width = cropped.width
        val height = cropped.height
        val srcPixels = IntArray(width * height)
        cropped.getPixels(srcPixels, 0, width, 0, 0, width, height)
        if (cropped !== source) {
            cropped.recycle()
        }

        val hasBokeh = bokehStrength > 0.01f &&
            subjectMask.hasSubject &&
            subjectMask.width > 0 &&
            subjectMask.height > 0 &&
            subjectMask.maskBytes.size >= subjectMask.width * subjectMask.height

        val workingPixels = if (hasBokeh) {
            applyCpuSubjectBokeh(srcPixels, width, height, bokehStrength, subjectMask)
        } else {
            srcPixels
        }

        val size = lut.size
        val maxIdx = size - 1
        val lutData = lut.rgbFloats
        val invW = 1f / width
        val invH = 1f / height
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val rowsPerChunk = ((height + workers - 1) / workers).coerceAtLeast(1)
        val baseSeed = (System.currentTimeMillis().toInt() or 1)

        runBlocking(Dispatchers.Default) {
            coroutineScope {
                var startY = 0
                var workerIdx = 0
                while (startY < height) {
                    val fromY = startY
                    val toY = min(startY + rowsPerChunk, height)
                    val seed = baseSeed xor (workerIdx * 0x9E3779B9.toInt()) or 1
                    workerIdx++
                    launch {
                        processRows(
                            pixels = workingPixels,
                            width = width,
                            fromY = fromY,
                            toY = toY,
                            invW = invW,
                            invH = invH,
                            lutData = lutData,
                            size = size,
                            maxIdx = maxIdx,
                            lutIntensity = lutIntensity,
                            highlightRolloff = highlightRolloff,
                            vignetteStrength = vignetteStrength,
                            grainStrength = grainStrength,
                            contrastOffset = contrastOffset,
                            saturationOffset = saturationOffset,
                            colorToneOffset = colorToneOffset,
                            initialSeed = seed
                        )
                    }
                    startY = toY
                }
            }
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(workingPixels, 0, width, 0, 0, width, height)
        return out
    }

    private fun rotateBitmapIfNeeded(source: Bitmap, exifDegrees: Int): Bitmap {
        val rotNorm = ((exifDegrees % 360) + 360) % 360
        if (rotNorm == 0) return source
        val m = Matrix().apply { postRotate(rotNorm.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
    }

    private fun cropToThreeTwo(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val targetAspect = 1.5f
        val currentAspect = w.toFloat() / h.toFloat()
        if (abs(currentAspect - targetAspect) < 0.02f) return source

        return if (currentAspect > targetAspect) {
            val cropW = (h * targetAspect).roundToInt().coerceIn(1, w)
            val x = (w - cropW) / 2
            Bitmap.createBitmap(source, x, 0, cropW, h)
        } else {
            val cropH = (w / targetAspect).roundToInt().coerceIn(1, h)
            val y = (h - cropH) / 2
            Bitmap.createBitmap(source, 0, y, w, cropH)
        }
    }

    private fun applyCpuSubjectBokeh(
        src: IntArray,
        w: Int,
        h: Int,
        bokehStrength: Float,
        mask: SubjectMaskData
    ): IntArray {
        val out = src.copyOf()
        val maxRadiusPx = h * 0.024f * bokehStrength
        if (maxRadiusPx < 1.2f) return out

        val mw = mask.width
        val mh = mask.height
        val mBytes = mask.maskBytes
        val tapCount = 16
        val goldenAngle = 2.3999632f
        val cosA = FloatArray(tapCount)
        val sinA = FloatArray(tapCount)
        val radiiFrac = FloatArray(tapCount)
        for (i in 0 until tapCount) {
            val a = i * goldenAngle
            cosA[i] = cos(a)
            sinA[i] = sin(a)
            radiiFrac[i] = sqrt((i + 0.5f) / tapCount)
        }

        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val rowsPerChunk = ((h + workers - 1) / workers).coerceAtLeast(1)

        runBlocking(Dispatchers.Default) {
            coroutineScope {
                var startY = 0
                while (startY < h) {
                    val fromY = startY
                    val toY = min(startY + rowsPerChunk, h)
                    launch {
                        for (y in fromY until toY) {
                            val ny = y.toFloat() / h
                            val my = (ny * (mh - 1)).roundToInt().coerceIn(0, mh - 1)
                            val mRow = my * mw
                            val pRow = y * w
                            for (x in 0 until w) {
                                val nx = x.toFloat() / w
                                val mx = (nx * (mw - 1)).roundToInt().coerceIn(0, mw - 1)
                                val subj = (mBytes[mRow + mx].toInt() and 0xFF) / 255f
                                val blurMask = smoothstep(0.10f, 0.78f, 1f - subj)
                                if (blurMask <= 0.02f) continue

                                val rPx = blurMask * maxRadiusPx
                                val cPx = src[pRow + x]
                                val cR = ((cPx ushr 16) and 0xFF).toFloat()
                                val cG = ((cPx ushr 8) and 0xFF).toFloat()
                                val cB = (cPx and 0xFF).toFloat()

                                var rAcc = 0f
                                var gAcc = 0f
                                var bAcc = 0f
                                var wSum = 0f
                                for (t in 0 until tapCount) {
                                    val rad = radiiFrac[t] * rPx
                                    val tx = (x + cosA[t] * rad).roundToInt().coerceIn(0, w - 1)
                                    val ty = (y + sinA[t] * rad).roundToInt().coerceIn(0, h - 1)
                                    val tmx = (tx.toFloat() / w * (mw - 1)).roundToInt().coerceIn(0, mw - 1)
                                    val tmy = (ty.toFloat() / h * (mh - 1)).roundToInt().coerceIn(0, mh - 1)
                                    val tapSubj = (mBytes[tmy * mw + tmx].toInt() and 0xFF) / 255f
                                    val bgW = (1.05f - tapSubj).coerceIn(0.04f, 1f)
                                    val sp = src[ty * w + tx]
                                    rAcc += ((sp ushr 16) and 0xFF) * bgW
                                    gAcc += ((sp ushr 8) and 0xFF) * bgW
                                    bAcc += (sp and 0xFF) * bgW
                                    wSum += bgW
                                }
                                if (wSum > 0.01f) {
                                    val invW = 1f / wSum
                                    val outR = lerp(cR, rAcc * invW, blurMask).roundToInt().coerceIn(0, 255)
                                    val outG = lerp(cG, gAcc * invW, blurMask).roundToInt().coerceIn(0, 255)
                                    val outB = lerp(cB, bAcc * invW, blurMask).roundToInt().coerceIn(0, 255)
                                    out[pRow + x] = Color.rgb(outR, outG, outB)
                                }
                            }
                        }
                    }
                    startY = toY
                }
            }
        }
        return out
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun processRows(
        pixels: IntArray,
        width: Int,
        fromY: Int,
        toY: Int,
        invW: Float,
        invH: Float,
        lutData: FloatArray,
        size: Int,
        maxIdx: Int,
        lutIntensity: Float,
        highlightRolloff: Float,
        vignetteStrength: Float,
        grainStrength: Float,
        contrastOffset: Int,
        saturationOffset: Int,
        colorToneOffset: Int,
        initialSeed: Int
    ) {
        var xorshift = if (initialSeed == 0) 0x6D5A11 else initialSeed
        for (y in fromY until toY) {
            val ny = y * invH - 0.5f
            val ny2 = ny * ny
            val rowOffset = y * width
            for (x in 0 until width) {
                val idx = rowOffset + x
                val px = pixels[idx]
                var r = ((px ushr 16) and 0xFF) / 255f
                var g = ((px ushr 8) and 0xFF) / 255f
                var b = (px and 0xFF) / 255f

                if (highlightRolloff > 0.01f) {
                    val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    if (lum > 0.62f) {
                        val shoulder = ((lum - 0.62f) / 0.38f).coerceIn(0f, 1f) * highlightRolloff
                        r = lerp(r, r / (1f + r * 0.35f * highlightRolloff), shoulder)
                        g = lerp(g, g / (1f + g * 0.35f * highlightRolloff), shoulder)
                        b = lerp(b, b / (1f + b * 0.35f * highlightRolloff), shoulder)
                    }
                }

                // Inline zero-allocation trilinear 3D LUT lookup
                val rf = r.coerceIn(0f, 1f) * maxIdx
                val gf = g.coerceIn(0f, 1f) * maxIdx
                val bf = b.coerceIn(0f, 1f) * maxIdx

                val r0 = floor(rf).toInt().coerceIn(0, maxIdx)
                val g0 = floor(gf).toInt().coerceIn(0, maxIdx)
                val b0 = floor(bf).toInt().coerceIn(0, maxIdx)
                val r1 = min(r0 + 1, maxIdx)
                val g1 = min(g0 + 1, maxIdx)
                val b1 = min(b0 + 1, maxIdx)

                val dr = rf - r0
                val dg = gf - g0
                val db = bf - b0

                val i000 = ((b0 * size + g0) * size + r0) * 3
                val i100 = ((b0 * size + g0) * size + r1) * 3
                val i010 = ((b0 * size + g1) * size + r0) * 3
                val i110 = ((b0 * size + g1) * size + r1) * 3
                val i001 = ((b1 * size + g0) * size + r0) * 3
                val i101 = ((b1 * size + g0) * size + r1) * 3
                val i011 = ((b1 * size + g1) * size + r0) * 3
                val i111 = ((b1 * size + g1) * size + r1) * 3

                val lr = lerp(
                    lerp(lerp(lutData[i000], lutData[i100], dr), lerp(lutData[i010], lutData[i110], dr), dg),
                    lerp(lerp(lutData[i001], lutData[i101], dr), lerp(lutData[i011], lutData[i111], dr), dg),
                    db
                )
                val lg = lerp(
                    lerp(lerp(lutData[i000 + 1], lutData[i100 + 1], dr), lerp(lutData[i010 + 1], lutData[i110 + 1], dr), dg),
                    lerp(lerp(lutData[i001 + 1], lutData[i101 + 1], dr), lerp(lutData[i011 + 1], lutData[i111 + 1], dr), dg),
                    db
                )
                val lb = lerp(
                    lerp(lerp(lutData[i000 + 2], lutData[i100 + 2], dr), lerp(lutData[i010 + 2], lutData[i110 + 2], dr), dg),
                    lerp(lerp(lutData[i001 + 2], lutData[i101 + 2], dr), lerp(lutData[i011 + 2], lutData[i111 + 2], dr), dg),
                    db
                )

                r = lerp(r, lr, lutIntensity)
                g = lerp(g, lg, lutIntensity)
                b = lerp(b, lb, lutIntensity)

                if (contrastOffset != 0) {
                    val cFactor = 1f + contrastOffset * 0.08f
                    r = ((r - 0.5f) * cFactor + 0.5f).coerceIn(0f, 1f)
                    g = ((g - 0.5f) * cFactor + 0.5f).coerceIn(0f, 1f)
                    b = ((b - 0.5f) * cFactor + 0.5f).coerceIn(0f, 1f)
                }
                if (saturationOffset != 0) {
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val sFactor = (1f + saturationOffset * 0.14f).coerceAtLeast(0f)
                    r = lerp(l, r, sFactor).coerceIn(0f, 1f)
                    g = lerp(l, g, sFactor).coerceIn(0f, 1f)
                    b = lerp(l, b, sFactor).coerceIn(0f, 1f)
                }
                if (colorToneOffset != 0) {
                    r = (r + colorToneOffset * 0.014f).coerceIn(0f, 1f)
                    b = (b - colorToneOffset * 0.010f).coerceIn(0f, 1f)
                }

                if (vignetteStrength > 0.005f) {
                    val nx = x * invW - 0.5f
                    val dist = (nx * nx + ny2) * 2.2f
                    val factor = (1f - dist * vignetteStrength).coerceIn(0.2f, 1f)
                    r *= factor
                    g *= factor
                    b *= factor
                }

                if (grainStrength > 0.002f) {
                    xorshift = xorshift xor (xorshift shl 13)
                    xorshift = xorshift xor (xorshift ushr 17)
                    xorshift = xorshift xor (xorshift shl 5)
                    val n = (((xorshift and 0xFFFF) / 65535f) - 0.5f) * grainStrength
                    r += n
                    g += n
                    b += n
                }

                val outR = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val outG = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val outB = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                pixels[idx] = Color.rgb(outR, outG, outB)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
