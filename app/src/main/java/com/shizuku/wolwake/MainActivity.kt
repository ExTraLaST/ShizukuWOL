package com.shizuku.wolwake

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shizuku.wolwake.data.WolConfig
import com.shizuku.wolwake.net.PingCheck
import com.shizuku.wolwake.net.ZeroTierManager
import com.shizuku.wolwake.ui.theme.WolWakeTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: WakeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WolWakeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WolWakeApp(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshOnResume()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WolWakeApp(viewModel: WakeViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }

    val config by viewModel.config.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val ztStatus by viewModel.ztStatus.collectAsState()
    val ztDiagLog by viewModel.ztDiagLog.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) "设置" else "一键唤醒") },
                navigationIcon = {
                    if (showSettings) {
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (!showSettings) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (showSettings) {
            SettingsScreen(
                config = config,
                ztStatus = ztStatus,
                ztDiagLog = ztDiagLog,
                onSave = { viewModel.saveConfig(it) },
                onToggleDiagLog = { viewModel.setDiagLogEnabled(it) },
                onReconnectZt = { viewModel.reconnectZt() },
                onClearDiagLog = { ZeroTierManager.clearDiagLog() },
                modifier = Modifier.padding(padding)
            )
        } else {
            HomeScreen(
                config = config,
                uiState = uiState,
                ztStatus = ztStatus,
                onWakeAll = { viewModel.wakeAll() },
                onCancelWake = { viewModel.cancelWake() },
                onWakePacketOnly = { viewModel.wakeByPacketOnly() },
                onWakeSshOnly = { viewModel.wakeBySshOnly() },
                onCheckStatus = { viewModel.checkStatus() },
                onToggleZt = { viewModel.setZtEnabled(it) },
                onReconnectZt = { viewModel.reconnectZt() },
                onOpenSettings = { showSettings = true },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
fun HomeScreen(
    config: WolConfig,
    uiState: WakeUiState,
    ztStatus: ZeroTierManager.ZtStatus,
    onWakeAll: () -> Unit,
    onCancelWake: () -> Unit,
    onWakePacketOnly: () -> Unit,
    onWakeSshOnly: () -> Unit,
    onCheckStatus: () -> Unit,
    onToggleZt: (Boolean) -> Unit,
    onReconnectZt: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val macOk = WolConfig.isValidMac(config.macAddress)
    val scrollState = rememberScrollState()

    var macRevealed by remember { mutableStateOf(false) }
    var targetRevealed by remember { mutableStateOf(false) }
    var sshRevealed by remember { mutableStateOf(false) }
    var nodeIdRevealed by remember { mutableStateOf(false) }
    var ztIpRevealed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = config.deviceName,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(4.dp))

        if (config.macAddress.isBlank()) {
            Text(
                text = "未填写 MAC 地址",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier.clickable { macRevealed = !macRevealed },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val upperMac = config.macAddress.uppercase(Locale.ROOT)
                Text(
                    text = if (macRevealed) upperMac else maskPreservingFormat(upperMac),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (macRevealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (macRevealed) "隐藏 MAC 地址" else "显示 MAC 地址",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        FilledIconButton(
            onClick = { if (uiState.isWaking) onCancelWake() else onWakeAll() },
            modifier = Modifier.size(168.dp)
        ) {
            if (uiState.isWaking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "一键唤醒",
                    modifier = Modifier.size(72.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = if (uiState.isWaking) "正在唤醒…点按停止" else "点一下，开机",
            style = MaterialTheme.typography.titleMedium
        )

        if (!macOk) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "请先去设置里填写电脑的 MAC 地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("设备状态", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(
                        onClick = onCheckStatus,
                        enabled = !uiState.isChecking
                    ) {
                        if (uiState.isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("检测状态")
                    }
                }

                Spacer(Modifier.height(8.dp))
                StatusRow(
                    label = "电脑",
                    value = statusText(uiState.pcStatus),
                    valueColor = if (uiState.pcStatus == PingCheck.Status.ONLINE) {
                        Color(0xFF2E7D32)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    showDot = true
                )
                MaskedStatusRow(
                    label = "目标地址",
                    value = "${config.broadcastAddress}:${config.wolPort}",
                    revealed = targetRevealed,
                    onToggle = { targetRevealed = !targetRevealed },
                    isPlaceholder = config.broadcastAddress.isBlank(),
                    placeholderValue = "未填写"
                )
                MaskedStatusRow(
                    label = "路由器 SSH",
                    value = "${config.sshUser}@${config.sshHost}:${config.sshPort}",
                    revealed = sshRevealed,
                    onToggle = { sshRevealed = !sshRevealed },
                    isPlaceholder = config.sshHost.isBlank(),
                    placeholderValue = "未填写"
                )
                if (uiState.lastActionTime != null) {
                    StatusRow("上次操作", uiState.lastActionTime)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("内置 ZeroTier", style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = config.ztEnabled,
                        onCheckedChange = onToggleZt
                    )
                }

                Spacer(Modifier.height(8.dp))
                if (!config.ztEnabled) {
                    Text(
                        text = "未开启。开启后 App 自己连 ZeroTier，不用再打开 ZeroTier One。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (WolConfig.parseZtNetworkId(config.ztNetworkId) == null) {
                    Text(
                        text = "已开启，但还没填 ZeroTier 网络 ID，请去设置里填写。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    MaskedStatusRow(
                        label = "Node ID",
                        value = ztStatus.nodeIdHex(),
                        revealed = nodeIdRevealed,
                        onToggle = { nodeIdRevealed = !nodeIdRevealed },
                        isPlaceholder = ztStatus.nodeIdHex().isBlank(),
                        placeholderValue = "还未启动"
                    )
                    MaskedStatusRow(
                        label = "ZeroTier IP",
                        value = ztStatus.assignedIp ?: "",
                        revealed = ztIpRevealed,
                        onToggle = { ztIpRevealed = !ztIpRevealed },
                        isPlaceholder = ztStatus.assignedIp.isNullOrBlank(),
                        placeholderValue = "未分配"
                    )
                    StatusRow(
                        label = "状态",
                        value = when {
                            ztStatus.networkReady -> "已就绪"
                            ztStatus.nodeId != 0L -> "连接中…"
                            else -> "未连接"
                        }
                    )
                    if (ztStatus.nodeId != 0L && !ztStatus.networkReady) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "首次使用：请去 my.zerotier.com 授权这个 Node ID，授权后才能拿到 IP。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    ztStatus.lastError?.let { err ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = maskSensitiveText(
                                text = err,
                                config = config,
                                ztStatus = ztStatus,
                                macRevealed = macRevealed,
                                targetRevealed = targetRevealed,
                                sshRevealed = sshRevealed,
                                nodeIdRevealed = nodeIdRevealed,
                                ztIpRevealed = ztIpRevealed
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onReconnectZt,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("重新连接")
                        }
                        OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("查看诊断")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onWakePacketOnly,
                enabled = !uiState.isWaking,
                modifier = Modifier.weight(1f)
            ) {
                Text("只发魔法包")
            }
            OutlinedButton(
                onClick = onWakeSshOnly,
                enabled = !uiState.isWaking,
                modifier = Modifier.weight(1f)
            ) {
                Text("只用 SSH 唤醒")
            }
        }

        if (uiState.resultLines.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("唤醒结果", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    uiState.resultLines.forEach { line ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (line.success) {
                                    Icons.Filled.CheckCircle
                                } else {
                                    Icons.Filled.Error
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (line.success) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            val detailText = maskSensitiveText(
                                text = line.detail,
                                config = config,
                                ztStatus = ztStatus,
                                macRevealed = macRevealed,
                                targetRevealed = targetRevealed,
                                sshRevealed = sshRevealed,
                                nodeIdRevealed = nodeIdRevealed,
                                ztIpRevealed = ztIpRevealed
                            )
                            Text(
                                text = "${line.label}：$detailText",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "开启内置 ZeroTier 后，App 自己连回家，不用再开 ZeroTier One。\nSSH 方式由路由器在内网发包，最可靠。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun StatusRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    showDot: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showDot) {
                val dotColor = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun maskPreservingFormat(value: String): String =
    value.map { ch -> if (ch.isLetterOrDigit()) '*' else ch }.joinToString("")

@Composable
fun MaskedStatusRow(
    label: String,
    value: String,
    revealed: Boolean,
    onToggle: () -> Unit,
    isPlaceholder: Boolean = false,
    placeholderValue: String = value
) {
    if (isPlaceholder) {
        StatusRow(label, placeholderValue)
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (revealed) value else maskPreservingFormat(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = "${if (revealed) "隐藏" else "显示"}$label",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

fun maskSensitiveText(
    text: String,
    config: WolConfig,
    ztStatus: ZeroTierManager.ZtStatus,
    macRevealed: Boolean,
    targetRevealed: Boolean,
    sshRevealed: Boolean,
    nodeIdRevealed: Boolean,
    ztIpRevealed: Boolean
): String {
    val secrets = mutableListOf<String>()
    if (!macRevealed) {
        val mac = config.macAddress.trim()
        if (mac.isNotEmpty()) {
            secrets.add(mac)
            secrets.add(mac.replace('-', ':'))
            secrets.add(mac.replace(':', '-'))
            secrets.add(mac.replace(":", "").replace("-", ""))
        }
    }
    if (!targetRevealed) {
        secrets.add(config.broadcastAddress.trim())
    }
    if (!sshRevealed) {
        secrets.add(config.sshHost.trim())
        secrets.add(config.ztSshHost.trim())
    }
    if (!nodeIdRevealed) {
        secrets.add(ztStatus.nodeIdHex())
    }
    if (!ztIpRevealed) {
        secrets.add(ztStatus.assignedIp ?: "")
    }
    secrets.add(config.pcIp.trim())
    secrets.add(config.ztNetworkId.trim())

    var masked = text
    secrets
        .map { it.trim() }
        .filter { it.length >= 3 }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .sortedByDescending { it.length }
        .forEach { secret ->
            masked = masked.replace(secret, maskPreservingFormat(secret), ignoreCase = true)
        }
    return masked
}

fun statusText(status: PingCheck.Status?): String = when (status) {
    PingCheck.Status.ONLINE -> "在线"
    PingCheck.Status.OFFLINE -> "离线"
    PingCheck.Status.UNKNOWN, null -> "未知"
}

@Composable
fun SettingsScreen(
    config: WolConfig,
    ztStatus: ZeroTierManager.ZtStatus,
    ztDiagLog: List<ZeroTierManager.ZtLogEntry>,
    onSave: (WolConfig) -> Unit,
    onToggleDiagLog: (Boolean) -> Unit,
    onReconnectZt: () -> Unit,
    onClearDiagLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var deviceName by remember(config) { mutableStateOf(config.deviceName) }
    var mac by remember(config) { mutableStateOf(config.macAddress) }
    var broadcast by remember(config) { mutableStateOf(config.broadcastAddress) }
    var port by remember(config) { mutableStateOf(config.wolPort.toString()) }
    var pcIp by remember(config) { mutableStateOf(config.pcIp) }
    var sshHost by remember(config) { mutableStateOf(config.sshHost) }
    var sshPort by remember(config) { mutableStateOf(config.sshPort.toString()) }
    var sshUser by remember(config) { mutableStateOf(config.sshUser) }
    var sshPassword by remember(config) { mutableStateOf(config.sshPassword) }
    var sshCmd by remember(config) { mutableStateOf(config.sshCommandTemplate) }
    var ztEnabled by remember(config) { mutableStateOf(config.ztEnabled) }
    var ztNetworkId by remember(config) { mutableStateOf(config.ztNetworkId) }
    var ztSshHost by remember(config) { mutableStateOf(config.ztSshHost) }
    var diagLogEnabled by remember(config) { mutableStateOf(config.diagLogEnabled) }

    var passwordVisible by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    var copiedHint by remember { mutableStateOf(false) }

    val macError = mac.isNotBlank() && !WolConfig.isValidMac(mac)
    val ztIdError = ztNetworkId.isNotBlank() && !WolConfig.isValidZtNetworkId(ztNetworkId)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("要唤醒的电脑", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("设备名") },
            singleLine = true
        )

        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("MAC 地址") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            supportingText = {
                if (macError) {
                    Text("格式不对，应为 AA:BB:CC:DD:EE:FF")
                } else {
                    Text("电脑有线网卡的 MAC，可在路由器 DHCP 列表里查")
                }
            },
            isError = macError,
            singleLine = true
        )

        OutlinedTextField(
            value = broadcast,
            onValueChange = { broadcast = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("魔法包目标地址") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        OutlinedTextField(
            value = port,
            onValueChange = { value -> port = value.filter { it.isDigit() } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("魔法包端口") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        OutlinedTextField(
            value = pcIp,
            onValueChange = { pcIp = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("电脑内网 IP（用于检测在线状态）") },
            placeholder = { Text("如 192.168.1.100") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))
        Text("路由器 SSH（远程唤醒用）", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = sshHost,
            onValueChange = { sshHost = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("路由器 SSH 地址") },
            placeholder = { Text("如 192.168.1.1") },
            singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = sshPort,
                onValueChange = { value -> sshPort = value.filter { it.isDigit() } },
                modifier = Modifier.weight(1f),
                label = { Text("SSH 端口") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                value = sshUser,
                onValueChange = { sshUser = it },
                modifier = Modifier.weight(1f),
                label = { Text("用户名") },
                singleLine = true
            )
        }

        OutlinedTextField(
            value = sshPassword,
            onValueChange = { sshPassword = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("SSH 密码") },
            placeholder = { Text("路由器的登录密码") },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                    )
                }
            },
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true
        )

        OutlinedTextField(
            value = sshCmd,
            onValueChange = { sshCmd = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("SSH 唤醒命令模板") },
            supportingText = { Text("{MAC} 会自动替换成上面填的 MAC 地址") },
            minLines = 1
        )

        Spacer(Modifier.height(8.dp))
        Text("内置 ZeroTier（libzt）", style = MaterialTheme.typography.titleMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("用 App 内置的 ZeroTier 连回家", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = ztEnabled,
                onCheckedChange = { ztEnabled = it }
            )
        }

        OutlinedTextField(
            value = ztNetworkId,
            onValueChange = { ztNetworkId = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ZeroTier 网络 ID") },
            placeholder = { Text("16 位十六进制，如 0123456789abcdef") },
            supportingText = {
                if (ztIdError) {
                    Text("格式不对，应为 16 位十六进制")
                } else {
                    Text("在 my.zerotier.com 的网络页面里查看")
                }
            },
            isError = ztIdError,
            singleLine = true
        )

        OutlinedTextField(
            value = ztSshHost,
            onValueChange = { ztSshHost = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("路由器 ZeroTier IP") },
            placeholder = { Text("如 192.168.192.x") },
            supportingText = {
                Text(
                    "内置 ZeroTier 唤醒时连这个地址；在 my.zerotier.com 里查路由器那台设备的 IP。" +
                        "libzt 不走 Central 的内网路由，所以这里不能填 192.168.1.1 这类路由器内网 IP"
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        if (ztEnabled) {
            Text(
                text = "本机节点 Node ID：${ztStatus.nodeIdHex().ifBlank { "还未启动，保存后会自动连接" }}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "ZeroTier IP：${ztStatus.assignedIp ?: "未分配"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "这是 App 内置的节点，和 ZeroTier One 是两个不同的节点、不同的 ZeroTier IP。" +
                    "首次使用请去 my.zerotier.com 授权上面这个 Node ID；" +
                    "连不上时 App 会自动回落到系统网络（ZeroTier One）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "关闭时，唤醒走系统网络，需要另外打开 ZeroTier One。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (ztEnabled) {
            Spacer(Modifier.height(8.dp))
            Text("详细诊断日志", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("记录诊断日志", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "关闭后不再记录新的诊断日志（已有记录仍可复制/清空）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = diagLogEnabled,
                    onCheckedChange = { enabled ->
                        diagLogEnabled = enabled
                        onToggleDiagLog(enabled)
                    }
                )
            }

            Text(
                text = "最新记录在最上面；点“复制日志”可复制全部日志。日志保存在本机，不含 SSH 密码与 MAC，复制时会自动脱敏 IP、主机地址与 ZeroTier ID。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (ztDiagLog.isEmpty()) {
                        Text(
                            text = "暂无记录。点唤醒或“重新连接”后，这里会显示详细过程。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        ztDiagLog.asReversed().take(200).forEach { entry ->
                            Text(
                                text = "${entry.time}  ${entry.text}",
                                modifier = Modifier.padding(vertical = 2.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        copiedHint = false
                        onReconnectZt()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("重新连接")
                }
                OutlinedButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(ZeroTierManager.exportDiagLog(config)))
                        copiedHint = true
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("复制日志")
                }
            }

            OutlinedButton(
                onClick = {
                    copiedHint = false
                    onClearDiagLog()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清空诊断")
            }

            if (copiedHint) {
                Text(
                    text = "日志已脱敏复制，可直接粘贴发送。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                onSave(
                    WolConfig(
                        deviceName = deviceName.ifBlank { "家里电脑" },
                        macAddress = mac.trim(),
                        broadcastAddress = broadcast.trim().ifBlank { "192.168.1.255" },
                        wolPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 9,
                        pcIp = pcIp.trim(),
                        sshHost = sshHost.trim(),
                        sshPort = sshPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 22,
                        sshUser = sshUser.ifBlank { "root" },
                        sshPassword = sshPassword,
                        sshCommandTemplate = sshCmd.ifBlank { "etherwake -i br-lan {MAC}" },
                        ztEnabled = ztEnabled,
                        ztNetworkId = ztNetworkId.trim(),
                        ztSshHost = ztSshHost.trim(),
                        diagLogEnabled = diagLogEnabled
                    )
                )
                savedHint = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存设置")
        }

        if (savedHint) {
            Text(
                text = "已保存，返回首页就可以一键唤醒了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = "所有设置只保存在本机，不会上传到任何地方。\n内置 ZeroTier 由 libzt 提供（原 BSL 1.1，已于 2026-01-01 自动转为 Apache 2.0）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
    }
}
