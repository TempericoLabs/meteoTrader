package com.polymeteo.meteotrader.data.backtest

import com.polymeteo.meteotrader.data.model.MarketConditionType
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.data.model.TraderDirection
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class RiskGuardrailsTest {

    private val config = RiskGuardrailConfig(
        maxTradesPerDay = 2,
        maxStakePerDayUnits = 1.2,
        maxDailyLossUnits = 0.8,
        maxLossPerMarketUnits = 0.4,
        minLiquidity = 900.0,
        maxSpread = 0.045,
        maxConsecutiveLosses = 3
    )

    @Test
    fun evaluate_blocksLowLiquidity() {
        val day = LocalDate.of(2026, 2, 18)
        val state = RiskGuardrails.buildState(emptyList(), day, config)
        val lowLiquidity = sampleRecord(
            id = "a",
            day = day,
            marketId = "m1",
            liquidity = 120.0,
            spread = 0.02
        )

        val reason = RiskGuardrails.evaluate(lowLiquidity, state, config)
        assertEquals(RiskBlockReason.LOW_LIQUIDITY, reason)
    }

    @Test
    fun buildState_activatesKillSwitchByDailyLoss() {
        val day = LocalDate.of(2026, 2, 18)
        val settledLoss = sampleRecord(
            id = "loss-1",
            day = day,
            marketId = "m1",
            settled = true,
            executed = true,
            pnl = -0.9
        )

        val state = RiskGuardrails.buildState(listOf(settledLoss), day, config)
        assertTrue(state.killSwitchActive)
        assertTrue(state.realizedPnlUnits <= -config.maxDailyLossUnits)
    }

    @Test
    fun evaluate_blocksByDailyTradeLimit() {
        val day = LocalDate.of(2026, 2, 18)
        val r1 = sampleRecord("r1", day, "m1")
        val r2 = sampleRecord("r2", day, "m2")
        val state = RiskGuardrails.buildState(listOf(r1, r2), day, config)

        val incoming = sampleRecord("r3", day, "m3")
        val reason = RiskGuardrails.evaluate(incoming, state, config)

        assertNotNull(reason)
        assertEquals(RiskBlockReason.DAILY_TRADE_LIMIT, reason)
    }

    private fun sampleRecord(
        id: String,
        day: LocalDate,
        marketId: String,
        liquidity: Double = 2000.0,
        spread: Double = 0.02,
        settled: Boolean = false,
        executed: Boolean = false,
        pnl: Double? = null
    ): BacktestRecord {
        val snapshotEpoch = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        return BacktestRecord(
            id = id,
            cityId = "london",
            cityName = "London",
            cityZoneId = "Europe/London",
            marketId = marketId,
            question = "Will test market resolve?",
            targetDateIso = day.toString(),
            conditionType = MarketConditionType.GREATER_OR_EQUAL,
            threshold = 10.0,
            upperThreshold = null,
            conditionUnit = TempUnit.C,
            snapshotEpochMs = snapshotEpoch,
            yesPrice = 0.45,
            noPrice = 0.55,
            modelProbabilityYes = 0.60,
            expectedEdge = 0.08,
            rawEdge = 0.10,
            executableEdge = 0.08,
            fillProbabilityEstimate = 0.70,
            feeCostEstimate = 0.01,
            spreadCostEstimate = 0.01,
            liquidityCostEstimate = 0.01,
            totalCostEstimate = 0.03,
            recommendedBuy = "YES",
            direction = TraderDirection.OVER,
            signal = TraderSignalLevel.YELLOW,
            liquidity = liquidity,
            volume24h = 2500.0,
            spread = spread,
            polyTempC = 11.5,
            isHistoricalSeed = false,
            settled = settled,
            settledEpochMs = if (settled) Instant.now().toEpochMilli() else null,
            settlementSourceUrl = null,
            observedMaxInConditionUnit = null,
            actualYesOutcome = null,
            executed = executed,
            expectedPnlUnits = null,
            executionGapUnits = null,
            fillProbabilityUsed = null,
            fillDraw = null,
            entryPriceUnits = null,
            entryFeeUnits = null,
            entrySlippageUnits = null,
            exitPayoutUnits = null,
            pnlUnits = pnl,
            stakeUnits = if (executed) 0.48 else null,
            brierScore = null,
            logLoss = null,
            lastSettleAttemptEpochMs = null,
            settleAttempts = 0
        )
    }
}
