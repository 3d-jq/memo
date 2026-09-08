package com.psyche.memo.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageCircleQuestion
import com.psyche.memo.ui.ChatStyleSpec
import com.psyche.memo.ui.IosCheckbox
import com.psyche.memo.ui.R as UiR
import com.psyche.memo.ui.theme.AppFontWeights
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * ask-user 工具卡 — chat_message_widget.dart `_AskUserToolCard` (6012-6114)
 * 与 `_AskUserInlineBody` (6116-6374) 及全部子组件 (6376-6766) 的移植。
 *
 * 已答状态从 `part.content`（`{answers: {...}}` 载荷）渲染；未答交互态（单/
 * 多选 + Other + Skip + Submit）从 `part.arguments` 的问题渲染。提交走
 * [onSubmit] 回调钩子（镜像 `_RecoveredAskUserAction.onSubmit`）——原生尚无
 * AskUserInteractionService，未接线时按钮禁用（与 Dart 无 action 时一致）。
 * 答案构建/载荷序列化（ask_user_interaction_service.dart AskUserAnswerValue /
 * AskUserResult.toJsonString）作为纯逻辑一并落地。
 */

// ---------------------------------------------------------------------------
// Answer model + pure logic (ask_user_interaction_service.dart)
// ---------------------------------------------------------------------------

/** ask_user_interaction_service.dart AskUserAnswerValue —— value 保留原 JSON 形态。 */
data class AskUserAnswerValue(
    val type: String,
    val value: JsonElement,
    val custom: Boolean,
    val skipped: Boolean,
) {
    companion object {
        fun single(value: String, custom: Boolean) = AskUserAnswerValue(
            type = "single",
            value = JsonPrimitive(value),
            custom = custom,
            skipped = false,
        )

        fun multi(value: List<String>, custom: Boolean) = AskUserAnswerValue(
            type = "multi",
            value = JsonArray(value.map { JsonPrimitive(it) }),
            custom = custom,
            skipped = false,
        )

        fun skipped(kind: AskUserQuestionKind) = AskUserAnswerValue(
            type = if (kind == AskUserQuestionKind.Multi) "multi" else "single",
            value = JsonPrimitive(""),
            custom = false,
            skipped = true,
        )
    }

    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive(type),
            "value" to value,
            "custom" to JsonPrimitive(custom),
            "skipped" to JsonPrimitive(skipped),
        ),
    )
}

/** ask_user_interaction_service.dart AskUserResult —— 只携带序列化载荷。 */
class AskUserResult private constructor(val jsonString: String) {
    companion object {
        fun answer(answers: Map<String, AskUserAnswerValue>): AskUserResult {
            val payload = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("ask_user_answer"),
                    "answers" to JsonObject(answers.mapValues { (_, v) -> v.toJson() }),
                ),
            )
            return AskUserResult(payload.toString())
        }

        fun error(error: String, message: String): AskUserResult {
            val payload = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("tool_error"),
                    "error" to JsonPrimitive(error),
                    "message" to JsonPrimitive(message),
                    "tool" to JsonPrimitive("ask_user_input_v0"),
                ),
            )
            return AskUserResult(payload.toString())
        }
    }
}

/** ask_user_interaction_service.dart `_buildAnswers` (6162-6206)。 */
internal fun buildAskUserAnswers(
    questions: List<AskUserQuestion>,
    singleAnswers: Map<String, String>,
    multiAnswers: Map<String, Set<String>>,
    textValues: Map<String, String>,
    skipped: Set<String>,
): Map<String, AskUserAnswerValue> {
    val out = LinkedHashMap<String, AskUserAnswerValue>()
    for (question in questions) {
        if (question.id in skipped) {
            out[question.id] = AskUserAnswerValue.skipped(question.kind)
            continue
        }
        val textValue = textValues[question.id].orEmpty().trim()
        when (question.kind) {
            AskUserQuestionKind.Single -> {
                val selected = singleAnswers[question.id].orEmpty()
                if (textValue.isNotEmpty() && textValue != selected) {
                    out[question.id] = AskUserAnswerValue.single(textValue, custom = true)
                } else {
                    out[question.id] = AskUserAnswerValue.single(selected, custom = false)
                }
            }
            AskUserQuestionKind.Multi -> {
                val selectedValues = multiAnswers[question.id].orEmpty().toMutableList()
                if (textValue.isNotEmpty() && !selectedValues.contains(textValue)) {
                    selectedValues.add(textValue)
                    out[question.id] = AskUserAnswerValue.multi(selectedValues, custom = true)
                } else {
                    out[question.id] = AskUserAnswerValue.multi(selectedValues, custom = false)
                }
            }
        }
    }
    return out
}

/** chat_message_widget.dart `_answeredValues` (6224-6231)：解析 `{answers: {...}}`。 */
internal fun parseAnsweredValues(content: String): Map<String, JsonObject> {
    return try {
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(content).jsonObject
        (obj["answers"] as? JsonObject)?.mapValues { (_, v) ->
            (v as? JsonObject) ?: JsonObject(emptyMap())
        } ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

/** chat_message_widget.dart `_answerLabel` (6208-6222)。 */
internal fun answerLabel(
    question: AskUserQuestion,
    answers: Map<String, JsonObject>,
    skippedLabel: String,
): String {
    val raw = answers[question.id] ?: return ""
    if (raw["skipped"] == JsonPrimitive(true)) return skippedLabel
    val value = raw["value"]
    return when (value) {
        is JsonArray -> value.joinToString(", ") { it.scalarText() }
        null -> ""
        else -> value.scalarText()
    }
}

/** Dart `value.toString()` 的近似：字符串/数字/布尔不带引号，对象/数组紧凑 JSON。 */
private fun JsonElement.scalarText(): String = when (this) {
    JsonNull -> ""
    is JsonPrimitive -> content
    is JsonObject, is JsonArray -> toString()
}

// ---------------------------------------------------------------------------
// _AskUserToolCard (chat_message_widget.dart 6012-6114)
// ---------------------------------------------------------------------------

/**
 * boxed 工具卡里的 ask-user 分支：16dp 圆角共享表面 + 可折叠头部
 * （MessageCircleQuestion 18 + 标题 + 已答徽标 + 箭头）+ AnimatedSize 展开体。
 */
@Composable
fun AskUserToolCard(
    part: ToolUiPart,
    onSubmit: ((AskUserResult) -> Unit)? = null,
    askUser: AskUserInteractionService? = null,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg(cs, isDark)
    var expanded by remember { mutableStateOf(true) }
    val answered = part.content?.trim()?.isNotEmpty() == true && !part.loading
    // didUpdateWidget：流式期间从未答转到已答时自动展开。
    LaunchedEffect(answered) { if (answered) expanded = true }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                cs.primaryContainer.copy(
                    alpha = if (isDark) {
                        ChatStyleSpec.TIMELINE_CARD_ALPHA_DARK
                    } else {
                        ChatStyleSpec.TIMELINE_CARD_ALPHA_LIGHT
                    },
                ),
                RoundedCornerShape(16.dp),
            )
            .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        CardPress(
            onTap = { expanded = !expanded },
            isDark = isDark,
            modifier = Modifier.fillMaxWidth(),
            radius = 10.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Lucide.MessageCircleQuestion,
                    contentDescription = null,
                    tint = fg.strong,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = askUserToolTitleFor(part.arguments),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = AppFontWeights.emphasis,
                        color = fg.strong,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (answered) {
                    Text(
                        text = stringResource(UiR.string.ask_user_card_answered),
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = AppFontWeights.emphasis,
                            color = fg.muted,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                    tint = fg.muted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .animateContentSize(
                        animationSpec = tween(durationMillis = 240, easing = EaseOutCubic),
                    ),
            ) {
                AskUserInlineBody(part = part, onSubmit = onSubmit, askUser = askUser)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// _AskUserInlineBody (chat_message_widget.dart 6116-6374)
// ---------------------------------------------------------------------------

/**
 * 问题/已答主体。compact 参数在源码 build 里未被使用，略去。
 * 未答且无问题 → "no longer active"；已答 → 逐问显示题干+答案；否则交互表单
 * + 提交按钮（无 [onSubmit] 时禁用）。
 */
@Composable
internal fun AskUserInlineBody(
    part: ToolUiPart,
    onSubmit: ((AskUserResult) -> Unit)? = null,
    askUser: AskUserInteractionService? = null,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg(cs, isDark)
    // ask_user_interaction_service.dart pendingRequests[part.id] —— 进行中的提问
    // 请求给出权威问题集；无请求时退回到参数的存储问题（Dart 6273-6277）。
    val pendingMap by remember(askUser) {
        askUser?.pendingRequests ?: MutableStateFlow<Map<String, AskUserRequest>>(emptyMap())
    }.collectAsState()
    val pendingRequest = pendingMap[part.id]
    val questions = remember(part.arguments, pendingRequest) {
        pendingRequest?.questions ?: normalizeAskUserQuestions(part.arguments)
    }
    val answered = part.content?.trim()?.isNotEmpty() == true
    val invalid = questions.isEmpty() && !answered
    val answeredValues = remember(part.content) {
        if (answered) parseAnsweredValues(part.content.orEmpty()) else emptyMap()
    }

    val singleAnswers = remember { mutableStateMapOf<String, String>() }
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }
    val textValues = remember { mutableStateMapOf<String, String>() }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var submitting by remember { mutableStateOf(false) }

    fun hasAnswer(question: AskUserQuestion): Boolean {
        if (question.id in skipped) return true
        if (textValues[question.id].orEmpty().trim().isNotEmpty()) return true
        return when (question.kind) {
            AskUserQuestionKind.Single -> singleAnswers[question.id].orEmpty().trim().isNotEmpty()
            AskUserQuestionKind.Multi -> multiAnswers[question.id].orEmpty().isNotEmpty()
        }
    }

    fun clearSkip(id: String) {
        skipped = skipped - id
    }

    Column(horizontalAlignment = Alignment.Start) {
        when {
            invalid -> Text(
                text = stringResource(UiR.string.ask_user_card_inactive),
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.2.sp, color = fg.body),
            )
            answered -> questions.forEachIndexed { index, question ->
                AskUserAnsweredQuestion(
                    question = question,
                    answer = answerLabel(
                        question,
                        answeredValues,
                        stringResource(UiR.string.ask_user_card_skipped),
                    ),
                )
                if (index != questions.lastIndex) Spacer(Modifier.height(10.dp))
            }
            else -> {
                questions.forEachIndexed { index, question ->
                    AskUserQuestionView(
                        question = question,
                        selectedSingle = singleAnswers[question.id],
                        selectedMulti = multiAnswers[question.id] ?: emptySet(),
                        textValue = textValues[question.id].orEmpty(),
                        skipped = question.id in skipped,
                        showQuestionText = true,
                        onOtherChanged = { value ->
                            clearSkip(question.id)
                            if (question.kind == AskUserQuestionKind.Single &&
                                value.trim().isNotEmpty()
                            ) {
                                singleAnswers.remove(question.id)
                            }
                            textValues[question.id] = value
                        },
                        onSelectSingle = { value ->
                            clearSkip(question.id)
                            singleAnswers[question.id] = value
                            textValues[question.id] = ""
                        },
                        onToggleMulti = { value ->
                            clearSkip(question.id)
                            val set = multiAnswers[question.id].orEmpty().toMutableSet()
                            if (set.contains(value)) set.remove(value) else set.add(value)
                            multiAnswers[question.id] = set
                        },
                        onToggleSkip = {
                            if (question.id in skipped) {
                                clearSkip(question.id)
                            } else {
                                singleAnswers.remove(question.id)
                                multiAnswers.remove(question.id)
                                textValues.remove(question.id)
                                skipped = skipped + question.id
                            }
                        },
                    )
                    if (index != questions.lastIndex) Spacer(Modifier.height(12.dp))
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    AskUserSubmitButton(
                        label = stringResource(UiR.string.ask_user_card_submit),
                        color = cs.primary,
                        enabled = questions.isNotEmpty() &&
                            questions.all { hasAnswer(it) } &&
                            !submitting &&
                            (pendingRequest != null || onSubmit != null),
                        onTap = {
                            val answers = buildAskUserAnswers(
                                questions,
                                singleAnswers,
                                multiAnswers,
                                textValues,
                                skipped,
                            )
                            if (pendingRequest != null && askUser != null) {
                                // 进行中：直接回调服务完成 deferred（Dart 6243-6245）。
                                askUser.answer(part.id, answers)
                            } else if (onSubmit != null) {
                                // 恢复路径（无进行中请求）：走 recovered 提交回调。
                                submitting = true
                                try {
                                    onSubmit(AskUserResult.answer(answers))
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// _AskUserQuestionView (6376-6450) + children
// ---------------------------------------------------------------------------

@Composable
private fun AskUserQuestionView(
    question: AskUserQuestion,
    selectedSingle: String?,
    selectedMulti: Set<String>,
    textValue: String,
    skipped: Boolean,
    showQuestionText: Boolean,
    onOtherChanged: (String) -> Unit,
    onSelectSingle: (String) -> Unit,
    onToggleMulti: (String) -> Unit,
    onToggleSkip: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg(cs, isDark)
    val isMulti = question.kind == AskUserQuestionKind.Multi

    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showQuestionText) {
                Text(
                    text = question.question,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 17.55.sp, color = fg.body),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
            }
            AskUserSkipPill(selected = skipped, onTap = onToggleSkip)
        }
        Spacer(Modifier.height(8.dp))
        if (question.options.isNotEmpty()) {
            question.options.forEachIndexed { index, option ->
                AskUserOptionRow(
                    index = index + 1,
                    label = option,
                    multi = isMulti,
                    selected = if (isMulti) selectedMulti.contains(option) else selectedSingle == option,
                    disabled = skipped,
                    onTap = { if (isMulti) onToggleMulti(option) else onSelectSingle(option) },
                )
                Spacer(Modifier.height(7.dp))
            }
        }
        AskUserOtherRow(
            index = question.options.size + 1,
            multi = isMulti,
            selected = textValue.trim().isNotEmpty(),
            value = textValue,
            disabled = skipped,
            onChanged = onOtherChanged,
        )
    }
}

/** _AskUserAnsweredQuestion (6452-6496)。 */
@Composable
private fun AskUserAnsweredQuestion(question: AskUserQuestion, answer: String) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg(cs, isDark)
    val displayAnswer = if (answer.trim().isEmpty()) {
        stringResource(UiR.string.ask_user_card_skipped)
    } else {
        answer.trim()
    }
    Column(
        horizontalAlignment = Alignment.Start,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            text = question.question,
            style = TextStyle(
                fontSize = 12.5.sp,
                lineHeight = 16.875.sp,
                color = fg.body,
                fontWeight = AppFontWeights.semibold,
            ),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = displayAnswer,
            style = TextStyle(
                fontSize = 13.sp,
                lineHeight = 17.55.sp,
                color = cs.primary.copy(alpha = 0.86f),
                fontWeight = AppFontWeights.semibold,
            ),
        )
    }
}

/** _AskUserOptionRow (6498-6568)。 */
@Composable
private fun AskUserOptionRow(
    index: Int,
    label: String,
    multi: Boolean,
    selected: Boolean,
    disabled: Boolean,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg(cs, isDark)
    val bg = if (selected) cs.primary.copy(alpha = 0.09f) else Color.Transparent
    CardPress(
        onTap = if (disabled) null else onTap,
        isDark = isDark,
        modifier = Modifier.fillMaxWidth(),
        radius = 14.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(bg, RoundedCornerShape(14.dp))
                .heightIn(min = 40.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (multi) {
                IosCheckbox(
                    value = selected,
                    onValueChanged = if (disabled) null else { _ -> onTap() },
                    size = 18.dp,
                    hitTestSize = 24.dp,
                    activeColor = cs.primary,
                    semanticLabel = label,
                )
            } else {
                AskUserIndexBadge(index = index, selected = selected)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 16.25.sp,
                    fontWeight = AppFontWeights.medium,
                    color = if (selected) cs.primary else fg.strong,
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** _AskUserOtherRow (6570-6641)：多选时行首是 IgnorePointer 的 IosCheckbox。 */
@Composable
private fun AskUserOtherRow(
    index: Int,
    multi: Boolean,
    selected: Boolean,
    value: String,
    disabled: Boolean,
    onChanged: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg(cs, isDark)
    val bg = if (selected) cs.primary.copy(alpha = 0.09f) else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (multi) {
            IosCheckbox(
                value = selected,
                onValueChanged = if (disabled) null else { _ -> },
                size = 18.dp,
                hitTestSize = 24.dp,
                activeColor = cs.primary,
                semanticLabel = stringResource(UiR.string.ask_user_card_something_else),
                interactive = false,
            )
        } else {
            AskUserIndexBadge(index = index, selected = selected)
        }
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onChanged,
            enabled = !disabled,
            maxLines = 2,
            textStyle = TextStyle(fontSize = 13.sp, lineHeight = 16.25.sp, color = fg.strong),
            modifier = Modifier.weight(1f),
            // Dart InputDecoration(hintText, border: none, contentPadding: zero)。
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(UiR.string.ask_user_card_custom_hint),
                            style = TextStyle(
                                fontSize = 13.sp,
                                lineHeight = 16.25.sp,
                                color = cs.onSurface.copy(alpha = 0.38f),
                            ),
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/** _AskUserIndexBadge (6643-6679)：24dp 圆底序号。 */
@Composable
private fun AskUserIndexBadge(index: Int, selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg(cs, isDark)
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(
                if (selected) {
                    cs.primary.copy(alpha = 0.13f)
                } else {
                    cs.onSurface.copy(alpha = if (isDark) 0.08f else 0.05f)
                },
                CircleShape,
            )
            .border(
                width = 1.dp,
                color = if (selected) {
                    cs.primary.copy(alpha = 0.28f)
                } else {
                    cs.onSurface.copy(alpha = if (isDark) 0.10f else 0.07f)
                },
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$index",
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = AppFontWeights.emphasis,
                color = if (selected) cs.primary else fg.muted,
            ),
        )
    }
}

/** _AskUserSkipPill (6681-6711)。 */
@Composable
private fun AskUserSkipPill(selected: Boolean, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val fg = chatSurfaceFg(cs, isDark)
    CardPress(onTap = onTap, isDark = isDark, radius = 7.dp) {
        Text(
            text = stringResource(UiR.string.ask_user_card_skip),
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = AppFontWeights.semibold,
                color = if (selected) cs.primary.copy(alpha = 0.78f) else fg.muted,
            ),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** _AskUserSubmitButton (6713-6766)。 */
@Composable
private fun AskUserSubmitButton(
    label: String,
    color: Color,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val base = if (enabled) {
        color.copy(alpha = 0.86f)
    } else {
        cs.surfaceContainerHighest.copy(alpha = 0.45f)
    }
    val fgColor = if (enabled) cs.onPrimary else cs.onSurface.copy(alpha = 0.38f)
    CardPress(
        onTap = if (enabled) onTap else null,
        isDark = isDark,
        radius = 14.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .height(38.dp)
                .background(base, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp),
        ) {
            Icon(
                Lucide.ArrowUp,
                contentDescription = null,
                tint = fgColor,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = AppFontWeights.heavy,
                    color = fgColor,
                ),
            )
        }
    }
}
