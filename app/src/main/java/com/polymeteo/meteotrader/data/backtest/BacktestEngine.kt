package com.polymeteo.meteotrader.data.backtest

import com.polymeteo.meteotrader.data.CityCatalog
import com.polymeteo.meteotrader.data.model.BacktestCityStat
import com.polymeteo.meteotrader.data.model.BacktestReport
import com.polymeteo.meteotrader.data.model.BacktestSettlementPreview
import com.polymeteo.meteotrader.data.model.BacktestStrategyStat
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.MarketConditionType
import com.polymeteo.meteotrader.data.model.MarketRangeCondition
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.data.model.TraderDirection
import com.polymeteo.meteotrader.data.model.TraderOpportunity
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import com.polymeteo.meteotrader.data.source.HttpClient
import com.polymeteo.meteotrader.data.source.PolymarketSource
import com.polymeteo.meteotrader.data.source.WundergroundSource
import com.polymeteo.meteotrader.data.source.asJsonObjectOrNull
import com.polymeteo.meteotrader.data.source.doubleOrNull
import com.polymeteo.meteotrader.data.source.longOrNull
import com.polymeteo.meteotrader.data.source.objOrNull
import com.polymeteo.meteotrader.util.celsiusToFahrenheit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

class BacktestEngine(
    private val store: BacktestStore,
    private val wundergroundSource: WundergroundSource,
    private val polymarketSource: PolymarketSource,
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowProvider: () -> Instant = { Instant.now() }
) {

    suspend fun ingestAndSettle(cities: List<CityWeatherData>): BacktestReport {
        val now = nowProvider()
        val currentDataset = store.read()
        val recordsById = currentDataset.records.associateBy { it.id }.toMutableMap()
        val warnings = mutableListOf<String>()

        var changed = false
        var historicalImportCompletedAtEpochMs = currentDataset.historicalImportCompletedAtEpochMs

        if (shouldRunHistoricalImport(currentDataset, recordsById.values.toList())) {
            val import = importHistoricalSeed(recordsById.values.toList())
            import.records.forEach { record ->
                if (!recordsById.containsKey(record.id)) {
                    recordsById[record.id] = record
                    changed = true
                }
            }
            warnings += import.warnings
            if (import.records.isNotEmpty()) {
                historicalImportCompletedAtEpochMs = now.toEpochMilli()
                changed = true
            }
        }

        val newRecords = buildSnapshotRecords(cities)
        val guardrailDayUtc = now.atZone(ZoneOffset.UTC).toLocalDate()
        var guardrailState = RiskGuardrails.buildState(
            allRecords = recordsById.values.toList(),
            day = guardrailDayUtc,
            config = RISK_GUARDRAIL_CONFIG
        )
        if (guardrailState.killSwitchActive) {
            warnings += "Risk guardrails: kill-switch activo (${guardrailState.day} UTC)"
        }

        var blockedByGuardrails = 0
        newRecords
            .asSequence()
            .filter { !recordsById.containsKey(it.id) }
            .sortedByDescending { it.executableEdge ?: it.expectedEdge }
            .forEach { record ->
                val blockReason = RiskGuardrails.evaluate(
                    record = record,
                    state = guardrailState,
                    config = RISK_GUARDRAIL_CONFIG
                )
                if (blockReason != null) {
                    blockedByGuardrails += 1
                    if (blockedByGuardrails <= MAX_GUARDRAIL_WARNING_DETAILS) {
                        warnings += "Guardrail: ${blockReason.label} bloquea ${record.cityName} (${record.marketId})"
                    }
                    return@forEach
                }
                recordsById[record.id] = record
                guardrailState = RiskGuardrails.accept(guardrailState, record)
                changed = true
            }
        if (blockedByGuardrails > 0) {
            warnings += "Risk guardrails: $blockedByGuardrails entradas bloqueadas (${guardrailDayUtc} UTC)"
        }

        val settled = settlePending(recordsById.values.toList())
        warnings += settled.warnings

        val prunedRecords = pruneRecords(settled.records, now)
        if (prunedRecords.size != currentDataset.records.size) {
            changed = true
        }

        if (changed || settled.changed) {
            store.write(
                BacktestDataset(
                    records = prunedRecords,
                    updatedAtEpochMs = now.toEpochMilli(),
                    historicalImportCompletedAtEpochMs = historicalImportCompletedAtEpochMs
                )
            )
        }

        return buildReport(
            records = prunedRecords,
            warnings = warnings.distinct()
        )
    }

    suspend fun computeReport(): BacktestReport {
        val dataset = store.read()
        return buildReport(dataset.records, warnings = emptyList())
    }

    private fun shouldRunHistoricalImport(
        dataset: BacktestDataset,
        existingRecords: List<BacktestRecord>
    ): Boolean {
        if (dataset.historicalImportCompletedAtEpochMs > 0L) return false
        if (existingRecords.any { it.isHistoricalSeed }) return false
        return true
    }

    private fun buildSnapshotRecords(cities: List<CityWeatherData>): List<BacktestRecord> {
        val records = mutableListOf<BacktestRecord>()
        for (cityData in cities) {
            for (opportunity in cityData.polymarket.opportunities) {
                buildRecord(cityData, opportunity)?.let { records += it }
            }
        }
        return records
    }

    private fun buildRecord(
        cityData: CityWeatherData,
        opportunity: TraderOpportunity
    ): BacktestRecord? {
        if (opportunity.expectedEdge < MIN_EDGE_TO_RECORD) return null

        val zoneId = ZoneId.of(cityData.city.zoneId)
        val snapshotAt = cityData.updatedAt
        val targetDate = opportunity.condition.targetDate ?: snapshotAt.atZone(zoneId).toLocalDate()
        val snapshotBucketEpochMs = truncateToBucket(snapshotAt, SNAPSHOT_BUCKET_MINUTES)
        val recommendedBuy = opportunity.recommendedBuy.uppercase(Locale.US)
        if (recommendedBuy != "YES" && recommendedBuy != "NO") return null

        val id = listOf(
            cityData.city.id,
            opportunity.marketId,
            targetDate.toString(),
            snapshotBucketEpochMs.toString(),
            recommendedBuy
        ).joinToString("|")

        return BacktestRecord(
            id = id,
            cityId = cityData.city.id,
            cityName = cityData.city.name,
            cityZoneId = cityData.city.zoneId,
            marketId = opportunity.marketId,
            question = opportunity.question,
            targetDateIso = targetDate.toString(),
            conditionType = opportunity.condition.type,
            threshold = opportunity.condition.threshold,
            upperThreshold = opportunity.condition.upperThreshold,
            conditionUnit = opportunity.condition.unit,
            snapshotEpochMs = snapshotAt.toEpochMilli(),
            yesPrice = opportunity.yesPrice.coerceIn(0.001, 0.999),
            noPrice = opportunity.noPrice.coerceIn(0.001, 0.999),
            modelProbabilityYes = opportunity.modelProbabilityYes.coerceIn(0.001, 0.999),
            expectedEdge = opportunity.expectedEdge,
            rawEdge = opportunity.rawEdge,
            executableEdge = opportunity.executableEdge,
            fillProbabilityEstimate = opportunity.fillProbability,
            feeCostEstimate = opportunity.feeCost,
            spreadCostEstimate = opportunity.spreadCost,
            liquidityCostEstimate = opportunity.liquidityCost,
            totalCostEstimate = opportunity.totalCost,
            recommendedBuy = recommendedBuy,
            direction = opportunity.direction,
            signal = opportunity.signal,
            liquidity = opportunity.liquidity,
            volume24h = opportunity.volume24h,
            spread = opportunity.spread,
            polyTempC = cityData.polyTempC,
            isHistoricalSeed = false
        )
    }

    private suspend fun importHistoricalSeed(existingRecords: List<BacktestRecord>): HistoricalImportResult = coroutineScope {
        val existingSlotKeys = existingRecords
            .map { "${it.cityId}|${it.targetDateIso}" }
            .toMutableSet()

        val records = mutableListOf<BacktestRecord>()
        val warnings = mutableListOf<String>()

        for (city in CityCatalog.cities) {
            val zoneId = ZoneId.of(city.zoneId)
            val localToday = LocalDate.now(zoneId)
            val targetDates = (1L..HISTORICAL_DAYS_BACK)
                .map { offset -> localToday.minusDays(offset) }
                .sortedDescending()

            val modelSeries = fetchHistoricalModelSeries(city, daysBack = HISTORICAL_DAYS_BACK + 2)
            if (modelSeries.isEmpty()) {
                warnings += "${city.name}: sin serie histórica de modelo"
                continue
            }

            for (targetDate in targetDates) {
                if (records.size >= MAX_HISTORICAL_IMPORT_RECORDS) break
                val slotKey = "${city.id}|$targetDate"
                if (existingSlotKeys.contains(slotKey)) continue

                val modelTempC = modelSeries[targetDate] ?: continue
                val candidates = polymarketSource.fetchHistoricalMarkets(city, targetDate)
                if (candidates.isEmpty()) continue

                val selected = selectHistoricalCandidate(
                    candidates = candidates,
                    cityZoneId = city.zoneId,
                    targetDate = targetDate,
                    modelTempC = modelTempC
                ) ?: continue

                if (selected.expectedEdge < MIN_EDGE_TO_RECORD) continue

                val entryInstant = selected.entryInstant
                val bucketMs = truncateToBucket(entryInstant, SNAPSHOT_BUCKET_MINUTES)
                val recordId = listOf(
                    "historical",
                    city.id,
                    selected.market.marketId,
                    targetDate.toString(),
                    bucketMs.toString(),
                    selected.recommendedBuy
                ).joinToString("|")

                val record = BacktestRecord(
                    id = recordId,
                    cityId = city.id,
                    cityName = city.name,
                    cityZoneId = city.zoneId,
                    marketId = selected.market.marketId,
                    question = selected.market.question,
                    targetDateIso = targetDate.toString(),
                    conditionType = selected.market.condition.type,
                    threshold = selected.market.condition.threshold,
                    upperThreshold = selected.market.condition.upperThreshold,
                    conditionUnit = selected.market.condition.unit,
                    snapshotEpochMs = selected.entryInstant.toEpochMilli(),
                    yesPrice = selected.yesPrice,
                    noPrice = selected.noPrice,
                    modelProbabilityYes = selected.modelProbabilityYes,
                    expectedEdge = selected.expectedEdge,
                    rawEdge = selected.expectedEdge,
                    executableEdge = selected.expectedEdge,
                    recommendedBuy = selected.recommendedBuy,
                    direction = selected.direction,
                    signal = selected.signal,
                    liquidity = selected.market.liquidity,
                    spread = selected.market.spread,
                    polyTempC = modelTempC,
                    isHistoricalSeed = true
                )

                records += record
                existingSlotKeys += slotKey
            }
        }

        if (records.isNotEmpty()) {
            warnings += "Importador histórico: ${records.size} trades añadidos"
        } else {
            warnings += "Importador histórico: sin trades añadidos"
        }

        HistoricalImportResult(records = records, warnings = warnings)
    }

    private suspend fun fetchHistoricalModelSeries(
        city: com.polymeteo.meteotrader.data.model.CityConfig,
        daysBack: Long
    ): Map<LocalDate, Double> {
        val url = buildString {
            append("https://previous-runs-api.open-meteo.com/v1/forecast")
            append("?latitude=${city.latitude}")
            append("&longitude=${city.longitude}")
            append("&daily=temperature_2m_max")
            append("&timezone=auto")
            append("&past_days=$daysBack")
            append("&forecast_days=1")
            append("&models=ecmwf_ifs")
        }

        return runCatching {
            val response = httpClient.get(url = url, retries = 1)
            if (response.code !in 200..299) return emptyMap()
            val root = json.parseToJsonElement(response.body).asJsonObjectOrNull() ?: return emptyMap()
            val daily = root.objOrNull("daily") ?: return emptyMap()
            val dates = (daily["time"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() } }
                .orEmpty()
            val maxValues = (daily["temperature_2m_max"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
                .orEmpty()

            dates.zip(maxValues)
                .filter { (_, value) -> value in VALID_TEMP_RANGE_C }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private suspend fun selectHistoricalCandidate(
        candidates: List<PolymarketSource.HistoricalMarketCandidate>,
        cityZoneId: String,
        targetDate: LocalDate,
        modelTempC: Double
    ): SelectedCandidate? {
        val topByCertainty = candidates
            .map { candidate ->
                val probability = estimateProbabilityYes(
                    condition = candidate.condition,
                    modelTempC = modelTempC,
                    sigmaC = HISTORICAL_SIGMA_C
                )
                CandidateScoring(
                    market = candidate,
                    modelProbabilityYes = probability,
                    certainty = abs(probability - 0.5)
                )
            }
            .sortedByDescending { it.certainty }
            .take(HISTORICAL_CANDIDATES_PER_DATE)

        if (topByCertainty.isEmpty()) return null

        val entryInstant = targetDate
            .atTime(HISTORICAL_ENTRY_HOUR_LOCAL, 0)
            .atZone(ZoneId.of(cityZoneId))
            .toInstant()

        var best: SelectedCandidate? = null

        for (scored in topByCertainty) {
            val yesPrice = fetchEntryYesPrice(scored.market.yesTokenId, entryInstant) ?: continue
            val noPrice = (1.0 - yesPrice).coerceIn(0.001, 0.999)
            val evYes = scored.modelProbabilityYes - yesPrice
            val evNo = (1.0 - scored.modelProbabilityYes) - noPrice
            val recommendedBuy = if (evYes >= evNo) "YES" else "NO"
            val expectedEdge = max(evYes, evNo)

            val meanInUnit = if (scored.market.condition.unit == TempUnit.C) {
                modelTempC
            } else {
                celsiusToFahrenheit(modelTempC)
            }

            val direction = resolveDirection(
                condition = scored.market.condition,
                recommendedBuy = recommendedBuy,
                meanInConditionUnit = meanInUnit
            )

            val signal = when {
                expectedEdge >= 0.12 -> TraderSignalLevel.GREEN
                expectedEdge >= 0.06 -> TraderSignalLevel.YELLOW
                else -> TraderSignalLevel.RED
            }

            val selected = SelectedCandidate(
                market = scored.market,
                modelProbabilityYes = scored.modelProbabilityYes,
                yesPrice = yesPrice,
                noPrice = noPrice,
                expectedEdge = expectedEdge,
                recommendedBuy = recommendedBuy,
                direction = direction,
                signal = signal,
                entryInstant = entryInstant
            )

            if (best == null || selected.expectedEdge > best.expectedEdge) {
                best = selected
            }
        }

        return best
    }

    private suspend fun fetchEntryYesPrice(tokenId: String, entryInstant: Instant): Double? {
        val entryTs = entryInstant.epochSecond
        val startTs = entryTs - PRICE_WINDOW_SECONDS
        val endTs = entryTs + PRICE_WINDOW_SECONDS
        val url = "https://clob.polymarket.com/prices-history?market=$tokenId&interval=max&fidelity=60&startTs=$startTs&endTs=$endTs"

        return runCatching {
            val response = httpClient.get(url = url, retries = 1)
            if (response.code !in 200..299) return null
            val root = json.parseToJsonElement(response.body).asJsonObjectOrNull() ?: return null
            val history = root["history"] as? JsonArray ?: return null

            val point = history
                .mapNotNull { element ->
                    val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
                    val ts = obj.longOrNull("t") ?: return@mapNotNull null
                    val price = obj.doubleOrNull("p") ?: return@mapNotNull null
                    PricePoint(epochSecond = ts, price = price)
                }
                .minByOrNull { abs(it.epochSecond - entryTs) }
                ?: return null

            point.price.coerceIn(0.02, 0.98)
        }.getOrNull()
    }

    private fun estimateProbabilityYes(
        condition: MarketRangeCondition,
        modelTempC: Double,
        sigmaC: Double
    ): Double {
        val unitFactor = if (condition.unit == TempUnit.C) 1.0 else 9.0 / 5.0
        val mean = if (condition.unit == TempUnit.C) modelTempC else celsiusToFahrenheit(modelTempC)
        val sigma = max(0.6, sigmaC * unitFactor)
        val threshold = condition.threshold

        return when (condition.type) {
            MarketConditionType.GREATER_OR_EQUAL -> 1.0 - normalCdf((threshold - 0.5 - mean) / sigma)
            MarketConditionType.LESS_OR_EQUAL -> normalCdf((threshold + 0.5 - mean) / sigma)
            MarketConditionType.EXACT -> {
                val upper = normalCdf((threshold + 0.5 - mean) / sigma)
                val lower = normalCdf((threshold - 0.5 - mean) / sigma)
                upper - lower
            }
            MarketConditionType.BETWEEN -> {
                val upperThreshold = condition.upperThreshold ?: threshold
                val upper = normalCdf((upperThreshold + 0.5 - mean) / sigma)
                val lower = normalCdf((threshold - 0.5 - mean) / sigma)
                upper - lower
            }
        }.coerceIn(0.001, 0.999)
    }

    private fun resolveDirection(
        condition: MarketRangeCondition,
        recommendedBuy: String,
        meanInConditionUnit: Double
    ): TraderDirection {
        return when (condition.type) {
            MarketConditionType.GREATER_OR_EQUAL -> if (recommendedBuy == "YES") TraderDirection.OVER else TraderDirection.UNDER
            MarketConditionType.LESS_OR_EQUAL -> if (recommendedBuy == "YES") TraderDirection.UNDER else TraderDirection.OVER
            MarketConditionType.EXACT -> when {
                meanInConditionUnit >= condition.threshold + 0.35 -> TraderDirection.OVER
                meanInConditionUnit <= condition.threshold - 0.35 -> TraderDirection.UNDER
                else -> TraderDirection.RANGE
            }
            MarketConditionType.BETWEEN -> {
                val upper = condition.upperThreshold ?: condition.threshold
                if (recommendedBuy == "YES") {
                    TraderDirection.RANGE
                } else {
                    when {
                        meanInConditionUnit > upper + 0.35 -> TraderDirection.OVER
                        meanInConditionUnit < condition.threshold - 0.35 -> TraderDirection.UNDER
                        else -> TraderDirection.RANGE
                    }
                }
            }
        }
    }

    private suspend fun settlePending(records: List<BacktestRecord>): SettlementResult = coroutineScope {
        val now = nowProvider()
        val nowEpochMs = now.toEpochMilli()
        val recordsById = records.associateBy { it.id }.toMutableMap()
        val cityById = CityCatalog.cities.associateBy { it.id }

        val pendingGroups = records
            .asSequence()
            .filter { !it.settled }
            .mapNotNull { record ->
                val city = cityById[record.cityId] ?: return@mapNotNull null
                val targetDate = runCatching { LocalDate.parse(record.targetDateIso) }.getOrNull() ?: return@mapNotNull null
                val cityToday = LocalDate.now(ZoneId.of(city.zoneId))
                if (!targetDate.isBefore(cityToday)) return@mapNotNull null
                if (!shouldAttemptSettlement(record, nowEpochMs)) return@mapNotNull null
                SettlementGroup(city.id, targetDate) to record.id
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        if (pendingGroups.isEmpty()) {
            return@coroutineScope SettlementResult(
                records = records,
                changed = false,
                warnings = emptyList()
            )
        }

        val selectedGroups = pendingGroups
            .keys
            .sortedByDescending { it.targetDate }
            .take(MAX_SETTLEMENT_QUERIES_PER_RUN)

        var changed = false
        for (group in selectedGroups) {
            pendingGroups[group].orEmpty().forEach { recordId ->
                val record = recordsById[recordId] ?: return@forEach
                recordsById[recordId] = record.copy(
                    lastSettleAttemptEpochMs = nowEpochMs,
                    settleAttempts = record.settleAttempts + 1
                )
                changed = true
            }
        }

        val semaphore = Semaphore(MAX_SETTLEMENT_PARALLELISM)
        val fetchResults = selectedGroups.map { group ->
            async {
                semaphore.withPermit {
                    val city = cityById[group.cityId] ?: return@withPermit GroupFetchResult(group, null)
                    val snapshot = wundergroundSource.fetchDailyMaxForDate(city, group.targetDate)
                    GroupFetchResult(group, snapshot)
                }
            }
        }.awaitAll()

        val warnings = mutableListOf<String>()

        for (result in fetchResults) {
            val snapshot = result.snapshot
            val group = result.group
            if (snapshot == null) continue

            val ids = pendingGroups[group].orEmpty()
            val sourceUrl = snapshot.sourceUrl

            if (snapshot.tempC == null && snapshot.tempF == null) {
                if (!snapshot.error.isNullOrBlank()) {
                    warnings += "${group.cityId} ${group.targetDate}: ${snapshot.error}"
                }
                continue
            }

            for (id in ids) {
                val record = recordsById[id] ?: continue
                val observed = when (record.conditionUnit) {
                    TempUnit.C -> snapshot.tempC
                    TempUnit.F -> snapshot.tempF
                } ?: continue

                val actualYes = BacktestMath.evaluateOutcome(
                    conditionType = record.conditionType,
                    threshold = record.threshold,
                    upperThreshold = record.upperThreshold,
                    observed = observed
                )
                val fillDraw = stableDraw(record.id, "entry-fill")
                val evaluation = BacktestMath.evaluatePaperTrade(
                    recommendedBuy = record.recommendedBuy,
                    yesPrice = record.yesPrice,
                    noPrice = record.noPrice,
                    modelProbabilityYes = record.modelProbabilityYes,
                    actualYes = actualYes,
                    expectedEdge = record.executableEdge ?: record.expectedEdge,
                    fillProbabilityEstimate = record.fillProbabilityEstimate,
                    spread = record.spread,
                    liquidity = record.liquidity,
                    volume24h = record.volume24h,
                    fillDraw = fillDraw
                )

                recordsById[id] = record.copy(
                    settled = true,
                    settledEpochMs = nowEpochMs,
                    settlementSourceUrl = sourceUrl,
                    observedMaxInConditionUnit = observed,
                    actualYesOutcome = actualYes,
                    executed = evaluation.executed,
                    expectedPnlUnits = evaluation.expectedPnlUnits,
                    executionGapUnits = evaluation.executionGapUnits,
                    fillProbabilityUsed = evaluation.fillProbabilityUsed,
                    fillDraw = evaluation.fillDraw,
                    entryPriceUnits = evaluation.entryPriceUnits,
                    entryFeeUnits = evaluation.entryFeeUnits,
                    entrySlippageUnits = evaluation.entrySlippageUnits,
                    exitPayoutUnits = evaluation.exitPayoutUnits,
                    pnlUnits = evaluation.pnlUnits,
                    stakeUnits = evaluation.stakeUnits,
                    brierScore = evaluation.brierScore,
                    logLoss = evaluation.logLoss
                )
                changed = true
            }
        }

        SettlementResult(
            records = recordsById.values.toList(),
            changed = changed,
            warnings = warnings.distinct()
        )
    }

    private fun shouldAttemptSettlement(record: BacktestRecord, nowEpochMs: Long): Boolean {
        if (record.settleAttempts >= MAX_SETTLE_ATTEMPTS) return false
        val lastAttempt = record.lastSettleAttemptEpochMs ?: return true
        val cooldownHours = when (record.settleAttempts) {
            0 -> 0L
            1 -> 2L
            2 -> 6L
            else -> 12L
        }
        val elapsedMs = nowEpochMs - lastAttempt
        return elapsedMs >= cooldownHours * 60L * 60L * 1000L
    }

    private fun pruneRecords(records: List<BacktestRecord>, now: Instant): List<BacktestRecord> {
        val minEpochMs = now.minus(RETENTION_DAYS, ChronoUnit.DAYS).toEpochMilli()
        return records
            .asSequence()
            .filter { it.snapshotEpochMs >= minEpochMs }
            .sortedByDescending { it.snapshotEpochMs }
            .take(MAX_RECORDS)
            .toList()
    }

    private fun buildReport(records: List<BacktestRecord>, warnings: List<String>): BacktestReport {
        val now = nowProvider()
        val guardrailDayUtc = now.atZone(ZoneOffset.UTC).toLocalDate()
        val guardrailState = RiskGuardrails.buildState(
            allRecords = records,
            day = guardrailDayUtc,
            config = RISK_GUARDRAIL_CONFIG
        )

        val settled = records.filter {
            it.settled &&
                it.actualYesOutcome != null
        }

        val liveTrades = records.count { !it.isHistoricalSeed }
        val historicalTrades = records.size - liveTrades
        val settledLiveTrades = settled.count { !it.isHistoricalSeed }
        val settledHistoricalTrades = settled.size - settledLiveTrades
        val executedSettled = settled.filter { it.isExecutedTrade() }
        val executedLiveTrades = executedSettled.count { !it.isHistoricalSeed }
        val executedHistoricalTrades = executedSettled.size - executedLiveTrades
        val skippedByFill = settled.size - executedSettled.size

        val totalStake = executedSettled.sumOf { it.stakeUnits ?: 0.0 }
        val totalPnl = settled.sumOf { it.pnlUnits ?: 0.0 }
        val expectedPnl = settled.sumOf { it.expectedPnlOrFallback() }
        val executionGap = settled.sumOf { it.executionGapOrFallback() }
        val settledCount = settled.size
        val executedCount = executedSettled.size
        val hitCount = executedSettled.count { didHit(it) }
        val brierScore = settled.mapNotNull { it.brierScore }.averageOrNull()
        val logLoss = settled.mapNotNull { it.logLoss }.averageOrNull()
        val avgEdge = settled.map { it.expectedEdge }.averageOrNull()
        val fillRate = if (settledCount > 0) executedCount.toDouble() / settledCount.toDouble() else null

        val cityStats = settled
            .groupBy { it.cityId }
            .map { (cityId, cityRecords) ->
                val executedCityRecords = cityRecords.filter { it.isExecutedTrade() }
                val stake = executedCityRecords.sumOf { it.stakeUnits ?: 0.0 }
                val pnl = cityRecords.sumOf { it.pnlUnits ?: 0.0 }
                val expected = cityRecords.sumOf { it.expectedPnlOrFallback() }
                val gap = cityRecords.sumOf { it.executionGapOrFallback() }
                val executedCityCount = executedCityRecords.size
                BacktestCityStat(
                    cityId = cityId,
                    cityName = cityRecords.firstOrNull()?.cityName ?: cityId,
                    settledTrades = cityRecords.size,
                    executedTrades = executedCityCount,
                    fillRate = if (cityRecords.isNotEmpty()) executedCityCount.toDouble() / cityRecords.size.toDouble() else null,
                    hitRate = cityRecords.hitRate(),
                    totalPnlUnits = pnl,
                    expectedPnlUnits = expected,
                    executionGapUnits = gap,
                    roi = if (stake > 0.0) pnl / stake else null,
                    avgExpectedEdge = cityRecords.map { it.expectedEdge }.averageOrNull(),
                    brierScore = cityRecords.mapNotNull { it.brierScore }.averageOrNull()
                )
            }
            .sortedWith(compareByDescending<BacktestCityStat> { it.totalPnlUnits }.thenByDescending { it.executedTrades })

        val strategyStats = settled
            .groupBy { it.direction to it.signal }
            .map { (key, strategyRecords) ->
                val executedStrategyRecords = strategyRecords.filter { it.isExecutedTrade() }
                val stake = executedStrategyRecords.sumOf { it.stakeUnits ?: 0.0 }
                val pnl = strategyRecords.sumOf { it.pnlUnits ?: 0.0 }
                val expected = strategyRecords.sumOf { it.expectedPnlOrFallback() }
                val gap = strategyRecords.sumOf { it.executionGapOrFallback() }
                val executedStrategyCount = executedStrategyRecords.size
                BacktestStrategyStat(
                    direction = key.first,
                    signal = key.second,
                    settledTrades = strategyRecords.size,
                    executedTrades = executedStrategyCount,
                    fillRate = if (strategyRecords.isNotEmpty()) {
                        executedStrategyCount.toDouble() / strategyRecords.size.toDouble()
                    } else {
                        null
                    },
                    hitRate = strategyRecords.hitRate(),
                    totalPnlUnits = pnl,
                    expectedPnlUnits = expected,
                    executionGapUnits = gap,
                    roi = if (stake > 0.0) pnl / stake else null,
                    avgExpectedEdge = strategyRecords.map { it.expectedEdge }.averageOrNull()
                )
            }
            .sortedWith(compareByDescending<BacktestStrategyStat> { it.totalPnlUnits }.thenByDescending { it.executedTrades })

        val recentSettlements = settled
            .sortedByDescending { it.settledEpochMs ?: it.snapshotEpochMs }
            .take(20)
            .mapNotNull { record ->
                val targetDate = runCatching { LocalDate.parse(record.targetDateIso) }.getOrNull() ?: return@mapNotNull null
                val settledAt = runCatching { Instant.ofEpochMilli(record.settledEpochMs ?: record.snapshotEpochMs) }.getOrNull()
                    ?: return@mapNotNull null
                BacktestSettlementPreview(
                    cityName = record.cityName,
                    question = record.question,
                    targetDate = targetDate,
                    recommendedBuy = record.recommendedBuy,
                    direction = record.direction,
                    signal = record.signal,
                    executed = record.isExecutedTrade(),
                    pnlUnits = record.pnlUnits ?: 0.0,
                    expectedPnlUnits = record.expectedPnlOrFallback(),
                    executionGapUnits = record.executionGapOrFallback(),
                    hit = didHit(record),
                    expectedEdge = record.expectedEdge,
                    observedMaxInConditionUnit = record.observedMaxInConditionUnit,
                    conditionUnit = record.conditionUnit,
                    settledAt = settledAt,
                    snapshotAt = Instant.ofEpochMilli(record.snapshotEpochMs)
                )
            }

        val reportWarnings = buildList {
            addAll(warnings)
            if (guardrailState.killSwitchActive) {
                add("Risk guardrails: kill-switch activo (${guardrailState.day} UTC)")
            }
            if (guardrailState.realizedPnlUnits <= -RISK_GUARDRAIL_CONFIG.maxDailyLossUnits) {
                add("Risk guardrails: perdida diaria maxima alcanzada (${String.format(Locale.US, "%.3f", guardrailState.realizedPnlUnits)}u)")
            }
        }.distinct()

        return BacktestReport(
            generatedAt = now,
            totalTrades = records.size,
            liveTrades = liveTrades,
            historicalTrades = historicalTrades,
            settledTrades = settledCount,
            settledLiveTrades = settledLiveTrades,
            settledHistoricalTrades = settledHistoricalTrades,
            simulatedTrades = settledCount,
            simulatedLiveTrades = settledLiveTrades,
            simulatedHistoricalTrades = settledHistoricalTrades,
            executedTrades = executedCount,
            executedLiveTrades = executedLiveTrades,
            executedHistoricalTrades = executedHistoricalTrades,
            skippedByFillTrades = skippedByFill,
            pendingTrades = records.size - settledCount,
            fillRate = fillRate,
            totalStakeUnits = totalStake,
            totalPnlUnits = totalPnl,
            expectedPnlUnits = expectedPnl,
            executionGapUnits = executionGap,
            guardrailDayUtc = guardrailState.day,
            guardrailKillSwitchActive = guardrailState.killSwitchActive,
            guardrailDailyPnlUnits = guardrailState.realizedPnlUnits,
            guardrailConsecutiveLosses = guardrailState.consecutiveExecutedLosses,
            avgPnlUnits = if (executedCount > 0) totalPnl / executedCount else null,
            roi = if (totalStake > 0.0) totalPnl / totalStake else null,
            hitRate = if (executedCount > 0) hitCount.toDouble() / executedCount.toDouble() else null,
            avgExpectedEdge = avgEdge,
            brierScore = brierScore,
            logLoss = logLoss,
            cityStats = cityStats,
            strategyStats = strategyStats,
            recentSettlements = recentSettlements,
            warnings = reportWarnings
        )
    }

    private fun didHit(record: BacktestRecord): Boolean {
        if (!record.isExecutedTrade()) return false
        val actualYes = record.actualYesOutcome ?: return false
        return BacktestMath.didHit(record.recommendedBuy, actualYes)
    }

    private fun List<BacktestRecord>.hitRate(): Double? {
        val executed = filter { it.isExecutedTrade() }
        if (executed.isEmpty()) return null
        val hits = executed.count { didHit(it) }
        return hits.toDouble() / executed.size.toDouble()
    }

    private fun BacktestRecord.expectedPnlOrFallback(): Double {
        return expectedPnlUnits ?: expectedEdge
    }

    private fun BacktestRecord.executionGapOrFallback(): Double {
        return executionGapUnits ?: ((pnlUnits ?: 0.0) - expectedPnlOrFallback())
    }

    private fun BacktestRecord.isExecutedTrade(): Boolean {
        return executed && pnlUnits != null && stakeUnits != null
    }

    private fun List<Double>.averageOrNull(): Double? {
        if (isEmpty()) return null
        return average()
    }

    private fun truncateToBucket(instant: Instant, minutes: Long): Long {
        val bucketMs = minutes * 60L * 1000L
        val epochMs = instant.toEpochMilli()
        return (epochMs / bucketMs) * bucketMs
    }

    private fun stableDraw(key: String, salt: String): Double {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$key|$salt".toByteArray(Charsets.UTF_8))
        val value = ((bytes[0].toInt() and 0xFF) shl 16) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            (bytes[2].toInt() and 0xFF)
        return value.toDouble() / 0xFFFFFF.toDouble()
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

    private data class SettlementGroup(
        val cityId: String,
        val targetDate: LocalDate
    )

    private data class GroupFetchResult(
        val group: SettlementGroup,
        val snapshot: com.polymeteo.meteotrader.data.model.ControlStationSnapshot?
    )

    private data class SettlementResult(
        val records: List<BacktestRecord>,
        val changed: Boolean,
        val warnings: List<String>
    )

    private data class HistoricalImportResult(
        val records: List<BacktestRecord>,
        val warnings: List<String>
    )

    private data class CandidateScoring(
        val market: PolymarketSource.HistoricalMarketCandidate,
        val modelProbabilityYes: Double,
        val certainty: Double
    )

    private data class SelectedCandidate(
        val market: PolymarketSource.HistoricalMarketCandidate,
        val modelProbabilityYes: Double,
        val yesPrice: Double,
        val noPrice: Double,
        val expectedEdge: Double,
        val recommendedBuy: String,
        val direction: TraderDirection,
        val signal: TraderSignalLevel,
        val entryInstant: Instant
    )

    private data class PricePoint(
        val epochSecond: Long,
        val price: Double
    )

    companion object {
        private const val SNAPSHOT_BUCKET_MINUTES = 30L
        private const val MIN_EDGE_TO_RECORD = 0.01
        private const val RETENTION_DAYS = 120L
        private const val MAX_RECORDS = 24_000
        private const val MAX_SETTLE_ATTEMPTS = 5
        private const val MAX_SETTLEMENT_QUERIES_PER_RUN = 24
        private const val MAX_SETTLEMENT_PARALLELISM = 4
        private const val MAX_GUARDRAIL_WARNING_DETAILS = 8

        private const val HISTORICAL_DAYS_BACK = 7L
        private const val HISTORICAL_ENTRY_HOUR_LOCAL = 9
        private const val HISTORICAL_CANDIDATES_PER_DATE = 3
        private const val HISTORICAL_SIGMA_C = 1.9
        private const val MAX_HISTORICAL_IMPORT_RECORDS = 220
        private const val PRICE_WINDOW_SECONDS = 5 * 60 * 60L
        private val VALID_TEMP_RANGE_C = -80.0..65.0
        private val RISK_GUARDRAIL_CONFIG = RiskGuardrailConfig(
            maxTradesPerDay = 24,
            maxStakePerDayUnits = 10.0,
            maxDailyLossUnits = 2.25,
            maxLossPerMarketUnits = 0.90,
            minLiquidity = 900.0,
            maxSpread = 0.045,
            maxConsecutiveLosses = 4
        )
    }
}
