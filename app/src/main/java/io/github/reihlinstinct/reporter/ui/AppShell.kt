package io.github.reihlinstinct.reporter.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.room.Room
import io.github.reihlinstinct.reporter.BuildConfig
import io.github.reihlinstinct.reporter.EntrySignal
import io.github.reihlinstinct.reporter.EntryIntents
import io.github.reihlinstinct.reporter.capture.CaptureScreen
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.core.SendGate
import io.github.reihlinstinct.reporter.evidence.EvidenceReader
import io.github.reihlinstinct.reporter.evidence.GalleryImport
import io.github.reihlinstinct.reporter.i18n.LanguageResolver
import io.github.reihlinstinct.reporter.i18n.ProvideStrings
import io.github.reihlinstinct.reporter.i18n.Strings
import io.github.reihlinstinct.reporter.i18n.rememberAppCatalog
import io.github.reihlinstinct.reporter.i18n.rememberLanguageController
import io.github.reihlinstinct.reporter.i18n.systemLanguageCode
import io.github.reihlinstinct.reporter.ledger.ReportDatabase
import io.github.reihlinstinct.reporter.ledger.ReportRecord
import io.github.reihlinstinct.reporter.municipal.FileSubmissionStateStore
import io.github.reihlinstinct.reporter.municipal.MunicipalClient
import io.github.reihlinstinct.reporter.municipal.OkHttpMunicipalClient
import io.github.reihlinstinct.reporter.municipal.AddressLookupPin
import io.github.reihlinstinct.reporter.municipal.MunicipalInputException
import io.github.reihlinstinct.reporter.municipal.StreetDirectory
import io.github.reihlinstinct.reporter.municipal.SubmissionCoordinator
import io.github.reihlinstinct.reporter.security.ReporterSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AppShell(
    authenticate: (title: String, subtitle: String, action: () -> Unit) -> Unit,
    // Issue #260: true when the device has a lockscreen credential, so the
    // unlock gate can actually run. Default true keeps test shells unchanged;
    // production (MainActivity) passes the real KeyguardManager check.
    hasDeviceCredential: () -> Boolean = { true },
    requestCamera: (() -> Unit) -> Unit,
    readSettings: () -> ReporterSettings?,
    saveSettings: (ReporterSettings) -> Unit,
    entry: State<EntrySignal?> = mutableStateOf(null),
    // Issue #187: one-time first-run welcome. Defaults keep existing call
    // sites (tests included) on the plain shell.
    showFirstRun: Boolean = false,
    onFirstRunDone: () -> Unit = {},
    // Test-only seam (FullFlowE2eTest): instrumented tests point the
    // submission client at an on-device loopback stub. App code passes
    // nothing; the production client keeps its pinned municipal base URL.
    municipalClientFactory: ((ReporterSettings) -> MunicipalClient)? = null,
    // Test-only in-memory lookup. Production always uses the normal loader,
    // including its file pin; a stub test never overwrites installed data.
    streetDirectoryLoader: (() -> StreetDirectory)? = null,
    registryLookup: (suspend (String) -> RegistrySnapshot)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build() }
    DisposableEffect(Unit) { onDispose { database.close() } }
    // Issue #115: the UI language comes from "ui-prefs" (no authentication) and switches
    // text and layout direction live through ProvideStrings below.
    val networkOnline = rememberNetworkOnline()
    val topicRepository = remember(context) { io.github.reihlinstinct.reporter.municipal.CityTopicRepository(context) }
    var topicSnapshot by remember { mutableStateOf(topicRepository.snapshot()) }
    var topicsLoading by remember { mutableStateOf(false) }
    var topicsConnection by remember { mutableStateOf(false) }
    val topicAliases = remember(context) {
        val root = org.json.JSONObject(context.assets.open("taxonomy/topic_aliases.json").bufferedReader().use { it.readText() })
        root.keys().asSequence().associateWith { code -> root.getJSONArray(code).let { a -> (0 until a.length()).map { a.getString(it) } } }
    }
    val catalog = rememberAppCatalog()
    val languageController = rememberLanguageController()
    val selectedLanguage by languageController.selected.collectAsState()
    val language = LanguageResolver.activeLanguage(catalog, selectedLanguage, systemLanguageCode())
    val strings = remember(catalog, language.code) { Strings(catalog, language.code) }
    var setupStarted by rememberSaveable { mutableStateOf(false) }
    var setupError by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("home") }
    var profile by remember { mutableStateOf<ReporterSettings?>(null) }
    var draft by remember { mutableStateOf<ReviewDraft?>(null) }
    var original by remember { mutableStateOf<File?>(null) }
    var revision by remember { mutableLongStateOf(0) }
    var records by remember { mutableStateOf<List<ReportRecord>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    suspend fun reload() { records = withContext(Dispatchers.IO) { database.reports().all() } }
    LaunchedEffect(Unit) {
        reload()
        // A copy whose import never finished (rotation or process kill while
        // the location dialog was open, a failed reserve) has no ledger row:
        // sweep any original the ledger does not reference.
        withContext(Dispatchers.IO) {
            try {
                // Ledger rows' relative paths; GalleryImport compares only the file names.
                val referenced = database.reports().all().map { it.originalRelativePath }.toSet()
                GalleryImport.sweepUnreferenced(File(context.noBackupFilesDir, "originals"), referenced)
            } catch (_: Exception) {}
        }
    }
    var status by remember { mutableStateOf("") }
    var pendingImport by remember { mutableStateOf<File?>(null) }
    // M-B submission state (issue #153): one attempt per user action, device
    // unlock at every submit (owner 19:49; no master toggle), durable
    // per-report municipal state under noBackupFilesDir/submissions/.
    var streetsDirectory by remember { mutableStateOf<StreetDirectory?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var submissionNote by remember(draft?.evidence?.stableId) { mutableStateOf("") }
    // The municipal address lookup is municipality-supplied private data
    // whose licence for publication is unresolved: it is NOT committed to
    // this public repo. Load the on-device private copy (pushed to the
    // app-specific external dir, or the no-backup dir); the bundled asset is
    // a dev-only fallback when a developer places the file there locally.
    fun loadStreetDirectory(context: android.content.Context): StreetDirectory {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), "addresses.json"),
            File(context.noBackupFilesDir, "addresses.json"),
        )
        for (candidate in candidates) {
            if (candidate.isFile) {
                return StreetDirectory.load(AddressLookupPin.verifyPinned(candidate.readText(Charsets.UTF_8)))
            }
        }
        val bundled = try {
            context.assets.open("addresses.json").bufferedReader().readText()
        } catch (_: Exception) {
            throw MunicipalInputException("address lookup not installed on device")
        }
        return StreetDirectory.load(AddressLookupPin.verifyPinned(bundled))
    }
    fun submitReport(shown: ReviewDraft, outgoing: io.github.reihlinstinct.reporter.evidence.OutgoingImage?) {
        // Runs 42853563754 / 42880867432: the send tap never reached the
        // submit path. Log every gate so the next live run shows exactly
        // where a tap stops (or that it never arrived - see the ReviewScreen
        // onClick log).
        Log.i("SUBMIT_FLOW", "submitReport entered: submitting=$submitting")
        if (submitting) {
            Log.i("SUBMIT_FLOW", "submitReport ignored: submitting already true")
            return
        }
        // Send is the single confirmation (owner instruction 2026-11-10,
        // phonemsg 01M73E6E2S6AZP46K75QS2S1WY): no approval latch. The gate
        // is that the shown draft is complete; the exact shown draft is
        // persisted below before the first network call, so a failed or
        // dropped save never sends stale text (issue #153's M-B property).
        if (!SendGate.readyToSend(shown) || !io.github.reihlinstinct.reporter.core.PlatePolicy.descriptionContainsPlate(shown)) {
            Log.i("SUBMIT_FLOW", "submitReport blocked: draft not ready")
            submissionNote = strings.t("shell.submit.blocked.not_ready")
            return
        }
        // #201: never fall back to the original when preview preparation failed
        // or is still running. Send uses this exact displayed derivative.
        if (outgoing == null || outgoing.originalHash != shown.evidence.originalSha256) {
            submissionNote = municipalOutcomeText(strings, SubmissionCoordinator.Outcome.Blocked(
                SubmissionCoordinator.Reason.INPUT_INVALID, "outgoing image not ready"))
            return
        }
        // Issue #260: without a device credential the unlock intent is null
        // and the gate would swallow the tap silently; block with a note.
        if (!hasDeviceCredential()) {
            Log.i("SUBMIT_FLOW", "submitReport blocked: no device credential")
            submissionNote = strings.t("shell.submit.no_device_lock")
            return
        }
        Log.i("SUBMIT_FLOW", "submitReport requesting device-credential authenticate")
        // Device unlock gates every submit (owner 19:49), reusing the same
        // device-credential flow that protects the settings screen. The
        // prompt states what the unlock authorises: sending this report.
        authenticate(strings.t("shell.unlock.submit_title"),
            strings.t("shell.unlock.submit_subtitle")) {
            Log.i("SUBMIT_FLOW", "submitReport authenticate callback: setting submitting=true")
            submitting = true
            submissionNote = strings.t("shell.submit.sending")
            scope.launch {
                // Persist the exact shown draft before anything network-bound
                // unless it is already the last saved copy; a failed save
                // blocks the send instead of sending stale text.
                if (shown != draft) {
                    val stored = withContext(Dispatchers.IO) {
                        try {
                            database.reports().store(shown.evidence.stableId, revision,
                                io.github.reihlinstinct.reporter.ledger.DraftCodec.encode(shown), shown.digest()) == 1
                        } catch (_: Exception) { false }
                    }
                    if (!stored) {
                        Log.i("SUBMIT_FLOW", "submitReport blocked: DRAFT_NOT_SAVED")
                        submissionNote = strings.t("shell.submit.draft_not_saved")
                        submitting = false
                        return@launch
                    }
                    revision += 1
                    draft = shown
                    reload()
                }
                Log.i("SUBMIT_FLOW", "submitReport shown draft persisted")
                var passportReviewDraft: ReviewDraft? = null
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        val settings = readSettings()
                            ?: return@withContext SubmissionCoordinator.Outcome.Blocked(
                                SubmissionCoordinator.Reason.MISSING_CREDENTIALS, "settings unreadable")
                        if (!io.github.reihlinstinct.reporter.municipal.PassportSubmission.hasReviewedDisclosure(shown.exactHebrewText, settings)) {
                            if (io.github.reihlinstinct.reporter.municipal.PassportSubmission.validProfile(settings)) {
                                val updated = shown.edit(text = io.github.reihlinstinct.reporter.municipal.PassportSubmission.reconcileDisclosure(shown.exactHebrewText, settings))
                                check(database.reports().store(updated.evidence.stableId, revision,
                                    io.github.reihlinstinct.reporter.ledger.DraftCodec.encode(updated),
                                    updated.digest().takeIf { updated.isReady() }) == 1)
                                passportReviewDraft = updated
                            }
                            return@withContext SubmissionCoordinator.Outcome.Blocked(
                                SubmissionCoordinator.Reason.PASSPORT_DISCLOSURE_REQUIRED, "passport disclosure must be saved before sending")
                        }
                        val directory = streetsDirectory
                            ?: (streetDirectoryLoader?.invoke() ?: loadStreetDirectory(context))
                                .also { streetsDirectory = it }
                        val file = original
                            ?: return@withContext SubmissionCoordinator.Outcome.Blocked(
                                SubmissionCoordinator.Reason.INPUT_INVALID, "missing original")
                        SubmissionCoordinator(
                            municipalClientFactory ?: { s ->
                                OkHttpMunicipalClient(
                                    subscriptionKey = s.municipalSubscriptionKey,
                                    username = s.municipalUsername,
                                    password = s.municipalPassword,
                                    diagnostic = { diagnostic -> io.github.reihlinstinct.reporter.security.EncryptedResponseLog(context)
                                        .append(shown.evidence.stableId, diagnostic) }) },
                            directory,
                            { id -> FileSubmissionStateStore.forReport(
                                File(context.noBackupFilesDir, "submissions"), id) },
                            activeTopicCodes = { topicRepository.topics().map { it.code }.toSet() },
                            afterSuccessfulSubmission = { client, token ->
                                scope.launch(Dispatchers.IO) {
                                    topicRepository.afterSuccess(client, token)
                                    val refreshed = topicRepository.snapshot()
                                    withContext(Dispatchers.Main) { topicSnapshot = refreshed }
                                }
                            },
                        ).submit(shown, settings, file,
                            SubmissionCoordinator.OutgoingPhoto(outgoing.jpeg, outgoing.originalHash))
                    } catch (e: io.github.reihlinstinct.reporter.municipal.AddressLookupMismatchException) {
                        SubmissionCoordinator.Outcome.Blocked(
                            SubmissionCoordinator.Reason.LOOKUP_MISMATCH,
                            "lookup is outdated or modified")
                    } catch (e: MunicipalInputException) {
                        SubmissionCoordinator.Outcome.Blocked(
                            SubmissionCoordinator.Reason.LOOKUP_NOT_INSTALLED,
                            e.message ?: "lookup not installed")
                    } catch (e: Exception) {
                        // A local crash is NOT a municipal answer: log the
                        // class name only and label the failure as local.
                        Log.w("AppShell", "submission crashed: " + e.javaClass.simpleName)
                        SubmissionCoordinator.Outcome.Blocked(
                            SubmissionCoordinator.Reason.LOCAL_FAILURE,
                            e.javaClass.simpleName)
                    }
                }
                passportReviewDraft?.let { updated -> revision += 1; draft = updated; reload() }
                // Fixed reason codes only, never reporter values or municipal details.
                Log.i("SUBMIT_FLOW", "submitReport outcome=" + when (outcome) {
                    is SubmissionCoordinator.Outcome.Blocked -> outcome.reason.name
                    is SubmissionCoordinator.Outcome.Submitted -> "SUBMITTED"
                    is SubmissionCoordinator.Outcome.AlreadySubmitted -> "ALREADY_SUBMITTED"
                })
                submissionNote = municipalOutcomeText(strings, outcome)
                if (outcome is SubmissionCoordinator.Outcome.Submitted ||
                    outcome is SubmissionCoordinator.Outcome.AlreadySubmitted) {
                    status = submissionNote
                    topicSnapshot = topicRepository.snapshot()
                }
                submitting = false
            }
        }
    }
    fun saveDraft(value: ReviewDraft) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    database.reports().store(value.evidence.stableId, revision,
                        io.github.reihlinstinct.reporter.ledger.DraftCodec.encode(value),
                        value.digest().takeIf { value.isReady() })
                }
                check(updated == 1)
                revision += 1
                draft = value; status = strings.t("shell.status.draft_saved")
                reload()
            } catch (_: Exception) { status = strings.t("shell.status.draft_save_failed") }
            finally { saving = false }
        }
    }
    fun openSettings() {
        // Issue #295: same silent swallow as the save path - on a lock-less
        // device the הגדרות tap opened nothing and said nothing.
        if (!hasDeviceCredential()) { status = strings.t("shell.submit.no_device_lock"); return }
        authenticate(strings.t("shell.unlock.settings_title"), strings.t("shell.unlock.settings_subtitle")) {
            try { profile = readSettings(); page = "settings" }
            catch (_: Exception) { status = strings.t("shell.status.settings_read_failed") }
        }
    }
    fun startReview(file: File, usePhotoLocation: Boolean = true) {
        scope.launch {
            try {
                var evidence = withContext(Dispatchers.IO) { EvidenceReader().read(file) }
                if (!usePhotoLocation && (evidence.latitude != null || evidence.longitude != null)) {
                    evidence = evidence.copy(latitude = null, longitude = null)
                }
                // Stable codes, translated when the review screen shows them (issue #115 PR-2b).
                val missing = initialReviewWarnings(evidence)
                try {
                    withContext(Dispatchers.IO) {
                        database.reports().reserve(ReportRecord(evidence.stableId, evidence.originalSha256,
                            "originals/${file.name}", 0,
                            io.github.reihlinstinct.reporter.ledger.DraftCodec.encode(ReviewDraft(evidence, "", "", "", "", missing)), null))
                    }
                } catch (e: Exception) {
                    // The reserve failed (for example a duplicate original), so
                    // the copied file has no ledger row: delete it rather than
                    // leave an EXIF-carrying original orphaned on disk.
                    withContext(Dispatchers.IO) { GalleryImport.deleteOrphaned(file) }
                    throw e
                }
                original = file; revision = 0
                draft = ReviewDraft(evidence, "", "", "", "", missing)
                reload(); page = "review"
            } catch (e: Exception) {
                Log.w("AppShell", "review start failed", e)
                status = strings.t("shell.status.evidence_duplicate_or_unsaved"); page = "home"
            }
        }
    }
    fun openRecord(record: ReportRecord) {
        try {
            val decoded = io.github.reihlinstinct.reporter.ledger.DraftCodec.decode(record.draftJson)
            val file = File(context.noBackupFilesDir, record.originalRelativePath)
            check(decoded.evidence.stableId == record.stableId && decoded.evidence.originalSha256 == record.originalSha256)
            check(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/") && file.isFile)
            check(EvidenceReader().read(file, record.stableId).originalSha256 == record.originalSha256)
            // Tab review (#266): assign draft/original/revision only after every
            // check passes. The old order set draft first, so a failed check
            // paired the tapped record's draft with the PREVIOUS record's
            // original photo and revision - and Continue could send that mix.
            draft = decoded; original = file; revision = record.revision; page = "review"
        } catch (_: Exception) { status = strings.t("shell.status.evidence_restore_failed") }
    }
    // The system photo picker needs no permissions (no camera, no location).
    // EXIF time and GPS, when present, come from the picked original bytes.
    // One import pipeline for the system picker and the share-sheet target
    // (issue #149): copy bytes, read EXIF from the copy, confirm location use.
    fun importShared(uri: Uri) {
        scope.launch {
            var copied: File? = null
            try {
                val file = withContext(Dispatchers.IO) {
                    GalleryImport.copyToOriginals({ context.contentResolver.openInputStream(uri) },
                        File(context.noBackupFilesDir, "originals"))
                }
                copied = file
                val hasGps = withContext(Dispatchers.IO) { EvidenceReader().read(file).latitude != null }
                if (hasGps) pendingImport = file else startReview(file)
            } catch (e: Exception) {
                Log.w("AppShell", "shared/gallery image import failed", e)
                copied?.let { orphan -> withContext(Dispatchers.IO) { GalleryImport.deleteOrphaned(orphan) } }
                status = strings.t("shell.status.image_read_failed")
            }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importShared(uri)
    }
    // Deep link / Quick Settings tile / share-sheet entry (issue #149 phase 1).
    // A first launch that lands on the welcome screen (#187) defers the entry
    // until Start is tapped: the import/camera flow then begins from home,
    // not underneath the welcome.
    LaunchedEffect(entry.value, showFirstRun) {
        if (showFirstRun) return@LaunchedEffect
        when (val request = entry.value?.request) {
            // An entry arriving over the settings screen drops the decrypted
            // profile from memory before any navigation.
            is EntryIntents.Request.SharedPhoto -> { profile = null; importShared(Uri.parse(request.uriString)) }
            EntryIntents.Request.NewReport -> { profile = null; requestCamera { page = "camera" } }
            null -> {}
        }
    }
    // Shared authenticated, fresh-read merge for onboarding and normal Settings. Neither
    // onboarding nor its language picker decrypts any existing profile on entry.
    fun persistSettings(value: ReporterSettings, touchedMunicipal: Set<String>, cleared: () -> Unit, failed: () -> Unit = {}) {
            // Issue #295: on a lock-less device the unlock intent is null and
            // authenticate swallows the save silently; block with a note instead.
            if (!hasDeviceCredential()) { status = strings.t("shell.submit.no_device_lock"); failed(); return }
            authenticate(strings.t("shell.unlock.settings_title"), strings.t("shell.unlock.settings_subtitle")) {
                try {
                    // The screen clears itself and profile is dropped after a save: blank
                    // personal fields keep the stored values; a municipal credential field
                    // saves as typed only when the user touched it (blank = cleared), an
                    // untouched one keeps its stored value. Merge against a fresh read.
                    // The plaintext missing-fields record (IDs only, atomic write) goes
                    // FIRST: if it fails, nothing was saved and no stale record can
                    // grey out a later send.
                    val merged = mergeReporterSettings(value, readSettings(), touchedMunicipal)
                    val profileStore = io.github.reihlinstinct.reporter.security.SettingsProfileStore(context)
                    profileStore.save(merged.missingMunicipalFields())
                    try {
                        saveSettings(merged)
                    } catch (failure: Exception) {
                        // The record already says the new answer: restore it to the
                        // previous stored one, else delete it to fall back to
                        // "unknown" - neither failure direction may grey out Send wrongly.
                        try {
                            val previous = readSettings()
                            if (previous != null) profileStore.save(previous.missingMunicipalFields()) else profileStore.clear()
                        } catch (_: Exception) { profileStore.clear() }
                        throw failure
                    }
                    // Issue #295: keep the freshly stored profile as the screen's
                    // baseline (fields themselves stay cleared) so the
                    // required-fields gate still sees complete effective values
                    // when the user edits again right after a save. `merged` is
                    // derived from the fresh read above and is exactly what was
                    // just persisted, so no second read (and no extra decryption
                    // failure mode) is needed here.
                    profile = merged; cleared(); status = strings.t("shell.status.settings_saved")
                }
                catch (_: Exception) { status = strings.t("shell.status.settings_save_failed"); failed() }
            }
    }
    ProvideStrings(catalog, language) {
    if (showFirstRun) {
        if (!setupStarted) {
            FirstRunScreen(strings, onStart = { setupStarted = true })
        } else {
            // Personal fields use remember, never rememberSaveable or plaintext prefs.
            // Skip and successful save dispose the form; unfinished setup never consumes entry.
            BackHandler { onFirstRunDone() }
            SettingsScreen(null, basicSetup = true, setupError = setupError,
                selectedLanguage = selectedLanguage,
                onLanguageSelected = languageController::select,
                noDeviceCredential = !hasDeviceCredential(),
                save = { value, touched, cleared ->
                    setupError = ""
                    persistSettings(value, touched, { cleared(); onFirstRunDone() },
                        // #295: a no-credential block must not surface as a generic save failure.
                        { setupError = strings.t(if (!hasDeviceCredential()) "shell.submit.no_device_lock" else "shell.status.settings_save_failed") })
                }, back = onFirstRunDone)
        }
        return@ProvideStrings
    }
    BackHandler(enabled = !showFirstRun && page != "home") {
        if (page == "licenses" || page == "plate-categories") page = "settings"
        else { profile = null; page = "home" }
    }
    Scaffold(
        bottomBar = {
            if (page == "home" || page == "settings" || page == "reports") {
                Column {
                // Issue #295: one shared status slot above the bar on every page
                // that has it, so a note (e.g. "set a screen lock" when the
                // Settings tap is blocked) is visible from Reports too, not
                // only from Home. The polite live region makes TalkBack
                // announce it when it appears.
                if (status.isNotEmpty()) {
                    Text(status, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                val navigationColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant)
                NavigationBar {
                    NavigationBarItem(
                        colors = navigationColors,
                        selected = page == "home",
                        onClick = { profile = null; page = "home" },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(strings.t("shell.nav.home")) },
                    )
                    NavigationBarItem(
                        colors = navigationColors,
                        selected = page == "reports",
                        onClick = { profile = null; page = "reports" },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text(strings.t("shell.nav.reports")) },
                    )
                    NavigationBarItem(
                        colors = navigationColors,
                        selected = page == "settings",
                        onClick = { openSettings() },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(strings.t("shell.nav.settings")) },
                    )
                }
                }
            }
        },
    ) { innerPadding ->
    Box(Modifier.padding(innerPadding)) {
    when (page) {
        "camera" -> CaptureScreen(onCaptured = { file -> startReview(file) }, onCancel = { page = "home" })
        "reports" -> ReportsScreen(records, open = { openRecord(it) })
        "review" -> draft?.let { value ->
            val preview by produceState<Pair<io.github.reihlinstinct.reporter.evidence.OutgoingImage, androidx.compose.ui.graphics.ImageBitmap>?>(null, original) {
                this.value = withContext(Dispatchers.IO) {
                    try {
                        original?.let { file ->
                            val outgoing = io.github.reihlinstinct.reporter.evidence.OutgoingEvidence.prepare(file)
                            BitmapFactory.decodeByteArray(outgoing.jpeg, 0, outgoing.jpeg.size)
                                ?.asImageBitmap()?.let { outgoing to it }
                        }
                    } catch (_: Exception) { null }
                }
            }
            val outgoing = preview?.first
            val image = preview?.second
            // Live submit is always visible (owner 19:49: no master toggle
            // and no settings pre-read; the per-submit device unlock is the
            // gate, and missing credentials answer after it).
            // Plaintext field-IDs record: which municipal credentials are unset, no
            // values, readable without the unlock that a settings read would need.
            // null (never saved) keeps the old answer-after-unlock behavior.
            val missingCredentials = remember(value.evidence.stableId) {
                io.github.reihlinstinct.reporter.security.SettingsProfileStore(context).read()
                    ?.mapNotNull(::municipalFieldLabelKey).orEmpty()
            }
            // Issue #260 review: one read per composition; a stale backstop
            // lock note is obsolete once a lockscreen exists.
            val noDeviceCredential = !hasDeviceCredential()
            LaunchedEffect(noDeviceCredential) {
                if (!noDeviceCredential && submissionNote == strings.t("shell.submit.no_device_lock")) submissionNote = ""
            }
            LaunchedEffect(value.evidence.stableId) {
                topicsConnection = io.github.reihlinstinct.reporter.security.SettingsProfileStore(context).read()?.isEmpty() != false
            }
            // A missing profile is an older install, not proof that credentials are absent.
            // Let its first authenticated catalog read establish that state.
            fun openCityTopics() {
                if (topicSnapshot != null || topicsLoading || !hasDeviceCredential()) return
                authenticate(strings.t("topics.connect_title"), strings.t("topics.connection_needed")) {
                    scope.launch {
                        topicsLoading = true
                        try {
                            topicSnapshot = withContext(Dispatchers.IO) {
                                val settings = readSettings() ?: error("Settings unavailable")
                                topicRepository.initial(settings, municipalClientFactory ?: { stored ->
                                    OkHttpMunicipalClient(stored.municipalSubscriptionKey, stored.municipalUsername, stored.municipalPassword)
                                })
                            }
                        } catch (_: Exception) { /* visible no-cache state; never overwrite last good cache */ }
                        finally { topicsLoading = false }
                    }
                }
            }
            ReviewScreen(value, image, ::saveDraft, manualAddressLookup = { street, house ->
                val directory = streetsDirectory ?: (streetDirectoryLoader?.invoke() ?: loadStreetDirectory(context)).also { streetsDirectory = it }
                directory.findConfirmedAddress(street, house)
            }, onBack = { page = "home" },
                onSubmit = { shown -> submitReport(shown, outgoing.takeIf { image != null }) },
                submitting = submitting, submissionNote = submissionNote, missingCredentialKeys = missingCredentials, noDeviceCredential = noDeviceCredential,
                topicSnapshot = topicSnapshot, topicsAvailable = topicsConnection,
                topicsLoading = topicsLoading, openTopics = ::openCityTopics,
                topicSelected = { topicRepository.selected(it) }, recentTopics = topicRepository.recentCodes(), topicAliases = topicAliases, plateEnables = topicRepository.plateEnables(), registryLookup = registryLookup)
            // Save feedback lands in the shell status; the review page has no
            // bottom bar, so it keeps its own announced slot (home/settings/
            // reports share the one above the bar).
            if (status.isNotEmpty()) Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        "settings" -> SettingsScreen(profile, save = { value, touchedMunicipal, cleared ->
            persistSettings(value, touchedMunicipal, cleared)
        }, back = { profile = null; page = "home" }, noDeviceCredential = !hasDeviceCredential(), debugTools = if (BuildConfig.DEBUG) {
            {
                Text(strings.t("shell.debug.title"), style = MaterialTheme.typography.titleMedium)
                Text(strings.t("shell.debug.description"))
                Button(onClick = {
                    scope.launch {
                        status = strings.t("shell.debug.running")
                        status = withContext(Dispatchers.Default) {
                            try { io.github.reihlinstinct.reporter.intake.SyntheticIntakeDemo.run() }
                            catch (_: Exception) { strings.t("shell.debug.failed") }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(strings.t("shell.debug.run_button")) }
            }
        } else null, selectedLanguage = selectedLanguage, onLanguageSelected = languageController::select,
            onLicenses = { page = "licenses" }, plateCategoriesOnline = networkOnline,
            onPlateCategories = { if (networkOnline) page = "plate-categories" })
        "plate-categories" -> PlateCategoriesScreen(networkOnline, topicRepository.plateEnables(), refresh = {
            val settings = readSettings() ?: error("Settings unavailable")
            topicRepository.forcePlateRefresh(networkOnline) {
                (municipalClientFactory ?: { stored: ReporterSettings -> OkHttpMunicipalClient(stored.municipalSubscriptionKey, stored.municipalUsername, stored.municipalPassword) })(settings)
            }.also { refreshed -> topicSnapshot = refreshed }
        }, enable = topicRepository::enablePlate, back = { page = "settings" })
        // Third-party licences and data attributions (owner decision 2026-11-08).
        // AppShell's ProvideStrings already supplies the catalog and active language
        // (#115 PR-2a), so the screen just reads LocalStrings.
        "licenses" -> LicensesScreen(onBack = { page = "settings" })
        else -> Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.t("shell.home.title"), style = MaterialTheme.typography.headlineMedium)
            Text(strings.t("shell.home.subtitle"), style = MaterialTheme.typography.titleLarge)
            Text(strings.t("shell.home.on_device_note"))
            Button(onClick = { requestCamera { page = "camera" } }, modifier = Modifier.fillMaxWidth()) {
                Icon(PhotoCameraIcon, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(strings.t("shell.home.take_photo"))
            }
            OutlinedButton(onClick = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, modifier = Modifier.fillMaxWidth()) { Text(strings.t("shell.home.upload_photo")) }
            Text(strings.t("shell.home.upload_note"), style = MaterialTheme.typography.bodySmall)
            Text(strings.t("shell.home.test_build_note"), style = MaterialTheme.typography.bodySmall)
            Text(strings.t("shell.home.drafts_title"), style = MaterialTheme.typography.titleMedium)
            if (records.isEmpty() && draft == null) Text(strings.t("shell.home.no_drafts"), style = MaterialTheme.typography.bodySmall)
            records.forEach { record ->
                OutlinedButton(onClick = { openRecord(record) }, modifier = Modifier.fillMaxWidth()) { Text(strings.t("shell.home.open_draft", mapOf("id" to record.stableId.take(8)))) }
            }
            if (draft != null) OutlinedButton(onClick = { page = "review" }, modifier = Modifier.fillMaxWidth()) { Text(strings.t("shell.home.continue_review")) }
            Text(strings.t("shell.home.login_unavailable"), style = MaterialTheme.typography.bodySmall)
            Text(strings.t("shell.home.permission_note"), style = MaterialTheme.typography.bodySmall)
        }
    }
    }
    }
    pendingImport?.let { file ->
        AlertDialog(
            onDismissRequest = {
                pendingImport = null
                scope.launch(Dispatchers.IO) { GalleryImport.deleteOrphaned(file) }
                status = strings.t("shell.status.import_cancelled")
            },
            title = { Text(strings.t("shell.import_location.title")) },
            text = { Text(strings.t("shell.import_location.message")) },
            confirmButton = {
                TextButton(onClick = { pendingImport = null; startReview(file, usePhotoLocation = true) }) { Text(strings.t("shell.import_location.use")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null; startReview(file, usePhotoLocation = false) }) { Text(strings.t("shell.import_location.skip")) }
            },
        )
    }
    }
}

/**
 * Issue #153 outcome -> the note shown under the review screen, in the active language
 * (issue #115). The mapping is #160's, one key per outcome; [SubmissionCoordinator]
 * never produces user-facing text itself.
 */
internal fun municipalFieldLabelKey(id: String): String? = when (id) {
    "subscription_key" -> "settings.field.subscription_key"
    "api_username" -> "settings.field.api_username"
    "api_password" -> "settings.field.api_password"
    else -> null
}

/** Name the unset municipal fields from the typed ID list (owner
 *  2026-11-09: an error must say exactly which values are missing). Unknown
 *  IDs are dropped rather than mislabeled. */
internal fun missingCredentialsSuffix(strings: Strings, missingFields: List<String>): String {
    val labels = missingFields.mapNotNull { municipalFieldLabelKey(it) }
    if (labels.isEmpty()) return ""
    return ": " + labels.joinToString(", ") { strings.t(it) }
}

internal fun municipalOutcomeText(strings: Strings, outcome: SubmissionCoordinator.Outcome): String = when (outcome) {
    is SubmissionCoordinator.Outcome.Submitted ->
        strings.t("shell.submit.sent",
            mapOf("case_number" to (outcome.caseNumber ?: strings.t("shell.submit.case_number_received"))))
    is SubmissionCoordinator.Outcome.AlreadySubmitted ->
        strings.t("shell.submit.already_sent", mapOf("case_number" to (outcome.caseNumber ?: "")))
    is SubmissionCoordinator.Outcome.Blocked -> when (outcome.reason) {
        SubmissionCoordinator.Reason.PASSPORT_DISCLOSURE_REQUIRED -> strings.t("shell.passport.review_required")
        SubmissionCoordinator.Reason.NOT_READY -> strings.t("shell.submit.blocked.not_ready")
        SubmissionCoordinator.Reason.MISSING_CREDENTIALS ->
            strings.t("shell.submit.blocked.missing_credentials") + missingCredentialsSuffix(strings, outcome.missingFields)
        SubmissionCoordinator.Reason.UNSUPPORTED_CATEGORY -> strings.t("shell.submit.blocked.unsupported_category")
        SubmissionCoordinator.Reason.ADDRESS_UNRESOLVED ->
            strings.t("shell.submit.blocked.address_unresolved", mapOf("detail" to outcome.detail))
        SubmissionCoordinator.Reason.INPUT_INVALID ->
            strings.t("shell.submit.blocked.input_invalid", mapOf("detail" to outcome.detail))
        SubmissionCoordinator.Reason.MUNICIPAL_REVIEW_NEEDED ->
            strings.t("shell.submit.blocked.municipal_review_needed", mapOf("detail" to outcome.detail))
        SubmissionCoordinator.Reason.MUNICIPAL_API_FAILURE ->
            strings.t("shell.submit.blocked.municipal_api_failure", mapOf("detail" to outcome.detail))
        SubmissionCoordinator.Reason.LOCAL_FAILURE ->
            strings.t("shell.submit.blocked.local_failure", mapOf("detail" to outcome.detail))
        SubmissionCoordinator.Reason.LOOKUP_NOT_INSTALLED -> strings.t("shell.submit.blocked.lookup_not_installed")
        SubmissionCoordinator.Reason.LOOKUP_MISMATCH -> strings.t("shell.submit.blocked.lookup_mismatch")
    }
}
