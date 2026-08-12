package com.alphaauxiliary.fitgenerator.core

import java.util.Calendar
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ActivityRoutePoint(
    val latitude: Double,
    val longitude: Double,
)

data class ActivityInput(
    val startTime: Calendar,
    val routePoints: List<ActivityRoutePoint>,
    val paceSecondsPerKilometer: Double,
    val restingHeartRate: Int,
    val maximumHeartRate: Int,
    val lapCount: Int,
)

@Serializable
internal data class GeoPointDto(
    val lat: Double,
    val lng: Double,
)

@Serializable
internal data class CoreRequestDto(
    val schemaVersion: Int,
    val startTimeUtc: String,
    val points: List<GeoPointDto>,
    val paceSecondsPerKm: Double,
    val hrRest: Int,
    val hrMax: Int,
    val lapCount: Int,
    val variantIndex: Int,
    val seed: ULong,
    val routeMode: String,
)

@Serializable
data class ActivitySampleDto(
    val timeMs: Long,
    val distanceCm: Long,
    val speedMmPerSec: Int,
    val heartRateBpm: Int,
    val positionLatSemicircles: Int,
    val positionLongSemicircles: Int,
)

@Serializable
data class LapModelDto(
    val index: Int,
    val startSample: Int,
    val endSample: Int,
    val distanceCm: Long,
    val durationMs: Long,
)

@Serializable
data class ActivityModelDto(
    val schemaVersion: Int,
    val algorithmVersion: Int,
    val startTimeUtc: String,
    val seed: ULong,
    val totalDistanceCm: Long,
    val totalDurationMs: Long,
    val laps: List<LapModelDto>,
    val samples: List<ActivitySampleDto>,
)

@Serializable
internal data class CoreErrorDto(
    val code: Int,
    val message: String,
)

class CoreException(
    val code: String,
    message: String,
    val nativeCode: Int? = null,
) : Exception(message)

@OptIn(ExperimentalSerializationApi::class)
internal val StrictCoreJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = false
}
