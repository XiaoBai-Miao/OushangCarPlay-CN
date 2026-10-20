package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * v111：仪表卡片预览必须**铺满**给它的整行宽度。
 *
 * 之前 `DiPlayActivity` 给预览的是 `LayoutParams(MATCH_PARENT, dp(240))`，
 * 而 `ClusterCardPreview.onMeasure` 按高度反推宽度（240dp × 8:3 = 640dp），
 * 于是在比640dp 宽的设置卡片上右边空出一大块 —— 用户看到的正是
 * 「预览的画面没有占满预览区域」。
 *
 * 这里用宽屏设置页实测：预览宽度 == 父容器宽度，且高度仍按 1920×720（8:3）推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w1000dp-h700dp-xhdpi")
class ClusterCardPreviewLayoutTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun previewFillsTheWidthTheSettingsCardGivesIt() {
        assertFillsCard(qualifiers = "zh-rCN-w1400dp-h800dp-xhdpi")
    }

    /** 车机常见的低密度大屏：卡片内区远宽于旧代码 240dp 高 × 8:3 = 640dp 的上限。 */
    @Test fun previewFillsTheCardOnALowDensityWideHeadUnit() {
        assertFillsCard(qualifiers = "zh-rCN-w1920dp-h720dp-mdpi")
    }

    private fun assertFillsCard(qualifiers: String) {
        val screen = openNavigationSettings(qualifiers)
        val preview = descendants(screen.window.decorView).filterIsInstance<ClusterCardPreview>().single()
        val parent = preview.parent as ViewGroup
        val m = screen.resources.displayMetrics
        // 卡片自己的内边距不算「没占满」—— 基准是卡片**内区**宽度。
        val inner = parent.width - parent.paddingLeft - parent.paddingRight
        val detail = "density=${m.density} screen=${m.widthPixels}x${m.heightPixels} " +
            "卡片外宽=${parent.width} 内区=$inner 预览=${preview.width}x${preview.height}"

        assertEquals("预览应铺满设置卡片的内区宽度（$detail）", inner, preview.width)
        // 铺满的前提是不牺牲比例：仪表屏是 1920×720，高度必须按 8:3 反推，
        // 否则预览里摆好的位置到实车就变了，这个预览也就没意义了。
        assertEquals(
            "高度应按 1920:720 推出，不拉伸变形（$detail）",
            (preview.width * 720f / 1920f).toInt(),
            preview.height,
        )
    }

    private fun openNavigationSettings(qualifiers: String): DiPlayActivity {
        // 仪表段整块内容被「仪表地图总开关」门控（默认关），预览框在里面。
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        RuntimeEnvironment.setQualifiers(qualifiers)
        return Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get().also {
            activity = it
            ReflectionHelpers.callInstanceMethod<Unit>(
                it, "openSettingsCategory",
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.NAVIGATION),
            )
            layoutRoot(it)
        }
    }

    private fun layoutRoot(screen: DiPlayActivity) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}