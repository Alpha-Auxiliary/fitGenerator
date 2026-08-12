package com.alphaauxiliary.fitgenerator.map

import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSearchServiceTest {
    @Test
    fun `default search uses the Nominatim request contract and caps results`() = runTest {
        val clock = FakeMonotonicClock()
        val transport = RecordingTransport {
            HttpResponse(statusCode = 200, body = createResultsJson(count = 6))
        }
        val service = createService(transport, clock)

        val results = service.search(
            query = "天安门 广场",
            provider = MapProvider.OpenStreetMap,
            locale = Locale.forLanguageTag("zh-CN"),
        )

        val request = transport.requests.single()
        val uri = URI(request.url)
        assertEquals("https://nominatim.openstreetmap.org/search", uri.withoutQuery())
        assertEquals("jsonv2", uri.queryValue("format"))
        assertEquals("5", uri.queryValue("limit"))
        assertEquals("天安门 广场", uri.queryValue("q"))
        assertTrue(uri.rawQuery.contains("q=%E5%A4%A9%E5%AE%89%E9%97%A8%20%E5%B9%BF%E5%9C%BA"))
        assertEquals(
            "fitGenerator/1.0 (+https://github.com/Alpha-Auxiliary/fitGenerator)",
            request.headers["User-Agent"],
        )
        assertEquals("zh-CN", request.headers["Accept-Language"])
        assertEquals(10_000, request.connectTimeoutMillis)
        assertEquals(10_000, request.readTimeoutMillis)
        assertEquals(5, results.size)
        assertTrue(results.all {
            it.attribution == MapProvider.OPEN_STREET_MAP_ATTRIBUTION
        })
        assertEquals("Result 0", results.first().label)
        assertEquals(
            GeoCoordinate(latitude = 39.9042, longitude = 116.4074),
            results.first().coordinate,
        )
    }

    @Test
    fun `consecutive Nominatim requests start at least one second apart`() = runTest {
        val clock = FakeMonotonicClock()
        val requestStarts = mutableListOf<Long>()
        val transport = RecordingTransport {
            requestStarts += clock.nowMillis()
            HttpResponse(statusCode = 200, body = createResultsJson())
        }
        val service = createService(transport, clock)

        service.search("first", MapProvider.OpenStreetMap, Locale.ROOT)
        service.search("second", MapProvider.OpenStreetMap, Locale.ROOT)

        assertEquals(listOf(0L, 1_000L), requestStarts)
        assertEquals(listOf(1_000L), clock.delays)
    }

    @Test
    fun `rate-limit mutex is released before the network request awaits`() = runTest {
        val clock = FakeMonotonicClock()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger()
        val requestStarts = mutableListOf<Long>()
        val transport = RecordingTransport {
            requestStarts += clock.nowMillis()
            if (requestCount.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                secondStarted.complete(Unit)
            }
            HttpResponse(statusCode = 200, body = createResultsJson())
        }
        val service = createService(transport, clock)

        val firstConnection = async(start = CoroutineStart.UNDISPATCHED) {
            service.testConnection(MapProvider.OpenStreetMap, Locale.ROOT)
        }
        firstStarted.await()
        val secondConnection = async(start = CoroutineStart.UNDISPATCHED) {
            service.testConnection(MapProvider.OpenStreetMap, Locale.ROOT)
        }
        secondStarted.await()
        releaseFirst.complete(Unit)

        assertTrue(firstConnection.await().isSuccess)
        assertTrue(secondConnection.await().isSuccess)
        assertEquals(listOf(0L, 1_000L), requestStarts)
    }

    @Test
    fun `a newer query cancels the superseded request`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val requestCount = AtomicInteger()
        val transport = RecordingTransport {
            if (requestCount.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    if (!currentCoroutineContext().isActive) {
                        firstCancelled.complete(Unit)
                    }
                }
            }
            HttpResponse(statusCode = 200, body = createResultsJson())
        }
        val service = createService(transport, FakeMonotonicClock())

        val firstSearch = async(start = CoroutineStart.UNDISPATCHED) {
            service.search("older", MapProvider.OpenStreetMap, Locale.ROOT)
        }
        firstStarted.await()
        val secondSearch = async(start = CoroutineStart.UNDISPATCHED) {
            service.search("newer", MapProvider.OpenStreetMap, Locale.ROOT)
        }

        firstCancelled.await()
        assertSuspendThrows<kotlinx.coroutines.CancellationException> {
            firstSearch.await()
        }
        assertEquals(1, secondSearch.await().size)
        assertEquals(2, requestCount.get())
    }

    @Test
    fun `caller cancellation is propagated to the transport`() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        val transport = RecordingTransport {
            requestStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                if (!currentCoroutineContext().isActive) {
                    requestCancelled.complete(Unit)
                }
            }
        }
        val service = createService(transport, FakeMonotonicClock())

        val search = async(start = CoroutineStart.UNDISPATCHED) {
            service.search("cancelled", createCustomProvider(), Locale.ENGLISH)
        }
        requestStarted.await()
        search.cancel(kotlinx.coroutines.CancellationException("caller cancelled"))

        requestCancelled.await()
        assertSuspendThrows<kotlinx.coroutines.CancellationException> {
            search.await()
        }
    }

    @Test
    fun `provider coordinates are converted to canonical WGS84`() = runTest {
        val expected = GeoCoordinate(latitude = 39.9042, longitude = 116.4074)
        val gcj02 = CoordinateTransforms.wgs84ToGcj02(expected)
        val transport = RecordingTransport {
            HttpResponse(
                statusCode = 200,
                body = createResultsJson(
                    latitude = gcj02.latitude,
                    longitude = gcj02.longitude,
                ),
            )
        }
        val provider = createCustomProvider(
            coordinateSystem = CoordinateSystem.GCJ02,
            attribution = "Example attribution",
        )
        val service = createService(transport, FakeMonotonicClock())

        val results = service.search("Beijing", provider, Locale.ENGLISH)

        assertEquals(1, results.size)
        assertTrue(
            CoordinateTransforms.distanceMeters(results.single().coordinate, expected) < 2.0,
        )
        assertEquals("Example attribution", results.single().attribution)
    }

    @Test
    fun `network timeout and parser failures do not mutate the route`() = runTest {
        val route = mutableListOf(
            GeoCoordinate(latitude = 39.9042, longitude = 116.4074),
            GeoCoordinate(latitude = 39.9052, longitude = 116.4084),
        )
        val originalRoute = route.toList()
        val failures = listOf(
            SearchErrorKind.NETWORK to RecordingTransport {
                HttpResponse(statusCode = 502, body = ByteArray(0))
            },
            SearchErrorKind.TIMEOUT to RecordingTransport {
                throw SocketTimeoutException("timeout")
            },
            SearchErrorKind.INVALID_RESPONSE to RecordingTransport {
                HttpResponse(statusCode = 200, body = "{".toByteArray(Charsets.UTF_8))
            },
            SearchErrorKind.INVALID_RESPONSE to RecordingTransport {
                HttpResponse(statusCode = 200, body = byteArrayOf(0xff.toByte()))
            },
            SearchErrorKind.INVALID_RESPONSE to RecordingTransport {
                HttpResponse(statusCode = 200, body = ByteArray(1024 * 1024 + 1))
            },
        )

        failures.forEach { (expectedKind, transport) ->
            val service = createService(transport, FakeMonotonicClock())

            val error = assertSuspendThrows<LocationSearchException> {
                service.search("Beijing", createCustomProvider(), Locale.ENGLISH)
            }

            assertEquals(expectedKind, error.kind)
            assertTrue(error.message?.isNotBlank() == true)
            assertEquals(originalRoute, route)
        }
    }

    @Test
    fun `connection test sends a header key but never renders the key`() = runTest {
        val secret = "secret-value"
        val transport = RecordingTransport {
            HttpResponse(statusCode = 200, body = ByteArray(0))
        }
        val provider = createCustomProvider(
            keyPlacement = ProviderKeyPlacement.HEADER,
            keyName = "X-Api-Key",
            apiKey = secret,
        )
        val service = createService(transport, FakeMonotonicClock())

        val result = service.testConnection(provider, Locale.ENGLISH)

        assertTrue(result.isSuccess)
        assertNull(result.errorKind)
        val request = transport.requests.single()
        assertEquals(secret, request.headers["X-Api-Key"])
        assertFalse(result.message.contains(secret))
        assertFalse(provider.toString().contains(secret))
        assertFalse(request.toString().contains(secret))
        assertFalse(request.toString().contains("example.test"))
    }

    @Test
    fun `connection test requests one tile and encodes a query key`() = runTest {
        val secret = "a key/+?"
        val transport = RecordingTransport {
            HttpResponse(statusCode = 204, body = ByteArray(0))
        }
        val provider = createCustomProvider(
            geocoderUrl = null,
            keyPlacement = ProviderKeyPlacement.QUERY_PARAMETER,
            keyName = "token",
            apiKey = secret,
        )
        val service = createService(transport, FakeMonotonicClock())

        val result = service.testConnection(provider, Locale.ENGLISH)

        assertTrue(result.isSuccess)
        val requestUri = URI(transport.requests.single().url)
        assertEquals("/0/0/0.png", requestUri.path)
        assertEquals(secret, requestUri.queryValue("token"))
        assertTrue(requestUri.rawQuery.contains("token=a%20key%2F%2B%3F"))
        assertFalse(requestUri.rawQuery.contains(secret))
        assertFalse(result.message.contains(secret))
    }

    @Test
    fun `invalid XYZ templates are rejected before any request`() = runTest {
        val transport = RecordingTransport {
            HttpResponse(statusCode = 200, body = createResultsJson())
        }
        val provider = createCustomProvider(
            xyzUrlTemplate = "https://tiles.example.test/{z}/{x}/tile.png",
        )
        val service = createService(transport, FakeMonotonicClock())

        val error = assertSuspendThrows<LocationSearchException> {
            service.search("Beijing", provider, Locale.ENGLISH)
        }

        assertEquals(SearchErrorKind.PROVIDER_NOT_CONFIGURED, error.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `connection test rejects a missing required key without a request`() = runTest {
        val transport = RecordingTransport {
            HttpResponse(statusCode = 200, body = ByteArray(0))
        }
        val provider = createCustomProvider(
            keyPlacement = ProviderKeyPlacement.HEADER,
            keyName = "X-Api-Key",
            apiKey = null,
        )
        val service = createService(transport, FakeMonotonicClock())

        val result = service.testConnection(provider, Locale.ENGLISH)

        assertFalse(result.isSuccess)
        assertEquals(SearchErrorKind.PROVIDER_NOT_CONFIGURED, result.errorKind)
        assertTrue(result.message.contains("API Key"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `OSM is an immutable zero-key default and custom providers remain separate`() {
        val defaultProvider = MapProvider.OpenStreetMap
        val savedCustomProviders = listOf(createCustomProvider())

        assertSame(defaultProvider, MapProvider.BuiltIns.single())
        assertTrue(defaultProvider.isDefault)
        assertTrue(defaultProvider.isBuiltIn)
        assertEquals(CoordinateSystem.WGS84, defaultProvider.coordinateSystem)
        assertEquals(MapProvider.BUNDLED_OSM_STYLE_URL, defaultProvider.styleUrl)
        assertEquals(MapProvider.OPEN_STREET_MAP_TILE_URL, defaultProvider.xyzUrlTemplate)
        assertEquals(19, defaultProvider.maxZoom)
        assertEquals(MapProvider.OPEN_STREET_MAP_ATTRIBUTION, defaultProvider.attribution)
        assertEquals(MapProvider.NOMINATIM_SEARCH_URL, defaultProvider.geocoderUrl)
        assertEquals(ProviderKeyPlacement.NONE, defaultProvider.keyPlacement)
        assertNull(defaultProvider.apiKey)
        assertEquals(1, savedCustomProviders.size)
        assertFalse(savedCustomProviders.single().isBuiltIn)
    }

    private fun createService(
        transport: HttpTransport,
        clock: MonotonicClock,
    ): LocationSearchService = LocationSearchService(
        transport = transport,
        clock = clock,
        dispatcher = Dispatchers.Unconfined,
    )

    private fun createCustomProvider(
        xyzUrlTemplate: String? = "https://tiles.example.test/{z}/{x}/{y}.png",
        geocoderUrl: String? = "https://example.test/search",
        coordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
        attribution: String = "Example attribution",
        keyPlacement: ProviderKeyPlacement = ProviderKeyPlacement.NONE,
        keyName: String? = null,
        apiKey: String? = null,
    ): MapProvider = MapProvider.custom(
        id = "custom",
        displayName = "Custom Provider",
        xyzUrlTemplate = xyzUrlTemplate,
        coordinateSystem = coordinateSystem,
        maxZoom = 18,
        attribution = attribution,
        geocoderUrl = geocoderUrl,
        keyPlacement = keyPlacement,
        keyName = keyName,
        apiKey = apiKey,
    )

    private fun createResultsJson(
        count: Int = 1,
        latitude: Double = 39.9042,
        longitude: Double = 116.4074,
    ): ByteArray = buildString {
        append('[')
        repeat(count) { index ->
            if (index > 0) {
                append(',')
            }
            append(
                """{"display_name":"Result $index","lat":"$latitude","lon":"$longitude"}""",
            )
        }
        append(']')
    }.toByteArray(Charsets.UTF_8)

    private suspend inline fun <reified T : Throwable> assertSuspendThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) {
                return error
            }
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        throw AssertionError("Expected ${T::class.java.name} to be thrown")
    }

    private class RecordingTransport(
        private val responder: suspend (HttpRequest) -> HttpResponse,
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return responder(request)
        }
    }

    private class FakeMonotonicClock : MonotonicClock {
        private var currentMillis = 0L
        val delays = mutableListOf<Long>()

        override fun nowMillis(): Long = currentMillis

        override suspend fun delayMillis(durationMillis: Long) {
            if (!currentCoroutineContext().isActive) {
                throw kotlinx.coroutines.CancellationException()
            }
            delays += durationMillis
            currentMillis += durationMillis
        }
    }
}

private fun URI.withoutQuery(): String = URI(
    scheme,
    userInfo,
    host,
    port,
    path,
    null,
    null,
).toString()

private fun URI.queryValue(name: String): String? = rawQuery
    ?.split('&')
    ?.asSequence()
    ?.map { pair -> pair.substringBefore('=') to pair.substringAfter('=', "") }
    ?.firstOrNull { (encodedName, _) -> decodeQueryComponent(encodedName) == name }
    ?.let { (_, encodedValue) -> decodeQueryComponent(encodedValue) }

private fun decodeQueryComponent(value: String): String =
    URLDecoder.decode(value, Charsets.UTF_8.name())
