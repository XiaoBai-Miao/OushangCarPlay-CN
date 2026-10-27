package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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

    /**
     * v139+: 宽屏（车机 1920×910 等）下，统一卡片预览块必须左右分栏 ——
     * **滑块在左、预览在右**，两者同屏可见，不需要滚动才能边看预览边调位置。
     *
     * 结构断言：
     * - 预览的直接父容器是「右列」（垂直 LinearLayout）；
     * - 右列的父容器是「split」（水平 LinearLayout）；
     * - split 中右列在左列右侧（index 更大）；
     * - 左列里能找到 SeekBar（位置/大小滑块）。
     */
    @Test fun previewSitsRightOfSlidersOnWideHeadUnitScreens() {
        val screen = openClusterSettings("zh-rCN-w1400dp-h800dp-xhdpi")
        val preview = descendants(screen.window.decorView).filterIsInstance<ClusterCardPreview>().single()
        val rightColumn = preview.parent as ViewGroup
        val split = rightColumn.parent as ViewGroup
        assertTrue(
            "宽屏下预览应位于左右分栏的右列，实际父容器=${split.javaClass.simpleName}",
            split is LinearLayout && split.orientation == LinearLayout.HORIZONTAL,
        )
        val rightIndex = split.indexOfChild(rightColumn)
        assertTrue("预览应在滑块右侧（右列 index=$rightIndex）", rightIndex > 0)
        val leftColumn = split.getChildAt(0) as ViewGroup
        val hasSeekBar = descendants(leftColumn).filterIsInstance<SeekBar>().any()
        assertTrue("左列应包含位置/大小滑块（SeekBar）", hasSeekBar)
    }

    /**
     * v139+: 宽屏（1920×910，mdpi）下，左右分栏后的「滑块+预览」整块高度
     * 必须不超过可视滚动区高度 —— 这是用户诉求的本质：**同屏可见，无需滚动**。
     */
    @Test
    fun previewAndSlidersFitInTheVisibleViewportOnWideScreens() {
        val screen = openClusterSettings("zh-rCN-w1920dp-h910dp-mdpi")
        val preview = descendants(screen.window.decorView).filterIsInstance<ClusterCardPreview>().single()
        val rightColumn = preview.parent as ViewGroup
        val split = rightColumn.parent as ViewGroup
        // 找到承载预览块的 ScrollView（categoryScroll）—— 它的高度就是可视滚动区。
        var scroll: ViewGroup? = null
        var ancestor: ViewGroup? = split.parent as? ViewGroup
        while (ancestor != null) {
            if (ancestor is android.widget.ScrollView) {
                scroll = ancestor
                break
            }
            ancestor = ancestor.parent as? ViewGroup
        }
        assertTrue("预览块应位于 ScrollView 内", scroll != null)
        val viewport = scroll!!.height - scroll.paddingTop - scroll.paddingBottom
        val detail = "split=${split.width}x${split.height} scrollViewport=$viewport"
        assertTrue(
            "滑块+预览整块高度应不超过可视滚动区（$detail）",
            split.height <= viewport,
        )
    }

    private fun assertFillsCard(qualifiers: String) {
        val screen = openClusterSettings(qualifiers)
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

    /**
     * v111 回归：卡片在预览里的位置/大小必须与实车公式一致。
     *
     * 曾经的 bug：`canvas.scale(w/1920)` 之后画布已是"面板空间"，但 drawCard 拿
     * **View 像素尺寸**去measure 卡片 —— 卡片按 (w,h) 当面板算了一遍几何再被缩放，
     * 整体缩到 (h/720) ≈ 80% 并挤向左上角；选中框又画在 View 边界上，于是框住了
     * 整块面板、经缩放后止于 80%，看着就是"预览画面没占满"。
     *
     * 这里直接渲染像素，量选中卡的框是否落在公式算出的位置上。
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun selectedCardIsDrawnWhereTheClusterGeometrySaysItShouldBe() {
        val preview = ClusterCardPreview(context)
        preview.update(
            geometry = mapOf(
                "turn" to Triple(
                    ClusterTurnCardOverlay.DEFAULT_X_PERCENT,
                    ClusterTurnCardOverlay.DEFAULT_Y_PERCENT,
                    ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT,
                ),
            ),
            opacity = mapOf("turn" to 100),
            night = false,
        )
        preview.setVisibleCards(true, false)
        preview.setSelectedCard("turn")

        val w = 1544
        val h = w * 720 / 1920
        preview.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        preview.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        preview.draw(Canvas(bitmap))

        // 选中框是纯蓝 #2563EB。卡内也有蓝色箭头，所以只取**最上**的蓝行与
        // **最右**的蓝列 —— 箭头碰不到这两个极值。
        var topRow = Int.MAX_VALUE
        var rightCol = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = bitmap.getPixel(x, y)
                if (kotlin.math.abs(Color.red(p) - 0x25) <= 10 &&
                    kotlin.math.abs(Color.green(p) - 0x63) <= 10 &&
                    kotlin.math.abs(Color.blue(p) - 0xEB) <= 10
                ) {
                    if (y < topRow) topRow = y
                    if (x > rightCol) rightCol = x
                }
            }
        }
        assertTrue("预览里没找到选中框", rightCol > 0)

        // 公式算出的卡片矩形（面板坐标）映射到预览像素。
        val card = ClusterTurnCardOverlay.card(1920, 720,
            ClusterTurnCardOverlay.DEFAULT_X_PERCENT,
            ClusterTurnCardOverlay.DEFAULT_Y_PERCENT,
            ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT,
        )
        val scale = w / 1920f
        val expectedTop = card.top * scale
        val expectedRight = (card.left + card.width) * scale
        val detail = "实测 top=$topRow right=$rightCol；期望 top≈${expectedTop.toInt()} " +
            "right≈${expectedRight.toInt()}（含描边内缩 ${SELECTION_INSET_PX}px）"
        assertEquals("选中卡上边应落在公式位置（$detail）", expectedTop.toDouble(), topRow.toDouble(), TOLERANCE_PX.toDouble())
        assertEquals("选中卡右边应落在公式位置（$detail）", expectedRight.toDouble(), rightCol.toDouble(), TOLERANCE_PX.toDouble())
    }

    /** v113: 仪表设置已整体移进「仪表」分类（原先挂在「导航」下）。 */
    private fun openClusterSettings(qualifiers: String): DiPlayActivity {
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
                ReflectionHelpers.ClassParameter(SettingsCategory::class.java, SettingsCategory.CLUSTER),
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

    private companion object {
        /** 描边内缩（面板像素），见 ClusterCardPreview.SELECTION_INSET。 */
        const val SELECTION_INSET_PX = 6f

        /** 允许的像素误差：抗锯齿 + 四舍五入 + 内缩换算，10px 足够宽松又能抓住 20% 的缩放 bug。 */
        const val TOLERANCE_PX = 10
    }
}