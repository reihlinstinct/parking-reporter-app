package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TwoCarCiBridgeTest {
    private class Client(val hazardPath: List<String>? = null, val bicyclePath: List<String>? = null, val duplicateBicyclePath: List<String>? = null, val hazardCode: String = "5164", val hazardName: String = "אחר") : MunicipalClient {
        val calls = mutableListOf<String>()
        override fun login(): String { calls.add("login"); return "synthetic-token" }
        override fun subjectCategories(token: String) = if (bicyclePath != null) (listOf(io.github.reihlinstinct.reporter.core.MunicipalSubject("1015", bicyclePath.last(), bicyclePath, false)) + if (duplicateBicyclePath == null) emptyList() else listOf(io.github.reihlinstinct.reporter.core.MunicipalSubject("1015", duplicateBicyclePath.last(), duplicateBicyclePath, false))) else if (hazardPath != null) listOf(io.github.reihlinstinct.reporter.core.MunicipalSubject(hazardCode, hazardName, hazardPath, false)) else listOf(io.github.reihlinstinct.reporter.core.MunicipalSubject("1145", "synthetic", emptyList(), true))
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
    @Test fun cableHazardRequiresDistinctModeAndExactLivePathBeforeWrite() {
        val root=Files.createTempDirectory("synthetic-cable").toFile()
        try {
            val r=report(root)+mapOf("plate" to "01234567", "text" to "synthetic cable 012-34-567", "subjectCode" to "5164", "eventType" to "charging_cable_sidewalk_hazard", "subjectPath" to TwoCarCiBridge.CABLE_PATH)
            assertEquals("5164",TwoCarCiBridge.payload(r,true)["CaseSubjectCode"])
            assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r) }
            assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r+("subjectPath" to listOf("חניה אסורה","אחר")),true) }
            for (client in listOf(Client(),Client(listOf("חניה אסורה","אחר")),Client(TwoCarCiBridge.CABLE_PATH,hazardName="renamed"),Client(TwoCarCiBridge.CABLE_PATH,hazardCode="1145"))) {
                val b=TwoCarCiBridge(client,linkedMapOf(),root,true)
                b.call(mapOf("operation" to "prepare","report" to r))
                assertThrows(IllegalArgumentException::class.java) { b.call(mapOf("operation" to "login")) }
                assertEquals(listOf("login"),client.calls)
                assertThrows(IllegalStateException::class.java) { b.call(mapOf("operation" to "create","report" to r)) }
            }
            for (change in listOf(mapOf("eventType" to "parking"), mapOf("plate" to "2345678", "text" to "synthetic 12-3456789"))) {
                assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r+change,true) }
            }
            val c=Client(TwoCarCiBridge.CABLE_PATH);val b=TwoCarCiBridge(c,linkedMapOf(),root,true)
            b.call(mapOf("operation" to "prepare","report" to r));b.call(mapOf("operation" to "login"))
            assertThrows(IllegalArgumentException::class.java) { b.call(mapOf("operation" to "create","report" to (r+("text" to "changed")))) }
            b.call(mapOf("operation" to "create","report" to r))
            assertThrows(IllegalStateException::class.java) { b.call(mapOf("operation" to "create","report" to r)) }
            assertEquals(listOf("login","contact","create"),c.calls)
        } finally { root.deleteRecursively() }
    }

    @Test fun bicycleRequiresNullPlateDescriptionAndExactLiveCategoryBeforeWrite() {
        val root=Files.createTempDirectory("synthetic-bicycle").toFile()
        try {
            val description="Synthetic green-marked mountain bicycle locked to a tree."
            val r=report(root)+mapOf("subjectCode" to "1015", "plate" to null, "text" to description,
                "eventType" to "abandoned_bicycle", "subjectPath" to TwoCarCiBridge.BICYCLE_PATH,
                "distinguishingDescription" to description)
            assertEquals("1015",TwoCarCiBridge.payload(r,abandonedBicycle=true)["CaseSubjectCode"])
            assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r) }
            assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r,true,true) }
            for (change in listOf(mapOf("plate" to "1234567"), mapOf("plate" to ""),mapOf("subjectCode" to "1145"),
                mapOf("eventType" to "parking"),mapOf("distinguishingDescription" to "short"),mapOf("distinguishingDescription" to "x".repeat(40)),
                mapOf("text" to (description+" סימון הפנייה")),mapOf("subjectPath" to listOf("ניקיון","אחר")))) {
                assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r+change,abandonedBicycle=true) }
            }
            for (c in listOf(Client(),Client(bicyclePath=listOf("ניקיון","אחר")), Client(bicyclePath=TwoCarCiBridge.BICYCLE_PATH, duplicateBicyclePath=listOf("אחר")), Client(bicyclePath=TwoCarCiBridge.BICYCLE_PATH, duplicateBicyclePath=TwoCarCiBridge.BICYCLE_PATH))) {
                val b=TwoCarCiBridge(c,linkedMapOf(),root,abandonedBicycle=true)
                b.call(mapOf("operation" to "prepare","report" to r))
                assertThrows(IllegalArgumentException::class.java) { b.call(mapOf("operation" to "login")) }
                assertEquals(listOf("login"),c.calls)
                assertThrows(IllegalStateException::class.java) { b.call(mapOf("operation" to "create","report" to r)) }
            }
            // Keep the marker vocabulary in parity with scripts/two_car_ci.py.
            for (marker in listOf("לא נמסר לי שניתן דוח", "סימון הפנייה", "מחדל האכיפה")) {
                assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r+("text" to (description+" "+marker)),abandonedBicycle=true) }
            }
            for (invalid in listOf("short", " ".repeat(30)+"short"+" ".repeat(30), "x".repeat(1001))) {
                assertThrows(IllegalArgumentException::class.java) { TwoCarCiBridge.payload(r+mapOf("text" to invalid, "distinguishingDescription" to invalid),abandonedBicycle=true) }
            }
            val c=Client(bicyclePath=TwoCarCiBridge.BICYCLE_PATH);val b=TwoCarCiBridge(c,linkedMapOf(),root,abandonedBicycle=true)
            b.call(mapOf("operation" to "prepare","report" to r));b.call(mapOf("operation" to "login"))
            assertThrows(IllegalArgumentException::class.java) { b.call(mapOf("operation" to "create","report" to r+("text" to "changed"))) }
            b.call(mapOf("operation" to "create","report" to r))
            assertThrows(IllegalStateException::class.java) { b.call(mapOf("operation" to "create","report" to r)) }
            assertEquals(listOf("login","contact","create"),c.calls)
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
