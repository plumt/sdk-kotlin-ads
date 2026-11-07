package net.plumt.ads.compose

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import net.plumt.ads.AdErrorReason
import net.plumt.ads.AdEvent
import net.plumt.ads.AdEventListener
import net.plumt.ads.AdFormat
import net.plumt.ads.AdsErrorKind
import net.plumt.ads.AdsException
import net.plumt.ads.PreloadPolicy
import net.plumt.ads.awaitRetry
import net.plumt.ads.classifyOffline
import net.plumt.ads.network.NativeAdHandle

/**
 * 풀을 채우고 [refreshAfterMillis] 마다 다시 받는다. 한 번의 시도는 [ids] 를 차례로(대체 단위) 돌고,
 * 모두 실패하면 [retry] 의 분류별 규칙으로 기다렸다 다시 시도하거나 멈춘다.
 */
@Suppress("LongParameterList")
internal suspend fun NativeAdPool.runLoop(
    ids: List<String>,
    placement: String,
    events: AdEventListener,
    retry: PreloadPolicy,
    refreshAfterMillis: Long,
    online: () -> Flow<Boolean>,
    onLoadFailed: (AdsException) -> Unit,
    load: suspend (String) -> List<NativeAdHandle>,
) {
    if (ids.isEmpty()) return
    var retries = 0
    while (true) {
        state = AdSlotState.Loading
        var failure: AdsException? = null
        for (id in ids) {
            events.onEvent(AdEvent.Requested(AdFormat.Native, placement))
            try {
                val loaded = loadWithTimeout(retry.loadTimeoutMillis, placement) { load(id) }
                if (loaded.isEmpty()) {
                    throw AdsException(AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "네이티브 광고를 받지 못했습니다")
                }
                fill(loaded)
                failure = null
                events.onEvent(AdEvent.Loaded(AdFormat.Native, placement))
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val raw = e as? AdsException ?: AdsException(
                    AdsErrorKind.Load, AdsException.CODE_UNKNOWN, "공급자 예외: ${e.javaClass.simpleName}: ${e.message}", e,
                )
                // 오프라인이면 SDK 가 내부 오류로 알려도 네트워크 실패다 — 재시도가 연결 복구를 기다린다
                val error = raw.classifyOffline(online().first())
                failure = error
                events.onEvent(AdEvent.LoadFailed(AdFormat.Native, placement, error.code, error.message.orEmpty(), error.reason))
                // 연결이 없으면 대체 단위도 같은 이유로 실패한다
                if (error.reason == AdErrorReason.Network) break
            }
        }
        if (failure == null) {
            state = AdSlotState.Loaded
            retries = 0
            delay(refreshAfterMillis)
            continue
        }
        state = AdSlotState.Failed(failure)
        onLoadFailed(failure)
        if (!retry.awaitRetry(failure.reason, retries, online())) return
        retries++
    }
}

/**
 * [block] 을 [timeoutMillis] 안에 끝낸다(0 이면 제한 없음). 넘기면 [AdErrorReason.Timeout] 으로 던진다 —
 * 공급자가 실패 콜백을 끝내 주지 않아도 풀이 불러오는 중에 머물지 않고 대체 단위·재시도로 넘어간다.
 * 제한을 넘긴 뒤나 취소된 뒤에 돌아온 광고는 놓는다.
 */
internal suspend fun loadWithTimeout(
    timeoutMillis: Long,
    placement: String,
    block: suspend () -> List<NativeAdHandle>,
): List<NativeAdHandle> {
    if (timeoutMillis <= 0L) return block()
    var arrived: List<NativeAdHandle>? = null
    try {
        return withTimeout(timeoutMillis) { block().also { arrived = it } }
    } catch (e: TimeoutCancellationException) {
        arrived?.forEach { it.destroy() }
        throw AdsException(
            AdsErrorKind.Load, AdsException.CODE_TIMEOUT, "네이티브 불러오기가 ${timeoutMillis}ms 안에 끝나지 않았습니다: $placement", e,
        )
    } catch (e: CancellationException) {
        arrived?.forEach { it.destroy() }
        throw e
    }
}

