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
}
