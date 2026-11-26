package com.studio6d.ui.dslr

import android.graphics.SurfaceTexture
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studio6d.core.model.ActiveDialTarget
import com.studio6d.core.model.CameraSettingsState
import com.studio6d.core.model.DslrStops
import com.studio6d.core.model.FlashMode
import com.studio6d.core.model.PictureStyle
import com.studio6d.core.model.PlaybackPhotoItem

/**
 * Production 2-Screen Canon EOS 6D Ergonomic Landscape DSLR Body ("Digital Mango 6D"):
 * - Full-body matte polycarbonate upper shoulder + organic pebbled vulcanite leatherette wrap
 * - Official Digital Mango 6D logo + Top Shoulder Monochrome/Amber Segment LCD (Screen #1)
 * - Recessed 3:2 Live View Monitor (Screen #2) with `designed by HungNQ2005` on lower bezel
 * - Hardware Zoom (pinch, wheel, or focal preset button), AE Lock (*), Optical Bokeh & Pro Controls
 */
@Composable
fun Dslr6dCameraScreen(
    state: CameraSettingsState,
    hasCameraPermission: Boolean,
    displayRotation: Int,
    sensorAspect: Float,
    capturedUris: List<String>,
    playbackItems: List<PlaybackPhotoItem> = emptyList(),
    hardwareInfoSummary: String,
    rollAngleDeg: Float = 0f,
    isPassiveFocused: Boolean = true,
    onRequestPermission: () -> Unit,
    onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    onHistogramUpdated: (FloatArray) -> Unit,
    onAutoAfPointsUpdated: (List<Int>) -> Unit = {},
    onTapToFocus: (Float, Float) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onCycleFocalPreset: () -> Unit,
    onToggleAeLock: () -> Unit,
    onHalfPressAf: () -> Unit,
    onAfButtonClick: () -> Unit = onHalfPressAf,
    onEnableAutoAfSelection: () -> Unit = {},
    onReleaseHalfPress: () -> Unit,
    onShutterFire: (Boolean) -> Unit,
    onStepDial: (Int) -> Unit,
    onStepModeDial: (Int) -> Unit,
    onDirectionalClick: (Int) -> Unit,
    onSetClick: () -> Unit,
    onSelectFlashMode: ((FlashMode) -> Unit)? = null,
    onUpdateState: ((CameraSettingsState) -> CameraSettingsState) -> Unit,
    onDeleteUri: (String) -> Unit
) {
    CameraBodyLeatheretteBackground(
        modifier = Modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // =========================================================================
            // ZONE 1 (LEFT THUMB): Power Lever, Mode Dial, MENU/INFO, Flash Lever, P.STYLE, ▶, 🗑
            // =========================================================================
            Column(
                modifier = Modifier
                    .width(98.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DslrColors.PanelGlassBg)
                    .border(1.dp, Color(0x55434957), RoundedCornerShape(16.dp))
                    .padding(vertical = 6.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                PowerSwitchLever(
                    isOn = state.isCameraPoweredOn,
                    onToggle = {
                        onUpdateState {
                            it.copy(
                                isCameraPoweredOn = !it.isCameraPoweredOn,
                                isQMenuOpen = false,
                                isMenuOpen = false,
                                isPlaybackOpen = false
                            )
                        }
                    }
                )

                ModeDialTurret(
                    mode = state.exposureMode,
                    onSelectNext = onStepModeDial
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DslrRoundButton(
                        size = 31.dp,
                        label = "MENU",
                        isActive = state.isMenuOpen,
                        onClick = {
                            onUpdateState {
                                it.copy(isMenuOpen = !it.isMenuOpen, isQMenuOpen = false, isPlaybackOpen = false)
                            }
                        }
                    )
                    DslrRoundButton(
                        size = 31.dp,
                        label = "INFO",
                        onClick = {
                            onUpdateState {
                                it.copy(
                                    infoDisplayMode = it.infoDisplayMode.next(),
                                    isMenuOpen = false,
                                    isPlaybackOpen = false
                                )
                            }
                        }
                    )
                }

                FlashSwitchLever(
                    flashMode = state.flashMode,
                    onSelectMode = { mode ->
                        onSelectFlashMode?.invoke(mode) ?: onUpdateState { it.copy(flashMode = mode) }
                    }
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DslrRoundButton(
                        size = 31.dp,
                        label = state.pictureStyle.shortCode,
                        isActive = state.activeDialTarget == ActiveDialTarget.PICTURE_STYLE,
                        onClick = {
                            val entries = PictureStyle.entries
                            val next = entries[(state.pictureStyle.ordinal + 1) % entries.size]
                            onUpdateState { it.copy(pictureStyle = next, activeDialTarget = ActiveDialTarget.PICTURE_STYLE) }
                        }
                    )
                    Text("P.STYLE", fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, color = DslrColors.LegendMuted)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        DslrRoundButton(
                            size = 31.dp,
                            isActive = state.isPlaybackOpen,
                            activeAccent = DslrColors.LegendBlue,
                            onClick = {
                                onUpdateState {
                                    it.copy(isPlaybackOpen = !it.isPlaybackOpen, isMenuOpen = false, isQMenuOpen = false)
                                }
                            }
                        ) {
                            PlaybackTriangleGlyph(color = DslrColors.LegendBlue)
                        }
                        if (state.pendingSaveCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(1.dp)
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF2828))
                                    .border(0.6.dp, Color(0xFF140606), CircleShape)
                            )
                        }
                    }
                    DslrRoundButton(
                        size = 31.dp,
                        onClick = {
                            onUpdateState { it.copy(isPlaybackOpen = true, isMenuOpen = false, isQMenuOpen = false) }
                        }
                    ) {
                        TrashBinGlyph(color = DslrColors.LegendBlue)
                    }
                }
            }

            // =========================================================================
            // ZONE 2 (CENTER CORE): Official Logo Prism + Top LCD (Screen #1) + 3:2 Monitor (Screen #2)
            // =========================================================================
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                DigitalMangoPrismHeader(
                    isPoweredOn = state.isCameraPoweredOn,
                    zoomRatio = state.zoomRatio,
                    apertureF = state.simulatedApertureF,
                    isAeLocked = state.isAeLocked,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(3.dp))

                TopShoulderSegmentLcd(
                    state = state,
                    onSelectDialTarget = { target ->
                        onUpdateState { it.copy(activeDialTarget = target) }
                    },
                    onToggleBacklight = {
                        onUpdateState { it.copy(topLcdAmberBacklight = !it.topLcdAmberBacklight) }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    RecessedViewfinderMonitor(
                        state = state,
                        hasCameraPermission = hasCameraPermission,
                        displayRotation = displayRotation,
                        sensorAspect = sensorAspect,
                        capturedUris = capturedUris,
                        playbackItems = playbackItems,
                        hardwareInfoSummary = hardwareInfoSummary,
                        rollAngleDeg = rollAngleDeg,
                        isPassiveFocused = isPassiveFocused,
                        onRequestPermission = onRequestPermission,
                        onSurfaceTextureReady = onSurfaceTextureReady,
                        onHistogramUpdated = onHistogramUpdated,
                        onAutoAfPointsUpdated = onAutoAfPointsUpdated,
                        onTapToFocus = onTapToFocus,
                        onPinchZoom = onPinchZoom,
                        onUpdateState = onUpdateState,
                        onDeleteUri = onDeleteUri,
                        modifier = Modifier.fillMaxHeight()
                    )
                }
            }

            // =========================================================================
            // ZONE 3 (RIGHT THUMB GRIP): AF-ON, *, Zoom, Shutter, Q, Tv/Av/ISO/ZM, Rotating Wheel, LOCK
            // =========================================================================
            LeatheretteGripBox(
                modifier = Modifier
                    .width(158.dp)
                    .fillMaxHeight()
                    .padding(1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                DslrRoundButton(
                                    size = 30.dp,
                                    label = "AF",
                                    isActive = state.isHalfPressedAfLocked || state.isAfHunting,
                                    activeAccent = if (state.isAfHunting) DslrColors.VfRedAf else DslrColors.VfGreenLock,
                                    onClick = onAfButtonClick,
                                    onLongClick = onEnableAutoAfSelection
                                )
                                DslrRoundButton(
                                    size = 30.dp,
                                    label = "*",
                                    isActive = state.isAeLocked,
                                    activeAccent = DslrColors.MangoGold,
                                    onClick = onToggleAeLock
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                DslrRoundButton(
                                    size = 30.dp,
                                    isActive = state.zoomRatio > 1.04f || state.activeDialTarget == ActiveDialTarget.LENS_ZOOM,
                                    activeAccent = DslrColors.MangoGold,
                                    onClick = onCycleFocalPreset
                                ) {
                                    ZoomMagnifierGlyph(
                                        color = if (state.zoomRatio > 1.04f) DslrColors.MangoGold else DslrColors.LegendBlue
                                    )
                                }
                                DslrRoundButton(
                                    size = 30.dp,
                                    label = if (state.liveViewZoomFactor > 1.1f) "${state.liveViewZoomFactor.toInt()}x" else "MF",
                                    isActive = state.liveViewZoomFactor > 1.1f,
                                    activeAccent = DslrColors.LegendBlue,
                                    onClick = {
                                        val nextZoom = when {
                                            state.liveViewZoomFactor < 2f -> 5f
                                            state.liveViewZoomFactor < 8f -> 10f
                                            else -> 1f
                                        }
                                        onUpdateState { it.copy(liveViewZoomFactor = nextZoom) }
                                    }
                                )
                            }
                        }

                        TwoStageShutterButton(
                            isHalfPressed = state.isHalfPressedAfLocked,
                            isCapturing = state.isCapturing,
                            onHalfPress = onHalfPressAf,
                            onReleaseHalfPress = onReleaseHalfPress,
                            onShutterFire = onShutterFire
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DslrQButton(
                            isActive = state.isQMenuOpen,
                            onClick = {
                                onUpdateState {
                                    it.copy(isQMenuOpen = !it.isQMenuOpen, isMenuOpen = false, isPlaybackOpen = false)
                                }
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            DialQuickPill(
                                label = "Tv",
                                active = state.activeDialTarget == ActiveDialTarget.SHUTTER,
                                onClick = { onUpdateState { it.copy(activeDialTarget = ActiveDialTarget.SHUTTER) } },
                                modifier = Modifier.weight(1f)
                            )
                            DialQuickPill(
                                label = "Av",
                                active = state.activeDialTarget == ActiveDialTarget.APERTURE,
                                onClick = { onUpdateState { it.copy(activeDialTarget = ActiveDialTarget.APERTURE) } },
                                modifier = Modifier.weight(1f)
                            )
                            DialQuickPill(
                                label = "ISO",
                                active = state.activeDialTarget == ActiveDialTarget.ISO,
                                onClick = { onUpdateState { it.copy(activeDialTarget = ActiveDialTarget.ISO) } },
                                modifier = Modifier.weight(1f)
                            )
                            DialQuickPill(
                                label = "ZM",
                                active = state.activeDialTarget == ActiveDialTarget.LENS_ZOOM,
                                onClick = { onUpdateState { it.copy(activeDialTarget = ActiveDialTarget.LENS_ZOOM) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Text(
                        text = activeDialReadout(state),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DslrColors.MangoGoldBright,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xAA0A0C10))
                            .border(0.7.dp, DslrColors.ChassisHairline, RoundedCornerShape(4.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )

                    RotaryQuickControlWheel(
                        isLocked = state.isMultiControlLocked,
                        onStepDial = onStepDial,
                        onDirectionalClick = onDirectionalClick,
                        onSetClick = onSetClick
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (state.opticalBokehEnabled) "BOKEH" else "STD",
                            fontSize = 7.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (state.opticalBokehEnabled) DslrColors.VfEmeraldText else DslrColors.LegendMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF0C0D11))
                                .border(0.8.dp, DslrColors.ChassisHairline, RoundedCornerShape(6.dp))
                                .clickable { onUpdateState { it.copy(opticalBokehEnabled = !it.opticalBokehEnabled) } }
                                .padding(horizontal = 6.dp, vertical = 2.5.dp)
                        )
                        CardAccessLamp(pendingCount = state.pendingSaveCount)
                        LockSwitchLever(
                            isLocked = state.isMultiControlLocked,
                            onToggle = {
                                onUpdateState { it.copy(isMultiControlLocked = !it.isMultiControlLocked) }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Canon EOS 6D Red SD-Card Access Lamp: glows & pulses while capturing/processing/writing to card.
 */
@Composable
private fun CardAccessLamp(pendingCount: Int) {
    val isBusy = pendingCount > 0
    val infinite = rememberInfiniteTransition(label = "cardAccessLed")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 280),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cardAccessAlpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .then(
                    if (isBusy) Modifier.shadow(4.dp, CircleShape, ambientColor = Color(0xFFFF2828), spotColor = Color(0xFFFF2828))
                    else Modifier
                )
                .clip(CircleShape)
                .background(if (isBusy) Color(0xFFFF2828).copy(alpha = pulseAlpha) else Color(0xFF2B1214))
                .border(0.6.dp, Color(0xFF0A0B0E), CircleShape)
        )
        if (isBusy) {
            Text(
                text = if (pendingCount > 1) "$pendingCount" else "SD",
                fontSize = 6.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF6868),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun DialQuickPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) DslrColors.MangoGold else Color(0xFF1E222A))
            .border(0.8.dp, if (active) DslrColors.MangoGoldBright else Color(0xFF3F4654), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 7.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (active) Color.Black else DslrColors.LegendWhite,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun activeDialReadout(state: CameraSettingsState): String {
    return when (state.activeDialTarget) {
        ActiveDialTarget.SHUTTER -> "TV ${DslrStops.formatShutter(state.shutterSpeedNs)}"
        ActiveDialTarget.APERTURE -> "AV ${DslrStops.formatAperture(state.simulatedApertureF)}"
        ActiveDialTarget.ISO -> "ISO ${DslrStops.formatIso(state.iso)}"
        ActiveDialTarget.EXPOSURE_COMP -> "EV ${DslrStops.formatEv(state.evCompensation)}"
        ActiveDialTarget.LENS_ZOOM -> "${DslrStops.formatFocalLength35mm(state.zoomRatio)} (${DslrStops.formatZoom(state.zoomRatio)})"
        ActiveDialTarget.WHITE_BALANCE -> "WB ${if (state.wbPreset.code == "K") "${state.customKelvin}K" else state.wbPreset.code}"
        ActiveDialTarget.PICTURE_STYLE -> "STYLE ${state.pictureStyle.title}"
        ActiveDialTarget.AF_MODE -> "AF ${state.afMode.lcdLabel}"
        ActiveDialTarget.DRIVE_MODE -> "DRIVE ${state.driveMode.badge}"
        ActiveDialTarget.METERING_MODE -> "MTR ${state.meteringMode.symbol}"
        ActiveDialTarget.MANUAL_FOCUS -> "MF %.1fd".format(state.manualFocusDiopter)
    }
}
