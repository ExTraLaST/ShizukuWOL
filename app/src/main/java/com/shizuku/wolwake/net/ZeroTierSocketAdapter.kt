package com.shizuku.wolwake.net

import com.jcraft.jsch.SocketFactory
import com.zerotier.sockets.ZeroTierDatagramSocket
import com.zerotier.sockets.ZeroTierNative
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
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ZeroTierSocketAdapter(
    private val ztSocket: ZeroTierSocket,
    private val remoteAddr: InetAddress,
    private val remotePort: Int,
    initiallyConnected: Boolean = true
) : Socket() {

    private val fd: Int = runCatching { ztSocket.nativeFileDescriptor }.getOrDefault(-1)

    @Volatile
    private var connectedFlag: Boolean = initiallyConnected

    private val closed = AtomicBoolean(false)
    private val nativeClosed = AtomicBoolean(false)
    private val activeOps = AtomicInteger(0)

    @Volatile
    private var inputShutdownFlag: Boolean = false

    @Volatile
    private var outputShutdownFlag: Boolean = false

    @Volatile
    private var soTimeoutMs: Int = 0

    init {
        if (fd >= 0) {
            // 使用 200ms 读分片超时：避免长时间占用 libzt 的 JNI GetPrimitiveArrayCritical 拖慢 ART GC，
            // 同时让关闭操作无需并发调用 lwip_close(fd)，满足 lwIP LWIP_NETCONN_FULLDUPLEX=0 约束。
            runCatching { ZeroTierNative.zts_set_recv_timeout(fd, 0, READ_SLICE_USEC) }
            runCatching { ZeroTierNative.zts_set_send_timeout(fd, WRITE_TIMEOUT_SEC, 0) }
        }
    }

    private inline fun <T> withActiveIo(block: () -> T): T {
        if (closed.get() || fd < 0) {
            throw IOException("ZeroTier socket is closed")
        }
        activeOps.incrementAndGet()
        if (closed.get()) {
            if (activeOps.decrementAndGet() == 0) {
                tryCloseNativeIfIdle()
            }
            throw IOException("ZeroTier socket is closed")
        }
        try {
            return block()
        } finally {
            if (activeOps.decrementAndGet() == 0 && closed.get()) {
                tryCloseNativeIfIdle()
            }
        }
    }

    private fun tryCloseNativeIfIdle() {
        if (closed.get() && activeOps.get() == 0 && nativeClosed.compareAndSet(false, true)) {
            try {
                ztSocket.close()
            } catch (e: Exception) {
                ZeroTierManager.logException("[ZT Socket] 关闭异常", e)
            }
        }
    }

    private val safeInputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > b.size - len) {
                throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
            }
            if (len == 0) return 0
            if (closed.get() || inputShutdownFlag) return -1

            val startMs = System.currentTimeMillis()
            while (true) {
                if (closed.get() || inputShutdownFlag) return -1
                val retval = try {
                    withActiveIo {
                        // 统一调用 zts_bsd_read_offset，避开 zts_bsd_read 在临界区内调 GetArrayLength 的 JNI 违规
                        ZeroTierNative.zts_bsd_read_offset(fd, b, off, len)
                    }
                } catch (e: IOException) {
                    if (closed.get() || inputShutdownFlag) return -1
                    throw e
                }

                if (retval > 0) return retval
                if (retval == 0 || retval == -ZeroTierNative.ZTS_ECONNRESET) return -1

                val isRetryable = retval == -ZeroTierNative.ZTS_EAGAIN ||
                    retval == -ZeroTierNative.ZTS_EWOULDBLOCK ||
                    retval == -ZeroTierNative.ZTS_EINTR ||
                    retval == -ZeroTierNative.ZTS_ETIMEDOUT

                if (isRetryable) {
                    if (closed.get() || inputShutdownFlag) return -1
                    val timeout = soTimeoutMs
                    if (timeout > 0 && System.currentTimeMillis() - startMs >= timeout.toLong()) {
                        throw SocketTimeoutException("ZeroTier read timed out after ${timeout}ms")
                    }
                    continue
                }
                throw IOException("ZeroTier read failed, errno=$retval")
            }
        }

        override fun close() {
            inputShutdownFlag = true
        }
    }

    private val safeOutputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf((b and 0xFF).toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || off > b.size - len) {
                throw IndexOutOfBoundsException("off=$off len=$len size=${b.size}")
            }
            if (len == 0) return

            var currentOff = off
            var remaining = len
            while (remaining > 0) {
                if (closed.get() || outputShutdownFlag) {
                    throw IOException("ZeroTier output stream is closed")
                }
                // libzt 的 zts_bsd_write_offset 对 jbyteArray 做了 &(buf[offset]) 指针运算，
                // offset != 0 会直接触发 JNI 崩溃；此处切片确保传给 native 的 offset 恒为 0。
                val slice = if (currentOff == 0) {
                    b
                } else {
                    b.copyOfRange(currentOff, currentOff + remaining)
                }
                val written = withActiveIo {
                    ZeroTierNative.zts_bsd_write_offset(fd, slice, 0, remaining)
                }
                if (written > 0) {
                    currentOff += written
                    remaining -= written
                } else if (written == -ZeroTierNative.ZTS_EAGAIN ||
                    written == -ZeroTierNative.ZTS_EWOULDBLOCK ||
                    written == -ZeroTierNative.ZTS_EINTR
                ) {
                    if (closed.get() || outputShutdownFlag) {
                        throw IOException("ZeroTier output stream is closed")
                    }
                    continue
                } else {
                    throw IOException("ZeroTier write failed, errno=$written")
                }
            }
        }

        override fun close() {
            outputShutdownFlag = true
        }
    }

    override fun getInputStream(): InputStream = safeInputStream

    override fun getOutputStream(): OutputStream = safeOutputStream

    override fun isConnected(): Boolean = connectedFlag && !closed.get()

    override fun isClosed(): Boolean = closed.get() || nativeClosed.get()

    override fun close() {
        connectedFlag = false
        inputShutdownFlag = true
        outputShutdownFlag = true
        if (closed.compareAndSet(false, true)) {
            ZeroTierManager.logNote(
                "[ZT Socket] 关闭：remote=${remoteAddr.hostAddress} port=$remotePort fd=${if (fd >= 0) fd else "未知"}"
            )
        }
        tryCloseNativeIfIdle()
    }

    override fun getInetAddress(): InetAddress = remoteAddr

    override fun getPort(): Int = remotePort

    override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(remoteAddr, remotePort)

    override fun setSoTimeout(timeout: Int) {
        soTimeoutMs = timeout.coerceAtLeast(0)
    }

    override fun getSoTimeout(): Int = soTimeoutMs

    override fun setTcpNoDelay(on: Boolean) {
        if (closed.get() || fd < 0) return
        try {
            withActiveIo {
                ztSocket.setTcpNoDelayEnabled(on)
            }
        } catch (_: Exception) {
        }
    }

    override fun getTcpNoDelay(): Boolean {
        if (closed.get() || fd < 0) return false
        return try {
            withActiveIo {
                ztSocket.tcpNoDelayEnabled()
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun shutdownInput() {
        inputShutdownFlag = true
    }

    override fun shutdownOutput() {
        outputShutdownFlag = true
    }

    override fun isInputShutdown(): Boolean = inputShutdownFlag || closed.get()

    override fun isOutputShutdown(): Boolean = outputShutdownFlag || closed.get()

    companion object {
        private const val READ_SLICE_USEC = 200_000
        private const val WRITE_TIMEOUT_SEC = 5
    }
}

class ZeroTierSocketFactory(
    private val cancelled: () -> Boolean = { false },
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
) : SocketFactory {

    override fun createSocket(host: String, port: Int): Socket {
        val attempts = maxAttempts.coerceAtLeast(1)
        val timeoutMs = connectTimeoutMs.coerceAtLeast(1000)
        val totalStart = System.currentTimeMillis()
        ZeroTierManager.logNote(
            "[ZT Socket] 开始创建：host=$host port=$port 策略=UDP预热+最多${attempts}次TCP尝试（单次${timeoutMs}ms，间隔${RETRY_DELAY_MS}ms）"
        )

        if (cancelled()) {
            throw IOException("ZeroTier connect cancelled before resolve: host=$host port=$port")
        }

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

        for (attempt in 1..attempts) {
            if (cancelled()) {
                throw IOException("ZeroTier connect cancelled: host=$hostForNative port=$port")
            }
            val attemptStart = System.currentTimeMillis()
            ZeroTierManager.logNote(
                "[ZT Socket] TCP尝试 $attempt/$attempts 开始：host=$hostForNative port=$port"
            )
            var zt: ZeroTierSocket? = null
            try {
                zt = ZeroTierSocket(family, ZeroTierNative.ZTS_SOCK_STREAM, 0)
                val fd = zt.nativeFileDescriptor
                if (fd < 0) {
                    throw IOException("ZeroTierSocket fd 无效：fd=$fd")
                }
                val ret = ZeroTierNative.zts_connect(fd, hostForNative, port, timeoutMs)
                lastReturn = ret
                val elapsed = System.currentTimeMillis() - attemptStart
                if (cancelled()) {
                    closeQuietly(zt)
                    throw IOException("ZeroTier connect cancelled: host=$hostForNative port=$port")
                }
                if (ret >= 0) {
                    markNativeConnected(zt)
                    val totalElapsed = System.currentTimeMillis() - totalStart
                    ZeroTierManager.logNote(
                        "[ZT Socket] TCP尝试 $attempt/$attempts 成功：host=$hostForNative port=$port fd=$fd ret=$ret elapsed=${elapsed}ms totalElapsed=${totalElapsed}ms"
                    )
                    return ZeroTierSocketAdapter(zt, addr, port, initiallyConnected = true)
                } else {
                    ZeroTierManager.logNote(
                        "[ZT Socket] TCP尝试 $attempt/$attempts 失败：host=$hostForNative port=$port fd=$fd ret=$ret elapsed=${elapsed}ms"
                    )
                    closeQuietly(zt)
                }
            } catch (e: Exception) {
                lastError = e
                val elapsed = System.currentTimeMillis() - attemptStart
                ZeroTierManager.logException(
                    "[ZT Socket] TCP尝试 $attempt/$attempts 异常：host=$hostForNative port=$port elapsed=${elapsed}ms",
                    e
                )
                closeQuietly(zt)
                if (cancelled()) {
                    throw IOException("ZeroTier connect cancelled: host=$hostForNative port=$port", e)
                }
            }

            if (attempt < attempts) {
                ZeroTierManager.logNote(
                    "[ZT Socket] ${RETRY_DELAY_MS}ms 后重试（下一次 ${attempt + 1}/$attempts）"
                )
                sleepInterruptibly(RETRY_DELAY_MS, hostForNative, port, attempt)
            }
        }

        val totalElapsed = System.currentTimeMillis() - totalStart
        val summary =
            "ZeroTier TCP connect failed after $attempts attempts: host=$hostForNative port=$port lastRet=${lastReturn ?: "无"} totalElapsed=${totalElapsed}ms"
        ZeroTierManager.logNote("[ZT Socket] 创建失败：$summary")
        throw if (lastError != null) IOException(summary, lastError) else IOException(summary)
    }

    private fun sleepInterruptibly(durationMs: Long, host: String, port: Int, attempt: Int) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            if (cancelled()) {
                throw IOException("ZeroTier connect cancelled: host=$host port=$port attempt=$attempt")
            }
            val remain = (deadline - System.currentTimeMillis()).coerceAtMost(100L)
            if (remain <= 0L) break
            try {
                Thread.sleep(remain)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(
                    "ZeroTier TCP connect interrupted: host=$host port=$port attempt=$attempt",
                    e
                )
            }
        }
    }

    private fun warmUpPeer(addr: InetAddress, port: Int) {
        if (addr is Inet6Address) {
            ZeroTierManager.logNote("[ZT Socket] 目标为 IPv6，跳过 IPv4 UDP 预热")
            return
        }
        val start = System.currentTimeMillis()
        ZeroTierManager.logNote(
            "[ZT Socket] UDP预热开始：host=${addr.hostAddress} port=$port packets=$WARMUP_PACKETS"
        )
        var datagramSocket: ZeroTierDatagramSocket? = null
        try {
            if (cancelled()) return
            datagramSocket = ZeroTierDatagramSocket()
            datagramSocket.connect(addr, port)
            val payload = "wol-warmup".toByteArray(Charsets.US_ASCII)
            for (index in 0 until WARMUP_PACKETS) {
                if (cancelled()) return
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
            runCatching { datagramSocket?.close() }
        }

        if (cancelled()) return
        try {
            val deadline = System.currentTimeMillis() + WARMUP_SETTLE_MS
            while (System.currentTimeMillis() < deadline && !cancelled()) {
                val remain = (deadline - System.currentTimeMillis()).coerceAtMost(100L)
                if (remain > 0L) Thread.sleep(remain)
            }
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
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 12000
        private const val DEFAULT_MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 3000L
        private const val WARMUP_PACKETS = 1
        private const val WARMUP_PACKET_GAP_MS = 400L
        private const val WARMUP_SETTLE_MS = 300L
    }
}
