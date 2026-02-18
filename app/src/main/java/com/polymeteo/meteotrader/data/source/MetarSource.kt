package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.MetarReading
import com.polymeteo.meteotrader.data.model.MetarSnapshot
import com.polymeteo.meteotrader.util.celsiusToFahrenheit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId

class MetarSource(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun fetch(city: CityConfig): MetarSnapshot {
        val sourceUrl = "https://aviationweather.gov/api/data/metar?ids=${city.metarCode}&format=json&hours=24"
        return runCatching {
            val response = httpClient.get(sourceUrl)
            if (response.code !in 200..299) {
                return MetarSnapshot(
                    sourceUrl = sourceUrl,
                    current = null,
                    previousSameDay = null,
                    error = "HTTP ${response.code}"
                )
            }
            val readings = parseReadings(response.body)
            if (readings.isEmpty()) {
                return MetarSnapshot(
                    sourceUrl = sourceUrl,
                    current = null,
                    previousSameDay = null,
                    error = "METAR sin lecturas"
                )
            }

            val zoneId = ZoneId.of(city.zoneId)
            val current = readings.first()
            val currentLocalDay = current.observedAt.atZone(zoneId).toLocalDate()
            val previous = readings.drop(1).firstOrNull {
                it.observedAt.atZone(zoneId).toLocalDate() == currentLocalDay
            }

            MetarSnapshot(
                sourceUrl = sourceUrl,
                current = current,
                previousSameDay = previous,
                error = null
            )
        }.getOrElse { throwable ->
            MetarSnapshot(
                sourceUrl = sourceUrl,
                current = null,
                previousSameDay = null,
                error = throwable.message ?: "Error desconocido en METAR"
            )
        }
    }

    private fun parseReadings(rawJson: String): List<MetarReading> {
        val root = json.parseToJsonElement(rawJson)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val data = root["data"]
                if (data is JsonArray) data else emptyList<JsonElement>().let { JsonArray(it) }
            }
            else -> JsonArray(emptyList())
        }

        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val observedAt = parseInstant(obj) ?: return@mapNotNull null
            val tempC = obj.doubleOrNull("temp")
                ?: obj.doubleOrNull("temperature")
                ?: obj.objOrNull("temperature")?.doubleOrNull("value")

            val tempF = obj.doubleOrNull("tempF")
                ?: obj.objOrNull("temperature")?.doubleOrNull("fahrenheit")
                ?: tempC?.let(::celsiusToFahrenheit)

            MetarReading(
                observedAt = observedAt,
                tempC = tempC,
                tempF = tempF,
                rawText = obj.stringOrNull("rawOb") ?: obj.stringOrNull("raw")
            )
        }.sortedByDescending { it.observedAt }
    }

    private fun parseInstant(obj: JsonObject): Instant? {
        val directCandidates = listOf("obsTime", "observationTime", "obs_time")
        for (key in directCandidates) {
            val value = obj.stringOrNull(key)
            if (!value.isNullOrBlank()) {
                runCatching { Instant.parse(value) }.getOrNull()?.let { return it }
            }
            obj.longOrNull(key)?.let { millisOrSeconds ->
                val instant = if (millisOrSeconds > 100_000_000_000L) {
                    Instant.ofEpochMilli(millisOrSeconds)
                } else {
                    Instant.ofEpochSecond(millisOrSeconds)
                }
                return instant
            }
        }

        obj.stringOrNull("date")?.let {
            runCatching { Instant.parse(it) }.getOrNull()?.let { parsed ->
                return parsed
            }
        }

        return null
    }
}
