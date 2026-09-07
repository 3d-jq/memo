package com.psyche.memo.ui.theme

import androidx.compose.ui.text.font.FontWeight

/**
 * app_font_weights.dart —— Android 上系统字重缺少真正的 medium/semibold，
 * Flutter 侧只在 TargetPlatform.android 归一化：w500→w400、w600+→w500。
 * 本工程只有 Android，所以归一化始终生效。
 */
object AppFontWeights {
    val regular: FontWeight get() = normalize(FontWeight.W400)
    val medium: FontWeight get() = normalize(FontWeight.W500)
    val semibold: FontWeight get() = normalize(FontWeight.W600)
    val emphasis: FontWeight get() = normalize(FontWeight.W700)
    val strong: FontWeight get() = normalize(FontWeight.W700)
    val heavy: FontWeight get() = normalize(FontWeight.W800)
    val black: FontWeight get() = normalize(FontWeight.W900)

    fun normalize(weight: FontWeight): FontWeight = when {
        weight.weight == 500 -> FontWeight.W400
        weight.weight >= 600 -> FontWeight.W500
        else -> weight
    }
}
