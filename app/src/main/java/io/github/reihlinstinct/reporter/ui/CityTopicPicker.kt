package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.reihlinstinct.reporter.core.LocalTopicRanker
import io.github.reihlinstinct.reporter.core.MunicipalSubject
import io.github.reihlinstinct.reporter.i18n.LocalStrings

@Composable
fun CityTopicPicker(topics: List<MunicipalSubject>, selected: String?, recent: List<String>,
                    aliases: Map<String, List<String>>, dismiss: () -> Unit, choose: (MunicipalSubject) -> Unit) {
    io.github.reihlinstinct.reporter.i18n.ProvideStringsIfMissing {
    val s = LocalStrings.current
    val search = rememberTextFieldState()
    val query = search.text.toString()
    val pathsByCode = remember(topics) { topics.groupBy { it.code }.mapValues { (_, rows) ->
        rows.map { it.path.joinToString(" › ") }.distinct().joinToString("\n")
    } }
    val namesByCode = remember(topics) { topics.groupBy { it.code }.mapValues { (_, rows) ->
        rows.map { it.name }.distinct().joinToString(" · ")
    } }
    val ranker = remember(aliases) { LocalTopicRanker(aliases) }
    val results = remember(query, topics, recent) { ranker.search(query, topics, recent) }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(16.dp)) {
                Text(s.t("topics.title"), Modifier.testTag("picker-title"), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(state = search,
                    modifier = Modifier.fillMaxWidth().testTag("picker-search-input"),
                    labelPosition = TextFieldLabelPosition.Above(),
                    label = { Text(s.t("topics.search"), Modifier.fillMaxWidth().wrapContentHeight(unbounded = true).testTag("picker-search-label"),
                        style = MaterialTheme.typography.bodySmall.copy(platformStyle = PlatformTextStyle(includeFontPadding = true))) }, lineLimits = TextFieldLineLimits.SingleLine)
                Text(s.t("topics.results", mapOf("count" to results.size.toString())),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                LazyColumn(Modifier.weight(1f, fill = false).testTag("topic-results").selectableGroup()) {
                    items(results, key = { it.code }) { topic ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = topic.code == selected, role = Role.RadioButton, onClick = { choose(topic) })
                            .semantics {
                                contentDescription = namesByCode.getValue(topic.code) + ". " + pathsByCode.getValue(topic.code)
                            }.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(namesByCode.getValue(topic.code), color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge.copy(platformStyle = PlatformTextStyle(includeFontPadding = true)))
                            Text(pathsByCode.getValue(topic.code), Modifier.fillMaxWidth().wrapContentHeight(unbounded = true).padding(horizontal = 4.dp).testTag("picker-path-${topic.code}"),
                                style = MaterialTheme.typography.bodySmall.copy(platformStyle = PlatformTextStyle(includeFontPadding = true)))
                        }

                    }
                }
                TextButton(onClick = dismiss) { Text(s.t("topics.close")) }
            }
        }
    }
}
}
