package io.github.reihlinstinct.reporter

import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.municipal.FileSubmissionStateStore
import io.github.reihlinstinct.reporter.municipal.MunicipalPayloads
import io.github.reihlinstinct.reporter.municipal.MunicipalReviewNeededException
import io.github.reihlinstinct.reporter.municipal.OkHttpMunicipalClient
import io.github.reihlinstinct.reporter.municipal.SubmissionPhoto
import io.github.reihlinstinct.reporter.municipal.SubmissionResult
import io.github.reihlinstinct.reporter.municipal.SubmissionStateMachine
import java.io.File
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Multi-photo durable persistence on a real device (#548): the on-device
 * FileSubmissionStateStore survives a fresh store/machine pair mid-batch,
 * so a killed process loses nothing that was confirmed and releases nothing
 * that was uncertain. Stub-only: a loopback MockWebServer answers the
 * municipal calls; no network outside loopback, no credentials, no live
 * mode. All values synthetic.
 */
class MultiPhotoSubmissionDeviceTest {

    private lateinit var dir: File

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.noBackupFilesDir, "multi-photo-device-test")
        dir.deleteRecursively()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = FileSubmissionStateStore.forReport(dir, "synthetic-multi-1")

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

    @Suppress("UNCHECKED_CAST")
    @Test
    fun perPhotoStateSurvivesAFreshStoreAndNeverRecreatesTheCase() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().body("""{"Success":true,"Token":"synthetic-token"}""").build())
            server.enqueue(MockResponse.Builder().body("""{"Success":true,"ContactIdInCRM":"synthetic-contact-id"}""").build())
            server.enqueue(MockResponse.Builder().body(
                """{"Success":true,"CaseIdInCRM":"synthetic-case-id","WebCaseIdInCRM":"synthetic-webcase-id","CaseNumberInCRM":"2000-0000000"}"""
            ).build())
            server.enqueue(MockResponse.Builder().body("""{"Success":true}""").build()) // photo a confirmed
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()) // photo b ambiguous

            val firstClient = OkHttpMunicipalClient.forTesting(
                server.url("/CRMCityConnect/api/jerusalem/"), "synthetic-key", "synthetic-user", "synthetic-password")
            assertThrows(MunicipalReviewNeededException::class.java) {
                SubmissionStateMachine(firstClient).submitPhotos(store(), contact, report, photos)
            }

            // A brand-new store instance reads exactly what the killed
            // attempt left on disk: one attached, one uncertain, one pending.
            val durable = store().read()!!
            assertEquals(2, (durable["version"] as Number).toInt())
            assertEquals("attaching", durable["status"])
            val journal = durable["photos"] as List<Map<String, Any?>>
            assertEquals(listOf("attached", "attaching", "pending"), journal.map { it["status"] })
            val lastName = journal.last()["file_name"] as String
            assertTrue(Regex("[0-9a-f-]{36}\\.jpg").matches(lastName))
            // The on-disk JSON itself carries the per-photo journal.
            val raw = File(dir, "synthetic-multi-1.json").readText(Charsets.UTF_8)
            val rawPhotos = JSONObject(raw).getJSONArray("photos")
            assertEquals(photos.size, rawPhotos.length())
            assertEquals(journal.map { it["file_name"] }, (0 until rawPhotos.length()).map { rawPhotos.getJSONObject(it).getString("file_name") })
            assertEquals(listOf("attached", "attaching", "pending"), (0 until rawPhotos.length()).map { rawPhotos.getJSONObject(it).getString("status") })
            
            // Resume through a fresh machine AND store with explicit retry:
            // photos b and c attach, the case is not recreated, photo a is
            // not re-attached.
            server.enqueue(MockResponse.Builder().body("""{"Success":true,"Token":"synthetic-token"}""").build())
            server.enqueue(MockResponse.Builder().body("""{"Success":true}""").build())
            server.enqueue(MockResponse.Builder().body("""{"Success":true}""").build())
            val secondClient = OkHttpMunicipalClient.forTesting(
                server.url("/CRMCityConnect/api/jerusalem/"), "synthetic-key", "synthetic-user", "synthetic-password")
            val result = SubmissionStateMachine(secondClient)
                .submitPhotos(store(), contact, report, photos, retryPhotos = true)
            assertTrue(result is SubmissionResult.Submitted)

            val targets = generateSequence {
                server.takeRequest(1, TimeUnit.SECONDS)?.target?.substringAfterLast('/')
            }.toList()
            assertEquals(1, targets.count { it == "CreateCase" })
            // a, the ambiguous b attempt, then b and c again on the explicit retry.
            assertEquals(4, targets.count { it == "AddFileToCase" })

            val done = store().read()!!
            assertEquals("complete", done["status"])
            assertTrue((done["photos"] as List<Map<String, Any?>>).all { it["status"] == "attached" })
            // Attachment bodies (case binding, per-photo bytes) are verified
            // in the JVM contract test against the same stub.
            val finalJson = JSONObject(File(dir, "synthetic-multi-1.json").readText(Charsets.UTF_8))
            assertEquals(2, finalJson.getInt("version"))
            assertEquals("complete", finalJson.getString("status"))
            assertTrue(finalJson.getString("text_sha256").matches(Regex("[0-9a-f]{64}")))
            assertEquals("synthetic-case-id", finalJson.getJSONObject("case").getString("CaseIdInCRM"))
            val finalPhotos = finalJson.getJSONArray("photos")
            assertEquals(photos.size, finalPhotos.length())
            for (i in 0 until finalPhotos.length()) {
                val entry = finalPhotos.getJSONObject(i)
                assertEquals("attached", entry.getString("status"))
                assertEquals(photos[i].sha256, entry.getString("sha256"))
                assertTrue(Regex("[0-9a-f-]{36}\\.jpg").matches(entry.getString("file_name")))
            }
        }
    }
}
