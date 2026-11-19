package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.domain.model.ThemeMode

internal val BSubDarkColorScheme =
  darkColorScheme(
    primary = CinemaAmberBright,
    onPrimary = Color(0xFF1E1300),
    primaryContainer = CinemaAmberDark,
    onPrimaryContainer = Color(0xFFFDE68A),
    secondary = ElectricCyan,
    onSecondary = Color(0xFF002028),
    secondaryContainer = ElectricCyanMuted,
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = EmeraldSuccess,
    onTertiary = Color(0xFF002116),
    tertiaryContainer = EmeraldSurface,
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = StudioMidnight,
    onBackground = TextPrimaryDark,
    surface = StudioSlate,
    onSurface = TextPrimaryDark,
    surfaceVariant = StudioCard,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = StudioMidnight,
    surfaceContainerLow = StudioAbyss,
    surfaceContainer = StudioSlate,
    surfaceContainerHigh = StudioCard,
    surfaceContainerHighest = StudioCardElevated,
    outline = StudioBorder,
    outlineVariant = Color(0xFF1F2A44),
    error = CoralError,
    onError = Color.White,
    errorContainer = CoralSurface,
    onErrorContainer = Color(0xFFFECDD3),
  )

internal val BSubLightColorScheme =
  lightColorScheme(
    primary = AmberLightPrimary,
    onPrimary = Color.White,
    primaryContainer = AmberLightContainer,
    onPrimaryContainer = Color(0xFF451A03),
    secondary = CyanLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = CyanLightContainer,
    onSecondaryContainer = Color(0xFF083344),
    tertiary = Color(0xFF047857),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    background = LightCanvas,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerLowest = Color(0xFFF1F5F9),
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightSurface,
    surfaceContainerHighest = Color(0xFFE2E8F0),
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = Color(0xFFBE123C),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
  )

val BSubShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun BSubTranslatorTheme(
  themeMode: ThemeMode = ThemeMode.DARK,
  onThemeModeChange: (ThemeMode) -> Unit = {},
  animateTransitions: Boolean = true,
  content: @Composable () -> Unit,
) {
  ThemeProvider(
    themeMode = themeMode,
    onThemeModeChange = onThemeModeChange,
    animateTransitions = animateTransitions,
    content = content,
  )
}
