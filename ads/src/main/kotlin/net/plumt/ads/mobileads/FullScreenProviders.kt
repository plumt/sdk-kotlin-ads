package net.plumt.ads.mobileads

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException
import net.plumt.ads.network.FullScreenAd
import net.plumt.ads.network.FullScreenAdCallbacks
import net.plumt.ads.network.FullScreenAdProvider

private fun LoadAdError.toException() = AdsException(AdsErrorKind.Load, code, message)

private fun AdError.toShowException() = AdsException(AdsErrorKind.Show, code, message)

private fun FullScreenAdCallbacks.toSdk() = object : FullScreenContentCallback() {
    override fun onAdShowedFullScreenContent() = onShown()
    override fun onAdImpression() = onImpression()
    override fun onAdClicked() = onClicked()
    override fun onAdDismissedFullScreenContent() = onDismissed()
    override fun onAdFailedToShowFullScreenContent(error: AdError) = onShowFailed(error.toShowException())
}

internal object InterstitialProvider : FullScreenAdProvider {
    override suspend fun load(context: Context, adUnitId: String): FullScreenAd =
        suspendCancellableCoroutine { cont ->
            InterstitialAd.load(
                context.applicationContext,
                adUnitId,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        val wrapped = object : FullScreenAd {
                            override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) {
                                ad.fullScreenContentCallback = callbacks.toSdk()
                                ad.show(activity)
                            }

                            override fun destroy() {
                                ad.fullScreenContentCallback = null
                            }
                        }
                        if (cont.isActive) cont.resume(wrapped) else wrapped.destroy()
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (cont.isActive) cont.resumeWithException(error.toException())
                    }
                },
            )
        }
}

internal object RewardedProvider : FullScreenAdProvider {
    override suspend fun load(context: Context, adUnitId: String): FullScreenAd =
        suspendCancellableCoroutine { cont ->
            RewardedAd.load(
                context.applicationContext,
                adUnitId,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        val wrapped = object : FullScreenAd {
                            override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) {
                                ad.fullScreenContentCallback = callbacks.toSdk()
                                ad.show(activity) { reward -> callbacks.onRewardEarned(reward.type, reward.amount) }
                            }

                            override fun destroy() {
                                ad.fullScreenContentCallback = null
                            }
                        }
                        if (cont.isActive) cont.resume(wrapped) else wrapped.destroy()
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (cont.isActive) cont.resumeWithException(error.toException())
                    }
                },
            )
        }
}

internal object AppOpenProvider : FullScreenAdProvider {
    override suspend fun load(context: Context, adUnitId: String): FullScreenAd =
        suspendCancellableCoroutine { cont ->
            AppOpenAd.load(
                context.applicationContext,
                adUnitId,
                AdRequest.Builder().build(),
                object : AppOpenAdLoadCallback() {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        val wrapped = object : FullScreenAd {
                            override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) {
                                ad.fullScreenContentCallback = callbacks.toSdk()
                                ad.show(activity)
                            }

                            override fun destroy() {
                                ad.fullScreenContentCallback = null
                            }
                        }
                        if (cont.isActive) cont.resume(wrapped) else wrapped.destroy()
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (cont.isActive) cont.resumeWithException(error.toException())
                    }
                },
            )
        }
}
