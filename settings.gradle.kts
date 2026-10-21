pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Termux 终端组件（terminal-emulator / terminal-view）只在 JitPack 发布，腾讯镜像没有
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        google()
        mavenCentral()
    }
}
rootProject.name = "app"

include(":app")
include(":terminal-emulator")
include(":terminal-view")
// Baseline Profile 生成器：com.android.test 模块，仅在手动触发的工作流里跑
// （.github/workflows/baseline-profile.yml），日常 CI 不构建它。
include(":baselineprofile")

