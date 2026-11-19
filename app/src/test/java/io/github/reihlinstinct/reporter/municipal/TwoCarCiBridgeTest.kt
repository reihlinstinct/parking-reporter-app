package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TwoCarCiBridgeTest {
    private class Client(val subjects: List<String> = listOf("1145"), val abandonedName: String = "רכב גרוטאה") : MunicipalClient {
        val calls = mutableListOf<String>()
        override fun login(): String { calls.add("login"); return "synthetic-token" }
        override fun subjectCategories(token: String) = subjects.map { io.github.reihlinstinct.reporter.core.MunicipalSubject(it, if (it == "1068") abandonedName else "synthetic", emptyList(), true) }
        override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> { calls.add("contact"); return mapOf("ContactIdInCRM" to "synthetic-contact") }
        override fun createCase(token: String, contact: LinkedHashMap<String, Any?>, report: LinkedHashMap<String, Any?>): Map<String, Any?> { calls.add("create"); return mapOf("CaseIdInCRM" to "00000000-0000-0000-0000-000000000000", "WebCaseIdInCRM" to "synthetic-web", "CaseNumberInCRM" to null) }
        override fun attachPhoto(token: String, caseIdInCRM: String, webCaseIdInCRM: String, fileName: String, jpeg: ByteArray) {
            assertEquals("00000000-0000-0000-0000-000000000000", caseIdInCRM)
            assertEquals("synthetic-web", webCaseIdInCRM)
            calls.add("attach")
        }
    }
    private fun report(root: java.io.File): Map<String, Any?> {
        val f=java.io.File(root,"photos/approved.jpg");f.parentFile.mkdirs()
        val im=java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB);javax.imageio.ImageIO.write(im,"jpg",f);im.flush()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        return mapOf("reportId" to "synthetic", "text" to "synthetic vehicle 1234567", "plate" to "1234567", "subjectCode" to "1145", "streetCode" to "123", "streetName" to "synthetic street", "houseNumber" to "2", "coordinateX" to 220000, "coordinateY" to 630000, "photos" to listOf(mapOf("path" to "photos/approved.jpg", "sha256" to hash)))
    }
    @Test fun payloadUsesProductionBuilderAndRefusesWrongTopicOrMissingPlate() {
        val root=Files.createTempDirectory("synthetic-cars").toFile()
        try {
            val r=report(root);val p=TwoCarCiBridge.payload(r)
            assertEquals("1145",p["CaseSubjectCode"]);assertEquals("2",p["CaseHouseNumber"]);assertEquals(r["text"],p["CaseDescription"])
            assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r+("subjectCode" to "1137")) }
            assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r+("text" to "no plate")) }
        } finally { root.deleteRecursively() }
    }
    @Test fun abandonedVehiclePreserves1068AndRequiresLiveIdentityBeforeAnyWrite() {
        val root=Files.createTempDirectory("synthetic-abandoned-car").toFile()
        try {
            val r=report(root)+("subjectCode" to "1068")
            assertEquals("1068", TwoCarCiBridge.payload(r)["CaseSubjectCode"])
            for (client in listOf(Client(), Client(listOf("1068"), "changed category"))) {
                val b=TwoCarCiBridge(client, linkedMapOf(), root)
                b.call(mapOf("operation" to "prepare", "report" to r))
                assertThrows(IllegalArgumentException::class.java) { b.call(mapOf("operation" to "login")) }
                assertEquals(listOf("login"), client.calls)
                assertThrows(IllegalStateException::class.java) { b.call(mapOf("operation" to "create", "report" to r)) }
            }
            val client=Client(listOf("1068"));val b=TwoCarCiBridge(client, linkedMapOf(), root)
            b.call(mapOf("operation" to "prepare", "report" to r))
            b.call(mapOf("operation" to "login"))
            b.call(mapOf("operation" to "create", "report" to r))
            assertEquals(listOf("login", "contact", "create"), client.calls)
            assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r+("plate" to "")) }
            assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r+("subjectCode" to "1015")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun acceptedZeroCrmReceiptAttachesExactPairOnceButCannotRecreate() {
        val root=Files.createTempDirectory("synthetic-cars").toFile()
        try {
            val c=Client();val b=TwoCarCiBridge(c, linkedMapOf(),root);val r=report(root)
            b.call(mapOf("operation" to "prepare","report" to r));assertTrue(c.calls.isEmpty())
            b.call(mapOf("operation" to "login"))
            @Suppress("UNCHECKED_CAST") val receipt=b.call(mapOf("operation" to "create","report" to r))["case"] as Map<String,Any?>
            assertEquals("synthetic-web",receipt["WebCaseIdInCRM"])
            assertThrows(IllegalStateException::class.java) { b.call(mapOf("operation" to "create","report" to r)) }
            @Suppress("UNCHECKED_CAST") val photo=(r["photos"] as List<Map<String,Any?>>).single()
            b.call(mapOf("operation" to "attach","case" to receipt,"photo" to photo))
            assertThrows(IllegalStateException::class.java) { b.call(mapOf("operation" to "attach","case" to receipt,"photo" to photo)) }
            assertEquals(listOf("login","contact","create","attach"),c.calls)
        } finally { root.deleteRecursively() }
    }
}
