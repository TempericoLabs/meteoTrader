package com.polymeteo.meteotrader.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Instant?.formatUtc(pattern: String = "yyyy-MM-dd HH:mm 'UTC'"): String {
    if (this == null) return "--"
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)
    return formatter.format(this.atZone(ZoneId.of("UTC")))
}

fun Instant?.formatInZone(zoneId: String, pattern: String = "yyyy-MM-dd HH:mm"): String {
    if (this == null) return "--"
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.US)
    return formatter.format(this.atZone(ZoneId.of(zoneId)))
}
