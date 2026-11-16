package io.github.reihlinstinct.reporter

import android.app.KeyguardManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Makes an ephemeral CI emulator ready before a flow starts: screen awake, keyguard gone, no system "not
 * responding" dialog on top. Run 14 of the live workflow stopped on the lock screen (the activity was paused and
 * stopped right after launch, so Compose found no hierarchy) and its recording also showed a launcher ANR dialog.
 *
 * The PIN is the throwaway credential of the ephemeral emulator (`adb shell locksettings set-pin`, set by the
 * live workflow); it protects nothing and is not a secret. The app's own submit gate is untouched.
 * Failure is loud and fixed-text: it never prints window contents.
 */
object DeviceReady {
    private const val POLL_MS = 400L

    private fun shell(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    fun keyguardLocked(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** Presses "Wait" on a system not-responding dialog if one is in front. True when one was dismissed. */
    private fun dismissNotResponding(): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = automation.rootInActiveWindow ?: return false
        try {
            if (root.findAccessibilityNodeInfosByText("isn't responding").isNullOrEmpty()) return false
            val wait = root.findAccessibilityNodeInfosByText("Wait").firstOrNull { it.isClickable } ?: return false
            return wait.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            root.recycle()
        }
    }

    /** True when the device is ready within [timeoutMs]; [pin] is tried only if a plain dismiss does not clear the keyguard. */
    fun ensureReady(context: Context, pin: String?, timeoutMs: Long = 45_000): Boolean {
        require(pin == null || pin.matches(Regex("[0-9]{4,16}"))) { "pin must be digits" }
        val deadline = System.currentTimeMillis() + timeoutMs
        var pinTried = false
        var calm = 0
        while (System.currentTimeMillis() < deadline) {
            shell("input keyevent KEYCODE_WAKEUP")
            if (dismissNotResponding()) { calm = 0; Thread.sleep(POLL_MS); continue }
            if (keyguardLocked(context)) {
                calm = 0
                shell("wm dismiss-keyguard")
                Thread.sleep(POLL_MS)
                if (keyguardLocked(context) && pin != null && !pinTried) {
                    shell("input swipe 540 1600 540 600 200")
                    Thread.sleep(POLL_MS)
                    shell("input text $pin")
                    shell("input keyevent 66")
                    pinTried = true
                }
            } else {
                // Require a short quiet stretch so a dialog that appears right after unlock is caught too.
                calm += 1
                if (calm >= 3) return true
            }
            Thread.sleep(POLL_MS)
        }
        return false
    }
}
