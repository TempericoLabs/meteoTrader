package com.polymeteo.meteotrader.data.source

internal object TraderCalibration {

    data class Adjustment(
        val sigmaMultiplier: Double,
        val confidenceMultiplier: Double,
        val meanBiasC: Double
    )

    fun resolve(
        cityId: String,
        horizonDays: Int,
        localHour: Int
    ): Adjustment {
        val profile = CITY_PROFILES[cityId] ?: DEFAULT_PROFILE
        val horizonIndex = horizonDays.coerceIn(0, 2)
        val band = LocalTimeBand.fromHour(localHour)
        val bandInfluence = when (horizonIndex) {
            0 -> 1.0
            1 -> 0.45
            else -> 0.25
        }

        val baseSigma = profile.sigmaByHorizon[horizonIndex]
        val baseConfidence = profile.confidenceByHorizon[horizonIndex]
        val baseBias = profile.biasByHorizonC[horizonIndex]

        val sigmaBand = 1.0 + ((SIGMA_BAND_MULTIPLIER[band] ?: 1.0) - 1.0) * bandInfluence
        val confidenceBand = 1.0 + ((CONFIDENCE_BAND_MULTIPLIER[band] ?: 1.0) - 1.0) * bandInfluence

        return Adjustment(
            sigmaMultiplier = (baseSigma * sigmaBand).coerceIn(0.70, 2.20),
            confidenceMultiplier = (baseConfidence * confidenceBand).coerceIn(0.55, 1.25),
            meanBiasC = (baseBias + ((BIAS_BAND_C[band] ?: 0.0) * bandInfluence)).coerceIn(-1.2, 1.2)
        )
    }

    private enum class LocalTimeBand {
        NIGHT,
        MORNING,
        MIDDAY,
        EVENING;

        companion object {
            fun fromHour(hour: Int): LocalTimeBand {
                val normalized = ((hour % 24) + 24) % 24
                return when (normalized) {
                    in 0..5 -> NIGHT
                    in 6..10 -> MORNING
                    in 11..16 -> MIDDAY
                    else -> EVENING
                }
            }
        }
    }

    private data class CityProfile(
        val sigmaByHorizon: DoubleArray,
        val confidenceByHorizon: DoubleArray,
        val biasByHorizonC: DoubleArray
    )

    private fun profile(
        sigmaToday: Double,
        sigmaTomorrow: Double,
        sigmaDay2: Double,
        confidenceToday: Double,
        confidenceTomorrow: Double,
        confidenceDay2: Double,
        biasTodayC: Double = 0.0,
        biasTomorrowC: Double = 0.0,
        biasDay2C: Double = 0.0
    ): CityProfile = CityProfile(
        sigmaByHorizon = doubleArrayOf(sigmaToday, sigmaTomorrow, sigmaDay2),
        confidenceByHorizon = doubleArrayOf(confidenceToday, confidenceTomorrow, confidenceDay2),
        biasByHorizonC = doubleArrayOf(biasTodayC, biasTomorrowC, biasDay2C)
    )

    private val DEFAULT_PROFILE = profile(
        sigmaToday = 1.00,
        sigmaTomorrow = 1.14,
        sigmaDay2 = 1.28,
        confidenceToday = 1.00,
        confidenceTomorrow = 0.94,
        confidenceDay2 = 0.88
    )

    private val CITY_PROFILES = mapOf(
        "miami" to profile(1.08, 1.20, 1.34, 0.98, 0.90, 0.84, 0.20, 0.10, 0.00),
        "london" to profile(0.96, 1.10, 1.24, 1.02, 0.96, 0.90, 0.00, 0.00, 0.00),
        "toronto" to profile(1.00, 1.14, 1.28, 1.00, 0.94, 0.88, 0.00, 0.00, 0.00),
        "seattle" to profile(0.94, 1.08, 1.20, 1.05, 0.98, 0.92, -0.10, -0.10, -0.10),
        "dallas" to profile(1.07, 1.20, 1.35, 0.99, 0.91, 0.84, 0.20, 0.20, 0.10),
        "wellington" to profile(1.09, 1.22, 1.36, 0.96, 0.89, 0.83, -0.10, -0.10, 0.00),
        "ankara" to profile(1.02, 1.16, 1.30, 1.00, 0.93, 0.87, 0.10, 0.10, 0.00),
        "seoul" to profile(1.01, 1.15, 1.29, 1.00, 0.93, 0.87, 0.10, 0.10, 0.00),
        "new-york" to profile(1.04, 1.17, 1.31, 1.00, 0.92, 0.86, 0.10, 0.10, 0.00),
        "chicago" to profile(1.05, 1.18, 1.32, 0.99, 0.91, 0.85, 0.10, 0.10, 0.00),
        "atlanta" to profile(1.06, 1.19, 1.33, 0.99, 0.91, 0.85, 0.20, 0.10, 0.00),
        "paris" to profile(0.97, 1.11, 1.25, 1.01, 0.95, 0.89, 0.00, 0.00, 0.00),
        "buenos-aires" to profile(1.03, 1.16, 1.30, 1.00, 0.93, 0.87, 0.00, 0.00, 0.00),
        "sao-paulo" to profile(1.02, 1.15, 1.29, 1.00, 0.94, 0.88, 0.00, 0.00, 0.00)
    )

    private val SIGMA_BAND_MULTIPLIER = mapOf(
        LocalTimeBand.NIGHT to 1.22,
        LocalTimeBand.MORNING to 1.12,
        LocalTimeBand.MIDDAY to 0.95,
        LocalTimeBand.EVENING to 0.88
    )

    private val CONFIDENCE_BAND_MULTIPLIER = mapOf(
        LocalTimeBand.NIGHT to 0.86,
        LocalTimeBand.MORNING to 0.93,
        LocalTimeBand.MIDDAY to 1.05,
        LocalTimeBand.EVENING to 1.12
    )

    private val BIAS_BAND_C = mapOf(
        LocalTimeBand.NIGHT to -0.05,
        LocalTimeBand.MORNING to 0.00,
        LocalTimeBand.MIDDAY to 0.12,
        LocalTimeBand.EVENING to 0.18
    )
}
