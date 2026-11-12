package io.github.reihlinstinct.reporter.municipal

import io.github.reihlinstinct.reporter.core.CityTopicCache

/** One fresh read per entry/retry. Never use stale data to edit preferences. */
class FreshPlateCatalog(private val cache: CityTopicCache) {
    @Synchronized fun refresh(online: Boolean, factory: () -> MunicipalClient): CityTopicCache.Snapshot {
        check(online)
        val client = factory()
        // Authentication only, followed by the same read-only catalog GET. No contact/report writes.
        return cache.replace(client.subjectCategories(client.login()))
    }
}
