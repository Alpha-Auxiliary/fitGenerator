package com.alphaauxiliary.fitgenerator.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alphaauxiliary.fitgenerator.map.CoordinateSystem
import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import com.alphaauxiliary.fitgenerator.map.MapProvider
import com.alphaauxiliary.fitgenerator.map.ProviderKeyPlacement
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val SETTINGS_MUTATION_MUTEX = Mutex()

internal val Context.fitGeneratorSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "fit-generator-settings")

internal class SettingsRepositoryException(message: String) : Exception(message)

internal interface AppSettingsRepository {
    suspend fun load(): AppSettings

    suspend fun save(settings: AppSettings)
}

internal class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val secretStore: ProviderSecretStore,
) : AppSettingsRepository {
    private val mutationMutex = SETTINGS_MUTATION_MUTEX
    constructor(context: Context) : this(
        dataStore = context.applicationContext.fitGeneratorSettingsDataStore,
        secretStore = SecretStore(
            context = context.applicationContext,
        ),
    )

    private val operationMutex = Mutex()

    override suspend fun load(): AppSettings = operationMutex.withLock {
        val preferences = try {
            dataStore.data.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            return@withLock AppSettings.Default
        }

        val rawPayload = try {
            preferences[SETTINGS_PAYLOAD]
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            null
        }
        if (rawPayload == null) {
            if (containsKnownSettings(preferences)) {
                backupAndClear(snapshotKnownPreferences(preferences))
            }
            return@withLock AppSettings.Default
        }

        val restored = try {
            restore(SettingsJson.decodeFromString<SettingsPayloadDto>(rawPayload))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            backupAndClear(rawPayload)
            return@withLock AppSettings.Default
        }

        restored.copy(
            customMapProviders = restored.customMapProviders.map { provider ->
                provider.copy(apiKey = readSecretOrNull(provider.id))
            },
        )
    }

    override suspend fun save(settings: AppSettings) = mutationMutex.withLock {
        operationMutex.withLock {
            try {
                validateSettings(settings)
                val payload = prepare(settings)
                val rawPayload = SettingsJson.encodeToString(payload)
                val previousProviderIds = readPersistedProviderIds()
                val currentProviderIds = settings.customMapProviders.mapTo(linkedSetOf()) { it.id }
                val previousSecrets = currentProviderIds.associateWith { providerId ->
                    secretStore.read(providerId)
                }

                withContext(NonCancellable) {
                    try {
                        settings.customMapProviders.forEach { provider ->
                            secretStore.write(provider.id, provider.apiKey)
                        }

                        dataStore.edit { preferences ->
                            preferences[SETTINGS_SCHEMA_VERSION] = settings.schemaVersion
                            preferences[ACTIVE_PROVIDER_ID] = settings.activeMapProviderId
                            preferences[CAMERA_LATITUDE] = settings.mapCamera.centerWgs84.latitude
                            preferences[CAMERA_LONGITUDE] = settings.mapCamera.centerWgs84.longitude
                            preferences[CAMERA_ZOOM] = settings.mapCamera.zoom
                            preferences[PACE_SECONDS_PER_KILOMETER] =
                                settings.paceSecondsPerKilometer
                            preferences[RESTING_HEART_RATE] = settings.restingHeartRate
                            preferences[MAXIMUM_HEART_RATE] = settings.maximumHeartRate
                            preferences[LAP_COUNT] = settings.lapCount
                            preferences[EXPORT_COUNT] = settings.exportCount
                            preferences[CUSTOM_PROVIDERS_PAYLOAD] = SettingsJson.encodeToString(
                                payload.customMapProviders,
                            )
                            settings.previewSeed?.let { seed ->
                                preferences[PREVIEW_SEED] = seed.toString()
                            } ?: preferences.remove(PREVIEW_SEED)
                            preferences[SETTINGS_PAYLOAD] = rawPayload
                        }
                    } catch (exception: Exception) {
                        restoreSecretsBestEffort(previousSecrets)
                        throw exception
                    }
                    (previousProviderIds - currentProviderIds).forEach { removedId ->
                        try {
                            secretStore.remove(removedId)
                        } catch (_: Exception) {
                            // The removed provider can no longer address this encrypted value.
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SettingsRepositoryException) {
                throw SettingsRepositoryException(SAVE_ERROR_MESSAGE)
            } catch (_: SecretStoreException) {
                throw SettingsRepositoryException(SAVE_ERROR_MESSAGE)
            } catch (_: IOException) {
                throw SettingsRepositoryException(SAVE_ERROR_MESSAGE)
            } catch (_: SerializationException) {
                throw SettingsRepositoryException(SAVE_ERROR_MESSAGE)
            } catch (_: IllegalArgumentException) {
                throw SettingsRepositoryException(SAVE_ERROR_MESSAGE)
            }
        }
    }

    private suspend fun restoreSecretsBestEffort(previousSecrets: Map<String, String?>) {
        previousSecrets.forEach { (providerId, previousValue) ->
            try {
                secretStore.write(providerId, previousValue)
            } catch (_: Exception) {
                // Preserve the original actionable save failure; a later edit can retry cleanup.
            }
        }
    }

    private suspend fun readSecretOrNull(providerId: String): String? = try {
        secretStore.read(providerId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: SecretStoreException) {
        try {
            secretStore.remove(providerId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecretStoreException) {
            // The nonsecret provider and all route data remain usable even if cleanup fails.
        }
        null
    }

    private suspend fun readPersistedProviderIds(): Set<String> {
        val rawPayload = try {
            dataStore.data.first()[SETTINGS_PAYLOAD]
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            return emptySet()
        } ?: return emptySet()
        return try {
            SettingsJson.decodeFromString<SettingsPayloadDto>(rawPayload)
                .customMapProviders
                .mapTo(linkedSetOf()) { it.id }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            emptySet()
        }
    }

    private suspend fun backupAndClear(rawPayload: String) {
        try {
            dataStore.edit { preferences ->
                preferences[INVALID_SETTINGS_BACKUP] = rawPayload
                preferences.remove(SETTINGS_SCHEMA_VERSION)
                preferences.remove(ACTIVE_PROVIDER_ID)
                preferences.remove(CAMERA_LATITUDE)
                preferences.remove(CAMERA_LONGITUDE)
                preferences.remove(CAMERA_ZOOM)
                preferences.remove(PACE_SECONDS_PER_KILOMETER)
                preferences.remove(RESTING_HEART_RATE)
                preferences.remove(MAXIMUM_HEART_RATE)
                preferences.remove(LAP_COUNT)
                preferences.remove(EXPORT_COUNT)
                preferences.remove(PREVIEW_SEED)
                preferences.remove(CUSTOM_PROVIDERS_PAYLOAD)
                preferences.remove(SETTINGS_PAYLOAD)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            // Loading must remain available even if a corrupt payload cannot be backed up.
        }
    }

    private fun prepare(settings: AppSettings): SettingsPayloadDto = SettingsPayloadDto(
        schemaVersion = settings.schemaVersion,
        activeMapProviderId = settings.activeMapProviderId,
        mapCamera = MapCameraPayloadDto(
            centerWgs84Latitude = settings.mapCamera.centerWgs84.latitude,
            centerWgs84Longitude = settings.mapCamera.centerWgs84.longitude,
            zoom = settings.mapCamera.zoom,
        ),
        paceSecondsPerKilometer = settings.paceSecondsPerKilometer,
        restingHeartRate = settings.restingHeartRate,
        maximumHeartRate = settings.maximumHeartRate,
        lapCount = settings.lapCount,
        exportCount = settings.exportCount,
        previewSeed = settings.previewSeed?.toString(),
        customMapProviders = settings.customMapProviders.map(::prepareProvider),
    )

    private fun prepareProvider(provider: MapProvider): CustomMapProviderPayloadDto =
        CustomMapProviderPayloadDto(
            id = provider.id,
            displayName = provider.displayName,
            styleUrl = provider.styleUrl,
            xyzUrlTemplate = provider.xyzUrlTemplate,
            keyPlacement = provider.keyPlacement.name,
            keyName = provider.keyName,
            coordinateSystem = provider.coordinateSystem.name,
            maxZoom = provider.maxZoom,
            attribution = provider.attribution,
            geocoderUrl = provider.geocoderUrl,
        )

    private fun restore(payload: SettingsPayloadDto): AppSettings {
        if (payload.schemaVersion != AppSettings.CURRENT_SCHEMA_VERSION) {
            throw SettingsRepositoryException("不支持的设置数据版本")
        }
        val settings = AppSettings(
            schemaVersion = payload.schemaVersion,
            activeMapProviderId = payload.activeMapProviderId,
            customMapProviders = payload.customMapProviders.map(::restoreProvider),
            mapCamera = MapCameraSettings(
                centerWgs84 = GeoCoordinate(
                    latitude = payload.mapCamera.centerWgs84Latitude,
                    longitude = payload.mapCamera.centerWgs84Longitude,
                ),
                zoom = payload.mapCamera.zoom,
            ),
            paceSecondsPerKilometer = payload.paceSecondsPerKilometer,
            restingHeartRate = payload.restingHeartRate,
            maximumHeartRate = payload.maximumHeartRate,
            lapCount = payload.lapCount,
            exportCount = payload.exportCount,
            previewSeed = payload.previewSeed?.toULongOrNull()
                ?: payload.previewSeed?.let {
                    throw SettingsRepositoryException("预览随机种子无效")
                },
        )
        validateSettings(settings)
        return settings
    }

    private fun restoreProvider(payload: CustomMapProviderPayloadDto): MapProvider =
        MapProvider.custom(
            id = payload.id,
            displayName = payload.displayName,
            styleUrl = payload.styleUrl,
            xyzUrlTemplate = payload.xyzUrlTemplate,
            keyPlacement = enumValueOf<ProviderKeyPlacement>(payload.keyPlacement),
            keyName = payload.keyName,
            apiKey = null,
            coordinateSystem = enumValueOf<CoordinateSystem>(payload.coordinateSystem),
            maxZoom = payload.maxZoom,
            attribution = payload.attribution,
            geocoderUrl = payload.geocoderUrl,
        )

    private fun validateSettings(settings: AppSettings) {
        if (
            settings.schemaVersion != AppSettings.CURRENT_SCHEMA_VERSION ||
            !settings.mapCamera.centerWgs84.latitude.isFinite() ||
            settings.mapCamera.centerWgs84.latitude !in -90.0..90.0 ||
            !settings.mapCamera.centerWgs84.longitude.isFinite() ||
            settings.mapCamera.centerWgs84.longitude !in -180.0..180.0 ||
            !settings.mapCamera.zoom.isFinite() ||
            settings.mapCamera.zoom !in MINIMUM_MAP_ZOOM..MAXIMUM_MAP_ZOOM ||
            !settings.paceSecondsPerKilometer.isFinite() ||
            settings.paceSecondsPerKilometer !in MINIMUM_PACE..MAXIMUM_PACE ||
            settings.restingHeartRate !in MINIMUM_RESTING_HEART_RATE..MAXIMUM_RESTING_HEART_RATE ||
            settings.maximumHeartRate !in MINIMUM_MAXIMUM_HEART_RATE..MAXIMUM_MAXIMUM_HEART_RATE ||
            settings.maximumHeartRate <= settings.restingHeartRate ||
            settings.lapCount !in 1..MAXIMUM_LAP_COUNT ||
            settings.exportCount !in 1..MAXIMUM_EXPORT_COUNT ||
            settings.customMapProviders.size > MAXIMUM_CUSTOM_PROVIDERS
        ) {
            throw SettingsRepositoryException("设置数据包含无效数值")
        }

        val providerIds = HashSet<String>()
        settings.customMapProviders.forEach { provider ->
            validateCustomProvider(provider)
            if (!providerIds.add(provider.id)) {
                throw SettingsRepositoryException("地图源标识不能重复")
            }
        }
        if (
            settings.activeMapProviderId != MapProvider.OPEN_STREET_MAP_ID &&
            settings.activeMapProviderId !in providerIds
        ) {
            throw SettingsRepositoryException("当前地图源不存在")
        }
    }

    private fun validateCustomProvider(provider: MapProvider) {
        val xyzUrl = provider.xyzUrlTemplate
        if (
            provider.isBuiltIn ||
            provider.id == MapProvider.OPEN_STREET_MAP_ID ||
            !PROVIDER_ID.matches(provider.id) ||
            provider.displayName.isBlank() || provider.displayName.length > MAXIMUM_LABEL_LENGTH ||
            provider.displayName.hasControlCharacters() ||
            xyzUrl.isNullOrBlank() || xyzUrl.length > MAXIMUM_URL_LENGTH ||
            !XYZ_Z.containsMatchIn(xyzUrl) ||
            !XYZ_X.containsMatchIn(xyzUrl) ||
            !XYZ_Y.containsMatchIn(xyzUrl) ||
            provider.maxZoom !in MINIMUM_MAP_ZOOM.toInt()..MAXIMUM_MAP_ZOOM.toInt() ||
            provider.attribution.isBlank() ||
            provider.attribution.length > MAXIMUM_ATTRIBUTION_LENGTH ||
            provider.attribution.hasControlCharacters() ||
            provider.keyName.hasControlCharacters() ||
            provider.apiKey.hasLineBreaks()
        ) {
            throw SettingsRepositoryException("自定义地图源配置无效")
        }
        validateUrlTemplate(xyzUrl)
        provider.styleUrl?.takeIf { it.isNotBlank() }?.let(::validateUrlTemplate)
        provider.geocoderUrl?.takeIf { it.isNotBlank() }?.let(::validateUrlTemplate)

        if (
            provider.keyPlacement == ProviderKeyPlacement.URL_TEMPLATE &&
            listOfNotNull(
                provider.xyzUrlTemplate,
                provider.styleUrl?.takeIf { it.isNotBlank() },
                provider.geocoderUrl?.takeIf { it.isNotBlank() },
            ).any { url -> !KEY.containsMatchIn(url) }
        ) {
            throw SettingsRepositoryException("URL 模板 Key 需要在每个服务地址中包含 {key}")
        }

        if (
            provider.keyPlacement in setOf(
                ProviderKeyPlacement.HEADER,
                ProviderKeyPlacement.QUERY_PARAMETER,
            ) && provider.keyName.isNullOrBlank()
        ) {
            throw SettingsRepositoryException("地图 Key 参数名称无效")
        }
    }

    private fun validateUrlTemplate(value: String) {
        val sample = value
            .replace(XYZ_Z, "0")
            .replace(XYZ_X, "0")
            .replace(XYZ_Y, "0")
            .replace(SUBDOMAIN, "a")
            .replace(KEY, "key")
        val uri = try {
            URI(sample)
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            throw SettingsRepositoryException("地图服务地址无效")
        }
        if (
            !uri.isAbsolute ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            !uri.scheme.equals("https", true)
        ) {
            throw SettingsRepositoryException("地图服务地址必须使用 HTTPS")
        }
    }

    private fun containsKnownSettings(preferences: Preferences): Boolean =
        SETTINGS_KEYS.any { it in preferences.asMap() }

    private fun snapshotKnownPreferences(preferences: Preferences): String =
        buildJsonObject {
            SETTINGS_KEYS.sortedBy { it.name }.forEach { key ->
                preferences.asMap()[key]?.let { value -> put(key.name, value.toString()) }
            }
        }.toString()

    private fun String?.hasControlCharacters(): Boolean =
        this?.any { it.code < 0x20 || it.code == 0x7f } == true

    private fun String?.hasLineBreaks(): Boolean =
        this?.contains('\r') == true || this?.contains('\n') == true

    companion object {
        private const val SAVE_ERROR_MESSAGE = "无法保存应用设置，请稍后重试"
        private const val MINIMUM_MAP_ZOOM = 0.0
        private const val MAXIMUM_MAP_ZOOM = 24.0
        private const val MINIMUM_PACE = 60.0
        private const val MAXIMUM_PACE = 3_600.0
        private const val MINIMUM_RESTING_HEART_RATE = 30
        private const val MAXIMUM_RESTING_HEART_RATE = 120
        private const val MINIMUM_MAXIMUM_HEART_RATE = 100
        private const val MAXIMUM_MAXIMUM_HEART_RATE = 220
        private const val MAXIMUM_LAP_COUNT = 100
        private const val MAXIMUM_EXPORT_COUNT = 20
        private const val MAXIMUM_CUSTOM_PROVIDERS = 32
        private const val MAXIMUM_LABEL_LENGTH = 80
        private const val MAXIMUM_URL_LENGTH = 2_048
        private const val MAXIMUM_ATTRIBUTION_LENGTH = 512

        private val SETTINGS_SCHEMA_VERSION = intPreferencesKey("schema_version")
        private val ACTIVE_PROVIDER_ID = stringPreferencesKey("active_provider_id")
        private val CAMERA_LATITUDE = doublePreferencesKey("map_camera_latitude_wgs84")
        private val CAMERA_LONGITUDE = doublePreferencesKey("map_camera_longitude_wgs84")
        private val CAMERA_ZOOM = doublePreferencesKey("map_camera_zoom")
        private val PACE_SECONDS_PER_KILOMETER = doublePreferencesKey("pace_seconds_per_kilometer")
        private val RESTING_HEART_RATE = intPreferencesKey("resting_heart_rate")
        private val MAXIMUM_HEART_RATE = intPreferencesKey("maximum_heart_rate")
        private val LAP_COUNT = intPreferencesKey("lap_count")
        private val EXPORT_COUNT = intPreferencesKey("export_count")
        private val PREVIEW_SEED = stringPreferencesKey("preview_seed")
        private val CUSTOM_PROVIDERS_PAYLOAD = stringPreferencesKey("custom_map_providers")
        private val SETTINGS_PAYLOAD = stringPreferencesKey("settings_payload_v1")
        private val INVALID_SETTINGS_BACKUP = stringPreferencesKey("settings_invalid_backup")
        private val SETTINGS_KEYS: Set<Preferences.Key<*>> = setOf(
            SETTINGS_SCHEMA_VERSION,
            ACTIVE_PROVIDER_ID,
            CAMERA_LATITUDE,
            CAMERA_LONGITUDE,
            CAMERA_ZOOM,
            PACE_SECONDS_PER_KILOMETER,
            RESTING_HEART_RATE,
            MAXIMUM_HEART_RATE,
            LAP_COUNT,
            EXPORT_COUNT,
            PREVIEW_SEED,
            CUSTOM_PROVIDERS_PAYLOAD,
            SETTINGS_PAYLOAD,
        )

        private val PROVIDER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val XYZ_Z = Regex("\\{z\\}", RegexOption.IGNORE_CASE)
        private val XYZ_X = Regex("\\{x\\}", RegexOption.IGNORE_CASE)
        private val XYZ_Y = Regex("\\{y\\}", RegexOption.IGNORE_CASE)
        private val SUBDOMAIN = Regex("\\{s\\}", RegexOption.IGNORE_CASE)
        private val KEY = Regex("\\{key\\}", RegexOption.IGNORE_CASE)
        private val SettingsJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
        }
    }
}

@Serializable
private data class SettingsPayloadDto(
    val schemaVersion: Int,
    val activeMapProviderId: String,
    val mapCamera: MapCameraPayloadDto,
    val paceSecondsPerKilometer: Double,
    val restingHeartRate: Int,
    val maximumHeartRate: Int,
    val lapCount: Int,
    val exportCount: Int,
    val previewSeed: String?,
    val customMapProviders: List<CustomMapProviderPayloadDto>,
)

@Serializable
private data class MapCameraPayloadDto(
    val centerWgs84Latitude: Double,
    val centerWgs84Longitude: Double,
    val zoom: Double,
)

@Serializable
private data class CustomMapProviderPayloadDto(
    val id: String,
    val displayName: String,
    val styleUrl: String?,
    val xyzUrlTemplate: String?,
    val keyPlacement: String,
    val keyName: String?,
    val coordinateSystem: String,
    val maxZoom: Int,
    val attribution: String,
    val geocoderUrl: String?,
)
