package com.shizuku.wolwake.data

import java.math.BigInteger

data class WolConfig(
    val deviceName: String = "家里电脑",
    val macAddress: String = "",
    val broadcastAddress: String = "192.168.6.255",
    val wolPort: Int = 9,
    val pcIp: String = "192.168.6.105",
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    val sshCommandTemplate: String = "etherwake -i br-lan {MAC}",
    val ztEnabled: Boolean = false,
    val ztNetworkId: String = "",
    val ztSshHost: String = "",
    val diagLogEnabled: Boolean = false
) {
    fun sshCommand(): String = sshCommandTemplate.replace("{MAC}", macAddress.trim())

    companion object {
        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        private val ZT_NETWORK_ID_REGEX = Regex("^[0-9a-fA-F]{16}$")

        fun isValidMac(mac: String): Boolean = MAC_REGEX.matches(mac.trim())

        fun isValidZtNetworkId(id: String): Boolean = ZT_NETWORK_ID_REGEX.matches(id.trim())

        fun parseZtNetworkId(id: String): Long? {
            if (!isValidZtNetworkId(id)) return null
            return try {
                BigInteger(id.trim(), 16).toLong()
            } catch (_: Exception) {
                null
            }
        }

        fun parseMac(mac: String): ByteArray? {
            if (!isValidMac(mac)) return null
            return mac.trim()
                .split(":", "-")
                .map { it.toInt(16).toByte() }
                .toByteArray()
        }

        fun isValidIpv4(ip: String): Boolean {
            val parts = ip.trim().split(".")
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.all { it.isDigit() } &&
                    (part.toIntOrNull()?.let { it in 0..255 } == true)
            }
        }
    }
}
