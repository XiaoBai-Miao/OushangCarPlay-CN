package com.shilapi.xcertplay

/**
 * v128: 无线热点地址族偏好的广播命令（adb 后门 / 设置页共用）。
 *
 * ```
 * adb shell am broadcast -a com.oushang.carplay.SET_ADDRESS_FAMILY --es family IPv6
 * ```
 *
 * 放在独立 object 而不是 Activity 的 private companion——
 * 设置页（DiPlayActivity）与宿主（CarPlayHostActivity）都要引用这些常量。
 */
object AddressFamilyCommand {
    const val ACTION = "com.oushang.carplay.SET_ADDRESS_FAMILY"

    /** [ACTION] 的取值 extra：`AUTOMATIC` / `IPV4` / `IPV6`；缺省按 AUTOMATIC。 */
    const val EXTRA_FAMILY = "family"
}
