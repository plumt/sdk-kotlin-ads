package net.plumt.ads

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.plumt.ads.network.FullScreenAd
import net.plumt.ads.network.FullScreenAdCallbacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 동기 표시 실패·표시 중 destroy·재시도 대기 상한. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class ControllerShowEdgeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val provider = FakeProvider()
    private val events = RecordingEvents()
    private val clock = FakeClock()
    private val job = Job()

    private fun TestScope.controller(
        preload: PreloadPolicy = PreloadPolicy(),
        format: AdFormat = AdFormat.Rewarded,
    ) = FullScreenAdController(
        format, "p", listOf(AdUnit("p", format, "x") to "ca-app-pub-3940256099942544/5224354917"), context, provider, AdFrequencyPolicy.Unlimited, preload,
        MutableStateFlow(true), events, clock, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
    )

    @After fun tearDown() = job.cancel()

    private class Recording : FullScreenAdCallbacks {
        val calls = mutableListOf<String>()
        override fun onShown() { calls += "shown" }
        override fun onDismissed() { calls += "dismissed" }
        override fun onShowFailed(error: AdsException) { calls += "failed:${error.code}" }
        override fun onRewardEarned(type: String, amount: Int) { calls += "reward:$type:$amount" }
        override fun onClicked() { calls += "clicked" }
        override fun onImpression() { calls += "impression" }
    }

    @Test fun synchronousFailureReturnsFailedAndReloads() = runTest {
        val err = AdsException(AdsErrorKind.Show, 3, "이미 쓴 광고")
        provider.results.addLast(object : FullScreenAd {
            override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) = callbacks.onShowFailed(err)
            override fun destroy() = Unit
        })
        val c = controller()
        val cb = Recording()
        assertEquals(ShowResult.Failed(err), c.show(activity, cb))
        assertEquals(listOf("failed:3"), cb.calls)
        assertEquals(1, events.count<AdEvent.ShowFailed>())
        assertEquals(FullScreenAdState.Ready, c.state.value) // 다음 광고를 불러 뒀다
        assertEquals(2, provider.loads)
        c.destroy()
    }

    @Test fun asynchronousFailureStillReturnsStarted() = runTest {
        val c = controller(PreloadPolicy(reloadAfterDismiss = false))
        val cb = Recording()
        assertEquals(ShowResult.Started, c.show(activity, cb))
        provider.produced.last().callbacks!!.onShowFailed(AdsException(AdsErrorKind.Show, 1, "x"))
        assertEquals(listOf("failed:1"), cb.calls)
        assertEquals(FullScreenAdState.Idle, c.state.value)
        c.destroy()
    }

    @Test fun duplicateEndCallbacksAreIgnored() = runTest {
        val c = controller(PreloadPolicy(reloadAfterDismiss = false))
        val cb = Recording()
        c.show(activity, cb)
        val sdk = provider.produced.last().callbacks!!
        sdk.onDismissed()
        sdk.onDismissed()
        sdk.onShowFailed(AdsException(AdsErrorKind.Show, 1, "x"))
        assertEquals(listOf("dismissed"), cb.calls)
        assertEquals(1, events.count<AdEvent.Dismissed>())
        assertEquals(0, events.count<AdEvent.ShowFailed>())
        c.destroy()
    }

    @Test fun destroyWhileShowingStopsScreenCallbacksButKeepsEvents() = runTest {
        val c = controller()
        val cb = Recording()
        assertEquals(ShowResult.Started, c.show(activity, cb))
        val sdk = provider.produced.last().callbacks!!
        sdk.onShown()
        c.destroy() // 화면 재생성
        sdk.onImpression()
        sdk.onClicked()
        sdk.onRewardEarned("coin", 10)
        sdk.onDismissed()
        assertEquals(listOf("shown"), cb.calls)
        assertTrue(events.list.contains(AdEvent.RewardEarned(AdFormat.Rewarded, "p", "coin", 10)))
        assertEquals(1, events.count<AdEvent.Dismissed>())
        assertEquals(1, provider.loads) // destroy 뒤에는 다시 불러오지 않는다
    }

    @Test fun backoffSaturatesInsteadOfOverflowing() = runTest {
        repeat(10) { provider.enqueueError(AdsException(AdsErrorKind.Load, 0, "x")) }
        val c = controller(PreloadPolicy(maxRetries = 5, retryBackoffMillis = Long.MAX_VALUE / 2 + 1))
        assertEquals(1, provider.loads)
        advanceTimeBy(Long.MAX_VALUE / 2 + 2)
        assertEquals(2, provider.loads)
        advanceTimeBy(Long.MAX_VALUE / 4)
        assertEquals("포화된 대기(Long.MAX_VALUE) 중에는 다시 요청하지 않는다", 2, provider.loads)
        c.destroy()
    }
}
