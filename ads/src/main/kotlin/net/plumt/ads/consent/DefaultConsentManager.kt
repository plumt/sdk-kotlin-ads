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

    /** 갱신 없이 저장된 값으로 상태를 다시 읽는다(앱 시작 때). */
    fun refreshFromStorage() = publish()

    override suspend fun gather(activity: Activity) {
        if (!gathering.compareAndSet(false, true)) return
        try {
            await { done -> gateway.gather(activity, done) }
        } finally {
            gathering.set(false)
            publish()
        }
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
