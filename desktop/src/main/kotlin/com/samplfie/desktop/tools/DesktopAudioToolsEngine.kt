package com.samplfie.desktop.tools

import com.samplfie.desktop.audio.DesktopSpectrogramAnalyzer
import com.samplfie.desktop.audio.DesktopSpectrogramPalette
import com.samplfie.desktop.model.DesktopAudioBuffer
import com.samplfie.desktop.model.DesktopSampleFormat
import com.samplfie.desktop.plugins.DesktopLadspaBrowserClient
import com.samplfie.desktop.plugins.DesktopLadspaBrowserEntry
import com.samplfie.desktop.plugins.DesktopPluginCatalogEntry
import com.samplfie.desktop.plugins.DesktopPluginCatalogRegistry
import com.samplfie.desktop.plugins.DesktopPluginDefinition
import com.samplfie.desktop.plugins.DesktopPluginSlotInstance
import com.samplfie.desktop.plugins.NativeLinuxPluginInfo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

enum class ChannelScope(val label: String) {
    ALL("ALL CH"),
    LEFT_ONLY("LEFT ONLY"),
    RIGHT_ONLY("RIGHT ONLY")
}

enum class MonoFoldMode(val label: String) {
    AVERAGE("AVERAGE (L+R)/2"),
    EQUAL_POWER("EQUAL POWER (-3dB)"),
    LEFT_ONLY("LEFT -> MONO"),
    RIGHT_ONLY("RIGHT -> MONO")
}

enum class StereoSplitMode(val label: String) {
    DUAL_MONO("DUAL MONO (L=R)"),
    HAAS_DELAY("HAAS WIDENER (ms)"),
    PSEUDO_STEREO_COMB("PSEUDO-STEREO COMB"),
    MID_SIDE_WIDTH("MID-SIDE WIDTH")
}

enum class DesktopToolsTab(val label: String) {
    SPECTRAL_DIFF("SPECTRAL / INVERT / DIFF"),
    MIX_CHANNELS("MIX / MONO / STEREO"),
    RATE_FORMAT("SAMPLE RATE / FORMAT"),
    PLUGIN_MANAGER("PLUGIN MANAGER"),
    PLUGIN_INSTALLER("PLUGIN INSTALLER")
}

data class DesktopAudioDiffResult(
    val diffBuffer: DesktopAudioBuffer,
    val peakDiff: Float,
    val rmsDiff: Float,
    val nullDepthDb: Float,
    val correlation: Float,
    val alignedFrames: Int
)

data class DesktopToolsState(
    val activeTab: DesktopToolsTab = DesktopToolsTab.SPECTRAL_DIFF,
    val specPalette: DesktopSpectrogramPalette = DesktopSpectrogramPalette.SAMPLFIE_NEON,
    val specFreqLowHz: Float = 220f,
    val specFreqHighHz: Float = 3800f,
    val specDeleteAttenDb: Float = 96f,
    val invertScope: ChannelScope = ChannelScope.ALL,
    val invertSelectionOnly: Boolean = false,
    val referenceBuffer: DesktopAudioBuffer? = null,
    val referenceLabel: String = "NO REF LOADED",
    val lastDiffResult: DesktopAudioDiffResult? = null,
    val mixPrimaryGain: Float = 1.0f,
    val mixSecondaryGain: Float = 0.7f,
    val mixOffsetSec: Float = 0f,
    val mixSoftLimit: Boolean = true,
    val monoFoldMode: MonoFoldMode = MonoFoldMode.AVERAGE,
    val stereoSplitMode: StereoSplitMode = StereoSplitMode.HAAS_DELAY,
    val stereoSplitParam: Float = 12f,
    val targetSampleRate: Int = 44100,
    val reinterpretRateOnly: Boolean = false,
    val targetSampleFormat: DesktopSampleFormat = DesktopSampleFormat.PCM_16,
    val applyDither: Boolean = false,
    val installedPlugins: List<DesktopPluginDefinition> = DesktopPluginCatalogRegistry.BUILT_IN_PLUGINS,
    val pluginChain: List<PluginSlotInstanceAlias> = emptyList(),
    val catalogEntries: List<DesktopPluginCatalogEntry> = DesktopPluginCatalogRegistry.INSTALLABLE_CATALOG,
    val nativeLinuxPlugins: List<NativeLinuxPluginInfo> = emptyList(),
    val ladspaFeedUrl: String = DesktopLadspaBrowserClient.DEFAULT_LADSPA_FEED_URL,
    val ladspaEntries: List<DesktopLadspaBrowserEntry> = DesktopLadspaBrowserClient.CURATED_COMMUNITY_LADSPA,
    val isFetchingLadspa: Boolean = false,
    val ladspaStatus: String = "READY — CURATED LADSPA CATALOG (ZERO-DCL)",
    val lastToolReport: String = "READY — SELECT SPECTRAL REGION OR AUDIO TOOL"
)

typealias PluginSlotInstanceAlias = DesktopPluginSlotInstance

/**
 * Pure-Kotlin Pro Audio Signal Tools Suite for Linux Desktop:
 * Spectral Delete, Invert, Audio Difference, Mix, Mix to Mono, Split to Stereo,
 * Sample Rate Conversion, and Multi-Format (PCM 8/16/24/32 + IEEE Float 32/64) Conversion.
 */
object DesktopAudioToolsEngine {

    fun spectralDelete(
        buffer: DesktopAudioBuffer,
        startFrac: Float,
        endFrac: Float,
        lowFreqHz: Float,
        highFreqHz: Float,
        attenuationDb: Float = 96f,
        fftSize: Int = 1024
    ): DesktopAudioBuffer {
        if (buffer.frameCount < 32) return buffer
        val n = highestPowerOfTwo(fftSize.coerceIn(256, 2048))
        val hop = n / 4
        val sr = buffer.sampleRate.coerceAtLeast(8000)
        val ch = buffer.channels
        val frames = buffer.frameCount

        val loFrac = min(startFrac, endFrac).coerceIn(0f, 1f)
        val hiFrac = max(startFrac, endFrac).coerceIn(loFrac, 1f)
        val selStartFrame = (loFrac * frames).toInt().coerceIn(0, frames - 1)
        val selEndFrame = (hiFrac * frames).toInt().coerceIn(selStartFrame + 1, frames)

        val fLo = min(lowFreqHz, highFreqHz).coerceIn(10f, sr * 0.49f)
        val fHi = max(lowFreqHz, highFreqHz).coerceIn(fLo + 10f, sr * 0.5f)
        val binLo = ((fLo * n) / sr).toInt().coerceIn(1, n / 2 - 1)
        val binHi = ((fHi * n) / sr).toInt().coerceIn(binLo, n / 2 - 1)

        val minGain = if (attenuationDb >= 90f) 0f else 10f.pow(-attenuationDb.coerceIn(0f, 96f) / 20f)
        val hann = FloatArray(n) { i -> (0.5 * (1.0 - cos(2.0 * PI * i / n))).toFloat() }
        val outSamples = FloatArray(buffer.samples.size)

        for (c in 0 until ch) {
            val mono = FloatArray(frames) { f -> buffer.samples[f * ch + c] }
            val accum = FloatArray(frames + n)
            val norm = FloatArray(frames + n)
            val real = FloatArray(n)
            val imag = FloatArray(n)

            var pos = 0
            while (pos < frames) {
                val frameCenter = pos + n / 2
                val inTimeRegion = frameCenter in selStartFrame..selEndFrame

                for (i in 0 until n) {
                    val idx = pos + i
                    real[i] = if (idx < frames) mono[idx] * hann[i] else 0f
                    imag[i] = 0f
                }

                if (inTimeRegion) {
                    DesktopSpectrogramAnalyzer.fftInPlace(real, imag)
                    val distEdge = min(frameCenter - selStartFrame, selEndFrame - frameCenter)
                    val timeBlend = (distEdge.toFloat() / hop.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val targetGain = 1f - timeBlend * (1f - minGain)

                    for (k in binLo..binHi) {
                        val binGain = if ((k == binLo || k == binHi) && binHi - binLo >= 2) {
                            1f - (1f - targetGain) * 0.65f
                        } else {
                            targetGain
                        }
                        real[k] *= binGain
                        imag[k] *= binGain
                        val mirror = n - k
                        if (mirror in 1 until n && mirror != k) {
                            real[mirror] *= binGain
                            imag[mirror] *= binGain
                        }
                    }

                    for (k in 0 until n) imag[k] = -imag[k]
                    DesktopSpectrogramAnalyzer.fftInPlace(real, imag)
                    val invN = 1f / n.toFloat()
                    for (i in 0 until n) {
                        accum[pos + i] += real[i] * invN * hann[i]
                        norm[pos + i] += hann[i] * hann[i]
                    }
                } else {
                    for (i in 0 until n) {
                        val s = if (pos + i < frames) mono[pos + i] * hann[i] else 0f
                        accum[pos + i] += s * hann[i]
                        norm[pos + i] += hann[i] * hann[i]
                    }
                }
                pos += hop
            }

            for (f in 0 until frames) {
                val nrm = norm[f]
                val recon = if (nrm > 1e-4f) (accum[f] / nrm) else mono[f]
                outSamples[f * ch + c] = recon.coerceIn(-1f, 1f)
            }
        }
        return buffer.copy(samples = outSamples, name = "${buffer.name}_specdel")
    }

    fun invertPolarity(
        buffer: DesktopAudioBuffer,
        startFrac: Float = 0f,
        endFrac: Float = 1f,
        scope: ChannelScope = ChannelScope.ALL
    ): DesktopAudioBuffer {
        if (buffer.frameCount == 0) return buffer
        val lo = min(startFrac, endFrac).coerceIn(0f, 1f)
        val hi = max(startFrac, endFrac).coerceIn(lo, 1f)
        val startFrame = (lo * buffer.frameCount).toInt().coerceIn(0, max(0, buffer.frameCount - 1))
        val endFrame = (hi * buffer.frameCount).toInt().coerceIn(startFrame + 1, buffer.frameCount)
        val out = buffer.samples.copyOf()
        val ch = buffer.channels
        for (f in startFrame until endFrame) {
            for (c in 0 until ch) {
                val doInvert = when (scope) {
                    ChannelScope.ALL -> true
                    ChannelScope.LEFT_ONLY -> c == 0
                    ChannelScope.RIGHT_ONLY -> (ch == 1 && c == 0) || c == 1
                }
                if (doInvert) out[f * ch + c] = (-out[f * ch + c]).coerceIn(-1f, 1f)
            }
        }
        return buffer.copy(samples = out, name = "${buffer.name}_inv")
    }

    fun computeAudioDifference(primary: DesktopAudioBuffer, reference: DesktopAudioBuffer): DesktopAudioDiffResult {
        val alignedRef = if (reference.sampleRate != primary.sampleRate) reference.resample(primary.sampleRate) else reference
        val targetCh = max(primary.channels, alignedRef.channels).coerceIn(1, 2)
        val p = primary.convertChannels(targetCh)
        val r = alignedRef.convertChannels(targetCh)
        val frames = max(p.frameCount, r.frameCount)
        val out = FloatArray(frames * targetCh)

        var peakDiff = 0f
        var diffSq = 0.0
        var primarySq = 0.0
        var refSq = 0.0
        var dotProd = 0.0

        for (i in out.indices) {
            val a = if (i < p.samples.size) p.samples[i] else 0f
            val b = if (i < r.samples.size) r.samples[i] else 0f
            val d = (a - b).coerceIn(-1f, 1f)
            out[i] = d
            val absD = abs(d)
            if (absD > peakDiff) peakDiff = absD
            diffSq += d * d
            primarySq += a * a
            refSq += b * b
            dotProd += a * b
        }
        val count = out.size.coerceAtLeast(1)
        val rmsDiff = sqrt(diffSq / count).toFloat()
        val rmsPrimary = sqrt(primarySq / count).toFloat()
        val ratio = (rmsDiff / max(rmsPrimary, 1e-6f)).coerceAtLeast(1e-6f)
        val nullDepthDb = if (rmsDiff <= 1e-7f) -120f else (20f * log10(ratio)).coerceIn(-120f, 12f)
        val denom = sqrt(primarySq * refSq)
        val corr = if (denom > 1e-9) (dotProd / denom).toFloat().coerceIn(-1f, 1f) else 0f
        return DesktopAudioDiffResult(
            diffBuffer = DesktopAudioBuffer(out, primary.sampleRate, targetCh, "DIFF_${primary.name}_vs_${reference.name}", primary.sampleFormat),
            peakDiff = peakDiff,
            rmsDiff = rmsDiff,
            nullDepthDb = nullDepthDb,
            correlation = corr,
            alignedFrames = frames
        )
    }

    fun mixBuffers(
        primary: DesktopAudioBuffer,
        secondary: DesktopAudioBuffer,
        primaryGain: Float = 1.0f,
        secondaryGain: Float = 0.7f,
        offsetSec: Float = 0f,
        softClipLimiter: Boolean = true
    ): DesktopAudioBuffer {
        val alignedSec = if (secondary.sampleRate != primary.sampleRate) secondary.resample(primary.sampleRate) else secondary
        val targetCh = max(primary.channels, alignedSec.channels).coerceIn(1, 2)
        val p = primary.convertChannels(targetCh)
        val s = alignedSec.convertChannels(targetCh)
        val offsetFrames = (offsetSec.coerceAtLeast(0f) * primary.sampleRate).toInt()
        val totalFrames = max(p.frameCount, offsetFrames + s.frameCount).coerceAtLeast(1)
        val out = FloatArray(totalFrames * targetCh)

        for (f in 0 until totalFrames) {
            val secF = f - offsetFrames
            for (c in 0 until targetCh) {
                val pv = if (f < p.frameCount) p.samples[f * targetCh + c] * primaryGain else 0f
                val sv = if (secF in 0 until s.frameCount) s.samples[secF * targetCh + c] * secondaryGain else 0f
                val sum = pv + sv
                out[f * targetCh + c] = if (softClipLimiter && abs(sum) > 0.92f) {
                    tanh(sum.toDouble()).toFloat().coerceIn(-1f, 1f)
                } else {
                    sum.coerceIn(-1f, 1f)
                }
            }
        }
        return DesktopAudioBuffer(out, primary.sampleRate, targetCh, "${primary.name}_mix", primary.sampleFormat)
    }

    fun mixToMono(buffer: DesktopAudioBuffer, mode: MonoFoldMode = MonoFoldMode.AVERAGE): DesktopAudioBuffer {
        if (buffer.frameCount == 0) return buffer.copy(channels = 1)
        if (buffer.channels == 1) return buffer.copy(name = "${buffer.name}_mono")
        val frames = buffer.frameCount
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            val l = buffer.samples[f * 2]
            val r = buffer.samples[f * 2 + 1]
            out[f] = when (mode) {
                MonoFoldMode.AVERAGE -> ((l + r) * 0.5f).coerceIn(-1f, 1f)
                MonoFoldMode.EQUAL_POWER -> ((l + r) * 0.70710677f).coerceIn(-1f, 1f)
                MonoFoldMode.LEFT_ONLY -> l.coerceIn(-1f, 1f)
                MonoFoldMode.RIGHT_ONLY -> r.coerceIn(-1f, 1f)
            }
        }
        return DesktopAudioBuffer(out, buffer.sampleRate, 1, "${buffer.name}_mono", buffer.sampleFormat)
    }

    fun splitToStereo(
        buffer: DesktopAudioBuffer,
        mode: StereoSplitMode = StereoSplitMode.HAAS_DELAY,
        paramValue: Float = 12f
    ): DesktopAudioBuffer {
        if (buffer.frameCount == 0) return buffer.copy(channels = 2)
        val frames = buffer.frameCount
        val out = FloatArray(frames * 2)

        if (buffer.channels == 2 && mode == StereoSplitMode.MID_SIDE_WIDTH) {
            val width = paramValue.coerceIn(0f, 2f)
            for (f in 0 until frames) {
                val l = buffer.samples[f * 2]
                val r = buffer.samples[f * 2 + 1]
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f * width
                out[f * 2] = (mid + side).coerceIn(-1f, 1f)
                out[f * 2 + 1] = (mid - side).coerceIn(-1f, 1f)
            }
            return buffer.copy(samples = out, channels = 2, name = "${buffer.name}_stereo")
        }

        val mono = buffer.monoMixed()
        when (mode) {
            StereoSplitMode.DUAL_MONO -> for (f in 0 until frames) {
                out[f * 2] = mono[f]
                out[f * 2 + 1] = mono[f]
            }
            StereoSplitMode.HAAS_DELAY -> {
                val dFrames = ((paramValue.coerceIn(1f, 40f) / 1000f) * buffer.sampleRate).toInt().coerceIn(1, max(1, frames - 1))
                for (f in 0 until frames) {
                    out[f * 2] = mono[f]
                    out[f * 2 + 1] = if (f >= dFrames) mono[f - dFrames] else 0f
                }
            }
            StereoSplitMode.PSEUDO_STEREO_COMB -> {
                val dFrames = ((paramValue.coerceIn(2f, 30f) / 1000f) * buffer.sampleRate).toInt().coerceIn(1, 2048)
                for (f in 0 until frames) {
                    val delayed = if (f >= dFrames) mono[f - dFrames] * 0.45f else 0f
                    out[f * 2] = (mono[f] * 0.78f + delayed).coerceIn(-1f, 1f)
                    out[f * 2 + 1] = (mono[f] * 0.78f - delayed).coerceIn(-1f, 1f)
                }
            }
            StereoSplitMode.MID_SIDE_WIDTH -> {
                val dFrames = ((6f / 1000f) * buffer.sampleRate).toInt().coerceIn(1, 1024)
                val width = (paramValue / 12f).coerceIn(0.4f, 1.8f)
                for (f in 0 until frames) {
                    val sideSrc = (mono[f] - (if (f >= dFrames) mono[f - dFrames] else 0f)) * 0.5f * width
                    out[f * 2] = (mono[f] + sideSrc).coerceIn(-1f, 1f)
                    out[f * 2 + 1] = (mono[f] - sideSrc).coerceIn(-1f, 1f)
                }
            }
        }
        return DesktopAudioBuffer(out, buffer.sampleRate, 2, "${buffer.name}_stereo", buffer.sampleFormat)
    }

    fun convertSampleRate(
        buffer: DesktopAudioBuffer,
        targetSampleRate: Int,
        reinterpretHeaderOnly: Boolean = false
    ): DesktopAudioBuffer {
        val sr = targetSampleRate.coerceIn(8000, 192000)
        return if (reinterpretHeaderOnly) {
            buffer.copy(sampleRate = sr, name = "${buffer.name}_${sr}hz")
        } else {
            buffer.resample(sr).copy(name = "${buffer.name}_${sr}hz")
        }
    }

    fun convertSampleFormat(
        buffer: DesktopAudioBuffer,
        targetFormat: DesktopSampleFormat,
        dither: Boolean = false
    ): DesktopAudioBuffer {
        val quantized = DesktopSampleFormat.quantizeSamples(buffer.samples, targetFormat, dither)
        return buffer.copy(samples = quantized, sampleFormat = targetFormat, name = "${buffer.name}_${targetFormat.shortLabel.replace(" ", "")}")
    }

    private fun highestPowerOfTwo(v: Int): Int {
        var p = 1
        while (p * 2 <= v) p *= 2
        return p
    }
}
