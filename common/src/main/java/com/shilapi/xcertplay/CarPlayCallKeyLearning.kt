package com.shilapi.xcertplay

/**
 * 通话按键的「按键学习」通道（v113）。
 *
 * 设置页按下「自定义…」后进入等待状态，接下来按下的那个键被记下来当作
 * 接听键或挂断键。
 *
 * ## 为什么要两条路径
 *
 * 方向盘按键有两个可能先拿到事件的地方：
 * - [WheelKeyService]（无障碍服务）在位时，**先于任何窗口**在输入过滤器里拿到
 * - 无障碍服务没开时，由设置页 Activity 自己的窗口回调拿到
 *
 * 两条都接上，学习流程才不会被"服务把键吃掉了"卡住。
 */
internal object CarPlayCallKeyLearning {
    @Volatile
    private var sink: ((Int) -> Unit)? = null

    /** 开始等待一个按键；[sink] 收到的是 Android keyCode。 */
    fun start(sink: (Int) -> Unit) {
        this.sink = sink
    }

    fun stop() {
        sink = null
    }

    /**
     * 把一个按键交给学习通道。
     *
     * 返回 true 表示这个键被吃掉了（学习完成），调用方不要再继续处理它。
     */
    fun offer(keyCode: Int): Boolean {
        val target = sink ?: return false
        sink = null
        target(keyCode)
        return true
    }
}
