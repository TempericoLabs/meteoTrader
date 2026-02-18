package com.polymeteo.meteotrader.util

import com.polymeteo.meteotrader.data.model.TraderDirection

fun formatPercent(value: Double?): String {
    if (value == null) return "--"
    return String.format(java.util.Locale.US, "%.1f%%", value * 100.0)
}

fun directionLabel(direction: TraderDirection): String {
    return when (direction) {
        TraderDirection.OVER -> "OVER"
        TraderDirection.UNDER -> "UNDER"
        TraderDirection.RANGE -> "RANGE"
    }
}
