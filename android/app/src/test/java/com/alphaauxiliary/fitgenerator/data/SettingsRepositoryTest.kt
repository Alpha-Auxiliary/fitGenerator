package com.alphaauxiliary.fitgenerator.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alphaauxiliary.fitgenerator.map.CoordinateSystem
import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import com.alphaauxiliary.fitgenerator.map.MapProvider
import com.alphaauxiliary.fitgenerator.map.ProviderKeyPlacement
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var secretStore: FakeSecretStore
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(temporaryFolder.root, "settings.preferences_pb") },
        )
        secretStore = FakeSecretStore()
        repository = SettingsRepository(dataStore, secretStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `missing data yields the zero-configuration OSM defaults`() = runTest {
        val loaded = repository.load()

        assertEquals(AppSettings.Default, loaded)
        assertEquals(MapProvider.OPEN_STREET_MAP_ID, loaded.activeMapProviderId)
        assertTrue(loaded.customMapProviders.isEmpty())
        assertNull(loaded.previewSeed)
    }

    @Test
    fun `parameters and custom providers round trip without losing the seed`() = runTest {
        val provider = createCustomProvider(apiKey = "round-trip-secret")
        val expected = AppSettings(
            activeMapProviderId = provider.id,
            customMapProviders = listOf(provider),
            mapCamera = MapCameraSettings(
                centerWgs84 = GeoCoordinate(latitude = 31.2304, longitude = 121.4737),
                zoom = 13.5,
            ),
            paceSecondsPerKilometer = 325.0,
            restingHeartRate = 58,
            maximumHeartRate = 188,
            lapCount = 4,
            exportCount = 3,
            previewSeed = ULong.MAX_VALUE,
        )

        repository.save(expected)

        assertEquals(expected, repository.load())
        assertEquals("round-trip-secret", secretStore.values[provider.id])
    }

    @Test
    fun `unknown schema preserves the exact raw payload and returns defaults`() = runTest {
        val rawPayload = """{"schemaVersion":99,"futureValue":"keep exactly"}"""
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("settings_payload_v1")] = rawPayload
            preferences[intPreferencesKey("schema_version")] = 99
        }

        val loaded = repository.load()
        val persisted = dataStore.data.first()

        assertEquals(AppSettings.Default, loaded)
        assertEquals(
            rawPayload,
            persisted[stringPreferencesKey("settings_invalid_backup")],
        )
        assertNull(persisted[stringPreferencesKey("settings_payload_v1")])
        assertNull(persisted[intPreferencesKey("schema_version")])
    }

    @Test
    fun `plaintext map keys never enter Preferences DataStore`() = runTest {
        val plaintextKey = "task-4-plaintext-secret"
        val provider = createCustomProvider(apiKey = plaintextKey)

        repository.save(
            AppSettings(
                activeMapProviderId = provider.id,
                customMapProviders = listOf(provider),
            ),
        )

        val persistedValues = dataStore.data.first()
            .asMap()
            .values
            .joinToString(separator = "|")
        assertFalse(persistedValues.contains(plaintextKey))
        assertTrue(persistedValues.contains(provider.displayName))
    }

    @Test
    fun `failed key decryption removes only that key and leaves route and settings`() = runTest {
        val provider = createCustomProvider(apiKey = "will-fail-after-restart")
        val expectedRoute = SavedRoute(
            wgs84Points = listOf(
                GeoCoordinate(latitude = 39.9042, longitude = 116.4074),
                GeoCoordinate(latitude = 39.9052, longitude = 116.4084),
            ),
        )
        val routeRepository = RouteRepository(
            storageDirectory = temporaryFolder.newFolder("route"),
            atomicFileMover = JvmAtomicFileMover,
            dispatcher = Dispatchers.Unconfined,
        )
        routeRepository.save(expectedRoute)
        repository.save(
            AppSettings(
                activeMapProviderId = provider.id,
                customMapProviders = listOf(provider),
                lapCount = 7,
                previewSeed = 42uL,
            ),
        )
        secretStore.failReads += provider.id

        val loadedSettings = repository.load()

        assertEquals(7, loadedSettings.lapCount)
        assertEquals(42uL, loadedSettings.previewSeed)
        assertNull(loadedSettings.customMapProviders.single().apiKey)
        assertTrue(provider.id in secretStore.removedIds)
        assertEquals(expectedRoute, routeRepository.load())
    }

    @Test
    fun `restoring OSM keeps saved custom provider definitions`() = runTest {
        val provider = createCustomProvider(apiKey = "still-saved")
        repository.save(
            AppSettings(
                activeMapProviderId = MapProvider.OPEN_STREET_MAP_ID,
                customMapProviders = listOf(provider),
            ),
        )

        val loaded = repository.load()

        assertEquals(MapProvider.OPEN_STREET_MAP_ID, loaded.activeMapProviderId)
        assertEquals(listOf(provider), loaded.customMapProviders)
    }

    @Test
    fun `failed multi-provider secret update restores earlier keys and keeps settings`() = runTest {
        val first = createCustomProvider(id = "first-map", apiKey = "first-old")
        val second = createCustomProvider(id = "second-map", apiKey = "second-old")
        val original = AppSettings(
            activeMapProviderId = first.id,
            customMapProviders = listOf(first, second),
        )
        repository.save(original)
        secretStore.failWrites += second.id

        var failure: Throwable? = null
        try {
            repository.save(
                original.copy(
                    customMapProviders = listOf(
                        first.copy(apiKey = "first-new"),
                        second.copy(apiKey = "second-new"),
                    ),
                ),
            )
        } catch (error: Throwable) {
            failure = error
        }

        assertTrue(failure is SettingsRepositoryException)
        assertEquals("first-old", secretStore.values[first.id])
        assertEquals("second-old", secretStore.values[second.id])
        assertEquals(original, repository.load())
    }

    private fun createCustomProvider(
        id: String = "custom-map",
        apiKey: String,
    ): MapProvider = MapProvider.custom(
        id = id,
        displayName = "Custom Map",
        xyzUrlTemplate = "https://tiles.example.test/{z}/{x}/{y}.png",
        coordinateSystem = CoordinateSystem.GCJ02,
        maxZoom = 18,
        attribution = "Example attribution",
        geocoderUrl = "https://search.example.test/lookup",
        keyPlacement = ProviderKeyPlacement.HEADER,
        keyName = "X-Api-Key",
        apiKey = apiKey,
    )

    private class FakeSecretStore : ProviderSecretStore {
        val values = mutableMapOf<String, String>()
        val failReads = mutableSetOf<String>()
        val failWrites = mutableSetOf<String>()
        val removedIds = mutableSetOf<String>()

        override suspend fun read(providerId: String): String? {
            if (providerId in failReads) {
                throw SecretStoreException("simulated decryption failure")
            }
            return values[providerId]
        }

        override suspend fun write(providerId: String, apiKey: String?) {
            if (providerId in failWrites) {
                throw SecretStoreException("simulated encryption failure")
            }
            if (apiKey.isNullOrBlank()) {
                values.remove(providerId)
            } else {
                values[providerId] = apiKey
            }
        }

        override suspend fun remove(providerId: String) {
            removedIds += providerId
            values.remove(providerId)
        }
    }

    private object JvmAtomicFileMover : AtomicFileMover {
        override fun replace(source: File, destination: File) {
            try {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: Exception) {
                throw IOException("Atomic test move failed", error)
            }
        }
    }
}
