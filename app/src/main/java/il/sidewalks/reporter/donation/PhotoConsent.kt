package il.sidewalks.reporter.donation

/** Inactive per-photo consent model. No persistence, network, terms text or app
 * caller. A local grant does not authorize upload until all legal/pilot gates
 * and durable encrypted receipt checks are independently satisfied. */
class TrainingTerms(val version: String, val sha256: String) {
    init {
        require(version.isNotBlank()) { "Terms version required" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid terms hash" }
    }
    override fun toString() = "TrainingTerms(redacted)"
}

class TrainingPhoto(val derivativeSha256: String, val processingVersion: String) {
    init {
        require(derivativeSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid derivative hash" }
        require(processingVersion.isNotBlank()) { "Processing version required" }
    }
    override fun toString() = "TrainingPhoto(redacted)"
}

/** Proposed task/release scope text is version-bound by terms hash, not inferred
 * from municipal permission or a global preference. No preselected state. */
class PhotoConsent(private val photo: TrainingPhoto, private val terms: TrainingTerms) {
    enum class State { OFF, GRANTED, WITHDRAWN }
    var state: State = State.OFF
        private set
    private var grant: Receipt? = null

    class Receipt internal constructor(val contributionId: String,
        val derivativeSha256: String, val processingVersion: String,
        val termsVersion: String, val termsSha256: String,
        val locale: String, val grantedAtEpochMillis: Long) {
        override fun toString() = "TrainingConsentReceipt(redacted)"
    }

    /** Only called by a later explicit per-photo confirmation, not by city Send.
     * ID generation/storage belongs to a later reviewed durable queue. */
    fun confirmExplicitly(contributionId: String, shownPhotoSha256: String,
        shownTermsSha256: String, locale: String, grantedAtEpochMillis: Long): Receipt {
        check(state == State.OFF) { "New consent review required" }
        require(contributionId.matches(Regex("[a-zA-Z0-9_-]{16,80}"))) { "Invalid contribution ID" }
        require(shownPhotoSha256 == photo.derivativeSha256) { "Photo review mismatch" }
        require(shownTermsSha256 == terms.sha256) { "Terms review mismatch" }
        require(locale.isNotBlank() && grantedAtEpochMillis > 0) { "Consent context required" }
        val receipt = Receipt(contributionId, photo.derivativeSha256, photo.processingVersion,
            terms.version, terms.sha256, locale, grantedAtEpochMillis)
        grant = receipt
        state = State.GRANTED
        return receipt
    }

    /** Irreversible within this review instance; offline withdrawal is not a
     * server deletion receipt. Uploaded withdrawal needs a separate protocol. */
    fun withdraw() { grant = null; state = State.WITHDRAWN }

    /** Re-import/restart/terms or processing changes require a new OFF review.
     * No API restores GRANTED from an unverified persisted boolean. */
    fun receiptForCurrentReview(): Receipt? = grant.takeIf { state == State.GRANTED }
}
