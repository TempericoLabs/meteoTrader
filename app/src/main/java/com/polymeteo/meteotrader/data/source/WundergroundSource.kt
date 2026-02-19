package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.ControlStationSnapshot
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.util.celsiusToFahrenheit
import com.polymeteo.meteotrader.util.fahrenheitToCelsius
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import java.time.LocalDate

class WundergroundSource(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun fetch(city: CityConfig): ControlStationSnapshot {
        val attempts = listOf(city.wundergroundControlUrl, city.wundergroundPwsUrl)
        return fetchFromUrls(city = city, attempts = attempts)
    }

    suspend fun fetchDailyMaxForDate(city: CityConfig, date: LocalDate): ControlStationSnapshot {
        val attempts = buildList {
            add(buildHistoryDateUrl(city.wundergroundControlUrl, date))
            add(city.wundergroundControlUrl)
            add(city.wundergroundPwsUrl)
        }.distinct()
        return fetchFromUrls(city = city, attempts = attempts)
    }

    private suspend fun fetchFromUrls(
        city: CityConfig,
        attempts: List<String>
    ): ControlStationSnapshot {
        val errors = mutableListOf<String>()

        for (url in attempts) {
            var lastError: String? = null

            repeat(HTML_FETCH_RETRIES) { retry ->
                val response = runCatching {
                    httpClient.get(
                        url = url,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Android) PolyMeteo/1.0",
                            "Accept" to "text/html,application/xhtml+xml",
                            "Referer" to "https://www.wunderground.com/",
                            "Origin" to "https://www.wunderground.com"
                        )
                    )
                }.getOrNull()

                if (response == null) {
                    lastError = "sin respuesta HTML"
                    if (retry < HTML_FETCH_RETRIES - 1) delay(RETRY_DELAY_MS)
                    return@repeat
                }

                if (response.code !in 200..299) {
                    lastError = "HTTP ${response.code}"
                    if (retry < HTML_FETCH_RETRIES - 1) delay(RETRY_DELAY_MS)
                    return@repeat
                }

                val parseResult = extractHighTempActual(
                    html = response.body,
                    city = city
                )
                if (parseResult != null) {
                    val (value, unit) = parseResult
                    val tempC = if (unit == TempUnit.C) value else fahrenheitToCelsius(value)
                    val tempF = if (unit == TempUnit.F) value else celsiusToFahrenheit(value)
                    return ControlStationSnapshot(
                        sourceUrl = response.url,
                        tempC = tempC,
                        tempF = tempF,
                        error = null
                    )
                }

                lastError = "sin maxima diaria detectable"
                if (retry < HTML_FETCH_RETRIES - 1) delay(RETRY_DELAY_MS)
            }

            errors += "${url.take(48)}... ${lastError ?: "error desconocido"}"
        }

        return ControlStationSnapshot(
            sourceUrl = null,
            tempC = null,
            tempF = null,
            error = if (errors.isEmpty()) "Wunderground sin respuesta" else errors.joinToString(" | ")
        )
    }

    private fun buildHistoryDateUrl(baseUrl: String, date: LocalDate): String {
        val noQuery = baseUrl.substringBefore("?")
        val trimmed = noQuery.trimEnd('/')
        val root = trimmed.substringBefore("/date/")
        return "$root/date/$date"
    }

    private suspend fun extractHighTempActual(
        html: String,
        city: CityConfig
    ): Pair<Double, TempUnit>? {
        val document = Jsoup.parse(html)
        val scriptsJoined = buildString {
            document.select("script").forEach { append(it.data()) }
        }
        val pageText = document.text()
        val summaryCandidate = findSummaryHighTempActual(pageText, preferredUnit = city.displayUnit)
        val currentCandidate = findEmbeddedObservationHighTemp(scriptsJoined, city, key = "temperatureMaxSince7Am")
            ?: findEmbeddedObservationHighTemp(scriptsJoined, city, key = "temperatureMax24Hour")
            ?: findHighTempAfterKeyword(pageText, preferredUnit = city.displayUnit)
            ?: findAnyEmbeddedMaxTemperature(scriptsJoined, preferredUnit = city.displayUnit)
            ?: fetchObservationMaxFromApi(html = html, city = city)
            ?: findAnyEmbeddedCurrentTemperature(scriptsJoined, preferredUnit = city.displayUnit)

        return pickHigherTemperature(summaryCandidate, currentCandidate)
    }

    private fun findSummaryHighTempActual(
        pageText: String,
        preferredUnit: TempUnit
    ): Pair<Double, TempUnit>? {
        val normalized = pageText.replace(Regex("\\s+"), " ").trim()
        val normalizedSummaryRegex = Regex(
            "Temperature\\s*\\(\\s*(?:°|º)?\\s*([CF])\\s*\\)\\s*Actual\\s*Historic\\s*Avg\\s*High\\s*Temp\\s*(-?\\d+(?:\\.\\d+)?)",
            RegexOption.IGNORE_CASE
        )
        normalizedSummaryRegex.find(normalized)?.let { match ->
            val value = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return@let
            val unit = when (match.groupValues.getOrNull(1)?.uppercase()) {
                "F" -> TempUnit.F
                else -> TempUnit.C
            }
            if (isPlausible(value, unit)) return value to unit
        }

        val unitMatch = Regex(
            "Temperature\\s*\\(\\s*(?:°|º)?\\s*([CF])\\s*\\)",
            RegexOption.IGNORE_CASE
        ).find(pageText)

        val headerUnit = unitMatch?.groupValues?.getOrNull(1)?.uppercase()?.let {
            if (it == "F") TempUnit.F else TempUnit.C
        }
        val highTempIndex = pageText.indexOf("High Temp", ignoreCase = true)
        if (highTempIndex < 0) return null

        val sliceEnd = (highTempIndex + 220).coerceAtMost(pageText.length)
        val slice = pageText.substring(highTempIndex, sliceEnd)

        val explicitRegex = Regex(
            "High\\s*Temp(?:\\s*Actual)?\\s*(-?\\d+(?:\\.\\d+)?)\\s*(?:°|º)?\\s*([CF])?",
            RegexOption.IGNORE_CASE
        )
        explicitRegex.find(slice)?.let { match ->
            val value = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return@let
            val unit = when (match.groupValues.getOrNull(2)?.uppercase()) {
                "F" -> TempUnit.F
                "C" -> TempUnit.C
                else -> headerUnit ?: inferUnitFromValue(value, preferredUnit)
            }
            if (isPlausible(value, unit)) return value to unit
        }

        // Some variants render "High Temp Actual Average 8 6".
        val numberRegex = Regex("(-?\\d+(?:\\.\\d+)?)")
        val firstNumber = numberRegex.find(slice)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return null
        val unit = headerUnit ?: inferUnitFromValue(firstNumber, preferredUnit)
        if (!isPlausible(firstNumber, unit)) return null
        return firstNumber to unit
    }

    private fun pickHigherTemperature(
        first: Pair<Double, TempUnit>?,
        second: Pair<Double, TempUnit>?
    ): Pair<Double, TempUnit>? {
        if (first == null) return second
        if (second == null) return first
        val firstC = if (first.second == TempUnit.C) first.first else fahrenheitToCelsius(first.first)
        val secondC = if (second.second == TempUnit.C) second.first else fahrenheitToCelsius(second.first)
        return if (firstC >= secondC) first else second
    }

    private fun findEmbeddedObservationHighTemp(
        text: String,
        city: CityConfig,
        key: String
    ): Pair<Double, TempUnit>? {
        val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val candidates = regex.findAll(text)
            .mapNotNull { match ->
                val value = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                val contextStart = (match.range.first - 1200).coerceAtLeast(0)
                val contextEnd = (match.range.last + 1200).coerceAtMost(text.length - 1)
                val context = text.substring(contextStart, contextEnd + 1)
                val unit = parseUnitFromContext(context) ?: inferUnitFromValue(value, city.displayUnit)
                if (!isPlausible(value, unit)) return@mapNotNull null
                val score = buildScore(context, city.metarCode, unit)
                ObservationMaxCandidate(value, unit, score)
            }
            .toList()

        if (candidates.isEmpty()) return null
        val preferred = candidates.maxByOrNull { it.score } ?: return null
        return preferred.value to preferred.unit
    }

    private suspend fun fetchObservationMaxFromApi(
        html: String,
        city: CityConfig
    ): Pair<Double, TempUnit>? {
        val urlsToTry = buildList {
            addAll(extractObservationApiUrls(html = html, metarCode = city.metarCode))
            addAll(buildFallbackObservationUrls(city))
        }.distinct()

        for (url in urlsToTry) {
            repeat(API_FETCH_RETRIES) { retry ->
                val response = runCatching {
                    httpClient.get(
                        url = url,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Android) PolyMeteo/1.0",
                            "Accept" to "application/json,text/plain,*/*",
                            "Referer" to city.wundergroundControlUrl,
                            "Origin" to "https://www.wunderground.com"
                        )
                    )
                }.getOrNull()

                if (response != null && response.code in 200..299) {
                    parseObservationJson(
                        rawJson = response.body,
                        requestUrl = url,
                        preferredUnit = city.displayUnit
                    )?.let { return it }
                }

                if (retry < API_FETCH_RETRIES - 1) delay(RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun extractObservationApiUrls(html: String, metarCode: String): List<String> {
        val normalized = html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val genericRegex = Regex(
            "https://api\\.weather\\.com/v3/wx/observations/current\\?[^\"'\\s>]*",
            RegexOption.IGNORE_CASE
        )
        return genericRegex.findAll(normalized)
            .map { it.value }
            .distinct()
            .sortedByDescending { candidate ->
                when {
                    candidate.contains("icaoCode=$metarCode", ignoreCase = true) -> 2
                    candidate.contains("icaoCode=", ignoreCase = true) -> 1
                    else -> 0
                }
            }
            .toList()
    }

    private fun buildFallbackObservationUrls(city: CityConfig): List<String> {
        val base = "https://api.weather.com/v3/wx/observations/current"
        val icaoE = "$base?apiKey=$WEATHER_COM_PUBLIC_KEY&language=en-US&units=e&format=json&icaoCode=${city.metarCode}"
        val icaoM = "$base?apiKey=$WEATHER_COM_PUBLIC_KEY&language=en-US&units=m&format=json&icaoCode=${city.metarCode}"
        val geo = "${city.latitude},${city.longitude}"
        val geoE = "$base?apiKey=$WEATHER_COM_PUBLIC_KEY&language=en-US&units=e&format=json&geocode=$geo"
        val geoM = "$base?apiKey=$WEATHER_COM_PUBLIC_KEY&language=en-US&units=m&format=json&geocode=$geo"
        return listOf(icaoE, icaoM, geoE, geoM)
    }

    private fun parseObservationJson(
        rawJson: String,
        requestUrl: String,
        preferredUnit: TempUnit
    ): Pair<Double, TempUnit>? {
        val root = runCatching {
            json.parseToJsonElement(rawJson) as? JsonObject
        }.getOrNull() ?: return null
        val payload = root.objOrNull("value") ?: root

        var maxValue: Double? = null
        for (key in listOf("temperatureMaxSince7Am", "temperatureMax24Hour", "temperatureMax")) {
            val value = payload.doubleOrNull(key)
            if (value != null) {
                maxValue = value
                break
            }
        }
        val value = maxValue ?: return null

        val unit = when {
            requestUrl.contains("units=e", ignoreCase = true) -> TempUnit.F
            requestUrl.contains("units=m", ignoreCase = true) -> TempUnit.C
            else -> inferUnitFromValue(value, preferredUnit)
        }
        if (!isPlausible(value, unit)) return null
        return value to unit
    }

    private fun findHighTempAfterKeyword(
        text: String,
        preferredUnit: TempUnit
    ): Pair<Double, TempUnit>? {
        val index = text.indexOf("High Temp", ignoreCase = true)
        if (index < 0) return null
        val slice = text.substring(index, (index + 260).coerceAtMost(text.length))
        val firstNumber = Regex("(-?\\d+(?:\\.\\d+)?)").find(slice)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: return null
        val unit = inferUnitFromValue(firstNumber, preferredUnit)
        if (!isPlausible(firstNumber, unit)) return null
        return firstNumber to unit
    }

    private fun findAnyEmbeddedMaxTemperature(
        text: String,
        preferredUnit: TempUnit
    ): Pair<Double, TempUnit>? {
        val regex = Regex(
            "\"(temperatureMaxSince7Am|temperatureMax24Hour|temperatureMax)\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)",
            RegexOption.IGNORE_CASE
        )
        val candidates = regex.findAll(text)
            .mapNotNull { match ->
                val value = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return@mapNotNull null
                val key = match.groupValues.getOrNull(1).orEmpty()
                val contextStart = (match.range.first - 120).coerceAtLeast(0)
                val contextEnd = (match.range.last + 120).coerceAtMost(text.length - 1)
                val context = text.substring(contextStart, contextEnd + 1)
                val unit = parseUnitFromContext(context) ?: inferUnitFromValue(value, preferredUnit)
                if (!isPlausible(value, unit)) return@mapNotNull null
                var score = 0
                if (key.equals("temperatureMaxSince7Am", ignoreCase = true)) score += 3
                if (key.equals("temperatureMax24Hour", ignoreCase = true)) score += 2
                if (parseUnitFromContext(context) != null) score += 2
                ObservationMaxCandidate(value, unit, score)
            }
            .toList()
        val best = candidates.maxByOrNull { it.score } ?: return null
        return best.value to best.unit
    }

    private fun findAnyEmbeddedCurrentTemperature(
        text: String,
        preferredUnit: TempUnit
    ): Pair<Double, TempUnit>? {
        val regex = Regex("\"temperature\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: return null
        val value = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val contextStart = (match.range.first - 120).coerceAtLeast(0)
        val contextEnd = (match.range.last + 120).coerceAtMost(text.length - 1)
        val context = text.substring(contextStart, contextEnd + 1)
        val unit = parseUnitFromContext(context) ?: inferUnitFromValue(value, preferredUnit)
        if (!isPlausible(value, unit)) return null
        return value to unit
    }

    private fun parseUnitFromContext(context: String): TempUnit? {
        return when {
            context.contains("units=e", ignoreCase = true) -> TempUnit.F
            context.contains("units=m", ignoreCase = true) -> TempUnit.C
            else -> null
        }
    }

    private fun buildScore(context: String, metarCode: String, unit: TempUnit): Int {
        var score = 0
        if (context.contains("icaoCode=$metarCode", ignoreCase = true)) score += 5
        if (context.contains("\"icaoCode\":\"$metarCode\"", ignoreCase = true)) score += 5
        if (context.contains("v3/wx/observations/current", ignoreCase = true)) score += 2
        if (context.contains("temperatureMaxSince7Am", ignoreCase = true)) score += 1
        if (context.contains("units=", ignoreCase = true)) score += 1
        if (unit == TempUnit.C || unit == TempUnit.F) score += 1
        return score
    }

    private fun inferUnitFromValue(value: Double, preferredUnit: TempUnit): TempUnit {
        return when {
            value > 60.0 -> TempUnit.F
            value < -45.0 -> TempUnit.C
            else -> preferredUnit
        }
    }

    private fun isPlausible(value: Double, unit: TempUnit): Boolean {
        return if (unit == TempUnit.C) {
            value in -80.0..65.0
        } else {
            value in -110.0..160.0
        }
    }

    private data class ObservationMaxCandidate(
        val value: Double,
        val unit: TempUnit,
        val score: Int
    )

    companion object {
        // Public key observed in WU embedded calls; used only when page parsing fails.
        private const val WEATHER_COM_PUBLIC_KEY = "e1f10a1e78da46f5b10a1e78da96f525"
        private const val HTML_FETCH_RETRIES = 2
        private const val API_FETCH_RETRIES = 2
        private const val RETRY_DELAY_MS = 300L
    }
}
