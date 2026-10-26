package com.shilapi.xcertplay.airplay.ultra

import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.AirPlayProtocolExtension
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.unsignedPlistDecimal
import com.shilapi.xcertplay.airplay.unsignedPlistInteger
import com.shilapi.xcertplay.airplay.ProtocolTraceFormatter
import com.shilapi.xcertplay.airplay.rcs.RcsDataStream
import com.shilapi.xcertplay.airplay.rcs.RcsDataStreamHandler
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.caf.CafBodyEncoding
import com.shilapi.xcertplay.airplay.rcs.caf.CafCommand
import com.shilapi.xcertplay.airplay.rcs.caf.CafProtocolException
import com.shilapi.xcertplay.airplay.rcs.caf.CafRcsCodec
import com.shilapi.xcertplay.airplay.rcs.caf.CarAccessoryMessages
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec
import com.shilapi.xcertplay.vehicle.B561UltraGauges
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ★ v125 B561: **真** CarPlay Ultra 协议（车辆状态协议 / VSP）。
 *
 * ## 协议栈
 *
 * ```
 * AirPlay RTSP
 *   └─ INFO 装饰：vehicleStateProtocol + vehicleStateProtocolInfo + displayPanels
 *   └─ SETUP 协商 feature "vehicleStateProtocol"
 *   └─ SETUP type=100/130 → AirPlayProtocolData 数据流
 *        └─ RCS 通道（HKDF "DataStream-Salt{seed}" 派生双向密钥）
 *             └─ CAF（Car Accessory Framework）/ Opack 编码
 *                  └─ accessory / service / characteristic 读写
 * ```
 *
 * ## 与自绘仪表盘（v123）的关系
 *
 * v123 是**我们自己**在仪表屏上画表；这一版是**让 iPhone 自己画**。
 * 两者不冲突：iPhone 画的是它那套原生 UI，我们画的是自定义卡片。
 *
 * ## 车辆数据从哪来
 *
 * 走我们已有的 [B561UltraGauges]（v123 的数据模型），数据源是
 * logcat / VHAL 读到的 B561 车辆属性。**读不到的字段不声明**，
 * 绝不给 iPhone 假的 0。
 *
 * ## ★ v130 相对 v126 改了什么
 *
 * 1. **displayPanels 改 LIVI themed 量产结构**：插件挂到集群 panel 自身
 *    （`displayPlugins`，`videoStreams=[集群 uuid]`），删掉虚拟 panel ——
 *    v126 的虚拟 panel 插件 `videoStreams` 为空，插件输出无处可去。
 * 2. **CAF 特征树对齐 LIVI 最小可用集**（见 [B561VehicleStateAccessory]）：
 *    补 navigation / automaker_settings / vehicle_resources / trip_computer /
 *    audio，修 fuel / tires 的 kind。
 * 3. **writeRequest / controlRequest 不再静默丢弃**。
 * 4. **publish 合并 + `CAF ALIVE subs=N` 心跳**：区分「手机没订阅」与
 *    「我们推得慢」，并防高频数据源灌满 worker 队列触发 close。
 *
 * ## 参考
 *
 * - 协议框架：VoyahPlay 的 `VoyahUltraProtocol`（Kotlin）
 * - iid / type 映射：LIVI 的 `vehicle_state.rs`（Rust，开源里最完整的一套）
 */
class B561UltraProtocol(
    /** 当前车辆读数；null = 还没读到。 */
    private val sample: () -> B561UltraGauges?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val trace: (String) -> Unit = {},
) : AirPlayProtocolExtension, Closeable {

    @Volatile private var gauges: B561UltraGauges? = null
    @Volatile private var negotiated = false
    private val closed = AtomicBoolean(false)
    private val channels = ConcurrentHashMap<Long, Channel>()
    // Keep closed CAF identities until this control session ends: TEARDOWN may arrive after EOF.
    private val cafConnections = ConcurrentHashMap.newKeySet<String>()
    private val nextId = AtomicLong(10_000)

    /**
     * ★ v132: 最近一个由 [forSession] 造出的**会话实例**。
     *
     * v131 真车黑屏的根因就在这里：[AirPlaySession] 持有的是 `forSession()`
     * 的产物（decorateInfo / RCS LISTEN / CAF register 全走它），而
     * `CarPlayHostActivity` 的 `onReadingChanged` 推到的是
     * `AirPlayConfig.protocolExtension` 里的**工厂实例**（本对象）——
     * 两者不是同一个东西。结果：会话实例有订阅没数据（零 updateNotify），
     * 工厂实例有数据没订阅（`CAF ALIVE subs=0 channels=0` 刷屏），
     * iPhone 官方仪表拿不到任何数据 → 集群屏全黑。
     *
     * 现在工厂实例把车辆数据转发给活跃会话实例，两边合一。
     */
    @Volatile private var activeSession: B561UltraProtocol? = null

    /** 上次 CAF ALIVE 心跳的时间戳（[clock] 同源）。 */
    private var lastHeartbeat = 0L

    override fun forSession(): AirPlayProtocolExtension =
        B561UltraProtocol(sample, clock, trace).also { activeSession = it }

    override fun decorateInfo(info: MutableMap<String, Any?>) {
        val accessory = newAccessory()
        info["vehicleStateProtocolInfo"] = linkedMapOf(
            "protocolVersion" to "1.0",
            "pluginCount" to 1,
            "pluginConfigs" to listOf(
                linkedMapOf<String, Any?>(
                    "name" to B561VehicleStateAccessory.PLUGIN_NAME,
                    "pluginID" to B561VehicleStateAccessory.PLUGIN_ID,
                ).apply { putAll(accessory.config(hasEngineRpm = true)) },
            ),
            "pluginMapping" to mapOf(
                B561VehicleStateAccessory.PLUGIN_NAME to B561VehicleStateAccessory.PLUGIN_ID,
            ),
        )
        // ★ v130: displayPanels —— 照 LIVI 的 **themed 量产路径**
        // （`info.rs::add_display_panels` 的 `theme_displays = Some(..)` 分支，
        // 测试断言原文："the cluster carries the plug-in, no extra panel"）。
        //
        // v125/v126 抄的是 LIVI 的**非 themed** 分支：插件挂在追加的
        // **虚拟 cluster panel** 上，且 `videoStreams` 为空 —— 插件输出
        // 无处可去（LIVI 对那条路径的注释：a plug-in would black out
        // the cluster）。结果：iPhone 只在集群屏上放 CarPlay 地图流，
        // 官方 Ultra 仪表永远出不来（v126 实车症状）。
        //
        // 量产形态：
        // ① 每个真实 display 一个 panel，uid 用显示名（LIVI 的 theme names）；
        // ② **仪表屏**（type=111）panel 带 properties=["showsInstruments"]，
        //    且 displayPlugins 指向我们的 CAF 插件 —— 插件 uid 与 panel uid
        //    相同，videoStreams=[集群屏自己的 uuid]（iPhone 把 CAF 车辆状态
        //    直接画进集群屏的视频流里）；
        // ③ 主屏 panel 不带插件；
        // ④ **不追加虚拟 panel**。
        val panels = mutableListOf<Map<String, Any?>>()
        (info["displays"] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>().forEach { display ->
                val uuid = display["uuid"] as? String ?: return@forEach
                // type 可能被 plist 解码成 Int 或 Long —— 按数值比，别按类型比。
                val isCluster = (display["type"] as? Number)?.toInt() == CLUSTER_STREAM_TYPE
                // themed 模式 panel uid 是显示名；插件 uid 与 panel uid 相同
                // （LIVI: streams 非空时 plugin_uid = uid）。
                val uid = if (isCluster) CLUSTER_PANEL_UID else MAIN_PANEL_UID
                val panel = linkedMapOf<String, Any?>(
                    "uid" to uid,
                    "widthPixels" to display["widthPixels"],
                    "heightPixels" to display["heightPixels"],
                    "widthPhysical" to display["widthPhysical"],
                    "heightPhysical" to display["heightPhysical"],
                    "properties" to if (isCluster) listOf(SHOWS_INSTRUMENTS) else emptyList<String>(),
                    "initialVideoStreams" to listOf(
                        mapOf(
                            "uuid" to uuid,
                            "originXPixels" to 0,
                            "originYPixels" to 0,
                            "zIndex" to 0,
                        ),
                    ),
                )
                if (isCluster) {
                    panel["displayPlugins"] = listOf(
                        linkedMapOf<String, Any?>(
                            "uid" to uid,
                            "videoStreams" to listOf(uuid),
                        ),
                    )
                }
                panels += panel
            }
        info["displayPanels"] = panels
        log(
            "INFO VSP=1 plugin=0 WIRE_REV=$WIRE_REV " +
                "panels=${(info["displayPanels"] as? List<*>)?.size ?: 0} " +
                "sample=${gauges?.primaryRpm}",
        )
    }

    /**
     * ★ v135: **无条件**把车辆状态能力报给 iPhone。
     *
     * 之前只在 iPhone 的 SETUP `features` 里已经出现 [FEATURE] 时才回它。
     * 实测（10-26 真车，RMX2202 之外的 B561）：iPhone **重新配对过一次**之后，
     * 它在 SETUP 里就不再提 `vehicleStateProtocol`（我们回给它的
     * enabledFeatures 因此少一项，响应体 183→158 字节），于是
     * → `negotiated=false`
     * → 车辆状态数据流（type=130 / RCS）被我们以"VSP 没协商"拒收
     * → iPhone 的 Ultra 侧建不起来，5 秒后它自己发 TEARDOWN
     * → 我们重建整条栈再连 → 又是同样流程 ⇒ **无限重连循环**（用户体感：连接很慢）
     *
     * 参照 LIVI（`livi_stack.rs`）：它的 `enabledFeatures` 同样是按手机提的来，
     * 但**车辆状态数据流只要手机 SETUP 了就接上**
     * （`lane.filter(|_| self.vehicle_state.is_some())`）—— 与 feature 有没有
     * 协商成功无关。iPhone 明确开了 type=130 就说明它要这条通道。
     *
     * 所以这里与 LIVI 对齐：**车机只要提供车辆状态，就把能力声明出去**，
     * 让 iPhone 第一次连接就能拿到 Ultra 通道，也让它从 /info 学到本车机支持
     * 车辆状态协议（`vehicleStateProtocolInfo`，那边本来就是无条件带的）。
     */
    override fun enabledFeatures(proposed: List<*>?): List<String> = listOf(FEATURE)

    override fun onInfoRequest(body: ByteArray) {
        log(
            "INFO REQUEST " + if (body.isEmpty()) "empty" else runCatching {
                ProtocolTraceFormatter.pretty(com.shilapi.xcertplay.airplay.BplistCodec.decode(body))
            }.getOrElse { "decode error: ${it.message}" },
        )
    }

    override fun onFeaturesNegotiated(features: List<String>) {
        negotiated = FEATURE in features
        log("NEGOTIATED VSP=${if (negotiated) 1 else 0}; enabled=${features.joinToString(",")} WIRE_REV=$WIRE_REV")
    }

    override fun openDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = stream["clientTypeUUID"] as? String ?: return null
        val client = RcsClientTypes.findByUuid(uuid) ?: return null
        if (client != RcsClientTypes.CAR_PLAY_PROTOCOL_DATA &&
            client != RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2
        ) {
            return null
        }
        /**
         * ★ v135: **不再因为「VSP 没协商」拒收**车辆状态数据流。
         *
         * 10-26 真车日志坐实：iPhone 明明 SETUP 了 type=130（`airplay SETUP stream
         * type=130 payload={controlType=2, channelID=…-RCS-58, …}`），是我们自己
         * 因为 `negotiated=false` 把它拒了（日志 `RCS rejected: VSP not negotiated`），
         * 结果一条 RCS LISTEN 都没有（10-25 成功那次有两条），iPhone 的 Ultra 侧
         * 建不起来 → 5 秒后 TEARDOWN → 无限重连。
         *
         * 参照 LIVI：车辆状态通道只取决于「车机有没有车辆状态能力」
         * （`self.vehicle_state.is_some()`），与 feature 有没有协商成功无关。
         * 通道的密钥本来就来自配对共享密钥 + seed，跟 feature 无关。
         */
        if (closed.get()) {
            log("RCS rejected: session closed")
            return null
        }
        if (!negotiated) {
            log("RCS accepted without negotiated VSP (LIVI-compatible)")
        }
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
        return openChannel(
            client,
            key("DataStream-Output-Encryption-Key"),
            key("DataStream-Input-Encryption-Key"),
            session.localAddress ?: InetAddress.getByName("::"),
            stream,
        )
    }

    internal fun openChannel(
        client: com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType,
        readKey: ByteArray,
        writeKey: ByteArray,
        address: InetAddress,
        stream: Map<String, Any?>,
    ): Map<String, Any?> {
        check(negotiated && !closed.get()) { "Ultra VSP is not active" }
        require(
            client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA ||
                client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2,
        )
        // 一个 clientType 只保留一个通道：重复 SETUP 会留下野通道。
        channels.entries.toList().filter { it.value.data.clientType == client }
            .forEach { (id, previous) ->
                channels.remove(id, previous)
                previous.close()
            }
        val data = RcsDataStream.open(client, readKey, writeKey, address, ::log)
        val channel = Channel(data, unsignedPlistDecimal(stream["streamConnectionID"]))
        channel.connectionId?.let { cafConnections.add(it) }
        val id = nextId.getAndIncrement()
        val port = try {
            data.listen()
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
        channels[id] = channel
        log("RCS LISTEN client=${client.name} streamID=$id port=$port")
        return linkedMapOf<String, Any?>(
            "type" to 130,
            "streamID" to id,
            "dataPort" to port,
        ).apply {
            stream["streamConnectionID"]?.let { put("streamConnectionID", unsignedPlistInteger(it)) }
        }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        attachPending()
    }

    internal fun attachPending() {
        channels.values.forEach { channel ->
            if (channel.attached.compareAndSet(false, true)) channel.data.attach(channel)
        }
    }

    override fun mediaTeardownTypes(body: Map<String, Any?>?, original: List<Int>?): List<Int>? {
        val entries = body?.get("streams") as? List<*> ?: return original
        if (entries.isEmpty()) return original
        // type=130 里既有 RCS 通道也有 iAP 隧道 —— 只拆属于 RCS 的那些。
        val media = entries.filterNot { value ->
            val entry = value as? Map<*, *> ?: return@filterNot false
            if ((entry["type"] as? Number)?.toInt() != 130) return@filterNot false
            val client = (entry["clientTypeUUID"] as? String)?.let(RcsClientTypes::findByUuid)
            val connection = unsignedPlistDecimal(entry["streamConnectionID"])
            client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA ||
                client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2 ||
                (connection != null && connection in cafConnections)
        }
        if (media.size == entries.size) return original
        return if (media.isEmpty()) {
            emptyList()
        } else {
            com.shilapi.xcertplay.airplay.teardownStreamTypes(mapOf("streams" to media)).orEmpty()
        }
    }

    override fun onTeardown(session: AirPlaySession, body: Map<String, Any?>?) {
        teardownChannels(body)
    }

    internal fun teardownChannels(body: Map<String, Any?>?) {
        val entries = body?.get("streams") as? List<*>
        if (entries.isNullOrEmpty()) {
            closeChannels()
            return
        }
        entries.filterIsInstance<Map<*, *>>().forEach { entry ->
            if ((entry["type"] as? Number)?.toInt() != 130) return@forEach
            val streamId = (entry["streamID"] as? Number)?.toLong()
            val connectionId = unsignedPlistDecimal(entry["streamConnectionID"])
            val uuid = entry["clientTypeUUID"] as? String
            channels.entries.toList().filter { (id, channel) ->
                when {
                    streamId != null -> streamId == id
                    connectionId != null -> connectionId == channel.connectionId
                    uuid != null -> RcsClientTypes.findByUuid(uuid) == channel.data.clientType
                    else -> true
                }
            }.forEach { (id, channel) ->
                channels.remove(id, channel)
                channel.close()
            }
        }
    }

    override fun onSessionClosed(session: AirPlaySession) {
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // 断开的会话不再接收转发（v132）。
        if (activeSession == this) activeSession = null
        closeChannels()
        cafConnections.clear()
        log("SESSION CLOSED")
    }

    private fun closeChannels() {
        channels.entries.toList().forEach { (id, channel) ->
            channels.remove(id, channel)
            channel.close()
        }
    }

    /**
     * 车辆读数变了 → 推给所有通道。
     *
     * 由外部（我们的车辆数据层）在读数更新时调用。**这个调用落在工厂实例上**
     * （`CarPlayHostActivity.ultraProtocolInstance`），真正持有 RCS 通道的是
     * [forSession] 造出的会话实例 —— 所以这里必须转发过去（v132 黑屏修复）。
     */
    fun onGaugesChanged(value: B561UltraGauges?) {
        val session = activeSession
        if (session != null && !session.closed.get()) {
            session.receiveGauges(value)
            return
        }
        // 没有活跃会话：只缓存，等 forSession 后由下次变化带入
        // （register 的 initialValue 预填已经保证 UI 有初始值）。
        gauges = value
    }

    /** 会话实例真正接收车辆数据的入口（v132 从 onGaugesChanged 拆出）。 */
    internal fun receiveGauges(value: B561UltraGauges?) {
        if (closed.get()) return
        val previous = gauges
        if (previous == value) return
        gauges = value
        log(
            "VEHICLE rpm=${value?.primaryRpm} power=${value?.powerKw} " +
                "battery=${value?.batteryPercent}",
        )
        heartbeat()
        channels.values.forEach { it.publish() }
    }

    /**
     * ★ v130: CAF 链路存活心跳 + 零订阅告警。
     *
     * v126 真车日志的教训：iPhone 走完 `configRequest → configResponse`
     * 之后**一条 registerRequest 都没发**，RCS 通道静默到 EOF。
     * 没有订阅时 `changed()` 永远返回 null，车辆数据一条都到不了
     * iPhone —— 而日志上完全看不出「手机没订阅」还是「我们推得慢」。
     *
     * 现在每 5 秒打一条 `CAF ALIVE subs=N`；subs=0 时明确告警
     * （phone registered nothing），下次抓车机日志一眼可辨。
     */
    private fun heartbeat() {
        val now = clock()
        if (now - lastHeartbeat < ALIVE_INTERVAL_MS) return
        lastHeartbeat = now
        val subs = channels.values.sumOf { it.subscribedCount() }
        if (subs == 0) {
            log(
                "CAF ALIVE subs=0 channels=${channels.size} — " +
                    "phone registered nothing; no updateNotify will ever go out",
            )
        } else {
            log("CAF ALIVE subs=$subs channels=${channels.size}")
        }
    }

    private fun newAccessory() = B561VehicleStateAccessory({ gauges })

    /**
     * ★ v126: 日志**同时**写 logcat 与 app 内部诊断日志。
     *
     * 上一版（v125）只走 [trace]，而 HostActivity 的 trace 回调只写
     * `files/logs/diplay.log`（app 私有目录），**不写 logcat**。
     * 结果：装车后用 logcat 抓日志，Ultra/CAF 层的痕迹一条都看不到，
     * 只能看到 AirPlaySession 自己的 debugLog —— 相当于协议层是黑的。
     *
     * 现在多写一份 logcat，tag 用 `xcertplay-usb`（与 AirPlaySession 一致，
     * 便于用同一个 grep 抓）。
     */
    private fun log(text: String) {
        val clipped = text.take(3000)
        runCatching { Log.i(TAG, clipped) }
        runCatching { trace(clipped) }
    }

    private inner class Channel(
        val data: RcsDataStream,
        val connectionId: String?,
    ) : RcsDataStreamHandler, Closeable {
        val attached = AtomicBoolean(false)
        private val stopped = AtomicBoolean(false)
        private val accessory = newAccessory()
        private var encoding = CafBodyEncoding.OPACK

        /** ★ v130: 发布合并标志 —— 见 [publish]。 */
        private val publishPending = AtomicBoolean(false)
        private val worker = ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64),
        ) { runnable ->
            Thread(runnable, "b561-ultra-caf").apply { isDaemon = true }
        }

        override fun onStreamOpened(stream: RcsDataStream) {
            log("RCS CONNECTED client=${stream.clientType.name}")
        }

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            if (message.body.size > 262_144) {
                log("RCS body exceeds the limit")
                close()
                return
            }
            val transport = ApTransportPackageCodec
            if (message.kind == transport.KIND_RPLY) {
                log("CAF transport reply token=${message.token}")
                return
            }
            if (message.kind == transport.KIND_SYNC) data.send(message.reply())
            enqueue {
                if (message.messageType != transport.MESSAGE_TYPE_COMM &&
                    message.messageType != transport.MESSAGE_TYPE_CMND
                ) {
                    throw CafProtocolException("Expected CAF comm/cmnd package")
                }
                encoding = CafRcsCodec.encoding(message.body)
                val request = CarAccessoryMessages.reader(message.body)
                log(
                    "CAF transport kind=${message.kind} token=${message.token} encoding=$encoding",
                )
                log(
                    "CAF RX plugin=${request.pluginId} command=${request.command.wireName} " +
                        "values=${ProtocolTraceFormatter.pretty(request.values())}",
                )
                if (request.command == CafCommand.CONFIG_REQUEST) {
                    val tree = accessory.config(hasEngineRpm = true)["accessories"] as? List<*>
                    log("CAF CONFIG WIRE_REV=$WIRE_REV accessories=${tree?.size ?: 0}")
                }
                accessory.handle(request)?.let(::send)
            }
        }

        /** 当前订阅的 iid 数（诊断用，见 [B561UltraProtocol.heartbeat]）。 */
        fun subscribedCount(): Int = accessory.subscribedIds().size

        /**
         * ★ v130: 合并发布。
         *
         * logcat 字段每秒变多次，`onGaugesChanged` 会连着调 publish ——
         * 旧写法每次都入队，worker 队列（64）一满就
         * `CAF queue full` → close 通道，表现为「数据刷新慢 /
         * 时断时续」。现在用 [AtomicBoolean] 合并：一次 worker 执行把
         * 期间积累的所有变化一次性发出（[B561VehicleStateAccessory.changed]
         * 本身就只报与上次比真的变了的 iid）。
         */
        fun publish() {
            if (stopped.get()) return
            if (publishPending.compareAndSet(false, true)) {
                enqueue {
                    publishPending.set(false)
                    accessory.changed()?.let(::send)
                }
            }
        }

        private fun send(frame: com.shilapi.xcertplay.airplay.rcs.caf.CafRcsFrame) {
            log(
                "CAF TX command=${frame.envelope.message.command.wireName} " +
                    "values=${ProtocolTraceFormatter.pretty(frame.envelope.message.values)} " +
                    "errors=${frame.envelope.message.errors}",
            )
            data.send(CafRcsCodec.encode(frame.envelope, encoding).asRcsMessage())
        }

        private fun enqueue(action: () -> Unit) {
            if (stopped.get()) return
            try {
                worker.execute {
                    runCatching(action).onFailure {
                        log("CAF ERROR ${it.javaClass.simpleName}: ${it.message}")
                        close()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                if (!stopped.get()) {
                    log("CAF queue full")
                    close()
                }
            }
        }

        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            log("RCS CLOSED client=${stream.clientType.name} reason=${cause?.message ?: "EOF"}")
            close()
        }

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            channels.entries.removeIf { it.value === this }
            worker.shutdownNow()
            data.close()
        }
    }

    companion object {
        /** logcat 的 tag —— 与 AirPlaySession 一致，便于用同一个 grep 抓。 */
        const val TAG = "xcertplay-usb"

        /** CarPlay Ultra 的 feature 名。 */
        const val FEATURE = "vehicleStateProtocol"

        /** 线协议版本标识（诊断用）。 */
        const val WIRE_REV = "b561-vsp-vehicle-state"

        /**
         * ★ v130: panel 显示名（LIVI themed 模式的 theme names）。
         *
         * v126 及以前这里放的是 UUID 家族第三项
         * （`b7e6c5a0-3333-...`）—— 那是 LIVI **非 themed** 分支
         * 虚拟 panel 的命名。v130 改走 LIVI 量产 themed 路径：
         * panel uid 用显示名，插件 uid 与 panel uid 相同，
         * videoStreams 指向集群屏自己的 display uuid
         * （`AirPlayInfoPlist` 里的 `...2222-...0002`）。
         */
        const val MAIN_PANEL_UID = "Main_Display"

        /** 集群 panel 显示名（LIVI 同款 "Cluster_Display"）。 */
        const val CLUSTER_PANEL_UID = "Cluster_Display"

        /** 仪表屏的 stream type（CarPlay 的 instrument cluster）。 */
        const val CLUSTER_STREAM_TYPE = 111

        /** panel 属性：这一块显示仪表。 */
        const val SHOWS_INSTRUMENTS = "showsInstruments"

        /** CAF ALIVE 心跳间隔（ms）—— 订阅诊断用。 */
        const val ALIVE_INTERVAL_MS = 5_000L
    }
}
