package com.polymeteo.meteotrader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.data.model.PolymarketAccountSnapshot
import com.polymeteo.meteotrader.data.model.PolymarketClosedPosition
import com.polymeteo.meteotrader.data.model.PolymarketOpenPosition
import com.polymeteo.meteotrader.data.model.PolymarketTrade
import com.polymeteo.meteotrader.ui.AppMode
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Positive
import com.polymeteo.meteotrader.util.formatInZone
import java.util.Locale
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolymarketAccountScreen(
    walletAddress: String,
    appMode: AppMode,
    snapshot: PolymarketAccountSnapshot?,
    isRefreshing: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit
) {
    val isRookie = appMode == AppMode.ROOKIE
    val uriHandler = LocalUriHandler.current

    Scaffold(
        containerColor = DarkBase,
        topBar = {
            TopAppBar(
                title = { Text("Cuenta Polymarket") },
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
                            contentDescription = "Refrescar cuenta"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (snapshot == null && isRefreshing) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Text(
                    text = "Cargando cuenta...",
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                AccountInfoCard(
                    walletAddress = walletAddress,
                    fetchedAt = snapshot?.fetchedAt?.formatInZone("UTC", "dd-MM-yyyy HH:mm 'UTC'") ?: "--",
                    warningCount = snapshot?.warnings?.size ?: 0
                )
            }

            errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                item {
                    ErrorCard(message)
                }
            }

            snapshot?.let { data ->
                item {
                    SummaryCard(data)
                }

                if (isRookie) {
                    item {
                        BaseCard(borderColor = Color(0x224FC3FF)) {
                            Text(
                                text = "Vista Rookie",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Aquí ves lo imprescindible: saldo estimado, posiciones abiertas, resultados y últimos movimientos. Pulsa 'Abrir' para ver el mercado original.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedInk,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                if (data.warnings.isNotEmpty()) {
                    item {
                        WarningCard(data.warnings)
                    }
                }

                item {
                    SectionTitle(
                        if (isRookie) "Apuestas abiertas (resumen) (${data.openPositions.size})"
                        else "Apuestas actuales (${data.openPositions.size})"
                    )
                }
                items(
                    if (isRookie) data.openPositions.take(5) else data.openPositions,
                    key = { position -> "${position.marketSlug}-${position.outcome}" }
                ) { position ->
                    OpenPositionRow(
                        position = position,
                        onOpenEvent = { slug ->
                            runCatching { uriHandler.openUri("https://polymarket.com/event/$slug") }
                        }
                    )
                }

                item {
                    SectionTitle(
                        if (isRookie) "Resultados cerrados recientes (${data.closedPositions.size})"
                        else "Histórico cerrado (${data.closedPositions.size})"
                    )
                }
                items(
                    if (isRookie) data.closedPositions.take(5) else data.closedPositions,
                    key = { position -> "${position.marketSlug}-${position.timestamp}" }
                ) { position ->
                    ClosedPositionRow(
                        position = position,
                        onOpenEvent = { slug ->
                            runCatching { uriHandler.openUri("https://polymarket.com/event/$slug") }
                        }
                    )
                }

                item {
                    SectionTitle(
                        if (isRookie) "Movimientos recientes (resumen) (${data.recentTrades.size})"
                        else "Movimientos recientes (${data.recentTrades.size})"
                    )
                }
                items(
                    if (isRookie) data.recentTrades.take(6) else data.recentTrades,
                    key = { trade -> "${trade.transactionHash}-${trade.timestamp}" }
                ) { trade ->
                    TradeRow(
                        trade = trade,
                        onOpenEvent = { slug ->
                            runCatching { uriHandler.openUri("https://polymarket.com/event/$slug") }
                        }
                    )
                }
            } ?: item {
                Text(
                    text = "Sin datos de cuenta todavía.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AccountInfoCard(
    walletAddress: String,
    fetchedAt: String,
    warningCount: Int
) {
    BaseCard {
        Text(
            text = "Wallet",
            style = MaterialTheme.typography.labelMedium,
            color = MutedInk
        )
        Text(
            text = walletAddress.ifBlank { "--" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Actualizado: $fetchedAt",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (warningCount > 0) {
            Text(
                text = "Fuentes con avisos: $warningCount",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFFC857),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun SummaryCard(snapshot: PolymarketAccountSnapshot) {
    val summary = snapshot.summary
    BaseCard {
        Text(
            text = "Resumen de cartera",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        SummaryRow("Valor total estimado", formatUsd(summary.totalValueUsd))
        SummaryRow("Posiciones abiertas", summary.openPositionsCount.toString())
        SummaryRow("Posiciones cerradas", summary.closedPositionsCount.toString())
        SummaryRow("Trades recientes", summary.recentTradesCount.toString())
        SummaryRow("Capital abierto", formatUsd(summary.openInitialValueUsd))
        SummaryRow("Valor abierto actual", formatUsd(summary.openCurrentValueUsd))
        SummaryRow(
            "PnL abierto (estimado)",
            formatSignedUsd(summary.openUnrealizedPnlUsd),
            valueColor = if (summary.openUnrealizedPnlUsd >= 0) Positive else Negative
        )
        SummaryRow(
            "PnL realizado (cerradas)",
            formatSignedUsd(summary.closedRealizedPnlUsd),
            valueColor = if (summary.closedRealizedPnlUsd >= 0) Positive else Negative
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
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
            color = MutedInk
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = valueColor
        )
    }
}

@Composable
private fun WarningCard(warnings: List<String>) {
    BaseCard(borderColor = Color(0x44FFC857)) {
        Text(
            text = "Avisos API",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFFFFC857)
        )
        warnings.take(6).forEach { warning ->
            Text(
                text = "• $warning",
                style = MaterialTheme.typography.bodySmall,
                color = MutedInk,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    BaseCard(borderColor = Color(0x55FF5D5D)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Negative
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun OpenPositionRow(
    position: PolymarketOpenPosition,
    onOpenEvent: (String) -> Unit
) {
    val pnl = position.cashPnlUsd ?: ((position.currentValueUsd ?: 0.0) - (position.initialValueUsd ?: 0.0))
    val pnlColor = if (pnl >= 0) Positive else Negative
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = position.title.ifBlank { "Mercado sin título" },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            position.marketSlug?.let { slug ->
                Text(
                    text = "Abrir",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Positive,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { onOpenEvent(slug) }
                )
            }
        }
        Text(
            text = "${position.outcome ?: "--"} • ${if (position.redeemable) "Redeemable" else "Abierta"}",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk
        )
        SummaryRow("Tamaño", formatNumber(position.size))
        SummaryRow("Precio medio", formatPrice(position.avgPrice))
        SummaryRow("Precio actual", formatPrice(position.curPrice))
        SummaryRow("Valor inicial", formatUsd(position.initialValueUsd))
        SummaryRow("Valor actual", formatUsd(position.currentValueUsd))
        SummaryRow("PnL", formatSignedUsd(pnl), valueColor = pnlColor)
        SummaryRow("PnL %", formatSignedPercent(position.percentPnl), valueColor = pnlColor)
    }
}

@Composable
private fun ClosedPositionRow(
    position: PolymarketClosedPosition,
    onOpenEvent: (String) -> Unit
) {
    val pnl = position.realizedPnlUsd ?: 0.0
    val pnlColor = if (pnl >= 0) Positive else Negative
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = position.title.ifBlank { "Mercado sin título" },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            position.marketSlug?.let { slug ->
                Text(
                    text = "Abrir",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Positive,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { onOpenEvent(slug) }
                )
            }
        }
        Text(
            text = "${position.outcome ?: "--"} • ${position.timestamp.formatInZone("UTC", "dd-MM-yyyy HH:mm 'UTC'")}",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk
        )
        SummaryRow("Total comprado", formatUsd(position.totalBoughtUsd))
        SummaryRow("Resultado realizado", formatSignedUsd(pnl), valueColor = pnlColor)
        SummaryRow("Precio cierre", formatPrice(position.curPrice))
    }
}

@Composable
private fun TradeRow(
    trade: PolymarketTrade,
    onOpenEvent: (String) -> Unit
) {
    val sideColor = if (trade.side.equals("BUY", ignoreCase = true)) Positive else Negative
    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = trade.title.ifBlank { "Trade sin título" },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            trade.marketSlug?.let { slug ->
                Text(
                    text = "Abrir",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Positive,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clickable { onOpenEvent(slug) }
                )
            }
        }
        Text(
            text = "${trade.side ?: "--"} ${trade.outcome ?: "--"} • ${trade.timestamp.formatInZone("UTC", "dd-MM-yyyy HH:mm 'UTC'")}",
            style = MaterialTheme.typography.labelSmall,
            color = sideColor
        )
        SummaryRow("Precio", formatPrice(trade.price))
        SummaryRow("Cantidad", formatNumber(trade.size))
        SummaryRow("USDC", formatUsd(trade.usdcSize))
    }
}

@Composable
private fun BaseCard(
    borderColor: Color = Color(0x22FFFFFF),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, borderColor)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content
    )
}

private fun formatUsd(value: Double?): String {
    if (value == null) return "--"
    return "$" + String.format(Locale.US, "%.2f", value)
}

private fun formatSignedUsd(value: Double?): String {
    if (value == null) return "--"
    val sign = if (value >= 0) "+" else "-"
    return "$sign$" + String.format(Locale.US, "%.2f", value.absoluteValue)
}

private fun formatPrice(value: Double?): String {
    if (value == null) return "--"
    return String.format(Locale.US, "%.3f", value)
}

private fun formatNumber(value: Double?): String {
    if (value == null) return "--"
    return String.format(Locale.US, "%.2f", value)
}

private fun formatSignedPercent(value: Double?): String {
    if (value == null) return "--"
    val sign = if (value >= 0) "+" else "-"
    return sign + String.format(Locale.US, "%.1f%%", value.absoluteValue)
}
