package il.sidewalks.reporter

import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.core.CityTopicCache
import il.sidewalks.reporter.core.MunicipalSubjects
import java.time.Instant

/** Explicit city-captured synthetic test fixture, never a production fallback. */
fun syntheticCityTopics(): CityTopicCache.Snapshot {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val catalog = context.assets.open(MunicipalSubjects.ASSET_PATH).use { MunicipalSubjects.load(it) }
    return CityTopicCache.Snapshot(catalog.subjects, Instant.now(), catalog.computedFingerprint())
}
fun seedSyntheticCityTopics() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    CityTopicCache(java.io.File(context.noBackupFilesDir, "city-topics/catalog.json")).replace(syntheticCityTopics().topics)
    il.sidewalks.reporter.security.SettingsProfileStore(context).save(emptyList())
}
