package com.aicode.feature.agent.domain.vscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VirtualScreenBackend] 的 Android 实现：本进程的 `DisplayManager.createVirtualDisplay` 建屏，
 * 屏自带的 [ImageReader] 取帧，root 通道只负责在屏上 `am start`。
 *
 * 两条真机结论决定了这个结构：
 * - shell 侧没有创建虚拟屏的命令（AOSP 的 `cmd display` 没有 `create-virtual-display`），
 *   App 作为屏的 owner 用 API 建屏不需要 root；
 * - `screencap -d` 只认 SurfaceFlinger/HWC 注册的 display，App 自建的 PUBLIC 屏不在其列
 *   （命令成功但写出 0 字节），所以帧只能从自己的 Surface 读。
 */
@Singleton
class AndroidVirtualScreenBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootShell: RootShell
) : VirtualScreenBackend {

    /** 输入法隐藏失败的告警只打一次：每次建屏都刷一条会让日志里的真正原因被淹掉。 */
    @Volatile
    private var imeWarningLogged = false

    /** 无句柄可释放的孤儿屏告警也只打一次，理由同上。 */
    @Volatile
    private var orphanWarningLogged = false

    /**
     * 本进程建出来的屏句柄：displayId -> 句柄。
     *
     * 孤儿对账要能真的释放残留屏，就必须留住建屏时拿到的 [VirtualDisplay]：系统没有
     * 「按 displayId 释放某个屏」的公开或隐藏入口，句柄一丢就只能等进程退出由系统回收。
     * 正常路径上 manager 销毁屏时会走句柄的 release()，这里随之清空；还留在表里的只可能是
     * 上一轮没被收干净的残留。
     */
    private val ownSurfaces = ConcurrentHashMap<Int, VirtualScreenSurface>()

    // 进程启动（本类首次被注入）时先对一次账：上一轮进程被杀时留下的屏要尽早发现，
    // 不能等下一次 vscreen_start。
    init {
        reconcileOrphans()
    }

    override suspend fun open(
        width: Int,
        height: Int,
        density: Int,
        packageName: String,
        activity: String?
    ): Result<VirtualScreenSurface> {
        reconcileOrphans()

        // 取帧用 ImageReader：格式 RGBA_8888 可直接按像素平面读出来压 PNG；
        // maxImages 给 3 是「够用且不浪费」——帧的新鲜度靠截图时的「腾槽 + 等新帧」保证（见 awaitFrame）。
        val reader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("创建取帧器失败：${e.message}", e))
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: run {
                reader.close()
                return Result.failure(IllegalStateException("取不到 DisplayManager，无法创建虚拟屏。"))
            }

        val created = try {
            // 不带 TRUSTED（需系统权限 ADD_TRUSTED_DISPLAY，普通 app 拿不到，会被 CTS 确认的
            // SecurityException 拒）：PUBLIC + OWN_CONTENT_ONLY 即可让第三方 Activity 落屏。
            dm.createVirtualDisplay(
                VscreenDisplayNames.create(System.currentTimeMillis()),
                width,
                height,
                density,
                reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            )
        } catch (e: Exception) {
            reader.close()
            return Result.failure(IllegalStateException("创建虚拟屏失败：${e.message}", e))
        }
        val display = created
            ?: run {
                reader.close()
                return Result.failure(IllegalStateException("创建虚拟屏返回空 display。"))
            }
        val displayId = display.display?.displayId
            ?: run {
                releaseQuietly(display, reader)
                return Result.failure(IllegalStateException("创建虚拟屏后取不到 displayId。"))
            }

        val surface = AndroidSurface(display, reader, displayId) { ownSurfaces.remove(displayId) }
        ownSurfaces[displayId] = surface
        hideImeOnDisplay(displayId)

        val launch = rootShell.run(launchCommand(displayId, packageName, activity), LAUNCH_TIMEOUT_MS)
        if (!launch.ok) {
            // 启动失败就把刚建的屏收拾掉，不留半成品：走的是和正常销毁同一条释放路径。
            surface.release()
            return Result.failure(
                IllegalStateException(
                    "在虚拟屏上启动 $packageName 失败（exit=${launch.exitCode}）。原始输出：${launch.output.trim().take(400)}"
                )
            )
        }
        return Result.success(surface)
    }

    private fun launchCommand(displayId: Int, packageName: String, activity: String?): String =
        if (activity != null) {
            "am start --display $displayId -n ${VscreenShellArgs.component("$packageName/$activity")}"
        } else {
            "am start --display $displayId -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${VscreenShellArgs.component(packageName)}"
        }

    /**
     * 让输入法不要长在虚拟屏上。
     *
     * 不设的话，虚拟屏里的输入框一拿焦点，输入法会弹到**默认屏**（用户的真机前台）且收不回去：
     * 非默认屏的默认策略是「回落到默认屏显示输入法」，而虚拟屏没有系统装饰，输入法窗口归属
     * 按默认屏算。只有 API 31 起才有按 display 的 IME policy（策略值取自 AOSP
     * `WindowManager.DISPLAY_IME_POLICY_LOCAL=0 / FALLBACK_DISPLAY=1 / HIDE=2`，与 scrcpy 的
     * 同名前缀常量一致）；旧版本没有等价能力，直接跳过而不是报错——那是平台限制，不该挡住建屏。
     *
     * 只能走反射：`InputMethodManager#setDisplayImePolicy` 是 @hide API（服务端对应
     * `IWindowManager.aidl#setDisplayImePolicy(int, int)`），普通应用编译期拿不到。
     * 反射失败（ROM 没这个方法、隐藏 API 拦截、或权限不足抛 SecurityException）只记一次日志，
     * 建屏照常继续——输入法跑偏是体验问题，不该升级成建屏失败。
     */
    private fun hideImeOnDisplay(displayId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            warnImeOnce("设备 API ${Build.VERSION.SDK_INT} 低于 31，没有按 display 的 IME policy")
            return
        }
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return warnImeOnce("取不到 InputMethodManager")
        val method = imm.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "setDisplayImePolicy" &&
                candidate.parameterTypes.size == 2 &&
                candidate.parameterTypes.all { it == Int::class.javaPrimitiveType }
        } ?: return warnImeOnce("当前 ROM 没有 setDisplayImePolicy(int, int)")
        try {
            method.invoke(imm, displayId, IME_POLICY_HIDE)
            FileLogger.i(TAG, "已在 displayId=$displayId 上请求隐藏输入法")
        } catch (e: Throwable) {
            // 反射可能抛 InvocationTargetException（真正原因在 target 里：权限、隐藏 API 拦截），
            // 也可能抛 LinkageError 之类的 Error，所以按 Throwable 兜。
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            warnImeOnce("${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    private fun warnImeOnce(reason: String) {
        if (imeWarningLogged) return
        imeWarningLogged = true
        FileLogger.w(TAG, "未能按 display 隐藏输入法（$reason）：虚拟屏上的输入框拿焦点时，输入法可能弹到真机前台。")
    }

    /**
     * 建屏前对账：遍历系统当前所有 display，按[自己的名字前缀][VscreenDisplayNames.PREFIX]
     * 认出本应用建的屏，能拿到句柄的就释放，拿不到的死心记日志。
     *
     * 只认前缀、不按尺寸或位置猜：别的应用（投屏、录屏、Presentation）建的屏也在同一份列表里，
     * 猜错就是替别人释放屏。
     *
     * 释放走句柄而不是 displayId：系统没有按 id 释放的实现，句柄是唯一能调用的入口。跨进程
     * 残留（例如另一个应用进程建的屏）本进程无论如何都释放不了，只能记下来等系统回收。
     *
     * 调用点有两处：构造时（进程启动）与 [open] 开头（每次建屏前）。前者覆盖上一轮进程留下的残留，
     * 后者覆盖本次会话中途的泄漏；两处都在建屏串行区内，所以表里的句柄一定是残留，不会误伤
     * 正在使用的屏。
     */
    private fun reconcileOrphans() {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val visible = dm.displays
        val seen = HashSet<Int>(visible.size)
        val unreleasable = ArrayList<String>()
        for (display in visible) {
            if (!VscreenDisplayNames.isOwned(display.name)) continue
            seen.add(display.displayId)
            val handle = ownSurfaces.remove(display.displayId)
            if (handle == null) {
                unreleasable.add("displayId=${display.displayId} name=${display.name}")
                continue
            }
            handle.release()
            FileLogger.i(TAG, "已释放孤儿虚拟屏：displayId=${display.displayId} name=${display.name}")
        }
        // 系统侧已经消失、本进程却还留着句柄的，一并收掉。
        for (staleId in ownSurfaces.keys.toList()) {
            if (staleId in seen) continue
            ownSurfaces.remove(staleId)?.release()
            FileLogger.i(TAG, "已释放系统侧已消失的虚拟屏句柄：displayId=$staleId")
        }
        if (unreleasable.isNotEmpty() && !orphanWarningLogged) {
            orphanWarningLogged = true
            FileLogger.w(
                TAG,
                "检测到 ${unreleasable.size} 块本应用前缀的残留虚拟屏，但本进程没有对应句柄，" +
                    "无法释放（只能等系统随进程退出回收）：${unreleasable.joinToString()}"
            )
        }
    }

    /** 释放失败只记日志：这条路径常在异常收尾里跑，再抛就把真正的失败原因盖掉了。 */
    private fun releaseQuietly(display: VirtualDisplay, reader: ImageReader) {
        runCatching { display.release() }.onFailure { FileLogger.w(TAG, "释放虚拟屏失败：${it.message}", it) }
        runCatching { reader.close() }.onFailure { FileLogger.w(TAG, "关闭取帧器失败：${it.message}", it) }
    }

    private companion object {
        const val TAG = "AndroidVirtualScreenBackend"

        const val LAUNCH_TIMEOUT_MS = 20_000L
        const val IMAGE_READER_MAX_IMAGES = 3

        /** 隐藏常量 WindowManager.DISPLAY_IME_POLICY_HIDE 的值。 */
        const val IME_POLICY_HIDE = 2
    }
}

/** 一块真实屏的句柄：持有 [VirtualDisplay] 与取帧器，并负责把两者一起收掉。 */
private class AndroidSurface(
    private val display: VirtualDisplay,
    private val reader: ImageReader,
    override val displayId: Int,
    private val onReleased: () -> Unit
) : VirtualScreenSurface {

    private val released = AtomicBoolean(false)

    /** 帧锁：回调线程与截图线程交换「最新帧」的所有权，避免同一张 Image 被关两次。 */
    private val frameLock = Any()

    /** 回调线程保留的最新一帧；谁把它取走，谁负责关。 */
    private var latestFrame: Image? = null

    /** 每产出一帧 +1：截图用它判断拿到的是不是「截图开始之后」产生的帧。 */
    private var frameSeq = 0L

    /** 取帧回调专用线程：ImageReader 的监听器要求有 Looper。 */
    private val frameThread = HandlerThread("vscreen-frames-$displayId").apply { start() }

    init {
        // 持续消费取帧器：缓冲被占满后屏的合成就走不动（画面冻结），所以必须一直有人取。
        // 这里只保留最新一帧、并立刻关掉被替换的那一帧，任一时刻最多占用 1 个缓冲——
        // 既让画面持续流动，也给「无障碍截图不可用」留一条真有新鲜度的回退通道。
        reader.setOnImageAvailableListener({ source ->
            val next = runCatching { source.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            synchronized(frameLock) {
                latestFrame?.close()
                latestFrame = next
                frameSeq++
            }
        }, Handler(frameThread.looper))
    }

    override val isValid: Boolean get() = display.display?.isValid == true

    override fun release() {
        // 先占位再释放：定时器与 stop 可能同时收尾，重复 release 会打出重复日志、也容易在
        // 已释放的 ImageReader 上抛异常。
        if (!released.compareAndSet(false, true)) return
        // 两个资源各自兜住自己的异常：一个失败不能让另一个漏掉。漏取帧器会让后续取帧抛异常，
        // 漏屏会让目标 app 留在幽灵屏上继续跑、持续合成。
        runCatching { display.release() }.onFailure { FileLogger.w(TAG, "释放虚拟屏失败：${it.message}", it) }
        runCatching { reader.close() }.onFailure { FileLogger.w(TAG, "关闭取帧器失败：${it.message}", it) }
        // 回调线程手里那一帧也要关：reader 关闭不会回收已取出的缓冲，留着就是一块十几 MB 的
        // 图形内存。
        synchronized(frameLock) {
            latestFrame?.close()
            latestFrame = null
        }
        runCatching { frameThread.quitSafely() }
            .onFailure { FileLogger.w(TAG, "停止取帧线程失败：${it.message}", it) }
        // 句柄从 backend 的残留登记表里摘掉：留着的条目会在下次对账时被当成孤儿再释放一遍。
        onReleased()
        FileLogger.i(TAG, "虚拟屏资源已释放：displayId=$displayId")
    }

    /**
     * 取一帧落盘。两条通道按顺序尝试：
     * 1. 无障碍按屏截图：系统合成结果，不受本进程帧队列影响，首选；
     * 2. 本屏取帧器里回调线程缓存的最新帧，回退。
     * 两条都失败时把两条原因一起写进错误，不返回空图。
     */
    override fun captureFrame(target: File, maxWaitMs: Long): Result<Unit> {
        if (released.get()) return Result.failure(IllegalStateException("虚拟屏已释放，无法取帧。"))
        val viaAccessibility = VscreenAccessibilityBridge.takeScreenshot(displayId, maxWaitMs)
        viaAccessibility.getOrNull()?.let { bitmap -> return writePng(bitmap, target) }
        val accessibilityReason = viaAccessibility.exceptionOrNull()?.message ?: "未知原因"
        FileLogger.d(TAG, "无障碍截图不可用，改用屏自身取帧：$accessibilityReason")

        val fromReader = captureFromReader(target, maxWaitMs)
        if (fromReader.isSuccess) return Result.success(Unit)
        return Result.failure(
            IllegalStateException(
                "截图失败：无障碍通道不可用（$accessibilityReason）；" +
                    "屏自身取帧失败（${fromReader.exceptionOrNull()?.message}）"
            )
        )
    }

    /**
     * 从回调线程缓存里取帧。
     *
     * 先等「已有帧」：屏刚建好时队列里可能还没有帧，用满调用方给的预算；
     * 有帧之后只为「截图开始之后产生的帧」多等 [FRESH_FRAME_WAIT_MS]——操作引起的重绘
     * 通常在 1–2 个 vsync 内出现，而等满 maxWaitMs 会让静止界面的每次截图白白多等几秒。
     * 静止界面等不到新帧时退回当前最新帧：内容没变，它就是当前画面。
     */
    private fun captureFromReader(target: File, maxWaitMs: Long): Result<Unit> {
        val waitFirstFrameUntil = System.nanoTime() + maxWaitMs * 1_000_000L
        while (synchronized(frameLock) { latestFrame == null }) {
            if (System.nanoTime() >= waitFirstFrameUntil) {
                return Result.failure(IllegalStateException("等待 ${maxWaitMs}ms 仍未取到帧（屏可能没有内容）。"))
            }
            SystemClock.sleep(CAPTURE_RETRY_INTERVAL_MS)
        }

        val startSeq = synchronized(frameLock) { frameSeq }
        val freshUntil = System.nanoTime() + FRESH_FRAME_WAIT_MS.coerceAtMost(maxWaitMs) * 1_000_000L
        while (System.nanoTime() < freshUntil) {
            takeFrame(minSeq = startSeq + 1)?.let { return encodeAndWrite(it, target) }
            SystemClock.sleep(CAPTURE_RETRY_INTERVAL_MS)
        }
        val latest = takeFrame(minSeq = Long.MIN_VALUE)
            ?: return Result.failure(IllegalStateException("截帧期间画面消失（取帧器已被关闭）。"))
        return encodeAndWrite(latest, target)
    }

    /** 取走一帧，所有权交给调用方；只有序号达到 [minSeq] 才取。 */
    private fun takeFrame(minSeq: Long): Image? = synchronized(frameLock) {
        val frame = latestFrame ?: return@synchronized null
        if (frameSeq < minSeq) return@synchronized null
        latestFrame = null
        frame
    }

    private fun encodeAndWrite(frame: Image, target: File): Result<Unit> =
        try {
            encodePng(frame, target)
        } finally {
            frame.close()
        }

    /**
     * 从 CPU 可读的像素平面转 Bitmap 再压 PNG。
     *
     * 这里不用 `Bitmap.wrapHardwareBuffer`：本通道要支持 minSdk 26，而那是 API 29 起才有的；
     * 无障碍截图那条通道本身就是 API 30+，那里才用硬件缓冲转换。
     */
    private fun encodePng(frame: Image, target: File): Result<Unit> {
        val plane = frame.planes.firstOrNull()
            ?: return Result.failure(IllegalStateException("帧没有可读的像素平面。"))
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        // rowStride 带对齐填充：按填充后的宽度建图、拷完再把右侧多余列裁掉，否则画面会斜切。
        val padding = rowStride - pixelStride * frame.width
        val padded = Bitmap.createBitmap(
            frame.width + padding / pixelStride,
            frame.height,
            Bitmap.Config.ARGB_8888
        )
        val bitmap = try {
            val buffer = plane.buffer
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            if (padded.width == frame.width) {
                padded
            } else {
                // rowStride 的对齐填充让宽度对不上，裁掉右侧多出来的列，否则画面会斜切。
                Bitmap.createBitmap(padded, 0, 0, frame.width, frame.height).also { padded.recycle() }
            }
        } catch (e: Exception) {
            padded.recycle()
            return Result.failure(IllegalStateException("取帧失败：${e.message}", e))
        }
        // writePng 负责回收它拿到的那张位图。
        return writePng(bitmap, target)
    }

    private companion object {
        const val TAG = "VirtualScreenSurface"
        const val CAPTURE_RETRY_INTERVAL_MS = 50L

        /** 等「更新的那一帧」的上限：一次操作引起的重绘通常在 1–2 个 vsync 内出现，300ms 足够宽松。 */
        const val FRESH_FRAME_WAIT_MS = 300L
    }
}

/**
 * 把 Bitmap 压成 PNG 落盘。
 *
 * 位图由本函数回收：一帧 1080×1920 的 ARGB_8888 约 8MB，靠 GC 回收会在连续截图时把
 * 堆顶满，闪退比泄漏更难查。
 */
private fun writePng(bitmap: Bitmap, target: File): Result<Unit> = try {
    val ok = FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    if (ok) {
        Result.success(Unit)
    } else {
        Result.failure(IllegalStateException("PNG 编码失败：${target.absolutePath}"))
    }
} catch (e: Exception) {
    Result.failure(IllegalStateException("写入截图失败：${e.message}", e))
} finally {
    bitmap.recycle()
}
