package il.sidewalks.reporter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import il.sidewalks.reporter.core.LocalTopicRanker
import il.sidewalks.reporter.core.MunicipalSubject
import il.sidewalks.reporter.i18n.LocalStrings

@Composable
fun CityTopicPicker(topics: List<MunicipalSubject>, selected: String?, recent: List<String>,
                    aliases: Map<String, List<String>>, dismiss: () -> Unit, choose: (MunicipalSubject) -> Unit) {
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    val ranker = remember(aliases) { LocalTopicRanker(aliases) }
    val results = remember(query, topics, recent) { ranker.search(query, topics, recent) }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(16.dp)) {
                Text(s.t("topics.title"), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(query, { query = it }, label = { Text(s.t("topics.search")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text(s.t("topics.results", mapOf("count" to results.size.toString())),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(results, key = { it.code }) { topic ->
                        TextButton(onClick = { choose(topic) }, modifier = Modifier.fillMaxWidth().semantics {
                            this.selected = topic.code == selected
                            contentDescription = topic.name + ". " + topic.path.joinToString(" / ")
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(topic.name)
                                Text(topic.path.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = dismiss) { Text(s.t("topics.close")) }
            }
        }
    }
}
