package com.polymeteo.meteotrader.data.model

import java.time.Instant
import java.time.LocalDate

data class BacktestSettlementPreview(
    val cityName: String,
    val question: String,
    val targetDate: LocalDate,
    val recommendedBuy: String,
    val direction: TraderDirection,
    val signal: TraderSignalLevel,
    val pnlUnits: Double,
    val hit: Boolean,
    val expectedEdge: Double,
    val observedMaxInConditionUnit: Double?,
    val conditionUnit: TempUnit,
    val settledAt: Instant,
    val snapshotAt: Instant
)

data class BacktestCityStat(
    val cityId: String,
    val cityName: String,
    val settledTrades: Int,
    val hitRate: Double?,
    val totalPnlUnits: Double,
    val roi: Double?,
    val avgExpectedEdge: Double?,
    val brierScore: Double?
)

data class BacktestStrategyStat(
    val direction: TraderDirection,
    val signal: TraderSignalLevel,
    val settledTrades: Int,
    val hitRate: Double?,
    val totalPnlUnits: Double,
    val roi: Double?,
    val avgExpectedEdge: Double?
)

data class BacktestReport(
    val generatedAt: Instant,
    val totalTrades: Int,
    val liveTrades: Int,
    val historicalTrades: Int,
    val settledTrades: Int,
    val settledLiveTrades: Int,
    val settledHistoricalTrades: Int,
    val pendingTrades: Int,
    val totalStakeUnits: Double,
    val totalPnlUnits: Double,
    val avgPnlUnits: Double?,
    val roi: Double?,
    val hitRate: Double?,
    val avgExpectedEdge: Double?,
    val brierScore: Double?,
    val logLoss: Double?,
    val cityStats: List<BacktestCityStat>,
    val strategyStats: List<BacktestStrategyStat>,
    val recentSettlements: List<BacktestSettlementPreview>,
    val warnings: List<String>
) {
    companion object {
        fun empty(now: Instant = Instant.now()): BacktestReport {
            return BacktestReport(
                generatedAt = now,
                totalTrades = 0,
                liveTrades = 0,
                historicalTrades = 0,
                settledTrades = 0,
                settledLiveTrades = 0,
                settledHistoricalTrades = 0,
                pendingTrades = 0,
                totalStakeUnits = 0.0,
                totalPnlUnits = 0.0,
                avgPnlUnits = null,
                roi = null,
                hitRate = null,
                avgExpectedEdge = null,
                brierScore = null,
                logLoss = null,
                cityStats = emptyList(),
                strategyStats = emptyList(),
                recentSettlements = emptyList(),
                warnings = emptyList()
            )
        }
    }
}
