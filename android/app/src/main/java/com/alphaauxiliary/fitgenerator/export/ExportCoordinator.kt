package com.alphaauxiliary.fitgenerator.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import androidx.core.content.FileProvider
import com.alphaauxiliary.fitgenerator.core.ActivityInput
import com.alphaauxiliary.fitgenerator.core.NativeCoreService
import com.alphaauxiliary.fitgenerator.ui.MainExportCoordinator
import com.alphaauxiliary.fitgenerator.ui.MainExportRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ExportErrorCode {
    INVALID_REQUEST,
    RESOURCE_LIMIT,
    FILE_PERMISSION_DENIED,
    DISK_FULL,
    FILE_WRITE_FAILED,
}

internal class ExportException(
    val code: ExportErrorCode,
    message: String,
) : Exception(message)

internal data class ExportDocumentRequest(
    val requestId: Long,
    val suggestedFileName: String,
    val mimeType: String,
) {
    override fun toString(): String =
        "ExportDocumentRequest(requestId=$requestId, " +
            "suggestedFileName=$suggestedFileName, mimeType=$mimeType)"
}

internal data class ExportShareRequest(
    val requestId: Long,
    val contentUri: String,
    val fileName: String,
    val mimeType: String,
) {
    // A content URI can expose provider implementation details. Keep diagnostics redacted.
    override fun toString(): String =
        "ExportShareRequest(requestId=$requestId, contentUri=<redacted>, " +
            "fileName=$fileName, mimeType=$mimeType)"
}

internal fun interface FitPayloadGenerator {
    suspend fun generate(
        input: ActivityInput,
        seed: ULong,
        variantIndex: Int,
    ): ByteArray
}

internal class NativeFitPayloadGenerator(
    private val service: NativeCoreService,
) : FitPayloadGenerator {
    override suspend fun generate(
        input: ActivityInput,
        seed: ULong,
        variantIndex: Int,
    ): ByteArray = service.generateFit(input, seed, variantIndex)
}

internal fun interface ExportDestinationWriter {
    suspend fun write(destination: String, payload: ByteArray)
}

internal fun interface ExportOutputStreamOpener {
    fun open(destination: String, mode: String): OutputStream?
}

internal fun interface DiskFullDetector {
    fun isDiskFull(error: IOException): Boolean
}

internal object AndroidDiskFullDetector : DiskFullDetector {
    override fun isDiskFull(error: IOException): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is ErrnoException && current.errno == OsConstants.ENOSPC) return true
            current = current.cause
        }
        return false
    }
}

internal class AndroidContentOutputStreamOpener(
    private val contentResolver: ContentResolver,
) : ExportOutputStreamOpener {
    override fun open(destination: String, mode: String): OutputStream? {
        val uri = Uri.parse(destination)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            throw ExportException(
                ExportErrorCode.FILE_PERMISSION_DENIED,
                "所选位置不可写，请重新选择保存位置",
            )
        }
        return contentResolver.openOutputStream(uri, mode)
    }
}

internal class ContentResolverExportDestinationWriter(
    private val outputStreamOpener: ExportOutputStreamOpener,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diskFullDetector: DiskFullDetector = AndroidDiskFullDetector,
) : ExportDestinationWriter {
    constructor(
        contentResolver: ContentResolver,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        outputStreamOpener = AndroidContentOutputStreamOpener(contentResolver),
        dispatcher = dispatcher,
        diskFullDetector = AndroidDiskFullDetector,
    )

    override suspend fun write(destination: String, payload: ByteArray) {
        withContext(dispatcher) {
            try {
                val output = outputStreamOpener.open(destination, WRITE_MODE)
                    ?: throw ExportException(
                        ExportErrorCode.FILE_PERMISSION_DENIED,
                        "无法打开所选位置，请重新选择保存位置",
                    )
                output.use { stream ->
                    stream.write(payload)
                    stream.flush()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ExportException) {
                throw error
            } catch (_: SecurityException) {
                throw ExportException(
                    ExportErrorCode.FILE_PERMISSION_DENIED,
                    "没有写入所选位置的权限，请重新选择保存位置",
                )
            } catch (error: IOException) {
                throw error.toExportException(
                    diskFullMessage = "文件写入失败，请检查设备可用空间",
                    fallbackMessage = "无法写入所选位置，请改选其他位置后重试",
                    diskFullDetector = diskFullDetector,
                )
            }
        }
    }

    private companion object {
        const val WRITE_MODE = "rwt"
    }
}

internal interface ExportShareStore {
    suspend fun write(document: ExportDocument, requestId: Long): ExportShareRequest

    suspend fun deleteStaleExports()
}

internal class CacheExportShareStore(
    context: Context,
    private val authority: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ExportShareStore {
    private val applicationContext = context.applicationContext
    private val exportRoot = File(applicationContext.cacheDir, EXPORT_DIRECTORY_NAME)
    private val operationMutex = Mutex()

    override suspend fun write(
        document: ExportDocument,
        requestId: Long,
    ): ExportShareRequest = withContext(dispatcher) {
        operationMutex.withLock {
            try {
                val canonicalRoot = checkedExportRoot()
                val requestDirectory = checkedChild(canonicalRoot, requestId.toString())
                if (!requestDirectory.isDirectory && !requestDirectory.mkdirs()) {
                    throw IOException("Share directory is unavailable")
                }
                val destination = checkedChild(requestDirectory, document.fileName)
                val temporary = checkedChild(requestDirectory, ".${document.fileName}.tmp")
                if (temporary.exists() && !temporary.delete()) {
                    throw IOException("Stale share temporary file cannot be removed")
                }
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(document.payload)
                        output.fd.sync()
                    }
                    if (destination.exists() && !destination.delete()) {
                        throw IOException("Stale share file cannot be replaced")
                    }
                    if (!temporary.renameTo(destination)) {
                        throw IOException("Share file cannot be finalized")
                    }
                } finally {
                    if (temporary.exists()) temporary.delete()
                }
                val uri = FileProvider.getUriForFile(
                    applicationContext,
                    authority,
                    destination,
                )
                ExportShareRequest(
                    requestId = requestId,
                    contentUri = uri.toString(),
                    fileName = document.fileName,
                    mimeType = document.mimeType,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ExportException) {
                throw error
            } catch (_: SecurityException) {
                throw ExportException(
                    ExportErrorCode.FILE_PERMISSION_DENIED,
                    "无法授权系统分享此文件，请改用保存到文件",
                )
            } catch (error: IOException) {
                throw error.toExportException(
                    diskFullMessage = "无法准备分享文件，请检查设备可用空间",
                    fallbackMessage = "无法准备分享文件，请改用保存到文件",
                    diskFullDetector = AndroidDiskFullDetector,
                )
            } catch (_: IllegalArgumentException) {
                throw ExportException(
                    ExportErrorCode.FILE_WRITE_FAILED,
                    "无法准备系统分享，请改用保存到文件",
                )
            }
        }
    }

    override suspend fun deleteStaleExports() {
        withContext(dispatcher) {
            operationMutex.withLock {
                val canonicalRoot = try {
                    checkedExportRoot()
                } catch (_: IOException) {
                    return@withLock
                } catch (_: SecurityException) {
                    return@withLock
                }
                val children = canonicalRoot.listFiles() ?: return@withLock
                children.forEach requestLoop@{ requestDirectory ->
                    val canonicalRequest = try {
                        requestDirectory.canonicalFile
                    } catch (_: IOException) {
                        return@requestLoop
                    }
                    if (
                        canonicalRequest.parentFile != canonicalRoot ||
                        !canonicalRequest.isDirectory
                    ) {
                        return@requestLoop
                    }
                    canonicalRequest.listFiles()?.forEach fileLoop@{ exportFile ->
                        val canonicalExport = try {
                            exportFile.canonicalFile
                        } catch (_: IOException) {
                            return@fileLoop
                        }
                        if (
                            canonicalExport.parentFile == canonicalRequest &&
                            canonicalExport.isFile
                        ) {
                            canonicalExport.delete()
                        }
                    }
                    canonicalRequest.delete()
                }
                canonicalRoot.delete()
            }
        }
    }

    private fun checkedChild(parent: File, name: String): File {
        val canonicalParent = parent.canonicalFile
        val child = File(canonicalParent, name).canonicalFile
        if (child.parentFile != canonicalParent) {
            throw SecurityException("Export cache path escaped its parent")
        }
        return child
    }

    private fun checkedExportRoot(): File {
        val canonicalCache = applicationContext.cacheDir.canonicalFile
        val canonicalRoot = exportRoot.canonicalFile
        if (canonicalRoot.parentFile != canonicalCache) {
            throw SecurityException("Export cache root escaped the app cache directory")
        }
        return canonicalRoot
    }

    private companion object {
        const val EXPORT_DIRECTORY_NAME = "exports"
    }
}

internal data class ExportDocument(
    val fileName: String,
    val mimeType: String,
    val payload: ByteArray,
)

internal class ExportCoordinator(
    private val generator: FitPayloadGenerator,
    private val destinationWriter: ExportDestinationWriter,
    private val shareStore: ExportShareStore,
    private val maximumTotalFitBytes: Long = DEFAULT_MAXIMUM_TOTAL_FIT_BYTES,
) : MainExportCoordinator {
    constructor(
        context: Context,
        coreService: NativeCoreService,
    ) : this(
        generator = NativeFitPayloadGenerator(coreService),
        destinationWriter = ContentResolverExportDestinationWriter(
            context.applicationContext.contentResolver,
        ),
        shareStore = CacheExportShareStore(
            context = context.applicationContext,
            authority = "${context.applicationContext.packageName}.fileprovider",
        ),
    )

    private val operationMutex = Mutex()
    private val cleanupMutex = Mutex()
    private val pendingLock = Any()
    // Avoid reusing a previously granted FileProvider URI after process restart.
    private val requestIdRandom = SecureRandom()
    private val _pendingDocument = MutableStateFlow<ExportDocumentRequest?>(null)
    override val pendingDocument: StateFlow<ExportDocumentRequest?> =
        _pendingDocument.asStateFlow()
    private var pendingExport: PendingExport? = null
    private var staleCleanupAttempted = false

    init {
        require(maximumTotalFitBytes > 0L)
    }

    override suspend fun export(request: MainExportRequest) {
        operationMutex.withLock {
            val document = buildDocument(request)
            val requestId = newRequestId()
            val destination = CompletableDeferred<DestinationDecision>()
            val pending = PendingExport(
                request = ExportDocumentRequest(
                    requestId = requestId,
                    suggestedFileName = document.fileName,
                    mimeType = document.mimeType,
                ),
                document = document,
                destination = destination,
            )
            synchronized(pendingLock) {
                check(pendingExport == null) { "An export is already awaiting a destination" }
                pendingExport = pending
                _pendingDocument.value = pending.request
            }

            try {
                when (val decision = destination.await()) {
                    DestinationDecision.Cancelled -> Unit
                    DestinationDecision.Shared -> Unit
                    is DestinationDecision.Save -> {
                        destinationWriter.write(decision.destination, document.payload)
                    }
                }
            } finally {
                synchronized(pendingLock) {
                    if (pendingExport === pending) {
                        pendingExport = null
                        _pendingDocument.value = null
                    }
                }
                document.payload.fill(0)
            }
        }
    }

    override fun markDocumentPickerLaunched(requestId: Long): Boolean =
        synchronized(pendingLock) {
            val pending = pendingExport
            if (
                pending == null ||
                pending.request.requestId != requestId ||
                pending.launchStarted ||
                pending.destination.isCompleted
            ) {
                false
            } else {
                pending.launchStarted = true
                true
            }
        }

    override fun completeDocument(requestId: Long, destination: String?): Boolean =
        completeDecision(
            requestId = requestId,
            decision = destination?.let(DestinationDecision::Save)
                ?: DestinationDecision.Cancelled,
        )

    override fun failDocumentLaunch(requestId: Long): Boolean = synchronized(pendingLock) {
        val pending = pendingExport
        if (
            pending == null ||
            pending.request.requestId != requestId ||
            pending.destination.isCompleted
        ) {
            false
        } else {
            pending.destination.completeExceptionally(
                ExportException(
                    ExportErrorCode.FILE_PERMISSION_DENIED,
                    "无法打开系统文件选择器，请重试或使用系统分享",
                ),
            )
        }
    }

    override suspend fun prepareShareFallback(requestId: Long): ExportShareRequest {
        deleteStaleShareExports()
        val snapshot = synchronized(pendingLock) {
            pendingExport?.takeIf {
                it.request.requestId == requestId && !it.destination.isCompleted
            }?.document?.let { document ->
                document.copy(payload = document.payload.copyOf())
            }
        } ?: throw ExportException(
            ExportErrorCode.INVALID_REQUEST,
            "导出内容已失效，请重新生成 FIT",
        )
        return try {
            shareStore.write(snapshot, requestId)
        } finally {
            snapshot.payload.fill(0)
        }
    }

    override fun completeShare(requestId: Long): Boolean =
        completeDecision(requestId, DestinationDecision.Shared)

    override suspend fun deleteStaleShareExports() {
        cleanupMutex.withLock {
            if (staleCleanupAttempted) return@withLock
            staleCleanupAttempted = true
            shareStore.deleteStaleExports()
        }
    }

    private fun completeDecision(
        requestId: Long,
        decision: DestinationDecision,
    ): Boolean = synchronized(pendingLock) {
        val pending = pendingExport
        if (
            pending == null ||
            pending.request.requestId != requestId ||
            pending.destination.isCompleted
        ) {
            false
        } else {
            pending.destination.complete(decision)
        }
    }

    private fun newRequestId(): Long {
        var candidate: Long
        do {
            candidate = requestIdRandom.nextLong() and Long.MAX_VALUE
        } while (candidate == 0L)
        return candidate
    }

    private suspend fun buildDocument(request: MainExportRequest): ExportDocument {
        validateRequest(request)
        val generated = ArrayList<ByteArray>(request.count)
        var totalFitBytes = 0L
        try {
            for (variantIndex in 1..request.count) {
                val payload = generator.generate(
                    input = request.input,
                    seed = request.seed,
                    variantIndex = variantIndex,
                )
                val payloadBytes = payload.size.toLong()
                if (payloadBytes > maximumTotalFitBytes - totalFitBytes) {
                    payload.fill(0)
                    throw ExportException(
                        ExportErrorCode.RESOURCE_LIMIT,
                        "导出批次过大，请减少份数或轨迹点后重试",
                    )
                }
                generated += payload
                totalFitBytes += payloadBytes
            }
            return if (request.count == 1) {
                ExportDocument(
                    fileName = SINGLE_FILE_NAME,
                    mimeType = FIT_MIME_TYPE,
                    payload = generated.removeAt(0),
                )
            } else {
                ExportDocument(
                    fileName = BATCH_FILE_NAME,
                    mimeType = ZIP_MIME_TYPE,
                    payload = zipPayloads(generated),
                )
            }
        } finally {
            generated.forEach { payload -> payload.fill(0) }
        }
    }

    private fun validateRequest(request: MainExportRequest) {
        if (
            request.count !in MINIMUM_EXPORT_COUNT..MAXIMUM_EXPORT_COUNT ||
            request.model.seed != request.seed
        ) {
            throw ExportException(
                ExportErrorCode.INVALID_REQUEST,
                "导出参数与当前预览不一致，请重新预览后再生成 FIT",
            )
        }
    }

    private fun zipPayloads(payloads: List<ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            payloads.forEachIndexed { index, payload ->
                val entry = ZipEntry("run_${index + 1}.fit").apply {
                    time = DETERMINISTIC_ZIP_TIMESTAMP_MILLIS
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(payload) }.value
                    extra = null
                    comment = null
                }
                zip.putNextEntry(entry)
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private data class PendingExport(
        val request: ExportDocumentRequest,
        val document: ExportDocument,
        val destination: CompletableDeferred<DestinationDecision>,
        var launchStarted: Boolean = false,
    )

    private sealed interface DestinationDecision {
        data object Cancelled : DestinationDecision

        data object Shared : DestinationDecision

        data class Save(val destination: String) : DestinationDecision
    }

    companion object {
        const val FIT_MIME_TYPE = "application/vnd.ant.fit"
        const val ZIP_MIME_TYPE = "application/zip"
        const val SINGLE_FILE_NAME = "run.fit"
        const val BATCH_FILE_NAME = "runs.zip"
        private const val MINIMUM_EXPORT_COUNT = 1
        private const val MAXIMUM_EXPORT_COUNT = 20
        private const val DEFAULT_MAXIMUM_TOTAL_FIT_BYTES = 32L * 1024L * 1024L
        private const val DETERMINISTIC_ZIP_TIMESTAMP_MILLIS = 0L
    }
}

private fun IOException.toExportException(
    diskFullMessage: String,
    fallbackMessage: String,
    diskFullDetector: DiskFullDetector,
): ExportException {
    if (diskFullDetector.isDiskFull(this)) {
        return ExportException(ExportErrorCode.DISK_FULL, diskFullMessage)
    }
    return ExportException(ExportErrorCode.FILE_WRITE_FAILED, fallbackMessage)
}
