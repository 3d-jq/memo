package com.psyche.memo.llm.stream

/**
 * 供应商 SSE 解码器（Dart `StreamChunkDecoder`）：一个实例对应一次 HTTP 响应，
 * 逐事件吐出 [StreamChunk]，并在流自然收尾时给出结束事件。
 */
interface StreamDecoder {
    fun accept(event: SseEvent): DecodeResult

    /** 传输结束（没有 terminal 事件）时的收尾事件，已收尾则返回空。 */
    fun onClosed(): List<StreamChunk>
}
