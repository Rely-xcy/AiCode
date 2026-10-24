package com.aicode.feature.agent.domain.container

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * 自定义镜像「已安装」判定：除目录与标记外，还要比对导入源文件的采样指纹。
 *
 * 回归点：只看标记存在时，同一个路径上的镜像被新内容覆盖后仍被当成已装好，容器里跑的是旧 rootfs。
 * 标记格式即契约：`custom:<32 位 hex 采样指纹>`。
 *
 * 采样窗口是头/中/尾各 128KB，所以用例用 1MB 文件让三段互不重叠，分别改头、中、尾各一个字节。
 *
 * 必须跑在 Robolectric 下：`isInstalledFor` 经 `android.net.Uri.parse` 把 `RootfsSource.LocalFile`
 * 解读成 File 分支，而纯 JVM 单测里 android.jar 是桩（见 app/build.gradle.kts 的
 * `unitTests.isReturnDefaultValues`），`Uri.parse` 会返回 null，指纹一路取不到，
 * 所有用例都只能看到「保守视为已安装」那一支——测不到任何内容变化的判定。
 */
@RunWith(AndroidJUnit4::class)
class ContainerInstallerImageSourceTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "installer-image-${System.nanoTime()}")
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val cacheDir = File(root, "cache").apply { mkdirs() }
    private val context = FakeContext(filesDir, cacheDir)
    private val installer = ContainerInstaller(context, ContainerOsDetector(context))

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun 源文件未变时视为已安装() {
        val image = imageFile("base.tar.gz")
        val profile = profileFor(image)
        markInstalled(profile, sampledFileFingerprint(image))

        assertTrue(installer.isInstalledFor(profile))
    }

    @Test
    fun 头部字节变化后视为未安装() {
        assertDetectsMutationAt(offsetOfHead())
    }

    @Test
    fun 中部字节变化后视为未安装() {
        assertDetectsMutationAt(offsetOfMiddle())
    }

    @Test
    fun 尾部字节变化后视为未安装() {
        assertDetectsMutationAt(IMAGE_SIZE - 1)
    }

    /**
     * 同大小、同修改时间但内容不同：元数据看不出来，只有内容采样能发现。
     * 这里刻意不经 `isInstalledFor` 生成标记（不填缓存），否则会命中缓存而看不出差异。
     */
    @Test
    fun 同大小同修改时间但内容不同时视为未安装() {
        val image = imageFile("base.tar.gz")
        val profile = profileFor(image)
        markInstalled(profile, sampledFileFingerprint(image))
        val mtime = image.lastModified()

        mutateByte(image, offsetOfMiddle())
        image.setLastModified(mtime)

        assertEquals("大小与修改时间必须没变，否则测的不是这条路径", mtime, image.lastModified())
        assertFalse("只有内容采样能发现这种变化", installer.isInstalledFor(profile))
    }

    @Test
    fun 旧版纯custom标记视为已安装并升级为采样指纹() {
        val image = imageFile("base.tar.gz")
        val profile = profileFor(image)
        val rootfs = markInstalled(profile, legacyMarker = "custom")

        assertTrue("老用户已装好的容器不能被当成未安装而整体重装", installer.isInstalledFor(profile))

        val marker = File(rootfs, ".installed_custom")
        assertEquals("custom:${sampledFileFingerprint(image)}", marker.readText())
    }

    @Test
    fun 旧版大小加修改时间标记视为已安装并升级() {
        val image = imageFile("base.tar.gz")
        val profile = profileFor(image)
        val rootfs = markInstalled(profile, legacyMarker = "custom:123:456")
        val sentinel = File(rootfs, "usr/bin/keep-me").apply { writeText("installed") }

        assertTrue("上一版的 size:mtime 标记同样不能触发重装", installer.isInstalledFor(profile))

        assertEquals("custom:${sampledFileFingerprint(image)}", File(rootfs, ".installed_custom").readText())
        assertTrue("升级标记不能动 rootfs 内容", sentinel.exists())
    }

    /**
     * 缓存命中：元数据（大小 + 修改时间）没变就不重读文件。
     * 代价是同大小又同修改时间换内容在同一进程里识别不到——重启后缓存为空即能发现。
     */
    @Test
    fun 缓存命中时不再读文件() {
        val image = imageFile("base.tar.gz")
        val profile = profileFor(image)
        markInstalled(profile, sampledFileFingerprint(image))
        val mtime = image.lastModified()
        assertTrue(installer.isInstalledFor(profile))

        mutateByte(image, offsetOfMiddle())
        image.setLastModified(mtime)

        assertTrue("元数据没变时应复用缓存指纹", installer.isInstalledFor(profile))
    }

    @Test
    fun 源文件取不到时保守视为已安装() {
        val missing = File(filesDir, "rootfs_images/missing.tar.gz")
        val profile = profileFor(missing)
        markInstalled(profile, fingerprint = "00112233445566778899aabbccddeeff")

        assertTrue("判断不了源文件是否变化时不能删已有容器重装", installer.isInstalledFor(profile))
    }

    @Test
    fun 远程SSH容器不参与指纹判断() {
        val profile = ContainerProfile(
            id = "custom-1",
            name = "远程",
            rootfsSource = RootfsSource.RemoteSsh("conn-1", "/home/u/workspace"),
            shellPath = null,
            isBuiltin = false
        )

        assertTrue(installer.isInstalledFor(profile))
    }

    /** 采样参数本身的钉子：头/中/尾三段、每段 128KB，文件更短时退化为整段。 */
    @Test
    fun 采样位置覆盖头中尾三段() {
        val segments = sampledSegments(IMAGE_SIZE)

        assertEquals(3, segments.size)
        assertEquals(0L, segments[0].first)
        assertEquals(OFFSET_MIDDLE, segments[1].first)
        assertEquals(IMAGE_SIZE - CHUNK, segments[2].first)
        assertTrue(segments.all { it.second == CHUNK.toInt() })

        val short = sampledSegments(10)
        assertEquals(listOf(0L to 10, 0L to 10, 0L to 10), short)
        assertTrue(sampledSegments(0).isEmpty())
    }

    @Test
    fun 采样指纹取十六字节且不含冒号() {
        val fingerprint = sampledFileFingerprint(imageFile("base.tar.gz"))

        assertEquals(32, fingerprint?.length ?: -1)
        assertTrue(fingerprint!!.all { it in "0123456789abcdef" })
    }

    /** 生成标记前算一次、改动后重算，指纹必须不同；同时确认内部采样函数是内容敏感的。 */
    private fun assertDetectsMutationAt(offset: Long) {
        val image = imageFile("base.tar.gz")
        val profile = profileFor(image)
        val before = sampledFileFingerprint(image)
        markInstalled(profile, before)

        mutateByte(image, offset)

        assertNotEquals("$offset 位置的字节变了，采样指纹必须跟着变", before, sampledFileFingerprint(image))
        assertFalse(installer.isInstalledFor(profile))
    }

    private fun imageFile(name: String): File {
        val dir = File(filesDir, "rootfs_images").apply { mkdirs() }
        return File(dir, name).apply {
            writeBytes(ByteArray(IMAGE_SIZE.toInt()) { (it * 31 + 7).toByte() })
        }
    }

    /** 改一个字节且不改变文件长度，模拟「同大小、内容不同」。 */
    private fun mutateByte(file: File, offset: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            val original = raf.read()
            raf.seek(offset)
            raf.write(original xor 0xFF)
        }
    }

    private fun offsetOfHead(): Long = 0L

    private fun offsetOfMiddle(): Long = OFFSET_MIDDLE + 512

    private fun profileFor(image: File) = ContainerProfile(
        id = "custom-1",
        name = "custom-1",
        rootfsSource = RootfsSource.LocalFile(image.toURI().toString()),
        shellPath = null,
        isBuiltin = false
    )

    /** 造一个已安装的自定义容器：rootfs 目录 + 标记文件；返回 rootfs 目录。 */
    private fun markInstalled(
        profile: ContainerProfile,
        fingerprint: String? = null,
        legacyMarker: String? = null
    ): File {
        val rootfs = File(filesDir, "rootfs_${profile.id}").apply { mkdirs() }
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, ".installed_custom").writeText(
            legacyMarker ?: "custom:$fingerprint"
        )
        return rootfs
    }

    private class FakeContext(private val files: File, private val cache: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
    }

    private companion object {
        /** 1MB：三段 128KB 采样窗口互不重叠，头/中/尾可以分别命中。 */
        const val IMAGE_SIZE = 1024L * 1024L
        const val CHUNK = 128L * 1024L

        /** 与生产实现一致的居中窗口起点：(size - CHUNK) / 2。 */
        const val OFFSET_MIDDLE = (IMAGE_SIZE - CHUNK) / 2
    }
}
