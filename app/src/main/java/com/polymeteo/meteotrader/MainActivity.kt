package com.polymeteo.meteotrader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.polymeteo.meteotrader.data.WeatherRepository
import com.polymeteo.meteotrader.ui.MeteoTraderApp
import com.polymeteo.meteotrader.ui.MeteoViewModelFactory
import com.polymeteo.meteotrader.ui.theme.MeteoTraderTheme

class MainActivity : ComponentActivity() {

    private val repository: WeatherRepository by lazy { WeatherRepository.createDefault(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeteoTraderTheme {
                MeteoTraderApp(viewModelFactory = MeteoViewModelFactory(repository))
            }
        }
    }
}
