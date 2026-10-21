package com.shilapi.xcertplay.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * CAST_GATE 异步回调接收器(manifest 静态注册,`exported=false`)。
 *
 * 规范要求:
 *  - `replyTo` 用 `PendingIntent.getBroadcast()` 创建,指定自己 App 内的接收器;
 *    结果接收器可以 `android:exported="false"`。
 *  - **不要**使用 `FLAG_ONE_SHOT` —— 打开请求会收到多个阶段的通知
 *    (`opening` → `active`),一次性 PendingIntent 只能收到第一条。
 *
 * 这里不做任何解析以外的判断,全部交给 [CastGateClient] 路由。
 */
class CastGateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val result = CastGateProtocol.parseResult(
            extras.keySet().associateWith { key -> extras.get(key) },
        ) ?: return
        Log.i(CastGateClient.TAG, "callback ${CastGateProtocol.describe(result)}")
        CastGateClient.dispatch(result)
    }
}
