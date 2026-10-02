package com.aicode.feature.agent.domain.schedule

import com.aicode.feature.workspace.domain.WorkspacePathMapper
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写范围租约：模式重叠判定（纯函数）与持有/冲突/转交/释放（登记表）。
 *
 * 重叠判定是这套机制的安全边界——判错一个「不重叠」，结果就是两个子代理把同一个文件互相覆盖，
 * 正是它要防的事。所以这里按「存在共同可匹配路径」逐类覆盖。
 */
class WriteLeaseRegistryTest {

    private companion object {
        const val ROOT = "/host/ws"
    }

    /** 复刻 WorkspacePathMapper 的关键语义：`~/workspace/...`、相对路径、绝对路径归一到同一份宿主路径。 */
    private fun fakeMapper(): WorkspacePathMapper = mockk<WorkspacePathMapper>().apply {
        every { toHostFile(any()) } answers {
            val p = firstArg<String>().trim().replace('\\', '/')
            when {
                p.startsWith("/host/") || p == "/host" -> File(p)
                p.startsWith("~/workspace") -> File(ROOT, p.removePrefix("~/workspace").trimStart('/'))
                p.startsWith("/") -> File(ROOT, p.trimStart('/'))
                else -> File(ROOT, p)
            }
        }
    }

    private fun registry() = WriteLeaseRegistry(fakeMapper())

    // ---------- 模式重叠 ----------

    @Test
    fun overlaps_directoryPrefixCoversFile() {
        assertTrue(WriteScopePattern.overlaps(listOf("app", "src", "**"), listOf("app", "src", "Foo.kt")))
        assertTrue(WriteScopePattern.overlaps(listOf("app", "**"), listOf("app", "src", "Foo.kt")))
    }

    @Test
    fun overlaps_siblingFilesDoNotConflict() {
        assertFalse(WriteScopePattern.overlaps(listOf("app", "src", "Foo.kt", "**"), listOf("app", "src", "Bar.kt")))
        // 关键：`Foo.kt` 不能因为字符串前缀相同就吃掉 `Foo.kt.bak`
        assertFalse(WriteScopePattern.overlaps(listOf("app", "src", "Foo.kt", "**"), listOf("app", "src", "Foo.kt.bak")))
    }

    @Test
    fun overlaps_anyDepthOnlyConflictsWithRealTarget() {
        assertTrue(WriteScopePattern.overlaps(listOf("**", "strings.xml"), listOf("app", "res", "values", "strings.xml")))
        // 空字面前缀不等于「和工作区所有路径冲突」
        assertFalse(WriteScopePattern.overlaps(listOf("**", "strings.xml"), listOf("app", "res", "values", "colors.xml")))
    }

    @Test
    fun overlaps_segmentWildcard() {
        assertTrue(WriteScopePattern.overlaps(listOf("app", "*", "Foo.kt"), listOf("app", "src", "Foo.kt")))
        assertTrue(WriteScopePattern.overlaps(listOf("strings.xml"), listOf("*.xml")))
        assertFalse(WriteScopePattern.overlaps(listOf("strings.xml"), listOf("*.json")))
        assertTrue(WriteScopePattern.overlaps(listOf("a*b"), listOf("aXb")))
        assertFalse(WriteScopePattern.overlaps(listOf("a*b"), listOf("ab.json")))
    }

    @Test
    fun overlaps_emptyPatternNeverMatches() {
        assertFalse(WriteScopePattern.overlaps(emptyList(), listOf("a")))
        assertFalse(WriteScopePattern.overlaps(listOf("a"), emptyList()))
    }

    // ---------- 登记表 ----------

    @Test
    fun claim_thenOtherHolderIsRejected() {
        val reg = registry()
        assertTrue(reg.claim("sub-1", "改登录", listOf("app/src/login"), ROOT).isEmpty())

        val conflict = reg.claimForWrite("sub-2", "改设置", "app/src/login/Login.kt", ROOT)
        assertNotNull(conflict)
        assertEquals("sub-1", conflict!!.holderId)
        assertEquals("改登录", conflict.holderLabel)
    }

    @Test
    fun claimForWrite_sameHolderIsAlwaysAllowed() {
        val reg = registry()
        assertNull(reg.claimForWrite("sub-1", "改登录", "app/src/login/Login.kt", ROOT))
        assertNull(reg.claimForWrite("sub-1", "改登录", "app/src/login/Login.kt", ROOT))
        // 同一持有者的第二份范围不能被第一份挤掉
        assertNull(reg.claimForWrite("sub-1", "改登录", "app/src/settings/Settings.kt", ROOT))
        assertEquals(2, reg.leasesOf("sub-1").size)
        assertNotNull(reg.claimForWrite("sub-2", "别的", "app/src/login/Login.kt", ROOT))
        assertNotNull(reg.claimForWrite("sub-2", "别的", "app/src/settings/Settings.kt", ROOT))
    }

    @Test
    fun claimForWrite_normalizesAliases() {
        val reg = registry()
        assertNull(reg.claimForWrite("sub-1", "改登录", "~/workspace/app/src/Login.kt", ROOT))
        // 同一个文件的另外一种写法必须命中同一份租约
        assertNotNull(reg.claimForWrite("sub-2", "改设置", "app/src/Login.kt", ROOT))
    }

    @Test
    fun claim_isAtomicOnPartialConflict() {
        val reg = registry()
        assertTrue(reg.claim("sub-1", "改登录", listOf("app/src/login"), ROOT).isEmpty())

        val conflicts = reg.claim("sub-2", "改多个", listOf("app/src/other", "app/src/login"), ROOT)
        assertEquals(1, conflicts.size)
        // 整批原子：没冲突的那份也不能落库，否则调用方以为成功了
        assertTrue(reg.leasesOf("sub-2").isEmpty())
    }

    @Test
    fun claim_handoverFromReleasesParentScope() {
        val reg = registry()
        assertNull(reg.claimForWrite("parent", "主会话", "app/src/Login.kt", ROOT))
        // 父会话自己的租约不该拦住它派子代理继续改同一个文件
        assertTrue(reg.claim("sub-1", "改登录", listOf("app/src/Login.kt"), ROOT, setOf("parent")).isEmpty())
        // 转交之后父会话写同一文件被拦
        assertNotNull(reg.claimForWrite("parent", "主会话", "app/src/Login.kt", ROOT))
    }

    @Test
    fun claim_rejectsWorkspaceWideScope() {
        val reg = registry()
        listOf(".", "", "**", "~/workspace", "~/workspace/**").forEach { pattern ->
            assertTrue("应忽略过宽范围: $pattern", reg.claim("sub-x", "占地", listOf(pattern), ROOT).isEmpty())
            assertTrue("过宽范围不该登记: $pattern", reg.snapshot().isEmpty())
        }
    }

    @Test
    fun release_freesScopeForOthers() {
        val reg = registry()
        assertNull(reg.claimForWrite("sub-1", "改登录", "app/src/Login.kt", ROOT))
        reg.release("sub-1")
        assertNull(reg.claimForWrite("sub-2", "改设置", "app/src/Login.kt", ROOT))
    }

    @Test
    fun claim_conflictMessageNamesHolderAndNextAction() {
        val reg = registry()
        assertTrue(reg.claim("sub-1", "改登录", listOf("app/src/login"), ROOT).isEmpty())
        val message = reg.claimForWrite("sub-2", "改设置", "app/src/login/Login.kt", ROOT)
            ?.describeForWrite("app/src/login/Login.kt")
            .orEmpty()
        assertTrue(message, message.contains("改登录"))
        assertTrue(message, message.contains("app/src/login"))
        assertTrue(message, message.contains("messageParent"))
    }

    // ---------- 具体文件：半成品备份与 shell 盯防的依据 ----------

    @Test
    fun concreteFiles_onlyHoldsPathsClaimedForWrite() {
        val reg = registry()
        assertNull(reg.claimForWrite("sub-1", "改登录", "app/src/Login.kt", ROOT))
        assertTrue(reg.claim("sub-1", "改登录", listOf("app/src/login"), ROOT).isEmpty())

        // 写入认领的是具体文件；声明的范围（补了双星号的那份）不是单个文件，不列进来
        assertEquals(listOf("app/src/Login.kt"), reg.concreteFiles().map { it.pattern })
        assertEquals(listOf("app/src/Login.kt"), reg.concreteFilesOf("sub-1").map { it.pattern })
        assertTrue(reg.concreteFilesOf("sub-2").isEmpty())
    }

    @Test
    fun concreteFiles_doNotSurviveRelease() {
        val reg = registry()
        assertNull(reg.claimForWrite("sub-1", "改登录", "app/src/Login.kt", ROOT))
        reg.release("sub-1")

        assertTrue(reg.concreteFiles().isEmpty())
    }

    @Test
    fun mainAgentMessageNamesHolderAndGivesWayOut() {
        val reg = registry()
        assertNull(reg.claimForWrite("sub-1", "改登录", "app/src/Login.kt", ROOT))

        val message = reg.claimForWrite("parent", "主会话", "app/src/Login.kt", ROOT)
            ?.describeForMainAgent("app/src/Login.kt")
            .orEmpty()

        assertTrue(message, message.contains("改登录"))
        assertTrue(message, message.contains("app/src/Login.kt"))
        assertTrue(message, message.contains("task(action=\"stop\""))
        assertTrue(message, message.contains("task(action=\"send\""))
        // 主代理那边是「照常执行 + 警告」，不能说成已被拒绝
        assertFalse(message, message.contains("已拒绝"))
    }
}
