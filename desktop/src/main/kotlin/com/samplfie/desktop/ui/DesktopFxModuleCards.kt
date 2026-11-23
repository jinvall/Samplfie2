package com.samplfie.desktop.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider

enum class FxSectionFilter(val label: String) {
    ALL("ALL (14)"),
    DYNAMICS_PITCH("DYNAMICS & PITCH"),
    DRIVE_MOD("OCTAVE, DRIVE & MOD"),
    DELAY_SPACE("DELAYS & REVERB")
}

/**
 * Reusable HiDPI-scalable Swing parameter controls for full-scope FX Rack editing.
 */
class FxParamSlider(
    private val title: String,
    min: Int,
    max: Int,
    initial: Int,
    private val accent: Color,
    private val formatter: (Int) -> String,
    private val isUpdating: () -> Boolean,
    private val onCommit: (Int) -> Unit
) : JPanel(BorderLayout(StudioTheme.s(6), 0)) {

    val slider: JSlider = StudioTheme.hardwareSlider(min, max, initial.coerceIn(min, max)).apply {
        foreground = accent
        StudioTheme.setScaledPrefSize(this, 160, 26)
    }
    private val valueLabel: JLabel = StudioTheme.hardwareLabel(
        formatter(slider.value),
        accent,
        StudioTheme.MonoFont
    )
    private val titleLabel: JLabel = StudioTheme.hardwareLabel(
        title,
        StudioTheme.TextSecondary,
        StudioTheme.MonoSmallFont
    )

    init {
        isOpaque = false
        val header = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.WEST)
            add(valueLabel, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(slider, BorderLayout.CENTER)
        slider.addChangeListener {
            valueLabel.text = formatter(slider.value)
            if (!isUpdating()) {
                onCommit(slider.value)
            }
        }
    }

    fun syncValue(rawValue: Int, enabled: Boolean = true) {
        slider.isEnabled = enabled
        val clamped = rawValue.coerceIn(slider.minimum, slider.maximum)
        if (!slider.valueIsAdjusting && slider.value != clamped) {
            slider.value = clamped
        }
        valueLabel.text = formatter(if (slider.valueIsAdjusting) slider.value else clamped)
        titleLabel.foreground = if (enabled) StudioTheme.TextSecondary else StudioTheme.TextMuted
        valueLabel.foreground = if (enabled) accent else StudioTheme.TextMuted
    }
}

class FxModuleCardBuilder(
    val section: FxSectionFilter,
    title: String,
    subtitle: String,
    private val accent: Color,
    private val isUpdating: () -> Boolean,
    onToggle: (Boolean) -> Unit
) {
    val enabledCheck: JCheckBox = StudioTheme.darkCheckBox(title, accent, basePt = 13f).apply {
        addActionListener { if (!isUpdating()) onToggle(isSelected) }
    }
    private val badgeLabel: JLabel = StudioTheme.hardwareLabel("OFF", StudioTheme.TextMuted, StudioTheme.MonoFont)
    private val controlsBody = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val inlineChipsRow = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 4)
    private var hasInlineChips = false

    val panel: JPanel = object : JPanel() {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        background = StudioTheme.BrushedPanel
        StudioTheme.setScaledPanelBorder(this, StudioTheme.HairlineDivider, 1, 10, 12)
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        val headerRow = JPanel(BorderLayout(StudioTheme.s(8), 0)).apply {
            isOpaque = false
            val titleCol = JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                enabledCheck.alignmentX = JPanel.LEFT_ALIGNMENT
                add(enabledCheck)
                add(StudioTheme.hardwareLabel(subtitle, StudioTheme.TextMuted, StudioTheme.font("SansSerif", Font.PLAIN, 11f)).apply {
                    alignmentX = JPanel.LEFT_ALIGNMENT
                })
            }
            add(titleCol, BorderLayout.CENTER)
            add(badgeLabel, BorderLayout.EAST)
        }
        headerRow.alignmentX = JPanel.LEFT_ALIGNMENT
        add(headerRow)
        add(Box.createVerticalStrut(StudioTheme.s(6)))
        inlineChipsRow.alignmentX = JPanel.LEFT_ALIGNMENT
        inlineChipsRow.isVisible = false
        add(inlineChipsRow)
        controlsBody.alignmentX = JPanel.LEFT_ALIGNMENT
        add(controlsBody)
    }

    fun addSlider(
        label: String,
        min: Int,
        max: Int,
        initial: Int,
        formatter: (Int) -> String,
        onCommit: (Int) -> Unit
    ): FxParamSlider {
        val ctrl = FxParamSlider(label, min, max, initial, accent, formatter, isUpdating, onCommit)
        ctrl.alignmentX = JPanel.LEFT_ALIGNMENT
        controlsBody.add(ctrl)
        controlsBody.add(Box.createVerticalStrut(StudioTheme.s(4)))
        return ctrl
    }

    fun addCheck(label: String, chipAccent: Color = accent, onToggle: (Boolean) -> Unit): JCheckBox {
        val cb = StudioTheme.darkCheckBox(label, chipAccent, basePt = 11.5f).apply {
            addActionListener { if (!isUpdating()) onToggle(isSelected) }
        }
        hasInlineChips = true
        inlineChipsRow.isVisible = true
        inlineChipsRow.add(cb)
        return cb
    }

    fun <T> addCombo(label: String, items: Array<T>, onSelect: (T) -> Unit): JComboBox<T> {
        val box = StudioTheme.styleComboBox(JComboBox(items), basePt = 11.5f).apply {
            addActionListener {
                if (!isUpdating()) {
                    @Suppress("UNCHECKED_CAST")
                    val sel = selectedItem as? T ?: return@addActionListener
                    onSelect(sel)
                }
            }
        }
        hasInlineChips = true
        inlineChipsRow.isVisible = true
        val wrap = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 4, 2).apply {
            add(StudioTheme.hardwareLabel(label, StudioTheme.TextSecondary, StudioTheme.MonoSmallFont))
            add(box)
        }
        inlineChipsRow.add(wrap)
        return box
    }

    fun syncHeader(enabled: Boolean) {
        if (enabledCheck.isSelected != enabled) {
            enabledCheck.isSelected = enabled
        }
        badgeLabel.text = if (enabled) "ACTIVE" else "OFF"
        badgeLabel.foreground = if (enabled) accent else StudioTheme.TextMuted
        StudioTheme.setScaledPanelBorder(
            panel,
            if (enabled) accent else StudioTheme.HairlineDivider,
            if (enabled) 2 else 1,
            10,
            12
        )
    }
}
