package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.audio.DesktopMicState
import com.samplfie.desktop.audio.DesktopPlaybackState
import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.network.CollabPeerDevice
import com.samplfie.desktop.network.NetBridgeState
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Point
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Persistent Circular Production Hub Sidebar matching Samplfie2 Android's musician's studio view.
 * Keeps Master Transport, Customizable 8-Spoke Circular Radar Wheel with Center Master Volume Control,
 * Peer Session Handoff, VU Peak Metering, Stomp Bypass, Linux Mic Capture, and File I/O visible across all stations.
 */
class CircularHubSidebar(
    private val onSelectStation: (DesktopStation) -> Unit,
    private val onTogglePlay: () -> Unit,
    private val onStop: () -> Unit,
    private val onToggleLoop: () -> Unit,
    private val onToggleMic: () -> Unit,
    private val onToggleStompBypass: () -> Unit,
    private val onPushHandoff: () -> Unit,
    private val onGrabHandoff: () -> Unit,
    private val onOpenWav: () -> Unit,
    private val onSaveWav: () -> Unit,
    private val onExportMp3: () -> Unit,
    private val onMasterVolumeChanged: (Float) -> Unit = {},
    private val onToggleMasterMute: () -> Unit = {},
    private val onOpenCollabDialog: () -> Unit = {},
    private val onToggleBandRec: () -> Unit = {}
) : JPanel() {

    private var updating = false
    private var uiState = DesktopStudioUiState()
    private var playState = DesktopPlaybackState()
    private var micState = DesktopMicState()
    private var netState = NetBridgeState()

    private val wheelCanvas = CircularWheelCanvas()
    private val vuMeterCanvas = VuMeterCanvas()

    private val playBtn = StudioTheme.hardwareButton("PLAY", StudioTheme.EmeraldSignal, filled = true) { onTogglePlay() }
    private val stopBtn = StudioTheme.hardwareButton("STOP", StudioTheme.TextSecondary) { onStop() }
    private val loopBtn = StudioTheme.hardwareButton("LOOP", StudioTheme.AmberHot) { onToggleLoop() }
    private val micBtn = StudioTheme.hardwareButton("REC MIC", StudioTheme.TacCoral) { onToggleMic() }
    private val stompBtn = StudioTheme.hardwareButton("STOMP ON", StudioTheme.VioletMod) { onToggleStompBypass() }

    private val masterMuteBtn = StudioTheme.hardwareButton("VOL", StudioTheme.EmeraldSignal) { onToggleMasterMute() }
    private val masterVolumeSlider = StudioTheme.hardwareSlider(0, 100, 85).apply {
        addChangeListener {
            if (!updating) {
                onMasterVolumeChanged(value / 100f)
            }
        }
    }
    private val masterVolValueLabel = StudioTheme.hardwareLabel("85%", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)

    private val pushHandoffBtn = StudioTheme.hardwareButton("PUSH PEER", StudioTheme.NeonCyan) { onPushHandoff() }
    private val grabHandoffBtn = StudioTheme.hardwareButton("GRAB PEER", StudioTheme.AmberHot) { onGrabHandoff() }
    private val bandLinkBtn = StudioTheme.hardwareButton("BAND 0/4", StudioTheme.NeonCyan) { onOpenCollabDialog() }
    private val bandRecBtn = StudioTheme.hardwareButton("BAND REC", StudioTheme.TacCoral) { onToggleBandRec() }

    private val openBtn = StudioTheme.hardwareButton("OPEN WAV", StudioTheme.NeonCyan) { onOpenWav() }
    private val saveWavBtn = StudioTheme.hardwareButton("SAVE WAV", StudioTheme.EmeraldSignal) { onSaveWav() }
    private val exportMp3Btn = StudioTheme.hardwareButton("MP3", StudioTheme.EmeraldSignal) { onExportMp3() }

    private val rigLabel = StudioTheme.hardwareLabel("44.1kHz • 1CH • B1024 • UDP", StudioTheme.NeonCyan, StudioTheme.MonoFont)

    init {
        background = StudioTheme.GunmetalDeck
        StudioTheme.setScaledPrefSize(this, 360, 760)
        border = BorderFactory.createMatteBorder(0, 0, 0, 1, StudioTheme.HairlineDivider)
        layout = BorderLayout(0, 0)

        val inner = JPanel().apply {
            background = StudioTheme.GunmetalDeck
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 10, 10, 10, 10)
        }

        val header = JPanel(BorderLayout(6, 4)).apply {
            background = StudioTheme.GunmetalDeck
            alignmentX = LEFT_ALIGNMENT
            add(StudioTheme.hardwareLabel("PRODUCTION HUB", StudioTheme.NeonCyan, StudioTheme.TitleFont), BorderLayout.CENTER)
            add(StudioTheme.hardwareButton("BOOTH", StudioTheme.NeonCyan) { onSelectStation(DesktopStation.BOOTH) }, BorderLayout.EAST)
        }
        inner.add(header)
        inner.add(Box.createVerticalStrut(4))
        rigLabel.alignmentX = LEFT_ALIGNMENT
        inner.add(rigLabel)
        inner.add(Box.createVerticalStrut(6))

        wheelCanvas.alignmentX = LEFT_ALIGNMENT
        StudioTheme.setScaledPrefSize(wheelCanvas, 340, 340)
        StudioTheme.setScaledMinSize(wheelCanvas, 260, 260)
        StudioTheme.setScaledMaxSize(wheelCanvas, Int.MAX_VALUE, 480)
        inner.add(wheelCanvas)
        inner.add(Box.createVerticalStrut(8))

        // Master Transport Grid
        val transportPanel = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = GridLayout(2, 2, 6, 6)
            alignmentX = LEFT_ALIGNMENT
            add(playBtn)
            add(stopBtn)
            add(loopBtn)
            add(micBtn)
        }
        inner.add(transportPanel)
        inner.add(Box.createVerticalStrut(6))

        // Equal Peer Handoff & 4-Device Band Strip
        val handoffPanel = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = GridLayout(2, 2, 6, 6)
            alignmentX = LEFT_ALIGNMENT
            add(pushHandoffBtn)
            add(grabHandoffBtn)
            add(bandLinkBtn)
            add(bandRecBtn)
        }
        inner.add(handoffPanel)
        inner.add(Box.createVerticalStrut(6))

        // Stomp + Master VU + Master Volume Control
        val masterPanel = StudioTheme.hardwarePanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            add(stompBtn.apply { alignmentX = LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(6))
            vuMeterCanvas.alignmentX = LEFT_ALIGNMENT
            StudioTheme.setScaledPrefSize(vuMeterCanvas, 300, 22)
            StudioTheme.setScaledMaxSize(vuMeterCanvas, Int.MAX_VALUE, 28)
            add(vuMeterCanvas)
            add(Box.createVerticalStrut(6))
            val volRow = JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                alignmentX = LEFT_ALIGNMENT
                add(masterMuteBtn, BorderLayout.WEST)
                add(masterVolumeSlider, BorderLayout.CENTER)
                add(masterVolValueLabel, BorderLayout.EAST)
            }
            add(volRow)
        }
        inner.add(masterPanel)
        inner.add(Box.createVerticalStrut(6))

        // Quick File I/O Bar
        val ioPanel = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = GridLayout(1, 3, 6, 0)
            alignmentX = LEFT_ALIGNMENT
            add(openBtn)
            add(saveWavBtn)
            add(exportMp3Btn)
        }
        inner.add(ioPanel)
        inner.add(Box.createVerticalGlue())

        add(StudioTheme.styleScrollPane(
            javax.swing.JScrollPane(inner, javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply {
                background = StudioTheme.GunmetalDeck
                viewport.background = StudioTheme.GunmetalDeck
            }
        ), BorderLayout.CENTER)
    }

    fun updateState(
        ui: DesktopStudioUiState,
        play: DesktopPlaybackState,
        mic: DesktopMicState,
        net: NetBridgeState,
        collabPeers: List<CollabPeerDevice> = emptyList()
    ) {
        updating = true
        try {
            uiState = ui
            playState = play
            micState = mic
            netState = net

            rigLabel.text = "${ui.rigConfig.sampleRate / 1000.0}kHz • ${ui.rigConfig.channels}CH • B${ui.rigConfig.bufferSize} • ${ui.rigConfig.protocol.label}"
            playBtn.text = if (play.isPlaying) "PAUSE" else "PLAY"
            loopBtn.text = if (play.isLooping) "LOOP: ON" else "LOOP: OFF"
            micBtn.text = if (mic.isRecording) "STOP REC" else "REC MIC"
            stompBtn.text = if (ui.stompState.engaged && !ui.fxState.masterBypass) {
                "STOMP ON (${ui.fxState.activeCount} FX)"
            } else {
                "MASTER BYPASS"
            }

            val volPct = (ui.masterVolume.coerceIn(0f, 1f) * 100f).roundToInt()
            if (!masterVolumeSlider.valueIsAdjusting) {
                masterVolumeSlider.value = volPct
            }
            masterMuteBtn.text = if (ui.isMasterMuted) "MUTE" else "VOL"
            masterMuteBtn.foreground = if (ui.isMasterMuted) StudioTheme.TacCoral else StudioTheme.EmeraldSignal
            masterVolValueLabel.text = if (ui.isMasterMuted) "MUTE" else "$volPct%"
            masterVolValueLabel.foreground = if (ui.isMasterMuted) StudioTheme.TacCoral else StudioTheme.EmeraldSignal

            val linked = collabPeers.count { it.isConnected }
            bandLinkBtn.text = "BAND $linked/4 (${ui.bandRecState.myStyle.shortTag})"
            bandRecBtn.text = if (ui.bandRecState.isRecording) "STOP BAND" else "BAND REC"

            wheelCanvas.repaint()
            vuMeterCanvas.repaint()
        } finally {
            updating = false
        }
    }

    private data class SpokeHit(val station: DesktopStation, val rect: RoundRectangle2D.Double, val accent: Color, val angleDeg: Double)

    private inner class CircularWheelCanvas : JPanel() {
        private var spokeHits = emptyList<SpokeHit>()
        private var hubCircle = Ellipse2D.Double()
        private var boothNavHit = RoundRectangle2D.Double()
        private var volMinusHit = RoundRectangle2D.Double()
        private var muteHit = RoundRectangle2D.Double()
        private var volPlusHit = RoundRectangle2D.Double()

        private var dragStartY: Int? = null
        private var dragStartVol: Float = 0.85f
        private var didDragVol = false

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Click BOOTH/spokes, - / MUTE / +, or drag/scroll wheel inside Hub Center for Master Volume"

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val p = Point(e.x, e.y)
                    didDragVol = false
                    if (hubCircle.contains(p) &&
                        !boothNavHit.contains(p) &&
                        !volMinusHit.contains(p) &&
                        !muteHit.contains(p) &&
                        !volPlusHit.contains(p)
                    ) {
                        dragStartY = e.y
                        dragStartVol = uiState.masterVolume
                    } else {
                        dragStartY = null
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (didDragVol) {
                        dragStartY = null
                        didDragVol = false
                        return
                    }
                    dragStartY = null
                    val p = Point(e.x, e.y)
                    when {
                        volMinusHit.contains(p) -> onMasterVolumeChanged((uiState.masterVolume - 0.05f).coerceIn(0f, 1f))
                        volPlusHit.contains(p) -> onMasterVolumeChanged((uiState.masterVolume + 0.05f).coerceIn(0f, 1f))
                        muteHit.contains(p) -> onToggleMasterMute()
                        boothNavHit.contains(p) -> onSelectStation(DesktopStation.BOOTH)
                        hubCircle.contains(p) -> onToggleMasterMute()
                        else -> spokeHits.firstOrNull { it.rect.contains(p) }?.let { onSelectStation(it.station) }
                    }
                }
            })

            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val sy = dragStartY ?: return
                    val dy = sy - e.y
                    if (abs(dy) >= 3) {
                        didDragVol = true
                        val sensitivity = StudioTheme.sf(110f).coerceAtLeast(60f)
                        val nextVol = (dragStartVol + dy / sensitivity).coerceIn(0f, 1f)
                        onMasterVolumeChanged(nextVol)
                    }
                }
            })

            addMouseWheelListener { e: MouseWheelEvent ->
                if (hubCircle.contains(e.point) && !e.isControlDown) {
                    val delta = if (e.wheelRotation < 0) 0.04f else -0.04f
                    onMasterVolumeChanged((uiState.masterVolume + delta).coerceIn(0f, 1f))
                    e.consume()
                }
            }
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val cx = width / 2.0
            val cy = height / 2.0
            val minDim = min(width, height).toDouble()
            val outerR = minDim * 0.365
            val innerR = minDim * 0.21

            g2.paint = RadialGradientPaint(
                cx.toFloat(), cy.toFloat(), (minDim * 0.49).toFloat(),
                floatArrayOf(0f, 0.65f, 1f),
                arrayOf(StudioTheme.withAlpha(StudioTheme.NeonCyan, 42), StudioTheme.GunmetalDeck, StudioTheme.ObsidianChassis)
            )
            g2.fill(Ellipse2D.Double(cx - minDim * 0.48, cy - minDim * 0.48, minDim * 0.96, minDim * 0.96))

            g2.color = StudioTheme.HairlineDivider
            g2.stroke = StudioTheme.stroke(1.5f)
            g2.draw(Ellipse2D.Double(cx - outerR, cy - outerR, outerR * 2, outerR * 2))
            for (deg in 0 until 360 step 15) {
                val r = Math.toRadians(deg.toDouble())
                val tIn = outerR * 0.93
                val tOut = outerR * 1.03
                g2.color = StudioTheme.withAlpha(StudioTheme.HairlineDivider, if (deg % 45 == 0) 210 else 95)
                g2.drawLine(
                    (cx + tIn * cos(r)).toInt(), (cy + tIn * sin(r)).toInt(),
                    (cx + tOut * cos(r)).toInt(), (cy + tOut * sin(r)).toInt()
                )
            }

            val wheelStations = uiState.workspaceConfig.radialWheelStations.ifEmpty {
                listOf(
                    DesktopStation.EDITOR, DesktopStation.SAMPLES, DesktopStation.INSTRUMENTS,
                    DesktopStation.TUNER, DesktopStation.LIVE, DesktopStation.VMC,
                    DesktopStation.TRACKS, DesktopStation.FX_STOMP
                )
            }
            val count = wheelStations.size
            val nodeW = minDim * (if (count > 5) 0.225 else 0.265)
            val nodeH = minDim * (if (count > 5) 0.12 else 0.14)
            val hits = mutableListOf<SpokeHit>()

            wheelStations.forEachIndexed { idx, st ->
                val deg = -90.0 + (360.0 / count) * idx
                val accent = ProductionBoothPanel.stationAccent(st)
                val active = uiState.station == st
                val rad = Math.toRadians(deg)
                val endX = cx + outerR * cos(rad)
                val endY = cy + outerR * sin(rad)
                val startX = cx + innerR * cos(rad)
                val startY = cy + innerR * sin(rad)

                if (active) {
                    g2.color = StudioTheme.withAlpha(accent, 75)
                    g2.stroke = StudioTheme.stroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawLine(startX.toInt(), startY.toInt(), endX.toInt(), endY.toInt())
                }
                g2.color = if (active) accent else StudioTheme.withAlpha(accent, 150)
                g2.stroke = StudioTheme.stroke(if (active) 3.2f else 1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(startX.toInt(), startY.toInt(), endX.toInt(), endY.toInt())

                val pad = StudioTheme.sd(2.0)
                val boxX = (cx + (minDim * 0.365) * cos(rad) - nodeW / 2.0).coerceIn(pad, width - nodeW - pad)
                val boxY = (cy + (minDim * 0.365) * sin(rad) - nodeH / 2.0).coerceIn(pad, height - nodeH - pad)
                val arc = StudioTheme.sd(10.0)
                val rr = RoundRectangle2D.Double(boxX, boxY, nodeW, nodeH, arc, arc)
                hits.add(SpokeHit(st, rr, accent, deg))

                g2.color = if (active) StudioTheme.withAlpha(accent, 65) else StudioTheme.GunmetalDeck
                g2.fill(rr)
                g2.color = if (active) accent else StudioTheme.withAlpha(accent, 190)
                g2.stroke = StudioTheme.stroke(if (active) 2.2f else 1.2f)
                g2.draw(rr)

                g2.font = StudioTheme.font("SansSerif", Font.BOLD, if (count > 5) 9.5f else 10.5f)
                val fm = g2.fontMetrics
                g2.color = StudioTheme.TextPrimary
                g2.drawString(st.shortLabel, (boxX + (nodeW - fm.stringWidth(st.shortLabel)) / 2).toFloat(), (boxY + nodeH * 0.45).toFloat())

                val sub = when (st) {
                    DesktopStation.BOOTH -> "HUB"
                    DesktopStation.EDITOR -> "${"%.1f".format(uiState.processedBuffer?.durationSeconds ?: 0f)}s"
                    DesktopStation.SAMPLES -> "LOOPS"
                    DesktopStation.INSTRUMENTS -> "SEQ"
                    DesktopStation.TRACKS -> "SYNC"
                    DesktopStation.FX_STOMP -> "${uiState.fxState.activeCount}/14 FX"
                    DesktopStation.LIVE -> uiState.stompState.liveMode.name.take(5)
                    DesktopStation.TOOLS -> "PRO"
                    DesktopStation.TUNER -> uiState.tunerReading.fullNote
                    DesktopStation.VMC -> "64CH"
                    DesktopStation.NET_LINK -> if (netState.isListening) ":${uiState.rigConfig.bindPort}" else uiState.rigConfig.protocol.label
                }
                g2.font = StudioTheme.font("Monospaced", Font.BOLD, 9f)
                val fm2 = g2.fontMetrics
                g2.color = accent
                g2.drawString(sub, (boxX + (nodeW - fm2.stringWidth(sub)) / 2).toFloat(), (boxY + nodeH * 0.84).toFloat())
            }
            spokeHits = hits

            val peak = maxOf(playState.livePeak, micState.peakLevel, netState.rxLivePeak)
            val vuR = innerR * 1.15
            g2.color = StudioTheme.HairlineDivider
            g2.stroke = StudioTheme.stroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(Arc2D.Double(cx - vuR, cy - vuR, vuR * 2, vuR * 2, 90.0, -360.0, Arc2D.OPEN))
            val sweep = if (playState.isPlaying) -(playState.playheadFraction * 360.0).coerceAtLeast(18.0) else -(peak * 360.0).coerceAtLeast(35.0)
            g2.color = if (playState.isPlaying) StudioTheme.EmeraldSignal else StudioTheme.NeonCyan
            g2.stroke = StudioTheme.stroke(3.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(Arc2D.Double(cx - vuR, cy - vuR, vuR * 2, vuR * 2, 90.0, sweep, Arc2D.OPEN))

            // Center Hub Chassis + Master Volume Rotary Arc
            val hubR = innerR * 0.96
            hubCircle = Ellipse2D.Double(cx - hubR, cy - hubR, hubR * 2, hubR * 2)
            g2.color = StudioTheme.ObsidianChassis
            g2.fill(hubCircle)

            val masterVol = uiState.masterVolume.coerceIn(0f, 1f)
            val muted = uiState.isMasterMuted
            val volPct = (masterVol * 100f).roundToInt()

            // Background arc & active Master Volume Arc (270 degrees clockwise from bottom-left 225° -> -270°)
            val arcR = hubR * 0.90
            g2.color = StudioTheme.HairlineDivider
            g2.stroke = StudioTheme.stroke(4.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(Arc2D.Double(cx - arcR, cy - arcR, arcR * 2, arcR * 2, 225.0, -270.0, Arc2D.OPEN))

            val volColor = if (muted) StudioTheme.TacCoral else StudioTheme.EmeraldSignal
            g2.color = volColor
            g2.stroke = StudioTheme.stroke(4.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(Arc2D.Double(cx - arcR, cy - arcR, arcR * 2, arcR * 2, 225.0, -(270.0 * masterVol), Arc2D.OPEN))

            // Top pill: BOOTH nav
            val navW = hubR * 0.98
            val navH = hubR * 0.30
            boothNavHit = RoundRectangle2D.Double(cx - navW / 2.0, cy - hubR * 0.72, navW, navH, StudioTheme.sd(8.0), StudioTheme.sd(8.0))
            g2.color = if (uiState.station == DesktopStation.BOOTH) StudioTheme.withAlpha(StudioTheme.AmberHot, 65) else StudioTheme.withAlpha(StudioTheme.NeonCyan, 35)
            g2.fill(boothNavHit)
            g2.color = if (uiState.station == DesktopStation.BOOTH) StudioTheme.AmberHot else StudioTheme.NeonCyan
            g2.stroke = StudioTheme.stroke(1.2f)
            g2.draw(boothNavHit)
            g2.font = StudioTheme.font("SansSerif", Font.BOLD, 9.5f)
            val fmHub = g2.fontMetrics
            g2.drawString("BOOTH", (cx - fmHub.stringWidth("BOOTH") / 2.0).toFloat(), (boothNavHit.y + (navH + fmHub.ascent - fmHub.descent) / 2.0).toFloat())

            // Center readout: Master Level & transport state
            val mstText = if (muted) "MUTED" else "MST $volPct%"
            g2.font = StudioTheme.font("Monospaced", Font.BOLD, 12.5f)
            val fmMst = g2.fontMetrics
            g2.color = volColor
            g2.drawString(mstText, (cx - fmMst.stringWidth(mstText) / 2.0).toFloat(), (cy + fmMst.ascent * 0.25).toFloat())

            val subStatus = if (playState.isPlaying) "PLAYING" else if (micState.isRecording) "REC" else uiState.station.shortLabel
            g2.font = StudioTheme.font("Monospaced", Font.PLAIN, 8.5f)
            val fmSub = g2.fontMetrics
            g2.color = StudioTheme.TextSecondary
            g2.drawString(subStatus, (cx - fmSub.stringWidth(subStatus) / 2.0).toFloat(), (cy - fmMst.ascent * 0.72).toFloat())

            // Bottom master control pills inside center hub: [-] [MUTE/VOL] [+]
            val ctrlY = cy + hubR * 0.26
            val ctrlH = hubR * 0.34
            val sideW = hubR * 0.36
            val midW = hubR * 0.62
            val gap = hubR * 0.06
            val totalW = sideW * 2 + midW + gap * 2
            val startCtrlX = cx - totalW / 2.0

            volMinusHit = RoundRectangle2D.Double(startCtrlX, ctrlY, sideW, ctrlH, StudioTheme.sd(7.0), StudioTheme.sd(7.0))
            muteHit = RoundRectangle2D.Double(startCtrlX + sideW + gap, ctrlY, midW, ctrlH, StudioTheme.sd(7.0), StudioTheme.sd(7.0))
            volPlusHit = RoundRectangle2D.Double(startCtrlX + sideW + gap * 2 + midW, ctrlY, sideW, ctrlH, StudioTheme.sd(7.0), StudioTheme.sd(7.0))

            drawMiniHubBtn(g2, volMinusHit, "-", StudioTheme.NeonCyan, filled = false)
            drawMiniHubBtn(g2, muteHit, if (muted) "UNMT" else "MUTE", if (muted) StudioTheme.TacCoral else StudioTheme.AmberHot, filled = muted)
            drawMiniHubBtn(g2, volPlusHit, "+", StudioTheme.NeonCyan, filled = false)

            g2.dispose()
        }

        private fun drawMiniHubBtn(g2: Graphics2D, rect: RoundRectangle2D.Double, label: String, accent: Color, filled: Boolean) {
            g2.color = if (filled) accent else StudioTheme.withAlpha(accent, 34)
            g2.fill(rect)
            g2.color = accent
            g2.stroke = StudioTheme.stroke(1.1f)
            g2.draw(rect)
            g2.font = StudioTheme.font("Monospaced", Font.BOLD, 9f)
            val fm = g2.fontMetrics
            g2.color = if (filled) StudioTheme.ObsidianChassis else StudioTheme.TextPrimary
            val tx = (rect.x + (rect.width - fm.stringWidth(label)) / 2.0).toFloat()
            val ty = (rect.y + (rect.height + fm.ascent - fm.descent) / 2.0).toFloat()
            g2.drawString(label, tx, ty)
        }
    }

    private inner class VuMeterCanvas : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val peak = maxOf(playState.livePeak, micState.peakLevel, netState.rxLivePeak, uiState.processedBuffer?.peakLevel() ?: 0f)
            val segments = 24
            val gap = StudioTheme.s(2)
            g2.font = StudioTheme.font("Monospaced", Font.BOLD, 10.5f)
            val fm = g2.fontMetrics
            val labelW = fm.stringWidth("MST") + StudioTheme.s(8)
            val segW = ((width - labelW - StudioTheme.s(6)) - gap * (segments - 1)) / segments.toFloat()
            val lit = (peak.coerceIn(0f, 1f) * segments).toInt()
            g2.color = StudioTheme.TextSecondary
            g2.drawString("MST", 0, (height + fm.ascent - fm.descent) / 2)

            val barY = StudioTheme.s(3)
            val barH = (height - barY * 2).coerceAtLeast(StudioTheme.s(8))
            val arc = StudioTheme.s(3)
            for (i in 0 until segments) {
                val c = when {
                    i >= segments - 3 -> StudioTheme.TacCoral
                    i >= segments - 7 -> StudioTheme.AmberHot
                    else -> StudioTheme.EmeraldSignal
                }
                g2.color = StudioTheme.withAlpha(c, if (i < lit) 245 else 40)
                val x = (labelW + i * (segW + gap)).toInt()
                g2.fillRoundRect(x, barY, segW.toInt().coerceAtLeast(2), barH, arc, arc)
            }
            g2.dispose()
        }
    }
}
