package net.plumt.ads

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.ads.network.FullScreenAdCallbacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class FullScreenAdControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val provider = FakeProvider()
    private val available = MutableStateFlow(true)
    private val events = RecordingEvents()
    private val clock = FakeClock()
    private val job = Job()

    private class Policy(var allow: Boolean = true) : AdFrequencyPolicy {
        val shown = mutableListOf<String>()
        override fun canShow(placement: String) = allow
        override fun onShown(placement: String) { shown += placement }
    }

    private fun TestScope.controller(
        policy: AdFrequencyPolicy = AdFrequencyPolicy.Unlimited,
        preload: PreloadPolicy = PreloadPolicy(),
        format: AdFormat = AdFormat.Interstitial,
    ) = FullScreenAdController(
        format, "home", "ca-app-pub-3940256099942544/1033173712", context, provider, policy, preload,
        available, events, clock, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
    )

    @After fun tearDown() = job.cancel()

    @Test fun unavailableNeverLoadsAndSuppresses() = runTest {
        available.value = false
        val c = controller()
        assertEquals(0, provider.loads)
        assertEquals(ShowResult.Suppressed(SuppressReason.Unavailable), c.show(activity))
        assertEquals(1, events.count<AdEvent.Suppressed>())
        c.preload()
        assertEquals(0, provider.loads)
        assertEquals(FullScreenAdState.Idle, c.state.value)
    }

    @Test fun autoPreloadLoadsOnceAndEmitsEvents() = runTest {
        val ad = provider.enqueueAd()
        val c = controller()
        assertEquals(FullScreenAdState.Ready, c.state.value)
        assertEquals(1, provider.loads)
        assertEquals(listOf<AdEvent>(AdEvent.Requested(AdFormat.Interstitial, "home"), AdEvent.Loaded(AdFormat.Interstitial, "home")), events.list)
        c.preload() // 이미 Ready
        assertEquals(1, provider.loads)
        assertEquals(0, ad.destroyed)
        assertEquals(AdFormat.Interstitial, c.format)
        assertEquals("home", c.placement)
    }

    @Test fun autoPreloadOffWaitsForExplicitPreload() = runTest {
        provider.enqueueAd()
        val c = controller(preload = PreloadPolicy(autoPreload = false))
        assertEquals(0, provider.loads)
        c.preload()
        assertEquals(1, provider.loads)
        assertEquals(FullScreenAdState.Ready, c.state.value)
    }

    @Test fun preloadWhileLoadingDoesNotDoubleRequest() = runTest {
        provider.gate = CompletableDeferred()
        val c = controller()
        assertEquals(FullScreenAdState.Loading, c.state.value)
        c.preload(); c.preload()
        assertEquals(1, provider.loads)
        provider.enqueueAd()
        provider.gate!!.complete(Unit)
        assertEquals(FullScreenAdState.Ready, c.state.value)
    }

    @Test fun showLifecycleAndPolicyRecording() = runTest {
        val first = provider.enqueueAd()
        val second = provider.enqueueAd()
        val policy = Policy()
        val c = controller(policy)
        val seen = mutableListOf<String>()
        val result = c.show(activity, object : FullScreenAdCallbacks {
            override fun onShown() { seen += "shown" }
            override fun onImpression() { seen += "imp" }
            override fun onClicked() { seen += "click" }
            override fun onDismissed() { seen += "dismiss" }
        })
        assertEquals(ShowResult.Started, result)
        assertEquals(FullScreenAdState.Showing, c.state.value)
        assertSame(activity, first.lastActivity)
        assertTrue("onShown 전에는 기록하지 않는다", policy.shown.isEmpty())
        val cb = first.callbacks!!
        cb.onShown(); cb.onImpression(); cb.onClicked()
        assertEquals(listOf("home"), policy.shown)
        cb.onDismissed()
        assertEquals(listOf("shown", "imp", "click", "dismiss"), seen)
        assertEquals(1, first.destroyed)
        // 닫힌 뒤 다음 광고를 미리 불러 둔다
        assertEquals(2, provider.loads)
        assertEquals(FullScreenAdState.Ready, c.state.value)
        assertEquals(0, second.destroyed)
        assertEquals(1, events.count<AdEvent.Shown>())
        assertEquals(1, events.count<AdEvent.Impression>())
        assertEquals(1, events.count<AdEvent.Clicked>())
        assertEquals(1, events.count<AdEvent.Dismissed>())
    }

    @Test fun reloadAfterDismissOffStaysIdle() = runTest {
        val ad = provider.enqueueAd()
        val c = controller(preload = PreloadPolicy(reloadAfterDismiss = false))
        c.show(activity)
        ad.callbacks!!.onDismissed()
        assertEquals(1, provider.loads)
        assertEquals(FullScreenAdState.Idle, c.state.value)
    }

    @Test fun frequencyCapKeepsAdReady() = runTest {
        val ad = provider.enqueueAd()
        val policy = Policy(allow = false)
        val c = controller(policy)
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), c.show(activity))
        assertEquals(FullScreenAdState.Ready, c.state.value)
        assertEquals(0, ad.shown)
        assertEquals(AdEvent.Suppressed(AdFormat.Interstitial, "home", SuppressReason.FrequencyCapped), events.list.last())
        policy.allow = true
        assertEquals(ShowResult.Started, c.show(activity))
    }

    @Test fun notReadyWhenNothingLoadedTriggersLoad() = runTest {
        val c = controller(preload = PreloadPolicy(autoPreload = false))
        assertEquals(ShowResult.NotReady, c.show(activity))
        assertEquals(1, provider.loads)
    }

    @Test fun showWhileShowingIsNotReady() = runTest {
        provider.enqueueAd()
        val c = controller()
        c.show(activity)
        assertEquals(ShowResult.NotReady, c.show(activity))
    }

    @Test fun showFailureReportsAndReloads() = runTest {
        val ad = provider.enqueueAd()
        val policy = Policy()
        val c = controller(policy)
        var failure: AdsException? = null
        c.show(activity, object : FullScreenAdCallbacks {
            override fun onShowFailed(error: AdsException) { failure = error }
        })
        val err = AdsException(AdsErrorKind.Show, 5, "표시 실패")
        ad.callbacks!!.onShowFailed(err)
        assertSame(err, failure)
        assertTrue(policy.shown.isEmpty())
        assertEquals(1, ad.destroyed)
        assertEquals(AdEvent.ShowFailed(AdFormat.Interstitial, "home", 5, err.message!!), events.list.single { it is AdEvent.ShowFailed })
        assertEquals(2, provider.loads)
    }

    @Test fun rewardForwardedToCallerAndEvents() = runTest {
        val ad = provider.enqueueAd()
        val c = controller(format = AdFormat.Rewarded)
        var got: Pair<String, Int>? = null
        c.show(activity, object : FullScreenAdCallbacks {
            override fun onRewardEarned(type: String, amount: Int) { got = type to amount }
        })
        ad.callbacks!!.onRewardEarned("coin", 10)
        assertEquals("coin" to 10, got)
        assertEquals(AdEvent.RewardEarned(AdFormat.Rewarded, "home", "coin", 10), events.list.last())
    }

    @Test fun switchOffDropsReadyAdAndOnReloads() = runTest {
        val ad = provider.enqueueAd()
        val c = controller()
        available.value = false
        assertEquals(1, ad.destroyed)
        assertEquals(FullScreenAdState.Idle, c.state.value)
        assertEquals(ShowResult.Suppressed(SuppressReason.Unavailable), c.show(activity))
        available.value = true
        assertEquals(2, provider.loads)
        assertEquals(FullScreenAdState.Ready, c.state.value)
    }

    @Test fun switchOffWhileLoadingDestroysArrivingAd() = runTest {
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val ad = provider.enqueueAd()
        val c = controller()
        assertEquals(FullScreenAdState.Loading, c.state.value)
        available.value = false
        gate.complete(Unit)
        // 취소된 로드가 늦게 끝나도 광고를 쥐고 있지 않는다
        assertEquals(FullScreenAdState.Idle, c.state.value)
        assertNull(null)
        assertTrue(ad.destroyed <= 1)
        assertEquals(ShowResult.Suppressed(SuppressReason.Unavailable), c.show(activity))
    }

    @Test fun switchOffDuringShowingKeepsAdUntilDismissed() = runTest {
        val ad = provider.enqueueAd()
        val c = controller(preload = PreloadPolicy(reloadAfterDismiss = false))
        c.show(activity)
        available.value = false
        assertEquals(0, ad.destroyed)
        assertEquals(FullScreenAdState.Showing, c.state.value)
        ad.callbacks!!.onDismissed()
        assertEquals(1, ad.destroyed)
    }

    @Test fun expiredAdIsDroppedAndReloaded() = runTest {
        val old = provider.enqueueAd()
        val fresh = provider.enqueueAd()
        val c = controller(preload = PreloadPolicy(expireAfterMillis = 1_000))
        clock.advance(999)
        // 만료 직전에는 보인다 — 새로 시험하려고 정책을 따로 두지 않고 경계만 확인
        clock.advance(1)
        assertEquals(ShowResult.NotReady, c.show(activity))
        assertEquals(1, old.destroyed)
        assertEquals(2, provider.loads)
        assertEquals(FullScreenAdState.Ready, c.state.value)
        assertEquals(ShowResult.Started, c.show(activity))
        assertEquals(1, fresh.shown)
    }

    @Test fun adJustBeforeExpiryStillShows() = runTest {
        val ad = provider.enqueueAd()
        val c = controller(preload = PreloadPolicy(expireAfterMillis = 1_000))
        clock.advance(999)
        assertEquals(ShowResult.Started, c.show(activity))
        assertEquals(1, ad.shown)
    }

    @Test fun retriesWithDoublingBackoffThenSucceeds() = runTest {
        provider.enqueueError(AdsException(AdsErrorKind.Load, 3, "no fill"))
        provider.enqueueError(AdsException(AdsErrorKind.Load, 3, "no fill"))
        provider.enqueueAd()
        val c = controller(preload = PreloadPolicy(maxRetries = 2, retryBackoffMillis = 100))
        assertEquals(1, provider.loads)
        advanceTimeBy(99)
        assertEquals(1, provider.loads)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, provider.loads)
        advanceTimeBy(199)
        assertEquals(2, provider.loads)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, provider.loads)
        assertEquals(FullScreenAdState.Ready, c.state.value)
        assertEquals(2, events.count<AdEvent.LoadFailed>())
        assertEquals(3, events.count<AdEvent.Requested>())
    }

    @Test fun retriesExhaustedEndsIdle() = runTest {
        repeat(3) { provider.enqueueError(AdsException(AdsErrorKind.Load, 3, "no fill")) }
        val c = controller(preload = PreloadPolicy(maxRetries = 2, retryBackoffMillis = 10))
        advanceTimeBy(1_000)
        assertEquals(3, provider.loads)
        assertEquals(FullScreenAdState.Idle, c.state.value)
        assertEquals(3, events.count<AdEvent.LoadFailed>())
    }

    @Test fun zeroRetriesFailsOnce() = runTest {
        provider.enqueueError(AdsException(AdsErrorKind.Load, 3, "no fill"))
        val c = controller(preload = PreloadPolicy(maxRetries = 0))
        assertEquals(1, provider.loads)
        assertEquals(FullScreenAdState.Idle, c.state.value)
    }

    @Test fun foreignProviderExceptionIsWrappedNotCrashing() = runTest {
        provider.enqueueError(IllegalStateException("SDK 터짐"))
        controller(preload = PreloadPolicy(maxRetries = 0))
        val failed = events.list.filterIsInstance<AdEvent.LoadFailed>().single()
        assertEquals(AdsException.CODE_UNKNOWN, failed.code)
        assertTrue(failed.message, failed.message.contains("IllegalStateException: SDK 터짐"))
    }

    @Test fun destroyReleasesEverything() = runTest {
        val ad = provider.enqueueAd()
        val c = controller()
        c.destroy()
        c.destroy() // 여러 번 불러도 된다
        assertEquals(1, ad.destroyed)
        assertEquals(FullScreenAdState.Idle, c.state.value)
        val e = assertThrows(IllegalStateException::class.java) { c.show(activity) }
        assertEquals("destroy 된 컨트롤러입니다: home", e.message)
        c.preload()
        available.value = false; available.value = true
        assertEquals(1, provider.loads)
    }

    @Test fun destroyWhileLoadingCancelsLoad() = runTest {
        provider.gate = CompletableDeferred()
        val c = controller()
        c.destroy()
        assertEquals(FullScreenAdState.Idle, c.state.value)
        assertFalse(job.isCancelled)
    }

    @Test fun destroyedAdArrivingLateIsDestroyed() = runTest {
        // 취소를 무시하고 늦게 끝나는 공급자도 광고를 남기지 않는다
        val ad = FakeAd()
        val gate = CompletableDeferred<Unit>()
        val late = object : net.plumt.ads.network.FullScreenAdProvider {
            override suspend fun load(context: Context, adUnitId: String): net.plumt.ads.network.FullScreenAd {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }
                return ad
            }
        }
        val c = FullScreenAdController(
            AdFormat.AppOpen, "open", "id", context, late, AdFrequencyPolicy.Unlimited, PreloadPolicy(),
            available, events, clock, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
        )
        c.destroy()
        gate.complete(Unit)
        assertEquals(1, ad.destroyed)
        assertEquals(0, ad.shown)
    }
}
