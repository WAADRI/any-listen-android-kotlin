package dev.waadri.anylisten.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
 * Persists the server URL and password.
 *
 * Persistence policy, and why: the JWT the server issues has no expiry and is stored per
 * `serverId`, but re-authentication needs the password. Rather than building a second,
 * weaker credential path for the "token went stale" case, the password is stored as well —
 * matching what the web client effectively does by keeping it in the login form. On a rooted
 * device this is readable via adb; that tradeoff is deliberate for a single-user LAN tool.
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

    private companion object {
        val KEY_URL = stringPreferencesKey("server_url")
        val KEY_PASSWORD = stringPreferencesKey("server_password")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
    }
}
