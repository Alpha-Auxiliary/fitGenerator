package com.alphaauxiliary.fitgenerator.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class NativeCoreService(
    private val gateway: NativeCoreGateway = JniNativeCoreGateway,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val apiVersionStatus by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        readApiVersionStatus()
    }

    suspend fun preview(input: ActivityInput, seed: ULong): ActivityModelDto =
        withContext(dispatcher) {
            ensureCompatibleApi()
            val request = encodeRequest(input, seed, PREVIEW_VARIANT_INDEX)
            val payload = invokeByteOperation("无法调用 Rust 核心生成活动预览") {
                gateway.preview(request)
            }
            decodePreview(payload, seed)
        }

    suspend fun generateFit(
        input: ActivityInput,
        seed: ULong,
        variantIndex: Int,
    ): ByteArray = withContext(dispatcher) {
        ensureCompatibleApi()
        val request = encodeRequest(input, seed, variantIndex)
        val payload = invokeByteOperation("无法调用 Rust 核心生成 FIT 文件") {
            gateway.generateFit(request)
        }
        if (!hasFitSignature(payload)) {
            throw invalidNativePayload("Rust 核心返回的 FIT 数据无效")
        }
        payload
    }

    private fun readApiVersionStatus(): ApiVersionStatus =
        try {
            val actualVersion = gateway.apiVersion()
            if (actualVersion == SUPPORTED_API_VERSION) {
                ApiVersionStatus.Compatible
            } else {
                ApiVersionStatus.Failed(
                    code = NATIVE_CORE_VERSION_MISMATCH,
                    message =
                        "Rust 核心 API 版本不兼容：需要 $SUPPORTED_API_VERSION，实际 $actualVersion",
                )
            }
        } catch (_: LinkageError) {
            ApiVersionStatus.Failed(
                code = NATIVE_CORE_UNAVAILABLE,
                message = "无法加载 Rust 核心，请确认应用包含当前设备架构的原生库",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ApiVersionStatus.Failed(
                code = NATIVE_CORE_UNAVAILABLE,
                message = "无法读取 Rust 核心 API 版本",
            )
        }

    private fun ensureCompatibleApi() {
        when (val status = apiVersionStatus) {
            ApiVersionStatus.Compatible -> Unit
            is ApiVersionStatus.Failed -> throw CoreException(status.code, status.message)
        }
    }

    private fun encodeRequest(
        input: ActivityInput,
        seed: ULong,
        variantIndex: Int,
    ): ByteArray {
        return try {
            val request = CoreRequestDto(
                schemaVersion = SUPPORTED_SCHEMA_VERSION,
                startTimeUtc = toUtcString(input.startTime),
                points = input.routePoints.map { point ->
                    GeoPointDto(lat = point.latitude, lng = point.longitude)
                },
                paceSecondsPerKm = input.paceSecondsPerKilometer,
                hrRest = input.restingHeartRate,
                hrMax = input.maximumHeartRate,
                lapCount = input.lapCount,
                variantIndex = variantIndex,
                seed = seed,
                routeMode = ROUTE_MODE,
            )
            StrictCoreJson.encodeToString(request).toByteArray(Charsets.UTF_8)
        } catch (_: SerializationException) {
            throw CoreException(INVALID_INPUT, "活动参数无法转换为 Rust 核心请求")
        } catch (_: IllegalArgumentException) {
            throw CoreException(INVALID_INPUT, "活动参数无法转换为 Rust 核心请求")
        }
    }

    private fun invokeByteOperation(
        failureMessage: String,
        operation: () -> ByteArray,
    ): ByteArray {
        val payload = try {
            operation()
        } catch (_: LinkageError) {
            throw CoreException(NATIVE_CORE_UNAVAILABLE, failureMessage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw CoreException(NATIVE_CORE_UNAVAILABLE, failureMessage)
        }

        if (payload.isEmpty()) {
            throw readCoreError()
        }
        return payload
    }

    private fun readCoreError(): CoreException {
        val rawError = try {
            gateway.lastError()
        } catch (_: LinkageError) {
            return CoreException(NATIVE_CORE_UNAVAILABLE, "无法读取 Rust 核心错误")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return CoreException(NATIVE_CORE_UNAVAILABLE, "无法读取 Rust 核心错误")
        }

        val error = try {
            StrictCoreJson.decodeFromString<CoreErrorDto>(rawError)
        } catch (_: SerializationException) {
            return invalidNativePayload("Rust 核心返回的错误数据无效")
        } catch (_: IllegalArgumentException) {
            return invalidNativePayload("Rust 核心返回的错误数据无效")
        }

        val code = when (error.code) {
            100 -> INVALID_INPUT
            101 -> UNSUPPORTED_SCHEMA
            102 -> RESOURCE_LIMIT
            200 -> SIMULATION_FAILED
            300 -> FIT_ENCODING_FAILED
            900 -> INTERNAL_ERROR
            else -> INTERNAL_ERROR
        }
        if (error.message.isEmpty()) {
            return invalidNativePayload("Rust 核心返回的错误消息为空")
        }
        return CoreException(code, error.message, nativeCode = error.code)
    }

    private fun decodePreview(payload: ByteArray, expectedSeed: ULong): ActivityModelDto {
        val model = try {
            StrictCoreJson.decodeFromString<ActivityModelDto>(payload.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw invalidNativePayload("Rust 核心返回的活动预览数据无效")
        } catch (_: IllegalArgumentException) {
            throw invalidNativePayload("Rust 核心返回的活动预览数据无效")
        }

        if (model.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw CoreException(
                NATIVE_CORE_VERSION_MISMATCH,
                "Rust 核心数据版本不兼容：需要 $SUPPORTED_SCHEMA_VERSION，实际 ${model.schemaVersion}",
            )
        }
        if (model.algorithmVersion != SUPPORTED_ALGORITHM_VERSION) {
            throw CoreException(
                NATIVE_CORE_VERSION_MISMATCH,
                "Rust 核心算法版本不兼容：需要 $SUPPORTED_ALGORITHM_VERSION，实际 ${model.algorithmVersion}",
            )
        }
        if (
            model.startTimeUtc.isBlank() ||
            model.seed != expectedSeed ||
            model.laps.isEmpty() ||
            model.samples.isEmpty()
        ) {
            throw invalidNativePayload("Rust 核心返回的活动预览数据不完整")
        }
        return model
    }

    private fun toUtcString(startTime: Calendar): String {
        val localCopy = startTime.clone() as Calendar
        val formatter = SimpleDateFormat(UTC_TIMESTAMP_PATTERN, Locale.ROOT).apply {
            timeZone = UTC_TIME_ZONE
        }
        return formatter.format(Date(localCopy.timeInMillis))
    }

    private fun hasFitSignature(payload: ByteArray): Boolean =
        payload.size >= FIT_SIGNATURE_END &&
            payload[8] == '.'.code.toByte() &&
            payload[9] == 'F'.code.toByte() &&
            payload[10] == 'I'.code.toByte() &&
            payload[11] == 'T'.code.toByte()

    private fun invalidNativePayload(message: String): CoreException =
        CoreException(INTERNAL_ERROR, message, nativeCode = 900)

    private sealed interface ApiVersionStatus {
        data object Compatible : ApiVersionStatus

        data class Failed(
            val code: String,
            val message: String,
        ) : ApiVersionStatus
    }

    private companion object {
        const val SUPPORTED_API_VERSION = 1
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val SUPPORTED_ALGORITHM_VERSION = 1
        const val PREVIEW_VARIANT_INDEX = 1
        const val FIT_SIGNATURE_END = 12
        const val ROUTE_MODE = "close_if_needed"
        const val UTC_TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

        const val INVALID_INPUT = "invalid_input"
        const val UNSUPPORTED_SCHEMA = "unsupported_schema"
        const val RESOURCE_LIMIT = "resource_limit"
        const val SIMULATION_FAILED = "simulation_failed"
        const val FIT_ENCODING_FAILED = "fit_encoding_failed"
        const val INTERNAL_ERROR = "internal_error"
        const val NATIVE_CORE_UNAVAILABLE = "native_core_unavailable"
        const val NATIVE_CORE_VERSION_MISMATCH = "native_core_version_mismatch"

        val UTC_TIME_ZONE: TimeZone = TimeZone.getTimeZone("UTC")
    }
}
