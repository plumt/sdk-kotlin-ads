package net.plumt.ads

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import net.plumt.ads.consent.ConsentManager
import net.plumt.ads.mobileads.MobileAdsNetwork
import net.plumt.ads.network.AdNetwork

/**
 * 광고 계층의 진입점. 앱에 하나만 두고 공유한다(DI 프레임워크는 소비자가 고른다).
 * 구현은 내부 클래스이고 [create] 로만 만든다.
 *
 * ```
 * val ads = AdsClient.create(applicationContext, AdsConfig(), enabledSource = removeAdsSource)
 * ads.initializeIfConsented()          // Application.onCreate — UI 없음
 * ads.gatherConsent(activity)          // 첫 Activity 에서 — 동의 폼
 * ```
 */
public interface AdsClient : AutoCloseable {
    /** 설정. */
    public val config: AdsConfig

    /** 사용 중인 네트워크. */
    public val network: AdNetwork

    /** 동의 관리. */
    public val consent: ConsentManager

    /** 앱이 꽂은 스위치의 최신 값. 값이 오기 전에는 false. */
    public val adsEnabled: StateFlow<Boolean>

    /**
     * 지금 광고를 요청해도 되는가: 스위치 켜짐 + 동의가 광고 요청을 허용 + SDK 초기화 완료.
     * 광고 슬롯과 컨트롤러가 이 값으로 그려지고 불러온다.
     */
    public val adsAvailable: StateFlow<Boolean>

    /** 마지막 SDK 초기화 실패. 성공하면 null. 실패해도 다음 동의·스위치 변화 때 다시 시도한다. */
    public val initializationError: StateFlow<AdsException?>

    /** 분석용 사건 훅. [create] 에 넘긴 리스너를 그대로 노출한다. */
    public val events: AdEventListener

    /**
     * 저장된 동의가 이미 광고를 허용하면 UI 없이 SDK 를 초기화한다. 앱 시작 때 한 번 부른다.
     * 허용 전이거나 스위치가 꺼져 있으면 아무것도 하지 않는다(스위치가 켜지면 그때 초기화한다).
     */
    public fun initializeIfConsented()

    /**
     * 동의 정보를 갱신하고 필요하면 폼을 띄운 뒤, 허용되면 SDK 를 초기화한다. 스위치가 꺼져 있으면 폼도 띄우지 않는다.
     *
     * @throws AdsException 동의 실패 또는 초기화 실패
     */
    public suspend fun gatherConsent(activity: Activity)

    /**
     * 전면형 광고 컨트롤러를 만든다. 쓰고 나면 [FullScreenAdController.destroy] 를 부른다.
     *
     * @param format [AdFormat.Interstitial]·[AdFormat.Rewarded]·[AdFormat.AppOpen] 만 받는다
     * @param policy 빈도 제한. 기본은 전면·앱 오프닝에 [IntervalFrequencyPolicy](새 인스턴스), 보상형에 [AdFrequencyPolicy.Unlimited]
     * @throws IllegalArgumentException [format] 이 전면형이 아니거나 [adUnitId]·[placement] 가 비었을 때
     */
    public fun fullScreenController(
        format: AdFormat,
        placement: String,
        adUnitId: String,
        policy: AdFrequencyPolicy = defaultPolicyFor(format),
        preload: PreloadPolicy = PreloadPolicy(),
    ): FullScreenAdController

    /** 코루틴을 멈춘다. 이 클라이언트에서 만든 컨트롤러는 따로 destroy 한다. */
    override fun close()

    public companion object {
        /**
         * 클라이언트를 만든다. 이 호출은 네트워크에 나가지 않는다.
         *
         * @param context 애플리케이션 컨텍스트로 바꿔 쓴다
         * @param enabledSource 광고 끄기 스위치. 기본은 항상 켜짐
         * @param events 분석 훅
         * @param network 다른 네트워크를 쓸 때 교체한다
         */
        public fun create(
            context: Context,
            config: AdsConfig = AdsConfig(),
            enabledSource: AdsEnabledSource = AdsEnabledSource.Always,
            events: AdEventListener = AdEventListener.None,
            network: AdNetwork = MobileAdsNetwork(),
        ): AdsClient = DefaultAdsClient.create(context.applicationContext, config, enabledSource, events, network)

        /** 형식별 기본 빈도 정책. */
        public fun defaultPolicyFor(format: AdFormat): AdFrequencyPolicy = when (format) {
            AdFormat.Rewarded -> AdFrequencyPolicy.Unlimited
            else -> IntervalFrequencyPolicy()
        }
    }
}

internal fun defaultScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
