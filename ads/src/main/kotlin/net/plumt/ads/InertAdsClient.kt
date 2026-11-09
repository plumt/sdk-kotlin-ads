package net.plumt.ads

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.plumt.ads.consent.ConsentGateway
import net.plumt.ads.consent.ConsentSnapshot
import net.plumt.ads.network.AdNetwork
import net.plumt.ads.network.BannerHandle
import net.plumt.ads.network.BannerListener
import net.plumt.ads.network.BannerProvider
import net.plumt.ads.network.FullScreenAdProvider
import net.plumt.ads.network.NativeAdProvider

/** 아무것도 요청하지 않는 네트워크. 미리보기·시험용 클라이언트가 쓴다. 불리면 [AdsErrorKind.Load] 로 실패한다. */
internal object InertNetwork : AdNetwork {
    private fun inert(): Nothing =
        throw AdsException(AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "광고를 요청하지 않는 클라이언트입니다")

    override suspend fun initialize(context: Context, config: AdsConfig) = Unit
    override val interstitial: FullScreenAdProvider = FullScreenAdProvider { _, _ -> inert() }
    override val rewarded: FullScreenAdProvider = interstitial
    override val appOpen: FullScreenAdProvider = interstitial
    override val banner: BannerProvider = object : BannerProvider {
        override fun create(context: Context, adUnitId: String, widthDp: Int, listener: BannerListener): BannerHandle = inert()
    }
    override val native: NativeAdProvider = NativeAdProvider { _, _, _ -> inert() }
}

internal object InertGateway : ConsentGateway {
    override fun current(): ConsentSnapshot = ConsentSnapshot.Initial
    override fun gather(activity: Activity, onUpdateRequested: () -> Unit, onDone: (AdsException?) -> Unit) = onDone(null)
    override fun showPrivacyOptions(activity: Activity, onDone: (AdsException?) -> Unit) = onDone(null)
}

/** [inert] 를 개발 모드 설정으로 만든다. */
public fun AdsClient.Companion.inert(context: Context): AdsClient =
    inert(context, AdsConfig.builder().mode(AdsMode.Development).build())

/**
 * 광고를 절대 요청하지 않는 클라이언트. 스위치는 꺼짐([AdsConfig.enabledSource] 는 무시한다), 동의·SDK 는 건드리지 않는다.
 * Compose 미리보기·스크린샷에서 [AdsClient] 가 필요한 코드에 넘긴다. 운영 코드에는 쓰지 않는다.
 * 앱 단위 테스트에서 광고 흐름을 시험하려면 [FakeAdsClient] 를 쓴다.
 */
public fun AdsClient.Companion.inert(context: Context, config: AdsConfig): AdsClient =
    DefaultAdsClient(
        context.applicationContext ?: context, config, AdsEnabledSource.Never, AdEventListener.None, InertNetwork, InertGateway,
        AdClock.Default, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )
