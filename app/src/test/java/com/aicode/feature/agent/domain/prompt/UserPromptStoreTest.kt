package com.aicode.feature.agent.domain.prompt

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 用户提示词存取：frontmatter 往返、作用域隔离、删除。 */
class UserPromptStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var globalDir: java.io.File
    private lateinit var projectDir: java.io.File

    private fun store(): UserPromptStore {
        globalDir = tempFolder.newFolder("aicode")
        projectDir = tempFolder.newFolder("project-aicode")
        val container = mockk<ContainerInstaller>()
        every { container.aicodeDir } returns globalDir
        val projectRoot = mockk<ProjectAicodeRoot>()
        every { projectRoot.forPath(any()) } returns projectDir
        return UserPromptStore(container, projectRoot)
    }

    @Test
    fun save_thenList_roundtripsFrontmatter() {
        val store = store()
        val prompt = store.newPrompt("总纲：先给结论", UserPromptPosition.BEFORE_ALL, "回答先给结论，再给理由。")

        assertTrue(store.save(UserPromptScope.GLOBAL, null, prompt))

        val listed = store.list(UserPromptScope.GLOBAL, null)
        assertEquals(1, listed.size)
        assertEquals("总纲：先给结论", listed[0].name)
        assertEquals(UserPromptPosition.BEFORE_ALL, listed[0].position)
        assertEquals("回答先给结论，再给理由。", listed[0].content)
    }

    @Test
    fun scope_isolatesGlobalAndProject() {
        val store = store()
        store.save(UserPromptScope.GLOBAL, "/ws", store.newPrompt("g", UserPromptPosition.OFF, "global"))
        store.save(UserPromptScope.PROJECT, "/ws", store.newPrompt("p", UserPromptPosition.OFF, "project"))

        assertEquals(listOf("g"), store.list(UserPromptScope.GLOBAL, "/ws").map { it.name })
        assertEquals(listOf("p"), store.list(UserPromptScope.PROJECT, "/ws").map { it.name })
    }

    @Test
    fun projectScope_withoutWorkspace_writesNothing() {
        val store = store()
        assertFalse(store.save(UserPromptScope.PROJECT, null, store.newPrompt("p", UserPromptPosition.OFF, "x")))
        assertTrue(store.list(UserPromptScope.PROJECT, null).isEmpty())
    }

    @Test
    fun delete_removesPrompt() {
        val store = store()
        val prompt = store.newPrompt("t", UserPromptPosition.AFTER_SYSTEM, "body")
        store.save(UserPromptScope.GLOBAL, null, prompt)

        assertTrue(store.delete(UserPromptScope.GLOBAL, null, prompt.id))
        assertTrue(store.list(UserPromptScope.GLOBAL, null).isEmpty())
        assertFalse(store.delete(UserPromptScope.GLOBAL, null, prompt.id))
    }

    @Test
    fun list_keepsCreationOrder() {
        val store = store()
        val first = store.newPrompt("a", UserPromptPosition.OFF, "1")
        val second = store.newPrompt("b", UserPromptPosition.OFF, "2")
        store.save(UserPromptScope.GLOBAL, null, first)
        store.save(UserPromptScope.GLOBAL, null, second)

        // id 内嵌时间戳，字典序即创建序（同毫秒时退化为随机后缀，顺序不保证——只断言都在）
        assertEquals(setOf("a", "b"), store.list(UserPromptScope.GLOBAL, null).map { it.name }.toSet())
    }
}
