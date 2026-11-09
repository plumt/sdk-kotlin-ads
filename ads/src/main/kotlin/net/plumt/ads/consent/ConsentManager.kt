package net.plumt.ads.consent

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import net.plumt.ads.AdsCallHandle
import net.plumt.ads.AdsException
import net.plumt.ads.launchCallback

/** 사용자 동의(UMP) 상태. 닫힌 집합이다. */
public sealed interface ConsentStatus {
    /** 아직 모른다(갱신 전). SDK 가 모르는 값을 새로 내도 광고를 요청하지 않는 쪽인 이 값으로 본다. */
    public data object Unknown : ConsentStatus

    /** 동의가 필요 없는 지역·사용자. */
    public data object NotRequired : ConsentStatus

    /** 동의가 필요한데 아직 받지 못했다. */
    public data object Required : ConsentStatus

    /** 동의를 받았다. */
    public data object Obtained : ConsentStatus
}

/** UMP SDK 의 `ConsentInformation.ConsentStatus` 정수를 옮긴다. 모르는 값은 [ConsentStatus.Unknown]. */
internal fun consentStatusFromUmp(value: Int): ConsentStatus = when (value) {
    UMP_NOT_REQUIRED -> ConsentStatus.NotRequired
    UMP_REQUIRED -> ConsentStatus.Required
    UMP_OBTAINED -> ConsentStatus.Obtained
    else -> ConsentStatus.Unknown
}

private const val UMP_NOT_REQUIRED = 1
private const val UMP_REQUIRED = 2
private const val UMP_OBTAINED = 3

/**
 * 어느 한 순간의 동의 상태.
 *
 * @property status 동의 상태
 * @property canRequestAds 지금 광고를 요청해도 되는가. 지난 실행에서 저장된 값도 반영한다
 * @property privacyOptionsRequired 설정 화면에 "개인정보 옵션" 진입점을 보여야 하는가
 */
public class ConsentSnapshot(
    public val status: ConsentStatus,
    public val canRequestAds: Boolean,
    public val privacyOptionsRequired: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is ConsentSnapshot && status == other.status && canRequestAds == other.canRequestAds &&
            privacyOptionsRequired == other.privacyOptionsRequired

    override fun hashCode(): Int = (status.hashCode() * 31 + canRequestAds.hashCode()) * 31 + privacyOptionsRequired.hashCode()

    override fun toString(): String =
        "ConsentSnapshot(status=$status, canRequestAds=$canRequestAds, privacyOptionsRequired=$privacyOptionsRequired)"

    public companion object {
        /** 갱신 전 상태. 광고를 요청하지 않는다. */
        @JvmField public val Initial: ConsentSnapshot = ConsentSnapshot(ConsentStatus.Unknown, false, false)
    }
}

/** 동의 폼·개인정보 옵션 폼 한 번의 결과. 실패도 값으로 온다. 닫힌 집합이다. */
public sealed interface ConsentFormResult {
    /** 끝났다(필요하면 폼을 띄웠다). 결과 상태는 [ConsentManager.snapshot] 에 있다. */
    public data object Completed : ConsentFormResult

    /** 다른 동의 수집이 진행 중이라 그 호출에 맡겼다. */
    public data object AlreadyInProgress : ConsentFormResult

    /**
     * 갱신·폼이 실패했다([net.plumt.ads.AdsErrorKind.Consent], `code` 는 UMP `FormError.ErrorCode`).
     * 오프라인은 정상 상태로 보고 다음 실행에 다시 시도한다. 지난 실행의 동의가 광고를 허용하면 [ConsentManager.snapshot] 은 그대로 허용이다.
     */
    public class Failed(public val error: AdsException) : ConsentFormResult {
        override fun equals(other: Any?): Boolean = other is Failed && error == other.error
        override fun hashCode(): Int = error.hashCode()
        override fun toString(): String = "Failed(error=${error.message})"
    }
}

/**
 * 동의 수집과 개인정보 옵션 진입점. 기본 구현은 UMP 이고, [net.plumt.ads.AdsClient.consent] 로 얻는다.
 * 테스트에서는 [FakeConsentManager] 를 쓴다. 모든 호출은 메인 스레드에서 한다.
 *
 * 같은 기능을 `suspend` 함수(기준)와 콜백(메인 스레드로 한 번, 취소 핸들 반환) 두 형태로 준다.
 */
public interface ConsentManager {
    /** 최신 동의 상태. 갱신·폼이 끝날 때마다 바뀐다. */
    public val snapshot: StateFlow<ConsentSnapshot>

    /**
     * 동의 정보를 갱신하고 필요하면 동의 폼을 띄운다. 끝나면 [snapshot] 이 갱신된다.
     * 이미 진행 중인 호출이 있으면 기다리지 않고 [ConsentFormResult.AlreadyInProgress] 를 돌려준다. 실패는 던지지 않고 값으로 온다.
     */
    public suspend fun gather(activity: Activity): ConsentFormResult

    /** [gather] 의 콜백 형태. [onResult] 는 메인 스레드에서 한 번 불린다. 핸들을 취소하면 불리지 않는다. */
    public fun gather(activity: Activity, onResult: (ConsentFormResult) -> Unit): AdsCallHandle =
        CoroutineScope(Dispatchers.Main.immediate).launchCallback({ gather(activity) }, onResult)

    /**
     * 개인정보 옵션 폼을 연다. [ConsentSnapshot.privacyOptionsRequired] 가 true 일 때만 부른다.
     * 결과는 [ConsentFormResult.Completed] 또는 [ConsentFormResult.Failed] 다.
     */
    public suspend fun showPrivacyOptions(activity: Activity): ConsentFormResult

    /** [showPrivacyOptions] 의 콜백 형태. [onResult] 는 메인 스레드에서 한 번 불린다. 핸들을 취소하면 불리지 않는다. */
    public fun showPrivacyOptions(activity: Activity, onResult: (ConsentFormResult) -> Unit): AdsCallHandle =
        CoroutineScope(Dispatchers.Main.immediate).launchCallback({ showPrivacyOptions(activity) }, onResult)
}
