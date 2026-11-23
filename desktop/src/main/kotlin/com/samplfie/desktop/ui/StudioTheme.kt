package com.samplfie.desktop.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.geom.RoundRectangle2D
import java.io.File
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Exact Samplfie2 Obsidian/Gunmetal/Neon Hardware Palette & Scalable Linux HiDPI UI Engine.
 * Supports automatic large-monitor/HiDPI detection plus live 75%..300% runtime UI scaling across
 * every font, widget, canvas stroke, menu, dialog, and wrapping layout without restarting.
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

    const val MIN_UI_SCALE = 0.75f
    const val MAX_UI_SCALE = 3.0f
    val UI_SCALE_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 3.0f)

    private const val PROP_FONT_SPEC = "samplfie.fontSpec"
    private const val PROP_BASE_PREF = "samplfie.basePref"
    private const val PROP_BASE_MIN = "samplfie.baseMin"
    private const val PROP_BASE_MAX = "samplfie.baseMax"
    private const val PROP_BASE_PADDING = "samplfie.basePadding"
    private const val PROP_PANEL_BORDER = "samplfie.panelBorder"

    private data class FontSpec(val family: String, val style: Int, val basePt: Float)
    private data class PanelBorderSpec(val accent: Color, val thickness: Int, val padV: Int, val padH: Int)

    private val scaleListeners = CopyOnWriteArrayList<(Float) -> Unit>()

    @Volatile
    var uiScale: Float = loadOrDetectInitialScale()
        private set

    @Volatile
    var hasExplicitSavedScale: Boolean = scaleConfigFile().exists()
        private set

    val TitleFont: Font get() = font("SansSerif", Font.BOLD, 15f)
    val LabelFont: Font get() = font("SansSerif", Font.BOLD, 12f)
    val MonoFont: Font get() = font("Monospaced", Font.BOLD, 12f)
    val MonoSmallFont: Font get() = font("Monospaced", Font.BOLD, 10.5f)
    val MonoLargeFont: Font get() = font("Monospaced", Font.BOLD, 36f)
    val BodyFont: Font get() = font("SansSerif", Font.PLAIN, 13f)

    fun s(px: Int): Int = (px * uiScale).roundToInt().coerceAtLeast(1)
    fun s0(px: Int): Int = if (px <= 0) 0 else (px * uiScale).roundToInt().coerceAtLeast(1)
    fun sf(px: Float): Float = px * uiScale
    fun sd(px: Double): Double = px * uiScale

    fun dim(w: Int, h: Int): Dimension = Dimension(
        if (w == Int.MAX_VALUE) Int.MAX_VALUE else s(w),
        if (h == Int.MAX_VALUE) Int.MAX_VALUE else s(h)
    )

    fun font(family: String, style: Int, basePt: Float): Font =
        Font(family, style, 12).deriveFont((basePt * uiScale).coerceAtLeast(6f))

    fun font(family: String, style: Int, basePt: Int): Font =
        font(family, style, basePt.toFloat())

    fun stroke(
        baseWidth: Float,
        cap: Int = BasicStroke.CAP_SQUARE,
        join: Int = BasicStroke.JOIN_MITER
    ): BasicStroke = BasicStroke((baseWidth * uiScale).coerceAtLeast(0.75f), cap, join)

    fun addScaleListener(listener: (Float) -> Unit) {
        scaleListeners.addIfAbsent(listener)
    }

    fun removeScaleListener(listener: (Float) -> Unit) {
        scaleListeners.remove(listener)
    }

    fun scalePercentLabel(scale: Float = uiScale): String = "${(scale * 100f).roundToInt()}%"

    fun initForDesktop() {
        uiScale = loadOrDetectInitialScale()
        applyUiDefaults()
    }

    fun autoDetectDisplayScale(
        screenWidthPx: Int? = null,
        screenHeightPx: Int? = null,
        dpi: Int? = null,
        hwScaleX: Double? = null
    ): Float {
        if (screenWidthPx == null && GraphicsEnvironment.isHeadless()) return 1.0f

        val ge = if (!GraphicsEnvironment.isHeadless()) GraphicsEnvironment.getLocalGraphicsEnvironment() else null
        val gd = ge?.defaultScreenDevice
        val gc = gd?.defaultConfiguration
        val effectiveHwScale = hwScaleX ?: (gc?.defaultTransform?.scaleX ?: 1.0)
        // If OS/JVM already applies >= 1.5x Java2D transform, avoid automatic double-scaling
        if (effectiveHwScale >= 1.45) return 1.0f

        val modeW = gd?.displayMode?.width ?: 0
        val modeH = gd?.displayMode?.height ?: 0
        val boundsW = gc?.bounds?.width ?: 0
        val boundsH = gc?.bounds?.height ?: 0
        val w = screenWidthPx ?: max(modeW, boundsW)
        val h = screenHeightPx ?: max(modeH, boundsH)
        val resolvedDpi = dpi ?: runCatching { Toolkit.getDefaultToolkit().screenResolution }.getOrDefault(96)

        val byResolution = when {
            w >= 3400 || h >= 2000 -> 2.0f   // 4K / 43" UHD / 5K displays
            w >= 2400 || h >= 1350 -> 1.5f   // QHD / Ultrawide displays
            w >= 1900 -> 1.25f               // Full HD on large desktop displays
            else -> 1.0f
        }
        val byDpi = if (resolvedDpi > 108) (resolvedDpi / 96f).coerceIn(1.0f, 2.5f) else 1.0f
        val raw = max(byResolution, byDpi)
        // Snap cleanly to nearest 0.25 step
        return ((raw * 4f).roundToInt() / 4f).coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
    }

    fun setUiScale(
        newScale: Float,
        persist: Boolean = true,
        windows: Collection<Window> = if (GraphicsEnvironment.isHeadless()) emptyList() else Window.getWindows().toList()
    ) {
        val clamped = ((newScale * 20f).roundToInt() / 20f).coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
        uiScale = clamped
        if (persist) {
            saveScalePreference(clamped)
            hasExplicitSavedScale = true
        }
        applyUiDefaults()
        windows.forEach { w ->
            rescaleComponentTree(w)
        }
        scaleListeners.forEach { it(clamped) }
    }

    fun stepScale(
        delta: Float,
        windows: Collection<Window> = if (GraphicsEnvironment.isHeadless()) emptyList() else Window.getWindows().toList()
    ) {
        setUiScale(uiScale + delta, persist = true, windows = windows)
    }

    fun resetAutoScale(
        windows: Collection<Window> = if (GraphicsEnvironment.isHeadless()) emptyList() else Window.getWindows().toList()
    ) {
        runCatching { scaleConfigFile().delete() }
        hasExplicitSavedScale = false
        setUiScale(autoDetectDisplayScale(), persist = false, windows = windows)
    }

    fun applyUiDefaults() {
        val labelF = LabelFont
        val bodyF = BodyFont
        val monoF = MonoFont
        val titleF = TitleFont
        val fontKeys = listOf(
            "Label.font" to labelF,
            "Button.font" to labelF,
            "ToggleButton.font" to labelF,
            "CheckBox.font" to labelF,
            "RadioButton.font" to labelF,
            "ComboBox.font" to monoF,
            "TextField.font" to monoF,
            "PasswordField.font" to monoF,
            "TextArea.font" to monoF,
            "FormattedTextField.font" to monoF,
            "Spinner.font" to monoF,
            "Table.font" to bodyF,
            "TableHeader.font" to labelF,
            "List.font" to bodyF,
            "Tree.font" to bodyF,
            "TabbedPane.font" to labelF,
            "TitledBorder.font" to titleF,
            "ToolTip.font" to bodyF,
            "MenuBar.font" to labelF,
            "Menu.font" to labelF,
            "MenuItem.font" to bodyF,
            "CheckBoxMenuItem.font" to bodyF,
            "RadioButtonMenuItem.font" to bodyF,
            "PopupMenu.font" to bodyF,
            "OptionPane.messageFont" to bodyF,
            "OptionPane.buttonFont" to labelF,
            "FileChooser.listFont" to bodyF
        )
        fontKeys.forEach { (k, f) -> UIManager.put(k, f) }
        UIManager.put("ScrollBar.width", s(16))
        UIManager.put("ToolTip.background", GunmetalDeck)
        UIManager.put("ToolTip.foreground", TextPrimary)
    }

    fun rescaleComponentTree(root: Component) {
        if (root is JComponent) {
            (root.getClientProperty(PROP_FONT_SPEC) as? FontSpec)?.let { spec ->
                root.font = font(spec.family, spec.style, spec.basePt)
            }
            (root.getClientProperty(PROP_PANEL_BORDER) as? PanelBorderSpec)?.let { pb ->
                root.border = panelBorder(pb.accent, pb.thickness, pb.padV, pb.padH)
            }
            (root.getClientProperty(PROP_BASE_PADDING) as? Insets)?.let { ins ->
                root.border = BorderFactory.createEmptyBorder(
                    s0(ins.top), s0(ins.left), s0(ins.bottom), s0(ins.right)
                )
            }
            (root.getClientProperty(PROP_BASE_PREF) as? Dimension)?.let { d ->
                root.preferredSize = Dimension(
                    if (d.width == Int.MAX_VALUE) Int.MAX_VALUE else s(d.width),
                    if (d.height == Int.MAX_VALUE) Int.MAX_VALUE else s(d.height)
                )
            }
            (root.getClientProperty(PROP_BASE_MIN) as? Dimension)?.let { d ->
                root.minimumSize = Dimension(
                    if (d.width == Int.MAX_VALUE) Int.MAX_VALUE else s(d.width),
                    if (d.height == Int.MAX_VALUE) Int.MAX_VALUE else s(d.height)
                )
            }
            (root.getClientProperty(PROP_BASE_MAX) as? Dimension)?.let { d ->
                root.maximumSize = Dimension(
                    if (d.width == Int.MAX_VALUE) Int.MAX_VALUE else s(d.width),
                    if (d.height == Int.MAX_VALUE) Int.MAX_VALUE else s(d.height)
                )
            }
            when (root) {
                is JSpinner -> {
                    val ed = (root.editor as? JSpinner.DefaultEditor)?.textField
                    if (ed != null) {
                        val spec = (root.getClientProperty(PROP_FONT_SPEC) as? FontSpec)
                            ?: FontSpec("Monospaced", Font.BOLD, 12f)
                        ed.font = font(spec.family, spec.style, spec.basePt)
                        ed.background = ObsidianChassis
                        ed.foreground = TextPrimary
                        ed.caretColor = NeonCyan
                    }
                }
                is JScrollPane -> {
                    root.verticalScrollBar?.unitIncrement = s(22)
                    root.horizontalScrollBar?.unitIncrement = s(22)
                }
                is JTable -> {
                    root.rowHeight = s(24)
                }
                is JMenuBar -> {
                    root.font = LabelFont
                }
                is JMenu -> {
                    root.font = LabelFont
                    for (i in 0 until root.itemCount) {
                        root.getItem(i)?.let { rescaleComponentTree(it) }
                    }
                }
                is JMenuItem -> {
                    root.font = BodyFont
                }
            }
        }
        if (root is Container) {
            val lm = root.layout
            if (lm is StudioWrapLayout) {
                lm.hgap = s(lm.baseHgap)
                lm.vgap = s(lm.baseVgap)
            }
            for (i in 0 until root.componentCount) {
                rescaleComponentTree(root.getComponent(i))
            }
            root.invalidate()
        }
        if (root is Window) {
            SwingUtilities.invokeLater {
                root.revalidate()
                root.repaint()
            }
        }
    }

    fun panelBorder(
        accent: Color = HairlineDivider,
        thickness: Int = 1,
        padV: Int = 8,
        padH: Int = 10
    ): Border {
        val borderThick = if (thickness <= 0) 0 else s(thickness)
        return BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(accent, borderThick, true),
            BorderFactory.createEmptyBorder(s0(padV), s0(padH), s0(padV), s0(padH))
        )
    }

    fun setScaledPanelBorder(
        c: JComponent,
        accent: Color = HairlineDivider,
        thickness: Int = 1,
        padV: Int = 8,
        padH: Int = 10
    ) {
        c.putClientProperty(PROP_PANEL_BORDER, PanelBorderSpec(accent, thickness, padV, padH))
        c.border = panelBorder(accent, thickness, padV, padH)
    }

    fun setScaledPadding(c: JComponent, top: Int, left: Int, bottom: Int, right: Int) {
        c.putClientProperty(PROP_BASE_PADDING, Insets(top, left, bottom, right))
        c.border = BorderFactory.createEmptyBorder(s0(top), s0(left), s0(bottom), s0(right))
    }

    fun setScaledPrefSize(c: JComponent, w: Int, h: Int) {
        c.putClientProperty(PROP_BASE_PREF, Dimension(w, h))
        c.preferredSize = dim(w, h)
    }

    fun setScaledMinSize(c: JComponent, w: Int, h: Int) {
        c.putClientProperty(PROP_BASE_MIN, Dimension(w, h))
        c.minimumSize = dim(w, h)
    }

    fun setScaledMaxSize(c: JComponent, w: Int, h: Int) {
        c.putClientProperty(PROP_BASE_MAX, Dimension(w, h))
        c.maximumSize = dim(w, h)
    }

    /**
     * Tracks a font spec on a Swing component so runtime scale changes resize it automatically.
     */
    fun <T : JComponent> trackFont(
        c: T,
        family: String = "SansSerif",
        style: Int = Font.BOLD,
        basePt: Float = 12f
    ): T {
        c.putClientProperty(PROP_FONT_SPEC, FontSpec(family, style, basePt))
        c.font = font(family, style, basePt)
        return c
    }

    fun <T : JComponent> trackFont(c: T, referenceFont: Font): T {
        val basePt = (referenceFont.size2D / uiScale.coerceAtLeast(0.5f)).coerceAtLeast(8f)
        return trackFont(c, referenceFont.family, referenceFont.style, basePt)
    }

    /**
     * Hardware panel whose maximum BoxLayout height expands automatically to fit wrapped children
     * so enlarged fonts at 150%-300% UI scale are never clipped vertically.
     */
    fun hardwarePanel(accent: Color = HairlineDivider, autoFitBoxHeight: Boolean = true): JPanel =
        object : JPanel() {
            override fun getMaximumSize(): Dimension {
                val explicit = getClientProperty(PROP_BASE_MAX) as? Dimension
                if (explicit != null) {
                    return dim(explicit.width, explicit.height)
                }
                if (autoFitBoxHeight && parent?.layout is javax.swing.BoxLayout) {
                    val prefH = super.getPreferredSize().height
                    return Dimension(Int.MAX_VALUE, max(prefH, s(32)))
                }
                return super.getMaximumSize()
            }
        }.apply {
            background = BrushedPanel
            setScaledPanelBorder(this, accent, 1, 8, 10)
        }

    fun wrapRowPanel(
        align: Int = FlowLayout.LEFT,
        hgap: Int = 8,
        vgap: Int = 4,
        opaque: Boolean = false
    ): JPanel = JPanel(StudioWrapLayout(align, hgap, vgap)).apply {
        isOpaque = opaque
    }

    fun hardwareLabel(text: String, color: Color = TextSecondary, font: Font = LabelFont): JLabel =
        JLabel(text).apply {
            foreground = color
            trackFont(this, font)
        }

    fun hardwareButton(
        text: String,
        accent: Color = NeonCyan,
        filled: Boolean = false,
        basePt: Float = 12f,
        onClick: () -> Unit = {}
    ): JButton = object : JButton(text) {
        override fun getPreferredSize(): Dimension {
            val f = font ?: LabelFont
            val fm = getFontMetrics(f)
            val lines = (this.text ?: "").split('\n')
            val maxW = lines.maxOfOrNull { fm.stringWidth(it) } ?: 0
            val totalTextH = fm.height * max(1, lines.size)
            val prefW = maxW + s(22)
            val prefH = max(totalTextH + s(12), s(30))
            val customMin = getClientProperty(PROP_BASE_PREF) as? Dimension
            return if (customMin != null) {
                Dimension(max(prefW, s(customMin.width)), max(prefH, s(customMin.height)))
            } else {
                Dimension(prefW, prefH)
            }
        }

        override fun getMinimumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val arc = sf(10f)
            val bg = when {
                !isEnabled -> ObsidianChassis
                filled -> accent
                model.isPressed -> accent.darker()
                model.isRollover -> Color(accent.red, accent.green, accent.blue, 55)
                else -> Color(accent.red, accent.green, accent.blue, 24)
            }
            g2.color = bg
            val strokePad = sf(1f)
            val rr = RoundRectangle2D.Float(strokePad, strokePad, width - strokePad * 2f - 1f, height - strokePad * 2f - 1f, arc, arc)
            g2.fill(rr)
            g2.color = if (isEnabled) accent else HairlineDivider
            g2.stroke = stroke(1.3f)
            g2.draw(rr)

            g2.font = font
            val fm = g2.fontMetrics
            g2.color = when {
                !isEnabled -> TextMuted
                filled -> ObsidianChassis
                else -> TextPrimary
            }
            val lines = (this.text ?: "").split('\n')
            val lineH = fm.height
            val blockH = lineH * lines.size
            val startY = (height - blockH) / 2 + fm.ascent
            lines.forEachIndexed { idx, line ->
                val tx = (width - fm.stringWidth(line)) / 2f
                val ty = (startY + idx * lineH).toFloat()
                g2.drawString(line, tx, ty)
            }
            g2.dispose()
        }
    }.apply {
        trackFont(this, "SansSerif", Font.BOLD, basePt)
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { onClick() }
    }

    fun hardwareSlider(min: Int, max: Int, value: Int): JSlider = object : JSlider(min, max, value) {
        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            val minPref = getClientProperty(PROP_BASE_PREF) as? Dimension
            val w = minPref?.let { s(it.width) } ?: max(base.width, s(120))
            val h = minPref?.let { s(it.height) } ?: max(base.height, s(28))
            return Dimension(w, h)
        }
    }.apply {
        background = BrushedPanel
        foreground = NeonCyan
        isOpaque = false
    }

    fun darkCheckBox(title: String, color: Color, basePt: Float = 12f, onToggle: (Boolean) -> Unit = {}): JCheckBox =
        JCheckBox(title).apply {
            isOpaque = false
            foreground = color
            trackFont(this, "SansSerif", Font.BOLD, basePt)
            icon = ScaledCheckBoxIcon(color)
            iconTextGap = s(8)
            addActionListener { onToggle(isSelected) }
        }

    fun styleCheckBox(cb: JCheckBox, color: Color = cb.foreground ?: TextPrimary, basePt: Float = 12f): JCheckBox =
        cb.apply {
            isOpaque = false
            foreground = color
            trackFont(this, "SansSerif", Font.BOLD, basePt)
            icon = ScaledCheckBoxIcon(color)
            iconTextGap = s(8)
        }

    fun styleTextField(
        tf: JTextField,
        basePt: Float = 12f,
        baseWidth: Int? = null,
        baseHeight: Int = 32
    ): JTextField = tf.apply {
        background = ObsidianChassis
        foreground = TextPrimary
        caretColor = NeonCyan
        trackFont(this, "Monospaced", Font.BOLD, basePt)
        setScaledPanelBorder(this, HairlineDivider, 1, 5, 8)
        if (baseWidth != null) {
            setScaledPrefSize(this, baseWidth, baseHeight)
        }
    }

    fun <T> styleComboBox(box: JComboBox<T>, basePt: Float = 12f): JComboBox<T> = box.apply {
        background = ObsidianChassis
        foreground = TextPrimary
        trackFont(this, "Monospaced", Font.BOLD, basePt)
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (c is JLabel) {
                    c.font = font("Monospaced", Font.BOLD, basePt)
                    c.border = BorderFactory.createEmptyBorder(s(4), s(8), s(4), s(8))
                    c.background = if (isSelected) ElevatedBezel else ObsidianChassis
                    c.foreground = if (isSelected) NeonCyan else TextPrimary
                }
                return c
            }
        }
    }

    fun styleSpinner(
        spinner: JSpinner,
        basePt: Float = 12f,
        baseWidth: Int = 88,
        baseHeight: Int = 32
    ): JSpinner = spinner.apply {
        trackFont(this, "Monospaced", Font.BOLD, basePt)
        setScaledPrefSize(this, baseWidth, baseHeight)
        (editor as? JSpinner.DefaultEditor)?.textField?.let { ed ->
            ed.background = ObsidianChassis
            ed.foreground = TextPrimary
            ed.caretColor = NeonCyan
            trackFont(ed, "Monospaced", Font.BOLD, basePt)
        }
    }

    fun styleScrollPane(sp: JScrollPane): JScrollPane = sp.apply {
        border = null
        background = ObsidianChassis
        viewport.background = ObsidianChassis
        verticalScrollBar?.unitIncrement = s(22)
        horizontalScrollBar?.unitIncrement = s(22)
    }

    fun scaledDialogSize(baseW: Int, baseH: Int): Dimension {
        if (GraphicsEnvironment.isHeadless()) return dim(baseW, baseH)
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        return Dimension(
            s(baseW).coerceAtMost((bounds.width * 0.94).toInt()),
            s(baseH).coerceAtMost((bounds.height * 0.92).toInt())
        )
    }

    fun applyMenuTypography(menuBar: JMenuBar) {
        menuBar.font = LabelFont
        for (i in 0 until menuBar.menuCount) {
            val menu = menuBar.getMenu(i) ?: continue
            menu.font = LabelFont
            for (j in 0 until menu.itemCount) {
                menu.getItem(j)?.let { item ->
                    item.font = BodyFont
                }
            }
        }
    }

    fun withAlpha(c: Color, alpha: Int): Color = Color(c.red, c.green, c.blue, alpha.coerceIn(0, 255))

    private class ScaledCheckBoxIcon(private val accent: Color) : Icon {
        override fun getIconWidth(): Int = s(16)
        override fun getIconHeight(): Int = s(16)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val cb = c as? AbstractButton ?: return
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val sz = s(16)
            val arc = sf(4f)
            val rr = RoundRectangle2D.Float(x.toFloat(), y.toFloat(), (sz - 1).toFloat(), (sz - 1).toFloat(), arc, arc)
            g2.color = if (cb.isSelected) withAlpha(accent, 55) else ObsidianChassis
            g2.fill(rr)
            g2.color = if (cb.isEnabled) accent else HairlineDivider
            g2.stroke = stroke(1.5f)
            g2.draw(rr)
            if (cb.isSelected) {
                g2.color = accent
                g2.stroke = stroke(2.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(x + (sz * 0.22f).roundToInt(), y + (sz * 0.52f).roundToInt(), x + (sz * 0.43f).roundToInt(), y + (sz * 0.75f).roundToInt())
                g2.drawLine(x + (sz * 0.43f).roundToInt(), y + (sz * 0.75f).roundToInt(), x + (sz * 0.78f).roundToInt(), y + (sz * 0.26f).roundToInt())
            }
            g2.dispose()
        }
    }

    private fun scaleConfigFile(): File {
        val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
        return File(File(File(home, ".config"), "samplfie2"), "ui_scale.properties")
    }

    private fun loadOrDetectInitialScale(): Float {
        val f = scaleConfigFile()
        if (f.exists()) {
            runCatching {
                val props = Properties()
                f.inputStream().use { props.load(it) }
                props.getProperty("ui_scale")?.toFloatOrNull()?.let { saved ->
                    return saved.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE)
                }
            }
        }
        return autoDetectDisplayScale()
    }

    private fun saveScalePreference(scale: Float) {
        runCatching {
            val f = scaleConfigFile()
            f.parentFile?.mkdirs()
            val props = Properties()
            props["ui_scale"] = "%.2f".format(java.util.Locale.US, scale)
            f.outputStream().use { props.store(it, "Samplfie2 Desktop UI Scale") }
        }
    }
}

/**
 * FlowLayout that wraps onto multiple lines inside ScrollPanes and BoxLayout containers,
 * preventing clipped toolbars when the UI is scaled up on 43" / 4K displays.
 */
class StudioWrapLayout(
    align: Int = LEFT,
    val baseHgap: Int = 8,
    val baseVgap: Int = 4
) : FlowLayout(align, StudioTheme.s(baseHgap), StudioTheme.s(baseVgap)) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension {
        val min = layoutSize(target, preferred = false)
        min.width -= (hgap + 1)
        return min
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            var targetWidth = target.size.width
            var container: Container? = target
            while (container != null && targetWidth == 0) {
                targetWidth = container.size.width
                container = container.parent
            }
            if (targetWidth <= 0) {
                targetWidth = Int.MAX_VALUE
            }
            val insets = target.insets
            val maxWidth = targetWidth - (insets.left + insets.right + hgap * 2)
            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            val count = target.componentCount
            for (i in 0 until count) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    dim.width = max(dim.width, rowWidth)
                    if (dim.height > 0) dim.height += vgap
                    dim.height += rowHeight
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = max(rowHeight, d.height)
            }
            dim.width = max(dim.width, rowWidth)
            if (dim.height > 0) dim.height += vgap
            dim.height += rowHeight
            dim.width += insets.left + insets.right + hgap * 2
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }
}
