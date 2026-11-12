package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.core.ReviewDraft

/**
 * Issue #358 part 2. Keeps the in-progress Review screen across a configuration change (rotation) only.
 * It lives in process memory, is never written to disk or a Bundle, and holds a single entry. The screen
 * stores it when it leaves composition because of a configuration change and takes it (removing it) when
 * the same report opens again. Save, discard, leaving the screen and process death all end with nothing held.
 */
object ReviewRotationState {
    class Held(
        val initial: ReviewDraft,
        val draft: ReviewDraft,
        val latitude: String,
        val longitude: String,
        val manualStreet: String,
        val manualHouse: String,
        val savedManualStreet: String,
        val savedManualHouse: String,
        val ocrPrefilledPlate: String?,
    )

    private var held: Held? = null

    @Synchronized fun hold(value: Held) { held = value }

    /** Returns the held state only for the same report (same stable id and same initial draft), and always empties the holder. */
    @Synchronized fun take(initial: ReviewDraft): Held? {
        val value = held
        held = null
        return value?.takeIf { it.initial.evidence.stableId == initial.evidence.stableId && it.initial == initial }
    }

    @Synchronized fun clear() { held = null }
}
