package dev.waadri.anylisten

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.waadri.anylisten.ui.connect.ConnectScreen
import dev.waadri.anylisten.ui.connect.ConnectViewModel
import dev.waadri.anylisten.ui.player.PlayerScreen
import dev.waadri.anylisten.ui.player.PlayerViewModel
import dev.waadri.anylisten.ui.theme.AnyListenTheme

/** Which screen is showing. Navigation is one flag; a nav graph would be noise at two screens. */
private enum class Screen { PLAYER, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AnyListenTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AnyListenAppRoot(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun AnyListenAppRoot(modifier: Modifier = Modifier) {
    val connectViewModel: ConnectViewModel = viewModel()
    val playerViewModel: PlayerViewModel = viewModel()

    val form by connectViewModel.form.collectAsStateWithLifecycle()
    val phase by connectViewModel.phase.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val connected by playerViewModel.connected.collectAsStateWithLifecycle()

    // The player is the primary screen and settings is a detour, so the player is the start
    // destination. Saved across configuration changes so rotating while editing the server
    // address does not throw the user back to the player.
    var screen by rememberSaveable { mutableStateOf(Screen.PLAYER) }

    NotificationPermissionRequest()

    // Back from settings returns to the player rather than leaving the app.
    BackHandler(enabled = screen == Screen.SETTINGS) { screen = Screen.PLAYER }

    when (screen) {
        Screen.PLAYER -> PlayerScreen(
            state = playerState,
            connected = connected,
            onTogglePlay = playerViewModel::togglePlay,
            onNext = playerViewModel::next,
            onPrevious = playerViewModel::previous,
            onSeek = playerViewModel::seekTo,
            onOpenSettings = { screen = Screen.SETTINGS },
            modifier = modifier,
        )

        Screen.SETTINGS -> ConnectScreen(
            form = form,
            phase = phase,
            onUrlChange = connectViewModel::onUrlChanged,
            onPasswordChange = connectViewModel::onPasswordChanged,
            onAutoConnectChange = connectViewModel::onAutoConnectChanged,
            onTogglePasswordVisible = connectViewModel::togglePasswordVisible,
            onConnect = connectViewModel::connect,
            onDisconnect = connectViewModel::disconnect,
            onOpenPlayer = { screen = Screen.PLAYER },
            modifier = modifier,
        )
    }
}

/**
 * Asks for the notification permission on Android 13+, once, on first composition.
 *
 * Without it the media notification cannot be posted, which on Android 14+ also means the
 * foreground service has nothing to show — playback itself still works, so a refusal is
 * tolerated rather than treated as an error. The request is fire-and-forget: the platform
 * remembers a refusal, so re-asking every launch would only be nagging.
 */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* granted or not, playback proceeds either way */ },
    )

    LaunchedEffect(Unit) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
