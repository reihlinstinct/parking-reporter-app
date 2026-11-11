package il.sidewalks.reporter.municipal

import il.sidewalks.reporter.core.ReportCategory
import il.sidewalks.reporter.core.ReviewDraft
import il.sidewalks.reporter.security.ReporterSettings
import java.io.File

/**
 * M-B glue between an approved review draft and the one-attempt
 * SubmissionStateMachine. Android-free on purpose (JVM-tested); the UI maps
 * outcomes to Hebrew copy - this class never produces user-facing text.
 *
 * Hard rules preserved here:
 * - the complete shown draft and original evidence hash must match;
 * - the app passes the exact previewed, EXIF-free outgoing photo; the JVM
 *   workflow engine explicitly retains its original-photo behavior;
 * - every municipal failure maps to a typed outcome; nothing is retried
 *   silently and no state is deleted here.
 */
class SubmissionCoordinator(
    private val clientFactory: (ReporterSettings) -> MunicipalClient,
    private val streets: StreetDirectory,
    private val storeFor: (stableId: String) -> SubmissionStateStore,
    private val activeTopicCodes: (() -> Set<String>)? = null,
    private val afterSuccessfulSubmission: ((MunicipalClient, String) -> Unit)? = null,
) {
    /** Snapshot of the exact reviewed outgoing bytes, bound to their source original. */
    class OutgoingPhoto(jpeg: ByteArray, val originalHash: String) {
        private val bytes = jpeg.copyOf()
        val hash: String = il.sidewalks.reporter.intake.EncryptedPackage.sha256(bytes)
        fun snapshot(): ByteArray = bytes.copyOf()
    }

    sealed interface Outcome {
        /** The city accepted the case; [caseNumber] is the municipal number. */
        data class Submitted(val caseNumber: String?) : Outcome

        /** This exact report was already submitted earlier (state short-circuit). */
        data class AlreadySubmitted(val caseNumber: String?) : Outcome

        /**
         * Refused before or during submission: [reason] is machine-readable
         * for the UI's Hebrew mapping; [detail] is the municipality's or
         * validator's exact answer, shown verbatim where the UI allows.
         * [missingFields] carries the unset municipal credential IDs (never
         * values) for MISSING_CREDENTIALS, so the UI needs no string parsing.
         */
        data class Blocked(
            val reason: Reason,
            val detail: String,
            val missingFields: List<String> = emptyList(),
        ) : Outcome
    }

    enum class Reason {
        NOT_READY,
        PASSPORT_DISCLOSURE_REQUIRED,
        MISSING_CREDENTIALS,
        UNSUPPORTED_CATEGORY,
        ADDRESS_UNRESOLVED,
        INPUT_INVALID,
        /** The app itself failed before/while talking to the city - NOT a municipal answer. */
        LOCAL_FAILURE,
        /** The private address lookup is not installed on this device. */
        LOOKUP_NOT_INSTALLED,
        LOOKUP_MISMATCH,
        MUNICIPAL_REVIEW_NEEDED,
        MUNICIPAL_API_FAILURE,
    }

    /** In-app send requires the exact previewed derivative. No original fallback. */
    fun submit(draft: ReviewDraft, settings: ReporterSettings, originalJpeg: File,
               outgoingPhoto: OutgoingPhoto): Outcome =
        submitInternal(draft, settings, originalJpeg, outgoingPhoto)

    /** Separate JVM workflow path: preserve its owner-approved original-photo policy. */
    fun submitOriginalForWorkflow(draft: ReviewDraft, settings: ReporterSettings,
                                  originalJpeg: File): Outcome =
        submitInternal(draft, settings, originalJpeg, null)

    @Suppress("UNCHECKED_CAST")
    private fun submitInternal(
        draft: ReviewDraft,
        settings: ReporterSettings,
        originalJpeg: File,
        outgoingPhoto: OutgoingPhoto?,
    ): Outcome {
        if (!draft.isReady()) return Outcome.Blocked(Reason.NOT_READY, "draft not ready")
        // Tab review (#266): never send a photo whose bytes do not match the
        // draft's evidence hash - a mis-paired draft and original would file
        // the wrong photo. Refuse before any state write or network call.
        val originalBytes = try { originalJpeg.readBytes() } catch (_: Exception) {
            return Outcome.Blocked(Reason.INPUT_INVALID, "original unreadable")
        }
        val photoHash = il.sidewalks.reporter.intake.EncryptedPackage.sha256(originalBytes)
        if (photoHash != draft.evidence.originalSha256)
            return Outcome.Blocked(Reason.INPUT_INVALID, "photo bytes do not match the draft evidence hash")
        val outgoingBytes = outgoingPhoto?.snapshot()
        if (outgoingPhoto != null && (outgoingPhoto.originalHash != photoHash ||
                outgoingBytes == null || outgoingBytes.isEmpty() || outgoingBytes.size > 1_100_000))
            return Outcome.Blocked(Reason.INPUT_INVALID, "outgoing photo does not match the reviewed evidence")
        if (!PassportSubmission.isSupportedPhone(settings.phone)) return Outcome.Blocked(Reason.INPUT_INVALID, "unsupported reporter phone")
        if (!PassportSubmission.hasReviewedDisclosure(draft.exactHebrewText, settings))
            return Outcome.Blocked(Reason.PASSPORT_DISCLOSURE_REQUIRED, "passport disclosure must be saved before sending")
        if (!settings.hasMunicipalCredentials()) {
            // Name exactly which values are missing (constant field IDs, never
            // values) so the UI can say it (owner 2026-11-09).
            return Outcome.Blocked(Reason.MISSING_CREDENTIALS,
                "municipal credentials absent: " + settings.missingMunicipalFields().joinToString(", "),
                missingFields = settings.missingMunicipalFields())
        }
        val topicCode = il.sidewalks.reporter.core.TopicIdentity.code(draft.subject)
            ?: return Outcome.Blocked(Reason.UNSUPPORTED_CATEGORY, draft.subject)
        // App path validates against its last-good city cache. Legacy JVM
        // workflow keeps only its existing four supported category codes.
        val allowed = activeTopicCodes?.invoke() ?: ReportCategory.entries.map { it.municipalSubjectCode }.toSet()
        if (topicCode !in allowed) return Outcome.Blocked(Reason.UNSUPPORTED_CATEGORY, draft.subject)
        val gps = if (draft.confirmedLatitude != null && draft.confirmedLongitude != null) {
            draft.confirmedLatitude to draft.confirmedLongitude
        } else {
            null
        }
        // A confirmed address line always carries street + house. Without a
        // house number we must NOT fall back to the GPS-nearest house: the
        // app would file a street and number the user never confirmed.
        val address = splitAddress(draft.address)
            ?: return Outcome.Blocked(
                Reason.ADDRESS_UNRESOLVED, "the confirmed address has no house number")
        val resolved = try {
            streets.resolve(address.first, address.second, gps)
        } catch (e: MunicipalInputException) {
            return Outcome.Blocked(Reason.ADDRESS_UNRESOLVED, e.message ?: "address unresolved")
        }
        val contact = try {
            MunicipalPayloads.buildContact(MunicipalPayloads.Reporter(
                settings.firstName, settings.lastName, PassportSubmission.phoneForPayload(settings), if (settings.passportMode) PassportSubmission.COMPATIBILITY_ID else settings.idNumber, settings.email))
        } catch (e: MunicipalInputException) {
            return Outcome.Blocked(Reason.INPUT_INVALID, e.message ?: "reporter invalid")
        }
        val report = try {
            MunicipalPayloads.buildReport(MunicipalPayloads.ReportFields(
                description = draft.exactHebrewText,
                subjectCode = topicCode,
                streetCode = resolved.streetCode,
                streetName = resolved.streetName,
                houseNumber = resolved.house,
                coordinateX = resolved.x,
                coordinateY = resolved.y,
            ))
        } catch (e: MunicipalInputException) {
            return Outcome.Blocked(Reason.INPUT_INVALID, e.message ?: "report invalid")
        }
        // Only the Android app provides a derivative. The workflow-staged JVM
        // engine keeps its approved original unchanged (owner scope #201).
        val jpeg = outgoingBytes ?: originalBytes
        val client = try {
            clientFactory(settings)
        } catch (e: MunicipalInputException) {
            return Outcome.Blocked(Reason.MISSING_CREDENTIALS, e.message ?: "credentials invalid")
        }
        val store = storeFor(draft.evidence.stableId)
        val result = try {
            SubmissionStateMachine(client).submit(store, contact, report, jpeg,
                photoSha256 = il.sidewalks.reporter.intake.EncryptedPackage.sha256(jpeg),
                afterSuccessfulSubmission = afterSuccessfulSubmission)
        } catch (e: MunicipalReviewNeededException) {
            return Outcome.Blocked(Reason.MUNICIPAL_REVIEW_NEEDED, e.message ?: "review needed")
        } catch (e: IllegalStateException) {
            return Outcome.Blocked(
                Reason.MUNICIPAL_REVIEW_NEEDED, e.message ?: "saved state unreadable")
        } catch (e: MunicipalApiException) {
            return Outcome.Blocked(Reason.MUNICIPAL_API_FAILURE, e.message ?: "municipal failure")
        } catch (e: MunicipalInputException) {
            return Outcome.Blocked(Reason.INPUT_INVALID, e.message ?: "input invalid")
        }
        val caseNumber = result.case["CaseNumberInCRM"] as? String
        return when (result) {
            is SubmissionResult.Submitted -> Outcome.Submitted(caseNumber)
            is SubmissionResult.AlreadySubmitted -> Outcome.AlreadySubmitted(caseNumber)
        }
    }

    companion object {
        private val HOUSE_SUFFIX = Regex("^(.*\\S)\\s+(" + HOUSE_NUMBER.pattern + ")$")

        /**
         * Splits a confirmed "street house" line into (street, house). A line
         * with no trailing house number returns null and the coordinator
         * refuses - the GPS-only resolver path exists for parity but is not
         * reachable from a confirmed draft.
         */
        fun splitAddress(address: String): Pair<String, String>? {
            val match = HOUSE_SUFFIX.matchEntire(address.trim()) ?: return null
            return match.groupValues[1] to match.groupValues[2]
        }
    }
}
