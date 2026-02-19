package com.polymeteo.meteotrader.data.premium

import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.ForecastSourceResult
import com.polymeteo.meteotrader.data.model.PolyTempPremiumModelStat
import com.polymeteo.meteotrader.data.model.PolyTempPremiumProviderDay
import com.polymeteo.meteotrader.data.model.PolyTempPremiumReport
import com.polymeteo.meteotrader.data.model.PolyTempPremiumVerificationDay
import com.polymeteo.meteotrader.data.model.SourceStatus
import com.polymeteo.meteotrader.data.source.WundergroundSource
import com.polymeteo.meteotrader.data.forecast.ForecastWeights
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
    val warnings: List<String>
)

class PolyTempPremiumEngine(
    private val store: PolyTempPremiumStore,
    private val wundergroundSource: WundergroundSource,
    private val nowProvider: () -> Instant = { Instant.now() }
) {

    suspend fun ingestAndResolveWeights(
        city: CityConfig,
        targetDate: LocalDate,
        forecasts: List<ForecastSourceResult>
    ): PremiumWeightSnapshot = coroutineScope {
        val now = nowProvider()
        var changed = false
        val warnings = mutableListOf<String>()

        var dataset = store.read()

        val upsert = upsertSnapshots(
            dataset = dataset,
            city = city,
            targetDate = targetDate,
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
        warnings += verified.warnings

        val pruned = pruneDataset(dataset = dataset)
        dataset = pruned.dataset
        changed = changed || pruned.changed

        if (changed) {
            store.write(dataset.copy(updatedAtEpochMs = now.toEpochMilli()))
        }

        val report = buildReport(
            dataset = dataset,
            city = city,
            now = now,
            extraWarnings = warnings
        )

        val providerIds = forecasts.map { it.sourceId }.toSet()
        val weights = providerIds.associateWith { providerId ->
            report.dynamicWeights[providerId] ?: ForecastWeights.baseWeightFor(providerId)
        }

        PremiumWeightSnapshot(
            weightsByProviderId = weights,
            verifiedDays = report.verifiedDays,
            lastVerifiedDate = report.lastVerifiedDate,
            warnings = report.warnings
        )
    }

    suspend fun refreshCityReport(city: CityConfig): PolyTempPremiumReport {
        val now = nowProvider()
        var changed = false

        var dataset = store.read()
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

        return buildReport(
            dataset = dataset,
            city = city,
            now = now,
            extraWarnings = verified.warnings
        )
    }

    suspend fun loadCityReport(city: CityConfig): PolyTempPremiumReport {
        val now = nowProvider()
        val dataset = store.read()
        return buildReport(
            dataset = dataset,
            city = city,
            now = now,
            extraWarnings = emptyList()
        )
    }

    private fun upsertSnapshots(
        dataset: PolyTempPremiumDataset,
        city: CityConfig,
        targetDate: LocalDate,
        forecasts: List<ForecastSourceResult>,
        now: Instant
    ): DatasetMutation {
        if (forecasts.isEmpty()) return DatasetMutation(dataset, changed = false)

        val snapshotsByKey = dataset.snapshots.associateBy { snapshotKey(it.cityId, it.targetDateIso, it.providerId) }
            .toMutableMap()
        var changed = false

        for (forecast in forecasts) {
            val sanitizedMax = forecast.maxTempC
                ?.takeIf { forecast.status == SourceStatus.SUCCESS }
                ?.takeIf { it in VALID_TEMP_RANGE_C }

            val next = PolyTempPremiumSnapshotRecord(
                cityId = city.id,
                cityName = city.name,
                cityZoneId = city.zoneId,
                targetDateIso = targetDate.toString(),
                providerId = forecast.sourceId,
                providerName = forecast.sourceName,
                capturedEpochMs = now.toEpochMilli(),
                status = forecast.status.name,
                forecastMaxC = sanitizedMax,
                errorMessage = forecast.error
            )
            val key = snapshotKey(city.id, targetDate.toString(), forecast.sourceId)
            val previous = snapshotsByKey[key]
            if (previous != next) {
                snapshotsByKey[key] = next
                changed = true
            }
        }

        if (!changed) return DatasetMutation(dataset, changed = false)

        return DatasetMutation(
            dataset = dataset.copy(
                snapshots = snapshotsByKey.values.sortedBy { it.capturedEpochMs }
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
        val verifiedDates = dataset.verifications
            .asSequence()
            .filter { it.cityId == city.id }
            .mapNotNull { it.targetDateIso.toLocalDateOrNull() }
            .toSet()

        val pendingDates = snapshots
            .mapNotNull { it.targetDateIso.toLocalDateOrNull() }
            .filter { it.isBefore(cityToday) }
            .distinct()
            .filterNot { verifiedDates.contains(it) }
            .sortedDescending()
            .take(MAX_VERIFY_DATES_PER_RUN)

        if (pendingDates.isEmpty()) {
            return VerifyMutation(dataset = dataset, changed = false, warnings = emptyList())
        }

        val warnings = mutableListOf<String>()
        var changed = false
        val verificationsByKey = dataset.verifications
            .associateBy { verificationKey(it.cityId, it.targetDateIso) }
            .toMutableMap()

        for (targetDate in pendingDates) {
            val observed = wundergroundSource.fetchDailyMaxForDate(city = city, date = targetDate)
            val observedMaxC = observed.tempC
            if (observedMaxC == null || observedMaxC !in VALID_TEMP_RANGE_C) {
                warnings += "${city.name} ${targetDate}: sin observacion valida para verificacion"
                continue
            }

            val daySnapshots = snapshots
                .filter { it.targetDateIso == targetDate.toString() }
                .sortedBy { it.providerName }

            if (daySnapshots.isEmpty()) continue

            val entries = daySnapshots.map { snapshot ->
                val validForecast = snapshot.forecastMaxC != null && snapshot.forecastMaxC in VALID_TEMP_RANGE_C
                val absError = if (validForecast) abs(snapshot.forecastMaxC!! - observedMaxC) else null
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

            val record = PolyTempPremiumVerificationRecord(
                cityId = city.id,
                cityName = city.name,
                cityZoneId = city.zoneId,
                targetDateIso = targetDate.toString(),
                observedMaxC = observedMaxC,
                observedSourceUrl = observed.sourceUrl,
                verifiedAtEpochMs = now.toEpochMilli(),
                entries = entries
            )
            val key = verificationKey(city.id, targetDate.toString())
            val previous = verificationsByKey[key]
            if (previous != record) {
                verificationsByKey[key] = record
                changed = true
            }
        }

        if (!changed) {
            return VerifyMutation(dataset = dataset, changed = false, warnings = warnings)
        }

        return VerifyMutation(
            dataset = dataset.copy(
                verifications = verificationsByKey.values.sortedByDescending { it.targetDateIso }
            ),
            changed = true,
            warnings = warnings
        )
    }

    private fun pruneDataset(dataset: PolyTempPremiumDataset): DatasetMutation {
        val snapshots = dataset.snapshots.filter { record ->
            withinRetention(
                dateIso = record.targetDateIso,
                zoneId = record.cityZoneId
            )
        }
        val verifications = dataset.verifications.filter { record ->
            withinRetention(
                dateIso = record.targetDateIso,
                zoneId = record.cityZoneId
            )
        }
        val changed = snapshots.size != dataset.snapshots.size || verifications.size != dataset.verifications.size
        if (!changed) return DatasetMutation(dataset, changed = false)
        return DatasetMutation(
            dataset = dataset.copy(
                snapshots = snapshots,
                verifications = verifications
            ),
            changed = true
        )
    }

    private fun buildReport(
        dataset: PolyTempPremiumDataset,
        city: CityConfig,
        now: Instant,
        extraWarnings: List<String>
    ): PolyTempPremiumReport {
        val zoneId = ZoneId.of(city.zoneId)
        val cityToday = LocalDate.now(zoneId)

        val citySnapshots = dataset.snapshots.filter { it.cityId == city.id }
        val cityVerifications = dataset.verifications
            .filter { it.cityId == city.id }
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

        val ranking = buildProviderRanking(
            providerIds = providerIds,
            verifications = cityVerifications,
            cityToday = cityToday,
            snapshots = citySnapshots
        )

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

        val lastVerifiedDate = cityVerifications
            .asSequence()
            .mapNotNull { it.targetDateIso.toLocalDateOrNull() }
            .maxOrNull()

        return PolyTempPremiumReport(
            cityId = city.id,
            cityName = city.name,
            generatedAt = now,
            lastVerifiedDate = lastVerifiedDate,
            verifiedDays = cityVerifications.size,
            pendingDays = pendingDays,
            providerRanking = ranking,
            dynamicWeights = dynamicWeights,
            dayVerifications = dayVerifications,
            warnings = extraWarnings.distinct()
        )
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

            var weightedAbsError = 0.0
            var weightedSquaredError = 0.0
            var errorWeightSum = 0.0
            var validDays = 0
            var withinOne = 0

            for (verification in verifications) {
                val targetDate = verification.targetDateIso.toLocalDateOrNull() ?: continue
                val ageDays = ChronoUnit.DAYS.between(targetDate, cityToday).coerceAtLeast(0)
                val recencyWeight = exp(-ageDays / RECENCY_HALF_LIFE_DAYS)

                val entry = verification.entries.firstOrNull { it.providerId == providerId } ?: continue
                val absError = entry.absoluteErrorC
                val squaredError = entry.squaredErrorC
                if (absError == null || squaredError == null) continue

                validDays += 1
                weightedAbsError += absError * recencyWeight
                weightedSquaredError += squaredError * recencyWeight
                errorWeightSum += recencyWeight
                if (absError <= 1.0) withinOne += 1
            }

            val mae = if (errorWeightSum > 0.0) weightedAbsError / errorWeightSum else null
            val rmse = if (errorWeightSum > 0.0) sqrt(weightedSquaredError / errorWeightSum) else null
            val coverage = if (totalVerifiedDays > 0) validDays.toDouble() / totalVerifiedDays else null
            val withinOneRate = if (validDays > 0) withinOne.toDouble() / validDays else null

            val reliability = (validDays.toDouble() / MIN_DAYS_FOR_FULL_CONFIDENCE).coerceIn(0.0, 1.0)
            val accuracy = mae?.let { 1.0 / (1.0 + it / ACCURACY_SCALE_C) } ?: 0.0
            val coverageAdj = (coverage ?: 0.0).coerceIn(0.0, 1.0)

            val score = if (validDays == 0) {
                0.0
            } else {
                accuracy * (0.45 + 0.55 * coverageAdj) * (0.40 + 0.60 * reliability)
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
        return providerId
    }

    private fun withinRetention(
        dateIso: String,
        zoneId: String
    ): Boolean {
        val date = dateIso.toLocalDateOrNull() ?: return false
        val today = LocalDate.now(runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC")))
        return !date.isBefore(today.minusDays(RETENTION_DAYS))
    }

    private fun parseStatus(raw: String): SourceStatus {
        return when (raw.uppercase(Locale.US)) {
            SourceStatus.SUCCESS.name -> SourceStatus.SUCCESS
            SourceStatus.SKIPPED.name -> SourceStatus.SKIPPED
            else -> SourceStatus.ERROR
        }
    }

    private fun snapshotKey(cityId: String, dateIso: String, providerId: String): String {
        return "$cityId|$dateIso|$providerId"
    }

    private fun verificationKey(cityId: String, dateIso: String): String {
        return "$cityId|$dateIso"
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

    companion object {
        private const val RETENTION_DAYS = 75L
        private const val MAX_VERIFY_DATES_PER_RUN = 2
        private const val MIN_DAYS_FOR_FULL_CONFIDENCE = 10.0
        private const val ACCURACY_SCALE_C = 1.8
        private const val RECENCY_HALF_LIFE_DAYS = 18.0
        private const val MIN_MULTIPLIER = 0.55
        private const val MAX_MULTIPLIER = 1.65
        private const val NO_DATA_MULTIPLIER = 0.90
        private val VALID_TEMP_RANGE_C = -80.0..65.0
    }
}
