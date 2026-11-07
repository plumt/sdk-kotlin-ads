package net.plumt.ads

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.ads.consent.ConsentGateway
import net.plumt.ads.consent.ConsentSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 실제 UMP 규칙을 흉내 내는 게이트웨이: `canRequestAds()` 는 이 프로세스에서 `requestConsentInfoUpdate` 를
 * 부르기 전까지 늘 false(상태 Unknown)이고, 부른 직후부터 지난 실행에 저장된 동의를 돌려준다.
 * 갱신 콜백은 [finish] 를 부를 때까지 오지 않는다(네트워크 왕복).
 */
private class UmpLikeGateway(private val stored: ConsentSnapshot) : ConsentGateway {
    var requested = false
    var updateCalls = 0
    private var pending: ((AdsException?) -> Unit)? = null

    override fun current(): ConsentSnapshot = if (requested) stored else ConsentSnapshot.Initial

    override fun gather(activity: Activity, onUpdateRequested: () -> Unit, onDone: (AdsException?) -> Unit) {
        updateCalls++
        requested = true
        onUpdateRequested()
        pending = onDone
    }

    override fun showPrivacyOptions(activity: Activity, onDone: (AdsException?) -> Unit) = onDone(null)

    fun finish(error: AdsException? = null) {
        pending?.invoke(error)
        pending = null
    }
}

/** 실기기: 동의 저장 뒤 콜드 스타트에서 광고가 켜지지 않던 결함. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class StoredConsentStartupTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val network = FakeNetwork()
    private val job = Job()

    private fun TestScope.client(gateway: ConsentGateway) = DefaultAdsClient(
        context, AdsConfig(), AdsEnabledSource.Always, RecordingEvents(), network, gateway, FakeClock(0),
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job), AdFrequencyStore.inMemory(), FakeClock(0),
    )

    @After fun tearDown() = job.cancel()

    @Test fun initializeIfConsentedAloneCannotSeeStoredConsent() = runTest {
        // UMP 규칙: 갱신 요청 전에는 저장된 동의가 있어도 false — 이 단계에서 초기화하지 않는 것이 맞다
        val gw = UmpLikeGateway(ALLOW)
        val c = client(gw)
        c.initializeIfConsented()
        runCurrent()
        assertFalse(c.adsAvailable.value)
        assertEquals(0, network.initCalls)
    }

    @Test fun storedConsentInitializesRightAfterUpdateIsRequested() = runTest {
        val gw = UmpLikeGateway(ALLOW)
        val c = client(gw)
        c.initializeIfConsented()
        val call = launch { c.gatherConsent(activity) }
        runCurrent()
        // 갱신 응답(네트워크)이 아직 안 왔어도 지난 실행의 동의로 바로 초기화한다 — 공식 권장의 병행 초기화
        assertEquals(1, gw.updateCalls)
        assertEquals(1, network.initCalls)
        assertTrue(c.adsAvailable.value)
        assertTrue(call.isActive)
        gw.finish()
        runCurrent()
        assertFalse(call.isActive)
        assertEquals("갱신이 끝나도 두 번 초기화하지 않는다", 1, network.initCalls)
    }

    @Test fun storedConsentStillUsedWhenUpdateFails() = runTest {
        val gw = UmpLikeGateway(ALLOW)
        val c = client(gw)
        val call = launch { runCatching { c.gatherConsent(activity) } }
        runCurrent()
        gw.finish(AdsException(AdsErrorKind.Consent, 2, "네트워크"))
        runCurrent()
        assertFalse(call.isActive)
        assertTrue(c.adsAvailable.value)
        assertEquals(1, network.initCalls)
    }

    @Test fun noStoredConsentDoesNotInitializeEarly() = runTest {
        val gw = UmpLikeGateway(DENY)
        val c = client(gw)
        launch { c.gatherConsent(activity) }
        runCurrent()
        assertEquals(0, network.initCalls)
        assertFalse(c.adsAvailable.value)
        gw.finish()
    }
}
