package net.plumt.ads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnabledSourceTest {
    @Test fun alwaysIsTrue() = runTest {
        assertTrue(AdsEnabledSource.Always.enabled.first())
    }

    @Test fun mutableDefaultsToEnabledAndEmitsChanges() = runTest(UnconfinedTestDispatcher()) {
        val src = MutableAdsEnabledSource()
        assertTrue(src.enabled.value)
        val seen = mutableListOf<Boolean>()
        val job = launch { src.enabled.toList(seen) }
        src.set(false); src.set(true); src.set(true)
        job.cancel()
        assertEquals(listOf(true, false, true), seen)
    }

    @Test fun mutableInitialFalse() {
        assertFalse(MutableAdsEnabledSource(false).enabled.value)
    }

    @Test fun fromWrapsAnyFlow() = runTest {
        val flow = MutableStateFlow(false)
        assertFalse(AdsEnabledSource.from(flow).enabled.first())
    }
}
