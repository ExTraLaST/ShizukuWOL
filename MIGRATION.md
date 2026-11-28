# Shizuku WOL（一键唤醒）迁移与维护说明

> 本文档为项目当前状态、核心约束与构建签名的权威说明（以本文档和源码为准）。
> 当前工程源码由 `v1.1.7` 归档安装包（`ShizukuWOL-v1.1.7.apk`）完整还原并校验通过。

## 1. 当前状态

- **应用名称**：一键唤醒（Shizuku WOL）
- **包名**：`com.shizuku.wolwake`
- **版本**：`versionCode 10` / `versionName 1.1.8`
- **技术栈**：Kotlin + Jetpack Compose + Material Design 3，AGP `8.5.2`，`minSdk 24`，`targetSdk 34`，`compileSdk 34`，JDK 17
- **核心依赖**：
  - `com.github.mwiede:jsch:0.2.18`（SSH）
  - `app/libs/libzt-release.aar`（ZeroTier libzt，原 BSL 1.1 且 `Change Date: 2026-01-01` 到期自动转为 Apache License 2.0，原许可文本见 `app/libs/libzt-LICENSE.txt`；内含 `com.zerotier.sockets.*` 与 `arm64-v8a` / `armeabi-v7a` 两份 `libzt.so`）
- **声明权限**：仅 `android.permission.INTERNET`、`android.permission.ACCESS_NETWORK_STATE`；**无 `VpnService`**，不占用系统 VPN 位；`android:allowBackup="false"`，禁止系统云备份同步本地凭据。

## 2. 核心功能与源码结构

- `app/src/main/java/com/shizuku/wolwake/data/WolConfig.kt` & `SettingsStore.kt`
  - 本地 `SharedPreferences`（`wolwake_prefs`）持久化配置。
  - `ztSshHost`：路由器 ZeroTier IP，供内置 libzt 路径使用；`sshHost`：普通 SSH 地址，供系统网络/普通路径使用，**两者用途分离，不得混用**。
- `app/src/main/java/com/shizuku/wolwake/net/ZeroTierManager.kt`
  - 管理内置 ZeroTier 节点启动、网络切换、40 位正整数 `getId()` 门闩（防止 `ZTS_ERR_SERVICE` 负值错误码击穿门闩）、`ensureReady`、仅 `leave()` 停止逻辑与本地诊断日志（默认关闭；`logSnapshot` 在关闭时直接早退，开启时对 `zts_core_query_*` 加 `zts_core_lock_obtain/release` 锁；写入时全局过滤 SSH 密码与 MAC，导出时自动脱敏 IP、主机地址及 ZeroTier ID）。
- `app/src/main/java/com/shizuku/wolwake/net/ZeroTierSocketAdapter.kt`
  - 实现 JSch `SocketFactory`（`ZeroTierSocketFactory`）与 `java.net.Socket` 适配（`ZeroTierSocketAdapter`），包含 UDP 预热、最多 4 次 TCP 连接重试、`_isConnected` 状态同步、按调用隔离的取消信号、无并发 `lwip_close` 的单次延迟关闭机制，以及安全流包装（200ms 读分片防 ART JNI 临界区阻塞、非零 offset 切片写防 `zts_bsd_write_offset` 崩溃）。
- `app/src/main/java/com/shizuku/wolwake/net/SshWake.kt`
  - 内置 ZeroTier 路径（`wakeViaZeroTier`）与普通 SSH 路径（`wake`），以及经路由器检测电脑在线状态（`checkPcViaRouter`，`ip neigh` + `ping`）。
  - 日志脱敏（`sanitizeForLog`）：过滤 SSH 密码与各种格式 MAC。
- `app/src/main/java/com/shizuku/wolwake/net/WolSender.kt` & `PingCheck.kt`
  - 本地 UDP 魔法包发送（默认连发 3 次）与本地在线状态探测（`isReachable` + `ping` + TCP 445/3389/135 探测）。
- `app/src/main/java/com/shizuku/wolwake/WakeViewModel.kt`
  - 一键唤醒（优先内置 libzt SSH 执行 `etherwake -i br-lan {MAC}`，失败自动回落普通 SSH，同时发送本地魔法包）、只发魔法包、只用 SSH 唤醒、唤醒停止（`cancelWake` 后台线程断开）、状态检测（优先路由器检测，回落本地探测）、内置 ZeroTier 开关与重连。
- `app/src/main/java/com/shizuku/wolwake/MainActivity.kt`
  - 首页：主按钮（唤醒中再点停止）、设备状态卡片（电脑在线绿色 `#2E7D32`，离线/未知灰色）、内置 ZeroTier 开关与重连、敏感信息同格式打码与点击切换显隐（`maskPreservingFormat` / `MaskedStatusRow` / `maskSensitiveText`）。
  - 设置页：完整配置项、TopAppBar 返回按钮与系统返回键（`BackHandler`）返回首页、诊断日志开关（默认关闭）与脱敏复制/清空功能。
- `app/src/main/java/com/shizuku/wolwake/widget/WakeWidgetProvider.kt` & `WakeReceiver.kt`
  - 桌面一键唤醒小部件（点击图标发广播后台唤醒，带 8.5 秒超时预算防 Broadcast ANR；点击卡片打开 App）。

## 3. 不可破坏的硬约束

1. **禁止引入 `VpnService` 模式**：`libzt` 仅服务 App 自己的 socket，整机/浏览器流量由官方 ZeroTier One 处理。
2. **直连路由器 ZeroTier IP**：`libzt` 不安装 ZeroTier Central 的 managed route，内置路必须直连路由器 ZeroTier IP（`ztSshHost`），不能指望经 Central 路由访问 LAN IP。
3. **路由器 SSH 监听要求**：路由器 SSH 服务必须监听 ZeroTier 接口地址；若只监听 LAN 地址，内置路必然失败。
4. **严禁调用 `ZeroTierNode.stop()` 或 `free()`**：从 Kotlin/Java 调用会触发 libzt JNI 的 `DetachCurrentThread` abort 崩溃；停止/关闭节点**只能调用 `leave()`**，节点常驻进程后台。
5. **保留合法 `getId()` 等待门**：`join()` 和 `leave()` 调用前必须确认 `node.id` 为合法 40 位正整数（`1L..0xFFFFFFFFFFL`，启动后最多等待 5 秒），防止 `start()` 后返回 `0` 或负值错误码导致空指针崩溃；`zts_core_query_*` 必须配对 `zts_core_lock_obtain/release`。
6. **ZeroTier 路径 JSch 必须使用无参 `session.connect()`**：带 `timeout` 参数的 `session.connect(timeout)` 在 JSch 中会绕过自定义 `SocketFactory`。
7. **诊断日志安全要求**：默认关闭（`diagLogEnabled = false`），绝不得记录 SSH 密码或完整 MAC 地址；复制/导出日志时自动脱敏 IP、主机地址及 ZeroTier ID。
8. **UI 交互保留项**：保留设置页返回首页（顶部返回按钮 + `BackHandler`）、首页内置 ZeroTier 开关、唤醒中点按主按钮停止、电脑在线绿色/离线未知灰色、首页敏感信息同格式打码点击显隐切换。

## 4. 签名与构建说明

- **签名证书 SHA-256**：`de04de1284743a5e363ee72fb7865462b8d18f775b1eb59a35a659cd000df7c9`
  - 后续版本必须继续使用同一签名证书，换证书会导致无法覆盖安装并丢失本机设置。
- **keystore 与密码管理**：
  - 仓库**不包含**任何 keystore 文件，禁止提交 `*.jks` / `*.keystore` 或密码到 GitHub。
  - 构建 Release 签名包时通过环境变量传入：
    - `WOL_KEYSTORE_PATH`（可选，默认读取项目根目录 `wol-release.jks`）
    - `WOL_KEY_ALIAS`（可选，默认 `wolwake`）
    - `WOL_STORE_PASSWORD`
    - `WOL_KEY_PASSWORD`
- **构建与验证命令**：
  ```bash
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  ./gradlew assembleDebug
  # 校验 APK 内 libzt.so
  unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libzt.so
  ```
- **发版检查项**：
  - 递增 `versionCode` / `versionName`，保持 `applicationId` 和签名证书不变；
  - 确认 APK 包含 `lib/arm64-v8a/libzt.so` 与 `lib/armeabi-v7a/libzt.so`；
  - 真机重点验证：内置 ZeroTier 唤醒、普通 SSH 回落、唤醒停止、状态检测、首页打码切换、设置页返回首页。
