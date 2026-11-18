package io.github.reihlinstinct.reporter.municipal

/** Keep the exact pair returned by CreateCase. A zero CRM ID is normal while
 * the municipal web case is queued; AddFileToCase uses its nonzero web ID.
 * Never substitute the web ID into the CRM field or invent either value.
 */
object MunicipalCaseReference {
    fun requireAttachable(receipt: Map<String, Any?>): Pair<String, String> {
        val crm = receipt["CaseIdInCRM"] as? String
        val web = receipt["WebCaseIdInCRM"] as? String ?: ""
        require(!crm.isNullOrBlank())
        fun usable(value: String) = value.trim('0', '-', ' ').isNotEmpty()
        require(usable(crm) || usable(web))
        return crm to web
    }
}
