package com.aicode.feature.agent.domain.vscreen

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟屏硬护栏的纯逻辑测试：授权生命周期与黑名单。
 *
 * 不碰设备、不碰命令——那些属于阶段 0 的真机验证（见方案 §5）。
 */
class VirtualScreenGuardTest {

    @Test
    fun `授权按会话建立与收回`() {
        val guard = VirtualScreenGuard()
        assertFalse(guard.isAuthorized("s1"))

        guard.authorize("s1")
        assertTrue(guard.isAuthorized("s1"))
        // 会话级：别的会话不受影响。
        assertFalse(guard.isAuthorized("s2"))

        guard.revoke("s1")
        assertFalse(guard.isAuthorized("s1"))
    }

    @Test
    fun `支付银行类包名命中黑名单`() {
        val guard = VirtualScreenGuard()
        assertNotNull(guard.blockReason("com.icbc.android.bank"))
        assertNotNull(guard.blockReason("com.eg.android.AlipayGphone".lowercase()))
        assertNotNull(guard.blockReason("com.tencent.mm.wallet"))
    }

    @Test
    fun `普通 app 不被黑名单拦截`() {
        val guard = VirtualScreenGuard()
        assertNull(guard.blockReason("com.aicode.beta"))
        assertNull(guard.blockReason("com.android.settings"))
        assertNull(guard.blockReason("com.android.calculator2"))
    }

    @Test
    fun `注入限速后连续两次注入之间至少等待最小间隔`() = runBlocking {
        val guard = VirtualScreenGuard()
        val start = System.currentTimeMillis()
        guard.awaitInjectionSlot("s1")
        guard.awaitInjectionSlot("s1")
        val elapsed = System.currentTimeMillis() - start
        assertTrue("第二次注入应至少等待一个最小间隔，实际 ${elapsed}ms", elapsed >= MIN_INTERVAL_MS)
    }

    private companion object {
        const val MIN_INTERVAL_MS = 300L
    }
}
