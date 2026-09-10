package dev.waadri.anylisten.ui.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.waadri.anylisten.AnyListenApp
import dev.waadri.anylisten.data.config.ServerConfig
import dev.waadri.anylisten.data.remote.AuthApi
import dev.waadri.anylisten.domain.ClientSession
import dev.waadri.anylisten.domain.ConnectionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Editable form state, kept separate from the live connection phase. */
data class ConnectFormState(
    val serverUrl: String = "",
    val password: String = "",
    val autoConnect: Boolean = true,
    val passwordVisible: Boolean = false,
    val loaded: Boolean = false,
)

class ConnectViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AnyListenApp).container

    private val _form = MutableStateFlow(ConnectFormState())
    val form: StateFlow<ConnectFormState> = _form.asStateFlow()

    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    private val session = ClientSession(viewModelScope)

    /** Exposed so later phases can hand the live RPC session to the player. */
    val clientSession: ClientSession get() = session

    private var autoConnectAttempted = false

    init {
        viewModelScope.launch {
            val saved = container.configStore.current()
            _form.value = _form.value.copy(
                serverUrl = saved.serverUrl,
                password = saved.password,
                autoConnect = saved.autoConnect,
                loaded = true,
            )
            _phase.value = session.phase.value
            if (saved.autoConnect && saved.isConfigured && saved.password.isNotEmpty() && !autoConnectAttempted) {
                autoConnectAttempted = true
                connect()
            }
        }
        viewModelScope.launch {
            session.phase.collect { _phase.value = it }
        }
    }

    fun onUrlChanged(value: String) {
        _form.value = _form.value.copy(serverUrl = value)
    }

    fun onPasswordChanged(value: String) {
        _form.value = _form.value.copy(password = value)
    }

    fun onAutoConnectChanged(value: Boolean) {
        _form.value = _form.value.copy(autoConnect = value)
    }

    fun togglePasswordVisible() {
        _form.value = _form.value.copy(passwordVisible = !_form.value.passwordVisible)
    }

    /**
     * Validates locally first so obviously broken input gets an instant answer instead of a
     * network round trip, then persists and connects.
     */
    fun connect() {
        val form = _form.value
        val normalized = AuthApi.normalizeBaseUrl(form.serverUrl)
        if (normalized == null) {
            _phase.value = ConnectionPhase.Failed("请填写有效的服务器地址，例如 192.168.1.5:9500", recoverable = true)
            return
        }
        if (form.password.isEmpty()) {
            _phase.value = ConnectionPhase.Failed("请填写访问密码", recoverable = true)
            return
        }

        viewModelScope.launch {
            container.configStore.save(
                ServerConfig(
                    serverUrl = normalized,
                    password = form.password,
                    autoConnect = form.autoConnect,
                ),
            )
            _form.value = _form.value.copy(serverUrl = normalized)
            session.connect(normalized, form.password)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            session.disconnect()
            _phase.value = ConnectionPhase.Disconnected
        }
    }
}
