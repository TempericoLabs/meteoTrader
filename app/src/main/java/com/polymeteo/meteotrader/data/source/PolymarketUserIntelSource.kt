package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.CityCatalog
import com.polymeteo.meteotrader.data.model.PolymarketAccountSnapshot
import com.polymeteo.meteotrader.data.model.PolymarketTrade
import com.polymeteo.meteotrader.data.model.PolymarketUserActivityItem
import com.polymeteo.meteotrader.data.model.PolymarketUserCityInsight
import com.polymeteo.meteotrader.data.model.PolymarketUserEventInsight
import com.polymeteo.meteotrader.data.model.PolymarketUserIntelInsights
import com.polymeteo.meteotrader.data.model.PolymarketUserIntelSnapshot
import com.polymeteo.meteotrader.data.model.PolymarketUserProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

class PolymarketUserIntelSource(
    private val httpClient: HttpClient,
    private val accountSource: PolymarketAccountSource,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun fetchUserIntel(
        usernameInput: String,
        limit: Int = DEFAULT_LIMIT
    ): PolymarketUserIntelSnapshot = coroutineScope {
        val normalizedQuery = usernameInput.trim().removePrefix("@")
        require(normalizedQuery.isNotBlank()) { "Introduce un nombre de usuario de Polymarket" }

        val profileResolution = resolveProfile(normalizedQuery)
        val warnings = mutableListOf<String>()
        warnings += profileResolution.warnings

        val profile = profileResolution.profile
            ?: throw IllegalStateException("No se encontró el usuario '$normalizedQuery' en Polymarket")

        val accountDeferred = async {
            runCatching {
                accountSource.fetchAccount(
                    walletAddress = profile.proxyWallet,
                    limit = limit.coerceIn(10, 80)
                )
            }
        }
        val activityDeferred = async { fetchActivity(profile.proxyWallet, limit.coerceIn(10, 120)) }

        val accountResult = accountDeferred.await()
        val account = accountResult.getOrElse { throwable ->
            throw IllegalStateException(
                throwable.message ?: "No se pudieron cargar datos públicos de cuenta para ${profile.proxyWallet}"
            )
        }
        val activityResult = activityDeferred.await()
        warnings += account.warnings
        activityResult.error?.let(warnings::add)

        val activity = activityResult.items
        val insights = buildInsights(
            trades = account.recentTrades,
            activity = activity,
            fetchedTradesWindow = account.recentTrades.size,
            fetchedActivityWindow = activity.size
        )

        PolymarketUserIntelSnapshot(
            queryUsername = normalizedQuery,
            fetchedAt = Instant.now(),
            profile = profile,
            account = account,
            activity = activity,
            insights = insights,
            warnings = warnings.distinct()
        )
    }

    suspend fun fetchPublicActivityByWallet(
        walletAddress: String,
        limit: Int = DEFAULT_POLL_LIMIT
    ): List<PolymarketUserActivityItem> {
        return fetchActivity(
            walletAddress = walletAddress.trim().lowercase(Locale.US),
            limit = limit.coerceIn(5, 120)
        ).items
    }

    private suspend fun resolveProfile(username: String): ProfileResolutionResult {
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.toString())
        val url = "$GAMMA_API_BASE/public-search?q=$encoded&search_profiles=true&search_tags=false&limit_per_type=10"
        return runCatching {
            val response = httpClient.get(
                url = url,
                headers = defaultHeaders(),
                retries = 2
            )
            if (response.code !in 200..299) {
                return ProfileResolutionResult(
                    profile = null,
                    warnings = listOf("Public search API HTTP ${response.code}")
                )
            }
            val obj = json.parseToJsonElement(response.body) as? JsonObject ?: JsonObject(emptyMap())
            val profiles = (obj["profiles"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val wallet = item.stringOrNull("proxyWallet")?.trim().orEmpty()
                if (wallet.isBlank()) return@mapNotNull null
                PolymarketUserProfile(
                    username = item.stringOrNull("name").orEmpty(),
                    pseudonym = item.stringOrNull("pseudonym"),
                    bio = item.stringOrNull("bio"),
                    proxyWallet = wallet.lowercase(Locale.US),
                    profileImageUrl = item.stringOrNull("profileImage") ?: item.stringOrNull("profileImageOptimized"),
                    displayUsernamePublic = item.booleanOrNull("displayUsernamePublic") == true
                )
            }

            val exact = profiles.firstOrNull { it.username.equals(username, ignoreCase = true) }
            val byPseudo = profiles.firstOrNull { (it.pseudonym ?: "").equals(username, ignoreCase = true) }
            val fuzzy = profiles.firstOrNull {
                it.username.contains(username, ignoreCase = true) ||
                    (it.pseudonym ?: "").contains(username, ignoreCase = true)
            }
            val selected = exact ?: byPseudo ?: fuzzy

            val warnings = buildList {
                if (profiles.size > 1 && selected != null) {
                    add("Coincidencias múltiples en perfil; se seleccionó '${selected.username}'.")
                }
            }
            ProfileResolutionResult(
                profile = selected,
                warnings = warnings
            )
        }.getOrElse { throwable ->
            ProfileResolutionResult(
                profile = null,
                warnings = listOf("Public search API: ${throwable.message ?: "error"}")
            )
        }
    }

    private suspend fun fetchActivity(
        walletAddress: String,
        limit: Int
    ): ActivityResult {
        val url = "$DATA_API_BASE/activity?user=$walletAddress&limit=$limit&offset=0"
        return runCatching {
            val response = httpClient.get(
                url = url,
                headers = defaultHeaders(),
                retries = 2
            )
            if (response.code !in 200..299) {
                return ActivityResult(
                    items = emptyList(),
                    error = "Activity API HTTP ${response.code}"
                )
            }
            val array = json.parseToJsonElement(response.body) as? JsonArray ?: JsonArray(emptyList())
            val items = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                PolymarketUserActivityItem(
                    type = obj.stringOrNull("type"),
                    side = obj.stringOrNull("side"),
                    outcome = obj.stringOrNull("outcome"),
                    title = obj.stringOrNull("title").orEmpty(),
                    eventSlug = obj.stringOrNull("eventSlug"),
                    marketSlug = obj.stringOrNull("slug"),
                    size = obj.doubleOrNull("size"),
                    usdcSize = obj.doubleOrNull("usdcSize"),
                    price = obj.doubleOrNull("price"),
                    timestamp = obj.longOrNull("timestamp")?.let(Instant::ofEpochSecond),
                    transactionHash = obj.stringOrNull("transactionHash")
                )
            }
            ActivityResult(items = items, error = null)
        }.getOrElse { throwable ->
            ActivityResult(
                items = emptyList(),
                error = "Activity API: ${throwable.message ?: "error"}"
            )
        }
    }

    private fun buildInsights(
        trades: List<PolymarketTrade>,
        activity: List<PolymarketUserActivityItem>,
        fetchedTradesWindow: Int,
        fetchedActivityWindow: Int
    ): PolymarketUserIntelInsights {
        val buyCount = trades.count { it.side.equals("BUY", ignoreCase = true) }
        val sellCount = trades.count { it.side.equals("SELL", ignoreCase = true) }
        val yesCount = trades.count { it.outcome.equals("Yes", ignoreCase = true) }
        val noCount = trades.count { it.outcome.equals("No", ignoreCase = true) }
        val tradeSizes = trades.mapNotNull { it.usdcSize ?: (it.price?.let { p -> (it.size ?: 0.0) * p }) }
            .filter { it > 0.0 }
        val totalTradeUsdc = tradeSizes.sum()
        val avgTradeUsdc = tradeSizes.takeIf { it.isNotEmpty() }?.average()
        val medianTradeUsdc = tradeSizes.takeIf { it.isNotEmpty() }?.sorted()?.let { sorted ->
            val mid = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
        val largestTradeUsdc = tradeSizes.maxOrNull()
        val uniqueEventsCount = trades.mapNotNull { it.eventSlug }.toSet().size

        val weatherTrades = trades.filter { it.isWeatherLike() }
        val weatherTradeRatio = if (trades.isNotEmpty()) weatherTrades.size.toDouble() / trades.size else null

        val trackedCityBuckets = mutableMapOf<String, MutableList<Double>>()
        trades.forEach { trade ->
            val cityId = detectTrackedCity(trade.eventSlug, trade.title) ?: return@forEach
            val usdc = trade.usdcSize ?: 0.0
            trackedCityBuckets.getOrPut(cityId) { mutableListOf() }.add(usdc)
        }
        val topTrackedCities = trackedCityBuckets.entries.mapNotNull { (cityId, amounts) ->
            val city = CityCatalog.findById(cityId) ?: return@mapNotNull null
            PolymarketUserCityInsight(
                cityId = cityId,
                cityName = city.name,
                count = amounts.size,
                totalUsdc = amounts.sum()
            )
        }.sortedWith(
            compareByDescending<PolymarketUserCityInsight> { it.count }
                .thenByDescending { it.totalUsdc }
        ).take(6)
        val trackedCitiesTradesCount = trackedCityBuckets.values.sumOf { it.size }
        val trackedCitiesRatio = if (trades.isNotEmpty()) trackedCitiesTradesCount.toDouble() / trades.size else null

        val topEvents = trades
            .groupBy { it.eventSlug ?: it.marketSlug ?: it.title }
            .map { (slugOrTitle, grouped) ->
                val title = grouped.firstOrNull()?.title.orEmpty()
                PolymarketUserEventInsight(
                    eventSlug = slugOrTitle,
                    title = title,
                    tradesCount = grouped.size,
                    totalUsdc = grouped.sumOf { trade ->
                        trade.usdcSize ?: ((trade.size ?: 0.0) * (trade.price ?: 0.0))
                    }
                )
            }
            .sortedWith(
                compareByDescending<PolymarketUserEventInsight> { it.tradesCount }
                    .thenByDescending { it.totalUsdc }
            )
            .take(6)

        val repeatedScaleInSignals = trades
            .filter { it.side.equals("BUY", ignoreCase = true) }
            .groupBy { "${it.marketSlug.orEmpty()}|${it.outcome.orEmpty()}" }
            .count { (_, grouped) -> grouped.size >= 2 }

        val lastActivityAt = (activity.maxByOrNull { it.timestamp ?: Instant.EPOCH }?.timestamp)
            ?: (trades.maxByOrNull { it.timestamp ?: Instant.EPOCH }?.timestamp)

        val styleNotes = buildList {
            if ((weatherTradeRatio ?: 0.0) >= 0.70) add("Fuerte foco en mercados meteorológicos recientes.")
            if ((trackedCitiesRatio ?: 0.0) >= 0.50) add("Opera principalmente en ciudades del radar PolyMeteo.")
            if (buyCount > sellCount * 2 && trades.size >= 6) add("Predominio de compras (más apertura/acumulación que cierre).")
            if (sellCount > 0 && buyCount > 0) add("Hace gestión activa de posiciones (compras y ventas).")
            if (repeatedScaleInSignals >= 2) add("Patrón de escalado: entra varias veces en el mismo mercado.")
            avgTradeUsdc?.let {
                when {
                    it < 10.0 -> add("Ticket medio pequeño (gestión conservadora de tamaño).")
                    it > 100.0 -> add("Ticket medio alto (convicción/tamaño agresivo).")
                    else -> Unit
                }
            }
            val dominantCity = topTrackedCities.firstOrNull()
            if (dominantCity != null && dominantCity.count >= 3) {
                add("Sesgo reciente a ${dominantCity.cityName} (${dominantCity.count} trades en la muestra).")
            }
        }

        return PolymarketUserIntelInsights(
            fetchedTradesWindow = fetchedTradesWindow,
            fetchedActivityWindow = fetchedActivityWindow,
            tradeCount = trades.size,
            activityCount = activity.size,
            buyCount = buyCount,
            sellCount = sellCount,
            yesCount = yesCount,
            noCount = noCount,
            avgTradeUsdc = avgTradeUsdc,
            medianTradeUsdc = medianTradeUsdc,
            largestTradeUsdc = largestTradeUsdc,
            totalTradeUsdc = totalTradeUsdc,
            uniqueEventsCount = uniqueEventsCount,
            weatherTradeRatio = weatherTradeRatio,
            trackedCitiesRatio = trackedCitiesRatio,
            topTrackedCities = topTrackedCities,
            topEvents = topEvents,
            lastActivityAt = lastActivityAt,
            repeatedScaleInSignals = repeatedScaleInSignals,
            styleNotes = styleNotes
        )
    }

    private fun PolymarketTrade.isWeatherLike(): Boolean {
        val slug = (eventSlug ?: marketSlug ?: "").lowercase(Locale.US)
        val titleLc = title.lowercase(Locale.US)
        return "highest-temperature" in slug ||
            ("temperature" in titleLc && ("highest" in titleLc || "temperatura más alta" in titleLc))
    }

    private fun detectTrackedCity(eventSlug: String?, title: String?): String? {
        val slug = (eventSlug ?: "").lowercase(Locale.US)
        val text = (title ?: "").lowercase(Locale.US)
        return when {
            "nyc" in slug || "new york" in text -> "new-york"
            "sao-paulo" in slug || "sao paulo" in text -> "sao-paulo"
            "buenos-aires" in slug || "buenos aires" in text -> "buenos-aires"
            "london" in slug || "london" in text -> "london"
            "miami" in slug || "miami" in text -> "miami"
            "toronto" in slug || "toronto" in text -> "toronto"
            "seattle" in slug || "seattle" in text -> "seattle"
            "dallas" in slug || "dallas" in text -> "dallas"
            "wellington" in slug || "wellington" in text -> "wellington"
            "ankara" in slug || "ankara" in text -> "ankara"
            "seoul" in slug || "seoul" in text -> "seoul"
            "chicago" in slug || "chicago" in text -> "chicago"
            "atlanta" in slug || "atlanta" in text -> "atlanta"
            "paris" in slug || "paris" in text -> "paris"
            else -> null
        }
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())

    private fun defaultHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "PolyMeteo/1.0",
            "Accept" to "application/json"
        )
    }

    private data class ProfileResolutionResult(
        val profile: PolymarketUserProfile?,
        val warnings: List<String>
    )

    private data class ActivityResult(
        val items: List<PolymarketUserActivityItem>,
        val error: String?
    )

    companion object {
        private const val GAMMA_API_BASE = "https://gamma-api.polymarket.com"
        private const val DATA_API_BASE = "https://data-api.polymarket.com"
        private const val DEFAULT_LIMIT = 40
        private const val DEFAULT_POLL_LIMIT = 20
    }
}
