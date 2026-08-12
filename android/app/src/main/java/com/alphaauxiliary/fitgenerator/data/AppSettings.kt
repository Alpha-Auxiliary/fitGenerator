package com.alphaauxiliary.fitgenerator.data

import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import com.alphaauxiliary.fitgenerator.map.MapProvider

internal data class MapCameraSettings(
    val centerWgs84: GeoCoordinate,
    val zoom: Double,
) {
    companion object {
        val Default = MapCameraSettings(
            centerWgs84 = GeoCoordinate(latitude = 39.9042, longitude = 116.4074),
            zoom = 9.0,
        )
    }
}

internal data class AppSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val activeMapProviderId: String = MapProvider.OPEN_STREET_MAP_ID,
    val customMapProviders: List<MapProvider> = emptyList(),
    val mapCamera: MapCameraSettings = MapCameraSettings.Default,
    val paceSecondsPerKilometer: Double = 360.0,
    val restingHeartRate: Int = 60,
    val maximumHeartRate: Int = 180,
    val lapCount: Int = 1,
    val exportCount: Int = 1,
    val previewSeed: ULong? = null,
) {
    override fun toString(): String =
        "AppSettings(schemaVersion=$schemaVersion, activeMapProviderId=$activeMapProviderId, " +
            "customMapProviderCount=${customMapProviders.size}, mapCamera=$mapCamera, " +
            "paceSecondsPerKilometer=$paceSecondsPerKilometer, restingHeartRate=$restingHeartRate, " +
            "maximumHeartRate=$maximumHeartRate, lapCount=$lapCount, exportCount=$exportCount, " +
            "previewSeed=$previewSeed)"

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        val Default = AppSettings()
    }
}

internal data class SavedRoute(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val wgs84Points: List<GeoCoordinate> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        val Empty = SavedRoute()
    }
}
