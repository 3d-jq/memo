// 交互式终端页的原生 PTY —— 上游 `workspace/src/main/cpp/workspace.cpp` 1:1。
//
// 上游只有模板注释、没有任何代码；保留这个空库是为了让 CMakeLists 与上游一一对应
// （app 侧 `System.loadLibrary("workspace")` 也没人调）。真正的 JNI 在 `termux_pty.cpp`。
