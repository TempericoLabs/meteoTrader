package com.polymeteo.meteotrader.data.backtest

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal enum class RiskBlockReason(val label: String) {
    KILL_SWITCH("Kill-switch activo"),
    DAILY_TRADE_LIMIT("Limite diario de trades"),
    DAILY_STAKE_LIMIT("Limite diario de stake"),
    DAILY_LOSS_LIMIT("Limite diario de perdida"),
    MARKET_LOSS_LIMIT("Max perdida por mercado"),
    LOW_LIQUIDITY("Liquidez baja o spread alto")
}

internal data class RiskGuardrailConfig(
    val maxTradesPerDay: Int,
    val maxStakePerDayUnits: Double,
    val maxDailyLossUnits: Double,
    val maxLossPerMarketUnits: Double,
    val minLiquidity: Double,
    val maxSpread: Double,
    val maxConsecutiveLosses: Int
)

internal data class RiskGuardrailState(
    val day: LocalDate,
    val acceptedTrades: Int,
    val acceptedStakeUnits: Double,
    val realizedPnlUnits: Double,
    val consecutiveExecutedLosses: Int,
    val marketPnlUnits: Map<String, Double>,
    val killSwitchActive: Boolean
)

internal object RiskGuardrails {

    fun buildState(
        allRecords: List<BacktestRecord>,
        day: LocalDate,
        config: RiskGuardrailConfig
    ): RiskGuardrailState {
        val dayRecords = allRecords.filter { it.snapshotDayUtc() == day }
        val acceptedTrades = dayRecords.count()
        val acceptedStakeUnits = dayRecords.sumOf { it.plannedStakeUnits() }
        val executedSettled = dayRecords.filter { it.settled && it.executed && it.pnlUnits != null }
        val realizedPnl = executedSettled.sumOf { it.pnlUnits ?: 0.0 }
        val marketPnl = executedSettled
            .groupBy { it.marketId }
            .mapValues { (_, records) -> records.sumOf { it.pnlUnits ?: 0.0 } }
        val consecutiveLosses = consecutiveLosses(allRecords, day)
        val killSwitch = realizedPnl <= -config.maxDailyLossUnits || consecutiveLosses >= config.maxConsecutiveLosses

        return RiskGuardrailState(
            day = day,
            acceptedTrades = acceptedTrades,
            acceptedStakeUnits = acceptedStakeUnits,
            realizedPnlUnits = realizedPnl,
            consecutiveExecutedLosses = consecutiveLosses,
            marketPnlUnits = marketPnl,
            killSwitchActive = killSwitch
        )
    }

    fun evaluate(
        record: BacktestRecord,
        state: RiskGuardrailState,
        config: RiskGuardrailConfig
    ): RiskBlockReason? {
        if (state.killSwitchActive) return RiskBlockReason.KILL_SWITCH
        if (record.liquidity != null && record.liquidity < config.minLiquidity) {
            return RiskBlockReason.LOW_LIQUIDITY
        }
        if (record.spread != null && record.spread > config.maxSpread) {
            return RiskBlockReason.LOW_LIQUIDITY
        }
        if (state.acceptedTrades >= config.maxTradesPerDay) {
            return RiskBlockReason.DAILY_TRADE_LIMIT
        }
        val plannedStakeAfter = state.acceptedStakeUnits + record.plannedStakeUnits()
        if (plannedStakeAfter > config.maxStakePerDayUnits) {
            return RiskBlockReason.DAILY_STAKE_LIMIT
        }
        if (state.realizedPnlUnits <= -config.maxDailyLossUnits) {
            return RiskBlockReason.DAILY_LOSS_LIMIT
        }
        val marketPnl = state.marketPnlUnits[record.marketId] ?: 0.0
        if (marketPnl <= -config.maxLossPerMarketUnits) {
            return RiskBlockReason.MARKET_LOSS_LIMIT
        }
        return null
    }

    fun accept(state: RiskGuardrailState, record: BacktestRecord): RiskGuardrailState {
        return state.copy(
            acceptedTrades = state.acceptedTrades + 1,
            acceptedStakeUnits = state.acceptedStakeUnits + record.plannedStakeUnits()
        )
    }

    private fun consecutiveLosses(records: List<BacktestRecord>, day: LocalDate): Int {
        val ordered = records
            .asSequence()
            .filter { it.snapshotDayUtc() == day }
            .filter { it.settled && it.executed && it.pnlUnits != null }
            .sortedByDescending { it.settledEpochMs ?: it.snapshotEpochMs }
            .toList()

        var count = 0
        for (record in ordered) {
            val pnl = record.pnlUnits ?: continue
            if (pnl < 0.0) {
                count += 1
            } else {
                break
            }
        }
        return count
    }

    private fun BacktestRecord.snapshotDayUtc(): LocalDate {
        return Instant.ofEpochMilli(snapshotEpochMs).atZone(ZoneOffset.UTC).toLocalDate()
    }

    private fun BacktestRecord.plannedStakeUnits(): Double {
        val sidePrice = if (recommendedBuy.equals("YES", ignoreCase = true)) yesPrice else noPrice
        val fee = feeCostEstimate ?: 0.0
        val spreadCost = spreadCostEstimate ?: 0.0
        val liquidityCost = liquidityCostEstimate ?: 0.0
        return (sidePrice + fee + spreadCost + liquidityCost)
            .coerceAtLeast(0.0)
            .coerceAtMost(1.2)
    }
}
