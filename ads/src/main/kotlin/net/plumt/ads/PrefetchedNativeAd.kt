package net.plumt.ads

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.plumt.ads.network.NativeAdHandle
import net.plumt.ads.network.NativeAdProvider

/**
 * 앱 수명 동안 네이티브 광고 한 장을 미리 받아 두는 자리. 종료 확인 팝업처럼 **뜨는 순간** 광고가 있어야 하는 곳에 쓴다.
 * [AdsClient.prefetchedNativeAd] 로 얻고, 같은 단위·배치는 같은 인스턴스다(화면을 다시 들어와도 새로 요청하지 않는다).
 * 모든 메서드는 메인 스레드에서 부른다.
 *
 * - [prefetch] 를 한 번 부르면 그 뒤로는 쓸 수 있게 될 때마다(스위치·동의·초기화) 알아서 채운다
 * - 받은 지 [ttlMillis] 가 지난 광고는 [fresh] 가 돌려주지 않고, 다음 [prefetch] 에서 버리고 다시 받는다
 * - 쓴 광고는 [consume] 으로 돌려준다 — 버리고 다음 것을 받는다
 * - 쓸 수 없게 되면(광고 끔·동의 철회) 들고 있던 광고를 버린다
 * - 단위가 모드와 맞지 않으면 아무것도 요청하지 않는다. 대체 단위가 있으면 실패한 단위 다음 것으로 넘어간다
 */
public class PrefetchedNativeAd internal constructor(
    public val unit: AdUnit,
    public val placement: String,
    public val ttlMillis: Long,
    private val chain: List<String>,
    /** 단위 ID 와 개수로 광고를 불러온다. 네트워크 공급자에 컨텍스트를 묶어 넘긴다. */
    private val loader: suspend (String, Int) -> List<NativeAdHandle>,
    private val available: StateFlow<Boolean>,
    private val events: AdEventListener,
    private val clock: AdClock,
    private val scope: CoroutineScope,
    private val loadTimeoutMillis: Long = DEFAULT_LOAD_TIMEOUT_MILLIS,
) {
    @Suppress("LongParameterList")
    internal constructor(
        unit: AdUnit,
        placement: String,
        ttlMillis: Long,
        chain: List<String>,
        context: Context,
        provider: NativeAdProvider,
        available: StateFlow<Boolean>,
        events: AdEventListener,
        clock: AdClock,
        scope: CoroutineScope,
        loadTimeoutMillis: Long = DEFAULT_LOAD_TIMEOUT_MILLIS,
    ) : this(
        unit, placement, ttlMillis, chain, { id, count -> provider.load(context, id, count) }, available, events, clock, scope,
        loadTimeoutMillis,
    )

    private val adFlow = MutableStateFlow<NativeAdHandle?>(null)
    private val errorFlow = MutableStateFlow<AdsException?>(null)
    private var storedAt = 0L
    private var wanted = false
    private var loadJob: Job? = null

    /** 들고 있는 광고. 만료 여부는 [fresh]·[isFresh] 로 판단한다. */
    public val ad: StateFlow<NativeAdHandle?> = adFlow.asStateFlow()

    /** 마지막 불러오기 실패. 성공하면 null. */
    public val lastError: StateFlow<AdsException?> = errorFlow.asStateFlow()

    /** 불러오는 중인가. */
    public val loading: Boolean get() = loadJob?.isActive == true

    init {
        scope.launch {
            available.collect { ok -> if (!ok) clear() else if (wanted) prefetch() }
        }
    }

    /** 만료되지 않은 광고. 없으면 null. 상태를 바꾸지 않아 컴포지션에서 불러도 된다. */
    public fun fresh(): NativeAdHandle? = adFlow.value?.takeIf { isFresh(it) }

    /** [handle] 이 지금 들고 있는, 만료되지 않은 광고인가. */
    public fun isFresh(handle: NativeAdHandle): Boolean =
        handle === adFlow.value && clock.nowMillis() - storedAt < ttlMillis

    /** 쓸 수 있는 광고가 없으면 받는다. 이미 받는 중이거나 쓸 수 없는 상태면 아무것도 하지 않는다(쓸 수 있게 되면 그때 받는다). */
    public fun prefetch() {
        wanted = true
        if (chain.isEmpty() || !available.value || loading) return
        if (fresh() != null) return
        destroyHeld()
        loadJob = scope.launch { load() }
    }

    /** 팝업에서 쓴 [handle] 을 버리고 다음 광고를 받는다. 들고 있는 광고가 아니면 받기만 한다. */
    public fun consume(handle: NativeAdHandle) {
        if (handle === adFlow.value) destroyHeld()
        prefetch()
    }

    /** 들고 있는 광고를 버리고 받던 요청을 멈춘다. 자동 채움도 멈춘다 — 다시 [prefetch] 하면 재개된다. */
    public fun reset() {
        wanted = false
        clear()
    }

    private fun clear() {
        loadJob?.cancel()
        loadJob = null
        destroyHeld()
    }

    private fun destroyHeld() {
        adFlow.value?.destroy()
        adFlow.value = null
    }

    private suspend fun load() {
        for (id in chain) {
            events.onEvent(AdEvent.Requested(AdFormat.Native, placement))
            try {
                val loaded = loadWithTimeout(id)
                val first = loaded.firstOrNull()
                loaded.drop(1).forEach { it.destroy() }
                if (first == null) continue
                if (!available.value) {
                    first.destroy()
                    return
                }
                destroyHeld()
                adFlow.value = first
                storedAt = clock.nowMillis()
                errorFlow.value = null
                events.onEvent(AdEvent.Loaded(AdFormat.Native, placement))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? AdsException ?: AdsException(
                    AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "공급자 예외: ${e.javaClass.simpleName}: ${e.message}", e,
                )
                errorFlow.value = failure
                events.onEvent(
                    AdEvent.LoadFailed(AdFormat.Native, placement, failure.code, failure.message.orEmpty(), failure.reason),
                )
            }
        }
    }

    /** 공급자가 끝내 응답하지 않아도 [loading] 에 머물지 않게 제한 시간을 둔다. 넘기면 다음 대체 단위로 넘어간다. */
    private suspend fun loadWithTimeout(id: String): List<NativeAdHandle> {
        if (loadTimeoutMillis <= 0L) return loader(id, 1)
        var arrived: List<NativeAdHandle>? = null
        try {
            return withTimeout(loadTimeoutMillis) { loader(id, 1).also { arrived = it } }
        } catch (e: TimeoutCancellationException) {
            arrived?.forEach { it.destroy() }
            throw AdsException(
                AdsErrorKind.Load, AdsException.CODE_TIMEOUT, "네이티브 불러오기가 ${loadTimeoutMillis}ms 안에 끝나지 않았습니다: $placement", e,
            )
        } catch (e: CancellationException) {
            arrived?.forEach { it.destroy() }
            throw e
        }
    }

    public companion object {
        /** 기본 보관 시간: 1시간. 네이티브 광고는 오래되면 노출이 계측되지 않는다. */
        public const val DEFAULT_TTL_MILLIS: Long = 3_600_000L

        /** 불러오기 한 번의 제한 시간. [PreloadPolicy.loadTimeoutMillis] 기본값과 같다. */
        internal const val DEFAULT_LOAD_TIMEOUT_MILLIS: Long = 30_000L
    }
}
