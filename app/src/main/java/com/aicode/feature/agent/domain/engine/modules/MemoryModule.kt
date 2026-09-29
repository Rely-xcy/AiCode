package com.aicode.feature.agent.domain.engine.modules

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.engine.EngineContext
import com.aicode.feature.agent.domain.engine.EngineModule
import com.aicode.feature.agent.domain.memory.Memory
import com.aicode.feature.agent.domain.memory.MemoryCurator
import com.aicode.feature.agent.domain.memory.MemoryExtractor
import com.aicode.feature.agent.domain.memory.MemoryKind
import com.aicode.feature.agent.domain.memory.MemoryRanker
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.settings.data.repository.MemorySettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆模块：把「AI 记住了什么」接进引擎。
 *
 * 两件事：
 * 1. 注入——把记忆清单（名称 + 描述）拼进系统提示词，详情仍由模型调 `memory(action=read)` 自取；
 * 2. 沉淀——两条路各管各的：模型对话中主动调 memory 工具记录（受「主动记忆」开关控制，
 *    开关关着时注入里没有这条规则）；按治理周期归纳去重（只看周期，周期为 0 才不跑）。
 *    压缩历史前的抽取不受任何开关控制（属上下文管理，见 CompactionModule）。
 *
 * 缓存策略沿用迁移前的实现：按 (sessionId, projectRoot) 会话级缓存，同一会话内只读一次盘，
 * 保持 system prompt 稳定以命中 KV 缓存；空结果用 "" 占位以区分「未缓存」。
 */
@Singleton
class MemoryModule @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memoryCurator: MemoryCurator,
    private val memorySettings: MemorySettingsRepository
) : EngineModule {

    override val id = MODULE_ID

    // 注入顺序：记忆清单跟着「项目规则 / 技能」这类上下文走，用默认序即可。
    override val order = 50

    /**
     * 注入缓存的 key：会话 + 工作区 + 开关 + 当前话题指纹。
     *
     * 带上话题指纹是因为召回要按当轮问题挑记忆；不带的话，一个会话里话题变了
     * 仍然注入开头那几条，排序就白做了。同一话题的多轮仍会命中缓存。
     */
    private data class CacheKey(
        val sessionId: String?,
        val projectRoot: String,
        val activeMemory: Boolean,
        val queryHash: Int
    )

    private val cachedByKey = ConcurrentHashMap<CacheKey, String>()

    /**
     * 治理锁是**全局**的，不按会话。
     *
     * 治理处理的是同一批记忆文件（全局 + 当前项目），而治理由引擎并发分发：
     * 两个会话同时结束一轮时会同时通过「距上次治理足够久」的检查，各跑一次治理
     * （重复调模型、并发改同一批记忆）。拿锁后再复核一次时间戳就成了一次。
     */
    private val curationLock = Mutex()

    // 声明在 init 之前：Kotlin 按声明顺序初始化
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 记忆被外部改动（如模型主动调 memory 工具写入）时丢掉注入缓存，
        // 否则新记忆要等到换会话才生效——主动记忆就白写了。
        scope.launch {
            memoryRepository.changes.collect { cachedByKey.clear() }
        }
    }

    override fun promptFragment(ctx: EngineContext): String? {
        val activeMemory = memorySettings.activeMemoryEnabledSync()
        // 开关与话题都进缓存 key：切换开关、换话题后注入内容都要跟着变
        val key = CacheKey(ctx.sessionId, ctx.projectRoot, activeMemory, queryOf(ctx).hashCode())
        val cached = cachedByKey[key]
        if (cached != null) return cached.ifEmpty { null }

        // 清单为空时也要可能返回规则本身（新用户没有任何记忆时，主动记忆规则必须照样注入）
        val content = listOfNotNull(
            // 子代理不拿主动记忆规则：写用户画像是主代理的事，子代理只管干活
            ACTIVE_MEMORY_RULE.takeIf { activeMemory && !ctx.isSubAgent },
            buildMemoryList(ctx)
        ).joinToString("\n\n")

        cachedByKey[key] = content
        trimIfNeeded()
        return content.ifEmpty { null }
    }

    private fun buildMemoryList(ctx: EngineContext): String? {
        val memories = try {
            memoryRepository.listMemories(ctx.projectRoot)
        } catch (e: Exception) {
            return null
        }
        if (memories.isEmpty()) return null

        // 注入有上限：记忆多了反而干扰决策（实测 10 条已开始干扰）。
        // 挑哪几条不能按名字序——名字是写入时随手起的 slug，与「这轮该用哪条」无关，
        // 所以先按与当前话题的重合度粗排，再按「描述长度预算」裁：至少 3 条（实测的甜点值，
        // 3 条以内不可能干扰），之后只在描述够短时才继续放——真正吃窗口的是描述长度，不是条数。
        val listed = withinDescriptionBudget(
            MemoryRanker.rank(memories, queryOf(ctx), MAX_INJECTED_MEMORIES)
        )
        if (listed.isNotEmpty()) {
            FileLogger.d(TAG, "注入记忆 ${listed.size}/${memories.size} 条: ${listed.joinToString { it.name }}")
        }
        // 记账异步做：注入路径上不能卡 I/O。同一会话同一记忆只记一次（仓库内部去重）。
        scope.launch { runCatching { memoryRepository.recordHits(listed, ctx.sessionId) } }
        val globalMemories = listed.filter { it.scope == MemoryScope.GLOBAL }
        val projectMemories = listed.filter { it.scope == MemoryScope.PROJECT }

        val content = buildString {
            // 立规矩：没有这句，模型倾向把召回的记忆全部塞进答案
            append("只使用与当前问题真正相关的记忆；无关的直接忽略，不必为了显得连贯而硬提。\n\n")
            if (globalMemories.isNotEmpty()) {
                append("全局记忆 (跨项目个人偏好，需要详情时用 memory(action=read, name=xxx, scope=global))：\n")
                globalMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
            }
            if (projectMemories.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("项目记忆 (当前项目专属，需要详情时用 memory(action=read, name=xxx, scope=project))：\n")
                projectMemories.forEach { append("- ${it.name}: ${it.description.ifBlank { "无" }}\n") }
            }
            if (memories.size > listed.size) {
                // 未展开的条目至少把名字列出来：只说「另有 N 条」时模型不知道有没有自己要的那条，
                // 也就不会去 read；名字很短，几乎不占窗口。
                val listedNames = listed.mapTo(mutableSetOf()) { it.name }
                val notListed = memories.filter { it.name !in listedNames }
                append("\n（另有 ${notListed.size} 条未展开：")
                append(notListed.take(MAX_LISTED_NAMES).joinToString(", ") { it.name })
                if (notListed.size > MAX_LISTED_NAMES) append(" …")
                append("；需要详情时用 memory(action=read, name=xxx)）")
            }
        }.trimEnd()

        return content
    }

    /**
     * 按描述长度预算裁剪已排序的记忆：前 [MIN_INJECTED_MEMORIES] 条无条件保留，
     * 之后只在描述总长不超 [DESCRIPTION_BUDGET_CHARS] 时继续放。
     */
    private fun withinDescriptionBudget(ranked: List<Memory>): List<Memory> {
        if (ranked.size <= MIN_INJECTED_MEMORIES) return ranked
        val kept = mutableListOf<Memory>()
        var used = 0
        ranked.forEach { memory ->
            val fits = kept.size < MIN_INJECTED_MEMORIES || used + memory.description.length <= DESCRIPTION_BUDGET_CHARS
            if (!fits) return@forEach
            used += memory.description.length
            kept += memory
        }
        return kept
    }

    /**
     * 排序用的当前话题文本：最近两条用户消息（含本轮）。
     * 不用助手消息——里面常带工具输出，噪声大。
     */
    private fun queryOf(ctx: EngineContext): String =
        ctx.history.asReversed()
            .filterIsInstance<AgentMessage.UserMessage>()
            .take(2)
            .joinToString("\n") { it.content }
            .take(QUERY_MAX_CHARS)

    /**
     * 一轮结束后：到点就做一次记忆治理。
     *
     * 只看治理周期：周期 <= 0 就是关闭治理，与主动记忆开关无关（那个开关只管对话中主动记）。
     *
     * 不再「每 N 轮归约」——开发过程中大多是写新功能或修 bug，很少会冒出值得沉淀的稳定偏好，
     * 每几轮跑一次模型既费钱又依赖模型能力（模型不够聪明就不会按格式输出）。
     * 改为：日常靠主模型用 memory 工具主动记（用户可见），沉淀靠低频治理。
     */
    override suspend fun onTurnCompleted(ctx: EngineContext) {
        if (ctx.isSubAgent) return
        if (ctx.history.isEmpty()) return

        val intervalHours = memorySettings.curationIntervalHours()
        // 周期 <= 0 是设置页里的「关闭治理」：直接返回，不参与下面的间隔比较
        if (intervalHours <= 0) return
        val lastCuratedAt = memorySettings.lastCuratedAt()
        val elapsed = System.currentTimeMillis() - lastCuratedAt
        if (lastCuratedAt > 0 && elapsed < intervalHours * 60L * 60 * 1000) return

        // 上次没跑完就不开新的，避免重复处理同一批记忆
        if (!curationLock.tryLock()) return
        try {
            // 拿到锁后复核间隔：并发分发下多个会话可能同时通过了上面的检查
            val checkedAt = System.currentTimeMillis()
            val lockedLastCuratedAt = memorySettings.lastCuratedAt()
            if (lockedLastCuratedAt > 0 && checkedAt - lockedLastCuratedAt < intervalHours * 60L * 60 * 1000) return

            val complete = ctx.oneShot
            val result = memoryCurator.curate(
                projectRoot = ctx.projectRoot,
                complete = complete?.let { oneShot ->
                    { userPrompt -> oneShot(CURATOR_PROMPT_FILE, userPrompt) }
                }
            )
            // 无论有没有改动都记时间戳：没改动也说明这轮看过了，不该每轮重看
            memorySettings.setLastCuratedAt(System.currentTimeMillis())
            if (result.changed) {
                cachedByKey.keys
                    .filter { it.sessionId == ctx.sessionId && it.projectRoot == ctx.projectRoot }
                    .forEach { cachedByKey.remove(it) }
            }
        } finally {
            curationLock.unlock()
        }
    }

    override suspend fun onSessionDeleted(ctx: EngineContext) {
        cachedByKey.keys.removeAll { it.sessionId == ctx.sessionId }
    }

    /** 注入缓存上限：会话多了不能让缓存无限长大。 */
    private fun trimIfNeeded() {
        if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
    }

    private companion object {
        const val MODULE_ID = "memory"
        const val TAG = "MemoryModule"
        const val SOURCE_CACHE_LIMIT = 64

        /** 治理提示词：只发名称+描述，不发正文（省钱）。 */
        const val CURATOR_PROMPT_FILE = "agent/memory-curator.md"

        /**
         * 主动记忆规则（A 路）：「主动记忆」开关打开时注入，让主模型在对话中自己把稳定结论存下来。
         * 判据是语义的（「未来会话里知道这条会不会让我做法不同」），不是关键词清单：
         * 用户几乎不会明说「记住这个」，偏好大多是隐含的。
         */
        val ACTIVE_MEMORY_RULE = """
            长期记忆（开关已打开，你需要主动维护，用户能在设置里的记忆页看到）：
            每轮回复收尾前，先判断这一轮是否出现了关于用户的稳定结论——
            偏好与表达习惯、工作方式、对你做法的纠正、环境或工具限制（设备/网络/权限/跑不起来的东西）、
            项目约定与架构决策、反复出现的术语与路径、关于用户自身的稳定事实。
            判据：如果未来某次会话一开始就知道这条，你会不会做得不一样？会 → 调用
            memory(action=save, scope=global, name=<短英文 slug>, description=<一句话>, content=<1-3 行>) 存下来。
            宁多勿少：漏记比多记更糟，用户可以自己删；不要等用户说「记住」，发现即记。
            没有条数上限，但也不要为了凑数把同一件事拆成多条。

            【硬性】记忆只有落进 memory 工具才算记住，先调用、再写回复：
            - 不能只在回复里写「我记住了」而不调用工具。那对下一轮毫无作用，
              而且用户会去记忆页找——找不到就是 bug（已经真实发生过一次）。
            - 回复末尾那行说明，**只在真的调用成功之后才写**。
              这一轮没调用工具，就根本不要提记忆这回事。
            - 拿不准要不要记时，倾向调用：多记用户可以删，漏记没人知道。
            不要记：一次性任务细节、工具输出、代码片段、明天就过期的状态。
        """.trimIndent()

        /** 抽取提示词与解析逻辑统一在 [MemoryExtractor]，供压缩前抽取使用。 */
        const val PROMPT_FILE = MemoryExtractor.PROMPT_FILE

        /** 单条记忆注入系统提示词时的条数上限（硬上限，共享给治理器做公平性判据）。
         *
         * 实测结论（MemOS 落地笔记“宁少勿多”）：记忆条数多了反而干扰决策，
         * 3 条是甜点值、10 条已开始干扰。超出部分不展开，靠 [MIN_INJECTED_MEMORIES] 之后的
         * 描述长度预算与 memory(action=list) 兑付。
         */
        const val MAX_INJECTED_MEMORIES = MemoryRanker.INJECTION_SLOTS

        /** 无条件保留的条数：3 条以内不会干扰决策（实测甜点值）。 */
        const val MIN_INJECTED_MEMORIES = 3

        /** 超过甜点值后继续放行的描述总长预算（字符）——真正吃窗口的是描述长度。 */
        const val DESCRIPTION_BUDGET_CHARS = 600

        /** 未展开条目最多列几个名字：再多就变成另一份清单，不如让模型自己 list。 */
        const val MAX_LISTED_NAMES = 12

        /** 话题文本（用于召回排序）最多取多少字符。 */
        const val QUERY_MAX_CHARS = 600
    }
}
