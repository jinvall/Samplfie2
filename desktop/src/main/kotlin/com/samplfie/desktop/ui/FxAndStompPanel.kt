package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.model.DesktopFxState
import com.samplfie.desktop.model.DesktopPreset
import com.samplfie.desktop.model.DesktopStompState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider

/**
 * 12-Module Audio DSP FX Rack + Master Stompbox Processor matching Samplfie2 Android.
 */
class FxAndStompPanel(
    private val onApplyPreset: (DesktopPreset) -> Unit,
    private val onUpdateFx: (DesktopFxState.() -> DesktopFxState) -> Unit,
    private val onUpdateStomp: (DesktopStompState.() -> DesktopStompState) -> Unit,
    private val onToggleMasterStomp: () -> Unit
) : JPanel() {

    private var updating = false
    private val stompStatusLabel = StudioTheme.hardwareLabel("STOMP ENGAGED", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)
    private val killDriveCb = darkCheck("KILL DRIVE", StudioTheme.TacCoral) { c -> onUpdateStomp { copy(killDrive = c) } }
    private val killModCb = darkCheck("KILL MOD", StudioTheme.VioletMod) { c -> onUpdateStomp { copy(killMod = c) } }
    private val killTimeCb = darkCheck("KILL SPACE", StudioTheme.AmberHot) { c -> onUpdateStomp { copy(killTime = c) } }

    private val tiltSlider = StudioTheme.hardwareSlider(-100, 100, 0)
    private val wetSlider = StudioTheme.hardwareSlider(0, 100, 100)
    private val trimSlider = StudioTheme.hardwareSlider(-60, 60, 0)

    private val gateCb = darkCheck("1. NOISE GATE", StudioTheme.NeonCyan) { c -> onUpdateFx { copy(noiseGateEnabled = c) } }
    private val gateThresh = StudioTheme.hardwareSlider(-70, -18, -46)

    private val pitchCb = darkCheck("2. PITCH / SPEED", StudioTheme.NeonCyan) { c -> onUpdateFx { copy(pitchEnabled = c) } }
    private val pitchSemi = StudioTheme.hardwareSlider(-12, 12, 0)

    private val gainCb = darkCheck("3. GAIN & NORM", StudioTheme.EmeraldSignal) { c -> onUpdateFx { copy(gainEnabled = c) } }
    private val gainSlider = StudioTheme.hardwareSlider(-12, 18, 0)

    private val driveCb = darkCheck("4. OVERDRIVE", StudioTheme.TacCoral) { c -> onUpdateFx { copy(overdriveEnabled = c) } }
    private val driveSlider = StudioTheme.hardwareSlider(1, 18, 4)

    private val wahCb = darkCheck("5. AUTO WAH", StudioTheme.AmberHot) { c -> onUpdateFx { copy(wahEnabled = c) } }
    private val wahSlider = StudioTheme.hardwareSlider(300, 2400, 950)

    private val phaserCb = darkCheck("6. PHASER 6-STG", StudioTheme.VioletMod) { c -> onUpdateFx { copy(phaserEnabled = c) } }
    private val phaserSlider = StudioTheme.hardwareSlider(0, 100, 50)

    private val flangerCb = darkCheck("7. FLANGER", StudioTheme.VioletMod) { c -> onUpdateFx { copy(flangerEnabled = c) } }
    private val flangerSlider = StudioTheme.hardwareSlider(0, 100, 50)

    private val chorusCb = darkCheck("8. CHORUS 2-V", StudioTheme.VioletMod) { c -> onUpdateFx { copy(chorusEnabled = c) } }
    private val chorusSlider = StudioTheme.hardwareSlider(0, 100, 45)

    private val rotaryCb = darkCheck("9. ROTARY SPKR", StudioTheme.MagentaFx) { c -> onUpdateFx { copy(rotaryEnabled = c) } }
    private val rotarySlider = StudioTheme.hardwareSlider(1, 10, 3)

    private val echoCb = darkCheck("10. TAPE ECHO", StudioTheme.AmberHot) { c -> onUpdateFx { copy(echoEnabled = c) } }
    private val echoSlider = StudioTheme.hardwareSlider(60, 700, 240)

    private val delayCb = darkCheck("11. DIGITAL DELAY", StudioTheme.AmberHot) { c -> onUpdateFx { copy(delayEnabled = c) } }
    private val delaySlider = StudioTheme.hardwareSlider(60, 900, 360)

    private val reverbCb = darkCheck("12. STUDIO REVERB", StudioTheme.MagentaFx) { c -> onUpdateFx { copy(reverbEnabled = c) } }
    private val reverbSlider = StudioTheme.hardwareSlider(0, 100, 35)

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 10, 12, 10, 12)
        }

        // Presets Strip
        val presetsBar = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 8, 4)
            add(StudioTheme.hardwareLabel("PRESETS:", StudioTheme.NeonCyan))
            DesktopPreset.entries.forEach { preset ->
                add(StudioTheme.hardwareButton(preset.label, StudioTheme.NeonCyan) { onApplyPreset(preset) })
            }
        }
        root.add(presetsBar)
        root.add(Box.createVerticalStrut(StudioTheme.s(8)))

        // Master Stompbox Mini Processor Strip
        val stompPanel = StudioTheme.hardwarePanel(StudioTheme.TacCoral).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val top = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareLabel("STOMPBOX MASTER PROCESSOR", StudioTheme.TacCoral, StudioTheme.TitleFont))
                add(stompStatusLabel)
                add(StudioTheme.hardwareButton("TOGGLE STOMP", StudioTheme.TacCoral, filled = true) { onToggleMasterStomp() })
                add(killDriveCb)
                add(killModCb)
                add(killTimeCb)
            }
            val sliders = JPanel(GridLayout(1, 3, StudioTheme.s(12), 0)).apply {
                isOpaque = false
                add(labeledSlider("DJ TONE TILT (BASS ↔ AIR)", tiltSlider) { v ->
                    onUpdateStomp { copy(engaged = true, toneTilt = v / 100f) }
                })
                add(labeledSlider("MASTER WET/DRY (%)", wetSlider) { v ->
                    onUpdateStomp { copy(engaged = true, masterWetMix = v / 100f) }
                })
                add(labeledSlider("OUTPUT TRIM (0.1 dB)", trimSlider) { v ->
                    onUpdateStomp { copy(engaged = true, dynamicsTrimDb = v / 10f) }
                })
            }
            add(top)
            add(sliders)
        }
        root.add(stompPanel)
        root.add(Box.createVerticalStrut(StudioTheme.s(8)))

        // 12 DSP Module Grid (3 columns x 4 rows)
        val grid = JPanel(GridLayout(4, 3, StudioTheme.s(8), StudioTheme.s(8))).apply {
            background = StudioTheme.ObsidianChassis
            add(fxCard(gateCb, "THRESH (dB)", gateThresh) { v -> onUpdateFx { copy(noiseGateEnabled = true, noiseThresholdDb = v.toFloat()) } })
            add(fxCard(pitchCb, "SEMITONES", pitchSemi) { v -> onUpdateFx { copy(pitchEnabled = true, pitchSemitones = v.toFloat()) } })
            add(fxCard(gainCb, "GAIN (dB)", gainSlider) { v -> onUpdateFx { copy(gainEnabled = true, gainDb = v.toFloat()) } })
            add(fxCard(driveCb, "DRIVE", driveSlider) { v -> onUpdateFx { copy(overdriveEnabled = true, driveAmount = v.toFloat()) } })
            add(fxCard(wahCb, "CENTER (Hz)", wahSlider) { v -> onUpdateFx { copy(wahEnabled = true, wahFreqHz = v.toFloat()) } })
            add(fxCard(phaserCb, "MIX (%)", phaserSlider) { v -> onUpdateFx { copy(phaserEnabled = true, phaserMix = v / 100f) } })
            add(fxCard(flangerCb, "MIX (%)", flangerSlider) { v -> onUpdateFx { copy(flangerEnabled = true, flangerMix = v / 100f) } })
            add(fxCard(chorusCb, "MIX (%)", chorusSlider) { v -> onUpdateFx { copy(chorusEnabled = true, chorusMix = v / 100f) } })
            add(fxCard(rotaryCb, "RATE (Hz)", rotarySlider) { v -> onUpdateFx { copy(rotaryEnabled = true, rotaryRateHz = v.toFloat()) } })
            add(fxCard(echoCb, "DELAY (ms)", echoSlider) { v -> onUpdateFx { copy(echoEnabled = true, echoDelayMs = v.toFloat()) } })
            add(fxCard(delayCb, "TIME (ms)", delaySlider) { v -> onUpdateFx { copy(delayEnabled = true, delayTimeMs = v.toFloat()) } })
            add(fxCard(reverbCb, "MIX (%)", reverbSlider) { v -> onUpdateFx { copy(reverbEnabled = true, reverbMix = v / 100f) } })
        }
        root.add(grid)

        add(StudioTheme.styleScrollPane(JScrollPane(root)), BorderLayout.CENTER)
    }

    fun updateState(ui: DesktopStudioUiState) {
        updating = true
        try {
            val s = ui.stompState
            val fx = ui.fxState
            stompStatusLabel.text = if (s.engaged) "ACTIVE • TILT ${(s.toneTilt * 100).toInt()}%" else "BYPASSED"
            killDriveCb.isSelected = s.killDrive
            killModCb.isSelected = s.killMod
            killTimeCb.isSelected = s.killTime
            tiltSlider.value = (s.toneTilt * 100).toInt()
            wetSlider.value = (s.masterWetMix * 100).toInt()
            trimSlider.value = (s.dynamicsTrimDb * 10).toInt()

            gateCb.isSelected = fx.noiseGateEnabled; gateThresh.value = fx.noiseThresholdDb.toInt()
            pitchCb.isSelected = fx.pitchEnabled; pitchSemi.value = fx.pitchSemitones.toInt()
            gainCb.isSelected = fx.gainEnabled; gainSlider.value = fx.gainDb.toInt()
            driveCb.isSelected = fx.overdriveEnabled; driveSlider.value = fx.driveAmount.toInt()
            wahCb.isSelected = fx.wahEnabled; wahSlider.value = fx.wahFreqHz.toInt()
            phaserCb.isSelected = fx.phaserEnabled; phaserSlider.value = (fx.phaserMix * 100).toInt()
            flangerCb.isSelected = fx.flangerEnabled; flangerSlider.value = (fx.flangerMix * 100).toInt()
            chorusCb.isSelected = fx.chorusEnabled; chorusSlider.value = (fx.chorusMix * 100).toInt()
            rotaryCb.isSelected = fx.rotaryEnabled; rotarySlider.value = fx.rotaryRateHz.toInt()
            echoCb.isSelected = fx.echoEnabled; echoSlider.value = fx.echoDelayMs.toInt()
            delayCb.isSelected = fx.delayEnabled; delaySlider.value = fx.delayTimeMs.toInt()
            reverbCb.isSelected = fx.reverbEnabled; reverbSlider.value = (fx.reverbMix * 100).toInt()
        } finally {
            updating = false
        }
    }

    private fun darkCheck(title: String, color: Color, onToggle: (Boolean) -> Unit): JCheckBox =
        StudioTheme.darkCheckBox(title, color).apply {
            addActionListener { if (!updating) onToggle(isSelected) }
        }

    private fun labeledSlider(title: String, slider: JSlider, onChanged: (Int) -> Unit): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            val lbl = StudioTheme.hardwareLabel(title, StudioTheme.TextSecondary)
            add(lbl, BorderLayout.NORTH)
            slider.addChangeListener { if (!updating && !slider.valueIsAdjusting) onChanged(slider.value) }
            add(slider, BorderLayout.CENTER)
        }

    private fun fxCard(
        cb: JCheckBox,
        paramTitle: String,
        slider: JSlider,
        onChanged: (Int) -> Unit
    ): JPanel = StudioTheme.hardwarePanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        cb.alignmentX = LEFT_ALIGNMENT
        add(cb)
        val lbl = StudioTheme.hardwareLabel("$paramTitle: ${slider.value}", StudioTheme.TextSecondary, StudioTheme.MonoFont).apply {
            alignmentX = LEFT_ALIGNMENT
        }
        slider.alignmentX = LEFT_ALIGNMENT
        slider.addChangeListener {
            lbl.text = "$paramTitle: ${slider.value}"
            if (!updating && !slider.valueIsAdjusting) onChanged(slider.value)
        }
        add(lbl)
        add(slider)
    }
}
