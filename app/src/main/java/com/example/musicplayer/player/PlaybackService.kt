package com.example.musicplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.musicplayer.MainActivity
import com.example.musicplayer.MusicApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground-сервис: держит процесс живым и показывает уведомление с кнопками управления. */
class PlaybackService : Service() {

    companion object {
        @Volatile
        var running = false

        const val ACTION_TOGGLE = "com.example.musicplayer.TOGGLE"
        const val ACTION_NEXT = "com.example.musicplayer.NEXT"
        const val ACTION_PREV = "com.example.musicplayer.PREV"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observing: Job? = null
    private lateinit var player: PlayerManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        player = (application as MusicApplication).player
        val channel = NotificationChannel(
            CHANNEL_ID, "Воспроизведение", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Нужно вызвать сразу, иначе система завершит сервис с ошибкой.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(player.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        when (intent?.action) {
            ACTION_TOGGLE -> player.togglePlayPause()
            ACTION_NEXT -> player.next()
            ACTION_PREV -> player.previous()
        }
        if (observing == null) {
            observing = scope.launch {
                player.state
                    .map { it.current?.id to it.isPlaying }
                    .distinctUntilChanged()
                    .collect { refresh() }
            }
        }
        return START_NOT_STICKY
    }

    private fun refresh() {
        val s = player.state.value
        val notification = buildNotification(s)
        if (s.isPlaying) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            // На паузе уведомление можно смахнуть, но оно остаётся с кнопкой «Играть».
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(s: PlayerState): Notification {
        val track = s.current
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: "Музыкальный плеер")
            .setContentText(track?.artist ?: "")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(s.isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "Назад", servicePending(ACTION_PREV))
            .addAction(
                if (s.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (s.isPlaying) "Пауза" else "Играть",
                servicePending(ACTION_TOGGLE)
            )
            .addAction(android.R.drawable.ic_media_next, "Далее", servicePending(ACTION_NEXT))
            .build()
    }

    private fun servicePending(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }
}
