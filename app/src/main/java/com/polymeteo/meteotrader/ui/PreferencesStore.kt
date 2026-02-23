package com.polymeteo.meteotrader.ui

import android.content.Context

data class AppPreferences(
    val appMode: AppMode = AppMode.EXPERT,
    val strategyMode: StrategyMode = StrategyMode.CONSERVADORA,
    val copyTrade: CopyTradePreferences = CopyTradePreferences()
)

data class CopyTradePreferences(
    val enabled: Boolean = false,
    val username: String = "",
    val proxyWallet: String = "",
    val alertBuys: Boolean = true,
    val alertSells: Boolean = true,
    val alertOtherActivity: Boolean = false,
    val weatherOnly: Boolean = true,
    val trackedCitiesOnly: Boolean = false,
    val minBuyUsdc: Double = 10.0,
    val minSellUsdc: Double = 10.0,
    val pollIntervalSec: Int = 3
)

class PreferencesStore(
    context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppPreferences {
        val appMode = runCatching {
            AppMode.valueOf(prefs.getString(KEY_APP_MODE, AppMode.EXPERT.name) ?: AppMode.EXPERT.name)
        }.getOrDefault(AppMode.EXPERT)

        val strategyMode = runCatching {
            StrategyMode.valueOf(
                prefs.getString(KEY_STRATEGY_MODE, StrategyMode.CONSERVADORA.name)
                    ?: StrategyMode.CONSERVADORA.name
            )
        }.getOrDefault(StrategyMode.CONSERVADORA)

        return AppPreferences(
            appMode = appMode,
            strategyMode = strategyMode,
            copyTrade = CopyTradePreferences(
                enabled = prefs.getBoolean(KEY_COPYTRADE_ENABLED, false),
                username = prefs.getString(KEY_COPYTRADE_USERNAME, "").orEmpty(),
                proxyWallet = prefs.getString(KEY_COPYTRADE_WALLET, "").orEmpty(),
                alertBuys = prefs.getBoolean(KEY_COPYTRADE_ALERT_BUYS, true),
                alertSells = prefs.getBoolean(KEY_COPYTRADE_ALERT_SELLS, true),
                alertOtherActivity = prefs.getBoolean(KEY_COPYTRADE_ALERT_OTHER, false),
                weatherOnly = prefs.getBoolean(KEY_COPYTRADE_WEATHER_ONLY, true),
                trackedCitiesOnly = prefs.getBoolean(KEY_COPYTRADE_TRACKED_CITIES_ONLY, false),
                minBuyUsdc = prefs.getFloat(KEY_COPYTRADE_MIN_BUY_USDC, 10f).toDouble(),
                minSellUsdc = prefs.getFloat(KEY_COPYTRADE_MIN_SELL_USDC, 10f).toDouble(),
                pollIntervalSec = prefs.getInt(KEY_COPYTRADE_POLL_INTERVAL_SEC, 3).coerceIn(2, 15)
            )
        )
    }

    fun saveAppMode(mode: AppMode) {
        prefs.edit().putString(KEY_APP_MODE, mode.name).apply()
    }

    fun saveStrategyMode(mode: StrategyMode) {
        prefs.edit().putString(KEY_STRATEGY_MODE, mode.name).apply()
    }

    fun saveCopyTrade(config: CopyTradePreferences) {
        prefs.edit()
            .putBoolean(KEY_COPYTRADE_ENABLED, config.enabled)
            .putString(KEY_COPYTRADE_USERNAME, config.username)
            .putString(KEY_COPYTRADE_WALLET, config.proxyWallet)
            .putBoolean(KEY_COPYTRADE_ALERT_BUYS, config.alertBuys)
            .putBoolean(KEY_COPYTRADE_ALERT_SELLS, config.alertSells)
            .putBoolean(KEY_COPYTRADE_ALERT_OTHER, config.alertOtherActivity)
            .putBoolean(KEY_COPYTRADE_WEATHER_ONLY, config.weatherOnly)
            .putBoolean(KEY_COPYTRADE_TRACKED_CITIES_ONLY, config.trackedCitiesOnly)
            .putFloat(KEY_COPYTRADE_MIN_BUY_USDC, config.minBuyUsdc.toFloat())
            .putFloat(KEY_COPYTRADE_MIN_SELL_USDC, config.minSellUsdc.toFloat())
            .putInt(KEY_COPYTRADE_POLL_INTERVAL_SEC, config.pollIntervalSec.coerceIn(2, 15))
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "polymeteo_preferences"
        const val KEY_APP_MODE = "app_mode"
        const val KEY_STRATEGY_MODE = "strategy_mode"
        const val KEY_COPYTRADE_ENABLED = "copytrade_enabled"
        const val KEY_COPYTRADE_USERNAME = "copytrade_username"
        const val KEY_COPYTRADE_WALLET = "copytrade_wallet"
        const val KEY_COPYTRADE_ALERT_BUYS = "copytrade_alert_buys"
        const val KEY_COPYTRADE_ALERT_SELLS = "copytrade_alert_sells"
        const val KEY_COPYTRADE_ALERT_OTHER = "copytrade_alert_other"
        const val KEY_COPYTRADE_WEATHER_ONLY = "copytrade_weather_only"
        const val KEY_COPYTRADE_TRACKED_CITIES_ONLY = "copytrade_tracked_cities_only"
        const val KEY_COPYTRADE_MIN_BUY_USDC = "copytrade_min_buy_usdc"
        const val KEY_COPYTRADE_MIN_SELL_USDC = "copytrade_min_sell_usdc"
        const val KEY_COPYTRADE_POLL_INTERVAL_SEC = "copytrade_poll_interval_sec"
    }
}
