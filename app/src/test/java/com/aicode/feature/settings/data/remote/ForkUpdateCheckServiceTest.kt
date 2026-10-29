package com.aicode.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查更新的纯逻辑：判新只有 [shouldReportUpdate] 一条规则（以服务端结论为准，
 * 客户端不再自己算），以及弹窗文案怎么截断。
 */
class ForkUpdateCheckServiceTest {

    private companion object {
        const val HINT = "（已截断）"
    }

    // ---- shouldReportUpdate ----

    /** 该通道还没发布版（或发布版指向的文件已删）→ 静默。 */
    @Test
    fun notReleased_staysSilent() {
        assertFalse(shouldReportUpdate(released = false, hasUpdate = false, direction = "same"))
        assertFalse(shouldReportUpdate(released = false, hasUpdate = true, direction = "up"))
    }

    /** 发布版在、服务端判了有更新、方向正常 → 提示。 */
    @Test
    fun releasedAndUp_reportsUpdate() {
        assertTrue(shouldReportUpdate(released = true, hasUpdate = true, direction = "up"))
    }

    /** 方向是降级（站上那份比本机旧，用户装的是测试包）→ 静默，不能提示用户降级。 */
    @Test
    fun directionDown_staysSilent() {
        assertFalse(shouldReportUpdate(released = true, hasUpdate = true, direction = "down"))
    }

    /** 服务端说没有更新 / 同版本 → 静默。 */
    @Test
    fun sameVersion_staysSilent() {
        assertFalse(shouldReportUpdate(released = true, hasUpdate = false, direction = "same"))
    }

    /** hasUpdate 缺失（老服务端或字段没给）→ 当没有更新，客户端不再自己算。 */
    @Test
    fun missingHasUpdate_treatedAsNoUpdate() {
        assertFalse(shouldReportUpdate(released = true, hasUpdate = false, direction = ""))
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
