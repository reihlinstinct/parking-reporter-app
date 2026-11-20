package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.reihlinstinct.reporter.i18n.LocalStrings

/** Production controls, also rendered by the advanced-settings instrumentation test. */
@Composable
fun AddressDataTools(busy: Boolean, online: Boolean, status: String,
                     onRefresh: () -> Unit, onImport: () -> Unit, onClear: () -> Unit) {
    val strings = LocalStrings.current
    Column(Modifier.testTag("address-data-tools")) {
        Text(strings.t("settings.address.title"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("address-title"))
        Text(strings.t("settings.address.description"))
        Text(strings.t("settings.address.reset_hint"))
        TextButton(enabled = !busy && online, onClick = onRefresh, modifier = Modifier.testTag("address-refresh")) {
            Text(strings.t("settings.address.refresh"))
        }
        TextButton(enabled = !busy, onClick = onImport, modifier = Modifier.testTag("address-import")) {
            Text(strings.t("settings.address.import"))
        }
        TextButton(enabled = !busy, onClick = onClear, modifier = Modifier.testTag("address-clear")) {
            Text(strings.t("settings.address.clear"))
        }
        if (status.isNotBlank()) Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
