package net.plumt.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.plumt.ads.consent.ConsentFormResult
import net.plumt.ads.consent.ConsentGateway
import net.plumt.ads.consent.ConsentManager
import net.plumt.ads.consent.DefaultConsentManager
import net.plumt.ads.consent.UmpConsentGateway
import net.plumt.ads.mobileads.MobileAdsNetwork
import net.plumt.ads.network.AdNetwork

internal class DefaultAdsClient(
    private val context: Context,
    override val config: AdsConfig,
    enabledSource: AdsEnabledSource,
    override val events: AdEventListener,
    override val network: AdNetwork,
    gateway: ConsentGateway,
    private val clock: AdClock,
    private val scope: CoroutineScope,
    private val frequencyStore: AdFrequencyStore = AdFrequencyStore.inMemory(),
    private val wallClock: AdClock = clock,
    debuggable: Boolean = true,
    override val units: AdUnitRegistry = AdUnitRegistry.Empty,
    override val connectivity: AdConnectivity = AdConnectivity.AlwaysOnline,
) : AdsClient {

    override val mode: AdsMode = config.mode.resolve(debuggable)

    // 네트워크·동의에 넘기는 값: 모드를 정하고, 운영 모드면 개발 전용 설정을 지운다
    private val effective: Pair<AdsConfig, Set<AdsWarning>> = config.effectiveFor(mode)
    private val effectiveConfig: AdsConfig = effective.first

    private val consentManager = DefaultConsentManager(gateway, scope)
    override val consent: ConsentManager = consentManager

    private val enabledState = MutableStateFlow(false)
    private val switchKnown = MutableStateFlow(false)
    private val sourceError = MutableStateFlow<AdsException?>(null)
    override val adsEnabled: StateFlow<Boolean> = enabledState.asStateFlow()
    override val enabledSourceError: StateFlow<AdsException?> = sourceError.asStateFlow()

    private val initialized = MutableStateFlow(false)
    private val initError = MutableStateFlow<AdsException?>(null)

    override val initializationError: StateFlow<AdsException?> = initError.asStateFlow()
    private var initializing = false

    private val configErrors = MutableStateFlow<List<AdsException>>(emptyList())
    private val configErrorKeys = HashSet<String>()
    override val configurationErrors: StateFlow<List<AdsException>> = configErrors.asStateFlow()

    override val warnings: StateFlow<Set<AdsWarning>> = MutableStateFlow(effective.second).asStateFlow()

    override val adsAvailable: StateFlow<Boolean> =
        combine(adsEnabled, consent.snapshot, initialized) { enabled, snap, init ->
            enabled && snap.canRequestAds && init
        }.stateIn(scope, SharingStarted.Eagerly, false)

    private val coordinator = FullScreenCoordinator(clock, config.appOpenCooldownMillis)

    // 형식별 공유 정책. 컨트롤러가 다시 만들어져도 같은 인스턴스를 쓴다. 시작 유예는 클라이언트 생성 시점부터 센다
    private val policies = DefaultPolicies(config, wallClock, frequencyStore)
    private val prefetched = HashMap<Pair<AdUnit, String>, PrefetchedNativeAd>()

    init {
        effective.second.forEach { warning ->
            AdsLog.warn("운영 모드라 개발 전용 설정을 무시합니다: $warning — 운영 빌드 설정에서 지우세요")
        }
        if (mode == AdsMode.Production) {
            units.all.forEach { unit -> unit.problemFor(mode)?.let { recordConfiguration(it) } }
        }
        // 앱 ID 는 동의보다 먼저 본다. 없으면 UMP 가 먼저 실패해 초기화(같은 검사)까지 가지 않는다 — 새 설치에서도 드러나게 한다.
        // 광고는 초기화 전이라 그대로 나가지 않는다. 같은 원인이 초기화에서 다시 와도 목록에는 한 번만 든다
        (network as? MobileAdsNetwork)?.appIdProblem(context, mode)?.let { problem ->
            initError.value = problem
            recordConfiguration(problem)
        }
        scope.launch {
            enabledSource.enabled
                .catch { e ->
                    // 조용히 대체하지 않는다: 꺼짐으로 닫고 원인을 노출한다
                    sourceError.value = AdsException(
                        AdsErrorKind.EnabledSource, AdsException.CODE_UNKNOWN,
                        "광고 스위치 Flow 실패: ${e.javaClass.simpleName}: ${e.message}", e,
                    )
                    emit(false)
                }
                .collect { value ->
                    enabledState.value = value
                    switchKnown.value = true
                }
        }
        // 스위치가 나중에 켜지면 그때 초기화한다. 꺼져 있는 동안은 SDK 도 건드리지 않는다
        scope.launch {
            combine(adsEnabled, consent.snapshot) { enabled, snap -> enabled && snap.canRequestAds }
                .collect { ok -> if (ok) initializeNow() }
        }
    }

    override fun adUnitId(unit: AdUnit, placement: String): String? = try {
        unit.idFor(mode)
    } catch (e: AdsException) {
        if (recordConfiguration(e)) {
            events.onEvent(AdEvent.Misconfigured(unit.format, placement, e.code, e.message.orEmpty()))
        }
        null
    }

    /** 처음 보는 원인이면 목록에 더하고 true. */
    private fun recordConfiguration(e: AdsException): Boolean {
        if (!configErrorKeys.add("${e.code}|${e.message}")) return false
        configErrors.value = configErrors.value + e
        AdsLog.error("광고 설정 오류: ${e.message}")
        return true
    }

    override fun initializeIfConsented() {
        consentManager.refreshFromStorage()
        maybeInitialize()
    }

    override suspend fun gatherConsent(activity: Activity, switchTimeoutMillis: Long): ConsentGatherResult {
        require(switchTimeoutMillis >= 0) { "switchTimeoutMillis 는 0 이상이어야 합니다: $switchTimeoutMillis" }
        if (!switchKnown.value) {
            withTimeoutOrNull(switchTimeoutMillis) { switchKnown.first { it } }
                ?: return ConsentGatherResult.SkippedSwitchTimeout
        }
        // 광고를 끈 사용자에게 동의 폼을 띄우지 않는다
        if (!adsEnabled.value) return ConsentGatherResult.SkippedAdsDisabled
        return when (val form = consentManager.gather(activity)) {
            ConsentFormResult.AlreadyInProgress -> ConsentGatherResult.AlreadyInProgress
            is ConsentFormResult.Failed -> ConsentGatherResult.Failed(form.error)
            ConsentFormResult.Completed -> {
                // 동의가 그대로여서 상태 흐름이 다시 방출하지 않아도, 지난 초기화 실패를 여기서 다시 시도한다
                maybeInitialize()
                ConsentGatherResult.Completed
            }
        }
    }

    override fun gatherConsent(
        activity: Activity,
        switchTimeoutMillis: Long,
        onResult: (ConsentGatherResult) -> Unit,
    ): AdsCallHandle {
        require(switchTimeoutMillis >= 0) { "switchTimeoutMillis 는 0 이상이어야 합니다: $switchTimeoutMillis" }
        return scope.launchCallback({ gatherConsent(activity, switchTimeoutMillis) }, onResult)
    }

    override fun gatherConsentOnFirstResume(
        application: Application,
        onResult: (ConsentGatherResult) -> Unit,
    ): AdsCallHandle {
        val done = Job(scope.coroutineContext[Job])
        val callbacks = FirstResumeConsent(application, done, onResult)
        application.registerActivityLifecycleCallbacks(callbacks)
        return JobHandle(done) { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }

    private inner class FirstResumeConsent(
        private val application: Application,
        private val done: CompletableJob,
        private val onResult: (ConsentGatherResult) -> Unit,
    ) : Application.ActivityLifecycleCallbacks {
        private var started = false

        override fun onActivityResumed(activity: Activity) {
            if (started || activity.isFinishing || !done.isActive) return
            started = true
            application.unregisterActivityLifecycleCallbacks(this)
            scope.launch(done) {
                val result = gatherConsent(activity)
                ensureActive()
                onResult(result)
            }.invokeOnCompletion { done.complete() }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun defaultPolicy(format: AdFormat): AdFrequencyPolicy = policies.forFormat(format)

    override fun defaultPolicy(unit: AdUnit, scope: FrequencyScope): AdFrequencyPolicy = policies.forUnit(unit, scope)

    private fun maybeInitialize() {
        if (adsEnabled.value && consent.snapshot.value.canRequestAds) initializeNow()
    }

    private fun initializeNow() {
        if (initialized.value || initializing) return
        initializing = true
        scope.launch {
            try {
                network.initialize(context, effectiveConfig)
                initialized.value = true
                initError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 교체한 네트워크가 다른 예외를 던져도 메인 스레드 크래시로 번지지 않게 감싸 원인을 보존한다
                val failure = e as? AdsException ?: AdsException(
                    AdsErrorKind.Initialization, AdsException.CODE_UNKNOWN,
                    "네트워크 초기화 예외: ${e.javaClass.simpleName}: ${e.message}", e,
                )
                initError.value = failure
                if (failure.kind == AdsErrorKind.Configuration) recordConfiguration(failure)
            } finally {
                initializing = false
            }
        }
    }

    /** [unit] 과 [fallbacks] 중 이 모드에서 쓸 수 있는 것의 (단위, ID). 형식이 다르면 던진다. */
    private fun resolveChain(unit: AdUnit, fallbacks: List<AdUnit>, placement: String): List<Pair<AdUnit, String>> {
        fallbacks.forEach { it.requireFormat(unit.format) }
        return (listOf(unit) + fallbacks).distinct().mapNotNull { u -> adUnitId(u, placement)?.let { u to it } }
    }

    override fun fullScreenController(unit: AdUnit, options: FullScreenAdOptions): FullScreenAdController {
        unit.requireFormat(AdFormat.Interstitial, AdFormat.Rewarded, AdFormat.AppOpen)
        val provider = when (unit.format) {
            AdFormat.Interstitial -> network.interstitial
            AdFormat.Rewarded -> network.rewarded
            else -> network.appOpen
        }
        val placement = options.placement ?: unit.name
        require(placement.isNotBlank()) { "placement 가 비었습니다: name=${unit.name}" }
        val chain = resolveChain(unit, options.fallbacks, placement)
        return FullScreenAdController(
            unit.format, placement, chain, context, provider, options.policy ?: defaultPolicy(unit, options.frequencyScope),
            options.preload, adsAvailable, events, clock, scope, coordinator, connectivity,
        )
    }

    override fun prefetchedNativeAd(
        unit: AdUnit,
        placement: String,
        ttlMillis: Long,
        fallbacks: List<AdUnit>,
    ): PrefetchedNativeAd {
        unit.requireFormat(AdFormat.Native)
        require(placement.isNotBlank()) { "placement 가 비었습니다: name=${unit.name}" }
        require(ttlMillis >= 1) { "ttlMillis 는 1 이상이어야 합니다: $ttlMillis" }
        fallbacks.forEach { it.requireFormat(AdFormat.Native) }
        return prefetched.getOrPut(unit to placement) {
            val chain = resolveChain(unit, fallbacks, placement).map { it.second }
            PrefetchedNativeAd(unit, placement, ttlMillis, chain, context, network.native, adsAvailable, events, clock, scope)
        }
    }

    override fun close() = scope.cancel()

    companion object {
        fun create(context: Context, config: AdsConfig): AdsClient {
            val debuggable = context.isAppDebuggable()
            val umpConfig = config.effectiveFor(config.mode.resolve(debuggable)).first
            return DefaultAdsClient(
                context, config, config.enabledSource, config.events, config.network ?: MobileAdsNetwork(),
                UmpConsentGateway(context, umpConfig), AdClock.Default, defaultScope(),
                config.frequencyStore ?: AdFrequencyStore.sharedPreferences(context), AdClock.Wall,
                debuggable, config.units, config.connectivity ?: AdConnectivity.system(context),
            )
        }
    }
}

/**
 * 형식별·단위별 기본 빈도 정책. 같은 형식(단위)은 늘 같은 인스턴스를 돌려준다. 메인 스레드에서만 쓴다.
 * 시작 유예는 이 객체를 만든 시점부터 센다.
 */
internal class DefaultPolicies(
    private val config: AdsConfig,
    private val clock: AdClock,
    private val store: AdFrequencyStore,
) {
    private val shared: Map<AdFormat, AdFrequencyPolicy> = mapOf(
        AdFormat.Interstitial to newPolicy(AdFormat.Interstitial, ""),
        AdFormat.AppOpen to newPolicy(AdFormat.AppOpen, ""),
        AdFormat.Rewarded to AdFrequencyPolicy.Unlimited,
    )
    private val perUnit = HashMap<String, AdFrequencyPolicy>()

    fun forFormat(format: AdFormat): AdFrequencyPolicy =
        requireNotNull(shared[format]) { "전면형 형식이 아닙니다: $format (Interstitial·Rewarded·AppOpen 만 허용)" }

    fun forUnit(unit: AdUnit, scope: FrequencyScope): AdFrequencyPolicy {
        val policy = forFormat(unit.format)
        return when (scope) {
            FrequencyScope.PerFormat -> policy
            FrequencyScope.PerUnit ->
                if (policy === AdFrequencyPolicy.Unlimited) {
                    policy
                } else {
                    perUnit.getOrPut("${unit.format}|${unit.name}") { newPolicy(unit.format, ".unit.${unit.name}") }
                }
        }
    }

    private fun newPolicy(format: AdFormat, keySuffix: String): AdFrequencyPolicy = when (format) {
        AdFormat.Interstitial -> IntervalFrequencyPolicy.builder()
            .clock(clock).store(store).key(KEY_INTERSTITIAL + keySuffix).build()
        AdFormat.AppOpen -> AdFrequencyPolicy.all(
            IntervalFrequencyPolicy.builder()
                .minIntervalMillis(config.appOpenMinIntervalMillis).initialDelayMillis(0)
                .clock(clock).store(store).key(KEY_APP_OPEN + keySuffix).build(),
            LaunchCountFrequencyPolicy(config.appOpenSkipFirstLaunches, store, KEY_APP_OPEN_LAUNCHES + keySuffix),
        )
        else -> AdFrequencyPolicy.Unlimited
    }

    companion object {
        const val KEY_INTERSTITIAL = "net.plumt.ads.interstitial"
        const val KEY_APP_OPEN = "net.plumt.ads.app_open"
        const val KEY_APP_OPEN_LAUNCHES = "net.plumt.ads.app_open.launches"
    }
}

/**
 * 한 클라이언트의 전면형 광고가 겹치지 않게 조율한다. 메인 스레드에서만 쓴다.
 *
 * - 하나라도 표시 중이면 다른 표시는 [SuppressReason.AnotherAdShowing]
 * - 앱 오프닝은 다른 전면형이 닫힌 뒤 [appOpenCooldownMillis] 안에는 [SuppressReason.FrequencyCapped]
 */
internal class FullScreenCoordinator(
    private val clock: AdClock,
    private val appOpenCooldownMillis: Long,
) {
    private var showing = 0
    private var lastOtherEndedAt: Long? = null

    fun blockReason(format: AdFormat): SuppressReason? {
        if (showing > 0) return SuppressReason.AnotherAdShowing
        if (format == AdFormat.AppOpen) {
            val ended = lastOtherEndedAt
            if (ended != null && clock.nowMillis() - ended < appOpenCooldownMillis) return SuppressReason.FrequencyCapped
        }
        return null
    }

    fun onStart() {
        showing++
    }

    fun onEnd(format: AdFormat) {
        showing = (showing - 1).coerceAtLeast(0)
        if (format != AdFormat.AppOpen) lastOtherEndedAt = clock.nowMillis()
    }
}
