package com.studio6d.core.camera2

import android.content.Context
import android.graphics.Bitmap
import com.studio6d.core.lut.OpticalSubjectSegmenter
import com.studio6d.core.lut.SubjectMaskData

/**
 * Delegates still-frame foreground subject segmentation to the shared 3-tier
 * [OpticalSubjectSegmenter] (ML Kit Subject Segmentation -> bundled Selfie Segmentation ->
 * Edge-Guided Dual-Geodesic Barrier + Joint Bilateral contour refinement).
 */
class MlSubjectSegmenter(context: Context) {

    private val segmenter = OpticalSubjectSegmenter.getInstance(context.applicationContext)

    suspend fun buildCaptureSubjectMask(
        sourceBitmap: Bitmap,
        exifDegrees: Int,
        focusNormX: Float,
        focusNormY: Float,
        lensDiopters: Float,
        @Suppress("UNUSED_PARAMETER") isFocusLocked: Boolean,
        isAutoMultiPointAf: Boolean = true
    ): SubjectMaskData {
        return segmenter.segmentStillCapture(
            sourceBitmap = sourceBitmap,
            exifDegrees = exifDegrees,
            focusNormX = focusNormX,
            focusNormY = focusNormY,
            lensDiopters = lensDiopters,
            isAutoMultiPointAf = isAutoMultiPointAf
        )
    }

    fun close() {
        // Shared singleton lifecycle managed across Live View & still processing
    }
}
