# 交互式终端页的原生 PTY —— 上游 `workspace/src/main/cpp/CMakeLists.txt` 1:1。
#
# 产两个库：
#  1. `libworkspace.so` —— 目前只有模板注释（上游也是空壳），保留是为了跟上游模块结构一致；
#  2. `libtermux.so` —— 真正干活的：`termux_pty.cpp` 里的 JNI 符号名写死为
#     `Java_com_termux_terminal_JNI_*`，绑定 `com.termux.termux-app:terminal-view`
#     里的 `com.termux.terminal.JNI`。AAR 里也带一个同名 `libtermux.so`，app 模块靠
#     `packaging.jniLibs.pickFirsts` 决定用我们这份（见 app/build.gradle.kts）。
cmake_minimum_required(VERSION 3.22.1)

project("workspace")

add_library(${CMAKE_PROJECT_NAME} SHARED
        workspace.cpp)

add_library(termux SHARED
        termux_pty.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        android
        log)

target_link_libraries(termux
        log)
