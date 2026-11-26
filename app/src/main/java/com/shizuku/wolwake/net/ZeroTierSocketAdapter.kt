package com.shizuku.wolwake.net

import com.jcraft.jsch.SocketFactory
import com.zerotier.sockets.ZeroTierDatagramSocket
import com.zerotier.sockets.ZeroTierInputStream
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierOutputStream
import com.zerotier.sockets.ZeroTierSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress

class ZeroTierSocketAdapter(
    private val ztSocket: ZeroTierSocket,
    private val remoteAddr: InetAddress,
    private val remotePort: Int,
    initiallyConnected: Boolean = true
) : Socket() {

    @Volatile
    private var connectedFlag: Boolean = initiallyConnected

    @Volatile
    private var closedFlag: Boolean = false

    @Volatile
    private var closeLogged: Boolean = false

    @Volatile
    private var inputShutdownFlag: Boolean = false

    @Volatile
    private var outputShutdownFlag: Boolean = false

    private val fallbackInputStream: ZeroTierInputStream by lazy {
        ZeroTierInputStream().apply {
            zfd = runCatching { ztSocket.nativeFileDescriptor }.getOrDefault(-1)
        }
    }

    private val fallbackOutputStream: ZeroTierOutputStream by lazy {
        ZeroTierOutputStream().apply {
            zfd = runCatching { ztSocket.nativeFileDescriptor }.getOrDefault(-1)
        }
    }

    override fun getInputStream(): InputStream {
        return try {
            ztSocket.inputStream!!
        } catch (_: Exception) {
            fallbackInputStream
        }
    }

    override fun getOutputStream(): OutputStream {
        return try {
            ztSocket.outputStream!!
        } catch (_: Exception) {
            fallbackOutputStream
        }
    }

    override fun isConnected(): Boolean = connectedFlag && !closedFlag

    override fun isClosed(): Boolean {
        if (closedFlag) return true
        return try {
            ztSocket.isClosed
        } catch (_: Exception) {
            true
        }
    }

    override fun close() {
        connectedFlag = false
        closedFlag = true
        if (!closeLogged) {
            closeLogged = true
            val fd = runCatching { ztSocket.nativeFileDescriptor }.getOrNull()
            ZeroTierManager.logNote(
                "[ZT Socket] 关闭：remote=${remoteAddr.hostAddress} port=$remotePort fd=${fd ?: "未知"}"
            )
        }
        try {
            ztSocket.close()
        } catch (e: Exception) {
            ZeroTierManager.logException("[ZT Socket] 关闭异常", e)
        }
    }

    override fun getInetAddress(): InetAddress = remoteAddr

    override fun getPort(): Int = remotePort

    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(remoteAddr, remotePort)

    override fun setSoTimeout(timeout: Int) {
        try {
            ztSocket.soTimeout = timeout
        } catch (_: Exception) {
        }
    }

    override fun getSoTimeout(): Int {
        return try {
            ztSocket.soTimeout
        } catch (_: Exception) {
            0
        }
    }

    override fun setTcpNoDelay(on: Boolean) {
        try {
            ztSocket.setTcpNoDelayEnabled(on)
        } catch (_: Exception) {
        }
    }

    override fun getTcpNoDelay(): Boolean {
        return try {
            ztSocket.tcpNoDelayEnabled()
        } catch (_: Exception) {
            false
        }
    }

    override fun shutdownInput() {
        inputShutdownFlag = true
        try {
            ztSocket.shutdownInput()
        } catch (_: Exception) {
            runCatching {
                ZeroTierNative.zts_bsd_shutdown(
                    ztSocket.nativeFileDescriptor,
                    ZeroTierNative.ZTS_SHUT_RD
                )
            }
        }
    }

    override fun shutdownOutput() {
        outputShutdownFlag = true
        try {
            ztSocket.shutdownOutput()
        } catch (_: Exception) {
            runCatching {
                ZeroTierNative.zts_bsd_shutdown(
                    ztSocket.nativeFileDescriptor,
                    ZeroTierNative.ZTS_SHUT_WR
                )
            }
        }
    }

    override fun isInputShutdown(): Boolean {
        if (inputShutdownFlag) return true
        return try {
            ztSocket.inputStreamHasBeenShutdown()
        } catch (_: Exception) {
            false
        }
    }

    override fun isOutputShutdown(): Boolean {
        if (outputShutdownFlag) return true
        return try {
            ztSocket.outputStreamHasBeenShutdown()
        } catch (_: Exception) {
            false
        }
    }
}

class ZeroTierSocketFactory : SocketFactory {

    override fun createSocket(host: String, port: Int): Socket {
        cancelRequested = false
        val totalStart = System.currentTimeMillis()
        ZeroTierManager.logNote(
            "[ZT Socket] 开始创建：host=$host port=$port 策略=UDP预热+最多${MAX_ATTEMPTS}次TCP尝试（单次${CONNECT_TIMEOUT_MS}ms，间隔${RETRY_DELAY_MS}ms）"
        )

        val addr = InetAddress.getByName(host)
        val family = if (addr is Inet6Address) {
            ZeroTierNative.ZTS_AF_INET6
        } else {
            ZeroTierNative.ZTS_AF_INET
        }
        val hostForNative = addr.hostAddress ?: host

        warmUpPeer(addr, port)

        var lastError: Exception? = null
        var lastReturn: Int? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            if (cancelRequested) {
                throw IOException("ZeroTier connect cancelled: host=$hostForNative port=$port")
            }
            val attemptStart = System.currentTimeMillis()
            ZeroTierManager.logNote(
                "[ZT Socket] TCP尝试 $attempt/$MAX_ATTEMPTS 开始：host=$hostForNative port=$port"
            )
            var zt: ZeroTierSocket? = null
            try {
                zt = ZeroTierSocket(family, ZeroTierNative.ZTS_SOCK_STREAM, 0)
                activeZtSocket = zt
                val fd = zt.nativeFileDescriptor
                if (fd < 0) {
                    throw IOException("ZeroTierSocket fd 无效：fd=$fd")
                }
                val ret = ZeroTierNative.zts_connect(fd, hostForNative, port, CONNECT_TIMEOUT_MS)
                lastReturn = ret
                val elapsed = System.currentTimeMillis() - attemptStart
                if (ret >= 0) {
                    markNativeConnected(zt)
                    val totalElapsed = System.currentTimeMillis() - totalStart
                    ZeroTierManager.logNote(
                        "[ZT Socket] TCP尝试 $attempt/$MAX_ATTEMPTS 成功：host=$hostForNative port=$port fd=$fd ret=$ret elapsed=${elapsed}ms totalElapsed=${totalElapsed}ms"
                    )
                    return ZeroTierSocketAdapter(zt, addr, port, initiallyConnected = true)
                } else {
                    if (activeZtSocket === zt) {
                        activeZtSocket = null
                    }
                    ZeroTierManager.logNote(
                        "[ZT Socket] TCP尝试 $attempt/$MAX_ATTEMPTS 失败：host=$hostForNative port=$port fd=$fd ret=$ret elapsed=${elapsed}ms"
                    )
                    closeQuietly(zt)
                }
            } catch (e: Exception) {
                lastError = e
                val elapsed = System.currentTimeMillis() - attemptStart
                ZeroTierManager.logException(
                    "[ZT Socket] TCP尝试 $attempt/$MAX_ATTEMPTS 异常：host=$hostForNative port=$port elapsed=${elapsed}ms",
                    e
                )
                closeQuietly(zt)
            }

            if (attempt < MAX_ATTEMPTS) {
                ZeroTierManager.logNote(
                    "[ZT Socket] ${RETRY_DELAY_MS}ms 后重试（下一次 ${attempt + 1}/$MAX_ATTEMPTS）"
                )
                try {
                    Thread.sleep(RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException(
                        "ZeroTier TCP connect interrupted: host=$hostForNative port=$port attempt=$attempt",
                        e
                    )
                }
            }
        }

        val totalElapsed = System.currentTimeMillis() - totalStart
        val summary =
            "ZeroTier TCP connect failed after $MAX_ATTEMPTS attempts: host=$hostForNative port=$port lastRet=${lastReturn ?: "无"} totalElapsed=${totalElapsed}ms"
        ZeroTierManager.logNote("[ZT Socket] 创建失败：$summary")
        throw if (lastError != null) IOException(summary, lastError) else IOException(summary)
    }

    private fun warmUpPeer(addr: InetAddress, port: Int) {
        val start = System.currentTimeMillis()
        ZeroTierManager.logNote(
            "[ZT Socket] UDP预热开始：host=${addr.hostAddress} port=$port packets=$WARMUP_PACKETS"
        )
        var datagramSocket: ZeroTierDatagramSocket? = null
        try {
            if (cancelRequested) return
            datagramSocket = ZeroTierDatagramSocket()
            activeDatagramSocket = datagramSocket
            datagramSocket.connect(addr, port)
            val payload = "wol-warmup".toByteArray(Charsets.US_ASCII)
            for (index in 0 until WARMUP_PACKETS) {
                datagramSocket.send(DatagramPacket(payload, payload.size, addr, port))
                ZeroTierManager.logNote("[ZT Socket] UDP预热已发送 ${index + 1}/$WARMUP_PACKETS")
                if (index < WARMUP_PACKETS - 1) {
                    Thread.sleep(WARMUP_PACKET_GAP_MS)
                }
            }
            ZeroTierManager.logNote(
                "[ZT Socket] UDP预热完成：elapsed=${System.currentTimeMillis() - start}ms"
            )
        } catch (e: Exception) {
            if (e is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            ZeroTierManager.logException("[ZT Socket] UDP预热失败（继续 TCP 连接）", e)
        } finally {
            if (activeDatagramSocket === datagramSocket) {
                activeDatagramSocket = null
            }
            runCatching { datagramSocket?.close() }
        }

        if (cancelRequested) return
        try {
            Thread.sleep(WARMUP_SETTLE_MS)
            ZeroTierManager.logNote("[ZT Socket] UDP预热等待完成：${WARMUP_SETTLE_MS}ms")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ZeroTierManager.logNote("[ZT Socket] UDP预热等待被中断，继续 TCP 连接")
        }
    }

    private fun markNativeConnected(zt: ZeroTierSocket) {
        runCatching {
            val field = ZeroTierSocket::class.java.getDeclaredField("_isConnected")
            field.isAccessible = true
            field.setBoolean(zt, true)
        }.onFailure { e ->
            ZeroTierManager.logException("[ZT Socket] 标记 Java 连接状态失败（流将用 fd 兜底）", e)
        }
    }

    private fun closeQuietly(zt: ZeroTierSocket?) {
        runCatching { zt?.close() }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12000
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 3000L
        private const val WARMUP_PACKETS = 1
        private const val WARMUP_PACKET_GAP_MS = 400L
        private const val WARMUP_SETTLE_MS = 300L

        @Volatile
        private var activeDatagramSocket: ZeroTierDatagramSocket? = null

        @Volatile
        private var activeZtSocket: ZeroTierSocket? = null

        @Volatile
        private var cancelRequested: Boolean = false

        fun cancelActive() {
            cancelRequested = true
            runCatching { activeZtSocket?.close() }
            runCatching { activeDatagramSocket?.close() }
        }
    }
}
