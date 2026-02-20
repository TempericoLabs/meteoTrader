package com.polymeteo.meteotrader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.forecast.ForecastWeights
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.PolyTempPremiumModelStat
import com.polymeteo.meteotrader.data.model.PolyTempPremiumProviderDay
import com.polymeteo.meteotrader.data.model.PolyTempPremiumReport
import com.polymeteo.meteotrader.data.model.PolyTempPremiumVerificationDay
import com.polymeteo.meteotrader.data.model.SourceStatus
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Positive
import com.polymeteo.meteotrader.util.convertTemperature
import com.polymeteo.meteotrader.util.formatInZone
import com.polymeteo.meteotrader.util.formatPercent
import com.polymeteo.meteotrader.util.formatTemperature
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolyTempPremiumScreen(
    cityData: CityWeatherData?,
    report: PolyTempPremiumReport?,
    isRefreshing: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val cityName = cityData?.city?.name ?: report?.cityName ?: "PolyTemp PREMIUM"
    val displayUnit = cityData?.city?.displayUnit ?: TempUnit.C
    val cityZoneId = cityData?.city?.zoneId ?: "UTC"
    val uriHandler = LocalUriHandler.current
    var helpTopic by remember { mutableStateOf<PremiumHelpTopic?>(null) }
    val visibleRanking = remember(report) {
        report?.providerRanking
            ?.filterNot { stat -> ForecastWeights.isDeprecatedProvider(stat.providerId, stat.providerName) }
            .orEmpty()
    }
    val visibleVerifications = remember(report) {
        report?.dayVerifications
            ?.mapNotNull { day ->
                val providers = day.providers.filterNot { provider ->
                    ForecastWeights.isDeprecatedProvider(provider.providerId, provider.providerName)
                }
                if (providers.isEmpty()) {
                    null
                } else {
                    day.copy(providers = providers)
                }
            }
            .orEmpty()
    }

    LaunchedEffect(report?.cityId, isRefreshing) {
        if (report == null && !isRefreshing) {
            onRefresh()
        }
    }

    Scaffold(
        containerColor = DarkBase,
        topBar = {
            TopAppBar(
                title = { Text("PolyTemp PREMIUM • $cityName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar PolyTemp PREMIUM")
                    }
                }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    SummaryCard(
                        report = report,
                        cityZoneId = cityZoneId,
                        onHelpRequested = { helpTopic = it }
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    item {
                        MessageCard(
                            title = "Error",
                            message = errorMessage,
                            color = Negative
                        )
                    }
                }

                if (!report?.warnings.isNullOrEmpty()) {
                    item {
                        MessageCard(
                            title = "Advertencias",
                            message = report?.warnings?.joinToString("\n") { "• $it" },
                            color = Color(0xFFFFC857)
                        )
                    }
                }

                item {
                    SectionTitle(
                        title = "Ranking vivo de modelos",
                        topic = PremiumHelpTopic.RANKING,
                        onHelpRequested = { helpTopic = it }
                    )
                }

                if (report == null) {
                    item { EmptyCard("Cargando ranking de modelos...") }
                } else if (visibleRanking.isEmpty()) {
                    item { EmptyCard("Aun no hay verificacion suficiente para crear ranking.") }
                } else {
                    items(visibleRanking, key = { it.providerId }) { stat ->
                        ModelRankingRow(
                            stat = stat,
                            displayUnit = displayUnit,
                            onHelpRequested = { helpTopic = it }
                        )
                    }
                }

                item {
                    SectionTitle(
                        title = "Verificacion diaria (hasta ayer)",
                        topic = PremiumHelpTopic.VERIFICATION,
                        onHelpRequested = { helpTopic = it }
                    )
                }

                if (report == null) {
                    item { EmptyCard("Cargando historico de verificacion...") }
                } else if (visibleVerifications.isEmpty()) {
                    item { EmptyCard("Sin dias verificados todavia.") }
                } else {
                    items(
                        visibleVerifications,
                        key = { "${it.targetDate}-${it.verifiedAt.toEpochMilli()}" }
                    ) { day ->
                        VerificationDayCard(
                            day = day,
                            displayUnit = displayUnit,
                            onHelpRequested = { helpTopic = it },
                            onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } }
                        )
                    }
                }

                item {
                    Text(
                        text = "Actualizado: ${report?.generatedAt?.formatInZone(cityZoneId, "dd-MM-yyyy HH:mm") ?: "--"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedInk
                    )
                }
            }

            helpTopic?.let { topic ->
                PremiumHelpDialog(
                    topic = topic,
                    onDismiss = { helpTopic = null }
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    report: PolyTempPremiumReport?,
    cityZoneId: String,
    onHelpRequested: (PremiumHelpTopic) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x33FFFFFF))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Motor de aprendizaje continuo",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF8FC8FF),
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onHelpRequested(PremiumHelpTopic.PREMIUM_ENGINE) }
        )

        if (report == null) {
            Text(
                text = "Inicializando datos PREMIUM...",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk
            )
            return
        }

        MetricLine(
            label = "Generado",
            value = report.generatedAt.formatInZone(cityZoneId, "dd-MM-yyyy HH:mm"),
            topic = PremiumHelpTopic.GENERATED,
            onHelpRequested = onHelpRequested
        )
        MetricLine(
            label = "Ultimo dia verificado",
            value = report.lastVerifiedDate?.format(DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.US)) ?: "--",
            topic = PremiumHelpTopic.VERIFIED_DAYS,
            onHelpRequested = onHelpRequested
        )
        MetricLine(
            label = "Dias verificados",
            value = report.verifiedDays.toString(),
            topic = PremiumHelpTopic.VERIFIED_DAYS,
            onHelpRequested = onHelpRequested
        )
        MetricLine(
            label = "Dias pendientes",
            value = report.pendingDays.toString(),
            topic = PremiumHelpTopic.PENDING_DAYS,
            onHelpRequested = onHelpRequested
        )

        PremiumHelpTerms(onHelpRequested = onHelpRequested)
    }
}

@Composable
private fun ModelRankingRow(
    stat: PolyTempPremiumModelStat,
    displayUnit: TempUnit,
    onHelpRequested: (PremiumHelpTopic) -> Unit
) {
    val trend = weightTrend(stat.dynamicWeight, stat.previousDynamicWeight)
    val weightColor = when (trend) {
        WeightTrend.UP -> Positive
        WeightTrend.FLAT -> Color(0xFFFFC857)
        WeightTrend.DOWN -> Negative
    }
    val trendLabel = when (trend) {
        WeightTrend.UP -> "Mejora vs ayer"
        WeightTrend.FLAT -> "Igual vs ayer"
        WeightTrend.DOWN -> "Baja vs ayer"
    }
    val deltaLabel = when (val delta = stat.previousDynamicWeight?.let { stat.dynamicWeight - it }) {
        null -> "Δ --"
        else -> "Δ ${formatSignedWeight(delta)}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, weightColor.copy(alpha = 0.65f))
            .padding(10.dp)
    ) {
        Text(
            text = stat.providerName,
            style = MaterialTheme.typography.titleSmall,
            color = weightColor
        )
        Text(
            text = "$trendLabel • $deltaLabel",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = weightColor
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniMetric(
                label = "Peso",
                value = formatWeight(stat.dynamicWeight),
                valueColor = weightColor,
                topic = PremiumHelpTopic.DYNAMIC_WEIGHT,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniMetric(
                label = "Base",
                value = formatWeight(stat.baseWeight),
                topic = PremiumHelpTopic.BASE_WEIGHT,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniMetric(
                label = "x",
                value = String.format(Locale.US, "%.2f", stat.multiplier),
                valueColor = weightColor,
                topic = PremiumHelpTopic.MULTIPLIER,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniMetric(
                label = "MAE",
                value = formatTempFromC(stat.meanAbsoluteErrorC, displayUnit),
                topic = PremiumHelpTopic.MAE,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniMetric(
                label = "RMSE",
                value = formatTempFromC(stat.rmseC, displayUnit),
                topic = PremiumHelpTopic.RMSE,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniMetric(
                label = "Hit<=1C",
                value = formatPercent(stat.withinOneDegreeRate),
                topic = PremiumHelpTopic.HIT_1C,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniMetric(
                label = "Cobertura",
                value = formatPercent(stat.coverage),
                topic = PremiumHelpTopic.COVERAGE,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniMetric(
                label = "Validos",
                value = "${stat.validForecastDays}/${stat.verifiedDays}",
                topic = PremiumHelpTopic.COVERAGE,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniMetric(
                label = "Score",
                value = stat.score?.let { String.format(Locale.US, "%.3f", it) } ?: "--",
                topic = PremiumHelpTopic.SCORE,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun VerificationDayCard(
    day: PolyTempPremiumVerificationDay,
    displayUnit: TempUnit,
    onHelpRequested: (PremiumHelpTopic) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(10.dp)
    ) {
        Text(
            text = day.targetDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.US)),
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF8FC8FF)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Observado ${formatTempFromC(day.observedMaxC, displayUnit)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onHelpRequested(PremiumHelpTopic.OBSERVED_MAX) }
            )
            Text(
                text = day.verifiedAt.formatInZone("UTC", "dd-MM HH:mm 'UTC'"),
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }

        if (!day.observedSourceUrl.isNullOrBlank()) {
            Text(
                text = "Fuente observada",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8FC8FF),
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { onOpenUrl(day.observedSourceUrl) }
            )
        }

        day.providers.forEach { provider ->
            ProviderVerificationLine(
                provider = provider,
                displayUnit = displayUnit,
                onHelpRequested = onHelpRequested
            )
        }
    }
}

@Composable
private fun ProviderVerificationLine(
    provider: PolyTempPremiumProviderDay,
    displayUnit: TempUnit,
    onHelpRequested: (PremiumHelpTopic) -> Unit
) {
    val statusColor = when (provider.status) {
        SourceStatus.SUCCESS -> Positive
        SourceStatus.ERROR -> Negative
        SourceStatus.SKIPPED -> MutedInk
    }
    val errorColor = when {
        provider.absoluteErrorC == null -> MutedInk
        provider.absoluteErrorC <= 1.0 -> Positive
        provider.absoluteErrorC <= 2.5 -> Color(0xFFFFC857)
        else -> Negative
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(Color(0x141E30FF))
            .border(1.dp, Color(0x1FFFFFFF))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = provider.providerName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Pred ${formatTempFromC(provider.forecastMaxC, displayUnit)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onHelpRequested(PremiumHelpTopic.FORECAST_VALUE) }
            )
            Text(
                text = "Err ${formatTempFromC(provider.absoluteErrorC, displayUnit)}",
                style = MaterialTheme.typography.labelSmall,
                color = errorColor,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onHelpRequested(PremiumHelpTopic.ERROR_VALUE) }
            )
            Text(
                text = provider.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onHelpRequested(PremiumHelpTopic.STATUS) }
            )
        }
        if (!provider.errorMessage.isNullOrBlank()) {
            Text(
                text = provider.errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = Negative,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun PremiumHelpTerms(onHelpRequested: (PremiumHelpTopic) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("Peso", PremiumHelpTopic.DYNAMIC_WEIGHT, onHelpRequested, Modifier.weight(1f))
            HelpPill("Base", PremiumHelpTopic.BASE_WEIGHT, onHelpRequested, Modifier.weight(1f))
            HelpPill("MAE", PremiumHelpTopic.MAE, onHelpRequested, Modifier.weight(1f))
            HelpPill("RMSE", PremiumHelpTopic.RMSE, onHelpRequested, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("Cobert", PremiumHelpTopic.COVERAGE, onHelpRequested, Modifier.weight(1f))
            HelpPill("Hit1C", PremiumHelpTopic.HIT_1C, onHelpRequested, Modifier.weight(1f))
            HelpPill("Score", PremiumHelpTopic.SCORE, onHelpRequested, Modifier.weight(1f))
            HelpPill("Pend", PremiumHelpTopic.PENDING_DAYS, onHelpRequested, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HelpPill(
    text: String,
    topic: PremiumHelpTopic,
    onHelpRequested: (PremiumHelpTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF8FC8FF),
        textAlign = TextAlign.Center,
        textDecoration = TextDecoration.Underline,
        modifier = modifier
            .background(Color(0x1A8FC8FF))
            .border(1.dp, Color(0x558FC8FF))
            .clickable { onHelpRequested(topic) }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun MiniMetric(
    label: String,
    value: String,
    topic: PremiumHelpTopic,
    onHelpRequested: (PremiumHelpTopic) -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8FC8FF),
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onHelpRequested(topic) }
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor
        )
    }
}

@Composable
private fun MetricLine(
    label: String,
    value: String,
    topic: PremiumHelpTopic,
    onHelpRequested: (PremiumHelpTopic) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onHelpRequested(topic) }
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SectionTitle(
    title: String,
    topic: PremiumHelpTopic,
    onHelpRequested: (PremiumHelpTopic) -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable { onHelpRequested(topic) }
    )
}

@Composable
private fun MessageCard(
    title: String,
    message: String?,
    color: Color
) {
    if (message.isNullOrBlank()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, color.copy(alpha = 0.75f))
            .padding(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = color
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun EmptyCard(message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(10.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk
        )
    }
}

@Composable
private fun PremiumHelpDialog(
    topic: PremiumHelpTopic,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(topic.title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(topic.message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Entendido")
            }
        }
    )
}

private fun formatWeight(value: Double): String {
    return String.format(Locale.US, "%.2f", value)
}

private fun formatSignedWeight(value: Double): String {
    return if (value >= 0.0) {
        "+${String.format(Locale.US, "%.2f", value)}"
    } else {
        String.format(Locale.US, "%.2f", value)
    }
}

private fun weightTrend(
    currentWeight: Double,
    previousWeight: Double?
): WeightTrend {
    if (previousWeight == null) return WeightTrend.FLAT
    val delta = currentWeight - previousWeight
    return when {
        delta > WEIGHT_TREND_EPSILON -> WeightTrend.UP
        delta < -WEIGHT_TREND_EPSILON -> WeightTrend.DOWN
        else -> WeightTrend.FLAT
    }
}

private enum class WeightTrend {
    UP,
    FLAT,
    DOWN
}

private fun formatTempFromC(valueC: Double?, unit: TempUnit): String {
    if (valueC == null) return "--"
    val valueInUnit = if (unit == TempUnit.C) valueC else convertTemperature(valueC, TempUnit.C, unit)
    return formatTemperature(valueInUnit, unit)
}

private const val WEIGHT_TREND_EPSILON = 0.01

private enum class PremiumHelpTopic(
    val title: String,
    val message: String
) {
    PREMIUM_ENGINE(
        title = "PolyTemp PREMIUM",
        message = "Es el motor que aprende diariamente por ciudad: compara pronosticos vs maxima observada real y ajusta los pesos de cada modelo automaticamente."
    ),
    GENERATED(
        title = "Generado",
        message = "Momento exacto del ultimo calculo del dashboard PREMIUM para esta ciudad."
    ),
    VERIFIED_DAYS(
        title = "Dias verificados",
        message = "Numero de dias cerrados y comparados contra observacion real. Cuantos mas dias, mas estable es el ranking."
    ),
    PENDING_DAYS(
        title = "Dias pendientes",
        message = "Dias con snapshots de pronostico pero sin verificacion final todavia. Normalmente bajan cuando se confirma la maxima al dia siguiente."
    ),
    RANKING(
        title = "Ranking vivo",
        message = "Ordena modelos por rendimiento reciente en esta ciudad. Verde = sube peso vs ayer, naranja = se mantiene, rojo = baja peso. El ranking alimenta los pesos dinamicos de PolyTemp PREMIUM."
    ),
    DYNAMIC_WEIGHT(
        title = "Peso dinamico",
        message = "Peso actual que usa PolyTemp PREMIUM para ese modelo en esta ciudad. Sube si acierta mas; baja si falla mas."
    ),
    BASE_WEIGHT(
        title = "Peso base",
        message = "Peso inicial antes del aprendizaje. Sirve como referencia para ver cuanto se adapta el motor."
    ),
    MULTIPLIER(
        title = "Multiplicador",
        message = "Factor aplicado sobre el peso base segun rendimiento. Mayor que 1.0 favorece al modelo; menor que 1.0 lo penaliza."
    ),
    MAE(
        title = "MAE",
        message = "Error absoluto medio. Cuanto mas bajo, mejor. Ejemplo: MAE 0.9C significa que se equivoca de media menos de 1 grado."
    ),
    RMSE(
        title = "RMSE",
        message = "Error cuadratico medio. Penaliza mas los fallos grandes. Util para detectar modelos con errores extremos."
    ),
    COVERAGE(
        title = "Cobertura",
        message = "Porcentaje de dias verificados en los que el modelo devolvio pronostico util. Cobertura baja reduce confianza operativa."
    ),
    HIT_1C(
        title = "Hit <= 1C",
        message = "Porcentaje de dias donde el error fue igual o menor a 1C. Es una metrica practica de precision fina."
    ),
    SCORE(
        title = "Score",
        message = "Puntuacion compuesta por precision, cobertura y consistencia. Alimenta el calculo final de pesos dinamicos."
    ),
    VERIFICATION(
        title = "Verificacion diaria",
        message = "Historial dia a dia de observado real vs pronostico por modelo. Permite auditar exactamente en que acierta o falla cada fuente."
    ),
    OBSERVED_MAX(
        title = "Observado",
        message = "Maxima real del dia en la estacion de control usada para resolver mercado. Es la verdad de referencia para aprender."
    ),
    FORECAST_VALUE(
        title = "Pred",
        message = "Maxima pronosticada por ese modelo para ese dia. Se compara contra observado para medir error."
    ),
    ERROR_VALUE(
        title = "Err",
        message = "Diferencia absoluta entre pronostico y observado. Cuanto mas bajo, mejor."
    ),
    STATUS(
        title = "Estado",
        message = "SUCCESS si hubo dato util, SKIPPED si se omitio por restricciones/cobertura y ERROR si fallo la llamada."
    )
}
