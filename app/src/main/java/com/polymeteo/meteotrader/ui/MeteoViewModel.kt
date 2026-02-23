package com.polymeteo.meteotrader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.polymeteo.meteotrader.data.CityCatalog
import com.polymeteo.meteotrader.data.WeatherRepository
import com.polymeteo.meteotrader.data.model.BacktestReport
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.PaperPortfolioSnapshot
import com.polymeteo.meteotrader.data.model.PolymarketAccountSnapshot
import com.polymeteo.meteotrader.data.model.PolymarketUserActivityItem
import com.polymeteo.meteotrader.data.model.PolymarketUserIntelSnapshot
import com.polymeteo.meteotrader.data.model.PolyTempPremiumReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import java.util.Locale

enum class AppMode {
    EXPERT,
    ROOKIE
}

enum class StrategyMode {
    CONSERVADORA,
    AGRESIVA
}

data class CopyTradeRuntimeAlert(
    val id: String,
    val username: String,
    val proxyWallet: String,
    val type: String,
    val side: String?,
    val outcome: String?,
    val title: String,
    val eventSlug: String?,
    val marketSlug: String?,
    val usdcSize: Double?,
    val price: Double?,
    val timestamp: Instant?,
    val cityId: String?,
    val cityName: String?,
    val summary: String
)

data class CopyTradeMonitorUiState(
    val config: CopyTradePreferences = CopyTradePreferences(),
    val isRunning: Boolean = false,
    val baselinePrimed: Boolean = false,
    val lastPollAt: Instant? = null,
    val lastSeenActivityAt: Instant? = null,
    val lastAlertAt: Instant? = null,
    val lastError: String? = null,
    val recentAlerts: List<CopyTradeRuntimeAlert> = emptyList()
)

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
    val paperPortfolio: PaperPortfolioSnapshot = PaperPortfolioSnapshot.empty(),
    val isPaperTradingRefreshing: Boolean = false,
    val paperTradingErrorMessage: String? = null,
    val polymarketWalletAddress: String = "",
    val polymarketAccountSnapshot: PolymarketAccountSnapshot? = null,
    val isPolymarketAccountRefreshing: Boolean = false,
    val polymarketAccountErrorMessage: String? = null,
    val polymarketUserIntelSnapshot: PolymarketUserIntelSnapshot? = null,
    val isPolymarketUserIntelRefreshing: Boolean = false,
    val polymarketUserIntelErrorMessage: String? = null,
    val copyTradeMonitor: CopyTradeMonitorUiState = CopyTradeMonitorUiState(),
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
            polymarketWalletAddress = repository.defaultPolymarketWalletAddress,
            copyTradeMonitor = CopyTradeMonitorUiState(
                config = initialPreferences.copyTrade
            )
        )
    )
    val uiState: StateFlow<MeteoUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var backtestJob: Job? = null
    private var accountJob: Job? = null
    private var userIntelJob: Job? = null
    private var copyTradeMonitorJob: Job? = null
    private var paperTradingJob: Job? = null
    private var premiumWarmupJob: Job? = null
    private val cityRefreshJobs = mutableMapOf<String, Job>()
    private val premiumJobs = mutableMapOf<String, Job>()
    private val seenCopyTradeActivityKeys = LinkedHashSet<String>()

    init {
        loadBacktestReport()
        refresh(initialLoad = true)
        if (initialPreferences.copyTrade.enabled && initialPreferences.copyTrade.proxyWallet.isNotBlank()) {
            startCopyTradeMonitor(rebaseline = true)
        }
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
                refreshPaperTrading(loadedCities)
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
                    refreshPaperTrading(updated)
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

    fun refreshPaperTrading() {
        refreshPaperTrading(_uiState.value.cities)
    }

    fun simulateBuyYes(
        cityId: String,
        marketId: String,
        stakeUsdc: Double
    ) {
        val cities = _uiState.value.cities
        val cityData = cities.firstOrNull { city -> city.city.id == cityId }
        val opportunity = cityData?.polymarket?.opportunities?.firstOrNull { it.marketId == marketId }
        if (cityData == null || opportunity == null) {
            _uiState.update { current ->
                current.copy(
                    paperTradingErrorMessage = "No se encontró el mercado para simular la compra YES"
                )
            }
            return
        }

        paperTradingJob?.cancel()
        paperTradingJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    isPaperTradingRefreshing = true,
                    paperTradingErrorMessage = null
                )
            }
            runCatching {
                withContext(Dispatchers.Default) {
                    repository.simulateBuyYes(
                        cityData = cityData,
                        opportunity = opportunity,
                        stakeUsdc = stakeUsdc,
                        cities = _uiState.value.cities
                    )
                }
            }.onSuccess { snapshot ->
                _uiState.update { current ->
                    current.copy(
                        paperPortfolio = snapshot,
                        isPaperTradingRefreshing = false,
                        paperTradingErrorMessage = null
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                _uiState.update { current ->
                    current.copy(
                        isPaperTradingRefreshing = false,
                        paperTradingErrorMessage = throwable.message
                            ?: "No se pudo registrar la compra simulada"
                    )
                }
            }
        }
    }

    fun simulateClosePosition(positionId: String) {
        if (positionId.isBlank()) return
        paperTradingJob?.cancel()
        paperTradingJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    isPaperTradingRefreshing = true,
                    paperTradingErrorMessage = null
                )
            }
            runCatching {
                withContext(Dispatchers.Default) {
                    repository.simulateClosePosition(
                        positionId = positionId,
                        cities = _uiState.value.cities
                    )
                }
            }.onSuccess { snapshot ->
                _uiState.update { current ->
                    current.copy(
                        paperPortfolio = snapshot,
                        isPaperTradingRefreshing = false,
                        paperTradingErrorMessage = null
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                _uiState.update { current ->
                    current.copy(
                        isPaperTradingRefreshing = false,
                        paperTradingErrorMessage = throwable.message
                            ?: "No se pudo cerrar la posición simulada"
                    )
                }
            }
        }
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

    fun analyzePolymarketUser(username: String) {
        if (userIntelJob?.isActive == true) return
        val normalized = username.trim().removePrefix("@")
        if (normalized.isBlank()) {
            _uiState.update { current ->
                current.copy(
                    polymarketUserIntelErrorMessage = "Introduce un nombre de usuario de Polymarket"
                )
            }
            return
        }

        userIntelJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    isPolymarketUserIntelRefreshing = true,
                    polymarketUserIntelErrorMessage = null
                )
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    repository.fetchPolymarketUserIntel(normalized)
                }
            }.onSuccess { snapshot ->
                _uiState.update { current ->
                    val currentConfig = current.copyTradeMonitor.config
                    val nextConfig = if (
                        !currentConfig.enabled &&
                        currentConfig.username.isBlank() &&
                        currentConfig.proxyWallet.isBlank()
                    ) {
                        currentConfig.copy(
                            username = snapshot.profile.username,
                            proxyWallet = snapshot.profile.proxyWallet
                        )
                    } else {
                        currentConfig
                    }
                    current.copy(
                        polymarketUserIntelSnapshot = snapshot,
                        isPolymarketUserIntelRefreshing = false,
                        polymarketUserIntelErrorMessage = null,
                        copyTradeMonitor = current.copyTradeMonitor.copy(
                            config = nextConfig
                        )
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                _uiState.update { current ->
                    current.copy(
                        isPolymarketUserIntelRefreshing = false,
                        polymarketUserIntelErrorMessage = throwable.message
                            ?: "No se pudo analizar el usuario de Polymarket"
                    )
                }
            }
        }
    }

    fun updateCopyTradeConfig(
        transform: (CopyTradePreferences) -> CopyTradePreferences
    ) {
        _uiState.update { current ->
            val nextConfig = transform(current.copyTradeMonitor.config)
            preferencesStore.saveCopyTrade(nextConfig)
            current.copy(
                copyTradeMonitor = current.copyTradeMonitor.copy(
                    config = nextConfig
                )
            )
        }
    }

    fun toggleCopyTradeFromCurrentIntel(enabled: Boolean) {
        if (!enabled) {
            val nextConfig = _uiState.value.copyTradeMonitor.config.copy(enabled = false)
            preferencesStore.saveCopyTrade(nextConfig)
            _uiState.update { current ->
                current.copy(
                    copyTradeMonitor = current.copyTradeMonitor.copy(
                        config = nextConfig,
                        isRunning = false,
                        lastError = null
                    )
                )
            }
            stopCopyTradeMonitor()
            return
        }

        val intel = _uiState.value.polymarketUserIntelSnapshot
        if (intel == null) {
            _uiState.update { current ->
                current.copy(
                    polymarketUserIntelErrorMessage = "Analiza primero un usuario para activar CopyTrade"
                )
            }
            return
        }

        val nextConfig = _uiState.value.copyTradeMonitor.config.copy(
            enabled = true,
            username = intel.profile.username,
            proxyWallet = intel.profile.proxyWallet
        )
        preferencesStore.saveCopyTrade(nextConfig)
        _uiState.update { current ->
            current.copy(
                copyTradeMonitor = current.copyTradeMonitor.copy(
                    config = nextConfig,
                    lastError = null
                )
            )
        }
        startCopyTradeMonitor(rebaseline = true)
    }

    fun clearCopyTradeAlerts() {
        _uiState.update { current ->
            current.copy(
                copyTradeMonitor = current.copyTradeMonitor.copy(
                    recentAlerts = emptyList()
                )
            )
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

    private fun startCopyTradeMonitor(rebaseline: Boolean) {
        val config = _uiState.value.copyTradeMonitor.config
        if (!config.enabled || config.proxyWallet.isBlank()) return
        if (copyTradeMonitorJob?.isActive == true) {
            if (rebaseline) {
                stopCopyTradeMonitor()
            } else {
                return
            }
        }
        if (rebaseline) {
            synchronized(seenCopyTradeActivityKeys) {
                seenCopyTradeActivityKeys.clear()
            }
        }

        copyTradeMonitorJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    copyTradeMonitor = current.copyTradeMonitor.copy(
                        isRunning = true,
                        baselinePrimed = !rebaseline && current.copyTradeMonitor.baselinePrimed,
                        lastError = null
                    )
                )
            }

            while (isActive) {
                val currentConfig = _uiState.value.copyTradeMonitor.config
                if (!currentConfig.enabled || currentConfig.proxyWallet.isBlank()) break

                runCatching {
                    repository.fetchPolymarketUserActivityByWallet(
                        walletAddress = currentConfig.proxyWallet,
                        limit = COPYTRADE_POLL_FETCH_LIMIT
                    )
                }.onSuccess { activity ->
                    handleCopyTradePollSuccess(activity, currentConfig)
                }.onFailure { throwable ->
                    if (throwable !is CancellationException) {
                        _uiState.update { current ->
                            current.copy(
                                copyTradeMonitor = current.copyTradeMonitor.copy(
                                    lastPollAt = Instant.now(),
                                    lastError = throwable.message ?: "Error en CopyTrade poll"
                                )
                            )
                        }
                    }
                }

                delay((_uiState.value.copyTradeMonitor.config.pollIntervalSec.coerceIn(2, 15) * 1000L))
            }

            _uiState.update { current ->
                current.copy(
                    copyTradeMonitor = current.copyTradeMonitor.copy(
                        isRunning = false
                    )
                )
            }
        }
    }

    private fun stopCopyTradeMonitor() {
        copyTradeMonitorJob?.cancel()
        copyTradeMonitorJob = null
    }

    private fun handleCopyTradePollSuccess(
        activity: List<PolymarketUserActivityItem>,
        config: CopyTradePreferences
    ) {
        val sorted = activity.sortedBy { it.timestamp ?: Instant.EPOCH }
        val now = Instant.now()
        val maxTimestamp = sorted.maxOfOrNull { it.timestamp ?: Instant.EPOCH }?.takeIf { it != Instant.EPOCH }

        val baselinePrimed = _uiState.value.copyTradeMonitor.baselinePrimed
        val newItems = mutableListOf<PolymarketUserActivityItem>()

        synchronized(seenCopyTradeActivityKeys) {
            if (!baselinePrimed && seenCopyTradeActivityKeys.isEmpty()) {
                sorted.forEach { item ->
                    seenCopyTradeActivityKeys += copyTradeActivityKey(item)
                }
            } else {
                sorted.forEach { item ->
                    val key = copyTradeActivityKey(item)
                    if (seenCopyTradeActivityKeys.add(key)) {
                        newItems += item
                    }
                }
            }
            while (seenCopyTradeActivityKeys.size > COPYTRADE_SEEN_KEYS_LIMIT) {
                val first = seenCopyTradeActivityKeys.firstOrNull() ?: break
                seenCopyTradeActivityKeys.remove(first)
            }
        }

        val alerts = if (!baselinePrimed && _uiState.value.copyTradeMonitor.recentAlerts.isEmpty()) {
            emptyList()
        } else {
            newItems.mapNotNull { item -> buildCopyTradeAlert(item, config) }
        }

        _uiState.update { current ->
            val existingAlerts = current.copyTradeMonitor.recentAlerts
            val nextAlerts = (alerts + existingAlerts)
                .distinctBy { it.id }
                .sortedByDescending { it.timestamp ?: Instant.EPOCH }
                .take(COPYTRADE_ALERT_HISTORY_LIMIT)

            current.copy(
                copyTradeMonitor = current.copyTradeMonitor.copy(
                    isRunning = current.copyTradeMonitor.config.enabled,
                    baselinePrimed = true,
                    lastPollAt = now,
                    lastSeenActivityAt = maxTimestamp ?: current.copyTradeMonitor.lastSeenActivityAt,
                    lastAlertAt = alerts.maxOfOrNull { it.timestamp ?: Instant.EPOCH }?.takeIf { it != Instant.EPOCH }
                        ?: current.copyTradeMonitor.lastAlertAt,
                    lastError = null,
                    recentAlerts = nextAlerts
                )
            )
        }
    }

    private fun copyTradeActivityKey(item: PolymarketUserActivityItem): String {
        return listOf(
            item.transactionHash,
            item.type,
            item.side,
            item.outcome,
            item.title,
            item.timestamp?.epochSecond?.toString()
        ).joinToString("|")
    }

    private fun buildCopyTradeAlert(
        item: PolymarketUserActivityItem,
        config: CopyTradePreferences
    ): CopyTradeRuntimeAlert? {
        val type = (item.type ?: "ACTIVITY").uppercase(Locale.US)
        val side = item.side?.uppercase(Locale.US)
        val outcome = item.outcome?.uppercase(Locale.US)
        val usdc = item.usdcSize ?: ((item.size ?: 0.0) * (item.price ?: 0.0)).takeIf { it > 0.0 }
        val titleLc = item.title.lowercase(Locale.US)
        val eventLc = (item.eventSlug ?: "").lowercase(Locale.US)

        val city = detectTrackedCityForCopyTrade(eventLc, titleLc)
        if (config.weatherOnly) {
            val isWeather = "highest-temperature" in eventLc || ("temperature" in titleLc && "highest" in titleLc)
            if (!isWeather) return null
        }
        if (config.trackedCitiesOnly && city == null) return null

        when (side) {
            "BUY" -> {
                if (!config.alertBuys) return null
                if ((usdc ?: 0.0) < config.minBuyUsdc) return null
            }
            "SELL" -> {
                if (!config.alertSells) return null
                if ((usdc ?: 0.0) < config.minSellUsdc) return null
            }
            else -> if (!config.alertOtherActivity) return null
        }

        val username = config.username.ifBlank { "usuario" }
        val sideText = side ?: type
        val usdcText = usdc?.let { "$" + String.format(Locale.US, "%.2f", it) } ?: "--"
        val priceText = item.price?.let { "${(it * 100.0).toInt()}c" } ?: "--"
        val summary = buildString {
            append(username)
            append(" ")
            append(sideText)
            outcome?.let {
                append(" ")
                append(it)
            }
            append(" • ")
            append(usdcText)
            append(" @ ")
            append(priceText)
            city?.let {
                append(" • ")
                append(it.second)
            }
        }

        return CopyTradeRuntimeAlert(
            id = copyTradeActivityKey(item),
            username = username,
            proxyWallet = config.proxyWallet,
            type = type,
            side = side,
            outcome = outcome,
            title = item.title,
            eventSlug = item.eventSlug,
            marketSlug = item.marketSlug,
            usdcSize = usdc,
            price = item.price,
            timestamp = item.timestamp,
            cityId = city?.first,
            cityName = city?.second,
            summary = summary
        )
    }

    private fun detectTrackedCityForCopyTrade(eventSlugLc: String, titleLc: String): Pair<String, String>? {
        val cityId = when {
            "nyc" in eventSlugLc || "new york" in titleLc -> "new-york"
            "sao-paulo" in eventSlugLc || "sao paulo" in titleLc -> "sao-paulo"
            "buenos-aires" in eventLcOrTitle(eventSlugLc, titleLc) -> "buenos-aires"
            "london" in eventLcOrTitle(eventSlugLc, titleLc) -> "london"
            "miami" in eventLcOrTitle(eventSlugLc, titleLc) -> "miami"
            "toronto" in eventLcOrTitle(eventSlugLc, titleLc) -> "toronto"
            "seattle" in eventLcOrTitle(eventSlugLc, titleLc) -> "seattle"
            "dallas" in eventLcOrTitle(eventSlugLc, titleLc) -> "dallas"
            "wellington" in eventLcOrTitle(eventSlugLc, titleLc) -> "wellington"
            "ankara" in eventLcOrTitle(eventSlugLc, titleLc) -> "ankara"
            "seoul" in eventLcOrTitle(eventSlugLc, titleLc) -> "seoul"
            "chicago" in eventLcOrTitle(eventSlugLc, titleLc) -> "chicago"
            "atlanta" in eventLcOrTitle(eventSlugLc, titleLc) -> "atlanta"
            "paris" in eventLcOrTitle(eventSlugLc, titleLc) -> "paris"
            else -> null
        } ?: return null
        val cityName = CityCatalog.findById(cityId)?.name ?: cityId
        return cityId to cityName
    }

    private fun eventLcOrTitle(eventSlugLc: String, titleLc: String): String = "$eventSlugLc|$titleLc"

    private fun refreshPaperTrading(cities: List<CityWeatherData>) {
        paperTradingJob?.cancel()
        paperTradingJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    isPaperTradingRefreshing = true,
                    paperTradingErrorMessage = null
                )
            }
            runCatching {
                withContext(Dispatchers.Default) {
                    repository.loadPaperPortfolio(cities)
                }
            }.onSuccess { snapshot ->
                _uiState.update { current ->
                    current.copy(
                        paperPortfolio = snapshot,
                        isPaperTradingRefreshing = false,
                        paperTradingErrorMessage = null
                    )
                }
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                _uiState.update { current ->
                    current.copy(
                        isPaperTradingRefreshing = false,
                        paperTradingErrorMessage = throwable.message
                            ?: "No se pudo actualizar paper trading"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        stopCopyTradeMonitor()
        super.onCleared()
    }
}

private const val MAX_CITY_REFRESH_CONCURRENCY = 4
private const val COPYTRADE_POLL_FETCH_LIMIT = 25
private const val COPYTRADE_SEEN_KEYS_LIMIT = 400
private const val COPYTRADE_ALERT_HISTORY_LIMIT = 40

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
