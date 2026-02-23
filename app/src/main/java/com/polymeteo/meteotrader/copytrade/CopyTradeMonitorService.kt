package com.polymeteo.meteotrader.copytrade

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.polymeteo.meteotrader.data.CityCatalog
import com.polymeteo.meteotrader.data.WeatherRepository
import com.polymeteo.meteotrader.data.model.PolymarketUserActivityItem
import com.polymeteo.meteotrader.ui.CopyTradePreferences
import com.polymeteo.meteotrader.ui.PreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.absoluteValue

class CopyTradeMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seenKeys = LinkedHashSet<String>()
    private var pollJob: Job? = null
    private lateinit var repository: WeatherRepository
    private lateinit var preferencesStore: PreferencesStore
    private var foregroundStarted = false
    private var foregroundNotiPrimed = false

    override fun onCreate() {
        super.onCreate()
        repository = WeatherRepository.createDefault(applicationContext)
        preferencesStore = PreferencesStore(applicationContext)
        CopyTradeNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitorAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val config = preferencesStore.load().copyTrade
                if (!config.enabled || config.proxyWallet.isBlank()) {
                    stopMonitorAndSelf()
                    return START_NOT_STICKY
                }
                ensureForegroundNotification(config = config)
                if (pollJob?.isActive != true) {
                    startPollingLoop(rebaseline = intent?.getBooleanExtra(EXTRA_REBASELINE, false) == true)
                }
                return START_STICKY
            }
            else -> {
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPollingLoop(rebaseline: Boolean) {
        if (rebaseline) {
            synchronized(seenKeys) { seenKeys.clear() }
        }
        pollJob = serviceScope.launch {
            var baselinePrimed = if (rebaseline) {
                false
            } else {
                synchronized(seenKeys) { seenKeys.isNotEmpty() }
            }
            var lastError: String? = null
            var lastPollAt: Instant? = null
            while (isActive) {
                val config = preferencesStore.load().copyTrade
                if (!config.enabled || config.proxyWallet.isBlank()) {
                    stopMonitorAndSelf()
                    break
                }

                try {
                    val activity = repository.fetchPolymarketUserActivityByWallet(
                        walletAddress = config.proxyWallet,
                        limit = POLL_LIMIT
                    )
                    val sorted = activity.sortedBy { it.timestamp ?: Instant.EPOCH }
                    val newItems = mutableListOf<PolymarketUserActivityItem>()
                    synchronized(seenKeys) {
                        if (!baselinePrimed && seenKeys.isEmpty()) {
                            sorted.forEach { seenKeys += keyOf(it) }
                            baselinePrimed = true
                        } else {
                            sorted.forEach { item ->
                                val key = keyOf(item)
                                if (seenKeys.add(key)) newItems += item
                            }
                        }
                        while (seenKeys.size > SEEN_KEYS_LIMIT) {
                            val first = seenKeys.firstOrNull() ?: break
                            seenKeys.remove(first)
                        }
                    }
                    newItems.mapNotNull { item -> buildAlert(item, config) }
                        .forEach { alert ->
                            CopyTradeNotifications.notifyAlert(
                                context = this@CopyTradeMonitorService,
                                notificationId = ((alert.id.hashCode().absoluteValue % 900000) + 10000),
                                title = alert.notificationTitle,
                                text = alert.notificationText,
                                subtext = alert.notificationSubtext
                            )
                        }
                    lastError = null
                    lastPollAt = Instant.now()
                    updateForegroundNotification(config, lastPollAt, lastError)
                } catch (t: Throwable) {
                    lastError = t.message ?: "Error monitorizando activity"
                    lastPollAt = Instant.now()
                    updateForegroundNotification(config, lastPollAt, lastError)
                }

                delay(config.pollIntervalSec.coerceIn(2, 15) * 1000L)
            }
        }
    }

    private fun ensureForegroundNotification(config: CopyTradePreferences) {
        val notification = CopyTradeNotifications.buildForegroundNotification(
            context = this,
            username = config.username,
            wallet = config.proxyWallet,
            pollIntervalSec = config.pollIntervalSec
        )
        if (!foregroundStarted) {
            startForeground(CopyTradeNotifications.FOREGROUND_NOTIFICATION_ID, notification)
            foregroundStarted = true
            foregroundNotiPrimed = true
        } else if (foregroundNotiPrimed) {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(CopyTradeNotifications.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun updateForegroundNotification(
        config: CopyTradePreferences,
        lastPollAt: Instant?,
        lastError: String?
    ) {
        if (!foregroundStarted) return
        val manager = androidx.core.app.NotificationManagerCompat.from(this)
        manager.notify(
            CopyTradeNotifications.FOREGROUND_NOTIFICATION_ID,
            CopyTradeNotifications.buildForegroundNotification(
                context = this,
                username = config.username,
                wallet = config.proxyWallet,
                pollIntervalSec = config.pollIntervalSec,
                lastPollAt = lastPollAt,
                lastError = lastError
            )
        )
    }

    private fun stopMonitorAndSelf() {
        pollJob?.cancel()
        pollJob = null
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun keyOf(item: PolymarketUserActivityItem): String {
        return listOf(
            item.transactionHash,
            item.type,
            item.side,
            item.outcome,
            item.title,
            item.timestamp?.epochSecond?.toString()
        ).joinToString("|")
    }

    private fun buildAlert(
        item: PolymarketUserActivityItem,
        config: CopyTradePreferences
    ): CopyTradeLocalAlert? {
        val side = item.side?.uppercase(Locale.US)
        val outcome = item.outcome?.uppercase(Locale.US)
        val titleLc = item.title.lowercase(Locale.US)
        val eventSlugLc = (item.eventSlug ?: "").lowercase(Locale.US)
        val usdc = item.usdcSize ?: ((item.size ?: 0.0) * (item.price ?: 0.0)).takeIf { it > 0.0 }

        if (config.weatherOnly) {
            val isWeather = "highest-temperature" in eventSlugLc ||
                ("temperature" in titleLc && ("highest" in titleLc || "temperatura" in titleLc))
            if (!isWeather) return null
        }
        val cityName = detectCity(eventSlugLc, titleLc)
        if (config.trackedCitiesOnly && cityName == null) return null

        when (side) {
            "BUY" -> {
                if (!config.alertBuys) return null
                if ((usdc ?: 0.0) < config.minBuyUsdc) return null
            }
            "SELL" -> {
                if (!config.alertSells) return null
                if ((usdc ?: 0.0) < config.minSellUsdc) return null
            }
            else -> if (!config.alertOtherActivity) return null
        }

        val px = item.price?.let { "${(it * 100.0).toInt()}c" } ?: "--"
        val usdcText = usdc?.let { "$" + String.format(Locale.US, "%.2f", it) } ?: "--"
        val actor = if (config.username.isBlank()) "CopyTrade" else "@${config.username}"
        val title = "$actor ${side ?: (item.type ?: "ACTIVITY")}"
        val text = buildString {
            append(listOfNotNull(outcome, usdcText, "@ $px").joinToString(" • "))
            cityName?.let {
                if (isNotBlank()) append(" • ")
                append(it)
            }
            if (isNotBlank()) append(" • ")
            append(item.title)
        }

        return CopyTradeLocalAlert(
            id = keyOf(item),
            notificationTitle = title,
            notificationText = text,
            notificationSubtext = item.timestamp?.let { "UTC ${it}" }?.take(40)
        )
    }

    private fun detectCity(eventSlugLc: String, titleLc: String): String? {
        val match = when {
            "nyc" in eventSlugLc || "new york" in titleLc -> "new-york"
            "sao-paulo" in eventSlugLc || "sao paulo" in titleLc -> "sao-paulo"
            "buenos-aires" in "$eventSlugLc|$titleLc" -> "buenos-aires"
            "london" in "$eventSlugLc|$titleLc" -> "london"
            "miami" in "$eventSlugLc|$titleLc" -> "miami"
            "toronto" in "$eventSlugLc|$titleLc" -> "toronto"
            "seattle" in "$eventSlugLc|$titleLc" -> "seattle"
            "dallas" in "$eventSlugLc|$titleLc" -> "dallas"
            "wellington" in "$eventSlugLc|$titleLc" -> "wellington"
            "ankara" in "$eventSlugLc|$titleLc" -> "ankara"
            "seoul" in "$eventSlugLc|$titleLc" -> "seoul"
            "chicago" in "$eventSlugLc|$titleLc" -> "chicago"
            "atlanta" in "$eventSlugLc|$titleLc" -> "atlanta"
            "paris" in "$eventSlugLc|$titleLc" -> "paris"
            else -> null
        } ?: return null
        return CityCatalog.findById(match)?.name ?: match
    }

    private data class CopyTradeLocalAlert(
        val id: String,
        val notificationTitle: String,
        val notificationText: String,
        val notificationSubtext: String?
    )

    companion object {
        private const val POLL_LIMIT = 25
        private const val SEEN_KEYS_LIMIT = 400
        const val ACTION_START = "com.polymeteo.meteotrader.copytrade.START"
        const val ACTION_STOP = "com.polymeteo.meteotrader.copytrade.STOP"
        const val EXTRA_REBASELINE = "rebaseline"

        fun startIntent(context: android.content.Context, rebaseline: Boolean): Intent {
            return Intent(context, CopyTradeMonitorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_REBASELINE, rebaseline)
            }
        }

        fun stopIntent(context: android.content.Context): Intent {
            return Intent(context, CopyTradeMonitorService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
