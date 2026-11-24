package com.garan.drytoast.ui.theme

import androidx.compose.ui.graphics.Color

val AmoledBlack = Color(0xFF000000)
val PureWhite = Color(0xFFFFFFFF)
val MutedWhite = Color(0xFFCCCCCC)
val SubtleGray = Color(0xFF888888)
val CharcoalGray = Color(0xFF1E1E1E)
val HairlineBorder = Color(0xFF262626)

// Material 3 Surface Container & Outline Hierarchy (Monochrome Instrument Palette)
val SurfaceContainerLow = Color(0xFF0C0C0C)
val SurfaceContainer = Color(0xFF121212)
val SurfaceContainerHigh = Color(0xFF1A1A1A)
val SurfaceContainerHighest = Color(0xFF242424)
val OutlineGray = Color(0xFF444444)

// 5-Step Monochrome Luminance Ramp for Caret Tracing Checkpoint
val CaretPass1Gray = Color(0xFF2B2B2B)
val CaretPass2Gray = Color(0xFF555555)
val CaretPass3Gray = Color(0xFF888888)
val CaretPass4Gray = Color(0xFFBFBFBF)
val CaretPass5White = PureWhite

// Monochrome Instrument Palette for Isometric 8-Slice Dualit Toaster Checkpoint
val ToasterFloorShadow = Color(0xFF0D0D0D)
val ToasterBodyTop = Color(0xFF0E0E0E)
val ToasterBodyFront = Color(0xFF050505)
val ToasterBodySide = Color(0xFF111111)
val ToasterTrimGray = Color(0xFF333333)
val ToasterNameplateBorder = Color(0xFF3A3A3A)
val ToasterNameplateRule = Color(0xFF5A5A5A)
val ToasterSlotRecess = Color(0xFF040404)
val ToasterSlotBorder = Color(0xFF484848)
val ToasterBreadIdleOutline = Color(0xFF686868)
val ToasterBreadIdleCrust = Color(0xFF404040)
val ToasterBreadIdleCrustFill = Color(0xFF101010)
val ToasterBreadLoweredCrust = Color(0xFF525252)
val ToasterBreadLoweredCrustFill = Color(0xFF141414)
val ToasterBreadPoppedOutline = Color(0xFFD0D0D0)
val ToasterBreadPoppedCrust = Color(0xFF828282)
val ToasterBreadPoppedCrustFill = Color(0xFF1A1A1A)
val ToasterBreadNewestCrust = Color(0xFFC4C4C4)
val ToasterBreadNewestCrustFill = Color(0xFF262626)
val ToasterBreadNewestFace = Color(0xFF060606)
val ToasterLeverHaloFill = Color(0x1FFFFFFF)
val ToasterLeverHaloStroke = Color(0x73FFFFFF)

// Profile Accent Palette (blended at low alpha over surface containers for dark/light adaptability)
val ProfileAmber = Color(0xFFD97706)
val ProfileEmerald = Color(0xFF059669)
val ProfileCyan = Color(0xFF0284C7)
val ProfileIndigo = Color(0xFF4F46E5)
val ProfileViolet = Color(0xFF9333EA)
val ProfileCrimson = Color(0xFFE11D48)

val ProfileColorPalette: List<Color> = listOf(
    ProfileAmber,
    ProfileEmerald,
    ProfileCyan,
    ProfileIndigo,
    ProfileViolet,
    ProfileCrimson,
)

fun profileBaseColor(colorIndex: Int): Color {
    val size = ProfileColorPalette.size
    val normalizedIndex = ((colorIndex % size) + size) % size
    return ProfileColorPalette[normalizedIndex]
}
