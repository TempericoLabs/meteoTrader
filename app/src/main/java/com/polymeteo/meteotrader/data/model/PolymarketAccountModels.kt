package com.polymeteo.meteotrader.data.model

import java.time.Instant

data class PolymarketAccountSummary(
    val totalValueUsd: Double?,
    val openPositionsCount: Int,
    val closedPositionsCount: Int,
    val recentTradesCount: Int,
    val openInitialValueUsd: Double,
    val openCurrentValueUsd: Double,
    val openUnrealizedPnlUsd: Double,
    val closedRealizedPnlUsd: Double
)

data class PolymarketOpenPosition(
    val title: String,
    val outcome: String?,
    val size: Double?,
    val avgPrice: Double?,
    val curPrice: Double?,
    val initialValueUsd: Double?,
    val currentValueUsd: Double?,
    val cashPnlUsd: Double?,
    val percentPnl: Double?,
    val redeemable: Boolean,
    val endDate: String?,
    val eventSlug: String?,
    val marketSlug: String?
)

data class PolymarketClosedPosition(
    val title: String,
    val outcome: String?,
    val avgPrice: Double?,
    val totalBoughtUsd: Double?,
    val realizedPnlUsd: Double?,
    val curPrice: Double?,
    val timestamp: Instant?,
    val endDate: String?,
    val eventSlug: String?,
    val marketSlug: String?
)

data class PolymarketTrade(
    val title: String,
    val side: String?,
    val outcome: String?,
    val price: Double?,
    val size: Double?,
    val usdcSize: Double?,
    val timestamp: Instant?,
    val transactionHash: String?,
    val eventSlug: String?,
    val marketSlug: String?
)

data class PolymarketAccountSnapshot(
    val walletAddress: String,
    val fetchedAt: Instant,
    val summary: PolymarketAccountSummary,
    val openPositions: List<PolymarketOpenPosition>,
    val closedPositions: List<PolymarketClosedPosition>,
    val recentTrades: List<PolymarketTrade>,
    val warnings: List<String>
)
