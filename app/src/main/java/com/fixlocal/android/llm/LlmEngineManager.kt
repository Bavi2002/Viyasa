package com.fixlocal.android.llm

import android.content.Context
import android.os.Debug
import android.util.Log
import com.fixlocal.android.data.downloader.ModelDownloadManager
import com.fixlocal.android.data.model.FixResult
import com.fixlocal.android.data.model.FixTone
import com.fixlocal.android.data.model.InferenceBackend
import com.fixlocal.android.data.model.ModelOption
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "FixLocal-LLM"

/**
 * Singleton manager owning the LiteRT-LM Engine and Conversation lifecycle.
 * Highly optimized for normal / budget phones (e.g. Samsung Galaxy A-series)
 * by capping maxNumTokens = 256 to drastically minimize KV-cache RAM allocation.
 */
class LlmEngineManager private constructor(private val context: Context) : AutoCloseable {

    companion object {
        @Volatile
        private var instance: LlmEngineManager? = null

        fun getInstance(context: Context): LlmEngineManager {
            return instance ?: synchronized(this) {
                instance ?: LlmEngineManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FixLocal-LlmWorker").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
        }
    }
    private val dispatcher = singleThreadExecutor.asCoroutineDispatcher()

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var loadedBackend: InferenceBackend? = null
    private var isClosed = false

    private val idleScope = CoroutineScope(Dispatchers.Default)
    private var idleJob: Job? = null

    /**
     * Resets the 60-second inactivity auto-sleep timer.
     * When idle for 60 seconds, native engine RAM is unloaded so the phone remains cool and light.
     */
    private fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = idleScope.launch {
            delay(60_000L)
            Log.i(TAG, "Inactivity timeout reached (60s). Unloading LLM engine to free RAM.")
            unloadEngine()
        }
    }

    /**
     * Unloads the LiteRT-LM engine from memory and triggers garbage collection to free native RAM.
     */
    suspend fun unloadEngine() = withContext(dispatcher) {
        mutex.withLock {
            try {
                engine?.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Error closing engine on unload: ${t.message}")
            }
            engine = null
            loadedModelPath = null
            loadedBackend = null
            System.gc()
            Log.i(TAG, "LLM Engine unloaded and RAM freed.")
        }
    }

    /**
     * Called when the app transitions to the background to release native RAM immediately.
     */
    fun onAppBackgrounded() {
        idleScope.launch {
            unloadEngine()
        }
    }

    private fun getApproximateMemoryUsageMb(): Double {
        return try {
            val runtime = Runtime.getRuntime()
            val jvmHeapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
            val nativeHeapAllocatedMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
            jvmHeapUsedMb + nativeHeapAllocatedMb
        } catch (_: Throwable) {
            0.0
        }
    }

    private fun buildSystemPrompt(
        tone: FixTone,
        customInstruction: String = "",
        wordLimitEnabled: Boolean = false,
        wordLimitCount: Int = 25
    ): String {
        val toneInstruction = when (tone) {
            FixTone.NATURAL -> "natural everyday English"
            FixTone.CASUAL -> "casual, friendly messaging English"
            FixTone.PROFESSIONAL -> "formal professional English"
            FixTone.CUSTOM -> if (customInstruction.isNotBlank()) customInstruction.trim() else "natural everyday English"
        }
        val wordLimitSuffix = if (wordLimitEnabled && wordLimitCount > 0) {
            " Output must not exceed $wordLimitCount words."
        } else {
            ""
        }
        return "You are an expert English proofreader. Correct all grammar errors, verb tenses (past time words like 'yesterday' require past tense e.g. went, met), double negatives (e.g. 'don't know nothing' -> 'doesn't know anything'), subject-verb agreement, and spelling. Preserve original meaning and agent roles strictly. Style: $toneInstruction.$wordLimitSuffix Output ONLY the corrected text. Never add quotes, greetings, or conversational commentary."
    }

    private fun buildUserPrompt(rawText: String): String {
        return "Correct the grammar and spelling of this text:\n\"$rawText\""
    }

    private fun ensureEngineLoaded(modelFile: File, backend: InferenceBackend): Engine {
        if (isClosed) {
            throw IllegalStateException("LlmEngineManager has been closed")
        }

        // Hardware Acceleration Guardrail for Gemma 3:
        // LiteRT-LM's OpenCL GPU delegate fails to compute output projection logits for Gemma 3
        // on mobile chipsets, causing uniform/zero logits that trigger infinite <pad> token loops.
        // Gemma 3 is ultra-compact (270M) and executes with low latency (~1.5s) on multi-core CPU (XNNPack).
        val isGemma = modelFile.name.contains("gemma", ignoreCase = true)
        val targetBackend = if (isGemma && backend == InferenceBackend.GPU) {
            Log.w(TAG, "Gemma 3 LiteRT OpenCL delegate unsupported on mobile GPU; automatically routing to high-speed CPU.")
            InferenceBackend.CPU
        } else {
            backend
        }

        val existingEngine = engine
        if (existingEngine != null &&
            existingEngine.isInitialized() &&
            loadedModelPath == modelFile.absolutePath &&
            loadedBackend == targetBackend
        ) {
            return existingEngine
        }

        // Close old engine if backend or model path changed and free memory
        try {
            existingEngine?.close()
        } catch (_: Throwable) {}
        engine = null
        System.gc()

        // Restrict CPU threads to 2-4 so budget octa-core chips are not overwhelmed
        val cpuThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
        val litertBackend: Backend = when (targetBackend) {
            InferenceBackend.CPU -> Backend.CPU(threadCount = cpuThreads)
            InferenceBackend.GPU -> try {
                Backend.GPU()
            } catch (t: Throwable) {
                Log.w(TAG, "Backend.GPU() not available, falling back to CPU", t)
                Backend.CPU(threadCount = cpuThreads)
            }
        }

        // Cap maxNumTokens = 1024. This provides ample KV cache headroom for multi-turn prompts,
        // translations, and summaries while taking only ~50-80 MB RAM for small models.
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = litertBackend,
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = 1024,
            maxNumImages = null,
            cacheDir = context.cacheDir.absolutePath,
            activationDataType = null
        )

        val newEngine = Engine(config)
        try {
            newEngine.initialize()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize engine with backend $targetBackend: ${t.message}", t)
            // If GPU failed (e.g. OpenCL driver issues on budget chipset), fallback to CPU
            if (targetBackend == InferenceBackend.GPU) {
                try { newEngine.close() } catch (_: Throwable) {}
                val fallbackConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(threadCount = cpuThreads),
                    visionBackend = null,
                    audioBackend = null,
                    maxNumTokens = 1024,
                    maxNumImages = null,
                    cacheDir = context.cacheDir.absolutePath,
                    activationDataType = null
                )
                val cpuEngine = Engine(fallbackConfig)
                cpuEngine.initialize()
                engine = cpuEngine
                loadedModelPath = modelFile.absolutePath
                loadedBackend = InferenceBackend.CPU
                return cpuEngine
            } else {
                throw t
            }
        }

        engine = newEngine
        loadedModelPath = modelFile.absolutePath
        loadedBackend = targetBackend

        return newEngine
    }

    /**
     * Warms up or pre-initializes the engine in the background.
     */
    suspend fun warmUp(modelFile: File, backend: InferenceBackend) = withContext(dispatcher) {
        mutex.withLock {
            ensureEngineLoaded(modelFile, backend)
        }
    }

    /**
     * Performs English grammar and phrasing correction on the given text.
     * Creates a fresh Conversation per request with thinking disabled.
     */
    suspend fun fixGrammar(
        rawText: String,
        modelFile: File,
        backend: InferenceBackend,
        tone: FixTone,
        customInstruction: String = "",
        wordLimitEnabled: Boolean = false,
        wordLimitCount: Int = 25
    ): FixResult = withContext(dispatcher) {
        mutex.withLock {
            val trimmedInput = rawText.trim()
            if (trimmedInput.isEmpty()) {
                return@withContext FixResult(
                    originalText = rawText,
                    fixedText = rawText,
                    latencyMs = 0L,
                    memoryUsageMb = getApproximateMemoryUsageMb()
                )
            }

            val startTime = System.currentTimeMillis()
            val loadedEngine = ensureEngineLoaded(modelFile, backend)

            val prompt = buildSystemPrompt(tone, customInstruction, wordLimitEnabled, wordLimitCount)

            val maxTokens = if (wordLimitEnabled && wordLimitCount > 0) {
                (wordLimitCount * 2).coerceIn(32, 256)
            } else {
                128
            }

            // Low temperature (0.1) and topK=20 ensures focused, grammatical output while
            // avoiding token repetition loops on small models.
            val samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.1, seed = 0)

            // Try creating conversation with thinking disabled, fallback without thinkingConfig if model rejects it
            val conversationConfig = try {
                ConversationConfig(
                    systemInstruction = Contents.of(prompt),
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
            } catch (_: Throwable) {
                ConversationConfig(
                    systemInstruction = Contents.of(prompt),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
            }

            val conversation: Conversation = try {
                loadedEngine.createConversation(conversationConfig)
            } catch (t: Throwable) {
                Log.w(TAG, "createConversation failed with thinkingConfig, retrying without thinkingConfig", t)
                val fallbackConfig = ConversationConfig(
                    systemInstruction = Contents.of(prompt),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
                loadedEngine.createConversation(fallbackConfig)
            }

            val rawOutput = StringBuilder()
            try {
                val userPrompt = buildUserPrompt(trimmedInput)
                val directMessage = conversation.sendMessage(userPrompt)
                for (c in directMessage.contents.contents) {
                    if (c is Content.Text) {
                        rawOutput.append(c.text)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "sendMessage failed: ${t.message}", t)
                throw t
            } finally {
                try {
                    conversation.close()
                } catch (_: Throwable) {}
            }

            val latency = System.currentTimeMillis() - startTime
            val sanitized = OutputSanitizer.sanitize(rawOutput.toString(), trimmedInput)
            val finalOutput = if (sanitized.isNotBlank()) sanitized else trimmedInput
            val memoryMb = getApproximateMemoryUsageMb()

            resetIdleTimer()

            Log.i(TAG, "fixGrammar: input='$rawText' | rawOutput='$rawOutput' | final='$finalOutput' | ${latency}ms")

            FixResult(
                originalText = rawText,
                fixedText = finalOutput,
                latencyMs = latency,
                memoryUsageMb = memoryMb
            )
        }
    }

    /**
     * Translates the given text into the target language on-device.
     */
    suspend fun translateText(
        rawText: String,
        targetLanguage: String,
        modelFile: File,
        backend: InferenceBackend
    ): FixResult = withContext(dispatcher) {
        mutex.withLock {
            val trimmedInput = rawText.trim()
            if (trimmedInput.isEmpty()) {
                return@withContext FixResult(rawText, rawText, 0L, getApproximateMemoryUsageMb())
            }

            val startTime = System.currentTimeMillis()
            val effectiveModelFile = modelFile

            val loadedEngine = ensureEngineLoaded(effectiveModelFile, backend)

            val targetLangLower = targetLanguage.trim().lowercase()
            val systemPrompt = when (targetLangLower) {
                "spanish" -> "You are a professional translator. Translate accurately into natural Spanish (Español). Example: 'Good morning' -> 'Buenos días'. Output ONLY the Spanish translation without quotes or chatter."
                "french" -> "You are a professional translator. Translate accurately into natural French (Français). Example: 'Good morning' -> 'Bonjour'. Output ONLY the French translation without quotes or chatter."
                "german" -> "You are a professional translator. Translate accurately into natural German (Deutsch). Example: 'Good morning' -> 'Guten Morgen'. Output ONLY the German translation without quotes or chatter."
                "italian" -> "You are a professional translator. Translate accurately into natural Italian (Italiano). Example: 'Good morning' -> 'Buongiorno'. Output ONLY the Italian translation without quotes or chatter."
                "portuguese" -> "You are a professional translator. Translate accurately into natural Portuguese (Português). Example: 'Good morning' -> 'Bom dia'. Output ONLY the Portuguese translation without quotes or chatter."
                "japanese" -> "You are a professional translator. Translate accurately into natural Japanese (日本語). Example: 'Good morning' -> 'おはようございます'. Output ONLY the Japanese translation without quotes or chatter."
                "chinese" -> "You are a professional translator. Translate accurately into natural Simplified Chinese (中文). Example: 'Good morning' -> '早上好'. Output ONLY the Chinese translation without quotes or chatter."
                "arabic" -> "You are a professional translator. Translate accurately into natural Arabic (العربية). Example: 'Good morning' -> 'صباح الخير'. Output ONLY the Arabic translation without quotes or chatter."
                else -> "You are a professional translator. Translate the text into $targetLanguage accurately, preserving original tone and meaning. Output ONLY the translation without quotes or chatter."
            }
            val userPrompt = "Translate the following text:\n\"$trimmedInput\"\n\nTranslation:"

            val samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.0, seed = 42)
            val maxTokens = 192

            val conversationConfig = try {
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
            } catch (_: Throwable) {
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
            }

            val conversation: Conversation = try {
                loadedEngine.createConversation(conversationConfig)
            } catch (t: Throwable) {
                Log.w(TAG, "createConversation failed with thinkingConfig in translateText, retrying without thinkingConfig", t)
                val fallbackConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
                loadedEngine.createConversation(fallbackConfig)
            }

            val rawOutput = StringBuilder()
            try {
                val directMessage = conversation.sendMessage(userPrompt)
                for (c in directMessage.contents.contents) {
                    if (c is Content.Text) rawOutput.append(c.text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "sendMessage failed in translateText: ${t.message}", t)
                throw t
            } finally {
                try { conversation.close() } catch (_: Throwable) {}
            }

            val latency = System.currentTimeMillis() - startTime
            var cleanText = OutputSanitizer.stripSpecialTokens(rawOutput.toString())
            cleanText = OutputSanitizer.unwrapJsonIfPresent(cleanText).trim()
            cleanText = OutputSanitizer.removeRepetitiveLoops(cleanText)
            // Clean common prefixes small models might generate
            cleanText = cleanText.replace(Regex("^(Translation|Here is the translation|Translated text|In $targetLanguage):\\s*", RegexOption.IGNORE_CASE), "")
            if (cleanText.startsWith("\"") && cleanText.endsWith("\"") && cleanText.length > 2) {
                cleanText = cleanText.substring(1, cleanText.length - 1).trim()
            }
            val finalOutput = if (cleanText.isNotBlank()) cleanText else trimmedInput

            resetIdleTimer()

            Log.i(TAG, "translateText: input='$trimmedInput' | target='$targetLanguage' | rawOutput='$rawOutput' | final='$finalOutput' | ${latency}ms")

            FixResult(
                originalText = rawText,
                fixedText = finalOutput,
                latencyMs = latency,
                memoryUsageMb = getApproximateMemoryUsageMb()
            )
        }
    }

    /**
     * Summarizes the text into bullet points or a single concise takeaway.
     */
    suspend fun summarizeText(
        rawText: String,
        format: String,
        modelFile: File,
        backend: InferenceBackend
    ): FixResult = withContext(dispatcher) {
        mutex.withLock {
            val trimmedInput = rawText.trim()
            if (trimmedInput.isEmpty()) {
                return@withContext FixResult(rawText, rawText, 0L, getApproximateMemoryUsageMb())
            }

            val startTime = System.currentTimeMillis()
            val loadedEngine = ensureEngineLoaded(modelFile, backend)

            val isBullets = format.contains("Bullet", ignoreCase = true)
            val systemPrompt = if (isBullets) {
                "You are an executive summarizer. Condense the text into 2 concise bullet points. Each bullet must start with '• '. Output ONLY the bullet points without introductions or conclusions."
            } else {
                "You are an executive summarizer. Condense the essence of the text into exactly one concise sentence. Output ONLY the single summary sentence without quotes or preambles."
            }
            val userPrompt = if (isBullets) {
                "Provide 2 brief bullet points summarizing this text:\n$trimmedInput"
            } else {
                "Summarize this text into one sentence:\n$trimmedInput"
            }

            val samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.1, seed = 0)
            val maxTokens = if (isBullets) 80 else 64

            val conversationConfig = try {
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
            } catch (_: Throwable) {
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
            }

            val conversation: Conversation = try {
                loadedEngine.createConversation(conversationConfig)
            } catch (t: Throwable) {
                Log.w(TAG, "createConversation failed with thinkingConfig in summarizeText, retrying without thinkingConfig", t)
                val fallbackConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
                loadedEngine.createConversation(fallbackConfig)
            }

            val rawOutput = StringBuilder()
            try {
                val directMessage = conversation.sendMessage(userPrompt)
                for (c in directMessage.contents.contents) {
                    if (c is Content.Text) rawOutput.append(c.text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "sendMessage failed in summarizeText: ${t.message}", t)
                throw t
            } finally {
                try { conversation.close() } catch (_: Throwable) {}
            }

            val latency = System.currentTimeMillis() - startTime
            var cleanText = OutputSanitizer.stripSpecialTokens(rawOutput.toString())
            cleanText = OutputSanitizer.unwrapJsonIfPresent(cleanText).trim()
            cleanText = cleanText.replace(Regex("^(Summary|Key Takeaways|Here is a summary):\\s*", RegexOption.IGNORE_CASE), "")
            if (cleanText.startsWith("\"") && cleanText.endsWith("\"") && cleanText.length > 2) {
                cleanText = cleanText.substring(1, cleanText.length - 1).trim()
            }
            val finalOutput = if (cleanText.isNotBlank()) cleanText else trimmedInput

            resetIdleTimer()

            Log.i(TAG, "summarizeText: input='$trimmedInput' | format='$format' | rawOutput='$rawOutput' | final='$finalOutput' | ${latency}ms")

            FixResult(
                originalText = rawText,
                fixedText = finalOutput,
                latencyMs = latency,
                memoryUsageMb = getApproximateMemoryUsageMb()
            )
        }
    }

    /**
     * Continues writing an unfinished sentence or draft naturally in the same voice and style.
     */
    suspend fun continueWriting(
        rawText: String,
        modelFile: File,
        backend: InferenceBackend
    ): FixResult = withContext(dispatcher) {
        mutex.withLock {
            val trimmedInput = rawText.trim()
            if (trimmedInput.isEmpty()) {
                return@withContext FixResult(rawText, rawText, 0L, getApproximateMemoryUsageMb())
            }

            val startTime = System.currentTimeMillis()
            val loadedEngine = ensureEngineLoaded(modelFile, backend)

            val systemPrompt = "You are an intelligent co-writer. Continue and complete the user's unfinished thought naturally in the same voice and style. Output ONLY the continuation text that immediately follows. Do not repeat the original text."
            val userPrompt = "Continue this thought naturally:\n$trimmedInput"

            val samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2, seed = 0)
            val maxTokens = 128

            val conversationConfig = try {
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
            } catch (_: Throwable) {
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
            }

            val conversation: Conversation = try {
                loadedEngine.createConversation(conversationConfig)
            } catch (t: Throwable) {
                Log.w(TAG, "createConversation failed with thinkingConfig in continueWriting, retrying without thinkingConfig", t)
                val fallbackConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = samplerConfig,
                    maxOutputToken = maxTokens
                )
                loadedEngine.createConversation(fallbackConfig)
            }

            val rawOutput = StringBuilder()
            try {
                val directMessage = conversation.sendMessage(userPrompt)
                for (c in directMessage.contents.contents) {
                    if (c is Content.Text) rawOutput.append(c.text)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "sendMessage failed in continueWriting: ${t.message}", t)
                throw t
            } finally {
                try { conversation.close() } catch (_: Throwable) {}
            }

            val latency = System.currentTimeMillis() - startTime
            var continuation = OutputSanitizer.stripSpecialTokens(rawOutput.toString())
            continuation = OutputSanitizer.unwrapJsonIfPresent(continuation).trim()
            continuation = continuation.replace(Regex("^(Continuation|Here is the continuation):\\s*", RegexOption.IGNORE_CASE), "")
            if (continuation.startsWith("\"") && continuation.endsWith("\"") && continuation.length > 2) {
                continuation = continuation.substring(1, continuation.length - 1).trim()
            }
            // Strip prompt if model echoed full input
            if (continuation.startsWith(trimmedInput, ignoreCase = true)) {
                continuation = continuation.substring(trimmedInput.length).trim()
            } else {
                // Strip trailing clause if model echoed the last clause/sentence
                val clauses = trimmedInput.split(Regex("[,.!?]\\s*")).filter { it.isNotBlank() }
                val lastClause = clauses.lastOrNull()?.trim() ?: ""
                if (lastClause.length > 6 && continuation.startsWith(lastClause, ignoreCase = true) && continuation.length > lastClause.length + 3) {
                    continuation = continuation.substring(lastClause.length).trim()
                }
            }
            // Seamless join
            val combined = if (continuation.isNotBlank()) {
                if (trimmedInput.endsWith(" ") || continuation.startsWith(" ") || continuation.startsWith(".") || continuation.startsWith(",")) {
                    "$trimmedInput$continuation"
                } else {
                    "$trimmedInput $continuation"
                }
            } else {
                trimmedInput
            }

            resetIdleTimer()

            Log.i(TAG, "continueWriting: input='$trimmedInput' | rawOutput='$rawOutput' | continuation='$continuation' | final='$combined' | ${latency}ms")

            FixResult(
                originalText = rawText,
                fixedText = combined,
                latencyMs = latency,
                memoryUsageMb = getApproximateMemoryUsageMb()
            )
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        idleJob?.cancel()
        idleScope.cancel()
        try {
            engine?.close()
        } catch (_: Throwable) {}
        engine = null
        singleThreadExecutor.shutdown()
        System.gc()
    }
}
