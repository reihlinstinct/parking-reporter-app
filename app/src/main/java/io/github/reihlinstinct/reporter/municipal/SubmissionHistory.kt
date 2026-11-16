package io.github.reihlinstinct.reporter.municipal

/**
 * The identifier a sent report carries in the report history (owner 2026-11-16): the case number the city
 * returned (CaseNumberInCRM) when there is one, otherwise WebCaseIdInCRM, both exactly as returned. Read from
 * the saved submission state, so it is the same data the success screen showed. Null when the report has no
 * created case yet or the state carries neither value. Never looked up, derived or invented.
 */
internal fun historyIdentifier(state: Map<String, Any?>): String? {
    if (state["status"] !in setOf("created", "attaching", "complete")) return null
    val case = state["case"] as? Map<*, *> ?: return null
    fun value(key: String) = (case[key] as? String)?.takeIf { it.isNotBlank() }
    return value("CaseNumberInCRM") ?: value("WebCaseIdInCRM")
}
