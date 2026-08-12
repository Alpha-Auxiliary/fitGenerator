package com.alphaauxiliary.fitgenerator.data

import android.content.Context
import android.security.keystore.KeyInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var secretStore: SecretStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deleteTestKey()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStoreFile = File(
            context.cacheDir,
            "secret-store-${UUID.randomUUID()}.preferences_pb",
        )
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dataStoreFile },
        )
        secretStore = SecretStore(dataStore, keyAlias = TEST_KEY_ALIAS)
    }

    @After
    fun tearDown() {
        runBlocking {
            dataStore.edit { preferences -> preferences.clear() }
        }
        dataStoreScope.cancel()
        dataStoreFile.delete()
        deleteTestKey()
    }

    @Test
    fun AES_GCM_round_trip_stores_only_randomized_ciphertext() = runBlocking {
        val providerId = "instrumented-provider"
        val plaintext = "instrumented-map-secret"
        val preferenceKey = stringPreferencesKey(secretStore.preferenceName(providerId))

        secretStore.write(providerId, plaintext)
        val firstCiphertext = dataStore.data.first()[preferenceKey]
        secretStore.write(providerId, plaintext)
        val secondCiphertext = dataStore.data.first()[preferenceKey]

        assertEquals(plaintext, secretStore.read(providerId))
        assertTrue(firstCiphertext?.isNotBlank() == true)
        assertTrue(secondCiphertext?.isNotBlank() == true)
        assertFalse(firstCiphertext.orEmpty().contains(plaintext))
        assertFalse(secondCiphertext.orEmpty().contains(plaintext))
        assertNotEquals(firstCiphertext, secondCiphertext)
    }

    @Test
    fun ciphertext_cannot_be_swapped_between_providers_and_only_bad_key_is_removed() =
        runBlocking {
            val firstId = "provider-a"
            val secondId = "provider-b"
            val firstPreference = stringPreferencesKey(secretStore.preferenceName(firstId))
            val secondPreference = stringPreferencesKey(secretStore.preferenceName(secondId))
            secretStore.write(firstId, "first-secret")
            secretStore.write(secondId, "second-secret")
            val secondCiphertext = checkNotNull(dataStore.data.first()[secondPreference])
            dataStore.edit { preferences ->
                preferences[firstPreference] = secondCiphertext
            }

            assertNull(secretStore.read(firstId))
            assertEquals("second-secret", secretStore.read(secondId))
            val afterRecovery = dataStore.data.first()
            assertNull(afterRecovery[firstPreference])
            assertTrue(afterRecovery[secondPreference]?.isNotBlank() == true)
        }

    @Test
    fun keystore_key_uses_AES_GCM_without_user_authentication() = runBlocking {
        secretStore.write("provider-auth-contract", "secret")

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(TEST_KEY_ALIAS, null) as SecretKey
        val keyFactory = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        val keyInfo = keyFactory.getKeySpec(key, KeyInfo::class.java) as KeyInfo

        assertEquals("AES", key.algorithm)
        assertFalse(keyInfo.isUserAuthenticationRequired())
    }

    private fun deleteTestKey() {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(TEST_KEY_ALIAS)) {
            keyStore.deleteEntry(TEST_KEY_ALIAS)
        }
    }

    private companion object {
        const val TEST_KEY_ALIAS = "fit-generator-map-keys-instrumented-test-v1"
    }
}
