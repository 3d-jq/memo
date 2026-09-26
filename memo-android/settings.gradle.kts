pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jlatexmath-android（RikkaHub fork）：数学公式渲染，见 PORTING.md §5.12 渲染批。
        maven("https://jitpack.io")
    }
}

rootProject.name = "memo-android"

// 模块只有「真有代码」才列在这里。2026-09-26 删掉 4 个只剩空 AndroidManifest 的
// feature:* 骨架模块（各带一个空 namespace，零 Kotlin 源，却完整参与 lint/test/assemble）。
// 将来真要拆 feature 域，连同第一行真实代码一起加回来。
include(":app")
include(":core:common")
include(":core:ui")
include(":core:highlight")
include(":core:data")
include(":core:llm")
include(":core:workspace")
