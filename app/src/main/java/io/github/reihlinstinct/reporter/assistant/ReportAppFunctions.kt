package io.github.reihlinstinct.reporter.assistant

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import io.github.reihlinstinct.reporter.EntryIntents

/** Non-secret local consent. Never reads reporter settings or saved reports. */
class AssistantEntryStore(context: Context) {
    private val prefs = context.getSharedPreferences("assistant-entry", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("enabled", false)
    fun save(enabled: Boolean) { prefs.edit().putBoolean("enabled", enabled).commit() }
}

object AssistantEntry {
    @get:RequiresApi(36)
    val FUNCTION_ID: String get() = ReportAppFunctionService.FUNCTION_ID_START_NEW_REPORT
    @androidx.annotation.ChecksSdkIntAtLeast(api = 36, parameter = 0)
    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 36

    /** The assistant can only offer entry to the normal visible capture flow. */
    fun captureIntent(context: Context): Intent = Intent(context, il.sidewalks.reporter.MainActivity::class.java)
        .setAction(EntryIntents.ACTION_TILE_NEW_REPORT)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun entryAction(context: Context): PendingIntent {
        if (!AssistantEntryStore(context).enabled()) {
            throw AppFunctionAppUnknownException("Assistant entry is disabled in the app settings")
        }
        return PendingIntent.getActivity(context, 149, captureIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val store = AssistantEntryStore(context)
        // Revoke locally first so an in-flight call fails closed, even if indexing lags.
        if (!enabled) store.save(false)
        if (!supported()) return false
        return try {
            val manager = AppFunctionManager.getInstance(context) ?: return false
            manager.setAppFunctionEnabled(FUNCTION_ID, if (enabled)
                AppFunctionManager.APP_FUNCTION_STATE_ENABLED else AppFunctionManager.APP_FUNCTION_STATE_DISABLED)
            store.save(enabled)
            true
        } catch (_: Exception) {
            // Indexing may not be ready on first install. Never show a false success.
            false
        }
    }
}

/** System-mediated entry only. No HTTP listener, report access or submission API. */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(serviceName = "ReportAppFunctionService", appFunctionXmlFileName = "report_app_functions")
abstract class BaseReportAppFunctionService : AppFunctionService() {
    /**
     * Start a new Jerusalem municipal report by showing the app's camera flow.
     * Returns an action to open the app. This never files a report. The person must
     * capture or choose a photo, check the report, and confirm sending in the app.
     * Does not read or return saved reports, identity fields, credentials or photos.
     * @return An immutable action that opens the existing visible report flow.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    fun startNewReport(): PendingIntent = AssistantEntry.entryAction(this)
}
