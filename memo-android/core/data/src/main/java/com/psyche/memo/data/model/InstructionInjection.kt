package com.psyche.memo.data.model

import kotlinx.serialization.Serializable

/** Instruction injection DTO — instruction_injection.dart JSON shape. */
@Serializable
data class InstructionInjection(
    val id: String = "",
    val title: String = "",
    val prompt: String = "",
    val group: String = "",
)
