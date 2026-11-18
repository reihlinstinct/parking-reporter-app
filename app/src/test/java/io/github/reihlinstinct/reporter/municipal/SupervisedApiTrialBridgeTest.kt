package io.github.reihlinstinct.reporter.municipal

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class SupervisedApiTrialBridgeTest {
    private class Client : MunicipalClient {
        val calls = mutableListOf<String>()
        var payload: Map<String, Any?>? = null
        var failCreate = false
        var crm = "synthetic-case"
        override fun login(): String { calls.add("login"); return "synthetic-token" }
        override fun subjectCategories(token: String) = listOf(io.github.reihlinstinct.reporter.core.MunicipalSubject("1137", "synthetic subject", emptyList()))
        override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> {
            calls.add("contact"); return mapOf("ContactIdInCRM" to "synthetic-contact")
        }
        override fun createCase(token: String, contact: LinkedHashMap<String, Any?>, report: LinkedHashMap<String, Any?>): Map<String, Any?> {
            calls.add("create"); payload = report
            if (failCreate) error("synthetic refusal")
            return mapOf("CaseIdInCRM" to crm, "WebCaseIdInCRM" to "synthetic-web", "CaseNumberInCRM" to null)
        }
        override fun attachPhoto(token: String, caseIdInCRM: String, webCaseIdInCRM: String, fileName: String, jpeg: ByteArray) { calls.add("attach") }
    }

    private fun report(root: java.io.File): Map<String, Any?> {
        val file = java.io.File(root, "photos/approved.jpg"); file.parentFile.mkdirs()
        val image = java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        javax.imageio.ImageIO.write(image, "jpg", file); image.flush()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        // Synthetic point in the supported coordinate envelope, not a real filing.
        return mapOf("reportId" to "synthetic-trial", "text" to "synthetic description", "subjectCode" to "1137",
            "latitude" to 31.8, "longitude" to 35.2, "locationDescription" to "synthetic crossing, no verified number",
            "photos" to listOf(mapOf("path" to "photos/approved.jpg", "sha256" to hash)))
    }

    @Test fun gpsOnlyPayloadHasNoGuessedStreetOrHouseAndKeepsPreciseCoordinates() {
        val root = Files.createTempDirectory("synthetic-trial").toFile()
        try {
            val report = report(root); val payload = SupervisedApiTrialBridge.payload(report)
            for (field in listOf("CaseStreetCode", "CaseStreetName", "CaseHouseNumber")) assertEquals("", payload[field])
            assertEquals(report["locationDescription"], payload["CaseAddressText"])
            assertEquals(Itm.toItm(31.8, 35.2).first, payload["CoordinateX"])
            assertEquals(Itm.toItm(31.8, 35.2).second, payload["CoordinateY"])
            assertFalse(payload.containsKey("VehicleNumber"))
            assertEquals(report["text"], payload["CaseDescription"])
            assertThrows(IllegalArgumentException::class.java) { SupervisedApiTrialBridge.payload(report + ("houseNumber" to "12")) }
            assertThrows(IllegalArgumentException::class.java) { SupervisedApiTrialBridge.payload(report + ("latitude" to Double.NaN)) }
        } finally { root.deleteRecursively() }
    }

    @Test fun coordinateEnvelopeMatchesPythonInclusiveEdges() {
        val root = Files.createTempDirectory("synthetic-envelope").toFile()
        try {
            val r = report(root)
            for (lat in listOf(31.65, 31.95)) for (lon in listOf(35.05, 35.35)) {
                SupervisedApiTrialBridge.payload(r + mapOf("latitude" to lat, "longitude" to lon))
            }
            for ((lat, lon) in listOf(31.649999 to 35.2, 31.950001 to 35.2, 31.8 to 35.049999, 31.8 to 35.350001)) {
                assertThrows(IllegalArgumentException::class.java) {
                    SupervisedApiTrialBridge.payload(r + mapOf("latitude" to lat, "longitude" to lon))
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun prepareIsOfflineCreateAndAttachAreSeparateAndBurned() {
        val root = Files.createTempDirectory("synthetic-trial").toFile()
        try {
            val report = report(root); val client = Client(); val bridge = SupervisedApiTrialBridge(client, linkedMapOf(), root)
            bridge.call(mapOf("operation" to "prepare", "report" to report)); assertTrue(client.calls.isEmpty())
            bridge.call(mapOf("operation" to "login"))
            @Suppress("UNCHECKED_CAST") val case = bridge.call(mapOf("operation" to "create", "report" to report))["case"] as Map<String, Any?>
            assertEquals(listOf("login", "contact", "create"), client.calls)
            assertNull(case["CaseNumberInCRM"])
            assertThrows(IllegalStateException::class.java) { bridge.call(mapOf("operation" to "create", "report" to report)) }
            @Suppress("UNCHECKED_CAST") val photo = (report["photos"] as List<Map<String, Any?>>).single()
            bridge.call(mapOf("operation" to "attach", "case" to case, "photo" to photo))
            assertEquals("attach", client.calls.last())
            assertThrows(IllegalStateException::class.java) { bridge.call(mapOf("operation" to "attach", "case" to case, "photo" to photo)) }
        } finally { root.deleteRecursively() }
    }

    @Test fun zeroCrmWithWebIdAttachesOnceAndInvalidPairIsRefused() {
        val root = Files.createTempDirectory("synthetic-zero-crm").toFile()
        try {
            val r = report(root)
            val c = Client().apply { crm = "00000000-0000-0000-0000-000000000000" }
            val b = SupervisedApiTrialBridge(c, linkedMapOf(), root)
            b.call(mapOf("operation" to "prepare", "report" to r)); b.call(mapOf("operation" to "login"))
            @Suppress("UNCHECKED_CAST") val receipt = b.call(mapOf("operation" to "create", "report" to r))["case"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val photo = (r["photos"] as List<Map<String, Any?>>).single()
            assertThrows(IllegalArgumentException::class.java) { b.call(mapOf("operation" to "attach", "case" to (receipt + ("WebCaseIdInCRM" to "other-web")), "photo" to photo)) }
            b.call(mapOf("operation" to "attach", "case" to receipt, "photo" to photo))
            assertThrows(IllegalStateException::class.java) { b.call(mapOf("operation" to "attach", "case" to receipt, "photo" to photo)) }
            assertEquals(listOf("login", "contact", "create", "attach"), c.calls)
            assertThrows(IllegalArgumentException::class.java) { MunicipalCaseReference.requireAttachable(mapOf("CaseIdInCRM" to c.crm, "WebCaseIdInCRM" to c.crm)) }
            assertThrows(IllegalArgumentException::class.java) { MunicipalCaseReference.requireAttachable(mapOf("WebCaseIdInCRM" to "synthetic-web")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun createRefusalNeverRetriesOrAttaches() {
        val root = Files.createTempDirectory("synthetic-trial").toFile()
        try {
            val report = report(root); val client = Client().apply { failCreate = true }; val bridge = SupervisedApiTrialBridge(client, linkedMapOf(), root)
            bridge.call(mapOf("operation" to "prepare", "report" to report)); bridge.call(mapOf("operation" to "login"))
            repeat(2) { assertThrows(IllegalStateException::class.java) { bridge.call(mapOf("operation" to "create", "report" to report)) } }
            assertEquals(listOf("login", "contact", "create"), client.calls)
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidPhotoAndMutatedReportRefuseWithoutCreate() {
        val root = Files.createTempDirectory("synthetic-trial").toFile()
        try {
            val report = report(root); val client = Client(); val bridge = SupervisedApiTrialBridge(client, linkedMapOf(), root)
            java.io.File(root, "photos/approved.jpg").writeBytes(byteArrayOf(1, 2, 3))
            assertThrows(IllegalArgumentException::class.java) { bridge.call(mapOf("operation" to "prepare", "report" to report)) }
            assertTrue(client.calls.isEmpty())
            val valid = report(root)
            bridge.call(mapOf("operation" to "prepare", "report" to valid)); bridge.call(mapOf("operation" to "login"))
            assertThrows(IllegalArgumentException::class.java) { bridge.call(mapOf("operation" to "create", "report" to (valid + ("text" to "mutated")))) }
            assertEquals(listOf("login"), client.calls)
        } finally { root.deleteRecursively() }
    }
    @Test fun actualHttpAttachmentSendsZeroCrmAndWebIdUnchanged() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("{\"Success\":true,\"BlobName\":\"synthetic-blob.jpg\"}").build())
            val c = OkHttpMunicipalClient.forTesting(server.url("/synthetic/"), "synthetic-key", "synthetic-user", "synthetic-password")
            val zero = "00000000-0000-0000-0000-000000000000"
            c.attachPhoto("synthetic-token", zero, "synthetic-web", "evidence.jpg", byteArrayOf(1, 2, 3))
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/synthetic/AddFileToCase", request.url.encodedPath)
            val body = org.json.JSONObject(request.body!!.utf8())
            assertEquals(zero, body.getString("caseIdInCRM"))
            assertEquals("synthetic-web", body.getString("webCaseIdInCRM"))
            assertEquals("AQID", body.getString("fileContent"))
            assertEquals("evidence.jpg", body.getString("fileName"))
        }
    }

    @Test fun actualHttpPayloadKeepsBlankAddressAnd403DoesNotRetry() {
        val root = Files.createTempDirectory("synthetic-trial-http").toFile()
        try {
            MockWebServer().apply { start() }.use { server ->
                server.enqueue(MockResponse.Builder().code(403).body("synthetic rejection").build())
                val client = OkHttpMunicipalClient.forTesting(server.url("/synthetic/"), "synthetic-key", "synthetic-user", "synthetic-password")
                val payload = SupervisedApiTrialBridge.payload(report(root))
                assertThrows(MunicipalApiException::class.java) { client.createCase("synthetic-token", linkedMapOf(), payload) }
                assertEquals(1, server.requestCount)
                val request = server.takeRequest()
                assertEquals("/synthetic/CreateCase", request.url.encodedPath)
                val body = org.json.JSONObject(request.body!!.utf8())
                for (field in listOf("CaseStreetCode", "CaseStreetName", "CaseHouseNumber")) assertEquals("", body.getString(field))
                assertEquals(payload["CoordinateX"], body.getInt("CoordinateX"))
                assertEquals(payload["CoordinateY"], body.getInt("CoordinateY"))
            }
        } finally { root.deleteRecursively() }
    }

}
