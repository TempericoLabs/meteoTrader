package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.ControlStationSnapshot
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.util.celsiusToFahrenheit
import com.polymeteo.meteotrader.util.fahrenheitToCelsius
import org.jsoup.Jsoup

class WundergroundSource(
    private val httpClient: HttpClient
) {

    suspend fun fetch(city: CityConfig): ControlStationSnapshot {
        val attempts = listOf(city.wundergroundControlUrl, city.wundergroundPwsUrl)
        val errors = mutableListOf<String>()

        for (url in attempts) {
            runCatching {
                val response = httpClient.get(
                    url = url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Android) PolyMeteo/1.0",
                        "Accept" to "text/html,application/xhtml+xml"
                    )
                )
                if (response.code !in 200..299) {
                    errors += "${url.take(48)}... HTTP ${response.code}"
                    return@runCatching null
                }

                val parseResult = extractTemperature(response.body)
                if (parseResult == null) {
                    errors += "${url.take(48)}... sin temperatura detectable"
                    return@runCatching null
                }

                val (value, unit) = parseResult
                val tempC = if (unit == TempUnit.C) value else fahrenheitToCelsius(value)
                val tempF = if (unit == TempUnit.F) value else celsiusToFahrenheit(value)
                ControlStationSnapshot(
                    sourceUrl = response.url,
                    tempC = tempC,
                    tempF = tempF,
                    error = null
                )
            }.getOrNull()?.let { snapshot ->
                return snapshot
            }
        }

        return ControlStationSnapshot(
            sourceUrl = null,
            tempC = null,
            tempF = null,
            error = if (errors.isEmpty()) "Wunderground sin respuesta" else errors.joinToString(" | ")
        )
    }

    private fun extractTemperature(html: String): Pair<Double, TempUnit>? {
        val document = Jsoup.parse(html)
        val scriptsJoined = buildString {
            document.select("script").forEach { append(it.data()) }
        }
        val pageText = document.text()

        findMetricImperialPair(scriptsJoined)?.let { return it }
        findExplicitUnit(scriptsJoined)?.let { return it }
        findExplicitUnit(pageText)?.let { return it }
        findLooseNumber(scriptsJoined)?.let { return it }
        return findLooseNumber(pageText)
    }

    private fun findMetricImperialPair(text: String): Pair<Double, TempUnit>? {
        val metricRegex = Regex("\"metric\"\\s*:\\s*\\{[^}]*\"temp\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val imperialRegex = Regex("\"imperial\"\\s*:\\s*\\{[^}]*\"temp\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val metric = metricRegex.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val imperial = imperialRegex.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        return when {
            metric != null -> metric to TempUnit.C
            imperial != null -> imperial to TempUnit.F
            else -> null
        }
    }

    private fun findExplicitUnit(text: String): Pair<Double, TempUnit>? {
        val regex = Regex(
            "(-?\\d+(?:\\.\\d+)?)\\s*(?:°|º|&deg;)?\\s*([CF])",
            RegexOption.IGNORE_CASE
        )
        return regex.find(text)?.let { match ->
            val value = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
            val unit = if (match.groupValues.getOrNull(2)?.uppercase() == "F") TempUnit.F else TempUnit.C
            if (!isPlausible(value, unit)) return null
            value to unit
        }
    }

    private fun findLooseNumber(text: String): Pair<Double, TempUnit>? {
        val regex = Regex("\"temperature\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        val number = regex.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return null
        val unit = if (number > 65) TempUnit.F else TempUnit.C
        if (!isPlausible(number, unit)) return null
        return number to unit
    }

    private fun isPlausible(value: Double, unit: TempUnit): Boolean {
        return if (unit == TempUnit.C) {
            value in -80.0..65.0
        } else {
            value in -110.0..160.0
        }
    }
}
