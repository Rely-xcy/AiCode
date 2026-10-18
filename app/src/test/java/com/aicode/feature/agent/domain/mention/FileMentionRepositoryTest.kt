package com.aicode.feature.agent.domain.mention

import com.aicode.feature.agent.domain.container.CommandEngine
import kotlinx.coroutines.runBlocking
import com.aicode.feature.agent.domain.container.CommandResult
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** @ 文件引用的清单拉取与模糊匹配。 */
class FileMentionRepositoryTest {

    private val engine = mockk<CommandEngine>(relaxed = true)
    private val repository = FileMentionRepository(engine)

    private val files = listOf(
        "chat.md",
        "docs/chat.md",
        "ChatInputBar.kt",
        "a/b/c.txt"
    )

    @Test
    fun match_emptyQuery_returnsLeadingItemsWithinLimit() {
        assertEquals(files.take(2), repository.match(files, "", limit = 2))
        assertEquals(files, repository.match(files, ""))
    }

    @Test
    fun match_subsequence_filtersAndRanksByGapThenLength() {
        // 贪心子序列匹配：chat.md、ChatInputBar.kt 从首字符起连续命中（gap 0），按长度排前后；
        // docs/chat.md 的 "docs" 里先有个 c 被吃掉、要跨段补全，gap 更大排最后。
        assertEquals(
            listOf("chat.md", "ChatInputBar.kt", "docs/chat.md"),
            repository.match(files, "chat")
        )
    }

    @Test
    fun match_isCaseInsensitive() {
        assertEquals(listOf("ChatInputBar.kt"), repository.match(files, "CIB"))
    }

    @Test
    fun match_noMatch_returnsEmpty() {
        assertTrue(repository.match(files, "zzzz").isEmpty())
    }

    @Test
    fun match_zeroLimit_returnsEmpty() {
        assertTrue(repository.match(files, "chat", limit = 0).isEmpty())
    }

    /** 逐键延长查询时，会话内的增量筛选必须与每次从头扫全量得到的结果完全一致。 */
    @Test
    fun match_incrementalQueryMatchesFullScan() {
        val typed = listOf("c", "ch", "cha", "chat", "chati", "chat.md")
        val incremental = typed.map { repository.match(files, it) }
        val fresh = typed.map { FileMentionRepository(engine).match(files, it) }
        assertEquals(fresh, incremental)
    }

    /** 查询变短或换向（删字符）后也要回到全量筛选，结果同样一致。 */
    @Test
    fun match_shorterQueryAfterLongerMatchesFullScan() {
        repository.match(files, "chatinput")
        assertEquals(
            FileMentionRepository(engine).match(files, "chat"),
            repository.match(files, "chat")
        )
        assertEquals(
            FileMentionRepository(engine).match(files, "a"),
            repository.match(files, "a")
        )
    }

    /** 上一次查询带过 limit 也不能把增量筛选的候选集缩小。 */
    @Test
    fun match_narrowingIgnoresPreviousLimit() {
        repository.match(files, "c", limit = 1)
        assertEquals(
            FileMentionRepository(engine).match(files, "chat"),
            repository.match(files, "chat")
        )
    }

    @Test
    fun match_limitKeepsTopOrdering() {
        val all = repository.match(files, "chat")
        assertEquals(all.take(1), repository.match(files, "chat", limit = 1))
        assertEquals(all.take(2), repository.match(files, "chat", limit = 2))
    }

    @Test
    fun loadFiles_success_parsesAndDeduplicatesLines() = runBlocking {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns CommandResult(
            "a.kt\nsub/b.kt\n\na.kt\n",
            0
        )
        val result = repository.loadFiles("/root/workspace") as FileMentionRepository.LoadResult.Success
        assertEquals(listOf("a.kt", "sub/b.kt"), result.files)
    }

    @Test
    fun loadFiles_containerNotReady_returnsUnavailable() = runBlocking {
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } returns null
        assertTrue(
            repository.loadFiles("/root/workspace") is FileMentionRepository.LoadResult.Unavailable
        )
    }

    @Test
    fun loadFiles_blankPath_returnsUnavailable() = runBlocking {
        assertTrue(repository.loadFiles("") is FileMentionRepository.LoadResult.Unavailable)
    }
}
