package com.alphaauxiliary.fitgenerator.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Android resolves the generic sans family to Roboto and uses the system Noto Sans CJK
// fallback for Chinese. Keeping both families generic avoids a bundled or network font.
internal val TracksideSansFontFamily: FontFamily = FontFamily.SansSerif
internal val TracksideMetricFontFamily: FontFamily = FontFamily.Monospace

private val BaselineTypography = Typography()

internal val FitGeneratorTypography = Typography(
    displayLarge = BaselineTypography.displayLarge.tracksideSans(),
    displayMedium = BaselineTypography.displayMedium.tracksideSans(),
    displaySmall = BaselineTypography.displaySmall.tracksideSans(),
    headlineLarge = BaselineTypography.headlineLarge.tracksideSans(),
    headlineMedium = BaselineTypography.headlineMedium.tracksideSans(),
    headlineSmall = BaselineTypography.headlineSmall.tracksideSans(),
    titleLarge = BaselineTypography.titleLarge.tracksideSans(),
    titleMedium = BaselineTypography.titleMedium.tracksideSans(),
    titleSmall = BaselineTypography.titleSmall.tracksideSans(),
    bodyLarge = BaselineTypography.bodyLarge.tracksideSans(),
    bodyMedium = BaselineTypography.bodyMedium.tracksideSans(),
    bodySmall = BaselineTypography.bodySmall.tracksideSans(),
    labelLarge = BaselineTypography.labelLarge.tracksideSans(),
    labelMedium = BaselineTypography.labelMedium.tracksideSans(),
    labelSmall = BaselineTypography.labelSmall.tracksideSans(),
)

internal val TimingMetricTextStyle: TextStyle = BaselineTypography.titleMedium.copy(
    fontFamily = TracksideMetricFontFamily,
    fontFeatureSettings = "'tnum' 1",
    fontWeight = FontWeight.Bold,
)

private fun TextStyle.tracksideSans(): TextStyle = copy(fontFamily = TracksideSansFontFamily)
