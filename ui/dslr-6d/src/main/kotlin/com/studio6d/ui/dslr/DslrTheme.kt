package com.studio6d.ui.dslr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object DslrColors {
    // Satin-matte polycarbonate upper dome & vulcanite pebbled leatherette lower body
    val PolyTop = Color(0xFF262930)
    val PolyMid = Color(0xFF1A1C21)
    val PolyDeep = Color(0xFF121418)
    val ChassisHairline = Color(0xFF3A3F4B)
    val PanelGlassBg = Color(0xCC111317)

    val LeatherBase = Color(0xFF0E0F13)

    // Precision recessed 3:2 monitor bezel
    val MonitorBezelOuter = Color(0xFF090A0D)
    val MonitorBezelInner = Color(0xFF16181E)
    val MonitorGlassEdge = Color(0xFF303540)

    // CNC titanium buttons & dials
    val ButtonSurfaceTop = Color(0xFF2B2F37)
    val ButtonSurfaceBottom = Color(0xFF16181D)
    val ButtonBorder = Color(0xFF474D5C)
    val ShutterSilverTop = Color(0xFF6B7280)
    val ShutterSilverBottom = Color(0xFF2C3038)
    val CrimsonLRing = Color(0xFFE52521)

    // Canon-inspired legends & Digital Mango champagne gold
    val LegendWhite = Color(0xFFF2F4F8)
    val LegendMuted = Color(0xFF9AA2B2)
    val LegendBlue = Color(0xFF64B5F6)
    val MangoGold = Color(0xFFF2B857)
    val MangoGoldBright = Color(0xFFFFDF9E)
    val MangoAmber = Color(0xFFEA8225)
    val MangoLeaf = Color(0xFF52D17C)

    // Classic Canon 6D Top Shoulder Segment LCD (Olive Reflective + Amber EL Backlight)
    val TopLcdOffBg = Color(0xFF8C9784)
    val TopLcdOffEdge = Color(0xFF687261)
    val TopLcdAmberBg = Color(0xFFFF9426)
    val TopLcdAmberGlow = Color(0xFFFFBC6B)
    val TopLcdInk = Color(0xFF151913)
    val TopLcdInkDim = Color(0x45151913)

    // Optical Viewfinder / Live View HUD
    val VfRedAf = Color(0xFFFF2414)
    val VfGreenLock = Color(0xFF4CFF7A)
    val VfEmeraldText = Color(0xFF68FF8E)
    val VfHudBg = Color(0xDD080A0D)
    val QMenuBg = Color(0xEB0E1117)
    val QMenuTile = Color(0xFF181D25)
    val QMenuTileSelected = Color(0xFF283446)
}

object DslrType {
    val modelBadge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = 9.5.sp,
        letterSpacing = 0.8.sp
    )
    val legend = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 8.5.sp,
        letterSpacing = 0.6.sp
    )
    val lcdBig = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 12.sp,
        letterSpacing = 0.3.sp
    )
    val lcdSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 8.5.sp,
        letterSpacing = 0.2.sp
    )
    val vfTelemetry = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        letterSpacing = 0.4.sp
    )
}
