package com.example.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.secretsVaultDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "samplfie2_secrets_vault"
)

data class StoredSecretEntry(
    val keyName: String,
    val maskedValue: String,
    val providerHint: String,
    val isFromBuildConfig: Boolean = false
)

data class SecretsVaultState(
    val entries: List<StoredSecretEntry> = emptyList(),
    val hasDisbandKey: Boolean = false,
    val disbandKeySource: String = "NOT CONFIGURED",
    val disbandBaseUrl: String = SecretsVaultRepository.DEFAULT_DISBAND_BASE_URL,
    val statusNote: String = "Store Disband API key or custom tokens securely on device"
)

/**
 * Local encrypted Secrets & Keys Vault for API credentials (e.g. DISBAND_API_KEY, DISBAND_BASE_URL,
 * and custom workstation tokens). Encrypts entries via AndroidKeyStore AES-256-GCM when available,
 * with a BuildConfig Secrets-plugin fallback.
 */
class SecretsVaultRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inMemoryCache = LinkedHashMap<String, Pair<String, String>>() // keyName -> (secret, providerHint)
    private val lock = Any()

    private val _state = MutableStateFlow(SecretsVaultState())
    val state: StateFlow<SecretsVaultState> = _state.asStateFlow()

    init {
        refreshBuildConfigOnlyState()
        scope.launch {
            loadFromDataStore()
        }
    }

    private fun refreshBuildConfigOnlyState() {
        publishSnapshot("Vault ready")
    }

    suspend fun loadFromDataStore() {
        runCatching {
            val prefs = context.secretsVaultDataStore.data.first()
            val serialized = prefs[KEY_VAULT_BLOB] ?: return@runCatching
            val loaded = decodeVaultBlob(serialized)
            synchronized(lock) {
                loaded.forEach { (k, pair) ->
                    inMemoryCache.putIfAbsent(k, pair)
                }
            }
            publishSnapshot("Loaded ${inMemoryCache.size} vault key(s)")
        }
    }

    suspend fun putSecret(keyName: String, value: String, providerHint: String = inferHint(keyName)) {
        val cleanKey = sanitizeKeyName(keyName)
        val cleanVal = value.trim()
        require(cleanKey.isNotEmpty()) { "Key name cannot be empty" }
        synchronized(lock) {
            if (cleanVal.isEmpty()) {
                inMemoryCache.remove(cleanKey)
            } else {
                inMemoryCache[cleanKey] = cleanVal to providerHint.ifBlank { inferHint(cleanKey) }
            }
        }
        publishSnapshot(if (cleanVal.isEmpty()) "Removed $cleanKey" else "Saved $cleanKey securely")
        persistToDataStore()
    }

    suspend fun removeSecret(keyName: String) {
        val cleanKey = sanitizeKeyName(keyName)
        synchronized(lock) {
            inMemoryCache.remove(cleanKey)
        }
        publishSnapshot("Removed $cleanKey")
        persistToDataStore()
    }

    fun getSecret(keyName: String): String? {
        val cleanKey = sanitizeKeyName(keyName)
        val stored = synchronized(lock) { inMemoryCache[cleanKey]?.first }
        if (!stored.isNullOrBlank()) return stored
        if (cleanKey == KEY_DISBAND_API_KEY) {
            return resolveBuildConfigDisbandKey()
        }
        return null
    }

    fun resolveDisbandApiKey(): String? = getSecret(KEY_DISBAND_API_KEY)?.takeIf { it.isNotBlank() }

    fun resolveDisbandBaseUrl(): String {
        val stored = synchronized(lock) { inMemoryCache[KEY_DISBAND_BASE_URL]?.first }
        return stored?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") }
            ?: DEFAULT_DISBAND_BASE_URL
    }

    private fun resolveBuildConfigDisbandKey(): String? {
        val bc = BuildConfig.DISBAND_API_KEY.trim()
        return bc.takeIf { it.isNotEmpty() && it != DISBAND_PLACEHOLDER_VALUE }
    }

    private fun publishSnapshot(note: String) {
        val list = mutableListOf<StoredSecretEntry>()
        val bcKey = resolveBuildConfigDisbandKey()
        synchronized(lock) {
            val userDisband = inMemoryCache[KEY_DISBAND_API_KEY]?.first
            if (userDisband != null) {
                list += StoredSecretEntry(
                    keyName = KEY_DISBAND_API_KEY,
                    maskedValue = maskSecret(userDisband),
                    providerHint = "Disband Stem API (Vault)",
                    isFromBuildConfig = false
                )
            } else if (bcKey != null) {
                list += StoredSecretEntry(
                    keyName = KEY_DISBAND_API_KEY,
                    maskedValue = maskSecret(bcKey),
                    providerHint = "Disband Stem API (.env Secret)",
                    isFromBuildConfig = true
                )
            }

            inMemoryCache.forEach { (k, pair) ->
                if (k != KEY_DISBAND_API_KEY) {
                    list += StoredSecretEntry(
                        keyName = k,
                        maskedValue = if (k == KEY_DISBAND_BASE_URL) pair.first else maskSecret(pair.first),
                        providerHint = pair.second,
                        isFromBuildConfig = false
                    )
                }
            }
        }
        val activeDisband = resolveDisbandApiKey()
        val disbandSrc = when {
            synchronized(lock) { inMemoryCache[KEY_DISBAND_API_KEY] != null } -> "VAULT KEY SET"
            bcKey != null -> "BUILDCONFIG SECRET SET"
            else -> "NOT CONFIGURED"
        }
        _state.value = SecretsVaultState(
            entries = list,
            hasDisbandKey = !activeDisband.isNullOrBlank(),
            disbandKeySource = disbandSrc,
            disbandBaseUrl = resolveDisbandBaseUrl(),
            statusNote = note
        )
    }

    private suspend fun persistToDataStore() {
        val blob = synchronized(lock) { encodeVaultBlob(inMemoryCache) }
        runCatching {
            context.secretsVaultDataStore.edit { prefs ->
                prefs[KEY_VAULT_BLOB] = blob
            }
        }
    }

    private fun encodeVaultBlob(map: Map<String, Pair<String, String>>): String {
        val plain = buildString {
            map.forEach { (k, pair) ->
                val ek = Base64.encodeToString(k.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val ev = Base64.encodeToString(pair.first.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val eh = Base64.encodeToString(pair.second.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                append(ek).append('\t').append(ev).append('\t').append(eh).append('\n')
            }
        }
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)
        return "V1:" + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decodeVaultBlob(blob: String): Map<String, Pair<String, String>> {
        if (!blob.startsWith("V1:")) return emptyMap()
        val packed = Base64.decode(blob.removePrefix("V1:"), Base64.NO_WRAP)
        if (packed.size < 14) return emptyMap()
        val ivLen = packed[0].toInt() and 0xFF
        if (1 + ivLen >= packed.size) return emptyMap()
        val iv = packed.copyOfRange(1, 1 + ivLen)
        val cipherBytes = packed.copyOfRange(1 + ivLen, packed.size)
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val plain = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        val out = LinkedHashMap<String, Pair<String, String>>()
        plain.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t')
            if (parts.size >= 2) {
                val k = String(Base64.decode(parts[0], Base64.NO_WRAP), Charsets.UTF_8)
                val v = String(Base64.decode(parts[1], Base64.NO_WRAP), Charsets.UTF_8)
                val h = if (parts.size >= 3) String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8) else inferHint(k)
                if (k.isNotBlank() && v.isNotBlank()) out[k] = v to h
            }
        }
        return out
    }

    private fun getOrCreateAesKey(): SecretKey {
        val androidKey = runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
            if (existing != null) return@runCatching existing
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            gen.generateKey()
        }.getOrNull()
        if (androidKey != null) return androidKey

        // JVM/Robolectric test fallback where AndroidKeyStore provider is absent
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("samplfie2-vault:" + context.packageName).toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest, "AES")
    }

    companion object {
        const val KEY_DISBAND_API_KEY = "DISBAND_API_KEY"
        const val KEY_DISBAND_BASE_URL = "DISBAND_BASE_URL"
        const val DEFAULT_DISBAND_BASE_URL = "https://api.disband.io/v1"
        private const val DISBAND_PLACEHOLDER_VALUE = "DISBAND_API_KEY_PLACEHOLDER"
        private const val KEYSTORE_ALIAS = "samplfie2_secrets_aes_gcm"
        private val KEY_VAULT_BLOB = stringPreferencesKey("encrypted_vault_blob")

        fun sanitizeKeyName(raw: String): String =
            raw.trim().uppercase().replace(Regex("[^A-Z0-9_.-]"), "_")

        fun inferHint(keyName: String): String = when (sanitizeKeyName(keyName)) {
            KEY_DISBAND_API_KEY -> "Disband Stem API"
            KEY_DISBAND_BASE_URL -> "Disband Endpoint Override"
            else -> "Custom Studio Key"
        }

        fun maskSecret(secret: String): String {
            val t = secret.trim()
            if (t.isEmpty()) return ""
            if (t.length <= 6) return "••••••"
            return "${t.take(3)}••••••${t.takeLast(4)}"
        }
    }
}
