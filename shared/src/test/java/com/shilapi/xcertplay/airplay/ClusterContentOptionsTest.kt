package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 仪表内容选项的回归测试。
 *
 * ## 这个 bug 是怎么来的
 *
 * 设置页曾经手写了一个 4 项的文案数组，而 `Content.entries` 也是 4 项，
 * 但**顺序不同**：
 *
 * ```
 * Content.entries : MAP / TURN_CARD / INSTRUMENTS / MAP_WITH_CUSTOM_CARD
 * 文案数组        : 地图 / 转向卡 / 地图和官方转向卡 / 地图和自定义转向卡
 * ```
 *
 * 设置页用 `contents[index]` 取枚举项、文案数组[index] 取标签，于是整体错位：
 * 选「地图和官方转向卡」实际存成 `INSTRUMENTS`，
 * 选「地图和自定义转向卡」实际存成 `MAP_WITH_CUSTOM_CARD`。
 *
 * 后果有两个：
 * 1. 「地图和官方转向卡」这个选项**永远选不到**。
 * 2. `INSTRUMENTS`（`maps:/car/instrumentcluster`，Apple 官方仪表，
 *    走 CarPlay Ultra 车辆状态协议由 iPhone 自己画）**从未被正确暴露**。
 *
 * v126 改成标签与枚举项成对给出，索引不可能再错位。
 */
class ClusterContentOptionsTest {

    private val labels = mapOf(
        Content.MAP to "地图 · 默认",
        Content.TURN_CARD to "转向提示卡",
        Content.INSTRUMENTS to "官方仪表盘（CarPlay Ultra）",
        Content.MAP_WITH_CUSTOM_CARD to "地图和自定义转向卡",
    )

    @Test
    fun `options follow the enum order so indices cannot drift`() {
        val options = CarPlayClusterDisplay.options(labels)
        assertEquals(Content.entries.size, options.size)
        // 每一项的 content 必须与它在列表里的位置一一对应。
        Content.entries.forEachIndexed { index, content ->
            assertEquals(content, options[index].content)
        }
    }

    @Test
    fun `every content has a label`() {
        val options = CarPlayClusterDisplay.options(labels)
        options.forEach { option ->
            assertTrue("选项 ${option.content.name} 的文案不能为空", option.label.isNotBlank())
        }
    }

    @Test
    fun `a missing label is rejected instead of silently misaligning`() {
        // 少一个文案就必须报错 —— 绝不能像以前那样悄悄错位。
        val incomplete = labels - Content.INSTRUMENTS
        try {
            CarPlayClusterDisplay.options(incomplete)
            fail("缺少 INSTRUMENTS 的文案时必须抛异常")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("INSTRUMENTS"))
        }
    }

    @Test
    fun `the official instruments url is the ultra cluster content`() {
        // Apple 官方仪表走的是这个 URL —— 它与地图、转向卡都不同，
        // iPhone 在 /info 请求的 altScreenURLs 里明确列出了它。
        assertEquals("maps:/car/instrumentcluster", Content.INSTRUMENTS.url)
        assertEquals("maps:/car/instrumentcluster/map", Content.MAP.url)
        assertEquals("maps:/car/instrumentcluster/instructioncard", Content.TURN_CARD.url)
    }
}
