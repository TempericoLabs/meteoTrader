package com.polymeteo.meteotrader.data.premium

import com.polymeteo.meteotrader.data.CityCatalog
import com.polymeteo.meteotrader.data.forecast.ForecastWeights
import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.ForecastSourceResult
import com.polymeteo.meteotrader.data.model.PolyTempPremiumBootstrapProgress
import com.polymeteo.meteotrader.data.model.PolyTempPremiumModelStat
import com.polymeteo.meteotrader.data.model.PolyTempPremiumProviderDay
import com.polymeteo.meteotrader.data.model.PolyTempPremiumReport
import com.polymeteo.meteotrader.data.model.PolyTempPremiumVerificationDay
import com.polymeteo.meteotrader.data.model.SourceStatus
import com.polymeteo.meteotrader.data.source.NoaaObservedSource
import com.polymeteo.meteotrader.data.source.OpenMeteoHistoricalSource
import com.polymeteo.meteotrader.data.source.OpenMeteoModelSpec
import com.polymeteo.meteotrader.data.source.WundergroundSource
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

data class PremiumWeightSnapshot(
    val weightsByProviderId: Map<String, Double>,
    val verifiedDays: Int,
    val lastVerifiedDate: LocalDate?,
    val calibrationReady: Boolean,
    val calibrationProgress: Double,
    val warnings: List<String>
)

class PolyTempPremiumEngine(
    private val store: PolyTempPremiumStore,
    private val wundergroundSource: WundergroundSource,
    private val openMeteoHistoricalSource: OpenMeteoHistoricalSource,
    private val noaaObservedSource: NoaaObservedSource,
    private val nowProvider: () -> Instant = { Instant.now() }
) {

    suspend fun ingestAndResolveWeights(
        city: CityConfig,
        targetDate: LocalDate,
        forecasts: List<ForecastSourceResult>
    ): PremiumWeightSnapshot = coroutineScope {
        val now = nowProvider()
        var changed = false

        var dataset = store.read()
        val sanitized = sanitizeDeprecatedProviders(dataset)
        dataset = sanitized.dataset
        changed = changed || sanitized.changed

        val targetHorizonDays = horizonDays(city = city, targetDate = targetDate, now = now)

        val upsert = upsertSnapshots(
            dataset = dataset,
            city = city,
            targetDate = targetDate,
            horizonDays = targetHorizonDays,
            forecasts = forecasts,
            now = now
        )
        dataset = upsert.dataset
        changed = changed || upsert.changed

        val verified = verifyPendingDates(
            dataset = dataset,
            city = city,
            now = now
        )
        dataset = verified.dataset
        changed = changed || verified.changed

        val pruned = pruneDataset(dataset = dataset)
        dataset = pruned.dataset
        changed = changed || pruned.changed

        if (changed) {
            store.write(dataset.copy(updatedAtEpochMs = now.toEpochMilli()))
        }

        val cityToday = cityToday(city, now)
        val providerIds = forecasts
            .mapNotNull { result ->
                if (ForecastWeights.isDeprecatedProvider(result.sourceId, result.sourceName)) null else result.sourceId
            }
            .toSet()

        val resolution = resolveWeightsForHorizon(
            dataset = dataset,
            city = city,
            horizonDays = targetHorizonDays,
            providerIds = providerIds,
            cityToday = cityToday
        )
        val bootstrapProgress = buildBootstrapProgress(
            dataset = dataset,
            city = city,
            cityToday = cityToday
        )
        val targetBootstrap = bootstrapProgress.firstOrNull { item ->
            item.horizonDays == targetHorizonDays
        }
        val targetProgress = when {
            targetBootstrap == null -> 0.0
            targetBootstrap.totalDays <= 0 -> 1.0
            else -> targetBootstrap.processedDays.toDouble() / targetBootstrap.totalDays.toDouble()
        }.coerceIn(0.0, 1.0)

        PremiumWeightSnapshot(
            weightsByProviderId = resolution.weightsByProviderId,
            verifiedDays = resolution.verifiedDays,
            lastVerifiedDate = resolution.lastVerifiedDate,
            calibrationReady = targetBootstrap?.completed == true || targetProgress >= 1.0,
            calibrationProgress = targetProgress,
            warnings = (verified.warnings + resolution.warnings).distinct()
        )
    }

    suspend fun refreshCityReport(city: CityConfig): PolyTempPremiumReport {
        val now = nowProvider()
        var changed = false
        val warnings = mutableListOf<String>()

        var dataset = store.read()
        val sanitized = sanitizeDeprecatedProviders(dataset)
        dataset = sanitized.dataset
        changed = changed || sanitized.changed

        val bootstrap = bootstrapHistoricalCalibration(
            dataset = dataset,
            city = city,
            now = now,
            chunkDays = BOOTSTRAP_CHUNK_DAYS_FULL,
            bypassCooldown = true,
            runToCompletion = true
        )
        dataset = bootstrap.dataset
        changed = changed || bootstrap.changed
        warnings += bootstrap.warnings

        val verified = verifyPendingDates(
            dataset = dataset,
            city = city,
            now = now
        )
        dataset = verified.dataset
        changed = changed || verified.changed
        warnings += verified.warnings

        val pruned = pruneDataset(dataset = dataset)
        dataset = pruned.dataset
        changed = changed || pruned.changed

        if (changed) {
            store.write(dataset.copy(updatedAtEpochMs = now.toEpochMilli()))
        }

        return buildReport(
            dataset = dataset,
            city = city,
            now = now,
            extraWarnings = warnings
        )
    }

    suspend fun loadCityReport(city: CityConfig): PolyTempPremiumReport {
        val now = nowProvider()
        var changed = false
        val warnings = mutableListOf<String>()

        var dataset = store.read()
        val sanitized = sanitizeDeprecatedProviders(dataset)
        dataset = sanitized.dataset
        changed = changed || sanitized.changed

        val bootstrap = bootstrapHistoricalCalibration(
            dataset = dataset,
            city = city,
            now = now,
            chunkDays = BOOTSTRAP_CHUNK_DAYS_LOAD,
            bypassCooldown = false,
            runToCompletion = false
        )
        dataset = bootstrap.dataset
        changed = changed || bootstrap.changed
        warnings += bootstrap.warnings

        val verified = verifyPendingDates(
            dataset = dataset,
            city = city,
            now = now
        )
        dataset = verified.dataset
        changed = changed || verified.changed
        warnings += verified.warnings

        val pruned = pruneDataset(dataset = dataset)
        dataset = pruned.dataset
        changed = changed || pruned.changed

        if (changed) {
            store.write(dataset.copy(updatedAtEpochMs = now.toEpochMilli()))
        }

        return buildReport(
            dataset = dataset,
            city = city,
            now = now,
            extraWarnings = warnings
        )
    }

    private fun upsertSnapshots(
        dataset: PolyTempPremiumDataset,
        city: CityConfig,
        targetDate: LocalDate,
        horizonDays: Int,
        forecasts: List<ForecastSourceResult>,
        now: Instant
    ): DatasetMutation {
        if (forecasts.isEmpty()) return DatasetMutation(dataset, changed = false)

        val snapshotsByKey = dataset.snapshots.associateBy { snapshot ->
            snapshotKey(snapshot.cityId, snapshot.targetDateIso, snapshot.providerId, snapshot.horizonDays)
        }.toMutableMap()
        var changed = false

        for (forecast in forecasts) {
            if (ForecastWeights.isDeprecatedProvider(forecast.sourceId, forecast.sourceName)) {
                continue
            }
            val sanitizedMax = forecast.maxTempC
                ?.takeIf { forecast.status == SourceStatus.SUCCESS }
                ?.takeIf { it in VALID_TEMP_RANGE_C }

            val next = PolyTempPremiumSnapshotRecord(
                cityId = city.id,
                cityName = city.name,
                cityZoneId = city.zoneId,
                targetDateIso = targetDate.toString(),
                horizonDays = horizonDays,
                providerId = forecast.sourceId,
                providerName = forecast.sourceName,
                capturedEpochMs = now.toEpochMilli(),
                status = forecast.status.name,
                forecastMaxC = sanitizedMax,
                errorMessage = forecast.error
            )
            val key = snapshotKey(city.id, targetDate.toString(), forecast.sourceId, horizonDays)
            val previous = snapshotsByKey[key]
            if (previous != next) {
                snapshotsByKey[key] = next
                changed = true
            }
        }

        if (!changed) return DatasetMutation(dataset, changed = false)

        return DatasetMutation(
            dataset = dataset.copy(
                snapshots = snapshotsByKey.values.sortedWith(
                    compareBy<PolyTempPremiumSnapshotRecord> { it.targetDateIso }
                        .thenBy { it.horizonDays }
                        .thenBy { it.providerId }
                        .thenBy { it.capturedEpochMs }
                )
            ),
            changed = true
        )
    }

    private suspend fun verifyPendingDates(
        dataset: PolyTempPremiumDataset,
        city: CityConfig,
        now: Instant
    ): VerifyMutation {
        val zoneId = ZoneId.of(city.zoneId)
        val cityToday = LocalDate.now(zoneId)
        val snapshots = dataset.snapshots.filter { it.cityId == city.id }

        val verifiedKeys = dataset.verifications
            .asSequence()
            .filter { it.cityId == city.id }
            .mapNotNull { record ->
                val date = record.targetDateIso.toLocalDateOrNull() ?: return@mapNotNull null
                date to record.horizonDays
            }
            .toSet()

        val pending = snapshots
            .asSequence()
            .mapNotNull { snapshot ->
                val date = snapshot.targetDateIso.toLocalDateOrNull() ?: return@mapNotNull null
                date to snapshot.horizonDays
            }
            .filter { (date, _) -> date.isBefore(cityToday) }
            .distinct()
            .filterNot { verifiedKeys.contains(it) }
            .sortedWith(compareByDescending<Pair<LocalDate, Int>> { it.first }.thenBy { it.second })
            .take(MAX_VERIFY_DATES_PER_RUN)
            .toList()

        if (pending.isEmpty()) {
            return VerifyMutation(dataset = dataset, changed = false, warnings = emptyList())
        }

        val warnings = mutableListOf<String>()
        var changed = false

        val verificationsByKey = dataset.verifications
            .associateBy { verificationKey(it.cityId, it.targetDateIso, it.horizonDays) }
            .toMutableMap()

        val observedCache = observedSeriesFromVerifications(dataset, city).toMutableMap()

        for ((targetDate, horizonDays) in pending) {
            val observed = resolveObservedForDate(
                city = city,
                date = targetDate,
                observedCache = observedCache
            )
            warnings += observed.warnings
            val observedPoint = observed.point
            if (observedPoint == null || observedPoint.tempC !in VALID_TEMP_RANGE_C) {
                warnings += "${city.name} $targetDate H$horizonDays: sin observación válida"
                continue
            }

            val daySnapshots = snapshots
                .filter { it.targetDateIso == targetDate.toString() && it.horizonDays == horizonDays }
                .sortedBy { it.providerName }

            if (daySnapshots.isEmpty()) continue

            val entries = daySnapshots.map { snapshot ->
                val validForecast = snapshot.forecastMaxC != null && snapshot.forecastMaxC in VALID_TEMP_RANGE_C
                val absError = if (validForecast) abs(snapshot.forecastMaxC!! - observedPoint.tempC) else null
                val squaredError = absError?.let { it * it }

                PolyTempPremiumVerificationEntry(
                    providerId = snapshot.providerId,
                    providerName = snapshot.providerName,
                    capturedEpochMs = snapshot.capturedEpochMs,
                    status = snapshot.status,
                    forecastMaxC = snapshot.forecastMaxC,
                    absoluteErrorC = absError,
                    squaredErrorC = squaredError,
                    errorMessage = snapshot.errorMessage
                )
            }

            val candidate = PolyTempPremiumVerificationRecord(
                cityId = city.id,
                cityName = city.name,
                cityZoneId = city.zoneId,
                targetDateIso = targetDate.toString(),
                horizonDays = horizonDays,
                observedMaxC = observedPoint.tempC,
                observedSourceUrl = observedPoint.sourceUrl,
                verifiedAtEpochMs = now.toEpochMilli(),
                entries = entries
            )
            val key = verificationKey(city.id, targetDate.toString(), horizonDays)
            val previous = verificationsByKey[key]
            if (shouldReplaceVerification(previous, candidate)) {
                verificationsByKey[key] = candidate
                changed = true
                observedCache[targetDate] = observedPoint
            }
        }

        if (!changed) {
            return VerifyMutation(dataset = dataset, changed = false, warnings = warnings.distinct())
        }

        return VerifyMutation(
            dataset = dataset.copy(
                verifications = verificationsByKey.values.sortedWith(
                    compareByDescending<PolyTempPremiumVerificationRecord> { it.targetDateIso }
                        .thenBy { it.horizonDays }
                )
            ),
            changed = true,
            warnings = warnings.distinct()
        )
    }

    private suspend fun bootstrapHistoricalCalibration(
        dataset: PolyTempPremiumDataset,
        city: CityConfig,
        now: Instant,
        chunkDays: Long,
        bypassCooldown: Boolean,
        runToCompletion: Boolean
    ): BootstrapMutation {
        val zoneId = ZoneId.of(city.zoneId)
        val cityToday = LocalDate.now(zoneId)
        val bootstrapEnd = cityToday.minusDays(1)
        if (bootstrapEnd.isBefore(BOOTSTRAP_START_H0)) {
            return BootstrapMutation(dataset = dataset, changed = false, warnings = emptyList())
        }

        val nowMs = now.toEpochMilli()
        val warnings = mutableListOf<String>()
        var changed = false

        val verificationsByKey = dataset.verifications
            .associateBy { verificationKey(it.cityId, it.targetDateIso, it.horizonDays) }
            .toMutableMap()

        val cursorsByKey = dataset.bootstrapCursors
            .associateBy { cursorKey(it.cityId, it.horizonDays) }
            .toMutableMap()

        val observedCache = observedSeriesFromVerifications(dataset, city).toMutableMap()

        for (horizonDays in BOOTSTRAP_HORIZONS) {
            val key = cursorKey(city.id, horizonDays)
            val defaultStart = defaultBootstrapStartForHorizon(horizonDays)
            var cursor = cursorsByKey[key] ?: PolyTempPremiumBootstrapCursor(
                cityId = city.id,
                horizonDays = horizonDays,
                nextDateIso = defaultStart.toString(),
                completed = false
            )

            if (!bypassCooldown && cursor.lastAttemptEpochMs > 0L && (nowMs - cursor.lastAttemptEpochMs) < BOOTSTRAP_MIN_INTERVAL_MS) {
                cursorsByKey[key] = cursor
                continue
            }

            var startDate = cursor.nextDateIso.toLocalDateOrNull() ?: defaultStart
            var chunksProcessed = 0
            while (true) {
                if (startDate.isAfter(bootstrapEnd)) {
                    val doneCursor = cursor.copy(
                        nextDateIso = startDate.toString(),
                        completed = true,
                        lastAttemptEpochMs = nowMs,
                        lastSuccessEpochMs = maxOf(cursor.lastSuccessEpochMs, nowMs),
                        lastError = null
                    )
                    if (doneCursor != cursorsByKey[key]) {
                        cursorsByKey[key] = doneCursor
                        changed = true
                    }
                    break
                }

                val chunkEnd = minOf(startDate.plusDays(chunkDays - 1), bootstrapEnd)
                val forecasts = openMeteoHistoricalSource.fetchForecastDailyMaxBatch(
                    city = city,
                    startDate = startDate,
                    endDate = chunkEnd,
                    models = CALIBRATION_MODELS,
                    horizonDays = horizonDays
                )
                warnings += forecasts.warnings.map { warning -> "${city.name} H$horizonDays: $warning" }

                val observedLoad = resolveObservedSeriesForRange(
                    city = city,
                    startDate = startDate,
                    endDate = chunkEnd,
                    cityToday = cityToday,
                    observedCache = observedCache
                )
                warnings += observedLoad.warnings
                observedCache.putAll(observedLoad.valuesByDate)

                var inserted = 0
                var date = startDate
                while (!date.isAfter(chunkEnd)) {
                    val observed = observedLoad.valuesByDate[date] ?: observedCache[date]
                    if (observed != null && observed.tempC in VALID_TEMP_RANGE_C) {
                        val entries = CALIBRATION_MODELS.map { model ->
                            val forecastMax = forecasts.valuesByProvider[model.providerId]?.get(date)
                            val validForecast = forecastMax != null && forecastMax in VALID_TEMP_RANGE_C
                            val absError = if (validForecast) abs(forecastMax!! - observed.tempC) else null
                            val squared = absError?.let { it * it }
                            PolyTempPremiumVerificationEntry(
                                providerId = model.providerId,
                                providerName = model.providerName,
                                capturedEpochMs = nowMs,
                                status = if (validForecast) SourceStatus.SUCCESS.name else SourceStatus.SKIPPED.name,
                                forecastMaxC = forecastMax,
                                absoluteErrorC = absError,
                                squaredErrorC = squared,
                                errorMessage = if (validForecast) null else "Sin forecast histórico"
                            )
                        }

                        if (entries.any { it.absoluteErrorC != null }) {
                            val candidate = PolyTempPremiumVerificationRecord(
                                cityId = city.id,
                                cityName = city.name,
                                cityZoneId = city.zoneId,
                                targetDateIso = date.toString(),
                                horizonDays = horizonDays,
                                observedMaxC = observed.tempC,
                                observedSourceUrl = observed.sourceUrl,
                                verifiedAtEpochMs = nowMs,
                                entries = entries
                            )
                            val verificationKey = verificationKey(city.id, date.toString(), horizonDays)
                            val previous = verificationsByKey[verificationKey]
                            if (shouldReplaceVerification(previous, candidate)) {
                                verificationsByKey[verificationKey] = candidate
                                inserted += 1
                                changed = true
                            }
                        }
                    }
                    date = date.plusDays(1)
                }

                val dataAvailable = forecasts.valuesByProvider.values.any { it.isNotEmpty() }
                val nextDate = if (dataAvailable) chunkEnd.plusDays(1) else startDate
                val nextCursor = cursor.copy(
                    nextDateIso = nextDate.toString(),
                    completed = nextDate.isAfter(bootstrapEnd),
                    lastAttemptEpochMs = nowMs,
                    lastSuccessEpochMs = if (inserted > 0) nowMs else cursor.lastSuccessEpochMs,
                    lastError = when {
                        !dataAvailable -> "Sin forecasts históricos en tramo"
                        inserted > 0 -> null
                        else -> "Sin verificaciones nuevas en tramo"
                    }
                )
                if (nextCursor != cursorsByKey[key]) {
                    cursorsByKey[key] = nextCursor
                    changed = true
                }
                cursor = nextCursor

                if (!runToCompletion || !dataAvailable || nextDate.isAfter(bootstrapEnd)) {
                    break
                }
                chunksProcessed += 1
                if (chunksProcessed >= MAX_FULL_BOOTSTRAP_CHUNKS_PER_HORIZON) {
                    warnings += "${city.name} H$horizonDays: límite de chunks alcanzado en refresh completo"
                    break
                }
                startDate = nextDate
            }
        }

        if (!changed) {
            return BootstrapMutation(
                dataset = dataset,
                changed = false,
                warnings = warnings.distinct()
            )
        }

        val updatedDataset = dataset.copy(
            verifications = verificationsByKey.values.sortedWith(
                compareByDescending<PolyTempPremiumVerificationRecord> { it.targetDateIso }
                    .thenBy { it.horizonDays }
            ),
            bootstrapCursors = cursorsByKey.values.sortedWith(
                compareBy<PolyTempPremiumBootstrapCursor> { it.cityId }
                    .thenBy { it.horizonDays }
            )
        )

        return BootstrapMutation(
            dataset = updatedDataset,
            changed = true,
            warnings = warnings.distinct()
        )
    }

    private suspend fun resolveObservedSeriesForRange(
        city: CityConfig,
        startDate: LocalDate,
        endDate: LocalDate,
        cityToday: LocalDate,
        observedCache: Map<LocalDate, ObservedPoint>
    ): ObservedSeriesLoad {
        val warnings = mutableListOf<String>()
        val values = observedCache
            .filterKeys { date -> !date.isBefore(startDate) && !date.isAfter(endDate) }
            .toMutableMap()

        val recentCutoff = cityToday.minusDays(WUNDERGROUND_LOOKBACK_DAYS)
        var wuMissingCount = 0

        var date = startDate
        while (!date.isAfter(endDate)) {
            if (values[date] == null && !date.isBefore(recentCutoff)) {
                val wu = wundergroundSource.fetchDailyMaxForDate(city = city, date = date)
                val tempC = wu.tempC
                if (tempC != null && tempC in VALID_TEMP_RANGE_C) {
                    values[date] = ObservedPoint(
                        tempC = tempC,
                        sourceUrl = wu.sourceUrl
                    )
                } else {
                    wuMissingCount += 1
                }
            }
            date = date.plusDays(1)
        }
        if (wuMissingCount > 0) {
            warnings += "${city.name} ${startDate}..${endDate}: Wunderground sin dato en $wuMissingCount dias"
        }

        val missingAfterWu = missingDates(startDate, endDate, values)
        if (missingAfterWu.isNotEmpty()) {
            val noaaSeries = noaaObservedSource.fetchDailyMaxSeries(
                city = city,
                startDate = startDate,
                endDate = endDate
            )
            if (!noaaSeries.warning.isNullOrBlank()) {
                warnings += "${city.name} ${startDate}..${endDate}: NOAA ${noaaSeries.warning}"
            }
            noaaSeries.valuesByDate.forEach { (targetDate, tempC) ->
                if (values[targetDate] == null && tempC in VALID_TEMP_RANGE_C) {
                    values[targetDate] = ObservedPoint(
                        tempC = tempC,
                        sourceUrl = noaaSeries.sourceUrl
                    )
                }
            }
        }

        val missingAfterNoaa = missingDates(startDate, endDate, values)
        if (missingAfterNoaa.isNotEmpty()) {
            val archive = openMeteoHistoricalSource.fetchArchiveObservedSeries(
                city = city,
                startDate = startDate,
                endDate = endDate
            )
            if (!archive.warning.isNullOrBlank()) {
                warnings += "${city.name} ${startDate}..${endDate}: Archive ${archive.warning}"
            }
            archive.valuesByDate.forEach { (targetDate, tempC) ->
                if (values[targetDate] == null && tempC in VALID_TEMP_RANGE_C) {
                    values[targetDate] = ObservedPoint(
                        tempC = tempC,
                        sourceUrl = archive.sourceUrl
                    )
                }
            }
        }

        return ObservedSeriesLoad(
            valuesByDate = values,
            warnings = warnings.distinct()
        )
    }

    private suspend fun resolveObservedForDate(
        city: CityConfig,
        date: LocalDate,
        observedCache: MutableMap<LocalDate, ObservedPoint>
    ): ObservedLookup {
        observedCache[date]?.let { cached ->
            return ObservedLookup(point = cached, warnings = emptyList())
        }

        val warnings = mutableListOf<String>()

        val wu = wundergroundSource.fetchDailyMaxForDate(city = city, date = date)
        val wuTemp = wu.tempC
        if (wuTemp != null && wuTemp in VALID_TEMP_RANGE_C) {
            val point = ObservedPoint(tempC = wuTemp, sourceUrl = wu.sourceUrl)
            observedCache[date] = point
            return ObservedLookup(point = point, warnings = emptyList())
        }
        if (!wu.error.isNullOrBlank()) {
            warnings += "${city.name} $date: Wunderground ${wu.error}"
        }

        val noaa = noaaObservedSource.fetchDailyMaxSeries(city = city, startDate = date, endDate = date)
        noaa.valuesByDate[date]?.takeIf { it in VALID_TEMP_RANGE_C }?.let { value ->
            val point = ObservedPoint(tempC = value, sourceUrl = noaa.sourceUrl)
            observedCache[date] = point
            return ObservedLookup(point = point, warnings = warnings)
        }
        if (!noaa.warning.isNullOrBlank()) {
            warnings += "${city.name} $date: NOAA ${noaa.warning}"
        }

        val archive = openMeteoHistoricalSource.fetchArchiveObservedSeries(
            city = city,
            startDate = date,
            endDate = date
        )
        archive.valuesByDate[date]?.takeIf { it in VALID_TEMP_RANGE_C }?.let { value ->
            val point = ObservedPoint(tempC = value, sourceUrl = archive.sourceUrl)
            observedCache[date] = point
            return ObservedLookup(point = point, warnings = warnings)
        }
        if (!archive.warning.isNullOrBlank()) {
            warnings += "${city.name} $date: Archive ${archive.warning}"
        }

        return ObservedLookup(point = null, warnings = warnings.distinct())
    }

    private fun missingDates(
        startDate: LocalDate,
        endDate: LocalDate,
        values: Map<LocalDate, ObservedPoint>
    ): List<LocalDate> {
        val missing = mutableListOf<LocalDate>()
        var date = startDate
        while (!date.isAfter(endDate)) {
            if (values[date] == null) missing += date
            date = date.plusDays(1)
        }
        return missing
    }

    private fun observedSeriesFromVerifications(
        dataset: PolyTempPremiumDataset,
        city: CityConfig
    ): Map<LocalDate, ObservedPoint> {
        return dataset.verifications
            .asSequence()
            .filter { verification -> verification.cityId == city.id }
            .mapNotNull { verification ->
                val date = verification.targetDateIso.toLocalDateOrNull() ?: return@mapNotNull null
                if (verification.observedMaxC !in VALID_TEMP_RANGE_C) return@mapNotNull null
                date to ObservedPoint(
                    tempC = verification.observedMaxC,
                    sourceUrl = verification.observedSourceUrl
                )
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, points) ->
                points.maxByOrNull { point -> sourcePriority(point.sourceUrl) } ?: points.first()
            }
    }

    private fun shouldReplaceVerification(
        existing: PolyTempPremiumVerificationRecord?,
        candidate: PolyTempPremiumVerificationRecord
    ): Boolean {
        if (existing == null) return true

        val existingPriority = sourcePriority(existing.observedSourceUrl)
        val candidatePriority = sourcePriority(candidate.observedSourceUrl)
        if (candidatePriority != existingPriority) {
            return candidatePriority > existingPriority
        }

        val existingValid = existing.entries.count { it.absoluteErrorC != null }
        val candidateValid = candidate.entries.count { it.absoluteErrorC != null }
        if (candidateValid != existingValid) {
            return candidateValid > existingValid
        }

        return candidate.verifiedAtEpochMs >= existing.verifiedAtEpochMs
    }

    private fun sourcePriority(sourceUrl: String?): Int {
        val normalized = sourceUrl?.lowercase(Locale.US).orEmpty()
        return when {
            normalized.contains("wunderground") -> 3
            normalized.contains("ncdc.noaa.gov") || normalized.contains("noaa.gov") -> 2
            normalized.contains("open-meteo") -> 1
            else -> 0
        }
    }

    private fun resolveWeightsForHorizon(
        dataset: PolyTempPremiumDataset,
        city: CityConfig,
        horizonDays: Int,
        providerIds: Set<String>,
        cityToday: LocalDate
    ): HorizonWeightResolution {
        if (providerIds.isEmpty()) {
            return HorizonWeightResolution(
                weightsByProviderId = emptyMap(),
                verifiedDays = 0,
                lastVerifiedDate = null,
                warnings = emptyList()
            )
        }

        val horizonVerifications = dataset.verifications
            .filter { it.cityId == city.id && it.horizonDays == horizonDays }
            .sortedByDescending { it.targetDateIso }

        val horizonSnapshots = dataset.snapshots
            .filter { it.cityId == city.id && it.horizonDays == horizonDays }

        val ranking = buildProviderRanking(
            providerIds = providerIds,
            verifications = horizonVerifications,
            cityToday = cityToday,
            snapshots = horizonSnapshots
        )

        val primaryMultipliers = ranking.associate { stat -> stat.providerId to stat.multiplier }
        val primaryVerifiedDays = horizonVerifications.size
        val primaryBlendConfidence = confidenceWeight(
            days = primaryVerifiedDays,
            fullConfidenceDays = PRIMARY_HORIZON_FULL_CONFIDENCE_DAYS
        )

        val crossHorizonSources = BOOTSTRAP_HORIZONS
            .asSequence()
            .filter { candidate -> candidate != horizonDays }
            .mapNotNull { candidate ->
                val candidateVerifications = dataset.verifications
                    .filter { it.cityId == city.id && it.horizonDays == candidate }
                    .sortedByDescending { it.targetDateIso }
                if (candidateVerifications.isEmpty()) return@mapNotNull null

                val candidateSnapshots = dataset.snapshots
                    .filter { it.cityId == city.id && it.horizonDays == candidate }
                val candidateRanking = buildProviderRanking(
                    providerIds = providerIds,
                    verifications = candidateVerifications,
                    cityToday = cityToday,
                    snapshots = candidateSnapshots
                )
                if (candidateRanking.isEmpty()) return@mapNotNull null

                HorizonBlendSource(
                    horizonDays = candidate,
                    verifiedDays = candidateVerifications.size,
                    multipliersByProvider = candidateRanking.associate { stat ->
                        stat.providerId to stat.multiplier
                    }
                )
            }
            .toList()

        val dynamicWeights = providerIds.associateWith { providerId ->
            val baseWeight = ForecastWeights.baseWeightFor(providerId)
            val primaryMultiplier = primaryMultipliers[providerId] ?: NO_DATA_MULTIPLIER
            val blendedMultiplier = blendMultiplierWithCrossHorizon(
                providerId = providerId,
                horizonDays = horizonDays,
                primaryMultiplier = primaryMultiplier,
                primaryBlendConfidence = primaryBlendConfidence,
                crossHorizonSources = crossHorizonSources
            )
            baseWeight * blendedMultiplier
        }
        val weightsByProvider = providerIds.associateWith { providerId ->
            dynamicWeights[providerId] ?: ForecastWeights.baseWeightFor(providerId)
        }

        val lastVerifiedDate = horizonVerifications
            .asSequence()
            .mapNotNull { it.targetDateIso.toLocalDateOrNull() }
            .maxOrNull()

        val warnings = buildList {
            if (horizonVerifications.size < MIN_VERIFIED_DAYS_FOR_STABLE_WEIGHTS) {
                add("MMA H$horizonDays: muestra corta (${horizonVerifications.size} días)")
            }
            if (
                crossHorizonSources.isNotEmpty() &&
                primaryBlendConfidence < 1.0 &&
                horizonVerifications.size < CROSS_HORIZON_BLEND_WARNING_THRESHOLD_DAYS
            ) {
                val sourceText = crossHorizonSources
                    .sortedByDescending { source -> source.verifiedDays }
                    .joinToString(", ") { source ->
                        "H${source.horizonDays}:${source.verifiedDays}d"
                    }
                add(
                    "MMA H$horizonDays: transferencia entre horizontes activa " +
                        "(base H$horizonDays=${horizonVerifications.size}d, apoyo $sourceText)"
                )
            }
        }

        return HorizonWeightResolution(
            weightsByProviderId = weightsByProvider,
            verifiedDays = horizonVerifications.size,
            lastVerifiedDate = lastVerifiedDate,
            warnings = warnings
        )
    }

    private fun blendMultiplierWithCrossHorizon(
        providerId: String,
        horizonDays: Int,
        primaryMultiplier: Double,
        primaryBlendConfidence: Double,
        crossHorizonSources: List<HorizonBlendSource>
    ): Double {
        if (crossHorizonSources.isEmpty() || primaryBlendConfidence >= 1.0) {
            return primaryMultiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        }

        var weightedSum = 0.0
        var totalWeight = 0.0
        for (source in crossHorizonSources) {
            val sourceMultiplier = source.multipliersByProvider[providerId] ?: continue
            val distance = abs(source.horizonDays - horizonDays)
            val proximityWeight = when (distance) {
                1 -> CROSS_HORIZON_DISTANCE_ONE_WEIGHT
                2 -> CROSS_HORIZON_DISTANCE_TWO_WEIGHT
                else -> CROSS_HORIZON_DISTANCE_OTHER_WEIGHT
            }
            val sampleConfidence = confidenceWeight(
                days = source.verifiedDays,
                fullConfidenceDays = CROSS_HORIZON_FULL_CONFIDENCE_DAYS
            )
            val weight = proximityWeight * sampleConfidence
            if (weight <= 0.0) continue
            weightedSum += sourceMultiplier * weight
            totalWeight += weight
        }

        if (totalWeight <= 0.0) {
            return primaryMultiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        }

        val fallbackMultiplier = weightedSum / totalWeight
        val primaryWeight = maxOf(primaryBlendConfidence, CROSS_HORIZON_MIN_PRIMARY_BLEND)
        val blended = primaryMultiplier * primaryWeight + fallbackMultiplier * (1.0 - primaryWeight)
        return blended.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    private fun pruneDataset(dataset: PolyTempPremiumDataset): DatasetMutation {
        val snapshots = dataset.snapshots.filter { record ->
            withinRetention(
                dateIso = record.targetDateIso,
                zoneId = record.cityZoneId,
                retentionDays = SNAPSHOT_RETENTION_DAYS
            )
        }

        val verifications = dataset.verifications.filter { record ->
            withinRetention(
                dateIso = record.targetDateIso,
                zoneId = record.cityZoneId,
                retentionDays = VERIFICATION_RETENTION_DAYS
            )
        }

        val validCityIds = CityCatalog.cities.map { it.id }.toSet()
        val cursors = dataset.bootstrapCursors.filter { cursor ->
            validCityIds.contains(cursor.cityId)
        }

        val changed = snapshots.size != dataset.snapshots.size ||
            verifications.size != dataset.verifications.size ||
            cursors.size != dataset.bootstrapCursors.size

        if (!changed) return DatasetMutation(dataset, changed = false)

        return DatasetMutation(
            dataset = dataset.copy(
                snapshots = snapshots,
                verifications = verifications,
                bootstrapCursors = cursors
            ),
            changed = true
        )
    }

    private fun sanitizeDeprecatedProviders(dataset: PolyTempPremiumDataset): DatasetMutation {
        val snapshots = dataset.snapshots.filterNot { snapshot ->
            ForecastWeights.isDeprecatedProvider(snapshot.providerId, snapshot.providerName)
        }

        val verifications = dataset.verifications.mapNotNull { verification ->
            val entries = verification.entries.filterNot { entry ->
                ForecastWeights.isDeprecatedProvider(entry.providerId, entry.providerName)
            }
            if (entries.isEmpty()) {
                null
            } else if (entries.size == verification.entries.size) {
                verification
            } else {
                verification.copy(entries = entries)
            }
        }

        val sanitized = dataset.copy(
            snapshots = snapshots,
            verifications = verifications
        )
        if (sanitized == dataset) return DatasetMutation(dataset, changed = false)
        return DatasetMutation(sanitized, changed = true)
    }

    private fun buildReport(
        dataset: PolyTempPremiumDataset,
        city: CityConfig,
        now: Instant,
        extraWarnings: List<String>
    ): PolyTempPremiumReport {
        val zoneId = ZoneId.of(city.zoneId)
        val cityToday = LocalDate.now(zoneId)

        val citySnapshots = dataset.snapshots.filter {
            it.cityId == city.id && it.horizonDays == REPORT_HORIZON_DAYS
        }
        val cityVerifications = dataset.verifications
            .filter { it.cityId == city.id && it.horizonDays == REPORT_HORIZON_DAYS }
            .sortedByDescending { it.targetDateIso }

        val snapshotDatesBeforeToday = citySnapshots
            .mapNotNull { it.targetDateIso.toLocalDateOrNull() }
            .filter { it.isBefore(cityToday) }
            .toSet()

        val verifiedDates = cityVerifications
            .mapNotNull { it.targetDateIso.toLocalDateOrNull() }
            .toSet()

        val pendingDays = snapshotDatesBeforeToday.count { !verifiedDates.contains(it) }

        val providerIds = buildSet {
            citySnapshots.forEach { add(it.providerId) }
            cityVerifications.forEach { verification ->
                verification.entries.forEach { entry -> add(entry.providerId) }
            }
        }

        val lastVerifiedDate = cityVerifications
            .asSequence()
            .mapNotNull { it.targetDateIso.toLocalDateOrNull() }
            .maxOrNull()

        val rankingToday = buildProviderRanking(
            providerIds = providerIds,
            verifications = cityVerifications,
            cityToday = cityToday,
            snapshots = citySnapshots
        )

        val previousWeightsByProvider = if (lastVerifiedDate == null) {
            emptyMap()
        } else {
            val previousVerifications = cityVerifications.filter { verification ->
                val date = verification.targetDateIso.toLocalDateOrNull() ?: return@filter false
                date.isBefore(lastVerifiedDate)
            }
            buildProviderRanking(
                providerIds = providerIds,
                verifications = previousVerifications,
                cityToday = cityToday,
                snapshots = citySnapshots
            ).associate { stat ->
                stat.providerId to stat.dynamicWeight
            }
        }

        val ranking = rankingToday.map { stat ->
            stat.copy(previousDynamicWeight = previousWeightsByProvider[stat.providerId])
        }

        val dayVerifications = cityVerifications.mapNotNull { verification ->
            val targetDate = verification.targetDateIso.toLocalDateOrNull() ?: return@mapNotNull null
            PolyTempPremiumVerificationDay(
                targetDate = targetDate,
                observedMaxC = verification.observedMaxC,
                observedSourceUrl = verification.observedSourceUrl,
                verifiedAt = Instant.ofEpochMilli(verification.verifiedAtEpochMs),
                providers = verification.entries
                    .sortedBy { it.providerName }
                    .map { entry ->
                        PolyTempPremiumProviderDay(
                            providerId = entry.providerId,
                            providerName = entry.providerName,
                            capturedAt = Instant.ofEpochMilli(entry.capturedEpochMs),
                            forecastMaxC = entry.forecastMaxC,
                            absoluteErrorC = entry.absoluteErrorC,
                            status = parseStatus(entry.status),
                            errorMessage = entry.errorMessage
                        )
                    }
            )
        }

        val dynamicWeights = ranking.associate { it.providerId to it.dynamicWeight }
        val bootstrapProgress = buildBootstrapProgress(
            dataset = dataset,
            city = city,
            cityToday = cityToday
        )

        return PolyTempPremiumReport(
            cityId = city.id,
            cityName = city.name,
            generatedAt = now,
            lastVerifiedDate = lastVerifiedDate,
            verifiedDays = cityVerifications.size,
            pendingDays = pendingDays,
            providerRanking = ranking,
            dynamicWeights = dynamicWeights,
            bootstrapProgress = bootstrapProgress,
            dayVerifications = dayVerifications,
            warnings = extraWarnings.distinct()
        )
    }

    private fun buildBootstrapProgress(
        dataset: PolyTempPremiumDataset,
        city: CityConfig,
        cityToday: LocalDate
    ): List<PolyTempPremiumBootstrapProgress> {
        val endDate = cityToday.minusDays(1)
        return BOOTSTRAP_HORIZONS.map { horizonDays ->
            val startDate = defaultBootstrapStartForHorizon(horizonDays)
            val totalDays = if (endDate.isBefore(startDate)) {
                0
            } else {
                (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt().coerceAtLeast(0)
            }
            val cursor = dataset.bootstrapCursors.firstOrNull { item ->
                item.cityId == city.id && item.horizonDays == horizonDays
            }
            val nextDate = cursor?.nextDateIso?.toLocalDateOrNull() ?: startDate
            val processedRaw = (ChronoUnit.DAYS.between(startDate, nextDate)).toInt()
            val processedDays = when {
                totalDays <= 0 -> 0
                cursor?.completed == true -> totalDays
                else -> processedRaw.coerceIn(0, totalDays)
            }
            PolyTempPremiumBootstrapProgress(
                horizonDays = horizonDays,
                startDate = startDate,
                endDate = maxOf(endDate, startDate),
                nextDate = nextDate,
                processedDays = processedDays,
                totalDays = totalDays,
                completed = cursor?.completed == true || (totalDays > 0 && processedDays >= totalDays),
                lastError = cursor?.lastError
            )
        }
    }

    private fun buildProviderRanking(
        providerIds: Set<String>,
        verifications: List<PolyTempPremiumVerificationRecord>,
        cityToday: LocalDate,
        snapshots: List<PolyTempPremiumSnapshotRecord>
    ): List<PolyTempPremiumModelStat> {
        if (providerIds.isEmpty()) return emptyList()

        val totalVerifiedDays = verifications.size

        data class Intermediate(
            val providerId: String,
            val providerName: String,
            val baseWeight: Double,
            val verifiedDays: Int,
            val validForecastDays: Int,
            val coverage: Double?,
            val maeC: Double?,
            val rmseC: Double?,
            val withinOneC: Double?,
            val rawScore: Double
        )

        val intermediate = providerIds.map { providerId ->
            val providerName = resolveProviderName(
                providerId = providerId,
                verifications = verifications,
                snapshots = snapshots
            )
            val baseWeight = ForecastWeights.baseWeightFor(providerId)

            val errors = mutableListOf<ErrorSample>()
            var withinOne = 0
            val recentCutoff = cityToday.minusDays(RECENT_WINDOW_DAYS)
            val targetMonth = cityToday.monthValue

            for (verification in verifications) {
                val targetDate = verification.targetDateIso.toLocalDateOrNull() ?: continue
                val entry = verification.entries.firstOrNull { it.providerId == providerId } ?: continue
                val absError = entry.absoluteErrorC
                val squaredError = entry.squaredErrorC
                if (absError == null || squaredError == null) continue

                errors += ErrorSample(
                    targetDate = targetDate,
                    absoluteErrorC = absError,
                    squaredErrorC = squaredError
                )
                if (absError <= 1.0) withinOne += 1
            }

            val validDays = errors.size
            val recencyMetrics = aggregateRecencyErrors(errors, cityToday)
            val seasonalMetrics = aggregateUnweightedErrors(
                errors.filter { sample ->
                    monthDistance(sample.targetDate.monthValue, targetMonth) <= SEASONAL_MONTH_DISTANCE
                }
            )
            val longMetrics = aggregateUnweightedErrors(errors)

            val recentDays = errors.count { sample -> !sample.targetDate.isBefore(recentCutoff) }
            val seasonalDays = seasonalMetrics.count
            val longDays = longMetrics.count

            val mae = blendMetric(
                recencyMetric = recencyMetrics.mae,
                recencyDays = recentDays,
                seasonalMetric = seasonalMetrics.mae,
                seasonalDays = seasonalDays,
                longMetric = longMetrics.mae,
                longDays = longDays
            )
            val rmse = blendMetric(
                recencyMetric = recencyMetrics.rmse,
                recencyDays = recentDays,
                seasonalMetric = seasonalMetrics.rmse,
                seasonalDays = seasonalDays,
                longMetric = longMetrics.rmse,
                longDays = longDays
            )
            val coverage = if (totalVerifiedDays > 0) validDays.toDouble() / totalVerifiedDays else null
            val withinOneRate = if (validDays > 0) withinOne.toDouble() / validDays else null

            val reliabilityRecent = confidenceWeight(recentDays, RECENT_DAYS_FOR_FULL_CONFIDENCE)
            val reliabilitySeasonal = confidenceWeight(seasonalDays, SEASONAL_DAYS_FOR_FULL_CONFIDENCE)
            val reliabilityLong = confidenceWeight(longDays, LONG_DAYS_FOR_FULL_CONFIDENCE)
            val reliability = (
                reliabilityRecent * 0.45 +
                    reliabilitySeasonal * 0.30 +
                    reliabilityLong * 0.25
                ).coerceIn(0.0, 1.0)

            val accuracy = mae?.let { 1.0 / (1.0 + it / ACCURACY_SCALE_C) } ?: 0.0
            val coverageAdj = (coverage ?: 0.0).coerceIn(0.0, 1.0)
            val spreadPenalty = if (mae != null && rmse != null) {
                (rmse - mae).coerceAtLeast(0.0)
            } else {
                0.0
            }
            val stability = (1.0 / (1.0 + spreadPenalty / RMSE_SPREAD_SCALE_C)).coerceIn(0.70, 1.0)

            val score = if (validDays == 0) {
                0.0
            } else {
                accuracy * stability * (0.45 + 0.55 * coverageAdj) * (0.40 + 0.60 * reliability)
            }

            Intermediate(
                providerId = providerId,
                providerName = providerName,
                baseWeight = baseWeight,
                verifiedDays = totalVerifiedDays,
                validForecastDays = validDays,
                coverage = coverage,
                maeC = mae,
                rmseC = rmse,
                withinOneC = withinOneRate,
                rawScore = score
            )
        }

        val meanPositiveScore = intermediate
            .map { it.rawScore }
            .filter { it > 0.0 }
            .average()
            .takeIf { !it.isNaN() && it > 0.0 }
            ?: 1.0

        return intermediate
            .map { item ->
                val multiplier = if (item.validForecastDays == 0) {
                    NO_DATA_MULTIPLIER
                } else {
                    (item.rawScore / meanPositiveScore).coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
                }
                val dynamicWeight = item.baseWeight * multiplier

                PolyTempPremiumModelStat(
                    providerId = item.providerId,
                    providerName = item.providerName,
                    baseWeight = item.baseWeight,
                    dynamicWeight = dynamicWeight,
                    previousDynamicWeight = null,
                    multiplier = multiplier,
                    verifiedDays = item.verifiedDays,
                    validForecastDays = item.validForecastDays,
                    coverage = item.coverage,
                    meanAbsoluteErrorC = item.maeC,
                    rmseC = item.rmseC,
                    withinOneDegreeRate = item.withinOneC,
                    score = if (item.rawScore > 0.0) item.rawScore else null
                )
            }
            .sortedWith(
                compareByDescending<PolyTempPremiumModelStat> { it.dynamicWeight }
                    .thenBy { it.meanAbsoluteErrorC ?: Double.MAX_VALUE }
                    .thenByDescending { it.coverage ?: 0.0 }
                    .thenBy { it.providerName }
            )
    }

    private fun aggregateRecencyErrors(
        errors: List<ErrorSample>,
        cityToday: LocalDate
    ): ErrorMetrics {
        if (errors.isEmpty()) return ErrorMetrics.empty()

        var weightedAbsError = 0.0
        var weightedSquaredError = 0.0
        var weightSum = 0.0
        for (sample in errors) {
            val ageDays = ChronoUnit.DAYS.between(sample.targetDate, cityToday).coerceAtLeast(0)
            val recencyWeight = exp(-ageDays / RECENCY_HALF_LIFE_DAYS)
            weightedAbsError += sample.absoluteErrorC * recencyWeight
            weightedSquaredError += sample.squaredErrorC * recencyWeight
            weightSum += recencyWeight
        }

        if (weightSum <= 0.0) return ErrorMetrics.empty()
        return ErrorMetrics(
            mae = weightedAbsError / weightSum,
            rmse = sqrt(weightedSquaredError / weightSum),
            count = errors.size
        )
    }

    private fun aggregateUnweightedErrors(errors: List<ErrorSample>): ErrorMetrics {
        if (errors.isEmpty()) return ErrorMetrics.empty()
        val mae = errors.map { sample -> sample.absoluteErrorC }.average().takeIf { !it.isNaN() }
        val rmse = errors.map { sample -> sample.squaredErrorC }
            .average()
            .takeIf { !it.isNaN() }
            ?.let { value -> sqrt(value) }
        return ErrorMetrics(
            mae = mae,
            rmse = rmse,
            count = errors.size
        )
    }

    private fun blendMetric(
        recencyMetric: Double?,
        recencyDays: Int,
        seasonalMetric: Double?,
        seasonalDays: Int,
        longMetric: Double?,
        longDays: Int
    ): Double? {
        val weightedComponents = mutableListOf<Pair<Double, Double>>()

        addMetricComponent(
            weightedComponents = weightedComponents,
            metric = recencyMetric,
            days = recencyDays,
            baseWeight = BLEND_WEIGHT_RECENCY,
            fullConfidenceDays = RECENT_DAYS_FOR_FULL_CONFIDENCE
        )
        addMetricComponent(
            weightedComponents = weightedComponents,
            metric = seasonalMetric,
            days = seasonalDays,
            baseWeight = BLEND_WEIGHT_SEASONAL,
            fullConfidenceDays = SEASONAL_DAYS_FOR_FULL_CONFIDENCE
        )
        addMetricComponent(
            weightedComponents = weightedComponents,
            metric = longMetric,
            days = longDays,
            baseWeight = BLEND_WEIGHT_LONG,
            fullConfidenceDays = LONG_DAYS_FOR_FULL_CONFIDENCE
        )

        val denominator = weightedComponents.sumOf { (_, weight) -> weight }
        if (denominator <= 0.0) return null
        return weightedComponents.sumOf { (metric, weight) -> metric * weight } / denominator
    }

    private fun addMetricComponent(
        weightedComponents: MutableList<Pair<Double, Double>>,
        metric: Double?,
        days: Int,
        baseWeight: Double,
        fullConfidenceDays: Double
    ) {
        if (metric == null || days <= 0) return
        val confidence = confidenceWeight(days, fullConfidenceDays)
        val effectiveWeight = baseWeight * (MIN_BLEND_CONFIDENCE_FACTOR + (1.0 - MIN_BLEND_CONFIDENCE_FACTOR) * confidence)
        weightedComponents += metric to effectiveWeight
    }

    private fun confidenceWeight(days: Int, fullConfidenceDays: Double): Double {
        if (days <= 0 || fullConfidenceDays <= 0.0) return 0.0
        return (days.toDouble() / fullConfidenceDays).coerceIn(0.0, 1.0)
    }

    private fun monthDistance(a: Int, b: Int): Int {
        val diff = abs(a - b)
        return minOf(diff, 12 - diff)
    }

    private fun resolveProviderName(
        providerId: String,
        verifications: List<PolyTempPremiumVerificationRecord>,
        snapshots: List<PolyTempPremiumSnapshotRecord>
    ): String {
        verifications.forEach { record ->
            record.entries.firstOrNull { it.providerId == providerId }?.providerName?.takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        snapshots.firstOrNull { it.providerId == providerId }?.providerName?.takeIf { it.isNotBlank() }?.let {
            return it
        }
        CALIBRATION_MODELS.firstOrNull { it.providerId == providerId }?.let { return it.providerName }
        return providerId
    }

    private fun withinRetention(
        dateIso: String,
        zoneId: String,
        retentionDays: Long
    ): Boolean {
        val date = dateIso.toLocalDateOrNull() ?: return false
        val today = LocalDate.now(runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC")))
        return !date.isBefore(today.minusDays(retentionDays))
    }

    private fun parseStatus(raw: String): SourceStatus {
        return when (raw.uppercase(Locale.US)) {
            SourceStatus.SUCCESS.name -> SourceStatus.SUCCESS
            SourceStatus.SKIPPED.name -> SourceStatus.SKIPPED
            else -> SourceStatus.ERROR
        }
    }

    private fun horizonDays(
        city: CityConfig,
        targetDate: LocalDate,
        now: Instant
    ): Int {
        val today = cityToday(city, now)
        return ChronoUnit.DAYS.between(today, targetDate).toInt().coerceIn(0, MAX_HORIZON_DAYS)
    }

    private fun cityToday(city: CityConfig, now: Instant): LocalDate {
        val zoneId = ZoneId.of(city.zoneId)
        return now.atZone(zoneId).toLocalDate()
    }

    private fun defaultBootstrapStartForHorizon(horizonDays: Int): LocalDate {
        return if (horizonDays == 0) BOOTSTRAP_START_H0 else BOOTSTRAP_START_H1_H2
    }

    private fun snapshotKey(cityId: String, dateIso: String, providerId: String, horizonDays: Int): String {
        return "$cityId|$dateIso|$horizonDays|$providerId"
    }

    private fun verificationKey(cityId: String, dateIso: String, horizonDays: Int): String {
        return "$cityId|$dateIso|$horizonDays"
    }

    private fun cursorKey(cityId: String, horizonDays: Int): String {
        return "$cityId|$horizonDays"
    }

    private fun String.toLocalDateOrNull(): LocalDate? {
        return runCatching { LocalDate.parse(this) }.getOrNull()
    }

    private data class DatasetMutation(
        val dataset: PolyTempPremiumDataset,
        val changed: Boolean
    )

    private data class VerifyMutation(
        val dataset: PolyTempPremiumDataset,
        val changed: Boolean,
        val warnings: List<String>
    )

    private data class BootstrapMutation(
        val dataset: PolyTempPremiumDataset,
        val changed: Boolean,
        val warnings: List<String>
    )

    private data class HorizonWeightResolution(
        val weightsByProviderId: Map<String, Double>,
        val verifiedDays: Int,
        val lastVerifiedDate: LocalDate?,
        val warnings: List<String>
    )

    private data class ObservedPoint(
        val tempC: Double,
        val sourceUrl: String?
    )

    private data class ObservedLookup(
        val point: ObservedPoint?,
        val warnings: List<String>
    )

    private data class ObservedSeriesLoad(
        val valuesByDate: Map<LocalDate, ObservedPoint>,
        val warnings: List<String>
    )

    private data class ErrorSample(
        val targetDate: LocalDate,
        val absoluteErrorC: Double,
        val squaredErrorC: Double
    )

    private data class ErrorMetrics(
        val mae: Double?,
        val rmse: Double?,
        val count: Int
    ) {
        companion object {
            fun empty(): ErrorMetrics = ErrorMetrics(
                mae = null,
                rmse = null,
                count = 0
            )
        }
    }

    private data class HorizonBlendSource(
        val horizonDays: Int,
        val verifiedDays: Int,
        val multipliersByProvider: Map<String, Double>
    )

    companion object {
        private const val MAX_HORIZON_DAYS = 2
        private const val REPORT_HORIZON_DAYS = 0

        private const val SNAPSHOT_RETENTION_DAYS = 140L
        private const val VERIFICATION_RETENTION_DAYS = 3650L

        private const val MAX_VERIFY_DATES_PER_RUN = 4

        private const val BOOTSTRAP_CHUNK_DAYS_LOAD = 10L
        private const val BOOTSTRAP_CHUNK_DAYS_FULL = 120L
        private const val MAX_FULL_BOOTSTRAP_CHUNKS_PER_HORIZON = 60
        private const val BOOTSTRAP_MIN_INTERVAL_MS = 35L * 60L * 1000L

        private const val WUNDERGROUND_LOOKBACK_DAYS = 45L

        private const val MIN_VERIFIED_DAYS_FOR_STABLE_WEIGHTS = 12
        private const val ACCURACY_SCALE_C = 1.8
        private const val RMSE_SPREAD_SCALE_C = 2.4
        private const val RECENCY_HALF_LIFE_DAYS = 18.0

        private const val RECENT_WINDOW_DAYS = 21L
        private const val SEASONAL_MONTH_DISTANCE = 1

        private const val RECENT_DAYS_FOR_FULL_CONFIDENCE = 14.0
        private const val SEASONAL_DAYS_FOR_FULL_CONFIDENCE = 45.0
        private const val LONG_DAYS_FOR_FULL_CONFIDENCE = 180.0
        private const val PRIMARY_HORIZON_FULL_CONFIDENCE_DAYS = 30.0
        private const val CROSS_HORIZON_FULL_CONFIDENCE_DAYS = 90.0
        private const val CROSS_HORIZON_MIN_PRIMARY_BLEND = 0.30
        private const val CROSS_HORIZON_DISTANCE_ONE_WEIGHT = 1.00
        private const val CROSS_HORIZON_DISTANCE_TWO_WEIGHT = 0.72
        private const val CROSS_HORIZON_DISTANCE_OTHER_WEIGHT = 0.50
        private const val CROSS_HORIZON_BLEND_WARNING_THRESHOLD_DAYS = 28

        private const val BLEND_WEIGHT_RECENCY = 0.55
        private const val BLEND_WEIGHT_SEASONAL = 0.30
        private const val BLEND_WEIGHT_LONG = 0.15
        private const val MIN_BLEND_CONFIDENCE_FACTOR = 0.25

        private const val MIN_MULTIPLIER = 0.55
        private const val MAX_MULTIPLIER = 1.65
        private const val NO_DATA_MULTIPLIER = 0.90

        private val VALID_TEMP_RANGE_C = -80.0..65.0

        private val BOOTSTRAP_START_H0 = LocalDate.of(2022, 1, 1)
        private val BOOTSTRAP_START_H1_H2 = LocalDate.of(2024, 1, 1)

        private val BOOTSTRAP_HORIZONS = listOf(0, 1, 2)

        private val CALIBRATION_MODELS: List<OpenMeteoModelSpec> = listOf(
            OpenMeteoModelSpec(
                providerId = "openmeteo-ifs",
                providerName = "Open-Meteo ECMWF IFS",
                modelId = "ecmwf_ifs"
            ),
            OpenMeteoModelSpec(
                providerId = "openmeteo-ifs025",
                providerName = "Open-Meteo ECMWF IFS 0.25",
                modelId = "ecmwf_ifs025"
            ),
            OpenMeteoModelSpec(
                providerId = "openmeteo-aifs",
                providerName = "Open-Meteo ECMWF AIFS",
                modelId = "ecmwf_aifs025_single"
            )
        )
    }
}
