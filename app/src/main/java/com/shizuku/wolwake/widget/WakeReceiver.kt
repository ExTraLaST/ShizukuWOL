package com.shizuku.wolwake.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.shizuku.wolwake.data.SettingsStore
import com.shizuku.wolwake.data.WolConfig
import com.shizuku.wolwake.net.SshWake
import com.shizuku.wolwake.net.WolSender
import com.shizuku.wolwake.net.ZeroTierManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WAKE) return
        val appContext = context.applicationContext
        val pending = goAsync()
        val timedOut = AtomicBoolean(false)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val completed = withTimeoutOrNull(WIDGET_BUDGET_MS) {
                    ZeroTierManager.initDiagnostics(appContext)
                    ZeroTierManager.logSection("小部件唤醒")
                    val config = SettingsStore(appContext).load()
                    ZeroTierManager.updateRedactionSecrets(config)
                    ZeroTierManager.logNote(
                        "小部件配置摘要：ztEnabled=${config.ztEnabled} " +
                            "networkIdValid=${WolConfig.parseZtNetworkId(config.ztNetworkId) != null} " +
                            "ztSshHost=${config.ztSshHost.trim().ifBlank { "（空）" }} " +
                            "sshHost=${config.sshHost.trim().ifBlank { "（空）" }} " +
                            "passwordConfigured=${config.sshPassword.isNotEmpty()} " +
                            "macValid=${WolConfig.isValidMac(config.macAddress)}"
                    )

                    if (!WolConfig.isValidMac(config.macAddress)) {
                        showToast(appContext, "请先在 App 里填写正确的 MAC 地址")
                        return@withTimeoutOrNull true
                    }

                    val nwid = WolConfig.parseZtNetworkId(config.ztNetworkId)
                    val useZt = config.ztEnabled && nwid != null && ZeroTierManager.isReadyQuick(nwid)
                    ZeroTierManager.logNote("小部件 SSH 分支：useZeroTier=$useZt")

                    val sshResult = if (useZt) {
                        SshWake.wakeViaZeroTier(
                            config = config,
                            cancelled = { timedOut.get() },
                            registerAsActiveWake = false,
                            ztConnectTimeoutMs = WIDGET_SSH_CONNECT_TIMEOUT_MS,
                            ztMaxAttempts = 1,
                            commandTimeoutMs = WIDGET_SSH_CMD_TIMEOUT_MS
                        )
                    } else {
                        SshWake.wake(
                            config = config,
                            cancelled = { timedOut.get() },
                            registerAsActiveWake = false,
                            connectTimeoutMs = WIDGET_SSH_CONNECT_TIMEOUT_MS,
                            commandTimeoutMs = WIDGET_SSH_CMD_TIMEOUT_MS
                        )
                    }
                    ZeroTierManager.logNote(
                        "小部件 SSH 结果：success=${sshResult.success} message=${sshResult.message}"
                    )

                    val wolResult = WolSender.send(config)
                    if (wolResult.isSuccess) {
                        ZeroTierManager.logNote("小部件魔法包发送成功：${wolResult.getOrNull()} 次")
                    } else {
                        wolResult.exceptionOrNull()?.let {
                            ZeroTierManager.logException("小部件魔法包发送失败", it)
                        }
                    }

                    val ok = sshResult.success || wolResult.isSuccess
                    showToast(appContext, if (ok) "唤醒指令已发送" else "唤醒失败，请打开 App 查看详情")
                    true
                }
                if (completed == null) {
                    timedOut.set(true)
                    ZeroTierManager.logNote("小部件唤醒超时，避免触发系统 Broadcast ANR")
                    showToast(appContext, "唤醒超时，请打开 App 查看详情")
                }
            } catch (t: Throwable) {
                ZeroTierManager.logException("小部件唤醒异常", t)
                showToast(appContext, "唤醒失败：${t.message ?: "未知错误"}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun showToast(context: Context, text: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_WAKE = "com.shizuku.wolwake.ACTION_WAKE_FROM_WIDGET"
        private const val WIDGET_BUDGET_MS = 8500L
        private const val WIDGET_SSH_CONNECT_TIMEOUT_MS = 4000
        private const val WIDGET_SSH_CMD_TIMEOUT_MS = 3000
    }
}
