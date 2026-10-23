package com.shilapi.xcertplay

/**
 * v127: 车辆数据模拟开关的命令定义（**测试专用**）。
 *
 * 为什么单独放一个 object 而不是塞进 [CarPlayHostActivity] 的 companion：
 * 那个 companion 是 private 的（内部实现细节），而设置页
 * （`DiPlayActivity`）也要用这两个常量发广播。
 *
 * ## adb 用法
 * ```
 * adb shell am broadcast -a com.oushang.carplay.SET_VEHICLE_SIM --ez enabled true
 * ```
 * 不带 `--ez enabled` 时取反当前状态（快速开关用）。
 */
object VehicleSimulationCommand {
    /** 切换模拟开关的广播 action。 */
    const val ACTION = "com.oushang.carplay.SET_VEHICLE_SIM"

    /** 开关 extra（boolean）；缺省时取反当前状态。 */
    const val EXTRA_ENABLED = "enabled"
}
