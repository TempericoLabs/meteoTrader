package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.MarketConditionType
import com.polymeteo.meteotrader.data.model.MarketRangeCondition
import com.polymeteo.meteotrader.data.model.PolymarketSnapshot
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.data.model.TraderDirection
import com.polymeteo.meteotrader.data.model.TraderOpportunity
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import com.polymeteo.meteotrader.util.celsiusToFahrenheit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

class PolymarketSource(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    data class HistoricalMarketCandidate(
        val marketId: String,
        val question: String,
        val condition: MarketRangeCondition,
        val yesTokenId: String,
        val liquidity: Double?,
        val spread: Double?
    )

    suspend fun fetch(
        city: CityConfig,
        polyTempC: Double?,
        forecastDailyMaxC: List<Double>
    ): PolymarketSnapshot {
        val zoneId = ZoneId.of(city.zoneId)
        val cityNow = Instant.now().atZone(zoneId)
        val today = cityNow.toLocalDate()
        val localHour = cityNow.hour
        val targetDates = listOf(today, today.plusDays(1), today.plusDays(2))

        if (polyTempC == null) {
            return PolymarketSnapshot(
                query = buildQuery(city),
                fetchedAt = Instant.now(),
                marketsScanned = 0,
                opportunities = emptyList(),
                topOpportunity = null,
                error = "PolyTEMP no disponible"
            )
        }

        val directSlugs = targetDates.map { date -> buildEventSlug(city.name, date) }
        val directMarketsResult = fetchDirectEventMarkets(city, targetDates, directSlugs)

        val parsedMarkets = if (directMarketsResult.markets.isNotEmpty()) {
            directMarketsResult.markets
        } else {
            fetchFallbackSearchMarkets(city)
        }

        if (parsedMarkets.isEmpty()) {
            val tried = directSlugs.joinToString(", ")
            val errorParts = mutableListOf<String>()
            if (directMarketsResult.errors.isNotEmpty()) {
                errorParts += directMarketsResult.errors.joinToString(" | ")
            }
            errorParts += "Sin mercados abiertos por slug ($tried)"
            return PolymarketSnapshot(
                query = buildQuery(city),
                fetchedAt = Instant.now(),
                marketsScanned = 0,
                opportunities = emptyList(),
                topOpportunity = null,
                error = errorParts.joinToString(" | ")
            )
        }

        val marketWindow = parsedMarkets.filterByTargetDates(targetDates.toSet())
        val sigmaC = estimateSigmaC(forecastDailyMaxC)
        val opportunities = marketWindow
            .map { market ->
                evaluateOpportunity(
                    market = market,
                    polyTempC = polyTempC,
                    sigmaC = sigmaC,
                    city = city,
                    cityToday = today,
                    localHour = localHour
                )
            }
            .sortedByDescending { it.executableEdge }

        val topToday = opportunities
            .filter { it.condition.targetDate == null || it.condition.targetDate == today }
            .maxByOrNull { it.executableEdge }

        return PolymarketSnapshot(
            query = buildQuery(city),
            fetchedAt = Instant.now(),
            marketsScanned = parsedMarkets.size,
            opportunities = opportunities,
            topOpportunity = topToday ?: opportunities.firstOrNull(),
            error = null
        )
    }

    suspend fun fetchHistoricalMarkets(
        city: CityConfig,
        targetDate: LocalDate
    ): List<HistoricalMarketCandidate> {
        val slug = buildEventSlug(city.name, targetDate)
        val url = "https://gamma-api.polymarket.com/events?slug=$slug"

        return runCatching {
            val response = httpClient.get(
                url,
                headers = mapOf(
                    "User-Agent" to "PolyMeteo/0.1",
                    "Accept" to "application/json"
                ),
                retries = 2
            )
            if (response.code !in 200..299) return emptyList()
            val root = json.parseToJsonElement(response.body) as? JsonArray ?: return emptyList()
            val event = root.firstOrNull() as? JsonObject ?: return emptyList()
            val eventMarkets = event["markets"] as? JsonArray ?: return emptyList()

            eventMarkets
                .mapNotNull { it as? JsonObject }
                .mapNotNull { parseMarket(it, city, allowClosed = true) }
                .filter { market ->
                    market.condition.targetDate == null || market.condition.targetDate == targetDate
                }
                .mapNotNull { market ->
                    val yesTokenId = market.yesTokenId ?: return@mapNotNull null
                    HistoricalMarketCandidate(
                        marketId = market.id,
                        question = market.question,
                        condition = market.condition,
                        yesTokenId = yesTokenId,
                        liquidity = market.liquidity,
                        spread = market.spread
                    )
                }
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchDirectEventMarkets(
        city: CityConfig,
        targetDates: List<LocalDate>,
        slugs: List<String>
    ): DirectMarketsResult = coroutineScope {
        val perSlug = targetDates.zip(slugs).map { (date, slug) ->
            async {
                fetchDirectEventMarketsForSlug(
                    city = city,
                    date = date,
                    slug = slug
                )
            }
        }.awaitAll()

        DirectMarketsResult(
            markets = perSlug.flatMap { it.markets },
            errors = perSlug.flatMap { it.errors }
        )
    }

    private suspend fun fetchDirectEventMarketsForSlug(
        city: CityConfig,
        date: LocalDate,
        slug: String
    ): DirectMarketsResult {
        val url = "https://gamma-api.polymarket.com/events?slug=$slug"
        return runCatching {
            val response = httpClient.get(
                url,
                headers = mapOf(
                    "User-Agent" to "PolyMeteo/0.1",
                    "Accept" to "application/json"
                ),
                retries = 2
            )
            if (response.code !in 200..299) {
                return DirectMarketsResult(
                    markets = emptyList(),
                    errors = listOf("${slug}: HTTP ${response.code}")
                )
            }
            val root = json.parseToJsonElement(response.body) as? JsonArray
                ?: return DirectMarketsResult(
                    markets = emptyList(),
                    errors = listOf("${slug}: payload inválido")
                )

            val event = root.firstOrNull() as? JsonObject
                ?: return DirectMarketsResult(
                    markets = emptyList(),
                    errors = listOf("${slug}: evento no encontrado")
                )

            val eventMarkets = event["markets"] as? JsonArray
                ?: return DirectMarketsResult(
                    markets = emptyList(),
                    errors = listOf("${slug}: evento sin mercados")
                )

            val markets = eventMarkets
                .mapNotNull { it as? JsonObject }
                .mapNotNull { parseMarket(it, city) }
                .filter { market ->
                    market.condition.targetDate == null || market.condition.targetDate == date
                }

            DirectMarketsResult(markets = markets, errors = emptyList())
        }.getOrElse { throwable ->
            DirectMarketsResult(
                markets = emptyList(),
                errors = listOf("${slug}: ${throwable.message ?: "error"}")
            )
        }
    }

    private suspend fun fetchFallbackSearchMarkets(city: CityConfig): List<ParsedMarket> {
        val query = URLEncoder.encode(buildQuery(city), StandardCharsets.UTF_8.toString())
        val url = "https://gamma-api.polymarket.com/markets?search=$query&active=true&closed=false&order=id&ascending=false&limit=120"

        return runCatching {
            val response = httpClient.get(
                url,
                headers = mapOf(
                    "User-Agent" to "PolyMeteo/0.1",
                    "Accept" to "application/json"
                )
            )
            if (response.code !in 200..299) return emptyList()
            val root = json.parseToJsonElement(response.body) as? JsonArray ?: return emptyList()
            root
                .mapNotNull { it as? JsonObject }
                .mapNotNull { parseMarket(it, city) }
        }.getOrDefault(emptyList())
    }

    private fun parseMarket(
        obj: JsonObject,
        city: CityConfig,
        allowClosed: Boolean = false
    ): ParsedMarket? {
        val question = obj.stringOrNull("question") ?: return null
        if (!question.contains("highest temperature", ignoreCase = true)) return null
        if (!containsCityName(question, city.name)) return null

        val isActive = obj.booleanOrNull("active") ?: true
        val isClosed = obj.booleanOrNull("closed") ?: false
        if (!allowClosed && (!isActive || isClosed)) return null

        val condition = parseConditionFromQuestion(question, city.zoneId) ?: return null
        val outcomes = parseStringArray(obj.stringOrNull("outcomes"))
        val prices = parseDoubleArray(obj.stringOrNull("outcomePrices"))
        val tokenIds = parseStringArray(obj.stringOrNull("clobTokenIds"))

        val yesIndex = outcomes.indexOfFirst { it.equals("Yes", ignoreCase = true) }
        val noIndex = outcomes.indexOfFirst { it.equals("No", ignoreCase = true) }
        val yesTokenId = when {
            yesIndex >= 0 && yesIndex < tokenIds.size -> tokenIds[yesIndex]
            tokenIds.isNotEmpty() -> tokenIds.first()
            else -> null
        }

        val yesPrice = when {
            yesIndex >= 0 && yesIndex < prices.size -> prices[yesIndex]
            prices.isNotEmpty() -> prices.first()
            else -> obj.doubleOrNull("lastTradePrice") ?: obj.doubleOrNull("bestAsk") ?: 0.5
        }.coerceIn(0.001, 0.999)

        var noPrice = when {
            noIndex >= 0 && noIndex < prices.size -> prices[noIndex]
            prices.size >= 2 -> prices[1]
            else -> (1.0 - yesPrice)
        }.coerceIn(0.001, 0.999)

        if (abs((yesPrice + noPrice) - 1.0) > 0.18) {
            noPrice = (1.0 - yesPrice).coerceIn(0.001, 0.999)
        }

        return ParsedMarket(
            id = obj.stringOrNull("id") ?: "unknown",
            question = question,
            condition = condition,
            yesPrice = yesPrice,
            noPrice = noPrice,
            yesTokenId = yesTokenId,
            liquidity = obj.doubleOrNull("liquidityNum") ?: obj.doubleOrNull("liquidity"),
            volume24h = obj.doubleOrNull("volume24hr") ?: obj.doubleOrNull("volume24hrClob"),
            spread = obj.doubleOrNull("spread")
        )
    }

    private fun evaluateOpportunity(
        market: ParsedMarket,
        polyTempC: Double,
        sigmaC: Double,
        city: CityConfig,
        cityToday: LocalDate,
        localHour: Int
    ): TraderOpportunity {
        val horizonDays = resolveHorizonDays(
            cityToday = cityToday,
            targetDate = market.condition.targetDate
        )
        val calibration = TraderCalibration.resolve(
            cityId = city.id,
            horizonDays = horizonDays,
            localHour = localHour
        )

        val unitFactor = if (market.condition.unit == TempUnit.C) 1.0 else 9.0 / 5.0
        val calibratedMeanC = polyTempC + calibration.meanBiasC
        val mean = if (market.condition.unit == TempUnit.C) calibratedMeanC else celsiusToFahrenheit(calibratedMeanC)
        val sigma = max(0.6, sigmaC * unitFactor * calibration.sigmaMultiplier)

        val threshold = market.condition.threshold
        val rawProbabilityYes = when (market.condition.type) {
            MarketConditionType.GREATER_OR_EQUAL -> 1.0 - normalCdf((threshold - 0.5 - mean) / sigma)
            MarketConditionType.LESS_OR_EQUAL -> normalCdf((threshold + 0.5 - mean) / sigma)
            MarketConditionType.EXACT -> {
                val upper = normalCdf((threshold + 0.5 - mean) / sigma)
                val lower = normalCdf((threshold - 0.5 - mean) / sigma)
                (upper - lower)
            }
            MarketConditionType.BETWEEN -> {
                val upperThreshold = market.condition.upperThreshold ?: threshold
                val upper = normalCdf((upperThreshold + 0.5 - mean) / sigma)
                val lower = normalCdf((threshold - 0.5 - mean) / sigma)
                (upper - lower)
            }
        }.coerceIn(0.001, 0.999)
        val probabilityYes = applyConfidenceWeight(
            probability = rawProbabilityYes,
            confidenceMultiplier = calibration.confidenceMultiplier
        )

        val evYes = probabilityYes - market.yesPrice
        val evNo = (1.0 - probabilityYes) - market.noPrice

        val recommendedBuy = if (evYes >= evNo) "YES" else "NO"
        val rawEdge = max(evYes, evNo)
        val selectedPrice = if (recommendedBuy == "YES") market.yesPrice else market.noPrice

        val direction = when (market.condition.type) {
            MarketConditionType.GREATER_OR_EQUAL -> if (recommendedBuy == "YES") TraderDirection.OVER else TraderDirection.UNDER
            MarketConditionType.LESS_OR_EQUAL -> if (recommendedBuy == "YES") TraderDirection.UNDER else TraderDirection.OVER
            MarketConditionType.EXACT -> when {
                mean >= threshold + 0.35 -> TraderDirection.OVER
                mean <= threshold - 0.35 -> TraderDirection.UNDER
                else -> TraderDirection.RANGE
            }
            MarketConditionType.BETWEEN -> {
                val upper = market.condition.upperThreshold ?: threshold
                if (recommendedBuy == "YES") {
                    TraderDirection.RANGE
                } else {
                    when {
                        mean > upper + 0.35 -> TraderDirection.OVER
                        mean < threshold - 0.35 -> TraderDirection.UNDER
                        else -> TraderDirection.RANGE
                    }
                }
            }
        }

        val fillProbability = estimateFillProbability(
            liquidity = market.liquidity,
            volume24h = market.volume24h,
            spread = market.spread
        )
        val feeCost = (selectedPrice * ASSUMED_ROUNDTRIP_FEE_RATE).coerceAtLeast(0.0)
        val spreadCost = estimateSpreadCost(
            spread = market.spread,
            fillProbability = fillProbability
        )
        val liquidityCost = estimateLiquidityCost(
            liquidity = market.liquidity,
            volume24h = market.volume24h,
            fillProbability = fillProbability
        )
        val totalCost = (feeCost + spreadCost + liquidityCost).coerceAtMost(MAX_TOTAL_EXECUTION_COST)
        val edgeAfterCosts = rawEdge - totalCost
        val executableEdge = edgeAfterCosts * fillProbability

        val signal = when {
            executableEdge >= GREEN_EXECUTABLE_EDGE && fillProbability >= GREEN_MIN_FILL_PROBABILITY -> TraderSignalLevel.GREEN
            executableEdge >= YELLOW_EXECUTABLE_EDGE && fillProbability >= YELLOW_MIN_FILL_PROBABILITY -> TraderSignalLevel.YELLOW
            else -> TraderSignalLevel.RED
        }
        val shouldTrade = executableEdge >= MIN_EXECUTABLE_EDGE_TO_TRADE && fillProbability >= MIN_FILL_PROBABILITY_TO_TRADE

        return TraderOpportunity(
            marketId = market.id,
            question = market.question,
            condition = market.condition,
            yesPrice = market.yesPrice,
            noPrice = market.noPrice,
            modelProbabilityYes = probabilityYes,
            expectedEdge = executableEdge,
            rawEdge = rawEdge,
            edgeAfterCosts = edgeAfterCosts,
            executableEdge = executableEdge,
            fillProbability = fillProbability,
            feeCost = feeCost,
            spreadCost = spreadCost,
            liquidityCost = liquidityCost,
            totalCost = totalCost,
            shouldTrade = shouldTrade,
            recommendedBuy = recommendedBuy,
            direction = direction,
            signal = signal,
            liquidity = market.liquidity,
            volume24h = market.volume24h,
            spread = market.spread
        )
    }

    private fun resolveHorizonDays(
        cityToday: LocalDate,
        targetDate: LocalDate?
    ): Int {
        val effectiveTarget = targetDate ?: cityToday
        return ChronoUnit.DAYS.between(cityToday, effectiveTarget).toInt().coerceIn(0, 2)
    }

    private fun applyConfidenceWeight(
        probability: Double,
        confidenceMultiplier: Double
    ): Double {
        val centered = probability - 0.5
        val weighted = 0.5 + centered * confidenceMultiplier
        return weighted.coerceIn(0.001, 0.999)
    }

    private fun estimateFillProbability(
        liquidity: Double?,
        volume24h: Double?,
        spread: Double?
    ): Double {
        val liq = (liquidity ?: 0.0).coerceAtLeast(0.0)
        val vol = (volume24h ?: 0.0).coerceAtLeast(0.0)
        val spr = (spread ?: DEFAULT_SPREAD_ASSUMPTION).coerceAtLeast(0.0)

        val liquidityScore = logistic((ln(1.0 + liq) - ln(1.0 + 1800.0)) / 0.9)
        val volumeScore = logistic((ln(1.0 + vol) - ln(1.0 + 1200.0)) / 0.95)
        val spreadScore = (1.0 - (spr / 0.08)).coerceIn(0.0, 1.0)
        val blended = (0.50 * liquidityScore) + (0.30 * volumeScore) + (0.20 * spreadScore)

        return (0.15 + (0.80 * blended)).coerceIn(0.15, 0.98)
    }

    private fun estimateSpreadCost(
        spread: Double?,
        fillProbability: Double
    ): Double {
        val effectiveSpread = (spread ?: DEFAULT_SPREAD_ASSUMPTION).coerceIn(0.0, 0.12)
        return (effectiveSpread * (0.50 + ((1.0 - fillProbability) * 0.35))).coerceAtMost(0.07)
    }

    private fun estimateLiquidityCost(
        liquidity: Double?,
        volume24h: Double?,
        fillProbability: Double
    ): Double {
        val liq = (liquidity ?: 0.0).coerceAtLeast(0.0)
        val vol = (volume24h ?: 0.0).coerceAtLeast(0.0)
        val thinBookPenalty = when {
            liq < 600.0 -> 0.020
            liq < 1500.0 -> 0.010
            else -> 0.0
        }
        val weakFlowPenalty = when {
            vol < 500.0 -> 0.015
            vol < 1200.0 -> 0.008
            else -> 0.0
        }
        val lowFillPenalty = (1.0 - fillProbability).coerceIn(0.0, 1.0) * 0.06
        return (thinBookPenalty + weakFlowPenalty + lowFillPenalty).coerceAtMost(0.09)
    }

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))

    private fun parseConditionFromQuestion(question: String, cityZoneId: String): MarketRangeCondition? {
        val geRegex = Regex(
            """be\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*or\s*higher\s*on\s*([A-Za-z]+\s+\d{1,2})""",
            RegexOption.IGNORE_CASE
        )
        val leRegex = Regex(
            """be\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*or\s*below\s*on\s*([A-Za-z]+\s+\d{1,2})""",
            RegexOption.IGNORE_CASE
        )
        val betweenRegex = Regex(
            """be\s*between\s*(-?\d+(?:\.\d+)?)\s*[-–]\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*on\s*([A-Za-z]+\s+\d{1,2})""",
            RegexOption.IGNORE_CASE
        )
        val exactRegex = Regex(
            """be\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*on\s*([A-Za-z]+\s+\d{1,2})""",
            RegexOption.IGNORE_CASE
        )

        geRegex.find(question)?.let { return buildCondition(it, MarketConditionType.GREATER_OR_EQUAL, cityZoneId) }
        leRegex.find(question)?.let { return buildCondition(it, MarketConditionType.LESS_OR_EQUAL, cityZoneId) }
        betweenRegex.find(question)?.let { return buildBetweenCondition(it, cityZoneId) }
        exactRegex.find(question)?.let { return buildCondition(it, MarketConditionType.EXACT, cityZoneId) }

        return null
    }

    private fun buildCondition(
        match: MatchResult,
        type: MarketConditionType,
        cityZoneId: String
    ): MarketRangeCondition? {
        val threshold = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val unit = if (match.groupValues.getOrNull(2).equals("F", ignoreCase = true)) TempUnit.F else TempUnit.C
        val rawDate = match.groupValues.getOrNull(3).orEmpty()
        val targetDate = parseMonthDayDate(rawDate, cityZoneId)

        return MarketRangeCondition(
            type = type,
            threshold = threshold,
            upperThreshold = null,
            unit = unit,
            targetDate = targetDate
        )
    }

    private fun buildBetweenCondition(
        match: MatchResult,
        cityZoneId: String
    ): MarketRangeCondition? {
        val first = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val second = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
        val lower = minOf(first, second)
        val upper = maxOf(first, second)
        val unit = if (match.groupValues.getOrNull(3).equals("F", ignoreCase = true)) TempUnit.F else TempUnit.C
        val rawDate = match.groupValues.getOrNull(4).orEmpty()

        return MarketRangeCondition(
            type = MarketConditionType.BETWEEN,
            threshold = lower,
            upperThreshold = upper,
            unit = unit,
            targetDate = parseMonthDayDate(rawDate, cityZoneId)
        )
    }

    private fun parseMonthDayDate(raw: String, cityZoneId: String): LocalDate? {
        val zoneId = ZoneId.of(cityZoneId)
        val today = LocalDate.now(zoneId)
        val value = raw.trim().replace(Regex("\\s+"), " ")
        val formatters = listOf(
            DateTimeFormatter.ofPattern("MMMM d uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d uuuu", Locale.ENGLISH)
        )

        val candidates = buildList {
            for (year in listOf(today.year - 1, today.year, today.year + 1)) {
                val withYear = "$value $year"
                formatters.forEach { formatter ->
                    val parsed = runCatching { LocalDate.parse(withYear, formatter) }.getOrNull()
                    if (parsed != null) add(parsed)
                }
            }
        }

        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { abs(java.time.temporal.ChronoUnit.DAYS.between(today, it)) }
    }

    private fun parseStringArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun parseDoubleArray(raw: String?): List<Double> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            (json.parseToJsonElement(raw) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun buildQuery(city: CityConfig): String = "highest temperature in ${city.name}"

    private fun buildEventSlug(cityName: String, date: LocalDate): String {
        val citySlug = slugifyCity(cityName)
        val monthSlug = date.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)).lowercase(Locale.US)
        return "highest-temperature-in-$citySlug-on-$monthSlug-${date.dayOfMonth}-${date.year}"
    }

    private fun slugifyCity(cityName: String): String {
        return normalizeToken(cityName)
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
    }

    private fun containsCityName(question: String, cityName: String): Boolean {
        val q = normalizeToken(question)
        val c = normalizeToken(cityName)
        return q.contains(c)
    }

    private fun normalizeToken(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
    }

    private fun List<ParsedMarket>.filterByTargetDates(targetDates: Set<LocalDate>): List<ParsedMarket> {
        if (isEmpty()) return emptyList()
        return this.filter { it.condition.targetDate == null || it.condition.targetDate in targetDates }
    }

    private fun estimateSigmaC(valuesC: List<Double>): Double {
        if (valuesC.size < 2) return 1.8
        val mean = valuesC.average()
        val variance = valuesC.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance).coerceIn(0.9, 4.5)
    }

    private fun normalCdf(x: Double): Double {
        return 0.5 * (1.0 + erfApprox(x / sqrt(2.0)))
    }

    private fun erfApprox(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val absX = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * absX)
        val polynomial = (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
        val y = 1.0 - polynomial * exp(-absX * absX)
        return sign * y
    }

    private data class ParsedMarket(
        val id: String,
        val question: String,
        val condition: MarketRangeCondition,
        val yesPrice: Double,
        val noPrice: Double,
        val yesTokenId: String?,
        val liquidity: Double?,
        val volume24h: Double?,
        val spread: Double?
    )

    private data class DirectMarketsResult(
        val markets: List<ParsedMarket>,
        val errors: List<String>
    )

    private companion object {
        const val ASSUMED_ROUNDTRIP_FEE_RATE = 0.018
        const val DEFAULT_SPREAD_ASSUMPTION = 0.020
        const val MAX_TOTAL_EXECUTION_COST = 0.30

        const val MIN_FILL_PROBABILITY_TO_TRADE = 0.45
        const val MIN_EXECUTABLE_EDGE_TO_TRADE = 0.015

        const val GREEN_MIN_FILL_PROBABILITY = 0.65
        const val GREEN_EXECUTABLE_EDGE = 0.06

        const val YELLOW_MIN_FILL_PROBABILITY = 0.45
        const val YELLOW_EXECUTABLE_EDGE = 0.025
    }
}
