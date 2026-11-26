package com.shizuku.wolwake

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shizuku.wolwake.data.SettingsStore
import com.shizuku.wolwake.data.WolConfig
import com.shizuku.wolwake.net.PingCheck
import com.shizuku.wolwake.net.SshWake
import com.shizuku.wolwake.net.WolSender
import com.shizuku.wolwake.net.ZeroTierManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ResultLine(
    val label: String,
    val success: Boolean,
    val detail: String
)

data class WakeUiState(
    val isWaking: Boolean = false,
    val isChecking: Boolean = false,
    val pcStatus: PingCheck.Status? = null,
    val resultLines: List<ResultLine> = emptyList(),
    val lastActionTime: String? = null
)

class WakeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val store = SettingsStore(application)
    val config: StateFlow<WolConfig> = store.config

    private val _uiState = MutableStateFlow(WakeUiState())
    val uiState: StateFlow<WakeUiState> = _uiState.asStateFlow()

    private val _ztStatus = MutableStateFlow(ZeroTierManager.currentStatus())
    val ztStatus: StateFlow<ZeroTierManager.ZtStatus> = _ztStatus.asStateFlow()

    private val _ztDiagLog = MutableStateFlow<List<ZeroTierManager.ZtLogEntry>>(emptyList())
    val ztDiagLog: StateFlow<List<ZeroTierManager.ZtLogEntry>> = _ztDiagLog.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var wakeJob: Job? = null

    init {
        ZeroTierManager.initDiagnostics(app)
        viewModelScope.launch {
            ZeroTierManager.statusFlow.collect { _ztStatus.value = it }
        }
        viewModelScope.launch {
            ZeroTierManager.diagLogFlow.collect { _ztDiagLog.value = it }
        }
        val cfg = store.load()
        val nwid = WolConfig.parseZtNetworkId(cfg.ztNetworkId)
        if (cfg.ztEnabled && nwid != null) {
            viewModelScope.launch(Dispatchers.IO) {
                _ztStatus.value = ZeroTierManager.ensureReady(app, nwid)
            }
        }
    }

    fun refreshOnResume() {
        val cfg = config.value
        val nwid = WolConfig.parseZtNetworkId(cfg.ztNetworkId) ?: return
        if (cfg.ztEnabled) {
            viewModelScope.launch(Dispatchers.IO) {
                _ztStatus.value = if (!ZeroTierManager.isRunning()) {
                    ZeroTierManager.ensureReady(app, nwid)
                } else {
                    ZeroTierManager.refreshStatus()
                }
            }
        }
    }

    fun reconnectZt() {
        val cfg = config.value
        val nwid = WolConfig.parseZtNetworkId(cfg.ztNetworkId) ?: return
        if (cfg.ztEnabled) {
            viewModelScope.launch(Dispatchers.IO) {
                ZeroTierManager.logSection("重新连接 ZeroTier")
                if (ZeroTierManager.isReadyFor(nwid)) {
                    ZeroTierManager.logNote("重新连接：节点已在目标网络就绪，跳过 leave/join，仅刷新状态")
                    _ztStatus.value = ZeroTierManager.refreshStatus()
                    _ztStatus.value = ZeroTierManager.ensureReady(app, nwid)
                } else {
                    ZeroTierManager.stop()
                    _ztStatus.value = ZeroTierManager.currentStatus()
                    _ztStatus.value = ZeroTierManager.ensureReady(app, nwid)
                }
            }
        }
    }

    fun saveConfig(config: WolConfig) {
        store.save(config)
        ZeroTierManager.setDiagLogEnabled(config.diagLogEnabled)
        applyZtEnabled(config)
    }

    fun setDiagLogEnabled(enabled: Boolean) {
        val newConfig = config.value.copy(diagLogEnabled = enabled)
        store.save(newConfig)
        ZeroTierManager.setDiagLogEnabled(enabled)
    }

    fun setZtEnabled(enabled: Boolean) {
        val newConfig = config.value.copy(ztEnabled = enabled)
        store.save(newConfig)
        applyZtEnabled(newConfig)
    }

    private fun applyZtEnabled(config: WolConfig) {
        val nwid = WolConfig.parseZtNetworkId(config.ztNetworkId)
        if (config.ztEnabled && nwid != null) {
            viewModelScope.launch(Dispatchers.IO) {
                _ztStatus.value = ZeroTierManager.ensureReady(app, nwid)
            }
        } else if (!config.ztEnabled) {
            viewModelScope.launch(Dispatchers.IO) {
                ZeroTierManager.stop()
                _ztStatus.value = ZeroTierManager.currentStatus()
            }
        }
    }

    private fun now(): String = timeFormat.format(Date())

    private fun logWakeStart(cfg: WolConfig, action: String) {
        ZeroTierManager.logSection("唤醒开始（$action）")
        val nwid = WolConfig.parseZtNetworkId(cfg.ztNetworkId)
        ZeroTierManager.logNote(
            "配置摘要：ztEnabled=${cfg.ztEnabled} " +
                "networkIdPresent=${cfg.ztNetworkId.isNotBlank()} " +
                "networkIdValid=${nwid != null} " +
                "networkId=${cfg.ztNetworkId.trim().ifBlank { "（空）" }} " +
                "ztSshHost=${cfg.ztSshHost.trim().ifBlank { "（空）" }} " +
                "sshHost=${cfg.sshHost.trim().ifBlank { "（空）" }} " +
                "sshPort=${cfg.sshPort} " +
                "sshUser=${cfg.sshUser} " +
                "passwordConfigured=${cfg.sshPassword.isNotEmpty()} " +
                "macValid=${WolConfig.isValidMac(cfg.macAddress)}"
        )
    }

    private suspend fun sshWakeStep(cfg: WolConfig): ResultLine {
        val ctx = coroutineContext
        val nwid = WolConfig.parseZtNetworkId(cfg.ztNetworkId)
        if (!cfg.ztEnabled || nwid == null) {
            if (cfg.ztEnabled) {
                ZeroTierManager.logNote("SSH 分支：内置 ZeroTier 已启用但网络 ID 无效或为空，走普通 SSH")
            } else {
                ZeroTierManager.logNote("SSH 分支：内置 ZeroTier 未启用，走普通 SSH")
            }
            val r = withContext(Dispatchers.IO) { SshWake.wake(cfg) }
            ctx.ensureActive()
            ZeroTierManager.logNote("普通 SSH 结果：success=${r.success} message=${r.message}")
            return ResultLine("路由器 SSH", r.success, r.message)
        }

        ZeroTierManager.logNote("SSH 分支：内置 ZeroTier 已启用且网络 ID 有效，调用 ensureReady")
        val zt = withContext(Dispatchers.IO) {
            ZeroTierManager.ensureReady(app, nwid, cancelled = { !ctx.isActive })
        }
        ctx.ensureActive()
        _ztStatus.value = zt
        ZeroTierManager.logNote(
            "ensureReady 结果：nodeId=${zt.nodeIdHex().ifBlank { "0" }} " +
                "online=${zt.online} networkReady=${zt.networkReady} " +
                "assignedIp=${zt.assignedIp ?: "未分配"} lastError=${zt.lastError ?: "无"}"
        )

        if (!zt.networkReady) {
            ZeroTierManager.logNote(
                "SSH 分支：内置 ZeroTier 未就绪（${zt.lastError ?: "未知原因"}），回落普通 SSH"
            )
            val fb = withContext(Dispatchers.IO) { SshWake.wake(cfg) }
            ctx.ensureActive()
            ZeroTierManager.logNote("回落普通 SSH 结果：success=${fb.success} message=${fb.message}")
            return ResultLine(
                "路由器 SSH（系统网络·回落）",
                fb.success,
                "内置 ZeroTier 未就绪（${zt.lastError ?: "未知原因"}），已回落：${fb.message}"
            )
        }

        ZeroTierManager.logSnapshot("内置 SSH 前", nwid)
        ZeroTierManager.logNote(
            "SSH 分支：节点就绪，尝试内置 ZeroTier SSH（ztSshHost=${
                cfg.ztSshHost.trim().ifBlank { "（空，将回落 sshHost）" }
            }）"
        )
        val r = withContext(Dispatchers.IO) { SshWake.wakeViaZeroTier(cfg) }
        ctx.ensureActive()
        ZeroTierManager.logNote("内置 ZeroTier SSH 结果：success=${r.success} message=${r.message}")
        if (r.success) {
            return ResultLine("ZeroTier SSH（App 内置）", true, r.message)
        }

        ZeroTierManager.logNote("SSH 分支：内置 ZeroTier SSH 失败，决定回落普通 SSH")
        val fb = withContext(Dispatchers.IO) { SshWake.wake(cfg) }
        ctx.ensureActive()
        ZeroTierManager.logNote("回落普通 SSH 结果：success=${fb.success} message=${fb.message}")
        return ResultLine(
            "路由器 SSH（系统网络·回落）",
            fb.success,
            "内置 ZeroTier 路失败（${r.message}），已回落：${fb.message}"
        )
    }

    fun wakeAll() {
        val cfg = config.value
        logWakeStart(cfg, "一键唤醒")
        if (!WolConfig.isValidMac(cfg.macAddress)) {
            ZeroTierManager.logNote("唤醒未执行：MAC 地址无效")
            _uiState.value = _uiState.value.copy(
                resultLines = listOf(ResultLine("配置检查", false, "请先在设置里填写正确的 MAC 地址")),
                lastActionTime = now()
            )
            return
        }
        if (_uiState.value.isWaking) return
        _uiState.value = _uiState.value.copy(
            isWaking = true,
            resultLines = emptyList()
        )
        wakeJob = viewModelScope.launch {
            val lines = mutableListOf<ResultLine>()
            lines.add(sshWakeStep(cfg))
            coroutineContext.ensureActive()

            val wolResult = withContext(Dispatchers.IO) { WolSender.send(cfg) }
            if (wolResult.isSuccess) {
                val count = wolResult.getOrNull()
                ZeroTierManager.logNote("魔法包发送成功：$count 次")
                lines.add(ResultLine("魔法包", true, "已发送 $count 次"))
            } else {
                val err = wolResult.exceptionOrNull()
                if (err != null) {
                    ZeroTierManager.logException("魔法包发送失败", err)
                } else {
                    ZeroTierManager.logNote("魔法包发送失败：未知错误")
                }
                lines.add(ResultLine("魔法包", false, "发送失败：${err?.message ?: "未知错误"}"))
            }

            _uiState.value = _uiState.value.copy(
                isWaking = false,
                resultLines = lines,
                lastActionTime = now()
            )
        }
    }

    fun wakeByPacketOnly() {
        val cfg = config.value
        logWakeStart(cfg, "只发魔法包")
        if (!WolConfig.isValidMac(cfg.macAddress)) {
            ZeroTierManager.logNote("唤醒未执行：MAC 地址无效")
            _uiState.value = _uiState.value.copy(
                resultLines = listOf(ResultLine("配置检查", false, "请先在设置里填写正确的 MAC 地址")),
                lastActionTime = now()
            )
            return
        }
        if (_uiState.value.isWaking) return
        _uiState.value = _uiState.value.copy(
            isWaking = true,
            resultLines = emptyList()
        )
        wakeJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { WolSender.send(cfg) }
            val line = if (result.isSuccess) {
                val count = result.getOrNull()
                ZeroTierManager.logNote("魔法包发送成功：$count 次")
                ResultLine("魔法包", true, "已发送 $count 次")
            } else {
                val err = result.exceptionOrNull()
                if (err != null) {
                    ZeroTierManager.logException("魔法包发送失败", err)
                } else {
                    ZeroTierManager.logNote("魔法包发送失败：未知错误")
                }
                ResultLine("魔法包", false, "发送失败：${err?.message ?: "未知错误"}")
            }
            _uiState.value = _uiState.value.copy(
                isWaking = false,
                resultLines = listOf(line),
                lastActionTime = now()
            )
        }
    }

    fun wakeBySshOnly() {
        val cfg = config.value
        logWakeStart(cfg, "只用 SSH 唤醒")
        if (!WolConfig.isValidMac(cfg.macAddress)) {
            ZeroTierManager.logNote("唤醒未执行：MAC 地址无效")
            _uiState.value = _uiState.value.copy(
                resultLines = listOf(ResultLine("配置检查", false, "请先在设置里填写正确的 MAC 地址")),
                lastActionTime = now()
            )
            return
        }
        if (_uiState.value.isWaking) return
        _uiState.value = _uiState.value.copy(
            isWaking = true,
            resultLines = emptyList()
        )
        wakeJob = viewModelScope.launch {
            val line = sshWakeStep(cfg)
            _uiState.value = _uiState.value.copy(
                isWaking = false,
                resultLines = listOf(line),
                lastActionTime = now()
            )
        }
    }

    fun cancelWake() {
        val job = wakeJob
        if ((job != null && job.isActive) || _uiState.value.isWaking) {
            job?.cancel()
            SshWake.cancelActive()
            ZeroTierManager.logNote("唤醒已停止（用户点按停止）")
            _uiState.value = _uiState.value.copy(
                isWaking = false,
                resultLines = listOf(ResultLine("唤醒", false, "已停止")),
                lastActionTime = now()
            )
        }
    }

    private suspend fun routerCheckStep(cfg: WolConfig): PingCheck.Status? {
        val nwid = WolConfig.parseZtNetworkId(cfg.ztNetworkId)
        if (cfg.ztEnabled && nwid != null) {
            val zt = withContext(Dispatchers.IO) { ZeroTierManager.ensureReady(app, nwid) }
            _ztStatus.value = zt
            if (zt.networkReady) {
                val r = withContext(Dispatchers.IO) {
                    SshWake.checkPcViaRouter(cfg, useZeroTier = true)
                }
                if (r != null) return r
                ZeroTierManager.logNote("[状态检测] 内置 ZeroTier 路由器检测不可用，试普通 SSH")
            } else {
                ZeroTierManager.logNote("[状态检测] 内置 ZeroTier 未就绪，试普通 SSH 路由器检测")
            }
        }
        if (cfg.sshHost.trim().isEmpty()) return null
        return withContext(Dispatchers.IO) {
            SshWake.checkPcViaRouter(cfg, useZeroTier = false)
        }
    }

    fun checkStatus() {
        val cfg = config.value
        if (cfg.pcIp.trim().isEmpty()) {
            _uiState.value = _uiState.value.copy(
                pcStatus = PingCheck.Status.UNKNOWN,
                resultLines = listOf(ResultLine("状态检测", false, "请先在设置里填写电脑内网 IP")),
                lastActionTime = now()
            )
            return
        }
        if (!WolConfig.isValidIpv4(cfg.pcIp)) {
            _uiState.value = _uiState.value.copy(
                pcStatus = PingCheck.Status.UNKNOWN,
                resultLines = listOf(
                    ResultLine("状态检测", false, "电脑 IP 格式不正确，请填写 IPv4 地址（如 192.168.6.105）")
                ),
                lastActionTime = now()
            )
            return
        }
        if (_uiState.value.isChecking) return
        _uiState.value = _uiState.value.copy(isChecking = true)
        viewModelScope.launch {
            try {
                val routerStatus = routerCheckStep(cfg)
                val status = if (routerStatus != null) {
                    ZeroTierManager.logNote("[状态检测] 方法=路由器检测 result=$routerStatus")
                    routerStatus
                } else {
                    val local = withContext(Dispatchers.IO) { PingCheck.check(cfg.pcIp) }
                    ZeroTierManager.logNote("[状态检测] 方法=本地探测 result=$local")
                    local
                }
                _uiState.value = _uiState.value.copy(
                    isChecking = false,
                    pcStatus = status,
                    lastActionTime = now()
                )
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}
