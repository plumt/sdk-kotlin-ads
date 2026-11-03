package net.plumt.ads.mobileads

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException
import net.plumt.ads.network.NativeAdAssets
import net.plumt.ads.network.NativeAdHandle
import net.plumt.ads.network.NativeAdProvider
import net.plumt.ads.network.NativeAdViews

internal object MobileAdsNativeProvider : NativeAdProvider {
    override suspend fun load(context: Context, adUnitId: String, count: Int): List<NativeAdHandle> {
        require(count in 1..MAX_COUNT) { "count 는 1..$MAX_COUNT 이어야 합니다: $count" }
        return suspendCancellableCoroutine { cont ->
            val loaded = mutableListOf<NativeAd>()
            var failure: LoadAdError? = null
            var loader: AdLoader? = null

            fun finishIfDone() {
                if (loader?.isLoading != false || !cont.isActive) return
                if (loaded.isEmpty()) {
                    val e = failure
                    cont.resumeWithException(
                        AdsException(
                            AdsErrorKind.Load,
                            e?.code ?: AdsException.CODE_UNKNOWN,
                            e?.message ?: "네이티브 광고를 받지 못했습니다",
                        )
                    )
                } else {
                    cont.resume(loaded.map { MobileAdsNativeHandle(it) })
                }
            }

            loader = AdLoader.Builder(context.applicationContext, adUnitId)
                .forNativeAd { ad ->
                    if (cont.isActive) loaded += ad else ad.destroy()
                    finishIfDone()
                }
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                        .build()
                )
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        failure = error
                        finishIfDone()
                    }
                })
                .build()
            cont.invokeOnCancellation { loaded.forEach { it.destroy() } }
            loader.loadAds(AdRequest.Builder().build(), count)
        }
    }

    /** 한 요청으로 받을 수 있는 광고 수 상한. */
    const val MAX_COUNT = 5
}

private class MobileAdsNativeHandle(private val ad: NativeAd) : NativeAdHandle {
    override val assets: NativeAdAssets = NativeAdAssets(
        headline = ad.headline,
        body = ad.body,
        callToAction = ad.callToAction,
        advertiser = ad.advertiser,
        price = ad.price,
        store = ad.store,
        starRating = ad.starRating,
        icon = ad.icon?.drawable,
    )

    override fun bind(container: ViewGroup, views: NativeAdViews) {
        val media = views.media
        require(media == null || media is MediaView) {
            "media 뷰는 MediaView 여야 합니다: ${media?.javaClass?.name}"
        }
        // 광고 SDK 는 뷰들이 NativeAdView 아래에 있어야 클릭·노출을 센다. 앱 레이아웃을 그 아래로 옮겨 끼운다.
        val adView = NativeAdView(container.context)
        val children = (0 until container.childCount).map { container.getChildAt(it) }
        container.removeAllViews()
        children.forEach { adView.addView(it) }
        container.addView(
            adView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        adView.headlineView = views.headline
        adView.bodyView = views.body
        adView.callToActionView = views.callToAction
        adView.advertiserView = views.advertiser
        adView.priceView = views.price
        adView.storeView = views.store
        adView.starRatingView = views.starRating
        adView.iconView = views.icon
        adView.mediaView = media as MediaView?
        (views.headline as? TextView)?.text = ad.headline
        (views.body as? TextView)?.apply { text = ad.body; visibility = visibleIf(ad.body) }
        (views.callToAction as? TextView)?.apply { text = ad.callToAction; visibility = visibleIf(ad.callToAction) }
        (views.advertiser as? TextView)?.apply { text = ad.advertiser; visibility = visibleIf(ad.advertiser) }
        (views.price as? TextView)?.apply { text = ad.price; visibility = visibleIf(ad.price) }
        (views.store as? TextView)?.apply { text = ad.store; visibility = visibleIf(ad.store) }
        (views.icon as? android.widget.ImageView)?.apply {
            setImageDrawable(ad.icon?.drawable)
            visibility = if (ad.icon == null) View.GONE else View.VISIBLE
        }
        adView.setNativeAd(ad)
    }

    override fun destroy() = ad.destroy()

    private fun visibleIf(text: String?) = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
}
