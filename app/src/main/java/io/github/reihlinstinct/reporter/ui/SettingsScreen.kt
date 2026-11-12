package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.reihlinstinct.reporter.BuildConfig
import io.github.reihlinstinct.reporter.i18n.Language
import io.github.reihlinstinct.reporter.i18n.LanguageResolver
import io.github.reihlinstinct.reporter.i18n.LocalStrings
import io.github.reihlinstinct.reporter.i18n.ProvideStringsIfMissing
import io.github.reihlinstinct.reporter.i18n.TextDir
import io.github.reihlinstinct.reporter.security.ReporterSettings

/** A save merges blank PERSONAL fields against the freshly read stored settings:
 *  a blank personal field means "keep the stored value". The municipal credential
 *  fields save exactly as typed when [touchedMunicipal] is null (typed is final);
 *  when the caller passes the screen's touched set, an untouched credential field
 *  keeps its stored value and only a field the user actually edited can clear it
 *  (owner 2026-11-09: clearing a credential field and saving removes its value). */
internal fun mergeReporterSettings(value: ReporterSettings, stored: ReporterSettings?, touchedMunicipal: Set<String>? = null): ReporterSettings {
    val merged = value.copy(
        firstName = value.firstName.ifBlank { stored?.firstName.orEmpty() },
        lastName = value.lastName.ifBlank { stored?.lastName.orEmpty() },
        phone = value.phone.ifBlank { stored?.phone.orEmpty() },
        idNumber = value.idNumber.ifBlank { stored?.idNumber.orEmpty() },
        passportNumber = value.passportNumber.ifBlank { stored?.passportNumber.orEmpty() },
        email = value.email.ifBlank { stored?.email.orEmpty() })
    if (touchedMunicipal == null) return merged
    return merged.copy(
        municipalSubscriptionKey = if ("subscription_key" in touchedMunicipal) merged.municipalSubscriptionKey else stored?.municipalSubscriptionKey.orEmpty(),
        municipalUsername = if ("api_username" in touchedMunicipal) merged.municipalUsername else stored?.municipalUsername.orEmpty(),
        municipalPassword = if ("api_password" in touchedMunicipal) merged.municipalPassword else stored?.municipalPassword.orEmpty())
}

/** Issue #295: an inline error or hint announced to screen readers when it
 *  appears; without a live region TalkBack never says why save is unavailable. */
@Composable
private fun AnnouncedNote(text: String) {
    Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

/** Caller obtains fresh system authentication before opening and again before saving.
 *  [noDeviceCredential] greys the save button and says why (issue #295): the unlock
 *  gate cannot run on a lock-less device, and without this the tap was swallowed
 *  silently - the same swallow issue #260 fixed on the review screen's send. */
@Composable
fun SettingsScreen(
    initial: ReporterSettings?,
    save: (ReporterSettings, Set<String>, () -> Unit) -> Unit,
    back: () -> Unit,
    debugTools: (@Composable () -> Unit)? = null,
    selectedLanguage: String? = null,
    onLanguageSelected: ((String?) -> Unit)? = null,
    onLicenses: (() -> Unit)? = null,
    basicSetup: Boolean = false,
    setupError: String = "",
    noDeviceCredential: Boolean = false,
    onPlateCategories: (() -> Unit)? = null, plateCategoriesOnline: Boolean = false,
) {
    var firstName by remember { mutableStateOf(initial?.firstName.orEmpty()) }
    var lastName by remember { mutableStateOf(initial?.lastName.orEmpty()) }
    var id by remember { mutableStateOf(initial?.idNumber.orEmpty()) }
    var passportMode by remember { mutableStateOf(initial?.passportMode ?: false) }
    var passport by remember { mutableStateOf(initial?.passportNumber.orEmpty()) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var email by remember { mutableStateOf(initial?.email.orEmpty()) }
    var subscriptionKey by remember { mutableStateOf(initial?.municipalSubscriptionKey.orEmpty()) }
    var apiUsername by remember { mutableStateOf(initial?.municipalUsername.orEmpty()) }
    var apiPassword by remember { mutableStateOf(initial?.municipalPassword.orEmpty()) }
    // Municipal fields the user actually edited this visit: only a touched field
    // saves as typed (blank = cleared); an untouched field keeps its stored value.
    var touchedMunicipal by remember { mutableStateOf(setOf<String>()) }
    var saved by remember { mutableStateOf(false) }
    var phoneFocused by remember { mutableStateOf(false) }
    var advanced106 by remember { mutableStateOf(false) }
    ProvideStringsIfMissing {
    val s = LocalStrings.current
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (basicSetup) {
            Text(s.t("setup.title"), style = MaterialTheme.typography.headlineMedium)
            Text(s.t("setup.description"))
            onLanguageSelected?.let { LanguageMenu(s.catalog.languages, selectedLanguage, it) }
            if (setupError.isNotBlank()) Text(setupError, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else Text(s.t("settings.privacy.mlkit_note"))
        Text(s.t("settings.privacy.title"), style = MaterialTheme.typography.titleLarge)
        if (!basicSetup) Text(s.t("settings.privacy.description"))
        OutlinedTextField(firstName, { firstName = ReporterInput.nameInput(it); saved = false }, label = { Text(s.t("settings.field.first_name")) }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(lastName, { lastName = ReporterInput.nameInput(it); saved = false }, label = { Text(s.t("settings.field.last_name")) }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = passportMode, onCheckedChange = { passportMode = it; saved = false })
            Text(s.t("settings.passport.mode"))
        }
        // Issue #295: the passport field shows why it blocks the save button, like the
        // ID/phone/email fields already do; before this an invalid (or, on first run,
        // empty) passport greyed the button with no message anywhere.
        val passportInvalid = passport.isNotBlank() && !io.github.reihlinstinct.reporter.municipal.PassportSubmission.validPassport(passport)
        val passportMissing = passport.isBlank() && initial?.passportNumber.isNullOrBlank()
        if (passportMode) {
            OutlinedTextField(passport, { passport = it; saved = false }, label = { Text(s.t("settings.passport.number")) }, isError = passportInvalid, supportingText = { if (passportInvalid) AnnouncedNote(s.t("settings.passport.invalid")) else if (passportMissing) AnnouncedNote(s.t("settings.passport.required")) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        } else OutlinedTextField(id, { id = ReporterInput.digits(it); saved = false }, label = { Text(s.t("settings.field.id_number")) }, isError = id.isNotBlank() && !ReporterInput.validId(id), supportingText = { if (id.isNotBlank() && !ReporterInput.validId(id)) AnnouncedNote(s.t("settings.id.invalid")) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = ReporterInput.phoneInput(it); saved = false },
            label = { Text(s.t("settings.field.phone")) },
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            isError = phone.isNotBlank() && !ReporterInput.validPhone(phone),
            supportingText = {
                if (phone.isNotBlank() && !ReporterInput.validPhone(phone)) AnnouncedNote(s.t("settings.phone.invalid"))
                else if (phone.isNotBlank() && !io.github.reihlinstinct.reporter.municipal.PassportSubmission.isIsraeliPhone(phone)) AnnouncedNote(s.t("settings.phone.international"))
            },
            visualTransformation = if (phoneFocused) VisualTransformation.None else PhoneDisplayTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                phoneFocused = focus.isFocused
                if (!focus.isFocused) phone = ReporterInput.canonicalPhone(phone)
            })
        OutlinedTextField(email, { email = it; saved = false }, label = { Text(s.t("settings.field.email")) }, isError = email.isNotBlank() && !ReporterInput.validEmail(email), supportingText = { if (email.isNotBlank() && !ReporterInput.validEmail(email)) AnnouncedNote(s.t("settings.email.invalid")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        if (!basicSetup) {
        HorizontalDivider()
        TextButton(onClick = { advanced106 = !advanced106 }) { Text(s.t("settings.api.advanced")) }
        if (advanced106) {
        if (onPlateCategories != null) {
            TextButton(enabled = plateCategoriesOnline, onClick = onPlateCategories) { Text(s.t("plate_categories.title")) }
            if (!plateCategoriesOnline) AnnouncedNote(s.t("plate_categories.network_required"))
        }
        Text(s.t("settings.api.title"), style = MaterialTheme.typography.titleMedium)
        Text(s.t("settings.api.description"), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(subscriptionKey, { subscriptionKey = it; saved = false; touchedMunicipal = touchedMunicipal + "subscription_key" }, label = { Text(s.t("settings.field.subscription_key")) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiUsername, { apiUsername = it; saved = false; touchedMunicipal = touchedMunicipal + "api_username" }, label = { Text(s.t("settings.field.api_username")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(apiPassword, { apiPassword = it; saved = false; touchedMunicipal = touchedMunicipal + "api_password" }, label = { Text(s.t("settings.field.api_password")) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
        }
        }
        // Blank personal fields keep the stored values via mergeReporterSettings in the
        // caller's save callback; a municipal credential field saves as typed only when
        // the user touched it (blank = cleared), otherwise its stored value is kept.
        // The fields clear after a save so no credential lingers on screen; a touched-then-
        // blanked credential still enables the button, so clearing works on a blank screen too.
        // stays disabled when every field is blank (and nothing was touched) or right after a save.
        val anyField = listOf(firstName, lastName, id, passport, phone, email, subscriptionKey, apiUsername, apiPassword).any { it.isNotBlank() }
        // Issue #295 (owner 2026-11-11): the button stays greyed until every field a 106
        // report needs is filled and valid, and says next to the button what is missing.
        // Blank personal fields keep their stored values, so the check runs on the
        // effective (typed-or-stored) values; municipal credentials stay optional and
        // are named at send time instead.
        val effFirstName = firstName.ifBlank { initial?.firstName.orEmpty() }
        val effLastName = lastName.ifBlank { initial?.lastName.orEmpty() }
        val effPhone = if (phone.isBlank()) initial?.phone.orEmpty() else ReporterInput.canonicalPhone(phone)
        val effEmail = email.ifBlank { initial?.email.orEmpty() }
        val effId = id.ifBlank { initial?.idNumber.orEmpty() }
        val effPassport = passport.ifBlank { initial?.passportNumber.orEmpty() }
        val missingRequired = buildList {
            if (effFirstName.isBlank()) add("settings.field.first_name")
            if (effLastName.isBlank()) add("settings.field.last_name")
            if (effPhone.isBlank() || !ReporterInput.validPhone(effPhone)) add("settings.field.phone")
            if (effEmail.isBlank() || !ReporterInput.validEmail(effEmail)) add("settings.field.email")
            if (passportMode) {
                if (!io.github.reihlinstinct.reporter.municipal.PassportSubmission.validPassport(effPassport)) add("settings.passport.number")
            } else if (effId.isBlank() || !ReporterInput.validId(effId)) add("settings.field.id_number")
        }
        val missingNote = if (missingRequired.isEmpty()) "" else s.t("settings.save.missing_fields") + " " + missingRequired.joinToString(", ") { s.t(it) }
        // Issue #295: no lockscreen - the save's unlock gate can never succeed, so
        // grey the button and say why (visible and TalkBack) instead of swallowing taps.
        if (noDeviceCredential) AnnouncedNote(s.t("settings.save.no_device_lock"))
        if (missingNote.isNotEmpty()) AnnouncedNote(missingNote)
        val saveDisabledReason = listOf(if (noDeviceCredential) s.t("settings.save.no_device_lock") else "", missingNote).filter { it.isNotEmpty() }.joinToString(". ")
        val saveModifier = if (saveDisabledReason.isNotEmpty()) Modifier.fillMaxWidth().semantics { stateDescription = saveDisabledReason } else Modifier.fillMaxWidth()
        Button(enabled = !noDeviceCredential && !saved && (anyField || touchedMunicipal.isNotEmpty()) && missingRequired.isEmpty(), onClick = { save(ReporterSettings(firstName.trim(), lastName.trim(), ReporterInput.canonicalPhone(phone), id.trim(), email.trim(),
            passportMode = passportMode, passportNumber = passport.trim(),
            municipalSubscriptionKey = subscriptionKey.trim(),
            municipalUsername = apiUsername.trim(),
            municipalPassword = apiPassword.trim()), touchedMunicipal) {
            firstName = ""; lastName = ""; id = ""; passport = ""; phone = ""; email = ""
            subscriptionKey = ""; apiUsername = ""; apiPassword = ""
            touchedMunicipal = setOf(); saved = true
        } }, modifier = saveModifier) { Text(if (basicSetup) s.t("setup.save_continue") else s.t("settings.save.button")) }
        if (saved) Text(s.t("settings.save.done"))
        // The UI language lives in "ui-prefs": picking one never calls save, never asks for
        // authentication and never touches the fields above or the encrypted settings.
        if (!basicSetup) {
            val (dynamic, setDynamic) = io.github.reihlinstinct.reporter.ui.theme.rememberDynamicPalette()
            val supported = io.github.reihlinstinct.reporter.ui.theme.supportsDynamicPalette()
            HorizontalDivider()
            Text(s.t("settings.appearance.title"), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = dynamic, enabled = supported, role = Role.Switch, onValueChange = setDynamic),
                verticalAlignment = Alignment.CenterVertically) {
                Text(s.t("settings.appearance.dynamic"), Modifier.weight(1f))
                Switch(checked = dynamic, onCheckedChange = null, enabled = supported)
            }
            AnnouncedNote(if (supported) s.t("settings.appearance.dynamic_note") else s.t("settings.appearance.needs_android12"))
        }
        if (!basicSetup) {
            io.github.reihlinstinct.reporter.assistant.AssistantEntrySettings()
        }
        if (!basicSetup) onLanguageSelected?.let { select ->
            HorizontalDivider()
            LanguagePicker(s.catalog.languages, selectedLanguage, select)
        }
        debugTools?.let { tools ->
            HorizontalDivider()
            tools()
        }
        onLicenses?.let { open ->
            TextButton(onClick = open, modifier = Modifier.fillMaxWidth()) { Text(s.t("licenses.screen.title")) }
        }
        TextButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text(if (basicSetup) s.t("setup.later") else s.t("settings.back")) }
        Text(s.t("settings.version", mapOf("version" to BuildConfig.VERSION_NAME)), style = MaterialTheme.typography.bodySmall)
    }
    }
}

/** System default first, then each catalog language by its native name, in catalog order. */
@Composable
private fun LanguagePicker(languages: List<Language>, selected: String?, onSelect: (String?) -> Unit) {
    val s = LocalStrings.current
    Text(s.t("settings.language.title"), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.selectableGroup()) {
        LanguageOption(s.t("settings.language.system_default"), null, selected == null) { onSelect(null) }
        languages.forEach { language ->
            LanguageOption(LanguageResolver.pickerLabel(language), language, selected == language.code) { onSelect(language.code) }
        }
    }
}

/** A native name is shown in its own language and direction, whatever the active UI language. */
@Composable
private fun LanguageOption(label: String, language: Language?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        val style = if (language == null) {
            LocalTextStyle.current
        } else {
            LocalTextStyle.current.copy(
                textDirection = if (language.dir == TextDir.RTL) TextDirection.Rtl else TextDirection.Ltr,
                localeList = LocaleList(language.code),
            )
        }
        Text(label, style = style)
    }
}

/** Compact language selection for onboarding, with native language names. No profile access. */
@Composable
private fun LanguageMenu(languages: List<Language>, selected: String?, onSelect: (String?) -> Unit) {
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val current = languages.firstOrNull { it.code == selected }?.let { LanguageResolver.pickerLabel(it) }
        ?: s.t("settings.language.system_default")
    Text(s.t("settings.language.title"), style = MaterialTheme.typography.titleMedium)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(s.t("settings.language.system_default")) }, onClick = { expanded = false; onSelect(null) })
            languages.forEach { language ->
                DropdownMenuItem(text = { Text(LanguageResolver.pickerLabel(language)) }, onClick = { expanded = false; onSelect(language.code) })
            }
        }
    }
}
