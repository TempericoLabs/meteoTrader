package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.DecisionTraceEntry
import com.polymeteo.meteotrader.data.model.DecisionTraceStatus
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    data class ModelInput(
        val polyTempC: Double?,
        val forecastDailyMaxC: List<Double>
    )

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
        modelInputsByDate: Map<LocalDate, ModelInput>
    ): PolymarketSnapshot {
        val zoneId = ZoneId.of(city.zoneId)
        val cityNow = Instant.now().atZone(zoneId)
        val today = cityNow.toLocalDate()
        val localHour = cityNow.hour
        val targetDates = listOf(today, today.plusDays(1), today.plusDays(2))
        val directSlugCandidatesByDate = targetDates.associateWith { date ->
            PolymarketCityHeuristics.buildEventSlugCandidates(city, date)
        }

        val directMarketsResult = fetchDirectEventMarkets(
            city = city,
            targetDates = targetDates,
            slugCandidatesByDate = directSlugCandidatesByDate
        )

        val parsedMarkets = if (directMarketsResult.markets.isNotEmpty()) {
            directMarketsResult.markets
        } else {
            fetchFallbackSearchMarkets(city)
        }

        if (parsedMarkets.isEmpty()) {
            val tried = directSlugCandidatesByDate.entries.joinToString(" | ") { (date, slugs) ->
                "$date: ${slugs.joinToString(", ")}"
            }
            val errorParts = mutableListOf<String>()
            if (directMarketsResult.errors.isNotEmpty()) {
                errorParts += directMarketsResult.errors.joinToString(" | ")
            }
            errorParts += "Sin mercados abiertos por slug ($tried)"
            return PolymarketSnapshot(
                query = buildPrimaryQuery(city),
                fetchedAt = Instant.now(),
                marketsScanned = 0,
                opportunities = emptyList(),
                topOpportunity = null,
                error = errorParts.joinToString(" | ")
            )
        }

        val marketWindow = parsedMarkets.filterByTargetDates(targetDates.toSet())
        val inputDecisionTrace = mutableListOf<DecisionTraceEntry>()
        val evaluatedOpportunities = buildList {
            marketWindow.forEach { market ->
                val marketDate = market.condition.targetDate ?: today
                val dayInput = modelInputsByDate[marketDate]
                    ?: modelInputsByDate[today]
                if (dayInput == null) {
                    inputDecisionTrace += DecisionTraceEntry(
                        marketId = market.id,
                        question = market.question,
                        targetDate = market.condition.targetDate,
                        stage = TRACE_STAGE_INPUT,
                        status = DecisionTraceStatus.DISCARDED,
                        reason = "Sin entrada de modelo para este horizonte",
                        details = listOf("Fecha objetivo: $marketDate")
                    )
                    return@forEach
                }
                val polyTempC = dayInput.polyTempC
                if (polyTempC == null) {
                    inputDecisionTrace += DecisionTraceEntry(
                        marketId = market.id,
                        question = market.question,
                        targetDate = market.condition.targetDate,
                        stage = TRACE_STAGE_INPUT,
                        status = DecisionTraceStatus.DISCARDED,
                        reason = "MM/MMA no disponible para evaluar el mercado",
                        details = listOf("Fecha objetivo: $marketDate")
                    )
                    return@forEach
                }
                val sigmaC = estimateSigmaC(dayInput.forecastDailyMaxC)
                add(
                    evaluateOpportunity(
                        market = market,
                        polyTempC = polyTempC,
                        sigmaC = sigmaC,
                        city = city,
                        cityToday = today,
                        localHour = localHour
                    )
                )
            }
        }
        val recommendationResult = applyRecommendationControls(
            opportunities = evaluatedOpportunities,
            cityToday = today,
            localHour = localHour
        )
        val opportunities = recommendationResult.kept
            .sortedByDescending { it.executableEdge }
        val decisionTrace = inputDecisionTrace + recommendationResult.traces

        val topToday = opportunities
            .filter { it.condition.targetDate == null || it.condition.targetDate == today }
            .maxByOrNull { it.executableEdge }

        return PolymarketSnapshot(
            query = buildPrimaryQuery(city),
            fetchedAt = Instant.now(),
            marketsScanned = parsedMarkets.size,
            opportunities = opportunities,
            topOpportunity = topToday,
            decisionTrace = decisionTrace,
            error = when {
                evaluatedOpportunities.isEmpty() -> "Media Modelos (MM/MMA) no disponible para los mercados activos"
                opportunities.isEmpty() -> "Mercados descartados por control de ejecución (liquidez/costes/dominancia)"
                else -> null
            }
        )
    }

    suspend fun fetchHistoricalMarkets(
        city: CityConfig,
        targetDate: LocalDate
    ): List<HistoricalMarketCandidate> {
        val slugs = PolymarketCityHeuristics.buildEventSlugCandidates(city, targetDate)
        val collected = mutableListOf<ParsedMarket>()
        for (slug in slugs.distinct()) {
            val result = fetchDirectEventMarketsForSlug(
                city = city,
                date = targetDate,
                slug = slug,
                allowClosed = true
            )
            collected += result.markets
        }
        return collected
            .distinctBy { market -> market.id }
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
    }

    private suspend fun fetchDirectEventMarkets(
        city: CityConfig,
        targetDates: List<LocalDate>,
        slugCandidatesByDate: Map<LocalDate, List<String>>
    ): DirectMarketsResult = coroutineScope {
        val perDate = targetDates.map { date ->
            async {
                fetchDirectEventMarketsForDate(
                    city = city,
                    date = date,
                    slugs = slugCandidatesByDate[date].orEmpty()
                )
            }
        }.awaitAll()

        DirectMarketsResult(
            markets = perDate
                .flatMap { it.markets }
                .distinctBy { market -> market.id },
            errors = perDate.flatMap { it.errors }
        )
    }

    private suspend fun fetchDirectEventMarketsForDate(
        city: CityConfig,
        date: LocalDate,
        slugs: List<String>
    ): DirectMarketsResult {
        val errors = mutableListOf<String>()
        val uniqueSlugs = slugs.distinct()
        for (slug in uniqueSlugs) {
            val result = fetchDirectEventMarketsForSlug(
                city = city,
                date = date,
                slug = slug
            )
            if (result.markets.isNotEmpty()) {
                return DirectMarketsResult(
                    markets = result.markets,
                    errors = emptyList()
                )
            }
            errors += result.errors
        }
        return DirectMarketsResult(
            markets = emptyList(),
            errors = errors
        )
    }

    private suspend fun fetchDirectEventMarketsForSlug(
        city: CityConfig,
        date: LocalDate,
        slug: String,
        allowClosed: Boolean = false
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
                .mapNotNull { parseMarket(it, city, allowClosed = allowClosed) }
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

    private suspend fun fetchFallbackSearchMarkets(city: CityConfig): List<ParsedMarket> = coroutineScope {
        val queries = PolymarketCityHeuristics.buildSearchQueries(city)
            .ifEmpty { listOf(buildPrimaryQuery(city)) }
        val perQuery = queries.map { query ->
            async { fetchFallbackSearchMarketsForQuery(city, query) }
        }.awaitAll()
        perQuery
            .flatten()
            .distinctBy { market -> market.id }
    }

    private suspend fun fetchFallbackSearchMarketsForQuery(
        city: CityConfig,
        queryRaw: String
    ): List<ParsedMarket> {
        val query = URLEncoder.encode(queryRaw, StandardCharsets.UTF_8.toString())
        val url = "https://gamma-api.polymarket.com/markets?search=$query&active=true&closed=false&order=id&ascending=false&limit=120"

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
        if (!isHighestTemperatureQuestion(question)) return null
        if (!PolymarketCityHeuristics.questionMatchesCity(question, city)) return null

        val isActive = obj.booleanOrNull("active") ?: true
        val isClosed = obj.booleanOrNull("closed") ?: false
        val acceptingOrders = obj.booleanOrNull("acceptingOrders") ?: true
        val umaResolutionStatus = obj.stringOrNull("umaResolutionStatus")?.lowercase(Locale.US)
        val closedTime = obj.stringOrNull("closedTime")
        val isResolved = umaResolutionStatus == "resolved" ||
            isClosed ||
            !closedTime.isNullOrBlank()

        if (!allowClosed) {
            if (!isActive || isClosed) return null
            if (isResolved) return null
            if (!acceptingOrders) return null
        }

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

        val bestBid = obj.doubleOrNull("bestBid")
        val bestAsk = obj.doubleOrNull("bestAsk")
        val rawSpread = obj.doubleOrNull("spread")
        val inferredSpread = when {
            rawSpread != null -> rawSpread
            bestBid != null && bestAsk != null && bestAsk >= bestBid -> bestAsk - bestBid
            else -> null
        }

        return ParsedMarket(
            id = obj.stringOrNull("id") ?: "unknown",
            question = question,
            condition = condition,
            yesPrice = yesPrice,
            noPrice = noPrice,
            yesTokenId = yesTokenId,
            acceptingOrders = acceptingOrders,
            bestBid = bestBid,
            bestAsk = bestAsk,
            liquidity = obj.doubleOrNull("liquidityNum") ?: obj.doubleOrNull("liquidity"),
            volumeBucket = obj.doubleOrNull("volume"),
            volume24h = obj.doubleOrNull("volume24hr") ?: obj.doubleOrNull("volume24hrClob"),
            spread = inferredSpread
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
            spread = market.spread,
            volumeBucket = market.volumeBucket
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

    private fun applyRecommendationControls(
        opportunities: List<TraderOpportunity>,
        cityToday: LocalDate,
        localHour: Int
    ): RecommendationControlResult {
        if (opportunities.isEmpty()) {
            return RecommendationControlResult(
                kept = emptyList(),
                traces = emptyList()
            )
        }

        val kept = mutableListOf<TraderOpportunity>()
        val traces = mutableListOf<DecisionTraceEntry>()
        opportunities
            .groupBy { opportunity -> opportunity.condition.targetDate ?: cityToday }
            .forEach { (targetDate, dayMarkets) ->
                val dominance = evaluateDominance(
                    dayMarkets = dayMarkets,
                    targetDate = targetDate,
                    cityToday = cityToday,
                    localHour = localHour
                )
                if (dominance.isDominated) {
                    dayMarkets.forEach { opportunity ->
                        traces += DecisionTraceEntry(
                            marketId = opportunity.marketId,
                            question = opportunity.question,
                            targetDate = opportunity.condition.targetDate,
                            stage = TRACE_STAGE_DOMINANCE,
                            status = DecisionTraceStatus.DISCARDED,
                            reason = "Mercado dominado por probabilidad extrema",
                            details = listOf(
                                "Hora local: $localHour",
                                "Max mkt YES: ${formatPercentValue(dominance.maxYesProbability ?: 0.0)}",
                                "Umbral dominancia: ${formatPercentValue(DOMINANCE_YES_THRESHOLD)}"
                            )
                        )
                    }
                    return@forEach
                }

                val minEdgeForHour = when {
                    targetDate == cityToday && localHour >= LATE_DAY_HOUR -> LATE_DAY_MIN_EXECUTABLE_EDGE
                    else -> CONTROL_MIN_EXECUTABLE_EDGE
                }
                dayMarkets.forEach { opportunity ->
                    val decision = evaluateExecutionControl(
                        opportunity = opportunity,
                        minEdge = minEdgeForHour
                    )
                    traces += DecisionTraceEntry(
                        marketId = opportunity.marketId,
                        question = opportunity.question,
                        targetDate = opportunity.condition.targetDate,
                        stage = TRACE_STAGE_EXECUTION,
                        status = if (decision.passes) DecisionTraceStatus.KEPT else DecisionTraceStatus.DISCARDED,
                        reason = decision.reason,
                        details = decision.details
                    )
                    if (decision.passes) {
                        kept += opportunity
                    }
                }
            }
        return RecommendationControlResult(
            kept = kept.distinctBy { opportunity -> opportunity.marketId },
            traces = traces
        )
    }

    private fun evaluateDominance(
        dayMarkets: List<TraderOpportunity>,
        targetDate: LocalDate,
        cityToday: LocalDate,
        localHour: Int
    ): DominanceDecision {
        if (targetDate != cityToday) return DominanceDecision(false, null)
        if (localHour < DOMINANCE_CHECK_HOUR) return DominanceDecision(false, null)
        val maxYesProbability = dayMarkets.maxOfOrNull { it.yesPrice } ?: return DominanceDecision(false, null)
        return DominanceDecision(
            isDominated = maxYesProbability >= DOMINANCE_YES_THRESHOLD,
            maxYesProbability = maxYesProbability
        )
    }

    private fun evaluateExecutionControl(
        opportunity: TraderOpportunity,
        minEdge: Double
    ): ExecutionControlDecision {
        val commonDetails = listOf(
            "Exec: ${formatPercentValue(opportunity.executableEdge)}",
            "Edge min: ${formatPercentValue(minEdge)}",
            "Fill: ${formatPercentValue(opportunity.fillProbability)}",
            "Liq: ${formatNullable(opportunity.liquidity, 0)}",
            "Vol24h: ${formatNullable(opportunity.volume24h, 0)}",
            "Spread: ${formatNullable(opportunity.spread, 2)}",
            "Costes: ${formatPercentValue(opportunity.totalCost)}"
        )
        if (!opportunity.shouldTrade) {
            return ExecutionControlDecision(
                passes = false,
                reason = "Señal base PASS: ventaja ejecutable insuficiente",
                details = commonDetails
            )
        }
        val liquidity = (opportunity.liquidity ?: 0.0).coerceAtLeast(0.0)
        val volume24h = (opportunity.volume24h ?: 0.0).coerceAtLeast(0.0)
        val spread = (opportunity.spread ?: DEFAULT_SPREAD_ASSUMPTION).coerceAtLeast(0.0)
        val selectedPrice = if (opportunity.recommendedBuy == "YES") opportunity.yesPrice else opportunity.noPrice

        return when {
            liquidity < CONTROL_MIN_LIQUIDITY -> ExecutionControlDecision(
                passes = false,
                reason = "Liquidez insuficiente",
                details = commonDetails + "Min liquidez: ${formatNullable(CONTROL_MIN_LIQUIDITY, 0)}"
            )
            volume24h < CONTROL_MIN_VOLUME_24H -> ExecutionControlDecision(
                passes = false,
                reason = "Volumen 24h insuficiente",
                details = commonDetails + "Min vol24h: ${formatNullable(CONTROL_MIN_VOLUME_24H, 0)}"
            )
            spread > CONTROL_MAX_SPREAD -> ExecutionControlDecision(
                passes = false,
                reason = "Spread demasiado alto",
                details = commonDetails + "Max spread: ${formatNullable(CONTROL_MAX_SPREAD, 2)}"
            )
            opportunity.totalCost > CONTROL_MAX_TOTAL_COST -> ExecutionControlDecision(
                passes = false,
                reason = "Costes totales no viables",
                details = commonDetails + "Max costes: ${formatPercentValue(CONTROL_MAX_TOTAL_COST)}"
            )
            opportunity.fillProbability < CONTROL_MIN_FILL -> ExecutionControlDecision(
                passes = false,
                reason = "Probabilidad de fill baja",
                details = commonDetails + "Min fill: ${formatPercentValue(CONTROL_MIN_FILL)}"
            )
            opportunity.executableEdge < minEdge -> ExecutionControlDecision(
                passes = false,
                reason = "Edge ejecutable por debajo del mínimo",
                details = commonDetails
            )
            selectedPrice !in CONTROL_MIN_ENTRY_PRICE..CONTROL_MAX_ENTRY_PRICE -> ExecutionControlDecision(
                passes = false,
                reason = "Precio de entrada fuera de rango",
                details = commonDetails + "Precio entrada: ${formatPercentValue(selectedPrice)}"
            )
            else -> ExecutionControlDecision(
                passes = true,
                reason = "Mantenido: pasa control de ejecución",
                details = commonDetails + "Entrada recomendada: ${opportunity.recommendedBuy}"
            )
        }
    }

    private fun formatPercentValue(value: Double): String {
        return String.format(Locale.US, "%.1f%%", value * 100.0)
    }

    private fun formatNullable(value: Double?, decimals: Int): String {
        value ?: return "--"
        return when (decimals) {
            0 -> String.format(Locale.US, "%.0f", value)
            1 -> String.format(Locale.US, "%.1f", value)
            2 -> String.format(Locale.US, "%.2f", value)
            else -> String.format(Locale.US, "%.${decimals}f", value)
        }
    }

    internal fun applyRecommendationControlsForTesting(
        opportunities: List<TraderOpportunity>,
        cityToday: LocalDate,
        localHour: Int
    ): List<DecisionTraceEntry> {
        return applyRecommendationControls(
            opportunities = opportunities,
            cityToday = cityToday,
            localHour = localHour
        ).traces
    }

    internal fun evaluateDominanceForTesting(
        dayMarkets: List<TraderOpportunity>,
        targetDate: LocalDate,
        cityToday: LocalDate,
        localHour: Int
    ): Boolean {
        return evaluateDominance(
            dayMarkets = dayMarkets,
            targetDate = targetDate,
            cityToday = cityToday,
            localHour = localHour
        ).isDominated
    }

    private fun logistic(x: Double): Double = 1.0 / (1.0 + exp(-x))

    private fun parseConditionFromQuestion(question: String, cityZoneId: String): MarketRangeCondition? {
        return PolymarketConditionParser.parseConditionFromQuestion(
            question = question,
            cityZoneId = cityZoneId
        )
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

    private fun buildPrimaryQuery(city: CityConfig): String {
        return PolymarketCityHeuristics.buildSearchQueries(city)
            .firstOrNull()
            ?: "highest temperature in ${city.name}"
    }

    private fun isHighestTemperatureQuestion(question: String): Boolean {
        val normalized = question.lowercase(Locale.US)
        return normalized.contains("highest temperature") ||
            normalized.contains("highest temp") ||
            normalized.contains("temperatura mas alta") ||
            normalized.contains("temperatura más alta")
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
        val acceptingOrders: Boolean,
        val bestBid: Double?,
        val bestAsk: Double?,
        val liquidity: Double?,
        val volumeBucket: Double?,
        val volume24h: Double?,
        val spread: Double?
    )

    private data class DirectMarketsResult(
        val markets: List<ParsedMarket>,
        val errors: List<String>
    )

    private data class RecommendationControlResult(
        val kept: List<TraderOpportunity>,
        val traces: List<DecisionTraceEntry>
    )

    private data class DominanceDecision(
        val isDominated: Boolean,
        val maxYesProbability: Double?
    )

    private data class ExecutionControlDecision(
        val passes: Boolean,
        val reason: String,
        val details: List<String>
    )

    private companion object {
        const val ASSUMED_ROUNDTRIP_FEE_RATE = 0.018
        const val DEFAULT_SPREAD_ASSUMPTION = 0.020
        const val MAX_TOTAL_EXECUTION_COST = 0.30

        const val MIN_FILL_PROBABILITY_TO_TRADE = 0.50
        const val MIN_EXECUTABLE_EDGE_TO_TRADE = 0.020

        const val GREEN_MIN_FILL_PROBABILITY = 0.65
        const val GREEN_EXECUTABLE_EDGE = 0.06

        const val YELLOW_MIN_FILL_PROBABILITY = 0.45
        const val YELLOW_EXECUTABLE_EDGE = 0.025

        const val CONTROL_MIN_EXECUTABLE_EDGE = 0.025
        const val CONTROL_MIN_FILL = 0.55
        const val CONTROL_MIN_LIQUIDITY = 250.0
        const val CONTROL_MIN_VOLUME_24H = 200.0
        const val CONTROL_MAX_SPREAD = 0.12
        const val CONTROL_MAX_TOTAL_COST = 0.22
        const val CONTROL_MIN_ENTRY_PRICE = 0.02
        const val CONTROL_MAX_ENTRY_PRICE = 0.90

        const val LATE_DAY_HOUR = 15
        const val LATE_DAY_MIN_EXECUTABLE_EDGE = 0.035
        const val DOMINANCE_CHECK_HOUR = 13
        const val DOMINANCE_YES_THRESHOLD = 0.95

        const val TRACE_STAGE_INPUT = "INPUT"
        const val TRACE_STAGE_EXECUTION = "EXECUTION_CONTROL"
        const val TRACE_STAGE_DOMINANCE = "DOMINANCE"
    }
}
