package net.plumt.ads

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.plumt.ads.network.FullScreenAd
import net.plumt.ads.network.FullScreenAdCallbacks
import net.plumt.ads.network.FullScreenAdProvider

/** 전면형 광고 하나의 상태. 닫힌 집합이다. */
public sealed interface FullScreenAdState {
    /** 들고 있는 광고도 불러오는 중인 요청도 없다. */
    public data object Idle : FullScreenAdState

    /** 불러오는 중. */
    public data object Loading : FullScreenAdState

    /** 불러온 광고가 있고 [FullScreenAdController.show] 로 보일 수 있다. */
    public data object Ready : FullScreenAdState

    /** 화면에 떠 있다. */
    public data object Showing : FullScreenAdState
}

/** [FullScreenAdController.show] 의 결과. 닫힌 집합이다. */
public sealed interface ShowResult {
    /** 표시를 시작했다. 끝나는 시점은 [FullScreenAdCallbacks.onDismissed] 로 안다. */
    public data object Started : ShowResult

    /** 보일 광고가 없다(불러오는 중이거나 불러오기 실패). 불러오기를 시작해 둔다. 실패 원인은 [FullScreenAdController.lastError]. */
    public data object NotReady : ShowResult

    /** 정책이 막았다. */
    public class Suppressed(public val reason: SuppressReason) : ShowResult {
        override fun equals(other: Any?): Boolean = other is Suppressed && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "Suppressed(reason=$reason)"
    }

    /**
     * 표시가 곧바로 실패했다. 분류는 [AdsException.reason] — 이미 쓴 광고([AdErrorReason.AlreadyUsed]),
     * 만료([AdErrorReason.Expired] — 들고 있던 광고가 [PreloadPolicy.expireAfterMillis] 를 넘긴 경우 포함),
     * 앱이 전면에 없음([AdErrorReason.AppInBackground]) 등. SDK 가 알린 실패면 [FullScreenAdCallbacks.onShowFailed] 도 불렸다.
     * 컨트롤러는 다음 광고를 미리 불러 둔다.
     */
    public class Failed(public val error: AdsException) : ShowResult {
        override fun equals(other: Any?): Boolean = other is Failed && error == other.error
        override fun hashCode(): Int = error.hashCode()
        override fun toString(): String = "Failed(error=${error.message})"
    }
}

/**
 * 미리 불러오기·재시도 정책. 불변이고 [builder] 로 만든다(기본값은 [Default]). 재시도는 실패 분류([AdErrorReason])마다 다르다:
 *
 * | 분류 | 재시도 |
 * |---|---|
 * | [AdErrorReason.NoFill] | [noFillBackoffMillis] 부터 두 배씩 길게 |
 * | [AdErrorReason.Network] | [retryOnNetworkRestore] 면 연결이 돌아온 직후, 아니면 [retryBackoffMillis] 부터 두 배씩 |
 * | [AdErrorReason.retryable] 이 false(잘못된 요청·앱 ID·설정) | 하지 않는다 |
 * | 그 밖(내부·시간 초과·모름) | [retryBackoffMillis] 부터 두 배씩 |
 *
 * 대체 단위가 있으면 한 번의 시도는 주 단위 → 대체 단위 순으로 돌고, 모두 실패해야 한 번 실패로 센다.
 *
 * @property autoPreload 사용 가능해지면(초기화·동의·스위치) 자동으로 한 장 불러 둔다. 기본 true
 * @property reloadAfterDismiss 광고가 닫히면 다음 광고를 미리 불러 둔다. 기본 true
 * @property maxRetries 불러오기가 실패했을 때 다시 시도하는 횟수(처음 시도 제외). 기본 2
 * @property retryBackoffMillis 첫 재시도 대기(기본 5초). 시도마다 두 배가 되고 `Long.MAX_VALUE` 에서 멈춘다(넘쳐서 음수가 되지 않는다)
 * @property expireAfterMillis 불러온 광고를 이 시간이 지나면 버린다(기본 1시간. SDK 광고는 오래되면 쓸 수 없다)
 * @property noFillBackoffMillis 채울 광고가 없을 때의 첫 재시도 대기(기본 1분). 짧게 두면 요청만 늘고 채움률은 오르지 않는다
 * @property retryOnNetworkRestore 네트워크 오류면 연결이 돌아올 때까지 기다렸다 다시 시도한다. 기본 true
 * @property loadTimeoutMillis 불러오기 한 번의 제한 시간(기본 30초). 넘기면 [AdErrorReason.Timeout]. 0 이면 제한 없음
 */
public class PreloadPolicy private constructor(builder: Builder) {
    public val autoPreload: Boolean = builder.autoPreload
    public val reloadAfterDismiss: Boolean = builder.reloadAfterDismiss
    public val maxRetries: Int = builder.maxRetries
    public val retryBackoffMillis: Long = builder.retryBackoffMillis
    public val expireAfterMillis: Long = builder.expireAfterMillis
    public val noFillBackoffMillis: Long = builder.noFillBackoffMillis
    public val retryOnNetworkRestore: Boolean = builder.retryOnNetworkRestore
    public val loadTimeoutMillis: Long = builder.loadTimeoutMillis

    /** 이 값에서 시작하는 빌더. */
    public fun toBuilder(): Builder = Builder().also { b ->
        b.autoPreload = autoPreload
        b.reloadAfterDismiss = reloadAfterDismiss
        b.maxRetries = maxRetries
        b.retryBackoffMillis = retryBackoffMillis
        b.expireAfterMillis = expireAfterMillis
        b.noFillBackoffMillis = noFillBackoffMillis
        b.retryOnNetworkRestore = retryOnNetworkRestore
        b.loadTimeoutMillis = loadTimeoutMillis
    }

    private fun parts(): List<Any> = listOf(
        autoPreload, reloadAfterDismiss, maxRetries, retryBackoffMillis, expireAfterMillis, noFillBackoffMillis,
        retryOnNetworkRestore, loadTimeoutMillis,
    )

    override fun equals(other: Any?): Boolean = other is PreloadPolicy && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String =
        "PreloadPolicy(autoPreload=$autoPreload, reloadAfterDismiss=$reloadAfterDismiss, maxRetries=$maxRetries, " +
            "retryBackoffMillis=$retryBackoffMillis, expireAfterMillis=$expireAfterMillis, noFillBackoffMillis=$noFillBackoffMillis, " +
            "retryOnNetworkRestore=$retryOnNetworkRestore, loadTimeoutMillis=$loadTimeoutMillis)"

    /** [PreloadPolicy] 를 만든다. 스레드 안전하지 않다. */
    public class Builder internal constructor() {
        internal var autoPreload = true
        internal var reloadAfterDismiss = true
        internal var maxRetries = 2
        internal var retryBackoffMillis = 5_000L
        internal var expireAfterMillis = 3_600_000L
        internal var noFillBackoffMillis = 60_000L
        internal var retryOnNetworkRestore = true
        internal var loadTimeoutMillis = 30_000L

        /** [PreloadPolicy.autoPreload] */
        public fun autoPreload(value: Boolean): Builder = apply { autoPreload = value }

        /** [PreloadPolicy.reloadAfterDismiss] */
        public fun reloadAfterDismiss(value: Boolean): Builder = apply { reloadAfterDismiss = value }

        /** [PreloadPolicy.maxRetries] */
        public fun maxRetries(value: Int): Builder = apply { maxRetries = value }

        /** [PreloadPolicy.retryBackoffMillis] */
        public fun retryBackoffMillis(value: Long): Builder = apply { retryBackoffMillis = value }

        /** [PreloadPolicy.expireAfterMillis] */
        public fun expireAfterMillis(value: Long): Builder = apply { expireAfterMillis = value }

        /** [PreloadPolicy.noFillBackoffMillis] */
        public fun noFillBackoffMillis(value: Long): Builder = apply { noFillBackoffMillis = value }

        /** [PreloadPolicy.retryOnNetworkRestore] */
        public fun retryOnNetworkRestore(value: Boolean): Builder = apply { retryOnNetworkRestore = value }

        /** [PreloadPolicy.loadTimeoutMillis] */
        public fun loadTimeoutMillis(value: Long): Builder = apply { loadTimeoutMillis = value }

        /** @throws IllegalArgumentException 값이 음수이거나 [expireAfterMillis] 가 0 이하일 때 */
        public fun build(): PreloadPolicy {
            require(maxRetries >= 0) { "maxRetries 는 0 이상이어야 합니다: $maxRetries" }
            require(retryBackoffMillis >= 0) { "retryBackoffMillis 는 0 이상이어야 합니다: $retryBackoffMillis" }
            require(expireAfterMillis > 0) { "expireAfterMillis 는 1 이상이어야 합니다: $expireAfterMillis" }
            require(noFillBackoffMillis >= 0) { "noFillBackoffMillis 는 0 이상이어야 합니다: $noFillBackoffMillis" }
            require(loadTimeoutMillis >= 0) { "loadTimeoutMillis 는 0 이상이어야 합니다: $loadTimeoutMillis" }
            return PreloadPolicy(this)
        }
    }

    public companion object {
        /** 기본값. */
        @JvmField public val Default: PreloadPolicy = PreloadPolicy(Builder())

        /** 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/** 불러오기 실패 뒤 다음 할 일. [PreloadPolicy.nextStep] 이 정한다. 직접 광고 뷰를 다루는 호출부도 같은 규칙을 쓸 수 있다. 닫힌 집합이다. */
public sealed interface RetryStep {
    /** 다시 시도하지 않는다. */
    public data object Stop : RetryStep

    /** 연결이 돌아오면 곧바로 다시 시도한다. */
    public data object AwaitNetwork : RetryStep

    /** [millis] 뒤 다시 시도한다. */
    public class After(public val millis: Long) : RetryStep {
        override fun equals(other: Any?): Boolean = other is After && millis == other.millis
        override fun hashCode(): Int = millis.hashCode()
        override fun toString(): String = "After(millis=$millis)"
    }
}

/**
 * [retriesDone] 번 재시도한 뒤 [reason] 으로 또 실패했을 때 다음 할 일. 표는 [PreloadPolicy] 에 있다.
 *
 * @throws IllegalArgumentException [retriesDone] 이 음수일 때
 */
public fun PreloadPolicy.nextStep(reason: AdErrorReason, retriesDone: Int): RetryStep {
    require(retriesDone >= 0) { "retriesDone 은 0 이상이어야 합니다: $retriesDone" }
    if (!reason.retryable || retriesDone >= maxRetries) return RetryStep.Stop
    return when (reason) {
        AdErrorReason.NoFill -> RetryStep.After(doubled(noFillBackoffMillis, retriesDone))
        AdErrorReason.Network ->
            if (retryOnNetworkRestore) RetryStep.AwaitNetwork else RetryStep.After(doubled(retryBackoffMillis, retriesDone))
        else -> RetryStep.After(doubled(retryBackoffMillis, retriesDone))
    }
}

/**
 * [retriesDone] 번 재시도한 뒤 [reason] 으로 또 실패했을 때, [nextStep] 만큼 기다린다.
 * [PreloadPolicy.retryOnNetworkRestore] 면 오프라인 동안은 다시 요청하지 않는다 — [RetryStep.After] 의 대기가 끝나도
 * [online] 이 true 가 될 때까지 더 기다리고, [RetryStep.AwaitNetwork] 는 연결이 돌아오는 즉시 끝난다.
 * [online] 은 구독하면 곧바로 현재 값을 내야 한다([AdConnectivity.online]).
 *
 * @return 다시 시도하면 true, [RetryStep.Stop] 이면 기다리지 않고 false
 * @throws IllegalArgumentException [retriesDone] 이 음수일 때
 */
public suspend fun PreloadPolicy.awaitRetry(reason: AdErrorReason, retriesDone: Int, online: Flow<Boolean>): Boolean {
    when (val step = nextStep(reason, retriesDone)) {
        RetryStep.Stop -> return false
        RetryStep.AwaitNetwork -> Unit
        is RetryStep.After -> delay(step.millis)
    }
    if (retryOnNetworkRestore) online.first { it }
    return true
}

/** [base] 를 [times] 번 두 배. 넘치면 `Long.MAX_VALUE`. */
internal fun doubled(base: Long, times: Int): Long {
    var v = base
    repeat(times) { v = if (v > Long.MAX_VALUE / 2) Long.MAX_VALUE else v * 2 }
    return v
}

/** 빈도 제한 기본 정책을 어디까지 공유하나. 닫힌 집합이다. */
public sealed interface FrequencyScope {
    /** 같은 형식의 단위가 한 정책을 나눈다(전면 1·2 중 하나를 보이면 둘 다 간격을 지킨다). 기본. */
    public data object PerFormat : FrequencyScope

    /** 단위마다 따로 센다. 기록 키는 단위 이름에 묶인다. */
    public data object PerUnit : FrequencyScope
}

/**
 * [AdsClient.fullScreenController] 의 선택 인자. 불변이고 [builder] 로 만든다(기본값은 [Default]).
 *
 * @property placement 분석 사건의 배치 이름. `null`(기본)이면 단위 이름
 * @property policy 빈도 제한. `null`(기본)이면 [AdsClient.defaultPolicy] (`unit`, [frequencyScope]). 직접 넘기는 정책은 앱에 하나 두고 공유한다
 * @property preload 미리 불러오기·재시도. 기본 [PreloadPolicy.Default]
 * @property fallbacks 주 단위가 실패하면 차례로 시도할 같은 형식의 단위. 모드와 맞지 않는 것은 건너뛴다
 * @property frequencyScope [policy] 가 null 일 때 기본 정책을 형식별로 공유할지 단위별로 둘지. 기본 [FrequencyScope.PerFormat]
 */
public class FullScreenAdOptions private constructor(builder: Builder) {
    public val placement: String? = builder.placement
    public val policy: AdFrequencyPolicy? = builder.policy
    public val preload: PreloadPolicy = builder.preload
    public val fallbacks: List<AdUnit> = builder.fallbacks.toList()
    public val frequencyScope: FrequencyScope = builder.frequencyScope

    /** 이 값에서 시작하는 빌더. */
    public fun toBuilder(): Builder = Builder().also { b ->
        b.placement = placement
        b.policy = policy
        b.preload = preload
        b.fallbacks = fallbacks
        b.frequencyScope = frequencyScope
    }

    override fun equals(other: Any?): Boolean =
        other is FullScreenAdOptions && placement == other.placement && policy == other.policy && preload == other.preload &&
            fallbacks == other.fallbacks && frequencyScope == other.frequencyScope

    override fun hashCode(): Int = listOf(placement, policy, preload, fallbacks, frequencyScope).hashCode()

    override fun toString(): String =
        "FullScreenAdOptions(placement=$placement, policy=${policy?.javaClass?.simpleName}, preload=$preload, " +
            "fallbacks=${fallbacks.map { it.name }}, frequencyScope=$frequencyScope)"

    /** [FullScreenAdOptions] 를 만든다. 스레드 안전하지 않다. */
    public class Builder internal constructor() {
        internal var placement: String? = null
        internal var policy: AdFrequencyPolicy? = null
        internal var preload: PreloadPolicy = PreloadPolicy.Default
        internal var fallbacks: List<AdUnit> = emptyList()
        internal var frequencyScope: FrequencyScope = FrequencyScope.PerFormat

        /** [FullScreenAdOptions.placement] */
        public fun placement(placement: String): Builder = apply { this.placement = placement }

        /** [FullScreenAdOptions.policy] */
        public fun policy(policy: AdFrequencyPolicy): Builder = apply { this.policy = policy }

        /** [FullScreenAdOptions.preload] */
        public fun preload(preload: PreloadPolicy): Builder = apply { this.preload = preload }

        /** [FullScreenAdOptions.fallbacks] */
        public fun fallbacks(units: List<AdUnit>): Builder = apply { fallbacks = units.toList() }

        /** [FullScreenAdOptions.frequencyScope] */
        public fun frequencyScope(scope: FrequencyScope): Builder = apply { frequencyScope = scope }

        /** 만든다. 값 검사는 [AdsClient.fullScreenController] 가 단위와 함께 한다. */
        public fun build(): FullScreenAdOptions = FullScreenAdOptions(this)
    }

    public companion object {
        /** 모두 기본값. */
        @JvmField public val Default: FullScreenAdOptions = FullScreenAdOptions(Builder())

        /** 빌더. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/**
 * 전면·보상형·앱 오프닝 광고 하나(배치 하나)의 미리 불러오기·빈도 제한·표시를 맡는다.
 * [AdsClient.fullScreenController] 로 만든다. 모든 메서드는 메인 스레드에서 부른다.
 *
 * - 광고가 쓸 수 없는 상태(스위치 꺼짐·동의 없음·초기화 전)이면 불러오지 않고 [show] 는 [ShowResult.Suppressed] 다
 * - 광고 단위가 모드와 맞지 않으면([misconfigured]) 아무것도 불러오지 않고 [show] 는 [SuppressReason.Misconfigured] 다
 * - 불러오기 실패는 분류별로 재시도하고([PreloadPolicy]), 대체 단위가 있으면 그 단위로 넘어간다. 마지막 실패는 [lastError]
 * - 스위치가 꺼지면 들고 있던 광고를 즉시 버린다
 * - 시작·종료 시점에 자동으로 보이지 않는다 — [show] 는 항상 호출부가 부른다
 * - [Activity] 는 [show] 호출 동안만 쓰고 붙들지 않는다. 화면을 떠나는 쪽이 [destroy] 를 부른다
 * - 빈도 상태는 [policy] 에 있다. 기본 정책은 [AdsClient.defaultPolicy] 가 주는 공유 인스턴스라 컨트롤러를 다시 만들어도 이어진다
 * - 표시 중에 [destroy] 되면(화면 회전·재생성) 광고는 닫힐 때까지 남지만 [show] 에 넘긴 콜백은 더 부르지 않는다 —
 *   사라진 화면으로 보내지 않기 위해서다. 보상은 앱 수명의 [AdsClient.events] 로 받은 [AdEvent.RewardEarned] 로 처리한다
 */
public class FullScreenAdController internal constructor(
    public val format: AdFormat,
    public val placement: String,
    /** 시도 순서대로의 (단위, 이 모드의 ID). 첫 항목이 주 단위다. 비었으면 설정 오류로 아무것도 불러오지 않는다. */
    private val chain: List<Pair<AdUnit, String>>,
    /** 단위 ID 하나로 광고 한 장을 불러온다. 네트워크 공급자에 컨텍스트를 묶어 넘긴다. */
    private val loader: suspend (String) -> FullScreenAd,
    private val policy: AdFrequencyPolicy,
    private val preload: PreloadPolicy,
    private val available: StateFlow<Boolean>,
    private val events: AdEventListener,
    private val clock: AdClock,
    private val scope: CoroutineScope,
    private val coordinator: FullScreenCoordinator = FullScreenCoordinator(clock, 0),
    private val connectivity: AdConnectivity = AdConnectivity.AlwaysOnline,
) {
    @Suppress("LongParameterList")
    internal constructor(
        format: AdFormat,
        placement: String,
        chain: List<Pair<AdUnit, String>>,
        context: Context,
        provider: FullScreenAdProvider,
        policy: AdFrequencyPolicy,
        preload: PreloadPolicy,
        available: StateFlow<Boolean>,
        events: AdEventListener,
        clock: AdClock,
        scope: CoroutineScope,
        coordinator: FullScreenCoordinator = FullScreenCoordinator(clock, 0),
        connectivity: AdConnectivity = AdConnectivity.AlwaysOnline,
    ) : this(
        format, placement, chain, { id -> provider.load(context, id) }, policy, preload, available, events, clock, scope,
        coordinator, connectivity,
    )

    /** 광고 단위가 모드와 맞지 않아 아무것도 불러오지 않는다. 원인은 [AdsClient.configurationErrors]. */
    public val misconfigured: Boolean get() = chain.isEmpty()

    private val lastErrorFlow = MutableStateFlow<AdsException?>(null)

    /** 마지막 불러오기·표시 실패(분류는 [AdsException.reason]). 불러오기에 성공하면 null. */
    public val lastError: StateFlow<AdsException?> = lastErrorFlow.asStateFlow()

    /** 지금 들고 있는 광고를 불러온 단위. 대체 단위로 채웠으면 그 단위다. */
    public var loadedUnit: AdUnit? = null
        private set

    private val stateFlow = MutableStateFlow<FullScreenAdState>(FullScreenAdState.Idle)

    /** 현재 상태. */
    public val state: StateFlow<FullScreenAdState> = stateFlow.asStateFlow()

    private var ad: FullScreenAd? = null
    private var loadedAt = 0L
    private var loadJob: Job? = null
    private var destroyed = false
    private var showCallbacks: FullScreenAdCallbacks? = null

    private val watchJob: Job = scope.launch {
        available.collect { ok ->
            if (ok) {
                if (preload.autoPreload) preload()
            } else {
                drop()
            }
        }
    }

    /** 광고 한 장을 미리 불러 둔다. 이미 있거나 불러오는 중이거나 쓸 수 없는 상태면 아무것도 하지 않는다. */
    public fun preload() {
        if (destroyed || misconfigured || !available.value || stateFlow.value != FullScreenAdState.Idle) return
        stateFlow.value = FullScreenAdState.Loading
        loadJob = scope.launch { loadWithRetry() }
    }

    /** 콜백 없이 보인다. 결과는 반환값과 [AdsClient.events] 로 안다. */
    public fun show(activity: Activity): ShowResult = show(activity, NoCallbacks)

    /**
     * 불러 둔 광고를 보인다.
     *
     * @param callbacks 이 표시에만 걸리는 콜백. 보상형 보상은 [FullScreenAdCallbacks.onRewardEarned] 로 온다.
     *   컨트롤러가 destroy 되면 더 불리지 않는다
     */
    public fun show(activity: Activity, callbacks: FullScreenAdCallbacks): ShowResult {
        check(!destroyed) { "destroy 된 컨트롤러입니다: $placement" }
        if (misconfigured) return suppress(SuppressReason.Misconfigured)
        if (!available.value) return suppress(SuppressReason.Unavailable)
        if (stateFlow.value == FullScreenAdState.Ready && clock.nowMillis() - loadedAt >= preload.expireAfterMillis) {
            drop()
            val expired = AdsException(
                AdsErrorKind.Show, AdsException.CODE_EXPIRED,
                "불러온 지 ${preload.expireAfterMillis}ms 가 지나 버렸습니다: $placement",
            )
            lastErrorFlow.value = expired
            events.onEvent(AdEvent.ShowFailed(format, placement, expired.code, expired.message.orEmpty(), expired.reason))
            preload()
            return ShowResult.Failed(expired)
        }
        val ready = ad
        if (stateFlow.value != FullScreenAdState.Ready || ready == null) {
            preload()
            return ShowResult.NotReady
        }
        coordinator.blockReason(format)?.let { return suppress(it) }
        if (!policy.canShow(placement)) return suppress(SuppressReason.FrequencyCapped)

        stateFlow.value = FullScreenAdState.Showing
        coordinator.onStart()
        showCallbacks = callbacks
        var returned = false
        var syncFailure: AdsException? = null
        var ended = false
        fun end(): FullScreenAdCallbacks? {
            if (ended) return null
            ended = true
            finishShowing()
            coordinator.onEnd(format)
            return showCallbacks.also { showCallbacks = null }
        }
        ready.show(activity, object : FullScreenAdCallbacks {
            override fun onShown() {
                policy.onShown(placement)
                events.onEvent(AdEvent.Shown(format, placement))
                showCallbacks?.onShown()
            }

            override fun onImpression() {
                events.onEvent(AdEvent.Impression(format, placement))
                showCallbacks?.onImpression()
            }

            override fun onClicked() {
                events.onEvent(AdEvent.Clicked(format, placement))
                showCallbacks?.onClicked()
            }

            override fun onRewardEarned(type: String, amount: Int) {
                events.onEvent(AdEvent.RewardEarned(format, placement, type, amount))
                showCallbacks?.onRewardEarned(type, amount)
            }

            override fun onDismissed() {
                if (ended) return
                val cb = end()
                events.onEvent(AdEvent.Dismissed(format, placement))
                cb?.onDismissed()
                if (preload.reloadAfterDismiss) preload()
            }

            override fun onShowFailed(error: AdsException) {
                if (ended) return
                if (!returned) syncFailure = error
                lastErrorFlow.value = error
                val cb = end()
                events.onEvent(AdEvent.ShowFailed(format, placement, error.code, error.message.orEmpty(), error.reason))
                cb?.onShowFailed(error)
                if (preload.reloadAfterDismiss) preload()
            }
        })
        returned = true
        return syncFailure?.let { ShowResult.Failed(it) } ?: ShowResult.Started
    }

    /** 감시와 불러오기를 멈추고 광고를 버린다. 이후 [show] 는 던진다. 여러 번 불러도 된다. */
    public fun destroy() {
        destroyed = true
        showCallbacks = null
        watchJob.cancel()
        drop()
    }

    private fun finishShowing() {
        ad?.destroy()
        ad = null
        stateFlow.value = FullScreenAdState.Idle
    }

    private fun suppress(reason: SuppressReason): ShowResult {
        events.onEvent(AdEvent.Suppressed(format, placement, reason))
        return ShowResult.Suppressed(reason)
    }

    private fun drop() {
        loadJob?.cancel()
        loadJob = null
        // 표시 중인 광고는 닫힐 때까지 건드리지 않는다
        if (stateFlow.value == FullScreenAdState.Showing) return
        ad?.destroy()
        ad = null
        stateFlow.value = FullScreenAdState.Idle
    }

    private suspend fun loadWithRetry() {
        var retries = 0
        while (true) {
            val failure = loadChainOnce() ?: return
            if (!preload.awaitRetry(failure.reason, retries, connectivity.online())) {
                stateFlow.value = FullScreenAdState.Idle
                return
            }
            retries++
        }
    }

    /** 주 단위부터 차례로 한 번씩 불러온다. 하나라도 채우면 null, 모두 실패하면 마지막 실패. */
    private suspend fun loadChainOnce(): AdsException? {
        var last: AdsException? = null
        for ((unit, id) in chain) {
            events.onEvent(AdEvent.Requested(format, placement))
            try {
                val loaded = loadWithTimeout(id)
                if (!available.value || destroyed) {
                    loaded.destroy()
                    stateFlow.value = FullScreenAdState.Idle
                    return null
                }
                ad = loaded
                loadedUnit = unit
                loadedAt = clock.nowMillis()
                lastErrorFlow.value = null
                stateFlow.value = FullScreenAdState.Ready
                events.onEvent(AdEvent.Loaded(format, placement))
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 공급자가 SDK 예외 대신 다른 예외를 던져도 메인 스레드 크래시로 번지지 않게 감싼다
                val raw = e as? AdsException ?: AdsException(
                    AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "공급자 예외: ${e.javaClass.simpleName}: ${e.message}", e,
                )
                // 오프라인이면 SDK 가 내부 오류로 알려도 네트워크 실패다 — 재시도가 연결 복구를 기다린다
                val failure = raw.classifyOffline(connectivity.online().first())
                last = failure
                lastErrorFlow.value = failure
                events.onEvent(AdEvent.LoadFailed(format, placement, failure.code, failure.message.orEmpty(), failure.reason))
                // 연결이 없으면 대체 단위도 같은 이유로 실패한다 — 요청을 아낀다
                if (failure.reason == AdErrorReason.Network) break
            }
        }
        return last
    }

    private suspend fun loadWithTimeout(id: String): FullScreenAd {
        if (preload.loadTimeoutMillis == 0L) return loader(id)
        var arrived: FullScreenAd? = null
        try {
            return withTimeout(preload.loadTimeoutMillis) { loader(id).also { arrived = it } }
        } catch (e: TimeoutCancellationException) {
            arrived?.destroy()
            throw AdsException(
                AdsErrorKind.Load, AdsException.CODE_TIMEOUT,
                "불러오기가 ${preload.loadTimeoutMillis}ms 안에 끝나지 않았습니다: $placement", e,
            )
        } catch (e: CancellationException) {
            // 취소를 무시하고 늦게 돌려준 광고도 남기지 않는다
            arrived?.destroy()
            throw e
        }
    }
}

private object NoCallbacks : FullScreenAdCallbacks
