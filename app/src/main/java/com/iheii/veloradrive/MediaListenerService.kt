package com.iheii.veloradrive

import android.service.notification.NotificationListenerService

/**
 * No procesa notificaciones: existe solo para que Android permita a Velora Drive
 * leer las sesiones de medios activas (Spotify, Android Auto, YouTube...).
 */
class MediaListenerService : NotificationListenerService()
