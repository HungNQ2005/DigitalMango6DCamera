package com.studio6d.camera

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studio6d.core.camera2.Camera2DslrController
import com.studio6d.core.camera2.DslrCameraCapabilities
import com.studio6d.core.camera2.DslrSoundEngine
import com.studio6d.core.model.ActiveDialTarget
import com.studio6d.core.model.AfMode
import com.studio6d.core.model.AfPoints6D
import com.studio6d.core.model.CameraSettingsState
import com.studio6d.core.model.DriveMode
import com.studio6d.core.model.DslrStops
import com.studio6d.core.model.ExposureMode
import com.studio6d.core.model.FlashMode
import com.studio6d.core.model.MeteringMode
import com.studio6d.core.model.PictureStyle
import com.studio6d.core.model.PlaybackPhotoItem
import com.studio6d.core.model.WhiteBalancePreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DslrViewModel(application: Application) : AndroidViewModel(application) {

    private val cameraController = Camera2DslrController(application)
    private val soundEngine = DslrSoundEngine(application)

    private val _state = MutableStateFlow(CameraSettingsState())
    val state: StateFlow<CameraSettingsState> = _state.asStateFlow()

    val capturedUris: StateFlow<List<String>> = cameraController.capturedUris
    val playbackItems: StateFlow<List<PlaybackPhotoItem>> = cameraController.playbackItems
    val isPassiveFocused: StateFlow<Boolean> = cameraController.isPassiveFocused

    private val _capabilities = MutableStateFlow<DslrCameraCapabilities?>(null)
    val capabilities: StateFlow<DslrCameraCapabilities?> = _capabilities.asStateFlow()

    private val _displayRotation = MutableStateFlow(Surface.ROTATION_90)
    val displayRotation: StateFlow<Int> = _displayRotation.asStateFlow()

    private var activeSurfaceTexture: SurfaceTexture? = null
    private var hasPermission: Boolean = false
    private var bannerClearJob: Job? = null
    private var captureJob: Job? = null

    @Volatile
    private var isShutterHeldDown: Boolean = false
    @Volatile
    private var hasAutoFiredForCurrentHold: Boolean = false
    @Volatile
    private var autoCaptureInProgress: Boolean = false

    init {
        viewModelScope.launch {
            cameraController.pendingSaveCount.collect { count ->
                _state.update { it.copy(pendingSaveCount = count) }
            }
        }
        viewModelScope.launch {
            cameraController.capabilities.collect { caps ->
                _capabilities.value = caps
                if (caps != null) {
                    _state.update { s ->
                        s.copy(
                            minZoomRatio = caps.minZoomRatio,
                            maxZoomRatio = caps.maxZoomRatio,
                            zoomRatio = s.zoomRatio.coerceIn(caps.minZoomRatio, caps.maxZoomRatio)
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            cameraController.afLocked.collect { locked ->
                _state.update { it.copy(isHalfPressedAfLocked = locked) }
            }
        }
        viewModelScope.launch {
            cameraController.isAfHunting.collect { hunting ->
                _state.update { it.copy(isAfHunting = hunting) }
                if (hunting && isShutterHeldDown && _state.value.isCameraPoweredOn && _state.value.soundEnabled) {
                    soundEngine.startAfHunt()
                } else if (!hunting) {
                    soundEngine.stopAfHunt()
                }
            }
        }
        viewModelScope.launch {
            cameraController.isSubjectFocused.collect { focused ->
                _state.update { it.copy(isSubjectFocused = focused) }
                if (focused) {
                    soundEngine.stopAfHunt()
                }
            }
        }
        viewModelScope.launch {
            cameraController.afLockConfirmedSeq.collect { seq ->
                if (seq > 0) {
                    soundEngine.stopAfHunt()
                    if (_state.value.afMode != AfMode.AI_SERVO) {
                        soundEngine.playAfBeep()
                    }
                    _state.update {
                        it.copy(
                            isHalfPressedAfLocked = true,
                            isSubjectFocused = true,
                            isAfHunting = false,
                            afFocusPulseSeq = seq
                        )
                    }
                    if (isShutterHeldDown && !hasAutoFiredForCurrentHold && !_state.value.isCapturing) {
                        hasAutoFiredForCurrentHold = true
                        autoCaptureInProgress = true
                        viewModelScope.launch {
                            delay(60L)
                            if (!_state.value.isCapturing && _state.value.isCameraPoweredOn) {
                                executeShutterCapture(fromHalfPress = true)
                            } else {
                                autoCaptureInProgress = false
                            }
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            cameraController.afLockFailedSeq.collect { seq ->
                if (seq > 0) {
                    soundEngine.stopAfHunt()
                    _state.update {
                        it.copy(
                            afFocusFailedSeq = seq,
                            isAfHunting = false,
                            isHalfPressedAfLocked = false,
                            isSubjectFocused = false
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            cameraController.lensFocusDiopters.collect { diopters ->
                _state.update { it.copy(lensFocusDiopters = diopters) }
            }
        }
        viewModelScope.launch {
            cameraController.liveTelemetry.collect { telemetry ->
                _state.update {
                    it.copy(
                        liveMeteredShutterNs = telemetry.shutterNs,
                        liveMeteredIso = telemetry.iso,
                        measuredEvOffset = telemetry.evOffset
                    )
                }
            }
        }
        viewModelScope.launch {
            cameraController.lastSavedEvent.collect { event ->
                if (!event.isNullOrBlank()) {
                    showBanner(event)
                }
            }
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        hasPermission = granted
        if (granted && _state.value.isCameraPoweredOn) {
            activeSurfaceTexture?.let { st ->
                cameraController.openCamera(st, _state.value)
            }
        }
    }

    fun setDisplayRotation(rotation: Int) {
        _displayRotation.value = rotation
        cameraController.displayRotation = rotation
    }

    fun onSurfaceTextureReady(surfaceTexture: SurfaceTexture) {
        activeSurfaceTexture = surfaceTexture
        if (hasPermission && _state.value.isCameraPoweredOn) {
            cameraController.openCamera(surfaceTexture, _state.value)
        }
    }

    fun onHistogramUpdated(bins: FloatArray) {
        _state.update { it.copy(histogramBins = bins) }
    }

    fun onPauseCamera() {
        cameraController.closeCamera()
    }

    fun onResumeCamera() {
        if (hasPermission && _state.value.isCameraPoweredOn) {
            activeSurfaceTexture?.let { st ->
                cameraController.openCamera(st, _state.value)
            }
        }
    }

    fun updateState(transform: (CameraSettingsState) -> CameraSettingsState) {
        val prev = _state.value
        val next = transform(prev)
        _state.value = next
        soundEngine.enabled = next.soundEnabled

        if (prev.isCameraPoweredOn != next.isCameraPoweredOn) {
            if (next.isCameraPoweredOn) {
                onResumeCamera()
            } else {
                soundEngine.stopAfHunt()
                onPauseCamera()
            }
        } else if (next.isCameraPoweredOn) {
            cameraController.updatePreview(next)
        }
    }

    fun onAutoAfPointsDetected(indices: List<Int>) {
        val current = _state.value
        if (!current.isCameraPoweredOn || !current.isAutoAfSelection ||
            current.isHalfPressedAfLocked || current.isAfHunting || current.isCapturing
        ) {
            return
        }
        val valid = indices.filter { it in 0..10 }.distinct().ifEmpty { listOf(5) }
        val primary = AfPoints6D.getPoint(valid.first())
        if (current.activeAfPointIndices == valid && current.selectedAfPointIndex == primary.index) return
        _state.update {
            it.copy(
                activeAfPointIndices = valid,
                selectedAfPointIndex = primary.index,
                customFocusNormX = primary.normX,
                customFocusNormY = primary.normY
            )
        }
    }

    fun onEnableAutoAfSelection() {
        if (!_state.value.isCameraPoweredOn) return
        soundEngine.playDialTick()
        _state.update {
            it.copy(
                isAutoAfSelection = true,
                activeAfPointIndices = listOf(5),
                selectedAfPointIndex = 5,
                customFocusNormX = 0.50f,
                customFocusNormY = 0.50f
            )
        }
        showBanner("11-PT AUTO AF")
    }

    fun onTapToFocus(normX: Float, normY: Float) {
        if (!_state.value.isCameraPoweredOn) return
        val nearestIdx = AfPoints6D.nearestPointIndex(normX, normY)
        _state.update {
            it.copy(
                isAutoAfSelection = false,
                activeAfPointIndices = listOf(nearestIdx),
                selectedAfPointIndex = nearestIdx,
                customFocusNormX = normX,
                customFocusNormY = normY,
                isHalfPressedAfLocked = false,
                isSubjectFocused = false
            )
        }
        cameraController.focusAtPoint(normX, normY, _state.value)
        showBanner("SINGLE-PT AF")
    }

    fun onPinchZoom(scaleFactor: Float) {
        val current = _state.value
        if (!current.isCameraPoweredOn) return
        val nextZoom = (current.zoomRatio * scaleFactor).coerceIn(current.minZoomRatio, current.maxZoomRatio)
        if (kotlin.math.abs(nextZoom - current.zoomRatio) < 0.01f) return
        updateState { it.copy(zoomRatio = nextZoom, activeDialTarget = ActiveDialTarget.LENS_ZOOM) }
    }

    fun onCycleFocalPreset() {
        val current = _state.value
        if (!current.isCameraPoweredOn) return
        soundEngine.playDialTick()
        val presets = buildList {
            if (current.minZoomRatio < 0.85f) add(current.minZoomRatio)
            add(1.0f)
            if (current.maxZoomRatio >= 1.5f) add(1.5f)
            if (current.maxZoomRatio >= 2.0f) add(2.0f)
            if (current.maxZoomRatio >= 3.5f) add(3.5f)
            if (current.maxZoomRatio >= 5.0f) add(5.0f)
            if (current.maxZoomRatio > 5.2f) add(current.maxZoomRatio)
        }.distinct()

        val nextZoom = presets.firstOrNull { it > current.zoomRatio + 0.15f } ?: presets.first()
        updateState { it.copy(zoomRatio = nextZoom, activeDialTarget = ActiveDialTarget.LENS_ZOOM) }
        showBanner("${DslrStops.formatFocalLength35mm(nextZoom)} (${DslrStops.formatZoom(nextZoom)})")
    }

    fun onToggleAeLock() {
        if (!_state.value.isCameraPoweredOn) return
        soundEngine.playDialTick()
        val nextLock = !_state.value.isAeLocked
        updateState { it.copy(isAeLocked = nextLock) }
        showBanner(if (nextLock) "AE LOCKED (*)" else "AE UNLOCKED")
    }

    fun onHalfPressAf() {
        if (!_state.value.isCameraPoweredOn) return
        isShutterHeldDown = true
        hasAutoFiredForCurrentHold = false
        _state.update {
            it.copy(
                isHalfPressedAfLocked = false,
                isSubjectFocused = false,
                isAfHunting = true,
                isMenuOpen = false,
                isQMenuOpen = false,
                isPlaybackOpen = false,
                isBokehSliderOpen = false
            )
        }
        cameraController.triggerHalfPressAfAe(_state.value)
        if (_state.value.soundEnabled && _state.value.afMode != AfMode.MANUAL) {
            soundEngine.startAfHunt()
        }
    }

    fun onAfButtonClick() {
        val current = _state.value
        if (!current.isCameraPoweredOn) return
        if (current.isHalfPressedAfLocked || current.isAfHunting) {
            onReleaseHalfPress()
        } else {
            isShutterHeldDown = false
            hasAutoFiredForCurrentHold = false
            _state.update {
                it.copy(
                    isHalfPressedAfLocked = false,
                    isSubjectFocused = false,
                    isAfHunting = true,
                    isMenuOpen = false,
                    isQMenuOpen = false,
                    isPlaybackOpen = false,
                    isBokehSliderOpen = false
                )
            }
            cameraController.triggerHalfPressAfAe(_state.value)
            if (_state.value.soundEnabled && _state.value.afMode != AfMode.MANUAL) {
                soundEngine.startAfHunt()
            }
        }
    }

    fun onSelectFlashMode(mode: FlashMode) {
        if (!_state.value.isCameraPoweredOn) return
        soundEngine.playDialTick()
        updateState { it.copy(flashMode = mode) }
        showBanner("⚡ ${mode.label.uppercase()}")
    }

    fun onReleaseHalfPress() {
        isShutterHeldDown = false
        soundEngine.stopAfHunt()
        if (!autoCaptureInProgress && !_state.value.isCapturing) {
            hasAutoFiredForCurrentHold = false
            cameraController.releaseHalfPress()
            _state.update { it.copy(isHalfPressedAfLocked = false, isAfHunting = false, isSubjectFocused = false) }
        }
    }

    fun onShutterFire(fromHalfPress: Boolean = false) {
        val current = _state.value
        if (!current.isCameraPoweredOn || !hasPermission || current.isCapturing) return

        if (hasAutoFiredForCurrentHold) {
            isShutterHeldDown = false
            hasAutoFiredForCurrentHold = false
            return
        }

        if (fromHalfPress && !current.isSubjectFocused) {
            // Canon 6D Focus-Priority: If user releases finger before focus locked, cancel without shooting!
            onReleaseHalfPress()
            return
        }

        executeShutterCapture(fromHalfPress)
    }

    private fun executeShutterCapture(fromHalfPress: Boolean) {
        val current = _state.value
        if (!current.isCameraPoweredOn || !hasPermission || current.isCapturing) return

        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            autoCaptureInProgress = false
            val delaySec = when (current.driveMode) {
                DriveMode.TIMER_2S -> 2
                DriveMode.TIMER_10S -> 10
                else -> 0
            }
            for (sec in delaySec downTo 1) {
                showBanner("SELF-TIMER ${sec}s...")
                soundEngine.playDialTick()
                delay(1000L)
            }

            soundEngine.stopAfHunt()
            val latestBeforeShot = _state.value
            if (fromHalfPress) {
                if (latestBeforeShot.isAfHunting && !latestBeforeShot.isHalfPressedAfLocked && !latestBeforeShot.isSubjectFocused) {
                    val locked = cameraController.acquireFocusForShutter(latestBeforeShot)
                    soundEngine.stopAfHunt()
                    if (!locked && (latestBeforeShot.afMode == AfMode.ONE_SHOT || latestBeforeShot.afMode == AfMode.AI_FOCUS)) {
                        hasAutoFiredForCurrentHold = false
                        showBanner("AF NOT LOCKED")
                        return@launch
                    }
                }
            } else {
                if (!latestBeforeShot.isSubjectFocused || !latestBeforeShot.isHalfPressedAfLocked) {
                    val locked = cameraController.acquireFocusForShutter(latestBeforeShot)
                    soundEngine.stopAfHunt()
                    if (!locked && (latestBeforeShot.afMode == AfMode.ONE_SHOT || latestBeforeShot.afMode == AfMode.AI_FOCUS)) {
                        hasAutoFiredForCurrentHold = false
                        showBanner("AF NOT LOCKED")
                        return@launch
                    }
                    if (locked && latestBeforeShot.afMode != AfMode.MANUAL) {
                        if (latestBeforeShot.afMode != AfMode.AI_SERVO) {
                            soundEngine.playAfBeep()
                        }
                        _state.update {
                            it.copy(
                                isHalfPressedAfLocked = true,
                                isSubjectFocused = true,
                                afFocusPulseSeq = it.afFocusPulseSeq + 1
                            )
                        }
                        delay(45L)
                    }
                }
            }

            if (current.aebEnabled) {
                val brackets = listOf(-1.0f, 0.0f, 1.0f)
                for ((idx, bias) in brackets.withIndex()) {
                    val snap = _state.value
                    _state.update { it.copy(isCapturing = true, shutterReflexSeq = it.shutterReflexSeq + 1) }
                    soundEngine.playShutter(snap.driveMode.isSilent)
                    cameraController.capturePhoto(
                        settings = snap,
                        evBiasOverride = bias,
                        focusConfirmed = snap.isSubjectFocused,
                        unlockAfAfterCapture = idx == brackets.lastIndex
                    )
                    delay(70L)
                    _state.update {
                        it.copy(
                            isCapturing = false,
                            remainingShotsEstimate = (it.remainingShotsEstimate - 1).coerceAtLeast(0)
                        )
                    }
                    if (idx < brackets.lastIndex) delay(240L)
                }
            } else if (current.driveMode == DriveMode.CONTINUOUS || current.driveMode == DriveMode.SILENT_CONTINUOUS) {
                // Canon 6D 4.5fps Continuous / 3.0fps Silent Continuous while shutter is held
                val burstDelayMs = if (current.driveMode == DriveMode.SILENT_CONTINUOUS) 333L else 222L
                val maxFrames = if (fromHalfPress) 20 else 3
                var shot = 0
                while ((shot == 0 || (fromHalfPress && isShutterHeldDown) || (!fromHalfPress && shot < maxFrames)) &&
                    shot < maxFrames &&
                    _state.value.isCameraPoweredOn
                ) {
                    val snap = _state.value
                    val hasMore = (fromHalfPress && isShutterHeldDown && shot + 1 < maxFrames) ||
                        (!fromHalfPress && shot + 1 < maxFrames)
                    _state.update { it.copy(isCapturing = true, shutterReflexSeq = it.shutterReflexSeq + 1) }
                    soundEngine.playShutter(snap.driveMode.isSilent)
                    cameraController.capturePhoto(
                        settings = snap,
                        focusConfirmed = snap.isSubjectFocused,
                        unlockAfAfterCapture = !hasMore
                    )
                    delay(70L)
                    _state.update {
                        it.copy(
                            isCapturing = false,
                            remainingShotsEstimate = (it.remainingShotsEstimate - 1).coerceAtLeast(0)
                        )
                    }
                    shot++
                    if (shot < maxFrames && ((fromHalfPress && isShutterHeldDown) || !fromHalfPress)) {
                        delay(burstDelayMs - 70L)
                    } else {
                        break
                    }
                }
            } else {
                val snap = _state.value
                _state.update { it.copy(isCapturing = true, shutterReflexSeq = it.shutterReflexSeq + 1) }
                soundEngine.playShutter(snap.driveMode.isSilent)
                cameraController.capturePhoto(
                    settings = snap,
                    focusConfirmed = snap.isSubjectFocused,
                    unlockAfAfterCapture = true
                )
                delay(70L)
                _state.update {
                    it.copy(
                        isCapturing = false,
                        remainingShotsEstimate = (it.remainingShotsEstimate - 1).coerceAtLeast(0)
                    )
                }
            }

            if (!isShutterHeldDown) {
                hasAutoFiredForCurrentHold = false
            }
            delay(140L)
            _state.update { it.copy(isHalfPressedAfLocked = false, isSubjectFocused = false) }
        }
    }

    fun onStepDial(delta: Int) {
        val current = _state.value
        if (!current.isCameraPoweredOn) return
        if (current.isMultiControlLocked) {
            showBanner("MULTI-CONTROL LOCKED")
            return
        }
        soundEngine.playDialTick()

        updateState { s ->
            when (s.activeDialTarget) {
                ActiveDialTarget.SHUTTER -> {
                    val nextShutter = DslrStops.stepList(DslrStops.SHUTTER_STOPS_NS, s.shutterSpeedNs, delta)
                    s.copy(shutterSpeedNs = nextShutter)
                }
                ActiveDialTarget.APERTURE -> {
                    val nextAv = DslrStops.stepList(DslrStops.APERTURE_STOPS, s.simulatedApertureF, delta)
                    s.copy(simulatedApertureF = nextAv)
                }
                ActiveDialTarget.ISO -> {
                    val nextIso = DslrStops.stepList(DslrStops.ISO_STOPS, s.iso, delta)
                    s.copy(iso = nextIso)
                }
                ActiveDialTarget.EXPOSURE_COMP -> {
                    val nextEv = DslrStops.stepList(DslrStops.EV_STOPS, s.evCompensation, delta)
                    s.copy(evCompensation = nextEv)
                }
                ActiveDialTarget.LENS_ZOOM -> {
                    val step = if (s.zoomRatio >= 4.0f) 0.4f else 0.2f
                    val nextZoom = (s.zoomRatio + delta * step).coerceIn(s.minZoomRatio, s.maxZoomRatio)
                    s.copy(zoomRatio = nextZoom)
                }
                ActiveDialTarget.WHITE_BALANCE -> {
                    if (s.wbPreset == WhiteBalancePreset.KELVIN) {
                        val nextK = (s.customKelvin + delta * 100).coerceIn(2500, 10000)
                        s.copy(customKelvin = nextK)
                    } else {
                        val list = WhiteBalancePreset.entries
                        val idx = (s.wbPreset.ordinal + delta).mod(list.size)
                        s.copy(wbPreset = list[idx])
                    }
                }
                ActiveDialTarget.PICTURE_STYLE -> {
                    val list = PictureStyle.entries
                    val idx = (s.pictureStyle.ordinal + delta).mod(list.size)
                    s.copy(pictureStyle = list[idx])
                }
                ActiveDialTarget.AF_MODE -> {
                    val list = AfMode.entries
                    val idx = (s.afMode.ordinal + delta).mod(list.size)
                    s.copy(afMode = list[idx])
                }
                ActiveDialTarget.DRIVE_MODE -> {
                    val list = DriveMode.entries
                    val idx = (s.driveMode.ordinal + delta).mod(list.size)
                    s.copy(driveMode = list[idx])
                }
                ActiveDialTarget.METERING_MODE -> {
                    val list = MeteringMode.entries
                    val idx = (s.meteringMode.ordinal + delta).mod(list.size)
                    s.copy(meteringMode = list[idx])
                }
                ActiveDialTarget.MANUAL_FOCUS -> {
                    val maxDiopter = _capabilities.value?.minFocusDistance ?: 10f
                    val nextDiopter = (s.manualFocusDiopter + delta * (maxDiopter / 24f)).coerceIn(0f, maxDiopter)
                    s.copy(afMode = AfMode.MANUAL, manualFocusDiopter = nextDiopter)
                }
            }
        }
    }

    fun onStepModeDial(delta: Int) {
        if (!_state.value.isCameraPoweredOn) return
        soundEngine.playDialTick()
        updateState { s ->
            val modes = ExposureMode.entries
            val nextMode = modes[(s.exposureMode.ordinal + delta).mod(modes.size)]
            val nextTarget = when (nextMode) {
                ExposureMode.MANUAL, ExposureMode.SHUTTER_PRIORITY -> ActiveDialTarget.SHUTTER
                ExposureMode.APERTURE_PRIORITY -> ActiveDialTarget.APERTURE
                ExposureMode.PROGRAM -> ActiveDialTarget.EXPOSURE_COMP
                else -> s.activeDialTarget
            }
            s.copy(exposureMode = nextMode, activeDialTarget = nextTarget)
        }
        showBanner("MODE: ${_state.value.exposureMode.displayName}")
    }

    fun onDirectionalClick(dir: Int) {
        val current = _state.value
        if (!current.isCameraPoweredOn) return
        if (current.isMultiControlLocked) {
            showBanner("MULTI-CONTROL LOCKED")
            return
        }
        soundEngine.playDialTick()

        if (current.isQMenuOpen) {
            val targets = ActiveDialTarget.entries
            val delta = if (dir == 1 || dir == 2) 1 else -1
            val nextTarget = targets[(current.activeDialTarget.ordinal + delta).mod(targets.size)]
            _state.update { it.copy(activeDialTarget = nextTarget) }
            return
        }

        val curPt = AfPoints6D.getPoint(current.selectedAfPointIndex)
        val candidates = AfPoints6D.points.filter { pt ->
            when (dir) {
                0 -> pt.normY < curPt.normY - 0.05f
                1 -> pt.normX > curPt.normX + 0.05f
                2 -> pt.normY > curPt.normY + 0.05f
                else -> pt.normX < curPt.normX - 0.05f
            }
        }
        val nextPt = candidates.minByOrNull { pt ->
            val dx = pt.normX - curPt.normX
            val dy = pt.normY - curPt.normY
            dx * dx + dy * dy
        } ?: curPt

        _state.update {
            it.copy(
                isAutoAfSelection = false,
                activeAfPointIndices = listOf(nextPt.index),
                selectedAfPointIndex = nextPt.index,
                customFocusNormX = nextPt.normX,
                customFocusNormY = nextPt.normY
            )
        }
        cameraController.focusAtPoint(nextPt.normX, nextPt.normY, _state.value)
    }

    fun onSetClick() {
        val current = _state.value
        if (!current.isCameraPoweredOn) return
        soundEngine.playDialTick()
        when {
            current.isMenuOpen -> _state.update { it.copy(isMenuOpen = false) }
            current.isPlaybackOpen -> _state.update { it.copy(isPlaybackOpen = false) }
            current.isQMenuOpen -> _state.update { it.copy(isQMenuOpen = false) }
            else -> _state.update { it.copy(isQMenuOpen = true) }
        }
    }

    fun onDeleteUri(uri: String) {
        cameraController.deleteCapturedUri(uri)
        showBanner("ERASED IMAGE")
    }

    fun formatHardwareSummary(): String {
        val caps = _capabilities.value ?: return "Inspecting Camera2 back sensor..."
        return buildString {
            append("Camera ID: ${caps.cameraId} · Orientation: ${caps.sensorOrientation}°\n")
            append("Manual Sensor: ${if (caps.supportsManualSensor) "YES" else "AE"} · ")
            append("RAW DNG: ${if (caps.supportsRawDng) "YES" else "NO"} · ")
            append("Flash: ${if (caps.hasFlash) "YES" else "NO"}\n")
            append("Zoom Range: ${DslrStops.formatZoom(caps.minZoomRatio)}..${DslrStops.formatZoom(caps.maxZoomRatio)} · ")
            append("ISO: ${caps.isoRange.lower}..${caps.isoRange.upper}\n")
            append("Preview: ${caps.previewSize.width}x${caps.previewSize.height} · ")
            append("JPEG: ${caps.jpegSize.width}x${caps.jpegSize.height}")
        }
    }

    private fun showBanner(msg: String) {
        _state.update { it.copy(statusBanner = msg) }
        bannerClearJob?.cancel()
        bannerClearJob = viewModelScope.launch {
            delay(2200L)
            _state.update { if (it.statusBanner == msg) it.copy(statusBanner = null) else it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.release()
        soundEngine.release()
    }
}
