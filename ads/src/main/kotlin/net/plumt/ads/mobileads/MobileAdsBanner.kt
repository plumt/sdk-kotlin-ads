package net.plumt.ads.mobileads

import android.content.Context
import android.view.View
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRefreshCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException
import net.plumt.ads.network.BannerHandle
import net.plumt.ads.network.BannerListener
import net.plumt.ads.network.BannerProvider

internal object MobileAdsBannerProvider : BannerProvider {
    override fun create(context: Context, adUnitId: String, widthDp: Int, listener: BannerListener): BannerHandle {
        val size = try {
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
        } catch (e: Exception) {
            throw AdsException(AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "배너 크기 계산 실패: ${e.message}", e)
        }
        val adView = try {
            AdView(context)
        } catch (e: Exception) {
            throw AdsException(AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "배너 뷰 생성 실패: ${e.message}", e)
        }
        return MobileAdsBannerHandle(adView, adUnitId, size, listener)
    }
}

/**
 * Next-Gen 배너. SDK 콜백은 백그라운드 스레드에서 오므로 [BannerListener] 는 메인 스레드로 옮겨 부른다.
 * 자동 갱신(AdMob 콘솔 설정)은 SDK 가 배너가 보이는 동안에만 돌린다 — 레거시의 pause/resume 에 해당하는 API 가 없어
 * [pause]·[resume] 은 아무것도 하지 않는다.
 */
private class MobileAdsBannerHandle(
    private val adView: AdView,
    private val adUnitId: String,
    private val size: AdSize,
    private val listener: BannerListener,
) : BannerHandle {
    @Volatile
    private var destroyed = false

    override val view: View = adView
    override val heightDp: Int = size.height

    override fun load() {
        if (destroyed) return
        adView.loadAd(
            BannerAdRequest.Builder(adUnitId, size).build(),
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    // 사건 콜백은 보이기 전에 단다(공식 안내)
                    ad.adEventCallback = object : BannerAdEventCallback {
                        override fun onAdImpression() = deliver { listener.onImpression() }
                        override fun onAdClicked() = deliver { listener.onClicked() }
                    }
                    ad.bannerAdRefreshCallback = object : BannerAdRefreshCallback {
                        override fun onAdRefreshed() = deliver { listener.onLoaded() }
                        override fun onAdFailedToRefresh(adError: LoadAdError) {
                            val error = adError.toException()
                            deliver { listener.onLoadFailed(error) }
                        }
                    }
                    deliver { listener.onLoaded() }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    val error = adError.toException()
                    deliver { listener.onLoadFailed(error) }
                }
            },
        )
    }

    /** 놓은 뒤 늦게 온 사건은 버린다. */
    private fun deliver(block: () -> Unit) = onMainThread { if (!destroyed) block() }

    override fun pause() = Unit

    override fun resume() = Unit

    override fun destroy() {
        destroyed = true
        adView.destroy()
    }
}
