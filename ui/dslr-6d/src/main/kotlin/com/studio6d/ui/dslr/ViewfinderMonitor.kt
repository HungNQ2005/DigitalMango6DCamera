package com.studio6d.ui.dslr

import android.graphics.SurfaceTexture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.studio6d.core.lut.DslrGlSurfaceView
import com.studio6d.core.model.AfMode
import com.studio6d.core.model.AfPoints6D
import com.studio6d.core.model.CameraSettingsState
import com.studio6d.core.model.DslrStops
import com.studio6d.core.model.FlashMode
import com.studio6d.core.model.GridMode
import com.studio6d.core.model.InfoDisplayMode
import com.studio6d.core.model.MeteringMode
import com.studio6d.core.model.PictureStyle
import com.studio6d.core.model.PlaybackPhotoItem
import com.studio6d.core.model.WhiteBalancePreset
import kotlinx.coroutines.delay
import kotlin.math.hypot

/**
 * Precision Recessed Small 3:2 Monitor & Optical Viewfinder HUD.
 * Supports 1-finger Tap-to-Focus (+ bokeh subject shift) and 2-finger Pinch-to-Zoom,
 * with `designed by HungNQ2005` etched on the lower-right bezel chin.
 */
@Composable
fun RecessedViewfinderMonitor(
    state: CameraSettingsState,
    hasCameraPermission: Boolean,
    displayRotation: Int,
    sensorAspect: Float,
    capturedUris: List<String>,
    playbackItems: List<PlaybackPhotoItem> = emptyList(),
    hardwareInfoSummary: String,
    rollAngleDeg: Float,
    isPassiveFocused: Boolean = true,
    onRequestPermission: () -> Unit,
    onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    onHistogramUpdated: (FloatArray) -> Unit,
    onAutoAfPointsUpdated: (List<Int>) -> Unit = {},
    onTapToFocus: (Float, Float) -> Unit,
    onPinchZoom: (Float) -> Unit,
    onUpdateState: ((CameraSettingsState) -> CameraSettingsState) -> Unit,
    onDeleteUri: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activePoint = AfPoints6D.getPoint(state.selectedAfPointIndex)
    val afFlashAlpha = remember { Animatable(0f) }
    val shutterFlashAlpha = remember { Animatable(0f) }
    var afFailedBlink by remember { mutableStateOf(false) }

    LaunchedEffect(state.afFocusPulseSeq) {
        if (state.afFocusPulseSeq > 0) {
            afFlashAlpha.snapTo(1f)
            afFlashAlpha.animateTo(0.25f, animationSpec = tween(durationMillis = 480))
            delay(260)
            if (!state.isHalfPressedAfLocked) {
                afFlashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 220))
            }
        }
    }

    LaunchedEffect(state.afFocusFailedSeq) {
        if (state.afFocusFailedSeq > 0) {
            repeat(3) {
                afFailedBlink = true
                delay(95L)
                afFailedBlink = false
                delay(85L)
            }
        }
    }

    LaunchedEffect(state.isHalfPressedAfLocked) {
        if (state.isHalfPressedAfLocked) {
            afFlashAlpha.snapTo(0.95f)
        } else {
            afFlashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 200))
        }
    }

    LaunchedEffect(state.shutterReflexSeq) {
        if (state.shutterReflexSeq > 0) {
            try {
                shutterFlashAlpha.snapTo(0.62f)
                shutterFlashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 85))
            } finally {
                shutterFlashAlpha.snapTo(0f)
            }
        }
    }

    Column(
        modifier = modifier
            .shadow(10.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF15181E), Color(0xFF090A0D))
                )
            )
            .border(1.2.dp, DslrColors.ChassisHairline, RoundedCornerShape(14.dp))
            .padding(start = 7.dp, end = 7.dp, top = 7.dp, bottom = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f, fill = true)
                .aspectRatio(1.5f)
                .clip(RoundedCornerShape(7.dp))
                .background(DslrColors.MonitorBezelInner)
                .border(1.dp, DslrColors.MonitorGlassEdge, RoundedCornerShape(7.dp))
                .padding(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black)
            ) {
                if (!hasCameraPermission) {
                    CameraPermissionScreen(onRequestPermission = onRequestPermission)
                } else {
                    var viewSize by remember { mutableStateOf(IntSize(1, 1)) }
                    val focusPeaking = state.afMode == AfMode.MANUAL

                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewSize = it }
                            .pointerInput(state.isCameraPoweredOn, state.isMenuOpen, state.isQMenuOpen, state.isPlaybackOpen) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val startPos = down.position
                                    var didMultiTouch = false
                                    var maxTravel = 0f

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressedChanges = event.changes.filter { it.pressed }
                                        if (pressedChanges.isEmpty()) break

                                        if (pressedChanges.size >= 2) {
                                            didMultiTouch = true
                                            val zoomDelta = event.calculateZoom()
                                            if (state.isCameraPoweredOn && !state.isMenuOpen && !state.isQMenuOpen && !state.isPlaybackOpen) {
                                                if (kotlin.math.abs(zoomDelta - 1f) > 0.005f) {
                                                    onPinchZoom(zoomDelta)
                                                }
                                            }
                                            event.changes.forEach { it.consume() }
                                        } else {
                                            val p = pressedChanges[0].position
                                            val dist = hypot(p.x - startPos.x, p.y - startPos.y)
                                            if (dist > maxTravel) maxTravel = dist
                                        }
                                    }

                                    if (!didMultiTouch && maxTravel < viewConfiguration.touchSlop) {
                                        if (state.isCameraPoweredOn && !state.isMenuOpen && !state.isQMenuOpen && !state.isPlaybackOpen) {
                                            val nx = (startPos.x / viewSize.width.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
                                            val ny = (startPos.y / viewSize.height.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
                                            onTapToFocus(nx, ny)
                                        }
                                    }
                                }
                            },
                        factory = { ctx ->
                            DslrGlSurfaceView(
                                context = ctx,
                                onSurfaceTextureReady = onSurfaceTextureReady,
                                onHistogramUpdated = onHistogramUpdated,
                                onAutoAfPointsUpdated = onAutoAfPointsUpdated
                            ).apply {
                                applyStyleAndParams(
                                    style = state.pictureStyle,
                                    lutIntensity = state.lutIntensity,
                                    highlightRolloff = state.highlightRolloff,
                                    vignetteStrength = state.simulatedVignetteStrength,
                                    grainStrength = state.simulatedGrainStrength,
                                    bokehStrength = state.opticalBokehStrength,
                                    focusCenterX = state.customFocusNormX,
                                    focusCenterY = state.customFocusNormY,
                                    isSubjectFocused = (state.isSubjectFocused && state.isHalfPressedAfLocked) || state.afMode == AfMode.MANUAL,
                                    isAutoAfSelection = state.isAutoAfSelection,
                                    lensFocusDiopters = state.lensFocusDiopters,
                                    contrastOffset = state.contrastOffset,
                                    saturationOffset = state.saturationOffset,
                                    colorToneOffset = state.colorToneOffset,
                                    highlightAlertEnabled = state.highlightAlertEnabled,
                                    focusPeaking = focusPeaking,
                                    displayRotation = displayRotation,
                                    sensorAspect = sensorAspect,
                                    zoomFactor = state.liveViewZoomFactor,
                                    zoomCenterX = activePoint.normX,
                                    zoomCenterY = activePoint.normY
                                )
                            }
                        },
                        update = { glView ->
                            glView.applyStyleAndParams(
                                style = state.pictureStyle,
                                lutIntensity = state.lutIntensity,
                                highlightRolloff = state.highlightRolloff,
                                vignetteStrength = state.simulatedVignetteStrength,
                                grainStrength = state.simulatedGrainStrength,
                                bokehStrength = state.opticalBokehStrength,
                                focusCenterX = state.customFocusNormX,
                                focusCenterY = state.customFocusNormY,
                                isSubjectFocused = (state.isSubjectFocused && state.isHalfPressedAfLocked) || state.afMode == AfMode.MANUAL,
                                isAutoAfSelection = state.isAutoAfSelection,
                                lensFocusDiopters = state.lensFocusDiopters,
                                contrastOffset = state.contrastOffset,
                                saturationOffset = state.saturationOffset,
                                colorToneOffset = state.colorToneOffset,
                                highlightAlertEnabled = state.highlightAlertEnabled,
                                focusPeaking = focusPeaking,
                                displayRotation = displayRotation,
                                sensorAspect = sensorAspect,
                                zoomFactor = state.liveViewZoomFactor,
                                zoomCenterX = activePoint.normX,
                                zoomCenterY = activePoint.normY
                            )
                        }
                    )

                    if (!state.isCameraPoweredOn) {
                        CameraPoweredOffScreen()
                    } else {
                        val anyOverlayOpen = state.isMenuOpen || state.isQMenuOpen || state.isPlaybackOpen ||
                            state.infoDisplayMode == InfoDisplayMode.SHOOTING_INFO_SCREEN

                        if (!anyOverlayOpen) {
                            ViewfinderReticleCanvas(
                                activeAfIndices = state.activeAfPointIndices,
                                primaryAfIndex = state.selectedAfPointIndex,
                                redFlashAlpha = afFlashAlpha.value,
                                isAfHunting = state.isAfHunting,
                                isAfLockedGreen = state.isHalfPressedAfLocked && state.isSubjectFocused,
                                showOverlays = state.infoDisplayMode == InfoDisplayMode.LIVE_VIEW_OVERLAYS,
                                gridMode = state.gridMode,
                                meteringMode = state.meteringMode,
                                rollAngleDeg = rollAngleDeg,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (!anyOverlayOpen && state.infoDisplayMode == InfoDisplayMode.LIVE_VIEW_OVERLAYS) {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(7.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                LiveHistogramBox(bins = state.histogramBins)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (state.zoomRatio > 1.04f || state.zoomRatio < 0.96f) {
                                        BadgePill(
                                            text = "${DslrStops.formatFocalLength35mm(state.zoomRatio)} · ${DslrStops.formatZoom(state.zoomRatio)}",
                                            accent = DslrColors.LegendWhite
                                        )
                                    }
                                    if (state.liveViewZoomFactor > 1.1f) {
                                        BadgePill(
                                            text = "MAG ${state.liveViewZoomFactor.toInt()}x",
                                            accent = DslrColors.MangoGold
                                        )
                                    }
                                }
                            }
                        }

                        if (!anyOverlayOpen) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 7.dp, top = 7.dp, end = 86.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                BadgePill(
                                    text = "[${state.pictureStyle.shortCode}] ${state.pictureStyle.title}",
                                    accent = DslrColors.MangoGold
                                )
                                if (state.opticalBokehEnabled && state.opticalBokehStrength > 0.05f) {
                                    BadgePill(
                                        text = "${DslrStops.formatAperture(state.simulatedApertureF)} BOKEH",
                                        accent = DslrColors.VfEmeraldText
                                    )
                                }
                                if (state.aebEnabled) {
                                    BadgePill(text = "AEB ±1", accent = DslrColors.LegendBlue)
                                }
                                if (state.afMode == AfMode.MANUAL) {
                                    BadgePill(text = "MF PEAKING", accent = DslrColors.VfRedAf)
                                }
                            }
                        }

                        if (!anyOverlayOpen) {
                            ViewfinderBottomTelemetryStrip(
                                state = state,
                                afFailedBlink = afFailedBlink,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                            )
                        }

                        if (shutterFlashAlpha.value > 0.01f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = shutterFlashAlpha.value))
                            )
                        }

                        when {
                            state.isPlaybackOpen -> {
                                InBodyPlaybackOverlay(
                                    items = playbackItems,
                                    onClose = { onUpdateState { it.copy(isPlaybackOpen = false) } },
                                    onDeleteUri = onDeleteUri
                                )
                            }
                            state.isMenuOpen -> {
                                CameraMenuOverlay(
                                    state = state,
                                    hardwareInfoSummary = hardwareInfoSummary,
                                    onUpdateState = onUpdateState,
                                    onClose = { onUpdateState { it.copy(isMenuOpen = false) } }
                                )
                            }
                            state.isQMenuOpen -> {
                                QuickControlQOverlay(
                                    state = state,
                                    onUpdateState = onUpdateState,
                                    onClose = { onUpdateState { it.copy(isQMenuOpen = false) } }
                                )
                            }
                            state.infoDisplayMode == InfoDisplayMode.SHOOTING_INFO_SCREEN -> {
                                RearShootingInfoScreen(
                                    state = state,
                                    onSelectTarget = { target ->
                                        onUpdateState { it.copy(activeDialTarget = target) }
                                    }
                                )
                            }
                        }

                        if (!anyOverlayOpen) {
                            state.statusBanner?.let { msg ->
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 32.dp, start = 14.dp, end = 14.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xDC080A0F))
                                        .border(0.8.dp, DslrColors.MangoGold.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = msg,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = DslrColors.LegendWhite,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dedicated Lower Anodized Bezel Chin with Designer Signature
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 6.dp, end = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CLEAR VIEW II · 3:2",
                fontSize = 7.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.7.sp,
                color = Color(0xFF6E7585)
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Normal,
                            fontSize = 8.5.sp,
                            color = Color(0xFF9AA2B1)
                        )
                    ) {
                        append("designed by ")
                    }
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 8.5.sp,
                            letterSpacing = 0.6.sp,
                            color = DslrColors.MangoGold
                        )
                    ) {
                        append("HungNQ2005")
                    }
                }
            )
        }
    }
}

@Composable
private fun ViewfinderReticleCanvas(
    activeAfIndices: List<Int>,
    primaryAfIndex: Int,
    redFlashAlpha: Float,
    isAfHunting: Boolean,
    isAfLockedGreen: Boolean,
    showOverlays: Boolean,
    gridMode: GridMode,
    meteringMode: MeteringMode,
    rollAngleDeg: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        if (showOverlays) {
            val gridColor = Color(0x3DFFFFFF)
            when (gridMode) {
                GridMode.THIRDS_3X3 -> {
                    drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = 0.9f)
                    drawLine(gridColor, Offset(2f * w / 3f, 0f), Offset(2f * w / 3f, h), strokeWidth = 0.9f)
                    drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = 0.9f)
                    drawLine(gridColor, Offset(0f, 2f * h / 3f), Offset(w, 2f * h / 3f), strokeWidth = 0.9f)
                }
                GridMode.GOLDEN_RATIO -> {
                    val a = 0.382f
                    val b = 0.618f
                    drawLine(gridColor, Offset(w * a, 0f), Offset(w * a, h), strokeWidth = 0.9f)
                    drawLine(gridColor, Offset(w * b, 0f), Offset(w * b, h), strokeWidth = 0.9f)
                    drawLine(gridColor, Offset(0f, h * a), Offset(w, h * a), strokeWidth = 0.9f)
                    drawLine(gridColor, Offset(0f, h * b), Offset(w, h * b), strokeWidth = 0.9f)
                }
                GridMode.CROSSHAIR -> {
                    drawLine(gridColor, Offset(w * 0.5f, 0f), Offset(w * 0.5f, h), strokeWidth = 0.9f)
                    drawLine(gridColor, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 0.9f)
                }
                GridMode.OFF -> Unit
            }

            // Electronic dual-axis horizon level
            val levelY = h * 0.5f
            val levelHalf = w * 0.16f
            val isLevel = kotlin.math.abs(rollAngleDeg) < 1.5f
            val levelColor = if (isLevel) DslrColors.VfGreenLock.copy(alpha = 0.75f) else Color(0x88F2B857)
            val dy = kotlin.math.sin(Math.toRadians(rollAngleDeg.coerceIn(-20f, 20f).toDouble())).toFloat() * levelHalf
            drawLine(
                color = levelColor,
                start = Offset(w * 0.5f - levelHalf, levelY - dy),
                end = Offset(w * 0.5f - w * 0.055f, levelY - dy * 0.35f),
                strokeWidth = 1.5f
            )
            drawLine(
                color = levelColor,
                start = Offset(w * 0.5f + w * 0.055f, levelY + dy * 0.35f),
                end = Offset(w * 0.5f + levelHalf, levelY + dy),
                strokeWidth = 1.5f
            )
        }

        // Canon 6D center metering zone: 3.5% Spot circle vs 8% Partial dashed circle
        if (meteringMode == MeteringMode.SPOT) {
            drawCircle(
                color = Color(0x66FFFFFF),
                radius = h * 0.048f,
                center = Offset(w * 0.5f, h * 0.5f),
                style = Stroke(width = 1.1f)
            )
        } else {
            drawCircle(
                color = Color(0x4DFFFFFF),
                radius = h * 0.095f,
                center = Offset(w * 0.5f, h * 0.5f),
                style = Stroke(
                    width = 1.0f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))
                )
            )
        }

        val cx = w * 0.5f
        val cy = h * 0.5f
        val crossArm = 7.dp.toPx()
        drawLine(Color(0xA6FFFFFF), Offset(cx - crossArm, cy), Offset(cx + crossArm, cy), strokeWidth = 1.1f)
        drawLine(Color(0xA6FFFFFF), Offset(cx, cy - crossArm), Offset(cx, cy + crossArm), strokeWidth = 1.1f)

        val activeSet = activeAfIndices.ifEmpty { listOf(primaryAfIndex) }
        for (pt in AfPoints6D.points) {
            val px = pt.normX * w
            val py = pt.normY * h
            val boxW = if (pt.isCrossType) 26.dp.toPx() else 20.dp.toPx()
            val boxH = if (pt.isCrossType) 20.dp.toPx() else 15.dp.toPx()
            val isPrimary = pt.index == primaryAfIndex
            val isActiveCandidate = pt.index in activeSet || isPrimary

            if (isAfLockedGreen && isActiveCandidate) {
                // Locked subject box(es): brilliant Canon green LED confirmation across all matched AF points
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            DslrColors.VfGreenLock.copy(alpha = if (isPrimary) 0.72f else 0.54f),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = boxW * 1.55f
                    ),
                    radius = boxW * 1.55f,
                    center = Offset(px, py)
                )
                drawRect(
                    color = DslrColors.VfGreenLock,
                    topLeft = Offset(px - boxW / 2f, py - boxH / 2f),
                    size = Size(boxW, boxH),
                    style = Stroke(width = if (isPrimary) 2.7f else 2.1f)
                )
                drawCircle(
                    color = DslrColors.VfGreenLock,
                    radius = if (isPrimary) 4.2.dp.toPx() else 2.8.dp.toPx(),
                    center = Offset(px, py)
                )
            } else if (!isAfLockedGreen && isActiveCandidate) {
                // Idle or AF Hunting: Canon Superimposed RED multi-point illumination on detected subjects
                val glowAlpha = if (isAfHunting) maxOf(redFlashAlpha, 0.92f) else 0.42f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            DslrColors.VfRedAf.copy(alpha = 0.56f * glowAlpha),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = boxW * (if (isAfHunting) 1.50f else 1.22f)
                    ),
                    radius = boxW * (if (isAfHunting) 1.50f else 1.22f),
                    center = Offset(px, py)
                )
                drawRect(
                    color = DslrColors.VfRedAf.copy(alpha = if (isAfHunting) 1.0f else if (isPrimary) 0.92f else 0.78f),
                    topLeft = Offset(px - boxW / 2f, py - boxH / 2f),
                    size = Size(boxW, boxH),
                    style = Stroke(width = if (isAfHunting) 2.4f else 1.8f)
                )
                drawCircle(
                    color = DslrColors.VfRedAf.copy(alpha = if (isAfHunting) 0.96f else 0.75f),
                    radius = if (isAfHunting) 3.8.dp.toPx() else 2.4.dp.toPx(),
                    center = Offset(px, py)
                )
            } else {
                drawRect(
                    color = Color(0x38FFFFFF),
                    topLeft = Offset(px - boxW / 2f, py - boxH / 2f),
                    size = Size(boxW, boxH),
                    style = Stroke(width = 1f)
                )
                drawCircle(
                    color = Color(0x35FFFFFF),
                    radius = 1.1.dp.toPx(),
                    center = Offset(px, py)
                )
            }
        }
    }
}

@Composable
private fun ViewfinderBottomTelemetryStrip(
    state: CameraSettingsState,
    afFailedBlink: Boolean = false,
    modifier: Modifier = Modifier
) {
    val displayedShutterNs = if (state.exposureMode.allowsManualShutter) {
        state.shutterSpeedNs
    } else {
        val rawNs = if (state.flashMode == FlashMode.ON || state.flashMode == FlashMode.AUTO) {
            maxOf(state.liveMeteredShutterNs, 5_555_556L)
        } else {
            state.liveMeteredShutterNs
        }
        DslrStops.nearestShutterStop(rawNs)
    }
    val isAutoIsoLive = state.iso <= 0 && (state.isHalfPressedAfLocked || state.isAfHunting)
    val isoText = if (isAutoIsoLive) {
        "ISO ${DslrStops.nearestIsoStop(state.liveMeteredIso)}A"
    } else {
        "ISO ${DslrStops.formatIso(state.iso, compact = true)}"
    }
    val showWarningBadge = state.pictureStyle == PictureStyle.MONOCHROME ||
        state.meteringMode == MeteringMode.SPOT ||
        state.wbPreset != WhiteBalancePreset.AWB

    Row(
        modifier = modifier
            .background(DslrColors.VfHudBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.exposureMode.code,
                style = DslrType.vfTelemetry,
                color = DslrColors.MangoGold,
                maxLines = 1,
                softWrap = false
            )
            if (state.isAeLocked) {
                Text(
                    text = "*",
                    style = DslrType.vfTelemetry,
                    color = DslrColors.MangoGoldBright,
                    maxLines = 1,
                    softWrap = false
                )
            }
            if (showWarningBadge) {
                Text(
                    text = "[!]",
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DslrColors.MangoGold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        Text(
            text = DslrStops.formatShutter(displayedShutterNs, compactTopLcd = true),
            style = DslrType.vfTelemetry,
            color = DslrColors.VfEmeraldText,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = DslrStops.formatAperture(state.simulatedApertureF),
            style = DslrType.vfTelemetry,
            color = DslrColors.VfEmeraldText,
            maxLines = 1,
            softWrap = false
        )
        val ev = if (state.exposureMode.allowsExposureCompensation) state.evCompensation else state.measuredEvOffset
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (ev < -3.0f) "<3" else "-3",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = if (ev < -3.0f) DslrColors.MangoGoldBright else DslrColors.VfEmeraldText,
                maxLines = 1,
                softWrap = false
            )
            Canvas(modifier = Modifier.width(38.dp).height(10.dp)) {
                val mid = size.width / 2f
                drawLine(DslrColors.VfEmeraldText.copy(alpha = 0.4f), Offset(0f, size.height - 2f), Offset(size.width, size.height - 2f), 1.3f)
                for (i in -3..3) {
                    val x = mid + (i / 3f) * (size.width * 0.45f)
                    drawLine(DslrColors.VfEmeraldText, Offset(x, size.height - 5f), Offset(x, size.height - 1f), 1.2f)
                }
                val markerX = mid + (ev / 3f).coerceIn(-1f, 1f) * (size.width * 0.45f)
                drawRect(DslrColors.VfEmeraldText, topLeft = Offset(markerX - 2f, 0f), size = Size(4f, 5f))
            }
            Text(
                text = if (ev > 3.0f) "3>" else "+3",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = if (ev > 3.0f) DslrColors.MangoGoldBright else DslrColors.VfEmeraldText,
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = isoText,
            style = DslrType.vfTelemetry,
            color = DslrColors.VfEmeraldText,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = state.flashMode.badge,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (state.flashMode != FlashMode.OFF) DslrColors.MangoGoldBright else DslrColors.LegendMuted,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = if (state.saveRawDng) "[6]" else "[9]",
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = DslrColors.VfEmeraldText.copy(alpha = 0.85f),
            maxLines = 1,
            softWrap = false
        )
        val dotColor = when {
            afFailedBlink -> DslrColors.VfRedAf
            state.isHalfPressedAfLocked && state.isSubjectFocused -> DslrColors.VfGreenLock
            state.isAfHunting -> DslrColors.VfRedAf
            else -> Color(0xFF1A2920)
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
                .border(1.dp, DslrColors.VfGreenLock.copy(alpha = 0.5f), CircleShape)
        )
    }
}

@Composable
private fun LiveHistogramBox(bins: FloatArray) {
    Box(
        modifier = Modifier
            .size(width = 74.dp, height = 32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xAA080A0D))
            .border(0.8.dp, Color(0x55FFFFFF), RoundedCornerShape(4.dp))
            .padding(3.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barW = size.width / bins.size.coerceAtLeast(1)
            for (i in bins.indices) {
                val h = (bins[i].coerceIn(0f, 1f) * size.height).coerceAtLeast(1f)
                drawRect(
                    color = Color(0xCCEEF2F6),
                    topLeft = Offset(i * barW, size.height - h),
                    size = Size((barW - 0.6f).coerceAtLeast(1f), h)
                )
            }
        }
    }
}

@Composable
private fun BadgePill(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xB80A0C10))
            .border(0.8.dp, accent.copy(alpha = 0.65f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CameraPoweredOffScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF060709)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(
                painter = painterResource(id = R.drawable.img_digital_mango_logo),
                contentDescription = "Digital Mango 6D",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(26.dp)
                    .alpha(0.6f)
            )
            Text(
                text = "POWER OFF — SWITCH LEVER TO ON",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = Color(0xFF5D636E)
            )
        }
    }
}

@Composable
private fun CameraPermissionScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CAMERA ACCESS REQUIRED",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = DslrColors.MangoGold
            )
            Text(
                text = "Grant camera permission to activate the Digital Mango 6D Live View & manual Camera2 controls.",
                fontSize = 10.sp,
                color = DslrColors.LegendMuted,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DslrColors.MangoGold,
                    contentColor = Color.Black
                ),
                modifier = Modifier.height(34.dp)
            ) {
                Text("ENABLE CAMERA", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
