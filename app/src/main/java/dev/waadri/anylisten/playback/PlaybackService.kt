package dev.waadri.anylisten.playback

import android.content.Intent
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.waadri.anylisten.MainActivity
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
 */
class PlaybackService : MediaSessionService() {

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
}

/** Notification metadata for a track. Falls back to an empty metadata block, which is valid. */
internal fun mediaMetadataFor(track: Wire.PlayMusicInfo?): MediaMetadata {
    if (track == null) return MediaMetadata.Builder().build()
    return MediaMetadata.Builder()
        .setTitle(track.musicInfo.name)
        .setArtist(track.musicInfo.singer)
        .setAlbumTitle(track.musicInfo.meta.albumName)
        .setArtworkUri(track.musicInfo.meta.picUrl?.let { android.net.Uri.parse(it) })
        .build()
}
