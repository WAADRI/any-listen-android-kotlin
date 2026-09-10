package dev.waadri.anylisten

import android.app.Application
import dev.waadri.anylisten.data.config.ConfigStore
import dev.waadri.anylisten.domain.ClientSession
import dev.waadri.anylisten.domain.LyricsRepository
import dev.waadri.anylisten.playback.PlaybackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Manual dependency container. The graph is small enough that a DI framework would cost more
 * than it saves; everything is constructed once here and reached through [AnyListenApp].
 *
 * The scope is application-lifetime on purpose: the session and the player must outlive any
 * activity, because playback continues while the UI is gone.
 */
class AppContainer(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val configStore: ConfigStore = ConfigStore(application)

    /**
     * The player takes the config store directly: playback preferences (play mode and the resume
     * point) are this device's own and are persisted locally rather than on the server.
     */
    val playback: PlaybackRepository = PlaybackRepository(application, scope, configStore)

    val clientSession: ClientSession = ClientSession(
        scope = scope,
        onSocketChanged = { socket, baseUrl ->
            playback.attachSocket(socket, baseUrl)
            lyrics.attachSocket(socket)
        },
    )

    /**
     * Lyrics are a separate collaborator from playback so a lyric fetch can never stall the
     * playhead. It observes the player rather than being called by it.
     */
    val lyrics: LyricsRepository = LyricsRepository(scope, configStore).also { repository ->
        repository.observe(playback.state)
    }

    fun shutdown() {
        playback.release()
        clientSession.disconnect()
        scope.cancel()
    }
}

class AnyListenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
