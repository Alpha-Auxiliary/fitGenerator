package com.alphaauxiliary.fitgenerator

import org.junit.Assert.assertFalse
import org.junit.Test

class AppLaunchContractTest {
    @Test
    fun `the app has no configured web app url`() {
        val fields = BuildConfig::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("WEB_APP_URL"))
    }
}
