package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app metadata and resources match Samplfie2`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Samplfie2", appName)
        assertTrue(context.resources.getResourceName(R.font.space_grotesk).contains("space_grotesk"))
        assertTrue(context.resources.getResourceName(R.font.jetbrains_mono).contains("jetbrains_mono"))
        assertTrue(context.resources.getResourceName(R.font.inter).contains("inter"))
    }
}
