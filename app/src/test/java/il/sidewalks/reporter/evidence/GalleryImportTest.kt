package il.sidewalks.reporter.evidence

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GalleryImportTest {
    @Test fun byteExactCopy() {
        val dir = java.nio.file.Files.createTempDirectory("gallery-import").toFile()
        val source = ByteArray(10240) { (it * 31 % 251).toByte() }
        val out = GalleryImport.copyToOriginals({ source.inputStream() }, dir, "picked.jpg")
        assertEquals("picked.jpg", out.name)
        assertArrayEquals(source, out.readBytes())
    }
    @Test fun unreadableImageFailsAndLeavesNoFiles() {
        val dir = java.nio.file.Files.createTempDirectory("gallery-import").toFile()
        try {
            GalleryImport.copyToOriginals({ null }, dir)
            fail("expected failure")
        } catch (_: Exception) {}
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
    @Test fun defaultNameIsUniqueJpg() {
        val dir = java.nio.file.Files.createTempDirectory("gallery-import").toFile()
        val a = GalleryImport.copyToOriginals({ "x".byteInputStream() }, dir)
        val b = GalleryImport.copyToOriginals({ "x".byteInputStream() }, dir)
        assertNotEquals(a.name, b.name)
        assertTrue(a.name.endsWith(".jpg"))
    }
    @Test fun deleteOrphanedRemovesUnreservedCopy() {
        val dir = java.nio.file.Files.createTempDirectory("gallery-import").toFile()
        val out = GalleryImport.copyToOriginals({ "orphan".byteInputStream() }, dir)
        assertTrue(out.exists())
        assertTrue(GalleryImport.deleteOrphaned(out))
        assertFalse(out.exists())
        assertTrue(GalleryImport.deleteOrphaned(out))
    }
    @Test fun failedCopyLeavesNoPartialFiles() {
        val dir = java.nio.file.Files.createTempDirectory("gallery-import").toFile()
        val broken = object : java.io.InputStream() {
            private var sent = 0
            override fun read(): Int = if (sent++ < 100) 65 else throw java.io.IOException("synthetic mid-copy failure")
        }
        try {
            GalleryImport.copyToOriginals({ broken }, dir)
            fail("expected failure")
        } catch (_: java.io.IOException) {}
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
    @Test fun sweepUnreferencedRemovesOnlyUnreferenced() {
        val dir = java.nio.file.Files.createTempDirectory("gallery-sweep").toFile()
        val keep = GalleryImport.copyToOriginals({ "keep".byteInputStream() }, dir, "keep.jpg")
        val drop = GalleryImport.copyToOriginals({ "drop".byteInputStream() }, dir, "drop.jpg")
        assertEquals(1, GalleryImport.sweepUnreferenced(dir, setOf("keep.jpg")))
        assertTrue(keep.exists())
        assertFalse(drop.exists())
        assertEquals(0, GalleryImport.sweepUnreferenced(dir, setOf("keep.jpg")))
    }
}
