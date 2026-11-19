package io.github.reihlinstinct.reporter

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Test

/** Test-APK-only metadata, no activities, addresses, registry or municipal calls. */
class CiHeapBudgetProbeTest {
    @Test fun recordActualProcessBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = JSONObject()
            .put("pid", Process.myPid())
            .put("javaMaxBytes", Runtime.getRuntime().maxMemory())
        File(context.filesDir, "ci-heap-probe.json").writeText(output.toString())
    }
}
