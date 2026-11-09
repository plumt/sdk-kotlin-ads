package net.plumt.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.plumt.ads.consent.ConsentFormResult
import net.plumt.ads.consent.ConsentManager
import net.plumt.ads.consent.ConsentSnapshot
import net.plumt.ads.consent.ConsentStatus
import net.plumt.ads.consent.DefaultConsentManager
import net.plumt.ads.network.NativeAdAssets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

/** 0.1.0 의 공개 값 타입(빌더·상수 클래스·일반 클래스의 equals)과 콜백 형태. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApiShapeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val job = Job()

    @After fun tearDown() = job.cancel()

    // ---- 상수 클래스 ----

    @Test fun constantClassesListEveryValueOnceWithName() {
        val all = listOf(
            AdFormat.values, SuppressReason.values, AdsErrorKind.values, AdErrorReason.values, AdsWarning.values,
            ConsentDebugGeography.values,
        )
        assertEquals(listOf(5, 4, 6, 12, 2, 4), all.map { it.size })
        all.forEach { values ->
            assertEquals(values.size, values.map { it.toString() }.toSet().size)
        }
        assertEquals("Banner", AdFormat.Banner.name)
        assertEquals("Banner", AdFormat.Banner.toString())
        assertEquals("Misconfigured", SuppressReason.Misconfigured.name)
        assertEquals("Load", AdsErrorKind.Load.name)
        assertEquals("NoFill", AdErrorReason.NoFill.name)
        assertEquals("TestDeviceIdsIgnored", AdsWarning.TestDeviceIdsIgnored.name)
        assertEquals("Eea", ConsentDebugGeography.Eea.name)
        assertEquals(TestAdIds.NATIVE, TestAdIds.forFormat(AdFormat.Native))
        AdFormat.values.forEach { assertTrue(TestAdIds.isTestId(TestAdIds.forFormat(it))) }
    }

    @Test fun sealedValuesPrintTheirNames() {
        assertEquals("Yes", AdFlag.Yes.toString())
        assertEquals("MA", AdContentRating.MA.toString())
        assertEquals("Teen", AdTarget.Teen.toString())
        assertEquals("Production", AdsMode.Production.toString())
        assertEquals("PerUnit", FrequencyScope.PerUnit.toString())
        assertEquals("Showing", FullScreenAdState.Showing.toString())
        assertEquals("Obtained", ConsentStatus.Obtained.toString())
        assertEquals("Completed", ConsentGatherResult.Completed.toString())
        assertEquals(AdsMode.Production, AdsMode.of(true))
        assertEquals(AdsMode.Development, AdsMode.of(false))
    }

    // ---- 일반 클래스 ----

    @Test fun audienceValueSemanticsAndWithers() {
        val a = AdAudience.forTarget(AdTarget.Teen)
        assertEquals(AdAudience(AdFlag.No, AdFlag.Unspecified, AdContentRating.T), a)
        assertEquals(a.hashCode(), AdAudience(AdFlag.No, AdFlag.Unspecified, AdContentRating.T).hashCode())
        assertEquals(AdContentRating.PG, a.withMaxContentRating(AdContentRating.PG).maxContentRating)
        assertEquals(AdFlag.Yes, a.withChildDirected(AdFlag.Yes).childDirected)
        assertEquals(AdFlag.No, a.withUnderAgeOfConsent(AdFlag.No).underAgeOfConsent)
        assertNotEquals(a, AdAudience.Default)
        assertNotEquals(a, "x")
        assertEquals(AdAudience(), AdAudience.Default)
        assertTrue(a.toString().contains("maxContentRating=T"))
    }

    @Test fun unitValueSemanticsAndOverloads() {
        val u = AdUnit.banner("b", "p")
        assertEquals(AdUnit("b", AdFormat.Banner, "p", TestAdIds.BANNER), u)
        assertEquals(u.hashCode(), AdUnit("b", AdFormat.Banner, "p").hashCode())
        assertNotEquals(u, AdUnit.banner("b", "p", "dev"))
        assertNotEquals(u, "b")
        assertTrue(u.toString().contains("name=b"))
        assertEquals("d", AdUnit.native("n", "p", "d").development)
        assertEquals("d", AdUnit.interstitial("n", "p", "d").development)
        assertEquals("d", AdUnit.rewarded("n", "p", "d").development)
        assertEquals("d", AdUnit.appOpen("n", "p", "d").development)
        assertEquals(TestAdIds.APP_OPEN, AdUnit.appOpen("n", "p").development)
    }

    @Test fun configBuilderRoundTripsAndValidates() {
        val store = AdFrequencyStore.inMemory()
        val network = FakeNetwork()
        val conn = AdConnectivity.AlwaysOnline
        val units = AdUnitRegistry(AdUnit.banner("b", ""))
        val listener = AdEventListener { }
        val c = adsConfig {
            mode(AdsMode.Production)
            audience(AdAudience.forTarget(AdTarget.Adult))
            testDeviceIds(listOf("h"))
            consentDebugGeography(ConsentDebugGeography.Eea)
            appOpenCooldownMillis(1)
            appOpenMinIntervalMillis(2)
            appOpenSkipFirstLaunches(3)
            enabledSource(AdsEnabledSource.Never)
            events(listener)
            network(network)
            frequencyStore(store)
            units(units)
            connectivity(conn)
        }
        assertEquals(c, c.toBuilder().build())
        assertEquals(c.hashCode(), c.toBuilder().build().hashCode())
        assertNotEquals(c, AdsConfig())
        assertNotEquals(c, "x")
        assertSame(network, c.network)
        assertSame(store, c.frequencyStore)
        assertSame(conn, c.connectivity)
        assertSame(listener, c.events)
        assertSame(AdsEnabledSource.Never, c.enabledSource)
        assertEquals(3, c.appOpenSkipFirstLaunches)
        assertTrue(c.toString().contains("FakeNetwork"))
        assertTrue(AdsConfig().toString().contains("network=default"))
        assertNull(AdsConfig().network)
        assertThrows(IllegalArgumentException::class.java) { adsConfig { testDeviceIds(listOf(" ")) } }
        assertThrows(IllegalArgumentException::class.java) { adsConfig { appOpenCooldownMillis(-1) } }
        assertThrows(IllegalArgumentException::class.java) { adsConfig { appOpenMinIntervalMillis(-1) } }
        assertThrows(IllegalArgumentException::class.java) { adsConfig { appOpenSkipFirstLaunches(-1) } }
    }

    @Test fun preloadAndOptionsValueSemantics() {
        val p = PreloadPolicy.builder().maxRetries(1).autoPreload(false).reloadAfterDismiss(false).build()
        assertEquals(p, p.toBuilder().build())
        assertEquals(p.hashCode(), p.toBuilder().build().hashCode())
        assertNotEquals(p, PreloadPolicy.Default)
        assertNotEquals(p, "x")
        assertTrue(p.toString().contains("maxRetries=1"))
        val o = FullScreenAdOptions.builder()
            .placement("p").policy(AdFrequencyPolicy.Unlimited).preload(p).fallbacks(listOf(AdUnit.interstitial("f", "")))
            .frequencyScope(FrequencyScope.PerUnit).build()
        assertEquals(o, o.toBuilder().build())
        assertEquals(o.hashCode(), o.toBuilder().build().hashCode())
        assertNotEquals(o, FullScreenAdOptions.Default)
        assertNotEquals(o, "x")
        assertTrue(o.toString().contains("fallbacks=[f]"))
        assertNull(FullScreenAdOptions.Default.placement)
        assertEquals(RetryStep.After(5), RetryStep.After(5))
        assertEquals(RetryStep.After(5).hashCode(), RetryStep.After(5).hashCode())
        assertNotEquals(RetryStep.After(5), RetryStep.Stop)
        assertEquals("After(millis=5)", RetryStep.After(5).toString())
    }

    @Test fun resultClassesCompareByContent() {
        val e = AdsException(AdsErrorKind.Show, 1, "m")
        assertEquals(ShowResult.Failed(e), ShowResult.Failed(e))
        assertEquals(ShowResult.Failed(e).hashCode(), ShowResult.Failed(e).hashCode())
        assertNotEquals(ShowResult.Failed(e), ShowResult.NotReady)
        assertTrue(ShowResult.Failed(e).toString().contains("m"))
        assertEquals(ShowResult.Suppressed(SuppressReason.Unavailable).hashCode(), SuppressReason.Unavailable.hashCode())
        assertEquals("Suppressed(reason=Unavailable)", ShowResult.Suppressed(SuppressReason.Unavailable).toString())
        assertNotEquals(ShowResult.Suppressed(SuppressReason.Unavailable), ShowResult.Started)
        assertEquals(ConsentGatherResult.Failed(e), ConsentGatherResult.Failed(e))
        assertEquals(ConsentGatherResult.Failed(e).hashCode(), e.hashCode())
        assertNotEquals(ConsentGatherResult.Failed(e), ConsentGatherResult.Completed)
        assertTrue(ConsentGatherResult.Failed(e).toString().contains("m"))
        assertNotEquals(ConsentFormResult.Failed(e), ConsentFormResult.Completed)
        val s = ConsentSnapshot(ConsentStatus.Obtained, true, false)
        assertEquals(s, ConsentSnapshot(ConsentStatus.Obtained, true, false))
        assertEquals(s.hashCode(), ConsentSnapshot(ConsentStatus.Obtained, true, false).hashCode())
        assertNotEquals(s, ConsentSnapshot.Initial)
        assertNotEquals(s, "x")
        assertTrue(s.toString().contains("Obtained"))
    }

    @Test fun eventsCompareByContent() {
        val f = AdFormat.Native
        val pairs = listOf(
            AdEvent.Requested(f, "p") to AdEvent.Requested(f, "q"),
            AdEvent.Loaded(f, "p") to AdEvent.Loaded(f, "q"),
            AdEvent.LoadFailed(f, "p", 1, "m", AdErrorReason.NoFill) to AdEvent.LoadFailed(f, "p", 1, "m", AdErrorReason.Network),
            AdEvent.Impression(f, "p") to AdEvent.Impression(f, "q"),
            AdEvent.Clicked(f, "p") to AdEvent.Clicked(f, "q"),
            AdEvent.Shown(f, "p") to AdEvent.Shown(f, "q"),
            AdEvent.ShowFailed(f, "p", 1, "m", AdErrorReason.Expired) to AdEvent.ShowFailed(f, "p", 2, "m", AdErrorReason.Expired),
            AdEvent.Dismissed(f, "p") to AdEvent.Dismissed(f, "q"),
            AdEvent.RewardEarned(f, "p", "coin", 1) to AdEvent.RewardEarned(f, "p", "coin", 2),
            AdEvent.Misconfigured(f, "p", 1, "m") to AdEvent.Misconfigured(f, "p", 1, "n"),
            AdEvent.Suppressed(f, "p", SuppressReason.Unavailable) to AdEvent.Suppressed(f, "p", SuppressReason.FrequencyCapped),
        )
        for ((a, b) in pairs) {
            val copy = rebuild(a)
            assertEquals(a, copy)
            assertEquals(a.hashCode(), copy.hashCode())
            assertNotEquals(a, b)
            assertFalse(a.equals("x"))
            assertTrue(a.toString().startsWith(a.javaClass.simpleName + "("))
        }
    }

    private fun rebuild(e: AdEvent): AdEvent = when (e) {
        is AdEvent.Requested -> AdEvent.Requested(e.format, e.placement)
        is AdEvent.Loaded -> AdEvent.Loaded(e.format, e.placement)
        is AdEvent.LoadFailed -> AdEvent.LoadFailed(e.format, e.placement, e.code, e.message, e.reason)
        is AdEvent.Impression -> AdEvent.Impression(e.format, e.placement)
        is AdEvent.Clicked -> AdEvent.Clicked(e.format, e.placement)
        is AdEvent.Shown -> AdEvent.Shown(e.format, e.placement)
        is AdEvent.ShowFailed -> AdEvent.ShowFailed(e.format, e.placement, e.code, e.message, e.reason)
        is AdEvent.Dismissed -> AdEvent.Dismissed(e.format, e.placement)
        is AdEvent.RewardEarned -> AdEvent.RewardEarned(e.format, e.placement, e.type, e.amount)
        is AdEvent.Misconfigured -> AdEvent.Misconfigured(e.format, e.placement, e.code, e.message)
        is AdEvent.Suppressed -> AdEvent.Suppressed(e.format, e.placement, e.reason)
    }

    @Test fun overloadsFillDefaults() {
        assertEquals(AdErrorReason.Unknown, AdsException(AdsErrorKind.Load, 0, "m", RuntimeException()).reason)
        assertEquals(AdErrorReason.Timeout, AdsException(AdsErrorKind.Load, AdsException.CODE_TIMEOUT, "m").reason)
        assertFalse(NativeAdAssets(null, null, null, null, null, null, null, null).hasVideo)
        assertTrue(NativeAdAssets(null, null, null, null, null, null, null, null, true).hasVideo)
        val v = View(context)
        assertSame(v, views(media = v).media)
        assertNull(views().headline)
        assertEquals(LaunchCountFrequencyPolicy.DEFAULT_KEY, LaunchCountFrequencyPolicy(0, AdFrequencyStore.inMemory()).key)
        assertTrue(MutableAdsEnabledSource().enabled.value)
        assertEquals(IntervalFrequencyPolicy.DEFAULT_KEY, IntervalFrequencyPolicy().key)
        AdFrequencyStore.sharedPreferences(context).putLong("k", 1)
        assertEquals(1L, AdFrequencyStore.sharedPreferences(context, AdFrequencyStore.DEFAULT_FILE_NAME).getLong("k"))
    }

    // ---- 콜백 형태 ----

    private fun TestScope.client(gateway: FakeGateway) = DefaultAdsClient(
        context, AdsConfig(), MutableAdsEnabledSource(), AdEventListener.None, FakeNetwork(), gateway, FakeClock(),
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
    )

    @Test fun gatherCallbackDeliversOnceAndCancelSuppresses() = runTest {
        val gateway = FakeGateway(ALLOW)
        val c = client(gateway)
        val results = mutableListOf<ConsentGatherResult>()
        val done = c.gatherConsent(activity) { results += it }
        assertFalse(done.isActive)
        assertEquals(listOf<ConsentGatherResult>(ConsentGatherResult.Completed), results)

        var finish: ((AdsException?) -> Unit)? = null
        gateway.onGather = { finish = it }
        val pending = c.gatherConsent(activity, 0) { results += it }
        assertTrue(pending.isActive)
        assertTrue(pending.toString().contains("true"))
        pending.cancel()
        finish!!(null)
        assertEquals(1, results.size)
        assertThrows(IllegalArgumentException::class.java) { c.gatherConsent(activity, -1) { } }
    }

    @Test fun firstResumeCancelBeforeResumeUnregisters() = runTest {
        val gateway = FakeGateway(ALLOW)
        val c = client(gateway)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val handle = c.gatherConsentOnFirstResume(app) { error("불리면 안 된다") }
        assertTrue(handle.isActive)
        handle.cancel()
        assertFalse(handle.isActive)
        Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(0, gateway.gatherCalls)
        val ok = c.gatherConsentOnFirstResume(app)
        Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(1, gateway.gatherCalls)
        assertFalse(ok.isActive)
    }

    @Test fun consentCallbacksOnClientScope() = runTest {
        val gateway = FakeGateway(ALLOW)
        val manager = DefaultConsentManager(gateway, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job))
        val got = mutableListOf<ConsentFormResult>()
        manager.gather(activity) { got += it }
        manager.showPrivacyOptions(activity) { got += it }
        assertEquals(listOf<ConsentFormResult>(ConsentFormResult.Completed, ConsentFormResult.Completed), got)
    }

    @Test fun interfaceDefaultCallbacksRunOnMainThread() {
        val manager = object : ConsentManager {
            override val snapshot: StateFlow<ConsentSnapshot> = MutableStateFlow(ConsentSnapshot.Initial)
            override suspend fun gather(activity: Activity): ConsentFormResult = ConsentFormResult.AlreadyInProgress
            override suspend fun showPrivacyOptions(activity: Activity): ConsentFormResult = ConsentFormResult.Completed
        }
        val got = mutableListOf<Pair<ConsentFormResult, Boolean>>()
        manager.gather(activity) { got += it to (Looper.myLooper() == Looper.getMainLooper()) }
        manager.showPrivacyOptions(activity) { got += it to (Looper.myLooper() == Looper.getMainLooper()) }
        val cancelled = manager.gather(activity) { got += it to false }
        cancelled.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(ConsentFormResult.AlreadyInProgress to true, ConsentFormResult.Completed to true),
            got.take(2),
        )
    }
}
