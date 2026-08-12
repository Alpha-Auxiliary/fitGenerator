package com.alphaauxiliary.fitgenerator.core

internal object NativeCore {
    init {
        System.loadLibrary("fit_generator_core")
    }

    external fun nativeApiVersion(): Int

    external fun nativePreview(requestUtf8: ByteArray): ByteArray

    external fun nativeGenerateFit(requestUtf8: ByteArray): ByteArray

    external fun nativeLastError(): String
}

internal interface NativeCoreGateway {
    fun apiVersion(): Int

    fun preview(requestUtf8: ByteArray): ByteArray

    fun generateFit(requestUtf8: ByteArray): ByteArray

    fun lastError(): String
}

internal object JniNativeCoreGateway : NativeCoreGateway {
    override fun apiVersion(): Int = NativeCore.nativeApiVersion()

    override fun preview(requestUtf8: ByteArray): ByteArray =
        NativeCore.nativePreview(requestUtf8)

    override fun generateFit(requestUtf8: ByteArray): ByteArray =
        NativeCore.nativeGenerateFit(requestUtf8)

    override fun lastError(): String = NativeCore.nativeLastError()
}
