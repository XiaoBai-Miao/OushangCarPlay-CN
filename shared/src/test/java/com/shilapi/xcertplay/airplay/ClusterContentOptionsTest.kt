package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * v126 改成标签与枚举项成对给出，索引不可能再错位。
 *
 * ## v136: `INSTRUMENTS` 已被删除
 *
 * 那一项是 **Apple 官方仪表**（`maps:/car/instrumentcluster`），要走
 * CarPlay Ultra 的车辆状态协议让 iPhone 自己画。那套协议栈
 * （`airplay/ultra/` + `airplay/rcs/`）在 v136 整体下线 —— 它从未在真车上
 * 跑通过一次，反而会让 iPhone 反复 TEARDOWN 重连。
 *
 * 现在仪表上的车辆信息全部由我们自绘（仪表卡片）。
 */
class ClusterContentOptionsTest {

    private val labels = mapOf(
        Content.MAP to "地图 · 默认",
        Content.TURN_CARD to "转向提示卡",
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
        val incomplete = labels - Content.TURN_CARD
        try {
            CarPlayClusterDisplay.options(incomplete)
            fail("缺少 TURN_CARD 的文案时必须抛异常")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("TURN_CARD"))
        }
    }

    @Test
    fun `the apple official instruments content is gone`() {
        // Apple 官方仪表依赖已下线的 CarPlay Ultra 车辆状态协议 —— 那一项
        // 必须不再存在，否则设置页会把它暴露成"选了也没用"的死选项。
        assertFalse(
            "官方仪表内容项必须随 Ultra 协议栈一起删除",
            Content.entries.any { it.name == "INSTRUMENTS" },
        )
        assertEquals("maps:/car/instrumentcluster/map", Content.MAP.url)
        assertEquals("maps:/car/instrumentcluster/instructioncard", Content.TURN_CARD.url)
    }

    @Test
    fun `only the custom card content draws our own cards`() {
        // 自绘卡片（转向/路口/车道/媒体/通话）只在"地图 + 自绘卡片"模式下挂载。
        assertTrue(CarPlayClusterDisplay.usesCustomTurnCard(Content.MAP_WITH_CUSTOM_CARD))
        assertFalse(CarPlayClusterDisplay.usesCustomTurnCard(Content.MAP))
        assertFalse(CarPlayClusterDisplay.usesCustomTurnCard(Content.TURN_CARD))
    }

    @Test
    fun `the official turn card is the instruction card only`() {
        // v136: INSTRUMENTS 删除后，"官方转向卡"只剩 TURN_CARD 一种。
        assertTrue(CarPlayClusterDisplay.usesOfficialTurnCard(Content.TURN_CARD))
        assertFalse(CarPlayClusterDisplay.usesOfficialTurnCard(Content.MAP))
        assertFalse(CarPlayClusterDisplay.usesOfficialTurnCard(Content.MAP_WITH_CUSTOM_CARD))
    }
}
