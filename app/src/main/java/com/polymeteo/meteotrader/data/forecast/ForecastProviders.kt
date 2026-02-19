package com.polymeteo.meteotrader.data.forecast

import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.ForecastSourceResult
import com.polymeteo.meteotrader.data.model.SourceStatus
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.data.source.HttpClient
import com.polymeteo.meteotrader.data.source.doubleOrNull
import com.polymeteo.meteotrader.data.source.longOrNull
import com.polymeteo.meteotrader.data.source.objOrNull
import com.polymeteo.meteotrader.data.source.stringOrNull
import com.polymeteo.meteotrader.util.celsiusToFahrenheit
import com.polymeteo.meteotrader.util.fahrenheitToCelsius
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.net.URLEncoder
import java.util.Locale

interface ForecastProvider {
    val id: String
    val name: String

    suspend fun fetch(city: CityConfig, targetDate: LocalDate): ForecastSourceResult
}

private fun successResult(
    provider: ForecastProvider,
    maxC: Double?,
    currentC: Double?
): ForecastSourceResult {
    if (maxC == null) {
        return ForecastSourceResult(
            sourceId = provider.id,
            sourceName = provider.name,
            status = SourceStatus.ERROR,
            maxTempC = null,
            maxTempF = null,
            currentTempC = null,
            currentTempF = null,
            error = "Sin dato de máxima"
        )
    }
    return ForecastSourceResult(
        sourceId = provider.id,
        sourceName = provider.name,
        status = SourceStatus.SUCCESS,
        maxTempC = maxC,
        maxTempF = celsiusToFahrenheit(maxC),
        currentTempC = currentC,
        currentTempF = currentC?.let(::celsiusToFahrenheit),
        error = null
    )
}

private fun errorResult(
    provider: ForecastProvider,
    message: String,
    status: SourceStatus = SourceStatus.ERROR
): ForecastSourceResult = ForecastSourceResult(
    sourceId = provider.id,
    sourceName = provider.name,
    status = status,
    maxTempC = null,
    maxTempF = null,
    currentTempC = null,
    currentTempF = null,
    error = message
)

private fun compactApiError(
    root: JsonObject?,
    fallback: String
): String {
    val errorObj = root?.objOrNull("error")
    val info = errorObj?.stringOrNull("info")
    val type = errorObj?.stringOrNull("type")
    val code = errorObj?.longOrNull("code")
    val pieces = listOfNotNull(
        code?.let { "code=$it" },
        type,
        info
    )
    return if (pieces.isNotEmpty()) pieces.joinToString(" | ") else fallback
}

private fun compactHttpError(
    code: Int,
    body: String
): String {
    val snippet = body
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(140)
    return if (snippet.isBlank()) "HTTP $code" else "HTTP $code: $snippet"
}

class OpenMeteoProvider(
    private val httpClient: HttpClient,
    private val model: String,
    override val id: String,
    override val name: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ForecastProvider {

    override suspend fun fetch(city: CityConfig, targetDate: LocalDate): ForecastSourceResult {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=")
            append(city.latitude)
            append("&longitude=")
            append(city.longitude)
            append("&daily=temperature_2m_max")
            append("&current=temperature_2m")
            append("&timezone=auto")
            append("&forecast_days=3")
            append("&models=")
            append(model)
        }

        return runCatching {
            val response = httpClient.get(url)
            if (response.code !in 200..299) {
                return errorResult(this, "HTTP ${response.code}")
            }
            val root = json.parseToJsonElement(response.body) as? JsonObject
                ?: return errorResult(this, "Payload Open-Meteo inválido")

            val daily = root.objOrNull("daily")
            val dayLabels = (daily?.get("time") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLocalDateOrNull() }
                .orEmpty()
            val maxTemps = (daily?.get("temperature_2m_max") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
                .orEmpty()

            val maxTemp = dayLabels.zip(maxTemps).firstOrNull { it.first == targetDate }?.second
                ?: maxTemps.firstOrNull()
            val current = root.objOrNull("current")?.doubleOrNull("temperature_2m")
            successResult(this, maxTemp, current)
        }.getOrElse { throwable ->
            errorResult(this, throwable.message ?: "Error Open-Meteo")
        }
    }
}

class OpenWeatherProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ForecastProvider {

    override val id: String = "openweather"
    override val name: String = "OpenWeather"

    override suspend fun fetch(city: CityConfig, targetDate: LocalDate): ForecastSourceResult {
        if (apiKey.isBlank()) {
            return errorResult(this, "OPEN_WEATHER_API_KEY vacío", SourceStatus.SKIPPED)
        }

        val url = buildString {
            append("https://api.openweathermap.org/data/2.5/forecast")
            append("?lat=${city.latitude}")
            append("&lon=${city.longitude}")
            append("&units=metric")
            append("&appid=$apiKey")
        }

        return runCatching {
            val response = httpClient.get(url)
            if (response.code !in 200..299) {
                return errorResult(this, "HTTP ${response.code}")
            }
            val root = json.parseToJsonElement(response.body) as? JsonObject
                ?: return errorResult(this, "Payload OpenWeather inválido")

            val list = root["list"] as? JsonArray ?: return errorResult(this, "OpenWeather sin lista")
            val zoneId = ZoneId.of(city.zoneId)

            val points = list.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val epoch = obj.longOrNull("dt") ?: return@mapNotNull null
                val temp = obj.objOrNull("main")?.doubleOrNull("temp") ?: return@mapNotNull null
                val instant = Instant.ofEpochSecond(epoch)
                val localDate = instant.atZone(zoneId).toLocalDate()
                Triple(localDate, instant, temp)
            }

            val maxTemp = points.filter { it.first == targetDate }.maxOfOrNull { it.third }
            val current = points.firstOrNull()?.third
            successResult(this, maxTemp, current)
        }.getOrElse { throwable ->
            errorResult(this, throwable.message ?: "Error OpenWeather")
        }
    }
}

class WeatherStackProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ForecastProvider {

    override val id: String = "weatherstack"
    override val name: String = "Weatherstack"

    override suspend fun fetch(city: CityConfig, targetDate: LocalDate): ForecastSourceResult {
        if (apiKey.isBlank()) {
            return errorResult(this, "WEATHERSTACK_API_KEY vacío", SourceStatus.SKIPPED)
        }

        val zoneId = ZoneId.of(city.zoneId)
        val today = LocalDate.now(zoneId)
        val daysAhead = ChronoUnit.DAYS.between(today, targetDate).toInt()
        val forecastDays = (daysAhead + 1).coerceIn(1, 3)
        val queryRaw = String.format(Locale.US, "%.4f,%.4f", city.latitude, city.longitude)
        val query = URLEncoder.encode(queryRaw, Charsets.UTF_8.name())
        val url = buildString {
            append("https://api.weatherstack.com/forecast")
            append("?access_key=$apiKey")
            append("&query=$query")
            append("&forecast_days=$forecastDays")
            append("&hourly=1")
            append("&units=m")
        }

        return runCatching {
            val response = httpClient.get(url)
            if (response.code !in 200..299) {
                return errorResult(this, compactHttpError(response.code, response.body))
            }
            val root = json.parseToJsonElement(response.body) as? JsonObject
                ?: return errorResult(this, "Payload Weatherstack inválido")

            val isSuccess = root["success"]?.let { (it as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() }
            if (isSuccess == false || root.objOrNull("error") != null) {
                val errorObj = root.objOrNull("error")
                val code = errorObj?.longOrNull("code")
                val errorMessage = compactApiError(root, "Error Weatherstack")
                val status = when (code?.toInt()) {
                    403, 609 -> SourceStatus.SKIPPED // plan sin forecast
                    else -> SourceStatus.ERROR
                }
                return errorResult(this, errorMessage, status)
            }

            val forecast = root.objOrNull("forecast")
            val dayObject = (forecast?.get(targetDate.toString()) as? JsonObject)
                ?: (forecast?.entries?.firstOrNull()?.value as? JsonObject)
            val maxTemp = dayObject?.doubleOrNull("maxtemp") ?: dayObject?.doubleOrNull("maxtempC")
            val current = root.objOrNull("current")?.doubleOrNull("temperature")
            if (maxTemp == null) {
                return errorResult(this, "Weatherstack sin maxtemp para ${targetDate}", SourceStatus.ERROR)
            }
            successResult(this, maxTemp, current)
        }.getOrElse { throwable ->
            errorResult(this, throwable.message ?: "Error Weatherstack")
        }
    }
}

class WeatherGovProvider(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ForecastProvider {

    override val id: String = "weather-gov"
    override val name: String = "NOAA Weather.gov"

    override suspend fun fetch(city: CityConfig, targetDate: LocalDate): ForecastSourceResult {
        if (!city.metarCode.startsWith("K")) {
            return errorResult(this, "Solo ciudades de EE.UU.", SourceStatus.SKIPPED)
        }

        val userAgent = "PolyMeteo/0.1 (meteo-trader local client)"
        return runCatching {
            val pointsUrl = "https://api.weather.gov/points/${city.latitude},${city.longitude}"
            val pointsResponse = httpClient.get(
                pointsUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/geo+json,application/json"
                )
            )
            if (pointsResponse.code !in 200..299) {
                return errorResult(this, "Points HTTP ${pointsResponse.code}")
            }
            val pointsRoot = json.parseToJsonElement(pointsResponse.body) as? JsonObject
                ?: return errorResult(this, "Points payload inválido")

            val properties = pointsRoot.objOrNull("properties")
                ?: return errorResult(this, "Points sin properties")
            val forecastUrl = properties.stringOrNull("forecastHourly")
                ?: properties.stringOrNull("forecast")
                ?: return errorResult(this, "Points sin URL forecast")

            val forecastResponse = httpClient.get(
                forecastUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/geo+json,application/json"
                )
            )
            if (forecastResponse.code !in 200..299) {
                return errorResult(this, "Forecast HTTP ${forecastResponse.code}")
            }

            val forecastRoot = json.parseToJsonElement(forecastResponse.body) as? JsonObject
                ?: return errorResult(this, "Forecast payload inválido")
            val periods = forecastRoot
                .objOrNull("properties")
                ?.get("periods") as? JsonArray
                ?: return errorResult(this, "Forecast sin periods")

            val zoneId = ZoneId.of(city.zoneId)
            val values = periods.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val temp = obj.doubleOrNull("temperature") ?: return@mapNotNull null
                val unit = when (obj.stringOrNull("temperatureUnit")?.uppercase()) {
                    "C" -> TempUnit.C
                    else -> TempUnit.F
                }
                val start = obj.stringOrNull("startTime") ?: return@mapNotNull null
                val localDate = runCatching {
                    Instant.parse(start).atZone(zoneId).toLocalDate()
                }.getOrElse {
                    runCatching {
                        java.time.OffsetDateTime.parse(start).toInstant().atZone(zoneId).toLocalDate()
                    }.getOrNull()
                } ?: return@mapNotNull null

                val tempC = if (unit == TempUnit.C) temp else fahrenheitToCelsius(temp)
                localDate to tempC
            }

            val sameDayTemps = values.filter { it.first == targetDate }.map { it.second }
            val maxTemp = sameDayTemps.maxOrNull()
            val current = values.firstOrNull()?.second
            successResult(this, maxTemp, current)
        }.getOrElse { throwable ->
            errorResult(this, throwable.message ?: "Error Weather.gov")
        }
    }
}

class WindyProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String,
    override val id: String,
    override val name: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : ForecastProvider {

    override suspend fun fetch(city: CityConfig, targetDate: LocalDate): ForecastSourceResult {
        if (apiKey.isBlank()) {
            return errorResult(this, "WINDY_API_KEY vacío", SourceStatus.SKIPPED)
        }
        val resolvedModel = normalizeWindyModel(model)
        if (resolvedModel == null) {
            return errorResult(
                this,
                "Modelo Windy no soportado: $model (Point Forecast no expone ECMWF)",
                SourceStatus.SKIPPED
            )
        }
        if (!isModelCoverageSupported(resolvedModel, city)) {
            return errorResult(
                this,
                "Modelo Windy $resolvedModel fuera de cobertura para ${city.name}",
                SourceStatus.SKIPPED
            )
        }

        val payload = """
            {
              "lat": ${city.latitude},
              "lon": ${city.longitude},
              "model": "$resolvedModel",
              "parameters": ["temp"],
              "levels": ["surface"],
              "key": "$apiKey"
            }
        """.trimIndent()

        return runCatching {
            val response = httpClient.postJson(
                url = "https://api.windy.com/api/point-forecast/v2",
                jsonBody = payload,
                headers = mapOf("Content-Type" to "application/json")
            )
            if (response.code == 204) {
                return errorResult(this, "Windy sin datos para modelo/parametría", SourceStatus.SKIPPED)
            }
            if (response.code !in 200..299) {
                return errorResult(this, compactHttpError(response.code, response.body))
            }

            val root = json.parseToJsonElement(response.body) as? JsonObject
                ?: return errorResult(this, "Payload Windy inválido")

            val errorText = compactApiError(root, "")
            if (errorText.isNotBlank() && root.objOrNull("error") != null) {
                return errorResult(this, errorText)
            }

            val timestamps = (root["ts"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
                .orEmpty()

            val unit = extractWindyTempUnit(root)
            val tempsRaw = extractTemperatureSeries(root).map { normalizeWindyTempToCelsius(it, unit) }
            if (tempsRaw.isEmpty()) {
                return errorResult(this, "Windy sin serie térmica")
            }

            val zoneId = ZoneId.of(city.zoneId)
            val series = if (timestamps.isNotEmpty() && timestamps.size == tempsRaw.size) {
                timestamps.zip(tempsRaw).map { (ts, value) ->
                    val instant = if (ts > 100_000_000_000L) Instant.ofEpochMilli(ts) else Instant.ofEpochSecond(ts)
                    instant.atZone(zoneId).toLocalDate() to value
                }
            } else {
                // Fallback defensivo cuando no hay timestamps válidos.
                val today = LocalDate.now(zoneId)
                tempsRaw.mapIndexed { index, value ->
                    today.plusDays((index / 8).toLong()) to value
                }
            }

            val dayValues = series.filter { it.first == targetDate }.map { it.second }
            val maxTemp = dayValues.maxOrNull()
            val current = series.firstOrNull()?.second
            successResult(this, maxTemp, current)
        }.getOrElse { throwable ->
            errorResult(this, throwable.message ?: "Error Windy")
        }
    }

    private fun extractTemperatureSeries(root: JsonObject): List<Double> {
        val directKeys = listOf("temp-surface", "temp_surface", "temp", "temperature")
        for (key in directKeys) {
            val values = (root[key] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
                .orEmpty()
            if (values.isNotEmpty()) return values
        }

        root.entries.forEach { (_, value) ->
            val arr = value as? JsonArray ?: return@forEach
            val first = arr.firstOrNull() as? JsonPrimitive ?: return@forEach
            val numeric = first.contentOrNull?.toDoubleOrNull() ?: return@forEach
            if (numeric in -150.0..350.0 && arr.size >= 4) {
                return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }
            }
        }
        return emptyList()
    }

    private fun extractWindyTempUnit(root: JsonObject): String? {
        val units = root.objOrNull("units") ?: return null
        return units.stringOrNull("temp-surface")
            ?: units.stringOrNull("temp_surface")
            ?: units.stringOrNull("temp")
            ?: units.entries.firstOrNull { it.key.startsWith("temp") }?.value
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun normalizeWindyTempToCelsius(
        value: Double,
        unit: String?
    ): Double {
        val normalizedUnit = unit?.trim()?.lowercase()
        return when {
            normalizedUnit == "k" || normalizedUnit == "kelvin" -> value - 273.15
            normalizedUnit == "c" || normalizedUnit == "°c" -> value
            normalizedUnit == "f" || normalizedUnit == "°f" -> fahrenheitToCelsius(value)
            // Heurística segura cuando no llega "units"
            value > 170 -> value - 273.15
            else -> value
        }
    }

    private fun normalizeWindyModel(rawModel: String): String? {
        return when (rawModel.trim().lowercase()) {
            "gfs" -> "gfs"
            "icon", "iconeu" -> "iconEu"
            "arome" -> "arome"
            "namconus", "nam_conus" -> "namConus"
            "ecmwf" -> null
            else -> rawModel
        }
    }

    private fun isModelCoverageSupported(
        windyModel: String,
        city: CityConfig
    ): Boolean {
        return when (windyModel) {
            // NAM CONUS se limita a EE. UU. continental.
            "namConus" -> city.metarCode.startsWith("K")
            // ICON-EU/AROME son regionales europeas; filtramos por caja geográfica aproximada.
            "iconEu", "arome" -> city.latitude in 30.0..72.0 && city.longitude in -25.0..45.0
            else -> true
        }
    }
}

class PlaceholderEcmwfProvider : ForecastProvider {
    override val id: String = "ecmwf-webapi"
    override val name: String = "ECMWF Web API"

    override suspend fun fetch(city: CityConfig, targetDate: LocalDate): ForecastSourceResult {
        return errorResult(
            provider = this,
            message = "Requiere pipeline batch (MARS/WebAPI) y bridge HTTP para móvil",
            status = SourceStatus.SKIPPED
        )
    }
}

private fun String.toLocalDateOrNull(): LocalDate? {
    return try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }
}
