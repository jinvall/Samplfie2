package com.example.ui

import android.net.Uri
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioAnalyzer
import com.example.audio.AudioBuffer
import com.example.audio.AudioDecoder
import com.example.audio.AudioPlaybackEngine
import com.example.audio.MicCaptureEngine
import com.example.audio.MicCaptureTelemetry
import com.example.audio.PlaybackState
import com.example.audio.WavCodec
import com.example.audio.WaveformPeaks
import com.example.config.NetworkProtocol
import com.example.config.RigConfig
import com.example.config.RigConfigRepository
import com.example.dsp.ChorusParams
import com.example.dsp.DelayParams
import com.example.dsp.DspChainEngine
import com.example.dsp.EchoParams
import com.example.dsp.FlangerParams
import com.example.dsp.FxPreset
import com.example.dsp.FxPresets
import com.example.dsp.FxRackState
import com.example.dsp.GainParams
import com.example.dsp.NoiseRemovalParams
import com.example.dsp.OverdriveParams
import com.example.dsp.PhaserParams
import com.example.dsp.PitchSpeedParams
import com.example.dsp.ReverbParams
import com.example.dsp.RotaryParams
import com.example.dsp.WahParams
import com.example.export.ExportedMp3Result
import com.example.export.Mp3Exporter
import com.example.midi.MidiControllerManager
import com.example.midi.MidiEvent
import com.example.midi.MidiState
import com.example.network.NetworkAudioTransmitter
import com.example.network.NetworkLinkState
import com.example.studio.LooperState
import com.example.studio.SequencerState
import com.example.studio.StudioEngine
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

enum class ConsoleTab(val label: String) {
    DECK("DECK"),
    FX_RACK("FX RACK"),
    STUDIO("LOOPER / SEQ"),
    RIG_LINK("RIG & MIDI")
}

enum class VisualizerMode(val label: String) {
    WAVEFORM("WAVE"),
    SPECTRUM("FFT"),
    DUAL("DUAL")
}

data class SavedTakeItem(
    val name: String,
    val file: File,
    val durationSec: Float
)

data class EditorState(
    val rawBuffer: AudioBuffer? = null,
    val dryBuffer: AudioBuffer? = null,
    val processedBuffer: AudioBuffer? = null,
    val dryPeaks: WaveformPeaks = WaveformPeaks(FloatArray(0), FloatArray(0), FloatArray(0)),
    val processedPeaks: WaveformPeaks = WaveformPeaks(FloatArray(0), FloatArray(0), FloatArray(0)),
    val staticSpectrum: FloatArray = FloatArray(32),
    val selectionStart: Float = 0f,
    val selectionEnd: Float = 1f,
    val playSelectionOnly: Boolean = false,
    val canUndo: Boolean = false,
    val isProcessingFx: Boolean = false,
    val visualizerMode: VisualizerMode = VisualizerMode.DUAL,
    val statusBanner: String = "HOLD TAC PAD TO CAPTURE MIC .WAV",
    val savedTakes: List<SavedTakeItem> = emptyList(),
    val lastExport: ExportedMp3Result? = null,
    val isExportingMp3: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EditorState) return false
        return rawBuffer == other.rawBuffer &&
            dryBuffer == other.dryBuffer &&
            processedBuffer == other.processedBuffer &&
            dryPeaks == other.dryPeaks &&
            processedPeaks == other.processedPeaks &&
            staticSpectrum.contentEquals(other.staticSpectrum) &&
            selectionStart == other.selectionStart &&
            selectionEnd == other.selectionEnd &&
            playSelectionOnly == other.playSelectionOnly &&
            canUndo == other.canUndo &&
            isProcessingFx == other.isProcessingFx &&
            visualizerMode == other.visualizerMode &&
            statusBanner == other.statusBanner &&
            savedTakes == other.savedTakes &&
            lastExport == other.lastExport &&
            isExportingMp3 == other.isExportingMp3
    }

    override fun hashCode(): Int {
        var result = rawBuffer?.hashCode() ?: 0
        result = 31 * result + (dryBuffer?.hashCode() ?: 0)
        result = 31 * result + (processedBuffer?.hashCode() ?: 0)
        result = 31 * result + dryPeaks.hashCode()
        result = 31 * result + processedPeaks.hashCode()
        result = 31 * result + staticSpectrum.contentHashCode()
        result = 31 * result + selectionStart.hashCode()
        result = 31 * result + selectionEnd.hashCode()
        result = 31 * result + playSelectionOnly.hashCode()
        result = 31 * result + canUndo.hashCode()
        result = 31 * result + isProcessingFx.hashCode()
        result = 31 * result + visualizerMode.hashCode()
        result = 31 * result + statusBanner.hashCode()
        result = 31 * result + savedTakes.hashCode()
        result = 31 * result + (lastExport?.hashCode() ?: 0)
        result = 31 * result + isExportingMp3.hashCode()
        return result
    }
}

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

    @Volatile
    private var hasRigLocalEdit = false

    private val _selectedTab = MutableStateFlow(ConsoleTab.DECK)
    val selectedTab: StateFlow<ConsoleTab> = _selectedTab.asStateFlow()

    private val _rigConfig = MutableStateFlow(RigConfig())
    val rigConfig: StateFlow<RigConfig> = _rigConfig.asStateFlow()

    private val _fxRack = MutableStateFlow(FxRackState())
    val fxRack: StateFlow<FxRackState> = _fxRack.asStateFlow()

    private val _editorState = MutableStateFlow(EditorState())
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    val micTelemetry: StateFlow<MicCaptureTelemetry> = micEngine.telemetry
    val playbackState: StateFlow<PlaybackState> = playbackEngine.state
    val networkState: StateFlow<NetworkLinkState> = networkTransmitter.state
    val midiState: StateFlow<MidiState> = midiController.state
    val looperState: StateFlow<LooperState> = studioEngine.looperState
    val sequencerState: StateFlow<SequencerState> = studioEngine.sequencerState
    val timelineState: StateFlow<TimelineState> = studioEngine.timelineState

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
                            in 40..43 -> {
                                val lane = studioEngine.sequencerState.value.lanes.getOrNull(event.note - 40)
                                if (lane != null) {
                                    playbackEngine.setBuffer(lane.buffer, _rigConfig.value.bufferSize)
                                    playbackEngine.play(onlySelection = false)
                                }
                            }
                            in 48..72 -> {
                                val base = _editorState.value.processedBuffer ?: return@collect
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
                    MidiEvent.TransportStart -> studioEngine.startSequencer()
                    MidiEvent.TransportStop -> {
                        studioEngine.stopSequencer()
                        studioEngine.stopLooper()
                        playbackEngine.stop()
                    }
                    MidiEvent.ClockTick -> {
                        midiController.state.value.syncedBpm?.let { bpm ->
                            studioEngine.setBpm(bpm)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun selectTab(tab: ConsoleTab) {
        _selectedTab.value = tab
    }

    fun setVisualizerMode(mode: VisualizerMode) {
        _editorState.value = _editorState.value.copy(visualizerMode = mode)
    }

    // --- TAC HOLD MIC CAPTURE ---

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

    /**
     * Built-in hardware reference oscillator (synth chord/tone) for testing DSP/visualizer/export
     * even when no acoustic microphone input is present.
     */
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

    /**
     * Imports an audio file from a content Uri (MP3, AAC, OGG, WAV, FLAC, ...),
     * decodes it to PCM via MediaExtractor + MediaCodec, persists a .wav copy to
     * the takes directory, and loads it into the editor as the active buffer.
     */
    fun importAudioFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = _rigConfig.value
                val displayName = uri.lastPathSegment
                    ?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    ?.ifBlank { "Imported" }
                    ?: "Imported"
                val name = if (displayName.endsWith(".wav", ignoreCase = true)) {
                    displayName.substringBeforeLast(".")
                } else {
                    displayName
                }

                val buffer = AudioDecoder.decodeUriToBuffer(
                    context = appContext,
                    uri = uri,
                    targetSampleRate = config.sampleRate,
                    targetChannels = config.channels,
                    name = name
                )

                // Persist a .wav copy so it shows up in the saved-takes bank
                val wavFile = File(takesDir, "$name.wav")
                WavCodec.writeWav(buffer, wavFile)

                withContext(Dispatchers.Main) {
                    loadNewRawBuffer(buffer, "IMPORTED $name")
                    refreshSavedTakes()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _editorState.value = _editorState.value.copy(
                        statusBanner = "IMPORT FAILED: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    private fun refreshSavedTakes() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = takesDir.listFiles { f -> f.extension.equals("wav", true) }
                ?.sortedByDescending { it.lastModified() }
                ?.take(8)
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

    fun togglePlay() {
        playbackEngine.togglePlay(onlySelection = _editorState.value.playSelectionOnly)
    }

    fun stopPlayback() {
        playbackEngine.stop()
    }

    fun toggleLoop() {
        playbackEngine.setLooping(!playbackEngine.state.value.isLooping)
    }

    fun togglePlaySelectionOnly() {
        val next = !_editorState.value.playSelectionOnly
        _editorState.value = _editorState.value.copy(playSelectionOnly = next)
    }

    fun seekPlayhead(fraction: Float) {
        playbackEngine.seekToFraction(fraction)
    }

    // --- 12-MODULE FX RACK ---

    fun updateFxRack(transform: FxRackState.() -> FxRackState) {
        val updated = _fxRack.value.transform()
        _fxRack.value = updated
        _editorState.value.dryBuffer?.let { reprocessFxChain(it) }
    }

    fun applyFxPreset(preset: FxPreset) {
        _fxRack.value = FxPresets.build(preset)
        _editorState.value.dryBuffer?.let { reprocessFxChain(it) }
        _editorState.value = _editorState.value.copy(statusBanner = "PRESET: ${preset.label}")
    }

    fun toggleMasterBypass() {
        updateFxRack { copy(masterBypass = !masterBypass) }
    }

    private fun reprocessFxChain(dry: AudioBuffer) {
        fxRenderJob?.cancel()
        val rack = _fxRack.value
        _editorState.value = _editorState.value.copy(isProcessingFx = true)
        fxRenderJob = viewModelScope.launch(Dispatchers.Default) {
            val processed = DspChainEngine.process(dry, rack)
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

    // --- LOOPER / SEQUENCER / TIMELINE ACTIONS ---

    fun sendEditorToLoopSlot(slotId: Int) {
        val buf = activeOutputBuffer() ?: return
        studioEngine.assignBufferToLoopSlot(slotId, buf, buf.name)
        _editorState.value = _editorState.value.copy(statusBanner = "ASSIGNED TO LOOP ${'A' + slotId}")
    }

    fun toggleLoopSlot(slotId: Int) = studioEngine.toggleLoopSlotActive(slotId)
    fun toggleLoopMute(slotId: Int) = studioEngine.toggleLoopSlotMute(slotId)
    fun clearLoopSlot(slotId: Int) = studioEngine.clearLoopSlot(slotId)
    fun setLoopSlotBars(slotId: Int, bars: Int) = studioEngine.setLoopSlotBars(slotId, bars)
    fun toggleLooperPlayback() = studioEngine.toggleLooperPlayback()

    fun bounceLooperToEditor() {
        val mix = studioEngine.bounceLooperMixdown(_rigConfig.value.sampleRate)
        loadNewRawBuffer(mix, "BOUNCED LOOPER MIX TO EDITOR")
        _selectedTab.value = ConsoleTab.DECK
    }

    fun sendEditorToSequencerLane(laneId: Int = 0) {
        val buf = activeOutputBuffer() ?: return
        studioEngine.setSequencerSampleForLane(laneId, buf)
        _editorState.value = _editorState.value.copy(statusBanner = "SAMPLE ASSIGNED TO SEQ LANE ${laneId + 1}")
    }

    fun toggleSequencerStep(laneId: Int, step: Int) = studioEngine.toggleSequencerStep(laneId, step)
    fun toggleSequencerLaneMute(laneId: Int) = studioEngine.toggleSequencerLaneMute(laneId)
    fun toggleSequencerPlayback() = studioEngine.toggleSequencerPlayback()
    fun setStudioBpm(bpm: Float) = studioEngine.setBpm(bpm)
    fun setSequencerSwing(swing: Float) = studioEngine.setSequencerSwing(swing)

    fun bounceSequencerToTimeline() {
        val beat = studioEngine.renderSequenceToBuffer(bars = 2, sampleRate = _rigConfig.value.sampleRate)
        studioEngine.addClipToTimeline(2, beat, startTimeSec = 0f)
        _editorState.value = _editorState.value.copy(statusBanner = "BOUNCED 2-BAR BEAT TO TIMELINE")
    }

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
    fun seekTimeline(sec: Float) = studioEngine.seekTimelinePlayhead(sec)
    fun toggleTimelinePlayback() = studioEngine.toggleTimelinePlayback()

    fun bounceTimelineToEditor() {
        val mix = studioEngine.mixdownTimeline(_rigConfig.value.sampleRate)
        if (mix.frameCount == 0) {
            _editorState.value = _editorState.value.copy(statusBanner = "TIMELINE HAS NO CLIPS")
            return
        }
        loadNewRawBuffer(mix, "TIMELINE MIXDOWN IN EDITOR")
        _selectedTab.value = ConsoleTab.DECK
    }

    // --- RIG CONFIG, NETWORK STREAMING & MP3 EXPORT ---

    fun updateRigConfig(transform: RigConfig.() -> RigConfig) {
        hasRigLocalEdit = true
        val next = _rigConfig.value.transform()
        _rigConfig.value = next
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

    fun stopNetworkStream() {
        networkTransmitter.stopTransmission()
    }

    fun exportAsMp3(fileNameBase: String) {
        val buf = activeOutputBuffer() ?: run {
            _editorState.value = _editorState.value.copy(statusBanner = "NO AUDIO TO EXPORT AS MP3")
            return
        }
        viewModelScope.launch {
            _editorState.value = _editorState.value.copy(
                isExportingMp3 = true,
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
                    isExportingMp3 = false,
                    lastExport = exported,
                    statusBanner = "SAVED MP3: ${exported.displayLocation}"
                )
            }.onFailure { err ->
                _editorState.value = _editorState.value.copy(
                    isExportingMp3 = false,
                    statusBanner = "MP3 EXPORT FAILED: ${err.message}"
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
