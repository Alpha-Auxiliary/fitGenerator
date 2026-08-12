package com.alphaauxiliary.fitgenerator.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import com.alphaauxiliary.fitgenerator.map.GeoCoordinate
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun interface AtomicFileMover {
    @Throws(IOException::class)
    fun replace(source: File, destination: File)
}

internal object AndroidAtomicFileMover : AtomicFileMover {
    override fun replace(source: File, destination: File) {
        try {
            Os.rename(source.absolutePath, destination.absolutePath)
        } catch (error: ErrnoException) {
            throw IOException("Atomic rename failed", error)
        }
    }
}

internal class RouteRepositoryException(message: String) : Exception(message)

internal interface SavedRouteRepository {
    suspend fun load(): SavedRoute

    suspend fun save(route: SavedRoute)
}

internal class RouteRepository(
    private val storageDirectory: File,
    private val atomicFileMover: AtomicFileMover,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : SavedRouteRepository {
    constructor(context: Context) : this(
        storageDirectory = File(context.applicationContext.filesDir, STORAGE_DIRECTORY_NAME),
        atomicFileMover = AndroidAtomicFileMover,
    )

    private val operationMutex = Mutex()
    private val routeFile = File(storageDirectory, ROUTE_FILE_NAME)
    private val temporaryFile = File(storageDirectory, "$ROUTE_FILE_NAME.tmp")

    internal val routeFilePath: File
        get() = routeFile

    override suspend fun load(): SavedRoute = withContext(dispatcher) {
        operationMutex.withLock {
            if (!routeFile.isFile) {
                return@withLock SavedRoute.Empty
            }

            try {
                val fileSize = routeFile.length()
                if (fileSize <= 0L || fileSize > MAXIMUM_ROUTE_FILE_BYTES) {
                    throw InvalidRoutePayloadException()
                }
                val rawPayload = routeFile.readBytes()
                if (rawPayload.size.toLong() != fileSize || rawPayload.size > MAXIMUM_ROUTE_FILE_BYTES) {
                    throw InvalidRoutePayloadException()
                }
                val persisted = RouteJson.decodeFromString<RoutePayloadDto>(
                    decodeUtf8Strict(rawPayload),
                )
                restore(persisted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InvalidRoutePayloadException) {
                backupInvalidRoute()
                SavedRoute.Empty
            } catch (_: SerializationException) {
                backupInvalidRoute()
                SavedRoute.Empty
            } catch (_: CharacterCodingException) {
                backupInvalidRoute()
                SavedRoute.Empty
            } catch (_: RouteRepositoryException) {
                backupInvalidRoute()
                SavedRoute.Empty
            } catch (_: IllegalArgumentException) {
                backupInvalidRoute()
                SavedRoute.Empty
            } catch (_: IOException) {
                SavedRoute.Empty
            }
        }
    }

    override suspend fun save(route: SavedRoute) {
        withContext(dispatcher) {
            operationMutex.withLock {
                try {
                    validateRoute(route)
                    val payload = RouteJson.encodeToString(
                        RoutePayloadDto(
                            schemaVersion = route.schemaVersion,
                            wgs84Points = route.wgs84Points.map { point ->
                                RoutePointPayloadDto(
                                    latitude = point.latitude,
                                    longitude = point.longitude,
                                )
                            },
                        ),
                    ).toByteArray(Charsets.UTF_8)
                    if (payload.size > MAXIMUM_ROUTE_FILE_BYTES) {
                        throw RouteRepositoryException(SAVE_ERROR_MESSAGE)
                    }
                    writeAtomically(payload)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RouteRepositoryException) {
                    throw RouteRepositoryException(SAVE_ERROR_MESSAGE)
                } catch (_: IOException) {
                    throw RouteRepositoryException(SAVE_ERROR_MESSAGE)
                } catch (_: SerializationException) {
                    throw RouteRepositoryException(SAVE_ERROR_MESSAGE)
                } catch (_: IllegalArgumentException) {
                    throw RouteRepositoryException(SAVE_ERROR_MESSAGE)
                }
            }
        }
    }

    private fun restore(payload: RoutePayloadDto): SavedRoute {
        if (payload.schemaVersion != SavedRoute.CURRENT_SCHEMA_VERSION) {
            throw InvalidRoutePayloadException()
        }
        val route = SavedRoute(
            schemaVersion = payload.schemaVersion,
            wgs84Points = payload.wgs84Points.map { point ->
                GeoCoordinate(latitude = point.latitude, longitude = point.longitude)
            },
        )
        validateRoute(route)
        return route
    }

    private fun validateRoute(route: SavedRoute) {
        if (
            route.schemaVersion != SavedRoute.CURRENT_SCHEMA_VERSION ||
            route.wgs84Points.size > MAXIMUM_ROUTE_POINTS ||
            route.wgs84Points.any { point ->
                !point.latitude.isFinite() ||
                    point.latitude !in -90.0..90.0 ||
                    !point.longitude.isFinite() ||
                    point.longitude !in -180.0..180.0
            }
        ) {
            throw RouteRepositoryException("最近轨迹数据无效")
        }
    }

    private fun writeAtomically(payload: ByteArray) {
        if (!storageDirectory.isDirectory && !storageDirectory.mkdirs()) {
            throw IOException("Route storage directory is unavailable")
        }
        if (temporaryFile.exists() && !temporaryFile.delete()) {
            throw IOException("Stale route temporary file cannot be removed")
        }

        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            atomicFileMover.replace(temporaryFile, routeFile)
        } finally {
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }
    }

    private fun backupInvalidRoute() {
        if (!routeFile.exists()) {
            return
        }
        val timestamp = clockMillis().coerceAtLeast(0L)
        for (suffix in 0 until MAXIMUM_BACKUP_ATTEMPTS) {
            val suffixText = if (suffix == 0) "" else "-$suffix"
            val backup = File(
                storageDirectory,
                "last-route-v1.invalid-$timestamp$suffixText.json",
            )
            if (backup.exists()) {
                continue
            }
            try {
                atomicFileMover.replace(routeFile, backup)
            } catch (_: IOException) {
                // Defaults remain available even when the invalid payload cannot be moved.
            }
            return
        }
    }

    @Throws(CharacterCodingException::class)
    private fun decodeUtf8Strict(payload: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()

    companion object {
        private const val STORAGE_DIRECTORY_NAME = "state"
        private const val ROUTE_FILE_NAME = "last-route-v1.json"
        private const val MAXIMUM_ROUTE_POINTS = 50_000
        private const val MAXIMUM_ROUTE_FILE_BYTES = 8 * 1024 * 1024
        private const val MAXIMUM_BACKUP_ATTEMPTS = 100
        private const val SAVE_ERROR_MESSAGE = "无法保存最近轨迹，请检查设备存储空间"
        private val RouteJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            encodeDefaults = true
        }
    }
}

private class InvalidRoutePayloadException : Exception()

@Serializable
private data class RoutePayloadDto(
    val schemaVersion: Int,
    val wgs84Points: List<RoutePointPayloadDto>,
)

@Serializable
private data class RoutePointPayloadDto(
    val latitude: Double,
    val longitude: Double,
)
