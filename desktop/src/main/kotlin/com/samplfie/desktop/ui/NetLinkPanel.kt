package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.model.DesktopRigConfig
import com.samplfie.desktop.model.NetworkProtocol
import com.samplfie.desktop.network.NetBridgeState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Bidirectional SMP2 Linux Audio Bridge & Rig Configuration Station.
 * Receives live TCP/UDP SMP2 audio packets streamed from the Samplfie2 Android app
 * directly into the desktop Signal Editor & Chromatic Tuner, or streams desktop audio out.
 */
class NetLinkPanel(
    private val onUpdateConfig: (DesktopRigConfig.() -> DesktopRigConfig) -> Unit,
    private val onToggleListener: () -> Unit,
    private val onCaptureRxToEditor: () -> Unit,
    private val onTransmitActive: () -> Unit
) : JPanel() {

    private var updating = false

    private val bindPortField = darkTextField("9000")
    private val txHostField = darkTextField("127.0.0.1")
    private val txPortField = darkTextField("9001")
    private val protocolBox = JComboBox(NetworkProtocol.entries.toTypedArray()).apply {
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        font = StudioTheme.MonoFont
    }
    private val sampleRateBox = JComboBox(arrayOf(22050, 44100, 48000, 96000)).apply {
        selectedItem = 44100
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        font = StudioTheme.MonoFont
    }
    private val channelsBox = JComboBox(arrayOf(1, 2)).apply {
        selectedItem = 1
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        font = StudioTheme.MonoFont
    }
    private val bufferBox = JComboBox(arrayOf(256, 512, 1024, 2048, 4096)).apply {
        selectedItem = 1024
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        font = StudioTheme.MonoFont
    }

    private val listenBtn = StudioTheme.hardwareButton("START SMP2 RX LISTENER", StudioTheme.EmeraldSignal, filled = true) {
        applyFieldsToConfig()
        onToggleListener()
    }
    private val captureBtn = StudioTheme.hardwareButton("LOAD RX STREAM -> EDITOR", StudioTheme.NeonCyan) {
        onCaptureRxToEditor()
    }
    private val txBtn = StudioTheme.hardwareButton("STREAM ACTIVE BUFFER OUT", StudioTheme.AmberHot) {
        applyFieldsToConfig()
        onTransmitActive()
    }

    private val statusLabel = StudioTheme.hardwareLabel("IDLE", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val rxStatsLabel = StudioTheme.hardwareLabel("RX: 0 PKTS • 0 BYTES", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)
    private val txStatsLabel = StudioTheme.hardwareLabel("TX: 0 PKTS • 0 BYTES", StudioTheme.AmberHot, StudioTheme.MonoFont)

    init {
        background = StudioTheme.ObsidianChassis
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12)

        val rxPanel = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(Int.MAX_VALUE, 175)
            add(StudioTheme.hardwareLabel("LINUX SMP2 RECEIVER (FROM SAMPLFIE2 ANDROID)", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(8))

            val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 10, 4)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("PROTOCOL:", StudioTheme.TextSecondary))
                add(protocolBox)
                add(StudioTheme.hardwareLabel("BIND PORT:", StudioTheme.TextSecondary))
                add(bindPortField)
                add(listenBtn)
                add(captureBtn)
            }
            add(row1)
            add(Box.createVerticalStrut(8))
            add(statusLabel)
            add(rxStatsLabel)
        }
        add(rxPanel)
        add(Box.createVerticalStrut(10))

        val txPanel = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(Int.MAX_VALUE, 140)
            add(StudioTheme.hardwareLabel("TRANSMIT STREAM TO REMOTE RIG / ANDROID", StudioTheme.AmberHot, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(8))
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 10, 4)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("TARGET IP:", StudioTheme.TextSecondary))
                add(txHostField.apply { preferredSize = Dimension(140, 30) })
                add(StudioTheme.hardwareLabel("PORT:", StudioTheme.TextSecondary))
                add(txPortField)
                add(txBtn)
            }
            add(row)
            add(txStatsLabel)
        }
        add(txPanel)
        add(Box.createVerticalStrut(10))

        val enginePanel = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            maximumSize = Dimension(Int.MAX_VALUE, 130)
            add(StudioTheme.hardwareLabel("DESKTOP ENGINE FORMAT (ALSA / PULSEAUDIO / PIPEWIRE)", StudioTheme.VioletMod, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(8))
            val row = JPanel(GridLayout(1, 3, 12, 0)).apply {
                isOpaque = false
                add(labeledCombo("SAMPLE RATE (Hz)", sampleRateBox) {
                    applyFieldsToConfig()
                })
                add(labeledCombo("CHANNELS", channelsBox) {
                    applyFieldsToConfig()
                })
                add(labeledCombo("PACKET BUFFER (FRAMES)", bufferBox) {
                    applyFieldsToConfig()
                })
            }
            add(row)
        }
        add(enginePanel)
        add(Box.createVerticalGlue())
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

    fun updateState(ui: DesktopStudioUiState, net: NetBridgeState) {
        updating = true
        try {
            listenBtn.text = if (net.isListening) "STOP SMP2 LISTENER" else "START SMP2 RX LISTENER"
            statusLabel.text = "STATUS: ${net.statusText}"
            rxStatsLabel.text = "RX: ${net.rxPackets} PKTS • ${net.rxBytes} BYTES • ${net.rxSampleRate}Hz ${net.rxChannels}CH"
            txStatsLabel.text = "TX: ${net.txPackets} PKTS • ${net.txBytes} BYTES"
        } finally {
            updating = false
        }
    }

    private fun darkTextField(initial: String): JTextField = JTextField(initial).apply {
        preferredSize = Dimension(86, 30)
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        caretColor = StudioTheme.NeonCyan
        font = StudioTheme.MonoFont
        border = StudioTheme.panelBorder(StudioTheme.HairlineDivider, 1)
    }

    private fun labeledCombo(title: String, box: JComboBox<*>, onChange: () -> Unit): JPanel =
        JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(StudioTheme.hardwareLabel(title, StudioTheme.TextSecondary), BorderLayout.NORTH)
            box.addActionListener { if (!updating) onChange() }
            add(box, BorderLayout.CENTER)
        }
}
