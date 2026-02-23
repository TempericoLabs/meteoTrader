package com.polymeteo.meteotrader.data.model

import java.time.Instant
import java.time.LocalDate

enum class PaperExecutionMode {
    SIMULATION
}

enum class PaperPositionStatus {
    OPEN,
    CLOSED
}

data class PaperPosition(
    val id: String,
    val cityId: String,
    val cityName: String,
    val marketId: String,
    val question: String,
    val targetDate: LocalDate?,
    val status: PaperPositionStatus,
    val stakeUsdc: Double,
    val sharesYes: Double,
    val entryYesPrice: Double,
    val entryFeeUsdc: Double,
    val totalCostUsdc: Double,
    val currentYesPrice: Double?,
    val currentValueUsdc: Double?,
    val unrealizedPnlUsdc: Double?,
    val closeYesPrice: Double?,
    val closeFeeUsdc: Double?,
    val proceedsNetUsdc: Double?,
    val realizedPnlUsdc: Double?,
    val openedAt: Instant,
    val closedAt: Instant?
)

data class PaperPortfolioSnapshot(
    val mode: PaperExecutionMode,
    val generatedAt: Instant,
    val positions: List<PaperPosition>,
    val openPositions: Int,
    val closedPositions: Int,
    val totalStakeUsdc: Double,
    val openCostUsdc: Double,
    val openMarketValueUsdc: Double,
    val openUnrealizedPnlUsdc: Double,
    val closedRealizedPnlUsdc: Double,
    val warnings: List<String>
) {
    companion object {
        fun empty(now: Instant = Instant.now()): PaperPortfolioSnapshot {
            return PaperPortfolioSnapshot(
                mode = PaperExecutionMode.SIMULATION,
                generatedAt = now,
                positions = emptyList(),
                openPositions = 0,
                closedPositions = 0,
                totalStakeUsdc = 0.0,
                openCostUsdc = 0.0,
                openMarketValueUsdc = 0.0,
                openUnrealizedPnlUsdc = 0.0,
                closedRealizedPnlUsdc = 0.0,
                warnings = emptyList()
            )
        }
    }
}
