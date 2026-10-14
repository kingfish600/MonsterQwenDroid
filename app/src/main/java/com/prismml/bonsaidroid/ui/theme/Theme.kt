package com.prismml.bonsaidroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF3DDC84)
// Owner QoL decision (2026-10-05): the app is ALWAYS dark with a true-black background
// and white text - the system-light look had a washed gray background with poor contrast.
private val DarkScheme = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF00220F),
    secondary = Color(0xFF8FD9B5),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF050505),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF12171C),
    onSurfaceVariant = Color(0xFFE6E6E6),
)
@Suppress("unused")
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B7A47),
    secondary = Color(0xFF2E6B4F),
)
// Owner QoL decision (2026-10-07): chat/code text a step larger - answers were small at defaults.
private val BaseTypography = Typography()
private val BonsaiTypography = BaseTypography.copy(
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun BonsaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = BonsaiTypography,
        content = content,
    )
}
