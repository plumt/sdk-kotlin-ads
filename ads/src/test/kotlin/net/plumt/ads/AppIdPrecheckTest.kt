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
import net.plumt.ads.consent.ConsentSnapshot
import net.plumt.ads.mobileads.MobileAdsNetwork
import net.plumt.ads.mobileads.MobileAdsSdk
import net.plumt.ads.network.AdNetwork
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 매니페스트 앱 ID 검사는 동의(UMP)보다 먼저 한다. 앱 ID 가 없으면 동의 수집이 먼저 실패해 초기화까지 가지 않으므로,
 * 새 설치(저장된 동의 없음)에서도 설정 오류가 한 번 드러나야 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 33, 35])
class AppIdPrecheckTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val events = RecordingEvents()
    private val clock = FakeClock(1_000_000)
    private val job = Job()

    private class AppIdSdk(var id: String?) : MobileAdsSdk {
        var initCalls = 0
        override fun applicationId(context: Context): String? = id
        override fun initialize(context: Context, appId: String, requestConfig: AdsConfig?, onComplete: () -> Unit) {
            initCalls++
            onComplete()
        }
    }

    private fun TestScope.client(
        network: AdNetwork,
        gateway: FakeGateway,
        config: AdsConfig = config(mode = AdsMode.Development),
    ) = DefaultAdsClient(
        context, config, AdsEnabledSource.Always, events, network, gateway, clock,
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job), AdFrequencyStore.inMemory(), clock, true,
    )

    private fun TestScope.network(sdk: MobileAdsSdk) = MobileAdsNetwork(sdk, UnconfinedTestDispatcher(testScheduler))

    /** 새 설치: 저장된 동의가 없고, UMP 는 앱 ID 가 없어 실패한다. */
    private fun freshInstallGateway(): FakeGateway = FakeGateway(ConsentSnapshot.Initial).apply {
        onGather = { done -> done(AdsException(AdsErrorKind.Consent, 3, "UMP 는 매니페스트 앱 ID 가 필요하다")) }
    }

    @After fun tearDown() = job.cancel()

    @Test fun missingAppIdIsReportedBeforeConsentOnFreshInstall() = runTest {
        val sdk = AppIdSdk(null)
        val c = client(network(sdk), freshInstallGateway())

        assertEquals(listOf(AdsException.CODE_APP_ID_MISSING), c.configurationErrors.value.map { it.code })
        assertEquals(AdErrorReason.AppIdMissing, c.configurationErrors.value.single().reason)
        assertEquals(AdsException.CODE_APP_ID_MISSING, c.initializationError.value?.code)
        assertFalse(c.adsAvailable.value)
        assertEquals(0, sdk.initCalls)
    }

    @Test fun blankAppIdIsReportedBeforeConsent() = runTest {
        val c = client(network(AppIdSdk("   ")), freshInstallGateway())
        assertEquals(listOf(AdsException.CODE_APP_ID_MISSING), c.configurationErrors.value.map { it.code })
    }

    @Test fun consentFailureAfterPrecheckKeepsOneErrorAndSuppressesAds() = runTest {
        val c = client(network(AppIdSdk(null)), freshInstallGateway())
        val result = c.gatherConsent(activity)
        assertTrue(result is ConsentGatherResult.Failed)

        val ctrl = c.fullScreenController(AdUnit.interstitial("home", ""), "home", AdFrequencyPolicy.Unlimited)
        assertEquals(ShowResult.Suppressed(SuppressReason.Unavailable), ctrl.show(activity))
        ctrl.destroy()
        assertEquals(1, c.configurationErrors.value.size)
    }

    @Test fun storedConsentDoesNotDuplicateTheError() = runTest {
        // 업데이트 설치: 동의가 저장돼 있어 초기화도 시도하고 같은 원인으로 실패한다 — 목록에는 한 번만 든다
        val sdk = AppIdSdk(null)
        val c = client(network(sdk), FakeGateway(ALLOW))
        assertEquals(listOf(AdsException.CODE_APP_ID_MISSING), c.configurationErrors.value.map { it.code })
        assertEquals(AdsException.CODE_APP_ID_MISSING, c.initializationError.value?.code)
        assertEquals(0, sdk.initCalls)
        assertFalse(c.adsAvailable.value)
    }

    @Test fun validAppIdReportsNothingAndInitializesAfterConsent() = runTest {
        val sdk = AppIdSdk(TestAdIds.APP_ID)
        val gateway = FakeGateway(ConsentSnapshot.Initial)
        gateway.onGather = { done -> gateway.snapshot = ALLOW; done(null) }
        val c = client(network(sdk), gateway)
        assertTrue(c.configurationErrors.value.isEmpty())
        assertNull(c.initializationError.value)
        assertEquals(0, sdk.initCalls) // 동의 전에는 SDK 를 부르지 않는다

        assertEquals(ConsentGatherResult.Completed, c.gatherConsent(activity))
        assertEquals(1, sdk.initCalls)
        assertTrue(c.adsAvailable.value)
        assertTrue(c.configurationErrors.value.isEmpty())
    }

    @Test fun productionTestAppIdIsReportedBeforeConsent() = runTest {
        val c = client(network(AppIdSdk(TestAdIds.APP_ID)), freshInstallGateway(), config(mode = AdsMode.Production))
        assertEquals(listOf(AdsException.CODE_TEST_APP_ID_IN_PRODUCTION), c.configurationErrors.value.map { it.code })
    }

    @Test fun replacedNetworkIsNotPrechecked() = runTest {
        // 앱이 교체한 네트워크는 앱 ID 를 쓰지 않을 수 있다 — 기본 구현일 때만 미리 본다
        val c = client(FakeNetwork(), freshInstallGateway())
        assertTrue(c.configurationErrors.value.isEmpty())
        assertNull(c.initializationError.value)
    }
}
