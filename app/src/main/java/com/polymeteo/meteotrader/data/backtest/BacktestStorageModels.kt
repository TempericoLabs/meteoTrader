package com.polymeteo.meteotrader.data.backtest

import com.polymeteo.meteotrader.data.model.MarketConditionType
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.data.model.TraderDirection
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import kotlinx.serialization.Serializable

@Serializable
data class BacktestDataset(
    val records: List<BacktestRecord> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
    val historicalImportCompletedAtEpochMs: Long = 0L
)

@Serializable
data class BacktestRecord(
    val id: String,
    val cityId: String,
    val cityName: String,
    val cityZoneId: String,
    val marketId: String,
    val question: String,
    val targetDateIso: String,
    val conditionType: MarketConditionType,
    val threshold: Double,
    val upperThreshold: Double? = null,
    val conditionUnit: TempUnit,
    val snapshotEpochMs: Long,
    val yesPrice: Double,
    val noPrice: Double,
    val modelProbabilityYes: Double,
    val expectedEdge: Double,
    val recommendedBuy: String,
    val direction: TraderDirection,
    val signal: TraderSignalLevel,
    val liquidity: Double? = null,
    val volume24h: Double? = null,
    val spread: Double? = null,
    val polyTempC: Double? = null,
    val isHistoricalSeed: Boolean = false,
    val settled: Boolean = false,
    val settledEpochMs: Long? = null,
    val settlementSourceUrl: String? = null,
    val observedMaxInConditionUnit: Double? = null,
    val actualYesOutcome: Boolean? = null,
    val pnlUnits: Double? = null,
    val stakeUnits: Double? = null,
    val brierScore: Double? = null,
    val logLoss: Double? = null,
    val lastSettleAttemptEpochMs: Long? = null,
    val settleAttempts: Int = 0
)
