package com.alphaauxiliary.fitgenerator.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// The approved six-color identity is intentionally fixed: system dark mode and
// wallpaper-derived dynamic colors must not remap pace, heart-rate, or track roles.
private val TracksideColorScheme = lightColorScheme(
    primary = ScoreboardInk,
    onPrimary = FieldPaper,
    primaryContainer = FieldPaper,
    onPrimaryContainer = ScoreboardInk,
    secondary = PaceTeal,
    onSecondary = FieldPaper,
    secondaryContainer = FieldPaper,
    onSecondaryContainer = ScoreboardInk,
    tertiary = HeartBerry,
    onTertiary = FieldPaper,
    tertiaryContainer = FieldPaper,
    onTertiaryContainer = ScoreboardInk,
    background = FieldPaper,
    onBackground = ScoreboardInk,
    surface = FieldPaper,
    onSurface = ScoreboardInk,
    surfaceVariant = FieldPaper,
    onSurfaceVariant = ScoreboardInk,
    outline = LaneGrayGreen,
    outlineVariant = LaneGrayGreen,
    error = ScoreboardInk,
    onError = FieldPaper,
    inverseSurface = ScoreboardInk,
    inverseOnSurface = FieldPaper,
    inversePrimary = LaneGrayGreen,
    scrim = ScoreboardInk,
)

private val TracksideShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
)

@Composable
internal fun FitGeneratorTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val activity = view.context.findActivity()

    if (!view.isInEditMode && activity != null) {
        SideEffect {
            applyBrandSystemBars(activity, view)
        }
    }

    MaterialTheme(
        colorScheme = TracksideColorScheme,
        typography = FitGeneratorTypography,
        shapes = TracksideShapes,
        content = content,
    )
}

@Suppress("DEPRECATION")
private fun applyBrandSystemBars(activity: Activity, view: android.view.View) {
    val supportsDarkNavigationIcons = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    activity.window.apply {
        statusBarColor = FieldPaper.toArgb()
        navigationBarColor = if (supportsDarkNavigationIcons) {
            FieldPaper.toArgb()
        } else {
            ScoreboardInk.toArgb()
        }
    }
    WindowCompat.getInsetsController(activity.window, view).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = supportsDarkNavigationIcons
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
    else -> null
}
