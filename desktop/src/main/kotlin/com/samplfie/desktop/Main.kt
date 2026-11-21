package com.samplfie.desktop

import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.ui.ChromaticTunerPanel
import com.samplfie.desktop.ui.CircularHubSidebar
import com.samplfie.desktop.ui.FxAndStompPanel
import com.samplfie.desktop.ui.NetLinkPanel
import com.samplfie.desktop.ui.SignalEditorPanel
import com.samplfie.desktop.ui.StudioTheme
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Main Linux-style Desktop Companion Frame matching Samplfie2 Android's circular studio workstation:
 * - Persistent Circular Production Hub sidebar (always on screen across every station)
 * - Signal & Audio Editor (Waveform, 48-band FFT, signal transforms & signal generator)
 * - Chromatic & Strobe Tuner (YIN pitch tracking, live mic / editor buffer / SMP2 stream)
 * - 12-Module DSP FX & Master Stompbox Processor
 * - Bidirectional SMP2 TCP/UDP Linux Audio Bridge
 */
class SamplfieDesktopFrame(
    private val controller: DesktopStudioController = DesktopStudioController()
) : JFrame("Samplfie2 Linux Studio — Signal Editor & Chromatic Tuner") {

    private val cardLayout = CardLayout()
    private val cardHost = JPanel(cardLayout).apply {
        background = StudioTheme.ObsidianChassis
    }

    private val topStatusLabel = StudioTheme.hardwareLabel("READY", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val stationTitleLabel = StudioTheme.hardwareLabel("SIGNAL & AUDIO EDITOR", StudioTheme.TextPrimary, StudioTheme.TitleFont)

    private val hubSidebar = CircularHubSidebar(
        onSelectStation = { controller.selectStation(it) },
        onTogglePlay = { controller.soundEngine.togglePlay() },
        onStop = { controller.soundEngine.stopPlayback() },
        onToggleLoop = { controller.soundEngine.setLooping(!controller.playbackState.value.isLooping) },
        onToggleMic = { controller.toggleMicRecording() },
        onToggleStompBypass = { controller.toggleMasterStompBypass() },
        onOpenWav = { chooseAndOpenWav() },
        onSaveWav = { chooseAndSaveWav() },
        onExportMp3 = { chooseAndExportMp3() }
    )

    private val editorPanel = SignalEditorPanel(
        onVisualizerMode = { controller.setVisualizerMode(it) },
        onSelectionChange = { s, e -> controller.updateSelection(s, e) },
        onSeekPlayhead = { controller.soundEngine.seekTo(it) },
        onTogglePlaySelection = { controller.togglePlaySelectionOnly() },
        onZeroCrossSnap = { controller.snapSelectionZeroCrossing() },
        onExtract = { controller.extractSelection() },
        onApplyFades = { fin, fout -> controller.applyFades(fin, fout) },
        onReverse = { controller.reverseSignal() },
        onDcFix = { controller.removeDcOffset() },
        onNormalize = { controller.normalizeSignal() },
        onUndo = { controller.undoEdit() },
        onReset = { controller.resetToOriginal() },
        onGenerateSignal = { type, hz -> controller.generateTestSignal(type, hz) }
    )

    private val tunerPanel = ChromaticTunerPanel(
        onSelectSource = { controller.setTunerSource(it) },
        onSelectPreset = { controller.setTuningPreset(it) },
        onSetA4Reference = { controller.setA4Reference(it) },
        onAnalyzeBufferNow = { controller.analyzeActiveBufferWindowForTuner() },
        onPlayReferencePitch = { hz ->
            controller.soundEngine.playReferenceTone(hz, 800, controller.uiState.value.rigConfig.sampleRate)
        }
    )

    private val fxPanel = FxAndStompPanel(
        onApplyPreset = { controller.applyPreset(it) },
        onUpdateFx = { controller.updateFx(it) },
        onUpdateStomp = { controller.updateStomp(it) },
        onToggleMasterStomp = { controller.toggleMasterStompBypass() }
    )

    private val netPanel = NetLinkPanel(
        onUpdateConfig = { controller.updateRigConfig(it) },
        onToggleListener = { controller.toggleNetworkListener() },
        onCaptureRxToEditor = { controller.captureNetworkStreamToEditor() },
        onTransmitActive = { controller.transmitActiveOverNetwork() }
    )

    private val refreshTimer = Timer(50) { syncUi() }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        minimumSize = Dimension(1080, 740)
        size = Dimension(1280, 820)
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        jMenuBar = buildLinuxMenuBar()
        add(buildTopHardwareRack(), BorderLayout.NORTH)
        add(hubSidebar, BorderLayout.WEST)

        cardHost.add(editorPanel, DesktopStation.EDITOR.name)
        cardHost.add(tunerPanel, DesktopStation.TUNER.name)
        cardHost.add(fxPanel, DesktopStation.FX_STOMP.name)
        cardHost.add(netPanel, DesktopStation.NET_LINK.name)
        add(cardHost, BorderLayout.CENTER)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                refreshTimer.stop()
                controller.shutdown()
            }
        })

        syncUi()
        refreshTimer.start()
        setLocationRelativeTo(null)
    }

    private fun buildTopHardwareRack(): JPanel = JPanel(BorderLayout(12, 0)).apply {
        background = StudioTheme.GunmetalDeck
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, StudioTheme.HairlineDivider),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)
        )
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
            isOpaque = false
            add(StudioTheme.hardwareLabel("SAMPLFIE2 LINUX DESKTOP", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(stationTitleLabel)
        }
        val nav = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            DesktopStation.entries.forEach { st ->
                add(StudioTheme.hardwareButton(st.shortLabel, StudioTheme.NeonCyan) {
                    controller.selectStation(st)
                })
            }
        }
        add(left, BorderLayout.WEST)
        add(topStatusLabel, BorderLayout.CENTER)
        add(nav, BorderLayout.EAST)
    }

    private fun buildLinuxMenuBar(): JMenuBar = JMenuBar().apply {
        background = StudioTheme.GunmetalDeck
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, StudioTheme.HairlineDivider)

        val fileMenu = JMenu("File").apply {
            foreground = StudioTheme.TextPrimary
            add(JMenuItem("Open .WAV…").apply { addActionListener { chooseAndOpenWav() } })
            add(JMenuItem("Save .WAV As…").apply { addActionListener { chooseAndSaveWav() } })
            add(JMenuItem("Export .MP3 As…").apply { addActionListener { chooseAndExportMp3() } })
            addSeparator()
            add(JMenuItem("Quit").apply { addActionListener { dispose() } })
        }
        val stationMenu = JMenu("Stations").apply {
            foreground = StudioTheme.TextPrimary
            DesktopStation.entries.forEach { st ->
                add(JMenuItem(st.fullTitle).apply { addActionListener { controller.selectStation(st) } })
            }
        }
        val signalMenu = JMenu("Signal").apply {
            foreground = StudioTheme.TextPrimary
            add(JMenuItem("Extract Selection").apply { addActionListener { controller.extractSelection() } })
            add(JMenuItem("Snap Zero-Crossings").apply { addActionListener { controller.snapSelectionZeroCrossing() } })
            add(JMenuItem("Peak Normalize (-0.35 dBFS)").apply { addActionListener { controller.normalizeSignal() } })
            add(JMenuItem("Remove DC Offset").apply { addActionListener { controller.removeDcOffset() } })
        }
        add(fileMenu)
        add(stationMenu)
        add(signalMenu)
    }

    private fun syncUi() {
        val ui = controller.uiState.value
        val play = controller.playbackState.value
        val mic = controller.micState.value
        val net = controller.netState.value

        stationTitleLabel.text = "· ${ui.station.fullTitle}"
        topStatusLabel.text = ui.statusLine
        cardLayout.show(cardHost, ui.station.name)

        hubSidebar.updateState(ui, play, mic, net)
        editorPanel.updateState(ui, play)
        tunerPanel.updateState(ui)
        fxPanel.updateState(ui)
        netPanel.updateState(ui, net)
    }

    private fun chooseAndOpenWav() {
        val chooser = JFileChooser(controller.defaultExportDir).apply {
            fileFilter = FileNameExtensionFilter("WAV Audio (*.wav)", "wav")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            controller.loadWavFile(chooser.selectedFile)
        }
    }

    private fun chooseAndSaveWav() {
        val chooser = JFileChooser(controller.defaultExportDir).apply {
            selectedFile = File(controller.defaultExportDir, "samplfie2_signal.wav")
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            val f = if (chooser.selectedFile.extension.equals("wav", true)) chooser.selectedFile
            else File(chooser.selectedFile.absolutePath + ".wav")
            controller.exportWav(f)
        }
    }

    private fun chooseAndExportMp3() {
        val chooser = JFileChooser(controller.defaultExportDir).apply {
            selectedFile = File(controller.defaultExportDir, "samplfie2_signal.mp3")
        }
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            val f = if (chooser.selectedFile.extension.equals("mp3", true)) chooser.selectedFile
            else File(chooser.selectedFile.absolutePath + ".mp3")
            controller.exportMp3(f).onFailure {
                JOptionPane.showMessageDialog(this, it.message ?: "Export failed", "Export Error", JOptionPane.ERROR_MESSAGE)
            }
        }
    }
}

fun main() {
    // Enable crisp anti-aliased fonts and Linux GTK Look & Feel hints where available
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("swing.aatext", "true")
    SwingUtilities.invokeLater {
        runCatching {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())
        }
        SamplfieDesktopFrame().isVisible = true
    }
}
