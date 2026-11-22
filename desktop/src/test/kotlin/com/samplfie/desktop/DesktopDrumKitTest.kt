package com.samplfie.desktop

import com.samplfie.desktop.drums.BeaterSide
import com.samplfie.desktop.drums.DesktopDrumComponentLibrary
import com.samplfie.desktop.drums.DrumAddonType
import com.samplfie.desktop.drums.DrumKitPreset
import com.samplfie.desktop.drums.DrumKitStyle
import com.samplfie.desktop.drums.DrumPieceCategory
import com.samplfie.desktop.drums.MAX_MAIN_KIT_PIECES
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDrumKitTest {

    @Test
    fun `desktop enforces 7 piece cap, arranges coordinates, and switches electric vs acoustic`() {
        val controller = DesktopStudioController()
        try {
            controller.applyDrumKitPreset(DrumKitPreset.ELECTRIC_CLUB_5)
            assertEquals(5, controller.customDrumKitState.value.mainPieceCount)
            assertEquals(DrumKitStyle.ELECTRIC, controller.customDrumKitState.value.kitStyle)

            assertTrue(controller.addMainDrumPiece("china_el_cyber"))
            assertTrue(controller.addMainDrumPiece("ride_el_bell"))
            assertEquals(MAX_MAIN_KIT_PIECES, controller.customDrumKitState.value.mainPieceCount)
            // 8th rejected
            assertFalse(controller.addMainDrumPiece("tom_flr_el"))
            assertEquals(MAX_MAIN_KIT_PIECES, controller.customDrumKitState.value.mainPieceCount)

            val kick = controller.customDrumKitState.value.mainPieces.first { it.category == DrumPieceCategory.KICK }
            controller.moveDrumPieceOnStage(kick.slotId, 0.44f, 0.81f)

            // Convert whole kit to Non-Electric Acoustic
            controller.setDrumKitStyle(DrumKitStyle.ACOUSTIC, convertExisting = true)
            val movedKick = controller.customDrumKitState.value.mainPieces.first { it.slotId == kick.slotId }
            assertEquals(DrumKitStyle.ACOUSTIC, movedKick.style)
            assertEquals("kick_ac_maple", movedKick.componentId)
            assertEquals(0.44f, movedKick.xNorm, 0.001f)
            assertEquals(0.81f, movedKick.yNorm, 0.001f)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `desktop mounts cowbell on snare, tambourine on hihat, maraca on double pedal L beater and routes to seq lane`() {
        val controller = DesktopStudioController()
        try {
            controller.applyDrumKitPreset(DrumKitPreset.ACOUSTIC_ARENA_7)
            val st = controller.customDrumKitState.value
            assertEquals(7, st.mainPieceCount)

            val dbl = st.mainPieces.first { it.category == DrumPieceCategory.DOUBLE_PEDAL }
            val leftHit = controller.drumKitManager.synthesizePieceHit(dbl.slotId, BeaterSide.LEFT)!!
            val rightHit = controller.drumKitManager.synthesizePieceHit(dbl.slotId, BeaterSide.RIGHT)!!
            assertTrue(leftHit.name.contains("MARACA"))
            assertFalse(rightHit.name.contains("MARACA"))

            val snare = st.mainPieces.first { it.category == DrumPieceCategory.SNARE }
            controller.sendDrumPieceToSeqLane(snare.slotId, 2)
            val lane2 = controller.sequencerState.value.lanes[2]
            assertTrue(lane2.name.contains("SNARE"))
            assertTrue(lane2.buffer.samples.maxOf { abs(it) } > 0.1f)

            // Attach additional Jam Block onto Floor Tom
            val floor = st.mainPieces.first { it.category == DrumPieceCategory.FLOOR_TOM }
            val mountedBlock = controller.attachDrumAddon(DrumAddonType.JAM_BLOCK, floor.slotId)
            assertNotNull(mountedBlock)
            assertEquals(11, DesktopDrumComponentLibrary.forStyle(DrumKitStyle.ACOUSTIC).size)
            assertEquals(11, DesktopDrumComponentLibrary.forStyle(DrumKitStyle.ELECTRIC).size)
        } finally {
            controller.shutdown()
        }
    }
}
