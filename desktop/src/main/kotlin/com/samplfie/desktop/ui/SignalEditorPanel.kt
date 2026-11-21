package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.audio.DesktopPlaybackState
import com.samplfie.desktop.model.SignalWaveformType
import com.samplfie.desktop.model.VisualizerMode
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
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
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive Desktop Signal & Audio Editor station matching Samplfie2 Android's Deck Visualizer:
 * - Dual-layer Waveform (ghost dry vs. cyan FX processed) with mouse-drag IN/OUT handles & scrub
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
    private val onGenerateSignal: (SignalWaveformType, Double) -> Unit
) : JPanel() {

    private var uiState = DesktopStudioUiState()
    private var playState = DesktopPlaybackState()

    private val waveformCanvas = WaveformCanvas()
    private val spectrumCanvas = SpectrumCanvas()

    private val headerMeta = StudioTheme.hardwareLabel("NO SIGNAL", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val selectionMeta = StudioTheme.hardwareLabel("IN 0.00s • OUT 0.00s", StudioTheme.AmberHot, StudioTheme.MonoFont)

    private val modeWaveBtn = StudioTheme.hardwareButton("WAVE", StudioTheme.NeonCyan) { onVisualizerMode(VisualizerMode.WAVE) }
    private val modeFftBtn = StudioTheme.hardwareButton("FFT", StudioTheme.VioletMod) { onVisualizerMode(VisualizerMode.FFT) }
    private val modeDualBtn = StudioTheme.hardwareButton("DUAL", StudioTheme.EmeraldSignal) { onVisualizerMode(VisualizerMode.DUAL) }
    private val playSelBtn = StudioTheme.hardwareButton("SEL ONLY: OFF", StudioTheme.AmberHot) { onTogglePlaySelection() }

    private val undoBtn = StudioTheme.hardwareButton("UNDO", StudioTheme.AmberHot) { onUndo() }
    private val resetBtn = StudioTheme.hardwareButton("RESET", StudioTheme.TacCoral) { onReset() }

    private val fadeInSlider = StudioTheme.hardwareSlider(0, 150, 10)
    private val fadeOutSlider = StudioTheme.hardwareSlider(0, 150, 15)

    private val genTypeBox = JComboBox(SignalWaveformType.entries.toTypedArray()).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        font = StudioTheme.MonoFont
    }
    private val genFreqSpinner = JSpinner(SpinnerNumberModel(440.0, 20.0, 8000.0, 10.0)).apply {
        font = StudioTheme.MonoFont
        preferredSize = Dimension(92, 30)
    }

    init {
        background = StudioTheme.ObsidianChassis
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12)

        // Top Visualizer Header & Mode Controls
        val topBar = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BorderLayout(8, 6)
            maximumSize = Dimension(Int.MAX_VALUE, 68)
            val left = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(headerMeta)
                add(selectionMeta)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(modeWaveBtn)
                add(modeFftBtn)
                add(modeDualBtn)
                add(playSelBtn)
            }
            add(left, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
        add(topBar)
        add(Box.createVerticalStrut(8))

        // Main Waveform + FFT Display Container
        val visPanel = StudioTheme.hardwarePanel(StudioTheme.ElevatedBezel).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            waveformCanvas.preferredSize = Dimension(680, 220)
            waveformCanvas.maximumSize = Dimension(Int.MAX_VALUE, 320)
            spectrumCanvas.preferredSize = Dimension(680, 130)
            spectrumCanvas.maximumSize = Dimension(Int.MAX_VALUE, 190)
            add(waveformCanvas)
            add(Box.createVerticalStrut(8))
            add(spectrumCanvas)
        }
        add(visPanel)
        add(Box.createVerticalStrut(8))

        // Signal Editing Actions Row
        val editPanel = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(Int.MAX_VALUE, 126)

            val btnRow = JPanel(GridLayout(1, 7, 6, 0)).apply {
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, 36)
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

            val fadeRow = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("FADE IN (ms):", StudioTheme.TextSecondary))
                add(fadeInSlider.apply { preferredSize = Dimension(130, 28) })
                add(StudioTheme.hardwareLabel("FADE OUT (ms):", StudioTheme.TextSecondary))
                add(fadeOutSlider.apply { preferredSize = Dimension(130, 28) })
                add(StudioTheme.hardwareButton("APPLY FADES", StudioTheme.EmeraldSignal) {
                    onApplyFades(fadeInSlider.value.toFloat(), fadeOutSlider.value.toFloat())
                })
            }
            add(fadeRow)
        }
        add(editPanel)
        add(Box.createVerticalStrut(8))

        // Test Signal Generator Strip
        val genPanel = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = FlowLayout(FlowLayout.LEFT, 10, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 58)
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
        add(genPanel)
        add(Box.createVerticalGlue())
    }

    fun updateState(ui: DesktopStudioUiState, play: DesktopPlaybackState) {
        uiState = ui
        playState = play

        val buf = ui.processedBuffer ?: ui.dryBuffer
        val sr = buf?.sampleRate ?: ui.rigConfig.sampleRate
        val dur = buf?.durationSeconds ?: 0f
        headerMeta.text = "${buf?.name ?: "EMPTY"} • ${"%.2f".format(dur)}s • ${sr}Hz • ${buf?.channels ?: 1}CH • RMS ${"%.2f".format(buf?.rmsLevel() ?: 0f)}"
        selectionMeta.text = "SEL IN ${"%.2f".format(ui.selectionStart * dur)}s  |  OUT ${"%.2f".format(ui.selectionEnd * dur)}s  |  LEN ${"%.2f".format((ui.selectionEnd - ui.selectionStart) * dur)}s"
        playSelBtn.text = if (ui.playSelectionOnly) "SEL ONLY: ON" else "SEL ONLY: OFF"
        undoBtn.isEnabled = ui.canUndo
        resetBtn.isEnabled = ui.rawBuffer != null

        waveformCanvas.isVisible = ui.visualizerMode != VisualizerMode.FFT
        spectrumCanvas.isVisible = ui.visualizerMode != VisualizerMode.WAVE

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
                g2.stroke = BasicStroke(2f)
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
            g2.stroke = BasicStroke(2f)
            g2.color = StudioTheme.AmberHot
            g2.drawLine(sx, 0, sx, h)
            g2.drawLine(ex, 0, ex, h)
            g2.font = Font("Monospaced", Font.BOLD, 10)
            g2.drawString("IN", sx + 4, 14)
            g2.drawString("OUT", (ex - 26).coerceAtLeast(sx + 20), 14)

            // Playhead
            val px = (playState.playheadFraction.coerceIn(0f, 1f) * w).toInt()
            g2.color = StudioTheme.EmeraldSignal
            g2.stroke = BasicStroke(2.2f)
            g2.drawLine(px, 0, px, h)

            g2.font = Font("Monospaced", Font.PLAIN, 10)
            g2.color = StudioTheme.TextSecondary
            g2.drawString("DRAG: SELECT RANGE  •  SHIFT+CLICK: SCRUB PLAYHEAD", 8, h - 8)
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
            val gap = 2f
            val barW = (width - 16f - gap * (n - 1)) / n
            val baseH = height - 20f

            for (i in bands.indices) {
                val amp = bands[i].coerceIn(0f, 1f)
                val bh = max(2f, amp * baseH)
                val x = 8f + i * (barW + gap)
                val y = height - 14f - bh
                g2.paint = GradientPaint(
                    x, y, StudioTheme.MagentaFx,
                    x, height - 14f, StudioTheme.NeonCyan
                )
                g2.fillRoundRect(x.toInt(), y.toInt(), barW.toInt().coerceAtLeast(3), bh.toInt(), 3, 3)
            }
            g2.font = Font("Monospaced", Font.BOLD, 10)
            g2.color = StudioTheme.TextSecondary
            g2.drawString("48-BAND RADIX-2 FFT SPECTRUM", 8, 13)
            g2.dispose()
        }
    }
}
