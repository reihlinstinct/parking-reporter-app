package il.sidewalks.reporter

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.fail

/** Field values the flow driver types into the review screen. */
internal data class FlowFields(
    val text: String,
    val plate: String?,
    val address: String,
    val capturedAtIso: String,
    val latitude: String,
    val longitude: String,
    val categoryLabel: String,
)

/**
 * Drives the real AppShell review -> approval -> send flow. Shared by the
 * synthetic stub E2E (FullFlowE2eTest) and the opt-in live submission E2E
 * (live/LiveEmulatorSubmissionTest): ONE flow code path for both, so a UI
 * change breaks the stub first, never only the live run.
 */
internal class FlowDriver(private val compose: ComposeTestRule) {

    /**
     * Best-effort root screenshot into the APP's files dir. A failed or
     * empty capture is logged to stdout (instrumentation output) and never
     * aborts the flow; only real files are listed in the pull manifest the
     * workflow reads.
     */
    fun screenshot(name: String) {
        runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val file = File(context.filesDir, name)
            file.outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            if (file.isFile && file.length() > 0) {
                File(context.filesDir, SHOTS_MANIFEST).appendText("$name\n")
            } else {
                println("FLOW_DRIVER screenshot $name empty - skipped")
            }
        }.onFailure {
            println("FLOW_DRIVER screenshot $name failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * Clicks the ENABLED button carrying [text] through its semantics action,
     * never an injected motion event. The approval and send buttons sit where
     * an open soft keyboard can cover them; a coordinate tap there is eaten
     * by the IME window and nothing happens (run 42769339528: the send tap
     * never reached the app and the run read as a municipal timeout). The
     * matcher requires an ENABLED node: a disabled button keeps its click
     * action in semantics, so without isEnabled a click there would silently
     * no-op. Waits briefly, then throws when no enabled clickable node
     * exists, which is how a disabled button surfaces instead of a miss.
     */
    private fun clickButton(text: String) {
        compose.waitUntil(SEND_FIRED_WAIT_MS) { hasClickableButton(text) }
        // Recomposition between the wait and the action can flip the node
        // back to disabled, and a disabled button keeps its click action,
        // so the click then no-ops silently (run 42853563754).
        compose.waitForIdle()
        // Runs 42853563754 / 42880867432: an enabled-button semantics click
        // no-opped repeatedly. Log every matching node (count, enabled,
        // bounds) so the next run shows whether onNode's first match is the
        // visible button or a stale duplicate left by a transition.
        val matches = compose.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes()
        println("FLOW_DRIVER click target '$text': ${matches.size} clickable node(s): " +
            matches.joinToString { m ->
                "[enabled=${SemanticsProperties.Disabled !in m.config} bounds=${m.boundsInRoot} visible=${m.layoutInfo.isPlaced}]"
            })
        compose.onNode(hasText(text) and hasClickAction() and isEnabled())
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    /**
     * Live runs only: completes the system ConfirmDeviceCredential screen the
     * submit gate raises (MainActivity.authenticate). The live workflow sets
     * a throwaway PIN on the ephemeral emulator (`adb shell locksettings
     * set-pin`, live-emulator-e2e.yml); nothing here bypasses the gate - the
     * PIN is entered into the real system prompt. Without a credential the
     * KeyguardManager intent is null and the gate swallows the tap silently
     * (run 42829175477). Polls for the credential window, types the PIN into
     * the system-focused field via the instrumentation UiAutomation shell
     * (digits only - no shell metacharacters), confirms, and waits for the
     * app window to return. False when the prompt appeared but did not
     * clear; when no prompt appears at all the gate is already satisfied
     * and the caller proceeds unchanged.
     */
    private fun completeDeviceCredential(pin: String): Boolean {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var promptSeen = false
        val appeared = System.currentTimeMillis() + CREDENTIAL_WAIT_MS
        while (System.currentTimeMillis() < appeared) {
            val root = uiAutomation.rootInActiveWindow
            val pkg = root?.packageName?.toString()
            root?.recycle()
            if (pkg != null && pkg in CREDENTIAL_PACKAGES) {
                promptSeen = true
                break
            }
            Thread.sleep(250)
        }
        if (!promptSeen) {
            println("FLOW_DRIVER no device-credential prompt appeared - proceeding")
            return true
        }
        // Let the system focus the PIN field before typing into it.
        Thread.sleep(500)
        // Each command must run to completion before the next: close() alone
        // does not wait, so an undrained ENTER could race ahead of the PIN
        // (#261 review). Draining the stream to EOF awaits the command.
        runShellToCompletion(uiAutomation, "input text $pin")
        runShellToCompletion(uiAutomation, "input keyevent 66") // KEYEVENT_ENTER
        val cleared = System.currentTimeMillis() + CREDENTIAL_WAIT_MS
        while (System.currentTimeMillis() < cleared) {
            val root = uiAutomation.rootInActiveWindow
            val pkg = root?.packageName?.toString()
            root?.recycle()
            if (pkg != null && pkg !in CREDENTIAL_PACKAGES) {
                println("FLOW_DRIVER device-credential prompt completed")
                return true
            }
            Thread.sleep(250)
        }
        println("FLOW_DRIVER device-credential prompt did not clear after PIN entry")
        return false
    }

    /** Runs a shell command to completion by draining its output to EOF. */
    private fun runShellToCompletion(uiAutomation: android.app.UiAutomation, command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            uiAutomation.executeShellCommand(command)
        ).use { it.readBytes() }
    }

    /** True when an ENABLED (clickable) button carrying [text] exists. */
    private fun hasClickableButton(text: String): Boolean =
        compose.onAllNodes(hasText(text) and hasClickAction() and isEnabled())
            .fetchSemanticsNodes().isNotEmpty()

    /** Shared-photo entry: category + field edits, without saving. */
    fun driveToEdited(fields: FlowFields) {
        // A photo with embedded GPS (every real live photo) first raises the
        // location prompt; a synthetic photo goes straight to the review.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("נמצא מיקום בתמונה").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("נמצא מיקום בתמונה").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("השתמש במיקום").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("בדיקת הדיווח").fetchSemanticsNodes().isNotEmpty()
            }
        }
        // This scripted flow already has an approved address; no provider
        // lookup is needed. Dismiss the optional address consent if shown.
        if (compose.onAllNodesWithText("למצוא רחוב ומספר בית?").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("לא עכשיו").performClick()
        }
        compose.onNodeWithText("בחר נושא מהעירייה").performScrollTo().performClick()
        // Search the city catalog, never the retired enum dropdown labels.
        val code = when (fields.categoryLabel) {
            "חניה על המדרכה" -> "1145"
            "תמרור שבור" -> "17841"
            "עמוד / מחסום פגום" -> "1280"
            else -> fields.categoryLabel
        }
        compose.onNodeWithText("חפש נושא, תיאור או מספר").performTextReplacement(code)
        val topic = il.sidewalks.reporter.municipal.CityTopicRepository(
            InstrumentationRegistry.getInstrumentation().targetContext).topics().first { it.code == code }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(topic.name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(topic.name, useUnmergedTree = true).performClick()
        compose.onNodeWithText("ערוך").performScrollTo().performClick()
        if (fields.plate != null) {
            compose.onNodeWithText("מספר לוחית (ספרות בלבד)").performScrollTo().performTextReplacement(fields.plate)
        }
        compose.onNodeWithText("רחוב ומספר בית שאישרת").performScrollTo().performTextReplacement(fields.address)
        compose.onNodeWithText("הטקסט המדויק לבדיקה").performScrollTo().performTextReplacement(fields.text)
        compose.onNodeWithText("זמן הצילום (24 שעות)").performScrollTo().performTextReplacement(il.sidewalks.reporter.ui.ReviewTime.display(fields.capturedAtIso))
        // Location comes from the original photo EXIF, never hidden UI injection.
        // The synthetic provider supplies known synthetic EXIF; live uses real EXIF.
    }

    /** Shared-photo entry through a locally saved draft. */
    fun driveToSaved(fields: FlowFields) {
        driveToEdited(fields)
        // Save is the only review action left before send (owner instruction
        // 2026-11-10): it folds the checked time/coordinates into the draft,
        // which is what enables send.
        clickButton("שמור טיוטה")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("נשמר מקומית, לא נשלח").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** What the UI showed after one send tap. */
    internal enum class SendOutcome { SUBMITTED, DRAFT_NOT_SAVED, TIMEOUT, SEND_NOT_FIRED, SUBMIT_FAILED }

    private fun outcomeShown(): Boolean =
        compose.onAllNodesWithText("נשלח לעירייה. מספר קריאה", substring = true)
            .fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("הטיוטה לא נשמרה", substring = true)
                .fetchSemanticsNodes().isNotEmpty()

    /**
     * The app answered the submission with a failure note: the municipal
     * step replied with an error ("העירייה ענתה"), a local crash was
     * labelled ("שגיאה מקומית"), or the saved state is uncertain
     * ("המצב מול העירייה אינו ודאי"). Run 42774734605 waited the full 300s
     * on exactly such a note because only the two success-path strings were
     * recognized. How far the submission got is decided from the state
     * record by the caller, not here.
     */
    private fun failureNoteShown(): Boolean =
        // Every local refusal is terminal too, even when it does not contain
        // one of the old municipal-error phrases (lookup, profile, address).
        // Sending may finish before the driver samples its transient label.
        compose.onAllNodesWithTag("submission-note").fetchSemanticsNodes().any { node ->
            val text = if (SemanticsProperties.Text in node.config) node.config[SemanticsProperties.Text].joinToString("") { it.text } else ""
            text.isNotBlank() && !text.contains("שולח לעירייה") &&
                !text.contains("נשלח לעירייה") && !text.contains("הטיוטה לא נשמרה")
        } ||
        compose.onAllNodesWithText("העירייה ענתה", substring = true)
            .fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("שגיאה מקומית", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText("המצב מול העירייה אינו ודאי", substring = true)
                .fetchSemanticsNodes().isNotEmpty()

    /**
     * Tap send once and wait up to [outcomeWaitMs] for the outcome. Never
     * throws on timeout: after a real send tap a timeout is ambiguous (the
     * request may already have reached the city) and the caller decides what
     * that means - the stub fails, the live run keeps its evidence.
     *
     * SEND_NOT_FIRED is a UI-only signal (no enabled button, or the sending
     * label never showed): it is a strong lead that nothing was sent, but
     * label absence alone is not proof. The live caller MUST corroborate it
     * against the app's submission-state record (written before the first
     * network call) before treating a re-run as safe, and must never retry
     * it in-run. When [screenshotEveryMs] > 0 the long outcome wait is
     * captured every that many ms into live-e2e-wait-NN.png files.
     * [onTapAboutToFire] runs immediately before the real click (after all
     * pre-tap gates pass) with the 1-based tap number - the live caller emits
     * its SEND_TAPPED marker there so the marker can never precede a real tap
     * by the gate waits. [onNote] reports why the tap loop stopped without a
     * tap (record directory not provably empty, or MAX_SEND_TAPS reached) so
     * the live caller can emit a marker for it, not only a logcat line.
     */
    fun tapSendAndAwaitOnce(outcomeWaitMs: Long, screenshotEveryMs: Long = 0, onTapAboutToFire: (Int) -> Unit = {}, devicePin: String? = null, onNote: (String) -> Unit = {}): SendOutcome {
        // Run 42821291508: the tap raced the tail of on-device OCR. OCR was
        // still running when the send button was matched as enabled; its
        // completion recomposes the review screen (and a plate auto-fill
        // resets the approval digest), so the button was disabled again by
        // the time the semantics click landed - and a disabled button keeps
        // its click action, which then no-ops silently (see clickButton).
        // Every pre-tap gate below fails loudly (instrumentation line +
        // screenshot) and returns SEND_NOT_FIRED: the tap provably never
        // happened, which the live caller arbitrates like any SEND_NOT_FIRED.
        if (!awaitPlateIdIdle()) {
            println("FLOW_DRIVER OCR still active after ${PLATE_ID_WAIT_MS}ms - refusing to tap send")
            screenshot("live-e2e-send-ocr-stuck.png")
            return SendOutcome.SEND_NOT_FIRED
        }
        if (!hideImeAndAwaitHidden()) {
            println("FLOW_DRIVER IME still visible after ${IME_HIDE_WAIT_MS}ms - refusing to tap send")
            screenshot("live-e2e-send-ime-stuck.png")
            return SendOutcome.SEND_NOT_FIRED
        }
        // The state machine writes its per-report record BEFORE the first
        // network call, so positive proof that the record directory is absent
        // or empty proves no request has fired and a tap cannot duplicate
        // anything at the city. That proof is re-established immediately
        // before EVERY tap (first and re-taps alike) inside the loop below:
        // File.list() is null both for an absent directory and for one that
        // cannot be read, so only the absent case is proof - an unreadable
        // path or ANY entry at all (a record, a .tmp file, anything) stops
        // the loop with no tap, and the live caller arbitrates that
        // SEND_NOT_FIRED into OUTCOME_UNKNOWN (#264 review).
        val submissionsDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            "submissions")
        var tap = 0
        while (true) {
            val enabled = try {
                compose.waitUntil(SEND_ENABLED_WAIT_MS) { hasClickableButton("שלח לעירייה") }
                true
            } catch (e: ComposeTimeoutException) { false }
            if (!enabled) {
                println("FLOW_DRIVER send button never enabled within ${SEND_ENABLED_WAIT_MS}ms - no tap")
                screenshot("live-e2e-send-never-enabled.png")
                return SendOutcome.SEND_NOT_FIRED
            }
            // Settle in-flight recomposition before the real click: run
            // 42853563754 clicked during the review screen's recompose churn
            // and the semantics click no-opped silently. The record-dir
            // proof sits directly before the click (#264 review), so the
            // window between proof and click is as small as it can be.
            compose.waitForIdle()
            if (!noSubmissionRecordProvably(submissionsDir)) {
                println("FLOW_DRIVER submission record dir not provably empty before send tap ${tap + 1} - not tapping")
                onNote("SEND_NOT_FIRED reason=record-dir-not-provably-empty")
                screenshot("live-e2e-send-record-unreadable.png")
                return SendOutcome.SEND_NOT_FIRED
            }
            tap += 1
            onTapAboutToFire(tap)
            // Runs 42853563754 / 42880867432: an enabled-button semantics
            // click no-opped repeatedly. Log every matching node (count,
            // enabled, bounds) so the run shows whether onNode's first match
            // is the visible button or a stale duplicate left by a transition.
            val matches = compose.onAllNodes(hasText("שלח לעירייה") and hasClickAction()).fetchSemanticsNodes()
            println("FLOW_DRIVER click target 'שלח לעירייה': ${matches.size} clickable node(s): " +
                matches.joinToString { m ->
                    "[enabled=${SemanticsProperties.Disabled !in m.config} bounds=${m.boundsInRoot} visible=${m.layoutInfo.isPlaced}]"
                })
            compose.onNode(hasText("שלח לעירייה") and hasClickAction() and isEnabled())
                .performScrollTo()
                .performSemanticsAction(SemanticsActions.OnClick)
            // Live path: the submit gate raises the system ConfirmDeviceCredential
            // prompt (MainActivity.authenticate). Complete it when a PIN was
            // supplied - live runs only; the synthetic stub has no gate. A prompt
            // that does not clear means the submit never started, which is a
            // pre-network SEND_NOT_FIRED like any other.
            if (devicePin != null && !completeDeviceCredential(devicePin)) {
                screenshot("live-e2e-credential-stuck.png")
                return SendOutcome.SEND_NOT_FIRED
            }
            // The button must flip to its sending label (or an outcome must
            // appear) within a few seconds; otherwise the tap never reached
            // the app's submit path.
            val fired = try {
                compose.waitUntil(SEND_FIRED_WAIT_MS) {
                    compose.onAllNodesWithText("שולח לעירייה", substring = true)
                        .fetchSemanticsNodes().isNotEmpty() || outcomeShown() || failureNoteShown()
                }; true
            } catch (e: ComposeTimeoutException) { false }
            if (fired) break
            // The tap no-opped. A re-tap happens only after the loop
            // re-proves above that still no submission record exists - never
            // once a network call may have begun - and only up to
            // MAX_SEND_TAPS.
            if (tap >= MAX_SEND_TAPS) {
                println("FLOW_DRIVER send tap $tap left no sending state and no record - giving up")
                onNote("SEND_NOT_FIRED reason=max-taps")
                return SendOutcome.SEND_NOT_FIRED
            }
            println("FLOW_DRIVER send tap $tap no-opped (no sending state, provably no record) - re-tapping after re-proof")
        }
        // Bring a fast terminal note into the captured pixels, rather than
        // leaving it below the saved review form. No extra submit is made.
        if (failureNoteShown()) runCatching { compose.onNodeWithTag("submission-note").performScrollTo() }
        screenshot("live-e2e-after-send-tap.png")
        val startedAt = System.currentTimeMillis()
        var shot = 0
        while (true) {
            val elapsed = System.currentTimeMillis() - startedAt
            val remaining = outcomeWaitMs - elapsed
            if (remaining <= 0) return SendOutcome.TIMEOUT
            val slice = if (screenshotEveryMs > 0) minOf(screenshotEveryMs, remaining) else remaining
            try {
                compose.waitUntil(slice) { outcomeShown() || failureNoteShown() }
                break
            } catch (e: ComposeTimeoutException) {
                if (screenshotEveryMs <= 0 || System.currentTimeMillis() - startedAt >= outcomeWaitMs) {
                    return SendOutcome.TIMEOUT
                }
                shot += 1
                screenshot("live-e2e-wait-%02d.png".format(shot))
            }
        }
        if (compose.onAllNodesWithText("נשלח לעירייה. מספר קריאה", substring = true)
            .fetchSemanticsNodes().isNotEmpty()) return SendOutcome.SUBMITTED
        return if (failureNoteShown()) SendOutcome.SUBMIT_FAILED else SendOutcome.DRAFT_NOT_SAVED
    }

    /**
     * Waits until on-device OCR is idle. "מזהה במכשיר..." (the suggest-plate
     * button while recognizing) and "מנתח את הצילום במכשיר..." (the analyzing
     * note) mark OCR in flight; its completion recomposes the review screen
     * and can flip the send button back to disabled mid-tap. False on timeout
     * (OCR stuck): the caller refuses the tap with SEND_NOT_FIRED.
     */
    private fun awaitPlateIdIdle(): Boolean = try {
        compose.waitUntil(PLATE_ID_WAIT_MS) {
            compose.onAllNodesWithText("מזהה במכשיר", substring = true).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("מנתח את הצילום במכשיר", substring = true).fetchSemanticsNodes().isEmpty()
        }
        true
    } catch (e: ComposeTimeoutException) { false }

    /**
     * Hides the IME through the window insets controller - never a Back key,
     * so it can never navigate away from the flow - then waits (bounded) for
     * the IME to report hidden. Called exactly once per tap attempt: a
     * check-then-act repeat against the hide animation was the #259 review
     * blocker. False when the IME is still visible after the bounded wait.
     * App-side IME handling is #256.
     */
    private fun hideImeAndAwaitHidden(): Boolean {
        val rule = compose as? AndroidComposeTestRule<*, *> ?: return true
        val decorView = rule.activity.window.decorView
        rule.runOnUiThread {
            ViewCompat.getWindowInsetsController(decorView)?.hide(WindowInsetsCompat.Type.ime())
        }
        return try {
            compose.waitUntil(IME_HIDE_WAIT_MS) {
                rule.runOnUiThread {
                    ViewCompat.getRootWindowInsets(decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true
                }
            }
            true
        } catch (e: ComposeTimeoutException) { false }
    }

    fun tapSendAndAwaitCaseNumber() {
        // The send gate compares the shown draft to the last SAVED one and
        // the post-approval save is asynchronous; retry the tap if the gate
        // answers "not saved" before the save lands.
        var attempt = 0
        while (true) {
            attempt += 1
            when (tapSendAndAwaitOnce(45_000)) {
                SendOutcome.SUBMITTED -> return
                SendOutcome.DRAFT_NOT_SAVED -> {
                    if (attempt >= 3) fail("draft-not-saved persisted across retries")
                    Thread.sleep(500)
                }
                SendOutcome.TIMEOUT -> fail("no submission outcome shown")
                SendOutcome.SEND_NOT_FIRED -> fail("send tap never fired")
                SendOutcome.SUBMIT_FAILED -> fail("municipal submit failed (see the on-screen note)")
            }
        }
    }

    /** Full text of the first node containing [substring], or null. */
    fun nodeTextContaining(substring: String): String? {
        val node = compose.onAllNodesWithText(substring, substring = true)
            .fetchSemanticsNodes().firstOrNull() ?: return null
        if (SemanticsProperties.Text !in node.config) return null
        return node.config[SemanticsProperties.Text].joinToString(separator = "") { it.text }
    }

    /**
     * Positive proof that no submission record exists under [dir]: the path
     * is absent, or it is a readable directory with no entries. File.list()
     * returns null both for an absent path AND for one that cannot be read
     * (an I/O error, or a non-directory at the path) - only the absent case
     * is proof, so a null listing on an existing path fails closed, as does
     * ANY entry at all (a record file, a .tmp, anything) (#264 review).
     */
    private fun noSubmissionRecordProvably(dir: File): Boolean {
        if (!dir.exists()) return true
        val names = dir.list() ?: return false
        return names.isEmpty()
    }

    private companion object {
        /** Lists the screenshots that actually landed, one filename per line; the workflow pulls by it. */
        const val SHOTS_MANIFEST = "live-e2e-shots.txt"
        /** How long the app may take to enter its sending state after the tap before we call the tap missed. */
        const val SEND_FIRED_WAIT_MS = 10_000L
        /** How long to wait for an ENABLED send button before calling the tap missed. Covers async approval/save. */
        const val SEND_ENABLED_WAIT_MS = 30_000L

        /** Total send taps per call: a no-op tap is re-tried only on fresh
         * positive proof that no submission record exists (never after a
         * network call may have begun) - see tapSendAndAwaitOnce. A live run
         * taps at most MAX_SEND_TAPS times per call, times the caller's
         * up-to-3 DRAFT_NOT_SAVED retries: 6 clicks worst case. */
        const val MAX_SEND_TAPS = 2
        /** How long to wait for on-device OCR to go idle before arming the send tap. ML Kit on an emulator can be slow. */
        const val PLATE_ID_WAIT_MS = 120_000L
        /** How long the IME may take to report hidden after an insets-controller hide. */
        const val IME_HIDE_WAIT_MS = 5_000L

        /** How long to wait for the system credential prompt and for its dismissal. */
        const val CREDENTIAL_WAIT_MS = 10_000L

        /** Packages that host the ConfirmDeviceCredential screen. */
        val CREDENTIAL_PACKAGES = setOf("com.android.settings", "com.android.systemui")
    }
}
