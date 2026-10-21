package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal object HotspotAddressPolicy {
    const val FAMILY_IPV4 = "IPv4"
    const val FAMILY_IPV6 = "IPv6"

    fun parseFamily(raw: String?): String? = when (raw) {
        FAMILY_IPV4 -> FAMILY_IPV4
        FAMILY_IPV6 -> FAMILY_IPV6
        else -> null
    }

    fun flipFamily(family: String): String = if (family == FAMILY_IPV6) FAMILY_IPV4 else FAMILY_IPV6

    fun select(addresses: List<InetAddress>): InetAddress? {
        var linkLocal: InetAddress? = null
        for (address in addresses) {
            if (address is Inet4Address && usable(address)) {
                return address
            }
            if (address is Inet6Address && address.isLinkLocalAddress && linkLocal == null) {
                linkLocal = address
            }
        }
        return linkLocal
    }

    fun select(addresses: List<InetAddress>, preferredFamily: String?): InetAddress? {
        if (preferredFamily == FAMILY_IPV6) {
            // Only a routable IPv6 address can be published: a link-local one loses its zone
            // when it is serialized into CarPlayStartSession, so the iPhone could never dial it.
            addresses.firstOrNull(::routableIpv6)?.let { return it }
        }
        return select(addresses)
    }

    fun alternateFamilyCandidate(addresses: List<InetAddress>, published: InetAddress): InetAddress? {
        val publishedIsIpv6 = published is Inet6Address
        for (address in addresses) {
            if (address is Inet4Address && usable(address) && publishedIsIpv6) {
                return address
            }
            if (routableIpv6(address) && !publishedIsIpv6) {
                return address
            }
        }
        return null
    }

    /** True for an IPv6 address the iPhone can actually reach (not an fe80:: link-local). */
    fun routableIpv6(address: InetAddress): Boolean =
        address is Inet6Address && !address.isLinkLocalAddress && usable(address)

    private fun usable(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isLinkLocalAddress &&
            !address.isMulticastAddress
}
