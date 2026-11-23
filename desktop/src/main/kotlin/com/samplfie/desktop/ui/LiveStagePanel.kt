package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.model.DesktopLiveMode
import com.samplfie.desktop.model.DesktopStompState
import com.samplfie.desktop.network.CollabPeerDevice
import com.samplfie.desktop.studio.DesktopSyncedPlayheadState
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
import java.awt.event.MouseMotionAdapter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Live Performance & Release Station for Desktop:
 * - Live Mode Switcher (STAGE DJ / LIVE REC / STUDIO RELEASE)
 * - Master Stompbox Mini-Processor (Kill Drive/Mod/Space, Mod & Space Scalers, Interactive X/Y Pad, Tap Tempo)
 * - Live Console Actions + Quick Release Bounce / Export
 */
class LiveStagePanel(
    private val onSetLiveMode: (DesktopLiveMode) -> Unit,
    private val onToggleStompBypass: () -> Unit,
    private val onUpdateStomp: (DesktopStompState.() -> DesktopStompState) -> Unit,
    private val onXyDrag: (Float, Float) -> Unit,
    private val onTapTempo: () -> Unit,
    private val onToggleSyncedPlay: () -> Unit,
    private val onExportWav: () -> Unit,
    private val onExportMp3: () -> Unit,
    private val onPushHandoff: () -> Unit,
    private val onOpenCollabDialog: () -> Unit = {},
    private val onToggleBandRec: () -> Unit = {}
) : JPanel() {

    private val collabStrip = DesktopBandCollabStrip(
        onOpenCollabDialog = onOpenCollabDialog,
        onToggleBandRec = onToggleBandRec
    )

    private var updating = false
    private var stompState = DesktopStompState()

    private val modeLabel = StudioTheme.hardwareLabel("STAGE DJ", StudioTheme.TacCoral, StudioTheme.TitleFont)
    private val bpmBadge = StudioTheme.hardwareLabel("124 BPM", StudioTheme.AmberHot, StudioTheme.MonoFont)

    private val killDriveCb = darkCheck("KILL DRIVE", StudioTheme.TacCoral) { c -> onUpdateStomp { copy(killDrive = c) } }
    private val killModCb = darkCheck("KILL MOD", StudioTheme.VioletMod) { c -> onUpdateStomp { copy(killMod = c) } }
    private val killTimeCb = darkCheck("KILL SPACE", StudioTheme.AmberHot) { c -> onUpdateStomp { copy(killTime = c) } }

    private val tiltSlider = StudioTheme.hardwareSlider(-100, 100, 0)
    private val wetSlider = StudioTheme.hardwareSlider(0, 100, 100)
    private val modScaleSlider = StudioTheme.hardwareSlider(20, 180, 100)
    private val spaceScaleSlider = StudioTheme.hardwareSlider(20, 180, 100)

    private val xyCanvas = StompXyCanvas()

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 10, 12, 10, 12)
        }

        // Mode Switcher Bar
        val modeBar = StudioTheme.hardwarePanel(StudioTheme.TacCoral).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 10, 4)
            add(StudioTheme.hardwareLabel("LIVE STATION MODE:", StudioTheme.TacCoral, StudioTheme.TitleFont))
            add(modeLabel)
            DesktopLiveMode.entries.forEach { mode ->
                add(StudioTheme.hardwareButton(mode.title, StudioTheme.TacCoral) { onSetLiveMode(mode) })
            }
            add(bpmBadge)
            add(StudioTheme.hardwareButton("TAP TEMPO", StudioTheme.AmberHot) { onTapTempo() })
            add(StudioTheme.hardwareButton("SYNC ALL", StudioTheme.EmeraldSignal, filled = true) { onToggleSyncedPlay() })
        }
        root.add(modeBar)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))
        root.add(collabStrip)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        // Main Split: Left Stompbox Rack Controls + Right Interactive X/Y Pad
        val mainRow = object : JPanel(GridLayout(1, 2, StudioTheme.s(12), 0)) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false

            val stompCard = StudioTheme.hardwarePanel(StudioTheme.TacCoral, autoFitBoxHeight = false).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                val hdr = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 2).apply {
                    add(StudioTheme.hardwareLabel("STOMPBOX MINI-PROCESSOR", StudioTheme.TacCoral, StudioTheme.TitleFont))
                    add(StudioTheme.hardwareButton("ENGAGE / BYPASS", StudioTheme.TacCoral, filled = true) { onToggleStompBypass() })
                }
                val kills = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 2).apply {
                    add(killDriveCb)
                    add(killModCb)
                    add(killTimeCb)
                }
                add(hdr)
                add(kills)
                add(Box.createVerticalStrut(StudioTheme.s(6)))

                tiltSlider.addChangeListener {
                    if (!updating && !tiltSlider.valueIsAdjusting) onUpdateStomp { copy(engaged = true, toneTilt = tiltSlider.value / 100f) }
                }
                wetSlider.addChangeListener {
                    if (!updating && !wetSlider.valueIsAdjusting) onUpdateStomp { copy(engaged = true, masterWetMix = wetSlider.value / 100f) }
                }
                modScaleSlider.addChangeListener {
                    if (!updating && !modScaleSlider.valueIsAdjusting) onUpdateStomp { copy(engaged = true, modScale = modScaleSlider.value / 100f) }
                }
                spaceScaleSlider.addChangeListener {
                    if (!updating && !spaceScaleSlider.valueIsAdjusting) onUpdateStomp { copy(engaged = true, spaceScale = spaceScaleSlider.value / 100f) }
                }

                add(sliderPanel("DJ TONE TILT (%)", tiltSlider))
                add(sliderPanel("MASTER WET MIX (%)", wetSlider))
                add(sliderPanel("MODULATION GROUP SCALE (%)", modScaleSlider))
                add(sliderPanel("SPACE / REVERB / DELAY SCALE (%)", spaceScaleSlider))
            }

            val xyCard = StudioTheme.hardwarePanel(StudioTheme.NeonCyan, autoFitBoxHeight = false).apply {
                layout = BorderLayout(0, StudioTheme.s(6))
                add(StudioTheme.hardwareLabel("LIVE X/Y EXPRESSION PAD (X: TONE TILT / MOD • Y: SPACE / AMBIENCE)", StudioTheme.NeonCyan, StudioTheme.LabelFont), BorderLayout.NORTH)
                StudioTheme.setScaledPrefSize(xyCanvas, 320, 260)
                StudioTheme.setScaledMinSize(xyCanvas, 200, 180)
                add(xyCanvas, BorderLayout.CENTER)
            }

            add(stompCard)
            add(xyCard)
        }
        root.add(mainRow)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        // Master Release & Collaboration Console
        val releaseCard = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = StudioWrapLayout(FlowLayout.LEFT, 10, 6)
            add(StudioTheme.hardwareLabel("RELEASE & LIVE OUTPUT:", StudioTheme.EmeraldSignal, StudioTheme.TitleFont))
            add(StudioTheme.hardwareButton("EXPORT MASTER .WAV", StudioTheme.NeonCyan, filled = true) { onExportWav() })
            add(StudioTheme.hardwareButton("EXPORT RELEASE .MP3", StudioTheme.EmeraldSignal, filled = true) { onExportMp3() })
            add(StudioTheme.hardwareButton("HANDOFF SESSION TO MOBILE PEER", StudioTheme.AmberHot) { onPushHandoff() })
        }
        root.add(releaseCard)
        root.add(Box.createVerticalGlue())

        add(StudioTheme.styleScrollPane(JScrollPane(root)), BorderLayout.CENTER)
    }

    fun updateState(
        ui: DesktopStudioUiState,
        sync: DesktopSyncedPlayheadState,
        collabPeers: List<CollabPeerDevice> = emptyList()
    ) {
        updating = true
        try {
            collabStrip.updateState(collabPeers, ui.bandRecState)
            stompState = ui.stompState
            modeLabel.text = "${stompState.liveMode.title} (${if (stompState.engaged) "ACTIVE" else "BYPASSED"})"
            bpmBadge.text = "${sync.bpm.toInt()} BPM • BAR ${sync.bar}.${sync.beat}"
            killDriveCb.isSelected = stompState.killDrive
            killModCb.isSelected = stompState.killMod
            killTimeCb.isSelected = stompState.killTime
            tiltSlider.value = (stompState.toneTilt * 100).toInt().coerceIn(-100, 100)
            wetSlider.value = (stompState.masterWetMix * 100).toInt().coerceIn(0, 100)
            modScaleSlider.value = (stompState.modScale * 100).toInt().coerceIn(20, 180)
            spaceScaleSlider.value = (stompState.spaceScale * 100).toInt().coerceIn(20, 180)
            xyCanvas.repaint()
        } finally {
            updating = false
        }
    }

    private fun darkCheck(title: String, color: java.awt.Color, onToggle: (Boolean) -> Unit): JCheckBox =
        StudioTheme.darkCheckBox(title, color).apply {
            addActionListener { if (!updating) onToggle(isSelected) }
        }

    private fun sliderPanel(title: String, slider: javax.swing.JSlider): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        val lbl = StudioTheme.hardwareLabel("$title: ${slider.value}", StudioTheme.TextSecondary)
        slider.addChangeListener { lbl.text = "$title: ${slider.value}" }
        add(lbl, BorderLayout.NORTH)
        add(slider, BorderLayout.CENTER)
    }

    private inner class StompXyCanvas : JPanel() {
        init {
            background = StudioTheme.ObsidianChassis
            StudioTheme.setScaledPanelBorder(this, StudioTheme.HairlineDivider, 1, 2, 2)
            cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = sendXy(e.x, e.y)
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) = sendXy(e.x, e.y)
            })
        }

        private fun sendXy(mx: Int, my: Int) {
            val x = (mx.toFloat() / width.coerceAtLeast(1)).coerceIn(0f, 1f)
            val y = (1f - (my.toFloat() / height.coerceAtLeast(1))).coerceIn(0f, 1f)
            onXyDrag(x, y)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            g2.color = StudioTheme.withAlpha(StudioTheme.HairlineDivider, 120)
            g2.stroke = StudioTheme.stroke(1f)
            for (i in 1..3) {
                val gx = width * i / 4
                val gy = height * i / 4
                g2.drawLine(gx, 0, gx, height)
                g2.drawLine(0, gy, width, gy)
            }

            val px = (stompState.xyPadX * width).toInt()
            val py = ((1f - stompState.xyPadY) * height).toInt()
            g2.color = StudioTheme.withAlpha(StudioTheme.NeonCyan, 90)
            g2.stroke = StudioTheme.stroke(1.5f)
            g2.drawLine(px, 0, px, height)
            g2.drawLine(0, py, width, py)

            val innerR = StudioTheme.s(10)
            val outerR = StudioTheme.s(14)
            g2.color = StudioTheme.TacCoral
            g2.fillOval(px - innerR, py - innerR, innerR * 2, innerR * 2)
            g2.color = StudioTheme.NeonCyan
            g2.drawOval(px - outerR, py - outerR, outerR * 2, outerR * 2)

            g2.dispose()
        }
    }
}
