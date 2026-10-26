package com.aicode.feature.agent.domain.tool.container

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolRoutingHintTest {

    @Test
    fun `cat 提示改用 readFile`() {
        val hint = ShellToolRoutingHint.forCommand("cat app/build.gradle.kts")
        assertTrue(hint!!.contains("readFile"))
    }

    @Test
    fun `grep 与 rg 提示改用 search`() {
        assertTrue(ShellToolRoutingHint.forCommand("grep -rn 'fun main' app")!!.contains("search"))
        assertTrue(ShellToolRoutingHint.forCommand("rg -n TODO app")!!.contains("search"))
    }

    @Test
    fun `ls 提示改用 list`() {
        assertTrue(ShellToolRoutingHint.forCommand("ls -la")!!.contains("list"))
    }

    @Test
    fun `带管道或重定向不提示`() {
        assertNull(ShellToolRoutingHint.forCommand("cat a.txt | head -20"))
        assertNull(ShellToolRoutingHint.forCommand("ls > out.txt"))
        assertNull(ShellToolRoutingHint.forCommand("grep -rn x . && echo ok"))
    }

    @Test
    fun `无关命令不提示`() {
        assertNull(ShellToolRoutingHint.forCommand("./gradlew :app:assembleUniversalDebug"))
        assertNull(ShellToolRoutingHint.forCommand("sed 's/a/b/' f.txt"))
    }

    @Test
    fun `appendTo 把提示拼在输出之后`() {
        val merged = ShellToolRoutingHint.appendTo("cat a.txt", "hello")
        assertTrue(merged.startsWith("hello"))
        assertTrue(merged.contains("readFile"))
        // 不需要提示时原样返回
        assertTrue(ShellToolRoutingHint.appendTo("echo hi", "hi") == "hi")
    }
}
