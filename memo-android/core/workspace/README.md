# core:workspace — 沙箱工作区（proot rootfs）

移植自 **RikkaHub**（<https://github.com/rikkahub/rikkahub>，AGPL-3.0，与本工程同许可）
的 `workspace` 模块：把一份 Ubuntu base rootfs 放进应用私有目录，用 PRoot 在里面跑
Linux 命令（`workspace_shell` 工具 + 文件读写工具 + 交互式终端页）。

> ⚠️ 这是**相对 Flutter 原项目的新增功能**（kelivo 没有 workspace，也没有对应 ARB/页面），
> 属有意偏离，见 `docs/PORTING.md` §5.11 与 §4。

## 目录

| 路径 | 作用 |
|---|---|
| `src/main/jniLibs/{arm64-v8a,x86_64}/libproot_{exec,loader}.so` | 预编译 PRoot 二进制（来自 RikkaHub 的 `workspace/src/main/jniLibs/`，ABI 只有 arm64-v8a + x86_64）。**只读文件，勿改** |
| `src/main/cpp/termux_pty.cpp` | 交互式终端的 PTY 后端；JNI 符号名写死 `Java_com_termux_terminal_JNI_*`，绑定 `com.termux.termux-app:terminal-view`；要 NDK（`ndkVersion = "28.2.13676358"`）+ cmake 3.22.1 |
| `src/main/cpp/CMakeLists.txt` | 产 `libworkspace.so`（空壳，对齐上游模块结构）与 `libtermux.so`（真 PTY） |

## 三条硬性约束（少一条整个功能就是静默失败）

1. **`useLegacyPackaging = true`**（app 模块 `packaging { jniLibs { … } }`）——
   Android 10+ 只有 `nativeLibraryDir`（`/data/app/<pkg>/lib/<abi>`）里的文件可执行，
   默认的 `extractNativeLibs=false` 会把 `.so` 留在 APK 里、`nativeLibraryDir` 为空。
   症状：`File(nativeLibraryDir, "libproot_exec.so").isFile == false`，**所有** shell 命令
   以 exit 127「proot executable not found」返回 —— 只有真机才看得出来。
2. **只有 arm64-v8a / x86_64**：没有 32 位 proot 二进制；`abiFilters` 已限定，
   `armeabi-v7a` 设备（minSdk 26 合法）永远用不了工作区。
3. **`abiFilters` + `ndk {}`** 放在本模块与 app 模块两处，避免产出空 ABI 目录。
4. **`ndkVersion = "28.2.13676358"` 写死**：不写的话 AGP 按默认版本（27.0.12077973）去找，
   缺了它是 `[CXX1101] NDK at … did not have a source.properties file`。CI 里
   `.github/workflows/android-pr-check.yml` 用 `sdkmanager` 装同版本 + `cmake;3.22.1`。
5. **PTY 的 `libtermux.so` 必须是我们这份**：terminal-view AAR 也带一个同名库，app 模块靠
   `packaging.jniLibs.pickFirsts += "lib/*/libtermux.so"` 去重（我们的 `.so` 里有
   `Java_com_termux_terminal_JNI_*` 四个符号）。

## 真机 spike 结论（2026-09-14，OPPO PKB110 / ColorOS / Android 16 / arm64-v8a）

在决定投入完整移植之前先验了最要命的两点，**均通过**：

```
# 1) 二进制确实被解压到 nativeLibraryDir 且有执行位
run-as com.psyche.memo.dev ls -l <nativeLibraryDir>/lib/arm64
  -rwxr-xr-x … libproot_exec.so
  -rwxr-xr-x … libproot_loader.so

# 2) 能执行（exec 权限 + SELinux 放行 untrusted_app 执行 app 自有 lib 目录）
./libproot_exec.so --version   →   proot 5.1.107.92

# 3) ptrace 机制可用（PRoot 的核心；部分加固 ROM 会拦）
PROOT_LOADER=…/libproot_loader.so PROOT_TMP_DIR=<cache> \
  ./libproot_exec.so --root-id --link2symlink --kill-on-exit -r / /system/bin/echo PROOT_PTRACE_OK
  →   PROOT_PTRACE_OK
```

复现命令（设备 shell 里跑，注意安装路径含 `==`，**不要**用 `env VAR=… <path>`——
toybox 的 `env` 会把带 `=` 的路径当成变量赋值）：

```sh
run-as com.psyche.memo.dev sh -c 'export PROOT_LOADER=<lib>/libproot_loader.so; \
  export PROOT_TMP_DIR=<cache>; cd <lib>; \
  ./libproot_exec.so --root-id --link2symlink --kill-on-exit -r / /system/bin/echo OK'
```

## 许可 / 源码获取声明

`libproot_{exec,loader}.so` 是 PRoot 的预编译产物，PRoot 上游为
**GPL-2.0-or-later**；本工程整体为 AGPL-3.0，二者兼容，但按 GPL/AGPL §3 需要提供
**对应源码的获取途径**。这些二进制随本仓库分发，其源码对应 RikkaHub 仓库中的同名
二进制（<https://github.com/rikkahub/rikkahub>，`workspace/src/main/jniLibs/`）与
PRoot 上游（termux/proot 系列，GPL-2.0-or-later）。
