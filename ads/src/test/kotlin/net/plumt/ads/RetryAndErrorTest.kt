package net.plumt.ads

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.ads.mobileads.MobileAdsNetwork
import net.plumt.ads.mobileads.MobileAdsSdk
import net.plumt.ads.mobileads.checkAppId
import net.plumt.ads.mobileads.loadException
import net.plumt.ads.mobileads.loadReason
import net.plumt.ads.mobileads.readManifestAppId
import net.plumt.ads.mobileads.showException
import net.plumt.ads.mobileads.showReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 실패 분류·분류별 재시도·시간 제한·표시 실패 분류·앱 ID 검사. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 30, 35])
class RetryAndErrorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()
    private val provider = FakeProvider()
    private val events = RecordingEvents()
    private val clock = FakeClock()
    private val online = MutableStateFlow(true)
    private val job = Job()

    @After fun tearDown() = job.cancel()

    private fun err(reason: AdErrorReason, code: Int = 0) = AdsException(AdsErrorKind.Load, code, "x", reason = reason)

    private fun TestScope.controller(
        preload: PreloadPolicy = PreloadPolicy(),
        chain: List<Pair<AdUnit, String>> = listOf(AdUnit.interstitial("i", "") to "id"),
    ) = FullScreenAdController(
        AdFormat.Interstitial, "p", chain, context, provider, AdFrequencyPolicy.Unlimited, preload,
        MutableStateFlow(true), events, clock, CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job),
        FullScreenCoordinator(clock, 0), AdConnectivity { online },
    )

    // ---- 분류 표 ----

    @Test fun retryableFlagsPerReason() {
        val stop = setOf(AdErrorReason.InvalidRequest, AdErrorReason.AppIdMissing, AdErrorReason.AppIdMismatch, AdErrorReason.Misconfigured)
        AdErrorReason.entries.forEach { assertEquals(it.name, it !in stop, it.retryable) }
    }

    @Test fun nextStepPerReason() {
        val p = PreloadPolicy(maxRetries = 3, retryBackoffMillis = 100, noFillBackoffMillis = 10_000)
        assertEquals(RetryStep.After(10_000), p.nextStep(AdErrorReason.NoFill, 0))
        assertEquals(RetryStep.After(40_000), p.nextStep(AdErrorReason.NoFill, 2))
        assertEquals(RetryStep.AwaitNetwork, p.nextStep(AdErrorReason.Network, 0))
        assertEquals(RetryStep.After(200), p.copy(retryOnNetworkRestore = false).nextStep(AdErrorReason.Network, 1))
        for (r in listOf(AdErrorReason.Internal, AdErrorReason.Timeout, AdErrorReason.Unknown, AdErrorReason.Expired)) {
            assertEquals(RetryStep.After(100), p.nextStep(r, 0))
        }
        for (r in listOf(AdErrorReason.InvalidRequest, AdErrorReason.AppIdMissing, AdErrorReason.AppIdMismatch, AdErrorReason.Misconfigured)) {
            assertEquals(RetryStep.Stop, p.nextStep(r, 0))
        }
        assertEquals(RetryStep.Stop, p.nextStep(AdErrorReason.NoFill, 3))
        assertEquals(RetryStep.Stop, PreloadPolicy(maxRetries = 0).nextStep(AdErrorReason.Network, 0))
    }

    @Test fun doubledSaturates() {
        assertEquals(5L, doubled(5, 0))
        assertEquals(40L, doubled(5, 3))
        assertEquals(Long.MAX_VALUE, doubled(1L shl 62, 2))
        assertEquals(Long.MAX_VALUE, doubled(Long.MAX_VALUE, 100))
        assertEquals(0L, doubled(0, 10))
    }

    @Test fun preloadPolicyRejectsNegativeNewFields() {
        assertEquals(
            "noFillBackoffMillis 는 0 이상이어야 합니다: -1",
            runCatching { PreloadPolicy(noFillBackoffMillis = -1) }.exceptionOrNull()!!.message,
        )
        assertEquals(
            "loadTimeoutMillis 는 0 이상이어야 합니다: -1",
            runCatching { PreloadPolicy(loadTimeoutMillis = -1) }.exceptionOrNull()!!.message,
        )
    }

    // ---- 컨트롤러 재시도 ----

    @Test fun noFillWaitsLongBackoff() = runTest {
        provider.enqueueError(err(AdErrorReason.NoFill, 3))
        val c = controller(PreloadPolicy(noFillBackoffMillis = 60_000, retryBackoffMillis = 1))
        assertEquals(1, provider.loads)
        assertEquals(AdErrorReason.NoFill, c.lastError.value!!.reason)
        advanceTimeBy(59_999)
        assertEquals(1, provider.loads)
        advanceTimeBy(2)
        assertEquals(2, provider.loads)
        assertEquals(FullScreenAdState.Ready, c.state.value)
        assertNull(c.lastError.value)
        val failed = events.list.filterIsInstance<AdEvent.LoadFailed>().single()
        assertEquals(AdErrorReason.NoFill, failed.reason)
    }

    @Test fun invalidRequestIsNotRetried() = runTest {
        provider.enqueueError(err(AdErrorReason.InvalidRequest, 1))
        val c = controller(PreloadPolicy(maxRetries = 5, retryBackoffMillis = 1))
        advanceTimeBy(1_000_000)
        assertEquals(1, provider.loads)
        assertEquals(FullScreenAdState.Idle, c.state.value)
        assertEquals(AdErrorReason.InvalidRequest, c.lastError.value!!.reason)
        assertEquals(ShowResult.NotReady, c.show(activity)) // 호출부가 다시 부르면 그때 새로 요청한다
        assertEquals(2, provider.loads)
    }

    @Test fun networkErrorRetriesWhenConnectionReturns() = runTest {
        online.value = false
        provider.enqueueError(err(AdErrorReason.Network, 2))
        val c = controller(PreloadPolicy(retryBackoffMillis = 1))
        advanceTimeBy(10_000_000)
        assertEquals(1, provider.loads) // 끊긴 동안은 시간이 지나도 다시 요청하지 않는다
        assertEquals(FullScreenAdState.Loading, c.state.value)
        online.value = true
        runCurrent()
        assertEquals(2, provider.loads)
        assertEquals(FullScreenAdState.Ready, c.state.value)
    }

    @Test fun networkErrorWithoutRestoreUsesBackoff() = runTest {
        online.value = false
        provider.enqueueError(err(AdErrorReason.Network, 2))
        controller(PreloadPolicy(retryBackoffMillis = 500, retryOnNetworkRestore = false))
        advanceTimeBy(501)
        assertEquals(2, provider.loads)
    }

    @Test fun timeoutIsClassifiedAndRetried() = runTest {
        provider.gate = CompletableDeferred()
        val c = controller(PreloadPolicy(loadTimeoutMillis = 1_000, retryBackoffMillis = 10, maxRetries = 1))
        advanceTimeBy(1_001)
        val e = c.lastError.value!!
        assertEquals(AdErrorReason.Timeout, e.reason)
        assertEquals(AdsException.CODE_TIMEOUT, e.code)
        provider.gate = null
        advanceTimeBy(11)
        assertEquals(FullScreenAdState.Ready, c.state.value)
    }

    @Test fun zeroTimeoutWaitsForever() = runTest {
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val c = controller(PreloadPolicy(loadTimeoutMillis = 0))
        advanceTimeBy(Long.MAX_VALUE / 4)
        assertEquals(FullScreenAdState.Loading, c.state.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(FullScreenAdState.Ready, c.state.value)
    }

    @Test fun chainCountsOneAttemptAcrossUnitsAndUsesLastReason() = runTest {
        provider.enqueueError(err(AdErrorReason.NoFill))
        provider.enqueueError(err(AdErrorReason.InvalidRequest))
        val c = controller(
            PreloadPolicy(maxRetries = 3),
            chain = listOf(AdUnit.interstitial("a", "") to "a", AdUnit.interstitial("b", "") to "b"),
        )
        advanceTimeBy(10_000_000)
        assertEquals(2, provider.loads) // 마지막 실패가 재시도 불가라 멈춘다
        assertEquals(FullScreenAdState.Idle, c.state.value)
        assertNull(c.loadedUnit)
    }

    @Test fun showFailureKeepsReasonInResultEventAndLastError() = runTest {
        provider.enqueueAd()
        val c = controller(PreloadPolicy(reloadAfterDismiss = false))
        val reused = AdsException(AdsErrorKind.Show, 1, "reused", reason = AdErrorReason.AlreadyUsed)
        provider.produced.first().let { ad ->
            val r = c.show(activity, object : net.plumt.ads.network.FullScreenAdCallbacks {})
            assertEquals(ShowResult.Started, r)
            ad.callbacks!!.onShowFailed(reused)
        }
        assertEquals(AdErrorReason.AlreadyUsed, c.lastError.value!!.reason)
        assertEquals(AdErrorReason.AlreadyUsed, events.list.filterIsInstance<AdEvent.ShowFailed>().single().reason)
    }

    // ---- SDK 코드 매핑 ----

    @Test fun loadCodesMapToReasons() {
        assertEquals(AdErrorReason.NoFill, loadReason(AdRequest.ERROR_CODE_NO_FILL))
        assertEquals(AdErrorReason.NoFill, loadReason(AdRequest.ERROR_CODE_MEDIATION_NO_FILL))
        assertEquals(AdErrorReason.Network, loadReason(AdRequest.ERROR_CODE_NETWORK_ERROR))
        assertEquals(AdErrorReason.InvalidRequest, loadReason(AdRequest.ERROR_CODE_INVALID_REQUEST))
        assertEquals(AdErrorReason.InvalidRequest, loadReason(AdRequest.ERROR_CODE_INVALID_AD_STRING))
        assertEquals(AdErrorReason.AppIdMissing, loadReason(AdRequest.ERROR_CODE_APP_ID_MISSING))
        assertEquals(AdErrorReason.AppIdMismatch, loadReason(AdRequest.ERROR_CODE_REQUEST_ID_MISMATCH))
        assertEquals(AdErrorReason.Internal, loadReason(AdRequest.ERROR_CODE_INTERNAL_ERROR))
        assertEquals(AdErrorReason.Unknown, loadReason(999))
        assertEquals(AdErrorReason.Unknown, loadReason(-1))
        val e = loadException(AdRequest.ERROR_CODE_NO_FILL, "m")
        assertEquals(AdsErrorKind.Load, e.kind)
        assertEquals(AdErrorReason.NoFill, e.reason)
    }

    @Test fun showCodesMapToReasons() {
        assertEquals(AdErrorReason.AlreadyUsed, showReason(FullScreenContentCallback.ERROR_CODE_AD_REUSED))
        assertEquals(AdErrorReason.Expired, showReason(FullScreenContentCallback.ERROR_CODE_NOT_READY))
        assertEquals(AdErrorReason.AppInBackground, showReason(FullScreenContentCallback.ERROR_CODE_APP_NOT_FOREGROUND))
        assertEquals(AdErrorReason.Internal, showReason(FullScreenContentCallback.ERROR_CODE_INTERNAL_ERROR))
        assertEquals(AdErrorReason.Internal, showReason(FullScreenContentCallback.ERROR_CODE_MEDIATION_SHOW_ERROR))
        assertEquals(AdErrorReason.Unknown, showReason(77))
        assertEquals(AdsErrorKind.Show, showException(1, "m").kind)
    }

    @Test fun defaultReasonFromLibraryCodes() {
        fun r(kind: AdsErrorKind, code: Int) = AdsException(kind, code, "m").reason
        assertEquals(AdErrorReason.Misconfigured, r(AdsErrorKind.Configuration, AdsException.CODE_PRODUCTION_ID_MISSING))
        assertEquals(AdErrorReason.Misconfigured, r(AdsErrorKind.Configuration, AdsException.CODE_TEST_ID_IN_PRODUCTION))
        assertEquals(AdErrorReason.AppIdMissing, r(AdsErrorKind.Configuration, AdsException.CODE_APP_ID_MISSING))
        assertEquals(AdErrorReason.AppIdMismatch, r(AdsErrorKind.Configuration, AdsException.CODE_TEST_APP_ID_IN_PRODUCTION))
        assertEquals(AdErrorReason.Timeout, r(AdsErrorKind.Load, AdsException.CODE_TIMEOUT))
        assertEquals(AdErrorReason.Expired, r(AdsErrorKind.Show, AdsException.CODE_EXPIRED))
        assertEquals(AdErrorReason.Misconfigured, r(AdsErrorKind.Configuration, 5))
        assertEquals(AdErrorReason.Unknown, r(AdsErrorKind.Load, 3))
    }

    // ---- 앱 ID ----

    @Test fun appIdCheckPerMode() {
        assertEquals(AdsException.CODE_APP_ID_MISSING, checkAppId(null, AdsMode.Development)!!.code)
        assertEquals(AdsException.CODE_APP_ID_MISSING, checkAppId("  ", AdsMode.Production)!!.code)
        assertEquals(AdErrorReason.AppIdMissing, checkAppId("", AdsMode.Production)!!.reason)
        val mismatch = checkAppId(TestAdIds.APP_ID, AdsMode.Production)!!
        assertEquals(AdsException.CODE_TEST_APP_ID_IN_PRODUCTION, mismatch.code)
        assertEquals(AdsErrorKind.Configuration, mismatch.kind)
        assertEquals(AdErrorReason.AppIdMismatch, mismatch.reason)
        assertNull(checkAppId(TestAdIds.APP_ID, AdsMode.Development))
        assertNull(checkAppId("ca-app-pub-sample~app", AdsMode.Development)) // 개발 모드의 운영 앱 ID 는 허용
        assertNull(checkAppId("ca-app-pub-sample~app", AdsMode.Production))
    }

    @Test fun manifestAppIdIsReadFromApplicationMetaData() {
        assertNull(readManifestAppId(context))
        val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).applicationInfo!!
        info.metaData = Bundle().apply { putString("com.google.android.gms.ads.APPLICATION_ID", TestAdIds.APP_ID) }
        assertEquals(TestAdIds.APP_ID, readManifestAppId(context))
    }

    @Test fun manifestAppIdUnknownPackageIsNull() {
        val other = object : android.content.ContextWrapper(context) {
            override fun getPackageName() = "no.such.package"
        }
        assertNull(readManifestAppId(other))
        assertTrue(PackageManager.GET_META_DATA != 0)
    }

    private class AppIdSdk(private val id: String?) : MobileAdsSdk {
        var initCalls = 0
        override fun applicationId(context: Context): String? = id
        override fun applyRequestConfiguration(config: AdsConfig) = Unit
        override fun initialize(context: Context, onComplete: () -> Unit) {
            initCalls++
            onComplete()
        }
    }

    @Test fun productionWithTestAppIdDoesNotInitializeSdk() = runBlocking {
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val sdk = AppIdSdk(TestAdIds.APP_ID)
        try {
            MobileAdsNetwork(sdk, io).initialize(context, AdsConfig(mode = AdsMode.Production))
            fail()
        } catch (e: AdsException) {
            assertEquals(AdsException.CODE_TEST_APP_ID_IN_PRODUCTION, e.code)
        }
        assertEquals(0, sdk.initCalls)
        val dev = AppIdSdk(TestAdIds.APP_ID)
        MobileAdsNetwork(dev, io).initialize(context, AdsConfig(mode = AdsMode.Development))
        assertEquals(1, dev.initCalls)
        io.close()
    }

    // ---- 연결 상태 ----

    @Test fun connectivityPorts() = runTest {
        assertTrue(AdConnectivity.AlwaysOnline.online().first())
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        // 인터넷 능력이 없으면 끊김
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, org.robolectric.shadows.ShadowNetworkCapabilities.newInstance())
        assertFalse(AdConnectivity.system(context).online().first())
        val internet = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        // 인터넷 능력만 있고 시스템이 연결을 확인(VALIDATED)하지 않았으면 끊김(캡티브 포털·전환 중)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, internet)
        assertFalse(AdConnectivity.system(context).online().first())
        shadowOf(internet).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, internet)
        assertTrue(AdConnectivity.system(context).online().first())
        assertFalse(AdConnectivity { kotlinx.coroutines.flow.flowOf(false) }.online().first())
    }
}
