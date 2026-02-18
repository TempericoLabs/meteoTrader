package com.polymeteo.meteotrader.data.model

import java.time.Instant
import java.time.LocalDate

enum class TempUnit(val symbol: String) {
    C("C"),
    F("F")
}

enum class SourceStatus {
    SUCCESS,
    ERROR,
    SKIPPED
}

enum class MarketConditionType {
    GREATER_OR_EQUAL,
    LESS_OR_EQUAL,
    EXACT,
    BETWEEN
}

enum class TraderDirection {
    OVER,
    UNDER,
    RANGE
}

enum class TraderSignalLevel {
    GREEN,
    YELLOW,
    RED
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

data class MarketRangeCondition(
    val type: MarketConditionType,
    val threshold: Double,
    val upperThreshold: Double? = null,
    val unit: TempUnit,
    val targetDate: LocalDate?
)

data class TraderOpportunity(
    val marketId: String,
    val question: String,
    val condition: MarketRangeCondition,
    val yesPrice: Double,
    val noPrice: Double,
    val modelProbabilityYes: Double,
    val expectedEdge: Double,
    val recommendedBuy: String,
    val direction: TraderDirection,
    val signal: TraderSignalLevel,
    val liquidity: Double?,
    val volume24h: Double?,
    val spread: Double?
)

data class PolymarketSnapshot(
    val query: String,
    val fetchedAt: Instant,
    val marketsScanned: Int,
    val opportunities: List<TraderOpportunity>,
    val topOpportunity: TraderOpportunity?,
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
    val polymarket: PolymarketSnapshot,
    val updatedAt: Instant,
    val warnings: List<String>
)
