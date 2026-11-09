package net.plumt.ads.mobileads

/**
 * 한 번의 네이티브 요청(광고 [count] 장)이 언제 끝났는지 정한다. 광고 SDK 콜백과 분리한 순수 판단이라 단위 테스트한다.
 *
 * - 실패 콜백은 그 요청이 끝났다는 뜻이다 — 로더가 아직 불러오는 중이라고 답해도 곧바로 끝낸다
 *   (받은 광고가 있으면 그것으로, 없으면 실패로). 예전엔 이 경우 다시 불리지 않아 영원히 기다렸다
 * - 광고 콜백은 [count] 장을 다 받았거나 로더가 더는 불러오지 않으면 끝낸다
 * - 완료 콜백(Next-Gen `onAdLoadingCompleted`)은 받은 만큼으로 끝낸다. 한 장도 없으면 이유 없는 실패다
 * - 끝난 뒤 늦게 온 광고는 [Outcome.Late] 로 돌려 호출부가 놓게 한다
 */
internal class NativeLoadGate<T>(private val count: Int) {
    private val loaded = mutableListOf<T>()
    private var done = false

    /** 아직 넘기지 않고 들고 있는 광고. 취소 때 놓는다. */
    val pending: List<T> get() = if (done) emptyList() else loaded.toList()

    fun onAd(ad: T, stillLoading: Boolean): Outcome<T> {
        if (done) return Outcome.Late(ad)
        loaded += ad
        return if (loaded.size >= count || !stillLoading) finish(null, null) else Outcome.Pending
    }

    fun onFailed(code: Int, message: String): Outcome<T> {
        if (done) return Outcome.Pending
        return finish(code, message)
    }

    fun onCompleted(): Outcome<T> {
        if (done) return Outcome.Pending
        return finish(null, null)
    }

    private fun finish(code: Int?, message: String?): Outcome<T> {
        done = true
        if (loaded.isNotEmpty()) return Outcome.Success(loaded.toList())
        return Outcome.Failure(code, message)
    }

    sealed class Outcome<out T> {
        /** 더 기다린다. */
        object Pending : Outcome<Nothing>()

        /** 받은 광고로 끝낸다. */
        data class Success<T>(val ads: List<T>) : Outcome<T>()

        /** 한 장도 못 받고 끝났다. 코드가 없으면 SDK 가 이유를 주지 않은 것이다. */
        data class Failure(val code: Int?, val message: String?) : Outcome<Nothing>()

        /** 이미 끝난 뒤 온 광고 — 놓아야 한다. */
        data class Late<T>(val ad: T) : Outcome<T>()
    }
}
