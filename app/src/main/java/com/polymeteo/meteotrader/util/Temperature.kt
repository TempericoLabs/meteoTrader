package com.polymeteo.meteotrader.util

import com.polymeteo.meteotrader.data.model.TempUnit
import kotlin.math.roundToInt

fun celsiusToFahrenheit(celsius: Double): Double = celsius * 9.0 / 5.0 + 32.0

fun fahrenheitToCelsius(fahrenheit: Double): Double = (fahrenheit - 32.0) * 5.0 / 9.0

fun convertTemperature(value: Double, from: TempUnit, to: TempUnit): Double {
    if (from == to) return value
    return if (from == TempUnit.C) celsiusToFahrenheit(value) else fahrenheitToCelsius(value)
}

fun formatTemperature(value: Double?, unit: TempUnit, digits: Int = 1): String {
    if (value == null) return "--"
    val rounded = when (digits) {
        0 -> value.roundToInt().toString()
        else -> String.format(java.util.Locale.US, "%.${digits}f", value)
    }
    return "$rounded°${unit.symbol}"
}

fun formatDelta(value: Double?, unit: TempUnit): String {
    if (value == null) return "--"
    val sign = when {
        value > 0 -> "+"
        value < 0 -> ""
        else -> "±"
    }
    return "$sign${String.format(java.util.Locale.US, "%.1f", value)}°${unit.symbol}"
}
