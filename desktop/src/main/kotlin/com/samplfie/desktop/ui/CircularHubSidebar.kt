package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.audio.DesktopMicState
import com.samplfie.desktop.audio.DesktopPlaybackState
import com.samplfie.desktop.model.DesktopStation
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
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Persistent Circular Production Hub Sidebar matching Samplfie2 Android's musician's studio view.
 * Keeps Master Transport, Customizable 8-Spoke Circular Radar Wheel, Peer Session Handoff,
 * VU Peak Metering, Stomp Bypass, Linux Mic Capture, and File I/O permanently visible across all stations.
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
    private val onExportMp3: () -> Unit
) : JPanel() {

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

    private val pushHandoffBtn = StudioTheme.hardwareButton("PUSH PEER", StudioTheme.NeonCyan) { onPushHandoff() }
    private val grabHandoffBtn = StudioTheme.hardwareButton("GRAB PEER", StudioTheme.AmberHot) { onGrabHandoff() }

    private val openBtn = StudioTheme.hardwareButton("OPEN WAV", StudioTheme.NeonCyan) { onOpenWav() }
    private val saveWavBtn = StudioTheme.hardwareButton("SAVE WAV", StudioTheme.EmeraldSignal) { onSaveWav() }
    private val exportMp3Btn = StudioTheme.hardwareButton("MP3", StudioTheme.EmeraldSignal) { onExportMp3() }

    private val rigLabel = StudioTheme.hardwareLabel("44.1kHz • 1CH • B1024 • UDP", StudioTheme.NeonCyan, StudioTheme.MonoFont)

    init {
        background = StudioTheme.GunmetalDeck
        preferredSize = Dimension(334, 760)
        border = BorderFactory.createMatteBorder(0, 0, 0, 1, StudioTheme.HairlineDivider)
        layout = BorderLayout(0, 8)

        val inner = JPanel().apply {
            background = StudioTheme.GunmetalDeck
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }

        val header = JPanel(BorderLayout()).apply {
            background = StudioTheme.GunmetalDeck
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            add(StudioTheme.hardwareLabel("CIRCULAR PRODUCTION HUB", StudioTheme.NeonCyan, StudioTheme.TitleFont), BorderLayout.WEST)
            add(StudioTheme.hardwareButton("BOOTH", StudioTheme.NeonCyan) { onSelectStation(DesktopStation.BOOTH) }.apply {
                preferredSize = Dimension(68, 24)
            }, BorderLayout.EAST)
        }
        inner.add(header)
        inner.add(Box.createVerticalStrut(4))
        rigLabel.alignmentX = LEFT_ALIGNMENT
        inner.add(rigLabel)
        inner.add(Box.createVerticalStrut(6))

        wheelCanvas.alignmentX = LEFT_ALIGNMENT
        wheelCanvas.preferredSize = Dimension(312, 312)
        wheelCanvas.maximumSize = Dimension(312, 312)
        inner.add(wheelCanvas)
        inner.add(Box.createVerticalStrut(8))

        // Master Transport Grid
        val transportPanel = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = GridLayout(2, 2, 6, 6)
            maximumSize = Dimension(Int.MAX_VALUE, 80)
            alignmentX = LEFT_ALIGNMENT
            add(playBtn)
            add(stopBtn)
            add(loopBtn)
            add(micBtn)
        }
        inner.add(transportPanel)
        inner.add(Box.createVerticalStrut(6))

        // Equal Peer Handoff Strip
        val handoffPanel = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = GridLayout(1, 2, 6, 0)
            maximumSize = Dimension(Int.MAX_VALUE, 46)
            alignmentX = LEFT_ALIGNMENT
            add(pushHandoffBtn)
            add(grabHandoffBtn)
        }
        inner.add(handoffPanel)
        inner.add(Box.createVerticalStrut(6))

        // Stomp + Master VU
        val masterPanel = StudioTheme.hardwarePanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(Int.MAX_VALUE, 84)
            alignmentX = LEFT_ALIGNMENT
            add(stompBtn.apply { alignmentX = LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 30) })
            add(Box.createVerticalStrut(6))
            vuMeterCanvas.alignmentX = LEFT_ALIGNMENT
            vuMeterCanvas.preferredSize = Dimension(280, 18)
            vuMeterCanvas.maximumSize = Dimension(Int.MAX_VALUE, 20)
            add(vuMeterCanvas)
        }
        inner.add(masterPanel)
        inner.add(Box.createVerticalStrut(6))

        // Quick File I/O Bar
        val ioPanel = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = GridLayout(1, 3, 6, 0)
            maximumSize = Dimension(Int.MAX_VALUE, 46)
            alignmentX = LEFT_ALIGNMENT
            add(openBtn)
            add(saveWavBtn)
            add(exportMp3Btn)
        }
        inner.add(ioPanel)
        inner.add(Box.createVerticalGlue())

        add(inner, BorderLayout.CENTER)
    }

    fun updateState(
        ui: DesktopStudioUiState,
        play: DesktopPlaybackState,
        mic: DesktopMicState,
        net: NetBridgeState
    ) {
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

        wheelCanvas.repaint()
        vuMeterCanvas.repaint()
    }

    private data class SpokeHit(val station: DesktopStation, val rect: RoundRectangle2D.Double, val accent: Color, val angleDeg: Double)

    private inner class CircularWheelCanvas : JPanel() {
        private var spokeHits = emptyList<SpokeHit>()
        private var hubCircle = Ellipse2D.Double()

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val p = Point(e.x, e.y)
                    if (hubCircle.contains(p)) {
                        onSelectStation(DesktopStation.BOOTH)
                        return
                    }
                    spokeHits.firstOrNull { it.rect.contains(p) }?.let { hit ->
                        onSelectStation(hit.station)
                    }
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val cx = width / 2.0
            val cy = height / 2.0
            val minDim = min(width, height).toDouble()
            val outerR = minDim * 0.365
            val innerR = minDim * 0.185

            g2.paint = RadialGradientPaint(
                cx.toFloat(), cy.toFloat(), (minDim * 0.49).toFloat(),
                floatArrayOf(0f, 0.65f, 1f),
                arrayOf(StudioTheme.withAlpha(StudioTheme.NeonCyan, 42), StudioTheme.GunmetalDeck, StudioTheme.ObsidianChassis)
            )
            g2.fill(Ellipse2D.Double(cx - minDim * 0.48, cy - minDim * 0.48, minDim * 0.96, minDim * 0.96))

            g2.color = StudioTheme.HairlineDivider
            g2.stroke = BasicStroke(1.5f)
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
            val nodeW = if (count > 5) 68.0 else 82.0
            val nodeH = if (count > 5) 36.0 else 42.0
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
                    g2.stroke = BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawLine(startX.toInt(), startY.toInt(), endX.toInt(), endY.toInt())
                }
                g2.color = if (active) accent else StudioTheme.withAlpha(accent, 150)
                g2.stroke = BasicStroke(if (active) 3.2f else 1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(startX.toInt(), startY.toInt(), endX.toInt(), endY.toInt())

                val boxX = (cx + (minDim * 0.36) * cos(rad) - nodeW / 2.0).coerceIn(2.0, width - nodeW - 2.0)
                val boxY = (cy + (minDim * 0.36) * sin(rad) - nodeH / 2.0).coerceIn(2.0, height - nodeH - 2.0)
                val rr = RoundRectangle2D.Double(boxX, boxY, nodeW, nodeH, 10.0, 10.0)
                hits.add(SpokeHit(st, rr, accent, deg))

                g2.color = if (active) StudioTheme.withAlpha(accent, 65) else StudioTheme.GunmetalDeck
                g2.fill(rr)
                g2.color = if (active) accent else StudioTheme.withAlpha(accent, 190)
                g2.stroke = BasicStroke(if (active) 2.2f else 1.2f)
                g2.draw(rr)

                g2.font = Font("SansSerif", Font.BOLD, if (count > 5) 9 else 10)
                val fm = g2.fontMetrics
                g2.color = StudioTheme.TextPrimary
                g2.drawString(st.shortLabel, (boxX + (nodeW - fm.stringWidth(st.shortLabel)) / 2).toFloat(), (boxY + 15).toFloat())

                val sub = when (st) {
                    DesktopStation.BOOTH -> "HUB"
                    DesktopStation.EDITOR -> "${"%.1f".format(uiState.processedBuffer?.durationSeconds ?: 0f)}s"
                    DesktopStation.SAMPLES -> "LOOPS"
                    DesktopStation.INSTRUMENTS -> "SEQ"
                    DesktopStation.TRACKS -> "SYNC"
                    DesktopStation.FX_STOMP -> "${uiState.fxState.activeCount} FX"
                    DesktopStation.LIVE -> uiState.stompState.liveMode.name.take(5)
                    DesktopStation.TUNER -> uiState.tunerReading.fullNote
                    DesktopStation.VMC -> "64CH"
                    DesktopStation.NET_LINK -> if (netState.isListening) ":${uiState.rigConfig.bindPort}" else uiState.rigConfig.protocol.label
                }
                g2.font = Font("Monospaced", Font.BOLD, 9)
                val fm2 = g2.fontMetrics
                g2.color = accent
                g2.drawString(sub, (boxX + (nodeW - fm2.stringWidth(sub)) / 2).toFloat(), (boxY + nodeH - 7).toFloat())
            }
            spokeHits = hits

            val peak = maxOf(playState.livePeak, micState.peakLevel, netState.rxLivePeak)
            val vuR = innerR * 1.22
            g2.color = StudioTheme.HairlineDivider
            g2.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(Arc2D.Double(cx - vuR, cy - vuR, vuR * 2, vuR * 2, 90.0, -360.0, Arc2D.OPEN))
            val sweep = if (playState.isPlaying) -(playState.playheadFraction * 360.0).coerceAtLeast(18.0) else -(peak * 360.0).coerceAtLeast(35.0)
            g2.color = if (playState.isPlaying) StudioTheme.EmeraldSignal else StudioTheme.NeonCyan
            g2.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(Arc2D.Double(cx - vuR, cy - vuR, vuR * 2, vuR * 2, 90.0, sweep, Arc2D.OPEN))

            val hubR = innerR * 0.94
            hubCircle = Ellipse2D.Double(cx - hubR, cy - hubR, hubR * 2, hubR * 2)
            g2.color = StudioTheme.ObsidianChassis
            g2.fill(hubCircle)
            g2.color = if (uiState.station == DesktopStation.BOOTH) StudioTheme.AmberHot
            else if (playState.isPlaying) StudioTheme.EmeraldSignal
            else StudioTheme.NeonCyan
            g2.stroke = BasicStroke(2f)
            g2.draw(hubCircle)

            g2.font = Font("SansSerif", Font.BOLD, 10)
            val hubTitle = "BOOTH"
            val fmHub = g2.fontMetrics
            g2.color = StudioTheme.NeonCyan
            g2.drawString(hubTitle, (cx - fmHub.stringWidth(hubTitle) / 2).toFloat(), (cy - 11).toFloat())

            val stateText = if (playState.isPlaying) "PLAYING" else if (micState.isRecording) "REC" else "READY"
            g2.font = Font("Monospaced", Font.BOLD, 11)
            val fmSt = g2.fontMetrics
            g2.color = if (playState.isPlaying) StudioTheme.EmeraldSignal else StudioTheme.TextPrimary
            g2.drawString(stateText, (cx - fmSt.stringWidth(stateText) / 2).toFloat(), (cy + 5).toFloat())

            val stationTag = uiState.station.shortLabel
            g2.font = Font("Monospaced", Font.PLAIN, 9)
            val fmTag = g2.fontMetrics
            g2.color = StudioTheme.TextSecondary
            g2.drawString(stationTag, (cx - fmTag.stringWidth(stationTag) / 2).toFloat(), (cy + 19).toFloat())

            g2.dispose()
        }
    }

    private inner class VuMeterCanvas : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            val peak = maxOf(playState.livePeak, micState.peakLevel, netState.rxLivePeak, uiState.processedBuffer?.peakLevel() ?: 0f)
            val segments = 24
            val gap = 2
            val segW = ((width - 42) - gap * (segments - 1)) / segments.toFloat()
            val lit = (peak.coerceIn(0f, 1f) * segments).toInt()
            g2.font = Font("Monospaced", Font.BOLD, 10)
            g2.color = StudioTheme.TextSecondary
            g2.drawString("MST", 0, 14)

            for (i in 0 until segments) {
                val c = when {
                    i >= segments - 3 -> StudioTheme.TacCoral
                    i >= segments - 7 -> StudioTheme.AmberHot
                    else -> StudioTheme.EmeraldSignal
                }
                g2.color = StudioTheme.withAlpha(c, if (i < lit) 245 else 40)
                val x = (32 + i * (segW + gap)).toInt()
                g2.fillRoundRect(x, 3, segW.toInt().coerceAtLeast(2), 12, 3, 3)
            }
            g2.dispose()
        }
    }
}
