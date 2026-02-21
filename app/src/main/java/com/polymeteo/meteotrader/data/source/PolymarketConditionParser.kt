package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.MarketConditionType
import com.polymeteo.meteotrader.data.model.MarketRangeCondition
import com.polymeteo.meteotrader.data.model.TempUnit
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object PolymarketConditionParser {

    private val geRegex = Regex(
        """be\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*or\s*higher\s*on\s*([A-Za-z]+\s+\d{1,2}(?:st|nd|rd|th)?(?:,\s*\d{4})?)""",
        RegexOption.IGNORE_CASE
    )
    private val leRegex = Regex(
        """be\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*or\s*below\s*on\s*([A-Za-z]+\s+\d{1,2}(?:st|nd|rd|th)?(?:,\s*\d{4})?)""",
        RegexOption.IGNORE_CASE
    )
    private val betweenRegex = Regex(
        """be\s*between\s*(-?\d+(?:\.\d+)?)\s*[-–]\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*on\s*([A-Za-z]+\s+\d{1,2}(?:st|nd|rd|th)?(?:,\s*\d{4})?)""",
        RegexOption.IGNORE_CASE
    )
    private val exactRegex = Regex(
        """be\s*(-?\d+(?:\.\d+)?)\s*[°º]?\s*([CF])\s*on\s*([A-Za-z]+\s+\d{1,2}(?:st|nd|rd|th)?(?:,\s*\d{4})?)""",
        RegexOption.IGNORE_CASE
    )

    fun parseConditionFromQuestion(
        question: String,
        cityZoneId: String,
        referenceDate: LocalDate? = null
    ): MarketRangeCondition? {
        geRegex.find(question)?.let {
            return buildCondition(
                match = it,
                type = MarketConditionType.GREATER_OR_EQUAL,
                cityZoneId = cityZoneId,
                referenceDate = referenceDate
            )
        }
        leRegex.find(question)?.let {
            return buildCondition(
                match = it,
                type = MarketConditionType.LESS_OR_EQUAL,
                cityZoneId = cityZoneId,
                referenceDate = referenceDate
            )
        }
        betweenRegex.find(question)?.let {
            return buildBetweenCondition(
                match = it,
                cityZoneId = cityZoneId,
                referenceDate = referenceDate
            )
        }
        exactRegex.find(question)?.let {
            return buildCondition(
                match = it,
                type = MarketConditionType.EXACT,
                cityZoneId = cityZoneId,
                referenceDate = referenceDate
            )
        }
        return null
    }

    fun parseMonthDayDate(
        raw: String,
        cityZoneId: String,
        referenceDate: LocalDate? = null
    ): LocalDate? {
        val zoneId = ZoneId.of(cityZoneId)
        val today = referenceDate ?: LocalDate.now(zoneId)
        val value = raw.trim()
            .replace(Regex("(?i)(\\d{1,2})(st|nd|rd|th)"), "$1")
            .replace(",", " ")
            .replace(Regex("\\s+"), " ")

        val formattersWithYear = listOf(
            DateTimeFormatter.ofPattern("MMMM d uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d uuuu", Locale.ENGLISH)
        )
        val formattersWithoutYear = listOf(
            DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
        )

        val candidates = mutableListOf<LocalDate>()
        val yearsToTry = listOf(today.year - 1, today.year, today.year + 1)

        formattersWithYear.forEach { formatter ->
            val parsed = runCatching { LocalDate.parse(value, formatter) }.getOrNull()
            if (parsed != null) {
                candidates += parsed
            }
        }

        formattersWithoutYear.forEach { formatter ->
            val parsed = runCatching { LocalDate.parse(value, formatter) }.getOrNull()
            if (parsed != null) {
                yearsToTry.forEach { year ->
                    candidates += parsed.withYear(year)
                }
            }
        }

        if (candidates.isEmpty()) {
            formattersWithYear.forEach { formatter ->
                yearsToTry.forEach { year ->
                    val withYear = "$value $year"
                    val parsed = runCatching { LocalDate.parse(withYear, formatter) }.getOrNull()
                    if (parsed != null) {
                        candidates += parsed
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { candidate ->
            abs(ChronoUnit.DAYS.between(today, candidate))
        }
    }

    private fun buildCondition(
        match: MatchResult,
        type: MarketConditionType,
        cityZoneId: String,
        referenceDate: LocalDate?
    ): MarketRangeCondition? {
        val threshold = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val unit = if (match.groupValues.getOrNull(2).equals("F", ignoreCase = true)) TempUnit.F else TempUnit.C
        val rawDate = match.groupValues.getOrNull(3).orEmpty()

        return MarketRangeCondition(
            type = type,
            threshold = threshold,
            upperThreshold = null,
            unit = unit,
            targetDate = parseMonthDayDate(
                raw = rawDate,
                cityZoneId = cityZoneId,
                referenceDate = referenceDate
            )
        )
    }

    private fun buildBetweenCondition(
        match: MatchResult,
        cityZoneId: String,
        referenceDate: LocalDate?
    ): MarketRangeCondition? {
        val first = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val second = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
        val lower = min(first, second)
        val upper = max(first, second)
        val unit = if (match.groupValues.getOrNull(3).equals("F", ignoreCase = true)) TempUnit.F else TempUnit.C
        val rawDate = match.groupValues.getOrNull(4).orEmpty()

        return MarketRangeCondition(
            type = MarketConditionType.BETWEEN,
            threshold = lower,
            upperThreshold = upper,
            unit = unit,
            targetDate = parseMonthDayDate(
                raw = rawDate,
                cityZoneId = cityZoneId,
                referenceDate = referenceDate
            )
        )
    }
}
