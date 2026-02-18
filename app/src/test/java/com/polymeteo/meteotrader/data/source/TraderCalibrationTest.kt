package com.polymeteo.meteotrader.data.source

import org.junit.Assert.assertTrue
import org.junit.Test

class TraderCalibrationTest {

    @Test
    fun defaultProfile_increasesSigmaAndReducesConfidenceByHorizon() {
        val today = TraderCalibration.resolve(cityId = "unknown-city", horizonDays = 0, localHour = 14)
        val tomorrow = TraderCalibration.resolve(cityId = "unknown-city", horizonDays = 1, localHour = 14)
        val dayAfter = TraderCalibration.resolve(cityId = "unknown-city", horizonDays = 2, localHour = 14)

        assertTrue(tomorrow.sigmaMultiplier > today.sigmaMultiplier)
        assertTrue(dayAfter.sigmaMultiplier > tomorrow.sigmaMultiplier)
        assertTrue(today.confidenceMultiplier > tomorrow.confidenceMultiplier)
        assertTrue(tomorrow.confidenceMultiplier > dayAfter.confidenceMultiplier)
    }

    @Test
    fun cityProfiles_changeRiskShape() {
        val miami = TraderCalibration.resolve(cityId = "miami", horizonDays = 1, localHour = 10)
        val seattle = TraderCalibration.resolve(cityId = "seattle", horizonDays = 1, localHour = 10)

        assertTrue(miami.sigmaMultiplier > seattle.sigmaMultiplier)
        assertTrue(miami.confidenceMultiplier < seattle.confidenceMultiplier)
    }

    @Test
    fun timeBand_adjustsTodayUncertainty() {
        val night = TraderCalibration.resolve(cityId = "london", horizonDays = 0, localHour = 3)
        val evening = TraderCalibration.resolve(cityId = "london", horizonDays = 0, localHour = 20)

        assertTrue(night.sigmaMultiplier > evening.sigmaMultiplier)
        assertTrue(night.confidenceMultiplier < evening.confidenceMultiplier)
    }

    @Test
    fun resolve_clampsOutOfRangeInputs() {
        val negativeHorizon = TraderCalibration.resolve(cityId = "ankara", horizonDays = -5, localHour = -1)
        val largeHorizon = TraderCalibration.resolve(cityId = "ankara", horizonDays = 99, localHour = 49)

        assertTrue(negativeHorizon.sigmaMultiplier in 0.70..2.20)
        assertTrue(negativeHorizon.confidenceMultiplier in 0.55..1.25)
        assertTrue(largeHorizon.sigmaMultiplier in 0.70..2.20)
        assertTrue(largeHorizon.confidenceMultiplier in 0.55..1.25)
    }
}
