package dev.waadri.anylisten.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.waadri.anylisten.MainActivity
import dev.waadri.anylisten.R
import dev.waadri.anylisten.data.remote.Wire

/**
 * Keeps playback alive when the UI is gone.
 *
 * Two jobs, and it is worth being precise about which is which:
 *  1. The foreground service keeps the process off the low-memory kill list while music plays,
 *     and gives Android somewhere to attach the media notification.
 *  2. The [MediaSession] exposes transport controls to the lock screen, notification shade,
 *     Bluetooth headsets and Android Auto, all dispatching into the SAME [Player] the UI drives.
 *
 * The service is deliberately thin and builds no session of its own: the session is created by
 * [PlaybackRepository] (see `ensureMediaSession`) because the underlying ExoPlayer lives there.
 * Giving the service a second player would recreate the exact class of bug this project exists
 * to remove — two sources of truth for one playhead.
 *
 * ## Why `onCreate` starts the foreground itself
 *
 * `Context.startForegroundService()` obliges the service to call `startForeground()` within about
 * five seconds, or the platform kills the whole process with
 * `ForegroundServiceDidNotStartInTimeException`. Relying on [MediaSessionService] to promote
 * itself is not good enough here, because it only does so once the player reports an active,
 * ready playback — and for a track being streamed that means waiting out connection setup and the
 * first buffer. On a real device that wait comfortably exceeded the deadline and the app died
 * mid-song.
 *
 * So the foreground slot is claimed immediately in [onCreate] with a placeholder notification,
 * and Media3 replaces it with the real media notification as soon as playback starts. Calling
 * `startForeground()` twice is fine: the second call simply swaps the notification over.
 */
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        (application as dev.waadri.anylisten.AnyListenApp).container.playback.mediaSession

    /**
     * The task was swiped away. Playback stops too: the notification is the only "still playing"
     * affordance this app offers, and silently continuing after an explicit dismissal surprises
     * more people than it pleases. (Revisit if a "keep playing" setting is ever added.)
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as dev.waadri.anylisten.AnyListenApp).container.playback.stopForTaskRemoval()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Both the session and the player are owned by the application-scoped repository, so
        // nothing is released here: the service may be recreated while playback continues.
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.playback_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.playback_notification_title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        // `startForeground` throws when the type is not allowed, and losing the foreground slot is
        // far better than crashing: playback itself is unaffected.
        runCatching {
            ServiceCompat.startForegroundCompat(this, notification)
        }.onFailure { error ->
            dev.waadri.anylisten.Diag.problem("service.foreground.failed", error.javaClass.simpleName)
        }
    }

    private companion object {
        const val CHANNEL_ID = "anylisten_playback"
    }
}

/**
 * Wrapper so the API-level branch lives in one place. `startForeground(id, notification, type)` is
 * API 29+; below that the two-argument form is the only one available.
 */
private object ServiceCompat {
    fun startForegroundCompat(service: Service, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    private const val NOTIFICATION_ID = 1001
}

/**
 * Notification metadata for a track.
 *
 * [artworkUrl] is passed in already resolved rather than read from the track, because the server
 * hands out covering URLs that are not fetchable as-is (see
 * [dev.waadri.anylisten.data.remote.ServerUrl]); handing the raw value to the platform produced
 * `FileNotFoundException: ./api/p_static/….jpeg` in the notification.
 */
internal fun mediaMetadataFor(track: Wire.PlayMusicInfo?, artworkUrl: String?): MediaMetadata {
    if (track == null) return MediaMetadata.Builder().build()
    return MediaMetadata.Builder()
        .setTitle(track.musicInfo.name)
        .setArtist(track.musicInfo.singer)
        .setAlbumTitle(track.musicInfo.meta.albumName)
        .setArtworkUri(artworkUrl?.let(Uri::parse))
        .build()
}
