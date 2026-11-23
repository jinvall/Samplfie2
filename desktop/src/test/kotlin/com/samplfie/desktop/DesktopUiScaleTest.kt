package com.samplfie.desktop

import com.samplfie.desktop.ui.FxAndStompPanel
import com.samplfie.desktop.ui.InstrumentsSeqPanel
import com.samplfie.desktop.ui.LiveStagePanel
import com.samplfie.desktop.ui.NetLinkPanel
import com.samplfie.desktop.ui.SamplesLooperPanel
import com.samplfie.desktop.ui.StudioTheme
import com.samplfie.desktop.ui.StudioWrapLayout
import com.samplfie.desktop.ui.TracksTimelinePanel
import com.samplfie.desktop.ui.VmcMidiPanel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.FlowLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class DesktopUiScaleTest {

    @After
    fun restoreScale() {
        StudioTheme.setUiScale(1.0f, persist = false, windows = emptyList())
    }

    @Test
    fun autoDetectScaleChoosesCrispHiDpiFactorFor43Inch4KMonitorWithoutDoubleScaling() {
        val uhd43 = StudioTheme.autoDetectDisplayScale(
            screenWidthPx = 3840,
            screenHeightPx = 2160,
            dpi = 96,
            hwScaleX = 1.0
        )
        assertEquals(2.0f, uhd43, 0.01f)

        val qhd = StudioTheme.autoDetectDisplayScale(
            screenWidthPx = 2560,
            screenHeightPx = 1440,
            dpi = 96,
            hwScaleX = 1.0
        )
        assertEquals(1.5f, qhd, 0.01f)

        // When OS/JVM already applies >= 1.5x transform, do not double scale
        val osScaled = StudioTheme.autoDetectDisplayScale(
            screenWidthPx = 3840,
            screenHeightPx = 2160,
            dpi = 192,
            hwScaleX = 2.0
        )
        assertEquals(1.0f, osScaled, 0.01f)
    }

    @Test
    fun setUiScaleScalesFontsStrokesButtonsAndRescalesSwingTree() {
        StudioTheme.setUiScale(1.0f, persist = false, windows = emptyList())
        val baseTitlePt = StudioTheme.TitleFont.size2D
        val baseMonoPt = StudioTheme.MonoFont.size2D
        val basePad = StudioTheme.s(12)
        val baseStroke = StudioTheme.stroke(2.0f).lineWidth

        val label = StudioTheme.hardwareLabel("MASTER", StudioTheme.NeonCyan, StudioTheme.TitleFont)
        val button = StudioTheme.hardwareButton("OPEN 64-CH VMC MAPPER", StudioTheme.NeonCyan, filled = true)
        val spinner = StudioTheme.styleSpinner(JSpinner(SpinnerNumberModel(1, 1, 64, 1)), 12f, 80, 32)
        val field = StudioTheme.styleTextField(JTextField("127.0.0.1"), 12f, 140, 32)
        val combo = StudioTheme.styleComboBox(JComboBox(arrayOf("44100", "48000")), 12f)
        val scroll = StudioTheme.styleScrollPane(JScrollPane())

        val btnPrefAt1x = button.preferredSize
        val spinnerPrefAt1x = spinner.preferredSize
        val fieldPrefAt1x = field.preferredSize

        val container = StudioTheme.wrapRowPanel(FlowLayout.LEFT, 8, 4).apply {
            add(label)
            add(button)
            add(spinner)
            add(field)
            add(combo)
            add(scroll)
        }

        StudioTheme.setUiScale(2.5f, persist = false, windows = emptyList())
        StudioTheme.rescaleComponentTree(container)

        assertEquals(2.5f, StudioTheme.uiScale, 0.01f)
        assertTrue(StudioTheme.TitleFont.size2D >= baseTitlePt * 2.4f)
        assertTrue(StudioTheme.MonoFont.size2D >= baseMonoPt * 2.4f)
        assertTrue(StudioTheme.s(12) >= basePad * 2)
        assertTrue(StudioTheme.stroke(2.0f).lineWidth >= baseStroke * 2.4f)

        assertTrue("Label font should scale live", label.font.size2D >= baseTitlePt * 2.4f)
        assertTrue("Button height should scale > 2x", button.preferredSize.height > btnPrefAt1x.height * 2)
        assertTrue("Button width should grow without truncating text", button.preferredSize.width > btnPrefAt1x.width * 2)
        assertTrue("Spinner pref size should scale", spinner.preferredSize.width > spinnerPrefAt1x.width * 2)
        assertTrue("TextField pref size should scale", field.preferredSize.width > fieldPrefAt1x.width * 2)
        assertTrue("Combo font should scale", combo.font.size2D >= baseMonoPt * 2.4f)
        assertTrue("ScrollBar increment should scale", scroll.verticalScrollBar.unitIncrement > 30)
    }

    @Test
    fun wrapLayoutWrapsMultiRowHeightWhenScaledToolbarExceedsWidth() {
        StudioTheme.setUiScale(2.0f, persist = false, windows = emptyList())
        val toolbar = JPanel(StudioWrapLayout(FlowLayout.LEFT, 8, 4)).apply {
            repeat(6) { idx ->
                add(StudioTheme.hardwareButton("STATION BUTTON #$idx", StudioTheme.NeonCyan))
            }
        }
        toolbar.setSize(420, 400)
        val singleRowH = toolbar.getComponent(0).preferredSize.height
        val wrappedDim = toolbar.preferredSize
        assertTrue(
            "Wrapped toolbar height (${wrappedDim.height}) should exceed single row ($singleRowH)",
            wrappedDim.height > singleRowH * 2
        )
    }

    @Test
    fun allDesktopStudioPanelsConstructAndRescaleCleanlyAt250Percent() {
        StudioTheme.setUiScale(2.5f, persist = false, windows = emptyList())

        val panels = listOf<JPanel>(
            SamplesLooperPanel(
                onToggleLooperPlay = {}, onBpmChanged = {}, onBounceLooperToDeck = {},
                onSendDeckToSlot = {}, onToggleSlotActive = {}, onToggleSlotMute = {},
                onCycleSlotBars = { _, _ -> }, onClearSlot = {}, onLoadSlotIntoRefiner = {},
                onRefinerSelection = { _, _ -> }, onSnapZeroCross = {}, onTrimLoop = {},
                onApplyFades = { _, _ -> }, onReverseLoop = {}, onNormalizeLoop = {},
                onRemoveDcLoop = {}, onResetLoop = {}, onAuditionLoop = {},
                onCommitRefiner = {}, onSendRefinerToDeck = {}
            ),
            InstrumentsSeqPanel(
                onToggleSeqPlay = {}, onTriggerPad = {}, onToggleStep = { _, _ -> },
                onToggleLaneMute = {}, onAssignDeckToLane = {}, onSetBpm = {}, onSetSwing = {},
                onBounceSeqToTimeline = {}, onRefreshMidi = {}, onOpenVmcMapper = {}
            ),
            TracksTimelinePanel(
                onToggleSyncAll = {}, onToggleTimelinePlay = {}, onSeekPlayheadSec = {},
                onSetBpm = {}, onAddDeckToTrack = {}, onAddLooperMixToTrack2 = {},
                onAddSeqBeatToTrack3 = {}, onMixdownToDeck = {}, onToggleMute = {},
                onToggleSolo = {}, onNudgeClip = { _, _, _ -> }, onRemoveClip = { _, _ -> }
            ),
            FxAndStompPanel(
                onApplyPreset = {}, onUpdateFx = {}, onUpdateStomp = {}, onToggleMasterStomp = {}
            ),
            LiveStagePanel(
                onSetLiveMode = {}, onToggleStompBypass = {}, onUpdateStomp = {},
                onXyDrag = { _, _ -> }, onTapTempo = {}, onToggleSyncedPlay = {},
                onExportWav = {}, onExportMp3 = {}, onPushHandoff = {}
            ),
            VmcMidiPanel(
                onSelectBank = {}, onSelectChannel64 = {}, onSetRouteMode = {},
                onEmitControl = { _, _, _, _ -> }, onArmLearnRule = {},
                onArmLearnNew = {}, onUpsertRule = {}, onRemoveRule = {},
                onRefreshHardwareMidi = {}
            ),
            NetLinkPanel(
                onUpdateConfig = {}, onToggleListener = {}, onCaptureRxToEditor = {},
                onTransmitActive = {}, onPushHandoff = {}, onGrabHandoff = {}
            )
        )

        panels.forEach { panel ->
            StudioTheme.rescaleComponentTree(panel)
            assertTrue(panel.componentCount > 0)
        }
    }
}
