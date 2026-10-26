package com.aicode.feature.settings.data.repository

import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.KeyRotationStrategy
import com.aicode.feature.settings.domain.model.ProviderKey
import com.aicode.feature.settings.domain.model.ProviderType
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderKeyRotatorTest {

    private fun config(
        id: String = "provider-1",
        apiKey: String = "key-1",
        apiKeys: List<ProviderKey> = emptyList(),
        multiKeyEnabled: Boolean = false,
        keyRotationStrategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
        keyFailoverThreshold: Int = 2,
        keyCooldownMinutes: Int = 5
    ) = AIProviderConfig(
        id = id,
        name = "测试提供商",
        type = ProviderType.OPENAI,
        apiKey = apiKey,
        baseUrl = "https://example.com",
        defaultModel = "gpt-test",
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyRotationStrategy = keyRotationStrategy,
        keyFailoverThreshold = keyFailoverThreshold,
        keyCooldownMinutes = keyCooldownMinutes
    )

    /** 多 Key 模式：effectiveApiKeys 取 apiKeys（去空白后非空者）。 */
    private fun multiKeyConfig(
        keys: List<String>,
        strategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
        threshold: Int = 2,
        cooldownMinutes: Int = 5,
        id: String = "provider-1"
    ) = config(
        id = id,
        apiKey = "unused",
        apiKeys = keys.map { ProviderKey(it) },
        multiKeyEnabled = true,
        keyRotationStrategy = strategy,
        keyFailoverThreshold = threshold,
        keyCooldownMinutes = cooldownMinutes
    )

    /** 模型列表拉取在这里不是被测对象，用松弛 mock 吞掉向它发出的登记调用。 */
    private fun rotator(
        catalog: KeyModelCatalog = KeyModelCatalog(),
        fetcher: ProviderKeyModelFetcher = mockk(relaxed = true)
    ) = ProviderKeyRotator(catalog, fetcher)

    // ---------- activeKey ----------

    /** 单 Key 直接返回，无需任何选择逻辑。 */
    @Test
    fun activeKey_singleKey_returnsIt() {
        val rotator = rotator()

        assertEquals("key-1", rotator.activeKey(config(), "session-1"))
    }

    /** 没有任何可用 Key（单 Key 为空、多 Key 未配置）时返回 null。 */
    @Test
    fun activeKey_emptyKeys_returnsNull() {
        val rotator = rotator()

        assertNull(rotator.activeKey(config(apiKey = ""), "session-1"))
    }

    /** 顺序策略：同一会话多次调用粘住同一个 Key（服务端 prompt 缓存按 Key 隔离）。 */
    @Test
    fun activeKey_sequential_sameSessionIsSticky() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        val first = rotator.activeKey(cfg, "session-1")
        repeat(5) {
            assertEquals(first, rotator.activeKey(cfg, "session-1"))
        }
    }

    /** 顺序策略：不同会话都从第一个未冷却 Key 开始。 */
    @Test
    fun activeKey_sequential_newSessionStartsFromFirst() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        assertEquals("key-a", rotator.activeKey(cfg, "session-1"))
        assertEquals("key-a", rotator.activeKey(cfg, "session-2"))
        assertEquals("key-a", rotator.activeKey(cfg, "session-3"))
    }

    /** 轮询策略：新会话轮流取起始 Key。 */
    @Test
    fun activeKey_roundRobin_rotatesStartingKeyPerNewSession() {
        val rotator = rotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b", "key-c"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        assertEquals("key-a", rotator.activeKey(cfg, "session-1"))
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
        assertEquals("key-c", rotator.activeKey(cfg, "session-3"))
        assertEquals("key-a", rotator.activeKey(cfg, "session-4"))
    }

    /** 轮询策略下同一会话仍粘住同一个 Key，不随游标漂移。 */
    @Test
    fun activeKey_roundRobin_sameSessionStaysSticky() {
        val rotator = rotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        val first = rotator.activeKey(cfg, "session-1")
        repeat(3) {
            assertEquals(first, rotator.activeKey(cfg, "session-1"))
        }
        // 其它会话不受影响，继续轮转
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
    }

    /** sessionId 为 null 时不建绑定：轮询策略每次调用都推进游标。 */
    @Test
    fun activeKey_nullSession_roundRobinAdvancesEveryCall() {
        val rotator = rotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        assertEquals("key-a", rotator.activeKey(cfg, null))
        assertEquals("key-b", rotator.activeKey(cfg, null))
        assertEquals("key-a", rotator.activeKey(cfg, null))
    }

    /** 冷却中的 Key 不被新会话选中。 */
    @Test
    fun activeKey_skipsCoolingDownKey() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        // 先建立配置快照并选中 key-a，随后 key-a 连续失败达阈值进入冷却
        rotator.activeKey(cfg, "session-1")
        rotator.reportFailure("provider-1", "session-1", "key-a")
        rotator.reportFailure("provider-1", "session-1", "key-a")

        // 新会话不能再选冷却中的 key-a
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
    }

    // ---------- currentKey ----------

    /** currentKey 返回最近一次 activeKey 选中的 Key。 */
    @Test
    fun currentKey_returnsLastSelectedKey() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // 选中 key-a

        assertEquals("key-a", rotator.currentKey(cfg))
    }

    /** currentKey 不推进轮询游标：只读调用后第一个新会话仍从原游标位置起步。 */
    @Test
    fun currentKey_doesNotAdvanceRoundRobinCursor() {
        val rotator = rotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        // 只调用 currentKey（不调用 activeKey）
        rotator.currentKey(cfg)
        rotator.currentKey(cfg)

        // 游标未被推进，第一个新会话仍从 key-a 开始
        assertEquals("key-a", rotator.activeKey(cfg, "session-1"))
    }

    /** 冷启动（无任何历史调用）时，currentKey 返回第一个未冷却 Key。 */
    @Test
    fun currentKey_noSelection_returnsFirstUsableKey() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        // currentKey 不依赖 activeKey 建立的配置快照，直接可用
        assertEquals("key-a", rotator.currentKey(cfg))
    }

    // ---------- reportFailure ----------

    /** 失败即刻切换：不累计计数，首次上报即返回切换结果。 */
    @Test
    fun reportFailure_switchesImmediately() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // 建立配置快照

        val switched = rotator.reportFailure("provider-1", "session-1", "key-a")

        assertNotNull(switched)
        assertEquals("key-b", switched!!.newKey)
        assertEquals(2, switched.newIndex)
        assertEquals(2, switched.total)
    }

    /** 失败切换后重绑会话到新 Key：同会话后续调用都用新 Key。 */
    @Test
    fun reportFailure_rebindsSessionToNewKey() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // key-a

        val switched = rotator.reportFailure("provider-1", "session-1", "key-a")

        assertNotNull(switched)
        assertEquals("key-b", switched!!.newKey)
        assertEquals(2, switched.newIndex)
        assertEquals(2, switched.total)
        // 会话重绑到新 Key
        assertEquals("key-b", rotator.activeKey(cfg, "session-1"))
    }

    /** 从未建立配置快照（没调用过 activeKey）时直接返回 null。 */
    @Test
    fun reportFailure_withoutPriorSetup_returnsNull() {
        val rotator = rotator()

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-a"))
    }

    /** 单 Key 配置无可切换目标：返回 null。 */
    @Test
    fun reportFailure_singleKey_returnsNull() {
        val rotator = rotator()
        val cfg = config()

        rotator.activeKey(cfg, "session-1")

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-1"))
        assertNull(rotator.reportFailure("provider-1", "session-1", "key-1"))
    }

    /** 上报不属于该 provider 的 Key：返回 null。 */
    @Test
    fun reportFailure_unknownKey_returnsNull() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1")

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-unknown"))
    }

    /** 失败切换后，原 Key 进入冷却（冷却时长非零时）。 */
    @Test
    fun reportFailure_switchAppliesCooldown() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "session-1") // key-a
        rotator.reportFailure("provider-1", "session-1", "key-a")
        rotator.reportFailure("provider-1", "session-1", "key-a")

        // key-a 冷却中，新会话不再选它
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
    }

    /** 冷却时长为 0 表示不冷却：切换后原 Key 仍可被新会话选中。 */
    @Test
    fun reportFailure_cooldownZero_noCooldownApplied() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 0)

        rotator.activeKey(cfg, "s1") // key-a
        rotator.reportFailure("provider-1", "s1", "key-a")
        rotator.reportFailure("provider-1", "s1", "key-a")

        // 无冷却，key-a 重新可用；顺序策略下新会话从第一个未冷却 Key 开始
        assertEquals("key-a", rotator.activeKey(cfg, "s2"))
    }

    /** 轮询策略下失败切换同样生效，且切换会继续推进轮询游标。 */
    @Test
    fun reportFailure_roundRobin_switchesAndAdvancesCursor() {
        val rotator = rotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b", "key-c"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        rotator.activeKey(cfg, "s1") // key-a（游标 0→1）
        val switched = rotator.reportFailure("provider-1", "s1", "key-a")

        // 游标=1：候选 [b,c,a]，避开 key-a → key-b
        assertEquals("key-b", switched!!.newKey)
        // 游标被推进（1→2），下一个新会话从 key-c 起步
        assertEquals("key-c", rotator.activeKey(cfg, "s2"))
    }

    /** 已试过的 Key 不再重复返回；候选全部试完时返回 null。 */
    @Test
    fun reportFailure_excludesTriedKeysAndReturnsNullWhenExhausted() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b", "key-c"))

        rotator.activeKey(cfg, "session-1")
        val switched = rotator.reportFailure(
            "provider-1", "session-1", "key-c", setOf("key-a", "key-b", "key-c")
        )

        assertNull(switched)
    }

    /** 没有可切换的候选时不冷却当前 Key，避免把最后一个可用 Key 锁死。 */
    @Test
    fun reportFailure_noAlternative_doesNotCoolDownCurrentKey() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "s1") // key-a
        rotator.reportFailure("provider-1", "s1", "key-a") // 切到 key-b，key-a 冷却

        // key-b 是最后一个可用候选：返回 null 且不冷却它
        assertNull(rotator.reportFailure("provider-1", "s1", "key-b"))
        assertEquals("key-b", rotator.activeKey(cfg, "s2"))
    }

    // ---------- 加权轮询 ----------

    /** 加权轮询：新会话按权重比例分配（1:3 时高权重 Key 约为低权重的三倍）。 */
    @Test
    fun activeKey_weightedRoundRobin_distributesByWeight() {
        val rotator = rotator()
        val cfg = config(
            apiKeys = listOf(ProviderKey("key-a", 1), ProviderKey("key-b", 3)),
            multiKeyEnabled = true,
            keyRotationStrategy = KeyRotationStrategy.ROUND_ROBIN
        )

        val counts = mutableMapOf<String, Int>()
        repeat(40) { i ->
            val key = rotator.activeKey(cfg, "session-$i")!!
            counts[key] = (counts[key] ?: 0) + 1
        }

        assertEquals(10, counts["key-a"])
        assertEquals(30, counts["key-b"])
    }

    /** 权重 ≤ 0 的 Key 不参与调度：轮询下始终不会被选中。 */
    @Test
    fun activeKey_nonPositiveWeightIsExcluded() {
        val rotator = rotator()
        val cfg = config(
            apiKeys = listOf(ProviderKey("key-a", 0), ProviderKey("key-b", 1)),
            multiKeyEnabled = true,
            keyRotationStrategy = KeyRotationStrategy.ROUND_ROBIN
        )

        repeat(5) { i ->
            assertEquals("key-b", rotator.activeKey(cfg, "session-$i"))
        }
    }

    /** 所有 Key 权重都 ≤ 0 时没有可调度的 Key，返回 null。 */
    @Test
    fun activeKey_allNonPositiveWeights_returnsNull() {
        val rotator = rotator()
        val cfg = config(
            apiKeys = listOf(ProviderKey("key-a", 0), ProviderKey("key-b", 0)),
            multiKeyEnabled = true
        )

        assertNull(rotator.activeKey(cfg, "session-1"))
    }

    // ---------- 按模型挑 Key ----------

    private val modelA = "gpt-5.6-sol"
    private val modelB = "gpt-5.6-mini"
    private val modelC = "gpt-5.6-other"

    /** 单 Key 带上模型名仍走原路径：没有任何证据时行为与以前完全一致，也不会去拉模型列表。 */
    @Test
    fun activeKey_singleKeyWithModel_returnsItWithoutFetching() {
        val fetcher = mockk<ProviderKeyModelFetcher>(relaxed = true)
        val rotator = rotator(fetcher = fetcher)

        assertEquals("key-1", rotator.activeKey(config(), "session-1", modelA))
        assertEquals("key-1", rotator.activeKey(config(), "session-1", modelA))
        verify(exactly = 0) { fetcher.ensureModels(any(), any(), any()) }
    }

    /** 已知能访问该模型的 Key 优先于「列表还没拉到」的未知 Key。 */
    @Test
    fun activeKey_prefersKeyKnownToHaveModel() {
        val catalog = KeyModelCatalog()
        catalog.recordModels("provider-1", "key-b", setOf(modelA))
        val rotator = rotator(catalog)
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        // key-a 未知、key-b 已知有该模型：先挑已知可用的，不拿未知 Key 碰运气
        assertEquals("key-b", rotator.activeKey(cfg, "s1", modelA))
    }

    /** 上游报过「分组没有该模型」的 Key 不参与该模型的选择。 */
    @Test
    fun activeKey_skipsKeyMarkedWithoutModel() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.markModelUnavailable("provider-1", "key-a", modelA)

        assertEquals("key-b", rotator.activeKey(cfg, "s1", modelA))
    }

    /** 模型证据按模型隔离：只影响出问题的那个模型，别的模型照旧用原来的 Key。 */
    @Test
    fun activeKey_modelEvidenceIsPerModel() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))
        rotator.markModelUnavailable("provider-1", "key-a", modelA)

        assertEquals("key-b", rotator.activeKey(cfg, "s1", modelA))
        assertEquals("key-a", rotator.activeKey(cfg, "s2", modelB))
    }

    /** 会话粘住的 Key 仍然可用时不换 Key（prompt 缓存按 Key 隔离），哪怕别的 Key 已知更合适。 */
    @Test
    fun activeKey_stickyKeyWinsOverKnownAvailableOtherKey() {
        val catalog = KeyModelCatalog()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))
        val rotator = rotator(catalog)

        assertEquals("key-a", rotator.activeKey(cfg, "s1", modelA)) // 无证据，按顺序先绑 key-a
        catalog.recordModels("provider-1", "key-b", setOf(modelA)) // 之后才知道 key-b 能访问

        assertEquals("key-a", rotator.activeKey(cfg, "s1", modelA))
        assertEquals("key-b", rotator.activeKey(cfg, "s2", modelA))
    }

    /** 粘住的 Key 被确认没有该模型后，下一次选 Key 换掉它并重绑会话。 */
    @Test
    fun activeKey_stickyKeyWithoutModelIsReplaced() {
        val rotator = rotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        assertEquals("key-a", rotator.activeKey(cfg, "s1", modelA)) // s1 绑定 key-a
        rotator.markModelUnavailable("provider-1", "key-a", modelA)

        assertEquals("key-b", rotator.activeKey(cfg, "s1", modelA))
        assertEquals("key-b", rotator.activeKey(cfg, "s1", modelA))
    }

    /** 所有 Key 都已知没有该模型：直接报错，不拿没渠道的 Key 硬打。 */
    @Test
    fun activeKey_allKeysWithoutModel_throws() {
        val fetcher = mockk<ProviderKeyModelFetcher>(relaxed = true)
        val rotator = rotator(fetcher = fetcher)
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))
        rotator.markModelUnavailable("provider-1", "key-a", modelA)
        rotator.markModelUnavailable("provider-1", "key-b", modelA)

        val error = assertThrows(IllegalStateException::class.java) {
            rotator.activeKey(cfg, "s1", modelA)
        }

        assertTrue(error.message.orEmpty().contains(modelA))
        assertTrue(error.message.orEmpty().contains("换一个分组的 Key"))
        verify(exactly = 0) { fetcher.ensureModels(any(), any(), any()) }
    }

    /** 懒加载只针对选中的 Key：不批量拉、也不在未带模型时拉。 */
    @Test
    fun activeKey_fetchesModelListForChosenKeyOnly() {
        val fetcher = mockk<ProviderKeyModelFetcher>(relaxed = true)
        val rotator = rotator(fetcher = fetcher)
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "s1", modelA)
        rotator.activeKey(cfg, "s2", modelA)

        // 每次选 Key 都登记一次（重复拉取由限频挡住），但只针对选中的那个 Key
        verify(exactly = 2) { fetcher.ensureModels(any(), "key-a", any()) }
        verify(exactly = 0) { fetcher.ensureModels(any(), "key-b", any()) }
    }

    /** 不带模型（生图等旁路）时不拉模型列表，行为与单 Key 轮换完全一致。 */
    @Test
    fun activeKey_withoutModel_doesNotFetch() {
        val fetcher = mockk<ProviderKeyModelFetcher>(relaxed = true)
        val rotator = rotator(fetcher = fetcher)

        rotator.activeKey(multiKeyConfig(keys = listOf("key-a", "key-b")), "s1")

        verify(exactly = 0) { fetcher.ensureModels(any(), any(), any()) }
    }

    /** Key 级失败切换也要避开「已知没有当前模型」的 Key。 */
    @Test
    fun reportFailure_switchTargetMustHaveCurrentModel() {
        val catalog = KeyModelCatalog()
        catalog.recordModels("provider-1", "key-b", setOf(modelB))
        val rotator = rotator(catalog)
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b", "key-c"), cooldownMinutes = 60)

        assertEquals("key-a", rotator.activeKey(cfg, "s1", modelA)) // 无证据，先 key-a
        catalog.recordModels("provider-1", "key-c", setOf(modelA)) // 之后得知 key-c 能访问
        val switched = rotator.reportFailure("provider-1", "s1", "key-a", model = modelA)

        assertEquals("key-c", switched!!.newKey)
    }

    /** 除当前 Key 外都已知没有该模型：没有可切的目标，返回 null（也不冷却当前 Key）。 */
    @Test
    fun reportFailure_noCandidateWithCurrentModel_returnsNull() {
        val catalog = KeyModelCatalog()
        catalog.recordModels("provider-1", "key-b", setOf(modelB))
        val rotator = rotator(catalog)
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "s1", modelA)
        assertNull(rotator.reportFailure("provider-1", "s1", "key-a", model = modelA))
        // key-a 未被冷却：换一个 key-b 列表里没有、key-a 也没有证据的模型，仍能选到 key-a
        assertEquals("key-a", rotator.activeKey(cfg, "s2", modelC))
    }
}
