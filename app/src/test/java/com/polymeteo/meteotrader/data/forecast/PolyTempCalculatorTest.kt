package com.polymeteo.meteotrader.data.forecast

import com.polymeteo.meteotrader.data.model.ForecastSourceResult
import com.polymeteo.meteotrader.data.model.SourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolyTempCalculatorTest {

    @Test
    fun compute_discardsOutlierAndKeepsCentralCluster() {
        val forecasts = listOf(
            forecast("a", 20.0),
            forecast("b", 21.0),
            forecast("c", 19.0),
            forecast("d", 50.0)
        )

        val result = PolyTempCalculator.compute(
            forecasts = forecasts,
            dynamicWeights = emptyMap()
        )

        assertTrue((result.polyTempC ?: 0.0) < 25.0)
        assertTrue(result.warnings.any { warning -> warning.contains("outliers", ignoreCase = true) })
    }

    @Test
    fun compute_appliesDominantBlendWhenOneModelWeightClearlyLeads() {
        val forecasts = listOf(
            forecast("openmeteo-ifs", 10.0),
            forecast("openmeteo-ifs025", 30.0)
        )

        val plain = PolyTempCalculator.compute(
            forecasts = forecasts,
            dynamicWeights = emptyMap()
        )
        val dominant = PolyTempCalculator.compute(
            forecasts = forecasts,
            dynamicWeights = mapOf(
                "openmeteo-ifs" to 10.0,
                "openmeteo-ifs025" to 1.0
            )
        )

        assertEquals(20.0, plain.polyTempC ?: 0.0, 0.5)
        assertTrue((dominant.polyTempC ?: 0.0) < 15.0)
    }

    @Test
    fun compute_returnsNullWhenNoValidForecasts() {
        val forecasts = listOf(
            ForecastSourceResult(
                sourceId = "x",
                sourceName = "invalid",
                status = SourceStatus.ERROR,
                maxTempC = null,
                maxTempF = null,
                currentTempC = null,
                currentTempF = null,
                error = "network"
            )
        )

        val result = PolyTempCalculator.compute(
            forecasts = forecasts,
            dynamicWeights = emptyMap()
        )

        assertEquals(null, result.polyTempC)
        assertTrue(result.warnings.any { warning -> warning.contains("sin fuentes válidas", ignoreCase = true) })
    }

    private fun forecast(sourceId: String, maxTempC: Double): ForecastSourceResult {
        return ForecastSourceResult(
            sourceId = sourceId,
            sourceName = sourceId,
            status = SourceStatus.SUCCESS,
            maxTempC = maxTempC,
            maxTempF = null,
            currentTempC = null,
            currentTempF = null,
            error = null
        )
    }
}
