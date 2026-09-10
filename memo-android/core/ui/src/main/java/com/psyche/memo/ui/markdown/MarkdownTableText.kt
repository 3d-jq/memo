package com.psyche.memo.ui.markdown

/**
 * Serialization helpers behind the table toolbar (copy / export CSV).
 *
 * These mirror the original's `_rowsToCsv` / `_rowsToMarkdown` /
 * `_csvCell` (markdown_with_highlight.dart L4014-4075) exactly — a copy that
 * pasted as CSV must be byte-compatible with the Flutter app, since the whole
 * port exists so both clients can be used interchangeably.
 */
object MarkdownTableText {

    /** Rows of cells -> CSV text, CRLF-joined like the original. */
    fun toCsv(rows: List<List<String>>): String =
        rows.joinToString("\r\n") { row -> row.joinToString(",") { csvCell(it) } }

    /**
     * Rows of cells -> a GFM pipe table. Ragged rows are padded to the widest
     * row so the re-parsed markdown keeps its column count.
     */
    fun toMarkdown(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val columnCount = rows.maxOf { it.size }
        if (columnCount == 0) return ""

        val normalized = rows.map { row ->
            List(columnCount) { index -> row.getOrElse(index) { "" }.let(::markdownCell) }
        }
        val buffer = StringBuilder()
        buffer.append(markdownLine(normalized.first())).append('\n')
        buffer.append(markdownLine(List(columnCount) { "---" })).append('\n')
        normalized.drop(1).forEach { row ->
            buffer.append(markdownLine(row)).append('\n')
        }
        return buffer.toString().trimEnd()
    }

    /**
     * Quotes a CSV cell only when it needs it. The original leaves bare values
     * untouched (no trimming), which is what spreadsheet apps expect.
     */
    fun csvCell(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun markdownLine(cells: List<String>): String =
        "| " + cells.joinToString(" | ") + " |"

    /** Escapes a cell for a pipe table; newlines become `<br>`, like the original. */
    private fun markdownCell(value: String): String =
        value.trim()
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r\n", "<br>")
            .replace("\n", "<br>")
            .replace("\r", "<br>")
}
