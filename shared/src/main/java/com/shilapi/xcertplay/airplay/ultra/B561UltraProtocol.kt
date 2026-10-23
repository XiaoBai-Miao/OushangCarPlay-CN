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

    override fun forSession(): AirPlayProtocolExtension =
        B561UltraProtocol(sample, clock, trace)

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
        // ★ v126: displayPanels —— 这段是 **iPhone 决定「Ultra 仪表画在哪」的唯一依据**。
        //
        // v125 这里只把每个 display 抄成一个 panel，properties 恒为空、
        // 没有 displayPlugins、也没有虚拟 cluster panel —— 结果 iPhone 收到
        // vehicleStateProtocol 声明后虽然开了 RCS/CAF 通道，却**不知道要把
        // 仪表画到哪块屏**，于是官方仪表出不来。
        //
        // 正确形态（照 LIVI 的 info.rs:add_display_panels）：
        // ① 每个真实 display 一个 panel，uid = "<uuid>-panel"；
        // ② **仪表屏**（type=111）的 panel 带 properties=["showsInstruments"]，
        //    但**不能**带 displayPlugins —— 插件会把该屏拉黑
        //    （LIVI 注释原文："a plug-in would black out the cluster"）；
        // ③ 额外追加一个**虚拟 cluster panel**：没有视频流、带 showsInstruments、
        //    带 displayPlugins 指向我们的 CAF plugin —— iPhone 在这块虚拟 panel 上
        //    用 CAF 数据画官方仪表。
        val panels = mutableListOf<Map<String, Any?>>()
        (info["displays"] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>().forEach { display ->
                val uuid = display["uuid"] as? String ?: return@forEach
                // type 可能被 plist 解码成 Int 或 Long —— 按数值比，别按类型比。
                val isCluster = (display["type"] as? Number)?.toInt() == CLUSTER_STREAM_TYPE
                panels += linkedMapOf<String, Any?>(
                    "uid" to "$uuid-panel",
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
            }
        // 虚拟 cluster panel：尺寸取仪表屏（没有就取第一块屏）。
        val sized = panels.firstOrNull { panel ->
            (panel["properties"] as? List<*>)?.any { it == SHOWS_INSTRUMENTS } == true
        } ?: panels.firstOrNull()
        if (sized != null) {
            panels += linkedMapOf<String, Any?>(
                "uid" to "$CLUSTER_PANEL_UID-panel",
                "widthPixels" to sized["widthPixels"],
                "heightPixels" to sized["heightPixels"],
                "widthPhysical" to sized["widthPhysical"],
                "heightPhysical" to sized["heightPhysical"],
                "properties" to listOf(SHOWS_INSTRUMENTS),
                // 虚拟 panel 没有视频流 —— 仪表是 CAF 数据画出来的，不是视频。
                "initialVideoStreams" to emptyList<Any?>(),
                "displayPlugins" to listOf(
                    linkedMapOf<String, Any?>(
                        "uid" to "$CLUSTER_PANEL_UID-plugin",
                        "videoStreams" to emptyList<Any?>(),
                    ),
                ),
            )
        }
        info["displayPanels"] = panels
        log(
            "INFO VSP=1 plugin=0 WIRE_REV=$WIRE_REV " +
                "panels=${(info["displayPanels"] as? List<*>)?.size ?: 0} " +
                "sample=${gauges?.primaryRpm}",
        )
    }

    override fun enabledFeatures(proposed: List<*>?): List<String> =
        if (proposed.orEmpty().contains(FEATURE)) listOf(FEATURE) else emptyList()

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
        if (!negotiated || closed.get()) {
            log("RCS rejected: VSP not negotiated or session closed")
            return null
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
     * 由外部（我们的车辆数据层）在读数更新时调用。
     */
    fun onGaugesChanged(value: B561UltraGauges?) {
        if (closed.get()) return
        val previous = gauges
        if (previous == value) return
        gauges = value
        log("VEHICLE rpm=${value?.primaryRpm} power=${value?.powerKw} battery=${value?.batteryPercent}")
        channels.values.forEach { it.publish() }
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

        fun publish() = enqueue { accessory.changed()?.let(::send) }

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
         * 虚拟 cluster panel 的 UUID 家族第三项。
         *
         * 与主屏 `...1111-...0001`、仪表屏 `...2222-...0002` 同族 ——
         * 这是 Apple CarPlay Ultra 的约定（LIVI 的 `info.rs` 也用这一族）。
         * iPhone 靠这个 panel 把 CAF 车辆状态画成官方仪表。
         */
        const val CLUSTER_PANEL_UID = "b7e6c5a0-3333-4000-8000-000000000003"

        /** 仪表屏的 stream type（CarPlay 的 instrument cluster）。 */
        const val CLUSTER_STREAM_TYPE = 111

        /** panel 属性：这一块显示仪表。 */
        const val SHOWS_INSTRUMENTS = "showsInstruments"
    }
}
