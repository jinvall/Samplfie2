package com.samplfie.desktop.ui

import com.samplfie.desktop.network.CollabInstrumentStyle
import com.samplfie.desktop.network.CollabPeerDevice
import com.samplfie.desktop.network.LiveBandRecordingState
import com.samplfie.desktop.network.MAX_COLLAB_PEERS
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.Timer

/**
 * Reusable 4-device collaborator & synced live band recording strip for Desktop Booth / NetLink / Live Stage.
 */
class DesktopBandCollabStrip(
    private val onOpenCollabDialog: () -> Unit,
    private val onToggleBandRec: () -> Unit
) : JPanel() {

    private val titleLabel = StudioTheme.hardwareLabel("4-DEVICE BAND COLLAB (0/4 LINKED)", StudioTheme.NeonCyan, StudioTheme.TitleFont)
    private val detailLabel = StudioTheme.hardwareLabel("MY STYLE: GUITAR -> TRACK 2 • 0 NEARBY", StudioTheme.TextSecondary, StudioTheme.MonoFont)
    private val openBtn = StudioTheme.hardwareButton("CONNECT DEVICES (0/4)", StudioTheme.NeonCyan) { onOpenCollabDialog() }
    private val recBtn = StudioTheme.hardwareButton("START BAND REC", StudioTheme.EmeraldSignal, filled = true) { onToggleBandRec() }
    private val peersPillPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { isOpaque = false }
    private var lastSignature = ""

    init {
        background = StudioTheme.BrushedPanel
        border = StudioTheme.panelBorder(StudioTheme.EmeraldSignal, 1)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        maximumSize = Dimension(Int.MAX_VALUE, 130)

        val top = JPanel(BorderLayout(8, 2)).apply {
            isOpaque = false
            val left = JPanel(GridLayout(2, 1, 0, 2)).apply {
                isOpaque = false
                add(titleLabel)
                add(detailLabel)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
                isOpaque = false
                add(openBtn)
                add(recBtn)
            }
            add(left, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
        add(top)
        add(peersPillPanel)
    }

    fun updateState(peers: List<CollabPeerDevice>, bandRec: LiveBandRecordingState) {
        val connected = peers.filter { it.isConnected }
        val nearby = peers.count { it.isNearby && !it.isConnected }
        val accent = when {
            bandRec.isRecording -> StudioTheme.TacCoral
            connected.isNotEmpty() -> StudioTheme.EmeraldSignal
            nearby > 0 -> StudioTheme.AmberHot
            else -> StudioTheme.NeonCyan
        }
        titleLabel.text = "4-DEVICE BAND COLLAB (${connected.size}/$MAX_COLLAB_PEERS LINKED)"
        titleLabel.foreground = accent
        val suffix = if (bandRec.lastRecordedSummary.isNotBlank()) " • ${bandRec.lastRecordedSummary}" else ""
        detailLabel.text = "MY STYLE: ${bandRec.myStyle.label.uppercase()} -> TRACK ${bandRec.myTrackSlot + 1} • $nearby NEARBY$suffix"
        openBtn.text = "CONNECT DEVICES (${connected.size}/$MAX_COLLAB_PEERS)"
        recBtn.text = if (bandRec.isRecording) "STOP BAND REC" else "START BAND REC"
        recBtn.background = if (bandRec.isRecording) StudioTheme.TacCoral else StudioTheme.EmeraldSignal

        val sig = connected.joinToString("|") { "${it.peerId}:${it.style.name}:${it.trackSlot}:${it.isRecording}" }
        if (sig != lastSignature) {
            lastSignature = sig
            peersPillPanel.removeAll()
            if (connected.isEmpty()) {
                peersPillPanel.add(StudioTheme.hardwareLabel("No remote collaborators linked yet. Click CONNECT DEVICES to pick nearby or saved peers.", StudioTheme.TextMuted))
            } else {
                connected.forEach { p ->
                    val pColor = if (p.isRecording) StudioTheme.TacCoral else StudioTheme.EmeraldSignal
                    peersPillPanel.add(
                        StudioTheme.hardwareLabel(
                            "[${p.name} • ${p.style.shortTag} -> T${p.trackSlot + 1}]",
                            pColor,
                            StudioTheme.MonoFont
                        )
                    )
                }
            }
            peersPillPanel.revalidate()
            peersPillPanel.repaint()
        }
    }
}

/**
 * Interactive Desktop dialog to scan LAN for nearby Samplfie2 peers, connect up to 4 collaborators,
 * choose each device's Instrument Style & Timeline Track, and launch Synced Live Band Recording.
 */
class DesktopCollabDialog(
    owner: JFrame,
    private val defaultHost: String,
    private val defaultPort: Int,
    private val nodeName: String,
    private val onRefreshDiscovery: () -> Unit,
    private val onSelectMyStyle: (CollabInstrumentStyle, Int) -> Unit,
    private val onConnectPeer: (host: String, port: Int, name: String, platform: String, style: CollabInstrumentStyle?) -> Boolean,
    private val onDisconnectPeer: (String) -> Unit,
    private val onUpdatePeerStyleAndTrack: (String, CollabInstrumentStyle, Int) -> Unit,
    private val onToggleBandRec: () -> Unit,
    private val peersProvider: () -> List<CollabPeerDevice>,
    private val bandStateProvider: () -> LiveBandRecordingState
) : JDialog(owner, "4-Device Live Band Link — Samplfie2", true) {

    private var updating = false
    private val myStyleBox = JComboBox(CollabInstrumentStyle.entries.toTypedArray()).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        font = StudioTheme.MonoFont
    }
    private val myTrackBox = JComboBox(arrayOf("Track 1", "Track 2", "Track 3", "Track 4")).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        font = StudioTheme.MonoFont
    }

    private val hostField = JTextField(defaultHost, 14).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        caretColor = StudioTheme.NeonCyan
        font = StudioTheme.MonoFont
        border = StudioTheme.panelBorder(StudioTheme.HairlineDivider, 1)
    }
    private val portField = JTextField(defaultPort.toString(), 6).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        caretColor = StudioTheme.NeonCyan
        font = StudioTheme.MonoFont
        border = StudioTheme.panelBorder(StudioTheme.HairlineDivider, 1)
    }
    private val nameField = JTextField("Android Bandmate", 14).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        caretColor = StudioTheme.NeonCyan
        font = StudioTheme.MonoFont
        border = StudioTheme.panelBorder(StudioTheme.HairlineDivider, 1)
    }

    private val headerStatusLabel = StudioTheme.hardwareLabel("", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val summaryLabel = StudioTheme.hardwareLabel("", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)
    private val recBtn = StudioTheme.hardwareButton("START LIVE BAND RECORDING", StudioTheme.EmeraldSignal, filled = true) {
        onToggleBandRec()
        refreshList(forceRebuild = true)
    }
    private val listPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private var lastListSig = ""
    private val liveTimer = Timer(400) { refreshList(forceRebuild = false) }

    init {
        size = Dimension(720, 600)
        background = StudioTheme.GunmetalDeck
        layout = BorderLayout(8, 8)

        val root = JPanel().apply {
            background = StudioTheme.GunmetalDeck
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
        }

        // 1. Local Station Style & Track Card
        val localCard = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val hdr = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("LOCAL STATION ($nodeName) — INSTRUMENT STYLE & TRACK", StudioTheme.NeonCyan, StudioTheme.TitleFont), BorderLayout.WEST)
                add(StudioTheme.hardwareButton("SCAN LAN", StudioTheme.NeonCyan) {
                    onRefreshDiscovery()
                    refreshList(forceRebuild = true)
                }, BorderLayout.EAST)
            }
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("MY STYLE:", StudioTheme.TextSecondary))
                add(myStyleBox)
                add(StudioTheme.hardwareLabel("TARGET TRACK:", StudioTheme.TextSecondary))
                add(myTrackBox)
            }
            myStyleBox.addActionListener {
                if (!updating) {
                    val st = myStyleBox.selectedItem as? CollabInstrumentStyle ?: return@addActionListener
                    onSelectMyStyle(st, st.defaultTrack)
                    refreshList(forceRebuild = false)
                }
            }
            myTrackBox.addActionListener {
                if (!updating) {
                    val st = myStyleBox.selectedItem as? CollabInstrumentStyle ?: return@addActionListener
                    onSelectMyStyle(st, myTrackBox.selectedIndex.coerceIn(0, 3))
                    refreshList(forceRebuild = false)
                }
            }
            add(hdr)
            add(row)
            add(headerStatusLabel)
        }
        root.add(localCard)
        root.add(Box.createVerticalStrut(8))

        // 2. Discovered & Known Peers List
        val peersCard = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = BorderLayout(4, 6)
            add(StudioTheme.hardwareLabel("NEARBY DISCOVERED & KNOWN COLLABORATORS (UP TO $MAX_COLLAB_PEERS SIMULTANEOUS)", StudioTheme.EmeraldSignal, StudioTheme.TitleFont), BorderLayout.NORTH)
            add(JScrollPane(listPanel).apply {
                border = null
                isOpaque = false
                viewport.isOpaque = false
                preferredSize = Dimension(660, 210)
            }, BorderLayout.CENTER)
        }
        root.add(peersCard)
        root.add(Box.createVerticalStrut(8))

        // 3. Quick Add Peer Endpoint + Preset
        val addCard = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("ADD / QUICK CONNECT COLLABORATOR DEVICE", StudioTheme.VioletMod, StudioTheme.TitleFont))
            val r = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("HOST:", StudioTheme.TextSecondary))
                add(hostField)
                add(StudioTheme.hardwareLabel("PORT:", StudioTheme.TextSecondary))
                add(portField)
                add(StudioTheme.hardwareLabel("NAME:", StudioTheme.TextSecondary))
                add(nameField)
                add(StudioTheme.hardwareButton("LINK PEER", StudioTheme.VioletMod, filled = true) {
                    val p = portField.text.toIntOrNull()?.coerceIn(1, 65535) ?: 9001
                    val h = hostField.text.trim()
                    if (h.isNotEmpty()) {
                        onConnectPeer(h, p, nameField.text.trim().ifBlank { "Studio Peer" }, "PEER", null)
                        refreshList(forceRebuild = true)
                    }
                })
                add(StudioTheme.hardwareButton("ANDROID EMU (:9001)", StudioTheme.AmberHot) {
                    onConnectPeer("127.0.0.1", 9001, "Android Peer", "ANDROID", CollabInstrumentStyle.VOCALS)
                    refreshList(forceRebuild = true)
                })
            }
            add(r)
        }
        root.add(addCard)
        root.add(Box.createVerticalStrut(8))

        // 4. Band Recording Bar
        val recCard = StudioTheme.hardwarePanel(StudioTheme.TacCoral).apply {
            layout = BorderLayout(8, 4)
            add(recBtn, BorderLayout.WEST)
            add(summaryLabel, BorderLayout.CENTER)
        }
        root.add(recCard)

        add(root, BorderLayout.CENTER)
        val footer = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            background = StudioTheme.GunmetalDeck
            add(StudioTheme.hardwareButton("CLOSE", StudioTheme.NeonCyan) { dispose() })
        }
        add(footer, BorderLayout.SOUTH)

        onRefreshDiscovery()
        refreshList(forceRebuild = true)
        liveTimer.start()
        setLocationRelativeTo(owner)
    }

    override fun dispose() {
        liveTimer.stop()
        super.dispose()
    }

    private fun refreshList(forceRebuild: Boolean) {
        val peers = peersProvider()
        val band = bandStateProvider()
        val connected = peers.count { it.isConnected }

        updating = true
        try {
            myStyleBox.selectedItem = band.myStyle
            myTrackBox.selectedIndex = band.myTrackSlot.coerceIn(0, 3)
        } finally {
            updating = false
        }

        headerStatusLabel.text = "CONNECTED: $connected/$MAX_COLLAB_PEERS • DISCOVERY BEACON ACTIVE ON UDP 9099"
        recBtn.text = if (band.isRecording) "STOP SYNCED BAND REC (${connected + 1} DEVICES)" else "START LIVE BAND REC (${connected + 1} DEVICES)"
        recBtn.background = if (band.isRecording) StudioTheme.TacCoral else StudioTheme.EmeraldSignal
        summaryLabel.text = band.lastRecordedSummary.ifBlank { "Records your ${band.myStyle.label} + all linked collaborators onto Timeline Tracks 1–4." }

        val sig = peers.joinToString(";") {
            "${it.peerId}|${it.isConnected}|${it.isNearby}|${it.style.name}|${it.trackSlot}|${it.isRecording}"
        }
        if (forceRebuild || sig != lastListSig) {
            lastListSig = sig
            listPanel.removeAll()
            if (peers.isEmpty()) {
                listPanel.add(StudioTheme.hardwareLabel("No nearby or saved collaborators found yet. Click SCAN LAN or use ANDROID EMU (:9001) below.", StudioTheme.TextMuted))
            } else {
                peers.forEach { p ->
                    val row = StudioTheme.hardwarePanel(
                        when {
                            p.isConnected -> StudioTheme.EmeraldSignal
                            p.isNearby -> StudioTheme.NeonCyan
                            else -> StudioTheme.HairlineDivider
                        }
                    ).apply {
                        layout = FlowLayout(FlowLayout.LEFT, 6, 3)
                        maximumSize = Dimension(Int.MAX_VALUE, 46)
                        val stateTag = when {
                            p.isConnected -> "LINKED"
                            p.isNearby -> "NEARBY"
                            else -> "KNOWN"
                        }
                        add(
                            StudioTheme.hardwareLabel(
                                "${p.name} [${p.platform}] ${p.endpoint} • $stateTag",
                                if (p.isConnected) StudioTheme.EmeraldSignal else StudioTheme.TextPrimary,
                                StudioTheme.MonoFont
                            )
                        )
                        val stBox = JComboBox(CollabInstrumentStyle.entries.toTypedArray()).apply {
                            selectedItem = p.style
                            background = StudioTheme.ObsidianChassis
                            foreground = StudioTheme.AmberHot
                            font = StudioTheme.LabelFont
                            addActionListener {
                                val nextSt = selectedItem as? CollabInstrumentStyle ?: return@addActionListener
                                onUpdatePeerStyleAndTrack(p.peerId, nextSt, p.trackSlot)
                            }
                        }
                        val trkBox = JComboBox(arrayOf("T1", "T2", "T3", "T4")).apply {
                            selectedIndex = p.trackSlot.coerceIn(0, 3)
                            background = StudioTheme.ObsidianChassis
                            foreground = StudioTheme.MagentaFx
                            font = StudioTheme.LabelFont
                            addActionListener {
                                onUpdatePeerStyleAndTrack(p.peerId, p.style, selectedIndex.coerceIn(0, 3))
                            }
                        }
                        add(stBox)
                        add(trkBox)
                        add(
                            StudioTheme.hardwareButton(
                                if (p.isConnected) "DROP" else "LINK",
                                if (p.isConnected) StudioTheme.TacCoral else StudioTheme.EmeraldSignal,
                                filled = true
                            ) {
                                if (p.isConnected) {
                                    onDisconnectPeer(p.peerId)
                                } else {
                                    onConnectPeer(p.host, p.listenPort, p.name, p.platform, p.style)
                                }
                                refreshList(forceRebuild = true)
                            }
                        )
                    }
                    listPanel.add(row)
                    listPanel.add(Box.createVerticalStrut(4))
                }
            }
            listPanel.revalidate()
            listPanel.repaint()
        }
    }
}
