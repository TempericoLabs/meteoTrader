package com.polymeteo.meteotrader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.polymeteo.meteotrader.ui.screens.CitiesScreen
import com.polymeteo.meteotrader.ui.screens.CityDetailScreen

private object Routes {
    const val Cities = "cities"
    const val Detail = "detail/{cityId}"
    const val DetailBase = "detail"
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
                onCitySelected = { cityId ->
                    navController.navigate("${Routes.DetailBase}/$cityId")
                }
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
                onBack = { navController.popBackStack() },
                onRefresh = { viewModel.refreshCity(cityId) }
            )
        }
    }
}
