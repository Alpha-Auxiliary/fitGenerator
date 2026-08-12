package com.alphaauxiliary.fitgenerator.core

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCoreServiceTest {
    @Test
    fun `an unsupported API version is a typed version mismatch`() {
        val gateway = FakeNativeCoreGateway(apiVersion = 2)
        val service = NativeCoreService(gateway, Dispatchers.Unconfined)

        val error = assertThrows(CoreException::class.java) {
            runTest {
                service.preview(createInput(), seed = 42uL)
            }
        }

        assertEquals("native_core_version_mismatch", error.code)
        assertEquals(1, gateway.apiVersionCalls)
        assertTrue(gateway.previewRequests.isEmpty())
    }

    @Test
    fun `preview request uses strict camelCase schema version one and UTC`() = runTest {
        val gateway = FakeNativeCoreGateway()
        val service = NativeCoreService(gateway, Dispatchers.Unconfined)

        service.preview(createInput(), seed = 42uL)

        val request = gateway.previewRequests.single().asJsonObject()
        assertEquals(1, request.requiredInt("schemaVersion"))
        assertFalse(request.containsKey("schema_version"))
        assertEquals(
            "2026-07-27T08:30:00.000Z",
            request.getValue("startTimeUtc").jsonPrimitive.content,
        )
        assertEquals("close_if_needed", request.getValue("routeMode").jsonPrimitive.content)
        assertEquals(1, request.requiredInt("variantIndex"))
    }

    @Test
    fun `preview and export forward the same caller supplied seed`() = runTest {
        val gateway = FakeNativeCoreGateway()
        val service = NativeCoreService(gateway, Dispatchers.Unconfined)
        val seed = 9_876_543_210uL

        val preview = service.preview(createInput(), seed)
        service.generateFit(createInput(), preview.seed, variantIndex = 3)

        val previewRequest = gateway.previewRequests.single().asJsonObject()
        val exportRequest = gateway.generateFitRequests.single().asJsonObject()
        assertEquals(
            seed.toString(),
            previewRequest.getValue("seed").jsonPrimitive.content,
        )
        assertEquals(
            seed.toString(),
            exportRequest.getValue("seed").jsonPrimitive.content,
        )
        assertEquals(1, previewRequest.requiredInt("variantIndex"))
        assertEquals(3, exportRequest.requiredInt("variantIndex"))
        assertEquals(seed, preview.seed)
        assertEquals(1, gateway.apiVersionCalls)
    }

    @Test
    fun `empty JNI bytes use the structured last error`() {
        val gateway = FakeNativeCoreGateway(
            previewResponse = { ByteArray(0) },
            lastError = """{"code":100,"message":" 轨迹点数量必须在 2 到 50000 之间 "}""",
        )
        val service = NativeCoreService(gateway, Dispatchers.Unconfined)

        val error = assertThrows(CoreException::class.java) {
            runTest {
                service.preview(createInput(), seed = 42uL)
            }
        }

        assertEquals("invalid_input", error.code)
        assertEquals(100, error.nativeCode)
        assertEquals(" 轨迹点数量必须在 2 到 50000 之间 ", error.message)
        assertEquals(1, gateway.lastErrorCalls)
    }

    @Test
    fun `coroutine cancellation is not mapped to a native failure`() {
        val cancellation = CancellationException("cancel preview")
        val gateway = FakeNativeCoreGateway(
            previewResponse = { throw cancellation },
        )
        val service = NativeCoreService(gateway, Dispatchers.Unconfined)

        val thrown = assertThrows(CancellationException::class.java) {
            runTest {
                service.preview(createInput(), seed = 42uL)
            }
        }

        assertEquals("cancel preview", thrown.message)
        assertEquals(0, gateway.lastErrorCalls)
    }

    private fun createInput(): ActivityInput {
        val localStart = GregorianCalendar(
            TimeZone.getTimeZone("Asia/Shanghai"),
            Locale.ROOT,
        ).apply {
            isLenient = false
            clear()
            set(2026, Calendar.JULY, 27, 16, 30, 0)
        }
        return ActivityInput(
            startTime = localStart,
            routePoints = listOf(
                ActivityRoutePoint(latitude = 39.9042, longitude = 116.4074),
                ActivityRoutePoint(latitude = 39.9052, longitude = 116.4084),
            ),
            paceSecondsPerKilometer = 360.0,
            restingHeartRate = 60,
            maximumHeartRate = 180,
            lapCount = 2,
        )
    }

    private class FakeNativeCoreGateway(
        private val apiVersion: Int = 1,
        private val previewResponse: (ByteArray) -> ByteArray = { request ->
            previewPayload(request.asJsonObject().getValue("seed").jsonPrimitive.content)
        },
        private val fitResponse: (ByteArray) -> ByteArray = { validFitBytes() },
        private val lastError: String = "",
    ) : NativeCoreGateway {
        var apiVersionCalls: Int = 0
            private set
        var lastErrorCalls: Int = 0
            private set
        val previewRequests = mutableListOf<ByteArray>()
        val generateFitRequests = mutableListOf<ByteArray>()

        override fun apiVersion(): Int {
            apiVersionCalls += 1
            return apiVersion
        }

        override fun preview(requestUtf8: ByteArray): ByteArray {
            previewRequests += requestUtf8.copyOf()
            return previewResponse(requestUtf8)
        }

        override fun generateFit(requestUtf8: ByteArray): ByteArray {
            generateFitRequests += requestUtf8.copyOf()
            return fitResponse(requestUtf8)
        }

        override fun lastError(): String {
            lastErrorCalls += 1
            return lastError
        }
    }

}

private fun ByteArray.asJsonObject(): JsonObject =
    StrictCoreJson.parseToJsonElement(toString(Charsets.UTF_8)).jsonObject

private fun JsonObject.requiredInt(name: String): Int = getValue(name).jsonPrimitive.int

private fun previewPayload(seed: String): ByteArray =
    """
        {
          "schemaVersion": 1,
          "algorithmVersion": 1,
          "startTimeUtc": "2026-07-27T08:30:00.000Z",
          "seed": $seed,
          "totalDistanceCm": 10000,
          "totalDurationMs": 60000,
          "laps": [
            {
              "index": 1,
              "startSample": 0,
              "endSample": 1,
              "distanceCm": 10000,
              "durationMs": 60000
            }
          ],
          "samples": [
            {
              "timeMs": 0,
              "distanceCm": 0,
              "speedMmPerSec": 2778,
              "heartRateBpm": 120,
              "positionLatSemicircles": 476795932,
              "positionLongSemicircles": 1388971064
            }
          ]
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)

private fun validFitBytes(): ByteArray = ByteArray(12).apply {
    this[8] = '.'.code.toByte()
    this[9] = 'F'.code.toByte()
    this[10] = 'I'.code.toByte()
    this[11] = 'T'.code.toByte()
}
