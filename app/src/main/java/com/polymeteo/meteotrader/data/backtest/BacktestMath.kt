package com.polymeteo.meteotrader.data.backtest

import com.polymeteo.meteotrader.data.model.MarketConditionType
import kotlin.math.ln
import kotlin.math.pow

data class TradeEvaluation(
    val hit: Boolean,
    val stakeUnits: Double,
    val pnlUnits: Double,
    val brierScore: Double,
    val logLoss: Double
)

object BacktestMath {

    fun evaluateOutcome(
        conditionType: MarketConditionType,
        threshold: Double,
        upperThreshold: Double?,
        observed: Double
    ): Boolean {
        return when (conditionType) {
            MarketConditionType.GREATER_OR_EQUAL -> observed >= threshold
            MarketConditionType.LESS_OR_EQUAL -> observed <= threshold
            MarketConditionType.EXACT -> observed >= (threshold - 0.5) && observed < (threshold + 0.5)
            MarketConditionType.BETWEEN -> {
                val upper = upperThreshold ?: threshold
                val low = minOf(threshold, upper)
                val high = maxOf(threshold, upper)
                observed in low..high
            }
        }
    }

    fun evaluateTrade(
        recommendedBuy: String,
        yesPrice: Double,
        noPrice: Double,
        modelProbabilityYes: Double,
        actualYes: Boolean
    ): TradeEvaluation {
        val buyYes = recommendedBuy.equals("YES", ignoreCase = true)
        val hit = if (buyYes) actualYes else !actualYes
        val stake = if (buyYes) yesPrice else noPrice
        val pnl = (if (hit) 1.0 else 0.0) - stake
        val p = modelProbabilityYes.coerceIn(0.001, 0.999)
        val actual = if (actualYes) 1.0 else 0.0
        val brier = (p - actual).pow(2)
        val logLoss = -((actual * ln(p)) + ((1.0 - actual) * ln(1.0 - p)))

        return TradeEvaluation(
            hit = hit,
            stakeUnits = stake,
            pnlUnits = pnl,
            brierScore = brier,
            logLoss = logLoss
        )
    }

    fun didHit(recommendedBuy: String, actualYes: Boolean): Boolean {
        return if (recommendedBuy.equals("YES", ignoreCase = true)) actualYes else !actualYes
    }
}
