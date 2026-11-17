package io.github.reihlinstinct.reporter.municipal

import io.github.reihlinstinct.reporter.core.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EngineTrialTopicsTest {
    private fun catalog(): MunicipalSubjects = listOf(File("src/main/assets/" + MunicipalSubjects.ASSET_PATH),
        File("app/src/main/assets/" + MunicipalSubjects.ASSET_PATH)).first { it.isFile }.inputStream().use { MunicipalSubjects.load(it) }

    @Test fun everyCapturedNumericCodeAndLegacyAliasResolvesWithoutDefault() {
        val catalog = catalog()
        catalog.subjects.forEach { assertEquals(it.code, EngineTrialTopics.resolve(it.code, catalog)) }
        ReportCategory.entries.forEach { assertEquals(it.municipalSubjectCode, EngineTrialTopics.resolve(it.code, catalog)) }
        assertEquals("1072", EngineTrialTopics.resolve("1072", catalog))
        assertFalse(PlatePolicy.required("1072", catalog.subjects, emptySet()))
        assertTrue(PlatePolicy.required("1145", catalog.subjects, emptySet()))
    }

    @Test fun resolvedPlateFreeAndPlateRequiredDraftsAreActuallyReady() {
        val catalog = catalog()
        for (selector in listOf("1072", "broken_sign", "1145", "sidewalk_parking")) {
            val code = EngineTrialTopics.resolve(selector, catalog)
            val subject = EngineTrialTopics.draftSubject(code)
            assertEquals(code, TopicIdentity.code(subject))
            val required = PlatePolicy.required(code, catalog.subjects, emptySet())
            val draft = ReviewDraft(Evidence("synthetic", "a".repeat(64),
                "2026-01-01T12:00:00+02:00", 31.77, 35.21),
                if (required) "1234567" else "", "synthetic street 12", subject,
                if (required) "synthetic plate 1234567" else "synthetic description", emptySet(),
                plateRequired = required)
            assertTrue("resolved draft must be ready", draft.isReady())
            assertTrue(PlatePolicy.descriptionContainsPlate(draft))
            assertFalse("raw numeric subject is not a draft identity", draft.copy(subject = code).isReady())
        }
    }

    @Test fun missingUnknownLabelsAndMalformedNumericCodesRefuse() {
        val catalog = catalog()
        for (input in listOf("", "999999999", "1072 OR 1145", " 1072", "גרפיטי")) {
            assertThrows(IllegalArgumentException::class.java) { EngineTrialTopics.resolve(input, catalog) }
        }
    }

    @Test fun modifiedCatalogFingerprintRefusesEvenKnownCode() {
        val catalog = catalog()
        assertThrows(IllegalArgumentException::class.java) {
            EngineTrialTopics.resolve("1072", catalog.copy(fingerprintSha256 = "0".repeat(64)))
        }
    }
}
