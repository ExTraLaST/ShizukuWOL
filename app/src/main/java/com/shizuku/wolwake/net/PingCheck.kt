package com.shizuku.wolwake.net

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object PingCheck {
    private const val TCP_PROBE_TIMEOUT_MS = 800
    private val TCP_PROBE_PORTS = listOf(445, 3389, 135)

    enum class Status {
        ONLINE,
        OFFLINE,
        UNKNOWN
    }

    fun check(host: String, timeoutMs: Int = 2000): Status {
        val target = host.trim()
        if (target.isEmpty()) return Status.UNKNOWN
        return try {
            val address = InetAddress.getByName(target)
            try {
                if (address.isReachable(timeoutMs) ||
                    pingViaCommand(target, timeoutMs) == Status.ONLINE ||
                    tcpProbe(address)
                ) {
                    Status.ONLINE
                } else {
                    Status.OFFLINE
                }
            } catch (_: Exception) {
                Status.UNKNOWN
            }
        } catch (_: Exception) {
            Status.UNKNOWN
        }
    }

    private fun tcpProbe(address: InetAddress): Boolean {
        for (port in TCP_PROBE_PORTS) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, port), TCP_PROBE_TIMEOUT_MS)
                    return true
                }
            } catch (_: ConnectException) {
                return true
            } catch (_: SocketTimeoutException) {
            } catch (_: Exception) {
            }
        }
        return false
    }

    private fun pingViaCommand(host: String, timeoutMs: Int): Status {
        return try {
            val seconds = (timeoutMs / 1000).coerceAtLeast(1)
            val process = ProcessBuilder("ping", "-c", "1", "-W", seconds.toString(), host)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutMs + 2000L, TimeUnit.MILLISECONDS)
            if (finished) {
                if (process.exitValue() == 0) Status.ONLINE else Status.OFFLINE
            } else {
                process.destroy()
                Status.UNKNOWN
            }
        } catch (_: Exception) {
            Status.UNKNOWN
        }
    }
}
