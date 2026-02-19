package com.polymeteo.meteotrader.data.forecast

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
        "openweather" to 0.85,
        "ecmwf-webapi" to 1.20
    )

    fun baseWeightFor(sourceId: String): Double {
        return BASE_WEIGHTS[sourceId] ?: DEFAULT_SOURCE_WEIGHT
    }
}
