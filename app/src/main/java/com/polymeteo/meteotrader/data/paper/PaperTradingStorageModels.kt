package com.polymeteo.meteotrader.data.paper

import com.polymeteo.meteotrader.data.model.PaperPositionStatus
import kotlinx.serialization.Serializable

@Serializable
data class PaperTradingDataset(
    val positions: List<PaperPositionRecord> = emptyList(),
    val updatedAtEpochMs: Long = 0L
)

@Serializable
data class PaperPositionRecord(
    val id: String,
    val cityId: String,
    val cityName: String,
    val marketId: String,
    val question: String,
    val targetDateIso: String? = null,
    val status: PaperPositionStatus = PaperPositionStatus.OPEN,
    val stakeUsdc: Double,
    val sharesYes: Double,
    val entryYesPrice: Double,
    val entryFeeUsdc: Double,
    val totalCostUsdc: Double,
    val lastMarkYesPrice: Double? = null,
    val closeYesPrice: Double? = null,
    val closeFeeUsdc: Double? = null,
    val proceedsNetUsdc: Double? = null,
    val realizedPnlUsdc: Double? = null,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long? = null
)
