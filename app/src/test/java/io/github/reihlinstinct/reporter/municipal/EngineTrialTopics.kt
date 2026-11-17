package io.github.reihlinstinct.reporter.municipal

import io.github.reihlinstinct.reporter.core.MunicipalSubjects
import io.github.reihlinstinct.reporter.core.ReportCategory

/** Test-source workflow boundary only. No fallback topic or free-text label. */
internal object EngineTrialTopics {
    fun resolve(selector: String, catalog: MunicipalSubjects): String {
        require(catalog.computedFingerprint() == catalog.fingerprintSha256) { "topic catalog fingerprint mismatch" }
        val code = ReportCategory.from(selector)?.municipalSubjectCode ?: selector
        require(Regex("[0-9]+").matches(code) && catalog.hasCode(code)) { "unsupported trial subject" }
        return code
    }
}
