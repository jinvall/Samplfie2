package com.samplfie.desktop.plugins

import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

data class DesktopLadspaBrowserEntry(
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
    fun toPluginDefinition(): DesktopPluginDefinition = DesktopPluginDefinition(
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
 * Pure-Kotlin Client-Side LADSPA ("LACSPA") & DLC Plugin Browser for Samplfie2 Desktop.
 * Fetches LADSPA RDF/XML or `.smpplugin.json` DLC feeds directly over HTTP/HTTPS with ZERO Python host.
 */
object DesktopLadspaBrowserClient {

    const val DEFAULT_LADSPA_FEED_URL = "https://ladspa.org/ladspa_sdk/rdf/ladspa.rdfs"
    private val BLOCKED_BINARY_REGEX = Regex(""".*\.(so|dll|dylib|jar|dex|apk)(\?.*)?$""", RegexOption.IGNORE_CASE)

    val CURATED_COMMUNITY_LADSPA: List<DesktopLadspaBrowserEntry> = listOf(
        DesktopLadspaBrowserEntry(
            id = "ladspa_tap_tubewarmth", ladspaUniqueId = 2158,
            name = "TAP TubeWarmth", creator = "Tom Szilagyi (TAP)",
            category = PluginCategory.SATURATION, kernel = PluginDspKernel.TUBE_WARMTH,
            sourceUrl = "ladspa://tap-plugins/tap_tubewarmth.rdf", sizeLabel = "LADSPA #2158 · 18 KB",
            description = "Classic TAP vacuum-tube & tape warmth triode saturator.",
            params = listOf(
                PluginParamSpec("drive", "Drive", 1f, 10f, 3.8f, "x"),
                PluginParamSpec("bias", "Tape Bias", 0f, 0.8f, 0.28f, ""),
                PluginParamSpec("air", "Blend Air", -6f, 6f, 1.2f, "dB")
            )
        ),
        DesktopLadspaBrowserEntry(
            id = "ladspa_swh_dyson_comp", ladspaUniqueId = 1403,
            name = "SWH Dyson Compress", creator = "Steve Harris (SWH)",
            category = PluginCategory.DYNAMICS, kernel = PluginDspKernel.BRICKWALL_LIMITER,
            sourceUrl = "ladspa://plugin.org.uk/swh/dyson_compress.rdf", sizeLabel = "LADSPA #1403 · 16 KB",
            description = "Steve Harris lookahead dynamic compressor & peak ceiling limiter.",
            params = listOf(
                PluginParamSpec("threshold", "Peak Limit", -24f, 0f, -8f, "dB"),
                PluginParamSpec("ceiling", "Ceiling", -6f, -0.1f, -0.5f, "dB"),
                PluginParamSpec("release", "Release", 15f, 350f, 95f, "ms")
            )
        ),
        DesktopLadspaBrowserEntry(
            id = "ladspa_calf_filterclavier", ladspaUniqueId = 33920,
            name = "Calf Resonant SVF", creator = "Calf Studio Gear",
            category = PluginCategory.TONAL_EQ, kernel = PluginDspKernel.RESONANT_SVF,
            sourceUrl = "ladspa://calf.sourceforge.net/filter.rdf", sizeLabel = "LADSPA #33920 · 20 KB",
            description = "State-variable resonant LP/BP/HP synth & mix buss filter.",
            params = listOf(
                PluginParamSpec("cutoff", "Cutoff", 80f, 14000f, 2400f, "Hz"),
                PluginParamSpec("resonance", "Resonance", 0.5f, 8.0f, 2.2f, "Q"),
                PluginParamSpec("mode", "Mode (0-2)", 0f, 2f, 0f, "")
            )
        ),
        DesktopLadspaBrowserEntry(
            id = "ladspa_swh_vinyl", ladspaUniqueId = 1905,
            name = "SWH Vinyl Vintage", creator = "Steve Harris (SWH)",
            category = PluginCategory.SATURATION, kernel = PluginDspKernel.VINYL_LOFI,
            sourceUrl = "ladspa://plugin.org.uk/swh/jamin_vinyl.rdf", sizeLabel = "LADSPA #1905 · 21 KB",
            description = "Warm vinyl wear, pitch flutter, and needle dust emulation.",
            params = listOf(
                PluginParamSpec("flutter", "Warp Drift", 0f, 1f, 0.32f, ""),
                PluginParamSpec("crackle", "Surface Wear", 0f, 1f, 0.22f, ""),
                PluginParamSpec("tone", "Tone Cut", 1200f, 12000f, 4200f, "Hz")
            )
        ),
        DesktopLadspaBrowserEntry(
            id = "ladspa_zameq_exciter", ladspaUniqueId = 4810,
            name = "ZamHarmonic Exciter", creator = "Damien Zammit (ZamAudio)",
            category = PluginCategory.TONAL_EQ, kernel = PluginDspKernel.MULTIBAND_EXCITER,
            sourceUrl = "ladspa://zamaudio.com/zameq.rdf", sizeLabel = "LADSPA #4810 · 15 KB",
            description = "Split-band air harmonic exciter for bright master sheen.",
            params = listOf(
                PluginParamSpec("xover", "Crossover", 1200f, 8000f, 3200f, "Hz"),
                PluginParamSpec("harmonics", "Drive", 0f, 1f, 0.5f, ""),
                PluginParamSpec("presence", "Presence", 0f, 1f, 0.6f, "")
            )
        ),
        DesktopLadspaBrowserEntry(
            id = "ladspa_freeverb_ir", ladspaUniqueId = 2912,
            name = "Freeverb3 Impulse Hall", creator = "Freeverb3 Project",
            category = PluginCategory.SPATIAL, kernel = PluginDspKernel.CONVOLUTION_IR,
            sourceUrl = "ladspa://freeverb3.org/impulse_hall.rdf", sizeLabel = "LADSPA #2912 · 48 KB",
            description = "Synthetic acoustic hall impulse convolution processor.",
            params = listOf(
                PluginParamSpec("decay", "Decay", 0.2f, 2.5f, 1.35f, "s"),
                PluginParamSpec("damping", "HF Damp", 0.05f, 0.9f, 0.34f, ""),
                PluginParamSpec("predelay", "PreDelay", 0f, 60f, 20f, "ms")
            )
        )
    )

    fun isBlockedBinaryUrl(url: String): Boolean = BLOCKED_BINARY_REGEX.matches(url.trim())

    fun fetchFromUrl(rawUrl: String, timeoutMs: Int = 7000): Result<List<DesktopLadspaBrowserEntry>> = runCatching {
        val trimmed = rawUrl.trim()
        require(trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            "Enter an http:// or https:// LADSPA RDF or JSON DLC URL"
        }
        require(!isBlockedBinaryUrl(trimmed)) {
            "Blocked native binary URL (.so/.dll/.jar/.dex) — only declarative RDF/JSON DLC supported"
        }
        val conn = (URL(trimmed).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/rdf+xml, application/xml, text/xml, application/json")
            setRequestProperty("User-Agent", "Samplfie2-Desktop-LACSPA/1.0")
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
            require(parsed.isNotEmpty()) { "Feed contained 0 declarative LADSPA/DLC definitions" }
            parsed
        } finally {
            conn.disconnect()
        }
    }

    fun parseFeedText(text: String, sourceUrl: String = "remote://feed"): Result<List<DesktopLadspaBrowserEntry>> = runCatching {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Empty LADSPA/DLC payload" }
        when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> parseJsonCatalog(trimmed, sourceUrl)
            trimmed.startsWith("<") -> parseLadspaRdfXml(trimmed, sourceUrl)
            else -> emptyList()
        }
    }

    private fun parseJsonCatalog(jsonText: String, sourceUrl: String): List<DesktopLadspaBrowserEntry> {
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
            val parsed = DesktopPluginCatalogRegistry.fromJson(block).getOrNull() ?: return@mapIndexedNotNull null
            DesktopLadspaBrowserEntry(
                id = parsed.id,
                ladspaUniqueId = 9000 + idx,
                name = parsed.name,
                creator = parsed.vendor,
                category = parsed.category,
                kernel = parsed.kernel,
                sourceUrl = sourceUrl,
                sizeLabel = "DLC · ${parsed.params.size}p",
                description = parsed.description,
                params = parsed.params
            )
        }
    }

    fun parseLadspaRdfXml(xmlText: String, sourceUrl: String = "remote://ladspa.rdf"): List<DesktopLadspaBrowserEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
        val candidates = mutableListOf<Element>()
        collectElements(doc.documentElement, setOf("Plugin", "Description", "AudioPlugin"), candidates)

        val parsed = mutableListOf<DesktopLadspaBrowserEntry>()
        for ((index, el) in candidates.withIndex()) {
            val local = el.localName ?: el.tagName.substringAfter(':')
            val about = el.getAttribute("rdf:about").ifBlank { el.getAttribute("about") }
            val title = firstChildText(el, setOf("title", "name", "hasLabel", "label")) ?: continue
            if (local.equals("Description", true) && !about.contains("ladspa", true) && !hasChildTag(el, setOf("hasPort", "InputControlPort"))) {
                continue
            }
            if (isBlockedBinaryUrl(about)) continue

            val creator = firstChildText(el, setOf("creator", "author", "vendor")) ?: "LADSPA Community"
            val uid = firstChildText(el, setOf("hasUniqueId", "uniqueId", "id"))?.toIntOrNull()
                ?: (about.substringAfterLast('/').toIntOrNull() ?: (2000 + index))
            val desc = firstChildText(el, setOf("description", "comment"))
                ?: "LADSPA #$uid ported deterministically to Samplfie2 DSP kernel."

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
                PluginParamSpec(key, pName.take(18), lo, hi, defVal, firstChildText(portEl, setOf("unit", "hasUnit")).orEmpty().take(4))
            }.take(4)

            val (category, kernel, defaultParams) = mapTitleToKernel(title, desc)
            val effParams = if (params.isNotEmpty()) params else defaultParams
            val safeId = "ladspa_${uid}_${title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)}"
            parsed.add(
                DesktopLadspaBrowserEntry(
                    id = safeId,
                    ladspaUniqueId = uid,
                    name = title.take(36),
                    creator = creator.take(32),
                    category = category,
                    kernel = kernel,
                    sourceUrl = if (about.isNotBlank()) about else sourceUrl,
                    sizeLabel = "LADSPA #$uid · ${effParams.size}p",
                    description = desc.take(140),
                    params = effParams
                )
            )
        }
        return parsed.distinctBy { it.id }
    }

    private fun mapTitleToKernel(title: String, desc: String): Triple<PluginCategory, PluginDspKernel, List<PluginParamSpec>> {
        val text = "$title $desc".lowercase(Locale.US)
        return when {
            "tube" in text || "warm" in text || "sat" in text -> Triple(
                PluginCategory.SATURATION, PluginDspKernel.TUBE_WARMTH,
                DesktopPluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.TUBE_WARMTH }.params
            )
            "limit" in text || "comp" in text -> Triple(
                PluginCategory.DYNAMICS, PluginDspKernel.BRICKWALL_LIMITER,
                DesktopPluginCatalogRegistry.BUILT_IN_PLUGINS.first { it.kernel == PluginDspKernel.BRICKWALL_LIMITER }.params
            )
            "vinyl" in text || "tape" in text -> Triple(
                PluginCategory.SATURATION, PluginDspKernel.VINYL_LOFI,
                DesktopPluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.VINYL_LOFI }.params
            )
            "reverb" in text || "hall" in text || "impulse" in text -> Triple(
                PluginCategory.SPATIAL, PluginDspKernel.CONVOLUTION_IR,
                DesktopPluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.CONVOLUTION_IR }.params
            )
            "sub" in text || "bass" in text -> Triple(
                PluginCategory.TONAL_EQ, PluginDspKernel.SUB_HARMONIC,
                DesktopPluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.SUB_HARMONIC }.params
            )
            "filter" in text || "eq" in text -> Triple(
                PluginCategory.TONAL_EQ, PluginDspKernel.RESONANT_SVF,
                DesktopPluginCatalogRegistry.BUILT_IN_PLUGINS.first { it.kernel == PluginDspKernel.RESONANT_SVF }.params
            )
            else -> Triple(
                PluginCategory.TONAL_EQ, PluginDspKernel.MULTIBAND_EXCITER,
                DesktopPluginCatalogRegistry.INSTALLABLE_CATALOG.first { it.kernel == PluginDspKernel.MULTIBAND_EXCITER }.params
            )
        }
    }

    private fun collectElements(node: Node, names: Set<String>, out: MutableList<Element>) {
        if (node is Element) {
            val local = node.localName ?: node.tagName.substringAfter(':')
            if (names.any { it.equals(local, ignoreCase = true) }) out.add(node)
        }
        val children = node.childNodes
        for (i in 0 until children.length) collectElements(children.item(i), names, out)
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
