package com.aicode.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * 采集冷启 baseline profile。
 *
 * 产物是一个 baseline-prof.txt（见 workflow 上传的 artifact），需要人工提交到
 * `app/src/main/baseline-prof.txt`，之后所有变体（含 beta 包）都会自动带进 APK。
 *
 * 只覆盖冷启动：首屏是工作台，后续页面（设置、文件树、终端）留待按需补充，
 * 因为多界面采集依赖控件结构，UI 一改就断，收益递减。
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun coldStartup() {
        rule.collect(
            // benchmark 变体没有 applicationIdSuffix，即正式包名
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true
        ) {
            pressHome()
            startActivityAndWait()
            // 首次启动要解压容器 rootfs，首帧可能远晚于 Activity 启动，故单独等窗口出现
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), FIRST_FRAME_TIMEOUT_MS)
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "Rely.aicode"
        const val FIRST_FRAME_TIMEOUT_MS = 60_000L
    }
}
