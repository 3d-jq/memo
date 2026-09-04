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
    }
}

rootProject.name = "memo-android"

include(":app")
include(":core:common")
include(":core:ui")
include(":core:data")
include(":core:llm")
include(":feature:chat")
include(":feature:assistant")
include(":feature:utility")
include(":feature:settings")
