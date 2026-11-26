package com.studio6d.ui.dslr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studio6d.core.model.ActiveDialTarget
import com.studio6d.core.model.CameraSettingsState
import com.studio6d.core.model.DslrStops
import com.studio6d.core.model.ExposureMode
import com.studio6d.core.model.FlashMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Generates a seamless 96x96 organic pebbled vulcanite leatherette tile (cached once)
 * so the entire camera body has realistic Canon DSLR leather-wrapped tactile depth.
 */
private fun createPebbledLeatherTile(): ImageBitmap {
    val size = 96
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val grid = 6 // 6x6 organic Voronoi pebble cells -> ~16px pebbles
    val cellPx = size.toFloat() / grid
    val ptsX = FloatArray(grid * grid)
    val ptsY = FloatArray(grid * grid)

    var seed = 0x5A17D921
    for (gy in 0 until grid) {
        for (gx in 0 until grid) {
            seed = seed * 1664525 + 1013904223
            val jx = ((seed ushr 16) and 0xFF) / 255f
            seed = seed * 1664525 + 1013904223
            val jy = ((seed ushr 16) and 0xFF) / 255f
            val idx = gy * grid + gx
            ptsX[idx] = (gx + 0.18f + jx * 0.64f) * cellPx
            ptsY[idx] = (gy + 0.18f + jy * 0.64f) * cellPx
        }
    }

    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val cy = (y / cellPx).toInt()
        for (x in 0 until size) {
            val cx = (x / cellPx).toInt()
            var d1 = 1e9f
            var d2 = 1e9f
            var nearDx = 0f
            var nearDy = 0f

            for (oy in -1..1) {
                val gy = (cy + oy + grid) % grid
                val wrapY = when {
                    cy + oy < 0 -> -size.toFloat()
                    cy + oy >= grid -> size.toFloat()
                    else -> 0f
                }
                for (ox in -1..1) {
                    val gx = (cx + ox + grid) % grid
                    val wrapX = when {
                        cx + ox < 0 -> -size.toFloat()
                        cx + ox >= grid -> size.toFloat()
                        else -> 0f
                    }
                    val idx = gy * grid + gx
                    val dx = x - (ptsX[idx] + wrapX)
                    val dy = y - (ptsY[idx] + wrapY)
                    val dist = hypot(dx, dy)
                    if (dist < d1) {
                        d2 = d1
                        d1 = dist
                        nearDx = dx
                        nearDy = dy
                    } else if (dist < d2) {
                        d2 = dist
                    }
                }
            }

            // Crevice depth + top-left directional leather dome sheen
            val ridge = ((d2 - d1) / (cellPx * 0.55f)).coerceIn(0f, 1f)
            val dome = (1f - (d1 / (cellPx * 0.85f))).coerceIn(0f, 1f)
            val topLight = ((-nearDx * 0.45f - nearDy * 0.85f) / cellPx).coerceIn(-0.4f, 0.5f)
            val luma = (13f + ridge * 13f + dome * 7f + topLight * 11f).toInt().coerceIn(8, 42)
            pixels[y * size + x] = android.graphics.Color.rgb(luma, luma + 1, luma + 4)
        }
    }
    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    return bmp.asImageBitmap()
}

/**
 * Full-chassis luxury DSLR shell:
 * - Upper 23%: Satin-matte magnesium-polycarbonate shoulder deck with chamfer & micro-screws
 * - Lower 77%: Seamless organic pebbled vulcanite leatherette wrap with contoured grip shading
 */
@Composable
fun CameraBodyLeatheretteBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val leatherTile = remember { createPebbledLeatherTile() }
    val leatherBrush = remember(leatherTile) {
        ShaderBrush(ImageShader(leatherTile, TileMode.Repeated, TileMode.Repeated))
    }

    Box(modifier = modifier.background(DslrColors.LeatherBase)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val seamY = h * 0.23f

            // Lower 77%: Tiled pebbled leatherette wrap
            drawRect(
                brush = leatherBrush,
                topLeft = Offset(0f, seamY),
                size = Size(w, h - seamY)
            )
            // Subtle ergonomic lighting over leatherette (grip highlights + deep bezel shadow)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0x55000000), Color(0x14FFFFFF), Color(0x33000000), Color(0x1EFFFFFF), Color(0x66000000))
                ),
                topLeft = Offset(0f, seamY),
                size = Size(w, h - seamY)
            )

            // Upper 23%: Satin-matte polycarbonate top dome
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(DslrColors.PolyTop, DslrColors.PolyMid, DslrColors.PolyDeep),
                    startY = 0f,
                    endY = seamY
                ),
                topLeft = Offset(0f, 0f),
                size = Size(w, seamY)
            )
            // Specular top bevel line
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(0f, 1.5f),
                end = Offset(w, 1.5f),
                strokeWidth = 2f
            )

            // Machined polycarbonate-to-leatherette seam groove
            drawLine(Color(0xFF060709), Offset(0f, seamY), Offset(w, seamY), strokeWidth = 3.5f)
            drawLine(Color(0x28FFFFFF), Offset(0f, seamY + 2.5f), Offset(w, seamY + 2.5f), strokeWidth = 1.2f)

            // Subtle blackened precision chassis screws at corners
            val screwPositions = listOf(
                Offset(14.dp.toPx(), 10.dp.toPx()),
                Offset(w - 14.dp.toPx(), 10.dp.toPx()),
                Offset(14.dp.toPx(), h - 10.dp.toPx()),
                Offset(w - 14.dp.toPx(), h - 10.dp.toPx())
            )
            for (pos in screwPositions) {
                drawCircle(Color(0xFF0A0B0D), radius = 3.2.dp.toPx(), center = pos)
                drawCircle(Color(0xFF2D313A), radius = 2.1.dp.toPx(), center = pos, style = Stroke(0.9f))
            }
        }
        content()
    }
}

/**
 * Right-hand contoured leatherette grip bay overlaying the seamless camera leather.
 */
@Composable
fun LeatheretteGripBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0x660B0C10), Color(0x3316181F), Color(0x8807080B))
                )
            )
            .border(1.dp, Color(0x55434957), RoundedCornerShape(cornerRadius))
    ) {
        content()
    }
}

/**
 * Embossed Pentaprism Nameplate using the official Digital Mango 6D logo asset.
 */
@Composable
fun DigitalMangoPrismHeader(
    isPoweredOn: Boolean,
    zoomRatio: Float,
    apertureF: Float,
    isAeLocked: Boolean,
    baseFocal35mm: Float = 24f,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp, bottomStart = 5.dp, bottomEnd = 5.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF2B2F37), Color(0xFF17191E))
                )
            )
            .border(1.dp, DslrColors.ChassisHairline, RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp, bottomStart = 5.dp, bottomEnd = 5.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_digital_mango_logo),
            contentDescription = "Digital Mango 6D",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(20.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isAeLocked) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(DslrColors.MangoGold.copy(alpha = 0.18f))
                        .border(0.7.dp, DslrColors.MangoGold, RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 0.5.dp)
                ) {
                    Text(
                        text = "* AE-L",
                        fontSize = 7.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DslrColors.MangoGoldBright
                    )
                }
            }
            Text(
                text = "${DslrStops.formatFocalLength35mm(zoomRatio, baseFocal35mm)} · ${DslrStops.formatAperture(apertureF)}",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = DslrColors.LegendMuted
            )
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .clip(CircleShape)
                    .background(if (isPoweredOn) DslrColors.VfGreenLock else Color(0xFF454A55))
            )
        }
    }
}

/**
 * Screen #1 of the 2-Screen Layout: Authentic Canon 6D Top Shoulder Monochrome Segment LCD
 * with reflective olive-green tint and toggleable Amber EL Backlight (`💡`).
 */
@Composable
fun TopShoulderSegmentLcd(
    state: CameraSettingsState,
    onSelectDialTarget: (ActiveDialTarget) -> Unit,
    onToggleBacklight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (state.topLcdAmberBacklight) DslrColors.TopLcdAmberBg else DslrColors.TopLcdOffBg,
        animationSpec = tween(180),
        label = "TopLcdBg"
    )
    val borderColor = if (state.topLcdAmberBacklight) DslrColors.TopLcdAmberGlow else DslrColors.TopLcdOffEdge

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0E1014))
            .border(1.1.dp, DslrColors.ChassisHairline, RoundedCornerShape(8.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(5.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(5.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            if (!state.isCameraPoweredOn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("OFF", style = DslrType.lcdBig, color = DslrColors.TopLcdInkDim, maxLines = 1, softWrap = false)
                    Text("[${state.remainingShotsEstimate}]", style = DslrType.lcdSmall, color = DslrColors.TopLcdInk, maxLines = 1, softWrap = false)
                }
            } else {
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
                val displayedIsoText = if (state.iso <= 0 && (state.isHalfPressedAfLocked || state.isAfHunting)) {
                    "ISO ${DslrStops.nearestIsoStop(state.liveMeteredIso)} A"
                } else {
                    "ISO ${DslrStops.formatIso(state.iso, compact = true)}"
                }

                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LcdTargetChip(
                            text = DslrStops.formatShutter(displayedShutterNs, compactTopLcd = true),
                            selected = state.activeDialTarget == ActiveDialTarget.SHUTTER,
                            onClick = { onSelectDialTarget(ActiveDialTarget.SHUTTER) }
                        )
                        LcdTargetChip(
                            text = DslrStops.formatAperture(state.simulatedApertureF, includePrefix = true),
                            selected = state.activeDialTarget == ActiveDialTarget.APERTURE,
                            onClick = { onSelectDialTarget(ActiveDialTarget.APERTURE) }
                        )
                        LcdTargetChip(
                            text = displayedIsoText,
                            selected = state.activeDialTarget == ActiveDialTarget.ISO,
                            onClick = { onSelectDialTarget(ActiveDialTarget.ISO) }
                        )
                        Text(
                            text = state.afMode.lcdLabel,
                            style = DslrType.lcdSmall,
                            color = DslrColors.TopLcdInk,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "((o))",
                                fontSize = 6.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = DslrColors.TopLcdInkDim,
                                maxLines = 1,
                                softWrap = false
                            )
                            BatteryGlyph(color = DslrColors.TopLcdInk)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val meterEv = if (state.exposureMode.allowsExposureCompensation) state.evCompensation else state.measuredEvOffset
                        SegmentExposureBar(ev = meterEv, ink = DslrColors.TopLcdInk)
                        val flashLcd = if (state.flashMode != FlashMode.OFF) "${state.flashMode.badge} " else ""
                        Text(
                            text = "$flashLcd${state.driveMode.badge} ${state.meteringMode.symbol} ${state.wbPreset.code}",
                            style = DslrType.lcdSmall,
                            color = DslrColors.TopLcdInk,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (state.saveRawDng) "RAW+L [${state.remainingShotsEstimate}]" else "L [${state.remainingShotsEstimate}]",
                            style = DslrType.lcdSmall,
                            color = DslrColors.TopLcdInk,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(5.dp))

        DslrRoundButton(
            size = 28.dp,
            onClick = onToggleBacklight,
            isActive = state.topLcdAmberBacklight,
            activeAccent = DslrColors.TopLcdAmberBg
        ) {
            BulbGlyph(color = if (state.topLcdAmberBacklight) DslrColors.TopLcdAmberGlow else DslrColors.LegendWhite)
        }
    }
}

@Composable
private fun LcdTargetChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Color(0x33171913) else Color.Transparent)
            .border(if (selected) 1.dp else 0.dp, if (selected) DslrColors.TopLcdInk else Color.Transparent, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(text = text, style = DslrType.lcdBig, color = DslrColors.TopLcdInk, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SegmentExposureBar(ev: Float, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("-3..0..+3", style = DslrType.lcdSmall, color = ink, maxLines = 1, softWrap = false)
        Canvas(modifier = Modifier.width(42.dp).height(8.dp)) {
            val midX = size.width / 2f
            drawLine(ink.copy(alpha = 0.45f), Offset(0f, size.height - 2f), Offset(size.width, size.height - 2f), 1.5f)
            drawLine(ink, Offset(midX, 0f), Offset(midX, size.height), 1.8f)
            val norm = (ev / 3f).coerceIn(-1f, 1f)
            val markerX = midX + norm * (size.width * 0.45f)
            drawRect(ink, topLeft = Offset(markerX - 2f, 1f), size = Size(4f, 5f))
        }
    }
}

@Composable
fun DslrRoundButton(
    size: Dp = 36.dp,
    label: String? = null,
    labelColor: Color = DslrColors.LegendWhite,
    isActive: Boolean = false,
    activeAccent: Color = DslrColors.MangoGold,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    icon: (@Composable BoxScope.() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .shadow(if (pressed) 1.dp else 4.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(DslrColors.ButtonSurfaceBottom, DslrColors.ButtonSurfaceTop)
                    else listOf(DslrColors.ButtonSurfaceTop, DslrColors.ButtonSurfaceBottom)
                )
            )
            .border(
                width = if (isActive) 1.3.dp else 0.9.dp,
                color = if (isActive) activeAccent else DslrColors.ButtonBorder,
                shape = CircleShape
            )
            .pointerInput(onClick, onLongClick) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (onLongClick != null) {
                        var longFired = false
                        val up = withTimeoutOrNull(420L) {
                            waitForUpOrCancellation()
                        }
                        if (up == null) {
                            longFired = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                            waitForUpOrCancellation()
                        } else if (!longFired) {
                            onClick()
                        }
                    } else {
                        val up = waitForUpOrCancellation()
                        if (up != null) onClick()
                    }
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) {
            icon()
        } else if (label != null) {
            Text(
                text = label,
                style = DslrType.legend,
                color = if (isActive) activeAccent else labelColor
            )
        }
    }
}

@Composable
fun DslrQButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .size(width = 32.dp, height = 30.dp)
            .shadow(if (pressed) 1.dp else 4.dp, shape)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(DslrColors.ButtonSurfaceBottom, DslrColors.ButtonSurfaceTop)
                    else listOf(DslrColors.ButtonSurfaceTop, DslrColors.ButtonSurfaceBottom)
                )
            )
            .border(1.1.dp, if (isActive) DslrColors.MangoGold else DslrColors.ButtonBorder, shape)
            .pointerInput(onClick) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) onClick()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(1.3.dp, if (isActive) DslrColors.MangoGold else DslrColors.LegendWhite, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Q",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 10.sp,
                color = if (isActive) DslrColors.MangoGold else DslrColors.LegendWhite,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
fun PowerSwitchLever(
    isOn: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0B0C10))
            .border(1.dp, DslrColors.ChassisHairline, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = "OFF",
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (!isOn) DslrColors.VfRedAf else DslrColors.LegendMuted,
            maxLines = 1,
            softWrap = false
        )
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF1F232C))
                .padding(2.dp),
            contentAlignment = if (isOn) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isOn) DslrColors.VfGreenLock else Color(0xFF6B7280))
            )
        }
        Text(
            text = "ON",
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (isOn) DslrColors.VfGreenLock else DslrColors.LegendMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Physically Rotating CNC Mode Dial Turret (M, Av, Tv, P, A+, CA, C1, C2).
 * Both the knurled outer ring and radial notch index visibly rotate when tapped or dragged.
 */
@Composable
fun ModeDialTurret(
    mode: ExposureMode,
    onSelectNext: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var cumulativeSteps by remember { mutableIntStateOf(mode.ordinal) }
    val stepAngle = 360f / ExposureMode.entries.size

    val targetRotation = -(cumulativeSteps * stepAngle) + (dragAccum * 0.8f)
    val animatedRotation by animateFloatAsState(
        targetValue = targetRotation,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "ModeDialSpin"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Fixed 12-o'clock crimson/white index triangle
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(DslrColors.CrimsonLRing)
        )
        Box(
            modifier = Modifier
                .size(50.dp)
                .shadow(7.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFF0A0B0E))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { dragAccum = 0f },
                        onDragCancel = { dragAccum = 0f }
                    ) { _, dragAmount ->
                        dragAccum += dragAmount.x
                        if (dragAccum > 26f) {
                            dragAccum = 0f
                            cumulativeSteps += 1
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectNext(1)
                        } else if (dragAccum < -26f) {
                            dragAccum = 0f
                            cumulativeSteps -= 1
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectNext(-1)
                        }
                    }
                }
                .clickable {
                    cumulativeSteps += 1
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectNext(1)
                },
            contentAlignment = Alignment.Center
        ) {
            // Physically rotating knurled outer wheel & index notches
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = animatedRotation }
                    .clip(CircleShape)
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                Color(0xFF3E434E), Color(0xFF1A1C22),
                                Color(0xFF434955), Color(0xFF14161B),
                                Color(0xFF3E434E)
                            )
                        )
                    )
                    .border(1.3.dp, Color(0xFF565D6D), CircleShape)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val rOuter = size.minDimension / 2f - 1f
                    val rInner = rOuter - 5.5f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    for (i in 0 until 32) {
                        val angle = i * (2.0 * PI / 32.0)
                        val p1 = Offset(center.x + (rInner * cos(angle)).toFloat(), center.y + (rInner * sin(angle)).toFloat())
                        val p2 = Offset(center.x + (rOuter * cos(angle)).toFloat(), center.y + (rOuter * sin(angle)).toFloat())
                        drawLine(Color(0xFF70788A), p1, p2, strokeWidth = 1.4f)
                    }
                    // 8 engraved mode pips on the rotating ring
                    val count = ExposureMode.entries.size
                    for (m in 0 until count) {
                        val a = (m * (2.0 * PI / count)) - (PI / 2.0)
                        val rPip = rInner - 3.dp.toPx()
                        val pipCenter = Offset(center.x + (rPip * cos(a)).toFloat(), center.y + (rPip * sin(a)).toFloat())
                        drawCircle(
                            color = if (m == mode.ordinal) DslrColors.MangoGoldBright else Color(0xFF6B7280),
                            radius = if (m == mode.ordinal) 2.2.dp.toPx() else 1.3.dp.toPx(),
                            center = pipCenter
                        )
                    }
                }
            }

            // Center stationary mode display lock cap
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0E1015))
                    .border(1.dp, Color(0xFF3D424E), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode.code,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    color = if (mode == ExposureMode.SCENE_AUTO) DslrColors.VfGreenLock else DslrColors.MangoGoldBright
                )
            }
        }
        Text(
            text = "MODE",
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = DslrColors.LegendMuted
        )
    }
}

@Composable
fun TwoStageShutterButton(
    isHalfPressed: Boolean,
    isCapturing: Boolean,
    onHalfPress: () -> Unit,
    onReleaseHalfPress: () -> Unit,
    onShutterFire: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val onHalfPressUpdated by rememberUpdatedState(onHalfPress)
    val onReleaseHalfPressUpdated by rememberUpdatedState(onReleaseHalfPress)
    val onShutterFireUpdated by rememberUpdatedState(onShutterFire)
    var pressed by remember { mutableStateOf(false) }

    val collarColor = when {
        isCapturing -> DslrColors.VfRedAf
        isHalfPressed -> DslrColors.VfGreenLock
        else -> DslrColors.CrimsonLRing
    }

    Box(
        modifier = modifier
            .size(60.dp)
            .semantics { contentDescription = "ShutterButton" }
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF2E333D), Color(0xFF14161B))
                )
            )
            .border(1.8.dp, collarColor, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val downMs = System.currentTimeMillis()
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val holdJob = scope.launch {
                        delay(35L)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHalfPressUpdated()
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    holdJob.cancel()
                    pressed = false
                    val heldMs = System.currentTimeMillis() - downMs
                    if (heldMs < 185L) {
                        onShutterFireUpdated(false)
                    } else {
                        onReleaseHalfPressUpdated()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (pressed) 38.dp else 42.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        if (pressed) listOf(DslrColors.ShutterSilverBottom, DslrColors.ShutterSilverTop)
                        else listOf(DslrColors.ShutterSilverTop, DslrColors.ShutterSilverBottom)
                    )
                )
                .border(1.1.dp, Color(0xFF939AA8), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(Color(0x38000000))
                    .border(0.8.dp, Color(0x55FFFFFF), CircleShape)
            )
        }
    }
}

/**
 * Physically Spinning CNC Knurled Quick Control Wheel + Stationary 4-Way Multi-Controller & SET.
 * Outer ring, brushed sweep sheen, and 8 machined thumb dimples spin 1:1 with your circular drag!
 */
@Composable
fun RotaryQuickControlWheel(
    isLocked: Boolean,
    onStepDial: (Int) -> Unit,
    onDirectionalClick: (Int) -> Unit,
    onSetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var wheelSizePx by remember { mutableStateOf(0) }
    var lastAngleDeg by remember { mutableFloatStateOf(Float.NaN) }
    var accumDeg by remember { mutableFloatStateOf(0f) }
    var wheelRotationDeg by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val smoothRotation by animateFloatAsState(
        targetValue = wheelRotationDeg,
        animationSpec = if (isDragging) tween(durationMillis = 16) else spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label = "WheelSpin"
    )

    Box(
        modifier = modifier
            .size(116.dp)
            .onSizeChanged { wheelSizePx = it.width }
            .shadow(10.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF0A0B0E))
            .border(
                width = 1.4.dp,
                color = if (isLocked) DslrColors.VfRedAf.copy(alpha = 0.75f) else Color(0xFF474D5B),
                shape = CircleShape
            )
            .pointerInput(isLocked) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val c = wheelSizePx / 2f
                        lastAngleDeg = (atan2(offset.y - c, offset.x - c) * 180.0 / PI).toFloat()
                        accumDeg = 0f
                    },
                    onDragEnd = {
                        isDragging = false
                        lastAngleDeg = Float.NaN
                        accumDeg = 0f
                    },
                    onDragCancel = {
                        isDragging = false
                        lastAngleDeg = Float.NaN
                        accumDeg = 0f
                    }
                ) { change, _ ->
                    if (isLocked) return@detectDragGestures
                    val c = wheelSizePx / 2f
                    val angle = (atan2(change.position.y - c, change.position.x - c) * 180.0 / PI).toFloat()
                    if (!lastAngleDeg.isNaN()) {
                        var delta = angle - lastAngleDeg
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        wheelRotationDeg += delta
                        accumDeg += delta
                        val stepThreshold = 16f
                        while (accumDeg >= stepThreshold) {
                            accumDeg -= stepThreshold
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onStepDial(1)
                        }
                        while (accumDeg <= -stepThreshold) {
                            accumDeg += stepThreshold
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onStepDial(-1)
                        }
                    }
                    lastAngleDeg = angle
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Physically Rotating Knurled Ring + Brushed Sheen + 8 Thumb Dimples
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = smoothRotation }
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Color(0xFF303540), Color(0xFF15171C),
                            Color(0xFF393F4D), Color(0xFF121418),
                            Color(0xFF303540)
                        )
                    )
                )
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rOuter = size.minDimension / 2f - 2f
                val rInner = rOuter - 7f
                val center = Offset(size.width / 2f, size.height / 2f)
                for (i in 0 until 52) {
                    val angle = i * (2.0 * PI / 52.0)
                    val p1 = Offset(center.x + (rInner * cos(angle)).toFloat(), center.y + (rInner * sin(angle)).toFloat())
                    val p2 = Offset(center.x + (rOuter * cos(angle)).toFloat(), center.y + (rOuter * sin(angle)).toFloat())
                    drawLine(Color(0xFF5A6172), p1, p2, strokeWidth = 1.35f)
                }
                // 8 machined thumb-grip dimples + 1 gold index marker that visibly travel as you spin
                val dimpleR = rInner - 5.dp.toPx()
                for (d in 0 until 8) {
                    val a = d * (2.0 * PI / 8.0) - (PI / 2.0)
                    val pos = Offset(center.x + (dimpleR * cos(a)).toFloat(), center.y + (dimpleR * sin(a)).toFloat())
                    drawCircle(
                        color = if (d == 0) DslrColors.MangoGold else Color(0xFF0A0B0E),
                        radius = if (d == 0) 2.5.dp.toPx() else 2.2.dp.toPx(),
                        center = pos
                    )
                    if (d != 0) {
                        drawCircle(
                            color = Color(0x40FFFFFF),
                            radius = 2.2.dp.toPx(),
                            center = pos,
                            style = Stroke(0.7f)
                        )
                    }
                }
            }
        }

        // Inner Stationary Multi-controller + SET
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Color(0xFF14161B))
                .border(1.dp, Color(0xFF383E4B), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "▲",
                fontSize = 8.sp,
                color = DslrColors.LegendMuted,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .clickable {
                        wheelRotationDeg -= 15f
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDirectionalClick(0)
                    }
                    .padding(top = 3.dp, start = 10.dp, end = 10.dp, bottom = 3.dp)
            )
            Text(
                text = "▼",
                fontSize = 8.sp,
                color = DslrColors.LegendMuted,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clickable {
                        wheelRotationDeg += 15f
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDirectionalClick(2)
                    }
                    .padding(bottom = 3.dp, start = 10.dp, end = 10.dp, top = 3.dp)
            )
            Text(
                text = "◀",
                fontSize = 8.sp,
                color = DslrColors.LegendMuted,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clickable {
                        wheelRotationDeg -= 15f
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDirectionalClick(3)
                    }
                    .padding(start = 4.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
            )
            Text(
                text = "▶",
                fontSize = 8.sp,
                color = DslrColors.LegendMuted,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clickable {
                        wheelRotationDeg += 15f
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onDirectionalClick(1)
                    }
                    .padding(end = 4.dp, top = 10.dp, bottom = 10.dp, start = 4.dp)
            )

            DslrRoundButton(
                size = 34.dp,
                label = "SET",
                labelColor = DslrColors.LegendWhite,
                onClick = onSetClick
            )
        }
    }
}

@Composable
fun LockSwitchLever(
    isLocked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0C0D11))
            .border(1.dp, if (isLocked) DslrColors.VfRedAf.copy(alpha = 0.7f) else DslrColors.ChassisHairline, RoundedCornerShape(10.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onToggle()
            }
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isLocked) DslrColors.VfRedAf else Color(0xFF525660))
        )
        Text(
            text = "LOCK",
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            color = if (isLocked) DslrColors.VfRedAf else DslrColors.LegendMuted
        )
    }
}

/**
 * Tactile 4-detent horizontal mechanical DSLR Flash Switch Lever positioned below MENU/INFO.
 * Supports both tapping to step through OFF / AUTO / ON / TORCH and horizontal drag detents.
 */
@Composable
fun FlashSwitchLever(
    flashMode: FlashMode,
    onSelectMode: (FlashMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val modes = FlashMode.entries
    val modeIdx = flashMode.ordinal.coerceIn(0, modes.lastIndex)
    val trackWidthDp = 86.dp
    val knobWidthDp = 26.dp
    val travelSpanDp = trackWidthDp - knobWidthDp - 4.dp
    val targetOffsetDp = 2.dp + (travelSpanDp * (modeIdx.toFloat() / modes.lastIndex.coerceAtLeast(1)))
    val knobOffsetDp by animateDpAsState(
        targetValue = targetOffsetDp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        label = "FlashLeverSlide"
    )

    val activeAccent = when (flashMode) {
        FlashMode.OFF -> DslrColors.LegendMuted
        FlashMode.AUTO -> DslrColors.MangoGold
        FlashMode.ON -> DslrColors.MangoGoldBright
        FlashMode.TORCH -> DslrColors.VfGreenLock
    }
    val shortTag = when (flashMode) {
        FlashMode.OFF -> "OFF"
        FlashMode.AUTO -> "AUTO"
        FlashMode.ON -> "ON"
        FlashMode.TORCH -> "LED"
    }

    var dragAccumPx by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier.width(trackWidthDp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FlashBoltGlyph(color = activeAccent)
                Text(
                    text = "FLASH",
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    color = DslrColors.LegendMuted,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Text(
                text = shortTag,
                fontSize = 7.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                color = activeAccent,
                maxLines = 1,
                softWrap = false
            )
        }

        Box(
            modifier = Modifier
                .width(trackWidthDp)
                .height(20.dp)
                .shadow(3.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0A0B0F))
                .border(
                    width = 1.dp,
                    color = if (flashMode == FlashMode.OFF) DslrColors.ChassisHairline else activeAccent.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(10.dp)
                )
                .pointerInput(flashMode) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccumPx = 0f },
                        onDragEnd = { dragAccumPx = 0f },
                        onDragCancel = { dragAccumPx = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        dragAccumPx += dragAmount
                        if (dragAccumPx > 22f && modeIdx < modes.lastIndex) {
                            dragAccumPx = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectMode(modes[modeIdx + 1])
                        } else if (dragAccumPx < -22f && modeIdx > 0) {
                            dragAccumPx = 0f
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectMode(modes[modeIdx - 1])
                        }
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectMode(modes[(modeIdx + 1) % modes.size])
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // 4 precision engraved detent pips inside track
            Canvas(modifier = Modifier.fillMaxSize()) {
                val leftPad = 14.dp.toPx()
                val usableW = (size.width - leftPad * 2f).coerceAtLeast(1f)
                val cy = size.height / 2f
                for (i in 0..3) {
                    val x = leftPad + usableW * (i / 3f)
                    drawCircle(
                        color = if (i == modeIdx) activeAccent.copy(alpha = 0.7f) else Color(0xFF2F3542),
                        radius = 1.6.dp.toPx(),
                        center = Offset(x, cy)
                    )
                }
            }

            // Sliding brushed-metal actuator lever knob
            Box(
                modifier = Modifier
                    .offset(x = knobOffsetDp)
                    .width(knobWidthDp)
                    .height(14.dp)
                    .shadow(3.dp, RoundedCornerShape(7.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF474E5D), Color(0xFF1F232C))
                        )
                    )
                    .border(0.8.dp, Color(0xFF727B8E), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 9.dp, height = 3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(activeAccent)
                )
            }
        }
    }
}

@Composable
fun FlashBoltGlyph(color: Color = DslrColors.MangoGold) {
    Canvas(modifier = Modifier.size(9.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.58f, size.height * 0.08f)
            lineTo(size.width * 0.22f, size.height * 0.54f)
            lineTo(size.width * 0.48f, size.height * 0.54f)
            lineTo(size.width * 0.36f, size.height * 0.92f)
            lineTo(size.width * 0.78f, size.height * 0.44f)
            lineTo(size.width * 0.52f, size.height * 0.44f)
            close()
        }
        drawPath(path, color = color)
    }
}

/* Custom crisp DSLR Canvas glyphs */

@Composable
fun ZoomMagnifierGlyph(color: Color = DslrColors.LegendBlue) {
    Canvas(modifier = Modifier.size(15.dp)) {
        val r = size.minDimension * 0.28f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, radius = r, center = c, style = Stroke(width = 2.0f))
        drawLine(
            color = color,
            start = Offset(c.x + r * 0.7f, c.y + r * 0.7f),
            end = Offset(size.width * 0.85f, size.height * 0.85f),
            strokeWidth = 2.4f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun PlaybackTriangleGlyph(color: Color = DslrColors.LegendBlue) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.18f)
            lineTo(size.width * 0.82f, size.height * 0.50f)
            lineTo(size.width * 0.28f, size.height * 0.82f)
            close()
        }
        drawPath(path, color = color)
    }
}

@Composable
fun TrashBinGlyph(color: Color = DslrColors.LegendBlue) {
    Canvas(modifier = Modifier.size(14.dp)) {
        drawLine(color, Offset(size.width * 0.20f, size.height * 0.26f), Offset(size.width * 0.80f, size.height * 0.26f), 2f, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.40f, size.height * 0.14f), Offset(size.width * 0.60f, size.height * 0.14f), 2f, StrokeCap.Round)
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.27f, size.height * 0.34f),
            size = Size(size.width * 0.46f, size.height * 0.52f),
            cornerRadius = CornerRadius(2f, 2f),
            style = Stroke(width = 1.8f)
        )
    }
}

@Composable
fun BulbGlyph(color: Color) {
    Canvas(modifier = Modifier.size(13.dp)) {
        drawCircle(color, radius = size.minDimension * 0.26f, center = Offset(size.width / 2f, size.height * 0.44f), style = Stroke(width = 1.8f))
        drawLine(color, Offset(size.width * 0.38f, size.height * 0.80f), Offset(size.width * 0.62f, size.height * 0.80f), 2f, StrokeCap.Round)
    }
}

@Composable
fun BatteryGlyph(color: Color) {
    Canvas(modifier = Modifier.size(width = 18.dp, height = 10.dp)) {
        drawRect(color, topLeft = Offset(1f, 1.5f), size = Size(size.width - 4f, size.height - 3f), style = Stroke(1.6f))
        drawRect(color, topLeft = Offset(size.width - 3f, size.height * 0.3f), size = Size(2.5f, size.height * 0.4f))
        drawRect(color, topLeft = Offset(3f, 3.3f), size = Size(size.width - 9f, size.height - 6.6f))
    }
}
