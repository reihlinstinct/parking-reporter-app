package io.github.reihlinstinct.reporter

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.ledger.ReportDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class InstalledNamespaceUpgradeTest {
    @Test fun oldInstalledDataSurvivesReplaceInstall() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("namespaceUpgrade") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("il.sidewalks.reporter", context.packageName)
        val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
        try {
            val row = db.reports().find("namespace-upgrade-synthetic")
            assertNotNull("Old Room row survives adb install -r", row)
            assertEquals(7L, row!!.revision)
            assertEquals("{\"syntheticUpgrade\":true}", row.draftJson)
            assertEquals("synthetic upgrade evidence", File(context.noBackupFilesDir, row.originalRelativePath).readText())
            assertTrue(context.getSharedPreferences("appearance", 0).getBoolean("dynamic_palette", false))
            val pm = context.packageManager
            assertNotNull(pm.getActivityInfo(android.content.ComponentName(context.packageName, "il.sidewalks.reporter.MainActivity"), 0))
            assertNotNull(pm.getServiceInfo(android.content.ComponentName(context.packageName, "il.sidewalks.reporter.ReportTileService"), 0))
        } finally { db.close() }
    }
}
