package com.psyche.memo.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ask_user_interaction_service.dart 的问题归一化部分 —— 工具卡标题按问题数
 * 取 `askUserCardQuestionCount`，因此渲染层需要同一套归一化口径。
 * 交互（作答/跳过/自定义）与 [AskUserQuestion.toJson] 属 ask-user 交互批次。
 */

/** ask_user_interaction_service.dart AskUserQuestionKind。 */
enum class AskUserQuestionKind { Single, Multi }

/** ask_user_interaction_service.dart AskUserQuestion。 */
data class AskUserQuestion(
    val id: String,
    val question: String,
    val kind: AskUserQuestionKind,
    val options: List<String> = emptyList(),
)

/** ask_user_interaction_service.dart AskUserInteractionService.normalizeQuestions。 */
fun normalizeAskUserQuestions(arguments: JsonObject): List<AskUserQuestion> {
    val rawQuestions = arguments["questions"] as? JsonArray ?: return emptyList()

    val usedIds = HashSet<String>()
    val questions = ArrayList<AskUserQuestion>()
    for (raw in rawQuestions.take(4)) {
        val map = raw as? JsonObject ?: continue
        val question = map.text("question").trim()
        if (question.isEmpty()) continue

        var id = map.text("id").trim()
        if (id.isEmpty() || usedIds.contains(id)) {
            id = "q${questions.size + 1}"
        }
        // 源码里后缀固定为 usedIds.size+1，撞名时会原地打转；这里让后缀递增，
        // 正常输入下结果与源码一致。
        var suffix = usedIds.size + 1
        while (usedIds.contains(id)) {
            id = "q${questions.size + 1}_$suffix"
            suffix++
        }
        usedIds.add(id)

        questions.add(
            AskUserQuestion(
                id = id,
                question = question,
                kind = kindFromString(map.text("type")),
                options = normalizeOptions(map["options"]),
            ),
        )
    }
    return questions
}

private fun kindFromString(value: String): AskUserQuestionKind =
    if (value.trim().lowercase() == "multi") AskUserQuestionKind.Multi else AskUserQuestionKind.Single

private fun normalizeOptions(raw: JsonElement?): List<String> {
    val array = raw as? JsonArray ?: return emptyList()
    val out = ArrayList<String>()
    for (item in array) {
        val text = item.text().trim()
        if (text.isEmpty() || out.contains(text)) continue
        out.add(text)
        if (out.size == 4) break
    }
    return out
}

private fun JsonObject.text(key: String): String = this[key].text()

/** JsonPrimitive 取原始字符串；对象/数组按 Dart 的 toString 语义不参与。 */
private fun JsonElement?.text(): String =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
