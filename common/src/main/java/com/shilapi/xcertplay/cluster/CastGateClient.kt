package com.shilapi.xcertplay.cluster

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CAST_GATE 通道客户端。
 *
 * 职责:发广播请求 + 收异步回调 + 管理 sessionId。
 *
 * 规范要点(逐条落实):
 *  - 广播必须 `setPackage("com.thunder.tbxt")` **或**显式组件;我们两个都设。
 *  - `replyTo` 用 `PendingIntent.getBroadcast()`,目标显式指向
 *    [CastGateResultReceiver];Android 12+ 必须 `FLAG_MUTABLE`
 *    (要允许对方往结果 extras 里填东西),所以按 SDK 分支给 flag。
 *  - **不能用** `FLAG_ONE_SHOT`:open 会先回 `opening` 再回 `active`,
 *    一次性 PendingIntent 只能收到第一条,`active` 就丢了。
 *  - **回调路由同理**:`opening`/`active` 两条回调共用同一个 requestId,
 *    所以 `opening` 是**中间态** —— 交付它时不能把 handler 摘掉,
 *    否则 `active` 无人接收(见 [dispatch])。
 *  - 每条请求用**不同的 Intent data**(`castgate://reply/<requestId>`)区分,
 *    否则 extras 不参与 PendingIntent 身份匹配,不同请求会复用同一个 PI。
 *  - `sendBroadcast()` 返回**不代表成功**,一切以回调 `status` 为准。
 */
object CastGateClient {

    const val TAG = "CastGate"

    /** 回调路由表:requestId -> 处理器。 */
    private val handlers = ConcurrentHashMap<String, (CastGateProtocol.Result) -> Unit>()

    /** 当前会话的 sessionId。open 时建立,close 后清除。 */
    @Volatile
    private var sessionId: String? = null

    /** 当前会话的通道。 */
    @Volatile
    private var sessionChannel: String? = null

    /** 会话是否已经拿到 `active`。 */
    @Volatile
    var isActive: Boolean = false
        private set

    /** 最近一次回调的 displayId;-1 表示不可用。 */
    @Volatile
    var displayId: Int = CastGateProtocol.DISPLAY_ID_UNAVAILABLE
        private set

    /** 目标包是否存在。没装 SuperUni 时直接跳过,不做无谓的广播。 */
    fun isAvailable(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(CastGateProtocol.TARGET_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 当前 sessionId;没有会话时返回 null。 */
    fun currentSessionId(): String? = sessionId

    /** 当前会话通道。 */
    fun currentChannel(): String? = sessionChannel

    // ------------------------------------------------------------ 请求

    /**
     * 打开通道。
     *
     * @param onResult 回调。规范明确:open 会有**多个阶段**的通知
     *   (`opening` → `active`),所以这里每次都把回调交给调用方,
     *   由调用方决定在哪个状态做动作。
     */
    fun open(
        context: Context,
        channel: String,
        onResult: (CastGateProtocol.Result) -> Unit,
    ): String {
        val session = UUID.randomUUID().toString()
        sessionId = session
        sessionChannel = channel
        isActive = false
        displayId = CastGateProtocol.DISPLAY_ID_UNAVAILABLE
        val requestId = send(context, CastGateProtocol.OPERATION_OPEN, channel, session, onResult)
        Log.i(TAG, "open channel=$channel session=$session request=$requestId")
        return session
    }

    /**
     * 关闭通道。**必须**用与 open 相同的 sessionId 和通道,否则对方不认。
     */
    fun close(
        context: Context,
        onResult: ((CastGateProtocol.Result) -> Unit)? = null,
    ) {
        val session = sessionId
        val channel = sessionChannel
        if (session == null || channel == null) {
            Log.i(TAG, "close skipped: no session")
            return
        }
        val requestId = send(context, CastGateProtocol.OPERATION_CLOSE, channel, session, onResult)
        Log.i(TAG, "close channel=$channel session=$session request=$requestId")
        sessionId = null
        sessionChannel = null
        isActive = false
        displayId = CastGateProtocol.DISPLAY_ID_UNAVAILABLE
    }

    /** 查询状态。只查询,不开启通道也不启动心跳。 */
    fun status(
        context: Context,
        channel: String,
        onResult: (CastGateProtocol.Result) -> Unit,
    ): String {
        // status 也用会话 id;没有会话时用占位 id 也能查(规范未禁止)。
        val session = sessionId ?: UUID.randomUUID().toString()
        return send(context, CastGateProtocol.OPERATION_STATUS, channel, session, onResult)
    }

    /**
     * 发一条请求。返回 requestId。
     */
    private fun send(
        context: Context,
        operation: String,
        channel: String,
        session: String,
        onResult: ((CastGateProtocol.Result) -> Unit)?,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val request = CastGateProtocol.Request(operation, channel, session, requestId)
        if (onResult != null) handlers[requestId] = onResult

        val intent = Intent(CastGateProtocol.ACTION).apply {
            setPackage(CastGateProtocol.TARGET_PACKAGE)
            setComponent(
                android.content.ComponentName(
                    CastGateProtocol.TARGET_PACKAGE,
                    CastGateProtocol.TARGET_COMPONENT,
                ),
            )
            for ((key, value) in CastGateProtocol.requestExtras(request)) {
                when (value) {
                    is Int -> putExtra(key, value)
                    is String -> putExtra(key, value)
                    else -> putExtra(key, value.toString())
                }
            }
            putExtra(CastGateProtocol.EXTRA_REPLY_TO, replyTo(context, requestId))
        }
        return try {
            context.sendBroadcast(intent)
            requestId
        } catch (error: RuntimeException) {
            // 没装 SuperUni / 权限被拒时 sendBroadcast 可能抛 SecurityException。
            Log.w(TAG, "sendBroadcast failed op=$operation: ${error.javaClass.simpleName}: ${error.message}")
            handlers.remove(requestId)
            requestId
        }
    }

    /**
     * 造 `replyTo` PendingIntent。
     *
     * 三个容易踩的坑:
     *  1. Android 12+ 不给 `FLAG_MUTABLE` 就崩 —— 对方要往结果 extras 里填东西。
     *  2. 不能用 `FLAG_ONE_SHOT` —— open 有多阶段通知。
     *  3. extras 不参与 PI 身份匹配,所以每条请求用**不同的 data** 区分。
     */
    private fun replyTo(context: Context, requestId: String): PendingIntent {
        val intent = Intent(context, CastGateResultReceiver::class.java).apply {
            action = "${CastGateProtocol.ACTION}.REPLY"
            data = Uri.parse("castgate://reply/$requestId")
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags or PendingIntent.FLAG_MUTABLE
        } else {
            flags
        }
        return PendingIntent.getBroadcast(context, requestId.hashCode(), intent, flags)
    }

    // ------------------------------------------------------------ 回调路由

    /**
     * 非终态状态集合 —— 只有这些**不摘** handler。
     *
     * `open` 的完整生命周期是 `opening` → `active` 两段回调,而且**共用同一个
     * requestId**(规范如此,`replyTo` 的 PendingIntent 也不是 ONE_SHOT)。
     * 改之前这里无条件 `handlers.remove(requestId)`,于是第一条 `opening`
     * 就把 handler 摘掉了,`active` 回来时 `remove()` 返回 null ——
     * activity 的 `createClusterPresentation()` 永不被调用:
     * 物理仪表通道被 SuperUni 占着,我们自己却不出图,仪表全黑
     * (v115/v116 引入 CAST_GATE 后的首次实车验证即此故障)。
     */
    private val NON_TERMINAL_STATUSES = setOf(CastGateProtocol.STATUS_OPENING)

    /** 由 [CastGateResultReceiver] 调用。 */
    internal fun dispatch(result: CastGateProtocol.Result) {
        when (result.status) {
            CastGateProtocol.STATUS_ACTIVE -> {
                isActive = true
                if (result.hasDisplay) displayId = result.displayId
            }
            CastGateProtocol.STATUS_CLOSED, CastGateProtocol.STATUS_ERROR -> {
                isActive = false
            }
            else -> Unit
        }
        // 终态(active/closed/error/busy/idle)交付后摘除 handler,防止 map 无限增长;
        // 中间态(opening)必须留着,否则下一段回调找不到接收方。
        if (result.status in NON_TERMINAL_STATUSES) {
            handlers[result.requestId]?.invoke(result)
        } else {
            handlers.remove(result.requestId)?.invoke(result)
        }
    }

    /**
     * 丢弃所有尚未交付的 handler。
     *
     * 给 open 超时兜底用:对方进程没起来时一条回调都不会来,handler 会一直
     * 留在 map 里(还攥着 activity 的 lambda)。超时已经走了回退路径,
     * 这些悬空的 handler 没有存在意义。
     */
    internal fun clearHandlers() {
        handlers.clear()
    }
}
