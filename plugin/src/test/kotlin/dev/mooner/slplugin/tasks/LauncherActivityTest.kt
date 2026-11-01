package dev.mooner.slplugin.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LauncherActivityTest {

    @Test
    fun `prefers the app's own activity over a library launcher`() {
        // Output of a debug StarLight build on Android 11, where LeakCanary adds a second launcher activity.
        val output = """
            2 activities found:
              Activity #0:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                dev.mooner.starlight/leakcanary.internal.activity.LeakLauncherActivity
              Activity #1:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                dev.mooner.starlight/.ui.splash.SplashActivity
        """.trimIndent().replace("\n", "\r\n")

        assertEquals("dev.mooner.starlight/.ui.splash.SplashActivity", pickLauncherActivity(output, "dev.mooner.starlight"))
    }

    @Test
    fun `falls back to the first activity when none is in the app's namespace`() {
        val output = """
            1 activities found:
              Activity #0:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                dev.mooner.starlight.debug/dev.mooner.starlight.ui.splash.SplashActivity
        """.trimIndent()

        assertEquals(
            "dev.mooner.starlight.debug/dev.mooner.starlight.ui.splash.SplashActivity",
            pickLauncherActivity(output, "dev.mooner.starlight.debug"),
        )
    }

    @Test
    fun `returns null when the app is not installed`() {
        assertNull(pickLauncherActivity("No activities found\n", "dev.mooner.starlight"))
    }
}
