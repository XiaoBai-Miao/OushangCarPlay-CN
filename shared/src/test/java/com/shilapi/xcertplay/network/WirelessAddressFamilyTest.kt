package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WirelessAddressFamilyTest {
    @Test
    fun `parse accepts the three published values`() {
        assertEquals(AddressFamilyPreference.AUTOMATIC, WirelessAddressFamilyPrefs.parse(null))
        assertEquals(AddressFamilyPreference.AUTOMATIC, WirelessAddressFamilyPrefs.parse(""))
        assertEquals(AddressFamilyPreference.IPV4, WirelessAddressFamilyPrefs.parse("IPV4"))
        assertEquals(AddressFamilyPreference.IPV6, WirelessAddressFamilyPrefs.parse("IPV6"))
    }

    @Test
    fun `parse falls back to automatic for anything else`() {
        assertEquals(AddressFamilyPreference.AUTOMATIC, WirelessAddressFamilyPrefs.parse("IPv6"))
        assertEquals(AddressFamilyPreference.AUTOMATIC, WirelessAddressFamilyPrefs.parse("ipv4"))
        assertEquals(AddressFamilyPreference.AUTOMATIC, WirelessAddressFamilyPrefs.parse("auto"))
        assertEquals(AddressFamilyPreference.AUTOMATIC, WirelessAddressFamilyPrefs.parse("IPv7"))
    }

    @Test
    fun `family mapping matches HotspotAddressPolicy constants`() {
        assertNull(AddressFamilyPreference.AUTOMATIC.family)
        assertEquals(HotspotAddressPolicy.FAMILY_IPV4, AddressFamilyPreference.IPV4.family)
        assertEquals(HotspotAddressPolicy.FAMILY_IPV6, AddressFamilyPreference.IPV6.family)
    }

    @Test
    fun `save then load round-trips every preference`() {
        val context = RuntimeEnvironment.getApplication()
        for (preference in AddressFamilyPreference.entries) {
            WirelessAddressFamilyPrefs.save(context, preference)
            assertEquals(preference, WirelessAddressFamilyPrefs.load(context))
        }
    }

    @Test
    fun `override key is separate from the auto-flip key`() {
        val context = RuntimeEnvironment.getApplication()
        // 模拟自动 flip 写了 hotspot_address_family=IPv6，override 仍应是 AUTOMATIC。
        context.getSharedPreferences(WirelessAddressFamilyPrefs.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("hotspot_address_family", HotspotAddressPolicy.FAMILY_IPV6)
            .apply()
        assertEquals(
            "自动键不得被当成用户显式选择",
            AddressFamilyPreference.AUTOMATIC,
            WirelessAddressFamilyPrefs.load(context),
        )
        // 用户显式选 IPv4 后，自动键保持 IPv6 不变，二者互不干扰。
        WirelessAddressFamilyPrefs.save(context, AddressFamilyPreference.IPV4)
        val prefs = context.getSharedPreferences(
            WirelessAddressFamilyPrefs.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        assertEquals(HotspotAddressPolicy.FAMILY_IPV6, prefs.getString("hotspot_address_family", null))
        assertEquals(AddressFamilyPreference.IPV4, WirelessAddressFamilyPrefs.load(context))
    }
}
