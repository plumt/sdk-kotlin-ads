package net.plumt.ads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.ads.mobileads.NativeLoadGate
import net.plumt.ads.mobileads.loadException
import net.plumt.ads.mobileads.loadReason
import net.plumt.ads.mobileads.looksLikeNetworkFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 실기기에서 드러난 두 결함: 네이티브 요청이 끝나지 않음, 오프라인 실패가 네트워크로 분류되지 않음. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineAndNativeCompletionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = RecordingEvents()
    private val clock = FakeClock()
    private val online = MutableStateFlow(true)
    private val job = Job()

    @After fun tearDown() = job.cancel()

    private val offlineMessage = "Error while connecting to ad server: Unable to resolve host \"ads.example.invalid\": " +
        "No address associated with hostname"

    // ---- 네이티브 완료 판단 ----

    @Test fun failureEndsRequestEvenWhileLoaderSaysLoading() {
        // 로더가 아직 불러오는 중이라고 답하는 시점의 실패 콜백 — 예전엔 여기서 끝나지 않았다
        val gate = NativeLoadGate<String>(count = 1)
        val out = gate.onFailed(LoadAdError.ErrorCode.NO_FILL.value, "no fill")
        assertEquals(NativeLoadGate.Outcome.Failure(LoadAdError.ErrorCode.NO_FILL.value, "no fill"), out)
        assertTrue(gate.pending.isEmpty())
    }

    @Test fun partialAdsThenFailureSucceedsWithWhatArrived() {
        val gate = NativeLoadGate<String>(count = 3)
        assertSame(NativeLoadGate.Outcome.Pending, gate.onAd("a", stillLoading = true))
        assertEquals(listOf("a"), gate.pending)
        assertEquals(NativeLoadGate.Outcome.Success(listOf("a")), gate.onFailed(3, "x"))
        assertTrue(gate.pending.isEmpty())
    }

    @Test fun successEndsAtCountOrWhenLoaderStops() {
        val full = NativeLoadGate<String>(count = 2)
        assertSame(NativeLoadGate.Outcome.Pending, full.onAd("a", stillLoading = true))
        assertEquals(NativeLoadGate.Outcome.Success(listOf("a", "b")), full.onAd("b", stillLoading = true))

        val stopped = NativeLoadGate<String>(count = 5)
        assertEquals(NativeLoadGate.Outcome.Success(listOf("a")), stopped.onAd("a", stillLoading = false))
    }

    @Test fun loadingCompletedEndsWithWhatArrived() {
        // Next-Gen 의 여러 장 요청은 onAdLoadingCompleted 로 끝을 알린다 — 요청보다 적게 와도 받은 만큼으로 끝낸다
        val partial = NativeLoadGate<String>(count = 3)
        assertSame(NativeLoadGate.Outcome.Pending, partial.onAd("a", stillLoading = true))
        assertEquals(NativeLoadGate.Outcome.Success(listOf("a")), partial.onCompleted())
        assertSame(NativeLoadGate.Outcome.Pending, partial.onCompleted()) // 두 번 끝내지 않는다

        val empty = NativeLoadGate<String>(count = 2)
        assertEquals(NativeLoadGate.Outcome.Failure(null, null), empty.onCompleted())

        val failedFirst = NativeLoadGate<String>(count = 2)
        failedFirst.onFailed(3, "no fill")
        assertSame(NativeLoadGate.Outcome.Pending, failedFirst.onCompleted())
    }

    @Test fun callbacksAfterEndAreLateOrIgnored() {
        val gate = NativeLoadGate<String>(count = 1)
        gate.onAd("a", stillLoading = true)
        assertEquals(NativeLoadGate.Outcome.Late("b"), gate.onAd("b", stillLoading = false))
        assertSame(NativeLoadGate.Outcome.Pending, gate.onFailed(0, "x")) // 두 번 끝내지 않는다
    }

    @Test fun prefetchedNativeTimesOutAndFallsBack() = runTest {
        // 첫 단위의 공급자가 끝내 응답하지 않아도 제한 시간 뒤 대체 단위로 넘어간다
        val hang = CompletableDeferred<Unit>()
        val produced = mutableListOf<FakeNativeHandle>()
        val provider = net.plumt.ads.network.NativeAdProvider { _, id, _ ->
            if (id == "missing") hang.await()
            listOf(FakeNativeHandle(id)).also { produced += it }
        }
        val cache = PrefetchedNativeAd(
            AdUnit.native("exit", ""), "exit", 60_000, listOf("missing", "backup"), context, provider,
            MutableStateFlow(true), events, clock, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
            loadTimeoutMillis = 1_000,
        )
        cache.prefetch()
        assertTrue(cache.loading)
        advanceTimeBy(999)
        assertTrue(cache.loading)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(cache.loading)
        assertEquals("backup", (cache.fresh() as FakeNativeHandle).id)
        val failed = events.list.filterIsInstance<AdEvent.LoadFailed>().single()
        assertEquals(AdErrorReason.Timeout, failed.reason)
        assertEquals(AdsException.CODE_TIMEOUT, failed.code)
    }

    // ---- 오프라인 분류 ----

    @Test fun unknownHostMessageIsNetwork() {
        assertEquals(AdErrorReason.Network, loadReason(LoadAdError.ErrorCode.INTERNAL_ERROR.value, offlineMessage))
        assertEquals(AdErrorReason.Network, loadException(LoadAdError.ErrorCode.INTERNAL_ERROR.value, offlineMessage).reason)
        assertEquals(AdErrorReason.Network, loadReason(999, "java.net.UnknownHostException: x"))
        assertEquals(AdErrorReason.Internal, loadReason(LoadAdError.ErrorCode.INTERNAL_ERROR.value, "Internal error."))
        // 분류가 분명한 코드는 메시지로 바꾸지 않는다
        assertEquals(AdErrorReason.NoFill, loadReason(LoadAdError.ErrorCode.NO_FILL.value, offlineMessage))
        assertFalse(looksLikeNetworkFailure(null))
        assertFalse(looksLikeNetworkFailure(""))
    }

    @Test fun classifyOfflineOnlyTouchesAmbiguousLoadFailures() {
        val internal = AdsException(AdsErrorKind.Load, 0, "m", null, AdErrorReason.Internal)
        val re = internal.classifyOffline(online = false)
        assertEquals(AdErrorReason.Network, re.reason)
        assertEquals(0, re.code)
        assertEquals("[Load/Network/0] m", re.message)
        assertSame(internal, internal.classifyOffline(online = true))
        val noFill = AdsException(AdsErrorKind.Load, 3, "m", null, AdErrorReason.NoFill)
        assertSame(noFill, noFill.classifyOffline(online = false))
        val show = AdsException(AdsErrorKind.Show, 0, "m", null, AdErrorReason.Internal)
        assertSame(show, show.classifyOffline(online = false))
        assertSame(internal, internal.withReason(AdErrorReason.Internal))
    }

    @Test fun awaitHoldsRetryWhileOffline() = runTest {
        online.value = false
        val p = preload(retryBackoffMillis = 100)
        val after = async { p.awaitRetry(AdErrorReason.Internal, 0, online) }
        advanceTimeBy(10_000)
        assertFalse(after.isCompleted) // 대기가 끝나도 오프라인이면 다시 요청하지 않는다
        val network = async { p.awaitRetry(AdErrorReason.Network, 0, online) }
        runCurrent()
        assertFalse(network.isCompleted)
        online.value = true
        runCurrent()
        assertTrue(after.await())
        assertTrue(network.await())
        assertFalse(p.awaitRetry(AdErrorReason.InvalidRequest, 0, online))
        // 연결 대기를 끈 정책은 오프라인이어도 간격대로 다시 시도한다
        online.value = false
        val optOut = async { p.toBuilder().retryOnNetworkRestore(false).build().awaitRetry(AdErrorReason.Internal, 0, online) }
        advanceTimeBy(101)
        runCurrent()
        assertTrue(optOut.await())
    }

    @Test fun controllerTreatsOfflineInternalAsNetworkAndWaitsForConnection() = runTest {
        val provider = FakeProvider()
        online.value = false
        repeat(2) { provider.enqueueError(AdsException(AdsErrorKind.Load, 0, "Internal error.", null, AdErrorReason.Internal)) }
        val controller = FullScreenAdController(
            AdFormat.Interstitial, "p",
            listOf(AdUnit.interstitial("i", "") to "missing", AdUnit.interstitial("j", "") to "backup"),
            context, provider, AdFrequencyPolicy.Unlimited, preload(retryBackoffMillis = 5_000),
            MutableStateFlow(true), events, clock, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
            FullScreenCoordinator(clock, 0), AdConnectivity { online },
        )
        assertEquals(1, provider.loads) // 오프라인이면 대체 단위로 넘어가지 않는다
        assertEquals(AdErrorReason.Network, controller.lastError.value!!.reason)
        advanceTimeBy(600_000)
        assertEquals(1, provider.loads) // 오프라인 동안 5초 간격 재요청이 없다
        online.value = true
        runCurrent()
        assertTrue(provider.loads >= 2)
        assertEquals(AdErrorReason.Network, events.list.filterIsInstance<AdEvent.LoadFailed>().first().reason)
    }
}
