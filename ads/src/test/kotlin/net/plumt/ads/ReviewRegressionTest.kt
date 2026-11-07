package net.plumt.ads

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.ads.network.FullScreenAd
import net.plumt.ads.network.FullScreenAdCallbacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 리뷰에서 나온 결함을 재현하는 회귀 테스트. 고치기 전 코드에서는 실패한다. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReviewRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val network = FakeNetwork()
    private val gateway = FakeGateway(ALLOW)
    private val events = RecordingEvents()
    private val clock = FakeClock(1_000_000)
    private val job = Job()

    private val store = AdFrequencyStore.inMemory()

    private fun TestScope.client(
        source: AdsEnabledSource = AdsEnabledSource.Always,
        config: AdsConfig = AdsConfig(),
    ) = DefaultAdsClient(
        context, config, source, events, network, gateway, clock,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job), store, clock,
    )

    @After fun tearDown() = job.cancel()

    // H1: 컨트롤러를 다시 만들어도(화면 재진입·회전) 빈도 제한이 이어져야 한다
    @Test fun frequencyCapSurvivesControllerRecreation() = runTest {
        val c = client()
        val first = c.fullScreenController(AdUnit("home", AdFormat.Interstitial, "id"), "home")
        clock.advance(IntervalFrequencyPolicy.DEFAULT_INITIAL_DELAY_MILLIS)
        assertEquals(ShowResult.Started, first.show(activity))
        network.interstitialProvider.produced.last().callbacks!!.onShown()
        network.interstitialProvider.produced.last().callbacks!!.onDismissed()
        first.destroy()

        val second = c.fullScreenController(AdUnit("home", AdFormat.Interstitial, "id"), "home")
        clock.advance(IntervalFrequencyPolicy.DEFAULT_INITIAL_DELAY_MILLIS)
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), second.show(activity))
        second.destroy()
    }

    // H2: 스위치 첫 값이 늦게 와도 동의 수집이 조용히 끝나지 않는다
    @Test fun gatherConsentWaitsForLateFirstSwitchValue() = runTest {
        gateway.snapshot = DENY
        gateway.onGather = { done -> gateway.snapshot = ALLOW; done(null) }
        val late = MutableSharedFlow<Boolean>()
        val c = client(AdsEnabledSource.from(late))
        val call = launch { c.gatherConsent(activity) }
        runCurrent() // 첫 값이 오기 전에 호출이 시작된다
        late.emit(true)
        runCurrent()
        call.join()
        assertEquals(1, gateway.gatherCalls)
        assertTrue(c.adsAvailable.value)
    }

    // M1: 초기화가 실패한 뒤 동의 수집을 다시 하면 초기화도 다시 시도한다(스위치가 Always 여도)
    @Test fun initializationRetriedOnGatherWithAlwaysSwitch() = runTest {
        network.initFailure = AdsException(AdsErrorKind.Initialization, -1, "일시 실패")
        val c = client()
        assertEquals(1, network.initCalls)
        network.initFailure = null
        c.gatherConsent(activity)
        assertEquals(2, network.initCalls)
        assertTrue(c.adsAvailable.value)
    }

    // M5-1: 앱 오프닝 기본 정책은 콜드 스타트 표시를 막지 않는다(첫 실행 제외 규칙은 따로)
    @Test fun appOpenDefaultAllowsColdStartAfterFirstLaunch() = runTest {
        // 지난 실행이 한 번 있었다 — 이번이 두 번째 실행
        store.putLong(DefaultAdsClient.KEY_APP_OPEN_LAUNCHES, 1)
        val c = client()
        val open = c.fullScreenController(AdUnit("open", AdFormat.AppOpen, "id"), "open")
        val r = open.show(activity)
        assertEquals(ShowResult.Started, r)
        open.destroy()
    }

    // M5-2: 다른 전면형 광고가 표시 중이면 앱 오프닝은 막힌다
    @Test fun anotherFullScreenShowingBlocksAppOpen() = runTest {
        val c = client()
        val inter = c.fullScreenController(AdUnit("reward", AdFormat.Rewarded, "id"), "reward")
        val open = c.fullScreenController(AdUnit("open", AdFormat.AppOpen, "id"), "open", AdFrequencyPolicy.Unlimited)
        assertEquals(ShowResult.Started, inter.show(activity))
        assertTrue(open.show(activity) is ShowResult.Suppressed)
        inter.destroy(); open.destroy()
    }

    // M10: 교체한 네트워크가 AdsException 이 아닌 예외를 던져도 크래시 대신 initializationError 로 알린다
    @Test fun foreignInitExceptionIsWrapped() = runTest {
        val boom = IllegalStateException("커스텀 실패")
        val bad = object : net.plumt.ads.network.AdNetwork by network {
            override suspend fun initialize(context: Context, config: AdsConfig) = throw boom
        }
        val c = DefaultAdsClient(
            context, AdsConfig(), AdsEnabledSource.Always, events, bad, gateway, clock,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
        )
        val err = c.initializationError.value
        assertEquals(AdsErrorKind.Initialization, err?.kind)
        assertTrue(err?.cause === boom)
        assertFalse(c.adsAvailable.value)
        assertTrue(job.isActive)
    }

    // M10: 스위치 Flow 가 예외를 내도 크래시하지 않고 꺼짐으로 본다
    @Test fun failingSwitchFlowFailsClosedWithoutCrash() = runTest {
        val src = AdsEnabledSource.from(flow { throw java.io.IOException("디스크") })
        val c = client(src)
        assertFalse(c.adsEnabled.value)
        assertFalse(c.adsAvailable.value)
        assertTrue(job.isActive)
    }

    // L1: show 안에서 곧바로 표시 실패가 오면 Started 를 돌려주지 않는다
    @Test fun synchronousShowFailureIsNotStarted() = runTest {
        val syncFail = object : FullScreenAd {
            override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) =
                callbacks.onShowFailed(AdsException(AdsErrorKind.Show, 1, "이미 쓴 광고"))
            override fun destroy() = Unit
        }
        network.rewardedProvider.results.addLast(syncFail)
        val c = client()
        val ctrl = c.fullScreenController(AdUnit("r", AdFormat.Rewarded, "id"), "r")
        assertNotEquals(ShowResult.Started, ctrl.show(activity))
        ctrl.destroy()
    }

    // L6: 재시도 대기가 넘쳐 음수가 되면 요청이 폭주한다
    @Test fun retryBackoffNeverOverflows() = runTest {
        val p = FakeProvider()
        repeat(60) { p.enqueueError(AdsException(AdsErrorKind.Load, 0, "x")) }
        val ctrl = FullScreenAdController(
            AdFormat.Interstitial, "p", listOf(AdUnit.interstitial("p", "") to "id"), context, p, AdFrequencyPolicy.Unlimited,
            PreloadPolicy(maxRetries = 50, retryBackoffMillis = 1L shl 61),
            kotlinx.coroutines.flow.MutableStateFlow(true), events, clock,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
        )
        advanceTimeBy((1L shl 61) + (1L shl 62) + 1)
        assertTrue("요청 수 ${p.loads}", p.loads <= 4)
        ctrl.destroy()
    }
}
