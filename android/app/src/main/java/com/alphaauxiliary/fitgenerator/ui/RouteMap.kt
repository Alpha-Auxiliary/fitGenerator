package com.alphaauxiliary.fitgenerator.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.PointF
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import com.alphaauxiliary.fitgenerator.map.MapStyleDefinition
import com.alphaauxiliary.fitgenerator.map.RouteMapState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs

@Composable
internal fun RouteMap(
    state: RouteMapState,
    onDrawPoint: (GeoCoordinate) -> Unit,
    onSelectVertex: (Int?) -> Unit,
    onMoveSelectedVertex: (GeoCoordinate) -> Unit,
    onCameraChanged: (GeoCoordinate, Double) -> Unit,
    onMapError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val applicationContext = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val bridge = remember(lifecycle) { RouteMapBridge() }
    bridge.updateCallbacks(
        onDrawPoint = onDrawPoint,
        onSelectVertex = onSelectVertex,
        onMoveSelectedVertex = onMoveSelectedVertex,
        onCameraChanged = onCameraChanged,
        onMapError = onMapError,
    )

    DisposableEffect(applicationContext, lifecycle, bridge) {
        val observer = LifecycleEventObserver { _, event -> bridge.onLifecycleEvent(event) }
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() = bridge.onLowMemory()

            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    bridge.onLowMemory()
                }
            }
        }
        lifecycle.addObserver(observer)
        applicationContext.registerComponentCallbacks(memoryCallbacks)
        onDispose {
            lifecycle.removeObserver(observer)
            applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            bridge.destroy()
        }
    }

    Box(modifier = modifier) {
        key(bridge) {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { viewContext ->
                    MapLibre.getInstance(viewContext.applicationContext)
                    MapView(viewContext).also { mapView ->
                        bridge.attach(mapView, lifecycle)
                    }
                },
                update = {
                    bridge.render(state)
                },
            )
        }

        if (state.isDrawing) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .semantics {
                        contentDescription = "路线绘制区域；拖动手指添加路线点"
                    }
                    .pointerInput(bridge, state.renderConfiguration.coordinateSystem) {
                        detectDragGestures(
                            onDragStart = { offset -> bridge.drawAt(offset.x, offset.y) },
                            onDrag = { change, _ ->
                                if (change.positionChanged()) {
                                    bridge.drawAt(change.position.x, change.position.y)
                                    change.consume()
                                }
                            },
                        )
                    },
            )
        }
    }
}

private class RouteMapBridge {
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var latestState: RouteMapState? = null
    private var requestedStyle: MapStyleDefinition? = null
    private var loadedStyle: MapStyleDefinition? = null
    private var loadingStyle: MapStyleDefinition? = null
    private var offlineFallbackForStyle: MapStyleDefinition? = null
    private var styleGeneration = 0L
    private var tileFailureWindowStartedAt = 0L
    private var tileFailureCount = 0
    private var onlineRetryAttempt = 0
    private var onlineRetry: Runnable? = null
    private var isStarted = false
    private var isResumed = false
    private var isDestroyed = false

    private var onDrawPoint: (GeoCoordinate) -> Unit = {}
    private var onSelectVertex: (Int?) -> Unit = {}
    private var onMoveSelectedVertex: (GeoCoordinate) -> Unit = {}
    private var onCameraChanged: (GeoCoordinate, Double) -> Unit = { _, _ -> }
    private var onMapError: (String) -> Unit = {}

    fun updateCallbacks(
        onDrawPoint: (GeoCoordinate) -> Unit,
        onSelectVertex: (Int?) -> Unit,
        onMoveSelectedVertex: (GeoCoordinate) -> Unit,
        onCameraChanged: (GeoCoordinate, Double) -> Unit,
        onMapError: (String) -> Unit,
    ) {
        this.onDrawPoint = onDrawPoint
        this.onSelectVertex = onSelectVertex
        this.onMoveSelectedVertex = onMoveSelectedVertex
        this.onCameraChanged = onCameraChanged
        this.onMapError = onMapError
    }

    fun attach(view: MapView, lifecycle: Lifecycle) {
        if (mapView === view) return
        mapView = view
        isDestroyed = false
        view.onCreate(null)
        view.addOnDidFailLoadingMapListener { _ ->
            if (isDestroyed || mapView !== view) return@addOnDidFailLoadingMapListener
            view.post {
                if (isDestroyed || mapView !== view) return@post
                if (loadingStyle != null) {
                    activateOfflineFallback(view)
                } else {
                    recordTileFailure(view)
                }
            }
        }
        view.addOnTileActionListener { operation, _, _, _, _, _, _ ->
            if (isDestroyed || mapView !== view) return@addOnTileActionListener
            when (operation) {
                TileOperation.Error -> view.post {
                    if (!isDestroyed && mapView === view) recordTileFailure(view)
                }
                TileOperation.EndParse -> view.post {
                    if (!isDestroyed && mapView === view && offlineFallbackForStyle == null) {
                        resetTileFailureWindow()
                        onlineRetryAttempt = 0
                    }
                }
                else -> Unit
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        view.getMapAsync { readyMap ->
            if (isDestroyed || mapView !== view) return@getMapAsync
            map = readyMap
            readyMap.uiSettings.isRotateGesturesEnabled = false
            readyMap.uiSettings.isTiltGesturesEnabled = false
            readyMap.addOnMapClickListener { coordinate ->
                !isDestroyed && mapView === view && selectVertexAt(readyMap, coordinate)
            }
            readyMap.addOnMapLongClickListener { coordinate ->
                !isDestroyed && mapView === view && moveSelectedVertexTo(coordinate)
            }
            readyMap.addOnCameraIdleListener {
                if (!isDestroyed && mapView === view) reportCamera(readyMap)
            }
            latestState?.let(::render)
        }
    }

    fun render(state: RouteMapState) {
        if (isDestroyed) return
        latestState = state
        val readyMap = map ?: return
        try {
            readyMap.setMaxZoomPreference(state.renderConfiguration.maximumZoom.toDouble())
            updateCamera(readyMap, state)
            val styleDefinition = state.renderConfiguration.style
            if (requestedStyle != styleDefinition) {
                requestedStyle = styleDefinition
                cancelOnlineRetry()
                offlineFallbackForStyle = null
                loadedStyle = null
                loadingStyle = null
                styleGeneration += 1
                onlineRetryAttempt = 0
                resetTileFailureWindow()
            }
            if (offlineFallbackForStyle == styleDefinition) {
                if (loadedStyle == styleDefinition) {
                    readyMap.style?.let { style -> updateRouteData(style, state) }
                }
                return
            }
            val styleNeedsLoading = if (loadingStyle == null) {
                loadedStyle != styleDefinition
            } else {
                loadingStyle != styleDefinition
            }
            if (styleNeedsLoading) {
                loadingStyle = styleDefinition
                val generation = ++styleGeneration
                readyMap.setStyle(styleDefinition.toStyleBuilder()) { style ->
                    if (
                        isDestroyed ||
                        generation != styleGeneration ||
                        latestState?.renderConfiguration?.style != styleDefinition
                    ) {
                        if (loadingStyle == styleDefinition) loadingStyle = null
                        return@setStyle
                    }
                    try {
                        installRouteLayers(style)
                        latestState?.let { latest -> updateRouteData(style, latest) }
                        loadedStyle = styleDefinition
                        loadingStyle = null
                        resetTileFailureWindow()
                    } catch (_: Exception) {
                        loadedStyle = null
                        loadingStyle = null
                        activateOfflineFallback(requireNotNull(mapView))
                    }
                }
            } else if (loadedStyle == styleDefinition) {
                readyMap.style?.let { style -> updateRouteData(style, state) }
            }
        } catch (_: Exception) {
            loadingStyle = null
            activateOfflineFallback(mapView ?: return)
        }
    }

    fun drawAt(x: Float, y: Float) {
        val readyMap = map ?: return
        val state = latestState ?: return
        if (!state.isDrawing) return
        try {
            val coordinate = readyMap.projection.fromScreenLocation(PointF(x, y))
            val providerPoint = GeoCoordinate(
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
            )
            onDrawPoint(state.providerPointToWgs84(providerPoint))
        } catch (_: IllegalArgumentException) {
            onMapError("这个位置无法加入路线，请在地图范围内继续绘制")
        }
    }

    fun onLifecycleEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> destroy()
            Lifecycle.Event.ON_CREATE,
            Lifecycle.Event.ON_ANY,
            -> Unit
        }
    }

    fun onLowMemory() {
        if (!isDestroyed) mapView?.onLowMemory()
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        cancelOnlineRetry()
        pause()
        stop()
        mapView?.onDestroy()
        map = null
        mapView = null
        latestState = null
        requestedStyle = null
        loadedStyle = null
        loadingStyle = null
        offlineFallbackForStyle = null
        resetTileFailureWindow()
        onlineRetryAttempt = 0
        styleGeneration += 1
        onDrawPoint = {}
        onSelectVertex = {}
        onMoveSelectedVertex = {}
        onCameraChanged = { _, _ -> }
        onMapError = {}
    }

    private fun start() {
        if (!isStarted && !isDestroyed) {
            val view = mapView ?: return
            view.onStart()
            isStarted = true
        }
    }

    private fun resume() {
        if (!isResumed && !isDestroyed) {
            val view = mapView ?: return
            if (!isStarted) start()
            view.onResume()
            isResumed = true
        }
    }

    private fun pause() {
        if (isResumed) {
            mapView?.onPause()
            isResumed = false
        }
    }

    private fun stop() {
        if (isResumed) pause()
        if (isStarted) {
            mapView?.onStop()
            isStarted = false
        }
    }

    private fun recordTileFailure(view: MapView) {
        if (offlineFallbackForStyle != null) return
        val now = SystemClock.elapsedRealtime()
        if (
            tileFailureWindowStartedAt == 0L ||
            now - tileFailureWindowStartedAt > TILE_FAILURE_WINDOW_MS
        ) {
            tileFailureWindowStartedAt = now
            tileFailureCount = 0
        }
        tileFailureCount += 1
        if (tileFailureCount >= TILE_FAILURE_THRESHOLD) {
            activateOfflineFallback(view)
        }
    }

    private fun activateOfflineFallback(view: MapView) {
        if (isDestroyed || mapView !== view) return
        val requested = latestState?.renderConfiguration?.style ?: return
        if (offlineFallbackForStyle == requested) return
        val readyMap = map ?: return

        offlineFallbackForStyle = requested
        loadedStyle = null
        loadingStyle = requested
        resetTileFailureWindow()
        val generation = ++styleGeneration
        try {
            readyMap.setStyle(OFFLINE_STYLE.toStyleBuilder()) { style ->
                if (
                    isDestroyed ||
                    mapView !== view ||
                    generation != styleGeneration ||
                    offlineFallbackForStyle != requested ||
                    latestState?.renderConfiguration?.style != requested
                ) {
                    if (loadingStyle == requested) loadingStyle = null
                    return@setStyle
                }
                try {
                    installRouteLayers(style)
                    latestState?.let { latest -> updateRouteData(style, latest) }
                    loadedStyle = requested
                    loadingStyle = null
                    onMapError(
                        "在线底图不可达，已自动切换到离线绘图底板；路线、预览和导出仍可使用",
                    )
                    scheduleOnlineRetry(view, requested)
                } catch (_: Exception) {
                    loadedStyle = null
                    loadingStyle = null
                    onMapError("地图渲染暂时不可用；路线数据仍已安全保留")
                }
            }
        } catch (_: Exception) {
            loadedStyle = null
            loadingStyle = null
            onMapError("地图渲染暂时不可用；路线数据仍已安全保留")
        }
    }

    private fun scheduleOnlineRetry(view: MapView, requested: MapStyleDefinition) {
        cancelOnlineRetry()
        onlineRetryAttempt = (onlineRetryAttempt + 1).coerceAtMost(RETRY_DELAYS_MS.size)
        val delay = RETRY_DELAYS_MS[onlineRetryAttempt - 1]
        val retry = Runnable {
            onlineRetry = null
            if (
                isDestroyed ||
                mapView !== view ||
                offlineFallbackForStyle != requested ||
                latestState?.renderConfiguration?.style != requested
            ) {
                return@Runnable
            }
            offlineFallbackForStyle = null
            loadedStyle = null
            loadingStyle = null
            styleGeneration += 1
            latestState?.let(::render)
        }
        onlineRetry = retry
        view.postDelayed(retry, delay)
    }

    private fun cancelOnlineRetry() {
        onlineRetry?.let { retry -> mapView?.removeCallbacks(retry) }
        onlineRetry = null
    }

    private fun resetTileFailureWindow() {
        tileFailureWindowStartedAt = 0L
        tileFailureCount = 0
    }

    private fun installRouteLayers(style: Style) {
        val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, empty))
        style.addSource(GeoJsonSource(VERTEX_SOURCE_ID, empty))
        style.addSource(GeoJsonSource(SELECTED_VERTEX_SOURCE_ID, empty))
        style.addSource(GeoJsonSource(PREVIEW_SOURCE_ID, empty))

        style.addLayer(
            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                lineColor(TRACK_RED),
                lineWidth(5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            CircleLayer(VERTEX_LAYER_ID, VERTEX_SOURCE_ID).withProperties(
                circleColor(FIELD_PAPER),
                circleRadius(5f),
                circleStrokeColor(SCOREBOARD_INK),
                circleStrokeWidth(2f),
            ),
        )
        style.addLayer(
            CircleLayer(SELECTED_VERTEX_LAYER_ID, SELECTED_VERTEX_SOURCE_ID).withProperties(
                circleColor(TRACK_RED),
                circleRadius(8f),
                circleStrokeColor(FIELD_PAPER),
                circleStrokeWidth(2f),
            ),
        )
        style.addLayer(
            CircleLayer(PREVIEW_LAYER_ID, PREVIEW_SOURCE_ID).withProperties(
                circleColor(PACE_TEAL),
                circleRadius(8f),
                circleStrokeColor(SCOREBOARD_INK),
                circleStrokeWidth(2f),
            ),
        )
    }

    private fun updateRouteData(style: Style, state: RouteMapState) {
        val renderedPoints = state.renderedProviderPoints.map { point ->
            Point.fromLngLat(point.longitude, point.latitude)
        }
        val routeFeatures = if (renderedPoints.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(renderedPoints)))
        } else {
            emptyList()
        }
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)?.setGeoJson(
            FeatureCollection.fromFeatures(routeFeatures),
        )

        val vertexFeatures = renderedPoints.mapIndexed { index, point ->
            Feature.fromGeometry(point).apply { addNumberProperty(VERTEX_INDEX_PROPERTY, index) }
        }
        style.getSourceAs<GeoJsonSource>(VERTEX_SOURCE_ID)?.setGeoJson(
            FeatureCollection.fromFeatures(vertexFeatures),
        )

        val selectedFeatures = state.selectedVertexIndex
            ?.let(renderedPoints::getOrNull)
            ?.let { point -> listOf(Feature.fromGeometry(point)) }
            ?: emptyList()
        style.getSourceAs<GeoJsonSource>(SELECTED_VERTEX_SOURCE_ID)?.setGeoJson(
            FeatureCollection.fromFeatures(selectedFeatures),
        )

        val previewFeatures = state.previewProviderPoint
            ?.let { point -> Point.fromLngLat(point.longitude, point.latitude) }
            ?.let { point -> listOf(Feature.fromGeometry(point)) }
            ?: emptyList()
        style.getSourceAs<GeoJsonSource>(PREVIEW_SOURCE_ID)?.setGeoJson(
            FeatureCollection.fromFeatures(previewFeatures),
        )
    }

    private fun updateCamera(map: MapLibreMap, state: RouteMapState) {
        val target = map.cameraPosition.target
        val zoom = map.cameraPosition.zoom
        if (
            target == null ||
            abs(target.latitude - state.camera.latitude) > CAMERA_EPSILON ||
            abs(target.longitude - state.camera.longitude) > CAMERA_EPSILON ||
            abs(zoom - state.cameraZoom) > ZOOM_EPSILON
        ) {
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(state.camera.latitude, state.camera.longitude))
                        .zoom(state.cameraZoom)
                        .build(),
                ),
            )
        }
    }

    private fun reportCamera(map: MapLibreMap) {
        val state = latestState ?: return
        val target = map.cameraPosition.target ?: return
        if (
            abs(target.latitude - state.camera.latitude) <= CAMERA_EPSILON &&
            abs(target.longitude - state.camera.longitude) <= CAMERA_EPSILON &&
            abs(map.cameraPosition.zoom - state.cameraZoom) <= ZOOM_EPSILON
        ) {
            return
        }
        try {
            val wgs84 = state.providerPointToWgs84(
                GeoCoordinate(target.latitude, target.longitude),
            )
            onCameraChanged(wgs84, map.cameraPosition.zoom)
        } catch (_: IllegalArgumentException) {
            onMapError("地图视口超出有效范围，已保留当前路线")
        }
    }

    private fun selectVertexAt(map: MapLibreMap, coordinate: LatLng): Boolean {
        return try {
            val screenPoint = map.projection.toScreenLocation(coordinate)
            val index = map.queryRenderedFeatures(screenPoint, VERTEX_LAYER_ID)
                .firstOrNull()
                ?.getNumberProperty(VERTEX_INDEX_PROPERTY)
                ?.toInt()
            onSelectVertex(index)
            index != null
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun moveSelectedVertexTo(coordinate: LatLng): Boolean {
        val state = latestState ?: return false
        if (state.selectedVertexIndex == null) return false
        return try {
            val point = state.providerPointToWgs84(
                GeoCoordinate(coordinate.latitude, coordinate.longitude),
            )
            onMoveSelectedVertex(point)
            true
        } catch (_: IllegalArgumentException) {
            onMapError("这个位置无法用于编辑路线点")
            false
        }
    }

    private companion object {
        const val ROUTE_SOURCE_ID = "fit-route-source"
        const val VERTEX_SOURCE_ID = "fit-route-vertex-source"
        const val SELECTED_VERTEX_SOURCE_ID = "fit-selected-vertex-source"
        const val PREVIEW_SOURCE_ID = "fit-preview-marker-source"
        const val ROUTE_LAYER_ID = "fit-route-line"
        const val VERTEX_LAYER_ID = "fit-route-vertices"
        const val SELECTED_VERTEX_LAYER_ID = "fit-selected-vertex"
        const val PREVIEW_LAYER_ID = "fit-preview-marker"
        const val VERTEX_INDEX_PROPERTY = "vertex-index"
        const val TRACK_RED = "#D14E39"
        const val PACE_TEAL = "#1D6F78"
        const val FIELD_PAPER = "#F3F6F1"
        const val SCOREBOARD_INK = "#16221C"
        const val CAMERA_EPSILON = 1e-7
        const val ZOOM_EPSILON = 0.01
        const val TILE_FAILURE_THRESHOLD = 6
        const val TILE_FAILURE_WINDOW_MS = 15_000L
        val RETRY_DELAYS_MS = longArrayOf(30_000L, 120_000L, 300_000L)
        val OFFLINE_STYLE = MapStyleDefinition(
            json = """
                {
                  "version": 8,
                  "name": "Offline route canvas",
                  "sources": {},
                  "layers": [
                    {
                      "id": "offline-background",
                      "type": "background",
                      "paint": { "background-color": "#E4E9E2" }
                    }
                  ]
                }
            """.trimIndent(),
        )
    }
}

private fun MapStyleDefinition.toStyleBuilder(): Style.Builder =
    uri?.let { styleUri -> Style.Builder().fromUri(styleUri) }
        ?: Style.Builder().fromJson(requireNotNull(json))
