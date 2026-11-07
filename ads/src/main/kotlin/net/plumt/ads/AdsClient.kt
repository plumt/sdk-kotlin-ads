package net.plumt.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import net.plumt.ads.consent.ConsentManager
import net.plumt.ads.mobileads.MobileAdsNetwork
import net.plumt.ads.network.AdNetwork

/** [AdsClient.gatherConsent] 가 실제로 무엇을 했는지. 건너뛴 경우도 값으로 알린다. */
public enum class ConsentGatherResult {
    /** 동의 정보를 갱신했다(필요하면 폼을 띄웠다). 결과는 [ConsentManager.snapshot] 에 있다. */
    Completed,

    /** 스위치가 꺼져 있어(광고 제거 등) 동의 폼을 띄우지 않았다. */
    SkippedAdsDisabled,

    /** 스위치 첫 값이 제한 시간 안에 오지 않아 건너뛰었다. 값이 온 뒤 다시 부른다. */
    SkippedSwitchTimeout,

    /** 다른 동의 수집이 진행 중이라 그 호출에 맡겼다. */
    AlreadyInProgress,
}

/**
 * 광고 계층의 진입점. 앱에 하나만 두고 공유한다(DI 프레임워크는 소비자가 고른다).
 * 빈도 제한 상태도 이 인스턴스에 있으므로 화면마다 새로 만들지 않는다. 구현은 내부 클래스이고 [create] 로만 만든다.
 *
 * ```
 * val ads = AdsClient.create(applicationContext, AdsConfig(), enabledSource = removeAdsSource, units = AppAdUnits.registry)
 * ads.gatherConsentOnFirstResume(application)   // Application.onCreate — 첫 Activity 가 오르면 동의 갱신·초기화
 * ```
 */
public interface AdsClient : AutoCloseable {
    /** 앱이 넘긴 설정 그대로. 실제로 적용한 값은 [mode]·[warnings] 를 본다. */
    public val config: AdsConfig

    /** 정한 모드. [AdsMode.Development]·[AdsMode.Production] 중 하나이고 [AdsMode.Auto] 는 나오지 않는다. */
    public val mode: AdsMode

    /** [create] 에 넘긴 광고 단위 등록부. */
    public val units: AdUnitRegistry

    /** 사용 중인 네트워크. */
    public val network: AdNetwork

    /** 연결 상태. 네트워크 오류 재시도가 연결 복구를 기다릴 때 쓴다. */
    public val connectivity: AdConnectivity

    /** 동의 관리. 직접 [ConsentManager.gather] 를 부르면 스위치 확인과 초기화 재시도를 건너뛴다 — [gatherConsent] 를 쓴다. */
    public val consent: ConsentManager

    /** 앱이 꽂은 스위치의 최신 값. 값이 오기 전과 스위치 Flow 가 예외로 끝난 뒤에는 false. */
    public val adsEnabled: StateFlow<Boolean>

    /**
     * 지금 광고를 요청해도 되는가: 스위치 켜짐 + 동의가 광고 요청을 허용 + SDK 초기화 완료.
     * 광고 슬롯과 컨트롤러가 이 값으로 그려지고 불러온다.
     */
    public val adsAvailable: StateFlow<Boolean>

    /**
     * 마지막 SDK 초기화 실패. 성공하면 null. 동의·스위치가 바뀔 때, 그리고 [initializeIfConsented]·[gatherConsent] 를
     * 부를 때 다시 시도한다. 앱 ID 가 모드와 맞지 않으면 [AdsErrorKind.Configuration] 이다.
     */
    public val initializationError: StateFlow<AdsException?>

    /** 스위치 Flow 가 예외로 끝났으면 그 원인([AdsErrorKind.EnabledSource]). 이후 스위치는 꺼짐이다. */
    public val enabledSourceError: StateFlow<AdsException?>

    /**
     * 모드와 맞지 않는 설정([AdsErrorKind.Configuration]) 목록. 같은 원인은 한 번만 들어간다.
     * 운영 모드면 [create] 때 [units] 를 모두 검사해 미리 채우고, 이후 화면이 넘긴 단위·앱 ID 검사 결과가 더해진다.
     * 운영 빌드에서 비어 있지 않으면 그 광고는 나가지 않고 있다 — 출시 전 점검·원격 로그로 보낸다.
     */
    public val configurationErrors: StateFlow<List<AdsException>>

    /** 운영 모드라 무시한 개발 전용 설정. 개발 모드에서는 늘 비었다. */
    public val warnings: StateFlow<Set<AdsWarning>>

    /**
     * 분석용 사건 훅. [create] 에 넘긴 리스너를 그대로 노출한다.
     * 앱 수명 동안 살아 있으므로, 보상([AdEvent.RewardEarned])처럼 화면이 재생성돼도 잃으면 안 되는 사건은 여기서 처리한다.
     */
    public val events: AdEventListener

    /**
     * [unit] 의 이 모드 ID. 모드와 맞지 않으면 null 을 돌려주고, 원인을 [configurationErrors] 에 올리고
     * [AdEvent.Misconfigured] 를 낸다 — 던지지 않는다(운영 빌드에서 앱을 멈추지 않고 그 광고만 숨긴다).
     * 직접 광고 뷰를 만드는 호출부가 쓴다. 라이브러리 컴포넌트는 안에서 부른다.
     */
    public fun adUnitId(unit: AdUnit, placement: String = unit.name): String?

    /**
     * 지금 동의 상태가 광고를 허용하면 UI 없이 SDK 를 초기화한다. 지난 초기화가 실패했으면 다시 시도한다.
     * 허용 전이거나 스위치가 꺼져 있으면 아무것도 하지 않는다(스위치가 켜지면 그때 초기화한다).
     *
     * UMP 는 **이 프로세스에서 동의 갱신을 요청하기 전에는** 지난 실행의 동의가 있어도 `canRequestAds = false` 다.
     * 그래서 콜드 스타트의 `Application.onCreate` 에서 이것만 부르면 초기화되지 않는다 — 매 실행 첫 Activity 에서
     * [gatherConsent] 를 부르거나 [gatherConsentOnFirstResume] 을 건다.
     */
    public fun initializeIfConsented()

    /**
     * 동의 정보를 갱신하고 필요하면 폼을 띄운 뒤, 허용되면 SDK 초기화를 시작한다. 스위치가 꺼져 있으면 폼도 띄우지 않는다.
     * UMP 권장대로 **매 실행** 부른다. 갱신을 요청한 직후 지난 실행의 동의가 광고를 허용하면 응답을 기다리지 않고 초기화를 병행한다.
     * 스위치 첫 값이 아직 안 왔으면 [switchTimeoutMillis] 까지 기다린다.
     *
     * 초기화는 이 함수가 끝난 뒤에도 비동기로 진행된다. 끝나면 [adsAvailable] 이 true 가 되고, 실패는 [initializationError] 에 담긴다.
     *
     * @return 실제로 한 일. 건너뛴 이유도 여기에 있다
     * @throws AdsException 동의 갱신·폼 실패([AdsErrorKind.Consent])
     * @throws IllegalArgumentException [switchTimeoutMillis] 가 음수일 때
     */
    public suspend fun gatherConsent(
        activity: Activity,
        switchTimeoutMillis: Long = DEFAULT_SWITCH_TIMEOUT_MILLIS,
    ): ConsentGatherResult

    /**
     * 프로세스에서 처음 화면에 오른(resumed) Activity 로 [gatherConsent] 를 한 번 부른다. `Application.onCreate` 에서 건다.
     * 앱 Activity 마다 동의 코드를 두지 않아도 모든 진입점(딥링크·위젯 포함)이 같은 시점에 동의를 받는다.
     *
     * @param onResult 메인 스레드에서 한 번. 동의 실패는 [AdsException] 으로 온다 — 보고는 앱이 정한다
     */
    public fun gatherConsentOnFirstResume(
        application: Application,
        onResult: (Result<ConsentGatherResult>) -> Unit = {},
    )

    /**
     * 형식별 기본 빈도 정책. 이 클라이언트 안에서 같은 형식은 늘 같은 인스턴스라, 컨트롤러를 다시 만들어도(화면 재진입·회전) 제한이 이어진다.
     * 마지막 표시 시각은 [create] 의 저장소에 남아 프로세스 재시작을 넘는다.
     *
     * - [AdFormat.Interstitial]: [IntervalFrequencyPolicy] 기본값(시작 30초 유예, 2분 간격)
     * - [AdFormat.AppOpen]: 시작 유예 없이 [AdsConfig.appOpenMinIntervalMillis](기본 2분) 간격 +
     *   처음 [AdsConfig.appOpenSkipFirstLaunches](기본 1)번 실행 제외([LaunchCountFrequencyPolicy]). 콜드 스타트에 보일 수 있다
     * - [AdFormat.Rewarded]: [AdFrequencyPolicy.Unlimited](사용자가 직접 요청한다)
     *
     * @throws IllegalArgumentException [format] 이 전면형이 아닐 때
     */
    public fun defaultPolicy(format: AdFormat): AdFrequencyPolicy

    /**
     * [unit] 의 기본 빈도 정책. [FrequencyScope.PerFormat] 이면 [defaultPolicy] 와 같고, [FrequencyScope.PerUnit] 이면
     * 같은 규칙을 단위 이름에 묶은 별도 인스턴스다(단위마다 따로 센다. 같은 단위는 늘 같은 인스턴스).
     *
     * @throws IllegalArgumentException [unit] 이 전면형이 아닐 때
     */
    public fun defaultPolicy(unit: AdUnit, scope: FrequencyScope): AdFrequencyPolicy

    /**
     * 전면형 광고 컨트롤러를 만든다. 쓰고 나면 [FullScreenAdController.destroy] 를 부른다.
     * 이 클라이언트의 컨트롤러끼리는 한 번에 하나만 표시한다([SuppressReason.AnotherAdShowing]).
     * 단위가 모드와 맞지 않으면 던지지 않고 아무것도 불러오지 않는 컨트롤러를 돌려준다([FullScreenAdController.misconfigured]).
     *
     * @param unit [AdFormat.Interstitial]·[AdFormat.Rewarded]·[AdFormat.AppOpen] 단위만 받는다
     * @param placement 분석 사건의 배치 이름. 기본은 단위 이름
     * @param policy 빈도 제한. null 이면 [defaultPolicy] (`unit`, [frequencyScope]). 직접 넘기는 정책은 앱에 하나 두고 공유한다
     * @param fallbacks 주 단위가 실패하면 차례로 시도할 같은 형식의 단위. 모드와 맞지 않는 것은 건너뛴다
     * @param frequencyScope [policy] 가 null 일 때 기본 정책을 형식별로 공유할지 단위별로 둘지
     * @throws IllegalArgumentException 단위가 전면형이 아니거나, [fallbacks] 형식이 [unit] 과 다르거나, [placement] 가 비었을 때
     */
    public fun fullScreenController(
        unit: AdUnit,
        placement: String = unit.name,
        policy: AdFrequencyPolicy? = null,
        preload: PreloadPolicy = PreloadPolicy(),
        fallbacks: List<AdUnit> = emptyList(),
        frequencyScope: FrequencyScope = FrequencyScope.PerFormat,
    ): FullScreenAdController

    /**
     * 네이티브 광고 한 장을 앱 수명 동안 미리 받아 두는 자리. 같은 단위·배치면 같은 인스턴스를 돌려준다.
     * 종료 확인 팝업처럼 뜨는 순간 광고가 있어야 하는 곳에 쓴다.
     *
     * @param ttlMillis 1 이상. 받은 뒤 이 시간이 지나면 쓰지 않는다
     * @throws IllegalArgumentException 단위가 네이티브가 아니거나 [fallbacks] 형식이 다르거나 [ttlMillis] 가 1 미만일 때
     */
    public fun prefetchedNativeAd(
        unit: AdUnit,
        placement: String = unit.name,
        ttlMillis: Long = PrefetchedNativeAd.DEFAULT_TTL_MILLIS,
        fallbacks: List<AdUnit> = emptyList(),
    ): PrefetchedNativeAd

    /** 코루틴을 멈춘다. 이 클라이언트에서 만든 컨트롤러는 따로 destroy 한다. */
    override fun close()

    public companion object {
        /** [gatherConsent] 가 스위치 첫 값을 기다리는 기본 시간: 5초. */
        public const val DEFAULT_SWITCH_TIMEOUT_MILLIS: Long = 5_000L

        /**
         * 클라이언트를 만든다. 이 호출은 네트워크에 나가지 않는다. 모드는 [AdsConfig.mode] 로 정한다
         * ([AdsMode.Auto] 면 앱의 `FLAG_DEBUGGABLE`).
         *
         * @param context 애플리케이션 컨텍스트로 바꿔 쓴다
         * @param enabledSource 광고 끄기 스위치. 기본은 항상 켜짐
         * @param events 분석 훅
         * @param network 다른 네트워크를 쓸 때 교체한다
         * @param frequencyStore 기본 빈도 정책이 표시 기록을 남기는 곳. 기본은 앱 전용 SharedPreferences
         * @param units 광고 단위 등록부. 운영 모드면 여기서 바로 검사해 [configurationErrors] 를 채운다
         * @param connectivity 연결 상태. 기본은 시스템 연결 관리자
         */
        public fun create(
            context: Context,
            config: AdsConfig = AdsConfig(),
            enabledSource: AdsEnabledSource = AdsEnabledSource.Always,
            events: AdEventListener = AdEventListener.None,
            network: AdNetwork = MobileAdsNetwork(),
            frequencyStore: AdFrequencyStore = AdFrequencyStore.sharedPreferences(context),
            units: AdUnitRegistry = AdUnitRegistry.Empty,
            connectivity: AdConnectivity = AdConnectivity.system(context),
        ): AdsClient = DefaultAdsClient.create(
            context.applicationContext, config, enabledSource, events, network, frequencyStore, units, connectivity,
        )
    }
}

internal fun defaultScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
