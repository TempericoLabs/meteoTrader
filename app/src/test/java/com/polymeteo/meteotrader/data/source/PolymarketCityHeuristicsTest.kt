package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.CityCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PolymarketCityHeuristicsTest {

    @Test
    fun nycAliasesIncludeCanonicalSlugAndQuestionVariants() {
        val city = CityCatalog.findById("new-york") ?: error("NYC city missing in catalog")
        val date = LocalDate.of(2026, 2, 19)

        val slugCandidates = PolymarketCityHeuristics.buildEventSlugCandidates(city, date)
        assertTrue(
            "NYC slug must be present for direct event lookup",
            slugCandidates.contains("highest-temperature-in-nyc-on-february-19-2026")
        )
        assertTrue(
            "Legacy New York slug should remain as fallback",
            slugCandidates.contains("highest-temperature-in-new-york-on-february-19-2026")
        )

        assertTrue(
            PolymarketCityHeuristics.questionMatchesCity(
                question = "Will the highest temperature in New York City be between 38-39°F on February 19?",
                city = city
            )
        )
        assertTrue(
            PolymarketCityHeuristics.questionMatchesCity(
                question = "Will the highest temperature in NYC be 40°F or higher on February 19?",
                city = city
            )
        )
    }

    @Test
    fun allCitiesExposeNonEmptyAliasSets() {
        CityCatalog.cities.forEach { city ->
            val aliases = PolymarketCityHeuristics.aliases(city)
            assertFalse("Slug aliases missing for ${city.id}", aliases.slugAliases.isEmpty())
            assertFalse("Question aliases missing for ${city.id}", aliases.questionAliases.isEmpty())
            assertFalse("Search aliases missing for ${city.id}", aliases.searchAliases.isEmpty())
        }
    }
}
