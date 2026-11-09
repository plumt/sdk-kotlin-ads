package net.plumt.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.plumt.ads.consent.CompletedHandle
import net.plumt.ads.consent.ConsentFormResult
import net.plumt.ads.consent.ConsentManager
import net.plumt.ads.consent.FakeConsentManager
import net.plumt.ads.network.AdNetwork
import net.plumt.ads.network.BannerHandle
import net.plumt.ads.network.BannerListener
import net.plumt.ads.network.BannerProvider
import net.plumt.ads.network.FullScreenAd
import net.plumt.ads.network.FullScreenAdCallbacks
import net.plumt.ads.network.FullScreenAdProvider
import net.plumt.ads.network.NativeAdAssets
import net.plumt.ads.network.NativeAdHandle
import net.plumt.ads.network.NativeAdProvider
import net.plumt.ads.network.NativeAdViews

/**
 * 테스트용 [AdsClient]. 광고 SDK·UMP·기기 없이 앱의 광고 흐름(언제 보이나, 결과를 어떻게 다루나)을 단위 테스트한다.
 *
 * - 컨트롤러([fullScreenController])·미리 받기([prefetchedNativeAd])·빈도 정책·광고 단위 검사는 **기본 구현과 같은 내부 로직**을 쓴다.
 *   광고는 가짜 네트워크가 곧바로 채운다. [loadFailure] 를 두면 모든 불러오기가 그 오류로 실패한다
 * - 광고 사용 가능([adsAvailable]) = [setAdsEnabled] 의 스위치(기본 켜짐) + [consent] 의 `canRequestAds`(기본 허용) +
 *   초기화 실패 없음([setInitializationError]). [AdsConfig.enabledSource]·[AdsConfig.network] 는 쓰지 않는다
 * - 전면형 광고를 보이면 [FullScreenAdCallbacks.onShown]·`onImpression` 까지 곧바로 불린다. 닫기·보상은 [dismissShowingAd]·[earnReward]
 *   로 흉내 낸다. [showFailure] 를 두면 표시가 그 오류로 실패한다
 * - 동의 수집([gatherConsent])은 스위치가 꺼져 있으면 건너뛰고, 아니면 [consent] 의 [FakeConsentManager.gatherResult] 를 따른다.
 *   [gatherConsentOnFirstResume] 은 화면을 기다리지 않고 곧바로 한 번 수집한다
 * - 콜백 형태는 **호출한 스레드에서 곧바로** 결과를 준다. 코루틴은 `Dispatchers.Unconfined` 에서 돈다(메인 디스패처 불필요)
 * - 모든 사건은 [recordedEvents] 에 남고 [AdsConfig.events] 에도 전달된다
 * - 모드는 [AdsMode.Auto] 면 개발이다. 운영 모드면 [AdsConfig.units] 를 만들 때 검사한다(기본 구현과 같다)
 * - 넘긴 [Activity]·[Application] 은 쓰지 않는다. 배너는 Compose 슬롯이 넘긴 컨텍스트로 빈 뷰를 만든다(Robolectric 필요)
 *
 * @param config 설정. 빈도 기록은 [AdsConfig.frequencyStore] 가 없으면 메모리에 남긴다
 * @param clock 빈도 정책·만료 판정의 시각
 */
public class FakeAdsClient(
    override val config: AdsConfig,
    private val clock: AdClock,
) : AdsClient {

    /** 기본 설정, 기기 시계. */
    public constructor() : this(AdsConfig(), AdClock.Wall)

    /** 기기 시계. */
    public constructor(config: AdsConfig) : this(config, AdClock.Wall)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    override val mode: AdsMode = config.mode.resolve(debuggable = true)

    override val units: AdUnitRegistry = config.units

    /** 동의 상태와 폼 결과를 정하는 가짜. [consent] 와 같은 인스턴스다. */
    public val fakeConsent: FakeConsentManager = FakeConsentManager()

    override val consent: ConsentManager get() = fakeConsent

    private val enabledState = MutableStateFlow(true)
    override val adsEnabled: StateFlow<Boolean> = enabledState.asStateFlow()

    private val initError = MutableStateFlow<AdsException?>(null)
    override val initializationError: StateFlow<AdsException?> = initError.asStateFlow()

    override val enabledSourceError: StateFlow<AdsException?> = MutableStateFlow<AdsException?>(null).asStateFlow()

    override val adsAvailable: StateFlow<Boolean> =
        combine(enabledState, fakeConsent.snapshot, initError) { enabled, snap, error -> enabled && snap.canRequestAds && error == null }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private val configErrors = MutableStateFlow<List<AdsException>>(emptyList())
    private val configErrorKeys = HashSet<String>()
    override val configurationErrors: StateFlow<List<AdsException>> = configErrors.asStateFlow()

    override val warnings: StateFlow<Set<AdsWarning>> = MutableStateFlow(config.effectiveFor(mode).second).asStateFlow()

    private val recorded = Collections.synchronizedList(ArrayList<AdEvent>())

    /** 지금까지 낸 사건(순서대로). */
    public val recordedEvents: List<AdEvent> get() = synchronized(recorded) { recorded.toList() }

    override val events: AdEventListener = AdEventListener { event ->
        recorded += event
        config.events.onEvent(event)
    }

    private val onlineState = MutableStateFlow(true)
    override val connectivity: AdConnectivity = AdConnectivity { onlineState }

    /** 모든 불러오기(전면형·배너·네이티브)를 이 오류로 실패시킨다. null(기본)이면 곧바로 채운다. */
    @Volatile
    public var loadFailure: AdsException? = null

    /** 전면형 광고 표시를 이 오류로 실패시킨다(`kind` 는 [AdsErrorKind.Show] 로 둔다). null(기본)이면 표시한다. */
    @Volatile
    public var showFailure: AdsException? = null

    /** [gatherConsent] 가 이 값을 그대로 돌려주게 한다. null(기본)이면 스위치·[fakeConsent] 를 따른다. */
    @Volatile
    public var gatherResult: ConsentGatherResult? = null

    @Volatile private var gathers = 0

    @Volatile private var initializes = 0

    @Volatile private var fullScreenLoads = 0

    /** [gatherConsent]·[gatherConsentOnFirstResume] 이 동의를 수집한 횟수. */
    public val gatherConsentCalls: Int get() = gathers

    /** [initializeIfConsented] 가 불린 횟수. */
    public val initializeCalls: Int get() = initializes

    /** 전면형 광고를 불러온 횟수(실패 포함). */
    public val fullScreenLoadCalls: Int get() = fullScreenLoads

    private var showing: FakeFullScreenAd? = null

    /** 지금 화면에 떠 있는(가짜) 전면형 광고가 있나. */
    public val isShowingFullScreenAd: Boolean get() = showing != null

    override val network: AdNetwork = FakeNetwork()

    private val coordinator = FullScreenCoordinator(clock, config.appOpenCooldownMillis)
    private val policies = DefaultPolicies(config, clock, config.frequencyStore ?: AdFrequencyStore.inMemory())
    private val prefetched = HashMap<Pair<AdUnit, String>, PrefetchedNativeAd>()

    init {
        if (mode == AdsMode.Production) units.all.forEach { unit -> unit.problemFor(mode)?.let { recordConfiguration(it) } }
    }

    /** 광고 끄기 스위치를 바꾼다. 끄면 컨트롤러는 들고 있던 광고를 버린다. */
    public fun setAdsEnabled(enabled: Boolean) {
        enabledState.value = enabled
    }

    /** 연결 상태를 바꾼다. 네트워크 실패 재시도는 연결이 돌아올 때까지 기다린다. */
    public fun setOnline(online: Boolean) {
        onlineState.value = online
    }

    /** SDK 초기화 실패를 흉내 낸다. null 이 아니면 광고를 쓸 수 없다. */
    public fun setInitializationError(error: AdsException?) {
        initError.value = error
    }

    /** 떠 있는 전면형 광고를 닫는다. 떠 있는 광고가 없으면 false. */
    public fun dismissShowingAd(): Boolean {
        val ad = showing ?: return false
        showing = null
        ad.callbacks?.onDismissed()
        return true
    }

    /** 떠 있는 (보상형) 광고의 보상 조건을 채운다. 떠 있는 광고가 없으면 false. */
    public fun earnReward(type: String, amount: Int): Boolean {
        val ad = showing ?: return false
        ad.callbacks?.onRewardEarned(type, amount)
        return true
    }

    /** 떠 있는 광고가 눌렸다. 떠 있는 광고가 없으면 false. */
    public fun clickShowingAd(): Boolean {
        val ad = showing ?: return false
        ad.callbacks?.onClicked()
        return true
    }

    override fun adUnitId(unit: AdUnit, placement: String): String? = try {
        unit.idFor(mode)
    } catch (e: AdsException) {
        if (recordConfiguration(e)) events.onEvent(AdEvent.Misconfigured(unit.format, placement, e.code, e.message.orEmpty()))
        null
    }

    private fun recordConfiguration(e: AdsException): Boolean {
        synchronized(configErrorKeys) { if (!configErrorKeys.add("${e.code}|${e.message}")) return false }
        configErrors.value = configErrors.value + e
        return true
    }

    override fun initializeIfConsented() {
        synchronized(this) { initializes++ }
    }

    override suspend fun gatherConsent(activity: Activity, switchTimeoutMillis: Long): ConsentGatherResult {
        require(switchTimeoutMillis >= 0) { "switchTimeoutMillis 는 0 이상이어야 합니다: $switchTimeoutMillis" }
        return gatherNow()
    }

    override fun gatherConsent(
        activity: Activity,
        switchTimeoutMillis: Long,
        onResult: (ConsentGatherResult) -> Unit,
    ): AdsCallHandle {
        require(switchTimeoutMillis >= 0) { "switchTimeoutMillis 는 0 이상이어야 합니다: $switchTimeoutMillis" }
        onResult(gatherNow())
        return CompletedHandle
    }

    override fun gatherConsentOnFirstResume(application: Application, onResult: (ConsentGatherResult) -> Unit): AdsCallHandle {
        onResult(gatherNow())
        return CompletedHandle
    }

    private fun gatherNow(): ConsentGatherResult {
        synchronized(this) { gathers++ }
        gatherResult?.let { return it }
        if (!enabledState.value) return ConsentGatherResult.SkippedAdsDisabled
        return when (val form = fakeConsent.gatherNow()) {
            ConsentFormResult.Completed -> ConsentGatherResult.Completed
            ConsentFormResult.AlreadyInProgress -> ConsentGatherResult.AlreadyInProgress
            is ConsentFormResult.Failed -> ConsentGatherResult.Failed(form.error)
        }
    }

    override fun defaultPolicy(format: AdFormat): AdFrequencyPolicy = policies.forFormat(format)

    override fun defaultPolicy(unit: AdUnit, scope: FrequencyScope): AdFrequencyPolicy = policies.forUnit(unit, scope)

    override fun fullScreenController(unit: AdUnit, options: FullScreenAdOptions): FullScreenAdController {
        unit.requireFormat(AdFormat.Interstitial, AdFormat.Rewarded, AdFormat.AppOpen)
        val placement = options.placement ?: unit.name
        require(placement.isNotBlank()) { "placement 가 비었습니다: name=${unit.name}" }
        val chain = chainOf(unit, options.fallbacks, placement)
        return FullScreenAdController(
            unit.format, placement, chain, { loadFullScreen() }, options.policy ?: defaultPolicy(unit, options.frequencyScope),
            options.preload, adsAvailable, events, clock, scope, coordinator, connectivity,
        )
    }

    override fun prefetchedNativeAd(unit: AdUnit, placement: String, ttlMillis: Long, fallbacks: List<AdUnit>): PrefetchedNativeAd {
        unit.requireFormat(AdFormat.Native)
        require(placement.isNotBlank()) { "placement 가 비었습니다: name=${unit.name}" }
        require(ttlMillis >= 1) { "ttlMillis 는 1 이상이어야 합니다: $ttlMillis" }
        return prefetched.getOrPut(unit to placement) {
            val chain = chainOf(unit, fallbacks, placement).map { it.second }
            PrefetchedNativeAd(unit, placement, ttlMillis, chain, { id, count -> loadNative(id, count) }, adsAvailable, events, clock, scope)
        }
    }

    override fun close() {
        scope.cancel()
    }

    private fun chainOf(unit: AdUnit, fallbacks: List<AdUnit>, placement: String): List<Pair<AdUnit, String>> {
        fallbacks.forEach { it.requireFormat(unit.format) }
        return (listOf(unit) + fallbacks).distinct().mapNotNull { u -> adUnitId(u, placement)?.let { u to it } }
    }

    private fun loadFullScreen(): FullScreenAd {
        synchronized(this) { fullScreenLoads++ }
        loadFailure?.let { throw it }
        return FakeFullScreenAd()
    }

    private fun loadNative(adUnitId: String, count: Int): List<NativeAdHandle> {
        loadFailure?.let { throw it }
        return List(count) { FakeNativeAdHandle(adUnitId) }
    }

    override fun toString(): String = "FakeAdsClient(mode=$mode, adsAvailable=${adsAvailable.value})"

    private inner class FakeFullScreenAd : FullScreenAd {
        var callbacks: FullScreenAdCallbacks? = null

        override fun show(activity: Activity, callbacks: FullScreenAdCallbacks) {
            showFailure?.let {
                callbacks.onShowFailed(it)
                return
            }
            this.callbacks = callbacks
            showing = this
            callbacks.onShown()
            callbacks.onImpression()
        }

        override fun destroy() {
            callbacks = null
            if (showing === this) showing = null
        }
    }

    private inner class FakeNetwork : AdNetwork {
        override suspend fun initialize(context: Context, config: AdsConfig) = Unit
        override val interstitial: FullScreenAdProvider = FullScreenAdProvider { _, _ -> loadFullScreen() }
        override val rewarded: FullScreenAdProvider = interstitial
        override val appOpen: FullScreenAdProvider = interstitial
        override val banner: BannerProvider = BannerProvider { context, _, _, listener -> FakeBannerHandle(context, listener) }
        override val native: NativeAdProvider = NativeAdProvider { _, id, count -> loadNative(id, count) }
    }

    private inner class FakeBannerHandle(context: Context, private val listener: BannerListener) : BannerHandle {
        override val view: View = View(context)
        override val heightDp: Int = FAKE_BANNER_HEIGHT_DP

        override fun load() {
            val failure = loadFailure
            if (failure != null) {
                listener.onLoadFailed(failure)
            } else {
                listener.onLoaded()
                listener.onImpression()
            }
        }

        override fun pause() = Unit
        override fun resume() = Unit
        override fun destroy() = Unit
    }

    private companion object {
        const val FAKE_BANNER_HEIGHT_DP = 50
    }
}

/** 가짜 네이티브 광고. 레이아웃을 건드리지 않고 묶은 것으로 친다. */
internal class FakeNativeAdHandle(adUnitId: String) : NativeAdHandle {
    override val assets: NativeAdAssets =
        NativeAdAssets("Test ad", adUnitId, "Open", null, null, null, null, null)

    override fun bind(container: ViewGroup, views: NativeAdViews): Boolean = true

    override fun destroy() = Unit
}
