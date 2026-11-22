package com.example.drums

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomDrumKitEngineTest {

    @Test
    fun `enforces max 7 main kit pieces and allows swapping or removing`() {
        val manager = DrumKitManager()
        manager.applyPreset(DrumKitPreset.ACOUSTIC_CLUB_5)
        assertEquals(5, manager.state.value.mainPieceCount)
        assertTrue(manager.state.value.canAddMainPiece)

        val piece6 = manager.addMainPiece("tom_flr_ac", 0.72f, 0.62f)
        val piece7 = manager.addMainPiece("crash_ac_dark", 0.25f, 0.24f)
        assertNotNull(piece6)
        assertNotNull(piece7)
        assertEquals(7, manager.state.value.mainPieceCount)
        assertFalse(manager.state.value.canAddMainPiece)

        // 8th main piece must be rejected cleanly with clear limit message
        val piece8 = manager.addMainPiece("china_ac_trash")
        assertNull(piece8)
        assertEquals(7, manager.state.value.mainPieceCount)
        assertTrue(manager.state.value.statusError?.contains("7") == true)

        // Swapping an existing slot works even when 7/7 full
        assertTrue(manager.swapMainPiece(piece7!!.slotId, "china_el_cyber"))
        assertEquals(7, manager.state.value.mainPieceCount)
        assertEquals("china_el_cyber", manager.state.value.mainPieces.last().componentId)

        // Removing reduces below 7 so another piece can be added
        manager.removeMainPiece(piece6!!.slotId)
        assertEquals(6, manager.state.value.mainPieceCount)
        assertTrue(manager.state.value.canAddMainPiece)
    }

    @Test
    fun `double pedal L and R beaters trigger coupled beater-specific maraca independently`() {
        val manager = DrumKitManager()
        manager.applyPreset(DrumKitPreset.ACOUSTIC_CLUB_5)
        // Swap single kick -> Double Pedal
        val kickSlot = manager.state.value.mainPieces.first { it.category == DrumPieceCategory.KICK }
        assertTrue(manager.swapMainPiece(kickSlot.slotId, "dkick_ac_twin"))

        // Mount Maraca onto LEFT beater only
        val mounted = manager.attachAddon(DrumAddonType.MARACA, kickSlot.slotId, BeaterSide.LEFT)
        assertNotNull(mounted)
        assertEquals(BeaterSide.LEFT, mounted!!.beaterSide)

        // Hit RIGHT beater (no maraca attached) vs LEFT beater (has coupled maraca)
        val rightHit = manager.synthesizePieceHit(kickSlot.slotId, BeaterSide.RIGHT)!!
        val leftHit = manager.synthesizePieceHit(kickSlot.slotId, BeaterSide.LEFT)!!

        assertFalse(rightHit.name.contains("MARACA"))
        assertTrue(leftHit.name.contains("MARACA"))

        // High-frequency energy in tail of leftHit is noticeably higher due to Maraca shaker
        val tailEnergyLeft = leftHit.samples.drop(leftHit.samples.size / 4).sumOf { abs(it.toDouble()) }
        val tailEnergyRight = rightHit.samples.drop(rightHit.samples.size / 4).sumOf { abs(it.toDouble()) }
        assertTrue("Left beater + maraca should differ acoustically from right beater alone", abs(tailEnergyLeft - tailEnergyRight) > 0.05)

        // Independent add-on hit works directly too
        val maracaSolo = manager.synthesizeAddonHit(mounted.instanceId)!!
        assertTrue(maracaSolo.frameCount > 0)
        assertTrue(maracaSolo.samples.maxOf { abs(it) } > 0.05f)
    }

    @Test
    fun `cowbell on snare and tambourine on hihat couple cleanly and respect style switching`() {
        val manager = DrumKitManager()
        manager.applyPreset(DrumKitPreset.ACOUSTIC_ARENA_7)
        val st = manager.state.value
        val snare = st.mainPieces.first { it.category == DrumPieceCategory.SNARE }
        val hihat = st.mainPieces.first { it.category == DrumPieceCategory.HIHAT }

        // Move snare cleanly on custom 2D stage before switching kit style
        manager.movePieceOnStage(snare.slotId, 0.28f, 0.69f)
        val snareAcousticHit = manager.synthesizePieceHit(snare.slotId)!!
        val hatAcousticHit = manager.synthesizePieceHit(hihat.slotId)!!
        assertTrue(snareAcousticHit.name.contains("COWBELL"))
        assertTrue(hatAcousticHit.name.contains("TAMB"))

        // Switch overall kit to ELECTRIC — pieces convert to Electronic counterparts & KEEP custom stage arrangement
        manager.setKitStyle(DrumKitStyle.ELECTRIC, convertExistingPieces = true)
        val updated = manager.state.value
        assertEquals(DrumKitStyle.ELECTRIC, updated.kitStyle)
        val updatedSnare = updated.mainPieces.first { it.slotId == snare.slotId }
        assertEquals(DrumKitStyle.ELECTRIC, updatedSnare.style)
        assertEquals("snare_el_909", updatedSnare.componentId)
        assertEquals(0.28f, updatedSnare.xNorm, 0.001f)
        assertEquals(0.69f, updatedSnare.yNorm, 0.001f)

        val snareElectricHit = manager.synthesizePieceHit(snare.slotId)!!
        assertTrue(snareElectricHit.samples.maxOf { abs(it) } > 0.1f)
        // Acoustic and Electric composite waveforms differ clearly
        assertFalse(snareAcousticHit.samples.contentEquals(snareElectricHit.samples))
    }

    @Test
    fun `flip left handed mirrors stage x positions and swaps beater sides`() {
        val manager = DrumKitManager()
        manager.applyPreset(DrumKitPreset.ACOUSTIC_ARENA_7)
        val origHiHatX = manager.state.value.mainPieces.first { it.category == DrumPieceCategory.HIHAT }.xNorm
        val origMaracaSide = manager.state.value.mountedAddons.first { it.addonType == DrumAddonType.MARACA }.beaterSide
        assertEquals(BeaterSide.LEFT, origMaracaSide)

        manager.flipLeftHanded()
        val mirrored = manager.state.value
        assertTrue(mirrored.isLeftHanded)
        val mirroredHiHatX = mirrored.mainPieces.first { it.category == DrumPieceCategory.HIHAT }.xNorm
        assertEquals(1f - origHiHatX, mirroredHiHatX, 0.001f)
        val flippedSide = mirrored.mountedAddons.first { it.addonType == DrumAddonType.MARACA }.beaterSide
        assertEquals(BeaterSide.RIGHT, flippedSide)
    }

    @Test
    fun `library includes full acoustic and electric variants for all component roles`() {
        val acoustic = DrumComponentLibrary.forStyle(DrumKitStyle.ACOUSTIC)
        val electric = DrumComponentLibrary.forStyle(DrumKitStyle.ELECTRIC)
        assertEquals(11, acoustic.size)
        assertEquals(11, electric.size)
        assertTrue(acoustic.any { it.category == DrumPieceCategory.DOUBLE_PEDAL })
        assertTrue(electric.any { it.category == DrumPieceCategory.DOUBLE_PEDAL })
    }
}
