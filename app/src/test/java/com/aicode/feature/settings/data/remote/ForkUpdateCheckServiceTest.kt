package com.aicode.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的纯逻辑：怎么从本机版本与站上包名里各取一个构建短哈希、拿不到时怎么回退认 id、
 * 弹窗文案怎么截断。
 *
 * 这几个用例钉的是同一个症状——「装了旧包却不提示更新」：判新必须以「本机装的是哪一份包」为准，
 * 而不是以「客户端上次见过站上哪个 id」为准。旧实现把两者混为一谈，所以下面标了「旧实现必红」
 * 的用例在旧规则下会红。
 */
class ForkUpdateCheckServiceTest {

    private companion object {
        /** 站点当前发布版包名里的 sha8（2026-10-04 实测 /dl/update 返回）。 */
        const val SITE_SHA = "b6c45eea"

        /** 站点当前发布版 id（同上实测）。 */
        const val RELEASE_ID = "z785yn6unaah"

        /** 另一个发布版的 id，用来模拟「站上换了新包」。 */
        const val OTHER_RELEASE_ID = "a1b2c3d4e5f6"

        const val HINT = "（已截断）"
    }

    // ---- buildShaFromVersionName ----

    @Test
    fun versionName_withReachableTag_extractsSha() {
        assertEquals(SITE_SHA, buildShaFromVersionName("1.12.0-rc2-dev.289+gb6c45eea"))
    }

    @Test
    fun versionName_withoutAnyTag_extractsSha() {
        // 仓库里一个 tag 都没有时 gitVersionName() 走 "1.7.0-dev+<短哈希>"，`+` 后没有 `g`
        assertEquals(SITE_SHA, buildShaFromVersionName("1.7.0-dev+b6c45eea"))
    }

    @Test
    fun versionName_dirtyWorkingTree_stillExtractsSha() {
        assertEquals(SITE_SHA, buildShaFromVersionName("1.12.0-rc2-dev.289+gb6c45eea-dirty"))
    }

    @Test
    fun versionName_exactTagBuild_hasNoSha() {
        assertNull(buildShaFromVersionName("1.12.0-rc2"))
        assertNull(buildShaFromVersionName("1.12.0"))
    }

    @Test
    fun versionName_missingOrUnreadable_hasNoSha() {
        assertNull(buildShaFromVersionName(null))
        assertNull(buildShaFromVersionName(""))
        assertNull(buildShaFromVersionName("unknown"))
        assertNull(buildShaFromVersionName("1.7.0-dev"))
    }

    // ---- buildShaFromApkName ----

    @Test
    fun apkName_siteBeta_extractsSha() {
        assertEquals(SITE_SHA, buildShaFromApkName("aicode-beta-b6c45eea.apk"))
    }

    @Test
    fun apkName_withFlavorSegment_extractsSha() {
        assertEquals(SITE_SHA, buildShaFromApkName("aicode-universal-beta-b6c45eea.apk"))
    }

    @Test
    fun apkName_withoutDotApkSuffix_extractsSha() {
        assertEquals(SITE_SHA, buildShaFromApkName("aicode-beta-b6c45eea"))
    }

    @Test
    fun apkName_withoutSha_isNull() {
        assertNull(buildShaFromApkName("aicode-1.12.0.apk"))
        assertNull(buildShaFromApkName("aicode-beta.apk"))
        assertNull(buildShaFromApkName("aicode-beta-abc123.apk"))
        assertNull(buildShaFromApkName(null))
        assertNull(buildShaFromApkName(""))
    }

    /** 大写不认（git 短哈希恒小写）：认不出就走 id 回退，不拿半个哈希硬比。 */
    @Test
    fun apkName_uppercaseSha_isNullAndFallsBack() {
        assertNull(buildShaFromApkName("aicode-beta-B6C45EEA.apk"))
    }

    // ---- isSameBuildSha ----

    @Test
    fun sameSha_sameLength() {
        assertTrue(isSameBuildSha(SITE_SHA, SITE_SHA))
    }

    @Test
    fun sameSha_differentLengths_comparesPrefix() {
        assertTrue(isSameBuildSha("b6c45ee", SITE_SHA))
        assertTrue(isSameBuildSha(SITE_SHA, "b6c45ee"))
        assertTrue(isSameBuildSha(SITE_SHA, "b6c45eea1f0d3c9b8e7a6f5d4c3b2a1908f7e6d5"))
    }

    @Test
    fun differentSha_isNotSame() {
        assertFalse(isSameBuildSha(SITE_SHA, "0f1e2d3c"))
    }

    /** 短到不足以唯一标识一个提交（不足 7 位）的一律判为不同：宁可多提示一次，也不冒静默漏更新的风险。 */
    @Test
    fun tooShortSha_isNeverSame() {
        assertFalse(isSameBuildSha("b6c45e", "b6c45e"))
        assertFalse(isSameBuildSha("", ""))
    }

    // ---- hasNewVersion ----

    /**
     * 装的是旧包 + 站上是另一份 → 必须提示。
     * **旧实现必红**：旧规则只看「本地有没有存过 id」，第一次检查时本地没 id，直接静默存下站上 id。
     */
    @Test
    fun olderLocalBuild_firstEverCheck_reportsUpdate() {
        assertTrue(
            hasNewVersion(
                localSha = SITE_SHA,
                remoteSha = "0f1e2d3c",
                lastSeenReleaseId = null,
                releaseId = RELEASE_ID
            )
        )
    }

    /** 本机就是站上那份 → 不提示（装了最新版不该弹窗，护栏）。 */
    @Test
    fun sameLocalAndRemoteBuild_reportsNothing() {
        assertFalse(hasNewVersion(SITE_SHA, SITE_SHA, null, RELEASE_ID))
        assertFalse(hasNewVersion(SITE_SHA, SITE_SHA, RELEASE_ID, RELEASE_ID))
    }

    /**
     * 划掉弹窗没装的用户，下次再检查（手动，或隔天的自动检查）仍要提示。
     * **旧实现必红**：旧规则此时带着 `?id=<最新>` 请求，站点回 hasUpdate=false，永久静默。
     */
    @Test
    fun dismissedDialog_nextCheck_stillReportsUpdate() {
        assertTrue(
            hasNewVersion(
                localSha = SITE_SHA,
                remoteSha = "0f1e2d3c",
                lastSeenReleaseId = RELEASE_ID,
                releaseId = RELEASE_ID
            )
        )
    }

    /**
     * 站上换了新发布版（本机仍是旧包）→ 提示。
     * **旧实现必红**：旧规则在「首次检查」那一次就把 id 记成了新 id，此后 hasUpdate 恒 false。
     */
    @Test
    fun newerReleaseOnSite_reportsUpdate() {
        assertTrue(hasNewVersion(SITE_SHA, "0f1e2d3c", OTHER_RELEASE_ID, RELEASE_ID))
    }

    // ---- hasNewVersion：字段缺失时的回退 ----

    /** 两边都取不到哈希（站点包名没哈希 / 本机是正式 tag 包）→ 退回 id：首次也提示，不静默。 */
    @Test
    fun fallback_withoutAnySha_firstCheck_reportsUpdate() {
        assertTrue(hasNewVersion(null, null, null, RELEASE_ID))
    }

    /** 回退路径：id 与上次相同 → 不重复提示（护栏：别每次冷启动都弹）。 */
    @Test
    fun fallback_sameId_reportsNothing() {
        assertFalse(hasNewVersion(null, null, RELEASE_ID, RELEASE_ID))
    }

    /** 回退路径：站上换了发布版（id 变了）→ 提示（护栏）。 */
    @Test
    fun fallback_changedId_reportsUpdate() {
        assertTrue(hasNewVersion(null, null, OTHER_RELEASE_ID, RELEASE_ID))
    }

    /** 只有一边有哈希时不许硬比，退回 id 认账（站点换命名 / 本机是 tag 包都会走到这里）。 */
    @Test
    fun oneSideHasSha_fallsBackToId() {
        assertFalse(hasNewVersion(SITE_SHA, null, RELEASE_ID, RELEASE_ID))
        assertFalse(hasNewVersion(null, "0f1e2d3c", RELEASE_ID, RELEASE_ID))
    }

    /** 站点连 id 都没给（released 但字段缺失）→ 无从判断，不提示：逐日弹假更新比偶尔漏提示更烦人。 */
    @Test
    fun nothingComparable_reportsNothing() {
        assertFalse(hasNewVersion(null, null, null, ""))
        assertFalse(hasNewVersion(null, null, RELEASE_ID, ""))
    }

    /** 哈希短到不足以认账时不算「相同」：宁可提示一次，也不静默（护栏）。 */
    @Test
    fun tooShortShaBothSides_reportsUpdate() {
        assertTrue(hasNewVersion("b6c", "b6c", RELEASE_ID, RELEASE_ID))
    }

    // ---- releaseLabel ----

    @Test
    fun label_usesShaAndDate() {
        assertEquals(
            "b6c45eea · 2026-10-04 19:49",
            releaseLabel(SITE_SHA, "aicode-beta-b6c45eea.apk", "2026-10-04 19:49")
        )
    }

    @Test
    fun label_withoutSha_stripsApkSuffix() {
        assertEquals(
            "aicode-1.12.0 · 2026-10-04 19:49",
            releaseLabel(null, "aicode-1.12.0.apk", "2026-10-04 19:49")
        )
    }

    @Test
    fun label_withoutDate_keepsShaOnly() {
        assertEquals(SITE_SHA, releaseLabel(SITE_SHA, "aicode-beta-b6c45eea.apk", ""))
    }

    @Test
    fun label_nothingAtAll_isBlank() {
        assertEquals("", releaseLabel(null, "", ""))
    }

    // ---- previewChangelog ----

    @Test
    fun shortChangelog_isUntouched() {
        val note = "第一行\n第二行"
        assertEquals(note, previewChangelog(note, 16, HINT))
    }

    @Test
    fun longChangelog_keepsFirstLinesAndAppendsHint() {
        val note = (1..30).joinToString("\n") { "line$it" }
        val out = previewChangelog(note, 16, HINT)
        assertEquals((1..16).joinToString("\n") { "line$it" } + "\n" + HINT, out)
        assertFalse(out.contains("line17"))
    }

    @Test
    fun exactLineCount_isNotTruncated() {
        val note = (1..16).joinToString("\n") { "line$it" }
        assertEquals(note, previewChangelog(note, 16, HINT))
    }

    /** 站点 note 以一个换行收尾：末尾空行不该被算成一行，否则会白白多截一行。 */
    @Test
    fun trailingNewline_doesNotCountAsALine() {
        val note = (1..16).joinToString("\n") { "line$it" } + "\n"
        assertEquals((1..16).joinToString("\n") { "line$it" }, previewChangelog(note, 16, HINT))
    }

    /** 站点 note 里大量 ──── 分隔线照原样保留，不做二次加工。 */
    @Test
    fun separatorLines_arePreserved() {
        val note = "标题\n────────\n正文"
        assertEquals(note, previewChangelog(note, 16, HINT))
    }
}
