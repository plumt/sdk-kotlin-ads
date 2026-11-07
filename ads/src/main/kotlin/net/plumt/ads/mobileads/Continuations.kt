package net.plumt.ads.mobileads

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation

/**
 * [value] 로 재개한다. 이미 취소됐거나, 재개한 뒤 디스패치 전에 취소돼 값이 버려지면 [release] 로 놓는다.
 * SDK 가 콜백으로 넘긴 광고 객체가 취소 경합으로 새지 않게 한다.
 */
internal fun <T> CancellableContinuation<T>.resumeOrRelease(value: T, release: (T) -> Unit) {
    if (isActive) resume(value) { _, dropped, _ -> release(dropped) } else release(value)
}
