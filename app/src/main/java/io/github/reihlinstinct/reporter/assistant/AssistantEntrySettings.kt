package io.github.reihlinstinct.reporter.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.reihlinstinct.reporter.i18n.LocalStrings
import kotlinx.coroutines.launch

/** Only shown in full Settings, not basic reporter setup. Default is OFF. */
@Composable
fun AssistantEntrySettings() {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val store = remember(context) { AssistantEntryStore(context) }
    var enabled by remember { mutableStateOf(store.enabled()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val supported = AssistantEntry.supported()
    HorizontalDivider()
    Text(strings.t("settings.assistant.title"), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
        value = enabled, enabled = supported && !busy, role = Role.Switch,
        onValueChange = { wanted ->
            busy = true
            scope.launch {
                val success = AssistantEntry.setEnabled(context, wanted)
                enabled = store.enabled()
                failed = !success
                busy = false
            }
        }), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.t("settings.assistant.enable"), Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null, enabled = supported && !busy)
    }
    Text(strings.t(if (supported) "settings.assistant.note" else "settings.assistant.unsupported"),
        style = MaterialTheme.typography.bodySmall)
    if (failed) Text(strings.t("settings.assistant.failed"),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
}
