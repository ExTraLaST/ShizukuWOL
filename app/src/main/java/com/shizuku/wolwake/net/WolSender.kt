package com.shizuku.wolwake.net

import com.shizuku.wolwake.data.WolConfig
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WolSender {
    fun buildMagicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "MAC 必须是 6 字节" }
        val packet = ByteArray(102)
        for (i in 0 until 6) {
            packet[i] = 0xFF.toByte()
        }
        for (i in 0 until 16) {
            mac.copyInto(packet, destinationOffset = 6 + i * 6)
        }
        return packet
    }

    fun send(config: WolConfig, repeat: Int = 3): Result<Int> {
        val macBytes = WolConfig.parseMac(config.macAddress)
            ?: return Result.failure(IllegalArgumentException("MAC 地址格式不正确"))
        val packetData = buildMagicPacket(macBytes)
        return try {
            val address = InetAddress.getByName(config.broadcastAddress.trim())
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (i in 0 until repeat) {
                    val packet = DatagramPacket(packetData, packetData.size, address, config.wolPort)
                    socket.send(packet)
                    if (i < repeat - 1) {
                        Thread.sleep(100L)
                    }
                }
            }
            Result.success(repeat)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
