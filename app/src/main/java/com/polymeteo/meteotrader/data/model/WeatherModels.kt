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

data class TafSnapshot(
    val sourceUrl: String,
    val issuedAt: Instant?,
    val validFrom: Instant?,
    val validTo: Instant?,
    val rawText: String?,
    val summary: String?,
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

data class ForecastHorizonData(
    val targetDate: LocalDate,
    val forecasts: List<ForecastSourceResult>,
    val polyTempC: Double?,
    val polyTempF: Double?,
    val polyTempInvalid: Boolean,
    val polyTempPremiumC: Double?,
    val polyTempPremiumF: Double?,
    val polyTempPremiumInvalid: Boolean
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
    val rawEdge: Double = expectedEdge,
    val edgeAfterCosts: Double = expectedEdge,
    val executableEdge: Double = expectedEdge,
    val fillProbability: Double = 1.0,
    val feeCost: Double = 0.0,
    val spreadCost: Double = 0.0,
    val liquidityCost: Double = 0.0,
    val totalCost: Double = 0.0,
    val shouldTrade: Boolean = expectedEdge > 0.0,
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
    val taf: TafSnapshot,
    val controlStation: ControlStationSnapshot,
    val horizons: List<ForecastHorizonData>,
    val forecasts: List<ForecastSourceResult>,
    val observedMaxC: Double?,
    val polyTempC: Double?,
    val polyTempF: Double?,
    val polyTempInvalid: Boolean,
    val polyTempPremiumC: Double?,
    val polyTempPremiumF: Double?,
    val polyTempPremiumInvalid: Boolean,
    val polymarket: PolymarketSnapshot,
    val updatedAt: Instant,
    val warnings: List<String>
)
