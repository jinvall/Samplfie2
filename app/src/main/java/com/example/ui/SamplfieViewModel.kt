package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioAnalyzer
import com.example.audio.AudioBuffer
import com.example.audio.AudioImporter
import com.example.audio.AudioPlaybackEngine
import com.example.audio.AudioSampleFormat
import com.example.audio.MicCaptureEngine
import com.example.audio.MicCaptureTelemetry
import com.example.audio.PlaybackState
import com.example.audio.SpectrogramAnalyzer
import com.example.audio.SpectrogramPalette
import com.example.audio.WavCodec
import com.example.config.RigConfig
import com.example.config.RigConfigRepository
import com.example.config.SecretsVaultRepository
import com.example.config.SecretsVaultState
import com.example.dsp.DspChainEngine
import com.example.dsp.DspModules
import com.example.dsp.FxPreset
import com.example.dsp.FxPresets
import com.example.dsp.FxRackState
import com.example.dsp.LiveSessionMode
import com.example.dsp.PitchSpeedParams
import com.example.dsp.StompboxMasterState
import com.example.export.ExportedAudioResult
import com.example.export.Mp3Exporter
import com.example.export.WavExporter
import com.example.midi.MidiControllerManager
import com.example.midi.MidiEvent
import com.example.midi.MidiState
import com.example.midi.VmcEngine
import com.example.midi.VmcEngineState
import com.example.midi.VmcMappingRule
import com.example.midi.VmcMsgType
import com.example.midi.VmcRouteMode
import com.example.midi.VmcTarget
import com.example.network.CollabInstrumentStyle
import com.example.network.CollabPeerDevice
import com.example.network.CollabSyncCommand
import com.example.network.CollabWireCodec
import com.example.network.LiveBandRecordingState
import com.example.network.MAX_COLLAB_PEERS
import com.example.network.NetworkAudioTransmitter
import com.example.network.NetworkLinkState
import com.example.network.SessionHandoffSnapshot
import com.example.drums.BeaterSide
import com.example.drums.CustomDrumKitState
import com.example.drums.DrumAddonType
import com.example.drums.DrumKitManager
import com.example.drums.DrumKitPreset
import com.example.drums.DrumKitStyle
import com.example.drums.DrumKitSynthesizerLibrary
import com.example.plugins.AndroidPluginRepository
import com.example.plugins.PluginCatalogEntry
import com.example.plugins.PluginCatalogRegistry
import com.example.plugins.SamplfiePluginDefinition
import com.example.stems.DisbandApiClient
import com.example.stems.DisbandHttpTransport
import com.example.stems.DisbandStem
import com.example.stems.SeparatedStemTrack
import com.example.stems.StemPreset
import com.example.stems.StemSeparationUiState
import com.example.studio.LooperState
import com.example.studio.SequencerState
import com.example.studio.StudioEngine
import com.example.studio.SyncedPlayheadState
import com.example.studio.TimelineState
import com.example.tools.AudioToolsEngine
import com.example.tools.ChannelScope
import com.example.tools.MonoFoldMode
import com.example.tools.StereoSplitMode
import com.example.tuner.PitchDetector
import com.example.tuner.TunerInputSource
import com.example.tuner.TuningPreset
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SamplfieViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val takesDir = File(appContext.filesDir, "takes").apply { mkdirs() }

    private val rigRepository = RigConfigRepository(appContext)
    val secretsVault = SecretsVaultRepository(appContext)
    @Volatile
    private var disbandClient = DisbandApiClient()
    private val micEngine: MicCaptureEngine = MicCaptureEngine { mono, sr ->
        if (_tunerState.value.source == TunerInputSource.LIVE_MIC) {
            analyzeSamplesForTuner(mono, sr)
        }
        val band = _bandRecState.value
        if (band.isRecording) {
            networkTransmitter.sendCollabLiveChunk(mono, sr, band.myStyle, band.myTrackSlot)
        }
    }
    private val playbackEngine: AudioPlaybackEngine = AudioPlaybackEngine { mono, sr ->
        if (_tunerState.value.source == TunerInputSource.DECK_BUFFER && playbackEngine.state.value.isPlaying) {
            analyzeSamplesForTuner(mono, sr)
        }
    }
    val vmcEngine: VmcEngine = VmcEngine(
        onExecuteTarget = { target, norm -> executeVmcTarget(target, norm) },
        onTransmitPeerVmc = { pkt -> networkTransmitter.sendVmcEvent(pkt, _rigConfig.value) }
    )
    private val networkTransmitter: NetworkAudioTransmitter = NetworkAudioTransmitter(
        onStreamChunkForTuner = { mono, sr ->
            if (_tunerState.value.source == TunerInputSource.SMP2_STREAM) {
                analyzeSamplesForTuner(mono, sr)
            }
        },
        onVmcEventReceived = { pkt -> vmcEngine.processIncomingEvent(pkt, fromPeer = true) },
        onSessionHandoffReceived = { snap ->
            viewModelScope.launch(Dispatchers.Main) {
                if (_rigConfig.value.autoAcceptIncomingHandoff) {
                    applySessionHandoff(snap)
                } else {
                    _editorState.value = _editorState.value.copy(
                        pendingHandoff = snap,
                        statusBanner = "HANDOFF READY FROM ${snap.senderName} — TAP APPLY"
                    )
                }
            }
        },
        onHandoffRequestedByPeer = { host, replyPort ->
            val snap = captureSessionSnapshot()
            networkTransmitter.sendSessionHandoff(snap, _rigConfig.value.protocol, host, replyPort)
        },
        onCollabSyncCommandReceived = { cmd ->
            viewModelScope.launch(Dispatchers.Main) {
                if (cmd.start) {
                    if (!_bandRecState.value.isRecording) {
                        setStudioBpm(cmd.bpm)
                        startLiveBandRecording(broadcastToPeers = false, syncedTakeName = cmd.takeName)
                    }
                } else {
                    if (_bandRecState.value.isRecording) {
                        stopLiveBandRecording(broadcastStop = false)
                    }
                }
            }
        }
    )
    private val midiController = MidiControllerManager(appContext)
    private val studioEngine = StudioEngine()
    val drumKitManager = DrumKitManager()
    private val pluginRepo = AndroidPluginRepository(File(appContext.filesDir, "plugins"))

    private val undoStack = ArrayDeque<AudioBuffer>()
    private var fxRenderJob: Job? = null
    private var stemJob: Job? = null
    private val rigSaveMutex = Mutex()
    private var lastTapMs: Long = 0L

    @Volatile
    private var hasRigLocalEdit = false

    private val _selectedSpoke = MutableStateFlow(StudioSpoke.BOOTH)
    val selectedSpoke: StateFlow<StudioSpoke> = _selectedSpoke.asStateFlow()

    private val _workspaceConfig = MutableStateFlow(WorkspaceConfig())
    val workspaceConfig: StateFlow<WorkspaceConfig> = _workspaceConfig.asStateFlow()

    private val _tunerState = MutableStateFlow(TunerUiState())
    val tunerState: StateFlow<TunerUiState> = _tunerState.asStateFlow()

    private val _rigConfig = MutableStateFlow(RigConfig())
    val rigConfig: StateFlow<RigConfig> = _rigConfig.asStateFlow()

    private val _fxRack = MutableStateFlow(FxRackState())
    val fxRack: StateFlow<FxRackState> = _fxRack.asStateFlow()

    private val _stompbox = MutableStateFlow(StompboxMasterState())
    val stompbox: StateFlow<StompboxMasterState> = _stompbox.asStateFlow()

    private val _editorState = MutableStateFlow(EditorState())
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    private val _loopEditorState = MutableStateFlow(LoopEditorState())
    val loopEditorState: StateFlow<LoopEditorState> = _loopEditorState.asStateFlow()

    private val _toolsState = MutableStateFlow(
        ToolsAndPluginsState(installedPlugins = pluginRepo.loadAllPlugins())
    )
    val toolsState: StateFlow<ToolsAndPluginsState> = _toolsState.asStateFlow()

    private val _stemState = MutableStateFlow(StemSeparationUiState())
    val stemState: StateFlow<StemSeparationUiState> = _stemState.asStateFlow()

    private val _showSecretsVault = MutableStateFlow(false)
    val showSecretsVault: StateFlow<Boolean> = _showSecretsVault.asStateFlow()

    private val _showCollabSheet = MutableStateFlow(false)
    val showCollabSheet: StateFlow<Boolean> = _showCollabSheet.asStateFlow()

    private val _bandRecState = MutableStateFlow(LiveBandRecordingState())
    val bandRecState: StateFlow<LiveBandRecordingState> = _bandRecState.asStateFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    val secretsState: StateFlow<SecretsVaultState> = secretsVault.state
    val micTelemetry: StateFlow<MicCaptureTelemetry> = micEngine.telemetry
    val playbackState: StateFlow<PlaybackState> = playbackEngine.state
    val networkState: StateFlow<NetworkLinkState> = networkTransmitter.state
    val collabPeers: StateFlow<List<CollabPeerDevice>> = networkTransmitter.collabPeers
    val midiState: StateFlow<MidiState> = midiController.state
    val vmcState: StateFlow<VmcEngineState> = vmcEngine.state
    val looperState: StateFlow<LooperState> = studioEngine.looperState
    val sequencerState: StateFlow<SequencerState> = studioEngine.sequencerState
    val timelineState: StateFlow<TimelineState> = studioEngine.timelineState
    val syncedPlayhead: StateFlow<SyncedPlayheadState> = studioEngine.syncedPlayhead
    val customDrumKitState: StateFlow<CustomDrumKitState> = drumKitManager.state

    init {
        viewModelScope.launch {
            val loaded = rigRepository.configFlow.first()
            if (!hasRigLocalEdit) {
                _rigConfig.value = loaded
                playbackEngine.setMasterVolume(loaded.masterVolume, loaded.isMasterMuted)
                studioEngine.setMasterVolume(loaded.masterVolume, loaded.isMasterMuted)
                val style = CollabInstrumentStyle.fromOrdinalOrName(null, loaded.myStyleName)
                _bandRecState.value = _bandRecState.value.copy(
                    myStyle = style,
                    myTrackSlot = style.defaultTrack
                )
                networkTransmitter.seedKnownPeers(CollabWireCodec.parseKnownPeers(loaded.knownPeersSpec))
                syncLocalPresence()
            }
        }
        refreshSavedTakes()
        midiController.start()
        observeMidiEvents()
        syncLocalPresence()
        networkTransmitter.startDiscovery()
    }

    private fun observeMidiEvents() {
        viewModelScope.launch {
            midiController.events.collect { event ->
                vmcEngine.processAndroidHardwareMidi(event, busIndex = 0)
                when (event) {
                    is MidiEvent.NoteOn -> {
                        val drumHit = if (event.note in 35..59) {
                            drumKitManager.synthesizeByMidiNote(event.note, _rigConfig.value.sampleRate)
                        } else null
                        if (drumHit != null) {
                            studioEngine.playOneShot(drumHit)
                        } else if (event.note in 48..72) {
                            _editorState.value.processedBuffer ?: return@collect
                            val semitones = (event.note - 60).toFloat()
                            updateFxRack {
                                copy(
                                    pitchSpeed = pitchSpeed.copy(
                                        enabled = true,
                                        pitchSemitones = semitones
                                    )
                                )
                            }
                            playbackEngine.seekToFraction(0f)
                            playbackEngine.play(onlySelection = _editorState.value.playSelectionOnly)
                        }
                    }
                    MidiEvent.ClockTick -> {
                        midiController.state.value.syncedBpm?.let { bpm ->
                            setStudioBpm(bpm)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun selectSpoke(spoke: StudioSpoke) {
        _selectedSpoke.value = spoke
        if (spoke == StudioSpoke.TUNER && _tunerState.value.source == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
    }

    fun toggleRadialWheelSpoke(spoke: StudioSpoke) {
        val cur = _workspaceConfig.value.radialWheelSpokes.toMutableList()
        if (cur.contains(spoke)) {
            if (cur.size > 4) cur.remove(spoke)
        } else if (cur.size < 8) {
            cur.add(spoke)
        }
        _workspaceConfig.value = _workspaceConfig.value.copy(radialWheelSpokes = cur)
    }

    fun toggleDockSpokePinned(spoke: StudioSpoke) {
        if (spoke == StudioSpoke.BOOTH) return
        val cur = _workspaceConfig.value.pinnedDockSpokes.toMutableList()
        if (cur.contains(spoke)) {
            if (cur.size > 4) cur.remove(spoke)
        } else {
            cur.add(spoke)
        }
        _workspaceConfig.value = _workspaceConfig.value.copy(pinnedDockSpokes = cur)
    }

    fun setVisualizerMode(mode: VisualizerMode) {
        _editorState.value = _editorState.value.copy(visualizerMode = mode)
    }

    // --- TAC HOLD MIC CAPTURE & UNIVERSAL AUDIO IMPORT ---

    fun onTacPressStart() {
        playbackEngine.pause()
        val cfg = _rigConfig.value
        val result = micEngine.startCapture(
            sampleRate = cfg.sampleRate,
            channels = cfg.channels,
            bufferSizeFrames = cfg.bufferSize
        )
        if (result.isFailure) {
            _editorState.value = _editorState.value.copy(
                statusBanner = result.exceptionOrNull()?.message ?: "Mic capture unavailable"
            )
        } else {
            _editorState.value = _editorState.value.copy(statusBanner = "CAPTURING LIVE MIC AUDIO…")
        }
    }

    fun onTacPressRelease() {
        viewModelScope.launch {
            val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val takeName = "TAKE_$stamp"
            val outFile = File(takesDir, "$takeName.wav")
            val result = micEngine.stopCaptureAndSaveWav(outFile, takeName)
            result.onSuccess { buffer ->
                loadNewRawBuffer(buffer, "LOADED $takeName.wav (${"%.2f".format(buffer.durationSeconds)}s)")
                refreshSavedTakes()
            }.onFailure { err ->
                _editorState.value = _editorState.value.copy(
                    statusBanner = err.message ?: "Hold TAC button longer to capture"
                )
            }
        }
    }

    fun importAudioUri(uri: Uri) {
        viewModelScope.launch {
            _editorState.value = _editorState.value.copy(
                isImportingAudio = true,
                statusBanner = "DECODING IMPORTED AUDIO AS .WAV…"
            )
            val result = AudioImporter.importUriAsWav(
                context = appContext,
                uri = uri,
                takesDir = takesDir,
                targetSampleRate = _rigConfig.value.sampleRate
            )
            result.onSuccess { (buffer, wavFile) ->
                _editorState.value = _editorState.value.copy(isImportingAudio = false)
                loadNewRawBuffer(
                    buffer,
                    "IMPORTED ${wavFile.name} (${"%.2f".format(buffer.durationSeconds)}s)"
                )
                refreshSavedTakes()
                _selectedSpoke.value = StudioSpoke.DECK
            }.onFailure { err ->
                _editorState.value = _editorState.value.copy(
                    isImportingAudio = false,
                    statusBanner = "IMPORT FAILED: ${err.message ?: "Unsupported format"}"
                )
            }
        }
    }

    fun generateReferenceOscillatorTone() {
        val cfg = _rigConfig.value
        val sr = cfg.sampleRate
        val ch = cfg.channels
        val durationSec = 1.6f
        val frames = (sr * durationSec).toInt()
        val samples = FloatArray(frames * ch)
        for (f in 0 until frames) {
            val t = f.toFloat() / sr
            val env = (1f - exp(-t * 24f)) * exp(-t * 1.1f)
            val fundamental = sin(2.0 * PI * 220.0 * t)
            val fifth = sin(2.0 * PI * 329.63 * t) * 0.55
            val octave = sin(2.0 * PI * 440.0 * t) * 0.35
            val v = ((fundamental + fifth + octave) * 0.42 * env).toFloat().coerceIn(-1f, 1f)
            for (c in 0 until ch) {
                samples[f * ch + c] = v
            }
        }
        val oscBuffer = AudioBuffer(samples, sr, ch, "OSC_SYNTH")
        val outFile = File(takesDir, "OSC_SYNTH.wav")
        viewModelScope.launch(Dispatchers.IO) {
            WavCodec.writeWav(oscBuffer, outFile)
            withContext(Dispatchers.Main) {
                loadNewRawBuffer(oscBuffer, "OSC SYNTH LOADED (${"%.2f".format(durationSec)}s)")
                refreshSavedTakes()
            }
        }
    }

    fun loadSavedTake(item: SavedTakeItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { WavCodec.readWav(item.file) }.onSuccess { buf ->
                withContext(Dispatchers.Main) {
                    loadNewRawBuffer(buf, "LOADED ${item.name}.wav")
                }
            }
        }
    }

    private fun loadNewRawBuffer(buffer: AudioBuffer, banner: String, preserveLoopPlayback: Boolean = false) {
        undoStack.clear()
        val keepLoop = preserveLoopPlayback && playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        if (!keepLoop) {
            playbackEngine.stop()
        }
        _editorState.value = _editorState.value.copy(
            rawBuffer = buffer,
            dryBuffer = buffer,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = false,
            statusBanner = banner
        )
        playbackEngine.setSelectionBounds(0f, 1f, _editorState.value.playSelectionOnly)
        studioEngine.setSequencerSampleForLane(0, buffer)
        reprocessFxChain(buffer, preserveLoopPlayback = keepLoop)
    }

    private fun refreshSavedTakes() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = takesDir.listFiles { f -> f.extension.equals("wav", true) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(10)
                ?: emptyList()
            val items = files.mapNotNull { file ->
                runCatching {
                    val buf = WavCodec.readWav(file)
                    SavedTakeItem(file.nameWithoutExtension, file, buf.durationSeconds)
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                _editorState.value = _editorState.value.copy(savedTakes = items)
            }
        }
    }

    // --- WAVEFORM SELECTION & EXTRACTION ---

    fun updateSelection(start: Float, end: Float) {
        val lo = minOf(start, end).coerceIn(0f, 0.98f)
        val hi = maxOf(start, end).coerceIn(lo + 0.02f, 1f)
        _editorState.value = _editorState.value.copy(selectionStart = lo, selectionEnd = hi)
        playbackEngine.setSelectionBounds(lo, hi, _editorState.value.playSelectionOnly)
        if (_tunerState.value.source == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
    }

    fun extractSelection() {
        val currentDry = _editorState.value.dryBuffer ?: return
        val start = _editorState.value.selectionStart
        val end = _editorState.value.selectionEnd
        if (end - start <= 0.01f) return

        undoStack.addLast(currentDry)
        val extracted = currentDry.extractSelection(start, end)
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        if (!keepLoop) {
            playbackEngine.stop()
        }
        _editorState.value = _editorState.value.copy(
            dryBuffer = extracted,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = undoStack.isNotEmpty(),
            statusBanner = "EXTRACTED ${"%.2f".format(extracted.durationSeconds)}s${if (keepLoop) " • LOOP PERSISTING" else ""}"
        )
        playbackEngine.setSelectionBounds(0f, 1f, _editorState.value.playSelectionOnly)
        studioEngine.setSequencerSampleForLane(0, extracted)
        reprocessFxChain(extracted, preserveLoopPlayback = keepLoop)
    }

    fun undoExtraction() {
        val prev = undoStack.removeLastOrNull() ?: return
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        if (!keepLoop) {
            playbackEngine.stop()
        }
        _editorState.value = _editorState.value.copy(
            dryBuffer = prev,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = undoStack.isNotEmpty(),
            statusBanner = if (keepLoop) "RESTORED PREVIOUS EDIT (LOOP ACTIVE)" else "RESTORED PREVIOUS EDIT"
        )
        playbackEngine.setSelectionBounds(0f, 1f, _editorState.value.playSelectionOnly)
        studioEngine.setSequencerSampleForLane(0, prev)
        reprocessFxChain(prev, preserveLoopPlayback = keepLoop)
    }

    fun resetToOriginalTake() {
        val raw = _editorState.value.rawBuffer ?: return
        undoStack.clear()
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        if (!keepLoop) {
            playbackEngine.stop()
        }
        _editorState.value = _editorState.value.copy(
            dryBuffer = raw,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = false,
            statusBanner = if (keepLoop) "RESET TO ORIGINAL .WAV (LOOP ACTIVE)" else "RESET TO ORIGINAL .WAV"
        )
        playbackEngine.setSelectionBounds(0f, 1f, _editorState.value.playSelectionOnly)
        studioEngine.setSequencerSampleForLane(0, raw)
        reprocessFxChain(raw, preserveLoopPlayback = keepLoop)
    }

    // --- PLAYBACK REVIEW ---

    fun togglePlay() = playbackEngine.togglePlay(onlySelection = _editorState.value.playSelectionOnly)
    fun stopPlayback() = playbackEngine.stop()
    fun toggleLoop() = playbackEngine.setLooping(!playbackEngine.state.value.isLooping)
    fun togglePlaySelectionOnly() {
        val next = !_editorState.value.playSelectionOnly
        _editorState.value = _editorState.value.copy(playSelectionOnly = next)
        playbackEngine.setPlaySelectionOnly(next)
    }
    fun seekPlayhead(fraction: Float) = playbackEngine.seekToFraction(fraction)

    // --- 12-MODULE FX RACK & LIVE STOMPBOX MASTER PROCESSOR ---

    fun updateFxRack(transform: FxRackState.() -> FxRackState) {
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        _fxRack.value = _fxRack.value.transform()
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
    }

    fun applyFxPreset(preset: FxPreset) {
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        _fxRack.value = FxPresets.build(preset)
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
        _editorState.value = _editorState.value.copy(statusBanner = "PRESET: ${preset.label}")
    }

    fun toggleMasterBypass() = updateFxRack { copy(masterBypass = !masterBypass) }

    fun updateStompbox(transform: StompboxMasterState.() -> StompboxMasterState) {
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        _stompbox.value = _stompbox.value.transform()
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
    }

    fun toggleStompboxBypass() = updateStompbox { copy(engaged = !engaged) }

    fun setLiveSessionMode(mode: LiveSessionMode) {
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        _stompbox.value = _stompbox.value.copy(liveMode = mode)
        when (mode) {
            LiveSessionMode.STAGE_DJ -> {
                _editorState.value = _editorState.value.copy(statusBanner = "LIVE MODE: STAGE DJ PERFORMANCE")
            }
            LiveSessionMode.LIVE_REC -> {
                _editorState.value = _editorState.value.copy(statusBanner = "LIVE MODE: CLEAN FIELD / BOOTH REC")
            }
            LiveSessionMode.STUDIO_RELEASE -> {
                _fxRack.value = _fxRack.value.let { rack ->
                    if (rack.activeCount == 0) FxPresets.build(FxPreset.STUDIO_MASTER) else rack
                }
                _editorState.value = _editorState.value.copy(statusBanner = "LIVE MODE: COLLAB STUDIO RELEASE")
            }
        }
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
    }

    fun onStompXyDrag(x: Float, y: Float) {
        val cx = x.coerceIn(0f, 1f)
        val cy = y.coerceIn(0f, 1f)
        updateStompbox {
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
                setStudioBpm(bpm)
            }
        }
        lastTapMs = now
    }

    private fun reprocessFxChain(dry: AudioBuffer, preserveLoopPlayback: Boolean = false) {
        fxRenderJob?.cancel()
        val rack = _fxRack.value
        val stomp = _stompbox.value
        val bpm = studioEngine.syncedPlayhead.value.bpm
        val pluginChain = _toolsState.value.pluginChain
        val pluginDefs = _toolsState.value.installedPlugins.associateBy { it.id }
        _editorState.value = _editorState.value.copy(isProcessingFx = true)
        fxRenderJob = viewModelScope.launch(Dispatchers.Default) {
            val processed = DspChainEngine.process(
                source = dry,
                rack = rack,
                stompbox = stomp,
                bpm = bpm,
                pluginChain = pluginChain,
                pluginDefs = pluginDefs
            )
            val dryPeaks = AudioAnalyzer.computeWaveformPeaks(dry, 140)
            val procPeaks = AudioAnalyzer.computeWaveformPeaks(processed, 140)
            val spectrum = AudioAnalyzer.computeSpectrumBands(processed, processed.frameCount / 2, 512, 32)
            val spectrogram = SpectrogramAnalyzer.compute(processed, maxFrames = 128, freqBins = 72)
            withContext(Dispatchers.Main) {
                _editorState.value = _editorState.value.copy(
                    processedBuffer = processed,
                    dryPeaks = dryPeaks,
                    processedPeaks = procPeaks,
                    staticSpectrum = spectrum,
                    spectrogram = spectrogram,
                    isProcessingFx = false
                )
                playbackEngine.updateBufferHot(
                    processed,
                    _rigConfig.value.bufferSize,
                    preserveLoopPlayback = preserveLoopPlayback
                )
                if (_tunerState.value.source == TunerInputSource.DECK_BUFFER) {
                    analyzeActiveBufferWindowForTuner()
                }
            }
        }
    }

    // --- SAMPLES SPOKE: LOOPER + LOOP SAMPLE REFINEMENT EDITOR ---

    fun sendEditorToLoopSlot(slotId: Int) {
        val buf = activeOutputBuffer() ?: return
        studioEngine.assignBufferToLoopSlot(slotId, buf, buf.name)
        loadLoopSlotIntoRefiner(slotId)
        _editorState.value = _editorState.value.copy(statusBanner = "ASSIGNED TO LOOP ${'A' + slotId}")
    }

    fun loadLoopSlotIntoRefiner(slotId: Int) {
        val slot = studioEngine.looperState.value.slots.firstOrNull { it.id == slotId }
        val buf = slot?.buffer ?: activeOutputBuffer()
        if (buf == null) {
            _loopEditorState.value = _loopEditorState.value.copy(
                selectedSlotId = slotId,
                rawSlotBuffer = null,
                workingBuffer = null,
                statusNote = "SLOT ${'A' + slotId} IS EMPTY — ASSIGN TAKE FROM DECK"
            )
            return
        }
        if (slot?.buffer == null) {
            studioEngine.assignBufferToLoopSlot(slotId, buf, buf.name)
        }
        viewModelScope.launch(Dispatchers.Default) {
            val peaks = AudioAnalyzer.computeWaveformPeaks(buf, 110)
            withContext(Dispatchers.Main) {
                _loopEditorState.value = LoopEditorState(
                    selectedSlotId = slotId,
                    rawSlotBuffer = buf,
                    workingBuffer = buf,
                    peaks = peaks,
                    loopStartFrac = 0f,
                    loopEndFrac = 1f,
                    statusNote = "REFINING LOOP ${'A' + slotId} (${"%.2f".format(buf.durationSeconds)}s)"
                )
            }
        }
    }

    fun updateLoopRefinerSelection(start: Float, end: Float) {
        val lo = minOf(start, end).coerceIn(0f, 0.98f)
        val hi = maxOf(start, end).coerceIn(lo + 0.02f, 1f)
        _loopEditorState.value = _loopEditorState.value.copy(loopStartFrac = lo, loopEndFrac = hi)
    }

    fun snapLoopSelectionToZeroCrossing() {
        val buf = _loopEditorState.value.workingBuffer ?: return
        val s = _loopEditorState.value.loopStartFrac
        val e = _loopEditorState.value.loopEndFrac
        val snappedS = buf.snapFractionToZeroCrossing(s)
        val snappedE = buf.snapFractionToZeroCrossing(e).coerceAtLeast((snappedS + 0.02f).coerceAtMost(1f))
        _loopEditorState.value = _loopEditorState.value.copy(
            loopStartFrac = snappedS,
            loopEndFrac = snappedE,
            statusNote = "SNAPPED IN/OUT TO ZERO-CROSSING"
        )
    }

    fun setLoopRefinerFades(fadeInMs: Float, fadeOutMs: Float) {
        _loopEditorState.value = _loopEditorState.value.copy(
            fadeInMs = fadeInMs.coerceIn(0f, 200f),
            fadeOutMs = fadeOutMs.coerceIn(0f, 200f)
        )
    }

    fun trimLoopRefiner() {
        val st = _loopEditorState.value
        val cur = st.workingBuffer ?: return
        val trimmed = cur.extractSelection(st.loopStartFrac, st.loopEndFrac, "${cur.name} (LoopTrim)")
        updateLoopWorkingBuffer(trimmed, "TRIMMED LOOP (${"%.2f".format(trimmed.durationSeconds)}s)")
    }

    fun applyLoopRefinerFades() {
        val st = _loopEditorState.value
        val cur = st.workingBuffer ?: return
        val faded = cur.applyFadeInOut(st.fadeInMs, st.fadeOutMs)
        updateLoopWorkingBuffer(faded, "APPLIED ${st.fadeInMs.toInt()}/${st.fadeOutMs.toInt()}ms FADES")
    }

    fun reverseLoopRefiner() {
        val cur = _loopEditorState.value.workingBuffer ?: return
        updateLoopWorkingBuffer(cur.reversed(), "REVERSED LOOP SAMPLE")
        _loopEditorState.value = _loopEditorState.value.copy(isReversed = !_loopEditorState.value.isReversed)
    }

    fun normalizeLoopRefiner() {
        val cur = _loopEditorState.value.workingBuffer ?: return
        updateLoopWorkingBuffer(cur.normalizePeak(), "NORMALIZED PEAK TO -0.3dB")
    }

    fun removeDcLoopRefiner() {
        val cur = _loopEditorState.value.workingBuffer ?: return
        updateLoopWorkingBuffer(cur.removeDcOffset(), "DC OFFSET REMOVED")
    }

    fun applyLoopRefinerPitchSpeed(pitchSemis: Float, speedFactor: Float) {
        val raw = _loopEditorState.value.rawSlotBuffer ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val tuned = DspModules.applyPitchAndSpeed(
                raw.samples,
                raw.sampleRate,
                raw.channels,
                PitchSpeedParams(enabled = true, pitchSemitones = pitchSemis, speedFactor = speedFactor)
            )
            val next = raw.copy(samples = tuned)
            val peaks = AudioAnalyzer.computeWaveformPeaks(next, 110)
            withContext(Dispatchers.Main) {
                _loopEditorState.value = _loopEditorState.value.copy(
                    workingBuffer = next,
                    peaks = peaks,
                    pitchSemitones = pitchSemis,
                    speedFactor = speedFactor,
                    statusNote = "TUNED ${if (pitchSemis >= 0) "+" else ""}${"%.1f".format(pitchSemis)}st • ${"%.2f".format(speedFactor)}x"
                )
            }
        }
    }

    fun resetLoopRefiner() {
        val raw = _loopEditorState.value.rawSlotBuffer ?: return
        updateLoopWorkingBuffer(raw, "RESET LOOP TO SLOT ORIGINAL")
        _loopEditorState.value = _loopEditorState.value.copy(
            pitchSemitones = 0f,
            speedFactor = 1.0f,
            isReversed = false
        )
    }

    fun auditionLoopRefiner() {
        val st = _loopEditorState.value
        val cur = st.workingBuffer ?: return
        val clip = cur.extractSelection(st.loopStartFrac, st.loopEndFrac)
        playbackEngine.setBuffer(clip, _rigConfig.value.bufferSize)
        playbackEngine.setSelectionBounds(0f, 1f)
        playbackEngine.setLooping(true)
        playbackEngine.play(onlySelection = false)
    }

    fun commitRefinerToLoopSlot() {
        val st = _loopEditorState.value
        val refined = st.workingBuffer ?: return
        studioEngine.assignBufferToLoopSlot(st.selectedSlotId, refined, refined.name)
        _loopEditorState.value = st.copy(
            rawSlotBuffer = refined,
            statusNote = "COMMITTED REFINED LOOP TO SLOT ${'A' + st.selectedSlotId}"
        )
    }

    fun sendRefinerToDeck() {
        val refined = _loopEditorState.value.workingBuffer ?: return
        loadNewRawBuffer(refined, "SENT REFINED LOOP TO DECK")
        _selectedSpoke.value = StudioSpoke.DECK
    }

    private fun updateLoopWorkingBuffer(next: AudioBuffer, note: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val peaks = AudioAnalyzer.computeWaveformPeaks(next, 110)
            withContext(Dispatchers.Main) {
                _loopEditorState.value = _loopEditorState.value.copy(
                    workingBuffer = next,
                    peaks = peaks,
                    loopStartFrac = 0f,
                    loopEndFrac = 1f,
                    statusNote = note
                )
            }
        }
    }

    fun toggleLoopSlot(slotId: Int) = studioEngine.toggleLoopSlotActive(slotId)
    fun toggleLoopMute(slotId: Int) = studioEngine.toggleLoopSlotMute(slotId)
    fun clearLoopSlot(slotId: Int) = studioEngine.clearLoopSlot(slotId)
    fun setLoopSlotBars(slotId: Int, bars: Int) = studioEngine.setLoopSlotBars(slotId, bars)
    fun toggleLooperPlayback() = studioEngine.toggleLooperPlayback()

    fun bounceLooperToEditor() {
        val mix = studioEngine.bounceLooperMixdown(_rigConfig.value.sampleRate)
        loadNewRawBuffer(mix, "BOUNCED LOOPER MIX TO DECK")
        _selectedSpoke.value = StudioSpoke.DECK
    }

    // --- INSTRUMENTS SPOKE: SEQUENCER, PADS & MIDI ---

    fun sendEditorToSequencerLane(laneId: Int = 0) {
        val buf = activeOutputBuffer() ?: return
        studioEngine.setSequencerSampleForLane(laneId, buf)
        _editorState.value = _editorState.value.copy(statusBanner = "SAMPLE ASSIGNED TO SEQ LANE ${laneId + 1}")
    }

    fun triggerSequencerLaneOneShot(laneId: Int) {
        val lane = studioEngine.sequencerState.value.lanes.getOrNull(laneId) ?: return
        playbackEngine.setBuffer(lane.buffer, _rigConfig.value.bufferSize)
        playbackEngine.setLooping(false)
        playbackEngine.play(onlySelection = false)
    }

    fun toggleSequencerStep(laneId: Int, step: Int) = studioEngine.toggleSequencerStep(laneId, step)
    fun toggleSequencerLaneMute(laneId: Int) = studioEngine.toggleSequencerLaneMute(laneId)
    fun toggleSequencerPlayback() = studioEngine.toggleSequencerPlayback()
    fun setStudioBpm(bpm: Float) {
        studioEngine.setBpm(bpm)
        if (_fxRack.value.delay.enabled && _fxRack.value.delay.bpmSync) {
            _editorState.value.dryBuffer?.let { reprocessFxChain(it) }
        }
    }
    fun setSequencerSwing(swing: Float) = studioEngine.setSequencerSwing(swing)

    fun bounceSequencerToTimeline() {
        val beat = studioEngine.renderSequenceToBuffer(bars = 2, sampleRate = _rigConfig.value.sampleRate)
        studioEngine.addClipToTimeline(2, beat, startTimeSec = 0f)
        _editorState.value = _editorState.value.copy(statusBanner = "BOUNCED 2-BAR BEAT TO TIMELINE")
    }

    // --- ARRANGEABLE CUSTOM ELECTRIC & ACOUSTIC DRUM KIT (UP TO 7 MAIN + MOUNTED ADD-ONS) ---

    fun setDrumKitStyle(style: DrumKitStyle, convertExisting: Boolean = true) {
        drumKitManager.setKitStyle(style, convertExisting)
        _editorState.value = _editorState.value.copy(statusBanner = "DRUM KIT -> ${style.label.uppercase()}")
    }

    fun applyDrumKitPreset(preset: DrumKitPreset) {
        drumKitManager.applyPreset(preset)
        _editorState.value = _editorState.value.copy(statusBanner = "DRUM KIT: ${preset.label.uppercase()}")
    }

    fun toggleDrumArrangeMode() = drumKitManager.toggleArrangeMode()
    fun flipDrumKitLeftHanded() = drumKitManager.flipLeftHanded()
    fun selectDrumSlot(slotId: String?) = drumKitManager.selectSlot(slotId)
    fun setTargetBeaterForMount(side: BeaterSide) = drumKitManager.setTargetBeaterForMount(side)

    fun addMainDrumPiece(componentId: String, xNorm: Float? = null, yNorm: Float? = null): Boolean {
        val added = drumKitManager.addMainPiece(componentId, xNorm, yNorm)
        if (added == null) {
            drumKitManager.state.value.statusError?.let { _toastEvents.tryEmit(it) }
            return false
        }
        return true
    }

    fun swapMainDrumPiece(slotId: String, newComponentId: String) =
        drumKitManager.swapMainPiece(slotId, newComponentId)

    fun removeMainDrumPiece(slotId: String) = drumKitManager.removeMainPiece(slotId)

    fun moveDrumPieceOnStage(slotId: String, xNorm: Float, yNorm: Float) =
        drumKitManager.movePieceOnStage(slotId, xNorm, yNorm)

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
        val hit = drumKitManager.synthesizePieceHit(slotId, beaterSide, _rigConfig.value.sampleRate) ?: return
        studioEngine.playOneShot(hit)
    }

    fun hitDrumAddon(instanceId: String) {
        val hit = drumKitManager.synthesizeAddonHit(instanceId, _rigConfig.value.sampleRate) ?: return
        studioEngine.playOneShot(hit)
    }

    fun sendDrumPieceToSeqLane(slotId: String, laneId: Int, beaterSide: BeaterSide = BeaterSide.NONE) {
        val hit = drumKitManager.synthesizePieceHit(slotId, beaterSide, _rigConfig.value.sampleRate) ?: return
        studioEngine.setSequencerSampleForLane(laneId.coerceIn(0, 3), hit)
        _editorState.value = _editorState.value.copy(
            statusBanner = "ROUTED ${hit.name} -> SEQ LANE ${laneId.coerceIn(0, 3) + 1}"
        )
    }

    fun sendDrumKitPieceToDeck(slotId: String, beaterSide: BeaterSide = BeaterSide.NONE) {
        val hit = drumKitManager.synthesizePieceHit(slotId, beaterSide, _rigConfig.value.sampleRate) ?: return
        loadNewRawBuffer(hit, "SENT ${hit.name} TO DECK EDITOR", preserveLoopPlayback = false)
    }

    // --- TRACKS SPOKE: SYNCED PLAYHEAD & TIMELINE ---

    fun toggleSyncedTransport() = studioEngine.toggleSyncedTransport()
    fun seekSyncedPlayhead(sec: Float) = studioEngine.seekSyncedPlayhead(sec)

    fun addEditorClipToTimeline(trackId: Int) {
        val buf = activeOutputBuffer() ?: return
        val playhead = studioEngine.timelineState.value.playheadSec
        studioEngine.addClipToTimeline(trackId, buf, playhead)
        _editorState.value = _editorState.value.copy(statusBanner = "ADDED CLIP TO TRACK ${trackId + 1}")
    }

    fun addLooperMixToTimeline() {
        val mix = studioEngine.bounceLooperMixdown(_rigConfig.value.sampleRate)
        studioEngine.addClipToTimeline(1, mix, 0f)
        _editorState.value = _editorState.value.copy(statusBanner = "ADDED LOOPER MIX TO TRACK 2")
    }

    fun nudgeTimelineClip(trackId: Int, clipId: String, deltaSec: Float) {
        val track = studioEngine.timelineState.value.tracks.firstOrNull { it.id == trackId } ?: return
        val clip = track.clips.firstOrNull { it.id == clipId } ?: return
        studioEngine.moveTimelineClip(trackId, clipId, clip.startTimeSec + deltaSec)
    }

    fun removeTimelineClip(trackId: Int, clipId: String) = studioEngine.removeTimelineClip(trackId, clipId)
    fun toggleTimelineMute(trackId: Int) = studioEngine.toggleTimelineTrackMute(trackId)
    fun toggleTimelineSolo(trackId: Int) = studioEngine.toggleTimelineTrackSolo(trackId)
    fun seekTimeline(sec: Float) = studioEngine.seekSyncedPlayhead(sec)
    fun toggleTimelinePlayback() = studioEngine.toggleTimelinePlayback()

    fun bounceTimelineToEditor() {
        val mix = studioEngine.mixdownTimeline(_rigConfig.value.sampleRate)
        if (mix.frameCount == 0) {
            _editorState.value = _editorState.value.copy(statusBanner = "TIMELINE HAS NO CLIPS")
            return
        }
        loadNewRawBuffer(mix, "TIMELINE MIXDOWN IN DECK")
        _selectedSpoke.value = StudioSpoke.DECK
    }

    // --- MASTER VOLUME, RIG CONFIG, 4-DEVICE COLLABORATION & NETWORK STREAMING ---

    fun setMasterVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        playbackEngine.setMasterVolume(clamped, _rigConfig.value.isMasterMuted)
        studioEngine.setMasterVolume(clamped, _rigConfig.value.isMasterMuted)
        updateRigConfig { copy(masterVolume = clamped) }
    }

    fun toggleMasterMute() {
        val nextMuted = !_rigConfig.value.isMasterMuted
        val vol = _rigConfig.value.masterVolume
        playbackEngine.setMasterVolume(vol, nextMuted)
        studioEngine.setMasterVolume(vol, nextMuted)
        updateRigConfig { copy(isMasterMuted = nextMuted) }
    }

    fun setShowCollabSheet(visible: Boolean) {
        _showCollabSheet.value = visible
        if (visible) {
            refreshNearbyDiscovery()
        }
    }

    fun refreshNearbyDiscovery() {
        syncLocalPresence()
        if (!networkTransmitter.state.value.isListening) {
            val cfg = _rigConfig.value
            runCatching { networkTransmitter.startListening(cfg.protocol, cfg.bindPort) }
        }
        if (!networkTransmitter.state.value.isDiscovering) {
            networkTransmitter.startDiscovery()
        }
        viewModelScope.launch(Dispatchers.IO) {
            networkTransmitter.sendDiscoveryBeaconOnce()
        }
    }

    fun setMyCollabStyle(style: CollabInstrumentStyle, trackSlot: Int = style.defaultTrack) {
        val slot = trackSlot.coerceIn(0, 3)
        _bandRecState.value = _bandRecState.value.copy(myStyle = style, myTrackSlot = slot)
        updateRigConfig { copy(myStyleName = style.name) }
        syncLocalPresence()
        viewModelScope.launch(Dispatchers.IO) {
            networkTransmitter.sendDiscoveryBeaconOnce()
        }
    }

    fun connectCollabPeer(
        host: String,
        port: Int,
        name: String = "Studio Peer",
        platform: String = "PEER",
        style: CollabInstrumentStyle? = null,
        trackSlot: Int? = null
    ): Boolean {
        val cfg = _rigConfig.value
        if (!networkTransmitter.state.value.isListening) {
            runCatching { networkTransmitter.startListening(cfg.protocol, cfg.bindPort) }
        }
        val res = networkTransmitter.connectPeer(host, port, name, platform, style, trackSlot)
        res.onSuccess { peer ->
            val knownSpec = CollabWireCodec.serializeKnownPeers(networkTransmitter.collabPeers.value)
            updateRigConfig {
                copy(
                    ipAddress = peer.host,
                    port = peer.listenPort,
                    knownPeersSpec = knownSpec
                )
            }
            val msg = "CONNECTED ${peer.name} (${peer.style.label}) • TRACK ${peer.trackSlot + 1}"
            _editorState.value = _editorState.value.copy(statusBanner = msg)
            _toastEvents.tryEmit(msg)
        }.onFailure { err ->
            val msg = err.message ?: "Max $MAX_COLLAB_PEERS collaborators linked"
            _editorState.value = _editorState.value.copy(statusBanner = msg)
            _toastEvents.tryEmit(msg)
        }
        return res.isSuccess
    }

    fun disconnectCollabPeer(peerId: String) {
        networkTransmitter.disconnectPeer(peerId)
        _editorState.value = _editorState.value.copy(statusBanner = "DISCONNECTED PEER $peerId")
    }

    fun removeKnownCollabPeer(peerId: String) {
        networkTransmitter.removeKnownPeer(peerId)
        val knownSpec = CollabWireCodec.serializeKnownPeers(networkTransmitter.collabPeers.value)
        updateRigConfig { copy(knownPeersSpec = knownSpec) }
    }

    fun updateCollabPeerStyleAndTrack(
        peerId: String,
        style: CollabInstrumentStyle,
        trackSlot: Int = style.defaultTrack
    ) {
        networkTransmitter.updatePeerStyleAndTrack(peerId, style, trackSlot)
        val knownSpec = CollabWireCodec.serializeKnownPeers(networkTransmitter.collabPeers.value)
        updateRigConfig { copy(knownPeersSpec = knownSpec) }
    }

    fun toggleLiveBandRecording() {
        if (_bandRecState.value.isRecording) {
            stopLiveBandRecording(broadcastStop = true)
        } else {
            startLiveBandRecording(broadcastToPeers = true)
        }
    }

    fun startLiveBandRecording(broadcastToPeers: Boolean = true, syncedTakeName: String = "") {
        if (_bandRecState.value.isRecording) return
        val cfg = _rigConfig.value
        if (!networkTransmitter.state.value.isListening) {
            runCatching { networkTransmitter.startListening(cfg.protocol, cfg.bindPort) }
        }
        networkTransmitter.beginCollabAudioCapture()
        val takeName = syncedTakeName.ifBlank {
            "BAND_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}"
        }
        val cur = _bandRecState.value
        _bandRecState.value = cur.copy(
            isRecording = true,
            startedAtMs = System.currentTimeMillis(),
            activeTakeName = takeName
        )
        syncLocalPresence()

        val micRes = micEngine.startCapture(
            sampleRate = cfg.sampleRate,
            channels = 1,
            bufferSizeFrames = cfg.bufferSize
        )
        if (broadcastToPeers) {
            networkTransmitter.sendCollabRecSync(
                CollabSyncCommand(
                    start = true,
                    bpm = studioEngine.syncedPlayhead.value.bpm,
                    senderPeerId = networkTransmitter.localNodeId,
                    senderName = cfg.nodeName,
                    senderStyle = cur.myStyle,
                    senderTrackSlot = cur.myTrackSlot,
                    takeName = takeName,
                    senderListenPort = cfg.bindPort
                )
            )
        }
        val linked = networkTransmitter.getConnectedPeers().size
        val note = if (micRes.isSuccess) {
            "LIVE BAND REC (${cur.myStyle.shortTag} -> T${cur.myTrackSlot + 1} • $linked PEERS)"
        } else {
            "BAND RX REC ONLY (GRANT MIC FOR LOCAL TRACK)"
        }
        _editorState.value = _editorState.value.copy(statusBanner = note)
    }

    fun stopLiveBandRecording(broadcastStop: Boolean = true) {
        val cur = _bandRecState.value
        if (!cur.isRecording) return
        _bandRecState.value = cur.copy(isRecording = false, elapsedMs = System.currentTimeMillis() - cur.startedAtMs)
        syncLocalPresence()
        val cfg = _rigConfig.value

        if (broadcastStop) {
            networkTransmitter.sendCollabRecSync(
                CollabSyncCommand(
                    start = false,
                    bpm = studioEngine.syncedPlayhead.value.bpm,
                    senderPeerId = networkTransmitter.localNodeId,
                    senderName = cfg.nodeName,
                    senderStyle = cur.myStyle,
                    senderTrackSlot = cur.myTrackSlot,
                    takeName = cur.activeTakeName,
                    senderListenPort = cfg.bindPort
                )
            )
        }

        viewModelScope.launch {
            val stamp = cur.activeTakeName.ifBlank { "BAND_${System.currentTimeMillis()}" }
            val localFile = File(takesDir, "${stamp}_${cur.myStyle.shortTag}.wav")
            val localRes = micEngine.stopCaptureAndSaveWav(localFile, "${stamp}_${cur.myStyle.shortTag}")
            val peerTracks = networkTransmitter.stopAndCollectCollabTracks(stamp)

            var placedCount = 0
            localRes.onSuccess { localBuf ->
                studioEngine.addClipToTimeline(cur.myTrackSlot.coerceIn(0, 3), localBuf, 0f)
                placedCount++
                loadNewRawBuffer(localBuf, "SAVED ${cur.myStyle.label} TAKE -> TRACK ${cur.myTrackSlot + 1}")
                refreshSavedTakes()
            }
            peerTracks.forEach { (peer, buf) ->
                studioEngine.addClipToTimeline(peer.trackSlot.coerceIn(0, 3), buf, 0f)
                placedCount++
            }
            val summary = "BAND REC LANDED $placedCount TRACKS (${1 + peerTracks.size} DEVICES)"
            _bandRecState.value = _bandRecState.value.copy(lastRecordedSummary = summary)
            _editorState.value = _editorState.value.copy(statusBanner = summary)
            _toastEvents.tryEmit(summary)
        }
    }

    private fun syncLocalPresence() {
        val cfg = _rigConfig.value
        val band = _bandRecState.value
        networkTransmitter.updateLocalPresence(
            nodeName = cfg.nodeName,
            listenPort = cfg.bindPort,
            style = band.myStyle,
            trackSlot = band.myTrackSlot,
            isRecording = band.isRecording
        )
    }

    fun updateRigConfig(transform: RigConfig.() -> RigConfig) {
        hasRigLocalEdit = true
        _rigConfig.value = _rigConfig.value.transform()
        val cfg = _rigConfig.value
        playbackEngine.setMasterVolume(cfg.masterVolume, cfg.isMasterMuted)
        studioEngine.setMasterVolume(cfg.masterVolume, cfg.isMasterMuted)
        syncLocalPresence()
        viewModelScope.launch {
            rigSaveMutex.withLock {
                rigRepository.saveConfig(cfg)
            }
        }
    }

    fun streamActiveAudioOverNetwork() {
        val buf = activeOutputBuffer() ?: run {
            _editorState.value = _editorState.value.copy(statusBanner = "CAPTURE OR LOAD AUDIO FIRST TO STREAM")
            return
        }
        networkTransmitter.transmitBuffer(buf, _rigConfig.value)
    }

    fun stopNetworkStream() = networkTransmitter.stopTransmission()

    fun toggleNetworkListener() {
        if (networkTransmitter.state.value.isListening) {
            networkTransmitter.stopListening()
        } else {
            val cfg = _rigConfig.value
            networkTransmitter.startListening(cfg.protocol, cfg.bindPort)
        }
    }

    fun captureReceivedAudioToDeck() {
        val buf = networkTransmitter.captureReceivedBuffer("SMP2_PEER_RX") ?: run {
            _editorState.value = _editorState.value.copy(statusBanner = "NO SMP2 STREAM BUFFERED YET")
            return
        }
        loadNewRawBuffer(buf, "LOADED PEER STREAM (${"%.2f".format(buf.durationSeconds)}s)")
        _selectedSpoke.value = StudioSpoke.DECK
    }

    // --- EQUAL PEER SESSION HANDOFF ---

    fun captureSessionSnapshot(): SessionHandoffSnapshot {
        val ed = _editorState.value
        val play = playbackEngine.state.value
        val cfg = _rigConfig.value
        val seqMasks = studioEngine.sequencerState.value.lanes.map { lane ->
            var mask = 0
            for (i in lane.steps.indices) if (lane.steps[i]) mask = mask or (1 shl i)
            mask
        }
        var loopMask = 0
        val loopBars = studioEngine.looperState.value.slots.mapIndexed { idx, s ->
            if (s.isActive) loopMask = loopMask or (1 shl idx)
            s.bars
        }
        return SessionHandoffSnapshot(
            senderName = cfg.nodeName,
            stationKey = _selectedSpoke.value.name,
            bpm = studioEngine.syncedPlayhead.value.bpm,
            swing = studioEngine.sequencerState.value.swing,
            isPlaying = play.isPlaying,
            isLooping = play.isLooping,
            playheadFraction = play.playheadFraction,
            selectionStart = ed.selectionStart,
            selectionEnd = ed.selectionEnd,
            playSelectionOnly = ed.playSelectionOnly,
            a4ReferenceHz = cfg.a4ReferenceHz,
            fxRack = _fxRack.value,
            stompbox = _stompbox.value,
            seqLaneMasks = seqMasks,
            looperActiveMask = loopMask,
            looperBars = loopBars,
            vmcMappings = vmcEngine.state.value.mappings,
            masterVolume = cfg.masterVolume,
            isMasterMuted = cfg.isMasterMuted,
            buffer = ed.dryBuffer
        )
    }

    fun pushSessionToPeer() {
        val cfg = _rigConfig.value
        if (!cfg.isValidIpOrHost() || !cfg.isValidPort()) {
            _editorState.value = _editorState.value.copy(statusBanner = "SET VALID PEER IP & PORT FOR HANDOFF")
            return
        }
        val snap = captureSessionSnapshot()
        networkTransmitter.sendSessionHandoff(snap, cfg.protocol, cfg.ipAddress, cfg.port)
        _editorState.value = _editorState.value.copy(statusBanner = "PUSHED SESSION -> ${cfg.ipAddress}:${cfg.port}")
    }

    fun grabSessionFromPeer() {
        val cfg = _rigConfig.value
        if (!networkTransmitter.state.value.isListening) {
            networkTransmitter.startListening(cfg.protocol, cfg.bindPort)
        }
        networkTransmitter.requestSessionFromPeer(cfg)
        _editorState.value = _editorState.value.copy(statusBanner = "REQUESTED SESSION FROM ${cfg.ipAddress}:${cfg.port}")
    }

    fun applyPendingHandoff() {
        val snap = _editorState.value.pendingHandoff ?: return
        applySessionHandoff(snap)
    }

    fun applySessionHandoff(snap: SessionHandoffSnapshot) {
        playbackEngine.stop()
        studioEngine.setBpm(snap.bpm)
        studioEngine.setSequencerSwing(snap.swing)
        snap.seqLaneMasks.forEachIndexed { idx, mask -> studioEngine.applySequencerPatternMask(idx, mask) }
        snap.looperBars.forEachIndexed { idx, bars ->
            studioEngine.setLoopSlotBars(idx, bars)
            studioEngine.setLoopSlotActive(idx, ((snap.looperActiveMask shr idx) and 1) == 1)
        }
        if (snap.vmcMappings.isNotEmpty()) {
            vmcEngine.replaceMappings(snap.vmcMappings)
        }

        val spoke = SessionHandoffSnapshot.mapStationKeyToAndroid(snap.stationKey)
        val incomingDry = snap.buffer ?: _editorState.value.dryBuffer
        _fxRack.value = snap.fxRack
        _stompbox.value = snap.stompbox
        updateRigConfig {
            copy(
                a4ReferenceHz = snap.a4ReferenceHz.coerceIn(430.0, 450.0),
                masterVolume = snap.masterVolume.coerceIn(0f, 1f),
                isMasterMuted = snap.isMasterMuted
            )
        }

        playbackEngine.setLooping(snap.isLooping)
        playbackEngine.setSelectionBounds(snap.selectionStart, snap.selectionEnd, snap.playSelectionOnly)

        _editorState.value = _editorState.value.copy(
            selectionStart = snap.selectionStart,
            selectionEnd = snap.selectionEnd,
            playSelectionOnly = snap.playSelectionOnly,
            pendingHandoff = null
        )

        if (incomingDry != null && incomingDry.frameCount > 0) {
            undoStack.clear()
            _editorState.value = _editorState.value.copy(rawBuffer = incomingDry, dryBuffer = incomingDry, canUndo = false)
            reprocessFxChain(incomingDry, preserveLoopPlayback = false)
            playbackEngine.seekToFraction(snap.playheadFraction)
            if (snap.isPlaying && _rigConfig.value.autoResumeOnHandoff) {
                playbackEngine.play(onlySelection = snap.playSelectionOnly)
            }
        }
        _editorState.value = _editorState.value.copy(
            statusBanner = "PICKED UP SESSION FROM ${snap.senderName} -> ${spoke.shortLabel}"
        )
        _selectedSpoke.value = spoke
    }

    // --- 64-CHANNEL VMC MIDI CONTROLLER ---

    fun vmcSelectBank(bankIndex: Int) = vmcEngine.selectBank(bankIndex)
    fun vmcSelectChannel64(ch64: Int) = vmcEngine.selectChannel64(ch64)
    fun vmcSetRouteMode(mode: VmcRouteMode) = vmcEngine.setRouteMode(mode)
    fun vmcEmitControl(msgType: VmcMsgType, number: Int, normalized: Float, ch64: Int = vmcEngine.state.value.activeChannel64) =
        vmcEngine.emitVirtualControl(msgType, number, normalized, ch64)
    fun vmcArmLearnRule(ruleId: String?) = vmcEngine.armLearnForRule(ruleId)
    fun vmcArmLearnNew(target: VmcTarget) = vmcEngine.armLearnNewMapping(target)
    fun vmcUpsertRule(rule: VmcMappingRule) = vmcEngine.upsertMapping(rule)
    fun vmcRemoveRule(ruleId: String) = vmcEngine.removeMapping(ruleId)

    private fun executeVmcTarget(target: VmcTarget, norm: Float) {
        when (target) {
            VmcTarget.DECK_PLAY_PAUSE -> togglePlay()
            VmcTarget.DECK_STOP -> stopPlayback()
            VmcTarget.DECK_LOOP_TOGGLE -> toggleLoop()
            VmcTarget.SYNC_ALL_PLAY -> toggleSyncedTransport()
            VmcTarget.BPM_TEMPO -> setStudioBpm(60f + norm * 120f)
            VmcTarget.STOMP_BYPASS -> toggleStompboxBypass()
            VmcTarget.STOMP_WET_MIX -> updateStompbox { copy(engaged = true, masterWetMix = norm) }
            VmcTarget.STOMP_TONE_TILT -> updateStompbox { copy(engaged = true, toneTilt = norm * 2f - 1f) }
            VmcTarget.STOMP_DYNAMICS -> updateStompbox { copy(engaged = true, dynamicsTrimDb = -6f + norm * 12f) }
            VmcTarget.STOMP_MOD_SCALE -> updateStompbox { copy(engaged = true, modScale = 0.2f + norm * 1.6f) }
            VmcTarget.STOMP_SPACE_SCALE -> updateStompbox { copy(engaged = true, spaceScale = 0.2f + norm * 1.6f) }
            VmcTarget.FX_DRIVE -> updateFxRack { copy(overdrive = overdrive.copy(enabled = true, drive = 1f + norm * 15f)) }
            VmcTarget.FX_WAH_FREQ -> updateFxRack { copy(wah = wah.copy(enabled = true, centerFreqHz = 280f + norm * 2200f)) }
            VmcTarget.FX_PHASER_MIX -> updateFxRack { copy(phaser = phaser.copy(enabled = true, mix = norm)) }
            VmcTarget.FX_FLANGER_MIX -> updateFxRack { copy(flanger = flanger.copy(enabled = true, mix = norm)) }
            VmcTarget.FX_CHORUS_MIX -> updateFxRack { copy(chorus = chorus.copy(enabled = true, mix = norm)) }
            VmcTarget.FX_ROTARY_RATE -> updateFxRack { copy(rotarySpeaker = rotarySpeaker.copy(enabled = true, rateHz = 0.5f + norm * 9.5f)) }
            VmcTarget.FX_ECHO_MIX -> updateFxRack { copy(echo = echo.copy(enabled = true, mix = norm)) }
            VmcTarget.FX_DELAY_MIX -> updateFxRack { copy(delay = delay.copy(enabled = true, mix = norm)) }
            VmcTarget.FX_REVERB_MIX -> updateFxRack { copy(reverb = reverb.copy(enabled = true, mix = norm)) }
            VmcTarget.FX_PITCH_SEMI -> updateFxRack { copy(pitchSpeed = pitchSpeed.copy(enabled = true, pitchSemitones = -12f + norm * 24f)) }
            VmcTarget.LOOPER_SLOT_A -> studioEngine.toggleLoopSlotActive(0)
            VmcTarget.LOOPER_SLOT_B -> studioEngine.toggleLoopSlotActive(1)
            VmcTarget.LOOPER_SLOT_C -> studioEngine.toggleLoopSlotActive(2)
            VmcTarget.LOOPER_SLOT_D -> studioEngine.toggleLoopSlotActive(3)
            VmcTarget.SEQ_PAD_1 -> triggerSequencerLaneOneShot(0)
            VmcTarget.SEQ_PAD_2 -> triggerSequencerLaneOneShot(1)
            VmcTarget.SEQ_PAD_3 -> triggerSequencerLaneOneShot(2)
            VmcTarget.SEQ_PAD_4 -> triggerSequencerLaneOneShot(3)
            VmcTarget.SEQ_PLAY_TOGGLE -> studioEngine.toggleSequencerPlayback()
            VmcTarget.TIMELINE_PLAY_TOGGLE -> studioEngine.toggleTimelinePlayback()
            VmcTarget.SESSION_HANDOFF_PUSH -> pushSessionToPeer()
            VmcTarget.LIVE_OVERDUB_TOGGLE -> _editorState.value = _editorState.value.copy(statusBanner = "VMC OVERDUB PULSE")
            VmcTarget.SEQ_CLEAR_PATTERN -> {
                for (lane in 0..3) studioEngine.applySequencerPatternMask(lane, 0)
            }
            VmcTarget.DRUM_PUNCH_LANE_1 -> triggerSequencerLaneOneShot(0)
            VmcTarget.DRUM_PUNCH_LANE_2 -> triggerSequencerLaneOneShot(1)
            VmcTarget.DRUM_PUNCH_LANE_3 -> triggerSequencerLaneOneShot(2)
            VmcTarget.DRUM_PUNCH_LANE_4 -> triggerSequencerLaneOneShot(3)
        }
    }

    // --- CHROMATIC & STROBE YIN TUNER ---

    fun setTunerSource(source: TunerInputSource) {
        _tunerState.value = _tunerState.value.copy(source = source)
        if (source == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
    }

    fun setTuningPreset(preset: TuningPreset) {
        _tunerState.value = _tunerState.value.copy(preset = preset)
    }

    fun setA4Reference(hz: Double) {
        val clamped = hz.coerceIn(430.0, 450.0)
        updateRigConfig { copy(a4ReferenceHz = clamped) }
        if (_tunerState.value.source == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
    }

    fun analyzeActiveBufferWindowForTuner() {
        val buf = activeOutputBuffer() ?: return
        if (buf.frameCount < 512) return
        val ed = _editorState.value
        val slice = buf.extractSelection(ed.selectionStart, ed.selectionEnd)
        val mono = slice.toMonoSamples()
        val windowSize = minOf(4096, mono.size)
        val start = maxOf(0, (mono.size - windowSize) / 2)
        val window = mono.copyOfRange(start, start + windowSize)
        analyzeSamplesForTuner(window, buf.sampleRate)
    }

    private fun analyzeSamplesForTuner(monoSamples: FloatArray, sampleRate: Int) {
        val reading = PitchDetector.detectPitch(
            monoSamples = monoSamples,
            sampleRate = sampleRate,
            a4ReferenceHz = _rigConfig.value.a4ReferenceHz
        )
        _tunerState.value = _tunerState.value.copy(reading = reading)
    }

    fun playTunerReferencePitch(freqHz: Double) {
        val sr = _rigConfig.value.sampleRate
        val frames = (sr * 0.7f).toInt()
        val samples = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toFloat() / sr
            val env = (1f - exp(-t * 30f)) * exp(-t * 3.0f)
            samples[i] = (sin(2.0 * PI * freqHz * t) * 0.48 * env).toFloat()
        }
        playbackEngine.setBuffer(AudioBuffer(samples, sr, 1, "REF_${freqHz.toInt()}Hz"), _rigConfig.value.bufferSize)
        playbackEngine.setLooping(false)
        playbackEngine.play(onlySelection = false)
    }

    fun exportAsMp3(fileNameBase: String) {
        val buf = activeOutputBuffer() ?: run {
            _editorState.value = _editorState.value.copy(statusBanner = "NO AUDIO TO EXPORT AS MP3")
            return
        }
        viewModelScope.launch {
            _editorState.value = _editorState.value.copy(
                isExporting = true,
                statusBanner = "ENCODING MP3 (${_rigConfig.value.mp3BitrateKbps} KBPS)…"
            )
            val res = Mp3Exporter.saveMp3ToDevice(
                context = appContext,
                buffer = buf,
                fileNameBase = fileNameBase,
                bitrateKbps = _rigConfig.value.mp3BitrateKbps
            )
            res.onSuccess { exported ->
                _editorState.value = _editorState.value.copy(
                    isExporting = false,
                    lastExport = exported,
                    lastLocalExport = ExportedAudioResult(
                        formatLabel = "MP3",
                        fileName = exported.fileName,
                        displayLocation = exported.displayLocation,
                        byteSize = exported.byteSize,
                        uri = exported.uri
                    ),
                    statusBanner = "SAVED MP3: ${exported.displayLocation}"
                )
            }.onFailure { err ->
                _editorState.value = _editorState.value.copy(
                    isExporting = false,
                    statusBanner = "MP3 EXPORT FAILED: ${err.message}"
                )
            }
        }
    }

    fun exportAsWav(fileNameBase: String) {
        val buf = activeOutputBuffer() ?: run {
            _editorState.value = _editorState.value.copy(statusBanner = "NO AUDIO TO EXPORT AS WAV")
            return
        }
        val targetFmt = _toolsState.value.targetSampleFormat
        val dither = _toolsState.value.applyDither
        viewModelScope.launch {
            _editorState.value = _editorState.value.copy(
                isExporting = true,
                statusBanner = "EXPORTING ${targetFmt.shortLabel} .WAV TO MUSIC/SAMPLFIE2…"
            )
            val res = WavExporter.saveWavToDevice(appContext, buf, fileNameBase, targetFmt, dither)
            res.onSuccess { exported ->
                _editorState.value = _editorState.value.copy(
                    isExporting = false,
                    lastLocalExport = exported,
                    statusBanner = "SAVED ${targetFmt.shortLabel} WAV: ${exported.displayLocation}"
                )
            }.onFailure { err ->
                _editorState.value = _editorState.value.copy(
                    isExporting = false,
                    statusBanner = "WAV EXPORT FAILED: ${err.message}"
                )
            }
        }
    }

    fun refreshMidiDevices() = midiController.refreshDevices()

    // --- SECRETS & KEYS STORE ---

    fun setShowSecretsVault(visible: Boolean) {
        _showSecretsVault.value = visible
    }

    fun saveSecretKey(keyName: String, secretValue: String, providerHint: String = "") {
        viewModelScope.launch {
            runCatching {
                secretsVault.putSecret(keyName, secretValue, providerHint)
            }.onSuccess {
                val cleanKey = SecretsVaultRepository.sanitizeKeyName(keyName)
                val msg = if (secretValue.trim().isEmpty()) {
                    "Removed $cleanKey from Keys & Secrets Vault"
                } else {
                    "Saved $cleanKey securely in Keys & Secrets Vault"
                }
                _editorState.value = _editorState.value.copy(statusBanner = msg)
                _toastEvents.tryEmit(msg)
            }.onFailure { err ->
                val msg = "Key store error: ${err.message}"
                _toastEvents.tryEmit(msg)
            }
        }
    }

    fun deleteSecretKey(keyName: String) {
        viewModelScope.launch {
            secretsVault.removeSecret(keyName)
            val msg = "Removed $keyName from Vault"
            _editorState.value = _editorState.value.copy(statusBanner = msg)
            _toastEvents.tryEmit(msg)
        }
    }

    // --- DISBAND STEM SEPARATION ---

    internal fun overrideDisbandTransportForTest(transport: DisbandHttpTransport) {
        disbandClient = DisbandApiClient(transport)
    }

    fun toggleDisbandStem(stem: DisbandStem) {
        val next = _stemState.value.selectedStems.toMutableSet()
        if (!next.add(stem)) next.remove(stem)
        _stemState.value = _stemState.value.copy(
            selectedStems = next,
            errorMessage = null,
            statusMessage = if (next.isEmpty()) {
                "NO SPECIFIC STEMS SELECTED — SEPARATING WILL PROMPT FOR ALL-STEMS CONFIRMATION"
            } else {
                "TARGET STEMS: ${next.joinToString(", ") { it.label }}"
            }
        )
    }

    fun applyStemPreset(preset: StemPreset) {
        val next = preset.stems
        _stemState.value = _stemState.value.copy(
            selectedStems = next,
            errorMessage = null,
            statusMessage = if (next.isEmpty()) {
                "UNSPECIFIED STEM SET — SEPARATING WILL ASK CONFIRMATION FOR ALL STEMS"
            } else {
                "PRESET ${preset.label.uppercase()}: ${next.joinToString(", ") { it.label }}"
            }
        )
    }

    fun setStemUseSelectionOnly(onlySelection: Boolean) {
        _stemState.value = _stemState.value.copy(useSelectionOnly = onlySelection)
    }

    fun requestStemSeparation() {
        if (_stemState.value.isRunning) return
        val source = resolveSourceBufferForStems() ?: run {
            val msg = "LOAD OR RECORD AUDIO BEFORE SEPARATING STEMS"
            _stemState.value = _stemState.value.copy(errorMessage = msg, statusMessage = msg, lastToastMessage = msg)
            _toastEvents.tryEmit(msg)
            return
        }
        val apiKey = secretsVault.resolveDisbandApiKey()
        if (apiKey.isNullOrBlank()) {
            val msg = "DISBAND_API_KEY MISSING — STORE KEY IN TOP TOOLBAR OR HUB VAULT"
            _stemState.value = _stemState.value.copy(errorMessage = msg, statusMessage = msg, lastToastMessage = msg)
            _toastEvents.tryEmit(msg)
            _showSecretsVault.value = true
            return
        }

        val chosen = _stemState.value.selectedStems
        if (chosen.isEmpty()) {
            val estCredits = StemSeparationUiState.estimateAllStemsCredits(source.durationSeconds)
            val toast = "No specific stem set selected — All Stems separation can use ~$estCredits credits. Confirm?"
            _stemState.value = _stemState.value.copy(
                showAllStemsConfirmation = true,
                pendingAllStemsEstimatedCredits = estCredits,
                pendingAudioDurationSec = source.durationSeconds,
                errorMessage = null,
                lastToastMessage = toast,
                statusMessage = "CONFIRMATION REQUIRED: SEPARATE ALL STEMS (~$estCredits CREDITS)"
            )
            _toastEvents.tryEmit(toast)
            return
        }

        launchDisbandSeparation(chosen.toList(), source, apiKey)
    }

    fun confirmSeparateAllStems() {
        val source = resolveSourceBufferForStems() ?: run {
            _stemState.value = _stemState.value.copy(showAllStemsConfirmation = false)
            return
        }
        val apiKey = secretsVault.resolveDisbandApiKey() ?: run {
            _stemState.value = _stemState.value.copy(showAllStemsConfirmation = false)
            _showSecretsVault.value = true
            return
        }
        _stemState.value = _stemState.value.copy(showAllStemsConfirmation = false)
        launchDisbandSeparation(DisbandStem.ALL_STEMS_DEFAULT, source, apiKey)
    }

    fun dismissAllStemsConfirmation() {
        val msg = "Cancelled All-Stems split — select specific stems (e.g. Vocals, Drums) to save credits."
        _stemState.value = _stemState.value.copy(
            showAllStemsConfirmation = false,
            statusMessage = msg
        )
    }

    fun cancelStemSeparation() {
        stemJob?.cancel()
        _stemState.value = _stemState.value.copy(
            isRunning = false,
            jobStatusLabel = "CANCELLED",
            statusMessage = "DISBAND STEM SEPARATION CANCELLED"
        )
    }

    private fun resolveSourceBufferForStems(): AudioBuffer? {
        val base = activeOutputBuffer() ?: return null
        if (!_stemState.value.useSelectionOnly) return base
        val sel = base.extractSelection(_editorState.value.selectionStart, _editorState.value.selectionEnd)
        return if (sel.frameCount > 0) sel else base
    }

    private fun launchDisbandSeparation(
        targetStems: List<DisbandStem>,
        source: AudioBuffer,
        apiKey: String
    ) {
        val baseUrl = secretsVault.resolveDisbandBaseUrl()
        stemJob?.cancel()
        stemJob = viewModelScope.launch(Dispatchers.IO) {
            _stemState.value = _stemState.value.copy(
                isRunning = true,
                showAllStemsConfirmation = false,
                jobStatusLabel = "SUBMITTING",
                errorMessage = null,
                statusMessage = "DISBAND: REQUESTING ${targetStems.joinToString("+") { it.shortTag }}…"
            )
            val result = runCatching {
                disbandClient.separateAndDownloadStems(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    stems = targetStems,
                    sourceBuffer = source,
                    isCancelled = { !coroutineContext.isActive }
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
            result.onSuccess { (job, tracks) ->
                val doneMsg = "DISBAND READY (${tracks.size} stems • ${job.charge ?: 0} credits)"
                _stemState.value = _stemState.value.copy(
                    isRunning = false,
                    activeJobId = job.id,
                    jobStatusLabel = "READY",
                    lastCreditCharge = job.charge,
                    separatedStems = tracks,
                    errorMessage = null,
                    lastToastMessage = doneMsg,
                    statusMessage = doneMsg
                )
                _editorState.value = _editorState.value.copy(statusBanner = doneMsg)
                _toastEvents.tryEmit(doneMsg)
            }.onFailure { err ->
                if (err is InterruptedException) return@onFailure
                val errMsg = err.message ?: "Disband separation failed"
                _stemState.value = _stemState.value.copy(
                    isRunning = false,
                    jobStatusLabel = "ERROR",
                    errorMessage = errMsg,
                    lastToastMessage = errMsg,
                    statusMessage = "DISBAND ERROR: $errMsg"
                )
                _toastEvents.tryEmit(errMsg)
            }
        }
    }

    fun loadSeparatedStemToDeck(stemId: String) {
        val stem = _stemState.value.separatedStems.firstOrNull { it.stemId == stemId } ?: return
        loadNewRawBuffer(
            buffer = stem.buffer,
            banner = "LOADED STEM TO DECK: ${stem.label.uppercase()}",
            preserveLoopPlayback = true
        )
    }

    fun sendSeparatedStemToLooper(stemId: String, slotId: Int = 0) {
        val stem = _stemState.value.separatedStems.firstOrNull { it.stemId == stemId } ?: return
        studioEngine.assignBufferToLoopSlot(slotId.coerceIn(0, 3), stem.buffer, stem.label)
        _editorState.value = _editorState.value.copy(
            statusBanner = "STEM ${stem.label.uppercase()} -> LOOP ${'A' + slotId.coerceIn(0, 3)}"
        )
    }

    fun addSeparatedStemToTimeline(stemId: String, trackId: Int) {
        val stem = _stemState.value.separatedStems.firstOrNull { it.stemId == stemId } ?: return
        studioEngine.addClipToTimeline(trackId.coerceIn(0, 3), stem.buffer, startTimeSec = 0f)
        _editorState.value = _editorState.value.copy(
            statusBanner = "STEM ${stem.label.uppercase()} -> TRACK ${trackId.coerceIn(0, 3) + 1}"
        )
    }

    fun sendAllSeparatedStemsToTimeline() {
        val stems = _stemState.value.separatedStems
        if (stems.isEmpty()) return
        stems.forEachIndexed { idx, stem ->
            val trackIdx = idx % 4
            val offsetSec = if (idx >= 4) 0.0f else 0f
            studioEngine.addClipToTimeline(trackIdx, stem.buffer, startTimeSec = offsetSec)
        }
        val msg = "PLACED ALL ${stems.size} SEPARATED STEMS ONTO TRACKS 1–4"
        _editorState.value = _editorState.value.copy(statusBanner = msg)
        _toastEvents.tryEmit(msg)
    }

    fun sendAllSeparatedStemsToLooper() {
        val stems = _stemState.value.separatedStems
        if (stems.isEmpty()) return
        stems.take(4).forEachIndexed { idx, stem ->
            studioEngine.assignBufferToLoopSlot(idx, stem.buffer, stem.label)
        }
        val msg = "LOADED ${stems.take(4).size} STEMS INTO LOOP SLOTS A–${'A' + (stems.take(4).size - 1)}"
        _editorState.value = _editorState.value.copy(statusBanner = msg)
        _toastEvents.tryEmit(msg)
    }

    fun exportSeparatedStemWav(stemId: String) {
        val stem = _stemState.value.separatedStems.firstOrNull { it.stemId == stemId } ?: return
        viewModelScope.launch {
            val res = WavExporter.saveWavToDevice(appContext, stem.buffer, "${stem.buffer.name}_stem")
            res.onSuccess { exported ->
                val msg = "SAVED STEM WAV: ${exported.displayLocation}"
                _editorState.value = _editorState.value.copy(lastLocalExport = exported, statusBanner = msg)
                _toastEvents.tryEmit(msg)
            }.onFailure { err ->
                val msg = "STEM WAV EXPORT FAILED: ${err.message}"
                _editorState.value = _editorState.value.copy(statusBanner = msg)
                _toastEvents.tryEmit(msg)
            }
        }
    }

    // --- AUDIO TOOLS SUITE & SPECTROGRAM ---

    fun setToolsTab(tab: ToolsSectionTab) {
        _toolsState.value = _toolsState.value.copy(activeTab = tab)
    }

    fun setSpectrogramPalette(palette: SpectrogramPalette) {
        _toolsState.value = _toolsState.value.copy(
            specPalette = palette,
            lastToolReport = "SPECTROGRAM GRADIENT: ${palette.name} (10 STOPS)"
        )
    }

    fun applyCustomTenStopGradient(rawHexStops: String) {
        SpectrogramPalette.parseTenHexStops(rawHexStops).onSuccess { custom ->
            _toolsState.value = _toolsState.value.copy(
                specPalette = custom,
                lastToolReport = "APPLIED CUSTOM 10-STOP SPECTROGRAM GRADIENT"
            )
            _toastEvents.tryEmit("Applied custom 10-stop spectrogram gradient")
        }.onFailure { err ->
            val msg = err.message ?: "Invalid 10-stop hex list"
            _toolsState.value = _toolsState.value.copy(lastToolReport = "GRADIENT ERROR: $msg")
            _toastEvents.tryEmit(msg)
        }
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
        val dry = _editorState.value.dryBuffer ?: return
        val ed = _editorState.value
        val ts = _toolsState.value
        val updated = AudioToolsEngine.spectralDelete(
            buffer = dry,
            startFrac = ed.selectionStart,
            endFrac = ed.selectionEnd,
            lowFreqHz = ts.specFreqLowHz,
            highFreqHz = ts.specFreqHighHz,
            attenuationDb = ts.specDeleteAttenDb
        )
        val loSec = ed.selectionStart * dry.durationSeconds
        val hiSec = ed.selectionEnd * dry.durationSeconds
        val report = "SPECTRAL DELETE: ${"%.2f".format(loSec)}s–${"%.2f".format(hiSec)}s × ${ts.specFreqLowHz.toInt()}–${ts.specFreqHighHz.toInt()} Hz (-${ts.specDeleteAttenDb.toInt()} dB)"
        mutateDryBufferWithUndo(updated, report, report)
    }

    fun applyInvertPolarity() {
        val dry = _editorState.value.dryBuffer ?: return
        val ed = _editorState.value
        val ts = _toolsState.value
        val sFrac = if (ts.invertSelectionOnly) ed.selectionStart else 0f
        val eFrac = if (ts.invertSelectionOnly) ed.selectionEnd else 1f
        val updated = AudioToolsEngine.invertPolarity(dry, sFrac, eFrac, ts.invertScope)
        val rangeLabel = if (ts.invertSelectionOnly) "SELECTION" else "FULL"
        val report = "PHASE INVERTED (${ts.invertScope.label} • $rangeLabel)"
        mutateDryBufferWithUndo(updated, report, report)
    }

    fun loadReferenceBufferFromUri(uri: Uri) {
        viewModelScope.launch {
            val cacheDir = File(appContext.cacheDir, "tool_refs").apply { mkdirs() }
            val result = AudioImporter.importUriAsWav(
                context = appContext,
                uri = uri,
                takesDir = cacheDir,
                targetSampleRate = null
            )
            result.onSuccess { (buf, wavFile) ->
                val label = "${wavFile.nameWithoutExtension} (${"%.2f".format(buf.durationSeconds)}s @ ${buf.sampleRate}Hz)"
                _toolsState.value = _toolsState.value.copy(
                    referenceBuffer = buf,
                    referenceLabel = label,
                    lastToolReport = "LOADED REF B: $label"
                )
            }.onFailure { err ->
                val msg = "Ref audio load failed: ${err.message}"
                _toolsState.value = _toolsState.value.copy(lastToolReport = msg)
                _toastEvents.tryEmit(msg)
            }
        }
    }

    fun selectReferenceFromSessionSource(sourceTag: String) {
        val candidate: AudioBuffer? = when (sourceTag) {
            "RAW" -> _editorState.value.rawBuffer
            "DRY" -> _editorState.value.dryBuffer
            "LOOP_A" -> studioEngine.looperState.value.slots.getOrNull(0)?.buffer
            "LOOP_B" -> studioEngine.looperState.value.slots.getOrNull(1)?.buffer
            "LOOP_C" -> studioEngine.looperState.value.slots.getOrNull(2)?.buffer
            "LOOP_D" -> studioEngine.looperState.value.slots.getOrNull(3)?.buffer
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
        val primary = activeOutputBuffer() ?: return
        val ref = _toolsState.value.referenceBuffer ?: _editorState.value.rawBuffer ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val diff = AudioToolsEngine.computeAudioDifference(primary, ref)
            val summary = "DIFF (A-B): Peak=${"%.4f".format(diff.peakDiff)} • RMS=${"%.4f".format(diff.rmsDiff)} • Null=${"%.1f".format(diff.nullDepthDb)} dB • Corr=${"%.3f".format(diff.correlation)}"
            withContext(Dispatchers.Main) {
                _toolsState.value = _toolsState.value.copy(
                    lastDiffResult = diff,
                    lastToolReport = summary
                )
            }
        }
    }

    fun loadDiffResultToDeck() {
        val diff = _toolsState.value.lastDiffResult ?: return
        mutateDryBufferWithUndo(
            nextDry = diff.diffBuffer,
            banner = "LOADED DIFF (A-B) TO DECK (NULL ${"%.1f".format(diff.nullDepthDb)} dB)",
            report = "LOADED DIFF BUFFER TO DECK"
        )
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
        val dry = _editorState.value.dryBuffer ?: return
        val ref = _toolsState.value.referenceBuffer ?: run {
            _toolsState.value = _toolsState.value.copy(lastToolReport = "LOAD OR PICK A REFERENCE B AUDIO FOR MIX")
            return
        }
        val ts = _toolsState.value
        val mixed = AudioToolsEngine.mixBuffers(
            primary = dry,
            secondary = ref,
            primaryGain = ts.mixPrimaryGain,
            secondaryGain = ts.mixSecondaryGain,
            offsetSec = ts.mixOffsetSec,
            softClipLimiter = ts.mixSoftLimit
        )
        val report = "MIXED DECK (${"%.2f".format(ts.mixPrimaryGain)}x) + REF (${"%.2f".format(ts.mixSecondaryGain)}x @ +${"%.2f".format(ts.mixOffsetSec)}s)"
        mutateDryBufferWithUndo(mixed, report, report)
    }

    fun setMonoFoldMode(mode: MonoFoldMode) {
        _toolsState.value = _toolsState.value.copy(monoFoldMode = mode)
    }

    fun applyMixToMono() {
        val dry = _editorState.value.dryBuffer ?: return
        val mode = _toolsState.value.monoFoldMode
        val monoBuf = AudioToolsEngine.mixToMono(dry, mode)
        val report = "CONVERTED TO MONO (${mode.label})"
        mutateDryBufferWithUndo(monoBuf, report, report)
    }

    fun setStereoSplitParams(mode: StereoSplitMode, param: Float) {
        _toolsState.value = _toolsState.value.copy(
            stereoSplitMode = mode,
            stereoSplitParam = param
        )
    }

    fun applySplitToStereo() {
        val dry = _editorState.value.dryBuffer ?: return
        val ts = _toolsState.value
        val stereoBuf = AudioToolsEngine.splitToStereo(dry, ts.stereoSplitMode, ts.stereoSplitParam)
        val report = "SPLIT TO STEREO (${ts.stereoSplitMode.label})"
        mutateDryBufferWithUndo(stereoBuf, report, report)
    }

    fun setRateConversionParams(sampleRate: Int, reinterpretOnly: Boolean) {
        _toolsState.value = _toolsState.value.copy(
            targetSampleRate = sampleRate.coerceIn(8000, 192000),
            reinterpretRateOnly = reinterpretOnly
        )
    }

    fun applyConvertSampleRate() {
        val dry = _editorState.value.dryBuffer ?: return
        val ts = _toolsState.value
        val converted = AudioToolsEngine.convertSampleRate(
            buffer = dry,
            targetSampleRate = ts.targetSampleRate,
            reinterpretHeaderOnly = ts.reinterpretRateOnly
        )
        val modeStr = if (ts.reinterpretRateOnly) "REINTERPRET VARISPEED" else "PITCH-LOCK RESAMPLE"
        val report = "SAMPLE RATE -> ${converted.sampleRate} Hz ($modeStr)"
        mutateDryBufferWithUndo(converted, report, report)
    }

    fun setFormatConversionParams(format: AudioSampleFormat, dither: Boolean) {
        _toolsState.value = _toolsState.value.copy(
            targetSampleFormat = format,
            applyDither = dither
        )
    }

    fun applyConvertSampleFormat() {
        val dry = _editorState.value.dryBuffer ?: return
        val ts = _toolsState.value
        val converted = AudioToolsEngine.convertSampleFormat(dry, ts.targetSampleFormat, ts.applyDither)
        val report = "FORMAT -> ${ts.targetSampleFormat.label}${if (ts.applyDither) " + TPDF DITHER" else ""}"
        mutateDryBufferWithUndo(converted, report, report)
    }

    // --- DSP PLUGIN MANAGER & PLUGIN INSTALLER ---

    fun addPluginToChain(pluginId: String) {
        val def = _toolsState.value.installedPlugins.firstOrNull { it.id == pluginId } ?: return
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        val nextChain = _toolsState.value.pluginChain + def.createSlotInstance()
        _toolsState.value = _toolsState.value.copy(
            pluginChain = nextChain,
            lastToolReport = "ADDED PLUGIN: ${def.name} (${nextChain.size} in chain)"
        )
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
    }

    fun removePluginFromChain(instanceId: String) {
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        val nextChain = _toolsState.value.pluginChain.filterNot { it.instanceId == instanceId }
        _toolsState.value = _toolsState.value.copy(
            pluginChain = nextChain,
            lastToolReport = "REMOVED PLUGIN SLOT (${nextChain.size} remaining)"
        )
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
    }

    fun movePluginInChain(instanceId: String, delta: Int) {
        val cur = _toolsState.value.pluginChain.toMutableList()
        val idx = cur.indexOfFirst { it.instanceId == instanceId }
        val target = (idx + delta).coerceIn(0, (cur.size - 1).coerceAtLeast(0))
        if (idx < 0 || idx == target) return
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        val item = cur.removeAt(idx)
        cur.add(target, item)
        _toolsState.value = _toolsState.value.copy(
            pluginChain = cur,
            lastToolReport = "REORDERED ${item.pluginName} -> SLOT ${target + 1}"
        )
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
    }

    fun togglePluginBypass(instanceId: String) {
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        val nextChain = _toolsState.value.pluginChain.map { slot ->
            if (slot.instanceId == instanceId) slot.copy(bypassed = !slot.bypassed) else slot
        }
        _toolsState.value = _toolsState.value.copy(pluginChain = nextChain)
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
    }

    fun updatePluginWetMix(instanceId: String, wetMix: Float) {
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        val nextChain = _toolsState.value.pluginChain.map { slot ->
            if (slot.instanceId == instanceId) slot.copy(wetMix = wetMix.coerceIn(0f, 1f)) else slot
        }
        _toolsState.value = _toolsState.value.copy(pluginChain = nextChain)
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
    }

    fun updatePluginParam(instanceId: String, paramKey: String, value: Float) {
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        val nextChain = _toolsState.value.pluginChain.map { slot ->
            if (slot.instanceId == instanceId) {
                val updated = slot.paramValues.toMutableMap().apply { put(paramKey, value) }
                slot.copy(paramValues = updated)
            } else {
                slot
            }
        }
        _toolsState.value = _toolsState.value.copy(pluginChain = nextChain)
        _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
    }

    fun bouncePluginChainToDryBuffer() {
        val dry = _editorState.value.dryBuffer ?: return
        val ts = _toolsState.value
        if (ts.pluginChain.none { it.enabled && !it.bypassed }) {
            _toolsState.value = ts.copy(lastToolReport = "NO ACTIVE PLUGINS TO BOUNCE")
            return
        }
        val defs = ts.installedPlugins.associateBy { it.id }
        val rendered = com.example.plugins.AudioPluginHostEngine.processChain(dry, ts.pluginChain, defs)
        val bypassedChain = ts.pluginChain.map { it.copy(bypassed = true) }
        _toolsState.value = ts.copy(
            pluginChain = bypassedChain,
            lastToolReport = "BOUNCED ${ts.pluginChain.size} PLUGIN SLOTS INTO DECK BUFFER"
        )
        mutateDryBufferWithUndo(rendered, "BOUNCED PLUGIN CHAIN TO DECK", "BOUNCED PLUGIN CHAIN")
    }

    fun installCatalogPlugin(entry: PluginCatalogEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = pluginRepo.installPlugin(entry.toDefinition())
            val all = pluginRepo.loadAllPlugins()
            withContext(Dispatchers.Main) {
                _toolsState.value = _toolsState.value.copy(
                    installedPlugins = all,
                    lastToolReport = "INSTALLED PLUGIN: ${saved.name} v${saved.version}"
                )
                _toastEvents.tryEmit("Installed ${saved.name}")
            }
        }
    }

    fun uninstallCustomPlugin(pluginId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            pluginRepo.uninstallPlugin(pluginId)
            val all = pluginRepo.loadAllPlugins()
            withContext(Dispatchers.Main) {
                val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
                val cleanChain = _toolsState.value.pluginChain.filterNot { it.pluginId == pluginId }
                _toolsState.value = _toolsState.value.copy(
                    installedPlugins = all,
                    pluginChain = cleanChain,
                    lastToolReport = "UNINSTALLED PLUGIN ($pluginId)"
                )
                _editorState.value.dryBuffer?.let { reprocessFxChain(it, preserveLoopPlayback = keepLoop) }
            }
        }
    }

    fun importPluginOrIrFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val textAttempt = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    val head = ByteArray(4096)
                    val read = stream.read(head)
                    if (read > 0) {
                        val s = String(head, 0, read, Charsets.UTF_8).trimStart()
                        if (s.startsWith("{")) {
                            val rest = stream.readBytes()
                            String(head, 0, read, Charsets.UTF_8) + String(rest, Charsets.UTF_8)
                        } else null
                    } else null
                }
            }.getOrNull()

            if (textAttempt != null) {
                PluginCatalogRegistry.fromJson(textAttempt).onSuccess { parsed ->
                    pluginRepo.installPlugin(parsed)
                    val all = pluginRepo.loadAllPlugins()
                    withContext(Dispatchers.Main) {
                        _toolsState.value = _toolsState.value.copy(
                            installedPlugins = all,
                            lastToolReport = "IMPORTED PLUGIN BUNDLE: ${parsed.name}"
                        )
                        _toastEvents.tryEmit("Installed plugin bundle: ${parsed.name}")
                    }
                    return@launch
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        val msg = "Invalid plugin JSON: ${err.message}"
                        _toolsState.value = _toolsState.value.copy(lastToolReport = msg)
                        _toastEvents.tryEmit(msg)
                    }
                    return@launch
                }
            }

            // Otherwise treat as custom Impulse Response audio (.wav / media)
            val cacheDir = File(appContext.cacheDir, "plugin_irs").apply { mkdirs() }
            AudioImporter.importUriAsWav(appContext, uri, cacheDir, targetSampleRate = null).onSuccess { (irBuf, file) ->
                val pluginDef = PluginCatalogRegistry.createIrPluginFromBuffer(irBuf, file.nameWithoutExtension)
                val current = (_toolsState.value.installedPlugins + pluginDef).distinctBy { it.id }
                withContext(Dispatchers.Main) {
                    _toolsState.value = _toolsState.value.copy(
                        installedPlugins = current,
                        lastToolReport = "LOADED WAV IMPULSE PLUGIN: ${pluginDef.name}"
                    )
                    _toastEvents.tryEmit("Loaded WAV IR plugin: ${pluginDef.name}")
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    val msg = "Plugin/IR import failed: ${err.message}"
                    _toolsState.value = _toolsState.value.copy(lastToolReport = msg)
                    _toastEvents.tryEmit(msg)
                }
            }
        }
    }

    private fun mutateDryBufferWithUndo(nextDry: AudioBuffer, banner: String, report: String? = null) {
        val currentDry = _editorState.value.dryBuffer
        if (currentDry != null && currentDry.frameCount > 0) {
            undoStack.addLast(currentDry)
        }
        val keepLoop = playbackEngine.state.value.isPlaying && playbackEngine.state.value.isLooping
        if (!keepLoop) {
            playbackEngine.stop()
        }
        _editorState.value = _editorState.value.copy(
            dryBuffer = nextDry,
            canUndo = undoStack.isNotEmpty(),
            statusBanner = banner + if (keepLoop) " • LOOP PERSISTING" else ""
        )
        if (report != null) {
            _toolsState.value = _toolsState.value.copy(lastToolReport = report)
        }
        studioEngine.setSequencerSampleForLane(0, nextDry)
        reprocessFxChain(nextDry, preserveLoopPlayback = keepLoop)
    }

    private fun activeOutputBuffer(): AudioBuffer? {
        val state = _editorState.value
        val active = state.processedBuffer ?: state.dryBuffer
        return if (active != null && active.frameCount > 0) {
            active
        } else {
            val tl = studioEngine.mixdownTimeline(_rigConfig.value.sampleRate)
            if (tl.frameCount > 0) tl else null
        }
    }

    override fun onCleared() {
        super.onCleared()
        stemJob?.cancel()
        micEngine.release()
        playbackEngine.release()
        networkTransmitter.release()
        midiController.release()
        studioEngine.release()
    }
}
