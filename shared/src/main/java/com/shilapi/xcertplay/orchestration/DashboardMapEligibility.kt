package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/** A negotiated alt stream alone does not mean that the driver can see a map on the dashboard. */
internal object DashboardMapEligibility {
    /**
     * ★ v136: 原来这里还放行 `Content.INSTRUMENTS.url`（Apple 官方仪表）——
     *   那一项依赖已下线的 CarPlay Ultra 车辆状态协议，随之删除。
     *
     *   现在只有「地图」这一类内容（含 `MAP_WITH_CUSTOM_CARD` —— 它与 MAP
     *   共用同一个 URL，只是我们额外在地图上画自绘卡片）能让仪表拥有
     *   方向盘音量。纯转向卡内容不是地图，不该抢音量。
     */
    fun permits(initialUrl: String?, physicalOutputVisible: Boolean, stream: Int, uiShown: Boolean, closed: Boolean): Boolean =
        !closed && physicalOutputVisible && stream > 0 && uiShown &&
            initialUrl == CarPlayClusterDisplay.MAP_URL
}
