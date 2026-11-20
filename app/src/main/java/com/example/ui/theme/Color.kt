package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Dark Terminal Palette (Default IDE Vibe inspired by Sd & Sd++ Cover)
val DarkBackground = Color(0xFF0B1120)
val DarkSurface = Color(0xFF111827)
val DarkSurfaceVariant = Color(0xFF1E293B)
val DarkSurfaceHigh = Color(0xFF273449)
val DarkBorder = Color(0xFF334155)
val DarkTextPrimary = Color(0xFFF1F5F9)
val DarkTextSecondary = Color(0xFF94A3B8)

// Light Textbook Paper Palette
val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightSurfaceHigh = Color(0xFFE2E8F0)
val LightBorder = Color(0xFFCBD5E1)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)

// Brand & Semantic Accents
val SdOrange = Color(0xFFFF5722)
val SdOrangeLight = Color(0xFFFB923C)
val SdppLime = Color(0xFF65A30D)
val SdppLimeBright = Color(0xFF84CC16)
val Part3Violet = Color(0xFF8B5CF6)
val Part3VioletBright = Color(0xFFA78BFA)
val InfoCyan = Color(0xFF06B6D4)
val WarnAmber = Color(0xFFF59E0B)
val ErrorRose = Color(0xFFF43F5E)
val SuccessEmerald = Color(0xFF10B981)

// Syntax Highlighting Tokens (Dark & Light)
data class SyntaxColors(
  val keyword: Color,
  val type: Color,
  val flag: Color,
  val string: Color,
  val formatSpec: Color,
  val comment: Color,
  val number: Color,
  val function: Color,
  val normal: Color,
  val gutterBg: Color,
  val gutterText: Color,
  val activeLineBg: Color,
  val debugLineBg: Color,
  val breakpointDot: Color,
)

val DarkSyntaxColors = SyntaxColors(
  keyword = Color(0xFFFB923C),     // Electric Orange for sta/iid/ed/bolean/for/while
  type = Color(0xFF38BDF8),        // Sky Blue for int/float/str/bool/ptr
  flag = Color(0xFFA3E635),        // Neon Lime for $, ₫, €, $$, #, ##, :&, *m
  string = Color(0xFFFDE047),      // Warm Yellow for strings
  formatSpec = Color(0xFF2DD4BF),  // Teal for /d{}, /s{}, //e
  comment = Color(0xFF64748B),     // Muted Slate for comments
  number = Color(0xFFC084FC),      // Lavender for numbers & hex
  function = Color(0xFF60A5FA),    // Bright Blue for library/fn calls
  normal = Color(0xFFE2E8F0),
  gutterBg = Color(0xFF0D1526),
  gutterText = Color(0xFF475569),
  activeLineBg = Color(0xFF172338),
  debugLineBg = Color(0x33F59E0B),
  breakpointDot = Color(0xFFF43F5E),
)

val LightSyntaxColors = SyntaxColors(
  keyword = Color(0xFFEA580C),
  type = Color(0xFF0284C7),
  flag = Color(0xFF4D7C0F),
  string = Color(0xFFB45309),
  formatSpec = Color(0xFF0F766E),
  comment = Color(0xFF94A3B8),
  number = Color(0xFF7C3AED),
  function = Color(0xFF2563EB),
  normal = Color(0xFF0F172A),
  gutterBg = Color(0xFFF1F5F9),
  gutterText = Color(0xFF94A3B8),
  activeLineBg = Color(0xFFE2E8F0),
  debugLineBg = Color(0x33F59E0B),
  breakpointDot = Color(0xFFE11D48),
)
