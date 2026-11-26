package com.studio6d.core.camera2

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import com.studio6d.core.lut.GpuStillLutProcessor
import com.studio6d.core.model.AfMode
import com.studio6d.core.model.CameraSettingsState
import com.studio6d.core.model.FlashMode
import com.studio6d.core.model.MeteringMode
import com.studio6d.core.model.PlaybackPhotoItem
import com.studio6d.core.model.WhiteBalancePreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class LiveCameraTelemetry(
    val shutterNs: Long = 4_000_000L,
    val iso: Int = 400,
    val evOffset: Float = 0f
)

/**
 * Production Camera2 manual engine for Digital Mango 6D:
 * - Zero-warp native 4:3 Photo ISP preview stream (EIS OFF) + hardware zoom ratio / crop region
 * - Continuous DSLR AF hunting (`PASSIVE_SCAN` / `ACTIVE_SCAN`) + Focus-Priority shutter gate
 * - ML Kit Subject Segmentation + foreground-occluded optical disc bokeh
 * - AE Lock (*), Flash/Torch modes, AEB EV bracketing, full DSLR EXIF metadata
 */
class Camera2DslrController(
    private val context: Context
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subjectSegmenter = MlSubjectSegmenter(context)
    private val stillProcessMutex = Mutex()

    private var handlerThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var previewSurfaceTexture: SurfaceTexture? = null

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var sessionWithRaw: Boolean = false

    private var latestRawImage: Image? = null
    private var latestCaptureResult: TotalCaptureResult? = null
    private var activeSettings: CameraSettingsState = CameraSettingsState()
    private var characteristics: CameraCharacteristics? = null

    @Volatile
    var displayRotation: Int = Surface.ROTATION_90

    private var lastTelemetryMs: Long = 0L
    @Volatile
    private var awaitingAfLock: Boolean = false
    @Volatile
    private var sawActiveScan: Boolean = false
    @Volatile
    private var afRetryCount: Int = 0
    private var afLockTimeoutRunnable: Runnable? = null
    private var afResweepRunnable: Runnable? = null
    @Volatile
    private var afTriggerStartMs: Long = 0L
    @Volatile
    private var lastLensMoving: Boolean = false

    @Volatile
    private var userAfActive: Boolean = false

    @Volatile
    private var pendingFocusDeferred: CompletableDeferred<Boolean>? = null

    private data class PendingCaptureMeta(
        val captureId: String,
        val settings: CameraSettingsState,
        val evBiasOverride: Float?,
        val wasFocused: Boolean,
        val diopters: Float,
        val flashFired: Boolean,
        val captureTimestampMs: Long
    )

    private val pendingCaptureQueue = ConcurrentLinkedQueue<PendingCaptureMeta>()

    private val _capabilities = MutableStateFlow<DslrCameraCapabilities?>(null)
    val capabilities: StateFlow<DslrCameraCapabilities?> = _capabilities.asStateFlow()

    private val _lastSavedEvent = MutableStateFlow<String?>(null)
    val lastSavedEvent: StateFlow<String?> = _lastSavedEvent.asStateFlow()

    private val _afLocked = MutableStateFlow(false)
    val afLocked: StateFlow<Boolean> = _afLocked.asStateFlow()

    private val _isAfHunting = MutableStateFlow(false)
    val isAfHunting: StateFlow<Boolean> = _isAfHunting.asStateFlow()

    private val _isSubjectFocused = MutableStateFlow(false)
    val isSubjectFocused: StateFlow<Boolean> = _isSubjectFocused.asStateFlow()

    private val _isPassiveFocused = MutableStateFlow(false)
    val isPassiveFocused: StateFlow<Boolean> = _isPassiveFocused.asStateFlow()

    private val _lensFocusDiopters = MutableStateFlow(0f)
    val lensFocusDiopters: StateFlow<Float> = _lensFocusDiopters.asStateFlow()

    private val _afPulseSeq = MutableStateFlow(0)
    val afPulseSeq: StateFlow<Int> = _afPulseSeq.asStateFlow()

    private val _afLockConfirmedSeq = MutableStateFlow(0)
    val afLockConfirmedSeq: StateFlow<Int> = _afLockConfirmedSeq.asStateFlow()

    private val _afLockFailedSeq = MutableStateFlow(0)
    val afLockFailedSeq: StateFlow<Int> = _afLockFailedSeq.asStateFlow()

    private val _liveTelemetry = MutableStateFlow(LiveCameraTelemetry())
    val liveTelemetry: StateFlow<LiveCameraTelemetry> = _liveTelemetry.asStateFlow()

    private val _capturedUris = MutableStateFlow<List<String>>(emptyList())
    val capturedUris: StateFlow<List<String>> = _capturedUris.asStateFlow()

    private val _playbackItems = MutableStateFlow<List<PlaybackPhotoItem>>(emptyList())
    val playbackItems: StateFlow<List<PlaybackPhotoItem>> = _playbackItems.asStateFlow()

    private val _pendingSaveCount = MutableStateFlow(0)
    val pendingSaveCount: StateFlow<Int> = _pendingSaveCount.asStateFlow()

    init {
        loadExistingPhotos()
    }

    private fun updatePendingCountFromItems(items: List<PlaybackPhotoItem>) {
        _pendingSaveCount.value = items.count { it.isPending }
    }

    private fun addPendingBufferItem(captureId: String) {
        _playbackItems.update { current ->
            val updated = if (current.any { it.id == captureId }) {
                current
            } else {
                listOf(PlaybackPhotoItem(id = captureId, isPending = true, statusLabel = "BUFFERING...")) + current
            }
            updatePendingCountFromItems(updated)
            updated
        }
    }

    private fun updatePendingBufferPreview(captureId: String, preview: Bitmap, statusLabel: String) {
        _playbackItems.update { current ->
            val updated = current.map { item ->
                if (item.id == captureId) {
                    item.copy(previewBitmap = preview, statusLabel = statusLabel)
                } else {
                    item
                }
            }
            updatePendingCountFromItems(updated)
            updated
        }
    }

    private fun finalizePendingBufferItem(captureId: String, savedUri: String, preview: Bitmap?) {
        _playbackItems.update { current ->
            val newItem = PlaybackPhotoItem(
                id = savedUri,
                uri = savedUri,
                previewBitmap = preview,
                isPending = false,
                statusLabel = null
            )
            val withoutOld = current.filterNot { it.id == captureId || it.id == savedUri }
            val pending = withoutOld.filter { it.isPending }
            val existingSaved = withoutOld.filterNot { it.isPending }
            val mergedSaved = (listOf(newItem) + existingSaved).mapIndexed { index, item ->
                if (index >= 3 && item.previewBitmap != null) item.copy(previewBitmap = null) else item
            }
            val updated = pending + mergedSaved
            _capturedUris.value = mergedSaved.mapNotNull { it.uri }
            updatePendingCountFromItems(updated)
            updated
        }
    }

    private fun removePendingBufferItem(captureId: String) {
        _playbackItems.update { current ->
            val updated = current.filterNot { it.id == captureId && it.isPending }
            updatePendingCountFromItems(updated)
            updated
        }
    }

    private fun decodeFastRamPreview(jpegBytes: ByteArray, exifDegrees: Int): Bitmap? {
        return runCatching {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 6
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val raw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts) ?: return null
            if (exifDegrees == 0) return raw
            val m = Matrix().apply { postRotate(exifDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (rotated !== raw) raw.recycle()
            rotated
        }.getOrNull()
    }

    private fun buildSmallGradedPreview(graded: Bitmap): Bitmap? {
        return runCatching {
            val w = graded.width
            val h = graded.height
            if (w <= 0 || h <= 0) return null
            val maxDim = max(w, h)
            if (maxDim <= 960) {
                return graded.copy(Bitmap.Config.RGB_565, false)
            }
            val scale = 960f / maxDim.toFloat()
            val tw = (w * scale).roundToInt().coerceAtLeast(1)
            val th = (h * scale).roundToInt().coerceAtLeast(1)
            if (tw == w && th == h) {
                return graded.copy(Bitmap.Config.RGB_565, false)
            }
            val scaled = Bitmap.createScaledBitmap(graded, tw, th, true)
            if (scaled === graded) {
                graded.copy(Bitmap.Config.RGB_565, false)
            } else {
                scaled
            }
        }.getOrNull()
    }

    fun loadExistingPhotos() {
        scope.launch {
            val found = mutableListOf<String>()
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("DM6D_%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext() && found.size < 60) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        found.add(uri.toString())
                    }
                }
            }
            if (found.isNotEmpty()) {
                _capturedUris.value = found
                _playbackItems.update { current ->
                    val pending = current.filter { it.isPending }
                    val existingByUri = current.filterNot { it.isPending }.associateBy { it.uri }
                    val saved = found.mapIndexed { index, uriStr ->
                        val prev = existingByUri[uriStr]
                        PlaybackPhotoItem(
                            id = uriStr,
                            uri = uriStr,
                            previewBitmap = if (index < 3) prev?.previewBitmap else null,
                            isPending = false,
                            statusLabel = null
                        )
                    }
                    val merged = pending + saved
                    updatePendingCountFromItems(merged)
                    merged
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(surfaceTexture: SurfaceTexture, initialSettings: CameraSettingsState) {
        ensureBackgroundThread()
        val handler = cameraHandler ?: return
        handler.post {
            if (cameraDevice != null && previewSurfaceTexture === surfaceTexture && previewSurface?.isValid == true) {
                activeSettings = initialSettings
                applyPreviewLocked(initialSettings)
                return@post
            }
            closeCameraInternal()
            activeSettings = initialSettings
            previewSurfaceTexture = surfaceTexture

            val caps = CameraCapabilitiesInspector.inspectBackCamera(cameraManager) ?: return@post
            _capabilities.value = caps
            characteristics = runCatching { cameraManager.getCameraCharacteristics(caps.cameraId) }.getOrNull()

            runCatching {
                surfaceTexture.setDefaultBufferSize(caps.previewSize.width, caps.previewSize.height)
                previewSurface = Surface(surfaceTexture)
            }.onFailure {
                return@post
            }

            setupImageReaders(caps, includeRaw = initialSettings.saveRawDng)

            runCatching {
                cameraManager.openCamera(
                    caps.cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            createCaptureSessionInternal()
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            runCatching { camera.close() }
                            if (cameraDevice === camera) cameraDevice = null
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            runCatching { camera.close() }
                            if (cameraDevice === camera) cameraDevice = null
                        }
                    },
                    handler
                )
            }
        }
    }

    private fun setupImageReaders(caps: DslrCameraCapabilities, includeRaw: Boolean) {
        runCatching { jpegReader?.close() }
        runCatching { rawReader?.close() }
        rawReader = null

        jpegReader = ImageReader.newInstance(
            caps.jpegSize.width,
            caps.jpegSize.height,
            ImageFormat.JPEG,
            3
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                val bytes: ByteArray
                try {
                    val buffer = image.planes[0].buffer
                    bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                } finally {
                    runCatching { image.close() }
                }
                val polled = pendingCaptureQueue.poll()
                val meta = if (polled != null) {
                    polled
                } else {
                    val fallbackId = "pending_${System.nanoTime()}"
                    addPendingBufferItem(fallbackId)
                    PendingCaptureMeta(
                        captureId = fallbackId,
                        settings = activeSettings,
                        evBiasOverride = null,
                        wasFocused = _isSubjectFocused.value || _afLocked.value,
                        diopters = _lensFocusDiopters.value,
                        flashFired = shouldFireStillFlash(activeSettings) || activeSettings.flashMode == FlashMode.TORCH,
                        captureTimestampMs = System.currentTimeMillis()
                    )
                }
                scope.launch(Dispatchers.Default) {
                    val exifDegrees = readExifDegrees(bytes)
                    decodeFastRamPreview(bytes, exifDegrees)?.let { fastPreview ->
                        val label = if (meta.settings.opticalBokehEnabled && meta.settings.opticalBokehStrength > 0.01f) {
                            "PROCESSING BOKEH..."
                        } else {
                            "PROCESSING..."
                        }
                        updatePendingBufferPreview(meta.captureId, fastPreview, label)
                    }
                    processAndSaveJpegBytes(bytes, meta, exifDegrees)
                }
            }, cameraHandler)
        }

        val rawSize = if (includeRaw && caps.supportsRawDng) caps.rawSize else null
        sessionWithRaw = rawSize != null
        if (rawSize != null) {
            rawReader = ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                ImageFormat.RAW_SENSOR,
                4
            ).apply {
                setOnImageAvailableListener({ reader ->
                    val img = runCatching { reader.acquireLatestImage() }.getOrNull()
                        ?: return@setOnImageAvailableListener
                    runCatching { latestRawImage?.close() }
                    latestRawImage = img
                    trySavePendingDng()
                }, cameraHandler)
            }
        }
    }

    private fun createCaptureSessionInternal() {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        if (!preview.isValid) return
        val jpegSurface = jpegReader?.surface ?: return
        if (!jpegSurface.isValid) return

        val outputs = buildList {
            add(preview)
            add(jpegSurface)
            if (sessionWithRaw) {
                rawReader?.surface?.takeIf { it.isValid }?.let { add(it) }
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== device || previewSurface?.isValid != true) {
                            runCatching { session.close() }
                            return
                        }
                        captureSession = session
                        applyPreviewLocked(activeSettings)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        _lastSavedEvent.value = "Camera2 session configuration failed"
                    }
                },
                cameraHandler
            )
        }
    }

    fun updatePreview(settings: CameraSettingsState) {
        activeSettings = settings
        cameraHandler?.post {
            applyPreviewLocked(settings)
        }
    }

    private fun applyPreviewLocked(settings: CameraSettingsState) {
        val device = cameraDevice ?: return
        val caps = _capabilities.value

        val needsRaw = caps != null && settings.saveRawDng && caps.supportsRawDng && caps.rawSize != null
        val sessionNeedsRebuild = needsRaw != sessionWithRaw
        if (awaitingAfLock && !sessionNeedsRebuild) {
            return
        }

        if (sessionNeedsRebuild) {
            runCatching { captureSession?.close() }
            captureSession = null
            setupImageReaders(caps!!, includeRaw = settings.saveRawDng)
            createCaptureSessionInternal()
            return
        }

        val session = captureSession ?: return
        val preview = previewSurface ?: return
        if (!preview.isValid) return

        runCatching {
            val afRect = buildMeteringRect(settings.customFocusNormX, settings.customFocusNormY, settings.zoomRatio, halfExtentFrac = 0.075f)
            val aeRect = buildAeMeteringRect(settings)
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview)
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                applyDslrControls(this, settings, afRect = afRect, aeRect = aeRect, isStillCapture = false, forceActiveAfMode = false)
            }
            session.setRepeatingRequest(builder.build(), previewCallback, cameraHandler)
        }
    }

    /**
     * Focus-Priority DSLR Shutter Gate:
     * If the lens hasn't locked onto a subject yet (or is still hunting), actively hunts & locks AF
     * before allowing the shutter to release.
     */
    suspend fun acquireFocusForShutter(settings: CameraSettingsState): Boolean {
        if (settings.afMode == AfMode.MANUAL) {
            userAfActive = false
            _afLocked.value = true
            _isSubjectFocused.value = true
            _isAfHunting.value = false
            return true
        }
        if (_afLocked.value && _isSubjectFocused.value && !_isAfHunting.value) {
            userAfActive = false
            _afPulseSeq.value += 1
            return true
        }

        val deferred = CompletableDeferred<Boolean>()
        pendingFocusDeferred?.complete(false)
        pendingFocusDeferred = deferred
        userAfActive = true
        _isAfHunting.value = true

        focusAtPoint(settings.customFocusNormX, settings.customFocusNormY, settings)

        val result = withTimeoutOrNull(1600L) {
            deferred.await()
        } ?: _isSubjectFocused.value

        if (pendingFocusDeferred === deferred) {
            pendingFocusDeferred = null
        }
        userAfActive = false
        _isAfHunting.value = false
        return result
    }

    fun focusAtPoint(normX: Float, normY: Float, settings: CameraSettingsState) {
        activeSettings = settings
        if (settings.afMode == AfMode.MANUAL) {
            userAfActive = false
            awaitingAfLock = false
            sawActiveScan = false
            _afLocked.value = true
            _isSubjectFocused.value = true
            _isPassiveFocused.value = true
            _isAfHunting.value = false
            _afPulseSeq.value += 1
            _afLockConfirmedSeq.value += 1
            pendingFocusDeferred?.complete(true)
            return
        }

        userAfActive = true
        awaitingAfLock = true
        sawActiveScan = false
        afRetryCount = 0
        afTriggerStartMs = System.currentTimeMillis()
        _afLocked.value = false
        _isSubjectFocused.value = false
        _isAfHunting.value = true

        cameraHandler?.post {
            afResweepRunnable?.let { cameraHandler?.removeCallbacks(it) }
            afResweepRunnable = null
            val device = cameraDevice
            val session = captureSession
            val preview = previewSurface
            if (device == null || session == null || preview == null || !preview.isValid) {
                userAfActive = false
                awaitingAfLock = false
                _afLocked.value = true
                _isSubjectFocused.value = true
                _isPassiveFocused.value = true
                _isAfHunting.value = false
                _afPulseSeq.value += 1
                _afLockConfirmedSeq.value += 1
                pendingFocusDeferred?.complete(true)
                return@post
            }

            val afRect = buildMeteringRect(normX, normY, settings.zoomRatio, halfExtentFrac = 0.075f)
            val aeRect = buildAeMeteringRect(settings.copy(customFocusNormX = normX, customFocusNormY = normY))
            scheduleAfFallbackPulse()

            runCatching {
                val forceAuto = settings.afMode == AfMode.ONE_SHOT || _capabilities.value?.supportsContinuousPictureAf != true
                val cancelBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                    applyDslrControls(this, settings, afRect = afRect, aeRect = aeRect, isStillCapture = false, forceActiveAfMode = forceAuto)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }
                session.capture(cancelBuilder.build(), previewCallback, cameraHandler)

                val repeatingBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                    applyDslrControls(this, settings, afRect = afRect, aeRect = aeRect, isStillCapture = false, forceActiveAfMode = forceAuto)
                }
                session.setRepeatingRequest(repeatingBuilder.build(), previewCallback, cameraHandler)

                val triggerBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                    applyDslrControls(this, settings, afRect = afRect, aeRect = aeRect, isStillCapture = false, forceActiveAfMode = forceAuto)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                session.capture(triggerBuilder.build(), previewCallback, cameraHandler)
            }.onFailure {
                userAfActive = false
                awaitingAfLock = false
                cancelAfFallbackPulse()
                _isAfHunting.value = false
                _afLocked.value = true
                _isSubjectFocused.value = true
                _afPulseSeq.value += 1
                _afLockConfirmedSeq.value += 1
                pendingFocusDeferred?.complete(true)
            }
        }
    }

    fun triggerHalfPressAfAe(settings: CameraSettingsState = activeSettings) {
        focusAtPoint(settings.customFocusNormX, settings.customFocusNormY, settings)
    }

    fun releaseHalfPress() {
        userAfActive = false
        _isAfHunting.value = false
        cameraHandler?.post {
            awaitingAfLock = false
            sawActiveScan = false
            cancelAfFallbackPulse()
            _afLocked.value = false
            _isSubjectFocused.value = false
            pendingFocusDeferred?.complete(false)
            pendingFocusDeferred = null
            val device = cameraDevice ?: return@post
            val session = captureSession ?: return@post
            val preview = previewSurface ?: return@post
            if (!preview.isValid) return@post

            runCatching {
                val afRect = buildMeteringRect(activeSettings.customFocusNormX, activeSettings.customFocusNormY, activeSettings.zoomRatio, halfExtentFrac = 0.075f)
                val aeRect = buildAeMeteringRect(activeSettings)
                val cancelBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                    applyDslrControls(this, activeSettings, afRect = afRect, aeRect = aeRect, isStillCapture = false, forceActiveAfMode = false)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }
                session.capture(cancelBuilder.build(), previewCallback, cameraHandler)
            }
            applyPreviewLocked(activeSettings)
        }
    }

    private fun shouldFireStillFlash(settings: CameraSettingsState): Boolean {
        if (_capabilities.value?.hasFlash != true) return false
        return when (settings.flashMode) {
            FlashMode.ON -> true
            FlashMode.AUTO -> _liveTelemetry.value.iso >= 700 || _liveTelemetry.value.evOffset < -0.4f
            FlashMode.TORCH, FlashMode.OFF -> false
        }
    }

    fun capturePhoto(
        settings: CameraSettingsState,
        evBiasOverride: Float? = null,
        focusConfirmed: Boolean = true,
        unlockAfAfterCapture: Boolean = false
    ) {
        activeSettings = settings
        userAfActive = false
        _isAfHunting.value = false
        val fireFlash = shouldFireStillFlash(settings)
        val torchPrewarm = fireFlash && settings.flashMode != FlashMode.TORCH
        val captureId = "pending_${System.nanoTime()}"
        val meta = PendingCaptureMeta(
            captureId = captureId,
            settings = settings,
            evBiasOverride = evBiasOverride,
            wasFocused = focusConfirmed || _isSubjectFocused.value || _afLocked.value || settings.afMode == AfMode.MANUAL,
            diopters = _lensFocusDiopters.value,
            flashFired = fireFlash || settings.flashMode == FlashMode.TORCH,
            captureTimestampMs = System.currentTimeMillis()
        )
        addPendingBufferItem(captureId)
        pendingCaptureQueue.offer(meta)

        cameraHandler?.post {
            val device = cameraDevice ?: run {
                pendingCaptureQueue.remove(meta)
                removePendingBufferItem(meta.captureId)
                return@post
            }
            val session = captureSession ?: run {
                pendingCaptureQueue.remove(meta)
                removePendingBufferItem(meta.captureId)
                return@post
            }
            val jpegSurface = jpegReader?.surface ?: run {
                pendingCaptureQueue.remove(meta)
                removePendingBufferItem(meta.captureId)
                return@post
            }
            if (!jpegSurface.isValid) {
                pendingCaptureQueue.remove(meta)
                removePendingBufferItem(meta.captureId)
                return@post
            }
            val caps = _capabilities.value
            val afRect = buildMeteringRect(settings.customFocusNormX, settings.customFocusNormY, settings.zoomRatio, halfExtentFrac = 0.075f)
            val aeRect = buildAeMeteringRect(settings)
            var captureSettled = false
            val safetyPreviewRestore = Runnable {
                if (!captureSettled) {
                    applyPreviewLocked(activeSettings)
                }
            }

            val submitStillCapture = Runnable {
                runCatching {
                    val activeDev = cameraDevice
                    val activeSess = captureSession
                    if (activeDev == null || activeSess == null) {
                        pendingCaptureQueue.remove(meta)
                        removePendingBufferItem(meta.captureId)
                        return@Runnable
                    }
                    val builder = activeDev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        // Keep preview stream fed during still ISP processing so Live View never blacks out
                        previewSurface?.takeIf { it.isValid }?.let { addTarget(it) }
                        addTarget(jpegSurface)
                        if (settings.saveRawDng && sessionWithRaw) {
                            rawReader?.surface?.takeIf { it.isValid }?.let { addTarget(it) }
                        }
                        set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
                        applyDslrControls(
                            builder = this,
                            settings = settings,
                            afRect = afRect,
                            aeRect = aeRect,
                            isStillCapture = true,
                            evBiasOverride = evBiasOverride,
                            forceActiveAfMode = caps?.supportsContinuousPictureAf != true && _afLocked.value,
                            forceFlashTorch = fireFlash
                        )
                        set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                        if (caps != null) {
                            set(CaptureRequest.JPEG_ORIENTATION, computeJpegOrientation(caps.sensorOrientation))
                        }
                        if (settings.saveRawDng) {
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
                        } else {
                            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                        }
                    }

                    cameraHandler?.postDelayed(safetyPreviewRestore, 650L)
                    activeSess.capture(
                        builder.build(),
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                captureSettled = true
                                cameraHandler?.removeCallbacks(safetyPreviewRestore)
                                latestCaptureResult = result
                                if (settings.saveRawDng && sessionWithRaw) {
                                    trySavePendingDng()
                                }
                                if (unlockAfAfterCapture) {
                                    awaitingAfLock = false
                                    sawActiveScan = false
                                    _afLocked.value = false
                                    _isSubjectFocused.value = false
                                    runCatching {
                                        previewSurface?.takeIf { it.isValid }?.let { prev ->
                                            val cancelReq = activeDev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                addTarget(prev)
                                                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                                                applyDslrControls(this, activeSettings, afRect = afRect, aeRect = aeRect, isStillCapture = false, forceActiveAfMode = false)
                                                set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                                            }
                                            session.capture(cancelReq.build(), previewCallback, cameraHandler)
                                        }
                                    }
                                }
                                applyPreviewLocked(activeSettings)
                            }

                            override fun onCaptureFailed(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                failure: CaptureFailure
                            ) {
                                captureSettled = true
                                cameraHandler?.removeCallbacks(safetyPreviewRestore)
                                if (!failure.wasImageCaptured()) {
                                    pendingCaptureQueue.remove(meta)
                                    removePendingBufferItem(meta.captureId)
                                }
                                _lastSavedEvent.value = "Capture failed: ${failure.reason}"
                                if (unlockAfAfterCapture) {
                                    awaitingAfLock = false
                                    sawActiveScan = false
                                    _afLocked.value = false
                                    _isSubjectFocused.value = false
                                }
                                applyPreviewLocked(activeSettings)
                            }
                        },
                        cameraHandler
                    )
                }.onFailure {
                    captureSettled = true
                    cameraHandler?.removeCallbacks(safetyPreviewRestore)
                    pendingCaptureQueue.remove(meta)
                    removePendingBufferItem(meta.captureId)
                    applyPreviewLocked(activeSettings)
                }
            }

            if (torchPrewarm) {
                // Pre-warm CMOS flash synchronization for 95ms so high manual shutter speeds never get partial dark bands
                runCatching {
                    val prewarmBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        previewSurface?.takeIf { it.isValid }?.let { addTarget(it) }
                        set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                        applyDslrControls(
                            builder = this,
                            settings = settings,
                            afRect = afRect,
                            aeRect = aeRect,
                            isStillCapture = false,
                            forceActiveAfMode = _afLocked.value,
                            forceFlashTorch = true
                        )
                    }
                    session.setRepeatingRequest(prewarmBuilder.build(), previewCallback, cameraHandler)
                }
                cameraHandler?.postDelayed(submitStillCapture, 95L)
            } else {
                submitStillCapture.run()
            }
        }
    }

    private fun applyDslrControls(
        builder: CaptureRequest.Builder,
        settings: CameraSettingsState,
        afRect: MeteringRectangle? = null,
        aeRect: MeteringRectangle? = null,
        isStillCapture: Boolean = false,
        evBiasOverride: Float? = null,
        forceActiveAfMode: Boolean = false,
        forceFlashTorch: Boolean = false
    ) {
        val caps = _capabilities.value

        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (caps?.supportsOis == true) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        applyZoomControl(builder, settings.zoomRatio, caps)

        val useManualSensor = caps?.supportsManualSensor == true && settings.exposureMode.allowsManualShutter
        if (useManualSensor) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

            val biasScale = if (evBiasOverride != null) 2.0.pow(-evBiasOverride.toDouble()) else 1.0
            var targetShutter = (settings.shutterSpeedNs * biasScale).toLong()
            if (caps!!.hasFlash && (settings.flashMode == FlashMode.ON || settings.flashMode == FlashMode.AUTO)) {
                // Canon EOS 6D Flash X-Sync ceiling: 1/180s (5,555,556 ns)
                targetShutter = maxOf(targetShutter, 5_555_556L)
            }
            val clampedShutter = targetShutter.coerceIn(
                caps.exposureTimeRangeNs.lower,
                caps.exposureTimeRangeNs.upper
            )
            val targetIso = if (settings.iso <= 0) 400 else settings.iso
            val clampedIso = targetIso.coerceIn(caps.isoRange.lower, caps.isoRange.upper)

            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampedShutter)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, clampedIso)

            if (caps.hasFlash) {
                val flashMode = when {
                    settings.flashMode == FlashMode.TORCH || forceFlashTorch -> CameraMetadata.FLASH_MODE_TORCH
                    isStillCapture && shouldFireStillFlash(settings) -> CameraMetadata.FLASH_MODE_SINGLE
                    else -> CameraMetadata.FLASH_MODE_OFF
                }
                builder.set(CaptureRequest.FLASH_MODE, flashMode)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            val aeMode = if (caps?.hasFlash == true) {
                if (forceFlashTorch) {
                    CameraMetadata.CONTROL_AE_MODE_ON
                } else {
                    when (settings.flashMode) {
                        FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                        FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                        FlashMode.TORCH, FlashMode.OFF -> CameraMetadata.CONTROL_AE_MODE_ON
                    }
                }
            } else {
                CameraMetadata.CONTROL_AE_MODE_ON
            }
            builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            if (caps?.hasFlash == true && (settings.flashMode == FlashMode.TORCH || forceFlashTorch)) {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
            builder.set(CaptureRequest.CONTROL_AE_LOCK, settings.isAeLocked)

            if (caps != null && (settings.exposureMode.allowsExposureCompensation || evBiasOverride != null)) {
                val baseEv = if (settings.exposureMode.allowsExposureCompensation) settings.evCompensation else 0f
                val totalEv = baseEv + (evBiasOverride ?: 0f)
                val steps = (totalEv / caps.evStep).roundToInt()
                    .coerceIn(caps.evRangeSteps.lower, caps.evRangeSteps.upper)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
            }
        }

        val kelvin = settings.effectiveKelvin
        if (settings.wbPreset != WhiteBalancePreset.AWB && kelvin != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                KelvinColorTransform.toRggbGains(kelvin, settings.wbPreset.tint)
            )
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }

        // One-Shot AF uses CONTROL_AF_MODE_AUTO so VCM optics physically hunt and confirm sharpness on trigger;
        // AI Servo / AI Focus use CONTINUOUS_PICTURE for continuous tracking.
        when {
            settings.afMode == AfMode.MANUAL -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                val maxDiopter = caps?.minFocusDistance ?: 10f
                builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    settings.manualFocusDiopter.coerceIn(0f, maxDiopter)
                )
            }
            settings.afMode == AfMode.ONE_SHOT || forceActiveAfMode -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            }
            caps?.supportsContinuousPictureAf == true -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            else -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            }
        }

        if (afRect != null && settings.afMode != AfMode.MANUAL) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afRect))
        }
        if (aeRect != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(aeRect))
        }
    }

    private fun buildAeMeteringRect(settings: CameraSettingsState): MeteringRectangle? {
        return when (settings.meteringMode) {
            MeteringMode.SPOT -> buildMeteringRect(0.50f, 0.50f, settings.zoomRatio, halfExtentFrac = 0.035f)
            MeteringMode.PARTIAL -> buildMeteringRect(0.50f, 0.50f, settings.zoomRatio, halfExtentFrac = 0.065f)
            MeteringMode.CENTER_WEIGHTED -> buildMeteringRect(0.50f, 0.50f, settings.zoomRatio, halfExtentFrac = 0.26f)
            MeteringMode.EVALUATIVE -> null // Full-frame 63-zone matrix metering prevents dark subjects from blowing out highlights
        }
    }

    private fun applyZoomControl(
        builder: CaptureRequest.Builder,
        requestedZoom: Float,
        caps: DslrCameraCapabilities?
    ) {
        val minZ = caps?.minZoomRatio ?: 1.0f
        val maxZ = caps?.maxZoomRatio ?: 4.0f
        val z = requestedZoom.coerceIn(minZ, maxZ)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
        } else {
            val active = caps?.activeArraySize ?: return
            val cropW = (active.width() / z.coerceAtLeast(1.0f)).roundToInt().coerceAtLeast(2)
            val cropH = (active.height() / z.coerceAtLeast(1.0f)).roundToInt().coerceAtLeast(2)
            val left = active.left + (active.width() - cropW) / 2
            val top = active.top + (active.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropW, top + cropH))
        }
    }

    private fun buildMeteringRect(
        normX: Float,
        normY: Float,
        zoomRatio: Float,
        halfExtentFrac: Float = 0.08f
    ): MeteringRectangle? {
        val active: Rect = _capabilities.value?.activeArraySize ?: return null
        val sensorOri = _capabilities.value?.sensorOrientation ?: 90
        val nx = normX.coerceIn(0.05f, 0.95f)
        val ny = normY.coerceIn(0.05f, 0.95f)

        val sx: Float
        val sy: Float
        when (displayRotation) {
            Surface.ROTATION_270 -> {
                if (sensorOri == 90) {
                    sx = 1f - nx
                    sy = 1f - ny
                } else {
                    sx = nx
                    sy = ny
                }
            }
            Surface.ROTATION_0 -> {
                sx = ny
                sy = 1f - nx
            }
            else -> {
                if (sensorOri == 90) {
                    sx = nx
                    sy = ny
                } else {
                    sx = 1f - nx
                    sy = 1f - ny
                }
            }
        }

        val z = zoomRatio.coerceAtLeast(1.0f)
        val zoomedSx = 0.5f + (sx - 0.5f) / z
        val zoomedSy = 0.5f + (sy - 0.5f) / z

        val cx = (active.left + zoomedSx * active.width()).roundToInt()
        val cy = (active.top + zoomedSy * active.height()).roundToInt()
        val halfW = (active.width() * halfExtentFrac / z).roundToInt().coerceAtLeast(24)
        val halfH = (active.height() * halfExtentFrac / z).roundToInt().coerceAtLeast(24)

        val left = (cx - halfW).coerceIn(active.left, active.right - 2)
        val top = (cy - halfH).coerceIn(active.top, active.bottom - 2)
        val right = (cx + halfW).coerceIn(left + 1, active.right)
        val bottom = (cy + halfH).coerceIn(top + 1, active.bottom)

        return MeteringRectangle(left, top, right - left, bottom - top, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun computeJpegOrientation(sensorOrientation: Int): Int {
        val deviceDegrees = when (displayRotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 90
        }
        return (sensorOrientation - deviceDegrees + 360) % 360
    }

    private fun scheduleAfFallbackPulse() {
        val h = cameraHandler ?: return
        cancelAfFallbackPulse()
        val r = Runnable {
            if (awaitingAfLock) {
                val halIgnoredTrigger = !sawActiveScan && !lastLensMoving
                userAfActive = false
                awaitingAfLock = false
                sawActiveScan = false
                _isAfHunting.value = false
                if (halIgnoredTrigger) {
                    _afLocked.value = true
                    _isSubjectFocused.value = true
                    _afPulseSeq.value += 1
                    _afLockConfirmedSeq.value += 1
                    pendingFocusDeferred?.complete(true)
                } else {
                    _afLocked.value = false
                    _isSubjectFocused.value = false
                    _afLockFailedSeq.value += 1
                    pendingFocusDeferred?.complete(false)
                }
            }
        }
        afLockTimeoutRunnable = r
        h.postDelayed(r, 2200L)
    }

    private fun cancelAfFallbackPulse() {
        afLockTimeoutRunnable?.let { cameraHandler?.removeCallbacks(it) }
        afLockTimeoutRunnable = null
        afResweepRunnable?.let { cameraHandler?.removeCallbacks(it) }
        afResweepRunnable = null
    }

    private fun scheduleAfResweep() {
        val h = cameraHandler ?: return
        afResweepRunnable?.let { h.removeCallbacks(it) }
        val r = Runnable {
            if (!awaitingAfLock || !userAfActive) return@Runnable
            val device = cameraDevice ?: return@Runnable
            val session = captureSession ?: return@Runnable
            val preview = previewSurface ?: return@Runnable
            if (!preview.isValid) return@Runnable

            val settings = activeSettings
            val afRect = buildMeteringRect(settings.customFocusNormX, settings.customFocusNormY, settings.zoomRatio, halfExtentFrac = 0.075f)
            val aeRect = buildAeMeteringRect(settings)
            val forceAuto = settings.afMode == AfMode.ONE_SHOT || _capabilities.value?.supportsContinuousPictureAf != true

            scheduleAfFallbackPulse()
            runCatching {
                val cancelBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                    applyDslrControls(this, settings, afRect = afRect, aeRect = aeRect, isStillCapture = false, forceActiveAfMode = forceAuto)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }
                session.capture(cancelBuilder.build(), previewCallback, h)

                val triggerBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(preview)
                    set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW)
                    applyDslrControls(this, settings, afRect = afRect, aeRect = aeRect, isStillCapture = false, forceActiveAfMode = forceAuto)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                session.capture(triggerBuilder.build(), previewCallback, h)
            }
        }
        afResweepRunnable = r
        h.postDelayed(r, 110L)
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val lensState = result.get(CaptureResult.LENS_STATE)
            val focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (focusDist != null) {
                _lensFocusDiopters.value = focusDist
            }

            val lensMoving = lensState == CaptureResult.LENS_STATE_MOVING
            lastLensMoving = lensMoving
            val passiveOrActiveMoving = activeSettings.afMode != AfMode.MANUAL && (
                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                    afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                    lensMoving
                )
            // Only emit active AF hunting state/sound during explicit user half-press or focus command
            val activeUserHunting = activeSettings.afMode != AfMode.MANUAL && userAfActive
            val passiveFocusedNow = activeSettings.afMode == AfMode.MANUAL || (
                (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) && !passiveOrActiveMoving
                )

            _isAfHunting.value = activeUserHunting
            _isPassiveFocused.value = passiveFocusedNow
            if (!awaitingAfLock && _afLocked.value && activeSettings.afMode != AfMode.MANUAL) {
                if (afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED || lensMoving) {
                    _isSubjectFocused.value = false
                }
            }

            if (awaitingAfLock) {
                if (afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                    afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                    lensMoving
                ) {
                    sawActiveScan = true
                }
                val elapsedMs = System.currentTimeMillis() - afTriggerStartMs
                if (activeSettings.afMode == AfMode.AI_SERVO) {
                    if ((sawActiveScan || elapsedMs >= 180L) && !lensMoving &&
                        (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED)
                    ) {
                        userAfActive = false
                        awaitingAfLock = false
                        sawActiveScan = false
                        cancelAfFallbackPulse()
                        _isAfHunting.value = false
                        _afLocked.value = true
                        _isSubjectFocused.value = true
                        _afLockConfirmedSeq.value += 1
                        pendingFocusDeferred?.complete(true)
                    }
                } else if (!lensMoving) {
                    val lockedNow = (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED &&
                        (sawActiveScan || elapsedMs >= 200L)) ||
                        (activeSettings.afMode == AfMode.AI_FOCUS &&
                            sawActiveScan &&
                            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED &&
                            elapsedMs >= 180L)
                    val failedNow = sawActiveScan && afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED

                    if (lockedNow) {
                        userAfActive = false
                        awaitingAfLock = false
                        sawActiveScan = false
                        afRetryCount = 0
                        cancelAfFallbackPulse()
                        _isAfHunting.value = false
                        _afLocked.value = true
                        _isSubjectFocused.value = true
                        _afPulseSeq.value += 1
                        _afLockConfirmedSeq.value += 1
                        pendingFocusDeferred?.complete(true)
                    } else if (failedNow) {
                        _afLockFailedSeq.value += 1
                        if (userAfActive && afRetryCount < 4) {
                            afRetryCount++
                            sawActiveScan = false
                            afTriggerStartMs = System.currentTimeMillis()
                            scheduleAfResweep()
                        } else {
                            userAfActive = false
                            awaitingAfLock = false
                            sawActiveScan = false
                            cancelAfFallbackPulse()
                            _isAfHunting.value = false
                            _afLocked.value = false
                            _isSubjectFocused.value = false
                            pendingFocusDeferred?.complete(false)
                        }
                    }
                }
            }

            val now = System.currentTimeMillis()
            if (now - lastTelemetryMs >= 180L) {
                lastTelemetryMs = now
                val expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: activeSettings.shutterSpeedNs
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: if (activeSettings.iso > 0) activeSettings.iso else 400
                val targetIso = (if (activeSettings.iso <= 0) 400 else activeSettings.iso).toDouble()
                val evOffset = if (activeSettings.exposureMode.allowsManualShutter) {
                    activeSettings.evCompensation
                } else {
                    val shutterEv = log2((expNs.toDouble() / 4_000_000.0).coerceAtLeast(0.01))
                    val isoEv = log2((iso.toDouble() / targetIso).coerceAtLeast(0.1))
                    (shutterEv + isoEv).toFloat().coerceIn(-3f, 3f)
                }
                _liveTelemetry.value = LiveCameraTelemetry(
                    shutterNs = expNs,
                    iso = iso,
                    evOffset = evOffset
                )
            }
        }
    }

    private suspend fun processAndSaveJpegBytes(
        bytes: ByteArray,
        meta: PendingCaptureMeta,
        exifDegrees: Int
    ) = stillProcessMutex.withLock {
        var finalized = false
        try {
            val settings = meta.settings
            val t0 = System.currentTimeMillis()
            val decodeOpts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: run {
                _lastSavedEvent.value = "Decode error: invalid sensor frame"
                return@withLock
            }

            val subjectMask = if (settings.opticalBokehEnabled && settings.opticalBokehStrength > 0.01f) {
                subjectSegmenter.buildCaptureSubjectMask(
                    sourceBitmap = decoded,
                    exifDegrees = exifDegrees,
                    focusNormX = settings.customFocusNormX,
                    focusNormY = settings.customFocusNormY,
                    lensDiopters = meta.diopters,
                    isFocusLocked = meta.wasFocused,
                    isAutoMultiPointAf = settings.isAutoAfSelection,
                    apertureF = settings.simulatedApertureF,
                    dofWidthScale = settings.externalLens.dofWidthScale
                )
            } else {
                com.studio6d.core.lut.SubjectMaskData.NONE
            }

            val graded = try {
                GpuStillLutProcessor.process(
                    context = context,
                    source = decoded,
                    exifDegrees = exifDegrees,
                    style = settings.pictureStyle,
                    lutIntensity = settings.lutIntensity,
                    highlightRolloff = settings.highlightRolloff,
                    vignetteStrength = settings.simulatedVignetteStrength,
                    grainStrength = settings.simulatedGrainStrength,
                    bokehStrength = settings.opticalBokehStrength,
                    subjectMask = subjectMask,
                    lensDiopters = meta.diopters,
                    contrastOffset = settings.contrastOffset,
                    saturationOffset = settings.saturationOffset,
                    colorToneOffset = settings.colorToneOffset
                )
            } finally {
                decoded.recycle()
            }

            val finalPreview = buildSmallGradedPreview(graded)
            if (finalPreview != null) {
                updatePendingBufferPreview(meta.captureId, finalPreview, "SAVING TO CARD...")
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(meta.captureTimestampMs))
            val fileName = "DM6D_${stamp}_${settings.pictureStyle.shortCode}.jpg"
            val savedUri = try {
                saveBitmapWithExifToMediaStore(
                    displayName = fileName,
                    bitmap = graded,
                    settings = settings,
                    evBiasOverride = meta.evBiasOverride,
                    flashFired = meta.flashFired,
                    captureTimestampMs = meta.captureTimestampMs
                )
            } finally {
                graded.recycle()
            }
            if (savedUri != null) {
                finalizePendingBufferItem(meta.captureId, savedUri.toString(), finalPreview)
                finalized = true
                val elapsedMs = System.currentTimeMillis() - t0
                _lastSavedEvent.value = "Saved $fileName (${elapsedMs}ms)"
            }
        } finally {
            if (!finalized) {
                removePendingBufferItem(meta.captureId)
            }
        }
    }

    private fun saveBitmapWithExifToMediaStore(
        displayName: String,
        bitmap: Bitmap,
        settings: CameraSettingsState,
        evBiasOverride: Float?,
        flashFired: Boolean,
        captureTimestampMs: Long
    ): Uri? {
        val width = bitmap.width
        val height = bitmap.height
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DigitalMango6D")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: run {
            _lastSavedEvent.value = "Save failed: MediaStore unavailable"
            return null
        }

        return try {
            val os = resolver.openOutputStream(uri) ?: error("Cannot open output stream")
            val compressed = os.use { rawOs ->
                BufferedOutputStream(rawOs, 65536).use { bos ->
                    val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                    bos.flush()
                    ok
                }
            }
            if (!compressed) {
                runCatching { resolver.delete(uri, null, null) }
                _lastSavedEvent.value = "JPEG compression failed"
                return null
            }
            writeDslrExif(
                uri = uri,
                width = width,
                height = height,
                settings = settings,
                evBiasOverride = evBiasOverride,
                flashFired = flashFired,
                captureTimestampMs = captureTimestampMs
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
            }
            uri
        } catch (err: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            _lastSavedEvent.value = "Save failed: ${err.message ?: "I/O error"}"
            null
        }
    }

    private fun writeDslrExif(
        uri: Uri,
        width: Int,
        height: Int,
        settings: CameraSettingsState,
        evBiasOverride: Float?,
        flashFired: Boolean,
        captureTimestampMs: Long
    ) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val biasScale = if (evBiasOverride != null) 2.0.pow(-evBiasOverride.toDouble()) else 1.0
                val expSeconds = (settings.shutterSpeedNs * biasScale) / 1_000_000_000.0
                val effectiveIso = if (settings.iso <= 0) settings.liveMeteredIso else settings.iso
                val focal35mm = (settings.externalLens.baseFocal35mm * settings.zoomRatio).roundToInt()
                val captureDate = Date(captureTimestampMs)
                val exifDate = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(captureDate)
                val subSec = SimpleDateFormat("SSS", Locale.US).format(captureDate)

                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
                exif.setAttribute(ExifInterface.TAG_MAKE, "Digital Mango")
                exif.setAttribute(ExifInterface.TAG_MODEL, "Digital Mango 6D")
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Digital Mango 6D Optical Engine")
                exif.setAttribute(ExifInterface.TAG_F_NUMBER, "%.1f".format(Locale.US, settings.simulatedApertureF))
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "%.6f".format(Locale.US, expSeconds))
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, effectiveIso.toString())
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, focal35mm.toString())
                exif.setAttribute(
                    ExifInterface.TAG_WHITE_BALANCE,
                    if (settings.wbPreset == WhiteBalancePreset.AWB) {
                        ExifInterface.WHITE_BALANCE_AUTO.toString()
                    } else {
                        ExifInterface.WHITE_BALANCE_MANUAL.toString()
                    }
                )
                exif.setAttribute(ExifInterface.TAG_FLASH, if (flashFired) "1" else "0")
                exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
                exif.setAttribute(ExifInterface.TAG_DATETIME, exifDate)
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDate)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDate)
                exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, subSec)
                exif.saveAttributes()
            }
        }
    }

    private fun readExifDegrees(jpegBytes: ByteArray): Int {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(jpegBytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    @Synchronized
    private fun trySavePendingDng() {
        val raw = latestRawImage ?: return
        val result = latestCaptureResult ?: return
        val chars = characteristics ?: return

        latestRawImage = null
        latestCaptureResult = null

        scope.launch {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val fileName = "DM6D_$stamp.dng"
                saveToMediaStoreAtomic(fileName, "image/x-adobe-dng", Environment.DIRECTORY_PICTURES + "/DigitalMango6D/RAW") { os ->
                    DngCreator(chars, result).use { dngCreator ->
                        dngCreator.writeImage(os, raw)
                    }
                }
                _lastSavedEvent.value = "Saved JPEG + RAW ($fileName)"
            } finally {
                runCatching { raw.close() }
            }
        }
    }

    private fun saveToMediaStoreAtomic(
        displayName: String,
        mimeType: String,
        relativePath: String,
        writer: (java.io.OutputStream) -> Unit
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return try {
            val os = resolver.openOutputStream(uri) ?: error("Cannot open output stream")
            os.use { rawOs ->
                BufferedOutputStream(rawOs, 65536).use { bos ->
                    writer(bos)
                    bos.flush()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publish = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
            }
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun deleteCapturedUri(uriString: String) {
        scope.launch {
            runCatching {
                context.contentResolver.delete(Uri.parse(uriString), null, null)
            }
            _capturedUris.value = _capturedUris.value.filterNot { it == uriString }
            _playbackItems.update { current ->
                val updated = current.filterNot { it.uri == uriString || it.id == uriString }
                updatePendingCountFromItems(updated)
                updated
            }
        }
    }

    fun closeCamera() {
        val handler = cameraHandler
        if (handler == null) {
            closeCameraInternal()
            return
        }
        handler.post {
            closeCameraInternal()
        }
    }

    fun release() {
        subjectSegmenter.close()
        val handler = cameraHandler
        if (handler != null) {
            handler.post {
                closeCameraInternal()
                stopBackgroundThread()
            }
        } else {
            closeCameraInternal()
            stopBackgroundThread()
        }
    }

    private fun closeCameraInternal() {
        cancelAfFallbackPulse()
        while (true) {
            val unreceived = pendingCaptureQueue.poll() ?: break
            removePendingBufferItem(unreceived.captureId)
        }
        userAfActive = false
        awaitingAfLock = false
        pendingFocusDeferred?.complete(false)
        pendingFocusDeferred = null
        _isAfHunting.value = false
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        runCatching { jpegReader?.close() }
        jpegReader = null
        runCatching { rawReader?.close() }
        rawReader = null
        runCatching { latestRawImage?.close() }
        latestRawImage = null
        sessionWithRaw = false
    }

    private fun ensureBackgroundThread() {
        if (handlerThread != null) return
        handlerThread = HandlerThread("Camera2DslrThread").also { it.start() }
        cameraHandler = Handler(handlerThread!!.looper)
    }

    private fun stopBackgroundThread() {
        handlerThread?.quitSafely()
        handlerThread = null
        cameraHandler = null
    }
}
