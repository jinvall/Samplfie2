package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.audio.DesktopPlaybackState
import com.samplfie.desktop.audio.DesktopSpectrogramPalette
import com.samplfie.desktop.model.DesktopSampleFormat
import com.samplfie.desktop.model.VisualizerMode
import com.samplfie.desktop.plugins.DesktopPluginCatalogEntry
import com.samplfie.desktop.plugins.DesktopPluginSlotInstance
import com.samplfie.desktop.tools.ChannelScope
import com.samplfie.desktop.tools.DesktopToolsState
import com.samplfie.desktop.tools.DesktopToolsTab
import com.samplfie.desktop.tools.MonoFoldMode
import com.samplfie.desktop.tools.StereoSplitMode
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Samplfie2 Linux Desktop — Pro Audio Tools, 10-Stop Spectrogram & DSP Plugin Rack Station.
 * Built with StudioTheme scalable primitives so 75%..300% HiDPI zoom (including 43" 4K displays)
 * renders cleanly without clipping.
 */
class ToolsAndPluginsPanel(
    private val onSelectTab: (DesktopToolsTab) -> Unit,
    private val onVisualizerMode: (VisualizerMode) -> Unit,
    private val onSelectionChange: (Float, Float) -> Unit,
    private val onSeekPlayhead: (Float) -> Unit,
    private val onTogglePlay: () -> Unit,
    private val onStop: () -> Unit,
    private val onToggleLoop: () -> Unit,
    private val onUndo: () -> Unit,
    private val onSelectPalette: (DesktopSpectrogramPalette) -> Unit,
    private val onApplyCustomHexStops: (String) -> Result<DesktopSpectrogramPalette>,
    private val onSpecFreqRange: (Float, Float) -> Unit,
    private val onSetSpecAttenDb: (Float) -> Unit,
    private val onApplySpectralDelete: () -> Unit,
    private val onSetInvertScope: (ChannelScope) -> Unit,
    private val onSetInvertSelectionOnly: (Boolean) -> Unit,
    private val onApplyInvert: () -> Unit,
    private val onBrowseRefFile: () -> Unit,
    private val onSelectSessionRef: (String) -> Unit,
    private val onComputeDiff: () -> Unit,
    private val onLoadDiffToDeck: () -> Unit,
    private val onUpdateMixParams: (Float, Float, Float, Boolean) -> Unit,
    private val onApplyMix: () -> Unit,
    private val onSetMonoMode: (MonoFoldMode) -> Unit,
    private val onApplyMixToMono: () -> Unit,
    private val onSetStereoParams: (StereoSplitMode, Float) -> Unit,
    private val onApplySplitToStereo: () -> Unit,
    private val onSetRateParams: (Int, Boolean) -> Unit,
    private val onApplyConvertRate: () -> Unit,
    private val onSetFormatParams: (DesktopSampleFormat, Boolean) -> Unit,
    private val onApplyConvertFormat: () -> Unit,
    private val onAddPluginToChain: (String) -> Unit,
    private val onRemovePluginFromChain: (String) -> Unit,
    private val onMovePluginInChain: (String, Int) -> Unit,
    private val onTogglePluginBypass: (String) -> Unit,
    private val onUpdatePluginWetMix: (String, Float) -> Unit,
    private val onUpdatePluginParam: (String, String, Float) -> Unit,
    private val onBouncePluginChain: () -> Unit,
    private val onInstallCatalogPlugin: (DesktopPluginCatalogEntry) -> Unit,
    private val onUninstallCustomPlugin: (String) -> Unit,
    private val onImportPluginOrIrFile: () -> Unit,
    private val onRescanLinuxPlugins: () -> Unit
) : JPanel() {

    private var updating = false
    private var uiState = DesktopStudioUiState()
    private var playState = DesktopPlaybackState()
    private var toolsState = DesktopToolsState()

    private val headerInfoLabel = StudioTheme.hardwareLabel("NO AUDIO LOADED", StudioTheme.TextPrimary, StudioTheme.MonoFont)
    private val reportLabel = StudioTheme.hardwareLabel("READY", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)

    private val playBtn = StudioTheme.hardwareButton("PLAY", StudioTheme.EmeraldSignal, filled = true) { onTogglePlay() }
    private val stopBtn = StudioTheme.hardwareButton("STOP", StudioTheme.TextSecondary) { onStop() }
    private val loopBtn = StudioTheme.hardwareButton("LOOP", StudioTheme.AmberHot) { onToggleLoop() }
    private val undoBtn = StudioTheme.hardwareButton("UNDO", StudioTheme.AmberHot) { onUndo() }

    private val waveformStrip = CompactWaveformCanvas()
    private val spectrogramCanvas = DesktopSpectrogramCanvas(
        onSelectionTimeChange = { s, e -> onSelectionChange(s, e) },
        onSelectionFreqChange = { fLo, fHi -> onSpecFreqRange(fLo, fHi) },
        onSeekPlayhead = { f -> onSeekPlayhead(f) }
    )

    private val tabCardLayout = CardLayout()
    private val tabCardHost = JPanel(tabCardLayout).apply { isOpaque = false }
    private val tabButtons = mutableMapOf<DesktopToolsTab, javax.swing.JButton>()

    // --- Tab 1: Spectral / Invert / Diff ---
    private val specLowSpinner = StudioTheme.styleSpinner(JSpinner(SpinnerNumberModel(220, 20, 22000, 20)), baseWidth = 98)
    private val specHighSpinner = StudioTheme.styleSpinner(JSpinner(SpinnerNumberModel(3800, 60, 24000, 40)), baseWidth = 98)
    private val specAttenSlider = StudioTheme.hardwareSlider(6, 96, 96)
    private val specAttenValLabel = StudioTheme.hardwareLabel("-96 dB", StudioTheme.TacCoral, StudioTheme.MonoFont)
    private val specRegionLabel = StudioTheme.hardwareLabel("SEL 0.00s..0.00s", StudioTheme.NeonCyan, StudioTheme.MonoFont)

    private val invertScopeBox = StudioTheme.styleComboBox(JComboBox(ChannelScope.entries.toTypedArray()))
    private val invertSelOnlyCb = StudioTheme.darkCheckBox("INVERT SELECTION ONLY", StudioTheme.AmberHot) { sel ->
        if (!updating) onSetInvertSelectionOnly(sel)
    }

    private val refStatusLabel = StudioTheme.hardwareLabel("REF B: NO REF LOADED", StudioTheme.AmberHot, StudioTheme.MonoFont)
    private val diffMetricsLabel = StudioTheme.hardwareLabel("RUN COMPUTE DIFF (A-B) TO MEASURE NULL DEPTH", StudioTheme.TextSecondary, StudioTheme.MonoFont)
    private val loadDiffBtn = StudioTheme.hardwareButton("LOAD DIFF (A-B) TO DECK", StudioTheme.EmeraldSignal) { onLoadDiffToDeck() }

    // --- Tab 2: Mix / Mono / Stereo ---
    private val mixPrimarySlider = StudioTheme.hardwareSlider(0, 200, 100)
    private val mixSecondarySlider = StudioTheme.hardwareSlider(0, 200, 70)
    private val mixOffsetSpinner = StudioTheme.styleSpinner(JSpinner(SpinnerNumberModel(0.0, 0.0, 30.0, 0.05)), baseWidth = 92)
    private val mixPrimaryValLabel = StudioTheme.hardwareLabel("100%", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val mixSecondaryValLabel = StudioTheme.hardwareLabel("70%", StudioTheme.AmberHot, StudioTheme.MonoFont)
    private val mixLimitCb = StudioTheme.darkCheckBox("SOFT-CLIP LIMITER", StudioTheme.EmeraldSignal) {
        if (!updating) pushMixParams()
    }.apply { isSelected = true }

    private val monoModeBox = StudioTheme.styleComboBox(JComboBox(MonoFoldMode.entries.toTypedArray()))
    private val stereoModeBox = StudioTheme.styleComboBox(JComboBox(StereoSplitMode.entries.toTypedArray()))
    private val stereoParamSlider = StudioTheme.hardwareSlider(1, 40, 12)
    private val stereoParamLabel = StudioTheme.hardwareLabel("12 ms / width", StudioTheme.VioletMod, StudioTheme.MonoFont)

    // --- Tab 3: Sample Rate / Format ---
    private val rateBox = StudioTheme.styleComboBox(JComboBox(DesktopSampleFormat.STANDARD_SAMPLE_RATES.toTypedArray()))
    private val reinterpretCb = StudioTheme.darkCheckBox("REINTERPRET HEADER ONLY (VARISPEED)", StudioTheme.AmberHot) { sel ->
        if (!updating) {
            val sr = (rateBox.selectedItem as? Int) ?: 44100
            onSetRateParams(sr, sel)
        }
    }
    private val formatBox = StudioTheme.styleComboBox(JComboBox(DesktopSampleFormat.entries.toTypedArray()))
    private val ditherCb = StudioTheme.darkCheckBox("APPLY TPDF DITHER", StudioTheme.EmeraldSignal) { sel ->
        if (!updating) {
            val fmt = (formatBox.selectedItem as? DesktopSampleFormat) ?: DesktopSampleFormat.PCM_16
            onSetFormatParams(fmt, sel)
        }
    }

    // --- Tab 4: Plugin Manager ---
    private val installedCombo = StudioTheme.styleComboBox(JComboBox<String>())
    private var installedPluginIds = emptyList<String>()
    private val pluginChainHost = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private var renderedChainSignature: String = "__init__"
    private data class SlotUiBinding(
        val wetSlider: JSlider,
        val wetLabel: JLabel,
        val bypassBtn: javax.swing.JButton,
        val paramBindings: Map<String, Pair<JSlider, JLabel>>
    )
    private val slotBindings = mutableMapOf<String, SlotUiBinding>()

    // --- Tab 5: Plugin Installer & Linux Scanner ---
    private val nativeScannerHost = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val catalogHost = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private var renderedNativeSignature: String = "__init__"
    private var renderedCatalogSignature: String = "__init__"

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 10, 12, 10, 12)
        }

        // Top Header + Transport + 10-Stop Gradient Selector
        val topCard = StudioTheme.hardwarePanel(StudioTheme.MagentaFx).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val row1 = JPanel(BorderLayout(8, 4)).apply {
                isOpaque = false
                val left = JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(StudioTheme.hardwareLabel("PRO AUDIO TOOLS, 10-STOP SPECTROGRAM & PLUGIN RACK", StudioTheme.MagentaFx, StudioTheme.TitleFont))
                    add(headerInfoLabel)
                    add(reportLabel)
                }
                val right = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 6, 2).apply {
                    add(playBtn)
                    add(stopBtn)
                    add(loopBtn)
                    add(undoBtn)
                }
                add(left, BorderLayout.CENTER)
                add(right, BorderLayout.EAST)
            }
            add(row1)
            add(Box.createVerticalStrut(6))

            val gradRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 6, 3).apply {
                add(StudioTheme.hardwareLabel("VIEW:", StudioTheme.TextSecondary, StudioTheme.MonoSmallFont))
                add(StudioTheme.hardwareButton("W+SPEC", StudioTheme.NeonCyan) { onVisualizerMode(VisualizerMode.W_SPEC) })
                add(StudioTheme.hardwareButton("SPEC", StudioTheme.MagentaFx) { onVisualizerMode(VisualizerMode.SPEC) })
                add(StudioTheme.hardwareButton("WAVE", StudioTheme.EmeraldSignal) { onVisualizerMode(VisualizerMode.WAVE) })
                add(StudioTheme.hardwareLabel("  10-STOP GRADIENT:", StudioTheme.TextSecondary, StudioTheme.MonoSmallFont))
                DesktopSpectrogramPalette.PRESETS.forEach { preset ->
                    add(StudioTheme.hardwareButton(preset.id.uppercase(), StudioTheme.VioletMod) { onSelectPalette(preset) })
                }
                add(StudioTheme.hardwareButton("EDIT 10-STOP HEX…", StudioTheme.NeonCyan, filled = true) { openCustomHexDialog() })
            }
            add(gradRow)
        }
        root.add(topCard)
        root.add(Box.createVerticalStrut(8))

        // Embedded Wave + 10-Stop Spectrogram Canvas
        val visCard = StudioTheme.hardwarePanel(StudioTheme.ElevatedBezel).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPrefSize(waveformStrip, 680, 92)
            StudioTheme.setScaledMaxSize(waveformStrip, Int.MAX_VALUE, 150)
            StudioTheme.setScaledPrefSize(spectrogramCanvas, 680, 196)
            StudioTheme.setScaledMaxSize(spectrogramCanvas, Int.MAX_VALUE, 360)
            add(waveformStrip)
            add(Box.createVerticalStrut(4))
            add(spectrogramCanvas)
        }
        root.add(visCard)
        root.add(Box.createVerticalStrut(8))

        // Section Tabs
        val tabBar = StudioTheme.hardwarePanel(StudioTheme.HairlineDivider).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 6, 4)
            DesktopToolsTab.entries.forEach { tab ->
                val accent = tabAccent(tab)
                val btn = StudioTheme.hardwareButton(tab.label, accent) { onSelectTab(tab) }
                tabButtons[tab] = btn
                add(btn)
            }
        }
        root.add(tabBar)
        root.add(Box.createVerticalStrut(8))

        tabCardHost.add(buildSpectralDiffTab(), DesktopToolsTab.SPECTRAL_DIFF.name)
        tabCardHost.add(buildMixChannelsTab(), DesktopToolsTab.MIX_CHANNELS.name)
        tabCardHost.add(buildRateFormatTab(), DesktopToolsTab.RATE_FORMAT.name)
        tabCardHost.add(buildPluginManagerTab(), DesktopToolsTab.PLUGIN_MANAGER.name)
        tabCardHost.add(buildPluginInstallerTab(), DesktopToolsTab.PLUGIN_INSTALLER.name)
        root.add(tabCardHost)
        root.add(Box.createVerticalGlue())

        wireInputListeners()
        add(StudioTheme.styleScrollPane(JScrollPane(root)), BorderLayout.CENTER)
    }

    private fun tabAccent(tab: DesktopToolsTab): Color = when (tab) {
        DesktopToolsTab.SPECTRAL_DIFF -> StudioTheme.TacCoral
        DesktopToolsTab.MIX_CHANNELS -> StudioTheme.EmeraldSignal
        DesktopToolsTab.RATE_FORMAT -> StudioTheme.AmberHot
        DesktopToolsTab.PLUGIN_MANAGER -> StudioTheme.NeonCyan
        DesktopToolsTab.PLUGIN_INSTALLER -> StudioTheme.MagentaFx
    }

    private fun buildSpectralDiffTab(): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // 1. Spectral Delete Card
        val specCard = StudioTheme.hardwarePanel(StudioTheme.TacCoral).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("SPECTRAL DELETE (2D TIME x FREQUENCY STFT NOTCH)", StudioTheme.TacCoral, StudioTheme.TitleFont))
            add(specRegionLabel)
            add(Box.createVerticalStrut(6))

            val row1 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareLabel("LOW FREQ (Hz):", StudioTheme.TextSecondary))
                add(specLowSpinner)
                add(StudioTheme.hardwareLabel("HIGH FREQ (Hz):", StudioTheme.TextSecondary))
                add(specHighSpinner)
                add(StudioTheme.hardwareLabel("ATTENUATION:", StudioTheme.TextSecondary))
                add(specAttenSlider.apply { StudioTheme.setScaledPrefSize(this, 160, 28) })
                add(specAttenValLabel)
                add(StudioTheme.hardwareButton("APPLY SPECTRAL DELETE", StudioTheme.TacCoral, filled = true) {
                    onApplySpectralDelete()
                })
            }
            add(row1)
        }
        add(specCard)
        add(Box.createVerticalStrut(8))

        // 2. Invert Phase/Polarity Card
        val invCard = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 10, 4)
            add(StudioTheme.hardwareLabel("POLARITY INVERT (180°):", StudioTheme.VioletMod, StudioTheme.TitleFont))
            add(invertScopeBox)
            add(invertSelOnlyCb)
            add(StudioTheme.hardwareButton("INVERT POLARITY", StudioTheme.VioletMod, filled = true) { onApplyInvert() })
        }
        add(invCard)
        add(Box.createVerticalStrut(8))

        // 3. Audio Difference Analyzer
        val diffCard = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("AUDIO DIFFERENCE ANALYZER (A - B NULL TEST)", StudioTheme.AmberHot, StudioTheme.TitleFont))
            add(refStatusLabel)
            add(diffMetricsLabel)
            add(Box.createVerticalStrut(6))
            val picks = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 6, 4).apply {
                add(StudioTheme.hardwareButton("BROWSE REF WAV…", StudioTheme.NeonCyan) { onBrowseRefFile() })
                listOf("RAW", "DRY", "LOOP_A", "LOOP_B", "LOOP_C", "LOOP_D").forEach { tag ->
                    add(StudioTheme.hardwareButton(tag, StudioTheme.AmberHot) { onSelectSessionRef(tag) })
                }
                add(StudioTheme.hardwareButton("COMPUTE DIFF (A-B)", StudioTheme.AmberHot, filled = true) { onComputeDiff() })
                add(loadDiffBtn)
            }
            add(picks)
        }
        add(diffCard)
    }

    private fun buildMixChannelsTab(): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // 1. Mix Deck + Ref
        val mixCard = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("MIX / BLEND DECK A + REFERENCE B", StudioTheme.EmeraldSignal, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(6))
            val r1 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 4).apply {
                add(StudioTheme.hardwareLabel("DECK A GAIN:", StudioTheme.TextSecondary))
                add(mixPrimarySlider.apply { StudioTheme.setScaledPrefSize(this, 130, 28) })
                add(mixPrimaryValLabel)
                add(StudioTheme.hardwareLabel("REF B GAIN:", StudioTheme.TextSecondary))
                add(mixSecondarySlider.apply { StudioTheme.setScaledPrefSize(this, 130, 28) })
                add(mixSecondaryValLabel)
                add(StudioTheme.hardwareLabel("OFFSET (s):", StudioTheme.TextSecondary))
                add(mixOffsetSpinner)
                add(mixLimitCb)
                add(StudioTheme.hardwareButton("APPLY MIX (A + B)", StudioTheme.EmeraldSignal, filled = true) { onApplyMix() })
            }
            add(r1)
        }
        add(mixCard)
        add(Box.createVerticalStrut(8))

        // 2. Mix to Mono & Split to Stereo
        val chCard = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("CHANNEL FOLD & STEREO SPLIT", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(6))
            val r1 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 4).apply {
                add(StudioTheme.hardwareLabel("MIX TO MONO:", StudioTheme.TextSecondary))
                add(monoModeBox)
                add(StudioTheme.hardwareButton("CONVERT TO MONO (1CH)", StudioTheme.NeonCyan, filled = true) { onApplyMixToMono() })
            }
            val r2 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 4).apply {
                add(StudioTheme.hardwareLabel("SPLIT TO STEREO:", StudioTheme.TextSecondary))
                add(stereoModeBox)
                add(stereoParamSlider.apply { StudioTheme.setScaledPrefSize(this, 140, 28) })
                add(stereoParamLabel)
                add(StudioTheme.hardwareButton("SPLIT TO STEREO (2CH)", StudioTheme.VioletMod, filled = true) { onApplySplitToStereo() })
            }
            add(r1)
            add(r2)
        }
        add(chCard)
    }

    private fun buildRateFormatTab(): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val srCard = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("SAMPLE RATE CONVERSION (8 kHz – 192 kHz)", StudioTheme.AmberHot, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(6))
            val r = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareLabel("TARGET SAMPLE RATE (Hz):", StudioTheme.TextSecondary))
                add(rateBox)
                add(reinterpretCb)
                add(StudioTheme.hardwareButton("CONVERT SAMPLE RATE", StudioTheme.AmberHot, filled = true) { onApplyConvertRate() })
            }
            add(r)
        }
        add(srCard)
        add(Box.createVerticalStrut(8))

        val fmtCard = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("PCM / IEEE FLOAT FORMAT & WAV EXPORT BIT DEPTH", StudioTheme.EmeraldSignal, StudioTheme.TitleFont))
            add(StudioTheme.hardwareLabel("Converts active deck buffer precision and configures Save .WAV output format.", StudioTheme.TextSecondary, StudioTheme.BodyFont))
            add(Box.createVerticalStrut(6))
            val r = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareLabel("TARGET FORMAT:", StudioTheme.TextSecondary))
                add(formatBox)
                add(ditherCb)
                add(StudioTheme.hardwareButton("CONVERT BUFFER FORMAT", StudioTheme.EmeraldSignal, filled = true) { onApplyConvertFormat() })
            }
            add(r)
        }
        add(fmtCard)
    }

    private fun buildPluginManagerTab(): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val addBar = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 8, 4)
            add(StudioTheme.hardwareLabel("INSERT PLUGIN:", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(installedCombo)
            add(StudioTheme.hardwareButton("+ ADD TO CHAIN", StudioTheme.NeonCyan, filled = true) {
                val idx = installedCombo.selectedIndex
                val id = installedPluginIds.getOrNull(idx) ?: return@hardwareButton
                onAddPluginToChain(id)
            })
            add(StudioTheme.hardwareButton("BOUNCE CHAIN TO DECK", StudioTheme.AmberHot, filled = true) {
                onBouncePluginChain()
            })
        }
        add(addBar)
        add(Box.createVerticalStrut(8))
        add(pluginChainHost)
    }

    private fun buildPluginInstallerTab(): JPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val importCard = StudioTheme.hardwarePanel(StudioTheme.MagentaFx).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 8, 4)
            add(StudioTheme.hardwareLabel("PLUGIN INSTALLER & IMPULSE RESPONSE LOADER:", StudioTheme.MagentaFx, StudioTheme.TitleFont))
            add(StudioTheme.hardwareButton("IMPORT .SMPPLUGIN.JSON / .WAV IR…", StudioTheme.MagentaFx, filled = true) {
                onImportPluginOrIrFile()
            })
            add(StudioTheme.hardwareButton("RESCAN LINUX LV2/LADSPA/VST", StudioTheme.NeonCyan) {
                onRescanLinuxPlugins()
            })
        }
        add(importCard)
        add(Box.createVerticalStrut(8))
        add(catalogHost)
        add(Box.createVerticalStrut(8))
        add(nativeScannerHost)
    }

    private fun wireInputListeners() {
        specLowSpinner.addChangeListener {
            if (updating) return@addChangeListener
            val lo = (specLowSpinner.value as? Number)?.toFloat() ?: 220f
            val hi = (specHighSpinner.value as? Number)?.toFloat() ?: 3800f
            onSpecFreqRange(lo, hi)
        }
        specHighSpinner.addChangeListener {
            if (updating) return@addChangeListener
            val lo = (specLowSpinner.value as? Number)?.toFloat() ?: 220f
            val hi = (specHighSpinner.value as? Number)?.toFloat() ?: 3800f
            onSpecFreqRange(lo, hi)
        }
        specAttenSlider.addChangeListener {
            if (!updating) {
                specAttenValLabel.text = "-${specAttenSlider.value} dB"
                onSetSpecAttenDb(specAttenSlider.value.toFloat())
            }
        }
        invertScopeBox.addActionListener {
            if (!updating) {
                (invertScopeBox.selectedItem as? ChannelScope)?.let(onSetInvertScope)
            }
        }
        mixPrimarySlider.addChangeListener { if (!updating) pushMixParams() }
        mixSecondarySlider.addChangeListener { if (!updating) pushMixParams() }
        mixOffsetSpinner.addChangeListener { if (!updating) pushMixParams() }
        monoModeBox.addActionListener {
            if (!updating) (monoModeBox.selectedItem as? MonoFoldMode)?.let(onSetMonoMode)
        }
        stereoModeBox.addActionListener {
            if (!updating) {
                val m = (stereoModeBox.selectedItem as? StereoSplitMode) ?: StereoSplitMode.HAAS_DELAY
                onSetStereoParams(m, stereoParamSlider.value.toFloat())
            }
        }
        stereoParamSlider.addChangeListener {
            if (!updating) {
                val m = (stereoModeBox.selectedItem as? StereoSplitMode) ?: StereoSplitMode.HAAS_DELAY
                onSetStereoParams(m, stereoParamSlider.value.toFloat())
            }
        }
        rateBox.addActionListener {
            if (!updating) {
                val sr = (rateBox.selectedItem as? Int) ?: 44100
                onSetRateParams(sr, reinterpretCb.isSelected)
            }
        }
        formatBox.addActionListener {
            if (!updating) {
                val fmt = (formatBox.selectedItem as? DesktopSampleFormat) ?: DesktopSampleFormat.PCM_16
                onSetFormatParams(fmt, ditherCb.isSelected)
            }
        }
    }

    private fun pushMixParams() {
        val g1 = mixPrimarySlider.value / 100f
        val g2 = mixSecondarySlider.value / 100f
        val off = (mixOffsetSpinner.value as? Number)?.toFloat() ?: 0f
        mixPrimaryValLabel.text = "${mixPrimarySlider.value}%"
        mixSecondaryValLabel.text = "${mixSecondarySlider.value}%"
        onUpdateMixParams(g1, g2, off, mixLimitCb.isSelected)
    }

    fun updateState(
        ui: DesktopStudioUiState,
        play: DesktopPlaybackState,
        tools: DesktopToolsState
    ) {
        updating = true
        try {
            uiState = ui
            playState = play
            toolsState = tools

            val buf = ui.processedBuffer ?: ui.dryBuffer
            val dur = buf?.durationSeconds ?: 0f
            headerInfoLabel.text = if (buf != null) {
                "${buf.name} • ${"%.2f".format(dur)}s • ${buf.sampleRate}Hz • ${buf.channels}CH • ${buf.sampleFormat.shortLabel}"
            } else {
                "NO AUDIO LOADED"
            }
            reportLabel.text = tools.lastToolReport

            playBtn.text = if (play.isPlaying) "PAUSE" else "PLAY"
            loopBtn.text = if (play.isLooping) "LOOP: ON" else "LOOP: OFF"
            undoBtn.isEnabled = ui.canUndo

            tabCardLayout.show(tabCardHost, tools.activeTab.name)
            tabButtons.forEach { (tab, btn) ->
                btn.foreground = if (tab == tools.activeTab) tabAccent(tab) else StudioTheme.TextSecondary
            }

            waveformStrip.repaint()
            spectrogramCanvas.updateSpectrogram(
                data = ui.spectrogram,
                palette = tools.specPalette,
                selectionStart = ui.selectionStart,
                selectionEnd = ui.selectionEnd,
                freqLowHz = tools.specFreqLowHz,
                freqHighHz = tools.specFreqHighHz,
                playheadFraction = play.playheadFraction
            )

            specRegionLabel.text = "REGION: ${"%.2f".format(ui.selectionStart * dur)}s .. ${"%.2f".format(ui.selectionEnd * dur)}s  ×  ${tools.specFreqLowHz.toInt()}–${tools.specFreqHighHz.toInt()} Hz"
            if (!specLowSpinner.hasFocus()) specLowSpinner.value = tools.specFreqLowHz.toInt()
            if (!specHighSpinner.hasFocus()) specHighSpinner.value = tools.specFreqHighHz.toInt()
            if (!specAttenSlider.valueIsAdjusting) specAttenSlider.value = tools.specDeleteAttenDb.toInt()
            specAttenValLabel.text = "-${tools.specDeleteAttenDb.toInt()} dB"

            invertScopeBox.selectedItem = tools.invertScope
            invertSelOnlyCb.isSelected = tools.invertSelectionOnly

            refStatusLabel.text = "REF B: ${tools.referenceLabel}"
            val diff = tools.lastDiffResult
            if (diff != null) {
                diffMetricsLabel.text = "PEAK DIFF=${"%.4f".format(diff.peakDiff)}  •  RMS=${"%.4f".format(diff.rmsDiff)}  •  NULL DEPTH=${"%.1f".format(diff.nullDepthDb)} dB  •  CORR=${"%.3f".format(diff.correlation)}"
                loadDiffBtn.isEnabled = true
            } else {
                diffMetricsLabel.text = "PICK REF B (FILE, RAW, DRY, OR LOOP A-D) & RUN COMPUTE DIFF (A-B)"
                loadDiffBtn.isEnabled = false
            }

            if (!mixPrimarySlider.valueIsAdjusting) mixPrimarySlider.value = (tools.mixPrimaryGain * 100f).roundToInt().coerceIn(0, 200)
            if (!mixSecondarySlider.valueIsAdjusting) mixSecondarySlider.value = (tools.mixSecondaryGain * 100f).roundToInt().coerceIn(0, 200)
            mixPrimaryValLabel.text = "${mixPrimarySlider.value}%"
            mixSecondaryValLabel.text = "${mixSecondarySlider.value}%"
            if (!mixOffsetSpinner.hasFocus()) mixOffsetSpinner.value = tools.mixOffsetSec.toDouble()
            mixLimitCb.isSelected = tools.mixSoftLimit

            monoModeBox.selectedItem = tools.monoFoldMode
            stereoModeBox.selectedItem = tools.stereoSplitMode
            if (!stereoParamSlider.valueIsAdjusting) {
                stereoParamSlider.value = tools.stereoSplitParam.roundToInt().coerceIn(1, 40)
            }
            stereoParamLabel.text = "${tools.stereoSplitParam.roundToInt()} ms"

            rateBox.selectedItem = tools.targetSampleRate
            reinterpretCb.isSelected = tools.reinterpretRateOnly
            formatBox.selectedItem = tools.targetSampleFormat
            ditherCb.isSelected = tools.applyDither

            syncInstalledPluginsCombo(tools)
            syncPluginChainCards(tools)
            syncInstallerCards(tools)
        } finally {
            updating = false
        }
    }

    private fun syncInstalledPluginsCombo(tools: DesktopToolsState) {
        val nextIds = tools.installedPlugins.map { it.id }
        if (nextIds != installedPluginIds) {
            installedPluginIds = nextIds
            installedCombo.removeAllItems()
            tools.installedPlugins.forEach { def ->
                installedCombo.addItem("${def.name} [${def.category.label}]")
            }
        }
    }

    private fun syncPluginChainCards(tools: DesktopToolsState) {
        val sig = tools.pluginChain.joinToString("|") { "${it.instanceId}:${it.pluginId}" }
        if (sig != renderedChainSignature) {
            renderedChainSignature = sig
            slotBindings.clear()
            pluginChainHost.removeAll()
            val defs = tools.installedPlugins.associateBy { it.id }

            if (tools.pluginChain.isEmpty()) {
                pluginChainHost.add(
                    StudioTheme.hardwarePanel(StudioTheme.HairlineDivider).apply {
                        layout = FlowLayout(FlowLayout.LEFT)
                        add(StudioTheme.hardwareLabel("PLUGIN CHAIN EMPTY — SELECT AN INSTALLED PLUGIN ABOVE AND CLICK + ADD TO CHAIN", StudioTheme.TextSecondary, StudioTheme.BodyFont))
                    }
                )
            } else {
                tools.pluginChain.forEachIndexed { idx, slot ->
                    val def = defs[slot.pluginId]
                    val card = StudioTheme.hardwarePanel(if (slot.bypassed) StudioTheme.HairlineDivider else StudioTheme.NeonCyan).apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)

                        val topRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 2)
                        topRow.add(StudioTheme.hardwareLabel("#${idx + 1} ${slot.pluginName} (${slot.category.label})", StudioTheme.TextPrimary, StudioTheme.TitleFont))
                        val bypassBtn = StudioTheme.hardwareButton(if (slot.bypassed) "BYPASSED" else "ACTIVE", StudioTheme.EmeraldSignal) {
                            onTogglePluginBypass(slot.instanceId)
                        }
                        topRow.add(bypassBtn)
                        topRow.add(StudioTheme.hardwareButton("UP", StudioTheme.NeonCyan) { onMovePluginInChain(slot.instanceId, -1) })
                        topRow.add(StudioTheme.hardwareButton("DOWN", StudioTheme.NeonCyan) { onMovePluginInChain(slot.instanceId, 1) })
                        topRow.add(StudioTheme.hardwareButton("REMOVE", StudioTheme.TacCoral) { onRemovePluginFromChain(slot.instanceId) })
                        add(topRow)

                        val wetRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 2)
                        wetRow.add(StudioTheme.hardwareLabel("WET MIX:", StudioTheme.TextSecondary, StudioTheme.MonoSmallFont))
                        val wetSlider = StudioTheme.hardwareSlider(0, 100, (slot.wetMix * 100f).roundToInt()).apply {
                            StudioTheme.setScaledPrefSize(this, 160, 26)
                        }
                        val wetLabel = StudioTheme.hardwareLabel("${(slot.wetMix * 100f).roundToInt()}%", StudioTheme.NeonCyan, StudioTheme.MonoSmallFont)
                        wetSlider.addChangeListener {
                            wetLabel.text = "${wetSlider.value}%"
                            if (!updating) onUpdatePluginWetMix(slot.instanceId, wetSlider.value / 100f)
                        }
                        wetRow.add(wetSlider)
                        wetRow.add(wetLabel)
                        add(wetRow)

                        val pMap = mutableMapOf<String, Pair<JSlider, JLabel>>()
                        def?.params?.forEach { spec ->
                            val curVal = slot.paramValues[spec.key] ?: spec.defaultVal
                            val pRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 2)
                            pRow.add(StudioTheme.hardwareLabel("${spec.label.uppercase()}:", StudioTheme.TextSecondary, StudioTheme.MonoSmallFont))
                            val slider = StudioTheme.hardwareSlider(0, 1000, normToTick(curVal, spec.min, spec.max)).apply {
                                StudioTheme.setScaledPrefSize(this, 170, 26)
                            }
                            val valLbl = StudioTheme.hardwareLabel("${"%.2f".format(curVal)} ${spec.unit}", StudioTheme.AmberHot, StudioTheme.MonoSmallFont)
                            slider.addChangeListener {
                                val mapped = tickToValue(slider.value, spec.min, spec.max)
                                valLbl.text = "${"%.2f".format(mapped)} ${spec.unit}"
                                if (!updating) onUpdatePluginParam(slot.instanceId, spec.key, mapped)
                            }
                            pRow.add(slider)
                            pRow.add(valLbl)
                            add(pRow)
                            pMap[spec.key] = slider to valLbl
                        }
                        slotBindings[slot.instanceId] = SlotUiBinding(wetSlider, wetLabel, bypassBtn, pMap)
                    }
                    pluginChainHost.add(card)
                    pluginChainHost.add(Box.createVerticalStrut(6))
                }
            }
            pluginChainHost.revalidate()
            pluginChainHost.repaint()
        } else {
            val defs = tools.installedPlugins.associateBy { it.id }
            tools.pluginChain.forEach { slot ->
                val b = slotBindings[slot.instanceId] ?: return@forEach
                b.bypassBtn.text = if (slot.bypassed) "BYPASSED" else "ACTIVE"
                if (!b.wetSlider.valueIsAdjusting) {
                    b.wetSlider.value = (slot.wetMix * 100f).roundToInt().coerceIn(0, 100)
                }
                b.wetLabel.text = "${(slot.wetMix * 100f).roundToInt()}%"
                val def = defs[slot.pluginId]
                def?.params?.forEach { spec ->
                    val curVal = slot.paramValues[spec.key] ?: spec.defaultVal
                    b.paramBindings[spec.key]?.let { (sl, lbl) ->
                        if (!sl.valueIsAdjusting) sl.value = normToTick(curVal, spec.min, spec.max)
                        lbl.text = "${"%.2f".format(curVal)} ${spec.unit}"
                    }
                }
            }
        }
    }

    private fun syncInstallerCards(tools: DesktopToolsState) {
        val catSig = tools.installedPlugins.joinToString(",") { it.id }
        if (catSig != renderedCatalogSignature) {
            renderedCatalogSignature = catSig
            catalogHost.removeAll()
            val installedIds = tools.installedPlugins.map { it.id }.toSet()

            catalogHost.add(StudioTheme.hardwareLabel("SAMPLFIE2 DSP PLUGIN CATALOG (10 INSTALLABLE MODULES)", StudioTheme.MagentaFx, StudioTheme.TitleFont))
            catalogHost.add(Box.createVerticalStrut(6))

            tools.catalogEntries.forEach { entry ->
                val isInstalled = installedIds.contains(entry.id)
                val rowCard = StudioTheme.hardwarePanel(if (isInstalled) StudioTheme.EmeraldSignal else StudioTheme.HairlineDivider).apply {
                    layout = BorderLayout(8, 4)
                    val info = JPanel().apply {
                        isOpaque = false
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(StudioTheme.hardwareLabel("${entry.name}  v${entry.version} • ${entry.vendor} • ${entry.category.label}", StudioTheme.TextPrimary, StudioTheme.LabelFont))
                        add(StudioTheme.hardwareLabel(entry.description, StudioTheme.TextSecondary, StudioTheme.BodyFont))
                    }
                    val actions = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 6, 2).apply {
                        if (isInstalled) {
                            add(StudioTheme.hardwareButton("+ CHAIN", StudioTheme.NeonCyan, filled = true) { onAddPluginToChain(entry.id) })
                            add(StudioTheme.hardwareButton("UNINSTALL", StudioTheme.TacCoral) { onUninstallCustomPlugin(entry.id) })
                        } else {
                            add(StudioTheme.hardwareButton("INSTALL (${entry.sizeLabel})", StudioTheme.EmeraldSignal, filled = true) {
                                onInstallCatalogPlugin(entry)
                            })
                        }
                    }
                    add(info, BorderLayout.CENTER)
                    add(actions, BorderLayout.EAST)
                }
                catalogHost.add(rowCard)
                catalogHost.add(Box.createVerticalStrut(5))
            }

            // Show imported custom JSON/IR plugins not in catalog
            val catIds = tools.catalogEntries.map { it.id }.toSet()
            tools.installedPlugins.filter { !it.isBuiltIn && !catIds.contains(it.id) }.forEach { custom ->
                val rowCard = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
                    layout = BorderLayout(8, 4)
                    val info = JPanel().apply {
                        isOpaque = false
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(StudioTheme.hardwareLabel("${custom.name} • ${custom.sourceFormatLabel}", StudioTheme.NeonCyan, StudioTheme.LabelFont))
                        add(StudioTheme.hardwareLabel(custom.description, StudioTheme.TextSecondary, StudioTheme.BodyFont))
                    }
                    val actions = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 6, 2).apply {
                        add(StudioTheme.hardwareButton("+ CHAIN", StudioTheme.NeonCyan, filled = true) { onAddPluginToChain(custom.id) })
                        add(StudioTheme.hardwareButton("REMOVE", StudioTheme.TacCoral) { onUninstallCustomPlugin(custom.id) })
                    }
                    add(info, BorderLayout.CENTER)
                    add(actions, BorderLayout.EAST)
                }
                catalogHost.add(rowCard)
                catalogHost.add(Box.createVerticalStrut(5))
            }
            catalogHost.revalidate()
            catalogHost.repaint()
        }

        val natSig = tools.nativeLinuxPlugins.joinToString(",") { it.path }
        if (natSig != renderedNativeSignature) {
            renderedNativeSignature = natSig
            nativeScannerHost.removeAll()
            nativeScannerHost.add(StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(StudioTheme.hardwareLabel("LINUX NATIVE PLUGINS DETECTED (${tools.nativeLinuxPlugins.size} IN LV2 / LADSPA / VST2 / VST3 PATHS)", StudioTheme.NeonCyan, StudioTheme.TitleFont))
                add(Box.createVerticalStrut(4))
                if (tools.nativeLinuxPlugins.isEmpty()) {
                    add(StudioTheme.hardwareLabel("No system LV2/LADSPA/VST bundles found in /usr/lib/{lv2,ladspa,vst*} or ~/.lv2.", StudioTheme.TextSecondary, StudioTheme.BodyFont))
                } else {
                    tools.nativeLinuxPlugins.take(24).forEach { plug ->
                        add(StudioTheme.hardwareLabel("[${plug.formatTag}] ${plug.name}  ->  ${plug.path}", StudioTheme.TextSecondary, StudioTheme.MonoSmallFont))
                    }
                }
            })
            nativeScannerHost.revalidate()
            nativeScannerHost.repaint()
        }
    }

    private fun openCustomHexDialog() {
        val owner: Window? = SwingUtilities.getWindowAncestor(this)
        val dlg = JDialog(owner, "Edit 10-Stop Spectrogram Gradient", java.awt.Dialog.ModalityType.APPLICATION_MODAL)
        dlg.contentPane.background = StudioTheme.GunmetalDeck
        dlg.layout = BorderLayout(10, 10)

        val field = StudioTheme.styleTextField(JTextField(toolsState.specPalette.hexListText, 48))
        val errLabel = StudioTheme.hardwareLabel("Enter exactly 10 #RRGGBB hex stops separated by commas or spaces.", StudioTheme.TextSecondary, StudioTheme.BodyFont)

        val previewStrip = object : JPanel() {
            override fun getPreferredSize(): Dimension = StudioTheme.dim(520, 24)
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val stops = DesktopSpectrogramPalette.parseTenHexStops(field.text)
                    .getOrNull()?.stopsArgb ?: toolsState.specPalette.stopsArgb
                val sw = width.toFloat() / stops.size.coerceAtLeast(1)
                stops.forEachIndexed { idx, argb ->
                    g.color = Color((argb and 0xFFFFFFL).toInt())
                    g.fillRect((idx * sw).toInt(), 0, max(2, sw.toInt() + 1), height)
                }
            }
        }
        field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = previewStrip.repaint()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = previewStrip.repaint()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = previewStrip.repaint()
        })

        val center = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 12, 12, 8, 12)
            add(errLabel)
            add(Box.createVerticalStrut(8))
            add(previewStrip)
            add(Box.createVerticalStrut(8))
            add(field)
        }
        val btns = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 8, 8).apply {
            add(StudioTheme.hardwareButton("CANCEL", StudioTheme.TextSecondary) { dlg.dispose() })
            add(StudioTheme.hardwareButton("APPLY 10 STOPS", StudioTheme.NeonCyan, filled = true) {
                onApplyCustomHexStops(field.text).onSuccess {
                    dlg.dispose()
                }.onFailure { ex ->
                    errLabel.text = ex.message ?: "Invalid 10-stop hex list"
                    errLabel.foreground = StudioTheme.TacCoral
                }
            })
        }
        dlg.add(center, BorderLayout.CENTER)
        dlg.add(btns, BorderLayout.SOUTH)
        dlg.size = StudioTheme.scaledDialogSize(640, 230)
        dlg.setLocationRelativeTo(owner)
        dlg.isVisible = true
    }

    private fun normToTick(value: Float, minVal: Float, maxVal: Float): Int {
        val span = (maxVal - minVal).coerceAtLeast(1e-4f)
        return (((value - minVal) / span).coerceIn(0f, 1f) * 1000f).roundToInt()
    }

    private fun tickToValue(tick: Int, minVal: Float, maxVal: Float): Float {
        val norm = (tick / 1000f).coerceIn(0f, 1f)
        return minVal + norm * (maxVal - minVal)
    }

    private inner class CompactWaveformCanvas : JPanel() {
        private var dragAnchor: Float? = null

        init {
            background = StudioTheme.ObsidianChassis
            cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val f = (e.x.toFloat() / max(1, width)).coerceIn(0f, 1f)
                    if (e.isShiftDown) {
                        onSeekPlayhead(f)
                    } else {
                        dragAnchor = f
                        onSeekPlayhead(f)
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    val anchor = dragAnchor ?: return
                    val f = (e.x.toFloat() / max(1, width)).coerceIn(0f, 1f)
                    if (abs(f - anchor) > 0.02f) onSelectionChange(min(anchor, f), max(anchor, f))
                    dragAnchor = null
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val anchor = dragAnchor ?: return
                    val f = (e.x.toFloat() / max(1, width)).coerceIn(0f, 1f)
                    if (abs(f - anchor) > 0.02f) onSelectionChange(min(anchor, f), max(anchor, f))
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val h = height
            val midY = h / 2f

            val sx = (uiState.selectionStart * w).toInt()
            val ex = (uiState.selectionEnd * w).toInt()
            g2.color = StudioTheme.withAlpha(StudioTheme.AmberHot, 36)
            g2.fillRect(sx, 0, max(2, ex - sx), h)

            val env = uiState.processedEnvelope
            if (env.buckets > 0) {
                val step = w.toFloat() / env.buckets
                g2.stroke = StudioTheme.stroke(1.8f)
                for (i in 0 until env.buckets) {
                    val frac = i.toFloat() / env.buckets
                    val inSel = frac in uiState.selectionStart..uiState.selectionEnd
                    g2.color = if (inSel) StudioTheme.NeonCyan else StudioTheme.withAlpha(StudioTheme.NeonCyan, 120)
                    val x = (i * step + step * 0.5f).toInt()
                    val yTop = (midY - env.maxPeaks[i] * midY * 0.88f).toInt()
                    val yBot = (midY - env.minPeaks[i] * midY * 0.88f).toInt()
                    g2.drawLine(x, yTop, x, max(yTop + 1, yBot))
                }
            }
            val px = (playState.playheadFraction.coerceIn(0f, 1f) * w).toInt()
            g2.color = StudioTheme.EmeraldSignal
            g2.stroke = StudioTheme.stroke(2f)
            g2.drawLine(px, 0, px, h)
            g2.dispose()
        }
    }
}
