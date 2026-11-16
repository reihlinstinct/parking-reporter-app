package io.github.reihlinstinct.reporter.municipal

import io.github.reihlinstinct.reporter.core.CityTopicCache

/** One fresh read per entry/retry. Never use stale data to edit preferences. */
class FreshPlateCatalog(private val cache: CityTopicCache) {
    private val generation = java.util.concurrent.atomic.AtomicLong()
    fun refresh(online: Boolean, factory: () -> MunicipalClient): CityTopicCache.Snapshot {
        val request = generation.incrementAndGet()
        check(online)
        val client = factory()
        // Authentication only, followed by the same read-only catalog GET. No contact/report writes.
        val topics = client.subjectCategories(client.login())
        synchronized(this) {
            check(generation.get() == request) { "Superseded catalog response" }
            return cache.replace(topics)
        }
    }
}
