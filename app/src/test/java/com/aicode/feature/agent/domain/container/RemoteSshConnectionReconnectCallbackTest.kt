package com.aicode.feature.agent.domain.container

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [invokeReconnectedCallbackSafely]：重连成功后的回调（工作区重载、文档同步）抛异常时的处理。
 *
 * 钉两件事：异常**不外抛**（调用方用返回值与随后的状态判断表达的是「连接是否建立」，回调失败不该
 * 被算成连接失败），以及异常**必须留下痕迹**——旧实现 `runCatching { onReconnected?.invoke() }`
 * 没有 onFailure，回调抛出的异常在日志里一点痕迹都不留，「已连接但工作区列表为空」因此无法归因。
 *
 * 打的是生产同一个函数：默认参数写 App 日志，测试只把记录方式换成收集器。
 */
class RemoteSshConnectionReconnectCallbackTest {

    @Test
    fun `回调抛异常时记一条带类型与消息的日志且不外抛`() = runBlocking {
        val logged = mutableListOf<Pair<String, Throwable>>()
        val boom = IllegalStateException("工作区重载失败：远程根目录不可读")

        invokeReconnectedCallbackSafely(
            callback = { throw boom },
            logFailure = { message, error -> logged += message to error }
        )

        // 旧实现下恒为 0：没有 onFailure，异常直接被 runCatching 丢掉，该断言必红
        assertEquals("恰好记一条失败日志", 1, logged.size)
        val (message, error) = logged.single()
        assertTrue("日志要带异常类型，实际：$message", "IllegalStateException" in message)
        assertTrue("日志要带异常消息，实际：$message", "工作区重载失败：远程根目录不可读" in message)
        assertSame("异常对象原样透传给记录方", boom, error)
    }

    @Test
    fun `回调挂起后抛出的异常同样被记录`() = runBlocking {
        val logged = mutableListOf<String>()

        invokeReconnectedCallbackSafely(
            callback = {
                delay(1)
                throw RuntimeException("同步远程文档失败")
            },
            logFailure = { message, _ -> logged += message }
        )

        // runCatching 必须包住整次 suspend 调用，而不只是同步段；旧实现下同样恒为 0
        assertEquals(1, logged.size)
        assertTrue("挂起后抛出的异常也要带类型", "RuntimeException" in logged.single())
    }

    @Test
    fun `回调正常返回时不记日志`() = runBlocking {
        var invoked = false
        val logged = mutableListOf<String>()

        invokeReconnectedCallbackSafely(
            callback = { invoked = true },
            logFailure = { message, _ -> logged += message }
        )

        assertTrue("回调必须被调用", invoked)
        assertEquals("成功路径不该记失败日志", 0, logged.size)
    }

    @Test
    fun `回调未注册时静默跳过`() = runBlocking {
        val logged = mutableListOf<String>()

        invokeReconnectedCallbackSafely(
            callback = null,
            logFailure = { message, _ -> logged += message }
        )

        assertEquals("supervisor 未启动属正常启动时序，不该记失败", 0, logged.size)
    }
}
