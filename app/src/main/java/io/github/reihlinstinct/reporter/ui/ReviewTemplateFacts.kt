package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.core.PermitState
import io.github.reihlinstinct.reporter.core.PlateClasses
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.core.TemplateFacts
import io.github.reihlinstinct.reporter.core.TemplateSlot
import io.github.reihlinstinct.reporter.registry.DisabilityLookupResult
import io.github.reihlinstinct.reporter.registry.VehicleLookupResult
import io.github.reihlinstinct.reporter.registry.VehicleRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Issue #424 slice 2b: turns the review form and the government registry result into [TemplateFacts]. Pure, so it is
 * unit-tested on the JVM. Only a completed live lookup may drive the state sentences; a cached, partial or failed
 * lookup leaves the permit state unknown and adds nothing. A completed permit answer is final, though: a "none" answer
 * adds the no-permit sentence even while the vehicle lookup is still pending. A missing part of the vehicle is dropped, never a placeholder.
 */
object ReviewTemplateFacts {
    private val houseTail = Regex("^(.*\\S)\\s+(\\d+\\s?[\u05D0-\u05EA]?)$")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /**
     * Country suffixes the registry appends to the manufacturer (owner 2026-11-15: "קיה ד. קוריאה" is shown as "קיה").
     * Only an exact trailing match is removed; any other name is kept whole.
     */
    private val countrySuffixes = listOf(
        "ד. קוריאה", "ד.קוריאה", "דרום קוריאה", "יפן", "גרמניה", "ארה\"ב", "צרפת", "איטליה", "ספרד", "בריטניה", "אנגליה",
        "שוודיה", "צ'כיה", "סלובקיה", "הונגריה", "רומניה", "פולין", "בלגיה", "הולנד", "אוסטריה", "תורכיה", "טורקיה",
        "סין", "הודו", "תאילנד", "מלזיה", "מקסיקו", "קנדה", "ברזיל", "ארגנטינה", "רוסיה", "אוקראינה", "ישראל", "דרום אפריקה",
    ).sortedByDescending { it.length }

    fun manufacturerName(raw: String?): String? {
        val name = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (suffix in countrySuffixes) {
            if (name.length > suffix.length && name.endsWith(" $suffix")) return name.removeSuffix(suffix).trim().takeIf { it.isNotEmpty() }
        }
        return name
    }

    /** "{manufacturer} {model} בצבע {colour}" with any missing part dropped; null when nothing is left. */
    fun vehicleText(record: VehicleRecord): String? {
        val maker = listOfNotNull(manufacturerName(record.manufacturer), record.commercialName?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" ")
        val colour = record.colour?.trim()?.takeIf { it.isNotEmpty() }?.let { "\u05D1\u05E6\u05D1\u05E2 $it" }
        return listOfNotNull(maker.takeIf { it.isNotEmpty() }, colour).joinToString(" ").takeIf { it.isNotEmpty() }
    }

    fun splitAddress(address: String): Pair<String, String?> {
        val trimmed = address.trim()
        val m = houseTail.matchEntire(trimmed) ?: return trimmed to null
        return m.groupValues[1].trim() to m.groupValues[2].replace(" ", "")
    }

    fun build(draft: ReviewDraft, snapshot: RegistrySnapshot?): TemplateFacts {
        val values = HashMap<TemplateSlot, String>()
        if (PlateClasses.ready(draft.plate, draft.plateClass)) values[TemplateSlot.PLATE] = PlateClasses.text(draft.plate, draft.plateClass)
        if (draft.address.isNotBlank()) {
            val (street, house) = splitAddress(draft.address)
            if (street.isNotEmpty()) values[TemplateSlot.STREET] = street
            if (house != null) values[TemplateSlot.HOUSE] = house
        }
        var permit = PermitState.UNKNOWN
        val live = snapshot != null && snapshot.cachedAt == null
        if (live && snapshot != null) {
            permit = when (snapshot.tag) {
                is DisabilityLookupResult.Found -> PermitState.HAS_PERMIT
                is DisabilityLookupResult.NotFound -> PermitState.CHECKED_NONE
                else -> PermitState.UNKNOWN
            }
            val record = (snapshot.vehicle as? VehicleLookupResult.Found)?.record
            if (record != null) {
                vehicleText(record)?.let { values[TemplateSlot.VEHICLE] = it }
                val expired = !record.inactive && (licenceDaysRemaining(record.licenceValidUntil, snapshot.checkedAt) ?: 0L) < 0L
                if (expired) {
                    record.licenceValidUntil?.let { values[TemplateSlot.LICENSE_EXPIRY] = formatDate(it) }
                    record.lastTestDate?.let { values[TemplateSlot.LAST_TEST] = formatDate(it) }
                }
            }
        }
        return TemplateFacts(values, permit)
    }

    private fun formatDate(date: LocalDate): String = date.format(dateFormat)
}
