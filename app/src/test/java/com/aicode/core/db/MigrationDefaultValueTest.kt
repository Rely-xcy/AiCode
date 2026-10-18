package com.aicode.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aicode.feature.agent.data.local.database.AgentDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// 判定「迁移里的 ADD COLUMN ... DEFAULT x 与实体 @ColumnInfo(defaultValue) 不一致会不会炸」。
//
// Room 2.7.1 的比对规则（util/TableInfo.kt 的 Column.equalsCommon，对应 b/136019383）：
// 只有当实体侧声明了非空 defaultValue 时，才把库里 PRAGMA table_info 的 dflt_value 拿来比；
// 实体侧没声明默认值的列，库里有没有 DEFAULT 都不参与 schema 校验。因此升级库与全新库的
// DDL 即使不同，也不会报 "Migration didn't properly handle"。
//
// 下面的用例把这个分歧和「不炸」都钉住：
// - 升级链（50→68 与 62→68）的真实 dflt_value；
// - 全新安装（Room 按实体生成的 CREATE TABLE）的真实 dflt_value；
// - 两者不同、但都能被 Room 正常打开（打开即触发 schema 校验）。
//
// 若将来给这些列补 @ColumnInfo(defaultValue)，升级库侧会先红——它们的物理列上并没有该默认值。
//
// 环境约束与 [MigrationTest] 相同：本容器 JVM 加载不了 Robolectric 的 native 库，用例跳过，
// CI（ubuntu x86_64）真实执行；是否通过只能由 CI 判定。
@RunWith(AndroidJUnit4::class)
class MigrationDefaultValueTest {

    private val upgradedDb = "default-value-upgraded.db"
    private val upgradedFrom62Db = "default-value-upgraded-62.db"
    private val upgradedFrom65Db = "default-value-upgraded-65.db"
    private val freshDb = "default-value-fresh.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            assumeTrue(
                "Robolectric 仅支持标准 Linux/CI 环境（当前为 Android PRoot 容器，会 UnsatisfiedLinkError）",
                !androidContainer
            )
        }
    }

    private fun defaultsOf(db: SupportSQLiteDatabase, table: String): Map<String, String?> {
        val result = mutableMapOf<String, String?>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                result[cursor.getString(nameIndex)] =
                    if (cursor.isNull(defaultIndex)) null else cursor.getString(defaultIndex)
            }
        }
        return result
    }

    // 键是 "表.列"，值是 PRAGMA 的 dflt_value（无默认值为 null，"DEFAULT NULL" 为字面量 "NULL"）。
    private fun observedDefaults(db: SupportSQLiteDatabase): Map<String, String?> {
        val tables = listOf("chat_sessions", "agent_messages", "ai_providers", "remote_connections")
        val result = mutableMapOf<String, String?>()
        for (table in tables) {
            for ((column, defaultValue) in defaultsOf(db, table)) {
                result["$table.$column"] = defaultValue
            }
        }
        return result
    }

    // helper 以历史 schema json 建库（等价于当年全新安装），再用生产同一套迁移开到 68。
    private fun upgradedDefaults(fromVersion: Int, dbName: String): Map<String, String?> {
        helper.createDatabase(dbName, fromVersion).close()
        val database = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        try {
            val db = database.openHelper.writableDatabase
            val observed = observedDefaults(db)
            println("升级库($fromVersion→68) dflt_value: $observed")
            return observed
        } finally {
            database.close()
        }
    }

    // 不加迁移直接打开：Room 按实体（KSP 生成的 createAllTables）建表，等价于全新安装。
    private fun freshDefaults(dbName: String): Map<String, String?> {
        context.deleteDatabase(dbName)
        val database = Room.databaseBuilder(context, AgentDatabase::class.java, dbName).build()
        try {
            val db = database.openHelper.writableDatabase
            val observed = observedDefaults(db)
            println("全新安装(68) dflt_value: $observed")
            return observed
        } finally {
            database.close()
        }
    }

    @Test
    fun upgraded_from_50_keeps_defaults_written_by_migrations() {
        val observed = upgradedDefaults(50, upgradedDb)

        assertEquals(
            "迁移 68 的 goalTurnCount ... DEFAULT 0 应保留在物理列上",
            "0",
            observed["chat_sessions.goalTurnCount"]
        )
        assertEquals(
            "迁移 65 给 remote_connections 加的 sort_order DEFAULT 0 应保留",
            "0",
            observed["remote_connections.sort_order"]
        )
        assertEquals(
            "ALTER TABLE ... ADD COLUMN x TEXT DEFAULT NULL 的 dflt_value 是字面量 NULL",
            "NULL",
            observed["agent_messages.modelReminder"]
        )
        assertNull(
            "迁移 57 重建 ai_providers 时未写 DEFAULT，53 加的 scriptParams 默认值被抹掉",
            observed["ai_providers.scriptParams"]
        )
    }

    @Test
    fun upgraded_from_62_gets_goal_columns_with_migration_defaults() {
        val observed = upgradedDefaults(62, upgradedFrom62Db)

        assertEquals("0", observed["chat_sessions.goalTurnCount"])
        assertEquals("0", observed["chat_sessions.goalStartInputTokens"])
        assertEquals("0", observed["chat_sessions.goalStartOutputTokens"])
        assertEquals("0", observed["remote_connections.sort_order"])
        assertNull(
            "62 的建表 DDL 里已有 modelReminder 且无默认值；59 号的迁移不会重放",
            observed["agent_messages.modelReminder"]
        )
    }

    @Test
    fun upgraded_from_65_has_no_default_on_sort_order() {
        val observed = upgradedDefaults(65, upgradedFrom65Db)

        assertNull(
            "65 的建表 DDL 已含 sort_order 且未写默认值，65 号的迁移不会重放",
            observed["remote_connections.sort_order"]
        )
        assertEquals(
            "68 号迁移新加的列仍带 DEFAULT 0",
            "0",
            observed["chat_sessions.goalTurnCount"]
        )
    }

    @Test
    fun fresh_install_has_defaults_only_where_entities_declare_them() {
        val observed = freshDefaults(freshDb)

        assertEquals(
            "实体上写了 @ColumnInfo(defaultValue = \"0\")，全新安装的建表也带 DEFAULT 0",
            "0",
            observed["chat_sessions.goalTurnCount"]
        )
        assertEquals("0", observed["agent_messages.foldsUpToTimestamp"])
        assertNull(
            "实体没有声明默认值，全新安装的列就没有 DEFAULT",
            observed["remote_connections.sort_order"]
        )
        assertNull(observed["agent_messages.modelReminder"])
        assertNull(observed["ai_providers.scriptParams"])
    }

    @Test
    fun divergent_default_values_are_tolerated_by_schema_validation() {
        val upgraded = upgradedDefaults(50, upgradedDb)
        val fresh = freshDefaults(freshDb)

        val upgradedSortOrder = upgraded["remote_connections.sort_order"]
        val freshSortOrder = fresh["remote_connections.sort_order"]
        println(
            "同一 schema 68 的两种物理 DDL —— 升级库: sort_order=" + upgradedSortOrder +
                ", modelReminder=" + upgraded["agent_messages.modelReminder"] +
                "; 全新库: sort_order=" + freshSortOrder +
                ", modelReminder=" + fresh["agent_messages.modelReminder"]
        )

        assertEquals("0", upgradedSortOrder)
        assertNull(freshSortOrder)
        assertNotEquals(
            "升级库与全新库的 dflt_value 不同（这正是所谓「同一 schema 号两种 DDL」）",
            upgradedSortOrder,
            freshSortOrder
        )
        assertEquals(
            "实体声明了 defaultValue 的列，两条路径必须一致",
            upgraded["chat_sessions.goalTurnCount"],
            fresh["chat_sessions.goalTurnCount"]
        )
        // 两个 databaseBuilder.openHelper.writableDatabase 都没抛异常，
        // 即 Room 对 50→68 的迁移与全新 68 建表都通过了 onValidateSchema。
    }
}
