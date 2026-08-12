package com.alphaauxiliary.fitgenerator.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCoreInstrumentedTest {
    @Test
    fun nativeCoreLoadsAndGeneratesPreviewAndFit() {
        assertEquals(1, NativeCore.nativeApiVersion())

        val failedPreview = NativeCore.nativePreview(ByteArray(0))
        assertTrue("无效 JNI 请求应返回空数组", failedPreview.isEmpty())
        val structuredError = StrictCoreJson.decodeFromString<CoreErrorDto>(
            NativeCore.nativeLastError(),
        )
        assertEquals(100, structuredError.code)
        assertTrue(structuredError.message.isNotBlank())

        val request = REQUEST_JSON.toByteArray(Charsets.UTF_8)
        val preview = NativeCore.nativePreview(request)
        assertTrue("Rust 核心应返回非空预览", preview.isNotEmpty())
        assertEquals("", NativeCore.nativeLastError())
        val model = StrictCoreJson.decodeFromString<ActivityModelDto>(
            preview.toString(Charsets.UTF_8),
        )
        assertTrue("Rust 核心预览应包含采样点", model.samples.isNotEmpty())

        val fit = NativeCore.nativeGenerateFit(request)
        assertTrue("FIT 数据必须至少包含 12 字节文件头", fit.size >= 12)
        assertEquals('.', fit[8].toInt().toChar())
        assertEquals('F', fit[9].toInt().toChar())
        assertEquals('I', fit[10].toInt().toChar())
        assertEquals('T', fit[11].toInt().toChar())
    }

    private companion object {
        const val REQUEST_JSON =
            "{\"schemaVersion\":1," +
                "\"startTimeUtc\":\"2026-07-27T08:00:00Z\"," +
                "\"points\":[{" +
                "\"lat\":39.9042,\"lng\":116.4074},{" +
                "\"lat\":39.9052,\"lng\":116.4084}]," +
                "\"paceSecondsPerKm\":360.0," +
                "\"hrRest\":60,\"hrMax\":180," +
                "\"lapCount\":1,\"variantIndex\":1," +
                "\"seed\":42,\"routeMode\":\"close_if_needed\"}"
    }
}
