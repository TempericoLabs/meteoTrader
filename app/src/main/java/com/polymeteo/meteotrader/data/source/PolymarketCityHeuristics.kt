package com.polymeteo.meteotrader.data.source

import com.polymeteo.meteotrader.data.model.CityConfig
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object PolymarketCityHeuristics {

    data class Aliases(
        val slugAliases: List<String>,
        val questionAliases: List<String>,
        val searchAliases: List<String>
    )

    private data class Override(
        val slugAliases: List<String> = emptyList(),
        val questionAliases: List<String> = emptyList(),
        val searchAliases: List<String> = emptyList()
    )

    private val overridesByCityId: Map<String, Override> = mapOf(
        "new-york" to Override(
            slugAliases = listOf("nyc", "new-york", "new-york-city"),
            questionAliases = listOf("new york city", "new york", "nyc"),
            searchAliases = listOf("NYC", "New York City", "New York")
        )
    )

    fun aliases(city: CityConfig): Aliases {
        val normalizedCityId = normalizeCityId(city.id)
        val override = overridesByCityId[normalizedCityId] ?: Override()

        val baseSlugAliases = linkedSetOf(
            slugify(city.name),
            slugify(city.id.replace('-', ' '))
        )
        baseSlugAliases += override.slugAliases.map(::slugify)

        val baseQuestionAliases = linkedSetOf(
            normalizePhrase(city.name),
            normalizePhrase(city.id.replace('-', ' '))
        )
        baseQuestionAliases += override.questionAliases.map(::normalizePhrase)

        val baseSearchAliases = linkedSetOf(
            city.name,
            city.id.replace('-', ' ')
                .split(' ')
                .joinToString(" ") { token ->
                    token.replaceFirstChar { ch ->
                        if (ch.isLowerCase()) ch.titlecase(Locale.US) else ch.toString()
                    }
                }
        )
        baseSearchAliases += override.searchAliases

        return Aliases(
            slugAliases = baseSlugAliases.filter { it.isNotBlank() },
            questionAliases = baseQuestionAliases.filter { it.isNotBlank() },
            searchAliases = baseSearchAliases.filter { it.isNotBlank() }
        )
    }

    fun buildEventSlugCandidates(city: CityConfig, date: LocalDate): List<String> {
        val monthSlug = date
            .format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
            .lowercase(Locale.US)
        return aliases(city).slugAliases.map { citySlug ->
            "highest-temperature-in-$citySlug-on-$monthSlug-${date.dayOfMonth}-${date.year}"
        }
    }

    fun buildSearchQueries(city: CityConfig): List<String> {
        return aliases(city).searchAliases
            .map { alias -> "highest temperature in $alias" }
            .distinct()
    }

    fun questionMatchesCity(question: String, city: CityConfig): Boolean {
        val normalizedQuestion = normalizePhrase(question)
        return aliases(city).questionAliases.any { alias ->
            normalizedQuestion.contains(alias)
        }
    }

    private fun normalizeCityId(cityId: String): String {
        return normalizePhrase(cityId).replace(' ', '-')
    }

    private fun slugify(raw: String): String {
        return normalizePhrase(raw).replace(' ', '-')
    }

    private fun normalizePhrase(raw: String): String {
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
