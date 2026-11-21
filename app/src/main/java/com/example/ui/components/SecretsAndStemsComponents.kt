package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.config.SecretsVaultRepository
import com.example.config.SecretsVaultState
import com.example.stems.DisbandStem
import com.example.stems.SeparatedStemTrack
import com.example.stems.StemCategory
import com.example.stems.StemPreset
import com.example.stems.StemSeparationUiState
import com.example.ui.theme.AmberHot
import com.example.ui.theme.BrushedPanel
import com.example.ui.theme.EmeraldSignal
import com.example.ui.theme.GunmetalDeck
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.MagentaFx
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.ObsidianChassis
import com.example.ui.theme.TacCoral
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VioletMod

@Composable
fun SecretsVaultDialog(
    secretsState: SecretsVaultState,
    onSaveSecret: (keyName: String, secretValue: String, providerHint: String) -> Unit,
    onDeleteSecret: (keyName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var disbandKeyInput by remember { mutableStateOf("") }
    var showDisbandKey by remember { mutableStateOf(false) }
    var baseUrlInput by remember(secretsState.disbandBaseUrl) { mutableStateOf(secretsState.disbandBaseUrl) }
    var customKeyName by remember { mutableStateOf("") }
    var customKeyValue by remember { mutableStateOf("") }
    var showCustomValue by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GunmetalDeck,
        modifier = Modifier.testTag("secrets_vault_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = "Keys and Secrets Store",
                    tint = if (secretsState.hasDisbandKey) EmeraldSignal else AmberHot
                )
                Column {
                    Text(
                        text = "SECRETS & KEYS VAULT",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        text = "AES-GCM encrypted store • ${secretsState.disbandKeySource}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (secretsState.hasDisbandKey) EmeraldSignal else AmberHot
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Disband API Key Card
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (secretsState.hasDisbandKey) EmeraldSignal else AmberHot)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DISBAND STEM API KEY",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = NeonCyan
                            )
                            Text(
                                text = if (secretsState.hasDisbandKey) "SET" else "MISSING",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (secretsState.hasDisbandKey) EmeraldSignal else TacCoral,
                                modifier = Modifier.testTag("secrets_disband_status")
                            )
                        }
                        OutlinedTextField(
                            value = disbandKeyInput,
                            onValueChange = { disbandKeyInput = it },
                            label = { Text("Enter DISBAND_API_KEY (Bearer)") },
                            singleLine = true,
                            visualTransformation = if (showDisbandKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showDisbandKey = !showDisbandKey }) {
                                    Icon(
                                        imageVector = if (showDisbandKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = "Toggle key visibility",
                                        tint = TextSecondary
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("secrets_disband_input")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (disbandKeyInput.isNotBlank()) {
                                        onSaveSecret(
                                            SecretsVaultRepository.KEY_DISBAND_API_KEY,
                                            disbandKeyInput,
                                            "Disband Stem API"
                                        )
                                        disbandKeyInput = ""
                                    }
                                },
                                enabled = disbandKeyInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldSignal),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("secrets_save_disband_button")
                            ) {
                                Text("SAVE KEY", color = ObsidianChassis)
                            }
                            OutlinedButton(
                                onClick = { onDeleteSecret(SecretsVaultRepository.KEY_DISBAND_API_KEY) },
                                border = BorderStroke(1.dp, TacCoral),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("CLEAR", color = TacCoral)
                            }
                        }

                        OutlinedTextField(
                            value = baseUrlInput,
                            onValueChange = { baseUrlInput = it },
                            label = { Text("Disband API Base URL") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("secrets_disband_url_input")
                        )
                        OutlinedButton(
                            onClick = {
                                onSaveSecret(
                                    SecretsVaultRepository.KEY_DISBAND_BASE_URL,
                                    baseUrlInput,
                                    "Disband Endpoint"
                                )
                            },
                            border = BorderStroke(1.dp, NeonCyan),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("SAVE ENDPOINT URL", color = NeonCyan)
                        }
                    }
                }

                // Stored Entries List
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, HairlineDivider)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "STORED KEYS & SECRETS (${secretsState.entries.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                        if (secretsState.entries.isEmpty()) {
                            Text(
                                text = "No keys stored yet. Add DISBAND_API_KEY above or custom tokens below.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        } else {
                            secretsState.entries.forEach { entry ->
                                Surface(
                                    color = BrushedPanel,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, HairlineDivider)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.keyName,
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = NeonCyan,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${entry.maskedValue} • ${entry.providerHint}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (!entry.isFromBuildConfig) {
                                            IconButton(
                                                onClick = { onDeleteSecret(entry.keyName) },
                                                modifier = Modifier.minimumInteractiveComponentSize()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Delete,
                                                    contentDescription = "Delete ${entry.keyName}",
                                                    tint = TacCoral
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Add Custom Secret Key/Value
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, HairlineDivider)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "ADD CUSTOM KEY / SECRET",
                            style = MaterialTheme.typography.labelMedium,
                            color = VioletMod
                        )
                        OutlinedTextField(
                            value = customKeyName,
                            onValueChange = { customKeyName = it },
                            label = { Text("Key Name (e.g. STUDIO_WEBHOOK_TOKEN)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("secrets_custom_name_input")
                        )
                        OutlinedTextField(
                            value = customKeyValue,
                            onValueChange = { customKeyValue = it },
                            label = { Text("Secret Value") },
                            singleLine = true,
                            visualTransformation = if (showCustomValue) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showCustomValue = !showCustomValue }) {
                                    Icon(
                                        imageVector = if (showCustomValue) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = "Toggle custom secret visibility",
                                        tint = TextSecondary
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("secrets_custom_value_input")
                        )
                        Button(
                            onClick = {
                                if (customKeyName.isNotBlank() && customKeyValue.isNotBlank()) {
                                    onSaveSecret(customKeyName, customKeyValue, "Custom Secret")
                                    customKeyName = ""
                                    customKeyValue = ""
                                }
                            },
                            enabled = customKeyName.isNotBlank() && customKeyValue.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = VioletMod),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("secrets_save_custom_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = ObsidianChassis)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("STORE SECRET", color = ObsidianChassis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("DONE", color = NeonCyan)
            }
        }
    )
}

@Composable
fun AllStemsConfirmationDialog(
    estimatedCredits: Int,
    durationSec: Float,
    onConfirmAllStems: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GunmetalDeck,
        modifier = Modifier.testTag("all_stems_confirm_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "High Credit Cost Warning",
                    tint = AmberHot
                )
                Text(
                    text = "SEPARATE ALL STEMS?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AmberHot
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "No specific stem set is selected. Running Disband without selecting stems separates ALL 6 core stems (Vocals, Drums, Bass, Guitar, Piano, Other).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Surface(
                    color = ObsidianChassis,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, AmberHot.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = "Audio: ${"%.1f".format(durationSec)}s • Est. Cost: ~$estimatedCredits Disband credits.\nTip: Pick individual stems (e.g. Vocals Only or Vocals + Drums) to lower credit usage.",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberHot,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmAllStems,
                colors = ButtonDefaults.buttonColors(containerColor = AmberHot),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("confirm_all_stems_button")
            ) {
                Text("CONFIRM ALL STEMS", color = ObsidianChassis)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                border = BorderStroke(1.dp, NeonCyan),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("cancel_all_stems_button")
            ) {
                Text("PICK STEMS INSTEAD", color = NeonCyan)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisbandStemSeparationPanel(
    stemState: StemSeparationUiState,
    secretsState: SecretsVaultState,
    hasAudio: Boolean,
    onToggleStem: (DisbandStem) -> Unit,
    onApplyPreset: (StemPreset) -> Unit,
    onSetUseSelectionOnly: (Boolean) -> Unit,
    onRequestSeparation: () -> Unit,
    onCancelSeparation: () -> Unit,
    onOpenSecretsVault: () -> Unit,
    onLoadStemToDeck: (String) -> Unit,
    onSendStemToLooper: (String, Int) -> Unit,
    onAddStemToTimeline: (String, Int) -> Unit,
    onSendAllStemsToTimeline: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSubStems by remember { mutableStateOf(false) }

    HardwarePanel(
        borderColor = MagentaFx.copy(alpha = 0.55f),
        modifier = modifier.testTag("stem_separation_panel")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DISBAND AI STEM SEPARATION",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MagentaFx
                )
                Text(
                    text = if (secretsState.hasDisbandKey) {
                        "KEY READY (${secretsState.disbandKeySource}) • ${stemState.selectedStems.size.let { if (it == 0) "ALL-STEMS PROMPT" else "$it STEM(S)" }}"
                    } else {
                        "DISBAND KEY MISSING — TAP KEYS VAULT"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (secretsState.hasDisbandKey) EmeraldSignal else AmberHot,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                color = if (secretsState.hasDisbandKey) EmeraldSignal.copy(alpha = 0.16f) else AmberHot.copy(alpha = 0.18f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (secretsState.hasDisbandKey) EmeraldSignal else AmberHot),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenSecretsVault)
                    .testTag("stems_open_secrets_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = "Open Keys and Secrets Store",
                        tint = if (secretsState.hasDisbandKey) EmeraldSignal else AmberHot,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = if (secretsState.hasDisbandKey) "KEYS OK" else "SET KEY",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (secretsState.hasDisbandKey) EmeraldSignal else AmberHot
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Preset Row
        Text("QUICK STEM PRESETS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StemPreset.entries.forEach { preset ->
                val isMatch = preset.stems == stemState.selectedStems
                HardwareChipToggle(
                    label = preset.label,
                    selected = isMatch,
                    onClick = { onApplyPreset(preset) },
                    accentColor = if (preset == StemPreset.UNSPECIFIED_ALL) AmberHot else NeonCyan,
                    modifier = Modifier.testTag("stem_preset_${preset.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Individual Stem Toggle Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "INDIVIDUAL STEMS (EMPTY = PROMPT ALL STEMS)",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showSubStems = !showSubStems }) {
                Text(if (showSubStems) "HIDE PRO SUBS" else "+ PRO SUB-STEMS", color = VioletMod)
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DisbandStem.entries.filter { it.category == StemCategory.PRIMARY }.forEach { stem ->
                HardwareChipToggle(
                    label = stem.label,
                    selected = stemState.selectedStems.contains(stem),
                    onClick = { onToggleStem(stem) },
                    accentColor = MagentaFx,
                    modifier = Modifier.testTag("stem_chip_${stem.apiId}")
                )
            }
        }

        AnimatedVisibility(visible = showSubStems) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DisbandStem.entries.filter { it.category != StemCategory.PRIMARY }.forEach { stem ->
                    HardwareChipToggle(
                        label = stem.label,
                        selected = stemState.selectedStems.contains(stem),
                        onClick = { onToggleStem(stem) },
                        accentColor = VioletMod,
                        modifier = Modifier.testTag("stem_chip_${stem.apiId}")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Source Scope & Separate Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HardwareChipToggle(
                label = if (stemState.useSelectionOnly) "IN-OUT SEL" else "FULL DECK",
                selected = stemState.useSelectionOnly,
                onClick = { onSetUseSelectionOnly(!stemState.useSelectionOnly) },
                accentColor = NeonCyan,
                modifier = Modifier.testTag("stem_scope_toggle")
            )
            if (stemState.isRunning) {
                Button(
                    onClick = onCancelSeparation,
                    colors = ButtonDefaults.buttonColors(containerColor = TacCoral),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("disband_cancel_button")
                ) {
                    Text("CANCEL (${stemState.jobStatusLabel})", color = ObsidianChassis)
                }
            } else {
                val btnLabel = if (stemState.selectedStems.isEmpty()) {
                    "GET STEMS (ALL - CONFIRM)"
                } else {
                    "GET ${stemState.selectedStems.size} STEM(S)"
                }
                Button(
                    onClick = onRequestSeparation,
                    enabled = hasAudio,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (stemState.selectedStems.isEmpty()) AmberHot else MagentaFx
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("disband_separate_button")
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = ObsidianChassis, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(btnLabel, color = ObsidianChassis, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (stemState.isRunning) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = MagentaFx,
                trackColor = ObsidianChassis
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stemState.errorMessage ?: stemState.statusMessage,
            style = MaterialTheme.typography.labelSmall,
            color = if (stemState.errorMessage != null) TacCoral else TextSecondary,
            modifier = Modifier.testTag("disband_status_text")
        )

        // Separated Stem Outputs
        if (stemState.separatedStems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SEPARATED STEMS (${stemState.separatedStems.size})",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = EmeraldSignal
                )
                HardwareChipToggle(
                    label = "ALL -> TRACKS",
                    selected = false,
                    onClick = onSendAllStemsToTimeline,
                    accentColor = EmeraldSignal,
                    modifier = Modifier.testTag("stems_all_to_tracks_button")
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stemState.separatedStems.forEachIndexed { idx, stem ->
                    SeparatedStemRow(
                        stem = stem,
                        suggestedSlot = idx % 4,
                        onLoadToDeck = { onLoadStemToDeck(stem.stemId) },
                        onSendToLooper = { onSendStemToLooper(stem.stemId, idx % 4) },
                        onSendToTrack = { onAddStemToTimeline(stem.stemId, idx % 4) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeparatedStemRow(
    stem: SeparatedStemTrack,
    suggestedSlot: Int,
    onLoadToDeck: () -> Unit,
    onSendToLooper: () -> Unit,
    onSendToTrack: () -> Unit
) {
    Surface(
        color = ObsidianChassis,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, HairlineDivider),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("separated_stem_row_${stem.stemId}")
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stem.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = NeonCyan
                )
                Text(
                    text = "${"%.2f".format(stem.buffer.durationSeconds)}s • ${stem.sizeBytes / 1024}KB",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HardwareChipToggle(
                    label = "-> DECK",
                    selected = false,
                    onClick = onLoadToDeck,
                    accentColor = NeonCyan,
                    modifier = Modifier.testTag("stem_to_deck_${stem.stemId}")
                )
                HardwareChipToggle(
                    label = "-> LOOP ${'A' + suggestedSlot}",
                    selected = false,
                    onClick = onSendToLooper,
                    accentColor = EmeraldSignal,
                    modifier = Modifier.testTag("stem_to_loop_${stem.stemId}")
                )
                HardwareChipToggle(
                    label = "-> TRK ${suggestedSlot + 1}",
                    selected = false,
                    onClick = onSendToTrack,
                    accentColor = MagentaFx,
                    modifier = Modifier.testTag("stem_to_track_${stem.stemId}")
                )
            }
        }
    }
}
