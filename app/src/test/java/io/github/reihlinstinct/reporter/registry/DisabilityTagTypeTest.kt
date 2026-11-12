package io.github.reihlinstinct.reporter.registry

import org.junit.Assert.assertEquals
import org.junit.Test

class DisabilityTagTypeTest {
    @Test fun ownerConfirmedTypes() {
        assertEquals(DisabilityTagType.TRIANGLE, DisabilityTagType.fromRegistry(1))
        assertEquals(DisabilityTagType.WHEELCHAIR, DisabilityTagType.fromRegistry(2))
    }
    @Test fun otherTypesAreNeutral() {
        listOf(null, -1, 0, 3, 4, Int.MAX_VALUE).forEach {
            assertEquals(DisabilityTagType.UNKNOWN, DisabilityTagType.fromRegistry(it))
        }
    }
}
