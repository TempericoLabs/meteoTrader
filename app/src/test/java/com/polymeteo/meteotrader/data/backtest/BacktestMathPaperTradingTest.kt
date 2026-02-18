package com.polymeteo.meteotrader.data.backtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestMathPaperTradingTest {

    @Test
    fun evaluatePaperTrade_executesWhenFillDrawIsBelowProbability() {
        val evaluation = BacktestMath.evaluatePaperTrade(
            recommendedBuy = "YES",
            yesPrice = 0.42,
            noPrice = 0.58,
            modelProbabilityYes = 0.63,
            actualYes = true,
            expectedEdge = 0.08,
            fillProbabilityEstimate = 0.90,
            spread = 0.01,
            liquidity = 5_000.0,
            volume24h = 8_000.0,
            fillDraw = 0.10
        )

        assertTrue(evaluation.executed)
        assertTrue(evaluation.stakeUnits > 0.0)
        assertTrue(evaluation.pnlUnits > 0.0)
        assertEquals(
            evaluation.pnlUnits - evaluation.expectedPnlUnits,
            evaluation.executionGapUnits,
            1e-9
        )
    }

    @Test
    fun evaluatePaperTrade_skipsWhenFillDrawIsAboveProbability() {
        val evaluation = BacktestMath.evaluatePaperTrade(
            recommendedBuy = "NO",
            yesPrice = 0.70,
            noPrice = 0.30,
            modelProbabilityYes = 0.62,
            actualYes = true,
            expectedEdge = 0.02,
            fillProbabilityEstimate = 0.20,
            spread = 0.05,
            liquidity = 250.0,
            volume24h = 120.0,
            fillDraw = 0.95
        )

        assertFalse(evaluation.executed)
        assertEquals(0.0, evaluation.stakeUnits, 1e-9)
        assertEquals(0.0, evaluation.pnlUnits, 1e-9)
        assertEquals(
            0.0 - evaluation.expectedPnlUnits,
            evaluation.executionGapUnits,
            1e-9
        )
    }
}
