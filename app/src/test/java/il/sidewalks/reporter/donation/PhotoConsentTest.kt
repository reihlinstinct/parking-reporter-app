package il.sidewalks.reporter.donation

import org.junit.Assert.*
import org.junit.Test

class PhotoConsentTest {
    private val photo = TrainingPhoto("a".repeat(64), "synthetic-processing-v1")
    private val terms = TrainingTerms("synthetic-v1", "b".repeat(64))
    private fun review() = PhotoConsent(photo, terms)
    private fun confirm(review: PhotoConsent) = review.confirmExplicitly("synthetic-contribution-id",
        photo.derivativeSha256, terms.sha256, "he", 1000)
    private fun fails(action: () -> Unit) {
        try { action(); fail("Must refuse") } catch (e: Exception) { /* expected */ }
    }
    @Test fun defaultOffAndIndependentPerPhotoNoGlobalConsent() {
        val first = review(); val second = review()
        assertEquals(PhotoConsent.State.OFF, first.state)
        assertNull(first.receiptForCurrentReview())
        confirm(first)
        assertEquals(PhotoConsent.State.OFF, second.state)
        assertNull(second.receiptForCurrentReview())
        assertEquals(PhotoConsent.State.OFF, review().state)
    }
    @Test fun receiptBindsExactShownPhotoAndVersionedTerms() {
        val review = review(); val receipt = confirm(review)
        assertEquals(PhotoConsent.State.GRANTED, review.state)
        assertSame(receipt, review.receiptForCurrentReview())
        assertEquals(photo.derivativeSha256, receipt.derivativeSha256)
        assertEquals(photo.processingVersion, receipt.processingVersion)
        assertEquals(terms.sha256, receipt.termsSha256)
        assertEquals(terms.version, receipt.termsVersion)
        assertEquals("he", receipt.locale)
        assertEquals(1000L, receipt.grantedAtEpochMillis)
        assertEquals("TrainingConsentReceipt(redacted)", receipt.toString())
        fails { confirm(review) }
    }
    @Test fun mismatchedReviewNeverGrants() {
        val review = review()
        fails { review.confirmExplicitly("synthetic-contribution-id", "c".repeat(64), terms.sha256, "he", 1) }
        fails { review.confirmExplicitly("synthetic-contribution-id", photo.derivativeSha256, "c".repeat(64), "he", 1) }
        assertEquals(PhotoConsent.State.OFF, review.state)
        assertNull(review.receiptForCurrentReview())
    }
    @Test fun withdrawalIsFinalForReviewAndNeverClaimsServerDeletion() {
        for (grantFirst in listOf(false, true)) {
            val review = review()
            if (grantFirst) confirm(review)
            review.withdraw(); review.withdraw()
            assertEquals(PhotoConsent.State.WITHDRAWN, review.state)
            assertNull(review.receiptForCurrentReview())
            fails { confirm(review) }
            assertEquals(PhotoConsent.State.OFF, review().state)
        }
    }
    @Test fun malformedContextsRefusedNoGrant() {
        for ((id, locale, time) in listOf(Triple("short", "he", 1L),
            Triple("synthetic-contribution-id", "", 1L), Triple("synthetic-contribution-id", "he", 0L))) {
            val review = review()
            fails { review.confirmExplicitly(id, photo.derivativeSha256, terms.sha256, locale, time) }
            assertEquals(PhotoConsent.State.OFF, review.state)
        }
        fails { TrainingTerms("", terms.sha256) }
        fails { TrainingPhoto("bad", "v1") }
    }
}
