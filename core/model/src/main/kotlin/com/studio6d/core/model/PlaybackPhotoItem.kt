package com.studio6d.core.model

import android.graphics.Bitmap

/**
 * Represents a captured photo slot in the Canon 6D RAM Buffer / SD Card Playback queue.
 * Pending captures remain immediately viewable via [previewBitmap] while GPU bokeh and
 * MediaStore saving finish in the background.
 */
data class PlaybackPhotoItem(
    val id: String,
    val uri: String? = null,
    val previewBitmap: Bitmap? = null,
    val isPending: Boolean = false,
    val statusLabel: String? = null
)
