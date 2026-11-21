package com.samplfie.desktop.ui

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.border.Border

/**
 * Exact Samplfie2 Obsidian/Gunmetal/Neon Hardware Palette & Linux Dark Component Helpers
 * matching the Android Jetpack Compose theme pixel-for-pixel.
 */
object StudioTheme {
    val ObsidianChassis = Color(0x0B, 0x0D, 0x12)
    val GunmetalDeck = Color(0x15, 0x19, 0x22)
    val BrushedPanel = Color(0x1D, 0x23, 0x30)
    val ElevatedBezel = Color(0x26, 0x2E, 0x3E)
    val HairlineDivider = Color(0x2B, 0x34, 0x45)

    val NeonCyan = Color(0x00, 0xE5, 0xFF)
    val TacCoral = Color(0xFF, 0x4D, 0x5A)
    val AmberHot = Color(0xFF, 0xB0, 0x20)
    val EmeraldSignal = Color(0x00, 0xE6, 0x76)
    val VioletMod = Color(0x8C, 0x6C, 0xFF)
    val MagentaFx = Color(0xFF, 0x4D, 0xCA)

    val TextPrimary = Color(0xF1, 0xF5, 0xF9)
    val TextSecondary = Color(0x94, 0xA3, 0xB8)
    val TextMuted = Color(0x64, 0x74, 0x8B)

    val TitleFont = Font("SansSerif", Font.BOLD, 14)
    val LabelFont = Font("SansSerif", Font.BOLD, 11)
    val MonoFont = Font("Monospaced", Font.BOLD, 12)
    val MonoLargeFont = Font("Monospaced", Font.BOLD, 34)
    val BodyFont = Font("SansSerif", Font.PLAIN, 12)

    fun panelBorder(accent: Color = HairlineDivider, thickness: Int = 1): Border =
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(accent, thickness, true),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        )

    fun hardwarePanel(accent: Color = HairlineDivider): JPanel = JPanel().apply {
        background = BrushedPanel
        border = panelBorder(accent)
    }

    fun hardwareLabel(text: String, color: Color = TextSecondary, font: Font = LabelFont): JLabel =
        JLabel(text).apply {
            foreground = color
            this.font = font
        }

    fun hardwareButton(
        text: String,
        accent: Color = NeonCyan,
        filled: Boolean = false,
        onClick: () -> Unit = {}
    ): JButton = object : JButton(text) {
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = when {
                !isEnabled -> ObsidianChassis
                filled -> accent
                model.isPressed -> accent.darker()
                model.isRollover -> Color(accent.red, accent.green, accent.blue, 55)
                else -> Color(accent.red, accent.green, accent.blue, 24)
            }
            g2.color = bg
            g2.fillRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.color = if (isEnabled) accent else HairlineDivider
            g2.drawRoundRect(0, 0, width - 1, height - 1, 10, 10)
            g2.font = font
            val fm = g2.fontMetrics
            val tx = (width - fm.stringWidth(this.text)) / 2
            val ty = (height + fm.ascent - fm.descent) / 2
            g2.color = when {
                !isEnabled -> TextMuted
                filled -> ObsidianChassis
                else -> TextPrimary
            }
            g2.drawString(this.text, tx, ty)
            g2.dispose()
        }
    }.apply {
        font = LabelFont
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        preferredSize = Dimension(preferredSize.width + 18, 32)
        addActionListener { onClick() }
    }

    fun hardwareSlider(min: Int, max: Int, value: Int): JSlider = JSlider(min, max, value).apply {
        background = BrushedPanel
        foreground = NeonCyan
        isOpaque = false
    }

    fun withAlpha(c: Color, alpha: Int): Color = Color(c.red, c.green, c.blue, alpha.coerceIn(0, 255))
}
