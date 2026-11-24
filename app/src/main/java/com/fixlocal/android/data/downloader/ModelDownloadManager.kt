package com.fixlocal.android.data.downloader

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.util.Log
import com.fixlocal.android.data.model.ModelOption
import com.fixlocal.android.data.model.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ModelDownloader"
        // Fallback minimum for unknown custom models
        private const val GENERIC_MIN_BYTES = 50L * 1024L * 1024L
    }

    private val _statusFlow = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val statusFlow: StateFlow<ModelStatus> = _statusFlow.asStateFlow()

    private var downloadJob: Job? = null
    private val fileObservers = mutableListOf<FileObserver>()

    init {
        refreshStatus()
        startDirectoryObservers()
    }

    private fun getCandidateDirectories(): List<File> {
        return listOfNotNull(
            context.filesDir,
            context.getExternalFilesDir("models"),
            File(context.getExternalFilesDir(null), "models"),
            context.getExternalFilesDir(null)
        )
    }

    /**
     * Watches candidate storage directories for any new or modified .litertlm files.
     * Automatically triggers status updates (e.g. when pushed via ADB or copied)
     * without requiring the user to press a manual refresh button.
     */
    private fun startDirectoryObservers() {
        stopDirectoryObservers()
        val dirs = getCandidateDirectories()
        for (dir in dirs) {
            try {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    object : FileObserver(dir, CLOSE_WRITE or MOVED_TO or DELETE) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path == null || path.endsWith(".litertlm") || path.endsWith(".tmp")) {
                                refreshStatus()
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO or DELETE) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path == null || path.endsWith(".litertlm") || path.endsWith(".tmp")) {
                                refreshStatus()
                            }
                        }
                    }
                }
                observer.startWatching()
                fileObservers.add(observer)
            } catch (_: Throwable) {}
        }
    }

    fun stopDirectoryObservers() {
        fileObservers.forEach {
            try { it.stopWatching() } catch (_: Throwable) {}
        }
        fileObservers.clear()
    }

    /**
     * Checks if any incomplete/truncated known model file exists on disk.
     * Returns the file and expected size if found.
     */
    fun findIncompleteModelFile(): Pair<File, Long>? {
        val checkDirs = getCandidateDirectories()
        for (option in ModelOption.entries) {
            for (dir in checkDirs) {
                val f = File(dir, option.fileName)
                if (f.exists() && f.isFile) {
                    val minAcceptable = (option.expectedSizeBytes * 90) / 100
                    if (f.length() < minAcceptable) {
                        return Pair(f, option.expectedSizeBytes)
                    }
                }
            }
        }
        return null
    }

    /**
     * Checks all known candidate paths for any valid .litertlm model file:
     * 1. Known model file names (SmolLM2-135M, SmolLM2-360M, Qwen3-0.6B) with size verification
     * 2. Any .litertlm file inside external models directory or internal filesDir
     */
    fun findExistingModelFile(preferredOption: ModelOption? = null): File? {
        val checkDirs = getCandidateDirectories()

        // 1. If preferred option is specified, check it first
        if (preferredOption != null) {
            for (dir in checkDirs) {
                val f = File(dir, preferredOption.fileName)
                val minAcceptable = (preferredOption.expectedSizeBytes * 90) / 100
                if (f.exists() && f.isFile && f.length() >= minAcceptable) {
                    return f
                }
            }
        }

        // 2. Check all known models
        for (option in ModelOption.entries) {
            for (dir in checkDirs) {
                val f = File(dir, option.fileName)
                val minAcceptable = (option.expectedSizeBytes * 90) / 100
                if (f.exists() && f.isFile && f.length() >= minAcceptable) {
                    return f
                }
            }
        }

        // 3. Check for any other custom .litertlm file
        for (dir in checkDirs) {
            if (dir.exists() && dir.isDirectory) {
                val litertFiles = dir.listFiles { file ->
                    file.isFile && file.name.endsWith(".litertlm") && file.length() >= GENERIC_MIN_BYTES
                }
                if (!litertFiles.isNullOrEmpty()) {
                    return litertFiles.maxByOrNull { it.lastModified() }
                }
            }
        }

        return null
    }

    fun getPreferredAdbPushPath(option: ModelOption): String {
        val externalModelsDir = context.getExternalFilesDir("models")
        return File(externalModelsDir, option.fileName).absolutePath
    }

    fun refreshStatus() {
        if (downloadJob?.isActive == true) return

        val existing = findExistingModelFile()
        if (existing != null) {
            val isExternal = existing.absolutePath.contains("Android/data")
            val knownOption = ModelOption.fromFileName(existing.name)
            val modelName = knownOption?.displayName ?: existing.nameWithoutExtension

            _statusFlow.value = ModelStatus.Ready(
                file = existing,
                sizeBytes = existing.length(),
                isExternal = isExternal,
                modelName = modelName
            )
        } else {
            val incomplete = findIncompleteModelFile()
            if (incomplete != null) {
                val (file, expected) = incomplete
                val currentMb = file.length() / (1024 * 1024)
                val expectedMb = expected / (1024 * 1024)
                _statusFlow.value = ModelStatus.Error(
                    "Corrupted/incomplete file detected (${file.name}: $currentMb MB of $expectedMb MB). Delete and re-download or push via ADB."
                )
            } else {
                _statusFlow.value = ModelStatus.NotDownloaded
            }
        }
    }

    fun startDownload(targetOption: ModelOption) {
        if (downloadJob?.isActive == true) return

        downloadJob = scope.launch(Dispatchers.IO) {
            val targetFile = File(context.filesDir, targetOption.fileName)
            val tempFile = File(context.filesDir, "${targetOption.fileName}.tmp")

            try {
                _statusFlow.value = ModelStatus.Downloading(
                    modelOption = targetOption,
                    progressPercent = 0,
                    bytesDownloaded = 0,
                    totalBytes = targetOption.expectedSizeBytes
                )

                var currentUrl = targetOption.downloadUrl
                var connection: HttpURLConnection? = null
                var redirectCount = 0

                // Follow CDN redirects
                while (redirectCount < 5) {
                    val url = URL(currentUrl)
                    Log.i(TAG, "Connecting to $currentUrl (redirect #$redirectCount)")
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "FixLocal-Android/1.0")
                    }

                    val responseCode = connection.responseCode
                    Log.i(TAG, "HTTP Response: $responseCode")
                    if (responseCode in 300..399) {
                        val newLocation = connection.getHeaderField("Location")
                            ?: throw IllegalStateException("Redirected with no Location header")
                        connection.disconnect()
                        currentUrl = newLocation
                        redirectCount++
                    } else if (responseCode == HttpURLConnection.HTTP_OK) {
                        break
                    } else {
                        throw IllegalStateException("Server returned HTTP $responseCode")
                    }
                }

                val conn = connection ?: throw IllegalStateException("Failed to open connection")
                val totalLength = if (conn.contentLengthLong > 0) conn.contentLengthLong else targetOption.expectedSizeBytes

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        var lastProgressUpdate = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) {
                                throw CancellationException("Download canceled by user")
                            }
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 250 || totalBytesRead == totalLength) {
                                lastProgressUpdate = now
                                val percent = if (totalLength > 0) {
                                    ((totalBytesRead * 100) / totalLength).toInt()
                                } else 0
                                _statusFlow.value = ModelStatus.Downloading(
                                    modelOption = targetOption,
                                    progressPercent = percent,
                                    bytesDownloaded = totalBytesRead,
                                    totalBytes = totalLength
                                )
                            }
                        }
                        output.flush()
                    }
                }

                val minAcceptable = (targetOption.expectedSizeBytes * 90) / 100
                if (tempFile.length() < minAcceptable) {
                    tempFile.delete()
                    throw IllegalStateException("Downloaded file is incomplete (${tempFile.length()} bytes)")
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }

                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                // Immediately transition to Ready
                val isExternal = targetFile.absolutePath.contains("Android/data")
                _statusFlow.value = ModelStatus.Ready(
                    file = targetFile,
                    sizeBytes = targetFile.length(),
                    isExternal = isExternal,
                    modelName = targetOption.displayName
                )
            } catch (e: CancellationException) {
                tempFile.delete()
                refreshStatus()
            } catch (e: Exception) {
                tempFile.delete()
                _statusFlow.value = ModelStatus.Error(e.localizedMessage ?: "Download failed")
            } finally {
                downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        for (option in ModelOption.entries) {
            val tempFile = File(context.filesDir, "${option.fileName}.tmp")
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
        refreshStatus()
    }

    fun deleteModel(): Boolean {
        cancelDownload()
        var deleted = false
        val checkDirs = getCandidateDirectories()

        for (dir in checkDirs) {
            if (dir.exists()) {
                val files = dir.listFiles { f -> f.name.endsWith(".litertlm") || f.name.endsWith(".tmp") }
                files?.forEach { f ->
                    deleted = f.delete() || deleted
                }
            }
        }

        refreshStatus()
        return deleted
    }

    suspend fun importFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = "imported_model.litertlm"
            val targetFile = File(context.filesDir, fileName)
            val tempFile = File(context.filesDir, "$fileName.tmp")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Cannot open input stream"))

            if (tempFile.length() < GENERIC_MIN_BYTES) {
                tempFile.delete()
                return@withContext Result.failure(IllegalStateException("Imported file is too small to be a valid model"))
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            tempFile.renameTo(targetFile)
            refreshStatus()
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
