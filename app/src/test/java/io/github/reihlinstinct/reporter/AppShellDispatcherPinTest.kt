package io.github.reihlinstinct.reporter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** #386: coroutines that write AppShell Compose state must start on the main dispatcher. */
class AppShellDispatcherPinTest {
    @Test fun everyScopeLaunchIsPinnedAndOnlyIoWorkLaunchesOnIo() {
        val source = File("src/main/java/io/github/reihlinstinct/reporter/ui/AppShell.kt").readText()
        val unpinned = Regex("""scope\.launch\s*\{""").findAll(source).count()
        assertEquals("scope.launch without an explicit dispatcher", 0, unpinned)
        // The only IO launches do no Compose state writes of their own: the topic refresh hops back
        // to Main for its write, and the orphan delete writes nothing.
        assertEquals(2, Regex("""scope\.launch\(Dispatchers\.IO\)""").findAll(source).count())
    }
}
