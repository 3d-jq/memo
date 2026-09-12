package com.psyche.memo.llm.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Port of `lib/core/services/custom_request_merger.dart` — merges the assistant
 * / provider / model custom-request layers into one header map and one body
 * object for the outgoing LLM request.
 *
 * Header layers apply in order base → assistant → providerAutomatic → provider
 * → model → protectedAssistant, each later layer winning case-insensitively;
 * `x-conversation-id` is a protected assistant header that always lands last.
 * Body values go through `parseOverrideValue` ("true"/"30"/"{...}" become real
 * JSON types), with provider rows then model entries winning over assistant.
 */
object CustomRequestMerger {

    private val PROTECTED_ASSISTANT_HEADERS = setOf("x-conversation-id")

    fun mergeHeaders(
        base: Map<String, String> = emptyMap(),
        assistant: Map<String, String>? = null,
        providerAutomatic: Map<String, String> = emptyMap(),
        provider: Map<String, String> = emptyMap(),
        model: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val protected = LinkedHashMap<String, String>()
        val ordinaryAssistant = LinkedHashMap<String, String>()
        if (assistant != null) {
            for ((key, value) in assistant) {
                if (key.lowercase() in PROTECTED_ASSISTANT_HEADERS) {
                    protected[key] = value
                } else {
                    ordinaryAssistant[key] = value
                }
            }
        }
        val merged = LinkedHashMap<String, String>()
        for (layer in listOf(base, ordinaryAssistant, providerAutomatic, provider, model, protected)) {
            addHeadersCaseInsensitive(merged, layer)
        }
        return merged
    }

    fun mergeBody(
        assistant: Map<String, String>? = null,
        providerRows: List<Map<String, String>> = emptyList(),
        model: Map<String, String> = emptyMap(),
    ): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>()
        if (assistant != null) {
            for ((key, value) in assistant) {
                merged[key] = parseOverrideValue(value)
            }
        }
        for ((key, value) in bodyFromRows(providerRows)) {
            merged[key] = value
        }
        for ((key, value) in model) {
            merged[key] = parseOverrideValue(value)
        }
        return JsonObject(merged)
    }

    /** `ModelOverridePayloadParser.customBodyFromRows` — rows are {key, value}. */
    fun bodyFromRows(rows: List<Map<String, String>>): JsonObject = buildJsonObject {
        for (row in rows) {
            val key = row["key"]?.trim().orEmpty()
            val value = row["value"]
            if (key.isNotEmpty()) put(key, parseOverrideValue(value))
        }
    }

    /** `ModelOverridePayloadParser.parseOverrideValue` — strings become typed JSON. */
    fun parseOverrideValue(raw: String?): JsonElement {
        if (raw == null) return JsonNull
        val s = raw.trim()
        if (s.isEmpty()) return JsonPrimitive(raw)
        when (s) {
            "true" -> return JsonPrimitive(true)
            "false" -> return JsonPrimitive(false)
            "null" -> return JsonNull
        }
        s.toIntOrNull()?.let { return JsonPrimitive(it) }
        s.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        if ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))) {
            runCatching { return kotlinx.serialization.json.Json.parseToJsonElement(s) }
        }
        return JsonPrimitive(raw)
    }

    private fun addHeadersCaseInsensitive(
        target: LinkedHashMap<String, String>,
        layer: Map<String, String>,
    ) {
        for ((key, value) in layer) {
            val normalized = key.lowercase()
            target.keys.removeAll { it.lowercase() == normalized }
            target[key] = value
        }
    }
}
