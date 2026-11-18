package io.github.reihlinstinct.reporter.municipal

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PrivateAddressInstallTest {
    @Test fun wrongPinLeavesOldCopyUntouchedAndNoTemporaryFiles() {
        val dir = Files.createTempDirectory("address-install").toFile()
        try {
            val target = java.io.File(dir, "addresses.json").apply { writeText("old private copy") }
            assertThrows(AddressLookupMismatchException::class.java) {
                PrivateAddressInstall.install(ByteArrayInputStream("{}".toByteArray()), dir)
            }
            assertEquals("old private copy", target.readText())
            assertEquals(listOf("addresses.json"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }
    @Test fun malformedUtf8IsRefused() {
        val dir = Files.createTempDirectory("address-install").toFile()
        try {
            assertThrows(java.nio.charset.CharacterCodingException::class.java) {
                PrivateAddressInstall.install(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)), dir)
            }
            assertTrue(dir.list()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun oversizedDocumentIsRefusedWithoutInstalling() {
        val dir = Files.createTempDirectory("address-install").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                PrivateAddressInstall.install(ByteArrayInputStream(ByteArray(PrivateAddressInstall.MAX_BYTES + 1)), dir)
            }
            assertTrue(dir.list()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
