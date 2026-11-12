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
    private val sync = TopicCatalogSync(cache)
    private val recent = context.getSharedPreferences("recent-city-topics", Context.MODE_PRIVATE)
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
