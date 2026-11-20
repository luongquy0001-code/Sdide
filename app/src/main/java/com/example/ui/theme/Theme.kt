package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalSyntaxColors = staticCompositionLocalOf { DarkSyntaxColors }

private val SdDarkColorScheme = darkColorScheme(
  primary = SdOrangeLight,
  onPrimary = Color(0xFF0F172A),
  primaryContainer = Color(0xFF431407),
  onPrimaryContainer = Color(0xFFFED7AA),
  secondary = SdppLimeBright,
  onSecondary = Color(0xFF0F172A),
  secondaryContainer = Color(0xFF1A2E05),
  onSecondaryContainer = Color(0xFFD9F99D),
  tertiary = Part3VioletBright,
  onTertiary = Color(0xFF0F172A),
  tertiaryContainer = Color(0xFF2E1065),
  onTertiaryContainer = Color(0xFFDDD6FE),
  background = DarkBackground,
  onBackground = DarkTextPrimary,
  surface = DarkSurface,
  onSurface = DarkTextPrimary,
  surfaceVariant = DarkSurfaceVariant,
  onSurfaceVariant = DarkTextSecondary,
  outline = DarkBorder,
  error = ErrorRose,
  onError = Color.White,
)

private val SdLightColorScheme = lightColorScheme(
  primary = SdOrange,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFFFEDD5),
  onPrimaryContainer = Color(0xFF7C2D12),
  secondary = SdppLime,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFECFCCB),
  onSecondaryContainer = Color(0xFF365314),
  tertiary = Part3Violet,
  onTertiary = Color.White,
  tertiaryContainer = Color(0xFFEDE9FE),
  onTertiaryContainer = Color(0xFF4C1D95),
  background = LightBackground,
  onBackground = LightTextPrimary,
  surface = LightSurface,
  onSurface = LightTextPrimary,
  surfaceVariant = LightSurfaceVariant,
  onSurfaceVariant = LightTextSecondary,
  outline = LightBorder,
  error = ErrorRose,
  onError = Color.White,
)

@Composable
fun SdIdeTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) SdDarkColorScheme else SdLightColorScheme
  val syntaxColors = if (darkTheme) DarkSyntaxColors else LightSyntaxColors
  CompositionLocalProvider(LocalSyntaxColors provides syntaxColors) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = AppTypography,
      content = content,
    )
  }
}
