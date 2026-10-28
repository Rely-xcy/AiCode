package com.aicode.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRankerSearchTest {

    private fun memory(name: String, description: String, content: String = "") = Memory(
        name = name,
        description = description,
        scope = MemoryScope.PROJECT,
        content = content
    )

    @Test
    fun search_matchesChineseKeywordInContent() {
        val memories = listOf(
            memory("android-env", "本机 Android 环境", "无 JDK，Kotlin 项目不能编译"),
            memory("writing-style", "写作偏好", "少用形容词")
        )

        val hits = MemoryRanker.search(memories, "编译", 20)

        assertEquals(listOf("android-env"), hits.map { it.name })
    }

    @Test
    fun search_ranksNameHitAboveContentHit() {
        val memories = listOf(
            memory("content-only", "无关描述", "这里提了一句 apk 反编译"),
            memory("apk-decompile", "描述也无关")
        )

        val hits = MemoryRanker.search(memories, "apk", 20)

        assertEquals(listOf("apk-decompile", "content-only"), hits.map { it.name })
    }

    @Test
    fun search_noMatchReturnsEmpty() {
        val memories = listOf(memory("writing-style", "写作偏好", "少用形容词"))

        assertTrue(MemoryRanker.search(memories, "反编译", 20).isEmpty())
    }

    @Test
    fun search_respectsLimit() {
        val memories = (1..5).map { memory("apk-$it", "apk 相关") }

        assertEquals(2, MemoryRanker.search(memories, "apk", 2).size)
    }
}
