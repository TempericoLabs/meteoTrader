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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import com.polymeteo.meteotrader.ui.MeteoUiState
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Neutral
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Positive
import com.polymeteo.meteotrader.util.controlTempInUnit
import com.polymeteo.meteotrader.util.directionLabel
import com.polymeteo.meteotrader.util.formatDelta
import com.polymeteo.meteotrader.util.formatInZone
import com.polymeteo.meteotrader.util.formatPercent
import com.polymeteo.meteotrader.util.formatTemperature
import com.polymeteo.meteotrader.util.metarCurrentInUnit
import com.polymeteo.meteotrader.util.metarDeltaInUnit
import com.polymeteo.meteotrader.util.polyTempInUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitiesScreen(
    state: MeteoUiState,
    onRefresh: () -> Unit,
    onBacktestSelected: () -> Unit,
    onCitySelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBase)
    ) {
        val topEdges = state.cities
            .mapNotNull { city -> city.polymarket.topOpportunity?.let { city.city.name to it } }
            .sortedByDescending { (_, opp) -> opp.expectedEdge }

        HeaderBar(
            updatedText = state.lastUpdatedAt?.formatInZone("UTC", "HH:mm:ss 'UTC'") ?: "sin actualización",
            onRefresh = onRefresh,
            onBacktestSelected = onBacktestSelected,
            isBacktestRefreshing = state.isBacktestRefreshing
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

        if (topEdges.isNotEmpty()) {
            TopEdgesBanner(
                edges = topEdges.take(3)
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
    onRefresh: () -> Unit,
    onBacktestSelected: () -> Unit,
    isBacktestRefreshing: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .padding(top = 4.dp),
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledIconButton(onClick = onBacktestSelected) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.QueryStats,
                    contentDescription = "Backtesting"
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

    if (isBacktestRefreshing) {
        Text(
            text = "Backtesting actualizando...",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        )
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
    val topTrader = data.polymarket.topOpportunity

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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = data.localTime,
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InlineMetric(
                label = "M",
                value = formatTemperature(current, data.city.displayUnit, digits = 0),
                modifier = Modifier.weight(1f)
            )
            InlineMetric(
                label = "Δ",
                value = formatDelta(delta, data.city.displayUnit),
                valueColor = deltaColor,
                modifier = Modifier.weight(1f)
            )
            InlineMetric(
                label = "S",
                value = formatTemperature(control, data.city.displayUnit, digits = 0),
                modifier = Modifier.weight(1f)
            )
            InlineMetric(
                label = "P",
                value = formatTemperature(poly, data.city.displayUnit, digits = 0),
                modifier = Modifier.weight(1f)
            )
        }

        if (topTrader != null) {
            val traderColor = when (topTrader.signal) {
                TraderSignalLevel.GREEN -> Positive
                TraderSignalLevel.YELLOW -> Color(0xFFFFC857)
                TraderSignalLevel.RED -> Neutral
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(traderColor.copy(alpha = 0.12f))
                    .border(1.dp, traderColor)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = directionLabel(topTrader.direction),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = traderColor,
                    maxLines = 1
                )
                Text(
                    text = "${topTrader.recommendedBuy} ${formatPercent(topTrader.expectedEdge)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = traderColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun InlineMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TopEdgesBanner(
    edges: List<Pair<String, com.polymeteo.meteotrader.data.model.TraderOpportunity>>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Color(0x101C2EFF))
            .border(1.dp, Color(0x2F77C4FF))
            .padding(8.dp)
    ) {
        Text(
            text = "Top Edges",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8FC8FF)
        )

        edges.forEach { (city, opp) ->
            Text(
                text = "$city • ${directionLabel(opp.direction)} • ${opp.recommendedBuy} ${formatPercent(opp.expectedEdge)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
