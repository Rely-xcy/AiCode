package com.aicode.feature.backup.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 空间预检的判定式：可用空间 >= 待写入体积 * 1.5。 */
class BackupSpaceTest {

    @Test
    fun `requiredBytes 按 1_5 倍向上取整`() {
        assertEquals(0L, BackupSpace.requiredBytes(0))
        assertEquals(0L, BackupSpace.requiredBytes(-1))
        assertEquals(1500L, BackupSpace.requiredBytes(1000))
        assertEquals(1502L, BackupSpace.requiredBytes(1001))
    }

    @Test
    fun `刚好达到余量时通过`() {
        assertTrue(BackupSpace.isEnough(1500, 1000))
        assertTrue(BackupSpace.isEnough(1502, 1001))
    }

    @Test
    fun `空间不足时拒绝并带上需要与可用的字节数`() {
        assertFalse(BackupSpace.isEnough(1499, 1000))
        val e = assertThrows(InsufficientSpaceException::class.java) {
            BackupSpace.requireSpace(availableBytes = 1499, payloadBytes = 1000)
        }
        assertEquals(1500L, e.requiredBytes)
        assertEquals(1499L, e.availableBytes)
    }
}
