package com.polymeteo.meteotrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.polymeteo.meteotrader.ui.screens.SettingsScreen

private object Routes {
    const val Cities = "cities"
    const val Backtest = "backtest"
    const val Settings = "settings"
    const val Detail = "detail/{cityId}"
    const val DetailBase = "detail"
    const val Premium = "premium/{cityId}"
    const val PremiumBase = "premium"
    const val Account = "account"
}

@Composable
fun MeteoTraderApp(
    viewModelFactory: MeteoViewModelFactory
) {
    val navController = rememberNavController()
    val viewModel: MeteoViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()

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
                globalError = uiState.errorMessage,
                isPremiumCalibrating = uiState.premiumRefreshingCityIds.contains(cityId),
                onBack = { navController.popBackStack() },
                onRefresh = { viewModel.refreshCity(cityId) },
                onOpenPolyTempPremium = {
                    navController.navigate("${Routes.PremiumBase}/$cityId")
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
                snapshot = uiState.polymarketAccountSnapshot,
                isRefreshing = uiState.isPolymarketAccountRefreshing,
                errorMessage = uiState.polymarketAccountErrorMessage,
                onBack = { navController.popBackStack() },
                onRefresh = { viewModel.refreshPolymarketAccount(force = true) }
            )
        }
    }
}
