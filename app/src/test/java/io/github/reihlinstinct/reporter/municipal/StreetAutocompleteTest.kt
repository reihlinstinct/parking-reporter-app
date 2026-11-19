package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.*
import org.junit.Test

class StreetAutocompleteTest {
    private fun directory(): StreetDirectory = StreetDirectory.load("""{"version":1,"streets":[
        {"StreetCode":"1","StreetName":"רחוב הנביאים","aliases":["הנביאים"]},
        {"StreetCode":"2","StreetName":"שדרות בן צבי","aliases":["בן-צבי","משותף"]},
        {"StreetCode":"3","StreetName":"רחוב אחר","aliases":["משותף"]}],"addresses":[[31.77,35.21,"1","שדרות בן צבי"]]}""")
    @Test fun canonicalNamesAreLocalBoundedAndDeduplicated() {
        assertEquals(listOf("רחוב הנביאים"), directory().suggestStreets("נביאים"))
        assertEquals(listOf("שדרות בן צבי"), directory().suggestStreets("בן צבי"))
        assertTrue(directory().suggestStreets("ר").isEmpty())
        assertTrue(directory().suggestStreets("unknown").isEmpty())
        assertEquals(1, directory().suggestStreets("רחוב", 1).size)
    }
    @Test fun ambiguousAliasDoesNotChooseAStreet() {
        assertTrue(directory().suggestStreets("משותף").isEmpty())
    }
    @Test fun suggestionsContainNoCoordinatesAndDoNotConfirmAHouse() {
        val d = directory()
        assertEquals(listOf("שדרות בן צבי"), d.suggestStreets("בן-צבי"))
        // A real address exists, but the suggestion is still a bare canonical street.
        val suggestion = d.suggestStreets("בן-צבי").single()
        assertEquals("שדרות בן צבי", suggestion)
        assertThrows(MunicipalInputException::class.java) { d.findConfirmedAddress(suggestion, "") }
        val explicit = d.findConfirmedAddress(suggestion, "1")
        assertEquals("שדרות בן צבי 1", explicit.address)
        assertEquals(31.77, explicit.latitude, 0.0)
        assertEquals(35.21, explicit.longitude, 0.0)
        assertEquals(listOf(suggestion), d.suggestStreets("בן-צבי"))
        assertThrows(IllegalArgumentException::class.java) { d.suggestStreets("רחוב", 0) }
        assertThrows(IllegalArgumentException::class.java) { d.suggestStreets("רחוב", -1) }
        assertThrows(IllegalArgumentException::class.java) { d.suggestStreets("רחוב", 21) }
    }
    @Test fun defaultCapIsFiveAndPrefixMatchesComeBeforeSubstringMatches() {
        val streets = (1..7).joinToString(",") { index ->
            """{"StreetCode":"$index","StreetName":"Street $index","aliases":["${if (index == 7) "needle start" else "contains needle $index"}"]}"""
        }
        val d = StreetDirectory.load("""{"version":1,"streets":[$streets],"addresses":[]}""")
        assertEquals(listOf("Street 7", "Street 1", "Street 2", "Street 3", "Street 4"), d.suggestStreets("needle"))
        assertEquals(7, d.suggestStreets("needle", 20).size)
        assertEquals(listOf("Street 7"), d.suggestStreets("needle", 1))
    }
}
