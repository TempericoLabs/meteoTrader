package com.polymeteo.meteotrader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.polymeteo.meteotrader.data.CityCatalog
import com.polymeteo.meteotrader.data.WeatherRepository
import com.polymeteo.meteotrader.data.model.BacktestReport
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.PolymarketAccountSnapshot
import com.polymeteo.meteotrader.data.model.PolyTempPremiumReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Instant

enum class AppMode {
    EXPERT,
    ROOKIE
}

enum class StrategyMode {
    CONSERVADORA,
    AGRESIVA
}

data class MeteoUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isBacktestRefreshing: Boolean = false,
    val cities: List<CityWeatherData> = emptyList(),
    val cityLoadingIds: Set<String> = CityCatalog.cities.map { it.id }.toSet(),
    val lastUpdatedAt: Instant? = null,
    val errorMessage: String? = null,
    val backtestErrorMessage: String? = null,
    val backtestReport: BacktestReport = BacktestReport.empty(),
    val premiumReportsByCity: Map<String, PolyTempPremiumReport> = emptyMap(),
    val premiumRefreshingCityIds: Set<String> = emptySet(),
    val premiumErrorsByCity: Map<String, String> = emptyMap(),
    val polymarketWalletAddress: String = "",
    val polymarketAccountSnapshot: PolymarketAccountSnapshot? = null,
    val isPolymarketAccountRefreshing: Boolean = false,
    val polymarketAccountErrorMessage: String? = null,
    val appMode: AppMode = AppMode.EXPERT,
    val strategyMode: StrategyMode = StrategyMode.CONSERVADORA
)

class MeteoViewModel(
    private val repository: WeatherRepository,
    private val preferencesStore: PreferencesStore
) : ViewModel() {

    private val initialPreferences = preferencesStore.load()
    private val _uiState = MutableStateFlow(
        MeteoUiState(
            appMode = initialPreferences.appMode,
            strategyMode = initialPreferences.strategyMode,
            polymarketWalletAddress = repository.defaultPolymarketWalletAddress
        )
    )
    val uiState: StateFlow<MeteoUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var backtestJob: Job? = null
    private var accountJob: Job? = null
    private var premiumWarmupJob: Job? = null
    private val cityRefreshJobs = mutableMapOf<String, Job>()
    private val premiumJobs = mutableMapOf<String, Job>()

    init {
        loadBacktestReport()
        refresh(initialLoad = true)
    }

    fun refresh(initialLoad: Boolean = false) {
        if (refreshJob?.isActive == true) return
        premiumWarmupJob?.cancel()
        refreshJob = viewModelScope.launch {
            val cityCatalog = CityCatalog.cities
            val cityIds = cityCatalog.map { it.id }.toSet()
            _uiState.update { current ->
                current.copy(
                    isLoading = true,
                    isRefreshing = !initialLoad,
                    errorMessage = null,
                    cityLoadingIds = cityIds,
                    cities = if (initialLoad) emptyList() else current.cities
                )
            }

            val loadErrors = mutableListOf<String>()
            runCatching {
                coroutineScope {
                    val semaphore = Semaphore(MAX_CITY_REFRESH_CONCURRENCY)
                    val jobs = cityCatalog.map { city ->
                        launch {
                            try {
                                semaphore.withPermit {
                                    runCatching {
                                        withContext(Dispatchers.Default) {
                                            repository.fetchCity(city)
                                        }
                                    }.onSuccess { cityData ->
                                        applyCityUpdate(cityData)
                                    }.onFailure { throwable ->
                                        if (throwable !is CancellationException) {
                                            synchronized(loadErrors) {
                                                loadErrors += "${city.name}: ${throwable.message ?: "No se pudo actualizar"}"
                                            }
                                        }
                                    }
                                }
                            } finally {
                                setCityLoading(city.id, false)
                            }
                        }
                    }
                    jobs.joinAll()
                }
            }.onFailure { throwable ->
                if (throwable !is CancellationException) {
                    synchronized(loadErrors) {
                        loadErrors += throwable.message ?: "No se pudo actualizar"
                    }
                }
            }

            val loadedCities = _uiState.value.cities
            _uiState.update { current ->
                current.copy(
                    isLoading = false,
                    isRefreshing = false,
                    lastUpdatedAt = if (loadedCities.isNotEmpty()) Instant.now() else current.lastUpdatedAt,
                    errorMessage = when {
                        loadErrors.isEmpty() -> null
                        loadedCities.isEmpty() -> loadErrors.joinToString(" | ")
                        else -> "Algunas ciudades fallaron (${loadErrors.size}). Reintenta para completar."
                    }
                )
            }

            if (loadedCities.isNotEmpty()) {
                launchBacktestRefresh(loadedCities)
                launchPremiumWarmup(loadedCities)
            }
        }
    }

    fun refreshCity(cityId: String) {
        if (cityRefreshJobs[cityId]?.isActive == true) return
        cityRefreshJobs[cityId] = viewModelScope.launch {
            setCityLoading(cityId, true)
            try {
                runCatching {
                    withContext(Dispatchers.Default) {
                        repository.fetchCityById(cityId)
                    }
                }.onSuccess { cityData ->
                    if (cityData == null) return@onSuccess
                    val updated = applyCityUpdate(cityData)
                    launchBacktestRefresh(updated)
                }.onFailure { throwable ->
                    _uiState.update { current ->
                        current.copy(
                            errorMessage = throwable.message ?: "No se pudo actualizar la ciudad"
                        )
                    }
                }
            } finally {
                setCityLoading(cityId, false)
                cityRefreshJobs.remove(cityId)
            }
        }
    }

    fun refreshBacktest() {
        val cities = _uiState.value.cities
        if (cities.isEmpty()) {
            loadBacktestReport()
            return
        }
        launchBacktestRefresh(cities)
    }

    fun refreshPolymarketAccount(force: Boolean = true) {
        if (accountJob?.isActive == true) return
        val currentSnapshot = _uiState.value.polymarketAccountSnapshot
        if (!force && currentSnapshot != null) return

        accountJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    isPolymarketAccountRefreshing = true,
                    polymarketAccountErrorMessage = null
                )
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    repository.fetchPolymarketAccount()
                }
            }.onSuccess { snapshot ->
                _uiState.update { current ->
                    current.copy(
                        polymarketAccountSnapshot = snapshot,
                        isPolymarketAccountRefreshing = false,
                        polymarketAccountErrorMessage = null
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                _uiState.update { current ->
                    current.copy(
                        isPolymarketAccountRefreshing = false,
                        polymarketAccountErrorMessage = throwable.message ?: "No se pudo cargar la cuenta Polymarket"
                    )
                }
            }
        }
    }

    private fun loadBacktestReport() {
        if (backtestJob?.isActive == true) return
        backtestJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isBacktestRefreshing = true,
                backtestErrorMessage = null
            )
            runCatching {
                repository.loadBacktestReport()
            }.onSuccess { report ->
                _uiState.value = _uiState.value.copy(
                    isBacktestRefreshing = false,
                    backtestReport = report,
                    backtestErrorMessage = null
                )
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                _uiState.value = _uiState.value.copy(
                    isBacktestRefreshing = false,
                    backtestErrorMessage = throwable.message ?: "No se pudo cargar backtesting"
                )
            }
        }
    }

    private fun launchBacktestRefresh(cities: List<CityWeatherData>) {
        backtestJob?.cancel()
        backtestJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isBacktestRefreshing = true,
                backtestErrorMessage = null
            )
            runCatching {
                repository.refreshBacktest(cities)
            }.onSuccess { report ->
                _uiState.value = _uiState.value.copy(
                    isBacktestRefreshing = false,
                    backtestReport = report,
                    backtestErrorMessage = null
                )
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                _uiState.value = _uiState.value.copy(
                    isBacktestRefreshing = false,
                    backtestErrorMessage = throwable.message ?: "No se pudo actualizar backtesting"
                )
            }
        }
    }

    private fun launchPremiumWarmup(cities: List<CityWeatherData>) {
        premiumWarmupJob?.cancel()
        premiumWarmupJob = viewModelScope.launch(Dispatchers.Default) {
            for (city in cities) {
                if (!isActive) break
                val cityId = city.city.id
                if (premiumJobs[cityId]?.isActive == true) continue

                _uiState.value = _uiState.value.copy(
                    premiumRefreshingCityIds = _uiState.value.premiumRefreshingCityIds + cityId,
                    premiumErrorsByCity = _uiState.value.premiumErrorsByCity - cityId
                )

                runCatching {
                    repository.refreshPremiumReport(cityId)
                }.onSuccess { report ->
                    if (report == null) {
                        _uiState.value = _uiState.value.copy(
                            premiumErrorsByCity = _uiState.value.premiumErrorsByCity + (cityId to "Ciudad no encontrada")
                        )
                        return@onSuccess
                    }

                    _uiState.value = _uiState.value.copy(
                        premiumReportsByCity = _uiState.value.premiumReportsByCity + (cityId to report),
                        premiumErrorsByCity = _uiState.value.premiumErrorsByCity - cityId
                    )

                    runCatching {
                        withContext(Dispatchers.Default) {
                            repository.fetchCityById(cityId)
                        }
                    }.onSuccess { updatedCity ->
                        if (updatedCity != null) {
                            applyCityUpdate(updatedCity)
                        }
                    }
                }.onFailure { throwable ->
                    if (throwable !is CancellationException) {
                        _uiState.value = _uiState.value.copy(
                            premiumErrorsByCity = _uiState.value.premiumErrorsByCity + (
                                cityId to (throwable.message ?: "No se pudo precalcular Media Modelos Ajustada (MMA)")
                                )
                        )
                    }
                }

                _uiState.value = _uiState.value.copy(
                    premiumRefreshingCityIds = _uiState.value.premiumRefreshingCityIds - cityId
                )
            }
        }
    }

    fun cityById(cityId: String): CityWeatherData? {
        return _uiState.value.cities.firstOrNull { it.city.id == cityId }
    }

    fun refreshPremium(cityId: String, force: Boolean = true) {
        if (cityId.isBlank()) return
        if (premiumJobs[cityId]?.isActive == true) return
        if (_uiState.value.premiumRefreshingCityIds.contains(cityId)) return
        if (!force && _uiState.value.premiumReportsByCity.containsKey(cityId)) return

        premiumJobs[cityId] = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                premiumRefreshingCityIds = _uiState.value.premiumRefreshingCityIds + cityId,
                premiumErrorsByCity = _uiState.value.premiumErrorsByCity - cityId
            )

            runCatching {
                repository.refreshPremiumReport(cityId)
            }.onSuccess { report ->
                if (report == null) {
                    _uiState.value = _uiState.value.copy(
                        premiumErrorsByCity = _uiState.value.premiumErrorsByCity + (cityId to "Ciudad no encontrada")
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        premiumReportsByCity = _uiState.value.premiumReportsByCity + (cityId to report),
                        premiumErrorsByCity = _uiState.value.premiumErrorsByCity - cityId
                    )
                    runCatching {
                        withContext(Dispatchers.Default) {
                            repository.fetchCityById(cityId)
                        }
                    }.onSuccess { cityData ->
                        if (cityData != null) {
                            applyCityUpdate(cityData)
                        }
                    }
                }
            }.onFailure { throwable ->
                if (throwable !is CancellationException) {
                    _uiState.value = _uiState.value.copy(
                        premiumErrorsByCity = _uiState.value.premiumErrorsByCity + (
                            cityId to (throwable.message ?: "No se pudo cargar Media Modelos Ajustada (MMA)")
                            )
                    )
                }
            }

            _uiState.value = _uiState.value.copy(
                premiumRefreshingCityIds = _uiState.value.premiumRefreshingCityIds - cityId
            )
            premiumJobs.remove(cityId)
        }
    }

    private fun applyCityUpdate(cityData: CityWeatherData): List<CityWeatherData> {
        var sorted = emptyList<CityWeatherData>()
        _uiState.update { current ->
            val updated = current.cities.toMutableList()
            val index = updated.indexOfFirst { it.city.id == cityData.city.id }
            if (index >= 0) {
                updated[index] = cityData
            } else {
                updated += cityData
            }
            val orderById = CityCatalog.cities
                .mapIndexed { idx, config -> config.id to idx }
                .toMap()
            sorted = updated.sortedBy { city -> orderById[city.city.id] ?: Int.MAX_VALUE }
            current.copy(
                cities = sorted,
                lastUpdatedAt = Instant.now()
            )
        }
        return sorted
    }

    private fun setCityLoading(cityId: String, loading: Boolean) {
        _uiState.update { current ->
            val next = if (loading) {
                current.cityLoadingIds + cityId
            } else {
                current.cityLoadingIds - cityId
            }
            current.copy(
                cityLoadingIds = next
            )
        }
    }

    fun setAppMode(mode: AppMode) {
        if (_uiState.value.appMode == mode) return
        _uiState.value = _uiState.value.copy(appMode = mode)
        preferencesStore.saveAppMode(mode)
    }

    fun setStrategyMode(mode: StrategyMode) {
        if (_uiState.value.strategyMode == mode) return
        _uiState.value = _uiState.value.copy(strategyMode = mode)
        preferencesStore.saveStrategyMode(mode)
    }
}

private const val MAX_CITY_REFRESH_CONCURRENCY = 4

class MeteoViewModelFactory(
    private val repository: WeatherRepository,
    private val preferencesStore: PreferencesStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MeteoViewModel::class.java)) {
            return MeteoViewModel(repository, preferencesStore) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
