package com.samplfie.desktop

import com.samplfie.desktop.audio.DesktopMicState
import com.samplfie.desktop.audio.DesktopMp3Exporter
import com.samplfie.desktop.audio.DesktopPlaybackState
import com.samplfie.desktop.audio.DesktopSpectrogramAnalyzer
import com.samplfie.desktop.audio.DesktopSpectrogramData
import com.samplfie.desktop.audio.DesktopSpectrogramPalette
import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.audio.LinuxSoundEngine
import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.audio.WaveformEnvelope
import com.samplfie.desktop.dsp.DesktopDspProcessor
import com.samplfie.desktop.midi.DesktopHardwareMidiManager
import com.samplfie.desktop.midi.DesktopVmcEngine
import com.samplfie.desktop.midi.VmcEngineState
import com.samplfie.desktop.midi.VmcEventPacket
import com.samplfie.desktop.midi.VmcMappingRule
import com.samplfie.desktop.midi.VmcMsgType
import com.samplfie.desktop.midi.VmcRouteMode
import com.samplfie.desktop.midi.VmcTarget
import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopFxState
import com.samplfie.desktop.model.DesktopLiveMode
import com.samplfie.desktop.model.DesktopPreset
import com.samplfie.desktop.model.DesktopPresetFactory
import com.samplfie.desktop.model.DesktopRigConfig
import com.samplfie.desktop.model.DesktopSampleFormat
import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.model.DesktopStompState
import com.samplfie.desktop.model.DesktopWorkspaceConfig
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.model.TunerInputSource
import com.samplfie.desktop.model.TunerReading
import com.samplfie.desktop.model.TuningPreset
import com.samplfie.desktop.model.VisualizerMode
import com.samplfie.desktop.network.CollabInstrumentStyle
import com.samplfie.desktop.network.CollabPeerDevice
import com.samplfie.desktop.network.CollabSyncCommand
import com.samplfie.desktop.network.LiveBandRecordingState
import com.samplfie.desktop.network.MAX_COLLAB_PEERS
import com.samplfie.desktop.network.NetBridgeState
import com.samplfie.desktop.network.SessionHandoffSnapshot
import com.samplfie.desktop.network.Smp2NetworkNode
import com.samplfie.desktop.plugins.DesktopPluginCatalogEntry
import com.samplfie.desktop.plugins.DesktopPluginCatalogRegistry
import com.samplfie.desktop.plugins.DesktopPluginHostEngine
import com.samplfie.desktop.plugins.DesktopPluginRepository
import com.samplfie.desktop.stems.DesktopDisbandClient
import com.samplfie.desktop.stems.DesktopDisbandHttpTransport
import com.samplfie.desktop.stems.DesktopDisbandStem
import com.samplfie.desktop.stems.DesktopSecretsState
import com.samplfie.desktop.stems.DesktopSecretsVault
import com.samplfie.desktop.stems.DesktopStemPreset
import com.samplfie.desktop.stems.DesktopStemState
import com.samplfie.desktop.drums.BeaterSide
import com.samplfie.desktop.drums.CustomDrumKitState
import com.samplfie.desktop.drums.DesktopDrumKitManager
import com.samplfie.desktop.drums.DrumAddonType
import com.samplfie.desktop.drums.DrumKitPreset
import com.samplfie.desktop.drums.DrumKitStyle
import com.samplfie.desktop.studio.DesktopArrangementEngine
import com.samplfie.desktop.studio.DesktopLoopRefinerState
import com.samplfie.desktop.studio.DesktopLooperState
import com.samplfie.desktop.studio.DesktopSequencerState
import com.samplfie.desktop.studio.DesktopSyncedPlayheadState
import com.samplfie.desktop.studio.DesktopTimelineState
import com.samplfie.desktop.tools.ChannelScope
import com.samplfie.desktop.tools.DesktopAudioToolsEngine
import com.samplfie.desktop.tools.DesktopToolsState
import com.samplfie.desktop.tools.DesktopToolsTab
import com.samplfie.desktop.tools.MonoFoldMode
import com.samplfie.desktop.tools.StereoSplitMode
import com.samplfie.desktop.tuner.PitchDetector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DesktopStudioUiState(
    val station: DesktopStation = DesktopStation.EDITOR,
    val visualizerMode: VisualizerMode = VisualizerMode.W_SPEC,
    val workspaceConfig: DesktopWorkspaceConfig = DesktopWorkspaceConfig(),
    val masterVolume: Float = 0.85f,
    val isMasterMuted: Boolean = false,
    val bandRecState: LiveBandRecordingState = LiveBandRecordingState(),
    val rawBuffer: DesktopAudioBuffer? = null,
    val dryBuffer: DesktopAudioBuffer? = null,
    val processedBuffer: DesktopAudioBuffer? = null,
    val dryEnvelope: WaveformEnvelope = WaveformEnvelope(FloatArray(0), FloatArray(0), FloatArray(0)),
    val processedEnvelope: WaveformEnvelope = WaveformEnvelope(FloatArray(0), FloatArray(0), FloatArray(0)),
    val staticSpectrum: FloatArray = FloatArray(48),
    val spectrogram: DesktopSpectrogramData = DesktopSpectrogramData.EMPTY,
    val selectionStart: Float = 0f,
    val selectionEnd: Float = 1f,
    val playSelectionOnly: Boolean = false,
    val canUndo: Boolean = false,
    val fadeInMs: Float = 10f,
    val fadeOutMs: Float = 15f,
    val generatorType: SignalWaveformType = SignalWaveformType.SINE,
    val generatorFreqHz: Double = 440.0,
    val fxState: DesktopFxState = DesktopFxState(),
    val stompState: DesktopStompState = DesktopStompState(),
    val rigConfig: DesktopRigConfig = DesktopRigConfig(),
    val tunerSource: TunerInputSource = TunerInputSource.DECK_BUFFER,
    val tuningPreset: TuningPreset = TuningPreset.CHROMATIC,
    val tunerReading: TunerReading = TunerReading(),
    val pendingHandoff: SessionHandoffSnapshot? = null,
    val statusLine: String = "READY — EQUAL WORKSTATION • LOAD .WAV, PLAY LOOP, OR LINK PEER"
)

/**
 * Central state controller for Samplfie2 Desktop Workstation.
 * Unifies all studio spokes (Booth, Deck/Signal Editor, Samples/Looper + Refiner, Instruments/Seq,
 * Synced Tracks, FX Rack, Live Stompbox, Chromatic Tuner, 64-Ch VMC MIDI, and 4-Device Collab Bridge).
 */
class DesktopStudioController {

    private val undoStack = ArrayDeque<DesktopAudioBuffer>()
    private var lastTapMs: Long = 0L
    private val stemExecutor = Executors.newSingleThreadExecutor()
    private var stemFuture: Future<*>? = null

    val defaultExportDir: File = File(System.getProperty("user.home"), "Music/Samplfie2").apply { mkdirs() }
    val secretsVault = DesktopSecretsVault()
    val pluginRepo = DesktopPluginRepository()
    @Volatile
    private var disbandClient = DesktopDisbandClient()

    private val _uiState = MutableStateFlow(DesktopStudioUiState())
    val uiState: StateFlow<DesktopStudioUiState> = _uiState.asStateFlow()

    private val _stemState = MutableStateFlow(DesktopStemState())
    val stemState: StateFlow<DesktopStemState> = _stemState.asStateFlow()

    private val _toolsState = MutableStateFlow(
        DesktopToolsState(
            installedPlugins = pluginRepo.loadAllPlugins(),
            nativeLinuxPlugins = pluginRepo.scanLinuxNativePlugins()
        )
    )
    val toolsState: StateFlow<DesktopToolsState> = _toolsState.asStateFlow()

    val secretsState: StateFlow<DesktopSecretsState> = secretsVault.state

    val arrangement = DesktopArrangementEngine()
    val drumKitManager = DesktopDrumKitManager()

    val soundEngine: LinuxSoundEngine = LinuxSoundEngine(
        onLiveSamplesForTuner = { monoChunk, sr ->
            val src = _uiState.value.tunerSource
            if (src == TunerInputSource.LIVE_MIC || (src == TunerInputSource.DECK_BUFFER && playbackState.value.isPlaying)) {
                analyzeSamplesForTuner(monoChunk, sr)
            }
        },
        onLiveRecordedAudio = { monoChunk, sr ->
            val band = _uiState.value.bandRecState
            if (band.isRecording) {
                netNode.sendCollabLiveChunk(monoChunk, sr, band.myStyle, band.myTrackSlot)
            }
        }
    )

    val vmcEngine: DesktopVmcEngine = DesktopVmcEngine(
        onExecuteTarget = { target, norm -> executeVmcTarget(target, norm) },
        onTransmitPeerVmc = { pkt ->
            netNode.sendVmcEvent(pkt, _uiState.value.rigConfig)
            hwMidi.sendToHardware(pkt)
        }
    )

    val hwMidi: DesktopHardwareMidiManager = DesktopHardwareMidiManager { pkt ->
        vmcEngine.processIncomingEvent(pkt, fromPeer = false)
    }

    val netNode: Smp2NetworkNode = Smp2NetworkNode(
        onStreamChunkForTuner = { monoChunk, sr ->
            if (_uiState.value.tunerSource == TunerInputSource.SMP2_STREAM) {
                analyzeSamplesForTuner(monoChunk, sr)
            }
        },
        onVmcEventReceived = { pkt ->
            vmcEngine.processIncomingEvent(pkt, fromPeer = true)
        },
        onSessionHandoffReceived = { snap ->
            if (_uiState.value.workspaceConfig.autoAcceptIncomingHandoff) {
                applySessionHandoff(snap)
            } else {
                _uiState.value = _uiState.value.copy(
                    pendingHandoff = snap,
                    statusLine = "HANDOFF READY FROM ${snap.senderName} — CLICK APPLY IN BOOTH/NET"
                )
            }
        },
        onHandoffRequestedByPeer = { host, replyPort ->
            val snap = captureSessionSnapshot()
            netNode.sendSessionHandoff(snap, _uiState.value.rigConfig.protocol, host, replyPort)
        },
        onCollabSyncCommandReceived = { cmd ->
            if (cmd.start) {
                if (!_uiState.value.bandRecState.isRecording) {
                    arrangement.setBpm(cmd.bpm)
                    startLiveBandRecording(broadcastToPeers = false, syncedTakeName = cmd.takeName)
                }
            } else {
                if (_uiState.value.bandRecState.isRecording) {
                    stopLiveBandRecording(broadcastStop = false)
                }
            }
        }
    )

    val playbackState: StateFlow<DesktopPlaybackState> = soundEngine.playbackState
    val micState: StateFlow<DesktopMicState> = soundEngine.micState
    val netState: StateFlow<NetBridgeState> = netNode.state
    val collabPeers: StateFlow<List<CollabPeerDevice>> = netNode.collabPeers
    val looperState: StateFlow<DesktopLooperState> = arrangement.looperState
    val loopRefinerState: StateFlow<DesktopLoopRefinerState> = arrangement.loopRefinerState
    val sequencerState: StateFlow<DesktopSequencerState> = arrangement.sequencerState
    val timelineState: StateFlow<DesktopTimelineState> = arrangement.timelineState
    val syncedPlayhead: StateFlow<DesktopSyncedPlayheadState> = arrangement.syncedPlayhead
    val vmcState: StateFlow<VmcEngineState> = vmcEngine.state
    val customDrumKitState: StateFlow<CustomDrumKitState> = drumKitManager.state

    init {
        soundEngine.setMasterVolume(_uiState.value.masterVolume, _uiState.value.isMasterMuted)
        arrangement.setMasterVolume(_uiState.value.masterVolume, _uiState.value.isMasterMuted)
        generateTestSignal(SignalWaveformType.SINE, 440.0)
        refreshMidiPorts()
        syncLocalPresence()
        netNode.startDiscovery()
    }

    fun selectStation(station: DesktopStation) {
        _uiState.value = _uiState.value.copy(station = station)
        if (station == DesktopStation.TUNER && _uiState.value.tunerSource == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
    }

    fun updateWorkspaceConfig(transform: DesktopWorkspaceConfig.() -> DesktopWorkspaceConfig) {
        _uiState.value = _uiState.value.copy(workspaceConfig = _uiState.value.workspaceConfig.transform())
    }

    fun toggleStationPinned(station: DesktopStation) {
        if (station == DesktopStation.BOOTH) return // Always keep Booth Hub accessible
        val cur = _uiState.value.workspaceConfig.visibleStations.toMutableList()
        if (cur.contains(station)) {
            if (cur.size > 2) cur.remove(station)
        } else {
            cur.add(station)
        }
        updateWorkspaceConfig { copy(visibleStations = cur) }
    }

    fun toggleRadialSpoke(station: DesktopStation) {
        val cur = _uiState.value.workspaceConfig.radialWheelStations.toMutableList()
        if (cur.contains(station)) {
            if (cur.size > 3) cur.remove(station)
        } else if (cur.size < 8) {
            cur.add(station)
        }
        updateWorkspaceConfig { copy(radialWheelStations = cur) }
    }

    fun setVisualizerMode(mode: VisualizerMode) {
        _uiState.value = _uiState.value.copy(visualizerMode = mode)
    }

    fun loadWavFile(file: File): Result<DesktopAudioBuffer> {
        return runCatching {
            val buf = DesktopWavCodec.readWav(file)
            loadNewBuffer(buf, "LOADED ${file.name} (${"%.2f".format(buf.durationSeconds)}s • ${buf.sampleRate}Hz)")
            buf
        }.onFailure { err ->
            _uiState.value = _uiState.value.copy(statusLine = "WAV LOAD FAILED: ${err.message}")
        }
    }

    fun generateTestSignal(type: SignalWaveformType, freqHz: Double) {
        val cfg = _uiState.value.rigConfig
        val buf = SignalAnalyzer.generateTestSignal(
            type = type,
            freqHz = freqHz,
            durationSec = 1.8f,
            sampleRate = cfg.sampleRate,
            channels = cfg.channels
        )
        _uiState.value = _uiState.value.copy(generatorType = type, generatorFreqHz = freqHz)
        loadNewBuffer(buf, "GENERATED ${type.label} @ ${"%.1f".format(freqHz)} Hz")
    }

    fun loadNewBuffer(buffer: DesktopAudioBuffer, banner: String, preserveLoopPlayback: Boolean = false) {
        undoStack.clear()
        val keepLoop = preserveLoopPlayback && playbackState.value.isPlaying && playbackState.value.isLooping
        if (!keepLoop) {
            soundEngine.stopPlayback()
        }
        _uiState.value = _uiState.value.copy(
            rawBuffer = buffer,
            dryBuffer = buffer,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = false,
            statusLine = banner
        )
        soundEngine.setSelection(0f, 1f, _uiState.value.playSelectionOnly)
        arrangement.setSequencerSampleForLane(0, buffer)
        reprocessDsp(buffer, preserveLoopPlayback = keepLoop)
    }

    fun updateSelection(start: Float, end: Float) {
        val lo = minOf(start, end).coerceIn(0f, 0.98f)
        val hi = maxOf(start, end).coerceIn(lo + 0.02f, 1f)
        _uiState.value = _uiState.value.copy(selectionStart = lo, selectionEnd = hi)
        soundEngine.setSelection(lo, hi, _uiState.value.playSelectionOnly)
        if (_uiState.value.tunerSource == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
    }

    fun togglePlaySelectionOnly() {
        val next = !_uiState.value.playSelectionOnly
        _uiState.value = _uiState.value.copy(playSelectionOnly = next)
        soundEngine.setSelection(_uiState.value.selectionStart, _uiState.value.selectionEnd, next)
    }

    fun snapSelectionZeroCrossing() {
        val cur = _uiState.value.dryBuffer ?: return
        val s = cur.snapFractionToZeroCrossing(_uiState.value.selectionStart)
        val e = cur.snapFractionToZeroCrossing(_uiState.value.selectionEnd).coerceAtLeast((s + 0.02f).coerceAtMost(1f))
        updateSelection(s, e)
        _uiState.value = _uiState.value.copy(statusLine = "SNAPPED SELECTION TO ZERO-CROSSINGS")
    }

    fun extractSelection() = mutateDryBuffer("EXTRACTED SELECTION") { buf ->
        buf.extractSelection(_uiState.value.selectionStart, _uiState.value.selectionEnd)
    }

    fun applyFades(fadeInMs: Float = _uiState.value.fadeInMs, fadeOutMs: Float = _uiState.value.fadeOutMs) {
        _uiState.value = _uiState.value.copy(fadeInMs = fadeInMs, fadeOutMs = fadeOutMs)
        mutateDryBuffer("APPLIED FADE IN ${fadeInMs.toInt()}ms / OUT ${fadeOutMs.toInt()}ms") { buf ->
            buf.applyLinearFades(fadeInMs, fadeOutMs)
        }
    }

    fun reverseSignal() = mutateDryBuffer("REVERSED SIGNAL") { it.reversed() }
    fun removeDcOffset() = mutateDryBuffer("DC OFFSET REMOVED") { it.removeDcOffset() }
    fun normalizeSignal() = mutateDryBuffer("NORMALIZED TO -0.35 dBFS") { it.peakNormalized(0.96f) }

    fun undoEdit() {
        val prev = undoStack.removeLastOrNull() ?: return
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        if (!keepLoop) soundEngine.stopPlayback()
        _uiState.value = _uiState.value.copy(
            dryBuffer = prev,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = undoStack.isNotEmpty(),
            statusLine = if (keepLoop) "RESTORED PREVIOUS EDIT (LOOP PERSISTING)" else "RESTORED PREVIOUS EDIT"
        )
        soundEngine.setSelection(0f, 1f, _uiState.value.playSelectionOnly)
        arrangement.setSequencerSampleForLane(0, prev)
        reprocessDsp(prev, preserveLoopPlayback = keepLoop)
    }

    fun resetToOriginal() {
        val raw = _uiState.value.rawBuffer ?: return
        undoStack.clear()
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        if (!keepLoop) soundEngine.stopPlayback()
        _uiState.value = _uiState.value.copy(
            dryBuffer = raw,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = false,
            statusLine = if (keepLoop) "RESET TO ORIGINAL (LOOP PERSISTING)" else "RESET TO ORIGINAL SIGNAL"
        )
        soundEngine.setSelection(0f, 1f, _uiState.value.playSelectionOnly)
        arrangement.setSequencerSampleForLane(0, raw)
        reprocessDsp(raw, preserveLoopPlayback = keepLoop)
    }

    private fun mutateDryBuffer(
        banner: String,
        resetSelection: Boolean = true,
        transform: (DesktopAudioBuffer) -> DesktopAudioBuffer
    ) {
        val cur = _uiState.value.dryBuffer ?: return
        undoStack.addLast(cur)
        val next = transform(cur)
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        if (!keepLoop) {
            soundEngine.stopPlayback()
        }
        val nextSelStart = if (resetSelection) 0f else _uiState.value.selectionStart
        val nextSelEnd = if (resetSelection) 1f else _uiState.value.selectionEnd
        _uiState.value = _uiState.value.copy(
            dryBuffer = next,
            selectionStart = nextSelStart,
            selectionEnd = nextSelEnd,
            canUndo = undoStack.isNotEmpty(),
            statusLine = "$banner (${"%.2f".format(next.durationSeconds)}s)${if (keepLoop) " • LOOP ACTIVE" else ""}"
        )
        soundEngine.setSelection(nextSelStart, nextSelEnd, _uiState.value.playSelectionOnly)
        arrangement.setSequencerSampleForLane(0, next)
        reprocessDsp(next, preserveLoopPlayback = keepLoop)
    }

    fun updateFx(transform: DesktopFxState.() -> DesktopFxState) {
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        _uiState.value = _uiState.value.copy(fxState = _uiState.value.fxState.transform())
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun applyPreset(preset: DesktopPreset) {
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        _uiState.value = _uiState.value.copy(
            fxState = DesktopPresetFactory.build(preset),
            statusLine = "PRESET: ${preset.label}"
        )
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun updateStomp(transform: DesktopStompState.() -> DesktopStompState) {
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        _uiState.value = _uiState.value.copy(stompState = _uiState.value.stompState.transform())
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun toggleMasterStompBypass() {
        val nextStomp = _uiState.value.stompState.copy(engaged = !_uiState.value.stompState.engaged)
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        _uiState.value = _uiState.value.copy(
            stompState = nextStomp,
            statusLine = if (nextStomp.engaged) "MASTER STOMP ENGAGED" else "MASTER STOMP BYPASSED"
        )
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun setLiveSessionMode(mode: DesktopLiveMode) {
        updateStomp { copy(liveMode = mode) }
        _uiState.value = _uiState.value.copy(statusLine = "LIVE MODE: ${mode.title}")
    }

    fun onStompXyDrag(x: Float, y: Float) {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        updateStomp {
            copy(
                engaged = true,
                xyPadX = cx,
                xyPadY = cy,
                toneTilt = (cx * 2f - 1f) * 0.85f,
                spaceScale = 0.2f + cy * 1.4f,
                modScale = 0.4f + cx * 1.2f
            )
        }
    }

    fun tapTempo() {
        val now = System.currentTimeMillis()
        if (lastTapMs in 1L until now) {
            val delta = now - lastTapMs
            if (delta in 300L..1200L) {
                val bpm = (60_000f / delta.toFloat()).coerceIn(60f, 180f)
                arrangement.setBpm(bpm)
            }
        }
        lastTapMs = now
    }

    private fun reprocessDsp(dry: DesktopAudioBuffer, preserveLoopPlayback: Boolean) {
        val fx = _uiState.value.fxState
        val stomp = _uiState.value.stompState
        val ts = _toolsState.value
        val defs = ts.installedPlugins.associateBy { it.id }
        val proc = DesktopDspProcessor.process(dry, fx, stomp, ts.pluginChain, defs)
        val dryEnv = SignalAnalyzer.computeWaveformEnvelope(dry, 180)
        val procEnv = SignalAnalyzer.computeWaveformEnvelope(proc, 180)
        val spec = SignalAnalyzer.computeSpectrumBands(proc, proc.frameCount / 2, 1024, 48)
        val spectrogram = DesktopSpectrogramAnalyzer.compute(proc, 144, 80)
        _uiState.value = _uiState.value.copy(
            processedBuffer = proc,
            dryEnvelope = dryEnv,
            processedEnvelope = procEnv,
            staticSpectrum = spec,
            spectrogram = spectrogram
        )
        soundEngine.updateBufferHot(proc, preserveLoopPlayback = preserveLoopPlayback)
        if (_uiState.value.tunerSource == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
    }

    // --- SAMPLES, INSTRUMENTS, TRACKS SPOKE ACTIONS ---

    fun sendEditorToLoopSlot(slotId: Int) {
        val buf = _uiState.value.processedBuffer ?: _uiState.value.dryBuffer ?: return
        arrangement.assignBufferToLoopSlot(slotId, buf, buf.name)
        _uiState.value = _uiState.value.copy(statusLine = "ASSIGNED DECK TO LOOP ${'A' + slotId}")
    }

    fun bounceLooperToEditor() {
        val mix = arrangement.bounceLooperMixdown(_uiState.value.rigConfig.sampleRate)
        loadNewBuffer(mix, "BOUNCED LOOPER MIX TO DECK")
        selectStation(DesktopStation.EDITOR)
    }

    fun auditionRefinerLoop() {
        val st = arrangement.loopRefinerState.value
        val cur = st.workingBuffer ?: return
        val clip = cur.extractSelection(st.loopStartFrac, st.loopEndFrac)
        soundEngine.setLooping(true)
        soundEngine.setBuffer(clip)
        soundEngine.startPlayback()
    }

    fun sendRefinerToDeck() {
        val buf = arrangement.loopRefinerState.value.workingBuffer ?: return
        loadNewBuffer(buf, "SENT REFINED LOOP TO DECK")
        selectStation(DesktopStation.EDITOR)
    }

    fun assignEditorToSeqLane(laneId: Int) {
        val buf = _uiState.value.processedBuffer ?: _uiState.value.dryBuffer ?: return
        arrangement.setSequencerSampleForLane(laneId, buf)
        _uiState.value = _uiState.value.copy(statusLine = "ASSIGNED DECK SAMPLE TO SEQ LANE ${laneId + 1}")
    }

    fun triggerSeqPadOneShot(laneId: Int) {
        val lane = arrangement.sequencerState.value.lanes.getOrNull(laneId) ?: return
        soundEngine.playOneShot(lane.buffer)
    }

    fun bounceSequencerToTimeline() {
        val beat = arrangement.renderSequenceToBuffer(bars = 2, sampleRate = _uiState.value.rigConfig.sampleRate)
        arrangement.addClipToTimeline(2, beat, 0f)
        _uiState.value = _uiState.value.copy(statusLine = "BOUNCED 2-BAR SEQ BEAT TO TRACK 3")
    }

    // --- ARRANGEABLE ELECTRIC & ACOUSTIC DRUM KIT STUDIO (UP TO 7 MAIN + MOUNTED ADD-ONS) ---

    fun setDrumKitStyle(style: DrumKitStyle, convertExisting: Boolean = true) {
        drumKitManager.setKitStyle(style, convertExisting)
        _uiState.value = _uiState.value.copy(statusLine = "DRUM KIT -> ${style.label.uppercase()}")
    }

    fun applyDrumKitPreset(preset: DrumKitPreset) {
        drumKitManager.applyPreset(preset)
        _uiState.value = _uiState.value.copy(statusLine = "DRUM KIT: ${preset.label.uppercase()}")
    }

    fun toggleDrumArrangeMode() = drumKitManager.toggleArrangeMode()
    fun flipDrumKitLeftHanded() = drumKitManager.flipLeftHanded()
    fun selectDrumSlot(slotId: String?) = drumKitManager.selectSlot(slotId)
    fun setTargetBeaterForMount(side: BeaterSide) = drumKitManager.setTargetBeaterForMount(side)

    fun addMainDrumPiece(componentId: String, xNorm: Float? = null, yNorm: Float? = null): Boolean {
        val added = drumKitManager.addMainPiece(componentId, xNorm, yNorm)
        if (added == null) {
            drumKitManager.state.value.statusError?.let { _uiState.value = _uiState.value.copy(statusLine = it) }
            return false
        }
        return true
    }

    fun swapMainDrumPiece(slotId: String, newComponentId: String) =
        drumKitManager.swapMainPiece(slotId, newComponentId)

    fun removeMainDrumPiece(slotId: String) = drumKitManager.removeMainPiece(slotId)
    fun moveDrumPieceOnStage(slotId: String, xNorm: Float, yNorm: Float) = drumKitManager.movePieceOnStage(slotId, xNorm, yNorm)

    fun updateDrumPieceParams(
        slotId: String,
        pitchSemitones: Float? = null,
        decayScale: Float? = null,
        volume: Float? = null,
        isMuted: Boolean? = null
    ) = drumKitManager.updatePieceParams(slotId, pitchSemitones, decayScale, volume, isMuted)

    fun attachDrumAddon(
        addonType: DrumAddonType,
        targetSlotId: String,
        beaterSide: BeaterSide = drumKitManager.state.value.targetBeaterForMount,
        styleOverride: DrumKitStyle? = null
    ) = drumKitManager.attachAddon(addonType, targetSlotId, beaterSide, styleOverride)

    fun removeDrumAddon(instanceId: String) = drumKitManager.removeMountedAddon(instanceId)
    fun toggleDrumAddonCoupled(instanceId: String) = drumKitManager.toggleAddonCoupled(instanceId)
    fun setDrumAddonStyle(instanceId: String, style: DrumKitStyle) = drumKitManager.setAddonStyle(instanceId, style)
    fun setDrumAddonBeaterSide(instanceId: String, side: BeaterSide) = drumKitManager.setAddonBeaterSide(instanceId, side)

    fun hitDrumPiece(slotId: String, beaterSide: BeaterSide = BeaterSide.NONE) {
        val hit = drumKitManager.synthesizePieceHit(slotId, beaterSide, _uiState.value.rigConfig.sampleRate) ?: return
        soundEngine.playOneShot(hit)
    }

    fun hitDrumAddon(instanceId: String) {
        val hit = drumKitManager.synthesizeAddonHit(instanceId, _uiState.value.rigConfig.sampleRate) ?: return
        soundEngine.playOneShot(hit)
    }

    fun sendDrumPieceToSeqLane(slotId: String, laneId: Int, beaterSide: BeaterSide = BeaterSide.NONE) {
        val hit = drumKitManager.synthesizePieceHit(slotId, beaterSide, _uiState.value.rigConfig.sampleRate) ?: return
        arrangement.setSequencerSampleForLane(laneId.coerceIn(0, 3), hit)
        _uiState.value = _uiState.value.copy(statusLine = "ROUTED ${hit.name} -> SEQ LANE ${laneId.coerceIn(0, 3) + 1}")
    }

    fun sendDrumPieceToDeck(slotId: String, beaterSide: BeaterSide = BeaterSide.NONE) {
        val hit = drumKitManager.synthesizePieceHit(slotId, beaterSide, _uiState.value.rigConfig.sampleRate) ?: return
        loadNewBuffer(hit, "SENT ${hit.name} TO DECK EDITOR", preserveLoopPlayback = false)
    }

    fun addEditorClipToTimeline(trackId: Int) {
        val buf = _uiState.value.processedBuffer ?: _uiState.value.dryBuffer ?: return
        arrangement.addClipToTimeline(trackId, buf, arrangement.timelineState.value.playheadSec)
        _uiState.value = _uiState.value.copy(statusLine = "ADDED DECK CLIP TO TRACK ${trackId + 1}")
    }

    fun addLooperMixToTimeline() {
        val mix = arrangement.bounceLooperMixdown(_uiState.value.rigConfig.sampleRate)
        arrangement.addClipToTimeline(1, mix, 0f)
        _uiState.value = _uiState.value.copy(statusLine = "ADDED LOOPER MIX TO TRACK 2")
    }

    fun bounceTimelineToEditor() {
        val mix = arrangement.mixdownTimeline(_uiState.value.rigConfig.sampleRate)
        if (mix.frameCount == 0) {
            _uiState.value = _uiState.value.copy(statusLine = "TIMELINE HAS NO CLIPS")
            return
        }
        loadNewBuffer(mix, "TIMELINE MIXDOWN LOADED IN DECK")
        selectStation(DesktopStation.EDITOR)
    }

    // --- VMC 64-CH MIDI ---

    fun refreshMidiPorts() {
        val (ins, outs) = hwMidi.refreshAndBindAll()
        vmcEngine.updateHardwareLists(ins, outs)
    }

    private fun executeVmcTarget(target: VmcTarget, norm: Float) {
        when (target) {
            VmcTarget.DECK_PLAY_PAUSE -> soundEngine.togglePlay()
            VmcTarget.DECK_STOP -> soundEngine.stopPlayback()
            VmcTarget.DECK_LOOP_TOGGLE -> soundEngine.setLooping(!playbackState.value.isLooping)
            VmcTarget.SYNC_ALL_PLAY -> arrangement.toggleSyncedTransport()
            VmcTarget.BPM_TEMPO -> arrangement.setBpm(60f + norm * 120f)
            VmcTarget.STOMP_BYPASS -> toggleMasterStompBypass()
            VmcTarget.STOMP_WET_MIX -> updateStomp { copy(engaged = true, masterWetMix = norm) }
            VmcTarget.STOMP_TONE_TILT -> updateStomp { copy(engaged = true, toneTilt = norm * 2f - 1f) }
            VmcTarget.STOMP_DYNAMICS -> updateStomp { copy(engaged = true, dynamicsTrimDb = -6f + norm * 12f) }
            VmcTarget.STOMP_MOD_SCALE -> updateStomp { copy(engaged = true, modScale = 0.2f + norm * 1.6f) }
            VmcTarget.STOMP_SPACE_SCALE -> updateStomp { copy(engaged = true, spaceScale = 0.2f + norm * 1.6f) }
            VmcTarget.FX_DRIVE -> updateFx { copy(overdriveEnabled = true, driveAmount = 1f + norm * 15f) }
            VmcTarget.FX_WAH_FREQ -> updateFx { copy(wahEnabled = true, wahFreqHz = 280f + norm * 2200f) }
            VmcTarget.FX_PHASER_MIX -> updateFx { copy(phaserEnabled = true, phaserMix = norm) }
            VmcTarget.FX_FLANGER_MIX -> updateFx { copy(flangerEnabled = true, flangerMix = norm) }
            VmcTarget.FX_CHORUS_MIX -> updateFx { copy(chorusEnabled = true, chorusMix = norm) }
            VmcTarget.FX_ROTARY_RATE -> updateFx { copy(rotaryEnabled = true, rotaryRateHz = 0.5f + norm * 9.5f) }
            VmcTarget.FX_ECHO_MIX -> updateFx { copy(echoEnabled = true, echoMix = norm) }
            VmcTarget.FX_DELAY_MIX -> updateFx { copy(delayEnabled = true, delayMix = norm) }
            VmcTarget.FX_REVERB_MIX -> updateFx { copy(reverbEnabled = true, reverbMix = norm) }
            VmcTarget.FX_PITCH_SEMI -> updateFx { copy(pitchEnabled = true, pitchSemitones = -12f + norm * 24f) }
            VmcTarget.LOOPER_SLOT_A -> arrangement.toggleLoopSlotActive(0)
            VmcTarget.LOOPER_SLOT_B -> arrangement.toggleLoopSlotActive(1)
            VmcTarget.LOOPER_SLOT_C -> arrangement.toggleLoopSlotActive(2)
            VmcTarget.LOOPER_SLOT_D -> arrangement.toggleLoopSlotActive(3)
            VmcTarget.SEQ_PAD_1 -> triggerSeqPadOneShot(0)
            VmcTarget.SEQ_PAD_2 -> triggerSeqPadOneShot(1)
            VmcTarget.SEQ_PAD_3 -> triggerSeqPadOneShot(2)
            VmcTarget.SEQ_PAD_4 -> triggerSeqPadOneShot(3)
            VmcTarget.SEQ_PLAY_TOGGLE -> arrangement.toggleSequencerPlayback()
            VmcTarget.TIMELINE_PLAY_TOGGLE -> arrangement.toggleTimelinePlayback()
            VmcTarget.SESSION_HANDOFF_PUSH -> pushSessionToPeer()
            VmcTarget.LIVE_OVERDUB_TOGGLE -> arrangement.toggleCompanionOverdubRx()
            VmcTarget.SEQ_CLEAR_PATTERN -> arrangement.clearSequencerPattern()
            VmcTarget.DRUM_PUNCH_LANE_1 -> {
                arrangement.recordCompanionStepHit(0)
                triggerSeqPadOneShot(0)
            }
            VmcTarget.DRUM_PUNCH_LANE_2 -> {
                arrangement.recordCompanionStepHit(1)
                triggerSeqPadOneShot(1)
            }
            VmcTarget.DRUM_PUNCH_LANE_3 -> {
                arrangement.recordCompanionStepHit(2)
                triggerSeqPadOneShot(2)
            }
            VmcTarget.DRUM_PUNCH_LANE_4 -> {
                arrangement.recordCompanionStepHit(3)
                triggerSeqPadOneShot(3)
            }
        }
    }

    // --- SESSION HANDOFF (EQUAL PEER PICKUP) ---

    fun captureSessionSnapshot(): SessionHandoffSnapshot {
        val ui = _uiState.value
        val play = playbackState.value
        val seqLanes = arrangement.sequencerState.value.lanes.map { lane ->
            var mask = 0
            for (i in lane.steps.indices) {
                if (lane.steps[i]) mask = mask or (1 shl i)
            }
            mask
        }
        var loopMask = 0
        val loopBars = arrangement.looperState.value.slots.mapIndexed { idx, s ->
            if (s.isActive) loopMask = loopMask or (1 shl idx)
            s.bars
        }
        return SessionHandoffSnapshot(
            senderName = ui.workspaceConfig.nodeName,
            stationKey = ui.station.name,
            bpm = arrangement.syncedPlayhead.value.bpm,
            swing = arrangement.sequencerState.value.swing,
            isPlaying = play.isPlaying,
            isLooping = play.isLooping,
            playheadFraction = play.playheadFraction,
            selectionStart = ui.selectionStart,
            selectionEnd = ui.selectionEnd,
            playSelectionOnly = ui.playSelectionOnly,
            a4ReferenceHz = ui.rigConfig.a4ReferenceHz,
            fxState = ui.fxState,
            stompState = ui.stompState,
            seqLaneMasks = seqLanes,
            looperActiveMask = loopMask,
            looperBars = loopBars,
            vmcMappings = vmcEngine.state.value.mappings,
            masterVolume = ui.masterVolume,
            isMasterMuted = ui.isMasterMuted,
            buffer = ui.dryBuffer
        )
    }

    fun pushSessionToPeer() {
        val cfg = _uiState.value.rigConfig
        val snap = captureSessionSnapshot()
        netNode.sendSessionHandoff(snap, cfg.protocol, cfg.txHost, cfg.txPort)
    }

    fun grabSessionFromPeer() {
        netNode.requestSessionFromPeer(_uiState.value.rigConfig)
    }

    fun applyPendingHandoff() {
        val snap = _uiState.value.pendingHandoff ?: return
        applySessionHandoff(snap)
    }

    fun applySessionHandoff(snap: SessionHandoffSnapshot) {
        soundEngine.stopPlayback()
        arrangement.setBpm(snap.bpm)
        arrangement.setSequencerSwing(snap.swing)
        snap.seqLaneMasks.forEachIndexed { idx, mask ->
            arrangement.applySequencerPatternMask(idx, mask)
        }
        snap.looperBars.forEachIndexed { idx, b ->
            arrangement.setLoopSlotBars(idx, b)
            arrangement.setLoopSlotActive(idx, ((snap.looperActiveMask shr idx) and 1) == 1)
        }
        if (snap.vmcMappings.isNotEmpty()) {
            vmcEngine.replaceMappings(snap.vmcMappings)
        }

        val targetStation = SessionHandoffSnapshot.mapStationKeyToDesktop(snap.stationKey)
        val newDry = snap.buffer ?: _uiState.value.dryBuffer
        val syncedVol = snap.masterVolume.coerceIn(0f, 1f)
        val syncedMuted = snap.isMasterMuted
        soundEngine.setMasterVolume(syncedVol, syncedMuted)
        arrangement.setMasterVolume(syncedVol, syncedMuted)
        _uiState.value = _uiState.value.copy(
            fxState = snap.fxState,
            stompState = snap.stompState,
            masterVolume = syncedVol,
            isMasterMuted = syncedMuted,
            selectionStart = snap.selectionStart,
            selectionEnd = snap.selectionEnd,
            playSelectionOnly = snap.playSelectionOnly,
            rigConfig = _uiState.value.rigConfig.copy(a4ReferenceHz = snap.a4ReferenceHz.coerceIn(430.0, 450.0)),
            pendingHandoff = null
        )
        soundEngine.setLooping(snap.isLooping)
        soundEngine.setSelection(snap.selectionStart, snap.selectionEnd, snap.playSelectionOnly)

        if (newDry != null && newDry.frameCount > 0) {
            undoStack.clear()
            _uiState.value = _uiState.value.copy(rawBuffer = newDry, dryBuffer = newDry, canUndo = false)
            reprocessDsp(newDry, preserveLoopPlayback = false)
            soundEngine.seekTo(snap.playheadFraction)
            if (snap.isPlaying && _uiState.value.workspaceConfig.autoResumeOnHandoff) {
                soundEngine.startPlayback()
            }
        }
        _uiState.value = _uiState.value.copy(
            station = targetStation,
            statusLine = "PICKED UP SESSION FROM ${snap.senderName} -> ${targetStation.shortLabel}"
        )
        if (targetStation == DesktopStation.TUNER && _uiState.value.tunerSource == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
    }

    // --- TUNER ---

    fun setTunerSource(source: TunerInputSource) {
        _uiState.value = _uiState.value.copy(tunerSource = source)
        when (source) {
            TunerInputSource.LIVE_MIC -> soundEngine.startMicCapture(_uiState.value.rigConfig.sampleRate, tunerOnly = true)
            TunerInputSource.DECK_BUFFER -> {
                if (soundEngine.micState.value.isTunerMonitorOn) {
                    soundEngine.stopMicAndCollectBuffer()
                }
                analyzeActiveBufferWindowForTuner()
            }
            TunerInputSource.SMP2_STREAM -> {
                if (soundEngine.micState.value.isTunerMonitorOn) {
                    soundEngine.stopMicAndCollectBuffer()
                }
            }
        }
    }

    fun setTuningPreset(preset: TuningPreset) {
        _uiState.value = _uiState.value.copy(tuningPreset = preset)
    }

    fun setA4Reference(hz: Double) {
        val clamped = hz.coerceIn(430.0, 450.0)
        _uiState.value = _uiState.value.copy(
            rigConfig = _uiState.value.rigConfig.copy(a4ReferenceHz = clamped)
        )
        if (_uiState.value.tunerSource == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
    }

    fun analyzeActiveBufferWindowForTuner() {
        val buf = _uiState.value.processedBuffer ?: _uiState.value.dryBuffer ?: return
        if (buf.frameCount < 512) return
        val slice = buf.extractSelection(_uiState.value.selectionStart, _uiState.value.selectionEnd)
        val mono = slice.monoMixed()
        val windowSize = minOf(4096, mono.size)
        val start = maxOf(0, (mono.size - windowSize) / 2)
        val window = mono.copyOfRange(start, start + windowSize)
        analyzeSamplesForTuner(window, buf.sampleRate)
    }

    private fun analyzeSamplesForTuner(monoSamples: FloatArray, sampleRate: Int) {
        val reading = PitchDetector.detectPitch(
            monoSamples = monoSamples,
            sampleRate = sampleRate,
            a4ReferenceHz = _uiState.value.rigConfig.a4ReferenceHz
        )
        _uiState.value = _uiState.value.copy(tunerReading = reading)
    }

    // --- LINUX MIC RECORDING ---

    fun toggleMicRecording() {
        if (soundEngine.micState.value.isRecording) {
            val captured = soundEngine.stopMicAndCollectBuffer()
            if (captured != null) {
                loadNewBuffer(captured, "CAPTURED LINUX MIC (${"%.2f".format(captured.durationSeconds)}s)")
            } else {
                _uiState.value = _uiState.value.copy(statusLine = "MIC CAPTURE STOPPED (NO AUDIO)")
            }
        } else {
            soundEngine.startMicCapture(_uiState.value.rigConfig.sampleRate, tunerOnly = false)
            _uiState.value = _uiState.value.copy(statusLine = "RECORDING LINUX MIC INPUT…")
        }
    }

    // --- EXPORT WAV / MP3 ---

    fun exportWav(targetFile: File): Result<File> {
        val buf = _uiState.value.processedBuffer ?: _uiState.value.dryBuffer
            ?: return Result.failure(IllegalStateException("No audio loaded"))
        val ts = _toolsState.value
        val targetFmt = ts.targetSampleFormat
        val dither = ts.applyDither
        return runCatching {
            DesktopWavCodec.writeWav(buf, targetFile, targetFmt, dither)
            _uiState.value = _uiState.value.copy(
                statusLine = "SAVED WAV (${targetFmt.shortLabel}) -> ${targetFile.absolutePath}"
            )
            targetFile
        }.onFailure {
            _uiState.value = _uiState.value.copy(statusLine = "WAV SAVE ERROR: ${it.message}")
        }
    }

    fun exportMp3(targetFile: File): Result<File> {
        val buf = _uiState.value.processedBuffer ?: _uiState.value.dryBuffer
            ?: return Result.failure(IllegalStateException("No audio loaded"))
        val res = DesktopMp3Exporter.encodeToFile(buf, targetFile, _uiState.value.rigConfig.mp3BitrateKbps)
        res.onSuccess {
            _uiState.value = _uiState.value.copy(statusLine = "SAVED MP3 -> ${it.absolutePath}")
        }.onFailure {
            _uiState.value = _uiState.value.copy(statusLine = "MP3 SAVE ERROR: ${it.message}")
        }
        return res
    }

    // --- MASTER VOLUME, 4-DEVICE COLLAB & NETWORK BRIDGE ---

    fun setMasterVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        val muted = _uiState.value.isMasterMuted
        soundEngine.setMasterVolume(clamped, muted)
        arrangement.setMasterVolume(clamped, muted)
        _uiState.value = _uiState.value.copy(masterVolume = clamped)
    }

    fun toggleMasterMute() {
        val nextMuted = !_uiState.value.isMasterMuted
        val vol = _uiState.value.masterVolume
        soundEngine.setMasterVolume(vol, nextMuted)
        arrangement.setMasterVolume(vol, nextMuted)
        _uiState.value = _uiState.value.copy(isMasterMuted = nextMuted)
    }

    fun refreshNearbyDiscovery() {
        syncLocalPresence()
        val cfg = _uiState.value.rigConfig
        if (!netNode.state.value.isListening) {
            runCatching { netNode.startListening(cfg.protocol, cfg.bindPort) }
        }
        if (!netNode.state.value.isDiscovering) {
            netNode.startDiscovery()
        }
        netNode.sendDiscoveryBeaconOnce()
    }

    fun setMyCollabStyle(style: CollabInstrumentStyle, trackSlot: Int = style.defaultTrack) {
        val slot = trackSlot.coerceIn(0, 3)
        _uiState.value = _uiState.value.copy(
            bandRecState = _uiState.value.bandRecState.copy(myStyle = style, myTrackSlot = slot)
        )
        syncLocalPresence()
        netNode.sendDiscoveryBeaconOnce()
    }

    fun connectCollabPeer(
        host: String,
        port: Int,
        name: String = "Studio Peer",
        platform: String = "PEER",
        style: CollabInstrumentStyle? = null,
        trackSlot: Int? = null
    ): Boolean {
        val cfg = _uiState.value.rigConfig
        if (!netNode.state.value.isListening) {
            runCatching { netNode.startListening(cfg.protocol, cfg.bindPort) }
        }
        val res = netNode.connectPeer(host, port, name, platform, style, trackSlot)
        res.onSuccess { peer ->
            _uiState.value = _uiState.value.copy(
                rigConfig = cfg.copy(txHost = peer.host, txPort = peer.listenPort),
                statusLine = "CONNECTED ${peer.name} (${peer.style.label}) -> TRACK ${peer.trackSlot + 1}"
            )
        }.onFailure { err ->
            _uiState.value = _uiState.value.copy(
                statusLine = err.message ?: "Max $MAX_COLLAB_PEERS collaborators connected"
            )
        }
        return res.isSuccess
    }

    fun disconnectCollabPeer(peerId: String) {
        netNode.disconnectPeer(peerId)
        _uiState.value = _uiState.value.copy(statusLine = "DISCONNECTED PEER $peerId")
    }

    fun updateCollabPeerStyleAndTrack(
        peerId: String,
        style: CollabInstrumentStyle,
        trackSlot: Int = style.defaultTrack
    ) {
        netNode.updatePeerStyleAndTrack(peerId, style, trackSlot)
    }

    fun toggleLiveBandRecording() {
        if (_uiState.value.bandRecState.isRecording) {
            stopLiveBandRecording(broadcastStop = true)
        } else {
            startLiveBandRecording(broadcastToPeers = true)
        }
    }

    fun startLiveBandRecording(broadcastToPeers: Boolean = true, syncedTakeName: String = "") {
        if (_uiState.value.bandRecState.isRecording) return
        val cfg = _uiState.value.rigConfig
        if (!netNode.state.value.isListening) {
            runCatching { netNode.startListening(cfg.protocol, cfg.bindPort) }
        }
        netNode.beginCollabAudioCapture()
        val takeName = syncedTakeName.ifBlank {
            "BAND_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}"
        }
        val cur = _uiState.value.bandRecState
        _uiState.value = _uiState.value.copy(
            bandRecState = cur.copy(
                isRecording = true,
                startedAtMs = System.currentTimeMillis(),
                activeTakeName = takeName
            )
        )
        syncLocalPresence()
        soundEngine.startMicCapture(cfg.sampleRate, tunerOnly = false)
        if (broadcastToPeers) {
            netNode.sendCollabRecSync(
                CollabSyncCommand(
                    start = true,
                    bpm = arrangement.syncedPlayhead.value.bpm,
                    senderPeerId = netNode.localNodeId,
                    senderName = _uiState.value.workspaceConfig.nodeName,
                    senderStyle = cur.myStyle,
                    senderTrackSlot = cur.myTrackSlot,
                    takeName = takeName,
                    senderListenPort = cfg.bindPort
                )
            )
        }
        val linked = netNode.getConnectedPeers().size
        _uiState.value = _uiState.value.copy(
            statusLine = "LIVE BAND REC (${cur.myStyle.shortTag} -> T${cur.myTrackSlot + 1} • $linked PEERS)"
        )
    }

    fun stopLiveBandRecording(broadcastStop: Boolean = true) {
        val cur = _uiState.value.bandRecState
        if (!cur.isRecording) return
        _uiState.value = _uiState.value.copy(
            bandRecState = cur.copy(
                isRecording = false,
                elapsedMs = System.currentTimeMillis() - cur.startedAtMs
            )
        )
        syncLocalPresence()
        val cfg = _uiState.value.rigConfig
        if (broadcastStop) {
            netNode.sendCollabRecSync(
                CollabSyncCommand(
                    start = false,
                    bpm = arrangement.syncedPlayhead.value.bpm,
                    senderPeerId = netNode.localNodeId,
                    senderName = _uiState.value.workspaceConfig.nodeName,
                    senderStyle = cur.myStyle,
                    senderTrackSlot = cur.myTrackSlot,
                    takeName = cur.activeTakeName,
                    senderListenPort = cfg.bindPort
                )
            )
        }
        val stamp = cur.activeTakeName.ifBlank { "BAND_${System.currentTimeMillis()}" }
        val localBuf = soundEngine.stopMicAndCollectBuffer("${stamp}_${cur.myStyle.shortTag}")
        val peerTracks = netNode.stopAndCollectCollabTracks(stamp)

        var placedCount = 0
        if (localBuf != null) {
            arrangement.addClipToTimeline(cur.myTrackSlot.coerceIn(0, 3), localBuf, 0f)
            placedCount++
            loadNewBuffer(localBuf, "SAVED ${cur.myStyle.label} TAKE -> TRACK ${cur.myTrackSlot + 1}")
        }
        peerTracks.forEach { (peer, buf) ->
            arrangement.addClipToTimeline(peer.trackSlot.coerceIn(0, 3), buf, 0f)
            placedCount++
        }
        val summary = "BAND REC LANDED $placedCount TRACKS (${1 + peerTracks.size} DEVICES)"
        _uiState.value = _uiState.value.copy(
            bandRecState = _uiState.value.bandRecState.copy(lastRecordedSummary = summary),
            statusLine = summary
        )
    }

    private fun syncLocalPresence() {
        val ui = _uiState.value
        netNode.updateLocalPresence(
            nodeName = ui.workspaceConfig.nodeName,
            listenPort = ui.rigConfig.bindPort,
            style = ui.bandRecState.myStyle,
            trackSlot = ui.bandRecState.myTrackSlot,
            isRecording = ui.bandRecState.isRecording
        )
    }

    fun updateRigConfig(transform: DesktopRigConfig.() -> DesktopRigConfig) {
        _uiState.value = _uiState.value.copy(rigConfig = _uiState.value.rigConfig.transform())
        syncLocalPresence()
    }

    fun toggleNetworkListener() {
        if (netNode.state.value.isListening) {
            netNode.stopListening()
        } else {
            val cfg = _uiState.value.rigConfig
            netNode.startListening(cfg.protocol, cfg.bindPort)
        }
    }

    fun captureNetworkStreamToEditor() {
        val captured = netNode.captureReceivedBuffer("SMP2_PEER_RX")
        if (captured != null) {
            loadNewBuffer(captured, "LOADED SMP2 STREAM (${"%.2f".format(captured.durationSeconds)}s @ ${captured.sampleRate}Hz)")
            _uiState.value = _uiState.value.copy(station = DesktopStation.EDITOR)
        } else {
            _uiState.value = _uiState.value.copy(statusLine = "NO SMP2 PACKETS CAPTURED YET")
        }
    }

    fun transmitActiveOverNetwork() {
        val buf = _uiState.value.processedBuffer ?: _uiState.value.dryBuffer ?: return
        netNode.transmitBuffer(buf, _uiState.value.rigConfig)
    }

    // --- SECRETS VAULT & DISBAND STEMS ---

    internal fun overrideDisbandTransportForTest(transport: DesktopDisbandHttpTransport) {
        disbandClient = DesktopDisbandClient(transport)
    }

    fun saveSecretKey(keyName: String, secretValue: String) {
        runCatching {
            secretsVault.putSecret(keyName, secretValue)
        }.onSuccess {
            val msg = if (secretValue.trim().isEmpty()) {
                "Removed ${DesktopSecretsVault.sanitizeKeyName(keyName)} from Vault"
            } else {
                "Saved ${DesktopSecretsVault.sanitizeKeyName(keyName)} securely"
            }
            _uiState.value = _uiState.value.copy(statusLine = msg)
        }.onFailure { err ->
            _uiState.value = _uiState.value.copy(statusLine = "VAULT ERROR: ${err.message}")
        }
    }

    fun deleteSecretKey(keyName: String) {
        secretsVault.removeSecret(keyName)
        _uiState.value = _uiState.value.copy(statusLine = "Removed $keyName from Vault")
    }

    fun toggleDisbandStem(stem: DesktopDisbandStem) {
        val next = _stemState.value.selectedStems.toMutableSet()
        if (!next.add(stem)) next.remove(stem)
        _stemState.value = _stemState.value.copy(
            selectedStems = next,
            errorMessage = null,
            statusMessage = if (next.isEmpty()) {
                "NO SPECIFIC STEMS SELECTED — WILL PROMPT FOR ALL-STEMS CONFIRMATION"
            } else {
                "TARGET STEMS: ${next.joinToString(", ") { it.label }}"
            }
        )
    }

    fun applyStemPreset(preset: DesktopStemPreset) {
        val next = preset.stems
        _stemState.value = _stemState.value.copy(
            selectedStems = next,
            errorMessage = null,
            statusMessage = if (next.isEmpty()) {
                "UNSPECIFIED STEM SET — WILL PROMPT FOR ALL-STEMS CONFIRMATION"
            } else {
                "PRESET ${preset.label.uppercase()}: ${next.joinToString(", ") { it.label }}"
            }
        )
    }

    fun setStemUseSelectionOnly(onlySel: Boolean) {
        _stemState.value = _stemState.value.copy(useSelectionOnly = onlySel)
    }

    fun requestStemSeparation(): Boolean {
        if (_stemState.value.isRunning) return false
        val source = resolveSourceBufferForStems() ?: run {
            val msg = "NO AUDIO LOADED TO SEPARATE STEMS"
            _stemState.value = _stemState.value.copy(errorMessage = msg, statusMessage = msg, lastToastMessage = msg)
            _uiState.value = _uiState.value.copy(statusLine = msg)
            return false
        }
        val apiKey = secretsVault.resolveDisbandApiKey()
        if (apiKey.isNullOrBlank()) {
            val msg = "DISBAND_API_KEY MISSING — OPEN KEYS & SECRETS STORE IN TOP BAR OR BOOTH"
            _stemState.value = _stemState.value.copy(errorMessage = msg, statusMessage = msg, lastToastMessage = msg)
            _uiState.value = _uiState.value.copy(statusLine = msg)
            return false
        }

        val chosen = _stemState.value.selectedStems
        if (chosen.isEmpty()) {
            val est = DesktopStemState.estimateAllStemsCredits(source.durationSeconds)
            val toast = "No specific stem set specified — Separating ALL stems can cost ~$est credits."
            _stemState.value = _stemState.value.copy(
                showAllStemsConfirmation = true,
                pendingAllStemsEstimatedCredits = est,
                pendingAudioDurationSec = source.durationSeconds,
                errorMessage = null,
                lastToastMessage = toast,
                statusMessage = "CONFIRMATION REQUIRED: SEPARATE ALL STEMS (~$est CREDITS)"
            )
            _uiState.value = _uiState.value.copy(statusLine = toast)
            return false
        }
        launchDisbandSeparation(chosen.toList(), source, apiKey)
        return true
    }

    fun confirmSeparateAllStems() {
        val source = resolveSourceBufferForStems() ?: run {
            _stemState.value = _stemState.value.copy(showAllStemsConfirmation = false)
            return
        }
        val apiKey = secretsVault.resolveDisbandApiKey() ?: run {
            _stemState.value = _stemState.value.copy(
                showAllStemsConfirmation = false,
                errorMessage = "DISBAND_API_KEY MISSING"
            )
            return
        }
        _stemState.value = _stemState.value.copy(showAllStemsConfirmation = false)
        launchDisbandSeparation(DesktopDisbandStem.ALL_STEMS_DEFAULT, source, apiKey)
    }

    fun dismissAllStemsConfirmation() {
        val msg = "Cancelled All-Stems separation — pick specific stems (Vocals, Drums, Bass, Guitar) to save credits."
        _stemState.value = _stemState.value.copy(
            showAllStemsConfirmation = false,
            statusMessage = msg
        )
        _uiState.value = _uiState.value.copy(statusLine = msg)
    }

    fun cancelStemSeparation() {
        stemFuture?.cancel(true)
        _stemState.value = _stemState.value.copy(
            isRunning = false,
            jobStatusLabel = "CANCELLED",
            statusMessage = "DISBAND STEM SEPARATION CANCELLED"
        )
    }

    private fun resolveSourceBufferForStems(): DesktopAudioBuffer? {
        val base = _uiState.value.processedBuffer ?: _uiState.value.dryBuffer ?: return null
        if (base.frameCount <= 0) return null
        if (!_stemState.value.useSelectionOnly) return base
        val sel = base.extractSelection(_uiState.value.selectionStart, _uiState.value.selectionEnd)
        return if (sel.frameCount > 0) sel else base
    }

    private fun launchDisbandSeparation(
        targetStems: List<DesktopDisbandStem>,
        source: DesktopAudioBuffer,
        apiKey: String
    ) {
        val baseUrl = secretsVault.resolveDisbandBaseUrl()
        stemFuture?.cancel(true)
        _stemState.value = _stemState.value.copy(
            isRunning = true,
            showAllStemsConfirmation = false,
            jobStatusLabel = "SUBMITTING",
            errorMessage = null,
            statusMessage = "DISBAND: REQUESTING ${targetStems.joinToString("+") { it.shortTag }}…"
        )
        stemFuture = stemExecutor.submit {
            val res = runCatching {
                disbandClient.separateAndDownloadStems(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    stems = targetStems,
                    sourceBuffer = source,
                    isCancelled = { Thread.currentThread().isInterrupted }
                ) { jobId, status, charge ->
                    _stemState.value = _stemState.value.copy(
                        activeJobId = jobId ?: _stemState.value.activeJobId,
                        jobStatusLabel = status,
                        lastCreditCharge = charge ?: _stemState.value.lastCreditCharge,
                        statusMessage = "DISBAND [${jobId ?: "…"}]: $status" +
                            (charge?.let { " ($it credits)" } ?: "")
                    )
                }
            }
            res.onSuccess { (job, tracks) ->
                val done = "DISBAND READY (${tracks.size} stems • ${job.charge ?: 0} credits)"
                _stemState.value = _stemState.value.copy(
                    isRunning = false,
                    activeJobId = job.id,
                    jobStatusLabel = "READY",
                    lastCreditCharge = job.charge,
                    separatedStems = tracks,
                    errorMessage = null,
                    lastToastMessage = done,
                    statusMessage = done
                )
                _uiState.value = _uiState.value.copy(statusLine = done)
            }.onFailure { err ->
                if (err is InterruptedException) return@onFailure
                val msg = err.message ?: "Disband separation failed"
                _stemState.value = _stemState.value.copy(
                    isRunning = false,
                    jobStatusLabel = "ERROR",
                    errorMessage = msg,
                    lastToastMessage = msg,
                    statusMessage = "DISBAND ERROR: $msg"
                )
                _uiState.value = _uiState.value.copy(statusLine = "DISBAND ERROR: $msg")
            }
        }
    }

    fun loadSeparatedStemToDeck(stemId: String) {
        val stem = _stemState.value.separatedStems.firstOrNull { it.stemId == stemId } ?: return
        loadNewBuffer(
            buffer = stem.buffer,
            banner = "LOADED STEM TO DECK: ${stem.label.uppercase()}",
            preserveLoopPlayback = true
        )
    }

    fun sendSeparatedStemToLooper(stemId: String, slotId: Int = 0) {
        val stem = _stemState.value.separatedStems.firstOrNull { it.stemId == stemId } ?: return
        arrangement.assignBufferToLoopSlot(slotId.coerceIn(0, 3), stem.buffer, stem.label)
        _uiState.value = _uiState.value.copy(statusLine = "STEM ${stem.label.uppercase()} -> LOOP ${'A' + slotId.coerceIn(0, 3)}")
    }

    fun addSeparatedStemToTimeline(stemId: String, trackId: Int) {
        val stem = _stemState.value.separatedStems.firstOrNull { it.stemId == stemId } ?: return
        arrangement.addClipToTimeline(trackId.coerceIn(0, 3), stem.buffer, startTimeSec = 0f)
        _uiState.value = _uiState.value.copy(statusLine = "STEM ${stem.label.uppercase()} -> TRACK ${trackId.coerceIn(0, 3) + 1}")
    }

    fun sendAllSeparatedStemsToTimeline() {
        val stems = _stemState.value.separatedStems
        if (stems.isEmpty()) return
        stems.forEachIndexed { idx, stem ->
            arrangement.addClipToTimeline(idx % 4, stem.buffer, startTimeSec = 0f)
        }
        _uiState.value = _uiState.value.copy(statusLine = "PLACED ALL ${stems.size} STEMS ONTO TRACKS 1–4")
    }

    fun sendAllSeparatedStemsToLooper() {
        val stems = _stemState.value.separatedStems
        if (stems.isEmpty()) return
        stems.take(4).forEachIndexed { idx, stem ->
            arrangement.assignBufferToLoopSlot(idx, stem.buffer, stem.label)
        }
        _uiState.value = _uiState.value.copy(
            statusLine = "LOADED ${stems.take(4).size} STEMS INTO LOOP SLOTS A–${'A' + (stems.take(4).size - 1)}"
        )
    }

    fun exportSeparatedStemWav(stemId: String, targetFile: File): Result<File> {
        val stem = _stemState.value.separatedStems.firstOrNull { it.stemId == stemId }
            ?: return Result.failure(IllegalArgumentException("Stem not found"))
        return runCatching {
            DesktopWavCodec.writeWav(stem.buffer, targetFile)
            _uiState.value = _uiState.value.copy(statusLine = "SAVED STEM WAV -> ${targetFile.absolutePath}")
            targetFile
        }
    }

    // --- AUDIO TOOLS SUITE & 10-STOP SPECTROGRAM ---

    fun setToolsTab(tab: DesktopToolsTab) {
        _toolsState.value = _toolsState.value.copy(activeTab = tab)
    }

    fun setSpectrogramPalette(palette: DesktopSpectrogramPalette) {
        _toolsState.value = _toolsState.value.copy(
            specPalette = palette,
            lastToolReport = "SPECTROGRAM GRADIENT: ${palette.name} (10 STOPS)"
        )
    }

    fun applyCustomTenStopGradient(rawHexStops: String): Result<DesktopSpectrogramPalette> {
        val res = DesktopSpectrogramPalette.parseTenHexStops(rawHexStops)
        res.onSuccess { custom ->
            _toolsState.value = _toolsState.value.copy(
                specPalette = custom,
                lastToolReport = "APPLIED CUSTOM 10-STOP SPECTROGRAM GRADIENT"
            )
            _uiState.value = _uiState.value.copy(statusLine = "APPLIED CUSTOM 10-STOP SPECTROGRAM GRADIENT")
        }.onFailure { err ->
            val msg = err.message ?: "Invalid 10-stop hex list"
            _toolsState.value = _toolsState.value.copy(lastToolReport = "GRADIENT ERROR: $msg")
            _uiState.value = _uiState.value.copy(statusLine = "GRADIENT ERROR: $msg")
        }
        return res
    }

    fun updateSpecFreqRange(lowHz: Float, highHz: Float) {
        val lo = minOf(lowHz, highHz).coerceIn(20f, 20000f)
        val hi = maxOf(lowHz, highHz).coerceIn(lo + 40f, 24000f)
        _toolsState.value = _toolsState.value.copy(specFreqLowHz = lo, specFreqHighHz = hi)
    }

    fun setSpecDeleteAttenuation(attenDb: Float) {
        _toolsState.value = _toolsState.value.copy(specDeleteAttenDb = attenDb.coerceIn(6f, 96f))
    }

    fun setInvertScope(scope: ChannelScope) {
        _toolsState.value = _toolsState.value.copy(invertScope = scope)
    }

    fun setInvertSelectionOnly(onlySel: Boolean) {
        _toolsState.value = _toolsState.value.copy(invertSelectionOnly = onlySel)
    }

    fun applySpectralDelete() {
        val dry = _uiState.value.dryBuffer ?: return
        val ui = _uiState.value
        val ts = _toolsState.value
        val updated = DesktopAudioToolsEngine.spectralDelete(
            buffer = dry,
            startFrac = ui.selectionStart,
            endFrac = ui.selectionEnd,
            lowFreqHz = ts.specFreqLowHz,
            highFreqHz = ts.specFreqHighHz,
            attenuationDb = ts.specDeleteAttenDb
        )
        val loSec = ui.selectionStart * dry.durationSeconds
        val hiSec = ui.selectionEnd * dry.durationSeconds
        val report = "SPECTRAL DELETE: ${"%.2f".format(loSec)}s–${"%.2f".format(hiSec)}s × ${ts.specFreqLowHz.toInt()}–${ts.specFreqHighHz.toInt()} Hz (-${ts.specDeleteAttenDb.toInt()} dB)"
        _toolsState.value = _toolsState.value.copy(lastToolReport = report)
        mutateDryBuffer(report, resetSelection = false) { updated }
    }

    fun applyInvertPolarity() {
        val dry = _uiState.value.dryBuffer ?: return
        val ui = _uiState.value
        val ts = _toolsState.value
        val sFrac = if (ts.invertSelectionOnly) ui.selectionStart else 0f
        val eFrac = if (ts.invertSelectionOnly) ui.selectionEnd else 1f
        val updated = DesktopAudioToolsEngine.invertPolarity(dry, sFrac, eFrac, ts.invertScope)
        val rangeLabel = if (ts.invertSelectionOnly) "SELECTION" else "FULL"
        val report = "PHASE INVERTED (${ts.invertScope.label} • $rangeLabel)"
        _toolsState.value = _toolsState.value.copy(lastToolReport = report)
        mutateDryBuffer(report) { updated }
    }

    fun loadReferenceFile(file: File): Result<DesktopAudioBuffer> {
        return runCatching {
            val buf = DesktopWavCodec.readWav(file)
            val label = "${file.nameWithoutExtension} (${"%.2f".format(buf.durationSeconds)}s @ ${buf.sampleRate}Hz)"
            _toolsState.value = _toolsState.value.copy(
                referenceBuffer = buf,
                referenceLabel = label,
                lastToolReport = "LOADED REF B: $label"
            )
            buf
        }.onFailure { err ->
            val msg = "REF LOAD FAILED: ${err.message}"
            _toolsState.value = _toolsState.value.copy(lastToolReport = msg)
            _uiState.value = _uiState.value.copy(statusLine = msg)
        }
    }

    fun selectReferenceFromSessionSource(sourceTag: String) {
        val candidate: DesktopAudioBuffer? = when (sourceTag) {
            "RAW" -> _uiState.value.rawBuffer
            "DRY" -> _uiState.value.dryBuffer
            "LOOP_A" -> arrangement.looperState.value.slots.getOrNull(0)?.buffer
            "LOOP_B" -> arrangement.looperState.value.slots.getOrNull(1)?.buffer
            "LOOP_C" -> arrangement.looperState.value.slots.getOrNull(2)?.buffer
            "LOOP_D" -> arrangement.looperState.value.slots.getOrNull(3)?.buffer
            else -> null
        }
        if (candidate == null || candidate.frameCount == 0) {
            _toolsState.value = _toolsState.value.copy(lastToolReport = "REF SOURCE $sourceTag IS EMPTY")
            return
        }
        val label = "$sourceTag (${"%.2f".format(candidate.durationSeconds)}s)"
        _toolsState.value = _toolsState.value.copy(
            referenceBuffer = candidate,
            referenceLabel = label,
            lastToolReport = "SET REF B: $label"
        )
    }

    fun computeAudioDifference() {
        val primary = _uiState.value.processedBuffer ?: _uiState.value.dryBuffer ?: return
        val ref = _toolsState.value.referenceBuffer ?: _uiState.value.rawBuffer ?: return
        val diff = DesktopAudioToolsEngine.computeAudioDifference(primary, ref)
        val summary = "DIFF (A-B): Peak=${"%.4f".format(diff.peakDiff)} • RMS=${"%.4f".format(diff.rmsDiff)} • Null=${"%.1f".format(diff.nullDepthDb)} dB • Corr=${"%.3f".format(diff.correlation)}"
        _toolsState.value = _toolsState.value.copy(
            lastDiffResult = diff,
            lastToolReport = summary
        )
        _uiState.value = _uiState.value.copy(statusLine = summary)
    }

    fun loadDiffResultToDeck() {
        val diff = _toolsState.value.lastDiffResult ?: return
        val banner = "LOADED DIFF (A-B) TO DECK (NULL ${"%.1f".format(diff.nullDepthDb)} dB)"
        _toolsState.value = _toolsState.value.copy(lastToolReport = banner)
        mutateDryBuffer(banner) { diff.diffBuffer }
    }

    fun updateMixParams(primaryGain: Float, secondaryGain: Float, offsetSec: Float, softLimit: Boolean) {
        _toolsState.value = _toolsState.value.copy(
            mixPrimaryGain = primaryGain.coerceIn(0f, 2f),
            mixSecondaryGain = secondaryGain.coerceIn(0f, 2f),
            mixOffsetSec = offsetSec.coerceIn(0f, 30f),
            mixSoftLimit = softLimit
        )
    }

    fun applyMixWithReference() {
        val dry = _uiState.value.dryBuffer ?: return
        val ref = _toolsState.value.referenceBuffer ?: run {
            _toolsState.value = _toolsState.value.copy(lastToolReport = "LOAD OR PICK A REFERENCE B AUDIO FOR MIX")
            return
        }
        val ts = _toolsState.value
        val mixed = DesktopAudioToolsEngine.mixBuffers(
            primary = dry,
            secondary = ref,
            primaryGain = ts.mixPrimaryGain,
            secondaryGain = ts.mixSecondaryGain,
            offsetSec = ts.mixOffsetSec,
            softClipLimiter = ts.mixSoftLimit
        )
        val report = "MIXED DECK (${"%.2f".format(ts.mixPrimaryGain)}x) + REF (${"%.2f".format(ts.mixSecondaryGain)}x @ +${"%.2f".format(ts.mixOffsetSec)}s)"
        _toolsState.value = _toolsState.value.copy(lastToolReport = report)
        mutateDryBuffer(report) { mixed }
    }

    fun setMonoFoldMode(mode: MonoFoldMode) {
        _toolsState.value = _toolsState.value.copy(monoFoldMode = mode)
    }

    fun applyMixToMono() {
        val dry = _uiState.value.dryBuffer ?: return
        val mode = _toolsState.value.monoFoldMode
        val monoBuf = DesktopAudioToolsEngine.mixToMono(dry, mode)
        val report = "CONVERTED TO MONO (${mode.label})"
        _toolsState.value = _toolsState.value.copy(lastToolReport = report)
        mutateDryBuffer(report) { monoBuf }
    }

    fun setStereoSplitParams(mode: StereoSplitMode, param: Float) {
        _toolsState.value = _toolsState.value.copy(
            stereoSplitMode = mode,
            stereoSplitParam = param
        )
    }

    fun applySplitToStereo() {
        val dry = _uiState.value.dryBuffer ?: return
        val ts = _toolsState.value
        val stereoBuf = DesktopAudioToolsEngine.splitToStereo(dry, ts.stereoSplitMode, ts.stereoSplitParam)
        val report = "SPLIT TO STEREO (${ts.stereoSplitMode.label})"
        _toolsState.value = _toolsState.value.copy(lastToolReport = report)
        mutateDryBuffer(report) { stereoBuf }
    }

    fun setRateConversionParams(sampleRate: Int, reinterpretOnly: Boolean) {
        _toolsState.value = _toolsState.value.copy(
            targetSampleRate = sampleRate.coerceIn(8000, 192000),
            reinterpretRateOnly = reinterpretOnly
        )
    }

    fun applyConvertSampleRate() {
        val dry = _uiState.value.dryBuffer ?: return
        val ts = _toolsState.value
        val converted = DesktopAudioToolsEngine.convertSampleRate(
            buffer = dry,
            targetSampleRate = ts.targetSampleRate,
            reinterpretHeaderOnly = ts.reinterpretRateOnly
        )
        val modeStr = if (ts.reinterpretRateOnly) "REINTERPRET VARISPEED" else "PITCH-LOCK RESAMPLE"
        val report = "SAMPLE RATE -> ${converted.sampleRate} Hz ($modeStr)"
        _toolsState.value = _toolsState.value.copy(lastToolReport = report)
        mutateDryBuffer(report) { converted }
    }

    fun setFormatConversionParams(format: DesktopSampleFormat, dither: Boolean) {
        _toolsState.value = _toolsState.value.copy(
            targetSampleFormat = format,
            applyDither = dither
        )
    }

    fun applyConvertSampleFormat() {
        val dry = _uiState.value.dryBuffer ?: return
        val ts = _toolsState.value
        val converted = DesktopAudioToolsEngine.convertSampleFormat(dry, ts.targetSampleFormat, ts.applyDither)
        val report = "FORMAT -> ${ts.targetSampleFormat.label}${if (ts.applyDither) " + TPDF DITHER" else ""}"
        _toolsState.value = _toolsState.value.copy(lastToolReport = report)
        mutateDryBuffer(report) { converted }
    }

    // --- DESKTOP DSP PLUGIN MANAGER & PLUGIN INSTALLER ---

    fun addPluginToChain(pluginId: String) {
        val def = _toolsState.value.installedPlugins.firstOrNull { it.id == pluginId } ?: return
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        val nextChain = _toolsState.value.pluginChain + def.createSlotInstance()
        _toolsState.value = _toolsState.value.copy(
            pluginChain = nextChain,
            lastToolReport = "ADDED PLUGIN: ${def.name} (${nextChain.size} in chain)"
        )
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun removePluginFromChain(instanceId: String) {
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        val nextChain = _toolsState.value.pluginChain.filterNot { it.instanceId == instanceId }
        _toolsState.value = _toolsState.value.copy(
            pluginChain = nextChain,
            lastToolReport = "REMOVED PLUGIN SLOT (${nextChain.size} remaining)"
        )
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun movePluginInChain(instanceId: String, delta: Int) {
        val cur = _toolsState.value.pluginChain.toMutableList()
        val idx = cur.indexOfFirst { it.instanceId == instanceId }
        val target = (idx + delta).coerceIn(0, (cur.size - 1).coerceAtLeast(0))
        if (idx < 0 || idx == target) return
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        val item = cur.removeAt(idx)
        cur.add(target, item)
        _toolsState.value = _toolsState.value.copy(
            pluginChain = cur,
            lastToolReport = "REORDERED ${item.pluginName} -> SLOT ${target + 1}"
        )
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun togglePluginBypass(instanceId: String) {
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        val nextChain = _toolsState.value.pluginChain.map { slot ->
            if (slot.instanceId == instanceId) slot.copy(bypassed = !slot.bypassed) else slot
        }
        _toolsState.value = _toolsState.value.copy(pluginChain = nextChain)
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun updatePluginWetMix(instanceId: String, wetMix: Float) {
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        val nextChain = _toolsState.value.pluginChain.map { slot ->
            if (slot.instanceId == instanceId) slot.copy(wetMix = wetMix.coerceIn(0f, 1f)) else slot
        }
        _toolsState.value = _toolsState.value.copy(pluginChain = nextChain)
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun updatePluginParam(instanceId: String, paramKey: String, value: Float) {
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        val nextChain = _toolsState.value.pluginChain.map { slot ->
            if (slot.instanceId == instanceId) {
                val updated = slot.paramValues.toMutableMap().apply { put(paramKey, value) }
                slot.copy(paramValues = updated)
            } else {
                slot
            }
        }
        _toolsState.value = _toolsState.value.copy(pluginChain = nextChain)
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun bouncePluginChainToDryBuffer() {
        val dry = _uiState.value.dryBuffer ?: return
        val ts = _toolsState.value
        if (ts.pluginChain.none { it.enabled && !it.bypassed }) {
            _toolsState.value = ts.copy(lastToolReport = "NO ACTIVE PLUGINS TO BOUNCE")
            return
        }
        val defs = ts.installedPlugins.associateBy { it.id }
        val rendered = DesktopPluginHostEngine.processChain(dry, ts.pluginChain, defs)
        val bypassedChain = ts.pluginChain.map { it.copy(bypassed = true) }
        val report = "BOUNCED ${ts.pluginChain.size} PLUGIN SLOTS INTO DECK BUFFER"
        _toolsState.value = ts.copy(
            pluginChain = bypassedChain,
            lastToolReport = report
        )
        mutateDryBuffer(report) { rendered }
    }

    fun installCatalogPlugin(entry: DesktopPluginCatalogEntry) {
        val saved = pluginRepo.installPlugin(entry.toDefinition())
        val all = pluginRepo.loadAllPlugins()
        val msg = "INSTALLED PLUGIN: ${saved.name} v${saved.version}"
        _toolsState.value = _toolsState.value.copy(
            installedPlugins = all,
            lastToolReport = msg
        )
        _uiState.value = _uiState.value.copy(statusLine = msg)
    }

    fun uninstallCustomPlugin(pluginId: String) {
        pluginRepo.uninstallPlugin(pluginId)
        val all = pluginRepo.loadAllPlugins()
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        val cleanChain = _toolsState.value.pluginChain.filterNot { it.pluginId == pluginId }
        _toolsState.value = _toolsState.value.copy(
            installedPlugins = all,
            pluginChain = cleanChain,
            lastToolReport = "UNINSTALLED PLUGIN ($pluginId)"
        )
        _uiState.value.dryBuffer?.let { reprocessDsp(it, preserveLoopPlayback = keepLoop) }
    }

    fun importPluginOrIrFile(file: File): Result<String> {
        return runCatching {
            val ext = file.extension.lowercase()
            if (ext == "json" || file.name.endsWith(".smpplugin.json", true)) {
                val parsed = DesktopPluginCatalogRegistry.fromJson(file.readText()).getOrThrow()
                pluginRepo.installPlugin(parsed)
                val all = pluginRepo.loadAllPlugins()
                val msg = "IMPORTED PLUGIN BUNDLE: ${parsed.name}"
                _toolsState.value = _toolsState.value.copy(installedPlugins = all, lastToolReport = msg)
                _uiState.value = _uiState.value.copy(statusLine = msg)
                msg
            } else {
                val irBuf = DesktopWavCodec.readWav(file)
                val pluginDef = DesktopPluginCatalogRegistry.createIrPluginFromBuffer(irBuf, file.nameWithoutExtension)
                val current = (_toolsState.value.installedPlugins + pluginDef).distinctBy { it.id }
                val msg = "LOADED WAV IMPULSE PLUGIN: ${pluginDef.name}"
                _toolsState.value = _toolsState.value.copy(installedPlugins = current, lastToolReport = msg)
                _uiState.value = _uiState.value.copy(statusLine = msg)
                msg
            }
        }.onFailure { err ->
            val msg = "PLUGIN/IR IMPORT FAILED: ${err.message}"
            _toolsState.value = _toolsState.value.copy(lastToolReport = msg)
            _uiState.value = _uiState.value.copy(statusLine = msg)
        }
    }

    fun rescanNativeLinuxPlugins(extraDirs: List<File> = emptyList()) {
        val found = pluginRepo.scanLinuxNativePlugins(extraDirs)
        val msg = "SCANNED LINUX PLUGINS: ${found.size} LV2/LADSPA/VST BUNDLES DETECTED"
        _toolsState.value = _toolsState.value.copy(
            nativeLinuxPlugins = found,
            lastToolReport = msg
        )
        _uiState.value = _uiState.value.copy(statusLine = msg)
    }

    fun shutdown() {
        stemFuture?.cancel(true)
        stemExecutor.shutdownNow()
        soundEngine.stopPlayback()
        soundEngine.stopMicAndCollectBuffer()
        arrangement.release()
        hwMidi.closeAll()
        netNode.release()
    }
}
