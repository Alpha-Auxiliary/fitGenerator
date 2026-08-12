package com.alphaauxiliary.fitgenerator.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateTransformsTest {
    @Test
    fun `Windows WGS84 and GCJ02 vectors round trip within two meters`() {
        loadVectors("wgs84Gcj02RoundTrips").forEach { vector ->
            val original = vector.coordinate

            val gcj02 = CoordinateTransforms.wgs84ToGcj02(original)
            val restored = CoordinateTransforms.gcj02ToWgs84(gcj02)

            assertTrue(
                "${vector.name} round-trip exceeded two meters",
                CoordinateTransforms.distanceMeters(restored, original) < 2.0,
            )
            assertTrue(
                "${vector.name} was not shifted inside China",
                CoordinateTransforms.distanceMeters(gcj02, original) > 100.0,
            )
        }
    }

    @Test
    fun `Windows GCJ02 and BD09 vectors round trip within two meters`() {
        loadVectors("gcj02Bd09RoundTrips").forEach { vector ->
            val original = vector.coordinate

            val bd09 = CoordinateTransforms.gcj02ToBd09(original)
            val restored = CoordinateTransforms.bd09ToGcj02(bd09)

            assertTrue(
                "${vector.name} round-trip exceeded two meters",
                CoordinateTransforms.distanceMeters(restored, original) < 2.0,
            )
        }
    }

    @Test
    fun `GCJ02 conversion leaves coordinates outside China unchanged`() {
        loadVectors("outsideChina").forEach { vector ->
            assertEquals(
                vector.coordinate,
                CoordinateTransforms.wgs84ToGcj02(vector.coordinate),
            )
            assertEquals(
                vector.coordinate,
                CoordinateTransforms.gcj02ToWgs84(vector.coordinate),
            )
        }
    }

    @Test
    fun `coordinate system dispatch preserves canonical WGS84`() {
        val original = GeoCoordinate(latitude = 39.9042, longitude = 116.4074)

        CoordinateSystem.entries.forEach { coordinateSystem ->
            val providerCoordinate = CoordinateTransforms.fromWgs84(
                original,
                coordinateSystem,
            )
            val restored = CoordinateTransforms.toWgs84(
                providerCoordinate,
                coordinateSystem,
            )

            assertTrue(
                "$coordinateSystem round-trip exceeded two meters",
                CoordinateTransforms.distanceMeters(restored, original) < 2.0,
            )
        }
    }

    @Test
    fun `non-finite and out-of-range coordinates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoordinateTransforms.wgs84ToGcj02(
                GeoCoordinate(latitude = Double.NaN, longitude = 116.4074),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoordinateTransforms.gcj02ToBd09(
                GeoCoordinate(latitude = 39.9042, longitude = Double.POSITIVE_INFINITY),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoordinateTransforms.distanceMeters(
                GeoCoordinate(latitude = 91.0, longitude = 0.0),
                GeoCoordinate(latitude = 0.0, longitude = 0.0),
            )
        }
    }

    private fun loadVectors(section: String): List<CoordinateVector> {
        val fixtureText = checkNotNull(
            javaClass.getResourceAsStream("/fixtures/coordinate-transform-vectors.json"),
        ) { "Missing coordinate transform fixture" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val root = Json.parseToJsonElement(fixtureText) as JsonObject
        assertEquals(1, root.getValue("schemaVersion").jsonPrimitive.int)
        return (root.getValue(section) as JsonArray).map { item ->
            val value = item as JsonObject
            CoordinateVector(
                name = value.getValue("name").jsonPrimitive.content,
                coordinate = GeoCoordinate(
                    latitude = value.getValue("latitude").jsonPrimitive.double,
                    longitude = value.getValue("longitude").jsonPrimitive.double,
                ),
            )
        }
    }

    private data class CoordinateVector(
        val name: String,
        val coordinate: GeoCoordinate,
    )
}
