package com.fixlocal.android.data.model

data class FixResult(
    val originalText: String,
    val fixedText: String,
    val latencyMs: Long,
    val memoryUsageMb: Double,
    val error: String? = null
)
