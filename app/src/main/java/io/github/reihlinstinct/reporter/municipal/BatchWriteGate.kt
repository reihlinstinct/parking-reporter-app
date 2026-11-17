package io.github.reihlinstinct.reporter.municipal

/**
 * Write boundary for the batch UI lane (#548, #558). Inactive: nothing in the
 * production app constructs it.
 *
 * The all-in-one app flow (SubmissionCoordinator -> SubmissionStateMachine ->
 * MunicipalClient) runs unchanged, but the client it is given is a
 * [GatedMunicipalClient]. Every municipal WRITE (contact upsert + CreateCase
 * as one "create" boundary, then each AddFileToCase as an "attach" boundary)
 * must first be authorized by the batch controller through
 * [BatchWriteAuthorizer], which the controller only grants after the shared
 * remote journal acknowledged the matching intent. Read-only Login passes
 * through.
 *
 * Rules, all fail-closed:
 * - a refusal, an authorizer error, or a failed/uncertain write poisons the
 *   gate: every later write is refused, never retried;
 * - "create" is authorized once; a second CreateCase is refused;
 * - an attach needs the case this gate's CreateCase returned and its own
 *   authorization; the sequence number is strictly increasing from 0;
 * - the case of an attach must equal the case the gate created, so the app
 *   can never write to a case the journal does not know.
 */
fun interface BatchWriteAuthorizer {
    /** True only when the controller's durable intent for exactly this write was acknowledged. */
    fun authorize(operation: String, reportId: String, sequence: Int, outgoingSha256: String?): Boolean
}

class GatedMunicipalClient(
    private val real: MunicipalClient,
    private val reportId: String,
    private val authorizer: BatchWriteAuthorizer,
    private val onWriteResult: (operation: String, sequence: Int, outgoingSha256: String?, success: Boolean) -> Unit =
        { _, _, _, _ -> },
) : MunicipalClient {
    private var poisoned = false
    private var contactUpserted = false
    private var caseCreated = false
    private var createdCaseIds: Pair<String, String>? = null
    private var nextAttach = 0

    private fun refuse(message: String): Nothing {
        poisoned = true
        throw MunicipalReviewNeededException(message)
    }

    private fun authorized(operation: String, sequence: Int, sha: String?) {
        if (poisoned) refuse("Batch write gate closed after an earlier refusal or failure.")
        val ok = try {
            authorizer.authorize(operation, reportId, sequence, sha)
        } catch (_: Exception) {
            refuse("Batch write authorization failed; no write was sent.")
        }
        if (!ok) refuse("Batch controller did not authorize this write; none was sent.")
    }

    override fun subjectCategories(token: String) = real.subjectCategories(token)

    override fun login(): String = real.login()

    override fun upsertContact(token: String, contact: LinkedHashMap<String, Any?>): Map<String, Any?> {
        if (contactUpserted) refuse("A second contact write is not allowed in one batch report.")
        authorized("create", 0, null)
        contactUpserted = true
        try {
            return real.upsertContact(token, contact)
        } catch (e: Exception) {
            poisoned = true
            onWriteResult("create", 0, null, false)
            throw e
        }
    }

    override fun createCase(
        token: String,
        contact: LinkedHashMap<String, Any?>,
        report: LinkedHashMap<String, Any?>,
    ): Map<String, Any?> {
        if (poisoned) refuse("Batch write gate closed after an earlier refusal or failure.")
        if (!contactUpserted || caseCreated) refuse("CreateCase is only allowed once, right after its authorized contact write.")
        caseCreated = true
        try {
            val result = real.createCase(token, contact, report)
            val id = result["CaseIdInCRM"] as? String
            val web = result["WebCaseIdInCRM"] as? String
            if (!id.isNullOrEmpty() && !web.isNullOrEmpty()) createdCaseIds = id to web
            onWriteResult("create", 0, null, createdCaseIds != null)
            return result
        } catch (e: Exception) {
            poisoned = true
            onWriteResult("create", 0, null, false)
            throw e
        }
    }

    override fun attachPhoto(token: String, caseIdInCRM: String, webCaseIdInCRM: String, fileName: String, jpeg: ByteArray) {
        if (poisoned) refuse("Batch write gate closed after an earlier refusal or failure.")
        if (createdCaseIds != caseIdInCRM to webCaseIdInCRM) {
            refuse("Attach refused: the case is not the one this gate created.")
        }
        val sequence = nextAttach
        val sha = io.github.reihlinstinct.reporter.intake.EncryptedPackage.sha256(jpeg)
        authorized("attach", sequence, sha)
        nextAttach++
        try {
            real.attachPhoto(token, caseIdInCRM, webCaseIdInCRM, fileName, jpeg)
        } catch (e: Exception) {
            poisoned = true
            onWriteResult("attach", sequence, sha, false)
            throw e
        }
        onWriteResult("attach", sequence, sha, true)
    }
}
