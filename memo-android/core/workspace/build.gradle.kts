plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * RikkaHub 的沙箱工作区（proot rootfs）—— 见 PORTING.md §5.11 / §4。
 *
 * 本模块的「原生部分」只有两样东西：
 *  1. `src/main/jniLibs/<abi>/libproot_{exec,loader}.so` —— 预编译 PRoot 二进制
 *     （GPL-2.0-or-later，随 AGPL-3.0 工程分发需附源码获取声明；来源
 *     RikkaHub `workspace/src/main/jniLibs/`，arm64-v8a + x86_64 两个 ABI）。
 *     它们必须经 `jniLibs` 打进 APK 并由 `useLegacyPackaging = true`（app 模块）
 *     解压到 `nativeLibraryDir` —— 那是应用唯一可执行自有文件的目录
 *     （Android 10+ 禁止 exec `/data/data/...`）。少了那个开关，
 *     `File(nativeLibraryDir, "libproot_exec.so").isFile` 为假，所有 shell 命令
 *     都会以 127「proot executable not found」失败，且**只在真机上才看得出来**。
 *  2. `src/main/cpp/termux_pty.cpp` —— 交互式终端页的 PTY 后端（JNI 符号名写死为
 *     `Java_com_termux_terminal_JNI_*`，绑定 `com.termux.termux-app:terminal-view`）。
 *     走 `externalNativeBuild`（需要 NDK + cmake 3.22.1），**只有终端页用得到**；
 *     文件工具/`workspace_shell` 走 `ProcessBuilder`，不需要它。
 *
 * 现阶段（spike）只放 (1)：先验证这台 ROM 允许从 nativeLibraryDir 执行 PRoot。
 */
android {
    namespace = "com.psyche.memo.workspace"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk {
            // 只有这两个 ABI 有 proot 二进制；armeabi-v7a 设备永远用不了工作区，
            // 与其打包一个空的 32 位目录不如直接不产。
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}
