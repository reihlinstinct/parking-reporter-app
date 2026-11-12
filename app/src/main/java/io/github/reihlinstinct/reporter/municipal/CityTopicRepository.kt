package io.github.reihlinstinct.reporter.municipal

import android.content.Context
import io.github.reihlinstinct.reporter.core.CityTopicCache
import io.github.reihlinstinct.reporter.core.MunicipalSubject
import io.github.reihlinstinct.reporter.security.ReporterSettings
import java.io.File
import java.time.Instant

/** One initial fetch, then stale refresh only on successful submission's session. */
class CityTopicRepository(context: Context) {
    private val cache = CityTopicCache(File(context.noBackupFilesDir, "city-topics/catalog.json"))
    private val fresh = FreshPlateCatalog(cache)
    private val platePrefs = context.getSharedPreferences("plate-topic-enables", Context.MODE_PRIVATE)
    private val sync = TopicCatalogSync(cache)
    private val recent = context.getSharedPreferences("recent-city-topics", Context.MODE_PRIVATE)
    fun plateEnables(): Set<String> = platePrefs.getStringSet("codes", emptySet()).orEmpty().toSet()
    fun enablePlate(code: String, enabled: Boolean) {
        require(topics().any { it.code == code })
        val next = plateEnables().toMutableSet().apply { if (enabled) add(code) else remove(code) }
        platePrefs.edit().putStringSet("codes", next).apply()
    }
    fun forcePlateRefresh(online: Boolean, factory: () -> MunicipalClient) = fresh.refresh(online, factory)
    fun snapshot() = cache.read()
    fun recentCodes(): List<String> = recent.getString("codes", "").orEmpty().split(',').filter { it.isNotBlank() }
    fun selected(code: String) { recent.edit().putString("codes", (listOf(code) + recentCodes().filter { it != code }).take(5).joinToString(",")).apply() }
    fun initial(settings: ReporterSettings, factory: (ReporterSettings) -> MunicipalClient): CityTopicCache.Snapshot {
        snapshot()?.let { return it }
        check(settings.hasMunicipalCredentials())
        return sync.initial { factory(settings) }
    }
    fun afterSuccess(client: MunicipalClient, token: String) = sync.afterSuccess(client, token)
    fun topics(): List<MunicipalSubject> = snapshot()?.topics.orEmpty()
}
