package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Key × 模型可用性缓存：列表证据、上游 503 的负面证据、10 分钟过期与拉取限频。
 *
 * 时间全部由参数注入，不依赖真实时钟。
 */
class KeyModelCatalogTest {

    private val ttl = KeyModelCatalog.MODELS_TTL_MILLIS

    /** 没有任何证据时是「未知」：允许参与选择，不能因此把 Key 排除掉。 */
    @Test
    fun support_unknownBeforeAnyEvidence() {
        assertEquals(KeyModelSupport.UNKNOWN, KeyModelCatalog().support("p", "k", "m"))
    }

    /** 拉到的列表就是正面证据：包含即可用，不包含即不可用。 */
    @Test
    fun recordModels_servesListAsEvidence() {
        val catalog = KeyModelCatalog()
        val now = 1_000L
        catalog.recordModels("p", "k", setOf("m1", "m2"), now)

        assertEquals(KeyModelSupport.AVAILABLE, catalog.support("p", "k", "m1", now))
        assertEquals(KeyModelSupport.MISSING, catalog.support("p", "k", "m3", now))
        assertEquals(setOf("m1", "m2"), catalog.modelsOf("p", "k", now))
    }

    /** 上游实际报过「没有这个模型」比列表更强：列表写着有也按不可用处理。 */
    @Test
    fun markMissing_winsOverList() {
        val catalog = KeyModelCatalog()
        val now = 1_000L
        catalog.recordModels("p", "k", setOf("m1"), now)
        catalog.markMissing("p", "k", "m1", now)

        assertEquals(KeyModelSupport.MISSING, catalog.support("p", "k", "m1", now))
    }

    /** 负面证据 10 分钟后失效，回到「未知」——上游补了渠道后能被重新选中。 */
    @Test
    fun markMissing_expiresAfterTtl() {
        val catalog = KeyModelCatalog()
        val now = 1_000L
        catalog.markMissing("p", "k", "m1", now)

        assertEquals(KeyModelSupport.MISSING, catalog.support("p", "k", "m1", now + ttl - 1))
        assertEquals(KeyModelSupport.UNKNOWN, catalog.support("p", "k", "m1", now + ttl))
    }

    /** 列表过期后不再作为证据，等下一次拉取。 */
    @Test
    fun recordedList_expiresAfterTtl() {
        val catalog = KeyModelCatalog()
        val now = 1_000L
        catalog.recordModels("p", "k", setOf("m1"), now)

        assertEquals(KeyModelSupport.AVAILABLE, catalog.support("p", "k", "m1", now + ttl - 1))
        assertEquals(KeyModelSupport.UNKNOWN, catalog.support("p", "k", "m1", now + ttl))
        assertNull(catalog.modelsOf("p", "k", now + ttl))
    }

    /** 限频：一次尝试（包括失败）后 10 分钟内不再拉。 */
    @Test
    fun shouldFetch_throttlesForTtlIncludingFailedAttempts() {
        val catalog = KeyModelCatalog()
        val now = 1_000L
        assertTrue(catalog.shouldFetch("p", "k", now))

        catalog.markFetchStarted("p", "k", now)

        assertFalse(catalog.shouldFetch("p", "k", now + ttl - 1))
        assertTrue(catalog.shouldFetch("p", "k", now + ttl))
        assertNull("拉取失败时没有列表证据，仍是未知", catalog.modelsOf("p", "k", now + ttl))
    }

    /** 配置里移除的 Key 不再保留证据，避免旧 Key 的标记影响新配置。 */
    @Test
    fun forgetKeys_dropsRemovedKeyEvidence() {
        val catalog = KeyModelCatalog()
        val now = 1_000L
        catalog.recordModels("p", "keep", setOf("m1"), now)
        catalog.recordModels("p", "gone", setOf("m1"), now)

        catalog.forgetKeys("p", setOf("keep"))

        assertEquals(KeyModelSupport.AVAILABLE, catalog.support("p", "keep", "m1", now))
        assertEquals(KeyModelSupport.UNKNOWN, catalog.support("p", "gone", "m1", now))
    }

    /** 证据按 provider 隔离：两个供应商用了同一串 Key 也互不影响。 */
    @Test
    fun evidence_isScopedByProvider() {
        val catalog = KeyModelCatalog()
        val now = 1_000L
        catalog.recordModels("p1", "k", setOf("m1"), now)

        assertEquals(KeyModelSupport.AVAILABLE, catalog.support("p1", "k", "m1", now))
        assertEquals(KeyModelSupport.UNKNOWN, catalog.support("p2", "k", "m1", now))
    }
}
