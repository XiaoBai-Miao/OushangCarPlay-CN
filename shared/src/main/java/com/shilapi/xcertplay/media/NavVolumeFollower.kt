package com.shilapi.xcertplay.media

import android.content.Context
import android.util.Log

/**
 * ★ v106 B561: 车机音量分组（CarAudio volume group）**只读**诊断。
 *
 * ## 分组表（从 `CarService.apk` 逆向得到，非猜测）
 *
 * `CarService.apk` → `com.android.car.CarAudioService`：
 * - `res/xml/car_volume_groups.xml` 定义 8 组：
 *   navigation=**0** / music=**1** / call=2 / voice_command=3 /
 *   alarm+call_ring+system_sound=4 / cruise=5 / notification=6 / remote_talk=7
 * - `CarAudioService.<clinit>` 的 `USAGE_TO_CONTEXT`（源码 `.line 127-138`）映射：
 *   `usage 1  (USAGE_MEDIA)           → MUSIC      → group 1`
 *   `usage 12 (NAVIGATION_GUIDANCE)   → NAVIGATION → group 0`
 *
 * 与 20:15 实测日志 `usage=1→groupId1` / `usage=12→groupId0` 完全一致
 * ⇒ **我们一直用对了 usage，分组从一开始就是对的。**
 *
 * 20:15 实测「播音乐后导航音量失去跟随」的根因不是分组错误，而是车机自己
 * 切到 `MixType: 3` 并判定 `the same source = 13`，把后续 `adjustVolume`
 * 全导向 source 13 混音总线（对应 `CarAudioService` 的 `HANDLE_MEDIA_FADE_IN`）。
 *
 * ## ★ 为什么全用反射
 *
 * `android.car.Car` / `android.car.media.CarAudioManager` 位于**固件内的
 * `android.car.jar`**，标准 Android SDK **没有**这些符号 —— 直接写类名
 * 连编译都过不去（实测 `Unresolved reference 'car'`，连 `car.connect()` 都报
 * `Unresolved reference 'connect'`）。所以连 `Car` 也必须 `Class.forName`。
 *
 * 我们用平台签名（`CN=Android, O=Android`），`CarService.checkCaller()`
 * 按签名而非包名放行 ⇒ 权限天然通过。
 *
 * ## ★ 为什么只读、不自动写回
 *
 * `setGroupVolume` 是写车机音量，有真实副作用，且我们无法可靠获知
 * 「用户此刻想要多大音量」，直接写可能覆盖用户的调整。
 * 因此本类**只做只读诊断**，把真实分组与音量打进 logcat。
 * 等实车确认数值合理后，再决定是否启用写回。
 */
object NavVolumeFollower {
    private const val TAG = "NavVolumeFollower"

    /** `android.car.Car.AUDIO_SERVICE` 的值。 */
    private const val AUDIO_SERVICE = "audio"

    private var probed = false

    /**
     * 只读探测分组表。由「音量分组诊断」开关控制（默认关）。
     *
     * **全程只调读 API** —— 不碰 `setGroupVolume` / `setMasterMute` / EQ 等写接口。
     */
    fun dumpGroups(context: Context?) {
        if (!AudioEnhancements.groupVolumeVerbose(context)) return
        val app = context?.applicationContext ?: return
        if (probed) return
        probed = true

        val carClass = try {
            Class.forName("android.car.Car")
        } catch (t: Throwable) {
            Log.w(TAG, "android.car.Car not found: ${t.message}")
            return
        }

        val car = try {
            // ★ 真实签名来自 CarService.apk 的 `smali/android/car/Car.smali`（权威）：
            //   createCar(Context, android.content.ServiceConnection) → Car       (line 608)
            //   createCar(Context, ServiceConnection, Handler)        → Car       (line 623)
            //   getCarManager(String) → Object                                    (line 1959)
            //   isConnected() / connect()
            //
            // ★ 踩坑记录：v106 我猜第二参数是 `android.car.ICarServiceConnectionListener`
            //   是**错的**（实车报 "Car.createCar/connect failed: android.car.
            //   ICarServiceConnectionListener"）。它就是标准的 **ServiceConnection**。
            val create = carClass.getMethod(
                "createCar",
                Context::class.java,
                android.content.ServiceConnection::class.java,
            )
            (create.invoke(null, app, null) as Any).also {
                carClass.getMethod("connect").invoke(it)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Car.createCar/connect failed (car_service 未运行?): ${t.message}")
            return
        }

        val connected = runCatching {
            carClass.getMethod("isConnected").invoke(car) as? Boolean
        }.getOrNull()
        Log.i(TAG, "=== car_service connected=$connected ===")

        val manager = try {
            carClass.getMethod("getCarManager", String::class.java).invoke(car, AUDIO_SERVICE)
        } catch (t: Throwable) {
            Log.w(TAG, "getCarManager(AUDIO_SERVICE) failed: ${t.message}")
            return
        }
        if (manager == null) {
            Log.w(TAG, "getCarManager(AUDIO_SERVICE) returned null")
            return
        }

        val cls: Class<*> = manager.javaClass
        Log.i(TAG, "=== CarAudioManager = ${cls.name} ===")

        // ① 分组总数
        runCatching {
            Log.i(TAG, "getVolumeGroupCount() = ${cls.getMethod("getVolumeGroupCount").invoke(manager)}")
        }.onFailure { Log.w(TAG, "getVolumeGroupCount failed: ${it.message}") }

        // ② 逐个 usage 查它落在哪个组、各组音量多少
        for (usage in intArrayOf(1, 2, 12)) {
            try {
                val gid = callInt(cls, manager, "getVolumeGroupIdForUsage", usage) ?: continue
                val vol = callInt(cls, manager, "getGroupVolume", gid)
                val max = callInt(cls, manager, "getGroupMaxVolume", gid)
                Log.i(TAG, "usage=$usage → groupId=$gid, groupVolume=$vol / max=$max")
            } catch (t: Throwable) {
                Log.w(TAG, "usage=$usage query failed: ${t.message}")
            }
        }

        // ③ 反查各组含哪些 usage —— 验证我们逆出的映射表
        for (gid in 0..7) {
            try {
                val arr = runCatching {
                    cls.getMethod("getUsagesForVolumeGroupId", Int::class.javaPrimitiveType)
                        .invoke(manager, gid) as? IntArray
                }.getOrNull()
                if (arr != null && arr.isNotEmpty()) {
                    Log.i(TAG, "group $gid usages = ${arr.joinToString(",")}")
                }
            } catch (_: Throwable) {
                // 某些组没有 usage 映射，属正常
            }
        }

        // ④ 车机认为当前是谁在放（诊断 MixType 场景有用）
        runCatching {
            Log.i(TAG, "getCurrentUsage() = " +
                cls.getMethod("getCurrentUsage").invoke(manager))
        }.onFailure { Log.w(TAG, "getCurrentUsage failed: ${it.message}") }

        Log.i(TAG, "=== dump end (expected: usage 12→group 0 navigation, usage 1→group 1 music) ===")
    }

    /**
     * 反射调返回 Int 的只读方法，失败返回 null。
     *
     * 注意 `invoke(target, *args)` 需要 `Array<out Any?>`，而 vararg IntArray 展开
     * 类型不匹配 —— 故显式 map 成 `Any?` 数组。
     */
    private fun callInt(cls: Class<*>, target: Any, name: String, vararg args: Int): Int? =
        runCatching {
            val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
            val boxed: Array<Any?> = Array(args.size) { args[it] }
            cls.getMethod(name, *types).invoke(target, *boxed) as? Int
        }.getOrNull()
}