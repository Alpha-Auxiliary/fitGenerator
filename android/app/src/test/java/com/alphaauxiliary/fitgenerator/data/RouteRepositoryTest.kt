package com.alphaauxiliary.fitgenerator.data

import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing route yields an empty WGS84 route`() = runTest {
        val repository = createRepository()

        assertEquals(SavedRoute.Empty, repository.load())
    }

    @Test
    fun `route round trips and replacement leaves no temporary file`() = runTest {
        val repository = createRepository()
        val first = routeAt(latitude = 39.9042, longitude = 116.4074)
        val replacement = routeAt(latitude = 31.2304, longitude = 121.4737)

        repository.save(first)
        assertEquals(first, repository.load())
        repository.save(replacement)

        assertEquals(replacement, repository.load())
        assertFalse(File(temporaryFolder.root, "last-route-v1.json.tmp").exists())
        val rawPayload = repository.routeFilePath.readText(Charsets.UTF_8)
        assertTrue(rawPayload.contains("\"schemaVersion\":1"))
        assertTrue(rawPayload.contains("\"wgs84Points\""))
    }

    @Test
    fun `unknown schema is atomically backed up byte for byte`() = runTest {
        val repository = createRepository(clockMillis = { 1_725_000_000_000L })
        val rawPayload = """{"schemaVersion":2,"wgs84Points":[],"future":"preserve"}"""
        repository.routeFilePath.writeText(rawPayload, Charsets.UTF_8)

        val loaded = repository.load()

        assertEquals(SavedRoute.Empty, loaded)
        assertFalse(repository.routeFilePath.exists())
        val backups = temporaryFolder.root.listFiles { file ->
            file.name.startsWith("last-route-v1.invalid-") && file.name.endsWith(".json")
        }.orEmpty()
        assertEquals(1, backups.size)
        assertEquals(rawPayload, backups.single().readText(Charsets.UTF_8))
    }

    @Test
    fun `invalid coordinates are rejected without replacing the prior route`() = runTest {
        val repository = createRepository()
        val original = routeAt(latitude = 39.9042, longitude = 116.4074)
        repository.save(original)
        val invalid = SavedRoute(
            wgs84Points = listOf(
                GeoCoordinate(latitude = Double.NaN, longitude = 116.4074),
            ),
        )

        assertSuspendThrows<RouteRepositoryException> {
            repository.save(invalid)
        }

        assertEquals(original, repository.load())
    }

    @Test
    fun `failed atomic rename preserves the previous route and removes the temp file`() = runTest {
        val working = createRepository()
        val original = routeAt(latitude = 39.9042, longitude = 116.4074)
        working.save(original)
        val failing = RouteRepository(
            storageDirectory = temporaryFolder.root,
            atomicFileMover = AtomicFileMover { _, _ -> throw IOException("simulated") },
            dispatcher = Dispatchers.Unconfined,
        )

        assertSuspendThrows<RouteRepositoryException> {
            failing.save(routeAt(latitude = 31.2304, longitude = 121.4737))
        }

        assertEquals(original, working.load())
        assertFalse(File(temporaryFolder.root, "last-route-v1.json.tmp").exists())
    }

    private fun createRepository(
        clockMillis: () -> Long = { 1_725_000_000_000L },
    ): RouteRepository = RouteRepository(
        storageDirectory = temporaryFolder.root,
        atomicFileMover = JvmAtomicFileMover,
        dispatcher = Dispatchers.Unconfined,
        clockMillis = clockMillis,
    )

    private fun routeAt(latitude: Double, longitude: Double): SavedRoute = SavedRoute(
        wgs84Points = listOf(
            GeoCoordinate(latitude = latitude, longitude = longitude),
            GeoCoordinate(latitude = latitude + 0.001, longitude = longitude + 0.001),
        ),
    )

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

    private object JvmAtomicFileMover : AtomicFileMover {
        override fun replace(source: File, destination: File) {
            try {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: Exception) {
                throw IOException("Atomic test move failed", error)
            }
        }
    }
}
