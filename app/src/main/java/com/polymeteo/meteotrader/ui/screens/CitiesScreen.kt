package com.polymeteo.meteotrader.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.CityCatalog
import com.polymeteo.meteotrader.data.forecast.ForecastWeights
import com.polymeteo.meteotrader.data.model.CityConfig
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.PolyTempPremiumReport
import com.polymeteo.meteotrader.data.model.SourceStatus
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.data.model.TraderOpportunity
import com.polymeteo.meteotrader.data.model.TraderSignalLevel
import com.polymeteo.meteotrader.ui.AppMode
import com.polymeteo.meteotrader.ui.MeteoUiState
import com.polymeteo.meteotrader.ui.StrategyMode
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Neutral
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Positive
import com.polymeteo.meteotrader.util.controlTempInUnit
import com.polymeteo.meteotrader.util.directionLabel
import com.polymeteo.meteotrader.util.formatInZone
import com.polymeteo.meteotrader.util.formatPercent
import com.polymeteo.meteotrader.util.formatTemperature
import com.polymeteo.meteotrader.util.metarCurrentInUnit
import com.polymeteo.meteotrader.util.metarDeltaInUnit
import com.polymeteo.meteotrader.util.polyTempInUnit
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitiesScreen(
    state: MeteoUiState,
    onRefresh: () -> Unit,
    onBacktestSelected: () -> Unit,
    onSettingsSelected: () -> Unit,
    onPolymarketAccountSelected: () -> Unit,
    onCitySelected: (String) -> Unit
) {
    var flashToken by rememberSaveable { mutableStateOf("") }
    var flashUntilMillis by rememberSaveable { mutableStateOf(0L) }
    var flashActive by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBase)
    ) {
        val topEdges = state.cities
            .mapNotNull { city -> city.polymarket.topOpportunity?.let { city to it } }
            .sortedByDescending { (_, opp) -> opp.executableEdge }
        val flashCandidate = topEdges.firstOrNull { (_, opp) -> isFlashOpportunity(opp) }

        LaunchedEffect(
            flashCandidate?.second?.marketId,
            flashCandidate?.second?.executableEdge,
            state.lastUpdatedAt
        ) {
            val candidate = flashCandidate ?: return@LaunchedEffect
            val newToken = buildString {
                append(candidate.second.marketId)
                append(":")
                append(state.lastUpdatedAt?.epochSecond ?: 0L)
            }
            if (newToken != flashToken) {
                flashToken = newToken
                flashUntilMillis = System.currentTimeMillis() + FLASH_DURATION_MILLIS
            }
        }

        LaunchedEffect(flashUntilMillis) {
            if (flashUntilMillis <= 0L) {
                flashActive = false
                return@LaunchedEffect
            }
            flashActive = true
            val remaining = flashUntilMillis - System.currentTimeMillis()
            if (remaining > 0L) delay(remaining)
            if (System.currentTimeMillis() >= flashUntilMillis) {
                flashActive = false
            }
        }

        HeaderBar(
            updatedText = state.lastUpdatedAt?.formatInZone("UTC", "HH:mm:ss 'UTC'") ?: "sin actualización",
            onRefresh = onRefresh,
            onBacktestSelected = onBacktestSelected,
            onSettingsSelected = onSettingsSelected,
            onPolymarketAccountSelected = onPolymarketAccountSelected,
            appMode = state.appMode,
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

        if (state.appMode == AppMode.EXPERT) {
            TopEdgesTicker(
                cities = state.cities,
                edges = topEdges,
                flashOpportunity = flashCandidate,
                flashActive = flashActive
            )
        } else {
            RookieHeader(strategyMode = state.strategyMode)
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if (state.appMode == AppMode.EXPERT) {
                CitiesGrid(
                    cityConfigs = CityCatalog.cities,
                    cities = state.cities,
                    cityLoadingIds = state.cityLoadingIds,
                    onCitySelected = onCitySelected
                )
            } else {
                if (state.cities.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    RookieOpportunitiesList(
                        cities = state.cities,
                        strategyMode = state.strategyMode,
                        premiumReportsByCity = state.premiumReportsByCity,
                        premiumRefreshingCityIds = state.premiumRefreshingCityIds
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
    onSettingsSelected: () -> Unit,
    onPolymarketAccountSelected: () -> Unit,
    appMode: AppMode,
    isBacktestRefreshing: Boolean
) {
    var titleTapCount by remember { mutableStateOf(0) }
    var lastTitleTapElapsedMs by remember { mutableStateOf(0L) }

    fun registerTitleTap() {
        val now = SystemClock.elapsedRealtime()
        titleTapCount = if (now - lastTitleTapElapsedMs <= TITLE_MULTI_TAP_WINDOW_MS) {
            titleTapCount + 1
        } else {
            1
        }
        lastTitleTapElapsedMs = now
        if (titleTapCount >= TITLE_SECRET_TAP_COUNT) {
            titleTapCount = 0
            onPolymarketAccountSelected()
        }
    }

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
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.clickable(onClick = ::registerTitleTap)
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
            if (appMode == AppMode.EXPERT) {
                FilledIconButton(onClick = onBacktestSelected) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.QueryStats,
                        contentDescription = "Backtesting"
                    )
                }
            }
            FilledIconButton(onClick = onRefresh) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refrescar"
                )
            }
            FilledIconButton(onClick = onSettingsSelected) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Ajustes"
                )
            }
        }
    }

    if (appMode == AppMode.EXPERT && isBacktestRefreshing) {
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
private fun RookieHeader(
    strategyMode: StrategyMode
) {
    val strategyName = when (strategyMode) {
        StrategyMode.CONSERVADORA -> "Conservadora"
        StrategyMode.AGRESIVA -> "Agresiva"
    }
    Text(
        text = "Modo Rookie • Estrategia $strategyName",
        style = MaterialTheme.typography.bodyMedium,
        color = MutedInk,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun CitiesGrid(
    cityConfigs: List<CityConfig>,
    cities: List<CityWeatherData>,
    cityLoadingIds: Set<String>,
    onCitySelected: (String) -> Unit
) {
    val citiesById = remember(cities) { cities.associateBy { it.city.id } }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
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
            items(cityConfigs, key = { it.id }) { cityConfig ->
                val cityData = citiesById[cityConfig.id]
                val isLoading = cityLoadingIds.contains(cityConfig.id)
                val canOpenDetail = cityData != null && !isLoading && !cityData.isClosedBySchedule
                CityCard(
                    city = cityConfig,
                    data = cityData,
                    isLoading = isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cardHeight)
                        .clickable(enabled = canOpenDetail) { onCitySelected(cityConfig.id) }
                )
            }
        }
    }
}

@Composable
private fun RookieOpportunitiesList(
    cities: List<CityWeatherData>,
    strategyMode: StrategyMode,
    premiumReportsByCity: Map<String, PolyTempPremiumReport>,
    premiumRefreshingCityIds: Set<String>
) {
    val uriHandler = LocalUriHandler.current
    val opportunities = remember(cities, strategyMode, premiumReportsByCity) {
        buildRookieOpportunities(
            cities = cities,
            strategyMode = strategyMode,
            premiumReportsByCity = premiumReportsByCity
        )
    }

    if (opportunities.isEmpty()) {
        val isAnyPremiumLoading = cities.any { city ->
            city.city.id in premiumRefreshingCityIds
        }
        val emptyMessage = if (isAnyPremiumLoading) {
            "Estamos terminando de ajustar la MMA con histórico. En cuanto acabe, verás solo entradas claras."
        } else {
            "Ahora mismo no veo una entrada clara con calidad suficiente. Mejor esperar que forzar una apuesta."
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = MutedInk,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        lazyItems(
            items = opportunities,
            key = { "${it.cityId}-${it.opportunity.marketId}" }
        ) { candidate ->
            RookieOpportunityCard(
                candidate = candidate,
                onOpenMarket = { url ->
                    runCatching { uriHandler.openUri(url) }
                }
            )
        }
    }
}

@Composable
private fun RookieOpportunityCard(
    candidate: RookieOpportunityCandidate,
    onOpenMarket: (String) -> Unit
) {
    val signalColor = when (candidate.opportunity.signal) {
        TraderSignalLevel.GREEN -> Positive
        TraderSignalLevel.YELLOW -> Color(0xFFFFC857)
        TraderSignalLevel.RED -> Neutral
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, signalColor.copy(alpha = 0.55f))
            .clickable { onOpenMarket(candidate.marketUrl) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = candidate.actionTitle,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = signalColor
        )
        Text(
            text = "${candidate.cityName} • ${candidate.dayLabel}",
            style = MaterialTheme.typography.labelMedium,
            color = MutedInk
        )
        Text(
            text = candidate.opportunity.question,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Acción sugerida: comprar ${if (candidate.opportunity.recommendedBuy == "YES") "SÍ" else "NO"}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = candidate.mmaSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MutedInk
        )
        Text(
            text = candidate.dominantModelSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MutedInk
        )
        Text(
            text = candidate.whyItIsInteresting,
            style = MaterialTheme.typography.bodyMedium,
            color = MutedInk
        )
        Text(
            text = candidate.profitSummary,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Abrir este mercado en Polymarket",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = Positive
        )
    }
}

private data class RookieOpportunityCandidate(
    val cityId: String,
    val cityName: String,
    val dayLabel: String,
    val marketUrl: String,
    val actionTitle: String,
    val mmaSummary: String,
    val dominantModelSummary: String,
    val whyItIsInteresting: String,
    val profitSummary: String,
    val opportunity: TraderOpportunity
)

private fun buildRookieOpportunities(
    cities: List<CityWeatherData>,
    strategyMode: StrategyMode,
    premiumReportsByCity: Map<String, PolyTempPremiumReport>
): List<RookieOpportunityCandidate> {
    return cities
        .asSequence()
        .filter { city -> !city.isClosedBySchedule }
        .flatMap { city ->
            val report = premiumReportsByCity[city.city.id]
            val dominantModel = report
                ?.providerRanking
                ?.filterNot { stat ->
                    ForecastWeights.isDeprecatedProvider(stat.providerId, stat.providerName)
                }
                ?.maxByOrNull { stat -> stat.dynamicWeight }

            val dominantShare = report
                ?.providerRanking
                ?.filterNot { stat ->
                    ForecastWeights.isDeprecatedProvider(stat.providerId, stat.providerName)
                }
                ?.sumOf { stat -> stat.dynamicWeight }
                ?.takeIf { sum -> sum > 0.0 }
                ?.let { total ->
                    dominantModel?.dynamicWeight?.div(total)
                }

            city.polymarket.opportunities.mapNotNull { opportunity ->
                if (!passesRookieStrategy(opportunity, strategyMode)) return@mapNotNull null
                val zoneId = ZoneId.of(city.city.zoneId)
                val cityToday = LocalDate.now(zoneId)
                val targetDate = opportunity.condition.targetDate ?: cityToday
                val horizon = city.horizons.firstOrNull { it.targetDate == targetDate } ?: return@mapNotNull null
                if (!horizon.polyTempPremiumReady) return@mapNotNull null
                if (targetDate == cityToday && horizon.polyTempPremiumInvalid) return@mapNotNull null
                if (dominantModel == null || dominantShare == null) return@mapNotNull null
                val mma = if (city.city.displayUnit == TempUnit.C) {
                    horizon.polyTempPremiumC
                } else {
                    horizon.polyTempPremiumF
                } ?: return@mapNotNull null
                val mm = if (city.city.displayUnit == TempUnit.C) {
                    horizon.polyTempC
                } else {
                    horizon.polyTempF
                }
                val selectedPrice = if (opportunity.recommendedBuy == "YES") {
                    opportunity.yesPrice
                } else {
                    opportunity.noPrice
                }
                val profitIfWinPer100 = estimateProfitIfWinPer100(selectedPrice)
                val expectedProfitPer100 = opportunity.executableEdge * ROOKIE_STAKE_REFERENCE
                RookieOpportunityCandidate(
                    cityId = city.city.id,
                    cityName = city.city.name,
                    dayLabel = rookieDayLabel(city, targetDate),
                    marketUrl = buildPolymarketEventUrl(city, opportunity.condition.targetDate),
                    actionTitle = rookieActionTitle(opportunity),
                    mmaSummary = rookieMmaSummary(
                        mma = mma,
                        mm = mm,
                        unit = city.city.displayUnit
                    ),
                    dominantModelSummary = rookieDominantModelSummary(
                        modelName = dominantModel.providerName,
                        dominantShare = dominantShare
                    ),
                    whyItIsInteresting = rookieReasonSummary(opportunity),
                    profitSummary = rookieProfitSummary(
                        expectedProfitPer100 = expectedProfitPer100,
                        profitIfWinPer100 = profitIfWinPer100
                    ),
                    opportunity = opportunity
                )
            }
        }
        .toList()
        .sortedByDescending { it.opportunity.executableEdge }
        .take(24)
}

private fun passesRookieStrategy(
    opportunity: TraderOpportunity,
    strategyMode: StrategyMode
): Boolean {
    val liquidity = opportunity.liquidity ?: 0.0
    val spread = opportunity.spread ?: 0.02
    return when (strategyMode) {
        StrategyMode.CONSERVADORA -> {
            opportunity.shouldTrade &&
                opportunity.signal == TraderSignalLevel.GREEN &&
                opportunity.executableEdge >= 0.07 &&
                opportunity.fillProbability >= 0.72 &&
                liquidity >= 1_200.0 &&
                spread <= 0.08 &&
                opportunity.totalCost <= 0.17
        }

        StrategyMode.AGRESIVA -> {
            opportunity.shouldTrade &&
                opportunity.signal != TraderSignalLevel.RED &&
                opportunity.executableEdge >= 0.03 &&
                opportunity.fillProbability >= 0.55 &&
                liquidity >= 300.0 &&
                opportunity.totalCost <= 0.21
        }
    }
}

private fun rookieActionTitle(opportunity: TraderOpportunity): String {
    if (!opportunity.shouldTrade) return "Mejor no entrar"
    return when {
        opportunity.signal == TraderSignalLevel.GREEN && opportunity.executableEdge >= 0.12 -> "Oportunidad muy clara"
        opportunity.signal == TraderSignalLevel.GREEN -> "Buena oportunidad"
        opportunity.signal == TraderSignalLevel.YELLOW -> "Entrada posible con cuidado"
        else -> "Mejor no entrar"
    }
}

private fun rookieMmaSummary(
    mma: Double,
    mm: Double?,
    unit: TempUnit
): String {
    val mmaLabel = formatTemperature(mma, unit, digits = 1)
    val mmLabel = formatTemperature(mm, unit, digits = 1)
    return "MMA ${mmaLabel} (ajustada con histórico). MM ${mmLabel}."
}

private fun rookieDominantModelSummary(
    modelName: String,
    dominantShare: Double
): String {
    return "Modelo histórico más fiable: $modelName (${formatPercent(dominantShare)} del peso total)."
}

private fun rookieReasonSummary(opportunity: TraderOpportunity): String {
    val fillText = formatPercent(opportunity.fillProbability)
    val costText = formatPercent(opportunity.totalCost)
    val edgeText = formatPercent(opportunity.executableEdge)
    val liquidityText = (opportunity.liquidity ?: 0.0).roundToInt()
    val volumeBucketText = opportunity.volumeBucket?.roundToInt()?.toString() ?: "--"
    val spreadText = formatPercent(opportunity.spread ?: 0.0)
    return "Por qué interesa: ventaja real ${edgeText}, ejecución probable ${fillText}, liquidez libro ${liquidityText}, volumen bucket ${volumeBucketText} y coste ${costText} (spread ${spreadText})."
}

private fun rookieProfitSummary(
    expectedProfitPer100: Double,
    profitIfWinPer100: Double?
): String {
    val expected = formatUsd(expectedProfitPer100)
    val winIfRight = if (profitIfWinPer100 == null) {
        "--"
    } else {
        formatUsd(profitIfWinPer100)
    }
    return "Con 100 USDC: beneficio esperado $expected. Si aciertas, beneficio bruto aprox. $winIfRight."
}

private fun estimateProfitIfWinPer100(price: Double): Double? {
    if (price <= 0.0 || price >= 1.0) return null
    val shares = ROOKIE_STAKE_REFERENCE / price
    val grossPayout = shares
    val grossProfit = grossPayout - ROOKIE_STAKE_REFERENCE
    return max(grossProfit, 0.0)
}

private fun formatUsd(value: Double): String {
    return "$" + String.format(Locale.US, "%.2f", value)
}

private fun rookieDayLabel(
    cityData: CityWeatherData,
    targetDate: LocalDate?
): String {
    val zoneId = ZoneId.of(cityData.city.zoneId)
    val today = LocalDate.now(zoneId)
    val date = targetDate ?: today
    return when (date) {
        today -> "Hoy"
        today.plusDays(1) -> "Mañana"
        today.plusDays(2) -> "Pasado mañana"
        else -> date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.US))
    }
}

private fun buildPolymarketEventUrl(
    cityData: CityWeatherData,
    targetDate: LocalDate?
): String {
    val zoneId = ZoneId.of(cityData.city.zoneId)
    val date = targetDate ?: LocalDate.now(zoneId)
    val citySlug = when (cityData.city.id) {
        "new-york" -> "nyc"
        else -> cityData.city.id
    }
    val monthSlug = date
        .format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
        .lowercase(Locale.US)
    return "https://polymarket.com/es/event/highest-temperature-in-$citySlug-on-$monthSlug-${date.dayOfMonth}-${date.year}"
}

@Composable
private fun CityCard(
    city: CityConfig,
    data: CityWeatherData?,
    isLoading: Boolean,
    modifier: Modifier = Modifier
 ) {
    val isDataReady = data != null
    val isScheduleClosed = data?.isClosedBySchedule == true
    val current = data?.metarCurrentInUnit()
    val delta = data?.metarDeltaInUnit()
    val control = data?.controlTempInUnit()
    val poly = data?.polyTempInUnit()
    val cardBackground = if (isScheduleClosed) Color(0xFF1A2431) else DarkPanel
    val cardBorder = if (isScheduleClosed) Color(0x338EA2B7) else Color(0x33FFFFFF)
    val primaryTextColor = if (isScheduleClosed) MutedInk else MaterialTheme.colorScheme.onSurface
    val polyColor = when {
        !isDataReady -> MutedInk
        isScheduleClosed -> MutedInk
        data?.polyTempInvalid == true -> Negative
        else -> MaterialTheme.colorScheme.onSurface
    }
    val topTrader = data?.polymarket?.topOpportunity
    val horizonAvailabilityTag = data?.let { cityOpportunityHorizonTag(it) }

    val deltaColor = when {
        delta == null -> Neutral
        delta > 0 -> Positive
        delta < 0 -> Negative
        else -> Neutral
    }

    Column(
        modifier = modifier
            .background(cardBackground)
            .border(width = 1.dp, color = cardBorder)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildAnnotatedString {
                    append(city.name)
                    if (!horizonAvailabilityTag.isNullOrBlank()) {
                        append(" ")
                        withStyle(
                            SpanStyle(
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            append(horizonAvailabilityTag)
                        }
                    }
                },
                style = MaterialTheme.typography.titleMedium,
                color = primaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = data?.localTime ?: "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            InlineMetric(
                label = "M",
                value = formatTemperature(current, city.displayUnit, digits = 0),
                modifier = Modifier.weight(1f)
            )
            InlineMetric(
                label = "Δ",
                value = formatCompactDelta(delta, city.displayUnit),
                valueColor = if (isScheduleClosed) MutedInk else deltaColor,
                modifier = Modifier.weight(1f)
            )
            InlineMetric(
                label = "S",
                value = formatTemperature(control, city.displayUnit, digits = 0),
                modifier = Modifier.weight(1f),
                valueColor = if (isScheduleClosed) MutedInk else MaterialTheme.colorScheme.onSurface
            )
            InlineMetric(
                label = "P",
                value = formatTemperature(poly, city.displayUnit, digits = 0),
                valueColor = polyColor,
                modifier = Modifier.weight(1f)
            )
        }

        if (!isDataReady && isLoading) {
            CityStatusRow(
                text = "Cargando datos...",
                color = MutedInk,
                showSpinner = true
            )
        } else if (!isDataReady) {
            CityStatusRow(
                text = "Sin datos",
                color = Neutral
            )
        } else if (isLoading) {
            CityStatusRow(
                text = "Actualizando...",
                color = MutedInk,
                showSpinner = true
            )
        } else if (isScheduleClosed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x2C7C8EA0))
                    .border(1.dp, Color(0x66A3B4C4))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CERRADO POR HORARIO",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MutedInk
                )
            }
        } else if (topTrader != null) {
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
                    text = "${if (topTrader.shouldTrade) "BET" else "PASS"} ${topTrader.recommendedBuy} ${formatPercent(topTrader.executableEdge)}",
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
private fun CityStatusRow(
    text: String,
    color: Color,
    showSpinner: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(10.dp),
                strokeWidth = 1.5.dp,
                color = color
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1
        )
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
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
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
            softWrap = false,
            overflow = TextOverflow.Clip
        )
    }
}

private fun formatCompactDelta(value: Double?, unit: TempUnit): String {
    if (value == null) return "--"
    val rounded = value.roundToInt()
    val sign = when {
        rounded > 0 -> "+"
        rounded < 0 -> "-"
        else -> "±"
    }
    val absValue = if (rounded == 0) 0 else abs(rounded)
    return "$sign$absValue°${unit.symbol}"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopEdgesTicker(
    cities: List<CityWeatherData>,
    edges: List<Pair<CityWeatherData, TraderOpportunity>>,
    flashOpportunity: Pair<CityWeatherData, TraderOpportunity>?,
    flashActive: Boolean
) {
    val flashTransition = rememberInfiniteTransition(label = "flashTicker")
    val flashAlpha by flashTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 420),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flashAlpha"
    )

    val tickerText = remember(cities, edges, flashOpportunity, flashActive) {
        val messages = mutableListOf<String>()

        if (flashActive && flashOpportunity != null) {
            val (cityData, opp) = flashOpportunity
            messages += "FLASH ${tickerCityLabel(cityData, opp)} ${directionLabel(opp.direction)} ${opp.recommendedBuy} ${formatPercent(opp.executableEdge)}"
        }

        if (edges.isEmpty()) {
            messages += "Sin oportunidades ejecutables por ahora"
        } else {
            val executableCount = edges.count { (_, opp) -> opp.shouldTrade }
            messages += "Ejecutables $executableCount/${edges.size}"

            edges.take(4).forEachIndexed { index, (cityData, opp) ->
                messages += "${index + 1} ${tickerCityLabel(cityData, opp)} ${directionLabel(opp.direction)} ${if (opp.shouldTrade) "BET" else "PASS"} ${opp.recommendedBuy} ${formatPercent(opp.executableEdge)}"
            }
        }

        val warningCities = cities
            .filter { city ->
                city.warnings.any { warning ->
                    warning.contains("alta dispersion", ignoreCase = true) ||
                        warning.contains("alta dispersión", ignoreCase = true)
                }
            }
            .map { it.city.name }

        if (warningCities.isNotEmpty()) {
            messages += "Dispersion alta: ${warningCities.joinToString("/")}"
        }

        val errorSources = cities.sumOf { city ->
            city.forecasts.count { source -> source.status == SourceStatus.ERROR }
        }
        if (errorSources > 0) {
            messages += "Fuentes con error: $errorSources"
        }

        messages.joinToString("   •   ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Color(0x101C2EFF))
            .border(1.dp, Color(0x2F77C4FF))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (flashActive && flashOpportunity != null) {
            Text(
                text = "FLASH",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                color = Color(0xFFFF4D4F),
                maxLines = 1,
                modifier = Modifier.alpha(flashAlpha)
            )
        }

        Text(
            text = tickerText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    animationMode = MarqueeAnimationMode.Immediately,
                    repeatDelayMillis = 0,
                    spacing = MarqueeSpacing(36.dp)
                )
        )
    }
}

private fun isFlashOpportunity(opportunity: TraderOpportunity): Boolean {
    val liquidity = opportunity.liquidity ?: 0.0
    return opportunity.shouldTrade &&
        opportunity.signal == TraderSignalLevel.GREEN &&
        opportunity.executableEdge >= FLASH_MIN_EXECUTABLE_EDGE &&
        liquidity >= FLASH_MIN_LIQUIDITY
}

private fun tickerCityLabel(
    cityData: CityWeatherData,
    opportunity: TraderOpportunity
): String {
    val horizon = opportunityHorizonLabel(cityData, opportunity) ?: return cityData.city.name
    return "${cityData.city.name} ($horizon)"
}

private fun cityOpportunityHorizonTag(cityData: CityWeatherData): String? {
    if (cityData.polymarket.opportunities.isEmpty()) return null
    val cityToday = LocalDate.now(ZoneId.of(cityData.city.zoneId))
    var hasToday = false
    var hasTomorrow = false
    var hasPast = false
    cityData.polymarket.opportunities.forEach { opportunity ->
        val targetDate = opportunity.condition.targetDate ?: cityToday
        when (targetDate) {
            cityToday -> hasToday = true
            cityToday.plusDays(1) -> hasTomorrow = true
            cityToday.plusDays(2) -> hasPast = true
        }
    }
    val label = buildString {
        if (hasToday) append("H")
        if (hasTomorrow) append("M")
        if (hasPast) append("P")
    }
    return when {
        label.isBlank() -> null
        label == "H" -> null
        else -> "[$label]"
    }
}

private fun opportunityHorizonLabel(
    cityData: CityWeatherData,
    opportunity: TraderOpportunity
): String? {
    val cityToday = LocalDate.now(ZoneId.of(cityData.city.zoneId))
    val targetDate = opportunity.condition.targetDate ?: cityToday
    return when (targetDate) {
        cityToday -> null
        cityToday.plusDays(1) -> "Mañana"
        cityToday.plusDays(2) -> "Pasado"
        else -> targetDate.format(DateTimeFormatter.ofPattern("dd/MM", Locale.US))
    }
}

private const val FLASH_DURATION_MILLIS = 60_000L
private const val FLASH_MIN_EXECUTABLE_EDGE = 0.20
private const val FLASH_MIN_LIQUIDITY = 1_200.0
private const val ROOKIE_STAKE_REFERENCE = 100.0
private const val TITLE_SECRET_TAP_COUNT = 3
private const val TITLE_MULTI_TAP_WINDOW_MS = 1_200L
