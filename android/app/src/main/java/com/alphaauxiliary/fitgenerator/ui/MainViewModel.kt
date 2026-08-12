package com.alphaauxiliary.fitgenerator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphaauxiliary.fitgenerator.core.ActivityInput
import com.alphaauxiliary.fitgenerator.core.ActivityModelDto
import com.alphaauxiliary.fitgenerator.core.ActivityRoutePoint
import com.alphaauxiliary.fitgenerator.core.CoreException
import com.alphaauxiliary.fitgenerator.core.NativeCoreService
import com.alphaauxiliary.fitgenerator.data.AppSettings
import com.alphaauxiliary.fitgenerator.data.AppSettingsRepository
import com.alphaauxiliary.fitgenerator.data.SavedRoute
import com.alphaauxiliary.fitgenerator.data.SavedRouteRepository
import com.alphaauxiliary.fitgenerator.export.ExportDocumentRequest
import com.alphaauxiliary.fitgenerator.export.ExportErrorCode
import com.alphaauxiliary.fitgenerator.export.ExportException
import com.alphaauxiliary.fitgenerator.export.ExportShareRequest
import com.alphaauxiliary.fitgenerator.map.CoordinateTransforms
import com.alphaauxiliary.fitgenerator.map.CoordinateSystem
import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import com.alphaauxiliary.fitgenerator.map.LocationSearchException
import com.alphaauxiliary.fitgenerator.map.LocationSearchResult
import com.alphaauxiliary.fitgenerator.map.LocationSearchService
import com.alphaauxiliary.fitgenerator.map.MapProvider
import com.alphaauxiliary.fitgenerator.map.ProviderConnectionResult
import com.alphaauxiliary.fitgenerator.map.ProviderKeyPlacement
import com.alphaauxiliary.fitgenerator.map.RouteMapState
import com.alphaauxiliary.fitgenerator.map.SearchErrorKind
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class MainPhase {
    EMPTY,
    DRAWING,
    READY,
    PREVIEWING,
    EXPORTING,
    ERROR,
}

internal data class MainUiState(
    val phase: MainPhase = MainPhase.EMPTY,
    val isInitialized: Boolean = false,
    val routeMapState: RouteMapState = RouteMapState.create(
        routeWgs84 = emptyList(),
        preview = null,
        previewSampleIndex = null,
        selectedVertexIndex = null,
        isDrawing = false,
        cameraWgs84 = AppSettings.Default.mapCamera.centerWgs84,
        cameraZoom = AppSettings.Default.mapCamera.zoom,
        provider = MapProvider.OpenStreetMap,
    ),
    val routeDistanceMeters: Double = 0.0,
    val preview: ActivityModelDto? = null,
    val previewSampleIndex: Int? = null,
    val activeProviderName: String = MapProvider.OpenStreetMap.displayName,
    val providerAttribution: String = MapProvider.OPEN_STREET_MAP_ATTRIBUTION,
    val searchQuery: String = "",
    val searchResults: List<LocationSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val searchMessage: String? = null,
    val paceSecondsPerKilometer: Double = AppSettings.Default.paceSecondsPerKilometer,
    val restingHeartRate: Int = AppSettings.Default.restingHeartRate,
    val maximumHeartRate: Int = AppSettings.Default.maximumHeartRate,
    val lapCount: Int = AppSettings.Default.lapCount,
    val exportCount: Int = AppSettings.Default.exportCount,
    val exportDestination: String? = null,
    val errorMessage: String? = null,
) {
    val canPreview: Boolean
        get() = routeMapState.canonicalWgs84Points.size >= MINIMUM_PREVIEW_POINTS &&
            phase == MainPhase.READY

    val canExport: Boolean
        get() = preview != null && phase == MainPhase.READY

    private companion object {
        const val MINIMUM_PREVIEW_POINTS = 2
    }
}

internal data class MainSettingsDraft(
    val id: String,
    val displayName: String,
    val xyzUrlTemplate: String,
    val keyPlacement: ProviderKeyPlacement,
    val keyName: String,
    val coordinateSystem: CoordinateSystem,
    val maxZoom: String,
    val attribution: String,
    val geocoderUrl: String,
) {
    override fun toString(): String =
        "MainSettingsDraft(id=$id, displayName=$displayName, " +
            "xyzUrlTemplate=<redacted>, keyPlacement=$keyPlacement, keyName=<redacted>, " +
            "coordinateSystem=$coordinateSystem, maxZoom=$maxZoom, " +
            "attribution=<redacted>, geocoderUrl=<redacted>)"

    fun toEditor(apiKeyInput: String = ""): MapProviderEditorState = MapProviderEditorState(
        id = id,
        displayName = displayName,
        xyzUrlTemplate = xyzUrlTemplate,
        keyPlacement = keyPlacement,
        keyName = keyName,
        apiKey = apiKeyInput,
        coordinateSystem = coordinateSystem,
        maxZoom = maxZoom,
        attribution = attribution,
        geocoderUrl = geocoderUrl,
    )

    companion object {
        fun from(provider: MapProvider): MainSettingsDraft = MainSettingsDraft(
            id = provider.id,
            displayName = provider.displayName,
            xyzUrlTemplate = provider.xyzUrlTemplate.orEmpty(),
            keyPlacement = provider.keyPlacement,
            keyName = provider.keyName.orEmpty(),
            coordinateSystem = provider.coordinateSystem,
            maxZoom = provider.maxZoom.toString(),
            attribution = provider.attribution,
            geocoderUrl = provider.geocoderUrl.orEmpty(),
        )

        fun from(editor: MapProviderEditorState): MainSettingsDraft = MainSettingsDraft(
            id = editor.id,
            displayName = editor.displayName,
            xyzUrlTemplate = editor.xyzUrlTemplate,
            keyPlacement = editor.keyPlacement,
            keyName = editor.keyName,
            coordinateSystem = editor.coordinateSystem,
            maxZoom = editor.maxZoom,
            attribution = editor.attribution,
            geocoderUrl = editor.geocoderUrl,
        )
    }
}

internal data class MainSettingsUiState(
    val isOpen: Boolean = false,
    val activeProviderId: String = MapProvider.OPEN_STREET_MAP_ID,
    val customProviders: List<MapProvider> = emptyList(),
    val draft: MainSettingsDraft = MainSettingsDraft.from(MapProvider.OpenStreetMap),
    val hasStoredApiKey: Boolean = false,
    val draftSessionId: Long = 0L,
    val isSaving: Boolean = false,
    val isTestingConnection: Boolean = false,
    val connectionResult: ProviderConnectionResult? = null,
) {
    init {
        require(customProviders.none { provider -> !provider.apiKey.isNullOrEmpty() })
    }

    override fun toString(): String =
        "MainSettingsUiState(isOpen=$isOpen, activeProviderId=$activeProviderId, " +
            "customProviderCount=${customProviders.size}, hasStoredApiKey=$hasStoredApiKey, " +
            "draftSessionId=$draftSessionId, " +
            "isSaving=$isSaving, isTestingConnection=$isTestingConnection, " +
            "hasConnectionResult=${connectionResult != null})"
}

internal fun interface ActivityPreviewer {
    suspend fun preview(input: ActivityInput, seed: ULong): ActivityModelDto
}

internal class NativeActivityPreviewer(
    private val service: NativeCoreService,
) : ActivityPreviewer {
    override suspend fun preview(input: ActivityInput, seed: ULong): ActivityModelDto =
        service.preview(input, seed)
}

internal fun interface MainLocationSearcher {
    suspend fun search(query: String, provider: MapProvider): List<LocationSearchResult>

    suspend fun testConnection(provider: MapProvider): ProviderConnectionResult =
        ProviderConnectionResult(
            isSuccess = false,
            message = "当前环境无法测试地图连接",
            errorKind = SearchErrorKind.PROVIDER_NOT_CONFIGURED,
        )
}

internal class ServiceLocationSearcher(
    private val service: LocationSearchService,
) : MainLocationSearcher {
    override suspend fun search(
        query: String,
        provider: MapProvider,
    ): List<LocationSearchResult> = service.search(query, provider)

    override suspend fun testConnection(provider: MapProvider): ProviderConnectionResult =
        service.testConnection(provider)
}

internal data class MainExportRequest(
    val input: ActivityInput,
    val seed: ULong,
    val model: ActivityModelDto,
    val count: Int,
    val destination: String?,
) {
    override fun toString(): String =
        "MainExportRequest(seed=$seed, count=$count, destination=<redacted>)"
}

internal fun interface MainExportCoordinator {
    suspend fun export(request: MainExportRequest)

    val pendingDocument: StateFlow<ExportDocumentRequest?>
        get() = NO_PENDING_EXPORT_DOCUMENT

    fun markDocumentPickerLaunched(requestId: Long): Boolean = false

    fun completeDocument(requestId: Long, destination: String?): Boolean = false

    fun failDocumentLaunch(requestId: Long): Boolean = false

    suspend fun prepareShareFallback(requestId: Long): ExportShareRequest {
        throw ExportException(
            code = ExportErrorCode.INVALID_REQUEST,
            message = "导出内容已失效，请重新生成 FIT",
        )
    }

    fun completeShare(requestId: Long): Boolean = false

    suspend fun deleteStaleShareExports() = Unit
}

private val NO_PENDING_EXPORT_DOCUMENT = MutableStateFlow<ExportDocumentRequest?>(null)
    .asStateFlow()

internal interface MainClock {
    fun nowCalendar(): Calendar

    fun nowMillis(): Long
}

internal object SystemMainClock : MainClock {
    override fun nowCalendar(): Calendar = Calendar.getInstance()

    override fun nowMillis(): Long = System.currentTimeMillis()
}

private data class SuccessfulPreview(
    val input: ActivityInput,
    val seed: ULong,
    val model: ActivityModelDto,
)

private class SettingsInputException(message: String) : Exception(message)

internal class MainViewModel(
    private val core: ActivityPreviewer,
    private val settingsRepository: AppSettingsRepository,
    private val routeRepository: SavedRouteRepository,
    private val locationSearch: MainLocationSearcher,
    private val exportCoordinator: MainExportCoordinator,
    private val clock: MainClock = SystemMainClock,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private val _settingsState = MutableStateFlow(MainSettingsUiState())
    val settingsState: StateFlow<MainSettingsUiState> = _settingsState.asStateFlow()
    val pendingExportDocument: StateFlow<ExportDocumentRequest?> =
        exportCoordinator.pendingDocument

    private var settings = AppSettings.Default
    private var activeProvider = MapProvider.OpenStreetMap
    private var contentRevision = 0L
    private var searchGeneration = 0L
    private var previewGeneration = 0L
    private var exportGeneration = 0L
    private var phaseBeforeError = MainPhase.EMPTY
    private var deferredExportError: String? = null
    private var successfulPreview: SuccessfulPreview? = null
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var exportJob: Job? = null
    private var routePersistenceJob: Job? = null
    private var settingsPersistenceJob: Job? = null
    private var settingsOperationJob: Job? = null
    private var settingsOperationGeneration = 0L
    private var settingsDraftSessionId = 0L

    init {
        viewModelScope.launch {
            initialize()
        }
    }

    fun openSettings() {
        val current = _state.value
        if (
            !current.isInitialized ||
            current.phase in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING) ||
            _settingsState.value.isOpen
        ) {
            return
        }
        searchGeneration += 1
        searchJob?.cancel()
        _state.update { state ->
            state.copy(
                isSearching = false,
                searchResults = emptyList(),
                searchMessage = null,
            )
        }
        settingsOperationGeneration += 1
        settingsOperationJob?.cancel()
        settingsOperationJob = null
        _settingsState.value = settingsStateFor(
            isOpen = true,
            selectedProvider = activeProvider,
        )
    }

    fun closeSettings() {
        val current = _settingsState.value
        if (!current.isOpen || current.isSaving) return
        settingsOperationGeneration += 1
        settingsOperationJob?.cancel()
        settingsOperationJob = null
        _settingsState.value = MainSettingsUiState()
    }

    fun selectSettingsProvider(providerId: String) {
        val current = _settingsState.value
        if (!current.isOpen || current.isSaving) return
        val provider = if (providerId == MapProvider.OPEN_STREET_MAP_ID) {
            MapProvider.OpenStreetMap
        } else {
            settings.customMapProviders.firstOrNull { it.id == providerId }
        } ?: return
        cancelSettingsConnectionTest()
        settingsDraftSessionId += 1
        _settingsState.update { state ->
            state.copy(
                activeProviderId = provider.id,
                draft = MainSettingsDraft.from(provider),
                hasStoredApiKey = !provider.apiKey.isNullOrEmpty(),
                draftSessionId = settingsDraftSessionId,
                connectionResult = null,
            )
        }
    }

    fun addCustomMapProvider() {
        val current = _settingsState.value
        if (!current.isOpen || current.isSaving || current.isTestingConnection) return
        if (settings.customMapProviders.size >= MAXIMUM_CUSTOM_PROVIDERS) {
            showSettingsFailure("最多可保存 $MAXIMUM_CUSTOM_PROVIDERS 个自定义地图源")
            return
        }
        cancelSettingsConnectionTest()
        val providerId = nextCustomProviderId()
        val providerNumber = providerId.substringAfterLast('-')
        settingsDraftSessionId += 1
        _settingsState.update { state ->
            state.copy(
                activeProviderId = providerId,
                draft = MainSettingsDraft(
                    id = providerId,
                    displayName = "自定义地图 $providerNumber",
                    xyzUrlTemplate = "",
                    keyPlacement = ProviderKeyPlacement.NONE,
                    keyName = "",
                    coordinateSystem = CoordinateSystem.WGS84,
                    maxZoom = DEFAULT_CUSTOM_PROVIDER_MAX_ZOOM.toString(),
                    attribution = "",
                    geocoderUrl = "",
                ),
                hasStoredApiKey = false,
                draftSessionId = settingsDraftSessionId,
                connectionResult = null,
            )
        }
    }

    fun updateSettingsDraft(draft: MainSettingsDraft) {
        val current = _settingsState.value
        if (
            !current.isOpen ||
            current.isSaving ||
            draft.id != current.draft.id
        ) {
            return
        }
        cancelSettingsConnectionTest()
        _settingsState.update { state ->
            state.copy(draft = draft, connectionResult = null)
        }
    }

    fun saveSettingsProvider(apiKeyInput: String = "") {
        val current = _settingsState.value
        if (!current.isOpen || current.isSaving || current.isTestingConnection) return
        val provider = try {
            providerFromDraft(current.draft, apiKeyInput)
        } catch (error: SettingsInputException) {
            showSettingsFailure(error.message)
            return
        }
        val customProviders = when {
            provider.isBuiltIn -> settings.customMapProviders
            settings.customMapProviders.any { saved -> saved.id == provider.id } ->
                settings.customMapProviders.map { saved ->
                    if (saved.id == provider.id) provider else saved
                }
            else -> settings.customMapProviders + provider
        }
        startSettingsSave(
            updated = settings.copy(
                activeMapProviderId = provider.id,
                customMapProviders = customProviders,
            ),
            selectedProvider = provider,
        )
    }

    fun testSettingsConnection(apiKeyInput: String = "") {
        val current = _settingsState.value
        if (!current.isOpen || current.isSaving || current.isTestingConnection) return
        val provider = try {
            providerFromDraft(current.draft, apiKeyInput)
        } catch (error: SettingsInputException) {
            showSettingsFailure(error.message)
            return
        }
        val generation = ++settingsOperationGeneration
        settingsOperationJob?.cancel()
        _settingsState.update { state ->
            state.copy(isTestingConnection = true, connectionResult = null)
        }
        settingsOperationJob = viewModelScope.launch {
            try {
                val result = withContext(workerDispatcher) {
                    locationSearch.testConnection(provider)
                }
                if (
                    settingsOperationGeneration == generation &&
                    _settingsState.value.isOpen
                ) {
                    val safeResult = sanitizeConnectionResult(
                        result = result,
                        apiKey = provider.apiKey,
                    )
                    _settingsState.update { state ->
                        state.copy(
                            isTestingConnection = false,
                            connectionResult = safeResult,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (
                    settingsOperationGeneration == generation &&
                    _settingsState.value.isOpen
                ) {
                    showSettingsFailure(
                        message = "地图连接测试失败，请检查网络和服务地址",
                        errorKind = SearchErrorKind.NETWORK,
                    )
                }
            } finally {
                if (
                    settingsOperationGeneration == generation &&
                    _settingsState.value.isOpen &&
                    _settingsState.value.isTestingConnection
                ) {
                    _settingsState.update { state -> state.copy(isTestingConnection = false) }
                }
                if (settingsOperationGeneration == generation) {
                    settingsOperationJob = null
                }
            }
        }
    }

    fun restoreDefaultMapProvider() {
        val current = _settingsState.value
        if (!current.isOpen || current.isSaving || current.isTestingConnection) return
        startSettingsSave(
            updated = settings.copy(activeMapProviderId = MapProvider.OPEN_STREET_MAP_ID),
            selectedProvider = MapProvider.OpenStreetMap,
        )
    }

    fun setDrawingEnabled(enabled: Boolean) {
        if (
            !_state.value.isInitialized ||
            _state.value.phase in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)
        ) {
            return
        }
        _state.update { current ->
            val phase = if (enabled) MainPhase.DRAWING else stablePhase(current.routeMapState.canonicalWgs84Points)
            current.copy(
                phase = phase,
                routeMapState = current.routeMapState.copy(isDrawing = enabled),
                errorMessage = null,
            )
        }
    }

    fun finishDrawing() = setDrawingEnabled(false)

    fun addDrawnPoint(pointWgs84: GeoCoordinate) {
        if (_state.value.phase in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)) return
        if (!_state.value.routeMapState.isDrawing || !pointWgs84.isValid()) return
        val points = _state.value.routeMapState.canonicalWgs84Points
        if (points.size >= MAXIMUM_ROUTE_POINTS) return
        val previous = points.lastOrNull()
        if (previous != null && CoordinateTransforms.distanceMeters(previous, pointWgs84) < DRAW_SPACING_METERS) {
            return
        }
        if (
            points.size > 1 &&
            CoordinateTransforms.distanceMeters(points.first(), pointWgs84) < DUPLICATE_POINT_METERS
        ) {
            return
        }
        replaceRoute(points + pointWgs84, selectedVertexIndex = null)
    }

    fun undoRoutePoint() {
        if (_state.value.phase in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)) return
        val points = _state.value.routeMapState.canonicalWgs84Points
        if (points.isEmpty()) return
        replaceRoute(points.dropLast(1), selectedVertexIndex = null)
    }

    fun clearRoute() {
        if (_state.value.phase in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)) return
        if (_state.value.routeMapState.canonicalWgs84Points.isEmpty()) return
        replaceRoute(emptyList(), selectedVertexIndex = null)
    }

    fun selectVertex(index: Int?) {
        val points = _state.value.routeMapState.canonicalWgs84Points
        val selected = index?.takeIf { it in points.indices }
        _state.update { current ->
            current.copy(routeMapState = current.routeMapState.copy(selectedVertexIndex = selected))
        }
    }

    fun moveSelectedVertex(pointWgs84: GeoCoordinate) {
        if (_state.value.phase in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)) return
        if (!pointWgs84.isValid()) return
        val current = _state.value
        val index = current.routeMapState.selectedVertexIndex ?: return
        val points = current.routeMapState.canonicalWgs84Points.toMutableList()
        if (index !in points.indices) return
        if (
            points.withIndex().any { (otherIndex, existing) ->
                otherIndex != index &&
                    CoordinateTransforms.distanceMeters(existing, pointWgs84) < DUPLICATE_POINT_METERS
            }
        ) {
            return
        }
        points[index] = pointWgs84
        replaceRoute(points, selectedVertexIndex = index)
    }

    fun updateMapCamera(centerWgs84: GeoCoordinate, zoom: Double) {
        if (
            !_state.value.isInitialized ||
            _settingsState.value.isOpen ||
            !centerWgs84.isValid() ||
            !zoom.isFinite()
        ) {
            return
        }
        val boundedZoom = zoom.coerceIn(0.0, activeProvider.maxZoom.toDouble())
        settings = settings.copy(
            mapCamera = settings.mapCamera.copy(centerWgs84 = centerWgs84, zoom = boundedZoom),
        )
        _state.update { current ->
            current.copy(
                routeMapState = buildRouteMapState(
                    route = current.routeMapState.canonicalWgs84Points,
                    preview = current.preview,
                    previewSampleIndex = current.previewSampleIndex,
                    selectedVertexIndex = current.routeMapState.selectedVertexIndex,
                    isDrawing = current.routeMapState.isDrawing,
                ),
            )
        }
        persistSettings()
    }

    fun locateAt(coordinateWgs84: GeoCoordinate) {
        updateMapCamera(coordinateWgs84, settings.mapCamera.zoom.coerceAtLeast(LOCATION_ZOOM))
    }

    fun updateSearchQuery(query: String) {
        if (!_state.value.isInitialized || _settingsState.value.isOpen) return
        val generation = ++searchGeneration
        searchJob?.cancel()
        val normalized = query.trim()
        _state.update { current ->
            current.copy(
                searchQuery = query,
                searchResults = if (normalized.isEmpty()) emptyList() else current.searchResults,
                isSearching = normalized.isNotEmpty(),
                searchMessage = null,
            )
        }
        if (normalized.isEmpty()) {
            _state.update { it.copy(isSearching = false) }
            return
        }

        val providerSnapshot = activeProvider
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            try {
                val results = withContext(workerDispatcher) {
                    locationSearch.search(normalized, providerSnapshot)
                }
                if (
                    searchGeneration == generation &&
                    _state.value.searchQuery.trim() == normalized &&
                    activeProvider.id == providerSnapshot.id
                ) {
                    _state.update { current ->
                        current.copy(
                            searchResults = results.toList(),
                            isSearching = false,
                            searchMessage = if (results.isEmpty()) "未找到地点，请换一个关键词" else null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LocationSearchException) {
                if (
                    searchGeneration == generation &&
                    _state.value.searchQuery.trim() == normalized &&
                    activeProvider.id == providerSnapshot.id
                ) {
                    _state.update { current ->
                        current.copy(
                            searchResults = emptyList(),
                            isSearching = false,
                            searchMessage = error.message ?: "地点搜索失败，请检查网络后重试",
                        )
                    }
                }
            } catch (_: Exception) {
                if (
                    searchGeneration == generation &&
                    _state.value.searchQuery.trim() == normalized &&
                    activeProvider.id == providerSnapshot.id
                ) {
                    _state.update { current ->
                        current.copy(
                            searchResults = emptyList(),
                            isSearching = false,
                            searchMessage = "地点搜索失败，请检查网络后重试",
                        )
                    }
                }
            } finally {
                if (
                    searchGeneration == generation &&
                    _state.value.searchQuery.trim() == normalized &&
                    _state.value.isSearching
                ) {
                    _state.update { it.copy(isSearching = false) }
                }
            }
        }
    }

    fun selectSearchResult(result: LocationSearchResult) {
        if (!_state.value.isInitialized) return
        searchGeneration += 1
        searchJob?.cancel()
        _state.update { current ->
            current.copy(
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false,
                searchMessage = null,
            )
        }
        locateAt(result.coordinate)
    }

    fun previewRoute() {
        val current = _state.value
        if (!current.isInitialized) return
        val route = current.routeMapState.canonicalWgs84Points
        if (current.phase in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)) return
        if (current.phase != MainPhase.READY || route.size < MINIMUM_PREVIEW_POINTS) {
            showError("至少绘制两个路线点后才能预览")
            return
        }
        previewJob?.cancel()
        val generation = ++previewGeneration
        val revision = contentRevision
        val settingsSnapshot = settings
        val input = createActivityInput(route, settingsSnapshot)
        val seed = settingsSnapshot.previewSeed ?: clock.nowMillis().toULong()
        successfulPreview = null
        _state.update { state ->
            state.copy(
                phase = MainPhase.PREVIEWING,
                routeMapState = state.routeMapState.copy(
                    previewProviderPoint = null,
                    isDrawing = false,
                ),
                preview = null,
                previewSampleIndex = null,
                errorMessage = null,
            )
        }

        previewJob = viewModelScope.launch {
            try {
                val model = withContext(workerDispatcher) { core.preview(input, seed) }
                if (
                    previewGeneration != generation ||
                    contentRevision != revision ||
                    _state.value.phase != MainPhase.PREVIEWING
                ) {
                    return@launch
                }
                check(model.seed == seed) { "preview seed mismatch" }
                val immutableModel = model.copy(
                    laps = model.laps.toList(),
                    samples = model.samples.toList(),
                )
                successfulPreview = SuccessfulPreview(
                    input = input,
                    seed = seed,
                    model = immutableModel,
                )
                if (settings.previewSeed != seed) {
                    settings = settings.copy(previewSeed = seed)
                    persistSettings()
                }
                _state.update { state ->
                    state.copy(
                        phase = MainPhase.READY,
                        preview = immutableModel,
                        previewSampleIndex = 0,
                        routeMapState = buildRouteMapState(
                            route = route,
                            preview = immutableModel,
                            previewSampleIndex = 0,
                            selectedVertexIndex = state.routeMapState.selectedVertexIndex,
                            isDrawing = false,
                        ),
                        errorMessage = null,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CoreException) {
                if (
                    previewGeneration == generation &&
                    contentRevision == revision &&
                    _state.value.phase == MainPhase.PREVIEWING
                ) {
                    showError(error.message ?: "活动预览失败，请检查参数后重试")
                }
            } catch (_: Exception) {
                if (
                    previewGeneration == generation &&
                    contentRevision == revision &&
                    _state.value.phase == MainPhase.PREVIEWING
                ) {
                    showError("活动预览失败，请检查参数后重试")
                }
            } finally {
                if (
                    previewGeneration == generation &&
                    contentRevision == revision &&
                    _state.value.phase == MainPhase.PREVIEWING
                ) {
                    _state.update { state ->
                        state.copy(phase = stablePhase(state.routeMapState.canonicalWgs84Points))
                    }
                }
            }
        }
    }

    fun updatePreviewSampleIndex(index: Int) {
        val current = _state.value
        val preview = current.preview ?: return
        if (index !in preview.samples.indices) return
        _state.update { state ->
            state.copy(
                previewSampleIndex = index,
                routeMapState = buildRouteMapState(
                    route = state.routeMapState.canonicalWgs84Points,
                    preview = preview,
                    previewSampleIndex = index,
                    selectedVertexIndex = state.routeMapState.selectedVertexIndex,
                    isDrawing = state.routeMapState.isDrawing,
                ),
            )
        }
    }

    fun updatePace(secondsPerKilometer: Double) {
        if (!_state.value.isInitialized) return
        if (!secondsPerKilometer.isFinite() || secondsPerKilometer !in 60.0..3_600.0) return
        updateCoreSettings(settings.copy(paceSecondsPerKilometer = secondsPerKilometer))
    }

    fun updateHeartRates(resting: Int, maximum: Int) {
        if (!_state.value.isInitialized) return
        if (resting !in 30..120 || maximum !in 100..220 || maximum <= resting) return
        updateCoreSettings(settings.copy(restingHeartRate = resting, maximumHeartRate = maximum))
    }

    fun updateLapCount(count: Int) {
        if (!_state.value.isInitialized) return
        if (count !in 1..100) return
        updateCoreSettings(settings.copy(lapCount = count))
    }

    fun updateExportCount(count: Int) {
        if (!_state.value.isInitialized) return
        if (count !in 1..20) return
        settings = settings.copy(exportCount = count)
        _state.update { it.copy(exportCount = count) }
        persistSettings()
    }

    fun updateExportDestination(destination: String?) {
        if (!_state.value.isInitialized) return
        _state.update { current -> current.copy(exportDestination = destination) }
    }

    fun exportPreview() {
        val current = _state.value
        if (!current.isInitialized) return
        if (current.phase in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)) return
        val preview = successfulPreview
        if (
            current.phase != MainPhase.READY ||
            preview == null ||
            current.preview !== preview.model
        ) {
            showError("请先完成活动预览，再生成 FIT")
            return
        }
        val generation = ++exportGeneration
        val revision = contentRevision
        val request = MainExportRequest(
            input = preview.input,
            seed = preview.seed,
            model = preview.model,
            count = settings.exportCount,
            destination = current.exportDestination,
        )
        exportJob?.cancel()
        _state.update { it.copy(phase = MainPhase.EXPORTING, errorMessage = null) }
        exportJob = viewModelScope.launch {
            try {
                withContext(workerDispatcher) { exportCoordinator.export(request) }
                if (
                    exportGeneration == generation &&
                    contentRevision == revision &&
                    _state.value.phase == MainPhase.EXPORTING
                ) {
                    _state.update { it.copy(phase = MainPhase.READY) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ExportException) {
                if (
                    exportGeneration == generation &&
                    contentRevision == revision &&
                    _state.value.phase == MainPhase.EXPORTING
                ) {
                    showError(error.message ?: "FIT 导出失败，请重新选择位置后重试")
                }
            } catch (error: CoreException) {
                if (
                    exportGeneration == generation &&
                    contentRevision == revision &&
                    _state.value.phase == MainPhase.EXPORTING
                ) {
                    showError(error.message ?: "FIT 生成失败，请检查参数后重试")
                }
            } catch (_: Exception) {
                if (
                    exportGeneration == generation &&
                    contentRevision == revision &&
                    _state.value.phase == MainPhase.EXPORTING
                ) {
                    showError("FIT 导出失败，请重新选择位置后重试")
                }
            } finally {
                if (
                    exportGeneration == generation &&
                    contentRevision == revision &&
                    _state.value.phase == MainPhase.EXPORTING
                ) {
                    _state.update { it.copy(phase = MainPhase.READY) }
                }
            }
        }
    }

    fun markExportDocumentPickerLaunched(requestId: Long): Boolean =
        exportCoordinator.markDocumentPickerLaunched(requestId)

    fun completeExportDocument(requestId: Long, destination: String?) {
        val wasCurrent = pendingExportDocument.value?.requestId == requestId
        val accepted = exportCoordinator.completeDocument(requestId, destination)
        if (!accepted && !wasCurrent) {
            val unmatchedPendingId = pendingExportDocument.value?.requestId
            unmatchedPendingId?.let(exportCoordinator::failDocumentLaunch)
            if (destination != null || unmatchedPendingId != null) {
                reportExportError(
                    "导出内容已失效，请重新生成 FIT 后再选择位置",
                )
            }
        }
    }

    fun failExportDocumentLaunch(requestId: Long, safeMessage: String? = null) {
        val accepted = exportCoordinator.failDocumentLaunch(requestId)
        val normalizedMessage = safeMessage?.trim()?.take(MAXIMUM_UI_ERROR_CHARACTERS)
        if (!normalizedMessage.isNullOrEmpty()) {
            reportExportError(normalizedMessage)
        } else if (!accepted) {
            reportExportError("无法继续当前导出，请重新生成 FIT")
        }
    }

    suspend fun prepareExportShareFallback(requestId: Long): ExportShareRequest =
        exportCoordinator.prepareShareFallback(requestId)

    fun completeExportShare(requestId: Long) {
        val wasCurrent = pendingExportDocument.value?.requestId == requestId
        if (!exportCoordinator.completeShare(requestId) && !wasCurrent) {
            reportExportError("导出内容已失效，请重新生成 FIT")
        }
    }

    suspend fun deleteStaleShareExports() {
        exportCoordinator.deleteStaleShareExports()
    }

    fun dismissError() {
        if (_state.value.errorMessage == null) return
        _state.update { current ->
            current.copy(
                phase = if (current.phase == MainPhase.ERROR) phaseBeforeError else current.phase,
                errorMessage = null,
            )
        }
    }

    fun reportMapError(message: String) {
        val safeMessage = message.trim().take(MAXIMUM_UI_ERROR_CHARACTERS)
        _state.update { current ->
            current.copy(
                errorMessage = safeMessage.ifEmpty {
                    "在线底图暂时不可用；路线功能仍可继续使用"
                },
            )
        }
    }

    private suspend fun initialize() {
        var initializationMessage: String? = null
        val loadedSettings = try {
            settingsRepository.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            initializationMessage = "设置未能读取，已使用默认值"
            AppSettings.Default
        }
        val loadedRoute = try {
            routeRepository.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            initializationMessage = "最近路线未能读取，可以重新绘制"
            SavedRoute.Empty
        }
        settings = loadedSettings
        val configuredProvider = resolveActiveProvider(loadedSettings)
        val renderingIssue = configuredProvider.androidRenderingIssue()
        activeProvider = if (renderingIssue == null) {
            configuredProvider
        } else {
            initializationMessage =
                "$renderingIssue；已保留该配置，本次使用 OpenStreetMap"
            MapProvider.OpenStreetMap
        }
        val route = loadedRoute.wgs84Points
        val stable = stablePhase(route)
        val startupMessage = deferredExportError ?: initializationMessage
        deferredExportError = null
        _state.value = MainUiState(
            phase = if (startupMessage == null) stable else MainPhase.ERROR,
            isInitialized = true,
            routeMapState = buildRouteMapState(
                route = route,
                preview = null,
                previewSampleIndex = null,
                selectedVertexIndex = null,
                isDrawing = false,
            ),
            routeDistanceMeters = route.distanceMeters(),
            activeProviderName = activeProvider.displayName,
            providerAttribution = activeProvider.attribution,
            paceSecondsPerKilometer = settings.paceSecondsPerKilometer,
            restingHeartRate = settings.restingHeartRate,
            maximumHeartRate = settings.maximumHeartRate,
            lapCount = settings.lapCount,
            exportCount = settings.exportCount,
            errorMessage = startupMessage,
        )
        phaseBeforeError = stable
    }

    private fun settingsStateFor(
        isOpen: Boolean,
        selectedProvider: MapProvider,
    ): MainSettingsUiState {
        settingsDraftSessionId += 1
        return MainSettingsUiState(
            isOpen = isOpen,
            activeProviderId = selectedProvider.id,
            customProviders = settings.customMapProviders.map { provider ->
                provider.copy(apiKey = null)
            },
            draft = MainSettingsDraft.from(selectedProvider),
            hasStoredApiKey = !selectedProvider.apiKey.isNullOrEmpty(),
            draftSessionId = settingsDraftSessionId,
        )
    }

    private fun providerFromDraft(
        draft: MainSettingsDraft,
        apiKeyInput: String,
    ): MapProvider {
        if (draft.id == MapProvider.OPEN_STREET_MAP_ID) {
            if (draft != MainSettingsDraft.from(MapProvider.OpenStreetMap)) {
                throw SettingsInputException("内置 OpenStreetMap 不可修改；可使用恢复默认地图")
            }
            return MapProvider.OpenStreetMap
        }
        val existing = settings.customMapProviders.firstOrNull { it.id == draft.id }
        if (existing == null && !NEW_CUSTOM_PROVIDER_ID.matches(draft.id)) {
            throw SettingsInputException("自定义地图源标识无效，请重新新建")
        }
        val displayName = draft.displayName.trim()
        val xyzUrlTemplate = draft.xyzUrlTemplate.trim()
        val attribution = draft.attribution.trim()
        val geocoderUrl = draft.geocoderUrl.trim().ifEmpty { null }
        val maxZoom = draft.maxZoom.toIntOrNull()
            ?: throw SettingsInputException("最大缩放级别必须是 0 到 24 的整数")
        if (displayName.isEmpty()) {
            throw SettingsInputException("请填写地图源名称")
        }
        if (xyzUrlTemplate.isEmpty()) {
            throw SettingsInputException("请填写 XYZ 地图地址")
        }
        if (
            !xyzUrlTemplate.contains("{z}", ignoreCase = true) ||
            !xyzUrlTemplate.contains("{x}", ignoreCase = true) ||
            !xyzUrlTemplate.contains("{y}", ignoreCase = true)
        ) {
            throw SettingsInputException("XYZ 地图地址必须包含 {z}、{x}、{y}")
        }
        if (!xyzUrlTemplate.startsWith("https://", ignoreCase = true)) {
            throw SettingsInputException("地图瓦片地址必须使用 HTTPS")
        }
        if (geocoderUrl != null && !geocoderUrl.startsWith("https://", ignoreCase = true)) {
            throw SettingsInputException("地点搜索地址必须使用 HTTPS")
        }
        val styleUrl = existing?.styleUrl
        if (
            styleUrl != null &&
            !styleUrl.startsWith("https://", ignoreCase = true)
        ) {
            throw SettingsInputException("地图样式地址必须使用 HTTPS")
        }
        if (attribution.isEmpty()) {
            throw SettingsInputException("请填写地图数据归属")
        }
        if (maxZoom !in MINIMUM_PROVIDER_ZOOM..MAXIMUM_PROVIDER_ZOOM) {
            throw SettingsInputException("最大缩放级别必须是 0 到 24 的整数")
        }
        if (draft.keyPlacement == ProviderKeyPlacement.HEADER) {
            throw SettingsInputException(
                "Android 地图渲染不支持 Header Key，请改用查询参数或 URL 占位符",
            )
        }
        val apiKey = if (draft.keyPlacement == ProviderKeyPlacement.NONE) {
            null
        } else {
            val candidate = apiKeyInput.takeUnless { it.isBlank() } ?: existing?.apiKey
            candidate?.takeUnless { it.isBlank() }
                ?: throw SettingsInputException("请填写此地图源的 API Key")
        }
        if (apiKey?.any { it == '\r' || it == '\n' } == true) {
            throw SettingsInputException("API Key 不能包含换行符")
        }
        val keyName = when (draft.keyPlacement) {
            ProviderKeyPlacement.HEADER,
            ProviderKeyPlacement.QUERY_PARAMETER ->
                draft.keyName.trim().takeUnless { it.isEmpty() }
                    ?: throw SettingsInputException("请填写 Key 参数名称")
            ProviderKeyPlacement.NONE,
            ProviderKeyPlacement.URL_TEMPLATE -> null
        }
        if (keyName?.any { character -> character.isISOControl() } == true) {
            throw SettingsInputException("Key 参数名称包含无效字符")
        }
        if (draft.keyPlacement == ProviderKeyPlacement.URL_TEMPLATE) {
            val networkUrls = listOfNotNull(xyzUrlTemplate, styleUrl, geocoderUrl)
            if (networkUrls.any { !it.contains("{key}", ignoreCase = true) }) {
                throw SettingsInputException("使用 URL 模板 Key 时，每个服务地址都必须包含 {key}")
            }
        }
        return MapProvider.custom(
            id = existing?.id ?: draft.id,
            displayName = displayName,
            styleUrl = styleUrl,
            xyzUrlTemplate = xyzUrlTemplate,
            keyPlacement = draft.keyPlacement,
            keyName = keyName,
            apiKey = apiKey,
            coordinateSystem = draft.coordinateSystem,
            maxZoom = maxZoom,
            attribution = attribution,
            geocoderUrl = geocoderUrl,
        )
    }

    private fun nextCustomProviderId(): String {
        val existingIds = settings.customMapProviders.mapTo(hashSetOf()) { provider -> provider.id }
        var index = 1
        while ("custom-$index" in existingIds) index += 1
        return "custom-$index"
    }

    private fun startSettingsSave(
        updated: AppSettings,
        selectedProvider: MapProvider,
    ) {
        settingsPersistenceJob?.cancel()
        val generation = ++settingsOperationGeneration
        settingsOperationJob?.cancel()
        _settingsState.update { state ->
            state.copy(
                isSaving = true,
                isTestingConnection = false,
                connectionResult = null,
            )
        }
        settingsOperationJob = viewModelScope.launch {
            try {
                withContext(workerDispatcher) {
                    settingsRepository.save(updated)
                }
                if (
                    settingsOperationGeneration == generation &&
                    _settingsState.value.isOpen
                ) {
                    applySavedProviderSettings(updated)
                    _settingsState.value = settingsStateFor(
                        isOpen = true,
                        selectedProvider = selectedProvider,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (
                    settingsOperationGeneration == generation &&
                    _settingsState.value.isOpen
                ) {
                    showSettingsFailure(
                        message = "地图设置未能保存，请检查配置和设备存储空间",
                        errorKind = null,
                    )
                }
            } finally {
                if (
                    settingsOperationGeneration == generation &&
                    _settingsState.value.isOpen &&
                    _settingsState.value.isSaving
                ) {
                    _settingsState.update { state -> state.copy(isSaving = false) }
                }
                if (settingsOperationGeneration == generation) {
                    settingsOperationJob = null
                }
            }
        }
    }

    private fun applySavedProviderSettings(updated: AppSettings) {
        settings = updated
        activeProvider = resolveActiveProvider(updated)
        searchGeneration += 1
        searchJob?.cancel()
        _state.update { current ->
            current.copy(
                routeMapState = buildRouteMapState(
                    route = current.routeMapState.canonicalWgs84Points,
                    preview = current.preview,
                    previewSampleIndex = current.previewSampleIndex,
                    selectedVertexIndex = current.routeMapState.selectedVertexIndex,
                    isDrawing = current.routeMapState.isDrawing,
                ),
                activeProviderName = activeProvider.displayName,
                providerAttribution = activeProvider.attribution,
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false,
                searchMessage = null,
            )
        }
    }

    internal fun cancelSettingsConnectionTest() {
        if (!_settingsState.value.isTestingConnection) return
        settingsOperationGeneration += 1
        settingsOperationJob?.cancel()
        settingsOperationJob = null
        _settingsState.update { state ->
            state.copy(isTestingConnection = false, connectionResult = null)
        }
    }

    private fun showSettingsFailure(
        message: String?,
        errorKind: SearchErrorKind? = SearchErrorKind.PROVIDER_NOT_CONFIGURED,
    ) {
        if (!_settingsState.value.isOpen) return
        val safeMessage = message
            ?.trim()
            ?.take(MAXIMUM_UI_ERROR_CHARACTERS)
            ?.takeUnless { it.isEmpty() }
            ?: "地图设置无效，请检查后重试"
        _settingsState.update { state ->
            state.copy(
                isSaving = false,
                isTestingConnection = false,
                connectionResult = ProviderConnectionResult(
                    isSuccess = false,
                    message = safeMessage,
                    errorKind = errorKind,
                ),
            )
        }
    }

    private fun sanitizeConnectionResult(
        result: ProviderConnectionResult,
        apiKey: String?,
    ): ProviderConnectionResult {
        var safeMessage = result.message
            .trim()
            .take(MAXIMUM_UI_ERROR_CHARACTERS)
            .ifEmpty { if (result.isSuccess) "连接成功" else "连接失败，请检查配置后重试" }
        apiKey?.takeUnless { key -> key.isBlank() }?.let { key ->
            safeMessage = safeMessage.replace(key, "<redacted>")
        }
        return result.copy(message = safeMessage)
    }

    private fun replaceRoute(points: List<GeoCoordinate>, selectedVertexIndex: Int?) {
        previewJob?.cancel()
        previewGeneration += 1
        contentRevision += 1
        successfulPreview = null
        _state.update { current ->
            val isDrawing = current.routeMapState.isDrawing
            current.copy(
                phase = if (isDrawing) MainPhase.DRAWING else stablePhase(points),
                routeMapState = buildRouteMapState(
                    route = points,
                    preview = null,
                    previewSampleIndex = null,
                    selectedVertexIndex = selectedVertexIndex,
                    isDrawing = isDrawing,
                ),
                routeDistanceMeters = points.distanceMeters(),
                preview = null,
                previewSampleIndex = null,
                errorMessage = null,
            )
        }
        routePersistenceJob?.cancel()
        val snapshot = SavedRoute(wgs84Points = points.toList())
        routePersistenceJob = viewModelScope.launch {
            try {
                routeRepository.save(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError("路线未能保存，请检查设备存储空间")
            }
        }
    }

    private fun updateCoreSettings(updated: AppSettings) {
        if (_state.value.phase in setOf(MainPhase.PREVIEWING, MainPhase.EXPORTING)) return
        previewJob?.cancel()
        previewGeneration += 1
        contentRevision += 1
        settings = updated
        successfulPreview = null
        _state.update { current ->
            current.copy(
                phase = stablePhase(current.routeMapState.canonicalWgs84Points),
                preview = null,
                previewSampleIndex = null,
                routeMapState = buildRouteMapState(
                    route = current.routeMapState.canonicalWgs84Points,
                    preview = null,
                    previewSampleIndex = null,
                    selectedVertexIndex = current.routeMapState.selectedVertexIndex,
                    isDrawing = false,
                ),
                paceSecondsPerKilometer = updated.paceSecondsPerKilometer,
                restingHeartRate = updated.restingHeartRate,
                maximumHeartRate = updated.maximumHeartRate,
                lapCount = updated.lapCount,
                errorMessage = null,
            )
        }
        persistSettings()
    }

    private fun persistSettings() {
        settingsPersistenceJob?.cancel()
        val snapshot = settings
        settingsPersistenceJob = viewModelScope.launch {
            try {
                settingsRepository.save(snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError("设置未能保存，请检查设备存储空间")
            }
        }
    }

    private fun showError(message: String) {
        val current = _state.value
        phaseBeforeError = when (current.phase) {
            MainPhase.ERROR -> phaseBeforeError
            MainPhase.DRAWING -> MainPhase.DRAWING
            else -> stablePhase(current.routeMapState.canonicalWgs84Points)
        }
        _state.update { it.copy(phase = MainPhase.ERROR, errorMessage = message) }
    }

    private fun reportExportError(message: String) {
        if (_state.value.isInitialized) {
            showError(message)
        } else {
            deferredExportError = message
        }
    }

    private fun createActivityInput(
        route: List<GeoCoordinate>,
        settings: AppSettings,
    ): ActivityInput = ActivityInput(
        startTime = clock.nowCalendar().clone() as Calendar,
        routePoints = route.map { point ->
            ActivityRoutePoint(latitude = point.latitude, longitude = point.longitude)
        },
        paceSecondsPerKilometer = settings.paceSecondsPerKilometer,
        restingHeartRate = settings.restingHeartRate,
        maximumHeartRate = settings.maximumHeartRate,
        lapCount = settings.lapCount,
    )

    private fun buildRouteMapState(
        route: List<GeoCoordinate>,
        preview: ActivityModelDto?,
        previewSampleIndex: Int?,
        selectedVertexIndex: Int?,
        isDrawing: Boolean,
    ): RouteMapState = RouteMapState.create(
        routeWgs84 = route,
        preview = preview,
        previewSampleIndex = previewSampleIndex,
        selectedVertexIndex = selectedVertexIndex,
        isDrawing = isDrawing,
        cameraWgs84 = settings.mapCamera.centerWgs84,
        cameraZoom = settings.mapCamera.zoom,
        provider = activeProvider,
    )

    private fun resolveActiveProvider(settings: AppSettings): MapProvider =
        if (settings.activeMapProviderId == MapProvider.OPEN_STREET_MAP_ID) {
            MapProvider.OpenStreetMap
        } else {
            settings.customMapProviders.firstOrNull { it.id == settings.activeMapProviderId }
                ?: MapProvider.OpenStreetMap
        }

    private fun stablePhase(route: List<GeoCoordinate>): MainPhase =
        if (route.size >= MINIMUM_PREVIEW_POINTS) MainPhase.READY else MainPhase.EMPTY

    private fun GeoCoordinate.isValid(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0

    private fun List<GeoCoordinate>.distanceMeters(): Double =
        zipWithNext().sumOf { (first, second) ->
            CoordinateTransforms.distanceMeters(first, second)
        }

    private companion object {
        const val MINIMUM_PREVIEW_POINTS = 2
        const val MAXIMUM_ROUTE_POINTS = 50_000
        const val DRAW_SPACING_METERS = 8.0
        const val DUPLICATE_POINT_METERS = 0.01
        const val LOCATION_ZOOM = 16.0
        const val SEARCH_DEBOUNCE_MILLIS = 400L
        const val MAXIMUM_UI_ERROR_CHARACTERS = 240
        const val MINIMUM_PROVIDER_ZOOM = 0
        const val MAXIMUM_PROVIDER_ZOOM = 24
        const val DEFAULT_CUSTOM_PROVIDER_MAX_ZOOM = 19
        const val MAXIMUM_CUSTOM_PROVIDERS = 32
        val NEW_CUSTOM_PROVIDER_ID = Regex("^custom-[1-9][0-9]*$")
    }
}
