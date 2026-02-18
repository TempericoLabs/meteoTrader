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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.model.BacktestCityStat
import com.polymeteo.meteotrader.data.model.BacktestSettlementPreview
import com.polymeteo.meteotrader.data.model.BacktestStrategyStat
import com.polymeteo.meteotrader.ui.MeteoUiState
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Positive
import com.polymeteo.meteotrader.util.directionLabel
import com.polymeteo.meteotrader.util.formatInZone
import com.polymeteo.meteotrader.util.formatPercent
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BacktestScreen(
    state: MeteoUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val report = state.backtestReport
    var helpTopic by remember { mutableStateOf<BacktestHelpTopic?>(null) }

    Scaffold(
        containerColor = DarkBase,
        topBar = {
            TopAppBar(
                title = { Text("Paper Trading") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Actualizar backtesting"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.isBacktestRefreshing,
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
                        state = state,
                        onHelpRequested = { helpTopic = it }
                    )
                }

                if (!state.backtestErrorMessage.isNullOrBlank()) {
                    item {
                        MessageCard(
                            title = "Error",
                            message = state.backtestErrorMessage,
                            color = Negative
                        )
                    }
                }

                if (report.warnings.isNotEmpty()) {
                    item {
                        MessageCard(
                            title = "Advertencias",
                            message = report.warnings.joinToString("\n") { "• $it" },
                            color = Color(0xFFFFC857)
                        )
                    }
                }

                item {
                    SectionTitle(
                        title = "Ranking por ciudad",
                        topic = BacktestHelpTopic.CITY_RANKING,
                        onHelpRequested = { helpTopic = it }
                    )
                }

                if (report.cityStats.isEmpty()) {
                    item {
                        EmptyCard("Aún no hay mercados liquidados para calcular ranking por ciudad.")
                    }
                } else {
                    items(report.cityStats.take(14), key = { it.cityId }) { stat ->
                        CityRankingRow(
                            stat = stat,
                            onHelpRequested = { helpTopic = it }
                        )
                    }
                }

                item {
                    SectionTitle(
                        title = "Ranking por estrategia",
                        topic = BacktestHelpTopic.STRATEGY_RANKING,
                        onHelpRequested = { helpTopic = it }
                    )
                }

                if (report.strategyStats.isEmpty()) {
                    item {
                        EmptyCard("Aún no hay datos para ranking de estrategia.")
                    }
                } else {
                    items(report.strategyStats.take(12), key = { "${it.direction}-${it.signal}" }) { stat ->
                        StrategyRankingRow(
                            stat = stat,
                            onHelpRequested = { helpTopic = it }
                        )
                    }
                }

                item {
                    SectionTitle(
                        title = "Liquidaciones recientes",
                        topic = BacktestHelpTopic.RECENT_SETTLEMENTS,
                        onHelpRequested = { helpTopic = it }
                    )
                }

                if (report.recentSettlements.isEmpty()) {
                    item {
                        EmptyCard("Sin liquidaciones recientes.")
                    }
                } else {
                    items(report.recentSettlements.take(20)) { settlement ->
                        RecentSettlementRow(
                            settlement = settlement,
                            onHelpRequested = { helpTopic = it }
                        )
                    }
                }
            }

            helpTopic?.let { topic ->
                BacktestHelpDialog(
                    topic = topic,
                    onDismiss = { helpTopic = null }
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    state: MeteoUiState,
    onHelpRequested: (BacktestHelpTopic) -> Unit
) {
    val report = state.backtestReport
    val pnlColor = when {
        report.totalPnlUnits > 0.0 -> Positive
        report.totalPnlUnits < 0.0 -> Negative
        else -> MutedInk
    }
    val guardrailColor = if (report.guardrailKillSwitchActive) Negative else Positive
    val dailyPnlColor = when {
        (report.guardrailDailyPnlUnits ?: 0.0) > 0.0 -> Positive
        (report.guardrailDailyPnlUnits ?: 0.0) < 0.0 -> Negative
        else -> MutedInk
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x33FFFFFF))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Generado: ${report.generatedAt.formatInZone("UTC", "dd-MM-yyyy HH:mm 'UTC'")}",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk
        )
        BacktestHelpTerms(onHelpRequested = onHelpRequested)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            InlineHelpStat(
                label = "Trades",
                value = report.totalTrades.toString(),
                topic = BacktestHelpTopic.TRADES,
                onHelpRequested = onHelpRequested
            )
            InlineHelpStat(
                label = "Simul.",
                value = report.simulatedTrades.toString(),
                topic = BacktestHelpTopic.SETTLED,
                onHelpRequested = onHelpRequested
            )
            InlineHelpStat(
                label = "Exec",
                value = report.executedTrades.toString(),
                topic = BacktestHelpTopic.EXECUTED,
                onHelpRequested = onHelpRequested
            )
            InlineHelpStat(
                label = "NoFill",
                value = report.skippedByFillTrades.toString(),
                topic = BacktestHelpTopic.NO_FILL,
                onHelpRequested = onHelpRequested
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            InlineHelpStat(
                label = "Live",
                value = "${report.liveTrades} (${report.simulatedLiveTrades}/${report.executedLiveTrades})",
                topic = BacktestHelpTopic.LIVE_HIST,
                onHelpRequested = onHelpRequested
            )
            InlineHelpStat(
                label = "Hist",
                value = "${report.historicalTrades} (${report.simulatedHistoricalTrades}/${report.executedHistoricalTrades})",
                topic = BacktestHelpTopic.LIVE_HIST,
                onHelpRequested = onHelpRequested
            )
            InlineHelpStat(
                label = "Fill",
                value = formatPercent(report.fillRate),
                topic = BacktestHelpTopic.FILL_RATE,
                onHelpRequested = onHelpRequested
            )
        }
        MetricLine("Expected PnL", formatUnits(report.expectedPnlUnits), MutedInk, BacktestHelpTopic.EXPECTED_PNL, onHelpRequested)
        MetricLine("Realized PnL", formatUnits(report.totalPnlUnits), pnlColor, BacktestHelpTopic.REALIZED_PNL, onHelpRequested)
        MetricLine("Gap ejecucion", formatUnits(report.executionGapUnits), pnlColor, BacktestHelpTopic.EXEC_GAP, onHelpRequested)
        MetricLine(
            "Guardrails",
            if (report.guardrailKillSwitchActive) "KILL-SWITCH ON" else "ACTIVO/OK",
            guardrailColor,
            BacktestHelpTopic.GUARDRAILS,
            onHelpRequested
        )
        MetricLine(
            "PnL dia UTC",
            "${formatUnits(report.guardrailDailyPnlUnits)} (${report.guardrailDayUtc ?: "--"})",
            dailyPnlColor,
            BacktestHelpTopic.DAILY_LIMITS,
            onHelpRequested
        )
        MetricLine(
            "Loss streak",
            report.guardrailConsecutiveLosses?.toString() ?: "--",
            MutedInk,
            BacktestHelpTopic.KILL_SWITCH,
            onHelpRequested
        )
        MetricLine("Stake total", formatUnits(report.totalStakeUnits), MutedInk, BacktestHelpTopic.STAKE, onHelpRequested)
        MetricLine("ROI", formatPercent(report.roi), MutedInk, BacktestHelpTopic.ROI, onHelpRequested)
        MetricLine("Hit-rate", formatPercent(report.hitRate), MutedInk, BacktestHelpTopic.HIT_RATE, onHelpRequested)
        MetricLine("Avg edge", formatPercent(report.avgExpectedEdge), MutedInk, BacktestHelpTopic.AVG_EDGE, onHelpRequested)
        MetricLine("Brier", formatDecimal(report.brierScore), MutedInk, BacktestHelpTopic.BRIER, onHelpRequested)
        MetricLine("Log-loss", formatDecimal(report.logLoss), MutedInk, BacktestHelpTopic.LOG_LOSS, onHelpRequested)
    }
}

@Composable
private fun CityRankingRow(
    stat: BacktestCityStat,
    onHelpRequested: (BacktestHelpTopic) -> Unit
) {
    val pnlColor = when {
        stat.totalPnlUnits > 0.0 -> Positive
        stat.totalPnlUnits < 0.0 -> Negative
        else -> MutedInk
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(10.dp)
    ) {
        Text(
            text = stat.cityName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniHelpMetric(
                label = "Sim/Exec",
                value = "${stat.settledTrades}/${stat.executedTrades}",
                topic = BacktestHelpTopic.TRADES,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Hit",
                value = formatPercent(stat.hitRate),
                topic = BacktestHelpTopic.HIT_RATE,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Fill",
                value = formatPercent(stat.fillRate),
                topic = BacktestHelpTopic.FILL_RATE,
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
            MiniHelpMetric(
                label = "Real",
                value = formatUnits(stat.totalPnlUnits),
                valueColor = pnlColor,
                topic = BacktestHelpTopic.REALIZED_PNL,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Exp",
                value = formatUnits(stat.expectedPnlUnits),
                topic = BacktestHelpTopic.EXPECTED_PNL,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Gap",
                value = formatUnits(stat.executionGapUnits),
                topic = BacktestHelpTopic.EXEC_GAP,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StrategyRankingRow(
    stat: BacktestStrategyStat,
    onHelpRequested: (BacktestHelpTopic) -> Unit
) {
    val pnlColor = when {
        stat.totalPnlUnits > 0.0 -> Positive
        stat.totalPnlUnits < 0.0 -> Negative
        else -> MutedInk
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(10.dp)
    ) {
        Text(
            text = "${directionLabel(stat.direction)} • ${stat.signal.name}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onHelpRequested(BacktestHelpTopic.DIRECTION_SIGNAL) }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniHelpMetric(
                label = "Sim/Exec",
                value = "${stat.settledTrades}/${stat.executedTrades}",
                topic = BacktestHelpTopic.TRADES,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Hit",
                value = formatPercent(stat.hitRate),
                topic = BacktestHelpTopic.HIT_RATE,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Fill",
                value = formatPercent(stat.fillRate),
                topic = BacktestHelpTopic.FILL_RATE,
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
            MiniHelpMetric(
                label = "Real",
                value = formatUnits(stat.totalPnlUnits),
                valueColor = pnlColor,
                topic = BacktestHelpTopic.REALIZED_PNL,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Exp",
                value = formatUnits(stat.expectedPnlUnits),
                topic = BacktestHelpTopic.EXPECTED_PNL,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Gap",
                value = formatUnits(stat.executionGapUnits),
                topic = BacktestHelpTopic.EXEC_GAP,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MiniHelpMetric(
    label: String,
    value: String,
    topic: BacktestHelpTopic,
    onHelpRequested: (BacktestHelpTopic) -> Unit,
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
private fun RecentSettlementRow(
    settlement: BacktestSettlementPreview,
    onHelpRequested: (BacktestHelpTopic) -> Unit
) {
    val pnlColor = if (settlement.pnlUnits >= 0.0) Positive else Negative
    val dateFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.US)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(10.dp)
    ) {
        Text(
            text = "${settlement.cityName} • ${settlement.targetDate.format(dateFormatter)}",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF8FC8FF)
        )
        Text(
            text = settlement.question,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${directionLabel(settlement.direction)} • ${settlement.recommendedBuy} • ${if (settlement.executed) "EXEC" else "NO-FILL"}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    !settlement.executed -> MutedInk
                    settlement.hit -> Positive
                    else -> Negative
                },
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onHelpRequested(BacktestHelpTopic.DIRECTION_SIGNAL) }
            )
            Text(
                text = "Real ${formatUnits(settlement.pnlUnits)}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = pnlColor,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { onHelpRequested(BacktestHelpTopic.REALIZED_PNL) }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MiniHelpMetric(
                label = "Obs",
                value = formatObserved(settlement.observedMaxInConditionUnit, settlement.conditionUnit),
                topic = BacktestHelpTopic.OBSERVED_MAX,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Exp",
                value = formatUnits(settlement.expectedPnlUnits),
                topic = BacktestHelpTopic.EXPECTED_PNL,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
            MiniHelpMetric(
                label = "Gap",
                value = formatUnits(settlement.executionGapUnits),
                topic = BacktestHelpTopic.EXEC_GAP,
                onHelpRequested = onHelpRequested,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricLine(
    label: String,
    value: String,
    valueColor: Color,
    topic: BacktestHelpTopic,
    onHelpRequested: (BacktestHelpTopic) -> Unit
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
            color = valueColor
        )
    }
}

@Composable
private fun SectionTitle(
    title: String,
    topic: BacktestHelpTopic?,
    onHelpRequested: (BacktestHelpTopic) -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textDecoration = if (topic != null) TextDecoration.Underline else TextDecoration.None,
        modifier = if (topic != null) Modifier.clickable { onHelpRequested(topic) } else Modifier
    )
}

@Composable
private fun MessageCard(title: String, message: String?, color: Color) {
    if (message.isNullOrBlank()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, color.copy(alpha = 0.7f))
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

private fun formatUnits(value: Double?): String {
    if (value == null) return "--"
    return String.format(Locale.US, "%+.3f u", value)
}

private fun formatDecimal(value: Double?): String {
    if (value == null) return "--"
    return String.format(Locale.US, "%.3f", value)
}

private fun formatObserved(value: Double?, unit: com.polymeteo.meteotrader.data.model.TempUnit): String {
    if (value == null) return "--"
    return String.format(Locale.US, "%.1f°%s", value, unit.symbol)
}

@Composable
private fun InlineHelpStat(
    label: String,
    value: String,
    topic: BacktestHelpTopic,
    onHelpRequested: (BacktestHelpTopic) -> Unit
) {
    Column(modifier = Modifier.padding(end = 6.dp)) {
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
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun BacktestHelpTerms(onHelpRequested: (BacktestHelpTopic) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("Risk", BacktestHelpTopic.GUARDRAILS, onHelpRequested, Modifier.weight(1f))
            HelpPill("Diario", BacktestHelpTopic.DAILY_LIMITS, onHelpRequested, Modifier.weight(1f))
            HelpPill("MktLoss", BacktestHelpTopic.MARKET_LOSS, onHelpRequested, Modifier.weight(1f))
            HelpPill("Kill", BacktestHelpTopic.KILL_SWITCH, onHelpRequested, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("Trades", BacktestHelpTopic.TRADES, onHelpRequested, Modifier.weight(1f))
            HelpPill("Exec", BacktestHelpTopic.EXECUTED, onHelpRequested, Modifier.weight(1f))
            HelpPill("NoFill", BacktestHelpTopic.NO_FILL, onHelpRequested, Modifier.weight(1f))
            HelpPill("Fill%", BacktestHelpTopic.FILL_RATE, onHelpRequested, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("ExpPnL", BacktestHelpTopic.EXPECTED_PNL, onHelpRequested, Modifier.weight(1f))
            HelpPill("RealPnL", BacktestHelpTopic.REALIZED_PNL, onHelpRequested, Modifier.weight(1f))
            HelpPill("Gap", BacktestHelpTopic.EXEC_GAP, onHelpRequested, Modifier.weight(1f))
            HelpPill("ROI", BacktestHelpTopic.ROI, onHelpRequested, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HelpPill("Hit", BacktestHelpTopic.HIT_RATE, onHelpRequested, Modifier.weight(1f))
            HelpPill("Brier", BacktestHelpTopic.BRIER, onHelpRequested, Modifier.weight(1f))
            HelpPill("LogLoss", BacktestHelpTopic.LOG_LOSS, onHelpRequested, Modifier.weight(1f))
            HelpPill("Live/Hist", BacktestHelpTopic.LIVE_HIST, onHelpRequested, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HelpPill(
    text: String,
    topic: BacktestHelpTopic,
    onHelpRequested: (BacktestHelpTopic) -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF8FC8FF),
        textDecoration = TextDecoration.Underline,
        modifier = modifier
            .background(Color(0x1A8FC8FF))
            .border(1.dp, Color(0x558FC8FF))
            .clickable { onHelpRequested(topic) }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun BacktestHelpDialog(
    topic: BacktestHelpTopic,
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

private enum class BacktestHelpTopic(
    val title: String,
    val message: String
) {
    GUARDRAILS(
        title = "Guardrails",
        message = "Guardrails son reglas obligatorias de riesgo. Si se incumple una, el paper trading bloquea la entrada."
    ),
    DAILY_LIMITS(
        title = "Limites diarios",
        message = "Limita trades y stake por dia UTC. Tambien detiene entradas si la perdida diaria supera el maximo permitido."
    ),
    MARKET_LOSS(
        title = "Max perdida por mercado",
        message = "Si un mercado acumula perdida por encima del umbral, se bloquean nuevas entradas en ese mercado."
    ),
    KILL_SWITCH(
        title = "Kill-switch",
        message = "Se activa automaticamente al superar perdida diaria o racha de perdidas. Mientras este activo, no se permite abrir nuevos trades."
    ),
    TRADES(
        title = "Trades",
        message = "Trades es el numero total de operaciones registradas para evaluar la estrategia."
    ),
    SETTLED(
        title = "Simulados",
        message = "Simulados son trades cuyo dia objetivo ya cerró y pasaron por el motor de paper trading."
    ),
    EXECUTED(
        title = "Ejecutados",
        message = "Ejecutados son trades que el simulador consiguió llenar segun reglas de fill, spread y liquidez."
    ),
    NO_FILL(
        title = "NoFill",
        message = "NoFill indica que la orden no se llenó en la simulacion. Cuenta para control de ejecucion, pero no arriesga stake."
    ),
    FILL_RATE(
        title = "Fill-rate",
        message = "Fill-rate es el porcentaje de trades simulados que realmente se ejecutan. Si cae, la ventaja teorica es dificil de capturar."
    ),
    LIVE_HIST(
        title = "Live vs Hist",
        message = "Live son operaciones capturadas en tiempo real desde esta app. Hist son operaciones importadas del historico inicial para arrancar con datos. Formato: total (simulados/ejecutados)."
    ),
    EXPECTED_PNL(
        title = "Expected PnL",
        message = "Expected PnL es la ganancia esperada por el modelo bajo reglas de ejecucion simuladas."
    ),
    REALIZED_PNL(
        title = "Realized PnL",
        message = "Realized PnL es la ganancia/perdida que realmente obtuvo la simulacion tras fill y liquidacion final."
    ),
    EXEC_GAP(
        title = "Gap de ejecucion",
        message = "Gap de ejecucion = Realized PnL - Expected PnL. Negativo indica que la ejecucion real rindio peor de lo esperado."
    ),
    STAKE(
        title = "Stake",
        message = "Stake es el coste pagado al entrar en cada posicion (precio de YES o NO). Sirve para calcular ROI."
    ),
    ROI(
        title = "ROI",
        message = "ROI mide rentabilidad: PnL total dividido por stake total. Ejemplo: 10% significa 0.10 unidades de beneficio por cada 1 unidad invertida."
    ),
    HIT_RATE(
        title = "Hit-rate",
        message = "Hit-rate es el porcentaje de trades acertados. No mide tamano de ganancias/perdidas, solo aciertos."
    ),
    AVG_EDGE(
        title = "Avg edge",
        message = "Avg edge es la ventaja media estimada al entrar. Si el edge real no se convierte en PnL, hay problema de modelo o ejecucion."
    ),
    BRIER(
        title = "Brier",
        message = "Brier mide calidad de probabilidades (0 es perfecto). Cuanto mas bajo, mejor calibrado esta el modelo."
    ),
    LOG_LOSS(
        title = "Log-loss",
        message = "Log-loss penaliza mucho la confianza excesiva cuando falla. Menor valor significa mejor prediccion probabilistica."
    ),
    DIRECTION_SIGNAL(
        title = "Direction y señal",
        message = "Direction muestra si la tesis era OVER, UNDER o RANGE. YES/NO indica el lado comprado. EXEC/NO-FILL indica si pudo ejecutarse."
    ),
    OBSERVED_MAX(
        title = "Obs",
        message = "Obs es la temperatura maxima observada usada para liquidar el trade. Es el dato real contra el que se valida la prediccion."
    ),
    CITY_RANKING(
        title = "Ranking por ciudad",
        message = "Ordena ciudades por resultado acumulado para detectar donde la estrategia rinde mejor y donde conviene reducir riesgo."
    ),
    STRATEGY_RANKING(
        title = "Ranking por estrategia",
        message = "Agrupa por direccion y senal para ver que combinaciones de OVER/UNDER/RANGE y semaforo realmente aportan valor."
    ),
    RECENT_SETTLEMENTS(
        title = "Liquidaciones recientes",
        message = "Muestra los ultimos trades cerrados con su resultado. Sirve para auditar rapidamente si el comportamiento reciente mejora o empeora."
    )
}
