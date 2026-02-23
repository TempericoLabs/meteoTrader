package com.polymeteo.meteotrader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.polymeteo.meteotrader.ui.screens.BacktestScreen
import com.polymeteo.meteotrader.ui.screens.CitiesScreen
import com.polymeteo.meteotrader.ui.screens.CityDetailScreen
import com.polymeteo.meteotrader.ui.screens.PolyTempPremiumScreen
import com.polymeteo.meteotrader.ui.screens.PolymarketAccountScreen
import com.polymeteo.meteotrader.ui.screens.PolymarketUserIntelScreen
import com.polymeteo.meteotrader.ui.screens.SettingsScreen
import com.polymeteo.meteotrader.ui.theme.DarkPanel
import com.polymeteo.meteotrader.ui.theme.MutedInk
import com.polymeteo.meteotrader.ui.theme.Negative
import com.polymeteo.meteotrader.ui.theme.Positive
import java.time.Instant
import java.time.temporal.ChronoUnit

private object Routes {
    const val Cities = "cities"
    const val Backtest = "backtest"
    const val Settings = "settings"
    const val Detail = "detail/{cityId}"
    const val DetailBase = "detail"
    const val Premium = "premium/{cityId}"
    const val PremiumBase = "premium"
    const val Account = "account"
    const val UserIntel = "user-intel"
}

@Composable
fun MeteoTraderApp(
    viewModelFactory: MeteoViewModelFactory
) {
    val navController = rememberNavController()
    val viewModel: MeteoViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.Cities
        ) {
        composable(Routes.Cities) {
            CitiesScreen(
                state = uiState,
                onRefresh = { viewModel.refresh() },
                onBacktestSelected = { navController.navigate(Routes.Backtest) },
                onSettingsSelected = { navController.navigate(Routes.Settings) },
                onPolymarketAccountSelected = {
                    viewModel.refreshPolymarketAccount(force = false)
                    navController.navigate(Routes.Account) {
                        launchSingleTop = true
                    }
                },
                onCitySelected = { cityId ->
                    navController.navigate("${Routes.DetailBase}/$cityId")
                }
            )
        }

        composable(Routes.Settings) {
            SettingsScreen(
                appMode = uiState.appMode,
                strategyMode = uiState.strategyMode,
                onBack = { navController.popBackStack() },
                onAppModeChanged = { viewModel.setAppMode(it) },
                onStrategyModeChanged = { viewModel.setStrategyMode(it) }
            )
        }

        composable(Routes.Backtest) {
            BacktestScreen(
                state = uiState,
                appMode = uiState.appMode,
                onBack = { navController.popBackStack() },
                onRefresh = { viewModel.refreshBacktest() }
            )
        }

        composable(
            route = Routes.Detail,
            arguments = listOf(navArgument("cityId") { type = NavType.StringType })
        ) { backStackEntry ->
            val cityId = backStackEntry.arguments?.getString("cityId").orEmpty()
            CityDetailScreen(
                cityData = uiState.cities.firstOrNull { it.city.id == cityId },
                appMode = uiState.appMode,
                globalError = uiState.errorMessage,
                isPremiumCalibrating = uiState.premiumRefreshingCityIds.contains(cityId),
                paperPortfolio = uiState.paperPortfolio,
                isPaperTradingRefreshing = uiState.isPaperTradingRefreshing,
                paperTradingErrorMessage = uiState.paperTradingErrorMessage,
                onBack = { navController.popBackStack() },
                onRefresh = { viewModel.refreshCity(cityId) },
                onRefreshPaperTrading = { viewModel.refreshPaperTrading() },
                onOpenPolyTempPremium = {
                    navController.navigate("${Routes.PremiumBase}/$cityId")
                },
                onSimulateBuyYes = { marketId, stakeUsdc ->
                    viewModel.simulateBuyYes(
                        cityId = cityId,
                        marketId = marketId,
                        stakeUsdc = stakeUsdc
                    )
                },
                onSimulateClosePosition = { positionId ->
                    viewModel.simulateClosePosition(positionId)
                }
            )
        }

        composable(
            route = Routes.Premium,
            arguments = listOf(navArgument("cityId") { type = NavType.StringType })
        ) { backStackEntry ->
            val cityId = backStackEntry.arguments?.getString("cityId").orEmpty()
            PolyTempPremiumScreen(
                cityData = uiState.cities.firstOrNull { it.city.id == cityId },
                appMode = uiState.appMode,
                report = uiState.premiumReportsByCity[cityId],
                isRefreshing = uiState.premiumRefreshingCityIds.contains(cityId),
                errorMessage = uiState.premiumErrorsByCity[cityId],
                onBack = { navController.popBackStack() },
                onRefresh = { viewModel.refreshPremium(cityId) }
            )
        }

        composable(Routes.Account) {
            LaunchedEffect(uiState.polymarketWalletAddress) {
                if (uiState.polymarketAccountSnapshot == null && !uiState.isPolymarketAccountRefreshing) {
                    viewModel.refreshPolymarketAccount(force = true)
                }
            }
            PolymarketAccountScreen(
                walletAddress = uiState.polymarketWalletAddress,
                appMode = uiState.appMode,
                snapshot = uiState.polymarketAccountSnapshot,
                isRefreshing = uiState.isPolymarketAccountRefreshing,
                errorMessage = uiState.polymarketAccountErrorMessage,
                onBack = { navController.popBackStack() },
                onRefresh = { viewModel.refreshPolymarketAccount(force = true) },
                onOpenUserIntel = {
                    navController.navigate(Routes.UserIntel) {
                        launchSingleTop = true
                    }
                }
            )
        }

            composable(Routes.UserIntel) {
                PolymarketUserIntelScreen(
                    appMode = uiState.appMode,
                    snapshot = uiState.polymarketUserIntelSnapshot,
                    copyTradeMonitor = uiState.copyTradeMonitor,
                    isRefreshing = uiState.isPolymarketUserIntelRefreshing,
                    errorMessage = uiState.polymarketUserIntelErrorMessage,
                    onBack = { navController.popBackStack() },
                    onSearch = { username ->
                        viewModel.analyzePolymarketUser(username)
                    },
                    onCopyTradeConfigChange = { config ->
                        viewModel.updateCopyTradeConfig { _ -> config }
                    },
                    onCopyTradeToggle = { enabled ->
                        viewModel.toggleCopyTradeFromCurrentIntel(enabled)
                    },
                    onClearCopyTradeAlerts = {
                        viewModel.clearCopyTradeAlerts()
                    }
                )
            }
        }

        CopyTradeForegroundAlertOverlay(
            latestAlert = uiState.copyTradeMonitor.recentAlerts.firstOrNull(),
            isMonitorRunning = uiState.copyTradeMonitor.isRunning,
            onOpenIntel = {
                navController.navigate(Routes.UserIntel) {
                    launchSingleTop = true
                }
            }
        )
    }
}

@Composable
private fun BoxScope.CopyTradeForegroundAlertOverlay(
    latestAlert: CopyTradeRuntimeAlert?,
    isMonitorRunning: Boolean,
    onOpenIntel: () -> Unit
) {
    if (!isMonitorRunning || latestAlert == null) return
    val ts = latestAlert.timestamp ?: return
    if (ChronoUnit.SECONDS.between(ts, Instant.now()) > 90) return

    val accent = when {
        latestAlert.side.equals("BUY", ignoreCase = true) -> Positive
        latestAlert.side.equals("SELL", ignoreCase = true) -> Negative
        else -> Color(0xFFFFC857)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .align(Alignment.TopCenter)
            .background(DarkPanel)
            .border(1.dp, Color(0x335FC8FF))
            .clickable { onOpenIntel() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = "COPYTRADE ALERTA • ${latestAlert.username}",
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = latestAlert.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
        Text(
            text = "Pulsa para abrir Intel de usuario",
            style = MaterialTheme.typography.labelSmall,
            color = MutedInk
        )
    }
}
