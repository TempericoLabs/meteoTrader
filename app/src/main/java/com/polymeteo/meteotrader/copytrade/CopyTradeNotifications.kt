package com.polymeteo.meteotrader.copytrade

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.polymeteo.meteotrader.MainActivity
import com.polymeteo.meteotrader.R
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object CopyTradeNotifications {
    const val CHANNEL_MONITOR = "copytrade_monitor"
    const val CHANNEL_ALERTS = "copytrade_alerts"
    const val FOREGROUND_NOTIFICATION_ID = 42101

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
        .withZone(ZoneOffset.UTC)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val monitorChannel = NotificationChannel(
            CHANNEL_MONITOR,
            "CopyTrade Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitorización activa del copytrade"
            setShowBadge(false)
        }

        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            "CopyTrade Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alertas de actividad detectada en usuarios monitorizados"
        }

        manager.createNotificationChannel(monitorChannel)
        manager.createNotificationChannel(alertsChannel)
    }

    fun buildForegroundNotification(
        context: Context,
        username: String,
        wallet: String,
        pollIntervalSec: Int,
        lastPollAt: Instant? = null,
        lastError: String? = null
    ) = NotificationCompat.Builder(context, CHANNEL_MONITOR)
        .setSmallIcon(R.mipmap.ic_launcher_round)
        .setContentTitle("CopyTrade monitor activo")
        .setContentText(
            buildString {
                append(if (username.isBlank()) wallet.take(10) else "@$username")
                append(" • ")
                append("${pollIntervalSec}s")
                lastPollAt?.let {
                    append(" • poll ")
                    append(timeFormat.format(it))
                    append(" UTC")
                }
                if (!lastError.isNullOrBlank()) {
                    append(" • aviso")
                }
            }
        )
        .setStyle(
            NotificationCompat.BigTextStyle().bigText(
                buildString {
                    append("Objetivo: ")
                    append(if (username.isBlank()) wallet else "@$username ($wallet)")
                    append("\nIntervalo de sondeo: ${pollIntervalSec}s")
                    if (!lastError.isNullOrBlank()) append("\nÚltimo error: $lastError")
                }
            )
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(mainActivityPendingIntent(context))
        .build()

    fun notifyAlert(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        subtext: String?
    ) {
        ensureChannels(context)
        if (!canPostNotifications(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(subtext)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent(context))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun mainActivityPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 42102, intent, flags)
    }
}

