package io.github.reihlinstinct.reporter

import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.junit.Assume.assumeTrue
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.assistant.AssistantEntry
import io.github.reihlinstinct.reporter.assistant.AssistantEntryStore
import org.junit.Assert.*
import org.junit.Test

class AssistantEntryTest {
    @Test fun disabledByDefaultAndOnlyOffersImmutableVisibleCaptureAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AssistantEntryStore(context)
        store.save(false)
        assertFalse(store.enabled())
        if (Build.VERSION.SDK_INT < 36) {
            assertFalse(AssistantEntry.supported())
            return
        }
        try {
            try {
                AssistantEntry.entryAction(context)
                fail("Disabled entry must reject calls")
            } catch (_: AppFunctionAppUnknownException) { }
            store.save(true)
            val action = AssistantEntry.entryAction(context)
            assertTrue(action.isImmutable)
            assertEquals("il.sidewalks.reporter", action.creatorPackage)
            val intent = AssistantEntry.captureIntent(context)
            assertEquals("il.sidewalks.reporter.MainActivity", intent.component!!.className)
            assertEquals(EntryIntents.ACTION_TILE_NEW_REPORT, intent.action)
            assertTrue(intent.extras == null || intent.extras!!.isEmpty)
            store.save(false)
            try {
                AssistantEntry.entryAction(context)
                fail("Revoked entry must reject calls")
            } catch (_: AppFunctionAppUnknownException) { }
        } finally {
            store.save(false)
        }
    }

    @Test fun systemManagerExecutesGeneratedFunctionWithoutFiling() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 36)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AppFunctionManager.getInstance(context)
        assertNotNull("API36 AppFunctions manager", manager)
        val store = AssistantEntryStore(context)
        try {
            // Wait for initial package indexing only, not a failed test or app assertion.
            var indexed = false
            for (attempt in 0 until 15) {
                if (AssistantEntry.setEnabled(context, true)) { indexed = true; break }
                delay(2000)
            }
            assertTrue("AppFunction must become indexed within 30 seconds", indexed)
            val request = ExecuteAppFunctionRequest(context.packageName, AssistantEntry.FUNCTION_ID, AppFunctionData.EMPTY)
            // Stock API36 legacy index has no custom-function metadata for Jetpack's caller
            // reader. Invoke the REAL platform binder, not a service method or a mock.
            val platform = context.getSystemService(android.app.appfunctions.AppFunctionManager::class.java)
            suspend fun invoke(): Result<android.app.appfunctions.ExecuteAppFunctionResponse> = suspendCancellableCoroutine { cont ->
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                platform.executeAppFunction(request.toPlatformExecuteAppFunctionRequest(), Runnable::run, signal,
                    object : android.os.OutcomeReceiver<android.app.appfunctions.ExecuteAppFunctionResponse, android.app.appfunctions.AppFunctionException> {
                        override fun onResult(value: android.app.appfunctions.ExecuteAppFunctionResponse) { cont.resume(Result.success(value)) }
                        override fun onError(error: android.app.appfunctions.AppFunctionException) { cont.resume(Result.failure(error)) }
                    })
            }
            val response = invoke()
            assertTrue("Actual platform system invocation must succeed: $response", response.isSuccess)
            // rc01 AppFunctionData parcelable encoding is property/<return-key> in extras.
            val action = response.getOrThrow().extras.getParcelable(
                "property/" + ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE, PendingIntent::class.java)
            assertNotNull("System must return an action, never submit", action)
            assertTrue(action!!.isImmutable)
            assertEquals(context.packageName, action.creatorPackage)
            // Do not send the action: this proof never captures a photo or files a report.
            assertTrue(AssistantEntry.setEnabled(context, false))
            val disabled = invoke()
            assertTrue("Actual system must reject after revocation", disabled.isFailure)
        } finally {
            store.save(false)
            AssistantEntry.setEnabled(context, false)
        }
    }

    @Test fun generatedServiceIsSystemProtectedAndMetadataStartsDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS).services.orEmpty()
        val service = services.single { it.name.endsWith(".assistant.ReportAppFunctionService") }
        assertEquals("android.permission.BIND_APP_FUNCTION_SERVICE", service.permission)
        assertTrue(service.exported)
        assertEquals(Build.VERSION.SDK_INT >= 36, service.enabled)
        val xml = context.assets.open("report_app_functions.xml").bufferedReader().use { it.readText() }
        assertTrue(xml.contains("startNewReport"))
        if (Build.VERSION.SDK_INT >= 36) {
            assertTrue("Use generated function identity, not a guessed name", xml.contains(AssistantEntry.FUNCTION_ID))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            assertNotNull(context.packageManager.getProperty("android.app.appfunctions.app_metadata", context.packageName))
        }
        val legacy = context.assets.open("report_app_functions-v1.xml").bufferedReader().use { it.readText() }
        assertTrue("API36 legacy index asset must contain our function", legacy.contains("startNewReport"))
        assertTrue(xml.contains("false"))
        assertFalse(xml.contains("submitReport"))
        assertFalse(xml.contains("password"))
    }
}
