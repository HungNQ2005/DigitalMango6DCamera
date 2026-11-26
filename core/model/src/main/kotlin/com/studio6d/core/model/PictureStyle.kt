package com.studio6d.core.model

/**
 * Canon EOS 6D Picture Styles with DIGIC 5+ color signature parameters.
 *
 * Each style points to an asset `.cube` 3D LUT file under `assets/luts/`.
 * Replace those `.cube` files with calibrated LUTs extracted from real paired shots
 * (Canon 6D vs. Android DNG) without changing any application code.
 */
enum class PictureStyle(
    val shortCode: String,
    val title: String,
    val cubeAssetPath: String,
    val defaultSharpness: Int,   // 0..7 on 6D
    val defaultContrast: Int,    // -4..+4
    val defaultSaturation: Int,  // -4..+4
    val defaultColorTone: Int    // -4..+4 (skin tone red <-> yellow)
) {
    STANDARD(
        shortCode = "S",
        title = "Standard",
        cubeAssetPath = "luts/6d_standard.cube",
        defaultSharpness = 3,
        defaultContrast = 0,
        defaultSaturation = 0,
        defaultColorTone = 0
    ),
    PORTRAIT(
        shortCode = "P",
        title = "Portrait",
        cubeAssetPath = "luts/6d_portrait.cube",
        defaultSharpness = 2,
        defaultContrast = 0,
        defaultSaturation = 0,
        defaultColorTone = 1
    ),
    LANDSCAPE(
        shortCode = "L",
        title = "Landscape",
        cubeAssetPath = "luts/6d_landscape.cube",
        defaultSharpness = 4,
        defaultContrast = 1,
        defaultSaturation = 1,
        defaultColorTone = 0
    ),
    NEUTRAL(
        shortCode = "N",
        title = "Neutral",
        cubeAssetPath = "luts/6d_neutral.cube",
        defaultSharpness = 0,
        defaultContrast = -1,
        defaultSaturation = -1,
        defaultColorTone = 0
    ),
    FAITHFUL(
        shortCode = "F",
        title = "Faithful",
        cubeAssetPath = "luts/6d_faithful.cube",
        defaultSharpness = 0,
        defaultContrast = 0,
        defaultSaturation = 0,
        defaultColorTone = 0
    ),
    MONOCHROME(
        shortCode = "M",
        title = "Monochrome",
        cubeAssetPath = "luts/6d_monochrome.cube",
        defaultSharpness = 3,
        defaultContrast = 1,
        defaultSaturation = -4,
        defaultColorTone = 0
    )
}
