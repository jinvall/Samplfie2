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
import com.samplfie.desktop.ui.StudioWrapLayout
import com.samplfie.desktop.ui.ToolsAndPluginsPanel
import com.samplfie.desktop.ui.TracksTimelinePanel
import com.samplfie.desktop.ui.VmcMidiPanel
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.math.roundToInt

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
    private val scaleOutBtn = StudioTheme.hardwareButton("A-", StudioTheme.TextSecondary) { StudioTheme.stepScale(-0.25f) }
    private val scaleInBtn = StudioTheme.hardwareButton("A+", StudioTheme.NeonCyan) { StudioTheme.stepScale(0.25f) }
    private val scaleOptions = arrayOf("AUTO", "75%", "100%", "125%", "150%", "175%", "200%", "225%", "250%", "300%")
    private var updatingScaleCombo = false
    private val scalePresetBox = StudioTheme.styleComboBox(JComboBox(scaleOptions), basePt = 11.5f).apply {
        addActionListener {
            if (updatingScaleCombo) return@addActionListener
            val sel = selectedItem as? String ?: return@addActionListener
            if (sel == "AUTO") {
                StudioTheme.resetAutoScale()
            } else {
                val pct = sel.removeSuffix("%").toFloatOrNull() ?: return@addActionListener
                StudioTheme.setUiScale(pct / 100f, persist = true)
            }
        }
    }
    private val navPanel = JPanel(StudioWrapLayout(FlowLayout.RIGHT, 4, 2)).apply { isOpaque = false }
    private var lastVisibleNav: List<DesktopStation> = emptyList()
    private var showingAllStemsConfirmModal = false
    private val scaleChangeListener: (Float) -> Unit = { newScale ->
        SwingUtilities.invokeLater {
            syncScaleControls(newScale)
            resizeFrameForScaleIfNeeded()
        }
    }
    private val mouseWheelZoomListener = AWTEventListener { ev ->
        val mwe = ev as? MouseWheelEvent ?: return@AWTEventListener
        if (mwe.isControlDown && SwingUtilities.isDescendingFrom(mwe.component, this)) {
            val delta = if (mwe.wheelRotation < 0) 0.15f else -0.15f
            StudioTheme.stepScale(delta)
            mwe.consume()
        }
    }

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
        onGenerateSignal = { type, hz -> controller.generateTestSignal(type, hz) },
        onSpecFreqRange = { lo, hi -> controller.updateSpecFreqRange(lo, hi) },
        onOpenTools = { controller.selectStation(DesktopStation.TOOLS) },
        onFadesChange = { fin, fout -> controller.setDeckFades(fin, fout) },
        onToggleLiveMonitor = { controller.toggleLiveMonitor() },
        onToggleMonitorPassthrough = { controller.setMonitorPassthrough(it) },
        onMonitorGainChange = { controller.setMonitorGain(it) },
        onMonitorWindowSecChange = { controller.setMonitorWindowSec(it) },
        onToggleMonitorSyncToDeck = { controller.setLiveMonitorSyncToDeck(it) },
        onLoadLiveMonitorToDeck = { controller.loadLiveMonitorToDeck() }
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
        onSendRefinerToDeck = { controller.sendRefinerToDeck() },
        onSetRefinerFades = { fin, fout -> controller.arrangement.setLoopRefinerFades(fin, fout) },
        onToggleSlotLock = { controller.toggleLoopSlotLock(it) }
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
        },
        onToggleLaneLock = { controller.toggleSequencerLaneLock(it) }
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

    private val toolsPanel = ToolsAndPluginsPanel(
        onSelectTab = { controller.setToolsTab(it) },
        onVisualizerMode = { controller.setVisualizerMode(it) },
        onSelectionChange = { s, e -> controller.updateSelection(s, e) },
        onSeekPlayhead = { controller.soundEngine.seekTo(it) },
        onTogglePlay = { controller.soundEngine.togglePlay() },
        onStop = { controller.soundEngine.stopPlayback() },
        onToggleLoop = { controller.soundEngine.setLooping(!controller.playbackState.value.isLooping) },
        onUndo = { controller.undoEdit() },
        onSelectPalette = { controller.setSpectrogramPalette(it) },
        onApplyCustomHexStops = { controller.applyCustomTenStopGradient(it) },
        onSpecFreqRange = { lo, hi -> controller.updateSpecFreqRange(lo, hi) },
        onSetSpecAttenDb = { controller.setSpecDeleteAttenuation(it) },
        onApplySpectralDelete = { controller.applySpectralDelete() },
        onSetInvertScope = { controller.setInvertScope(it) },
        onSetInvertSelectionOnly = { controller.setInvertSelectionOnly(it) },
        onApplyInvert = { controller.applyInvertPolarity() },
        onBrowseRefFile = { chooseAndLoadReferenceWav() },
        onSelectSessionRef = { controller.selectReferenceFromSessionSource(it) },
        onComputeDiff = { controller.computeAudioDifference() },
        onLoadDiffToDeck = { controller.loadDiffResultToDeck() },
        onUpdateMixParams = { g1, g2, off, lim -> controller.updateMixParams(g1, g2, off, lim) },
        onApplyMix = { controller.applyMixWithReference() },
        onSetMonoMode = { controller.setMonoFoldMode(it) },
        onApplyMixToMono = { controller.applyMixToMono() },
        onSetStereoParams = { m, p -> controller.setStereoSplitParams(m, p) },
        onApplySplitToStereo = { controller.applySplitToStereo() },
        onSetRateParams = { sr, re -> controller.setRateConversionParams(sr, re) },
        onApplyConvertRate = { controller.applyConvertSampleRate() },
        onSetFormatParams = { fmt, dit -> controller.setFormatConversionParams(fmt, dit) },
        onApplyConvertFormat = { controller.applyConvertSampleFormat() },
        onAddPluginToChain = { controller.addPluginToChain(it) },
        onRemovePluginFromChain = { controller.removePluginFromChain(it) },
        onMovePluginInChain = { id, d -> controller.movePluginInChain(id, d) },
        onTogglePluginBypass = { controller.togglePluginBypass(it) },
        onUpdatePluginWetMix = { id, w -> controller.updatePluginWetMix(id, w) },
        onUpdatePluginParam = { id, k, v -> controller.updatePluginParam(id, k, v) },
        onBouncePluginChain = { controller.bouncePluginChainToDryBuffer() },
        onInstallCatalogPlugin = { controller.installCatalogPlugin(it) },
        onUninstallCustomPlugin = { controller.uninstallCustomPlugin(it) },
        onImportPluginOrIrFile = { chooseAndImportPluginOrIr() },
        onRescanLinuxPlugins = { controller.rescanNativeLinuxPlugins() },
        onSetLadspaUrl = { controller.setLadspaFeedUrl(it) },
        onFetchLadspa = { controller.fetchLadspaCatalog() },
        onInstallLadspa = { controller.installLadspaPlugin(it) }
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
        applyScaledWindowBounds(growOnly = false)
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
        cardHost.add(toolsPanel, DesktopStation.TOOLS.name)
        cardHost.add(tunerPanel, DesktopStation.TUNER.name)
        cardHost.add(vmcPanel, DesktopStation.VMC.name)
        cardHost.add(netPanel, DesktopStation.NET_LINK.name)
        add(cardHost, BorderLayout.CENTER)

        registerZoomShortcuts()
        StudioTheme.addScaleListener(scaleChangeListener)
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener(mouseWheelZoomListener, AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        }
        syncScaleControls(StudioTheme.uiScale)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                refreshTimer.stop()
                StudioTheme.removeScaleListener(scaleChangeListener)
                runCatching {
                    Toolkit.getDefaultToolkit().removeAWTEventListener(mouseWheelZoomListener)
                }
                controller.shutdown()
            }
        })

        syncUi()
        refreshTimer.start()
        setLocationRelativeTo(null)
    }

    private fun applyScaledWindowBounds(growOnly: Boolean) {
        if (GraphicsEnvironment.isHeadless()) return
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val maxW = (bounds.width * 0.96).toInt().coerceAtLeast(640)
        val maxH = (bounds.height * 0.94).toInt().coerceAtLeast(480)
        val minW = StudioTheme.s(980).coerceAtMost((maxW * 0.85).toInt())
        val minH = StudioTheme.s(640).coerceAtMost((maxH * 0.85).toInt())
        minimumSize = Dimension(minW, minH)

        val targetW = StudioTheme.s(1420).coerceIn(minW, maxW)
        val targetH = StudioTheme.s(900).coerceIn(minH, maxH)
        if (!growOnly || width < targetW || height < targetH) {
            size = Dimension(
                if (growOnly) maxOf(width, targetW).coerceAtMost(maxW) else targetW,
                if (growOnly) maxOf(height, targetH).coerceAtMost(maxH) else targetH
            )
        }
    }

    private fun resizeFrameForScaleIfNeeded() {
        applyScaledWindowBounds(growOnly = true)
        jMenuBar?.let { StudioTheme.applyMenuTypography(it) }
        revalidate()
        repaint()
    }

    private fun syncScaleControls(scale: Float) {
        updatingScaleCombo = true
        try {
            val label = "${(scale * 100f).roundToInt()}%"
            if (!StudioTheme.hasExplicitSavedScale) {
                scalePresetBox.selectedItem = "AUTO"
            } else if (scaleOptions.contains(label)) {
                scalePresetBox.selectedItem = label
            } else {
                scalePresetBox.selectedItem = scaleOptions.drop(1).minByOrNull {
                    kotlin.math.abs((it.removeSuffix("%").toFloatOrNull() ?: 100f) - scale * 100f)
                } ?: "AUTO"
            }
            scaleOutBtn.toolTipText = "Zoom Out (Ctrl+-) • Current $label"
            scaleInBtn.toolTipText = "Zoom In (Ctrl+=) • Current $label"
            scalePresetBox.toolTipText = "UI Scale ($label) • Ctrl+Wheel or Ctrl+/Ctrl-"
        } finally {
            updatingScaleCombo = false
        }
    }

    private fun registerZoomShortcuts() {
        val im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val am = rootPane.actionMap
        val mask = InputEvent.CTRL_DOWN_MASK
        listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, mask),
            KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, mask),
            KeyStroke.getKeyStroke(KeyEvent.VK_ADD, mask)
        ).forEach { ks -> im.put(ks, "zoomIn") }
        listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, mask),
            KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, mask)
        ).forEach { ks -> im.put(ks, "zoomOut") }
        listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_0, mask),
            KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0, mask)
        ).forEach { ks -> im.put(ks, "zoomAuto") }

        am.put("zoomIn", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { StudioTheme.stepScale(0.25f) }
        })
        am.put("zoomOut", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { StudioTheme.stepScale(-0.25f) }
        })
        am.put("zoomAuto", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { StudioTheme.resetAutoScale() }
        })
    }

    private fun buildTopHardwareRack(): JPanel = JPanel(BorderLayout(8, 4)).apply {
        background = StudioTheme.GunmetalDeck
        StudioTheme.setScaledPanelBorder(this, StudioTheme.HairlineDivider, 1, 6, 10)
        val topRow = JPanel(BorderLayout(12, 4)).apply {
            isOpaque = false
            val left = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 2).apply {
                add(StudioTheme.hardwareLabel("SAMPLFIE2 LINUX STUDIO", StudioTheme.NeonCyan, StudioTheme.TitleFont))
                add(stationTitleLabel)
            }
            val right = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 6, 2).apply {
                add(StudioTheme.hardwareLabel("SCALE:", StudioTheme.TextSecondary, StudioTheme.MonoSmallFont))
                add(scaleOutBtn)
                add(scalePresetBox)
                add(scaleInBtn)
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
        val navScroll = StudioTheme.styleScrollPane(
            JScrollPane(navPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED)
        ).apply {
            isOpaque = false
            viewport.isOpaque = false
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
            addSeparator()
            add(JMenuItem("Open Pro Audio Tools & Plugins…").apply { addActionListener { controller.selectStation(DesktopStation.TOOLS) } })
            add(JMenuItem("Rescan Linux LV2 / LADSPA / VST Plugins").apply { addActionListener { controller.rescanNativeLinuxPlugins() } })
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
        val viewMenu = JMenu("View / Scale").apply {
            foreground = StudioTheme.TextPrimary
            add(JMenuItem("Zoom In (+25%)   Ctrl+=").apply { addActionListener { StudioTheme.stepScale(0.25f) } })
            add(JMenuItem("Zoom Out (-25%)   Ctrl+-").apply { addActionListener { StudioTheme.stepScale(-0.25f) } })
            add(JMenuItem("Auto-Detect Monitor Scale   Ctrl+0").apply { addActionListener { StudioTheme.resetAutoScale() } })
            addSeparator()
            StudioTheme.UI_SCALE_PRESETS.forEach { preset ->
                val pct = (preset * 100f).roundToInt()
                add(JMenuItem("Scale $pct%").apply { addActionListener { StudioTheme.setUiScale(preset) } })
            }
        }
        add(fileMenu)
        add(stationMenu)
        add(signalMenu)
        add(peerMenu)
        add(vaultMenu)
        add(viewMenu)
        StudioTheme.applyMenuTypography(this)
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
        val tools = controller.toolsState.value
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
        editorPanel.updateState(ui, play, tools, mic)
        samplesPanel.updateState(looper, refiner)
        instrumentsPanel.updateState(seq, vmc, controller.customDrumKitState.value)
        tracksPanel.updateState(tl, sync)
        fxPanel.updateState(ui)
        livePanel.updateState(ui, sync, collabPeers)
        toolsPanel.updateState(ui, play, tools)
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

    private fun chooseAndLoadReferenceWav() {
        val chooser = JFileChooser(controller.defaultExportDir).apply {
            dialogTitle = "Select Reference B WAV Audio (for Diff / Mix)"
            fileFilter = FileNameExtensionFilter("WAV Audio (*.wav)", "wav")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            controller.loadReferenceFile(chooser.selectedFile)
        }
    }

    private fun chooseAndImportPluginOrIr() {
        val chooser = JFileChooser(controller.defaultExportDir).apply {
            dialogTitle = "Import .smpplugin.json Bundle or .WAV Impulse Response"
            fileFilter = FileNameExtensionFilter("Plugin JSON or WAV Impulse (*.json, *.wav)", "json", "wav")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            controller.importPluginOrIrFile(chooser.selectedFile).onFailure {
                JOptionPane.showMessageDialog(this, it.message ?: "Import failed", "Plugin / IR Error", JOptionPane.ERROR_MESSAGE)
            }
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
        StudioTheme.initForDesktop()
        SamplfieDesktopFrame().isVisible = true
    }
}
