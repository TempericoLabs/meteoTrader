package com.polymeteo.meteotrader.data

import android.content.Context
import com.polymeteo.meteotrader.BuildConfig
import com.polymeteo.meteotrader.data.backtest.BacktestEngine
import com.polymeteo.meteotrader.data.backtest.BacktestStore
import com.polymeteo.meteotrader.data.forecast.ForecastProvider
import com.polymeteo.meteotrader.data.forecast.ForecastWeights
import com.polymeteo.meteotrader.data.forecast.OpenMeteoProvider
import com.polymeteo.meteotrader.data.forecast.OpenWeatherProvider
import com.polymeteo.meteotrader.data.forecast.PlaceholderEcmwfProvider
import com.polymeteo.meteotrader.data.forecast.WeatherGovProvider
import com.polymeteo.meteotrader.data.forecast.WeatherStackProvider
import com.polymeteo.meteotrader.data.forecast.WindyProvider
import com.polymeteo.meteotrader.data.model.BacktestReport
import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.ControlStationSnapshot
import com.polymeteo.meteotrader.data.model.ForecastSourceResult
import com.polymeteo.meteotrader.data.model.MetarSnapshot
import com.polymeteo.meteotrader.data.model.PolyTempPremiumReport
import com.polymeteo.meteotrader.data.model.PolymarketSnapshot
import com.polymeteo.meteotrader.data.model.SourceStatus
import com.polymeteo.meteotrader.data.premium.PolyTempPremiumEngine
import com.polymeteo.meteotrader.data.premium.PolyTempPremiumStore
import com.polymeteo.meteotrader.data.premium.PremiumWeightSnapshot
import com.polymeteo.meteotrader.data.source.HttpClient
import com.polymeteo.meteotrader.data.source.MetarSource
import com.polymeteo.meteotrader.data.source.PolymarketSource
import com.polymeteo.meteotrader.data.source.WundergroundSource
import com.polymeteo.meteotrader.util.celsiusToFahrenheit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class WeatherRepository(
    private val metarSource: MetarSource,
    private val wundergroundSource: WundergroundSource,
    private val forecastProviders: List<ForecastProvider>,
    private val polymarketSource: PolymarketSource,
    private val backtestEngine: BacktestEngine? = null,
    private val premiumEngine: PolyTempPremiumEngine? = null
) {

    suspend fun fetchAllCities(): List<CityWeatherData> = coroutineScope {
        val citySemaphore = Semaphore(MAX_CITY_CONCURRENCY)
        CityCatalog.cities.map { city ->
            async {
                citySemaphore.withPermit {
                    fetchCity(city)
                }
            }
        }.awaitAll()
    }

    suspend fun fetchCityById(cityId: String): CityWeatherData? {
        val city = CityCatalog.findById(cityId) ?: return null
        return fetchCity(city)
    }

    suspend fun refreshBacktest(cities: List<CityWeatherData>): BacktestReport {
        val engine = backtestEngine ?: return BacktestReport.empty()
        return engine.ingestAndSettle(cities)
    }

    suspend fun loadBacktestReport(): BacktestReport {
        val engine = backtestEngine ?: return BacktestReport.empty()
        return engine.computeReport()
    }

    suspend fun refreshPremiumReport(cityId: String): PolyTempPremiumReport? {
        val city = CityCatalog.findById(cityId) ?: return null
        val engine = premiumEngine ?: return PolyTempPremiumReport.empty(
            cityId = city.id,
            cityName = city.name
        )
        return engine.refreshCityReport(city)
    }

    suspend fun loadPremiumReport(cityId: String): PolyTempPremiumReport? {
        val city = CityCatalog.findById(cityId) ?: return null
        val engine = premiumEngine ?: return PolyTempPremiumReport.empty(
            cityId = city.id,
            cityName = city.name
        )
        return engine.loadCityReport(city)
    }

    suspend fun fetchCity(city: CityConfig): CityWeatherData = coroutineScope {
        val zoneId = ZoneId.of(city.zoneId)
        val targetDate = LocalDate.now(zoneId)

        val metarDeferred = async { fetchMetarWithTimeout(city) }
        val controlDeferred = async { fetchControlWithTimeout(city) }
        val forecastsDeferred = forecastProviders.map { provider ->
            async { fetchForecastWithTimeout(provider, city, targetDate) }
        }

        val metar = metarDeferred.await()
        val control = controlDeferred.await()
        val forecasts = forecastsDeferred.awaitAll()

        val premiumWeights = fetchPremiumWeightsWithTimeout(
            city = city,
            targetDate = targetDate,
            forecasts = forecasts
        )
        val polyTempComputation = computePolyTemp(
            forecasts = forecasts,
            dynamicWeights = premiumWeights.weightsByProviderId
        )
        val polymarket = fetchPolymarketWithTimeout(
            city = city,
            polyTempC = polyTempComputation.polyTempC,
            forecastDailyMaxC = polyTempComputation.validMaxTempsC
        )

        val warnings = buildList {
            metar.error?.let { add("METAR: $it") }
            control.error?.let { add("Wunderground: $it") }
            polymarket.error?.let { add("Polymarket: $it") }
            forecasts.filter { it.status == SourceStatus.ERROR }.forEach { result ->
                result.error?.let { add("${result.sourceName}: $it") }
            }
            addAll(polyTempComputation.warnings)
            addAll(premiumWeights.warnings)
        }

        CityWeatherData(
            city = city,
            localTime = currentLocalTime(zoneId),
            metar = metar,
            controlStation = control,
            forecasts = forecasts,
            polyTempC = polyTempComputation.polyTempC,
            polyTempF = polyTempComputation.polyTempC?.let(::celsiusToFahrenheit),
            polymarket = polymarket,
            updatedAt = Instant.now(),
            warnings = warnings
        )
    }

    private fun currentLocalTime(zoneId: ZoneId): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        return formatter.format(Instant.now().atZone(zoneId))
    }

    private suspend fun fetchMetarWithTimeout(city: CityConfig): MetarSnapshot {
        val fallbackUrl = "https://aviationweather.gov/api/data/metar?ids=${city.metarCode}&format=json&hours=24"
        return withTimeoutOrNull(METAR_TIMEOUT_MS) {
            metarSource.fetch(city)
        } ?: MetarSnapshot(
            sourceUrl = fallbackUrl,
            current = null,
            previousSameDay = null,
            error = "Timeout METAR"
        )
    }

    private suspend fun fetchControlWithTimeout(city: CityConfig): ControlStationSnapshot {
        return withTimeoutOrNull(CONTROL_TIMEOUT_MS) {
            wundergroundSource.fetch(city)
        } ?: ControlStationSnapshot(
            sourceUrl = city.wundergroundControlUrl,
            tempC = null,
            tempF = null,
            error = "Timeout Wunderground"
        )
    }

    private suspend fun fetchForecastWithTimeout(
        provider: ForecastProvider,
        city: CityConfig,
        targetDate: LocalDate
    ): ForecastSourceResult {
        return withTimeoutOrNull(FORECAST_TIMEOUT_MS) {
            provider.fetch(city, targetDate)
        } ?: ForecastSourceResult(
            sourceId = provider.id,
            sourceName = provider.name,
            status = SourceStatus.ERROR,
            maxTempC = null,
            maxTempF = null,
            currentTempC = null,
            currentTempF = null,
            error = "Timeout ${provider.name}"
        )
    }

    private suspend fun fetchPolymarketWithTimeout(
        city: CityConfig,
        polyTempC: Double?,
        forecastDailyMaxC: List<Double>
    ): PolymarketSnapshot {
        return withTimeoutOrNull(POLYMARKET_TIMEOUT_MS) {
            polymarketSource.fetch(
                city = city,
                polyTempC = polyTempC,
                forecastDailyMaxC = forecastDailyMaxC
            )
        } ?: PolymarketSnapshot(
            query = "highest temperature in ${city.name}",
            fetchedAt = Instant.now(),
            marketsScanned = 0,
            opportunities = emptyList(),
            topOpportunity = null,
            error = "Timeout Polymarket"
        )
    }

    private suspend fun fetchPremiumWeightsWithTimeout(
        city: CityConfig,
        targetDate: LocalDate,
        forecasts: List<ForecastSourceResult>
    ): PremiumWeightSnapshot {
        val engine = premiumEngine ?: return PremiumWeightSnapshot(
            weightsByProviderId = emptyMap(),
            verifiedDays = 0,
            lastVerifiedDate = null,
            warnings = emptyList()
        )
        return withTimeoutOrNull(PREMIUM_TIMEOUT_MS) {
            engine.ingestAndResolveWeights(
                city = city,
                targetDate = targetDate,
                forecasts = forecasts
            )
        } ?: PremiumWeightSnapshot(
            weightsByProviderId = emptyMap(),
            verifiedDays = 0,
            lastVerifiedDate = null,
            warnings = listOf("PolyTEMP PREMIUM: timeout del motor de verificación")
        )
    }

    private fun computePolyTemp(
        forecasts: List<ForecastSourceResult>,
        dynamicWeights: Map<String, Double>
    ): PolyTempComputation {
        val candidates = forecasts
            .asSequence()
            .filter { it.status == SourceStatus.SUCCESS }
            .mapNotNull { result ->
                val value = result.maxTempC ?: return@mapNotNull null
                if (value !in VALID_TEMP_RANGE_C) return@mapNotNull null
                SourceTemp(result.sourceId, result.sourceName, value)
            }
            .toList()

        if (candidates.isEmpty()) {
            return PolyTempComputation(
                polyTempC = null,
                validMaxTempsC = emptyList(),
                warnings = listOf("PolyTEMP: sin fuentes válidas")
            )
        }

        val median = median(candidates.map { it.valueC })
        val absDev = candidates.map { abs(it.valueC - median) }
        val mad = median(absDev)
        val outlierTolerance = max(1.8, mad * 3.2)

        val filtered = if (candidates.size <= 2) {
            candidates
        } else {
            candidates.filter { abs(it.valueC - median) <= outlierTolerance }
        }.ifEmpty { candidates }

        val weighted = filtered.weightedAverage(dynamicWeights)
        val spread = filtered.maxOf { it.valueC } - filtered.minOf { it.valueC }
        val warnings = buildList {
            val outliers = candidates.size - filtered.size
            if (outliers > 0) {
                add("PolyTEMP: descartados $outliers outliers")
            }
            if (filtered.size < MIN_FORECASTS_FOR_HIGH_CONFIDENCE) {
                add("PolyTEMP: baja confianza (${filtered.size} fuentes)")
            }
            if (spread > 5.5) {
                add("PolyTEMP: alta dispersión (${String.format(Locale.US, "%.1f", spread)}°C)")
            }
        }

        return PolyTempComputation(
            polyTempC = weighted,
            validMaxTempsC = filtered.map { it.valueC },
            warnings = warnings
        )
    }

    private fun List<SourceTemp>.weightedAverage(dynamicWeights: Map<String, Double>): Double {
        var weightedSum = 0.0
        var totalWeight = 0.0
        forEach { sample ->
            val weight = dynamicWeights[sample.sourceId]
                ?: ForecastWeights.baseWeightFor(sample.sourceId)
            weightedSum += sample.valueC * weight
            totalWeight += weight
        }
        return if (totalWeight <= 0.0) {
            map { it.valueC }.average()
        } else {
            weightedSum / totalWeight
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private data class SourceTemp(
        val sourceId: String,
        val sourceName: String,
        val valueC: Double
    )

    private data class PolyTempComputation(
        val polyTempC: Double?,
        val validMaxTempsC: List<Double>,
        val warnings: List<String>
    )

    companion object {
        private const val MAX_CITY_CONCURRENCY = 4
        private const val METAR_TIMEOUT_MS = 7_000L
        private const val CONTROL_TIMEOUT_MS = 8_000L
        private const val FORECAST_TIMEOUT_MS = 9_000L
        private const val POLYMARKET_TIMEOUT_MS = 6_000L
        private const val PREMIUM_TIMEOUT_MS = 5_000L
        private const val MIN_FORECASTS_FOR_HIGH_CONFIDENCE = 3
        private val VALID_TEMP_RANGE_C = -80.0..65.0

        fun createDefault(context: Context? = null): WeatherRepository {
            val httpClient = HttpClient()
            val metarSource = MetarSource(httpClient)
            val wundergroundSource = WundergroundSource(httpClient)
            val polymarketSource = PolymarketSource(httpClient)
            val backtestEngine = context?.let {
                BacktestEngine(
                    store = BacktestStore(it),
                    wundergroundSource = wundergroundSource,
                    polymarketSource = polymarketSource,
                    httpClient = httpClient,
                    demoMode = BuildConfig.DEMO_MODE
                )
            }
            val premiumEngine = context?.let {
                PolyTempPremiumEngine(
                    store = PolyTempPremiumStore(it),
                    wundergroundSource = wundergroundSource
                )
            }

            val providers: List<ForecastProvider> = listOf(
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
                    model = "iconEu",
                    id = "windy-icon-eu",
                    name = "Windy ICON-EU"
                ),
                WindyProvider(
                    httpClient = httpClient,
                    apiKey = BuildConfig.WINDY_API_KEY,
                    model = "namConus",
                    id = "windy-nam-conus",
                    name = "Windy NAM CONUS"
                ),
                OpenMeteoProvider(
                    httpClient = httpClient,
                    model = "ecmwf_ifs",
                    id = "openmeteo-ifs",
                    name = "Open-Meteo ECMWF IFS"
                ),
                OpenMeteoProvider(
                    httpClient = httpClient,
                    model = "ecmwf_ifs025",
                    id = "openmeteo-ifs025",
                    name = "Open-Meteo ECMWF IFS 0.25"
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
                forecastProviders = providers,
                polymarketSource = polymarketSource,
                backtestEngine = backtestEngine,
                premiumEngine = premiumEngine
            )
        }
    }
}
