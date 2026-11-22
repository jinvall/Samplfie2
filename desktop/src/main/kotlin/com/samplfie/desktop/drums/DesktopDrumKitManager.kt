package com.samplfie.desktop.drums

import com.samplfie.desktop.model.DesktopAudioBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the Linux Desktop totally arrangeable & customizable Electric / Non-Electric Drum Kit
 * with strict up-to-7 main piece cap and optional mounted add-on percussion pieces.
 */
class DesktopDrumKitManager {

    private val slotSeq = AtomicInteger(100)
    private val addonSeq = AtomicInteger(100)

    private val _state = MutableStateFlow(buildPresetState(DrumKitPreset.ACOUSTIC_ARENA_7))
    val state: StateFlow<CustomDrumKitState> = _state.asStateFlow()

    fun setKitStyle(style: DrumKitStyle, convertExistingPieces: Boolean = true) {
        val cur = _state.value
        val nextPieces = if (convertExistingPieces) {
            cur.mainPieces.map { piece ->
                val targetComp = DesktopDrumComponentLibrary.counterpartForStyle(piece.componentId, style)
                if (targetComp != null) {
                    piece.copy(
                        componentId = targetComp.id,
                        customLabel = targetComp.shortLabel,
                        style = style
                    )
                } else piece.copy(style = style)
            }
        } else cur.mainPieces
        val nextAddons = if (convertExistingPieces) {
            cur.mountedAddons.map { it.copy(style = style) }
        } else cur.mountedAddons

        _state.value = cur.copy(
            kitStyle = style,
            mainPieces = nextPieces,
            mountedAddons = nextAddons,
            lastHitSummary = "KIT STYLE -> ${style.label.uppercase()}",
            statusError = null
        )
    }

    fun applyPreset(preset: DrumKitPreset) {
        _state.value = buildPresetState(preset)
    }

    fun toggleArrangeMode() {
        val cur = _state.value
        val next = !cur.arrangeMode
        _state.value = cur.copy(
            arrangeMode = next,
            lastHitSummary = if (next) "ARRANGE MODE: DRAG PIECES ANYWHERE ON STAGE" else "PLAY MODE: CLICK PIECES, BEATERS & ADD-ONS",
            statusError = null
        )
    }

    fun flipLeftHanded() {
        val cur = _state.value
        val mirrored = cur.mainPieces.map { p -> p.copy(xNorm = (1f - p.xNorm).coerceIn(0.08f, 0.92f)) }
        val flippedAddons = cur.mountedAddons.map { a ->
            val flippedSide = when (a.beaterSide) {
                BeaterSide.LEFT -> BeaterSide.RIGHT
                BeaterSide.RIGHT -> BeaterSide.LEFT
                BeaterSide.NONE -> BeaterSide.NONE
            }
            a.copy(beaterSide = flippedSide)
        }
        val nextHanded = !cur.isLeftHanded
        _state.value = cur.copy(
            mainPieces = mirrored,
            mountedAddons = flippedAddons,
            isLeftHanded = nextHanded,
            lastHitSummary = if (nextHanded) "MIRRORED KIT -> LEFT-HANDED SETUP" else "RESTORED RIGHT-HANDED SETUP",
            statusError = null
        )
    }

    fun selectSlot(slotId: String?) {
        val cur = _state.value
        if (slotId != null && cur.mainPieces.none { it.slotId == slotId }) return
        _state.value = cur.copy(selectedSlotId = slotId, statusError = null)
    }

    fun setTargetBeaterForMount(side: BeaterSide) {
        val clean = if (side == BeaterSide.NONE) BeaterSide.LEFT else side
        _state.value = _state.value.copy(targetBeaterForMount = clean)
    }

    fun addMainPiece(componentId: String, xNorm: Float? = null, yNorm: Float? = null): PlacedKitPiece? {
        val cur = _state.value
        if (cur.mainPieces.size >= MAX_MAIN_KIT_PIECES) {
            _state.value = cur.copy(
                statusError = "LIMIT REACHED: $MAX_MAIN_KIT_PIECES/$MAX_MAIN_KIT_PIECES MAIN PIECES — REMOVE OR SWAP A PIECE FIRST",
                lastHitSummary = "KIT FULL (7/7 MAIN PIECES) — CLICK SWAP OR REMOVE"
            )
            return null
        }
        val comp = DesktopDrumComponentLibrary.byId(componentId) ?: return null
        val offsetIndex = cur.mainPieces.size
        val defaultX = ((xNorm ?: comp.defaultXNorm) + (if (xNorm == null && cur.mainPieces.any { abs(it.xNorm - comp.defaultXNorm) < 0.06f }) 0.07f * ((offsetIndex % 3) - 1) else 0f))
            .coerceIn(0.08f, 0.92f)
        val defaultY = (yNorm ?: comp.defaultYNorm).coerceIn(0.12f, 0.88f)
        val piece = PlacedKitPiece(
            slotId = "slot_${slotSeq.incrementAndGet()}",
            componentId = comp.id,
            customLabel = comp.shortLabel,
            style = comp.style,
            category = comp.category,
            xNorm = defaultX,
            yNorm = defaultY,
            pitchSemitones = comp.defaultPitchSemis,
            decayScale = comp.defaultDecayScale,
            midiNote = comp.defaultMidiNote
        )
        _state.value = cur.copy(
            mainPieces = cur.mainPieces + piece,
            selectedSlotId = piece.slotId,
            lastHitSummary = "ADDED ${piece.customLabel} (${cur.mainPieces.size + 1}/$MAX_MAIN_KIT_PIECES)",
            statusError = null
        )
        return piece
    }

    fun swapMainPiece(slotId: String, newComponentId: String): Boolean {
        val cur = _state.value
        val comp = DesktopDrumComponentLibrary.byId(newComponentId) ?: return false
        var swapped = false
        val updated = cur.mainPieces.map { p ->
            if (p.slotId == slotId) {
                swapped = true
                p.copy(
                    componentId = comp.id,
                    customLabel = comp.shortLabel,
                    style = comp.style,
                    category = comp.category,
                    midiNote = comp.defaultMidiNote
                )
            } else p
        }
        if (!swapped) return false
        val updatedAddons = cur.mountedAddons.map { a ->
            if (a.targetSlotId == slotId && !comp.category.supportsDoubleBeater && a.beaterSide != BeaterSide.NONE) {
                a.copy(beaterSide = BeaterSide.NONE)
            } else a
        }
        _state.value = cur.copy(
            mainPieces = updated,
            mountedAddons = updatedAddons,
            selectedSlotId = slotId,
            lastHitSummary = "SWAPPED -> ${comp.displayName}",
            statusError = null
        )
        return true
    }

    fun removeMainPiece(slotId: String) {
        val cur = _state.value
        val remaining = cur.mainPieces.filterNot { it.slotId == slotId }
        val remainingAddons = cur.mountedAddons.filterNot { it.targetSlotId == slotId }
        val nextSel = if (cur.selectedSlotId == slotId) remaining.firstOrNull()?.slotId else cur.selectedSlotId
        _state.value = cur.copy(
            mainPieces = remaining,
            mountedAddons = remainingAddons,
            selectedSlotId = nextSel,
            lastHitSummary = "REMOVED PIECE (${remaining.size}/$MAX_MAIN_KIT_PIECES MAIN)",
            statusError = null
        )
    }

    fun movePieceOnStage(slotId: String, xNorm: Float, yNorm: Float) {
        val cur = _state.value
        val cx = xNorm.coerceIn(0.08f, 0.92f)
        val cy = yNorm.coerceIn(0.12f, 0.88f)
        _state.value = cur.copy(
            mainPieces = cur.mainPieces.map { if (it.slotId == slotId) it.copy(xNorm = cx, yNorm = cy) else it }
        )
    }

    fun updatePieceParams(
        slotId: String,
        pitchSemitones: Float? = null,
        decayScale: Float? = null,
        volume: Float? = null,
        isMuted: Boolean? = null
    ) {
        val cur = _state.value
        _state.value = cur.copy(
            mainPieces = cur.mainPieces.map { p ->
                if (p.slotId == slotId) {
                    p.copy(
                        pitchSemitones = (pitchSemitones ?: p.pitchSemitones).coerceIn(-12f, 12f),
                        decayScale = (decayScale ?: p.decayScale).coerceIn(0.35f, 2.2f),
                        volume = (volume ?: p.volume).coerceIn(0f, 1.2f),
                        isMuted = isMuted ?: p.isMuted
                    )
                } else p
            }
        )
    }

    fun attachAddon(
        addonType: DrumAddonType,
        targetSlotId: String,
        beaterSide: BeaterSide = _state.value.targetBeaterForMount,
        styleOverride: DrumKitStyle? = null
    ): MountedAddonPiece? {
        val cur = _state.value
        val host = cur.mainPieces.firstOrNull { it.slotId == targetSlotId } ?: return null
        val resolvedSide = if (host.category.supportsDoubleBeater) {
            if (beaterSide == BeaterSide.NONE) BeaterSide.LEFT else beaterSide
        } else BeaterSide.NONE
        val mounted = MountedAddonPiece(
            instanceId = "addon_${addonSeq.incrementAndGet()}",
            addonType = addonType,
            style = styleOverride ?: cur.kitStyle,
            targetSlotId = targetSlotId,
            beaterSide = resolvedSide,
            triggerCoupledWithHost = true
        )
        val hostTag = if (resolvedSide != BeaterSide.NONE) "${host.customLabel} (${resolvedSide.shortTag})" else host.customLabel
        _state.value = cur.copy(
            mountedAddons = cur.mountedAddons + mounted,
            lastHitSummary = "MOUNTED ${addonType.label.uppercase()} ON $hostTag",
            statusError = null
        )
        return mounted
    }

    fun removeMountedAddon(instanceId: String) {
        val cur = _state.value
        _state.value = cur.copy(
            mountedAddons = cur.mountedAddons.filterNot { it.instanceId == instanceId },
            lastHitSummary = "UNMOUNTED ADD-ON PERCUSSION",
            statusError = null
        )
    }

    fun toggleAddonCoupled(instanceId: String) {
        val cur = _state.value
        _state.value = cur.copy(
            mountedAddons = cur.mountedAddons.map {
                if (it.instanceId == instanceId) it.copy(triggerCoupledWithHost = !it.triggerCoupledWithHost) else it
            }
        )
    }

    fun setAddonStyle(instanceId: String, style: DrumKitStyle) {
        val cur = _state.value
        _state.value = cur.copy(
            mountedAddons = cur.mountedAddons.map { if (it.instanceId == instanceId) it.copy(style = style) else it }
        )
    }

    fun setAddonBeaterSide(instanceId: String, side: BeaterSide) {
        val cur = _state.value
        _state.value = cur.copy(
            mountedAddons = cur.mountedAddons.map { if (it.instanceId == instanceId) it.copy(beaterSide = side) else it }
        )
    }

    fun synthesizePieceHit(slotId: String, beaterSide: BeaterSide = BeaterSide.NONE, sampleRate: Int = 44100): DesktopAudioBuffer? {
        val cur = _state.value
        val piece = cur.mainPieces.firstOrNull { it.slotId == slotId } ?: return null
        val effectiveBeater = if (piece.category.supportsDoubleBeater && beaterSide == BeaterSide.NONE) BeaterSide.LEFT else beaterSide
        val buf = DesktopDrumKitSynthesizerLibrary.synthesizeHitComposite(piece, cur.mountedAddons, effectiveBeater, sampleRate)
        _state.value = cur.copy(
            selectedSlotId = slotId,
            lastHitSlotId = slotId,
            lastHitBeater = effectiveBeater,
            lastHitAddonId = null,
            lastHitSummary = "HIT ${buf.name}",
            statusError = null
        )
        return buf
    }

    fun synthesizeAddonHit(instanceId: String, sampleRate: Int = 44100): DesktopAudioBuffer? {
        val cur = _state.value
        val addon = cur.mountedAddons.firstOrNull { it.instanceId == instanceId } ?: return null
        val buf = DesktopDrumKitSynthesizerLibrary.synthesizeAddon(addon, sampleRate)
        _state.value = cur.copy(
            lastHitSlotId = addon.targetSlotId,
            lastHitBeater = addon.beaterSide,
            lastHitAddonId = instanceId,
            lastHitSummary = "HIT ADD-ON: ${addon.style.shortTag} ${addon.addonType.label.uppercase()}",
            statusError = null
        )
        return buf
    }

    private fun buildPresetState(preset: DrumKitPreset): CustomDrumKitState {
        return when (preset) {
            DrumKitPreset.ACOUSTIC_CLUB_5 -> CustomDrumKitState(
                kitStyle = DrumKitStyle.ACOUSTIC,
                activePresetId = preset.id,
                mainPieces = listOf(
                    place("ac_c_kick", "kick_ac_maple", 0.50f, 0.74f),
                    place("ac_c_snare", "snare_ac_maple", 0.35f, 0.60f),
                    place("ac_c_hat", "hat_ac_bronze", 0.19f, 0.45f),
                    place("ac_c_tom", "tom_hi_ac", 0.46f, 0.41f),
                    place("ac_c_ride", "ride_ac_ping", 0.73f, 0.27f)
                ),
                mountedAddons = listOf(
                    MountedAddonPiece("addon_c_cow", DrumAddonType.COWBELL, DrumKitStyle.ACOUSTIC, "ac_c_snare")
                ),
                selectedSlotId = "ac_c_snare",
                lastHitSummary = "LOADED 5-PC ACOUSTIC CLUB KIT (5/7 MAIN + COWBELL ON SNARE)"
            )
            DrumKitPreset.ACOUSTIC_ARENA_7 -> CustomDrumKitState(
                kitStyle = DrumKitStyle.ACOUSTIC,
                activePresetId = preset.id,
                mainPieces = listOf(
                    place("ac_7_dkick", "dkick_ac_twin", 0.50f, 0.76f),
                    place("ac_7_snare", "snare_ac_maple", 0.34f, 0.62f),
                    place("ac_7_hat", "hat_ac_bronze", 0.17f, 0.47f),
                    place("ac_7_hi_tom", "tom_hi_ac", 0.41f, 0.41f),
                    place("ac_7_flr_tom", "tom_flr_ac", 0.71f, 0.62f),
                    place("ac_7_crash", "crash_ac_dark", 0.27f, 0.22f),
                    place("ac_7_ride", "ride_ac_ping", 0.75f, 0.24f)
                ),
                mountedAddons = listOf(
                    MountedAddonPiece("addon_ac_cow", DrumAddonType.COWBELL, DrumKitStyle.ACOUSTIC, "ac_7_snare", BeaterSide.NONE, true),
                    MountedAddonPiece("addon_ac_tamb", DrumAddonType.TAMBOURINE, DrumKitStyle.ACOUSTIC, "ac_7_hat", BeaterSide.NONE, true),
                    MountedAddonPiece("addon_ac_maraca", DrumAddonType.MARACA, DrumKitStyle.ACOUSTIC, "ac_7_dkick", BeaterSide.LEFT, true)
                ),
                selectedSlotId = "ac_7_dkick",
                lastHitSummary = "LOADED 7-PC ACOUSTIC DBL-PEDAL + COWBELL/TAMB/BEATER MARACA"
            )
            DrumKitPreset.ELECTRIC_CLUB_5 -> CustomDrumKitState(
                kitStyle = DrumKitStyle.ELECTRIC,
                activePresetId = preset.id,
                mainPieces = listOf(
                    place("el_c_kick", "kick_el_808", 0.50f, 0.74f),
                    place("el_c_snare", "snare_el_909", 0.35f, 0.60f),
                    place("el_c_hat", "hat_el_crisp", 0.19f, 0.45f),
                    place("el_c_tom", "tom_hi_el", 0.46f, 0.41f),
                    place("el_c_crash", "crash_el_synth", 0.73f, 0.27f)
                ),
                mountedAddons = listOf(
                    MountedAddonPiece("addon_el_tamb", DrumAddonType.TAMBOURINE, DrumKitStyle.ELECTRIC, "el_c_hat")
                ),
                selectedSlotId = "el_c_snare",
                lastHitSummary = "LOADED 5-PC 808/909 ELECTRO KIT (5/7 MAIN + SYNTH TAMB ON E-HAT)"
            )
            DrumKitPreset.ELECTRIC_CYBER_7 -> CustomDrumKitState(
                kitStyle = DrumKitStyle.ELECTRIC,
                activePresetId = preset.id,
                mainPieces = listOf(
                    place("el_7_dkick", "dkick_el_909", 0.50f, 0.76f),
                    place("el_7_snare", "snare_el_909", 0.34f, 0.62f),
                    place("el_7_hat", "hat_el_crisp", 0.17f, 0.47f),
                    place("el_7_hi_tom", "tom_hi_el", 0.41f, 0.41f),
                    place("el_7_flr_tom", "tom_flr_el", 0.71f, 0.62f),
                    place("el_7_crash", "crash_el_synth", 0.27f, 0.22f),
                    place("el_7_ride", "ride_el_bell", 0.75f, 0.24f)
                ),
                mountedAddons = listOf(
                    MountedAddonPiece("addon_el_cow", DrumAddonType.COWBELL, DrumKitStyle.ELECTRIC, "el_7_snare", BeaterSide.NONE, true),
                    MountedAddonPiece("addon_el_tamb", DrumAddonType.TAMBOURINE, DrumKitStyle.ELECTRIC, "el_7_hat", BeaterSide.NONE, true),
                    MountedAddonPiece("addon_el_maraca", DrumAddonType.MARACA, DrumKitStyle.ELECTRIC, "el_7_dkick", BeaterSide.RIGHT, true)
                ),
                selectedSlotId = "el_7_dkick",
                lastHitSummary = "LOADED 7-PC CYBER E-KIT + 808 COWBELL/E-TAMB/R-BEATER MARACA"
            )
        }
    }

    private fun place(slotId: String, compId: String, x: Float, y: Float): PlacedKitPiece {
        val c = DesktopDrumComponentLibrary.byId(compId)!!
        return PlacedKitPiece(
            slotId = slotId,
            componentId = c.id,
            customLabel = c.shortLabel,
            style = c.style,
            category = c.category,
            xNorm = x,
            yNorm = y,
            pitchSemitones = c.defaultPitchSemis,
            decayScale = c.defaultDecayScale,
            midiNote = c.defaultMidiNote
        )
    }
}
