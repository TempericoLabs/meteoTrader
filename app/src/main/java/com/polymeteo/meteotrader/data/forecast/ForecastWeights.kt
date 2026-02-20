package com.polymeteo.meteotrader.data.forecast

import java.util.Locale

object ForecastWeights {
    const val DEFAULT_SOURCE_WEIGHT = 0.9

    val BASE_WEIGHTS: Map<String, Double> = mapOf(
        // Legacy IDs kept for compatibility with existing local datasets.
        "windy-ecmwf" to 1.35,
        "windy-icon" to 1.15,

        "openmeteo-ifs025" to 1.33,
        "openmeteo-ifs" to 1.30,
        "openmeteo-aifs" to 1.25,
        "windy-icon-eu" to 1.15,
        "windy-nam-conus" to 1.12,
        "weather-gov" to 1.10,
        "windy-gfs" to 1.05,
        "openweather" to 0.85
    )

    private val DEPRECATED_PROVIDER_IDS: Set<String> = setOf(
        "weatherstack",
        "ecmwf-webapi",
        "ecmwf-web-api",
        "ecmwf_webapi"
    )

    private val DEPRECATED_PROVIDER_NAME_TOKENS: List<String> = listOf(
        "weatherstack",
        "ecmwf web api",
        "ecmwf-web api",
        "ecmwf webapi"
    )

    fun baseWeightFor(sourceId: String): Double {
        return BASE_WEIGHTS[sourceId] ?: DEFAULT_SOURCE_WEIGHT
    }

    fun isDeprecatedProvider(
        sourceId: String,
        sourceName: String? = null
    ): Boolean {
        val normalizedId = sourceId.trim().lowercase(Locale.US)
        if (DEPRECATED_PROVIDER_IDS.contains(normalizedId)) return true

        val normalizedName = sourceName
            ?.trim()
            ?.lowercase(Locale.US)
            .orEmpty()

        return DEPRECATED_PROVIDER_NAME_TOKENS.any { token ->
            normalizedName.contains(token)
        }
    }
}
