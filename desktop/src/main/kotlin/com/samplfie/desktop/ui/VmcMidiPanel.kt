package com.samplfie.desktop.ui

import com.samplfie.desktop.midi.VmcEngineState
import com.samplfie.desktop.midi.VmcMappingRule
import com.samplfie.desktop.midi.VmcMsgType
import com.samplfie.desktop.midi.VmcRouteMode
import com.samplfie.desktop.midi.VmcTarget
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * 64-Channel Mappable Virtual MIDI Controller (VMC) Station:
 * - 4 MIDI Buses x 16 Channels = Channels 1..64 (plus CH 0 ANY matching)
 * - Route Mode: LOCAL + PEER (companion control), LOCAL ONLY, or PEER VMC TX
 * - Interactive Virtual Pad Bank (8 trigger pads) & 8 Assignable Continuous CC Sliders
 * - Full Mappable Routing Table with 1-Click MIDI Learn & New Rule Builder
 * - Live ALSA/Hardware MIDI Port Bridge & Telemetry Monitor
 */
class VmcMidiPanel(
    private val onSelectBank: (Int) -> Unit,
    private val onSelectChannel64: (Int) -> Unit,
    private val onSetRouteMode: (VmcRouteMode) -> Unit,
    private val onEmitControl: (VmcMsgType, Int, Float, Int) -> Unit,
    private val onArmLearnRule: (String?) -> Unit,
    private val onArmLearnNew: (VmcTarget) -> Unit,
    private val onUpsertRule: (VmcMappingRule) -> Unit,
    private val onRemoveRule: (String) -> Unit,
    private val onRefreshHardwareMidi: () -> Unit
) : JPanel() {

    private var updating = false
    private var lastState = VmcEngineState()

    private val chSpinner = JSpinner(SpinnerNumberModel(1, 1, 64, 1)).apply {
        preferredSize = Dimension(68, 30)
        font = StudioTheme.MonoFont
    }
    private val routeBox = JComboBox(VmcRouteMode.entries.toTypedArray()).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        font = StudioTheme.MonoFont
    }
    private val statusLabel = StudioTheme.hardwareLabel("VMC 64-CH READY", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val hwPortsLabel = StudioTheme.hardwareLabel("HW MIDI: 0 IN / 0 OUT", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)

    private val newChSpinner = JSpinner(SpinnerNumberModel(1, 0, 64, 1)).apply { preferredSize = Dimension(62, 28) }
    private val newTypeBox = JComboBox(VmcMsgType.entries.toTypedArray()).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
    }
    private val newNumSpinner = JSpinner(SpinnerNumberModel(1, 0, 127, 1)).apply { preferredSize = Dimension(62, 28) }
    private val newTargetBox = JComboBox(VmcTarget.entries.toTypedArray()).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
    }

    private val mappingsHost = JPanel().apply {
        background = StudioTheme.ObsidianChassis
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val logLabel = StudioTheme.hardwareLabel("LOG: --", StudioTheme.TextSecondary, StudioTheme.MonoFont)

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12)
        }

        // Top 64-Channel Bank & Routing Bar
        val headerBar = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(Int.MAX_VALUE, 108)
            val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("VMC 64-CH MIDI CONTROLLER", StudioTheme.NeonCyan, StudioTheme.TitleFont))
                val banks = listOf("B1: CH 1-16", "B2: CH 17-32", "B3: CH 33-48", "B4: CH 49-64")
                banks.forEachIndexed { idx, label ->
                    add(StudioTheme.hardwareButton(label, StudioTheme.NeonCyan) { onSelectBank(idx) })
                }
                add(StudioTheme.hardwareLabel("ACTIVE CH (1..64):", StudioTheme.TextSecondary))
                chSpinner.addChangeListener {
                    if (!updating) onSelectChannel64((chSpinner.value as? Int) ?: 1)
                }
                add(chSpinner)
                add(StudioTheme.hardwareLabel("ROUTE:", StudioTheme.TextSecondary))
                routeBox.addActionListener {
                    if (!updating) (routeBox.selectedItem as? VmcRouteMode)?.let(onSetRouteMode)
                }
                add(routeBox)
            }
            val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 10, 2)).apply {
                isOpaque = false
                add(statusLabel)
                add(hwPortsLabel)
                add(StudioTheme.hardwareButton("RESCAN ALSA MIDI", StudioTheme.EmeraldSignal) { onRefreshHardwareMidi() })
            }
            add(row1)
            add(row2)
        }
        root.add(headerBar)
        root.add(Box.createVerticalStrut(8))

        // Interactive Surface: 8 Virtual Pads + 8 Virtual Sliders
        val surfacePanel = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(Int.MAX_VALUE, 240)
            add(StudioTheme.hardwareLabel("VIRTUAL PERFORMANCE SURFACE (USES ACTIVE CH OR MAPPED DEFAULTS & TRANSMITS VMC2)", StudioTheme.AmberHot, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(6))

            val padsRow = JPanel(GridLayout(1, 8, 6, 0)).apply {
                isOpaque = false
                val pads = listOf(
                    Triple("PLAY", 60, 1),
                    Triple("LOOP", 61, 1),
                    Triple("SYNC", 62, 1),
                    Triple("STOMP", 64, 2),
                    Triple("LOOP A", 36, 17),
                    Triple("PAD 1", 40, 33),
                    Triple("PAD 2", 41, 33),
                    Triple("HANDOFF", 96, 64)
                )
                pads.forEach { (title, note, chHint) ->
                    add(StudioTheme.hardwareButton("$title\nC$chHint", StudioTheme.NeonCyan, filled = true) {
                        val active = lastState.activeChannel64
                        onEmitControl(VmcMsgType.NOTE_ON, note, 1f, active)
                    })
                }
            }
            add(padsRow)
            add(Box.createVerticalStrut(8))

            val fadersRow = JPanel(GridLayout(1, 8, 6, 0)).apply {
                isOpaque = false
                val faders = listOf(
                    "WAH CC1" to 1,
                    "DRIVE CC71" to 71,
                    "TILT CC74" to 74,
                    "REV CC91" to 91,
                    "ECHO CC93" to 93,
                    "WET CC7" to 7,
                    "BPM CC14" to 14,
                    "PITCH CC21" to 21
                )
                faders.forEach { (title, cc) ->
                    val slider = StudioTheme.hardwareSlider(0, 127, 64).apply {
                        addChangeListener {
                            if (!valueIsAdjusting) {
                                onEmitControl(VmcMsgType.CC, cc, value / 127f, lastState.activeChannel64)
                            }
                        }
                    }
                    add(JPanel(BorderLayout()).apply {
                        isOpaque = false
                        add(StudioTheme.hardwareLabel(title, StudioTheme.TextSecondary), BorderLayout.NORTH)
                        add(slider, BorderLayout.CENTER)
                    })
                }
            }
            add(fadersRow)
        }
        root.add(surfacePanel)
        root.add(Box.createVerticalStrut(8))

        // Add / Learn New 64-Ch Rule Bar
        val builderBar = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = FlowLayout(FlowLayout.LEFT, 8, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 58)
            add(StudioTheme.hardwareLabel("ADD 64-CH MAP:", StudioTheme.EmeraldSignal, StudioTheme.LabelFont))
            add(StudioTheme.hardwareLabel("CH (0=ANY..64):", StudioTheme.TextSecondary))
            add(newChSpinner)
            add(newTypeBox)
            add(StudioTheme.hardwareLabel("CC/NOTE:", StudioTheme.TextSecondary))
            add(newNumSpinner)
            add(newTargetBox)
            add(StudioTheme.hardwareButton("ADD RULE", StudioTheme.EmeraldSignal, filled = true) {
                val ch = (newChSpinner.value as? Int) ?: 1
                val typ = newTypeBox.selectedItem as? VmcMsgType ?: VmcMsgType.CC
                val num = (newNumSpinner.value as? Int) ?: 1
                val tgt = newTargetBox.selectedItem as? VmcTarget ?: VmcTarget.DECK_PLAY_PAUSE
                onUpsertRule(VmcMappingRule("rule_${System.nanoTime()}", ch, typ, num, tgt))
            })
            add(StudioTheme.hardwareButton("LEARN NEW -> TARGET", StudioTheme.AmberHot) {
                val tgt = newTargetBox.selectedItem as? VmcTarget ?: VmcTarget.DECK_PLAY_PAUSE
                onArmLearnNew(tgt)
            })
        }
        root.add(builderBar)
        root.add(Box.createVerticalStrut(8))

        // Scrollable Mappings List
        val listCard = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = BorderLayout(0, 4)
            add(StudioTheme.hardwareLabel("ACTIVE 64-CHANNEL MIDI ROUTING TABLE", StudioTheme.VioletMod, StudioTheme.TitleFont), BorderLayout.NORTH)
            add(JScrollPane(mappingsHost).apply {
                border = null
                preferredSize = Dimension(820, 210)
                background = StudioTheme.ObsidianChassis
                viewport.background = StudioTheme.ObsidianChassis
            }, BorderLayout.CENTER)
            add(logLabel, BorderLayout.SOUTH)
        }
        root.add(listCard)

        add(JScrollPane(root).apply {
            border = null
            background = StudioTheme.ObsidianChassis
            viewport.background = StudioTheme.ObsidianChassis
        }, BorderLayout.CENTER)
    }

    fun updateState(vmc: VmcEngineState) {
        updating = true
        try {
            val rulesChanged = vmc.mappings != lastState.mappings ||
                vmc.learnTargetRuleId != lastState.learnTargetRuleId ||
                vmc.learnNewTarget != lastState.learnNewTarget
            lastState = vmc

            chSpinner.value = vmc.activeChannel64.coerceIn(1, 64)
            routeBox.selectedItem = vmc.routeMode
            statusLabel.text = "B${((vmc.activeChannel64 - 1) / 16) + 1} CH ${vmc.activeChannel64}/64 • ${vmc.lastEventSummary}"
            hwPortsLabel.text = "HW MIDI: ${vmc.hardwareInputs.size} IN / ${vmc.hardwareOutputs.size} OUT"
            logLabel.text = "LOG: ${vmc.recentLog.take(3).joinToString("  |  ")}"

            if (rulesChanged) rebuildMappingRows(vmc)
        } finally {
            updating = false
        }
    }

    private fun rebuildMappingRows(vmc: VmcEngineState) {
        mappingsHost.removeAll()
        vmc.mappings.forEach { rule ->
            val learning = vmc.learnTargetRuleId == rule.id
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                background = StudioTheme.GunmetalDeck
                border = StudioTheme.panelBorder(if (learning) StudioTheme.AmberHot else StudioTheme.HairlineDivider, 1)
                maximumSize = Dimension(Int.MAX_VALUE, 42)
                add(StudioTheme.hardwareLabel(rule.channelLabel(), StudioTheme.NeonCyan, StudioTheme.MonoFont).apply {
                    preferredSize = Dimension(130, 24)
                })
                add(StudioTheme.hardwareLabel("${rule.msgType.label} #${rule.number}", StudioTheme.AmberHot, StudioTheme.MonoFont).apply {
                    preferredSize = Dimension(115, 24)
                })
                add(JLabel("-> ${rule.target.label}").apply {
                    foreground = StudioTheme.TextPrimary
                    font = StudioTheme.BodyFont
                    preferredSize = Dimension(220, 24)
                })
                add(StudioTheme.hardwareButton(if (learning) "WAITING MIDI…" else "LEARN", StudioTheme.AmberHot, filled = learning) {
                    onArmLearnRule(if (learning) null else rule.id)
                })
                add(StudioTheme.hardwareButton("TEST", StudioTheme.EmeraldSignal) {
                    val ch = if (rule.channel64 > 0) rule.channel64 else lastState.activeChannel64
                    onEmitControl(rule.msgType, rule.number, 0.75f, ch)
                })
                add(StudioTheme.hardwareButton("DEL", StudioTheme.TacCoral) {
                    onRemoveRule(rule.id)
                })
            }
            mappingsHost.add(row)
            mappingsHost.add(Box.createVerticalStrut(4))
        }
        mappingsHost.revalidate()
        mappingsHost.repaint()
    }
}
