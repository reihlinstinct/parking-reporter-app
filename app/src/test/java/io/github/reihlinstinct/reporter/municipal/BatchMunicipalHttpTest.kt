package io.github.reihlinstinct.reporter.municipal

import java.nio.file.Files
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Real production HTTP transport against a loopback mock only. */
class BatchMunicipalHttpTest {
    private val lookup = """{"version":1,"streets":[{"StreetCode":"1001","StreetName":"synthetic street"}],"addresses":[[31.7683,35.2137,"12","synthetic street"]]}"""
    private val report = mapOf("text" to "synthetic description", "subjectCode" to "9000", "address" to "synthetic street 12", "latitude" to 31.7683, "longitude" to 35.2137)
    private fun ok(body: String) = MockResponse.Builder().body(body).build()

    @Test fun noPhotoUsesOneCreateAndNoAddFileAndPreservesNullNumber() {
        MockWebServer().apply { start() }.use { server ->
            val root = Files.createTempDirectory("synthetic-batch-http").toFile()
            try {
                server.enqueue(ok("""{"Success":true,"Token":"synthetic-token"}"""))
                server.enqueue(ok("""{"Success":true,"ContactIdInCRM":"synthetic-contact"}"""))
                server.enqueue(ok("""{"Success":true,"CaseIdInCRM":"00000000-0000-0000-0000-000000000000","WebCaseIdInCRM":"synthetic-case","CaseNumberInCRM":null}"""))
                val client = OkHttpMunicipalClient.forTesting(server.url("/"), "synthetic-key", "synthetic-user", "synthetic-password")
                val bridge = BatchMunicipalBridge(client, StreetDirectory.load(lookup), linkedMapOf(), root)
                bridge.call(mapOf("operation" to "prepare", "report" to report))
                assertEquals(0, server.requestCount)
                bridge.call(mapOf("operation" to "login"))
                val result = bridge.call(mapOf("operation" to "create", "report" to report))
                assertEquals(3, server.requestCount)
                assertEquals("/Login", server.takeRequest().url.encodedPath)
                assertEquals("/createupdatecontact", server.takeRequest().url.encodedPath)
                val create = server.takeRequest()
                assertEquals("/CreateCase", create.url.encodedPath)
                @Suppress("UNCHECKED_CAST") val case = result["case"] as Map<String, Any?>
                assertNull(case["CaseNumberInCRM"])
                assertEquals("synthetic-case", case["WebCaseIdInCRM"])
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun login403HasNoContactCreateOrAttach() {
        MockWebServer().apply { start() }.use { server ->
            val root = Files.createTempDirectory("synthetic-batch-http").toFile()
            try {
                server.enqueue(MockResponse.Builder().code(403).body("synthetic blocked").build())
                val bridge = BatchMunicipalBridge(OkHttpMunicipalClient.forTesting(server.url("/"), "synthetic-key", "synthetic-user", "synthetic-password"),
                    StreetDirectory.load(lookup), linkedMapOf(), root)
                assertThrows(MunicipalApiException::class.java) { bridge.call(mapOf("operation" to "login")) }
                assertEquals(1, server.requestCount)
                assertThrows(IllegalStateException::class.java) { bridge.call(mapOf("operation" to "create", "report" to report)) }
                assertEquals(1, server.requestCount)
            } finally { root.deleteRecursively() }
        }
    }
}
