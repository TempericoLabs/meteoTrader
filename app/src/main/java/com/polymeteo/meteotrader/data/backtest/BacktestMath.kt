package com.polymeteo.meteotrader.data.backtest

import com.polymeteo.meteotrader.data.model.MarketConditionType
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class TradeEvaluation(
    val hit: Boolean,
    val stakeUnits: Double,
    val pnlUnits: Double,
    val brierScore: Double,
    val logLoss: Double
)

data class PaperTradeEvaluation(
    val executed: Boolean,
    val hit: Boolean,
    val stakeUnits: Double,
    val pnlUnits: Double,
    val expectedPnlUnits: Double,
    val executionGapUnits: Double,
    val fillProbabilityUsed: Double,
    val fillDraw: Double,
    val entryPriceUnits: Double?,
    val entryFeeUnits: Double?,
    val entrySlippageUnits: Double?,
    val exitPayoutUnits: Double?,
    val brierScore: Double,
    val logLoss: Double
)

object BacktestMath {

    private const val ENTRY_FEE_RATE = 0.012
    private const val DEFAULT_SPREAD = 0.020

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

    fun evaluatePaperTrade(
        recommendedBuy: String,
        yesPrice: Double,
        noPrice: Double,
        modelProbabilityYes: Double,
        actualYes: Boolean,
        expectedEdge: Double,
        fillProbabilityEstimate: Double?,
        spread: Double?,
        liquidity: Double?,
        volume24h: Double?,
        fillDraw: Double
    ): PaperTradeEvaluation {
        val buyYes = recommendedBuy.equals("YES", ignoreCase = true)
        val marketPrice = if (buyYes) yesPrice else noPrice
        val pYes = modelProbabilityYes.coerceIn(0.001, 0.999)
        val probabilityBought = if (buyYes) pYes else 1.0 - pYes

        val fillProbability = (fillProbabilityEstimate ?: estimateFillProbability(liquidity, volume24h, spread))
            .coerceIn(0.08, 0.98)
        val spreadValue = (spread ?: DEFAULT_SPREAD).coerceIn(0.0, 0.12)
        val liquidityValue = (liquidity ?: 0.0).coerceAtLeast(0.0)
        val volumeValue = (volume24h ?: 0.0).coerceAtLeast(0.0)

        val entrySlippage = estimateEntrySlippage(spreadValue, liquidityValue, volumeValue)
        val entryFee = (marketPrice * ENTRY_FEE_RATE).coerceAtLeast(0.0)
        val entryCost = (marketPrice + entrySlippage + entryFee).coerceIn(0.001, 0.999)

        val expectedIfFilled = probabilityBought - entryCost
        val fallbackExpected = expectedEdge.coerceIn(-0.999, 0.999)
        val expectedPnl = if (expectedEdge.isFinite()) {
            (fillProbability * expectedIfFilled) + ((1.0 - fillProbability) * 0.0)
        } else {
            fallbackExpected
        }

        val isFilled = fillDraw.coerceIn(0.0, 1.0) <= fillProbability
        if (!isFilled) {
            val brierNoFill = ((pYes - if (actualYes) 1.0 else 0.0).pow(2))
            val logLossNoFill = -(((if (actualYes) 1.0 else 0.0) * ln(pYes)) + ((if (actualYes) 0.0 else 1.0) * ln(1.0 - pYes)))
            return PaperTradeEvaluation(
                executed = false,
                hit = false,
                stakeUnits = 0.0,
                pnlUnits = 0.0,
                expectedPnlUnits = expectedPnl,
                executionGapUnits = 0.0 - expectedPnl,
                fillProbabilityUsed = fillProbability,
                fillDraw = fillDraw,
                entryPriceUnits = null,
                entryFeeUnits = null,
                entrySlippageUnits = null,
                exitPayoutUnits = null,
                brierScore = brierNoFill,
                logLoss = logLossNoFill
            )
        }

        val hit = if (buyYes) actualYes else !actualYes
        val payout = if (hit) 1.0 else 0.0
        val pnl = payout - entryCost
        val actual = if (actualYes) 1.0 else 0.0
        val brier = (pYes - actual).pow(2)
        val logLoss = -((actual * ln(pYes)) + ((1.0 - actual) * ln(1.0 - pYes)))

        return PaperTradeEvaluation(
            executed = true,
            hit = hit,
            stakeUnits = entryCost,
            pnlUnits = pnl,
            expectedPnlUnits = expectedPnl,
            executionGapUnits = pnl - expectedPnl,
            fillProbabilityUsed = fillProbability,
            fillDraw = fillDraw,
            entryPriceUnits = marketPrice,
            entryFeeUnits = entryFee,
            entrySlippageUnits = entrySlippage,
            exitPayoutUnits = payout,
            brierScore = brier,
            logLoss = logLoss
        )
    }

    private fun estimateEntrySlippage(
        spread: Double,
        liquidity: Double,
        volume24h: Double
    ): Double {
        val spreadImpact = spread * 0.55
        val liquidityImpact = when {
            liquidity < 600.0 -> 0.018
            liquidity < 1500.0 -> 0.010
            liquidity < 3000.0 -> 0.004
            else -> 0.0
        }
        val volumeImpact = when {
            volume24h < 500.0 -> 0.010
            volume24h < 1200.0 -> 0.005
            else -> 0.0
        }
        return (spreadImpact + liquidityImpact + volumeImpact).coerceAtMost(0.09)
    }

    private fun estimateFillProbability(
        liquidity: Double?,
        volume24h: Double?,
        spread: Double?
    ): Double {
        val liq = (liquidity ?: 0.0).coerceAtLeast(0.0)
        val vol = (volume24h ?: 0.0).coerceAtLeast(0.0)
        val spr = (spread ?: DEFAULT_SPREAD).coerceAtLeast(0.0)

        val liquidityScore = logistic((ln(1.0 + liq) - ln(1.0 + 1800.0)) / 0.9)
        val volumeScore = logistic((ln(1.0 + vol) - ln(1.0 + 1200.0)) / 0.95)
        val spreadScore = (1.0 - (spr / 0.08)).coerceIn(0.0, 1.0)
        val blended = (0.50 * liquidityScore) + (0.30 * volumeScore) + (0.20 * spreadScore)

        return (0.15 + (0.80 * blended)).coerceIn(0.15, 0.98)
    }

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
