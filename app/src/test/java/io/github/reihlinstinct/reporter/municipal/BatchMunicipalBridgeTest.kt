package io.github.reihlinstinct.reporter.municipal

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BatchMunicipalBridgeTest {
    private val lookup = """{"version":1,"streets":[{"StreetCode":"1001","StreetName":"synthetic street"}],"addresses":[[31.7683,35.2137,"12","synthetic street"]]}"""
    private val report = mapOf("text" to "synthetic description", "subjectCode" to "9000", "address" to "synthetic street 12", "latitude" to 31.7683, "longitude" to 35.2137)

    private class Client : MunicipalClient {
        val calls = mutableListOf<String>()
        var report: Map<String, Any?>? = null
        override fun login(): String { calls.add("login"); return "synthetic-token" }
        override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> {
            calls.add("contact"); return mapOf("ContactIdInCRM" to "synthetic-contact")
        }
        override fun createCase(token: String, contact: LinkedHashMap<String, Any?>, report: LinkedHashMap<String, Any?>): Map<String, Any?> {
            calls.add("create"); this.report = report
            return mapOf("CaseIdInCRM" to "00000000-0000-0000-0000-000000000000", "WebCaseIdInCRM" to "synthetic-web-case", "CaseNumberInCRM" to null)
        }
        override fun attachPhoto(token: String, caseIdInCRM: String, webCaseIdInCRM: String, fileName: String, jpeg: ByteArray) { calls.add("attach") }
    }

    @Test fun prepareIsOfflineAndCreateUsesProductionPayloadWithoutAttachment() {
        val root = Files.createTempDirectory("synthetic-batch").toFile()
        try {
            val client = Client()
            val bridge = BatchMunicipalBridge(client, StreetDirectory.load(lookup), linkedMapOf(), root)
            assertEquals(true, bridge.call(mapOf("operation" to "prepare", "report" to report))["success"])
            assertTrue(client.calls.isEmpty())
            bridge.call(mapOf("operation" to "login"))
            val result = bridge.call(mapOf("operation" to "create", "report" to report))
            assertEquals(listOf("login", "contact", "create"), client.calls)
            assertEquals("synthetic description", client.report!!["CaseDescription"])
            assertEquals("1001", client.report!!["CaseStreetCode"])
            assertEquals(220333, client.report!!["CoordinateX"])
            @Suppress("UNCHECKED_CAST") val case = result["case"] as Map<String, Any?>
            assertNull(case["CaseNumberInCRM"])
            assertFalse(client.calls.contains("attach"))
        } finally { root.deleteRecursively() }
    }

    @Test fun photoPreflightDecodesBoundedBytesBeforeAnyNetwork() {
        val root = Files.createTempDirectory("synthetic-batch-image").toFile()
        try {
            val client = Client(); val bridge = BatchMunicipalBridge(client, StreetDirectory.load(lookup), linkedMapOf(), root)
            val file = java.io.File(root, "photos/synthetic.png"); file.parentFile.mkdirs()
            val image = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
            javax.imageio.ImageIO.write(image, "png", file)
            fun photos() = listOf(mapOf("path" to "photos/synthetic.png", "sha256" to
                java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }))
            bridge.call(mapOf("operation" to "prepare", "report" to (report + ("photos" to photos()))))
            assertTrue(client.calls.isEmpty())
            file.writeBytes(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()))
            assertThrows(IllegalArgumentException::class.java) {
                bridge.call(mapOf("operation" to "prepare", "report" to (report + ("photos" to photos()))))
            }
            assertTrue(client.calls.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun createBeforeLoginAndUnknownAddressRefuseBeforeWrites() {
        val root = Files.createTempDirectory("synthetic-batch").toFile()
        try {
            val client = Client(); val bridge = BatchMunicipalBridge(client, StreetDirectory.load(lookup), linkedMapOf(), root)
            assertThrows(IllegalStateException::class.java) { bridge.call(mapOf("operation" to "create", "report" to report)) }
            assertTrue(client.calls.isEmpty())
            assertThrows(MunicipalInputException::class.java) {
                bridge.call(mapOf("operation" to "prepare", "report" to (report + ("address" to "unknown street 12"))))
            }
            assertTrue(client.calls.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
