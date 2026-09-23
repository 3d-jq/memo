import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.psyche.memo"
    compileSdk = 36

    // 签名只在 keystore.properties 存在时启用：那是本机密钥，CI 和别人克隆后都没有，
    // 此时 release 产出未签名包（装不上，但构建照常），debug 不受影响。
    val keystoreProperties = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "com.psyche.memo"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.9"

        ndk {
            // 只有这两个 ABI 有 proot 二进制，工作区才有意义；顺带把 termux AAR 里
            // 32 位的 libtermux.so 挡在外面（见下面 packaging 的 pickFirsts）。
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 沙箱工作区（core:workspace 的 proot 二进制）：必须把 jniLibs 以「传统方式」
    // 打包 —— Android 10+ 只有 nativeLibraryDir 里的文件可执行，默认
    // extractNativeLibs=false 会把 .so 留在 APK 里、nativeLibraryDir 为空，
    // 于是每个 shell 命令都以 127「proot executable not found」失败（只在真机上看得出来）。
    //
    // pickFirsts：terminal-view AAR 自带一个 libtermux.so，core:workspace 的
    // termux_pty.cpp 也产出一个同名库（JNI 符号绑在 com.termux.terminal.JNI 上，必须
    // 用我们的那份，因为 AAR 里那个是给 Termux 自己的进程模型用的）。两份同名 .so
    // 不 pick 就直接构建失败。照上游 app/build.gradle.kts:104。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/*/libtermux.so"
        }
    }

    buildTypes {
        getByName("debug") {
            // Dev build: offset applicationId so the native port can be
            // installed next to the existing Flutter Memo (same package id,
            // different signature => INSTALL_FAILED_UPDATE_INCOMPATIBLE) and
            // its data stays untouched. Release keeps the canonical id.
            applicationIdSuffix = ".dev"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// Compose 稳定性配置：把含 List 字段的消息/助手等模型显式声明为 stable，
// 让聊天列表的 item 能按引用跳过重组（详见 app/compose_compiler_config.conf）。
composeCompiler {
    stabilityConfigurationFiles.add(
        layout.projectDirectory.file("compose_compiler_config.conf"),
    )
    // 稳定性/可跳过性报告（build/compose_reports，不进仓库）：用来验证
    // 上面的配置确实让聊天相关 composable 变成 skippable。
    reportsDestination = layout.buildDirectory.dir("compose_reports")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:llm"))
    // 沙箱工作区：proot 二进制 + 工作区核心（见 core/workspace/build.gradle.kts）
    implementation(project(":core:workspace"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:assistant"))
    implementation(project(":feature:utility"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // ProcessLifecycleOwner —— ChatBackgroundController 的 app 前后台观察
    //（RikkaHub ChatNotificationManager/ChatService 同款依赖）。
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lucide.icons)
    implementation(libs.coil)
    implementation(libs.zxing.core)
    implementation(libs.quickie.bundled)
    implementation(libs.coilSvg)
    // 手写 SVG 落盘前用它解析校验（本来是 coil-svg 的传递依赖，显式声明见 toml 注释）
    implementation(libs.androidsvg)
    implementation(libs.accompanistDrawablePainter)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation(libs.exp4j)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // 交互式终端页：termux 终端模拟器 + TerminalView（原生 PTY 在 core:workspace）
    implementation(libs.termux.terminal.view)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    lint {
        lintConfig = file("lint.xml")
    }
}

/**
 * 关掉 release 变体的单元测试。
 *
 * 理由：Compose UI 测试靠 `androidx.compose.ui:ui-test-manifest` 在**合并清单**里
 * 声明 `androidx.activity.ComponentActivity`（`createAndroidComposeRule` 要启动它），
 * 而它只能挂在 debug —— 那是测试脚手架，不能进 release 清单。于是 release 变体里
 * 所有 ActivityScenario 用例必然全红（2026-09-17 实测 65 例，`./gradlew test` 一片
 * 红的假警报就是这么来的）。
 *
 * release 单测对本题没有额外价值：本工程没有 BuildConfig / debug 专属分支，两个变体
 * 跑的是同一份代码；真正有意义的门禁是 `:app:testDebugUnitTest`（全绿）。
 */
androidComponents {
    beforeVariants(selector().withBuildType("release")) { builder ->
        builder.enableUnitTest = false
    }
}
