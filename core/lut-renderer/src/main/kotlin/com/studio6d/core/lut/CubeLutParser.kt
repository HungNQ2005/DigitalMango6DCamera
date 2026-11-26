package com.studio6d.core.lut

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Parsed 3D LUT data ready to upload to OpenGL ES 3.0 `GL_TEXTURE_3D` (RGBA8)
 * or used on CPU for still-image processing.
 */
data class Lut3DData(
    val size: Int,
    /** Normalized [0f..1f] RGB triplets in B-major -> G -> R order (standard .cube order). */
    val rgbFloats: FloatArray
) {
    fun toRgbaByteBuffer(): ByteBuffer {
        val totalTexels = size * size * size
        val buffer = ByteBuffer.allocateDirect(totalTexels * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until totalTexels) {
            val base = i * 3
            val r = (rgbFloats[base].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            val g = (rgbFloats[base + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            val b = (rgbFloats[base + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            buffer.put(r)
            buffer.put(g)
            buffer.put(b)
            buffer.put(0xFF.toByte())
        }
        buffer.flip()
        return buffer
    }
}

object CubeLutParser {

    private val cache = ConcurrentHashMap<String, Lut3DData>()

    fun loadFromAssets(context: Context, assetPath: String): Lut3DData {
        cache[assetPath]?.let { return it }
        val parsed = runCatching {
            context.assets.open(assetPath).use { stream ->
                BufferedReader(InputStreamReader(stream), 32768).use { reader ->
                    parseCube(reader)
                }
            }
        }.getOrElse {
            createIdentityLut(size = 17)
        }
        cache.putIfAbsent(assetPath, parsed)
        return parsed
    }

    fun parseCube(reader: BufferedReader): Lut3DData {
        var size = 0
        var values: FloatArray? = null
        var valueCount = 0
        val fallbackList = ArrayList<Float>(17 * 17 * 17 * 3)

        var rawLine = reader.readLine()
        while (rawLine != null) {
            val line = rawLine.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) {
                when {
                    line.startsWith("TITLE") || line.startsWith("DOMAIN_") -> Unit
                    line.startsWith("LUT_3D_SIZE") -> {
                        val lastSpace = line.lastIndexOf(' ')
                        val parsedSize = if (lastSpace >= 0) line.substring(lastSpace + 1).trim().toIntOrNull() else null
                        if (parsedSize != null && parsedSize > 1) {
                            size = parsedSize
                            values = FloatArray(size * size * size * 3)
                        }
                    }
                    else -> {
                        val target = values
                        if (target != null && valueCount + 3 <= target.size) {
                            if (parseThreeFloats(line, target, valueCount)) {
                                valueCount += 3
                            }
                        } else {
                            val tmp = FloatArray(3)
                            if (parseThreeFloats(line, tmp, 0)) {
                                fallbackList.add(tmp[0])
                                fallbackList.add(tmp[1])
                                fallbackList.add(tmp[2])
                            }
                        }
                    }
                }
            }
            rawLine = reader.readLine()
        }

        val finalFloats = values ?: fallbackList.toFloatArray()
        val finalCount = if (values != null) valueCount else fallbackList.size
        require(size > 1 && finalCount == size * size * size * 3) {
            "Invalid .cube LUT: size=$size, floats=$finalCount"
        }
        return Lut3DData(size = size, rgbFloats = finalFloats)
    }

    private fun parseThreeFloats(line: String, dest: FloatArray, offset: Int): Boolean {
        val len = line.length
        var i = 0
        for (c in 0 until 3) {
            while (i < len && line[i] <= ' ') i++
            if (i >= len) return false
            val start = i
            while (i < len && line[i] > ' ') i++
            val parsed = line.substring(start, i).toFloatOrNull() ?: return false
            dest[offset + c] = parsed
        }
        return true
    }

    fun createIdentityLut(size: Int = 17): Lut3DData {
        val data = FloatArray(size * size * size * 3)
        var idx = 0
        val max = (size - 1).toFloat()
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    data[idx++] = r / max
                    data[idx++] = g / max
                    data[idx++] = b / max
                }
            }
        }
        return Lut3DData(size = size, rgbFloats = data)
    }
}
