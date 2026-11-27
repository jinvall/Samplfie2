package com.example.plugins

import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

data class LadspaBrowserEntry(
    val id: String,
    val ladspaUniqueId: Int,
    val name: String,
    val creator: String,
    val category: PluginCategory,
    val kernel: PluginDspKernel,
    val sourceUrl: String,
    val sizeLabel: String,
    val description: String,
    val params: List<PluginParamSpec>
) {
    fun toPluginDefinition(): SamplfiePluginDefinition = SamplfiePluginDefinition(
        id = id,
        name = name,
        vendor = creator,
        version = "LADSPA-$ladspaUniqueId",
        category = category,
        kernel = kernel,
        description = description,
        params = params,
        isBuiltIn = false,
        sourceFormatLabel = "LADSPA-RDF"
    )
}

/**
 * Pure-Kotlin Client-Side LADSPA ("LACSPA") & DLC Plugin Browser for Android.
 * Fetches LADSPA RDF/XML or `.smpplugin.json` DLC feeds directly over HTTP/HTTPS with ZERO Python host.
 * Enforces Google Play Creator Program Zero-DCL compliance: rejects native binary downloads (`.so`/`.dll`/`.jar`/`.dex`).
 */
object LadspaBrowserClient {

    const val DEFAULT_LADSPA_FEED_URL = "https://ladspa.org/ladspa_sdk/rdf/ladspa.rdfs"

    private val BLOCKED_BINARY_REGEX = Regex(""".*\.(so|dll|dylib|jar|dex|apk)(\?.*)?$""", RegexOption.IGNORE_CASE)

    val CURATED_COMMUNITY_LADSPA: List<LadspaBrowserEntry> = listOf(
        LadspaBrowserEntry(
            id = "ladspa_tap_tubewarmth",
            ladspaUniqueId = 2158,
            name = "TAP TubeWarmth",
            creator = "Tom Szilagyi (TAP)",
            category = PluginCategory.SATURATION,
            kernel = PluginDspKernel.TUBE_WARMTH,
            sourceUrl = "ladspa://tap-plugins/tap_tubewarmth.rdf",
            sizeLabel = "LADSPA #2158 · 18 KB",
            description = "Classic TAP vacuum-tube & tape warmth triode saturator mapped to Zero-DCL DSP.",
            params = listOf(
                PluginParamSpec("drive", "Drive", 1f, 10f, 3.8f, "x"),
                PluginParamSpec("bias", "Tape Bias", 0f, 0.8f, 0.28f, ""),
                PluginParamSpec("air", "Blend Air", -6f, 6f, 1.2f, "dB")
            )
        ),
        LadspaBrowserEntry(
            id = "ladspa_swh_dyson_comp",
            ladspaUniqueId = 1403,
            name = "SWH Dyson Compress",
            creator = "Steve Harris (SWH)",
            category = PluginCategory.DYNAMICS,
            kernel = PluginDspKernel.BRICKWALL_LIMITER,
            sourceUrl = "ladspa://plugin.org.uk/swh/dyson_compress.rdf",
            sizeLabel = "LADSPA #1403 · 16 KB",
            description = "Steve Harris lookahead dynamic compressor & peak ceiling limiter.",
            params = listOf(
                PluginParamSpec("threshold", "Peak Limit", -24f, 0f, -8f, "dB"),
                PluginParamSpec("ceiling", "Ceiling", -6f, -0.1f, -0.5f, "dB"),
                PluginParamSpec("release", "Release", 15f, 350f, 95f, "ms")
            )
        ),
        LadspaBrowserEntry(
            id = "ladspa_calf_filterclavier",
            ladspaUniqueId = 33920,
            name = "Calf Resonant SVF",
            creator = "Calf Studio Gear",
            category = PluginCategory.TONAL_EQ,
            kernel = PluginDspKernel.RESONANT_SVF,
            sourceUrl = "ladspa://calf.sourceforge.net/filter.rdf",
            sizeLabel = "LADSPA #33920 · 20 KB",
            description = "State-variable resonant LP/BP/HP synth & mix buss filter.",
            params = listOf(
                PluginParamSpec("cutoff", "Cutoff", 80f, 14000f, 2400f, "Hz"),
                PluginParamSpec("resonance", "Resonance", 0.5f, 8.0f, 2.2f, "Q"),
                PluginParamSpec("mode", "Mode (0-2)", 0f, 2f, 0f, "")
            )
        ),
        LadspaBrowserEntry(
            id = "ladspa_swh_vinyl",
            ladspaUniqueId = 1905,
            name = "SWH Vinyl Vintage",
            creator = "Steve Harris (SWH)",
            category = PluginCategory.SATURATION,
            kernel = PluginDspKernel.VINYL_LOFI,
            sourceUrl = "ladspa://plugin.org.uk/swh/jamin_vinyl.rdf",
            sizeLabel = "LADSPA #1905 · 21 KB",
            description = "Warm vinyl wear, pitch flutter, and needle dust emulation.",
            params = listOf(
                PluginParamSpec("flutter", "Warp Drift", 0f, 1f, 0.32f, ""),
                PluginParamSpec("crackle", "Surface Wear", 0f, 1f, 0.22f, ""),
                PluginParamSpec("tone", "Tone Cut", 1200f, 12000f, 4200f, "Hz")
            )
        ),
        LadspaBrowserEntry(
            id = "ladspa_zameq_exciter",
            ladspaUniqueId = 4810,
            name = "ZamHarmonic Exciter",
            creator = "Damien Zammit (ZamAudio)",
            category = PluginCategory.TONAL_EQ,
            kernel = PluginDspKernel.MULTIBAND_EXCITER,
            sourceUrl = "ladspa://zamaudio.com/zameq.rdf",
            sizeLabel = "LADSPA #4810 · 15 KB",
            description = "Split-band air harmonic exciter for bright master sheen.",
            params = listOf(
                PluginParamSpec("xover", "Crossover", 1200f, 8000f, 3200f, "Hz"),
                PluginParamSpec("harmonics", "Drive", 0f, 1f, 0.5f, ""),
                PluginParamSpec("presence", "Presence", 0f, 1f, 0.6f, "")
            )
        ),
        LadspaBrowserEntry(
            id = "ladspa_swh_deesser",
            ladspaUniqueId = 1412,
            name = "SWH Sibilance Control",
            creator = "Steve Harris (SWH)",
            category = PluginCategory.DYNAMICS,
            kernel = PluginDspKernel.DE_ESSER,
            sourceUrl = "ladspa://plugin.org.uk/swh/deesser.rdf",
            sizeLabel = "LADSPA #1412 · 14 KB",
            description = "Dynamic split-band HF vocal de-esser.",
            params = listOf(
                PluginParamSpec("freq", "Sib Freq", 3500f, 9500f, 6400f, "Hz"),
                PluginParamSpec("threshold", "Thresh", -42f, -6f, -20f, "dB"),
                PluginParamSpec("amount", "Atten", 0f, 1f, 0.65f, "")
            )
        ),
        LadspaBrowserEntry(
            id = "ladspa_matrix_imager",
            ladspaUniqueId = 1408,
            name = "SWH Matrix Spatializer",
            creator = "Steve Harris (SWH)",
            category = PluginCategory.MODULATION,
            kernel = PluginDspKernel.STEREO_IMAGER,
            sourceUrl = "ladspa://plugin.org.uk/swh/matrix_spatialiser.rdf",
            sizeLabel = "LADSPA #1408 · 12 KB",
            description = "M/S stereo widener with phase-locked mono low end.",
            params = listOf(
                PluginParamSpec("width", "Width", 0f, 2f, 1.45f, "x"),
                PluginParamSpec("monoBelow", "Mono Bass", 40f, 300f, 110f, "Hz"),
                PluginParamSpec("midTrim", "Mid Trim", -6f, 6f, 0f, "dB")
            )
        ),
        LadspaBrowserEntry(
            id = "ladspa_transient_designer",
            ladspaUniqueId = 2165,
            name = "TAP Transient Designer",
            creator = "Tom Szilagyi (TAP)",
            category = PluginCategory.DYNAMICS,
            kernel = PluginDspKernel.TRANSIENT_SHAPER,
            sourceUrl = "ladspa://tap-plugins/tap_transient.rdf",
            sizeLabel = "LADSPA #2165 · 17 KB",
            description = "Dual-envelope punch & sustain transient controller.",
            params = listOf(
                PluginParamSpec("attack", "Attack", -1f, 1f, 0.45f, ""),
                PluginParamSpec("sustain", "Sustain", -1f, 1f, 0.05f, ""),
                PluginParamSpec("snapMs", "Snap", 3f, 25f, 9f, "ms")
            )
        ),
        LadspaBrowserEntry(
            id = "ladspa_formant_vowel",
            ladspaUniqueId = 1891,
            name = "SWH Formant Filter",
            creator = "Steve Harris (SWH)",
            category = PluginCategory.MODULATION,
            kernel = PluginDspKernel.FORMANT_FILTER,
            sourceUrl = "ladspa://plugin.org.uk/swh/formant.rdf",
            sizeLabel = "LADSPA #1891 · 18 KB",
            description = "Parallel vocal formant resonator (A-E-I-O-U morph).",
            params = listOf(
                PluginParamSpec("vowel", "Vowel", 0f, 4f, 1.6f, ""),
                PluginParamSpec("q", "Q", 1.5f, 9f, 4.5f, "Q"),
                PluginParamSpec("lfoHz", "Drift", 0f, 5f, 0.4f, "Hz")
            )
        ),
        LadspaBrowserEntry(
            id = "ladspa_freeverb_ir",
            ladspaUniqueId = 2912,
            name = "Freeverb3 Impulse Hall",
            creator = "Freeverb3 Project",
            category = PluginCategory.SPATIAL,
            kernel = PluginDspKernel.CONVOLUTION_IR,
            sourceUrl = "ladspa://freeverb3.org/impulse_hall.rdf",
            sizeLabel = "LADSPA #2912 · 48 KB",
            description = "Synthetic acoustic hall impulse convolution processor.",
            params = listOf(
                PluginParamSpec("decay", "Decay", 0.2f, 2.5f, 1.35f, "s"),
                PluginParamSpec("damping", "HF Damp", 0.05f, 0.9f, 0.34f, ""),
                PluginParamSpec("predelay", "PreDelay", 0f, 60f, 20f, "ms")
            )
        )
    )

    fun isBlockedBinaryUrl(url: String): Boolean = BLOCKED_BINARY_REGEX.matches(url.trim())

    /**
     * Fetches a remote LADSPA RDF/XML feed or JSON DLC plugin catalog directly over HTTP/HTTPS.
     * Zero Python host required. Rejects native binary URLs (`.so`, `.dll`, `.jar`, `.dex`, `.apk`).
     */
    fun fetchFromUrl(rawUrl: String, timeoutMs: Int = 7000): Result<List<LadspaBrowserEntry>> = runCatching {
        val trimmed = rawUrl.trim()
        require(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            "Enter an http:// or https:// LADSPA RDF or JSON DLC URL"
        }
        require(!isBlockedBinaryUrl(trimmed)) {
            "Blocked by Zero-DCL Play Policy: native binary plugins (.so/.dll/.jar/.dex) cannot be downloaded"
        }

        val conn = (URL(trimmed).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/rdf+xml, application/xml, text/xml, application/json")
            setRequestProperty("User-Agent", "Samplfie2-LACSPA-Client/1.0")
        }
        try {
            val code = conn.responseCode
            require(code in 200..299) { "HTTP $code from $trimmed" }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var total = 0
                while (total < 1_048_576) {
                    val r = reader.read(buf)
                    if (r <= 0) break
                    sb.append(buf, 0, r)
                    total += r
                }
                sb.toString()
            }
            val parsed = parseFeedText(body, trimmed).getOrThrow()
            require(parsed.isNotEmpty()) { "Feed contained 0 declarative LADSPA/DLC plugin definitions" }
            parsed
        } finally {
            conn.disconnect()
        }
    }

    fun parseFeedText(text: String, sourceUrl: String = "remote://feed"): Result<List<LadspaBrowserEntry>> = runCatching {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Empty LADSPA/DLC payload" }
        val entries = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseJsonCatalog(trimmed, sourceUrl)
        } else if (trimmed.startsWith("<")) {
            parseLadspaRdfXml(trimmed, sourceUrl)
        } else {
            emptyList()
        }
        entries
    }

    private fun parseJsonCatalog(jsonText: String, sourceUrl: String): List<LadspaBrowserEntry> {
        val blocks = if (jsonText.trimStart().startsWith("[")) {
            Regex("""\{[^{}]*(?:\{[^{}]*}[^{}]*)*}""").findAll(jsonText).map { it.value }.toList()
        } else if (jsonText.contains("\"plugins\"")) {
            val arrayInner = Regex(""""plugins"\s*:\s*\[(.*)]""", RegexOption.DOT_MATCHES_ALL)
                .find(jsonText)?.groupValues?.getOrNull(1).orEmpty()
            Regex("""\{.*?"name".*?\}""", RegexOption.DOT_MATCHES_ALL).findAll(arrayInner).map { it.value }.toList()
        } else {
            listOf(jsonText)
        }

        return blocks.mapIndexedNotNull { idx, block ->
            if (isBlockedBinaryUrl(block)) return@mapIndexedNotNull null
            val parsed = PluginCatalogRegistry.fromJson(block).getOrNull() ?: return@mapIndexedNotNull null
            LadspaBrowserEntry(
                id = parsed.id,
                ladspaUniqueId = 9000 + idx,
                name = parsed.name,
                creator = parsed.vendor,
                category = parsed.category,
                kernel = parsed.kernel,
                sourceUrl = sourceUrl,
                sizeLabel = "DLC · ${parsed.params.size} params",
                description = parsed.description,
                params = parsed.params
            )
        }
    }

    fun parseLadspaRdfXml(xmlText: String, sourceUrl: String = "remote://ladspa.rdf"): List<LadspaBrowserEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
        val root = doc.documentElement
        val candidates = mutableListOf<Element>()
        collectElements(root, setOf("Plugin", "Description", "AudioPlugin"), candidates)

        val parsed = mutableListOf<LadspaBrowserEntry>()
        for ((index, el) in candidates.withIndex()) {
            val local = el.localName ?: el.tagName.substringAfter(':')
            val about = el.getAttribute("rdf:about").ifBlank { el.getAttribute("about") }
            val title = firstChildText(el, setOf("title", "name", "hasLabel", "label"))
                ?: continue
            if (local.equals("Description", true) && !about.contains("ladspa", true) && !hasChildTag(el, setOf("hasPort", "InputControlPort"))) {
                continue
            }
            if (isBlockedBinaryUrl(about)) continue

            val creator = firstChildText(el, setOf("creator", "author", "vendor")) ?: "LADSPA Community"
            val idStr = firstChildText(el, setOf("hasUniqueId", "uniqueId", "id"))
            val uid = idStr?.toIntOrNull() ?: (about.substringAfterLast('/').toIntOrNull() ?: (2000 + index))
            val desc = firstChildText(el, setOf("description", "comment"))
                ?: "LADSPA #$uid ported deterministically to pure-Kotlin DSP kernel."

            val portElements = mutableListOf<Element>()
            collectElements(el, setOf("InputControlPort", "ControlPort", "Port"), portElements)
            val params = portElements.mapIndexedNotNull { pIdx, portEl ->
                val pName = firstChildText(portEl, setOf("hasLabel", "name", "title", "label"))
                    ?: return@mapIndexedNotNull null
                val lo = firstChildText(portEl, setOf("hasLowerBound", "lowerBound", "min"))?.toFloatOrNull() ?: 0f
                val hi = firstChildText(portEl, setOf("hasUpperBound", "upperBound", "max"))?.toFloatOrNull()
                    ?.let { if (it <= lo) lo + 1f else it } ?: (lo + 1f)
                val defVal = firstChildText(portEl, setOf("hasDefault", "default", "defaultVal"))?.toFloatOrNull()
                    ?.coerceIn(lo, hi) ?: ((lo + hi) * 0.5f)
                val key = pName.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    .ifBlank { "p$pIdx" }
                PluginParamSpec(
                    key = key,
                    label = pName.take(18),
                    min = lo,
                    max = hi,
                    defaultVal = defVal,
                    unit = firstChildText(portEl, setOf("unit", "hasUnit")).orEmpty().take(4)
                )
            }.take(4)

            val (category, kernel, defaultParams) = mapTitleToKernel(title, desc)
            val effectiveParams = if (params.isNotEmpty()) params else defaultParams
            val safeId = "ladspa_${uid}_${title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)}"
            parsed.add(
                LadspaBrowserEntry(
                    id = safeId,
                    ladspaUniqueId = uid,
                    name = title.take(36),
                    creator = creator.take(32),
                    category = category,
                    kernel = kernel,
                    sourceUrl = if (about.isNotBlank()) about else sourceUrl,
                    sizeLabel = "LADSPA #$uid · ${effectiveParams.size}p",
                    description = desc.take(140),
                    params = effectiveParams
                )
            )
        }
        return parsed.distinctBy { it.id }
    }

    private fun mapTitleToKernel(
        title: String,
        desc: String
    ): Triple<PluginCategory, PluginDspKernel, List<PluginParamSpec>> {
        val text = "$title $desc".lowercase(Locale.US)
        return when {
            "tube" in text || "warm" in text || "sat" in text || "overdrive" in text -> Triple(
                PluginCategory.SATURATION,
                PluginDspKernel.TUBE_WARMTH,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.TUBE_WARMTH }.params
            )
            "limit" in text || "comp" in text || "dyson" in text -> Triple(
                PluginCategory.DYNAMICS,
                PluginDspKernel.BRICKWALL_LIMITER,
                PluginCatalogRegistry.BUILT_IN_PLUGINS.first { it.kernel == PluginDspKernel.BRICKWALL_LIMITER }.params
            )
            "vinyl" in text || "tape" in text || "lofi" in text -> Triple(
                PluginCategory.SATURATION,
                PluginDspKernel.VINYL_LOFI,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.VINYL_LOFI }.params
            )
            "reverb" in text || "hall" in text || "impulse" in text || "ir" in text -> Triple(
                PluginCategory.SPATIAL,
                PluginDspKernel.CONVOLUTION_IR,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.CONVOLUTION_IR }.params
            )
            "sub" in text || "bass" in text || "808" in text -> Triple(
                PluginCategory.TONAL_EQ,
                PluginDspKernel.SUB_HARMONIC,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.SUB_HARMONIC }.params
            )
            "deess" in text || "sibil" in text -> Triple(
                PluginCategory.DYNAMICS,
                PluginDspKernel.DE_ESSER,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.DE_ESSER }.params
            )
            "stereo" in text || "width" in text || "matrix" in text -> Triple(
                PluginCategory.MODULATION,
                PluginDspKernel.STEREO_IMAGER,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.STEREO_IMAGER }.params
            )
            "transient" in text || "punch" in text -> Triple(
                PluginCategory.DYNAMICS,
                PluginDspKernel.TRANSIENT_SHAPER,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.TRANSIENT_SHAPER }.params
            )
            "crush" in text || "bit" in text || "decim" in text -> Triple(
                PluginCategory.MODULATION,
                PluginDspKernel.BITCRUSHER_MOD,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.BITCRUSHER_MOD }.params
            )
            "formant" in text || "vowel" in text -> Triple(
                PluginCategory.MODULATION,
                PluginDspKernel.FORMANT_FILTER,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.FORMANT_FILTER }.params
            )
            "filter" in text || "eq" in text || "svf" in text -> Triple(
                PluginCategory.TONAL_EQ,
                PluginDspKernel.RESONANT_SVF,
                PluginCatalogRegistry.BUILT_IN_PLUGINS.first { it.kernel == PluginDspKernel.RESONANT_SVF }.params
            )
            else -> Triple(
                PluginCategory.TONAL_EQ,
                PluginDspKernel.MULTIBAND_EXCITER,
                PluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.MULTIBAND_EXCITER }.params
            )
        }
    }

    private fun collectElements(node: Node, names: Set<String>, out: MutableList<Element>) {
        if (node is Element) {
            val local = node.localName ?: node.tagName.substringAfter(':')
            if (names.any { it.equals(local, ignoreCase = true) }) {
                out.add(node)
            }
        }
        val children = node.childNodes
        for (i in 0 until children.length) {
            collectElements(children.item(i), names, out)
        }
    }

    private fun hasChildTag(el: Element, names: Set<String>): Boolean {
        val children = el.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c is Element) {
                val local = c.localName ?: c.tagName.substringAfter(':')
                if (names.any { it.equals(local, ignoreCase = true) }) return true
            }
        }
        return false
    }

    private fun firstChildText(el: Element, names: Set<String>): String? {
        val children = el.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c is Element) {
                val local = c.localName ?: c.tagName.substringAfter(':')
                if (names.any { it.equals(local, ignoreCase = true) }) {
                    val txt = c.textContent?.trim()
                    if (!txt.isNullOrBlank()) return txt
                }
            }
        }
        return null
    }
}
