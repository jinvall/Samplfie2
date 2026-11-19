package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.ConsoleTab
import com.example.ui.SamplfieViewModel
import com.example.ui.components.HardwareTopRack
import com.example.ui.screens.DeckEditorScreen
import com.example.ui.screens.FxRackScreen
import com.example.ui.screens.RigLinkMidiScreen
import com.example.ui.screens.StudioPerformanceScreen
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.GunmetalDeck
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.Samplfie2Theme
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    private val viewModel: SamplfieViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Samplfie2Theme {
                SamplfieConsoleApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun SamplfieConsoleApp(viewModel: SamplfieViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    val importAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importAudioFromUri(it) }
    }

    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val rigConfig by viewModel.rigConfig.collectAsStateWithLifecycle()
    val fxRack by viewModel.fxRack.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val micTelemetry by viewModel.micTelemetry.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val midiState by viewModel.midiState.collectAsStateWithLifecycle()
    val looperState by viewModel.looperState.collectAsStateWithLifecycle()
    val sequencerState by viewModel.sequencerState.collectAsStateWithLifecycle()
    val timelineState by viewModel.timelineState.collectAsStateWithLifecycle()

    var showMp3Dialog by remember { mutableStateOf(false) }
    var mp3NameInput by remember { mutableStateOf("samplfie2_mix") }

    val activeBuf = editorState.processedBuffer ?: editorState.dryBuffer
    val hasAudio = activeBuf != null && activeBuf.frameCount > 0

    if (showMp3Dialog) {
        AlertDialog(
            onDismissRequest = { showMp3Dialog = false },
            containerColor = GunmetalDeck,
            title = { Text("EXPORT .MP3 TO DEVICE", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        text = "Saves to Music/Samplfie2/ at ${rigConfig.mp3BitrateKbps} kbps.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = mp3NameInput,
                        onValueChange = { mp3NameInput = it },
                        label = { Text("Filename") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .testTag("dialog_mp3_filename")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.exportAsMp3(mp3NameInput)
                        showMp3Dialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                    modifier = Modifier.testTag("dialog_mp3_confirm")
                ) {
                    Text("SAVE MP3", color = ObsidianChassis)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMp3Dialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 700.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ObsidianChassis,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                HardwareTopRack(
                    rigConfig = rigConfig,
                    activeFxCount = fxRack.activeCount,
                    isMasterBypass = fxRack.masterBypass,
                    isRecording = micTelemetry.isRecording,
                    hasAudio = hasAudio,
                    onExportMp3Click = { showMp3Dialog = true },
                    modifier = Modifier.statusBarsPadding()
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = GunmetalDeck,
                        modifier = Modifier.navigationBarsPadding()
                    ) {
                        ConsoleTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { viewModel.selectTab(tab) },
                                icon = {
                                    Icon(
                                        imageVector = tabIcon(tab),
                                        contentDescription = tab.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ObsidianChassis,
                                    selectedTextColor = NeonCyan,
                                    indicatorColor = NeonCyan,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = GunmetalDeck,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        ConsoleTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = selectedTab == tab,
                                onClick = { viewModel.selectTab(tab) },
                                icon = {
                                    Icon(
                                        imageVector = tabIcon(tab),
                                        contentDescription = tab.label
                                    )
                                },
                                label = {
                                    Text(tab.label, style = MaterialTheme.typography.labelSmall)
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = ObsidianChassis,
                                    selectedTextColor = NeonCyan,
                                    indicatorColor = NeonCyan,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_rail_${tab.name.lowercase()}")
                            )
                        }
                    }
                }

                when (selectedTab) {
                    ConsoleTab.DECK -> DeckEditorScreen(
                        editorState = editorState,
                        micTelemetry = micTelemetry,
                        playbackState = playbackState,
                        fxRack = fxRack,
                        networkState = networkState,
                        hasMicPermission = hasMicPermission,
                        onRequestMicPermission = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onTacPressStart = { viewModel.onTacPressStart() },
                        onTacPressRelease = { viewModel.onTacPressRelease() },
                        onGenerateOscTone = { viewModel.generateReferenceOscillatorTone() },
                        onVisualizerModeChange = { viewModel.setVisualizerMode(it) },
                        onSelectionChange = { s, e -> viewModel.updateSelection(s, e) },
                        onSeekPlayhead = { viewModel.seekPlayhead(it) },
                        onTogglePlay = { viewModel.togglePlay() },
                        onStop = { viewModel.stopPlayback() },
                        onToggleLoop = { viewModel.toggleLoop() },
                        onTogglePlaySelectionOnly = { viewModel.togglePlaySelectionOnly() },
                        onToggleMasterBypass = { viewModel.toggleMasterBypass() },
                        onExtractSelection = { viewModel.extractSelection() },
                        onUndo = { viewModel.undoExtraction() },
                        onResetToOriginal = { viewModel.resetToOriginalTake() },
                        onOpenFxRack = { viewModel.selectTab(ConsoleTab.FX_RACK) },
                        onSendToLooperSlot = { viewModel.sendEditorToLoopSlot(it) },
                        onSendToSequencer = { viewModel.sendEditorToSequencerLane(0) },
                        onAddToTimeline = { viewModel.addEditorClipToTimeline(0) },
                        onStreamTx = { viewModel.streamActiveAudioOverNetwork() },
                        onExportMp3Click = { showMp3Dialog = true },
                        onLoadSavedTake = { viewModel.loadSavedTake(it) },
                        onImportAudioFile = {
                            importAudioLauncher.launch(arrayOf("audio/*"))
                        }
                    )

                    ConsoleTab.FX_RACK -> FxRackScreen(
                        fxRack = fxRack,
                        playbackState = playbackState,
                        hasAudio = hasAudio,
                        onTogglePlay = { viewModel.togglePlay() },
                        onToggleMasterBypass = { viewModel.toggleMasterBypass() },
                        onApplyPreset = { viewModel.applyFxPreset(it) },
                        onUpdateFxRack = { viewModel.updateFxRack(it) }
                    )

                    ConsoleTab.STUDIO -> StudioPerformanceScreen(
                        looperState = looperState,
                        sequencerState = sequencerState,
                        timelineState = timelineState,
                        hasEditorAudio = hasAudio,
                        onSetBpm = { viewModel.setStudioBpm(it) },
                        onToggleLooperPlay = { viewModel.toggleLooperPlayback() },
                        onAssignEditorToLoop = { viewModel.sendEditorToLoopSlot(it) },
                        onToggleLoopSlot = { viewModel.toggleLoopSlot(it) },
                        onToggleLoopMute = { viewModel.toggleLoopMute(it) },
                        onClearLoopSlot = { viewModel.clearLoopSlot(it) },
                        onSetLoopBars = { slotId, bars -> viewModel.setLoopSlotBars(slotId, bars) },
                        onBounceLooperToEditor = { viewModel.bounceLooperToEditor() },
                        onToggleSequencerPlay = { viewModel.toggleSequencerPlayback() },
                        onToggleSequencerStep = { laneId, step -> viewModel.toggleSequencerStep(laneId, step) },
                        onToggleSequencerMute = { viewModel.toggleSequencerLaneMute(it) },
                        onAssignEditorToSeqLane = { viewModel.sendEditorToSequencerLane(it) },
                        onSetSwing = { viewModel.setSequencerSwing(it) },
                        onBounceSequencerToTimeline = { viewModel.bounceSequencerToTimeline() },
                        onToggleTimelinePlay = { viewModel.toggleTimelinePlayback() },
                        onSeekTimeline = { viewModel.seekTimeline(it) },
                        onAddEditorClipToTrack = { viewModel.addEditorClipToTimeline(it) },
                        onAddLooperToTimeline = { viewModel.addLooperMixToTimeline() },
                        onNudgeClip = { trackId, clipId, delta -> viewModel.nudgeTimelineClip(trackId, clipId, delta) },
                        onRemoveClip = { trackId, clipId -> viewModel.removeTimelineClip(trackId, clipId) },
                        onToggleTrackMute = { viewModel.toggleTimelineMute(it) },
                        onToggleTrackSolo = { viewModel.toggleTimelineSolo(it) },
                        onBounceTimelineToEditor = { viewModel.bounceTimelineToEditor() }
                    )

                    ConsoleTab.RIG_LINK -> RigLinkMidiScreen(
                        rigConfig = rigConfig,
                        networkState = networkState,
                        midiState = midiState,
                        hasAudio = hasAudio,
                        isExportingMp3 = editorState.isExportingMp3,
                        lastExport = editorState.lastExport,
                        onUpdateConfig = { viewModel.updateRigConfig(it) },
                        onStreamTx = { viewModel.streamActiveAudioOverNetwork() },
                        onStopStream = { viewModel.stopNetworkStream() },
                        onExportMp3 = { viewModel.exportAsMp3(it) },
                        onRefreshMidi = { viewModel.refreshMidiDevices() }
                    )
                }
            }
        }
    }
}

private fun tabIcon(tab: ConsoleTab) = when (tab) {
    ConsoleTab.DECK -> Icons.Filled.GraphicEq
    ConsoleTab.FX_RACK -> Icons.Filled.Tune
    ConsoleTab.STUDIO -> Icons.Filled.GridView
    ConsoleTab.RIG_LINK -> Icons.Filled.SettingsInputComponent
}
