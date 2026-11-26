package com.studio6d.core.camera2

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import kotlin.math.abs

data class DslrCameraCapabilities(
    val cameraId: String,
    val sensorOrientation: Int,
    val activeArraySize: Rect?,
    val supportsManualSensor: Boolean,
    val supportsRawDng: Boolean,
    val supportsOis: Boolean,
    val supportsContinuousPictureAf: Boolean,
    val hasFlash: Boolean,
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val isoRange: Range<Int>,
    val exposureTimeRangeNs: Range<Long>,
    val minFocusDistance: Float,
    val evStep: Float,
    val evRangeSteps: Range<Int>,
    val previewSize: Size,
    val jpegSize: Size,
    val rawSize: Size?
)

object CameraCapabilitiesInspector {

    private const val MAX_CANON_6D_PIXELS = 21_000_000L

    fun inspectBackCamera(cameraManager: CameraManager): DslrCameraCapabilities? {
        val backId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return null

        val chars = cameraManager.getCameraCharacteristics(backId)
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val activeArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val supportsManual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val supportsRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val supportsOis = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val supportsContinuousPictureAf = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        val (minZoom, maxZoom) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val lo = (zRange?.lower ?: 1.0f).coerceAtLeast(0.5f)
            val hi = (zRange?.upper ?: 4.0f).coerceAtLeast(1.0f)
            lo to hi
        } else {
            val maxDig = (chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4.0f)
                .coerceAtLeast(1.0f)
            1.0f to maxDig
        }

        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            ?: Range(100, 3200)
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(100_000L, 1_000_000_000L)
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
        val evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f)
        val evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(-6, 6)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty()
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val rawSizes = if (supportsRaw) map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty() else emptyList()

        // Always pick a native 4:3 aligned preview stream so HAL stays on Photo ISP (prevents EIS tilt wedges)
        val previewSize = pickPreferredSize(previewSizes, targetRatio = 4f / 3f, maxWidth = 1920)
            ?: Size(1440, 1080)

        val jpegSize = jpegSizes
            .filter {
                val px = it.width.toLong() * it.height
                val ratio = it.width.toFloat() / it.height.toFloat().coerceAtLeast(1f)
                px <= MAX_CANON_6D_PIXELS && abs(ratio - 4f / 3f) < 0.08f
            }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: jpegSizes
                .filter { it.width.toLong() * it.height <= MAX_CANON_6D_PIXELS }
                .maxByOrNull { it.width.toLong() * it.height }
            ?: jpegSizes.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(4000, 3000)

        val rawSize = rawSizes.maxByOrNull { it.width.toLong() * it.height }

        return DslrCameraCapabilities(
            cameraId = backId,
            sensorOrientation = sensorOrientation,
            activeArraySize = activeArraySize,
            supportsManualSensor = supportsManual,
            supportsRawDng = supportsRaw,
            supportsOis = supportsOis,
            supportsContinuousPictureAf = supportsContinuousPictureAf,
            hasFlash = hasFlash,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            isoRange = isoRange,
            exposureTimeRangeNs = expRange,
            minFocusDistance = minFocus,
            evStep = evStep,
            evRangeSteps = evRange,
            previewSize = previewSize,
            jpegSize = jpegSize,
            rawSize = rawSize
        )
    }

    private fun pickPreferredSize(sizes: List<Size>, targetRatio: Float, maxWidth: Int): Size? {
        val aligned = sizes.filter {
            it.width in 960..maxWidth &&
                it.width % 32 == 0 &&
                it.height % 16 == 0
        }
        val pool = aligned.ifEmpty { sizes.filter { it.width in 960..maxWidth } }.ifEmpty { sizes }
        return pool.minByOrNull { size ->
            val ratio = size.width.toFloat() / size.height.toFloat().coerceAtLeast(1f)
            abs(ratio - targetRatio) * 12f - (size.width / 1000f)
        }
    }
}
