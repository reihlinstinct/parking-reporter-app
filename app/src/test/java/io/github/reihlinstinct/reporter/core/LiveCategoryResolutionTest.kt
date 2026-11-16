package io.github.reihlinstinct.reporter.core

import java.io.File
import org.junit.Test

/**
 * Resolves the category picker label for a live E2E run from the staged
 * TRIAL_SUBJECT code - the SAME code the engine trial files with - so the
 * UI run can never carry a label that disagrees with the staged subject.
 * Runs only when the live workflow sets LIVE_CATEGORY_PATH; the regular
 * suite returns immediately. The resolved Hebrew label is public taxonomy,
 * safe to print.
 */
class LiveCategoryResolutionTest {
    @Test fun resolveTrialSubjectLabel() {
        val out = System.getenv("LIVE_CATEGORY_PATH") ?: return
        // TRIAL_SUBJECT is mandatory for a live run, mirroring the engine trial's
        // env() helper: fail closed when the secret is absent rather than guessing
        // a category for the approved text.
        val code = System.getenv("TRIAL_SUBJECT")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("missing env TRIAL_SUBJECT")
        val label = resolveSelector(code)
        File(out).writeText("LIVE_CATEGORY label=$label\n")
        println("LIVE_CATEGORY label=$label")
    }
    /** Numeric city codes are selectors, not an enum fallback or a free label.
     * The real UI must still find this exact code in its fresh city catalog. */
    private fun resolveSelector(code: String): String {
        val numeric = ReportCategory.from(code)?.municipalSubjectCode ?: code
        val file = listOf(
            File("src/main/assets/" + MunicipalSubjects.ASSET_PATH),
            File("app/src/main/assets/" + MunicipalSubjects.ASSET_PATH),
        ).first { it.isFile }
        val catalog = file.inputStream().use { MunicipalSubjects.load(it) }
        if (!Regex("[0-9]+").matches(numeric) || !catalog.hasCode(numeric))
            throw AssertionError("unsupported trial subject")
        return numeric
    }

    @Test fun redWhite1144SelectsItselfNeverSidewalk1145() {
        org.junit.Assert.assertEquals("1144", resolveSelector("1144"))
        org.junit.Assert.assertNotEquals("1145", resolveSelector("1144"))
    }

    @Test fun legacyAliasesKeepTheirOwnMunicipalCode() {
        ReportCategory.entries.forEach {
            org.junit.Assert.assertEquals(it.municipalSubjectCode, resolveSelector(it.code))
        }
    }

    @Test fun unknownCodesAndFreeLabelsFailClosed() {
        for (value in listOf("999999999", "", "1144 OR 1145", "חניה אסורה")) {
            val failure = runCatching { resolveSelector(value) }.exceptionOrNull()
            org.junit.Assert.assertTrue("must refuse unknown selector", failure is AssertionError)
            org.junit.Assert.assertEquals("unsupported trial subject", failure?.message)
        }
    }

}
