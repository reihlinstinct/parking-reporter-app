package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AddressFileCacheTest {
    private fun withCache(action: (AddressFileCache, File) -> Unit) {
        val root = Files.createTempDirectory("address-cache-test").toFile()
        try { action(AddressFileCache(root), root) } finally { root.deleteRecursively() }
    }
    private fun fixture() = File("src/main/assets/addresses.json")

    @Test fun emptyCacheDoesNotPretendOfflineSupport() = withCache { cache, _ ->
        assertFalse(cache.available())
        assertThrows(IllegalStateException::class.java) { cache.verifiedFile() }
    }
    @Test fun exactFileCanBeReadOfflineThenRemoved() = withCache { cache, _ ->
        fixture().inputStream().use { cache.store(it) }
        assertTrue(cache.available())
        assertEquals(fixture().length(), cache.verifiedFile().length())
        cache.clear()
        assertFalse(cache.available())
    }
    @Test fun badImportNeverReplacesGoodCacheOrLeavesPartialFile() = withCache { cache, root ->
        fixture().inputStream().use { cache.store(it) }
        assertThrows(AddressLookupMismatchException::class.java) { cache.store("wrong dataset".byteInputStream()) }
        assertTrue(cache.available())
        assertEquals(listOf("addresses-v1.json"), root.listFiles()!!.map { it.name })
    }
    @Test fun interruptedReadKeepsPreviousGoodCache() = withCache { cache, root ->
        fixture().inputStream().use { cache.store(it) }
        val broken = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("synthetic") }
        assertThrows(java.io.IOException::class.java) { cache.store(broken) }
        assertTrue(cache.available())
        assertEquals(1, root.listFiles()!!.size)
    }
    @Test fun oversizedInputIsBoundedAndNeverCached() = withCache { cache, root ->
        val input = object : java.io.InputStream() { override fun read() = 65 }
        assertThrows(IllegalArgumentException::class.java) { cache.store(input) }
        assertFalse(cache.available())
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun tamperedPersistedFileIsNotAnOfflineHit() = withCache { cache, _ ->
        fixture().inputStream().use { cache.store(it) }
        cache.verifiedFile().appendText("tampered")
        assertFalse(cache.available())
    }
}
