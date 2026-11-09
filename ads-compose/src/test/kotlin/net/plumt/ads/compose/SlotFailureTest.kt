package net.plumt.ads.compose

import android.view.ViewGroup
import androidx.compose.ui.unit.dp
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 슬롯 실패 처리·배너 대체/재시도 판단·풀 불러오기 루프·미리보기 판단. */
@OptIn(ExperimentalCoroutinesApi::class)
class SlotFailureTest {

    // ---- 슬롯 그리기 ----

    @Test fun adAlwaysWins() {
        for (state in listOf(AdSlotState.Idle, AdSlotState.Loading, AdSlotState.Loaded, AdSlotState.Failed(err(AdErrorReason.NoFill)))) {
            for (f in listOf(AdSlotFailure.Collapse, AdSlotFailure.KeepSpace)) {
                assertEquals(SlotDraw.Ad, slotDraw(true, state, f, hasPlaceholder = false))
                assertEquals(SlotDraw.Ad, slotDraw(true, state, f, hasPlaceholder = true))
            }
        }
    }

    @Test fun placeholderWhileLoadingOnly() {
        assertEquals(SlotDraw.Placeholder, slotDraw(false, AdSlotState.Loading, AdSlotFailure.Collapse, true))
        assertEquals(SlotDraw.Nothing, slotDraw(false, AdSlotState.Loading, AdSlotFailure.Collapse, false))
        assertEquals(SlotDraw.Nothing, slotDraw(false, AdSlotState.Idle, AdSlotFailure.KeepSpace, true))
        assertEquals(SlotDraw.Nothing, slotDraw(false, AdSlotState.Loaded, AdSlotFailure.KeepSpace, true)) // 슬롯 수보다 광고가 적음
    }

    @Test fun failureKeepsOrCollapses() {
        val failed = AdSlotState.Failed(err(AdErrorReason.Network))
        assertEquals(SlotDraw.Placeholder, slotDraw(false, failed, AdSlotFailure.KeepSpace, true))
        assertEquals(SlotDraw.Nothing, slotDraw(false, failed, AdSlotFailure.Collapse, true))
        assertEquals(SlotDraw.Nothing, slotDraw(false, failed, AdSlotFailure.KeepSpace, false))
    }

    @Test fun videoNeedsMediaOfPolicySize() {
        assertTrue(canDrawNative(hasVideo = false, mediaHeight = null))
        assertFalse(canDrawNative(hasVideo = true, mediaHeight = null))
        assertFalse(canDrawNative(hasVideo = true, mediaHeight = 119.dp))
        assertTrue(canDrawNative(hasVideo = true, mediaHeight = 120.dp))
        assertTrue(canDrawNative(hasVideo = true, mediaHeight = 180.dp))
    }

    // ---- 배너 ----

    @Test fun bannerHeightByFailureMode() {
        assertEquals(50, bannerHeightDp(50, reserveSpace = true, loaded = false, failed = true, failure = AdSlotFailure.KeepSpace))
        assertEquals(0, bannerHeightDp(50, reserveSpace = true, loaded = false, failed = true, failure = AdSlotFailure.Collapse))
        assertEquals(50, bannerHeightDp(50, reserveSpace = true, loaded = false, failed = false, failure = AdSlotFailure.Collapse))
        // 한 번 왔으면 실패해도 높이 유지(SDK 가 이전 광고를 보인다)
        assertEquals(50, bannerHeightDp(50, reserveSpace = false, loaded = true, failed = true, failure = AdSlotFailure.Collapse))
        assertEquals(0, bannerHeightDp(50, reserveSpace = false, loaded = false, failed = true, failure = AdSlotFailure.KeepSpace))
        assertEquals(0, bannerHeightDp(-5, reserveSpace = true, loaded = false))
    }

    @Test fun bannerFallsBackThenRetries() {
        assertEquals(BannerNext.Fallback(1), bannerNext(0, chainSize = 3, everLoaded = false))
        assertEquals(BannerNext.Fallback(2), bannerNext(1, chainSize = 3, everLoaded = false))
        assertSame(BannerNext.Retry, bannerNext(2, chainSize = 3, everLoaded = false))
        assertSame(BannerNext.Retry, bannerNext(0, chainSize = 1, everLoaded = false))
        assertSame(BannerNext.Nothing, bannerNext(0, chainSize = 3, everLoaded = true))
    }

    // ---- 미리보기 ----

    @Test fun previewConfigWinsOverInspection() {
        assertNull(previewVisibility(null, inspection = false))
        assertEquals(true, previewVisibility(null, inspection = true))
        assertEquals(false, previewVisibility(AdPreviewConfig(adsVisible = false), inspection = true))
        assertEquals(true, previewVisibility(AdPreviewConfig(adsVisible = true), inspection = false))
    }

    @Test fun testTagsKeepStableValues() {
        assertEquals("ad_banner", AdTestTags.BANNER)
        assertEquals("ad_native_row", AdTestTags.NATIVE_ROW)
        assertEquals("ad_native_list_item", AdTestTags.NATIVE_LIST_ITEM)
        assertEquals("ad_native_inline", AdTestTags.NATIVE_INLINE)
        assertEquals("ad_native_badge", AdTestTags.NATIVE_BADGE)
        assertEquals("ad_exit_dialog", AdTestTags.EXIT_DIALOG)
        assertEquals("ad_exit_slot", AdTestTags.EXIT_SLOT)
        assertEquals("ad_exit_cancel", AdTestTags.EXIT_CANCEL)
        assertEquals("ad_exit_confirm", AdTestTags.EXIT_CONFIRM)
        assertEquals("ad_mock_media", AdTestTags.MOCK_MEDIA)
        assertEquals("ad_mock_banner", AdTestTags.MOCK_BANNER)
        assertEquals("ad_mock_row_icon", AdTestTags.MOCK_ROW_ICON)
    }

    // ---- 풀 루프 ----

    private fun err(reason: AdErrorReason) = AdsException(AdsErrorKind.Load, 0, "x", null, reason)

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

    @Test fun poolFillsAndRefreshes() = runTest {
        val pool = NativeAdPool(2)
        val events = Recorder()
        var calls = 0
        val produced = mutableListOf<Handle>()
        val job = launch {
            pool.runLoop(listOf("a"), "p", events, PreloadPolicy.Default, 1_000, { MutableStateFlow(true) }, {}) { id ->
                calls++
                listOf(Handle(id), Handle(id)).also { produced += it }
            }
        }
        runCurrent()
        assertEquals(AdSlotState.Loaded, pool.state)
        assertFalse(pool.loading)
        assertSame(produced[0], pool[0])
        assertSame(produced[1], pool[1])
        assertNull(pool[2])
        advanceTimeBy(1_001)
        assertEquals(2, calls)
        assertEquals(1, produced[0].destroyed) // 갱신 때 이전 광고를 놓는다
        assertSame(produced[2], pool[0])
        job.cancel()
        pool.destroyAll()
        assertEquals(1, produced[2].destroyed)
        assertEquals(AdSlotState.Idle, pool.state)
        assertEquals(2, events.list.count { it is AdEvent.Loaded })
    }

    @Test fun poolFallsBackToNextUnit() = runTest {
        val pool = NativeAdPool(1)
        val asked = mutableListOf<String>()
        val job = launch {
            pool.runLoop(listOf("a", "b"), "p", AdEventListener.None, PreloadPolicy.Default, 10_000, { MutableStateFlow(true) }, {}) { id ->
                asked += id
                if (id == "a") throw err(AdErrorReason.NoFill)
                listOf(Handle(id))
            }
        }
        runCurrent()
        assertEquals(listOf("a", "b"), asked)
        assertEquals("b", (pool[0] as Handle).id)
        job.cancel()
    }

    @Test fun poolNoFillWaitsLongAndInvalidStops() = runTest {
        val pool = NativeAdPool(1)
        val failures = mutableListOf<AdsException>()
        val results = ArrayDeque<Any>(listOf(err(AdErrorReason.NoFill), err(AdErrorReason.InvalidRequest)))
        var calls = 0
        val job = launch {
            pool.runLoop(
                listOf("a"), "p", AdEventListener.None, PreloadPolicy.builder().noFillBackoffMillis(60_000).retryBackoffMillis(1).build(), 10_000,
                { MutableStateFlow(true) }, { failures += it },
            ) {
                calls++
                throw results.removeFirst() as Throwable
            }
        }
        runCurrent()
        assertEquals(AdErrorReason.NoFill, (pool.state as AdSlotState.Failed).error.reason)
        advanceTimeBy(59_999)
        assertEquals(1, calls)
        advanceTimeBy(2)
        assertEquals(2, calls)
        advanceTimeBy(10_000_000)
        assertEquals(2, calls) // 잘못된 요청은 다시 보내지 않는다
        assertEquals(listOf(AdErrorReason.NoFill, AdErrorReason.InvalidRequest), failures.map { it.reason })
        assertTrue(job.isCompleted)
    }

    @Test fun poolNetworkErrorWaitsForConnection() = runTest {
        val pool = NativeAdPool(1)
        val online = MutableStateFlow(false)
        var calls = 0
        val job = launch {
            pool.runLoop(listOf("a"), "p", AdEventListener.None, PreloadPolicy.Default, 10_000, { online }, {}) {
                calls++
                if (calls == 1) throw err(AdErrorReason.Network)
                listOf(Handle("a"))
            }
        }
        advanceTimeBy(1_000_000)
        assertEquals(1, calls)
        online.value = true
        runCurrent()
        assertEquals(2, calls)
        assertEquals(AdSlotState.Loaded, pool.state)
        job.cancel()
    }

    @Test fun poolWrapsForeignExceptionsAndEmptyResults() = runTest {
        val pool = NativeAdPool(1)
        val failures = mutableListOf<AdsException>()
        val job = launch {
            pool.runLoop(listOf("a", "b"), "p", AdEventListener.None, PreloadPolicy.builder().maxRetries(0).build(), 10_000, { MutableStateFlow(true) }, { failures += it }) { id ->
                if (id == "a") throw IllegalStateException("boom")
                emptyList()
            }
        }
        runCurrent()
        val e = failures.single()
        assertEquals(AdsErrorKind.Load, e.kind) // 마지막 실패(빈 결과)
        assertTrue(job.isCompleted)
    }

    @Test fun poolWithoutIdsDoesNothing() = runTest {
        val pool = NativeAdPool(1)
        pool.runLoop(emptyList(), "p", AdEventListener.None, PreloadPolicy.Default, 1, { MutableStateFlow(true) }, {}) { error("호출 안 됨") }
        assertEquals(AdSlotState.Idle, pool.state)
    }

    @Test fun poolLoadingFlagFollowsState() = runTest {
        val pool = NativeAdPool(1)
        val gate = CompletableDeferred<Unit>()
        val job = launch {
            pool.runLoop(listOf("a"), "p", AdEventListener.None, PreloadPolicy.Default, 10_000, { MutableStateFlow(true) }, {}) {
                gate.await(); listOf(Handle("a"))
            }
        }
        runCurrent()
        assertTrue(pool.loading)
        gate.complete(Unit)
        runCurrent()
        assertFalse(pool.loading)
        assertEquals(1, pool.size)
        job.cancel()
    }
}
