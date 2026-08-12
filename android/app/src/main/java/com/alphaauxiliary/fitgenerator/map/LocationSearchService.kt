package com.alphaauxiliary.fitgenerator.map

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal data class LocationSearchResult(
    val label: String,
    val attribution: String,
    val coordinate: GeoCoordinate,
)

internal enum class SearchErrorKind {
    INVALID_REQUEST,
    PROVIDER_NOT_CONFIGURED,
    NETWORK,
    TIMEOUT,
    INVALID_RESPONSE,
}

internal class LocationSearchException(
    val kind: SearchErrorKind,
    message: String,
) : Exception(message)

internal data class ProviderConnectionResult(
    val isSuccess: Boolean,
    val message: String,
    val errorKind: SearchErrorKind? = null,
)

internal data class HttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
    val maximumResponseBytes: Int,
) {
    // URL and headers can contain provider keys; never include either in diagnostics.
    override fun toString(): String =
        "HttpRequest(method=GET, url=<redacted>, headers=<redacted>)"
}

internal data class HttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

internal fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

internal interface MonotonicClock {
    fun nowMillis(): Long

    suspend fun delayMillis(durationMillis: Long)
}

internal object SystemMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = System.nanoTime() / NANOSECONDS_PER_MILLISECOND

    override suspend fun delayMillis(durationMillis: Long) {
        delay(durationMillis)
    }

    private const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
}

internal object HttpUrlConnectionTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val connection = try {
                URL(request.url).openConnection() as? HttpURLConnection
                    ?: throw IOException("地图服务地址不是 HTTP 资源")
            } catch (error: Exception) {
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                connection.disconnectQuietly()
            }

            val result: Result<HttpResponse> = try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.doInput = true
                connection.connectTimeout = request.connectTimeoutMillis
                connection.readTimeout = request.readTimeoutMillis
                request.headers.forEach { (name, value) ->
                    connection.setRequestProperty(name, value)
                }

                val statusCode = connection.responseCode
                if (connection.contentLength > request.maximumResponseBytes) {
                    throw ResponseTooLargeException()
                }
                val stream = if (statusCode in SUCCESS_STATUS_CODES) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.use {
                    it.readWithLimit(request.maximumResponseBytes)
                } ?: ByteArray(0)
                Result.success(HttpResponse(statusCode = statusCode, body = body))
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                connection.disconnectQuietly()
            }
            continuation.resumeWith(result)
        }

    private val SUCCESS_STATUS_CODES = 200..299
}

internal class LocationSearchService(
    private val transport: HttpTransport = HttpUrlConnectionTransport,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val nominatimRequestMutex = Mutex()
    private val activeSearchLock = Any()
    private var activeSearchJob: Job? = null
    private var lastNominatimRequestStartMillis: Long? = null

    suspend fun search(
        query: String,
        provider: MapProvider = MapProvider.OpenStreetMap,
        locale: Locale = Locale.getDefault(),
    ): List<LocationSearchResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            throw LocationSearchException(
                SearchErrorKind.INVALID_REQUEST,
                "请输入要搜索的地点",
            )
        }

        return coroutineScope {
            coroutineContext.ensureActive()
            val thisSearchJob = coroutineContext[Job]
                ?: error("地点搜索必须在协程中运行")
            val previousSearch = synchronized(activeSearchLock) {
                val previous = activeSearchJob
                activeSearchJob = thisSearchJob
                previous
            }
            previousSearch?.cancel(
                CancellationException("地点搜索已被新查询取代"),
            )

            try {
                withContext(dispatcher) {
                    searchCore(normalizedQuery, provider, locale)
                }
            } finally {
                synchronized(activeSearchLock) {
                    if (activeSearchJob === thisSearchJob) {
                        activeSearchJob = null
                    }
                }
            }
        }
    }

    suspend fun testConnection(
        provider: MapProvider,
        locale: Locale = Locale.getDefault(),
    ): ProviderConnectionResult = try {
        withContext(dispatcher) {
            val requestUrl = buildConnectionUrl(provider)
            val request = createRequest(
                url = requestUrl,
                provider = provider,
                locale = locale,
                expectsJson = provider.geocoderUrl?.isNotBlank() == true,
            )
            val response = send(
                request = request,
                applyNominatimRateLimit = isNominatimEndpoint(requestUrl),
            )
            if (response.statusCode in SUCCESS_STATUS_CODES) {
                ProviderConnectionResult(
                    isSuccess = true,
                    message = if (provider.keyPlacement == ProviderKeyPlacement.NONE) {
                        "连接成功（此地图源无需 API Key）"
                    } else {
                        "连接成功"
                    },
                )
            } else {
                connectionFailure(SearchErrorKind.NETWORK, "连接失败：服务返回错误")
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: LocationSearchException) {
        connectionFailure(error.kind, error.message ?: "连接失败")
    } catch (_: SocketTimeoutException) {
        currentCoroutineContext().ensureActive()
        connectionFailure(SearchErrorKind.TIMEOUT, "连接失败：请求超时")
    } catch (_: ResponseTooLargeException) {
        currentCoroutineContext().ensureActive()
        connectionFailure(SearchErrorKind.INVALID_RESPONSE, "连接失败：服务返回数据过大")
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        connectionFailure(SearchErrorKind.NETWORK, "连接失败：服务暂时不可用")
    } catch (_: IllegalArgumentException) {
        connectionFailure(SearchErrorKind.PROVIDER_NOT_CONFIGURED, INVALID_PROVIDER_MESSAGE)
    }

    private suspend fun searchCore(
        query: String,
        provider: MapProvider,
        locale: Locale,
    ): List<LocationSearchResult> {
        try {
            val requestUrl = buildSearchUrl(query, provider)
            val request = createRequest(
                url = requestUrl,
                provider = provider,
                locale = locale,
                expectsJson = true,
            )
            val response = send(
                request = request,
                applyNominatimRateLimit = isNominatimEndpoint(requestUrl),
            )
            if (response.statusCode !in SUCCESS_STATUS_CODES) {
                throw LocationSearchException(
                    SearchErrorKind.NETWORK,
                    NETWORK_ERROR_MESSAGE,
                )
            }
            return parseResults(response.body, provider, requestUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LocationSearchException) {
            throw error
        } catch (_: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw LocationSearchException(SearchErrorKind.TIMEOUT, TIMEOUT_ERROR_MESSAGE)
        } catch (_: ResponseTooLargeException) {
            currentCoroutineContext().ensureActive()
            throw LocationSearchException(
                SearchErrorKind.INVALID_RESPONSE,
                INVALID_RESPONSE_MESSAGE,
            )
        } catch (_: SerializationException) {
            throw LocationSearchException(
                SearchErrorKind.INVALID_RESPONSE,
                INVALID_RESPONSE_MESSAGE,
            )
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw LocationSearchException(SearchErrorKind.NETWORK, NETWORK_ERROR_MESSAGE)
        } catch (_: IllegalArgumentException) {
            throw LocationSearchException(
                SearchErrorKind.PROVIDER_NOT_CONFIGURED,
                INVALID_PROVIDER_MESSAGE,
            )
        }
    }

    private suspend fun send(
        request: HttpRequest,
        applyNominatimRateLimit: Boolean,
    ): HttpResponse {
        if (!applyNominatimRateLimit) {
            return executeWithTimeout(request)
        }

        reserveNominatimRequestStart()
        return executeWithTimeout(request)
    }

    private suspend fun reserveNominatimRequestStart() {
        nominatimRequestMutex.withLock {
            lastNominatimRequestStartMillis?.let { previousStart ->
                val elapsed = (clock.nowMillis() - previousStart).coerceAtLeast(0L)
                if (elapsed < MINIMUM_NOMINATIM_INTERVAL_MILLIS) {
                    clock.delayMillis(MINIMUM_NOMINATIM_INTERVAL_MILLIS - elapsed)
                    currentCoroutineContext().ensureActive()
                }
            }
            lastNominatimRequestStartMillis = clock.nowMillis()
        }
    }

    private suspend fun executeWithTimeout(request: HttpRequest): HttpResponse =
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            transport.execute(request)
        } ?: throw LocationSearchException(SearchErrorKind.TIMEOUT, TIMEOUT_ERROR_MESSAGE)

    private fun parseResults(
        body: ByteArray,
        provider: MapProvider,
        requestUrl: String,
    ): List<LocationSearchResult> {
        if (body.size > MAXIMUM_RESPONSE_BYTES) {
            throw LocationSearchException(
                SearchErrorKind.INVALID_RESPONSE,
                INVALID_RESPONSE_MESSAGE,
            )
        }
        val root = SearchJson.parseToJsonElement(decodeUtf8Strict(body))
        if (root !is JsonArray) {
            throw LocationSearchException(
                SearchErrorKind.INVALID_RESPONSE,
                INVALID_RESPONSE_MESSAGE,
            )
        }

        val attribution = if (isNominatimEndpoint(requestUrl)) {
            MapProvider.OPEN_STREET_MAP_ATTRIBUTION
        } else {
            provider.attribution
        }
        if (attribution.isBlank()) {
            throw LocationSearchException(
                SearchErrorKind.PROVIDER_NOT_CONFIGURED,
                INVALID_PROVIDER_MESSAGE,
            )
        }

        return root.take(MAXIMUM_RESULTS).map { item ->
            val objectValue = item as? JsonObject
                ?: invalidResponse()
            val labelValue = objectValue["display_name"] as? JsonPrimitive
                ?: invalidResponse()
            if (!labelValue.isString || labelValue.content.isBlank()) {
                invalidResponse()
            }
            val latitude = objectValue.readCoordinate("lat")
            val longitude = objectValue.readCoordinate("lon")
            if (
                !latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0
            ) {
                invalidResponse()
            }

            LocationSearchResult(
                label = labelValue.content,
                attribution = attribution,
                coordinate = CoordinateTransforms.toWgs84(
                    GeoCoordinate(latitude = latitude, longitude = longitude),
                    provider.coordinateSystem,
                ),
            )
        }
    }

    private fun createRequest(
        url: String,
        provider: MapProvider,
        locale: Locale,
        expectsJson: Boolean,
    ): HttpRequest {
        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to if (expectsJson) "application/json" else "*/*",
        )
        locale.toLanguageTag()
            .takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
            ?.let { headers["Accept-Language"] = it }
        if (provider.keyPlacement == ProviderKeyPlacement.HEADER) {
            val keyName = provider.keyName
            val apiKey = provider.apiKey
            if (keyName.isNullOrBlank() || !HEADER_NAME.matches(keyName) || apiKey.isNullOrBlank()) {
                throw LocationSearchException(
                    SearchErrorKind.PROVIDER_NOT_CONFIGURED,
                    INVALID_PROVIDER_MESSAGE,
                )
            }
            headers[keyName] = apiKey
        }
        return HttpRequest(
            url = url,
            headers = headers.toMap(),
            connectTimeoutMillis = REQUEST_TIMEOUT_MILLIS.toInt(),
            readTimeoutMillis = REQUEST_TIMEOUT_MILLIS.toInt(),
            maximumResponseBytes = MAXIMUM_RESPONSE_BYTES,
        )
    }

    private fun buildSearchUrl(query: String, provider: MapProvider): String {
        ensureProviderConfiguration(provider)
        val geocoderUrl = provider.geocoderUrl
        if (geocoderUrl.isNullOrBlank()) {
            throw LocationSearchException(
                SearchErrorKind.PROVIDER_NOT_CONFIGURED,
                "当前地图源未配置地点搜索服务",
            )
        }
        val endpoint = applyUrlTemplateKey(geocoderUrl, provider)
        val withSearchParameters = addQueryParameters(
            endpoint,
            listOf(
                "format" to "jsonv2",
                "limit" to MAXIMUM_RESULTS.toString(),
                "q" to query,
            ),
        )
        return validateHttpUrl(addQueryKey(withSearchParameters, provider))
    }

    private fun buildConnectionUrl(provider: MapProvider): String {
        ensureProviderConfiguration(provider)
        provider.geocoderUrl?.takeIf { it.isNotBlank() }?.let { geocoderUrl ->
            val endpoint = applyUrlTemplateKey(geocoderUrl, provider)
            val withTestParameters = addQueryParameters(
                endpoint,
                listOf(
                    "format" to "jsonv2",
                    "limit" to "1",
                    "q" to "connection test",
                ),
            )
            return validateHttpUrl(addQueryKey(withTestParameters, provider))
        }

        val resourceUrl = provider.xyzUrlTemplate?.takeIf { it.isNotBlank() }
            ?.replace(Regex("\\{z\\}", RegexOption.IGNORE_CASE), "0")
            ?.replace(Regex("\\{x\\}", RegexOption.IGNORE_CASE), "0")
            ?.replace(Regex("\\{y\\}", RegexOption.IGNORE_CASE), "0")
            ?.replace(Regex("\\{s\\}", RegexOption.IGNORE_CASE), "a")
            ?: provider.styleUrl?.takeIf {
                it.startsWith("https://", ignoreCase = true) ||
                    it.startsWith("http://", ignoreCase = true)
            }
            ?: throw LocationSearchException(
                SearchErrorKind.PROVIDER_NOT_CONFIGURED,
                INVALID_PROVIDER_MESSAGE,
            )
        val endpoint = applyUrlTemplateKey(resourceUrl, provider)
        return validateHttpUrl(addQueryKey(endpoint, provider))
    }

    private fun ensureProviderConfiguration(provider: MapProvider) {
        if (
            !PROVIDER_ID.matches(provider.id) ||
            provider.displayName.isBlank() || provider.displayName.hasControlCharacters() ||
            provider.attribution.isBlank() || provider.attribution.hasControlCharacters() ||
            provider.maxZoom !in MINIMUM_ZOOM..MAXIMUM_ZOOM ||
            (provider.styleUrl.isNullOrBlank() && provider.xyzUrlTemplate.isNullOrBlank())
        ) {
            invalidProvider()
        }
        if (
            (provider.isBuiltIn && provider != MapProvider.OpenStreetMap) ||
            (!provider.isBuiltIn && provider.id == MapProvider.OPEN_STREET_MAP_ID)
        ) {
            invalidProvider()
        }
        provider.xyzUrlTemplate?.takeIf { it.isNotBlank() }?.let { template ->
            if (
                !XYZ_Z_PLACEHOLDER.containsMatchIn(template) ||
                !XYZ_X_PLACEHOLDER.containsMatchIn(template) ||
                !XYZ_Y_PLACEHOLDER.containsMatchIn(template)
            ) {
                invalidProvider()
            }
            if (
                provider.keyPlacement == ProviderKeyPlacement.HEADER &&
                !HEADER_NAME.matches(provider.keyName.orEmpty())
            ) {
                invalidProvider()
            }
        }
        provider.styleUrl?.takeIf { it.isNotBlank() }?.let { styleUrl ->
            val isBundledStyle = styleUrl == MapProvider.BUNDLED_OSM_STYLE_URL
            val isNetworkStyle = styleUrl.startsWith("https://", ignoreCase = true) ||
                styleUrl.startsWith("http://", ignoreCase = true)
            if ((!isBundledStyle && !isNetworkStyle) || (isBundledStyle && !provider.isBuiltIn)) {
                invalidProvider()
            }
        }
        if (provider.keyPlacement != ProviderKeyPlacement.NONE) {
            val apiKey = provider.apiKey
            if (apiKey.isNullOrBlank()) {
                throw LocationSearchException(
                    SearchErrorKind.PROVIDER_NOT_CONFIGURED,
                    MISSING_KEY_MESSAGE,
                )
            }
            if (apiKey.contains('\r') || apiKey.contains('\n')) {
                invalidProvider()
            }
            if (
                provider.keyPlacement in setOf(
                    ProviderKeyPlacement.HEADER,
                    ProviderKeyPlacement.QUERY_PARAMETER,
                ) && (
                    provider.keyName.isNullOrBlank() ||
                        provider.keyName.hasControlCharacters()
                    )
            ) {
                invalidProvider()
            }
        }

        provider.xyzUrlTemplate?.takeIf { it.isNotBlank() }?.let { template ->
            val sampleUrl = template
                .replace(XYZ_Z_PLACEHOLDER, "0")
                .replace(XYZ_X_PLACEHOLDER, "0")
                .replace(XYZ_Y_PLACEHOLDER, "0")
                .replace(Regex("\\{s\\}", RegexOption.IGNORE_CASE), "a")
            validateProviderResourceUrl(sampleUrl, provider)
        }
        provider.styleUrl?.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true)
        }?.let { validateProviderResourceUrl(it, provider) }
        provider.geocoderUrl?.takeIf { it.isNotBlank() }
            ?.let { validateProviderResourceUrl(it, provider) }
    }

    private fun validateProviderResourceUrl(url: String, provider: MapProvider) {
        val withTemplateKey = applyUrlTemplateKey(url, provider)
        validateHttpUrl(addQueryKey(withTemplateKey, provider))
    }

    private fun applyUrlTemplateKey(url: String, provider: MapProvider): String {
        if (provider.keyPlacement != ProviderKeyPlacement.URL_TEMPLATE) {
            return url
        }
        if (!KEY_PLACEHOLDER.containsMatchIn(url)) {
            throw LocationSearchException(
                SearchErrorKind.PROVIDER_NOT_CONFIGURED,
                INVALID_PROVIDER_MESSAGE,
            )
        }
        return KEY_PLACEHOLDER.replace(url, encodeQueryComponent(provider.apiKey.orEmpty()))
    }

    private fun addQueryKey(url: String, provider: MapProvider): String {
        if (provider.keyPlacement != ProviderKeyPlacement.QUERY_PARAMETER) {
            return url
        }
        return addQueryParameters(
            url,
            listOf(provider.keyName.orEmpty() to provider.apiKey.orEmpty()),
        )
    }

    private fun addQueryParameters(
        url: String,
        values: List<Pair<String, String>>,
    ): String {
        val fragmentIndex = url.indexOf('#')
        val base = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val encodedValues = values.joinToString("&") { (name, value) ->
            "${encodeQueryComponent(name)}=${encodeQueryComponent(value)}"
        }
        val separator = when {
            !base.contains('?') -> "?"
            base.endsWith('?') || base.endsWith('&') -> ""
            else -> "&"
        }
        return "$base$separator$encodedValues$fragment"
    }

    private fun validateHttpUrl(value: String): String {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw LocationSearchException(
                SearchErrorKind.PROVIDER_NOT_CONFIGURED,
                INVALID_PROVIDER_MESSAGE,
            )
        }
        if (
            !uri.isAbsolute ||
            uri.host.isNullOrBlank() ||
            uri.userInfo != null ||
            !uri.scheme.equals("https", ignoreCase = true)
        ) {
            throw LocationSearchException(
                SearchErrorKind.PROVIDER_NOT_CONFIGURED,
                INVALID_PROVIDER_MESSAGE,
            )
        }
        return uri.toASCIIString()
    }

    private fun isNominatimEndpoint(value: String): Boolean {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("nominatim.openstreetmap.org", ignoreCase = true) &&
            uri.path.orEmpty().trimEnd('/').equals("/search", ignoreCase = true)
    }

    private fun JsonObject.readCoordinate(name: String): Double {
        val primitive = this[name] as? JsonPrimitive ?: invalidResponse()
        return if (primitive.isString) {
            primitive.content.toDoubleOrNull() ?: invalidResponse()
        } else {
            primitive.doubleOrNull ?: invalidResponse()
        }
    }

    private fun decodeUtf8Strict(body: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
            .toString()
    } catch (_: CharacterCodingException) {
        invalidResponse()
    }

    private fun invalidResponse(): Nothing = throw LocationSearchException(
        SearchErrorKind.INVALID_RESPONSE,
        INVALID_RESPONSE_MESSAGE,
    )

    private fun invalidProvider(): Nothing = throw LocationSearchException(
        SearchErrorKind.PROVIDER_NOT_CONFIGURED,
        INVALID_PROVIDER_MESSAGE,
    )

    private fun String?.hasControlCharacters(): Boolean =
        this?.any { it.code < 0x20 || it.code == 0x7f } == true

    private fun connectionFailure(
        kind: SearchErrorKind,
        message: String,
    ): ProviderConnectionResult = ProviderConnectionResult(
        isSuccess = false,
        message = message,
        errorKind = kind,
    )

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val USER_AGENT =
            "fitGenerator/1.0 (+https://github.com/Alpha-Auxiliary/fitGenerator)"
        const val MISSING_KEY_MESSAGE = "当前地图源需要 API Key，但尚未配置"
        const val INVALID_PROVIDER_MESSAGE = "地图服务配置无效"
        const val NETWORK_ERROR_MESSAGE = "地点搜索服务暂时不可用"
        const val TIMEOUT_ERROR_MESSAGE = "地点搜索请求超时"
        const val INVALID_RESPONSE_MESSAGE = "地点搜索服务返回了无效数据"
        const val MAXIMUM_RESULTS = 5
        const val MAXIMUM_RESPONSE_BYTES = 1024 * 1024
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val MINIMUM_NOMINATIM_INTERVAL_MILLIS = 1_000L
        const val MINIMUM_ZOOM = 0
        const val MAXIMUM_ZOOM = 24

        val SUCCESS_STATUS_CODES = 200..299
        val KEY_PLACEHOLDER = Regex("\\{key\\}", RegexOption.IGNORE_CASE)
        val XYZ_Z_PLACEHOLDER = Regex("\\{z\\}", RegexOption.IGNORE_CASE)
        val XYZ_X_PLACEHOLDER = Regex("\\{x\\}", RegexOption.IGNORE_CASE)
        val XYZ_Y_PLACEHOLDER = Regex("\\{y\\}", RegexOption.IGNORE_CASE)
        val HEADER_NAME = Regex("^[A-Za-z0-9-]+$")
        val PROVIDER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        val SearchJson = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
    }
}

private class ResponseTooLargeException : IOException()

private fun HttpURLConnection.disconnectQuietly() {
    try {
        disconnect()
    } catch (_: Exception) {
        // Cancellation and cleanup must never surface provider details or mask the main result.
    }
}

private fun InputStream.readWithLimit(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_SIZE, maximumBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) {
            break
        }
        totalBytes += count
        if (totalBytes > maximumBytes) {
            throw ResponseTooLargeException()
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
