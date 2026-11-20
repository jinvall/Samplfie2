package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioAnalyzer
import com.example.audio.AudioBuffer
import com.example.audio.AudioImporter
import com.example.audio.AudioPlaybackEngine
import com.example.audio.MicCaptureEngine
import com.example.audio.MicCaptureTelemetry
import com.example.audio.PlaybackState
import com.example.audio.WavCodec
import com.example.config.RigConfig
import com.example.config.RigConfigRepository
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
import com.example.network.NetworkAudioTransmitter
import com.example.network.NetworkLinkState
import com.example.studio.LooperState
import com.example.studio.SequencerState
import com.example.studio.StudioEngine
import com.example.studio.SyncedPlayheadState
import com.example.studio.TimelineState
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SamplfieViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val takesDir = File(appContext.filesDir, "takes").apply { mkdirs() }

    private val rigRepository = RigConfigRepository(appContext)
    private val micEngine = MicCaptureEngine()
    private val playbackEngine = AudioPlaybackEngine()
    private val networkTransmitter = NetworkAudioTransmitter()
    private val midiController = MidiControllerManager(appContext)
    private val studioEngine = StudioEngine()

    private val undoStack = ArrayDeque<AudioBuffer>()
    private var fxRenderJob: Job? = null
    private val rigSaveMutex = Mutex()
    private var lastTapMs: Long = 0L

    @Volatile
    private var hasRigLocalEdit = false

    private val _selectedSpoke = MutableStateFlow(StudioSpoke.BOOTH)
    val selectedSpoke: StateFlow<StudioSpoke> = _selectedSpoke.asStateFlow()

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

    val micTelemetry: StateFlow<MicCaptureTelemetry> = micEngine.telemetry
    val playbackState: StateFlow<PlaybackState> = playbackEngine.state
    val networkState: StateFlow<NetworkLinkState> = networkTransmitter.state
    val midiState: StateFlow<MidiState> = midiController.state
    val looperState: StateFlow<LooperState> = studioEngine.looperState
    val sequencerState: StateFlow<SequencerState> = studioEngine.sequencerState
    val timelineState: StateFlow<TimelineState> = studioEngine.timelineState
    val syncedPlayhead: StateFlow<SyncedPlayheadState> = studioEngine.syncedPlayhead

    init {
        viewModelScope.launch {
            val loaded = rigRepository.configFlow.first()
            if (!hasRigLocalEdit) {
                _rigConfig.value = loaded
            }
        }
        refreshSavedTakes()
        midiController.start()
        observeMidiEvents()
    }

    private fun observeMidiEvents() {
        viewModelScope.launch {
            midiController.events.collect { event ->
                when (event) {
                    is MidiEvent.NoteOn -> {
                        when (event.note) {
                            in 36..39 -> studioEngine.toggleLoopSlotActive(event.note - 36)
                            in 40..43 -> triggerSequencerLaneOneShot(event.note - 40)
                            in 48..72 -> {
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
                    }
                    is MidiEvent.ControlChange -> {
                        val norm = event.value / 127f
                        when (event.controller) {
                            1, 74 -> updateFxRack {
                                copy(wah = wah.copy(enabled = true, centerFreqHz = 350f + norm * 1850f))
                            }
                            71 -> updateFxRack {
                                copy(overdrive = overdrive.copy(enabled = true, drive = 1f + norm * 16f))
                            }
                            91 -> updateFxRack {
                                copy(reverb = reverb.copy(enabled = true, mix = norm))
                            }
                            93 -> updateFxRack {
                                copy(echo = echo.copy(enabled = true, mix = norm))
                            }
                            7 -> updateFxRack {
                                copy(gain = gain.copy(enabled = true, gainDb = -12f + norm * 24f))
                            }
                        }
                    }
                    MidiEvent.TransportStart -> studioEngine.startSyncedTransport()
                    MidiEvent.TransportStop -> {
                        studioEngine.stopSyncedTransport()
                        playbackEngine.stop()
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

    private fun loadNewRawBuffer(buffer: AudioBuffer, banner: String) {
        undoStack.clear()
        playbackEngine.stop()
        _editorState.value = _editorState.value.copy(
            rawBuffer = buffer,
            dryBuffer = buffer,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = false,
            statusBanner = banner
        )
        playbackEngine.setSelectionBounds(0f, 1f)
        studioEngine.setSequencerSampleForLane(0, buffer)
        reprocessFxChain(buffer)
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
        playbackEngine.setSelectionBounds(lo, hi)
    }

    fun extractSelection() {
        val currentDry = _editorState.value.dryBuffer ?: return
        val start = _editorState.value.selectionStart
        val end = _editorState.value.selectionEnd
        if (end - start <= 0.01f) return

        undoStack.addLast(currentDry)
        val extracted = currentDry.extractSelection(start, end)
        playbackEngine.stop()
        _editorState.value = _editorState.value.copy(
            dryBuffer = extracted,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = undoStack.isNotEmpty(),
            statusBanner = "EXTRACTED ${"%.2f".format(extracted.durationSeconds)}s SELECTION"
        )
        playbackEngine.setSelectionBounds(0f, 1f)
        studioEngine.setSequencerSampleForLane(0, extracted)
        reprocessFxChain(extracted)
    }

    fun undoExtraction() {
        val prev = undoStack.removeLastOrNull() ?: return
        playbackEngine.stop()
        _editorState.value = _editorState.value.copy(
            dryBuffer = prev,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = undoStack.isNotEmpty(),
            statusBanner = "RESTORED PREVIOUS EDIT"
        )
        playbackEngine.setSelectionBounds(0f, 1f)
        reprocessFxChain(prev)
    }

    fun resetToOriginalTake() {
        val raw = _editorState.value.rawBuffer ?: return
        undoStack.clear()
        playbackEngine.stop()
        _editorState.value = _editorState.value.copy(
            dryBuffer = raw,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = false,
            statusBanner = "RESET TO ORIGINAL .WAV"
        )
        playbackEngine.setSelectionBounds(0f, 1f)
        reprocessFxChain(raw)
    }

    // --- PLAYBACK REVIEW ---

    fun togglePlay() = playbackEngine.togglePlay(onlySelection = _editorState.value.playSelectionOnly)
    fun stopPlayback() = playbackEngine.stop()
    fun toggleLoop() = playbackEngine.setLooping(!playbackEngine.state.value.isLooping)
    fun togglePlaySelectionOnly() {
        _editorState.value = _editorState.value.copy(playSelectionOnly = !_editorState.value.playSelectionOnly)
    }
    fun seekPlayhead(fraction: Float) = playbackEngine.seekToFraction(fraction)

    // --- 12-MODULE FX RACK & LIVE STOMPBOX MASTER PROCESSOR ---

    fun updateFxRack(transform: FxRackState.() -> FxRackState) {
        _fxRack.value = _fxRack.value.transform()
        _editorState.value.dryBuffer?.let { reprocessFxChain(it) }
    }

    fun applyFxPreset(preset: FxPreset) {
        _fxRack.value = FxPresets.build(preset)
        _editorState.value.dryBuffer?.let { reprocessFxChain(it) }
        _editorState.value = _editorState.value.copy(statusBanner = "PRESET: ${preset.label}")
    }

    fun toggleMasterBypass() = updateFxRack { copy(masterBypass = !masterBypass) }

    fun updateStompbox(transform: StompboxMasterState.() -> StompboxMasterState) {
        _stompbox.value = _stompbox.value.transform()
        _editorState.value.dryBuffer?.let { reprocessFxChain(it) }
    }

    fun toggleStompboxBypass() = updateStompbox { copy(engaged = !engaged) }

    fun setLiveSessionMode(mode: LiveSessionMode) {
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
        _editorState.value.dryBuffer?.let { reprocessFxChain(it) }
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

    private fun reprocessFxChain(dry: AudioBuffer) {
        fxRenderJob?.cancel()
        val rack = _fxRack.value
        val stomp = _stompbox.value
        val bpm = studioEngine.syncedPlayhead.value.bpm
        _editorState.value = _editorState.value.copy(isProcessingFx = true)
        fxRenderJob = viewModelScope.launch(Dispatchers.Default) {
            val processed = DspChainEngine.process(dry, rack, stomp, bpm)
            val dryPeaks = AudioAnalyzer.computeWaveformPeaks(dry, 140)
            val procPeaks = AudioAnalyzer.computeWaveformPeaks(processed, 140)
            val spectrum = AudioAnalyzer.computeSpectrumBands(processed, processed.frameCount / 2, 512, 32)
            withContext(Dispatchers.Main) {
                _editorState.value = _editorState.value.copy(
                    processedBuffer = processed,
                    dryPeaks = dryPeaks,
                    processedPeaks = procPeaks,
                    staticSpectrum = spectrum,
                    isProcessingFx = false
                )
                playbackEngine.setBuffer(processed, _rigConfig.value.bufferSize)
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

    // --- RIG CONFIG, NETWORK STREAMING & LOCAL WAV/MP3 EXPORT ---

    fun updateRigConfig(transform: RigConfig.() -> RigConfig) {
        hasRigLocalEdit = true
        _rigConfig.value = _rigConfig.value.transform()
        viewModelScope.launch {
            rigSaveMutex.withLock {
                rigRepository.saveConfig(_rigConfig.value)
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
        viewModelScope.launch {
            _editorState.value = _editorState.value.copy(
                isExporting = true,
                statusBanner = "EXPORTING .WAV TO MUSIC/SAMPLFIE2…"
            )
            val res = WavExporter.saveWavToDevice(appContext, buf, fileNameBase)
            res.onSuccess { exported ->
                _editorState.value = _editorState.value.copy(
                    isExporting = false,
                    lastLocalExport = exported,
                    statusBanner = "SAVED WAV: ${exported.displayLocation}"
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
        micEngine.release()
        playbackEngine.release()
        networkTransmitter.stopTransmission()
        midiController.release()
        studioEngine.release()
    }
}
