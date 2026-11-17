package io.github.reihlinstinct.reporter

import android.app.PendingIntent
import android.os.Build
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.assistant.AssistantEntry
import io.github.reihlinstinct.reporter.assistant.AssistantEntryStore
import io.github.reihlinstinct.reporter.core.ReportCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** #190 typed dispatch proof. Real platform binder, no action launch or municipal calls. */
class AssistantCategoryBinderTest {
    @Test fun generatedCategoryFunctionValidatesParametersAndRejectsRevocationThroughSystem() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 36)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AssistantEntryStore(context)
        val platform = context.getSystemService(android.app.appfunctions.AppFunctionManager::class.java)
        assertNotNull(platform)
        fun parameters(code: String): AppFunctionData = AppFunctionData.Builder(
            listOf(AppFunctionParameterMetadata("categoryCode", true, AppFunctionStringTypeMetadata(false))),
            AppFunctionComponentsMetadata(),
        ).setString("categoryCode", code).build()

        suspend fun invoke(data: AppFunctionData): Result<android.app.appfunctions.ExecuteAppFunctionResponse> =
            withTimeout(15_000) {
                val request = ExecuteAppFunctionRequest(context.packageName, AssistantEntry.FUNCTION_ID_CATEGORY, data)
                suspendCancellableCoroutine { continuation ->
                    val signal = android.os.CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    platform.executeAppFunction(request.toPlatformExecuteAppFunctionRequest(), Runnable::run, signal,
                        object : android.os.OutcomeReceiver<android.app.appfunctions.ExecuteAppFunctionResponse, android.app.appfunctions.AppFunctionException> {
                            override fun onResult(value: android.app.appfunctions.ExecuteAppFunctionResponse) {
                                continuation.resume(Result.success(value))
                            }
                            override fun onError(error: android.app.appfunctions.AppFunctionException) {
                                continuation.resume(Result.failure(error))
                            }
                        })
                }
            }

        try {
            // Bounded initial package indexing only, never a retry of failed execution assertions.
            var indexed = false
            var attemptsRemaining = 15
            while (!indexed && attemptsRemaining > 0) {
                attemptsRemaining--
                indexed = AssistantEntry.setEnabled(context, true)
                if (!indexed) delay(2000)
            }
            assertTrue("Generated category function must be indexed", indexed)
            for (category in ReportCategory.entries) {
                val response = invoke(parameters(category.code))
                assertTrue("Typed system dispatch must accept a known category: $response", response.isSuccess)
                val action = response.getOrThrow().extras.getParcelable(
                    "property/" + ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE, PendingIntent::class.java)
                assertNotNull("Typed function returns only an action", action)
                assertTrue(action!!.isImmutable)
                assertEquals(context.packageName, action.creatorPackage)
                assertTrue(action.isActivity)
                // Never send this PendingIntent: capture, review and filing are out of scope.
            }
            for (data in listOf(parameters("not_a_category"), AppFunctionData.EMPTY)) {
                val rejected = invoke(data)
                assertTrue("Unknown or missing category must be rejected by generated dispatch", rejected.isFailure)
                val error = rejected.exceptionOrNull()
                assertTrue("Rejection must be a typed platform error", error is android.app.appfunctions.AppFunctionException)
                assertEquals(android.app.appfunctions.AppFunctionException.ERROR_INVALID_ARGUMENT,
                    (error as android.app.appfunctions.AppFunctionException).errorCode)
            }
            assertTrue("Revocation must disable both functions", AssistantEntry.setEnabled(context, false))
            assertFalse(store.enabled())
            val revoked = invoke(parameters(ReportCategory.entries.first().code))
            assertTrue("Valid category must be rejected after revocation", revoked.isFailure)
            val error = revoked.exceptionOrNull()
            assertTrue("Revocation must be a typed platform error", error is android.app.appfunctions.AppFunctionException)
            assertEquals(android.app.appfunctions.AppFunctionException.ERROR_DISABLED,
                (error as android.app.appfunctions.AppFunctionException).errorCode)
        } finally {
            store.save(false)
            assertTrue("System cleanup must revoke consent", AssistantEntry.setEnabled(context, false))
        }
    }
}
