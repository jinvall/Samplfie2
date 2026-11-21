package com.samplfie.desktop.ui

import com.samplfie.desktop.studio.DesktopLoopRefinerState
import com.samplfie.desktop.studio.DesktopLooperState
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSlider

/**
 * Samples Spoke Panel for Desktop:
 * - 4-Slot Synchronized Live Looper (Slots A-D, 1/2/4 bars, Active/Mute/Load/Refine/Clear, Bounce Mix -> Deck)
 * - Dedicated Loop Sample Refinement Editor (Waveform view, IN/OUT selection, Zero-Cross Snap, Trim,
 *   Linear Fade In/Out, Reverse, Peak Normalize, DC Offset Removal, Audition Loop, Commit Slot, Send -> Deck)
 */
class SamplesLooperPanel(
    private val onToggleLooperPlay: () -> Unit,
    private val onBpmChanged: (Float) -> Unit,
    private val onBounceLooperToDeck: () -> Unit,
    private val onSendDeckToSlot: (Int) -> Unit,
    private val onToggleSlotActive: (Int) -> Unit,
    private val onToggleSlotMute: (Int) -> Unit,
    private val onCycleSlotBars: (Int, Int) -> Unit,
    private val onClearSlot: (Int) -> Unit,
    private val onLoadSlotIntoRefiner: (Int) -> Unit,
    private val onRefinerSelection: (Float, Float) -> Unit,
    private val onSnapZeroCross: () -> Unit,
    private val onTrimLoop: () -> Unit,
    private val onApplyFades: (Float, Float) -> Unit,
    private val onReverseLoop: () -> Unit,
    private val onNormalizeLoop: () -> Unit,
    private val onRemoveDcLoop: () -> Unit,
    private val onResetLoop: () -> Unit,
    private val onAuditionLoop: () -> Unit,
    private val onCommitRefiner: () -> Unit,
    private val onSendRefinerToDeck: () -> Unit
) : JPanel() {

    private var updating = false
    private var currentLooper = DesktopLooperState()
    private var currentRefiner = DesktopLoopRefinerState()

    private val looperPlayBtn = StudioTheme.hardwareButton("RUN LOOPER", StudioTheme.EmeraldSignal, filled = true) { onToggleLooperPlay() }
    private val bpmLabel = StudioTheme.hardwareLabel("124 BPM", StudioTheme.AmberHot, StudioTheme.MonoFont)
    private val bpmSlider = StudioTheme.hardwareSlider(60, 180, 124)
    private val barProgress = JProgressBar(0, 100).apply {
        foreground = StudioTheme.EmeraldSignal
        background = StudioTheme.ObsidianChassis
        preferredSize = Dimension(160, 12)
    }

    private data class SlotWidgets(
        val title: JLabel,
        val info: JLabel,
        val loadBtn: javax.swing.JButton,
        val refineBtn: javax.swing.JButton,
        val activeBtn: javax.swing.JButton,
        val muteBtn: javax.swing.JButton,
        val barsBtn: javax.swing.JButton,
        val clearBtn: javax.swing.JButton
    )
    private val slotWidgets = mutableListOf<SlotWidgets>()

    private val refinerCanvas = RefinerWaveformCanvas()
    private val refinerStatus = StudioTheme.hardwareLabel("REFINER READY", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)
    private val startSlider = StudioTheme.hardwareSlider(0, 980, 0)
    private val endSlider = StudioTheme.hardwareSlider(20, 1000, 1000)
    private val fadeInSlider = StudioTheme.hardwareSlider(0, 200, 8)
    private val fadeOutSlider = StudioTheme.hardwareSlider(0, 200, 12)

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12)
        }

        // Looper Header Bar
        val looperHeader = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = FlowLayout(FlowLayout.LEFT, 10, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 58)
            add(StudioTheme.hardwareLabel("4-SLOT LIVE LOOPER", StudioTheme.EmeraldSignal, StudioTheme.TitleFont))
            add(looperPlayBtn)
            add(bpmLabel)
            bpmSlider.addChangeListener {
                bpmLabel.text = "${bpmSlider.value} BPM"
                if (!updating && !bpmSlider.valueIsAdjusting) onBpmChanged(bpmSlider.value.toFloat())
            }
            add(bpmSlider)
            add(barProgress)
            add(StudioTheme.hardwareButton("MIX LOOPS -> DECK", StudioTheme.NeonCyan) { onBounceLooperToDeck() })
        }
        root.add(looperHeader)
        root.add(Box.createVerticalStrut(8))

        // 4 Loop Slots (2x2 Grid)
        val slotsGrid = JPanel(GridLayout(2, 2, 10, 10)).apply {
            background = StudioTheme.ObsidianChassis
            maximumSize = Dimension(Int.MAX_VALUE, 220)
            for (slotId in 0 until 4) {
                val title = StudioTheme.hardwareLabel("LOOP ${'A' + slotId}", StudioTheme.NeonCyan, StudioTheme.TitleFont)
                val info = StudioTheme.hardwareLabel("EMPTY", StudioTheme.TextSecondary, StudioTheme.MonoFont)
                val loadBtn = StudioTheme.hardwareButton("LOAD DECK", StudioTheme.NeonCyan) { onSendDeckToSlot(slotId) }
                val refineBtn = StudioTheme.hardwareButton("REFINE", StudioTheme.EmeraldSignal) { onLoadSlotIntoRefiner(slotId) }
                val activeBtn = StudioTheme.hardwareButton("OFF", StudioTheme.AmberHot) { onToggleSlotActive(slotId) }
                val muteBtn = StudioTheme.hardwareButton("MUTE", StudioTheme.TacCoral) { onToggleSlotMute(slotId) }
                val barsBtn = StudioTheme.hardwareButton("1B", StudioTheme.VioletMod) {
                    val curBars = currentLooper.slots.getOrNull(slotId)?.bars ?: 1
                    val nextBars = when (curBars) { 1 -> 2; 2 -> 4; else -> 1 }
                    onCycleSlotBars(slotId, nextBars)
                }
                val clearBtn = StudioTheme.hardwareButton("CLR", StudioTheme.TextSecondary) { onClearSlot(slotId) }
                slotWidgets += SlotWidgets(title, info, loadBtn, refineBtn, activeBtn, muteBtn, barsBtn, clearBtn)

                val card = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    val topRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                        isOpaque = false
                        add(title)
                        add(info)
                    }
                    val btnRow1 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                        isOpaque = false
                        add(loadBtn)
                        add(refineBtn)
                        add(activeBtn)
                    }
                    val btnRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                        isOpaque = false
                        add(muteBtn)
                        add(barsBtn)
                        add(clearBtn)
                    }
                    add(topRow)
                    add(btnRow1)
                    add(btnRow2)
                }
                add(card)
            }
        }
        root.add(slotsGrid)
        root.add(Box.createVerticalStrut(10))

        // Loop Sample Refinement Editor
        val refinerPanel = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 10, 2)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("LOOP SAMPLE REFINEMENT EDITOR", StudioTheme.NeonCyan, StudioTheme.TitleFont))
                add(refinerStatus)
            }
            add(titleRow)
            add(Box.createVerticalStrut(6))

            refinerCanvas.preferredSize = Dimension(820, 170)
            refinerCanvas.minimumSize = Dimension(400, 140)
            refinerCanvas.maximumSize = Dimension(Int.MAX_VALUE, 195)
            add(refinerCanvas)
            add(Box.createVerticalStrut(6))

            val rangeRow = JPanel(GridLayout(1, 4, 10, 0)).apply {
                isOpaque = false
                maximumSize = Dimension(Int.MAX_VALUE, 52)
                startSlider.addChangeListener {
                    if (!updating && !startSlider.valueIsAdjusting) {
                        val s = startSlider.value / 1000f
                        val e = (endSlider.value / 1000f).coerceAtLeast(s + 0.02f)
                        onRefinerSelection(s, e)
                    }
                }
                endSlider.addChangeListener {
                    if (!updating && !endSlider.valueIsAdjusting) {
                        val e = endSlider.value / 1000f
                        val s = (startSlider.value / 1000f).coerceAtMost(e - 0.02f)
                        onRefinerSelection(s, e)
                    }
                }
                add(sliderBox("LOOP IN", startSlider))
                add(sliderBox("LOOP OUT", endSlider))
                add(sliderBox("FADE IN (ms)", fadeInSlider))
                add(sliderBox("FADE OUT (ms)", fadeOutSlider))
            }
            add(rangeRow)
            add(Box.createVerticalStrut(6))

            val toolsRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
                isOpaque = false
                add(StudioTheme.hardwareButton("ZERO-X SNAP", StudioTheme.NeonCyan) { onSnapZeroCross() })
                add(StudioTheme.hardwareButton("TRIM LOOP", StudioTheme.AmberHot) { onTrimLoop() })
                add(StudioTheme.hardwareButton("APPLY FADES", StudioTheme.VioletMod) {
                    onApplyFades(fadeInSlider.value.toFloat(), fadeOutSlider.value.toFloat())
                })
                add(StudioTheme.hardwareButton("REVERSE", StudioTheme.MagentaFx) { onReverseLoop() })
                add(StudioTheme.hardwareButton("NORMALIZE", StudioTheme.EmeraldSignal) { onNormalizeLoop() })
                add(StudioTheme.hardwareButton("DC FIX", StudioTheme.NeonCyan) { onRemoveDcLoop() })
                add(StudioTheme.hardwareButton("RESET", StudioTheme.TacCoral) { onResetLoop() })
                add(StudioTheme.hardwareButton("AUDITION LOOP", StudioTheme.EmeraldSignal, filled = true) { onAuditionLoop() })
                add(StudioTheme.hardwareButton("COMMIT SLOT", StudioTheme.AmberHot, filled = true) { onCommitRefiner() })
                add(StudioTheme.hardwareButton("SEND -> DECK", StudioTheme.NeonCyan) { onSendRefinerToDeck() })
            }
            add(toolsRow)
        }
        root.add(refinerPanel)
        root.add(Box.createVerticalGlue())

        add(JScrollPane(root).apply {
            border = null
            background = StudioTheme.ObsidianChassis
            viewport.background = StudioTheme.ObsidianChassis
        }, BorderLayout.CENTER)
    }

    fun updateState(looper: DesktopLooperState, refiner: DesktopLoopRefinerState) {
        updating = true
        try {
            currentLooper = looper
            currentRefiner = refiner

            looperPlayBtn.text = if (looper.isRunning) "STOP LOOPER" else "RUN LOOPER"
            bpmSlider.value = looper.bpm.toInt().coerceIn(60, 180)
            bpmLabel.text = "${looper.bpm.toInt()} BPM"
            barProgress.value = (looper.barProgress * 100f).toInt().coerceIn(0, 100)

            looper.slots.forEachIndexed { idx, slot ->
                val w = slotWidgets.getOrNull(idx) ?: return@forEachIndexed
                w.title.text = "LOOP ${'A' + idx} ${if (idx == refiner.selectedSlotId) "[REFINING]" else ""}"
                val buf = slot.buffer
                w.info.text = if (buf != null) "${buf.name.take(14)} (${"%.2f".format(buf.durationSeconds)}s)" else "EMPTY"
                w.activeBtn.text = if (slot.isActive) "ACTIVE" else "OFF"
                w.muteBtn.text = if (slot.isMuted) "MUTED" else "MUTE"
                w.barsBtn.text = "${slot.bars}B"
            }

            refinerStatus.text = refiner.statusNote
            startSlider.value = (refiner.loopStartFrac * 1000f).toInt().coerceIn(0, 980)
            endSlider.value = (refiner.loopEndFrac * 1000f).toInt().coerceIn(20, 1000)
            fadeInSlider.value = refiner.fadeInMs.toInt().coerceIn(0, 200)
            fadeOutSlider.value = refiner.fadeOutMs.toInt().coerceIn(0, 200)
            refinerCanvas.repaint()
        } finally {
            updating = false
        }
    }

    private fun sliderBox(title: String, slider: JSlider): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        val lbl = StudioTheme.hardwareLabel("$title: ${slider.value}", StudioTheme.TextSecondary)
        slider.addChangeListener { lbl.text = "$title: ${slider.value}" }
        add(lbl, BorderLayout.NORTH)
        add(slider, BorderLayout.CENTER)
    }

    private inner class RefinerWaveformCanvas : JPanel() {
        private var dragStart = 0f

        init {
            background = StudioTheme.ObsidianChassis
            border = StudioTheme.panelBorder(StudioTheme.HairlineDivider, 1)
            cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    dragStart = (e.x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                }

                override fun mouseReleased(e: MouseEvent) {
                    val end = (e.x.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    if (kotlin.math.abs(end - dragStart) > 0.02f) {
                        onRefinerSelection(minOf(dragStart, end), maxOf(dragStart, end))
                    }
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val w = width.toFloat()
            val h = height.toFloat()
            val midY = h / 2f

            g2.color = StudioTheme.withAlpha(StudioTheme.HairlineDivider, 140)
            g2.drawLine(0, midY.toInt(), width, midY.toInt())

            val sx = (currentRefiner.loopStartFrac * w).toInt()
            val ex = (currentRefiner.loopEndFrac * w).toInt()
            g2.color = StudioTheme.withAlpha(StudioTheme.EmeraldSignal, 36)
            g2.fillRect(sx, 0, (ex - sx).coerceAtLeast(2), height)

            val env = currentRefiner.envelope
            val n = env.buckets
            if (n > 0) {
                val barW = (w / n).coerceAtLeast(2f)
                for (i in 0 until n) {
                    val frac = i.toFloat() / n
                    val inSel = frac in currentRefiner.loopStartFrac..currentRefiner.loopEndFrac
                    val topAmp = env.maxPeaks[i].coerceIn(0f, 1f) * (h * 0.44f)
                    val botAmp = kotlin.math.abs(env.minPeaks[i]).coerceIn(0f, 1f) * (h * 0.44f)
                    val x = (i * w / n).toInt()
                    g2.color = if (inSel) StudioTheme.EmeraldSignal else StudioTheme.withAlpha(StudioTheme.NeonCyan, 120)
                    g2.fillRect(
                        x,
                        (midY - topAmp).toInt(),
                        (barW - 1f).toInt().coerceAtLeast(1),
                        (topAmp + botAmp).toInt().coerceAtLeast(2)
                    )
                }
            }

            g2.color = StudioTheme.AmberHot
            g2.stroke = BasicStroke(2f)
            g2.drawLine(sx, 0, sx, height)
            g2.drawLine(ex, 0, ex, height)

            g2.dispose()
        }
    }
}
