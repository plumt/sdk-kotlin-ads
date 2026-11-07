package net.plumt.ads.consent

import android.app.Activity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.ads.AdsException

internal class DefaultConsentManager(
    private val gateway: ConsentGateway,
) : ConsentManager {

    private val state = MutableStateFlow(gateway.current())
    private val gathering = AtomicBoolean(false)

    override val snapshot: StateFlow<ConsentSnapshot> = state.asStateFlow()

    /**
     * 갱신 없이 상태를 다시 읽는다. UMP 는 이 프로세스에서 갱신을 요청하기 전에는 저장된 동의가 있어도
     * `canRequestAds = false` 를 돌려주므로, 앱 시작 직후에는 저장된 동의가 보이지 않는다.
     */
    fun refreshFromStorage() = publish()

    override suspend fun gather(activity: Activity) {
        gatherIfIdle(activity)
    }

    /** [gather] 와 같다. 다른 호출이 진행 중이라 아무것도 하지 않았으면 false. */
    suspend fun gatherIfIdle(activity: Activity): Boolean {
        if (!gathering.compareAndSet(false, true)) return false
        try {
            // 갱신을 요청한 직후 한 번 내보낸다 — 지난 실행의 동의가 허용이면 응답 전에 초기화가 시작된다
            await { done -> gateway.gather(activity, onUpdateRequested = ::publish, onDone = done) }
        } finally {
            gathering.set(false)
            publish()
        }
        return true
    }

    override suspend fun showPrivacyOptions(activity: Activity) {
        try {
            await { done -> gateway.showPrivacyOptions(activity, done) }
        } finally {
            publish()
        }
    }

    private fun publish() {
        state.value = gateway.current()
    }

    private suspend fun await(start: ((AdsException?) -> Unit) -> Unit) {
        suspendCancellableCoroutine { cont ->
            start { error ->
                if (!cont.isActive) return@start
                if (error == null) cont.resume(Unit) else cont.resumeWithException(error)
            }
        }
    }
}
