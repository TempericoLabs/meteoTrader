package com.polymeteo.meteotrader.util

import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.TempUnit

fun CityWeatherData.metarCurrentInUnit(): Double? {
    return if (city.displayUnit == TempUnit.C) metar.current?.tempC else metar.current?.tempF
}

fun CityWeatherData.metarPreviousInUnit(): Double? {
    return if (city.displayUnit == TempUnit.C) metar.previousSameDay?.tempC else metar.previousSameDay?.tempF
}

fun CityWeatherData.metarDeltaInUnit(): Double? {
    val current = metarCurrentInUnit() ?: return null
    val previous = metarPreviousInUnit() ?: return null
    return current - previous
}

fun CityWeatherData.controlTempInUnit(): Double? {
    return if (city.displayUnit == TempUnit.C) controlStation.tempC else controlStation.tempF
}

fun CityWeatherData.polyTempInUnit(): Double? {
    return if (city.displayUnit == TempUnit.C) polyTempC else polyTempF
}

fun CityWeatherData.polyTempPremiumInUnit(): Double? {
    return if (city.displayUnit == TempUnit.C) polyTempPremiumC else polyTempPremiumF
}
