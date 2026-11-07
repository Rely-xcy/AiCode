package com.aicode.core.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认配色的回退语义。
 *
 * 「未选过主题」与「显式选了默认蓝」在持久化层都是一个可空 id，二者只靠 [AppThemePreset.findById]
 * 的入参区分：null 回退暖纸，\"default\" 仍拿到品牌蓝。这条边界一旦写反，老用户升级后会被悄悄换色。
 */
class AppThemePresetTest {

    @Test
    fun `null 回退到暖纸`() {
        assertSame(AppThemePreset.COFFEE, AppThemePreset.findById(null))
    }

    @Test
    fun `未知 id 回退到暖纸`() {
        assertSame(AppThemePreset.COFFEE, AppThemePreset.findById("no_such_preset"))
    }

    @Test
    fun `显式选择默认蓝仍然生效`() {
        assertSame(AppThemePreset.DEFAULT, AppThemePreset.findById("default"))
    }

    @Test
    fun `暖纸排在选择器第一位`() {
        assertSame(AppThemePreset.COFFEE, AppThemePreset.ALL_PRESETS.first())
    }

    @Test
    fun `四套预设齐全且 id 唯一`() {
        val expected = listOf(
            AppThemePreset.COFFEE,
            AppThemePreset.DEFAULT,
            AppThemePreset.GRAPHITE,
            AppThemePreset.FOREST
        )
        val presets = AppThemePreset.ALL_PRESETS
        assertEquals(expected.size, presets.size)
        assertEquals(expected.map { it.id }, presets.map { it.id })
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
    }

    @Test
    fun `暖纸 id 保持 coffee 以兼容老用户已选值`() {
        assertEquals("coffee", AppThemePreset.COFFEE.id)
        assertSame(AppThemePreset.COFFEE, AppThemePreset.findById("coffee"))
    }

    @Test
    fun `每套预设都有可用的名称资源`() {
        AppThemePreset.ALL_PRESETS.forEach { preset ->
            assertTrue("${preset.id} 的 nameRes 应为有效资源 id", preset.nameRes != 0)
        }
        val nameRes = AppThemePreset.ALL_PRESETS.map { it.nameRes }
        assertEquals(nameRes.size, nameRes.distinct().size)
    }

    @Test
    fun `暖纸浅色沿用设计稿的纸底与陶土红`() {
        val light = AppThemePreset.COFFEE.light
        assertEquals(Color(0xFFF5F3EC), light.semanticColors.pageBackground)
        assertEquals(Color(0xFFFFFFFF), light.semanticColors.cardSurface)
        assertEquals(Color(0xFFEDEAE1), light.semanticColors.mutedSurface)
        assertEquals(Color(0xFFB85B38), light.colorScheme.primary)
        assertEquals(Color(0xFFE4DFD4), light.colorScheme.outlineVariant)
    }

    @Test
    fun `暖纸深色不用纯黑底`() {
        val dark = AppThemePreset.COFFEE.dark
        assertEquals(Color(0xFF181716), dark.semanticColors.pageBackground)
        assertEquals(Color(0xFF22201E), dark.semanticColors.cardSurface)
        assertTrue(dark.semanticColors.pageBackground != Color.Black)
    }
}
