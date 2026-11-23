package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.model.DesktopRigConfig
import com.samplfie.desktop.model.NetworkProtocol
import com.samplfie.desktop.network.CollabPeerDevice
import com.samplfie.desktop.network.NetBridgeState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField

/**
 * Bidirectional Equal-Peer Network Bridge & Rig Configuration Station.
 * Multiplexes SMP2 Audio Streams, VMC2 64-Channel MIDI Companion packets,
 * and SMH2/SMHR Instant Session Handoff between Desktop & Android.
 */
class NetLinkPanel(
    private val onUpdateConfig: (DesktopRigConfig.() -> DesktopRigConfig) -> Unit,
    private val onToggleListener: () -> Unit,
    private val onCaptureRxToEditor: () -> Unit,
    private val onTransmitActive: () -> Unit,
    private val onPushHandoff: () -> Unit,
    private val onGrabHandoff: () -> Unit,
    private val onOpenCollabDialog: () -> Unit = {},
    private val onToggleBandRec: () -> Unit = {}
) : JPanel() {

    private val collabStrip = DesktopBandCollabStrip(
        onOpenCollabDialog = onOpenCollabDialog,
        onToggleBandRec = onToggleBandRec
    )

    private var updating = false

    private val bindPortField = darkTextField("9000", 92)
    private val txHostField = darkTextField("127.0.0.1", 148)
    private val txPortField = darkTextField("9001", 92)
    private val protocolBox = StudioTheme.styleComboBox(JComboBox(NetworkProtocol.entries.toTypedArray()), 12f)
    private val sampleRateBox = StudioTheme.styleComboBox(JComboBox(arrayOf(22050, 44100, 48000, 96000)).apply { selectedItem = 44100 }, 12f)
    private val channelsBox = StudioTheme.styleComboBox(JComboBox(arrayOf(1, 2)).apply { selectedItem = 1 }, 12f)
    private val bufferBox = StudioTheme.styleComboBox(JComboBox(arrayOf(256, 512, 1024, 2048, 4096)).apply { selectedItem = 1024 }, 12f)

    private val listenBtn = StudioTheme.hardwareButton("START SMP2/VMC2 RX LISTENER", StudioTheme.EmeraldSignal, filled = true) {
        applyFieldsToConfig()
        onToggleListener()
    }
    private val captureBtn = StudioTheme.hardwareButton("LOAD RX STREAM -> DECK", StudioTheme.NeonCyan) {
        onCaptureRxToEditor()
    }
    private val txBtn = StudioTheme.hardwareButton("STREAM ACTIVE BUFFER OUT", StudioTheme.AmberHot) {
        applyFieldsToConfig()
        onTransmitActive()
    }
    private val pushHandoffBtn = StudioTheme.hardwareButton("PUSH SESSION -> PEER (SMH2)", StudioTheme.EmeraldSignal, filled = true) {
        applyFieldsToConfig()
        onPushHandoff()
    }
    private val grabHandoffBtn = StudioTheme.hardwareButton("GRAB SESSION FROM PEER (SMHR)", StudioTheme.AmberHot, filled = true) {
        applyFieldsToConfig()
        onGrabHandoff()
    }

    private val statusLabel = StudioTheme.hardwareLabel("IDLE", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val rxStatsLabel = StudioTheme.hardwareLabel("RX: 0 PKTS • 0 BYTES", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)
    private val txStatsLabel = StudioTheme.hardwareLabel("TX: 0 PKTS • VMC2 TX 0 / RX 0", StudioTheme.AmberHot, StudioTheme.MonoFont)

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 10, 12, 10, 12)
        }

        root.add(collabStrip)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        val rxPanel = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("LINUX PEER RECEIVER (SMP2 AUDIO + VMC2 64-CH MIDI + SMH2 HANDOFF)", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(StudioTheme.s(8)))

            val row1 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareLabel("PROTOCOL:", StudioTheme.TextSecondary))
                add(protocolBox)
                add(StudioTheme.hardwareLabel("BIND PORT:", StudioTheme.TextSecondary))
                add(bindPortField)
                add(listenBtn)
                add(captureBtn)
            }
            add(row1)
            add(Box.createVerticalStrut(StudioTheme.s(8)))
            add(statusLabel)
            add(rxStatsLabel)
        }
        root.add(rxPanel)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        val txPanel = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("EQUAL PEER STREAMING & INSTANT SESSION HANDOFF", StudioTheme.AmberHot, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(StudioTheme.s(8)))
            val row = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareLabel("PEER IP:", StudioTheme.TextSecondary))
                add(txHostField)
                add(StudioTheme.hardwareLabel("PEER PORT:", StudioTheme.TextSecondary))
                add(txPortField)
                add(txBtn)
            }
            val handoffRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(pushHandoffBtn)
                add(grabHandoffBtn)
            }
            add(row)
            add(handoffRow)
            add(txStatsLabel)
        }
        root.add(txPanel)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        val enginePanel = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("DESKTOP ENGINE FORMAT (ALSA / PULSEAUDIO / PIPEWIRE)", StudioTheme.VioletMod, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(StudioTheme.s(8)))
            val row = JPanel(GridLayout(1, 3, StudioTheme.s(12), 0)).apply {
                isOpaque = false
                add(labeledCombo("SAMPLE RATE (Hz)", sampleRateBox) { applyFieldsToConfig() })
                add(labeledCombo("CHANNELS", channelsBox) { applyFieldsToConfig() })
                add(labeledCombo("PACKET BUFFER (FRAMES)", bufferBox) { applyFieldsToConfig() })
            }
            add(row)
        }
        root.add(enginePanel)
        root.add(Box.createVerticalGlue())

        add(StudioTheme.styleScrollPane(JScrollPane(root)), BorderLayout.CENTER)
    }

    private fun applyFieldsToConfig() {
        if (updating) return
        val bindP = bindPortField.text.toIntOrNull()?.coerceIn(1, 65535) ?: 9000
        val txP = txPortField.text.toIntOrNull()?.coerceIn(1, 65535) ?: 9001
        val host = txHostField.text.trim().ifBlank { "127.0.0.1" }
        val proto = protocolBox.selectedItem as? NetworkProtocol ?: NetworkProtocol.UDP
        val sr = sampleRateBox.selectedItem as? Int ?: 44100
        val ch = channelsBox.selectedItem as? Int ?: 1
        val buf = bufferBox.selectedItem as? Int ?: 1024
        onUpdateConfig {
            copy(
                protocol = proto,
                bindPort = bindP,
                txHost = host,
                txPort = txP,
                sampleRate = sr,
                channels = ch,
                bufferSize = buf
            )
        }
    }

    fun updateState(ui: DesktopStudioUiState, net: NetBridgeState, collabPeers: List<CollabPeerDevice> = emptyList()) {
        updating = true
        try {
            collabStrip.updateState(collabPeers, ui.bandRecState)
            listenBtn.text = if (net.isListening) "STOP PEER LISTENER" else "START SMP2/VMC2 RX LISTENER"
            statusLabel.text = "STATUS: ${net.statusText}"
            rxStatsLabel.text = "RX: ${net.rxPackets} PKTS • ${net.rxBytes} BYTES • ${net.rxSampleRate}Hz ${net.rxChannels}CH • LAST HANDOFF: ${net.lastHandoffSender ?: "NONE"}"
            txStatsLabel.text = "TX: ${net.txPackets} PKTS • ${net.txBytes} BYTES • VMC2 TX ${net.vmcTxCount} / RX ${net.vmcRxCount}"
        } finally {
            updating = false
        }
    }

    private fun darkTextField(initial: String, baseWidth: Int = 92): JTextField =
        StudioTheme.styleTextField(JTextField(initial), 12f, baseWidth, 32)

    private fun labeledCombo(title: String, box: JComboBox<*>, onChange: () -> Unit): JPanel =
        JPanel(BorderLayout(0, StudioTheme.s(4))).apply {
            isOpaque = false
            add(StudioTheme.hardwareLabel(title, StudioTheme.TextSecondary), BorderLayout.NORTH)
            box.addActionListener { if (!updating) onChange() }
            add(box, BorderLayout.CENTER)
        }
}
