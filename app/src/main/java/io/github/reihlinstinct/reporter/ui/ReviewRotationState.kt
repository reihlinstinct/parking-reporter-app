package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.core.ReviewDraft
import java.io.File

/**
 * Issue #358 part 2. Keeps the in-progress Review screen across a configuration change (rotation) only.
 * It lives in process memory, is never written to disk or a Bundle, and holds a single entry. The screen
 * stores it when it leaves composition because of a configuration change and takes it (removing it) when
 * the same report opens again. Save, discard, leaving the screen and process death all end with nothing held.
 * The app shell also parks the open report (draft, original file, revision) so the Review page can reopen after the
 * rotation instead of falling back to home. Each of the two entries has its own timestamp, so parking one never extends the other. Nothing is held longer than [MAX_AGE_MS], and the shell clears it as soon
 * as any page other than Review is shown. No personal data goes into saved-instance state.
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
        val gpsFilled: Boolean = false,
        /** Issue #424: which template sentences were last rendered, so an edit made before the rotation is still recognised. */
        val templateState: io.github.reihlinstinct.reporter.core.TemplateSession.State = io.github.reihlinstinct.reporter.core.TemplateSession.State(),
        /** Issue #424: the user typed in the description field, so a later category change keeps the text. */
        val textTyped: Boolean = false,
        /** Issue #419: the edit panel was open, the OCR plate suggestions, the confirmed manual address and the open discard dialog. */
        val editing: Boolean = false,
        val plates: List<String> = emptyList(),
        val manualResult: io.github.reihlinstinct.reporter.municipal.StreetDirectory.ConfirmableAddress? = null,
        val confirmExit: Boolean = false,
    )

    class Shell(val draft: ReviewDraft, val original: File?, val revision: Long)

    const val MAX_AGE_MS = 60_000L
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    private var held: Held? = null
    private var shell: Shell? = null
    private var heldAt = 0L
    private var shellAt = 0L

    private fun expired(at: Long) = clock() - at > MAX_AGE_MS

    @Synchronized fun hold(value: Held) { held = value; heldAt = clock() }

    @Synchronized fun holdShell(value: Shell) { shell = value; shellAt = clock() }

    /**
     * The open report to reopen after a rotation, or null when nothing fresh is held. It empties the shell, so a later
     * recreation that was not a rotation can never reopen a stale revision. The held screen state stays for the Review screen to take.
     */
    @Synchronized fun takeShell(): Shell? {
        val value = if (expired(shellAt)) null else shell
        shell = null
        return value
    }

    /** Returns the held state only for the same report (same stable id and same initial draft), and always empties the holder. */
    @Synchronized fun take(initial: ReviewDraft): Held? {
        val value = if (expired(heldAt)) null else held
        held = null
        return value?.takeIf { it.initial.evidence.stableId == initial.evidence.stableId && it.initial == initial }
    }

    @Synchronized fun clear() { held = null; shell = null }
}
