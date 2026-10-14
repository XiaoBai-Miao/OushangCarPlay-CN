package com.shilapi.xcertplay.cluster

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/**
 * 长安欧尚 B561 副屏(仪表屏)枚举与选择。
 *
 * 背景:原实现只认比亚迪 DiLink 屏的特征尺寸
 * (见 ClusterMapPresentation.findDisplay 中的 ClusterStreamProfile.matches),
 * 在 B561 上永远匹配不到。本类改为**枚举除主屏(Display 0)以外的所有屏幕**,
 * 交由用户在设置中自行选择目标仪表屏。
 *
 * 覆盖三类副屏:
 *  - HDMI 外接屏(仪表屏,用户确认有 HDMI 输入口)
 *  - 其他物理副屏
 *  - VirtualDisplay 创建的虚拟屏(可能由其他应用提供,也可作为投放目标)
 *
 * 全部 API 均为 API 17/21 级别,兼容车机的 Android 9(API 28)。
 */
object ClusterDisplayCatalog {

    const val PREFS = "carplay_cluster_display"
    private const val KEY_SELECTED_ID = "selected_display_id"
    private const val KEY_AUTO_FOLLOW = "auto_follow_primary"

    /** 一台可作为仪表出图目标的屏幕。 */
    data class Candidate(
        val displayId: Int,
        val name: String,
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val refreshHz: Float,
        val kind: Kind,
    ) {
        val sizeLabel: String get() = "${widthPx}x$heightPx"

        /** 用于 UI 展示的一行描述。 */
        fun describe(): String = buildString {
            append("$displayId · $name · $sizeLabel")
            if (densityDpi > 0) append(" @${densityDpi}dpi")
            if (refreshHz > 1f) append(" @${refreshHz.toInt()}Hz")
            append(" · ${kind.label}")
        }

        override fun toString(): String = describe()

        enum class Kind(val label: String) {
            /** HDMI / DP 等外接物理屏 —— B561 仪表屏属于此类 */
            EXTERNAL("外接屏(HDMI)"),

            /** 车机内部第二块屏 */
            SECONDARY("内置副屏"),

            /** 其他应用创建的虚拟屏 */
            VIRTUAL("虚拟屏"),

            /** 未归类 */
            UNKNOWN("未分类"),
        }
    }

    // ------------------------------------------------------------- 枚举

    /**
     * 枚举除主屏外的所有候选屏。
     * 主屏定义:displayId == [Display.DEFAULT_DISPLAY];若系统报告里没有 id 0,
     * 则退化为"分辨率最大者"(仪表屏通常远小于中控屏)。
     */
    fun candidates(context: Context): List<Candidate> {
        val displays = allDisplays(context)
        if (displays.isEmpty()) return emptyList()
        val primaryId = primaryDisplayId(displays)
        val result = ArrayList<Candidate>(displays.size)
        for (d in displays) {
            if (d.displayId == primaryId) continue
            if (!isUsable(d)) continue
            result.add(toCandidate(context, d))
        }
        result.sortWith(compareByDescending<Candidate> { it.widthPx * it.heightPx }
            .thenBy { it.displayId })
        return result
    }

    /** 读取当前所有 Display。DisplayManager.displays 自 API 17 起可用。 */
    private fun allDisplays(context: Context): List<Display> {
        val app = context.applicationContext ?: context
        val fromDm = runCatching {
            app.getSystemService(DisplayManager::class.java)?.displays?.toList().orEmpty()
        }.getOrNull().orEmpty()
        if (fromDm.isNotEmpty()) return fromDm

        // 极老系统回退:WindowManager.getDisplays()
        return runCatching {
            val wm = app.getSystemService(android.view.WindowManager::class.java)
            val m = wm?.javaClass?.methods?.firstOrNull {
                it.name == "getDisplays" && it.parameterCount == 0
            }
            @Suppress("UNCHECKED_CAST")
            (m?.invoke(wm) as? Array<Display>)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 找出主屏 id。 */
    private fun primaryDisplayId(displays: List<Display>): Int {
        for (d in displays) {
            if (d.displayId == Display.DEFAULT_DISPLAY) return d.displayId
        }
        var bestId = -1
        var bestArea = -1L
        for (d in displays) {
            val size = sizeOf(d)
            val area = size.x.toLong() * size.y.toLong()
            if (area > bestArea) {
                bestArea = area
                bestId = d.displayId
            }
        }
        return bestId
    }

    // getRealSize/getMetrics 在新SDK 标记 deprecated,但它们是 API 1 就有、
    // Android 9 车机上唯一可靠的读屏尺寸方式(replacement 需要 API 30+)。
    @Suppress("DEPRECATION")
    private fun sizeOf(display: Display): Point {
        val p = Point()
        val ok = runCatching { display.getRealSize(p) }.isSuccess
        if (!ok || p.x <= 0 || p.y <= 0) {
            p.set(0, 0)
            runCatching {
                val dm = DisplayMetrics()
                display.getMetrics(dm)
                p.set(dm.widthPixels, dm.heightPixels)
            }
        }
        return p
    }

    @Suppress("DEPRECATION")
    private fun densityOf(display: Display): Int {
        val dm = DisplayMetrics()
        val ok = runCatching { display.getMetrics(dm) }.isSuccess
        return if (ok) dm.densityDpi else 0
    }

    /** 排除已断开连接的屏。熄屏(STATE_OFF)仍可投图,系统会自动点亮。 */
    private fun isUsable(display: Display): Boolean = runCatching {
        if (!display.isValid) return@runCatching false
        // Display 只有 STATE_UNKNOWN / STATE_OFF / STATE_ON 三个常量。
        // STATE_UNKNOWN 表示 DisplayManager 尚未完成状态同步,视为可用。
        display.state != Display.STATE_OFF
    }.getOrDefault(false)

    private fun toCandidate(context: Context, display: Display): Candidate {
        val flags = runCatching { display.flags }.getOrDefault(0)
        val displayId = display.displayId
        val kind = when {
            displayId >= VIRTUAL_DISPLAY_ID_BASE -> Candidate.Kind.VIRTUAL
            flags and Display.FLAG_PRESENTATION != 0 -> Candidate.Kind.EXTERNAL
            flags and Display.FLAG_SECURE != 0 -> Candidate.Kind.SECONDARY
            else -> Candidate.Kind.UNKNOWN
        }
        val size = sizeOf(display)
        val refresh = runCatching { display.refreshRate }.getOrDefault(0f)
        return Candidate(
            displayId = displayId,
            name = runCatching { display.name }.getOrDefault("display-$displayId"),
            widthPx = size.x,
            heightPx = size.y,
            densityDpi = densityOf(display),
            refreshHz = refresh,
            kind = kind,
        )
    }

    // --------------------------------------------------------- 持久化

    /** 用户是否开启"自动选择"。关闭时使用 [selectedId]。 */
    fun autoFollow(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_FOLLOW, true)

    fun setAutoFollow(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_FOLLOW, enabled).apply()
    }

    /** 用户手动选择的 displayId;-1 表示未选择。 */
    fun selectedId(context: Context): Int = prefs(context).getInt(KEY_SELECTED_ID, -1)

    /**
     * 记住用户选择的仪表屏。
     *
     * **同时切换"自动选择"开关**:[autoFollow] 默认为 true,而 [resolve] 只在它关闭时
     * 才读 [selectedId]。不联动的话,用户在设置里点了某块屏、选择被存下来了,
     * 但 [resolve] 继续按"分辨率最大的候选"出图 —— 表现为**选了 A 屏,画面却出在 B 屏上**,
     * 且不报任何错。这是 ClusterMapPresentationTest.theUserSelectedDisplayWins 一直红的原因。
     *
     * 语义:传 `>= 0` = 手动指定(关自动);传 `-1` = 清除选择、回到自动。
     */
    fun setSelectedId(context: Context, displayId: Int) {
        prefs(context).edit()
            .putInt(KEY_SELECTED_ID, displayId)
            .putBoolean(KEY_AUTO_FOLLOW, displayId < 0)
            .apply()
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // -------------------------------------------------------------- 解析

    /**
     * 解析实际用于出图的 Display。
     *
     * 优先级:
     *  1. 用户手动选择的 displayId(若该屏当前仍存在)
     *  2. 自动模式:候选列表中的第一块(按分辨率降序,仪表屏通常仅此一块)
     *  3. null(无副屏)
     */
    fun resolve(context: Context): Display? {
        val displays = allDisplays(context)
        if (displays.isEmpty()) return null
        val primaryId = primaryDisplayId(displays)

        if (!autoFollow(context)) {
            val wanted = selectedId(context)
            if (wanted >= 0 && wanted != primaryId) {
                for (d in displays) {
                    if (d.displayId == wanted && isUsable(d)) return d
                }
                // 用户选的屏掉线了,落回自动
            }
        }
        val cands = candidates(context)
        for (c in cands) {
            val hit = displays.firstOrNull { it.displayId == c.displayId }
            if (hit != null) return hit
        }
        return null
    }

    /** 诊断输出。 */
    fun report(context: Context): String = buildString {
        val displays = allDisplays(context)
        val primaryId = primaryDisplayId(displays)
        appendLine("displays=${displays.size}")
        for (d in displays) {
            val size = sizeOf(d)
            val flags = runCatching { d.flags }.getOrDefault(0)
            appendLine(
                "  id=${d.displayId} name=${runCatching { d.name }.getOrDefault("?")} " +
                    "${size.x}x${size.y} state=${d.state} flags=0x${Integer.toHexString(flags)} " +
                    "density=${densityOf(d)} refresh=${runCatching { d.refreshRate }.getOrDefault(0f)} " +
                    "primary=${d.displayId == primaryId}"
            )
        }
        appendLine("primaryId=$primaryId")
        appendLine("autoFollow=${autoFollow(context)} selectedId=${selectedId(context)}")
        val resolved = resolve(context)
        appendLine(
            "resolvedCluster=" +
                (if (resolved == null) "none" else "${resolved.displayId}:${runCatching { resolved.name }.getOrDefault("?")}")
        )
        val cands = candidates(context)
        appendLine("candidates=${cands.size}")
        for (c in cands) appendLine("  - ${c.describe()}")
    }

    /** VirtualDisplay 的 displayId 由系统分配,取值远大于物理屏;用阈值区分。 */
    private const val VIRTUAL_DISPLAY_ID_BASE = 0x1000
}