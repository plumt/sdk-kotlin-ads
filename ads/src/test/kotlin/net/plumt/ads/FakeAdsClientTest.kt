package net.plumt.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.plumt.ads.consent.ConsentFormResult
import net.plumt.ads.consent.FakeConsentManager
import net.plumt.ads.network.BannerListener
import net.plumt.ads.network.FullScreenAdCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 앱 단위 테스트용 가짜 클라이언트가 기본 구현과 같은 규칙으로 동작하는지. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FakeAdsClientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val interstitial = AdUnit.interstitial("level_end", "")
    private val rewarded = AdUnit.rewarded("hint", "")
    private val unlimited = FullScreenAdOptions.builder().policy(AdFrequencyPolicy.Unlimited).build()

    private class Callbacks : FullScreenAdCallbacks {
        val log = mutableListOf<String>()
        override fun onShown() { log += "shown" }
        override fun onImpression() { log += "impression" }
        override fun onClicked() { log += "clicked" }
        override fun onDismissed() { log += "dismissed" }
        override fun onShowFailed(error: AdsException) { log += "failed:${error.reason}" }
        override fun onRewardEarned(type: String, amount: Int) { log += "reward:$type:$amount" }
    }

    @Test fun defaultsAreAvailableDevelopmentAndRecordEvents() {
        val forwarded = mutableListOf<AdEvent>()
        val fake = FakeAdsClient(AdsConfig.builder().events { forwarded += it }.build())
        assertEquals(AdsMode.Development, fake.mode)
        assertTrue(fake.adsEnabled.value)
        assertTrue(fake.adsAvailable.value)
        assertNull(fake.initializationError.value)
        assertNull(fake.enabledSourceError.value)
        assertTrue(fake.warnings.value.isEmpty())
        assertTrue(fake.configurationErrors.value.isEmpty())
        assertSame(fake.fakeConsent, fake.consent)
        assertSame(AdUnitRegistry.Empty, fake.units)
        assertTrue(fake.toString().contains("Development"))
        fake.events.onEvent(AdEvent.Loaded(AdFormat.Banner, "b"))
        assertEquals(listOf<AdEvent>(AdEvent.Loaded(AdFormat.Banner, "b")), fake.recordedEvents)
        assertEquals(fake.recordedEvents, forwarded)
        fake.close()
    }

    @Test fun availabilityFollowsSwitchConsentAndInitialization() {
        val fake = FakeAdsClient()
        fake.setAdsEnabled(false)
        assertFalse(fake.adsAvailable.value)
        fake.setAdsEnabled(true)
        fake.fakeConsent.setSnapshot(FakeConsentManager.Denied)
        assertFalse(fake.adsAvailable.value)
        fake.fakeConsent.setSnapshot(FakeConsentManager.Allowed)
        val error = AdsException(AdsErrorKind.Initialization, -1, "x")
        fake.setInitializationError(error)
        assertSame(error, fake.initializationError.value)
        assertFalse(fake.adsAvailable.value)
        fake.setInitializationError(null)
        assertTrue(fake.adsAvailable.value)
    }

    @Test fun interstitialShowDismissFlow() {
        val fake = FakeAdsClient()
        val controller = fake.fullScreenController(interstitial, unlimited)
        assertEquals(FullScreenAdState.Ready, controller.state.value)
        assertEquals(1, fake.fullScreenLoadCalls)
        val callbacks = Callbacks()
        assertEquals(ShowResult.Started, controller.show(activity, callbacks))
        assertTrue(fake.isShowingFullScreenAd)
        assertEquals(FullScreenAdState.Showing, controller.state.value)
        // 전면형은 한 번에 하나만
        val other = fake.fullScreenController(rewarded, unlimited)
        assertEquals(ShowResult.Suppressed(SuppressReason.AnotherAdShowing), other.show(activity))
        assertTrue(fake.clickShowingAd())
        assertTrue(fake.dismissShowingAd())
        assertFalse(fake.isShowingFullScreenAd)
        assertEquals(listOf("shown", "impression", "clicked", "dismissed"), callbacks.log)
        // 닫히면 다음 광고를 미리 불러 둔다
        assertEquals(FullScreenAdState.Ready, controller.state.value)
        assertFalse(fake.dismissShowingAd())
        assertFalse(fake.earnReward("coin", 1))
        assertFalse(fake.clickShowingAd())
        assertTrue(fake.recordedEvents.any { it is AdEvent.Dismissed })
        controller.destroy()
        other.destroy()
    }

    @Test fun rewardGoesToCallbacksAndAppLifetimeEvents() {
        val fake = FakeAdsClient()
        val controller = fake.fullScreenController(rewarded)
        val callbacks = Callbacks()
        assertEquals(ShowResult.Started, controller.show(activity, callbacks))
        assertTrue(fake.earnReward("coin", 3))
        assertTrue(fake.dismissShowingAd())
        assertTrue("reward:coin:3" in callbacks.log)
        assertEquals(AdEvent.RewardEarned(AdFormat.Rewarded, "hint", "coin", 3), fake.recordedEvents.single { it is AdEvent.RewardEarned })
        controller.destroy()
    }

    @Test fun defaultInterstitialPolicyKeepsStartupDelay() {
        val clock = FakeClock(1_000_000)
        val fake = FakeAdsClient(AdsConfig(), clock)
        val controller = fake.fullScreenController(interstitial)
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), controller.show(activity))
        clock.advance(IntervalFrequencyPolicy.DEFAULT_INITIAL_DELAY_MILLIS)
        assertEquals(ShowResult.Started, controller.show(activity))
        assertTrue(fake.dismissShowingAd())
        assertSame(fake.defaultPolicy(AdFormat.Interstitial), fake.defaultPolicy(interstitial, FrequencyScope.PerFormat))
        controller.destroy()
    }

    @Test fun loadAndShowFailures() {
        val fake = FakeAdsClient()
        fake.loadFailure = AdsException(AdsErrorKind.Load, 3, "no fill", null, AdErrorReason.NoFill)
        val controller = fake.fullScreenController(
            interstitial,
            FullScreenAdOptions.builder().policy(AdFrequencyPolicy.Unlimited).preload(PreloadPolicy.builder().maxRetries(0).build()).build(),
        )
        assertEquals(ShowResult.NotReady, controller.show(activity))
        assertEquals(AdErrorReason.NoFill, controller.lastError.value?.reason)
        fake.loadFailure = null
        controller.preload()
        assertEquals(FullScreenAdState.Ready, controller.state.value)
        fake.showFailure = AdsException(AdsErrorKind.Show, 1, "reused", null, AdErrorReason.AlreadyUsed)
        val result = controller.show(activity)
        assertEquals(AdErrorReason.AlreadyUsed, (result as ShowResult.Failed).error.reason)
        assertFalse(fake.isShowingFullScreenAd)
        controller.destroy()
    }

    @Test fun switchOffDropsAndSuppresses() {
        val fake = FakeAdsClient()
        val controller = fake.fullScreenController(interstitial, unlimited)
        fake.setAdsEnabled(false)
        assertEquals(FullScreenAdState.Idle, controller.state.value)
        assertEquals(ShowResult.Suppressed(SuppressReason.Unavailable), controller.show(activity))
        controller.destroy()
    }

    @Test fun validatesLikeDefaultClient() {
        val fake = FakeAdsClient()
        assertThrows(IllegalArgumentException::class.java) { fake.fullScreenController(AdUnit.banner("b", "")) }
        assertThrows(IllegalArgumentException::class.java) {
            fake.fullScreenController(interstitial, FullScreenAdOptions.builder().placement(" ").build())
        }
        assertThrows(IllegalArgumentException::class.java) {
            fake.fullScreenController(interstitial, FullScreenAdOptions.builder().fallbacks(listOf(rewarded)).build())
        }
        assertThrows(IllegalArgumentException::class.java) { fake.prefetchedNativeAd(AdUnit.banner("b", "")) }
        assertThrows(IllegalArgumentException::class.java) { fake.prefetchedNativeAd(AdUnit.native("n", ""), " ") }
        assertThrows(IllegalArgumentException::class.java) {
            fake.prefetchedNativeAd(AdUnit.native("n", ""), "n", 0, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) { fake.defaultPolicy(AdFormat.Banner) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fake.gatherConsent(activity, -1) } }
        assertThrows(IllegalArgumentException::class.java) { fake.gatherConsent(activity, -1) { } }
    }

    @Test fun productionModeChecksUnitsAndHidesMisconfigured() {
        val bad = AdUnit.interstitial("bad", "")
        val fake = FakeAdsClient(AdsConfig.builder().mode(AdsMode.Production).units(AdUnitRegistry(bad)).build())
        assertEquals(AdsMode.Production, fake.mode)
        assertEquals(1, fake.configurationErrors.value.size)
        val controller = fake.fullScreenController(bad)
        assertTrue(controller.misconfigured)
        assertEquals(ShowResult.Suppressed(SuppressReason.Misconfigured), controller.show(activity))
        assertNull(fake.adUnitId(bad))
        assertEquals(1, fake.configurationErrors.value.size) // 같은 원인은 한 번
        assertEquals("ca-app-pub-1/2", fake.adUnitId(AdUnit.banner("ok", "ca-app-pub-1/2")))
        controller.destroy()
    }

    @Test fun prefetchedNativeFillsImmediately() {
        val fake = FakeAdsClient()
        val unit = AdUnit.native("exit", "")
        val cache = fake.prefetchedNativeAd(unit)
        assertSame(cache, fake.prefetchedNativeAd(unit, "exit"))
        cache.prefetch()
        val ad = cache.fresh()!!
        assertEquals("Test ad", ad.assets.headline)
        assertEquals("exit", cache.placement)
        assertTrue(cache.fresh()!!.bind(FrameLayout(context), views()))
        cache.consume(cache.fresh()!!)
        fake.loadFailure = AdsException(AdsErrorKind.Load, 3, "no fill")
        cache.reset()
        cache.prefetch()
        assertNull(cache.fresh())
        assertNotNull(cache.lastError.value)
    }

    @Test fun networkFakesServeComposeSlots() = runBlocking {
        val fake = FakeAdsClient()
        fake.network.initialize(context, fake.config)
        val log = mutableListOf<String>()
        val listener = object : BannerListener {
            override fun onLoaded() { log += "loaded" }
            override fun onImpression() { log += "impression" }
            override fun onLoadFailed(error: AdsException) { log += "failed" }
        }
        val banner = fake.network.banner.create(context, "id", 320, listener)
        assertEquals(50, banner.heightDp)
        assertNotNull(banner.view)
        banner.load()
        banner.pause()
        banner.resume()
        fake.loadFailure = AdsException(AdsErrorKind.Load, 2, "offline")
        banner.load()
        banner.destroy()
        assertEquals(listOf("loaded", "impression", "failed"), log)
        assertThrows(AdsException::class.java) { runBlocking { fake.network.native.load(context, "id", 2) } }
        fake.loadFailure = null
        assertEquals(2, fake.network.native.load(context, "id", 2).size)
        val ad = fake.network.interstitial.load(context, "id")
        ad.destroy()
        assertSame(fake.network.interstitial, fake.network.rewarded)
        assertSame(fake.network.interstitial, fake.network.appOpen)
        fake.setOnline(false)
        assertFalse(fake.connectivity.online().first())
    }

    @Test fun consentFlowFollowsSwitchAndFakeConsent() = runBlocking {
        val fake = FakeAdsClient()
        fake.fakeConsent.snapshotAfterGather = FakeConsentManager.Denied
        assertEquals(ConsentGatherResult.Completed, fake.gatherConsent(activity))
        assertFalse(fake.adsAvailable.value)
        val err = AdsException(AdsErrorKind.Consent, 2, "offline")
        fake.fakeConsent.gatherResult = ConsentFormResult.Failed(err)
        assertSame(err, (fake.gatherConsent(activity) as ConsentGatherResult.Failed).error)
        fake.fakeConsent.gatherResult = ConsentFormResult.AlreadyInProgress
        assertEquals(ConsentGatherResult.AlreadyInProgress, fake.gatherConsent(activity))
        fake.setAdsEnabled(false)
        assertEquals(ConsentGatherResult.SkippedAdsDisabled, fake.gatherConsent(activity))
        fake.gatherResult = ConsentGatherResult.SkippedSwitchTimeout
        val results = mutableListOf<ConsentGatherResult>()
        val handle = fake.gatherConsent(activity) { results += it }
        assertFalse(handle.isActive)
        handle.cancel()
        fake.gatherConsentOnFirstResume(ApplicationProvider.getApplicationContext<Application>()) { results += it }
        fake.gatherConsentOnFirstResume(ApplicationProvider.getApplicationContext<Application>())
        assertEquals(listOf<ConsentGatherResult>(ConsentGatherResult.SkippedSwitchTimeout, ConsentGatherResult.SkippedSwitchTimeout), results)
        assertEquals(7, fake.gatherConsentCalls)
        fake.initializeIfConsented()
        assertEquals(1, fake.initializeCalls)
    }

    @Test fun fakeConsentManagerFormsAndCallbacks() = runBlocking {
        val consent = FakeConsentManager(FakeConsentManager.Denied)
        assertFalse(consent.snapshot.value.canRequestAds)
        consent.snapshotAfterGather = FakeConsentManager.Allowed
        val got = mutableListOf<ConsentFormResult>()
        val handle = consent.gather(activity) { got += it }
        assertFalse(handle.isActive)
        assertTrue(handle.toString().contains("false"))
        assertTrue(consent.snapshot.value.canRequestAds)
        consent.snapshotAfterPrivacyOptions = FakeConsentManager.Denied
        assertEquals(ConsentFormResult.Completed, consent.showPrivacyOptions(activity))
        assertFalse(consent.snapshot.value.canRequestAds)
        val err = AdsException(AdsErrorKind.Consent, 7, "form")
        consent.privacyOptionsResult = ConsentFormResult.Failed(err)
        consent.snapshotAfterPrivacyOptions = FakeConsentManager.Allowed
        consent.showPrivacyOptions(activity) { got += it }
        assertFalse(consent.snapshot.value.canRequestAds) // 실패면 상태를 바꾸지 않는다
        consent.gatherResult = ConsentFormResult.AlreadyInProgress
        assertEquals(ConsentFormResult.AlreadyInProgress, consent.gather(activity))
        assertEquals(listOf(ConsentFormResult.Completed, ConsentFormResult.Failed(err)), got)
        assertEquals(2, consent.gatherCalls)
        assertEquals(2, consent.privacyOptionsCalls)
        assertTrue(consent.toString().contains("gatherCalls=2"))
        assertTrue(FakeConsentManager().snapshot.value.canRequestAds)
    }
}
