package il.sidewalks.reporter.municipal

import android.content.Context
import il.sidewalks.reporter.core.CityTopicCache
import il.sidewalks.reporter.core.MunicipalSubject
import il.sidewalks.reporter.security.ReporterSettings
import java.io.File
import java.time.Instant

/** One initial fetch, then stale refresh only on successful submission's session. */
class CityTopicRepository(context: Context) {
    private val cache = CityTopicCache(File(context.noBackupFilesDir, "city-topics/catalog.json"))
    private val recent = context.getSharedPreferences("recent-city-topics", Context.MODE_PRIVATE)
    fun snapshot() = cache.read()
    fun recentCodes(): List<String> = recent.getString("codes", "").orEmpty().split(',').filter { it.isNotBlank() }
    fun selected(code: String) { recent.edit().putString("codes", (listOf(code) + recentCodes().filter { it != code }).take(5).joinToString(",")).apply() }
    fun initial(settings: ReporterSettings, factory: (ReporterSettings) -> MunicipalClient): CityTopicCache.Snapshot {
        snapshot()?.let { return it }
        check(settings.hasMunicipalCredentials())
        val client = factory(settings)
        return cache.replace(client.subjectCategories(client.login()))
    }
    fun afterSuccess(client: MunicipalClient, token: String) {
        val existing = snapshot()
        if (existing == null || existing.refreshDue(Instant.now())) {
            // Catalog failure must never turn a successful report into a retry.
            runCatching { cache.replace(client.subjectCategories(token)) }
        }
    }
    fun topics(): List<MunicipalSubject> = snapshot()?.topics.orEmpty()
}
