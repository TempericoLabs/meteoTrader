package com.polymeteo.meteotrader.data.model

import java.time.Instant

data class PolymarketUserProfile(
    val username: String,
    val pseudonym: String?,
    val bio: String?,
    val proxyWallet: String,
    val profileImageUrl: String?,
    val displayUsernamePublic: Boolean
)

data class PolymarketUserActivityItem(
    val type: String?,
    val side: String?,
    val outcome: String?,
    val title: String,
    val eventSlug: String?,
    val marketSlug: String?,
    val size: Double?,
    val usdcSize: Double?,
    val price: Double?,
    val timestamp: Instant?,
    val transactionHash: String?
)

data class PolymarketUserCityInsight(
    val cityId: String,
    val cityName: String,
    val count: Int,
    val totalUsdc: Double
)

data class PolymarketUserEventInsight(
    val eventSlug: String,
    val title: String,
    val tradesCount: Int,
    val totalUsdc: Double
)

data class PolymarketUserIntelInsights(
    val fetchedTradesWindow: Int,
    val fetchedActivityWindow: Int,
    val tradeCount: Int,
    val activityCount: Int,
    val buyCount: Int,
    val sellCount: Int,
    val yesCount: Int,
    val noCount: Int,
    val avgTradeUsdc: Double?,
    val medianTradeUsdc: Double?,
    val largestTradeUsdc: Double?,
    val totalTradeUsdc: Double,
    val uniqueEventsCount: Int,
    val weatherTradeRatio: Double?,
    val trackedCitiesRatio: Double?,
    val topTrackedCities: List<PolymarketUserCityInsight>,
    val topEvents: List<PolymarketUserEventInsight>,
    val lastActivityAt: Instant?,
    val repeatedScaleInSignals: Int,
    val styleNotes: List<String>
)

data class PolymarketUserIntelSnapshot(
    val queryUsername: String,
    val fetchedAt: Instant,
    val profile: PolymarketUserProfile,
    val account: PolymarketAccountSnapshot,
    val activity: List<PolymarketUserActivityItem>,
    val insights: PolymarketUserIntelInsights,
    val warnings: List<String>
)

