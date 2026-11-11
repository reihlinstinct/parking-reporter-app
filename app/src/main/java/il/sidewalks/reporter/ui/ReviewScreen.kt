package il.sidewalks.reporter.ui

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import il.sidewalks.reporter.core.ReviewDraft
import il.sidewalks.reporter.i18n.LocalStrings
import il.sidewalks.reporter.i18n.ProvideStringsIfMissing

/** Same 24-hour format sample in every language. */
private const val LOCAL_TIME_EXAMPLE = "\u206601/01/2026 12:00\u2069"

@Composable
fun ReviewScreen(initial: ReviewDraft, image: ImageBitmap?, onSave: (ReviewDraft) -> Unit, onBack: () -> Unit, onSubmit: ((ReviewDraft) -> Unit)? = null, submitting: Boolean = false, submissionNote: String = "", missingCredentialKeys: List<String> = emptyList(), noDeviceCredential: Boolean = false, addressLookup: (suspend (Double, Double) -> List<il.sidewalks.reporter.evidence.AddressSuggestion>)? = null, plateRecognizer: (suspend () -> List<String>)? = null, manualAddressLookup: (suspend (String, String) -> il.sidewalks.reporter.municipal.StreetDirectory.ConfirmableAddress)? = null,
    topicSnapshot: il.sidewalks.reporter.core.CityTopicCache.Snapshot? = null,
    topicsAvailable: Boolean = false, topicsLoading: Boolean = false,
    openTopics: (() -> Unit)? = null, topicSelected: ((String) -> Unit)? = null,
    recentTopics: List<String> = emptyList(), topicAliases: Map<String, List<String>> = emptyMap()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plates by remember(initial.evidence.stableId) { mutableStateOf<List<String>>(emptyList()) }
    var useGoogle by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    var plateEdits by remember { mutableIntStateOf(0) }
    // Status lines hold catalog keys and are translated when shown, so they follow a language switch.
    var ocrStatus by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<il.sidewalks.reporter.evidence.AddressSuggestion>>(emptyList()) }
    var geocoding by remember { mutableStateOf(false) }
    var geocodeStatus by remember { mutableStateOf<String?>(null) }
    var draft by remember(initial.evidence.stableId) { mutableStateOf(initial) }
    LaunchedEffect(initial) { draft = initial }
    var time by remember(initial.evidence.stableId) { mutableStateOf(ReviewTime.display(initial.confirmedTime)) }
    var latitude by remember(initial.evidence.stableId) { mutableStateOf(initial.confirmedLatitude?.toString().orEmpty()) }
    var longitude by remember(initial.evidence.stableId) { mutableStateOf(initial.confirmedLongitude?.toString().orEmpty()) }
    var editing by remember(initial.evidence.stableId) { mutableStateOf(false) }
    var manualStreet by remember(initial.evidence.stableId) { mutableStateOf("") }
    var manualHouse by remember(initial.evidence.stableId) { mutableStateOf("") }
    var manualResult by remember(initial.evidence.stableId) { mutableStateOf<il.sidewalks.reporter.municipal.StreetDirectory.ConfirmableAddress?>(null) }
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
    // Owner instruction 2026-11-10 (phonemsg 01M73E6E2S6AZP46K75QS2S1WY):
    // the separate "confirm time/location/address" and approval buttons are
    // gone - send is the single confirmation. Saving or sending folds the
    // checked time and coordinates into the draft exactly like the removed
    // confirm button did.
    fun foldedDraft(): ReviewDraft {
        val iso = ReviewTime.toJerusalemIso(time, draft.confirmedTime) ?: return draft
        val lat = latitude.toDoubleOrNull()
        val lon = longitude.toDoubleOrNull()
        val folded = draft.confirmEvidence(iso, lat, lon)
        // Tab review (#266): the fold confirms time and location only, never
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
        val iso = ReviewTime.toJerusalemIso(time, draft.confirmedTime)
        draft = if (iso != null) foldedDraft()
            else draft.copy(unresolved = draft.unresolved + ReviewWarning.TIME_NEEDS_CONFIRMATION.code)
    }
    suspend fun findAddress() {
        val lat = latitude.toDoubleOrNull() ?: return
        val lon = longitude.toDoubleOrNull() ?: return
        geocoding = true
        try {
            suggestions = withContext(Dispatchers.IO) {
                try { addressLookup?.invoke(lat, lon) ?: il.sidewalks.reporter.evidence.AddressResolver(context).suggest(lat, lon) }
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
    suspend fun recognize() {
        recognizing = true
        val plateBefore = draft.plate
        val editsBefore = plateEdits
        try {
            plates = withContext(Dispatchers.Default) {
                try { plateRecognizer?.invoke() ?: if (useGoogle) il.sidewalks.reporter.recognition.MlKitPlateOcr.recognize(image!!.asAndroidBitmap()) else il.sidewalks.reporter.recognition.OfflinePlateOcr(context).recognize(image!!.asAndroidBitmap()) }
                catch (_: LinkageError) { emptyList() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList() }
            }
            if (!il.sidewalks.reporter.core.TopicIdentity.needsPlate(draft.subject)) plates = emptyList()
            if (plateEdits == editsBefore) draft = PlateEntry.prefill(draft, plates, plateBefore)
            ocrStatus = if (plates.isEmpty()) "review.ocr.no_plate" else "review.ocr.suggestions_found"
        } finally { recognizing = false }
    }
    LaunchedEffect(initial.evidence.stableId, image) {
        if (image != null && draft.plate.isEmpty() && il.sidewalks.reporter.core.TopicIdentity.needsPlate(draft.subject)) recognize()
    }
    ProvideStringsIfMissing {
    val s = LocalStrings.current
    if (askAddressConsent) {
        AlertDialog(onDismissRequest = { askAddressConsent = false },
            title = { Text(s.t("review.location.lookup_title")) },
            text = { Text(s.t("review.edit.geocode_note")) },
            confirmButton = { TextButton(onClick = { askAddressConsent = false; scope.launch { findAddress() } }) { Text(s.t("review.location.lookup_yes")) } },
            dismissButton = { TextButton(onClick = { askAddressConsent = false }) { Text(s.t("review.location.lookup_later")) } })
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.t("review.title"), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { editing = !editing }) { Text(s.t(if (editing) "review.edit.done" else "review.edit.start")) }
        }
        image?.let { Image(it, s.t("review.image_description"), modifier = Modifier.fillMaxWidth().height(220.dp)) }
        val selectedCode = il.sidewalks.reporter.core.TopicIdentity.code(draft.subject)
        val selectedTopic = topicSnapshot?.topics?.firstOrNull { it.code == selectedCode }
        val removed = topicSnapshot != null && selectedCode != null && selectedTopic == null
        val canBrowseTopics = topicSnapshot != null || topicsAvailable
        val topicNote = when {
            topicsLoading -> s.t("topics.loading")
            removed -> s.t("topics.removed")
            !canBrowseTopics -> s.t("topics.connection_needed")
            topicSnapshot == null -> s.t("topics.unavailable")
            else -> ""
        }
        TextButton(enabled = canBrowseTopics && !topicsLoading, onClick = {
            openTopics?.invoke(); categoryMenu = true
        }, modifier = Modifier.semantics { stateDescription = topicNote }) {
            Text(selectedTopic?.name ?: s.t("topics.choose"), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        }
        if (topicNote.isNotBlank()) Text(topicNote, Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        topicSnapshot?.let { snapshot ->
            Text(s.t("topics.updated", mapOf("date" to snapshot.checkedAt.atZone(java.time.ZoneId.of("Asia/Jerusalem")).toLocalDate().toString())))
            if (snapshot.stale(java.time.Instant.now())) Text(s.t("topics.stale"), Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        }
        if (categoryMenu && topicSnapshot != null && canBrowseTopics) CityTopicPicker(
            topicSnapshot.topics, selectedCode, recentTopics, topicAliases,
            dismiss = { categoryMenu = false }, choose = { topic ->
                draft = draft.edit(subject = "municipal:" + topic.code)
                if (!il.sidewalks.reporter.core.TopicIdentity.needsPlate(draft.subject)) plates = emptyList()
                topicSelected?.invoke(topic.code); categoryMenu = false
            })
        Text(s.t("review.category_note"), style = MaterialTheme.typography.bodySmall)
        if (!editing) {
            if (il.sidewalks.reporter.core.TopicIdentity.needsPlate(draft.subject)) {
                Text(reviewPlateText(s, draft.plate), style = MaterialTheme.typography.titleMedium)
                if (recognizing) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s.t("review.ocr.analyzing")) }
                Text(ocrStatus?.let { s.t(it) }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                plates.forEach { plate -> TextButton(onClick = { draft = draft.edit(plate = plate) }) { Text(reviewPlateSuggestionText(s, plate)) } }
            }
            Text(reviewAddressText(s, draft.address))
            if (draft.address.isBlank() && draft.evidence.latitude != null && draft.evidence.longitude != null) {
                Text(s.t("review.edit.geocode_note"), style = MaterialTheme.typography.bodySmall)
                if (geocoding) Text(s.t("review.geocode.loading"))
                Text(geocodeStatus?.let { s.t(it) }.orEmpty())
                suggestions.forEach { suggestion ->
                    TextButton(onClick = { draft = draft.edit(address = suggestion.streetAndHouse).copy(unresolved = draft.unresolved + ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code); addressBaseline = suggestion.streetAndHouse; pendingAddressWarnings = pendingAddressWarnings + ReviewWarning.SUGGESTED_ADDRESS_NEEDS_CONFIRMATION.code; editing = true }) {
                        Text(reviewAddressSuggestionText(s, suggestion.streetAndHouse))
                    }
                }
            }
            Text(reviewTimeText(s, draft.confirmedTime))
            Text(draft.exactHebrewText.ifBlank { s.t("review.text_missing") })
            if (draft.unresolved.isNotEmpty()) Text(unresolvedText(s, draft.unresolved), style = MaterialTheme.typography.bodySmall)
        }
        if (editing) {
        Text(s.t("review.edit.original_time_title"))
        Text("\u2066${HebrewPresentation.evidenceTime(s, draft.evidence.capturedAtIso)}\u2069", modifier = Modifier.fillMaxWidth(), style = TextStyle(textDirection = TextDirection.Ltr))

        Text(s.t("review.edit.privacy_note"))
        if (il.sidewalks.reporter.core.TopicIdentity.needsPlate(draft.subject)) {
        Row { Checkbox(checked = useGoogle, enabled = !recognizing, onCheckedChange = { useGoogle = it; plates = emptyList(); ocrStatus = null }); Text(s.t("review.edit.use_mlkit")) }
        Button(enabled = image != null && !recognizing, onClick = { scope.launch { recognize() } }, modifier = Modifier.fillMaxWidth()) { Text(s.t(if (recognizing) "review.edit.recognizing" else "review.edit.suggest_plate")) }
        Text(ocrStatus?.let { s.t(it) }.orEmpty())
        plates.forEach { plate -> TextButton(onClick = { draft = draft.edit(plate = plate) }) { Text(reviewPlateSuggestionText(s, plate)) } }
        if (il.sidewalks.reporter.core.TopicIdentity.needsPlate(draft.subject)) OutlinedTextField(draft.plate, { plateEdits++; draft = draft.edit(plate = PlateEntry.digits(it)) }, singleLine = true,
            isError = draft.plate.isNotBlank() && !draft.plate.matches(Regex("[0-9]{7,8}")),
            visualTransformation = PlateEntry, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, autoCorrectEnabled = false),
            textStyle = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
            label = { Text(s.t("review.edit.plate_field")) }, modifier = Modifier.width(216.dp))
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
            if (draft.evidence.latitude == null || draft.evidence.longitude == null) {
                latitude = ""; longitude = ""; manualResult = null; manualGeneration++
                draft = draft.copy(confirmedLatitude = null, confirmedLongitude = null,
                    unresolved = draft.unresolved + ReviewWarning.LOCATION_NEEDS_CONFIRMATION.code)
            }
        }, label = { Text(s.t("review.edit.address_field")) }, modifier = Modifier.fillMaxWidth())
        if (draft.evidence.latitude != null && draft.evidence.longitude != null) {
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
        Text(LOCAL_TIME_EXAMPLE, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(time, { time = it; draft = draft.copy(unresolved = setOf(ReviewWarning.TIME_NEEDS_CONFIRMATION.code)) }, textStyle = TextStyle(textDirection = TextDirection.Ltr), isError = ReviewTime.toJerusalemIso(time, draft.confirmedTime) == null, label = { Text(s.t("review.edit.time_field")) }, modifier = Modifier.fillMaxWidth())
        Text(reviewAddressText(s, draft.address))
        if (draft.unresolved.isNotEmpty()) Text(unresolvedText(s, draft.unresolved))
        }
        if (latitude.toDoubleOrNull() == null || longitude.toDoubleOrNull() == null) {
            Text(s.t("review.location.missing_plain"))
            OutlinedTextField(manualStreet, { manualStreet = it; manualResult = null; manualGeneration++ }, label = { Text(s.t("review.location.street")) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(manualHouse, { manualHouse = it; manualResult = null; manualGeneration++ }, label = { Text(s.t("review.location.house")) }, modifier = Modifier.fillMaxWidth())
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
        if (onSubmit != null) {
            // Send the draft this screen actually shows, not some other copy.
            // N4: when the lock state disables send, TalkBack announces why.
            val sendModifier = if (noDeviceCredential) Modifier.fillMaxWidth().semantics { stateDescription = s.t("review.submit.no_device_lock") } else Modifier.fillMaxWidth()
            Button(enabled = selectedTopic != null && topicsAvailable && foldedDraft().isReady() && !submitting && missingCredentialKeys.isEmpty() && !noDeviceCredential, onClick = {
                confirmPendingEvidence()
                // Runs 42853563754 / 42880867432: an ENABLED send button's
                // semantics click no-opped silently. First proof point:
                // does the click reach this lambda at all?
                Log.i("SUBMIT_FLOW", "send onClick reached: ready=${draft.isReady()} submitting=$submitting")
                onSubmit(draft)
            }, modifier = sendModifier) { Text(s.t(if (submitting) "review.submit.sending" else "review.submit.button")) }
            if (submissionNote.isNotEmpty()) Text(submissionNote, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("submission-note"))
        } else {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(s.t("review.submit.disabled_button")) }
            Text(s.t("review.submit.unavailable"), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(s.t("review.back")) }
    }
    }
}
