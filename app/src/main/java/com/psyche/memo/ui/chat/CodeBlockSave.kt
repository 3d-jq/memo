package com.psyche.memo.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.psyche.memo.ui.R
import com.psyche.memo.ui.markdown.CodeBlockActions
import com.psyche.memo.ui.snackbar.NotificationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * markdown_with_highlight.dart `_saveCodeAsFile`（L2709-2760）的平台侧实现。
 *
 * 文件名 = 「代码」词干 + `_` + 本地 ISO8601 时间戳（`:` 与 `.` 一律换成 `-`）+
 * 语言决定的扩展名；写盘交给系统 SAF 创建文档（上游 `FilePicker.saveFile(bytes:)`
 * 在 Android 上走的就是这个）。取消静默返回、成功「已导出为 <文件名>」、失败带原因。
 */
@Composable
fun rememberCodeBlockActions(
    onPreviewHtml: ((String) -> Unit)? = null,
): CodeBlockActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        // SAF 没有上游那层 allowedExtensions：扩展名已经写在建议文件名里交给用户选的位置。
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val job = pending
        pending = null
        // 取消 = 什么都不做（上游 `if (savePath == null) return`）。
        if (uri == null || job == null) return@rememberLauncherForActivityResult
        val (code, suggestedName) = job
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(code.toByteArray(Charsets.UTF_8))
                    } ?: error("stream failed")
                }
            }
            val basename = uri.lastPathSegment?.substringAfterLast('/') ?: suggestedName
            if (outcome.isSuccess) {
                toast(exportedAs(context).format(basename), NotificationType.SUCCESS)
            } else {
                toast(
                    exportFailed(context).format(outcome.exceptionOrNull()?.message ?: basename),
                    NotificationType.ERROR,
                )
            }
        }
    }

    return remember(context, launcher, onPreviewHtml) {
        CodeBlockActions(
            onSaveAs = { code, language ->
                val stem = context.getString(R.string.code_block_default_file_name_stem)
                val stamp = ZonedDateTime.now().toString().replace(Regex("[:.]"), "-")
                val filename = stem + "_" + stamp + codeFileExtension(language)
                pending = code to filename
                launcher.launch(filename)
            },
            onPreviewHtml = onPreviewHtml,
        )
    }
}

/** `_codeFileExtension` L3137-3200：语言别名 → 扩展名，认不出的一律 `.txt`。 */
internal fun codeFileExtension(language: String?): String =
    when ((language ?: "").trim().lowercase()) {
        "kotlin", "kt" -> ".kt"
        "java" -> ".java"
        "python", "py" -> ".py"
        "javascript", "js" -> ".js"
        "typescript", "ts" -> ".ts"
        "dart" -> ".dart"
        "cpp", "c++" -> ".cpp"
        "c" -> ".c"
        "csharp", "cs", "c#" -> ".cs"
        "go", "golang" -> ".go"
        "rust", "rs" -> ".rs"
        "swift" -> ".swift"
        "html", "htm", "rawhtml", "raw_html" -> ".html"
        "css" -> ".css"
        "xml" -> ".xml"
        "json" -> ".json"
        "yaml", "yml" -> ".yml"
        "markdown", "md" -> ".md"
        "sql" -> ".sql"
        "shell", "bash", "sh", "zsh" -> ".sh"
        "svg" -> ".svg"
        else -> ".txt"
    }
