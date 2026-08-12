package com.alphaauxiliary.fitgenerator.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class CoordinateSystem {
    WGS84,
    GCJ02,
    BD09,
}

internal data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
)

internal object CoordinateTransforms {
    private const val SEMI_MAJOR_AXIS = 6_378_245.0
    private const val ECCENTRICITY_SQUARED = 0.00669342162296594323
    private const val BD_PI = PI * 3_000.0 / 180.0
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val CHINA_MINIMUM_LATITUDE = 0.8293
    private const val CHINA_MAXIMUM_LATITUDE = 55.8271
    private const val CHINA_MINIMUM_LONGITUDE = 72.004
    private const val CHINA_MAXIMUM_LONGITUDE = 137.8347
    private const val INVERSE_TOLERANCE_DEGREES = 1e-7
    private const val MAXIMUM_INVERSE_ITERATIONS = 10

    fun toWgs84(point: GeoCoordinate, source: CoordinateSystem): GeoCoordinate {
        validateCoordinate(point)
        return when (source) {
            CoordinateSystem.WGS84 -> point
            CoordinateSystem.GCJ02 -> gcj02ToWgs84(point)
            CoordinateSystem.BD09 -> gcj02ToWgs84(bd09ToGcj02(point))
        }
    }

    fun fromWgs84(point: GeoCoordinate, target: CoordinateSystem): GeoCoordinate {
        validateCoordinate(point)
        return when (target) {
            CoordinateSystem.WGS84 -> point
            CoordinateSystem.GCJ02 -> wgs84ToGcj02(point)
            CoordinateSystem.BD09 -> gcj02ToBd09(wgs84ToGcj02(point))
        }
    }

    fun wgs84ToGcj02(point: GeoCoordinate): GeoCoordinate {
        validateCoordinate(point)
        if (isOutsideChina(point)) {
            return point
        }

        var latitudeOffset = transformLatitude(
            longitude = point.longitude - 105.0,
            latitude = point.latitude - 35.0,
        )
        var longitudeOffset = transformLongitude(
            longitude = point.longitude - 105.0,
            latitude = point.latitude - 35.0,
        )
        val latitudeRadians = degreesToRadians(point.latitude)
        val sineLatitude = sin(latitudeRadians)
        val magic = 1.0 - ECCENTRICITY_SQUARED * sineLatitude * sineLatitude
        val squareRootMagic = sqrt(magic)
        latitudeOffset = latitudeOffset * 180.0 /
            (
                (SEMI_MAJOR_AXIS * (1.0 - ECCENTRICITY_SQUARED)) /
                    (magic * squareRootMagic) * PI
                )
        longitudeOffset = longitudeOffset * 180.0 /
            (SEMI_MAJOR_AXIS / squareRootMagic * cos(latitudeRadians) * PI)

        return GeoCoordinate(
            latitude = point.latitude + latitudeOffset,
            longitude = point.longitude + longitudeOffset,
        )
    }

    fun gcj02ToWgs84(point: GeoCoordinate): GeoCoordinate {
        validateCoordinate(point)
        if (isOutsideChina(point)) {
            return point
        }

        var estimate = point
        repeat(MAXIMUM_INVERSE_ITERATIONS) {
            val transformed = wgs84ToGcj02(estimate)
            val latitudeError = transformed.latitude - point.latitude
            val longitudeError = transformed.longitude - point.longitude
            estimate = GeoCoordinate(
                latitude = estimate.latitude - latitudeError,
                longitude = estimate.longitude - longitudeError,
            )

            if (
                abs(latitudeError) < INVERSE_TOLERANCE_DEGREES &&
                abs(longitudeError) < INVERSE_TOLERANCE_DEGREES
            ) {
                return estimate
            }
        }
        return estimate
    }

    fun gcj02ToBd09(point: GeoCoordinate): GeoCoordinate {
        validateCoordinate(point)
        val radius = sqrt(
            point.longitude * point.longitude + point.latitude * point.latitude,
        ) + 0.00002 * sin(point.latitude * BD_PI)
        val angle = atan2(point.latitude, point.longitude) +
            0.000003 * cos(point.longitude * BD_PI)

        return GeoCoordinate(
            latitude = radius * sin(angle) + 0.006,
            longitude = radius * cos(angle) + 0.0065,
        )
    }

    fun bd09ToGcj02(point: GeoCoordinate): GeoCoordinate {
        validateCoordinate(point)
        val longitude = point.longitude - 0.0065
        val latitude = point.latitude - 0.006
        val radius = sqrt(longitude * longitude + latitude * latitude) -
            0.00002 * sin(latitude * BD_PI)
        val angle = atan2(latitude, longitude) -
            0.000003 * cos(longitude * BD_PI)

        return GeoCoordinate(
            latitude = radius * sin(angle),
            longitude = radius * cos(angle),
        )
    }

    fun distanceMeters(first: GeoCoordinate, second: GeoCoordinate): Double {
        validateCoordinate(first)
        validateCoordinate(second)
        val latitudeDelta = degreesToRadians(second.latitude - first.latitude)
        val longitudeDelta = degreesToRadians(second.longitude - first.longitude)
        val firstLatitude = degreesToRadians(first.latitude)
        val secondLatitude = degreesToRadians(second.latitude)
        val haversine = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        val centralAngle = 2.0 * asin(sqrt(min(1.0, max(0.0, haversine))))
        return EARTH_RADIUS_METERS * centralAngle
    }

    private fun isOutsideChina(point: GeoCoordinate): Boolean =
        point.longitude < CHINA_MINIMUM_LONGITUDE ||
            point.longitude > CHINA_MAXIMUM_LONGITUDE ||
            point.latitude < CHINA_MINIMUM_LATITUDE ||
            point.latitude > CHINA_MAXIMUM_LATITUDE

    private fun validateCoordinate(point: GeoCoordinate) {
        require(point.latitude.isFinite() && point.longitude.isFinite()) {
            "坐标必须是有限数值"
        }
        require(point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) {
            "坐标超出有效经纬度范围"
        }
    }

    private fun transformLatitude(longitude: Double, latitude: Double): Double {
        var transformed = -100.0 +
            2.0 * longitude +
            3.0 * latitude +
            0.2 * latitude * latitude +
            0.1 * longitude * latitude +
            0.2 * sqrt(abs(longitude))
        transformed += (
            20.0 * sin(6.0 * longitude * PI) +
                20.0 * sin(2.0 * longitude * PI)
            ) * 2.0 / 3.0
        transformed += (
            20.0 * sin(latitude * PI) +
                40.0 * sin(latitude / 3.0 * PI)
            ) * 2.0 / 3.0
        transformed += (
            160.0 * sin(latitude / 12.0 * PI) +
                320.0 * sin(latitude * PI / 30.0)
            ) * 2.0 / 3.0
        return transformed
    }

    private fun transformLongitude(longitude: Double, latitude: Double): Double {
        var transformed = 300.0 +
            longitude +
            2.0 * latitude +
            0.1 * longitude * longitude +
            0.1 * longitude * latitude +
            0.1 * sqrt(abs(longitude))
        transformed += (
            20.0 * sin(6.0 * longitude * PI) +
                20.0 * sin(2.0 * longitude * PI)
            ) * 2.0 / 3.0
        transformed += (
            20.0 * sin(longitude * PI) +
                40.0 * sin(longitude / 3.0 * PI)
            ) * 2.0 / 3.0
        transformed += (
            150.0 * sin(longitude / 12.0 * PI) +
                300.0 * sin(longitude / 30.0 * PI)
            ) * 2.0 / 3.0
        return transformed
    }

    private fun degreesToRadians(value: Double): Double = value * PI / 180.0
}
