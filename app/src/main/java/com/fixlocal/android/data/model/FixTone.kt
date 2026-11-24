package com.fixlocal.android.data.model

enum class FixTone(
    val displayName: String,
    val description: String,
    val promptInstruction: String
) {
    NATURAL(
        displayName = "Natural",
        description = "Balanced, fluent, and native everyday English",
        promptInstruction = "natural everyday English"
    ),
    CASUAL(
        displayName = "Casual",
        description = "Relaxed, friendly, and conversational for chat & messaging",
        promptInstruction = "casual, friendly messaging English"
    ),
    PROFESSIONAL(
        displayName = "Professional",
        description = "Polished, formal, and precise for workplace & emails",
        promptInstruction = "formal professional English"
    ),
    CUSTOM(
        displayName = "Custom",
        description = "User-defined custom instructions and optional word limits",
        promptInstruction = ""
    );

    companion object {
        fun fromName(name: String?): FixTone {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: NATURAL
        }
    }
}
