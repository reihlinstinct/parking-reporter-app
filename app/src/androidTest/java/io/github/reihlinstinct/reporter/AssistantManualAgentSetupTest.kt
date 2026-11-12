package io.github.reihlinstinct.reporter

import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.assistant.AssistantEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** CI-only fresh-emulator setup for the official manual agent; never files or sends an action. */
class AssistantManualAgentSetupTest {
    @Test fun enableForOfficialManualAgent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var indexed = false
        repeat(15) {
            if (!indexed) {
                indexed = AssistantEntry.setEnabled(context, true)
                if (!indexed) delay(2000)
            }
        }
        java.io.File(context.filesDir, "manual-agent-function-id.txt").writeText(AssistantEntry.FUNCTION_ID)
        assertTrue("Function must be indexed and explicitly enabled on this fresh test device", indexed)
    }
    @Test fun revokeAfterOfficialManualAgent() = runBlocking {
        assertTrue(AssistantEntry.setEnabled(InstrumentationRegistry.getInstrumentation().targetContext, false))
    }
}
