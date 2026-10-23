plugins {
    id("com.android.test")
    kotlin("android")
}

// Baseline Profile 生成器：仅在被 .github/workflows/baseline-profile.yml 手动触发时构建并运行，
// 日常 CI（ci.yml）不引用本模块，不会拖慢构建。
//
// 为什么不把这段测试放进 app 的 androidTest：macrobenchmark 必须从独立进程启动目标 app 并反复
// 冷启，与目标 app 同进程的 instrumentation 做不到这一点。
android {
    namespace = "com.aicode.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        // 与被测 app 保持一致：targetSdk 28（见 app/build.gradle.kts 的说明，勿升）
        targetSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 被测模块与变体：benchmark 变体是 release 型（非 debuggable + 同 R8 配置），
    // 只有它能被 macrobenchmark 接住；universal 是含两套容器镜像的通用 flavor。
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    // app 有 container 维度的三个 flavor，生成器必须声明同一维度与同名 flavor，否则 Gradle
    // 无法在 universalBenchmark / armsoloBenchmark / x86soloBenchmark 之间选择，报
    // "cannot choose between the following variants"。
    flavorDimensions += "container"
    productFlavors {
        create("universal") { dimension = "container" }
        create("armsolo") { dimension = "container" }
        create("x86solo") { dimension = "container" }
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            // 解析 :app 的传递依赖时 fallback 取自发起解析的变体（就是本模块的该 buildType）：
            // :terminal-emulator 等库模块没有这些变体，缺这一行会报
            // "No matching variant of project :terminal-emulator"。
            matchingFallbacks += "release"
            // 自定义 buildType 不会自动继承 debug 签名，不签会 INSTALL_PARSE_FAILED_NO_CERTIFICATES
            signingConfig = signingConfigs.getByName("debug")
        }
        // 采集用变体：对应 :app 的 profilegen（非 debuggable、未混淆），与 benchmark 同配置。
        create("profilegen") {
            isDebuggable = false
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // 与被测 app 一致：Kotlin/Java 目标版本不一致会直接报错
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

androidComponents {
    // 只保留两个有用途的变体：profilegen（采集）与 benchmark（验证发布型构建包含 profile），
    // 避免多构无用的 debug 测试 APK。
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark" || variant.buildType == "profilegen"
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
