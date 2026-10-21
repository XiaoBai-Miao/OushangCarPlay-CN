package com.shilapi.xcertplay.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仪表投屏通道广播接口(com.thunder.tbxt CastGate v1)协议层单测。
 *
 * 覆盖规范里最容易踩的几条:参数完整性、回调字段解析、
 * 缺字段时的宽容处理、非法 operation/channel 的拒绝。
 */
class CastGateProtocolTest {

    @Test
    fun everyRequestCarriesTheMandatoryExtras() {
        val request = CastGateProtocol.Request(
            operation = CastGateProtocol.OPERATION_OPEN,
            channel = CastGateProtocol.CHANNEL_PARTIAL,
            sessionId = "session-1",
            requestId = "request-1",
        )
        val extras = CastGateProtocol.requestExtras(request)
        assertEquals(1, extras[CastGateProtocol.EXTRA_API_VERSION])
        assertEquals("open", extras[CastGateProtocol.EXTRA_OPERATION])
        assertEquals("partial", extras[CastGateProtocol.EXTRA_CHANNEL])
        assertEquals("session-1", extras[CastGateProtocol.EXTRA_SESSION_ID])
        assertEquals("request-1", extras[CastGateProtocol.EXTRA_REQUEST_ID])
    }

    @Test
    fun requestRejectsAnUnknownOperation() {
        val failure = runCatching {
            CastGateProtocol.Request(
                operation = "toggle",
                channel = CastGateProtocol.CHANNEL_PARTIAL,
                sessionId = "s",
                requestId = "r",
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun requestRejectsAnUnknownChannel() {
        val failure = runCatching {
            CastGateProtocol.Request(
                operation = CastGateProtocol.OPERATION_OPEN,
                channel = "half",
                sessionId = "s",
                requestId = "r",
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun anActiveCallbackExposesItsDisplayId() {
        val result = CastGateProtocol.parseResult(
            mapOf(
                CastGateProtocol.EXTRA_API_VERSION to 1,
                CastGateProtocol.EXTRA_REQUEST_ID to "request-1",
                CastGateProtocol.EXTRA_SESSION_ID to "session-1",
                CastGateProtocol.EXTRA_OPERATION to "open",
                CastGateProtocol.EXTRA_CHANNEL to "partial",
                CastGateProtocol.EXTRA_STATUS to CastGateProtocol.STATUS_ACTIVE,
                CastGateProtocol.EXTRA_DISPLAY_ID to 3,
                CastGateProtocol.EXTRA_HEARTBEAT_RUNNING to true,
                CastGateProtocol.EXTRA_OWNED_BY_YOU to true,
            ),
        )
        assertTrue(result != null)
        requireNotNull(result)
        assertTrue(result.isActive)
        assertFalse(result.isError)
        assertTrue(result.hasDisplay)
        assertEquals(3, result.displayId)
        assertTrue(result.heartbeatRunning)
        assertTrue(result.ownedByYou)
    }

    @Test
    fun anErrorCallbackKeepsItsCodeAndMessage() {
        val result = CastGateProtocol.parseResult(
            mapOf(
                CastGateProtocol.EXTRA_REQUEST_ID to "request-2",
                CastGateProtocol.EXTRA_STATUS to CastGateProtocol.STATUS_ERROR,
                CastGateProtocol.EXTRA_ERROR_CODE to CastGateProtocol.ERROR_BUSY,
                CastGateProtocol.EXTRA_MESSAGE to "channel occupied",
                CastGateProtocol.EXTRA_DISPLAY_ID to CastGateProtocol.DISPLAY_ID_UNAVAILABLE,
            ),
        )
        requireNotNull(result)
        assertTrue(result.isError)
        assertFalse(result.isActive)
        assertFalse(result.hasDisplay)
        assertEquals("busy", result.errorCode)
        assertEquals("channel occupied", result.message)
        assertEquals(CastGateProtocol.DISPLAY_ID_UNAVAILABLE, result.displayId)
    }

    @Test
    fun aCallbackWithoutARequestIdIsNotOurs() {
        assertNull(CastGateProtocol.parseResult(mapOf(CastGateProtocol.EXTRA_STATUS to "active")))
    }

    @Test
    fun aCallbackWithAnEmptyRequestIdIsNotOurs() {
        assertNull(
            CastGateProtocol.parseResult(
                mapOf(
                    CastGateProtocol.EXTRA_REQUEST_ID to "",
                    CastGateProtocol.EXTRA_STATUS to "active",
                ),
            ),
        )
    }

    @Test
    fun missingFieldsFallBackInsteadOfThrowing() {
        val result = CastGateProtocol.parseResult(
            mapOf(CastGateProtocol.EXTRA_REQUEST_ID to "request-3"),
        )
        requireNotNull(result)
        assertEquals("", result.sessionId)
        assertEquals("", result.operation)
        assertEquals("", result.channel)
        assertEquals(CastGateProtocol.DISPLAY_ID_UNAVAILABLE, result.displayId)
        assertFalse(result.heartbeatRunning)
        assertFalse(result.ownedByYou)
    }

    @Test
    fun theDescriptionMentionsTheFieldsThatMatter() {
        val result = CastGateProtocol.parseResult(
            mapOf(
                CastGateProtocol.EXTRA_REQUEST_ID to "request-4",
                CastGateProtocol.EXTRA_STATUS to CastGateProtocol.STATUS_ACTIVE,
                CastGateProtocol.EXTRA_OPERATION to "open",
                CastGateProtocol.EXTRA_CHANNEL to "partial",
                CastGateProtocol.EXTRA_DISPLAY_ID to 2,
            ),
        )
        requireNotNull(result)
        val text = CastGateProtocol.describe(result)
        assertTrue(text.contains("status=active"))
        assertTrue(text.contains("display=2"))
    }
}
