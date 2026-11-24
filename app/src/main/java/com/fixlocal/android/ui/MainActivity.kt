package com.fixlocal.android.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
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
import androidx.compose.material3.IconButton
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
import com.fixlocal.android.ui.components.SupportCard
import com.fixlocal.android.ui.theme.FixLocalTheme
import java.util.Locale
import androidx.compose.ui.res.painterResource
import com.fixlocal.android.R

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
                        // ── Modern Viyasa Header ───────────────────────────────────────────
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .statusBarsPadding()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Left: logo badge + wordmark
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Logo badge — sleek rounded squircle
                                    Surface(
                                        shape = RoundedCornerShape(13.dp),
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_viyasa_logo),
                                                contentDescription = "Viyasa",
                                                tint = Color.Unspecified,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }

                                    // Wordmark column
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "Viyasa",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp,
                                            letterSpacing = (-0.3).sp,
                                            color = MaterialTheme.colorScheme.onBackground
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(5.dp)
                                                    .background(
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                        shape = CircleShape
                                                    )
                                            )
                                            Text(
                                                text = "Private On-Device AI",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Normal,
                                                letterSpacing = 0.1.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                // Right: action buttons
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Hardware specs button
                                    Surface(
                                        onClick = { showDeviceSpecsDialog = true },
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Outlined.Devices,
                                                contentDescription = "Device Hardware Specs",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    // More options button + dropdown
                                    Box {
                                        Surface(
                                            onClick = { showMenu = true },
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                            modifier = Modifier.size(38.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Outlined.MoreVert,
                                                    contentDescription = "More Options",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Support on Ko-fi") },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Default.LocalCafe,
                                                        contentDescription = null,
                                                        tint = Color(0xFF72A4F2),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                },
                                                onClick = {
                                                    showMenu = false
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/I7H42B846I")).apply {
                                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                                    }
                                                    startActivity(intent)
                                                }
                                            )

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

                            // Thin hairline separator at bottom of header
                            HorizontalDivider(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                thickness = 0.8.dp
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Scrollable content area
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = innerPadding.calculateTopPadding())
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

                                        SupportCard()

                                        Spacer(modifier = Modifier.height(100.dp))
                                    }
                                }
                                MainNavTab.FEATURES -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
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

                                        Spacer(modifier = Modifier.height(100.dp))
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

                                        Spacer(modifier = Modifier.height(100.dp))
                                    }
                                }
                            }
                        }

                        // Truly Floating Minimalist Navigation Capsule
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 20.dp),
                            shape = RoundedCornerShape(32.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shadowElevation = 12.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MainNavTab.entries.forEach { tab ->
                                    val selected = selectedTab == tab
                                    Surface(
                                        onClick = { selectedTab = tab },
                                        shape = CircleShape,
                                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(46.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.icon,
                                                contentDescription = tab.label,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
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
