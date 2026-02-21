package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.PolymarketAccountSnapshot
import com.polymeteo.meteotrader.data.model.PolymarketAccountSummary
import com.polymeteo.meteotrader.data.model.PolymarketClosedPosition
import com.polymeteo.meteotrader.data.model.PolymarketOpenPosition
import com.polymeteo.meteotrader.data.model.PolymarketTrade
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.Locale

class PolymarketAccountSource(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun fetchAccount(
        walletAddress: String,
        limit: Int = DEFAULT_LIMIT
    ): PolymarketAccountSnapshot = coroutineScope {
        val normalizedWallet = walletAddress.trim().lowercase(Locale.US)
        val warnings = mutableListOf<String>()

        val valueDeferred = async { fetchValue(normalizedWallet) }
        val openDeferred = async { fetchOpenPositions(normalizedWallet, limit) }
        val closedDeferred = async { fetchClosedPositions(normalizedWallet, limit) }
        val tradesDeferred = async { fetchTrades(normalizedWallet, limit) }

        val valueResult = valueDeferred.await()
        val openResult = openDeferred.await()
        val closedResult = closedDeferred.await()
        val tradesResult = tradesDeferred.await()

        if (valueResult.error != null) warnings += valueResult.error
        if (openResult.error != null) warnings += openResult.error
        if (closedResult.error != null) warnings += closedResult.error
        if (tradesResult.error != null) warnings += tradesResult.error

        val openInitialValueUsd = openResult.items.sumOf { it.initialValueUsd ?: 0.0 }
        val openCurrentValueUsd = openResult.items.sumOf { it.currentValueUsd ?: 0.0 }
        val closedRealizedPnlUsd = closedResult.items.sumOf { it.realizedPnlUsd ?: 0.0 }

        val summary = PolymarketAccountSummary(
            totalValueUsd = valueResult.valueUsd,
            openPositionsCount = openResult.items.size,
            closedPositionsCount = closedResult.items.size,
            recentTradesCount = tradesResult.items.size,
            openInitialValueUsd = openInitialValueUsd,
            openCurrentValueUsd = openCurrentValueUsd,
            openUnrealizedPnlUsd = openCurrentValueUsd - openInitialValueUsd,
            closedRealizedPnlUsd = closedRealizedPnlUsd
        )

        PolymarketAccountSnapshot(
            walletAddress = normalizedWallet,
            fetchedAt = Instant.now(),
            summary = summary,
            openPositions = openResult.items,
            closedPositions = closedResult.items,
            recentTrades = tradesResult.items,
            warnings = warnings
        )
    }

    private suspend fun fetchValue(walletAddress: String): ValueResult {
        val url = "$DATA_API_BASE/value?user=$walletAddress"
        return runCatching {
            val response = httpClient.get(
                url = url,
                headers = defaultHeaders(),
                retries = 2
            )
            if (response.code !in 200..299) {
                return ValueResult(
                    valueUsd = null,
                    error = "Value API HTTP ${response.code}"
                )
            }
            val array = json.parseToJsonElement(response.body) as? JsonArray
            val obj = array?.firstOrNull() as? JsonObject
            ValueResult(
                valueUsd = obj?.doubleOrNull("value"),
                error = null
            )
        }.getOrElse { throwable ->
            ValueResult(
                valueUsd = null,
                error = "Value API: ${throwable.message ?: "error"}"
            )
        }
    }

    private suspend fun fetchOpenPositions(
        walletAddress: String,
        limit: Int
    ): OpenPositionsResult {
        val url = "$DATA_API_BASE/positions?user=$walletAddress&sizeThreshold=0.1&limit=$limit&offset=0"
        return runCatching {
            val response = httpClient.get(
                url = url,
                headers = defaultHeaders(),
                retries = 2
            )
            if (response.code !in 200..299) {
                return OpenPositionsResult(
                    items = emptyList(),
                    error = "Positions API HTTP ${response.code}"
                )
            }
            val array = json.parseToJsonElement(response.body) as? JsonArray ?: JsonArray(emptyList())
            val items = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                PolymarketOpenPosition(
                    title = obj.stringOrNull("title").orEmpty(),
                    outcome = obj.stringOrNull("outcome"),
                    size = obj.doubleOrNull("size"),
                    avgPrice = obj.doubleOrNull("avgPrice"),
                    curPrice = obj.doubleOrNull("curPrice"),
                    initialValueUsd = obj.doubleOrNull("initialValue"),
                    currentValueUsd = obj.doubleOrNull("currentValue"),
                    cashPnlUsd = obj.doubleOrNull("cashPnl"),
                    percentPnl = obj.doubleOrNull("percentPnl"),
                    redeemable = obj.booleanOrNull("redeemable") == true,
                    endDate = obj.stringOrNull("endDate"),
                    eventSlug = obj.stringOrNull("eventSlug"),
                    marketSlug = obj.stringOrNull("slug")
                )
            }
            OpenPositionsResult(
                items = items,
                error = null
            )
        }.getOrElse { throwable ->
            OpenPositionsResult(
                items = emptyList(),
                error = "Positions API: ${throwable.message ?: "error"}"
            )
        }
    }

    private suspend fun fetchClosedPositions(
        walletAddress: String,
        limit: Int
    ): ClosedPositionsResult {
        val url = "$DATA_API_BASE/closed-positions?user=$walletAddress&limit=$limit&offset=0"
        return runCatching {
            val response = httpClient.get(
                url = url,
                headers = defaultHeaders(),
                retries = 2
            )
            if (response.code !in 200..299) {
                return ClosedPositionsResult(
                    items = emptyList(),
                    error = "Closed positions API HTTP ${response.code}"
                )
            }
            val array = json.parseToJsonElement(response.body) as? JsonArray ?: JsonArray(emptyList())
            val items = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                PolymarketClosedPosition(
                    title = obj.stringOrNull("title").orEmpty(),
                    outcome = obj.stringOrNull("outcome"),
                    avgPrice = obj.doubleOrNull("avgPrice"),
                    totalBoughtUsd = obj.doubleOrNull("totalBought"),
                    realizedPnlUsd = obj.doubleOrNull("realizedPnl"),
                    curPrice = obj.doubleOrNull("curPrice"),
                    timestamp = obj.longOrNull("timestamp")?.let { Instant.ofEpochSecond(it) },
                    endDate = obj.stringOrNull("endDate"),
                    eventSlug = obj.stringOrNull("eventSlug"),
                    marketSlug = obj.stringOrNull("slug")
                )
            }
            ClosedPositionsResult(
                items = items,
                error = null
            )
        }.getOrElse { throwable ->
            ClosedPositionsResult(
                items = emptyList(),
                error = "Closed positions API: ${throwable.message ?: "error"}"
            )
        }
    }

    private suspend fun fetchTrades(
        walletAddress: String,
        limit: Int
    ): TradesResult {
        val url = "$DATA_API_BASE/trades?user=$walletAddress&limit=$limit&offset=0"
        return runCatching {
            val response = httpClient.get(
                url = url,
                headers = defaultHeaders(),
                retries = 2
            )
            if (response.code !in 200..299) {
                return TradesResult(
                    items = emptyList(),
                    error = "Trades API HTTP ${response.code}"
                )
            }
            val array = json.parseToJsonElement(response.body) as? JsonArray ?: JsonArray(emptyList())
            val items = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                PolymarketTrade(
                    title = obj.stringOrNull("title").orEmpty(),
                    side = obj.stringOrNull("side"),
                    outcome = obj.stringOrNull("outcome"),
                    price = obj.doubleOrNull("price"),
                    size = obj.doubleOrNull("size"),
                    usdcSize = obj.doubleOrNull("usdcSize"),
                    timestamp = obj.longOrNull("timestamp")?.let { Instant.ofEpochSecond(it) },
                    transactionHash = obj.stringOrNull("transactionHash"),
                    eventSlug = obj.stringOrNull("eventSlug"),
                    marketSlug = obj.stringOrNull("slug")
                )
            }
            TradesResult(
                items = items,
                error = null
            )
        }.getOrElse { throwable ->
            TradesResult(
                items = emptyList(),
                error = "Trades API: ${throwable.message ?: "error"}"
            )
        }
    }

    private fun defaultHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "PolyMeteo/1.0",
            "Accept" to "application/json"
        )
    }

    private data class ValueResult(
        val valueUsd: Double?,
        val error: String?
    )

    private data class OpenPositionsResult(
        val items: List<PolymarketOpenPosition>,
        val error: String?
    )

    private data class ClosedPositionsResult(
        val items: List<PolymarketClosedPosition>,
        val error: String?
    )

    private data class TradesResult(
        val items: List<PolymarketTrade>,
        val error: String?
    )

    companion object {
        private const val DATA_API_BASE = "https://data-api.polymarket.com"
        private const val DEFAULT_LIMIT = 30
    }
}
