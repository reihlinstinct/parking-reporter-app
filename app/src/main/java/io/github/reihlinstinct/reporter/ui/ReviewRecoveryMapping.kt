package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.core.PlateClass
import io.github.reihlinstinct.reporter.core.RenderedSentence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.core.TemplateSession
import io.github.reihlinstinct.reporter.security.ReviewRecoveryCodec

/** Explicit field mapping only. The owner must authenticate and validate ledger/evidence bindings BEFORE
 * passing a snapshot here. Evidence, baseline and revision always come from the saved report, never the
 * checkpoint. No Send/auth/approval or pending network operation can be represented by this mapping. */
internal object ReviewRecoveryMapping {
    fun held(initial: ReviewDraft, s: ReviewRecoveryCodec.Snapshot): ReviewRotationState.Held {
        val draft = initial.copy(plate = s.plate, plateClass = PlateClass.fromCode(s.plateClass),
            plateRequired = s.plateRequired, address = s.address, subject = s.subject,
            exactHebrewText = s.text, unresolved = s.unresolved, confirmedTime = s.confirmedTime,
            confirmedLatitude = s.confirmedLatitude, confirmedLongitude = s.confirmedLongitude,
            pendingStreet = s.manualStreet, pendingHouse = s.manualHouse, pendingCross = s.manualCross)
        return ReviewRotationState.Held(initial, draft, s.latitudeRaw, s.longitudeRaw, s.manualStreet,
            s.manualHouse, s.savedManualStreet, s.savedManualHouse, s.ocrPrefilledPlate, s.gpsFilled,
            TemplateSession.State(s.templateTag, s.templateRendered.map { RenderedSentence(it.id, it.text) },
                s.templateFrozen), s.textTyped, s.editing, emptyList(), null, s.confirmExit, s.plateEdits,
            plateRaw = s.plateRaw, manualCross = s.manualCross, addressBaseline = s.addressBaseline,
            pendingAddressWarnings = s.pendingAddressWarnings)
    }
}
