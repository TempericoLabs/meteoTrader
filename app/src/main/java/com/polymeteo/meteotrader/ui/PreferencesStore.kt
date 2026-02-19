package com.polymeteo.meteotrader.ui

import android.content.Context

data class AppPreferences(
    val appMode: AppMode = AppMode.EXPERT,
    val strategyMode: StrategyMode = StrategyMode.CONSERVADORA
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
            strategyMode = strategyMode
        )
    }

    fun saveAppMode(mode: AppMode) {
        prefs.edit().putString(KEY_APP_MODE, mode.name).apply()
    }

    fun saveStrategyMode(mode: StrategyMode) {
        prefs.edit().putString(KEY_STRATEGY_MODE, mode.name).apply()
    }

    private companion object {
        const val PREFS_NAME = "polymeteo_preferences"
        const val KEY_APP_MODE = "app_mode"
        const val KEY_STRATEGY_MODE = "strategy_mode"
    }
}
