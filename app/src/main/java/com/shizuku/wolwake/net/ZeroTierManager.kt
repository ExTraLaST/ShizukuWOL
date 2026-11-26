package com.shizuku.wolwake.net

import android.content.Context
import com.shizuku.wolwake.data.SettingsStore
import com.zerotier.sockets.ZeroTierEventListener
import com.zerotier.sockets.ZeroTierNative
import com.zerotier.sockets.ZeroTierNode
import java.io.File
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ZeroTierManager {
    private const val MAX_LOG_ENTRIES = 300
    private const val MAX_LOG_FILE_BYTES = 1_000_000L
    private const val MAX_LOG_FILE_LINES = 1000
    private const val MAX_LOG_TEXT_CHARS = 2500
    private const val MAX_SEEN_PEERS = 64
    private const val MAX_VALID_NODE_ID = 0xFFFFFFFFFFL // ZeroTier 节点 ID 为 40 位无符号整数

    private val lock = Any()
    private val logLock = Any()
    private val peerLock = Any()

    @Volatile
    private var node: ZeroTierNode? = null

    @Volatile
    private var nodeReady: Boolean = false

    @Volatile
    private var startedNetworkId: Long = 0L

    @Volatile
    private var status: ZtStatus = ZtStatus()

    private val _statusFlow = MutableStateFlow(ZtStatus())
    val statusFlow: StateFlow<ZtStatus> = _statusFlow.asStateFlow()

    @Volatile
    private var diagLogEnabled: Boolean = false

    @Volatile
    private var diagnosticFile: File? = null

    @Volatile
    private var diagnosticsInitialized: Boolean = false

    private val logEntries = ArrayDeque<ZtLogEntry>()
    private val seenPeerIds = LinkedHashSet<Long>()

    private val _diagLogFlow = MutableStateFlow<List<ZtLogEntry>>(emptyList())
    val diagLogFlow: StateFlow<List<ZtLogEntry>> = _diagLogFlow.asStateFlow()

    fun isValidNodeId(id: Long): Boolean = id in 1L..MAX_VALID_NODE_ID

    private fun normalizeNodeId(id: Long): Long = if (isValidNodeId(id)) id else 0L

    data class ZtStatus(
        val nodeId: Long = 0L,
        val online: Boolean = false,
        val networkReady: Boolean = false,
        val assignedIp: String? = null,
        val lastError: String? = null
    ) {
        fun nodeIdHex(): String = if (isValidNodeId(nodeId)) java.lang.Long.toHexString(nodeId) else ""
    }

    data class ZtLogEntry(
        val time: String,
        val text: String
    )

    fun currentStatus(): ZtStatus = status

    private fun setStatus(s: ZtStatus) {
        status = s
        _statusFlow.value = s
    }

    private fun hex(id: Long): String = if (id == 0L) "0" else java.lang.Long.toHexString(id)

    fun setDiagLogEnabled(enabled: Boolean) {
        diagLogEnabled = enabled
    }

    private fun nowTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun sanitizeLogText(text: String): String {
        val oneLine = text
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (oneLine.length > MAX_LOG_TEXT_CHARS) {
            oneLine.take(MAX_LOG_TEXT_CHARS) + "…(已截断)"
        } else {
            oneLine
        }
    }

    private fun parseLogLine(line: String): ZtLogEntry {
        return if (line.length >= 24 && line[23] == ' ') {
            ZtLogEntry(line.substring(0, 23), sanitizeLogText(line.substring(24)))
        } else {
            ZtLogEntry(nowTimestamp(), sanitizeLogText(line))
        }
    }

    private fun appendToFileLocked(entry: ZtLogEntry) {
        val file = diagnosticFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText("${entry.time} ${entry.text}\n", Charsets.UTF_8)
            if (file.length() > MAX_LOG_FILE_BYTES) {
                val lines = file.readLines(Charsets.UTF_8).takeLast(MAX_LOG_FILE_LINES)
                file.writeText(
                    lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"),
                    Charsets.UTF_8
                )
            }
        }
    }

    private fun appendLogLocked(entry: ZtLogEntry) {
        val clean = entry.copy(text = sanitizeLogText(entry.text))
        logEntries.addLast(clean)
        while (logEntries.size > MAX_LOG_ENTRIES) {
            logEntries.removeFirst()
        }
        _diagLogFlow.value = logEntries.toList()
        appendToFileLocked(clean)
    }

    fun initDiagnostics(context: Context) {
        diagLogEnabled = runCatching {
            SettingsStore(context).load().diagLogEnabled
        }.getOrDefault(false)

        synchronized(logLock) {
            if (diagnosticsInitialized) return
            diagnosticsInitialized = true
            val file = runCatching {
                File(File(context.filesDir, "diagnostics"), "wol-diagnostic.log").apply {
                    parentFile?.mkdirs()
                }
            }.getOrNull()
            diagnosticFile = file
            if (file != null) {
                runCatching {
                    if (file.exists()) {
                        val loaded = file.readLines(Charsets.UTF_8)
                            .filter { it.isNotBlank() }
                            .takeLast(MAX_LOG_ENTRIES)
                            .map { parseLogLine(it) }
                        logEntries.clear()
                        logEntries.addAll(loaded)
                        _diagLogFlow.value = logEntries.toList()
                    }
                }
            }
            if (diagLogEnabled) {
                appendLogLocked(
                    ZtLogEntry(
                        nowTimestamp(),
                        "诊断日志已初始化（持久化文件：${file?.name ?: "不可用"}，已载入 ${logEntries.size} 条）"
                    )
                )
            }
        }
    }

    fun logNote(text: String) {
        if (!diagLogEnabled) return
        synchronized(logLock) {
            appendLogLocked(ZtLogEntry(nowTimestamp(), text))
        }
    }

    fun logSection(title: String) {
        logNote("==== $title ====")
    }

    fun logException(prefix: String, throwable: Throwable) {
        val message = throwable.message?.takeIf { it.isNotBlank() } ?: "（无消息）"
        logNote("$prefix：${throwable.javaClass.name}: $message")

        val causes = generateSequence(throwable.cause) { it.cause }
            .take(5)
            .toList()
        if (causes.isNotEmpty()) {
            val causeText = causes.joinToString(" <- ") {
                "${it.javaClass.name}: ${it.message ?: "（无消息）"}"
            }
            logNote("$prefix 原因链：$causeText")
        }

        val frames = throwable.stackTrace.take(8).joinToString(" <- ") {
            "${it.className}.${it.methodName}(${it.fileName ?: "未知文件"}:${it.lineNumber})"
        }
        if (frames.isNotBlank()) {
            logNote("$prefix 堆栈：$frames")
        }
    }

    fun clearDiagLog() {
        synchronized(logLock) {
            logEntries.clear()
            _diagLogFlow.value = emptyList()
            val file = diagnosticFile
            if (file != null) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText("", Charsets.UTF_8)
                }
            }
        }
    }

    fun exportDiagLog(): String {
        val snapshot = synchronized(logLock) { logEntries.toList() }
        return buildString {
            appendLine("Shizuku WOL 诊断日志")
            appendLine("生成时间：${nowTimestamp()}")
            appendLine("条目数：${snapshot.size}")
            appendLine("说明：日志不包含 SSH 密码。")
            appendLine()
            snapshot.forEach { entry ->
                appendLine("${entry.time} ${entry.text}")
            }
        }
    }

    private fun trackPeer(peerId: Long) {
        if (peerId == 0L) return
        synchronized(peerLock) {
            seenPeerIds.add(peerId)
            while (seenPeerIds.size > MAX_SEEN_PEERS) {
                val it = seenPeerIds.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
        }
    }

    private fun seenPeersSnapshot(): List<Long> = synchronized(peerLock) { seenPeerIds.toList() }

    fun eventText(code: Int, id: Long): String {
        val idHex = if (id != 0L) "（${java.lang.Long.toHexString(id)}）" else ""
        return when (code) {
            ZeroTierNative.ZTS_EVENT_NODE_UP -> "节点已启动"
            ZeroTierNative.ZTS_EVENT_NODE_ONLINE -> "节点已上线（已连上根服务器）"
            ZeroTierNative.ZTS_EVENT_NODE_OFFLINE -> "节点离线（连不上根服务器）"
            ZeroTierNative.ZTS_EVENT_NODE_DOWN -> "节点已停止"
            ZeroTierNative.ZTS_EVENT_NODE_FATAL_ERROR -> "节点致命错误$idHex"
            ZeroTierNative.ZTS_EVENT_NETWORK_NOT_FOUND -> "网络不存在（网络 ID 可能填错了）$idHex"
            ZeroTierNative.ZTS_EVENT_NETWORK_CLIENT_TOO_OLD -> "客户端版本太旧"
            ZeroTierNative.ZTS_EVENT_NETWORK_REQ_CONFIG -> "正在向控制器请求网络配置…$idHex"
            ZeroTierNative.ZTS_EVENT_NETWORK_OK -> "已加入网络$idHex"
            ZeroTierNative.ZTS_EVENT_NETWORK_ACCESS_DENIED -> "控制器拒绝加入：还没在 my.zerotier.com 授权本节点$idHex"
            ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP4 -> "已拿到 IPv4 地址"
            ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP6 -> "已拿到 IPv6 地址"
            ZeroTierNative.ZTS_EVENT_NETWORK_READY_IP4_IP6 -> "已拿到 IPv4/IPv6 地址"
            ZeroTierNative.ZTS_EVENT_NETWORK_DOWN -> "网络已断开$idHex"
            ZeroTierNative.ZTS_EVENT_NETWORK_UPDATE -> "网络配置已更新$idHex"
            ZeroTierNative.ZTS_EVENT_STACK_UP -> "网络协议栈已启动"
            ZeroTierNative.ZTS_EVENT_STACK_DOWN -> "网络协议栈已停止"
            ZeroTierNative.ZTS_EVENT_NETIF_UP -> "虚拟网卡已启动"
            ZeroTierNative.ZTS_EVENT_NETIF_DOWN -> "虚拟网卡已停止"
            ZeroTierNative.ZTS_EVENT_NETIF_REMOVED -> "虚拟网卡已移除"
            ZeroTierNative.ZTS_EVENT_NETIF_LINK_UP -> "虚拟网卡链路已接通"
            ZeroTierNative.ZTS_EVENT_NETIF_LINK_DOWN -> "虚拟网卡链路已断开"
            ZeroTierNative.ZTS_EVENT_PEER_DIRECT -> "与对端节点直连$idHex"
            ZeroTierNative.ZTS_EVENT_PEER_RELAY -> "经中继连接对端节点$idHex"
            ZeroTierNative.ZTS_EVENT_PEER_UNREACHABLE -> "对端节点不可达$idHex"
            ZeroTierNative.ZTS_EVENT_PEER_PATH_DISCOVERED -> "发现对端节点路径$idHex"
            ZeroTierNative.ZTS_EVENT_PEER_PATH_DEAD -> "对端节点路径失效$idHex"
            ZeroTierNative.ZTS_EVENT_ROUTE_ADDED -> "已添加路由"
            ZeroTierNative.ZTS_EVENT_ROUTE_REMOVED -> "已移除路由"
            ZeroTierNative.ZTS_EVENT_ADDR_ADDED_IP4 -> "已分配 IPv4 地址"
            ZeroTierNative.ZTS_EVENT_ADDR_REMOVED_IP4 -> "IPv4 地址已移除"
            ZeroTierNative.ZTS_EVENT_ADDR_ADDED_IP6 -> "已分配 IPv6 地址"
            ZeroTierNative.ZTS_EVENT_ADDR_REMOVED_IP6 -> "IPv6 地址已移除"
            else -> "未知事件（code=$code）$idHex"
        }
    }

    private fun onZtEvent(code: Int, id: Long) {
        try {
            if (code == ZeroTierNative.ZTS_EVENT_PEER_DIRECT ||
                code == ZeroTierNative.ZTS_EVENT_PEER_RELAY ||
                code == ZeroTierNative.ZTS_EVENT_PEER_UNREACHABLE ||
                code == ZeroTierNative.ZTS_EVENT_PEER_PATH_DISCOVERED ||
                code == ZeroTierNative.ZTS_EVENT_PEER_PATH_DEAD
            ) {
                trackPeer(id)
            }
            logNote(eventText(code, id))
            refreshStatus()
        } catch (_: Throwable) {
        }
    }

    fun isRunning(): Boolean = node != null && nodeReady

    fun refreshStatus(): ZtStatus {
        val n = node ?: return currentStatus()
        if (!nodeReady) return currentStatus()
        val netId = startedNetworkId
        return try {
            val id = normalizeNodeId(runCatching { n.id }.getOrDefault(0L))
            if (id == 0L) return currentStatus()
            val online = runCatching { n.isOnline }.getOrDefault(false)
            val ready = if (netId != 0L) {
                runCatching { n.isNetworkTransportReady(netId) }.getOrDefault(false)
            } else {
                false
            }
            val ip = if (netId != 0L) readAssignedIp(n, netId) else null
            val s = ZtStatus(id, online, ready, ip, currentStatus().lastError)
            setStatus(s)
            s
        } catch (_: Throwable) {
            currentStatus()
        }
    }

    private fun readAssignedIp(n: ZeroTierNode, networkId: Long): String? {
        return try {
            if (ZeroTierNative.zts_addr_is_assigned(networkId, ZeroTierNative.ZTS_AF_INET) != 1) {
                return null
            }
            val host = n.getIPv4Address(networkId)?.hostAddress ?: return null
            if (host == "" || host == "0.0.0.0" || host == "127.0.0.1" || host == "::1") {
                return null
            }
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (addr != null && (addr.isLoopbackAddress || addr.isAnyLocalAddress)) {
                return null
            }
            host
        } catch (_: Throwable) {
            null
        }
    }

    private fun nativeIntText(block: () -> Int): String {
        return runCatching { block().toString() }.getOrElse {
            "失败(${it.javaClass.simpleName}:${it.message ?: "无消息"})"
        }
    }

    private fun networkStatusText(raw: Int?): String = when (raw) {
        0 -> "请求配置中"
        1 -> "OK"
        2 -> "访问被拒绝"
        3 -> "网络不存在"
        4 -> "端口错误"
        5 -> "客户端太旧"
        null -> "读取失败"
        else -> "未知状态"
    }

    private fun networkTypeText(raw: Int?): String = when (raw) {
        0 -> "私有"
        1 -> "公开"
        null -> "读取失败"
        else -> "未知类型"
    }

    fun logSnapshot(label: String, networkId: Long) {
        if (!diagLogEnabled) return
        val n = node
        if (n == null || !nodeReady) {
            logNote("快照[$label] 跳过：节点对象未就绪")
            return
        }
        val nodeId = normalizeNodeId(runCatching { n.id }.getOrDefault(0L))
        if (nodeId == 0L) {
            logNote("快照[$label] 跳过：getId() 无效，native 节点未创建")
            return
        }
        val online = runCatching { n.isOnline }.getOrDefault(false)
        val transportReady = runCatching { n.isNetworkTransportReady(networkId) }.getOrDefault(false)
        val assignedIp = readAssignedIp(n, networkId) ?: "未分配"
        val port = nativeIntText { ZeroTierNative.zts_node_get_port() }
        // libzt 的 zts_core_query_* 内部不加 _nets_m 锁，必须成对使用 zts_core_lock_obtain/release
        var addrCount = "未获取锁"
        var routeCount = "未获取锁"
        val lockRes = runCatching { ZeroTierNative.zts_core_lock_obtain() }.getOrDefault(-1)
        if (lockRes == ZeroTierNative.ZTS_ERR_OK) {
            try {
                addrCount = nativeIntText { ZeroTierNative.zts_core_query_addr_count(networkId) }
                routeCount = nativeIntText { ZeroTierNative.zts_core_query_route_count(networkId) }
            } finally {
                runCatching { ZeroTierNative.zts_core_lock_release() }
            }
        }
        val routeAssigned = runCatching {
            ZeroTierNative.zts_route_is_assigned(networkId, ZeroTierNative.ZTS_AF_INET)
        }.getOrNull()
        val statusRaw = runCatching { ZeroTierNative.zts_net_get_status(networkId) }.getOrNull()
        val typeRaw = runCatching { ZeroTierNative.zts_net_get_type(networkId) }.getOrNull()
        val mtu = nativeIntText { ZeroTierNative.zts_net_get_mtu(networkId) }

        logNote(
            "快照[$label] nodeId=${hex(nodeId)} online=$online transportReady=$transportReady " +
                "assignedIp=$assignedIp networkId=${hex(networkId)} port=$port addrCount=$addrCount " +
                "routeCount=$routeCount routeAssignedAF_INET=${routeAssigned ?: "读取失败"} " +
                "status=${statusRaw ?: "读取失败"}(${networkStatusText(statusRaw)}) " +
                "type=${typeRaw ?: "读取失败"}(${networkTypeText(typeRaw)}) mtu=$mtu"
        )

        val peers = seenPeersSnapshot()
        if (peers.isEmpty()) {
            logNote("快照[$label] 对端路径：无已见对端")
        } else {
            val pathText = peers.take(32).joinToString("; ") { peerId ->
                val count = nativeIntText { ZeroTierNative.zts_core_query_path_count(peerId) }
                "${hex(peerId)}=$count"
            }
            logNote("快照[$label] 对端路径：$pathText")
        }
    }

    fun ensureReady(
        context: Context,
        networkId: Long,
        timeoutMs: Long = 30000L,
        cancelled: () -> Boolean = { false }
    ): ZtStatus = synchronized(lock) {
        val startTime = System.currentTimeMillis()
        initDiagnostics(context)
        val nodeExisted = node != null
        logNote(
            "ensureReady 开始：networkId=${hex(networkId)} timeoutMs=$timeoutMs " +
                "node已存在=$nodeExisted startedNetworkId=${hex(startedNetworkId)}"
        )

        try {
            val storageDir = File(context.filesDir, "zerotier").apply { mkdirs() }
            var n = node
            var justStarted = false
            if (n == null) {
                logNote("ensureReady：创建 ZeroTierNode 并从存储初始化")
                n = ZeroTierNode()
                val initRes = runCatching { n.initFromStorage(storageDir.absolutePath) }
                    .onFailure { logException("initFromStorage 异常", it) }
                    .getOrDefault(-1)
                logNote("initFromStorage() 返回 $initRes")

                val handlerRes = runCatching {
                    n.initSetEventHandler(ZeroTierEventListener { id, code ->
                        onZtEvent(code, id)
                    })
                }.onFailure { logException("注册事件监听异常", it) }
                    .getOrDefault(-1)
                logNote("注册事件监听返回 $handlerRes")

                val startRes = runCatching { n.start() }
                    .onFailure { logException("start 异常", it) }
                    .getOrDefault(-1)
                logNote("start() 返回 $startRes")
                if (startRes != 0) {
                    logNote("注意：start() 返回非 0，节点可能没有真正启动起来")
                }
                node = n
                justStarted = true
            } else {
                logNote("ensureReady：复用已有 ZeroTierNode，跳过 init/start")
            }

            // 等待门：join()/leave() 前必须确认 getId() 返回合法正值，避免 start 后返回负数错误码绕过门闩导致空指针崩溃
            val gateStart = System.currentTimeMillis()
            val nodeDeadline = gateStart + 5000L
            var nodeId = 0L
            while (!isValidNodeId(nodeId) && System.currentTimeMillis() < nodeDeadline) {
                if (cancelled()) {
                    logNote("ensureReady 被取消（getId 门闩等待期间），停止等待")
                    return@synchronized status.copy()
                }
                nodeId = runCatching { n.id }.getOrDefault(0L)
                if (!isValidNodeId(nodeId)) {
                    Thread.sleep(100L)
                }
            }
            val gateElapsed = System.currentTimeMillis() - gateStart
            logNote("getId 门闩：等待 ${gateElapsed}ms nodeId=${hex(normalizeNodeId(nodeId))}")
            if (!isValidNodeId(nodeId)) {
                val errMsg = "start() 后 5 秒内节点一直没创建出来，未调 join()，请重试"
                logNote(errMsg)
                logNote("ensureReady 结束：elapsed=${System.currentTimeMillis() - startTime}ms status=门闩超时")
                val s = status.copy(lastError = errMsg)
                setStatus(s)
                return@synchronized s
            }
            nodeReady = true
            if (justStarted) {
                logNote("节点已创建，Node ID：${java.lang.Long.toHexString(nodeId)}")
            }

            if (startedNetworkId != networkId) {
                val oldNetworkId = startedNetworkId
                logNote("ensureReady：网络切换 ${hex(oldNetworkId)} -> ${hex(networkId)}")
                if (oldNetworkId != 0L) {
                    val gateId = runCatching { n.id }.getOrDefault(0L)
                    if (isValidNodeId(gateId)) {
                        val leaveRes = runCatching { n.leave(oldNetworkId) }
                            .onFailure { logException("leave 旧网络异常", it) }
                            .getOrDefault(-1)
                        logNote("leave(${hex(oldNetworkId)}) 返回 $leaveRes")
                    } else {
                        logNote("leave 旧网络跳过：getId() 无效，门闩未通过")
                    }
                }
                if (cancelled()) {
                    logNote("ensureReady 被取消（join 前），停止等待，节点保持不动")
                    return@synchronized status.copy()
                }
                val joinRes = runCatching { n.join(networkId) }
                    .onFailure { logException("join 异常", it) }
                    .getOrDefault(-1)
                logNote("join(${java.lang.Long.toHexString(networkId)}) 返回 $joinRes")
                if (joinRes != 0) {
                    logNote("注意：join() 返回非 0，加入网络可能失败了")
                }
                startedNetworkId = networkId
            } else {
                logNote("ensureReady：已在目标网络，跳过 join")
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            var online = false
            var ready = false
            var ip: String? = null
            var id = nodeId

            var hasLoggedInitial = false
            var lastOnline = false
            var lastReady = false
            var lastIp: String? = null

            while (System.currentTimeMillis() < deadline) {
                if (cancelled()) {
                    logNote("ensureReady 被取消，停止等待（不离开网络、不停节点）")
                    return@synchronized status.copy()
                }
                online = runCatching { n.isOnline }.getOrDefault(false)
                ready = runCatching { n.isNetworkTransportReady(networkId) }.getOrDefault(false)
                ip = readAssignedIp(n, networkId)
                val polledId = normalizeNodeId(runCatching { n.id }.getOrDefault(0L))
                if (polledId != 0L) {
                    id = polledId
                }
                setStatus(ZtStatus(id, online, ready, ip, null))

                if (!hasLoggedInitial || online != lastOnline || ready != lastReady || ip != lastIp) {
                    logNote(
                        "ensureReady 状态变化：online=$online ready=$ready ip=${ip ?: "未分配"} " +
                            "elapsed=${System.currentTimeMillis() - startTime}ms"
                    )
                    hasLoggedInitial = true
                    lastOnline = online
                    lastReady = ready
                    lastIp = ip
                }

                if (online && ready && !ip.isNullOrBlank()) {
                    break
                }
                Thread.sleep(200L)
            }

            val errorMsg = when {
                ready -> null
                online -> "网络未就绪：请确认已在 my.zerotier.com 授权本节点（Node ID：${java.lang.Long.toHexString(id)}）"
                else -> "节点未上线，请检查手机网络"
            }
            val finalStatus = ZtStatus(id, online, ready, ip, errorMsg)
            setStatus(finalStatus)
            logSnapshot("ensureReady 结束", networkId)
            logNote(
                "ensureReady 结束：elapsed=${System.currentTimeMillis() - startTime}ms " +
                    "online=$online ready=$ready ip=${ip ?: "未分配"} lastError=${finalStatus.lastError ?: "无"}"
            )
            finalStatus
        } catch (t: Throwable) {
            logException("ensureReady 异常", t)
            val msg = "启动内置 ZeroTier 失败：${t.message ?: t.javaClass.simpleName}"
            logNote("ensureReady 结束：elapsed=${System.currentTimeMillis() - startTime}ms 失败：$msg")
            val s = status.copy(lastError = msg)
            setStatus(s)
            s
        }
    }

    /**
     * 仅调用 leave() 离开当前网络，绝不调用 ZeroTierNode.stop() 或 free()，
     * 以避免触发 libzt JNI 的 DetachCurrentThread abort。
     */
    fun stop() = synchronized(lock) {
        val startTime = System.currentTimeMillis()
        val oldNetworkId = startedNetworkId
        logNote("stop 开始：startedNetworkId=${hex(oldNetworkId)} node存在=${node != null}")
        try {
            val n = node
            if (n != null && nodeReady && oldNetworkId != 0L) {
                val gateId = runCatching { n.id }.getOrDefault(0L)
                if (isValidNodeId(gateId)) {
                    val leaveRes = runCatching { n.leave(oldNetworkId) }
                        .onFailure { logException("stop leave 异常", it) }
                        .getOrDefault(-1)
                    logNote("stop leave(${hex(oldNetworkId)}) 返回 $leaveRes")
                } else {
                    logNote("stop leave 跳过：getId() 无效，门闩未通过")
                }
            } else {
                logNote("stop leave 跳过：node存在=${n != null} nodeReady=$nodeReady startedNetworkId=${hex(oldNetworkId)}")
            }
        } finally {
            startedNetworkId = 0L
            setStatus(ZtStatus())
            logNote("已离开网络（节点保留在后台，下次直接重连） stop 耗时 ${System.currentTimeMillis() - startTime}ms")
        }
    }

    fun isReadyQuick(networkId: Long): Boolean {
        val n = node ?: return false
        if (!nodeReady) return false
        return try {
            startedNetworkId == networkId && n.isOnline && n.isNetworkTransportReady(networkId)
        } catch (_: Throwable) {
            false
        }
    }

    fun isReadyFor(networkId: Long): Boolean {
        if (!isRunning() || startedNetworkId != networkId) return false
        val s = refreshStatus()
        return s.online && s.networkReady && !s.assignedIp.isNullOrBlank()
    }
}
