package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gate semantics for the batch UI lane (#548/#558). Fake transport, synthetic values. */
class BatchWriteGateTest {
    private class Fake(var failAttach: Boolean = false) : MunicipalClient {
        val calls = mutableListOf<String>()
        override fun login(): String { calls.add("login"); return "synthetic-token" }
        override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> {
            calls.add("contact"); return mapOf("ContactIdInCRM" to "c")
        }
        override fun createCase(token: String, contact: LinkedHashMap<String, Any?>, report: LinkedHashMap<String, Any?>): Map<String, Any?> {
            calls.add("create")
            return mapOf("CaseIdInCRM" to "case", "WebCaseIdInCRM" to "web")
        }
        override fun attachPhoto(token: String, caseIdInCRM: String, webCaseIdInCRM: String, fileName: String, jpeg: ByteArray) {
            calls.add("attach")
            if (failAttach) throw MunicipalApiException("synthetic")
        }
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)
    private val contact = LinkedHashMap<String, Any?>()
    private val report = LinkedHashMap<String, Any?>()

    @Test fun readOnlyLoginNeedsNoAuthorization() {
        val real = Fake()
        val gate = GatedMunicipalClient(real, "r1", { _, _, _, _ -> false })
        assertEquals("synthetic-token", gate.login())
        assertEquals(listOf("login"), real.calls)
    }

    @Test fun unauthorizedCreateSendsNothingAndClosesTheGate() {
        val real = Fake()
        val gate = GatedMunicipalClient(real, "r1", { _, _, _, _ -> false })
        assertThrows(MunicipalReviewNeededException::class.java) { gate.upsertContact("t", contact) }
        assertThrows(MunicipalReviewNeededException::class.java) { gate.upsertContact("t", contact) }
        assertEquals(emptyList<String>(), real.calls)
    }

    @Test fun authorizerErrorCountsAsRefusal() {
        val real = Fake()
        val gate = GatedMunicipalClient(real, "r1", { _, _, _, _ -> throw IllegalStateException("channel down") })
        assertThrows(MunicipalReviewNeededException::class.java) { gate.upsertContact("t", contact) }
        assertEquals(emptyList<String>(), real.calls)
    }

    @Test fun authorizedFlowCreatesOnceThenAttachesInSequence() {
        val real = Fake()
        val asked = mutableListOf<Triple<String, Int, String?>>()
        val results = mutableListOf<String>()
        val gate = GatedMunicipalClient(real, "r1",
            { op, rid, seq, sha -> assertEquals("r1", rid); asked.add(Triple(op, seq, sha)); true },
            { op, seq, _, ok -> results.add("$op:$seq:$ok") })
        gate.upsertContact("t", contact)
        val case = gate.createCase("t", contact, report)
        gate.attachPhoto("t", case["CaseIdInCRM"] as String, case["WebCaseIdInCRM"] as String, "a.jpg", jpeg)
        gate.attachPhoto("t", "case", "web", "b.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 2))
        assertEquals(listOf("contact", "create", "attach", "attach"), real.calls)
        assertEquals(listOf("create" to 0, "attach" to 0, "attach" to 1), asked.map { it.first to it.second })
        assertEquals(io.github.reihlinstinct.reporter.intake.EncryptedPackage.sha256(jpeg), asked[1].third)
        assertEquals(listOf("create:0:true", "attach:0:true", "attach:1:true"), results)
    }

    @Test fun secondCreateAndForeignCaseAreRefused() {
        val real = Fake()
        val gate = GatedMunicipalClient(real, "r1", { _, _, _, _ -> true })
        gate.upsertContact("t", contact)
        gate.createCase("t", contact, report)
        assertThrows(MunicipalReviewNeededException::class.java) { gate.createCase("t", contact, report) }
        assertEquals(listOf("contact", "create"), real.calls)
        val other = GatedMunicipalClient(Fake(), "r1", { _, _, _, _ -> true })
        assertThrows(MunicipalReviewNeededException::class.java) { other.attachPhoto("t", "case", "web", "a.jpg", jpeg) }
        val real2 = Fake()
        val g2 = GatedMunicipalClient(real2, "r1", { _, _, _, _ -> true })
        g2.upsertContact("t", contact); g2.createCase("t", contact, report)
        assertThrows(MunicipalReviewNeededException::class.java) { g2.attachPhoto("t", "other-case", "web", "a.jpg", jpeg) }
        assertEquals(listOf("contact", "create"), real2.calls)
    }

    @Test fun createCaseWithoutAuthorizedContactIsRefused() {
        val real = Fake()
        val gate = GatedMunicipalClient(real, "r1", { _, _, _, _ -> true })
        assertThrows(MunicipalReviewNeededException::class.java) { gate.createCase("t", contact, report) }
        assertEquals(emptyList<String>(), real.calls)
    }

    @Test fun failedAttachPoisonsEveryLaterWrite() {
        val real = Fake(failAttach = true)
        val results = mutableListOf<String>()
        val gate = GatedMunicipalClient(real, "r1", { _, _, _, _ -> true }, { op, seq, _, ok -> results.add("$op:$seq:$ok") })
        gate.upsertContact("t", contact); gate.createCase("t", contact, report)
        assertThrows(MunicipalApiException::class.java) { gate.attachPhoto("t", "case", "web", "a.jpg", jpeg) }
        real.failAttach = false
        assertThrows(MunicipalReviewNeededException::class.java) { gate.attachPhoto("t", "case", "web", "b.jpg", jpeg) }
        assertEquals(listOf("contact", "create", "attach"), real.calls)
        assertTrue(results.last() == "attach:0:false")
    }

    @Test fun wholeAppStateMachineCannotWriteWithoutAuthorization() {
        val real = Fake()
        val gate = GatedMunicipalClient(real, "r1", { op, _, _, _ -> false })
        val store = InMemorySubmissionStateStore()
        assertThrows(MunicipalReviewNeededException::class.java) {
            SubmissionStateMachine(gate).submit(store, contact, report, jpeg)
        }
        assertEquals(listOf("login"), real.calls)
    }
}
