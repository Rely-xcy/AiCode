package com.aicode.feature.agent.domain.vscreen

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟屏几何参数的接受边界。上下界的依据见 [VscreenScreenSpec] 的注释。
 */
class VscreenScreenSpecTest {

    @Test
    fun `下界可接受`() {
        assertNull(VscreenScreenSpec.violation(64, 64, 120))
    }

    @Test
    fun `4K UHD 正好落在像素上限内`() {
        assertNull(VscreenScreenSpec.violation(3840, 2160, 640))
    }

    @Test
    fun `宽高低于下界被拒且原因指出是哪个维度`() {
        val tooNarrow = VscreenScreenSpec.violation(63, 1920, 320)
        assertNotNull(tooNarrow)
        assertTrue(tooNarrow!!.contains("宽度"))

        val tooShort = VscreenScreenSpec.violation(1080, 63, 320)
        assertNotNull(tooShort)
        assertTrue(tooShort!!.contains("高度"))
    }

    @Test
    fun `单边不超但像素总量超限也被拒`() {
        val reason = VscreenScreenSpec.violation(4096, 4096, 320)
        assertNotNull(reason)
        assertTrue("要说明是像素总量超限", reason!!.contains("像素"))
    }

    @Test
    fun `密度两端可接受、越界被拒`() {
        assertNull(VscreenScreenSpec.violation(1080, 1920, 120))
        assertNull(VscreenScreenSpec.violation(1080, 1920, 640))

        assertNotNull(VscreenScreenSpec.violation(1080, 1920, 119))
        assertNotNull(VscreenScreenSpec.violation(1080, 1920, 641))
    }
}
