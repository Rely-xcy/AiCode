package com.aicode.feature.agent.domain.vscreen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 虚拟屏生命周期的状态机测试：到期主动销毁、并发建屏互斥、过期屏先回收再新建、参数越界。
 *
 * 全部跑在 JVM 上：设备侧动作走 [VirtualScreenBackend] 假实现，时钟走假时钟，
 * 定时器跑在 `runTest` 的虚拟时间上——既有真定时器语义，又不用真等 30 分钟。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualScreenManagerTest {

    private class FakeSurface(override val displayId: Int) : VirtualScreenSurface {

        var releaseCount = 0
        var valid = true

        override val isValid: Boolean get() = valid

        override fun captureFrame(target: File, maxWaitMs: Long): Result<Unit> {
            target.writeText("frame")
            return Result.success(Unit)
        }

        override fun release() {
            releaseCount++
        }
    }

    private class FakeBackend : VirtualScreenBackend {

        val surfaces = mutableListOf<FakeSurface>()
        var openCalls = 0

        /** 非 null 时建屏会挂在这里，用来把两个 start 交错到同一次「已有屏判断」上。 */
        var gate: CompletableDeferred<Unit>? = null

        private var nextDisplayId = 7

        override suspend fun open(
            width: Int,
            height: Int,
            density: Int,
            packageName: String,
            activity: String?
        ): Result<VirtualScreenSurface> {
            openCalls++
            gate?.await()
            val surface = FakeSurface(nextDisplayId++)
            surfaces += surface
            return Result.success(surface)
        }
    }

    private class FakeClock(var nowMs: Long = 0L) : VscreenClock {
        override fun nowMs(): Long = nowMs
    }

    private val backend = FakeBackend()
    private val clock = FakeClock()
    private val guard = VirtualScreenGuard()

    @Test
    fun `到存活上限时定时器主动销毁并收回授权`() = runTest {
        val manager = VirtualScreenManager(backend, guard, clock, backgroundScope)

        val started = manager.start("s1", "com.example.app", null, 1080, 1920, 320, lifetimeMs = 60_000)
        assertTrue(started.isSuccess)
        guard.authorize("s1")
        // 让定时任务先启动、按当前时钟算好剩余时间，后面的推进才有确定含义。
        runCurrent()

        clock.nowMs = 59_000
        advanceTimeBy(59_000)
        runCurrent()
        assertNotNull("未到上限不该销毁", manager.current)

        clock.nowMs = 60_000
        advanceTimeBy(1_000)
        runCurrent()

        assertNull("到点必须由定时器主动销毁，不能等下次工具调用", manager.current)
        assertEquals(1, backend.surfaces.single().releaseCount)
        assertFalse("屏没了授权也要收回", guard.isAuthorized("s1"))
    }

    @Test
    fun `并发 start 只会建出一块屏`() = runTest {
        backend.gate = CompletableDeferred()
        val manager = VirtualScreenManager(backend, guard, clock, backgroundScope)

        val first = async { manager.start("s1", "com.example.app", null, 1080, 1920, 320) }
        runCurrent()
        assertEquals("第一个已经进到建屏里", 1, backend.openCalls)

        val second = async { manager.start("s2", "com.example.app", null, 1080, 1920, 320) }
        runCurrent()
        assertEquals("第二个必须等在锁上，不能再建一块", 1, backend.openCalls)

        backend.gate?.complete(Unit)
        val results = listOf(first.await(), second.await())

        assertEquals("整场只允许建出一块屏", 1, backend.surfaces.size)
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals("没人销毁，唯一的屏不该被释放", 0, backend.surfaces.single().releaseCount)
        assertTrue(results.first { it.isFailure }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `已过期的屏会先被回收再建新的`() = runTest {
        val manager = VirtualScreenManager(backend, guard, clock, backgroundScope)

        val first = manager.start("s1", "com.example.app", null, 1080, 1920, 320, lifetimeMs = 60_000).getOrThrow()
        runCurrent()

        // 进程被冻结：时钟已越过上限，但定时器没被调度到（虚拟时间没推进）。
        clock.nowMs = 120_000

        val second = manager.start("s2", "com.example.app", null, 1080, 1920, 320, lifetimeMs = 60_000).getOrThrow()

        assertEquals("旧屏必须先释放，不能泄漏", 1, backend.surfaces[0].releaseCount)
        assertEquals(2, backend.surfaces.size)
        assertNotEquals(first.displayId, second.displayId)
        assertEquals(second.displayId, manager.current?.displayId)
        assertFalse("旧会话的授权随旧屏失效", guard.isAuthorized("s1"))
    }

    @Test
    fun `ensureAlive 在定时器之外再挡一次过期屏`() = runTest {
        val manager = VirtualScreenManager(backend, guard, clock, backgroundScope)

        manager.start("s1", "com.example.app", null, 1080, 1920, 320, lifetimeMs = 60_000).getOrThrow()
        runCurrent()

        clock.nowMs = 61_000

        val alive = manager.ensureAlive()

        assertTrue(alive.isFailure)
        assertTrue(
            "错误里要能看出是超时，而不是「没有屏」",
            alive.exceptionOrNull()!!.message!!.contains("存活上限")
        )
        assertNull(manager.current)
        assertEquals(1, backend.surfaces.single().releaseCount)
    }

    @Test
    fun `参数越界时退回可读错误且不建屏`() = runTest {
        val manager = VirtualScreenManager(backend, guard, clock, backgroundScope)

        val tooSmall = manager.start("s1", "com.example.app", null, 10, 1920, 320)
        assertTrue(tooSmall.exceptionOrNull() is IllegalArgumentException)
        assertTrue(tooSmall.exceptionOrNull()!!.message!!.contains("宽度"))

        val tooManyPixels = manager.start("s1", "com.example.app", null, 4096, 4096, 320)
        assertTrue(tooManyPixels.exceptionOrNull() is IllegalArgumentException)
        assertTrue(tooManyPixels.exceptionOrNull()!!.message!!.contains("像素"))

        val badDensity = manager.start("s1", "com.example.app", null, 1080, 1920, 1_000)
        assertTrue(badDensity.exceptionOrNull() is IllegalArgumentException)
        assertTrue(badDensity.exceptionOrNull()!!.message!!.contains("密度"))

        assertEquals("参数被拒时不该碰设备", 0, backend.openCalls)
        assertNull(manager.current)
    }

    @Test
    fun `控件树缓存跟屏一起失效`() = runTest {
        val manager = VirtualScreenManager(backend, guard, clock, backgroundScope)
        val screen = manager.start("s1", "com.example.app", null, 1080, 1920, 320, lifetimeMs = 60_000).getOrThrow()
        runCurrent()

        val snapshot = UiTreeSnapshot(nodeCount = 40, truncated = true, hash = 42L)
        manager.rememberTreeSnapshot(screen, snapshot)
        assertEquals(snapshot, manager.recentTreeSnapshot(screen))

        // 注入成功会改动界面：旧指纹必须作废，不能当下一回的对照。
        manager.forgetTreeSnapshot(screen)
        assertNull(manager.recentTreeSnapshot(screen))

        manager.rememberTreeSnapshot(screen, snapshot)
        manager.stop()
        assertNull("屏没了，屏上读到的树不能再拿来当对照", manager.recentTreeSnapshot(screen))
    }

    @Test
    fun `换屏后迟到的旧屏快照不会盖到新屏上`() = runTest {
        val manager = VirtualScreenManager(backend, guard, clock, backgroundScope)
        val first = manager.start("s1", "com.example.app", null, 1080, 1920, 320, lifetimeMs = 60_000).getOrThrow()
        runCurrent()

        clock.nowMs = 120_000
        val second = manager.start("s2", "com.example.app", null, 1080, 1920, 320, lifetimeMs = 60_000).getOrThrow()

        val snapshot = UiTreeSnapshot(nodeCount = 40, truncated = true, hash = 7L)
        manager.rememberTreeSnapshot(first, snapshot)
        assertNull("旧屏的读树结果晚到时不允许当新屏的对照", manager.recentTreeSnapshot(second))

        manager.rememberTreeSnapshot(second, snapshot)
        assertEquals(snapshot, manager.recentTreeSnapshot(second))
    }

    @Test
    fun `只看到不足对比深度的快照不能当对照`() = runTest {
        val manager = VirtualScreenManager(backend, guard, clock, backgroundScope)
        val screen = manager.start("s1", "com.example.app", null, 1080, 1920, 320, lifetimeMs = 60_000).getOrThrow()
        runCurrent()

        manager.rememberTreeSnapshot(screen, UiTreeSnapshot(nodeCount = 5, truncated = true, hash = 1L))
        assertNull("前缀比对比读取短，比出来必然是「变了」，只能弃用", manager.recentTreeSnapshot(screen))
    }

    @Test
    fun `stop 释放屏、收回授权并取消定时器`() = runTest {
        val manager = VirtualScreenManager(backend, guard, clock, backgroundScope)

        manager.start("s1", "com.example.app", null, 1080, 1920, 320, lifetimeMs = 60_000).getOrThrow()
        guard.authorize("s1")
        runCurrent()

        assertTrue(manager.stop().isSuccess)
        assertEquals(1, backend.surfaces.single().releaseCount)
        assertFalse(guard.isAuthorized("s1"))

        // 定时器被取消后，即便时钟越界也不该再动一块已经不存在的屏。
        clock.nowMs = 120_000
        advanceTimeBy(120_000)
        assertNull(manager.current)
        assertEquals("release 只能发生一次", 1, backend.surfaces.single().releaseCount)
    }
}
