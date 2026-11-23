package com.example

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.ui.components.AllStemsConfirmationDialog
import com.example.ui.components.CollabConnectDialog
import com.example.ui.components.SecretsVaultDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.SamplfieViewModel
import com.example.ui.StudioSpoke
import com.example.ui.components.CircularStudioHub
import com.example.ui.components.DockedCircularProductionHub
import com.example.ui.components.HardwareTopRack
import com.example.ui.components.WideCircularProductionSidebar
import com.example.ui.screens.DeckEditorScreen
import com.example.ui.screens.FxRackScreen
import com.example.ui.screens.InstrumentsSpokeScreen
import com.example.ui.screens.LiveSpokeScreen
import com.example.ui.screens.SamplesSpokeScreen
import com.example.ui.screens.ToolsAndPluginsScreen
import com.example.ui.screens.TracksSpokeScreen
import com.example.ui.screens.TunerSpokeScreen
import com.example.ui.screens.VmcSpokeScreen
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.GunmetalDeck
import com.example.ui.theme.HairlineDivider
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val requiredAudioReadPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioReadPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                requiredAudioReadPermission
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMicPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                hasAudioReadPermission = ContextCompat.checkSelfPermission(
                    context,
                    requiredAudioReadPermission
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
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            viewModel.importAudioUri(uri)
        }
    }

    val launchAudioPickerSafely = {
        try {
            importAudioLauncher.launch(arrayOf("audio/*"))
        } catch (_: ActivityNotFoundException) {
            try {
                importAudioLauncher.launch(arrayOf("*/*"))
            } catch (ex: Exception) {
                Toast.makeText(context, "No audio file picker found: ${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val audioReadPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioReadPermission = granted
        launchAudioPickerSafely()
    }

    val importRefAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            viewModel.loadReferenceBufferFromUri(uri)
        }
    }

    val importPluginBundleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            viewModel.importPluginOrIrFromUri(uri)
        }
    }

    val selectedSpoke by viewModel.selectedSpoke.collectAsStateWithLifecycle()
    val workspaceConfig by viewModel.workspaceConfig.collectAsStateWithLifecycle()
    val tunerState by viewModel.tunerState.collectAsStateWithLifecycle()
    val vmcState by viewModel.vmcState.collectAsStateWithLifecycle()
    val rigConfig by viewModel.rigConfig.collectAsStateWithLifecycle()
    val secretsState by viewModel.secretsState.collectAsStateWithLifecycle()
    val stemState by viewModel.stemState.collectAsStateWithLifecycle()
    val toolsState by viewModel.toolsState.collectAsStateWithLifecycle()
    val showSecretsVault by viewModel.showSecretsVault.collectAsStateWithLifecycle()
    val showCollabSheet by viewModel.showCollabSheet.collectAsStateWithLifecycle()
    val collabPeers by viewModel.collabPeers.collectAsStateWithLifecycle()
    val bandRecState by viewModel.bandRecState.collectAsStateWithLifecycle()
    val fxRack by viewModel.fxRack.collectAsStateWithLifecycle()
    val stompbox by viewModel.stompbox.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val loopEditorState by viewModel.loopEditorState.collectAsStateWithLifecycle()
    val micTelemetry by viewModel.micTelemetry.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val midiState by viewModel.midiState.collectAsStateWithLifecycle()
    val looperState by viewModel.looperState.collectAsStateWithLifecycle()
    val sequencerState by viewModel.sequencerState.collectAsStateWithLifecycle()
    val timelineState by viewModel.timelineState.collectAsStateWithLifecycle()
    val syncedPlayhead by viewModel.syncedPlayhead.collectAsStateWithLifecycle()
    val customDrumKitState by viewModel.customDrumKitState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.toastEvents.collect { msg ->
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    BackHandler(enabled = selectedSpoke != StudioSpoke.BOOTH) {
        viewModel.selectSpoke(StudioSpoke.BOOTH)
    }

    var showExportDialog by remember { mutableStateOf(false) }
    var exportNameInput by remember { mutableStateOf("samplfie2_mix") }

    val activeBuf = editorState.processedBuffer ?: editorState.dryBuffer
    val hasAudio = (activeBuf != null && activeBuf.frameCount > 0) ||
        timelineState.tracks.any { it.clips.isNotEmpty() }

    if (showSecretsVault) {
        SecretsVaultDialog(
            secretsState = secretsState,
            onSaveSecret = { k, v, hint -> viewModel.saveSecretKey(k, v, hint) },
            onDeleteSecret = { k -> viewModel.deleteSecretKey(k) },
            onDismiss = { viewModel.setShowSecretsVault(false) }
        )
    }

    if (showCollabSheet) {
        CollabConnectDialog(
            peers = collabPeers,
            bandRecState = bandRecState,
            defaultHost = rigConfig.ipAddress,
            defaultPort = rigConfig.port,
            nodeName = rigConfig.nodeName,
            onRefreshDiscovery = { viewModel.refreshNearbyDiscovery() },
            onSelectMyStyle = { style, slot -> viewModel.setMyCollabStyle(style, slot) },
            onConnectPeer = { host, port, name, platform, style ->
                viewModel.connectCollabPeer(host, port, name, platform, style)
            },
            onDisconnectPeer = { peerId -> viewModel.disconnectCollabPeer(peerId) },
            onRemoveKnownPeer = { peerId -> viewModel.removeKnownCollabPeer(peerId) },
            onUpdatePeerStyleAndTrack = { peerId, style, slot ->
                viewModel.updateCollabPeerStyleAndTrack(peerId, style, slot)
            },
            onToggleBandRec = { viewModel.toggleLiveBandRecording() },
            onDismiss = { viewModel.setShowCollabSheet(false) }
        )
    }

    if (stemState.showAllStemsConfirmation) {
        AllStemsConfirmationDialog(
            estimatedCredits = stemState.pendingAllStemsEstimatedCredits,
            durationSec = stemState.pendingAudioDurationSec,
            onConfirmAllStems = { viewModel.confirmSeparateAllStems() },
            onDismiss = { viewModel.dismissAllStemsConfirmation() }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = GunmetalDeck,
            title = { Text("LOCAL EXPORT (MUSIC/SAMPLFIE2)", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        text = "Export master audio locally as .WAV or .MP3 (${rigConfig.mp3BitrateKbps} kbps).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = exportNameInput,
                        onValueChange = { exportNameInput = it },
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.exportAsWav(exportNameInput)
                            showExportDialog = false
                        },
                        border = BorderStroke(1.dp, NeonCyan),
                        modifier = Modifier.testTag("dialog_wav_confirm")
                    ) {
                        Text("SAVE .WAV", color = NeonCyan)
                    }
                    Button(
                        onClick = {
                            viewModel.exportAsMp3(exportNameInput)
                            showExportDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                        modifier = Modifier.testTag("dialog_mp3_confirm")
                    ) {
                        Text("SAVE .MP3", color = ObsidianChassis)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }

    val openAudioPicker = {
        if (hasAudioReadPermission) {
            launchAudioPickerSafely()
        } else {
            audioReadPermissionLauncher.launch(requiredAudioReadPermission)
        }
    }

    val connectedPeerCount = collabPeers.count { it.isConnected }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideStudioLayout = maxWidth >= 700.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = ObsidianChassis,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                HardwareTopRack(
                    rigConfig = rigConfig,
                    selectedSpoke = selectedSpoke,
                    activeFxCount = fxRack.activeCount,
                    isMasterBypass = fxRack.masterBypass,
                    isRecording = micTelemetry.isRecording,
                    hasAudio = hasAudio,
                    bpm = syncedPlayhead.bpm,
                    hasDisbandKey = secretsState.hasDisbandKey,
                    connectedPeerCount = connectedPeerCount,
                    isBandRecording = bandRecState.isRecording,
                    onReturnToBooth = { viewModel.selectSpoke(StudioSpoke.BOOTH) },
                    onOpenCollabDialog = { viewModel.setShowCollabSheet(true) },
                    onOpenSecretsVault = { viewModel.setShowSecretsVault(true) },
                    onExportMp3Click = { showExportDialog = true },
                    modifier = Modifier.statusBarsPadding()
                )
            },
            bottomBar = {
                if (!isWideStudioLayout) {
                    StudioSpokeBottomDock(
                        selectedSpoke = selectedSpoke,
                        dockSpokes = workspaceConfig.pinnedDockSpokes,
                        onSelectSpoke = { viewModel.selectSpoke(it) },
                        modifier = Modifier.navigationBarsPadding()
                    )
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideStudioLayout) {
                    WideCircularProductionSidebar(
                        selectedSpoke = selectedSpoke,
                        editorState = editorState,
                        playbackState = playbackState,
                        micTelemetry = micTelemetry,
                        fxRack = fxRack,
                        stompbox = stompbox,
                        looperState = looperState,
                        sequencerState = sequencerState,
                        timelineState = timelineState,
                        syncedPlayhead = syncedPlayhead,
                        networkState = networkState,
                        midiState = midiState,
                        hasAudio = hasAudio,
                        workspaceConfig = workspaceConfig,
                        onSelectSpoke = { viewModel.selectSpoke(it) },
                        onToggleSyncedPlay = { viewModel.toggleSyncedTransport() },
                        onToggleDeckPlay = { viewModel.togglePlay() },
                        onTapTempo = { viewModel.tapTempo() },
                        onToggleStompBypass = { viewModel.toggleStompboxBypass() },
                        onImportAudioClick = openAudioPicker,
                        onExportMp3Click = { showExportDialog = true },
                        onStreamTxClick = { viewModel.streamActiveAudioOverNetwork() },
                        hasDisbandKey = secretsState.hasDisbandKey,
                        masterVolume = rigConfig.masterVolume,
                        isMasterMuted = rigConfig.isMasterMuted,
                        connectedPeerCount = connectedPeerCount,
                        myStyleShortTag = bandRecState.myStyle.shortTag,
                        isBandRecording = bandRecState.isRecording,
                        onSetMasterVolume = { viewModel.setMasterVolume(it) },
                        onToggleMasterMute = { viewModel.toggleMasterMute() },
                        onOpenCollabDialog = { viewModel.setShowCollabSheet(true) },
                        onToggleBandRec = { viewModel.toggleLiveBandRecording() },
                        onOpenSecretsVault = { viewModel.setShowSecretsVault(true) },
                        onPushHandoffClick = { viewModel.pushSessionToPeer() }
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    if (!isWideStudioLayout && selectedSpoke != StudioSpoke.BOOTH) {
                        DockedCircularProductionHub(
                            selectedSpoke = selectedSpoke,
                            editorState = editorState,
                            playbackState = playbackState,
                            micTelemetry = micTelemetry,
                            fxRack = fxRack,
                            stompbox = stompbox,
                            looperState = looperState,
                            sequencerState = sequencerState,
                            timelineState = timelineState,
                            syncedPlayhead = syncedPlayhead,
                            networkState = networkState,
                            midiState = midiState,
                            hasAudio = hasAudio,
                            workspaceConfig = workspaceConfig,
                            masterVolume = rigConfig.masterVolume,
                            isMasterMuted = rigConfig.isMasterMuted,
                            connectedPeerCount = connectedPeerCount,
                            myStyleShortTag = bandRecState.myStyle.shortTag,
                            isBandRecording = bandRecState.isRecording,
                            onSetMasterVolume = { viewModel.setMasterVolume(it) },
                            onToggleMasterMute = { viewModel.toggleMasterMute() },
                            onOpenCollabDialog = { viewModel.setShowCollabSheet(true) },
                            onSelectSpoke = { viewModel.selectSpoke(it) },
                            onToggleSyncedPlay = { viewModel.toggleSyncedTransport() },
                            onToggleDeckPlay = { viewModel.togglePlay() },
                            onTapTempo = { viewModel.tapTempo() },
                            onToggleStompBypass = { viewModel.toggleStompboxBypass() },
                            onImportAudioClick = openAudioPicker,
                            onStreamTxClick = { viewModel.streamActiveAudioOverNetwork() }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        when (selectedSpoke) {
                            StudioSpoke.BOOTH -> CircularStudioHub(
                                editorState = editorState,
                                playbackState = playbackState,
                                micTelemetry = micTelemetry,
                                fxRack = fxRack,
                                stompbox = stompbox,
                                looperState = looperState,
                                sequencerState = sequencerState,
                                timelineState = timelineState,
                                syncedPlayhead = syncedPlayhead,
                                networkState = networkState,
                                midiState = midiState,
                                secretsState = secretsState,
                                stemState = stemState,
                                workspaceConfig = workspaceConfig,
                                collabPeers = collabPeers,
                                bandRecState = bandRecState,
                                masterVolume = rigConfig.masterVolume,
                                isMasterMuted = rigConfig.isMasterMuted,
                                peerLabel = "${rigConfig.ipAddress}:${rigConfig.port}",
                                onSelectSpoke = { viewModel.selectSpoke(it) },
                                onToggleSyncedPlay = { viewModel.toggleSyncedTransport() },
                                onToggleDeckPlay = { viewModel.togglePlay() },
                                onTapTempo = { viewModel.tapTempo() },
                                onToggleStompBypass = { viewModel.toggleStompboxBypass() },
                                onSelectLiveMode = { viewModel.setLiveSessionMode(it) },
                                onImportAudioClick = openAudioPicker,
                                onGenerateOscTone = { viewModel.generateReferenceOscillatorTone() },
                                onExportWavClick = { viewModel.exportAsWav("samplfie2_booth") },
                                onExportMp3Click = { showExportDialog = true },
                                onStreamTxClick = { viewModel.streamActiveAudioOverNetwork() },
                                onSetMasterVolume = { viewModel.setMasterVolume(it) },
                                onToggleMasterMute = { viewModel.toggleMasterMute() },
                                onOpenCollabDialog = { viewModel.setShowCollabSheet(true) },
                                onToggleBandRec = { viewModel.toggleLiveBandRecording() },
                                onPushHandoffClick = { viewModel.pushSessionToPeer() },
                                onGrabHandoffClick = { viewModel.grabSessionFromPeer() },
                                onApplyPendingHandoff = { viewModel.applyPendingHandoff() },
                                onToggleRadialWheelSpoke = { viewModel.toggleRadialWheelSpoke(it) },
                                onToggleDockSpokePinned = { viewModel.toggleDockSpokePinned(it) },
                                onOpenSecretsVault = { viewModel.setShowSecretsVault(true) },
                                onToggleDisbandStem = { viewModel.toggleDisbandStem(it) },
                                onApplyStemPreset = { viewModel.applyStemPreset(it) },
                                onSetStemSelectionOnly = { viewModel.setStemUseSelectionOnly(it) },
                                onRequestStemSeparation = { viewModel.requestStemSeparation() },
                                onCancelStemSeparation = { viewModel.cancelStemSeparation() },
                                onLoadStemToDeck = { viewModel.loadSeparatedStemToDeck(it) },
                                onSendStemToLooper = { stemId, slotId -> viewModel.sendSeparatedStemToLooper(stemId, slotId) },
                                onAddStemToTimeline = { stemId, trackId -> viewModel.addSeparatedStemToTimeline(stemId, trackId) },
                                onSendAllStemsToTimeline = { viewModel.sendAllSeparatedStemsToTimeline() },
                                onSendAllStemsToLooper = { viewModel.sendAllSeparatedStemsToLooper() },
                                onExportStemWav = { viewModel.exportSeparatedStemWav(it) },
                                showWheel = !isWideStudioLayout
                            )

                            StudioSpoke.DECK -> DeckEditorScreen(
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
                                onImportAudioClick = openAudioPicker,
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
                                onOpenFxRack = { viewModel.selectSpoke(StudioSpoke.FX_RACK) },
                                onSendToLooperSlot = { viewModel.sendEditorToLoopSlot(it) },
                                onSendToSequencer = { viewModel.sendEditorToSequencerLane(0) },
                                onAddToTimeline = { viewModel.addEditorClipToTimeline(0) },
                                onStreamTx = { viewModel.streamActiveAudioOverNetwork() },
                                onExportWavClick = { viewModel.exportAsWav("samplfie2_deck") },
                                onExportMp3Click = { showExportDialog = true },
                                onLoadSavedTake = { viewModel.loadSavedTake(it) },
                                specPalette = toolsState.specPalette,
                                specFreqLowHz = toolsState.specFreqLowHz,
                                specFreqHighHz = toolsState.specFreqHighHz,
                                onSpecFreqRangeChange = { lo, hi -> viewModel.updateSpecFreqRange(lo, hi) },
                                onOpenTools = { viewModel.selectSpoke(StudioSpoke.TOOLS) },
                                onFadesChange = { fin, fout -> viewModel.setDeckFades(fin, fout) },
                                onApplyFades = { viewModel.applyDeckFades() }
                            )

                            StudioSpoke.SAMPLES -> SamplesSpokeScreen(
                                looperState = looperState,
                                loopEditorState = loopEditorState,
                                savedTakes = editorState.savedTakes,
                                hasEditorAudio = hasAudio,
                                onSetBpm = { viewModel.setStudioBpm(it) },
                                onToggleLooperPlay = { viewModel.toggleLooperPlayback() },
                                onAssignEditorToLoop = { viewModel.sendEditorToLoopSlot(it) },
                                onToggleLoopSlot = { viewModel.toggleLoopSlot(it) },
                                onToggleLoopMute = { viewModel.toggleLoopMute(it) },
                                onClearLoopSlot = { viewModel.clearLoopSlot(it) },
                                onSetLoopBars = { slotId, bars -> viewModel.setLoopSlotBars(slotId, bars) },
                                onBounceLooperToEditor = { viewModel.bounceLooperToEditor() },
                                onImportAudioClick = openAudioPicker,
                                onLoadSavedTake = { viewModel.loadSavedTake(it) },
                                onLoadSlotIntoRefiner = { viewModel.loadLoopSlotIntoRefiner(it) },
                                onUpdateLoopSelection = { s, e -> viewModel.updateLoopRefinerSelection(s, e) },
                                onSnapZeroCrossing = { viewModel.snapLoopSelectionToZeroCrossing() },
                                onTrimLoop = { viewModel.trimLoopRefiner() },
                                onSetLoopFades = { fin, fout -> viewModel.setLoopRefinerFades(fin, fout) },
                                onApplyFades = { viewModel.applyLoopRefinerFades() },
                                onReverseLoop = { viewModel.reverseLoopRefiner() },
                                onNormalizeLoop = { viewModel.normalizeLoopRefiner() },
                                onRemoveDcLoop = { viewModel.removeDcLoopRefiner() },
                                onTuneLoopPitchSpeed = { p, sp -> viewModel.applyLoopRefinerPitchSpeed(p, sp) },
                                onResetLoopRefiner = { viewModel.resetLoopRefiner() },
                                onAuditionLoopRefiner = { viewModel.auditionLoopRefiner() },
                                onCommitRefinerToSlot = { viewModel.commitRefinerToLoopSlot() },
                                onSendRefinerToDeck = { viewModel.sendRefinerToDeck() },
                                onToggleLoopSlotLock = { viewModel.toggleLoopSlotLock(it) }
                            )

                            StudioSpoke.INSTRUMENTS -> InstrumentsSpokeScreen(
                                sequencerState = sequencerState,
                                midiState = midiState,
                                customDrumKitState = customDrumKitState,
                                hasEditorAudio = hasAudio,
                                onSetBpm = { viewModel.setStudioBpm(it) },
                                onToggleSequencerPlay = { viewModel.toggleSequencerPlayback() },
                                onToggleSequencerStep = { laneId, step -> viewModel.toggleSequencerStep(laneId, step) },
                                onToggleSequencerMute = { viewModel.toggleSequencerLaneMute(it) },
                                onAssignEditorToSeqLane = { viewModel.sendEditorToSequencerLane(it) },
                                onTriggerLanePad = { viewModel.triggerSequencerLaneOneShot(it) },
                                onSetSwing = { viewModel.setSequencerSwing(it) },
                                onBounceSequencerToTimeline = { viewModel.bounceSequencerToTimeline() },
                                onRefreshMidi = { viewModel.refreshMidiDevices() },
                                onOpenVmc = { viewModel.selectSpoke(StudioSpoke.VMC) },
                                onOpenTuner = { viewModel.selectSpoke(StudioSpoke.TUNER) },
                                onSetDrumStyle = { viewModel.setDrumKitStyle(it) },
                                onApplyDrumPreset = { viewModel.applyDrumKitPreset(it) },
                                onToggleDrumArrangeMode = { viewModel.toggleDrumArrangeMode() },
                                onFlipDrumKitLeftHanded = { viewModel.flipDrumKitLeftHanded() },
                                onSelectDrumSlot = { viewModel.selectDrumSlot(it) },
                                onSetTargetBeater = { viewModel.setTargetBeaterForMount(it) },
                                onHitDrumPiece = { slotId, beater -> viewModel.hitDrumPiece(slotId, beater) },
                                onHitDrumAddon = { viewModel.hitDrumAddon(it) },
                                onMoveDrumPiece = { slotId, x, y -> viewModel.moveDrumPieceOnStage(slotId, x, y) },
                                onAddDrumPiece = { viewModel.addMainDrumPiece(it) },
                                onSwapDrumPiece = { slotId, compId -> viewModel.swapMainDrumPiece(slotId, compId) },
                                onRemoveDrumPiece = { viewModel.removeMainDrumPiece(it) },
                                onUpdateDrumPieceParams = { slotId, pitch, decay, vol, muted ->
                                    viewModel.updateDrumPieceParams(slotId, pitch, decay, vol, muted)
                                },
                                onAttachDrumAddon = { addonType, targetSlot, beater ->
                                    viewModel.attachDrumAddon(addonType, targetSlot, beater)
                                },
                                onRemoveDrumAddon = { viewModel.removeDrumAddon(it) },
                                onToggleDrumAddonCoupled = { viewModel.toggleDrumAddonCoupled(it) },
                                onSetDrumAddonStyle = { id, style -> viewModel.setDrumAddonStyle(id, style) },
                                onSetDrumAddonBeaterSide = { id, side -> viewModel.setDrumAddonBeaterSide(id, side) },
                                onRouteDrumPieceToSeqLane = { slotId, laneId, beater ->
                                    viewModel.sendDrumPieceToSeqLane(slotId, laneId, beater)
                                },
                                onSendDrumPieceToDeck = { slotId, beater ->
                                    viewModel.sendDrumKitPieceToDeck(slotId, beater)
                                },
                                onToggleLaneLock = { viewModel.toggleSequencerLaneLock(it) }
                            )

                            StudioSpoke.TRACKS -> TracksSpokeScreen(
                                timelineState = timelineState,
                                syncedPlayhead = syncedPlayhead,
                                hasEditorAudio = hasAudio,
                                onSetBpm = { viewModel.setStudioBpm(it) },
                                onToggleSyncedPlay = { viewModel.toggleSyncedTransport() },
                                onToggleTimelinePlay = { viewModel.toggleTimelinePlayback() },
                                onSeekSyncedPlayhead = { viewModel.seekSyncedPlayhead(it) },
                                onAddEditorClipToTrack = { viewModel.addEditorClipToTimeline(it) },
                                onAddLooperToTimeline = { viewModel.addLooperMixToTimeline() },
                                onBounceSequencerToTimeline = { viewModel.bounceSequencerToTimeline() },
                                onNudgeClip = { trackId, clipId, delta -> viewModel.nudgeTimelineClip(trackId, clipId, delta) },
                                onRemoveClip = { trackId, clipId -> viewModel.removeTimelineClip(trackId, clipId) },
                                onToggleTrackMute = { viewModel.toggleTimelineMute(it) },
                                onToggleTrackSolo = { viewModel.toggleTimelineSolo(it) },
                                onBounceTimelineToEditor = { viewModel.bounceTimelineToEditor() }
                            )

                            StudioSpoke.FX_RACK -> FxRackScreen(
                                fxRack = fxRack,
                                playbackState = playbackState,
                                hasAudio = hasAudio,
                                onTogglePlay = { viewModel.togglePlay() },
                                onToggleMasterBypass = { viewModel.toggleMasterBypass() },
                                onApplyPreset = { viewModel.applyFxPreset(it) },
                                onUpdateFxRack = { viewModel.updateFxRack(it) }
                            )

                            StudioSpoke.LIVE -> LiveSpokeScreen(
                                stompbox = stompbox,
                                fxRack = fxRack,
                                rigConfig = rigConfig,
                                micTelemetry = micTelemetry,
                                playbackState = playbackState,
                                looperState = looperState,
                                networkState = networkState,
                                hasMicPermission = hasMicPermission,
                                hasAudio = hasAudio,
                                isExporting = editorState.isExporting,
                                lastLocalExport = editorState.lastLocalExport,
                                bpm = syncedPlayhead.bpm,
                                onSelectLiveMode = { viewModel.setLiveSessionMode(it) },
                                onUpdateStompbox = { viewModel.updateStompbox(it) },
                                onToggleStompBypass = { viewModel.toggleStompboxBypass() },
                                onXyDrag = { x, y -> viewModel.onStompXyDrag(x, y) },
                                onTapTempo = { viewModel.tapTempo() },
                                onTogglePlay = { viewModel.togglePlay() },
                                onToggleSyncedPlay = { viewModel.toggleSyncedTransport() },
                                onToggleLoopSlot = { viewModel.toggleLoopSlot(it) },
                                onRequestMicPermission = {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onTacPressStart = { viewModel.onTacPressStart() },
                                onTacPressRelease = { viewModel.onTacPressRelease() },
                                onBounceTimelineToEditor = { viewModel.bounceTimelineToEditor() },
                                onUpdateConfig = { viewModel.updateRigConfig(it) },
                                onStreamTx = { viewModel.streamActiveAudioOverNetwork() },
                                onStopStream = { viewModel.stopNetworkStream() },
                                onToggleListener = { viewModel.toggleNetworkListener() },
                                onCaptureRxToDeck = { viewModel.captureReceivedAudioToDeck() },
                                onPushHandoff = { viewModel.pushSessionToPeer() },
                                onGrabHandoff = { viewModel.grabSessionFromPeer() },
                                collabPeers = collabPeers,
                                bandRecState = bandRecState,
                                onOpenCollabDialog = { viewModel.setShowCollabSheet(true) },
                                onToggleBandRec = { viewModel.toggleLiveBandRecording() },
                                onSelectMyStyle = { viewModel.setMyCollabStyle(it) },
                                onExportWav = { viewModel.exportAsWav(it) },
                                onExportMp3 = { viewModel.exportAsMp3(it) }
                            )

                            StudioSpoke.TOOLS -> ToolsAndPluginsScreen(
                                editorState = editorState,
                                toolsState = toolsState,
                                playbackState = playbackState,
                                micTelemetry = micTelemetry,
                                fxRack = fxRack,
                                onSelectTab = { viewModel.setToolsTab(it) },
                                onVisualizerModeChange = { viewModel.setVisualizerMode(it) },
                                onSelectionChange = { s, e -> viewModel.updateSelection(s, e) },
                                onSeekPlayhead = { viewModel.seekPlayhead(it) },
                                onTogglePlay = { viewModel.togglePlay() },
                                onStop = { viewModel.stopPlayback() },
                                onToggleLoop = { viewModel.toggleLoop() },
                                onUndo = { viewModel.undoExtraction() },
                                onSelectPalette = { viewModel.setSpectrogramPalette(it) },
                                onApplyCustomHexStops = { viewModel.applyCustomTenStopGradient(it) },
                                onSpecFreqRangeChange = { lo, hi -> viewModel.updateSpecFreqRange(lo, hi) },
                                onSetSpecAttenDb = { viewModel.setSpecDeleteAttenuation(it) },
                                onApplySpectralDelete = { viewModel.applySpectralDelete() },
                                onSetInvertScope = { viewModel.setInvertScope(it) },
                                onSetInvertSelectionOnly = { viewModel.setInvertSelectionOnly(it) },
                                onApplyInvert = { viewModel.applyInvertPolarity() },
                                onPickReferenceAudio = {
                                    try {
                                        importRefAudioLauncher.launch(arrayOf("audio/*"))
                                    } catch (_: ActivityNotFoundException) {
                                        importRefAudioLauncher.launch(arrayOf("*/*"))
                                    }
                                },
                                onPickSessionReference = { viewModel.selectReferenceFromSessionSource(it) },
                                onComputeDifference = { viewModel.computeAudioDifference() },
                                onLoadDiffToDeck = { viewModel.loadDiffResultToDeck() },
                                onUpdateMixParams = { p, s, o, lim -> viewModel.updateMixParams(p, s, o, lim) },
                                onApplyMix = { viewModel.applyMixWithReference() },
                                onSetMonoMode = { viewModel.setMonoFoldMode(it) },
                                onApplyMixToMono = { viewModel.applyMixToMono() },
                                onSetStereoParams = { m, p -> viewModel.setStereoSplitParams(m, p) },
                                onApplySplitToStereo = { viewModel.applySplitToStereo() },
                                onSetRateParams = { sr, reinterp -> viewModel.setRateConversionParams(sr, reinterp) },
                                onApplyConvertRate = { viewModel.applyConvertSampleRate() },
                                onSetFormatParams = { fmt, dith -> viewModel.setFormatConversionParams(fmt, dith) },
                                onApplyConvertFormat = { viewModel.applyConvertSampleFormat() },
                                onAddPluginToChain = { viewModel.addPluginToChain(it) },
                                onRemovePluginFromChain = { viewModel.removePluginFromChain(it) },
                                onMovePluginInChain = { id, d -> viewModel.movePluginInChain(id, d) },
                                onTogglePluginBypass = { viewModel.togglePluginBypass(it) },
                                onUpdatePluginWetMix = { id, w -> viewModel.updatePluginWetMix(id, w) },
                                onUpdatePluginParam = { id, k, v -> viewModel.updatePluginParam(id, k, v) },
                                onBouncePluginChain = { viewModel.bouncePluginChainToDryBuffer() },
                                onInstallCatalogPlugin = { viewModel.installCatalogPlugin(it) },
                                onUninstallCustomPlugin = { viewModel.uninstallCustomPlugin(it) },
                                onImportPluginOrIrFile = {
                                    try {
                                        importPluginBundleLauncher.launch(arrayOf("*/*"))
                                    } catch (ex: Exception) {
                                        Toast.makeText(context, "No file picker found: ${ex.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )

                            StudioSpoke.TUNER -> TunerSpokeScreen(
                                tunerState = tunerState,
                                a4ReferenceHz = rigConfig.a4ReferenceHz,
                                hasDeckAudio = hasAudio,
                                onSelectSource = { viewModel.setTunerSource(it) },
                                onSelectPreset = { viewModel.setTuningPreset(it) },
                                onSetA4Reference = { viewModel.setA4Reference(it) },
                                onAnalyzeDeckWindow = { viewModel.analyzeActiveBufferWindowForTuner() },
                                onPlayReferenceNote = { viewModel.playTunerReferencePitch(it) }
                            )

                            StudioSpoke.VMC -> VmcSpokeScreen(
                                vmcState = vmcState,
                                onSelectBank = { viewModel.vmcSelectBank(it) },
                                onSelectChannel64 = { viewModel.vmcSelectChannel64(it) },
                                onSetRouteMode = { viewModel.vmcSetRouteMode(it) },
                                onEmitControl = { type, num, norm, ch -> viewModel.vmcEmitControl(type, num, norm, ch) },
                                onArmLearnRule = { viewModel.vmcArmLearnRule(it) },
                                onArmLearnNew = { viewModel.vmcArmLearnNew(it) },
                                onUpsertRule = { viewModel.vmcUpsertRule(it) },
                                onRemoveRule = { viewModel.vmcRemoveRule(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioSpokeBottomDock(
    selectedSpoke: StudioSpoke,
    dockSpokes: List<StudioSpoke>,
    onSelectSpoke: (StudioSpoke) -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleDock = dockSpokes.ifEmpty { StudioSpoke.entries }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = GunmetalDeck,
        border = BorderStroke(1.dp, HairlineDivider),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            visibleDock.forEach { spoke ->
                val isSelected = selectedSpoke == spoke
                Surface(
                    modifier = Modifier
                        .widthIn(min = 56.dp)
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectSpoke(spoke) }
                        .testTag("nav_tab_${spoke.name.lowercase()}"),
                    color = if (isSelected) NeonCyan.copy(alpha = 0.22f) else ObsidianChassis.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isSelected) NeonCyan else HairlineDivider.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = spokeIcon(spoke),
                            contentDescription = spoke.fullTitle,
                            tint = if (isSelected) NeonCyan else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = spoke.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) NeonCyan else TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun spokeIcon(spoke: StudioSpoke) = when (spoke) {
    StudioSpoke.BOOTH -> Icons.Filled.Hub
    StudioSpoke.DECK -> Icons.Filled.GraphicEq
    StudioSpoke.SAMPLES -> Icons.Filled.Loop
    StudioSpoke.INSTRUMENTS -> Icons.Filled.Piano
    StudioSpoke.TRACKS -> Icons.Filled.ViewTimeline
    StudioSpoke.FX_RACK -> Icons.Filled.Tune
    StudioSpoke.LIVE -> Icons.Filled.ElectricBolt
    StudioSpoke.TOOLS -> Icons.Filled.Build
    StudioSpoke.TUNER -> Icons.Filled.MusicNote
    StudioSpoke.VMC -> Icons.Filled.Piano
}
