package io.github.reihlinstinct.reporter.municipal

import io.github.reihlinstinct.reporter.core.CityTopicCache
import java.time.Instant

/** Android-free lifecycle, so bootstrap and session reuse are directly testable. */
class TopicCatalogSync(private val cache: CityTopicCache, private val now: () -> Instant = Instant::now) {
    @Synchronized fun initial(clientFactory: () -> MunicipalClient): CityTopicCache.Snapshot {
        cache.read()?.let { return it }
        val client = clientFactory()
        return cache.replace(client.subjectCategories(client.login()))
    }
    @Synchronized fun afterSuccess(client: MunicipalClient, token: String) {
        val existing = cache.read()
        if (existing == null || existing.refreshDue(now())) {
            // Report state is already complete. Never retry or obscure it.
            runCatching { cache.replace(client.subjectCategories(token)) }
        }
    }
}
