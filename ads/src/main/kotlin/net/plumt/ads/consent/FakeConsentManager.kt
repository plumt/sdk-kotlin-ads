package net.plumt.ads.consent

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.plumt.ads.AdsCallHandle

/**
 * 테스트용 [ConsentManager]. UMP 없이 동의 상태와 폼 결과를 정해 둔다. [net.plumt.ads.FakeAdsClient.consent] 가 이것이다.
 *
 * - [gather] 는 [gatherResult] 를 돌려주고, 그 값이 [ConsentFormResult.Completed] 면 [snapshotAfterGather] 를 상태로 올린다
 * - [showPrivacyOptions] 는 [privacyOptionsResult] 를 돌려주고, 완료면 [snapshotAfterPrivacyOptions] 를 올린다
 * - 콜백 형태는 **호출한 스레드에서 곧바로** 결과를 준다(메인 디스패처가 없는 JVM 테스트에서도 돈다). 돌려준 핸들은 이미 끝난 상태다
 * - 넘긴 [Activity] 는 쓰지 않는다
 *
 * 스레드 안전하다.
 *
 * @param initial 처음 상태
 */
public class FakeConsentManager(initial: ConsentSnapshot) : ConsentManager {

    /** 동의를 받아 광고를 요청할 수 있는 상태([Allowed])로 시작한다. */
    public constructor() : this(Allowed)

    private val state = MutableStateFlow(initial)

    @Volatile private var gathers = 0

    @Volatile private var privacyShows = 0

    override val snapshot: StateFlow<ConsentSnapshot> = state.asStateFlow()

    /** [gather] 가 돌려줄 값. 기본 [ConsentFormResult.Completed]. */
    @Volatile
    public var gatherResult: ConsentFormResult = ConsentFormResult.Completed

    /** [gather] 가 완료로 끝날 때 올릴 상태. null(기본)이면 그대로 둔다. */
    @Volatile
    public var snapshotAfterGather: ConsentSnapshot? = null

    /** [showPrivacyOptions] 가 돌려줄 값. 기본 [ConsentFormResult.Completed]. */
    @Volatile
    public var privacyOptionsResult: ConsentFormResult = ConsentFormResult.Completed

    /** [showPrivacyOptions] 가 완료로 끝날 때 올릴 상태(예: 동의 철회). null(기본)이면 그대로 둔다. */
    @Volatile
    public var snapshotAfterPrivacyOptions: ConsentSnapshot? = null

    /** [gather] 가 불린 횟수(콜백 형태 포함). */
    public val gatherCalls: Int get() = gathers

    /** [showPrivacyOptions] 가 불린 횟수(콜백 형태 포함). */
    public val privacyOptionsCalls: Int get() = privacyShows

    /** 상태를 바로 바꾼다(지난 실행의 동의가 읽힌 경우, 다른 화면에서 철회한 경우 등). */
    public fun setSnapshot(snapshot: ConsentSnapshot) {
        state.value = snapshot
    }

    override suspend fun gather(activity: Activity): ConsentFormResult = gatherNow()

    override fun gather(activity: Activity, onResult: (ConsentFormResult) -> Unit): AdsCallHandle {
        onResult(gatherNow())
        return CompletedHandle
    }

    override suspend fun showPrivacyOptions(activity: Activity): ConsentFormResult = privacyNow()

    override fun showPrivacyOptions(activity: Activity, onResult: (ConsentFormResult) -> Unit): AdsCallHandle {
        onResult(privacyNow())
        return CompletedHandle
    }

    /** Activity 없이 [gather] 와 같은 일을 한다. */
    internal fun gatherNow(): ConsentFormResult {
        synchronized(this) { gathers++ }
        val result = gatherResult
        if (result == ConsentFormResult.Completed) snapshotAfterGather?.let { state.value = it }
        return result
    }

    private fun privacyNow(): ConsentFormResult {
        synchronized(this) { privacyShows++ }
        val result = privacyOptionsResult
        if (result == ConsentFormResult.Completed) snapshotAfterPrivacyOptions?.let { state.value = it }
        return result
    }

    override fun toString(): String = "FakeConsentManager(snapshot=${state.value}, gatherCalls=$gathers)"

    public companion object {
        /** 동의를 받았고 광고를 요청할 수 있다. 개인정보 옵션 진입점은 필요 없다. */
        @JvmField public val Allowed: ConsentSnapshot = ConsentSnapshot(ConsentStatus.Obtained, true, false)

        /** 동의가 필요한데 받지 못해 광고를 요청할 수 없다. */
        @JvmField public val Denied: ConsentSnapshot = ConsentSnapshot(ConsentStatus.Required, false, false)
    }
}

/** 곧바로 끝난 호출의 핸들. */
internal object CompletedHandle : AdsCallHandle {
    override fun cancel() = Unit
    override val isActive: Boolean get() = false
    override fun toString(): String = "AdsCallHandle(active=false)"
}
