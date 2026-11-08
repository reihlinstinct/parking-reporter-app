package il.sidewalks.reporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for entry-point parsing (issue #149 phase 1). */
class EntryIntentsTest {

    @Test
    fun deepLinkOpensNewReport() {
        assertEquals(EntryIntents.Request.NewReport,
            EntryIntents.parse("android.intent.action.VIEW", "app://report/new", null, null))
        assertEquals(EntryIntents.Request.NewReport,
            EntryIntents.parse("android.intent.action.VIEW", "app://report", null, null))
        assertEquals(EntryIntents.Request.NewReport,
            EntryIntents.parse("android.intent.action.VIEW", "app://report/new/", null, null))
    }

    @Test
    fun tileActionOpensNewReport() {
        assertEquals(EntryIntents.Request.NewReport,
            EntryIntents.parse(EntryIntents.ACTION_TILE_NEW_REPORT, null, null, null))
    }

    @Test
    fun sharedImagePrefillsPhoto() {
        assertEquals(EntryIntents.Request.SharedPhoto("content://media/1"),
            EntryIntents.parse("android.intent.action.SEND", null, "image/jpeg", "content://media/1"))
        assertEquals(EntryIntents.Request.SharedPhoto("content://media/2"),
            EntryIntents.parse("android.intent.action.SEND", null, "image/png", "content://media/2"))
    }

    @Test
    fun shareWithoutImageOrStreamIgnored() {
        assertNull(EntryIntents.parse("android.intent.action.SEND", null, "text/plain", "content://media/1"))
        assertNull(EntryIntents.parse("android.intent.action.SEND", null, "image/jpeg", null))
    }

    @Test
    fun unknownIntentsIgnored() {
        assertNull(EntryIntents.parse("android.intent.action.VIEW", "https://example.com/report/new", null, null))
        assertNull(EntryIntents.parse("android.intent.action.VIEW", "app://other/new", null, null))
        assertNull(EntryIntents.parse("android.intent.action.MAIN", null, null, null))
        assertNull(EntryIntents.parse(null, null, null, null))
    }
}
