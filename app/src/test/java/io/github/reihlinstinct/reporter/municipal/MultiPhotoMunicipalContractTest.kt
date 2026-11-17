package io.github.reihlinstinct.reporter.municipal

import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server-stub contract for the multi-photo submission path (#548): a real
 * OkHttpMunicipalClient against a loopback MockWebServer proves one case
 * receives exactly N attachments, in order, bound to that case - and that
 * an ambiguous attachment holds only its own photo. All values synthetic.
 */
class MultiPhotoMunicipalContractTest {

    private fun client(server: MockWebServer) = OkHttpMunicipalClient.forTesting(
        server.url("/CRMCityConnect/api/jerusalem/"),
        "synthetic-key",
        "synthetic-user",
        "synthetic-password",
    )

    private val contact = MunicipalPayloads.buildContact(
        MunicipalPayloads.Reporter("ישראל", "ישראלי", "+972501234567", "000000018", "synthetic@example.com")
    )

    private val report = MunicipalPayloads.buildReport(
        MunicipalPayloads.ReportFields("דיווח סינתטי.", "1145", "1630", "הפלמח", "900", 220000, 630500)
    )

    private fun photo(marker: Byte): SubmissionPhoto {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(16) { (marker + it).toByte() }
        return SubmissionPhoto(
            bytes,
            io.github.reihlinstinct.reporter.intake.EncryptedPackage.sha256(bytes),
        )
    }

    private val photos = listOf(
        photo(10),
        photo(40),
        photo(70),
    )

    private fun MockWebServer.enqueueHappyOpen() {
        enqueue(MockResponse.Builder().body("""{"Success":true,"Token":"synthetic-token"}""").build())
        enqueue(MockResponse.Builder().body("""{"Success":true,"ContactIdInCRM":"synthetic-contact-id"}""").build())
        enqueue(MockResponse.Builder().body(
            """{"Success":true,"CaseIdInCRM":"synthetic-case-id","WebCaseIdInCRM":"synthetic-webcase-id","CaseNumberInCRM":"2000-0000000"}"""
        ).build())
    }

    private val attachOk = MockResponse.Builder().body("""{"Success":true}""").build()

    private fun take(server: MockWebServer) =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "expected request never reached the stub" }

    @Test
    fun oneCaseReceivesEveryPhotoInOrderBoundToThatCase() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueueHappyOpen()
            repeat(photos.size) { server.enqueue(attachOk) }
            val store = InMemorySubmissionStateStore()
            val result = SubmissionStateMachine(client(server))
                .submitPhotos(store, contact, report, photos)
            assertTrue(result is SubmissionResult.Submitted)

            val requests = (1..3 + photos.size).map { take(server) }
            assertEquals(
                listOf("Login", "createupdatecontact", "CreateCase", "AddFileToCase", "AddFileToCase", "AddFileToCase"),
                requests.map { it.target.substringAfterLast('/') },
            )
            val sentNames = requests.drop(3).map { JSONObject(it.body!!.utf8()).getString("fileName") }
            assertEquals(3, sentNames.toSet().size)
            assertEquals(1, requests.count { it.target.endsWith("/CreateCase") })
            val attaches = requests.drop(3)
            for ((index, request) in attaches.withIndex()) {
                val body = JSONObject(request.body!!.utf8())
                assertEquals("synthetic-case-id", body.getString("caseIdInCRM"))
                assertEquals("synthetic-webcase-id", body.getString("webCaseIdInCRM"))
                assertTrue(Regex("[0-9a-f-]{36}\\.jpg").matches(body.getString("fileName")))
                assertArrayEquals(
                    photos[index].bytes(),
                    java.util.Base64.getDecoder().decode(body.getString("fileContent")),
                )
            }
        }
    }

    @Test
    fun ambiguousMidBatchAttachHoldsOnlyItsPhotoAndNeverRecreatesTheCase() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueueHappyOpen()
            server.enqueue(attachOk) // photo a confirmed
            // Photo b: the socket dies as the response starts - the city may
            // have it. That ambiguity must stop the batch for a human check.
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            val store = InMemorySubmissionStateStore()
            assertThrows(MunicipalReviewNeededException::class.java) {
                SubmissionStateMachine(client(server)).submitPhotos(store, contact, report, photos)
            }
            // Drain the first run: login, contact, one CreateCase, photo a,
            // and photo b's attempt (which did reach the server).
            val firstRun = (1..5).map { take(server) }
            assertEquals(
                listOf("Login", "createupdatecontact", "CreateCase", "AddFileToCase", "AddFileToCase"),
                firstRun.map { it.target.substringAfterLast('/') },
            )
            assertEquals("attaching", store.stored!!["status"])
            @Suppress("UNCHECKED_CAST")
            val journal = store.stored!!["photos"] as List<Map<String, Any?>>
            val journalNames = journal.map { it["file_name"] as String }
            assertEquals(listOf("attached", "attaching", "pending"), journal.map { it["status"] })

            // A blind re-run without retryPhotos is refused with no traffic.
            val beforeRefusal = server.requestCount
            assertThrows(MunicipalReviewNeededException::class.java) {
                SubmissionStateMachine(client(server)).submitPhotos(store, contact, report, photos)
            }
            assertEquals(beforeRefusal, server.requestCount)

            // Explicit retry: login again, then exactly photos b and c go
            // out - no second CreateCase, no re-attach of confirmed photo a.
            server.enqueue(MockResponse.Builder().body("""{"Success":true,"Token":"synthetic-token"}""").build())
            server.enqueue(attachOk)
            server.enqueue(attachOk)
            val result = SubmissionStateMachine(client(server))
                .submitPhotos(store, contact, report, photos, retryPhotos = true)
            assertTrue(result is SubmissionResult.Submitted)
            val resumed = listOf(take(server), take(server), take(server))
            assertEquals(
                listOf("Login", "AddFileToCase", "AddFileToCase"),
                resumed.map { it.target.substringAfterLast('/') },
            )
            assertEquals(
                journalNames.drop(1),
                resumed.drop(1).map { JSONObject(it.body!!.utf8()).getString("fileName") },
            )
            assertEquals("complete", store.stored!!["status"])
            assertEquals(
                listOf("attached", "attached", "attached"),
                (store.stored!!["photos"] as List<Map<String, Any?>>).map { it["status"] },
            )
        }
    }
}
