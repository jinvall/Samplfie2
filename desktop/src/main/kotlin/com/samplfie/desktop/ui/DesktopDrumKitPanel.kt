package com.samplfie.desktop.ui

import com.samplfie.desktop.drums.BeaterSide
import com.samplfie.desktop.drums.CustomDrumKitState
import com.samplfie.desktop.drums.DesktopDrumComponentLibrary
import com.samplfie.desktop.drums.DrumAddonType
import com.samplfie.desktop.drums.DrumKitPreset
import com.samplfie.desktop.drums.DrumKitStyle
import com.samplfie.desktop.drums.DrumPieceCategory
import com.samplfie.desktop.drums.MAX_MAIN_KIT_PIECES
import com.samplfie.desktop.drums.MountedAddonPiece
import com.samplfie.desktop.drums.PlacedKitPiece
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Point2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import kotlin.math.hypot
import kotlin.math.min

/**
 * Interactive Linux Desktop Arrangeable & Customizable Electric / Non-Electric Drum Kit Studio Panel.
 * 1:1 feature parity with Android:
 * - Select up to 7 main pieces + swap/add/remove from Acoustic & Electric component library
 * - Interactive 2D Java2D drum rug (click to hit in Play Mode, drag anywhere in Arrange Mode)
 * - Mount optional add-on percussion (Cowbell on Snare, Tambourine on Hi-Hat, Maraca on Double-Pedal L/R Beater, etc.)
 * - Route any piece or composite mounted hit to 16-Step Sequencer Lanes 1..4 or Deck Editor
 */
class DesktopDrumKitPanel(
    private val onSetStyle: (DrumKitStyle) -> Unit,
    private val onApplyPreset: (DrumKitPreset) -> Unit,
    private val onToggleArrangeMode: () -> Unit,
    private val onFlipLeftHanded: () -> Unit,
    private val onSelectSlot: (String?) -> Unit,
    private val onSetTargetBeater: (BeaterSide) -> Unit,
    private val onHitPiece: (String, BeaterSide) -> Unit,
    private val onHitAddon: (String) -> Unit,
    private val onMovePiece: (String, Float, Float) -> Unit,
    private val onAddPiece: (String) -> Unit,
    private val onSwapPiece: (String, String) -> Unit,
    private val onRemovePiece: (String) -> Unit,
    private val onUpdatePieceParams: (String, Float?, Float?, Float?, Boolean?) -> Unit,
    private val onAttachAddon: (DrumAddonType, String, BeaterSide) -> Unit,
    private val onRemoveAddon: (String) -> Unit,
    private val onToggleAddonCoupled: (String) -> Unit,
    private val onSetAddonStyle: (String, DrumKitStyle) -> Unit,
    private val onSetAddonBeaterSide: (String, BeaterSide) -> Unit,
    private val onRouteToSeqLane: (String, Int, BeaterSide) -> Unit,
    private val onSendToDeck: (String, BeaterSide) -> Unit
) : JPanel() {

    private var currentState = CustomDrumKitState()
    private var libraryFilter: DrumKitStyle? = null
    private var lastRenderedKey = ""
    private var suppressSliderEvents = false

    private val summaryLbl = StudioTheme.hardwareLabel("DRUM KIT STUDIO", StudioTheme.AmberHot, StudioTheme.MonoFont)
    private val countLbl = StudioTheme.hardwareLabel("7/7 MAIN", StudioTheme.EmeraldSignal, StudioTheme.TitleFont)
    private val acousticStyleBtn = StudioTheme.hardwareButton("NON-ELECTRIC (ACOUSTIC)", StudioTheme.AmberHot, filled = true) {
        onSetStyle(DrumKitStyle.ACOUSTIC)
    }
    private val electricStyleBtn = StudioTheme.hardwareButton("ELECTRIC (E-KIT)", StudioTheme.NeonCyan) {
        onSetStyle(DrumKitStyle.ELECTRIC)
    }
    private val arrangeBtn = StudioTheme.hardwareButton("ARRANGE STAGE", StudioTheme.EmeraldSignal) {
        onToggleArrangeMode()
    }
    private val flipBtn = StudioTheme.hardwareButton("FLIP L/R", StudioTheme.VioletMod) {
        onFlipLeftHanded()
    }

    private val stageCanvas = DrumStageJava2DCanvas()
    private val quickPadsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply { isOpaque = false }
    private val selectedInfoLbl = StudioTheme.hardwareLabel("NO PIECE SELECTED", StudioTheme.TextPrimary, StudioTheme.TitleFont)
    private val pitchSlider = StudioTheme.hardwareSlider(-12, 12, 0)
    private val decaySlider = StudioTheme.hardwareSlider(40, 200, 100)
    private val volSlider = StudioTheme.hardwareSlider(10, 120, 90)
    private val beaterTargetPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { isOpaque = false }
    private val targetLeftBtn = StudioTheme.hardwareButton("TARGET: LEFT BEATER", StudioTheme.EmeraldSignal, filled = true) {
        onSetTargetBeater(BeaterSide.LEFT)
    }
    private val targetRightBtn = StudioTheme.hardwareButton("TARGET: RIGHT BEATER", StudioTheme.VioletMod) {
        onSetTargetBeater(BeaterSide.RIGHT)
    }
    private val addonsListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val libraryListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        background = StudioTheme.BrushedPanel
        border = StudioTheme.panelBorder(StudioTheme.AmberHot)
        layout = BorderLayout(8, 8)

        // Top Bar
        val topBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            val headerRow = JPanel(BorderLayout(8, 4)).apply {
                isOpaque = false
                val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                    isOpaque = false
                    add(StudioTheme.hardwareLabel("ARRANGEABLE DRUM KIT STUDIO", StudioTheme.TextPrimary, StudioTheme.TitleFont))
                    add(countLbl)
                }
                add(left, BorderLayout.WEST)
                add(summaryLbl, BorderLayout.EAST)
            }
            val modeRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3)).apply {
                isOpaque = false
                add(acousticStyleBtn)
                add(electricStyleBtn)
                add(arrangeBtn)
                add(flipBtn)
                DrumKitPreset.entries.forEach { preset ->
                    val col = if (preset.style == DrumKitStyle.ACOUSTIC) StudioTheme.AmberHot else StudioTheme.NeonCyan
                    add(StudioTheme.hardwareButton(preset.label, col) { onApplyPreset(preset) })
                }
            }
            add(headerRow)
            add(modeRow)
        }
        add(topBar, BorderLayout.NORTH)

        // Center: Left = 2D Interactive Stage + Quick Pads; Right = Selected Inspector & Mount Add-Ons
        val centerSplit = JPanel(BorderLayout(10, 8)).apply { isOpaque = false }

        val stageColumn = JPanel(BorderLayout(6, 6)).apply {
            isOpaque = false
            add(stageCanvas, BorderLayout.CENTER)
            add(quickPadsPanel, BorderLayout.SOUTH)
        }
        centerSplit.add(stageColumn, BorderLayout.CENTER)

        // Right Inspector + Library
        val rightColumn = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            preferredSize = Dimension(430, 390)

            val inspectorBox = StudioTheme.hardwarePanel(StudioTheme.HairlineDivider).apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(selectedInfoLbl)
                add(Box.createVerticalStrut(4))

                val paramGrid = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                    isOpaque = false
                    add(StudioTheme.hardwareLabel("PITCH:", StudioTheme.TextSecondary))
                    pitchSlider.preferredSize = Dimension(95, 22)
                    pitchSlider.addChangeListener {
                        val sel = currentState.selectedPiece ?: return@addChangeListener
                        if (!suppressSliderEvents && !pitchSlider.valueIsAdjusting) {
                            onUpdatePieceParams(sel.slotId, pitchSlider.value.toFloat(), null, null, null)
                        }
                    }
                    add(pitchSlider)
                    add(StudioTheme.hardwareLabel("DECAY:", StudioTheme.TextSecondary))
                    decaySlider.preferredSize = Dimension(95, 22)
                    decaySlider.addChangeListener {
                        val sel = currentState.selectedPiece ?: return@addChangeListener
                        if (!suppressSliderEvents && !decaySlider.valueIsAdjusting) {
                            onUpdatePieceParams(sel.slotId, null, decaySlider.value / 100f, null, null)
                        }
                    }
                    add(decaySlider)
                    add(StudioTheme.hardwareLabel("VOL:", StudioTheme.TextSecondary))
                    volSlider.preferredSize = Dimension(85, 22)
                    volSlider.addChangeListener {
                        val sel = currentState.selectedPiece ?: return@addChangeListener
                        if (!suppressSliderEvents && !volSlider.valueIsAdjusting) {
                            onUpdatePieceParams(sel.slotId, null, null, volSlider.value / 100f, null)
                        }
                    }
                    add(volSlider)
                }
                add(paramGrid)

                val routeRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                    isOpaque = false
                    add(StudioTheme.hardwareLabel("SEQ:", StudioTheme.NeonCyan))
                    for (l in 0..3) {
                        add(StudioTheme.hardwareButton("L${l + 1}", StudioTheme.NeonCyan) {
                            val sel = currentState.selectedPiece ?: return@hardwareButton
                            val beater = if (sel.category.supportsDoubleBeater) currentState.targetBeaterForMount else BeaterSide.NONE
                            onRouteToSeqLane(sel.slotId, l, beater)
                        }.apply { preferredSize = Dimension(42, 26) })
                    }
                    add(StudioTheme.hardwareButton("DECK", StudioTheme.AmberHot) {
                        val sel = currentState.selectedPiece ?: return@hardwareButton
                        val beater = if (sel.category.supportsDoubleBeater) currentState.targetBeaterForMount else BeaterSide.NONE
                        onSendToDeck(sel.slotId, beater)
                    }.apply { preferredSize = Dimension(56, 26) })
                    add(StudioTheme.hardwareButton("REMOVE PIECE", StudioTheme.TacCoral) {
                        val sel = currentState.selectedPiece ?: return@hardwareButton
                        onRemovePiece(sel.slotId)
                    }.apply { preferredSize = Dimension(112, 26) })
                }
                add(routeRow)

                beaterTargetPanel.add(targetLeftBtn)
                beaterTargetPanel.add(targetRightBtn)
                add(beaterTargetPanel)

                val mountRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                    isOpaque = false
                    add(StudioTheme.hardwareLabel("MOUNT:", StudioTheme.MagentaFx))
                    DrumAddonType.entries.forEach { t ->
                        add(StudioTheme.hardwareButton("+${t.shortTag}", StudioTheme.MagentaFx) {
                            val sel = currentState.selectedPiece ?: return@hardwareButton
                            val beater = if (sel.category.supportsDoubleBeater) currentState.targetBeaterForMount else BeaterSide.NONE
                            onAttachAddon(t, sel.slotId, beater)
                        }.apply { preferredSize = Dimension(72, 26) })
                    }
                }
                add(mountRow)
                add(addonsListPanel)
            }
            add(inspectorBox)
            add(Box.createVerticalStrut(6))

            // Library filter + scrollable list
            val libBox = StudioTheme.hardwarePanel(StudioTheme.HairlineDivider).apply {
                layout = BorderLayout(4, 4)
                val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                    isOpaque = false
                    add(StudioTheme.hardwareLabel("LIBRARY:", StudioTheme.TextPrimary))
                    add(StudioTheme.hardwareButton("ALL", StudioTheme.TextPrimary) {
                        libraryFilter = null; lastRenderedKey = ""; updateState(currentState)
                    }.apply { preferredSize = Dimension(50, 24) })
                    add(StudioTheme.hardwareButton("ACOUSTIC", StudioTheme.AmberHot) {
                        libraryFilter = DrumKitStyle.ACOUSTIC; lastRenderedKey = ""; updateState(currentState)
                    }.apply { preferredSize = Dimension(82, 24) })
                    add(StudioTheme.hardwareButton("ELECTRIC", StudioTheme.NeonCyan) {
                        libraryFilter = DrumKitStyle.ELECTRIC; lastRenderedKey = ""; updateState(currentState)
                    }.apply { preferredSize = Dimension(82, 24) })
                }
                add(filterBar, BorderLayout.NORTH)
                add(JScrollPane(libraryListPanel).apply {
                    border = null
                    preferredSize = Dimension(410, 145)
                    viewport.background = StudioTheme.ObsidianChassis
                }, BorderLayout.CENTER)
            }
            add(libBox)
        }
        centerSplit.add(rightColumn, BorderLayout.EAST)
        add(centerSplit, BorderLayout.CENTER)
    }

    fun updateState(state: CustomDrumKitState) {
        currentState = state
        stageCanvas.repaint()

        countLbl.text = "${state.mainPieceCount}/$MAX_MAIN_KIT_PIECES MAIN"
        countLbl.foreground = if (state.canAddMainPiece) StudioTheme.EmeraldSignal else StudioTheme.AmberHot
        summaryLbl.text = state.statusError ?: state.lastHitSummary
        summaryLbl.foreground = if (state.statusError != null) StudioTheme.TacCoral else StudioTheme.AmberHot

        val sel = state.selectedPiece
        selectedInfoLbl.text = if (sel != null) {
            "SEL: ${sel.customLabel} (${sel.style.shortTag} · ${(sel.xNorm * 100).toInt()}%,${(sel.yNorm * 100).toInt()}%)"
        } else {
            "NO PIECE SELECTED — ADD FROM LIBRARY"
        }

        beaterTargetPanel.isVisible = sel?.category?.supportsDoubleBeater == true
        targetLeftBtn.foreground = if (state.targetBeaterForMount == BeaterSide.LEFT) StudioTheme.ObsidianChassis else StudioTheme.EmeraldSignal
        targetRightBtn.foreground = if (state.targetBeaterForMount == BeaterSide.RIGHT) StudioTheme.ObsidianChassis else StudioTheme.VioletMod

        if (sel != null) {
            suppressSliderEvents = true
            pitchSlider.value = sel.pitchSemitones.toInt().coerceIn(-12, 12)
            decaySlider.value = (sel.decayScale * 100).toInt().coerceIn(40, 200)
            volSlider.value = (sel.volume * 100).toInt().coerceIn(10, 120)
            suppressSliderEvents = false
        }

        val rebuildKey = buildString {
            append(state.kitStyle.name).append('|')
            append(state.arrangeMode).append('|')
            append(state.selectedSlotId).append('|')
            append(libraryFilter?.name ?: "ALL").append('|')
            state.mainPieces.forEach { append(it.slotId).append(':').append(it.componentId).append(',') }
            append('|')
            state.mountedAddons.forEach {
                append(it.instanceId).append(':').append(it.style.name).append(':')
                    .append(it.triggerCoupledWithHost).append(':').append(it.beaterSide.name).append(',')
            }
        }
        if (rebuildKey != lastRenderedKey) {
            lastRenderedKey = rebuildKey
            rebuildDynamicRows(state)
        }
    }

    private fun rebuildDynamicRows(state: CustomDrumKitState) {
        quickPadsPanel.removeAll()
        state.mainPieces.forEach { piece ->
            val col = if (piece.style == DrumKitStyle.ACOUSTIC) StudioTheme.AmberHot else StudioTheme.NeonCyan
            val isSel = piece.slotId == state.selectedPiece?.slotId
            val addons = state.mountedAddons.count { it.targetSlotId == piece.slotId }
            val txt = if (addons > 0) "${piece.customLabel}+$addons" else piece.customLabel
            quickPadsPanel.add(
                StudioTheme.hardwareButton(txt, col, filled = isSel) {
                    onSelectSlot(piece.slotId)
                    if (!state.arrangeMode) onHitPiece(piece.slotId, BeaterSide.NONE)
                }.apply { preferredSize = Dimension(96, 30) }
            )
        }
        val dbl = state.mainPieces.firstOrNull { it.category == DrumPieceCategory.DOUBLE_PEDAL }
        if (dbl != null) {
            quickPadsPanel.add(
                StudioTheme.hardwareButton("L-BEATER", StudioTheme.EmeraldSignal) {
                    onHitPiece(dbl.slotId, BeaterSide.LEFT)
                }.apply { preferredSize = Dimension(88, 30) }
            )
            quickPadsPanel.add(
                StudioTheme.hardwareButton("R-BEATER", StudioTheme.VioletMod) {
                    onHitPiece(dbl.slotId, BeaterSide.RIGHT)
                }.apply { preferredSize = Dimension(88, 30) }
            )
        }

        addonsListPanel.removeAll()
        state.mountedAddons.forEach { addon ->
            val host = state.mainPieces.firstOrNull { it.slotId == addon.targetSlotId }
            val bTag = if (host?.category?.supportsDoubleBeater == true && addon.beaterSide != BeaterSide.NONE) {
                " [${addon.beaterSide.shortTag}]"
            } else ""
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                isOpaque = false
                add(StudioTheme.hardwareLabel("${addon.addonType.shortTag}->$bTag${host?.customLabel ?: "?"}", StudioTheme.TextPrimary))
                add(StudioTheme.hardwareButton("HIT", StudioTheme.NeonCyan) { onHitAddon(addon.instanceId) }.apply { preferredSize = Dimension(46, 22) })
                add(StudioTheme.hardwareButton(if (addon.triggerCoupledWithHost) "COUPLED" else "SOLO", StudioTheme.EmeraldSignal, filled = addon.triggerCoupledWithHost) {
                    onToggleAddonCoupled(addon.instanceId)
                }.apply { preferredSize = Dimension(74, 22) })
                add(StudioTheme.hardwareButton(if (addon.style == DrumKitStyle.ACOUSTIC) "AC" else "EL", StudioTheme.AmberHot) {
                    onSetAddonStyle(addon.instanceId, if (addon.style == DrumKitStyle.ACOUSTIC) DrumKitStyle.ELECTRIC else DrumKitStyle.ACOUSTIC)
                }.apply { preferredSize = Dimension(38, 22) })
                if (host?.category?.supportsDoubleBeater == true) {
                    add(StudioTheme.hardwareButton(addon.beaterSide.shortTag, StudioTheme.VioletMod) {
                        onSetAddonBeaterSide(addon.instanceId, if (addon.beaterSide == BeaterSide.LEFT) BeaterSide.RIGHT else BeaterSide.LEFT)
                    }.apply { preferredSize = Dimension(56, 22) })
                }
                add(StudioTheme.hardwareButton("X", StudioTheme.TacCoral) { onRemoveAddon(addon.instanceId) }.apply { preferredSize = Dimension(32, 22) })
            }
            addonsListPanel.add(row)
        }

        libraryListPanel.removeAll()
        DesktopDrumComponentLibrary.forStyle(libraryFilter).forEach { comp ->
            val col = if (comp.style == DrumKitStyle.ACOUSTIC) StudioTheme.AmberHot else StudioTheme.NeonCyan
            val row = JPanel(BorderLayout(4, 2)).apply {
                background = StudioTheme.ObsidianChassis
                border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
                maximumSize = Dimension(Int.MAX_VALUE, 30)
                add(StudioTheme.hardwareLabel("${comp.shortLabel} (${comp.style.shortTag.take(2)}) · ${comp.displayName}", col, StudioTheme.BodyFont), BorderLayout.CENTER)
                val btns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    isOpaque = false
                    add(StudioTheme.hardwareButton(if (state.canAddMainPiece) "+ADD" else "FULL", StudioTheme.EmeraldSignal) {
                        onAddPiece(comp.id)
                    }.apply { preferredSize = Dimension(62, 24) })
                    if (state.selectedPiece != null) {
                        add(StudioTheme.hardwareButton("SWAP", col) {
                            val selId = state.selectedPiece?.slotId ?: return@hardwareButton
                            onSwapPiece(selId, comp.id)
                        }.apply { preferredSize = Dimension(62, 24) })
                    }
                }
                add(btns, BorderLayout.EAST)
            }
            libraryListPanel.add(row)
        }

        quickPadsPanel.revalidate(); quickPadsPanel.repaint()
        addonsListPanel.revalidate(); addonsListPanel.repaint()
        libraryListPanel.revalidate(); libraryListPanel.repaint()
    }

    private inner class DrumStageJava2DCanvas : JPanel() {
        private var draggingSlotId: String? = null

        init {
            background = StudioTheme.ObsidianChassis
            preferredSize = Dimension(520, 295)
            minimumSize = Dimension(380, 240)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val w = width.toFloat().coerceAtLeast(1f)
                    val h = height.toFloat().coerceAtLeast(1f)
                    val st = currentState
                    if (!st.arrangeMode) {
                        st.mountedAddons.forEach { addon ->
                            val host = st.mainPieces.firstOrNull { it.slotId == addon.targetSlotId } ?: return@forEach
                            val bc = computeAddonPoint(host, addon, w, h)
                            if (hypot(e.x - bc.x, e.y - bc.y) <= min(w, h) * 0.085f) {
                                onHitAddon(addon.instanceId)
                                return
                            }
                        }
                    }
                    val target = st.mainPieces.minByOrNull { p -> hypot(e.x - p.xNorm * w, e.y - p.yNorm * h) } ?: return
                    if (hypot(e.x - target.xNorm * w, e.y - target.yNorm * h) <= min(w, h) * 0.19f) {
                        onSelectSlot(target.slotId)
                        if (st.arrangeMode) {
                            draggingSlotId = target.slotId
                        } else {
                            val beater = if (target.category == DrumPieceCategory.DOUBLE_PEDAL) {
                                if ((e.x / w) < target.xNorm) BeaterSide.LEFT else BeaterSide.RIGHT
                            } else BeaterSide.NONE
                            onHitPiece(target.slotId, beater)
                        }
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    draggingSlotId = null
                }
            })

            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val slot = draggingSlotId ?: return
                    if (!currentState.arrangeMode) return
                    val w = width.toFloat().coerceAtLeast(1f)
                    val h = height.toFloat().coerceAtLeast(1f)
                    onMovePiece(slot, (e.x / w).coerceIn(0.08f, 0.92f), (e.y / h).coerceIn(0.12f, 0.88f))
                }
            })
        }

        private fun computeAddonPoint(host: PlacedKitPiece, addon: MountedAddonPiece, w: Float, h: Float): Point2D.Float {
            val hx = host.xNorm * w
            val hy = host.yNorm * h
            val unit = min(w, h)
            if (host.category == DrumPieceCategory.DOUBLE_PEDAL) {
                val dx = when (addon.beaterSide) {
                    BeaterSide.LEFT -> -unit * 0.085f
                    BeaterSide.RIGHT -> unit * 0.085f
                    BeaterSide.NONE -> 0f
                }
                return Point2D.Float(hx + dx, hy - unit * 0.095f)
            }
            return Point2D.Float(hx + unit * 0.075f, hy - unit * 0.072f)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val w = width.toFloat()
            val h = height.toFloat()
            val unit = min(w, h)

            // Circular drum rug rings
            g2.color = StudioTheme.withAlpha(StudioTheme.HairlineDivider, 90)
            g2.stroke = BasicStroke(1.5f)
            val r1 = (unit * 0.44f).toInt()
            g2.drawOval((w * 0.5f - r1).toInt(), (h * 0.55f - r1).toInt(), r1 * 2, r1 * 2)
            val r2 = (unit * 0.28f).toInt()
            g2.drawOval((w * 0.5f - r2).toInt(), (h * 0.55f - r2).toInt(), r2 * 2, r2 * 2)

            val st = currentState
            st.mainPieces.forEach { piece ->
                val cx = (piece.xNorm * w).toInt()
                val cy = (piece.yNorm * h).toInt()
                val isSel = piece.slotId == st.selectedSlotId
                val isHit = piece.slotId == st.lastHitSlotId
                val isAcoustic = piece.style == DrumKitStyle.ACOUSTIC
                val rad = when (piece.category) {
                    DrumPieceCategory.KICK, DrumPieceCategory.DOUBLE_PEDAL -> (unit * 0.125f).toInt()
                    DrumPieceCategory.RIDE, DrumPieceCategory.CRASH -> (unit * 0.115f).toInt()
                    DrumPieceCategory.SNARE, DrumPieceCategory.FLOOR_TOM -> (unit * 0.105f).toInt()
                    else -> (unit * 0.09f).toInt()
                }
                val isCymbal = piece.category == DrumPieceCategory.CRASH ||
                    piece.category == DrumPieceCategory.RIDE ||
                    piece.category == DrumPieceCategory.HIHAT ||
                    piece.category == DrumPieceCategory.FX_CYMBAL

                val fill = when {
                    piece.isMuted -> StudioTheme.withAlpha(StudioTheme.TextMuted, 50)
                    isAcoustic && isCymbal -> Color(0xD4, 0xA4, 0x3A, 95)
                    isAcoustic -> Color(0x9C, 0x54, 0x2A, 90)
                    else -> StudioTheme.withAlpha(StudioTheme.NeonCyan, 42)
                }
                val rim = when {
                    isHit -> StudioTheme.EmeraldSignal
                    isSel -> if (st.arrangeMode) StudioTheme.EmeraldSignal else Color.WHITE
                    isAcoustic -> StudioTheme.AmberHot
                    else -> StudioTheme.NeonCyan
                }

                g2.color = fill
                g2.fillOval(cx - rad, cy - rad, rad * 2, rad * 2)
                g2.color = rim
                g2.stroke = BasicStroke(if (isSel) 3f else 2f)
                g2.drawOval(cx - rad, cy - rad, rad * 2, rad * 2)

                if (piece.category == DrumPieceCategory.DOUBLE_PEDAL) {
                    val pw = (rad * 0.62f).toInt()
                    val ph = (rad * 0.92f).toInt()
                    val py = cy + (rad * 0.1f).toInt()
                    g2.color = StudioTheme.EmeraldSignal
                    g2.drawRoundRect(cx - (rad * 0.78f).toInt(), py, pw, ph, 6, 6)
                    g2.drawString("L", cx - (rad * 0.54f).toInt(), py + ph - 5)
                    g2.color = StudioTheme.VioletMod
                    g2.drawRoundRect(cx + (rad * 0.16f).toInt(), py, pw, ph, 6, 6)
                    g2.drawString("R", cx + (rad * 0.36f).toInt(), py + ph - 5)
                }

                g2.font = Font("SansSerif", Font.BOLD, 10)
                val fm = g2.fontMetrics
                g2.color = StudioTheme.TextPrimary
                g2.drawString(piece.customLabel, cx - fm.stringWidth(piece.customLabel) / 2, cy - 2)
            }

            // Draw mounted add-ons
            st.mountedAddons.forEach { addon ->
                val host = st.mainPieces.firstOrNull { it.slotId == addon.targetSlotId } ?: return@forEach
                val pt = computeAddonPoint(host, addon, w, h)
                val bx = pt.x.toInt()
                val by = pt.y.toInt()
                val br = (unit * 0.056f).toInt().coerceAtLeast(11)
                val col = when (addon.addonType) {
                    DrumAddonType.COWBELL -> StudioTheme.AmberHot
                    DrumAddonType.TAMBOURINE -> StudioTheme.NeonCyan
                    DrumAddonType.MARACA -> StudioTheme.EmeraldSignal
                    DrumAddonType.JAM_BLOCK -> StudioTheme.MagentaFx
                    DrumAddonType.CHIME_SPLASHER -> StudioTheme.VioletMod
                }
                g2.color = col
                g2.stroke = BasicStroke(2f)
                g2.drawLine((host.xNorm * w).toInt(), (host.yNorm * h).toInt(), bx, by)
                if (addon.addonType == DrumAddonType.COWBELL) {
                    val poly = Polygon(
                        intArrayOf(bx - (br * 0.6).toInt(), bx + (br * 0.6).toInt(), bx + (br * 0.85).toInt(), bx - (br * 0.85).toInt()),
                        intArrayOf(by - (br * 0.7).toInt(), by - (br * 0.7).toInt(), by + (br * 0.7).toInt(), by + (br * 0.7).toInt()),
                        4
                    )
                    g2.fillPolygon(poly)
                } else {
                    g2.fillOval(bx - br, by - br, br * 2, br * 2)
                }
                g2.color = StudioTheme.ObsidianChassis
                g2.font = Font("SansSerif", Font.BOLD, 8)
                val t = addon.addonType.shortTag.take(4)
                val fm = g2.fontMetrics
                g2.drawString(t, bx - fm.stringWidth(t) / 2, by + 3)
            }

            if (st.arrangeMode) {
                g2.color = StudioTheme.EmeraldSignal
                g2.font = Font("SansSerif", Font.BOLD, 11)
                g2.drawString("ARRANGE MODE ACTIVE: DRAG PIECES ANYWHERE ON RUG", 12, 18)
            }
            g2.dispose()
        }
    }
}
