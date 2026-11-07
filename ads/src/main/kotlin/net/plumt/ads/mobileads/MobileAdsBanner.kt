package net.plumt.ads.mobileads

import android.content.Context
import android.view.View
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
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
            AdView(context).apply {
                setAdSize(size)
                this.adUnitId = adUnitId
                adListener = object : AdListener() {
                    override fun onAdLoaded() = listener.onLoaded()
                    override fun onAdImpression() = listener.onImpression()
                    override fun onAdClicked() = listener.onClicked()
                    override fun onAdFailedToLoad(error: LoadAdError) =
                        listener.onLoadFailed(loadException(error.code, error.message))
                }
            }
        } catch (e: Exception) {
            throw AdsException(AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "배너 뷰 생성 실패: ${e.message}", e)
        }
        return object : BannerHandle {
            override val view: View = adView
            override val heightDp: Int = size.height
            override fun load() = adView.loadAd(AdRequest.Builder().build())
            override fun pause() = adView.pause()
            override fun resume() = adView.resume()
            override fun destroy() {
                adView.adListener = object : AdListener() {}
                adView.destroy()
            }
        }
    }
}
