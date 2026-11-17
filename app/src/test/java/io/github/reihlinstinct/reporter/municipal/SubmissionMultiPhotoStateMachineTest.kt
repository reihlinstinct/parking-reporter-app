package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-photo one-attempt semantics (#548): exactly one CreateCase plus one
 * AddFileToCase per photo, with per-photo durable state. Uses a fake
 * transport; no network. All values are synthetic.
 */
class SubmissionMultiPhotoStateMachineTest {

    private open class FakeClient(
        var contactId: String? = "synthetic-contact-id",
        var createError: Exception? = null,
        var failAttachFor: MutableSet<String> = mutableSetOf(),
        var createResult: Map<String, Any?> = mapOf(
            "CaseIdInCRM" to "synthetic-case-id",
            "WebCaseIdInCRM" to "synthetic-webcase-id",
            "CaseNumberInCRM" to "2000-0000000",
        ),
    ) : MunicipalClient {
        val calls = mutableListOf<String>()
        val attached = mutableListOf<String>()

        override fun login(): String {
            calls.add("login")
            return "synthetic-token"
        }

        override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> {
            calls.add("contact")
            return if (contactId == null) mapOf() else mapOf("ContactIdInCRM" to contactId)
        }

        override fun createCase(
            token: String,
            contact: LinkedHashMap<String, Any?>,
            report: LinkedHashMap<String, Any?>,
        ): Map<String, Any?> {
            calls.add("create")
            createError?.let { throw it }
            return createResult
        }

        override fun attachPhoto(
            token: String,
            caseIdInCRM: String,
            webCaseIdInCRM: String,
            fileName: String,
            jpeg: ByteArray,
        ) {
            calls.add("attach:$fileName")
            if (fileName in failAttachFor) {
                throw MunicipalApiException("AddFileToCase: network failure or invalid response.")
            }
            attached.add(fileName)
        }
    }

    private val contact = MunicipalPayloads.buildContact(
        MunicipalPayloads.Reporter("ישראל", "ישראלי", "+972501234567", "000000018", "synthetic@example.com")
    )

    private val report = MunicipalPayloads.buildReport(
        MunicipalPayloads.ReportFields("דיווח סינתטי.", "1145", "1630", "הפלמח", "900", 220000, 630500)
    )

    private fun photo(marker: Byte): SubmissionPhoto {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), marker)
        return SubmissionPhoto(
            bytes,
            io.github.reihlinstinct.reporter.intake.EncryptedPackage.sha256(bytes),
        )
    }

    private val threePhotos = listOf(
        photo(1),
        photo(2),
        photo(3),
    )

    private fun machine(client: FakeClient): SubmissionStateMachine {
        var next = 0
        return SubmissionStateMachine(client, fileNameGenerator = { "synthetic-${'a' + next++}.jpg" })
    }

    private val textId = submissionTextIdentity(contact, report)

    private fun journal(status: String = "pending") = threePhotos.mapIndexed { i, p ->
        mutableMapOf<String, Any?>("file_name" to "synthetic-${'a' + i}.jpg", "sha256" to p.sha256, "status" to status)
    }

    @Suppress("UNCHECKED_CAST")
    private fun photoStatuses(store: InMemorySubmissionStateStore): List<Pair<String, String>> =
        (store.stored!!["photos"] as List<Map<String, Any?>>).map {
            (it["file_name"] as String) to (it["status"] as String)
        }

    @Test
    fun freshRunCreatesOneCaseAndAttachesEveryPhotoInOrder() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        val result = machine(client).submitPhotos(store, contact, report, threePhotos)
        assertTrue(result is SubmissionResult.Submitted)
        assertEquals("synthetic-case-id", result.case["CaseIdInCRM"])
        assertEquals(
            listOf("login", "contact", "create", "attach:synthetic-a.jpg", "attach:synthetic-b.jpg", "attach:synthetic-c.jpg"),
            client.calls,
        )
        assertEquals(1, client.calls.count { it == "create" })
        assertEquals(
            listOf("prepared", "creating", "created", "attaching", "attaching", "attaching", "attaching", "attaching", "attaching", "complete"),
            store.writes,
        )
        assertEquals(2, store.stored!!["version"])
        assertEquals(
            listOf(
                "synthetic-a.jpg" to "attached",
                "synthetic-b.jpg" to "attached",
                "synthetic-c.jpg" to "attached",
            ),
            photoStatuses(store),
        )
    }

    @Test
    fun completedStateShortCircuitsWithoutNetwork() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        store.write(
            mutableMapOf(
                "version" to 2,
                "status" to "complete",
                "text_sha256" to textId,
                "case" to mapOf("CaseIdInCRM" to "synthetic-case-id"),
                "photos" to mutableListOf(
                    mutableMapOf("file_name" to "synthetic-a.jpg", "sha256" to threePhotos[0].sha256, "status" to "attached"),
                ),
            )
        )
        val result = machine(client).submitPhotos(store, contact, report, threePhotos.subList(0, 1))
        assertTrue(result is SubmissionResult.AlreadySubmitted)
        assertEquals("synthetic-case-id", result.case["CaseIdInCRM"])
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun creatingStateIsRefusedAsUncertain() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        store.write(mutableMapOf("version" to 2, "status" to "creating"))
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, threePhotos)
        }
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun singlePhotoStateIsRefusedByTheMultiPhotoPath() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        store.write(mutableMapOf("version" to 1, "status" to "prepared", "file_name" to "synthetic-a.jpg"))
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, threePhotos)
        }
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun multiPhotoStateIsRefusedByTheSinglePhotoPath() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        store.write(mutableMapOf("version" to 2, "status" to "prepared"))
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submit(store, contact, report, byteArrayOf(1))
        }
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun midBatchAttachFailureHoldsExactlyTheUnconfirmedPhoto() {
        val client = FakeClient(failAttachFor = mutableSetOf("synthetic-b.jpg"))
        val store = InMemorySubmissionStateStore()
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, threePhotos)
        }
        assertEquals("attaching", store.stored!!["status"])
        assertEquals(
            listOf(
                "synthetic-a.jpg" to "attached",
                "synthetic-b.jpg" to "attaching",
                "synthetic-c.jpg" to "pending",
            ),
            photoStatuses(store),
        )
        assertEquals(listOf("synthetic-a.jpg"), client.attached)

        // Without retryPhotos the held photo refuses, and nothing moves.
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, threePhotos)
        }
        assertEquals(listOf("synthetic-a.jpg"), client.attached)
        assertEquals(1, client.calls.count { it == "create" })

        // retryPhotos re-attempts only the unconfirmed photos: the case and
        // the confirmed photo are never touched again.
        client.failAttachFor.clear()
        val attachesBefore = client.calls.count { it.startsWith("attach:") }
        val result = machine(client).submitPhotos(store, contact, report, threePhotos, retryPhotos = true)
        assertTrue(result is SubmissionResult.Submitted)
        assertEquals(1, client.calls.count { it == "create" })
        assertEquals(2, client.calls.count { it.startsWith("attach:") } - attachesBefore)
        assertEquals(
            listOf("synthetic-a.jpg", "synthetic-b.jpg", "synthetic-c.jpg"),
            client.attached,
        )
        assertEquals("complete", store.stored!!["status"])
        assertEquals(
            listOf(
                "synthetic-a.jpg" to "attached",
                "synthetic-b.jpg" to "attached",
                "synthetic-c.jpg" to "attached",
            ),
            photoStatuses(store),
        )
    }

    @Test
    fun resumeFromCreatedAttachesAllPhotosWithoutRecreatingTheCase() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        store.write(
            mutableMapOf(
                "version" to 2,
                "status" to "created",
                "text_sha256" to textId,
                "case" to mutableMapOf(
                    "CaseIdInCRM" to "synthetic-case-id",
                    "WebCaseIdInCRM" to "synthetic-webcase-id",
                ),
                "photos" to journal("pending"),
            )
        )
        val result = machine(client).submitPhotos(store, contact, report, threePhotos)
        assertTrue(result is SubmissionResult.Submitted)
        assertEquals(
            listOf("login", "attach:synthetic-a.jpg", "attach:synthetic-b.jpg", "attach:synthetic-c.jpg"),
            client.calls,
        )
    }

    @Test
    fun changedPhotoSetIsRefusedBeforeNetwork() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        store.write(
            mutableMapOf(
                "version" to 2,
                "status" to "created",
                "text_sha256" to textId,
                "case" to mutableMapOf(
                    "CaseIdInCRM" to "synthetic-case-id",
                    "WebCaseIdInCRM" to "synthetic-webcase-id",
                ),
                "photos" to journal("pending"),
            )
        )
        val different = listOf(threePhotos[0], threePhotos[1], photo(4))
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, different)
        }
        val fewer = threePhotos.subList(0, 2)
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, fewer)
        }
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun unknownPhotoStateIsRefused() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        store.write(
            mutableMapOf(
                "version" to 2,
                "status" to "attaching",
                "text_sha256" to textId,
                "case" to mutableMapOf(
                    "CaseIdInCRM" to "synthetic-case-id",
                    "WebCaseIdInCRM" to "synthetic-webcase-id",
                ),
                "photos" to journal("half-sent"),
            )
        )
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, threePhotos, retryPhotos = true)
        }
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun photosRetryOnFreshStateIsRefused() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        assertThrows(MunicipalInputException::class.java) {
            machine(client).submitPhotos(store, contact, report, threePhotos, retryPhotos = true)
        }
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun createFailureStopsAtCreatingWithReviewNeeded() {
        val client = FakeClient(createError = MunicipalApiException("CreateCase: network failure or invalid response."))
        val store = InMemorySubmissionStateStore()
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, threePhotos)
        }
        assertEquals("creating", store.stored!!["status"])
        assertEquals(listOf("prepared", "creating"), store.writes)
        assertEquals(listOf("login", "contact", "create"), client.calls)
    }

    @Test
    fun invalidPhotoSetsAreRefusedBeforeAnyStateWriteOrNetwork() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()

        assertThrows(MunicipalInputException::class.java) {
            machine(client).submitPhotos(store, contact, report, emptyList())
        }
        assertThrows(MunicipalInputException::class.java) {
            machine(client).submitPhotos(store, contact, report, listOf(threePhotos[0], threePhotos[0]))
        }
        val wrongDigest = listOf(SubmissionPhoto(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1), "0".repeat(64)))
        assertThrows(MunicipalInputException::class.java) {
            machine(client).submitPhotos(store, contact, report, wrongDigest)
        }
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1)
        val png = listOf(
            SubmissionPhoto(pngBytes, io.github.reihlinstinct.reporter.intake.EncryptedPackage.sha256(pngBytes))
        )
        assertThrows(MunicipalInputException::class.java) {
            machine(client).submitPhotos(store, contact, report, png)
        }
        assertEquals(null, store.stored)
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun concurrentSubmitSecondIsRefusedAndExactlyOneCaseIsCreated() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val inLogin = java.util.concurrent.CountDownLatch(1)
        val client = object : FakeClient() {
            override fun login(): String {
                inLogin.countDown()
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return super.login()
            }
        }
        val store = InMemorySubmissionStateStore()
        var resultA: SubmissionResult? = null
        val threadA = kotlin.concurrent.thread {
            resultA = machine(client).submitPhotos(store, contact, report, threePhotos)
        }
        assertTrue(inLogin.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, threePhotos)
        }
        gate.countDown()
        threadA.join(5000)
        assertTrue(resultA is SubmissionResult.Submitted)
        assertEquals(1, client.calls.count { it == "create" })
        assertEquals(3, client.attached.size)
    }

    @Test
    fun catalogCallbackRunsOnlyAfterDurableCompletionOfEveryPhoto() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        var refreshes = 0
        val callback: (MunicipalClient, String) -> Unit = { seen, token ->
            assertTrue(seen === client)
            assertEquals("synthetic-token", token)
            assertEquals("complete", store.stored!!["status"])
            assertTrue(photoStatuses(store).all { it.second == "attached" })
            refreshes++
        }
        assertTrue(
            machine(client).submitPhotos(store, contact, report, threePhotos, afterSuccessfulSubmission = callback)
                is SubmissionResult.Submitted
        )
        assertEquals(1, refreshes)
        assertTrue(
            machine(client).submitPhotos(store, contact, report, threePhotos, afterSuccessfulSubmission = callback)
                is SubmissionResult.AlreadySubmitted
        )
        assertEquals(1, refreshes)
    }

    @Test
    fun submitPhotosDoesNotMutateTheCallersContactMap() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        val caller = LinkedHashMap(contact)
        val snapshot = LinkedHashMap(caller)
        machine(client).submitPhotos(store, caller, report, threePhotos)
        assertEquals(snapshot, caller)
    }

    @Test
    fun completedStateWithADifferentPhotoSetIsRefusedNotShortCircuited() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        store.write(
            mutableMapOf(
                "version" to 2,
                "status" to "complete",
                "text_sha256" to textId,
                "case" to mapOf("CaseIdInCRM" to "synthetic-case-id"),
                "photos" to journal("attached"),
            )
        )
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, report, threePhotos.subList(0, 2))
        }
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun changedReportTextIsRefusedAgainstAnExistingJournal() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        store.write(
            mutableMapOf(
                "version" to 2,
                "status" to "created",
                "text_sha256" to textId,
                "case" to mutableMapOf(
                    "CaseIdInCRM" to "synthetic-case-id",
                    "WebCaseIdInCRM" to "synthetic-webcase-id",
                ),
                "photos" to journal(),
            )
        )
        val other = MunicipalPayloads.buildReport(
            MunicipalPayloads.ReportFields("דיווח סינתטי אחר.", "1145", "1630", "הפלמח", "900", 220000, 630500)
        )
        assertThrows(MunicipalReviewNeededException::class.java) {
            machine(client).submitPhotos(store, contact, other, threePhotos)
        }
        assertEquals(emptyList<String>(), client.calls)
    }

    @Test
    fun outgoingNamesAreGeneratedAndPersistedNeverTakenFromTheCaller() {
        val client = FakeClient()
        val store = InMemorySubmissionStateStore()
        SubmissionStateMachine(client).submitPhotos(store, contact, report, threePhotos)
        val names = client.attached
        assertEquals(3, names.size)
        assertEquals(3, names.toSet().size)
        assertTrue(names.all { Regex("[0-9a-f-]{36}\\.jpg").matches(it) })
        assertEquals(names, photoStatuses(store).map { it.first })
    }
}
