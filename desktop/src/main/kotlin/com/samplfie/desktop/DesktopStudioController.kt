package com.samplfie.desktop

import com.samplfie.desktop.audio.DesktopMicState
import com.samplfie.desktop.audio.DesktopMp3Exporter
import com.samplfie.desktop.audio.DesktopPlaybackState
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
import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.model.DesktopStompState
import com.samplfie.desktop.model.DesktopWorkspaceConfig
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.model.TunerInputSource
import com.samplfie.desktop.model.TunerReading
import com.samplfie.desktop.model.TuningPreset
import com.samplfie.desktop.model.VisualizerMode
import com.samplfie.desktop.network.NetBridgeState
import com.samplfie.desktop.network.SessionHandoffSnapshot
import com.samplfie.desktop.network.Smp2NetworkNode
import com.samplfie.desktop.studio.DesktopArrangementEngine
import com.samplfie.desktop.studio.DesktopLoopRefinerState
import com.samplfie.desktop.studio.DesktopLooperState
import com.samplfie.desktop.studio.DesktopSequencerState
import com.samplfie.desktop.studio.DesktopSyncedPlayheadState
import com.samplfie.desktop.studio.DesktopTimelineState
import com.samplfie.desktop.tuner.PitchDetector
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DesktopStudioUiState(
    val station: DesktopStation = DesktopStation.EDITOR,
    val visualizerMode: VisualizerMode = VisualizerMode.DUAL,
    val workspaceConfig: DesktopWorkspaceConfig = DesktopWorkspaceConfig(),
    val rawBuffer: DesktopAudioBuffer? = null,
    val dryBuffer: DesktopAudioBuffer? = null,
    val processedBuffer: DesktopAudioBuffer? = null,
    val dryEnvelope: WaveformEnvelope = WaveformEnvelope(FloatArray(0), FloatArray(0), FloatArray(0)),
    val processedEnvelope: WaveformEnvelope = WaveformEnvelope(FloatArray(0), FloatArray(0), FloatArray(0)),
    val staticSpectrum: FloatArray = FloatArray(48),
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
 * Synced Tracks, FX Rack, Live Stompbox, Chromatic Tuner, 64-Ch VMC MIDI, and Peer Handoff Bridge).
 */
class DesktopStudioController {

    private val undoStack = ArrayDeque<DesktopAudioBuffer>()
    private var lastTapMs: Long = 0L

    val defaultExportDir: File = File(System.getProperty("user.home"), "Music/Samplfie2").apply { mkdirs() }

    private val _uiState = MutableStateFlow(DesktopStudioUiState())
    val uiState: StateFlow<DesktopStudioUiState> = _uiState.asStateFlow()

    val arrangement = DesktopArrangementEngine()

    val soundEngine: LinuxSoundEngine = LinuxSoundEngine { monoChunk, sr ->
        val src = _uiState.value.tunerSource
        if (src == TunerInputSource.LIVE_MIC || (src == TunerInputSource.DECK_BUFFER && playbackState.value.isPlaying)) {
            analyzeSamplesForTuner(monoChunk, sr)
        }
    }

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
        }
    )

    val playbackState: StateFlow<DesktopPlaybackState> = soundEngine.playbackState
    val micState: StateFlow<DesktopMicState> = soundEngine.micState
    val netState: StateFlow<NetBridgeState> = netNode.state
    val looperState: StateFlow<DesktopLooperState> = arrangement.looperState
    val loopRefinerState: StateFlow<DesktopLoopRefinerState> = arrangement.loopRefinerState
    val sequencerState: StateFlow<DesktopSequencerState> = arrangement.sequencerState
    val timelineState: StateFlow<DesktopTimelineState> = arrangement.timelineState
    val syncedPlayhead: StateFlow<DesktopSyncedPlayheadState> = arrangement.syncedPlayhead
    val vmcState: StateFlow<VmcEngineState> = vmcEngine.state

    init {
        generateTestSignal(SignalWaveformType.SINE, 440.0)
        refreshMidiPorts()
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

    private fun mutateDryBuffer(banner: String, transform: (DesktopAudioBuffer) -> DesktopAudioBuffer) {
        val cur = _uiState.value.dryBuffer ?: return
        undoStack.addLast(cur)
        val next = transform(cur)
        val keepLoop = playbackState.value.isPlaying && playbackState.value.isLooping
        if (!keepLoop) {
            soundEngine.stopPlayback()
        }
        _uiState.value = _uiState.value.copy(
            dryBuffer = next,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = undoStack.isNotEmpty(),
            statusLine = "$banner (${"%.2f".format(next.durationSeconds)}s)${if (keepLoop) " • LOOP ACTIVE" else ""}"
        )
        soundEngine.setSelection(0f, 1f, _uiState.value.playSelectionOnly)
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
        val proc = DesktopDspProcessor.process(dry, fx, stomp)
        val dryEnv = SignalAnalyzer.computeWaveformEnvelope(dry, 180)
        val procEnv = SignalAnalyzer.computeWaveformEnvelope(proc, 180)
        val spec = SignalAnalyzer.computeSpectrumBands(proc, proc.frameCount / 2, 1024, 48)
        _uiState.value = _uiState.value.copy(
            processedBuffer = proc,
            dryEnvelope = dryEnv,
            processedEnvelope = procEnv,
            staticSpectrum = spec
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
        _uiState.value = _uiState.value.copy(
            fxState = snap.fxState,
            stompState = snap.stompState,
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
        return runCatching {
            DesktopWavCodec.writeWav(buf, targetFile)
            _uiState.value = _uiState.value.copy(statusLine = "SAVED WAV -> ${targetFile.absolutePath}")
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

    // --- NETWORK BRIDGE ---

    fun updateRigConfig(transform: DesktopRigConfig.() -> DesktopRigConfig) {
        _uiState.value = _uiState.value.copy(rigConfig = _uiState.value.rigConfig.transform())
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

    fun shutdown() {
        soundEngine.stopPlayback()
        soundEngine.stopMicAndCollectBuffer()
        arrangement.release()
        hwMidi.closeAll()
        netNode.stopListening()
    }
}
