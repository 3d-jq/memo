package com.psyche.memo.common

/**
 * 压缩模型的回退链 —— compress → summary → title → assistant → current
 * （原版 `resolveCompressContextModel`）。旧的
 * `compress_context_options.dart` 分块/字符预算机制随上下文压缩改成 opencode
 * 的阈值 + 锚定摘要机制一起删掉了（见 [SessionCompaction]）。
 */
object CompressModel {

    /** 解析出「用哪个 provider/model 做压缩」；全空时返回 null。 */
    fun resolve(
        compress: Pair<String, String>?,
        summary: Pair<String, String>?,
        title: Pair<String, String>?,
        assistant: Pair<String, String>?,
        current: Pair<String, String>?,
    ): Pair<String, String>? {
        val provider = compress?.first ?: summary?.first ?: title?.first
            ?: assistant?.first ?: current?.first
        val model = compress?.second ?: summary?.second ?: title?.second
            ?: assistant?.second ?: current?.second
        return if (provider.isNullOrEmpty() || model.isNullOrEmpty()) null else provider to model
    }
}
