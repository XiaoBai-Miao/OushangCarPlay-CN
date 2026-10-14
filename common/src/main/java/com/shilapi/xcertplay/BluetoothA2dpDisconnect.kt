package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ★ v79: 断开**车机侧**的 A2DP 音频会话，用来缓解"手机蓝牙音乐 + CarPlay 音频"双焦点。
 *
 * ## ★★★ profile id 必须以实车 logcat 为准，SDK 常量会骗人
 *
 * v76~v78 三版都用 `BluetoothProfile.A2DP` / `HEADSET`，`javap` 坐实它们是 2 / 1，
 * 但这台 B561 的真实链路是 **profile=11（A2DP_SINK）** 和 **profile=16（厂商 HFP）**。
 * 断profile=2 时蓝牙栈直接回`Ignored disconnect request ... no state machine`，
 * 而我们自己的日志却打 "disconnect requested" —— **静默无效三版**。
 *
 * ## 上游没有这个功能（已核查）
 *
 * 上游 DiPlay 全仓对 A2DP 只有两处引用：[android.bluetooth.BluetoothA2dp] 的 import，
 * 加上 `CarPlayController.connectedBluetoothDevices()` 里的一行读取 ——
 * 用途是**给候选 iPhone 排序**（优先选正连着 A2DP/HEADSET 的那台），不是断开。
 * `adapter.disable()` / `A2dp.disconnect()` 在上游**零出现**。
 *
 * ## 为什么只断 A2DP，不关蓝牙
 *
 * 车机蓝牙同时承担三件事，断任何一件都会坏：
 * - **A2DP_SINK**（立体声音乐，车机当音箱）← 唯一要断的
 * - **HFP / 厂商 profile=16**（通话、通话音频）← 通常要留
 * - **iAP2 配对与重连**（`CarPlayController.connectWirelessBluetoothDevices`）← 必须留
 *
 * 所以走 `profile.disconnect(device)` 而不是 `BluetoothAdapter.disable()`。
 * 关蓝牙会让 iPhone 断开后车机**拉不起会话**，只能用户手动重连。
 *
 * ## 与 iAP2 选机逻辑的关系（不是 bug，是已知的副作用）
 *
 * `CarPlayController.selectWirelessBluetoothDevices()` 会优先挑当前连着 A2DP 的 iPhone。
 * 断开 A2DP 之后这个排序会退化到按地址排。首次配对（此时还没建会话）不受影响，
 * 但如果用户有多台 iPhone 且已配对过，重连时可能挑错 —— 所以这是个**开关**，
 * 默认关闭，让用户自己判断。
 *
 * ## 权限
 *
 * 上游 manifest 已声明 `BLUETOOTH_CONNECT` 与 `BLUETOOTH_ADMIN`（`maxSdkVersion=30`），
 * B561 是 API 28，两者都生效；`disconnect()` 属于 `BLUETOOTH_CONNECT` 运行时权限，
 * 平台签名 + `android.uid.system` 下可直接授予。
 *
 * ## 线程
 *
 * `getProfileProxy` 是异步的且回调在 binder 线程，这里用 `CountDownLatch` 等它回来
 * （与 `CarPlayController.connectedBluetoothDevices` 同一套写法）。
 * **必须在后台线程调用** —— 主线程调`await` 会 ANR。
 */
internal object BluetoothA2dpDisconnect {
    private const val TAG = "DiPlay-A2dp"
    private const val PROFILE_TIMEOUT_SECONDS = 3L

    /**
     * ★ v76: 本对象要断的 profile 集合。
     *
     * ## 为什么是「A2DP_SINK + HFP」而不是 public SDK 的 A2DP + HEADSET
     *
     * 用户要的是「断开手机与车的蓝牙连接」—— 手机连着车机时，音乐（A2DP Sink）
     * 与通话（HFP）是**两条独立链路**，只断一条时手机仍与车机保持蓝牙连接、
     * 铃声还在，听起来「没断开」。
     *
     * ## ★★ v79 实车打脸：v76~v78 的 profile id 全是错的
     *
     * 之前三版一直用 `BluetoothProfile.A2DP` / `BluetoothProfile.HEADSET`，
     * `javap` 坐实它们是 **2 / 1** —— 分别是 A2DP **Source** 与标准 HFP。
     * 这台 B561 的真实链路是：
     * ```
     * profile=11  A2DP_SINK   车机当音箱收手机音乐   ← 要断的是这个
     * profile=16  厂商 HFP   com.chinatsp.phone.bt.profile.HfpProfile
     * ```
     * 断 profile=2 时蓝牙栈直接回：
     * `E A2dpService: Ignored disconnect request ... : no state machine`
     * —— 栈里没有这条链路，请求被丢弃，我们自己的日志却打「disconnect requested」。
     * **静默无效。** 详见 [Profile] 各枚举项注释。
     *
     * ## 为什么**不能**用 `BluetoothAdapter.disable()`
     *
     * 关适配器会一并断掉 iAP2 配对与重连通道
     * （`CarPlayController.connectWirelessBluetoothDevices` 靠它），
     * 手机断开后车机**拉不起会话**，只能用户手动重连 ——
     * 这正是"断蓝牙"最容易踩的坑。逐 profile 断开只动音频链路。
     *
     * ## 为什么不列 AVRCP
     *
     * public SDK 的 `BluetoothProfile` 里没有 AVRCP 常量；AOSP 的
     * `AvrcpProfile.ID = 16` 与这台车机的 HFP 撞号（厂商自己分配的 id 空间
     * 不遵循 AOSP），所以不硬塞 —— 遥控链路跟着 A2DP_SINK 一起断即可。
     */
    enum class Profile(val id: Int, val label: String) {
        /**
         * ★★ v79: **这才是断手机蓝牙音乐的那条链路。**
         *
         * AOSP 里 `A2dpSinkProfile.ID = 11`。车机当**音箱**、手机当音源，
         * 手机音乐 → 车机喇叭走的就是它。
         *
         * ## v76→v78 为什么一直断不掉（实车日志打脸）
         *
         * v76~v78 用的是 `BluetoothProfile.A2DP`，而 `javap` 坐实它的值是 **2**
         * —— 那是 `A2dpProfile.ID`，即 A2DP **Source** 角色（车机当音源往外放）。
         * 这台车机作为 Sink，根本没有 Source 链路。实测日志里蓝牙栈自己打脸：
         * ```
         * E A2dpService: Ignored disconnect request for 0C:6F:8B:1C:CC:CD : no state machine
         * ```
         * 「no state machine」= 栈里压根没有这条链路，请求被直接丢弃。
         * 所以 v76~v78 三版点「立即断开」都是**静默无效**，日志里我们自己的
         * `Log.i` 还打 "disconnect requested"，看起来像成功了。
         *
         * ## 证据链（日志_10月10日19时04分29秒007.log）
         *
         * | 日志行 | 次数 | 含义 |
         * |---|---|---|
         * | `AdapterProperties: PROFILE_CONNECTION_STATE_CHANGE: profile=11, 2 -> 3` | 1 | A2DP_SINK 已连接→断开中 |
         * | `CachedBluetoothDevice: onProfileStateChanged: profile A2DPSink` | 2 | ★ profile=11 就是 A2DP_SINK |
         * | `A2dpSinkService: disconnect for device 0C:6F:8B:1C:CC:CD` | — | 手动断开时真正被断的是 Sink |
         *
         * 11 不在 public SDK（`A2dpSinkProfile` 是 hidden 类），但
         * `BluetoothAdapter.getProfileProxy()` 接受任意 int profile id，
         * 所以直接用字面量即可，不需要反射也不需要 hidden 符号。
         */
        A2DP_SINK(11, "A2DP_SINK"),

        /** A2DP Source 侧（车机当音源）。本车机通常没有这条，留着无害。 */
        A2DP_SDK(BluetoothProfile.A2DP, "A2DP"),

        /** 通话 / 语音。见下面 HFP 的说明。 */
        HEADSET(BluetoothProfile.HEADSET, "HEADSET"),

        /**
         * ★ v79: **这台车机的 HFP 实际 profile id 是 16，不是 1。**
         *
         * `javap` 确认 `BluetoothProfile.HEADSET = 1`（AOSP `HeadsetProfile.ID`）。
         * 但 B561 厂商把 HFP 注册在 **profile=16**：
         * ```
         * E BluetoothPhoneStub:getConnectionState: profile=16, address=0C:6F:8B:1C:CC:CD
         * E Proxy:getConnectionState: profile=16, ...,
         *     feature=com.chinatsp.phone.bt.profile.HfpProfile@b5
         * ```
         * 厂商实现类叫 `HfpProfile`（在 `com.chinatsp.phone.bt.profile` 包下），
         * id 由厂商分配。所以断 HEADSET(=1) 在这台车上是断不到通话链路的。
         *
         * 与 A2DP_SINK 同理，用字面量 16。
         */
        HFP(16, "HFP"),
    }

    /**
     * ★ v79: 「彻底断开」用的 profile 名单 —— **按实车日志实测 ID，不是按 SDK 常量**。
     *
     * 顺序说明：A2DP_SINK 在最前（手机音乐是双焦点的首要目标），
     * 其次 HFP（铃声/通话），最后才是两个可能压根不存在的
     * （A2DP_SDK / HEADSET）—— 它们留着是为了兼容别的 ROM 变体。
     *
     * ## v78 的教训：SDK 常量不等于 ROM 实际值
     *
     * v76~v78 一直用 `BluetoothProfile.A2DP` / `HEADSET`，两个都断错了角色/ID，
     * 实车上表现为「点了没反应」。结论在这台车上：
     * **profile id 必须以 logcat 的 `PROFILE_CONNECTION_STATE_CHANGE: profile=N` 为准。**
     */
    val ALL_PROFILES: List<Profile> = listOf(
        Profile.A2DP_SINK,
        Profile.HFP,
        Profile.A2DP_SDK,
        Profile.HEADSET,
    )

    /** 单次操作的结果，供调用方写进诊断日志。 */
    enum class Outcome {
        /** 没有连着 A2DP 的设备，本来就没什么可断的。 */
        NOTHING_CONNECTED,

        /** 至少断开了一台。 */
        DISCONNECTED,

        /** 拿不到 A2DP profile（适配器关闭、权限缺失、profile 不支持）。 */
        PROFILE_UNAVAILABLE,

        /** 连上了 profile 但 `disconnect()` 抛异常 —— 多半是权限被运行时撤销。 */
        FAILED,
    }

    /**
     * ★ v77: 「彻底断开」—— 断开**当前实际连着的所有蓝牙链路**。
     *
     * ## 与 [disconnect] 的区别
     *
     * [disconnect] 按 profile 名单断（见 [ALL_PROFILES]），
     * 漏了名单外的 profile；本方法不问 profile，只问
     * **「现在有哪些设备连着」**（[BluetoothAdapter.getConnectedDevices]），
     * 然后对每台调 [BluetoothDevice.disconnect] —— 让系统自己决定断哪些链路。
     * 这样不受profile id 的 ROM 差异影响，也不会漏掉厂商私有 profile。
     *
     * ## 仍然**不关适配器**
     *
     * 关掉适配器会连 iAP2 配对与重连一起断，车机拉不起会话。
     * 本方法只断「已建立的连接」，配对关系（bond）保留 ——
     * 所以下次 CarPlay 仍能自动重连，只是不会有多余的音频链路抢车机喇叭。
     *
     * 副作用（预期内）：手机可能自动重连这些链路。
     * 部分车机在 A2DP 被断后会立刻尝试重连 —— 那属于手机侧行为，
     * 车机这边只能断一次；日志里能看到两次请求。
     *
     * @param onResult 每台设备 (地址, 是否成功) 的回调（binder 线程）。
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT 已由 manifest 声明
    fun disconnectAllLinks(
        context: Context,
        onResult: (address: String, ok: Boolean) -> Unit = { _, _ -> },
    ) {
        val manager = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
        if (manager == null) {
            Log.w(TAG, "Bluetooth manager unavailable")
            return
        }
        // 局部名不叫 adapter：这个文件里 companion 里已有同名的隐式引用，
        // 沿用adapter 会让Kotlin 解析到别的东西（曾推断成 String）。
        val btAdapter: BluetoothAdapter = manager.adapter ?: run {
            Log.w(TAG, "Bluetooth adapter unavailable")
            return
        }
        val names = runCatching { bondedNames(btAdapter) }.getOrDefault(emptyMap<String, String>())
        // ★★ v78: `BluetoothAdapter.getConnectedDevices()` 在这台 B561 上**根本不存在**。
        //
        // 实车日志证据（19:02:01）：
        //   W DiPlay-A2dp: BluetoothAdapter.getConnectedDevices() unavailable
        //   W DiPlay-A2dp: java.lang.NoSuchMethodException: getConnectedDevices []
        //
        // 不是 hidden API 的可见性问题，是厂商蓝牙栈**实现里就没有这个方法**（public
        // android.jar 里也没有该符号，可交叉验证）。所以反射必然失败。
        //
        // 因此这里只把聚合视图当作「有就用」的优化，拿不到**必须走回退**——
        // v77 在这里直接 return，把回退路径整个跳过了，导致「彻底断开」形同虚设。
        val devices: Set<BluetoothDevice> = connectedDevicesReflectively(btAdapter)
        if (devices.isEmpty()) {
            Log.i(TAG, "disconnectAllLinks: no aggregate view on this ROM; using per-profile fallback")
            // ★ v79: 名单已换成实测 profile id（A2DP_SINK=11 / HFP=16）。
            //   v76~v78 这里传的是 BluetoothProfile.A2DP(=2) 与 HEADSET(=1)，
            //   两个都不是这台车机的真实链路 —— 栈回 "no state machine" 静默丢弃。
            disconnect(context, ALL_PROFILES) { address, profile, r -> onResult(address, r) }
            return
        }
        var ok = 0
        var needFallback = false
        for (device in devices) {
            // BluetoothDevice.disconnect() 是 **@hide API**，SDK 里没有编译期符号。
            // 它断的是该设备**全部已建立链路** —— 正是「彻底」要的语义。
            // 不同 ROM 对 hidden API 的可见性/实现不一致，逐个兜住失败。
            val succeeded = runCatching {
                device.javaClass.getMethod("disconnect").invoke(device)
                true
            }.getOrElse { error ->
                Log.w(TAG, "BluetoothDevice.disconnect() unavailable on this ROM", error)
                false
            }
            if (succeeded) ok++ else needFallback = true
            onResult(device.address, succeeded)
            Log.i(
                TAG,
                "disconnectAllLinks: ${names[device.address] ?: device.address} " +
                    if (succeeded) "requested" else "deferred to profile fallback",
            )
        }

        // 回退路径：该ROM 不给 BluetoothDevice.disconnect 时，改走按 profile 断 ——
        // 至少保证 A2DP + HEADSET 断得掉（覆盖绝大多数「双焦点」场景）。
        if (needFallback) {
            Log.i(TAG, "disconnectAllLinks: falling back to per-profile disconnect")
            disconnect(context, ALL_PROFILES) { address, profile, r ->
                onResult(address, r)
            }
        }
        Log.i(TAG, "disconnectAllLinks: ${devices.size} device(s), $ok via hidden API")
    }

    /**
     * ★ v76: 按 profile 列表断开所有连着的设备。**阻塞**，只能在后台线程调用。
     *
     * 逐 profile 独立获取代理 —— 一个 profile 拿不到不影响另一个。
     * 少一个「因为 HEADSET 不支持就整条放弃」的失败模式：
     * 部分车机的通话profile 是 `HEADSET_WIFI`，id 对不上，
     * 那时 A2DP 仍应被断开。
     *
     * @param profiles 要断的 profile。传空列表则什么都不做。
     * @param onResult 每个 (设备地址, profile 名, 是否成功) 的回调（binder 线程）。
     */
    fun disconnect(
        context: Context,
        profiles: List<Profile>,
        onResult: (address: String, profile: String, ok: Boolean) -> Unit = { _, _, _ -> },
    ) {
        if (profiles.isEmpty()) return
        val manager = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
        val adapter = runCatching { manager?.adapter }.getOrNull()
        if (manager == null || adapter == null) {
            Log.w(TAG, "Bluetooth adapter unavailable")
            return
        }

        // 设备名只解析一次：getName() 也要 binder 往返，逐设备逐 profile 调太慢。
        val names = runCatching { bondedNames(adapter) }.getOrDefault(emptyMap())

        var anyDisconnected = false
        for (profile in profiles) {
            val proxy = awaitProfile(context, adapter, profile.id) ?: run {
                Log.w(TAG, "${profile.label} profile proxy unavailable (id=${profile.id})")
                continue
            }
            try {
                val devices = runCatching { proxy.connectedDevices.orEmpty() }.getOrDefault(emptySet())
                if (devices.isEmpty()) {
                    // ★ v79: 这一行是「静默无效」的诊断点。
                    // profile 拿得到代理但没有设备 = 这条 profile 本机没建链路。
                    // v76~v78 缺这条日志，所以 profile id 写错三版都没人发现。
                    Log.i(TAG, "${profile.label} (id=${profile.id}): 0 connected device — skipped")
                    continue
                }
                for (device in devices) {
                    val ok = runCatching {
                        proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                            .invoke(proxy, device)
                        true
                    }.getOrElse { error ->
                        Log.w(TAG, "${profile.label} disconnect refused for ${device.address}", error)
                        false
                    }
                    anyDisconnected = anyDisconnected || ok
                    onResult(device.address, profile.label, ok)
                    Log.i(
                        TAG,
                        "${profile.label} (id=${profile.id}) ${if (ok) "disconnect requested" else "refused"} " +
                            "for ${names[device.address] ?: device.address}",
                    )
                }
            } finally {
                runCatching { adapter.closeProfileProxy(profile.id, proxy) }
            }
        }
        Log.i(TAG, "disconnect outcome=${if (anyDisconnected) "DISCONNECTED" else "NOTHING_CONNECTED"}")
    }

    /**
     * 已配对设备的地址 → 名称映射。
     *
     * 用 [BluetoothAdapter.getBondedDevices] 而不是 A2DP 的 connectedDevices：
     * 断开动作要报的是「哪台手机」，而 HEADSET 没连上时A2DP 那边可能已有结果，
     * 两者不一定同时存在。
     */
    /**
     * ★ v77:反射调 `BluetoothAdapter.getConnectedDevices()`。
     *
     * ## 为什么必须反射（已核查 android-37.0 的 android.jar）
     *
     * public SDK 的 [BluetoothAdapter] 只暴露 `getBondedDevices()` 与
     * `getMaxConnectedAudioDevices()` —— `getConnectedDevices()` 带
     * `@SystemApi`，**编译期没有这个符号**（写了直接报 unresolved reference）。
     *
     * 它的语义正是我们要的：所有 profile（A2DP / HEADSET / AVRCP / 厂商私有）
     * 的**已建立链路**聚合视图，不含「已配对但没连」的设备。
     *
     * 拿不到就返回空集 —— 上层会回退到按 profile 断A2DP + HEADSET。
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT 已由 manifest 声明
    private fun connectedDevicesReflectively(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        runCatching {
            val method = adapter.javaClass.getMethod("getConnectedDevices")
            @Suppress("UNCHECKED_CAST")
            method.invoke(adapter) as? Set<BluetoothDevice> ?: emptySet()
        }.getOrElse { error ->
            Log.w(TAG, "BluetoothAdapter.getConnectedDevices() unavailable", error)
            emptySet()
        }

    @SuppressLint("MissingPermission")
    private fun bondedNames(adapter: BluetoothAdapter): Map<String, String> =
        runCatching {
            adapter.bondedDevices.orEmpty().mapNotNull { device ->
                val name = runCatching { device.name }.getOrNull()
                if (name.isNullOrEmpty()) null else device.address to name
            }.toMap()
        }.getOrDefault(emptyMap())

    /**
     * v76: 泛化的 profile 代理获取（A2DP / HEADSET 都要用）。
     *
     * 返回 [BluetoothProfile] 而不是 [BluetoothA2dp]，因为调用方要对
     * 两种 profile 走同一套反射 `disconnect()`，而反射只需要 profile 接口。
     *
     * 用 latch 而非回调 —— `getProfileProxy` 的回调在 binder 线程，
     * 主线程等它会 ANR。沿用 `CarPlayController.connectedBluetoothDevices` 的写法。
     *
     * @param context 必须传：Android 9（API 28）上**没有**两参数的
     *   `getProfileProxy(listener, profile)` 重载，只有带 Context 的那个。
     */
    @SuppressLint("MissingPermission")
    private fun awaitProfile(context: Context, adapter: BluetoothAdapter, profileId: Int): BluetoothProfile? {
        val latch = CountDownLatch(1)
        var proxy: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, service: BluetoothProfile) {
                try {
                    proxy = service
                } finally {
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        val granted = runCatching { adapter.getProfileProxy(context, listener, profileId) }
            .getOrElse { error ->
                Log.w(TAG, "getProfileProxy($profileId) failed", error)
                false
            }
        if (!granted) return null
        if (!latch.await(PROFILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out connecting profile $profileId")
            return null
        }
        return proxy
    }

    /**
     * v76: 某个 profile 上当前连着的设备（地址 → 设备名）。**阻塞**。
     *
     * 菜单与日志里要显示「当前连着哪几台」时用这个，比裸地址对用户友好。
     */
    @SuppressLint("MissingPermission")
    fun connectedDevices(context: Context, profile: Profile): List<Pair<String, String?>> {
        val manager = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
        val adapter = runCatching { manager?.adapter }.getOrNull() ?: return emptyList()
        val names = runCatching { bondedNames(adapter) }.getOrDefault(emptyMap())
        val proxy = awaitProfile(context, adapter, profile.id) ?: return emptyList()
        return try {
            runCatching {
                proxy.connectedDevices.orEmpty().map { it.address to names[it.address] }
            }.getOrDefault(emptyList())
        } finally {
            runCatching { adapter.closeProfileProxy(profile.id, proxy) }
        }
    }
}
