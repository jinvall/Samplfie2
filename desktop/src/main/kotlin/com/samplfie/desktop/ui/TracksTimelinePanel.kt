package com.samplfie.desktop.ui

import com.samplfie.desktop.studio.DesktopSyncedPlayheadState
import com.samplfie.desktop.studio.DesktopTimelineState
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Synced Multi-Track Timeline Station for Desktop:
 * - Globally Synced Master Playhead (syncs Deck, Looper, 16-Step Sequencer & 4-Track Timeline)
 * - Scrubbable Beat/Time Ruler & Clip Lanes (Vocal/Mic, Looper Mix, Seq Beat, FX/Dub)
 * - Clip nudge (-0.5s / +0.5s), Mute/Solo per track, and Full Mixdown -> Deck
 */
class TracksTimelinePanel(
    private val onToggleSyncAll: () -> Unit,
    private val onToggleTimelinePlay: () -> Unit,
    private val onSeekPlayheadSec: (Float) -> Unit,
    private val onSetBpm: (Float) -> Unit,
    private val onAddDeckToTrack: (Int) -> Unit,
    private val onAddLooperMixToTrack2: () -> Unit,
    private val onAddSeqBeatToTrack3: () -> Unit,
    private val onMixdownToDeck: () -> Unit,
    private val onToggleMute: (Int) -> Unit,
    private val onToggleSolo: (Int) -> Unit,
    private val onNudgeClip: (Int, String, Float) -> Unit,
    private val onRemoveClip: (Int, String) -> Unit
) : JPanel() {

    private var updating = false
    private var timelineState = DesktopTimelineState()
    private var syncState = DesktopSyncedPlayheadState()

    private val syncPlayBtn = StudioTheme.hardwareButton("SYNC ALL PLAY", StudioTheme.EmeraldSignal, filled = true) { onToggleSyncAll() }
    private val tlPlayBtn = StudioTheme.hardwareButton("PLAY TIMELINE", StudioTheme.VioletMod) { onToggleTimelinePlay() }
    private val playheadLabel = StudioTheme.hardwareLabel("BAR 1.1 • 0.00s @ 124 BPM", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val bpmSlider = StudioTheme.hardwareSlider(60, 180, 124)

    private val timelineCanvas = TimelineArrangementCanvas()
    private val trackRowsHost = JPanel().apply {
        background = StudioTheme.ObsidianChassis
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout(0, StudioTheme.s(8))
        StudioTheme.setScaledPadding(this, 10, 12, 10, 12)

        val topPanel = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val row1 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareLabel("SYNCED TRACK TIMELINE", StudioTheme.VioletMod, StudioTheme.TitleFont))
                add(syncPlayBtn)
                add(tlPlayBtn)
                add(playheadLabel)
                bpmSlider.addChangeListener {
                    if (!updating && !bpmSlider.valueIsAdjusting) onSetBpm(bpmSlider.value.toFloat())
                }
                add(bpmSlider)
            }
            val row2 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 4).apply {
                add(StudioTheme.hardwareButton("+ DECK -> TRACK 1", StudioTheme.NeonCyan) { onAddDeckToTrack(0) })
                add(StudioTheme.hardwareButton("+ LOOPER -> TRACK 2", StudioTheme.EmeraldSignal) { onAddLooperMixToTrack2() })
                add(StudioTheme.hardwareButton("+ SEQ BEAT -> TRACK 3", StudioTheme.AmberHot) { onAddSeqBeatToTrack3() })
                add(StudioTheme.hardwareButton("+ DECK -> TRACK 4 (FX)", StudioTheme.MagentaFx) { onAddDeckToTrack(3) })
                add(StudioTheme.hardwareButton("MIXDOWN -> DECK", StudioTheme.EmeraldSignal, filled = true) { onMixdownToDeck() })
            }
            add(row1)
            add(row2)
        }
        add(topPanel, BorderLayout.NORTH)

        val centerSplit = JPanel(BorderLayout(0, StudioTheme.s(10))).apply {
            isOpaque = false
            StudioTheme.setScaledPrefSize(timelineCanvas, 860, 270)
            StudioTheme.setScaledMinSize(timelineCanvas, 400, 220)
            add(timelineCanvas, BorderLayout.NORTH)
            add(StudioTheme.styleScrollPane(JScrollPane(trackRowsHost)), BorderLayout.CENTER)
        }
        add(centerSplit, BorderLayout.CENTER)
    }

    fun updateState(tl: DesktopTimelineState, sync: DesktopSyncedPlayheadState) {
        updating = true
        try {
            val changedTracks = tl.tracks != timelineState.tracks
            timelineState = tl
            syncState = sync

            syncPlayBtn.text = if (sync.isPlaying) "STOP SYNC ALL" else "SYNC ALL PLAY"
            tlPlayBtn.text = if (tl.isPlaying) "STOP TIMELINE" else "PLAY TIMELINE"
            bpmSlider.value = sync.bpm.toInt().coerceIn(60, 180)
            val sec = if (sync.isPlaying) sync.playheadSec else tl.playheadSec
            playheadLabel.text = "BAR ${sync.bar}.${sync.beat} • ${"%.2f".format(sec)}s / ${"%.1f".format(tl.totalDurationSec)}s @ ${sync.bpm.toInt()} BPM"

            timelineCanvas.repaint()
            if (changedTracks) rebuildClipControls(tl)
        } finally {
            updating = false
        }
    }

    private fun rebuildClipControls(tl: DesktopTimelineState) {
        trackRowsHost.removeAll()
        val colors = listOf(StudioTheme.NeonCyan, StudioTheme.EmeraldSignal, StudioTheme.AmberHot, StudioTheme.MagentaFx)
        tl.tracks.forEachIndexed { idx, track ->
            val accent = colors.getOrElse(idx) { StudioTheme.VioletMod }
            val panel = StudioTheme.hardwarePanel(accent).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                val header = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 2).apply {
                    add(StudioTheme.hardwareLabel(track.name, accent, StudioTheme.TitleFont))
                    add(StudioTheme.hardwareButton(if (track.isMuted) "UNMUTE" else "MUTE", StudioTheme.TacCoral) {
                        onToggleMute(track.id)
                    })
                    add(StudioTheme.hardwareButton(if (track.isSolo) "SOLO ON" else "SOLO", StudioTheme.AmberHot) {
                        onToggleSolo(track.id)
                    })
                }
                add(header)
                val clipsRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 6, 2).apply {
                    if (track.clips.isEmpty()) {
                        add(StudioTheme.hardwareLabel("NO CLIPS ON TRACK", StudioTheme.TextMuted, StudioTheme.MonoFont))
                    } else {
                        track.clips.forEach { clip ->
                            add(StudioTheme.hardwareLabel("${clip.label} @${"%.1f".format(clip.startTimeSec)}s", StudioTheme.TextPrimary, StudioTheme.MonoFont))
                            add(StudioTheme.hardwareButton("-0.5s", accent) { onNudgeClip(track.id, clip.id, -0.5f) })
                            add(StudioTheme.hardwareButton("+0.5s", accent) { onNudgeClip(track.id, clip.id, 0.5f) })
                            add(StudioTheme.hardwareButton("X", StudioTheme.TacCoral) { onRemoveClip(track.id, clip.id) })
                        }
                    }
                }
                add(clipsRow)
            }
            trackRowsHost.add(panel)
            trackRowsHost.add(Box.createVerticalStrut(StudioTheme.s(6)))
        }
        trackRowsHost.revalidate()
        trackRowsHost.repaint()
    }

    private inner class TimelineArrangementCanvas : JPanel() {
        init {
            background = StudioTheme.ObsidianChassis
            StudioTheme.setScaledPanelBorder(this, StudioTheme.HairlineDivider, 1, 2, 2)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val leftPad = StudioTheme.sf(155f)
                    val usable = (width - leftPad - StudioTheme.sf(12f)).coerceAtLeast(1f)
                    val frac = ((e.x - leftPad) / usable).coerceIn(0f, 1f)
                    onSeekPlayheadSec(frac * timelineState.totalDurationSec)
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val leftPad = StudioTheme.sf(155f)
            val totalSec = timelineState.totalDurationSec.coerceAtLeast(4f)
            val usableW = (width - leftPad - StudioTheme.sf(12f)).coerceAtLeast(1f)
            val rulerH = StudioTheme.s(28)

            // Top beat/second ruler
            g2.color = StudioTheme.GunmetalDeck
            g2.fillRect(0, 0, width, rulerH)
            g2.font = StudioTheme.LabelFont
            val rulerFm = g2.fontMetrics
            val rulerTextY = (rulerH - rulerFm.height) / 2 + rulerFm.ascent

            val beatSec = 60f / syncState.bpm.coerceIn(60f, 180f)
            var t = 0f
            var beatIdx = 0
            while (t <= totalSec) {
                val x = (leftPad + (t / totalSec) * usableW).toInt()
                g2.color = if (beatIdx % 4 == 0) StudioTheme.NeonCyan else StudioTheme.HairlineDivider
                g2.drawLine(x, if (beatIdx % 4 == 0) StudioTheme.s(4) else StudioTheme.s(14), x, height)
                if (beatIdx % 4 == 0) {
                    g2.drawString("B${(beatIdx / 4) + 1}", x + StudioTheme.s(4), rulerTextY)
                }
                t += beatSec
                beatIdx++
            }

            val colors = listOf(StudioTheme.NeonCyan, StudioTheme.EmeraldSignal, StudioTheme.AmberHot, StudioTheme.MagentaFx)
            val laneH = ((height - rulerH - StudioTheme.s(8)) / 4).coerceAtLeast(StudioTheme.s(36))
            val arc = StudioTheme.s(8)
            timelineState.tracks.forEachIndexed { idx, track ->
                val y = rulerH + StudioTheme.s(4) + idx * laneH
                val accent = colors.getOrElse(idx) { StudioTheme.VioletMod }

                g2.color = StudioTheme.GunmetalDeck
                g2.fillRect(StudioTheme.s(4), y + StudioTheme.s(2), (leftPad - StudioTheme.s(12)).toInt(), laneH - StudioTheme.s(6))
                g2.color = accent
                g2.font = StudioTheme.LabelFont
                val fm = g2.fontMetrics
                val textY = y + (laneH - fm.height) / 2 + fm.ascent
                g2.drawString(track.name.take(16), StudioTheme.s(10), textY)

                g2.color = StudioTheme.BrushedPanel
                g2.fillRect(leftPad.toInt(), y + StudioTheme.s(2), usableW.toInt(), laneH - StudioTheme.s(6))

                track.clips.forEach { clip ->
                    val cx = (leftPad + (clip.startTimeSec / totalSec) * usableW).toInt()
                    val cw = ((clip.buffer.durationSeconds / totalSec) * usableW).toInt().coerceAtLeast(StudioTheme.s(14))
                    val ch = (laneH - StudioTheme.s(12)).coerceAtLeast(StudioTheme.s(16))
                    g2.color = StudioTheme.withAlpha(accent, if (track.isMuted) 45 else 155)
                    g2.fillRoundRect(cx, y + StudioTheme.s(5), cw, ch, arc, arc)
                    g2.color = accent
                    g2.drawRoundRect(cx, y + StudioTheme.s(5), cw, ch, arc, arc)
                    g2.color = StudioTheme.ObsidianChassis
                    g2.font = StudioTheme.LabelFont
                    g2.drawString(clip.label.take(12), cx + StudioTheme.s(6), textY)
                }
            }

            // Global Playhead Line
            val headSec = if (syncState.isPlaying) syncState.playheadSec else timelineState.playheadSec
            val px = (leftPad + (headSec / totalSec).coerceIn(0f, 1f) * usableW).toInt()
            g2.color = StudioTheme.TacCoral
            g2.stroke = StudioTheme.stroke(2.2f)
            g2.drawLine(px, 0, px, height)

            g2.dispose()
        }
    }
}
