package net.plumt.ads.compose

import android.view.ViewGroup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.ads.AdErrorReason
import net.plumt.ads.AdEvent
import net.plumt.ads.AdEventListener
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException
import net.plumt.ads.PreloadPolicy
import net.plumt.ads.network.NativeAdAssets
import net.plumt.ads.network.NativeAdHandle
import net.plumt.ads.network.NativeAdViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 네이티브 풀이 불러오는 중에 머물지 않는다(제한 시간·대체 단위), 오프라인이면 재시도를 멈춘다, 배너 오프라인 판단. */
@OptIn(ExperimentalCoroutinesApi::class)
class NativePoolCompletionTest {
    private class Handle(val id: String) : NativeAdHandle {
        var destroyed = 0
        override val assets = NativeAdAssets(null, null, null, null, null, null, null, null)
        override fun bind(container: ViewGroup, views: NativeAdViews) = true
        override fun destroy() {
            destroyed++
        }
    }

    private class Recorder : AdEventListener {
        val list = mutableListOf<AdEvent>()
        override fun onEvent(event: AdEvent) {
            list += event
        }
    }

    @Test fun hangingUnitTimesOutAndFallsBack() = runTest {
        // 공급자가 실패 콜백을 끝내 주지 않는 단위 — 예전엔 풀이 Loading 에 영원히 머물렀다
        val pool = NativeAdPool(1)
        val events = Recorder()
        val asked = mutableListOf<String>()
        val never = CompletableDeferred<List<NativeAdHandle>>()
        val job = launch {
            pool.runLoop(
                listOf("missing", "backup"), "p", events, PreloadPolicy(loadTimeoutMillis = 2_000), 3_600_000,
                { MutableStateFlow(true) }, {},
            ) { id ->
                asked += id
                if (id == "missing") never.await() else listOf(Handle(id))
            }
        }
        runCurrent()
        assertSame(AdSlotState.Loading, pool.state)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf("missing", "backup"), asked)
        assertSame(AdSlotState.Loaded, pool.state)
        assertEquals("backup", (pool[0] as Handle).id)
        val failed = events.list.filterIsInstance<AdEvent.LoadFailed>().single()
        assertEquals(AdErrorReason.Timeout, failed.reason)
        job.cancel()
    }

    @Test fun timeoutWithoutFallbackEndsInFailedNotLoading() = runTest {
        val pool = NativeAdPool(1)
        val failures = mutableListOf<AdsException>()
        val job = launch {
            pool.runLoop(
                listOf("missing"), "p", AdEventListener.None, PreloadPolicy(loadTimeoutMillis = 1_000, maxRetries = 0), 3_600_000,
                { MutableStateFlow(true) }, { failures += it },
            ) { CompletableDeferred<List<NativeAdHandle>>().await() }
        }
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(AdErrorReason.Timeout, (pool.state as AdSlotState.Failed).error.reason)
        assertEquals(AdsException.CODE_TIMEOUT, failures.single().code)
        assertTrue(job.isCompleted)
    }

    @Test fun lateArrivalAfterTimeoutIsReleased() = runTest {
        val late = Handle("late")
        val r = runCatching { loadWithTimeout(1_000, "p") { kotlinx.coroutines.delay(5_000); listOf(late) } }
        assertEquals(AdErrorReason.Timeout, (r.exceptionOrNull() as AdsException).reason)
        assertEquals(0, late.destroyed) // 취소돼 돌아오지 못한 광고는 공급자가 놓는다(돌아온 것만 여기서 놓는다)
        assertEquals(listOf(late), loadWithTimeout(0, "p") { listOf(late) })
    }

    @Test fun offlineInternalFailureIsNetworkAndWaitsForConnection() = runTest {
        val pool = NativeAdPool(1)
        val online = MutableStateFlow(false)
        val events = Recorder()
        val asked = mutableListOf<String>()
        val job = launch {
            pool.runLoop(
                listOf("a", "b"), "p", events, PreloadPolicy(retryBackoffMillis = 5_000), 3_600_000, { online }, {},
            ) { id ->
                asked += id
                if (!online.value) throw AdsException(AdsErrorKind.Load, 0, "Internal error.", reason = AdErrorReason.Internal)
                listOf(Handle(id))
            }
        }
        runCurrent()
        assertEquals(listOf("a"), asked) // 오프라인이면 대체 단위로 넘어가지 않는다
        assertEquals(AdErrorReason.Network, (pool.state as AdSlotState.Failed).error.reason)
        assertEquals(AdErrorReason.Network, events.list.filterIsInstance<AdEvent.LoadFailed>().single().reason)
        advanceTimeBy(600_000)
        assertEquals(1, asked.size) // 오프라인 동안 재요청 없음
        online.value = true
        runCurrent()
        assertEquals(listOf("a", "a"), asked)
        assertSame(AdSlotState.Loaded, pool.state)
        job.cancel()
    }

    @Test fun bannerSkipsFallbackOnNetworkAndRunsOnlyWhenResumedAndOnline() {
        assertSame(BannerNext.Retry, bannerNext(0, chainSize = 3, everLoaded = false, network = true))
        assertEquals(BannerNext.Fallback(1), bannerNext(0, chainSize = 3, everLoaded = false, network = false))
        assertTrue(bannerShouldRun(resumed = true, online = true))
        assertFalse(bannerShouldRun(resumed = true, online = false))
        assertFalse(bannerShouldRun(resumed = false, online = true))
    }
}
