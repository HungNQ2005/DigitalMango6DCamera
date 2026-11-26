package com.studio6d.camera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.studio6d.ui.dslr.Dslr6dCameraScreen
import kotlin.math.atan2

class MainActivity : ComponentActivity(), SensorEventListener {

    private val viewModel: DslrViewModel by viewModels()
    private var hasCameraPermission by mutableStateOf(false)
    private var rollAngleDeg by mutableFloatStateOf(0f)

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        viewModel.setPermissionGranted(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setPermissionGranted(hasCameraPermission)
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            val state by viewModel.state.collectAsState()
            val caps by viewModel.capabilities.collectAsState()
            val capturedUris by viewModel.capturedUris.collectAsState()
            val playbackItems by viewModel.playbackItems.collectAsState()
            val rotation by viewModel.displayRotation.collectAsState()
            val isPassiveFocused by viewModel.isPassiveFocused.collectAsState()

            val sensorAspect = caps?.let {
                it.previewSize.width.toFloat() / it.previewSize.height.toFloat().coerceAtLeast(1f)
            } ?: (4f / 3f)

            Dslr6dCameraScreen(
                state = state,
                hasCameraPermission = hasCameraPermission,
                displayRotation = rotation,
                sensorAspect = sensorAspect,
                capturedUris = capturedUris,
                playbackItems = playbackItems,
                hardwareInfoSummary = viewModel.formatHardwareSummary(),
                rollAngleDeg = rollAngleDeg,
                isPassiveFocused = isPassiveFocused,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onSurfaceTextureReady = { st ->
                    updateRotationFromDisplay()
                    viewModel.onSurfaceTextureReady(st)
                },
                onHistogramUpdated = viewModel::onHistogramUpdated,
                onAutoAfPointsUpdated = viewModel::onAutoAfPointsDetected,
                onTapToFocus = viewModel::onTapToFocus,
                onPinchZoom = viewModel::onPinchZoom,
                onCycleFocalPreset = viewModel::onCycleFocalPreset,
                onToggleAeLock = viewModel::onToggleAeLock,
                onHalfPressAf = viewModel::onHalfPressAf,
                onAfButtonClick = viewModel::onAfButtonClick,
                onEnableAutoAfSelection = viewModel::onEnableAutoAfSelection,
                onReleaseHalfPress = viewModel::onReleaseHalfPress,
                onShutterFire = viewModel::onShutterFire,
                onStepDial = viewModel::onStepDial,
                onStepModeDial = viewModel::onStepModeDial,
                onDirectionalClick = viewModel::onDirectionalClick,
                onSetClick = viewModel::onSetClick,
                onSelectFlashMode = viewModel::onSelectFlashMode,
                onUpdateState = viewModel::updateState,
                onDeleteUri = viewModel::onDeleteUri
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        updateRotationFromDisplay()
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setPermissionGranted(hasCameraPermission)
        viewModel.onResumeCamera()
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        viewModel.onPauseCamera()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            updateRotationFromDisplay()
        }
    }

    private var gripShutterDownMs: Long = 0L

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CAMERA -> {
                if (event?.repeatCount == 0) {
                    gripShutterDownMs = System.currentTimeMillis()
                    viewModel.onHalfPressAf()
                }
                true
            }
            KeyEvent.KEYCODE_FOCUS -> {
                if (event?.repeatCount == 0) {
                    viewModel.onHalfPressAf()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CAMERA -> {
                val heldMs = System.currentTimeMillis() - gripShutterDownMs
                viewModel.onShutterFire(fromHalfPress = heldMs >= 350L)
                viewModel.onReleaseHalfPress()
                true
            }
            KeyEvent.KEYCODE_FOCUS -> {
                viewModel.onReleaseHalfPress()
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val ax = event.values[0]
        val ay = event.values[1]
        val rot = viewModel.displayRotation.value
        val rawDeg = Math.toDegrees(atan2(ay.toDouble(), ax.toDouble())).toFloat()
        rollAngleDeg = if (rot == Surface.ROTATION_270) {
            -Math.toDegrees(atan2(ay.toDouble(), (-ax).toDouble())).toFloat()
        } else {
            rawDeg
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateRotationFromDisplay() {
        @Suppress("DEPRECATION")
        val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: windowManager.defaultDisplay.rotation
        } else {
            windowManager.defaultDisplay.rotation
        }
        viewModel.setDisplayRotation(rot)
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
