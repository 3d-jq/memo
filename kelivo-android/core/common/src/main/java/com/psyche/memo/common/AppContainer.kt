package com.psyche.memo.common

/**
 * Manual dependency container (no Hilt/KSP by design — see plan.md).
 * Built once at app startup; feature ViewModels and screens pull what they
 * need from here.
 */
interface AppContainer {
    val appName: String
    val platform: Platform
}

enum class Platform { ANDROID }
