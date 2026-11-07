package net.plumt.ads

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import net.plumt.ads.network.BannerListener
import net.plumt.ads.network.FullScreenAdCallbacks
import net.plumt.ads.network.NativeAdProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 미리보기용 무동작 클라이언트와 인터페이스 기본 구현. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InertClientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).get()

    @Test fun inertClientNeverRequests() = runBlocking {
        val c = AdsClient.inert(context)
        assertEquals(AdsMode.Development, c.mode)
        assertFalse(c.adsAvailable.value)
        assertFalse(c.adsEnabled.value)
        val ctrl = c.fullScreenController(AdUnit.interstitial("i", ""))
        assertEquals(ShowResult.Suppressed(SuppressReason.Unavailable), ctrl.show(activity))
        ctrl.destroy()
        assertEquals(ConsentGatherResult.SkippedAdsDisabled, c.gatherConsent(activity))
        c.consent.showPrivacyOptions(activity) // 폼 없이 끝난다
        c.close()
    }

    @Test fun inertNetworkFailsLoudlyIfCalled() = runBlocking {
        val net = InertNetwork
        net.initialize(context, AdsConfig())
        val load = assertThrows(AdsException::class.java) { runBlocking { net.interstitial.load(context, "x") } }
        assertEquals(AdsErrorKind.Load, load.kind)
        assertThrows(AdsException::class.java) { runBlocking { net.rewarded.load(context, "x") } }
        assertThrows(AdsException::class.java) { runBlocking { net.appOpen.load(context, "x") } }
        assertThrows(AdsException::class.java) { runBlocking { net.native.load(context, "x", 1) } }
        assertThrows(AdsException::class.java) { net.banner.create(context, "x", 320, object : BannerListener {}) }
        assertNull(net.native.createMediaView(context))
        var done = 0
        InertGateway.gather(activity, {}) { done++ }
        InertGateway.showPrivacyOptions(activity) { done++ }
        assertEquals(2, done)
    }

    @Test fun interfaceDefaultsAreNoOps() {
        val listener = object : BannerListener {}
        listener.onLoaded(); listener.onImpression(); listener.onClicked()
        listener.onLoadFailed(AdsException(AdsErrorKind.Load, 1, "x"))
        val cb = object : FullScreenAdCallbacks {}
        cb.onShown(); cb.onImpression(); cb.onClicked(); cb.onDismissed(); cb.onRewardEarned("t", 1)
        cb.onShowFailed(AdsException(AdsErrorKind.Show, 1, "x"))
        val provider = NativeAdProvider { _, _, _ -> emptyList() }
        assertNull(provider.createMediaView(context))
        val v: View? = provider.createMediaView(context)
        assertTrue(v == null)
    }
}
