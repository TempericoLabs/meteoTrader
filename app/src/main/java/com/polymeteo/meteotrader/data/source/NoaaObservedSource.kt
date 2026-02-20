package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.CityConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import kotlin.math.pow

data class NoaaObservedSeries(
    val valuesByDate: Map<LocalDate, Double>,
    val sourceUrl: String?,
    val warning: String? = null
)

class NoaaObservedSource(
    private val httpClient: HttpClient,
    private val token: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val stationCache = mutableMapOf<String, NoaaStation>()

    suspend fun fetchDailyMaxSeries(
        city: CityConfig,
        startDate: LocalDate,
        endDate: LocalDate
    ): NoaaObservedSeries {
        if (startDate.isAfter(endDate)) {
            return NoaaObservedSeries(
                valuesByDate = emptyMap(),
                sourceUrl = null
            )
        }
        if (token.isBlank()) {
            return NoaaObservedSeries(
                valuesByDate = emptyMap(),
                sourceUrl = null,
                warning = "NOAA token no configurado"
            )
        }

        val station = stationCache[city.id] ?: resolveStation(city)?.also { stationCache[city.id] = it }
        if (station == null) {
            return NoaaObservedSeries(
                valuesByDate = emptyMap(),
                sourceUrl = null,
                warning = "NOAA sin estación cercana para ${city.name}"
            )
        }

        val url = buildDataUrl(
            stationId = station.id,
            startDate = startDate,
            endDate = endDate
        )
        val response = runCatching {
            httpClient.get(
                url = url,
                headers = requestHeaders()
            )
        }.getOrElse { throwable ->
            return NoaaObservedSeries(
                valuesByDate = emptyMap(),
                sourceUrl = null,
                warning = throwable.message ?: "Error NOAA data"
            )
        }

        if (response.code !in 200..299) {
            return NoaaObservedSeries(
                valuesByDate = emptyMap(),
                sourceUrl = response.url,
                warning = "NOAA data HTTP ${response.code}"
            )
        }

        val root = runCatching {
            json.parseToJsonElement(response.body) as? JsonObject
        }.getOrNull() ?: return NoaaObservedSeries(
            valuesByDate = emptyMap(),
            sourceUrl = response.url,
            warning = "NOAA data payload inválido"
        )

        val results = root["results"] as? kotlinx.serialization.json.JsonArray ?: return NoaaObservedSeries(
            valuesByDate = emptyMap(),
            sourceUrl = response.url,
            warning = "NOAA data sin resultados"
        )

        val valuesByDate = results.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val date = obj.stringOrNull("date")
                ?.substring(0, 10)
                ?.toLocalDateOrNull()
                ?: return@mapNotNull null
            val rawValue = obj.doubleOrNull("value") ?: return@mapNotNull null
            val tempC = normalizeNoaaTmax(rawValue)
            if (tempC !in VALID_TEMP_RANGE_C) return@mapNotNull null
            date to tempC
        }.toMap()

        return NoaaObservedSeries(
            valuesByDate = valuesByDate,
            sourceUrl = response.url
        )
    }

    private suspend fun resolveStation(city: CityConfig): NoaaStation? {
        val radii = listOf(0.6, 1.5, 3.0)
        for (radius in radii) {
            val url = buildStationsUrl(city, radius)
            val response = runCatching {
                httpClient.get(
                    url = url,
                    headers = requestHeaders()
                )
            }.getOrNull() ?: continue

            if (response.code !in 200..299) continue

            val root = runCatching {
                json.parseToJsonElement(response.body) as? JsonObject
            }.getOrNull() ?: continue

            val results = root["results"] as? kotlinx.serialization.json.JsonArray ?: continue
            val best = results.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj.stringOrNull("id") ?: return@mapNotNull null
                val lat = obj.doubleOrNull("latitude") ?: return@mapNotNull null
                val lon = obj.doubleOrNull("longitude") ?: return@mapNotNull null
                NoaaStation(
                    id = id,
                    latitude = lat,
                    longitude = lon
                )
            }.minByOrNull { station ->
                squaredDistance(city.latitude, city.longitude, station.latitude, station.longitude)
            }
            if (best != null) return best
        }
        return null
    }

    private fun buildStationsUrl(city: CityConfig, radiusDeg: Double): String {
        val minLat = city.latitude - radiusDeg
        val maxLat = city.latitude + radiusDeg
        val minLon = city.longitude - radiusDeg
        val maxLon = city.longitude + radiusDeg
        return buildString {
            append("$CDO_BASE/stations")
            append("?datasetid=GHCND")
            append("&extent=$minLat,$minLon,$maxLat,$maxLon")
            append("&limit=50")
        }
    }

    private fun buildDataUrl(
        stationId: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): String {
        return buildString {
            append("$CDO_BASE/data")
            append("?datasetid=GHCND")
            append("&datatypeid=TMAX")
            append("&stationid=${encode(stationId)}")
            append("&startdate=$startDate")
            append("&enddate=$endDate")
            append("&units=metric")
            append("&limit=1000")
        }
    }

    private fun requestHeaders(): Map<String, String> {
        return mapOf(
            "token" to token,
            "Accept" to "application/json"
        )
    }

    private fun normalizeNoaaTmax(rawValue: Double): Double {
        return if (rawValue > 80.0 || rawValue < -80.0) rawValue / 10.0 else rawValue
    }

    private fun squaredDistance(
        latA: Double,
        lonA: Double,
        latB: Double,
        lonB: Double
    ): Double {
        return (latA - latB).pow(2) + (lonA - lonB).pow(2)
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
    }

    private fun String.toLocalDateOrNull(): LocalDate? {
        return runCatching { LocalDate.parse(this) }.getOrNull()
    }

    private data class NoaaStation(
        val id: String,
        val latitude: Double,
        val longitude: Double
    )

    companion object {
        private const val CDO_BASE = "https://www.ncdc.noaa.gov/cdo-web/api/v2"
        private val VALID_TEMP_RANGE_C = -80.0..65.0
    }
}
