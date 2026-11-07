package net.plumt.ads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 종료 팝업용 앱 수명 네이티브 캐시: 미리 받기·만료·소비·스위치·대체 단위. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrefetchedNativeAdTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val provider = FakeNativeProvider()
    private val available = MutableStateFlow(true)
    private val events = RecordingEvents()
    private val clock = FakeClock()
    private val job = Job()

    @After fun tearDown() = job.cancel()

    private fun TestScope.cache(chain: List<String> = listOf("id"), ttl: Long = 1_000) = PrefetchedNativeAd(
        AdUnit.native("exit", ""), "exit", ttl, chain, context, provider, available, events, clock,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
    )

    @Test fun nothingIsRequestedUntilPrefetch() = runTest {
        val c = cache()
        assertNull(c.ad.value)
        assertTrue(provider.requestedIds.isEmpty())
        c.prefetch()
        assertEquals(listOf("id"), provider.requestedIds)
        assertSame(provider.produced.single(), c.fresh())
        assertFalse(c.loading)
        c.prefetch() // 이미 있으면 다시 받지 않는다
        assertEquals(1, provider.requestedIds.size)
        assertEquals(1, events.count<AdEvent.Loaded>())
    }

    @Test fun expiredAdIsNotFreshAndReplacedOnNextPrefetch() = runTest {
        val c = cache(ttl = 1_000)
        c.prefetch()
        val first = provider.produced.single()
        clock.advance(999)
        assertTrue(c.isFresh(first))
        clock.advance(1)
        assertNull(c.fresh())
        assertFalse(c.isFresh(first))
        c.prefetch()
        assertEquals(1, first.destroyed)
        assertEquals(2, provider.produced.size)
        assertSame(provider.produced[1], c.fresh())
    }

    @Test fun consumeDestroysAndFetchesNext() = runTest {
        val c = cache()
        c.prefetch()
        val first = provider.produced.single()
        c.consume(first)
        assertEquals(1, first.destroyed)
        assertEquals(2, provider.produced.size)
        // 들고 있지 않은 광고를 돌려주면 지금 것은 그대로다
        c.consume(first)
        assertEquals(0, provider.produced[1].destroyed)
        assertFalse(c.isFresh(first))
    }

    @Test fun switchOffDropsAndSwitchOnRefillsOnlyAfterPrefetchWasAsked() = runTest {
        val c = cache()
        available.value = false
        available.value = true
        assertTrue(provider.requestedIds.isEmpty()) // 아직 아무도 원하지 않았다
        c.prefetch()
        val first = provider.produced.single()
        available.value = false
        assertEquals(1, first.destroyed)
        assertNull(c.ad.value)
        c.prefetch() // 꺼진 동안은 요청하지 않는다
        assertEquals(1, provider.requestedIds.size)
        available.value = true
        assertEquals(2, provider.requestedIds.size)
        assertTrue(c.fresh() != null)
    }

    @Test fun adArrivingAfterSwitchOffIsDestroyed() = runTest {
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val c = cache()
        c.prefetch()
        assertTrue(c.loading)
        available.value = false
        gate.complete(Unit)
        runCurrent()
        assertNull(c.ad.value)
    }

    @Test fun lateArrivalAfterSwitchOffWhileNotCancelledIsDestroyed() = runTest {
        // 공급자가 취소를 무시하고 돌려줘도 꺼진 상태면 버린다
        val c = cache()
        val handle = FakeNativeHandle()
        provider.results.addLast(listOf(handle))
        val custom = PrefetchedNativeAd(
            AdUnit.native("exit", ""), "exit", 1_000, listOf("id"), context,
            { _, _, _ -> available.value = false; listOf(handle) }, available, events, clock,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
        )
        custom.prefetch()
        assertEquals(1, handle.destroyed)
        assertNull(custom.ad.value)
        assertNull(c.ad.value)
    }

    @Test fun failureFallsBackToNextUnitAndIsRecorded() = runTest {
        provider.results.addLast(AdsException(AdsErrorKind.Load, 3, "no fill", reason = AdErrorReason.NoFill))
        val c = cache(chain = listOf("primary", "fallback"))
        c.prefetch()
        assertEquals(listOf("primary", "fallback"), provider.requestedIds)
        assertEquals("fallback", (c.fresh() as FakeNativeHandle).id)
        assertNull(c.lastError.value)
        assertEquals(AdErrorReason.NoFill, events.list.filterIsInstance<AdEvent.LoadFailed>().single().reason)
    }

    @Test fun allFailKeepsLastErrorAndNonSdkExceptionIsWrapped() = runTest {
        provider.results.addLast(IllegalStateException("boom"))
        val c = cache()
        c.prefetch()
        val e = c.lastError.value!!
        assertEquals(AdsErrorKind.Load, e.kind)
        assertTrue(e.cause is IllegalStateException)
        assertNull(c.ad.value)
        assertFalse(c.loading)
        c.prefetch() // 다음 호출이 다시 시도한다
        assertTrue(c.fresh() != null)
    }

    @Test fun emptyResultTriesNextAndExtraAdsAreReleased() = runTest {
        val extra = FakeNativeHandle("extra")
        provider.results.addLast(emptyList<FakeNativeHandle>())
        provider.results.addLast(listOf(FakeNativeHandle("keep"), extra))
        val c = cache(chain = listOf("a", "b"))
        c.prefetch()
        assertEquals("keep", (c.fresh() as FakeNativeHandle).id)
        assertEquals(1, extra.destroyed)
    }

    @Test fun misconfiguredChainNeverRequests() = runTest {
        val c = cache(chain = emptyList())
        c.prefetch()
        assertTrue(provider.requestedIds.isEmpty())
    }

    @Test fun resetStopsAutoRefill() = runTest {
        val c = cache()
        c.prefetch()
        val first = provider.produced.single()
        c.reset()
        assertEquals(1, first.destroyed)
        available.value = false
        available.value = true
        assertEquals(1, provider.requestedIds.size)
        assertEquals(PrefetchedNativeAd.DEFAULT_TTL_MILLIS, 3_600_000L)
        assertEquals("exit", c.unit.name)
        assertEquals(1_000L, c.ttlMillis)
    }
}
