package com.aicode.feature.agent.domain.tool.shizuku

import com.aicode.feature.agent.domain.shizuku.ShizukuBindFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绑定失败类别到 AI 可读提示的映射：每一类都要有非空、互不相同、可照做的中文提示。
 * 纯 JVM 测试，不触 Android / Shizuku。
 */
class ShizukuFailureHintTest {

    @Test
    fun everyFailureMapsToNonEmptyHint() {
        for (failure in ShizukuBindFailure.entries) {
            val hint = shizukuBindFailureHint(failure)
            assertTrue("$failure 的提示不能为空", hint.isNotBlank())
        }
    }

    @Test
    fun hintsAreDistinct() {
        val hints = ShizukuBindFailure.entries.map { shizukuBindFailureHint(it) }
        assertEquals(ShizukuBindFailure.entries.size, hints.toSet().size)
    }
}
