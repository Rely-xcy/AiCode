package com.aicode.feature.agent.domain.vscreen

/**
 * 控件树指纹：注入前后只比它，不保留两棵树全文，也不把整棵树拼成字符串再比较。
 *
 * 只覆盖前 [COMPARE_NODES] 个节点。vscreen_observe 的 limit 可以很大（默认 200、上限 500），
 * 对比读取只取前 COMPARE_NODES 个——两者取的是同一棵树的前缀，所以两种 limit 之间的指纹可以直接比较。
 */
data class UiTreeSnapshot(
    /** 本次实际取到的节点数；[truncated] 为 true 时它只是前缀长度，不是全树节点数。 */
    val nodeCount: Int,
    /** 取树时是否已达 limit。 */
    val truncated: Boolean,
    /** 前 [COMPARE_NODES] 个节点内容（层级、类名、文本、坐标、可点/可编辑）的滚动哈希。 */
    val hash: Long
) {

    /**
     * 能否拿来当注入前的对照。
     *
     * limit 比 [COMPARE_NODES] 还小时拿到的是更短的前缀，与对比读取出的前缀长度不同，
     * 比出来必然是「变了」——这种快照只能弃用，让调用方现读一次。
     */
    val coversComparePrefix: Boolean get() = !truncated || nodeCount >= COMPARE_NODES

    /** 两份快照描述的是不是同一个界面。 */
    fun sameUiAs(other: UiTreeSnapshot): Boolean = hash == other.hash

    companion object {
        /** 对比读取的节点数上限，也是指纹覆盖的深度：两处必须同源，否则指纹不可比。 */
        const val COMPARE_NODES = 40

        fun of(nodes: List<UiNode>, limit: Int): UiTreeSnapshot {
            var hash = FNV_OFFSET
            val covered = minOf(nodes.size, COMPARE_NODES)
            for (index in 0 until covered) {
                val node = nodes[index]
                hash = mix(hash, index.toLong())
                hash = mix(hash, node.depth.toLong())
                hash = mix(hash, node.className.hashCode().toLong())
                hash = mix(hash, node.text?.hashCode()?.toLong() ?: HASH_ABSENT)
                hash = mix(hash, node.contentDescription?.hashCode()?.toLong() ?: HASH_ABSENT)
                hash = mix(hash, node.viewId?.hashCode()?.toLong() ?: HASH_ABSENT)
                hash = mix(hash, node.bounds.hashCode().toLong())
                hash = mix(hash, if (node.clickable) 1L else 0L)
                hash = mix(hash, if (node.editable) 1L else 0L)
                hash = mix(hash, if (node.password) 1L else 0L)
            }
            // 覆盖到的节点数也进哈希：只有末尾少了一个节点、内容都相同也要算变化。
            hash = mix(hash, covered.toLong())
            return UiTreeSnapshot(nodeCount = nodes.size, truncated = nodes.size >= limit, hash = hash)
        }

        /** FNV-1a 的 64 位一步；Long 乘法溢出按位截断，正是这里要的。 */
        private fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME

        /** 0xcbf29ce484222325 的有符号写法（FNV-1a offset basis）。 */
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 0x100000001b3L

        /** 字段为 null 时的占位：与任何真实 hash 值区分开。 */
        private const val HASH_ABSENT = Long.MIN_VALUE
    }
}
