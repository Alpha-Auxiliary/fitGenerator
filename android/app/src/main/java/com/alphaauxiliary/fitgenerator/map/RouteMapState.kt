package com.alphaauxiliary.fitgenerator.map

import com.alphaauxiliary.fitgenerator.core.ActivityModelDto

internal data class MapRenderConfiguration(
    val style: MapStyleDefinition,
    val coordinateSystem: CoordinateSystem,
    val maximumZoom: Int,
    val attribution: String,
) {
    // A custom style can contain a provider key. Never expose it through diagnostics.
    override fun toString(): String =
        "MapRenderConfiguration(style=<redacted>, coordinateSystem=$coordinateSystem, " +
            "maximumZoom=$maximumZoom, attribution=<redacted>)"
}

internal data class RouteMapState(
    val canonicalWgs84Points: List<GeoCoordinate>,
    val renderedProviderPoints: List<GeoCoordinate>,
    val previewProviderPoint: GeoCoordinate?,
    val selectedVertexIndex: Int?,
    val isDrawing: Boolean,
    val camera: GeoCoordinate,
    val cameraZoom: Double,
    val renderConfiguration: MapRenderConfiguration,
) {
    init {
        require(canonicalWgs84Points.size <= MAXIMUM_ROUTE_POINTS) {
            "路线点不能超过 $MAXIMUM_ROUTE_POINTS 个"
        }
        require(canonicalWgs84Points.size == renderedProviderPoints.size) {
            "路线渲染点必须与 WGS-84 路线逐点对应"
        }
        require(canonicalWgs84Points.all { it.isValid() }) {
            "WGS-84 路线包含无效坐标"
        }
        require(renderedProviderPoints.all { it.isValid() }) {
            "地图路线包含无效坐标"
        }
        require(previewProviderPoint == null || previewProviderPoint.isValid()) {
            "预览位置坐标无效"
        }
        require(selectedVertexIndex == null || selectedVertexIndex in canonicalWgs84Points.indices) {
            "选中的路线点不存在"
        }
        require(camera.isValid())
        require(cameraZoom.isFinite() && cameraZoom >= 0.0)
    }

    fun providerPointToWgs84(point: GeoCoordinate): GeoCoordinate =
        CoordinateTransforms.toWgs84(point, renderConfiguration.coordinateSystem)

    companion object {
        fun create(
            routeWgs84: List<GeoCoordinate>,
            preview: ActivityModelDto?,
            previewSampleIndex: Int?,
            selectedVertexIndex: Int?,
            isDrawing: Boolean,
            cameraWgs84: GeoCoordinate,
            cameraZoom: Double,
            provider: MapProvider,
        ): RouteMapState {
            val renderedPoints = routeWgs84.map { point ->
                CoordinateTransforms.fromWgs84(point, provider.coordinateSystem)
            }
            val previewWgs84 = previewSampleIndex
                ?.let { index -> preview?.samples?.getOrNull(index) }
                ?.toWgs84OrNull()
            return RouteMapState(
                canonicalWgs84Points = routeWgs84.toList(),
                renderedProviderPoints = renderedPoints,
                previewProviderPoint = previewWgs84?.let { point ->
                    CoordinateTransforms.fromWgs84(point, provider.coordinateSystem)
                },
                selectedVertexIndex = selectedVertexIndex,
                isDrawing = isDrawing,
                camera = CoordinateTransforms.fromWgs84(
                    cameraWgs84,
                    provider.coordinateSystem,
                ),
                cameraZoom = cameraZoom.coerceAtMost(provider.maxZoom.toDouble()),
                renderConfiguration = MapRenderConfiguration(
                    style = provider.mapStyleDefinition(),
                    coordinateSystem = provider.coordinateSystem,
                    maximumZoom = provider.maxZoom,
                    attribution = provider.attribution,
                ),
            )
        }

        private fun com.alphaauxiliary.fitgenerator.core.ActivitySampleDto.toWgs84OrNull():
            GeoCoordinate? {
            val latitude = positionLatSemicircles.toDegrees()
            val longitude = positionLongSemicircles.toDegrees()
            return if (
                latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0
            ) {
                GeoCoordinate(latitude = latitude, longitude = longitude)
            } else {
                null
            }
        }

        private fun Int.toDegrees(): Double = toDouble() * DEGREES_PER_SEMICIRCLE

        private fun GeoCoordinate.isValid(): Boolean =
            latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0

        private const val DEGREES_PER_SEMICIRCLE = 180.0 / 2_147_483_648.0
        private const val MAXIMUM_ROUTE_POINTS = 50_000
    }
}
