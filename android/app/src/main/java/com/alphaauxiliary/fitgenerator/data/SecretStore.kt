package com.alphaauxiliary.fitgenerator.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val GCM_IV_BYTES = 12
private val KEYSTORE_MUTEX = Mutex()

internal interface ProviderSecretStore {
    suspend fun read(providerId: String): String?

    suspend fun write(providerId: String, apiKey: String?)

    suspend fun remove(providerId: String)
}

internal class SecretStoreException(message: String) : Exception(message)

internal class SecretStore(
    private val dataStore: DataStore<Preferences>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val keyAlias: String = KEY_ALIAS,
) : ProviderSecretStore {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.fitGeneratorSettingsDataStore,
    )

    private val operationMutex = KEYSTORE_MUTEX

    init {
        require(keyAlias.isNotBlank() && keyAlias.none { it.code < 0x20 || it.code == 0x7f })
    }

    override suspend fun read(providerId: String): String? = operationMutex.withLock {
        validateProviderId(providerId)
        val preferenceKey = preferenceKey(providerId)
        val encodedPayload = try {
            dataStore.data.first()[preferenceKey]
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            throw SecretStoreException(READ_ERROR_MESSAGE)
        } ?: return@withLock null

        try {
            withContext(dispatcher) {
                decrypt(providerId, encodedPayload)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            removePreference(preferenceKey)
            null
        }
    }

    override suspend fun write(providerId: String, apiKey: String?) {
        operationMutex.withLock {
            validateProviderId(providerId)
            if (apiKey.isNullOrBlank()) {
                removePreference(preferenceKey(providerId))
                return@withLock
            }
            val sizeProbe = apiKey.toByteArray(Charsets.UTF_8)
            try {
                if (sizeProbe.size > MAXIMUM_PLAINTEXT_BYTES) {
                    throw SecretStoreException(WRITE_ERROR_MESSAGE)
                }
            } finally {
                sizeProbe.fill(0)
            }

            val encodedPayload = try {
                withContext(dispatcher) {
                    encrypt(providerId, apiKey)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                throw SecretStoreException(WRITE_ERROR_MESSAGE)
            }
            try {
                dataStore.edit { preferences ->
                    preferences[preferenceKey(providerId)] = encodedPayload
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                throw SecretStoreException(WRITE_ERROR_MESSAGE)
            }
        }
    }

    override suspend fun remove(providerId: String) {
        operationMutex.withLock {
            validateProviderId(providerId)
            removePreference(preferenceKey(providerId))
        }
    }

    internal fun preferenceName(providerId: String): String {
        validateProviderId(providerId)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                val value = byte.toInt() and 0xff
                "${HEX_DIGITS[value ushr 4]}${HEX_DIGITS[value and 0x0f]}"
            }
        return "$PREFERENCE_PREFIX$digest"
    }

    private suspend fun removePreference(key: Preferences.Key<String>) {
        try {
            dataStore.edit { preferences -> preferences.remove(key) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            throw SecretStoreException(REMOVE_ERROR_MESSAGE)
        }
    }

    private fun encrypt(providerId: String, apiKey: String): String {
        val plaintext = apiKey.toByteArray(Charsets.UTF_8)
        var ciphertext: ByteArray? = null
        var payload: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(associatedData(providerId))
            ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv.size != GCM_IV_BYTES) {
                throw GeneralSecurityException("Invalid AES-GCM IV")
            }
            payload = ByteArray(PAYLOAD_HEADER_BYTES + iv.size + ciphertext.size).apply {
                this[0] = PAYLOAD_VERSION
                this[1] = iv.size.toByte()
                iv.copyInto(this, destinationOffset = PAYLOAD_HEADER_BYTES)
                ciphertext.copyInto(this, destinationOffset = PAYLOAD_HEADER_BYTES + iv.size)
            }
            return Base64.encodeToString(payload, Base64.NO_WRAP)
        } finally {
            plaintext.fill(0)
            ciphertext?.fill(0)
            payload?.fill(0)
        }
    }

    private fun decrypt(providerId: String, encodedPayload: String): String {
        if (encodedPayload.length > MAXIMUM_ENCODED_PAYLOAD_CHARS) {
            throw GeneralSecurityException("Encrypted map key is too large")
        }
        val payload = Base64.decode(encodedPayload, Base64.NO_WRAP)
        var plaintext: ByteArray? = null
        try {
            if (payload.size <= PAYLOAD_HEADER_BYTES + MINIMUM_GCM_TAG_BYTES) {
                throw GeneralSecurityException("Encrypted map key is incomplete")
            }
            if (payload[0] != PAYLOAD_VERSION) {
                throw GeneralSecurityException("Encrypted map key version is unsupported")
            }
            val ivSize = payload[1].toInt() and 0xff
            if (
                ivSize != GCM_IV_BYTES ||
                payload.size <= PAYLOAD_HEADER_BYTES + ivSize + MINIMUM_GCM_TAG_BYTES
            ) {
                throw GeneralSecurityException("Encrypted map key has an invalid IV")
            }
            val ivStart = PAYLOAD_HEADER_BYTES
            val ciphertextStart = ivStart + ivSize
            val iv = payload.copyOfRange(ivStart, ciphertextStart)
            val ciphertext = payload.copyOfRange(ciphertextStart, payload.size)
            try {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getExistingKey(),
                    GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv),
                )
                cipher.updateAAD(associatedData(providerId))
                plaintext = cipher.doFinal(ciphertext)
                if (plaintext.isEmpty() || plaintext.size > MAXIMUM_PLAINTEXT_BYTES) {
                    throw GeneralSecurityException("Decrypted map key has an invalid length")
                }
                return decodeUtf8Strict(plaintext)
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            payload.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEYSTORE_LOCK) {
        val keyStore = loadKeyStore()
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKey()
    }

    private fun getExistingKey(): SecretKey = synchronized(KEYSTORE_LOCK) {
        loadKeyStore().getKey(keyAlias, null) as? SecretKey
            ?: throw GeneralSecurityException("Android Keystore key is unavailable")
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private fun associatedData(providerId: String): ByteArray =
        "$keyAlias\u0000${PAYLOAD_VERSION.toInt()}\u0000$providerId".toByteArray(Charsets.UTF_8)

    private fun decodeUtf8Strict(value: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()
    } catch (_: CharacterCodingException) {
        throw GeneralSecurityException("Decrypted map key is not UTF-8")
    }

    private fun preferenceKey(providerId: String): Preferences.Key<String> =
        stringPreferencesKey(preferenceName(providerId))

    private fun validateProviderId(providerId: String) {
        if (!PROVIDER_ID.matches(providerId)) {
            throw SecretStoreException("地图源标识无效")
        }
    }

    companion object {
        const val KEY_ALIAS = "fit-generator-map-keys-v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFERENCE_PREFIX = "encrypted_map_key_"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val MINIMUM_GCM_TAG_BYTES = 16
        private const val PAYLOAD_HEADER_BYTES = 2
        private const val MAXIMUM_PLAINTEXT_BYTES = 4 * 1024
        private const val MAXIMUM_ENCODED_PAYLOAD_CHARS = 8 * 1024
        private const val READ_ERROR_MESSAGE = "无法读取已保护的地图 Key"
        private const val WRITE_ERROR_MESSAGE = "无法安全保存地图 Key"
        private const val REMOVE_ERROR_MESSAGE = "无法移除失效的地图 Key"
        private const val HEX_DIGITS = "0123456789abcdef"
        private val PAYLOAD_VERSION: Byte = 1
        private val PROVIDER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val KEYSTORE_LOCK = Any()
    }
}
