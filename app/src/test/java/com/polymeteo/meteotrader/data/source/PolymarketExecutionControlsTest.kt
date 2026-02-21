package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.DecisionTraceStatus
import com.polymeteo.meteotrader.data.model.MarketConditionType
import com.polymeteo.meteotrader.data.model.MarketRangeCondition
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.data.model.TraderDirection
import com.polymeteo.meteotrader.data.model.TraderOpportunity
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PolymarketExecutionControlsTest {

    private val source = PolymarketSource(httpClient = HttpClient())

    @Test
    fun dominance_discardsTodayWhenYesProbabilityIsExtreme() {
        val today = LocalDate.of(2026, 2, 19)
        val markets = listOf(
            baseOpportunity(id = "m1", yesPrice = 0.97),
            baseOpportunity(id = "m2", yesPrice = 0.25)
        )

        val dominated = source.evaluateDominanceForTesting(
            dayMarkets = markets,
            targetDate = today,
            cityToday = today,
            localHour = 14
        )

        assertTrue(dominated)
    }

    @Test
    fun executionControl_discardsWhenLiquidityIsLow() {
        val today = LocalDate.of(2026, 2, 19)
        val traces = source.applyRecommendationControlsForTesting(
            opportunities = listOf(baseOpportunity(id = "liq-low", liquidity = 120.0)),
            cityToday = today,
            localHour = 10
        )

        val trace = traces.firstOrNull { it.marketId == "liq-low" }
        assertEquals(DecisionTraceStatus.DISCARDED, trace?.status)
        assertTrue(trace?.reason?.contains("Liquidez", ignoreCase = true) == true)
    }

    @Test
    fun executionControl_keepsMarketWhenThresholdsPass() {
        val today = LocalDate.of(2026, 2, 19)
        val traces = source.applyRecommendationControlsForTesting(
            opportunities = listOf(
                baseOpportunity(
                    id = "kept-1",
                    liquidity = 2_500.0,
                    volume24h = 2_000.0,
                    spread = 0.03,
                    fillProbability = 0.80,
                    executableEdge = 0.08,
                    shouldTrade = true
                )
            ),
            cityToday = today,
            localHour = 10
        )

        val trace = traces.firstOrNull { it.marketId == "kept-1" }
        assertEquals(DecisionTraceStatus.KEPT, trace?.status)
        assertTrue(trace?.reason?.contains("pasa control", ignoreCase = true) == true)
    }

    private fun baseOpportunity(
        id: String,
        yesPrice: Double = 0.40,
        noPrice: Double = 0.60,
        liquidity: Double = 2_000.0,
        volume24h: Double = 1_600.0,
        spread: Double = 0.04,
        fillProbability: Double = 0.70,
        executableEdge: Double = 0.06,
        shouldTrade: Boolean = true
    ): TraderOpportunity {
        return TraderOpportunity(
            marketId = id,
            question = "Will the highest temperature in London be 7°C on February 19?",
            condition = MarketRangeCondition(
                type = MarketConditionType.EXACT,
                threshold = 7.0,
                upperThreshold = null,
                unit = TempUnit.C,
                targetDate = LocalDate.of(2026, 2, 19)
            ),
            yesPrice = yesPrice,
            noPrice = noPrice,
            modelProbabilityYes = 0.55,
            expectedEdge = executableEdge,
            rawEdge = executableEdge + 0.02,
            edgeAfterCosts = executableEdge + 0.01,
            executableEdge = executableEdge,
            fillProbability = fillProbability,
            feeCost = 0.01,
            spreadCost = 0.01,
            liquidityCost = 0.01,
            totalCost = 0.03,
            shouldTrade = shouldTrade,
            recommendedBuy = "YES",
            direction = TraderDirection.RANGE,
            signal = TraderSignalLevel.GREEN,
            liquidity = liquidity,
            volume24h = volume24h,
            spread = spread
        )
    }
}
