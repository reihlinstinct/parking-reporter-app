package io.github.reihlinstinct.reporter.security

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** UNCOMPILED, inactive (#358 slice 3). Platform adapters only. Needs `androidx.lifecycle:lifecycle-process`
 * (not currently a dependency; same version as the other lifecycle artifacts). No Activity state, no Bundle. */
object ReviewRecoveryPlatform {
    fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    /** Null (fail closed) when the boot counter is unreadable. */
    fun bootCount(context: Context): Int? =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
    fun deviceSecure(context: Context) = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    fun deviceUnlocked(context: Context) = !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked

    /** Process-foreground ON_STOP (rotation is absorbed by the ~700 ms stop delay, and a
     * configuration-change handoff keeps the session) plus runtime ACTION_SCREEN_OFF. Register once per process
     * from the Application, never per Activity. */
    fun attach(context: Context, controller: ReviewRecoveryController) {
        val app = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = controller.onProcessStop()
        })
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { controller.revoke() }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
}
