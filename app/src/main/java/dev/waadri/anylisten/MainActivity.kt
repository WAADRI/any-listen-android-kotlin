package dev.waadri.anylisten

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.waadri.anylisten.ui.connect.ConnectScreen
import dev.waadri.anylisten.ui.connect.ConnectViewModel
import dev.waadri.anylisten.ui.theme.AnyListenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AnyListenTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val viewModel: ConnectViewModel = viewModel()
                    val form by viewModel.form.collectAsStateWithLifecycle()
                    val phase by viewModel.phase.collectAsStateWithLifecycle()

                    ConnectScreen(
                        form = form,
                        phase = phase,
                        onUrlChange = viewModel::onUrlChanged,
                        onPasswordChange = viewModel::onPasswordChanged,
                        onAutoConnectChange = viewModel::onAutoConnectChanged,
                        onTogglePasswordVisible = viewModel::togglePasswordVisible,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
