package com.example.ui

import com.example.audio.AudioBuffer
import com.example.audio.AudioSampleFormat
import com.example.audio.SpectrogramData
import com.example.audio.SpectrogramPalette
import com.example.audio.WaveformPeaks
import com.example.export.ExportedAudioResult
import com.example.export.ExportedMp3Result
import com.example.network.SessionHandoffSnapshot
import com.example.plugins.PluginCatalogEntry
import com.example.plugins.PluginCatalogRegistry
import com.example.plugins.PluginSlotInstance
import com.example.plugins.SamplfiePluginDefinition
import com.example.tools.AudioDifferenceResult
import com.example.tools.ChannelScope
import com.example.tools.MonoFoldMode
import com.example.tools.StereoSplitMode
import com.example.tuner.TunerInputSource
import com.example.tuner.TunerReading
import com.example.tuner.TuningPreset
import java.io.File

/**
 * Circular studio hub-and-spoke device topology:
 * BOOTH is the central hub; studio spokes (incl. TOOLS, TUNER, and 64-Ch VMC) radiate around it.
 * Dock visibility and radial wheel spoke placement are customizable in Production Booth.
 */
enum class StudioSpoke(
    val shortLabel: String,
    val fullTitle: String,
    val badge: String
) {
    BOOTH("BOOTH", "PRODUCTION BOOTH", "HUB"),
    DECK("DECK", "CAPTURE & WAV DECK", "AUDIO"),
    SAMPLES("SAMPLES", "LOOPER & SAMPLE REFINER", "LOOPS"),
    INSTRUMENTS("INST", "16-STEP SEQ & MIDI", "SYNTH"),
    TRACKS("TRACKS", "SYNCED TRACK TIMELINE", "SYNC"),
    FX_RACK("FX", "12-MODULE PRO DSP RACK", "DSP"),
    LIVE("LIVE", "LIVE STOMPBOX & RELEASE", "STAGE"),
    TOOLS("TOOLS", "PRO TOOLS & PLUGINS", "PRO"),
    TUNER("TUNER", "CHROMATIC & STROBE TUNER", "PITCH"),
    VMC("VMC", "VMC 64-CH MIDI MAPPER", "MIDI")
}

enum class VisualizerMode(val label: String) {
    WAVEFORM("WAVE"),
    SPECTRUM("FFT"),
    SPECTROGRAM("SPEC"),
    WAVE_SPEC("W+SPEC"),
    DUAL("DUAL")
}

enum class ToolsSectionTab(val label: String) {
    SPECTRAL_PHASE("SPECTRAL / DIFF"),
    MIX_CHANNELS("MIX / MONO / STEREO"),
    RATE_FORMAT("RATE / FORMAT"),
    PLUGIN_MANAGER("PLUGINS"),
    PLUGIN_INSTALLER("INSTALLER")
}

data class ToolsAndPluginsState(
    val activeTab: ToolsSectionTab = ToolsSectionTab.SPECTRAL_PHASE,
    val specPalette: SpectrogramPalette = SpectrogramPalette.SAMPLFIE_NEON,
    val specFreqLowHz: Float = 220f,
    val specFreqHighHz: Float = 3800f,
    val specDeleteAttenDb: Float = 96f,
    val invertScope: ChannelScope = ChannelScope.ALL,
    val invertSelectionOnly: Boolean = false,
    val referenceBuffer: AudioBuffer? = null,
    val referenceLabel: String = "NO REF LOADED",
    val lastDiffResult: AudioDifferenceResult? = null,
    val mixPrimaryGain: Float = 1.0f,
    val mixSecondaryGain: Float = 0.7f,
    val mixOffsetSec: Float = 0f,
    val mixSoftLimit: Boolean = true,
    val monoFoldMode: MonoFoldMode = MonoFoldMode.AVERAGE,
    val stereoSplitMode: StereoSplitMode = StereoSplitMode.HAAS_DELAY,
    val stereoSplitParam: Float = 12f,
    val targetSampleRate: Int = 44100,
    val reinterpretRateOnly: Boolean = false,
    val targetSampleFormat: AudioSampleFormat = AudioSampleFormat.PCM_16,
    val applyDither: Boolean = false,
    val installedPlugins: List<SamplfiePluginDefinition> = PluginCatalogRegistry.BUILT_IN_PLUGINS,
    val pluginChain: List<PluginSlotInstance> = emptyList(),
    val catalogEntries: List<PluginCatalogEntry> = PluginCatalogRegistry.INSTALLABLE_CATALOG,
    val lastToolReport: String = "READY — SELECT SPECTRAL REGION OR AUDIO TOOL"
)

data class WorkspaceConfig(
    val pinnedDockSpokes: List<StudioSpoke> = listOf(
        StudioSpoke.BOOTH,
        StudioSpoke.DECK,
        StudioSpoke.SAMPLES,
        StudioSpoke.INSTRUMENTS,
        StudioSpoke.TRACKS,
        StudioSpoke.FX_RACK,
        StudioSpoke.TOOLS,
        StudioSpoke.LIVE
    ),
    val radialWheelSpokes: List<StudioSpoke> = listOf(
        StudioSpoke.DECK,
        StudioSpoke.SAMPLES,
        StudioSpoke.INSTRUMENTS,
        StudioSpoke.TUNER,
        StudioSpoke.LIVE,
        StudioSpoke.TOOLS,
        StudioSpoke.TRACKS,
        StudioSpoke.FX_RACK
    )
)

data class TunerUiState(
    val source: TunerInputSource = TunerInputSource.DECK_BUFFER,
    val preset: TuningPreset = TuningPreset.CHROMATIC,
    val reading: TunerReading = TunerReading()
)

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
    val spectrogram: SpectrogramData = SpectrogramData.EMPTY,
    val selectionStart: Float = 0f,
    val selectionEnd: Float = 1f,
    val playSelectionOnly: Boolean = false,
    val canUndo: Boolean = false,
    val isProcessingFx: Boolean = false,
    val isImportingAudio: Boolean = false,
    val visualizerMode: VisualizerMode = VisualizerMode.WAVE_SPEC,
    val statusBanner: String = "HOLD TAC PAD TO RECORD .WAV OR IMPORT ANY AUDIO",
    val savedTakes: List<SavedTakeItem> = emptyList(),
    val lastExport: ExportedMp3Result? = null,
    val lastLocalExport: ExportedAudioResult? = null,
    val isExporting: Boolean = false,
    val pendingHandoff: SessionHandoffSnapshot? = null
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
            spectrogram == other.spectrogram &&
            selectionStart == other.selectionStart &&
            selectionEnd == other.selectionEnd &&
            playSelectionOnly == other.playSelectionOnly &&
            canUndo == other.canUndo &&
            isProcessingFx == other.isProcessingFx &&
            isImportingAudio == other.isImportingAudio &&
            visualizerMode == other.visualizerMode &&
            statusBanner == other.statusBanner &&
            savedTakes == other.savedTakes &&
            lastExport == other.lastExport &&
            lastLocalExport == other.lastLocalExport &&
            isExporting == other.isExporting &&
            pendingHandoff == other.pendingHandoff
    }

    override fun hashCode(): Int {
        var result = rawBuffer?.hashCode() ?: 0
        result = 31 * result + (dryBuffer?.hashCode() ?: 0)
        result = 31 * result + (processedBuffer?.hashCode() ?: 0)
        result = 31 * result + dryPeaks.hashCode()
        result = 31 * result + processedPeaks.hashCode()
        result = 31 * result + staticSpectrum.contentHashCode()
        result = 31 * result + spectrogram.hashCode()
        result = 31 * result + selectionStart.hashCode()
        result = 31 * result + selectionEnd.hashCode()
        result = 31 * result + playSelectionOnly.hashCode()
        result = 31 * result + canUndo.hashCode()
        result = 31 * result + isProcessingFx.hashCode()
        result = 31 * result + isImportingAudio.hashCode()
        result = 31 * result + visualizerMode.hashCode()
        result = 31 * result + statusBanner.hashCode()
        result = 31 * result + savedTakes.hashCode()
        result = 31 * result + (lastExport?.hashCode() ?: 0)
        result = 31 * result + (lastLocalExport?.hashCode() ?: 0)
        result = 31 * result + isExporting.hashCode()
        result = 31 * result + (pendingHandoff?.hashCode() ?: 0)
        return result
    }
}

/**
 * State for the Samples Spoke Loop Refinement Editor.
 */
data class LoopEditorState(
    val selectedSlotId: Int = 0,
    val rawSlotBuffer: AudioBuffer? = null,
    val workingBuffer: AudioBuffer? = null,
    val peaks: WaveformPeaks = WaveformPeaks(FloatArray(0), FloatArray(0), FloatArray(0)),
    val loopStartFrac: Float = 0f,
    val loopEndFrac: Float = 1f,
    val fadeInMs: Float = 8f,
    val fadeOutMs: Float = 12f,
    val pitchSemitones: Float = 0f,
    val speedFactor: Float = 1.0f,
    val isReversed: Boolean = false,
    val statusNote: String = "LOAD A LOOP SLOT TO REFINE START/END, FADES & ZERO-CROSSINGS"
)
