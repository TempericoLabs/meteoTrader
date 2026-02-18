package com.polymeteo.meteotrader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AppColors = darkColorScheme(
    primary = Accent,
    onPrimary = DarkBase,
    surface = DarkPanel,
    background = DarkBase,
    onSurface = LightInk,
    onBackground = LightInk
)

@Composable
fun MeteoTraderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        content = content
    )
}
