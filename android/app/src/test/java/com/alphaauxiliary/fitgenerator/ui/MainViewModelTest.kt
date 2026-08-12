package com.alphaauxiliary.fitgenerator.ui

import app.cash.turbine.test
import com.alphaauxiliary.fitgenerator.core.ActivityInput
import com.alphaauxiliary.fitgenerator.core.ActivityModelDto
import com.alphaauxiliary.fitgenerator.core.ActivitySampleDto
import com.alphaauxiliary.fitgenerator.core.CoreException
import com.alphaauxiliary.fitgenerator.core.LapModelDto
import com.alphaauxiliary.fitgenerator.data.AppSettings
import com.alphaauxiliary.fitgenerator.data.AppSettingsRepository
import com.alphaauxiliary.fitgenerator.data.SavedRoute
import com.alphaauxiliary.fitgenerator.data.SavedRouteRepository
import com.alphaauxiliary.fitgenerator.map.CoordinateSystem
import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import com.alphaauxiliary.fitgenerator.map.LocationSearchResult
import com.alphaauxiliary.fitgenerator.map.MapProvider
import com.alphaauxiliary.fitgenerator.map.ProviderConnectionResult
import com.alphaauxiliary.fitgenerator.map.ProviderKeyPlacement
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class MainViewModelTest {
    private lateinit var dispatcher: TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `empty drawing ready previewing ready follows the route workflow`() = runTest(dispatcher) {
        val fixture = fixture(savedRoute = SavedRoute.Empty)
        runCurrent()

        fixture.viewModel.state.map { it.phase }.distinctUntilChanged().test {
            assertEquals(MainPhase.EMPTY, awaitItem())

            fixture.viewModel.setDrawingEnabled(true)
            assertEquals(MainPhase.DRAWING, awaitItem())
            fixture.viewModel.addDrawnPoint(ROUTE_POINTS[0])
            fixture.viewModel.addDrawnPoint(GeoCoordinate(39.90001, 116.40001))
            assertEquals(1, fixture.viewModel.state.value.routeMapState.canonicalWgs84Points.size)
            fixture.viewModel.addDrawnPoint(ROUTE_POINTS[1])
            fixture.viewModel.addDrawnPoint(ROUTE_POINTS[0])
            assertEquals(2, fixture.viewModel.state.value.routeMapState.canonicalWgs84Points.size)
            runCurrent()
            fixture.viewModel.finishDrawing()
            assertEquals(MainPhase.READY, awaitItem())

            fixture.viewModel.previewRoute()
            assertEquals(MainPhase.PREVIEWING, awaitItem())
            runCurrent()
            assertEquals(MainPhase.READY, awaitItem())
            assertNotNull(fixture.viewModel.state.value.preview)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ready exporting ready preserves preview and route`() = runTest(dispatcher) {
        val exportGate = CompletableDeferred<Unit>()
        val fixture = fixture(
            savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS),
            exportCoordinator = MainExportCoordinator { exportGate.await() },
        )
        runCurrent()
        fixture.viewModel.previewRoute()
        runCurrent()
        val preview = fixture.viewModel.state.value.preview
        assertNotNull(preview)

        fixture.viewModel.state.map { it.phase }.distinctUntilChanged().test {
            assertEquals(MainPhase.READY, awaitItem())
            fixture.viewModel.exportPreview()
            assertEquals(MainPhase.EXPORTING, awaitItem())
            exportGate.complete(Unit)
            runCurrent()
            assertEquals(MainPhase.READY, awaitItem())
            assertSame(preview, fixture.viewModel.state.value.preview)
            assertEquals(ROUTE_POINTS, fixture.viewModel.state.value.routeMapState.canonicalWgs84Points)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `core error preserves route and inputs then dismisses back to ready`() = runTest(dispatcher) {
        val settings = AppSettings.Default.copy(
            paceSecondsPerKilometer = 420.0,
            restingHeartRate = 58,
            maximumHeartRate = 188,
            lapCount = 3,
        )
        val fixture = fixture(
            settings = settings,
            savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS),
            previewer = ActivityPreviewer { _, _ ->
                throw CoreException(code = "invalid_input", message = "配速与路线不匹配")
            },
        )
        runCurrent()

        fixture.viewModel.state.map { it.phase }.distinctUntilChanged().test {
            assertEquals(MainPhase.READY, awaitItem())
            fixture.viewModel.previewRoute()
            assertEquals(MainPhase.PREVIEWING, awaitItem())
            runCurrent()
            assertEquals(MainPhase.ERROR, awaitItem())
            assertEquals(ROUTE_POINTS, fixture.viewModel.state.value.routeMapState.canonicalWgs84Points)
            assertEquals(420.0, fixture.viewModel.state.value.paceSecondsPerKilometer, 0.0)
            assertEquals(58, fixture.viewModel.state.value.restingHeartRate)
            assertEquals(188, fixture.viewModel.state.value.maximumHeartRate)
            assertEquals(3, fixture.viewModel.state.value.lapCount)

            fixture.viewModel.dismissError()
            assertEquals(MainPhase.READY, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `map notice keeps ready state and canonical route`() = runTest(dispatcher) {
        val fixture = fixture(savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS))
        runCurrent()

        fixture.viewModel.reportMapError("在线底图不可达，已使用离线底板")

        assertEquals(MainPhase.READY, fixture.viewModel.state.value.phase)
        assertEquals(ROUTE_POINTS, fixture.viewModel.state.value.routeMapState.canonicalWgs84Points)
        assertEquals("在线底图不可达，已使用离线底板", fixture.viewModel.state.value.errorMessage)

        fixture.viewModel.dismissError()
        assertEquals(MainPhase.READY, fixture.viewModel.state.value.phase)
        assertNull(fixture.viewModel.state.value.errorMessage)
    }

    @Test
    fun `route edits clear preview but export destination changes do not`() = runTest(dispatcher) {
        val fixture = fixture(savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS))
        runCurrent()
        fixture.viewModel.previewRoute()
        runCurrent()
        val preview = fixture.viewModel.state.value.preview
        assertNotNull(preview)

        fixture.viewModel.updateExportDestination("content://selected/tree")
        assertSame(preview, fixture.viewModel.state.value.preview)

        fixture.viewModel.setDrawingEnabled(true)
        fixture.viewModel.addDrawnPoint(GeoCoordinate(39.9020, 116.4020))
        runCurrent()
        assertNull(fixture.viewModel.state.value.preview)
        assertEquals("content://selected/tree", fixture.viewModel.state.value.exportDestination)
        assertTrue(fixture.routeRepository.savedRoutes.isNotEmpty())
    }

    @Test
    fun `export reuses the successful preview input seed and immutable model`() = runTest(dispatcher) {
        var previewInput: ActivityInput? = null
        var previewSeed: ULong? = null
        var exportRequest: MainExportRequest? = null
        val fixture = fixture(
            savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS),
            previewer = ActivityPreviewer { input, seed ->
                previewInput = input
                previewSeed = seed
                previewModel(seed)
            },
            exportCoordinator = MainExportCoordinator { request -> exportRequest = request },
        )
        runCurrent()

        fixture.viewModel.previewRoute()
        runCurrent()
        val preview = checkNotNull(fixture.viewModel.state.value.preview)
        fixture.viewModel.updateExportDestination("content://selected/tree")
        fixture.viewModel.exportPreview()
        runCurrent()

        val request = checkNotNull(exportRequest)
        assertSame(previewInput, request.input)
        assertEquals(previewSeed, request.seed)
        assertSame(preview, request.model)
        assertEquals("content://selected/tree", request.destination)
    }

    @Test
    fun `map notice does not cancel an in flight preview`() = runTest(dispatcher) {
        val firstResultGate = CompletableDeferred<Unit>()
        var previewCalls = 0
        val fixture = fixture(
            savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS),
            previewer = ActivityPreviewer { _, seed ->
                previewCalls += 1
                if (previewCalls == 1) {
                    withContext(NonCancellable) { firstResultGate.await() }
                    previewModel(seed).copy(totalDistanceCm = 1)
                } else {
                    previewModel(seed).copy(totalDistanceCm = 2)
                }
            },
        )
        runCurrent()

        fixture.viewModel.previewRoute()
        runCurrent()
        assertEquals(MainPhase.PREVIEWING, fixture.viewModel.state.value.phase)
        fixture.viewModel.previewRoute()
        runCurrent()
        assertEquals(1, previewCalls)

        fixture.viewModel.reportMapError("地图暂时不可用；路线仍已保留")
        assertEquals(MainPhase.PREVIEWING, fixture.viewModel.state.value.phase)
        fixture.viewModel.dismissError()
        assertEquals(MainPhase.PREVIEWING, fixture.viewModel.state.value.phase)

        firstResultGate.complete(Unit)
        runCurrent()
        assertEquals(1, previewCalls)
        assertEquals(1L, fixture.viewModel.state.value.preview?.totalDistanceCm?.toLong())
        assertEquals(MainPhase.READY, fixture.viewModel.state.value.phase)
    }

    @Test
    fun `preview cancellation restores ready and still propagates cancellation`() = runTest(dispatcher) {
        val fixture = fixture(
            savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS),
            previewer = ActivityPreviewer { _, _ -> throw CancellationException("cancelled") },
        )
        runCurrent()

        fixture.viewModel.previewRoute()
        runCurrent()

        assertEquals(MainPhase.READY, fixture.viewModel.state.value.phase)
        assertNull(fixture.viewModel.state.value.preview)
    }

    @Test
    fun `search waits 400 ms cancels superseded query and never persists results`() = runTest(dispatcher) {
        val requestedQueries = mutableListOf<String>()
        val fixture = fixture(
            savedRoute = SavedRoute.Empty,
            searcher = MainLocationSearcher { query, _ ->
                requestedQueries += query
                listOf(
                    LocationSearchResult(
                        label = "东操场",
                        attribution = MapProvider.OPEN_STREET_MAP_ATTRIBUTION,
                        coordinate = ROUTE_POINTS.first(),
                    ),
                )
            },
        )
        runCurrent()

        fixture.viewModel.updateSearchQuery("东")
        advanceTimeBy(399)
        runCurrent()
        assertTrue(requestedQueries.isEmpty())

        fixture.viewModel.updateSearchQuery("东操场")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf("东操场"), requestedQueries)
        assertEquals("东操场", fixture.viewModel.state.value.searchResults.single().label)
        assertTrue(fixture.settingsRepository.savedSettings.isEmpty())
    }

    @Test
    fun `stale search cannot overwrite a repeated latest query`() = runTest(dispatcher) {
        val firstResultGate = CompletableDeferred<Unit>()
        var repeatedQueryCalls = 0
        val fixture = fixture(
            savedRoute = SavedRoute.Empty,
            searcher = MainLocationSearcher { query, _ ->
                if (query == "操场") repeatedQueryCalls += 1
                if (query == "操场" && repeatedQueryCalls == 1) {
                    withContext(NonCancellable) { firstResultGate.await() }
                    listOf(searchResult("旧结果"))
                } else {
                    listOf(searchResult("最新结果"))
                }
            },
        )
        runCurrent()

        fixture.viewModel.updateSearchQuery("操场")
        advanceTimeBy(400)
        runCurrent()
        fixture.viewModel.updateSearchQuery("临时查询")
        fixture.viewModel.updateSearchQuery("操场")
        advanceTimeBy(400)
        runCurrent()
        assertEquals("最新结果", fixture.viewModel.state.value.searchResults.single().label)

        firstResultGate.complete(Unit)
        runCurrent()
        assertEquals("最新结果", fixture.viewModel.state.value.searchResults.single().label)
        assertEquals(2, repeatedQueryCalls)
    }

    @Test
    fun `settings open and close never expose the stored key through state`() = runTest(dispatcher) {
        val provider = customProvider(apiKey = "private-map-key")
        val fixture = fixture(
            settings = AppSettings.Default.copy(
                activeMapProviderId = provider.id,
                customMapProviders = listOf(provider),
            ),
            savedRoute = SavedRoute.Empty,
        )
        runCurrent()

        fixture.viewModel.openSettings()

        val openState = fixture.viewModel.settingsState.value
        assertTrue(openState.isOpen)
        assertEquals(provider.id, openState.activeProviderId)
        assertTrue(openState.hasStoredApiKey)
        assertEquals("", openState.draft.toEditor().apiKey)
        assertNull(openState.customProviders.single().apiKey)
        assertFalse(openState.toString().contains("private-map-key"))

        fixture.viewModel.closeSettings()

        assertFalse(fixture.viewModel.settingsState.value.isOpen)
        assertFalse(fixture.viewModel.settingsState.value.toString().contains("private-map-key"))
    }

    @Test
    fun `saving a selected provider persists its protected key and updates the map`() =
        runTest(dispatcher) {
            val provider = customProvider(apiKey = "old-key")
            val fixture = fixture(
                settings = AppSettings.Default.copy(customMapProviders = listOf(provider)),
                savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS),
            )
            runCurrent()

            fixture.viewModel.openSettings()
            fixture.viewModel.selectSettingsProvider(provider.id)
            fixture.viewModel.updateSettingsDraft(
                fixture.viewModel.settingsState.value.draft.copy(
                    displayName = "校园专用地图",
                ),
            )
            fixture.viewModel.saveSettingsProvider(apiKeyInput = "new-key")
            assertTrue(fixture.viewModel.settingsState.value.isSaving)
            runCurrent()

            val saved = fixture.settingsRepository.savedSettings.single()
            assertEquals(provider.id, saved.activeMapProviderId)
            assertEquals("校园专用地图", saved.customMapProviders.single().displayName)
            assertEquals("new-key", saved.customMapProviders.single().apiKey)
            assertEquals("校园专用地图", fixture.viewModel.state.value.activeProviderName)
            assertEquals(provider.id, fixture.viewModel.settingsState.value.activeProviderId)
            assertFalse(fixture.viewModel.settingsState.value.isSaving)
        }

    @Test
    fun `new custom provider persists and renders its keyed XYZ source instead of OSM`() =
        runTest(dispatcher) {
            val secret = "a key/+?"
            val fixture = fixture(savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS))
            runCurrent()

            fixture.viewModel.openSettings()
            fixture.viewModel.addCustomMapProvider()
            val draft = fixture.viewModel.settingsState.value.draft
            fixture.viewModel.updateSettingsDraft(
                draft.copy(
                    displayName = "新校园地图",
                    xyzUrlTemplate = "https://tiles.example.test/{z}/{x}/{y}.png",
                    keyPlacement = ProviderKeyPlacement.QUERY_PARAMETER,
                    keyName = "token",
                    coordinateSystem = CoordinateSystem.GCJ02,
                    maxZoom = "18",
                    attribution = "校园地图数据",
                ),
            )
            fixture.viewModel.saveSettingsProvider(apiKeyInput = secret)
            runCurrent()

            val saved = fixture.settingsRepository.savedSettings.single()
            val provider = saved.customMapProviders.single()
            assertEquals(provider.id, saved.activeMapProviderId)
            assertEquals(secret, provider.apiKey)
            assertEquals("新校园地图", fixture.viewModel.state.value.activeProviderName)
            val style = fixture.viewModel.state.value.routeMapState.renderConfiguration.style
            assertNull(style.uri)
            assertTrue(style.json.orEmpty().contains("token=a%20key%2F%2B%3F"))
            assertFalse(style.json.orEmpty().contains(MapProvider.OPEN_STREET_MAP_TILE_URL))
            assertFalse(fixture.viewModel.state.value.toString().contains(secret))
            assertFalse(draft.toEditor(secret).toString().contains(secret))
        }

    @Test
    fun `header key provider is rejected with an actionable safe message`() =
        runTest(dispatcher) {
            val provider = customProvider(apiKey = "header-secret")
            val fixture = fixture(
                settings = AppSettings.Default.copy(customMapProviders = listOf(provider)),
                savedRoute = SavedRoute.Empty,
            )
            runCurrent()

            fixture.viewModel.openSettings()
            fixture.viewModel.selectSettingsProvider(provider.id)
            fixture.viewModel.updateSettingsDraft(
                fixture.viewModel.settingsState.value.draft.copy(
                    keyPlacement = ProviderKeyPlacement.HEADER,
                    keyName = "X-Api-Key",
                ),
            )
            fixture.viewModel.saveSettingsProvider(apiKeyInput = "header-secret")

            val result = checkNotNull(fixture.viewModel.settingsState.value.connectionResult)
            assertFalse(result.isSuccess)
            assertTrue(result.message.contains("Header Key"))
            assertTrue(result.message.contains("查询参数"))
            assertFalse(result.message.contains("header-secret"))
            assertTrue(fixture.settingsRepository.savedSettings.isEmpty())
        }

    @Test
    fun `legacy active header provider is retained but explicitly falls back to OSM`() =
        runTest(dispatcher) {
            val secret = "legacy-header-secret"
            val provider = customProvider(apiKey = secret).copy(
                keyPlacement = ProviderKeyPlacement.HEADER,
                keyName = "X-Api-Key",
            )
            val fixture = fixture(
                settings = AppSettings.Default.copy(
                    activeMapProviderId = provider.id,
                    customMapProviders = listOf(provider),
                ),
                savedRoute = SavedRoute.Empty,
            )
            runCurrent()

            val state = fixture.viewModel.state.value
            assertEquals(MapProvider.OpenStreetMap.displayName, state.activeProviderName)
            assertEquals(MainPhase.ERROR, state.phase)
            assertTrue(state.errorMessage.orEmpty().contains("Header Key"))
            assertTrue(state.errorMessage.orEmpty().contains("OpenStreetMap"))
            assertFalse(state.toString().contains(secret))

            fixture.viewModel.openSettings()
            assertEquals(1, fixture.viewModel.settingsState.value.customProviders.size)
        }

    @Test
    fun `restoring default keeps custom providers and switches the live map`() = runTest(dispatcher) {
        val provider = customProvider(apiKey = "kept-key")
        val fixture = fixture(
            settings = AppSettings.Default.copy(
                activeMapProviderId = provider.id,
                customMapProviders = listOf(provider),
            ),
            savedRoute = SavedRoute(wgs84Points = ROUTE_POINTS),
        )
        runCurrent()

        fixture.viewModel.openSettings()
        fixture.viewModel.restoreDefaultMapProvider()
        runCurrent()

        val saved = fixture.settingsRepository.savedSettings.single()
        assertEquals(MapProvider.OPEN_STREET_MAP_ID, saved.activeMapProviderId)
        assertEquals(listOf(provider), saved.customMapProviders)
        assertEquals(MapProvider.OpenStreetMap.displayName, fixture.viewModel.state.value.activeProviderName)
        assertEquals(
            MapProvider.OPEN_STREET_MAP_ID,
            fixture.viewModel.settingsState.value.activeProviderId,
        )
        assertTrue(fixture.viewModel.settingsState.value.isOpen)
    }

    @Test
    fun `settings save error is stable and never exposes the draft key`() = runTest(dispatcher) {
        val provider = customProvider(apiKey = "private-save-key")
        val fixture = fixture(
            settings = AppSettings.Default.copy(customMapProviders = listOf(provider)),
            savedRoute = SavedRoute.Empty,
            settingsSaveError = IllegalStateException("private-save-key at /private/path"),
        )
        runCurrent()

        fixture.viewModel.openSettings()
        fixture.viewModel.selectSettingsProvider(provider.id)
        fixture.viewModel.saveSettingsProvider()
        runCurrent()

        val result = checkNotNull(fixture.viewModel.settingsState.value.connectionResult)
        assertFalse(result.isSuccess)
        assertFalse(result.message.contains("private-save-key"))
        assertFalse(result.message.contains("/private/path"))
        assertEquals(MapProvider.OpenStreetMap.displayName, fixture.viewModel.state.value.activeProviderName)
        assertTrue(fixture.settingsRepository.savedSettings.isEmpty())

        fixture.settingsRepository.saveError = null
        fixture.viewModel.saveSettingsProvider(apiKeyInput = "   ")
        runCurrent()

        assertNull(fixture.viewModel.settingsState.value.connectionResult)
        val recovered = fixture.settingsRepository.savedSettings.single()
        assertEquals("private-save-key", recovered.customMapProviders.single().apiKey)
    }

    @Test
    fun `connection test uses the selected draft and publishes its safe result`() =
        runTest(dispatcher) {
            val provider = customProvider(apiKey = "connection-key")
            val searcher = FakeLocationSearcher(
                result = ProviderConnectionResult(
                    isSuccess = true,
                    message = "连接成功：connection-key",
                ),
            )
            val fixture = fixture(
                settings = AppSettings.Default.copy(customMapProviders = listOf(provider)),
                savedRoute = SavedRoute.Empty,
                searcher = searcher,
            )
            runCurrent()

            fixture.viewModel.openSettings()
            fixture.viewModel.selectSettingsProvider(provider.id)
            fixture.viewModel.testSettingsConnection()
            runCurrent()

            assertEquals(provider.id, searcher.testedProviders.single().id)
            assertEquals(true, fixture.viewModel.settingsState.value.connectionResult?.isSuccess)
            assertFalse(
                fixture.viewModel.settingsState.value.connectionResult
                    ?.message
                    .orEmpty()
                    .contains("connection-key"),
            )
            assertFalse(fixture.viewModel.settingsState.value.isTestingConnection)
        }

    @Test
    fun `cancelled stale connection result cannot replace the latest draft result`() =
        runTest(dispatcher) {
            val provider = customProvider(apiKey = "stale-key")
            val firstResultGate = CompletableDeferred<Unit>()
            var calls = 0
            val searcher = object : MainLocationSearcher {
                override suspend fun search(
                    query: String,
                    provider: MapProvider,
                ): List<LocationSearchResult> = emptyList()

                override suspend fun testConnection(provider: MapProvider): ProviderConnectionResult {
                    calls += 1
                    return if (calls == 1) {
                        withContext(NonCancellable) { firstResultGate.await() }
                        ProviderConnectionResult(isSuccess = false, message = "旧结果")
                    } else {
                        ProviderConnectionResult(isSuccess = true, message = "最新结果")
                    }
                }
            }
            val fixture = fixture(
                settings = AppSettings.Default.copy(customMapProviders = listOf(provider)),
                savedRoute = SavedRoute.Empty,
                searcher = searcher,
            )
            runCurrent()

            fixture.viewModel.openSettings()
            fixture.viewModel.selectSettingsProvider(provider.id)
            fixture.viewModel.testSettingsConnection()
            runCurrent()
            fixture.viewModel.updateSettingsDraft(
                fixture.viewModel.settingsState.value.draft.copy(displayName = "新地图名称"),
            )
            fixture.viewModel.testSettingsConnection()
            runCurrent()
            assertEquals("最新结果", fixture.viewModel.settingsState.value.connectionResult?.message)

            firstResultGate.complete(Unit)
            runCurrent()

            assertEquals(2, calls)
            assertEquals("最新结果", fixture.viewModel.settingsState.value.connectionResult?.message)
        }

    @Test
    fun `closing settings cancels an in flight connection test without an error`() =
        runTest(dispatcher) {
            val provider = customProvider(apiKey = "cancel-key")
            val connectionGate = CompletableDeferred<ProviderConnectionResult>()
            var cancellationObserved = false
            val searcher = object : MainLocationSearcher {
                override suspend fun search(
                    query: String,
                    provider: MapProvider,
                ): List<LocationSearchResult> = emptyList()

                override suspend fun testConnection(provider: MapProvider): ProviderConnectionResult =
                    try {
                        connectionGate.await()
                    } catch (cancelled: CancellationException) {
                        cancellationObserved = true
                        throw cancelled
                    }
            }
            val fixture = fixture(
                settings = AppSettings.Default.copy(customMapProviders = listOf(provider)),
                savedRoute = SavedRoute.Empty,
                searcher = searcher,
            )
            runCurrent()

            fixture.viewModel.openSettings()
            fixture.viewModel.selectSettingsProvider(provider.id)
            fixture.viewModel.testSettingsConnection()
            runCurrent()
            assertTrue(fixture.viewModel.settingsState.value.isTestingConnection)

            fixture.viewModel.closeSettings()
            runCurrent()

            assertTrue(cancellationObserved)
            assertFalse(fixture.viewModel.settingsState.value.isOpen)
            assertNull(fixture.viewModel.settingsState.value.connectionResult)
        }

    private fun fixture(
        settings: AppSettings = AppSettings.Default,
        savedRoute: SavedRoute,
        previewer: ActivityPreviewer = ActivityPreviewer { _, seed -> previewModel(seed) },
        searcher: MainLocationSearcher = MainLocationSearcher { _, _ -> emptyList() },
        exportCoordinator: MainExportCoordinator = MainExportCoordinator { },
        settingsSaveError: Throwable? = null,
    ): Fixture {
        val settingsRepository = FakeSettingsRepository(settings, settingsSaveError)
        val routeRepository = FakeRouteRepository(savedRoute)
        val viewModel = MainViewModel(
            core = previewer,
            settingsRepository = settingsRepository,
            routeRepository = routeRepository,
            locationSearch = searcher,
            exportCoordinator = exportCoordinator,
            clock = FixedClock,
            workerDispatcher = dispatcher,
        )
        return Fixture(viewModel, settingsRepository, routeRepository)
    }

    private data class Fixture(
        val viewModel: MainViewModel,
        val settingsRepository: FakeSettingsRepository,
        val routeRepository: FakeRouteRepository,
    )

    private class FakeSettingsRepository(
        private var settings: AppSettings,
        var saveError: Throwable? = null,
    ) : AppSettingsRepository {
        val savedSettings = mutableListOf<AppSettings>()

        override suspend fun load(): AppSettings = settings

        override suspend fun save(settings: AppSettings) {
            saveError?.let { throw it }
            this.settings = settings
            savedSettings += settings
        }
    }

    private class FakeLocationSearcher(
        private val result: ProviderConnectionResult,
    ) : MainLocationSearcher {
        val testedProviders = mutableListOf<MapProvider>()

        override suspend fun search(
            query: String,
            provider: MapProvider,
        ): List<LocationSearchResult> = emptyList()

        override suspend fun testConnection(provider: MapProvider): ProviderConnectionResult {
            testedProviders += provider
            return result
        }
    }

    private class FakeRouteRepository(
        private var route: SavedRoute,
    ) : SavedRouteRepository {
        val savedRoutes = mutableListOf<SavedRoute>()

        override suspend fun load(): SavedRoute = route

        override suspend fun save(route: SavedRoute) {
            this.route = route
            savedRoutes += route
        }
    }

    private object FixedClock : MainClock {
        override fun nowCalendar(): Calendar = Calendar.getInstance(UTC).apply {
            timeInMillis = FIXED_TIME_MILLIS
        }

        override fun nowMillis(): Long = FIXED_TIME_MILLIS
    }

    private companion object {
        const val FIXED_TIME_MILLIS = 1_788_163_200_000L
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        val ROUTE_POINTS = listOf(
            GeoCoordinate(latitude = 39.9000, longitude = 116.4000),
            GeoCoordinate(latitude = 39.9010, longitude = 116.4010),
        )

        fun previewModel(seed: ULong): ActivityModelDto = ActivityModelDto(
            schemaVersion = 1,
            algorithmVersion = 1,
            startTimeUtc = "2026-08-08T00:00:00.000Z",
            seed = seed,
            totalDistanceCm = 14_000,
            totalDurationMs = 60_000,
            laps = listOf(
                LapModelDto(
                    index = 0,
                    startSample = 0,
                    endSample = 1,
                    distanceCm = 14_000,
                    durationMs = 60_000,
                ),
            ),
            samples = listOf(
                ActivitySampleDto(
                    timeMs = 0,
                    distanceCm = 0,
                    speedMmPerSec = 2_000,
                    heartRateBpm = 120,
                    positionLatSemicircles = 476_204_329,
                    positionLongSemicircles = 1_388_819_573,
                ),
                ActivitySampleDto(
                    timeMs = 60_000,
                    distanceCm = 14_000,
                    speedMmPerSec = 2_000,
                    heartRateBpm = 150,
                    positionLatSemicircles = 476_216_259,
                    positionLongSemicircles = 1_388_831_503,
                ),
            ),
        )

        fun searchResult(label: String): LocationSearchResult = LocationSearchResult(
            label = label,
            attribution = MapProvider.OPEN_STREET_MAP_ATTRIBUTION,
            coordinate = ROUTE_POINTS.first(),
        )

        fun customProvider(apiKey: String): MapProvider = MapProvider.custom(
            id = "campus-map",
            displayName = "校园地图",
            xyzUrlTemplate = "https://tiles.example.test/{z}/{x}/{y}.png",
            coordinateSystem = CoordinateSystem.WGS84,
            maxZoom = 19,
            attribution = "示例地图数据",
            geocoderUrl = "https://search.example.test/search",
            keyPlacement = ProviderKeyPlacement.QUERY_PARAMETER,
            keyName = "token",
            apiKey = apiKey,
        )
    }
}
