package com.samplfie.desktop

import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.ui.ChromaticTunerPanel
import com.samplfie.desktop.ui.CircularHubSidebar
import com.samplfie.desktop.ui.DesktopCollabDialog
import com.samplfie.desktop.ui.DesktopSecretsVaultDialog
import com.samplfie.desktop.ui.DesktopStemSeparationPanel
import com.samplfie.desktop.ui.FxAndStompPanel
import com.samplfie.desktop.ui.InstrumentsSeqPanel
import com.samplfie.desktop.ui.LiveStagePanel
import com.samplfie.desktop.ui.NetLinkPanel
import com.samplfie.desktop.ui.ProductionBoothPanel
import com.samplfie.desktop.ui.SamplesLooperPanel
import com.samplfie.desktop.ui.SignalEditorPanel
import com.samplfie.desktop.ui.StudioTheme
import com.samplfie.desktop.ui.TracksTimelinePanel
import com.samplfie.desktop.ui.VmcMidiPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Samplfie2 Linux Studio — Equal Peer Workstation with all Studio Spokes,
 * Loop Persistence Through Edits/Extractions, Session Handoff, Chromatic YIN Tuner,
 * and 64-Channel Mappable Virtual MIDI Controller (VMC).
 */
class SamplfieDesktopFrame(
    private val controller: DesktopStudioController = DesktopStudioController()
) : JFrame("Samplfie2 Linux Studio — Equal Workstation & 64-Ch VMC") {

    private val cardLayout = CardLayout()
    private val cardHost = JPanel(cardLayout).apply {
        background = StudioTheme.ObsidianChassis
    }

    private val topStatusLabel = StudioTheme.hardwareLabel("READY", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val stationTitleLabel = StudioTheme.hardwareLabel("SIGNAL & WAV DECK EDITOR", StudioTheme.TextPrimary, StudioTheme.TitleFont)
    private val topBandBtn = StudioTheme.hardwareButton("BAND (0/4)", StudioTheme.EmeraldSignal) { openCollabDialog() }
    private val topSecretsBtn = StudioTheme.hardwareButton("KEYS VAULT", StudioTheme.AmberHot) { openSecretsVaultDialog() }
    private val navPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply { isOpaque = false }
    private var lastVisibleNav: List<DesktopStation> = emptyList()
    private var showingAllStemsConfirmModal = false

    private val hubSidebar = CircularHubSidebar(
        onSelectStation = { controller.selectStation(it) },
        onTogglePlay = { controller.soundEngine.togglePlay() },
        onStop = { controller.soundEngine.stopPlayback() },
        onToggleLoop = { controller.soundEngine.setLooping(!controller.playbackState.value.isLooping) },
        onToggleMic = { controller.toggleMicRecording() },
        onToggleStompBypass = { controller.toggleMasterStompBypass() },
        onPushHandoff = { controller.pushSessionToPeer() },
        onGrabHandoff = { controller.grabSessionFromPeer() },
        onOpenWav = { chooseAndOpenWav() },
        onSaveWav = { chooseAndSaveWav() },
        onExportMp3 = { chooseAndExportMp3() },
        onMasterVolumeChanged = { controller.setMasterVolume(it) },
        onToggleMasterMute = { controller.toggleMasterMute() },
        onOpenCollabDialog = { openCollabDialog() },
        onToggleBandRec = { controller.toggleLiveBandRecording() }
    )

    private val boothPanel = ProductionBoothPanel(
        onSelectStation = { controller.selectStation(it) },
        onUpdateWorkspace = { controller.updateWorkspaceConfig(it) },
        onTogglePinStation = { controller.toggleStationPinned(it) },
        onToggleRadialSpoke = { controller.toggleRadialSpoke(it) },
        onPushHandoff = { controller.pushSessionToPeer() },
        onGrabHandoff = { controller.grabSessionFromPeer() },
        onApplyPendingHandoff = { controller.applyPendingHandoff() },
        onToggleSyncedPlay = { controller.arrangement.toggleSyncedTransport() },
        onOpenSecretsVault = { openSecretsVaultDialog() },
        onToggleDisbandStem = { controller.toggleDisbandStem(it) },
        onApplyStemPreset = { controller.applyStemPreset(it) },
        onSetStemScopeSelectionOnly = { controller.setStemUseSelectionOnly(it) },
        onRequestStemSeparation = { triggerStemSeparationWithPrompt() },
        onCancelStemSeparation = { controller.cancelStemSeparation() },
        onLoadStemToDeck = { controller.loadSeparatedStemToDeck(it) },
        onSendStemToLoop = { stemId, slotId -> controller.sendSeparatedStemToLooper(stemId, slotId) },
        onAddStemToTrack = { stemId, trackId -> controller.addSeparatedStemToTimeline(stemId, trackId) },
        onSendAllStemsToTracks = { controller.sendAllSeparatedStemsToTimeline() },
        onSendAllStemsToLooper = { controller.sendAllSeparatedStemsToLooper() },
        onSaveStemWav = { stemId -> chooseAndSaveStemWav(stemId) },
        onOpenCollabDialog = { openCollabDialog() },
        onToggleBandRec = { controller.toggleLiveBandRecording() }
    )

    private val editorPanel = SignalEditorPanel(
        onVisualizerMode = { controller.setVisualizerMode(it) },
        onSelectionChange = { s, e -> controller.updateSelection(s, e) },
        onSeekPlayhead = { controller.soundEngine.seekTo(it) },
        onTogglePlaySelection = { controller.togglePlaySelectionOnly() },
        onZeroCrossSnap = { controller.snapSelectionZeroCrossing() },
        onExtract = { controller.extractSelection() },
        onApplyFades = { fin, fout -> controller.applyFades(fin, fout) },
        onReverse = { controller.reverseSignal() },
        onDcFix = { controller.removeDcOffset() },
        onNormalize = { controller.normalizeSignal() },
        onUndo = { controller.undoEdit() },
        onReset = { controller.resetToOriginal() },
        onGenerateSignal = { type, hz -> controller.generateTestSignal(type, hz) }
    )

    private val samplesPanel = SamplesLooperPanel(
        onToggleLooperPlay = { controller.arrangement.toggleLooperPlayback() },
        onBpmChanged = { controller.arrangement.setBpm(it) },
        onBounceLooperToDeck = { controller.bounceLooperToEditor() },
        onSendDeckToSlot = { controller.sendEditorToLoopSlot(it) },
        onToggleSlotActive = { controller.arrangement.toggleLoopSlotActive(it) },
        onToggleSlotMute = { controller.arrangement.toggleLoopSlotMute(it) },
        onCycleSlotBars = { slot, bars -> controller.arrangement.setLoopSlotBars(slot, bars) },
        onClearSlot = { controller.arrangement.clearLoopSlot(it) },
        onLoadSlotIntoRefiner = { controller.arrangement.loadLoopSlotIntoRefiner(it, controller.uiState.value.processedBuffer) },
        onRefinerSelection = { s, e -> controller.arrangement.updateLoopRefinerSelection(s, e) },
        onSnapZeroCross = { controller.arrangement.snapLoopSelectionZeroCross() },
        onTrimLoop = { controller.arrangement.trimLoopRefiner() },
        onApplyFades = { fin, fout -> controller.arrangement.applyLoopRefinerFades(fin, fout) },
        onReverseLoop = { controller.arrangement.reverseLoopRefiner() },
        onNormalizeLoop = { controller.arrangement.normalizeLoopRefiner() },
        onRemoveDcLoop = { controller.arrangement.removeDcLoopRefiner() },
        onResetLoop = { controller.arrangement.resetLoopRefiner() },
        onAuditionLoop = { controller.auditionRefinerLoop() },
        onCommitRefiner = { controller.arrangement.commitRefinerToSlot() },
        onSendRefinerToDeck = { controller.sendRefinerToDeck() }
    )

    private val instrumentsPanel = InstrumentsSeqPanel(
        onToggleSeqPlay = { controller.arrangement.toggleSequencerPlayback() },
        onTriggerPad = { controller.triggerSeqPadOneShot(it) },
        onToggleStep = { lane, step -> controller.arrangement.toggleSequencerStep(lane, step) },
        onToggleLaneMute = { controller.arrangement.toggleSequencerLaneMute(it) },
        onAssignDeckToLane = { controller.assignEditorToSeqLane(it) },
        onSetBpm = { controller.arrangement.setBpm(it) },
        onSetSwing = { controller.arrangement.setSequencerSwing(it) },
        onBounceSeqToTimeline = { controller.bounceSequencerToTimeline() },
        onRefreshMidi = { controller.refreshMidiPorts() },
        onOpenVmcMapper = { controller.selectStation(DesktopStation.VMC) },
        onToggleCompanionOverdubRx = { controller.arrangement.toggleCompanionOverdubRx() },
        onClearPattern = { controller.arrangement.clearSequencerPattern() },
        onUndoPattern = { controller.arrangement.undoSequencerPattern() },
        onSetDrumStyle = { controller.setDrumKitStyle(it) },
        onApplyDrumPreset = { controller.applyDrumKitPreset(it) },
        onToggleDrumArrangeMode = { controller.toggleDrumArrangeMode() },
        onFlipDrumKitLeftHanded = { controller.flipDrumKitLeftHanded() },
        onSelectDrumSlot = { controller.selectDrumSlot(it) },
        onSetTargetBeater = { controller.setTargetBeaterForMount(it) },
        onHitDrumPiece = { slotId, beater -> controller.hitDrumPiece(slotId, beater) },
        onHitDrumAddon = { controller.hitDrumAddon(it) },
        onMoveDrumPiece = { slotId, x, y -> controller.moveDrumPieceOnStage(slotId, x, y) },
        onAddDrumPiece = { controller.addMainDrumPiece(it) },
        onSwapDrumPiece = { slotId, compId -> controller.swapMainDrumPiece(slotId, compId) },
        onRemoveDrumPiece = { controller.removeMainDrumPiece(it) },
        onUpdateDrumPieceParams = { slotId, pitch, decay, vol, muted ->
            controller.updateDrumPieceParams(slotId, pitch, decay, vol, muted)
        },
        onAttachDrumAddon = { addonType, targetSlot, beater ->
            controller.attachDrumAddon(addonType, targetSlot, beater)
        },
        onRemoveDrumAddon = { controller.removeDrumAddon(it) },
        onToggleDrumAddonCoupled = { controller.toggleDrumAddonCoupled(it) },
        onSetDrumAddonStyle = { id, style -> controller.setDrumAddonStyle(id, style) },
        onSetDrumAddonBeaterSide = { id, side -> controller.setDrumAddonBeaterSide(id, side) },
        onRouteDrumPieceToSeqLane = { slotId, laneId, beater ->
            controller.sendDrumPieceToSeqLane(slotId, laneId, beater)
        },
        onSendDrumPieceToDeck = { slotId, beater ->
            controller.sendDrumPieceToDeck(slotId, beater)
        }
    )

    private val tracksPanel = TracksTimelinePanel(
        onToggleSyncAll = { controller.arrangement.toggleSyncedTransport() },
        onToggleTimelinePlay = { controller.arrangement.toggleTimelinePlayback() },
        onSeekPlayheadSec = { controller.arrangement.seekSyncedPlayhead(it) },
        onSetBpm = { controller.arrangement.setBpm(it) },
        onAddDeckToTrack = { controller.addEditorClipToTimeline(it) },
        onAddLooperMixToTrack2 = { controller.addLooperMixToTimeline() },
        onAddSeqBeatToTrack3 = { controller.bounceSequencerToTimeline() },
        onMixdownToDeck = { controller.bounceTimelineToEditor() },
        onToggleMute = { controller.arrangement.toggleTimelineMute(it) },
        onToggleSolo = { controller.arrangement.toggleTimelineSolo(it) },
        onNudgeClip = { trackId, clipId, delta -> controller.arrangement.nudgeClip(trackId, clipId, delta) },
        onRemoveClip = { trackId, clipId -> controller.arrangement.removeTimelineClip(trackId, clipId) }
    )

    private val fxPanel = FxAndStompPanel(
        onApplyPreset = { controller.applyPreset(it) },
        onUpdateFx = { controller.updateFx(it) },
        onUpdateStomp = { controller.updateStomp(it) },
        onToggleMasterStomp = { controller.toggleMasterStompBypass() }
    )

    private val livePanel = LiveStagePanel(
        onSetLiveMode = { controller.setLiveSessionMode(it) },
        onToggleStompBypass = { controller.toggleMasterStompBypass() },
        onUpdateStomp = { controller.updateStomp(it) },
        onXyDrag = { x, y -> controller.onStompXyDrag(x, y) },
        onTapTempo = { controller.tapTempo() },
        onToggleSyncedPlay = { controller.arrangement.toggleSyncedTransport() },
        onExportWav = { chooseAndSaveWav() },
        onExportMp3 = { chooseAndExportMp3() },
        onPushHandoff = { controller.pushSessionToPeer() },
        onOpenCollabDialog = { openCollabDialog() },
        onToggleBandRec = { controller.toggleLiveBandRecording() }
    )

    private val tunerPanel = ChromaticTunerPanel(
        onSelectSource = { controller.setTunerSource(it) },
        onSelectPreset = { controller.setTuningPreset(it) },
        onSetA4Reference = { controller.setA4Reference(it) },
        onAnalyzeBufferNow = { controller.analyzeActiveBufferWindowForTuner() },
        onPlayReferencePitch = { hz ->
            controller.soundEngine.playReferenceTone(hz, 800, controller.uiState.value.rigConfig.sampleRate)
        }
    )

    private val vmcPanel = VmcMidiPanel(
        onSelectBank = { controller.vmcEngine.selectBank(it) },
        onSelectChannel64 = { controller.vmcEngine.selectChannel64(it) },
        onSetRouteMode = { controller.vmcEngine.setRouteMode(it) },
        onEmitControl = { typ, num, norm, ch -> controller.vmcEngine.emitVirtualControl(typ, num, norm, ch) },
        onArmLearnRule = { controller.vmcEngine.armLearnForRule(it) },
        onArmLearnNew = { controller.vmcEngine.armLearnNewMapping(it) },
        onUpsertRule = { controller.vmcEngine.upsertMapping(it) },
        onRemoveRule = { controller.vmcEngine.removeMapping(it) },
        onRefreshHardwareMidi = { controller.refreshMidiPorts() }
    )

    private val netPanel = NetLinkPanel(
        onUpdateConfig = { controller.updateRigConfig(it) },
        onToggleListener = { controller.toggleNetworkListener() },
        onCaptureRxToEditor = { controller.captureNetworkStreamToEditor() },
        onTransmitActive = { controller.transmitActiveOverNetwork() },
        onPushHandoff = { controller.pushSessionToPeer() },
        onGrabHandoff = { controller.grabSessionFromPeer() },
        onOpenCollabDialog = { openCollabDialog() },
        onToggleBandRec = { controller.toggleLiveBandRecording() }
    )

    private val refreshTimer = Timer(50) { syncUi() }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(1140, 760)
        size = Dimension(1360, 860)
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        jMenuBar = buildLinuxMenuBar()
        add(buildTopHardwareRack(), BorderLayout.NORTH)
        add(hubSidebar, BorderLayout.WEST)

        cardHost.add(boothPanel, DesktopStation.BOOTH.name)
        cardHost.add(editorPanel, DesktopStation.EDITOR.name)
        cardHost.add(samplesPanel, DesktopStation.SAMPLES.name)
        cardHost.add(instrumentsPanel, DesktopStation.INSTRUMENTS.name)
        cardHost.add(tracksPanel, DesktopStation.TRACKS.name)
        cardHost.add(fxPanel, DesktopStation.FX_STOMP.name)
        cardHost.add(livePanel, DesktopStation.LIVE.name)
        cardHost.add(tunerPanel, DesktopStation.TUNER.name)
        cardHost.add(vmcPanel, DesktopStation.VMC.name)
        cardHost.add(netPanel, DesktopStation.NET_LINK.name)
        add(cardHost, BorderLayout.CENTER)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                refreshTimer.stop()
                controller.shutdown()
            }
        })

        syncUi()
        refreshTimer.start()
        setLocationRelativeTo(null)
    }

    private fun buildTopHardwareRack(): JPanel = JPanel(BorderLayout(8, 4)).apply {
        background = StudioTheme.GunmetalDeck
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, StudioTheme.HairlineDivider),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)
        )
        val topRow = JPanel(BorderLayout(12, 0)).apply {
            isOpaque = false
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("SAMPLFIE2 LINUX STUDIO", StudioTheme.NeonCyan, StudioTheme.TitleFont))
                add(stationTitleLabel)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(topBandBtn)
                add(StudioTheme.hardwareButton("STEMS (DISBAND)", StudioTheme.MagentaFx) {
                    controller.selectStation(DesktopStation.BOOTH)
                })
                add(topSecretsBtn)
            }
            add(left, BorderLayout.WEST)
            add(topStatusLabel, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
        val navScroll = JScrollPane(navPanel, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            preferredSize = Dimension(1200, 38)
        }
        add(topRow, BorderLayout.NORTH)
        add(navScroll, BorderLayout.CENTER)
    }

    private fun rebuildNavButtons(visible: List<DesktopStation>) {
        lastVisibleNav = visible
        navPanel.removeAll()
        val withBooth = if (visible.contains(DesktopStation.BOOTH)) visible else listOf(DesktopStation.BOOTH) + visible
        withBooth.forEach { st ->
            navPanel.add(StudioTheme.hardwareButton(st.shortLabel, ProductionBoothPanel.stationAccent(st)) {
                controller.selectStation(st)
            })
        }
        navPanel.revalidate()
        navPanel.repaint()
    }

    private fun buildLinuxMenuBar(): JMenuBar = JMenuBar().apply {
        background = StudioTheme.GunmetalDeck
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, StudioTheme.HairlineDivider)

        val fileMenu = JMenu("File").apply {
            foreground = StudioTheme.TextPrimary
            add(JMenuItem("Open .WAV…").apply { addActionListener { chooseAndOpenWav() } })
            add(JMenuItem("Save .WAV As…").apply { addActionListener { chooseAndSaveWav() } })
            add(JMenuItem("Export .MP3 As…").apply { addActionListener { chooseAndExportMp3() } })
            addSeparator()
            add(JMenuItem("Quit").apply { addActionListener { dispose() } })
        }
        val stationMenu = JMenu("Stations").apply {
            foreground = StudioTheme.TextPrimary
            DesktopStation.entries.forEach { st ->
                add(JMenuItem(st.fullTitle).apply { addActionListener { controller.selectStation(st) } })
            }
        }
        val signalMenu = JMenu("Signal").apply {
            foreground = StudioTheme.TextPrimary
            add(JMenuItem("Extract Selection").apply { addActionListener { controller.extractSelection() } })
            add(JMenuItem("Snap Zero-Crossings").apply { addActionListener { controller.snapSelectionZeroCrossing() } })
            add(JMenuItem("Peak Normalize (-0.35 dBFS)").apply { addActionListener { controller.normalizeSignal() } })
            add(JMenuItem("Remove DC Offset").apply { addActionListener { controller.removeDcOffset() } })
        }
        val peerMenu = JMenu("Peer & VMC").apply {
            foreground = StudioTheme.TextPrimary
            add(JMenuItem("Push Session -> Peer (SMH2)").apply { addActionListener { controller.pushSessionToPeer() } })
            add(JMenuItem("Grab Session From Peer (SMHR)").apply { addActionListener { controller.grabSessionFromPeer() } })
            addSeparator()
            add(JMenuItem("Open 64-Ch VMC Controller").apply { addActionListener { controller.selectStation(DesktopStation.VMC) } })
            add(JMenuItem("Rescan Linux ALSA MIDI Ports").apply { addActionListener { controller.refreshMidiPorts() } })
        }
        val vaultMenu = JMenu("Stems & Vault").apply {
            foreground = StudioTheme.TextPrimary
            add(JMenuItem("Secrets & Keys Vault…").apply { addActionListener { openSecretsVaultDialog() } })
            add(JMenuItem("Separate Stems (Disband API)…").apply { addActionListener { triggerStemSeparationWithPrompt() } })
        }
        add(fileMenu)
        add(stationMenu)
        add(signalMenu)
        add(peerMenu)
        add(vaultMenu)
    }

    private fun syncUi() {
        val ui = controller.uiState.value
        val play = controller.playbackState.value
        val mic = controller.micState.value
        val net = controller.netState.value
        val looper = controller.looperState.value
        val refiner = controller.loopRefinerState.value
        val seq = controller.sequencerState.value
        val tl = controller.timelineState.value
        val sync = controller.syncedPlayhead.value
        val vmc = controller.vmcState.value
        val stems = controller.stemState.value
        val secrets = controller.secretsState.value
        val collabPeers = controller.collabPeers.value
        val linkedCount = collabPeers.count { it.isConnected }

        if (ui.workspaceConfig.visibleStations != lastVisibleNav) {
            rebuildNavButtons(ui.workspaceConfig.visibleStations)
        }

        topBandBtn.text = if (ui.bandRecState.isRecording) "REC $linkedCount/4" else "BAND $linkedCount/4 (${ui.bandRecState.myStyle.shortTag})"
        topBandBtn.foreground = if (ui.bandRecState.isRecording) StudioTheme.TacCoral else if (linkedCount > 0) StudioTheme.EmeraldSignal else StudioTheme.NeonCyan
        topSecretsBtn.text = if (secrets.hasDisbandKey) "KEYS OK" else "SET KEYS"
        topSecretsBtn.foreground = if (secrets.hasDisbandKey) StudioTheme.EmeraldSignal else StudioTheme.AmberHot
        stationTitleLabel.text = "· ${ui.station.fullTitle}"
        topStatusLabel.text = ui.statusLine
        cardLayout.show(cardHost, ui.station.name)

        hubSidebar.updateState(ui, play, mic, net, collabPeers)
        boothPanel.updateState(ui, play, net, sync, stems, secrets, collabPeers)
        editorPanel.updateState(ui, play)
        samplesPanel.updateState(looper, refiner)
        instrumentsPanel.updateState(seq, vmc, controller.customDrumKitState.value)
        tracksPanel.updateState(tl, sync)
        fxPanel.updateState(ui)
        livePanel.updateState(ui, sync, collabPeers)
        tunerPanel.updateState(ui)
        vmcPanel.updateState(vmc)
        netPanel.updateState(ui, net, collabPeers)
    }

    private fun openCollabDialog() {
        val ui = controller.uiState.value
        DesktopCollabDialog(
            owner = this,
            defaultHost = ui.rigConfig.txHost,
            defaultPort = ui.rigConfig.txPort,
            nodeName = ui.workspaceConfig.nodeName,
            onRefreshDiscovery = { controller.refreshNearbyDiscovery() },
            onSelectMyStyle = { st, slot -> controller.setMyCollabStyle(st, slot) },
            onConnectPeer = { host, port, name, plat, st ->
                controller.connectCollabPeer(host, port, name, plat, st)
            },
            onDisconnectPeer = { id -> controller.disconnectCollabPeer(id) },
            onUpdatePeerStyleAndTrack = { id, st, slot -> controller.updateCollabPeerStyleAndTrack(id, st, slot) },
            onToggleBandRec = { controller.toggleLiveBandRecording() },
            peersProvider = { controller.collabPeers.value },
            bandStateProvider = { controller.uiState.value.bandRecState }
        ).isVisible = true
    }

    private fun openSecretsVaultDialog() {
        DesktopSecretsVaultDialog(
            owner = this,
            initialState = controller.secretsState.value,
            onSaveSecret = { k, v -> controller.saveSecretKey(k, v) },
            onDeleteSecret = { k -> controller.deleteSecretKey(k) },
            currentStateProvider = { controller.secretsState.value }
        ).isVisible = true
    }

    private fun triggerStemSeparationWithPrompt() {
        controller.requestStemSeparation()
        val st = controller.stemState.value
        if (st.showAllStemsConfirmation && !showingAllStemsConfirmModal) {
            showingAllStemsConfirmModal = true
            try {
                val ok = DesktopStemSeparationPanel.showAllStemsConfirmDialog(
                    parent = this,
                    estimatedCredits = st.pendingAllStemsEstimatedCredits,
                    durationSec = st.pendingAudioDurationSec
                )
                if (ok) {
                    controller.confirmSeparateAllStems()
                } else {
                    controller.dismissAllStemsConfirmation()
                }
            } finally {
                showingAllStemsConfirmModal = false
            }
        } else if (st.errorMessage?.contains("DISBAND_API_KEY MISSING", ignoreCase = true) == true) {
            openSecretsVaultDialog()
        }
    }

    private fun chooseAndOpenWav() {
        val chooser = JFileChooser(controller.defaultExportDir).apply {
            fileFilter = FileNameExtensionFilter("WAV Audio (*.wav)", "wav")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            controller.loadWavFile(chooser.selectedFile)
        }
    }

    private fun chooseAndSaveWav() {
        val chooser = JFileChooser(controller.defaultExportDir).apply {
            selectedFile = File(controller.defaultExportDir, "samplfie2_signal.wav")
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            val f = if (chooser.selectedFile.extension.equals("wav", true)) chooser.selectedFile
            else File(chooser.selectedFile.absolutePath + ".wav")
            controller.exportWav(f)
        }
    }

    private fun chooseAndSaveStemWav(stemId: String) {
        val chooser = JFileChooser(controller.defaultExportDir).apply {
            selectedFile = File(controller.defaultExportDir, "samplfie2_${stemId}_stem.wav")
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            val f = if (chooser.selectedFile.extension.equals("wav", true)) chooser.selectedFile
            else File(chooser.selectedFile.absolutePath + ".wav")
            controller.exportSeparatedStemWav(stemId, f)
        }
    }

    private fun chooseAndExportMp3() {
        val chooser = JFileChooser(controller.defaultExportDir).apply {
            selectedFile = File(controller.defaultExportDir, "samplfie2_signal.mp3")
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            val f = if (chooser.selectedFile.extension.equals("mp3", true)) chooser.selectedFile
            else File(chooser.selectedFile.absolutePath + ".mp3")
            controller.exportMp3(f).onFailure {
                JOptionPane.showMessageDialog(this, it.message ?: "Export failed", "Export Error", JOptionPane.ERROR_MESSAGE)
            }
        }
    }
}

fun main() {
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")
    SwingUtilities.invokeLater {
        runCatching {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
        }
        SamplfieDesktopFrame().isVisible = true
    }
}
