plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * 上游的沙箱工作区（proot rootfs）—— 见 PORTING.md §5.11 / §4。
 *
 * 本模块的「原生部分」只有两样东西：
 *  1. `src/main/jniLibs/<abi>/libproot_{exec,loader}.so` —— 预编译 PRoot 二进制
 *     （GPL-2.0-or-later，随 AGPL-3.0 工程分发需附源码获取声明；来源：上游
 *     `workspace/src/main/jniLibs/`，arm64-v8a + x86_64 两个 ABI，
 *     完整的许可/源码获取声明见本模块 README.md）。
 *     它们必须经 `jniLibs` 打进 APK 并由 `useLegacyPackaging = true`（app 模块）
 *     解压到 `nativeLibraryDir` —— 那是应用唯一可执行自有文件的目录
 *     （Android 10+ 禁止 exec `/data/data/...`）。少了那个开关，
 *     `File(nativeLibraryDir, "libproot_exec.so").isFile` 为假，所有 shell 命令
 *     都会以 127「proot executable not found」失败，且**只在真机上才看得出来**。
 *  2. `src/main/cpp/termux_pty.cpp` —— 交互式终端页的 PTY 后端（JNI 符号名写死为
 *     `Java_com_termux_terminal_JNI_*`，绑定 `com.termux.termux-app:terminal-view`）。
 *     走 `externalNativeBuild`（需要 NDK + cmake 3.22.1）；文件工具/`workspace_shell`
 *     走 `ProcessBuilder`，不需要它。
 *
 * Kotlin 侧是**纯 JVM 核心**（无 Android API，除日志外），从上游 `workspace` 模块的
 * 7 个文件 1:1 移植：`Workspace` / `WorkspaceFileSystem` / `WorkspaceManager` /
 * `RootfsPatcher` / `RootfsInstaller` / `WorkspaceShellRunner` / `ProotShellRunner`。
 */
android {
    namespace = "com.psyche.memo.workspace"
    compileSdk = 35
    // 交互式终端的 PTY 要 NDK 编译。版本写死是为了让本机与 CI 用同一套工具链
    // （CI 的 sdkmanager 装的就是这个）；不写的话 AGP 会按默认版本去找，
    // 缺了它是 `[CXX1101] NDK at ... did not have a source.properties file`。
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        ndk {
            // 只有这两个 ABI 有 proot 二进制；armeabi-v7a 设备永远用不了工作区，
            // 与其打包一个空的 32 位目录不如直接不产。
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }
    // 交互式终端的 PTY：termux_pty.cpp 提供 com.termux.terminal.JNI 的四个符号，
    // 产出 libtermux.so（AAR 里也有一份同名库，app 侧 pickFirsts 去重）。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            // 单测跑在宿主 JVM 上（本模块不引 Robolectric）；WorkspaceShellRunner 用
            // android.util.Log 记录被吞掉的 IOException，不开这个开关会在调用处抛
            // "Method w in android.util.Log not mocked"。
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    // org.tukaani:xz —— RootfsInstaller 解 TAR_XZ（.tar.xz/.txz）用；gzip 走 JDK。
    implementation(libs.xz)
    testImplementation(libs.junit)
    // 本地 HTTP 服务端（rootfs 下载用例）：上游用 JDK 的 com.sun.net.httpserver，
    // 但 Kotlin 在 Android + jvmTarget 11 下看不到 com.sun.*，改用 core:llm 同款 MockWebServer。
    testImplementation(libs.okhttp.mockwebserver)
}
