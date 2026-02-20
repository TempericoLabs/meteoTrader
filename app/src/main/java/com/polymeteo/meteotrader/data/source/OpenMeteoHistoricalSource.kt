package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.CityConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class OpenMeteoModelSpec(
    val providerId: String,
    val providerName: String,
    val modelId: String
)

data class HistoricalForecastBatch(
    val valuesByProvider: Map<String, Map<LocalDate, Double>>,
    val warnings: List<String>
)

data class HistoricalObservedSeries(
    val valuesByDate: Map<LocalDate, Double>,
    val sourceUrl: String?,
    val warning: String? = null
)

class OpenMeteoHistoricalSource(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun fetchForecastDailyMaxBatch(
        city: CityConfig,
        startDate: LocalDate,
        endDate: LocalDate,
        models: List<OpenMeteoModelSpec>,
        horizonDays: Int
    ): HistoricalForecastBatch {
        if (startDate.isAfter(endDate) || models.isEmpty()) {
            return HistoricalForecastBatch(emptyMap(), emptyList())
        }

        val warnings = mutableListOf<String>()

        if (horizonDays == 0) {
            val root = requestJson(
                url = buildHistoricalDailyUrl(
                    city = city,
                    startDate = startDate,
                    endDate = endDate,
                    models = models
                )
            )
            val series = root?.objOrNull("daily")
                ?.let { parseDailySeries(it, models, baseVariable = "temperature_2m_max", horizonDays = 0) }
                .orEmpty()
            if (series.isEmpty()) {
                warnings += "Open-Meteo histórico H0 sin datos (${city.name} ${startDate}..${endDate})"
            }
            return HistoricalForecastBatch(series, warnings)
        }

        val previousRunsDaily = requestJson(
            url = buildPreviousRunsDailyUrl(
                city = city,
                startDate = startDate,
                endDate = endDate,
                models = models,
                horizonDays = horizonDays
            )
        )?.objOrNull("daily")

        val dailySeries = previousRunsDaily
            ?.let { parseDailySeries(it, models, baseVariable = "temperature_2m_max", horizonDays = horizonDays) }
            .orEmpty()

        if (dailySeries.isNotEmpty()) {
            return HistoricalForecastBatch(dailySeries, warnings)
        }

        val previousRunsHourly = requestJson(
            url = buildPreviousRunsHourlyUrl(
                city = city,
                startDate = startDate,
                endDate = endDate,
                models = models,
                horizonDays = horizonDays
            )
        )?.objOrNull("hourly")

        val hourlySeries = previousRunsHourly
            ?.let { parseHourlySeries(it, city.zoneId, models, horizonDays) }
            .orEmpty()

        if (hourlySeries.isNotEmpty()) {
            return HistoricalForecastBatch(
                valuesByProvider = hourlySeries,
                warnings = warnings
            )
        }

        val proxyRoot = requestJson(
            url = buildHistoricalDailyUrl(
                city = city,
                startDate = startDate,
                endDate = endDate,
                models = models
            )
        )
        val proxySeries = proxyRoot?.objOrNull("daily")
            ?.let { parseDailySeries(it, models, baseVariable = "temperature_2m_max", horizonDays = 0) }
            .orEmpty()

        if (proxySeries.isNotEmpty()) {
            warnings += "Previous Runs H$horizonDays no disponible; usando proxy H0 para ${city.name}"
        } else {
            warnings += "Open-Meteo histórico H$horizonDays sin datos (${city.name} ${startDate}..${endDate})"
        }

        return HistoricalForecastBatch(proxySeries, warnings)
    }

    suspend fun fetchArchiveObservedSeries(
        city: CityConfig,
        startDate: LocalDate,
        endDate: LocalDate
    ): HistoricalObservedSeries {
        if (startDate.isAfter(endDate)) {
            return HistoricalObservedSeries(
                valuesByDate = emptyMap(),
                sourceUrl = null
            )
        }

        val url = buildArchiveUrl(city, startDate, endDate)
        val response = runCatching {
            httpClient.get(
                url = url,
                headers = mapOf("Accept" to "application/json")
            )
        }.getOrElse { throwable ->
            return HistoricalObservedSeries(
                valuesByDate = emptyMap(),
                sourceUrl = null,
                warning = throwable.message ?: "Error Open-Meteo Archive"
            )
        }

        if (response.code !in 200..299) {
            return HistoricalObservedSeries(
                valuesByDate = emptyMap(),
                sourceUrl = response.url,
                warning = "Open-Meteo Archive HTTP ${response.code}"
            )
        }

        val root = runCatching {
            json.parseToJsonElement(response.body) as? JsonObject
        }.getOrNull() ?: return HistoricalObservedSeries(
            valuesByDate = emptyMap(),
            sourceUrl = response.url,
            warning = "Open-Meteo Archive payload inválido"
        )

        val daily = root.objOrNull("daily") ?: return HistoricalObservedSeries(
            valuesByDate = emptyMap(),
            sourceUrl = response.url,
            warning = "Open-Meteo Archive sin daily"
        )

        val dates = parseDailyDates(daily)
        val values = parseDoubleArray(daily["temperature_2m_max"] as? JsonArray)
        val map = dates.zip(values).mapNotNull { (date, value) ->
            val valid = value?.takeIf { it in VALID_TEMP_RANGE_C } ?: return@mapNotNull null
            date to valid
        }.toMap()

        return HistoricalObservedSeries(
            valuesByDate = map,
            sourceUrl = response.url
        )
    }

    private suspend fun requestJson(url: String): JsonObject? {
        val response = runCatching {
            httpClient.get(
                url = url,
                headers = mapOf("Accept" to "application/json")
            )
        }.getOrNull() ?: return null

        if (response.code !in 200..299) return null
        return runCatching {
            json.parseToJsonElement(response.body) as? JsonObject
        }.getOrNull()
    }

    private fun parseDailySeries(
        daily: JsonObject,
        models: List<OpenMeteoModelSpec>,
        baseVariable: String,
        horizonDays: Int
    ): Map<String, Map<LocalDate, Double>> {
        val dates = parseDailyDates(daily)
        if (dates.isEmpty()) return emptyMap()

        val onlyOneModel = models.size == 1

        return models.associateNotNull { model ->
            val candidates = dailyKeysForModel(
                modelId = model.modelId,
                baseVariable = baseVariable,
                horizonDays = horizonDays,
                onlyOneModel = onlyOneModel
            )
            val key = candidates.firstOrNull { daily.containsKey(it) } ?: return@associateNotNull null
            val values = parseDoubleArray(daily[key] as? JsonArray)
            val series = dates.zip(values).mapNotNull { (date, value) ->
                val valid = value?.takeIf { it in VALID_TEMP_RANGE_C } ?: return@mapNotNull null
                date to valid
            }.toMap()
            if (series.isEmpty()) null else model.providerId to series
        }
    }

    private fun parseHourlySeries(
        hourly: JsonObject,
        zoneIdText: String,
        models: List<OpenMeteoModelSpec>,
        horizonDays: Int
    ): Map<String, Map<LocalDate, Double>> {
        val zoneId = runCatching { ZoneId.of(zoneIdText) }.getOrDefault(ZoneId.of("UTC"))
        val timeValues = (hourly["time"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        if (timeValues.isEmpty()) return emptyMap()

        val dates = timeValues.mapNotNull { parseTimeToLocalDate(it, zoneId) }
        if (dates.isEmpty()) return emptyMap()

        val onlyOneModel = models.size == 1

        return models.associateNotNull { model ->
            val candidates = hourlyKeysForModel(model.modelId, horizonDays, onlyOneModel)
            val key = candidates.firstOrNull { hourly.containsKey(it) } ?: return@associateNotNull null
            val values = parseDoubleArray(hourly[key] as? JsonArray)
            if (values.isEmpty()) return@associateNotNull null

            val dailyMax = mutableMapOf<LocalDate, Double>()
            val count = minOf(dates.size, values.size)
            for (index in 0 until count) {
                val value = values[index] ?: continue
                if (value !in VALID_TEMP_RANGE_C) continue
                val day = dates[index]
                val previous = dailyMax[day]
                if (previous == null || value > previous) {
                    dailyMax[day] = value
                }
            }
            if (dailyMax.isEmpty()) null else model.providerId to dailyMax
        }
    }

    private fun parseDailyDates(daily: JsonObject): List<LocalDate> {
        return (daily["time"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLocalDateOrNull() }
            .orEmpty()
    }

    private fun parseDoubleArray(array: JsonArray?): List<Double?> {
        if (array == null) return emptyList()
        return array.map { element ->
            (element as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        }
    }

    private fun dailyKeysForModel(
        modelId: String,
        baseVariable: String,
        horizonDays: Int,
        onlyOneModel: Boolean
    ): List<String> {
        if (horizonDays <= 0) {
            return buildList {
                add("${baseVariable}_$modelId")
                if (onlyOneModel) add(baseVariable)
            }
        }
        val suffix = "previous_day$horizonDays"
        return buildList {
            add("${baseVariable}_${suffix}_$modelId")
            add("${baseVariable}_${modelId}_$suffix")
            add("${baseVariable}_$suffix")
            add("${baseVariable}_$modelId")
            if (onlyOneModel) add(baseVariable)
        }
    }

    private fun hourlyKeysForModel(
        modelId: String,
        horizonDays: Int,
        onlyOneModel: Boolean
    ): List<String> {
        val suffix = "previous_day$horizonDays"
        return buildList {
            add("temperature_2m_${suffix}_$modelId")
            add("temperature_2m_${modelId}_$suffix")
            add("temperature_2m_$suffix")
            add("temperature_2m_$modelId")
            if (onlyOneModel) add("temperature_2m")
        }
    }

    private fun parseTimeToLocalDate(raw: String, zoneId: ZoneId): LocalDate? {
        return runCatching {
            OffsetDateTime.parse(raw).toLocalDate()
        }.getOrElse {
            runCatching {
                LocalDateTime.parse(raw).atZone(zoneId).toLocalDate()
            }.getOrElse {
                runCatching {
                    LocalDate.parse(raw)
                }.getOrNull()
            }
        }
    }

    private fun buildHistoricalDailyUrl(
        city: CityConfig,
        startDate: LocalDate,
        endDate: LocalDate,
        models: List<OpenMeteoModelSpec>
    ): String {
        val modelParam = models.joinToString(",") { it.modelId }
        return buildString {
            append("https://historical-forecast-api.open-meteo.com/v1/forecast")
            append("?latitude=${city.latitude}")
            append("&longitude=${city.longitude}")
            append("&start_date=$startDate")
            append("&end_date=$endDate")
            append("&daily=temperature_2m_max")
            append("&timezone=${encode(city.zoneId)}")
            append("&models=${encode(modelParam)}")
        }
    }

    private fun buildPreviousRunsDailyUrl(
        city: CityConfig,
        startDate: LocalDate,
        endDate: LocalDate,
        models: List<OpenMeteoModelSpec>,
        horizonDays: Int
    ): String {
        val modelParam = models.joinToString(",") { it.modelId }
        return buildString {
            append("https://previous-runs-api.open-meteo.com/v1/forecast")
            append("?latitude=${city.latitude}")
            append("&longitude=${city.longitude}")
            append("&start_date=$startDate")
            append("&end_date=$endDate")
            append("&daily=temperature_2m_max_previous_day$horizonDays")
            append("&timezone=${encode(city.zoneId)}")
            append("&models=${encode(modelParam)}")
        }
    }

    private fun buildPreviousRunsHourlyUrl(
        city: CityConfig,
        startDate: LocalDate,
        endDate: LocalDate,
        models: List<OpenMeteoModelSpec>,
        horizonDays: Int
    ): String {
        val modelParam = models.joinToString(",") { it.modelId }
        return buildString {
            append("https://previous-runs-api.open-meteo.com/v1/forecast")
            append("?latitude=${city.latitude}")
            append("&longitude=${city.longitude}")
            append("&start_date=$startDate")
            append("&end_date=$endDate")
            append("&hourly=temperature_2m_previous_day$horizonDays")
            append("&timezone=${encode(city.zoneId)}")
            append("&models=${encode(modelParam)}")
        }
    }

    private fun buildArchiveUrl(
        city: CityConfig,
        startDate: LocalDate,
        endDate: LocalDate
    ): String {
        return buildString {
            append("https://archive-api.open-meteo.com/v1/archive")
            append("?latitude=${city.latitude}")
            append("&longitude=${city.longitude}")
            append("&start_date=$startDate")
            append("&end_date=$endDate")
            append("&daily=temperature_2m_max")
            append("&timezone=${encode(city.zoneId)}")
        }
    }

    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
    }

    private inline fun <T, K, V> Iterable<T>.associateNotNull(transform: (T) -> Pair<K, V>?): Map<K, V> {
        val destination = LinkedHashMap<K, V>()
        for (element in this) {
            val pair = transform(element) ?: continue
            destination[pair.first] = pair.second
        }
        return destination
    }

    private fun String.toLocalDateOrNull(): LocalDate? {
        return runCatching { LocalDate.parse(this) }.getOrNull()
    }

    companion object {
        private val VALID_TEMP_RANGE_C = -80.0..65.0
    }
}
