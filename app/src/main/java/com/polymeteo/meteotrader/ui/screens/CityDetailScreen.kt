package com.polymeteo.meteotrader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.DecisionTraceEntry
import com.polymeteo.meteotrader.data.model.DecisionTraceStatus
import com.polymeteo.meteotrader.data.model.ForecastSourceResult
import com.polymeteo.meteotrader.data.model.PaperPortfolioSnapshot
import com.polymeteo.meteotrader.data.model.PaperPosition
import com.polymeteo.meteotrader.data.model.PaperPositionStatus
import com.polymeteo.meteotrader.data.model.PremiumComputationSource
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
import com.polymeteo.meteotrader.util.observedMaxInUnit
import com.polymeteo.meteotrader.util.polyTempInUnit
import com.polymeteo.meteotrader.util.polyTempPremiumInUnit
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityDetailScreen(
    cityData: CityWeatherData?,
    globalError: String?,
    isPremiumCalibrating: Boolean,
    paperPortfolio: PaperPortfolioSnapshot,
    isPaperTradingRefreshing: Boolean,
    paperTradingErrorMessage: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshPaperTrading: () -> Unit,
    onOpenPolyTempPremium: () -> Unit,
    onSimulateBuyYes: (marketId: String, stakeUsdc: Double) -> Unit,
    onSimulateClosePosition: (positionId: String) -> Unit
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
            var pendingBuyOpportunity by remember(cityData.city.id) { mutableStateOf<TraderOpportunity?>(null) }
            var pendingStakeInput by rememberSaveable(cityData.city.id) { mutableStateOf("10") }
            val openExternalUrl: (String) -> Unit = { url ->
                runCatching { uriHandler.openUri(url) }
            }
            val observationDateTime = cityData.metar.current?.observedAt
                ?.formatInZone(cityData.city.zoneId, "dd-MM-yyyy HH:mm")
            val controlWithSecondaryUnit = formatTemperatureWithAlternateUnit(
                valueInDisplayUnit = cityData.controlTempInUnit(),
                displayUnit = unit
            )
            val tafIssuedAt = cityData.taf.issuedAt.formatInZone(cityData.city.zoneId, "dd-MM-yyyy HH:mm")
            val tafValidity = formatTafValidityWindow(
                from = cityData.taf.validFrom,
                to = cityData.taf.validTo,
                zoneId = cityData.city.zoneId
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
            val isTodayTab = selectedTab.date == cityToday
            val selectedHorizon = cityData.horizons.firstOrNull { horizon ->
                horizon.targetDate == selectedTab.date
            } ?: cityData.horizons.firstOrNull()
            val selectedForecasts = selectedHorizon?.forecasts ?: cityData.forecasts
            val selectedPolyInUnit = if (unit == TempUnit.C) selectedHorizon?.polyTempC else selectedHorizon?.polyTempF
            val selectedPolyPremiumInUnit = if (unit == TempUnit.C) {
                selectedHorizon?.polyTempPremiumC
            } else {
                selectedHorizon?.polyTempPremiumF
            }
            val polyWithSecondaryUnit = formatTemperatureWithAlternateUnit(
                valueInDisplayUnit = selectedPolyInUnit,
                displayUnit = unit
            )
            val polyPremiumWithSecondaryUnit = formatTemperatureWithAlternateUnit(
                valueInDisplayUnit = selectedPolyPremiumInUnit,
                displayUnit = unit
            )
            val observedMaxInDisplayUnit = cityData.observedMaxInUnit()
            val polyTempInvalid = selectedHorizon?.polyTempInvalid == true
            val polyTempPremiumReady = selectedHorizon?.polyTempPremiumReady == true
            val polyTempPremiumInvalid = selectedHorizon?.polyTempPremiumInvalid == true
            val polyTempPremiumSource = selectedHorizon?.polyTempPremiumSource ?: PremiumComputationSource.UNAVAILABLE
            val activeModelUsesPremium = polyTempPremiumReady
            val activeModelInvalid = if (activeModelUsesPremium) {
                polyTempPremiumInvalid
            } else {
                polyTempInvalid
            }
            val polyDisplayColor = if (polyTempInvalid) Negative else Positive
            val polyPremiumDisplayColor = when {
                !polyTempPremiumReady -> MutedInk
                polyTempPremiumInvalid -> Negative
                else -> Positive
            }
            val polyDisplayValue = appendForecastInvalidSuffix(
                temperature = polyWithSecondaryUnit,
                invalid = polyTempInvalid
            )
            val polyPremiumDisplayValue = if (!polyTempPremiumReady) {
                "CALCULANDO..."
            } else {
                appendForecastInvalidSuffix(
                    temperature = polyPremiumWithSecondaryUnit,
                    invalid = polyTempPremiumInvalid
                )
            }
            val premiumSourceLabel = when {
                !polyTempPremiumReady || polyTempPremiumSource == PremiumComputationSource.BUILDING ->
                    "MMA en construcción (sin cache válida todavía)"
                isPremiumCalibrating ->
                    "MMA disponible (actualizando en segundo plano)"
                polyTempPremiumSource == PremiumComputationSource.PREFERENCES_CACHE ->
                    "MMA leída de preferencias (sesión anterior)"
                polyTempPremiumSource == PremiumComputationSource.RECALCULATED ->
                    "MMA recalculada en esta sesión"
                else -> "MMA sin cache previa"
            }
            val premiumSourceColor = if (polyTempPremiumSource == PremiumComputationSource.PREFERENCES_CACHE) {
                Color(0xFF8FC8FF)
            } else {
                MutedInk
            }
            val observedMaxLabel = formatTemperature(observedMaxInDisplayUnit, unit)
            val metarActualValue = if (isTodayTab) {
                appendObservationTime(
                    temperature = formatTemperature(cityData.metarCurrentInUnit(), unit),
                    observationDateTime = observationDateTime
                )
            } else {
                "SIN METAR"
            }
            val metarPreviousValue = if (isTodayTab) {
                appendMetarElapsedHours(
                    temperature = formatTemperature(cityData.metarPreviousInUnit(), unit),
                    currentObservedAt = cityData.metar.current?.observedAt,
                    previousObservedAt = cityData.metar.previousSameDay?.observedAt
                )
            } else {
                "SIN METAR"
            }
            val metarDeltaValue = if (isTodayTab) {
                formatDelta(cityData.metarDeltaInUnit(), unit)
            } else {
                "SIN METAR"
            }
            val selectedCandidates = cityData.polymarket.opportunities
                .filter { opportunity ->
                    val targetDate = opportunity.condition.targetDate
                    (targetDate == null && selectedTab.date == cityToday) || targetDate == selectedTab.date
                }
            val selectedTopOpportunity = selectedCandidates.maxByOrNull { it.executableEdge }
            val selectedOpportunities = if (selectedTab.date == cityToday && cityData.observedMaxC != null) {
                selectedCandidates
                    .sortedWith(
                        compareByDescending<TraderOpportunity> { it.modelProbabilityYes }
                            .thenBy { it.condition.threshold }
                    )
            } else {
                selectedCandidates.sortedByDescending { it.executableEdge }
            }
            val selectedDecisionTrace = cityData.polymarket.decisionTrace
                .filter { trace ->
                    val traceDate = trace.targetDate ?: cityToday
                    traceDate == selectedTab.date
                }
                .sortedWith(
                    compareBy<DecisionTraceEntry> { trace ->
                        if (trace.status == DecisionTraceStatus.DISCARDED) 0 else 1
                    }.thenBy { trace -> trace.stage }
                )
            val opportunitiesByDate = cityData.polymarket.opportunities.groupBy { it.condition.targetDate }
            val cityPaperPositions = paperPortfolio.positions.filter { position ->
                position.cityId == cityData.city.id
            }
            val cityOpenPaperPositions = cityPaperPositions.filter { position ->
                position.status == PaperPositionStatus.OPEN
            }

            LaunchedEffect(cityData.city.id, cityOpenPaperPositions.size) {
                if (cityOpenPaperPositions.isEmpty()) return@LaunchedEffect
                while (true) {
                    delay(PAPER_AUTO_SYNC_MS)
                    onRefresh()
                    onRefreshPaperTrading()
                }
            }

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
                        DetailPair("Diferencia", metarDeltaValue)
                        DetailPair("Temp estación control", controlWithSecondaryUnit)
                        DetailPair(
                            label = "Media Modelos (MM)",
                            value = polyDisplayValue,
                            labelColor = polyDisplayColor,
                            valueColor = polyDisplayColor,
                            valueFontWeight = FontWeight.Bold
                        )
                        PolyTempPremiumLink(
                            value = polyPremiumDisplayValue,
                            valueColor = polyPremiumDisplayColor,
                            onClick = onOpenPolyTempPremium
                        )
                        Text(
                            text = premiumSourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = premiumSourceColor,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        if (activeModelInvalid) {
                            Text(
                                text = "Modelo activo invalidado hoy: ya se observo ${observedMaxLabel} en la ciudad.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Negative,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HelpPill(
                                text = "TAF",
                                topic = TraderHelpTopic.TAF,
                                onHelpRequested = { helpTopic = it },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        DetailPair("TAF emitido", tafIssuedAt)
                        DetailPair("TAF vigencia", tafValidity)
                        DetailPair("TAF resumen", cityData.taf.summary ?: "--")
                        if (!cityData.taf.error.isNullOrBlank()) {
                            Text(
                                text = cityData.taf.error,
                                style = MaterialTheme.typography.labelSmall,
                                color = Negative,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        SourceLinkRow(
                            label = "Fuente METAR",
                            url = cityData.metar.sourceUrl,
                            onOpenUrl = openExternalUrl
                        )
                        SourceLinkRow(
                            label = "Fuente TAF",
                            url = cityData.taf.sourceUrl,
                            onOpenUrl = openExternalUrl
                        )
                        SourceLinkRow(
                            label = "Fuente estación",
                            url = cityData.controlStation.sourceUrl,
                            onOpenUrl = openExternalUrl
                        )
                        if (isTodayTab && !cityData.metar.current?.rawText.isNullOrBlank()) {
                            Text(
                                text = cityData.metar.current?.rawText.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MutedInk,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        if (!cityData.taf.rawText.isNullOrBlank()) {
                            Text(
                                text = cityData.taf.rawText.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MutedInk,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }

                item {
                    ForecastHeader()
                }

                items(selectedForecasts, key = { it.sourceId }) { forecast ->
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
                    PaperTradingCityCard(
                        cityName = cityData.city.name,
                        cityOpenPositions = cityOpenPaperPositions,
                        openUnrealizedPnlUsdc = cityOpenPaperPositions.sumOf { it.unrealizedPnlUsdc ?: 0.0 },
                        totalOpenValueUsdc = cityOpenPaperPositions.sumOf { it.currentValueUsdc ?: 0.0 },
                        totalOpenCostUsdc = cityOpenPaperPositions.sumOf { it.totalCostUsdc },
                        portfolioOpenPositions = paperPortfolio.openPositions,
                        portfolioClosedPositions = paperPortfolio.closedPositions,
                        portfolioOpenUnrealizedPnlUsdc = paperPortfolio.openUnrealizedPnlUsdc,
                        portfolioClosedRealizedPnlUsdc = paperPortfolio.closedRealizedPnlUsdc,
                        generatedAtLabel = paperPortfolio.generatedAt.formatInZone(
                            cityData.city.zoneId,
                            "dd-MM-yyyy HH:mm"
                        ),
                        isRefreshing = isPaperTradingRefreshing,
                        errorMessage = paperTradingErrorMessage,
                        warnings = paperPortfolio.warnings,
                        onRefresh = onRefreshPaperTrading,
                        onClosePosition = onSimulateClosePosition
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

                item {
                    OperationalTraceabilityCard(
                        selectedDayTitle = selectedTab.title,
                        traces = selectedDecisionTrace
                    )
                }

                if (selectedOpportunities.isNotEmpty()) {
                    items(
                        selectedOpportunities.take(12),
                        key = { it.marketId }
                    ) { opportunity ->
                        TraderOpportunityRow(
                            opportunity = opportunity,
                            isPaperTradingRefreshing = isPaperTradingRefreshing,
                            onHelpRequested = { helpTopic = it },
                            onSimulateBuyYesRequested = {
                                pendingStakeInput = "10"
                                pendingBuyOpportunity = opportunity
                            }
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

            pendingBuyOpportunity?.let { opportunity ->
                SimulatedBuyYesDialog(
                    opportunity = opportunity,
                    stakeInput = pendingStakeInput,
                    onStakeInputChange = { pendingStakeInput = it },
                    onDismiss = { pendingBuyOpportunity = null },
                    onConfirm = { stakeUsdc ->
                        onSimulateBuyYes(opportunity.marketId, stakeUsdc)
                        pendingBuyOpportunity = null
                    }
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

@Composable
private fun PolyTempPremiumLink(
    value: String,
    valueColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Text(
            text = "Media Modelos Ajustada (MMA)",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color(0xFF8FC8FF),
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onClick() }
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .padding(top = 2.dp)
                .clickable { onClick() }
        )
    }
}

private fun appendForecastInvalidSuffix(
    temperature: String,
    invalid: Boolean
): String {
    if (!invalid || temperature == "--") return temperature
    return "$temperature (No valida hoy)"
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

private fun formatTafValidityWindow(
    from: Instant?,
    to: Instant?,
    zoneId: String
): String {
    if (from == null && to == null) return "--"
    val fromText = from.formatInZone(zoneId, "dd-MM-yyyy HH:mm")
    val toText = to.formatInZone(zoneId, "dd-MM-yyyy HH:mm")
    return "$fromText -> $toText"
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
                text = "Top ejecutable ($selectedDayTitle): ${directionLabel(top.direction)} • ${top.recommendedBuy} ${formatPercent(top.executableEdge)} • ${if (top.shouldTrade) "BET" else "PASS"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = "Bruto ${formatPercent(top.rawEdge)} • Costes ${formatPercent(top.totalCost)} • Fill ${formatPercent(top.fillProbability)}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk,
                modifier = Modifier.padding(top = 4.dp)
            )
        } ?: Text(
            text = "Sin oportunidades en $selectedDayTitle",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedInk,
            modifier = Modifier.padding(top = 6.dp)
        )

        cityData.polymarket.topOpportunity?.let { topToday ->
            if (selectedTopOpportunity?.marketId != topToday.marketId) {
                val referenceHorizon = detailOpportunityDayLabel(cityData, topToday)
                Text(
                    text = "Referencia ${referenceHorizon.lowercase()}: ${directionLabel(topToday.direction)} • ${topToday.recommendedBuy} ${formatPercent(topToday.executableEdge)} • ${if (topToday.shouldTrade) "BET" else "PASS"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        ClickableHelpText(
            text = "Calibracion local activa: ciudad + horizonte + franja",
            topic = TraderHelpTopic.CALIBRATION,
            color = MutedInk,
            onHelpRequested = onHelpRequested,
            modifier = Modifier.padding(top = 4.dp)
        )

        TraderHelpTerms(onHelpRequested = onHelpRequested)
    }
}

private fun detailOpportunityDayLabel(
    cityData: CityWeatherData,
    opportunity: TraderOpportunity
): String {
    val cityToday = LocalDate.now(ZoneId.of(cityData.city.zoneId))
    val targetDate = opportunity.condition.targetDate ?: cityToday
    return when (targetDate) {
        cityToday -> "Hoy"
        cityToday.plusDays(1) -> "Mañana"
        cityToday.plusDays(2) -> "Pasado"
        else -> targetDate.format(DateTimeFormatter.ofPattern("dd/MM", Locale.US))
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
private fun OperationalTraceabilityCard(
    selectedDayTitle: String,
    traces: List<DecisionTraceEntry>
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(Color(0x101C2EFF))
            .border(1.dp, Color(0x2F77C4FF))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Trazabilidad operativa ($selectedDayTitle)",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF8FC8FF)
        )
        if (traces.isEmpty()) {
            Text(
                text = "Sin trazas de decisión para este día.",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        } else {
            traces.take(12).forEach { trace ->
                val statusColor = if (trace.status == DecisionTraceStatus.KEPT) Positive else Negative
                val statusLabel = if (trace.status == DecisionTraceStatus.KEPT) "MANTENIDO" else "DESCARTADO"
                Text(
                    text = "$statusLabel • ${traceStageLabel(trace.stage)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColor
                )
                Text(
                    text = trace.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (trace.details.isNotEmpty()) {
                    Text(
                        text = trace.details.take(3).joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedInk
                    )
                }
            }
        }
    }
}

private fun traceStageLabel(stage: String): String {
    return when (stage) {
        "INPUT" -> "Entrada modelo"
        "EXECUTION_CONTROL" -> "Control ejecución"
        "DOMINANCE" -> "Dominancia"
        "LIVE_VIABILITY" -> "Viabilidad en vivo"
        else -> stage
    }
}

@Composable
private fun PaperTradingCityCard(
    cityName: String,
    cityOpenPositions: List<PaperPosition>,
    openUnrealizedPnlUsdc: Double,
    totalOpenValueUsdc: Double,
    totalOpenCostUsdc: Double,
    portfolioOpenPositions: Int,
    portfolioClosedPositions: Int,
    portfolioOpenUnrealizedPnlUsdc: Double,
    portfolioClosedRealizedPnlUsdc: Double,
    generatedAtLabel: String,
    isRefreshing: Boolean,
    errorMessage: String?,
    warnings: List<String>,
    onRefresh: () -> Unit,
    onClosePosition: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(Color(0x1A3D1F))
            .border(1.dp, Color(0xAA31D26B))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MODO SIMULACION ACTIVO",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = Color(0xFF6DF7A8)
            )
            TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                Text(
                    text = if (isRefreshing) "Actualizando..." else "Refrescar sim",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isRefreshing) MutedInk else Color(0xFF6DF7A8)
                )
            }
        }

        Text(
            text = "Solo simulación (paper). Ninguna operación se envía a Polymarket.",
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk
        )
        Text(
            text = "Generado: $generatedAtLabel",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk
        )
        Text(
            text = "Auto-sync cada 20s mientras haya posiciones abiertas en esta ciudad.",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$cityName abiertas: ${cityOpenPositions.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Global abiertas/cerradas: $portfolioOpenPositions/$portfolioClosedPositions",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Coste abierto ciudad: ${formatUsd(totalOpenCostUsdc)}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
            Text(
                text = "Valor abierto ciudad: ${formatUsd(totalOpenValueUsdc)}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }

        val cityPnlColor = if (openUnrealizedPnlUsdc >= 0) Positive else Negative
        val globalPnlColor = if (portfolioOpenUnrealizedPnlUsdc >= 0) Positive else Negative
        val globalRealizedColor = if (portfolioClosedRealizedPnlUsdc >= 0) Positive else Negative

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "PnL abierto ciudad: ${formatSignedUsd(openUnrealizedPnlUsdc)}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = cityPnlColor
            )
            Text(
                text = "PnL abierto global: ${formatSignedUsd(portfolioOpenUnrealizedPnlUsdc)}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = globalPnlColor
            )
        }

        Text(
            text = "PnL cerrado global: ${formatSignedUsd(portfolioClosedRealizedPnlUsdc)}",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = globalRealizedColor
        )

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = Negative
            )
        }

        if (warnings.isNotEmpty()) {
            warnings.take(2).forEach { warning ->
                Text(
                    text = "Aviso: $warning",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFC857)
                )
            }
        }

        if (cityOpenPositions.isEmpty()) {
            Text(
                text = "No hay posiciones abiertas en esta ciudad.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedInk
            )
        } else {
            cityOpenPositions.take(5).forEach { position ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x112B2B2B))
                        .border(1.dp, Color(0x3344FF88))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = position.question,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "YES ${formatPercent(position.entryYesPrice)} • Stake ${formatUsd(position.stakeUsdc)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedInk
                        )
                        Text(
                            text = "Mark ${formatPercent(position.currentYesPrice ?: position.entryYesPrice)} • PnL ${formatSignedUsd(position.unrealizedPnlUsdc ?: 0.0)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if ((position.unrealizedPnlUsdc ?: 0.0) >= 0) Positive else Negative
                        )
                    }
                    TextButton(
                        onClick = { onClosePosition(position.id) },
                        enabled = !isRefreshing
                    ) {
                        Text(
                            text = "Cerrar (sim)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFFFFC857)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SimulatedBuyYesDialog(
    opportunity: TraderOpportunity,
    stakeInput: String,
    onStakeInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var localError by remember(opportunity.marketId) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Simular compra YES")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Mercado: ${opportunity.question}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Precio YES actual: ${formatPercent(opportunity.yesPrice)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk
                )
                Text(
                    text = "Modo paper: no se envía ninguna orden real.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6DF7A8)
                )
                OutlinedTextField(
                    value = stakeInput,
                    onValueChange = {
                        localError = null
                        onStakeInputChange(it)
                    },
                    singleLine = true,
                    label = { Text("Cantidad USDC") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                if (!localError.isNullOrBlank()) {
                    Text(
                        text = localError.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Negative
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = parseStake(stakeInput)
                    if (parsed == null || parsed <= 0.0) {
                        localError = "Introduce una cantidad válida en USDC."
                    } else {
                        onConfirm(parsed)
                    }
                }
            ) {
                Text("Confirmar compra SI (sim)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

private fun parseStake(raw: String): Double? {
    val normalized = raw
        .trim()
        .replace(",", ".")
    if (normalized.isBlank()) return null
    return normalized.toDoubleOrNull()
}

private fun formatUsd(value: Double): String {
    return "$" + String.format(Locale.US, "%.2f", value)
}

private fun formatSignedUsd(value: Double): String {
    val sign = if (value >= 0) "+" else "-"
    return "$sign$" + String.format(Locale.US, "%.2f", kotlin.math.abs(value))
}

private const val PAPER_AUTO_SYNC_MS = 20_000L

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
    isPaperTradingRefreshing: Boolean,
    onHelpRequested: (TraderHelpTopic) -> Unit,
    onSimulateBuyYesRequested: () -> Unit
) {
    val signalColor = when (opportunity.signal) {
        TraderSignalLevel.GREEN -> Positive
        TraderSignalLevel.YELLOW -> Color(0xFFFFC857)
        TraderSignalLevel.RED -> Neutral
    }
    val actionColor = if (opportunity.shouldTrade) Positive else Neutral
    val canSimulateBuyYes = opportunity.shouldTrade && opportunity.signal != TraderSignalLevel.RED

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
                    color = signalColor,
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
                    color = signalColor,
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
                    color = signalColor,
                    onHelpRequested = onHelpRequested
                )
            }
            ClickableHelpText(
                text = if (opportunity.shouldTrade) "BET" else "PASS",
                topic = TraderHelpTopic.ACTION,
                style = MaterialTheme.typography.labelSmall,
                color = actionColor,
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
                text = "Exec ${formatPercent(opportunity.executableEdge)}",
                topic = TraderHelpTopic.EXECUTABLE_EDGE,
                color = signalColor,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested
            )
            ClickableHelpText(
                text = "Bruto ${formatPercent(opportunity.rawEdge)}",
                topic = TraderHelpTopic.RAW_EDGE,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested,
                textAlign = TextAlign.Center
            )
            ClickableHelpText(
                text = "Costes ${formatPercent(opportunity.totalCost)}",
                topic = TraderHelpTopic.COSTS,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested,
                textAlign = TextAlign.End
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ClickableHelpText(
                text = "Fill ${formatPercent(opportunity.fillProbability)}",
                topic = TraderHelpTopic.FILL,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested
            )
            ClickableHelpText(
                text = "Post-coste ${formatPercent(opportunity.edgeAfterCosts)}",
                topic = TraderHelpTopic.EDGE,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested,
                textAlign = TextAlign.Center
            )
            ClickableHelpText(
                text = "Fee ${formatPercent(opportunity.feeCost)}",
                topic = TraderHelpTopic.COSTS,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested,
                textAlign = TextAlign.End
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ClickableHelpText(
                text = "Mkt NO ${formatPercent(opportunity.noPrice)}",
                topic = TraderHelpTopic.YES_NO,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested
            )
            ClickableHelpText(
                text = "Spread ${opportunity.spread?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "--"}",
                topic = TraderHelpTopic.SPREAD,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested,
                textAlign = TextAlign.Center
            )
            ClickableHelpText(
                text = "LiqCost ${formatPercent(opportunity.liquidityCost)}",
                topic = TraderHelpTopic.COSTS,
                color = MutedInk,
                modifier = Modifier.weight(1f),
                onHelpRequested = onHelpRequested,
                textAlign = TextAlign.End
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Color(0x1A2E7D32))
                .border(1.dp, Color(0x552ED573))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SIMULACION (paper) • No envía órdenes reales",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF6DF7A8)
                )
                Text(
                    text = "Acción disponible: Comprar SI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk
                )
            }
            TextButton(
                onClick = onSimulateBuyYesRequested,
                enabled = canSimulateBuyYes && !isPaperTradingRefreshing
            ) {
                Text(
                    text = if (isPaperTradingRefreshing) "Procesando..." else "Comprar SI (sim)",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (canSimulateBuyYes) Color(0xFF6DF7A8) else MutedInk
                )
            }
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
            HelpPill("CALIB", TraderHelpTopic.CALIBRATION, onHelpRequested, Modifier.weight(1f))
            HelpPill("EJEC", TraderHelpTopic.EXECUTABLE_EDGE, onHelpRequested, Modifier.weight(1f))
            HelpPill("BRUTO", TraderHelpTopic.RAW_EDGE, onHelpRequested, Modifier.weight(1f))
            HelpPill("COSTE", TraderHelpTopic.COSTS, onHelpRequested, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("FILL", TraderHelpTopic.FILL, onHelpRequested, Modifier.weight(1f))
            HelpPill("BET/PASS", TraderHelpTopic.ACTION, onHelpRequested, Modifier.weight(1f))
            HelpPill("LIQ", TraderHelpTopic.LIQUIDITY, onHelpRequested, Modifier.weight(1f))
            HelpPill("SPREAD", TraderHelpTopic.SPREAD, onHelpRequested, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("VERDE", TraderHelpTopic.GREEN, onHelpRequested, Modifier.weight(1f))
            HelpPill("AMARILLO", TraderHelpTopic.YELLOW, onHelpRequested, Modifier.weight(1f))
            HelpPill("BAJO", TraderHelpTopic.RED, onHelpRequested, Modifier.weight(1f))
            HelpPill("EDGE", TraderHelpTopic.EDGE, onHelpRequested, Modifier.weight(1f))
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
        message = "OVER significa que compras la idea de que la maxima final quedara por encima del nivel del mercado. Sirve cuando la Media Modelos (MM) y varios modelos apuntan claramente arriba."
    ),
    UNDER(
        title = "UNDER",
        message = "UNDER significa que esperas una maxima final por debajo del nivel del mercado. Es mas solido cuando la Media Modelos (MM) y datos actuales se mantienen por debajo con margen."
    ),
    RANGE(
        title = "RANGE",
        message = "RANGE aplica cuando el mercado pregunta por un intervalo de temperatura. Si la prediccion esta cerca de los bordes del rango, el riesgo sube y suele convenir prudencia."
    ),
    YES_NO(
        title = "YES y NO",
        message = "Comprar YES apuesta a que la condicion se cumple. Comprar NO apuesta a que no se cumple. Elige el lado donde tu estimacion supere claramente al precio del mercado."
    ),
    CALIBRATION(
        title = "Calibracion local",
        message = "El modelo ajusta sigma y ponderacion de probabilidad por ciudad, por horizonte (hoy/manana/pasado) y por franja horaria local para reducir sobreconfianza y mejorar precision."
    ),
    EXECUTABLE_EDGE(
        title = "Edge ejecutable",
        message = "Edge ejecutable es la ventaja final que queda despues de descontar costes y multiplicar por la probabilidad de ejecucion. Es el edge que realmente se puede intentar capturar."
    ),
    RAW_EDGE(
        title = "Edge bruto",
        message = "Edge bruto compara solo modelo vs precio, sin costes. Sirve para ver si hay idea base, pero no basta para decidir entrada real."
    ),
    EDGE(
        title = "EDGE",
        message = "Post-coste es el edge que queda tras restar comision, spread e impacto de liquidez, antes de aplicar fill probability. Si ya es bajo aqui, suele ser mejor no entrar."
    ),
    COSTS(
        title = "Costes",
        message = "Costes agrupa fee estimada, spread y penalizacion por liquidez. Si los costes comen el edge bruto, la operacion deja de ser atractiva."
    ),
    FILL(
        title = "Fill probability",
        message = "Fill es la probabilidad de poder ejecutar la orden cerca del precio esperado. Baja liquidez o spread alto bajan este valor y reducen edge ejecutable."
    ),
    ACTION(
        title = "BET o PASS",
        message = "BET significa que la oportunidad supera umbrales minimos de edge ejecutable y fill. PASS significa que hay ventaja teorica insuficiente o dificil de ejecutar."
    ),
    SPREAD(
        title = "Spread",
        message = "Spread es la distancia entre mejor compra y mejor venta. Cuanto mas alto, peor precio de entrada/salida y mayor coste real de la operacion."
    ),
    GREEN(
        title = "Senal VERDE",
        message = "Verde indica ventaja ejecutable alta y fill suficiente. Suele ser la zona prioritaria para evaluar entrada, siempre con control de riesgo."
    ),
    YELLOW(
        title = "Senal AMARILLA",
        message = "Amarillo indica ventaja ejecutable moderada. Puede interesar en posicion mas pequena o esperando mejor precio."
    ),
    RED(
        title = "Senal BAJA",
        message = "Bajo indica ventaja ejecutable debil o mala ejecucion probable. Normalmente es zona de no entrar."
    ),
    LIQUIDITY(
        title = "Liquidez",
        message = "Liq muestra cuanto dinero hay en ese mercado. Con liquidez baja, el precio se mueve facil y cuesta entrar o salir sin perder valor."
    ),
    TAF(
        title = "TAF",
        message = "TAF es el pronostico aeronautico oficial del aeropuerto. Sirve para anticipar cambios de nubes, visibilidad, viento y fenomenos que pueden frenar o impulsar la maxima diaria."
    )
}
