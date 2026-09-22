package com.psyche.memo.common.compaction

/**
 * provider 报的这条错误是不是「上下文超长」（opencode `isContextLengthError` 的对应物）。
 *
 * 用途：自动压缩重试的触发判据 —— 命中时才值得压缩上下文再发一次，别的错误压缩也没用。
 *
 * 各家的文案不一样，这里按**实际见过的原文**匹配（大小写不敏感）：
 *  - OpenAI：「This model's maximum context length is 128000 tokens」「context_length_exceeded」
 *  - Anthropic：「prompt is too long: 210000 tokens > 200000 maximum」
 *  - Gemini：「input token count exceeds the maximum number of tokens allowed」
 *  - 国内厂商（OpenAI 兼容居多）：「maximum context length」「too many tokens」，
 *    也有直接回中文的「超过最大长度」「上下文长度超限」
 *
 * 只认**明确指向长度/容量**的说法，不把普通的 400（参数错、模型不存在）算进来 ——
 * 那些压缩了也没用，重试只会白等一轮。
 */
fun isContextLengthError(message: String?): Boolean {
    val text = message?.lowercase() ?: return false
    if (text.isBlank()) return false
    return PATTERNS.any { it.containsMatchIn(text) }
}

private val PATTERNS = listOf(
    // 英文：长度/容量超限
    Regex("""context[_ ]length[_ ]exceeded"""),
    Regex("""maximum context length"""),
    Regex("""context length is only"""),
    Regex("""prompt is too long"""),
    Regex("""input (?:is )?too long"""),
    Regex("""too many (?:input )?tokens"""),
    Regex("""input token count exceeds"""),
    Regex("""exceeds? the maximum (?:number of )?tokens"""),
    Regex("""reduce the length of the messages"""),
    Regex("""tokens? (?:in the )?(?:messages|prompt) (?:is|are) too long"""),
    // 中文：国内厂商偶尔直出
    Regex("""超过最大长度"""),
    Regex("""上下文长度"""),
    Regex("""请求过长"""),
)
