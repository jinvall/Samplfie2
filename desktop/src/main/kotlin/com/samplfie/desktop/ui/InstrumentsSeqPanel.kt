package com.samplfie.desktop.ui

import com.samplfie.desktop.drums.BeaterSide
import com.samplfie.desktop.drums.CustomDrumKitState
import com.samplfie.desktop.drums.DrumAddonType
import com.samplfie.desktop.drums.DrumKitPreset
import com.samplfie.desktop.drums.DrumKitStyle
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
    private val onOpenVmcMapper: () -> Unit,
    private val onToggleCompanionOverdubRx: () -> Unit = {},
    private val onClearPattern: () -> Unit = {},
    private val onUndoPattern: () -> Unit = {},
    private val onSetDrumStyle: (DrumKitStyle) -> Unit = {},
    private val onApplyDrumPreset: (DrumKitPreset) -> Unit = {},
    private val onToggleDrumArrangeMode: () -> Unit = {},
    private val onFlipDrumKitLeftHanded: () -> Unit = {},
    private val onSelectDrumSlot: (String?) -> Unit = {},
    private val onSetTargetBeater: (BeaterSide) -> Unit = {},
    private val onHitDrumPiece: (String, BeaterSide) -> Unit = { _, _ -> },
    private val onHitDrumAddon: (String) -> Unit = {},
    private val onMoveDrumPiece: (String, Float, Float) -> Unit = { _, _, _ -> },
    private val onAddDrumPiece: (String) -> Unit = {},
    private val onSwapDrumPiece: (String, String) -> Unit = { _, _ -> },
    private val onRemoveDrumPiece: (String) -> Unit = {},
    private val onUpdateDrumPieceParams: (String, Float?, Float?, Float?, Boolean?) -> Unit = { _, _, _, _, _ -> },
    private val onAttachDrumAddon: (DrumAddonType, String, BeaterSide) -> Unit = { _, _, _ -> },
    private val onRemoveDrumAddon: (String) -> Unit = {},
    private val onToggleDrumAddonCoupled: (String) -> Unit = {},
    private val onSetDrumAddonStyle: (String, DrumKitStyle) -> Unit = { _, _ -> },
    private val onSetDrumAddonBeaterSide: (String, BeaterSide) -> Unit = { _, _ -> },
    private val onRouteDrumPieceToSeqLane: (String, Int, BeaterSide) -> Unit = { _, _, _ -> },
    private val onSendDrumPieceToDeck: (String, BeaterSide) -> Unit = { _, _ -> }
) : JPanel() {

    private val drumKitPanel = DesktopDrumKitPanel(
        onSetStyle = onSetDrumStyle,
        onApplyPreset = onApplyDrumPreset,
        onToggleArrangeMode = onToggleDrumArrangeMode,
        onFlipLeftHanded = onFlipDrumKitLeftHanded,
        onSelectSlot = onSelectDrumSlot,
        onSetTargetBeater = onSetTargetBeater,
        onHitPiece = onHitDrumPiece,
        onHitAddon = onHitDrumAddon,
        onMovePiece = onMoveDrumPiece,
        onAddPiece = onAddDrumPiece,
        onSwapPiece = onSwapDrumPiece,
        onRemovePiece = onRemoveDrumPiece,
        onUpdatePieceParams = onUpdateDrumPieceParams,
        onAttachAddon = onAttachDrumAddon,
        onRemoveAddon = onRemoveDrumAddon,
        onToggleAddonCoupled = onToggleDrumAddonCoupled,
        onSetAddonStyle = onSetDrumAddonStyle,
        onSetAddonBeaterSide = onSetDrumAddonBeaterSide,
        onRouteToSeqLane = onRouteDrumPieceToSeqLane,
        onSendToDeck = onSendDrumPieceToDeck
    )

    private var updating = false
    private var lastSeqState = DesktopSequencerState()

    private val playSeqBtn = StudioTheme.hardwareButton("RUN SEQ", StudioTheme.AmberHot, filled = true) { onToggleSeqPlay() }
    private val bpmLabel = StudioTheme.hardwareLabel("124 BPM", StudioTheme.AmberHot, StudioTheme.MonoFont)
    private val bpmSlider = StudioTheme.hardwareSlider(60, 180, 124)
    private val swingLabel = StudioTheme.hardwareLabel("SWING 8%", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val swingSlider = StudioTheme.hardwareSlider(0, 45, 8)
    private val companionRxBtn = StudioTheme.hardwareButton("COMPANION OVERDUB RX: ON", StudioTheme.EmeraldSignal, filled = true) {
        onToggleCompanionOverdubRx()
    }
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
            StudioTheme.setScaledPadding(this, 10, 12, 10, 12)
        }

        root.add(drumKitPanel)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        // Top Sequencer Controls
        val ctrlBar = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 10, 4)
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
            add(companionRxBtn)
            add(StudioTheme.hardwareButton("UNDO PASS", StudioTheme.NeonCyan) { onUndoPattern() })
            add(StudioTheme.hardwareButton("CLEAR PAT", StudioTheme.TacCoral) { onClearPattern() })
        }
        root.add(ctrlBar)
        root.add(Box.createVerticalStrut(StudioTheme.s(8)))

        // Live Performance Trigger Pads
        val padPanel = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BorderLayout(0, StudioTheme.s(6))
            add(StudioTheme.hardwareLabel("LIVE DRUM & SAMPLE PADS (CLICK TO TRIGGER ONE-SHOT)", StudioTheme.NeonCyan, StudioTheme.LabelFont), BorderLayout.NORTH)
            val grid = JPanel(GridLayout(1, 4, StudioTheme.s(10), 0)).apply {
                isOpaque = false
                val labels = listOf("PAD 1 · DECK/SAMPLE", "PAD 2 · 808 KICK", "PAD 3 · 909 SNARE", "PAD 4 · HI-HAT")
                val colors = listOf(StudioTheme.NeonCyan, StudioTheme.TacCoral, StudioTheme.AmberHot, StudioTheme.EmeraldSignal)
                for (i in 0 until 4) {
                    add(StudioTheme.hardwareButton(labels[i], colors[i], filled = true) { onTriggerPad(i) }.apply {
                        StudioTheme.setScaledPrefSize(this, 140, 48)
                    })
                }
            }
            add(grid, BorderLayout.CENTER)
        }
        root.add(padPanel)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        // 4 x 16 Step Matrix
        val matrixPanel = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("16-STEP PATTERN MATRIX", StudioTheme.AmberHot, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(StudioTheme.s(6)))

            for (laneId in 0 until 4) {
                val laneRow = object : JPanel(BorderLayout(StudioTheme.s(8), 0)) {
                    override fun getMaximumSize(): Dimension =
                        Dimension(Int.MAX_VALUE, preferredSize.height.coerceAtLeast(StudioTheme.s(46)))
                }.apply {
                    isOpaque = false
                    val leftCtrl = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 4, 2).apply {
                        StudioTheme.setScaledPrefSize(this, 240, 40)
                        val nameLbl = StudioTheme.hardwareLabel("LANE ${laneId + 1}", StudioTheme.TextPrimary, StudioTheme.MonoFont).apply {
                            StudioTheme.setScaledPrefSize(this, 96, 26)
                        }
                        laneNameLabels[laneId] = nameLbl
                        val muteBtn = StudioTheme.hardwareButton("M", StudioTheme.TacCoral) { onToggleLaneMute(laneId) }.apply {
                            StudioTheme.setScaledPrefSize(this, 44, 28)
                        }
                        laneMuteButtons[laneId] = muteBtn
                        val useDeckBtn = StudioTheme.hardwareButton("DECK", StudioTheme.NeonCyan) { onAssignDeckToLane(laneId) }.apply {
                            StudioTheme.setScaledPrefSize(this, 68, 28)
                        }
                        add(nameLbl)
                        add(muteBtn)
                        add(useDeckBtn)
                    }

                    val stepsGrid = JPanel(GridLayout(1, 16, StudioTheme.s(4), 0)).apply {
                        isOpaque = false
                        for (step in 0 until 16) {
                            val btn = JButton("${step + 1}").apply {
                                StudioTheme.trackFont(this, "SansSerif", java.awt.Font.BOLD, 12f)
                                margin = java.awt.Insets(2, 2, 2, 2)
                                StudioTheme.setScaledMinSize(this, 28, 34)
                                StudioTheme.setScaledPrefSize(this, 36, 36)
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
                add(Box.createVerticalStrut(StudioTheme.s(6)))
            }
        }
        root.add(matrixPanel)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        // Linux HW MIDI + Open 64-Ch VMC
        val midiBar = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 10, 4)
            add(StudioTheme.hardwareLabel("ALSA / HARDWARE MIDI & 64-CH VMC:", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(midiSummary)
            add(StudioTheme.hardwareButton("SCAN MIDI PORTS", StudioTheme.EmeraldSignal) { onRefreshMidi() })
            add(StudioTheme.hardwareButton("OPEN 64-CH VMC MAPPER", StudioTheme.NeonCyan, filled = true) { onOpenVmcMapper() })
        }
        root.add(midiBar)
        root.add(Box.createVerticalGlue())

        add(StudioTheme.styleScrollPane(JScrollPane(root)), BorderLayout.CENTER)
    }

    fun updateState(seq: DesktopSequencerState, vmc: VmcEngineState, drumKit: CustomDrumKitState = CustomDrumKitState()) {
        drumKitPanel.updateState(drumKit)
        updating = true
        try {
            lastSeqState = seq
            playSeqBtn.text = if (seq.isPlaying) "STOP SEQ" else "RUN SEQ"
            bpmSlider.value = seq.bpm.toInt().coerceIn(60, 180)
            bpmLabel.text = "${seq.bpm.toInt()} BPM"
            swingSlider.value = (seq.swing * 100f).toInt().coerceIn(0, 45)
            swingLabel.text = "SWING ${(seq.swing * 100f).toInt()}%"
            companionRxBtn.text = if (seq.companionOverdubRx) {
                "COMPANION RX: ON (STEP ${seq.companionStepCursor + 1})"
            } else {
                "COMPANION RX: OFF"
            }
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
