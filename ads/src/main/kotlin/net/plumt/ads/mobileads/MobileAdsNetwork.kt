package net.plumt.ads.mobileads

import android.content.Context
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.ads.AdContentRating
import net.plumt.ads.AdFlag
import net.plumt.ads.AdsConfig
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException
import net.plumt.ads.network.AdNetwork
import net.plumt.ads.network.BannerProvider
import net.plumt.ads.network.FullScreenAdProvider
import net.plumt.ads.network.NativeAdProvider

/**
 * Google Mobile Ads SDK 기본 구현. 비공식 연동이며 Google 과 무관하다.
 * 앱 ID 는 앱 매니페스트의 `com.google.android.gms.ads.APPLICATION_ID` meta-data 로 앱이 제공한다.
 */
public class MobileAdsNetwork : AdNetwork {
    private var initialized = false

    override val interstitial: FullScreenAdProvider = InterstitialProvider
    override val rewarded: FullScreenAdProvider = RewardedProvider
    override val appOpen: FullScreenAdProvider = AppOpenProvider
    override val banner: BannerProvider = MobileAdsBannerProvider
    override val native: NativeAdProvider = MobileAdsNativeProvider

    override suspend fun initialize(context: Context, config: AdsConfig) {
        if (initialized) return
        try {
            applyRequestConfiguration(config)
            suspendCancellableCoroutine { cont ->
                MobileAds.initialize(context.applicationContext) { if (cont.isActive) cont.resume(Unit) }
            }
            initialized = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AdsException(
                AdsErrorKind.Initialization,
                AdsException.CODE_UNKNOWN,
                "광고 SDK 초기화 실패 — 매니페스트의 APPLICATION_ID meta-data 를 확인하세요: ${e.message}",
                e,
            )
        }
    }

    private fun applyRequestConfiguration(config: AdsConfig) {
        val audience = config.audience
        val customized = audience.childDirected != AdFlag.Unspecified ||
            audience.underAgeOfConsent != AdFlag.Unspecified ||
            audience.maxContentRating != AdContentRating.Unspecified ||
            config.testDeviceIds.isNotEmpty()
        if (!customized) return
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setTagForChildDirectedTreatment(audience.childDirected.toSdkTag())
                .setTagForUnderAgeOfConsent(audience.underAgeOfConsent.toSdkTag())
                .setMaxAdContentRating(audience.maxContentRating.toSdkRating())
                .setTestDeviceIds(config.testDeviceIds)
                .build()
        )
    }
}

/** SDK 의 `RequestConfiguration.TAG_FOR_*` 상수: 지정 안 함 -1, 아니오 0, 예 1. */
internal fun AdFlag.toSdkTag(): Int = when (this) {
    AdFlag.Unspecified -> -1
    AdFlag.No -> 0
    AdFlag.Yes -> 1
}

/** SDK 의 `MAX_AD_CONTENT_RATING_*` 문자열. 지정 안 함은 빈 문자열이다. */
internal fun AdContentRating.toSdkRating(): String = when (this) {
    AdContentRating.Unspecified -> ""
    else -> name
}
