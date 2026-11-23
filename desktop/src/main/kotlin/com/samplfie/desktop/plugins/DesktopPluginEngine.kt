package com.samplfie.desktop.plugins

import com.samplfie.desktop.model.DesktopAudioBuffer
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh

enum class PluginCategory(val label: String) {
    DYNAMICS("DYNAMICS"),
    TONAL_EQ("TONAL & EQ"),
    SATURATION("SATURATION"),
    SPATIAL("SPATIAL & IR"),
    MODULATION("MOD & IMAGER"),
    UTILITY("UTILITY")
}

enum class PluginDspKernel {
    TUBE_WARMTH,
    BRICKWALL_LIMITER,
    VINYL_LOFI,
    CONVOLUTION_IR,
    MULTIBAND_EXCITER,
    SUB_HARMONIC,
    DE_ESSER,
    STEREO_IMAGER,
    TRANSIENT_SHAPER,
    FORMANT_FILTER,
    RESONANT_SVF,
    BITCRUSHER_MOD
}

data class PluginParamSpec(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val defaultVal: Float,
    val unit: String = ""
)

data class DesktopPluginDefinition(
    val id: String,
    val name: String,
    val vendor: String,
    val version: String,
    val category: PluginCategory,
    val kernel: PluginDspKernel,
    val description: String,
    val params: List<PluginParamSpec>,
    val isBuiltIn: Boolean = false,
    val sourceFormatLabel: String = "SMP-DSP",
    val irSamples: FloatArray? = null,
    val irSampleRate: Int = 44100
) {
    fun createSlotInstance(): DesktopPluginSlotInstance = DesktopPluginSlotInstance(
        instanceId = UUID.randomUUID().toString().take(8),
        pluginId = id,
        pluginName = name,
        category = category,
        kernel = kernel,
        enabled = true,
        bypassed = false,
        wetMix = 1.0f,
        paramValues = params.associate { it.key to it.defaultVal }
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DesktopPluginDefinition) return false
        return id == other.id && name == other.name && kernel == other.kernel &&
            params == other.params && (irSamples?.contentEquals(other.irSamples) ?: (other.irSamples == null))
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + name.hashCode()
        r = 31 * r + kernel.hashCode()
        return r
    }
}

data class DesktopPluginSlotInstance(
    val instanceId: String,
    val pluginId: String,
    val pluginName: String,
    val category: PluginCategory,
    val kernel: PluginDspKernel,
    val enabled: Boolean = true,
    val bypassed: Boolean = false,
    val wetMix: Float = 1.0f,
    val paramValues: Map<String, Float> = emptyMap()
)

data class DesktopPluginCatalogEntry(
    val id: String,
    val name: String,
    val vendor: String,
    val version: String,
    val category: PluginCategory,
    val kernel: PluginDspKernel,
    val sizeLabel: String,
    val description: String,
    val params: List<PluginParamSpec>
) {
    fun toDefinition(): DesktopPluginDefinition = DesktopPluginDefinition(
        id = id,
        name = name,
        vendor = vendor,
        version = version,
        category = category,
        kernel = kernel,
        description = description,
        params = params,
        isBuiltIn = false,
        sourceFormatLabel = "SMP2-JSON"
    )
}

data class NativeLinuxPluginInfo(
    val name: String,
    val formatTag: String,
    val path: String
)

/**
 * Pure-Kotlin Plugin Registry, Zero-Dep .smpplugin.json Serializer/Parser, Linux LADSPA/LV2/VST scanner,
 * and 12 Real-Time DSP Execution Kernels matching Samplfie2 Android.
 */
object DesktopPluginCatalogRegistry {

    val BUILT_IN_PLUGINS: List<DesktopPluginDefinition> = listOf(
        DesktopPluginDefinition(
            id = "builtin_resonant_svf",
            name = "Analog SVF Sculpt EQ",
            vendor = "Samplfie2 Labs",
            version = "1.2",
            category = PluginCategory.TONAL_EQ,
            kernel = PluginDspKernel.RESONANT_SVF,
            description = "Zero-delay state-variable resonant filter (LP/BP/HP).",
            params = listOf(
                PluginParamSpec("cutoff", "Cutoff", 80f, 14000f, 3200f, "Hz"),
                PluginParamSpec("resonance", "Resonance", 0.5f, 8.0f, 1.4f, "Q"),
                PluginParamSpec("mode", "Mode (0=LP,1=BP,2=HP)", 0f, 2f, 0f, "")
            ),
            isBuiltIn = true
        ),
        DesktopPluginDefinition(
            id = "builtin_brickwall_limiter",
            name = "Brickwall Master Limiter",
            vendor = "Samplfie2 Labs",
            version = "2.0",
            category = PluginCategory.DYNAMICS,
            kernel = PluginDspKernel.BRICKWALL_LIMITER,
            description = "Transparent lookahead peak ceiling & RMS punch maximizer.",
            params = listOf(
                PluginParamSpec("threshold", "Threshold", -18f, 0f, -6f, "dB"),
                PluginParamSpec("ceiling", "Ceiling", -6f, -0.1f, -0.3f, "dB"),
                PluginParamSpec("release", "Release", 15f, 300f, 80f, "ms")
            ),
            isBuiltIn = true
        )
    )

    val INSTALLABLE_CATALOG: List<DesktopPluginCatalogEntry> = listOf(
        DesktopPluginCatalogEntry(
            id = "cat_tube_warmth", name = "Tube Warmth Triode Pre", vendor = "AnalogForge DSP", version = "1.4.0",
            category = PluginCategory.SATURATION, kernel = PluginDspKernel.TUBE_WARMTH, sizeLabel = "18 KB DSP Graph",
            description = "Even-order vacuum triode harmonic saturation with input bias & silk high-shelf.",
            params = listOf(
                PluginParamSpec("drive", "Tube Drive", 1f, 10f, 3.2f, "x"),
                PluginParamSpec("bias", "Asym Bias", 0f, 0.8f, 0.25f, ""),
                PluginParamSpec("air", "High Silk", -6f, 6f, 1.5f, "dB")
            )
        ),
        DesktopPluginCatalogEntry(
            id = "cat_vinyl_lofi", name = "Vinyl Lo-Fi Tape Deck", vendor = "CrateDiggers Audio", version = "1.1.2",
            category = PluginCategory.SATURATION, kernel = PluginDspKernel.VINYL_LOFI, sizeLabel = "22 KB DSP Graph",
            description = "Authentic wow/flutter pitch drift, tape hiss floor, and 3.5kHz warm head roll-off.",
            params = listOf(
                PluginParamSpec("flutter", "Wow/Flutter", 0f, 1f, 0.35f, ""),
                PluginParamSpec("crackle", "Vinyl Dust", 0f, 1f, 0.18f, ""),
                PluginParamSpec("tone", "Head LPF", 1200f, 12000f, 4600f, "Hz")
            )
        ),
        DesktopPluginCatalogEntry(
            id = "cat_cathedral_ir", name = "Cathedral Convolution IR", vendor = "ImpulseSpace", version = "2.0.1",
            category = PluginCategory.SPATIAL, kernel = PluginDspKernel.CONVOLUTION_IR, sizeLabel = "64 KB IR Kernel",
            description = "Real FIR partitioned convolution engine with acoustic kernel or custom WAV IR.",
            params = listOf(
                PluginParamSpec("decay", "IR Decay", 0.2f, 2.5f, 1.2f, "s"),
                PluginParamSpec("damping", "HF Damp", 0.05f, 0.9f, 0.38f, ""),
                PluginParamSpec("predelay", "Pre-Delay", 0f, 60f, 18f, "ms")
            )
        ),
        DesktopPluginCatalogEntry(
            id = "cat_harmonic_exciter", name = "Multiband Harmonic Exciter", vendor = "HarmonicLab", version = "1.3.0",
            category = PluginCategory.TONAL_EQ, kernel = PluginDspKernel.MULTIBAND_EXCITER, sizeLabel = "14 KB DSP Graph",
            description = "Splits high-band overtones and synthesizes crisp octave shimmer above crossover.",
            params = listOf(
                PluginParamSpec("xover", "Crossover", 1200f, 8000f, 2800f, "Hz"),
                PluginParamSpec("harmonics", "Drive", 0f, 1f, 0.45f, ""),
                PluginParamSpec("presence", "Presence", 0f, 1f, 0.55f, "")
            )
        ),
        DesktopPluginCatalogEntry(
            id = "cat_sub_punch", name = "Sub-Harmonic 808 Punch", vendor = "LowEnd Audio", version = "1.0.5",
            category = PluginCategory.TONAL_EQ, kernel = PluginDspKernel.SUB_HARMONIC, sizeLabel = "16 KB DSP Graph",
            description = "Octave-down frequency divider & resonant lowpass bass weight generator.",
            params = listOf(
                PluginParamSpec("cutoff", "Sub Cutoff", 45f, 160f, 95f, "Hz"),
                PluginParamSpec("subMix", "Sub Level", 0f, 1f, 0.38f, ""),
                PluginParamSpec("tightness", "Tightness", 0.1f, 1f, 0.65f, "")
            )
        ),
        DesktopPluginCatalogEntry(
            id = "cat_broadcast_deesser", name = "Broadcast Vocal De-Esser", vendor = "VoxCraft Pro", version = "1.5.0",
            category = PluginCategory.DYNAMICS, kernel = PluginDspKernel.DE_ESSER, sizeLabel = "15 KB DSP Graph",
            description = "Dynamic split-band sibilance suppressor targeting harsh 4kHz–9kHz consonants.",
            params = listOf(
                PluginParamSpec("freq", "Sibilance Hz", 3500f, 9500f, 6200f, "Hz"),
                PluginParamSpec("threshold", "Threshold", -42f, -6f, -22f, "dB"),
                PluginParamSpec("amount", "Reduction", 0f, 1f, 0.6f, "")
            )
        ),
        DesktopPluginCatalogEntry(
            id = "cat_stereo_imager", name = "Mid-Side Stereo Imager", vendor = "SpatialWorks", version = "1.2.4",
            category = PluginCategory.MODULATION, kernel = PluginDspKernel.STEREO_IMAGER, sizeLabel = "12 KB DSP Graph",
            description = "Safe Mid/Side matrix with low-end mono focus keeping sub-bass phase locked.",
            params = listOf(
                PluginParamSpec("width", "Stereo Width", 0f, 2f, 1.35f, "x"),
                PluginParamSpec("monoBelow", "Mono Bass", 40f, 300f, 120f, "Hz"),
                PluginParamSpec("midTrim", "Mid Trim", -6f, 6f, 0f, "dB")
            )
        ),
        DesktopPluginCatalogEntry(
            id = "cat_transient_shaper", name = "Dual Transient Shaper", vendor = "ImpactDSP", version = "1.1.0",
            category = PluginCategory.DYNAMICS, kernel = PluginDspKernel.TRANSIENT_SHAPER, sizeLabel = "19 KB DSP Graph",
            description = "Differential fast/slow envelope transient enhancer for drums, plucks, and stems.",
            params = listOf(
                PluginParamSpec("attack", "Attack", -1f, 1f, 0.4f, ""),
                PluginParamSpec("sustain", "Sustain", -1f, 1f, 0.1f, ""),
                PluginParamSpec("snapMs", "Snap Time", 3f, 25f, 10f, "ms")
            )
        ),
        DesktopPluginCatalogEntry(
            id = "cat_bitcrusher", name = "Cyber Bitcrusher & Decimator", vendor = "GlitchMatrix", version = "1.0.2",
            category = PluginCategory.MODULATION, kernel = PluginDspKernel.BITCRUSHER_MOD, sizeLabel = "11 KB DSP Graph",
            description = "Step-depth quantization & sample-and-hold sample rate decimator.",
            params = listOf(
                PluginParamSpec("bits", "Bit Depth", 3f, 16f, 8f, "bit"),
                PluginParamSpec("downsample", "Rate Div", 1f, 24f, 4f, "x"),
                PluginParamSpec("ring", "Ring Mod", 0f, 1f, 0.12f, "")
            )
        ),
        DesktopPluginCatalogEntry(
            id = "cat_formant_vowel", name = "Formant Vocal Morph", vendor = "SynthLab", version = "1.0.0",
            category = PluginCategory.MODULATION, kernel = PluginDspKernel.FORMANT_FILTER, sizeLabel = "17 KB DSP Graph",
            description = "Dual-bandpass parallel vowel formant resonator morphing A-E-I-O-U.",
            params = listOf(
                PluginParamSpec("vowel", "Vowel Morph", 0f, 4f, 1.5f, ""),
                PluginParamSpec("q", "Formant Q", 1.5f, 9f, 4.2f, "Q"),
                PluginParamSpec("lfoHz", "Auto Drift", 0f, 5f, 0.35f, "Hz")
            )
        )
    )

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    fun toJson(plugin: DesktopPluginDefinition): String = buildString {
        appendLine("{")
        appendLine("""  "format": "samplfie2-plugin-v1",""")
        appendLine("""  "id": "${esc(plugin.id)}",""")
        appendLine("""  "name": "${esc(plugin.name)}",""")
        appendLine("""  "vendor": "${esc(plugin.vendor)}",""")
        appendLine("""  "version": "${esc(plugin.version)}",""")
        appendLine("""  "category": "${plugin.category.name}",""")
        appendLine("""  "kernel": "${plugin.kernel.name}",""")
        appendLine("""  "description": "${esc(plugin.description)}",""")
        appendLine("""  "sourceFormatLabel": "${esc(plugin.sourceFormatLabel)}",""")
        appendLine("""  "params": [""")
        plugin.params.forEachIndexed { idx, p ->
            val comma = if (idx < plugin.params.lastIndex) "," else ""
            appendLine("""    {"key": "${esc(p.key)}", "label": "${esc(p.label)}", "min": ${p.min}, "max": ${p.max}, "defaultVal": ${p.defaultVal}, "unit": "${esc(p.unit)}"}$comma""")
        }
        appendLine("  ]")
        append("}")
    }

    fun fromJson(jsonText: String): Result<DesktopPluginDefinition> = runCatching {
        fun extractStr(src: String, key: String, fallback: String): String {
            val regex = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            return regex.find(src)?.groupValues?.getOrNull(1)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: fallback
        }
        fun extractNum(src: String, key: String, fallback: Float): Float {
            val regex = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""")
            return regex.find(src)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: fallback
        }

        val id = extractStr(jsonText, "id", "").ifBlank { "custom_${UUID.randomUUID().toString().take(6)}" }
        val name = extractStr(jsonText, "name", "").ifBlank { "Custom DSP Plugin" }
        val vendor = extractStr(jsonText, "vendor", "User Custom")
        val version = extractStr(jsonText, "version", "1.0")
        val catStr = extractStr(jsonText, "category", PluginCategory.UTILITY.name)
        val kernelStr = extractStr(jsonText, "kernel", PluginDspKernel.TUBE_WARMTH.name)
        val desc = extractStr(jsonText, "description", "Custom declarative DSP plugin bundle")
        val srcFmt = extractStr(jsonText, "sourceFormatLabel", "IMPORTED-JSON")

        val category = PluginCategory.entries.firstOrNull { it.name.equals(catStr, true) } ?: PluginCategory.UTILITY
        val kernel = PluginDspKernel.entries.firstOrNull { it.name.equals(kernelStr, true) } ?: PluginDspKernel.TUBE_WARMTH

        val paramsBlock = Regex(""""params"\s*:\s*\[(.*)]""", RegexOption.DOT_MATCHES_ALL)
            .find(jsonText)?.groupValues?.getOrNull(1).orEmpty()
        val objRegex = Regex("""\{([^}]*)}""")
        val params = objRegex.findAll(paramsBlock).mapNotNull { m ->
            val obj = m.groupValues[1]
            val k = extractStr(obj, "key", "").trim()
            if (k.isBlank()) null else PluginParamSpec(
                key = k,
                label = extractStr(obj, "label", k),
                min = extractNum(obj, "min", 0f),
                max = extractNum(obj, "max", 1f),
                defaultVal = extractNum(obj, "defaultVal", 0.5f),
                unit = extractStr(obj, "unit", "")
            )
        }.toList()

        DesktopPluginDefinition(
            id = id,
            name = name,
            vendor = vendor,
            version = version,
            category = category,
            kernel = kernel,
            description = desc,
            params = params,
            isBuiltIn = false,
            sourceFormatLabel = srcFmt
        )
    }

    fun createIrPluginFromBuffer(irBuffer: DesktopAudioBuffer, name: String): DesktopPluginDefinition {
        val mono = irBuffer.monoMixed()
        val maxSamples = min(mono.size, irBuffer.sampleRate * 2)
        val cleanName = name.ifBlank { irBuffer.name }.take(32)
        val id = "ir_${cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_${System.currentTimeMillis() % 10000}"
        return DesktopPluginDefinition(
            id = id,
            name = "$cleanName (IR)",
            vendor = "User Impulse",
            version = "1.0",
            category = PluginCategory.SPATIAL,
            kernel = PluginDspKernel.CONVOLUTION_IR,
            description = "Custom .WAV Impulse Response (${"%.2f".format(maxSamples.toFloat() / irBuffer.sampleRate)}s @ ${irBuffer.sampleRate}Hz).",
            params = listOf(
                PluginParamSpec("decay", "IR Trim", 0.1f, 2.0f, 1.0f, "s"),
                PluginParamSpec("damping", "HF Damp", 0f, 0.9f, 0.25f, ""),
                PluginParamSpec("predelay", "Pre-Delay", 0f, 50f, 8f, "ms")
            ),
            isBuiltIn = false,
            sourceFormatLabel = "WAV-IR",
            irSamples = mono.copyOf(maxSamples),
            irSampleRate = irBuffer.sampleRate
        )
    }
}

class DesktopPluginRepository(
    val pluginsDir: File = File(File(System.getProperty("user.home", "."), ".config/samplfie2"), "plugins")
) {
    init {
        pluginsDir.mkdirs()
    }

    fun loadAllPlugins(): List<DesktopPluginDefinition> {
        val custom = pluginsDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".smpplugin.json", true) || f.name.endsWith(".json", true))
        }?.mapNotNull { file ->
            runCatching { DesktopPluginCatalogRegistry.fromJson(file.readText()).getOrThrow() }.getOrNull()
        }?.sortedBy { it.name } ?: emptyList()

        return (DesktopPluginCatalogRegistry.BUILT_IN_PLUGINS + custom).distinctBy { it.id }
    }

    fun installPlugin(plugin: DesktopPluginDefinition): DesktopPluginDefinition {
        val safeFile = File(pluginsDir, "${sanitizeId(plugin.id)}.smpplugin.json")
        safeFile.writeText(DesktopPluginCatalogRegistry.toJson(plugin))
        return plugin
    }

    fun uninstallPlugin(pluginId: String): Boolean {
        val safeFile = File(pluginsDir, "${sanitizeId(pluginId)}.smpplugin.json")
        return if (safeFile.exists()) safeFile.delete() else false
    }

    fun scanLinuxNativePlugins(extraDirs: List<File> = emptyList()): List<NativeLinuxPluginInfo> {
        val home = System.getProperty("user.home", ".")
        val searchPaths = (listOf(
            File("/usr/lib/lv2") to "LV2",
            File("/usr/lib64/lv2") to "LV2",
            File("/usr/local/lib/lv2") to "LV2",
            File(home, ".lv2") to "LV2",
            File("/usr/lib/ladspa") to "LADSPA",
            File("/usr/lib64/ladspa") to "LADSPA",
            File("/usr/local/lib/ladspa") to "LADSPA",
            File(home, ".ladspa") to "LADSPA",
            File("/usr/lib/vst") to "VST2",
            File("/usr/lib/vst3") to "VST3",
            File(home, ".vst") to "VST2",
            File(home, ".vst3") to "VST3"
        ) + extraDirs.map { it to "CUSTOM" })

        val results = mutableListOf<NativeLinuxPluginInfo>()
        for ((dir, tag) in searchPaths) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()?.take(64)?.forEach { entry ->
                val ext = entry.extension.lowercase()
                if (entry.isDirectory && (ext == "lv2" || ext == "vst3")) {
                    results.add(NativeLinuxPluginInfo(entry.nameWithoutExtension, ext.uppercase(), entry.absolutePath))
                } else if (entry.isFile && (ext == "so" || ext == "lv2" || ext == "vst3")) {
                    results.add(NativeLinuxPluginInfo(entry.nameWithoutExtension, tag, entry.absolutePath))
                }
            }
        }
        return results.distinctBy { it.path }.sortedBy { it.name }.take(80)
    }

    private fun sanitizeId(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "plugin" }
}

object DesktopPluginHostEngine {

    fun processChain(
        source: DesktopAudioBuffer,
        chain: List<DesktopPluginSlotInstance>,
        definitions: Map<String, DesktopPluginDefinition> = emptyMap()
    ): DesktopAudioBuffer {
        if (source.frameCount == 0 || chain.isEmpty()) return source
        val activeSlots = chain.filter { it.enabled && !it.bypassed }
        if (activeSlots.isEmpty()) return source

        var working = source.samples
        val sr = source.sampleRate.coerceAtLeast(8000)
        val ch = source.channels.coerceIn(1, 2)

        for (slot in activeSlots) {
            val wet = processSlot(working, sr, ch, slot, definitions[slot.pluginId])
            val mix = slot.wetMix.coerceIn(0f, 1f)
            working = if (mix < 0.995f && wet.size == working.size) {
                val dry = 1f - mix
                FloatArray(working.size) { i -> (working[i] * dry + wet[i] * mix).coerceIn(-1f, 1f) }
            } else {
                wet
            }
        }
        return source.copy(samples = working)
    }

    private fun processSlot(
        samples: FloatArray,
        sr: Int,
        ch: Int,
        slot: DesktopPluginSlotInstance,
        def: DesktopPluginDefinition?
    ): FloatArray = when (slot.kernel) {
        PluginDspKernel.TUBE_WARMTH -> runTubeWarmth(samples, sr, ch, slot.paramValues)
        PluginDspKernel.BRICKWALL_LIMITER -> runBrickwallLimiter(samples, sr, ch, slot.paramValues)
        PluginDspKernel.VINYL_LOFI -> runVinylLofi(samples, sr, ch, slot.paramValues)
        PluginDspKernel.CONVOLUTION_IR -> runConvolutionIr(samples, sr, ch, slot.paramValues, def)
        PluginDspKernel.MULTIBAND_EXCITER -> runMultibandExciter(samples, sr, ch, slot.paramValues)
        PluginDspKernel.SUB_HARMONIC -> runSubHarmonic(samples, sr, ch, slot.paramValues)
        PluginDspKernel.DE_ESSER -> runDeEsser(samples, sr, ch, slot.paramValues)
        PluginDspKernel.STEREO_IMAGER -> runStereoImager(samples, sr, ch, slot.paramValues)
        PluginDspKernel.TRANSIENT_SHAPER -> runTransientShaper(samples, sr, ch, slot.paramValues)
        PluginDspKernel.FORMANT_FILTER -> runFormantFilter(samples, sr, ch, slot.paramValues)
        PluginDspKernel.RESONANT_SVF -> runResonantSvf(samples, sr, ch, slot.paramValues)
        PluginDspKernel.BITCRUSHER_MOD -> runBitcrusher(samples, sr, ch, slot.paramValues)
    }

    private fun runTubeWarmth(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val drive = (p["drive"] ?: 3.2f).coerceIn(1f, 12f)
        val bias = (p["bias"] ?: 0.25f).coerceIn(0f, 0.9f) * 0.42f
        val airGain = 10f.pow((p["air"] ?: 1.5f).coerceIn(-6f, 6f) / 20f) - 1f
        val norm = 1f / max(0.35f, tanh(drive * 0.65f))
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * 4500f / sr)).coerceIn(0.01f, 1f)
        val lp = FloatArray(ch)
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                val idx = f * ch + c
                val sat = (tanh(samples[idx] * drive + bias) - tanh(bias)) * norm
                lp[c] += lpAlpha * (sat - lp[c])
                out[idx] = (sat + (sat - lp[c]) * airGain * 0.5f).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun runBrickwallLimiter(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val threshDb = (p["threshold"] ?: -6f).coerceIn(-24f, 0f)
        val ceilingDb = (p["ceiling"] ?: -0.3f).coerceIn(-6f, -0.05f)
        val relMs = (p["release"] ?: 80f).coerceIn(10f, 400f)
        val boostLin = 10f.pow(-threshDb * 0.45f / 20f)
        val ceilLin = 10f.pow(ceilingDb / 20f)
        val relCoeff = exp(-1f / ((relMs / 1000f) * sr))
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        var env = 1f
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until ch) peak = max(peak, abs(samples[f * ch + c] * boostLin))
            val need = if (peak > ceilLin) ceilLin / peak else 1f
            env = if (need < env) need else env * relCoeff + need * (1f - relCoeff)
            for (c in 0 until ch) out[f * ch + c] = (samples[f * ch + c] * boostLin * env).coerceIn(-ceilLin, ceilLin)
        }
        return out
    }

    private fun runVinylLofi(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val flutter = (p["flutter"] ?: 0.35f).coerceIn(0f, 1f)
        val crackle = (p["crackle"] ?: 0.18f).coerceIn(0f, 1f)
        val cutoff = (p["tone"] ?: 4600f).coerceIn(800f, sr * 0.45f)
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * cutoff / sr)).coerceIn(0.01f, 1f)
        val lp = FloatArray(ch)
        val maxDelay = (0.006f * sr).toInt().coerceAtLeast(8)
        var rng = 987654321L
        for (f in 0 until frames) {
            val lfo = 0.5f + 0.5f * sin(2.0 * PI * 1.8 * f / sr).toFloat()
            val delayF = (lfo * flutter * maxDelay).toInt()
            rng = (rng * 1103515245L + 12345L) and 0x7FFFFFFFL
            val noise = ((rng % 2000).toFloat() / 1000f - 1f)
            val dust = if (rng % 997L < (crackle * 4L).toLong()) noise * 0.22f * crackle else noise * 0.008f * crackle
            for (c in 0 until ch) {
                val srcF = (f - delayF).coerceIn(0, frames - 1)
                lp[c] += lpAlpha * (samples[srcF * ch + c] + dust - lp[c])
                out[f * ch + c] = tanh((lp[c] * 1.15f).toDouble()).toFloat().coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun runConvolutionIr(
        samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>, def: DesktopPluginDefinition?
    ): FloatArray {
        val decay = (p["decay"] ?: 1.0f).coerceIn(0.15f, 2.2f)
        val damp = (p["damping"] ?: 0.35f).coerceIn(0f, 0.9f)
        val preFrames = (((p["predelay"] ?: 14f).coerceIn(0f, 60f) / 1000f) * sr).toInt()
        val rawIr = def?.irSamples
        val irTapCount = 24
        val tapOffsets = IntArray(irTapCount)
        val tapGains = FloatArray(irTapCount)
        if (rawIr != null && rawIr.size >= 64) {
            val irLimit = min(rawIr.size, (sr * decay).toInt().coerceAtLeast(128))
            val stride = max(1, irLimit / irTapCount)
            for (t in 0 until irTapCount) {
                val idx = (t * stride).coerceIn(0, rawIr.size - 1)
                tapOffsets[t] = preFrames + idx
                var energy = 0f
                for (k in idx until min(rawIr.size, idx + stride)) energy += rawIr[k]
                tapGains[t] = (energy / stride.toFloat()) * 1.8f
            }
        } else {
            val irLenFrames = (sr * decay * 0.45f).toInt().coerceIn(256, sr)
            for (t in 0 until irTapCount) {
                val normT = (t + 1).toFloat() / irTapCount
                tapOffsets[t] = preFrames + (normT * normT * irLenFrames).toInt()
                tapGains[t] = (if (t % 2 == 0) 1f else -1f) * exp(-normT * (2.2f + damp * 3.0f)) * 0.28f
            }
        }
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                var irSum = 0f
                for (t in 0 until irTapCount) {
                    val srcF = f - tapOffsets[t] - (c * 17)
                    if (srcF >= 0) irSum += samples[srcF * ch + c] * tapGains[t]
                }
                out[f * ch + c] = (samples[f * ch + c] * 0.65f + irSum * 0.55f).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun runMultibandExciter(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val xover = (p["xover"] ?: 2800f).coerceIn(600f, sr * 0.42f)
        val drive = (p["harmonics"] ?: 0.45f).coerceIn(0f, 1f)
        val pres = (p["presence"] ?: 0.55f).coerceIn(0f, 1f)
        val alpha = (1f - exp(-2f * PI.toFloat() * xover / sr)).coerceIn(0.01f, 1f)
        val lp = FloatArray(ch)
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                val x = samples[f * ch + c]
                lp[c] += alpha * (x - lp[c])
                val excited = tanh(((x - lp[c]) * (1f + drive * 6f)).toDouble()).toFloat() * pres * 0.65f
                out[f * ch + c] = (x + excited).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun runSubHarmonic(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val cutoff = (p["cutoff"] ?: 95f).coerceIn(35f, 220f)
        val subLevel = (p["subMix"] ?: 0.38f).coerceIn(0f, 1f)
        val tight = (p["tightness"] ?: 0.65f).coerceIn(0.1f, 1f)
        val lpAlpha = (1f - exp(-2f * PI.toFloat() * cutoff / sr)).coerceIn(0.002f, 0.5f)
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        var lpState = 0f; var flip = 1f; var prevSign = true; var env = 0f
        for (f in 0 until frames) {
            var mono = 0f
            for (c in 0 until ch) mono += samples[f * ch + c]
            mono /= ch
            lpState += lpAlpha * (mono - lpState)
            val sign = lpState >= 0f
            if (sign != prevSign && abs(lpState) > 0.01f) { flip = -flip; prevSign = sign }
            env = env * (0.985f - tight * 0.015f) + abs(lpState) * (0.015f + tight * 0.015f)
            val sub = flip * env * subLevel * 1.4f
            for (c in 0 until ch) out[f * ch + c] = (samples[f * ch + c] + sub).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun runDeEsser(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val sibHz = (p["freq"] ?: 6200f).coerceIn(2500f, sr * 0.45f)
        val threshLin = 10f.pow((p["threshold"] ?: -22f).coerceIn(-45f, -3f) / 20f)
        val amount = (p["amount"] ?: 0.6f).coerceIn(0f, 1f)
        val alpha = (1f - exp(-2f * PI.toFloat() * sibHz / sr)).coerceIn(0.05f, 1f)
        val lp = FloatArray(ch)
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        var env = 0f
        for (f in 0 until frames) {
            var hiPeak = 0f
            for (c in 0 until ch) {
                val x = samples[f * ch + c]
                lp[c] += alpha * (x - lp[c])
                hiPeak = max(hiPeak, abs(x - lp[c]))
            }
            env = env * 0.94f + hiPeak * 0.06f
            val gain = if (env > threshLin) (1f - amount * ((env - threshLin) / max(env, 1e-4f))).coerceIn(0.15f, 1f) else 1f
            for (c in 0 until ch) {
                val x = samples[f * ch + c]
                out[f * ch + c] = (lp[c] + (x - lp[c]) * gain).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun runStereoImager(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        if (ch < 2) return samples
        val width = (p["width"] ?: 1.35f).coerceIn(0f, 2.2f)
        val monoBelow = (p["monoBelow"] ?: 120f).coerceIn(30f, 400f)
        val midTrimLin = 10f.pow((p["midTrim"] ?: 0f).coerceIn(-6f, 6f) / 20f)
        val alpha = (1f - exp(-2f * PI.toFloat() * monoBelow / sr)).coerceIn(0.002f, 0.5f)
        var lowSide = 0f
        val frames = samples.size / 2
        val out = FloatArray(samples.size)
        for (f in 0 until frames) {
            val l = samples[f * 2]; val r = samples[f * 2 + 1]
            val mid = (l + r) * 0.5f * midTrimLin
            val side = (l - r) * 0.5f
            lowSide += alpha * (side - lowSide)
            val hiSide = (side - lowSide) * width
            out[f * 2] = (mid + hiSide).coerceIn(-1f, 1f)
            out[f * 2 + 1] = (mid - hiSide).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun runTransientShaper(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val atk = (p["attack"] ?: 0.4f).coerceIn(-1f, 1f)
        val sus = (p["sustain"] ?: 0.1f).coerceIn(-1f, 1f)
        val snapMs = (p["snapMs"] ?: 10f).coerceIn(2f, 35f)
        val fastCoef = exp(-1f / (0.0015f * sr))
        val slowCoef = exp(-1f / ((snapMs / 1000f) * sr))
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        var fastEnv = 0f; var slowEnv = 0f
        for (f in 0 until frames) {
            var peak = 0f
            for (c in 0 until ch) peak = max(peak, abs(samples[f * ch + c]))
            fastEnv = fastCoef * fastEnv + (1f - fastCoef) * peak
            slowEnv = slowCoef * slowEnv + (1f - slowCoef) * peak
            val gain = if (fastEnv > slowEnv) (1f + atk * (fastEnv - slowEnv) * 4.5f).coerceIn(0.25f, 2.5f)
            else (1f + sus * slowEnv * 1.2f).coerceIn(0.25f, 2.0f)
            for (c in 0 until ch) out[f * ch + c] = (samples[f * ch + c] * gain).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun runFormantFilter(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val vowel = (p["vowel"] ?: 1.5f).coerceIn(0f, 4f)
        val qInv = 1f / (p["q"] ?: 4.2f).coerceIn(1.2f, 10f)
        val lfoHz = (p["lfoHz"] ?: 0.35f).coerceIn(0f, 6f)
        val f1Table = floatArrayOf(730f, 530f, 270f, 570f, 300f)
        val f2Table = floatArrayOf(1090f, 1840f, 2290f, 840f, 870f)
        val lp1 = FloatArray(ch); val bp1 = FloatArray(ch)
        val lp2 = FloatArray(ch); val bp2 = FloatArray(ch)
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        for (f in 0 until frames) {
            val drift = if (lfoHz > 0.01f) 0.5f * (1f + sin(2.0 * PI * lfoHz * f / sr).toFloat()) * 0.4f else 0f
            val v = (vowel + drift).coerceIn(0f, 4f)
            val idx0 = v.toInt().coerceIn(0, 3)
            val frac = v - idx0
            val c1 = (2f * sin(PI.toFloat() * (f1Table[idx0] + (f1Table[idx0 + 1] - f1Table[idx0]) * frac) / sr)).coerceIn(0.01f, 0.9f)
            val c2 = (2f * sin(PI.toFloat() * (f2Table[idx0] + (f2Table[idx0 + 1] - f2Table[idx0]) * frac) / sr)).coerceIn(0.01f, 0.9f)
            for (c in 0 until ch) {
                val x = samples[f * ch + c]
                lp1[c] += c1 * bp1[c]; val hp1 = x - lp1[c] - qInv * bp1[c]; bp1[c] += c1 * hp1
                lp2[c] += c2 * bp2[c]; val hp2 = x - lp2[c] - qInv * bp2[c]; bp2[c] += c2 * hp2
                out[f * ch + c] = (x * 0.25f + (bp1[c] + bp2[c] * 0.7f) * 0.75f).coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun runResonantSvf(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val cutoff = (p["cutoff"] ?: 3200f).coerceIn(60f, sr * 0.44f)
        val qInv = 1f / (p["resonance"] ?: 1.4f).coerceIn(0.5f, 10f)
        val mode = (p["mode"] ?: 0f).toInt().coerceIn(0, 2)
        val fCoeff = (2f * sin(PI.toFloat() * cutoff / sr)).coerceIn(0.005f, 0.9f)
        val lp = FloatArray(ch); val bp = FloatArray(ch)
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                val x = samples[f * ch + c]
                lp[c] += fCoeff * bp[c]
                val hp = x - lp[c] - qInv * bp[c]
                bp[c] += fCoeff * hp
                out[f * ch + c] = when (mode) { 1 -> bp[c]; 2 -> hp; else -> lp[c] }.coerceIn(-1f, 1f)
            }
        }
        return out
    }

    private fun runBitcrusher(samples: FloatArray, sr: Int, ch: Int, p: Map<String, Float>): FloatArray {
        val bits = (p["bits"] ?: 8f).coerceIn(2f, 16f)
        val div = (p["downsample"] ?: 4f).toInt().coerceIn(1, 32)
        val ringAmt = (p["ring"] ?: 0.12f).coerceIn(0f, 1f)
        val levels = 2f.pow(bits) * 0.5f
        val held = FloatArray(ch)
        val frames = samples.size / ch
        val out = FloatArray(samples.size)
        for (f in 0 until frames) {
            val ringOsc = if (ringAmt > 0.01f) sin(2.0 * PI * 380.0 * f / sr).toFloat() else 1f
            if (f % div == 0) {
                for (c in 0 until ch) held[c] = round(samples[f * ch + c] * levels) / levels
            }
            for (c in 0 until ch) out[f * ch + c] = (held[c] * ((1f - ringAmt) + ringAmt * ringOsc)).coerceIn(-1f, 1f)
        }
        return out
    }
}
