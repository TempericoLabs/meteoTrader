package com.polymeteo.meteotrader.data

import com.polymeteo.meteotrader.BuildConfig
import com.polymeteo.meteotrader.data.forecast.ForecastProvider
import com.polymeteo.meteotrader.data.forecast.OpenMeteoProvider
import com.polymeteo.meteotrader.data.forecast.OpenWeatherProvider
import com.polymeteo.meteotrader.data.forecast.PlaceholderEcmwfProvider
import com.polymeteo.meteotrader.data.forecast.WeatherGovProvider
import com.polymeteo.meteotrader.data.forecast.WeatherStackProvider
import com.polymeteo.meteotrader.data.forecast.WindyProvider
import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.SourceStatus
import com.polymeteo.meteotrader.data.source.HttpClient
import com.polymeteo.meteotrader.data.source.MetarSource
import com.polymeteo.meteotrader.data.source.WundergroundSource
import com.polymeteo.meteotrader.util.celsiusToFahrenheit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class WeatherRepository(
    private val metarSource: MetarSource,
    private val wundergroundSource: WundergroundSource,
    private val forecastProviders: List<ForecastProvider>
) {

    suspend fun fetchAllCities(): List<CityWeatherData> = coroutineScope {
        CityCatalog.cities.map { city ->
            async { fetchCity(city) }
        }.awaitAll()
    }

    suspend fun fetchCityById(cityId: String): CityWeatherData? {
        val city = CityCatalog.findById(cityId) ?: return null
        return fetchCity(city)
    }

    suspend fun fetchCity(city: CityConfig): CityWeatherData = coroutineScope {
        val zoneId = ZoneId.of(city.zoneId)
        val targetDate = LocalDate.now(zoneId)

        val metarDeferred = async { metarSource.fetch(city) }
        val controlDeferred = async { wundergroundSource.fetch(city) }
        val forecastsDeferred = forecastProviders.map { provider ->
            async { provider.fetch(city, targetDate) }
        }

        val metar = metarDeferred.await()
        val control = controlDeferred.await()
        val forecasts = forecastsDeferred.awaitAll()

        val successfulForecasts = forecasts
            .filter { it.status == SourceStatus.SUCCESS }
            .mapNotNull { it.maxTempC }
        val polyTempC = if (successfulForecasts.isNotEmpty()) {
            successfulForecasts.average()
        } else {
            null
        }

        val warnings = buildList {
            metar.error?.let { add("METAR: $it") }
            control.error?.let { add("Wunderground: $it") }
            forecasts.filter { it.status == SourceStatus.ERROR }.forEach { result ->
                result.error?.let { add("${result.sourceName}: $it") }
            }
        }

        CityWeatherData(
            city = city,
            localTime = currentLocalTime(zoneId),
            metar = metar,
            controlStation = control,
            forecasts = forecasts,
            polyTempC = polyTempC,
            polyTempF = polyTempC?.let(::celsiusToFahrenheit),
            updatedAt = Instant.now(),
            warnings = warnings
        )
    }

    private fun currentLocalTime(zoneId: ZoneId): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        return formatter.format(Instant.now().atZone(zoneId))
    }

    companion object {
        fun createDefault(): WeatherRepository {
            val httpClient = HttpClient()
            val metarSource = MetarSource(httpClient)
            val wundergroundSource = WundergroundSource(httpClient)

            val providers: List<ForecastProvider> = listOf(
                WindyProvider(
                    httpClient = httpClient,
                    apiKey = BuildConfig.WINDY_API_KEY,
                    model = "ecmwf",
                    id = "windy-ecmwf",
                    name = "Windy ECMWF IFS"
                ),
                WindyProvider(
                    httpClient = httpClient,
                    apiKey = BuildConfig.WINDY_API_KEY,
                    model = "gfs",
                    id = "windy-gfs",
                    name = "Windy GFS"
                ),
                WindyProvider(
                    httpClient = httpClient,
                    apiKey = BuildConfig.WINDY_API_KEY,
                    model = "icon",
                    id = "windy-icon",
                    name = "Windy ICON"
                ),
                OpenMeteoProvider(
                    httpClient = httpClient,
                    model = "ecmwf_ifs",
                    id = "openmeteo-ifs",
                    name = "Open-Meteo ECMWF IFS"
                ),
                OpenMeteoProvider(
                    httpClient = httpClient,
                    model = "ecmwf_aifs025_single",
                    id = "openmeteo-aifs",
                    name = "Open-Meteo ECMWF AIFS"
                ),
                OpenWeatherProvider(httpClient, BuildConfig.OPEN_WEATHER_API_KEY),
                WeatherStackProvider(httpClient, BuildConfig.WEATHERSTACK_API_KEY),
                WeatherGovProvider(httpClient),
                PlaceholderEcmwfProvider()
            )

            return WeatherRepository(
                metarSource = metarSource,
                wundergroundSource = wundergroundSource,
                forecastProviders = providers
            )
        }
    }
}
