package com.psyche.memo.data.model

import kotlinx.serialization.Serializable

/** Assistant group tag — mirrors lib/core/models/assistant_tag.dart. */
@Serializable
data class AssistantTag(
    val id: String = "",
    val name: String = "",
)
