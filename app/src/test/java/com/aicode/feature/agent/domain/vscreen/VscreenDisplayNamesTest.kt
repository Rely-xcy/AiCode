package com.aicode.feature.agent.domain.vscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孤儿屏对账的识别依据：只认自己建屏时写死的前缀。
 *
 * 这条规则必须只按名字判：尺寸、位置、displayId 都可能与别的应用建的屏重合，按它们猜
 * 就会替别人释放屏。
 */
class VscreenDisplayNamesTest {

    @Test
    fun `自己建的屏名带前缀`() {
        val name = VscreenDisplayNames.create(1_700_000_000_000)
        assertTrue(name.startsWith(VscreenDisplayNames.PREFIX))
        assertTrue(VscreenDisplayNames.isOwned(name))
    }

    @Test
    fun `别的应用的屏一律不认`() {
        assertFalse(VscreenDisplayNames.isOwned("Built-in Screen"))
        assertFalse(VscreenDisplayNames.isOwned("HDMI Screen"))
        assertFalse(VscreenDisplayNames.isOwned("scrcpy-virtual-display"))
        assertFalse(VscreenDisplayNames.isOwned("overlay:1"))
    }

    @Test
    fun `名字只有前缀不算、前缀出现在中间也不算`() {
        assertFalse(VscreenDisplayNames.isOwned("aicode-vscreen"))
        assertFalse(VscreenDisplayNames.isOwned("my-aicode-vscreen-123"))
    }

    @Test
    fun `没有名字的屏不认`() {
        assertFalse(VscreenDisplayNames.isOwned(null))
    }
}
