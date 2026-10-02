package com.aicode.feature.agent.domain.schedule

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 一份写范围租约：某持有者独占的路径集合（单个文件、一棵子树或一个通配模式）。 */
data class WriteLease(
    val holderId: String,
    /** 人类可读的持有者描述（子代理用它的任务标题），用于冲突提示。 */
    val holderLabel: String,
    /** 用户/模型写的原始模式，仅用于回报，不参与判定。 */
    val pattern: String,
    /** 规范化后的路径段，判定用。 */
    val segments: List<String>,
    val acquiredAt: Long
)

/** 一次写范围冲突：请求的路径落在别人已持有的范围内。 */
data class LeaseConflict(
    val holderId: String,
    val holderLabel: String,
    val pattern: String
) {
    /** 冲突提示：必须同时说清「谁占着」「占的是什么范围」「被拒的一方该做什么」，否则就是静默失败。 */
    fun describe(requested: String): String =
        "路径 $requested 正被「$holderLabel」持有（写范围：$pattern），本次调用已拒绝，文件未被改动。" +
            "不要重试同一次调用：等它结束后重试、改用不冲突的文件，或让派发方用 task(action=\"stop\", id=…) 停掉它。"

    /** 写被拒时的提示：额外要求被拒方上报，否则派发方只会看到子代理卡住而不知道原因。 */
    fun describeForWrite(requested: String): String =
        describe(requested) + "请用 messageParent 把被拒的文件与占用者报给派发方，再继续做不冲突的部分。"

    /**
     * 主代理写入时的提示：**不拒绝**（它是对用户负责的一方，直接拒掉只会让用户看到「改不动」），
     * 但要把「有人正在同一个文件上干活」摆明，并给出三条可执行的出路。
     */
    fun describeForMainAgent(requested: String): String =
        "\n\n[写范围提示] $requested 正被「$holderLabel」持有（写范围：$pattern），本次写入已照常执行。" +
            "两边可能各自基于旧内容改同一处，刚写的内容有被覆盖的风险：要么等它结束再改，" +
            "要么 task(action=\"stop\", id=…) 停掉它，要么用 task(action=\"send\", id=…, message=…) 让它自己改。"
}

/**
 * 写范围租约登记表。
 *
 * 解决的问题很具体：主代理可以并发派发多个子代理（`task` 上限 5），它们共享同一个工作区，
 * 两个子代理同时改同一个文件必然互相覆盖——各自的 `editFile` 都基于自己读到的旧内容。
 *
 * 做法不是「禁止并发写」（做不到，也不该做），而是要求写之前先持有范围：
 * - 派发时声明（[claim]）：`task(action="create", writeScope=[...])`，冲突在启动前就暴露，不浪费一次子代理启动；
 * - 写入时兜底（[claimForWrite]）：没声明的范围在第一次写入时认领，后到者拿到冲突而不是默默盖掉别人的改动。
 *
 * 一个持有者可以同时持有多份范围：子代理写完 A 再写 B，A 的租约必须继续有效，
 * 否则「A 写完 → 释放 A → 别人改 A → 自己再改 A」会把别人的改动盖掉。
 *
 * 刻意只做内存态、不落盘：租约是「这一次并发批次」里的协调信息，App 重启后所有子代理都已终止，
 * 残留租约只会让主代理凭空被拒。
 */
@Singleton
class WriteLeaseRegistry @Inject constructor(
    private val pathMapper: WorkspacePathMapper
) {

    private companion object {
        const val TAG = "WriteLeaseRegistry"

        /**
         * 租约有效期。持有者每次写入都会刷新；超过这么久没动静就视为已放弃。
         *
         * 这是最后的兜底，不是释放的主路径：正常完成/取消/异常由运行结束回调释放、会话删除由
         * 模块钩子释放。只有进程被杀这种没人能跑到释放代码的情况才靠它。因为不常走，定得偏长：
         * 太短的话，一个先读了十几分钟才动手写的子代理会在写之前丢掉自己的声明。
         */
        const val LEASE_TTL_MS = 30 * 60 * 1000L
    }

    /** holderId → (规范化模式串 → 租约)。一个持有者可持有多份。 */
    private val byHolder = ConcurrentHashMap<String, MutableMap<String, WriteLease>>()

    /** holderId → 最后一次活动时间（写入或认领），TTL 判定用。 */
    private val lastActiveAt = ConcurrentHashMap<String, Long>()

    /**
     * 派发时声明写范围：整批原子生效——任一模式冲突则一份都不登记，并把全部冲突回报给调用方。
     *
     * @param handoverFrom 交出方会话 id（通常是派发子代理的父会话）。父会话自己的租约在这里
     *   被忽略并转交给子代理：父会话刚写完的文件交给子代理继续改是正常流程，不该被自己的租约拦住；
     *   转交后父会话若再写同一文件会被子代理的租约拦住，那才是正确行为。
     * @return 冲突列表；为空表示已成功登记。
     */
    fun claim(
        holderId: String,
        holderLabel: String,
        patterns: List<String>,
        projectRoot: String,
        handoverFrom: Set<String> = emptySet()
    ): List<LeaseConflict> {
        pruneExpired()
        val parsed = patterns
            .mapNotNull { raw -> scopeOf(raw, projectRoot) }
            .distinctBy { it.second.joinToString("/") }
        if (parsed.isEmpty()) return emptyList()

        val conflicts = parsed
            .mapNotNull { (raw, segments) -> findConflict(holderId, segments, handoverFrom) }
            .distinctBy { it.holderId + "|" + it.pattern }
        if (conflicts.isNotEmpty()) {
            FileLogger.w(
                TAG,
                "写范围声明被拒：$holderLabel 申请 ${parsed.map { it.first }}，" +
                    "冲突 ${conflicts.map { "${it.holderLabel}(${it.pattern})" }}"
            )
            return conflicts
        }

        val now = System.currentTimeMillis()
        parsed.forEach { (raw, segments) -> put(holderId, holderLabel, raw, segments, now) }
        handoverFrom.forEach { from -> releaseOverlapping(from, parsed.map { it.second }) }
        FileLogger.i(TAG, "写范围已登记：$holderLabel → ${parsed.map { it.first }}")
        return emptyList()
    }

    /**
     * 写入前的准入检查 + 首次写入自动认领（单次原子操作，避免检查与认领之间的竞态）。
     *
     * 自己已覆盖该路径时只刷新租约；否则先查别人有没有占着，没有就自己认领。
     *
     * @return 冲突；无冲突返回 null（表示本次写入已获准）。
     */
    fun claimForWrite(
        holderId: String,
        holderLabel: String,
        path: String,
        projectRoot: String
    ): LeaseConflict? {
        pruneExpired()
        val segments = segmentsOf(path, projectRoot) ?: return null
        val now = System.currentTimeMillis()

        // 同一持有者写自己的范围永远放行，并刷新活跃时间（避免活跃持有者被 TTL 踢掉）。
        byHolder[holderId]?.values
            ?.firstOrNull { WriteScopePattern.overlaps(it.segments, segments) }
            ?.let {
                put(holderId, holderLabel, it.pattern, it.segments, now)
                return null
            }

        val conflict = findConflict(holderId, segments, emptySet())
        if (conflict != null) {
            FileLogger.w(TAG, "写入被拒：$holderLabel 想写 $path，已被 ${conflict.holderLabel}（${conflict.pattern}）持有")
            return conflict
        }
        put(holderId, holderLabel, path, segments, now)
        return null
    }

    /** 释放该持有者的全部租约。会话运行结束（成功/取消/异常）与会话被删除都要调，否则只能等 TTL。 */
    fun release(holderId: String) {
        val removed = byHolder.remove(holderId)
        lastActiveAt.remove(holderId)
        if (removed != null && removed.isNotEmpty()) {
            FileLogger.i(TAG, "释放写范围：$holderId → ${removed.keys}")
        }
    }

    /** 该持有者当前持有的全部租约。 */
    fun leasesOf(holderId: String): List<WriteLease> {
        pruneExpired()
        return byHolder[holderId]?.values?.toList().orEmpty()
    }

    /** 全部在册租约，供排查与测试。 */
    fun snapshot(): List<WriteLease> {
        pruneExpired()
        return byHolder.values.flatMap { it.values }
    }

    /**
     * 该持有者写过的**具体文件**：不含通配符的那些租约——[claimForWrite] 认领的一定是具体文件路径。
     *
     * 异常收尾时用它列半成品。声明式范围（`app/src` 这类）指的不是单个文件，枚举它就得扫目录，
     * 所以不含在内：那份范围里到底写过什么，只有第一次写入认领时才看得见。
     */
    fun concreteFilesOf(holderId: String): List<WriteLease> =
        leasesOf(holderId).filter { isConcreteFile(it) }

    /**
     * 全部在册的「具体文件」租约，供 shell 写入的事后比对。
     * 调用方只为这几份文件记指纹，不扫任何目录；没有人在写文件时返回空列表（代价为零）。
     */
    fun concreteFiles(): List<WriteLease> =
        snapshot().filter { isConcreteFile(it) }

    private fun isConcreteFile(lease: WriteLease): Boolean =
        lease.segments.isNotEmpty() && lease.segments.none { it.contains(WriteScopePattern.WILDCARD) }

    /** 把路径规范成「工作区相对」或「容器绝对」的段序列；解析不出内容时返回 null（不拦）。 */
    private fun segmentsOf(path: String, projectRoot: String): List<String>? {
        val raw = path.trim()
        if (raw.isEmpty()) return null
        val canonical = canonicalSegments(raw) ?: return null
        if (canonical.isEmpty()) return null
        // 去掉工作区根前缀后为空 = 路径就是工作区根，不是文件，不参与判定。
        return stripRoot(canonical, projectRoot).takeIf { it.isNotEmpty() }
    }

    /**
     * 把「声明用的模式」规范成判定用的段序列：非通配模式补上尾随的双星号，
     * 语义是「该路径本身及其子树」，派发方不必记「目录要不要带尾斜杠」这种细节。
     * 覆盖整个工作区或整个容器根的模式视为无效声明，返回 null。
     *
     * 等价写法的具体例子见函数体里的行注释：块注释里不能出现「斜杠 + 星号」的组合，
     * Kotlin 的块注释会**嵌套**，写进去会把整个文件后半段吞掉（编译期报一堆 Unresolved reference）。
     */
    private fun scopeOf(pattern: String, projectRoot: String): Pair<String, List<String>>? {
        // 等价写法：`app/src`、`app/src/`、`app/src` 再接双星号后缀，三种归一成同一份段序列。
        val raw = pattern.trim()
        if (raw.isEmpty()) return null
        val canonical = canonicalSegments(raw) ?: return null
        val relative = stripRoot(canonical, projectRoot)
        val effective = if (relative.any { it.contains(WriteScopePattern.WILDCARD) }) {
            relative
        } else {
            relative + WriteScopePattern.ANY_DEPTH
        }
        // 覆盖整个工作区（`.`、双星号、`~/workspace` 及其双星号后缀）的声明等于「独占全部文件」，
        // 会让所有并行都退化成串行，直接拒绝而不是默默接受。
        if (effective.size <= 1 || WriteScopePattern.overlaps(effective, rootSegments(projectRoot))) {
            FileLogger.w(TAG, "忽略过宽的写范围声明：$raw")
            return null
        }
        return raw to effective
    }

    /** 把路径映射成宿主绝对路径再切段：`~/workspace/...`、`$HOME/workspace/...`、相对路径、绝对路径都归一到同一份。 */
    private fun canonicalSegments(path: String): List<String>? = try {
        val host = pathMapper.toHostFile(path).absolutePath.replace('\\', '/')
        // 归一化 `.` / `..`：声明 `.`（或 `./`）时 toHostFile 会原样留下这个点段，
        // 不消掉的话它不会被认成「工作区根」，过宽声明就被默默接受了。
        val segments = mutableListOf<String>()
        host.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            when (segment) {
                "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(segment)
            }
        }
        segments
    } catch (e: Exception) {
        FileLogger.w(TAG, "规范化路径失败: $path", e)
        null
    }

    /** 落在工作区内时去掉工作区根前缀，让工作区内的路径与容器内路径互不混淆。 */
    private fun stripRoot(canonical: List<String>, projectRoot: String): List<String> {
        val root = rootSegments(projectRoot)
        return if (root.isNotEmpty() && canonical.size >= root.size && canonical.subList(0, root.size) == root) {
            canonical.drop(root.size)
        } else {
            canonical
        }
    }

    private fun rootSegments(projectRoot: String): List<String> {
        val root = projectRoot.trim()
        if (root.isEmpty()) return emptyList()
        return try {
            pathMapper.toHostFile(root).absolutePath.replace('\\', '/')
                .split('/').filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun findConflict(
        holderId: String,
        segments: List<String>,
        ignoredHolders: Set<String>
    ): LeaseConflict? =
        byHolder.entries
            .asSequence()
            .filter { it.key != holderId && it.key !in ignoredHolders }
            .flatMap { it.value.values.asSequence() }
            .firstOrNull { WriteScopePattern.overlaps(it.segments, segments) }
            ?.let { LeaseConflict(it.holderId, it.holderLabel, it.pattern) }

    private fun put(
        holderId: String,
        holderLabel: String,
        pattern: String,
        segments: List<String>,
        now: Long
    ) {
        val lease = WriteLease(holderId, holderLabel, pattern, segments, now)
        byHolder.computeIfAbsent(holderId) { ConcurrentHashMap<String, WriteLease>() }[segments.joinToString("/")] = lease
        lastActiveAt[holderId] = now
    }

    /** 交出方持有的、与新范围重叠的租约（派发时转交，避免父子同时持同一路径）。 */
    private fun releaseOverlapping(holderId: String, segmentsList: List<List<String>>) {
        val held = byHolder[holderId] ?: return
        val doomed = held.filterValues { lease ->
            segmentsList.any { WriteScopePattern.overlaps(lease.segments, it) }
        }
        doomed.keys.forEach { held.remove(it) }
        if (held.isEmpty()) byHolder.remove(holderId)
        if (doomed.isNotEmpty()) {
            FileLogger.i(TAG, "写范围转交：$holderId 交出 ${doomed.keys}")
        }
    }

    /** 清掉过期租约（持有者已静默退出）。 */
    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - LEASE_TTL_MS
        val stale = lastActiveAt.entries.filter { it.value < cutoff }.map { it.key }
        stale.forEach { holderId ->
            byHolder.remove(holderId)
            lastActiveAt.remove(holderId)
        }
    }
}

/**
 * 写范围模式的规范化与重叠判定：纯函数，不碰磁盘、不依赖 DI。
 *
 * 模式是「以斜杠分段的路径」：段内单个星号匹配任意字符，整段双星号匹配任意层（含 0 层）。
 * 重叠判定用两条模式的乘积自动机做可达性分析——判的是「是否存在一条路径同时匹配两者」，
 * 而不是「字符串前缀是否相同」。这点很关键：双星号开头的同名文件模式，只会和真正的那个文件冲突，
 * 不会因为字面前缀为空就误判成和整个工作区冲突。
 *
 * 具体例子写在对象体里的行注释：块注释里出现「斜杠 + 星号」会被当成嵌套注释，
 * 而「星号 + 斜杠」会提前把注释关掉，两者都会把文件后半段变成语法错误。
 */
internal object WriteScopePattern {

    // 具体例子：双星号加 `/strings.xml` 只与真正叫 strings.xml 的文件冲突，不与 Foo.kt 冲突。

    const val WILDCARD = "*"
    const val ANY_DEPTH = "**"

    /**
     * 两条模式是否存在共同可匹配的路径。
     *
     * 状态 (i, j) 表示「两条模式各自已消费到第 i / 第 j 段」，转移时两条模式必须消费同一个段。
     * `**` 既能消费 0 段（跳过的闭包）也能消费任意多段（停留在原状态）。
     */
    fun overlaps(a: List<String>, b: List<String>): Boolean {
        // 空模式不匹配任何路径：不显式挡掉的话「两条都消费完」会在 (0,0) 直接成立。
        if (a.isEmpty() || b.isEmpty()) return false
        val n = a.size
        val m = b.size
        val width = m + 1
        val seen = BooleanArray((n + 1) * width)
        val queue = ArrayDeque<Int>()

        fun push(i: Int, j: Int) {
            val key = i * width + j
            if (!seen[key]) {
                seen[key] = true
                queue.addLast(key)
            }
        }

        push(0, 0)
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val i = key / width
            val j = key % width
            if (i == n && j == m) return true

            // `**` 匹配 0 段：跳过它
            if (i < n && a[i] == ANY_DEPTH) push(i + 1, j)
            if (j < m && b[j] == ANY_DEPTH) push(i, j + 1)

            if (i < n && j < m) {
                when {
                    // 两边都是 `**`：可以共同消费任意多段，等价于分别跳过后再对齐
                    a[i] == ANY_DEPTH && b[j] == ANY_DEPTH -> push(i + 1, j + 1)
                    // 一边是 `**`：它吃掉对面的这一段，自己留在 `**` 上继续吃
                    a[i] == ANY_DEPTH -> push(i, j + 1)
                    b[j] == ANY_DEPTH -> push(i + 1, j)
                    segmentOverlaps(a[i], b[j]) -> push(i + 1, j + 1)
                }
            }
        }
        return false
    }

    /** 单个段内的两条模式是否存在共同可匹配的字符串（`*` 匹配任意字符序列，含空串）。 */
    private fun segmentOverlaps(p: String, q: String): Boolean {
        if (p == q) return true
        if (WILDCARD !in p && WILDCARD !in q) return false

        val n = p.length
        val m = q.length
        val seen = Array(n + 1) { BooleanArray(m + 1) }
        val queue = ArrayDeque<Int>()

        fun push(i: Int, j: Int) {
            if (!seen[i][j]) {
                seen[i][j] = true
                queue.addLast(i * (m + 1) + j)
            }
        }

        push(0, 0)
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val i = key / (m + 1)
            val j = key % (m + 1)
            if (i == n && j == m) return true

            // `*` 匹配空串：跳过它
            if (i < n && p[i] == WILDCARD_CHAR) push(i + 1, j)
            if (j < m && q[j] == WILDCARD_CHAR) push(i, j + 1)

            if (i < n && j < m) {
                val pc = p[i]
                val qc = q[j]
                when {
                    // 两个 `*` 共同消费字符后仍各自停在 `*` 上，状态不变（已访问），无需再入队
                    pc == WILDCARD_CHAR && qc == WILDCARD_CHAR -> Unit
                    pc == WILDCARD_CHAR -> push(i, j + 1)
                    qc == WILDCARD_CHAR -> push(i + 1, j)
                    pc == qc -> push(i + 1, j + 1)
                }
            }
        }
        return false
    }

    private const val WILDCARD_CHAR = '*'
}
