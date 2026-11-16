package io.github.reihlinstinct.reporter.ledger

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Historical v1 schema and identity hash, created before the source rename. */
class SourceRenameUpgradeTest {
    @Test fun renamedDatabaseOpensExistingV1WithoutLosingDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "source-rename-upgrade-synthetic.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS reports (stableId TEXT NOT NULL, originalSha256 TEXT NOT NULL, originalRelativePath TEXT NOT NULL, revision INTEGER NOT NULL, draftJson TEXT NOT NULL, reviewedDigest TEXT, PRIMARY KEY(stableId))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reports_originalSha256 ON reports (originalSha256)")
                db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '54340c9bf76fd96e24f7fd16eee41624')")
                db.execSQL("INSERT INTO reports VALUES ('synthetic-upgrade-draft', 'synthetic-sha256', 'originals/synthetic.jpg', 7, '{}', NULL)")
                db.version = 1
            }
            val renamed = Room.databaseBuilder(context, ReportDatabase::class.java, name).build()
            try {
                val draft = renamed.reports().find("synthetic-upgrade-draft")
                assertNotNull(draft)
                assertEquals("originals/synthetic.jpg", draft!!.originalRelativePath)
                assertEquals(7L, draft.revision)
                assertEquals("{}", draft.draftJson)
                assertEquals(1, renamed.reports().all().size)
            } finally {
                renamed.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
