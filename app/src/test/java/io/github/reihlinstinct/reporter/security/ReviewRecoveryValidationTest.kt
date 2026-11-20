package io.github.reihlinstinct.reporter.security

import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReviewRecoveryValidationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "synthetic photo bytes".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val baseline = ReviewDraft(Evidence("synthetic", hash, null, null, null), "", "", "", "", emptySet())
    private val binding = ReviewRecoveryEnvelope.Binding("a".repeat(32), "synthetic", baseline.digest(), hash,
        7, 199, 4, 100, 100 + ReviewRecoveryEnvelope.LIFETIME_MS)
    private fun valid(draft: ReviewDraft = baseline, revision: Long = 7, id: String = "synthetic",
        originalHash: String = hash, path: String = "originals/synthetic.jpg", journal: Boolean = false) =
        ReviewRecoveryValidation.matches(binding, draft, revision, id, originalHash, path, temporary.root, journal)
    private fun prepare(): File = File(temporary.root, "originals/synthetic.jpg").apply { parentFile.mkdirs(); writeBytes(bytes) }

    @Test fun freshLedgerAndActualOriginalHashAreRequired() {
        prepare(); assertTrue(valid())
        assertFalse(valid(revision = 8)); assertFalse(valid(id = "other"))
        assertFalse(valid(originalHash = "b".repeat(64)))
        assertFalse(valid(draft = baseline.copy(exactHebrewText = "changed saved draft")))
        assertFalse(valid(journal = true))
    }

    @Test fun missingAndChangedOriginalRejectWithoutChangingSavedFiles() {
        val original = prepare(); original.writeText("changed"); assertFalse(valid())
        assertEquals("changed", original.readText()); original.delete(); assertFalse(valid())
    }

    @Test fun ledgerPathCannotEscapeOriginalsEvenWithMatchingBytes() {
        prepare()
        File(temporary.root, "outside.jpg").writeBytes(bytes)
        assertFalse(valid(path = "outside.jpg")); assertFalse(valid(path = "originals/../outside.jpg"))
        assertFalse(valid(path = "originals/../../outside.jpg"))
        File(temporary.root, "originals-neighbor").mkdir()
        File(temporary.root, "originals-neighbor/synthetic.jpg").writeBytes(bytes)
        assertFalse(valid(path = "originals-neighbor/synthetic.jpg"))
    }
}
