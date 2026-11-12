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
import androidx.appfunctions.AppFunctionInvalidArgumentException
import io.github.reihlinstinct.reporter.EntryIntents
import io.github.reihlinstinct.reporter.core.ReportCategory

/** Non-secret local consent. Never reads reporter settings or saved reports. */
class AssistantEntryStore(context: Context) {
    private val prefs = context.getSharedPreferences("assistant-entry", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("enabled", false)
    fun save(enabled: Boolean) { prefs.edit().putBoolean("enabled", enabled).commit() }
}

object AssistantEntry {
    @get:RequiresApi(36)
    val FUNCTION_ID: String get() = ReportAppFunctionService.FUNCTION_ID_START_NEW_REPORT
    @get:RequiresApi(36)
    val FUNCTION_ID_CATEGORY: String get() = ReportAppFunctionService.FUNCTION_ID_START_NEW_REPORT_IN_CATEGORY
    @androidx.annotation.ChecksSdkIntAtLeast(api = 36, parameter = 0)
    fun supported(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 36

    /** The assistant can only offer entry to the normal visible capture flow. */
    fun captureIntent(context: Context, category: ReportCategory? = null): Intent = Intent(context, il.sidewalks.reporter.MainActivity::class.java)
        .setAction(EntryIntents.ACTION_TILE_NEW_REPORT)
        .apply { if (category != null) setData(android.net.Uri.parse(EntryIntents.categoryLink(category.code))) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Same consent gate as [entryAction]. Unknown codes are rejected, never guessed. */
    fun categoryEntryAction(context: Context, categoryCode: String): PendingIntent {
        if (!AssistantEntryStore(context).enabled()) {
            throw AppFunctionAppUnknownException("Assistant entry is disabled in the app settings")
        }
        val category = ReportCategory.from(categoryCode)
            ?: throw AppFunctionInvalidArgumentException("Unknown report category")
        return PendingIntent.getActivity(context, 150 + category.ordinal, captureIntent(context, category),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

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
            val state = if (enabled) AppFunctionManager.APP_FUNCTION_STATE_ENABLED else AppFunctionManager.APP_FUNCTION_STATE_DISABLED
            manager.setAppFunctionEnabled(FUNCTION_ID, state)
            manager.setAppFunctionEnabled(FUNCTION_ID_CATEGORY, state)
            store.save(enabled)
            true
        } catch (_: Exception) {
            // Indexing may not be ready on first install. Never show a false success.
            false
        }
    }
}

