package com.psyche.memo.data.model

import kotlinx.serialization.Serializable

/**
 * Quick phrase DTO — mirrors lib/core/models/quick_phrase.dart's JSON shape
 * (stored in quick_phrase_rows.payload).
 */
@Serializable
data class QuickPhrase(
    val id: String = "",
    val title: String = "",
    val content: String = "",
    val isGlobal: Boolean = true,
    val assistantId: String? = null,
)
