package com.studio6d.ui.dslr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studio6d.core.model.ActiveDialTarget
import com.studio6d.core.model.AfMode
import com.studio6d.core.model.CameraSettingsState
import com.studio6d.core.model.DriveMode
import com.studio6d.core.model.DslrStops
import com.studio6d.core.model.ExternalLensProfile
import com.studio6d.core.model.FlashMode
import com.studio6d.core.model.GridMode
import com.studio6d.core.model.MeteringMode
import com.studio6d.core.model.PictureStyle
import com.studio6d.core.model.PlaybackPhotoItem
import com.studio6d.core.model.WhiteBalancePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Canon 6D Quick Control (Q) interactive overlay inside the 3:2 monitor.
 */
@Composable
fun QuickControlQOverlay(
    state: CameraSettingsState,
    onUpdateState: ((CameraSettingsState) -> CameraSettingsState) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF80B0E14))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Q QUICK · ${state.activeDialTarget.label}",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = DslrColors.MangoGold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            )
            Text(
                text = "[Q] CLOSE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 8.5.sp,
                color = DslrColors.LegendWhite,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF282E38))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Row 1: TV, AV, ISO, EV
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            QTile(
                title = "TV",
                value = DslrStops.formatShutter(state.shutterSpeedNs),
                selected = state.activeDialTarget == ActiveDialTarget.SHUTTER,
                onClick = { onUpdateState { it.copy(activeDialTarget = ActiveDialTarget.SHUTTER) } },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            QTile(
                title = "AV",
                value = DslrStops.formatAperture(state.simulatedApertureF),
                selected = state.activeDialTarget == ActiveDialTarget.APERTURE,
                onClick = { onUpdateState { it.copy(activeDialTarget = ActiveDialTarget.APERTURE) } },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            QTile(
                title = "ISO",
                value = DslrStops.formatIso(state.iso),
                selected = state.activeDialTarget == ActiveDialTarget.ISO,
                onClick = { onUpdateState { it.copy(activeDialTarget = ActiveDialTarget.ISO) } },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            QTile(
                title = "EV COMP",
                value = DslrStops.formatEv(state.evCompensation),
                selected = state.activeDialTarget == ActiveDialTarget.EXPOSURE_COMP,
                onClick = { onUpdateState { it.copy(activeDialTarget = ActiveDialTarget.EXPOSURE_COMP) } },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }

        // Row 2: ZOOM, BOKEH, FLASH, WB
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            QTile(
                title = "ZOOM",
                value = "${DslrStops.formatFocalLength35mm(state.zoomRatio)} ${DslrStops.formatZoom(state.zoomRatio)}",
                selected = state.activeDialTarget == ActiveDialTarget.LENS_ZOOM,
                onClick = { onUpdateState { it.copy(activeDialTarget = ActiveDialTarget.LENS_ZOOM) } },
                modifier = Modifier.weight(1.2f).fillMaxHeight()
            )
            QTile(
                title = "BOKEH",
                value = if (state.opticalBokehEnabled) "ON" else "OFF",
                selected = state.opticalBokehEnabled,
                onClick = { onUpdateState { it.copy(opticalBokehEnabled = !it.opticalBokehEnabled) } },
                modifier = Modifier.weight(0.95f).fillMaxHeight()
            )
            QTile(
                title = "FLASH",
                value = state.flashMode.badge,
                selected = state.flashMode != FlashMode.OFF,
                onClick = {
                    val next = FlashMode.entries[(state.flashMode.ordinal + 1) % FlashMode.entries.size]
                    onUpdateState { it.copy(flashMode = next) }
                },
                modifier = Modifier.weight(0.9f).fillMaxHeight()
            )
            QTile(
                title = "WB",
                value = if (state.wbPreset == WhiteBalancePreset.KELVIN) "${state.customKelvin}K" else state.wbPreset.code,
                selected = state.activeDialTarget == ActiveDialTarget.WHITE_BALANCE,
                onClick = {
                    val next = WhiteBalancePreset.entries[(state.wbPreset.ordinal + 1) % WhiteBalancePreset.entries.size]
                    onUpdateState { it.copy(wbPreset = next, activeDialTarget = ActiveDialTarget.WHITE_BALANCE) }
                },
                modifier = Modifier.weight(0.95f).fillMaxHeight()
            )
        }

        // Row 3: AF, DRIVE, AEB, PIC STYLE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            QTile(
                title = "AF OP",
                value = state.afMode.lcdLabel,
                selected = state.activeDialTarget == ActiveDialTarget.AF_MODE,
                onClick = {
                    val next = AfMode.entries[(state.afMode.ordinal + 1) % AfMode.entries.size]
                    onUpdateState { it.copy(afMode = next, activeDialTarget = ActiveDialTarget.AF_MODE) }
                },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            QTile(
                title = "DRIVE",
                value = state.driveMode.badge,
                selected = state.activeDialTarget == ActiveDialTarget.DRIVE_MODE,
                onClick = {
                    val next = DriveMode.entries[(state.driveMode.ordinal + 1) % DriveMode.entries.size]
                    onUpdateState { it.copy(driveMode = next, activeDialTarget = ActiveDialTarget.DRIVE_MODE) }
                },
                modifier = Modifier.weight(0.8f).fillMaxHeight()
            )
            QTile(
                title = "AEB ±1",
                value = if (state.aebEnabled) "3-SHOT" else "OFF",
                selected = state.aebEnabled,
                onClick = { onUpdateState { it.copy(aebEnabled = !it.aebEnabled) } },
                modifier = Modifier.weight(0.85f).fillMaxHeight()
            )
            QTile(
                title = "STYLE",
                value = "[${state.pictureStyle.shortCode}] ${state.pictureStyle.title}",
                selected = state.activeDialTarget == ActiveDialTarget.PICTURE_STYLE,
                onClick = {
                    val next = PictureStyle.entries[(state.pictureStyle.ordinal + 1) % PictureStyle.entries.size]
                    onUpdateState { it.copy(pictureStyle = next, activeDialTarget = ActiveDialTarget.PICTURE_STYLE) }
                },
                modifier = Modifier.weight(1.3f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun QTile(
    title: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) DslrColors.QMenuTileSelected else DslrColors.QMenuTile)
            .border(
                width = if (selected) 1.3.dp else 0.8.dp,
                color = if (selected) DslrColors.MangoGold else Color(0xFF343B47),
                shape = RoundedCornerShape(5.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            color = DslrColors.LegendMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (selected) DslrColors.MangoGold else DslrColors.LegendWhite,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Classic Canon 6D rear Shooting Info dashboard (cycled via INFO button).
 */
@Composable
fun RearShootingInfoScreen(
    state: CameraSettingsState,
    onSelectTarget: (ActiveDialTarget) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C10))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = "MODE: ${state.exposureMode.displayName.uppercase()}",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.5.sp,
                color = DslrColors.MangoGold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            Text(
                text = "${DslrStops.formatFocalLength35mm(state.zoomRatio)} (${DslrStops.formatZoom(state.zoomRatio)})",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = DslrColors.LegendWhite,
                maxLines = 1,
                softWrap = false
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelectTarget(ActiveDialTarget.SHUTTER) }
            ) {
                Text("SHUTTER", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DslrColors.LegendMuted, maxLines = 1, softWrap = false)
                Text(
                    DslrStops.formatShutter(state.shutterSpeedNs),
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = if (state.activeDialTarget == ActiveDialTarget.SHUTTER) DslrColors.MangoGold else DslrColors.LegendWhite,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelectTarget(ActiveDialTarget.APERTURE) }
            ) {
                Text("APERTURE", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DslrColors.LegendMuted, maxLines = 1, softWrap = false)
                Text(
                    DslrStops.formatAperture(state.simulatedApertureF),
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = if (state.activeDialTarget == ActiveDialTarget.APERTURE) DslrColors.MangoGold else DslrColors.LegendWhite,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelectTarget(ActiveDialTarget.ISO) }
            ) {
                Text("ISO", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DslrColors.LegendMuted, maxLines = 1, softWrap = false)
                Text(
                    DslrStops.formatIso(state.iso),
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = if (state.activeDialTarget == ActiveDialTarget.ISO) DslrColors.MangoGold else DslrColors.LegendWhite,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF151920))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("AF: ${state.afMode.lcdLabel}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DslrColors.LegendWhite, maxLines = 1, softWrap = false)
            Text("WB: ${state.wbPreset.code}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DslrColors.LegendWhite, maxLines = 1, softWrap = false)
            Text("STYLE: ${state.pictureStyle.title}", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DslrColors.MangoGold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(if (state.saveRawDng) "RAW+JPEG" else "JPEG", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DslrColors.VfEmeraldText, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * 4-Tab Pro DSLR Menu Overlay (OPTICS, COLOR, SHOOT, SYSTEM) inside the 3:2 monitor.
 */
@Composable
fun CameraMenuOverlay(
    state: CameraSettingsState,
    hardwareInfoSummary: String,
    onUpdateState: ((CameraSettingsState) -> CameraSettingsState) -> Unit,
    onClose: () -> Unit
) {
    var activeTab by remember { mutableIntStateOf(0) } // 0=OPTICS, 1=COLOR, 2=SHOOT, 3=SYSTEM

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFA0B0E14))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("OPTICS", "COLOR", "SHOOT", "SYSTEM").forEachIndexed { idx, title ->
                    val sel = activeTab == idx
                    Text(
                        text = title,
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (sel) Color.Black else DslrColors.LegendWhite,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sel) DslrColors.MangoGold else Color(0xFF222731))
                            .clickable { activeTab = idx }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = "MENU ✕",
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = DslrColors.LegendWhite,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2E3440))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            when (activeTab) {
                0 -> { // OPTICS
                    MenuToggleRow(
                        label = "Optical Lens Bokeh (${DslrStops.formatAperture(state.simulatedApertureF)})",
                        checked = state.opticalBokehEnabled,
                        onToggle = { onUpdateState { it.copy(opticalBokehEnabled = !it.opticalBokehEnabled) } }
                    )
                    Text(
                        text = "Bokeh Blur Strength: ${(state.bokehUserMultiplier * 100).toInt()}% (${DslrStops.formatAperture(state.simulatedApertureF)})",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DslrColors.VfEmeraldText,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    DslrMenuSlider(
                        value = state.bokehUserMultiplier.coerceIn(0f, 1.6f),
                        valueRange = 0f..1.6f,
                        onValueChange = { v ->
                            onUpdateState { it.copy(bokehUserMultiplier = v, opticalBokehEnabled = v > 0.02f) }
                        }
                    )
                    MenuCycleRow(
                        label = "External Lens Attachment",
                        value = state.externalLens.label,
                        onClick = {
                            val entries = ExternalLensProfile.entries
                            val next = entries[(state.externalLens.ordinal + 1) % entries.size]
                            onUpdateState { it.copy(externalLens = next) }
                        }
                    )
                    MenuToggleRow(
                        label = "11-Point Auto AF Selection",
                        checked = state.isAutoAfSelection,
                        onToggle = {
                            onUpdateState {
                                val nextAuto = !it.isAutoAfSelection
                                it.copy(
                                    isAutoAfSelection = nextAuto,
                                    activeAfPointIndices = if (nextAuto) it.activeAfPointIndices.ifEmpty { listOf(5) } else listOf(it.selectedAfPointIndex)
                                )
                            }
                        }
                    )
                    Text(
                        text = "Hardware Zoom: ${DslrStops.formatZoom(state.zoomRatio)} (${DslrStops.formatFocalLength35mm(state.zoomRatio)}) · Max ${DslrStops.formatZoom(state.maxZoomRatio)}",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DslrColors.LegendWhite,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    DslrMenuSlider(
                        value = state.zoomRatio.coerceIn(state.minZoomRatio, state.maxZoomRatio),
                        valueRange = state.minZoomRatio..state.maxZoomRatio.coerceAtLeast(state.minZoomRatio + 0.1f),
                        onValueChange = { z -> onUpdateState { it.copy(zoomRatio = z) } }
                    )
                    MenuToggleRow(
                        label = "Manual Focus Peaking (Red Edge Assist)",
                        checked = state.afMode == AfMode.MANUAL,
                        onToggle = {
                            onUpdateState {
                                it.copy(afMode = if (it.afMode == AfMode.MANUAL) AfMode.ONE_SHOT else AfMode.MANUAL)
                            }
                        }
                    )
                    MenuToggleRow(
                        label = "Highlight Alert (Overexposure Zebra)",
                        checked = state.highlightAlertEnabled,
                        onToggle = { onUpdateState { it.copy(highlightAlertEnabled = !it.highlightAlertEnabled) } }
                    )
                }
                1 -> { // COLOR
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PictureStyle.entries.forEach { style ->
                            val sel = state.pictureStyle == style
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (sel) DslrColors.MangoGold else Color(0xFF1D222B))
                                    .clickable { onUpdateState { it.copy(pictureStyle = style) } }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = style.shortCode,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    color = if (sel) Color.Black else DslrColors.LegendWhite,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    Text(
                        text = "${state.pictureStyle.title} · LUT ${(state.lutIntensity * 100).toInt()}% · Highlight Roll ${(state.highlightRolloff * 100).toInt()}%",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DslrColors.MangoGold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    DslrMenuSlider(
                        value = state.lutIntensity,
                        valueRange = 0f..1f,
                        onValueChange = { v -> onUpdateState { it.copy(lutIntensity = v) } }
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FineTuneChip(
                            label = "CTR ${signedInt(state.contrastOffset)}",
                            onClick = { onUpdateState { it.copy(contrastOffset = wrapTune(it.contrastOffset)) } },
                            modifier = Modifier.weight(1f)
                        )
                        FineTuneChip(
                            label = "SAT ${signedInt(state.saturationOffset)}",
                            onClick = { onUpdateState { it.copy(saturationOffset = wrapTune(it.saturationOffset)) } },
                            modifier = Modifier.weight(1f)
                        )
                        FineTuneChip(
                            label = "TONE ${signedInt(state.colorToneOffset)}",
                            onClick = { onUpdateState { it.copy(colorToneOffset = wrapTune(it.colorToneOffset)) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                2 -> { // SHOOT
                    MenuToggleRow(
                        label = "AEB Bracketing (±1 EV · 3 Exposures)",
                        checked = state.aebEnabled,
                        onToggle = { onUpdateState { it.copy(aebEnabled = !it.aebEnabled) } }
                    )
                    MenuCycleRow(
                        label = "Flash Mode",
                        value = state.flashMode.label,
                        onClick = {
                            val next = FlashMode.entries[(state.flashMode.ordinal + 1) % FlashMode.entries.size]
                            onUpdateState { it.copy(flashMode = next) }
                        }
                    )
                    MenuCycleRow(
                        label = "Viewfinder Grid Overlay",
                        value = state.gridMode.label,
                        onClick = {
                            val next = GridMode.entries[(state.gridMode.ordinal + 1) % GridMode.entries.size]
                            onUpdateState { it.copy(gridMode = next) }
                        }
                    )
                    MenuToggleRow(
                        label = "Dual Save RAW (.DNG) + Graded JPEG",
                        checked = state.saveRawDng,
                        onToggle = { onUpdateState { it.copy(saveRawDng = !it.saveRawDng) } }
                    )
                }
                else -> { // SYSTEM
                    MenuToggleRow(
                        label = "Mechanical Shutter & AF Beep Sound",
                        checked = state.soundEnabled,
                        onToggle = { onUpdateState { it.copy(soundEnabled = !it.soundEnabled) } }
                    )
                    MenuToggleRow(
                        label = "Top LCD Amber EL Backlight",
                        checked = state.topLcdAmberBacklight,
                        onToggle = { onUpdateState { it.copy(topLcdAmberBacklight = !it.topLcdAmberBacklight) } }
                    )
                    Text(
                        text = hardwareInfoSummary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DslrColors.LegendWhite
                    )
                    Text(
                        text = "Output: Pictures/DigitalMango6D (EXIF: Digital Mango 6D)",
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DslrColors.LegendMuted
                    )
                }
            }
        }
    }
}

private fun signedInt(v: Int): String = if (v > 0) "+$v" else "$v"
private fun wrapTune(v: Int): Int = if (v >= 4) -4 else v + 1

@Composable
private fun FineTuneChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1B2029))
            .border(0.8.dp, Color(0xFF394150), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DslrColors.LegendWhite)
    }
}

@Composable
private fun DslrMenuSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val norm = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + frac * span)
                }
            }
            .pointerInput(valueRange) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onValueChange(valueRange.start + frac * span)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val midY = size.height / 2f
            val padX = 7.dp.toPx()
            val trackW = (size.width - padX * 2f).coerceAtLeast(1f)
            val thumbX = padX + norm * trackW
            drawLine(
                color = Color(0xFF2B3240),
                start = Offset(padX, midY),
                end = Offset(padX + trackW, midY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = DslrColors.MangoGold,
                start = Offset(padX, midY),
                end = Offset(thumbX, midY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = DslrColors.MangoGoldBright,
                radius = 6.dp.toPx(),
                center = Offset(thumbX, midY)
            )
        }
    }
}

@Composable
private fun MenuCycleRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF1A1E26))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = DslrColors.LegendWhite,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Text(text = value, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DslrColors.MangoGold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun MenuToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF1A1E26))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            color = DslrColors.LegendWhite,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Text(
            text = if (checked) "ON" else "OFF",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (checked) DslrColors.VfGreenLock else DslrColors.LegendMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * In-Body Photo Playback (▶) & Canon-style Erase Confirmation (🗑).
 * Uses Canon EOS 6D RAM Buffer previews for instant review right after shutter release.
 */
@Composable
fun InBodyPlaybackOverlay(
    items: List<PlaybackPhotoItem>,
    onClose: () -> Unit,
    onDeleteUri: (String) -> Unit
) {
    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(0) }
    var confirmErase by remember { mutableStateOf(false) }
    var decodedDiskBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val safeIndex = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val activeItem = items.getOrNull(safeIndex)
    val displayBitmap = activeItem?.previewBitmap ?: decodedDiskBitmap

    LaunchedEffect(activeItem?.id, activeItem?.uri, activeItem?.previewBitmap == null) {
        val uri = activeItem?.uri
        if (uri == null) {
            decodedDiskBitmap = null
        } else if (activeItem.previewBitmap == null) {
            decodedDiskBitmap = null
            decodedDiskBitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                }.getOrNull()
            }
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "playbackBusyPulse")
    val busyPulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 360),
            repeatMode = RepeatMode.Reverse
        ),
        label = "playbackBusyAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (items.isEmpty() || displayBitmap == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (activeItem?.isPending == true) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .alpha(busyPulseAlpha)
                            .clip(CircleShape)
                            .background(Color(0xFFFF2828))
                    )
                }
                Text(
                    text = when {
                        items.isEmpty() -> "NO IMAGE ON CARD"
                        activeItem?.isPending == true -> activeItem.statusLabel ?: "BUFFERING..."
                        else -> "LOADING..."
                    },
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = DslrColors.MangoGold
                )
            }
        } else {
            Image(
                bitmap = displayBitmap.asImageBitmap(),
                contentDescription = "Captured Photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (activeItem?.isPending == true && displayBitmap != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (items.size > 1) 28.dp else 10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xCC0D1016))
                    .border(0.8.dp, Color(0xAAFF2828), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .alpha(busyPulseAlpha)
                        .clip(CircleShape)
                        .background(Color(0xFFFF2828))
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = activeItem.statusLabel ?: "SAVING TO CARD...",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.5.sp,
                    color = DslrColors.LegendWhite
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color(0xAA000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (items.isEmpty()) "PLAYBACK 0/0" else "PLAYBACK ${safeIndex + 1}/${items.size}",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = DslrColors.LegendBlue
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val erasableUri = activeItem?.uri?.takeIf { !activeItem.isPending }
                if (erasableUri != null) {
                    Text(
                        text = "ERASE",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = DslrColors.VfRedAf,
                        modifier = Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF281818))
                            .clickable { confirmErase = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "LIVE VIEW",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = DslrColors.LegendWhite,
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF252A32))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "◀ PREV",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = DslrColors.LegendWhite,
                    modifier = Modifier.clickable {
                        currentIndex = if (safeIndex > 0) safeIndex - 1 else items.lastIndex
                    }
                )
                Text(
                    text = "NEXT ▶",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = DslrColors.LegendWhite,
                    modifier = Modifier.clickable {
                        currentIndex = (safeIndex + 1) % items.size
                    }
                )
            }
        }

        val eraseTargetUri = activeItem?.uri?.takeIf { !activeItem.isPending }
        if (confirmErase && eraseTargetUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1A1E25))
                        .border(1.2.dp, DslrColors.VfRedAf, RoundedCornerShape(6.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Erase image?",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = DslrColors.LegendWhite
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            text = "CANCEL",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = DslrColors.LegendMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF282E38))
                                .clickable { confirmErase = false }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                        Text(
                            text = "ERASE",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(DslrColors.VfRedAf)
                                .clickable {
                                    confirmErase = false
                                    onDeleteUri(eraseTargetUri)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Floating Live View Bokeh Intensity Slider Bar (coupled with Av aperture stop).
 * Lets photographers dial subtle-to-creamy background blur (0%..160%) live while framing.
 */
@Composable
fun LiveBokehControlBar(
    state: CameraSettingsState,
    onUpdateState: ((CameraSettingsState) -> CameraSettingsState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = state.opticalBokehEnabled && state.bokehUserMultiplier > 0.02f
    val pct = (state.bokehUserMultiplier * 100f).toInt()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xDD0D1016))
            .border(1.dp, if (active) DslrColors.VfEmeraldText.copy(alpha = 0.65f) else DslrColors.ChassisHairline, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = if (active) "ON" else "OFF",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = if (active) Color.Black else DslrColors.LegendMuted,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (active) DslrColors.VfEmeraldText else Color(0xFF1F242D))
                .clickable {
                    onUpdateState { s ->
                        val nextOn = !s.opticalBokehEnabled
                        s.copy(
                            opticalBokehEnabled = nextOn,
                            bokehUserMultiplier = if (nextOn && s.bokehUserMultiplier <= 0.05f) 1.0f else s.bokehUserMultiplier
                        )
                    }
                }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Text(
            text = "BLUR $pct%",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = if (active) DslrColors.MangoGold else DslrColors.LegendMuted,
            maxLines = 1,
            softWrap = false
        )
        Box(modifier = Modifier.weight(1f)) {
            DslrMenuSlider(
                value = state.bokehUserMultiplier.coerceIn(0f, 1.6f),
                valueRange = 0f..1.6f,
                onValueChange = { v ->
                    onUpdateState { it.copy(bokehUserMultiplier = v, opticalBokehEnabled = v > 0.02f) }
                }
            )
        }
        Text(
            text = "✕",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            color = DslrColors.LegendWhite,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF232934))
                .clickable(onClick = onClose)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
