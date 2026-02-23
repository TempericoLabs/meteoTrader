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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.forecast.ForecastWeights
import com.polymeteo.meteotrader.data.model.CityWeatherData
import com.polymeteo.meteotrader.data.model.PolyTempPremiumModelStat
import com.polymeteo.meteotrader.data.model.PolyTempPremiumReport
import com.polymeteo.meteotrader.data.model.TempUnit
import com.polymeteo.meteotrader.ui.AppMode
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
    appMode: AppMode,
    report: PolyTempPremiumReport?,
    isRefreshing: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val isRookie = appMode == AppMode.ROOKIE
    val cityName = cityData?.city?.name ?: report?.cityName ?: "Media Modelos Ajustada (MMA)"
    val displayUnit = cityData?.city?.displayUnit ?: TempUnit.C
    val cityZoneId = cityData?.city?.zoneId ?: "UTC"
    var helpTopic by remember { mutableStateOf<PremiumHelpTopic?>(null) }
    val visibleRanking = remember(report) {
        report?.providerRanking
            ?.filterNot { stat -> ForecastWeights.isDeprecatedProvider(stat.providerId, stat.providerName) }
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
                title = { Text("Media Modelos Ajustada (MMA) • $cityName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar Media Modelos Ajustada (MMA)")
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
                if (isRookie) {
                    item {
                        RookiePremiumSummaryCard(
                            report = report,
                            visibleRanking = visibleRanking
                        )
                    }
                } else {
                    item {
                        SummaryCard(
                            report = report,
                            cityZoneId = cityZoneId,
                            isRefreshing = isRefreshing,
                            onHelpRequested = { helpTopic = it }
                        )
                    }
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

                if (!isRookie) {
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
                } else {
                    if (report == null) {
                        item { EmptyCard("Calculando ajuste histórico (MMA)...") }
                    } else if (visibleRanking.isEmpty()) {
                        item { EmptyCard("Aún no hay suficiente histórico para resumir el ajuste.") }
                    } else {
                        items(visibleRanking.take(5), key = { it.providerId }) { stat ->
                            RookieModelRankingRow(stat = stat)
                        }
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
private fun RookiePremiumSummaryCard(
    report: PolyTempPremiumReport?,
    visibleRanking: List<PolyTempPremiumModelStat>
) {
    val top = visibleRanking.firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Qué hace esta pantalla (versión fácil)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "La MMA aprende qué modelos aciertan más en esta ciudad y les da más peso.",
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk
        )
        if (report == null) {
            Text(
                text = "Todavía se está calculando el ajuste histórico.",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        } else {
            Text(
                text = "Días verificados: ${report.verifiedDays} • Pendientes: ${report.pendingDays}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
            top?.let { stat ->
                val share = visibleRanking.sumOf { it.dynamicWeight }
                    .takeIf { it > 0.0 }
                    ?.let { total -> stat.dynamicWeight / total }
                    ?: 0.0
                Text(
                    text = "Modelo que más pesa ahora: ${stat.providerName}",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Positive
                )
                Text(
                    text = "Peso actual: ${formatPercent(share)} • Error medio: ${stat.meanAbsoluteErrorC?.let { String.format(Locale.US, "%.2f°C", it) } ?: "--"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedInk
                )
            }
        }
    }
}

@Composable
private fun RookieModelRankingRow(stat: PolyTempPremiumModelStat) {
    val trendColor = when {
        stat.previousDynamicWeight == null -> MutedInk
        stat.dynamicWeight > stat.previousDynamicWeight + 1e-9 -> Positive
        stat.dynamicWeight < stat.previousDynamicWeight - 1e-9 -> Negative
        else -> Color(0xFFFFC857)
    }
    val trendLabel = when {
        stat.previousDynamicWeight == null -> "nuevo"
        stat.dynamicWeight > (stat.previousDynamicWeight ?: 0.0) + 1e-9 -> "sube"
        stat.dynamicWeight < (stat.previousDynamicWeight ?: 0.0) - 1e-9 -> "baja"
        else -> "igual"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stat.providerName,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Peso actual ${String.format(Locale.US, "%.2f", stat.dynamicWeight)} • tendencia $trendLabel",
            style = MaterialTheme.typography.labelSmall,
            color = trendColor
        )
        Text(
            text = "Error medio ${stat.meanAbsoluteErrorC?.let { String.format(Locale.US, "%.2f°C", it) } ?: "--"} • cobertura ${stat.coverage?.let { formatPercent(it) } ?: "--"}",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk
        )
    }
}

@Composable
private fun SummaryCard(
    report: PolyTempPremiumReport?,
    cityZoneId: String,
    isRefreshing: Boolean,
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
                text = "Inicializando datos de Media Modelos Ajustada (MMA)...",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedInk
            )
            if (isRefreshing) {
                Text(
                    text = "Calibrando histórico completo...",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8FC8FF)
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            return
        }

        val ranked = report.providerRanking
        val topModel = ranked.firstOrNull()
        val secondModel = ranked.drop(1).firstOrNull()
        val totalWeight = ranked.sumOf { stat -> stat.dynamicWeight }.takeIf { it > 0.0 } ?: 0.0
        val topShare = if (topModel != null && totalWeight > 0.0) {
            (topModel.dynamicWeight / totalWeight).coerceIn(0.0, 1.0)
        } else {
            null
        }
        val advantageVsSecond = if (topModel != null && secondModel != null && secondModel.dynamicWeight > 0.0) {
            topModel.dynamicWeight / secondModel.dynamicWeight
        } else {
            null
        }

        if (topModel != null) {
            Text(
                text = "Modelo dominante",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8FC8FF),
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onHelpRequested(PremiumHelpTopic.DOMINANT_MODEL) }
            )
            Text(
                text = topModel.providerName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Positive
            )
            Text(
                text = "Peso decisivo: ${formatWeight(topModel.dynamicWeight)}" +
                    (topShare?.let { share -> " (${formatPercent(share)})" } ?: ""),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = Positive
            )
            Text(
                text = "Ventaja vs 2º modelo: " +
                    (advantageVsSecond?.let { value -> String.format(Locale.US, "x%.2f", value) } ?: "--"),
                style = MaterialTheme.typography.labelSmall,
                color = if ((advantageVsSecond ?: 0.0) >= 1.20) Positive else Color(0xFFFFC857)
            )
            val dominanceBar = topShare?.toFloat()?.coerceIn(0f, 1f)
            if (dominanceBar != null) {
                LinearProgressIndicator(
                    progress = { dominanceBar },
                    modifier = Modifier.fillMaxWidth()
                )
            }
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

        if (report.bootstrapProgress.isNotEmpty()) {
            val totalDays = report.bootstrapProgress.sumOf { progress -> progress.totalDays }
            val processedDays = report.bootstrapProgress.sumOf { progress -> progress.processedDays }
            val overallFraction = if (totalDays <= 0) 1.0 else {
                processedDays.toDouble() / totalDays.toDouble()
            }.coerceIn(0.0, 1.0)

            Text(
                text = "Carga histórica completa (H0/H1/H2)",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8FC8FF),
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onHelpRequested(PremiumHelpTopic.BOOTSTRAP_PROGRESS) }
            )
            Text(
                text = "$processedDays/$totalDays (${formatPercent(overallFraction)})",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (overallFraction >= 1.0) Positive else Color(0xFFFFC857)
            )
            if (isRefreshing && overallFraction < 1.0) {
                Text(
                    text = "Calibrando histórico completo...",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8FC8FF)
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { overallFraction.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            report.bootstrapProgress.forEach { progress ->
                val progressFraction = if (progress.totalDays <= 0) {
                    1.0
                } else {
                    progress.processedDays.toDouble() / progress.totalDays.toDouble()
                }
                val progressColor = when {
                    progress.completed -> Positive
                    progressFraction >= 0.85 -> Color(0xFFFFC857)
                    else -> MutedInk
                }
                MetricLine(
                    label = "H${progress.horizonDays} ${horizonLabel(progress.horizonDays)}",
                    value = "${progress.processedDays}/${progress.totalDays} (${formatPercent(progressFraction)})",
                    topic = PremiumHelpTopic.BOOTSTRAP_PROGRESS,
                    onHelpRequested = onHelpRequested
                )
                Text(
                    text = if (progress.completed) {
                        "Completado hasta ${progress.endDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.US))}"
                    } else {
                        "Cursor: ${progress.nextDate.format(DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.US))}"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = progressColor
                )
                if (!progress.lastError.isNullOrBlank()) {
                    Text(
                        text = progress.lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFC857)
                    )
                }
            }
        }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("Hist", PremiumHelpTopic.BOOTSTRAP_PROGRESS, onHelpRequested, Modifier.weight(1f))
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

private fun horizonLabel(horizonDays: Int): String {
    return when (horizonDays) {
        0 -> "Hoy"
        1 -> "Mañana"
        2 -> "Pasado"
        else -> "H$horizonDays"
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
        title = "Media Modelos Ajustada (MMA)",
        message = "Es el motor que aprende diariamente por ciudad: compara pronosticos vs maxima observada real y ajusta los pesos de cada modelo automaticamente."
    ),
    DOMINANT_MODEL(
        title = "Modelo dominante",
        message = "Modelo con mayor peso dinámico en esta ciudad/horizonte. Cuanto mayor su ventaja frente al segundo modelo, más decisiva es su influencia en la Media Modelos Ajustada (MMA)."
    ),
    GENERATED(
        title = "Generado",
        message = "Momento exacto del ultimo calculo del dashboard de Media Modelos Ajustada (MMA) para esta ciudad."
    ),
    VERIFIED_DAYS(
        title = "Dias verificados",
        message = "Numero de dias cerrados y comparados contra observacion real. Cuantos mas dias, mas estable es el ranking."
    ),
    PENDING_DAYS(
        title = "Dias pendientes",
        message = "Dias con snapshots de pronostico pero sin verificacion final todavia. Normalmente bajan cuando se confirma la maxima al dia siguiente."
    ),
    BOOTSTRAP_PROGRESS(
        title = "Bootstrap historico",
        message = "Carga histórica completa por horizonte: H0 (hoy), H1 (mañana), H2 (pasado mañana). En refresh se recorre todo el histórico pendiente en una sola ejecución."
    ),
    RANKING(
        title = "Ranking vivo",
        message = "Ordena modelos por rendimiento reciente en esta ciudad. Verde = sube peso vs ayer, naranja = se mantiene, rojo = baja peso. El ranking alimenta los pesos dinamicos de la Media Modelos Ajustada (MMA)."
    ),
    DYNAMIC_WEIGHT(
        title = "Peso dinamico",
        message = "Peso actual que usa la Media Modelos Ajustada (MMA) para ese modelo en esta ciudad. Sube si acierta mas; baja si falla mas."
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
