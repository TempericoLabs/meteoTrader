package com.polymeteo.meteotrader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.polymeteo.meteotrader.data.WeatherRepository
import com.polymeteo.meteotrader.ui.MeteoTraderApp
import com.polymeteo.meteotrader.ui.MeteoViewModelFactory
import com.polymeteo.meteotrader.ui.PreferencesStore
import com.polymeteo.meteotrader.ui.theme.MeteoTraderTheme

class MainActivity : ComponentActivity() {

    private val repository: WeatherRepository by lazy { WeatherRepository.createDefault(applicationContext) }
    private val preferencesStore: PreferencesStore by lazy { PreferencesStore(applicationContext) }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: app sigue funcionando con alerta in-app si se deniega */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationsPermissionIfNeeded()
        setContent {
            MeteoTraderTheme {
                MeteoTraderApp(
                    viewModelFactory = MeteoViewModelFactory(
                        repository = repository,
                        preferencesStore = preferencesStore
                    )
                )
            }
        }
    }

    private fun requestNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
