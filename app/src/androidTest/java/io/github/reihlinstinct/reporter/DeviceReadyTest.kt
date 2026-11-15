package io.github.reihlinstinct.reporter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dry check for [DeviceReady], no city contact: lock the emulator with a throwaway PIN, put the screen to sleep,
 * and require [DeviceReady.ensureReady] to bring it back with the keyguard gone. The PIN is always cleared again
 * so no other test sees a locked device. Skipped (with a printed note) when the emulator refuses to set a PIN.
 */
class DeviceReadyTest {
    private val pin = "131313"

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { String(it.readBytes()) }
    }

    @Test fun lockedAndSleepingDeviceIsBroughtBack() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val set = shell("locksettings set-pin $pin")
        if (!set.contains("set to")) { println("DEVICE_READY_DRYCHECK skipped: emulator did not accept a PIN"); return }
        try {
            shell("input keyevent KEYCODE_SLEEP")
            // API 36 can take longer than 1.5 s to raise the keyguard after sleep (#444): poll up to 15 s.
            val until = System.currentTimeMillis() + 15_000
            while (!DeviceReady.keyguardLocked(context) && System.currentTimeMillis() < until) Thread.sleep(500)
            assertTrue("keyguard should be up after sleep with a PIN set", DeviceReady.keyguardLocked(context))
            val ready = DeviceReady.ensureReady(context, pin)
            println("DEVICE_READY_DRYCHECK ready=$ready locked=${DeviceReady.keyguardLocked(context)}")
            assertTrue("device was not made ready", ready)
            assertFalse(DeviceReady.keyguardLocked(context))
        } finally {
            shell("locksettings clear --old $pin")
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            // A failed check must not leave a locked or sleeping device for the tests that run after it (the
            // "no compose hierarchies" cascade seen on API 36, #444). The PIN is already cleared here.
            DeviceReady.ensureReady(context, null)
        }
    }
}
