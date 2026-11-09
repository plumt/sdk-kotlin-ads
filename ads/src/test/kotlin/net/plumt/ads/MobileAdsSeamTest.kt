package net.plumt.ads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.plumt.ads.consent.toUmpGeography
import net.plumt.ads.mobileads.MobileAdsNetwork
import net.plumt.ads.mobileads.MobileAdsSdk
import net.plumt.ads.mobileads.canBindNative
import net.plumt.ads.mobileads.needsRequestConfiguration
import net.plumt.ads.mobileads.resumeOrRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 광고 SDK 직결 코드에서 SDK 없이 검증할 수 있는 부분: 초기화 스레드·순서, 취소 경합, 매핑. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileAdsSeamTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeSdk(var fail: Exception? = null, var complete: Boolean = true) : MobileAdsSdk {
        val calls = mutableListOf<String>()
        var appId: String? = TestAdIds.APP_ID
        override fun applicationId(context: Context): String? = appId
        var initThread: String? = null
        var onComplete: (() -> Unit)? = null
        var initAppId: String? = null

        override fun initialize(context: Context, appId: String, requestConfig: AdsConfig?, onComplete: () -> Unit) {
            // 요청 설정은 초기화 설정에 담겨 함께 넘어간다(Next-Gen 은 초기화 전 MobileAds 호출을 막는다)
            if (requestConfig != null) calls += "config"
            calls += "init"
            initAppId = appId
            initThread = Thread.currentThread().name
            fail?.let { throw it }
            this.onComplete = onComplete
            if (complete) onComplete()
        }
    }

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ads-io-test") }.asCoroutineDispatcher()

    @Test fun initializeRunsOffCallerThreadOnce() = runBlocking {
        val sdk = FakeSdk()
        val net = MobileAdsNetwork(sdk, io)
        net.initialize(context, config())
        assertTrue(sdk.initThread!!, sdk.initThread!!.startsWith("ads-io-test"))
        assertFalse(Thread.currentThread().name.startsWith("ads-io-test"))
        assertEquals(listOf("init"), sdk.calls) // 기본 설정은 요청 설정을 건드리지 않는다
        assertEquals(TestAdIds.APP_ID, sdk.initAppId) // 매니페스트 앱 ID 를 초기화 설정으로 넘긴다
        net.initialize(context, config())
        assertEquals(listOf("init"), sdk.calls)
    }

    @Test fun requestConfigurationAppliedBeforeInitialize() = runBlocking {
        val sdk = FakeSdk()
        MobileAdsNetwork(sdk, io).initialize(context, config(audience = AdAudience.forTarget(AdTarget.Teen)))
        assertEquals(listOf("config", "init"), sdk.calls)
    }

    @Test fun sdkExceptionIsWrappedWithCause() = runBlocking {
        val boom = IllegalStateException("앱 ID 없음")
        val net = MobileAdsNetwork(FakeSdk(fail = boom), io)
        try {
            net.initialize(context, config())
            fail()
        } catch (e: AdsException) {
            assertEquals(AdsErrorKind.Initialization, e.kind)
            assertEquals(AdsException.CODE_UNKNOWN, e.code)
            // 디버그 모드의 스택 복구가 스레드를 넘을 때 예외를 복제할 수 있어 타입·메시지로 확인한다
            assertTrue(e.cause is IllegalStateException)
            assertEquals(boom.message, e.cause!!.message)
        }
    }

    @Test fun cancellationPropagatesAndAllowsRetry() = runTest {
        val sdk = FakeSdk(complete = false)
        val net = MobileAdsNetwork(sdk, UnconfinedTestDispatcher(testScheduler))
        val call = async { net.initialize(context, config()) }
        runCurrent()
        call.cancel()
        runCurrent()
        assertTrue(call.isCancelled)
        sdk.complete = true
        net.initialize(context, config())
        assertEquals(listOf("init", "init"), sdk.calls)
    }

    @Test fun publicConstructorUsesRealSdk() {
        // SDK 를 부르지 않고 만들 수만 있는지 본다
        val net = MobileAdsNetwork()
        assertTrue(net.interstitial !== net.rewarded)
    }

    @Test fun needsRequestConfigurationOnlyWhenCustomized() {
        assertFalse(config().needsRequestConfiguration())
        assertTrue(config(testDeviceIds = listOf("x")).needsRequestConfiguration())
        assertTrue(config(audience = AdAudience.Default.withChildDirected(AdFlag.No)).needsRequestConfiguration())
        assertTrue(config(audience = AdAudience.Default.withUnderAgeOfConsent(AdFlag.Yes)).needsRequestConfiguration())
        assertTrue(config(audience = AdAudience.Default.withMaxContentRating(AdContentRating.PG)).needsRequestConfiguration())
    }

    // --- 취소 경합 (L5) ---

    @Test fun resumeAfterCancellationReleasesValue() = runTest {
        var released: String? = null
        var cont: kotlinx.coroutines.CancellableContinuation<String>? = null
        val job = launch { suspendCancellableCoroutine<String> { cont = it } }
        runCurrent()
        job.cancel()
        runCurrent()
        cont!!.resumeOrRelease("ad") { released = it }
        assertEquals("ad", released)
    }

    @Test fun promptCancellationAfterResumeReleasesValue() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var released: String? = null
        var got: String? = null
        var cont: kotlinx.coroutines.CancellableContinuation<String>? = null
        val job = launch(dispatcher) { got = suspendCancellableCoroutine { cont = it } }
        runCurrent()
        cont!!.resumeOrRelease("ad") { released = it } // 재개는 디스패치 대기
        job.cancel() // 디스패치 전에 취소 — 값이 버려진다
        runCurrent()
        assertNull(got)
        assertEquals("ad", released)
    }

    @Test fun normalResumeDeliversWithoutRelease() = runTest {
        var released = false
        val got = suspendCancellableCoroutine<String> { it.resumeOrRelease("ad") { released = true } }
        assertEquals("ad", got)
        assertFalse(released)
    }

    // --- 매핑 ---

    @Test fun debugGeographyUsesCurrentUmpConstants() {
        val g = com.google.android.ump.ConsentDebugSettings.DebugGeography::class.java
        assertNull(ConsentDebugGeography.Disabled.toUmpGeography())
        assertEquals(g.getField("DEBUG_GEOGRAPHY_EEA").get(null), ConsentDebugGeography.Eea.toUmpGeography())
        assertEquals(g.getField("DEBUG_GEOGRAPHY_OTHER").get(null), ConsentDebugGeography.NotEea.toUmpGeography())
        assertEquals(
            g.getField("DEBUG_GEOGRAPHY_REGULATED_US_STATE").get(null),
            ConsentDebugGeography.RegulatedUsState.toUmpGeography(),
        )
    }

    @Test fun sdkCallbacksAreDeliveredOnMainThread() {
        // Next-Gen SDK 는 콜백을 백그라운드 스레드에서 부른다 — 메인으로 옮기고, 이미 메인이면 곧바로 부른다
        var direct = false
        net.plumt.ads.mobileads.onMainThread { direct = true }
        assertTrue(direct)
        var thread: Thread? = null
        val worker = Thread { net.plumt.ads.mobileads.onMainThread { thread = Thread.currentThread() } }
        worker.start()
        worker.join()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(android.os.Looper.getMainLooper().thread, thread)
    }

    @Test fun videoNativeNeedsMediaView() {
        assertFalse(canBindNative(hasVideo = true, hasMediaView = false))
        assertTrue(canBindNative(hasVideo = true, hasMediaView = true))
        assertTrue(canBindNative(hasVideo = false, hasMediaView = false))
        assertTrue(canBindNative(hasVideo = false, hasMediaView = true))
    }
}
