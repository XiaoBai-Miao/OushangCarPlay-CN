package com.shilapi.xcertplay.network

import android.content.Context

/**
 * 无线热点发布地址的族偏好（v128 用户显式开关）。
 *
 * 背景：`HotspotAddressRecovery` 原本只认一个自动管理的偏好键
 * （`CarPlayController` 里的 `hotspot_address_family`）——连接失败时
 * `flipWirelessAddressFamily` 会把它翻到另一个族，接口满足不了时又删掉它。
 * 用户没有任何办法说"我这台车机就爱用 IPv6"或"IPv6 在我这儿永远等不到，
 * 别试了"。本类提供**独立**的 override 键与自动键共存：
 *
 * - [AUTOMATIC]：不覆盖，沿用自动 flip 行为（默认）。
 * - [IPV4] / [IPV6]：显式锁定，自动 flip 不再改写偏好。
 */
enum class AddressFamilyPreference {
    AUTOMATIC,
    IPV4,
    IPV6,
    ;

    /** 对应的 [HotspotAddressPolicy] 族常量；[AUTOMATIC] 返回 null（交给自动键）。 */
    val family: String?
        get() = when (this) {
            IPV4 -> HotspotAddressPolicy.FAMILY_IPV4
            IPV6 -> HotspotAddressPolicy.FAMILY_IPV6
            AUTOMATIC -> null
        }
}

/**
 * [AddressFamilyPreference] 的持久化。
 *
 * 键刻意与自动 flip 的 `hotspot_address_family` **分开**——共用一个键就无法
 * 区分"用户锁了 IPv6"和"自动 flip 翻到了 IPv6"，后者会被失败重试反复改写，
 * 用户的显式选择会被悄悄吞掉。
 */
object WirelessAddressFamilyPrefs {
    const val PREFS_NAME = "diplay_wireless"
    const val KEY_OVERRIDE = "wireless_address_family_override"

    fun load(context: Context): AddressFamilyPreference =
        parse(prefs(context).getString(KEY_OVERRIDE, null))

    fun save(context: Context, preference: AddressFamilyPreference) {
        prefs(context).edit().putString(KEY_OVERRIDE, preference.name).apply()
    }

    fun parse(raw: String?): AddressFamilyPreference = when (raw) {
        "IPV4" -> AddressFamilyPreference.IPV4
        "IPV6" -> AddressFamilyPreference.IPV6
        else -> AddressFamilyPreference.AUTOMATIC
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
