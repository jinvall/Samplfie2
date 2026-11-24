package com.samplfie.desktop.ui

import com.samplfie.desktop.stems.DesktopDisbandStem
import com.samplfie.desktop.stems.DesktopSecretsState
import com.samplfie.desktop.stems.DesktopSecretsVault
import com.samplfie.desktop.stems.DesktopStemPreset
import com.samplfie.desktop.stems.DesktopStemState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Dark hardware-styled Secrets & Keys Store dialog for Linux Desktop.
 */
class DesktopSecretsVaultDialog(
    owner: JFrame,
    initialState: DesktopSecretsState,
    private val onSaveSecret: (keyName: String, value: String) -> Unit,
    private val onDeleteSecret: (keyName: String) -> Unit,
    private val currentStateProvider: () -> DesktopSecretsState
) : JDialog(owner, "Secrets & Keys Vault — Samplfie2", true) {

    private val disbandField = StudioTheme.styleTextField(JPasswordField(24), 12f, 260, 32) as JPasswordField
    private val urlField = StudioTheme.styleTextField(JTextField(initialState.disbandBaseUrl, 24), 12f, 280, 32)
    private val customNameField = StudioTheme.styleTextField(JTextField(14), 12f, 160, 32)
    private val customValueField = StudioTheme.styleTextField(JPasswordField(18), 12f, 200, 32) as JPasswordField
    private val disbandStatusLabel = StudioTheme.hardwareLabel("", StudioTheme.EmeraldSignal, StudioTheme.MonoFont)
    private val entriesListPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val scaleListener: (Float) -> Unit = {
        size = StudioTheme.scaledDialogSize(700, 560)
        StudioTheme.rescaleComponentTree(this)
    }

    init {
        size = StudioTheme.scaledDialogSize(700, 560)
        background = StudioTheme.GunmetalDeck
        layout = BorderLayout(StudioTheme.s(8), StudioTheme.s(8))

        val root = JPanel().apply {
            background = StudioTheme.GunmetalDeck
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            StudioTheme.setScaledPadding(this, 12, 14, 12, 14)
        }

        // Disband Key Box
        val disbandBox = StudioTheme.hardwarePanel(StudioTheme.NeonCyan).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("DISBAND STEM API KEY (~/.config/samplfie2/secrets.properties • 0600)", StudioTheme.NeonCyan, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(StudioTheme.s(6)))
            add(disbandStatusLabel)
            add(Box.createVerticalStrut(StudioTheme.s(6)))
            val r1 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 6, 4).apply {
                add(StudioTheme.hardwareLabel("DISBAND_API_KEY:", StudioTheme.TextSecondary))
                add(disbandField)
                add(StudioTheme.hardwareButton("SHOW/HIDE", StudioTheme.TextSecondary) {
                    disbandField.echoChar = if (disbandField.echoChar == '\u0000') '•' else '\u0000'
                })
                add(StudioTheme.hardwareButton("SAVE KEY", StudioTheme.EmeraldSignal, filled = true) {
                    val v = String(disbandField.password)
                    if (v.isNotBlank()) {
                        onSaveSecret(DesktopSecretsVault.KEY_DISBAND_API_KEY, v)
                        disbandField.text = ""
                        refreshEntries()
                    }
                })
                add(StudioTheme.hardwareButton("CLEAR", StudioTheme.TacCoral) {
                    onDeleteSecret(DesktopSecretsVault.KEY_DISBAND_API_KEY)
                    refreshEntries()
                })
            }
            val r2 = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 6, 4).apply {
                add(StudioTheme.hardwareLabel("ENDPOINT URL:", StudioTheme.TextSecondary))
                add(urlField)
                add(StudioTheme.hardwareButton("SAVE URL", StudioTheme.NeonCyan) {
                    onSaveSecret(DesktopSecretsVault.KEY_DISBAND_BASE_URL, urlField.text.trim())
                    refreshEntries()
                })
            }
            add(r1)
            add(r2)
        }
        root.add(disbandBox)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        // Custom Secret Box
        val customBox = StudioTheme.hardwarePanel(StudioTheme.VioletMod).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(StudioTheme.hardwareLabel("ADD CUSTOM KEY OR SECRET", StudioTheme.VioletMod, StudioTheme.TitleFont))
            add(Box.createVerticalStrut(StudioTheme.s(4)))
            val r = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 6, 4).apply {
                add(StudioTheme.hardwareLabel("KEY:", StudioTheme.TextSecondary))
                add(customNameField)
                add(StudioTheme.hardwareLabel("SECRET:", StudioTheme.TextSecondary))
                add(customValueField)
                add(StudioTheme.hardwareButton("STORE", StudioTheme.VioletMod, filled = true) {
                    val k = customNameField.text.trim()
                    val v = String(customValueField.password)
                    if (k.isNotEmpty() && v.isNotBlank()) {
                        onSaveSecret(k, v)
                        customNameField.text = ""
                        customValueField.text = ""
                        refreshEntries()
                    }
                })
            }
            add(r)
        }
        root.add(customBox)
        root.add(Box.createVerticalStrut(StudioTheme.s(10)))

        // Entries List
        val listBox = StudioTheme.hardwarePanel(StudioTheme.HairlineDivider).apply {
            layout = BorderLayout(StudioTheme.s(4), StudioTheme.s(6))
            add(StudioTheme.hardwareLabel("STORED CREDENTIALS", StudioTheme.TextSecondary, StudioTheme.TitleFont), BorderLayout.NORTH)
            add(StudioTheme.styleScrollPane(JScrollPane(entriesListPanel).apply {
                isOpaque = false
                viewport.isOpaque = false
                StudioTheme.setScaledPrefSize(this, 560, 150)
            }), BorderLayout.CENTER)
        }
        root.add(listBox)

        add(StudioTheme.styleScrollPane(JScrollPane(root)), BorderLayout.CENTER)
        val footer = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 8, 6, opaque = true).apply {
            background = StudioTheme.GunmetalDeck
            add(StudioTheme.hardwareButton("CLOSE", StudioTheme.NeonCyan) { dispose() })
        }
        add(footer, BorderLayout.SOUTH)

        StudioTheme.addScaleListener(scaleListener)
        refreshEntries()
        setLocationRelativeTo(owner)
    }

    override fun dispose() {
        StudioTheme.removeScaleListener(scaleListener)
        super.dispose()
    }

    private fun refreshEntries() {
        val st = currentStateProvider()
        disbandStatusLabel.text = "STATUS: ${st.disbandKeySource} • BASE URL: ${st.disbandBaseUrl}"
        disbandStatusLabel.foreground = if (st.hasDisbandKey) StudioTheme.EmeraldSignal else StudioTheme.AmberHot
        entriesListPanel.removeAll()
        if (st.entries.isEmpty()) {
            entriesListPanel.add(StudioTheme.hardwareLabel("No secrets stored yet.", StudioTheme.TextMuted))
        } else {
            st.entries.forEach { e ->
                val row = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 2).apply {
                    add(StudioTheme.hardwareLabel("${e.keyName}: ${e.maskedValue} (${e.sourceLabel})", StudioTheme.TextPrimary, StudioTheme.MonoFont))
                    if (!e.isReadOnlyEnv) {
                        add(StudioTheme.hardwareButton("DEL", StudioTheme.TacCoral) {
                            onDeleteSecret(e.keyName)
                            refreshEntries()
                        })
                    }
                }
                entriesListPanel.add(row)
            }
        }
        entriesListPanel.revalidate()
        entriesListPanel.repaint()
    }
}

/**
 * Embedded Disband AI Stem Separation studio panel for Desktop Production Booth.
 */
class DesktopStemSeparationPanel(
    private val onToggleStem: (DesktopDisbandStem) -> Unit,
    private val onApplyPreset: (DesktopStemPreset) -> Unit,
    private val onSetScopeSelectionOnly: (Boolean) -> Unit,
    private val onRequestSeparate: () -> Unit,
    private val onCancelSeparate: () -> Unit,
    private val onOpenVault: () -> Unit,
    private val onLoadStemToDeck: (String) -> Unit,
    private val onSendStemToLoop: (String, Int) -> Unit,
    private val onAddStemToTrack: (String, Int) -> Unit,
    private val onSendAllToTracks: () -> Unit,
    private val onSendAllToLooper: () -> Unit = {},
    private val onSaveStemWav: (String) -> Unit = {}
) : JPanel() {

    private var updating = false
    private val stemCheckboxes = mutableMapOf<DesktopDisbandStem, JCheckBox>()
    private val scopeSelCb = StudioTheme.styleCheckBox(JCheckBox("SELECTION ONLY"), StudioTheme.NeonCyan, 12f).apply {
        addActionListener { if (!updating) onSetScopeSelectionOnly(isSelected) }
    }
    private val keyBadge = StudioTheme.hardwareLabel("KEY: MISSING", StudioTheme.AmberHot, StudioTheme.MonoFont)
    private val statusLabel = StudioTheme.hardwareLabel("", StudioTheme.TextSecondary, StudioTheme.MonoFont)
    private val separateBtn = StudioTheme.hardwareButton("GET STEMS (ALL - CONFIRM)", StudioTheme.AmberHot, filled = true) {
        onRequestSeparate()
    }
    private val cancelBtn = StudioTheme.hardwareButton("CANCEL", StudioTheme.TacCoral) { onCancelSeparate() }
    private val errorPayloadArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        background = StudioTheme.ObsidianChassis
        foreground = StudioTheme.TextPrimary
        caretColor = StudioTheme.TacCoral
        font = StudioTheme.MonoFont
        StudioTheme.setScaledPadding(this, 8, 10, 8, 10)
    }
    private val errorPayloadScroll = StudioTheme.styleScrollPane(
        JScrollPane(
            errorPayloadArea,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ).apply {
            StudioTheme.setScaledPrefSize(this, 780, 240)
            StudioTheme.setScaledMinSize(this, 360, 140)
        }
    )
    private var currentErrorPayloadText: String = ""
    private val errorPayloadPanel = StudioTheme.hardwarePanel(StudioTheme.TacCoral).apply {
        layout = BorderLayout(StudioTheme.s(6), StudioTheme.s(6))
        isVisible = false
        val hdr = JPanel(BorderLayout(StudioTheme.s(8), 0)).apply {
            isOpaque = false
            add(StudioTheme.hardwareLabel("ERROR PAYLOAD & HEADERS (FULL)", StudioTheme.TacCoral, StudioTheme.TitleFont), BorderLayout.WEST)
            val btns = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 6, 2).apply {
                add(StudioTheme.hardwareButton("COPY FULL PAYLOAD", StudioTheme.NeonCyan) {
                    if (currentErrorPayloadText.isNotBlank()) {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(
                            StringSelection(currentErrorPayloadText),
                            null
                        )
                    }
                })
                add(StudioTheme.hardwareButton("EXPAND WINDOW", StudioTheme.TacCoral) {
                    if (currentErrorPayloadText.isNotBlank()) {
                        showFullErrorWindow()
                    }
                })
            }
            add(btns, BorderLayout.EAST)
        }
        add(hdr, BorderLayout.NORTH)
        add(errorPayloadScroll, BorderLayout.CENTER)
    }
    private val resultsPanel = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private var lastStemsSignature = ""

    init {
        background = StudioTheme.BrushedPanel
        StudioTheme.setScaledPanelBorder(this, StudioTheme.MagentaFx, 1)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val header = JPanel(BorderLayout(StudioTheme.s(8), 0)).apply {
            isOpaque = false
            add(StudioTheme.hardwareLabel("DISBAND AI STEM SEPARATION (VOCALS / DRUMS / BASS / GUITAR / ALL)", StudioTheme.MagentaFx, StudioTheme.TitleFont), BorderLayout.WEST)
            val right = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 8, 2).apply {
                add(keyBadge)
                add(StudioTheme.hardwareButton("KEYS & SECRETS", StudioTheme.NeonCyan) { onOpenVault() })
            }
            add(right, BorderLayout.EAST)
        }
        add(header)
        add(Box.createVerticalStrut(StudioTheme.s(6)))

        // Quick Presets Row
        val presetRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 6, 3).apply {
            add(StudioTheme.hardwareLabel("PRESETS:", StudioTheme.TextSecondary))
            DesktopStemPreset.entries.forEach { preset ->
                val accent = if (preset == DesktopStemPreset.UNSPECIFIED_ALL) StudioTheme.AmberHot else StudioTheme.NeonCyan
                add(StudioTheme.hardwareButton(preset.label, accent) { onApplyPreset(preset) })
            }
        }
        add(presetRow)

        // Primary + Pro Sub-stem checkboxes (wrapping dynamically so labels never clip at 200%-300%)
        val stemsGrid = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 12, 4).apply {
            DesktopDisbandStem.entries.forEach { stem ->
                val cb = StudioTheme.styleCheckBox(
                    JCheckBox(stem.label),
                    if (stem.isPrimary) StudioTheme.MagentaFx else StudioTheme.VioletMod,
                    12f
                ).apply {
                    addActionListener { if (!updating) onToggleStem(stem) }
                }
                stemCheckboxes[stem] = cb
                add(cb)
            }
        }
        add(stemsGrid)
        add(Box.createVerticalStrut(StudioTheme.s(4)))

        // Action bar
        val actionRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 3).apply {
            add(scopeSelCb)
            add(separateBtn)
            add(cancelBtn)
            add(StudioTheme.hardwareButton("ALL -> LOOPS", StudioTheme.NeonCyan) { onSendAllToLooper() })
            add(StudioTheme.hardwareButton("ALL -> TRACKS", StudioTheme.EmeraldSignal) { onSendAllToTracks() })
        }
        add(actionRow)
        add(statusLabel)
        add(Box.createVerticalStrut(StudioTheme.s(4)))
        add(errorPayloadPanel)
        add(Box.createVerticalStrut(StudioTheme.s(4)))
        add(resultsPanel)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height.coerceAtLeast(StudioTheme.s(120)))

    private fun showFullErrorWindow() {
        val owner = SwingUtilities.getWindowAncestor(this)
        val dlg = JDialog(owner, "Disband Stem Separation — Full Error Payload & Headers", java.awt.Dialog.ModalityType.MODELESS).apply {
            size = StudioTheme.scaledDialogSize(960, 680)
            background = StudioTheme.GunmetalDeck
            layout = BorderLayout(StudioTheme.s(8), StudioTheme.s(8))
            val area = JTextArea(currentErrorPayloadText).apply {
                isEditable = false
                lineWrap = false
                background = StudioTheme.ObsidianChassis
                foreground = StudioTheme.TextPrimary
                font = StudioTheme.MonoFont
                StudioTheme.setScaledPadding(this, 10, 12, 10, 12)
                caretPosition = 0
            }
            add(StudioTheme.styleScrollPane(JScrollPane(area)), BorderLayout.CENTER)
            val footer = StudioTheme.wrapRowPanel(FlowLayout.RIGHT, 8, 6, opaque = true).apply {
                background = StudioTheme.GunmetalDeck
                add(StudioTheme.hardwareButton("COPY FULL PAYLOAD", StudioTheme.NeonCyan) {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        StringSelection(currentErrorPayloadText),
                        null
                    )
                })
                add(StudioTheme.hardwareButton("CLOSE", StudioTheme.TacCoral) { dispose() })
            }
            add(footer, BorderLayout.SOUTH)
            setLocationRelativeTo(owner)
        }
        dlg.isVisible = true
    }

    fun updateState(st: DesktopStemState, secrets: DesktopSecretsState) {
        updating = true
        try {
            keyBadge.text = if (secrets.hasDisbandKey) "KEY: ${secrets.disbandKeySource}" else "KEY: MISSING"
            keyBadge.foreground = if (secrets.hasDisbandKey) StudioTheme.EmeraldSignal else StudioTheme.AmberHot
            scopeSelCb.isSelected = st.useSelectionOnly
            DesktopDisbandStem.entries.forEach { stem ->
                stemCheckboxes[stem]?.isSelected = st.selectedStems.contains(stem)
            }
            separateBtn.isEnabled = !st.isRunning
            cancelBtn.isEnabled = st.isRunning
            separateBtn.text = if (st.selectedStems.isEmpty()) {
                "GET STEMS (ALL - CONFIRM)"
            } else {
                "GET ${st.selectedStems.size} STEM(S)"
            }
            separateBtn.background = if (st.selectedStems.isEmpty()) StudioTheme.AmberHot else StudioTheme.MagentaFx
            statusLabel.text = st.errorMessage ?: st.statusMessage
            statusLabel.foreground = if (st.errorMessage != null) StudioTheme.TacCoral else StudioTheme.TextSecondary

            val detail = st.errorPayloadDetail.orEmpty()
            val shouldShowErrorPayload = detail.isNotBlank()
            if (shouldShowErrorPayload != errorPayloadPanel.isVisible || detail != currentErrorPayloadText) {
                currentErrorPayloadText = detail
                errorPayloadArea.text = detail
                errorPayloadArea.caretPosition = 0
                errorPayloadPanel.isVisible = shouldShowErrorPayload
                revalidate()
                repaint()
            }

            val sig = st.separatedStems.joinToString("|") { "${it.stemId}:${it.sizeBytes}" }
            if (sig != lastStemsSignature) {
                lastStemsSignature = sig
                resultsPanel.removeAll()
                st.separatedStems.forEachIndexed { idx, stem ->
                    val row = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 6, 2).apply {
                        val slot = idx % 4
                        add(StudioTheme.hardwareLabel("${stem.label.uppercase()} (${"%.2f".format(stem.buffer.durationSeconds)}s, ${stem.sizeBytes / 1024}KB)", StudioTheme.NeonCyan, StudioTheme.MonoFont))
                        add(StudioTheme.hardwareButton("-> DECK", StudioTheme.NeonCyan) { onLoadStemToDeck(stem.stemId) })
                        add(StudioTheme.hardwareButton("-> LOOP ${'A' + slot}", StudioTheme.EmeraldSignal) { onSendStemToLoop(stem.stemId, slot) })
                        add(StudioTheme.hardwareButton("-> TRK ${slot + 1}", StudioTheme.MagentaFx) { onAddStemToTrack(stem.stemId, slot) })
                        add(StudioTheme.hardwareButton("-> .WAV", StudioTheme.AmberHot) { onSaveStemWav(stem.stemId) })
                    }
                    resultsPanel.add(row)
                }
                resultsPanel.revalidate()
                resultsPanel.repaint()
            }
        } finally {
            updating = false
        }
    }

    companion object {
        fun showAllStemsConfirmDialog(
            parent: Component,
            estimatedCredits: Int,
            durationSec: Float
        ): Boolean {
            val res = JOptionPane.showConfirmDialog(
                parent,
                "No specific stem set was specified.\n\nDisband will separate ALL 6 stems (Vocals, Drums, Bass, Guitar, Piano, Other).\n" +
                    "Audio: ${"%.1f".format(durationSec)}s • Est. Cost: ~$estimatedCredits Disband credits.\n\nProceed with All-Stems separation?",
                "Separate All Stems? (High Credit Cost)",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            )
            return res == JOptionPane.YES_OPTION
        }
    }
}
