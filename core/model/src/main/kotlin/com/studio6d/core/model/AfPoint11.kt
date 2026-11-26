package com.studio6d.core.model

import kotlin.math.hypot

/**
 * Canon EOS 6D 11-point diamond AF array (1 - 3 - 3 - 3 - 1).
 * Index 5 is the high-sensitivity center cross-type AF point.
 */
data class AfPoint11(
    val index: Int,
    val normX: Float,
    val normY: Float,
    val isCrossType: Boolean = false,
    val label: String = ""
)

object AfPoints6D {
    val points: List<AfPoint11> = listOf(
        AfPoint11(index = 0, normX = 0.22f, normY = 0.50f, label = "Far Left"),
        AfPoint11(index = 1, normX = 0.36f, normY = 0.34f, label = "Upper Left"),
        AfPoint11(index = 2, normX = 0.36f, normY = 0.50f, label = "Mid Left"),
        AfPoint11(index = 3, normX = 0.36f, normY = 0.66f, label = "Lower Left"),
        AfPoint11(index = 4, normX = 0.50f, normY = 0.24f, label = "Top Center"),
        AfPoint11(index = 5, normX = 0.50f, normY = 0.50f, isCrossType = true, label = "Center Cross"),
        AfPoint11(index = 6, normX = 0.50f, normY = 0.76f, label = "Bottom Center"),
        AfPoint11(index = 7, normX = 0.64f, normY = 0.34f, label = "Upper Right"),
        AfPoint11(index = 8, normX = 0.64f, normY = 0.50f, label = "Mid Right"),
        AfPoint11(index = 9, normX = 0.64f, normY = 0.66f, label = "Lower Right"),
        AfPoint11(index = 10, normX = 0.78f, normY = 0.50f, label = "Far Right")
    )

    fun getPoint(index: Int): AfPoint11 = points.getOrElse(index) { points[5] }

    fun nearestPointIndex(normX: Float, normY: Float): Int {
        return points.minByOrNull { hypot(it.normX - normX, it.normY - normY) }?.index ?: 5
    }
}
