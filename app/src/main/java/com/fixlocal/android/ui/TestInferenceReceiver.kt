package com.fixlocal.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fixlocal.android.data.downloader.ModelDownloadManager
import com.fixlocal.android.data.model.AssistantTask
import com.fixlocal.android.data.model.FixTone
import com.fixlocal.android.data.model.InferenceBackend
import com.fixlocal.android.data.model.ModelOption
import com.fixlocal.android.llm.LlmEngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TestInferenceReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "Vyasa-TestRunner"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskName = intent.getStringExtra("task") ?: "REWRITE"
        val input = intent.getStringExtra("input") ?: ""
        val param = intent.getStringExtra("param") ?: ""
        val backendStr = intent.getStringExtra("backend") ?: "CPU"
        val backend = if (backendStr.equals("GPU", ignoreCase = true)) InferenceBackend.GPU else InferenceBackend.CPU
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val downloadManager = ModelDownloadManager(context, CoroutineScope(Dispatchers.IO))

                if (taskName.equals("DOWNLOAD", ignoreCase = true)) {
                    val option = if (param.contains("GEMMA", ignoreCase = true)) ModelOption.GEMMA3_270M else ModelOption.QWEN3_0_6B_Q4
                    Log.i(TAG, "STARTING_DOWNLOAD option=${option.displayName} url=${option.downloadUrl}")
                    downloadManager.startDownload(option)
                    downloadManager.statusFlow.collect { status ->
                        Log.i(TAG, "DOWNLOAD_STATUS: $status")
                        if (status is com.fixlocal.android.data.model.ModelStatus.Ready || status is com.fixlocal.android.data.model.ModelStatus.Error) {
                            pendingResult.finish()
                        }
                    }
                    return@launch
                }

                val modelStr = intent.getStringExtra("model") ?: ""
                val preferredOption = when {
                    modelStr.contains("QWEN", ignoreCase = true) -> ModelOption.QWEN3_0_6B_Q4
                    modelStr.contains("GEMMA", ignoreCase = true) -> ModelOption.GEMMA3_270M
                    else -> null
                }
                val modelFile = downloadManager.findExistingModelFile(preferredOption)
                if (modelFile == null) {
                    Log.e(TAG, "TEST_FAILED: Model file not found")
                    pendingResult.finish()
                    return@launch
                }

                val engine = LlmEngineManager.getInstance(context)
                val task = try {
                    AssistantTask.valueOf(taskName.uppercase())
                } catch (_: Throwable) {
                    AssistantTask.REWRITE
                }

                Log.i(TAG, "RUNNING_TEST task=$task backend=$backend model=${modelFile.name} input='$input' param='$param'")
                val result = when (task) {
                    AssistantTask.REWRITE -> {
                        val tone = try { FixTone.valueOf(param.uppercase()) } catch (_: Throwable) { FixTone.NATURAL }
                        engine.fixGrammar(input, modelFile, backend, tone)
                    }
                    AssistantTask.TRANSLATE -> {
                        val targetLang = if (param.isNotBlank()) param else "English"
                        engine.translateText(input, targetLang, modelFile, backend)
                    }
                    AssistantTask.SUMMARIZE -> {
                        val format = if (param.isNotBlank()) param else "Bullets"
                        engine.summarizeText(input, format, modelFile, backend)
                    }
                    AssistantTask.CONTINUE -> {
                        engine.continueWriting(input, modelFile, backend)
                    }
                }

                val encodedOutput = result.fixedText.replace("\r", "").replace("\n", "\\n")
                Log.i(TAG, "TEST_RESULT: task=$task | backend=$backend | input='$input' | output='$encodedOutput' | latency=${result.latencyMs}ms")
            } catch (t: Throwable) {
                Log.e(TAG, "TEST_ERROR: task=$taskName | error=${t.message}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
