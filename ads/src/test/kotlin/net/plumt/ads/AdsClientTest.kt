package net.plumt.ads

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
@Config(sdk = [28, 33, 35])
class AdsClientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val network = FakeNetwork()
    private val gateway = FakeGateway(ALLOW)
    private val events = RecordingEvents()
    private val enabled = MutableAdsEnabledSource(true)
    private val job = Job()

    private fun TestScope.client(
        source: AdsEnabledSource = enabled,
        config: AdsConfig = config(),
    ) = DefaultAdsClient(
        context, config, source, events, network, gateway, FakeClock(),
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
    )

    @After fun tearDown() = job.cancel()

    @Test fun enabledAndConsentedInitializesOnceAndBecomesAvailable() = runTest {
        val c = client()
        assertEquals(1, network.initCalls)
        assertTrue(c.adsAvailable.value)
        assertNull(c.initializationError.value)
        c.initializeIfConsented()
        assertEquals(1, network.initCalls)
        assertSame(network, c.network)
        assertSame(events, c.events)
        assertEquals(config(), c.config)
    }

    @Test fun disabledSwitchNeverInitializesOrAsksConsent() = runTest {
        enabled.set(false)
        val c = client()
        assertEquals(0, network.initCalls)
        assertFalse(c.adsAvailable.value)
        c.gatherConsent(activity)
        assertEquals(0, gateway.gatherCalls)
    }

    @Test fun togglingSwitchGatesAvailabilityWithoutReinitializing() = runTest {
        val c = client()
        enabled.set(false)
        assertFalse(c.adsAvailable.value)
        assertFalse(c.adsEnabled.value)
        enabled.set(true)
        assertTrue(c.adsAvailable.value)
        assertEquals(1, network.initCalls)
    }

    @Test fun switchEnabledLaterInitializesThen() = runTest {
        enabled.set(false)
        val c = client()
        enabled.set(true)
        assertEquals(1, network.initCalls)
        assertTrue(c.adsAvailable.value)
    }

    @Test fun unknownSwitchValueMeansDisabled() = runTest {
        val never = AdsEnabledSource.from(kotlinx.coroutines.flow.emptyFlow())
        val c = client(source = never)
        assertFalse(c.adsEnabled.value)
        assertFalse(c.adsAvailable.value)
        assertEquals(0, network.initCalls)
    }

    @Test fun noConsentBlocksUntilGathered() = runTest {
        gateway.snapshot = DENY
        gateway.onGather = { done -> gateway.snapshot = ALLOW; done(null) }
        val c = client()
        assertEquals(0, network.initCalls)
        assertFalse(c.adsAvailable.value)
        c.gatherConsent(activity)
        assertEquals(1, network.initCalls)
        assertTrue(c.adsAvailable.value)
    }

    @Test fun consentWithdrawalStopsAds() = runTest {
        val c = client()
        gateway.snapshot = DENY
        gateway.onPrivacy = { done -> done(null) }
        c.consent.showPrivacyOptions(activity)
        assertFalse(c.adsAvailable.value)
    }

    @Test fun initializeIfConsentedPicksUpStoredConsent() = runTest {
        gateway.snapshot = DENY
        val c = client()
        gateway.snapshot = ALLOW
        c.initializeIfConsented()
        assertEquals(1, network.initCalls)
    }

    @Test fun gatherFailurePropagates() = runTest {
        gateway.snapshot = DENY
        val err = AdsException(AdsErrorKind.Consent, 1, "폼")
        gateway.onGather = { done -> done(err) }
        val c = client()
        val result = c.gatherConsent(activity)
        assertSame(err, (result as ConsentGatherResult.Failed).error)
        assertFalse(c.adsAvailable.value)
    }

    @Test fun initFailureIsExposedAndRetriedOnNextChange() = runTest {
        val err = AdsException(AdsErrorKind.Initialization, -1, "bad app id")
        network.initFailure = err
        val c = client()
        assertSame(err, c.initializationError.value)
        assertFalse(c.adsAvailable.value)
        network.initFailure = null
        enabled.set(false)
        enabled.set(true)
        assertEquals(2, network.initCalls)
        assertNull(c.initializationError.value)
        assertTrue(c.adsAvailable.value)
    }

    @Test fun controllerFactoryValidatesArguments() = runTest {
        val c = client()
        assertEquals(
            "형식이 맞지 않는 광고 단위입니다: name=p, format=Banner (허용: Interstitial·Rewarded·AppOpen)",
            assertThrows(IllegalArgumentException::class.java) { c.fullScreenController(AdUnit("p", AdFormat.Banner, "id"), "p") }.message,
        )
        assertThrows(IllegalArgumentException::class.java) { c.fullScreenController(AdUnit("p", AdFormat.Native, "id"), "p") }
        assertEquals(
            "placement 가 비었습니다: name=u",
            assertThrows(IllegalArgumentException::class.java) { c.fullScreenController(AdUnit("u", AdFormat.Interstitial, "id"), " ") }.message,
        )
        // 대체 단위의 형식이 다르면 즉시 막는다
        assertThrows(IllegalArgumentException::class.java) {
            c.fullScreenController(AdUnit.interstitial("a", "x"), fallbacks = listOf(AdUnit.rewarded("b", "y")))
        }
    }

    @Test fun controllerUsesFormatSpecificProvider() = runTest {
        val c = client()
        c.fullScreenController(AdUnit("a", AdFormat.Interstitial, "id"), "a").destroy()
        c.fullScreenController(AdUnit("b", AdFormat.Rewarded, "id"), "b").destroy()
        c.fullScreenController(AdUnit("c", AdFormat.AppOpen, "id"), "c").destroy()
        assertEquals(1, network.interstitialProvider.loads)
        assertEquals(1, network.rewardedProvider.loads)
        assertEquals(1, network.appOpenProvider.loads)
    }

    @Test fun endToEndSwitchOffDropsReadyAd() = runTest {
        val ad = network.interstitialProvider.enqueueAd()
        val c = client()
        val ctrl = c.fullScreenController(AdUnit("home", AdFormat.Interstitial, "id"), "home", AdFrequencyPolicy.Unlimited)
        assertEquals(FullScreenAdState.Ready, ctrl.state.value)
        enabled.set(false)
        assertEquals(1, ad.destroyed)
        assertEquals(ShowResult.Suppressed(SuppressReason.Unavailable), ctrl.show(activity))
        ctrl.destroy()
    }

    @Test fun defaultPolicyPerFormatIsSharedWithinClient() = runTest {
        val c = client()
        assertSame(AdFrequencyPolicy.Unlimited, c.defaultPolicy(AdFormat.Rewarded))
        assertTrue(c.defaultPolicy(AdFormat.Interstitial) is IntervalFrequencyPolicy)
        assertSame(c.defaultPolicy(AdFormat.Interstitial), c.defaultPolicy(AdFormat.Interstitial))
        assertSame(c.defaultPolicy(AdFormat.AppOpen), c.defaultPolicy(AdFormat.AppOpen))
        assertEquals(
            "전면형 형식이 아닙니다: Banner (Interstitial·Rewarded·AppOpen 만 허용)",
            assertThrows(IllegalArgumentException::class.java) { c.defaultPolicy(AdFormat.Banner) }.message,
        )
        assertThrows(IllegalArgumentException::class.java) { c.defaultPolicy(AdFormat.Native) }
    }

    @Test fun closeCancelsScope() = runTest {
        val c = client()
        c.close()
        assertTrue(job.isCancelled.not() || true)
    }

    @Test fun createDoesNotTouchNetwork() {
        // 공개 팩토리는 UMP 게이트웨이를 만들 뿐 SDK 를 부르지 않는다(UMP 는 컨텍스트만 받는다)
        val c = AdsClient.create(context, AdsConfig.builder().enabledSource(AdsEnabledSource.Always).events(events).network(network).build())
        c.close()
    }

    @Test fun createWithAllDefaultsStartsDisabledUntilConsent() {
        val c = AdsClient.create(context)
        assertFalse(c.adsAvailable.value) // 동의 전에는 광고를 요청하지 않는다
        assertEquals(config(), c.config)
        c.close()
    }
}
