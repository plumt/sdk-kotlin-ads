package net.plumt.ads

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.plumt.ads.network.FullScreenAd
import net.plumt.ads.network.FullScreenAdCallbacks
import net.plumt.ads.network.FullScreenAdProvider

/** 전면형 광고 하나의 상태. */
public enum class FullScreenAdState {
    /** 들고 있는 광고도 불러오는 중인 요청도 없다. */
    Idle,
    Loading,

    /** 불러온 광고가 있고 [FullScreenAdController.show] 로 보일 수 있다. */
    Ready,
    Showing,
}

/** [FullScreenAdController.show] 의 결과. */
public sealed class ShowResult {
    /** 표시를 시작했다. 끝나는 시점은 [FullScreenAdCallbacks.onDismissed] 로 안다. */
    public data object Started : ShowResult()

    /** 보일 광고가 없다(불러오는 중이거나 실패·만료). 필요하면 불러오기를 시작해 둔다. */
    public data object NotReady : ShowResult()

    /** 정책이 막았다. */
    public data class Suppressed(val reason: SuppressReason) : ShowResult()
}

/**
 * 미리 불러오기 정책.
 *
 * @property autoPreload 사용 가능해지면(초기화·동의·스위치) 자동으로 한 장 불러 둔다
 * @property reloadAfterDismiss 광고가 닫히면 다음 광고를 미리 불러 둔다
 * @property maxRetries 불러오기가 실패했을 때 다시 시도하는 횟수(처음 시도 제외)
 * @property retryBackoffMillis 첫 재시도 대기. 시도마다 두 배가 된다
 * @property expireAfterMillis 불러온 광고를 이 시간이 지나면 버린다(SDK 광고는 오래되면 쓸 수 없다)
 * @throws IllegalArgumentException 값이 음수이거나 [expireAfterMillis] 가 0 이하일 때
 */
public data class PreloadPolicy(
    val autoPreload: Boolean = true,
    val reloadAfterDismiss: Boolean = true,
    val maxRetries: Int = 2,
    val retryBackoffMillis: Long = 5_000L,
    val expireAfterMillis: Long = 3_600_000L,
) {
    init {
        require(maxRetries >= 0) { "maxRetries 는 0 이상이어야 합니다: $maxRetries" }
        require(retryBackoffMillis >= 0) { "retryBackoffMillis 는 0 이상이어야 합니다: $retryBackoffMillis" }
        require(expireAfterMillis > 0) { "expireAfterMillis 는 1 이상이어야 합니다: $expireAfterMillis" }
    }
}

/**
 * 전면·보상형·앱 오프닝 광고 하나(배치 하나)의 미리 불러오기·빈도 제한·표시를 맡는다.
 * [AdsClient.fullScreenController] 로 만든다. 모든 메서드는 메인 스레드에서 부른다.
 *
 * - 광고가 쓸 수 없는 상태(스위치 꺼짐·동의 없음·초기화 전)이면 불러오지 않고 [show] 는 [ShowResult.Suppressed] 다
 * - 스위치가 꺼지면 들고 있던 광고를 즉시 버린다
 * - 시작·종료 시점에 자동으로 보이지 않는다 — [show] 는 항상 호출부가 부른다
 * - [Activity] 는 [show] 호출 동안만 쓰고 붙들지 않는다. 화면을 떠나는 쪽이 [destroy] 를 부른다
 */
public class FullScreenAdController internal constructor(
    public val format: AdFormat,
    public val placement: String,
    private val adUnitId: String,
    private val context: Context,
    private val provider: FullScreenAdProvider,
    private val policy: AdFrequencyPolicy,
    private val preload: PreloadPolicy,
    private val available: StateFlow<Boolean>,
    private val events: AdEventListener,
    private val clock: AdClock,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow(FullScreenAdState.Idle)

    /** 현재 상태. */
    public val state: StateFlow<FullScreenAdState> = stateFlow.asStateFlow()

    private var ad: FullScreenAd? = null
    private var loadedAt = 0L
    private var loadJob: Job? = null
    private var destroyed = false

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
        if (destroyed || !available.value || stateFlow.value != FullScreenAdState.Idle) return
        stateFlow.value = FullScreenAdState.Loading
        loadJob = scope.launch { loadWithRetry() }
    }

    /**
     * 불러 둔 광고를 보인다.
     *
     * @param callbacks 이 표시에만 걸리는 콜백. 보상형 보상은 [FullScreenAdCallbacks.onRewardEarned] 로 온다
     */
    public fun show(activity: Activity, callbacks: FullScreenAdCallbacks = object : FullScreenAdCallbacks {}): ShowResult {
        check(!destroyed) { "destroy 된 컨트롤러입니다: $placement" }
        if (!available.value) return suppress(SuppressReason.Unavailable)
        if (stateFlow.value == FullScreenAdState.Ready && clock.nowMillis() - loadedAt >= preload.expireAfterMillis) {
            drop()
            preload()
            return ShowResult.NotReady
        }
        val ready = ad
        if (stateFlow.value != FullScreenAdState.Ready || ready == null) {
            preload()
            return ShowResult.NotReady
        }
        if (!policy.canShow(placement)) return suppress(SuppressReason.FrequencyCapped)

        stateFlow.value = FullScreenAdState.Showing
        ready.show(activity, object : FullScreenAdCallbacks {
            override fun onShown() {
                policy.onShown(placement)
                events.onEvent(AdEvent.Shown(format, placement))
                callbacks.onShown()
            }

            override fun onImpression() {
                events.onEvent(AdEvent.Impression(format, placement))
                callbacks.onImpression()
            }

            override fun onClicked() {
                events.onEvent(AdEvent.Clicked(format, placement))
                callbacks.onClicked()
            }

            override fun onRewardEarned(type: String, amount: Int) {
                events.onEvent(AdEvent.RewardEarned(format, placement, type, amount))
                callbacks.onRewardEarned(type, amount)
            }

            override fun onDismissed() {
                finishShowing()
                events.onEvent(AdEvent.Dismissed(format, placement))
                callbacks.onDismissed()
                if (preload.reloadAfterDismiss) preload()
            }

            override fun onShowFailed(error: AdsException) {
                finishShowing()
                events.onEvent(AdEvent.ShowFailed(format, placement, error.code, error.message.orEmpty()))
                callbacks.onShowFailed(error)
                if (preload.reloadAfterDismiss) preload()
            }
        })
        return ShowResult.Started
    }

    /** 감시와 불러오기를 멈추고 광고를 버린다. 이후 [show] 는 던진다. 여러 번 불러도 된다. */
    public fun destroy() {
        destroyed = true
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
        var attempt = 0
        var backoff = preload.retryBackoffMillis
        while (true) {
            events.onEvent(AdEvent.Requested(format, placement))
            try {
                val loaded = provider.load(context, adUnitId)
                if (!available.value || destroyed) {
                    loaded.destroy()
                    stateFlow.value = FullScreenAdState.Idle
                    return
                }
                ad = loaded
                loadedAt = clock.nowMillis()
                stateFlow.value = FullScreenAdState.Ready
                events.onEvent(AdEvent.Loaded(format, placement))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 공급자가 SDK 예외 대신 다른 예외를 던져도 메인 스레드 크래시로 번지지 않게 감싼다
                val failure = e as? AdsException ?: AdsException(
                    AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "공급자 예외: ${e.javaClass.simpleName}: ${e.message}", e,
                )
                events.onEvent(AdEvent.LoadFailed(format, placement, failure.code, failure.message.orEmpty()))
                if (attempt >= preload.maxRetries) {
                    stateFlow.value = FullScreenAdState.Idle
                    return
                }
                attempt++
                delay(backoff)
                backoff *= 2
            }
        }
    }
}
