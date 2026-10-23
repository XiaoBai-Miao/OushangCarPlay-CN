package com.shilapi.xcertplay.airplay

/** Optional per-session protocol adapter. Release builds supply null. */
interface AirPlayProtocolExtension {
    fun forSession(): AirPlayProtocolExtension
    fun decorateInfo(info: MutableMap<String, Any?>) {}
    fun onInfoRequest(body: ByteArray) {}
    fun enabledFeatures(proposed: List<*>?): List<String> = emptyList()
    fun onFeaturesNegotiated(features: List<String>) {}
    fun openDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onSetupResponseSent(session: AirPlaySession) {}
    fun mediaTeardownTypes(body: Map<String, Any?>?, original: List<Int>?): List<Int>? = original
    fun onTeardown(session: AirPlaySession, body: Map<String, Any?>?) {}
    fun onSessionClosed(session: AirPlaySession) {}
}
