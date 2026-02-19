package com.polymeteo.meteotrader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.polymeteo.meteotrader.data.WeatherRepository
import com.polymeteo.meteotrader.data.model.BacktestReport
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.PolyTempPremiumReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    val lastUpdatedAt: Instant? = null,
    val errorMessage: String? = null,
    val backtestErrorMessage: String? = null,
    val backtestReport: BacktestReport = BacktestReport.empty(),
    val premiumReportsByCity: Map<String, PolyTempPremiumReport> = emptyMap(),
    val premiumRefreshingCityIds: Set<String> = emptySet(),
    val premiumErrorsByCity: Map<String, String> = emptyMap(),
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
            strategyMode = initialPreferences.strategyMode
        )
    )
    val uiState: StateFlow<MeteoUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var backtestJob: Job? = null
    private val cityRefreshJobs = mutableMapOf<String, Job>()
    private val premiumJobs = mutableMapOf<String, Job>()

    init {
        loadBacktestReport()
        refresh(initialLoad = true)
    }

    fun refresh(initialLoad: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = initialLoad,
                isRefreshing = !initialLoad,
                errorMessage = null
            )

            runCatching {
                repository.fetchAllCities()
            }.onSuccess { cities ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    cities = cities,
                    lastUpdatedAt = Instant.now(),
                    errorMessage = null
                )
                launchBacktestRefresh(cities)
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    errorMessage = throwable.message ?: "No se pudo actualizar"
                )
            }
        }
    }

    fun refreshCity(cityId: String) {
        if (cityRefreshJobs[cityId]?.isActive == true) return
        cityRefreshJobs[cityId] = viewModelScope.launch {
            try {
                runCatching {
                    repository.fetchCityById(cityId)
                }.onSuccess { cityData ->
                    if (cityData == null) return@onSuccess
                    val updated = _uiState.value.cities.toMutableList()
                    val index = updated.indexOfFirst { it.city.id == cityId }
                    if (index >= 0) {
                        updated[index] = cityData
                    } else {
                        updated += cityData
                    }
                    _uiState.value = _uiState.value.copy(
                        cities = updated,
                        lastUpdatedAt = Instant.now(),
                        errorMessage = null
                    )
                    launchBacktestRefresh(updated)
                }.onFailure { throwable ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = throwable.message ?: "No se pudo actualizar la ciudad"
                    )
                }
            } finally {
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

    fun cityById(cityId: String): CityWeatherData? {
        return _uiState.value.cities.firstOrNull { it.city.id == cityId }
    }

    fun refreshPremium(cityId: String, force: Boolean = true) {
        if (cityId.isBlank()) return
        if (premiumJobs[cityId]?.isActive == true) return
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
                }
            }.onFailure { throwable ->
                if (throwable !is CancellationException) {
                    _uiState.value = _uiState.value.copy(
                        premiumErrorsByCity = _uiState.value.premiumErrorsByCity + (
                            cityId to (throwable.message ?: "No se pudo cargar PolyTemp PREMIUM")
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
