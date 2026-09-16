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

include(":app")
include(":core:common")
include(":core:ui")
include(":core:highlight")
include(":core:data")
include(":core:llm")
include(":core:workspace")
include(":feature:chat")
include(":feature:assistant")
include(":feature:utility")
include(":feature:settings")
