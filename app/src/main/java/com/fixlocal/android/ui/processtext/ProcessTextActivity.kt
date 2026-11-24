package com.fixlocal.android.ui.processtext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.fixlocal.android.R
import com.fixlocal.android.data.downloader.ModelDownloadManager
import com.fixlocal.android.data.model.AssistantTask
import com.fixlocal.android.data.model.FixResult
import com.fixlocal.android.data.model.FixTone
import com.fixlocal.android.data.preferences.PreferencesManager
import com.fixlocal.android.llm.LlmEngineManager
import com.fixlocal.android.ui.MainActivity
import com.fixlocal.android.ui.theme.FixLocalTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

sealed interface ProcessTextUiState {
    data object CheckingModel : ProcessTextUiState
    data object ModelNotDownloaded : ProcessTextUiState
    data class ImmediateFixing(val cancelJob: () -> Unit) : ProcessTextUiState
    data class ReadOnlyResult(val fixedText: String, val originalText: String) : ProcessTextUiState
    data class DockMode(
        val originalText: String,
        val isReadOnly: Boolean,
        val modelFile: File
    ) : ProcessTextUiState
    data class Error(val message: String) : ProcessTextUiState
}

class ProcessTextActivity : ComponentActivity() {

    private var inferenceJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.CENTER)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        val rawInput = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
        val isReadOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val isDockMode = intent.component?.className?.contains("VyasaDockActivity") == true

        val downloadManager = ModelDownloadManager(this, lifecycleScope)
        val preferencesManager = PreferencesManager(this)
        val llmEngineManager = LlmEngineManager.getInstance(this)

        setContent {
            FixLocalTheme {
                var uiState by remember { mutableStateOf<ProcessTextUiState>(ProcessTextUiState.CheckingModel) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    val preferredModel = preferencesManager.selectedModelFlow.first()
                    val modelFile = downloadManager.findExistingModelFile(preferredModel)
                    if (modelFile == null) {
                        uiState = ProcessTextUiState.ModelNotDownloaded
                        return@LaunchedEffect
                    }

                    if (rawInput.isBlank()) {
                        finish()
                        return@LaunchedEffect
                    }

                    if (isDockMode) {
                        // User chose "Vyasa" -> open the interactive tool dock
                        uiState = ProcessTextUiState.DockMode(
                            originalText = rawInput,
                            isReadOnly = isReadOnly,
                            modelFile = modelFile
                        )
                    } else {
                        // User chose "Rewrite" -> execute immediately in-place
                        val tone = preferencesManager.selectedToneFlow.first()
                        val backend = preferencesManager.selectedBackendFlow.first()
                        val customInstruction = preferencesManager.customInstructionFlow.first()
                        val wordLimitEnabled = preferencesManager.wordLimitEnabledFlow.first()
                        val wordLimitCount = preferencesManager.wordLimitCountFlow.first()

                        uiState = ProcessTextUiState.ImmediateFixing {
                            inferenceJob?.cancel()
                            finish()
                        }

                        inferenceJob = scope.launch {
                            try {
                                val result = llmEngineManager.fixGrammar(
                                    rawText = rawInput,
                                    modelFile = modelFile,
                                    backend = backend,
                                    tone = tone,
                                    customInstruction = customInstruction,
                                    wordLimitEnabled = wordLimitEnabled,
                                    wordLimitCount = wordLimitCount
                                )

                                if (isReadOnly) {
                                    uiState = ProcessTextUiState.ReadOnlyResult(
                                        fixedText = result.fixedText,
                                        originalText = rawInput
                                    )
                                } else {
                                    // Copy to clipboard as universal fallback for apps (like Gmail RichText/WebView) that drop ACTION_PROCESS_TEXT
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Viyasa Output", result.fixedText))

                                    val returnIntent = Intent().apply {
                                        putExtra(Intent.EXTRA_PROCESS_TEXT, result.fixedText)
                                    }
                                    setResult(RESULT_OK, returnIntent)
                                    Toast.makeText(
                                        this@ProcessTextActivity,
                                        "✨ Rewritten",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    finish()
                                }
                            } catch (_: CancellationException) {
                                finish()
                            } catch (t: Throwable) {
                                uiState = ProcessTextUiState.Error(
                                    t.localizedMessage ?: "Rewrite failed: ${t::class.simpleName}"
                                )
                            }
                        }
                    }
                }

                ProcessTextScreen(
                    uiState = uiState,
                    preferencesManager = preferencesManager,
                    llmEngineManager = llmEngineManager,
                    onDismiss = { finish() },
                    onOpenApp = {
                        val appIntent = Intent(this@ProcessTextActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        }
                        startActivity(appIntent)
                        finish()
                    },
                    onReplace = { textToReplace ->
                        // Copy to clipboard as universal fallback for apps (like Gmail RichText/WebView) that drop ACTION_PROCESS_TEXT
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Viyasa Output", textToReplace))

                        val returnIntent = Intent().apply {
                            putExtra(Intent.EXTRA_PROCESS_TEXT, textToReplace)
                        }
                        setResult(RESULT_OK, returnIntent)
                        Toast.makeText(this@ProcessTextActivity, "✨ Replaced", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onCopy = { textToCopy ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Viyasa Output", textToCopy))
                        Toast.makeText(this@ProcessTextActivity, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inferenceJob?.cancel()
    }
}

@Composable
private fun ProcessTextScreen(
    uiState: ProcessTextUiState,
    preferencesManager: PreferencesManager,
    llmEngineManager: LlmEngineManager,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
    onReplace: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (uiState !is ProcessTextUiState.ImmediateFixing && uiState !is ProcessTextUiState.CheckingModel) {
                    onDismiss()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when (uiState) {
            ProcessTextUiState.CheckingModel -> {}

            is ProcessTextUiState.ImmediateFixing -> {
                FloatingLoadingPill(onCancel = uiState.cancelJob)
            }

            ProcessTextUiState.ModelNotDownloaded -> {
                ModelNotDownloadedSheet(
                    onOpenApp = onOpenApp,
                    onDismiss = onDismiss
                )
            }

            is ProcessTextUiState.ReadOnlyResult -> {
                ReadOnlyResultSheet(
                    fixedText = uiState.fixedText,
                    onCopy = { onCopy(uiState.fixedText) },
                    onDismiss = onDismiss
                )
            }

            is ProcessTextUiState.DockMode -> {
                VyasaFloatingDock(
                    originalText = uiState.originalText,
                    isReadOnly = uiState.isReadOnly,
                    modelFile = uiState.modelFile,
                    preferencesManager = preferencesManager,
                    llmEngineManager = llmEngineManager,
                    onReplace = onReplace,
                    onCopy = onCopy,
                    onDismiss = onDismiss
                )
            }

            is ProcessTextUiState.Error -> {
                ErrorDialog(
                    message = uiState.message,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VyasaFloatingDock(
    originalText: String,
    isReadOnly: Boolean,
    modelFile: File,
    preferencesManager: PreferencesManager,
    llmEngineManager: LlmEngineManager,
    onReplace: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentTask by remember { mutableStateOf(AssistantTask.REWRITE) }
    var selectedTone by remember { mutableStateOf(FixTone.NATURAL) }
    var translateTarget by remember { mutableStateOf("English") }
    var summarizeFormat by remember { mutableStateOf("Bullets") }

    var isRunning by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var latencyMs by remember { mutableStateOf(0L) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var isTranslateEnabled by remember { mutableStateOf(true) }
    var isSummarizeEnabled by remember { mutableStateOf(true) }
    var isContinueEnabled by remember { mutableStateOf(true) }

    fun runTask(task: AssistantTask) {
        currentTask = task
        isRunning = true
        errorMessage = null
        scope.launch {
            try {
                val backend = preferencesManager.selectedBackendFlow.first()
                val fixResult: FixResult = when (task) {
                    AssistantTask.REWRITE -> {
                        val customInst = preferencesManager.customInstructionFlow.first()
                        val wordLimitEn = preferencesManager.wordLimitEnabledFlow.first()
                        val wordLimitCt = preferencesManager.wordLimitCountFlow.first()
                        llmEngineManager.fixGrammar(
                            rawText = originalText,
                            modelFile = modelFile,
                            backend = backend,
                            tone = selectedTone,
                            customInstruction = customInst,
                            wordLimitEnabled = wordLimitEn,
                            wordLimitCount = wordLimitCt
                        )
                    }
                    AssistantTask.TRANSLATE -> {
                        llmEngineManager.translateText(
                            rawText = originalText,
                            targetLanguage = translateTarget,
                            modelFile = modelFile,
                            backend = backend
                        )
                    }
                    AssistantTask.SUMMARIZE -> {
                        llmEngineManager.summarizeText(
                            rawText = originalText,
                            format = summarizeFormat,
                            modelFile = modelFile,
                            backend = backend
                        )
                    }
                    AssistantTask.CONTINUE -> {
                        llmEngineManager.continueWriting(
                            rawText = originalText,
                            modelFile = modelFile,
                            backend = backend
                        )
                    }
                }
                resultText = fixResult.fixedText
                latencyMs = fixResult.latencyMs
            } catch (t: Throwable) {
                errorMessage = t.localizedMessage ?: "Processing error"
            } finally {
                isRunning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        isTranslateEnabled = preferencesManager.isTranslateEnabledFlow.first()
        isSummarizeEnabled = preferencesManager.isSummarizeEnabledFlow.first()
        isContinueEnabled = preferencesManager.isContinueWritingEnabledFlow.first()
        translateTarget = preferencesManager.defaultTranslateTargetFlow.first()
        summarizeFormat = preferencesManager.defaultSummarizeFormatFlow.first()
        selectedTone = preferencesManager.selectedToneFlow.first()
        // Auto-run Rewrite as initial default
        runTask(AssistantTask.REWRITE)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .navigationBarsPadding(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 16.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_viyasa_logo),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Text(
                        text = "Viyasa Assistant",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 1.dp
            )

            // Task Selector Chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistantTask.entries.forEach { task ->
                    val isAvailable = when (task) {
                        AssistantTask.REWRITE -> true
                        AssistantTask.TRANSLATE -> isTranslateEnabled
                        AssistantTask.SUMMARIZE -> isSummarizeEnabled
                        AssistantTask.CONTINUE -> isContinueEnabled
                    }
                    if (isAvailable) {
                        val isSelected = currentTask == task
                        Surface(
                            onClick = { runTask(task) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = task.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = task.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Task Context Controls
            when (currentTask) {
                AssistantTask.REWRITE -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(FixTone.NATURAL, FixTone.CASUAL, FixTone.PROFESSIONAL).forEach { tone ->
                            val isToneSelected = selectedTone == tone
                            Surface(
                                onClick = {
                                    selectedTone = tone
                                    runTask(AssistantTask.REWRITE)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isToneSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                contentColor = if (isToneSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text(
                                    text = tone.name.lowercase().replaceFirstChar { it.uppercase() },
                                    fontSize = 11.sp,
                                    fontWeight = if (isToneSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
                AssistantTask.TRANSLATE -> {
                    Text(
                        text = "Translates to $translateTarget (configured in Settings)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AssistantTask.SUMMARIZE -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("Bullets", "One-Liner").forEach { fmt ->
                            val isFmtSelected = summarizeFormat == fmt
                            Surface(
                                onClick = {
                                    summarizeFormat = fmt
                                    runTask(AssistantTask.SUMMARIZE)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isFmtSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                contentColor = if (isFmtSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text(
                                    text = fmt,
                                    fontSize = 11.sp,
                                    fontWeight = if (isFmtSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
                AssistantTask.CONTINUE -> {
                    Text(
                        text = "Continues and completes the thought naturally in the same style.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Output Card or Progress
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    contentAlignment = if (isRunning) Alignment.Center else Alignment.TopStart
                ) {
                    if (isRunning) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Processing on device...",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else if (errorMessage != null) {
                        Text(
                            text = errorMessage ?: "",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (resultText.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = resultText,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (latencyMs > 0) {
                                Text(
                                    text = "${latencyMs}ms • On-Device AI",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Text(
                            text = originalText,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Action Buttons
            if (!isRunning && resultText.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isReadOnly) {
                        Button(
                            onClick = { onReplace(resultText) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Replace",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { onCopy(resultText) },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Copy",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingLoadingPill(onCancel: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 8.dp,
        modifier = Modifier
            .wrapContentSize()
            .padding(24.dp)
            .shadow(12.dp, CircleShape)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Rewriting...",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.width(4.dp))

            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text(
                    text = "Cancel",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun ModelNotDownloadedSheet(
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Model Not Downloaded",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Viyasa needs an on-device AI model (e.g. Gemma 3 270M or Qwen 0.6B) to rewrite text offline. Open the app to download it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = onOpenApp,
                        modifier = Modifier.weight(1.5f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Viyasa")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyResultSheet(
    fixedText: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Rewritten Text",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Source is read-only. Ready to copy or paste into your reply.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = fixedText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onCopy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy to Clipboard")
                }
            }
        }
    }
}

@Composable
private fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 12.dp,
        modifier = Modifier.padding(32.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(36.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Inference Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Dismiss")
            }
        }
    }
}
