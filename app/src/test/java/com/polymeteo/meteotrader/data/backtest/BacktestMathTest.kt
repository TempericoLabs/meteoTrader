package com.polymeteo.meteotrader.data.backtest

import com.polymeteo.meteotrader.data.model.MarketConditionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestMathTest {

    @Test
    fun `exact condition uses half degree bucket`() {
        assertTrue(
            BacktestMath.evaluateOutcome(
                conditionType = MarketConditionType.EXACT,
                threshold = 8.0,
                upperThreshold = null,
                observed = 7.6
            )
        )
        assertTrue(
            BacktestMath.evaluateOutcome(
                conditionType = MarketConditionType.EXACT,
                threshold = 8.0,
                upperThreshold = null,
                observed = 8.49
            )
        )
        assertFalse(
            BacktestMath.evaluateOutcome(
                conditionType = MarketConditionType.EXACT,
                threshold = 8.0,
                upperThreshold = null,
                observed = 8.5
            )
        )
    }

    @Test
    fun `between condition is inclusive on both sides`() {
        assertTrue(
            BacktestMath.evaluateOutcome(
                conditionType = MarketConditionType.BETWEEN,
                threshold = 4.0,
                upperThreshold = 6.0,
                observed = 4.0
            )
        )
        assertTrue(
            BacktestMath.evaluateOutcome(
                conditionType = MarketConditionType.BETWEEN,
                threshold = 4.0,
                upperThreshold = 6.0,
                observed = 6.0
            )
        )
        assertFalse(
            BacktestMath.evaluateOutcome(
                conditionType = MarketConditionType.BETWEEN,
                threshold = 4.0,
                upperThreshold = 6.0,
                observed = 6.1
            )
        )
    }

    @Test
    fun `trade evaluation computes pnl for yes side`() {
        val result = BacktestMath.evaluateTrade(
            recommendedBuy = "YES",
            yesPrice = 0.36,
            noPrice = 0.64,
            modelProbabilityYes = 0.58,
            actualYes = true
        )

        assertTrue(result.hit)
        assertEquals(0.36, result.stakeUnits, 0.0001)
        assertEquals(0.64, result.pnlUnits, 0.0001)
    }

    @Test
    fun `trade evaluation computes pnl for no side`() {
        val result = BacktestMath.evaluateTrade(
            recommendedBuy = "NO",
            yesPrice = 0.71,
            noPrice = 0.29,
            modelProbabilityYes = 0.42,
            actualYes = false
        )

        assertTrue(result.hit)
        assertEquals(0.29, result.stakeUnits, 0.0001)
        assertEquals(0.71, result.pnlUnits, 0.0001)
        assertTrue(result.logLoss > 0.0)
    }
}
