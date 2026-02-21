package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.DecisionTraceStatus
import com.polymeteo.meteotrader.data.model.MarketConditionType
import com.polymeteo.meteotrader.data.model.MarketRangeCondition
import com.polymeteo.meteotrader.data.model.PolymarketSnapshot
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.data.model.TraderDirection
import com.polymeteo.meteotrader.data.model.TraderOpportunity
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class LiveMarketViabilityFilterTest {

    @Test
    fun filter_keepsThresholdMatchingTruncatedObserved_and_discardsLowerExact() {
        val today = LocalDate.of(2026, 2, 19)
        val snapshot = PolymarketSnapshot(
            query = "highest temperature in Sao Paulo",
            fetchedAt = Instant.now(),
            marketsScanned = 2,
            opportunities = listOf(
                opportunity(
                    id = "m-28",
                    question = "Will the highest temperature in Sao Paulo be 28°C on February 19?",
                    condition = MarketRangeCondition(MarketConditionType.EXACT, threshold = 28.0, unit = TempUnit.C, targetDate = today)
                ),
                opportunity(
                    id = "m-29",
                    question = "Will the highest temperature in Sao Paulo be 29°C on February 19?",
                    condition = MarketRangeCondition(MarketConditionType.EXACT, threshold = 29.0, unit = TempUnit.C, targetDate = today)
                )
            ),
            topOpportunity = null,
            error = null
        )

        val filtered = LiveMarketViabilityFilter.apply(
            snapshot = snapshot,
            observedMaxC = 29.9,
            cityToday = today
        )

        assertEquals(listOf("m-29"), filtered.opportunities.map { it.marketId })
        assertTrue(
            filtered.decisionTrace.any { trace ->
                trace.marketId == "m-28" &&
                    trace.status == DecisionTraceStatus.DISCARDED &&
                    trace.stage == "LIVE_VIABILITY"
            }
        )
    }

    @Test
    fun filter_keepsGreaterOrEqualEvenWhenObservedAlreadyAboveThreshold() {
        val today = LocalDate.of(2026, 2, 19)
        val snapshot = PolymarketSnapshot(
            query = "highest temperature in Sao Paulo",
            fetchedAt = Instant.now(),
            marketsScanned = 1,
            opportunities = listOf(
                opportunity(
                    id = "m-ge-27",
                    question = "Will the highest temperature in Sao Paulo be 27°C or higher on February 19?",
                    condition = MarketRangeCondition(
                        type = MarketConditionType.GREATER_OR_EQUAL,
                        threshold = 27.0,
                        unit = TempUnit.C,
                        targetDate = today
                    )
                )
            ),
            topOpportunity = null,
            error = null
        )

        val filtered = LiveMarketViabilityFilter.apply(
            snapshot = snapshot,
            observedMaxC = 29.4,
            cityToday = today
        )

        assertEquals(1, filtered.opportunities.size)
        assertEquals("m-ge-27", filtered.opportunities.first().marketId)
    }

    @Test
    fun filter_doesNotApplyTodayRuleToFutureMarkets() {
        val today = LocalDate.of(2026, 2, 19)
        val tomorrow = today.plusDays(1)
        val snapshot = PolymarketSnapshot(
            query = "highest temperature in London",
            fetchedAt = Instant.now(),
            marketsScanned = 1,
            opportunities = listOf(
                opportunity(
                    id = "m-future-6",
                    question = "Will the highest temperature in London be 6°C on February 20?",
                    condition = MarketRangeCondition(
                        type = MarketConditionType.EXACT,
                        threshold = 6.0,
                        unit = TempUnit.C,
                        targetDate = tomorrow
                    )
                )
            ),
            topOpportunity = null,
            error = null
        )

        val filtered = LiveMarketViabilityFilter.apply(
            snapshot = snapshot,
            observedMaxC = 9.0,
            cityToday = today
        )

        assertEquals(1, filtered.opportunities.size)
        assertEquals("m-future-6", filtered.opportunities.first().marketId)
    }

    private fun opportunity(
        id: String,
        question: String,
        condition: MarketRangeCondition
    ): TraderOpportunity {
        return TraderOpportunity(
            marketId = id,
            question = question,
            condition = condition,
            yesPrice = 0.50,
            noPrice = 0.50,
            modelProbabilityYes = 0.50,
            expectedEdge = 0.04,
            rawEdge = 0.04,
            edgeAfterCosts = 0.03,
            executableEdge = 0.03,
            fillProbability = 0.70,
            feeCost = 0.01,
            spreadCost = 0.01,
            liquidityCost = 0.01,
            totalCost = 0.03,
            shouldTrade = true,
            recommendedBuy = "YES",
            direction = TraderDirection.RANGE,
            signal = TraderSignalLevel.YELLOW,
            liquidity = 1500.0,
            volume24h = 1000.0,
            spread = 0.03
        )
    }
}
