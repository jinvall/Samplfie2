package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.model.DesktopDelayDivision
import com.samplfie.desktop.model.DesktopFxState
import com.samplfie.desktop.model.DesktopPreset
import com.samplfie.desktop.model.DesktopStompState
import com.samplfie.desktop.model.DesktopWahMode
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import kotlin.math.roundToInt

/**
 * Full-Scope 14-Module Audio DSP FX Rack + Master Stompbox Processor for Desktop Workstation.
 * Includes full parameter editing per effect, explicit LEVEL (0..200%) per effect,
 * Octave (-1/+1 Oct), Analog BBD Delay, and Reverse Delay across all 3 delay modules.
 */
class FxAndStompPanel(
    private val onApplyPreset: (DesktopPreset) -> Unit,
    private val onUpdateFx: (DesktopFxState.() -> DesktopFxState) -> Unit,
    private val onUpdateStomp: (DesktopStompState.() -> DesktopStompState) -> Unit,
    private val onToggleMasterStomp: () -> Unit
) : JPanel() {

    private var updating = false
    private var activeFilter: FxSectionFilter = FxSectionFilter.ALL
    private val cards = mutableListOf<FxModuleCardBuilder>()
    private val cardsGrid = JPanel(GridLayout(0, 2, StudioTheme.s(10), StudioTheme.s(10))).apply {
        background = StudioTheme.ObsidianChassis
    }

    private val rackCountLabel = StudioTheme.hardwareLabel("0/14 ACTIVE", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val stompStatusLabel = StudioTheme.hardwareLabel("STOMP ENGAGED", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)
    private val killDriveCb = darkCheck("KILL DRIVE", StudioTheme.TacCoral) { c -> onUpdateStomp { copy(killDrive = c) } }
    private val killModCb = darkCheck("KILL MOD", StudioTheme.VioletMod) { c -> onUpdateStomp { copy(killMod = c) } }
    private val killTimeCb = darkCheck("KILL SPACE", StudioTheme.AmberHot) { c -> onUpdateStomp { copy(killTime = c) } }

    private val tiltSlider = StudioTheme.hardwareSlider(-100, 100, 0)
    private val wetSlider = StudioTheme.hardwareSlider(0, 100, 100)
    private val trimSlider = StudioTheme.hardwareSlider(-60, 60, 0)

    private val syncHooks = mutableListOf<(DesktopFxState) -> Unit>()
    private val filterButtons = mutableMapOf<FxSectionFilter, javax.swing.JButton>()

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 10, 12, 10, 12)
        }

        // Top Rack Header + Presets Strip
        val presetsBar = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 8, 4)
            add(StudioTheme.hardwareLabel("14-MODULE PRO DSP RACK", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(rackCountLabel)
            add(StudioTheme.hardwareLabel("PRESETS:", StudioTheme.TextSecondary))
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
                add(labeledSlider("OUTPUT TRIM (dB)", trimSlider) { v ->
                    onUpdateStomp { copy(engaged = true, dynamicsTrimDb = v / 10f) }
                })
            }
            add(top)
            add(sliders)
        }
        root.add(stompPanel)
        root.add(Box.createVerticalStrut(StudioTheme.s(8)))

        // Section Filter Tabs Bar
        val filterBar = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 8, 4)
            add(StudioTheme.hardwareLabel("VIEW GROUP:", StudioTheme.VioletMod))
            FxSectionFilter.entries.forEach { f ->
                val btn = StudioTheme.hardwareButton(f.label, StudioTheme.NeonCyan, filled = f == activeFilter) {
                    activeFilter = f
                    rebuildVisibleGrid()
                }
                filterButtons[f] = btn
                add(btn)
            }
        }
        root.add(filterBar)
        root.add(Box.createVerticalStrut(StudioTheme.s(8)))

        buildAllFourteenCards()
        rebuildVisibleGrid()
        root.add(cardsGrid)
        root.add(Box.createVerticalGlue())

        add(StudioTheme.styleScrollPane(JScrollPane(root)), BorderLayout.CENTER)
    }

    private fun card(
        section: FxSectionFilter,
        title: String,
        subtitle: String,
        accent: Color,
        onToggle: (Boolean) -> Unit,
        init: FxModuleCardBuilder.() -> (DesktopFxState) -> Unit
    ) {
        val b = FxModuleCardBuilder(section, title, subtitle, accent, { updating }, onToggle)
        val syncFn = b.init()
        cards += b
        syncHooks += { fx -> syncFn(fx) }
    }

    private fun buildAllFourteenCards() {
        // 1. Noise Removal & HPF
        card(FxSectionFilter.DYNAMICS_PITCH, "1. NOISE REMOVAL & HPF", "Gate Threshold, Reduction, Attack/Release, Knee & Level", StudioTheme.EmeraldSignal, { c -> onUpdateFx { copy(noiseGateEnabled = c) } }) {
            val hpfCb = addCheck("HPF", StudioTheme.EmeraldSignal) { c -> onUpdateFx { copy(noiseGateEnabled = true, noiseHighPassEnabled = c) } }
            val thresh = addSlider("THRESHOLD", -60, -10, -46, { "$it dB" }) { v -> onUpdateFx { copy(noiseGateEnabled = true, noiseThresholdDb = v.toFloat()) } }
            val red = addSlider("REDUCTION", 6, 48, 24, { "$it dB" }) { v -> onUpdateFx { copy(noiseGateEnabled = true, noiseReductionDb = v.toFloat()) } }
            val hpfHz = addSlider("HPF CUTOFF", 30, 250, 80, { "$it Hz" }) { v -> onUpdateFx { copy(noiseGateEnabled = true, noiseHighPassEnabled = true, noiseHighPassHz = v.toFloat()) } }
            val atk = addSlider("ATTACK", 5, 250, 30, { "${"%.1f".format(it / 10f)} ms" }) { v -> onUpdateFx { copy(noiseGateEnabled = true, noiseAttackMs = v / 10f) } }
            val rel = addSlider("RELEASE", 15, 350, 65, { "$it ms" }) { v -> onUpdateFx { copy(noiseGateEnabled = true, noiseReleaseMs = v.toFloat()) } }
            val knee = addSlider("KNEE WIDTH", 0, 18, 6, { "$it dB" }) { v -> onUpdateFx { copy(noiseGateEnabled = true, noiseKneeDb = v.toFloat()) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(noiseGateEnabled = true, noiseGateLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.noiseGateEnabled)
                if (hpfCb.isSelected != fx.noiseHighPassEnabled) hpfCb.isSelected = fx.noiseHighPassEnabled
                thresh.syncValue(fx.noiseThresholdDb.roundToInt())
                red.syncValue(fx.noiseReductionDb.roundToInt())
                hpfHz.syncValue(fx.noiseHighPassHz.roundToInt(), enabled = fx.noiseHighPassEnabled)
                atk.syncValue((fx.noiseAttackMs * 10f).roundToInt())
                rel.syncValue(fx.noiseReleaseMs.roundToInt())
                knee.syncValue(fx.noiseKneeDb.roundToInt())
                lvl.syncValue((fx.noiseGateLevel * 100f).roundToInt())
            }
        }

        // 2. Pitch & Speed
        card(FxSectionFilter.DYNAMICS_PITCH, "2. PITCH & SPEED", "WSOLA Grain Shifter, Fine Cents & Vinyl Link", StudioTheme.NeonCyan, { c -> onUpdateFx { copy(pitchEnabled = c) } }) {
            val vinylCb = addCheck("VINYL LINK (PITCH=SPEED)", StudioTheme.NeonCyan) { c -> onUpdateFx { copy(pitchEnabled = true, pitchVinylLink = c) } }
            val semi = addSlider("PITCH SHIFT", -120, 120, 0, { "${if (it >= 0) "+" else ""}${"%.1f".format(it / 10f)} st" }) { v -> onUpdateFx { copy(pitchEnabled = true, pitchSemitones = v / 10f) } }
            val cents = addSlider("FINE DETUNE", -50, 50, 0, { "$it ct" }) { v -> onUpdateFx { copy(pitchEnabled = true, pitchFineCents = v.toFloat()) } }
            val spd = addSlider("TEMPO / SPEED", 50, 200, 100, { "${"%.2f".format(it / 100f)}x" }) { v -> onUpdateFx { copy(pitchEnabled = true, speedFactor = v / 100f) } }
            val grain = addSlider("GRAIN WINDOW", 12, 80, 32, { "$it ms" }) { v -> onUpdateFx { copy(pitchEnabled = true, pitchGrainMs = v.toFloat()) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(pitchEnabled = true, pitchLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.pitchEnabled)
                if (vinylCb.isSelected != fx.pitchVinylLink) vinylCb.isSelected = fx.pitchVinylLink
                semi.syncValue((fx.pitchSemitones * 10f).roundToInt(), enabled = !fx.pitchVinylLink)
                cents.syncValue(fx.pitchFineCents.roundToInt(), enabled = !fx.pitchVinylLink)
                spd.syncValue((fx.speedFactor * 100f).roundToInt())
                grain.syncValue(fx.pitchGrainMs.roundToInt(), enabled = !fx.pitchVinylLink)
                lvl.syncValue((fx.pitchLevel * 100f).roundToInt())
            }
        }

        // 3. Gain & Dynamics
        card(FxSectionFilter.DYNAMICS_PITCH, "3. GAIN & DYNAMICS", "Comp Ratio, Soft Limiter Ceiling, Stereo Width & Level", StudioTheme.EmeraldSignal, { c -> onUpdateFx { copy(gainEnabled = c) } }) {
            val normCb = addCheck("NORMALIZE", StudioTheme.EmeraldSignal) { c -> onUpdateFx { copy(gainEnabled = true, normalize = c) } }
            val limCb = addCheck("SOFT LIMITER", StudioTheme.AmberHot) { c -> onUpdateFx { copy(gainEnabled = true, softLimiter = c) } }
            val gain = addSlider("GAIN", -240, 240, 0, { "${if (it >= 0) "+" else ""}${"%.1f".format(it / 10f)} dB" }) { v -> onUpdateFx { copy(gainEnabled = true, gainDb = v / 10f) } }
            val ratio = addSlider("COMP RATIO", 10, 60, 15, { "${"%.1f".format(it / 10f)}:1" }) { v -> onUpdateFx { copy(gainEnabled = true, compressionRatio = v / 10f) } }
            val ceil = addSlider("LIMIT CEILING", -60, 0, -5, { "${"%.1f".format(it / 10f)} dB" }) { v -> onUpdateFx { copy(gainEnabled = true, softLimiter = true, limiterCeilingDb = v / 10f) } }
            val width = addSlider("STEREO WIDTH", 0, 180, 100, { "$it%" }) { v -> onUpdateFx { copy(gainEnabled = true, stereoWidth = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(gainEnabled = true, gainLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.gainEnabled)
                if (normCb.isSelected != fx.normalize) normCb.isSelected = fx.normalize
                if (limCb.isSelected != fx.softLimiter) limCb.isSelected = fx.softLimiter
                gain.syncValue((fx.gainDb * 10f).roundToInt())
                ratio.syncValue((fx.compressionRatio * 10f).roundToInt())
                ceil.syncValue((fx.limiterCeilingDb * 10f).roundToInt(), enabled = fx.softLimiter)
                width.syncValue((fx.stereoWidth * 100f).roundToInt())
                lvl.syncValue((fx.gainLevel * 100f).roundToInt())
            }
        }

        // 4. Octave (-1 Oct / +1 Oct)
        card(FxSectionFilter.DRIVE_MOD, "4. OCTAVE (-1 / +1 OCT)", "Warm -1 Sub Divider, +1 Octave Doubler & Dry Mix", StudioTheme.VioletMod, { c -> onUpdateFx { copy(octaveEnabled = c) } }) {
            val subMix = addSlider("SUB (-1 OCT)", 0, 100, 65, { "$it%" }) { v -> onUpdateFx { copy(octaveEnabled = true, octaveSubMix = v / 100f) } }
            val upMix = addSlider("UPPER (+1 OCT)", 0, 100, 35, { "$it%" }) { v -> onUpdateFx { copy(octaveEnabled = true, octaveUpMix = v / 100f) } }
            val tone = addSlider("SUB LPF TONE", 250, 2400, 900, { "$it Hz" }) { v -> onUpdateFx { copy(octaveEnabled = true, octaveSubToneHz = v.toFloat()) } }
            val dry = addSlider("DRY BLEND", 0, 100, 70, { "$it%" }) { v -> onUpdateFx { copy(octaveEnabled = true, octaveDryMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(octaveEnabled = true, octaveLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.octaveEnabled)
                subMix.syncValue((fx.octaveSubMix * 100f).roundToInt())
                upMix.syncValue((fx.octaveUpMix * 100f).roundToInt())
                tone.syncValue(fx.octaveSubToneHz.roundToInt())
                dry.syncValue((fx.octaveDryMix * 100f).roundToInt())
                lvl.syncValue((fx.octaveLevel * 100f).roundToInt())
            }
        }

        // 5. Tube Overdrive
        card(FxSectionFilter.DRIVE_MOD, "5. OVERDRIVE", "Pre-HPF Tightener, Tube Bias Asymmetry, Tone & Level", StudioTheme.TacCoral, { c -> onUpdateFx { copy(overdriveEnabled = c) } }) {
            val drive = addSlider("DRIVE", 10, 200, 40, { "${"%.1f".format(it / 10f)}x" }) { v -> onUpdateFx { copy(overdriveEnabled = true, driveAmount = v / 10f) } }
            val asym = addSlider("TUBE ASYMMETRY", 0, 80, 25, { "$it%" }) { v -> onUpdateFx { copy(overdriveEnabled = true, overdriveAsymmetry = v / 100f) } }
            val preHp = addSlider("PRE-HPF", 30, 450, 120, { "$it Hz" }) { v -> onUpdateFx { copy(overdriveEnabled = true, overdrivePreHpHz = v.toFloat()) } }
            val tone = addSlider("TONE LPF", 800, 12000, 4200, { "$it Hz" }) { v -> onUpdateFx { copy(overdriveEnabled = true, overdriveToneHz = v.toFloat()) } }
            val mix = addSlider("WET MIX", 0, 100, 65, { "$it%" }) { v -> onUpdateFx { copy(overdriveEnabled = true, overdriveMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(overdriveEnabled = true, overdriveLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.overdriveEnabled)
                drive.syncValue((fx.driveAmount * 10f).roundToInt())
                asym.syncValue((fx.overdriveAsymmetry * 100f).roundToInt())
                preHp.syncValue(fx.overdrivePreHpHz.roundToInt())
                tone.syncValue(fx.overdriveToneHz.roundToInt())
                mix.syncValue((fx.overdriveMix * 100f).roundToInt())
                lvl.syncValue((fx.overdriveLevel * 100f).roundToInt())
            }
        }

        // 6. Auto / LFO / Pedal Wah
        card(FxSectionFilter.DRIVE_MOD, "6. WAH FILTER", "State-Variable Bandpass: Auto Env / LFO / Manual Pedal", StudioTheme.AmberHot, { c -> onUpdateFx { copy(wahEnabled = c) } }) {
            val modeBox = addCombo("MODE:", DesktopWahMode.entries.toTypedArray()) { m -> onUpdateFx { copy(wahEnabled = true, wahMode = m) } }
            val freq = addSlider("CENTER FREQ", 350, 2200, 950, { "$it Hz" }) { v -> onUpdateFx { copy(wahEnabled = true, wahFreqHz = v.toFloat()) } }
            val q = addSlider("RESONANCE Q", 15, 80, 35, { "${"%.1f".format(it / 10f)}" }) { v -> onUpdateFx { copy(wahEnabled = true, wahResonanceQ = v / 10f) } }
            val rate = addSlider("LFO RATE", 2, 60, 16, { "${"%.1f".format(it / 10f)} Hz" }) { v -> onUpdateFx { copy(wahEnabled = true, wahRateHz = v / 10f) } }
            val env = addSlider("ENV SENS", 0, 100, 60, { "$it%" }) { v -> onUpdateFx { copy(wahEnabled = true, wahEnvSens = v / 100f) } }
            val pedal = addSlider("PEDAL SWEEP", 0, 100, 50, { "$it%" }) { v -> onUpdateFx { copy(wahEnabled = true, wahMode = DesktopWahMode.MANUAL_PEDAL, wahPedalPos = v / 100f) } }
            val mix = addSlider("WET MIX", 0, 100, 85, { "$it%" }) { v -> onUpdateFx { copy(wahEnabled = true, wahMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(wahEnabled = true, wahLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.wahEnabled)
                if (modeBox.selectedItem != fx.wahMode) modeBox.selectedItem = fx.wahMode
                freq.syncValue(fx.wahFreqHz.roundToInt())
                q.syncValue((fx.wahResonanceQ * 10f).roundToInt())
                rate.syncValue((fx.wahRateHz * 10f).roundToInt())
                env.syncValue((fx.wahEnvSens * 100f).roundToInt(), enabled = fx.wahMode == DesktopWahMode.AUTO_ENV)
                pedal.syncValue((fx.wahPedalPos * 100f).roundToInt(), enabled = fx.wahMode == DesktopWahMode.MANUAL_PEDAL)
                mix.syncValue((fx.wahMix * 100f).roundToInt())
                lvl.syncValue((fx.wahLevel * 100f).roundToInt())
            }
        }

        // 7. Phaser
        card(FxSectionFilter.DRIVE_MOD, "7. PHASER", "4/6/8-Stage Stereo All-Pass Network & Feedback", StudioTheme.VioletMod, { c -> onUpdateFx { copy(phaserEnabled = c) } }) {
            val stBox = addCombo("STAGES:", arrayOf(4, 6, 8)) { st -> onUpdateFx { copy(phaserEnabled = true, phaserStages = st) } }
            val rate = addSlider("RATE", 10, 500, 80, { "${"%.2f".format(it / 100f)} Hz" }) { v -> onUpdateFx { copy(phaserEnabled = true, phaserRateHz = v / 100f) } }
            val depth = addSlider("DEPTH", 0, 100, 75, { "$it%" }) { v -> onUpdateFx { copy(phaserEnabled = true, phaserDepth = v / 100f) } }
            val center = addSlider("CENTER FREQ", 300, 2500, 900, { "$it Hz" }) { v -> onUpdateFx { copy(phaserEnabled = true, phaserCenterHz = v.toFloat()) } }
            val fb = addSlider("FEEDBACK", 0, 85, 55, { "$it%" }) { v -> onUpdateFx { copy(phaserEnabled = true, phaserFeedback = v / 100f) } }
            val stOfs = addSlider("STEREO OFFSET", 0, 100, 35, { "$it%" }) { v -> onUpdateFx { copy(phaserEnabled = true, phaserStereoOffset = v / 100f) } }
            val mix = addSlider("WET MIX", 0, 100, 55, { "$it%" }) { v -> onUpdateFx { copy(phaserEnabled = true, phaserMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(phaserEnabled = true, phaserLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.phaserEnabled)
                if (stBox.selectedItem != fx.phaserStages) stBox.selectedItem = fx.phaserStages
                rate.syncValue((fx.phaserRateHz * 100f).roundToInt())
                depth.syncValue((fx.phaserDepth * 100f).roundToInt())
                center.syncValue(fx.phaserCenterHz.roundToInt())
                fb.syncValue((fx.phaserFeedback * 100f).roundToInt())
                stOfs.syncValue((fx.phaserStereoOffset * 100f).roundToInt())
                mix.syncValue((fx.phaserMix * 100f).roundToInt())
                lvl.syncValue((fx.phaserLevel * 100f).roundToInt())
            }
        }

        // 8. Flanger
        card(FxSectionFilter.DRIVE_MOD, "8. FLANGER", "Jet-Sweep Comb + Manual Delay & Stereo Spread", StudioTheme.MagentaFx, { c -> onUpdateFx { copy(flangerEnabled = c) } }) {
            val rate = addSlider("RATE", 10, 400, 60, { "${"%.2f".format(it / 100f)} Hz" }) { v -> onUpdateFx { copy(flangerEnabled = true, flangerRateHz = v / 100f) } }
            val man = addSlider("MANUAL DELAY", 2, 40, 8, { "${"%.1f".format(it / 10f)} ms" }) { v -> onUpdateFx { copy(flangerEnabled = true, flangerManualMs = v / 10f) } }
            val depth = addSlider("DEPTH", 3, 60, 30, { "${"%.1f".format(it / 10f)} ms" }) { v -> onUpdateFx { copy(flangerEnabled = true, flangerDepthMs = v / 10f) } }
            val fb = addSlider("FEEDBACK", -85, 85, 50, { "$it%" }) { v -> onUpdateFx { copy(flangerEnabled = true, flangerFeedback = v / 100f) } }
            val spr = addSlider("STEREO SPREAD", 0, 100, 40, { "$it%" }) { v -> onUpdateFx { copy(flangerEnabled = true, flangerSpread = v / 100f) } }
            val mix = addSlider("WET MIX", 0, 100, 50, { "$it%" }) { v -> onUpdateFx { copy(flangerEnabled = true, flangerMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(flangerEnabled = true, flangerLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.flangerEnabled)
                rate.syncValue((fx.flangerRateHz * 100f).roundToInt())
                man.syncValue((fx.flangerManualMs * 10f).roundToInt())
                depth.syncValue((fx.flangerDepthMs * 10f).roundToInt())
                fb.syncValue((fx.flangerFeedback * 100f).roundToInt())
                spr.syncValue((fx.flangerSpread * 100f).roundToInt())
                mix.syncValue((fx.flangerMix * 100f).roundToInt())
                lvl.syncValue((fx.flangerLevel * 100f).roundToInt())
            }
        }

        // 9. Chorus
        card(FxSectionFilter.DRIVE_MOD, "9. CHORUS", "2–4 Voice Stereo Ensemble & High-Cut Tone", StudioTheme.NeonCyan, { c -> onUpdateFx { copy(chorusEnabled = c) } }) {
            val vBox = addCombo("VOICES:", arrayOf(2, 3, 4)) { v -> onUpdateFx { copy(chorusEnabled = true, chorusVoices = v) } }
            val rate = addSlider("RATE", 20, 400, 110, { "${"%.2f".format(it / 100f)} Hz" }) { v -> onUpdateFx { copy(chorusEnabled = true, chorusRateHz = v / 100f) } }
            val base = addSlider("BASE DELAY", 5, 30, 14, { "$it ms" }) { v -> onUpdateFx { copy(chorusEnabled = true, chorusBaseMs = v.toFloat()) } }
            val depth = addSlider("DEPTH", 20, 150, 65, { "${"%.1f".format(it / 10f)} ms" }) { v -> onUpdateFx { copy(chorusEnabled = true, chorusDepthMs = v / 10f) } }
            val hicut = addSlider("HIGH CUT", 2000, 14000, 7500, { "$it Hz" }) { v -> onUpdateFx { copy(chorusEnabled = true, chorusHighCutHz = v.toFloat()) } }
            val mix = addSlider("WET MIX", 0, 100, 45, { "$it%" }) { v -> onUpdateFx { copy(chorusEnabled = true, chorusMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(chorusEnabled = true, chorusLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.chorusEnabled)
                if (vBox.selectedItem != fx.chorusVoices) vBox.selectedItem = fx.chorusVoices
                rate.syncValue((fx.chorusRateHz * 100f).roundToInt())
                base.syncValue(fx.chorusBaseMs.roundToInt())
                depth.syncValue((fx.chorusDepthMs * 10f).roundToInt())
                hicut.syncValue(fx.chorusHighCutHz.roundToInt())
                mix.syncValue((fx.chorusMix * 100f).roundToInt())
                lvl.syncValue((fx.chorusLevel * 100f).roundToInt())
            }
        }

        // 10. Rotary Speaker
        card(FxSectionFilter.DRIVE_MOD, "10. ROTARY SPEAKER", "Leslie Dual Horn/Drum + Crossover, Cab Drive & Brake", StudioTheme.AmberHot, { c -> onUpdateFx { copy(rotaryEnabled = c) } }) {
            val fastCb = addCheck("FAST ROTOR", StudioTheme.AmberHot) { c -> onUpdateFx { copy(rotaryEnabled = true, rotaryFastMode = c, rotaryBrake = false) } }
            val brakeCb = addCheck("BRAKE", StudioTheme.TacCoral) { c -> onUpdateFx { copy(rotaryEnabled = true, rotaryBrake = c) } }
            val rate = addSlider("ROTOR SPEED", 5, 90, 32, { "${"%.1f".format(it / 10f)} Hz" }) { v -> onUpdateFx { copy(rotaryEnabled = true, rotaryRateHz = v / 10f) } }
            val depth = addSlider("DOPPLER DEPTH", 0, 100, 75, { "$it%" }) { v -> onUpdateFx { copy(rotaryEnabled = true, rotaryDepth = v / 100f) } }
            val xover = addSlider("CROSSOVER", 400, 1600, 800, { "$it Hz" }) { v -> onUpdateFx { copy(rotaryEnabled = true, rotaryCrossoverHz = v.toFloat()) } }
            val horn = addSlider("HORN BALANCE", 0, 100, 60, { "$it%" }) { v -> onUpdateFx { copy(rotaryEnabled = true, rotaryHornBalance = v / 100f) } }
            val drv = addSlider("CABINET DRIVE", 0, 100, 15, { "$it%" }) { v -> onUpdateFx { copy(rotaryEnabled = true, rotaryCabinetDrive = v / 100f) } }
            val mix = addSlider("WET MIX", 0, 100, 80, { "$it%" }) { v -> onUpdateFx { copy(rotaryEnabled = true, rotaryMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(rotaryEnabled = true, rotaryLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.rotaryEnabled)
                if (fastCb.isSelected != fx.rotaryFastMode) fastCb.isSelected = fx.rotaryFastMode
                if (brakeCb.isSelected != fx.rotaryBrake) brakeCb.isSelected = fx.rotaryBrake
                rate.syncValue((fx.rotaryRateHz * 10f).roundToInt())
                depth.syncValue((fx.rotaryDepth * 100f).roundToInt())
                xover.syncValue(fx.rotaryCrossoverHz.roundToInt())
                horn.syncValue((fx.rotaryHornBalance * 100f).roundToInt())
                drv.syncValue((fx.rotaryCabinetDrive * 100f).roundToInt())
                mix.syncValue((fx.rotaryMix * 100f).roundToInt())
                lvl.syncValue((fx.rotaryLevel * 100f).roundToInt())
            }
        }

        // 11. Tape Echo (with Reverse Delay)
        card(FxSectionFilter.DELAY_SPACE, "11. TAPE ECHO", "Wow/Flutter, Tape Sat, HF Damping & Reverse Delay", StudioTheme.NeonCyan, { c -> onUpdateFx { copy(echoEnabled = c) } }) {
            val revCb = addCheck("REVERSE DELAY", StudioTheme.MagentaFx) { c -> onUpdateFx { copy(echoEnabled = true, echoReverseDelay = c) } }
            val time = addSlider("ECHO TIME", 50, 1000, 240, { "$it ms" }) { v -> onUpdateFx { copy(echoEnabled = true, echoDelayMs = v.toFloat()) } }
            val fb = addSlider("FEEDBACK", 0, 88, 42, { "$it%" }) { v -> onUpdateFx { copy(echoEnabled = true, echoFeedback = v / 100f) } }
            val damp = addSlider("DAMPING", 0, 95, 40, { "$it%" }) { v -> onUpdateFx { copy(echoEnabled = true, echoDamping = v / 100f) } }
            val wow = addSlider("WOW & FLUTTER", 0, 100, 25, { "$it%" }) { v -> onUpdateFx { copy(echoEnabled = true, echoWowFlutter = v / 100f) } }
            val sat = addSlider("TAPE SATURATION", 0, 100, 20, { "$it%" }) { v -> onUpdateFx { copy(echoEnabled = true, echoSaturation = v / 100f) } }
            val mix = addSlider("WET MIX", 0, 100, 38, { "$it%" }) { v -> onUpdateFx { copy(echoEnabled = true, echoMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(echoEnabled = true, echoLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.echoEnabled)
                if (revCb.isSelected != fx.echoReverseDelay) revCb.isSelected = fx.echoReverseDelay
                time.syncValue(fx.echoDelayMs.roundToInt())
                fb.syncValue((fx.echoFeedback * 100f).roundToInt())
                damp.syncValue((fx.echoDamping * 100f).roundToInt())
                wow.syncValue((fx.echoWowFlutter * 100f).roundToInt())
                sat.syncValue((fx.echoSaturation * 100f).roundToInt())
                mix.syncValue((fx.echoMix * 100f).roundToInt())
                lvl.syncValue((fx.echoLevel * 100f).roundToInt())
            }
        }

        // 12. Digital Delay (BPM Sync + Reverse Delay)
        card(FxSectionFilter.DELAY_SPACE, "12. DIGITAL DELAY", "BPM Note Sync, HP/LP Filter, Ping-Pong & Reverse Delay", StudioTheme.VioletMod, { c -> onUpdateFx { copy(delayEnabled = c) } }) {
            val syncCb = addCheck("BPM SYNC", StudioTheme.VioletMod) { c -> onUpdateFx { copy(delayEnabled = true, delayBpmSync = c) } }
            val revCb = addCheck("REVERSE DELAY", StudioTheme.MagentaFx) { c -> onUpdateFx { copy(delayEnabled = true, delayReverseDelay = c) } }
            val divBox = addCombo("DIV:", DesktopDelayDivision.entries.toTypedArray()) { d ->
                onUpdateFx { copy(delayEnabled = true, delayBpmSync = d != DesktopDelayDivision.FREE_MS, delayDivision = d) }
            }
            val time = addSlider("DELAY TIME", 20, 1200, 360, { "$it ms" }) { v -> onUpdateFx { copy(delayEnabled = true, delayTimeMs = v.toFloat()) } }
            val fb = addSlider("FEEDBACK", 0, 90, 40, { "$it%" }) { v -> onUpdateFx { copy(delayEnabled = true, delayFeedback = v / 100f) } }
            val hp = addSlider("HIGH-PASS", 40, 1000, 140, { "$it Hz" }) { v -> onUpdateFx { copy(delayEnabled = true, delayHighPassHz = v.toFloat()) } }
            val lp = addSlider("LOW-PASS", 1200, 14000, 6500, { "$it Hz" }) { v -> onUpdateFx { copy(delayEnabled = true, delayLowPassHz = v.toFloat()) } }
            val spr = addSlider("TAP SPREAD", 0, 100, 50, { "$it%" }) { v -> onUpdateFx { copy(delayEnabled = true, delaySpread = v / 100f) } }
            val mix = addSlider("WET MIX", 0, 100, 35, { "$it%" }) { v -> onUpdateFx { copy(delayEnabled = true, delayMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(delayEnabled = true, delayLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.delayEnabled)
                if (syncCb.isSelected != fx.delayBpmSync) syncCb.isSelected = fx.delayBpmSync
                if (revCb.isSelected != fx.delayReverseDelay) revCb.isSelected = fx.delayReverseDelay
                if (divBox.selectedItem != fx.delayDivision) divBox.selectedItem = fx.delayDivision
                time.syncValue(fx.delayTimeMs.roundToInt(), enabled = !fx.delayBpmSync)
                fb.syncValue((fx.delayFeedback * 100f).roundToInt())
                hp.syncValue(fx.delayHighPassHz.roundToInt())
                lp.syncValue(fx.delayLowPassHz.roundToInt())
                spr.syncValue((fx.delaySpread * 100f).roundToInt())
                mix.syncValue((fx.delayMix * 100f).roundToInt())
                lvl.syncValue((fx.delayLevel * 100f).roundToInt())
            }
        }

        // 13. Analog BBD Delay (with Reverse Delay)
        card(FxSectionFilter.DELAY_SPACE, "13. ANALOG BBD DELAY", "Warm BBD LPF Bucket, Compander Sat, LFO Mod & Reverse", StudioTheme.AmberHot, { c -> onUpdateFx { copy(analogDelayEnabled = c) } }) {
            val revCb = addCheck("REVERSE DELAY", StudioTheme.MagentaFx) { c -> onUpdateFx { copy(analogDelayEnabled = true, analogDelayReverseDelay = c) } }
            val time = addSlider("BBD TIME", 30, 1000, 310, { "$it ms" }) { v -> onUpdateFx { copy(analogDelayEnabled = true, analogDelayMs = v.toFloat()) } }
            val fb = addSlider("FEEDBACK", 0, 92, 52, { "$it%" }) { v -> onUpdateFx { copy(analogDelayEnabled = true, analogDelayFeedback = v / 100f) } }
            val warm = addSlider("WARMTH LPF", 600, 6500, 2400, { "$it Hz" }) { v -> onUpdateFx { copy(analogDelayEnabled = true, analogDelayWarmthHz = v.toFloat()) } }
            val sat = addSlider("COMPANDER SAT", 0, 100, 35, { "$it%" }) { v -> onUpdateFx { copy(analogDelayEnabled = true, analogDelaySaturation = v / 100f) } }
            val modD = addSlider("CLOCK MOD", 0, 100, 30, { "$it%" }) { v -> onUpdateFx { copy(analogDelayEnabled = true, analogDelayModDepth = v / 100f) } }
            val modR = addSlider("MOD RATE", 10, 400, 70, { "${"%.2f".format(it / 100f)} Hz" }) { v -> onUpdateFx { copy(analogDelayEnabled = true, analogDelayModRateHz = v / 100f) } }
            val mix = addSlider("WET MIX", 0, 100, 45, { "$it%" }) { v -> onUpdateFx { copy(analogDelayEnabled = true, analogDelayMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(analogDelayEnabled = true, analogDelayLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.analogDelayEnabled)
                if (revCb.isSelected != fx.analogDelayReverseDelay) revCb.isSelected = fx.analogDelayReverseDelay
                time.syncValue(fx.analogDelayMs.roundToInt())
                fb.syncValue((fx.analogDelayFeedback * 100f).roundToInt())
                warm.syncValue(fx.analogDelayWarmthHz.roundToInt())
                sat.syncValue((fx.analogDelaySaturation * 100f).roundToInt())
                modD.syncValue((fx.analogDelayModDepth * 100f).roundToInt())
                modR.syncValue((fx.analogDelayModRateHz * 100f).roundToInt())
                mix.syncValue((fx.analogDelayMix * 100f).roundToInt())
                lvl.syncValue((fx.analogDelayLevel * 100f).roundToInt())
            }
        }

        // 14. Studio Reverb
        card(FxSectionFilter.DELAY_SPACE, "14. STUDIO HALL REVERB", "Room Size, Early Reflections, Low-Cut, Stereo Width & Level", StudioTheme.EmeraldSignal, { c -> onUpdateFx { copy(reverbEnabled = c) } }) {
            val size = addSlider("ROOM SIZE", 10, 98, 60, { "$it%" }) { v -> onUpdateFx { copy(reverbEnabled = true, reverbSize = v / 100f) } }
            val pre = addSlider("PRE-DELAY", 0, 80, 18, { "$it ms" }) { v -> onUpdateFx { copy(reverbEnabled = true, reverbPreDelayMs = v.toFloat()) } }
            val er = addSlider("EARLY REFL", 0, 100, 45, { "$it%" }) { v -> onUpdateFx { copy(reverbEnabled = true, reverbEarlyReflections = v / 100f) } }
            val damp = addSlider("HF DAMPING", 0, 95, 35, { "$it%" }) { v -> onUpdateFx { copy(reverbEnabled = true, reverbDamping = v / 100f) } }
            val lc = addSlider("LOW-CUT HPF", 20, 500, 120, { "$it Hz" }) { v -> onUpdateFx { copy(reverbEnabled = true, reverbLowCutHz = v.toFloat()) } }
            val width = addSlider("STEREO WIDTH", 0, 140, 75, { "$it%" }) { v -> onUpdateFx { copy(reverbEnabled = true, reverbStereoWidth = v / 100f) } }
            val mix = addSlider("WET MIX", 0, 100, 35, { "$it%" }) { v -> onUpdateFx { copy(reverbEnabled = true, reverbMix = v / 100f) } }
            val lvl = addSlider("LEVEL GAIN", 0, 200, 100, { "$it%" }) { v -> onUpdateFx { copy(reverbEnabled = true, reverbLevel = v / 100f) } }
            return@card { fx ->
                syncHeader(fx.reverbEnabled)
                size.syncValue((fx.reverbSize * 100f).roundToInt())
                pre.syncValue(fx.reverbPreDelayMs.roundToInt())
                er.syncValue((fx.reverbEarlyReflections * 100f).roundToInt())
                damp.syncValue((fx.reverbDamping * 100f).roundToInt())
                lc.syncValue(fx.reverbLowCutHz.roundToInt())
                width.syncValue((fx.reverbStereoWidth * 100f).roundToInt())
                mix.syncValue((fx.reverbMix * 100f).roundToInt())
                lvl.syncValue((fx.reverbLevel * 100f).roundToInt())
            }
        }
    }

    private fun rebuildVisibleGrid() {
        cardsGrid.removeAll()
        cards.filter { activeFilter == FxSectionFilter.ALL || it.section == activeFilter }
            .forEach { cardsGrid.add(it.panel) }
        filterButtons.forEach { (f, btn) ->
            btn.foreground = if (f == activeFilter) StudioTheme.EmeraldSignal else StudioTheme.TextSecondary
        }
        cardsGrid.revalidate()
        cardsGrid.repaint()
    }

    fun updateState(ui: DesktopStudioUiState) {
        updating = true
        try {
            val s = ui.stompState
            val fx = ui.fxState
            rackCountLabel.text = "${fx.activeCount}/14 ACTIVE${if (fx.masterBypass) " (BYPASSED)" else ""}"
            stompStatusLabel.text = if (s.engaged) "ACTIVE • TILT ${(s.toneTilt * 100).toInt()}%" else "BYPASSED"
            killDriveCb.isSelected = s.killDrive
            killModCb.isSelected = s.killMod
            killTimeCb.isSelected = s.killTime
            if (!tiltSlider.valueIsAdjusting) tiltSlider.value = (s.toneTilt * 100).roundToInt()
            if (!wetSlider.valueIsAdjusting) wetSlider.value = (s.masterWetMix * 100).roundToInt()
            if (!trimSlider.valueIsAdjusting) trimSlider.value = (s.dynamicsTrimDb * 10).roundToInt()

            syncHooks.forEach { it(fx) }
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
            slider.addChangeListener { if (!updating) onChanged(slider.value) }
            add(slider, BorderLayout.CENTER)
        }
}
