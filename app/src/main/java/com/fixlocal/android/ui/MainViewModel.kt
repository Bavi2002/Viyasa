package com.fixlocal.android.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fixlocal.android.data.device.DeviceCapabilityManager
import com.fixlocal.android.data.device.DeviceSpecs
import com.fixlocal.android.data.downloader.ModelDownloadManager
import com.fixlocal.android.data.model.AssistantTask
import com.fixlocal.android.data.model.FixResult
import com.fixlocal.android.data.model.FixTone
import com.fixlocal.android.data.model.InferenceBackend
import com.fixlocal.android.data.model.ModelOption
import com.fixlocal.android.data.model.ModelStatus
import com.fixlocal.android.data.preferences.PreferencesManager
import com.fixlocal.android.llm.LlmEngineManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaygroundState(
    val inputText: String = "yesterday i go to shop and buyed milk",
    val isRunning: Boolean = false,
    val selectedTask: AssistantTask = AssistantTask.REWRITE,
    val translateTarget: String = "English",
    val summarizeFormat: String = "Bullets",
    val result: FixResult? = null,
    val errorMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)
    private val downloadManager = ModelDownloadManager(application, viewModelScope)
    private val llmEngineManager = LlmEngineManager.getInstance(application)
    private val deviceCapabilityManager = DeviceCapabilityManager(application)

    val deviceSpecs: DeviceSpecs = deviceCapabilityManager.getDeviceSpecs()

    val modelStatus: StateFlow<ModelStatus> = downloadManager.statusFlow

    val selectedTone: StateFlow<FixTone> = preferencesManager.selectedToneFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FixTone.NATURAL)

    val customInstruction: StateFlow<String> = preferencesManager.customInstructionFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Fix grammar and spelling while keeping it natural and concise.")

    val wordLimitEnabled: StateFlow<Boolean> = preferencesManager.wordLimitEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val wordLimitCount: StateFlow<Int> = preferencesManager.wordLimitCountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25)

    val selectedBackend: StateFlow<InferenceBackend> = preferencesManager.selectedBackendFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InferenceBackend.CPU)

    val isTranslateEnabled: StateFlow<Boolean> = preferencesManager.isTranslateEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isSummarizeEnabled: StateFlow<Boolean> = preferencesManager.isSummarizeEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val isContinueWritingEnabled: StateFlow<Boolean> = preferencesManager.isContinueWritingEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val defaultTranslateTarget: StateFlow<String> = preferencesManager.defaultTranslateTargetFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "English")

    val defaultSummarizeFormat: StateFlow<String> = preferencesManager.defaultSummarizeFormatFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Bullets")

    private val _selectedModelOption = MutableStateFlow(deviceSpecs.recommendedModel)
    val selectedModelOption: StateFlow<ModelOption> = _selectedModelOption.asStateFlow()

    private val _playgroundState = MutableStateFlow(PlaygroundState())
    val playgroundState: StateFlow<PlaygroundState> = _playgroundState.asStateFlow()

    private var fixJob: Job? = null

    init {
        // Enforce GPU guardrail: if device does not support GPU acceleration, ensure backend is CPU
        if (!deviceSpecs.isGpuSupported) {
            viewModelScope.launch {
                preferencesManager.setBackend(InferenceBackend.CPU)
            }
        }

        // Synchronize selected model from preferences
        viewModelScope.launch {
            preferencesManager.selectedModelFlow.collect { option ->
                _selectedModelOption.value = option
            }
        }
        viewModelScope.launch {
            preferencesManager.defaultTranslateTargetFlow.collect { target ->
                _playgroundState.value = _playgroundState.value.copy(translateTarget = target)
            }
        }
        viewModelScope.launch {
            preferencesManager.defaultSummarizeFormatFlow.collect { format ->
                _playgroundState.value = _playgroundState.value.copy(summarizeFormat = format)
            }
        }
    }

    fun isModelCapable(option: ModelOption): Pair<Boolean, String?> {
        return deviceCapabilityManager.checkModelCapability(option, deviceSpecs)
    }

    fun isModelDownloaded(option: ModelOption): Boolean {
        return downloadManager.isModelDownloaded(option)
    }

    fun selectModelOption(option: ModelOption) {
        val (capable, _) = isModelCapable(option)
        if (capable) {
            _selectedModelOption.value = option
            viewModelScope.launch {
                preferencesManager.setSelectedModel(option)
                if (option == ModelOption.GEMMA3_270M) {
                    preferencesManager.setBackend(InferenceBackend.CPU)
                }
                downloadManager.refreshStatus(option)
            }
        }
    }

    fun onInputTextChanged(newText: String) {
        _playgroundState.value = _playgroundState.value.copy(
            inputText = newText,
            errorMessage = null
        )
    }

    fun setTone(tone: FixTone) {
        viewModelScope.launch {
            preferencesManager.setTone(tone)
        }
    }

    fun setCustomInstruction(instruction: String) {
        viewModelScope.launch {
            preferencesManager.setCustomInstruction(instruction)
        }
    }

    fun setWordLimit(enabled: Boolean, limit: Int) {
        viewModelScope.launch {
            preferencesManager.setWordLimit(enabled, limit)
        }
    }

    fun setBackend(backend: InferenceBackend) {
        if (backend == InferenceBackend.GPU && !deviceSpecs.isGpuSupported) {
            return
        }
        viewModelScope.launch {
            preferencesManager.setBackend(backend)
        }
    }

    fun startDownload(option: ModelOption = _selectedModelOption.value) {
        downloadManager.startDownload(option)
    }

    fun cancelDownload() {
        downloadManager.cancelDownload()
    }

    fun deleteModel(option: ModelOption = _selectedModelOption.value) {
        downloadManager.deleteModel(option)
    }

    fun refreshModelStatus() {
        downloadManager.refreshStatus(_selectedModelOption.value)
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            val result = downloadManager.importFromUri(uri)
            if (result.isFailure) {
                _playgroundState.value = _playgroundState.value.copy(
                    errorMessage = "Import failed: ${result.exceptionOrNull()?.localizedMessage}"
                )
            }
        }
    }

    fun setTranslateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setTranslateEnabled(enabled)
        }
    }

    fun setSummarizeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setSummarizeEnabled(enabled)
        }
    }

    fun setContinueWritingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setContinueWritingEnabled(enabled)
        }
    }

    fun setDefaultTranslateTarget(target: String) {
        viewModelScope.launch {
            preferencesManager.setDefaultTranslateTarget(target)
        }
    }

    fun setDefaultSummarizeFormat(format: String) {
        viewModelScope.launch {
            preferencesManager.setDefaultSummarizeFormat(format)
        }
    }

    fun setPlaygroundTask(task: AssistantTask) {
        _playgroundState.value = _playgroundState.value.copy(
            selectedTask = task,
            errorMessage = null
        )
    }

    fun setPlaygroundTranslateTarget(target: String) {
        _playgroundState.value = _playgroundState.value.copy(translateTarget = target)
    }

    fun setPlaygroundSummarizeFormat(format: String) {
        _playgroundState.value = _playgroundState.value.copy(summarizeFormat = format)
    }

    fun testFix() {
        val currentInput = _playgroundState.value.inputText.trim()
        if (currentInput.isEmpty()) {
            _playgroundState.value = _playgroundState.value.copy(
                errorMessage = "Please enter text to test"
            )
            return
        }

        val modelFile = downloadManager.findExistingModelFile(_selectedModelOption.value)
        if (modelFile == null) {
            _playgroundState.value = _playgroundState.value.copy(
                errorMessage = "Model not found. Download or push a model first."
            )
            return
        }

        fixJob?.cancel()
        fixJob = viewModelScope.launch {
            _playgroundState.value = _playgroundState.value.copy(
                isRunning = true,
                errorMessage = null
            )

            try {
                val currentBackend = selectedBackend.value
                val state = _playgroundState.value

                val result = when (state.selectedTask) {
                    AssistantTask.REWRITE -> {
                        val currentTone = selectedTone.value
                        val customInst = customInstruction.value
                        val wordLimitEn = wordLimitEnabled.value
                        val wordLimitCt = wordLimitCount.value
                        llmEngineManager.fixGrammar(
                            rawText = currentInput,
                            modelFile = modelFile,
                            backend = currentBackend,
                            tone = currentTone,
                            customInstruction = customInst,
                            wordLimitEnabled = wordLimitEn,
                            wordLimitCount = wordLimitCt
                        )
                    }
                    AssistantTask.TRANSLATE -> {
                        llmEngineManager.translateText(
                            rawText = currentInput,
                            targetLanguage = state.translateTarget,
                            modelFile = modelFile,
                            backend = currentBackend
                        )
                    }
                    AssistantTask.SUMMARIZE -> {
                        llmEngineManager.summarizeText(
                            rawText = currentInput,
                            format = state.summarizeFormat,
                            modelFile = modelFile,
                            backend = currentBackend
                        )
                    }
                    AssistantTask.CONTINUE -> {
                        llmEngineManager.continueWriting(
                            rawText = currentInput,
                            modelFile = modelFile,
                            backend = currentBackend
                        )
                    }
                }

                _playgroundState.value = _playgroundState.value.copy(
                    isRunning = false,
                    result = result,
                    errorMessage = null
                )
            } catch (e: Throwable) {
                _playgroundState.value = _playgroundState.value.copy(
                    isRunning = false,
                    errorMessage = e.localizedMessage ?: "Inference error occurred: ${e::class.simpleName}"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        fixJob?.cancel()
        downloadManager.stopDirectoryObservers()
    }
}
