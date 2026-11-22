package com.example.midi

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.drumPadCompanionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "samplfie2_drum_companion"
)

class DrumPadCompanionRepository(private val context: Context) {

    val configFlow: Flow<DrumPadCompanionConfig> = context.drumPadCompanionDataStore.data.map { prefs ->
        val presetName = prefs[KEY_PRESET] ?: DrumPadBankPreset.SAMPLFIE_LIVE_KIT.name
        val preset = DrumPadBankPreset.entries.firstOrNull { it.name == presetName }
            ?: DrumPadBankPreset.SAMPLFIE_LIVE_KIT
        val transportName = prefs[KEY_TRANSPORT] ?: DrumPadTransportTarget.LOCAL_PLUS_MIDI.name
        val transport = DrumPadTransportTarget.entries.firstOrNull { it.name == transportName }
            ?: DrumPadTransportTarget.LOCAL_PLUS_MIDI
        val velName = prefs[KEY_VELOCITY_MODE] ?: PadVelocityMode.FULL_127.name
        val velMode = PadVelocityMode.entries.firstOrNull { it.name == velName } ?: PadVelocityMode.FULL_127
        val trigName = prefs[KEY_TRIGGER_MODE] ?: PadTriggerMode.ONE_SHOT.name
        val trigMode = PadTriggerMode.entries.firstOrNull { it.name == trigName } ?: PadTriggerMode.ONE_SHOT
        val repName = prefs[KEY_NOTE_REPEAT] ?: NoteRepeatDivision.OFF.name
        val noteRepeat = NoteRepeatDivision.entries.firstOrNull { it.name == repName } ?: NoteRepeatDivision.OFF

        val serializedPads = prefs[KEY_PADS]
        val pads = if (!serializedPads.isNullOrBlank()) {
            decodePads(serializedPads).ifEmpty { DrumPadCompanionConfig.buildPadsForPreset(preset) }
        } else {
            DrumPadCompanionConfig.buildPadsForPreset(preset)
        }

        DrumPadCompanionConfig(
            enabled = prefs[KEY_ENABLED] ?: true,
            transportTarget = transport,
            preset = preset,
            hardwareMidiChannel16 = (prefs[KEY_HW_CH16] ?: 10).coerceIn(1, 16),
            vmcChannel64 = (prefs[KEY_VMC_CH64] ?: 33).coerceIn(1, 64),
            velocityMode = velMode,
            triggerMode = trigMode,
            noteRepeat = noteRepeat,
            liveOverdubArmed = prefs[KEY_OVERDUB] ?: false,
            stepRecordWhenStopped = prefs[KEY_STEP_REC] ?: true,
            quantizeToStep = prefs[KEY_QUANTIZE] ?: true,
            stepCursor = (prefs[KEY_STEP_CURSOR] ?: 0).coerceIn(0, 15),
            accentToggle = prefs[KEY_ACCENT] ?: false,
            sendMidiClock = prefs[KEY_MIDI_CLOCK] ?: false,
            localAuditionEnabled = prefs[KEY_LOCAL_AUDITION] ?: true,
            selectedPadIndex = (prefs[KEY_SELECTED_PAD] ?: 0).coerceIn(0, 15),
            pads = pads
        )
    }

    suspend fun saveConfig(config: DrumPadCompanionConfig) {
        context.drumPadCompanionDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = config.enabled
            prefs[KEY_TRANSPORT] = config.transportTarget.name
            prefs[KEY_PRESET] = config.preset.name
            prefs[KEY_HW_CH16] = config.hardwareMidiChannel16.coerceIn(1, 16)
            prefs[KEY_VMC_CH64] = config.vmcChannel64.coerceIn(1, 64)
            prefs[KEY_VELOCITY_MODE] = config.velocityMode.name
            prefs[KEY_TRIGGER_MODE] = config.triggerMode.name
            prefs[KEY_NOTE_REPEAT] = config.noteRepeat.name
            prefs[KEY_OVERDUB] = config.liveOverdubArmed
            prefs[KEY_STEP_REC] = config.stepRecordWhenStopped
            prefs[KEY_QUANTIZE] = config.quantizeToStep
            prefs[KEY_STEP_CURSOR] = config.stepCursor.coerceIn(0, 15)
            prefs[KEY_ACCENT] = config.accentToggle
            prefs[KEY_MIDI_CLOCK] = config.sendMidiClock
            prefs[KEY_LOCAL_AUDITION] = config.localAuditionEnabled
            prefs[KEY_SELECTED_PAD] = config.selectedPadIndex.coerceIn(0, 15)
            prefs[KEY_PADS] = encodePads(config.pads)
        }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("companion_enabled")
        private val KEY_TRANSPORT = stringPreferencesKey("companion_transport")
        private val KEY_PRESET = stringPreferencesKey("companion_preset")
        private val KEY_HW_CH16 = intPreferencesKey("companion_hw_ch16")
        private val KEY_VMC_CH64 = intPreferencesKey("companion_vmc_ch64")
        private val KEY_VELOCITY_MODE = stringPreferencesKey("companion_vel_mode")
        private val KEY_TRIGGER_MODE = stringPreferencesKey("companion_trig_mode")
        private val KEY_NOTE_REPEAT = stringPreferencesKey("companion_note_repeat")
        private val KEY_OVERDUB = booleanPreferencesKey("companion_overdub")
        private val KEY_STEP_REC = booleanPreferencesKey("companion_step_rec")
        private val KEY_QUANTIZE = booleanPreferencesKey("companion_quantize")
        private val KEY_STEP_CURSOR = intPreferencesKey("companion_step_cursor")
        private val KEY_ACCENT = booleanPreferencesKey("companion_accent")
        private val KEY_MIDI_CLOCK = booleanPreferencesKey("companion_midi_clock")
        private val KEY_LOCAL_AUDITION = booleanPreferencesKey("companion_local_audition")
        private val KEY_SELECTED_PAD = intPreferencesKey("companion_selected_pad")
        private val KEY_PADS = stringPreferencesKey("companion_pads_v1")

        fun encodePads(pads: List<DrumPadSlotConfig>): String {
            return pads.joinToString(";") { pad ->
                val cleanLabel = pad.label.replace(";", "_").replace("|", "_")
                val ch = pad.channel64Override?.toString() ?: ""
                val lane = pad.sequencerLaneId?.toString() ?: ""
                "${pad.index}|$cleanLabel|${pad.midiNote}|$ch|$lane|${pad.accentColor.name}"
            }
        }

        fun decodePads(raw: String): List<DrumPadSlotConfig> {
            return raw.split(";").mapNotNull { chunk ->
                val parts = chunk.split("|")
                if (parts.size < 6) return@mapNotNull null
                val idx = parts[0].toIntOrNull()?.coerceIn(0, 15) ?: return@mapNotNull null
                val note = parts[2].toIntOrNull()?.coerceIn(0, 127) ?: 36
                val chOv = parts[3].toIntOrNull()?.coerceIn(1, 64)
                val laneId = parts[4].toIntOrNull()?.coerceIn(0, 3)
                val color = PadAccentColor.entries.firstOrNull { it.name == parts[5] } ?: PadAccentColor.CYAN
                DrumPadSlotConfig(
                    index = idx,
                    label = parts[1].ifBlank { "PAD ${idx + 1}" },
                    midiNote = note,
                    channel64Override = chOv,
                    sequencerLaneId = laneId,
                    accentColor = color
                )
            }.sortedBy { it.index }
        }
    }
}
