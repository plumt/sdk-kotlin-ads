package net.plumt.ads.mobileads

import android.app.Activity
import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.ads.network.FullScreenAd
import net.plumt.ads.network.FullScreenAdCallbacks
import net.plumt.ads.network.FullScreenAdProvider

/**
 * SDK 사건을 메인 스레드의 [FullScreenAdCallbacks] 로 옮긴다. Next-Gen SDK 는 사건을 백그라운드 스레드에서 부른다.
 * 세 형식의 사건 콜백 인터페이스가 모두 이 메서드들을 가져 한 벌로 구현한다.
 */
private class MainThreadEvents(private val callbacks: FullScreenAdCallbacks) :
    InterstitialAdEventCallback, RewardedAdEventCallback, AppOpenAdEventCallback {
    override fun onAdShowedFullScreenContent() = onMainThread { callbacks.onShown() }
    override fun onAdImpression() = onMainThread { callbacks.onImpression() }
    override fun onAdClicked() = onMainThread { callbacks.onClicked() }
    override fun onAdDismissedFullScreenContent() = onMainThread { callbacks.onDismissed() }
    override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
        val error = fullScreenContentError.toException()
        onMainThread { callbacks.onShowFailed(error) }
    }
}

/** 불러오기 콜백을 코루틴으로 잇는다. 취소된 뒤 도착한 광고는 [destroy] 로 놓는다. */
private suspend fun <AdT, Wrapped : FullScreenAd> loadFullScreen(
    load: (AdLoadCallback<AdT>) -> Unit,
    wrap: (AdT) -> Wrapped,
): FullScreenAd = suspendCancellableCoroutine { cont ->
    load(object : AdLoadCallback<AdT> {
        override fun onAdLoaded(ad: AdT) {
            cont.resumeOrRelease<FullScreenAd>(wrap(ad)) { it.destroy() }
        }

        override fun onAdFailedToLoad(adError: LoadAdError) {
            if (cont.isActive) cont.resumeWithException(adError.toException())
        }
    })
}

internal object InterstitialProvider : FullScreenAdProvider {
    override suspend fun load(context: Context, adUnitId: String): FullScreenAd = loadFullScreen<InterstitialAd, FullScreenAd>(
        load = { InterstitialAd.load(AdRequest.Builder(adUnitId).build(), it) },
        wrap = { ad ->
            object : FullScreenAd {
                override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) {
                    ad.adEventCallback = MainThreadEvents(callbacks)
                    ad.show(activity)
                }

                override fun destroy() {
                    ad.adEventCallback = null
                    ad.destroy()
                }
            }
        },
    )
}

internal object RewardedProvider : FullScreenAdProvider {
    override suspend fun load(context: Context, adUnitId: String): FullScreenAd = loadFullScreen<RewardedAd, FullScreenAd>(
        load = { RewardedAd.load(AdRequest.Builder(adUnitId).build(), it) },
        wrap = { ad ->
            object : FullScreenAd {
                override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) {
                    ad.adEventCallback = MainThreadEvents(callbacks)
                    ad.show(activity) { reward ->
                        val type = reward.type
                        val amount = reward.amount
                        onMainThread { callbacks.onRewardEarned(type, amount) }
                    }
                }

                override fun destroy() {
                    ad.adEventCallback = null
                    ad.destroy()
                }
            }
        },
    )
}

internal object AppOpenProvider : FullScreenAdProvider {
    override suspend fun load(context: Context, adUnitId: String): FullScreenAd = loadFullScreen<AppOpenAd, FullScreenAd>(
        load = { AppOpenAd.load(AdRequest.Builder(adUnitId).build(), it) },
        wrap = { ad ->
            object : FullScreenAd {
                override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) {
                    ad.adEventCallback = MainThreadEvents(callbacks)
                    ad.show(activity)
                }

                override fun destroy() {
                    ad.adEventCallback = null
                    ad.destroy()
                }
            }
        },
    )
}
