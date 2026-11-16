package il.sidewalks.reporter

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.ledger.ReportDatabase
import il.sidewalks.reporter.ledger.ReportRecord
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Injected into the pinned pre-rename test build, synthetic local bytes only. */
class NamespaceUpgradeSeedTest {
    @Test fun seedOldInstall() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File(context.noBackupFilesDir, "originals/namespace-upgrade-synthetic.txt")
        original.parentFile!!.mkdirs()
        original.writeText("synthetic upgrade evidence")
        context.getSharedPreferences("appearance", 0).edit().putBoolean("dynamic_palette", true).commit()
        val db = Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build()
        try {
            db.reports().reserve(ReportRecord("namespace-upgrade-synthetic", "a".repeat(64),
                "originals/namespace-upgrade-synthetic.txt", 7, "{\"syntheticUpgrade\":true}", null))
        } finally { db.close() }
    }
}
