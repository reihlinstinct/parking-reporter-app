package io.github.reihlinstinct.reporter.security

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.reihlinstinct.reporter.BuildConfig
import java.util.concurrent.Executors

/** Process-wide ownership, never Activity/Bundle state. Activation is deliberately hard-off until reviewed
 * copy, credential/result routing, ledger validation and external process-kill proof are complete.
 * Disabled means no executor, timer, file, key, startup sweep or lifecycle receiver is created. */
object ReviewRecoveryOwner {
    const val ENABLED = false
    @Volatile var controller: ReviewRecoveryController? = null
        private set

    @Synchronized fun install(context: Context) {
        if (!ENABLED || controller != null) return
        val app = context.applicationContext
        val io = Executors.newSingleThreadExecutor { task -> Thread(task, "review-recovery-io") }
        val handler = Handler(Looper.getMainLooper())
        val store = ReviewRecoveryStore(app.noBackupFilesDir)
        val elapsed = { ReviewRecoveryPlatform.elapsedMs() }
        val boot = { ReviewRecoveryPlatform.bootCount(app) }
        val unlocked = { ReviewRecoveryPlatform.deviceUnlocked(app) }
        val writer = ReviewRecoveryWriter(store, elapsed, boot, { BuildConfig.VERSION_CODE }, unlocked)
        val owner = ReviewRecoveryController({ ENABLED }, { ReviewRecoveryPlatform.deviceSecure(app) },
            unlocked, store, AndroidReviewRecoveryKeys(), writer, ReviewRecoveryAuthRequest(elapsed),
            elapsed, boot, { BuildConfig.VERSION_CODE }, io = { io.execute(it) },
            schedule = { delay, task -> handler.postDelayed(task, delay); { handler.removeCallbacks(task) } })
        controller = owner
        ReviewRecoveryPlatform.attach(app, owner)
        io.execute { owner.startupSweep() }
    }
}

class ReporterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReviewRecoveryOwner.install(this)
    }
}
