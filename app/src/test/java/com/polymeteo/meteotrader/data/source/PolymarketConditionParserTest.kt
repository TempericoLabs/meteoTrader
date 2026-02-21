package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.MarketConditionType
import com.polymeteo.meteotrader.data.model.TempUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PolymarketConditionParserTest {

    @Test
    fun parseExactCondition_withExplicitYear() {
        val condition = PolymarketConditionParser.parseConditionFromQuestion(
            question = "Will the highest temperature in London be 7°C on February 19, 2026?",
            cityZoneId = "Europe/London",
            referenceDate = LocalDate.of(2026, 2, 19)
        )

        assertNotNull(condition)
        assertEquals(MarketConditionType.EXACT, condition?.type)
        assertEquals(7.0, condition?.threshold ?: 0.0, 0.0001)
        assertEquals(TempUnit.C, condition?.unit)
        assertEquals(LocalDate.of(2026, 2, 19), condition?.targetDate)
    }

    @Test
    fun parseBetweenCondition_sortsRangeAndParsesOrdinalDate() {
        val condition = PolymarketConditionParser.parseConditionFromQuestion(
            question = "Will the highest temperature in NYC be between 39-38°F on February 19th?",
            cityZoneId = "America/New_York",
            referenceDate = LocalDate.of(2026, 2, 18)
        )

        assertNotNull(condition)
        assertEquals(MarketConditionType.BETWEEN, condition?.type)
        assertEquals(38.0, condition?.threshold ?: 0.0, 0.0001)
        assertEquals(39.0, condition?.upperThreshold ?: 0.0, 0.0001)
        assertEquals(TempUnit.F, condition?.unit)
        assertEquals(LocalDate.of(2026, 2, 19), condition?.targetDate)
    }

    @Test
    fun parseMonthDayDate_picksNearestYearToReferenceDate() {
        val parsed = PolymarketConditionParser.parseMonthDayDate(
            raw = "February 1",
            cityZoneId = "Europe/London",
            referenceDate = LocalDate.of(2026, 12, 31)
        )

        assertEquals(LocalDate.of(2027, 2, 1), parsed)
    }

    @Test
    fun parseCondition_returnsNullWhenPatternDoesNotMatch() {
        val condition = PolymarketConditionParser.parseConditionFromQuestion(
            question = "Will it rain in London tomorrow?",
            cityZoneId = "Europe/London",
            referenceDate = LocalDate.of(2026, 2, 19)
        )
        assertNull(condition)
    }
}
