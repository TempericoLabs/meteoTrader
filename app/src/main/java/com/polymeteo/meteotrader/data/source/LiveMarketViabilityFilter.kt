package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.DecisionTraceEntry
import com.polymeteo.meteotrader.data.model.DecisionTraceStatus
import com.polymeteo.meteotrader.data.model.MarketConditionType
import com.polymeteo.meteotrader.data.model.MarketRangeCondition
import com.polymeteo.meteotrader.data.model.PolymarketSnapshot
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.util.celsiusToFahrenheit
import java.time.LocalDate
import java.util.Locale
import kotlin.math.truncate

object LiveMarketViabilityFilter {

    fun apply(
        snapshot: PolymarketSnapshot,
        observedMaxC: Double?,
        cityToday: LocalDate
    ): PolymarketSnapshot {
        if (observedMaxC == null || snapshot.opportunities.isEmpty()) {
            return snapshot
        }

        val discardedTraces = mutableListOf<DecisionTraceEntry>()
        val filtered = snapshot.opportunities.filter { opportunity ->
            val targetDate = opportunity.condition.targetDate
            if (targetDate != null && targetDate != cityToday) {
                true
            } else {
                val possible = isMarketStillPossibleForToday(
                    condition = opportunity.condition,
                    observedMaxC = observedMaxC
                )
                if (!possible) {
                    val observedInUnit = observedInConditionUnit(
                        observedMaxC = observedMaxC,
                        unit = opportunity.condition.unit
                    )
                    val observedTruncated = truncate(observedInUnit)
                    discardedTraces += DecisionTraceEntry(
                        marketId = opportunity.marketId,
                        question = opportunity.question,
                        targetDate = opportunity.condition.targetDate,
                        stage = TRACE_STAGE_LIVE_VIABILITY,
                        status = DecisionTraceStatus.DISCARDED,
                        reason = "Mercado imposible: la máxima observada truncada ya invalida este bucket",
                        details = listOf(
                            "Observada truncada: ${formatTemp(observedTruncated, opportunity.condition.unit)}",
                            "Condición: ${describeCondition(opportunity.condition)}"
                        )
                    )
                }
                possible
            }
        }
        val topToday = filtered
            .filter { it.condition.targetDate == null || it.condition.targetDate == cityToday }
            .maxByOrNull { it.executableEdge }

        return snapshot.copy(
            opportunities = filtered,
            topOpportunity = topToday ?: filtered.firstOrNull(),
            decisionTrace = snapshot.decisionTrace + discardedTraces
        )
    }

    fun isMarketStillPossibleForToday(
        condition: MarketRangeCondition,
        observedMaxC: Double
    ): Boolean {
        val observedInConditionUnit = observedInConditionUnit(observedMaxC, condition.unit)
        val truncatedObservedDegree = truncate(observedInConditionUnit)
        return when (condition.type) {
            MarketConditionType.GREATER_OR_EQUAL -> true
            MarketConditionType.LESS_OR_EQUAL -> condition.threshold + EPSILON >= truncatedObservedDegree
            MarketConditionType.EXACT -> condition.threshold + EPSILON >= truncatedObservedDegree
            MarketConditionType.BETWEEN -> {
                (condition.upperThreshold ?: condition.threshold) + EPSILON >= truncatedObservedDegree
            }
        }
    }

    private fun observedInConditionUnit(observedMaxC: Double, unit: TempUnit): Double {
        return if (unit == TempUnit.C) observedMaxC else celsiusToFahrenheit(observedMaxC)
    }

    private fun describeCondition(condition: MarketRangeCondition): String {
        return when (condition.type) {
            MarketConditionType.GREATER_OR_EQUAL -> ">= ${formatTemp(condition.threshold, condition.unit)}"
            MarketConditionType.LESS_OR_EQUAL -> "<= ${formatTemp(condition.threshold, condition.unit)}"
            MarketConditionType.EXACT -> "= ${formatTemp(condition.threshold, condition.unit)}"
            MarketConditionType.BETWEEN -> {
                val upper = condition.upperThreshold ?: condition.threshold
                "${formatTemp(condition.threshold, condition.unit)} - ${formatTemp(upper, condition.unit)}"
            }
        }
    }

    private fun formatTemp(value: Double, unit: TempUnit): String {
        return String.format(Locale.US, "%.0f°%s", value, unit.symbol)
    }

    private const val EPSILON = 0.001
    private const val TRACE_STAGE_LIVE_VIABILITY = "LIVE_VIABILITY"
}
