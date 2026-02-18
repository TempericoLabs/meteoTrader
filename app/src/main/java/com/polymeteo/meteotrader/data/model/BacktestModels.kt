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
    val executed: Boolean,
    val pnlUnits: Double,
    val expectedPnlUnits: Double,
    val executionGapUnits: Double,
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
    val executedTrades: Int,
    val fillRate: Double?,
    val hitRate: Double?,
    val totalPnlUnits: Double,
    val expectedPnlUnits: Double,
    val executionGapUnits: Double,
    val roi: Double?,
    val avgExpectedEdge: Double?,
    val brierScore: Double?
)

data class BacktestStrategyStat(
    val direction: TraderDirection,
    val signal: TraderSignalLevel,
    val settledTrades: Int,
    val executedTrades: Int,
    val fillRate: Double?,
    val hitRate: Double?,
    val totalPnlUnits: Double,
    val expectedPnlUnits: Double,
    val executionGapUnits: Double,
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
    val simulatedTrades: Int,
    val simulatedLiveTrades: Int,
    val simulatedHistoricalTrades: Int,
    val executedTrades: Int,
    val executedLiveTrades: Int,
    val executedHistoricalTrades: Int,
    val skippedByFillTrades: Int,
    val pendingTrades: Int,
    val fillRate: Double?,
    val totalStakeUnits: Double,
    val totalPnlUnits: Double,
    val expectedPnlUnits: Double,
    val executionGapUnits: Double,
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
                simulatedTrades = 0,
                simulatedLiveTrades = 0,
                simulatedHistoricalTrades = 0,
                executedTrades = 0,
                executedLiveTrades = 0,
                executedHistoricalTrades = 0,
                skippedByFillTrades = 0,
                pendingTrades = 0,
                fillRate = null,
                totalStakeUnits = 0.0,
                totalPnlUnits = 0.0,
                expectedPnlUnits = 0.0,
                executionGapUnits = 0.0,
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
