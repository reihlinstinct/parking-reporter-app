package io.github.reihlinstinct.reporter.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import io.github.reihlinstinct.reporter.core.PlateClass
import io.github.reihlinstinct.reporter.core.PlateClasses
import io.github.reihlinstinct.reporter.core.PlateDetect
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.core.ReportTime
import io.github.reihlinstinct.reporter.i18n.LocalStrings
import io.github.reihlinstinct.reporter.i18n.ProvideStringsIfMissing

@Composable
fun ReviewScreen(initial: ReviewDraft, image: ImageBitmap?, onSave: (ReviewDraft) -> Unit, onBack: () -> Unit, onSubmit: ((ReviewDraft) -> Unit)? = null, submitting: Boolean = false, submissionNote: String = "", missingCredentialKeys: List<String> = emptyList(), noDeviceCredential: Boolean = false, addressLookup: (suspend (Double, Double) -> List<io.github.reihlinstinct.reporter.evidence.AddressSuggestion>)? = null, plateRecognizer: (suspend () -> List<String>)? = null, manualAddressLookup: (suspend (String, String) -> io.github.reihlinstinct.reporter.municipal.StreetDirectory.ConfirmableAddress)? = null,
    topicSnapshot: io.github.reihlinstinct.reporter.core.CityTopicCache.Snapshot? = null,
    topicsAvailable: Boolean = false, topicsLoading: Boolean = false,
    openTopics: (() -> Unit)? = null, topicSelected: ((String) -> Unit)? = null,
    recentTopics: List<String> = emptyList(), topicAliases: Map<String, List<String>> = emptyMap(),
    plateEnables: Set<String> = emptySet(), registryLookup: RegistryLookup? = null,
    sidewalkScorer: (suspend (android.graphics.Bitmap) -> Float?)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Issue #358 part 2: state held across a rotation only (process memory, see ReviewRotationState).
    val restored = remember(initial.evidence.stableId) { ReviewRotationState.take(initial) }
    fun restoredNow() = restored?.takeIf { it.initial == initial }
    var plates by remember(initial.evidence.stableId) { mutableStateOf<List<String>>(emptyList()) }
    var sidewalkScore by remember(initial.evidence.stableId) { mutableStateOf<Float?>(null) }
    LaunchedEffect(initial.evidence.stableId, image) {
        val scorer = sidewalkScorer
        if (scorer != null && image != null) {
            sidewalkScore = try { scorer(image.asAndroidBitmap()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    var useGoogle by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    var plateEdits by remember { mutableIntStateOf(0) }
    // Status lines hold catalog keys and are translated when shown, so they follow a language switch.
    var ocrStatus by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<io.github.reihlinstinct.reporter.evidence.AddressSuggestion>>(emptyList()) }
    var geocoding by remember { mutableStateOf(false) }
    var geocodeStatus by remember { mutableStateOf<String?>(null) }
    fun policy(value: ReviewDraft) = topicSnapshot?.let { io.github.reihlinstinct.reporter.core.PlatePolicy.apply(value, it.topics, plateEnables) } ?: value
    var draft by remember(initial.evidence.stableId) { mutableStateOf(restored?.draft ?: policy(ReportTime.forSave(initial))) }
    LaunchedEffect(initial) { if (restoredNow() == null) draft = policy(ReportTime.forSave(initial)) }
    LaunchedEffect(topicSnapshot, plateEnables) { draft = policy(draft) }
    // The plate field shows what the user typed; the type (regular, police, CD, CC) is read from it by PlateDetect.
    fun plateShown(d: ReviewDraft) = if (d.plate.isBlank()) "" else PlateClasses.text(d.plate, d.plateClass)
    var plateRaw by remember(initial.evidence.stableId) { mutableStateOf(plateShown(draft)) }
    // Invariant: detect(plateRaw) equals what onValueChange stored, so this converges without a loop.
    // An outside change (OCR suggestion, restore) moves the field. Typing that already matches the draft leaves it alone.
    PlateDetect.detect(plateRaw).let { if ((it.plate != draft.plate || it.plateClass != draft.plateClass) && !(draft.plate.isBlank() && !PlateClasses.ready(it.plate, it.plateClass))) plateRaw = plateShown(draft) }
    val registryPlate = if (draft.plateClass == PlateClass.REGULAR) draft.plate else ""
    var latitude by remember(initial.evidence.stableId) { mutableStateOf(restored?.latitude ?: initial.confirmedLatitude?.toString().orEmpty()) }
    var longitude by remember(initial.evidence.stableId) { mutableStateOf(restored?.longitude ?: initial.confirmedLongitude?.toString().orEmpty()) }
    var editing by remember(initial.evidence.stableId) { mutableStateOf(false) }
    // ML Kit note and choice are hidden by default; one tap on the ML Kit line shows them.
    var showMlKit by remember(initial.evidence.stableId) { mutableStateOf(false) }
    var manualStreet by remember(initial.evidence.stableId) { mutableStateOf(restored?.manualStreet.orEmpty()) }
    var manualHouse by remember(initial.evidence.stableId) { mutableStateOf(restored?.manualHouse.orEmpty()) }
    var manualResult by remember(initial.evidence.stableId) { mutableStateOf<io.github.reihlinstinct.reporter.municipal.StreetDirectory.ConfirmableAddress?>(null) }
    var manualBusy by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf(false) }
    // Tab review (#266): address warnings clear only when the field value
    // actually CHANGES from the value that carried the warning - a keystroke
    // that returns to the reviewed/suggested baseline is not a confirmation.
    var addressBaseline by remember(initial.evidence.stableId) { mutableStateOf(initial.address) }
    var pendingAddressWarnings by remember(initial.evidence.stableId) {
        mutableStateOf(initial.unresolved.intersect(setOf(
            ReviewWarning.ADDRESS_NEEDS_CONFIRMATION.code,
            ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code)))
    }
    var manualGeneration by remember { mutableIntStateOf(0) }
    var categoryMenu by remember { mutableStateOf(false) }
    var confirmExit by remember(initial.evidence.stableId) { mutableStateOf(false) }
    val savedManualStreet = remember(initial) { restoredNow()?.savedManualStreet ?: manualStreet }
    val savedManualHouse = remember(initial) { restoredNow()?.savedManualHouse ?: manualHouse }
    // Issue #358: an automatic OCR plate prefill is not a user edit. It only moves the clean baseline for the
    // plate, and only while the user has not touched the plate (recognize() guards that with plateEdits).
    var ocrPrefilledPlate by remember(initial.evidence.stableId) { mutableStateOf<String?>(restored?.ocrPrefilledPlate) }
    // Issue #186: coordinates that came from the phone survive a rotation together with the rest of the state.
    var gpsFilled by remember(initial.evidence.stableId) { mutableStateOf(restored?.gpsFilled ?: false) }
    // The screen's draft always has the topic plate policy applied, so the clean baseline must too, or a screen
    // with a topic snapshot would count as edited before the user touched anything.
    val savedDraft = policy(ReportTime.forSave(initial))
    val cleanDraft = ocrPrefilledPlate?.takeIf { savedDraft.plate.isBlank() }?.let { savedDraft.copy(plate = it) }
    val hasUnsavedEdits = (draft != savedDraft && draft != cleanDraft) ||
        latitude != initial.confirmedLatitude?.toString().orEmpty() ||
        longitude != initial.confirmedLongitude?.toString().orEmpty() ||
        manualStreet != savedManualStreet || manualHouse != savedManualHouse
    // Kept only when the screen leaves composition because of a configuration change. Every other exit
    // (save, discard, back, process death) leaves nothing held.
    val holdOnRotation by rememberUpdatedState {
        ReviewRotationState.Held(initial, draft, latitude, longitude, manualStreet, manualHouse,
            savedManualStreet, savedManualHouse, ocrPrefilledPlate, gpsFilled)
    }
    val rotationActivity = context.findActivity()
    DisposableEffect(initial.evidence.stableId) {
        onDispose {
            if (rotationActivity?.isChangingConfigurations == true) ReviewRotationState.hold(holdOnRotation())
            else ReviewRotationState.clear()
        }
    }
    fun requestExit() {
        if (hasUnsavedEdits) confirmExit = true else onBack()
    }

    // Owner instruction 2026-11-10 (phonemsg 01M73E6E2S6AZP46K75QS2S1WY):
    // the separate "confirm time/location/address" and approval buttons are
    // gone - send is the single confirmation. Saving or sending folds the
    // coordinates into the draft exactly like the removed
    // confirm button did.
    fun foldedDraft(): ReviewDraft {
        val lat = latitude.toDoubleOrNull()
        val lon = longitude.toDoubleOrNull()
        val folded = ReportTime.forSave(draft).copy(confirmedLatitude = lat, confirmedLongitude = lon, unresolved = emptySet())
        // Tab review (#266): the fold confirms location only, never
        // the address - address warnings need their own explicit act: editing
        // the address field (its label says "שאישרת") or the lookup's אשר כתובת.
        // And without coordinates the location warnings stay too, so the user
        // keeps the explanation while send is disabled.
        val unsettled = draft.unresolved.intersect(setOf(
            ReviewWarning.ADDRESS_NEEDS_CONFIRMATION.code,
            ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code)) +
            if (lat == null || lon == null) draft.unresolved.intersect(setOf(
                ReviewWarning.MISSING_GPS.code, ReviewWarning.LOCATION_NEEDS_CONFIRMATION.code)) else emptySet()
        return folded.copy(unresolved = unsettled)
    }
    fun confirmPendingEvidence() {
        draft = foldedDraft()
    }
    suspend fun findAddress() {
        val lat = latitude.toDoubleOrNull() ?: return
        val lon = longitude.toDoubleOrNull() ?: return
        geocoding = true
        try {
            suggestions = withContext(Dispatchers.IO) {
                try { addressLookup?.invoke(lat, lon) ?: io.github.reihlinstinct.reporter.evidence.AddressResolver(context).suggest(lat, lon) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            }
            geocodeStatus = if (suggestions.isEmpty()) "review.geocode.no_suggestions" else "review.geocode.check_house_number"
        } finally { geocoding = false }
    }
    // The provider may receive coordinates. Keep geocoding opt-in, even
    // though the resulting street/house lookup needs no coordinate typing.
    var askAddressConsent by rememberSaveable(initial.evidence.stableId) {
        mutableStateOf(initial.address.isBlank() && initial.confirmedLatitude != null && initial.confirmedLongitude != null)
    }
    // Issue #186: optional, user-triggered one-shot location read. The permission is asked only at the tap.
    // The button fills the phone's position, so it is offered only for a photo taken a moment ago.
    val freshCapture = remember(initial.evidence.stableId) {
        io.github.reihlinstinct.reporter.evidence.CurrentLocation.freshCapture(initial.evidence.capturedAtIso, java.time.Instant.now(), java.time.ZoneId.systemDefault())
    }
    var gpsBusy by remember { mutableStateOf(false) }
    var gpsStatus by remember { mutableStateOf<String?>(null) }
    fun applyDeviceFix(fix: io.github.reihlinstinct.reporter.evidence.DeviceFix) {
        latitude = fix.latitude.toString(); longitude = fix.longitude.toString()
        gpsFilled = true; editing = true; suggestions = emptyList(); geocodeStatus = null
        draft = draft.copy(confirmedLatitude = fix.latitude, confirmedLongitude = fix.longitude,
            unresolved = draft.unresolved - ReviewWarning.MISSING_GPS.code + ReviewWarning.LOCATION_NEEDS_CONFIRMATION.code)
        gpsStatus = "review.location.gps_done"
    }
    fun readDeviceFix() {
        gpsBusy = true; gpsStatus = null
        scope.launch {
            val fix = try { io.github.reihlinstinct.reporter.evidence.CurrentLocation.read(context) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            gpsBusy = false
            // A slow fix must not replace coordinates the user confirmed (for example through the manual address) meanwhile.
            val alreadyLocated = latitude.toDoubleOrNull() != null && longitude.toDoubleOrNull() != null
            if (alreadyLocated) return@launch
            if (fix != null) applyDeviceFix(fix) else gpsStatus = "review.location.gps_failed"
        }
    }
    // Android lint requires FINE to be requested together with COARSE; the user may grant only approximate
    // location, which still allows the network provider.
    val locationPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) readDeviceFix() else gpsStatus = "review.location.gps_denied"
    }
    suspend fun recognize() {
        recognizing = true
        val plateBefore = draft.plate
        val editsBefore = plateEdits
        try {
            plates = withContext(Dispatchers.Default) {
                try { plateRecognizer?.invoke() ?: if (useGoogle) io.github.reihlinstinct.reporter.recognition.MlKitPlateOcr.recognize(image!!.asAndroidBitmap()) else io.github.reihlinstinct.reporter.recognition.OfflinePlateOcr(context).recognize(image!!.asAndroidBitmap()) }
                catch (_: LinkageError) { emptyList() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            }
            if (!draft.needsPlate()) plates = emptyList()
            if (plateEdits == editsBefore) {
                val prefilled = PlateEntry.prefill(draft, plates, plateBefore)
                if (prefilled.plate != draft.plate) ocrPrefilledPlate = prefilled.plate
                draft = prefilled
            }
            ocrStatus = if (plates.isEmpty()) "review.ocr.no_plate" else "review.ocr.suggestions_found"
        } finally { recognizing = false }
    }
    LaunchedEffect(initial.evidence.stableId, image) {
        if (image != null && draft.plate.isEmpty() && draft.needsPlate()) recognize()
    }
    ProvideStringsIfMissing {
    val s = LocalStrings.current
    BackHandler(enabled = !categoryMenu && !askAddressConsent) { requestExit() }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text(s.t("review.unsaved.title")) },
        text = { Text(s.t("review.unsaved.note")) },
        confirmButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text(s.t("review.unsaved.discard")) } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text(s.t("review.unsaved.keep")) } },
    )
    if (askAddressConsent) {
        AlertDialog(onDismissRequest = { askAddressConsent = false },
            title = { Text(s.t("review.location.lookup_title")) },
            text = { Text(s.t("review.edit.geocode_note")) },
            confirmButton = { TextButton(onClick = { askAddressConsent = false; scope.launch { findAddress() } }) { Text(s.t("review.location.lookup_yes")) } },
            dismissButton = { TextButton(onClick = { askAddressConsent = false }) { Text(s.t("review.location.lookup_later")) } })
    }
    // Issue #256: the IME must never stay open over the send control.
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = { focusManager.clearFocus(); keyboard?.hide() }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.t("review.title"), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { if (editing) dismissKeyboard(); editing = !editing }) { Text(s.t(if (editing) "review.edit.done" else "review.edit.start")) }
        }
        image?.let { Image(it, s.t("review.image_description"), modifier = Modifier.fillMaxWidth().height(220.dp)) }
        val selectedCode = io.github.reihlinstinct.reporter.core.TopicIdentity.code(draft.subject)
        val selectedRows = topicSnapshot?.topics.orEmpty().filter { it.code == selectedCode }
        val selectedTopic = selectedRows.firstOrNull()
        val selectedLabel = selectedRows.map { it.path.joinToString(" / ") }.distinct().let { paths ->
            if (paths.size > 1) paths.joinToString("; ") else selectedTopic?.name
        }
        val removed = topicSnapshot != null && selectedCode != null && selectedTopic == null
        val canBrowseTopics = topicSnapshot != null || topicsAvailable
        val topicNote = when {
            topicSnapshot == null && noDeviceCredential -> s.t("review.submit.no_device_lock")
            topicsLoading -> s.t("topics.loading")
            removed -> s.t("topics.removed")
            !canBrowseTopics -> s.t("topics.connection_needed")
            topicSnapshot == null -> s.t("topics.unavailable")
            else -> ""
        }
        TextButton(enabled = canBrowseTopics && !topicsLoading && (topicSnapshot != null || !noDeviceCredential), onClick = {
            openTopics?.invoke(); categoryMenu = true
        }, modifier = Modifier.semantics { stateDescription = topicNote }) {
            Text(selectedLabel ?: s.t("topics.choose"), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        }
        if (topicNote.isNotBlank()) Text(topicNote, Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        topicSnapshot?.let { snapshot ->
            Text(snapshot.knownCheckDate()?.let { date -> s.t("topics.updated", mapOf("date" to date.toString())) } ?: s.t("topics.updated_unknown"))
            if (snapshot.stale(java.time.Instant.now())) Text(s.t("topics.stale"), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        }
        if (categoryMenu && topicSnapshot != null && canBrowseTopics) CityTopicPicker(
            topicSnapshot.topics, selectedCode, recentTopics, topicAliases,
            dismiss = { categoryMenu = false }, choose = { topic ->
                draft = policy(draft.edit(subject = "municipal:" + topic.code))
                if (!draft.needsPlate()) plates = emptyList()
                topicSelected?.invoke(topic.code); categoryMenu = false
            })
        Text(s.t("review.category_note"), style = MaterialTheme.typography.bodySmall)
        // Issue #158: experimental, advisory only. Silent unless the private model is on the phone and scores high.
        val sidewalkCode = io.github.reihlinstinct.reporter.recognition.SidewalkSuggestion.SIDEWALK_SUBJECT_CODE
        val sidewalkTopicAvailable = topicSnapshot?.topics.orEmpty().any { it.code == sidewalkCode }
        if (sidewalkTopicAvailable && io.github.reihlinstinct.reporter.recognition.SidewalkSuggestion.shouldSuggest(sidewalkScore, selectedCode)) {
            TextButton(onClick = {
                draft = policy(draft.edit(subject = "municipal:" + sidewalkCode))
                if (!draft.needsPlate()) plates = emptyList()
                topicSelected?.invoke(sidewalkCode)
            }, modifier = Modifier.fillMaxWidth().testTag("sidewalk_suggestion")) { Text(s.t("review.suggest.sidewalk")) }
        }
        if (!editing) {
            if (draft.needsPlate()) {
                if (draft.plateClass != PlateClass.REGULAR && draft.plate.isNotBlank()) SpecialPlate(PlateClasses.text(draft.plate, draft.plateClass), draft.plateClass, reviewPlateClassText(s, draft), Modifier.testTag("review-plate"))
                else if (IsraeliPlateStyle.isRegular(draft.plate)) IsraeliPlate(draft.plate, reviewPlateText(s, draft.plate), Modifier.testTag("review-plate"))
                else Text(reviewPlateText(s, draft.plate), style = MaterialTheme.typography.titleMedium)
                RegistryStatus(registryPlate, registryLookup)
                if (recognizing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s.t("review.ocr.analyzing")) }
                Text(ocrStatus?.let { s.t(it) }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                plates.forEach { plate -> TextButton(onClick = { draft = draft.edit(plate = plate, plateClass = PlateClass.REGULAR) }) { Text(reviewPlateSuggestionText(s, plate)) } }
            }
            Text(reviewAddressText(s, draft.address))
            if (draft.address.isBlank() && (draft.evidence.latitude != null && draft.evidence.longitude != null || gpsFilled)) {
                Text(s.t("review.edit.geocode_note"), style = MaterialTheme.typography.bodySmall)
                if (geocoding) Text(s.t("review.geocode.loading"))
                Text(geocodeStatus?.let { s.t(it) }.orEmpty())
                suggestions.forEach { suggestion ->
                    TextButton(onClick = { draft = draft.edit(address = suggestion.streetAndHouse).copy(unresolved = draft.unresolved + ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code); addressBaseline = suggestion.streetAndHouse; pendingAddressWarnings = pendingAddressWarnings + ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code; editing = true }) {
                        Text(reviewAddressSuggestionText(s, suggestion.streetAndHouse))
                    }
                }
            }
            Text(draft.exactHebrewText.ifBlank { s.t("review.text_missing") })
            if (draft.unresolved.isNotEmpty()) Text(unresolvedText(s, draft.unresolved), style = MaterialTheme.typography.bodySmall)
        }
        if (editing) {

        if (draft.needsPlate()) {
        TextButton(onClick = { showMlKit = !showMlKit }) { Text(s.t("review.edit.use_mlkit"), style = MaterialTheme.typography.labelLarge) }
        if (showMlKit || useGoogle) Text(s.t("review.edit.privacy_note"))
        if (showMlKit || useGoogle) Row { Checkbox(checked = useGoogle, enabled = !recognizing, onCheckedChange = { useGoogle = it; plates = emptyList(); ocrStatus = null }); Text(s.t("review.edit.use_mlkit")) }
        Button(enabled = image != null && !recognizing, onClick = { scope.launch { recognize() } }, modifier = Modifier.fillMaxWidth()) { Text(s.t(if (recognizing) "review.edit.recognizing" else "review.edit.suggest_plate")) }
        Text(ocrStatus?.let { s.t(it) }.orEmpty())
        plates.forEach { plate -> TextButton(onClick = { draft = draft.edit(plate = plate, plateClass = PlateClass.REGULAR) }) { Text(reviewPlateSuggestionText(s, plate)) } }
        // One plate field, no type picker: the type is read from the typed text (digits = regular, hyphen or trailing mem = police, CD or CC prefix).
        if (draft.needsPlate()) OutlinedTextField(plateRaw, { input ->
                if (PlateDetect.allowed(input)) {
                    plateEdits++
                    val found = PlateDetect.detect(input)
                    plateRaw = input
                    val text = PlateDetect.textAfterChange(draft.exactHebrewText, draft.plate, draft.plateClass, found.plateClass)
                    draft = draft.edit(plate = found.plate, plateClass = found.plateClass, text = text)
                }
            }, singleLine = true,
            isError = plateRaw.isNotBlank() && !PlateClasses.ready(draft.plate, draft.plateClass),
            visualTransformation = if (draft.plateClass == PlateClass.REGULAR && plateRaw.all { it.isDigit() }) PlateEntry else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
            textStyle = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
            label = { Text(s.t("review.edit.plate_field")) }, modifier = Modifier.width(216.dp))
        RegistryStatus(registryPlate, registryLookup)
        }
        OutlinedTextField(draft.address, {
            // Editing the field labeled "רחוב ומספר בית שאישרת" IS the explicit
            // address confirmation (Tab #266); the save/send fold does not
            // clear address warnings. But only a CHANGED value confirms:
            // typing and deleting back to the baseline re-flags the warning.
            val edited = draft.edit(address = it)
            draft = edited.copy(unresolved = edited.unresolved -
                ReviewWarning.ADDRESS_NEEDS_CONFIRMATION.code - ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code +
                if (it != addressBaseline) emptySet() else pendingAddressWarnings)
            if (!gpsFilled && (draft.evidence.latitude == null || draft.evidence.longitude == null)) {
                latitude = ""; longitude = ""; manualResult = null; manualGeneration++
                draft = draft.copy(confirmedLatitude = null, confirmedLongitude = null,
                    unresolved = draft.unresolved + ReviewWarning.LOCATION_NEEDS_CONFIRMATION.code)
            }
        }, label = { Text(s.t("review.edit.address_field")) }, modifier = Modifier.fillMaxWidth())
        if (draft.evidence.latitude != null && draft.evidence.longitude != null || gpsFilled) {
        Text(s.t("review.edit.geocode_note"))
        Button(enabled = !geocoding && latitude.toDoubleOrNull() != null && longitude.toDoubleOrNull() != null, onClick = {
            scope.launch { findAddress() }
        }, modifier = Modifier.fillMaxWidth()) { Text(s.t("review.edit.geocode_button")) }
        if (geocoding) Text(s.t("review.geocode.loading"))
        Text(geocodeStatus?.let { s.t(it) }.orEmpty())
        suggestions.forEach { suggestion ->
            TextButton(onClick = { draft = draft.edit(address = suggestion.streetAndHouse).copy(unresolved = draft.unresolved + ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code); addressBaseline = suggestion.streetAndHouse; pendingAddressWarnings = pendingAddressWarnings + ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code }) {
                Text(reviewAddressSuggestionText(s, suggestion.streetAndHouse))
            }
        }
        }
        OutlinedTextField(draft.exactHebrewText, { draft = draft.edit(text = it) }, label = { Text(s.t("review.edit.text_field")) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Text(reviewAddressText(s, draft.address))
        if (draft.unresolved.isNotEmpty()) Text(unresolvedText(s, draft.unresolved))
        }
        gpsStatus?.let { Text(s.t(it), style = MaterialTheme.typography.bodySmall) }
        if (latitude.toDoubleOrNull() == null || longitude.toDoubleOrNull() == null) {
            Text(s.t("review.location.missing_plain"))
            if (freshCapture) Button(enabled = !gpsBusy, onClick = {
                val granted = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION).any {
                    androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (granted) readDeviceFix()
                else locationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            }, modifier = Modifier.fillMaxWidth().testTag("use_current_location")) { Text(s.t("review.location.gps_button")) }
            if (freshCapture && gpsBusy) Text(s.t("review.location.gps_loading"))
            OutlinedTextField(manualStreet, { manualStreet = it; manualResult = null; manualGeneration++ }, label = { Text(s.t("review.location.street")) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(manualHouse, { manualHouse = it; manualResult = null; manualGeneration++ }, label = { Text(s.t("review.location.house")) }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }))
            Text(s.t("review.location.offline_note"), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !manualBusy && manualAddressLookup != null && manualStreet.isNotBlank() && manualHouse.isNotBlank(), onClick = {
                val generation = manualGeneration
                val street = manualStreet
                val house = manualHouse
                manualBusy = true; manualError = false; manualResult = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { manualAddressLookup!!.invoke(street, house) }
                        if (generation == manualGeneration) manualResult = result
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { if (generation == manualGeneration) manualError = true }
                    finally { manualBusy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(s.t("review.location.find_address")) }
            if (manualAddressLookup == null || manualError) Text(s.t("review.location.lookup_failed"))
            manualResult?.let { result ->
                Text(reviewAddressSuggestionText(s, result.address))
                Text(s.t("review.location.confirm_note"))
                Button(onClick = {
                    latitude = result.latitude.toString(); longitude = result.longitude.toString()
                    addressBaseline = result.address; pendingAddressWarnings = emptySet()
                    draft = draft.edit(address = result.address).copy(confirmedLatitude = result.latitude,
                        confirmedLongitude = result.longitude,
                        unresolved = draft.unresolved - ReviewWarning.MISSING_GPS.code - ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code - ReviewWarning.ADDRESS_NEEDS_CONFIRMATION.code - ReviewWarning.LOCATION_NEEDS_CONFIRMATION.code)
                    manualResult = null
                }, modifier = Modifier.fillMaxWidth()) { Text(s.t("review.location.confirm_address")) }
            }
        }
        Button(onClick = { confirmPendingEvidence(); onSave(draft) }, modifier = Modifier.fillMaxWidth()) { Text(s.t("review.save_draft")) }
        if (onSubmit != null && missingCredentialKeys.isNotEmpty()) {
            // Owner 2026-11-09: when required municipal values are unset, say which
            // ones and grey out send (UI only; the unlock gate before submit is unchanged).
            // Only where a live submit exists - a trial/disabled build shows the
            // generic disabled button instead.
            Text(s.t("review.submit.missing_credentials") + " " + missingCredentialKeys.joinToString(", ") { s.t(it) }, style = MaterialTheme.typography.bodySmall)
        }
        if (onSubmit != null && noDeviceCredential) {
            // Issue #260: with no device lockscreen the unlock gate cannot run.
            // Blocking note and a greyed send - no bypass, no silent no-op.
            Text(s.t("review.submit.no_device_lock"), style = MaterialTheme.typography.bodySmall)
        }
        val plateInDescription = io.github.reihlinstinct.reporter.core.PlatePolicy.descriptionContainsPlate(draft)
        if (draft.needsPlate() && !plateInDescription) {
            Text(s.t("registry.plate_text_required"), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            TextButton(enabled = PlateClasses.ready(draft.plate, draft.plateClass), onClick = {
                // User explicitly inserts it here, before seeing/confirming the outgoing text.
                draft = draft.edit(text = s.t("registry.plate_text", mapOf("plate" to PlateClasses.text(draft.plate, draft.plateClass))) + "\n" + draft.exactHebrewText)
            }) { Text(s.t("registry.insert_plate")) }
        }
        if (!draft.needsPlate()) Text(s.t("plate_categories.enable_hint"), style = MaterialTheme.typography.bodySmall)
        if (onSubmit != null) {
            // Send the draft this screen actually shows, not some other copy.
            // N4: when the lock state disables send, TalkBack announces why.
            val sendModifier = if (noDeviceCredential) Modifier.fillMaxWidth().semantics { stateDescription = s.t("review.submit.no_device_lock") } else Modifier.fillMaxWidth()
            Button(enabled = selectedTopic != null && topicsAvailable && plateInDescription && foldedDraft().isReady() && !submitting && missingCredentialKeys.isEmpty() && !noDeviceCredential, onClick = {
                confirmPendingEvidence()
                dismissKeyboard()
                // Runs 42853563754 / 42880867432: an ENABLED send button's
                // semantics click no-opped silently. First proof point:
                // does the click reach this lambda at all?
                Log.i("SUBMIT_FLOW", "send onClick reached: ready=${draft.isReady()} submitting=$submitting")
                onSubmit(ReportTime.forSend(ReportTime.forSave(draft)))
            }, modifier = sendModifier) { Text(s.t(if (submitting) "review.submit.sending" else "review.submit.button")) }
            if (submissionNote.isNotEmpty()) Text(submissionNote, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("submission-note"))
        } else {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(s.t("review.submit.disabled_button")) }
            Text(s.t("review.submit.unavailable"), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = ::requestExit, modifier = Modifier.fillMaxWidth()) { Text(s.t("review.back")) }
    }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
