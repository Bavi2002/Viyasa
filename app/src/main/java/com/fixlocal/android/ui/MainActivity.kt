package com.fixlocal.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fixlocal.android.data.model.ModelStatus
import com.fixlocal.android.llm.LlmEngineManager
import com.fixlocal.android.ui.components.FeaturesCard
import com.fixlocal.android.ui.components.ModelStatusCard
import com.fixlocal.android.ui.components.PlaygroundCard
import com.fixlocal.android.ui.components.SettingsAndGuideCard
import com.fixlocal.android.ui.theme.FixLocalTheme
import java.util.Locale

enum class MainNavTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    PLAYGROUND("Playground", Icons.Outlined.EditNote, Icons.Filled.EditNote),
    FEATURES("Features", Icons.Outlined.Widgets, Icons.Filled.Widgets),
    SETTINGS("Settings", Icons.Outlined.Tune, Icons.Filled.Tune)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FixLocalTheme {
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.refreshModelStatus()
                        } else if (event == Lifecycle.Event.ON_STOP) {
                            LlmEngineManager.getInstance(applicationContext).onAppBackgrounded()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                val modelStatus by viewModel.modelStatus.collectAsState()
                val selectedModelOption by viewModel.selectedModelOption.collectAsState()
                val selectedTone by viewModel.selectedTone.collectAsState()
                val customInstruction by viewModel.customInstruction.collectAsState()
                val wordLimitEnabled by viewModel.wordLimitEnabled.collectAsState()
                val wordLimitCount by viewModel.wordLimitCount.collectAsState()
                val selectedBackend by viewModel.selectedBackend.collectAsState()
                val isTranslateEnabled by viewModel.isTranslateEnabled.collectAsState()
                val isSummarizeEnabled by viewModel.isSummarizeEnabled.collectAsState()
                val isContinueWritingEnabled by viewModel.isContinueWritingEnabled.collectAsState()
                val defaultTranslateTarget by viewModel.defaultTranslateTarget.collectAsState()
                val defaultSummarizeFormat by viewModel.defaultSummarizeFormat.collectAsState()
                val playgroundState by viewModel.playgroundState.collectAsState()

                var selectedTab by rememberSaveable { mutableStateOf(MainNavTab.PLAYGROUND) }
                var showDeleteConfirmDialog by remember { mutableStateOf(false) }
                var modelToDelete by remember { mutableStateOf<com.fixlocal.android.data.model.ModelOption?>(null) }
                var showDeviceSpecsDialog by remember { mutableStateOf(false) }
                var showMenu by remember { mutableStateOf(false) }

                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri ->
                    uri?.let { viewModel.importModel(it) }
                }

                Scaffold(
                    topBar = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .statusBarsPadding()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Column {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Vyasa",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                letterSpacing = 0.4.sp,
                                                color = MaterialTheme.colorScheme.onBackground
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.surface,
                                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                            ) {
                                                Text(
                                                    text = "OFFLINE",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 0.8.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "On-Device AI Assistant",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        onClick = { showDeviceSpecsDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Outlined.Devices,
                                                contentDescription = "Device Hardware Specs",
                                                tint = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    Box {
                                        Surface(
                                            onClick = { showMenu = true },
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Outlined.MoreVert,
                                                    contentDescription = "More Options",
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Sideload Model (.litertlm)") },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.FileOpen,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                },
                                                onClick = {
                                                    showMenu = false
                                                    filePickerLauncher.launch("*/*")
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                thickness = 1.dp
                            )
                        }
                    },
                    bottomBar = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(bottom = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(28.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                shadowElevation = 8.dp,
                                modifier = Modifier
                                    .width(260.dp)
                                    .height(56.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MainNavTab.entries.forEach { tab ->
                                        val selected = selectedTab == tab
                                        Surface(
                                            onClick = { selectedTab = tab },
                                            shape = CircleShape,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(42.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = if (selected) tab.selectedIcon else tab.icon,
                                                    contentDescription = tab.label,
                                                    modifier = Modifier.size(22.dp),
                                                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        when (selectedTab) {
                            MainNavTab.PLAYGROUND -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    PlaygroundCard(
                                        inputText = playgroundState.inputText,
                                        isRunning = playgroundState.isRunning,
                                        selectedTask = playgroundState.selectedTask,
                                        translateTarget = playgroundState.translateTarget,
                                        summarizeFormat = playgroundState.summarizeFormat,
                                        result = playgroundState.result,
                                        errorMessage = playgroundState.errorMessage,
                                        isModelReady = (modelStatus is ModelStatus.Ready),
                                        activeModelOption = selectedModelOption,
                                        onInputChanged = { viewModel.onInputTextChanged(it) },
                                        onTaskSelected = { viewModel.setPlaygroundTask(it) },
                                        onTranslateTargetChanged = { viewModel.setPlaygroundTranslateTarget(it) },
                                        onSummarizeFormatChanged = { viewModel.setPlaygroundSummarizeFormat(it) },
                                        onNavigateToSettings = { selectedTab = MainNavTab.SETTINGS },
                                        onTestFixClick = { viewModel.testFix() }
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                            MainNavTab.FEATURES -> {
                                Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                    FeaturesCard(
                                        isTranslateEnabled = isTranslateEnabled,
                                        isSummarizeEnabled = isSummarizeEnabled,
                                        isContinueWritingEnabled = isContinueWritingEnabled,
                                        defaultTranslateTarget = defaultTranslateTarget,
                                        defaultSummarizeFormat = defaultSummarizeFormat,
                                        onTranslateEnabledChanged = { viewModel.setTranslateEnabled(it) },
                                        onSummarizeEnabledChanged = { viewModel.setSummarizeEnabled(it) },
                                        onContinueWritingEnabledChanged = { viewModel.setContinueWritingEnabled(it) }
                                    )
                                }
                            }
                            MainNavTab.SETTINGS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    ModelStatusCard(
                                        status = modelStatus,
                                        selectedOption = selectedModelOption,
                                        onOptionSelected = { viewModel.selectModelOption(it) },
                                        deviceSpecs = viewModel.deviceSpecs,
                                        isModelCapable = { viewModel.isModelCapable(it) },
                                        isModelDownloaded = { viewModel.isModelDownloaded(it) },
                                        onDownloadClick = { viewModel.startDownload() },
                                        onCancelDownloadClick = { viewModel.cancelDownload() },
                                        onDeleteClick = { optionToDelete ->
                                            modelToDelete = optionToDelete
                                            showDeleteConfirmDialog = true
                                        }
                                    )

                                    SettingsAndGuideCard(
                                        selectedTone = selectedTone,
                                        customInstruction = customInstruction,
                                        wordLimitEnabled = wordLimitEnabled,
                                        wordLimitCount = wordLimitCount,
                                        selectedBackend = selectedBackend,
                                        deviceSpecs = viewModel.deviceSpecs,
                                        defaultTranslateTarget = defaultTranslateTarget,
                                        defaultSummarizeFormat = defaultSummarizeFormat,
                                        selectedModelOption = selectedModelOption,
                                        onToneSelected = { viewModel.setTone(it) },
                                        onCustomInstructionChanged = { viewModel.setCustomInstruction(it) },
                                        onWordLimitChanged = { enabled, limit -> viewModel.setWordLimit(enabled, limit) },
                                        onBackendSelected = { viewModel.setBackend(it) },
                                        onDefaultTranslateTargetChanged = { viewModel.setDefaultTranslateTarget(it) },
                                        onDefaultSummarizeFormatChanged = { viewModel.setDefaultSummarizeFormat(it) }
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                    }
                }

                // Delete Confirmation Dialog (Strict Monochrome)
                if (showDeleteConfirmDialog) {
                    val targetName = modelToDelete?.displayName ?: selectedModelOption.displayName
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmDialog = false },
                        title = { Text("Delete On-Device Model?") },
                        text = {
                            Text("This will remove $targetName from local storage. Other models will remain available.")
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDeleteConfirmDialog = false
                                    modelToDelete?.let { viewModel.deleteModel(it) } ?: viewModel.deleteModel()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                )
                            ) {
                                Text("Delete", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                Text("Cancel")
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                }

                // Device Hardware Specs Dialog
                if (showDeviceSpecsDialog) {
                    val specs = viewModel.deviceSpecs
                    AlertDialog(
                        onDismissRequest = { showDeviceSpecsDialog = false },
                        title = { Text("Device Hardware Specs") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("• Device: ${specs.manufacturer} ${specs.model}")
                                Text("• Total RAM: ${String.format(Locale.US, "%.1f GB", specs.totalRamGb)} (${String.format(Locale.US, "%.1f GB", specs.availableRamGb)} available)")
                                Text("• CPU Cores: ${specs.cpuCores} cores")
                                Text("• GPU OpenCL: ${if (specs.hasOpenClDriver) "Available" else "Not detected"}")
                                Text("• GPU Acceleration: ${if (specs.isGpuSupported) "Supported" else "Disabled (CPU Recommended)"}")
                                Text("• Recommended AI Model: ${specs.recommendedModel.displayName}")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showDeviceSpecsDialog = false }) {
                                Text("Close")
                            }
                        },
                        shape = RoundedCornerShape(14.dp)
                    )
                }
            }
        }
    }
}
