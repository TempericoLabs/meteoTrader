package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.TafSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.math.abs

class TafSource(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun fetch(city: CityConfig): TafSnapshot {
        val sourceUrl = "https://aviationweather.gov/api/data/metar?ids=${city.metarCode}&format=json&hours=24&taf=1"
        return runCatching {
            val response = httpClient.get(sourceUrl)
            if (response.code == 204) {
                return TafSnapshot(
                    sourceUrl = sourceUrl,
                    issuedAt = null,
                    validFrom = null,
                    validTo = null,
                    rawText = null,
                    summary = null,
                    error = "TAF sin datos actuales"
                )
            }
            if (response.code !in 200..299) {
                return TafSnapshot(
                    sourceUrl = sourceUrl,
                    issuedAt = null,
                    validFrom = null,
                    validTo = null,
                    rawText = null,
                    summary = null,
                    error = "HTTP ${response.code}"
                )
            }

            val taf = parseLatestTaf(response.body)
            if (taf == null) {
                return TafSnapshot(
                    sourceUrl = sourceUrl,
                    issuedAt = null,
                    validFrom = null,
                    validTo = null,
                    rawText = null,
                    summary = null,
                    error = "TAF sin lecturas"
                )
            }

            TafSnapshot(
                sourceUrl = sourceUrl,
                issuedAt = taf.issuedAt,
                validFrom = taf.validFrom,
                validTo = taf.validTo,
                rawText = taf.rawText,
                summary = buildSummary(taf.rawText),
                error = null
            )
        }.getOrElse { throwable ->
            TafSnapshot(
                sourceUrl = sourceUrl,
                issuedAt = null,
                validFrom = null,
                validTo = null,
                rawText = null,
                summary = null,
                error = throwable.message ?: "Error desconocido en TAF"
            )
        }
    }

    private fun parseLatestTaf(rawJson: String): ParsedTaf? {
        val root = json.parseToJsonElement(rawJson)
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val data = root["data"]
                if (data is JsonArray) data else JsonArray(emptyList())
            }
            else -> JsonArray(emptyList())
        }

        return array
            .mapNotNull { parseTafRow(it) }
            .maxByOrNull { parsed ->
                parsed.issuedAt?.epochSecond
                    ?: parsed.validFrom?.epochSecond
                    ?: parsed.validTo?.epochSecond
                    ?: Long.MIN_VALUE
            }
    }

    private fun parseTafRow(element: JsonElement): ParsedTaf? {
        val obj = element as? JsonObject ?: return null
        val raw = obj.stringOrNull("rawTaf")
            ?: obj.stringOrNull("rawTAF")
            ?: obj.stringOrNull("raw_text")
            ?: obj.stringOrNull("raw")

        val issuedAtFromFields = obj.instantFromKeys(
            "issueTime",
            "tafIssueTime",
            "bulletinTime",
            "reportTime",
            "receiptTime",
            "obsTime"
        )

        val validFromFromFields = obj.instantFromKeys(
            "validTimeFrom",
            "valid_time_from",
            "fcstTimeFrom",
            "fcst_time_from",
            "startTime",
            "start_time"
        )

        val validToFromFields = obj.instantFromKeys(
            "validTimeTo",
            "valid_time_to",
            "fcstTimeTo",
            "fcst_time_to",
            "endTime",
            "end_time"
        )

        if (raw.isNullOrBlank()) {
            return null
        }

        val rawReference = issuedAtFromFields
            ?: validFromFromFields
            ?: validToFromFields
            ?: Instant.now()
        val parsedFromRaw = parseFromRawTaf(raw, rawReference)

        return ParsedTaf(
            rawText = raw,
            issuedAt = parsedFromRaw.issuedAt ?: issuedAtFromFields,
            validFrom = parsedFromRaw.validFrom ?: validFromFromFields,
            validTo = parsedFromRaw.validTo ?: validToFromFields
        )
    }

    private fun JsonObject.instantFromKeys(vararg keys: String): Instant? {
        keys.forEach { key ->
            stringOrNull(key)?.let { candidate ->
                runCatching { Instant.parse(candidate) }.getOrNull()?.let { return it }
            }
            longOrNull(key)?.let { epoch ->
                val instant = if (epoch > 100_000_000_000L) {
                    Instant.ofEpochMilli(epoch)
                } else {
                    Instant.ofEpochSecond(epoch)
                }
                return instant
            }
        }
        return null
    }

    private fun parseFromRawTaf(rawTaf: String, reference: Instant): ParsedTimes {
        val normalized = rawTaf.uppercase()
        val mainPattern = Regex("\\bTAF(?:\\s+AMD|\\s+COR|\\s+RTD)?\\s+[A-Z0-9]{4}\\s+(\\d{6})Z\\s+(\\d{4})/(\\d{4})")
        val mainMatch = mainPattern.find(normalized)

        val issueToken = mainMatch?.groupValues?.getOrNull(1)
            ?: Regex("\\b(\\d{6})Z\\b").find(normalized)?.groupValues?.getOrNull(1)

        val validFromToken: String?
        val validToToken: String?
        if (mainMatch != null) {
            validFromToken = mainMatch.groupValues.getOrNull(2)
            validToToken = mainMatch.groupValues.getOrNull(3)
        } else {
            val fallback = Regex("\\b(\\d{4})/(\\d{4})\\b").find(normalized)
            validFromToken = fallback?.groupValues?.getOrNull(1)
            validToToken = fallback?.groupValues?.getOrNull(2)
        }

        val issuedAt = issueToken?.let { parseDayHourMinuteToken(it, reference) }
        val validFrom = validFromToken?.let { parseDayHourToken(it, issuedAt ?: reference) }
        val validTo = validToToken?.let { parseDayHourToken(it, validFrom ?: issuedAt ?: reference, validFrom) }

        return ParsedTimes(
            issuedAt = issuedAt,
            validFrom = validFrom,
            validTo = validTo
        )
    }

    private fun parseDayHourMinuteToken(token: String, reference: Instant): Instant? {
        if (token.length != 6) return null
        val day = token.substring(0, 2).toIntOrNull() ?: return null
        val hour = token.substring(2, 4).toIntOrNull() ?: return null
        val minute = token.substring(4, 6).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return resolveDayTime(
            day = day,
            hour = hour,
            minute = minute,
            reference = reference
        )
    }

    private fun parseDayHourToken(token: String, reference: Instant, notBefore: Instant? = null): Instant? {
        if (token.length != 4) return null
        val day = token.substring(0, 2).toIntOrNull() ?: return null
        val hour = token.substring(2, 4).toIntOrNull() ?: return null
        if (hour !in 0..23) return null
        return resolveDayTime(
            day = day,
            hour = hour,
            minute = 0,
            reference = reference,
            notBefore = notBefore
        )
    }

    private fun resolveDayTime(
        day: Int,
        hour: Int,
        minute: Int,
        reference: Instant,
        notBefore: Instant? = null
    ): Instant? {
        val referenceUtc = reference.atOffset(ZoneOffset.UTC)
        val months = listOf(
            YearMonth.from(referenceUtc.minusMonths(1)),
            YearMonth.from(referenceUtc),
            YearMonth.from(referenceUtc.plusMonths(1))
        )

        val candidates = months.mapNotNull { ym ->
            if (day > ym.lengthOfMonth()) {
                null
            } else {
                runCatching {
                    LocalDateTime.of(ym.year, ym.monthValue, day, hour, minute)
                        .toInstant(ZoneOffset.UTC)
                }.getOrNull()
            }
        }

        if (candidates.isEmpty()) return null

        val eligible = if (notBefore == null) {
            candidates
        } else {
            candidates.filter { !it.isBefore(notBefore) }
        }
        val pool = if (eligible.isNotEmpty()) eligible else candidates
        return pool.minByOrNull { candidate ->
            abs(Duration.between(reference, candidate).seconds)
        }
    }

    private fun buildSummary(rawTaf: String?): String? {
        if (rawTaf.isNullOrBlank()) return null
        val raw = rawTaf.uppercase()
        val hazards = buildList {
            if (Regex("\\b(TS|TSRA|VCTS|TSGR)\\b").containsMatchIn(raw)) add("tormenta")
            if (Regex("\\b(SN|SHSN|BLSN|FZRA|PL)\\b").containsMatchIn(raw)) add("nieve/hielo")
            if (Regex("\\b(FG|BR|HZ|FU|VA|DU)\\b").containsMatchIn(raw)) add("visibilidad reducida")
            if (Regex("\\bWS\\d{3}/\\d{2,3}KT\\b").containsMatchIn(raw)) add("wind shear")
        }
        val changeGroups = Regex("\\b(BECMG|TEMPO|PROB30|PROB40|FM\\d{6})\\b")
            .findAll(raw)
            .map { match -> match.value }
            .distinct()
            .toList()

        return buildString {
            if (hazards.isNotEmpty()) {
                append("Riesgo ")
                append(hazards.joinToString(", "))
                if (changeGroups.isNotEmpty()) append(" | ")
            }
            if (changeGroups.isNotEmpty()) {
                append("Cambios ")
                append(changeGroups.take(4).joinToString(" "))
            }
        }.ifBlank { "TAF disponible" }
    }

    private data class ParsedTaf(
        val rawText: String?,
        val issuedAt: Instant?,
        val validFrom: Instant?,
        val validTo: Instant?
    )

    private data class ParsedTimes(
        val issuedAt: Instant?,
        val validFrom: Instant?,
        val validTo: Instant?
    )
}
