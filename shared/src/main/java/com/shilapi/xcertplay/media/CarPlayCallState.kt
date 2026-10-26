package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** What the car shows for the CarPlay call: the phase and the caller, and since when it is connected. */
data class CarPlayCallCard(val phase: Phase, val name: String, val activeSinceMillis: Long? = null) {
    enum class Phase { RINGING, DIALING, ACTIVE }
}

/**
 * The iPhone's calls from iAP2 CallStateUpdate (0x4155): remote id (0), display name (1), status (2)
 * and call UUID (4). Status: 0 disconnected, 1 sending, 2 ringing, 3 connecting, 4 active, 5 held,
 * 6 disconnecting. Several calls can exist at once (call waiting); the card shows a ringing call
 * first, then a connected (active or held) one, then an outgoing one.
 *
 * 移植自 v64 车验版（原 hud/BydCarPlayCall.kt 的 CarPlayCallState，BYD 时代在实车上
 * 验证过字段与状态取值）。v100 重建时随 BYD 集成一并被删；本文件只保留与车机无关的
 * 协议解析部分，出口由调用方决定（B561：画到仪表副屏 + 方控接挂）。
 */
class CarPlayCallState(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Call(val status: Int, val name: String, val activeSince: Long?)

    private val calls = LinkedHashMap<String, Call>()
    private var last: CarPlayCallCard? = null

    /** Returns true when the card changed; read it with [current]. */
    fun accept(frame: Iap2Frame): Boolean {
        if (frame.messageId != CALL_STATE_UPDATE) return false
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return false
        val status = runCatching { body.optionalU8(STATUS) }.getOrNull() ?: return false
        val remote = runCatching { body.optionalString(REMOTE_ID) }.getOrNull()?.trim().orEmpty()
        val display = runCatching { body.optionalString(DISPLAY_NAME) }.getOrNull()?.trim().orEmpty()
        val id = runCatching { body.optionalString(CALL_UUID) }.getOrNull()?.takeIf { it.isNotBlank() } ?: remote
        if (status == DISCONNECTED || status == DISCONNECTING) {
            // ★ v138: 挂断帧可能不带 CALL_UUID / REMOTE_ID（id 为空串）。
            // 旧实现 calls.remove("") 无法移除先前存的有值 id → 通话残留 → 仪表通话卡不消失。
            // 此时视为「当前通话已结束」，清空全部通话，保证卡片隐藏。
            if (id.isBlank()) {
                calls.clear()
            } else {
                calls.remove(id)
            }
        } else {
            val previous = calls[id]
            val connected = status == ACTIVE || status == HELD
            calls[id] = Call(
                status = status,
                name = display.ifEmpty { remote }.ifEmpty { previous?.name.orEmpty() },
                activeSince = previous?.activeSince ?: if (connected) clock() else null,
            )
        }
        val next = card()
        if (next == last) return false
        last = next
        return true
    }

    fun current(): CarPlayCallCard? = last

    fun clear() {
        calls.clear()
        last = null
    }

    private fun card(): CarPlayCallCard? {
        calls.values.firstOrNull { it.status == RINGING }?.let { return CarPlayCallCard(CarPlayCallCard.Phase.RINGING, it.name) }
        (calls.values.firstOrNull { it.status == ACTIVE } ?: calls.values.firstOrNull { it.status == HELD })?.let {
            return CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, it.name, it.activeSince)
        }
        calls.values.firstOrNull { it.status == SENDING || it.status == CONNECTING }?.let {
            return CarPlayCallCard(CarPlayCallCard.Phase.DIALING, it.name)
        }
        return null
    }

    companion object {
        const val CALL_STATE_UPDATE = 0x4155
        private const val REMOTE_ID = 0
        private const val DISPLAY_NAME = 1
        private const val STATUS = 2
        private const val CALL_UUID = 4
        private const val DISCONNECTED = 0
        private const val SENDING = 1
        private const val RINGING = 2
        private const val CONNECTING = 3
        private const val ACTIVE = 4
        private const val HELD = 5
        private const val DISCONNECTING = 6
    }
}
