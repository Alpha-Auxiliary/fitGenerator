package com.alphaauxiliary.fitgenerator.export

import com.alphaauxiliary.fitgenerator.core.ActivityInput
import com.alphaauxiliary.fitgenerator.core.ActivityModelDto
import com.alphaauxiliary.fitgenerator.core.ActivityRoutePoint
import com.alphaauxiliary.fitgenerator.core.ActivitySampleDto
import com.alphaauxiliary.fitgenerator.core.LapModelDto
import com.alphaauxiliary.fitgenerator.data.AppSettings
import com.alphaauxiliary.fitgenerator.data.AppSettingsRepository
import com.alphaauxiliary.fitgenerator.data.SavedRoute
import com.alphaauxiliary.fitgenerator.data.SavedRouteRepository
import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import com.alphaauxiliary.fitgenerator.ui.ActivityPreviewer
import com.alphaauxiliary.fitgenerator.ui.MainClock
import com.alphaauxiliary.fitgenerator.ui.MainExportRequest
import com.alphaauxiliary.fitgenerator.ui.MainLocationSearcher
import com.alphaauxiliary.fitgenerator.ui.MainPhase
import com.alphaauxiliary.fitgenerator.ui.MainViewModel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class ExportCoordinatorTest {
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
    fun `one export proposes run fit and waits for the destination`() = runTest(dispatcher) {
        val writer = RecordingWriter(mutableListOf())
        val coordinator = coordinator(writer = writer)

        val export = async { coordinator.export(request(count = 1)) }
        runCurrent()

        val pending = checkNotNull(coordinator.pendingDocument.value)
        assertEquals(ExportCoordinator.SINGLE_FILE_NAME, pending.suggestedFileName)
        assertEquals(ExportCoordinator.FIT_MIME_TYPE, pending.mimeType)
        assertTrue(coordinator.markDocumentPickerLaunched(pending.requestId))
        assertFalse(coordinator.markDocumentPickerLaunched(pending.requestId))
        assertFalse(coordinator.completeDocument(pending.requestId + 1L, "content://test/stale.fit"))
        assertTrue(writer.payloads.isEmpty())
        assertTrue(coordinator.completeDocument(pending.requestId, "content://test/run.fit"))
        assertFalse(coordinator.completeDocument(pending.requestId, "content://test/again.fit"))
        export.await()

        assertEquals(1, writer.payloads.size)
        assertNull(coordinator.pendingDocument.value)
    }

    @Test
    fun `multiple exports propose runs zip and generate every fit before writing`() =
        runTest(dispatcher) {
            val events = mutableListOf<String>()
            val generator = RecordingGenerator(events = events)
            val writer = RecordingWriter(events)
            val coordinator = coordinator(generator = generator, writer = writer)

            val export = async { coordinator.export(request(count = 3)) }
            runCurrent()

            val pending = checkNotNull(coordinator.pendingDocument.value)
            assertEquals(ExportCoordinator.BATCH_FILE_NAME, pending.suggestedFileName)
            assertEquals(ExportCoordinator.ZIP_MIME_TYPE, pending.mimeType)
            assertEquals(listOf("generate-1", "generate-2", "generate-3"), events)

            coordinator.completeDocument(pending.requestId, "content://test/runs.zip")
            export.await()

            assertEquals(
                listOf("generate-1", "generate-2", "generate-3", "write"),
                events,
            )
            assertEquals(listOf(1, 2, 3), generator.variantIndices)
            assertTrue(generator.seeds.all { seed -> seed == PREVIEW_SEED })
        }

    @Test
    fun `generation failure publishes no destination and writes zero bytes`() =
        runTest(dispatcher) {
            val writer = RecordingWriter(mutableListOf())
            val coordinator = coordinator(
                generator = RecordingGenerator(
                    events = mutableListOf(),
                    failAtVariant = 2,
                ),
                writer = writer,
            )

            val failure = try {
                coordinator.export(request(count = 3))
                null
            } catch (error: IllegalStateException) {
                error
            }

            assertNotNull(failure)
            assertNull(coordinator.pendingDocument.value)
            assertTrue(writer.payloads.isEmpty())
        }

    @Test
    fun `batch count is limited to one through twenty before generation`() =
        runTest(dispatcher) {
            listOf(0, 21).forEach { count ->
                val generator = RecordingGenerator(mutableListOf())
                val coordinator = coordinator(generator = generator)

                val failure = try {
                    coordinator.export(request(count = count))
                    null
                } catch (error: ExportException) {
                    error
                }

                assertEquals(ExportErrorCode.INVALID_REQUEST, failure?.code)
                assertTrue(generator.variantIndices.isEmpty())
                assertNull(coordinator.pendingDocument.value)
            }
        }

    @Test
    fun `zip entries are ordered and deterministically named`() = runTest(dispatcher) {
        val writer = RecordingWriter(mutableListOf())
        val coordinator = coordinator(writer = writer)

        val export = async { coordinator.export(request(count = 3)) }
        runCurrent()
        val pending = checkNotNull(coordinator.pendingDocument.value)
        coordinator.completeDocument(pending.requestId, "content://test/runs.zip")
        export.await()

        val entries = readZip(checkNotNull(writer.payloads.single()))
        assertEquals(listOf("run_1.fit", "run_2.fit", "run_3.fit"), entries.map { it.first })
        entries.forEachIndexed { index, (_, payload) ->
            assertArrayEquals(fitPayload(index + 1), payload)
        }
    }

    @Test
    fun `zip bytes are identical for repeated identical batches`() = runTest(dispatcher) {
        val firstWriter = RecordingWriter(mutableListOf())
        val firstCoordinator = coordinator(writer = firstWriter)
        val firstExport = async { firstCoordinator.export(request(count = 3)) }
        runCurrent()
        val firstPending = checkNotNull(firstCoordinator.pendingDocument.value)
        firstCoordinator.completeDocument(firstPending.requestId, "content://test/first.zip")
        firstExport.await()

        val secondWriter = RecordingWriter(mutableListOf())
        val secondCoordinator = coordinator(writer = secondWriter)
        val secondExport = async { secondCoordinator.export(request(count = 3)) }
        runCurrent()
        val secondPending = checkNotNull(secondCoordinator.pendingDocument.value)
        secondCoordinator.completeDocument(secondPending.requestId, "content://test/second.zip")
        secondExport.await()

        assertArrayEquals(firstWriter.payloads.single(), secondWriter.payloads.single())
        assertTrue(readZipMethods(firstWriter.payloads.single()).all { it == java.util.zip.ZipEntry.STORED })
    }

    @Test
    fun `total fit byte limit fails before a destination is requested`() = runTest(dispatcher) {
        val generator = RecordingGenerator(mutableListOf())
        val writer = RecordingWriter(mutableListOf())
        val coordinator = coordinator(
            generator = generator,
            writer = writer,
            maximumTotalFitBytes = 8L,
        )

        val failure = try {
            coordinator.export(request(count = 2))
            null
        } catch (error: ExportException) {
            error
        }

        assertEquals(ExportErrorCode.RESOURCE_LIMIT, failure?.code)
        assertEquals(listOf(1, 2), generator.variantIndices)
        assertNull(coordinator.pendingDocument.value)
        assertTrue(writer.payloads.isEmpty())
    }

    @Test
    fun `content writer requests truncate mode closes once and preserves bytes`() =
        runTest(dispatcher) {
            val output = RecordingOutputStream()
            var openedDestination: String? = null
            var openedMode: String? = null
            val writer = ContentResolverExportDestinationWriter(
                outputStreamOpener = ExportOutputStreamOpener { destination, mode ->
                    openedDestination = destination
                    openedMode = mode
                    output
                },
                dispatcher = dispatcher,
            )

            writer.write("content://test/run.fit", byteArrayOf(1, 2, 3))

            assertEquals("content://test/run.fit", openedDestination)
            assertEquals("rwt", openedMode)
            assertArrayEquals(byteArrayOf(1, 2, 3), output.bytes.toByteArray())
            assertEquals(1, output.closeCalls)
        }

    @Test
    fun `content writer maps null security disk full and cancellation without raw paths`() =
        runTest(dispatcher) {
            val nullStreamError = captureExportFailure(
                ContentResolverExportDestinationWriter(
                    outputStreamOpener = ExportOutputStreamOpener { _, _ -> null },
                    dispatcher = dispatcher,
                ),
            )
            assertEquals(ExportErrorCode.FILE_PERMISSION_DENIED, nullStreamError.code)

            val securityError = captureExportFailure(
                ContentResolverExportDestinationWriter(
                    outputStreamOpener = ExportOutputStreamOpener { _, _ ->
                        throw SecurityException("secret/provider/path")
                    },
                    dispatcher = dispatcher,
                ),
            )
            assertEquals(ExportErrorCode.FILE_PERMISSION_DENIED, securityError.code)
            assertFalse(securityError.message.orEmpty().contains("secret/provider/path"))

            val partialDiskFullStream = FailingOutputStream(
                failure = IOException("private/path"),
                bytesBeforeFailure = 1,
            )
            val diskFullError = captureExportFailure(
                ContentResolverExportDestinationWriter(
                    outputStreamOpener = ExportOutputStreamOpener { _, _ -> partialDiskFullStream },
                    dispatcher = dispatcher,
                    diskFullDetector = DiskFullDetector { true },
                ),
            )
            assertEquals(ExportErrorCode.DISK_FULL, diskFullError.code)
            assertFalse(diskFullError.message.orEmpty().contains("private/path"))
            assertEquals(1, partialDiskFullStream.bytesWritten)
            assertEquals(1, partialDiskFullStream.closeCalls)

            val cancellation = CancellationException("cancel export")
            val cancellingWriter = ContentResolverExportDestinationWriter(
                outputStreamOpener = ExportOutputStreamOpener { _, _ -> throw cancellation },
                dispatcher = dispatcher,
            )
            val propagated = try {
                cancellingWriter.write("content://test/run.fit", byteArrayOf(1))
                null
            } catch (error: CancellationException) {
                error
            }
            assertNotNull(propagated)
            assertEquals(cancellation.message, propagated?.message)
        }

    @Test
    fun `share fallback uses only the prepared document and releases the export`() =
        runTest(dispatcher) {
            val shareStore = RecordingShareStore()
            val writer = RecordingWriter(mutableListOf())
            val coordinator = coordinator(writer = writer, shareStore = shareStore)

            coordinator.deleteStaleShareExports()

            val export = async { coordinator.export(request(count = 2)) }
            runCurrent()
            val pending = checkNotNull(coordinator.pendingDocument.value)

            assertTrue(coordinator.markDocumentPickerLaunched(pending.requestId))
            val share = coordinator.prepareShareFallback(pending.requestId)

            assertEquals(1, shareStore.cleanupCalls)
            assertEquals(1, shareStore.writeCalls)
            assertEquals(ExportCoordinator.BATCH_FILE_NAME, share.fileName)
            assertEquals(ExportCoordinator.ZIP_MIME_TYPE, share.mimeType)
            assertEquals(pending.requestId, share.requestId)
            assertTrue(writer.payloads.isEmpty())

            assertTrue(coordinator.completeShare(pending.requestId))
            export.await()
            assertNull(coordinator.pendingDocument.value)
            assertTrue(writer.payloads.isEmpty())
        }

    @Test
    fun `picker cancellation returns the screen to ready with preview intact`() =
        runTest(dispatcher) {
            val coordinator = coordinator()
            val route = SavedRoute(wgs84Points = ROUTE_POINTS)
            val viewModel = MainViewModel(
                core = ActivityPreviewer { _, seed -> previewModel(seed) },
                settingsRepository = FakeSettingsRepository,
                routeRepository = FakeRouteRepository(route),
                locationSearch = MainLocationSearcher { _, _ -> emptyList() },
                exportCoordinator = coordinator,
                clock = FixedClock,
                workerDispatcher = dispatcher,
            )
            runCurrent()
            viewModel.previewRoute()
            runCurrent()
            val preview = checkNotNull(viewModel.state.value.preview)

            viewModel.exportPreview()
            runCurrent()
            assertEquals(MainPhase.EXPORTING, viewModel.state.value.phase)
            val pending = checkNotNull(coordinator.pendingDocument.value)

            assertTrue(coordinator.markDocumentPickerLaunched(pending.requestId))
            assertTrue(coordinator.completeDocument(pending.requestId, destination = null))
            runCurrent()

            assertEquals(MainPhase.READY, viewModel.state.value.phase)
            assertSame(preview, viewModel.state.value.preview)
            assertEquals(ROUTE_POINTS, viewModel.state.value.routeMapState.canonicalWgs84Points)
        }

    @Test
    fun `stale document result never writes the current pending export`() =
        runTest(dispatcher) {
            val writer = RecordingWriter(mutableListOf())
            val coordinator = coordinator(writer = writer)
            val viewModel = MainViewModel(
                core = ActivityPreviewer { _, seed -> previewModel(seed) },
                settingsRepository = FakeSettingsRepository,
                routeRepository = FakeRouteRepository(SavedRoute(wgs84Points = ROUTE_POINTS)),
                locationSearch = MainLocationSearcher { _, _ -> emptyList() },
                exportCoordinator = coordinator,
                clock = FixedClock,
                workerDispatcher = dispatcher,
            )
            runCurrent()
            viewModel.previewRoute()
            runCurrent()
            val preview = checkNotNull(viewModel.state.value.preview)
            viewModel.exportPreview()
            runCurrent()
            val pending = checkNotNull(coordinator.pendingDocument.value)

            viewModel.completeExportDocument(
                requestId = pending.requestId + 1L,
                destination = "content://test/stale.fit",
            )
            runCurrent()

            assertEquals(MainPhase.ERROR, viewModel.state.value.phase)
            assertSame(preview, viewModel.state.value.preview)
            assertTrue(writer.payloads.isEmpty())
            assertNull(coordinator.pendingDocument.value)
        }

    private fun coordinator(
        generator: FitPayloadGenerator = RecordingGenerator(mutableListOf()),
        writer: RecordingWriter = RecordingWriter(mutableListOf()),
        shareStore: ExportShareStore = RecordingShareStore(),
        maximumTotalFitBytes: Long = 32L * 1024L * 1024L,
    ): ExportCoordinator = ExportCoordinator(
        generator = generator,
        destinationWriter = writer,
        shareStore = shareStore,
        maximumTotalFitBytes = maximumTotalFitBytes,
    )

    private suspend fun captureExportFailure(
        writer: ContentResolverExportDestinationWriter,
    ): ExportException = try {
        writer.write("content://test/run.fit", byteArrayOf(1, 2, 3))
        error("expected export failure")
    } catch (error: ExportException) {
        error
    }

    private class RecordingGenerator(
        private val events: MutableList<String>,
        private val failAtVariant: Int? = null,
    ) : FitPayloadGenerator {
        val variantIndices = mutableListOf<Int>()
        val seeds = mutableListOf<ULong>()

        override suspend fun generate(
            input: ActivityInput,
            seed: ULong,
            variantIndex: Int,
        ): ByteArray {
            events += "generate-$variantIndex"
            variantIndices += variantIndex
            seeds += seed
            if (variantIndex == failAtVariant) error("generation failed")
            return fitPayload(variantIndex)
        }
    }

    private class RecordingWriter(
        private val events: MutableList<String>,
    ) : ExportDestinationWriter {
        val destinations = mutableListOf<String>()
        val payloads = mutableListOf<ByteArray>()

        override suspend fun write(destination: String, payload: ByteArray) {
            events += "write"
            destinations += destination
            payloads += payload.copyOf()
        }
    }

    private class RecordingOutputStream : OutputStream() {
        val bytes = mutableListOf<Byte>()
        var closeCalls = 0
            private set

        override fun write(value: Int) {
            bytes += value.toByte()
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class FailingOutputStream(
        private val failure: IOException,
        private val bytesBeforeFailure: Int = 0,
    ) : OutputStream() {
        var bytesWritten = 0
            private set
        var closeCalls = 0
            private set

        override fun write(value: Int) {
            if (bytesWritten < bytesBeforeFailure) {
                bytesWritten += 1
                return
            }
            throw failure
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class RecordingShareStore : ExportShareStore {
        var cleanupCalls = 0
            private set
        var writeCalls = 0
            private set

        override suspend fun write(
            document: ExportDocument,
            requestId: Long,
        ): ExportShareRequest {
            writeCalls += 1
            return ExportShareRequest(
                requestId = requestId,
                contentUri = "content://test/${document.fileName}",
                fileName = document.fileName,
                mimeType = document.mimeType,
            )
        }

        override suspend fun deleteStaleExports() {
            cleanupCalls += 1
        }
    }

    private object FakeSettingsRepository : AppSettingsRepository {
        override suspend fun load(): AppSettings = AppSettings.Default

        override suspend fun save(settings: AppSettings) = Unit
    }

    private class FakeRouteRepository(
        private var route: SavedRoute,
    ) : SavedRouteRepository {
        override suspend fun load(): SavedRoute = route

        override suspend fun save(route: SavedRoute) {
            this.route = route
        }
    }

    private object FixedClock : MainClock {
        override fun nowCalendar(): Calendar = Calendar.getInstance(UTC).apply {
            timeInMillis = FIXED_TIME_MILLIS
        }

        override fun nowMillis(): Long = FIXED_TIME_MILLIS
    }

    private companion object {
        const val PREVIEW_SEED = 1_788_163_200_000uL
        const val FIXED_TIME_MILLIS = 1_788_163_200_000L
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        val ROUTE_POINTS = listOf(
            GeoCoordinate(latitude = 39.9000, longitude = 116.4000),
            GeoCoordinate(latitude = 39.9010, longitude = 116.4010),
        )

        fun request(count: Int): MainExportRequest = MainExportRequest(
            input = activityInput(),
            seed = PREVIEW_SEED,
            model = previewModel(PREVIEW_SEED),
            count = count,
            destination = null,
        )

        fun activityInput(): ActivityInput = ActivityInput(
            startTime = Calendar.getInstance(UTC).apply { timeInMillis = FIXED_TIME_MILLIS },
            routePoints = ROUTE_POINTS.map { point ->
                ActivityRoutePoint(point.latitude, point.longitude)
            },
            paceSecondsPerKilometer = 360.0,
            restingHeartRate = 60,
            maximumHeartRate = 180,
            lapCount = 1,
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
                    endSample = 0,
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
            ),
        )

        fun fitPayload(variantIndex: Int): ByteArray =
            "fit-$variantIndex".toByteArray(Charsets.UTF_8)

        fun readZip(payload: ByteArray): List<Pair<String, ByteArray>> {
            val result = mutableListOf<Pair<String, ByteArray>>()
            ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    result += entry.name to zip.readBytes()
                    zip.closeEntry()
                }
            }
            return result
        }

        fun readZipMethods(payload: ByteArray): List<Int> {
            val methods = mutableListOf<Int>()
            ZipInputStream(ByteArrayInputStream(payload)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    methods += entry.method
                    zip.readBytes()
                    zip.closeEntry()
                }
            }
            return methods
        }
    }
}
