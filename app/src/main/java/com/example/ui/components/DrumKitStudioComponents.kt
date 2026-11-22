package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.drums.BeaterSide
import com.example.drums.CustomDrumKitState
import com.example.drums.DrumAddonType
import com.example.drums.DrumComponentLibrary
import com.example.drums.DrumKitPreset
import com.example.drums.DrumKitStyle
import com.example.drums.DrumPieceCategory
import com.example.drums.MAX_MAIN_KIT_PIECES
import com.example.drums.MountedAddonPiece
import com.example.drums.PlacedKitPiece
import com.example.ui.theme.AmberHot
import com.example.ui.theme.BrushedPanel
import com.example.ui.theme.ElevatedBezel
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.MagentaFx
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod
import kotlin.math.hypot
import kotlin.math.min

/**
 * Interactive Electric & Non-Electric Arrangeable Drum Kit Studio:
 * - Supports selecting & customizing up to 7 main kit pieces
 * - Drag-to-arrange 2D drum rug coordinates + left/right-handed mirror
 * - Mounts optional add-ons onto kit hosts (Cowbell on Snare, Tambourine on Hi-Hat,
 *   Maraca on Double-Pedal L/R Beater, Jam Block, Bar Chime)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrumKitStudioSection(
    kitState: CustomDrumKitState,
    onSetStyle: (DrumKitStyle) -> Unit,
    onApplyPreset: (DrumKitPreset) -> Unit,
    onToggleArrangeMode: () -> Unit,
    onFlipLeftHanded: () -> Unit,
    onSelectSlot: (String?) -> Unit,
    onSetTargetBeater: (BeaterSide) -> Unit,
    onHitPiece: (String, BeaterSide) -> Unit,
    onHitAddon: (String) -> Unit,
    onMovePiece: (String, Float, Float) -> Unit,
    onAddPiece: (String) -> Unit,
    onSwapPiece: (String, String) -> Unit,
    onRemovePiece: (String) -> Unit,
    onUpdatePieceParams: (String, Float?, Float?, Float?, Boolean?) -> Unit,
    onAttachAddon: (DrumAddonType, String, BeaterSide) -> Unit,
    onRemoveAddon: (String) -> Unit,
    onToggleAddonCoupled: (String) -> Unit,
    onSetAddonStyle: (String, DrumKitStyle) -> Unit,
    onSetAddonBeaterSide: (String, BeaterSide) -> Unit,
    onRoutePieceToSeqLane: (String, Int, BeaterSide) -> Unit,
    onSendPieceToDeck: (String, BeaterSide) -> Unit,
    modifier: Modifier = Modifier
) {
    var libraryFilter by remember { mutableStateOf<DrumKitStyle?>(null) }
    var showLibrary by remember { mutableStateOf(false) }

    val selectedPiece = kitState.selectedPiece
    val accent = if (kitState.kitStyle == DrumKitStyle.ACOUSTIC) AmberHot else NeonCyan

    HardwarePanel(
        borderColor = accent.copy(alpha = 0.65f),
        modifier = modifier
    ) {
        // 1. Header & Style / Mode Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ARRANGEABLE DRUM KIT STUDIO",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = kitState.lastHitSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (kitState.statusError != null) TacCoral else accent,
                    maxLines = 1
                )
            }
            Surface(
                color = if (kitState.canAddMainPiece) EmeraldSignal.copy(alpha = 0.16f) else AmberHot.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (kitState.canAddMainPiece) EmeraldSignal else AmberHot)
            ) {
                Text(
                    text = "${kitState.mainPieceCount}/$MAX_MAIN_KIT_PIECES MAIN",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (kitState.canAddMainPiece) EmeraldSignal else AmberHot,
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .testTag("drum_piece_count")
                )
            }
        }

        if (kitState.statusError != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = TacCoral.copy(alpha = 0.16f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, TacCoral),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = kitState.statusError,
                    style = MaterialTheme.typography.labelSmall,
                    color = TacCoral,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Style Toggle (Non-Electric Acoustic vs Electric E-Kit) + Arrange / Mirror
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HardwareChipToggle(
                label = "NON-ELECTRIC (ACOUSTIC)",
                selected = kitState.kitStyle == DrumKitStyle.ACOUSTIC,
                onClick = { onSetStyle(DrumKitStyle.ACOUSTIC) },
                accentColor = AmberHot,
                modifier = Modifier.testTag("drum_style_acoustic")
            )
            HardwareChipToggle(
                label = "ELECTRIC (SYNTH E-KIT)",
                selected = kitState.kitStyle == DrumKitStyle.ELECTRIC,
                onClick = { onSetStyle(DrumKitStyle.ELECTRIC) },
                accentColor = NeonCyan,
                modifier = Modifier.testTag("drum_style_electric")
            )
            HardwareChipToggle(
                label = if (kitState.arrangeMode) "ARRANGE: ON (DRAG)" else "ARRANGE STAGE",
                selected = kitState.arrangeMode,
                onClick = onToggleArrangeMode,
                accentColor = EmeraldSignal,
                modifier = Modifier.testTag("drum_mode_arrange")
            )
            HardwareChipToggle(
                label = if (kitState.isLeftHanded) "LEFTY KIT" else "FLIP L/R",
                selected = kitState.isLeftHanded,
                onClick = onFlipLeftHanded,
                accentColor = VioletMod,
                modifier = Modifier.testTag("drum_flip_lr")
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Starter Kit Presets
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DrumKitPreset.entries.forEach { preset ->
                val presetAccent = if (preset.style == DrumKitStyle.ACOUSTIC) AmberHot else NeonCyan
                HardwareChipToggle(
                    label = preset.label,
                    selected = kitState.activePresetId == preset.id,
                    onClick = { onApplyPreset(preset) },
                    accentColor = presetAccent,
                    modifier = Modifier.testTag("drum_preset_${preset.id}")
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Interactive 2D Circular Drum Rug Stage
        DrumKitInteractiveStageCanvas(
            kitState = kitState,
            onHitPiece = onHitPiece,
            onHitAddon = onHitAddon,
            onSelectSlot = onSelectSlot,
            onMovePiece = onMovePiece
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 3. Direct Piece & Double-Pedal Beater Quick Pads (Accessible 48dp+ touch targets)
        Text(
            text = if (kitState.arrangeMode) "SELECT PIECE ON STAGE TO INSPECT / DRAG" else "KIT PIECES & MOUNTED ADD-ONS (TAP TO PLAY)",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            kitState.mainPieces.forEach { piece ->
                val isSel = piece.slotId == selectedPiece?.slotId
                val pColor = if (piece.style == DrumKitStyle.ACOUSTIC) AmberHot else NeonCyan
                val addonCount = kitState.mountedAddons.count { it.targetSlotId == piece.slotId }
                val suffix = if (addonCount > 0) " +$addonCount" else ""
                HardwareChipToggle(
                    label = "${piece.customLabel}$suffix",
                    selected = isSel,
                    onClick = {
                        onSelectSlot(piece.slotId)
                        if (!kitState.arrangeMode) {
                            onHitPiece(piece.slotId, BeaterSide.NONE)
                        }
                    },
                    accentColor = pColor,
                    modifier = Modifier.testTag("drum_piece_chip_${piece.slotId}")
                )
            }
            val dblPedal = kitState.mainPieces.firstOrNull { it.category == DrumPieceCategory.DOUBLE_PEDAL }
            if (dblPedal != null) {
                HardwareChipToggle(
                    label = "L-BEATER",
                    selected = kitState.lastHitSlotId == dblPedal.slotId && kitState.lastHitBeater == BeaterSide.LEFT,
                    onClick = { onHitPiece(dblPedal.slotId, BeaterSide.LEFT) },
                    accentColor = EmeraldSignal,
                    modifier = Modifier.testTag("drum_beater_left")
                )
                HardwareChipToggle(
                    label = "R-BEATER",
                    selected = kitState.lastHitSlotId == dblPedal.slotId && kitState.lastHitBeater == BeaterSide.RIGHT,
                    onClick = { onHitPiece(dblPedal.slotId, BeaterSide.RIGHT) },
                    accentColor = VioletMod,
                    modifier = Modifier.testTag("drum_beater_right")
                )
            }
        }

        // 4. Selected Piece Inspector + Optional Mounted Add-On Controls
        if (selectedPiece != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = ObsidianChassis,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, HairlineDivider),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SELECTED: ${selectedPiece.customLabel} (${selectedPiece.style.shortTag} · ${selectedPiece.category.label})",
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selectedPiece.style == DrumKitStyle.ACOUSTIC) AmberHot else NeonCyan
                            )
                            Text(
                                text = "Pos (${(selectedPiece.xNorm * 100).toInt()}%, ${(selectedPiece.yNorm * 100).toInt()}%) • MIDI N${selectedPiece.midiNote}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            HardwareChipToggle(
                                label = if (selectedPiece.isMuted) "MUTED" else "MUTE",
                                selected = selectedPiece.isMuted,
                                onClick = {
                                    onUpdatePieceParams(selectedPiece.slotId, null, null, null, !selectedPiece.isMuted)
                                },
                                accentColor = TacCoral
                            )
                            Surface(
                                color = TacCoral.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, TacCoral),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onRemovePiece(selectedPiece.slotId) }
                                    .testTag("drum_remove_selected")
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Remove Kit Piece", tint = TacCoral)
                                }
                            }
                        }
                    }

                    HardwareParameterSlider(
                        label = "PITCH TUNE",
                        valueText = "${if (selectedPiece.pitchSemitones >= 0) "+" else ""}${"%.1f".format(selectedPiece.pitchSemitones)} st",
                        value = selectedPiece.pitchSemitones,
                        valueRange = -12f..12f,
                        accentColor = accent,
                        onValueChange = { onUpdatePieceParams(selectedPiece.slotId, it, null, null, null) }
                    )
                    HardwareParameterSlider(
                        label = "DECAY / DAMPING",
                        valueText = "${"%.2f".format(selectedPiece.decayScale)}x",
                        value = selectedPiece.decayScale,
                        valueRange = 0.4f..2.0f,
                        accentColor = VioletMod,
                        onValueChange = { onUpdatePieceParams(selectedPiece.slotId, null, it, null, null) }
                    )
                    HardwareParameterSlider(
                        label = "PIECE LEVEL",
                        valueText = "${(selectedPiece.volume * 100).toInt()}%",
                        value = selectedPiece.volume,
                        valueRange = 0.1f..1.2f,
                        accentColor = EmeraldSignal,
                        onValueChange = { onUpdatePieceParams(selectedPiece.slotId, null, null, it, null) }
                    )

                    // Route selected kit sound to 16-Step Sequencer Lanes or Deck
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SEND TO SEQ:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        for (laneIdx in 0..3) {
                            HardwareChipToggle(
                                label = "L${laneIdx + 1}",
                                selected = false,
                                onClick = {
                                    val beater = if (selectedPiece.category.supportsDoubleBeater) kitState.targetBeaterForMount else BeaterSide.NONE
                                    onRoutePieceToSeqLane(selectedPiece.slotId, laneIdx, beater)
                                },
                                accentColor = NeonCyan,
                                modifier = Modifier.testTag("drum_route_lane_$laneIdx")
                            )
                        }
                        HardwareChipToggle(
                            label = "TO DECK",
                            selected = false,
                            onClick = {
                                val beater = if (selectedPiece.category.supportsDoubleBeater) kitState.targetBeaterForMount else BeaterSide.NONE
                                onSendPieceToDeck(selectedPiece.slotId, beater)
                            },
                            accentColor = AmberHot
                        )
                    }

                    // Mount Add-On Percussion onto Selected Main Piece (e.g. Cowbell -> Snare, Tambourine -> Hi-Hat, Maraca -> Double Pedal L/R Beater)
                    Text(
                        text = "MOUNT OPTIONAL ADD-ON TO ${selectedPiece.customLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MagentaFx
                    )
                    if (selectedPiece.category.supportsDoubleBeater) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HardwareChipToggle(
                                label = "TARGET: LEFT BEATER",
                                selected = kitState.targetBeaterForMount == BeaterSide.LEFT,
                                onClick = { onSetTargetBeater(BeaterSide.LEFT) },
                                accentColor = EmeraldSignal,
                                modifier = Modifier.testTag("drum_target_beater_left")
                            )
                            HardwareChipToggle(
                                label = "TARGET: RIGHT BEATER",
                                selected = kitState.targetBeaterForMount == BeaterSide.RIGHT,
                                onClick = { onSetTargetBeater(BeaterSide.RIGHT) },
                                accentColor = VioletMod,
                                modifier = Modifier.testTag("drum_target_beater_right")
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DrumAddonType.entries.forEach { addonType ->
                            val beater = if (selectedPiece.category.supportsDoubleBeater) kitState.targetBeaterForMount else BeaterSide.NONE
                            HardwareChipToggle(
                                label = "+ ${addonType.label.uppercase()}",
                                selected = false,
                                onClick = { onAttachAddon(addonType, selectedPiece.slotId, beater) },
                                accentColor = MagentaFx,
                                modifier = Modifier.testTag("drum_mount_${addonType.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        }

        // 5. Mounted Add-On Percussion List
        if (kitState.mountedAddons.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "MOUNTED ADD-ON PERCUSSION (${kitState.mountedAddons.size} ATTACHED · DOES NOT COUNT TOWARD 7 MAIN PIECES)",
                style = MaterialTheme.typography.labelSmall,
                color = MagentaFx
            )
            Spacer(modifier = Modifier.height(4.dp))
            kitState.mountedAddons.forEach { addon ->
                val hostPiece = kitState.mainPieces.firstOrNull { it.slotId == addon.targetSlotId }
                val beaterTag = if (hostPiece?.category?.supportsDoubleBeater == true && addon.beaterSide != BeaterSide.NONE) {
                    " [${addon.beaterSide.shortTag}]"
                } else ""
                Surface(
                    color = ElevatedBezel,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MagentaFx.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${addon.addonType.label} -> ${hostPiece?.customLabel ?: "?"}$beaterTag",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "${addon.style.shortTag} • ${if (addon.triggerCoupledWithHost) "Coupled w/ Host Hit" else "Standalone Trigger"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            HardwareChipToggle(
                                label = "HIT",
                                selected = false,
                                onClick = { onHitAddon(addon.instanceId) },
                                accentColor = NeonCyan,
                                modifier = Modifier.testTag("drum_addon_play_${addon.instanceId}")
                            )
                            HardwareChipToggle(
                                label = if (addon.triggerCoupledWithHost) "COUPLED" else "SOLO",
                                selected = addon.triggerCoupledWithHost,
                                onClick = { onToggleAddonCoupled(addon.instanceId) },
                                accentColor = EmeraldSignal,
                                modifier = Modifier.testTag("drum_addon_couple_${addon.instanceId}")
                            )
                            HardwareChipToggle(
                                label = if (addon.style == DrumKitStyle.ACOUSTIC) "AC" else "EL",
                                selected = true,
                                onClick = {
                                    val next = if (addon.style == DrumKitStyle.ACOUSTIC) DrumKitStyle.ELECTRIC else DrumKitStyle.ACOUSTIC
                                    onSetAddonStyle(addon.instanceId, next)
                                },
                                accentColor = if (addon.style == DrumKitStyle.ACOUSTIC) AmberHot else NeonCyan,
                                modifier = Modifier.testTag("drum_addon_style_${addon.instanceId}")
                            )
                            if (hostPiece?.category?.supportsDoubleBeater == true) {
                                HardwareChipToggle(
                                    label = addon.beaterSide.shortTag,
                                    selected = true,
                                    onClick = {
                                        val next = if (addon.beaterSide == BeaterSide.LEFT) BeaterSide.RIGHT else BeaterSide.LEFT
                                        onSetAddonBeaterSide(addon.instanceId, next)
                                    },
                                    accentColor = VioletMod
                                )
                            }
                            HardwareChipToggle(
                                label = "X",
                                selected = false,
                                onClick = { onRemoveAddon(addon.instanceId) },
                                accentColor = TacCoral,
                                modifier = Modifier.testTag("drum_addon_remove_${addon.instanceId}")
                            )
                        }
                    }
                }
            }
        }

        // 6. Acoustic & Electric Component Library Drawer Toggle
        Spacer(modifier = Modifier.height(8.dp))
        HardwareChipToggle(
            label = if (showLibrary) "HIDE KIT COMPONENT LIBRARY" else "CUSTOMIZE KIT LIBRARY (ACOUSTIC & ELECTRIC · ${DrumComponentLibrary.catalog.size} PIECES)",
            selected = showLibrary,
            onClick = { showLibrary = !showLibrary },
            accentColor = accent,
            modifier = Modifier.testTag("drum_toggle_library")
        )

        if (showLibrary) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HardwareChipToggle(
                    label = "ALL (${DrumComponentLibrary.catalog.size})",
                    selected = libraryFilter == null,
                    onClick = { libraryFilter = null },
                    accentColor = TextPrimary,
                    modifier = Modifier.testTag("drum_lib_filter_all")
                )
                HardwareChipToggle(
                    label = "NON-ELECTRIC (ACOUSTIC)",
                    selected = libraryFilter == DrumKitStyle.ACOUSTIC,
                    onClick = { libraryFilter = DrumKitStyle.ACOUSTIC },
                    accentColor = AmberHot,
                    modifier = Modifier.testTag("drum_lib_filter_acoustic")
                )
                HardwareChipToggle(
                    label = "ELECTRIC (E-KIT)",
                    selected = libraryFilter == DrumKitStyle.ELECTRIC,
                    onClick = { libraryFilter = DrumKitStyle.ELECTRIC },
                    accentColor = NeonCyan,
                    modifier = Modifier.testTag("drum_lib_filter_electric")
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            val items = DrumComponentLibrary.forStyle(libraryFilter)
            items.forEach { comp ->
                val itemColor = if (comp.style == DrumKitStyle.ACOUSTIC) AmberHot else NeonCyan
                Surface(
                    color = BrushedPanel,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, itemColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = comp.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "${comp.style.shortTag} · ${comp.category.label} · MIDI N${comp.defaultMidiNote}",
                                style = MaterialTheme.typography.labelSmall,
                                color = itemColor
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                color = if (kitState.canAddMainPiece) EmeraldSignal.copy(alpha = 0.18f) else HairlineDivider,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (kitState.canAddMainPiece) EmeraldSignal else TextMuted),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onAddPiece(comp.id) }
                                    .testTag("drum_lib_add_${comp.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add ${comp.displayName}", tint = if (kitState.canAddMainPiece) EmeraldSignal else TextMuted)
                                    Text(
                                        text = if (kitState.canAddMainPiece) "ADD" else "FULL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (kitState.canAddMainPiece) EmeraldSignal else TextMuted
                                    )
                                }
                            }
                            if (selectedPiece != null) {
                                Surface(
                                    color = itemColor.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, itemColor),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onSwapPiece(selectedPiece.slotId, comp.id) }
                                        .testTag("drum_lib_swap_${comp.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap Selected With ${comp.displayName}", tint = itemColor)
                                        Text("SWAP", style = MaterialTheme.typography.labelSmall, color = itemColor)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrumKitInteractiveStageCanvas(
    kitState: CustomDrumKitState,
    onHitPiece: (String, BeaterSide) -> Unit,
    onHitAddon: (String) -> Unit,
    onSelectSlot: (String?) -> Unit,
    onMovePiece: (String, Float, Float) -> Unit
) {
    val latestState by rememberUpdatedState(kitState)
    val latestOnHitPiece by rememberUpdatedState(onHitPiece)
    val latestOnHitAddon by rememberUpdatedState(onHitAddon)
    val latestOnSelectSlot by rememberUpdatedState(onSelectSlot)
    val latestOnMovePiece by rememberUpdatedState(onMovePiece)
    val textMeasurer = rememberTextMeasurer()

    var draggingSlotId by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(258.dp)
    ) {
        Surface(
            color = ObsidianChassis,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(
                width = if (kitState.arrangeMode) 2.dp else 1.dp,
                color = if (kitState.arrangeMode) EmeraldSignal else HairlineDivider
            ),
            modifier = Modifier
                .fillMaxSize()
                .testTag("drum_stage_canvas")
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val st = latestState
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val nx = offset.x / w
                            val ny = offset.y / h

                            // Check mounted add-on badge centers first if in play mode
                            if (!st.arrangeMode) {
                                st.mountedAddons.forEach { addon ->
                                    val host = st.mainPieces.firstOrNull { it.slotId == addon.targetSlotId } ?: return@forEach
                                    val badgeCenter = computeAddonOffset(host, addon, w, h)
                                    if (hypot(offset.x - badgeCenter.x, offset.y - badgeCenter.y) <= min(w, h) * 0.085f) {
                                        latestOnHitAddon(addon.instanceId)
                                        return@detectTapGestures
                                    }
                                }
                            }

                            val hitPiece = st.mainPieces.minByOrNull { p ->
                                hypot(offset.x - p.xNorm * w, offset.y - p.yNorm * h)
                            }
                            if (hitPiece != null && hypot(offset.x - hitPiece.xNorm * w, offset.y - hitPiece.yNorm * h) <= min(w, h) * 0.19f) {
                                latestOnSelectSlot(hitPiece.slotId)
                                if (!st.arrangeMode) {
                                    val beater = if (hitPiece.category == DrumPieceCategory.DOUBLE_PEDAL) {
                                        if (nx < hitPiece.xNorm) BeaterSide.LEFT else BeaterSide.RIGHT
                                    } else BeaterSide.NONE
                                    latestOnHitPiece(hitPiece.slotId, beater)
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { start ->
                                val st = latestState
                                if (!st.arrangeMode) return@detectDragGestures
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val target = st.mainPieces.minByOrNull { p ->
                                    hypot(start.x - p.xNorm * w, start.y - p.yNorm * h)
                                }
                                if (target != null && hypot(start.x - target.xNorm * w, start.y - target.yNorm * h) <= min(w, h) * 0.22f) {
                                    draggingSlotId = target.slotId
                                    latestOnSelectSlot(target.slotId)
                                }
                            },
                            onDrag = { change, dragAmount ->
                                val slot = draggingSlotId ?: return@detectDragGestures
                                if (!latestState.arrangeMode) return@detectDragGestures
                                change.consume()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val curPiece = latestState.mainPieces.firstOrNull { it.slotId == slot } ?: return@detectDragGestures
                                latestOnMovePiece(
                                    slot,
                                    (curPiece.xNorm + dragAmount.x / w).coerceIn(0.08f, 0.92f),
                                    (curPiece.yNorm + dragAmount.y / h).coerceIn(0.12f, 0.88f)
                                )
                            },
                            onDragEnd = { draggingSlotId = null },
                            onDragCancel = { draggingSlotId = null }
                        )
                    }
            ) {
                drawDrumStage(
                    state = kitState,
                    textMeasurer = textMeasurer
                )
            }
        }
    }
}

private fun computeAddonOffset(
    host: PlacedKitPiece,
    addon: MountedAddonPiece,
    width: Float,
    height: Float
): Offset {
    val hx = host.xNorm * width
    val hy = host.yNorm * height
    val unit = min(width, height)
    if (host.category == DrumPieceCategory.DOUBLE_PEDAL) {
        val dx = when (addon.beaterSide) {
            BeaterSide.LEFT -> -unit * 0.085f
            BeaterSide.RIGHT -> unit * 0.085f
            BeaterSide.NONE -> 0f
        }
        return Offset(hx + dx, hy - unit * 0.095f)
    }
    return Offset(hx + unit * 0.075f, hy - unit * 0.072f)
}

private fun DrawScope.drawDrumStage(
    state: CustomDrumKitState,
    textMeasurer: TextMeasurer
) {
    val w = size.width
    val h = size.height
    val unit = min(w, h)

    // Circular drum rug markings
    val rugCenter = Offset(w * 0.5f, h * 0.55f)
    drawCircle(
        color = HairlineDivider.copy(alpha = 0.28f),
        radius = unit * 0.44f,
        center = rugCenter,
        style = Stroke(width = 1.5f)
    )
    drawCircle(
        color = HairlineDivider.copy(alpha = 0.16f),
        radius = unit * 0.28f,
        center = rugCenter,
        style = Stroke(width = 1f)
    )
    drawLine(
        color = HairlineDivider.copy(alpha = 0.2f),
        start = Offset(w * 0.08f, h * 0.55f),
        end = Offset(w * 0.92f, h * 0.55f),
        strokeWidth = 1f
    )

    // Draw main kit pieces
    state.mainPieces.forEach { piece ->
        val cx = piece.xNorm * w
        val cy = piece.yNorm * h
        val center = Offset(cx, cy)
        val isSelected = piece.slotId == state.selectedSlotId
        val isHit = piece.slotId == state.lastHitSlotId
        val isAcoustic = piece.style == DrumKitStyle.ACOUSTIC

        val baseRadius = when (piece.category) {
            DrumPieceCategory.KICK, DrumPieceCategory.DOUBLE_PEDAL -> unit * 0.125f
            DrumPieceCategory.RIDE, DrumPieceCategory.CRASH -> unit * 0.115f
            DrumPieceCategory.SNARE, DrumPieceCategory.FLOOR_TOM -> unit * 0.105f
            DrumPieceCategory.HIHAT -> unit * 0.092f
            DrumPieceCategory.RACK_TOM, DrumPieceCategory.FX_CYMBAL -> unit * 0.088f
        }

        val shellColor = when {
            isAcoustic && (piece.category == DrumPieceCategory.CRASH || piece.category == DrumPieceCategory.RIDE ||
                piece.category == DrumPieceCategory.HIHAT || piece.category == DrumPieceCategory.FX_CYMBAL) -> Color(0xFFD4A43A)
            isAcoustic -> Color(0xFF9C542A)
            else -> Color(0xFF1A2232)
        }
        val headColor = when {
            piece.isMuted -> TextMuted.copy(alpha = 0.25f)
            isAcoustic && (piece.category == DrumPieceCategory.CRASH || piece.category == DrumPieceCategory.RIDE ||
                piece.category == DrumPieceCategory.HIHAT || piece.category == DrumPieceCategory.FX_CYMBAL) -> Color(0xFFE8BE5A).copy(alpha = 0.35f)
            isAcoustic -> Color(0xFFEDE4D3).copy(alpha = 0.22f)
            else -> NeonCyan.copy(alpha = 0.14f)
        }
        val rimColor = when {
            isHit -> EmeraldSignal
            isSelected -> if (state.arrangeMode) EmeraldSignal else Color.White
            isAcoustic -> AmberHot
            else -> NeonCyan
        }

        if (piece.category == DrumPieceCategory.DOUBLE_PEDAL) {
            // Bass shell center + Left & Right twin beaters & pedals
            drawCircle(color = shellColor.copy(alpha = 0.45f), radius = baseRadius, center = center)
            drawCircle(color = rimColor, radius = baseRadius, center = center, style = Stroke(width = if (isSelected) 3f else 2f))

            val pedalY = cy + baseRadius * 0.36f
            val pedalW = baseRadius * 0.62f
            val pedalH = baseRadius * 0.96f
            val leftX = cx - baseRadius * 0.78f
            val rightX = cx + baseRadius * 0.16f

            drawRoundRect(
                color = if (isHit && state.lastHitBeater == BeaterSide.LEFT) EmeraldSignal else rimColor.copy(alpha = 0.7f),
                topLeft = Offset(leftX, pedalY - pedalH * 0.35f),
                size = Size(pedalW, pedalH),
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 2.5f)
            )
            drawRoundRect(
                color = if (isHit && state.lastHitBeater == BeaterSide.RIGHT) VioletMod else rimColor.copy(alpha = 0.7f),
                topLeft = Offset(rightX, pedalY - pedalH * 0.35f),
                size = Size(pedalW, pedalH),
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 2.5f)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "L",
                topLeft = Offset(leftX + pedalW * 0.28f, pedalY - 4f),
                style = TextStyle(color = EmeraldSignal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "R",
                topLeft = Offset(rightX + pedalW * 0.28f, pedalY - 4f),
                style = TextStyle(color = VioletMod, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            )
        } else {
            drawCircle(color = headColor, radius = baseRadius, center = center)
            drawCircle(color = shellColor.copy(alpha = 0.55f), radius = baseRadius * 0.88f, center = center, style = Stroke(width = 3f))
            drawCircle(color = rimColor, radius = baseRadius, center = center, style = Stroke(width = if (isSelected) 3.5f else 2f))
        }

        val labelLayout = textMeasurer.measure(
            piece.customLabel,
            TextStyle(color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        )
        drawText(
            textLayoutResult = labelLayout,
            topLeft = Offset(cx - labelLayout.size.width / 2f, cy - labelLayout.size.height / 2f - 4f)
        )
    }

    // Draw mounted add-ons attached onto host pieces (e.g. Cowbell on Snare, Tambourine on Hi-Hat, Maraca on Double-Pedal Beater)
    state.mountedAddons.forEach { addon ->
        val host = state.mainPieces.firstOrNull { it.slotId == addon.targetSlotId } ?: return@forEach
        val badgeCenter = computeAddonOffset(host, addon, w, h)
        val badgeRadius = unit * 0.056f
        val isAddonHit = addon.instanceId == state.lastHitAddonId
        val badgeColor = when (addon.addonType) {
            DrumAddonType.COWBELL -> AmberHot
            DrumAddonType.TAMBOURINE -> NeonCyan
            DrumAddonType.MARACA -> EmeraldSignal
            DrumAddonType.JAM_BLOCK -> MagentaFx
            DrumAddonType.CHIME_SPLASHER -> VioletMod
        }

        // Connector mount bracket line
        drawLine(
            color = badgeColor.copy(alpha = 0.7f),
            start = Offset(host.xNorm * w, host.yNorm * h),
            end = badgeCenter,
            strokeWidth = 2f
        )
        if (addon.addonType == DrumAddonType.COWBELL) {
            val bellPath = Path().apply {
                moveTo(badgeCenter.x - badgeRadius * 0.55f, badgeCenter.y - badgeRadius * 0.65f)
                lineTo(badgeCenter.x + badgeRadius * 0.55f, badgeCenter.y - badgeRadius * 0.65f)
                lineTo(badgeCenter.x + badgeRadius * 0.8f, badgeCenter.y + badgeRadius * 0.65f)
                lineTo(badgeCenter.x - badgeRadius * 0.8f, badgeCenter.y + badgeRadius * 0.65f)
                close()
            }
            drawPath(bellPath, color = if (isAddonHit) Color.White else badgeColor)
        } else {
            drawCircle(
                color = if (isAddonHit) Color.White else badgeColor.copy(alpha = 0.9f),
                radius = badgeRadius,
                center = badgeCenter
            )
            drawCircle(
                color = ObsidianChassis,
                radius = badgeRadius * 0.65f,
                center = badgeCenter,
                style = Stroke(width = 1.5f)
            )
        }
        val tag = addon.addonType.shortTag.take(4)
        val tagLayout = textMeasurer.measure(
            tag,
            TextStyle(color = ObsidianChassis, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
        )
        drawText(
            textLayoutResult = tagLayout,
            topLeft = Offset(badgeCenter.x - tagLayout.size.width / 2f, badgeCenter.y - tagLayout.size.height / 2f)
        )
    }

    if (state.arrangeMode) {
        val hintLayout = textMeasurer.measure(
            "ARRANGE MODE: DRAG ANY PIECE",
            TextStyle(color = EmeraldSignal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        )
        drawText(hintLayout, topLeft = Offset(10f, 8f))
    }
}
