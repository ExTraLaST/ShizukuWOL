package com.shizuku.wolwake.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<WolConfig> = _config.asStateFlow()

    fun load(): WolConfig = WolConfig(
        deviceName = prefs.getString(KEY_DEVICE_NAME, "家里电脑") ?: "家里电脑",
        macAddress = prefs.getString(KEY_MAC, "") ?: "",
        broadcastAddress = prefs.getString(KEY_BROADCAST, "192.168.6.255") ?: "192.168.6.255",
        wolPort = prefs.getInt(KEY_PORT, 9),
        pcIp = prefs.getString(KEY_PC_IP, "192.168.6.105") ?: "192.168.6.105",
        sshHost = prefs.getString(KEY_SSH_HOST, "") ?: "",
        sshPort = prefs.getInt(KEY_SSH_PORT, 22),
        sshUser = prefs.getString(KEY_SSH_USER, "root") ?: "root",
        sshPassword = prefs.getString(KEY_SSH_PASSWORD, "") ?: "",
        sshCommandTemplate = prefs.getString(KEY_SSH_CMD, "etherwake -i br-lan {MAC}")
            ?: "etherwake -i br-lan {MAC}",
        ztEnabled = prefs.getBoolean(KEY_ZT_ENABLED, false),
        ztNetworkId = prefs.getString(KEY_ZT_NETWORK_ID, "") ?: "",
        ztSshHost = prefs.getString(KEY_ZT_SSH_HOST, "") ?: "",
        diagLogEnabled = prefs.getBoolean(KEY_DIAG_LOG_ENABLED, false)
    )

    fun save(config: WolConfig) {
        prefs.edit()
            .putString(KEY_DEVICE_NAME, config.deviceName)
            .putString(KEY_MAC, config.macAddress)
            .putString(KEY_BROADCAST, config.broadcastAddress)
            .putInt(KEY_PORT, config.wolPort)
            .putString(KEY_PC_IP, config.pcIp)
            .putString(KEY_SSH_HOST, config.sshHost)
            .putInt(KEY_SSH_PORT, config.sshPort)
            .putString(KEY_SSH_USER, config.sshUser)
            .putString(KEY_SSH_PASSWORD, config.sshPassword)
            .putString(KEY_SSH_CMD, config.sshCommandTemplate)
            .putBoolean(KEY_ZT_ENABLED, config.ztEnabled)
            .putString(KEY_ZT_NETWORK_ID, config.ztNetworkId)
            .putString(KEY_ZT_SSH_HOST, config.ztSshHost)
            .putBoolean(KEY_DIAG_LOG_ENABLED, config.diagLogEnabled)
            .apply()
        _config.value = config
    }

    companion object {
        const val PREFS_NAME = "wolwake_prefs"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_MAC = "mac"
        const val KEY_BROADCAST = "broadcast"
        const val KEY_PORT = "port"
        const val KEY_PC_IP = "pc_ip"
        const val KEY_SSH_HOST = "ssh_host"
        const val KEY_SSH_PORT = "ssh_port"
        const val KEY_SSH_USER = "ssh_user"
        const val KEY_SSH_PASSWORD = "ssh_password"
        const val KEY_SSH_CMD = "ssh_cmd"
        const val KEY_ZT_ENABLED = "zt_enabled"
        const val KEY_ZT_NETWORK_ID = "zt_network_id"
        const val KEY_ZT_SSH_HOST = "zt_ssh_host"
        const val KEY_DIAG_LOG_ENABLED = "diag_log_enabled"
    }
}
