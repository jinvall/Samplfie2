package com.samplfie.desktop.ui

import com.samplfie.desktop.midi.VmcEngineState
import com.samplfie.desktop.studio.DesktopSequencerState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Instruments Spoke Panel for Desktop:
 * - 4 Live One-Shot Trigger Pads (Sample, 808 Kick, 909 Snare, Hi-Hat)
 * - 16-Step Sequencer Grid (4 lanes x 16 steps + Mute + Use Deck Sample + Swing + BPM)
 * - Linux Hardware MIDI / VMC quick summary & Jump to 64-Ch VMC Mapper
 */
class InstrumentsSeqPanel(
    private val onToggleSeqPlay: () -> Unit,
    private val onTriggerPad: (Int) -> Unit,
    private val onToggleStep: (Int, Int) -> Unit,
    private val onToggleLaneMute: (Int) -> Unit,
    private val onAssignDeckToLane: (Int) -> Unit,
    private val onSetBpm: (Float) -> Unit,
    private val onSetSwing: (Float) -> Unit,
    private val onBounceSeqToTimeline: () -> Unit,
    private val onRefreshMidi: () -> Unit,
    private val onOpenVmcMapper: () -> Unit
) : JPanel() {

    private var updating = false
    private var lastSeqState = DesktopSequencerState()

    private val playSeqBtn = StudioTheme.hardwareButton("RUN SEQ", StudioTheme.AmberHot, filled = true) { onToggleSeqPlay() }
    private val bpmLabel = StudioTheme.hardwareLabel("124 BPM", StudioTheme.AmberHot, StudioTheme.MonoFont)
    private val bpmSlider = StudioTheme.hardwareSlider(60, 180, 124)
    private val swingLabel = StudioTheme.hardwareLabel("SWING 8%", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val swingSlider = StudioTheme.hardwareSlider(0, 45, 8)
    private val midiSummary = StudioTheme.hardwareLabel("MIDI PORTS: 0 IN / 0 OUT", StudioTheme.NeonCyan, StudioTheme.MonoFont)

    private val stepButtons = Array(4) { arrayOfNulls<JButton>(16) }
    private val laneNameLabels = arrayOfNulls<javax.swing.JLabel>(4)
    private val laneMuteButtons = arrayOfNulls<JButton>(4)

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12)
        }

        // Top Sequencer Controls
        val ctrlBar = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = FlowLayout(FlowLayout.LEFT, 10, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 58)
            add(StudioTheme.hardwareLabel("16-STEP SEQUENCER & PADS", StudioTheme.AmberHot, StudioTheme.TitleFont))
            add(playSeqBtn)
            add(bpmLabel)
            bpmSlider.addChangeListener {
                bpmLabel.text = "${bpmSlider.value} BPM"
                if (!updating && !bpmSlider.valueIsAdjusting) onSetBpm(bpmSlider.value.toFloat())
            }
            add(bpmSlider)
            add(swingLabel)
            swingSlider.addChangeListener {
                swingLabel.text = "SWING ${swingSlider.value}%"
                if (!updating && !swingSlider.valueIsAdjusting) onSetSwing(swingSlider.value / 100f)
            }
            add(swingSlider)
            add(StudioTheme.hardwareButton("BOUNCE BEAT -> TRACK 3", StudioTheme.VioletMod) { onBounceSeqToTimeline() })
        }
        root.add(ctrlBar)
        root.add(Box.createVerticalStrut(8))

        // Live Performance Trigger Pads
        val padPanel = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BorderLayout(0, 6)
            maximumSize = Dimension(Int.MAX_VALUE, 96)
            add(StudioTheme.hardwareLabel("LIVE DRUM & SAMPLE PADS (CLICK TO TRIGGER ONE-SHOT)", StudioTheme.NeonCyan, StudioTheme.LabelFont), BorderLayout.NORTH)
            val grid = JPanel(GridLayout(1, 4, 10, 0)).apply {
                isOpaque = false
                val labels = listOf("PAD 1 · DECK/SAMPLE", "PAD 2 · 808 KICK", "PAD 3 · 909 SNARE", "PAD 4 · HI-HAT")
                val colors = listOf(StudioTheme.NeonCyan, StudioTheme.TacCoral, StudioTheme.AmberHot, StudioTheme.EmeraldSignal)
                for (i in 0 until 4) {
                    add(StudioTheme.hardwareButton(labels[i], colors[i], filled = true) { onTriggerPad(i) }.apply {
                        preferredSize = Dimension(140, 48)
                    })
                }
            }
            add(grid, BorderLayout.CENTER)
        }
        root.add(padPanel)
        root.add(Box.createVerticalStrut(10))

        // 4 x 16 Step Matrix
        val matrixPanel = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("16-STEP PATTERN MATRIX", StudioTheme.AmberHot, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(6))

            for (laneId in 0 until 4) {
                val laneRow = JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = false
                    maximumSize = Dimension(Int.MAX_VALUE, 46)
                    val leftCtrl = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                        isOpaque = false
                        preferredSize = Dimension(230, 40)
                        val nameLbl = StudioTheme.hardwareLabel("LANE ${laneId + 1}", StudioTheme.TextPrimary, StudioTheme.MonoFont).apply {
                            preferredSize = Dimension(92, 26)
                        }
                        laneNameLabels[laneId] = nameLbl
                        val muteBtn = StudioTheme.hardwareButton("M", StudioTheme.TacCoral) { onToggleLaneMute(laneId) }.apply {
                            preferredSize = Dimension(42, 28)
                        }
                        laneMuteButtons[laneId] = muteBtn
                        val useDeckBtn = StudioTheme.hardwareButton("DECK", StudioTheme.NeonCyan) { onAssignDeckToLane(laneId) }.apply {
                            preferredSize = Dimension(68, 28)
                        }
                        add(nameLbl)
                        add(muteBtn)
                        add(useDeckBtn)
                    }

                    val stepsGrid = JPanel(GridLayout(1, 16, 4, 0)).apply {
                        isOpaque = false
                        for (step in 0 until 16) {
                            val btn = JButton("${step + 1}").apply {
                                font = StudioTheme.LabelFont
                                isFocusPainted = false
                                foreground = StudioTheme.TextPrimary
                                background = StudioTheme.GunmetalDeck
                                addActionListener { onToggleStep(laneId, step) }
                            }
                            stepButtons[laneId][step] = btn
                            add(btn)
                        }
                    }

                    add(leftCtrl, BorderLayout.WEST)
                    add(stepsGrid, BorderLayout.CENTER)
                }
                add(laneRow)
                add(Box.createVerticalStrut(6))
            }
        }
        root.add(matrixPanel)
        root.add(Box.createVerticalStrut(10))

        // Linux HW MIDI + Open 64-Ch VMC
        val midiBar = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = FlowLayout(FlowLayout.LEFT, 10, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 64)
            add(StudioTheme.hardwareLabel("ALSA / HARDWARE MIDI & 64-CH VMC:", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(midiSummary)
            add(StudioTheme.hardwareButton("SCAN MIDI PORTS", StudioTheme.EmeraldSignal) { onRefreshMidi() })
            add(StudioTheme.hardwareButton("OPEN 64-CH VMC MAPPER", StudioTheme.NeonCyan, filled = true) { onOpenVmcMapper() })
        }
        root.add(midiBar)
        root.add(Box.createVerticalGlue())

        add(JScrollPane(root).apply {
            border = null
            background = StudioTheme.ObsidianChassis
            viewport.background = StudioTheme.ObsidianChassis
        }, BorderLayout.CENTER)
    }

    fun updateState(seq: DesktopSequencerState, vmc: VmcEngineState) {
        updating = true
        try {
            lastSeqState = seq
            playSeqBtn.text = if (seq.isPlaying) "STOP SEQ" else "RUN SEQ"
            bpmSlider.value = seq.bpm.toInt().coerceIn(60, 180)
            bpmLabel.text = "${seq.bpm.toInt()} BPM"
            swingSlider.value = (seq.swing * 100f).toInt().coerceIn(0, 45)
            swingLabel.text = "SWING ${(seq.swing * 100f).toInt()}%"
            midiSummary.text = "MIDI PORTS: ${vmc.hardwareInputs.size} IN / ${vmc.hardwareOutputs.size} OUT • ${vmc.lastEventSummary}"

            seq.lanes.forEachIndexed { laneIdx, lane ->
                laneNameLabels[laneIdx]?.text = lane.name
                laneMuteButtons[laneIdx]?.text = if (lane.isMuted) "MUTED" else "M"
                for (step in 0 until 16) {
                    val btn = stepButtons[laneIdx][step] ?: continue
                    val on = lane.steps.getOrElse(step) { false }
                    val isHead = seq.isPlaying && seq.currentStep == step
                    btn.background = when {
                        isHead && on -> StudioTheme.EmeraldSignal
                        isHead -> StudioTheme.ElevatedBezel
                        on -> if (step % 4 == 0) StudioTheme.AmberHot else StudioTheme.NeonCyan
                        step % 4 == 0 -> StudioTheme.BrushedPanel
                        else -> StudioTheme.ObsidianChassis
                    }
                    btn.foreground = if (on || isHead) Color.BLACK else StudioTheme.TextSecondary
                }
            }
        } finally {
            updating = false
        }
    }
}
