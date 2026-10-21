package com.shilapi.xcertplay.network

import java.io.IOException

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    // The iPhone needs 20-25s to join the P2P group after CarPlayStartSession on a healthy
    // link (measured 18.6s to associate, 23.5s to the first AirPlay TCP). A 30s window fired
    // right at the edge of that latency, so a slow-but-working join was torn down and the
    // whole stack rebuilt, roughly doubling the total connect time.
    const val FIRST_TCP_MILLIS = 60_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure {
    HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION,

    /** The head unit's Android Bluetooth accepts connections that reach no phone. */
    BLUETOOTH_UNUSABLE;

    /** False when no further attempt can change what went wrong. */
    val retryable: Boolean get() = this != HOTSPOT_CONFIGURATION && this != BLUETOOTH_UNUSABLE
}

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)
