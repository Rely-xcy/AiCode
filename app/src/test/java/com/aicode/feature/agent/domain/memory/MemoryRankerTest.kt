package com.aicode.feature.agent.domain.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRankerTest {

    private fun memory(
        name: String,
        description: String = "",
        content: String = "",
        modifiedAt: Long = 0L,
        scope: MemoryScope = MemoryScope.GLOBAL,
        pinned: Boolean = false
    ) =
        Memory(
            name = name,
            description = description,
            scope = scope,
            file = FakeFile(modifiedAt),
            content = content,
            kind = MemoryKind.PROFILE,
            pinned = pinned
        )

    @Test
    fun `条数不超上限时原样返回`() {
        val list = listOf(memory("a"), memory("b"))
        assertEquals(list, MemoryRanker.rank(list, "随便问点什么", 3))
    }

    @Test
    fun `按与当前问题的重合度挑选`() {
        val memories = listOf(
            memory("banana-preference", "喜欢香蕉"),
            memory("editor-preference", "编辑器用 Neovim，键位自己改过"),
            memory("shell-preference", "shell 用 zsh"),
            memory("git-remote-preference", "远端走 ssh 而不是 https")
        )

        val picked = MemoryRanker.rank(memories, "我这个 neovim 的键位怎么改？", 2)

        assertEquals("editor-preference", picked.first().name)
    }

    @Test
    fun `中文按二元组命中`() {
        val memories = listOf(
            memory("build-env", "构建环境用容器，本地跑不了 gradle"),
            memory("color-preference", "界面主色偏蓝"),
            memory("font-preference", "字体用思源黑体")
        )

        val picked = MemoryRanker.rank(memories, "为什么本地 gradle 编译不过？", 1)

        assertEquals("build-env", picked.first().name)
    }

    @Test
    fun `名称权重高于正文`() {
        val byName = memory("docker-preference", "", "")
        val byContent = memory("misc", "", "正文里提了一次 docker 而已")
        val memories = listOf(byContent, memory("unrelated", "无关"), byName)

        val picked = MemoryRanker.rank(memories, "docker 怎么配", 1)

        assertEquals("docker-preference", picked.first().name)
    }

    @Test
    fun `分数相同按更新时间新的优先`() {
        val older = memory("old-note", "项目约定", modifiedAt = 1_000L)
        val newer = memory("new-note", "项目约定", modifiedAt = 2_000L)
        val memories = listOf(older, memory("filler", "无关"), newer)

        val picked = MemoryRanker.rank(memories, "项目约定是什么", 1)

        assertEquals("new-note", picked.first().name)
    }

    @Test
    fun `问题没有任何可用词时退化为按更新时间排`() {
        val older = memory("old-note", modifiedAt = 1_000L)
        val newer = memory("new-note", modifiedAt = 5_000L)
        val memories = listOf(older, memory("filler"), newer)

        val picked = MemoryRanker.rank(memories, "？", 1)

        assertEquals("new-note", picked.first().name)
    }

    @Test
    fun `近似重复的记忆只占一个坑位`() {
        val memories = listOf(
            memory("build-env", "构建环境用容器，本地跑不了 gradle，只能靠 CI"),
            memory("build-env-copy", "构建环境用容器，本地跑不了 gradle，只能靠 CI"),
            memory("editor-preference", "编辑器用 Neovim")
        )

        val picked = MemoryRanker.rank(memories, "本地构建怎么弄", 2)

        // 两条近似重复的只能进来一条，另一个坑位留给其它记忆。
        // limit 必须小于候选条数：rank 在「条数不超上限」时原样返回（不跑去重，保证每条都每轮参与竞争），
        // limit=3 时这个用例根本没走到去重那一步。
        assertEquals(2, picked.size)
        assertTrue(picked.any { it.name == "editor-preference" })
    }

    @Test
    fun `分词：ASCII 词与中文二元组`() {
        val tokens = MemoryRanker.tokenize("用 Neovim 改键位，keymap")
        assertTrue("neovim" in tokens)
        assertTrue("keymap" in tokens)
        assertTrue("键位" in tokens)
        // 单字不成词
        assertTrue("用" !in tokens)
    }

    @Test
    fun `select：置顶记忆无条件入选`() {
        val memories = listOf(
            memory("unrelated-pinned", "完全无关的置顶条目", pinned = true),
            memory("topic-a", "话题相关 A"),
            memory("topic-b", "话题相关 B"),
            memory("topic-c", "话题相关 C"),
            memory("topic-d", "话题相关 D")
        )

        val picked = MemoryRanker.select(memories, "话题", limit = 2, globalReserved = 0)

        assertEquals(2, picked.size)
        assertEquals("unrelated-pinned", picked.first().name)
    }

    @Test
    fun `select：全局记忆保底若干坑位`() {
        val memories = listOf(
            memory("proj-a", "项目里的构建约定", scope = MemoryScope.PROJECT),
            memory("proj-b", "项目里的发布约定", scope = MemoryScope.PROJECT),
            memory("proj-c", "项目里的测试约定", scope = MemoryScope.PROJECT),
            memory("proj-d", "项目里的目录约定", scope = MemoryScope.PROJECT),
            memory("profile", "用户画像：偏好简洁", scope = MemoryScope.GLOBAL)
        )

        // 话题只命中项目记忆的字面；全局画像字面不重合，但保底 1 个坑位仍要进来
        val picked = MemoryRanker.select(memories, "项目的构建约定怎么定的", limit = 3, globalReserved = 1)

        assertEquals(3, picked.size)
        assertTrue(picked.any { it.name == "profile" })
    }

    @Test
    fun `select：无全局记忆时保底退化为话题召回`() {
        val memories = listOf(
            memory("proj-a", "构建约定", scope = MemoryScope.PROJECT),
            memory("proj-b", "发布约定", scope = MemoryScope.PROJECT),
            memory("proj-c", "测试约定", scope = MemoryScope.PROJECT),
            memory("proj-d", "目录约定", scope = MemoryScope.PROJECT)
        )

        val picked = MemoryRanker.select(memories, "构建约定", limit = 2, globalReserved = 2)

        assertEquals(2, picked.size)
        assertEquals("proj-a", picked.first().name)
    }

    @Test
    fun `select：条数不超上限时原样返回`() {
        val list = listOf(memory("a"), memory("b", pinned = true))
        assertEquals(list, MemoryRanker.select(list, "随便问点什么", limit = 3))
    }
}

/** 只用来给排序器提供「最近更新」时间的最小 File 替身。 */
private class FakeFile(private val modified: Long) : java.io.File("/tmp/fake") {
    override fun lastModified(): Long = modified
}
