package com.samplfie.desktop.ui

import com.samplfie.desktop.DesktopStudioUiState
import com.samplfie.desktop.audio.DesktopPlaybackState
import com.samplfie.desktop.model.DesktopStation
import com.samplfie.desktop.model.DesktopWorkspaceConfig
import com.samplfie.desktop.network.CollabPeerDevice
import com.samplfie.desktop.network.NetBridgeState
import com.samplfie.desktop.stems.DesktopDisbandStem
import com.samplfie.desktop.stems.DesktopSecretsState
import com.samplfie.desktop.stems.DesktopStemPreset
import com.samplfie.desktop.stems.DesktopStemState
import com.samplfie.desktop.studio.DesktopSyncedPlayheadState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField

/**
 * Equal Peer Production Booth & Workspace Customizer Station.
 * Lets the musician pick up/push live sessions seamlessly with Android and customize
 * which stations appear in the top rack bar and 360-degree Circular Wheel.
 */
class ProductionBoothPanel(
    private val onSelectStation: (DesktopStation) -> Unit,
    private val onUpdateWorkspace: (DesktopWorkspaceConfig.() -> DesktopWorkspaceConfig) -> Unit,
    private val onTogglePinStation: (DesktopStation) -> Unit,
    private val onToggleRadialSpoke: (DesktopStation) -> Unit,
    private val onPushHandoff: () -> Unit,
    private val onGrabHandoff: () -> Unit,
    private val onApplyPendingHandoff: () -> Unit,
    private val onToggleSyncedPlay: () -> Unit,
    private val onOpenSecretsVault: () -> Unit = {},
    private val onToggleDisbandStem: (DesktopDisbandStem) -> Unit = {},
    private val onApplyStemPreset: (DesktopStemPreset) -> Unit = {},
    private val onSetStemScopeSelectionOnly: (Boolean) -> Unit = {},
    private val onRequestStemSeparation: () -> Unit = {},
    private val onCancelStemSeparation: () -> Unit = {},
    private val onLoadStemToDeck: (String) -> Unit = {},
    private val onSendStemToLoop: (String, Int) -> Unit = { _, _ -> },
    private val onAddStemToTrack: (String, Int) -> Unit = { _, _ -> },
    private val onSendAllStemsToTracks: () -> Unit = {},
    private val onSendAllStemsToLooper: () -> Unit = {},
    private val onSaveStemWav: (String) -> Unit = {},
    private val onOpenCollabDialog: () -> Unit = {},
    private val onToggleBandRec: () -> Unit = {}
) : JPanel() {

    private var updating = false

    private val nodeNameField = StudioTheme.styleTextField(JTextField("LINUX DESKTOP RIG"), baseWidth = 210, baseHeight = 32)

    private val autoResumeCb = darkCheck("AUTO-RESUME LOOP/PLAY ON HANDOFF", StudioTheme.EmeraldSignal) { c ->
        onUpdateWorkspace { copy(autoResumeOnHandoff = c) }
    }
    private val autoAcceptCb = darkCheck("AUTO-ACCEPT INCOMING HANDOFF", StudioTheme.NeonCyan) { c ->
        onUpdateWorkspace { copy(autoAcceptIncomingHandoff = c) }
    }

    private val applyPendingBtn = StudioTheme.hardwareButton("APPLY PENDING HANDOFF", StudioTheme.AmberHot, filled = true) {
        onApplyPendingHandoff()
    }
    private val telemetryLabel = StudioTheme.hardwareLabel("HUB READY", StudioTheme.NeonCyan, StudioTheme.MonoFont)
    private val handoffStatusLabel = StudioTheme.hardwareLabel("NO PENDING HANDOFF", StudioTheme.TextSecondary, StudioTheme.MonoFont)
    private val syncPlayBtn = StudioTheme.hardwareButton("SYNC ALL PLAY", StudioTheme.EmeraldSignal, filled = true) {
        onToggleSyncedPlay()
    }

    private val navCheckboxes = mutableMapOf<DesktopStation, JCheckBox>()
    private val wheelCheckboxes = mutableMapOf<DesktopStation, JCheckBox>()
    private val stemPanel = DesktopStemSeparationPanel(
        onToggleStem = onToggleDisbandStem,
        onApplyPreset = onApplyStemPreset,
        onSetScopeSelectionOnly = onSetStemScopeSelectionOnly,
        onRequestSeparate = onRequestStemSeparation,
        onCancelSeparate = onCancelStemSeparation,
        onOpenVault = onOpenSecretsVault,
        onLoadStemToDeck = onLoadStemToDeck,
        onSendStemToLoop = onSendStemToLoop,
        onAddStemToTrack = onAddStemToTrack,
        onSendAllToTracks = onSendAllStemsToTracks,
        onSendAllToLooper = onSendAllStemsToLooper,
        onSaveStemWav = onSaveStemWav
    )
    private val collabStrip = DesktopBandCollabStrip(
        onOpenCollabDialog = onOpenCollabDialog,
        onToggleBandRec = onToggleBandRec
    )

    init {
        background = StudioTheme.ObsidianChassis
        layout = BorderLayout()

        val root = JPanel().apply {
            background = StudioTheme.ObsidianChassis
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 10, 12, 10, 12)
        }

        root.add(collabStrip)
        root.add(Box.createVerticalStrut(10))

        // Equal Peer Handoff Card
        val handoffCard = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("EQUAL PEER SESSION HANDOFF (DESKTOP <-> ANDROID)", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(6))

            val row1 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareLabel("RIG NAME:", StudioTheme.TextSecondary))
                add(nodeNameField)
                add(StudioTheme.hardwareButton("SAVE NAME", StudioTheme.NeonCyan) {
                    val clean = nodeNameField.text.trim().ifBlank { "LINUX DESKTOP RIG" }
                    onUpdateWorkspace { copy(nodeName = clean) }
                })
                add(autoResumeCb)
                add(autoAcceptCb)
            }
            val row2 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 10, 4).apply {
                add(StudioTheme.hardwareButton("PUSH SESSION -> PEER", StudioTheme.EmeraldSignal, filled = true) { onPushHandoff() })
                add(StudioTheme.hardwareButton("GRAB SESSION FROM PEER", StudioTheme.AmberHot, filled = true) { onGrabHandoff() })
                add(applyPendingBtn)
                add(syncPlayBtn)
                add(StudioTheme.hardwareButton("KEYS & SECRETS VAULT", StudioTheme.VioletMod) { onOpenSecretsVault() })
            }
            add(row1)
            add(row2)
            add(Box.createVerticalStrut(4))
            add(handoffStatusLabel)
        }
        root.add(handoffCard)
        root.add(Box.createVerticalStrut(10))

        // Live Master Telemetry Strip
        val telemetryCard = StudioTheme.hardwarePanel(StudioTheme.EmeraldSignal).apply {
            layout = BorderLayout(8, 4)
            add(StudioTheme.hardwareLabel("LIVE PRODUCTION BOOTH TELEMETRY", StudioTheme.EmeraldSignal, StudioTheme.TitleFont), BorderLayout.NORTH)
            add(telemetryLabel, BorderLayout.CENTER)
        }
        root.add(telemetryCard)
        root.add(Box.createVerticalStrut(10))

        // Quick Station Launcher Grid
        val launcherCard = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = BorderLayout(0, 8)
            add(StudioTheme.hardwareLabel("ALL STUDIO SPOKES (EQUAL WORKSTATION LAUNCHER)", StudioTheme.VioletMod, StudioTheme.TitleFont), BorderLayout.NORTH)
            val grid = JPanel(GridLayout(0, 4, 8, 8)).apply {
                isOpaque = false
                DesktopStation.entries.forEach { st ->
                    val accent = stationAccent(st)
                    add(StudioTheme.hardwareButton("${st.shortLabel} · ${st.badge}", accent) {
                        onSelectStation(st)
                    })
                }
            }
            add(grid, BorderLayout.CENTER)
        }
        root.add(launcherCard)
        root.add(Box.createVerticalStrut(10))

        // Disband AI Stem Separation Studio Console
        root.add(stemPanel)
        root.add(Box.createVerticalStrut(10))

        // Workspace Customizer
        val customizerCard = StudioTheme.hardwarePanel(StudioTheme.AmberHot).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("WORKSPACE CUSTOMIZER (TOP DOCK & 8-SPOKE RADIAL WHEEL)", StudioTheme.AmberHot, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(6))

            val columns = JPanel(GridLayout(1, 2, 14, 0)).apply {
                isOpaque = false
                val dockPanel = JPanel(GridLayout(0, 2, 6, 4)).apply {
                    isOpaque = false
                    StudioTheme.setScaledPanelBorder(this, StudioTheme.HairlineDivider, 1, 8, 10)
                    DesktopStation.entries.forEach { st ->
                        val cb = darkCheck("DOCK: ${st.shortLabel}", stationAccent(st)) {
                            onTogglePinStation(st)
                        }.apply {
                            if (st == DesktopStation.BOOTH) isEnabled = false
                        }
                        navCheckboxes[st] = cb
                        add(cb)
                    }
                }
                val wheelPanel = JPanel(GridLayout(0, 2, 6, 4)).apply {
                    isOpaque = false
                    StudioTheme.setScaledPanelBorder(this, StudioTheme.HairlineDivider, 1, 8, 10)
                    DesktopStation.entries.filter { it != DesktopStation.BOOTH }.forEach { st ->
                        val cb = darkCheck("WHEEL: ${st.shortLabel}", stationAccent(st)) {
                            onToggleRadialSpoke(st)
                        }
                        wheelCheckboxes[st] = cb
                        add(cb)
                    }
                }
                add(dockPanel)
                add(wheelPanel)
            }
            add(columns)
        }
        root.add(customizerCard)
        root.add(Box.createVerticalGlue())

        add(StudioTheme.styleScrollPane(JScrollPane(root)), BorderLayout.CENTER)
    }

    fun updateState(
        ui: DesktopStudioUiState,
        play: DesktopPlaybackState,
        net: NetBridgeState,
        sync: DesktopSyncedPlayheadState,
        stemState: DesktopStemState = DesktopStemState(),
        secretsState: DesktopSecretsState = DesktopSecretsState(),
        collabPeers: List<CollabPeerDevice> = emptyList()
    ) {
        updating = true
        try {
            collabStrip.updateState(collabPeers, ui.bandRecState)
            val ws = ui.workspaceConfig
            if (!nodeNameField.hasFocus() && nodeNameField.text != ws.nodeName) {
                nodeNameField.text = ws.nodeName
            }
            autoResumeCb.isSelected = ws.autoResumeOnHandoff
            autoAcceptCb.isSelected = ws.autoAcceptIncomingHandoff

            DesktopStation.entries.forEach { st ->
                navCheckboxes[st]?.isSelected = ws.visibleStations.contains(st)
                wheelCheckboxes[st]?.isSelected = ws.radialWheelStations.contains(st)
            }

            applyPendingBtn.isEnabled = ui.pendingHandoff != null
            handoffStatusLabel.text = when {
                ui.pendingHandoff != null -> "PENDING HANDOFF READY FROM ${ui.pendingHandoff.senderName} (${ui.pendingHandoff.stationKey})"
                net.lastHandoffSender != null -> "LAST HANDOFF: ${net.lastHandoffSender} • PEER LINK ${ui.rigConfig.txHost}:${ui.rigConfig.txPort} (${ui.rigConfig.protocol.label})"
                else -> "PEER TARGET: ${ui.rigConfig.txHost}:${ui.rigConfig.txPort} • RX LISTEN :${ui.rigConfig.bindPort} • ${ui.rigConfig.protocol.label}"
            }

            syncPlayBtn.text = if (sync.isPlaying) "STOP SYNC ALL" else "SYNC ALL PLAY"
            val bufDur = ui.processedBuffer?.durationSeconds ?: 0f
            telemetryLabel.text = "DECK: ${"%.2f".format(bufDur)}s (${if (play.isLooping) "LOOP ON" else "LOOP OFF"}) • SYNC BAR ${sync.bar}.${sync.beat} @ ${sync.bpm.toInt()} BPM • ${ui.fxState.activeCount} FX • VMC RX ${net.vmcRxCount}/TX ${net.vmcTxCount}"
            stemPanel.updateState(stemState, secretsState)
        } finally {
            updating = false
        }
    }

    private fun darkCheck(title: String, color: java.awt.Color, onToggle: (Boolean) -> Unit): JCheckBox =
        StudioTheme.darkCheckBox(title, color) { selected ->
            if (!updating) onToggle(selected)
        }

    companion object {
        fun stationAccent(st: DesktopStation): java.awt.Color = when (st) {
            DesktopStation.BOOTH -> StudioTheme.NeonCyan
            DesktopStation.EDITOR -> StudioTheme.NeonCyan
            DesktopStation.SAMPLES -> StudioTheme.EmeraldSignal
            DesktopStation.INSTRUMENTS -> StudioTheme.AmberHot
            DesktopStation.TRACKS -> StudioTheme.VioletMod
            DesktopStation.FX_STOMP -> StudioTheme.MagentaFx
            DesktopStation.LIVE -> StudioTheme.TacCoral
            DesktopStation.TOOLS -> StudioTheme.MagentaFx
            DesktopStation.TUNER -> StudioTheme.EmeraldSignal
            DesktopStation.VMC -> StudioTheme.NeonCyan
            DesktopStation.NET_LINK -> StudioTheme.AmberHot
        }
    }
}
