package net.plumt.ads.mobileads

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.common.VideoOptions
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
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
            val gate = NativeLoadGate<NativeAd>(count)

            // Next-Gen SDK 는 콜백을 백그라운드 스레드(광고마다 다를 수 있다)에서 부른다. 판단은 메인 스레드에서 한 줄로 한다
            fun settle(outcome: NativeLoadGate.Outcome<NativeAd>) {
                when (outcome) {
                    NativeLoadGate.Outcome.Pending -> Unit
                    is NativeLoadGate.Outcome.Late -> outcome.ad.destroy()
                    is NativeLoadGate.Outcome.Success -> {
                        val handles = outcome.ads.map { MobileAdsNativeHandle(it) }
                        cont.resumeOrRelease<List<NativeAdHandle>>(handles) { list -> list.forEach { it.destroy() } }
                    }
                    is NativeLoadGate.Outcome.Failure -> if (cont.isActive) {
                        val code = outcome.code
                        cont.resumeWithException(
                            if (code != null) loadException(code, outcome.message.orEmpty())
                            else AdsException(AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "네이티브 광고를 받지 못했습니다")
                        )
                    }
                }
            }

            val callback = object : NativeAdLoaderCallback {
                override fun onNativeAdLoaded(nativeAd: NativeAd) = onMainThread {
                    if (!cont.isActive) nativeAd.destroy() else settle(gate.onAd(nativeAd, stillLoading = true))
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    val code = adError.code.value
                    val message = adError.message
                    // 실패 콜백은 이 요청의 끝이다
                    onMainThread { settle(gate.onFailed(code, message)) }
                }

                override fun onAdLoadingCompleted() = onMainThread { settle(gate.onCompleted()) }
            }
            val request = NativeAdRequest.Builder(adUnitId, listOf(NativeAd.NativeAdType.NATIVE))
                .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
                .build()
            cont.invokeOnCancellation { onMainThread { gate.pending.forEach { it.destroy() } } }
            if (count == 1) NativeAdLoader.load(request, callback) else NativeAdLoader.load(request, count, callback)
        }
    }

    override fun createMediaView(context: Context): View = MediaView(context)

    /** 한 요청으로 받을 수 있는 광고 수 상한. */
    const val MAX_COUNT = 5
}

/** 동영상 광고는 미디어 뷰 없이 보일 수 없다(정책). */
internal fun canBindNative(hasVideo: Boolean, hasMediaView: Boolean): Boolean = !hasVideo || hasMediaView

private class MobileAdsNativeHandle(private val ad: NativeAd) : NativeAdHandle {
    private var adView: NativeAdView? = null

    override val assets: NativeAdAssets = NativeAdAssets(
        headline = ad.headline,
        body = ad.body,
        callToAction = ad.callToAction,
        advertiser = ad.advertiser,
        price = ad.price,
        store = ad.store,
        starRating = ad.starRating,
        icon = ad.icon?.drawable,
        hasVideo = ad.mediaContent?.hasVideoContent == true,
    )

    override fun bind(container: ViewGroup, views: NativeAdViews): Boolean {
        val media = views.media
        require(media == null || media is MediaView) {
            "media 뷰는 Next-Gen MediaView(createMediaView 로 만든 뷰)여야 합니다: ${media?.javaClass?.name}"
        }
        if (!canBindNative(assets.hasVideo, media != null)) return false
        // 다시 묶으면 이전 래퍼를 놓는다(목록 스크롤에서 뷰가 쌓이지 않게)
        adView?.destroy()
        // 광고 SDK 는 뷰들이 NativeAdView 아래에 있어야 클릭·노출을 센다. 앱 레이아웃을 그 아래로 옮겨 끼운다.
        val adView = NativeAdView(container.context).also { this.adView = it }
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
        val mediaView = (media as MediaView?)?.apply {
            imageScaleType = ImageView.ScaleType.CENTER_CROP
            // 미디어 자산이 없는 광고면 빈 미디어 자리를 남기지 않는다
            visibility = if (ad.mediaContent == null) View.GONE else View.VISIBLE
        }
        (views.headline as? TextView)?.text = ad.headline
        (views.body as? TextView)?.apply { text = ad.body; visibility = visibleIf(ad.body) }
        (views.callToAction as? TextView)?.apply { text = ad.callToAction; visibility = visibleIf(ad.callToAction) }
        (views.advertiser as? TextView)?.apply { text = ad.advertiser; visibility = visibleIf(ad.advertiser) }
        (views.price as? TextView)?.apply { text = ad.price; visibility = visibleIf(ad.price) }
        (views.store as? TextView)?.apply { text = ad.store; visibility = visibleIf(ad.store) }
        (views.starRating as? RatingBar)?.apply {
            val stars = ad.starRating
            rating = stars?.toFloat() ?: 0f
            visibility = if (stars == null) View.GONE else View.VISIBLE
        }
        (views.icon as? ImageView)?.apply {
            setImageDrawable(ad.icon?.drawable)
            visibility = if (ad.icon == null) View.GONE else View.VISIBLE
        }
        // Next-Gen 은 미디어 뷰를 등록 호출로 받는다. 자산 뷰를 모두 단 뒤에 부른다
        adView.registerNativeAd(ad, mediaView)
        return true
    }

    override fun destroy() {
        adView?.destroy()
        adView = null
        ad.destroy()
    }

    private fun visibleIf(text: String?) = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
}
