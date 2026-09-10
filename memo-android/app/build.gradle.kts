import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.psyche.memo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.psyche.memo"
        minSdk = 26
        targetSdk = 35
        versionCode = 2073
        versionName = "1.2.5"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
    implementation(libs.accompanistDrawablePainter)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation(libs.exp4j)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
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
