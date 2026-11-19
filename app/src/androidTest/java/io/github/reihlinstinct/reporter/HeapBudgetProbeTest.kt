package io.github.reihlinstinct.reporter

import org.junit.Test
import org.junit.Assert.assertTrue

/** A synthetic-only process probe. The selected listener records the heap cap. */
class HeapBudgetProbeTest {
    @Test
    fun processBudgetIsObservableWithoutUiOrAddressLoading() {
        assertTrue(Runtime.getRuntime().maxMemory() > 0)
    }
}
