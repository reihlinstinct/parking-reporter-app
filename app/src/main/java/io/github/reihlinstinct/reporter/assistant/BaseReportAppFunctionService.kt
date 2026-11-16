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

    /**
     * Start a new Jerusalem municipal report by showing the app's camera flow, with one
     * report category preselected on the review screen. This never files a report and does
     * not create a hidden draft. The person must capture or choose a photo, check the
     * report, and confirm sending in the app. The category is only a starting choice that
     * the person can change. Does not read or return saved reports, identity fields,
     * credentials or photos.
     * @param categoryCode One of: sidewalk_parking, broken_sign, faded_crosswalk, bollard_request.
     * Any other value is rejected.
     * @return An immutable action that opens the existing visible report flow.
     */
    @AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)
    fun startNewReportInCategory(categoryCode: String): PendingIntent = AssistantEntry.categoryEntryAction(this, categoryCode)
}
