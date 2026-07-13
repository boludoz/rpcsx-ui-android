
package net.rpcsx

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

object colors {
    // Premium Light Mode (Slate & Electric Blue)
    val primaryLight = Color(0xFF3B82F6)
    val onPrimaryLight = Color(0xFFFFFFFF)
    val primaryContainerLight = Color(0xFFDBEAFE)
    val onPrimaryContainerLight = Color(0xFF1E40AF)
    val secondaryLight = Color(0xFF64748B)
    val onSecondaryLight = Color(0xFFFFFFFF)
    val secondaryContainerLight = Color(0xFFF1F5F9)
    val onSecondaryContainerLight = Color(0xFF1E293B)
    val tertiaryLight = Color(0xFF8B5CF6)
    val onTertiaryLight = Color(0xFFFFFFFF)
    val tertiaryContainerLight = Color(0xFFEDE9FE)
    val onTertiaryContainerLight = Color(0xFF5B21B6)
    val errorLight = Color(0xFFEF4444)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFEE2E2)
    val onErrorContainerLight = Color(0xFF991B1B)
    val backgroundLight = Color(0xFFF8FAFC)
    val onBackgroundLight = Color(0xFF0F172A)
    val surfaceLight = Color(0xFFFFFFFF)
    val onSurfaceLight = Color(0xFF1E293B)
    val surfaceVariantLight = Color(0xFFF1F5F9)
    val onSurfaceVariantLight = Color(0xFF475569)
    val outlineLight = Color(0xFF94A3B8)
    val outlineVariantLight = Color(0xFFE2E8F0)
    val scrimLight = Color(0xFF000000)
    val inverseSurfaceLight = Color(0xFF1E293B)
    val inverseOnSurfaceLight = Color(0xFFF8FAFC)
    val inversePrimaryLight = Color(0xFF93C5FD)
    val surfaceDimLight = Color(0xFFE2E8F0)
    val surfaceBrightLight = Color(0xFFFFFFFF)
    val surfaceContainerLowestLight = Color(0xFFFFFFFF)
    val surfaceContainerLowLight = Color(0xFFF8FAFC)
    val surfaceContainerLight = Color(0xFFF1F5F9)
    val surfaceContainerHighLight = Color(0xFFE2E8F0)
    val surfaceContainerHighestLight = Color(0xFFCBD5E1)

    // Premium Dark Mode (Console / Space Obsidian Theme)
    val primaryDark = Color(0xFF5B73F7)
    val onPrimaryDark = Color(0xFFFFFFFF)
    val primaryContainerDark = Color(0xFF2A3B90)
    val onPrimaryContainerDark = Color(0xFFE0E5FF)
    val secondaryDark = Color(0xFF7C93B2)
    val onSecondaryDark = Color(0xFF0F1117)
    val secondaryContainerDark = Color(0xFF1B202A)
    val onSecondaryContainerDark = Color(0xFFE2E8F0)
    val tertiaryDark = Color(0xFFA78BFA)
    val onTertiaryDark = Color(0xFF0F1117)
    val tertiaryContainerDark = Color(0xFF4C1D95)
    val onTertiaryContainerDark = Color(0xFFF5F3FF)
    val errorDark = Color(0xFFF87171)
    val onErrorDark = Color(0xFF0F1117)
    val errorContainerDark = Color(0xFF7F1D1D)
    val onErrorContainerDark = Color(0xFFFEE2E2)
    val backgroundDark = Color(0xFF08090C)
    val onBackgroundDark = Color(0xFFF1F5F9)
    val surfaceDark = Color(0xFF0E1117)
    val onSurfaceDark = Color(0xFFE2E8F0)
    val surfaceVariantDark = Color(0xFF1B202A)
    val onSurfaceVariantDark = Color(0xFF94A3B8)
    val outlineDark = Color(0xFF475569)
    val outlineVariantDark = Color(0xFF334155)
    val scrimDark = Color(0xFF000000)
    val inverseSurfaceDark = Color(0xFFE2E8F0)
    val inverseOnSurfaceDark = Color(0xFF0E1117)
    val inversePrimaryDark = Color(0xFF3B82F6)
    val surfaceDimDark = Color(0xFF08090C)
    val surfaceBrightDark = Color(0xFF1B202A)
    val surfaceContainerLowestDark = Color(0xFF040507)
    val surfaceContainerLowDark = Color(0xFF0A0C10)
    val surfaceContainerDark = Color(0xFF0E1117)
    val surfaceContainerHighDark = Color(0xFF151923)
    val surfaceContainerHighestDark = Color(0xFF1E2330)
}

private val lightScheme = lightColorScheme(
    primary = colors.primaryLight,
    onPrimary = colors.onPrimaryLight,
    primaryContainer = colors.primaryContainerLight,
    onPrimaryContainer = colors.onPrimaryContainerLight,
    secondary = colors.secondaryLight,
    onSecondary = colors.onSecondaryLight,
    secondaryContainer = colors.secondaryContainerLight,
    onSecondaryContainer = colors.onSecondaryContainerLight,
    tertiary = colors.tertiaryLight,
    onTertiary = colors.onTertiaryLight,
    tertiaryContainer = colors.tertiaryContainerLight,
    onTertiaryContainer = colors.onTertiaryContainerLight,
    error = colors.errorLight,
    onError = colors.onErrorLight,
    errorContainer = colors.errorContainerLight,
    onErrorContainer = colors.onErrorContainerLight,
    background = colors.backgroundLight,
    onBackground = colors.onBackgroundLight,
    surface = colors.surfaceLight,
    onSurface = colors.onSurfaceLight,
    surfaceVariant = colors.surfaceVariantLight,
    onSurfaceVariant = colors.onSurfaceVariantLight,
    outline = colors.outlineLight,
    outlineVariant = colors.outlineVariantLight,
    scrim = colors.scrimLight,
    inverseSurface = colors.inverseSurfaceLight,
    inverseOnSurface = colors.inverseOnSurfaceLight,
    inversePrimary = colors.inversePrimaryLight,
    surfaceDim = colors.surfaceDimLight,
    surfaceBright = colors.surfaceBrightLight,
    surfaceContainerLowest = colors.surfaceContainerLowestLight,
    surfaceContainerLow = colors.surfaceContainerLowLight,
    surfaceContainer = colors.surfaceContainerLight,
    surfaceContainerHigh = colors.surfaceContainerHighLight,
    surfaceContainerHighest = colors.surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = colors.primaryDark,
    onPrimary = colors.onPrimaryDark,
    primaryContainer = colors.primaryContainerDark,
    onPrimaryContainer = colors.onPrimaryContainerDark,
    secondary = colors.secondaryDark,
    onSecondary = colors.onSecondaryDark,
    secondaryContainer = colors.secondaryContainerDark,
    onSecondaryContainer = colors.onSecondaryContainerDark,
    tertiary = colors.tertiaryDark,
    onTertiary = colors.onTertiaryDark,
    tertiaryContainer = colors.tertiaryContainerDark,
    onTertiaryContainer = colors.onTertiaryContainerDark,
    error = colors.errorDark,
    onError = colors.onErrorDark,
    errorContainer = colors.errorContainerDark,
    onErrorContainer = colors.onErrorContainerDark,
    background = colors.backgroundDark,
    onBackground = colors.onBackgroundDark,
    surface = colors.surfaceDark,
    onSurface = colors.onSurfaceDark,
    surfaceVariant = colors.surfaceVariantDark,
    onSurfaceVariant = colors.onSurfaceVariantDark,
    outline = colors.outlineDark,
    outlineVariant = colors.outlineVariantDark,
    scrim = colors.scrimDark,
    inverseSurface = colors.inverseSurfaceDark,
    inverseOnSurface = colors.inverseOnSurfaceDark,
    inversePrimary = colors.inversePrimaryDark,
    surfaceDim = colors.surfaceDimDark,
    surfaceBright = colors.surfaceBrightDark,
    surfaceContainerLowest = colors.surfaceContainerLowestDark,
    surfaceContainerLow = colors.surfaceContainerLowDark,
    surfaceContainer = colors.surfaceContainerDark,
    surfaceContainerHigh = colors.surfaceContainerHighDark,
    surfaceContainerHighest = colors.surfaceContainerHighestDark,
)

@Composable
fun RPCSXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // TODO(Ishan09811): Implement dynamic colors option whenever settings gets implemented
    val colors = if (darkTheme) darkScheme else lightScheme

    val view = LocalView.current
    val activity = view.context as? Activity

    SideEffect {
        activity?.window?.apply {
            statusBarColor = android.graphics.Color.TRANSPARENT
            navigationBarColor = android.graphics.Color.TRANSPARENT
            isNavigationBarContrastEnforced = false
            val insetsController = WindowInsetsControllerCompat(this, decorView)
            insetsController.isAppearanceLightNavigationBars = !darkTheme
            insetsController.isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}
