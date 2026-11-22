package com.samplfie.desktop.stems

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DesktopSecretEntry(
    val keyName: String,
    val maskedValue: String,
    val sourceLabel: String,
    val isReadOnlyEnv: Boolean = false
)

data class DesktopSecretsState(
    val entries: List<DesktopSecretEntry> = emptyList(),
    val hasDisbandKey: Boolean = false,
    val disbandKeySource: String = "NOT CONFIGURED",
    val disbandBaseUrl: String = DesktopSecretsVault.DEFAULT_DISBAND_BASE_URL,
    val statusNote: String = "Configure DISBAND_API_KEY or custom API secrets"
)

/**
 * Persistent POSIX-0600 Secrets & Keys Vault for Linux Desktop (`~/.config/samplfie2/secrets.properties`),
 * with environment variable / workspace `.env` fallback for `DISBAND_API_KEY`.
 */
class DesktopSecretsVault(
    private val storeFile: File = File(
        File(System.getProperty("user.home"), ".config/samplfie2"),
        "secrets.properties"
    )
) {
    private val lock = Any()
    private val inMemory = LinkedHashMap<String, String>()

    private val _state = MutableStateFlow(DesktopSecretsState())
    val state: StateFlow<DesktopSecretsState> = _state.asStateFlow()

    init {
        loadFromDisk()
    }

    fun loadFromDisk() {
        synchronized(lock) {
            if (storeFile.exists()) {
                runCatching {
                    val props = Properties()
                    storeFile.inputStream().use { props.load(it) }
                    props.stringPropertyNames().sorted().forEach { k ->
                        val v = props.getProperty(k)?.trim().orEmpty()
                        if (v.isNotEmpty()) inMemory[k] = v
                    }
                }
            }
        }
        publish("Vault ready (${inMemory.size} stored)")
    }

    fun putSecret(keyName: String, value: String) {
        val cleanKey = sanitizeKeyName(keyName)
        require(cleanKey.isNotEmpty()) { "Key name cannot be empty" }
        val cleanVal = value.trim()
        synchronized(lock) {
            if (cleanVal.isEmpty()) inMemory.remove(cleanKey) else inMemory[cleanKey] = cleanVal
            saveToDiskLocked()
        }
        publish(if (cleanVal.isEmpty()) "Removed $cleanKey" else "Saved $cleanKey securely")
    }

    fun removeSecret(keyName: String) {
        val cleanKey = sanitizeKeyName(keyName)
        synchronized(lock) {
            inMemory.remove(cleanKey)
            saveToDiskLocked()
        }
        publish("Removed $cleanKey")
    }

    fun getSecret(keyName: String): String? {
        val cleanKey = sanitizeKeyName(keyName)
        val local = synchronized(lock) { inMemory[cleanKey] }
        if (!local.isNullOrBlank()) return local
        val envVal = System.getenv(cleanKey)?.trim()
        if (!envVal.isNullOrBlank() && envVal != PLACEHOLDER_KEY) return envVal
        return null
    }

    fun resolveDisbandApiKey(): String? = getSecret(KEY_DISBAND_API_KEY)?.takeIf { it.isNotBlank() }

    fun resolveDisbandBaseUrl(): String {
        val custom = getSecret(KEY_DISBAND_BASE_URL)?.trim()?.trimEnd('/')
        return custom?.takeIf { it.startsWith("http") } ?: DEFAULT_DISBAND_BASE_URL
    }

    private fun saveToDiskLocked() {
        runCatching {
            storeFile.parentFile?.mkdirs()
            val props = Properties()
            inMemory.forEach { (k, v) -> props.setProperty(k, v) }
            storeFile.outputStream().use { props.store(it, "Samplfie2 Workstation Secrets Vault") }
            runCatching {
                Files.setPosixFilePermissions(
                    storeFile.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                )
            }
        }
    }

    private fun publish(note: String) {
        val list = mutableListOf<DesktopSecretEntry>()
        val envDisband = System.getenv(KEY_DISBAND_API_KEY)?.trim()?.takeIf { it.isNotBlank() && it != PLACEHOLDER_KEY }
        var vaultDisband: String?
        synchronized(lock) {
            val localDisband = inMemory[KEY_DISBAND_API_KEY]
            vaultDisband = localDisband
            if (localDisband != null) {
                list += DesktopSecretEntry(
                    keyName = KEY_DISBAND_API_KEY,
                    maskedValue = maskSecret(localDisband),
                    sourceLabel = "Local Vault (POSIX 600)",
                    isReadOnlyEnv = false
                )
            } else if (envDisband != null) {
                list += DesktopSecretEntry(
                    keyName = KEY_DISBAND_API_KEY,
                    maskedValue = maskSecret(envDisband),
                    sourceLabel = "Environment DISBAND_API_KEY",
                    isReadOnlyEnv = true
                )
            }
            inMemory.forEach { (k, v) ->
                if (k != KEY_DISBAND_API_KEY) {
                    list += DesktopSecretEntry(
                        keyName = k,
                        maskedValue = if (k == KEY_DISBAND_BASE_URL) v else maskSecret(v),
                        sourceLabel = "Local Vault",
                        isReadOnlyEnv = false
                    )
                }
            }
        }
        val active = resolveDisbandApiKey()
        val src = when {
            vaultDisband != null -> "VAULT KEY SET"
            envDisband != null -> "ENV KEY SET"
            else -> "NOT CONFIGURED"
        }
        _state.value = DesktopSecretsState(
            entries = list,
            hasDisbandKey = !active.isNullOrBlank(),
            disbandKeySource = src,
            disbandBaseUrl = resolveDisbandBaseUrl(),
            statusNote = note
        )
    }

    companion object {
        const val KEY_DISBAND_API_KEY = "DISBAND_API_KEY"
        const val KEY_DISBAND_BASE_URL = "DISBAND_BASE_URL"
        const val DEFAULT_DISBAND_BASE_URL = "https://api.disband.io/v1"
        private const val PLACEHOLDER_KEY = "DISBAND_API_KEY_PLACEHOLDER"

        fun sanitizeKeyName(raw: String): String =
            raw.trim().uppercase().replace(Regex("[^A-Z0-9_.-]"), "_")

        fun maskSecret(secret: String): String {
            val t = secret.trim()
            if (t.isEmpty()) return ""
            if (t.length <= 6) return "••••••"
            return "${t.take(3)}••••••${t.takeLast(4)}"
        }
    }
}
