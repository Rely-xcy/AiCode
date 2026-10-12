package com.aicode.feature.agent.domain.container

import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 镜像目录条目的展示名、EOL 判定与 JSON 契约。
 *
 * 回归点：Debian 的 rootfs 走 Jenkins `lastSuccessfulBuild`，内容随上游滚动，而目录里只写了主版本
 * （`12`），界面上完全看不出实际是 12.15 还是 12.16——用户无法判断要不要重下。
 * 现在由 [ContainerImageEntry.versionPath] 运行时探测补上补丁号，这里锁住拼接与回退规则。
 */
class ContainerImageEntryLogicTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun entry(
        name: String = "Debian",
        version: String = "12",
        versionSuffix: String = "",
        versionPath: String = "",
        eol: String = ""
    ) = ContainerImageEntry(
        id = "debian-12",
        name = name,
        version = version,
        versionSuffix = versionSuffix,
        versionPath = versionPath,
        eol = eol,
        path = "some/rootfs.tar.xz"
    )

    @Test
    fun displayLabel_未探测到版本_回退内置版本与后缀() {
        val e = entry(version = "12", versionSuffix = "(bookworm)")

        assertEquals("Debian 12 (bookworm)", e.displayLabel())
        assertEquals("Debian 12 (bookworm)", e.displayLabel(null))
        assertEquals("Debian 12 (bookworm)", e.displayLabel(""))
    }

    @Test
    fun displayLabel_探测到补丁号_用精确版本替换内置版本() {
        val e = entry(version = "12", versionSuffix = "(bookworm)")

        assertEquals("Debian 12.15 (bookworm)", e.displayLabel("12.15"))
    }

    @Test
    fun displayLabel_无后缀条目_不产生多余空格() {
        val e = entry(name = "Alpine Linux", version = "3.21.8")

        assertEquals("Alpine Linux 3.21.8", e.displayLabel())
    }

    @Test
    fun eolStatus_未配置日期_不标注() {
        assertEquals(ContainerImageEol.NONE, entry(eol = "").eolStatus(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun eolStatus_日期非法_不标注() {
        assertEquals(ContainerImageEol.NONE, entry(eol = "not-a-date").eolStatus(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun eolStatus_距今不足预警窗口_标记即将停止支持() {
        val today = LocalDate.of(2026, 10, 12)
        // Alpine 3.21 的 EOL 是 2026-11-01，距 2026-10-12 还有 20 天
        assertEquals(ContainerImageEol.SOON, entry(eol = "2026-11-01").eolStatus(today))
    }

    @Test
    fun eolStatus_预警窗口边界_刚好等于窗口天数仍不标注() {
        val today = LocalDate.of(2026, 10, 12)
        // 相差正好 EOL_SOON_DAYS 天时不算「即将」；少一天才进入预警
        val boundary = today.plusDays(EOL_SOON_DAYS).toString()
        assertEquals(ContainerImageEol.NONE, entry(eol = boundary).eolStatus(today))
        assertEquals(
            ContainerImageEol.SOON,
            entry(eol = today.plusDays(EOL_SOON_DAYS - 1).toString()).eolStatus(today)
        )
    }

    @Test
    fun eolStatus_已过日期_标记停止支持() {
        assertEquals(
            ContainerImageEol.ENDED,
            entry(eol = "2026-11-01").eolStatus(LocalDate.of(2026, 11, 2))
        )
    }

    @Test
    fun eolStatus_当天到期_仍算即将而非已停止() {
        assertEquals(
            ContainerImageEol.SOON,
            entry(eol = "2026-11-01").eolStatus(LocalDate.of(2026, 11, 1))
        )
    }

    @Test
    fun json_解析新增字段() {
        val parsed = json.decodeFromString(
            ContainerImageEntry.serializer(),
            """
            {
              "id": "debian-12",
              "name": "Debian",
              "version": "12",
              "versionSuffix": "(bookworm)",
              "versionPath": "job/{abi}/artifact/bookworm/rootfs.debian_version",
              "eol": "2026-11-01",
              "path": "job/{abi}/artifact/bookworm/rootfs.tar.xz",
              "abiNames": { "arm64": "arm64v8", "x86_64": "amd64" }
            }
            """.trimIndent()
        )

        assertEquals("(bookworm)", parsed.versionSuffix)
        assertEquals("job/{abi}/artifact/bookworm/rootfs.debian_version", parsed.versionPath)
        assertEquals("2026-11-01", parsed.eol)
    }

    @Test
    fun json_旧目录缺新字段_取默认值不抛异常() {
        val parsed = json.decodeFromString(
            ContainerImageEntry.serializer(),
            """
            {
              "id": "alpine-3.21",
              "name": "Alpine Linux",
              "version": "3.21.7",
              "path": "alpine/v3.21/releases/{abi}/alpine-minirootfs-3.21.7-{abi}.tar.gz"
            }
            """.trimIndent()
        )

        assertEquals("", parsed.versionSuffix)
        assertEquals("", parsed.versionPath)
        assertEquals("", parsed.eol)
        assertEquals("Alpine Linux 3.21.7", parsed.displayLabel())
    }
}
