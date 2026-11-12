package io.github.reihlinstinct.reporter.ui

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.reihlinstinct.reporter.core.*
import io.github.reihlinstinct.reporter.i18n.LocalStrings
import kotlinx.coroutines.*

@Composable
fun rememberNetworkOnline(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(ConnectivityManager::class.java) }
    fun online() = manager.activeNetwork?.let { manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true
    var available by remember { mutableStateOf(online()) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { available = online() }
            override fun onLost(network: Network) { available = online() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { available = online() }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return available
}

/** Fresh response only. Leaving cancels delivery, retry replaces the generation, never shows cached rows. */
@Composable
fun PlateCategoriesScreen(online: Boolean, initialEnables: Set<String>, refresh: suspend () -> CityTopicCache.Snapshot,
                          enable: (String, Boolean) -> Unit, back: () -> Unit) {
    io.github.reihlinstinct.reporter.i18n.ProvideStringsIfMissing {
    val s = LocalStrings.current
    var attempt by remember { mutableIntStateOf(0) }
    var snapshot by remember(attempt, online) { mutableStateOf<CityTopicCache.Snapshot?>(null) }
    var failed by remember(attempt, online) { mutableStateOf(false) }
    var enables by remember { mutableStateOf(initialEnables) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(attempt, online) {
        if (online) try { snapshot = withContext(Dispatchers.IO) { refresh() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.t("plate_categories.title"), style = MaterialTheme.typography.titleLarge)
        Text(s.t("plate_categories.description"))
        when {
            !online -> Text(s.t("plate_categories.network_required"), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            failed -> {
                Text(s.t("plate_categories.failed"), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Button(onClick = { attempt++ }) { Text(s.t("plate_categories.retry")) }
            }
            snapshot == null -> Text(s.t("plate_categories.loading"), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            else -> {
                val topics = snapshot!!.topics
                OutlinedTextField(query, { query = it }, label = { Text(s.t("plate_categories.search")) }, modifier = Modifier.fillMaxWidth())
                topics.distinctBy { it.code }.filter { query.isBlank() || it.name.contains(query, true) || it.code.contains(query) }.forEach { topic ->
                    val forced = topic.plateRequired == true
                    // Bootstrap defaults apply without a preference. Enabling is additive; disabling an
                    // override restores metadata/default behavior rather than turning off requirements.
                    Row {
                        Checkbox(checked = forced || topic.code in enables, enabled = !forced, onCheckedChange = { checked ->
                            enable(topic.code, checked)
                            enables = if (checked) enables + topic.code else enables - topic.code
                        })
                        Column {
                            Text(topic.name + " (" + topic.code + ")")
                            if (PlatePolicy.required(topic.code, topics, enables)) Text(s.t("plate_categories.required"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        TextButton(onClick = back) { Text(s.t("review.back")) }
    }
}
}
