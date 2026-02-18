package com.polymeteo.meteotrader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.ForecastSourceResult
import com.polymeteo.meteotrader.data.model.SourceStatus
import com.polymeteo.meteotrader.data.model.TraderOpportunity
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Neutral
import com.polymeteo.meteotrader.ui.theme.Positive
import com.polymeteo.meteotrader.util.controlTempInUnit
import com.polymeteo.meteotrader.util.directionLabel
import com.polymeteo.meteotrader.util.formatDelta
import com.polymeteo.meteotrader.util.formatInZone
import com.polymeteo.meteotrader.util.formatPercent
import com.polymeteo.meteotrader.util.formatTemperature
import com.polymeteo.meteotrader.util.metarCurrentInUnit
import com.polymeteo.meteotrader.util.metarDeltaInUnit
import com.polymeteo.meteotrader.util.metarPreviousInUnit
import com.polymeteo.meteotrader.util.polyTempInUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityDetailScreen(
    cityData: CityWeatherData?,
    globalError: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    LaunchedEffect(cityData?.city?.id) {
        if (cityData == null) onRefresh()
    }

    Scaffold(
        containerColor = DarkBase,
        topBar = {
            TopAppBar(
                title = {
                    Text(text = cityData?.city?.name ?: "Detalle")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refrescar ciudad")
                    }
                }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (cityData == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        if (!globalError.isNullOrBlank()) {
                            Text(
                                text = globalError,
                                color = Negative,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                }
                return@PullToRefreshBox
            }

            val unit = cityData.city.displayUnit

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .background(DarkPanel)
                            .border(1.dp, Color(0x22FFFFFF))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "METAR (${cityData.city.metarCode})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DetailPair("Hora local", cityData.localTime)
                        DetailPair("METAR actual", formatTemperature(cityData.metarCurrentInUnit(), unit))
                        DetailPair("METAR anterior", formatTemperature(cityData.metarPreviousInUnit(), unit))
                        DetailPair("Diferencia", formatDelta(cityData.metarDeltaInUnit(), unit))
                        DetailPair(
                            "Observación",
                            cityData.metar.current?.observedAt?.formatInZone(cityData.city.zoneId) ?: "--"
                        )
                        DetailPair("Temp estación control", formatTemperature(cityData.controlTempInUnit(), unit))
                        DetailPair("PolyTEMP", formatTemperature(cityData.polyTempInUnit(), unit))
                        DetailPair("Fuente METAR", cityData.metar.sourceUrl)
                        DetailPair("Fuente estación", cityData.controlStation.sourceUrl ?: "--")
                        if (!cityData.metar.current?.rawText.isNullOrBlank()) {
                            Text(
                                text = cityData.metar.current?.rawText.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MutedInk,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                item {
                    ForecastHeader()
                }

                items(cityData.forecasts, key = { it.sourceId }) { forecast ->
                    ForecastRow(
                        forecast = forecast,
                        cityData = cityData
                    )
                }

                item {
                    TraderHeader(cityData = cityData)
                }

                if (cityData.polymarket.opportunities.isNotEmpty()) {
                    items(
                        cityData.polymarket.opportunities.take(12),
                        key = { it.marketId }
                    ) { opportunity ->
                        TraderOpportunityRow(opportunity = opportunity)
                    }
                } else {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .background(DarkPanel)
                                .border(1.dp, Color(0x22FFFFFF))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = cityData.polymarket.error ?: "Sin mercados detectados",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MutedInk
                            )
                        }
                    }
                }

                if (cityData.warnings.isNotEmpty() || !globalError.isNullOrBlank()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .background(DarkPanel)
                                .border(1.dp, Color(0x22FFFFFF))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Errores y advertencias",
                                style = MaterialTheme.typography.titleMedium,
                                color = Negative
                            )
                            cityData.warnings.forEach { warning ->
                                Text(
                                    text = "• $warning",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            if (!globalError.isNullOrBlank()) {
                                Text(
                                    text = "• $globalError",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Negative,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Actualizado: ${cityData.updatedAt.formatInZone(cityData.city.zoneId)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedInk,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailPair(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TraderHeader(cityData: CityWeatherData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(Color(0x101C2EFF))
            .border(1.dp, Color(0x2F77C4FF))
            .padding(12.dp)
    ) {
        Text(
            text = "Trader Mode",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF8FC8FF)
        )
        Text(
            text = "Query: ${cityData.polymarket.query}",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "Mercados evaluados: ${cityData.polymarket.marketsScanned}",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk
        )
        cityData.polymarket.topOpportunity?.let { top ->
            Text(
                text = "Top edge: ${directionLabel(top.direction)} • ${top.recommendedBuy} ${formatPercent(top.expectedEdge)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ForecastHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(Color(0x131D2CFF))
            .border(1.dp, Color(0x22FFFFFF))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Fuente",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.weight(1.8f)
        )
        Text(
            text = "Now",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Max",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Estado",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.weight(1.2f)
        )
    }
}

@Composable
private fun ForecastRow(
    forecast: ForecastSourceResult,
    cityData: CityWeatherData
) {
    val unit = cityData.city.displayUnit
    val nowValue = if (unit.symbol == "C") forecast.currentTempC else forecast.currentTempF
    val maxValue = if (unit.symbol == "C") forecast.maxTempC else forecast.maxTempF
    val statusColor = when (forecast.status) {
        SourceStatus.SUCCESS -> Positive
        SourceStatus.ERROR -> Negative
        SourceStatus.SKIPPED -> Neutral
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = forecast.sourceName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1.8f)
            )
            Text(
                text = formatTemperature(nowValue, unit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatTemperature(maxValue, unit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = forecast.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                modifier = Modifier.weight(1.2f)
            )
        }

        if (!forecast.error.isNullOrBlank()) {
            Text(
                text = forecast.error,
                style = MaterialTheme.typography.labelSmall,
                color = Negative,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TraderOpportunityRow(opportunity: TraderOpportunity) {
    val color = when (opportunity.signal) {
        TraderSignalLevel.GREEN -> Positive
        TraderSignalLevel.YELLOW -> Color(0xFFFFC857)
        TraderSignalLevel.RED -> Neutral
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = opportunity.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${directionLabel(opportunity.direction)} • ${opportunity.recommendedBuy}",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
            Text(
                text = "Edge ${formatPercent(opportunity.expectedEdge)}",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Model YES ${formatPercent(opportunity.modelProbabilityYes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
            Text(
                text = "Mkt YES ${formatPercent(opportunity.yesPrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
            Text(
                text = "Liq ${opportunity.liquidity?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "--"}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }
    }
}
