package com.polymeteo.meteotrader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.model.PolymarketClosedPosition
import com.polymeteo.meteotrader.data.model.PolymarketOpenPosition
import com.polymeteo.meteotrader.data.model.PolymarketTrade
import com.polymeteo.meteotrader.data.model.PolymarketUserActivityItem
import com.polymeteo.meteotrader.data.model.PolymarketUserIntelSnapshot
import com.polymeteo.meteotrader.ui.AppMode
import com.polymeteo.meteotrader.ui.CopyTradeMonitorUiState
import com.polymeteo.meteotrader.ui.CopyTradePreferences
import com.polymeteo.meteotrader.ui.CopyTradeRuntimeAlert
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Positive
import com.polymeteo.meteotrader.util.formatInZone
import java.time.Instant
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolymarketUserIntelScreen(
    appMode: AppMode,
    snapshot: PolymarketUserIntelSnapshot?,
    copyTradeMonitor: CopyTradeMonitorUiState,
    isRefreshing: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onCopyTradeConfigChange: (CopyTradePreferences) -> Unit,
    onCopyTradeToggle: (Boolean) -> Unit,
    onClearCopyTradeAlerts: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var query by rememberSaveable(snapshot?.queryUsername) {
        mutableStateOf(snapshot?.queryUsername ?: "")
    }
    val isRookie = appMode == AppMode.ROOKIE

    Scaffold(
        containerColor = DarkBase,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Intel Polymarket",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onSearch(query) },
                        enabled = query.trim().isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Actualizar análisis"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                InfoCard(
                    title = "Pantalla informativa",
                    accent = Color(0xFF5FC8FF),
                    body = "No cambia ningún cálculo de la app. Solo analiza actividad pública del usuario para obtener contexto útil."
                )
            }

            item {
                BaseCard {
                    Text(
                        text = if (isRookie) "Busca un usuario (@alias)" else "Analizar usuario público",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isRookie) {
                            "Introduce el alias de Polymarket (por ejemplo, ikik111). La app resuelve su wallet pública y carga su actividad reciente."
                        } else {
                            "Se usa la API pública oficial para resolver @username → proxy wallet y consultar actividad/trades/posiciones públicas."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedInk,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 54.dp),
                            singleLine = true,
                            label = { Text("Usuario Polymarket") },
                            placeholder = { Text("ikik111 o @ikik111") }
                        )
                        Button(
                            onClick = { onSearch(query) },
                            enabled = query.trim().isNotBlank(),
                            modifier = Modifier
                                .height(48.dp)
                                .widthIn(min = 108.dp)
                        ) {
                            Text("Analizar")
                        }
                    }
                }
            }

            errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                item {
                    ErrorCard(message)
                }
            }

            if (isRefreshing && snapshot == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            snapshot?.let { data ->
                item {
                    ProfileCard(
                        snapshot = data,
                        onOpenProfile = {
                            runCatching {
                                uriHandler.openUri("https://polymarket.com/es/@${data.profile.username}?tab=activity")
                            }
                        }
                    )
                }

                item {
                    CopyTradeControlCard(
                        monitor = copyTradeMonitor,
                        currentProfileUsername = data.profile.username,
                        currentProfileWallet = data.profile.proxyWallet,
                        isRookie = isRookie,
                        onConfigChange = onCopyTradeConfigChange,
                        onToggle = onCopyTradeToggle,
                        onClearAlerts = onClearCopyTradeAlerts
                    )
                }

                if (data.warnings.isNotEmpty()) {
                    item {
                        WarningCard(data.warnings)
                    }
                }

                item {
                    InsightsCard(
                        snapshot = data,
                        isRookie = isRookie
                    )
                }

                item {
                    AccountSummaryCard(data)
                }

                item { SectionTitle("Actividad reciente pública (${data.activity.size})") }
                items(
                    if (isRookie) data.activity.take(8) else data.activity.take(20),
                    key = { item -> "${item.transactionHash}-${item.timestamp}" }
                ) { activity ->
                    ActivityRow(activity)
                }

                item { SectionTitle("Trades recientes (${data.account.recentTrades.size})") }
                items(
                    if (isRookie) data.account.recentTrades.take(8) else data.account.recentTrades.take(20),
                    key = { trade -> "${trade.transactionHash}-${trade.timestamp}" }
                ) { trade ->
                    TradeRowIntel(
                        trade = trade,
                        onOpenEvent = { slug ->
                            runCatching { uriHandler.openUri("https://polymarket.com/event/$slug") }
                        }
                    )
                }

                item { SectionTitle("Posiciones abiertas (${data.account.openPositions.size})") }
                items(
                    if (isRookie) data.account.openPositions.take(6) else data.account.openPositions.take(15),
                    key = { position -> "${position.marketSlug}-${position.outcome}" }
                ) { position ->
                    OpenPositionRowIntel(position)
                }

                item { SectionTitle("Posiciones cerradas (${data.account.closedPositions.size})") }
                items(
                    if (isRookie) data.account.closedPositions.take(6) else data.account.closedPositions.take(15),
                    key = { position -> "${position.marketSlug}-${position.timestamp}" }
                ) { position ->
                    ClosedPositionRowIntel(position)
                }
            }
        }
    }
}

@Composable
private fun CopyTradeControlCard(
    monitor: CopyTradeMonitorUiState,
    currentProfileUsername: String,
    currentProfileWallet: String,
    isRookie: Boolean,
    onConfigChange: (CopyTradePreferences) -> Unit,
    onToggle: (Boolean) -> Unit,
    onClearAlerts: () -> Unit
) {
    val config = monitor.config
    val targetMatchesCurrent = config.proxyWallet.equals(currentProfileWallet, ignoreCase = true)
    val targetLabel = when {
        config.username.isNotBlank() -> "@${config.username}"
        config.proxyWallet.isNotBlank() -> config.proxyWallet
        else -> "sin objetivo"
    }

    BaseCard(borderColor = if (monitor.isRunning) Color(0x3339D98A) else Color(0x33FFC857)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Realtime Copytrade",
                style = MaterialTheme.typography.titleSmall,
                color = if (monitor.isRunning) Positive else Color(0xFFFFC857),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = config.enabled,
                onCheckedChange = { enabled -> onToggle(enabled) },
                modifier = Modifier.scale(0.86f)
            )
        }
        Text(
            text = if (monitor.isRunning) "ACTIVO" else "OFF",
            style = MaterialTheme.typography.labelSmall,
            color = if (monitor.isRunning) Positive else MutedInk
        )
        Text(
            text = if (isRookie) {
                "Alertas de compras/ventas del usuario con filtros por importe."
            } else {
                "Monitor de activity pública por proxy wallet (polling rápido)."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 2.dp)
        )
        Text(
            text = "Objetivo monitorizado: $targetLabel" + if (!targetMatchesCurrent) " (perfil actual distinto)" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (!targetMatchesCurrent) {
            Text(
                text = "Al activar, el monitor se reengancha al usuario analizado en esta pantalla.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFC857),
                modifier = Modifier.padding(top = 1.dp)
            )
        }

        CopyTradeSwitchRow(
            label = "Compras",
            checked = config.alertBuys,
            onChecked = { onConfigChange(config.copy(alertBuys = it)) }
        )
        CopyTradeSwitchRow(
            label = "Ventas",
            checked = config.alertSells,
            onChecked = { onConfigChange(config.copy(alertSells = it)) }
        )
        CopyTradeSwitchRow(
            label = "Solo meteo",
            checked = config.weatherOnly,
            onChecked = { onConfigChange(config.copy(weatherOnly = it)) }
        )
        CopyTradeSwitchRow(
            label = "Solo PolyMeteo",
            checked = config.trackedCitiesOnly,
            onChecked = { onConfigChange(config.copy(trackedCitiesOnly = it)) }
        )
        CopyTradeSwitchRow(
            label = "Otros eventos",
            checked = config.alertOtherActivity,
            onChecked = { onConfigChange(config.copy(alertOtherActivity = it)) }
        )

        CopyTradeSliderRow(
            label = "Mín. compra (USDC)",
            value = config.minBuyUsdc.toFloat(),
            valueText = "$" + String.format(Locale.US, "%.0f", config.minBuyUsdc),
            range = 0f..500f,
            steps = 24,
            onValueChange = { onConfigChange(config.copy(minBuyUsdc = it.toDouble())) }
        )
        CopyTradeSliderRow(
            label = "Mín. venta (USDC)",
            value = config.minSellUsdc.toFloat(),
            valueText = "$" + String.format(Locale.US, "%.0f", config.minSellUsdc),
            range = 0f..500f,
            steps = 24,
            onValueChange = { onConfigChange(config.copy(minSellUsdc = it.toDouble())) }
        )
        CopyTradeSliderRow(
            label = "Polling (segundos)",
            value = config.pollIntervalSec.toFloat(),
            valueText = "${config.pollIntervalSec}s",
            range = 2f..15f,
            steps = 12,
            onValueChange = { onConfigChange(config.copy(pollIntervalSec = it.roundToInt().coerceIn(2, 15))) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Último poll: ${monitor.lastPollAt?.formatInZone("UTC", "HH:mm:ss 'UTC'") ?: "--"}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Última alerta: ${monitor.lastAlertAt?.formatInZone("UTC", "HH:mm:ss") ?: "--"}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }
        monitor.lastSeenActivityAt?.let { lastSeen ->
            Text(
                text = "Última actividad detectada: ${lastSeen.formatInZone("UTC", "dd-MM HH:mm:ss 'UTC'")}",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        monitor.lastError?.takeIf { it.isNotBlank() }?.let { error ->
            Text(
                text = "CopyTrade monitor: $error",
                style = MaterialTheme.typography.labelSmall,
                color = Negative,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (monitor.recentAlerts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Alertas recientes (${monitor.recentAlerts.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF7CC2FF)
                )
                Text(
                    text = "Limpiar",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF63C5FF),
                    modifier = Modifier.clickable { onClearAlerts() }
                )
            }
            monitor.recentAlerts.take(if (isRookie) 6 else 12).forEach { alert ->
                CopyTradeAlertRow(alert)
            }
        }
    }
}

@Composable
private fun CopyTradeSwitchRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.scale(0.80f)
        )
    }
}

@Composable
private fun CopyTradeSliderRow(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .scale(0.92f),
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun CopyTradeAlertRow(alert: CopyTradeRuntimeAlert) {
    val sideColor = when {
        alert.side.equals("BUY", ignoreCase = true) -> Positive
        alert.side.equals("SELL", ignoreCase = true) -> Negative
        else -> Color(0xFFFFC857)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = listOfNotNull(alert.side, alert.outcome).joinToString(" • ").ifBlank { alert.type },
                style = MaterialTheme.typography.labelSmall,
                color = sideColor
            )
            Text(
                text = alert.timestamp?.formatInZone("UTC", "HH:mm:ss") ?: "--",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }
        Text(
            text = alert.summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 3.dp)
        )
        Text(
            text = alert.title,
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun ProfileCard(
    snapshot: PolymarketUserIntelSnapshot,
    onOpenProfile: () -> Unit
) {
    BaseCard {
        Text(
            text = snapshot.profile.username,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        snapshot.profile.pseudonym?.takeIf { it.isNotBlank() }?.let { pseudo ->
            Text(
                text = "Alias: $pseudo",
                style = MaterialTheme.typography.bodySmall,
                color = MutedInk,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            text = "Proxy wallet: ${snapshot.profile.proxyWallet}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "Actualizado: ${snapshot.fetchedAt.formatInZone("UTC", "dd-MM-yyyy HH:mm:ss 'UTC'")}",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = "Abrir perfil en Polymarket",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF63C5FF),
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable { onOpenProfile() }
        )
    }
}

@Composable
private fun InsightsCard(
    snapshot: PolymarketUserIntelSnapshot,
    isRookie: Boolean
) {
    val intel = snapshot.insights
    BaseCard(borderColor = Color(0x2247DDAA)) {
        Text(
            text = if (isRookie) "Qué se ve en este usuario (útil para ti)" else "Insights operativos (solo informativo)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        MetricRow("Trades analizados", "${intel.tradeCount} (muestra reciente)")
        MetricRow("Actividad pública", "${intel.activityCount} eventos")
        MetricRow("Sesgo BUY/SELL", "${intel.buyCount}/${intel.sellCount}")
        MetricRow("Sesgo YES/NO", "${intel.yesCount}/${intel.noCount}")
        MetricRow("Ticket medio", intel.avgTradeUsdc?.let(::formatUsd) ?: "--")
        MetricRow("Ticket mediano", intel.medianTradeUsdc?.let(::formatUsd) ?: "--")
        MetricRow("Mayor ticket", intel.largestTradeUsdc?.let(::formatUsd) ?: "--")
        MetricRow("Notional total muestra", formatUsd(intel.totalTradeUsdc))
        MetricRow("Eventos únicos", intel.uniqueEventsCount.toString())
        MetricRow("Foco meteo", intel.weatherTradeRatio?.let(::formatPct) ?: "--")
        MetricRow("Foco ciudades PolyMeteo", intel.trackedCitiesRatio?.let(::formatPct) ?: "--")
        MetricRow(
            "Última actividad",
            intel.lastActivityAt?.formatInZone("UTC", "dd-MM-yyyy HH:mm 'UTC'") ?: "--"
        )

        if (intel.topTrackedCities.isNotEmpty()) {
            Text(
                text = "Top ciudades de la muestra",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF7CC2FF),
                modifier = Modifier.padding(top = 10.dp)
            )
            intel.topTrackedCities.forEach { item ->
                Text(
                    text = "• ${item.cityName}: ${item.count} trades · ${formatUsd(item.totalUsdc)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (intel.topEvents.isNotEmpty()) {
            Text(
                text = "Eventos más repetidos",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF7CC2FF),
                modifier = Modifier.padding(top = 10.dp)
            )
            intel.topEvents.take(if (isRookie) 3 else 5).forEach { item ->
                Text(
                    text = "• ${item.title.ifBlank { item.eventSlug }} (${item.tradesCount} · ${formatUsd(item.totalUsdc)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (isRookie) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (intel.styleNotes.isNotEmpty()) {
            Text(
                text = "Lectura rápida",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF7CC2FF),
                modifier = Modifier.padding(top = 10.dp)
            )
            intel.styleNotes.forEach { note ->
                Text(
                    text = "• $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Text(
            text = "Muestra usada: ${intel.fetchedTradesWindow} trades y ${intel.fetchedActivityWindow} eventos de activity (ventana reciente pública).",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun AccountSummaryCard(snapshot: PolymarketUserIntelSnapshot) {
    val summary = snapshot.account.summary
    BaseCard {
        Text(
            text = "Resumen público de cuenta (si la API lo expone)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        MetricRow("Valor cartera", summary.totalValueUsd?.let(::formatUsd) ?: "--")
        MetricRow("Posiciones abiertas", summary.openPositionsCount.toString())
        MetricRow("Posiciones cerradas", summary.closedPositionsCount.toString())
        MetricRow("Trades recientes", summary.recentTradesCount.toString())
        MetricRow("PnL abierto (muestra)", formatSignedUsd(summary.openUnrealizedPnlUsd))
        MetricRow("PnL cerrado (muestra)", formatSignedUsd(summary.closedRealizedPnlUsd))
    }
}

@Composable
private fun ActivityRow(item: PolymarketUserActivityItem) {
    val sideColor = when {
        item.side.equals("BUY", ignoreCase = true) -> Positive
        item.side.equals("SELL", ignoreCase = true) -> Negative
        else -> MutedInk
    }
    BaseCard(borderColor = Color(0x22FFFFFF)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = item.type.orEmpty().ifBlank { "ACTIVITY" },
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF7CC2FF)
            )
            Text(
                text = item.timestamp?.formatInZone("UTC", "dd-MM HH:mm") ?: "--",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = listOfNotNull(
                item.side?.uppercase(Locale.US),
                item.outcome?.uppercase(Locale.US),
                item.price?.let { "Px ${(it * 100.0).roundToInt()}c" },
                item.usdcSize?.let { "USDC ${formatUsdCompact(it)}" }
            ).joinToString(" · ").ifBlank { "--" },
            style = MaterialTheme.typography.bodySmall,
            color = sideColor,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun TradeRowIntel(
    trade: PolymarketTrade,
    onOpenEvent: (String) -> Unit
) {
    val sideColor = when {
        trade.side.equals("BUY", ignoreCase = true) -> Positive
        trade.side.equals("SELL", ignoreCase = true) -> Negative
        else -> MutedInk
    }
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = listOfNotNull(
                    trade.side?.uppercase(Locale.US),
                    trade.outcome?.uppercase(Locale.US)
                ).joinToString(" · ").ifBlank { "TRADE" },
                style = MaterialTheme.typography.labelMedium,
                color = sideColor
            )
            Text(
                text = trade.timestamp?.formatInZone("UTC", "dd-MM HH:mm") ?: "--",
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk
            )
        }
        Text(
            text = trade.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = listOfNotNull(
                trade.price?.let { "Px ${(it * 100.0).roundToInt()}c" },
                trade.size?.let { "Sz ${formatQty(it)}" },
                trade.usdcSize?.let { "USDC ${formatUsdCompact(it)}" }
            ).joinToString(" · ").ifBlank { "--" },
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 4.dp)
        )
        trade.marketSlug?.takeIf { it.isNotBlank() }?.let { slug ->
            Text(
                text = "Abrir mercado",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF63C5FF),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clickable { onOpenEvent(slug) }
            )
        }
    }
}

@Composable
private fun OpenPositionRowIntel(position: PolymarketOpenPosition) {
    BaseCard {
        Text(
            text = position.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOfNotNull(
                position.outcome?.uppercase(Locale.US),
                position.avgPrice?.let { "Avg ${(it * 100.0).roundToInt()}c" },
                position.curPrice?.let { "Now ${(it * 100.0).roundToInt()}c" }
            ).joinToString(" · ").ifBlank { "--" },
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "Valor ${position.currentValueUsd?.let(::formatUsd) ?: "--"} · PnL ${formatSignedUsd(position.cashPnlUsd ?: 0.0)}",
            style = MaterialTheme.typography.bodySmall,
            color = when {
                (position.cashPnlUsd ?: 0.0) > 0.0 -> Positive
                (position.cashPnlUsd ?: 0.0) < 0.0 -> Negative
                else -> MutedInk
            },
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ClosedPositionRowIntel(position: PolymarketClosedPosition) {
    val pnl = position.realizedPnlUsd ?: 0.0
    BaseCard {
        Text(
            text = position.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOfNotNull(
                position.outcome?.uppercase(Locale.US),
                position.avgPrice?.let { "Avg ${(it * 100.0).roundToInt()}c" },
                position.timestamp?.formatInZone("UTC", "dd-MM HH:mm")
            ).joinToString(" · ").ifBlank { "--" },
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = "PnL realizado ${formatSignedUsd(pnl)}",
            style = MaterialTheme.typography.bodySmall,
            color = when {
                pnl > 0.0 -> Positive
                pnl < 0.0 -> Negative
                else -> MutedInk
            },
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    accent: Color,
    body: String
) {
    BaseCard(borderColor = Color(0x334FC3FF)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = accent
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun WarningCard(warnings: List<String>) {
    BaseCard(borderColor = Color(0x33FFC857)) {
        Text(
            text = "Avisos de fuente/API",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFFFFC857)
        )
        warnings.distinct().forEach { warning ->
            Text(
                text = "• $warning",
                style = MaterialTheme.typography.bodySmall,
                color = MutedInk,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    BaseCard(borderColor = Color(0x33FF6B6B)) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.titleSmall,
            color = Negative
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun BaseCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Color(0x22FFFFFF),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, borderColor)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        content = content
    )
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

private fun formatUsd(value: Double): String = "$" + String.format(Locale.US, "%.2f", value)

private fun formatUsdCompact(value: Double): String {
    return when {
        value.absoluteValue >= 1000.0 -> "$" + String.format(Locale.US, "%.0f", value)
        value.absoluteValue >= 100.0 -> "$" + String.format(Locale.US, "%.1f", value)
        else -> "$" + String.format(Locale.US, "%.2f", value)
    }
}

private fun formatSignedUsd(value: Double): String {
    val sign = when {
        value > 0 -> "+"
        value < 0 -> "-"
        else -> ""
    }
    return sign + "$" + String.format(Locale.US, "%.2f", value.absoluteValue)
}

private fun formatPct(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100.0)

private fun formatQty(value: Double): String {
    return when {
        value.absoluteValue >= 100.0 -> String.format(Locale.US, "%.0f", value)
        value.absoluteValue >= 10.0 -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
}
