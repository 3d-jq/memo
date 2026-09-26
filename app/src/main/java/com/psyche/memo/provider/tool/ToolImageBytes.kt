package com.psyche.memo.provider.tool

/**
 * 工具结果附带的图片字节：原始 bytes + 文件名，由 `ToolHandler.persistToolImage`
 * 落盘成 [com.psyche.memo.data.model.ToolImage]。
 *
 * 放在 `provider/tool` 而不是工作区里，是因为**两个来源同源**：工作区 `read_file`
 * 读到的图片和 Agent 浏览器的截图走的是同一条上行链路。
 */
class ToolImageBytes(val name: String, val bytes: ByteArray)
