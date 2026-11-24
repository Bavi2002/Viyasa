package com.fixlocal.android.data.model

enum class ModelOption(
    val modelId: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeText: String,
    val expectedSizeBytes: Long,
    val description: String
) {
    GEMMA3_270M(
        modelId = "gemma3-270m",
        displayName = "Gemma 3 (270M-IT)",
        fileName = "gemma3_270m_fused.litertlm",
        downloadUrl = "https://huggingface.co/saliltambe/gemma3-270m-litertlm/resolve/main/gemma3_270m_fused.litertlm",
        sizeText = "272 MB",
        expectedSizeBytes = 285577392L,
        description = "Ultra-compact • Google's on-device 270M model"
    ),
    QWEN3_0_6B_Q4(
        modelId = "qwen3-0.6b-q4",
        displayName = "Qwen3 (0.6B Q4)",
        fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        sizeText = "343 MB",
        expectedSizeBytes = 343490560L,
        description = "Recommended • Multilingual translation & high accuracy"
    );

    companion object {
        fun fromFileName(fileName: String): ModelOption? {
            return entries.find { it.fileName.equals(fileName, ignoreCase = true) }
        }
    }
}
