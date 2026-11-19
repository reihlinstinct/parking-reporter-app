package io.github.reihlinstinct.reporter

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.notification.RunListener
import java.io.File

/** Opt-in synthetic-CI telemetry, never installed in the production APK. Issue #684. */
class MemoryTraceListener : RunListener() {
    private var ordinal = 0
    private val enabled get() = InstrumentationRegistry.getArguments().getString("memoryTrace") == "1"

    override fun testStarted(description: Description) {
        if (!enabled) return
        ordinal++
        record("before", description)
        // A separate perturbed run only. Heap dumps can force GC and change timing.
        if (InstrumentationRegistry.getArguments().getString("memorySnapshots") == "1" && ordinal in setOf(24, 27)) {
            val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "memory-diagnostic")
            check(root.isDirectory || root.mkdirs())
            try {
                Debug.dumpHprofData(File(root, "before-$ordinal.hprof").absolutePath)
                record("snapshot-complete", description)
            } catch (failure: Exception) {
                // Diagnostic failure is visible and the workflow requires both snapshots.
                Log.e("API29_MEMORY", "snapshot-failed-$ordinal:${failure.javaClass.simpleName}")
            }
        }
    }

    override fun testFinished(description: Description) {
        if (enabled) record("after", description)
    }

    private fun record(phase: String, description: Description) {
        val runtime = Runtime.getRuntime()
        val record = JSONObject()
            .put("phase", phase).put("ordinal", ordinal)
            .put("class", description.className).put("test", description.methodName)
            .put("elapsedMs", SystemClock.elapsedRealtime())
            .put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            .put("javaCommittedBytes", runtime.totalMemory()).put("javaMaxBytes", runtime.maxMemory())
            .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        Log.i("API29_MEMORY", record.toString())
    }
}
