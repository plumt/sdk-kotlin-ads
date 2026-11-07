package net.plumt.ads

import android.view.View
import net.plumt.ads.network.BannerListener
import net.plumt.ads.network.FullScreenAdCallbacks
import net.plumt.ads.network.NativeAdAssets
import net.plumt.ads.network.NativeAdViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28])
class NetworkModelsTest {
    @Test fun fullScreenCallbacksDefaultsDoNothing() {
        val cb = object : FullScreenAdCallbacks {}
        cb.onShown(); cb.onImpression(); cb.onClicked(); cb.onDismissed()
        cb.onShowFailed(AdsException(AdsErrorKind.Show, 1, "x"))
        cb.onRewardEarned("coin", 1)
    }

    @Test fun bannerListenerDefaultsDoNothing() {
        val l = object : BannerListener {}
        l.onLoaded(); l.onImpression(); l.onClicked()
        l.onLoadFailed(AdsException(AdsErrorKind.Load, 1, "x"))
    }

    @Test fun nativeViewsDefaultToNull() {
        val v = NativeAdViews()
        listOf(v.headline, v.body, v.callToAction, v.advertiser, v.price, v.store, v.starRating, v.icon, v.media).forEach { assertNull(it) }
    }

    @Test fun nativeAssetsHoldValues() {
        val a = NativeAdAssets("h", "b", "c", "adv", "1000", "store", 4.5, null)
        assertEquals("h", a.headline); assertEquals("b", a.body); assertEquals("c", a.callToAction)
        assertEquals("adv", a.advertiser); assertEquals("1000", a.price); assertEquals("store", a.store)
        assertEquals(4.5, a.starRating!!, 0.0); assertNull(a.icon)
        org.junit.Assert.assertFalse(a.hasVideo)
        org.junit.Assert.assertTrue(NativeAdAssets(null, null, null, null, null, null, null, null, hasVideo = true).hasVideo)
    }

    @Test fun nativeViewsHoldGivenViews() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = View(ctx)
        assertEquals(view, NativeAdViews(headline = view).headline)
    }
}
