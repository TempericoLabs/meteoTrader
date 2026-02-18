package com.polymeteo.meteotrader.data

import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.TempUnit

object CityCatalog {
    val cities: List<CityConfig> = listOf(
        CityConfig(
            id = "miami",
            name = "Miami",
            metarCode = "KMIA",
            latitude = 25.857,
            longitude = -80.265,
            zoneId = "America/New_York",
            displayUnit = TempUnit.F,
            wundergroundControlUrl = "https://www.wunderground.com/weather/us/fl/miami/KMIA",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/KFLHIALE117?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "london",
            name = "London",
            metarCode = "EGLC",
            latitude = 51.514,
            longitude = 0.037,
            zoneId = "Europe/London",
            displayUnit = TempUnit.C,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/gb/london/EGLC",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/ILONDO288?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "toronto",
            name = "Toronto",
            metarCode = "CYYZ",
            latitude = 43.733,
            longitude = -79.637,
            zoneId = "America/Toronto",
            displayUnit = TempUnit.C,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/ca/mississauga/CYYZ",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/IMISSI113?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "seattle",
            name = "Seattle",
            metarCode = "KSEA",
            latitude = 47.446,
            longitude = -122.276,
            zoneId = "America/Los_Angeles",
            displayUnit = TempUnit.F,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/us/wa/seatac/KSEA",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/KWASEATA17?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "dallas",
            name = "Dallas",
            metarCode = "KDAL",
            latitude = 32.829,
            longitude = -96.878,
            zoneId = "America/Chicago",
            displayUnit = TempUnit.F,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/us/tx/dallas/KDAL",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/KTXDALLA1276?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "wellington",
            name = "Wellington",
            metarCode = "NZWN",
            latitude = -41.325,
            longitude = 174.792,
            zoneId = "Pacific/Auckland",
            displayUnit = TempUnit.C,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/nz/wellington/NZWN",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/IWGNLYAL3?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "ankara",
            name = "Ankara",
            metarCode = "LTAC",
            latitude = 39.931,
            longitude = 32.899,
            zoneId = "Europe/Istanbul",
            displayUnit = TempUnit.C,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/tr/%C3%A7ubuk/LTAC",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/IANKAR46?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "seoul",
            name = "Seoul",
            metarCode = "RKSI",
            latitude = 37.482,
            longitude = 126.523,
            zoneId = "Asia/Seoul",
            displayUnit = TempUnit.C,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/kr/incheon/RKSI",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/IINCHE10?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "new-york",
            name = "New York",
            metarCode = "KLGA",
            latitude = 40.764,
            longitude = -73.835,
            zoneId = "America/New_York",
            displayUnit = TempUnit.F,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/us/ny/new-york-city/KLGA",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/KNYNEWYO1974?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "chicago",
            name = "Chicago",
            metarCode = "KORD",
            latitude = 41.964,
            longitude = -87.946,
            zoneId = "America/Chicago",
            displayUnit = TempUnit.F,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/us/il/chicago/KORD",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/KILBENSE15?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "atlanta",
            name = "Atlanta",
            metarCode = "KATL",
            latitude = 33.661,
            longitude = -84.399,
            zoneId = "America/New_York",
            displayUnit = TempUnit.F,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/us/ga/atlanta/KATL",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/KGAHAPEV1?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "paris",
            name = "Paris",
            metarCode = "LFPG",
            latitude = 48.974,
            longitude = 2.632,
            zoneId = "Europe/Paris",
            displayUnit = TempUnit.C,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/fr/paris/LFPG",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/IMITRY1?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "buenos-aires",
            name = "Buenos Aires",
            metarCode = "SAEZ",
            latitude = -34.817,
            longitude = -58.467,
            zoneId = "America/Argentina/Buenos_Aires",
            displayUnit = TempUnit.C,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/ar/ezeiza/SAEZ",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/IMONTEGR27?cm_ven=localwx_pwsdash"
        ),
        CityConfig(
            id = "sao-paulo",
            name = "Sao Paulo",
            metarCode = "SBGR",
            latitude = -23.448,
            longitude = -46.526,
            zoneId = "America/Sao_Paulo",
            displayUnit = TempUnit.C,
            wundergroundControlUrl = "https://www.wunderground.com/history/daily/br/guarulhos/SBGR",
            wundergroundPwsUrl = "https://www.wunderground.com/dashboard/pws/IGUARU12?cm_ven=localwx_pwsdash"
        )
    )

    fun findById(id: String): CityConfig? = cities.firstOrNull { it.id == id }
}
