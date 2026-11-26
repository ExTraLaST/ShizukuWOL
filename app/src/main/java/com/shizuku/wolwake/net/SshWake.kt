package com.shizuku.wolwake.net

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.shizuku.wolwake.data.WolConfig
import java.io.ByteArrayOutputStream
import java.net.InetAddress

object SshWake {
    private const val CONNECT_TIMEOUT_MS = 10000
    private const val COMMAND_TIMEOUT_MS = 15000

    @Volatile
    private var activeSession: Session? = null

    @Volatile
    private var activeChannel: ChannelExec? = null

    data class SshResult(
        val success: Boolean,
        val message: String
    )

    private data class CommandResult(
        val success: Boolean,
        val exitStatus: Int?,
        val closed: Boolean,
        val stdout: String,
        val stderr: String,
        val message: String,
        val totalElapsed: Long,
        val stage: String
    )

    fun cancelActive() {
        runCatching { activeChannel?.disconnect() }
        runCatching { activeSession?.disconnect() }
        ZeroTierSocketFactory.cancelActive()
    }

    private fun elapsedSince(start: Long): Long = System.currentTimeMillis() - start

    private fun sanitizeForLog(text: String, config: WolConfig, maxLength: Int = 1000): String {
        var s = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
        val password = config.sshPassword
        if (password.isNotBlank()) {
            s = s.replace(password, "***")
        }
        val mac = config.macAddress.trim()
        if (mac.isNotEmpty()) {
            s = s
                .replace(mac, "[MAC]", ignoreCase = true)
                .replace(mac.replace('-', ':'), "[MAC]", ignoreCase = true)
                .replace(mac.replace(':', '-'), "[MAC]", ignoreCase = true)
                .replace(mac.replace(":", "").replace("-", ""), "[MAC]", ignoreCase = true)
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        return if (s.length > maxLength) {
            s.take(maxLength) + "…(已截断)"
        } else {
            s
        }
    }

    private class DiagnosticJschLogger(private val config: WolConfig) : Logger {
        override fun isEnabled(level: Int): Boolean = true

        override fun log(level: Int, message: String?) {
            ZeroTierManager.logNote(
                "[JSch ${levelName(level)}] ${sanitizeForLog(message ?: "", config, 1000)}"
            )
        }

        override fun log(level: Int, message: String?, throwable: Throwable?) {
            log(level, message)
            if (throwable != null) {
                ZeroTierManager.logException("[JSch ${levelName(level)}] 异常", throwable)
            }
        }

        private fun levelName(level: Int): String = when (level) {
            Logger.DEBUG -> "DEBUG"
            Logger.INFO -> "INFO"
            Logger.WARN -> "WARN"
            Logger.ERROR -> "ERROR"
            Logger.FATAL -> "FATAL"
            else -> "LEVEL$level"
        }
    }

    fun wake(config: WolConfig): SshResult = wakeInternal(config, useZeroTier = false)

    fun wakeViaZeroTier(config: WolConfig): SshResult {
        val ztHost = config.ztSshHost.trim()
        val fallbackHost = config.sshHost.trim()
        val host = ztHost.ifBlank { fallbackHost }
        ZeroTierManager.logNote(
            "[SSH-ZT] 目标选择：ztSshHost=${ztHost.ifBlank { "（空）" }} " +
                "sshHost=${fallbackHost.ifBlank { "（空）" }} 实际host=${host.ifBlank { "（空）" }}"
        )
        if (host.isEmpty()) {
            ZeroTierManager.logNote("[SSH-ZT] 失败：未填写路由器 ZeroTier IP")
            return SshResult(false, "未填写路由器 ZeroTier IP，请在设置里填写")
        }
        return wakeInternal(config, useZeroTier = true, hostOverride = host)
    }

    fun checkPcViaRouter(config: WolConfig, useZeroTier: Boolean): PingCheck.Status? {
        val ip = config.pcIp.trim()
        if (!WolConfig.isValidIpv4(ip)) {
            ZeroTierManager.logNote("[状态检测] 路由器检测跳过：电脑 IP 不是 IPv4 字面量")
            return null
        }
        val host = if (useZeroTier) {
            config.ztSshHost.trim().ifBlank { config.sshHost.trim() }
        } else {
            config.sshHost.trim()
        }
        if (host.isEmpty()) {
            ZeroTierManager.logNote("[状态检测] 路由器检测跳过：未填写路由器 SSH 地址")
            return null
        }

        val command = "ip neigh show $ip 2>/dev/null; ping -c 1 -W 1 $ip >/dev/null 2>&1; echo WOL_PING_EXIT:$?"
        val r = runCommand(config, useZeroTier, host, command, "状态检测")
        if (!r.closed || r.exitStatus == null) {
            ZeroTierManager.logNote(
                "[状态检测] 路由器检测不可用：route=${if (useZeroTier) "内置 ZeroTier" else "普通网络"} " +
                    "stage=${r.stage} message=${sanitizeForLog(r.message, config, 300)}"
            )
            return null
        }

        val neighOnline = r.stdout.lineSequence().any { line ->
            line.contains(ip) && (
                line.contains("REACHABLE") ||
                    line.contains("STALE") ||
                    line.contains("DELAY") ||
                    line.contains("PROBE")
                )
        }
        val pingOk = r.stdout.contains("WOL_PING_EXIT:0")
        val status = if (neighOnline || pingOk) PingCheck.Status.ONLINE else PingCheck.Status.OFFLINE
        ZeroTierManager.logNote(
            "[状态检测] 路由器检测结果：route=${if (useZeroTier) "内置 ZeroTier" else "普通网络"} " +
                "ip=$ip neighOnline=$neighOnline pingOk=$pingOk result=$status"
        )
        return status
    }

    private fun wakeInternal(
        config: WolConfig,
        useZeroTier: Boolean,
        hostOverride: String? = null
    ): SshResult {
        val host = (hostOverride ?: config.sshHost).trim()
        if (host.isEmpty()) {
            ZeroTierManager.logNote("[SSH] 失败：未填写路由器 SSH 地址")
            return SshResult(false, "未填写路由器 SSH 地址")
        }
        if (!WolConfig.isValidMac(config.macAddress)) {
            ZeroTierManager.logNote("[SSH] 失败：MAC 地址格式不正确")
            return SshResult(false, "MAC 地址格式不正确")
        }

        val r = runCommand(config, useZeroTier, host, config.sshCommand(), "etherwake")
        if (r.success) {
            val detail = if (r.stdout.isNotEmpty()) "：${sanitizeForLog(r.stdout, config, 500)}" else ""
            return SshResult(true, "已执行 etherwake$detail")
        }
        if (!r.closed && r.exitStatus == null && r.stage == "等待命令") {
            val errDetail = if (r.stderr.isNotEmpty()) "：${sanitizeForLog(r.stderr, config, 500)}" else ""
            return SshResult(false, "SSH 失败（等待命令阶段，耗时 ${r.totalElapsed}ms）：命令超时未结束$errDetail")
        }
        if (r.exitStatus != null && r.exitStatus != 0) {
            val errDetail = if (r.stderr.isNotEmpty()) "：${sanitizeForLog(r.stderr, config, 500)}" else ""
            return SshResult(false, "SSH 失败（等待命令阶段，耗时 ${r.totalElapsed}ms）：命令退出码 ${r.exitStatus}$errDetail")
        }
        return SshResult(
            false,
            "SSH 失败（${r.stage}阶段，耗时 ${r.totalElapsed}ms）：${sanitizeForLog(r.message, config, 500)}"
        )
    }

    private fun runCommand(
        config: WolConfig,
        useZeroTier: Boolean,
        host: String,
        command: String,
        label: String
    ): CommandResult {
        val overallStart = System.currentTimeMillis()
        val routeText = if (useZeroTier) "内置 ZeroTier" else "普通网络"
        ZeroTierManager.logNote(
            "[SSH] 开始：route=$routeText host=$host port=${config.sshPort} user=${config.sshUser} " +
                "passwordConfigured=${config.sshPassword.isNotEmpty()} label=$label"
        )

        var session: Session? = null
        var channel: ChannelExec? = null
        var stage = "地址解析"

        try {
            var stageStart = System.currentTimeMillis()
            ZeroTierManager.logNote("[SSH] 地址解析开始：host=$host")
            val resolved = runCatching { InetAddress.getByName(host).hostAddress }
            resolved.onSuccess { ip ->
                ZeroTierManager.logNote(
                    "[SSH] 地址解析成功：host=$host resolved=$ip elapsed=${elapsedSince(stageStart)}ms"
                )
            }.onFailure { err ->
                ZeroTierManager.logException("[SSH] 地址解析失败（继续尝试连接）", err)
            }

            stage = "创建会话"
            stageStart = System.currentTimeMillis()
            val jsch = JSch()
            jsch.setInstanceLogger(DiagnosticJschLogger(config))
            session = jsch.getSession(config.sshUser, host, config.sshPort)
            activeSession = session
            session.setPassword(config.sshPassword)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "password")
            if (useZeroTier) {
                session.setSocketFactory(ZeroTierSocketFactory())
            }
            ZeroTierManager.logNote(
                "[SSH] 会话已创建：route=$routeText host=$host port=${config.sshPort} " +
                    "user=${config.sshUser} elapsed=${elapsedSince(stageStart)}ms"
            )

            stage = "连接"
            stageStart = System.currentTimeMillis()
            val connectMode = if (useZeroTier) {
                "无参 connect（SocketFactory）"
            } else {
                "connect(${CONNECT_TIMEOUT_MS}ms)"
            }
            ZeroTierManager.logNote(
                "[SSH] 开始连接：route=$routeText host=$host port=${config.sshPort} connect方式=$connectMode"
            )
            // ZeroTier 路径必须使用无参 session.connect()，带 timeout 会绕过 SocketFactory
            if (useZeroTier) {
                session.connect()
            } else {
                session.connect(CONNECT_TIMEOUT_MS)
            }
            ZeroTierManager.logNote(
                "[SSH] 连接成功：elapsed=${elapsedSince(stageStart)}ms total=${elapsedSince(overallStart)}ms"
            )
            session.serverVersion?.let { ver ->
                ZeroTierManager.logNote("[SSH] 服务器版本：${sanitizeForLog(ver, config, 200)}")
            }

            stage = "打开通道"
            stageStart = System.currentTimeMillis()
            channel = session.openChannel("exec") as ChannelExec
            activeChannel = channel
            ZeroTierManager.logNote("[SSH] 命令通道已打开：elapsed=${elapsedSince(stageStart)}ms")

            stage = "设置命令"
            stageStart = System.currentTimeMillis()
            channel.setCommand(command)
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()
            channel.outputStream = output
            channel.setErrStream(error)
            ZeroTierManager.logNote(
                "[SSH] 命令已设置：label=$label commandLength=${command.length}（MAC 已隐藏）" +
                    "elapsed=${elapsedSince(stageStart)}ms"
            )

            stage = "连接通道"
            stageStart = System.currentTimeMillis()
            channel.connect(COMMAND_TIMEOUT_MS)
            ZeroTierManager.logNote("[SSH] 通道连接成功：elapsed=${elapsedSince(stageStart)}ms")

            stage = "等待命令"
            stageStart = System.currentTimeMillis()
            val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS.toLong()
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(100L)
            }
            val waitElapsed = elapsedSince(stageStart)
            val closed = channel.isClosed
            val exitStatus = channel.exitStatus
            val outText = output.toString(Charsets.UTF_8.name()).trim()
            val errText = error.toString(Charsets.UTF_8.name()).trim()

            ZeroTierManager.logNote(
                "[SSH] 命令等待结束：closed=$closed exitStatus=$exitStatus elapsed=${waitElapsed}ms " +
                    "stdoutLength=${outText.length} stderrLength=${errText.length} " +
                    "stdout=${sanitizeForLog(outText, config, 500)} " +
                    "stderr=${sanitizeForLog(errText, config, 500)}"
            )

            val totalElapsed = elapsedSince(overallStart)
            return if (!closed) {
                ZeroTierManager.logNote("[SSH] 命令超时未结束：total=${totalElapsed}ms")
                CommandResult(false, null, false, outText, errText, "命令超时未结束", totalElapsed, "等待命令")
            } else if (exitStatus == 0) {
                ZeroTierManager.logNote("[SSH] 成功：total=${totalElapsed}ms")
                CommandResult(true, exitStatus, true, outText, errText, "成功", totalElapsed, "等待命令")
            } else {
                ZeroTierManager.logNote("[SSH] 命令非零退出：exitStatus=$exitStatus total=${totalElapsed}ms")
                CommandResult(false, exitStatus, true, outText, errText, "命令退出码 $exitStatus", totalElapsed, "等待命令")
            }
        } catch (e: Exception) {
            val totalElapsed = elapsedSince(overallStart)
            ZeroTierManager.logException("[SSH] 失败（${stage}阶段，耗时 ${totalElapsed}ms）", e)
            val msg = e.message ?: e.javaClass.simpleName
            return CommandResult(false, null, false, "", "", msg, totalElapsed, stage)
        } finally {
            try {
                channel?.disconnect()
            } catch (_: Exception) {
            }
            try {
                session?.disconnect()
            } catch (_: Exception) {
            }
            if (activeChannel === channel) {
                activeChannel = null
            }
            if (activeSession === session) {
                activeSession = null
            }
        }
    }
}
