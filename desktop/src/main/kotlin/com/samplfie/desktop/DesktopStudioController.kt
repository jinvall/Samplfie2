package com.samplfie.desktop

import com.samplfie.desktop.audio.DesktopMicState
import com.samplfie.desktop.audio.DesktopMp3Exporter
import com.samplfie.desktop.audio.DesktopPlaybackState
import com.samplfie.desktop.audio.DesktopWavCodec
import com.samplfie.desktop.audio.LinuxSoundEngine
import com.samplfie.desktop.audio.SignalAnalyzer
import com.samplfie.desktop.audio.WaveformEnvelope
import com.samplfie.desktop.dsp.DesktopDspProcessor
import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopFxState
import com.samplfie.desktop.model.DesktopPreset
import com.samplfie.desktop.model.DesktopPresetFactory
import com.samplfie.desktop.model.DesktopRigConfig
import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.model.DesktopStompState
import com.samplfie.desktop.model.NetworkProtocol
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.model.TunerInputSource
import com.samplfie.desktop.model.TunerReading
import com.samplfie.desktop.model.TuningPreset
import com.samplfie.desktop.model.VisualizerMode
import com.samplfie.desktop.network.NetBridgeState
import com.samplfie.desktop.network.Smp2NetworkNode
import com.samplfie.desktop.tuner.PitchDetector
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DesktopStudioUiState(
    val station: DesktopStation = DesktopStation.EDITOR,
    val visualizerMode: VisualizerMode = VisualizerMode.DUAL,
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
    val statusLine: String = "READY — LOAD .WAV, GENERATE SIGNAL, OR LISTEN FOR SMP2 STREAM"
)

/**
 * Central state controller for Samplfie2 Desktop Audio, Signal Editor & Chromatic Tuner.
 */
class DesktopStudioController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val undoStack = ArrayDeque<DesktopAudioBuffer>()

    val defaultExportDir: File = File(System.getProperty("user.home"), "Music/Samplfie2").apply { mkdirs() }

    private val _uiState = MutableStateFlow(DesktopStudioUiState())
    val uiState: StateFlow<DesktopStudioUiState> = _uiState.asStateFlow()

    val soundEngine: LinuxSoundEngine = LinuxSoundEngine { monoChunk, sr ->
        val src = _uiState.value.tunerSource
        if (src == TunerInputSource.LIVE_MIC || (src == TunerInputSource.DECK_BUFFER && playbackState.value.isPlaying)) {
            analyzeSamplesForTuner(monoChunk, sr)
        }
    }

    val netNode: Smp2NetworkNode = Smp2NetworkNode { monoChunk, sr ->
        if (_uiState.value.tunerSource == TunerInputSource.SMP2_STREAM) {
            analyzeSamplesForTuner(monoChunk, sr)
        }
    }

    val playbackState: StateFlow<DesktopPlaybackState> = soundEngine.playbackState
    val micState: StateFlow<DesktopMicState> = soundEngine.micState
    val netState: StateFlow<NetBridgeState> = netNode.state

    init {
        // Boot with a calibrated 440Hz A4 reference tone so waveform, FFT & tuner respond immediately
        generateTestSignal(SignalWaveformType.SINE, 440.0)
    }

    fun selectStation(station: DesktopStation) {
        _uiState.value = _uiState.value.copy(station = station)
        if (station == DesktopStation.TUNER && _uiState.value.tunerSource == TunerInputSource.DECK_BUFFER) {
            analyzeActiveBufferWindowForTuner()
        }
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

    fun loadNewBuffer(buffer: DesktopAudioBuffer, banner: String) {
        undoStack.clear()
        soundEngine.stopPlayback()
        _uiState.value = _uiState.value.copy(
            rawBuffer = buffer,
            dryBuffer = buffer,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = false,
            statusLine = banner
        )
        soundEngine.setSelection(0f, 1f, _uiState.value.playSelectionOnly)
        reprocessDsp(buffer)
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
        soundEngine.stopPlayback()
        _uiState.value = _uiState.value.copy(
            dryBuffer = prev,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = undoStack.isNotEmpty(),
            statusLine = "RESTORED PREVIOUS EDIT"
        )
        reprocessDsp(prev)
    }

    fun resetToOriginal() {
        val raw = _uiState.value.rawBuffer ?: return
        undoStack.clear()
        soundEngine.stopPlayback()
        _uiState.value = _uiState.value.copy(
            dryBuffer = raw,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = false,
            statusLine = "RESET TO ORIGINAL SIGNAL"
        )
        reprocessDsp(raw)
    }

    private fun mutateDryBuffer(banner: String, transform: (DesktopAudioBuffer) -> DesktopAudioBuffer) {
        val cur = _uiState.value.dryBuffer ?: return
        undoStack.addLast(cur)
        val next = transform(cur)
        soundEngine.stopPlayback()
        _uiState.value = _uiState.value.copy(
            dryBuffer = next,
            selectionStart = 0f,
            selectionEnd = 1f,
            canUndo = undoStack.isNotEmpty(),
            statusLine = "$banner (${"%.2f".format(next.durationSeconds)}s)"
        )
        soundEngine.setSelection(0f, 1f, _uiState.value.playSelectionOnly)
        reprocessDsp(next)
    }

    fun updateFx(transform: DesktopFxState.() -> DesktopFxState) {
        _uiState.value = _uiState.value.copy(fxState = _uiState.value.fxState.transform())
        _uiState.value.dryBuffer?.let { reprocessDsp(it) }
    }

    fun applyPreset(preset: DesktopPreset) {
        _uiState.value = _uiState.value.copy(
            fxState = DesktopPresetFactory.build(preset),
            statusLine = "PRESET: ${preset.label}"
        )
        _uiState.value.dryBuffer?.let { reprocessDsp(it) }
    }

    fun updateStomp(transform: DesktopStompState.() -> DesktopStompState) {
        _uiState.value = _uiState.value.copy(stompState = _uiState.value.stompState.transform())
        _uiState.value.dryBuffer?.let { reprocessDsp(it) }
    }

    fun toggleMasterStompBypass() {
        val nextStomp = _uiState.value.stompState.copy(engaged = !_uiState.value.stompState.engaged)
        _uiState.value = _uiState.value.copy(
            stompState = nextStomp,
            statusLine = if (nextStomp.engaged) "MASTER STOMP ENGAGED" else "MASTER STOMP BYPASSED"
        )
        _uiState.value.dryBuffer?.let { reprocessDsp(it) }
    }

    private fun reprocessDsp(dry: DesktopAudioBuffer) {
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
        soundEngine.setBuffer(proc)
        if (_uiState.value.tunerSource == TunerInputSource.DECK_BUFFER) {
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

    // --- SMP2 NETWORK BRIDGE ---

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
        val captured = netNode.captureReceivedBuffer("SMP2_ANDROID_RX")
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
        netNode.stopListening()
    }
}
