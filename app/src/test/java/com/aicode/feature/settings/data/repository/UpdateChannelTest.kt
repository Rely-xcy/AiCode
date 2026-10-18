package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 更新通道的存储值解析与默认值推导。
 *
 * 钉两件事：旧版本存的 `LATEST` 必须映射回 BETA（否则老用户会被换到别的通道），
 * 以及未设置过的默认通道与后台「按包名推通道」的规则一致。
 */
class UpdateChannelTest {

    @Test
    fun storedValue_legacyLatest_mapsToBeta() {
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromStored("LATEST"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromStored("latest"))
    }

    @Test
    fun storedValue_knownNames_resolveCaseInsensitively() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromStored("STABLE"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.fromStored("beta"))
        assertEquals(UpdateChannel.RC, UpdateChannel.fromStored("Rc"))
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromStored("DEV"))
        assertEquals(UpdateChannel.DEBUG, UpdateChannel.fromStored("debug"))
    }

    @Test
    fun storedValue_unknownOrBlank_isNull() {
        assertNull(UpdateChannel.fromStored(null))
        assertNull(UpdateChannel.fromStored(""))
        assertNull(UpdateChannel.fromStored("  "))
        assertNull(UpdateChannel.fromStored("nightly"))
    }

    @Test
    fun wireName_matchesServerChannelNames() {
        assertEquals("stable", UpdateChannel.STABLE.wireName)
        assertEquals("beta", UpdateChannel.BETA.wireName)
        assertEquals("rc", UpdateChannel.RC.wireName)
        assertEquals("dev", UpdateChannel.DEV.wireName)
        assertEquals("debug", UpdateChannel.DEBUG.wireName)
    }

    @Test
    fun defaultChannel_followsApplicationIdSuffix() {
        assertEquals(UpdateChannel.BETA, UpdateChannel.defaultFor("Rely.aicode.beta"))
        assertEquals(UpdateChannel.DEBUG, UpdateChannel.defaultFor("Rely.aicode.debug"))
        assertEquals(UpdateChannel.DEV, UpdateChannel.defaultFor("Rely.aicode.dev"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultFor("Rely.aicode"))
    }

    /** 后缀必须是完整的 `.xxx` 段：包名正文里出现 beta 不算。 */
    @Test
    fun defaultChannel_partialSuffix_isStable() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultFor("Rely.betacode"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.defaultFor("Rely.aicode.beta2"))
    }
}
