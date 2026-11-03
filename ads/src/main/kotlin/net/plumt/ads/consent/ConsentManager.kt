package net.plumt.ads.consent

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import net.plumt.ads.AdsException

/** 사용자 동의(UMP) 상태. */
public enum class ConsentStatus {
    /** 아직 모른다(갱신 전). */
    Unknown,

    /** 동의가 필요 없는 지역·사용자. */
    NotRequired,

    /** 동의가 필요한데 아직 받지 못했다. */
    Required,

    /** 동의를 받았다. */
    Obtained,
    ;

    internal companion object {
        /**
         * UMP SDK 의 `ConsentInformation.ConsentStatus` 정수를 옮긴다.
         * SDK 가 모르는 값을 새로 내면 광고를 요청하지 않는 쪽인 [Unknown] 으로 본다.
         */
        fun fromUmp(value: Int): ConsentStatus = when (value) {
            UMP_NOT_REQUIRED -> NotRequired
            UMP_REQUIRED -> Required
            UMP_OBTAINED -> Obtained
            else -> Unknown
        }

        private const val UMP_NOT_REQUIRED = 1
        private const val UMP_REQUIRED = 2
        private const val UMP_OBTAINED = 3
    }
}

/**
 * 어느 한 순간의 동의 상태.
 *
 * @property status 동의 상태
 * @property canRequestAds 지금 광고를 요청해도 되는가. 지난 실행에서 저장된 값도 반영한다
 * @property privacyOptionsRequired 설정 화면에 "개인정보 옵션" 진입점을 보여야 하는가
 */
public data class ConsentSnapshot(
    val status: ConsentStatus,
    val canRequestAds: Boolean,
    val privacyOptionsRequired: Boolean,
) {
    public companion object {
        /** 갱신 전 상태. 광고를 요청하지 않는다. */
        public val Initial: ConsentSnapshot = ConsentSnapshot(ConsentStatus.Unknown, false, false)
    }
}

/**
 * 동의 수집과 개인정보 옵션 진입점. 기본 구현은 UMP 이고, [net.plumt.ads.AdsClient.consent] 로 얻는다.
 * 모든 호출은 메인 스레드에서 한다.
 */
public interface ConsentManager {
    /** 최신 동의 상태. 갱신·폼이 끝날 때마다 바뀐다. */
    public val snapshot: StateFlow<ConsentSnapshot>

    /**
     * 동의 정보를 갱신하고 필요하면 동의 폼을 띄운다. 끝나면 [snapshot] 이 갱신된다.
     * 이미 진행 중인 호출이 있으면 그 호출이 끝나길 기다리지 않고 바로 반환한다.
     *
     * @throws AdsException 갱신·폼 실패([net.plumt.ads.AdsErrorKind.Consent]). 지난 실행의 동의가 광고를 허용하면 [snapshot] 은 그대로 허용이다
     */
    public suspend fun gather(activity: Activity)

    /**
     * 개인정보 옵션 폼을 연다. [ConsentSnapshot.privacyOptionsRequired] 가 true 일 때만 부른다.
     *
     * @throws AdsException 폼 실패([net.plumt.ads.AdsErrorKind.Consent])
     */
    public suspend fun showPrivacyOptions(activity: Activity)
}
