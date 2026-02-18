package com.polymeteo.meteotrader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.ForecastSourceResult
import com.polymeteo.meteotrader.data.model.SourceStatus
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.data.model.TraderDirection
import com.polymeteo.meteotrader.data.model.TraderOpportunity
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Neutral
import com.polymeteo.meteotrader.ui.theme.Positive
import com.polymeteo.meteotrader.util.convertTemperature
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
            val uriHandler = LocalUriHandler.current
            var helpTopic by remember { mutableStateOf<TraderHelpTopic?>(null) }
            val openExternalUrl: (String) -> Unit = { url ->
                runCatching { uriHandler.openUri(url) }
            }
            val observationDateTime = cityData.metar.current?.observedAt
                ?.formatInZone(cityData.city.zoneId, "dd-MM-yyyy HH:mm")
            val metarActualValue = appendObservationTime(
                temperature = formatTemperature(cityData.metarCurrentInUnit(), unit),
                observationDateTime = observationDateTime
            )
            val metarPreviousValue = appendMetarElapsedHours(
                temperature = formatTemperature(cityData.metarPreviousInUnit(), unit),
                currentObservedAt = cityData.metar.current?.observedAt,
                previousObservedAt = cityData.metar.previousSameDay?.observedAt
            )
            val controlWithSecondaryUnit = formatTemperatureWithAlternateUnit(
                valueInDisplayUnit = cityData.controlTempInUnit(),
                displayUnit = unit
            )
            val polyWithSecondaryUnit = formatTemperatureWithAlternateUnit(
                valueInDisplayUnit = cityData.polyTempInUnit(),
                displayUnit = unit
            )
            val cityZoneId = ZoneId.of(cityData.city.zoneId)
            val cityToday = LocalDate.now(cityZoneId)
            val dayTabs = listOf(
                TraderDayTab(title = "Hoy", date = cityToday),
                TraderDayTab(title = "Mañana", date = cityToday.plusDays(1)),
                TraderDayTab(title = "Pasado", date = cityToday.plusDays(2))
            )
            var selectedDayIndex by rememberSaveable(cityData.city.id) { mutableStateOf(0) }
            val selectedTab = dayTabs.getOrElse(selectedDayIndex) { dayTabs.first() }
            val selectedOpportunities = cityData.polymarket.opportunities
                .filter { opportunity ->
                    val targetDate = opportunity.condition.targetDate
                    (targetDate == null && selectedTab.date == cityToday) || targetDate == selectedTab.date
                }
                .sortedByDescending { it.expectedEdge }
            val selectedTopOpportunity = selectedOpportunities.firstOrNull()
            val opportunitiesByDate = cityData.polymarket.opportunities.groupBy { it.condition.targetDate }

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
                        DetailPair("METAR actual", metarActualValue)
                        DetailPair("METAR anterior", metarPreviousValue)
                        DetailPair("Diferencia", formatDelta(cityData.metarDeltaInUnit(), unit))
                        DetailPair("Temp estación control", controlWithSecondaryUnit)
                        DetailPair(
                            label = "PolyTEMP",
                            value = polyWithSecondaryUnit,
                            labelColor = Positive,
                            valueColor = Positive,
                            valueFontWeight = FontWeight.Bold
                        )
                        SourceLinkRow(
                            label = "Fuente METAR",
                            url = cityData.metar.sourceUrl,
                            onOpenUrl = openExternalUrl
                        )
                        SourceLinkRow(
                            label = "Fuente estación",
                            url = cityData.controlStation.sourceUrl,
                            onOpenUrl = openExternalUrl
                        )
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
                    TraderHeader(
                        cityData = cityData,
                        selectedTopOpportunity = selectedTopOpportunity,
                        selectedDayTitle = selectedTab.title,
                        onHelpRequested = { helpTopic = it }
                    )
                }

                item {
                    TraderDaySelector(
                        tabs = dayTabs,
                        selectedIndex = selectedDayIndex,
                        onSelectedIndexChanged = { selectedDayIndex = it },
                        opportunitiesByDate = opportunitiesByDate
                    )
                }

                if (selectedOpportunities.isNotEmpty()) {
                    items(
                        selectedOpportunities.take(12),
                        key = { it.marketId }
                    ) { opportunity ->
                        TraderOpportunityRow(
                            opportunity = opportunity,
                            onHelpRequested = { helpTopic = it }
                        )
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
                                text = cityData.polymarket.error
                                    ?: "Sin mercados para ${selectedTab.title.lowercase()}",
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

            helpTopic?.let { topic ->
                TraderHelpDialog(
                    topic = topic,
                    onDismiss = { helpTopic = null }
                )
            }
        }
    }
}

@Composable
private fun DetailPair(
    label: String,
    value: String,
    labelColor: Color = MutedInk,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueFontWeight: FontWeight = FontWeight.Normal
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = valueFontWeight),
            color = valueColor,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun appendObservationTime(
    temperature: String,
    observationDateTime: String?
): String {
    if (observationDateTime.isNullOrBlank() || temperature == "--") return temperature
    return "$temperature ($observationDateTime)"
}

private fun appendMetarElapsedHours(
    temperature: String,
    currentObservedAt: Instant?,
    previousObservedAt: Instant?
): String {
    if (temperature == "--" || currentObservedAt == null || previousObservedAt == null) return temperature
    val hours = elapsedHoursRounded(currentObservedAt, previousObservedAt)
    return "$temperature (Hace $hours horas)"
}

private fun elapsedHoursRounded(currentObservedAt: Instant, previousObservedAt: Instant): Long {
    val minutes = kotlin.math.abs(Duration.between(previousObservedAt, currentObservedAt).toMinutes())
    if (minutes <= 0) return 0
    return maxOf(1L, (minutes + 30L) / 60L)
}

private fun formatTemperatureWithAlternateUnit(
    valueInDisplayUnit: Double?,
    displayUnit: TempUnit
): String {
    if (valueInDisplayUnit == null) return "--"
    val otherUnit = if (displayUnit == TempUnit.C) TempUnit.F else TempUnit.C
    val otherValue = convertTemperature(valueInDisplayUnit, from = displayUnit, to = otherUnit)
    return "${formatTemperature(valueInDisplayUnit, displayUnit)} (${formatTemperature(otherValue, otherUnit)})"
}

private data class TraderDayTab(
    val title: String,
    val date: LocalDate
)

@Composable
private fun SourceLinkRow(
    label: String,
    url: String?,
    onOpenUrl: (String) -> Unit
) {
    val hasUrl = !url.isNullOrBlank()
    val safeUrl = url.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (hasUrl) Color(0xFF8FC8FF) else MutedInk,
            textDecoration = if (hasUrl) TextDecoration.Underline else TextDecoration.None,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (hasUrl) {
                        Modifier.clickable { onOpenUrl(safeUrl) }
                    } else {
                        Modifier
                    }
                )
        )
        Text(
            text = if (hasUrl) "Abrir" else "--",
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasUrl) Color(0xFF8FC8FF) else MutedInk,
            modifier = if (hasUrl) {
                Modifier.clickable { onOpenUrl(safeUrl) }
            } else {
                Modifier
            }
        )
    }
}

@Composable
private fun TraderHeader(
    cityData: CityWeatherData,
    selectedTopOpportunity: TraderOpportunity?,
    selectedDayTitle: String,
    onHelpRequested: (TraderHelpTopic) -> Unit
) {
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
        selectedTopOpportunity?.let { top ->
            Text(
                text = "Top edge ($selectedDayTitle): ${directionLabel(top.direction)} • ${top.recommendedBuy} ${formatPercent(top.expectedEdge)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp)
            )
        } ?: Text(
            text = "Sin oportunidades en $selectedDayTitle",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedInk,
            modifier = Modifier.padding(top = 6.dp)
        )

        cityData.polymarket.topOpportunity?.let { topToday ->
            if (selectedTopOpportunity?.marketId != topToday.marketId) {
                Text(
                    text = "Referencia hoy: ${directionLabel(topToday.direction)} • ${topToday.recommendedBuy} ${formatPercent(topToday.expectedEdge)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        TraderHelpTerms(onHelpRequested = onHelpRequested)
    }
}

@Composable
private fun TraderDaySelector(
    tabs: List<TraderDayTab>,
    selectedIndex: Int,
    onSelectedIndexChanged: (Int) -> Unit,
    opportunitiesByDate: Map<LocalDate?, List<TraderOpportunity>>
) {
    val todayDate = tabs.firstOrNull()?.date
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEachIndexed { index, tab ->
            val count = if (tab.date == todayDate) {
                (opportunitiesByDate[tab.date]?.size ?: 0) + (opportunitiesByDate[null]?.size ?: 0)
            } else {
                opportunitiesByDate[tab.date]?.size ?: 0
            }
            val isSelected = selectedIndex == index
            val border = if (isSelected) Color(0xFF8FC8FF) else Color(0x33FFFFFF)
            val bg = if (isSelected) Color(0x1F2C78FF) else DarkPanel
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bg)
                    .border(1.dp, border)
                    .clickable { onSelectedIndexChanged(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${tab.title} ($count)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isSelected) Color(0xFF8FC8FF) else MaterialTheme.colorScheme.onSurface
                )
            }
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
private fun TraderOpportunityRow(
    opportunity: TraderOpportunity,
    onHelpRequested: (TraderHelpTopic) -> Unit
) {
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
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClickableHelpText(
                    text = directionLabel(opportunity.direction),
                    topic = helpTopicForDirection(opportunity.direction),
                    color = color,
                    onHelpRequested = onHelpRequested
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk
                )
                ClickableHelpText(
                    text = opportunity.recommendedBuy,
                    topic = TraderHelpTopic.YES_NO,
                    color = color,
                    onHelpRequested = onHelpRequested
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk
                )
                ClickableHelpText(
                    text = signalLabel(opportunity.signal),
                    topic = helpTopicForSignal(opportunity.signal),
                    color = color,
                    onHelpRequested = onHelpRequested
                )
            }
            ClickableHelpText(
                text = "Edge ${formatPercent(opportunity.expectedEdge)}",
                topic = TraderHelpTopic.EDGE,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                onHelpRequested = onHelpRequested
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ClickableHelpText(
                text = "Model YES ${formatPercent(opportunity.modelProbabilityYes)}",
                topic = TraderHelpTopic.YES_NO,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested
            )
            ClickableHelpText(
                text = "Mkt YES ${formatPercent(opportunity.yesPrice)}",
                topic = TraderHelpTopic.YES_NO,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested,
                textAlign = TextAlign.Center
            )
            ClickableHelpText(
                text = "Liq ${opportunity.liquidity?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "--"}",
                topic = TraderHelpTopic.LIQUIDITY,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
private fun TraderHelpTerms(onHelpRequested: (TraderHelpTopic) -> Unit) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("OVER", TraderHelpTopic.OVER, onHelpRequested, Modifier.weight(1f))
            HelpPill("UNDER", TraderHelpTopic.UNDER, onHelpRequested, Modifier.weight(1f))
            HelpPill("RANGE", TraderHelpTopic.RANGE, onHelpRequested, Modifier.weight(1f))
            HelpPill("YES/NO", TraderHelpTopic.YES_NO, onHelpRequested, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("EDGE", TraderHelpTopic.EDGE, onHelpRequested, Modifier.weight(1f))
            HelpPill("VERDE", TraderHelpTopic.GREEN, onHelpRequested, Modifier.weight(1f))
            HelpPill("AMARILLO", TraderHelpTopic.YELLOW, onHelpRequested, Modifier.weight(1f))
            HelpPill("BAJO", TraderHelpTopic.RED, onHelpRequested, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HelpPill(
    text: String,
    topic: TraderHelpTopic,
    onHelpRequested: (TraderHelpTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0x1A8FC8FF))
            .border(1.dp, Color(0x558FC8FF))
            .clickable { onHelpRequested(topic) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8FC8FF),
            maxLines = 1
        )
    }
}

@Composable
private fun ClickableHelpText(
    text: String,
    topic: TraderHelpTopic,
    color: Color,
    onHelpRequested: (TraderHelpTopic) -> Unit,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelSmall
) {
    Text(
        text = text,
        style = style,
        color = color,
        textAlign = textAlign,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable { onHelpRequested(topic) }
    )
}

@Composable
private fun TraderHelpDialog(
    topic: TraderHelpTopic,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = topic.title,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Text(
                text = topic.message,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Entendido")
            }
        }
    )
}

private fun helpTopicForDirection(direction: TraderDirection): TraderHelpTopic {
    return when (direction) {
        TraderDirection.OVER -> TraderHelpTopic.OVER
        TraderDirection.UNDER -> TraderHelpTopic.UNDER
        TraderDirection.RANGE -> TraderHelpTopic.RANGE
    }
}

private fun helpTopicForSignal(signal: TraderSignalLevel): TraderHelpTopic {
    return when (signal) {
        TraderSignalLevel.GREEN -> TraderHelpTopic.GREEN
        TraderSignalLevel.YELLOW -> TraderHelpTopic.YELLOW
        TraderSignalLevel.RED -> TraderHelpTopic.RED
    }
}

private fun signalLabel(signal: TraderSignalLevel): String {
    return when (signal) {
        TraderSignalLevel.GREEN -> "VERDE"
        TraderSignalLevel.YELLOW -> "AMARILLO"
        TraderSignalLevel.RED -> "BAJO"
    }
}

private enum class TraderHelpTopic(
    val title: String,
    val message: String
) {
    OVER(
        title = "OVER",
        message = "OVER significa que compras la idea de que la maxima final quedara por encima del nivel del mercado. Sirve cuando PolyTEMP y varios modelos apuntan claramente arriba."
    ),
    UNDER(
        title = "UNDER",
        message = "UNDER significa que esperas una maxima final por debajo del nivel del mercado. Es mas solido cuando PolyTEMP y datos actuales se mantienen por debajo con margen."
    ),
    RANGE(
        title = "RANGE",
        message = "RANGE aplica cuando el mercado pregunta por un intervalo de temperatura. Si la prediccion esta cerca de los bordes del rango, el riesgo sube y suele convenir prudencia."
    ),
    YES_NO(
        title = "YES y NO",
        message = "Comprar YES apuesta a que la condicion se cumple. Comprar NO apuesta a que no se cumple. Elige el lado donde tu estimacion supere claramente al precio del mercado."
    ),
    EDGE(
        title = "EDGE",
        message = "Edge es la diferencia entre lo que estima el modelo y lo que descuenta el mercado. Cuanto mayor sea el edge positivo, mayor ventaja teorica; edge bajo implica poca ventaja."
    ),
    GREEN(
        title = "Senal VERDE",
        message = "Verde indica oportunidad fuerte: edge alto y mejor alineacion entre modelo y mercado. Aun asi, revisa liquidez y cambios recientes antes de entrar."
    ),
    YELLOW(
        title = "Senal AMARILLA",
        message = "Amarillo indica oportunidad moderada. Puede valer, pero con menos margen; suele ser mejor tamano de posicion pequeno o esperar confirmacion."
    ),
    RED(
        title = "Senal BAJA",
        message = "Bajo (antes rojo/gris) indica ventaja debil o incierta. Normalmente es zona de no apostar salvo informacion externa muy clara."
    ),
    LIQUIDITY(
        title = "Liquidez",
        message = "Liq muestra cuanto dinero hay en ese mercado. Con liquidez baja, el precio se mueve facil y cuesta entrar o salir sin perder valor."
    )
}
