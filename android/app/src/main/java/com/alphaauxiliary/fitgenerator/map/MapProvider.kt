package com.alphaauxiliary.fitgenerator.map

import java.net.URLEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class MapStyleDefinition(
    val uri: String? = null,
    val json: String? = null,
) {
    init {
        require((uri == null) != (json == null)) {
            "地图样式必须且只能指定 URI 或 JSON"
        }
        require(!uri.isNullOrBlank() || !json.isNullOrBlank()) {
            "地图样式不能为空"
        }
    }

    // Query/URL-template keys can be present in the style. Never render either value.
    override fun toString(): String = "MapStyleDefinition(<redacted>)"
}

internal enum class ProviderKeyPlacement {
    NONE,
    URL_TEMPLATE,
    HEADER,
    QUERY_PARAMETER,
}

internal data class MapProvider(
    val id: String,
    val displayName: String,
    val styleUrl: String?,
    val xyzUrlTemplate: String?,
    val keyPlacement: ProviderKeyPlacement,
    val keyName: String?,
    val apiKey: String?,
    val coordinateSystem: CoordinateSystem,
    val maxZoom: Int,
    val attribution: String,
    val geocoderUrl: String?,
    val isBuiltIn: Boolean,
) {
    val isDefault: Boolean
        get() = isBuiltIn && id == OPEN_STREET_MAP_ID

    // Provider configuration may contain a secret, so its default data-class rendering is unsafe.
    override fun toString(): String = displayName

    fun androidRenderingIssue(): String? = when {
        keyPlacement == ProviderKeyPlacement.HEADER ->
            "Android 地图渲染不支持 Header Key，请改用查询参数或 URL 占位符"
        keyPlacement != ProviderKeyPlacement.NONE && apiKey.isNullOrBlank() ->
            "当前地图源需要 API Key，请在地图设置中补充"
        styleUrl.isNullOrBlank() && xyzUrlTemplate.isNullOrBlank() ->
            "当前地图源未配置样式或 XYZ 地址"
        styleUrl?.let { style ->
            style != BUNDLED_OSM_STYLE_URL && !style.startsWith("https://", ignoreCase = true)
        } == true -> "Android 地图样式地址必须使用 HTTPS"
        styleUrl == BUNDLED_OSM_STYLE_URL && !isBuiltIn ->
            "自定义地图源不能冒充内置地图样式"
        styleUrl.isNullOrBlank() &&
            xyzUrlTemplate?.startsWith("https://", ignoreCase = true) != true ->
            "Android XYZ 地图地址必须使用 HTTPS"
        keyPlacement == ProviderKeyPlacement.URL_TEMPLATE &&
            !KEY_PLACEHOLDER.containsMatchIn(styleUrl ?: xyzUrlTemplate.orEmpty()) ->
            "Android 地图 URL 模板缺少 {key} 占位符"
        keyPlacement == ProviderKeyPlacement.QUERY_PARAMETER && keyName.isNullOrBlank() ->
            "Android 地图查询 Key 缺少参数名称"
        else -> null
    }

    fun mapStyleDefinition(): MapStyleDefinition {
        androidRenderingIssue()?.let { issue -> throw IllegalArgumentException(issue) }
        styleUrl?.takeUnless { it.isBlank() }?.let { style ->
            return MapStyleDefinition(uri = applyKey(style))
        }
        val tileUrl = applyKey(requireNotNull(xyzUrlTemplate))
        return MapStyleDefinition(
            json = buildRasterStyleJson(
                tileUrl = tileUrl,
                maximumZoom = maxZoom,
                attribution = attribution,
            ),
        )
    }

    private fun applyKey(url: String): String = when (keyPlacement) {
        ProviderKeyPlacement.NONE -> url
        ProviderKeyPlacement.URL_TEMPLATE -> {
            val key = requireNotNull(apiKey).encodeUrlComponent()
            require(KEY_PLACEHOLDER.containsMatchIn(url)) {
                "URL 模板缺少 {key} 占位符"
            }
            KEY_PLACEHOLDER.replace(url, key)
        }
        ProviderKeyPlacement.QUERY_PARAMETER -> {
            val name = requireNotNull(keyName).encodeUrlComponent()
            val key = requireNotNull(apiKey).encodeUrlComponent()
            val fragmentIndex = url.indexOf('#')
            val base = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
            val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
            val separator = when {
                !base.contains('?') -> "?"
                base.endsWith('?') || base.endsWith('&') -> ""
                else -> "&"
            }
            "$base$separator$name=$key$fragment"
        }
        ProviderKeyPlacement.HEADER -> error("Header Key 不可进入 Android 地图渲染")
    }

    companion object {
        const val OPEN_STREET_MAP_ID = "osm"
        const val BUNDLED_OSM_STYLE_URL = "asset://osm-raster-style.json"
        const val OPEN_STREET_MAP_TILE_URL =
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
        const val OPEN_STREET_MAP_ATTRIBUTION = "© OpenStreetMap contributors"

        val OpenStreetMap: MapProvider = MapProvider(
            id = OPEN_STREET_MAP_ID,
            displayName = "OpenStreetMap",
            styleUrl = BUNDLED_OSM_STYLE_URL,
            xyzUrlTemplate = OPEN_STREET_MAP_TILE_URL,
            keyPlacement = ProviderKeyPlacement.NONE,
            keyName = null,
            apiKey = null,
            coordinateSystem = CoordinateSystem.WGS84,
            maxZoom = 19,
            attribution = OPEN_STREET_MAP_ATTRIBUTION,
            geocoderUrl = NOMINATIM_SEARCH_URL,
            isBuiltIn = true,
        )

        val BuiltIns: List<MapProvider> = listOf(OpenStreetMap)

        private val KEY_PLACEHOLDER = Regex("\\{key\\}", RegexOption.IGNORE_CASE)

        fun custom(
            id: String,
            displayName: String,
            xyzUrlTemplate: String?,
            coordinateSystem: CoordinateSystem,
            maxZoom: Int,
            attribution: String,
            geocoderUrl: String?,
            styleUrl: String? = null,
            keyPlacement: ProviderKeyPlacement = ProviderKeyPlacement.NONE,
            keyName: String? = null,
            apiKey: String? = null,
        ): MapProvider = MapProvider(
            id = id,
            displayName = displayName,
            styleUrl = styleUrl,
            xyzUrlTemplate = xyzUrlTemplate,
            keyPlacement = keyPlacement,
            keyName = keyName,
            apiKey = apiKey,
            coordinateSystem = coordinateSystem,
            maxZoom = maxZoom,
            attribution = attribution,
            geocoderUrl = geocoderUrl,
            isBuiltIn = false,
        )

        private fun buildRasterStyleJson(
            tileUrl: String,
            maximumZoom: Int,
            attribution: String,
        ): String = buildJsonObject {
            put("version", 8)
            put("name", "Custom raster map")
            put(
                "sources",
                buildJsonObject {
                    put(
                        "custom-raster",
                        buildJsonObject {
                            put("type", "raster")
                            put("tiles", buildJsonArray { add(JsonPrimitive(tileUrl)) })
                            put("tileSize", 256)
                            put("minzoom", 0)
                            put("maxzoom", maximumZoom)
                            put("attribution", attribution)
                        },
                    )
                },
            )
            put(
                "layers",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "custom-raster-layer")
                            put("type", "raster")
                            put("source", "custom-raster")
                            put("minzoom", 0)
                            put("maxzoom", maximumZoom)
                        },
                    )
                },
            )
        }.toString()
    }
}

private fun String.encodeUrlComponent(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
