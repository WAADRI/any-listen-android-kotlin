package dev.waadri.anylisten.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.waadri.anylisten.data.remote.PlayMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.configStore: DataStore<Preferences> by preferencesDataStore(name = "anylisten_config")

/** Server connection settings, as typed by the user on the connect screen. */
data class ServerConfig(
    val serverUrl: String = "",
    val password: String = "",
    val autoConnect: Boolean = true,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank()
}

/**
 * Playback state that belongs to THIS device.
 *
 * Deliberately local-only. The server is a music source (lists, stream URLs, artwork, lyrics) and
 * is never told what is playing, how loud, or in what order — so nothing here is ever written
 * upstream. That is the whole point of this app: playback is owned by the device, which is also
 * why the progress bar and auto-advance work at all here after failing in the web client.
 *
 * Consequence, stated plainly: there is no cross-device continuity. Pausing on the phone does not
 * pause the desktop app, and a half-finished song is not resumed on another device.
 */
data class PlaybackPreferences(
    /** Local playback order. Never synced. */
    val playMethod: PlayMethod = PlayMethod.LIST_LOOP,
    /**
     * Preferred stream quality, passed to the server's URL resolver. Null lets the server use its
     * own default; kept as a setting because phones on mobile data often want a lower bitrate.
     */
    val playQuality: String? = null,
    /** Where playback left off, so relaunching the app resumes roughly where it stopped. */
    val lastListId: String? = null,
    val lastTrackIndex: Int = 0,
    val lastPositionMs: Long = 0L,
    /** Restore the queue on launch instead of waiting for the user to pick a list. */
    val resumeOnLaunch: Boolean = true,
    /** Show the translation line under the original lyric. A local display preference. */
    val showTranslation: Boolean = true,
)

/**
 * Persists the server URL and password, plus this device's playback preferences.
 *
 * Credential policy, and why: the JWT the server issues has no expiry and is stored per
 * `serverId`, but re-authentication needs the password. Rather than building a second, weaker
 * credential path for the "token went stale" case, the password is stored as well — matching what
 * the web client effectively does by keeping it in the login form. On a rooted device this is
 * readable via adb; that tradeoff is deliberate for a single-user LAN tool.
 */
class ConfigStore(private val context: Context) {

    val config: Flow<ServerConfig> = context.configStore.data.map { prefs ->
        ServerConfig(
            serverUrl = prefs[KEY_URL].orEmpty(),
            password = prefs[KEY_PASSWORD].orEmpty(),
            autoConnect = prefs[KEY_AUTO_CONNECT] ?: true,
        )
    }

    suspend fun current(): ServerConfig = config.first()

    suspend fun save(config: ServerConfig) {
        context.configStore.edit { prefs ->
            prefs[KEY_URL] = config.serverUrl.trim()
            prefs[KEY_PASSWORD] = config.password
            prefs[KEY_AUTO_CONNECT] = config.autoConnect
        }
    }

    /** Keeps the URL but forgets the password, e.g. after a failed login. */
    suspend fun clear() {
        context.configStore.edit { prefs ->
            prefs.remove(KEY_URL)
            prefs.remove(KEY_PASSWORD)
            prefs.remove(KEY_AUTO_CONNECT)
        }
    }

    // ------------------------------------------------------------------ playback preferences

    val playback: Flow<PlaybackPreferences> = context.configStore.data.map { prefs -> prefs.toPlayback() }

    suspend fun currentPlayback(): PlaybackPreferences = playback.first()

    suspend fun savePlayMethod(method: PlayMethod) {
        context.configStore.edit { prefs -> prefs[KEY_PLAY_METHOD] = method.wire }
    }

    suspend fun savePlayQuality(quality: String?) {
        context.configStore.edit { prefs ->
            if (quality == null) prefs.remove(KEY_PLAY_QUALITY) else prefs[KEY_PLAY_QUALITY] = quality
        }
    }

    /** Records where playback is, so the next launch can pick it up. */
    suspend fun saveResumePoint(listId: String?, trackIndex: Int, positionMs: Long) {
        context.configStore.edit { prefs ->
            prefs[KEY_LAST_LIST_ID] = listId.orEmpty()
            prefs[KEY_LAST_TRACK_INDEX] = trackIndex
            prefs[KEY_LAST_POSITION_MS] = positionMs
        }
    }

    suspend fun saveResumeOnLaunch(enabled: Boolean) {
        context.configStore.edit { prefs -> prefs[KEY_RESUME_ON_LAUNCH] = enabled }
    }

    suspend fun saveShowTranslation(enabled: Boolean) {
        context.configStore.edit { prefs -> prefs[KEY_SHOW_TRANSLATION] = enabled }
    }

    /**
     * The translation toggle on its own, as a flow.
     *
     * Exposed separately from [playback] so the lyric pane can recompose on this one boolean
     * without re-reading the whole preference set on every playback tick.
     */
    val playbackShowTranslation: Flow<Boolean> = context.configStore.data.map { prefs ->
        prefs[KEY_SHOW_TRANSLATION] ?: true
    }

    private fun Preferences.toPlayback() = PlaybackPreferences(
        playMethod = PlayMethod.fromWire(this[KEY_PLAY_METHOD]),
        playQuality = this[KEY_PLAY_QUALITY]?.takeIf { it.isNotBlank() },
        lastListId = this[KEY_LAST_LIST_ID]?.takeIf { it.isNotBlank() },
        lastTrackIndex = this[KEY_LAST_TRACK_INDEX] ?: 0,
        lastPositionMs = this[KEY_LAST_POSITION_MS] ?: 0L,
        resumeOnLaunch = this[KEY_RESUME_ON_LAUNCH] ?: true,
        showTranslation = this[KEY_SHOW_TRANSLATION] ?: true,
    )

    private companion object {
        val KEY_URL = stringPreferencesKey("server_url")
        val KEY_PASSWORD = stringPreferencesKey("server_password")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")

        val KEY_PLAY_METHOD = stringPreferencesKey("playback_method")
        val KEY_PLAY_QUALITY = stringPreferencesKey("playback_quality")
        val KEY_LAST_LIST_ID = stringPreferencesKey("playback_last_list_id")
        val KEY_LAST_TRACK_INDEX = intPreferencesKey("playback_last_track_index")
        val KEY_LAST_POSITION_MS = longPreferencesKey("playback_last_position")
        val KEY_RESUME_ON_LAUNCH = booleanPreferencesKey("playback_resume_on_launch")
        val KEY_SHOW_TRANSLATION = booleanPreferencesKey("lyrics_show_translation")
    }
}
