package com.polymeteo.meteotrader.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.polymeteo.meteotrader.data.WeatherRepository
import com.polymeteo.meteotrader.data.model.CityWeatherData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

data class MeteoUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val cities: List<CityWeatherData> = emptyList(),
    val lastUpdatedAt: Instant? = null,
    val errorMessage: String? = null
)

class MeteoViewModel(
    private val repository: WeatherRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeteoUiState())
    val uiState: StateFlow<MeteoUiState> = _uiState.asStateFlow()

    init {
        refresh(initialLoad = true)
    }

    fun refresh(initialLoad: Boolean = false) {
        viewModelScope.launch {
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
        viewModelScope.launch {
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
            }.onFailure { throwable ->
                _uiState.value = _uiState.value.copy(
                    errorMessage = throwable.message ?: "No se pudo actualizar la ciudad"
                )
            }
        }
    }

    fun cityById(cityId: String): CityWeatherData? {
        return _uiState.value.cities.firstOrNull { it.city.id == cityId }
    }
}

class MeteoViewModelFactory(
    private val repository: WeatherRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MeteoViewModel::class.java)) {
            return MeteoViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
