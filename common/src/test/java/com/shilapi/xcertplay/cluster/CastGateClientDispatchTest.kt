package com.shilapi.xcertplay.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * CastGateClient 回调路由单测 —— 重点是 v119 修的"opening 摘掉 handler"回归。
 *
 * 实车形态（log1 11:36:38）：open → callback `opening`(38.360) → callback
 * `active`(38.535)，两条共用同一 requestId。改之前 `dispatch()` 在 opening
 * 就 `handlers.remove`，active 回来时找不到 handler，activity 的
 * `createClusterPresentation()` 从不执行 —— 飞屏占着物理仪表通道、
 * app 不出图，仪表全黑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CastGateClientDispatchTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun dropLeftoverHandlers() {
        // CastGateClient 是进程级单例，测试之间的 handler 必须清干净。
        CastGateClient.clearHandlers()
    }

    /** 发一次 open，返回 (requestId, 收到的回调列表)。 */
    private fun openChannel(): Pair<String, MutableList<CastGateProtocol.Result>> {
        val received = mutableListOf<CastGateProtocol.Result>()
        CastGateClient.open(context, CastGateProtocol.CHANNEL_PARTIAL) { received.add(it) }
        val intent = shadowOf(context).broadcastIntents.last { it.action == CastGateProtocol.ACTION }
        val requestId = intent.getStringExtra(CastGateProtocol.EXTRA_REQUEST_ID)
        requireNotNull(requestId) { "open 广播里没有 requestId" }
        return requestId to received
    }

    private fun callback(
        requestId: String,
        status: String,
        displayId: Int = CastGateProtocol.DISPLAY_ID_UNAVAILABLE,
    ) = CastGateProtocol.parseResult(
        mapOf(
            CastGateProtocol.EXTRA_REQUEST_ID to requestId,
            CastGateProtocol.EXTRA_SESSION_ID to "session-test",
            CastGateProtocol.EXTRA_OPERATION to CastGateProtocol.OPERATION_OPEN,
            CastGateProtocol.EXTRA_CHANNEL to CastGateProtocol.CHANNEL_PARTIAL,
            CastGateProtocol.EXTRA_STATUS to status,
            CastGateProtocol.EXTRA_DISPLAY_ID to displayId,
        ),
    ) ?: error("回调解析失败: $status")

    @Test
    fun openingDoesNotConsumeTheHandlerSoActiveStillArrives() {
        val (requestId, received) = openChannel()

        CastGateClient.dispatch(callback(requestId, CastGateProtocol.STATUS_OPENING))
        assertEquals(listOf(CastGateProtocol.STATUS_OPENING), received.map { it.status })
        assertFalse("opening 不等于 active", CastGateClient.isActive)

        CastGateClient.dispatch(callback(requestId, CastGateProtocol.STATUS_ACTIVE, displayId = 1))
        assertEquals(
            listOf(CastGateProtocol.STATUS_OPENING, CastGateProtocol.STATUS_ACTIVE),
            received.map { it.status },
        )
        assertTrue(CastGateClient.isActive)
        assertEquals(1, CastGateClient.displayId)
    }

    @Test
    fun terminalStatusesDeliverOnceThenReleaseTheHandler() {
        val terminals = listOf(
            CastGateProtocol.STATUS_CLOSED,
            CastGateProtocol.STATUS_ERROR,
            CastGateProtocol.STATUS_BUSY,
            CastGateProtocol.STATUS_IDLE,
        )
        for (status in terminals) {
            val (requestId, received) = openChannel()
            CastGateClient.dispatch(callback(requestId, status))
            assertEquals("$status 应交付一次", 1, received.size)
            // 终态之后同 requestId 的重复/迟发回调不再进 handler。
            CastGateClient.dispatch(callback(requestId, status))
            assertEquals("$status 之后 handler 应已摘除", 1, received.size)
        }
    }

    @Test
    fun activeIsTerminalSoHeartbeatRepeatsDoNotReopenTheFlow() {
        val (requestId, received) = openChannel()
        CastGateClient.dispatch(callback(requestId, CastGateProtocol.STATUS_OPENING))
        CastGateClient.dispatch(callback(requestId, CastGateProtocol.STATUS_ACTIVE, displayId = 1))
        // 迟到的第二个 active（心跳式重发）：状态照刷，但 handler 已交付完毕。
        CastGateClient.dispatch(callback(requestId, CastGateProtocol.STATUS_ACTIVE, displayId = 1))
        assertEquals(2, received.size)
        assertTrue(CastGateClient.isActive)
    }

    @Test
    fun clearHandlersDropsPendingCallbacksButKeepsStateUpdates() {
        val (requestId, received) = openChannel()
        CastGateClient.clearHandlers()
        CastGateClient.dispatch(callback(requestId, CastGateProtocol.STATUS_ACTIVE, displayId = 1))
        assertTrue("没有 handler 就不该交付", received.isEmpty())
        // 状态段与 handler 无关：通道确实 active 了，isActive 必须跟着真。
        assertTrue(CastGateClient.isActive)
        assertEquals(1, CastGateClient.displayId)
    }

    @Test
    fun closedAndErrorResetTheActiveFlag() {
        val (requestId, _) = openChannel()
        CastGateClient.dispatch(callback(requestId, CastGateProtocol.STATUS_ACTIVE, displayId = 1))
        assertTrue(CastGateClient.isActive)
        // active 已交付完 handler，但状态段照跑 —— 通道关掉时 isActive 必须回落。
        CastGateClient.dispatch(callback(requestId, CastGateProtocol.STATUS_CLOSED))
        assertFalse(CastGateClient.isActive)
    }
}
