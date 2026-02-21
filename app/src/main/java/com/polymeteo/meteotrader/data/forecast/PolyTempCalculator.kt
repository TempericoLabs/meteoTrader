package com.polymeteo.meteotrader.data.forecast

import com.polymeteo.meteotrader.data.model.ForecastSourceResult
import com.polymeteo.meteotrader.data.model.SourceStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class PolyTempComputation(
    val polyTempC: Double?,
    val validMaxTempsC: List<Double>,
    val warnings: List<String>
)

object PolyTempCalculator {

    fun compute(
        forecasts: List<ForecastSourceResult>,
        dynamicWeights: Map<String, Double>
    ): PolyTempComputation {
        val candidates = forecasts
            .asSequence()
            .filter { it.status == SourceStatus.SUCCESS }
            .mapNotNull { result ->
                val value = result.maxTempC ?: return@mapNotNull null
                if (value !in VALID_TEMP_RANGE_C) return@mapNotNull null
                SourceTemp(result.sourceId, result.sourceName, value)
            }
            .toList()

        if (candidates.isEmpty()) {
            return PolyTempComputation(
                polyTempC = null,
                validMaxTempsC = emptyList(),
                warnings = listOf("MM: sin fuentes válidas")
            )
        }

        val median = median(candidates.map { it.valueC })
        val absDev = candidates.map { abs(it.valueC - median) }
        val mad = median(absDev)
        val outlierTolerance = max(1.8, mad * 3.2)

        val filtered = if (candidates.size <= 2) {
            candidates
        } else {
            candidates.filter { abs(it.valueC - median) <= outlierTolerance }
        }.ifEmpty { candidates }

        val weighted = filtered.weightedAverage(dynamicWeights)
        val spread = filtered.maxOf { it.valueC } - filtered.minOf { it.valueC }
        val warnings = buildList {
            val outliers = candidates.size - filtered.size
            if (outliers > 0) {
                add("MM: descartados $outliers outliers")
            }
            if (filtered.size < MIN_FORECASTS_FOR_HIGH_CONFIDENCE) {
                add("MM: baja confianza (${filtered.size} fuentes)")
            }
            if (spread > 5.5) {
                add("MM: alta dispersión (${String.format(Locale.US, "%.1f", spread)}°C)")
            }
        }

        return PolyTempComputation(
            polyTempC = weighted,
            validMaxTempsC = filtered.map { it.valueC },
            warnings = warnings
        )
    }

    private fun List<SourceTemp>.weightedAverage(dynamicWeights: Map<String, Double>): Double {
        val weightedSamples = map { sample ->
            val weight = dynamicWeights[sample.sourceId]
                ?: ForecastWeights.baseWeightFor(sample.sourceId)
            WeightedSample(sample = sample, weight = weight)
        }

        val totalWeight = weightedSamples.sumOf { it.weight }
        val weightedMean = if (totalWeight <= 0.0) {
            map { it.valueC }.average()
        } else {
            weightedSamples.sumOf { it.sample.valueC * it.weight } / totalWeight
        }

        if (dynamicWeights.isEmpty() || weightedSamples.size < 2 || totalWeight <= 0.0) {
            return weightedMean
        }

        val sortedByWeight = weightedSamples.sortedByDescending { it.weight }
        val top = sortedByWeight.first()
        val second = sortedByWeight.getOrNull(1)
        val topShare = (top.weight / totalWeight).coerceIn(0.0, 1.0)
        val dominanceRatio = if (second == null || second.weight <= 0.0) {
            Double.POSITIVE_INFINITY
        } else {
            top.weight / second.weight
        }

        if (topShare < PREMIUM_DOMINANT_MIN_SHARE && dominanceRatio < PREMIUM_DOMINANT_MIN_RATIO) {
            return weightedMean
        }

        val ratioFactor = if (dominanceRatio.isFinite()) {
            ((dominanceRatio - 1.0) / (PREMIUM_DOMINANT_TARGET_RATIO - 1.0)).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        val shareFactor = (topShare / PREMIUM_DOMINANT_TARGET_SHARE).coerceIn(0.0, 1.0)
        val alpha = (
            PREMIUM_DOMINANT_BASE_ALPHA +
                PREMIUM_DOMINANT_EXTRA_ALPHA * max(ratioFactor, shareFactor)
            ).coerceIn(PREMIUM_DOMINANT_BASE_ALPHA, PREMIUM_DOMINANT_MAX_ALPHA)

        return weightedMean * (1.0 - alpha) + top.sample.valueC * alpha
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private data class SourceTemp(
        val sourceId: String,
        val sourceName: String,
        val valueC: Double
    )

    private data class WeightedSample(
        val sample: SourceTemp,
        val weight: Double
    )

    private const val MIN_FORECASTS_FOR_HIGH_CONFIDENCE = 3
    private const val PREMIUM_DOMINANT_MIN_SHARE = 0.22
    private const val PREMIUM_DOMINANT_TARGET_SHARE = 0.35
    private const val PREMIUM_DOMINANT_MIN_RATIO = 1.03
    private const val PREMIUM_DOMINANT_TARGET_RATIO = 1.20
    private const val PREMIUM_DOMINANT_BASE_ALPHA = 0.45
    private const val PREMIUM_DOMINANT_EXTRA_ALPHA = 0.30
    private const val PREMIUM_DOMINANT_MAX_ALPHA = 0.75
    private val VALID_TEMP_RANGE_C = -80.0..65.0
}
