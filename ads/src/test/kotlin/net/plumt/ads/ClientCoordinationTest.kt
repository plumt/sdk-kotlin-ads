package net.plumt.ads

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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

/** 클라이언트 단위 공유 상태: 빈도 정책·전면형 겹침 조율·동의 수집 결과·초기화 재시도. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 33, 35])
class ClientCoordinationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val network = FakeNetwork()
    private val gateway = FakeGateway(ALLOW)
    private val events = RecordingEvents()
    private val clock = FakeClock(1_000_000)
    private val store = AdFrequencyStore.inMemory()
    private val job = Job()

    private fun TestScope.client(
        source: AdsEnabledSource = AdsEnabledSource.Always,
        config: AdsConfig = AdsConfig(),
        freq: AdFrequencyStore = store,
    ) = DefaultAdsClient(
        context, config, source, events, network, gateway, clock,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job), freq, clock,
    )

    @After fun tearDown() = job.cancel()

    private fun FakeProvider.lastCallbacks(): FullScreenAdCallbacks = produced.last().callbacks!!

    @Test fun frequencyCapSurvivesClientRecreationWithPersistentStore() = runTest {
        val prefs = AdFrequencyStore.sharedPreferences(context, "client-restart-test")
        val c1 = client(freq = prefs)
        val a = c1.fullScreenController(AdUnit("home", AdFormat.Interstitial, "id"), "home")
        clock.advance(IntervalFrequencyPolicy.DEFAULT_INITIAL_DELAY_MILLIS)
        assertEquals(ShowResult.Started, a.show(activity))
        network.interstitialProvider.lastCallbacks().onShown()
        network.interstitialProvider.lastCallbacks().onDismissed()
        a.destroy()

        // 프로세스 재시작
        val c2 = client(freq = AdFrequencyStore.sharedPreferences(context, "client-restart-test"))
        val b = c2.fullScreenController(AdUnit("home", AdFormat.Interstitial, "id"), "home")
        clock.advance(IntervalFrequencyPolicy.DEFAULT_INITIAL_DELAY_MILLIS)
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), b.show(activity))
        clock.advance(IntervalFrequencyPolicy.DEFAULT_MIN_INTERVAL_MILLIS)
        assertEquals(ShowResult.Started, b.show(activity))
        b.destroy()
    }

    @Test fun explicitPolicyOverridesDefault() = runTest {
        val c = client()
        val ctrl = c.fullScreenController(AdUnit("p", AdFormat.Interstitial, "id"), "p", AdFrequencyPolicy.Unlimited)
        assertEquals(ShowResult.Started, ctrl.show(activity)) // 시작 유예 없음
        ctrl.destroy()
    }

    @Test fun appOpenFirstLaunchIsSuppressed() = runTest {
        val c = client()
        val open = c.fullScreenController(AdUnit("open", AdFormat.AppOpen, "id"), "open")
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), open.show(activity))
        assertEquals(1L, store.getLong(DefaultAdsClient.KEY_APP_OPEN_LAUNCHES))
        open.destroy()
    }

    @Test fun appOpenWaitsCooldownAfterOtherFullScreenDismissed() = runTest {
        store.putLong(DefaultAdsClient.KEY_APP_OPEN_LAUNCHES, 5)
        val c = client(config = AdsConfig(appOpenCooldownMillis = 10_000))
        val reward = c.fullScreenController(AdUnit("reward", AdFormat.Rewarded, "id"), "reward")
        val open = c.fullScreenController(AdUnit("open", AdFormat.AppOpen, "id"), "open")
        assertEquals(ShowResult.Started, reward.show(activity))
        assertEquals(ShowResult.Suppressed(SuppressReason.AnotherAdShowing), open.show(activity))
        network.rewardedProvider.lastCallbacks().onDismissed()
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), open.show(activity))
        clock.advance(9_999)
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), open.show(activity))
        clock.advance(1)
        assertEquals(ShowResult.Started, open.show(activity))
        // 앱 오프닝이 닫혀도 앱 오프닝 대기는 생기지 않는다(앱 오프닝 자신은 빈도 정책이 맡는다)
        network.appOpenProvider.lastCallbacks().onDismissed()
        assertTrue(events.list.contains(AdEvent.Suppressed(AdFormat.AppOpen, "open", SuppressReason.AnotherAdShowing)))
        reward.destroy(); open.destroy()
    }

    @Test fun showingLockReleasedAfterShowFailureToo() = runTest {
        val c = client()
        val r1 = c.fullScreenController(AdUnit("a", AdFormat.Rewarded, "id"), "a")
        val r2 = c.fullScreenController(AdUnit("b", AdFormat.Rewarded, "id"), "b")
        assertEquals(ShowResult.Started, r1.show(activity))
        assertEquals(ShowResult.Suppressed(SuppressReason.AnotherAdShowing), r2.show(activity))
        network.rewardedProvider.produced.first().callbacks!!.onShowFailed(AdsException(AdsErrorKind.Show, 1, "x"))
        assertEquals(ShowResult.Started, r2.show(activity))
        r1.destroy(); r2.destroy()
    }

    @Test fun lockReleasedWhenControllerDestroyedWhileShowing() = runTest {
        val c = client()
        val r1 = c.fullScreenController(AdUnit("a", AdFormat.Rewarded, "id"), "a")
        assertEquals(ShowResult.Started, r1.show(activity))
        val cb = network.rewardedProvider.lastCallbacks()
        r1.destroy() // 화면 회전
        cb.onDismissed()
        val r2 = c.fullScreenController(AdUnit("a", AdFormat.Rewarded, "id"), "a")
        assertEquals(ShowResult.Started, r2.show(activity))
        r2.destroy()
    }

    @Test fun appOpenSkipLaunchesConfigurable() = runTest {
        val c = client(config = AdsConfig(appOpenSkipFirstLaunches = 0))
        val open = c.fullScreenController(AdUnit("open", AdFormat.AppOpen, "id"), "open")
        assertEquals(ShowResult.Started, open.show(activity)) // 첫 실행부터 보인다
        open.destroy()
    }

    @Test fun appOpenSkipMoreLaunchesConfigurable() = runTest {
        store.putLong(DefaultAdsClient.KEY_APP_OPEN_LAUNCHES, 1) // 이번이 두 번째 실행
        val c = client(config = AdsConfig(appOpenSkipFirstLaunches = 3))
        val open = c.fullScreenController(AdUnit("open", AdFormat.AppOpen, "id"), "open")
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), open.show(activity))
        open.destroy()
    }

    @Test fun appOpenMinIntervalConfigurable() = runTest {
        val c = client(config = AdsConfig(appOpenSkipFirstLaunches = 0, appOpenMinIntervalMillis = 5_000, appOpenCooldownMillis = 0))
        val open = c.fullScreenController(AdUnit("open", AdFormat.AppOpen, "id"), "open")
        assertEquals(ShowResult.Started, open.show(activity))
        network.appOpenProvider.lastCallbacks().onShown()
        network.appOpenProvider.lastCallbacks().onDismissed()
        clock.advance(4_999)
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), open.show(activity))
        clock.advance(1)
        assertEquals(ShowResult.Started, open.show(activity))
        open.destroy()
    }

    @Test fun appOpenDefaultIntervalIsTwoMinutes() = runTest {
        val c = client(config = AdsConfig(appOpenSkipFirstLaunches = 0))
        val open = c.fullScreenController(AdUnit("open", AdFormat.AppOpen, "id"), "open")
        assertEquals(ShowResult.Started, open.show(activity))
        network.appOpenProvider.lastCallbacks().onShown()
        network.appOpenProvider.lastCallbacks().onDismissed()
        clock.advance(119_999)
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), open.show(activity))
        clock.advance(1)
        assertEquals(ShowResult.Started, open.show(activity))
        open.destroy()
    }

    @Test fun appOpenConfigDefaultsAndValidation() {
        val d = AdsConfig()
        assertEquals(120_000L, d.appOpenMinIntervalMillis)
        assertEquals(1, d.appOpenSkipFirstLaunches)
        assertEquals(AdsConfig.DEFAULT_APP_OPEN_MIN_INTERVAL_MILLIS, d.appOpenMinIntervalMillis)
        assertEquals(AdsConfig.DEFAULT_APP_OPEN_SKIP_FIRST_LAUNCHES, d.appOpenSkipFirstLaunches)
        assertEquals(
            "appOpenMinIntervalMillis 는 0 이상이어야 합니다: -1",
            assertThrows(IllegalArgumentException::class.java) { AdsConfig(appOpenMinIntervalMillis = -1) }.message,
        )
        assertEquals(
            "appOpenSkipFirstLaunches 는 0 이상이어야 합니다: -2",
            assertThrows(IllegalArgumentException::class.java) { AdsConfig(appOpenSkipFirstLaunches = -2) }.message,
        )
    }

    @Test fun configRejectsNegativeCooldown() {
        assertEquals(
            "appOpenCooldownMillis 는 0 이상이어야 합니다: -1",
            assertThrows(IllegalArgumentException::class.java) { AdsConfig(appOpenCooldownMillis = -1) }.message,
        )
        assertEquals(60_000L, AdsConfig().appOpenCooldownMillis)
        assertEquals(AdsConfig.DEFAULT_APP_OPEN_COOLDOWN_MILLIS, AdsConfig().appOpenCooldownMillis)
    }

    // --- 동의 수집 결과 ---

    @Test fun gatherReportsCompleted() = runTest {
        assertEquals(ConsentGatherResult.Completed, client().gatherConsent(activity))
        assertEquals(1, gateway.gatherCalls)
    }

    @Test fun gatherReportsSkippedWhenDisabled() = runTest {
        val c = client(MutableAdsEnabledSource(false))
        assertEquals(ConsentGatherResult.SkippedAdsDisabled, c.gatherConsent(activity))
        assertEquals(0, gateway.gatherCalls)
    }

    @Test fun gatherReportsTimeoutWhenSwitchNeverEmits() = runTest {
        val c = client(AdsEnabledSource.from(emptyFlow()))
        val r = async { c.gatherConsent(activity, switchTimeoutMillis = 1_000) }
        advanceTimeBy(999)
        assertFalse(r.isCompleted)
        advanceTimeBy(2)
        assertEquals(ConsentGatherResult.SkippedSwitchTimeout, r.await())
        assertEquals(0, gateway.gatherCalls)
    }

    @Test fun gatherZeroTimeoutDoesNotWait() = runTest {
        val c = client(AdsEnabledSource.from(emptyFlow()))
        assertEquals(ConsentGatherResult.SkippedSwitchTimeout, c.gatherConsent(activity, 0))
    }

    @Test fun gatherRejectsNegativeTimeout() = runTest {
        val c = client()
        assertEquals(
            "switchTimeoutMillis 는 0 이상이어야 합니다: -1",
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { c.gatherConsent(activity, -1) }
            }.message,
        )
        assertEquals(5_000L, AdsClient.DEFAULT_SWITCH_TIMEOUT_MILLIS)
    }

    @Test fun gatherReportsAlreadyInProgress() = runTest {
        var finish: ((AdsException?) -> Unit)? = null
        gateway.onGather = { done -> finish = done }
        val c = client()
        val first = async { c.gatherConsent(activity) }
        runCurrent()
        assertEquals(ConsentGatherResult.AlreadyInProgress, c.gatherConsent(activity))
        finish!!(null)
        assertEquals(ConsentGatherResult.Completed, first.await())
        assertEquals(1, gateway.gatherCalls)
    }

    @Test fun switchErrorIsExposedAndGatherSkipped() = runTest {
        val cause = IllegalStateException("저장소")
        val c = client(AdsEnabledSource.from(flow<Boolean> { throw cause }))
        val err = c.enabledSourceError.value!!
        assertEquals(AdsErrorKind.EnabledSource, err.kind)
        assertEquals(AdsException.CODE_UNKNOWN, err.code)
        assertSame(cause, err.cause)
        assertEquals(ConsentGatherResult.SkippedAdsDisabled, c.gatherConsent(activity))
    }

    @Test fun switchErrorAfterValueTurnsAdsOff() = runTest {
        val c = client(AdsEnabledSource.from(flow { emit(true); throw java.io.IOException("끊김") }))
        assertFalse(c.adsEnabled.value)
        assertFalse(c.adsAvailable.value)
        assertTrue(c.enabledSourceError.value!!.cause is java.io.IOException)
    }

    @Test fun healthySwitchHasNoError() = runTest {
        assertNull(client().enabledSourceError.value)
    }

    // --- 초기화 재시도 ---

    @Test fun initializeIfConsentedRetriesAfterFailure() = runTest {
        network.initFailure = AdsException(AdsErrorKind.Initialization, -1, "x")
        val c = client()
        assertEquals(1, network.initCalls)
        network.initFailure = null
        c.initializeIfConsented()
        assertEquals(2, network.initCalls)
        assertNull(c.initializationError.value)
        assertTrue(c.adsAvailable.value)
        c.initializeIfConsented()
        assertEquals(2, network.initCalls) // 성공 뒤에는 다시 부르지 않는다
    }

    @Test fun initializeIfConsentedDoesNothingWithoutConsent() = runTest {
        gateway.snapshot = DENY
        val c = client()
        c.initializeIfConsented()
        assertEquals(0, network.initCalls)
    }

    @Test fun gatherWithoutConsentDoesNotInitialize() = runTest {
        gateway.snapshot = DENY
        val c = client()
        assertEquals(ConsentGatherResult.Completed, c.gatherConsent(activity))
        assertEquals(0, network.initCalls)
    }
}
