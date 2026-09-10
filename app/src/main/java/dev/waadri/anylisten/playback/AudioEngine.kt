package dev.waadri.anylisten.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Emitted when a track could not be played. */
data class PlaybackError(val message: String, val cause: Throwable? = null)

/**
 * Thin wrapper around a single ExoPlayer instance.
 *
 * Architectural point, and the reason this project exists: audio is fed straight from ExoPlayer
 * to the device's audio output. The web build instead pipes `<audio>` through a WebAudio graph
 * (`createMediaElementSource`) with `crossOrigin='anonymous'`, which on mobile browsers both
 * blocks `currentTime`/`duration` (killing the progress bar and the `ended` event, hence
 * auto-advance) and suspends when the tab is backgrounded. Nothing here touches WebAudio.
 *
 * `AudioAttributes` with `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC` also hands audio focus to the
 * platform, which is what makes pausing on incoming calls work.
 */
class AudioEngine(
    context: Context,
    private val scope: CoroutineScope,
) {
    private var player: ExoPlayer? = null
    private var tickJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _ended = MutableStateFlow(0L)
    val ended: StateFlow<Long> = _ended.asStateFlow()

    private val _errors = MutableStateFlow<PlaybackError?>(null)
    val errors: StateFlow<PlaybackError?> = _errors.asStateFlow()

    private val appContext = context.applicationContext

    fun ensurePlayer() {
        if (player != null) return
        val created = ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        created.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startTicking() else stopTicking()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _buffering.value = playbackState == Player.STATE_BUFFERING
                when (playbackState) {
                    Player.STATE_READY -> {
                        val d = created.duration
                        _durationMs.value = if (d > 0) d else 0L
                    }
                    Player.STATE_ENDED -> _ended.value = System.currentTimeMillis()
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _errors.value = PlaybackError(describe(error), error)
            }
        })

        player = created
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败，无法获取音频"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "获取音频超时"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器拒绝了音频请求（HTTP 错误）"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "音频文件不存在或链接已失效"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "不支持的音频格式"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "设备不支持该音频编码"
        else -> error.errorCodeName.ifEmpty { "播放失败" }
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                sample()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
        sample()
    }

    private fun sample() {
        val p = player ?: return
        _positionMs.value = p.currentPosition.coerceAtLeast(0L)
        val d = p.duration
        if (d > 0) _durationMs.value = d
    }

    fun setSource(url: String, startPositionMs: Long = 0L) {
        ensurePlayer()
        val p = player ?: return
        _errors.value = null
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        if (startPositionMs > 0) p.seekTo(startPositionMs)
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun stop() {
        val p = player ?: return
        p.stop()
        p.clearMediaItems()
        _positionMs.value = 0L
        _durationMs.value = 0L
        _isPlaying.value = false
        stopTicking()
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        p.seekTo(positionMs.coerceAtLeast(0L))
        _positionMs.value = positionMs.coerceAtLeast(0L)
    }

    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun setMuted(muted: Boolean) {
        player?.volume = if (muted) 0f else 1f
    }

    fun setPlaybackRate(rate: Float) {
        player?.setPlaybackSpeed(rate.coerceIn(0.25f, 4f))
    }

    fun hasSource(): Boolean = player?.mediaItemCount?.let { it > 0 } == true

    fun currentPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun duration(): Long = player?.duration?.takeIf { it > 0 } ?: 0L

    fun release() {
        stopTicking()
        player?.release()
        player = null
    }

    companion object {
        /** 500 ms: fine-grained enough for a smooth seek bar without waking the CPU needlessly. */
        const val TICK_MS = 500L
    }
}
