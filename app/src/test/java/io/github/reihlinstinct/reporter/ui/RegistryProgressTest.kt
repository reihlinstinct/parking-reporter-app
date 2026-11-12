package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.registry.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RegistryProgressTest {
    @Test fun completedTagPublishedBeforeSlowVehicleAndErrorRetainsTag() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val tagReady = CompletableDeferred<RegistrySnapshot>()
        val updates = mutableListOf<RegistrySnapshot>()
        val job = async { readRegistrySnapshot("1234567",
            { DisabilityLookupResult.Found(DisabilityTagType.WHEELCHAIR) },
            { release.await(); VehicleLookupResult.Error(LookupErrorKind.NETWORK) },
            { updates.add(it); if (it.tag != null) tagReady.complete(it) }) }
        val partial = tagReady.await()
        assertTrue(partial.tag is DisabilityLookupResult.Found)
        assertNull(partial.vehicle)
        assertFalse(job.isCompleted)
        release.complete(Unit)
        val final = job.await()
        assertTrue(final.tag is DisabilityLookupResult.Found)
        assertEquals(VehicleLookupResult.Error(LookupErrorKind.NETWORK), final.vehicle)
        assertEquals(2, updates.size)
    }
    @Test fun cancelledPlateNeverPublishesLateNoncancellableResponse() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var published = 0
        val job = launch { readRegistrySnapshot("1234567",
            { started.complete(Unit); withContext(NonCancellable) { release.await() }; DisabilityLookupResult.NotFound },
            { awaitCancellation() }, { published++ }) }
        started.await(); job.cancel(); release.complete(Unit); job.join()
        assertEquals(0, published)
    }
    @Test fun licenceBoundariesUseJerusalemCheckDateNotUtcOrLaterClock() {
        val checked = java.time.Instant.parse("2026-11-12T22:30:00Z") // Nov13 Jerusalem
        val today = java.time.LocalDate.of(2026, 11, 13)
        for (days in listOf(-1L, 0L, 1L, 13L, 14L)) {
            assertEquals(days, licenceDaysRemaining(today.plusDays(days), checked))
        }
        assertNull(licenceDaysRemaining(null, checked))
    }

    @Test fun tagPendingOnlyWhileVehicleResolvedAndTagMissing() {
        val now = java.time.Instant.now()
        val vehicleDone = VehicleLookupResult.Error(LookupErrorKind.NETWORK)
        assertTrue(tagPending(RegistrySnapshot(null, vehicleDone, now)))
        assertFalse("nothing resolved yet shows the generic loading line", tagPending(RegistrySnapshot(null, null, now)))
        assertFalse("tag resolved, including NotFound", tagPending(RegistrySnapshot(DisabilityLookupResult.NotFound, vehicleDone, now)))
        assertFalse(tagPending(RegistrySnapshot(DisabilityLookupResult.Found(DisabilityTagType.WHEELCHAIR), vehicleDone, now)))
    }
}
