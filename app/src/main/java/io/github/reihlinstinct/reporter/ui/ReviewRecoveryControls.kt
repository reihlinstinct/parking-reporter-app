package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.reihlinstinct.reporter.i18n.LocalStrings

/** Typed status vocabulary is staged with disabled recovery; no arbitrary disclosure keys. */
enum class ReviewRecoveryMessage(val key: String) {
    RESTORED("review.recovery.restored"),
    RESTORED_SEND("review.recovery.restored_send"),
    EXPIRED("review.recovery.expired"),
    EXPIRED_DELETED("review.recovery.expired_deleted"),
    FAILED("review.recovery.failed"),
    FAILED_DELETED("review.recovery.failed_deleted"),
    FAILED_PERMANENT("review.recovery.failed_permanent"),
    NOT_AVAILABLE("review.recovery.not_available"),
    SAVED_ENDED("review.recovery.saved_ended"),
    RESTART_NEEDED("review.recovery.restart_needed"),
    A11Y_STARTED("review.recovery.a11y_started"),
    A11Y_EXPIRING("review.recovery.a11y_expiring"),
    A11Y_EXPIRED("review.recovery.a11y_expired"),
    A11Y_EXPIRED_DELETED("review.recovery.a11y_expired_deleted"),
    A11Y_CANCELLED("review.recovery.a11y_cancelled"),
    A11Y_RESTORED("review.recovery.a11y_restored"),
    A11Y_FAILED("review.recovery.a11y_failed"),
}

/** Null ports in ordinary builds. These actions never share the Send/device-auth callback. */
data class ReviewRecoveryActions(
    val canStart: Boolean,
    val canRestore: Boolean,
    val busy: Boolean = false,
    val statusKey: ReviewRecoveryMessage? = null,
    val announcementKey: ReviewRecoveryMessage? = null,
    val keep: () -> Unit,
    val restore: () -> Unit,
    val cancel: () -> Unit,
)

@Composable
internal fun ReviewRecoveryControls(actions: ReviewRecoveryActions) {
    val strings = LocalStrings.current
    var askingRestore by remember { mutableStateOf<Boolean?>(null) }
    Column {
        if (actions.canStart) {
            val description = strings.t("review.recovery.keep_cd")
            OutlinedButton(onClick = { askingRestore = false }, enabled = !actions.busy,
                modifier = Modifier.testTag("recovery-keep").semantics { contentDescription = description }) {
                Text(strings.t("review.recovery.keep"))
            }
        }
        if (actions.canRestore) OutlinedButton(onClick = { askingRestore = true }, enabled = !actions.busy,
            modifier = Modifier.testTag("recovery-restore")) { Text(strings.t("review.recovery.restore")) }
        actions.statusKey?.let { key ->
            Text(strings.t(key.key), Modifier.testTag("recovery-status"))
        }
        actions.announcementKey?.let { key ->
            Text(strings.t(key.key), Modifier.testTag("recovery-announcement").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    askingRestore?.let { restoring ->
        AlertDialog(onDismissRequest = { askingRestore = null; actions.cancel() },
            title = { Text(strings.t(if (restoring) "review.recovery.restore" else "review.recovery.keep")) },
            text = { Text(strings.t(if (restoring) "review.recovery.restore_explain" else "review.recovery.keep_explain")) },
            confirmButton = { TextButton(onClick = {
                askingRestore = null
                if (restoring) actions.restore() else actions.keep()
            }) { Text(strings.t("common.ok")) } },
            dismissButton = { TextButton(onClick = { askingRestore = null; actions.cancel() }) {
                Text(strings.t("review.recovery.keep_cancel"))
            } })
    }
}
