package net.plumt.ads

import android.app.Activity
import android.app.Application
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 개발·운영 모드, 단위 등록부, 설정 오류 신호, 단위별 빈도, 대체 단위, 첫 화면 동의. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 33, 35])
class ClientModeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val network = FakeNetwork()
    private val gateway = FakeGateway(ALLOW)
    private val events = RecordingEvents()
    private val clock = FakeClock(1_000_000)
    private val job = Job()

    private fun TestScope.client(
        config: AdsConfig = config(),
        debuggable: Boolean = true,
        units: AdUnitRegistry = AdUnitRegistry.Empty,
        source: AdsEnabledSource = AdsEnabledSource.Always,
    ) = DefaultAdsClient(
        context, config, source, events, network, gateway, clock,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job), AdFrequencyStore.inMemory(), clock,
        debuggable, units,
    )

    @After fun tearDown() = job.cancel()

    @Test fun autoModeFollowsDebuggable() = runTest {
        assertEquals(AdsMode.Development, client(debuggable = true).mode)
        assertEquals(AdsMode.Production, client(debuggable = false).mode)
        assertEquals(AdsMode.Production, client(config(mode = AdsMode.Production), debuggable = true).mode)
        assertEquals(AdsMode.Development, client(config(mode = AdsMode.Development), debuggable = false).mode)
    }

    @Test fun developmentAppliesTestSettingsToNetwork() = runTest {
        val cfg = config(mode = AdsMode.Development, testDeviceIds = listOf("dev"), consentDebugGeography = ConsentDebugGeography.Eea)
        val c = client(cfg)
        assertEquals(listOf("dev"), network.lastConfig!!.testDeviceIds)
        assertEquals(ConsentDebugGeography.Eea, network.lastConfig!!.consentDebugGeography)
        assertEquals(AdsMode.Development, network.lastConfig!!.mode)
        assertTrue(c.warnings.value.isEmpty())
        assertSame(cfg, c.config)
    }

    @Test fun productionIgnoresTestSettingsAndWarns() = runTest {
        val cfg = config(mode = AdsMode.Production, testDeviceIds = listOf("dev"), consentDebugGeography = ConsentDebugGeography.Eea)
        val c = client(cfg)
        assertTrue(network.lastConfig!!.testDeviceIds.isEmpty())
        assertEquals(ConsentDebugGeography.Disabled, network.lastConfig!!.consentDebugGeography)
        assertEquals(AdsMode.Production, network.lastConfig!!.mode)
        assertEquals(setOf(AdsWarning.TestDeviceIdsIgnored, AdsWarning.ConsentDebugGeographyIgnored), c.warnings.value)
        assertSame(cfg, c.config) // 앱이 넘긴 값은 그대로 보인다
    }

    @Test fun productionChecksRegistryEagerly() = runTest {
        val reg = AdUnitRegistry(AdUnit.banner("ok", "real"), AdUnit.banner("empty", ""), AdUnit.rewarded("test", TestAdIds.REWARDED))
        val prod = client(config(mode = AdsMode.Production), units = reg)
        assertSame(reg, prod.units)
        assertEquals(
            listOf(AdsException.CODE_PRODUCTION_ID_MISSING, AdsException.CODE_TEST_ID_IN_PRODUCTION),
            prod.configurationErrors.value.map { it.code },
        )
        val dev = client(config(mode = AdsMode.Development), units = reg)
        assertTrue(dev.configurationErrors.value.isEmpty())
    }

    @Test fun adUnitIdPicksByModeAndReportsMisconfigurationOnce() = runTest {
        val unit = AdUnit.banner("home_banner", "real")
        assertEquals(TestAdIds.BANNER, client(config(mode = AdsMode.Development)).adUnitId(unit))
        assertEquals("real", client(config(mode = AdsMode.Production)).adUnitId(unit))

        val prod = client(config(mode = AdsMode.Production))
        val broken = AdUnit.banner("home_banner", "")
        assertNull(prod.adUnitId(broken, "home"))
        assertNull(prod.adUnitId(broken, "home")) // 크래시하지 않고 다시 불러도 같다
        assertEquals(1, prod.configurationErrors.value.size)
        val event = events.list.filterIsInstance<AdEvent.Misconfigured>().single()
        assertEquals(AdEvent.Misconfigured(AdFormat.Banner, "home", AdsException.CODE_PRODUCTION_ID_MISSING, event.message), event)
    }

    @Test fun misconfiguredControllerNeverLoadsAndSuppresses() = runTest {
        val c = client(config(mode = AdsMode.Production))
        val ctrl = c.fullScreenController(AdUnit.interstitial("level_end", TestAdIds.INTERSTITIAL), policy = AdFrequencyPolicy.Unlimited)
        assertTrue(ctrl.misconfigured)
        assertEquals("level_end", ctrl.placement)
        assertEquals(0, network.interstitialProvider.loads)
        ctrl.preload()
        assertEquals(0, network.interstitialProvider.loads)
        assertEquals(ShowResult.Suppressed(SuppressReason.Misconfigured), ctrl.show(activity))
        assertEquals(1, c.configurationErrors.value.size)
        ctrl.destroy()
    }

    @Test fun productionUsesProductionIdForLoad() = runTest {
        val ids = mutableListOf<String>()
        val c = DefaultAdsClient(
            context, config(mode = AdsMode.Production), AdsEnabledSource.Always, events,
            object : net.plumt.ads.network.AdNetwork by network {
                override val interstitial = net.plumt.ads.network.FullScreenAdProvider { _, id -> ids += id; FakeAd() }
            },
            gateway, clock, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
        )
        c.fullScreenController(AdUnit.interstitial("i", "real-i")).destroy()
        assertEquals(listOf("real-i"), ids)
    }

    @Test fun configurationInitFailureIsRecorded() = runTest {
        network.initFailure = AdsException(AdsErrorKind.Configuration, AdsException.CODE_TEST_APP_ID_IN_PRODUCTION, "앱 ID")
        val c = client()
        assertFalse(c.adsAvailable.value)
        assertEquals(AdErrorReason.AppIdMismatch, c.initializationError.value!!.reason)
        assertEquals(listOf(AdsException.CODE_TEST_APP_ID_IN_PRODUCTION), c.configurationErrors.value.map { it.code })
    }

    @Test fun nonConfigurationInitFailureIsNotAConfigurationError() = runTest {
        network.initFailure = IllegalStateException("x")
        val c = client()
        assertEquals(AdsErrorKind.Initialization, c.initializationError.value!!.kind)
        assertTrue(c.configurationErrors.value.isEmpty())
    }

    // ---- 여러 단위·빈도 범위 ----

    @Test fun perFormatSharesAndPerUnitSeparatesPolicies() = runTest {
        val c = client()
        val a = AdUnit.interstitial("a", "pa")
        val b = AdUnit.interstitial("b", "pb")
        assertSame(c.defaultPolicy(AdFormat.Interstitial), c.defaultPolicy(a, FrequencyScope.PerFormat))
        assertSame(c.defaultPolicy(a, FrequencyScope.PerFormat), c.defaultPolicy(b, FrequencyScope.PerFormat))
        val pa = c.defaultPolicy(a, FrequencyScope.PerUnit)
        assertSame(pa, c.defaultPolicy(a, FrequencyScope.PerUnit))
        assertNotSame(pa, c.defaultPolicy(b, FrequencyScope.PerUnit))
        assertNotSame(pa, c.defaultPolicy(AdFormat.Interstitial))
        assertTrue(c.defaultPolicy(AdUnit.appOpen("o", "p"), FrequencyScope.PerUnit) !== c.defaultPolicy(AdFormat.AppOpen))
        // 보상형은 제한이 없어 범위와 무관하다
        assertSame(AdFrequencyPolicy.Unlimited, c.defaultPolicy(AdUnit.rewarded("r", "p"), FrequencyScope.PerUnit))
        assertThrows(IllegalArgumentException::class.java) { c.defaultPolicy(AdUnit.banner("x", "p"), FrequencyScope.PerUnit) }
    }

    private fun showAndDismiss(ctrl: FullScreenAdController, provider: FakeProvider): ShowResult {
        val r = ctrl.show(activity)
        if (r == ShowResult.Started) {
            val ad = provider.produced.last { it.shown == 1 && it.destroyed == 0 }
            ad.callbacks!!.onShown()
            ad.callbacks!!.onDismissed()
        }
        return r
    }

    @Test fun perUnitFrequencyKeepsUnitsIndependent() = runTest {
        val c = client(config(appOpenSkipFirstLaunches = 0))
        val a = c.fullScreenController(AdUnit.appOpen("open_a", "p"), frequencyScope = FrequencyScope.PerUnit)
        val b = c.fullScreenController(AdUnit.appOpen("open_b", "p"), frequencyScope = FrequencyScope.PerUnit)
        assertEquals(ShowResult.Started, showAndDismiss(a, network.appOpenProvider))
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), a.show(activity))
        assertEquals(ShowResult.Started, showAndDismiss(b, network.appOpenProvider))
        a.destroy(); b.destroy()
    }

    @Test fun perFormatFrequencySharesAcrossUnits() = runTest {
        val c = client(config(appOpenSkipFirstLaunches = 0))
        val a = c.fullScreenController(AdUnit.appOpen("open_a", "p"))
        val b = c.fullScreenController(AdUnit.appOpen("open_b", "p"))
        assertEquals(ShowResult.Started, showAndDismiss(a, network.appOpenProvider))
        assertEquals(ShowResult.Suppressed(SuppressReason.FrequencyCapped), b.show(activity))
        a.destroy(); b.destroy()
    }

    // ---- 대체 단위 ----

    @Test fun fallbackUnitFillsWhenPrimaryFails() = runTest {
        network.rewardedProvider.enqueueError(AdsException(AdsErrorKind.Load, 3, "no fill", null, AdErrorReason.NoFill))
        val c = client()
        val primary = AdUnit.rewarded("reward_1", "p1")
        val second = AdUnit.rewarded("reward_2", "p2")
        val ctrl = c.fullScreenController(primary, fallbacks = listOf(second, primary))
        assertEquals(FullScreenAdState.Ready, ctrl.state.value)
        assertSame(second, ctrl.loadedUnit)
        assertEquals(2, network.rewardedProvider.loads) // 주 단위가 대체 목록에 다시 있어도 한 번만
        assertNull(ctrl.lastError.value)
        ctrl.destroy()
    }

    @Test fun misconfiguredPrimaryIsSkippedForValidFallback() = runTest {
        val c = client(config(mode = AdsMode.Production))
        val ctrl = c.fullScreenController(
            AdUnit.rewarded("reward_1", ""), fallbacks = listOf(AdUnit.rewarded("reward_2", "real")),
        )
        assertFalse(ctrl.misconfigured)
        assertEquals("reward_2", ctrl.loadedUnit!!.name)
        assertEquals(1, c.configurationErrors.value.size)
        ctrl.destroy()
    }

    // ---- 미리 받는 네이티브 ----

    @Test fun prefetchedNativeIsSharedPerUnitAndPlacement() = runTest {
        val c = client()
        val unit = AdUnit.native("exit", "p")
        val p = c.prefetchedNativeAd(unit)
        assertSame(p, c.prefetchedNativeAd(unit))
        assertNotSame(p, c.prefetchedNativeAd(unit, "other"))
        assertEquals("exit", p.placement)
        assertThrows(IllegalArgumentException::class.java) { c.prefetchedNativeAd(AdUnit.banner("b", "p")) }
        assertThrows(IllegalArgumentException::class.java) { c.prefetchedNativeAd(unit, " ") }
        assertThrows(IllegalArgumentException::class.java) { c.prefetchedNativeAd(unit, unit.name, 0, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { c.prefetchedNativeAd(unit, unit.name, PrefetchedNativeAd.DEFAULT_TTL_MILLIS, listOf(AdUnit.banner("b", "p"))) }
    }

    // ---- 첫 화면 동의 ----

    @Test fun gatherConsentOnFirstResumeRunsOnceWithFirstActivity() = runTest {
        val c = client()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val results = mutableListOf<ConsentGatherResult>()
        c.gatherConsentOnFirstResume(app) { results += it }
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        Robolectric.buildActivity(Activity::class.java).setup()
        controller.pause().resume()
        assertEquals(1, gateway.gatherCalls)
        assertEquals(listOf<ConsentGatherResult>(ConsentGatherResult.Completed), results)
    }

    @Test fun gatherConsentOnFirstResumeReportsFailure() = runTest {
        val err = AdsException(AdsErrorKind.Consent, 1, "폼")
        gateway.onGather = { done -> done(err) }
        val c = client()
        var result: ConsentGatherResult? = null
        c.gatherConsentOnFirstResume(ApplicationProvider.getApplicationContext()) { result = it }
        Robolectric.buildActivity(Activity::class.java).setup()
        assertSame(err, (result as ConsentGatherResult.Failed).error)
    }

    @Test fun gatherConsentOnFirstResumeSkipsFinishingActivity() = runTest {
        val c = client()
        c.gatherConsentOnFirstResume(ApplicationProvider.getApplicationContext())
        val finishing = Robolectric.buildActivity(Activity::class.java).create().start()
        finishing.get().finish()
        finishing.resume()
        assertEquals(0, gateway.gatherCalls)
        Robolectric.buildActivity(Activity::class.java).setup()
        assertEquals(1, gateway.gatherCalls)
    }

    @Test fun publicCreateResolvesModeAndKeepsUnits() {
        val reg = AdUnitRegistry(AdUnit.banner("b", ""))
        val c = AdsClient.create(context, AdsConfig.builder().mode(AdsMode.Production).network(network).units(reg).build())
        assertEquals(AdsMode.Production, c.mode)
        assertSame(reg, c.units)
        assertEquals(1, c.configurationErrors.value.size)
        c.close()
    }
}
