package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.audio.DesktopPlaybackState
import com.samplfie.desktop.audio.DesktopSpectrogramData
import com.samplfie.desktop.audio.DesktopSpectrogramPalette
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.model.VisualizerMode
import com.samplfie.desktop.tools.DesktopToolsState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reusable 10-Stop Gradient Spectrogram Canvas for both SignalEditorPanel and ToolsAndPluginsPanel.
 * Supports logarithmic/linear frequency grid overlays, real-time playhead tracking,
 * 2D Time x Frequency selection box (for Spectral Delete), and 10-stop gradient legend bar.
 */
class DesktopSpectrogramCanvas(
    private val onSelectionTimeChange: (Float, Float) -> Unit,
    private val onSelectionFreqChange: (Float, Float) -> Unit,
    private val onSeekPlayhead: (Float) -> Unit
) : JPanel() {

    private var specData: DesktopSpectrogramData = DesktopSpectrogramData.EMPTY
    private var palette: DesktopSpectrogramPalette = DesktopSpectrogramPalette.SAMPLFIE_NEON
    private var selectionStart: Float = 0f
    private var selectionEnd: Float = 1f
    private var freqLowHz: Float = 220f
    private var freqHighHz: Float = 3800f
    private var playheadFraction: Float = 0f

    private var cachedImage: BufferedImage? = null
    private var cachedData: DesktopSpectrogramData? = null
    private var cachedPaletteId: String? = null
    private var cachedStopsHash: Int = 0

    private var dragStartXNorm: Float? = null
    private var dragStartYNorm: Float? = null

    init {
        background = StudioTheme.ObsidianChassis
        cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val legendH = legendBarHeightPx()
                val plotH = (height - legendH).coerceAtLeast(16)
                val tx = (e.x.toFloat() / max(1, width)).coerceIn(0f, 1f)
                if (e.isShiftDown) {
                    onSeekPlayhead(tx)
                    return
                }
                val ty = (1f - (e.y.toFloat() / plotH)).coerceIn(0f, 1f)
                dragStartXNorm = tx
                dragStartYNorm = ty
                onSeekPlayhead(tx)
            }

            override fun mouseReleased(e: MouseEvent) {
                val sx = dragStartXNorm ?: return
                val sy = dragStartYNorm ?: return
                val legendH = legendBarHeightPx()
                val plotH = (height - legendH).coerceAtLeast(16)
                val ex = (e.x.toFloat() / max(1, width)).coerceIn(0f, 1f)
                val ey = (1f - (e.y.toFloat() / plotH)).coerceIn(0f, 1f)
                if (abs(ex - sx) > 0.015f) {
                    onSelectionTimeChange(min(sx, ex), max(sx, ex))
                }
                if (abs(ey - sy) > 0.02f && !specData.isEmpty) {
                    val f1 = specData.normYToFreqHz(min(sy, ey))
                    val f2 = specData.normYToFreqHz(max(sy, ey))
                    onSelectionFreqChange(f1, f2)
                }
                dragStartXNorm = null
                dragStartYNorm = null
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val sx = dragStartXNorm ?: return
                val sy = dragStartYNorm ?: return
                val legendH = legendBarHeightPx()
                val plotH = (height - legendH).coerceAtLeast(16)
                val ex = (e.x.toFloat() / max(1, width)).coerceIn(0f, 1f)
                val ey = (1f - (e.y.toFloat() / plotH)).coerceIn(0f, 1f)
                if (abs(ex - sx) > 0.015f) {
                    onSelectionTimeChange(min(sx, ex), max(sx, ex))
                }
                if (abs(ey - sy) > 0.02f && !specData.isEmpty) {
                    val f1 = specData.normYToFreqHz(min(sy, ey))
                    val f2 = specData.normYToFreqHz(max(sy, ey))
                    onSelectionFreqChange(f1, f2)
                }
            }
        })
    }

    fun updateSpectrogram(
        data: DesktopSpectrogramData,
        palette: DesktopSpectrogramPalette,
        selectionStart: Float,
        selectionEnd: Float,
        freqLowHz: Float,
        freqHighHz: Float,
        playheadFraction: Float
    ) {
        this.specData = data
        this.palette = palette
        this.selectionStart = selectionStart
        this.selectionEnd = selectionEnd
        this.freqLowHz = freqLowHz
        this.freqHighHz = freqHighHz
        this.playheadFraction = playheadFraction
        repaint()
    }

    private fun legendBarHeightPx(): Int = StudioTheme.s(18)

    private fun ensureBitmap(): BufferedImage? {
        if (specData.isEmpty) return null
        val stopsHash = palette.stopsArgb.hashCode()
        if (cachedImage != null && cachedData === specData && cachedPaletteId == palette.id && cachedStopsHash == stopsHash) {
            return cachedImage
        }
        val lut = DesktopSpectrogramPalette.buildLut256(palette.stopsArgb)
        val w = specData.frames
        val h = specData.bins
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until w) {
            for (b in 0 until h) {
                val mag = specData.valueAt(x, b).coerceIn(0f, 1f)
                val lutIdx = (mag * 255f).toInt().coerceIn(0, 255)
                val y = h - 1 - b
                img.setRGB(x, y, lut[lutIdx])
            }
        }
        cachedImage = img
        cachedData = specData
        cachedPaletteId = palette.id
        cachedStopsHash = stopsHash
        return img
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val w = width
        val h = height
        val legendH = legendBarHeightPx()
        val plotH = (h - legendH).coerceAtLeast(16)

        val bmp = ensureBitmap()
        if (bmp != null) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.drawImage(bmp, 0, 0, w, plotH, null)
        } else {
            g2.color = StudioTheme.ObsidianChassis
            g2.fillRect(0, 0, w, plotH)
        }

        // Frequency reference lines & labels
        if (!specData.isEmpty) {
            val markers = listOf(100f, 500f, 1000f, 4000f, 10000f)
            g2.font = StudioTheme.font("Monospaced", Font.BOLD, 9.5f)
            val fm = g2.fontMetrics
            for (hz in markers) {
                if (hz <= specData.minFreqHz || hz >= specData.maxFreqHz) continue
                val normY = specData.freqHzToNormY(hz)
                val py = ((1f - normY) * plotH).toInt()
                g2.color = StudioTheme.withAlpha(StudioTheme.TextPrimary, 48)
                g2.stroke = StudioTheme.stroke(1f)
                g2.drawLine(0, py, w, py)
                val lbl = if (hz >= 1000f) "${(hz / 1000f).toInt()}k" else "${hz.toInt()}"
                g2.color = StudioTheme.withAlpha(StudioTheme.TextPrimary, 175)
                g2.drawString(lbl, StudioTheme.s(4), (py - StudioTheme.s(2)).coerceAtLeast(fm.ascent))
            }

            // 2D Selection (Time x Freq) box for Spectral Delete
            val sx = (min(selectionStart, selectionEnd) * w).toInt()
            val ex = (max(selectionStart, selectionEnd) * w).toInt()
            val yLoNorm = specData.freqHzToNormY(min(freqLowHz, freqHighHz))
            val yHiNorm = specData.freqHzToNormY(max(freqLowHz, freqHighHz))
            val topY = ((1f - yHiNorm) * plotH).toInt()
            val botY = ((1f - yLoNorm) * plotH).toInt()
            val boxW = max(StudioTheme.s(6), ex - sx)
            val boxH = max(StudioTheme.s(8), botY - topY)

            g2.color = StudioTheme.withAlpha(StudioTheme.TacCoral, 55)
            g2.fillRect(sx, topY, boxW, boxH)
            g2.color = StudioTheme.TacCoral
            g2.stroke = StudioTheme.stroke(1.8f)
            g2.drawRect(sx, topY, boxW, boxH)

            val boxLabel = "${freqLowHz.toInt()}-${freqHighHz.toInt()} Hz"
            val lblY = if (topY > fm.height + StudioTheme.s(4)) topY - StudioTheme.s(3) else topY + fm.ascent + StudioTheme.s(3)
            g2.drawString(boxLabel, sx + StudioTheme.s(4), lblY)
        }

        // Playhead line
        val px = (playheadFraction.coerceIn(0f, 1f) * w).toInt()
        g2.color = StudioTheme.EmeraldSignal
        g2.stroke = StudioTheme.stroke(2f)
        g2.drawLine(px, 0, px, plotH)

        // Top-right badge
        g2.font = StudioTheme.font("Monospaced", Font.BOLD, 10f)
        val fmTop = g2.fontMetrics
        val badgeText = "${palette.name} • DRAG 2D BOX FOR SPECTRAL DELETE"
        g2.color = StudioTheme.withAlpha(StudioTheme.ObsidianChassis, 190)
        val badgeW = fmTop.stringWidth(badgeText) + StudioTheme.s(12)
        g2.fillRect(max(0, w - badgeW), 0, badgeW, fmTop.height + StudioTheme.s(4))
        g2.color = StudioTheme.NeonCyan
        g2.drawString(badgeText, max(StudioTheme.s(4), w - badgeW + StudioTheme.s(6)), fmTop.ascent + StudioTheme.s(2))

        // 10-Stop Gradient Legend Bar at bottom
        val yLegend = plotH
        val stops = palette.stopsArgb
        val stopW = w.toFloat() / stops.size.coerceAtLeast(1)
        stops.forEachIndexed { idx, argb ->
            g2.color = Color((argb and 0xFFFFFFL).toInt())
            val x0 = (idx * stopW).toInt()
            val x1 = ((idx + 1) * stopW).toInt()
            g2.fillRect(x0, yLegend, max(2, x1 - x0), legendH)
        }
        g2.color = StudioTheme.HairlineDivider
        g2.stroke = StudioTheme.stroke(1f)
        g2.drawLine(0, yLegend, w, yLegend)

        g2.font = StudioTheme.font("Monospaced", Font.BOLD, 9.5f)
        val fmLeg = g2.fontMetrics
        g2.color = StudioTheme.TextPrimary
        g2.drawString("-84 dB", StudioTheme.s(4), yLegend + (legendH + fmLeg.ascent - fmLeg.descent) / 2)
        val tenLabel = "10-STOP: ${palette.id.uppercase()}"
        g2.color = Color.WHITE
        g2.drawString(tenLabel, (w - fmLeg.stringWidth(tenLabel)) / 2, yLegend + (legendH + fmLeg.ascent - fmLeg.descent) / 2)
        g2.color = StudioTheme.ObsidianChassis
        val maxLbl = "0 dB"
        g2.drawString(maxLbl, w - fmLeg.stringWidth(maxLbl) - StudioTheme.s(4), yLegend + (legendH + fmLeg.ascent - fmLeg.descent) / 2)

        g2.dispose()
    }
}

/**
 * Interactive Desktop Signal & Audio Editor station matching Samplfie2 Android's Deck Visualizer:
 * - Dual-layer Waveform (ghost dry vs. cyan FX processed) with mouse-drag IN/OUT handles & scrub
 * - 10-Stop Gradient Spectrogram (`SPEC` & stacked `W+SPEC` modes) with 2D time x freq selection
 * - 48-Band Radix-2 FFT Spectrum Analyzer
 * - Zero-crossing snap, Extract, Fades, Reverse, DC Removal, Peak Normalize, Undo/Reset
 * - Built-in Calibrated Signal Generator (Sine, Square, Saw, Triangle, Log Chirp, White Noise)
 */
class SignalEditorPanel(
    private val onVisualizerMode: (VisualizerMode) -> Unit,
    private val onSelectionChange: (Float, Float) -> Unit,
    private val onSeekPlayhead: (Float) -> Unit,
    private val onTogglePlaySelection: () -> Unit,
    private val onZeroCrossSnap: () -> Unit,
    private val onExtract: () -> Unit,
    private val onApplyFades: (Float, Float) -> Unit,
    private val onReverse: () -> Unit,
    private val onDcFix: () -> Unit,
    private val onNormalize: () -> Unit,
    private val onUndo: () -> Unit,
    private val onReset: () -> Unit,
    private val onGenerateSignal: (SignalWaveformType, Double) -> Unit,
    private val onSpecFreqRange: (Float, Float) -> Unit = { _, _ -> },
    private val onOpenTools: () -> Unit = {}
) : JPanel() {

    private var uiState = DesktopStudioUiState()
    private var playState = DesktopPlaybackState()

    private val waveformCanvas = WaveformCanvas()
    private val spectrogramCanvas = DesktopSpectrogramCanvas(
        onSelectionTimeChange = { s, e -> onSelectionChange(s, e) },
        onSelectionFreqChange = { fLo, fHi -> onSpecFreqRange(fLo, fHi) },
        onSeekPlayhead = { f -> onSeekPlayhead(f) }
    )
    private val spectrumCanvas = SpectrumCanvas()

    private val headerMeta = StudioTheme.hardwareLabel("NO SIGNAL", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val selectionMeta = StudioTheme.hardwareLabel("IN 0.00s • OUT 0.00s", StudioTheme.AmberHot, StudioTheme.MonoFont)

    private val modeWaveBtn = StudioTheme.hardwareButton("WAVE", StudioTheme.NeonCyan) { onVisualizerMode(VisualizerMode.WAVE) }
    private val modeFftBtn = StudioTheme.hardwareButton("FFT", StudioTheme.VioletMod) { onVisualizerMode(VisualizerMode.FFT) }
    private val modeSpecBtn = StudioTheme.hardwareButton("SPEC", StudioTheme.MagentaFx) { onVisualizerMode(VisualizerMode.SPEC) }
    private val modeWSpecBtn = StudioTheme.hardwareButton("W+SPEC", StudioTheme.NeonCyan) { onVisualizerMode(VisualizerMode.W_SPEC) }
    private val modeDualBtn = StudioTheme.hardwareButton("DUAL", StudioTheme.EmeraldSignal) { onVisualizerMode(VisualizerMode.DUAL) }
    private val playSelBtn = StudioTheme.hardwareButton("SEL ONLY: OFF", StudioTheme.AmberHot) { onTogglePlaySelection() }
    private val openToolsBtn = StudioTheme.hardwareButton("TOOLS & PLUGINS", StudioTheme.MagentaFx) { onOpenTools() }

    private val undoBtn = StudioTheme.hardwareButton("UNDO", StudioTheme.AmberHot) { onUndo() }
    private val resetBtn = StudioTheme.hardwareButton("RESET", StudioTheme.TacCoral) { onReset() }

    private val fadeInSlider = StudioTheme.hardwareSlider(0, 150, 10)
    private val fadeOutSlider = StudioTheme.hardwareSlider(0, 150, 15)

    private val genTypeBox = StudioTheme.styleComboBox(JComboBox(SignalWaveformType.entries.toTypedArray()))
    private val genFreqSpinner = StudioTheme.styleSpinner(JSpinner(SpinnerNumberModel(440.0, 20.0, 8000.0, 10.0)), baseWidth = 96, baseHeight = 32)

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 10, 12, 10, 12)
        }

        // Top Visualizer Header & Mode Controls
        val topBar = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BorderLayout(8, 6)
            val left = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(headerMeta)
                add(selectionMeta)
            }
            val right = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 6, 2).apply {
                add(modeWaveBtn)
                add(modeWSpecBtn)
                add(modeSpecBtn)
                add(modeFftBtn)
                add(modeDualBtn)
                add(playSelBtn)
                add(openToolsBtn)
            }
            add(left, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
        root.add(topBar)
        root.add(Box.createVerticalStrut(8))

        // Main Waveform + 10-Stop Spectrogram + FFT Display Container
        val visPanel = StudioTheme.hardwarePanel(StudioTheme.ElevatedBezel).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPrefSize(waveformCanvas, 680, 190)
            StudioTheme.setScaledMaxSize(waveformCanvas, Int.MAX_VALUE, 380)
            StudioTheme.setScaledPrefSize(spectrogramCanvas, 680, 210)
            StudioTheme.setScaledMaxSize(spectrogramCanvas, Int.MAX_VALUE, 400)
            StudioTheme.setScaledPrefSize(spectrumCanvas, 680, 130)
            StudioTheme.setScaledMaxSize(spectrumCanvas, Int.MAX_VALUE, 240)
            add(waveformCanvas)
            add(Box.createVerticalStrut(6))
            add(spectrogramCanvas)
            add(Box.createVerticalStrut(6))
            add(spectrumCanvas)
        }
        root.add(visPanel)
        root.add(Box.createVerticalStrut(8))

        // Signal Editing Actions Row
        val editPanel = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            val btnRow = JPanel(GridLayout(1, 7, 6, 0)).apply {
                isOpaque = false
                add(StudioTheme.hardwareButton("EXTRACT", StudioTheme.AmberHot, filled = true) { onExtract() })
                add(StudioTheme.hardwareButton("ZERO-X", StudioTheme.NeonCyan) { onZeroCrossSnap() })
                add(StudioTheme.hardwareButton("REVERSE", StudioTheme.VioletMod) { onReverse() })
                add(StudioTheme.hardwareButton("DC FIX", StudioTheme.NeonCyan) { onDcFix() })
                add(StudioTheme.hardwareButton("NORM", StudioTheme.EmeraldSignal) { onNormalize() })
                add(undoBtn)
                add(resetBtn)
            }
            add(btnRow)
            add(Box.createVerticalStrut(8))

            val fadeRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareLabel("FADE IN (ms):", StudioTheme.TextSecondary))
                add(fadeInSlider.apply { StudioTheme.setScaledPrefSize(this, 140, 28) })
                add(StudioTheme.hardwareLabel("FADE OUT (ms):", StudioTheme.TextSecondary))
                add(fadeOutSlider.apply { StudioTheme.setScaledPrefSize(this, 140, 28) })
                add(StudioTheme.hardwareButton("APPLY FADES", StudioTheme.EmeraldSignal) {
                    onApplyFades(fadeInSlider.value.toFloat(), fadeOutSlider.value.toFloat())
                })
            }
            add(fadeRow)
        }
        root.add(editPanel)
        root.add(Box.createVerticalStrut(8))

        // Test Signal Generator Strip
        val genPanel = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 10, 4)
            add(StudioTheme.hardwareLabel("SIGNAL GENERATOR:", StudioTheme.VioletMod))
            add(genTypeBox)
            add(StudioTheme.hardwareLabel("FREQ (Hz):", StudioTheme.TextSecondary))
            add(genFreqSpinner)
            add(StudioTheme.hardwareButton("GENERATE SIGNAL", StudioTheme.VioletMod, filled = true) {
                val type = genTypeBox.selectedItem as? SignalWaveformType ?: SignalWaveformType.SINE
                val hz = (genFreqSpinner.value as? Number)?.toDouble() ?: 440.0
                onGenerateSignal(type, hz)
            })
            add(StudioTheme.hardwareButton("440Hz A4", StudioTheme.NeonCyan) {
                genTypeBox.selectedItem = SignalWaveformType.SINE
                genFreqSpinner.value = 440.0
                onGenerateSignal(SignalWaveformType.SINE, 440.0)
            })
            add(StudioTheme.hardwareButton("SWEEP", StudioTheme.MagentaFx) {
                genTypeBox.selectedItem = SignalWaveformType.CHIRP
                onGenerateSignal(SignalWaveformType.CHIRP, 55.0)
            })
        }
        root.add(genPanel)
        root.add(Box.createVerticalGlue())

        add(StudioTheme.styleScrollPane(JScrollPane(root)), BorderLayout.CENTER)
    }

    fun updateState(
        ui: DesktopStudioUiState,
        play: DesktopPlaybackState,
        tools: DesktopToolsState = DesktopToolsState()
    ) {
        uiState = ui
        playState = play

        val buf = ui.processedBuffer ?: ui.dryBuffer
        val sr = buf?.sampleRate ?: ui.rigConfig.sampleRate
        val fmtLabel = buf?.sampleFormat?.shortLabel ?: "16-BIT"
        val dur = buf?.durationSeconds ?: 0f
        headerMeta.text = "${buf?.name ?: "EMPTY"} • ${"%.2f".format(dur)}s • ${sr}Hz • $fmtLabel • ${buf?.channels ?: 1}CH • RMS ${"%.2f".format(buf?.rmsLevel() ?: 0f)}"
        selectionMeta.text = "SEL IN ${"%.2f".format(ui.selectionStart * dur)}s  |  OUT ${"%.2f".format(ui.selectionEnd * dur)}s  |  SPEC ${tools.specFreqLowHz.toInt()}-${tools.specFreqHighHz.toInt()} Hz"
        playSelBtn.text = if (ui.playSelectionOnly) "SEL ONLY: ON" else "SEL ONLY: OFF"
        undoBtn.isEnabled = ui.canUndo
        resetBtn.isEnabled = ui.rawBuffer != null

        val showWave = ui.visualizerMode == VisualizerMode.WAVE || ui.visualizerMode == VisualizerMode.W_SPEC || ui.visualizerMode == VisualizerMode.DUAL
        val showSpec = ui.visualizerMode == VisualizerMode.SPEC || ui.visualizerMode == VisualizerMode.W_SPEC
        val showFft = ui.visualizerMode == VisualizerMode.FFT || ui.visualizerMode == VisualizerMode.DUAL

        waveformCanvas.isVisible = showWave
        spectrogramCanvas.isVisible = showSpec
        spectrumCanvas.isVisible = showFft

        spectrogramCanvas.updateSpectrogram(
            data = ui.spectrogram,
            palette = tools.specPalette,
            selectionStart = ui.selectionStart,
            selectionEnd = ui.selectionEnd,
            freqLowHz = tools.specFreqLowHz,
            freqHighHz = tools.specFreqHighHz,
            playheadFraction = play.playheadFraction
        )
        waveformCanvas.repaint()
        spectrumCanvas.repaint()
    }

    private inner class WaveformCanvas : JPanel() {
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
                    if (abs(f - anchor) > 0.02f) {
                        onSelectionChange(min(anchor, f), max(anchor, f))
                    }
                    dragAnchor = null
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val anchor = dragAnchor ?: return
                    val f = (e.x.toFloat() / max(1, width)).coerceIn(0f, 1f)
                    if (abs(f - anchor) > 0.02f) {
                        onSelectionChange(min(anchor, f), max(anchor, f))
                    }
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

            // Grid
            g2.color = StudioTheme.withAlpha(StudioTheme.HairlineDivider, 120)
            g2.drawLine(0, midY.toInt(), w, midY.toInt())
            for (i in 1..7) {
                val gx = (w * i) / 8
                g2.drawLine(gx, 0, gx, h)
            }

            // Selection shading
            val sx = (uiState.selectionStart * w).toInt()
            val ex = (uiState.selectionEnd * w).toInt()
            g2.color = StudioTheme.withAlpha(StudioTheme.AmberHot, 32)
            g2.fillRect(sx, 0, max(2, ex - sx), h)

            // Dry ghost waveform
            val dryEnv = uiState.dryEnvelope
            if (dryEnv.buckets > 0) {
                g2.color = StudioTheme.withAlpha(StudioTheme.TextMuted, 110)
                val step = w.toFloat() / dryEnv.buckets
                for (i in 0 until dryEnv.buckets) {
                    val x = (i * step).toInt()
                    val yTop = (midY - dryEnv.maxPeaks[i] * midY * 0.88f).toInt()
                    val yBot = (midY - dryEnv.minPeaks[i] * midY * 0.88f).toInt()
                    g2.drawLine(x, yTop, x, yBot)
                }
            }

            // Active processed waveform
            val procEnv = uiState.processedEnvelope
            if (procEnv.buckets > 0) {
                val step = w.toFloat() / procEnv.buckets
                g2.stroke = StudioTheme.stroke(2f)
                for (i in 0 until procEnv.buckets) {
                    val frac = i.toFloat() / procEnv.buckets
                    val inSel = frac in uiState.selectionStart..uiState.selectionEnd
                    g2.color = if (inSel) StudioTheme.NeonCyan else StudioTheme.withAlpha(StudioTheme.NeonCyan, 130)
                    val x = (i * step + step * 0.5f).toInt()
                    val yTop = (midY - procEnv.maxPeaks[i] * midY * 0.9f).toInt()
                    val yBot = (midY - procEnv.minPeaks[i] * midY * 0.9f).toInt()
                    g2.drawLine(x, yTop, x, max(yTop + 1, yBot))
                }
            }

            // Selection IN / OUT handles
            g2.stroke = StudioTheme.stroke(2f)
            g2.color = StudioTheme.AmberHot
            g2.drawLine(sx, 0, sx, h)
            g2.drawLine(ex, 0, ex, h)
            g2.font = StudioTheme.font("Monospaced", Font.BOLD, 10.5f)
            val fm = g2.fontMetrics
            g2.drawString("IN", sx + StudioTheme.s(4), fm.ascent + StudioTheme.s(4))
            g2.drawString("OUT", (ex - fm.stringWidth("OUT") - StudioTheme.s(4)).coerceAtLeast(sx + StudioTheme.s(20)), fm.ascent + StudioTheme.s(4))

            // Playhead
            val px = (playState.playheadFraction.coerceIn(0f, 1f) * w).toInt()
            g2.color = StudioTheme.EmeraldSignal
            g2.stroke = StudioTheme.stroke(2.2f)
            g2.drawLine(px, 0, px, h)

            g2.font = StudioTheme.font("Monospaced", Font.PLAIN, 10.5f)
            val fmHint = g2.fontMetrics
            g2.color = StudioTheme.TextSecondary
            g2.drawString("DRAG: SELECT RANGE  •  SHIFT+CLICK: SCRUB PLAYHEAD", StudioTheme.s(8), h - fmHint.descent - StudioTheme.s(4))
            g2.dispose()
        }
    }

    private inner class SpectrumCanvas : JPanel() {
        init { background = StudioTheme.ObsidianChassis }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bands = if (playState.isPlaying) playState.liveSpectrum else uiState.staticSpectrum
            val n = max(1, bands.size)
            val gap = StudioTheme.sf(2f)
            val margin = StudioTheme.sf(8f)
            val barW = (width - margin * 2f - gap * (n - 1)) / n
            val bottomPad = StudioTheme.sf(14f)
            val topPad = StudioTheme.sf(20f)
            val baseH = (height - bottomPad - topPad).coerceAtLeast(8f)
            val arc = StudioTheme.s(3)

            for (i in bands.indices) {
                val amp = bands[i].coerceIn(0f, 1f)
                val bh = max(2f, amp * baseH)
                val x = margin + i * (barW + gap)
                val y = height - bottomPad - bh
                g2.paint = GradientPaint(
                    x, y, StudioTheme.MagentaFx,
                    x, height - bottomPad, StudioTheme.NeonCyan
                )
                g2.fillRoundRect(x.toInt(), y.toInt(), barW.toInt().coerceAtLeast(3), bh.toInt(), arc, arc)
            }
            g2.font = StudioTheme.font("Monospaced", Font.BOLD, 10.5f)
            val fm = g2.fontMetrics
            g2.color = StudioTheme.TextSecondary
            g2.drawString("48-BAND RADIX-2 FFT SPECTRUM", StudioTheme.s(8), fm.ascent + StudioTheme.s(3))
            g2.dispose()
        }
    }
}
