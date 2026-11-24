package com.fixlocal.android.data.model

import java.io.File

sealed interface ModelStatus {
    data object NotDownloaded : ModelStatus
    data class Downloading(
        val modelOption: ModelOption,
        val progressPercent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : ModelStatus
    data class Ready(
        val file: File,
        val sizeBytes: Long,
        val isExternal: Boolean,
        val modelName: String
    ) : ModelStatus
    data class Error(val message: String) : ModelStatus
}
