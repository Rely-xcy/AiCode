package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RootfsSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ContainerSettingsRepository] 的「解码失败绝不覆写」护栏，以及合并/删除的纯逻辑。
 *
 * 背景：读路径与写路径过去都用 `runCatching { decode }.getOrNull() ?: emptyList()`——
 * 坏数据被当成空表，下一次 upsert/delete 就把整份容器清单写回成空表，正是用户报的
 * 「导入的容器在容器列表里消失」。现在 [decodeProfiles] / [decodeDownloadedImages] 对坏数据
 * 返回 null，写路径据此 `?: return@edit` 跳过写入。
 *
 * 覆盖：
 *  - 坏数据不被当成空表（decode 返回 null）、只有「从未写过」的 null 才当空表；
 *  - [mergeProfiles] / [removeProfile] / [mergeDownloadedImage] / [removeDownloadedImageEntry] 的纯逻辑；
 *  - 真实 DataStore 下 upsert 不丢其它 profile、delete 只删目标、ensureBuiltinDefault 只补内置项。
 *
 * ① 的端到端版本（往 DataStore 里预置坏 JSON → upsert → 断言原始字符串未变）写不了：
 * DataStore 里的原始字符串只能经本仓库的公开写路径落盘，而这些方法不接受任意字符串；
 * 直接手写 preferences_pb（protobuf）注入坏值过于依赖 DataStore 内部实现，且本容器 JVM 无法启动
 * （PaX/MPROTECT 拦截可执行内存页）无法编译验证。故这里用 `decodeProfiles(坏数据) == null` 钉住
 * 「守卫触发」这一步，写路径的 `?: return@edit` 由源码核对（ContainerSettingsRepository.kt:169 / :218 / :228）。
 *
 * DataStore 用 mockk 出来的 Context + 临时目录构造，写法照抄
 * [com.aicode.feature.workspace.data.repository.WorkspaceRepositoryCurrentPathTest]。
 */
class ContainerSettingsRepositoryTest {

    private val context = mockk<Context>()

    /**
     * `preferencesDataStore` 委托在 JVM 内是单例：缓存首次传入的 Context 与目录，之后所有
     * 用例共用同一份 DataStore。故目录不删除，且各用例只用各自独有的 id、断言用包含/不包含，
     * 避免互相污染。
     */
    private val filesDir: File by lazy { Files.createTempDirectory("container-settings-repo-test").toFile() }

    private fun createRepository(): ContainerSettingsRepository {
        // 构造期就会访问这两处：DataStore 委托取 applicationContext.filesDir 定位
        // filesDir/datastore/container_prefs.preferences_pb。
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
        return ContainerSettingsRepository(context)
    }

    private fun profile(id: String, createdAt: Long = 1L) = ContainerProfile(
        id = id,
        name = id,
        rootfsSource = RootfsSource.LocalFile("file:///tmp/$id.tar.gz"),
        shellPath = "/bin/sh",
        isBuiltin = false,
        createdAt = createdAt
    )

    // ── ① 坏数据不当空表：decode 返回 null，写路径据此跳过覆写 ─────────────────

    @Test
    fun decodeProfiles_nullRaw_isEmptyList_onlyWhenNeverWritten() {
        assertEquals(emptyList<ContainerProfile>(), decodeProfiles(null))
    }

    @Test
    fun decodeProfiles_badJson_returnsNull_notEmptyList() {
        // 旧实现 runCatching{...}.getOrNull() ?: emptyList() 在这三行都会得到空表，
        // 写回即把整份清单清空；现在返回 null，调用方 `?: return@edit` 不写。
        assertNull(decodeProfiles("这不是 JSON"))
        assertNull(decodeProfiles("""[{"id":"custom-1"}]""")) // 缺 name/rootfsSource 等必填字段
        assertNull(decodeProfiles("[] trailing-garbage"))
    }

    @Test
    fun decodeProfiles_validJson_roundTrips() {
        val list = listOf(profile("custom-a"), profile("custom-b"))
        assertEquals(list, decodeProfiles(encodeProfiles(list)))
    }

    @Test
    fun decodeDownloadedImages_nullRaw_isEmptyList_badRaw_isNull() {
        assertEquals(emptyList<DownloadedImageRecord>(), decodeDownloadedImages(null))
        assertNull(decodeDownloadedImages("{oops"))
    }

    // ── ② 合并保留其它 profile（纯逻辑） ─────────────────────────────────────

    @Test
    fun mergeProfiles_keepsOthers_andAppendsUpdated() {
        val a = profile("a")
        val b = profile("b")
        val updated = profile("a", createdAt = 5L).copy(name = "A2")
        val merged = mergeProfiles(listOf(a, b), updated, now = 999L)
        assertEquals(listOf("b", "a"), merged.map { it.id })
        assertEquals("A2", merged.last().name)
    }

    @Test
    fun mergeProfiles_keepsExistingCreatedAt_whenIncomingIsZero() {
        val existing = profile("a", createdAt = 123L)
        val incoming = profile("a", createdAt = 0L)
        assertEquals(123L, mergeProfiles(listOf(existing), incoming, now = 999L).single().createdAt)
    }

    @Test
    fun mergeProfiles_usesNow_forBrandNewWithZeroCreatedAt() {
        val merged = mergeProfiles(emptyList(), profile("new", createdAt = 0L), now = 999L)
        assertEquals(999L, merged.single().createdAt)
    }

    // ── ③ 删除只动目标（纯逻辑） ─────────────────────────────────────────────

    @Test
    fun removeProfile_removesOnlyTarget() {
        val a = profile("a")
        val b = profile("b")
        assertEquals(listOf("b"), removeProfile(listOf(a, b), "a").map { it.id })
        // 目标不存在时原样返回，不清表
        assertEquals(listOf("a", "b"), removeProfile(listOf(a, b), "missing").map { it.id })
    }

    @Test
    fun downloadedImageMergeAndRemove_areScopedToEntryId() {
        val r1 = DownloadedImageRecord("e1", "uri://1")
        val r2 = DownloadedImageRecord("e2", "uri://2")
        assertEquals(listOf("e1", "e2"), mergeDownloadedImage(listOf(r1), r2).map { it.entryId })
        assertEquals(listOf("e2"), removeDownloadedImageEntry(listOf(r1, r2), "e1").map { it.entryId })
    }

    // ── 真实 DataStore：写路径不清其它条目 ───────────────────────────────────

    @Test
    fun upsertCustomProfile_keepsOtherProfiles() = runBlocking {
        val repo = createRepository()
        repo.upsertCustomProfile(profile("it-upsert-a"))
        repo.upsertCustomProfile(profile("it-upsert-b"))
        val ids = repo.customProfilesFlow.first().map { it.id }
        assertTrue("it-upsert-a" in ids)
        assertTrue("it-upsert-b" in ids)
    }

    @Test
    fun deleteCustomProfile_removesOnlyTarget() = runBlocking {
        val repo = createRepository()
        repo.upsertCustomProfile(profile("it-del-a"))
        repo.upsertCustomProfile(profile("it-del-b"))
        repo.deleteCustomProfile("it-del-a")
        val ids = repo.customProfilesFlow.first().map { it.id }
        assertFalse("it-del-a" in ids)
        assertTrue("it-del-b" in ids)
    }

    @Test
    fun ensureBuiltinDefault_prependsBuiltin_withoutDroppingExisting() = runBlocking {
        val repo = createRepository()
        repo.upsertCustomProfile(profile("it-ens-a"))
        repo.ensureBuiltinDefault()
        val ids = repo.customProfilesFlow.first().map { it.id }
        assertTrue(ContainerProfile.BUILTIN_ID in ids)
        assertTrue("it-ens-a" in ids)
    }
}
