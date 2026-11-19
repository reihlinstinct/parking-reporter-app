package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.*
import org.junit.Test

class StreetAutocompleteTest {
    private fun directory(): StreetDirectory = StreetDirectory.load("""{"version":1,"streets":[
        {"StreetCode":"1","StreetName":"רחוב הנביאים","aliases":["הנביאים"]},
        {"StreetCode":"2","StreetName":"שדרות בן צבי","aliases":["בן-צבי","משותף"]},
        {"StreetCode":"3","StreetName":"רחוב אחר","aliases":["משותף"]}],"addresses":[]}""")
    @Test fun canonicalNamesAreLocalBoundedAndDeduplicated() {
        assertEquals(listOf("רחוב הנביאים"), directory().suggestStreets("נביאים"))
        assertEquals(listOf("שדרות בן צבי"), directory().suggestStreets("בן צבי"))
        assertTrue(directory().suggestStreets("ר").isEmpty())
        assertTrue(directory().suggestStreets("unknown").isEmpty())
        assertTrue(directory().suggestStreets("רחוב", 1).size <= 1)
    }
    @Test fun ambiguousAliasDoesNotChooseAStreet() {
        assertTrue(directory().suggestStreets("משותף").isEmpty())
    }
    @Test fun suggestionsContainNoCoordinatesAndDoNotConfirmAHouse() {
        val d = directory()
        assertEquals(listOf("שדרות בן צבי"), d.suggestStreets("בן-צבי"))
        assertThrows(MunicipalInputException::class.java) { d.findConfirmedAddress("שדרות בן צבי", "1") }
        assertThrows(IllegalArgumentException::class.java) { d.suggestStreets("רחוב", 21) }
    }
}
