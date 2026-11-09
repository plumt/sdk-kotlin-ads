package net.plumt.ads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 콜백 형태 호출의 취소 핸들. [cancel] 하면 진행 중인 작업을 멈추고 결과 콜백을 부르지 않는다.
 * 이미 결과를 받았거나 이미 취소했으면 아무것도 하지 않는다.
 */
public interface AdsCallHandle {
    /** 작업을 멈추고 결과 콜백을 부르지 않게 한다. 여러 번 불러도 된다. */
    public fun cancel()

    /** 결과를 아직 기다리는 중인가. 결과를 받았거나 취소했으면 false. */
    public val isActive: Boolean
}

/** [block] 을 [scope] 에서 돌리고 결과를 [onResult] 로 한 번 준다. 취소됐으면 부르지 않는다. */
internal fun <T> CoroutineScope.launchCallback(block: suspend () -> T, onResult: (T) -> Unit): AdsCallHandle {
    val job = launch {
        val result = block()
        ensureActive()
        onResult(result)
    }
    return JobHandle(job)
}

internal class JobHandle(private val job: Job, private val onCancel: () -> Unit = {}) : AdsCallHandle {
    override fun cancel() {
        onCancel()
        job.cancel()
    }

    override val isActive: Boolean get() = job.isActive

    override fun toString(): String = "AdsCallHandle(active=${job.isActive})"
}
