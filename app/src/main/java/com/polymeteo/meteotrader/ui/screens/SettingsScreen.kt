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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.polymeteo.meteotrader.ui.AppMode
import com.polymeteo.meteotrader.ui.StrategyMode
import com.polymeteo.meteotrader.ui.theme.DarkBase
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Positive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appMode: AppMode,
    strategyMode: StrategyMode,
    onBack: () -> Unit,
    onAppModeChanged: (AppMode) -> Unit,
    onStrategyModeChanged: (StrategyMode) -> Unit
) {
    var helpTopic by remember { mutableStateOf<SettingsHelpTopic?>(null) }

    Scaffold(
        containerColor = DarkBase,
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PreferenceSection(
                title = "Modo",
                subtitle = "Experto por defecto. Rookie simplifica la pantalla para empezar.",
                content = {
                    PreferenceOption(
                        title = "Experto",
                        subtitle = "Vista completa por ciudades, datos técnicos y análisis detallado.",
                        selected = appMode == AppMode.EXPERT,
                        onClick = { onAppModeChanged(AppMode.EXPERT) }
                    )
                    PreferenceOption(
                        title = "Rookie",
                        subtitle = "Vista simple: solo oportunidades claras, ordenadas y fáciles de entender.",
                        selected = appMode == AppMode.ROOKIE,
                        onClick = { onAppModeChanged(AppMode.ROOKIE) }
                    )
                }
            )

            PreferenceSection(
                title = "Estrategia",
                subtitle = "Pulsa una estrategia para activarla y ver sus puntos importantes.",
                content = {
                    PreferenceOption(
                        title = "Conservadora",
                        subtitle = "Filtra más y prioriza calidad de ejecución.",
                        selected = strategyMode == StrategyMode.CONSERVADORA,
                        onClick = {
                            onStrategyModeChanged(StrategyMode.CONSERVADORA)
                            helpTopic = SettingsHelpTopic.CONSERVADORA
                        }
                    )
                    PreferenceOption(
                        title = "Agresiva",
                        subtitle = "Acepta más riesgo para capturar más oportunidades.",
                        selected = strategyMode == StrategyMode.AGRESIVA,
                        onClick = {
                            onStrategyModeChanged(StrategyMode.AGRESIVA)
                            helpTopic = SettingsHelpTopic.AGRESIVA
                        }
                    )
                }
            )
        }
    }

    helpTopic?.let { topic ->
        AlertDialog(
            onDismissRequest = { helpTopic = null },
            title = { Text(topic.title) },
            text = { Text(topic.message) },
            confirmButton = {
                TextButton(onClick = { helpTopic = null }) {
                    Text("Entendido")
                }
            }
        )
    }
}

@Composable
private fun PreferenceSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkPanel)
            .border(1.dp, Color(0x22FFFFFF))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MutedInk
        )
        content()
    }
}

@Composable
private fun PreferenceOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) Positive else Color(0x2FFFFFFF)
    val background = if (selected) Positive.copy(alpha = 0.11f) else DarkBase.copy(alpha = 0.45f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .border(1.dp, borderColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MutedInk
            )
        }
        Text(
            text = if (selected) "ACTIVO" else "Elegir",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (selected) Positive else MutedInk
        )
    }
}

private enum class SettingsHelpTopic(
    val title: String,
    val message: String
) {
    CONSERVADORA(
        title = "Estrategia Conservadora",
        message = "Busca operaciones de mayor calidad. Solo muestra oportunidades con ventaja más clara, buena liquidez y mejor probabilidad de ejecución. Suele generar menos entradas, pero más selectivas."
    ),
    AGRESIVA(
        title = "Estrategia Agresiva",
        message = "Busca más oportunidades y entra antes. Acepta señales con mayor variación y menor margen de seguridad. Puede capturar movimientos rápidos, pero también aumenta el riesgo de fallos."
    )
}
