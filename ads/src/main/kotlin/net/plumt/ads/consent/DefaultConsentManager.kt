package net.plumt.ads.consent

import android.app.Activity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.plumt.ads.AdsCallHandle
import net.plumt.ads.AdsException
import net.plumt.ads.defaultScope
import net.plumt.ads.launchCallback

internal class DefaultConsentManager(
    private val gateway: ConsentGateway,
    /** 콜백 형태 호출이 도는 곳. 클라이언트의 범위를 넘겨 [net.plumt.ads.AdsClient.close] 때 함께 멈춘다. */
    private val callbackScope: CoroutineScope = defaultScope(),
) : ConsentManager {

    private val state = MutableStateFlow(gateway.current())
    private val gathering = AtomicBoolean(false)

    override val snapshot: StateFlow<ConsentSnapshot> = state.asStateFlow()

    /**
     * 갱신 없이 상태를 다시 읽는다. UMP 는 이 프로세스에서 갱신을 요청하기 전에는 저장된 동의가 있어도
     * `canRequestAds = false` 를 돌려주므로, 앱 시작 직후에는 저장된 동의가 보이지 않는다.
     */
    fun refreshFromStorage() = publish()

    override suspend fun gather(activity: Activity): ConsentFormResult {
        if (!gathering.compareAndSet(false, true)) return ConsentFormResult.AlreadyInProgress
        try {
            // 갱신을 요청한 직후 한 번 내보낸다 — 지난 실행의 동의가 허용이면 응답 전에 초기화가 시작된다
            return await { done -> gateway.gather(activity, onUpdateRequested = ::publish, onDone = done) }
        } finally {
            gathering.set(false)
            publish()
        }
    }

    override fun gather(activity: Activity, onResult: (ConsentFormResult) -> Unit): AdsCallHandle =
        callbackScope.launchCallback({ gather(activity) }, onResult)

    override suspend fun showPrivacyOptions(activity: Activity): ConsentFormResult {
        try {
            return await { done -> gateway.showPrivacyOptions(activity, done) }
        } finally {
            publish()
        }
    }

    override fun showPrivacyOptions(activity: Activity, onResult: (ConsentFormResult) -> Unit): AdsCallHandle =
        callbackScope.launchCallback({ showPrivacyOptions(activity) }, onResult)

    private fun publish() {
        state.value = gateway.current()
    }

    private suspend fun await(start: ((AdsException?) -> Unit) -> Unit): ConsentFormResult =
        suspendCancellableCoroutine { cont ->
            start { error ->
                if (!cont.isActive) return@start
                cont.resume(if (error == null) ConsentFormResult.Completed else ConsentFormResult.Failed(error))
            }
        }
}
