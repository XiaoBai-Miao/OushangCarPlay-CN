package com.shilapi.xcertplay.cluster

/**
 * 仪表投屏通道广播接口(com.thunder.tbxt CastGate)v1 —— 协议层。
 *
 * 规范来源:`参考-第三方接口/第三方仪表投屏接口-com.thunder.tbxt-CastGate-v1.md`
 *
 * 该接口由 SuperUni 侧实现,负责**物理仪表通道**的占用与合成导航心跳;
 * 第三方 App(本项目)只负责在拿到的 displayId 上绘制自己的内容。
 *
 * 关键约定(逐条对应规范原文):
 *  - Action `com.thunder.tbxt.action.CAST_GATE`,必须 `setPackage("com.thunder.tbxt")`
 *    或显式组件 `com.thunder.tbxt.castapi.CastGateReceiver` 发送。
 *  - 每次请求都要带 `apiVersion=1` / `operation` / `channel` / `sessionId` / `requestId` /
 *    `replyTo`。`replyTo` 是**可变的** PendingIntent(Android 12+ 必须 FLAG_MUTABLE),
 *    用来接收异步回调。
 *  - `sendBroadcast()` 返回**不代表成功**,一切以回调里的 `status` 为准。
 *  - `status` 取值:opening / active / closed / idle / busy / error。
 *
 * 本类只做纯数据转换(构造 extras、解析回调),不含任何 Android 组件,
 * 以便用普通 JUnit 单测覆盖。
 */
object CastGateProtocol {

    // ------------------------------------------------------------ 常量

    /** 广播 Action。 */
    const val ACTION = "com.thunder.tbxt.action.CAST_GATE"

    /** 目标包。必须显式限定,否则会被系统拦下或投给错误的接收者。 */
    const val TARGET_PACKAGE = "com.thunder.tbxt"

    /** 目标组件。规范要求 `setPackage` 或显式组件二选一;我们两个都用,更稳。 */
    const val TARGET_COMPONENT = "com.thunder.tbxt.castapi.CastGateReceiver"

    /** 权限名。`normal` 级别,不同签名的开发者 App 也可接入,无需运行时弹窗。 */
    const val PERMISSION = "com.thunder.tbxt.permission.CONTROL_CAST_GATE"

    /** 协议版本。当前只支持 1。 */
    const val API_VERSION = 1

    // operation

    const val OPERATION_OPEN = "open"
    const val OPERATION_CLOSE = "close"
    const val OPERATION_STATUS = "status"

    // channel

    /** 部分/地图通道。 */
    const val CHANNEL_PARTIAL = "partial"

    /** 全屏/健身通道。 */
    const val CHANNEL_FULL = "full"

    // status

    /** 已接受打开请求,正在准备。 */
    const val STATUS_OPENING = "opening"

    /** 开门请求已执行,所需心跳已启动。可以开始画自己的画面了。 */
    const val STATUS_ACTIVE = "active"

    /** 对应会话已关闭(重复关闭空闲通道也返回它)。 */
    const val STATUS_CLOSED = "closed"

    /** 查询时没有活动会话。 */
    const val STATUS_IDLE = "idle"

    /** 通道被内置投屏或其他会话占用。 */
    const val STATUS_BUSY = "busy"

    /** 参数/环境/归属/执行错误。 */
    const val STATUS_ERROR = "error"

    // errorCode

    const val ERROR_UNSUPPORTED_VERSION = "unsupported_version"
    const val ERROR_INVALID_REQUEST = "invalid_request"
    const val ERROR_INVALID_CALLBACK = "invalid_callback"
    const val ERROR_HOST_NOT_READY = "host_not_ready"
    const val ERROR_SERVICE_START_FAILED = "service_start_failed"
    const val ERROR_CAR_SERVICE_UNAVAILABLE = "car_service_unavailable"
    const val ERROR_DISPLAY_UNAVAILABLE = "display_unavailable"
    const val ERROR_PREPARATION_FAILED = "preparation_failed"
    const val ERROR_BUSY = "busy"
    const val ERROR_CHANNEL_MISMATCH = "channel_mismatch"
    const val ERROR_OWNER_MISMATCH = "owner_mismatch"
    const val ERROR_CANCELED = "canceled"

    /** 仪表显示屏不可用时回调里的 displayId。 */
    const val DISPLAY_ID_UNAVAILABLE = -1

    // ------------------------------------------------------------ 请求

    /** 一次通道操作请求的 extras。 */
    data class Request(
        val operation: String,
        val channel: String,
        val sessionId: String,
        val requestId: String,
    ) {
        init {
            require(operation == OPERATION_OPEN || operation == OPERATION_CLOSE || operation == OPERATION_STATUS) {
                "unsupported operation: $operation"
            }
            require(channel == CHANNEL_PARTIAL || channel == CHANNEL_FULL) {
                "unsupported channel: $channel"
            }
        }
    }

    /**
     * 生成请求 extras(除 `replyTo` 外的全部)。
     *
     * `replyTo` 由调用方注入 —— 它是 PendingIntent,无法在纯逻辑层构造,
     * 所以这里只产出可序列化的部分,客户端再补上。
     */
    fun requestExtras(request: Request): Map<String, Any> = mapOf(
        EXTRA_API_VERSION to API_VERSION,
        EXTRA_OPERATION to request.operation,
        EXTRA_CHANNEL to request.channel,
        EXTRA_SESSION_ID to request.sessionId,
        EXTRA_REQUEST_ID to request.requestId,
    )

    // ------------------------------------------------------------ 回调

    /**
     * 一次回调的解析结果。
     *
     * @param displayId 仪表显示屏 ID;不可用时为 -1。
     * @param heartbeatRunning SuperUni 是否正在维护部分通道的合成导航心跳。
     * @param ownedByYou 当前会话是否属于该调用方。
     */
    data class Result(
        val requestId: String,
        val sessionId: String,
        val operation: String,
        val channel: String,
        val status: String,
        val errorCode: String?,
        val message: String?,
        val displayId: Int,
        val heartbeatRunning: Boolean,
        val ownedByYou: Boolean,
    ) {
        /** 是否是"可以画了"的状态。 */
        val isActive: Boolean get() = status == STATUS_ACTIVE

        /** 是否是错误终态。 */
        val isError: Boolean get() = status == STATUS_ERROR

        /** 拿到的显示屏 ID 是否可用。 */
        val hasDisplay: Boolean get() = displayId != DISPLAY_ID_UNAVAILABLE
    }

    /**
     * 解析回调 extras。
     *
     * 宽容处理:缺字段时用空串 / -1 兜底,而不是抛异常 —— 回调来自别的 App,
     * 字段缺失不该让我们的进程崩掉。`requestId` 为空表示这不是给我们的回调,返回 null。
     */
    fun parseResult(extras: Map<String, Any?>): Result? {
        val requestId = extras[EXTRA_REQUEST_ID] as? String ?: return null
        if (requestId.isEmpty()) return null
        return Result(
            requestId = requestId,
            sessionId = extras[EXTRA_SESSION_ID] as? String ?: "",
            operation = extras[EXTRA_OPERATION] as? String ?: "",
            channel = extras[EXTRA_CHANNEL] as? String ?: "",
            status = extras[EXTRA_STATUS] as? String ?: STATUS_ERROR,
            errorCode = extras[EXTRA_ERROR_CODE] as? String,
            message = extras[EXTRA_MESSAGE] as? String,
            displayId = (extras[EXTRA_DISPLAY_ID] as? Number)?.toInt() ?: DISPLAY_ID_UNAVAILABLE,
            heartbeatRunning = extras[EXTRA_HEARTBEAT_RUNNING] as? Boolean ?: false,
            ownedByYou = extras[EXTRA_OWNED_BY_YOU] as? Boolean ?: false,
        )
    }

    // ------------------------------------------------------------ extras key

    const val EXTRA_API_VERSION = "apiVersion"
    const val EXTRA_OPERATION = "operation"
    const val EXTRA_CHANNEL = "channel"
    const val EXTRA_SESSION_ID = "sessionId"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_REPLY_TO = "replyTo"

    const val EXTRA_STATUS = "status"
    const val EXTRA_ERROR_CODE = "errorCode"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_DISPLAY_ID = "displayId"
    const val EXTRA_HEARTBEAT_RUNNING = "heartbeatRunning"
    const val EXTRA_OWNED_BY_YOU = "ownedByYou"

    // ------------------------------------------------------------ 工具

    /** 人类可读的一行状态描述,用于设置页/日志。 */
    fun describe(result: Result): String = buildString {
        append("status=").append(result.status)
        append(" op=").append(result.operation)
        append(" channel=").append(result.channel)
        if (result.displayId != DISPLAY_ID_UNAVAILABLE) {
            append(" display=").append(result.displayId)
        }
        if (result.ownedByYou) append(" owned")
        if (result.heartbeatRunning) append(" heartbeat")
        val code = result.errorCode
        if (!code.isNullOrEmpty()) append(" err=").append(code)
        val message = result.message
        if (!message.isNullOrEmpty()) append(" msg=").append(message)
    }
}
