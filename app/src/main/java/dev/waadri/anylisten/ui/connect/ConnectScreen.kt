package dev.waadri.anylisten.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.waadri.anylisten.domain.ConnectionPhase

@Composable
fun ConnectScreen(
    form: ConnectFormState,
    phase: ConnectionPhase,
    onUrlChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = phase is ConnectionPhase.Authenticating ||
        phase is ConnectionPhase.Connecting ||
        phase is ConnectionPhase.Connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("AnyListen", style = MaterialTheme.typography.headlineLarge)
        Text(
            "连接你自建的 any-listen 音乐服务",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = form.serverUrl,
            onValueChange = onUrlChange,
            label = { Text("服务器地址") },
            placeholder = { Text("192.168.1.5:9500") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            supportingText = { Text("可省略 http:// 前缀；HTTPS 地址会自动使用 wss") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = form.password,
            onValueChange = onPasswordChange,
            label = { Text("访问密码") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = if (form.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisible) {
                    Icon(
                        imageVector = if (form.passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (form.passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("启动时自动连接", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "使用已保存的地址与密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = form.autoConnect,
                onCheckedChange = onAutoConnectChange,
                enabled = !busy,
            )
        }

        when (phase) {
            is ConnectionPhase.Connected -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("已连接", style = MaterialTheme.typography.titleMedium)
                        Text(
                            phase.serverName.ifBlank { "(未命名服务器)" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "播放与控制现在都在这台设备上进行",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Button(onClick = onOpenPlayer, modifier = Modifier.fillMaxWidth()) {
                    Text("进入播放器")
                }
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("断开连接")
                }
            }

            is ConnectionPhase.Reconnecting -> {
                StatusCard(
                    title = "连接已断开，正在重连…",
                    detail = "第 ${phase.attempt} 次尝试：${phase.reason}",
                )
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    Text("取消")
                }
            }

            else -> {
                Button(
                    onClick = onConnect,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        when (phase) {
                            is ConnectionPhase.Authenticating -> "正在鉴权…"
                            is ConnectionPhase.Connecting -> "正在建立连接…"
                            else -> "连接"
                        },
                    )
                }
            }
        }

        if (phase is ConnectionPhase.Failed) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "连接失败",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        phase.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
