package com.polymeteo.meteotrader.data.model

import java.time.Instant

enum class TempUnit(val symbol: String) {
    C("C"),
    F("F")
}

enum class SourceStatus {
    SUCCESS,
    ERROR,
    SKIPPED
}

data class CityConfig(
    val id: String,
    val name: String,
    val metarCode: String,
    val latitude: Double,
    val longitude: Double,
    val zoneId: String,
    val displayUnit: TempUnit,
    val wundergroundControlUrl: String,
    val wundergroundPwsUrl: String
)

data class MetarReading(
    val observedAt: Instant,
    val tempC: Double?,
    val tempF: Double?,
    val rawText: String?
)

data class MetarSnapshot(
    val sourceUrl: String,
    val current: MetarReading?,
    val previousSameDay: MetarReading?,
    val error: String?
)

data class ControlStationSnapshot(
    val sourceUrl: String?,
    val tempC: Double?,
    val tempF: Double?,
    val error: String?
)

data class ForecastSourceResult(
    val sourceId: String,
    val sourceName: String,
    val status: SourceStatus,
    val maxTempC: Double?,
    val maxTempF: Double?,
    val currentTempC: Double?,
    val currentTempF: Double?,
    val error: String?
)

data class CityWeatherData(
    val city: CityConfig,
    val localTime: String,
    val metar: MetarSnapshot,
    val controlStation: ControlStationSnapshot,
    val forecasts: List<ForecastSourceResult>,
    val polyTempC: Double?,
    val polyTempF: Double?,
    val updatedAt: Instant,
    val warnings: List<String>
)
