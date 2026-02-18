package com.polymeteo.meteotrader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.ui.MeteoUiState
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Neutral
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Positive
import com.polymeteo.meteotrader.util.controlTempInUnit
import com.polymeteo.meteotrader.util.formatDelta
import com.polymeteo.meteotrader.util.formatInZone
import com.polymeteo.meteotrader.util.formatTemperature
import com.polymeteo.meteotrader.util.metarCurrentInUnit
import com.polymeteo.meteotrader.util.metarDeltaInUnit
import com.polymeteo.meteotrader.util.polyTempInUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitiesScreen(
    state: MeteoUiState,
    onRefresh: () -> Unit,
    onCitySelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBase)
    ) {
        HeaderBar(
            updatedText = state.lastUpdatedAt?.formatInZone("UTC", "HH:mm:ss 'UTC'") ?: "sin actualización",
            onRefresh = onRefresh
        )

        if (!state.errorMessage.isNullOrBlank()) {
            Text(
                text = state.errorMessage,
                color = Negative,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                state.isLoading && state.cities.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }

                state.cities.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No hay datos todavía",
                            color = MutedInk,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                else -> {
                    CitiesGrid(
                        cities = state.cities,
                        onCitySelected = onCitySelected
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderBar(
    updatedText: String,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "PolyMeteo",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "Actualizado: $updatedText",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }

        FilledIconButton(onClick = onRefresh) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refrescar"
            )
        }
    }
}

@Composable
private fun CitiesGrid(
    cities: List<CityWeatherData>,
    onCitySelected: (String) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        val spacing = 8.dp
        val cardHeight = (maxHeight - (spacing * 6)) / 7

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            userScrollEnabled = true
        ) {
            items(cities, key = { it.city.id }) { cityData ->
                CityCard(
                    data = cityData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cardHeight)
                        .clickable { onCitySelected(cityData.city.id) }
                )
            }
        }
    }
}

@Composable
private fun CityCard(
    data: CityWeatherData,
    modifier: Modifier = Modifier
) {
    val current = data.metarCurrentInUnit()
    val delta = data.metarDeltaInUnit()
    val control = data.controlTempInUnit()
    val poly = data.polyTempInUnit()

    val deltaColor = when {
        delta == null -> Neutral
        delta > 0 -> Positive
        delta < 0 -> Negative
        else -> Neutral
    }

    Column(
        modifier = modifier
            .background(DarkPanel)
            .border(width = 1.dp, color = Color(0x33FFFFFF))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = data.city.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = data.localTime,
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatTemperature(current, data.city.displayUnit, digits = 0),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(deltaColor.copy(alpha = 0.15f))
                    .border(1.dp, deltaColor)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = formatDelta(delta, data.city.displayUnit),
                    style = MaterialTheme.typography.labelSmall,
                    color = deltaColor
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatValue(
                label = "Station",
                value = formatTemperature(control, data.city.displayUnit),
                modifier = Modifier.weight(1f)
            )
            Box(modifier = Modifier.width(8.dp))
            StatValue(
                label = "PolyTEMP",
                value = formatTemperature(poly, data.city.displayUnit),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
