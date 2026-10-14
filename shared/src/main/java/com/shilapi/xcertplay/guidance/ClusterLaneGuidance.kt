package com.shilapi.xcertplay.guidance

/**
 * B561: 车道级导航（0x5204）+ 路口几何（0x5202 的 0x09/0x0A/0x0B）。
 *
 * ## 这份数据从哪来 —— 为什么"路口放大图"必须自己画
 *
 * iOS **不会**把路口放大图当成图片发过来。CarPlay 的导航平面只有
 * `0x5201`（路线级）、`0x5202`（单条 maneuver）、`0x5204`（车道级）三类消息，
 * 全是 TLV，**没有任何图片/位图字段**。原厂仪表之所以能画出漂亮的路口图，
 * 是因为它拿这些几何数据**自己渲染** —— 这也是 Apple 的设计意图：
 * 车机画什么样式由车机决定。
 *
 * 所以「能不能拿到路口放大图」的准确答案是：
 * **拿不到成品图，但拿得到画它所需的全部几何**，本类就是那份几何。
 *
 * ## 字段编号（对齐 Apple `+[ACCNavigationRouteGuidanceUpdateInfo keyForType:]`）
 *
 * - `0x5201`：`0x10` LaneGuidanceCurrentIndex / `0x11` TotalCount / `0x12` Showing
 * - `0x5202`：`0x09` JunctionType、`0x0A` JunctionElementAngle、`0x0B` JunctionElementExitAngle
 * - `0x5204`：`0x01` LaneGuidanceIndex、`0x02` LaneInformations、`0x03` Description
 *
 * ## 角度约定
 *
 * 车道角度是**有符号度数**（maneuver 几何用半度，车道不用）。
 * `0°` = 直行，`+90°` = 右转，`-90°` = 左转（以车头前进方向为基准，
 * 右侧为正 —— 与 0x5202 的 `0x08 DrivingSide` 独立，不要混用）。
 *
 * 公开可见是因为 `BydNavigationOutputs` 是 public 类，其 setter 的参数就是本类型。
 */
data class ClusterLaneGuidance(
    /** 本帧的 lane 事件序号；换号即换了一组车道数据。 */
    val eventIndex: Int,
    /** 车道数（上限 8，超出按收到的顺序截断）。 */
    val laneCount: Int,
    /** 每条车道的可行驶方向（度）。空 = 该车道没有可画方向。 */
    val angles: List<List<Int>>,
    /** 每条车道的左右位置序号；`null` = iOS 没给（此时按收到顺序排）。 */
    val positions: List<Int?>,
    /** 推荐（应行驶）车道在 [angles] 里的下标；`-1` = 无推荐。 */
    val primaryLane: Int,
    /** iOS 说"该显示车道指引了"（0x5201 的 0x12）。 */
    val showing: Boolean = false,
    /** 路口类型（0x5202 的 0x09）。0 = 普通路口，其余见 [junctionKind]。 */
    val junctionType: Int = 0,
    /**
     * 路口各岔路的方位角（0x5202 的 0x0A，单位度）。
     *
     * 这就是"路口放大图"里那几条辐射线的角度 —— 有了它才画得出
     * "从主路拐进第几条岔路"的形状，而不是只有一个孤零零的箭头。
     */
    val junctionAngles: List<Int> = emptyList(),
    /** 出口/匝道的额外偏角（0x5202 的 0x0B，有符号）。 */
    val junctionExitAngle: Int? = null,
    /**
     * B561: 这份车道数据是用哪套布局解出来的。
     *
     * 两条路径都要求"字节正好用完容器"，所以正常情况下只有一条会成功；
     * 记录它是为了让实车日志能**一眼定论**到底是哪套对 ——
     * 拿到 `laneSource=flat` 就说明严格布局错了，该按扁平重写。
     */
    val source: LaneSource = LaneSource.STRICT,
) {
    /**
     * 有没有可画的车道内容。什么都没有时整块不画（宁缺勿画）。
     *
     * ★ **不看 [showing]** —— 显隐的门在 [RouteGuidanceState.currentLaneGuidance]
     *   （那里才有 0x5201 的 `0x12`）。在这里再判一次会出现"状态说该显示、
     *   但事件自己说没显示"的矛盾，结果永远是空卡。
     */
    val drawable: Boolean
        get() = laneCount > 0 && angles.any { it.isNotEmpty() }

    /** 路口类型的可读标签，交给 UI 层本地化。 */
    val junctionKind: JunctionKind
        get() = JunctionKind.of(junctionType)

    /** 车道数据是否可信：角度必须是有限度数，否则画出来是乱线。 */
    val sane: Boolean
        get() = angles.all { lane -> lane.all { it in -180..180 } }

    /**
     * B561: `0x5204` 的两套互斥解读。
     *
     * ## 为什么必须留两条
     *
     * 同一个消息 ID，两套字段语义**完全对立**，且都有各自的证据来源：
     *
     * | 字段 | [STRICT] | [FLAT] |
     * |---|---|---|
     * | `0x01` | `LaneGuidanceIndex` **u16** 事件序号 | `laneHighlight` **u8** 推荐车道下标 |
     * | `0x02` | 车道记录容器（6 字节定长头 + 变长角度） | **一个 u8** = 一条车道方向 |
     * | `0x03` | `Description`（文本） | **剩余全部字节** = 更多车道方向 |
     * | 方向 | i16 有符号度数（0 直 / +90 右 / −90 左） | 枚举 `0 不用 / 1 直 / 2 左 / 3 右` |
     *
     * - [STRICT] 依据：`accessoryd` 反编译 + Apple 字段枚举 + 渲染批 `CMD_LANES_LANE`
     *   里 `position` 占 2 字节。**没有 0x5204 的实车抓包。**
     * - [FLAT] 依据：DiPlay-CN 分支在实车（2025 BYD 汉 DM-i）上写出来的实现。
     *   但他们在 cn.4 把车道条整个删掉了（用户主动要求，不是验证后发现画错），
     *   所以**也没有"画对了"的证据**。
     *
     * 两条路径都强制"字节正好用完容器"，所以正常只有一条能成功。
     * 首次解析失败时 [dump] 会打印原始十六进制；实车跑一次看
     * `laneSource=` 就能定论，之后删掉错的那条。
     */
    enum class LaneSource {
        /** 定长头 + i16 有符号角度（本项目首选）。 */
        STRICT,

        /** 每车道一个 u8 方向枚举（DiPlay-CN 解读）。 */
        FLAT,
    }

    enum class JunctionKind(val raw: Int) {        /** 普通路口。 */
        NONE(0),

        /** 环岛。 */
        ROUNDABOUT(1),

        /** 高速/快速路出口。 */
        EXIT(2),

        /** 分叉口（同向多路）。 */
        FORK(3);

        companion object {
            fun of(raw: Int): JunctionKind = entries.firstOrNull { it.raw == raw } ?: NONE
        }
    }

    companion object {
        const val ROUTE_GUIDANCE_LANE_INFO = 0x5204

        /** iOS 实测上限：8 条车道，每条最多 16 个角度。 */
        const val MAX_LANES = 8
        const val MAX_ANGLES_PER_LANE = 16

        /** 一条车道里最多画 3 个方向，多的会让格子挤成一团。 */
        const val MAX_DIRECTIONS_PER_LANE = 3

        /**
         * 解析一条 0x5204，**先试严格布局、失败再试扁平布局**。
         *
         * ## 严格校验，坏了就整体丢弃
         *
         * 这里不做"部分解析"：车道数据错一位就会把箭头画到隔壁车道上，
         * 比不画更危险。所以任一条 lane TLV 长度对不上、角度越界，
         * 一律返回 `null`，让调用方保持上一次的画面（[RouteGuidanceState] 的
         * stale 窗口会自然把它超时清掉）。
         *
         * ## 为什么还要一条扁平回退
         *
         * 同一个 `0x5204` 存在两套互斥解读（见 [LaneSource]），各自都有证据、
         * 都没有实车抓包定论。既然无法离线判定，就让两条路径都试一遍：
         * 两条都要求"字节正好用完容器"，所以不会同时成功，
         * 结果的 [source] 字段直接告诉实车日志该信哪一套。
         */
        fun parse(payload: ByteArray): ClusterLaneGuidance? {
            parseStrict(payload)?.let { return it }
            return parseFlat(payload)
        }

        /** 首选路径：[LaneSource.STRICT] 的定长头 + i16 有符号角度。 */
        private fun parseStrict(payload: ByteArray): ClusterLaneGuidance? {
            if (!validTlvs(payload)) return null
            var eventIndex = -1
            // 记的是**值区**起点与长度（不是 TLV 起点）—— 少减一个 4 字节头
            // 会让 parseLanes 从类型字段开始读，整条事件直接被判为截断。
            var laneValue: Pair<Int, Int>? = null
            var offset = 0
            while (offset + TLV_HEADER <= payload.size) {
                val length = u16(payload, offset)
                val type = u16(payload, offset + 2)
                when (type) {
                    0x01 -> if (length >= TLV_HEADER + 2) {
                        eventIndex = u16(payload, offset + TLV_HEADER)
                    }
                    0x02 -> if (length >= TLV_HEADER) {
                        laneValue = (offset + TLV_HEADER) to (length - TLV_HEADER)
                    }
                }
                offset += length
            }
            // ★ 严格布局要求 0x01 是 u16 事件序号。扁平布局里它是 u8 推荐下标，
            //   那种载荷的 0x01 值区只有 1 字节 —— 这里自然落空并交给扁平路径。
            if (eventIndex < 0) return null
            val container = laneValue
                ?: return ClusterLaneGuidance(eventIndex, 0, emptyList(), emptyList(), -1)
            val lanes = parseLanes(payload, container.first, container.second) ?: return null
            val primary = lanes.indexOfFirst { it.primary }
            return ClusterLaneGuidance(
                eventIndex = eventIndex,
                laneCount = lanes.size,
                angles = lanes.map { it.angles },
                positions = lanes.map { it.position },
                primaryLane = primary,
                source = LaneSource.STRICT,
            )
        }

        /**
         * 回退路径：[LaneSource.FLAT] —— DiPlay-CN 在实车上写出来的解读。
         *
         * - `0x01`（u8 值区）= 推荐车道下标
         * - `0x02`（u8 值区）= 一条车道方向
         * - `0x03`（任意长度值区）= 剩余全部车道方向，逐字节
         *
         * 方向是**枚举**不是角度：`0 不用 / 1 直 / 2 左 / 3 右`，
         * 这里转成与 [STRICT] 相同的角度表示，让渲染层不用管数据来自哪条路径。
         *
         * 校验：方向字节必须落在枚举内（否则视为布局不符，丢弃），
         * 且车道数 ≤ [MAX_LANES]。
         */
        private fun parseFlat(payload: ByteArray): ClusterLaneGuidance? {
            if (!validTlvs(payload)) return null
            var highlight = -1
            val directions = ArrayList<Int>()
            var sawDirectionTlv = false
            var offset = 0
            while (offset + TLV_HEADER <= payload.size) {
                val length = u16(payload, offset)
                val type = u16(payload, offset + 2)
                val valueStart = offset + TLV_HEADER
                val valueLength = length - TLV_HEADER
                if (valueLength < 0 || valueStart + valueLength > payload.size) return null
                when (type) {
                    0x01 -> if (valueLength == 1) {
                        // ★ 扁平布局的推荐下标是**单字节**。严格布局这里是 u16 事件序号，
                        //   值区 ≥2 字节 —— 用长度直接分流，不靠内容猜。
                        highlight = payload[valueStart].toInt() and 0xff
                    }
                    0x02 -> {
                        // ★ 关键判据：扁平布局每条车道只占 1 字节，
                        //   所以 `0x02` 的值区最多几字节。严格布局里 `0x02` 是
                        //   车道记录容器（定长头 6 字节 + 变长角度）。
                        //   值区 ≥ [FLAT_MAX_DIRECTION_TLV] 时它**一定**不是扁平布局 ——
                        //   不加这条判据，严格布局的 position/status/count 字节里
                        //   那些恰好落在 0..3 的值会被当成方向枚举收下，
                        //   凭空"解析"出 8 条车道（实测就是这么翻车的）。
                        if (valueLength >= FLAT_MAX_DIRECTION_TLV) return null
                        if (valueLength >= 1) {
                            sawDirectionTlv = true
                            if (!appendFlatDirection(payload, valueStart, valueLength, directions)) return null
                        }
                    }
                    0x03 -> if (valueLength >= 1 && valueLength < FLAT_MAX_DIRECTION_TLV) {
                        // 严格布局里 `0x03` 是 Description 文本；UTF-8 汉字的字节
                        // 几乎不可能全部落在 0..3，所以长度上限 + 枚举校验双保险。
                        sawDirectionTlv = true
                        if (!appendFlatDirection(payload, valueStart, valueLength, directions)) return null
                    }
                }
                offset += length
            }
            // 没有任何方向 TLV → 两条路径都不适用，整条丢弃。
            if (!sawDirectionTlv || directions.isEmpty()) return null
            // ★ `0x01` 必须存在且是**单字节**（扁平布局的推荐下标）。
            //   严格布局的 `0x01` 是 u16 事件序号（值区 2 字节），上面已按长度
            //   跳过 —— 所以走到这里若highlight 仍是 -1，说明这条载荷根本没有
            //   推荐车道字段，不能当成扁平布局。
            //
            //   这一条堵住了最后一个漏洞：严格布局的 `0x03`（Description 短文本）
            //   字节恰好全落在 0..3 时仍可能被误收。没有 `0x01` 就拒绝。
            if (highlight < 0) return null
            val lanes = directions.take(MAX_LANES)
            // 扁平布局没有事件序号，用推荐下标派生一个稳定的合成值：
            // 同一帧重复解析会得到同一个号，不会误触发"换了一批车道"。
            return ClusterLaneGuidance(
                eventIndex = highlight,
                laneCount = lanes.size,
                // 扁平布局每条车道只有一个方向。
                angles = lanes.map { if (it == FLAT_UNUSED) emptyList() else listOf(flatAngle(it)) },
                // 扁平布局没有 position —— 按收到顺序就是左到右。
                positions = lanes.map { it.toInt() },
                primaryLane = highlight.coerceIn(-1, lanes.lastIndex),
                source = LaneSource.FLAT,
            )
        }

        /**
         * 逐字节收方向。
         *
         * @return `false` = 遇到枚举外的值，说明这不是扁平布局，整条丢弃。
         *   必须向上传播而不是就地跳过 —— "跳过这一个继续收" 会让错位的数据
         *   画出**看起来合理但完全错误**的车道带，那比不画危险得多。
         *   超出 [MAX_LANES] 只是截断（iOS 最多 8 条，超出部分本来就不画）。
         */
        private fun appendFlatDirection(
            payload: ByteArray,
            start: Int,
            length: Int,
            out: ArrayList<Int>,
        ): Boolean {
            for (i in 0 until length) {
                val raw = payload[start + i].toInt() and 0xff
                if (raw !in FLAT_DIRECTION_CODES) return false
                if (out.size >= MAX_LANES) return true
                out.add(raw)
            }
            return true
        }

        /** 扁平枚举 → 与 [STRICT] 一致的角度。`0`（不用）由调用方映射成空列表。 */
        private fun flatAngle(code: Int): Int = when (code) {
            FLAT_STRAIGHT -> 0
            FLAT_LEFT -> -90
            FLAT_RIGHT -> 90
            else -> 0
        }

        private class Lane(
            val position: Int?,
            val angles: List<Int>,
            val primary: Boolean,
        )

        /**
         * `0x02` LaneInformations 里嵌一层记录，每条车道一个。
         *
         * ## 记录布局（有外部证据的版本）
         *
         * `u16 position | u8 status | u8 angleCount | i16 primary | i16[angleCount] angles`
         * —— 固定头 6 字节，**没有**子 TLV 头（与 [RouteGuidanceState] 的顶层不同）。
         *
         * `position` 是 **u16**（左到右的序号，可以大于 8 —— 车道数才是 ≤8），
         * 这与"渲染批 `CMD_LANES_LANE` 里 position 占 2 字节"一致。
         *
         * `status == 2` 才是推荐车道（与 HUD 侧"只高亮 status 2"一致）；
         * `primary` 字段是 iOS 自己标出的主方向，`0xFFFF` 表示无。
         *
         * ## ★ 为什么必须严格逐字节对齐
         *
         * 这套布局**没有 0x5204 的实车抓包**来验证，是按上述记录格式推的。
         * 猜错的后果不是"解析失败"而是**把箭头画到隔壁车道上** ——
         * 那比不画危险得多。所以这里要求记录长度**正好**用完容器：
         * 多一个字节、角度越界、条数超限，一律整条丢弃（返回 null），
         * 表现为"路口放大图不出现"，而不是画错。
         *
         * 真机上第一次跑，[dump] 会把原始字节打进日志；拿到抓包后按实际
         * 布局校正本函数即可，不需要改调用方。
         */
        private fun parseLanes(payload: ByteArray, start: Int, length: Int): List<Lane>? {
            val end = start + length
            if (end > payload.size) return null
            val lanes = ArrayList<Lane>()
            var cursor = start
            while (cursor + LANE_FIXED_BYTES <= end) {
                val position = u16(payload, cursor)
                val status = payload[cursor + 2].toInt() and 0xff
                val count = payload[cursor + 3].toInt() and 0xff
                if (count > MAX_ANGLES_PER_LANE) return null
                val recordBytes = LANE_FIXED_BYTES + count * 2
                if (cursor + recordBytes > end) return null
                val angles = ArrayList<Int>(count)
                for (i in 0 until count) {
                    val angle = i16(payload, cursor + LANE_FIXED_BYTES + i * 2)
                    if (angle !in -180..180) return null
                    angles.add(angle)
                }
                val declaredPrimary = i16(payload, cursor + 4)
                lanes.add(
                    Lane(
                        position = position,
                        angles = angles,
                        primary = status == LANE_STATUS_RECOMMENDED && declaredPrimary != NO_PRIMARY_ANGLE,
                    ),
                )
                cursor += recordBytes
                if (lanes.size >= MAX_LANES) break
            }
            // 剩余字节必须正好落在记录边界上 —— 多出半条车道说明布局对不上。
            if (cursor != end) return null
            return lanes
        }

        private const val TLV_HEADER = 4

        /**
         * 车道记录固定头：`u16 position | u8 status | u8 angleCount | i16 primary`。
         *
         * 长度必须**正好**等于这个值 + `angleCount * 2`，否则整条丢弃。
         */
        private const val LANE_FIXED_BYTES = 6
        private const val LANE_STATUS_RECOMMENDED = 2
        private const val NO_PRIMARY_ANGLE = -1

        /**
         * 扁平布局（[LaneSource.FLAT]）的方向枚举，取自 DiPlay-CN 的实车实现。
         *
         * 只有这四个值合法 —— 出现别的值就说明这条消息不是扁平布局。
         * 数值范围刻意收得很窄（0..3），这样"恰好蒙对"的概率极低，
         * 不会把严格布局的载荷误判成扁平。
         */
        private const val FLAT_UNUSED = 0
        private const val FLAT_STRAIGHT = 1
        private const val FLAT_LEFT = 2
        private const val FLAT_RIGHT = 3
        private val FLAT_DIRECTION_CODES = 0..3

        /**
         * 扁平布局里单个方向 TLV 的值区长度上限。
         *
         * 扁平布局每条车道只占 1 字节，iOS 最多8 条 —— 实际发出的单个 TLV
         * 不会超过 8 字节。取 8 作为分界：≥8 就一定是严格布局的车道记录容器
         * （定长头就 6 字节）或是 Description 文本。
         */
        private const val FLAT_MAX_DIRECTION_TLV = 8

        /**
         * B561: 诊断用的原始字节转储。
         *
         * ## 为什么要它
         *
         * 本类里车道记录的**字节布局是推出来的**（见 [parseLanes] 的说明），
         * 没有 0x5204 的实车抓包验证过。真机上第一版大概率解析不出来 ——
         * 这时需要原始字节才能把布局钉死，而不是靠猜。
         *
         * 所以解析失败时把前 [DUMP_BYTES] 字节打成十六进制进日志，
         * 一次实车验证就能定位到正确的字段偏移。
         */
        fun dump(payload: ByteArray): String {
            val head = payload.take(DUMP_BYTES).joinToString("") {
                val v = it.toInt() and 0xff
                "0123456789ABCDEF"[v ushr 4].toString() + "0123456789ABCDEF"[v and 0xf]
            }
            return if (payload.size > DUMP_BYTES) "$head…(${payload.size}B)" else "$head(${payload.size}B)"
        }

        private const val DUMP_BYTES = 48

        private fun validTlvs(data: ByteArray): Boolean {
            var offset = 0
            while (offset < data.size) {
                if (offset + TLV_HEADER > data.size) return false
                val length = u16(data, offset)
                if (length < TLV_HEADER || length > data.size - offset) return false
                offset += length
            }
            return offset == data.size
        }

        private fun u16(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

        private fun i16(data: ByteArray, offset: Int): Int {
            val raw = u16(data, offset)
            return if (raw >= 0x8000) raw - 0x10000 else raw
        }
    }
}
