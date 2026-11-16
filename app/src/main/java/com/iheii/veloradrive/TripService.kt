package com.iheii.veloradrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Servicio en primer plano (tipo location) que mantiene vivo a [TripTracker] mientras Android Auto
 * u otra app tapa a Velora. Notificación de baja importancia, sin sonido. Solo arranca con el
 * permiso de ubicación concedido (MainActivity lo inicia al volver a la pantalla).
 */
class TripService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            stopSelf()
            return
        }
        running = true
        TripTracker.acquire(this, fast = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cada startForegroundService exige su startForeground.
        if (!goForeground()) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        if (running) TripTracker.release(this, fast = false)
        running = false
        super.onDestroy()
    }

    private fun goForeground(): Boolean = runCatching {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }.isSuccess

    private fun notification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.trip_channel), NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    setSound(null, null)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_trip)
            .setContentTitle(getString(R.string.trip_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val NOTIFICATION_ID = 7
        private var running = false

        /** Idempotente. Debe llamarse con Velora al frente (Android no deja iniciarlo desde atrás). */
        fun start(context: Context) {
            if (running || !TripTracker.hasPermission(context)) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TripService::class.java))
            }
        }
    }
}
