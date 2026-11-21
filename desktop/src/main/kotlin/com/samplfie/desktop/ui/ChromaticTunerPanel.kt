package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.model.TunerInputSource
import com.samplfie.desktop.model.TuningPreset
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Precision Chromatic & Strobe Tuner matching Samplfie2's circular studio dial aesthetic.
 * Tracks Linux Mic input, Editor Signal selection, or incoming Android SMP2 network streams.
 */
class ChromaticTunerPanel(
    private val onSelectSource: (TunerInputSource) -> Unit,
    private val onSelectPreset: (TuningPreset) -> Unit,
    private val onSetA4Reference: (Double) -> Unit,
    private val onAnalyzeBufferNow: () -> Unit,
    private val onPlayReferencePitch: (Double) -> Unit
) : JPanel() {

    private var uiState = DesktopStudioUiState()

    private val gaugeCanvas = TunerGaugeCanvas()
    private val presetNotesPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply { isOpaque = false }

    private val srcMicBtn = StudioTheme.hardwareButton("LINUX MIC", StudioTheme.TacCoral) { onSelectSource(TunerInputSource.LIVE_MIC) }
    private val srcDeckBtn = StudioTheme.hardwareButton("EDITOR SIGNAL", StudioTheme.NeonCyan) { onSelectSource(TunerInputSource.DECK_BUFFER) }
    private val srcNetBtn = StudioTheme.hardwareButton("SMP2 STREAM", StudioTheme.AmberHot) { onSelectSource(TunerInputSource.SMP2_STREAM) }

    private val presetBox = JComboBox(TuningPreset.entries.toTypedArray()).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        font = StudioTheme.MonoFont
        addActionListener {
            (selectedItem as? TuningPreset)?.let { onSelectPreset(it) }
        }
    }

    private val a4Spinner = JSpinner(SpinnerNumberModel(440.0, 432.0, 448.0, 0.5)).apply {
        font = StudioTheme.MonoFont
        preferredSize = Dimension(88, 30)
        addChangeListener {
            val hz = (value as? Number)?.toDouble() ?: 440.0
            onSetA4Reference(hz)
        }
    }

    init {
        background = StudioTheme.ObsidianChassis
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12)

        // Input Source & Calibration Header
        val topControls = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = BorderLayout(8, 6)
            maximumSize = Dimension(Int.MAX_VALUE, 64)

            val srcRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("TUNER INPUT:", StudioTheme.TextSecondary))
                add(srcMicBtn)
                add(srcDeckBtn)
                add(srcNetBtn)
                add(StudioTheme.hardwareButton("SCAN BUFFER", StudioTheme.NeonCyan) { onAnalyzeBufferNow() })
            }
            val calRow = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("A4 REF (Hz):", StudioTheme.TextSecondary))
                add(a4Spinner)
                add(StudioTheme.hardwareLabel("PRESET:", StudioTheme.TextSecondary))
                add(presetBox)
            }
            add(srcRow, BorderLayout.WEST)
            add(calRow, BorderLayout.EAST)
        }
        add(topControls)
        add(Box.createVerticalStrut(10))

        // Main Circular Tuner Dial & Strobe Readout
        val dialPanel = StudioTheme.hardwarePanel(StudioTheme.ElevatedBezel).apply {
            layout = BorderLayout()
            gaugeCanvas.preferredSize = Dimension(680, 340)
            add(gaugeCanvas, BorderLayout.CENTER)
        }
        add(dialPanel)
        add(Box.createVerticalStrut(10))

        // Reference Pitch Synthesizer Pads
        val refPanel = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(Int.MAX_VALUE, 104)
            add(StudioTheme.hardwareLabel("CLICK A TARGET NOTE TO PLAY CALIBRATED REFERENCE PITCH:", StudioTheme.VioletMod))
            add(Box.createVerticalStrut(4))
            add(presetNotesPanel)
        }
        add(refPanel)
        add(Box.createVerticalGlue())

        rebuildPresetButtons(TuningPreset.CHROMATIC, 440.0)
    }

    private var lastPreset: TuningPreset? = null
    private var lastA4: Double = -1.0

    fun updateState(ui: DesktopStudioUiState) {
        uiState = ui
        if (lastPreset != ui.tuningPreset || abs(lastA4 - ui.rigConfig.a4ReferenceHz) > 0.01) {
            lastPreset = ui.tuningPreset
            lastA4 = ui.rigConfig.a4ReferenceHz
            rebuildPresetButtons(ui.tuningPreset, ui.rigConfig.a4ReferenceHz)
        }
        gaugeCanvas.repaint()
    }

    private fun rebuildPresetButtons(preset: TuningPreset, a4Hz: Double) {
        presetNotesPanel.removeAll()
        val scale = a4Hz / 440.0
        for ((note, baseHz) in preset.notes) {
            val scaledHz = baseHz * scale
            presetNotesPanel.add(
                StudioTheme.hardwareButton("$note (${"%.1f".format(scaledHz)}Hz)", StudioTheme.EmeraldSignal) {
                    onPlayReferencePitch(scaledHz)
                }
            )
        }
        presetNotesPanel.revalidate()
        presetNotesPanel.repaint()
    }

    private inner class TunerGaugeCanvas : JPanel() {
        init { background = StudioTheme.ObsidianChassis }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val r = uiState.tunerReading
            val cx = width / 2.0
            val cy = height * 0.56
            val radius = min(width * 0.32, height * 0.44)

            // Outer dial arc (-150 deg to +150 deg from top -> Swing angles: 210 down to -30)
            g2.color = StudioTheme.HairlineDivider
            g2.stroke = BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(Arc2D.Double(cx - radius, cy - radius, radius * 2, radius * 2, 20.0, 140.0, Arc2D.OPEN))

            // In-tune center sweet-spot arc (±3 cents)
            g2.color = StudioTheme.EmeraldSignal
            g2.stroke = BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(Arc2D.Double(cx - radius, cy - radius, radius * 2, radius * 2, 85.0, 10.0, Arc2D.OPEN))

            // Cents tick marks (-50 .. +50)
            for (c in -50..50 step 5) {
                val angleDeg = 90.0 - (c / 50.0) * 65.0
                val rad = Math.toRadians(angleDeg)
                val tIn = radius * if (c % 10 == 0) 0.86 else 0.91
                val tOut = radius * 1.03
                g2.color = if (c == 0) StudioTheme.EmeraldSignal else StudioTheme.TextMuted
                g2.stroke = BasicStroke(if (c == 0) 3f else 1.4f)
                g2.drawLine(
                    (cx + tIn * cos(rad)).toInt(), (cy - tIn * sin(rad)).toInt(),
                    (cx + tOut * cos(rad)).toInt(), (cy - tOut * sin(rad)).toInt()
                )
            }

            // Needle
            if (r.detected) {
                val clampedCents = r.centsOffset.coerceIn(-50.0, 50.0)
                val needleDeg = 90.0 - (clampedCents / 50.0) * 65.0
                val needleRad = Math.toRadians(needleDeg)
                val needleColor = when {
                    r.isInTune -> StudioTheme.EmeraldSignal
                    abs(clampedCents) <= 12.0 -> StudioTheme.AmberHot
                    else -> StudioTheme.TacCoral
                }
                g2.color = needleColor
                g2.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(
                    cx.toInt(), cy.toInt(),
                    (cx + radius * 0.88 * cos(needleRad)).toInt(),
                    (cy - radius * 0.88 * sin(needleRad)).toInt()
                )
                g2.fillOval((cx - 8).toInt(), (cy - 8).toInt(), 16, 16)
            }

            // Center Note Readout
            val noteText = r.fullNote
            g2.font = StudioTheme.MonoLargeFont
            val fmNote = g2.fontMetrics
            g2.color = when {
                !r.detected -> StudioTheme.TextMuted
                r.isInTune -> StudioTheme.EmeraldSignal
                else -> StudioTheme.NeonCyan
            }
            g2.drawString(noteText, (cx - fmNote.stringWidth(noteText) / 2).toFloat(), (cy + 58).toFloat())

            val hzText = if (r.detected) {
                "${"%.2f".format(r.frequencyHz)} Hz   |   ${if (r.centsOffset >= 0) "+" else ""}${"%.1f".format(r.centsOffset)} ¢   |   CONF ${(r.confidence * 100).toInt()}%"
            } else {
                "AWAITING TONAL SIGNAL (${uiState.tunerSource.label})"
            }
            g2.font = StudioTheme.MonoFont
            val fmHz = g2.fontMetrics
            g2.color = StudioTheme.TextPrimary
            g2.drawString(hzText, (cx - fmHz.stringWidth(hzText) / 2).toFloat(), (cy + 86).toFloat())

            val targetText = if (r.detected) {
                "TARGET: ${r.fullNote} @ ${"%.2f".format(r.targetFrequencyHz)} Hz  (A4=${"%.1f".format(uiState.rigConfig.a4ReferenceHz)} Hz)"
            } else {
                "ACTIVE INPUT: ${uiState.tunerSource.label} • PRESET: ${uiState.tuningPreset.label}"
            }
            g2.font = Font("Monospaced", Font.PLAIN, 11)
            val fmTgt = g2.fontMetrics
            g2.color = StudioTheme.TextSecondary
            g2.drawString(targetText, (cx - fmTgt.stringWidth(targetText) / 2).toFloat(), (cy + 108).toFloat())

            g2.dispose()
        }
    }
}
